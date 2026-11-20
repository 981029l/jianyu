import base64
import json
import threading
import unittest
from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

import server


class Provider(BaseHTTPRequestHandler):
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        self.respond(body)

    def do_GET(self):
        self.respond(None)

    def respond(self, body):
        self.server.calls.append((self.command, self.path, self.headers['Authorization'], body))
        code, result = self.server.answers.pop(0)
        self.send_response(code)
        self.end_headers()
        self.wfile.write(json.dumps(result).encode())

    def log_request(self, *args):
        pass


class Integration(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        def launch(handler):
            instance = ThreadingHTTPServer(('127.0.0.1', 0), handler)
            threading.Thread(target=instance.serve_forever, daemon=True).start()
            cls.addClassCleanup(instance.server_close)
            cls.addClassCleanup(instance.shutdown)
            return instance
        cls.provider = launch(Provider)
        cls.gateway = launch(server.Handler)
        cls.gateway.api_key = 'local-test-only'
        api = patch.object(server, 'API', f'http://127.0.0.1:{cls.provider.server_port}/api/v1')
        api.start()
        cls.addClassCleanup(api.stop)

    def setUp(self):
        self.provider.answers, self.provider.calls = [], []
        self.form = {'model': 'Image 1', 'ratio': '1:1', 'prompt': '清晨的海岸灯塔'}
        self.image = 'https://example.invalid/image.png'

    def call(self, path='/generate', body=None):
        conn = HTTPConnection('127.0.0.1', self.gateway.server_port, timeout=2)
        try:
            raw = None if body is None else json.dumps(body, ensure_ascii=False).encode()
            conn.request('GET' if raw is None else 'POST', path, raw, {'Content-Type': 'application/json'})
            resp = conn.getresponse()
            return resp.status, json.loads(resp.read())
        finally:
            conn.close()

    def test_gpt_and_unicode(self):
        self.provider.answers.append((200, {'data': [{'url': self.image}], 'task_id': 12}))
        self.assertEqual(self.call(body=self.form), (200, {'image_url': self.image, 'task_id': '12'}))
        self.assertEqual(self.provider.calls, [('POST', '/api/v1/images/generations', 'Bearer local-test-only',
            {'prompt': self.form['prompt'], 'model': 'image-1', 'quality': 'auto', 'size': '1024x1024', 'n': 1, 'response_format': 'url'})])

    def test_grok_local_reference(self):
        image = 'data:image/png;base64,' + base64.b64encode(b'png').decode()
        self.provider.answers.append((200, {'data': [{'url': self.image}]}))
        self.assertEqual(self.call(body=dict(self.form, model='Image 2', ratio='3:4', image=image))[0], 200)
        method, path, _, body = self.provider.calls[0]
        self.assertEqual((method, path, body['model'], body['quality'], body['size'], body['image']),
                         ('POST', '/api/v1/images/edits', 'image-2', '1k', '768x1024', image))

    def test_timeout_then_lookup_same_task(self):
        self.provider.answers.extend([(504, {'detail': {'message': 'timeout', 'task_id': 108}}),
            (200, {'status': 'processing', 'delivered_count': 0}), (200, {'result': {'data': [{'url': self.image}]}})])
        self.assertEqual(self.call(body=self.form), (202, {'task_id': '108'}))
        self.assertEqual(self.call('/generate?task_id=108'), (202, {'task_id': '108'}))
        self.assertEqual(self.call('/generate?task_id=108'), (200, {'image_url': self.image, 'task_id': '108'}))
        self.assertEqual([(c[0], c[1]) for c in self.provider.calls], [('POST', '/api/v1/images/generations'),
                        ('GET', '/api/v1/tasks/108'), ('GET', '/api/v1/tasks/108')])

    def test_errors_without_task_do_not_resubmit(self):
        for code in (401, 403, 422, 429, 502, 504):
            with self.subTest(code=code):
                self.provider.answers.append((code, {'detail': 'upstream message'}))
                status, body = self.call(body=self.form)
                self.assertEqual(status, code)
                self.assertIn('error', body)
        self.assertEqual(len(self.provider.calls), 6)

    def test_partial_or_refunded_outcomes(self):
        self.provider.answers.extend([(200, {'partial': True, 'refunded': True, 'data': [{'url': self.image}]}),
                                    (200, {'refunded': True, 'data': [], 'delivered_count': 0})])
        self.assertEqual(self.call('/generate?task_id=76')[0], 200)
        self.assertEqual(self.call('/generate?task_id=77')[0], 410)

    def test_invalid_input_never_reaches_provider(self):
        for change in ({'prompt': ' '}, {'prompt': None}, {'ratio': '0:0'}, {'ratio': '2:3'}, {'model': None},
                       {'image': 'file:///bad'}, {'nsfw': 'true'}, {'nsfw': 1}, {'nsfw': True}):
            with self.subTest(field=next(iter(change)), value=next(iter(change.values()))):
                self.assertEqual(self.call(body=dict(self.form, **change))[0], 400)
        self.assertEqual(self.call('/generate?task_id=abc%2Fother')[0], 400)
        self.assertEqual(self.provider.calls, [])

    def test_model_size_mapping(self):
        expected = {
            'Image 1': {'1:1': '1024x1024', '3:4': '1024x1536', '4:3': '1536x1024',
                        '9:16': '1024x1536', '16:9': '1536x1024'},
            'Image 2': {'1:1': '1024x1024', '3:4': '768x1024', '4:3': '1024x768',
                        '9:16': '576x1024', '16:9': '1024x576'},
        }
        for model, table in expected.items():
            for ratio, size in table.items():
                with self.subTest(model=model, ratio=ratio):
                    self.provider.answers.append((200, {'data': [{'url': self.image}]}))
                    status, _ = self.call(body=dict(self.form, model=model, ratio=ratio))
                    self.assertEqual(status, 200)
                    self.assertEqual(self.provider.calls[-1][3]['size'], size)

    def test_grok_nsfw_switch(self):
        for flag in (True, False):
            self.provider.answers.append((200, {'data': [{'url': self.image}]}))
            status, _ = self.call(body=dict(self.form, model='Image 2', nsfw=flag))
            self.assertEqual(status, 200)
            self.assertIs(self.provider.calls[-1][3]['nsfw'], flag)
            self.assertEqual(self.provider.calls[-1][3]['size'], '1024x1536' if flag else '1024x1024')
        self.provider.answers.append((200, {'data': [{'url': self.image}]}))
        self.assertEqual(self.call(body=dict(self.form, model='Image 1', nsfw=False))[0], 200)
        self.assertNotIn('nsfw', self.provider.calls[-1][3])

    def test_grok_nsfw_fixed_ratio_with_and_without_reference(self):
        image = 'data:image/png;base64,' + base64.b64encode(b'png').decode()
        for ratio in ('1:1', '2:3', '9:16'):
            for reference in ({}, {'image': image}):
                with self.subTest(ratio=ratio, reference=bool(reference)):
                    self.provider.answers.append((200, {'data': [{'url': self.image}]}))
                    status, _ = self.call(body=dict(self.form, model='Image 2', ratio=ratio, nsfw=True, **reference))
                    self.assertEqual(status, 200)
                    self.assertEqual(self.provider.calls[-1][3]['size'], '1024x1536')
                    self.assertEqual(self.provider.calls[-1][1], '/api/v1/images/edits' if reference else '/api/v1/images/generations')

    def test_long_unicode_prompt_is_forwarded_without_truncation(self):
        prompt = '海边灯塔🌊' * 1000
        self.provider.answers.extend([(200, {'data': [{'url': self.image}]}),
                                     (422, {'detail': 'upstream prompt too long'})])
        self.assertEqual(self.call(body=dict(self.form, prompt=prompt))[0], 200)
        self.assertEqual(self.provider.calls[-1][3]['prompt'], prompt)
        status, result = self.call(body=dict(self.form, prompt=prompt))
        self.assertEqual(status, 422)
        self.assertEqual(result['detail'], 'upstream prompt too long')
        self.assertEqual(len(self.provider.calls), 2)

    def test_error_detail_passthrough(self):
        self.provider.answers.extend([
            (429, {'detail': 'retry after 30s'}),
            (502, {'detail': {'message': 'upstream worker busy'}}),
            (422, {'detail': [{'loc': ['body', 'size'], 'msg': 'unsupported size'}]}),
            (200, {'status': 'failed', 'detail': 'content policy blocked', 'delivered_count': 0}),
            (400, {'detail': 'x' * 500}),
        ])
        status, body = self.call(body=self.form)
        self.assertEqual(status, 429)
        self.assertEqual(body['detail'], 'retry after 30s')
        self.assertIn('retry after 30s', body['error'])

        status, body = self.call(body=self.form)
        self.assertEqual(status, 502)
        self.assertEqual(body['detail'], 'upstream worker busy')
        self.assertIn('upstream worker busy', body['error'])

        status, body = self.call(body=self.form)
        self.assertEqual(status, 422)
        self.assertEqual(body['detail'], 'size: unsupported size')
        self.assertIn('size: unsupported size', body['error'])

        status, body = self.call('/generate?task_id=88')
        self.assertEqual(status, 410)
        self.assertEqual(body['detail'], 'content policy blocked')
        self.assertIn('content policy blocked', body['error'])

        status, body = self.call(body=self.form)
        self.assertEqual(status, 400)
        self.assertEqual(len(body['detail']), server.MAX_DETAIL)
        self.assertTrue(body['detail'].endswith('…'))

    def test_key_pool_round_robin_and_task_affinity(self):
        pool = server.KeyPool(['key-1', 'key-2', 'key-3'])
        with patch.object(self.gateway, 'key_pool', pool, create=True):
            self.provider.answers.extend([
                (200, {'data': [{'url': self.image}]}),
                (504, {'detail': {'message': 'timeout', 'task_id': 202}}),
                (200, {'data': [{'url': self.image}]}),
                (200, {'data': [{'url': self.image}]}),
                (200, {'result': {'data': [{'url': self.image}]}}),
                (404, {'detail': 'not found'}),
                (200, {'result': {'data': [{'url': self.image}]}}),
            ])
            self.assertEqual(self.call(body=self.form)[0], 200)
            self.assertEqual(self.call(body=self.form), (202, {'task_id': '202'}))
            self.assertEqual(self.call(body=self.form)[0], 200)
            self.assertEqual(self.call(body=self.form)[0], 200)
            # task 202 归属 key-2，查询时必须使用 key-2
            self.assertEqual(self.call('/generate?task_id=202')[0], 200)
            # 未知 task 303（如重启后）在 key-1 返回 404 时继续尝试 key-2 找回
            self.assertEqual(self.call('/generate?task_id=303')[0], 200)

        auths = [(c[0], c[1], c[2]) for c in self.provider.calls]
        self.assertEqual(auths, [
            ('POST', '/api/v1/images/generations', 'Bearer key-1'),
            ('POST', '/api/v1/images/generations', 'Bearer key-2'),
            ('POST', '/api/v1/images/generations', 'Bearer key-3'),
            ('POST', '/api/v1/images/generations', 'Bearer key-1'),
            ('GET', '/api/v1/tasks/202', 'Bearer key-2'),
            ('GET', '/api/v1/tasks/303', 'Bearer key-1'),
            ('GET', '/api/v1/tasks/303', 'Bearer key-2'),
        ])

    def test_parse_and_load_keys(self):
        self.assertEqual(server.parse_keys('k1, k2\nk3;k1'), ['k1', 'k2', 'k3'])
        self.assertEqual(server.load_keys({'IMAGE_API_KEYS': 'a,b', 'IMAGE_API_KEY': 'c'}), ['a', 'b'])
        self.assertEqual(server.load_keys({'IMAGE_API_KEY': 'c,d'}), ['c', 'd'])


if __name__ == '__main__':
    unittest.main()
