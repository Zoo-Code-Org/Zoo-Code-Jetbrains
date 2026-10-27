#!/usr/bin/env python3
"""Loopback authentication relay for manual Solheim extension focus smoke tests.

Real credentials stay in SOLHEIM_KEY_FILE (mode 0600), outside IDE settings/logs.
The extension uses a per-run relay token and http://127.0.0.1:19487/v1. Logs contain only
request metadata, upstream status, stream byte counts, and tool names.
"""
import http.server
import hmac
import json
import os
import time
import urllib.error
import urllib.request
from pathlib import Path

KEY = None
TOKEN = None
BASE = 'https://api.solheim.ai/v1'
MAX_BODY_BYTES = 8 * 1024 * 1024
LOG = Path(os.environ.get('SOLHEIM_PROXY_LOG', '/tmp/zoo-solheim-proxy.jsonl'))


def record(**fields):
    with LOG.open('a') as output:
        output.write(json.dumps({'time': time.time(), **fields}) + '\n')


class Relay(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *_):
        pass

    def do_GET(self):
        self.relay()

    def do_POST(self):
        self.relay()

    def relay(self):
        expected_host = '127.0.0.1:' + str(self.server.server_port)
        if self.headers.get('Host') != expected_host or self.headers.get('Origin') is not None:
            self.send_error(403)
            return
        supplied = self.headers.get('Authorization', '')
        if not TOKEN or not hmac.compare_digest(supplied, 'Bearer ' + TOKEN):
            self.send_error(401)
            return
        if not self.path.startswith('/v1/'):
            self.send_error(404)
            return
        lengths = self.headers.get_all('Content-Length', [])
        length_text = lengths[0] if lengths else '0'
        if (len(lengths) > 1 or self.headers.get('Transfer-Encoding') is not None
                or not length_text.isascii() or not length_text.isdecimal()):
            self.send_error(400, 'Invalid request framing')
            return
        # Check before int conversion, including absurdly long decimal strings.
        if len(length_text.lstrip('0')) > 8 or int(length_text.lstrip('0') or '0') > MAX_BODY_BYTES:
            self.send_error(413, 'Request body too large')
            return
        length = int(length_text.lstrip('0') or '0')
        body = self.rfile.read(length)
        try:
            parsed = json.loads(body) if body else {}
            if len(body) != length or not isinstance(parsed, dict):
                raise ValueError('Expected a JSON object')
            if not isinstance(parsed.get('messages', []), list):
                raise ValueError('Expected a message list')
        except (ValueError, UnicodeError):
            self.send_error(400, 'Invalid JSON request body')
            return
        record(event='request', path=self.path, model=parsed.get('model'),
               messages=len(parsed.get('messages', [])), stream=parsed.get('stream', False))
        request = urllib.request.Request(BASE + self.path[3:], data=body or None,
                                        method=self.command,
                                        headers={'Authorization': 'Bearer ' + KEY,
                                                 'Content-Type': 'application/json'})
        try:
            response = urllib.request.urlopen(request, timeout=180)
        except urllib.error.HTTPError as error:
            response = error
        except Exception as error:
            record(event='transport-error', error=type(error).__name__)
            self.send_error(502)
            return
        self.send_response(response.status)
        self.send_header('Content-Type', response.headers.get('Content-Type', 'application/json'))
        self.send_header('Connection', 'close')
        self.end_headers()
        count = 0
        buffer = b''
        tools = set()
        try:
            while True:
                chunk = response.read1(4096)
                if not chunk:
                    break
                count += len(chunk)
                self.wfile.write(chunk)
                self.wfile.flush()
                buffer += chunk
                while b'\n' in buffer:
                    line, buffer = buffer.split(b'\n', 1)
                    if line.startswith(b'data: ') and line != b'data: [DONE]':
                        try:
                            event = json.loads(line[6:])
                            for choice in event.get('choices', []):
                                for tool in choice.get('delta', {}).get('tool_calls', []):
                                    name = tool.get('function', {}).get('name')
                                    if name:
                                        tools.add(name)
                        except (ValueError, TypeError):
                            pass
        except (BrokenPipeError, ConnectionResetError):
            record(event='client-disconnected')
        finally:
            response.close()
            self.close_connection = True
            record(event='response', status=response.status, bytes=count, tools=sorted(tools))


if __name__ == '__main__':
    KEY = Path(os.environ['SOLHEIM_KEY_FILE']).read_text().strip()
    TOKEN = os.environ['SOLHEIM_RELAY_TOKEN']
    if len(TOKEN) < 32:
        raise SystemExit('Use a random relay token of at least 32 characters')
    http.server.ThreadingHTTPServer(('127.0.0.1', 19487), Relay).serve_forever()
