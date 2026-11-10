import http.client
import json
import tempfile
import threading
import unittest
from unittest.mock import patch
from pathlib import Path

from streaming_fixture import make_server


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


if __name__ == '__main__':
    unittest.main()
