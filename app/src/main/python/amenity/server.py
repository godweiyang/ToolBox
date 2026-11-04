"""Independent Beike local service. No LOL modules, accounts, cookies or URL proxy."""
import json
import os
import re
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs
from .housing import HOUSING

HOST = '127.0.0.1'
PORT = 17532
VERSION = '1.0.0'
SERVICE = 'amenity-local'
SCHEME = 'amenity'
HTTPD = None
LAST_ACTIVITY = time.time()


def allowed_origin(origin, bridge=False):
    if not origin:
        return True
    p = urlparse(origin)
    host = p.hostname or ''
    if origin == 'null':
        return False
    if p.scheme not in ('https', 'http') or p.username or p.password:
        return False
    if origin == 'https://appassets.androidplatform.net':
        return True
    if host in ('127.0.0.1', 'localhost'):
        return True
    if p.scheme == 'https' and (host == 'godweiyang.com' or host.endswith('.godweiyang.com')):
        return True
    return bridge and p.scheme == 'https' and bool(re.fullmatch(r'(?:map|[a-z]{2,12})\.ke\.com', host)) and origin == 'https://' + host


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def cors(self):
        origin = self.headers.get('Origin', '')
        bridge = self.path.split('?', 1)[0] in ('/api/housing/poll', '/api/housing/response', '/api/housing/bridge.js')
        if origin and allowed_origin(origin, bridge):
            self.send_header('Access-Control-Allow-Origin', origin)
        self.send_header('Vary', 'Origin')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'content-type')
        self.send_header('Access-Control-Allow-Private-Network', 'true')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')

    def permitted(self):
        bridge = self.path.split('?', 1)[0] in ('/api/housing/poll', '/api/housing/response', '/api/housing/bridge.js')
        return self.headers.get('Host', '') in ('127.0.0.1:17532', 'localhost:17532') and allowed_origin(self.headers.get('Origin', ''), bridge)

    def send(self, body, kind='application/json', status=200):
        if kind == 'application/json':
            body = json.dumps(body, ensure_ascii=False, allow_nan=False)
        raw = body.encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', kind + '; charset=utf-8')
        self.send_header('Content-Length', str(len(raw)))
        self.cors()
        self.end_headers()
        try:
            self.wfile.write(raw)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def ok(self, data):
        self.send({'ok': True, 'data': data})

    def error(self, message, status=400):
        self.send({'ok': False, 'error': str(message)[:300]}, status=status)

    def do_OPTIONS(self):
        if not self.permitted():
            return self.error('来源不允许', 403)
        self.send_response(204)
        self.cors()
        self.send_header('Access-Control-Max-Age', '300')
        self.end_headers()

    def do_GET(self):
        global LAST_ACTIVITY
        if not self.permitted():
            return self.error('来源不允许', 403)
        LAST_ACTIVITY = time.time()
        p = urlparse(self.path)
        q = parse_qs(p.query)
        try:
            if p.path == '/api/health':
                return self.ok({'service': SERVICE, 'version': VERSION, 'scheme': SCHEME,
                                'platform': os.environ.get('AMENITY_PLATFORM', sys.platform),
                                'port': PORT, 'capabilities': ['beike-map', 'beike-profile', 'community-scoring']})
            if p.path == '/api/housing/status':
                return self.ok({**HOUSING.status(), 'serviceVersion': VERSION})
            if p.path == '/api/housing/connect':
                return self.send(HOUSING.connect_page(), 'text/html')
            if p.path == '/api/housing/bridge.js':
                return self.send(HOUSING.bridge_script(), 'application/javascript')
            if p.path == '/api/housing/poll':
                return self.ok(HOUSING.poll(q.get('token', [''])[0], q.get('cityId', [''])[0],
                                           q.get('surface', ['map'])[0], q.get('host', [''])[0]))
            return self.error('not found', 404)
        except Exception as e:
            return self.error(e)

    def do_POST(self):
        global LAST_ACTIVITY
        if not self.permitted():
            return self.error('来源不允许', 403)
        LAST_ACTIVITY = time.time()
        try:
            size = int(self.headers.get('Content-Length', '0'))
            if not 0 <= size <= 1024 * 1024:
                return self.error('请求过大', 413)
            body = json.loads(self.rfile.read(size).decode('utf-8') or '{}')
            if not isinstance(body, dict):
                return self.error('请求格式无效')
            path = urlparse(self.path).path
            if path == '/api/housing/search':
                return self.ok(HOUSING.search(body))
            if path == '/api/housing/detail':
                return self.ok(HOUSING.detail(body))
            if path == '/api/housing/response':
                if not isinstance(body.get('payload'), dict):
                    return self.error('响应格式无效')
                return self.ok(HOUSING.response(body.get('token'), body.get('id'), body['payload']))
            if path == '/api/shutdown':
                self.ok(True)
                threading.Thread(target=self.server.shutdown, daemon=True).start()
                return
            return self.error('not found', 404)
        except Exception as e:
            return self.error(e)


def register_protocol():
    if not sys.platform.startswith('win') or not getattr(sys, 'frozen', False):
        return
    import winreg
    with winreg.CreateKey(winreg.HKEY_CURRENT_USER, r'Software\Classes\amenity') as key:
        winreg.SetValueEx(key, '', 0, winreg.REG_SZ, 'URL:amenity Protocol')
        winreg.SetValueEx(key, 'URL Protocol', 0, winreg.REG_SZ, '')
    with winreg.CreateKey(winreg.HKEY_CURRENT_USER, r'Software\Classes\amenity\shell\open\command') as key:
        winreg.SetValueEx(key, '', 0, winreg.REG_SZ, '"' + sys.executable + '" "%1"')


def stop():
    if HTTPD:
        threading.Thread(target=HTTPD.shutdown, daemon=True).start()


def main():
    global HTTPD, LAST_ACTIVITY
    register_protocol()
    try:
        HTTPD = ThreadingHTTPServer((HOST, PORT), Handler)
    except OSError:
        # 不杀其他占用者；已启动同服务时第二次调用直接退出。
        import urllib.request
        try:
            d = json.load(urllib.request.urlopen('http://127.0.0.1:17532/api/health', timeout=2))
            if os.environ.get('AMENITY_PLATFORM') != 'android' and d.get('data', {}).get('service') == SERVICE:
                return
        except Exception:
            pass
        raise RuntimeError('17532端口被其他程序占用')
    HTTPD.daemon_threads = True
    LAST_ACTIVITY = time.time()
    try:
        HTTPD.serve_forever()
    finally:
        HTTPD.server_close()
        HTTPD = None


EMBEDDED_LOCK = threading.RLock()
EMBEDDED_WORKER = None


def start_embedded(port=PORT):
    """Bind before returning so the host can verify readiness; no other tool imports."""
    global HTTPD, EMBEDDED_WORKER, HOUSING
    with EMBEDDED_LOCK:
        if EMBEDDED_WORKER and EMBEDDED_WORKER.is_alive():
            return HTTPD.server_address[1]
        from .housing import HousingBridge
        candidate = ThreadingHTTPServer((HOST, port), Handler)
        candidate.daemon_threads = True
        HOUSING = HousingBridge()
        HTTPD = candidate
        EMBEDDED_WORKER = threading.Thread(target=candidate.serve_forever, name='amenity-http', daemon=True)
        EMBEDDED_WORKER.start()
        return candidate.server_address[1]


def stop_embedded():
    global HTTPD, EMBEDDED_WORKER
    with EMBEDDED_LOCK:
        if HTTPD:
            HTTPD.shutdown()
            HTTPD.server_close()
        if EMBEDDED_WORKER:
            EMBEDDED_WORKER.join(timeout=5)
            if EMBEDDED_WORKER.is_alive():
                raise RuntimeError('小区服务未能停止')
        HTTPD = None
        EMBEDDED_WORKER = None


if __name__ == '__main__':
    main()
