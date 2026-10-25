# -*- coding: utf-8 -*-
"""缠论引擎（chan.py 包装层 · 阶段0最小验证版）
=====================================================
数据源优先级：
  1. data/kline_store.json（统一存储，存在时优先；snaps 为日线）
  2. 新浪 K 线 API（1H=scale60 / 日线=scale240，datalen=1023，带今日缓存）

输出：compute_structure(code, lv) → 统一结构 JSON 契约
  {code, lv, update_time, klines[], bi[], seg[], zs[], bsp[]}

用法：
  python smalltools/_chanlun_engine.py 600188            # 1H（默认）
  python smalltools/_chanlun_engine.py 600188 --lv day   # 日线
"""
import json
import os
import sys
import urllib.request
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
CHAN_DIR = os.path.join(HERE, "chan_py")
if CHAN_DIR not in sys.path:
    sys.path.insert(0, CHAN_DIR)

from Chan import CChan                      # noqa: E402
from ChanConfig import CChanConfig          # noqa: E402
from Common.CEnum import KL_TYPE, DATA_FIELD  # noqa: E402
from Common.CTime import CTime              # noqa: E402
from KLine.KLine_Unit import CKLine_Unit    # noqa: E402

CACHE = os.path.join(HERE, "data", "_chanlun_cache.json")
OUT_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "data", "chanlun")

LV_MAP = {"1h": (KL_TYPE.K_60M, 60), "day": (KL_TYPE.K_DAY, 240)}


# ── 数据层 ────────────────────────────────────────────────────────────────────
def _sym(raw: str) -> str:
    s = raw.strip().lower()
    if s.startswith(("sh", "sz")):
        return s
    return ("sh" if s[0] in "659" else "sz") + s


def _sina_fetch(code: str, scale: int) -> list:
    """新浪 OHLC K 线；返回 [{time,open,high,low,close,volume}]，失败返回 []。"""
    url = (f"https://quotes.sina.cn/cn/api/json_v2.php/CN_MarketDataService.getKLineData"
           f"?symbol={_sym(code)}&scale={scale}&ma=no&datalen=1023")
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0",
                                               "Referer": "https://finance.sina.com.cn"})
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            d = json.loads(r.read().decode("utf-8"))
    except Exception:
        return []
    if not d:
        return []
    return [{"time": k["day"], "open": float(k["open"]), "high": float(k["high"]),
             "low": float(k["low"]), "close": float(k["close"]),
             "volume": float(k.get("volume", 0))} for k in d]


def load_klines(code: str, lv: str = "1h", use_cache: bool = True) -> list:
    """取 K 线（今日内缓存；缓存键 {code}_{lv}_{yyyymmdd}）。"""
    key = f"{code}_{lv}"
    today = datetime.now().strftime("%Y%m%d")
    cache = {}
    if use_cache and os.path.isfile(CACHE):
        try:
            with open(CACHE, "r", encoding="utf-8") as f:
                cache = json.load(f)
        except Exception:
            cache = {}
        ent = cache.get(key)
        if ent and ent.get("date") == today and ent.get("data"):
            return ent["data"]
    _, scale = LV_MAP[lv]
    data = _sina_fetch(code, scale)
    if data:
        cache[key] = {"date": today, "data": data}
        try:
            os.makedirs(os.path.dirname(CACHE), exist_ok=True)
            with open(CACHE, "w", encoding="utf-8") as f:
                json.dump(cache, f, ensure_ascii=False)
        except Exception:
            pass
    return data


# ── 引擎层 ────────────────────────────────────────────────────────────────────
def _to_klu(row: dict, lv: str) -> "CKLine_Unit":
    ts = row["time"]
    # 新浪格式："2026-10-20 14:00"（分钟级）或 "2026-10-20"（日线）
    parts = ts.split(" ")
    y, m, d = (int(x) for x in parts[0].split("-"))
    hh, mm = (14, 0) if len(parts) == 1 else (int(x) for x in parts[1][:5].split(":"))
    return CKLine_Unit({
        DATA_FIELD.FIELD_TIME: CTime(y, m, d, hh, mm),
        DATA_FIELD.FIELD_OPEN: row["open"], DATA_FIELD.FIELD_HIGH: row["high"],
        DATA_FIELD.FIELD_LOW: row["low"], DATA_FIELD.FIELD_CLOSE: row["close"],
        DATA_FIELD.FIELD_VOLUME: row.get("volume", 0.0),
    })


def compute_structure(code: str, lv: str = "1h") -> dict:
    """计算单票缠论结构，返回统一 JSON 契约。"""
    kl_type, _ = LV_MAP[lv]
    rows = load_klines(code, lv)
    if not rows:
        raise RuntimeError(f"{code} {lv} K线获取失败")
    chan = CChan(code=code, lv_list=[kl_type],
                 config=CChanConfig({"trigger_step": True}))
    chan.trigger_load({kl_type: [_to_klu(r, lv) for r in rows]})
    kl = chan.kl_datas[kl_type]

    def _t(klu) -> str:
        t = klu.time
        return f"{t.year}-{t.month:02d}-{t.day:02d} {t.hour:02d}:{t.minute:02d}"

    bi = [{"idx": b.idx, "dir": "up" if b.dir.value == 1 else "down",
           "is_sure": b.is_sure,
           "begin_val": round(b.get_begin_val(), 3), "end_val": round(b.get_end_val(), 3),
           "begin_time": _t(b.get_begin_klu()), "end_time": _t(b.get_end_klu()),
           "begin_klu_idx": b.get_begin_klu().idx, "end_klu_idx": b.get_end_klu().idx}
          for b in kl.bi_list.bi_list]
    seg = [{"idx": s.idx, "dir": "up" if s.dir.value == 1 else "down", "is_sure": s.is_sure,
            "begin_time": _t(s.start_bi.get_begin_klu()), "end_time": _t(s.end_bi.get_end_klu())}
           for s in kl.seg_list.lst]
    zs = [{"idx": i, "low": round(z.low, 3), "high": round(z.high, 3),
           "begin_time": _t(z.begin), "end_time": _t(z.end),
           "begin_klu_idx": z.begin.idx, "end_klu_idx": z.end.idx}
          for i, z in enumerate(kl.zs_list.zs_lst)]
    bsp = [{"klu_idx": b.klu.idx, "is_buy": b.is_buy, "time": _t(b.klu),
            "type": [t.value for t in b.type], "is_sure": b.bi.is_sure,
            "price": round(b.klu.close, 3)}
           for b in sorted(kl.bs_point_lst.bsp_store_flat_dict.values(),
                           key=lambda x: x.bi.idx)]
    return {
        "code": code, "lv": lv,
        "update_time": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "kline_cnt": len(rows), "merged_kline_cnt": len(kl.lst),
        "klines": [{"time": r["time"], "open": r["open"], "high": r["high"],
                    "low": r["low"], "close": r["close"]} for r in rows],
        "bi": bi, "seg": seg, "zs": zs, "bsp": bsp,
    }


# ── CLI ───────────────────────────────────────────────────────────────────────
if __name__ == "__main__":
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("code")
    ap.add_argument("--lv", default="1h", choices=list(LV_MAP.keys()))
    ap.add_argument("--save", action="store_true", help="写 assets/data/chanlun/")
    args = ap.parse_args()

    r = compute_structure(args.code, args.lv)
    print(f"[{r['code']}] lv={r['lv']} K线={r['kline_cnt']} 合并K线={r['merged_kline_cnt']}")
    print(f"笔={len(r['bi'])} 线段={len(r['seg'])} 中枢={len(r['zs'])} 买卖点={len(r['bsp'])}")
    print("最近5笔:")
    for b in r["bi"][-5:]:
        print(f"  bi#{b['idx']} {b['dir']} sure={b['is_sure']} "
              f"{b['begin_val']}→{b['end_val']} (klu {b['begin_klu_idx']}→{b['end_klu_idx']})")
    print("中枢:")
    for z in r["zs"][-3:]:
        print(f"  zs#{z['idx']} [{z['low']}, {z['high']}] (klu {z['begin_klu_idx']}~{z['end_klu_idx']})")
    print("买卖点:")
    for p in r["bsp"][-5:]:
        side = "买" if p["is_buy"] else "卖"
        print(f"  {side} {p['type']} @klu{p['klu_idx']} price={p['price']} sure={p['is_sure']}")
    if args.save:
        os.makedirs(OUT_DIR, exist_ok=True)
        out = os.path.join(OUT_DIR, f"structure_{args.code}_{args.lv}.json")
        with open(out, "w", encoding="utf-8") as f:
            json.dump(r, f, ensure_ascii=False, indent=1)
        print(f"已写: {out}")
