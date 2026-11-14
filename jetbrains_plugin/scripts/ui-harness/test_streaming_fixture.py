import http.client
import json
import tempfile
import threading
import unittest
from unittest.mock import patch
from pathlib import Path

from streaming_fixture import history_role, make_server


class StreamingFixtureTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.log = Path(self.directory.name) / 'provider.jsonl'
        self.server = make_server(0, self.log, chunks=3, interval=0.03)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.connection = http.client.HTTPConnection('127.0.0.1', self.server.server_port, timeout=5)

    def tearDown(self):
        self.connection.close()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.directory.cleanup()

    def post(self, body, token='focus-fixture-local-only'):
        self.connection.request('POST', '/v1/chat/completions', json.dumps(body),
                                {'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
        return self.connection.getresponse()

    def test_two_incremental_streams_finish_with_native_completion(self):
        request = {'stream': True, 'messages': [], 'tools': [
            {'type': 'function', 'function': {'name': 'attempt_completion'}}]}
        for turn in (1, 2):
            paused = threading.Event()
            release = threading.Event()

            def pause_stream(_interval):
                paused.set()
                if not release.wait(5):
                    raise TimeoutError('Test did not release the fixture stream')

            # Hold the server after its first content chunk. Receiving both the
            # role and content before release proves incremental delivery without
            # depending on sleep durations or the host's clock resolution.
            with patch('streaming_fixture.time.sleep', side_effect=pause_stream):
                try:
                    response = self.post(request)
                    self.assertEqual(200, response.status)
                    self.assertEqual('text/event-stream', response.getheader('Content-Type'))
                    first = json.loads(response.readline().decode()[6:])
                    self.assertEqual('assistant', first['choices'][0]['delta']['role'])
                    self.assertEqual(b'\n', response.readline())
                    update = json.loads(response.readline().decode()[6:])
                    self.assertEqual(f'History update 1 for turn {turn}. ',
                                     update['choices'][0]['delta']['content'])
                    self.assertTrue(paused.wait(5), 'Server did not pause during the stream')
                finally:
                    # Also unblock the request handler if any assertion fails.
                    release.set()
                events = [update] + [json.loads(line[6:]) for line in response.read().decode().splitlines()
                                     if line.startswith('data: {')]
            updates = [e for e in events if e['choices'][0]['delta'].get('content')]
            self.assertEqual(3, len(updates))
            tool = events[-2]['choices'][0]['delta']['tool_calls'][0]
            self.assertEqual('attempt_completion', tool['function']['name'])
            self.assertIn(str(turn), json.loads(tool['function']['arguments'])['result'])
            self.assertEqual('tool_calls', events[-1]['choices'][0]['finish_reason'])
        records = [json.loads(line) for line in self.log.read_text().splitlines()]
        self.assertEqual(2, len([e for e in records if e['event'] == 'completed']))

    def test_rejects_non_streaming_and_missing_completion_tool(self):
        for request in ({'stream': False}, {'stream': True, 'tools': []}):
            response = self.post(request)
            self.assertEqual(400, response.status)
            response.read()
        self.assertEqual(0, self.server.requests)

    def test_rejects_invalid_local_token(self):
        response = self.post({'stream': True}, token='wrong')
        self.assertEqual(401, response.status)
        response.read()
        self.assertEqual(0, self.server.requests)

    def test_health_and_model_discovery(self):
        self.connection.request('GET', '/health')
        response = self.connection.getresponse()
        self.assertEqual(b'ready', response.read())
        self.connection.request('GET', '/v1/models')
        response = self.connection.getresponse()
        self.assertEqual('focus-fixture', json.loads(response.read())['data'][0]['id'])


class HistoryRaceScenarioTest(unittest.TestCase):
    def user(self, text):
        return {'role': 'user', 'content': [{'type': 'text', 'text': text}]}

    def test_roles_follow_the_first_user_message(self):
        self.assertEqual(('delegate', 'A'), history_role([self.user('<task>HISTORY_RACE_PARENT A go')]))
        self.assertEqual(('child', 'B'), history_role([self.user('<task>HISTORY_RACE_CHILD B go')]))
        self.assertEqual(('plain', ''), history_role([self.user('hello')]))

    def test_parent_resumes_after_new_task_but_ignores_the_system_prompt(self):
        system = {'role': 'system', 'content': 'tools: new_task, attempt_completion'}
        first = self.user('<task>HISTORY_RACE_PARENT A go')
        self.assertEqual(('delegate', 'A'), history_role([system, first]))
        called = {'role': 'assistant', 'tool_calls': [{'function': {'name': 'new_task'}}]}
        self.assertEqual(('resume', 'A'), history_role([system, first, called]))

    def test_two_parents_delegate_together_and_children_complete(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        log = Path(directory.name) / 'provider.jsonl'
        server = make_server(0, log, chunks=2, interval=0.01, scenario='history-race', barrier=2)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(thread.join)
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        tools = [{'type': 'function', 'function': {'name': 'attempt_completion'}}]
        results = {}

        def call(tag, text):
            connection = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=10)
            body = {'stream': True, 'tools': tools, 'messages': [self.user(text)]}
            connection.request('POST', '/v1/chat/completions', json.dumps(body),
                               {'Authorization': 'Bearer focus-fixture-local-only'})
            data = connection.getresponse().read().decode()
            results[tag] = [json.loads(line[6:]) for line in data.splitlines() if line.startswith('data: {')]
            connection.close()

        parents = [threading.Thread(target=call, args=(tag, f'HISTORY_RACE_PARENT {tag}')) for tag in 'AB']
        for parent in parents:
            parent.start()
        for parent in parents:
            parent.join(15)
        for tag in 'AB':
            call_delta = results[tag][-2]['choices'][0]['delta']['tool_calls'][0]['function']
            self.assertEqual('new_task', call_delta['name'])
            arguments = json.loads(call_delta['arguments'])
            self.assertEqual(f'HISTORY_RACE_CHILD {tag} reply with attempt_completion.', arguments['message'])
            self.assertIsNone(arguments['todos'])
        call('A', 'HISTORY_RACE_CHILD A go')
        done = results['A'][-2]['choices'][0]['delta']['tool_calls'][0]['function']['name']
        self.assertEqual('attempt_completion', done)
        events = [json.loads(line)['event'] for line in log.read_text().splitlines()]
        self.assertEqual(2, events.count('barrier-released'))
        self.assertNotIn('barrier-timeout', events)

    def test_lone_parent_times_out_instead_of_passing(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        log = Path(directory.name) / 'provider.jsonl'
        server = make_server(0, log, chunks=1, interval=0.01, scenario='history-race',
                             barrier=2, barrier_timeout=0.3)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(thread.join)
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        connection = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=10)
        body = {'stream': True, 'tools': [{'type': 'function', 'function': {'name': 'attempt_completion'}}],
                'messages': [self.user('HISTORY_RACE_PARENT A go')]}
        connection.request('POST', '/v1/chat/completions', json.dumps(body),
                           {'Authorization': 'Bearer focus-fixture-local-only'})
        connection.getresponse().read()
        connection.close()
        events = [json.loads(line)['event'] for line in log.read_text().splitlines()]
        self.assertIn('barrier-timeout', events)


if __name__ == '__main__':
    unittest.main()
