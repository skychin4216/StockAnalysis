# -*- coding: utf-8 -*-
"""每周参数保鲜：增量回溯 → 导出参数 → 上传 COS → 推送简报（2026-09-20）。

## 为什么单独成一个脚本（而不是塞进守护）

守护（`_publish_candidates.py --daemon`）管的是**盘中节奏**（10~15 分钟一轮推送），
而回溯拟合是**离线批量**（哪怕增量也可能跑几分钟~几十分钟）。
混在一起的风险是：**回溯卡住 → 盘中推送被拖死**。故拆开，用计划任务在收盘后跑。

## 为什么每周跑也很快（关键）

`_walk_forward.py` **天然增量**：`_records/selected_YYYY-MM.json` 已存在就跳过该窗口。
所以第一次全量（216 窗口，数小时）之后，每周只补**新增的 1 个窗口** → **几分钟**。

## 四步流水线

    1. `_walk_forward.py`（WF_START=2008-08-15，增量）→ _records/selected_*.json
    2. `_export_params.py --fit-cache`            → assets/backtest_params.json
    3. `cloud_upload_params.py`                   → COS params_key（供 APK 参数回流下载）
    4. `push_channel` 简报                        → 微信群（成功/失败如实报告）

## 用法

    python _weekly_refit.py --dry            # 只打印将要执行什么（安全）
    python _weekly_refit.py                  # 真跑一轮完整流水线
    python _weekly_refit.py --months 3       # 只回溯最近 3 个窗口（快速验证）
    python _weekly_refit.py --install-task   # 注册 Windows 计划任务（每周一 16:00）
    python _weekly_refit.py --remove-task    # 注销计划任务
    python _weekly_refit.py --status         # 看计划任务是否已注册

⚠️ 需要 `TRADE_LIVE` 之类无关；本脚本只做数据与参数，不涉及交易。
"""
import argparse
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

TASK_NAME = "StockAnalysis_WeeklyRefit"
WF_START = "2008-08-15"      # 与 2026-09-19 那次全量回溯保持同一窗口起点
PY = sys.executable


def _run(tag, args, env_extra=None, timeout=7200):
    """跑一步，返回 (ok, 输出尾部)。"""
    env = {**os.environ, **(env_extra or {})}
    print("\n=== [%s] %s ===" % (tag, " ".join(args)))
    t0 = time.time()
    try:
        r = subprocess.run(args, cwd=HERE, env=env, capture_output=True,
                           timeout=timeout, text=True, encoding="utf-8", errors="replace")
    except subprocess.TimeoutExpired:
        print("  ⏱ 超时（%ds）" % timeout)
        return False, "超时"
    out = ((r.stdout or "") + (r.stderr or "")).strip()
    print("  rc=%s 耗时 %.0fs" % (r.returncode, time.time() - t0))
    for line in out.splitlines()[-6:]:
        print("  |", line[:150])
    return r.returncode == 0, out


def _notify(title, text):
    try:
        import push_channel as PC  # noqa: PLC0415
        cfg = PC.load_notify_cfg()
        ok = PC.push(title, text, cfg, kind="notice")
        print("推送:", "✅" if ok else "❌（已落盘排队）")
        return ok
    except Exception as e:  # noqa: BLE001
        print("推送异常:", type(e).__name__, e)
        return False


def refit(months=0, dry=False, notify=True):
    steps = []
    wf = [PY, "-u", os.path.join(HERE, "_walk_forward.py")]
    if months:
        wf += ["--months", str(months)]
    steps.append(("① 增量回溯（walk-forward）", wf, {"WF_START": WF_START}))
    steps.append(("② 导出参数 → backtest_params.json",
                  [PY, os.path.join(HERE, "_export_params.py"), "--fit-cache"], None))
    steps.append(("③ 上传 COS（供 APK 回流下载）",
                  [PY, os.path.join(HERE, "cloud_upload_params.py")], None))

    if dry:
        print("【dry-run】将依次执行：")
        for tag, args, env in steps:
            print("  %s\n     %s   env=%s" % (tag, " ".join(args), env or {}))
        return True

    t0 = time.time()
    results = []
    for tag, args, env in steps:
        ok, out = _run(tag, args, env)
        results.append((tag, ok, out))
        if not ok:
            print("⚠️ 步骤失败，后续步骤仍会继续（导出可能用旧记录）")

    el = time.time() - t0
    lines = ["📈 参数保鲜（每周回溯拟合）", "耗时 %.0f 分 %.0f 秒" % (el // 60, el % 60), ""]
    for tag, ok, out in results:
        lines.append("%s %s" % ("✅" if ok else "❌", tag))
        tail = [x for x in (out or "").splitlines() if x.strip()][-2:]
        for t in tail:
            lines.append("    " + t[:120])
    text = "\n".join(lines)
    print("\n" + text)
    if notify:
        _notify("📈 参数保鲜完成（回溯→导出→上传）", text)
    return all(ok for _, ok, _ in results)


def _task_cmd(action):
    if action == "install":
        # 每周一 16:00（收盘后，避开盘中推送）
        cmd = [PY, os.path.join(HERE, "_weekly_refit.py")]
        return ["schtasks", "/Create", "/TN", TASK_NAME, "/SC", "WEEKLY",
                "/D", "MON", "/ST", "16:00", "/TR", '"%s"' % " ".join('"%s"' % c for c in cmd),
                "/F"]
    if action == "remove":
        return ["schtasks", "/Delete", "/TN", TASK_NAME, "/F"]
    return ["schtasks", "/Query", "/TN", TASK_NAME]


def main():
    ap = argparse.ArgumentParser(description="每周参数保鲜（增量回溯→导出→上传→推送）")
    ap.add_argument("--dry", action="store_true", help="只打印将执行的步骤")
    ap.add_argument("--months", type=int, default=0, help="只回溯最近 N 个窗口（0=增量全量）")
    ap.add_argument("--no-notify", action="store_true", help="不推送微信")
    ap.add_argument("--install-task", action="store_true")
    ap.add_argument("--remove-task", action="store_true")
    ap.add_argument("--status", action="store_true")
    a = ap.parse_args()

    if a.install_task or a.remove_task or a.status:
        action = "install" if a.install_task else ("remove" if a.remove_task else "status")
        cmd = _task_cmd(action)
        print("执行:", " ".join(cmd))
        if action == "status":
            r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
            print(r.stdout or r.stderr)
            return r.returncode
        r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
        print(r.stdout or "", r.stderr or "")
        if r.returncode == 0 and action == "install":
            print("✅ 已注册：每周一 16:00 自动跑「回溯→导出→上传→推送」")
            print("   查看: python _weekly_refit.py --status ｜ 注销: --remove-task")
        return r.returncode

    return 0 if refit(months=a.months, dry=a.dry, notify=not a.no_notify) else 1


if __name__ == "__main__":
    sys.exit(main())
