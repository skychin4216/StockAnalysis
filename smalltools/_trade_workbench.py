# -*- coding: utf-8 -*-
"""《交易决策工作台》Excel —— 三系统合并（选股池 + 趋势规律 + 止损价）。

用户需求（2026-09-12）：
    「D. 三系统合并：把选股池 + 趋势规律 + 止损价合成一个统一的『交易决策工作台』Excel」
    「针对三大周期选股 + ETF 全行业扫描 + ETF top5 选股结果 + 实仓，所有股票加起来变成一个持仓」

口径（单一事实源，不另起一套算法）：
    ① 选股池    ← usecase `inst_holding` 的 `n_inst_pool`（三大周期选股 + ETF 全行业扫描
                  + ETF top5 + 实仓，由 `inst_pool_build` 汇总，见 inst_holding_pipeline.xml）
    ② 机构定性  ← `n_inst_judge`（读 data/_inst_holdings.json；A精选/B观察/C散户票）
    ③ 趋势规律  ← 直接复用 `usecase_pipeline` 的 `_trend_match_3way / _etf_macd_text /
                  _etf_sar_text / _etf_obv_text`（与 APK TrendClassGate 同口径）
    ④ 止损价    ← `n_inst_stop`（`stop_loss_vote` 六理论投票取最保守 + 棘轮）

因 ①②③④ 全部在**同一条 XML DAG**（app/src/main/assets/usecases/inst_holding_pipeline.xml）
里串起来，本脚本只做「跑 DAG → 拉 stageOutputs → 排版落 Excel」，APK/exe 双端同源。

用法：
    python _trade_workbench.py                       # asof = 行情缓存最新交易日
    python _trade_workbench.py --asof 2026-09-11
    python _trade_workbench.py --period LONG         # 止损周期（ULTRA_SHORT/SHORT/MID/LONG）
    python _trade_workbench.py --grade A             # 只导出 A 级精选
    python _trade_workbench.py --no-holdings         # 不含实仓
输出：data/交易决策工作台_<asof>.xlsx（并覆盖 data/交易决策工作台_latest.xlsx）
"""
import argparse
import datetime as _dt
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.normpath(os.path.join(HERE, ".."))
_USECASES = os.path.join(_ROOT, "app", "src", "main", "assets", "usecases")
_DATA = os.path.join(_ROOT, "data")
for _p in (HERE, _USECASES):
    if _p not in sys.path:
        sys.path.insert(0, _p)

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

from usecase_pipeline import (  # noqa: E402
    UseCaseRunner, _trend_match_3way, _etf_macd_text, _etf_sar_text,
    _etf_obv_text, _idiom_ma,
)

INST_ASSET = os.path.join(_DATA, "_inst_holdings.json")

# ── 主表列（三系统合并后的「一个持仓工作台」）──
HEAD = [
    "序号", "股票名称", "代码", "来源",
    "机构评级", "机构裁定", "机构分", "持股比例%", "本期环比pp", "连续增持期",
    "净增持pp", "基金家数", "户数环比%",
    "现价", "止损价", "止损幅度%", "距止损空间%", "止损理论", "风险动作", "动量",
    "趋势规律", "备注",
]
COL_W = [5, 12, 8, 16, 12, 18, 7, 10, 10, 9, 9, 8, 10,
         9, 9, 10, 11, 16, 14, 26, 18, 22]

_GRADE_ORDER = {"A": 0, "B": 1, "C": 2}


def _code6(code):
    """任意代码写法 → 6 位纯数字（'sh600519' / '600519.SH' / '600519'）。"""
    c = str(code or "").strip().lower()
    if "." in c:
        c = c.split(".", 1)[0]
    if c[:2] in ("sh", "sz", "bj"):
        c = c[2:]
    return c.zfill(6) if c.isdigit() and len(c) < 6 else c


def _bars_for(cache, code, limit=140):
    """从行情缓存取日K（供趋势规律重算；止损节点已 pop 掉 bars，此处按码回取）。"""
    c6 = _code6(code)
    for k in (("sh" if c6[:1] in ("6", "9") else "sz") + c6, c6):
        e = (cache or {}).get(k)
        if e and (e.get("snaps") or []):
            return (e["snaps"] or [])[-limit:]
    return []


def _win_theory(theory):
    """止损六理论 dict → 「赢家（取最保守=最高价）+ 价」。"""
    if not isinstance(theory, dict) or not theory:
        return "六理论取最保守"
    try:
        k = max(theory, key=lambda x: float(theory[x] or 0))
    except (TypeError, ValueError):
        return "六理论取最保守"
    return "取最保守:%s" % k


def _fmt(v, nd=2, suffix=""):
    if v is None or v == "":
        return "—"
    try:
        f = float(v)
    except (TypeError, ValueError):
        return str(v)
    return (("%." + str(int(nd)) + "f") % f) + suffix


def _trend_rule(bars):
    """趋势规律文本：复用 usecase_pipeline 同口径三类趋势匹配 + MACD/SAR/OBV/MA20 判读。"""
    if not bars or len(bars) < 26:
        return "—"
    try:
        closes = [float(s.get("close") or 0) for s in bars]
        highs = [float(s.get("high") or 0) for s in bars]
        lows = [float(s.get("low") or 0) for s in bars]
        vols = [float(s.get("volume") or 0) for s in bars]
    except (TypeError, ValueError):
        return "—"
    if not closes[-1]:
        return "—"
    m = _trend_match_3way(bars)
    ma20 = _idiom_ma(closes, 20, len(closes) - 1)
    pos = ""
    if ma20:
        pos = "站上MA20" if closes[-1] >= ma20 else "破MA20"
    parts = [x for x in (_etf_macd_text(closes), _etf_sar_text(closes, highs, lows),
                         _etf_obv_text(closes, vols), pos) if x]
    return "%s·%s" % (m.get("label") or "中性", " ".join(parts))


def _load_inst_asset():
    try:
        with open(INST_ASSET, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def run_workbench(asof=None, period="MID", grade="", no_holdings=False, out=None):
    from _full_cycle_backtest import load_cache
    cache = load_cache()
    if not cache:
        print("✗ 行情缓存为空（smalltools/_kline_cache.json）", file=sys.stderr)
        return 2
    all_dates = sorted({s["date"] for e in cache.values() for s in e.get("snaps", [])})
    asof = asof or all_dates[-1]
    if asof not in all_dates:
        prev = [d for d in all_dates if d <= asof]
        if not prev:
            print("✗ asof=%s 早于全部数据" % asof, file=sys.stderr)
            return 2
        asof = prev[-1]
        print("[提示] asof 取最近交易日 %s" % asof)

    # ── 跑 inst_holding DAG（汇总 → 机构判定 → 多理论止损）──
    #   overrides 覆盖 pipeline XML 默认（止损周期 / 是否含实仓 / 评级过滤），不改 XML 本身。
    g = (grade or "").strip().upper()
    runner = UseCaseRunner(
        "inst_holding", cache=cache, asof=asof, period="inst_holding",
        overrides={"stop_loss_vote": {"period": str(period or "MID").upper()},
                   "inst_pool_build": {"includeHoldings": (not no_holdings)},
                   "inst_holding_judge": {"gradeFilter": g}})
    try:
        runner.run()
    except Exception as e:  # noqa: BLE001
        print("✗ inst_holding DAG 执行失败: %s" % e, file=sys.stderr)
        return 2
    so = runner.ctx.stage_outputs or {}
    pool = so.get("n_inst_pool") or {}
    judge = so.get("n_inst_judge") or {}
    stop = so.get("n_inst_stop") or {}
    rows = list(stop.get("rows") or judge.get("rows") or pool.get("rows") or [])
    # 趋势规律需日K：下游止损节点会 pop 掉 bars，此处从 n_inst_pool 原始行按码回取。
    pool_bars = {_code6(r.get("code")): r.get("bars")
                 for r in (pool.get("rows") or []) if r.get("bars")}
    if no_holdings:
        rows = [r for r in rows if "实仓" not in (r.get("from") or "")]

    if g:
        rows = [r for r in rows if (r.get("grade") or "").upper() == g]

    rows.sort(key=lambda r: (_GRADE_ORDER.get((r.get("grade") or "").upper(), 3),
                             -(float(r.get("score") or 0))))
    inst = _load_inst_asset()

    def _cell(r, i):
        grade_ = (r.get("grade") or "").upper()
        bars = (r.get("bars") or pool_bars.get(_code6(r.get("code")))
                or _bars_for(cache, r.get("code")))
        return [
            i + 1, r.get("name") or "", _code6(r.get("code")), r.get("from") or "",
            grade_ or "—", r.get("label") or "—", _fmt(r.get("score"), 1),
            _fmt(r.get("hold_ratio"), 2), _fmt(r.get("latest_chg"), 2),
            _fmt(r.get("streak"), 0), _fmt(r.get("net_chg"), 2),
            _fmt(r.get("n_funds"), 0), _fmt(r.get("holder_chg_pct"), 1),
            _fmt(r.get("close") or (bars[-1].get("close") if bars else 0) or r.get("entry") or 0, 3),
            _fmt(r.get("stop"), 3),
            _fmt(r.get("stopPct"), 2, "%"), _fmt(r.get("spacePct"), 2, "%"),
            _win_theory(r.get("stopTheory")) if r.get("stop") else "—",
            r.get("stopAction") or ("已破位→清仓" if r.get("broken") else "持有"),
            r.get("momentum") or "—",
            _trend_rule(bars),
            ("⚠ 机构撤退/散户票" if grade_ == "C" else
             ("★ 机构真加仓·强趋势" if grade_ == "A" else "")),
        ]

    table = [_cell(r, i) for i, r in enumerate(rows)]
    n = len(table)
    cnt = {"A": 0, "B": 0, "C": 0, "—": 0}
    for r in rows:
        cnt[(r.get("grade") or "—").upper() if (r.get("grade") or "") else "—"] = \
            cnt.get((r.get("grade") or "—").upper(), 0) + 1
    broken = sum(1 for r in rows if r.get("broken"))
    priced = sum(1 for r in rows if r.get("stop"))

    sheets = {
        "交易决策工作台": (HEAD, table),
        "A级精选": (HEAD, [t for t in table if t[4] == "A"]),
        "B级观察": (HEAD, [t for t in table if t[4] == "B"]),
    }
    # 机构明细：全市场 A/B 池（来自 _inst_holdings.json）
    pool_rows = []
    for v in (inst.get("pool") or []):
        pool_rows.append([
            v.get("code", ""), v.get("name", ""), v.get("grade", ""), v.get("label", ""),
            _fmt(v.get("score"), 1), _fmt(v.get("hold_ratio"), 2),
            _fmt(v.get("latest_chg"), 2), _fmt(v.get("streak"), 0),
            _fmt(v.get("net_chg"), 2), _fmt(v.get("n_funds"), 0),
            _fmt(v.get("holder_chg_pct"), 1), v.get("hold_trend", ""),
        ])
    sheets["机构明细AB池"] = ([
        "代码", "名称", "评级", "标签", "机构分", "持股比例%", "本期环比pp",
        "连续增持期", "净增持pp", "基金家数", "户数环比%", "持仓趋势"], pool_rows)

    stats = [
        ["报告期", inst.get("period", ""), "上期", inst.get("prev_period", "")],
        ["机构数据更新", inst.get("updated") or inst.get("asof") or "",
         "数据源", inst.get("source", "")],
        ["合并股票数", n, "其中含日K", priced],
        ["A级精选", cnt.get("A", 0), "B级观察", cnt.get("B", 0)],
        ["C级散户票", cnt.get("C", 0), "无季报数据", cnt.get("—", 0)],
        ["已破位(止损)", broken, "止损周期", stop.get("period", period)],
        ["止损口径", stop.get("stop_vote", ""), "市场状态", stop.get("marketState", "auto")],
        ["大纲", "选股池(三周期+ETF全行业扫描+ETFtop5+实仓)", "止损", "六理论投票取最保守+棘轮"],
    ]
    sheets["汇总统计"] = (["项目", "值", "项目", "值"], stats)

    notes = [
        ["三系统合并", "① 选股池 = usecase inst_holding 的 n_inst_pool（三大周期选股 + ETF全行业扫描 + ETF top5 + 实仓）"],
        ["", "② 趋势规律 = usecase_pipeline 三类趋势匹配 + MACD/SAR/OBV/MA20（与 APK TrendClassGate 同口径）"],
        ["", "③ 止损价 = stop_loss_vote 六理论投票取最保守 + 棘轮（只上移不下移）"],
        ["机构判定", "口径A 连续≥2期环比增持>0.5pp；口径B 全程净增持>1pp；A=口径A且score≥75｜B=(A或B)且score≥50｜C=其余"],
        ["", "算法单一事实源 = smalltools/_inst_holdings.py（东财 RPT_MAIN_ORGHOLD + RPT_HOLDERNUMLATEST）"],
        ["重要提醒", "机构季报天然滞后 1-3 月，只做中长线定性、不可作实时信号；机构加仓≠必涨，须与趋势规律 + 止损共振才入场"],
        ["生成时间", _dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S")],
        ["asof", asof],
    ]
    sheets["使用说明"] = (["项目", "说明"], notes)

    out = out or os.path.join(_DATA, "交易决策工作台_%s.xlsx" % asof)
    _write_xlsx(out, sheets)
    latest = os.path.join(_DATA, "交易决策工作台_latest.xlsx")
    try:
        _write_xlsx(latest, sheets)
    except Exception:
        latest = None
    print("《交易决策工作台》Excel 完成：asof=%s 共 %d 只（A %d / B %d / C %d，已破位 %d）\n  → %s%s"
          % (asof, n, cnt.get("A", 0), cnt.get("B", 0), cnt.get("C", 0), broken, out,
             ("\n  → %s" % latest) if latest else ""))
    return 0


def _write_xlsx(path, sheets):
    """sheets = {表名: (表头, 行列表)} → 全左对齐 + 文本写入（保留股票代码前导 0）。"""
    from openpyxl import Workbook
    from openpyxl.styles import Alignment, Font, PatternFill
    from openpyxl.utils import get_column_letter

    left = Alignment(horizontal="left", vertical="center")
    head_font = Font(bold=True, color="FFFFFF")
    head_fill = PatternFill("solid", fgColor="4472C4")

    wb = Workbook()
    wb.remove(wb.active)
    for name, (head, rows) in sheets.items():
        ws = wb.create_sheet(title=name[:31])
        widths = {}

        def put(r, values, font=None, fill=None):
            for i, v in enumerate(values):
                c = ws.cell(row=r, column=i + 1, value="" if v is None else str(v))
                c.alignment = left
                c.number_format = "@"      # 强制文本：左对齐 + 保留前导 0
                if font:
                    c.font = font
                if fill:
                    c.fill = fill
                widths[i] = max(widths.get(i, 8), sum(2 if ord(x) > 127 else 1 for x in str(v or "")))

        put(1, head, font=head_font, fill=head_fill)
        for i, row in enumerate(rows):
            put(i + 2, row)
        ws.freeze_panes = "A2"
        for i, w in widths.items():
            ws.column_dimensions[get_column_letter(i + 1)].width = min(46, max(9, w + 2))
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    wb.save(path)
    return path


def main():
    ap = argparse.ArgumentParser(description="《交易决策工作台》Excel（选股池+趋势规律+止损价三系统合并）")
    ap.add_argument("--asof", default=None, help="交易日 YYYY-MM-DD（默认缓存最新）")
    ap.add_argument("--period", default="MID",
                    help="止损周期 ULTRA_SHORT|SHORT|MID|LONG（默认 MID）")
    ap.add_argument("--grade", default="", help="只导出指定评级 A|B|C")
    ap.add_argument("--no-holdings", action="store_true", help="不含实仓")
    ap.add_argument("--out", default=None, help="输出 xlsx 路径")
    args = ap.parse_args()
    return run_workbench(asof=args.asof, period=args.period, grade=args.grade,
                         no_holdings=args.no_holdings, out=args.out)


if __name__ == "__main__":
    sys.exit(main())
