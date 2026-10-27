"""Local relay access checks; never contact the provider."""
import http.client
import http.server
import threading
import unittest
from unittest.mock import patch
import solheim_proxy as relay


class RelayAccessTest(unittest.TestCase):
    def setUp(self):
        relay.TOKEN = 'a' * 64
        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), relay.Relay)
        self.worker = threading.Thread(target=self.server.serve_forever)
        self.worker.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.worker.join()

    def request(self, headers, body=None, method='GET'):
        connection = http.client.HTTPConnection('127.0.0.1', self.server.server_port)
        with patch.object(relay.urllib.request, 'urlopen') as upstream:
            connection.request(method, '/v1/models', body=body, headers=headers)
            response = connection.getresponse()
            response.read()
            connection.close()
            upstream.assert_not_called()
            return response.status

    def test_missing_token(self):
        self.assertEqual(401, self.request({}))

    def test_wrong_token(self):
        self.assertEqual(401, self.request({'Authorization': 'Bearer wrong'}))

    def test_browser_origin(self):
        self.assertEqual(403, self.request({'Authorization': 'Bearer ' + relay.TOKEN,
                                         'Origin': 'https://example.com'}))

    def test_untrusted_host(self):
        self.assertEqual(403, self.request({'Authorization': 'Bearer ' + relay.TOKEN,
                                         'Host': 'example.com'}))

    def test_invalid_content_length(self):
        for length in ['-1', 'not-a-number', '1.5']:
            with self.subTest(length=length):
                self.assertEqual(400, self.request({'Authorization': 'Bearer ' + relay.TOKEN,
                                                    'Content-Length': length}, method='POST'))

    def test_excessive_content_length(self):
        for length in [str(relay.MAX_BODY_BYTES + 1), '9' * 5000]:
            with self.subTest(length=length[:20]):
                self.assertEqual(413, self.request({'Authorization': 'Bearer ' + relay.TOKEN,
                                                    'Content-Length': length}, method='POST'))

    def test_malformed_json(self):
        for body in [b'{', b'\xff']:
            with self.subTest(body=body):
                self.assertEqual(400, self.request({'Authorization': 'Bearer ' + relay.TOKEN},
                                                   body=body, method='POST'))

    def test_non_object_json(self):
        for body in [b'[]', b'null', b'1']:
            with self.subTest(body=body):
                self.assertEqual(400, self.request({'Authorization': 'Bearer ' + relay.TOKEN},
                                                   body=body, method='POST'))

    def test_wrong_message_shape(self):
        self.assertEqual(400, self.request({'Authorization': 'Bearer ' + relay.TOKEN},
                                           body=b'{"messages":null}', method='POST'))

    def test_unsupported_transfer_encoding(self):
        self.assertEqual(400, self.request({'Authorization': 'Bearer ' + relay.TOKEN,
                                           'Transfer-Encoding': 'chunked'}, method='POST'))

    def test_authorized_request_forwards_only_upstream_key(self):
        relay.KEY = 'private-upstream-test-key'
        response = unittest.mock.Mock(status=200, headers={'Content-Type': 'application/json'})
        response.read1.side_effect = [b'{"data":[]}', b'']
        connection = http.client.HTTPConnection('127.0.0.1', self.server.server_port)
        with patch.object(relay.urllib.request, 'urlopen', return_value=response) as upstream, patch.object(relay, 'record'):
            connection.request('GET', '/v1/models', headers={'Authorization': 'Bearer ' + relay.TOKEN})
            result = connection.getresponse()
            self.assertEqual(200, result.status)
            self.assertEqual(b'{"data":[]}', result.read())
            self.assertEqual('Bearer private-upstream-test-key', upstream.call_args.args[0].get_header('Authorization'))
            connection.close()

    def test_authorized_post_forwards_json_unchanged(self):
        relay.KEY = 'private-upstream-test-key'
        body = b'{"model":"test-model","messages":[],"stream":true}'
        response = unittest.mock.Mock(status=200, headers={'Content-Type': 'text/event-stream'})
        response.read1.side_effect = [b'data: [DONE]\n', b'']
        connection = http.client.HTTPConnection('127.0.0.1', self.server.server_port)
        with patch.object(relay.urllib.request, 'urlopen', return_value=response) as upstream, patch.object(relay, 'record'):
            connection.request('POST', '/v1/chat/completions', body=body,
                               headers={'Authorization': 'Bearer ' + relay.TOKEN})
            result = connection.getresponse()
            self.assertEqual(200, result.status)
            self.assertEqual(b'data: [DONE]\n', result.read())
            self.assertEqual(body, upstream.call_args.args[0].data)
            self.assertEqual('POST', upstream.call_args.args[0].get_method())
            connection.close()


if __name__ == '__main__':
    unittest.main()
