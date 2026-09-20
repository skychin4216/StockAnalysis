# -*- coding: utf-8 -*-
"""bridge 守护（2026-09-19）：把「本机 API」+「COS 中继轮询」合成**一个**守护进程。

背景：APK 走 COS 中继提交任务（如 push.round），链路需要两个常驻进程：
    data_service.py  本机 HTTP API（127.0.0.1:8888，仅回环，不暴露局域网）
    relay_worker.py  轮询 COS stockanalysis/bridge/pc/inbox → 调本机 API → 写应答
两者是强依赖（relay 没有本机 API 就空转），却要分别启动/分别看日志，容易漏启
（本次 APK 推送失败的真正原因就是没人启动它们）。故合并为一个守护：

    python _bridge_daemon.py            # 常驻：拉起两个子进程并监管（崩溃自动重启）

子进程 stdout/stderr 继承本进程（由 daemon_start.ps1 重定向到 _daemon_bridge.log）。
停止时 daemon_stop.ps1 用 Stop-Tree 杀进程树，子进程一起退出。
"""
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
AUTO = os.path.join(ROOT, "AutoQuant")
PY = sys.executable

CHILDREN = (
    ("datasvc", ["-u", "-m", "autoquant.data_service"]),
    ("relay", ["-u", "-m", "autoquant.relay_worker"]),
)
CHECK_INTERVAL = 10


def _spawn(tag, args):
    p = subprocess.Popen([PY] + args, cwd=AUTO,
                         env={**os.environ, "PYTHONIOENCODING": "utf-8"})
    print("[bridge] 子进程 %s 已启动 pid=%d" % (tag, p.pid), flush=True)
    return p


def main():
    if not os.path.isdir(AUTO):
        print("[bridge] 找不到 AutoQuant 目录：%s" % AUTO, flush=True)
        return 2
    procs = {tag: _spawn(tag, args) for tag, args in CHILDREN}
    print("[bridge] 监管中（%s 秒一轮，子进程崩溃自动拉起）" % CHECK_INTERVAL, flush=True)
    while True:
        time.sleep(CHECK_INTERVAL)
        for tag, args in CHILDREN:
            p = procs.get(tag)
            if p is None or p.poll() is not None:
                code = p.poll() if p else None
                print("[bridge] 子进程 %s 退出(code=%s)，重新拉起" % (tag, code), flush=True)
                procs[tag] = _spawn(tag, args)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("[bridge] 已停止", flush=True)
