# -*- coding: utf-8 -*-
"""外媒·宏观·名人·券商宏观策略 新闻情报扩展（_market_scan 附加组数据源）。

2026-09-04 探针结论：外网直连（Google News / Reuters RSS）在本机不可达；
以下国内可达聚合源已覆盖用户需求：
  1. 东财全球财经快讯(column=100)：贝莱德/美商务部/欧洲央行等外媒财经
  2. 东财要闻快讯(column=103)：美股盘面（苹果/特斯拉/英伟达）、AH 股公司动态
  3. 新浪财经 7x24 直播：国际政经/科技名人（特朗普/美联储/Anthropic/OpenAI 等）
  4. 东财研报中心 qType=1：券商宏观/策略/行业报告

用法：
  python _news_watch.py                    # 采集并打印分类结果
  被 _market_scan.run_once 调用：cur["ext"] = collect_ext()
"""
import datetime
import json
import os
import re
import sys
import time

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import requests  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
SNAP = os.path.join(HERE, "_news_watch_snap.json")
AUTH_SNAP = os.path.join(HERE, "_news_authoritative.json")
HEADERS = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}
PROXIES = {"http": None, "https": None}
TIMEOUT = 8

# 外媒/宏观快讯命中词（世界财经、货币政策、地缘金融）
WORLD_KW = ("美联储", "鲍威尔", "加息", "降息", "利率", "关税", "非农", "CPI", "通胀",
            "欧洲央行", "英国央行", "日本央行", "美债", "美元", "欧元", "贝莱德",
            "高盛", "摩根", "花旗", "美国", "欧洲", "特朗普", "白宫", "商务部",
            "纳斯达克", "标普", "道指", "原油", "黄金")
# 名人/明星公司/大行动态命中词（其消息直接驱动情绪与题材）
NAME_KW = ("马斯克", "特斯拉", "英伟达", "黄仁勋", "OpenAI", "Anthropic", "谷歌",
           "微软", "苹果", "Meta", "扎克伯格", "亚马逊", "贝佐斯", "台积电",
           "巴菲特", "比尔盖茨", "黄奇帆", "巴菲特", "花旗", "瑞银", "高盛", "摩根大通")
# 排除噪音
SKIP_KW = ("泥石流", "遇难", "武装", "袭击", "空袭", "北约", "俄罗斯国防部", "乌克兰武装")

# ── 条目结构 + 原文网页归档（2026-09-17 用户需求）──────────────────────
# 用户要求：参考过的财经/机构消息，把「对应的网页」先保留下来放进 json，方便整理。
# 统一条目 = {"title","url","time","src"}；_titles() 提取标题，
# 保证 collect_ext() 等只吃标题的既有消费方向后兼容。
LINKS_FILE = os.path.join(HERE, "_records", "_news_links.jsonl")
LINKS_MAX = 20000                # 归档上限（超量按时间截断；按 URL/标题去重，重复自动跳过）
_SRC_CACHE = {}                  # 源级短期缓存：同一轮采集内不重复抓同一源
_SRC_TTL = 300                   # 5 分钟（与 collect_authoritative 缓存同拍）


def _cache_get(key):
    e = _SRC_CACHE.get(key)
    if e and (time.time() - e[0]) < _SRC_TTL:
        return e[1]
    return None


def _cache_put(key, val):
    """仅缓存非空结果（抓取失败不缓存，下轮重试）。"""
    if val:
        _SRC_CACHE[key] = (time.time(), val)
    return val


def _mk(title, url="", tm="", src=""):
    """构造统一新闻条目。"""
    return {"title": (title or "").strip(), "url": url or "",
            "time": tm or "", "src": src or ""}


def _titles(items):
    """从条目列表提取标题（兼容纯字符串，旧消费方不受影响）。"""
    return [(x.get("title") if isinstance(x, dict) else x) or "" for x in items]


def _east_url(code):
    """东财快讯 code → 原文网页 URL。"""
    code = str(code or "").strip()
    return "https://finance.eastmoney.com/a/%s.html" % code if code else ""


def archive_links(groups):
    """把带 URL 的条目追加进 _records/_news_links.jsonl（按 url/标题去重，供长期整理）。"""
    rows, seen = [], set()
    try:
        if os.path.exists(LINKS_FILE):
            with open(LINKS_FILE, encoding="utf-8") as f:
                for ln in f:
                    try:
                        d = json.loads(ln)
                    except ValueError:
                        continue
                    seen.add(d.get("url") or d.get("title") or "")
        for grp, items in (groups or {}).items():
            for x in items:
                if isinstance(x, dict):
                    title, url = (x.get("title") or "")[:120], x.get("url") or ""
                    tm, src = x.get("time") or "", x.get("src") or grp
                else:
                    title, url, tm, src = str(x)[:120], "", "", grp
                if not title:
                    continue
                key = url or title
                if key in seen:
                    continue
                seen.add(key)
                rows.append({"at": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                             "group": grp, "src": src, "time": tm,
                             "title": title, "url": url})
        if rows:
            os.makedirs(os.path.dirname(LINKS_FILE), exist_ok=True)
            with open(LINKS_FILE, "a", encoding="utf-8") as f:
                for r in rows:
                    f.write(json.dumps(r, ensure_ascii=False) + "\n")
            _trim_links()
        return len(rows)
    except Exception as e:  # noqa: BLE001
        print("[news_watch] 链接归档失败: %s" % e, file=sys.stderr)
        return 0


def _trim_links():
    try:
        with open(LINKS_FILE, encoding="utf-8") as f:
            rows = [ln for ln in f if ln.strip()]
        if len(rows) > LINKS_MAX:
            with open(LINKS_FILE, "w", encoding="utf-8") as f:
                f.writelines(rows[-LINKS_MAX:])
    except OSError:
        pass

# ── 2026-09-09：气候/极端天气大事件主动关注（厄尔尼诺/拉尼娜等）──
# 用户指出"厄尔尼诺此前是告知后才挖掘，之后要主动盯——这是大事件"。
# 机制：1) 东财快讯/新浪7x24 文本关键词命中 → climate 组（先于行业消息面推送）；
#       2) NOAA ONI(厄尔尼诺3.4区)指数自动拉取（外网可达时生效、2天缓存，
#          不可达静默跳过，绝不阻塞情报主流程）。
CLIMATE_KW = ("厄尔尼诺", "拉尼娜", "ENSO", "厄尔尼", "暖冬", "冷冬", "倒春寒",
              "台风", "超强台风", "高温", "热浪", "寒潮", "暴雨", "洪涝",
              "干旱", "极端天气", "气候大会", "拉尼娜事件", "厄尔尼诺事件")
CLIMATE_TAG = {
    "厄尔尼诺": "→ 糖/棕榈油/铜矿/制冷需求(空调啤酒)，南美干旱扰动农产品",
    "拉尼娜": "→ 冷冬取暖(煤炭天然气)、农业防冻、风电增强，需防冻害题材",
    "台风": "→ 农业受灾/保险/灾后重建(基建管材)，沿海港口航运扰动",
    "高温": "→ 电力负荷/空调/啤酒饮料/冷链，农产品干旱减产",
    "热浪": "→ 电力负荷/空调/啤酒饮料/冷链，农产品干旱减产",
    "暴雨": "→ 水利/城市应急/基建管材，农业渍涝减产",
    "洪涝": "→ 水利/城市应急/基建管材，农业渍涝减产",
    "干旱": "→ 粮食/糖/棉花涨价预期，灌溉水利",
    "寒潮": "→ 煤炭/天然气取暖需求，农业防冻(蔬菜/水果)",
    "冷冬": "→ 煤炭/天然气取暖需求，羽绒服/防寒服饰",
    "暖冬": "→ 取暖能源需求弱，羽绒服库存压力",
    "倒春寒": "→ 果树花期冻害(苹果/梨/柑橘)，防冻题材",
}
CLIMATE_KW = tuple(dict.fromkeys(CLIMATE_KW))  # 去重保留顺序


def _get(url, params):
    r = requests.get(url, params=params, timeout=TIMEOUT, headers=HEADERS, proxies=PROXIES)
    r.raise_for_status()
    return r.json()


def _u(name, fallback):
    """2026-09-19：URL 统一走 data/datasources.json（_sources）；缺失回退硬编码。"""
    try:
        import _sources as _src
        return _src.url(name)
    except Exception:  # noqa: BLE001
        return fallback


def _east_fast(column):
    """东财快讯列表：column=100 全球 / 103 要闻。返回条目（含原文网页 URL）。"""
    key = "east:%s" % column
    hit = _cache_get(key)
    if hit is not None:
        return hit
    try:
        data = _get(_u("east_fast_news",
                       "https://np-listapi.eastmoney.com/comm/web/getFastNewsList"),
                    {"client": "web", "biz": "web_724", "fastColumn": column,
                     "sortEnd": "", "pageSize": 30, "req_trace": "1"})
        out = []
        for it in ((data.get("data") or {}).get("fastNewsList") or []):
            t = it.get("title") or it.get("summary") or ""
            if not t:
                continue
            out.append(_mk(t, it.get("uniqueUrl") or _east_url(it.get("code")),
                           it.get("showTime") or it.get("digestTime") or "", "eastmoney"))
        return _cache_put(key, out)
    except Exception as e:  # noqa: BLE001
        print("[news_watch] 东财快讯 column=%s 失败: %s" % (column, e), file=sys.stderr)
        return []


def _sina_live():
    """新浪财经 7x24 直播（国际政经/科技名人浓度高）。返回条目。"""
    key = "sina"
    hit = _cache_get(key)
    if hit is not None:
        return hit
    try:
        data = _get(_u("sina_zhibo", "https://zhibo.sina.com.cn/api/zhibo/feed"),
                    {"page": 1, "page_size": 40, "zhibo_id": 152, "tag_id": 0,
                     "dire": "f", "dpc": 1})
        feed = (((data.get("result") or {}).get("data") or {}).get("feed") or {}).get("list") or []
        out = []
        for it in feed:
            t = (it.get("rich_text") or it.get("content") or "").strip()
            if not t:
                continue
            out.append(_mk(t, it.get("docurl") or it.get("url") or "",
                           it.get("create_time") or "", "sina"))
        return _cache_put(key, out)
    except Exception as e:  # noqa: BLE001
        print("[news_watch] 新浪 7x24 失败: %s" % e, file=sys.stderr)
        return []


def _east_macro_reports():
    """东财研报中心 qType=1 宏观/策略/行业报告。返回条目（含研报原文页）。"""
    key = "east:report"
    hit = _cache_get(key)
    if hit is not None:
        return hit
    try:
        end = datetime.date.today()
        begin = end - datetime.timedelta(days=3)
        data = _get(_u("east_reportapi", "https://reportapi.eastmoney.com/report/list"),
                    {"industryCode": "*", "pageSize": 8, "pageNo": 1, "qType": 1,
                     "code": "*", "beginTime": begin.strftime("%Y-%m-%d"),
                     "endTime": end.strftime("%Y-%m-%d")})
        out = []
        for it in (data.get("data") or []):
            info = it.get("infoCode") or ""
            out.append(_mk("%s|%s" % (it.get("orgSName") or "", (it.get("title") or "")[:40]),
                           "https://data.eastmoney.com/report/info/%s.html" % info if info else "",
                           (it.get("publishDate") or "")[:10], "eastmoney_report"))
        return _cache_put(key, out)
    except Exception as e:  # noqa: BLE001
        print("[news_watch] 东财宏观研报失败: %s" % e, file=sys.stderr)
        return []


def _clim_tag(txt):
    """给气候新闻补一个板块提示（命中多个关键词时取首个映射），无映射返回 None。"""
    for k, tag in CLIMATE_TAG.items():
        if k in txt:
            return tag
    return None


def fetch_oni_state():
    """NOAA ONI(厄尔尼诺3.4区)自动拉取 → 状态文本/None。

    数据：https://www.cpc.ncep.noaa.gov/data/indices/oni.ascii（每季滚动值）。
    外网可达时约 2 天更新一次；不可达静默返回 None（并 1 小时内不重试），
    不影响 _market_scan 主流程 —— 新闻关键词命中是保底通道。
    """
    cache = os.path.join(HERE, "_oni_state.json")
    try:
        if os.path.exists(cache):
            with open(cache, "r", encoding="utf-8") as f:
                d = json.load(f)
            if time.time() - d.get("ts", 0) < (172800 if d.get("ok") else 3600):
                return d.get("text") or None
        r = requests.get(_u("cpc_noaa_oni",
                        "https://www.cpc.ncep.noaa.gov/data/indices/oni.ascii"),
                         timeout=8, headers=HEADERS, proxies=PROXIES)
        r.raise_for_status()
        rows = [ln.split() for ln in r.text.splitlines() if ln.strip()]
        rec = rows[-1]  # 末行 = 最新季度
        if len(rec) < 4:
            raise ValueError("oni.ascii 末行字段不足: %r" % rec)
        season, year, oni = rec[0], rec[1], float(rec[3])
        if oni >= 0.5:
            tag, hint = "厄尔尼诺", "（≥+0.5°C 连续季为事件）"
        elif oni <= -0.5:
            tag, hint = "拉尼娜", "（≤-0.5°C 连续季为事件）"
        else:
            tag, hint = "中性", "（未达 ±0.5°C 事件阈值）"
        tag_txt = CLIMATE_TAG.get("厄尔尼诺" if tag == "厄尔尼诺" else
                                  ("拉尼娜" if tag == "拉尼娜" else ""), "")
        text = ("NOAA ONI 厄尔尼诺3.4区 %s %s = %+.1f°C → %s %s %s"
                % (season, year, oni, tag, hint, tag_txt)).strip()
        with open(cache, "w", encoding="utf-8") as f:
            json.dump({"ts": time.time(), "ok": True, "text": text}, f, ensure_ascii=False)
        return text
    except Exception as e:  # noqa: BLE001（外网不通是常态，静默跳过）
        try:
            with open(cache, "w", encoding="utf-8") as f:
                json.dump({"ts": time.time(), "ok": False}, f)
        except OSError:
            pass
        return None


# ── 权威源收敛（2026-09-17 用户口径）──────────────────────────────────
# 用户指出：新闻解析量太大（每题都读几十条快讯）导致上下文消耗高。
# 收敛为「美国 1 财经 + 1 政治 + 1 机构 / 中国 1 财经 + 1 政治 + 1 机构」六类权威线索。
# 定向源（华尔街见闻 / 美联储 RSS）优先；不可达时退化为东财+新浪聚合按关键词过滤。
AUTH_KW = {
    "us_finance": ("美联储", "鲍威尔", "美国CPI", "美国PPI", "非农", "美债",
                   "美元指数", "纳斯达克", "标普", "道指", "美国财政部", "美财长",
                   "美国通胀", "美联储主席", "美债收益率"),
    "us_politics": ("白宫", "特朗普", "美国国会", "参议院", "众议院", "美国商务部",
                    "美国大选", "美国国务卿", "对华关税", "美国司法部", "对华"),
    "us_agency": ("美联储", "FOMC", "IMF", "世界银行", "SEC", "美国证监会",
                  "美国财政部", "点阵图", "利率决议", "美联储官员", "鲍曼", "沃勒"),
    "cn_finance": ("央行", "证监会", "A股", "沪指", "人民币", "LPR", "MLF",
                   "社融", "财联社", "两市", "北向", "存款准备金"),
    "cn_politics": ("政治局", "国务院", "国常会", "中央经济工作会议", "新华社",
                    "人民日报", "两会", "全国人大", "中共中央"),
    "cn_agency": ("央行", "人民银行", "发改委", "统计局", "财政部", "工信部",
                  "银保监", "外汇局", "国资委", "市场监管总局"),
}
AUTH_LABEL = {
    "us_finance": "🇺🇸 美·财经", "us_politics": "🇺🇸 美·政治", "us_agency": "🇺🇸 美·机构",
    "cn_finance": "🇨🇳 中·财经", "cn_politics": "🇨🇳 中·政治", "cn_agency": "🇨🇳 中·机构",
}
AUTH_PER_MAX = 3                 # 每类最多保留条数
_AUTH_TTL = 300                  # 5 分钟复用缓存（同源同内容不重复抓/不重复进上下文）
_AUTH_CACHE = {"ts": 0.0, "data": None}


def _wallstreetcn_lives(limit=20):
    """华尔街见闻实时快讯（美·财经定向源，国内通常可达）。返回条目（含原文页）。"""
    key = "wscn"
    hit = _cache_get(key)
    if hit is not None:
        return hit
    try:
        data = _get(_u("wallstreetcn", "https://api-one.wallstcn.com/apiv1/content/lives"),
                    {"channel": "global-channel", "client": "pc",
                     "limit": limit, "first_page": "true"})
        items = (data.get("data") or {}).get("items") or []
        out = []
        for it in items:
            t = (it.get("content_text") or it.get("title") or "").strip()
            if not t:
                continue
            url = it.get("uri") or ""
            if url and not url.startswith("http"):
                url = "https://wallstreetcn.com" + url
            if not url and it.get("id"):
                url = "https://wallstreetcn.com/livenews/%s" % it.get("id")
            out.append(_mk(t, url, it.get("display_time")
                           and datetime.datetime.fromtimestamp(
                               it["display_time"]).strftime("%Y-%m-%d %H:%M") or "",
                           "wallstreetcn"))
        return _cache_put(key, out)
    except Exception as e:  # noqa: BLE001
        print("[news_watch] 华尔街见闻不可达: %s" % e, file=sys.stderr)
        return []


def _fed_rss(limit=10):
    """美联储官网新闻稿 RSS（美·机构定向源；外网不通时静默）。返回条目（含公报页链接）。"""
    key = "fed"
    hit = _cache_get(key)
    if hit is not None:
        return hit
    try:
        r = requests.get(_u("fed_press_rss",
                            "https://www.federalreserve.gov/feeds/press_all.xml"),
                         timeout=TIMEOUT, headers=HEADERS, proxies=PROXIES)
        r.raise_for_status()
        out = []
        for blk in re.findall(r"<item>(.*?)</item>", r.text, re.S)[:limit]:
            m = re.search(r"<title>(?:<!\[CDATA\[)?(.*?)(?:\]\]>)?</title>", blk, re.S)
            lk = re.search(r"<link>(?:<!\[CDATA\[)?(.*?)(?:\]\]>)?</link>", blk, re.S)
            pub = re.search(r"<pubDate>(.*?)</pubDate>", blk, re.S)
            t = (m.group(1).strip() if m else "")
            if t:
                out.append(_mk(t, (lk.group(1).strip() if lk else ""),
                               (pub.group(1).strip() if pub else ""), "federalreserve"))
        if out:
            return _cache_put(key, out)
        titles = re.findall(r"<title><!\[CDATA\[(.*?)\]\]></title>", r.text)
        if not titles:
            titles = re.findall(r"<title>(.*?)</title>", r.text)
        fb = [_mk(t, "", "", "federalreserve") for t in titles[1:limit + 1] if t.strip()]
        return _cache_put(key, fb)
    except Exception as e:  # noqa: BLE001
        print("[news_watch] 美联储RSS不可达: %s" % e, file=sys.stderr)
        return []


def collect_authoritative(force=False):
    """只收「美/中各 1 财经 + 1 政治 + 1 机构」六类权威线索。

    返回 {ts, us_finance: [条目…], us_politics: […], …}，每类 ≤AUTH_PER_MAX 条，
    条目 = {title, url, time, src}（**url 保留原文网页**，方便整理）。
    写 _news_authoritative.json；同时把带 URL 的条目追加进 _records/_news_links.jsonl。
    5 分钟内重复调用直接复用缓存（去重复用，省抓取 + 省上下文）。
    """
    now = time.time()
    if not force and _AUTH_CACHE["data"] and (now - _AUTH_CACHE["ts"]) < _AUTH_TTL:
        return _AUTH_CACHE["data"]

    g100 = _east_fast(100)
    g103 = _east_fast(103)
    sina = _sina_live()
    wscn = _wallstreetcn_lives()
    fed = _fed_rss()

    cand = {
        "us_finance": wscn + g100,
        "us_politics": sina + g103,
        "us_agency": fed + g100,
        "cn_finance": g103 + sina,
        "cn_politics": g103 + sina,
        "cn_agency": g103 + sina,
    }
    out = {"ts": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")}
    seen = set()
    for key, kws in AUTH_KW.items():
        picked = []
        for it in cand[key]:
            t = (it.get("title") if isinstance(it, dict) else it) or ""
            if not t or any(k in t for k in SKIP_KW) or t in seen:
                continue
            if any(k in t for k in kws):
                seen.add(t)
                e = dict(it) if isinstance(it, dict) else _mk(t)
                e["title"] = t[:70]
                picked.append(e)
            if len(picked) >= AUTH_PER_MAX:
                break
        out[key] = picked
    _AUTH_CACHE.update(ts=now, data=out)
    try:
        with open(AUTH_SNAP, "w", encoding="utf-8") as f:
            json.dump(out, f, ensure_ascii=False, indent=1)
    except OSError:
        pass
    # 2026-09-17 用户口径：所有「访问过的源网页」全部留档，不限于六类命中；
    # 同一 URL/标题重复的由 archive_links 自动跳过（先归档全源，再去重补充六类）。
    added = archive_links({
        "east_global": g100, "east_news": g103, "sina_724": sina,
        "wallstreetcn": wscn, "fed_rss": fed,
    })
    added += archive_links({k: out[k] for k in AUTH_KW})
    n = sum(len(out[k]) for k in AUTH_KW)
    print("[news_watch] 权威源采集 %d 条 / 全源留档新增 %d 条（含原文 URL）" % (n, added))
    return out


def collect_ext():
    """采集并归类 → 返回 dict；同时写 _news_watch_snap.json。

    2026-09-09 起气候事件(厄尔尼诺/拉尼娜/极端天气)最优先：climate 组先收，
    命中的文本不再重复进 names/world（seen 去重），保证大盘前能主动看到。
    """
    t0 = time.time()
    raw100 = _east_fast(100)    # 全球（外媒财经）
    raw103 = _east_fast(103)    # 要闻（美股盘面/AH公司）
    rawsina = _sina_live()
    rawmacro = _east_macro_reports()
    g100 = _titles(raw100)      # 归类只吃标题（输出格式对旧消费方向后兼容）
    g103 = _titles(raw103)
    sina = _titles(rawsina)
    macro = _titles(rawmacro)

    seen = set()
    # ① 气候/极端天气（大事件优先，最先行推）
    clim = []
    for t in g100 + g103 + sina:
        if any(k in t for k in CLIMATE_KW) and not any(k in t for k in SKIP_KW) and t not in seen:
            seen.add(t)
            tg = _clim_tag(t)
            clim.append(t if not tg else t[:70] + " " + tg)
    oni = fetch_oni_state()
    if oni:
        clim.insert(0, oni)
    clim = clim[:5]
    # ② 名人·大行（驱动情绪/题材）
    names = []
    for t in g103 + sina:
        if any(k in t for k in NAME_KW) and not any(k in t for k in SKIP_KW) and t not in seen:
            seen.add(t)
            names.append(t)
    # ③ 外媒·宏观
    world = []
    for t in g100 + g103:
        if any(k in t for k in WORLD_KW) and not any(k in t for k in SKIP_KW) and t not in seen:
            seen.add(t)
            world.append(t)
    world = world[:8]
    names = names[:8]

    out = {
        "ts": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "climate": clim,
        "world": world,
        "names": names,
        "macro_reports": macro[:3],
    }
    try:
        with open(SNAP, "w", encoding="utf-8") as f:
            json.dump(out, f, ensure_ascii=False, indent=1)
    except OSError:
        pass
    # 2026-09-17：命中条目（带网页 URL）归档，方便整理；全源底档由
    # collect_authoritative 负责（同一 URL 去重），此处不重复全量写。
    try:
        hit_world, hit_names = set(world), set(names)
        archive_links({
            "climate": [x for x in (raw100 + raw103 + rawsina)
                        if any(k in (x.get("title") or "") for k in CLIMATE_KW)],
            "world": [x for x in (raw100 + raw103) if (x.get("title") or "") in hit_world],
            "names": [x for x in (raw103 + rawsina) if (x.get("title") or "") in hit_names],
            "macro_reports": rawmacro,
        })
    except Exception:  # noqa: BLE001
        pass
    print("[news_watch] 采集 %.1fs climate=%d world=%d names=%d macro=%d"
          % (time.time() - t0, len(clim), len(world), len(names), len(macro)))
    return out


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--links":
        n = int(sys.argv[2]) if len(sys.argv) > 2 else 20
        rows = []
        if os.path.exists(LINKS_FILE):
            with open(LINKS_FILE, encoding="utf-8") as f:
                for ln in f:
                    if ln.strip():
                        try:
                            rows.append(json.loads(ln))
                        except ValueError:
                            pass
        print("📚 归档新闻 %d 条 → %s" % (len(rows), LINKS_FILE))
        for r in rows[-n:]:
            print("%s [%s/%s] %s\n    %s" % ((r.get("at") or "")[5:16], r.get("group", ""),
                                             r.get("src", ""),
                                             (r.get("title") or "")[:60],
                                             r.get("url") or "-"))
        sys.exit(0)
    out = collect_ext()
    print("☀️ 气候·极端天气:")
    for t in out["climate"]:
        print("  -", t[:70])
    print("🌐 外媒·宏观:")
    for t in out["world"]:
        print("  -", t[:60])
    print("🗣️ 名人·大行动态:")
    for t in out["names"]:
        print("  -", t[:60])
    print("📑 券商宏观·策略:")
    for t in out["macro_reports"]:
        print("  -", t[:60])
