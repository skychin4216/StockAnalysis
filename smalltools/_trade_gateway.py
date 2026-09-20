# -*- coding: utf-8 -*-
"""统一交易网关（exe 实时交易接口，2026-09-20 骨架 + 模拟盘实现）。

## 设计目标

给 exe / 守护 / APK 一个**统一的交易接口**，底层券商可换（QMT / PTrade / 模拟），
上层（选股 → 下单）代码不用改。对齐用户选择："10 万起、口碑较好的券商" → 首选 **QMT（迅投）**。

    ┌──────────────┐   统一订单模型     ┌─────────────┐
    │ exe / 守护    │ ───────────────→  │ TradeGateway │──→ QmtGateway    (实盘, 需开户)
    │ 选股/建仓逻辑 │ ←───────────────  │  抽象层      │──→ PaperGateway  (模拟, 立即可用)
    └──────────────┘   持仓/成交回报     └─────────────┘──→ PtradeGateway (预留)
                                              │
                                       风控前置（复用项目组合纪律）

## 安全设计（重要）

- **默认 `dry_run=True`**：所有下单只记录 + 落盘，**不真的报单**。
  必须显式 `TradeGateway(dry_run=False)` 或环境变量 `TRADE_LIVE=1` 才会真实交易。
- 风控前置（下单前硬校验，任一不过直接拒单）：
    单票占持仓市值 ≤ 30% ｜ 前 2 大合计 ≤ 55% ｜ 组合整体浮亏 ≤ -8% 时禁开新仓
    （与 `_publish_candidates` 的「⚠️ 组合纪律」同阈值）
- 全部指令落盘 `data/_trade_orders.jsonl`（含时间/理由/风控快照），可事后审计。

## 统一订单模型

    Order(symbol="sh600000", side="buy"|"sell", price=0.0(市价), qty=100,
          order_type="limit"|"market", reason="选股BULLISH 招行")

## 用法（示例）

    python _trade_gateway.py --check                     # 查看当前可用网关与配置
    python _trade_gateway.py --paper-demo                # 模拟盘跑一笔，验证链路
    python _trade_gateway.py --positions                 # 查持仓（模拟盘读本地）

## 接入 QMT 时要做的事（开户后）

    1. `pip install xtquant`（或从 QMT 客户端目录拷贝）
    2. 启动 QMT 客户端并登录（交易需 `xttrader` 连接成功）
    3. 在 `QmtGateway.__init__` 里填 `path`（QMT userdata_mini 目录）与 `account_id`
    4. 把 `TRADE_LIVE=1` 写进守护环境变量（`_daemon_ctl.py` 已有注入位置）
"""
import argparse
import json
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
ORDER_LOG = os.path.join(ROOT, "data", "_trade_orders.jsonl")
PAPER_STATE = os.path.join(ROOT, "data", "_paper_account.json")

# ── 风控阈值（与 _publish_candidates 的组合纪律同口径）──
RISK_SINGLE_MAX = 0.30      # 单票占持仓市值上限
RISK_TOP2_MAX = 0.55        # 前 2 大合计
RISK_DRAWDOWN_STOP = -0.08  # 组合整体浮亏线：跌破后禁开新仓


class Order(object):
    __slots__ = ("symbol", "side", "price", "qty", "order_type", "reason")

    def __init__(self, symbol, side, qty, price=0.0, order_type="limit", reason=""):
        self.symbol = symbol                # sh600000
        self.side = side.lower()            # buy / sell
        self.price = float(price or 0)
        self.qty = int(qty)
        self.order_type = order_type        # limit / market
        self.reason = reason

    def to_dict(self):
        return {"symbol": self.symbol, "side": self.side, "price": self.price,
                "qty": self.qty, "order_type": self.order_type, "reason": self.reason}


def risk_check(orders, positions, account):
    """下单前风控：返回 (通过列表, 拒单原因列表)。

    positions: [{symbol, market_value, pnl_pct, ...}]；account: {"total_value":..}
    """
    ok, rejects = [], []
    total = float((account or {}).get("total_value") or 0)
    dd = float((account or {}).get("drawdown") or 0)
    if dd <= RISK_DRAWDOWN_STOP:
        for o in orders:
            if o.side == "buy":
                rejects.append((o, "组合浮亏 %.1f%% 已触 %.0f%% → 禁开新仓"
                                % (dd * 100, RISK_DRAWDOWN_STOP * 100)))
            else:
                ok.append(o)
        return ok, rejects
    mv = {p["symbol"]: float(p.get("market_value") or 0) for p in (positions or [])}
    for o in orders:
        if o.side != "buy":
            ok.append(o)
            continue
        add = o.qty * (o.price or 0)
        after = (mv.get(o.symbol, 0) + add)
        if total > 0 and after / total > RISK_SINGLE_MAX:
            rejects.append((o, "单票占比将达 %.1f%% > %.0f%%"
                            % (after / total * 100, RISK_SINGLE_MAX * 100)))
            continue
        top2 = sorted([v for k, v in mv.items() if k != o.symbol] + [after],
                      reverse=True)[:2]
        if total > 0 and sum(top2) / total > RISK_TOP2_MAX:
            rejects.append((o, "前2大合计将达 %.1f%% > %.0f%%"
                            % (sum(top2) / total * 100, RISK_TOP2_MAX * 100)))
            continue
        ok.append(o)
    return ok, rejects


class TradeGateway(object):
    """交易网关抽象基类。子类实现 _do_* 系列即可。"""

    name = "abstract"

    def __init__(self, dry_run=True, log_path=ORDER_LOG):
        env_live = (os.environ.get("TRADE_LIVE") or "").strip() in ("1", "true", "yes")
        self.dry_run = bool(dry_run) and not env_live
        self.log_path = log_path

    # ── 子类需实现 ────────────────────────────────────────
    def _do_place(self, order):
        raise NotImplementedError

    def _do_query_positions(self):
        return []

    def _do_query_account(self):
        return {"total_value": 0.0, "drawdown": 0.0, "cash": 0.0}

    # ── 对外统一接口 ──────────────────────────────────────
    def place(self, orders, reason=""):
        """批量下单（含风控）。返回 {"ok":[...], "rejected":[...], "dry_run":bool}。"""
        pos, acc = self._do_query_positions(), self._do_query_account()
        ok, rej = risk_check(orders, pos, acc)
        filled = []
        for o in ok:
            rec = {"ts": time.strftime("%Y-%m-%d %H:%M:%S"), "gateway": self.name,
                   "dry_run": self.dry_run, "order": o.to_dict(),
                   "reason": o.reason or reason}
            if not self.dry_run:
                try:
                    rec["result"] = self._do_place(o)
                except Exception as e:  # noqa: BLE001
                    rec["result"] = {"ok": False, "err": "%s: %s" % (type(e).__name__, e)}
            else:
                rec["result"] = {"ok": True, "note": "dry_run 未真实报单"}
            filled.append(rec)
            self._log(rec)
        for o, why in rej:
            self._log({"ts": time.strftime("%Y-%m-%d %H:%M:%S"), "gateway": self.name,
                       "order": o.to_dict(), "rejected": why})
        return {"ok": filled, "rejected": [(o.to_dict(), w) for o, w in rej],
                "dry_run": self.dry_run}

    def positions(self):
        return self._do_query_positions()

    def account(self):
        return self._do_query_account()

    def _log(self, rec):
        try:
            os.makedirs(os.path.dirname(self.log_path), exist_ok=True)
            with open(self.log_path, "a", encoding="utf-8") as f:
                f.write(json.dumps(rec, ensure_ascii=False) + "\n")
        except OSError:
            pass


class PaperGateway(TradeGateway):
    """模拟盘：持仓/资金落盘 `data/_paper_account.json`，立即可用、零风险。"""

    name = "paper"

    def _load(self):
        try:
            with open(PAPER_STATE, encoding="utf-8") as f:
                return json.load(f)
        except Exception:  # noqa: BLE001
            return {"cash": 100000.0, "positions": {}}

    def _save(self, st):
        os.makedirs(os.path.dirname(PAPER_STATE), exist_ok=True)
        with open(PAPER_STATE, "w", encoding="utf-8") as f:
            json.dump(st, f, ensure_ascii=False, indent=1)

    def _do_query_positions(self):
        st = self._load()
        return [{"symbol": k, "market_value": v.get("qty", 0) * v.get("cost", 0),
                 "pnl_pct": 0.0, "qty": v.get("qty", 0), "cost": v.get("cost", 0)}
                for k, v in (st.get("positions") or {}).items()]

    def _do_query_account(self):
        st = self._load()
        pos_mv = sum(v.get("qty", 0) * v.get("cost", 0)
                     for v in (st.get("positions") or {}).values())
        return {"total_value": st.get("cash", 0) + pos_mv,
                "cash": st.get("cash", 0), "drawdown": st.get("drawdown", 0.0)}

    def _do_place(self, order):
        st = self._load()
        pos = st.setdefault("positions", {})
        cur = pos.get(order.symbol) or {"qty": 0, "cost": 0}
        price = order.price or cur.get("cost", 0)
        if order.side == "buy":
            cost = price * order.qty
            if cost > st.get("cash", 0):
                return {"ok": False, "err": "现金不足"}
            st["cash"] = st.get("cash", 0) - cost
            q = cur.get("qty", 0) + order.qty
            cur["cost"] = ((cur.get("cost", 0) * cur.get("qty", 0)) + cost) / q if q else 0
            cur["qty"] = q
        else:
            q = max(0, cur.get("qty", 0) - order.qty)
            st["cash"] = st.get("cash", 0) + price * (cur.get("qty", 0) - q)
            cur["qty"] = q
        pos[order.symbol] = cur
        self._save(st)
        return {"ok": True, "fill": {"symbol": order.symbol, "qty": order.qty, "price": price}}


class QmtGateway(TradeGateway):
    """QMT 实盘（迅投 xtquant）—— **开户后填两个参数即可启用**。

    前置：pip install xtquant（或从 QMT 客户端目录拷贝）+ QMT 客户端登录。
    """

    name = "qmt"

    def __init__(self, path=None, account_id=None, dry_run=True):
        super().__init__(dry_run=dry_run)
        self.path = path or os.environ.get("QMT_PATH") or r"C:\国金QMT交易端\userdata_mini"
        self.account_id = account_id or os.environ.get("QMT_ACCOUNT") or ""
        self._trader = None
        self._acc = None

    def connect(self):
        from xtquant.xttrader import XtQuantTrader          # noqa: PLC0415
        from xtquant.xttype import StockAccount             # noqa: PLC0415
        sid = int(time.time())
        self._trader = XtQuantTrader(self.path, sid)
        self._trader.start()
        r = self._trader.connect()
        if r != 0:
            raise RuntimeError("QMT 连接失败（code=%s）：客户端需已登录" % r)
        self._acc = StockAccount(self.account_id)
        if self._trader.subscribe(self._acc) != 0:
            raise RuntimeError("QMT 账户订阅失败：检查 account_id 是否正确")
        return True

    def _do_place(self, order):
        from xtquant import xtconstant                       # noqa: PLC0415
        if self._trader is None:
            self.connect()
        code = ("%s.%s" % (order.symbol[2:], order.symbol[:2].upper())
                if order.symbol[:2] in ("sh", "sz") else order.symbol)
        typ = xtconstant.STOCK_BUY if order.side == "buy" else xtconstant.STOCK_SELL
        ptype = (xtconstant.FIX_PRICE if order.order_type == "limit"
                 else xtconstant.LATEST_PRICE)
        oid = self._trader.order_stock(self._acc, code, typ, order.qty, ptype,
                                       order.price or 0, "stockanalysis", order.reason or "")
        return {"ok": bool(oid and oid > 0), "order_id": oid}

    def _do_query_positions(self):
        if self._trader is None:
            self.connect()
        out = []
        for p in (self._trader.query_stock_positions(self._acc) or []):
            out.append({"symbol": p.stock_code, "qty": p.volume,
                        "market_value": p.market_value,
                        "pnl_pct": getattr(p, "profit_rate", 0.0)})
        return out

    def _do_query_account(self):
        if self._trader is None:
            self.connect()
        a = self._trader.query_stock_asset(self._acc)
        return {"total_value": getattr(a, "total_asset", 0.0),
                "cash": getattr(a, "cash", 0.0), "drawdown": 0.0}


GATEWAYS = {"paper": PaperGateway, "qmt": QmtGateway}


def build(name="paper", **kw):
    """工厂：`build("qmt", account_id="123456")`。"""
    cls = GATEWAYS.get(name)
    if not cls:
        raise ValueError("未知网关: %s（可选 %s）" % (name, list(GATEWAYS)))
    return cls(**kw)


def main():
    ap = argparse.ArgumentParser(description="统一交易网关（exe 实时交易接口）")
    ap.add_argument("--check", action="store_true", help="查看可用网关与当前配置")
    ap.add_argument("--paper-demo", action="store_true", help="模拟盘跑一笔买+卖，验证链路")
    ap.add_argument("--positions", action="store_true", help="查持仓")
    ap.add_argument("--gateway", default="paper", choices=list(GATEWAYS))
    ap.add_argument("--symbol", default="sh600000")
    ap.add_argument("--qty", type=int, default=100)
    a = ap.parse_args()

    if a.check:
        print("可用网关:", list(GATEWAYS))
        print("TRADE_LIVE 环境变量:", os.environ.get("TRADE_LIVE") or "(未设置 → 全部 dry_run)")
        print("订单日志:", ORDER_LOG)
        try:
            import xtquant  # noqa: PLC0415, F401
            print("xtquant: ✅ 已安装（QMT 实盘可用）")
        except ImportError:
            print("xtquant: ❌ 未安装（QMT 需开户 + 安装客户端自带包）")
        return 0

    gw = build(a.gateway)
    if a.positions:
        print("账户:", gw.account())
        print("持仓:", gw.positions())
        return 0

    if a.paper_demo:
        r1 = gw.place([Order(a.symbol, "buy", a.qty, 10.0, reason="paper demo 买入")])
        r2 = gw.place([Order(a.symbol, "sell", a.qty, 10.5, reason="paper demo 卖出")])
        print("买入:", json.dumps(r1, ensure_ascii=False)[:200])
        print("卖出:", json.dumps(r2, ensure_ascii=False)[:200])
        print("账户:", gw.account())
        return 0

    ap.print_help()
    return 0


if __name__ == "__main__":
    sys.exit(main())
