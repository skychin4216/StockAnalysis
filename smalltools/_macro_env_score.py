# -*- coding: utf-8 -*-
"""大环境多因子打分（2026-09-19 用户需求②）。

把"网上推演的黄金逻辑链"改造成**可量化的多因子打分**，而不是单链推导。
权重直接取自 `_macro_gold_chain.py` 的实测相关系数（2015-2026 月度，136 个月）：

  因子                             实测 r      用途
  10Y TIPS 实际利率变化 → 黄金      -0.24      ★计入（教科书主因，弱但方向明确）
  美元指数月涨% → 黄金              -0.23      ★计入（弱但方向明确）
  油价月涨% → 次月 CPI 同比变化      +0.38      ★仅用于 CPI 预警（不直接给黄金打分）
  CPI 同比变化 → 加息               -0.03      ✗ 不成立 → 不参与
  加息 → 黄金                       -0.01      ✗ 不成立 → 不参与
  油价月涨% → 黄金                  -0.05      ✗ 不成立 → 不参与（原推演链后半段未被验证）

三资产倾向分（-2.0 ~ +2.0；负 = 利空/减配，正 = 利好/加配）：

  黄金 = -1.2×z(TIPS变化) - 1.2×z(美元月涨)
  能源 = +1.0×z(油价月涨) + 0.5×高位分(油价≥100 且维持 20 天)
  科技 = -0.8×z(TIPS变化) - 0.8×z(油价月涨)     （高利率 + 高油价双杀高估值成长）

  z() = 用近 3 年同口径序列做标准化（避免绝对量纲差异）。

另输出 **CPI 预警行**：油价 ≥ 100 且持续 ≥ 20 天 → 提示"下月 CPI 上行风险"（实证 r=+0.38）。

数据：FRED（DTWEXBGS 美元 / DFII10 10Y TIPS / DCOILWTICO WTI），近 3 年；当日缓存。

用法：
    python _macro_env_score.py            # 打印打分明细
    python _macro_env_score.py --line     # 只输出一行推送文本（供早盘推送调用）
"""
import argparse
import datetime as dt
import json
import os
import sys

import requests

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DATA = os.path.join(ROOT, "data")
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import _sources as _S  # noqa: E402

PROXIES = {"http": None, "https": None}
UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}
FRED_CSV = _S.url("fred")
CACHE = os.path.join(DATA, "_macro_env_score.json")
W = {"tips": 1.2, "usd": 1.2, "oil": 1.0, "oil_hi": 0.5, "tech_tips": 0.8,
     "tech_oil": 0.8}


def _series(sid, force=False):
    """FRED 序列 → {date: value}（当日缓存）。"""
    try:
        c = json.load(open(CACHE, encoding="utf-8"))
    except Exception:  # noqa: BLE001
        c = {}
    if not force and c.get("built", "")[:10] == dt.date.today().isoformat() \
            and sid in (c.get("s") or {}):
        return c["s"][sid]
    out = {}
    try:
        r = requests.get(FRED_CSV, params={"id": sid}, timeout=25, headers=UA,
                         proxies=PROXIES)
        for ln in r.text.strip().splitlines()[1:]:
            d, _, v = ln.partition(",")
            v = v.strip()
            if v and v != ".":
                try:
                    out[d.strip()] = float(v)
                except ValueError:
                    continue
    except Exception as e:  # noqa: BLE001
        print("  ⚠ %s 拉取失败：%s" % (sid, str(e)[:60]))
    c.setdefault("s", {})[sid] = out
    c["built"] = dt.datetime.now().strftime("%Y-%m-%d %H:%M")
    try:
        json.dump(c, open(CACHE, "w", encoding="utf-8"), ensure_ascii=False)
    except Exception:  # noqa: BLE001
        pass
    return out


def _monthly(series, how="mean"):
    b = {}
    for d, v in (series or {}).items():
        b.setdefault(d[:7], []).append((d, v))
    out = {}
    for m, arr in b.items():
        arr.sort()
        out[m] = sum(x[1] for x in arr) / len(arr) if how == "mean" else arr[-1][1]
    return out


def _chg_pct(m):
    """月度变化%（最新月 vs 上月）。"""
    ks = sorted(m)
    out = {}
    for i, k in enumerate(ks):
        if i >= 1 and m[ks[i - 1]]:
            out[k] = (m[k] / m[ks[i - 1]] - 1) * 100
    return out


def _chg_abs(m):
    ks = sorted(m)
    return {ks[i]: m[ks[i]] - m[ks[i - 1]] for i in range(1, len(ks))}


def _z(seq_vals, v):
    """用近 36 期做标准化 → 截断到 ±2。"""
    tail = list(seq_vals)[-36:]
    if len(tail) < 6:
        return 0.0
    mu = sum(tail) / len(tail)
    sd = (sum((x - mu) ** 2 for x in tail) / len(tail)) ** 0.5 or 1.0
    return max(-2.0, min(2.0, (v - mu) / sd))


def _wti_high_days(oil_daily, thr=100.0):
    """WTI 最近连续 ≥thr 的交易日数（用于 CPI 预警：≥20 天）。"""
    ks = sorted(oil_daily)
    n = 0
    for k in reversed(ks):
        if oil_daily[k] >= thr:
            n += 1
        else:
            break
    return n


def compute(force=False):
    """返回 {tips_chg, usd_chg, oil_chg, oil_last, hi_days, gold, energy, tech, warn}。"""
    tips = _monthly(_series("DFII10", force))
    usd = _monthly(_series("DTWEXBGS", force))
    oil_d = _series("DCOILWTICO", force)
    oil = _monthly(oil_d)
    if not (tips and usd and oil):
        return None
    tc, uc, oc = _chg_abs(tips), _chg_pct(usd), _chg_pct(oil)
    m_now = max(set(tc) & set(uc) & set(oc))
    zt = _z(tc.values(), tc[m_now])
    zu = _z(uc.values(), uc[m_now])
    zo = _z(oc.values(), oc[m_now])
    oil_last = oil[max(oil)]
    hi = _wti_high_days(oil_d)
    hi_score = 1.0 if hi >= 20 else (0.5 if hi >= 10 else 0.0)
    gold = round(-W["tips"] * zt - W["usd"] * zu, 2)
    energy = round(W["oil"] * zo + W["oil_hi"] * hi_score, 2)
    tech = round(-W["tech_tips"] * zt - W["tech_oil"] * zo, 2)
    warn = ""
    if hi >= 20:
        warn = ("油价 $%.0f 已连续 %d 个交易日站上 100 → 下月 CPI 上行风险"
                "（实证 r=+0.38，2026-09-19 验证）" % (oil_last, hi))
    return {"month": m_now, "tips_chg": round(tc[m_now], 3),
            "usd_chg": round(uc[m_now], 2), "oil_chg": round(oc[m_now], 2),
            "oil_last": round(oil_last, 2), "hi_days": hi,
            "zt": round(zt, 2), "zu": round(zu, 2), "zo": round(zo, 2),
            "gold": gold, "energy": energy, "tech": tech, "warn": warn}


def _tag(v):
    if v >= 1.0:
        return "加配↑↑"
    if v >= 0.3:
        return "偏多↑"
    if v <= -1.0:
        return "减配↓↓"
    if v <= -0.3:
        return "偏空↓"
    return "中性→"


def line():
    """一行推送文本（供早盘推送 08:00 段调用；数据缺失返回 ""）。"""
    r = compute()
    if not r:
        return ""
    s = ("🌍 大环境多因子：黄金 %+0.2f(%s) ｜ 科技 %+0.2f(%s) ｜ 能源 %+0.2f(%s)"
         "（%s；因子：TIPS变化 %+.2f、美元 %+.2f%%、油价 %+.2f%%、WTI $%.0f）"
         % (r["gold"], _tag(r["gold"]), r["tech"], _tag(r["tech"]),
            r["energy"], _tag(r["energy"]), r["month"],
            r["tips_chg"], r["usd_chg"], r["oil_chg"], r["oil_last"]))
    if r["warn"]:
        s += " ｜⚠️ " + r["warn"]
    return s


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--line", action="store_true")
    ap.add_argument("--force", action="store_true")
    a = ap.parse_args()
    if a.line:
        print(line())
        return
    r = compute(force=a.force)
    if not r:
        print("数据不足（FRED 不可达或序列为空）")
        return
    print("== 大环境多因子打分（%s）==" % r["month"])
    print("  因子：TIPS实际利率变化 %+.3f ｜ 美元月涨 %+.2f%% ｜ 油价月涨 %+.2f%%"
          "（WTI $%.2f，高位 %d 天）" % (
              r["tips_chg"], r["usd_chg"], r["oil_chg"], r["oil_last"], r["hi_days"]))
    print("  标准分：z(TIPS)=%+.2f  z(美元)=%+.2f  z(油价)=%+.2f" % (
        r["zt"], r["zu"], r["zo"]))
    print("  倾向：黄金 %+0.2f %s ｜ 科技 %+0.2f %s ｜ 能源 %+0.2f %s" % (
        r["gold"], _tag(r["gold"]), r["tech"], _tag(r["tech"]),
        r["energy"], _tag(r["energy"])))
    if r["warn"]:
        print("  ⚠️ " + r["warn"])
    print("\n  推送行：\n  " + line())


if __name__ == "__main__":
    main()
