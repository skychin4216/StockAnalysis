# -*- coding: utf-8 -*-
"""期货与库存速览（2026-09-08 新增：开盘前大盘速览附加段「🧭 期货与库存速览」）。

数据源：
  新浪国内期货主连实时行情  hq.sinajs.cn/list=nf_SR0,nf_JM0,...（需 Referer: finance.sina.com.cn）
  字段（逗号分隔，实测校准 2026-09-08）：
    idx0 名称 / idx1 时间戳 / idx2 今开 / idx3 最高 / idx4 最低 / idx5 昨收(连续多为0)
    idx6 买一 / idx7 卖一 / idx8 最新价 / idx9 今结算(盘中0) / idx10 昨结算
    idx11/12 盘口量 / idx13 持仓量 / idx14 成交量 / idx15 交易所 / idx16 品种 / idx17 日期

消息面/库存线索：smalltools/data/_macro_topics.json（巡检/搜索维护：主题→依据/日期/A股映射）。

对外：render_futures() -> list[str] 速览文本行；网络/解析失败返回 []（守护静默降级，
不因附加段失败阻塞主推送）。

2026-09-08 首版线索（用户指定方向）：
  - 煤炭：9月港口库存去化 + 供暖季补库临近 → 双焦/动力煤 + 煤电；
  - 糖/厄尔尼诺：NOAA 确认 2026 强厄尔尼诺（秋冬季概率>90%），参考 2015 年式糖周期。
2026-09-08 扩展线索（检索核实：库存紧张+低位+周期启动+期货涨价预期）：
  - 铝/氧化铝：LME 库存 36 年低位 + 中东产能受损 + 几内亚限矿 + 国内天花板/枯水期；
  - 锡：供需缺口 1-2 年但已 +40% 高位区（提示勿追高）；
  - 棕榈油/菜油：Q4 超强厄尔尼诺概率 95%，印尼减产 + B50 收紧出口；
  - 棉花/橡胶：东南亚/美国南部降水异常致供给扰动，美麦 3 年新高。
"""

import datetime as _dt
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
TOPICS_FILE = os.path.join(HERE, "data", "_macro_topics.json")

# 常规观察品种（新浪主连符号 → 中文名）；hot 主题由 topics 文件声明
WATCH = [
    ("CU0", "沪铜"), ("AU0", "沪金"), ("RB0", "螺纹钢"),
    ("ZC0", "动力煤"), ("TA0", "PTA"), ("P0", "棕榈油"), ("OI0", "菜油"),
]

_HEADERS = {"Referer": "https://finance.sina.com.cn", "User-Agent": "Mozilla/5.0"}


def _quote_map(symbols):
    """新浪主连实时行情 → {symbol: {...}}；失败重试一次。"""
    try:
        import requests
    except Exception:  # noqa: BLE001
        return {}
    for _ in range(2):
        try:
            r = requests.get(
                "https://hq.sinajs.cn/list=" + ",".join("nf_" + s for s in symbols),
                timeout=8, headers=_HEADERS)
            r.encoding = "gbk"
            out = {}
            for ln in r.text.split(";"):
                if "=" not in ln:
                    continue
                head, _, body = ln.partition("=")
                if '"' not in body:
                    continue
                sym = head.rsplit("nf_", 1)[-1].strip().upper()
                p = body.strip().strip('"').split(",")
                if len(p) < 18 or not p[0]:
                    continue
                try:
                    last = float(p[8] or 0)
                    prev = float(p[10] or 0)
                except ValueError:
                    continue
                if prev <= 0 or last <= 0:
                    continue
                out[sym] = {
                    "name": p[16] or p[0], "date": p[17],
                    "last": last, "prev": prev, "open": _f(p[2]),
                    "high": _f(p[3]), "low": _f(p[4]),
                    "pct": (last / prev - 1) * 100.0,
                    "hold": float(p[13] or 0) / 1e4,
                    "vol": float(p[14] or 0) / 1e4,
                }
            if out:
                return out
        except Exception:  # noqa: BLE001
            continue
    return {}


def _f(v):
    try:
        return float(v or 0)
    except ValueError:
        return 0.0


def _load_topics():
    try:
        with open(TOPICS_FILE, encoding="utf-8") as f:
            d = json.load(f)
        return d.get("topics") or []
    except (OSError, ValueError):
        return []


def render_futures():
    """渲染「🧭 期货与库存速览」文本行；失败/无行情返回 []。"""
    try:
        topics = _load_topics()
        syms = [s for s, _c in WATCH]
        for t in topics:
            for s in (t.get("futures") or []):
                if s not in syms:
                    syms.append(s)
        q = _quote_map(syms)
        if not q:
            return []
        lines = ["🧭 期货与库存速览（%s）" % _dt.date.today().strftime("%m-%d")]
        # ① hot 主题行：行情 + 依据 + A股映射
        hot_shown = set()
        for t in topics:
            if not t.get("hot"):
                continue
            fq = [(s, q[s]) for s in (t.get("futures") or []) if s in q]
            if not fq:
                continue
            syms_used = []
            for s, _x in fq:
                hot_shown.add(s)
                syms_used.append(s)
            quote_txt = " | ".join(
                "%s %s %+.2f%%" % (_name(q[s]), _num(q[s]["last"]), q[s]["pct"])
                for s, _x in fq)
            ev = (t.get("evidence") or "").strip()
            if len(ev) > 52:
                ev = ev[:52].rstrip("，。；") + "…"
            stock = t.get("stocks") or ""
            line = "  🔥 %s: %s｜%s" % (t.get("theme", ""), quote_txt, ev)
            if stock:
                line += " → %s" % stock
            lines.append(line)
        # ② 常规观察（排除已入 hot 行的品种；明显异动前置 ⚠/🔻）
        rest = [q[s] for s, _c in WATCH if s in q and s not in hot_shown]
        if rest:
            rest.sort(key=lambda x: -abs(x["pct"]))
            toks = []
            for x in rest:
                mark = ""
                if abs(x["pct"]) >= 1.5:
                    mark = "🔺" if x["pct"] > 0 else "🔻"
                toks.append("%s%s %s %+.2f%%" % (mark, _name(x), _num(x["last"]), x["pct"]))
            if toks:
                lines.append("  ⚪ 观察: " + " | ".join(toks))
        return lines
    except Exception:  # noqa: BLE001
        return []


def _name(x):
    return x.get("name") or "?"


def _num(v):
    return ("%.0f" % v) if v >= 1000 else ("%.1f" % v)


def main():
    import sys
    sys.path.insert(0, HERE)
    lines = render_futures()
    if not lines:
        print("期货行情获取失败（网络/解析），本次速览跳过该段")
        return 1
    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    sys.exit(main())
