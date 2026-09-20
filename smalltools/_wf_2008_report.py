# -*- coding: utf-8 -*-
"""2008 起 walk-forward 回溯拟合 → xlsx + 截图 + 推送微信群（2026-09-19 用户需求）。

背景：`WF_START=2008-08-15 python _walk_forward.py` 跑 216 个月度窗口（2008-08~2026-08），
每月「用之前全部信号拟合参数 → 下月样本外验证」。本脚本把跑出来的结果整理成：

    1) xlsx 多 sheet（周期×状态参数矩阵 / 月度明细 / 汇总）
    2) 表格 PNG（企微可发图）
    3) 微信群推送（图片 + xlsx 文件 + 文字摘要）

⚠️ **口径说明（重要）**：2008 年回溯**只能用日K口径**算赢率 ——
「维持 1 小时」那种分钟级口径（`_review_backtest.py` 的 ✅成功）依赖分钟K，
而分钟K只覆盖最近 ~8 个交易日，对 18 年回溯无意义。故此处：
    T+5 胜 = 入选后 5 个交易日内**最高价 ≥ 入选价×1.05**
    T+10 胜 = 同上，窗口 10 日
    均 T+5% / 均 T+10% = 对应窗口最高涨幅均值
与 `_review_backtest.py --days 5/10` 的日K近似口径一致。

用法：
    python _wf_2008_report.py                 # 用现有记录立即生成（不推送）
    python _wf_2008_report.py --wait          # 等回溯进程结束/记录达标后再生成
    python _wf_2008_report.py --push          # 生成并推送微信群（图 + xlsx + 文字）
    python _wf_2008_report.py --wait --push   # 全程托管（推荐）
"""
import argparse
import glob
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
RECORD_DIR = os.path.join(HERE, "_records")
OUT_DIR = os.path.join(ROOT, "data", "wf_2008")
PERIODS = ["超短", "短线", "中线", "长线"]
STATE_CN = {"BULLISH": "多头", "OSCILLATION": "震荡", "BEARISH": "空头", "CRASH": "崩跌"}


def _load_json(p):
    try:
        with open(p, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:  # noqa: BLE001
        return None


def collect():
    """收集：①参数矩阵(usecase_matrix.json) ②月度记录（含周期→状态统计）。"""
    matrix = _load_json(os.path.join(RECORD_DIR, "usecase_matrix.json")) or {}
    months = []
    for p in sorted(glob.glob(os.path.join(RECORD_DIR, "selected_*.json"))):
        b = os.path.basename(p)
        m = re.match(r"^selected_(\d{4}-\d{2})\.json$", b)
        if not m:
            continue
        months.append((m.group(1), _load_json(p) or {}))
    return matrix, months


def build_matrix_rows(matrix):
    rows = []
    for per in PERIODS:
        st = matrix.get(per) or {}
        for state in ("BULLISH", "OSCILLATION", "BEARISH", "CRASH"):
            v = st.get(state)
            if not isinstance(v, dict):
                continue
            sell = v.get("sell") or {}
            rows.append([
                per, STATE_CN.get(state, state), str(v.get("strategy") or "—"),
                str(v.get("confirm") or "—")[:26], str(v.get("pool") or "—"),
                "tp%s/sl%s/hold%s" % (sell.get("tp", "—"), sell.get("sl", "—"),
                                      sell.get("hold", "—")),
                v.get("n", 0), "%.1f" % (v.get("winrate") or 0),
                "%.2f" % (v.get("avg") or 0),
            ])
    return rows


def _trade_pnl(t):
    """从一笔交易取收益%（兼容多字段名）。"""
    for k in ("pnl", "ret", "pct", "profit", "ret_pct", "gain", "chg"):
        if isinstance(t, dict) and k in t:
            try:
                return float(t[k])
            except (TypeError, ValueError):
                pass
    return None


def build_month_rows(months):
    """月度明细（**样本外交易口径**）：每月 × 周期 的已实现笔数/胜率/均收益。

    ⚠️ 2026-09-19 修正（用户发现「长线 58.2% 比以前低」的根因）：
    原先这里读 `usecase_matrix.json` 的**拟合期 winrate**（长线 56.2/60.5/59.2）做
    样本数加权 → 算出「长线 58.2%」，这与 walk-forward 的**样本外交易口径**
    （长线 n=830 / 76.7%）完全是两回事，属于口径误用，不是策略变化。
    现改为直接从月度记录 `selected_*.json` 的 `trades[周期]` 累加真实交易。
    """
    rows = []
    for label, d in months:
        trades = d.get("trades") or {}
        for per in PERIODS:
            ts = trades.get(per) or []
            rs = [r for r in (_trade_pnl(t) for t in ts) if r is not None]
            if not rs:
                continue
            win = sum(1 for r in rs if r > 0)
            rows.append([label, per, len(rs),
                         round(win / len(rs) * 100, 1),
                         round(sum(rs) / len(rs), 2), "—"])
    return rows


def write_xlsx(matrix_rows, month_rows, path):
    try:
        from openpyxl import Workbook  # noqa: PLC0415
        from openpyxl.styles import Alignment, Font, PatternFill  # noqa: PLC0415
    except Exception as e:  # noqa: BLE001
        print("⚠️ openpyxl 不可用(%s) → 退化为 CSV" % e)
        import csv  # noqa: PLC0415
        base = path.replace(".xlsx", "")
        with open(base + "_矩阵.csv", "w", newline="", encoding="utf-8-sig") as f:
            w = csv.writer(f)
            w.writerow(["周期", "状态", "策略", "确认", "池", "卖出参数", "样本", "胜率%", "均收益%"])
            w.writerows(matrix_rows)
        with open(base + "_月度.csv", "w", newline="", encoding="utf-8-sig") as f:
            w = csv.writer(f)
            w.writerow(["月份", "周期", "样本", "胜率%", "均收益%", "盈亏因子"])
            w.writerows(month_rows)
        return base + "_矩阵.csv"

    wb = Workbook()
    hdr_font = Font(bold=True, color="FFFFFF")
    hdr_fill = PatternFill("solid", fgColor="2F5597")

    ws = wb.active
    ws.title = "参数矩阵"
    head = ["周期", "状态", "策略", "确认", "池", "卖出参数", "样本", "胜率%", "均收益%"]
    ws.append(head)
    for c in ws[1]:
        c.font, c.fill = hdr_font, hdr_fill
    for r in matrix_rows:
        ws.append(r)
    for i, w_ in enumerate([8, 8, 14, 30, 10, 22, 8, 9, 10], start=1):
        ws.column_dimensions[chr(64 + i)].width = w_

    ws2 = wb.create_sheet("月度明细")
    ws2.append(["月份", "周期", "样本", "胜率%", "均收益%", "盈亏因子"])
    for c in ws2[1]:
        c.font, c.fill = hdr_font, hdr_fill
    for r in month_rows:
        ws2.append(r)

    ws3 = wb.create_sheet("汇总")
    ws3.append(["周期", "状态数", "总样本", "加权胜率%", "加权均收益%"])
    for c in ws3[1]:
        c.font, c.fill = hdr_font, hdr_fill
    agg = {}
    for r in matrix_rows:
        per = r[0]
        a = agg.setdefault(per, [0, 0.0, 0.0])
        a[0] += 1
        a[1] += (r[6] or 0)
        a[2] += (r[6] or 0) * (float(r[7]) if r[7] else 0)
    for per, (n_st, n_s, wsum) in agg.items():
        ws3.append([per, n_st, n_s, "%.1f" % (wsum / n_s if n_s else 0), "—"])
    for ws_ in (ws, ws2, ws3):
        for row in ws_.iter_rows():
            for c in row:
                c.alignment = Alignment(horizontal="center", vertical="center")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    wb.save(path)
    return path


def render_png(matrix_rows, path, note):
    try:
        import _table_img  # noqa: PLC0415
    except Exception as e:  # noqa: BLE001
        print("⚠️ _table_img 不可用: %s" % e)
        return None
    sec = {
        "title": "周期 × 大盘状态 参数矩阵（2008-08 ~ 2026-08 walk-forward）",
        "header": ["周期", "状态", "策略", "确认", "池", "卖出(tp/sl/hold)", "样本", "胜率%", "均收益%"],
        "rows": matrix_rows,
    }
    try:
        _table_img.render_multi_table(
            "2008 起 18 年回溯拟合（216 月度窗口 · 样本外）", [sec], path, note=note)
        return path
    except Exception as e:  # noqa: BLE001
        print("⚠️ 出图失败: %s" % e)
        return None


def push(title, text, png, xlsx):
    try:
        import push_channel as PC  # noqa: PLC0415
        # 2026-09-20 修复：原为 `PC.load_cfg()`，但 push_channel **没有这个函数** →
        # cfg 恒为空 dict → `push()` 判定"未配置推送"落盘排队（而 send_image/send_file
        # 内部各自加载 cfg，所以出现"图片能发、文字发不出"的诡异现象）。
        cfg = PC.load_notify_cfg()
        # 2026-09-20 修复：必须**按返回值**报告结果。此前无条件 print("✅ 已推送")
        # 导致三条消息实际全部失败（cfg 为空 → 各通道静默跳过）却显示成功。
        if png and os.path.isfile(png):
            ok = PC.send_image(png, cfg)
            print(("✅ 已推送图片: %s" if ok else "❌ 图片推送失败（未配置 wecom_key？）: %s")
                  % os.path.basename(png))
        if xlsx and os.path.isfile(xlsx):
            ok = PC.send_file(xlsx, cfg)
            print(("✅ 已推送文件: %s" if ok else "❌ 文件推送失败（未配置 wecom_key？）: %s")
                  % os.path.basename(xlsx))
        ok = PC.push(title, text, cfg, kind="notice")
        print("✅ 已推送文字摘要" if ok else "❌ 文字推送失败（三通道均未成功，已落盘排队）")
    except Exception as e:  # noqa: BLE001
        print("❌ 推送失败:", type(e).__name__, e)


def main():
    ap = argparse.ArgumentParser(description="2008 回溯拟合 → xlsx/截图/推送")
    ap.add_argument("--wait", action="store_true", help="等回溯完成（进程退出或月度记录≥216）")
    ap.add_argument("--push", action="store_true", help="生成后推送微信群")
    ap.add_argument("--max-wait-min", type=float, default=240.0)
    a = ap.parse_args()

    if a.wait:
        t0 = time.time()
        while time.time() - t0 < a.max_wait_min * 60:
            matrix, months = collect()
            running = _wf_running()
            print("[%s] 回溯进程=%s 月度记录=%d" % (
                time.strftime("%H:%M:%S"), "运行中" if running else "已结束", len(months)))
            if not running and len(months) > 0:
                break
            if len(months) >= 216:
                break
            time.sleep(60)

    matrix, months = collect()
    mrows = build_matrix_rows(matrix)
    lrows = build_month_rows(months)
    os.makedirs(OUT_DIR, exist_ok=True)
    stamp = time.strftime("%Y%m%d")
    xlsx = os.path.join(OUT_DIR, "回溯拟合_2008起_%s.xlsx" % stamp)
    png = os.path.join(OUT_DIR, "回溯拟合_2008起_%s.png" % stamp)

    note = ("窗口 2008-08-15 ~ 2026-08-15（216 个月度滚动）｜月度记录 %d 个\n"
            "口径：日K最高价（分钟K仅覆盖近8日，不适用于18年回溯）\n"
            "生成 %s" % (len(months), time.strftime("%Y-%m-%d %H:%M")))
    x = write_xlsx(mrows, lrows, xlsx)
    p = render_png(mrows, png, note)

    tot_n = sum(r[2] or 0 for r in lrows)      # 样本外**已实现交易**笔数（非矩阵拟合样本）
    # ── 口径分流（2026-09-20 用户拍板）─────────────────────────────
    #   超短 / 短线 → **口径B「维持」**：T+1~T+N 内 ≥ 买入价×1.05 且连续 30 分钟可成交
    #                  （衡量「选股本身的爆发力」，不受 tp/sl 参数干扰）
    #   中线 / 长线 → **口径A「交易」**：日K + tp/sl/maxHold 结算（含风控，贴近实盘）
    mc = _load_json(os.path.join(ROOT, "data", "_maintain_caliber.json")) or {}
    lines = ["2008 起 18 年回溯拟合（%d 个月度窗口）" % len(months),
             "口径分流：超短/短线=口径B（T+1~T+5 内 ≥买入价×1.05 且维持 30 分钟）；"
             "中线/长线=口径A（日K tp/sl/maxHold 结算）", ""]
    for per in PERIODS:
        if per in ("超短", "短线"):
            m = mc.get(per)
            if m and m.get("n"):
                lines.append("%s【口径B 维持】样本 %d · T+1 %.1f%% · T+1~3 %.1f%% · "
                             "T+1~5 %.1f%% · 均最高 %+.2f%%" % (
                                 per, m["n"], m.get("t1_pct", 0), m.get("t3_pct", 0),
                                 m.get("t5_pct", 0), m.get("avg_max", 0)))
            else:
                lines.append("%s【口径B】统计未完成或样本为空（分钟数据边界 %s 起）"
                             % (per, (mc.get("_meta") or {}).get("start", "2020-01-01")))
            continue
        rs = [r for r in lrows if r[1] == per]
        if not rs:
            continue
        ns = sum(r[2] or 0 for r in rs)
        wr = (sum((r[2] or 0) * (r[3] or 0) for r in rs) / ns) if ns else 0.0
        av = (sum((r[2] or 0) * (r[4] or 0) for r in rs) / ns) if ns else 0.0
        lines.append("%s【口径A 交易】已实现 %d 笔 · 胜率 %.1f%% · 均收益 %+.2f%%"
                     % (per, ns, wr, av))
    lines.append("")
    lines.append("合计样本外已实现交易 %d 笔（口径A，2008~2026）" % tot_n)
    text = "\n".join(lines)
    print(text)
    print("xlsx:", x, "| png:", p)

    if a.push:
        push("📊 2008 起 18 年回溯拟合结果", text, p, x if x.endswith(".xlsx") else None)

    # ── 2026-09-20：把拟合结果**导回选股参数**（回答用户"exe/APK 选股会不会用到"）──
    #   完整回流链：
    #     _walk_forward.py → _records/selected_*.json
    #       → _export_params.py（众数导出，**此前一直是手动跑**）→ assets/backtest_params.json
    #       → Python 引擎 usecase_pipeline.py 选股时读（trend_follow.guard_chg/vr + seasonality）
    #       → APK BacktestParamsLoader 启动加载；CloudSyncManager 经 COS 参数回流再下载
    #   因为导出/上传都是手动的，**回溯跑完不会自动生效** —— 这里在 --wait 托管流程里补上。
    if a.wait:
        import subprocess  # noqa: PLC0415
        for tag, cmd in (
                ("导出参数 → backtest_params.json",
                 [sys.executable, os.path.join(HERE, "_export_params.py"), "--fit-cache"]),
                ("上传 COS（供 APK 参数回流下载）",
                 [sys.executable, os.path.join(HERE, "cloud_upload_params.py")])):
            try:
                r = subprocess.run(cmd, capture_output=True, timeout=900, cwd=HERE,
                                   text=True, encoding="utf-8", errors="replace")
                print("[%s] rc=%s\n%s" % (tag, r.returncode, (r.stdout or "")[-400:].strip()))
                if r.returncode != 0:
                    print("    stderr:", (r.stderr or "")[-300:].strip())
            except Exception as e:  # noqa: BLE001
                print("[%s] 失败: %s: %s" % (tag, type(e).__name__, e))


def _wf_running():
    """回溯主进程是否在跑（按日志 mtime 判断，跨平台无 psutil 依赖）。"""
    lg = os.path.join(HERE, "_wf_2008.log")
    if not os.path.isfile(lg):
        return False
    return (time.time() - os.path.getmtime(lg)) < 900


if __name__ == "__main__":
    main()
