# -*- coding: utf-8 -*-
"""非交易时间宏观哨兵（2026-09-17 用户需求）。

daemon 在非交易时段（交易日收盘后→次日08:00、早间 00:00→08:00、周末/节假日全天）
每小时调用一次 run_hourly()：

  1) 拉信息：东财 7x24 快讯（collect_news）+ 美股盘中/收盘（us_close）
     + 原油/黄金/人民币（commodity_fx）+ 宏观事件库活跃事件（_macro_events_lines）
  2) 分级（用户口径：100% 会影响股市的才触发选股并推送）：
     · MAJOR 确定级事件 → 务必先宏观分析（事件卡+美股/商品+利好利空板块），
       再选股 → 推送（复用 _daily_intel.run_slot("offhour")，与 08:00 盘前情报
       同一链路并参考当日盘前情报存档 data/_daily_intel.json）；
       事件存续期间每小时推一次跟踪更新（非交易时间也推送，间隔 1h）
     · CHANGED 宏观有变动但非确定级（美股 1%~2% / 原油 2%~4% / 人民币 0.5%~1% /
       事件库新增活跃事件 / 新增快讯但未命中确定级关键词）→ 轻量快照推送（1h 一推），
       不触发选股
     · NONE 无动静 → 静默（只更新状态文件，不打扰）
  3) 去重：快讯按标题指纹与上一轮比对，只有「新增」标题参与关键词判定；
     确定级触发条件（含美股暴跌等量化阈值）持续期间，每小时更新一次。

状态：smalltools/_records/_offhour_state.json（节流 ≥55 分钟、快讯标题指纹 ≤240 条）。
手动：python _offhour_watch.py [--dry] [--force]。
"""
import datetime
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

_STATE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                           "_records", "_offhour_state.json")
# 2026-09-17 用户口径：非交易时间只「采集 + 去重 + 存池」，推送交给 08:00 pre8
# 与盘中 1h 节奏。采集到的新快讯落此池，次日 8:00 盘前/盘中当新闻因子消费。
_POOL_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                          "_records", "_offhour_news_pool.jsonl")
_THROTTLE_SEC = 55 * 60          # 每小时一轮（55 分钟节流防重复）
_SEEN_MAX = 240                  # 快讯标题指纹上限
_POOL_MAX = 400                  # 新闻池保底条数（超出按时间截断）

# ── 确定级关键词（标题包含任一 → MAJOR；口径：100% 会影响股市）──
_MAJOR_KW = (
    "降息", "加息", "存款准备金", "准备金率", "LPR", "MLF利率", "逆回购利率",
    "宣战", "开战", "导弹袭击", "空袭", "军事冲突", "停火协议", "进入紧急状态",
    "制裁", "出口管制", "实体清单", "加征关税", "关税反制",
    "政治局会议", "中央经济工作会议", "国常会部署",
    "QE", "量化宽松", "缩表",
    "熔断", "交易所暂停",
)
# 复合规则：宏观数据 + 超预期修饰（两个都命中才升级 MAJOR）
_DATA_KW = ("CPI", "PPI", "PMI", "GDP", "非农", "社融")
_DATA_HIT = ("超预期", "大超", "不及预期", "爆表", "爆冷", "远低")
# 「暴跌/大涨」类词太常见，必须与指数词同现才算确定级
_CRASH_KW = ("暴跌", "闪崩", "崩盘", "大涨", "飙涨")
_CRASH_WITH = ("美股", "道琼斯", "纳斯达克", "标普", "A股", "上证", "深证", "创业板", "全球股市")

# ── 区域分类标签（2026-09-17 用户口径：MAJOR 触发必须 ≥3 命中 + 至少 1 个属于
#     fed/cn/eu，避免单一中文 A 股快讯或单只个股利好「噪音级」误触发全量选股推送） ──
_FED_TAGS = ("美联储", "FOMC", "鲍威尔", "沃什", "点阵图", "PCE", "非农",
             "美元", "美债", "美股", "纳斯达克", "标普", "道琼斯", "美元指数",
             "美国通胀", "美国CPI", "沃勒", "鲍曼")
_CN_TAGS = ("央行", "国常会", "政治局", "国务院", "证监会", "中概",
            "人民币", "A股", "上证", "深证", "创业板", "北向", "社融",
            "PMI", "CPI", "GDP", "中国", "人民银行", "PBOC", "外汇局")
_EU_TAGS = ("欧央行", "ECB", "欧洲央行", "欧元区", "英镑", "德国", "法国",
            "意大利", "瑞士", "英国央行", "BOE", "欧债", "欧股")


def _category(title):
    t = title or ""
    if any(k in t for k in _FED_TAGS):
        return "fed"
    if any(k in t for k in _CN_TAGS):
        return "cn"
    if any(k in t for k in _EU_TAGS):
        return "eu"
    return "other"


def _log_default(msg):
    print("[%s] %s" % (datetime.datetime.now().strftime("%H:%M:%S"), msg))


def _load_state():
    try:
        with open(_STATE_FILE, encoding="utf-8") as f:
            return json.load(f)
    except Exception:  # noqa: BLE001
        return {"ts": "", "seen": []}


def _save_state(st):
    try:
        os.makedirs(os.path.dirname(_STATE_FILE), exist_ok=True)
        with open(_STATE_FILE, "w", encoding="utf-8") as f:
            json.dump(st, f, ensure_ascii=False)
    except Exception:  # noqa: BLE001
        pass


def _hit_major(title):
    """返回 (kw, category) 或 None。category ∈ {fed, cn, eu, other}（2026-09-17）。"""
    t = (title or "")
    for kw in _MAJOR_KW:
        if kw in t:
            return (kw, _category(t))
    if any(k in t for k in _DATA_KW) and any(k in t for k in _DATA_HIT):
        return ("宏观数据超预期", _category(t))
    if any(k in t for k in _CRASH_KW) and any(k in t for k in _CRASH_WITH):
        return ("市场剧烈波动", _category(t))
    return None


def _fmt_pct(v):
    if v is None or v != v:
        return "—"
    return "%+.2f%%" % v


# ── 新闻池（非交易时间采集 → 8:00/盘中当新闻因子消费）────────────────────
def append_news_pool(items):
    """把非交易时段采集到的新快讯追加进池（jsonl，一行一条）。"""
    if not items:
        return 0
    try:
        os.makedirs(os.path.dirname(_POOL_FILE), exist_ok=True)
        with open(_POOL_FILE, "a", encoding="utf-8") as f:
            for it in items:
                title = (it.get("title") or "").strip()
                if not title:
                    continue
                f.write(json.dumps({"title": title, "time": it.get("time") or "",
                                    "url": it.get("url") or "",   # 原文网页（2026-09-17）
                                    "at": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")},
                                   ensure_ascii=False) + "\n")
        _trim_pool()
        return len(items)
    except Exception as e:  # noqa: BLE001
        _log_default("新闻池写入失败: %s %s" % (type(e).__name__, e))
        return 0


def _trim_pool():
    """池子超 _POOL_MAX 时保留最新一批（防无限增长）。"""
    try:
        with open(_POOL_FILE, encoding="utf-8") as f:
            rows = [ln for ln in f if ln.strip()]
        if len(rows) > _POOL_MAX:
            with open(_POOL_FILE, "w", encoding="utf-8") as f:
                f.writelines(rows[-_POOL_MAX:])
    except OSError:
        pass


def drain_news_pool(max_items=60):
    """读走新闻池（返回列表并清空文件）。8:00 pre8 / 盘中 1h 消费点调用。"""
    if not os.path.exists(_POOL_FILE):
        return []
    out = []
    try:
        with open(_POOL_FILE, encoding="utf-8") as f:
            for ln in f:
                ln = ln.strip()
                if not ln:
                    continue
                try:
                    out.append(json.loads(ln))
                except ValueError:
                    continue
        with open(_POOL_FILE, "w", encoding="utf-8") as f:
            f.write("")
    except OSError:
        return out
    return out[-max_items:]


def _light_push(lines, dry=False):
    """CHANGED 级轻量推送（企微，非交易时间也推）。"""
    try:
        import push_channel
        cfg = push_channel.load_notify_cfg()
        title = "🌙 非交易时间宏观快照 %s" % datetime.datetime.now().strftime("%m-%d %H:%M")
        content = "\n".join(lines)
        if dry:
            print("[dry] 轻量快照推送：\n" + title + "\n" + content)
        else:
            push_channel.push(title, content, cfg, kind="notice")
    except Exception as e:  # noqa: BLE001
        _log_default("轻量推送失败: %s %s" % (type(e).__name__, e))


def run_hourly(dry=False, log=_log_default, force=False, push=True):
    """非交易时间哨兵。返回 POOL / MAJOR / CHANGED / NONE / SKIP。

    2026-09-17 用户口径修正：
      · push=False（daemon 默认）→ 只采集 + 去重 + 写入新闻池，**不推送不选股**；
        推送交给 08:00 pre8 与盘中 1h 节奏（昨晚凌晨每小时推送太频繁，已删除）。
      · push=True（手动 --force 排查用）→ 保留原 MAJOR/CHANGED 即时推送行为。
    """
    now = datetime.datetime.now()
    st = _load_state()
    if not force and st.get("ts"):
        try:
            last = datetime.datetime.strptime(st["ts"], "%Y-%m-%d %H:%M:%S")
            if (now - last).total_seconds() < _THROTTLE_SEC:
                return "SKIP"
        except Exception:  # noqa: BLE001
            pass

    news = us = cmdt = []
    try:
        import _daily_intel as di
        # top 与 snapshot() 默认一致（12），便于 prefetched.news 直接复用；
        # drain_pool=False：哨兵自查时不消费新闻池（留给 8:00/盘中消费）
        news = di.collect_news(top=12, drain_pool=False) or []
        us = di.us_close() or []
        cmdt = di.commodity_fx() or {}
    except Exception as e:  # noqa: BLE001
        log("非交易时间哨兵数据源异常: %s %s（本轮跳过）" % (type(e).__name__, e))
        return "NONE"

    seen = set(st.get("seen") or [])
    new_items = [n for n in news if n.get("title") and n["title"] not in seen]
    first_run = not seen            # 首轮全部标题都算"新增"，不参与条数判定
    major_hits = []
    for n in new_items:
        hit = _hit_major(n.get("title"))
        if hit:
            kw, cat = hit
            major_hits.append("[%s·%s] %s" % (cat, kw, n["title"]))

    # 量化阈值（美股/原油/人民币）
    us_pcts = [abs(x.get("pct") or 0) for x in us]
    us_max = max(us_pcts) if us_pcts else 0.0
    oil = cmdt.get("WTI原油") or {}
    fx = cmdt.get("在岸人民币") or {}
    oil_abs = abs(oil.get("pct") or 0)
    fx_abs = abs(fx.get("pct") or 0)

    # 2026-09-17 收紧口径：单点关键词不足触发 MAJOR
    #  ① 关键词型：命中数 ≥ 3 且至少 1 条 cat ∈ {fed, cn, eu}
    #  ② 量化型：us_max≥2% / oil≥4% / fx≥1% 中 ≥2 项同时命中（避免单一市场异常噪音）
    keyword_major = len(major_hits) >= 3 and any(
        "[fed·" in h or "[cn·" in h or "[eu·" in h for h in major_hits
    )
    quant_hits = sum([us_max >= 2.0, oil_abs >= 4.0, fx_abs >= 1.0])
    quant_major = quant_hits >= 2
    major = keyword_major or quant_major
    changed = (us_max >= 1.0 or oil_abs >= 2.0 or fx_abs >= 0.5
               or (not first_run and len(new_items) >= 8))

    # 状态先更新（本轮看到的标题都记指纹，避免重复判定）
    titles = [n["title"] for n in news if n.get("title")]
    st = {"ts": now.strftime("%Y-%m-%d %H:%M:%S"),
          "seen": (list(seen | set(titles)))[-_SEEN_MAX:]}

    if major:
        st["last_major"] = now.strftime("%Y-%m-%d %H:%M:%S")
        st["major_reason"] = major_hits[:3] or [
            "美股 %s" % _fmt_pct(next((x.get("pct") for x in us
                                       if abs(x.get("pct") or 0) == us_max), None)),
            "原油 %s" % _fmt_pct(oil.get("pct")),
            "人民币 %s" % _fmt_pct(fx.get("pct")),
        ]
        _save_state(st)
        if not push:
            # 2026-09-17：非交易时间只记录 + 存池，不推送（推送交给 08:00 / 盘中 1h）
            append_news_pool(new_items)
            log("非交易时间确定级事件（仅记录+存池，不推送，留待 08:00 盘前）：%s"
                % "; ".join(st["major_reason"]))
            return "POOL"
        log("🚨 非交易时间确定级宏观事件 → 先宏观分析再选股推送：%s"
            % "; ".join(st["major_reason"]))
        try:
            import _daily_intel
            # 2026-09-17 用户口径·节流：把顶部已抓的 news/us_close/commodity_fx 透传进
            # run_slot 的 prefetched，避免 snapshot() 内部重复抓取（节省 ~60% 网络与 90s+ 延迟）。
            # collect_news 默认 top 与 snapshot 对齐（12），保证两边数据一致。
            _daily_intel.run_slot("offhour", dry=dry, log=log,
                                  prefetched={"news": news, "us_close": us, "commodity": cmdt})
            return "MAJOR"
        except Exception as e:  # noqa: BLE001
            log("MAJOR 情报流程失败: %s %s（降级为轻量快照）" % (type(e).__name__, e))

    # CHANGED：轻量快照（1h 一推，不选股）
    if changed:
        _save_state(st)
        if not push:
            append_news_pool(new_items)
            log("非交易时间采集 %d 条新快讯 + 宏观变动（仅存池，不推送）" % len(new_items))
            return "POOL"
        lines = ["【非交易时间宏观快照】%s" % now.strftime("%m-%d %H:%M"), ""]
        if us:
            lines.append("· 美股: " + "  ".join(
                "%s %s" % (x.get("name"), _fmt_pct(x.get("pct"))) for x in us))
        toks = []
        for nm in ("WTI原油", "COMEX黄金", "在岸人民币"):
            it = cmdt.get(nm)
            if it:
                toks.append("%s %s" % (nm, _fmt_pct(it.get("pct"))))
        if toks:
            lines.append("· 商品/汇率: " + "  ".join(toks))
        if new_items:
            lines.append("· 新增快讯 %d 条:" % len(new_items))
            for n in new_items[:5]:
                lines.append("  - %s" % (n.get("title") or "")[:60])
        lines.append("")
        lines.append("（非确定级宏观变动，不触发选股；08:00 盘前情报将完整分析）")
        _light_push(lines, dry=dry)
        return "CHANGED"

    _save_state(st)
    if not push:
        append_news_pool(new_items)
    return "NONE"


if __name__ == "__main__":
    _dry = "--dry" in sys.argv
    _force = "--force" in sys.argv
    _push = "--push" in sys.argv          # 手动排查时才即时推送
    _drain = "--drain" in sys.argv        # 读走新闻池并打印
    if _drain:
        for _it in drain_news_pool():
            print("%s  %s" % (_it.get("time") or _it.get("at"), _it.get("title")))
        sys.exit(0)
    lvl = run_hourly(dry=_dry, force=_force, push=_push)
    print("本轮级别:", lvl)
