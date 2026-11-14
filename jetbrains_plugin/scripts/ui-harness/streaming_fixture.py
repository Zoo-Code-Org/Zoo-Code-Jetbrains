#!/usr/bin/env python3
"""Deterministic loopback OpenAI fixture for headful chat-focus CI tests.

Only the provider is simulated. The extension, React history, JCEF, and native
keyboard delivery remain real. No commands or file edits are requested.
"""
import argparse
import http.server
import json
import re
import threading
import time
from pathlib import Path


def _text(content):
    if isinstance(content, list):
        return ''.join(part.get('text', '') for part in content if isinstance(part, dict))
    return content if isinstance(content, str) else ''


def history_role(messages):
    """Classify a request in the history-race scenario.

    The first user message names the task: a parent carries HISTORY_RACE_PARENT <tag>
    and a child carries HISTORY_RACE_CHILD <tag>. A parent that already holds a
    new_task call in a non-system message has delegated, so it resumes.
    """
    users = [m for m in messages if m.get('role') == 'user']
    first = _text(users[0].get('content')) if users else ''
    child = re.search(r'HISTORY_RACE_CHILD (\w+)', first)
    if child:
        return 'child', child.group(1)
    parent = re.search(r'HISTORY_RACE_PARENT (\w+)', first)
    if not parent:
        return 'plain', ''
    body = json.dumps([m for m in messages if m.get('role') != 'system'])
    return ('resume' if 'new_task' in body else 'delegate'), parent.group(1)


class Fixture(http.server.BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        if self.path == '/health':
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b'ready')
        elif self.path == '/v1/models':
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps({'object': 'list', 'data': [
                {'id': 'focus-fixture', 'object': 'model', 'owned_by': 'fixture'}
            ]}).encode())
        else:
            self.send_error(404)

    def do_POST(self):
        if self.path != '/v1/chat/completions':
            self.send_error(404)
            return
        if self.headers.get('Authorization') != 'Bearer focus-fixture-local-only':
            self.send_error(401)
            return
        try:
            length = int(self.headers.get('Content-Length', '0'))
            if not 0 < length <= 8 * 1024 * 1024:
                raise ValueError('body size')
            request = json.loads(self.rfile.read(length))
            if request.get('stream') is not True:
                raise ValueError('streaming required')
            tools = [t.get('function', {}).get('name') for t in request.get('tools', [])]
            if 'attempt_completion' not in tools:
                raise ValueError('native attempt_completion tool required')
        except (ValueError, TypeError, AttributeError):
            self.send_error(400, 'Expected streaming request with native completion tool')
            return
        with self.server.counter_lock:
            self.server.requests += 1
            turn = self.server.requests
        role, tag = 'plain', ''
        if self.server.scenario == 'history-race':
            role, tag = history_role(request.get('messages', []))
        self.server.record(event='request', turn=turn, tools=tools, role=role, tag=tag)
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.send_header('Cache-Control', 'no-cache')
        self.end_headers()

        def emit(delta, finish=None):
            chunk = {'id': f'focus-{turn}', 'object': 'chat.completion.chunk',
                     'created': int(time.time()), 'model': 'focus-fixture',
                     'choices': [{'index': 0, 'delta': delta, 'finish_reason': finish}]}
            self.wfile.write(('data: ' + json.dumps(chunk) + '\n\n').encode())
            self.wfile.flush()

        try:
            emit({'role': 'assistant', 'content': ''})
            chunks = self.server.chunks
            if role == 'delegate':
                # Hold every parent until all IDEs have submitted, so the delegations
                # and the child streams that follow overlap in time.
                try:
                    self.server.barrier.wait()
                except threading.BrokenBarrierError:
                    self.server.record(event='barrier-timeout', turn=turn, tag=tag)
                    return
                self.server.record(event='barrier-released', turn=turn, tag=tag)
            if role in ('delegate', 'resume'):
                chunks = 3
            for index in range(chunks):
                emit({'content': f'History update {index + 1} for turn {turn}. '})
                time.sleep(self.server.interval)
            if role == 'delegate':
                call = ('new_task', {'mode': 'code', 'todos': None,
                                     'message': f'HISTORY_RACE_CHILD {tag} reply with attempt_completion.'})
            else:
                call = ('attempt_completion', {'result': f'Fixture turn {turn} complete.'})
            emit({'tool_calls': [{'index': 0, 'id': f'call-{turn}',
                                 'type': 'function', 'function': {
                                     'name': call[0], 'arguments': json.dumps(call[1])}}]})
            emit({}, 'tool_calls')
            self.wfile.write(b'data: [DONE]\n\n')
            self.wfile.flush()
            self.server.record(event='completed', turn=turn, chunks=self.server.chunks)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            self.server.record(event='disconnected', turn=turn)


def make_server(port=19487, log=None, chunks=100, interval=0.25, scenario=None, barrier=2, barrier_timeout=300):
    server = http.server.ThreadingHTTPServer(('127.0.0.1', port), Fixture)
    server.requests = 0
    server.counter_lock = threading.Lock()
    server.chunks, server.interval = chunks, interval
    server.scenario = scenario
    server.barrier = threading.Barrier(barrier, timeout=barrier_timeout)

    def record(**fields):
        if log:
            with server.counter_lock, Path(log).open('a', encoding='utf-8') as output:
                output.write(json.dumps({'time': time.time(), **fields}) + '\n')
    server.record = record
    return server


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=19487)
    parser.add_argument('--log', type=Path, required=True)
    parser.add_argument('--scenario', choices=['history-race'])
    parser.add_argument('--chunks', type=int, default=100)
    parser.add_argument('--interval', type=float, default=0.25)
    parser.add_argument('--barrier', type=int, default=2)
    args = parser.parse_args()
    make_server(args.port, args.log, args.chunks, args.interval, args.scenario, args.barrier).serve_forever()
