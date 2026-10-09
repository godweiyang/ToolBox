# -*- coding: utf-8 -*-
"""本地后台服务（无界面）：只监听 127.0.0.1，把 Engine 能力以 JSON HTTP 接口暴露给
托管在 godweiyang.com/lolzjcx 的纯静态前端。浏览器干不了的事（伪造 X-Forwarded-For
注册、多账号 Cookie 池、串行轮换）都在本机这个进程里完成。

- 不对外网开放，仅绑定 loopback；
- CORS 只放行博客源 / 本机源；
- 不主动开浏览器/新标签页：已打开的页面会通过 /api/health 轮询自动连上；
- 前端通过 /api/health 探测本服务是否在运行。
"""
import json
import os
import sys
import threading
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from engine import Engine

HOST = "127.0.0.1"
PORT = 17530
VERSION = "1.5.1"   # 本机服务版本号；发布新包时与前端 LATEST_VERSION 同步递增

# 允许调用本服务的页面来源（浏览器跨域校验）
ALLOW_ORIGIN_SUFFIX = ("godweiyang.com",)
ALLOW_ORIGIN_HOST = ("127.0.0.1", "localhost")


IS_WIN = sys.platform.startswith("win")
IS_MAC = sys.platform == "darwin"
# Android（Chaquopy 内嵌）：宿主通过环境变量显式标记；前台服务常驻，不靠网页心跳续命
IS_ANDROID = os.environ.get("LOLZJCX_PLATFORM", "") == "android"


def app_dir():
    if getattr(sys, "frozen", False):
        return os.path.dirname(sys.executable)
    return os.path.dirname(os.path.abspath(__file__))


def app_display_path():
    """前端展示用的“程序位置”：macOS 打包后 sys.executable 在 .app 包内部，
    向上回溯到 .app 包路径展示；Windows/源码运行直接展示可执行文件/脚本。"""
    exe = sys.executable
    if IS_MAC and getattr(sys, "frozen", False):
        p = exe
        for _ in range(4):  # xxx.app/Contents/MacOS/<bin> 向上回到 .app
            p = os.path.dirname(p)
            if p.endswith(".app"):
                return p
    return exe


def data_base_dir():
    """账号数据根目录（账号池/Cookie/配置都在其下的 data_lolzjcx）。优先级：
    - 环境变量 LOLZJCX_DATA_DIR：由宿主指定（Android 内嵌 Python 时指向 App 私有可写目录）；
    - macOS 打包：.app 包内不应写数据（位于 /Applications 时只读，且更新会整包覆盖），
      统一放到 ~/Library/Application Support/lolzjcx；
    - Windows/源码运行：可执行文件/脚本同级目录。"""
    env_dir = os.environ.get("LOLZJCX_DATA_DIR")
    if env_dir:
        os.makedirs(env_dir, exist_ok=True)
        return env_dir
    if getattr(sys, "frozen", False) and IS_MAC:
        base = os.path.join(os.path.expanduser("~"), "Library",
                            "Application Support", "lolzjcx")
        os.makedirs(base, exist_ok=True)
        return base
    return app_dir()


ENGINE = Engine(data_base_dir())


def _boot_trace(msg):
    """启动轨迹：仅 Android 生成。安卓内嵌 CPython 没有控制台，宿主 App 会轮询
    data_lolzjcx/server_boot.log 来显示启动进度；Windows/macOS 不生成（前端靠 /api/health 判活）。"""
    if os.environ.get("LOLZJCX_PLATFORM", "").lower() != "android":
        return
    try:
        os.makedirs(ENGINE.data_dir, exist_ok=True)
        p = os.path.join(ENGINE.data_dir, "server_boot.log")
        with open(p, "a", encoding="utf-8") as f:
            f.write(f"{_time.strftime('%H:%M:%S')} {msg}\n")
    except Exception:
        pass


def open_in_file_manager(path):
    """跨平台：在系统文件管理器中打开目录。"""
    import subprocess
    try:
        if IS_WIN:
            os.startfile(path)  # noqa
        elif IS_MAC:
            subprocess.Popen(["open", path])
        else:
            subprocess.Popen(["xdg-open", path])
    except Exception:
        traceback.print_exc()

# ---- 页面心跳 / 空闲自动停止 ----
# 前端每 1.5s 带 hb 探活；所有页面关闭（或刷新/崩溃）后连续 IDLE_STOP 秒没心跳，服务自动退出
import threading as _threading
import time as _time
HB_LOCK = _threading.Lock()
LAST_PING = None          # 首次收到页面心跳后置为时间戳；None=尚未有页面连过，不自停
AUTO_STOP = True          # 前端可通过 as=0 关闭“离开自动停”
IDLE_STOP = 25            # 秒：超过此时长无心跳则自停


def page_ping(auto_stop):
    global LAST_PING, AUTO_STOP
    with HB_LOCK:
        LAST_PING = _time.time()
        AUTO_STOP = bool(auto_stop)


def idle_watchdog(httpd):
    global LAST_PING
    # Android 上前台服务由用户手动停止（App 按钮 / /api/shutdown）。
    # 移动浏览器切到后台会冻结页面定时器导致心跳中断，因此不做“离开网页自动停”，避免误杀。
    if IS_ANDROID:
        return
    while True:
        _time.sleep(2)
        with HB_LOCK:
            armed = LAST_PING is not None
            idle = armed and (_time.time() - LAST_PING > IDLE_STOP)
            on = AUTO_STOP
        if armed and idle and on:
            try:
                _threading.Thread(target=httpd.shutdown, daemon=True).start()
            except Exception:
                pass
            return


def allow_origin(origin):
    """返回可回写的 Access-Control-Allow-Origin，不匹配则不给跨域权限。"""
    if not origin:
        return "https://godweiyang.com"
    if origin == "null":  # file:// 打开的本地页面
        return "null"
    try:
        from urllib.parse import urlparse
        host = (urlparse(origin).hostname or "").lower()
    except Exception:
        host = ""
    if host.endswith(ALLOW_ORIGIN_SUFFIX) or host in ALLOW_ORIGIN_HOST:
        return origin
    return "https://godweiyang.com"


class Handler(BaseHTTPRequestHandler):
    server_version = "LolzjcxLocal/1.0"

    # ---- 工具 ----
    def _cors(self):
        origin = self.headers.get("Origin", "")
        self.send_header("Access-Control-Allow-Origin", allow_origin(origin))
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "content-type")
        # Chrome 私网访问（PNA）：安全页面访问 loopback 需要该头
        self.send_header("Access-Control-Allow-Private-Network", "true")
        self.send_header("Cache-Control", "no-store")

    def _json(self, obj, status=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self._cors()
        self.end_headers()
        self.wfile.write(body)

    def _ok(self, data=None):
        self._json({"ok": True, "data": data})

    def _err(self, msg, status=200):
        self._json({"ok": False, "error": str(msg)}, status=status)

    def _body(self):
        try:
            n = int(self.headers.get("Content-Length", 0) or 0)
            if n <= 0:
                return {}
            raw = self.rfile.read(n)
            return json.loads(raw.decode("utf-8") or "{}")
        except Exception:
            return {}

    def log_message(self, *a):
        pass  # 静默，不打控制台

    # ---- 路由 ----
    def do_OPTIONS(self):
        self.send_response(204)
        self._cors()
        # 缓存 CORS / PNA 私网预检结果 10 分钟，避免移动端每次探测都重复预检造成的偶发失败/抖动
        self.send_header("Access-Control-Max-Age", "600")
        self.end_headers()

    def do_GET(self):
        from urllib.parse import urlparse, parse_qs
        parsed = urlparse(self.path)
        path = parsed.path
        q = parse_qs(parsed.query)
        try:
            if path == "/api/health":
                if "hb" in q:   # 来自前端页面的心跳（内部单实例探测不带 hb，不会误续命）
                    page_ping(q.get("as", ["1"])[0] not in ("0", "false", "False"))
                return self._ok({"account_count": len(ENGINE.accounts), "service": "lolzjcx-local",
                                 "version": VERSION, "scheme": PROTO_SCHEME,
                                 "path": app_display_path(), "platform": sys.platform})
            if path == "/api/accounts":
                return self._ok(ENGINE.list_accounts())
            if path == "/api/pool_overview":
                return self._ok(ENGINE.pool_overview())
            if path == "/api/job_status":
                return self._ok(ENGINE.job_status())
            return self._err("not found", 404)
        except Exception as e:
            traceback.print_exc()
            return self._err(e, 500)

    def do_POST(self):
        path = self.path.split("?", 1)[0]
        body = self._body()
        try:
            if path == "/api/delete_account":
                return self._ok(ENGINE.delete_account(body.get("num")))
            if path == "/api/clear_accounts":
                return self._ok(ENGINE.clear_accounts(body.get("mode", "all")))
            if path == "/api/start_check":
                return self._ok(ENGINE.start_check())
            if path == "/api/stop_job":
                return self._ok(ENGINE.stop_job())
            if path == "/api/preview_summoners":
                return self._ok(ENGINE.preview_summoners(body.get("text", "") or ""))
            if path == "/api/start_batch_query":
                return self._ok(ENGINE.start_batch_query(body.get("params", {}) or {}))
            if path == "/api/detail_init":
                return self._ok(ENGINE.detail_init(body or {}))
            if path == "/api/detail_page":
                return self._ok(ENGINE.detail_page(body or {}))
            if path == "/api/start_detail_batch":
                return self._ok(ENGINE.start_detail_batch(body.get("params", {}) or body or {}))
            if path == "/api/open_data_dir":
                p = ENGINE.data_dir
                os.makedirs(p, exist_ok=True)
                open_in_file_manager(p)
                return self._ok(True)
            if path == "/api/shutdown":
                self._ok(True)
                def _stop(srv):
                    try:
                        srv.shutdown()          # 停止 serve_forever 循环
                    finally:
                        try:
                            srv.server_close()  # 关键：释放监听套接字，否则同进程重启会端口占用（Android）
                        except Exception:
                            pass
                threading.Thread(target=_stop, args=(self.server,), daemon=True).start()
                return
            return self._err("not found", 404)
        except Exception as e:
            traceback.print_exc()
            return self._err(e, 500)


def port_in_use(port):
    import socket
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        # Linux/安卓/macOS（BSD 语义）：开 SO_REUSEADDR，刚停服残留的 TIME_WAIT 不再误报占用，
        # 而真有监听者时 bind 仍会失败（覆盖活跃监听需要 SO_REUSEPORT），判断准确。
        # Windows 的 SO_REUSEADDR 语义不同（允许抢占活跃监听端口），开启会让探测永远报空闲、
        # 破坏单实例判断；且 Windows 本就无此 TIME_WAIT 误报，故 Windows 保持原样。
        if not IS_WIN:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            s.bind((HOST, port))
            return False
        except OSError:
            return True


def _ver_tuple(v):
    return tuple(int(x) if x.isdigit() else 0 for x in str(v or "0").split("."))


def running_health():
    """端口被占时，返回占用者的 health.data；不是本服务/取不到则返回 None。"""
    import urllib.request
    try:
        with urllib.request.urlopen(f"http://{HOST}:{PORT}/api/health", timeout=1.5) as r:
            data = json.loads(r.read().decode("utf-8"))
        d = data.get("data", {})
        return d if d.get("service") == "lolzjcx-local" else None
    except Exception:
        return None


def stop_running_service():
    """请求已在运行的本服务退出，并等待端口释放（用于双击新版时自动替换旧版）。"""
    import urllib.request, time, socket
    try:
        req = urllib.request.Request(f"http://{HOST}:{PORT}/api/shutdown", data=b"{}", method="POST")
        urllib.request.urlopen(req, timeout=2).read()
    except Exception:
        pass
    for _ in range(40):  # 最多等 4 秒
        if not port_in_use(PORT):
            return True
        time.sleep(0.1)
    return not port_in_use(PORT)


def dev_channel():
    """判定当前是否为“开发构建”：frozen 可执行文件位于工程目录内（向上若干级内能找到
    server.py 与 .spec）就算 dev，使用独立协议，避免劫持正式 lolzjcx:// 协议。
    Windows 的 exe 向上两级即可；macOS 的可执行文件在 xxx.app/Contents/MacOS/ 下，
    层级更深，因此统一向上多找几级。下载到普通目录的正式包找不到这些工程文件，走正式协议。"""
    if not getattr(sys, "frozen", False):
        return True  # 直接跑源码也算开发
    d = os.path.dirname(sys.executable)
    for _ in range(6):
        if os.path.exists(os.path.join(d, "server.py")) and glob_exists_spec(d):
            return True
        nd = os.path.dirname(d)
        if nd == d:
            break
        d = nd
    return False


def glob_exists_spec(folder):
    try:
        return any(f.endswith(".spec") for f in os.listdir(folder))
    except Exception:
        return False


PROTO_SCHEME = "lolzjcxdev" if dev_channel() else "lolzjcx"


def register_protocol():
    """自注册自定义协议；每次启动重写以自愈路径变更。
    正式包注册 lolzjcx://，工程内的开发构建注册 lolzjcxdev://，互不干扰。
    - Windows：写当前用户注册表（无需管理员）。
    - macOS：协议由打包时 .app 的 Info.plist（CFBundleURLTypes）声明并由
      LaunchServices 登记，运行时无需、也无法这样写注册表，因此直接返回。"""
    if not getattr(sys, "frozen", False):
        return
    if not IS_WIN:
        return
    scheme = PROTO_SCHEME
    try:
        import winreg
        cmd = f'"{sys.executable}" "%1"'
        k = winreg.CreateKey(winreg.HKEY_CURRENT_USER, rf"Software\Classes\{scheme}")
        winreg.SetValueEx(k, "", 0, winreg.REG_SZ, f"URL:{scheme} Protocol")
        winreg.SetValueEx(k, "URL Protocol", 0, winreg.REG_SZ, "")
        sub = winreg.CreateKey(k, r"shell\open\command")
        winreg.SetValueEx(sub, "", 0, winreg.REG_SZ, cmd)
        winreg.CloseKey(sub); winreg.CloseKey(k)
    except Exception:
        traceback.print_exc()


def show_alert(text, title="lolzjcx 本地服务"):
    """无窗口程序出错时用系统弹窗提示，避免静默失败（跨平台）。"""
    try:
        if IS_WIN:
            import ctypes
            ctypes.windll.user32.MessageBoxW(0, text, title, 0x10)  # MB_ICONERROR
        elif IS_MAC:
            import subprocess
            # 转义双引号，经 osascript 弹出原生对话框
            msg = text.replace('"', '\\"')
            ttl = title.replace('"', '\\"')
            subprocess.run(["osascript", "-e",
                            f'display dialog "{msg}" with title "{ttl}" buttons {{"好"}} with icon caution'],
                           timeout=20, check=False)
    except Exception:
        pass


def main():
    _boot_trace(f"main() enter, platform={sys.platform}, frozen={getattr(sys,'frozen',False)}, scheme={PROTO_SCHEME}")
    try:
        # 任何时刻只保留一个服务：
        #  - 端口被同版本/更新版本的本服务占用 → 静默退出（不启动第二个）
        #  - 被更旧版本占用（双击了新 exe）→ 停掉旧的、由当前新版接管
        #  - 被其他程序占用 → 明确报错
        in_use = port_in_use(PORT)
        _boot_trace(f"port_in_use({PORT})={in_use}")
        if in_use:
            info = running_health()
            _boot_trace(f"running_health={info}")
            if info is None:
                # 探测到端口占用但没有本服务应答：可能是旧实例正在关闭 / TIME_WAIT 残留
                # （移动端同进程“停止→再启动”常见）。短暂等待其释放，而不是立刻判为被其它程序占用。
                freed = False
                for _ in range(20):  # 最多约 3 秒
                    _time.sleep(0.15)
                    if not port_in_use(PORT):
                        freed = True
                        break
                    info = running_health()
                    if info is not None:
                        break
                if freed:
                    _boot_trace("旧实例已释放端口，继续由本实例绑定")
                    in_use, info = False, None
            if in_use and info is None:
                hint = (f"可用 netstat -ano | findstr {PORT} 查看。" if IS_WIN
                        else f"可用 lsof -i :{PORT} 查看。")
                _boot_trace("端口被非本服务占用 → 退出")
                show_alert(f"端口 {PORT} 已被其他程序占用，本地服务无法启动。\n"
                           f"请关闭占用该端口的程序后重试（{hint}）")
                return
            other_scheme = info.get("scheme") or ""
            same_channel = (other_scheme == PROTO_SCHEME)
            # 同一通道(dev/dev 或 正式/正式)且对方同版/更新 → 不重复启动；
            # 通道不同（如正在跑 dev，双击正式包切换）或对方更旧 → 停掉对方由本程序接管
            if same_channel and _ver_tuple(info.get("version", "0.0.0")) >= _ver_tuple(VERSION):
                _boot_trace("已有同通道同版/更新服务 → 静默退出")
                return
            if not stop_running_service():
                _boot_trace("无法停掉旧服务 → 退出")
                show_alert("检测到正在运行的服务无法停止，请先在网页上关闭服务后再切换/更新。")
                return
        register_protocol()
        # 绑定重试：旧实例刚收到 shutdown、套接字可能还在释放（尤其 Android 同进程重启），
        # port_in_use 探测为空闲但 bind 仍可能短暂失败，重试若干次。
        httpd = None
        last_err = None
        for attempt in range(6):
            try:
                _boot_trace(f"准备 ThreadingHTTPServer 绑定 …（第 {attempt+1} 次）")
                httpd = ThreadingHTTPServer((HOST, PORT), Handler)
                break
            except OSError as e:
                last_err = e
                _boot_trace(f"绑定失败（{e!r}），0.5s 后重试")
                _time.sleep(0.5)
        if httpd is None:
            raise last_err if last_err else OSError("绑定失败")
        _boot_trace(f"绑定成功 {HOST}:{PORT}，启动 serve_forever 线程")
    except Exception as e:
        traceback.print_exc()
        _boot_trace(f"启动异常: {e!r}")
        show_alert(f"本地服务启动失败：{e}")
        return
    t = threading.Thread(target=httpd.serve_forever, daemon=True)
    t.start()
    # 所有前端页面关闭后，空闲超时自动停止本服务
    threading.Thread(target=idle_watchdog, args=(httpd,), daemon=True).start()
    _boot_trace("服务已进入监听循环")
    # 不主动打开浏览器：用户本来就在页面上，页面轮询到本服务后会自动连上
    try:
        while t.is_alive():
            t.join(1)
    except KeyboardInterrupt:
        httpd.shutdown()
    try:
        httpd.server_close()   # 兜底释放监听端口（/api/shutdown 路径已关一次，重复关闭无害）
    except Exception:
        pass
    _boot_trace("main() 结束，监听循环退出")


if __name__ == "__main__":
    main()
