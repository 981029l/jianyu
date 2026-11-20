from collections import OrderedDict
import json
import os
from pathlib import Path
import re
import socket
import threading
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
MAX_TASK_KEYS = 2000
GROK_NSFW_RATIO = '2:3'
GROK_NSFW_SIZE = '1024x1536'


def parse_keys(raw):
    if not raw:
        return []
    keys = []
    for part in re.split(r'[\s,;]+', raw.strip()):
        if part and part not in keys:
            keys.append(part)
    return keys


def load_dotenv(*paths):
    for path in paths:
        file_path = Path(path)
        if not file_path.is_file():
            continue
        for line in file_path.read_text(encoding='utf-8').splitlines():
            text = line.strip()
            if not text or text.startswith('#') or '=' not in text:
                continue
            name, value = text.split('=', 1)
            name = name.strip()
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in ('"', "'"):
                value = value[1:-1]
            if name:
                os.environ.setdefault(name, value)


def load_keys(env=None):
    source = os.environ if env is None else env
    return parse_keys(source.get('IMAGE_API_KEYS') or source.get('IMAGE_API_KEY') or '')


class KeyPool:
    def __init__(self, keys):
        self._keys = list(keys)
        if not self._keys:
            raise ValueError('密钥列表不能为空。')
        self._index = 0
        self._tasks = OrderedDict()
        self._lock = threading.Lock()

    def __len__(self):
        return len(self._keys)

    def next_key(self):
        with self._lock:
            key = self._keys[self._index]
            self._index = (self._index + 1) % len(self._keys)
            return key

    def remember(self, task_id, key):
        if not task_id or not key:
            return
        with self._lock:
            self._tasks[task_id] = key
            self._tasks.move_to_end(task_id)
            while len(self._tasks) > MAX_TASK_KEYS:
                self._tasks.popitem(last=False)

    def forget(self, task_id):
        if not task_id:
            return
        with self._lock:
            self._tasks.pop(task_id, None)

    def keys_for_task(self, task_id):
        with self._lock:
            owner = self._tasks.get(task_id)
            return [owner] if owner else list(self._keys)


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def generation(body):
    if not isinstance(body, dict):
        raise ValueError('请求必须为 JSON 对象。')
    prompt = body.get('prompt')
    if not isinstance(prompt, str) or not prompt.strip():
        raise ValueError('请输入画面描述。')
    model, ratio = body.get('model'), body.get('ratio')
    if not isinstance(model, str) or model not in MODELS:
        raise ValueError('模型或图片比例无效。')
    name, quality = MODELS[model]
    model_sizes = SIZES[name]
    nsfw = False
    if 'nsfw' in body:
        nsfw = body['nsfw']
        if not isinstance(nsfw, bool):
            raise ValueError('nsfw 须为布尔值。')
        if nsfw and name != 'image-2':
            raise ValueError('仅 Grok 支持开启 NSFW。')
    if not isinstance(ratio, str) or (ratio not in model_sizes and not (nsfw and ratio == GROK_NSFW_RATIO)):
        raise ValueError('模型或图片比例无效。')
    size = GROK_NSFW_SIZE if nsfw else model_sizes[ratio]
    payload = dict(prompt=prompt.strip(), model=name, size=size, quality=quality, n=1, response_format='url')
    if name == 'image-2' and 'nsfw' in body:
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
                    401: '请检查网关的生图 API 密钥。', 403: '请检查生图服务账户权限。',
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
        return 410, error_payload(info, '任务未交付图片，请核对任务与额度记录。', detail_text)
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

    def key_pool(self):
        pool = getattr(self.server, 'key_pool', None)
        if pool is not None:
            return pool
        key = getattr(self.server, 'api_key', '')
        return KeyPool([key]) if key else None

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
        pool = self.key_pool()
        if pool is None:
            return self.reply(500, {'error': '网关未配置生图 API 密钥。'})
        try:
            if self.command == 'GET':
                used_key = None
                status, body = 502, {}
                for candidate in pool.keys_for_task(task):
                    used_key = candidate
                    status, body = upstream(path, None, candidate)
                    if status not in (401, 403, 404):
                        break
                status, result = outcome(status, body, task)
                if status in (200, 410):
                    pool.forget(task)
                elif status == 202 and used_key:
                    pool.remember(task, used_key)
            else:
                used_key = pool.next_key()
                status, body = upstream(path, payload, used_key)
                status, result = outcome(status, body, task)
                if status == 202 and result.get('task_id'):
                    pool.remember(result['task_id'], used_key)
        except (URLError, OSError, HTTPException):
            status, result = 502, {'error': '生图服务连接中断，请核对原任务后再发起生成。'}
        self.reply(status, result)

    do_POST = do_GET = handle_api


if __name__ == '__main__':
    root_dir = Path(__file__).resolve().parent.parent
    load_dotenv(root_dir / '.env', Path(__file__).resolve().parent / '.env')
    keys = load_keys()
    if not keys:
        raise SystemExit('请在环境变量或 .env 中配置 IMAGE_API_KEYS（或 IMAGE_API_KEY）。')
    with ThreadingHTTPServer(('127.0.0.1', int(os.getenv('PORT', '8787'))), Handler) as server:
        server.key_pool = KeyPool(keys)
        print(f'见屿网关监听 127.0.0.1:{server.server_port}（已加载 {len(keys)} 个密钥轮询）', flush=True)
        server.serve_forever()
