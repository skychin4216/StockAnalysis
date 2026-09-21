# -*- coding: utf-8 -*-
"""油价 / 黄金 事件复盘 → A 股利好利空板块 + 趋势（2026-09-21 用户需求 · Q3）

承接 `_macro_gold_chain.py`（油价→CPI→加息→黄金 逻辑链的统计检验），
本脚本做**落地映射**：

  1. 拉实时行情：上海原油 SC0、沪金 AU0（内盘，字段已校准）+ 外盘 WTI/伦敦金（尽力）
  2. 算今日 / 5日 / 20日 变化
  3. 按经济链条映射到 A 股板块的**利好 / 利空**
  4. **用 A 股板块真实近 20 日动量反向校验**映射是否已被市场定价
     （若"利好板块"其实已在跌 → 说明市场不认同或已提前反映）
  5. 输出 md 文档 + 可选推送微信群

CLI:
    python _macro_oil_gold_review.py              # 复盘 + 写 md
    python _macro_oil_gold_review.py --push       # 额外推送微信群
"""
from __future__ import annotations

import datetime as dt
import json
import os
import sys
from typing import Any, Dict, List, Optional, Tuple

import requests

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

PROXIES = {"http": None, "https": None}
HEADERS = {"Referer": "https://finance.sina.com.cn", "User-Agent": "Mozilla/5.0"}
DOC = os.path.join(ROOT, "docs", "油价黄金复盘.md")

# 内盘期货字段（对齐 _macro_futures.py 文档：idx8=最新价 idx10=昨结算 idx2=今开 idx17=日期）
NF = {"SC0": "上海原油", "AU0": "沪金", "AG0": "沪银", "PG0": "LPG", "FU0": "燃油"}

# 外盘（尽力，失败忽略）
HF = {"CL": "WTI原油", "GC": "伦敦金", "NG": "天然气"}

# ── 事件 → A 股板块映射（依据经济链条，人工整理）──
# 每条: (板块关键词, 方向, 依据)
MAP_OIL_DOWN: List[Tuple[str, int, str]] = [
    ("航空机场", +1, "燃油占营业成本 25~40%，油价跌直接增厚利润"),
    ("航运港口", +1, "燃料成本占比高（干散/集运/油运均受益）"),
    ("物流", +1, "运输燃料成本下行"),
    ("汽车", +1, "用车成本下降 + 消费刺激预期"),
    ("化学制品", +1, "原油是主要原料，成本端下行（需求端需同步验证）"),
    ("轮胎轮毂", +1, "合成橡胶/炭黑等原料随油价回落"),
    ("炼油化工", -1, "炼化价差收窄（成品油调价滞后于原油）"),
    ("油气开采Ⅱ", -1, "实现油价直接下移，利润弹性负向"),
    ("油服工程", -1, "上游资本开支预期收缩"),
    ("煤炭", -1, "能源比价效应：油跌削弱煤价支撑"),
]
MAP_GOLD_UP: List[Tuple[str, int, str]] = [
    ("黄金", +1, "金价上涨直接抬升金矿企业毛利与存货重估收益"),
    ("贵金属", +1, "同上（白银弹性更大）"),
    ("钟表珠宝", +1, "金价上行带动终端提价与存货增值"),
    ("有色金属", 0, "需区分：金/银受益，铜/铝看工业需求"),
]
MAP_GOLD_DOWN: List[Tuple[str, int, str]] = [
    ("黄金", -1, "金价回落压制金矿利润与估值"),
    ("钟表珠宝", -1, "金价下跌时终端观望，量价齐跌风险"),
    ("贵金属", -1, "同上"),
]

# 注：板块名必须用东财 push2delay 的**精确名**（模糊匹配会把「航空」错配到
# 「航空装备Ⅲ」这种军工板块，导致校验数据张冠李戴）
VALIDATE_SECTORS = ["航空机场", "航运港口", "物流", "汽车", "化学制品", "轮胎轮毂",
                    "炼油化工", "油气开采Ⅱ", "油服工程", "煤炭",
                    "黄金", "贵金属", "钟表珠宝", "有色金属"]


# ══════════════════════════════════════════════════════════
# 行情
# ══════════════════════════════════════════════════════════

def fetch_inner() -> Dict[str, Dict[str, Any]]:
    """内盘期货：最新价 / 昨结算 / 今日涨跌%。"""
    out: Dict[str, Dict[str, Any]] = {}
    try:
        url = "https://hq.sinajs.cn/list=" + ",".join("nf_" + c for c in NF)
        r = requests.get(url, headers=HEADERS, timeout=10, proxies=PROXIES)
        r.encoding = "gbk"
        for line in r.text.strip().splitlines():
            if "=" not in line:
                continue
            key = line.split("=")[0].strip().split("_")[-1].upper()
            raw = line.split('"')[1] if '"' in line else ""
            f = raw.split(",")
            if len(f) < 11:
                continue
            try:
                last = float(f[8] or 0)
                prev = float(f[10] or 0)
            except (ValueError, IndexError):
                continue
            if last <= 0:
                continue
            out[key] = {"name": NF.get(key, key), "last": last, "prev": prev,
                        "chg": round((last / prev - 1) * 100, 2) if prev else 0.0}
    except Exception as e:                       # noqa: BLE001
        print("内盘行情获取失败:", e)
    return out


def fetch_outer() -> Dict[str, Dict[str, Any]]:
    """外盘（尽力）：WTI / 伦敦金。字段不确定时只取最新价与昨收的启发式解析。"""
    out: Dict[str, Dict[str, Any]] = {}
    try:
        url = "https://hq.sinajs.cn/list=" + ",".join("hf_" + c for c in HF)
        r = requests.get(url, headers=HEADERS, timeout=10, proxies=PROXIES)
        r.encoding = "gbk"
        for line in r.text.strip().splitlines():
            if "=" not in line:
                continue
            key = line.split("=")[0].strip().split("_")[-1].upper()
            raw = line.split('"')[1] if '"' in line else ""
            f = [x for x in raw.split(",") if x.strip() != ""]
            if not f:
                continue
            try:
                last = float(f[0])
            except ValueError:
                continue
            # 昨收：在字段里找与 last 最接近且差值合理的候选（idx 6~9 区间）
            prev = 0.0
            for cand in f[6:10]:
                try:
                    v = float(cand)
                except ValueError:
                    continue
                if v > 0 and (prev == 0 or abs(v - last) < abs(prev - last)):
                    prev = v
            out[key] = {"name": HF.get(key, key), "last": last, "prev": prev,
                        "chg": round((last / prev - 1) * 100, 2) if prev else 0.0,
                        "raw_n": len(f)}
    except Exception as e:                       # noqa: BLE001
        print("外盘行情获取失败:", e)
    return out


# ══════════════════════════════════════════════════════════
# 板块校验（市场是否已定价）
# ══════════════════════════════════════════════════════════

def board_momentum() -> Dict[str, Dict[str, Any]]:
    """东财**全行业**板块行情（含今日/5日/10日/20日涨跌幅 + 主力净流入）。

    比 `_sector_gate`（只用池内 102 个行业）覆盖更全 —— 航空/航运/石油/黄金
    这些**池外行业**也能拿到真实动量，否则映射表全是「—」没法校验。
    """
    out: Dict[str, Dict[str, Any]] = {}
    try:
        import _sources as _S                                # noqa: PLC0415
        host = _S.url("east_delay_clist")                    # push2delay：本机可达
        # 注：本机 push2.eastmoney.com 全分片被断（RemoteDisconnect），
        #     push2delay 才是通的（`_sector_fundflow.py` 用的也是它）。
        r = requests.get(
            host,
            params={"pn": "1", "pz": "500", "po": "1", "np": "1", "fltt": "2", "invt": "2",
                    "fid": "f3", "fs": "m:90+t:2",
                    "fields": "f3,f12,f14,f62,f109,f185,f186"},
            headers={"Referer": "https://quote.eastmoney.com/", "User-Agent": "Mozilla/5.0"},
            timeout=12, proxies=PROXIES)
        for it in ((r.json().get("data") or {}).get("diff") or []):
            nm = it.get("f14")
            if not nm:
                continue
            out[nm] = {
                "chg": it.get("f3") if isinstance(it.get("f3"), (int, float)) else 0.0,
                "d5": it.get("f109") if isinstance(it.get("f109"), (int, float)) else 0.0,
                "d10": it.get("f185") if isinstance(it.get("f185"), (int, float)) else 0.0,
                "d20": it.get("f186") if isinstance(it.get("f186"), (int, float)) else 0.0,
                "main_yi": round((it.get("f62") or 0) / 1e8, 2),
            }
    except Exception as e:                               # noqa: BLE001
        print("东财板块行情获取失败:", e)
    return out


def fuzzy_board(board: Dict[str, Dict[str, Any]], name: str) -> Optional[Dict[str, Any]]:
    """板块名模糊匹配（东财名如「航空机场」，映射表用「航空」）。"""
    if name in board:
        return board[name]
    for k, v in board.items():
        if name in k or k in name:
            return v
    return None


def sector_check(names: List[str]) -> Dict[str, Dict[str, Any]]:
    """双源合并的板块校验（本机数据可达性实测决定）：

      ① **20 日动量 / 连涨** ← `_sector_gate`（基于本地 K 线池等权合成，可靠但只覆盖池内行业）
      ② **今日涨跌幅 / 主力净流入** ← `_sector_fundflow`（东财 push2delay，496 板块，
         但本机只有当日值，5/10/20 日字段为空）

    ⇒ 两个维度互补：①能看趋势但覆盖窄，②覆盖全但只有当日。
    """
    out: Dict[str, Dict[str, Any]] = {}
    try:
        import _sector_gate as SG                            # noqa: PLC0415
    except Exception:                                        # noqa: BLE001
        SG = None
    flow: Dict[str, Dict[str, Any]] = {}
    try:
        import _sector_fundflow as SF                        # noqa: PLC0415
        flow = SF.fetch_board_flow() or {}
    except Exception:                                        # noqa: BLE001
        pass
    for nm in names:
        row: Dict[str, Any] = {"sector": nm}
        if SG is not None:
            m = SG.metrics(nm)
            if m.get("ok"):
                row["mom20"] = m["mom20"]
                row["up_streak"] = m["up_streak"]
        f = fuzzy_board(flow, nm)
        if f:
            row["chg"] = f.get("zdf_pct")
            row["main_yi"] = f.get("main_yi")
        if len(row) > 1:
            out[nm] = row
    return out


# ══════════════════════════════════════════════════════════
# 复盘输出
# ══════════════════════════════════════════════════════════

def build_review() -> Dict[str, Any]:
    inner = fetch_inner()
    outer = fetch_outer()
    oil = inner.get("SC0") or outer.get("CL") or {}
    gold = inner.get("AU0") or outer.get("GC") or {}
    oil_down = (oil.get("chg") or 0) < 0
    gold_up = (gold.get("chg") or 0) > 0

    maps = list(MAP_OIL_DOWN if oil_down else [])
    maps += MAP_GOLD_UP if gold_up else MAP_GOLD_DOWN

    checks = sector_check(VALIDATE_SECTORS)

    lines = ["# 油价 / 黄金 事件复盘", "",
             "生成时间：%s" % dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S"), "",
             "## 一、行情快照", ""]
    for k, v in list(inner.items()):
        lines.append("- **%s**（内盘 %s）：%.2f ｜ 今日 %+.2f%%" %
                     (v["name"], k, v["last"], v["chg"]))
    for k, v in list(outer.items()):
        lines.append("- **%s**（外盘 %s）：%.2f ｜ 约 %+.2f%%" %
                     (v["name"], k, v["last"], v["chg"]))

    lines += ["", "## 二、链条映射（油价%s / 黄金%s）" %
              ("下跌" if oil_down else "上涨", "上涨" if gold_up else "下跌"), "",
              "| 板块 | 方向 | 依据 | 今日 | 20日动量 | 连涨 | 主力(亿) |",
              "|---|---|---|---|---|---|---|"]
    for name, direction, why in maps:
        c = checks.get(name) or {}
        lines.append("| %s | %s | %s | %s | %s | %s | %s |" % (
            name, "利好" if direction > 0 else ("利空" if direction < 0 else "中性"),
            why,
            ("%+.2f%%" % c["chg"]) if c.get("chg") is not None else "—",
            ("%+.2f%%" % c["mom20"]) if c.get("mom20") is not None else "—",
            c.get("up_streak", "—"),
            ("%+.2f" % c["main_yi"]) if c.get("main_yi") is not None else "—"))

    lines += ["", "## 三、市场是否已定价（真实板块数据反向校验）", "",
              "> 两个维度互补：**20日动量/连涨** 来自本地 K 线池（可靠但只覆盖池内行业）；",
              "> **今日/主力** 来自东财 push2delay（覆盖全但本机只有当日值）。", ""]
    got = [(n, checks[n]) for n in VALIDATE_SECTORS if n in checks]
    if got:
        got.sort(key=lambda x: (x[1].get("mom20") if x[1].get("mom20") is not None else -999),
                 reverse=True)
        for n, c in got:
            m20 = c.get("mom20")
            tag = ("🔥已启动" if (m20 or 0) > 8
                   else ("➡️温和" if (m20 or 0) > -3 else "❄️仍弱")) if m20 is not None else "（池内无 20 日数据）"
            lines.append("- %-6s 今日 %s ｜ 20日 %s ｜ 连涨 %s ｜ 主力 %s → %s" % (
                n,
                ("%+6.2f%%" % c["chg"]) if c.get("chg") is not None else "   —   ",
                ("%+6.2f%%" % m20) if m20 is not None else "   —   ",
                c.get("up_streak", "—"),
                ("%+.2f亿" % c["main_yi"]) if c.get("main_yi") is not None else "—",
                tag))
    else:
        lines.append("- （板块动量取不到，跳过）")

    lines += ["", "## 四、逻辑链统计检验（引自 _macro_gold_chain.py）", "",
              "2015-01 起 136 个月样本，检验「油价→CPI→加息→黄金」链条：", "",
              "| 假设 | 相关系数 r | 方向命中率 | 结论 |", "|---|---|---|---|",
              "| H1 油价月涨 → 次月CPI同比 | +0.03 | 57% | ❌ 不成立 |",
              "| H1b 油价月涨 → 次月CPI同比**变化** | **+0.38** | 61% | ✅ 成立 |",
              "| H2 CPI同比变化 → 次月利率变化 | -0.03 | 44% | ❌ 不成立 |",
              "| H3 利率变化 → 次月黄金收益 | -0.01 | 53% | ❌ 不成立 |",
              "| H4 油价月涨 → 次月黄金收益 | -0.05 | 42% | ❌ 不成立 |",
              "| H5 美元月涨 → 当月黄金收益 | **-0.23** | 45% | △ 弱 |",
              "| H7 10Y实际利率变化 → 当月黄金收益 | **-0.24** | 34% | △ 弱 |",
              "",
              "**要点**：",
              "1. **只有「油价→CPI变化」这一环成立**（r=+0.38）；再往后的「CPI→加息」「加息→金价」",
              "   **统计上不成立** —— 说明市场对加息的预期通常**提前**反应，用滞后月度数据无法捕捉。",
              "2. 黄金的**可靠驱动是美元与实际利率**（均为负相关，r≈-0.23/-0.24），",
              "   与「实际利率 = 名义利率 − 通胀预期」的定价框架一致。",
              "3. 所以**不要用「油价跌→CPI降→不加息→黄金涨」这条链去做黄金交易**；",
              "   直接用「美元指数」与「10Y 实际利率」两个变量更有效。", ""]

    # 明确、可执行的结论
    lines += ["## 五、可执行结论", ""]
    if oil_down:
        lines.append("- **油价下跌**：成本端利好 **航空 / 航运 / 物流 / 化工 / 轮胎**；"
                     "价格端利空 **油气开采 / 油服 / 石油加工**（煤价亦受能源比价拖累）。")
        lines.append("- 但上述为**逻辑映射**，是否可交易要看上方「市场是否已定价」——"
                     "若利好板块动量仍为负，说明市场不认同或尚未启动。")
    else:
        lines.append("- **油价上涨**：利好 **油气开采 / 油服 / 煤炭**；利空 **航空 / 航运 / 物流 / 化工**。")
    if gold_up:
        lines.append("- **黄金上涨**：直接利好 **金矿（黄金/贵金属）**、**珠宝首饰**；"
                     "但注意金价涨≠黄金股必涨（估值与成本端需同步看）。")
    else:
        lines.append("- **黄金下跌**：压制 **金矿（黄金/贵金属）/ 珠宝首饰**。")
    lines.append("- 组合提示：当前实仓含 **赤峰黄金 / 招金黄金**（黄金）与 **晋控煤业**（煤）——"
                 "前者看美元与 10Y 实际利率，后者看能源比价与油价。")
    lines += ["", "> 数据口径：内盘为新浪 `nf_` 实时（字段已校准）；板块动量为 `_sector_gate` "
                  "基于 K 线池等权合成，非官网板块指数。", ""]

    text = "\n".join(lines)
    return {"text": text, "oil": oil, "gold": gold,
            "oil_down": oil_down, "gold_up": gold_up, "checks": checks}


def main(push: bool = False) -> None:
    r = build_review()
    os.makedirs(os.path.dirname(DOC), exist_ok=True)
    with open(DOC, "w", encoding="utf-8") as f:
        f.write(r["text"])
    print("复盘已写入:", DOC)
    print(r["text"][:1200])
    if push:
        try:
            import push_channel as PC                    # noqa: PLC0415
            cfg = PC.load_notify_cfg()
            head = "油价%.2f(%+.2f%%) ｜ 黄金%.2f(%+.2f%%)" % (
                r["oil"].get("last", 0), r["oil"].get("chg", 0),
                r["gold"].get("last", 0), r["gold"].get("chg", 0))
            ok = PC.push("📉 油价/黄金复盘", head + "\n\n" + r["text"][:1500], cfg, kind="notice")
            print("推送:", "成功" if ok else "失败")
        except Exception as e:                           # noqa: BLE001
            print("推送异常:", e)


if __name__ == "__main__":
    main(push="--push" in sys.argv)
