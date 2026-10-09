# -*- coding: utf-8 -*-
"""编排层（lolzjcx 自查版）：XFF 伪造无限注册 + 账号池 + 自动补号 + 串行轮换查询。"""
import os
import re
import json
import time
import random
import unicodedata
import threading
from datetime import datetime, timezone

from lolzjcx import LolClient, LolError, DEFAULT_BASE
import lolview

DISPLAY_LIMIT = 15   # 展示最近 15 场
DETECT_LIMIT = 25    # 开黑判定用更宽窗口
DAILY_LIMIT = 21     # 每号每日免费“接口调用”额度（free_daily_query totalQuantity）
COST_PER_PLAYER = 3  # 一个完整玩家页 = server_info + overview + match_analysis 三次调用

# 程序内置固定密码（不对用户暴露）；用户名每次随机生成，避免“前缀+连号”的批量特征
FIXED_PASSWORD = "Abc123456*"

AUTO_GROW = 1          # 查询缺号时每次自动补 1 个新号（1 个新号≈可查 7 人，足够一批）
REGISTER_DELAY = 0.8   # 自动建号间隔（秒），温和一点
# 注册不校验邮箱归属，域名在主流邮箱中随机，进一步降低批量特征
MAIL_DOMAINS = ("outlook.com", "hotmail.com", "gmail.com", "qq.com", "163.com",
                "126.com", "foxmail.com", "sina.com", "yeah.net", "aliyun.com")
DEFAULT_CONFIG = {}    # 本工具无需用户配置（保留配置读写仅为兼容数据目录结构）
LOCAL_PROXY_PORTS = [7890, 7897, 10809, 10808, 8889, 7891, 1080, 1081, 8080]

LIMIT_KEYWORDS = ("额度", "次数", "上限", "用完", "频繁", "quota", "limit",
                  "membership", "expired", "达到上限", "请明天", "冷却", "订阅")
DISABLE_KEYWORDS = ("已被禁用", "被禁用", "已禁用", "账号封禁", "被封", "封号",
                    "联系管理员", "disabled", "banned")


def _strip_invisible(s):
    if not isinstance(s, str):
        return ""
    return "".join(ch for ch in s
                   if unicodedata.category(ch) != "Cf" and ch != "​")


def parse_summoners(text):
    """解析批量召唤师输入 -> [{name,tag}]，保序去重。兼容普通多行、队伍聊天(bidi 不可见字符)、分号。"""
    if isinstance(text, list):
        parts = [str(x) for x in text]
    else:
        parts = re.split(r"[\r\n;；]+", _strip_invisible(str(text)))
    out, seen = [], set()
    noise = re.compile(r"(加入了队伍聊天|加入队伍|队伍聊天|joined\s+the\s+team|joined\s+queue).*$",
                       re.IGNORECASE)
    for raw in parts:
        seg = raw.strip()
        if not seg:
            continue
        m = re.search(r"#\s*(\d{2,})", seg)
        if not m:  # 兼容 “名字 12345”
            m = re.search(r"\s(\d{3,})\s*$", seg)
            if not m:
                continue
        tag = m.group(1)
        name = noise.sub("", seg[:m.start()])
        # 仅去掉“1. ”“2) ”“3、”这类序号前缀；不能裸删开头数字，否则纯数字名字（如 19276）会被清空
        name = re.sub(r"^\s*\d+\s*[\.\)、\-:：]\s*", "", name)
        name = re.sub(r"[\s,，、:：·\-]+$", "", name).strip()
        if not name:
            continue
        key = (name.casefold(), tag)
        if key in seen:
            continue
        seen.add(key)
        out.append({"name": name, "tag": tag})
    return out


class Engine:
    def __init__(self, base_dir):
        self.base_dir = base_dir
        self.data_dir = os.path.join(base_dir, "data_lolzjcx")   # 本工具数据目录（账号池/Cookie/配置）
        self.cookie_dir = os.path.join(self.data_dir, "cookies")
        os.makedirs(self.cookie_dir, exist_ok=True)
        self.config_path = os.path.join(self.data_dir, "config.json")
        self.accounts_path = os.path.join(self.data_dir, "accounts.json")
        self.lock = threading.RLock()
        self._acct_lock = threading.RLock()
        self._in_use = set()
        self._proxy_cache = False
        self.config = self._load_json(self.config_path, dict(DEFAULT_CONFIG))
        for k, v in DEFAULT_CONFIG.items():
            self.config.setdefault(k, v)
        self.accounts = {str(k): v for k, v in self._load_json(self.accounts_path, {}).items()}
        self.job = None
        self._worker = None

    # ---------- 基础 ----------
    @staticmethod
    def _load_json(path, default):
        try:
            with open(path, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            return default

    def _save_config(self):
        with self.lock:
            tmp = self.config_path + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(self.config, f, ensure_ascii=False, indent=2)
            os.replace(tmp, self.config_path)

    def _save_accounts(self):
        with self.lock:
            tmp = self.accounts_path + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(self.accounts, f, ensure_ascii=False, indent=2)
            os.replace(tmp, self.accounts_path)

    def update_config(self, patch: dict):
        for k, v in (patch or {}).items():
            if k in DEFAULT_CONFIG:
                self.config[k] = v
        self._save_config()
        return self.get_config()

    def get_config(self):
        return dict(self.config)

    def _http_proxy(self):
        # 不暴露给用户：优先快速直连；直连不通（如 Clash TUN 接管）才探测本机代理
        if self._proxy_cache is not False:
            return self._proxy_cache or None
        if self._proxy_reachable(None, timeout=2.5):
            self._proxy_cache = ""            # 直连可用
        else:
            self._proxy_cache = self._detect_proxy() or ""
        return self._proxy_cache or None

    @staticmethod
    def _proxy_reachable(proxy, tries=1, timeout=6):
        import http.cookiejar
        from urllib.request import build_opener, HTTPCookieProcessor, ProxyHandler, Request
        handlers = [HTTPCookieProcessor(http.cookiejar.CookieJar())]
        if proxy:
            handlers.append(ProxyHandler({"http": proxy, "https": proxy}))
        op = build_opener(*handlers)
        req = Request(DEFAULT_BASE + "/user/me",
                      headers={"User-Agent": "Mozilla/5.0", "Accept": "application/json"})
        for _ in range(tries):
            try:
                with op.open(req, timeout=timeout) as r:
                    json.loads(r.read().decode("utf-8", "replace"))
            except Exception:
                return False
        return True

    def _detect_proxy(self):
        for ev in ("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy", "ALL_PROXY", "all_proxy"):
            v = os.environ.get(ev)
            if v:
                return v
        for port in LOCAL_PROXY_PORTS:  # 短超时快速扫本机常见代理端口
            px = f"http://127.0.0.1:{port}"
            if self._proxy_reachable(px, timeout=1.2):
                return px
        return None

    def _client(self, n):
        return LolClient(os.path.join(self.cookie_dir, f"{n}.txt"), proxy=self._http_proxy())

    # ---------- 账号身份 ----------
    @staticmethod
    def _password():
        # 内置固定密码，不暴露给界面
        return FIXED_PASSWORD

    @staticmethod
    def _gen_username():
        """随机小写字母用户名（长度 8~10，字母开头），降低批量注册的可识别特征。"""
        import string
        length = random.randint(8, 10)
        letters = string.ascii_lowercase
        return random.choice(letters) + "".join(random.choice(letters) for _ in range(length - 1))

    def _rand_identity(self):
        """返回随机 (username, email, phone)。"""
        username = self._gen_username()
        domain = random.choice(MAIL_DOMAINS)
        return username, f"{username}@{domain}", self._rand_phone()

    @staticmethod
    def _rand_phone():
        # 中国大陆手机号 11 位：1 + [3-9] + 9 位随机
        return "1" + str(random.choice([3, 5, 6, 7, 8, 9])) + f"{random.randint(0, 10**9 - 1):09d}"

    # ---------- 账号池展示 ----------
    def _today(self):
        return datetime.now().astimezone().date().isoformat()

    def _remaining(self, rec):
        if rec.get("quota_date") != self._today():
            return int(rec.get("daily_limit", DAILY_LIMIT))
        return max(0, int(rec.get("remaining", rec.get("daily_limit", DAILY_LIMIT))))

    def _key_sort(self, k):
        s = str(k)
        if s.lstrip("-").isdigit():
            return (0, int(s), s)
        m = re.search(r"(\d+)$", s)
        return (1, int(m.group(1)) if m else 0, s)

    def _sorted_keys(self):
        return sorted(list(self.accounts.keys()), key=self._key_sort)

    def list_accounts(self):
        items = []
        for k in self._sorted_keys():
            r = dict(self.accounts[k])
            r["num"] = int(k) if str(k).isdigit() else k
            r["cookie_file"] = os.path.exists(os.path.join(self.cookie_dir, f"{k}.txt"))
            r["daily_quota"] = int(r.get("daily_limit", DAILY_LIMIT))
            r["remaining"] = self._remaining(r)
            r["used_today"] = r["daily_quota"] - r["remaining"]
            r["exhausted"] = r["remaining"] <= 0
            items.append(r)
        return items

    def pool_overview(self):
        items = self.list_accounts()
        avail = [x for x in items if x["remaining"] > 0 and x.get("check_status") not in ("badpass", "banned")]
        return {"count": len(items), "available": len(avail),
                "used_today": sum(x["used_today"] for x in items),
                "total_quota": sum(x["daily_quota"] for x in items),
                "total_remaining": sum(x["remaining"] for x in items)}

    def delete_account(self, num):
        k = str(num)
        with self.lock:
            if k not in self.accounts:
                return {"deleted": False, "reason": "账号不存在"}
            if k in self._in_use:
                raise RuntimeError(f"账号 {k} 查询中，无法删除")
            rec = self.accounts.pop(k)
            cf = os.path.join(self.cookie_dir, f"{k}.txt")
            removed = os.path.exists(cf)
            if removed:
                try:
                    os.remove(cf)
                except OSError:
                    removed = False
            self._save_accounts()
        return {"deleted": True, "num": k, "username": rec.get("username"), "cookie_removed": removed}

    def clear_accounts(self, mode="all"):
        invalid = {"banned", "badpass"}
        with self.lock:
            keep, removed = {}, []
            for k, rec in self.accounts.items():
                drop = False
                if mode == "all":
                    drop = True
                elif mode == "invalid":
                    # 失效 = 检测判定封禁/密码冲突，或当日额度耗尽
                    drop = rec.get("check_status") in invalid
                if drop:
                    removed.append(k)
                else:
                    keep[k] = rec
            self.accounts = keep
            for k in removed:
                cf = os.path.join(self.cookie_dir, f"{k}.txt")
                if os.path.exists(cf):
                    try:
                        os.remove(cf)
                    except OSError:
                        pass
            self._save_accounts()
        return {"removed": len(removed), "left": len(self.accounts), "nums": removed}

    def _upsert(self, n, **fields):
        with self.lock:
            rec = self.accounts.get(str(n), {})
            rec.update(fields)
            rec["num"] = n
            rec["updated_at"] = datetime.now().astimezone().isoformat(timespec="seconds")
            self.accounts[str(n)] = rec

    # ---------- 任务框架 ----------
    def _new_job(self, jtype, total, nums):
        self.job = {"type": jtype, "running": True, "total": total, "done": 0,
                    "ok": 0, "fail": 0, "skip": 0, "current": None, "nums": list(nums),
                    "logs": [], "results": [], "finished": False, "stop": False,
                    "started_at": time.time()}

    def log(self, msg):
        if self.job:
            self.job["logs"].append(f"[{datetime.now().strftime('%H:%M:%S')}] {msg}")
            self.job["logs"] = self.job["logs"][-400:]

    def job_status(self):
        return self.job

    def stop_job(self):
        if self.job:
            self.job["stop"] = True
        return {"ok": True}

    def _finish(self):
        self.job["running"] = False
        self.job["finished"] = True
        self.log(f"结束：成功 {self.job['ok']}，失败 {self.job['fail']}，跳过 {self.job['skip']}")

    # ---------- 自动建号（XFF 伪造 + 随机身份；仅在查询缺号时内部触发）----------
    def _sync_quota(self, cli, rec, today):
        """登录/检测后从站点读权威额度，回写 remaining。"""
        try:
            q = cli.subscription()
            if q:
                rec["daily_limit"] = q["total"] or DAILY_LIMIT
                rec["remaining"] = q["remaining"]
                rec["quota_date"] = today
                rec["exhausted"] = q["remaining"] <= 0
        except Exception:
            rec.setdefault("daily_limit", DAILY_LIMIT)
            rec.setdefault("remaining", DAILY_LIMIT)
            rec.setdefault("quota_date", today)

    def _ensure_one(self, n, log=lambda m: None):
        """单个本地编号：本地登录态有效→跳过；否则用随机身份伪造 XFF 直接注册并登录。"""
        today = self._today()
        password = self._password()
        cli = self._client(n)

        if str(n) in self.accounts and cli.is_logged_in():
            log(f"#{n} {self.accounts[str(n)].get('username','')} 本地登录态有效，跳过")
            return "skip"

        last_collision = None
        for attempt in range(4):  # 极小概率随机用户名/邮箱撞车，换一组随机身份重试
            username, email, phone = self._rand_identity()
            try:
                log(f"── #{n}：伪造 X-Forwarded-For 注册随机账号 {username} …")
                cli.register(username, phone, email, password)
                cli.login(username, password)
                me = cli.me()
                rec = {"username": username, "email": email, "password": password,
                       "logged_in": bool(me), "origin": "registered"}
                self._sync_quota(cli, rec, today)
                self._upsert(n, **rec)
                self._save_accounts()
                cli.save_cookies()
                log(f"#{n} 新号 {username} 注册并登录成功（剩余 {rec.get('remaining')}）")
                return "registered"
            except LolError as e:
                if e.err_code in ("ERR_USER_NAME_EXIST", "ERR_EMAIL_EXIST"):
                    last_collision = e
                    continue  # 换随机身份再来
                raise
        raise last_collision or LolError("注册失败")

    # ---------- 检测 ----------
    def start_check(self, params=None):
        if self.job and self.job["running"]:
            raise RuntimeError("已有任务在运行")
        keys = self._sorted_keys()
        if not keys:
            raise RuntimeError("账号池为空")
        self._new_job("check", len(keys), keys)
        self._worker = threading.Thread(target=self._run_check, args=(keys,), daemon=True)
        self._worker.start()
        return {"ok": True, "total": len(keys)}

    def _check_one(self, k):
        rec = self.accounts[k]
        cli = self._client(k)
        today = self._today()

        def mark(status, **extra):
            rec["check_status"] = status
            rec["checked_at"] = datetime.now().astimezone().isoformat(timespec="seconds")
            rec.update(extra)
            self._save_accounts()
            return status

        try:
            if not cli.is_logged_in():
                cli.login(rec.get("username") or k, rec.get("password") or self._password())
            cli.me()
            self._sync_quota(cli, rec, today)
            cli.save_cookies()
            return mark("ok", logged_in=True, disabled=False)
        except LolError as e:
            if self._is_disabled(e):
                return mark("banned", logged_in=False, disabled=True)
            if e.err_code == "ERR_USER_INVALID":
                return mark("badpass", logged_in=False)
            return mark("neterr")
        except Exception:
            return mark("neterr")

    def _run_check(self, keys):
        label = {"ok": "正常", "banned": "已封禁", "badpass": "登录失败(失效)", "neterr": "网络异常(待复测)"}
        try:
            for k in keys:
                if self.job["stop"]:
                    self.log("已手动停止"); break
                self.job["current"] = f"#{k}"
                rec = self.accounts.get(k, {})
                try:
                    st = self._check_one(k)
                    self.log(f"#{k}（{rec.get('username','')}）检测：{label.get(st, st)}")
                    self.job["ok"] += 1 if st == "ok" else 0
                    self.job["fail"] += 0 if st in ("ok", "neterr") else 1
                except Exception as e:
                    self.log(f"#{k} 检测异常：{str(e)[:60]}")
                finally:
                    self.job["done"] += 1
                time.sleep(0.5)
        finally:
            self._finish()

    # ---------- 查询 ----------
    @staticmethod
    def _is_limit(exc):
        s = str(exc).lower()
        if isinstance(exc, LolError) and exc.err_code == "ERR_SUBSCRIPTION_MEMBERSHIP_EXPIRED":
            return True
        return any(k.lower() in s for k in LIMIT_KEYWORDS)

    @staticmethod
    def _is_disabled(exc):
        s = str(exc).lower()
        return any(k.lower() in s for k in DISABLE_KEYWORDS)

    @staticmethod
    def _is_transient(exc):
        s = str(exc); low = s.lower()
        return ("timeout" in low) or ("timed out" in low) or ("10054" in s) or ("10060" in s) \
            or ("10061" in s) or ("reset by peer" in low) or ("broken pipe" in low) \
            or ("connection" in low and ("aborted" in low or "reset" in low or "refused" in low)) \
            or ("badstatusline" in low) or ("频繁" in s) or ("稍后" in s) or ("再试" in s) \
            or ("max retries" in low) or ("远程主机" in s)

    def _next_num(self):
        nums = [int(k) for k in self.accounts if str(k).isdigit()]
        return (max(nums) + 1) if nums else 1

    def _auto_grow(self, log):
        """池子无可用账号时，按最大编号自动 +1 注册 AUTO_GROW 个新号（查询内部触发）。"""
        cnt = AUTO_GROW
        start = self._next_num()
        log(f"账号池无可用号，自动注册 {cnt} 个新号（#{start} 起）")
        new_keys = []
        for i in range(cnt):
            n = start + i
            try:
                st = self._ensure_one(n, log)
                if st in ("registered", "existing", "skip"):
                    new_keys.append(str(n))
            except Exception as e:
                log(f"#{n} 自动补号失败：{str(e)[:80]}")
            time.sleep(REGISTER_DELAY)
        return new_keys

    def _pick_account(self, log=None, need=COST_PER_PLAYER):
        with self._acct_lock:
            keys = self._sorted_keys()
            avail = [k for k in keys
                     if self.accounts[k].get("check_status") not in ("badpass", "banned")
                     and not self.accounts[k].get("disabled")
                     and self._remaining(self.accounts[k]) >= need
                     and k not in self._in_use]
            if avail:
                k = avail[0]
                self._in_use.add(k)
                return k
        # 无可用 → 自动补号（在锁外，避免嵌套）
        new_keys = self._auto_grow(log or (lambda m: None))
        with self._acct_lock:
            for k in new_keys:
                if self._remaining(self.accounts.get(k, {})) >= need and k not in self._in_use:
                    self._in_use.add(k)
                    return k
        return None

    def _release(self, k):
        if k is None:
            return
        with self._acct_lock:
            self._in_use.discard(str(k))

    def _ensure_login(self, k):
        rec = self.accounts[k]
        cli = self._client(k)
        if cli.is_logged_in():
            return cli, rec
        cli.login(rec.get("username") or k, rec.get("password") or self._password())
        cli.save_cookies()
        return cli, rec

    def _run_with_account(self, work, cost, log=None, need=None):
        """通用账号池执行器（lolzjcx2 单人详情页用，不改动批量查询路径）：
        取号→确保登录→执行 work(cli)（返回任意可 JSON 化对象）；统一处理封禁/额度/临时错误，
        按本次实际接口调用次数 cost 扣减额度。返回 (data, account_num, remaining)。"""
        log = log or (lambda m: None)
        need = cost if need is None else need
        attempts, max_attempts = 0, 12
        transient_tried = set()
        while True:
            attempts += 1
            if attempts > max_attempts:
                raise RuntimeError("多次补号/换号/重试后仍查询失败，已停止")
            k = self._pick_account(log, need=need)
            if k is None:
                raise RuntimeError("账号池为空且自动补号失败，请检查网络/代理或手动建号")
            try:
                cli, rec = self._ensure_login(k)
                last = None
                for rt in range(5):
                    try:
                        data = work(cli)
                        last = None
                        break
                    except Exception as re_:
                        last = re_
                        if not self._is_transient(re_) or rt >= 4:
                            raise
                        wait = min(4 * (rt + 1), 16) if ("频繁" in str(re_) or "稍后" in str(re_)) else 1.2
                        log(f"#{k} 临时错误（{str(re_)[:36]}），{wait}s 后重试 {rt+2}/5")
                        time.sleep(wait)
                if last:
                    raise last
                today = self._today()
                projected = max(0, self._remaining(rec) - cost)
                server_remaining = None
                try:
                    q = cli.subscription()
                    if q:
                        rec["daily_limit"] = q["total"] or DAILY_LIMIT
                        server_remaining = q["remaining"]
                except Exception:
                    pass
                rec["quota_date"] = today
                rec["remaining"] = min(projected, server_remaining) if server_remaining is not None else projected
                rec["exhausted"] = rec["remaining"] <= 0
                self._upsert(k, last_used=datetime.now().astimezone().isoformat(timespec="seconds"),
                             queries=int(rec.get("queries", 0)) + 1,
                             remaining=self._remaining(rec), quota_date=rec.get("quota_date"),
                             daily_limit=rec.get("daily_limit", DAILY_LIMIT))
                self._save_accounts()
                remaining = self._remaining(self.accounts[k])
                self._release(k)
                return data, k, remaining
            except Exception as e:
                if self._is_disabled(e):
                    log(f"账号 #{k} 被站点禁用，弃用并自动换号")
                    self._upsert(k, check_status="banned", disabled=True, disabled_reason=str(e)[:120])
                    self._save_accounts(); self._release(k); continue
                if self._is_limit(e):
                    log(f"账号 #{k} 额度用尽，标记并自动换号/补号")
                    self._upsert(k, remaining=0, quota_date=self._today(),
                                 exhausted=True, exhausted_reason=str(e)[:120])
                    self._save_accounts(); self._release(k); continue
                if self._is_transient(e):
                    transient_tried.add(k)
                    pool = {kk for kk in self.accounts if self._remaining(self.accounts[kk]) >= need}
                    if pool and transient_tried >= pool:
                        self._release(k); raise
                    log(f"#{k} 临时错误，换号重试")
                    self._release(k); time.sleep(0.6); continue
                self._release(k); raise

    def _raw_query(self, cli, name, tag):
        si = cli.server_info(name, tag) or {}
        puuid, server_id = si.get("puuid"), si.get("serverId")
        if not puuid:
            raise LolError("后端没有返回 puuid（检查名字/尾标）")
        ov = cli.overview(server_id, puuid) or {}
        ma = cli.match_analysis(server_id, puuid, size=DETECT_LIMIT) or {}
        return si, ov, ma

    def _do_one_query(self, s, log=lambda m: None):
        attempts, max_attempts = 0, 12
        transient_tried = set()
        while True:
            attempts += 1
            if attempts > max_attempts:
                raise RuntimeError("多次补号/换号/重试后仍查询失败，已停止")
            k = self._pick_account(log)
            if k is None:
                raise RuntimeError("账号池为空且自动补号失败，请检查网络/代理或手动建号")
            try:
                cli, rec = self._ensure_login(k)
                last = None
                for rt in range(5):
                    try:
                        si, ov, ma = self._raw_query(cli, s["name"], s["tag"])
                        last = None
                        break
                    except Exception as re_:
                        last = re_
                        if not self._is_transient(re_) or rt >= 4:
                            raise
                        wait = min(4 * (rt + 1), 16) if ("频繁" in str(re_) or "稍后" in str(re_)) else 1.2
                        log(f"#{k} 临时错误（{str(re_)[:36]}），{wait}s 后重试 {rt+2}/5")
                        time.sleep(wait)
                if last:
                    raise last
                # 成功：本地先按 3 次/人扣减，再与服务端权威额度取较小值（服务端计数有短暂延迟）
                today = self._today()
                projected = max(0, self._remaining(rec) - COST_PER_PLAYER)
                server_remaining = None
                try:
                    q = cli.subscription()
                    if q:
                        rec["daily_limit"] = q["total"] or DAILY_LIMIT
                        server_remaining = q["remaining"]
                except Exception:
                    pass
                rec["quota_date"] = today
                rec["remaining"] = min(projected, server_remaining) if server_remaining is not None else projected
                rec["exhausted"] = rec["remaining"] <= 0
                break
            except Exception as e:
                if self._is_disabled(e):
                    log(f"账号 #{k} 被站点禁用，弃用并自动换号")
                    self._upsert(k, check_status="banned", disabled=True, disabled_reason=str(e)[:120])
                    self._save_accounts(); self._release(k); continue
                if self._is_limit(e):
                    log(f"账号 #{k} 额度用尽，标记并自动换号/补号")
                    self._upsert(k, remaining=0, quota_date=self._today(),
                                 exhausted=True, exhausted_reason=str(e)[:120])
                    self._save_accounts(); self._release(k); continue
                if self._is_transient(e):
                    transient_tried.add(k)
                    pool = {kk for kk in self.accounts if self._remaining(self.accounts[kk]) >= COST_PER_PLAYER}
                    if pool and transient_tried >= pool:
                        self._release(k); raise
                    log(f"#{k} 临时错误，换号重试")
                    self._release(k); time.sleep(0.6); continue
                self._release(k); raise

        # ---- 归一为视图模型 ----
        puuid = si.get("puuid"); server_id = si.get("serverId")
        base = ov.get("base") or {}
        ranked = ov.get("ranked") or {}
        raw_matches = ma.get("matchHistory") or []
        detect_views = [lolview.normalize_match(m, puuid, s["name"]) for m in raw_matches[:DETECT_LIMIT]]
        views = detect_views[:DISPLAY_LIMIT]
        coop = [{"gameId": v["gameId"], "teamId": v["teamId"], "creationMs": v["creationMs"],
                 "remake": v["remake"], "teamPuuids": v.get("teamPuuids", []),
                 "allPuuids": v.get("allPuuids", [])} for v in detect_views]
        self._upsert(k, last_used=datetime.now().astimezone().isoformat(timespec="seconds"),
                     queries=int(rec.get("queries", 0)) + 1,
                     remaining=self._remaining(rec), quota_date=rec.get("quota_date"),
                     daily_limit=rec.get("daily_limit", DAILY_LIMIT))
        self._save_accounts()
        remaining = self._remaining(self.accounts[k])
        self._release(k)
        return {
            "name": s["name"], "tag": s["tag"],
            "account_num": k, "account": rec.get("username") or k,
            "remaining": remaining,
            "summoner": {"name": base.get("gameName") or s["name"], "tag": s["tag"],
                         "region": lolview.cn_region(server_id), "regionCode": server_id, "puuid": puuid,
                         "level": base.get("level"),
                         "privacy": base.get("privacy") or "",
                         "lastGameMs": base.get("lastGameDate")},
            "rank_cards": lolview.normalize_rank(ranked),
            "assets": None,
            "masteries": None,
            "summary": lolview.summarize(views),
            "matches": views,
            "coop": coop,
            "match_count": len(views),
            "has_more": len(raw_matches) > DISPLAY_LIMIT,
            "notes": [],
        }

    def preview_summoners(self, text):
        return parse_summoners(text)

    @staticmethod
    def _query_opts(params):
        return {"num": params.get("num")}

    def query(self, params):
        name = (params.get("name") or "").strip()
        tag = str(params.get("tag") or "").strip().lstrip("#")
        if not name or not tag:
            raise ValueError("请填写召唤师名字和尾标(ID)")
        return self._do_one_query({"name": name, "tag": tag})

    # ---------- lolzjcx2 单人完整详情（透传原始 overview + match_analysis，前端自行渲染）----------
    @staticmethod
    def _page_opts(params):
        """统一解析翻页/模式参数：size 服务端上限 20，tag 即 queueId（-1=所有队列）。"""
        try:
            size = int(params.get("size", 15) or 15)
        except (TypeError, ValueError):
            size = 15
        size = max(1, min(size, 20))
        try:
            page = max(1, int(params.get("page", 1) or 1))
        except (TypeError, ValueError):
            page = 1
        try:
            qtag = int(params.get("queue", params.get("tag", -1)))
        except (TypeError, ValueError):
            qtag = -1
        return page, size, qtag

    def detail_init(self, params):
        """首次查一个召唤师：server_info + overview + match_analysis(第1页)，共 3 次调用。"""
        name = (params.get("name") or "").strip()
        tag = str(params.get("tag") or "").strip().lstrip("#")
        if not name or not tag:
            raise ValueError("请填写召唤师名字和尾标(ID)")
        page, size, qtag = self._page_opts(params)

        def work(cli):
            si = cli.server_info(name, tag) or {}
            puuid, server_id = si.get("puuid"), si.get("serverId")
            if not puuid:
                raise LolError("后端没有返回 puuid（检查名字/尾标）")
            ov = cli.overview(server_id, puuid) or {}
            ma = cli.match_analysis(server_id, puuid, size=size, page=page, tag=qtag) or {}
            return {"server_info": si, "overview": ov, "match_analysis": ma,
                    "puuid": puuid, "serverId": server_id, "page": page, "size": size, "queue": qtag}

        data, k, remaining = self._run_with_account(work, cost=3)
        data["account_num"] = k
        data["remaining"] = remaining
        return data

    def detail_page(self, params):
        """翻页 / 切换模式：只再拉一次 match_analysis（1 次调用），puuid/serverId 由前端回传。"""
        server_id = params.get("serverId") or params.get("server_id")
        puuid = params.get("puuid")
        if not server_id or not puuid:
            raise ValueError("缺少 serverId/puuid，请先初始化查询")
        page, size, qtag = self._page_opts(params)

        def work(cli):
            ma = cli.match_analysis(server_id, puuid, size=size, page=page, tag=qtag) or {}
            return {"match_analysis": ma, "puuid": puuid, "serverId": server_id,
                    "page": page, "size": size, "queue": qtag}

        data, k, remaining = self._run_with_account(work, cost=1)
        data["account_num"] = k
        data["remaining"] = remaining
        return data

    # ---------- lolzjcx3 多人完整复刻（逐人串行 detail_init，回传原始 overview+match_analysis）----------
    def start_detail_batch(self, params):
        if self.job and self.job["running"]:
            raise RuntimeError("已有任务在运行")
        summoners = parse_summoners(params.get("summoners", ""))
        if not summoners:
            raise ValueError("没有解析到任何“名字#尾标”，请每行一个或直接粘贴队伍聊天")
        if len(summoners) > 5:
            raise ValueError(f"一次最多查询 5 个，当前解析到 {len(summoners)} 个")
        _, size, qtag = self._page_opts(params)
        self._new_job("detail_batch", len(summoners), list(range(len(summoners))))
        self.job["results"] = []
        self.job["targets"] = summoners
        self.job["queue"] = qtag
        self.job["size"] = size
        self._worker = threading.Thread(target=self._run_detail_batch,
                                        args=(summoners, qtag, size), daemon=True)
        self._worker.start()
        return {"ok": True, "parsed": summoners}

    def _run_detail_batch(self, summoners, qtag, size):
        # 严格串行（并发易触发同 IP 限流/封号）；每人一次 detail_init（3 次调用），账号池自动轮换/补号
        try:
            for idx, s in enumerate(summoners, 1):
                if self.job["stop"]:
                    self.log("已手动停止"); break
                self.job["current"] = f"{idx}/{len(summoners)} {s['name']}#{s['tag']}"
                self.log(f"── 查询 {s['name']}#{s['tag']}")
                name, tag = s["name"], s["tag"]

                def work(cli, _name=name, _tag=tag):
                    si = cli.server_info(_name, _tag) or {}
                    puuid, server_id = si.get("puuid"), si.get("serverId")
                    if not puuid:
                        raise LolError("后端没有返回 puuid（检查名字/尾标）")
                    ov = cli.overview(server_id, puuid) or {}
                    ma = cli.match_analysis(server_id, puuid, size=size, page=1, tag=qtag) or {}
                    return {"server_info": si, "overview": ov, "match_analysis": ma,
                            "puuid": puuid, "serverId": server_id, "page": 1, "size": size, "queue": qtag}

                try:
                    data, k, remaining = self._run_with_account(work, cost=3, log=self.log)
                    games = len((data.get("match_analysis") or {}).get("matchHistory") or [])
                    self.job["results"].append({"ok": True, "name": name, "tag": tag,
                                                "account_num": k, "remaining": remaining, **data})
                    self.job["ok"] += 1
                    self.log(f"完成：{games} 局，使用账号 #{k}，该号剩余 {remaining}")
                except Exception as e:
                    self.job["results"].append({"ok": False, "name": name, "tag": tag,
                                                "error": str(e)})
                    self.job["fail"] += 1
                    self.log(f"{name}#{tag} 查询失败：{e}")
                finally:
                    self.job["done"] += 1
        finally:
            with self._acct_lock:
                self._in_use.clear()
            self._finish()

    def start_batch_query(self, params):
        if self.job and self.job["running"]:
            raise RuntimeError("已有任务在运行")
        self.update_config(params)
        summoners = parse_summoners(params.get("summoners", ""))
        if not summoners:
            raise ValueError("没有解析到任何“名字#尾标”，请每行一个或直接粘贴队伍聊天")
        if len(summoners) > 5:
            raise ValueError(f"一次最多查询 5 个，当前解析到 {len(summoners)} 个")
        self._new_job("batch_query", len(summoners), list(range(len(summoners))))
        self.job["results"] = []
        self.job["targets"] = summoners
        self._worker = threading.Thread(target=self._run_batch_query,
                                        args=(summoners,), daemon=True)
        self._worker.start()
        return {"ok": True, "parsed": summoners}

    def _run_batch_query(self, summoners):
        # 串行（并发易触发同 IP 限流甚至封号），结果天然按输入顺序
        try:
            for idx, s in enumerate(summoners, 1):
                if self.job["stop"]:
                    self.log("已手动停止"); break
                self.job["current"] = f"{idx}/{len(summoners)} {s['name']}#{s['tag']}"
                self.log(f"── 查询 {s['name']}#{s['tag']}")
                try:
                    r = self._do_one_query(s, self.log)
                    self.job["results"].append({"ok": True, **r})
                    self.job["ok"] += 1
                    self.log(f"完成：{r['match_count']} 局，使用账号 #{r['account_num']}，该号剩余 {r['remaining']}")
                except Exception as e:
                    self.job["results"].append({"ok": False, "name": s["name"], "tag": s["tag"],
                                               "error": str(e)})
                    self.job["fail"] += 1
                    self.log(f"{s['name']}#{s['tag']} 查询失败：{e}")
                finally:
                    self.job["done"] += 1
        finally:
            with self._acct_lock:
                self._in_use.clear()
            self._finish()
