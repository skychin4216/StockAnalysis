# -*- coding: utf-8 -*-
"""行业龙头图谱生成器 —— 「活跃行业赛道 → 核心龙头股(代码+名称)」结构化图谱。

定位：参考人工整理的 A 股行业赛道龙头图谱(产业链视角)，做一份可每日动态更新的
结构化版本。骨架为【精选赛道目录】(下方 TRACK，覆盖半导体产业链/PCB/MLCC/光通信/
电池/光伏/有色贵金属/创新药等，可自行增删)，每日基于东财实时行情(近20日动量 +
当日涨幅 + 主力资金流)做活跃度综合评分，动态圈定当日 TopN 活跃赛道，再对每个赛道
按成分总市值取核心龙头 Top3(代码+名称+市值+涨幅)，并附当日/近20日人气领涨股。

数据源(东方财富 push2delay, 全走 clist):
  1. 行业板块全量榜(fs=m:90+t:2, 约496个含细分, 翻页拉全) → 板块当日行情与赛道匹配
  2. 板块成分榜(fs=b:BKxxxx, 按总市值降序) → 龙头选取(自动剔除 ST/退/次新/B股/北交)

产出(默认落盘 StockAnalysis/data/)：
  _industry_leader_map.json -- 结构化图谱(程序/推送复用)
  _industry_leader_map.md   -- Markdown 表格(人工阅读/文档引用)

用法：
  python _industry_leader_map.py                # 活跃度 blend 取 Top20
  python _industry_leader_map.py --top 25 --leaders 4
  python _industry_leader_map.py --mode day|momentum|flow|blend   # 活跃口径
  python _industry_leader_map.py --all          # 全部赛道都取龙头(约44个成分请求)
  python _industry_leader_map.py --stdout       # 控制台打印表格不落盘
"""
import argparse
import bisect
import json
import os
import sys
import time

import requests

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

UA = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    "Referer": "https://quote.eastmoney.com/",
}
PX = {"http": None, "https": None}
HOST = "https://push2delay.eastmoney.com/api/qt/clist/get"
FS_IND = "m:90+t:2+f:!50"                        # 东财行业板块(全层级)
BOARD_FIELDS = "f2,f3,f12,f14,f62,f109,f128,f140"
STOCK_FIELDS = "f2,f3,f8,f12,f14,f20,f21,f62"
SLEEP = 0.15

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT_DIR = os.path.join(ROOT, "data")
JSON_OUT = os.path.join(OUT_DIR, "_industry_leader_map.json")
MD_OUT = os.path.join(OUT_DIR, "_industry_leader_map.md")

# 沪深A股代码前缀(剔除 B股 200/900、北交 4/8/92、新股 C/N、ST/退)
A_PREFIX = ("60", "68", "00", "30")


# ---------------------------------------------------------------------------
# 精选赛道目录(产业链视角; 与东财行业板块名精确对应, 板块代码实测稳定)
#   值: 东财板块名; 部分板块在东财新行业树中为 Ⅱ 级(证券/保险/白酒/中药)。
#   增删/改赛道直接改这里; 板块名若失效运行时会提示"未匹配"。
# ---------------------------------------------------------------------------
TRACK_GROUPS = {
    "半导体": [
        ("半导体", "半导体"), ("半导体设备", "半导体设备"), ("半导体材料", "半导体材料"),
        ("集成电路制造", "集成电路制造"), ("集成电路封测", "集成电路封测"),
    ],
    "电子与元件": [
        ("元件", "元件"), ("PCB", "印制电路板"), ("MLCC/被动元件", "被动元件"),
        ("消费电子", "消费电子"), ("光学光电子", "光学光电子"),
    ],
    "通信与软件": [
        ("通信设备/光模块", "通信设备"), ("通信服务", "通信服务"),
        ("软件开发", "软件开发"), ("计算机设备", "计算机设备"), ("传媒/游戏", "传媒"),
    ],
    "电力设备与新能源": [
        ("光伏设备", "光伏设备"), ("电池/锂电", "电池"), ("风电设备", "风电设备"),
        ("电网设备", "电网设备"), ("电力", "电力"),
    ],
    "医药生物": [
        ("创新药/化学制药", "化学制药"), ("生物制品", "生物制品"),
        ("医疗器械", "医疗器械"), ("医疗服务", "医疗服务"), ("中药", "中药Ⅱ"),
    ],
    "有色与资源": [
        ("黄金", "黄金"), ("贵金属/白银", "贵金属"), ("铜", "铜"), ("铝", "铝"),
        ("稀土", "稀土"), ("小金属/钨钼", "小金属"), ("能源金属/锂钴", "能源金属"),
        ("煤炭", "煤炭"), ("石油石化", "石油石化"),
    ],
    "金融": [
        ("银行", "银行"), ("券商", "证券Ⅱ"), ("保险", "保险Ⅱ"),
    ],
    "周期与消费": [
        ("白酒", "白酒Ⅱ"), ("白色家电", "白色家电"), ("乘用车", "乘用车"),
        ("汽车零部件", "汽车零部件"), ("钢铁", "钢铁"), ("基础化工", "基础化工"),
        ("生猪养殖", "生猪养殖"), ("航运", "航运"),
    ],
}


def iter_track():
    """展开 (赛道名, 东财板块名, 分组) 序列。"""
    for group, items in TRACK_GROUPS.items():
        for track, board in items:
            yield track, board, group


# ---------------------------------------------------------------------------
# 基础请求
# ---------------------------------------------------------------------------
def _num(v):
    try:
        x = float(v or 0)
        return x if x == x else 0.0
    except (TypeError, ValueError):
        return 0.0


def _clist(fs, fid, po, fields, pn=1, pz=100):
    params = {"pn": pn, "pz": pz, "po": po, "np": 1,
              "ut": "bd1d9ddb04089700cf9c27f6f7426281",
              "fltt": 2, "invt": 2, "fid": fid, "fs": fs, "fields": fields}
    q = "&".join("%s=%s" % (k, v) for k, v in params.items())
    try:
        r = requests.get(HOST + "?" + q, timeout=15, headers=UA, proxies=PX)
        d = r.json().get("data") or {}
        return d.get("diff") or []
    except Exception as e:
        print("  [warn] clist 请求失败 fs=%s: %s" % (fs, e))
        return []


def secid_of(code):
    return ("sh" if code.startswith(("60", "68")) else "sz") + code


def is_a_stock(code, name):
    if not code.startswith(A_PREFIX):
        return False
    if name[:1] in ("N", "C", "N!"):
        return False
    if "ST" in name.upper() or "退" in name:
        return False
    return True


def fetch_all_boards():
    """翻页拉全东财行业板块榜(约496个, 每页上限100)。返回 {板块名: 行情dict}。"""
    out = {}
    pn = 1
    while pn <= 6:
        diff = _clist(FS_IND, "f109", 1, BOARD_FIELDS, pn=pn, pz=100)
        if not diff:
            break
        for it in diff:
            name = str(it.get("f14") or "").strip()
            code = str(it.get("f12") or "").strip()
            if not name or not code:
                continue
            out[name] = {
                "name": name, "code": code,
                "chg_pct": _num(it.get("f3")),          # 当日涨幅%
                "chg20_pct": _num(it.get("f109")),      # 近20日(约1月)涨幅%
                "main_yi": _num(it.get("f62")) / 1e8,   # 主力净流入(亿)
                "leader": str(it.get("f128") or ""),
                "leader_code": str(it.get("f140") or ""),
            }
        pn += 1
        time.sleep(SLEEP)
    return out


def fetch_board_members(code):
    """板块成分按总市值降序取前 ~90。过滤 ST/退/次新/B股/北交。"""
    members = []
    for it in _clist("b:%s" % code, "f20", 1, STOCK_FIELDS, pz=100):
        raw = str(it.get("f12") or "")
        name = str(it.get("f14") or "").replace(" ", "")
        if len(raw) != 6 or not is_a_stock(raw, name):
            continue
        members.append({
            "secid": secid_of(raw),
            "code": raw,
            "name": name,
            "mv_yi": round(_num(it.get("f20")) / 1e8, 1),
            "chg_pct": _num(it.get("f3")),
        })
    members.sort(key=lambda x: -(x["mv_yi"] or 0))
    return members


# ---------------------------------------------------------------------------
# 活跃度评分
# ---------------------------------------------------------------------------
def _pct_rank(vals, v):
    if not vals:
        return 0.5
    s = sorted(vals)
    return bisect.bisect_left(s, v) / max(1.0, len(s))


def _active_score(boards, mode):
    """给赛道行情打分。mode: momentum(近20日)/day(当日)/flow(主力净流入)/blend。"""
    for b in boards:
        b["_chg20"] = b["chg20_pct"] or 0
        b["_chg"] = b["chg_pct"] or 0
        b["_flow"] = b["main_yi"] or 0
    if mode == "momentum":
        boards.sort(key=lambda b: -b["_chg20"])
    elif mode == "day":
        boards.sort(key=lambda b: -b["_chg"])
    elif mode == "flow":
        boards.sort(key=lambda b: -b["_flow"])
    else:  # blend
        p20 = [b["_chg20"] for b in boards]
        pday = [b["_chg"] for b in boards]
        pflow = [b["_flow"] for b in boards]
        for b in boards:
            b["_score"] = (0.5 * _pct_rank(p20, b["_chg20"])
                           + 0.3 * _pct_rank(pday, b["_chg"])
                           + 0.2 * _pct_rank(pflow, b["_flow"]))
        boards.sort(key=lambda b: -b["_score"])
    return boards


# ---------------------------------------------------------------------------
# 图谱组装
# ---------------------------------------------------------------------------
def pick_leaders(members, n, exclude_codes=()):
    """按市值取前 n 只(排除指定代码)。"""
    picked = [m for m in members if m["code"] not in set(exclude_codes)]
    return picked[:n]


def build_sector(track, board, quote, n_leaders):
    """单赛道 → 龙头图谱节点。quote 来自板块全量榜; 拉成分选龙头。"""
    members = fetch_board_members(quote["code"])
    core = pick_leaders(members, n_leaders)
    core_codes = [c["code"] for c in core]

    # 当日涨幅最大(剔除核心龙头后) = 情绪/日内领涨
    rest = [m for m in members if m["code"] not in core_codes]
    day_hot = max(rest, key=lambda m: m["chg_pct"] or -999) if rest else None

    # 近20日人气领涨(板块榜自带 f128/f140, 可能已落在 core 中)
    month_hot = None
    lc = str(quote.get("leader_code") or "")
    if lc and quote.get("leader"):
        hit = next((m for m in members if m["code"] == lc), None)
        if hit and hit["code"] not in core_codes:
            month_hot = hit

    return {
        "track": track,
        "board": board,
        "code": quote["code"],
        "group": None,          # 组装时补
        "rank": {
            "chg20_pct": round(quote["chg20_pct"], 2),
            "chg_pct": round(quote["chg_pct"], 2),
            "main_yi": round(quote["main_yi"], 2),
            "members": len(members),
        },
        "core_leaders": core,
        "day_hot": day_hot,
        "month_hot": month_hot,
    }


# ---------------------------------------------------------------------------
# Markdown 渲染
# ---------------------------------------------------------------------------
def fmt_stock(m, brief=False):
    if not m:
        return "-"
    chg = m.get("chg_pct")
    chg_txt = ("%+.1f%%" % chg) if chg is not None else "-"
    if brief:
        return "%s(%s)" % (m["name"], m["code"])
    mv = m.get("mv_yi")
    mv_txt = ("%.0f亿" % mv) if mv else "-"
    return "%s(%s) 市值%s %s" % (m["name"], m["code"], mv_txt, chg_txt)


def render_md(payload):
    L = []
    L.append("# A股行业龙头图谱（当日活跃赛道）")
    L.append("")
    L.append("> 行情日期：%s  生成：%s" % (payload["date"], payload["generated_at"]))
    L.append("> 口径：覆盖精选赛道 %d 个（半导体产业链/PCB/MLCC/光通信/新能源/有色/创新药等，见脚本 TRACK_GROUPS），"
             "按活跃度自动圈定 %d 个入榜（近20日动量50%%+当日涨幅30%%+主力净流入20%%）。"
             % (payload["total_tracks"], len(payload["sectors"])))
    L.append("> 核心龙头=赛道成分按总市值 Top%d（代码+名称，附市值与当日涨跌）；人气领涨=东财板块榜自带领涨股(板块内人气/活跃)，当日涨幅最大者存于 JSON 的 day_hot。" % payload["n_leaders_per_board"])
    L.append("> 数据源：%s" % payload["source"])
    L.append("")
    # 按 TRACK_GROUPS 固定分组顺序展示, 组内保持活跃度排序
    by_group = {}
    for s in payload["sectors"]:
        by_group.setdefault(s["group"], []).append(s)
    for group in TRACK_GROUPS:
        items = by_group.get(group)
        if not items:
            continue
        L.append("## %s" % group)
        L.append("")
        L.append("| 赛道 | 近20日 | 当日 | 主力净流入 | 核心龙头(市值Top) | 人气领涨 |")
        L.append("|---|---:|---:|---:|---|---|")
        for s in items:
            r = s["rank"]
            core_txt = "<br>".join(fmt_stock(m) for m in s["core_leaders"]) if s["core_leaders"] else "-"
            hot = s.get("month_hot") or s.get("day_hot")
            hot_txt = fmt_stock(hot) if hot else "-"
            L.append("| %s | %+.1f%% | %+.1f%% | %+.2f亿 | %s | %s |" % (
                s["track"], r["chg20_pct"], r["chg_pct"], r["main_yi"], core_txt, hot_txt))
    L.append("")
    L.append("---")
    L.append("更新：`python smalltools/_industry_leader_map.py`（--top 数量 / --mode 活跃口径 / --all 全赛道）")
    L.append("")
    return "\n".join(L)


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser(description="行业龙头图谱生成器(活跃赛道→龙头)")
    ap.add_argument("--top", type=int, default=20, help="入榜赛道数(默认20, 最多=赛道总数)")
    ap.add_argument("--mode", default="blend", choices=["blend", "momentum", "day", "flow"],
                    help="活跃度口径: blend默认 / momentum近20日 / day当日 / flow主力净流入")
    ap.add_argument("--leaders", type=int, default=3, help="每赛道核心龙头数(默认3)")
    ap.add_argument("--all", action="store_true", help="全部赛道都取龙头(成分请求数=赛道数)")
    ap.add_argument("--no-md", action="store_true", help="只出 JSON")
    ap.add_argument("--stdout", action="store_true", help="控制台打印 Markdown 不落盘")
    args = ap.parse_args()

    tracks = list(iter_track())
    n_track = len(tracks)
    top_n = n_track if args.all else max(5, min(args.top, n_track))
    n_ld = max(1, min(5, args.leaders))
    print("赛道目录 %d 个 | 入榜 %d | 每赛道龙头 %d" % (n_track, top_n, n_ld))

    print("拉取东财行业板块全量榜(约496个)...")
    boards = fetch_all_boards()
    print("  板块榜 %d 个" % len(boards))

    # 组装赛道行情(仅 TRACK 内的; 板块名失效则报错提示)
    quotes, missing = [], []
    for track, board, group in tracks:
        q = boards.get(board)
        if not q:
            missing.append("%s→%s" % (track, board))
            continue
        quotes.append({"track": track, "board": board, "group": group, **q})
    if missing:
        print("  [warn] 以下赛道未匹配到东财板块(板块名可能已改, 需更新 TRACK): %s" % "; ".join(missing))

    quotes = _active_score(quotes, args.mode)
    print("按 %s 活跃度排序 Top%d:" % (args.mode, top_n))
    for q in quotes[:12]:
        print("  %-14s(%s) 近20日%+6.1f%% 当日%+6.1f%% 主力%+7.2f亿" % (
            q["track"], q["board"], q["chg20_pct"], q["chg_pct"], q["main_yi"]))

    print("逐赛道拉成分取龙头...")
    selected = quotes[:top_n]
    sectors = []
    for i, q in enumerate(selected):
        s = build_sector(q["track"], q["board"], q, n_ld)
        s["group"] = q["group"]
        sectors.append(s)
        print("  [%d/%d] %-12s 龙头: %s" % (
            i + 1, top_n, s["track"],
            " ".join("%s(%s)" % (m["name"], m["code"]) for m in s["core_leaders"])))
        time.sleep(SLEEP)

    today = time.strftime("%Y-%m-%d")
    payload = {
        "date": today,
        "generated_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        "mode": args.mode,
        "source": "push2delay.eastmoney.com clist (行业板块榜 + 板块成分, 市值取龙头)",
        "total_tracks": n_track,
        "n_leaders_per_board": n_ld,
        "sectors": sectors,
    }

    if not args.stdout:
        os.makedirs(OUT_DIR, exist_ok=True)
        with open(JSON_OUT, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False, indent=1)
        print("\nJSON 落盘: %s" % JSON_OUT)
        if not args.no_md:
            with open(MD_OUT, "w", encoding="utf-8") as f:
                f.write(render_md(payload))
            print("Markdown 落盘: %s" % MD_OUT)
    else:
        print("\n" + render_md(payload))


if __name__ == "__main__":
    main()
