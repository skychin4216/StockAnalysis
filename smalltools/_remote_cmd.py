# -*- coding: utf-8 -*-
"""APK 指令消费（2026-09-21 用户需求 · Q1/Q2）

轮询 `AutoQuant/data/cb_inbox.json` 的 **pending** 消息并执行：

    买 11            → 买子编码 11 对应的股票
    买 11 12 21      → 一次买多只
    卖出 11          → 卖出（T+1 内拒绝，A 股规则）
    其他文本          → 不处理（留给人工 / CodeBuddy）

下单走 `_trade_gateway`（**默认 dry_run**，`TRADE_LIVE=1` 才实盘），
成交/拒单后同步维护 `data/_auto_positions.json`（供 `_auto_sell.py` 做 T+1 止盈止损）。

CLI:
    python _remote_cmd.py --once      # 处理一轮
    python _remote_cmd.py --daemon    # 常驻（默认 5s 一轮）
"""
from __future__ import annotations

import datetime as dt
import json
import os
import re
import sys
import time
from typing import Any, Dict, List

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import _pick_codes as PC                     # noqa: E402
from _trade_gateway import Order, build as build_gateway   # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
INBOX = os.path.join(ROOT, "AutoQuant", "data", "cb_inbox.json")
TOKEN_FILE = os.path.join(ROOT, "AutoQuant", "data", "remote_token.txt")
POS_FILE = os.path.join(ROOT, "data", "_auto_positions.json")

BUDGET_PER_CODE = 10000.0     # 每个子编码的默认买入金额（元）
LOTS = 100                    # A 股一手


# ══════════════════════════════════════════════════════════
# 基础设施
# ══════════════════════════════════════════════════════════

def _token() -> str:
    return open(TOKEN_FILE, encoding="utf-8").read().strip()


def _http(path: str, payload: Dict[str, Any]) -> Dict[str, Any]:
    import requests                              # noqa: PLC0415
    r = requests.post("http://127.0.0.1:8888" + path, json=payload,
                      timeout=10, headers={"X-Token": _token()},
                      proxies={"http": None, "https": None})
    return r.json() if r.headers.get("content-type", "").startswith("application/json") else {}


def reply(msg_id: str, content: str, detail: str = "") -> None:
    """回复 APK（content=结论进「对话」，detail=过程进「日志」）。"""
    try:
        _http("/remote/msg/reply", {"msg_id": msg_id, "content": content,
                                    "detail": detail or None})
    except Exception as e:                       # noqa: BLE001
        print("回复失败:", e)


def load_positions() -> List[Dict[str, Any]]:
    try:
        return json.load(open(POS_FILE, encoding="utf-8"))
    except Exception:                            # noqa: BLE001
        return []


def save_positions(ps: List[Dict[str, Any]]) -> None:
    os.makedirs(os.path.dirname(POS_FILE), exist_ok=True)
    tmp = POS_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(ps, f, ensure_ascii=False, indent=1)
    os.replace(tmp, POS_FILE)


def qty_for(price: float, budget: float = BUDGET_PER_CODE) -> int:
    """按预算算股数（整手，向下取整；至少 1 手）。"""
    if price <= 0:
        return LOTS
    return max(LOTS, int(budget / price // LOTS) * LOTS)


# ══════════════════════════════════════════════════════════
# 指令解析与执行
# ══════════════════════════════════════════════════════════

def parse_sell(text: str) -> List[str]:
    m = re.search(r"(?:卖出|卖|sell)\s*([\d\s,，、]+)", (text or "").strip(), re.IGNORECASE)
    if not m:
        return []
    return [x for x in re.split(r"[\s,，、]+", m.group(1)) if re.fullmatch(r"\d{2}", x)]


def handle_message(msg: Dict[str, Any], gw) -> bool:
    """处理一条消息。返回 True=已处理（会被标记 done）。"""
    text = str(msg.get("content") or "").strip()
    mid = msg.get("id", "")
    codes = PC.parse_buy(text)
    sells = parse_sell(text)
    if not codes and not sells:
        return False

    steps: List[str] = []
    now = dt.datetime.now()
    positions = load_positions()
    orders: List[Order] = []
    names: List[str] = []

    # ── 买入 ──
    for c in codes:
        p = PC.resolve(c)
        if not p:
            steps.append("✗ %s：不在当日选股清单（可能已过期，等下一次选股）" % c)
            continue
        price = float(p.get("price") or 0)
        if price <= 0:
            steps.append("✗ %s %s：无有效价格" % (c, p.get("name")))
            continue
        q = qty_for(price)
        orders.append(Order(symbol=p["secid"], side="buy", qty=q, price=price,
                            order_type="limit",
                            reason="APK子编码%s" % c))
        names.append("%s(%s %s x%d@%.2f)" % (c, p.get("name"), PC.code6(p), q, price))

    # ── 卖出（T+1 校验）──
    for c in sells:
        hit = [x for x in positions if x.get("pick_code") == c and x.get("state") == "open"]
        if not hit:
            steps.append("✗ %s：无持仓记录" % c)
            continue
        for pos in hit:
            if pos.get("buy_date") == now.strftime("%Y-%m-%d"):
                steps.append("✗ %s %s：T+1 当日不可卖（%s 买入）"
                             % (c, pos.get("name"), pos.get("buy_date")))
                continue
            orders.append(Order(symbol=pos["symbol"], side="sell", qty=int(pos["qty"]),
                                price=float(pos.get("last_price") or pos.get("buy_price") or 0),
                                order_type="limit", reason="APK卖出%s" % c))
            names.append("卖 %s(%s)" % (c, pos.get("name")))

    if not orders:
        reply(mid, "⚠ 指令无法执行（%s）" % (text[:40]),
              "\n".join("• " + s for s in steps) or "无可执行订单")
        return True

    res = gw.place(orders, reason="APK指令: %s" % text[:40])
    oks = res.get("ok") or []
    rej = res.get("rejected") or []

    # 记录持仓（dry_run 也记，便于演示整条链路；实盘同结构）
    # 注意：place() 返回的 ok 项是**日志记录**，订单在 `order` 键下 —— 需解一层
    if oks:
        for rec in oks:
            o = (rec.get("order") if isinstance(rec, dict) else None) or rec
            if str(o.get("side")) == "buy":
                pc = next((c for c in codes
                           if PC.resolve(c) and PC.resolve(c)["secid"] == o.get("symbol")), "")
                pname = (PC.resolve(pc) or {}).get("name") if pc else (o.get("name") or o.get("symbol"))
                positions.append({
                    "symbol": o.get("symbol"), "name": pname or o.get("symbol"),
                    "qty": o.get("qty"), "buy_price": o.get("price"),
                    "buy_date": now.strftime("%Y-%m-%d"), "buy_time": now.strftime("%H:%M:%S"),
                    "pick_code": pc, "state": "open",
                })
        save_positions(positions)

    lines = ["已受理 %d 笔，拒绝 %d 笔" % (len(oks), len(rej))] + names
    content = "✅ " + "；".join(lines[:3]) + (" 等 %d 笔" % len(oks) if len(oks) > 3 else "")
    detail = "\n".join(["• " + s for s in steps] +
                       ["• 模式: %s" % ("实盘" if not gw.dry_run else "dry_run（TRADE_LIVE=1 才实盘）")] +
                       ["• 受理: %s" % json.dumps(o, ensure_ascii=False)[:120] for o in oks] +
                       ["• 拒绝: %s" % json.dumps(r, ensure_ascii=False)[:120] for r in rej])
    reply(mid, content, detail)
    return True


def run_once() -> int:
    try:
        inbox = json.load(open(INBOX, encoding="utf-8"))
    except Exception as e:                       # noqa: BLE001
        print("读 inbox 失败:", e)
        return 0
    pending = [m for m in inbox if m.get("status") == "pending"]
    if not pending:
        return 0
    gw = build_gateway("paper")                  # 默认模拟盘；TRADE_LIVE=1 走实盘
    n = 0
    for m in pending:
        try:
            if handle_message(m, gw):
                n += 1
            else:
                _http("/remote/msg/done", {"msg_id": m.get("id")})
        except Exception as e:                   # noqa: BLE001
            print("处理异常:", e)
    return n


if __name__ == "__main__":
    if "--daemon" in sys.argv:
        itv = 5
        print("指令消费守护启动（%ds/轮）" % itv)
        while True:
            try:
                k = run_once()
                if k:
                    print(time.strftime("%H:%M:%S"), "处理", k, "条")
            except Exception as e:               # noqa: BLE001
                print(e)
            time.sleep(itv)
    else:
        print("本轮处理:", run_once(), "条")
