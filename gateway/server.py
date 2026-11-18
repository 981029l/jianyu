import json
import os
import re
import socket
from http.client import HTTPException
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlsplit
from urllib.request import build_opener, Request, HTTPRedirectHandler

API = 'https://img.pipiwangcom.com/api/v1'
MODELS = {'Image 1': ('image-1', 'auto'), 'Image 2': ('image-2', '1k')}
SIZES = {
    'image-1': {'1:1': '1024x1024', '3:4': '1024x1536', '4:3': '1536x1024',
                '9:16': '1024x1536', '16:9': '1536x1024'},
    'image-2': {'1:1': '1024x1024', '3:4': '768x1024', '4:3': '1024x768',
                '9:16': '576x1024', '16:9': '1024x576'},
}
TASK = re.compile(r'[A-Za-z0-9_-]{1,128}')
TIMEOUT = 1900
MAX_REQUEST = 6 * 1024 * 1024
MAX_RESULT = 1024 * 1024
MAX_DETAIL = 300


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def generation(body):
    if not isinstance(body, dict):
        raise ValueError('请求必须为 JSON 对象。')
    prompt = body.get('prompt')
    if not isinstance(prompt, str) or not 1 <= len(prompt.strip()) <= 4000:
        raise ValueError('提示词长度须为 1 至 4000 个字符。')
    model, ratio = body.get('model'), body.get('ratio')
    if not isinstance(model, str) or model not in MODELS:
        raise ValueError('模型或图片比例无效。')
    name, quality = MODELS[model]
    model_sizes = SIZES[name]
    if not isinstance(ratio, str) or ratio not in model_sizes:
        raise ValueError('模型或图片比例无效。')
    payload = dict(prompt=prompt.strip(), model=name, size=model_sizes[ratio], quality=quality, n=1, response_format='url')
    if 'nsfw' in body:
        nsfw = body['nsfw']
        if not isinstance(nsfw, bool):
            raise ValueError('nsfw 须为布尔值。')
        if nsfw and name != 'image-2':
            raise ValueError('仅 Grok 支持开启 NSFW。')
        if name == 'image-2':
            payload['nsfw'] = nsfw
    if 'image' in body:
        image = body['image']
        if not isinstance(image, str) or not re.fullmatch(r'data:image/(?:jpeg|png|webp);base64,[A-Za-z0-9+/]+=*', image):
            raise ValueError('参考图须为 JPEG、PNG 或 WebP。')
        payload['image'] = image
        return '/images/edits', payload
    return '/images/generations', payload


def extract_detail(body):
    def pick(value):
        if isinstance(value, str):
            text = ' '.join(value.split())
            return text or None
        if isinstance(value, dict):
            for key in ('message', 'error', 'msg', 'detail'):
                found = pick(value.get(key))
                if found:
                    return found
        if isinstance(value, list):
            parts = []
            for item in value:
                if isinstance(item, dict):
                    msg = pick(item.get('msg') or item.get('message') or item.get('error'))
                    loc = item.get('loc')
                    if msg and isinstance(loc, list) and loc:
                        field = str(loc[-1]).strip()
                        parts.append(f'{field}: {msg}' if field else msg)
                    elif msg:
                        parts.append(msg)
                else:
                    found = pick(item)
                    if found:
                        parts.append(found)
            if parts:
                return '；'.join(parts)
        return None

    for candidate in (body.get('detail'), body.get('message'), body.get('error'), body.get('result')):
        text = pick(candidate)
        if text:
            return text if len(text) <= MAX_DETAIL else text[:MAX_DETAIL - 1] + '…'
    return None


def error_payload(info, base, detail_text):
    if detail_text:
        return dict(info, error=f"{base.rstrip('。')}：{detail_text}", detail=detail_text)
    return dict(info, error=base)


def upstream(path, body, key):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode('utf-8')
    request = Request(API + path, data=data, headers={'Authorization': 'Bearer ' + key,
                      'Content-Type': 'application/json', 'Accept': 'application/json'})
    try:
        response = build_opener(NoRedirect()).open(request, timeout=TIMEOUT)
    except HTTPError as exc:
        response = exc
    with response:
        raw = response.read(MAX_RESULT + 1)
        if len(raw) > MAX_RESULT:
            raise OSError('oversized response')
        try:
            result = json.loads(raw)
        except (ValueError, UnicodeError):
            result = {}
        return response.status, result if isinstance(result, dict) else {}


def outcome(status, body, known_id=None):
    detail = body.get('detail')
    task = body.get('task_id') or (detail.get('task_id') if isinstance(detail, dict) else None) or known_id
    task = str(task) if task and TASK.fullmatch(str(task)) else None
    info = {'task_id': task} if task else {}
    if status in (502, 504) and task:
        return 202, info
    detail_text = extract_detail(body)
    if not 200 <= status < 300:
        messages = {400: '检查提示词、模型及参考图。', 422: '检查提示词、模型及参考图。',
                    401: '请检查网关的皮皮智绘密钥。', 403: '请检查皮皮智绘账户权限。',
                    429: '请求过于频繁，请稍后再试。'}
        base = messages.get(status, '服务未返回作品，请核对任务记录。')
        return status if 400 <= status < 600 else 502, error_payload(info, base, detail_text)
    result = body.get('result') if isinstance(body.get('result'), dict) else body
    images = result.get('data')
    for item in images if isinstance(images, list) else []:
        url = item.get('url') if isinstance(item, dict) else None
        if isinstance(url, str) and url.startswith('https://'):
            return 200, dict(info, image_url=url)
    state = str(body.get('status', '')).lower()
    if state in ('failed', 'error', 'cancelled', 'canceled') or result.get('refunded') or ('delivered_count' in result and result['delivered_count'] == 0 and state not in ('queued', 'pending', 'running', 'processing')):
        return 410, error_payload(info, '任务未交付图片，请在皮皮智绘核对任务与积分记录。', detail_text)
    if task and state not in ('completed', 'done', 'succeeded', 'success'):
        return 202, info
    return 502, error_payload(info, '结果中缺少可下载的图片地址。', detail_text)


class Handler(BaseHTTPRequestHandler):
    def log_request(self, *args):
        pass

    def reply(self, status, data):
        encoded = json.dumps(data, ensure_ascii=False).encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(encoded)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(encoded)

    def handle_api(self):
        url = urlsplit(self.path)
        if url.path != '/generate':
            return self.reply(404, {'error': '接口不存在。'})
        task = None
        try:
            if self.command == 'GET':
                ids = parse_qs(url.query).get('task_id', [])
                if len(ids) != 1 or not TASK.fullmatch(ids[0]):
                    raise ValueError('任务编号无效。')
                task = ids[0]
                path, payload = '/tasks/' + task, None
            else:
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 < length <= MAX_REQUEST:
                    return self.reply(413, {'error': '请求过大或缺少长度，请选择 4 MiB 以内的参考图。'})
                path, payload = generation(json.loads(self.rfile.read(length)))
        except (ValueError, UnicodeError) as exc:
            return self.reply(400, {'error': str(exc)})
        try:
            status, body = upstream(path, payload, self.server.api_key)
            status, result = outcome(status, body, task)
        except (URLError, OSError, HTTPException):
            status, result = 502, {'error': '与皮皮智绘的连接中断，请核对原任务后再发起生成。'}
        self.reply(status, result)

    do_POST = do_GET = handle_api


if __name__ == '__main__':
    key = os.getenv('IMAGE_API_KEY', '').strip()
    if not key:
        raise SystemExit('请在网关进程环境中配置 IMAGE_API_KEY。')
    with ThreadingHTTPServer(('127.0.0.1', int(os.getenv('PORT', '8787'))), Handler) as server:
        server.api_key = key
        print(f'见屿网关监听 127.0.0.1:{server.server_port}', flush=True)
        server.serve_forever()
