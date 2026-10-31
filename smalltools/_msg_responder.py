#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""APK ↔ PC「登录即聊」常驻应答器（无 GUI 版，2026-09-22）。

## 为什么要有这个脚本

自动应答能力原本只写在 GUI 面板里
（`AutoQuant/autoquant/gui/msg_consumer_tab.py` 的 `MsgConsumerWorker`），
必须 exe 开着、且手动点「启动」才工作 —— **GUI 一关，APK 发来的消息就一直
pending**，用户要等到有人（AI）在会话里主动去查才收到回复（实测 2 分钟级）。

本脚本把那个 worker 抽成**独立常驻守护**，接入 `_daemon_ctl` 统一管理：

- **秒级 ACK**：收到立刻回「⏳ 已收到…正在分析」，先消除"对方收到没"的等待焦虑
- **真 AI 分析**：走 `agent_loop.run_agent_plan`（Agent 模式，LLM 自行决定调哪些工具）
- **结论/过程分流**：conclusion → APK「对话」Tab；steps → 「日志」Tab
  （与 2026-09-21 定下的「结论 vs 过程」口径一致）
- **单实例锁**：`_msg_responder.lock` 记 PID，进程活着就直接退出，
  防止两个实例各自消费同一条指令（**同一条"每日选股"被跑两遍**）

## 用法

    python _msg_responder.py --daemon                  # 常驻（由 _daemon_ctl 调用）
    python _msg_responder.py --once                    # 处理完当前积压即退出
    python _msg_responder.py --daemon --poll 1.5 --max-turns 10

## 关于 ACK 的实现细节

`DataService.reply_msg` 会把 inbox 消息标记为 `replied` 并往 outbox 追加一条。
**它不检查当前状态**，所以可以被调用两次 —— 正好用来实现「先 ACK、再结论」：
第二次调用同样能找到该消息，再追加一条 outbox 记录。APK 侧因此会看到两条气泡：
`⏳ 已收到…` 然后 `💬 结论…`。
"""
from __future__ import annotations

import argparse
import atexit
import os
import sys
import time
import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
LOCK = os.path.join(HERE, "_msg_responder.lock")

# AutoQuant/autoquant 里有 data_service / agent_loop / ai_config
_AQ = os.path.join(os.path.dirname(HERE), "AutoQuant", "autoquant")
if os.path.isdir(_AQ) and _AQ not in sys.path:
    sys.path.insert(0, _AQ)


def _log(msg: str) -> None:
    print("[%s] %s" % (datetime.datetime.now().strftime("%H:%M:%S"), msg), flush=True)


def _alive(pid: int) -> bool:
    if not pid:
        return False
    try:
        os.kill(pid, 0)
    except Exception:
        return False
    return True


def _acquire_lock() -> bool:
    """单实例锁：已有活着的实例则拒绝启动（防止重复消费同一条指令）。"""
    try:
        if os.path.exists(LOCK):
            try:
                with open(LOCK, encoding="utf-8") as f:
                    pid = int((f.read() or "").strip() or 0)
            except Exception:
                pid = 0
            if _alive(pid) and pid != os.getpid():
                _log("已有实例在运行（pid=%s），本次退出" % pid)
                return False
        with open(LOCK, "w", encoding="utf-8") as f:
            f.write(str(os.getpid()))
        return True
    except Exception as e:  # noqa: BLE001
        # 锁本身写不了不应该阻止应答（_daemon_ctl 另有 PID 兜底）
        _log("锁文件不可用（继续运行）: %s" % e)
        return True


def _release_lock() -> None:
    try:
        if os.path.exists(LOCK):
            with open(LOCK, encoding="utf-8") as f:
                if (f.read() or "").strip() == str(os.getpid()):
                    os.remove(LOCK)
    except Exception:
        pass


def _detail_of(result: dict) -> str:
    """把 Agent 的执行步骤整理成「过程」明细 → APK「日志」Tab。"""
    steps = result.get("steps") or []
    if not steps:
        return ""
    lines = []
    for i, s in enumerate(steps, 1):
        if isinstance(s, dict):
            lines.append("%d. %s" % (i, s.get("text") or s.get("name") or str(s)))
        else:
            lines.append("%d. %s" % (i, str(s)))
    return "\n".join(lines)


class Responder:
    def __init__(self, provider_id=None, max_turns=10, ack=True):
        self.provider_id = provider_id
        self.max_turns = max_turns
        self.ack = ack
        from data_service import DataService  # noqa: PLC0415
        self.svc = DataService()
        try:
            from agent_loop import run_agent_plan  # noqa: PLC0415
            self.run_agent_plan = run_agent_plan
        except Exception as e:  # noqa: BLE001
            self.run_agent_plan = None
            _log("⚠️ agent_loop 不可用：%s（将只回 ACK 与错误提示）" % e)

    # ── 单条消息 ──
    def handle(self, m: dict) -> None:
        msg_id = (m.get("id") or "")
        content = (m.get("content") or "").strip()
        if not msg_id:
            return
        short = content[:40] + ("…" if len(content) > 40 else "")
        _log("📩 收到 [%s]：%s" % (msg_id[:12], short))

        if not content:
            try:
                self.svc.done_msg(msg_id)
            except Exception:
                pass
            return

        # ① 秒级 ACK —— 先让人知道收到了（reply_msg 可重复调用，见模块文档）
        if self.ack:
            try:
                self.svc.reply_msg(
                    msg_id,
                    "⏳ 已收到「%s」\n正在分析，请稍候…" % short,
                )
                _log("   ↳ ACK 已回")
            except Exception as e:  # noqa: BLE001
                _log("   ↳ ACK 失败（不影响后续）: %s" % e)

        # ② 真 AI 分析
        if self.run_agent_plan is None:
            self.svc.reply_msg(msg_id, "⚠️ agent_loop 不可用，无法自动分析，请稍后在 PC 侧处理。")
            return
        try:
            result = self.run_agent_plan(
                goal=content,
                provider_id=self.provider_id,
                progress=lambda s: _log("   · %s" % str(s)[:120]),
                max_turns=self.max_turns,
            )
        except Exception as e:  # noqa: BLE001
            try:
                self.svc.reply_msg(msg_id, "⚠️ 处理异常：%s" % e)
            except Exception:
                pass
            _log("❌ 处理异常: %s" % e)
            return

        if result.get("ok"):
            reply = (result.get("conclusion") or "").strip()
            if not reply:
                reply = "（已执行完毕，无文本结论，详见日志 Tab）"
        else:
            reply = "⚠️ 未能完成：" + str(result.get("error", "未知错误"))

        try:
            self.svc.reply_msg(msg_id, reply, detail=_detail_of(result))
            _log("💬 已回复 [%s]：%s" % (msg_id[:12], reply[:120].replace("\n", " ")))
        except Exception as e:  # noqa: BLE001
            _log("❌ 回复失败: %s" % e)

    # ── 主循环 ──
    def run(self, poll: float, once: bool = False) -> None:
        _log("应答器已启动 · 轮询 %.1fs · max_turns=%d · ack=%s"
             % (poll, self.max_turns, self.ack))
        while True:
            try:
                inbox = self.svc.get_inbox(state="pending", limit=50)
                msgs = inbox.get("messages") or []
            except Exception as e:  # noqa: BLE001
                _log("读取消息桥失败: %s" % e)
                msgs = []
            for m in msgs:
                try:
                    self.handle(m)
                except Exception as e:  # noqa: BLE001
                    _log("单条处理失败: %s" % e)
            if once:
                _log("--once：处理完毕，退出")
                return
            time.sleep(poll)


def main() -> int:
    ap = argparse.ArgumentParser(description="APK ↔ PC 消息常驻应答器（无 GUI）")
    ap.add_argument("--daemon", action="store_true", help="常驻循环（由 _daemon_ctl 调用）")
    ap.add_argument("--once", action="store_true", help="处理完当前积压即退出")
    ap.add_argument("--poll", type=float, default=1.5, help="轮询间隔秒（默认 1.5）")
    ap.add_argument("--max-turns", type=int, default=10, help="Agent 最大轮数（默认 10）")
    ap.add_argument("--provider", default=None, help="指定 AI Provider id（默认自动）")
    ap.add_argument("--no-ack", action="store_true", help="关闭秒级 ACK")
    a = ap.parse_args()

    if not a.daemon and not a.once:
        ap.error("需指定 --daemon 或 --once")

    if not _acquire_lock():
        return 0
    atexit.register(_release_lock)

    try:
        Responder(provider_id=a.provider, max_turns=a.max_turns,
                  ack=not a.no_ack).run(a.poll, once=a.once)
    except KeyboardInterrupt:
        _log("🛑 已停止")
    return 0


if __name__ == "__main__":
    sys.exit(main())
