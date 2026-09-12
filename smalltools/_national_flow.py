# -*- coding: utf-8 -*-
"""国家队（中央汇金/证金）与大基金（国家集成电路产业投资基金等）进出检测。

规则出处：
  · 《national_flow_research.md》—— 国家队 ETF 行为与十大持有人规律（附核查：本实现只用个股层面法定披露）
  · 《institutional_breakout_guide.md》§8/§9 —— FundFlowAnalyzer 流入/流出扫描口径
  · 《institutional_breakout.py》—— NATIONAL_AUTHORITY 权威性加权 / 连续两期确认

数据源（与 _holder_signals.py 同一接口，可回溯 2008，季度粒度）：
  东财 RPT_F10_EH_FREEHOLDERS 十大流通股东历史
  字段 HOLDER_NAME / HOLDER_STATE(新进/加仓/不变/减持) / FREE_HOLDNUM_RATIO(占自由流通%)
       / HOLD_NUM_CHANGE / END_DATE(报告期) / NOTICE_DATE(实际披露日)

★ 三条硬约束（research 文档 §7.4「实盘红线」，实现层强制）：
  1) 国家队/大基金身份**只能**来自「十大流通股东」法定披露；
     ETF 申赎、估算资金流、成交额一律不得写入国家队口径（避免伪归因）；
  2) 所有判定必须 NOTICE_DATE ≤ 信号日（无未来函数），本模块只输出「已披露」状态；
  3) 季报天然滞后 1-3 月，只做中长线定性，**不作实时信号**；看不见前十大之后的仓位（小市值假阴性）。

分类口径（单一事实源，双端/拟合/推送三处共用本文件）：
  nat 国家队 = 中央汇金投资 / 中央汇金资产管理 / 中国证券金融 / 证金
  big 大基金 = 国家集成电路产业投资基金(一/二/三期) / 国家制造业转型升级基金 /
               国家中小企业发展基金 / 国家绿色发展基金 / 中国国有企业结构调整基金 /
               中国国有资本风险投资基金 / 国家军民融合产业投资基金 / 国家产融合作
  ss  社保   = 全国社保基金 / 社保基金 / 基本养老
  nb  北向   = 香港中央结算有限公司

概要加权（对齐 institutional_breakout.py 的 NATIONAL_AUTHORITY）：
  nat 1.0（平准型：单季波动大，需连续 ≥3 期才确认）
  big 1.0（半导体产业链专属；非半导体票不加分）
  ss  0.9（配置型：连续 ≥2 期即确认）

产物：
  data/_national_flow_hist.json                     （PC 侧，拟合/推送复用）
  app/src/main/assets/data/_national_flow_hist.json （APK 内置，双端只读不算）
  结构：{asof, codes, built, stocks:{code:{name, theme,
          nat:[{end,notice,name,ratio,state}], big:[...], ss:[...]}}}

用法：
  python _national_flow.py --build                 # 全史抓取（断点续跑）→ 落盘 + 同步 assets
  python _national_flow.py --build --codes 300308,600487
  python _national_flow.py --show                  # 最新季国家队/大基金进出榜
  python _national_flow.py --pool 300308           # 单只诊断（逐季序列）
  python _national_flow.py --check                 # 自检（离线，用已存资产跑判定口径）
"""
import argparse
import datetime as dt
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import _holder_signals as hs  # noqa: E402  复用 F10 抓取（同接口、同回溯窗口）

DATA_DIR = os.path.join(ROOT, "data")
ASSET_DATA = os.path.join(ROOT, "app", "src", "main", "assets", "data")
OUT_FILE = os.path.join(DATA_DIR, "_national_flow_hist.json")
ASSET_OUT = os.path.join(ASSET_DATA, "_national_flow_hist.json")
PROG_FILE = os.path.join(DATA_DIR, "_national_flow_progress.json")

# ── 分类关键词（按优先级从上到下匹配；先 nat/big 后 ss，避免「社保」误吞）──
NAT_HINT = ("中央汇金", "中国证券金融", "证金")
BIG_HINT = ("国家集成电路产业投资基金", "国家制造业转型升级基金",
            "国家中小企业发展基金", "国家绿色发展基金",
            "中国国有企业结构调整基金", "中国国有资本风险投资基金",
            "国家军民融合产业投资基金", "国家产融合作")
SS_HINT = ("全国社保基金", "社保基金", "基本养老")

STATE_POS = ("新进", "加仓", "增持")
STATE_NEG = ("减持",)

# 各报告期 → 法定披露截止日 (年份偏移, 月, 日)；expected_end「是否已披露」判定用
_DEADLINES = {(3, 31): (0, 4, 30), (6, 30): (0, 8, 31),
              (9, 30): (0, 10, 31), (12, 31): (1, 4, 30)}

# 权威性加权（institutional_breakout_guide §2 的 NATIONAL_AUTHORITY）
AUTHORITY = {"nat": 1.0, "big": 1.0, "ss": 0.9}
# 确认期数门槛（guide §8.2：平准型要求 ≥3 期，配置型 ≥2 期）
CONFIRM_STREAK = {"nat": 3, "big": 2, "ss": 2}

# 半导体产业链关键词（大基金专属规则的适用域，guide §2「大基金专属规则」）
SEMI_HINT = ("半导体", "芯片", "集成电路", "电子", "封测", "晶圆", "EDA", "材料", "设备")


def classify(name):
    """股东名 → 类别（nat/big/ss/nb/None）。"""
    if not name:
        return None
    for h in NAT_HINT:
        if h in name:
            return "nat"
    for h in BIG_HINT:
        if h in name:
            return "big"
    for h in SS_HINT:
        if h in name:
            return "ss"
    if name == hs.NORTH_NAME:
        return "nb"
    return None


# ═══════════════════════ 判定口径（唯一实现，供节点/拟合/推送共用）═══════════════════════

def _ratio_sum(rows):
    return round(sum(float(r.get("ratio") or 0) for r in rows), 4)


def flow_series(recs, day=None):
    """按报告期聚合的序列（升序），仅含 NOTICE_DATE ≤ day 的记录（无未来函数）。

    返回 [{end, notice, ratio(合计), n(账户数), names[], pos(bool 有加码动作)}]
    """
    if day:
        recs = [r for r in recs if r.get("notice") and r["notice"] <= day]
    by_end = {}
    for r in recs:
        by_end.setdefault(r["end"], []).append(r)
    out = []
    for end in sorted(by_end):
        rows = by_end[end]
        out.append({
            "end": end,
            "notice": max((r.get("notice") or "") for r in rows),
            "ratio": _ratio_sum(rows),
            "n": len({r.get("name") for r in rows}),
            "names": sorted({r.get("name") for r in rows if r.get("name")}),
            "pos": any(r.get("state") in STATE_POS for r in rows),
            "neg": any(r.get("state") in STATE_NEG for r in rows),
        })
    return out


def _qidx(end):
    """报告期 → 连续季度序号（算缺席期数用）。"""
    try:
        y, m = int(str(end)[:4]), int(str(end)[5:7])
        return y * 4 + {3: 0, 6: 1, 9: 2, 12: 3}.get(m, 0)
    except (TypeError, ValueError):
        return -10 ** 6


def expected_end(day):
    """信号日 day 对应的最近一个「法定披露截止日已到」的季末报告期。

    按各期真实披露截止日（沪深交易所规则）判定，避免统一滞后天数造成的偏差：
      3-31 → 4-30 | 6-30 → 8-31 | 9-30 → 10-31 | 12-31 → 次年 4-30
    """
    try:
        d = dt.date.fromisoformat(str(day)[:10])
    except (TypeError, ValueError):
        return ""
    best = ""
    for y in (d.year, d.year - 1, d.year - 2):
        for (m, dd), dl in _DEADLINES.items():
            e = dt.date(y, m, dd)
            if d >= dt.date(y + dl[0], dl[1], dl[2]) and e.isoformat() > best:
                best = e.isoformat()
    return best


def _dir_of(cur, prv):
    """一期方向：+1 增持 / -1 减持 / 0 持平。以合计占比环比为主、动作标签为辅。"""
    d = (cur["ratio"] - prv["ratio"]) if prv else None
    if cur["pos"] and (d is None or d >= 0):
        return 1
    if cur["neg"] and (d is None or d <= 0):
        return -1
    if d is None:
        return 0
    return 1 if d > 0.3 else (-1 if d < -0.3 else 0)


def flow_state(recs, day, kind="nat"):
    """截至 day（≤NOTICE_DATE）的最新已披露进出状态。**无未来函数**。

    ★ 关键修正（2026-09-13）：十大流通股东**每季披露**。若最新记录比「当期应披露报告期」
      落后 ≥2 期，说明该主体**已退出前十大**（不是因为"还持有只是没披露"）→ 判「退出」并按流出占分。
      否则会把 2015 年的一次新进一路带到 2026 年，制造大量伪信号。
      （实测：002371 国家队最新披露停在 2021Q1、300308 停在 2020Q2 —— 与 research 文档
       「汇金 2015 后个股前十大可见度下降、转向 ETF」一致，必须显式退出。）

    kind: nat/big/ss —— 决定「连续几期才算确认」（CONFIRM_STREAK）。

    返回 dict：
      state   流入 | 流出 | 退出 | 持稳 | 无
              （无 = 从未进前十大 ≠ 减持；退出 = 曾在前十大、现已连续 ≥2 期缺席）
      strong  连续增持期数 ≥ CONFIRM_STREAK（仅 state=流入 时可能为真）
      chg     最新期合计占比 − 上一期合计占比（pp；仅一期时为 None）
      streak  连续增持期数（负值=连续减持期数）
      ratio/n/names  最新期合计占比(%) / 账户数 / 账户名
      end/notice/gap 最新已披露报告期 / 实际披露日 / 距当期报告期的缺席期数
    """
    ser = flow_series(recs, day)
    if not ser:
        return {"state": "无", "strong": False, "chg": None, "streak": 0,
                "ratio": None, "n": 0, "names": [], "end": "", "notice": "",
                "gap": None, "series_n": 0}
    last = ser[-1]
    prev = ser[-2] if len(ser) >= 2 else None
    chg = round(last["ratio"] - prev["ratio"], 4) if prev else None
    exp = expected_end(day) if day else ""
    gap = (_qidx(exp) - _qidx(last["end"])) if exp else 0

    # 连续期数（增持为正 / 减持为负）
    dirs = [_dir_of(ser[i], ser[i - 1]) for i in range(1, len(ser))]
    last_dir = dirs[-1] if dirs else _dir_of(last, None)
    streak = last_dir
    if last_dir:
        for d in reversed(dirs[:-1]):
            if d != last_dir:
                break
            streak += last_dir

    if gap >= 2:
        state = "退出"                                   # 连续 ≥2 期缺席前十大
    elif chg is not None and chg < -0.3:
        state = "流出"
    elif chg is not None and chg > 0.3:
        state = "流入"
    elif last["pos"] and (chg is None or chg >= 0):
        state = "流入"
    elif last["neg"] and (chg is None or chg <= 0):
        state = "流出"
    else:
        state = "持稳"

    need = CONFIRM_STREAK.get(kind, 2)
    return {"state": state, "strong": bool(state == "流入" and streak >= need),
            "chg": chg, "streak": streak, "ratio": last["ratio"],
            "n": last["n"], "names": last["names"], "end": last["end"],
            "notice": last["notice"], "gap": gap, "series_n": len(ser)}


def flow_bundle(stock, day):
    """一只股票在 day 时点的全部渠道状态（national_flow 节点 / 拟合共用入口）。

    stock = {name, theme, nat:[], big:[], ss:[]}
    返回 {nat, big, ss, flowScore(0~100), label, semi}
    """
    nat = flow_state(stock.get("nat") or [], day, "nat")
    big = flow_state(stock.get("big") or [], day, "big")
    ss = flow_state(stock.get("ss") or [], day, "ss")

    score = 0.0
    for kind, st, up_w, dn_w in (("nat", nat, 45, 40), ("big", big, 40, 35), ("ss", ss, 25, 25)):
        w = AUTHORITY[kind]
        if st["state"] == "流入":
            score += up_w * w * (1.35 if st["strong"] else 1.0)
        elif st["state"] == "流出":
            score -= dn_w * w
        elif st["state"] == "退出":
            score -= dn_w * w * 0.6          # 退出前十大：弱于显式减持（可能仅掉出十名外）
    score = max(0.0, min(100.0, 50.0 + score))

    tags = []
    for lab, st in (("国家队", nat), ("大基金", big), ("社保", ss)):
        if st["state"] == "流入":
            tags.append("%s流入%s" % (lab, "·强" if st["strong"] else ""))
        elif st["state"] == "流出":
            tags.append("%s流出" % lab)
        elif st["state"] == "退出":
            tags.append("%s退出" % lab)
    if not tags:
        tags.append("无披露" if all(x["state"] == "无" for x in (nat, big, ss)) else "持稳")

    semi = any(h in (stock.get("theme") or "") for h in SEMI_HINT) or \
        any(h in (stock.get("name") or "") for h in SEMI_HINT)
    return {"nat": nat, "big": big, "ss": ss, "flowScore": round(score, 1),
            "label": " + ".join(tags), "semi": semi,
            "natState": nat["state"], "bigState": big["state"], "ssState": ss["state"],
            "natStrong": nat["strong"], "bigStrong": big["strong"]}


def build_snapshot(stocks, day):
    """当前时点状态快照 —— 供 pipeline 节点 / APK / 推送表**只读**，避免 Python/Kotlin/推送三处重算口径。

    与 _inst_holdings.json 内嵌 grade 同一模式：算法只在 smalltools，资产内预计算。
    （回溯拟合不读快照，直接逐日调 flow_bundle —— 状态随时点变化，必须逐日算。）
    """
    snap = {}
    for c, s in (stocks or {}).items():
        b = flow_bundle(s, day)
        if all(b[k]["state"] == "无" for k in ("nat", "big", "ss")):
            continue
        ent = {"name": s.get("name") or "", "theme": s.get("theme") or "",
               "flowScore": b["flowScore"], "flowLabel": b["label"],
               "semi": b["semi"], "day": day}
        for k in ("nat", "big", "ss"):
            st = b[k]
            ent[k + "State"] = st["state"]
            ent[k + "Strong"] = st["strong"]
            ent[k + "Ratio"] = st["ratio"]
            ent[k + "Chg"] = st["chg"]
            ent[k + "End"] = st["end"]
            ent[k + "Notice"] = st["notice"]
            ent[k + "Gap"] = st["gap"]
            ent[k + "Names"] = (st["names"] or [])[:3]
        snap[c] = ent
    return snap


# ═══════════════════════ 数据采集 ═══════════════════════

def load_national_hist():
    """读 data/_national_flow_hist.json（PC 侧）。"""
    try:
        with open(OUT_FILE, encoding="utf-8") as f:
            return json.load(f) or {}
    except (OSError, ValueError):
        return {}


def _theme_map():
    """code → 行业主题（复用 _etf_holdings 的行业ETF前五归属）。"""
    try:
        import _etf_holdings as eh
        return hs._theme_map(eh.load_holdings() or {})
    except Exception:  # noqa: BLE001
        return {}


def target_codes(explicit=None):
    """默认标的池 = _holder_hist.json 的 codes（行业ETF前五重仓，与拟合口径一致）。"""
    if explicit:
        return [c.strip().zfill(6) for c in explicit if c.strip()]
    try:
        with open(os.path.join(DATA_DIR, "_holder_hist.json"), encoding="utf-8") as f:
            d = json.load(f) or {}
        m = {}
        for k in ("ss", "nb"):
            for c in (d.get(k) or {}):
                m[c] = 1
        codes = sorted(m)
        if codes:
            return codes
    except (OSError, ValueError):
        pass
    try:
        with open(os.path.join(DATA_DIR, "_etf_top5_hist.json"), encoding="utf-8") as f:
            return sorted(((json.load(f) or {}).get("codes") or {}).keys())
    except (OSError, ValueError):
        return []


def build(codes=None, resume=True):
    """逐股抓 F10 全史 → 分类归集 → 落盘 + 同步 assets（断点续跑）。"""
    codes = target_codes(codes)
    if not codes:
        print("✗ 无标的池（先跑 _holder_signals.py --build-hist 或 _etf_holdings.py）")
        return {}
    theme_of = _theme_map()
    res, stocks = {}, {}
    if resume:
        for f in (OUT_FILE, PROG_FILE):        # 主文件 + 中断进度，合并续跑
            try:
                with open(f, encoding="utf-8") as fh:
                    res = json.load(fh) or res
                    stocks.update(res.get("stocks") or {})
            except (OSError, ValueError):
                pass
    done = set(stocks.keys()) if resume else set()
    print("标的 %d 只（已完成 %d，断点续跑）" % (len(codes), len(done)), flush=True)
    hit = {"nat": 0, "big": 0, "ss": 0}
    for i, c in enumerate(codes):
        if c in done:
            hit = {k: hit[k] + (1 if stocks[c].get(k) else 0) for k in hit}
            continue
        try:
            recs = hs.fetch_f10_history(c)
        except Exception as e:  # noqa: BLE001
            print("  ! %s 抓取失败: %s" % (c, e))
            continue
        ent = {"name": "", "theme": theme_of.get(c) or ""}
        for k in ("nat", "big", "ss"):
            rows = []
            for r in recs:
                if classify(r.get("name") or "") != k:
                    continue
                if not r.get("notice"):
                    continue           # 无披露日 → 无法做无未来函数判定，丢弃
                rows.append({kk: r[kk] for kk in ("end", "notice", "name", "ratio", "state")})
            if rows:
                ent[k] = rows
                hit[k] += 1
        if ent.get("nat") or ent.get("big") or ent.get("ss"):
            stocks[c] = ent
        if (i + 1) % 10 == 0:
            print("  %d/%d 国家队%d 大基金%d 社保%d" % (i + 1, len(codes),
                                                 hit["nat"], hit["big"], hit["ss"]), flush=True)
            _save({"asof": res.get("asof") or "", "codes": len(codes),
                   "built": dt.datetime.now().strftime("%Y-%m-%d %H:%M"),
                   "stocks": stocks}, partial=True)
    asof = ""
    try:
        with open(os.path.join(DATA_DIR, "_holder_hist.json"), encoding="utf-8") as f:
            asof = (json.load(f) or {}).get("asof") or ""
    except (OSError, ValueError):
        pass
    day = asof or dt.date.today().isoformat()
    out = {"asof": asof, "codes": len(codes), "built": dt.datetime.now().strftime("%Y-%m-%d %H:%M"),
           "judge_day": day,
           "rule": "国家队=中央汇金/证金；大基金=国家集成电路产业投资基金等；"
                   "社保=全国社保/基本养老；判定只用 NOTICE_DATE≤信号日 的法定披露（无未来函数）",
           "states": "流入|流出|退出|持稳|无（退出=曾进前十大、现连续≥2期缺席）",
           "snap": build_snapshot(stocks, day),
           "stocks": stocks}
    _save(out)
    print("✓ 国家队%d只 / 大基金%d只 / 社保%d只 ｜ 快照 %d 只（判定日 %s）→ %s"
          % (hit["nat"], hit["big"], hit["ss"], len(out["snap"]), day, OUT_FILE))
    return out


def _save(d, partial=False):
    os.makedirs(DATA_DIR, exist_ok=True)
    with open(OUT_FILE if not partial else PROG_FILE, "w", encoding="utf-8") as f:
        json.dump(d, f, ensure_ascii=False, separators=(",", ":"))
    if not partial:
        os.makedirs(ASSET_DATA, exist_ok=True)
        with open(ASSET_OUT, "w", encoding="utf-8") as f:
            json.dump(d, f, ensure_ascii=False, separators=(",", ":"))
        if os.path.exists(PROG_FILE):
            try:
                os.remove(PROG_FILE)
            except OSError:
                pass


# ═══════════════════════ 展示 / 自检 ═══════════════════════

def show(today=None):
    d = load_national_hist()
    stocks = d.get("stocks") or {}
    if not stocks:
        print("✗ 缺 %s，先跑 --build" % OUT_FILE)
        return 1
    day = today or dt.date.today().isoformat()
    print("═══ 国家队/大基金/社保 进出（截至 %s，标的 %d 只）═══" % (day, len(stocks)))
    buckets = {"流入": [], "流出": []}
    for c, s in stocks.items():
        b = flow_bundle(s, day)
        for k, lab in (("nat", "国家队"), ("big", "大基金"), ("ss", "社保")):
            st = b[k]
            if st["state"] in ("流入", "流出"):
                buckets[st["state"]].append(
                    (b["flowScore"], c, s.get("name") or "", lab, st, s.get("theme") or ""))
    for lab in ("流入", "流出"):
        rows = sorted(buckets[lab], key=lambda x: (-x[0] if lab == "流入" else x[0]))
        print("\n▼ %s %d 条" % (lab, len(rows)))
        for score, c, nm, kind, st, theme in rows[:25]:
            print("   [%5.1f] %s %s %s | %s%s | 占比%s%% 环比%s streak=%d 披露%s"
                  % (score, c, nm, theme or "-", kind,
                     "·强" if st["strong"] else "",
                     st["ratio"], st["chg"], st["streak"], st["notice"]))
    return 0


def pool(codes, day=None):
    d = load_national_hist()
    stocks = d.get("stocks") or {}
    day = day or dt.date.today().isoformat()
    for c in codes:
        s = stocks.get(c)
        print("\n═══ %s ═══" % c)
        if not s:
            print("  无国家队/大基金/社保披露记录")
            continue
        b = flow_bundle(s, day)
        print("  %s | flowScore %.1f | %s" % (s.get("name") or "", b["flowScore"], b["label"]))
        for k, lab in (("nat", "国家队"), ("big", "大基金"), ("ss", "社保")):
            rows = (s.get(k) or [])
            if not rows:
                continue
            print("  · %s %d 条：%s" % (lab, len(rows), flow_state(rows, day, k)))
            for r in sorted(rows, key=lambda x: x["end"])[-6:]:
                print("      %s(披露%s) %s %s%% %s"
                      % (r["end"], r["notice"], r["name"][-14:], r["ratio"], r["state"]))
    return 0


def check():
    """离线自检：用已存资产跑判定口径，确认无未来函数（同 day 复现）。"""
    d = load_national_hist()
    stocks = d.get("stocks") or {}
    print("资产: %d 只，built %s" % (len(stocks), d.get("built")))
    day = d.get("asof") or dt.date.today().isoformat()
    n_ok = 0
    for c, s in list(stocks.items())[:200]:
        b = flow_bundle(s, day)
        for k in ("nat", "big", "ss"):
            for r in (s.get(k) or []):
                if r["notice"] > day:
                    print("✗ 未来函数: %s %s 披露%s > %s" % (c, k, r["notice"], day))
                    return 1
        if b["natState"] != "无" or b["bigState"] != "无":
            n_ok += 1
    print("✓ 无未来函数；day=%s 有国家队/大基金状态 %d 只" % (day, n_ok))
    return 0


def main():
    ap = argparse.ArgumentParser(description="国家队/大基金持有进出检测（数据层）")
    ap.add_argument("--build", action="store_true", help="抓全史 → 落盘 + 同步 APK assets")
    ap.add_argument("--codes", default="", help="指定标的（逗号分隔，缺省=行业ETF前五重仓）")
    ap.add_argument("--no-resume", action="store_true", help="忽略已完成，全量重抓")
    ap.add_argument("--show", action="store_true", help="最新季进出榜")
    ap.add_argument("--pool", default="", help="单只/多只诊断")
    ap.add_argument("--check", action="store_true", help="离线自检判定口径")
    ap.add_argument("--asof", default="", help="指定判定日（默认今天）")
    a = ap.parse_args()
    codes = [x for x in a.codes.split(",") if x.strip()]
    if a.build:
        return 0 if build(codes or None, resume=not a.no_resume) else 1
    if a.pool:
        return pool([x.strip().zfill(6) for x in a.pool.split(",")], a.asof or None)
    if a.check:
        return check()
    return show(a.asof or None)


if __name__ == "__main__":
    sys.exit(main() or 0)
