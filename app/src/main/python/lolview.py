# -*- coding: utf-8 -*-
"""把目标站点返回的段位/对局原始结构（标准 Riot DTO），归一成界面可直接渲染的视图模型（强兼容多种字段/嵌套）。"""
import json

try:
    from champ_ids import CHAMP_ID_EN
except Exception:  # 兼容缺文件
    CHAMP_ID_EN = {}

TIER_CN = {
    "IRON": "黑铁", "BRONZE": "青铜", "SILVER": "白银", "GOLD": "黄金",
    "PLATINUM": "铂金", "EMERALD": "翡翠", "DIAMOND": "钻石",
    "MASTER": "大师", "GRANDMASTER": "傲世宗师", "CHALLENGER": "最强王者",
}
QUEUE_TITLE = {
    "RANKED_SOLO_5x5": "单双排", "RANKED_FLEX_SR": "灵活组排",
    "RANKED_TFT": "云顶之弈", "RANKED_TFT_TURBO": "狂暴模式", "RANKED_TFT_DOUBLE_UP": "双人作战",
    "JADE_RANKED_SOLO_5x5": "斗魂竞技场", "CHERRY": "斗魂竞技场",
    "RANKED_PREMADE_5x5": "灵活组排(旧)",
}
QUEUE_ID_NAME = {
    0: "全部模式", 400: "征召模式", 420: "单双排", 430: "匹配模式", 440: "灵活组排",
    450: "极地大乱斗", 480: "快速模式", 325: "灵活组排(旧)", 700: "克隆模式",
    830: "入门人机", 840: "新手人机", 850: "一般人机", 870: "入门人机", 880: "新手人机",
    890: "人机模式", 900: "无限乱斗", 920: "传说之证", 1010: "飞升争夺战",
    1020: "无限火力", 1200: "极限闪击", 1300: "终极魔典", 1700: "斗魂竞技场", 1750: "斗魂竞技场",
    1810: "极限闪击", 1900: "无限火力", 2000: "新手教程", 2010: "新手教程",
    2020: "新手教程", 2300: "神木之门", 2400: "海克斯大乱斗", 2450: "经典海斗",
    3270: "自定义·海克斯大乱斗", 4210: "末日人机", 4220: "末日人机", 4250: "末日人机",
    4310: "经典匹配",
}
MODE_CN = {"CLASSIC": "召唤师峡谷", "ARAM": "极地大乱斗", "ODIN": "水晶之痕",
           "TUTORIAL": "新手教程", "URF": "无限火力", "CHERRY": "斗魂竞技场",
           "ARENA": "斗魂竞技场", "ONEFORALL": "克隆模式", "KIWI": "海克斯大乱斗",
           "KIWI_JADE": "经典海斗", "JADE": "经典匹配", "NEXUSBLITZ": "极限闪击",
           "ULTBOOK": "终极魔典"}

# 国服大区代码 -> 中文名（serverId 形如 HN1 / TENCENT_HN1 / BGP2 等，统一去掉 TENCENT_ 前缀）
REGION_CN = {
    "HN1": "艾欧尼亚", "HN2": "祖安", "HN3": "诺克萨斯", "HN4": "班德尔城",
    "HN5": "皮尔特沃夫", "HN6": "战争学院", "HN7": "巨神峰", "HN8": "雷瑟守备",
    "HN9": "裁决之地", "HN10": "黑色玫瑰", "HN11": "暗影岛", "HN12": "钢铁烈阳",
    "HN13": "水晶之痕", "HN14": "均衡教派", "HN15": "影流", "HN16": "守望之海",
    "HN17": "征服之海", "HN18": "卡拉曼达", "HN19": "皮城警备",
    "BGP2": "峡谷之巅", "PBE": "试炼之地", "FORCES": "电信比赛服",
    "NJ100": "联盟一区", "GZ100": "联盟二区", "CQ100": "联盟三区",
    "TJ100": "联盟四区", "TJ101": "联盟五区",
}


def cn_region(code):
    if not code:
        return "—"
    key = str(code).upper().replace("TENCENT_", "").strip()
    return REGION_CN.get(key, str(code))

# 英雄英文 -> 国服中文（championName 可能是 MonkeyKing/Nunu/Renata/DrMundo 等内部名，一并覆盖）
CHAMP_CN = {
    "Aatrox": "亚托克斯", "Ahri": "阿狸", "Akali": "阿卡丽", "Akshan": "阿克尚",
    "Alistar": "阿利斯塔", "Ambessa": "安蓓萨", "Amumu": "阿木木", "Anivia": "艾尼维亚",
    "Annie": "安妮", "Aphelios": "厄斐琉斯", "Ashe": "艾希", "AurelionSol": "奥瑞利安 · 索尔",
    "Aurora": "奥萝拉", "Azir": "阿兹尔", "Bard": "巴德", "BelVeth": "卑尔维斯",
    "Blitzcrank": "布里茨", "Brand": "布兰德", "Braum": "布隆", "Briar": "贝蕾亚",
    "Caitlyn": "凯特琳", "Camille": "卡蜜尔", "Cassiopeia": "卡西奥佩娅", "Chogath": "科加斯",
    "Corki": "库奇", "Darius": "德莱厄斯", "Diana": "黛安娜", "Draven": "德莱文",
    "DrMundo": "蒙多医生", "Ekko": "艾克", "Elise": "伊莉丝", "Evelynn": "伊芙琳",
    "Ezreal": "伊泽瑞尔", "Fiddlesticks": "费德提克", "Fiora": "菲奥娜", "Fizz": "菲兹",
    "Galio": "加里奥", "Gangplank": "普朗克", "Garen": "盖伦", "Gnar": "纳尔",
    "Gragas": "古拉加斯", "Graves": "格雷福斯", "Gwen": "格温", "Hecarim": "赫卡里姆",
    "Heimerdinger": "黑默丁格", "Hwei": "彗", "Illaoi": "俄洛伊", "Irelia": "艾瑞莉娅",
    "Ivern": "艾翁", "Janna": "迦娜", "JarvanIV": "嘉文四世", "Jax": "贾克斯",
    "Jayce": "杰斯", "Jhin": "烬", "Jinx": "金克丝", "Kaisa": "卡莎", "Kalista": "卡莉丝塔",
    "Karma": "卡尔玛", "Karthus": "卡尔萨斯", "Kassadin": "卡萨丁", "Katarina": "卡特琳娜",
    "Kayle": "凯尔", "Kayn": "凯隐", "Kennen": "凯南", "Khazix": "卡兹克", "Kindred": "千珏",
    "Kled": "克烈", "KogMaw": "克格莫", "KSante": "奎桑提", "Leblanc": "乐芙兰",
    "LeeSin": "李青", "Leona": "蕾欧娜", "Lillia": "莉莉娅", "Lissandra": "丽桑卓",
    "Lucian": "卢锡安", "Lulu": "璐璐", "Lux": "拉克丝", "Malphite": "墨菲特",
    "Malzahar": "玛尔扎哈", "Maokai": "茂凯", "MasterYi": "易", "Mel": "梅尔", "Milio": "米利欧",
    "MissFortune": "厄运小姐", "MonkeyKing": "孙悟空", "Wukong": "孙悟空",
    "Mordekaiser": "莫德凯撒", "Morgana": "莫甘娜", "Naafiri": "纳亚菲利", "Nami": "娜美",
    "Nasus": "内瑟斯", "Nautilus": "诺提勒斯", "Neeko": "妮蔻", "Nidalee": "奈德丽",
    "Nilah": "尼菈", "Nocturne": "魔腾", "Nunu": "雪原双子", "Olaf": "奥拉夫",
    "Orianna": "奥莉安娜", "Ornn": "奥恩", "Pantheon": "潘森", "Poppy": "波比", "Pyke": "派克",
    "Qiyana": "奇亚娜", "Quinn": "奎因", "Rakan": "洛", "Rammus": "拉莫斯", "RekSai": "雷克塞",
    "Rell": "芮尔", "Renata": "烈娜塔", "Renekton": "雷克顿", "Rengar": "雷恩加尔",
    "Riven": "锐雯", "Rumble": "兰博", "Ryze": "瑞兹", "Samira": "莎弥拉", "Sejuani": "瑟庄妮",
    "Senna": "赛娜", "Seraphine": "萨勒芬妮", "Sett": "瑟提", "Shaco": "萨科", "Shen": "慎",
    "Shyvana": "希瓦娜", "Singed": "辛吉德", "Sion": "赛恩", "Sivir": "希维尔",
    "Skarner": "斯卡纳", "Smolder": "斯莫德", "Sona": "娑娜", "Soraka": "索拉卡",
    "Swain": "斯维因", "Sylas": "塞拉斯", "Syndra": "辛德拉", "TahmKench": "塔姆 · 肯奇",
    "Taliyah": "塔莉垭", "Talon": "泰隆", "Taric": "塔里克", "Teemo": "提莫", "Thresh": "锤石",
    "Tristana": "崔丝塔娜", "Trundle": "特朗德尔", "Tryndamere": "泰达米尔",
    "TwistedFate": "崔斯特", "Twitch": "图奇", "Udyr": "乌迪尔", "Urgot": "厄加特",
    "Varus": "韦鲁斯", "Vayne": "薇恩", "Veigar": "维迦", "Velkoz": "维克兹", "Vex": "薇古丝",
    "Vi": "蔚", "Viego": "佛耶戈", "Viktor": "维克托", "Vladimir": "弗拉基米尔",
    "Volibear": "沃利贝尔", "Warwick": "沃里克", "Xayah": "霞", "Xerath": "泽拉斯",
    "XinZhao": "赵信", "Yasuo": "亚索", "Yone": "永恩", "Yorick": "约里克", "Yuumi": "悠米",
    "Zac": "扎克", "Zed": "劫", "Zeri": "泽丽", "Ziggs": "吉格斯", "Zilean": "基兰",
    "Zoe": "佐伊", "Zyra": "婕拉",
}


def _champ_cn(name):
    if not name:
        return "未知英雄"
    if name in CHAMP_CN:
        return CHAMP_CN[name]
    key = "".join(ch for ch in str(name).lower() if ch.isalnum())
    for en, cn in CHAMP_CN.items():
        if "".join(ch for ch in en.lower() if ch.isalnum()) == key:
            return cn
    return str(name)


def _champ_id_cn(cid):
    """数字 championId -> 中文名（熟练度接口只给数字 ID）。"""
    try:
        cid = int(cid)
    except (TypeError, ValueError):
        return "未知英雄"
    en = CHAMP_ID_EN.get(cid)
    return _champ_cn(en) if en else f"英雄{cid}"


def _points_text(pts):
    pts = int(_num(pts))
    if pts >= 10000:
        return f"{round(pts/10000,1)}万"
    return str(pts)


def _num(v, default=0):
    try:
        if v is None or v == "":
            return default
        return float(v) if "." in str(v) else int(v)
    except Exception:
        return default


def _truthy_win(v):
    if isinstance(v, bool):
        return v
    return str(v).strip().lower() in ("1", "true", "win", "victory", "胜利")


def _first(obj, *keys):
    """依次在对象本身及其 stats 子对象里找第一个非空字段。"""
    if not isinstance(obj, dict):
        return None
    for k in keys:
        if obj.get(k) is not None:
            return obj.get(k)
    st = obj.get("stats")
    if isinstance(st, dict):
        for k in keys:
            if st.get(k) is not None:
                return st.get(k)
    return None


# ---------------- 段位 ----------------
def _queue_iterable(data):
    q = data.get("queues", data.get("queueList")) if isinstance(data, dict) else data
    if isinstance(q, list):
        return [x for x in q if isinstance(x, dict)]
    if isinstance(q, dict):
        out = []
        for k, v in q.items():
            if isinstance(v, dict):
                out.append(dict(v, queueType=v.get("queueType", k)))
        return out
    return []


def normalize_rank(data):
    cards = []
    for it in _queue_iterable(data or {}):
        qt = str(it.get("queueType") or it.get("queue_type") or it.get("type")
                  or it.get("name") or "").upper().replace("5X5", "5x5")
        tier = str(it.get("tier") or it.get("tierName") or it.get("ratedTier") or "NONE").upper()
        division = str(it.get("division") or it.get("rank") or it.get("tierRank")
                       or it.get("divisionName") or "").upper()
        lp = _num(_first(it, "leaguePoints", "league_points", "lp", "points", "ratedRating"))
        wins = _num(_first(it, "wins", "win", "won"))
        losses = _num(_first(it, "losses", "loss", "lost"))
        prev_tier = str(it.get("previousSeasonEndTier") or it.get("prevTier") or "NONE").upper()
        prev_div = str(it.get("previousSeasonEndRank") or it.get("prevRank") or "").upper()
        prev_placed = prev_tier not in ("NONE", "", "UNRANKED")
        prev_cn = (TIER_CN.get(prev_tier, prev_tier.title()) + prev_div) if prev_placed else "—"
        # 该上游接口常把 losses 恒返回 0（只给胜场），此时不计算胜率以免误显示 100%
        loss_known = losses > 0
        total = wins + losses
        placed = tier not in ("NONE", "", "UNRANKED")
        cards.append({
            "queueType": qt, "title": QUEUE_TITLE.get(qt, qt or "其他"),
            "tier": tier, "tierCn": TIER_CN.get(tier, "未定级" if not placed else tier.title()),
            "division": division if placed and tier not in
                        ("MASTER", "GRANDMASTER", "CHALLENGER") else "",
            "lp": int(lp), "wins": int(wins), "losses": int(losses),
            "lossKnown": loss_known,
            "winRate": round(100 * wins / total) if loss_known and total else None,
            "total": int(total),
            "placed": placed,
            "prevCn": prev_cn, "prevTier": prev_tier if prev_placed else "",
        })
    # 只展示单双排与灵活组排，其余（云顶/斗魂竞技场/旧队列）一律不显示
    KEEP = {"RANKED_SOLO_5x5", "RANKED_FLEX_SR"}
    cards = [c for c in cards if c["queueType"] in KEEP]
    pref = {"RANKED_SOLO_5x5": 0, "RANKED_FLEX_SR": 1}
    cards.sort(key=lambda c: pref.get(c["queueType"], 9))
    return cards


def _av(obj, key, default=0):
    """资产字段在响应顶层，也兼容嵌在 data 里。"""
    if not isinstance(obj, dict):
        return default
    src = obj.get("data") if isinstance(obj.get("data"), dict) else obj
    v = src.get(key)
    return v if v not in (None, "") else default


def normalize_assets(resp):
    """资产：英雄/皮肤/炫彩/守卫/图标/表情数量、全服排名、称号。无数据返回 None。"""
    if not isinstance(resp, dict):
        return None
    src = resp.get("data") if isinstance(resp.get("data"), dict) else resp
    nums = {k: int(_num(_av(resp, k))) for k in
            ("heros", "skins", "colorfuls", "guards", "icons", "emotes")}
    levels = str(_av(resp, "levels", "") or "").strip()
    titles = str(_av(resp, "titles", "") or "").strip()
    if not any(nums.values()) and not levels and not titles:
        return None
    nums["levels"] = levels or "—"
    nums["titles"] = titles or "—"
    return nums


def normalize_masteries(resp, limit=3):
    """英雄熟练度：total=有熟练度英雄数；items=Top（中文名/成就等级/积分）。"""
    if not isinstance(resp, dict):
        return None
    src = resp.get("data") if isinstance(resp.get("data"), dict) else resp
    if not isinstance(src, dict):
        return None
    raw = src.get("items") or src.get("allChampionMasteries") or []
    items = []
    for it in raw[:limit]:
        if not isinstance(it, dict):
            continue
        cid = _num(_first(it, "championId", "champion_id"))
        pts = int(_num(_first(it, "championPoints", "champion_points")))
        lvl = int(_num(_first(it, "championLevel", "champion_level")))
        items.append({"id": int(cid), "name": _champ_id_cn(cid), "level": lvl,
                      "points": pts, "pointsText": _points_text(pts)})
    return {"total": int(_num(src.get("total"))), "items": items}


# ---------------- 对局（强兼容） ----------------
def _find_base(m):
    if not isinstance(m, dict):
        return {}
    cands = []
    # 站点把真正的 Riot info 包在 m["json"]（可能是字符串）
    j = m.get("json")
    if isinstance(j, str):
        try:
            j = json.loads(j)
        except Exception:
            j = None
    if isinstance(j, dict):
        cands.append(j)
    for path in (("info",), ("data", "info"), ("match", "info")):
        cur = m
        for k in path:
            cur = cur.get(k) if isinstance(cur, dict) else None
        if isinstance(cur, dict):
            cands.append(cur)
    if isinstance(m.get("data"), dict):
        cands.append(m["data"])
    cands.append(m)
    for c in cands:
        if isinstance(c, dict) and (isinstance(c.get("participants"), list)
                                    or c.get("gameDuration") or c.get("gameMode")
                                    or c.get("queueId") is not None):
            return c
    return m


def _looks_like_participant(x):
    if not isinstance(x, dict):
        return False
    return any(k in x for k in ("kills", "championName", "championId", "win")) or \
        isinstance(x.get("stats"), dict) and any(
            k in x["stats"] for k in ("kills", "championId", "win"))


def _all_pools(base, m):
    pools = []
    if isinstance(base.get("participants"), list):
        pools.append(base["participants"])
    tb = []
    for key in ("teamA", "teamB"):
        t = base.get(key) if isinstance(base, dict) else None
        t = t or (m.get(key) if isinstance(m, dict) else None)
        if isinstance(t, list):
            for p in t:
                if isinstance(p, dict):
                    tb.append(dict(p, teamId=100 if key == "teamA" else 200))
    if tb:
        pools.append(tb)
    teams = base.get("teams") if isinstance(base, dict) else None
    if isinstance(teams, list):
        for t in teams:
            if isinstance(t, dict) and isinstance(t.get("participants"), list):
                pools.append(t["participants"])
    for key in ("target", "self", "me", "player", "targetPlayer", "summoner", "own"):
        o = (base.get(key) if isinstance(base, dict) else None) or \
            (m.get(key) if isinstance(m, dict) else None)
        if isinstance(o, dict):
            pools.append([o])
    return pools


def _pid(p):
    return str(p.get("puuid") or (p.get("player") or {}).get("puuid") or
               (p.get("riotId") or {}).get("puuid") or "")


def _find_participant(base, m, puuid, name):
    pools = _all_pools(base, m)
    # 1) puuid 精确匹配
    for pool in pools:
        for p in pool:
            if puuid and _pid(p) == str(puuid):
                return p
    # 2) 显式 isTarget
    for pool in pools:
        for p in pool:
            if p.get("isTarget"):
                return p
    # 3) 名字匹配
    if name:
        low = name.strip().lower()
        for pool in pools:
            for p in pool:
                cand = " ".join(str(_first(p, "riotIdGameName", "gameName",
                                          "summonerName", "name") or "") for _ in [0]).lower()
                if low and low in cand:
                    return p
    # 4) 元素本身就是“目标玩家”扁平结构
    if _looks_like_participant(m):
        return m
    if base is not m and _looks_like_participant(base):
        return base
    # 5) 唯一带英雄的参与者
    for pool in pools:
        champs = [p for p in pool if isinstance(p, dict)
                  and (_first(p, "championName", "championId"))]
        if len(champs) == 1:
            return champs[0]
        if len(pool) == 1:
            return pool[0]
    return {}


def _items(p):
    its = _first(p, "items")
    ids = []
    if isinstance(its, list):
        for x in its:
            if isinstance(x, dict):
                ids.append(x.get("id") or x.get("itemId"))
            else:
                ids.append(x)
    else:
        for i in range(7):
            ids.append(_first(p, f"item{i}"))
    out = []
    for x in ids:
        try:
            iv = int(x)
        except (TypeError, ValueError):
            continue
        if iv > 0:
            out.append(iv)
    return out[:7]


def normalize_match(m, puuid="", name=""):
    base = _find_base(m)
    p = _find_participant(base, m, puuid, name)
    win = _truthy_win(_first(p, "win"))
    k, d_ = _num(_first(p, "kills")), _num(_first(p, "deaths"))
    a = _num(_first(p, "assists"))
    cs = _num(_first(p, "totalMinionsKilled", "minionsKilled", "creepScore", "cs")) \
        + _num(_first(p, "neutralMinionsKilled"))
    gold = _num(_first(p, "goldEarned", "gold"))
    damage = _num(_first(p, "totalDamageDealtToChampions", "damageDealtToChampions", "damage"))
    dur = _num(_first(base, "gameDuration", "gameLength", "duration")
               or _first(p, "timePlayed", "duration"))
    if dur > 100000:
        dur /= 1000
    qid = int(_num(_first(base, "queueId") or (m.get("queueId") if isinstance(m, dict) else 0)))
    mode = str(_first(base, "gameMode") or (m.get("gameMode") if isinstance(m, dict) else "") or "").upper()
    champ = _first(p, "championName", "champion", "heroName") or "未知英雄"
    level = _num(_first(p, "champLevel", "level", "championLevel"))
    pos = str(_first(p, "teamPosition", "individualPosition", "lane", "position") or "").upper()
    pos_cn = {"TOP": "上路", "JUNGLE": "打野", "MIDDLE": "中路", "MID": "中路",
              "BOTTOM": "下路", "UTILITY": "辅助", "SUPPORT": "辅助",
              "ADC": "下路", "CARRY": "下路"}.get(pos, "")
    creation = _num(_first(base, "gameCreation", "gameEndTimestamp", "creation", "timestamp"))
    remake = 0 < dur <= 180
    kda = round((k + a) / d_, 2) if d_ > 0 else round(k + a, 2)
    cs_per_min = round(cs / (dur / 60), 1) if dur > 0 else 0
    target_team = _num(_first(p, "teamId"))
    # 同队所有玩家 puuid（含自己）；以及整场对局全部玩家 puuid（含对面）。
    # 官网“同局”规则：同一场对局出现多位被查玩家即标记，不要求同队，
    # 因此开黑判定用 all_puuids（整场），team_puuids 仅保留备用。
    team_puuids, all_puuids = [], []
    if isinstance(base.get("participants"), list):
        for pp in base["participants"]:
            if not isinstance(pp, dict):
                continue
            pu = _first(pp, "puuid", "Puuid", "playerPuuid")
            if pu:
                all_puuids.append(str(pu))
                if _num(_first(pp, "teamId")) == target_team:
                    team_puuids.append(str(pu))
    return {
        "win": win, "remake": remake,
        "resultText": "重开" if remake else ("胜利" if win else "失败"),
        "champion": str(champ), "championCn": _champ_cn(champ), "championId": int(_num(_first(p, "championId"))),
        "level": int(level), "k": int(k), "d": int(d_), "a": int(a), "kda": kda,
        "cs": int(cs), "csPerMin": cs_per_min, "gold": int(gold), "damage": int(damage),
        "items": _items(p), "position": pos_cn,
        "queueId": qid,
        "queueName": (QUEUE_ID_NAME.get(qid) if qid else None)
                     or MODE_CN.get(mode, mode or "未知"),
        "durationSec": int(dur), "durationText": f"{int(dur)//60}:{int(dur)%60:02d}",
        "gameId": int(_num(_first(base, "gameId", "matchId", "id"))),
        "creationMs": int(creation),
        "spell1": int(_num(_first(p, "summoner1Id", "spell1Id"))),
        "spell2": int(_num(_first(p, "summoner2Id", "spell2Id"))),
        "teamId": int(target_team),
        "teamPuuids": team_puuids,
        "allPuuids": all_puuids,
        "_found_target": bool(p),
    }


def summarize(views):
    if not views:
        return {"games": 0}
    real = [v for v in views if not v["remake"]]
    pool = real or views
    wins = sum(1 for v in pool if v["win"])
    games = len(pool)
    avg_kda = round(sum(v["kda"] for v in pool) / games, 2)
    streak_type, streak = pool[0]["win"], 0
    for v in pool:
        if v["win"] == streak_type:
            streak += 1
        else:
            break
    champ = {}
    for v in pool:
        cn = v.get("championCn") or v["champion"]
        c = champ.setdefault(cn, {"champion": cn, "games": 0, "wins": 0})
        c["games"] += 1
        c["wins"] += 1 if v["win"] else 0
    top = sorted(champ.values(), key=lambda x: (-x["games"], -x["wins"]))[:3]
    for c in top:
        c["winRate"] = round(100 * c["wins"] / c["games"])
    # 简易“马”评级（同官网主视图 k_：(总击杀+总助攻)/总死亡）
    tk = sum(v["k"] for v in pool); td = sum(v["d"] for v in pool)
    ta = sum(v["a"] for v in pool)
    ratio = (tk + ta) / td if td > 0 else None
    if ratio is None:
        horse, tier = ("上等马(A)", "A") if (tk + ta) > 0 else ("暂无评级", "N")
    elif ratio >= 5:
        horse, tier = "上等马(A)", "A"
    elif ratio >= 3:
        horse, tier = "中等马(B)", "B"
    elif ratio >= 2:
        horse, tier = "下等马(C)", "C"
    else:
        horse, tier = "牛马(D)", "D"
    return {"games": len(views), "wins": wins, "losses": games - wins,
            "winRate": round(100 * wins / games) if games else 0, "avgKda": avg_kda,
            "horse": horse, "horseTier": tier,
            "streak": streak,
            "streakText": (f"{streak}{'连胜' if streak_type else '连败'}") if streak >= 2 else "近期胜负交替",
            "topChampions": top}
