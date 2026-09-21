# -*- coding: utf-8 -*-
"""四个后台守护的统一控制层（2026-09-19 用户需求）。

## 为什么需要

原先只有 `daemon_start/stop/status.ps1`（PowerShell）能管守护 → exe（PyQt）与
codebuddy 想控制/监控就得各自 shell out 到 PS，容易出现三套实现漂移。
本模块把「启动/停止/状态/日志/发指令」收敛成**唯一实现**，三方共用：

    exe（AutoQuant 守护 Tab） ─┐
    codebuddy（stock-daemon skill）─┼→  python smalltools/_daemon_ctl.py <cmd>
    命令行手工排查              ─┘

## 守护清单（与 daemon_start.ps1 完全一致）

    publish         _publish_candidates.py --daemon     盘段推送（选股/情报/复盘）
    scan            _market_scan.py --daemon            盘中情报扫描（快讯/研报）
    holdings_flow   _holdings_flow_daemon.py --daemon   持仓/板块资金流监控
    bridge          _bridge_daemon.py                   手机桥（与 scan 强依赖）
    responder       _msg_responder.py --daemon          APK消息常驻应答（秒级ACK+AI分析）

## 用法

    python _daemon_ctl.py status              # 表格：名称/pid/状态/心跳/日志尾部
    python _daemon_ctl.py status --json       # 机器可读（exe、codebuddy 用）
    python _daemon_ctl.py start [name|all]    # 启动（默认 all）
    python _daemon_ctl.py stop [name|all]
    python _daemon_ctl.py restart [name|all]
    python _daemon_ctl.py logs <name> [n]     # 日志尾部 n 行（默认 30）
    python _daemon_ctl.py send <name> <text>  # 写指令到 data/_inbox/（守护轮询消费）

## 状态字段

    running   进程存活（pid 文件 + 存活探测）
    stale     进程在但**心跳超时**（日志 stale_min 分钟未更新）→ 疑假死，建议 restart
    stopped   无 pid 文件或进程已退出
"""
import argparse
import datetime as dt
import json
import os
import signal
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DATA = os.path.join(ROOT, "data")
RECORDS = os.path.join(HERE, "_records")
STATE = os.path.join(RECORDS, "_daemons.json")
STALE_MIN = 20          # 心跳超时（分钟）
TAIL_LINES = 30

DAEMONS = [
    {"name": "publish", "script": "_publish_candidates.py", "args": ["--daemon"],
     "desc": "盘段推送（选股/情报/复盘）"},
    {"name": "scan", "script": "_market_scan.py", "args": ["--daemon"],
     "desc": "盘中情报扫描（快讯/研报/亚太）"},
    {"name": "holdings_flow", "script": "_holdings_flow_daemon.py", "args": ["--daemon"],
     "desc": "持仓+板块资金流监控"},
    {"name": "bridge", "script": "_bridge_daemon.py", "args": [],
     "desc": "手机桥（与 scan 强依赖）"},
    {"name": "responder", "script": "_msg_responder.py", "args": ["--daemon"],
     "desc": "APK消息常驻应答（秒级ACK+AI分析）"},
]
BY_NAME = {d["name"]: d for d in DAEMONS}
ALIAS = {"holdings": "holdings_flow", "holding_flow": "holdings_flow"}


def _pid_file(tag):
    return os.path.join(HERE, "_daemon_%s.pid" % tag)


def _log_file(tag):
    return os.path.join(HERE, "_daemon_%s.log" % tag)


def _read_pid(tag):
    try:
        with open(_pid_file(tag), encoding="utf-8") as f:
            return int((f.read() or "").strip() or 0)
    except Exception:  # noqa: BLE001
        return 0


def _alive(pid):
    """跨平台进程存活探测（不依赖 psutil）。"""
    if not pid or pid <= 0:
        return False
    if os.name == "nt":
        try:
            out = subprocess.run(["tasklist", "/FI", "PID eq %d" % pid],
                                 capture_output=True, text=True, timeout=8)
            return str(pid) in (out.stdout or "")
        except Exception:  # noqa: BLE001
            return False
    try:
        os.kill(pid, 0)
        return True
    except Exception:  # noqa: BLE001
        return False


def _tail(path, n=TAIL_LINES):
    """日志尾部 n 行（大文件只读末 64KB，避免慢）。"""
    if not os.path.exists(path):
        return []
    try:
        size = os.path.getsize(path)
        with open(path, "rb") as f:
            if size > 65536:
                f.seek(size - 65536)
                f.readline()
            raw = f.read().decode("utf-8", errors="replace")
        lines = [x for x in raw.splitlines() if x.strip()]
        return lines[-n:]
    except Exception:  # noqa: BLE001
        return []


def stale_limit():
    """心跳超时阈值 —— **按交易时段动态**（2026-09-19：原先固定 20 分钟会误报）。

    守护在非交易时段本就该安静（publish 盘后不推、scan 等到下一盘段），
    用固定 20 分钟会把正常休眠误判成假死（实测周六 19:58 把 publish/holdings_flow
    都标成 stale）。故：
      · 交易日盘中/尾盘（09:00-15:20）→ 20 分钟（真卡死要能立刻发现）
      · 交易日盘后                   → 3 小时
      · 非交易日（周末/节假日）       → 4 小时
    """
    try:
        import _trade_calendar as _tc
        if not _tc.is_trading_day():
            return 240.0
        now = dt.datetime.now()
        hm = now.hour * 60 + now.minute
        if (9 * 60) <= hm <= (15 * 60 + 20):
            return STALE_MIN
        return 180.0
    except Exception:  # noqa: BLE001
        return STALE_MIN


def status_of(d, stale_min=None):
    tag = d["tag"] if "tag" in d else d["name"]
    pid = _read_pid(tag)
    log = _log_file(tag)
    alive = _alive(pid)
    mt = os.path.getmtime(log) if os.path.exists(log) else 0
    age_min = (time.time() - mt) / 60.0 if mt else None
    if not alive:
        state = "stopped"
    elif age_min is not None and age_min > stale_min:
        state = "stale"
    else:
        state = "running"
    return {
        "name": d["name"], "desc": d["desc"], "pid": pid, "state": state,
        "alive": alive,
        "log": log, "log_age_min": round(age_min, 1) if age_min is not None else None,
        "log_mtime": (dt.datetime.fromtimestamp(mt).strftime("%Y-%m-%d %H:%M:%S")
                      if mt else ""),
        "tail": _tail(log, 3),
    }


def status_all(stale_min=None):
    lim = stale_min or stale_limit()
    return [status_of(d, lim) for d in DAEMONS]


def start_one(d):
    tag = d["name"]
    if _alive(_read_pid(tag)):
        return {"name": tag, "ok": True, "msg": "已在运行"}
    script = os.path.join(HERE, d["script"])
    if not os.path.exists(script):
        return {"name": tag, "ok": False, "msg": "脚本不存在: %s" % script}
    log = _log_file(tag)
    try:
        with open(log, "ab") as f:
            f.write(("\n=== %s 启动 %s ===\n" % (
                tag, dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S"))).encode("utf-8"))
            # 2026-09-20：COS 域名在本机 TLS 层**时好时坏**（证书链被安全软件/网络设备
            # 替换，表现为 `SSL CERTIFICATE_VERIFY_FAILED: Hostname mismatch`）——
            # 实测同一份代码不同时段结果不同，与业务代码无关。
            # 守护统一注入 COS_INSECURE_TLS=1，让 cos_utils 跳过证书校验；
            # 否则 bridge / relay / 推送会间歇性断（典型现象：图片能发、文字发不出）。
            # 外部若已显式设置该变量则以外部为准（便于临时恢复严格校验）。
            _env = {**os.environ}
            _env.setdefault("COS_INSECURE_TLS", "1")
            p = subprocess.Popen([sys.executable, "-u", script] + list(d["args"]),
                                 cwd=HERE, stdout=f, stderr=subprocess.STDOUT,
                                 env=_env,
                                 creationflags=(subprocess.CREATE_NEW_PROCESS_GROUP
                                                if os.name == "nt" else 0))
        with open(_pid_file(tag), "w", encoding="utf-8") as f:
            f.write(str(p.pid))
        return {"name": tag, "ok": True, "pid": p.pid, "msg": "已启动"}
    except Exception as e:  # noqa: BLE001
        return {"name": tag, "ok": False, "msg": "启动失败: %s" % e}


def stop_one(d):
    tag = d["name"]
    pid = _read_pid(tag)
    if not pid or not _alive(pid):
        try:
            os.remove(_pid_file(tag))
        except OSError:
            pass
        return {"name": tag, "ok": True, "msg": "未在运行"}
    try:
        if os.name == "nt":
            subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"],
                           capture_output=True, timeout=15)
        else:
            os.kill(pid, signal.SIGTERM)
    except Exception as e:  # noqa: BLE001
        return {"name": tag, "ok": False, "msg": "停止失败: %s" % e}
    for _ in range(10):
        if not _alive(pid):
            break
        time.sleep(0.5)
    try:
        os.remove(_pid_file(tag))
    except OSError:
        pass
    return {"name": tag, "ok": True, "msg": "已停止"}


def resolve(name):
    """名称 → 守护列表（all/空 = 全部）。"""
    if not name or name == "all":
        return DAEMONS
    n = ALIAS.get(name, name)
    d = BY_NAME.get(n)
    return [d] if d else []


def send_cmd(name, text):
    """写指令文件到 data/_inbox/（守护可轮询消费；也兼容 _daemon_<name>.cmd）。"""
    n = ALIAS.get(name, name)
    if n not in BY_NAME:
        return {"ok": False, "msg": "未知守护: %s" % name}
    os.makedirs(DATA, exist_ok=True)
    p = os.path.join(DATA, "_inbox", "daemon_%s_%s.cmd"
                     % (n, dt.datetime.now().strftime("%Y%m%d_%H%M%S")))
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w", encoding="utf-8") as f:
        f.write(json.dumps({"to": n, "cmd": text,
                            "at": dt.datetime.now().isoformat()}, ensure_ascii=False))
    return {"ok": True, "path": p, "msg": "指令已入队"}


def print_status(rows, as_json=False):
    if as_json:
        print(json.dumps({"ts": dt.datetime.now().isoformat(), "daemons": rows},
                         ensure_ascii=False, indent=1))
        return
    icon = {"running": "🟢", "stale": "🟡", "stopped": "🔴"}
    print("==== 后台守护状态（%s）====" % dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S"))
    for r in rows:
        print("%s %-14s pid=%-7s %-8s 日志心跳：%s（%s 分钟前）  %s"
              % (icon.get(r["state"], "?"), r["name"],
                 r["pid"] or "-", r["state"],
                 r["log_mtime"] or "无",
                 r["log_age_min"] if r["log_age_min"] is not None else "-",
                 r["desc"]))
        for t in r["tail"][-1:]:
            print("      └ %s" % t[:120])


def main():
    ap = argparse.ArgumentParser(description="StockAnalysis 后台守护统一控制层")
    ap.add_argument("cmd", choices=["status", "start", "stop", "restart",
                                    "logs", "send", "list"])
    ap.add_argument("target", nargs="?", default="all")
    ap.add_argument("extra", nargs="?", default="")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--stale-min", type=float, default=None,
                    help="心跳超时分钟数；不填则按交易时段自动（盘中20/盘后180/非交易日240）")
    a = ap.parse_args()
    if a.cmd == "list":
        for d in DAEMONS:
            print("%-14s %-30s %s" % (d["name"], d["script"], d["desc"]))
        return
    if a.cmd == "status":
        print_status(status_all(a.stale_min), as_json=a.json)
        return
    if a.cmd == "logs":
        n = int(a.extra or TAIL_LINES)
        for d in resolve(a.target):
            print("==== %s（%s）====" % (d["name"], _log_file(d["name"])))
            for ln in _tail(_log_file(d["name"]), n):
                print(ln)
        return
    if a.cmd == "send":
        r = send_cmd(a.target, a.extra)
        print(json.dumps(r, ensure_ascii=False) if a.json else r["msg"])
        return
    # start / stop / restart
    out = []
    for d in resolve(a.target):
        if a.cmd in ("stop", "restart"):
            out.append(stop_one(d))
        if a.cmd in ("start", "restart"):
            out.append(start_one(d))
    if a.json:
        print(json.dumps(out, ensure_ascii=False, indent=1))
    else:
        for r in out:
            print("[%s] %s%s" % (r["name"], r["msg"],
                                 " pid=%s" % r.get("pid") if r.get("pid") else ""))
        print_status(status_all(a.stale_min))


if __name__ == "__main__":
    main()
