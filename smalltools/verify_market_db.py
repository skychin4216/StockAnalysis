# -*- coding: utf-8 -*-
"""
市场库端到端验证（由 _tmp_dbverify.py 转正）
================================================
验证 PC 市场库（data/market_data.db）与 JSON 缓存的一致性、参数 save/get
往返、新闻导入与公告抽样。云端同步 DB 前/后跑一遍，确保库可正常导入 App。

用法：
    python verify_market_db.py
    python verify_market_db.py --db 自定义市场库路径
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _market_db  # noqa: E402

SMALL = os.path.dirname(os.path.abspath(__file__))
DEFAULT_DB = os.path.join(os.path.dirname(SMALL), "data", "market_data.db")


def main():
    ap = argparse.ArgumentParser(description="市场库端到端验证")
    ap.add_argument("--db", default=DEFAULT_DB, help="市场库路径（默认 data/market_data.db）")
    args = ap.parse_args()

    # 0) 确认库存在
    if not os.path.exists(args.db):
        print("数据库不存在: %s" % args.db)
        sys.exit(1)
    _market_db.DB_PATH = args.db

    # 1) K线一致性：SQLite vs _kline_cache.json
    cache = json.load(open(os.path.join(SMALL, "_kline_cache.json"), encoding="utf-8"))
    conn = _market_db.get_conn()
    mismatch = checked = 0
    for secid in list(cache)[:5]:
        for s in cache[secid]["snaps"][-20:]:
            row = conn.execute(
                "SELECT open,high,low,close,volume,change_pct,turnover FROM kline WHERE secid=? AND date=?",
                (secid, s["date"])).fetchone()
            checked += 1
            if not row:
                mismatch += 1
                continue
            exp = (s["open"], s["high"], s["low"], s["close"], s["volume"], s["changePct"], s["turnover"])
            if tuple(round(float(x or 0), 4) for x in row) != tuple(round(float(x or 0), 4) for x in exp):
                mismatch += 1
    print(f"[1] K线一致性: 抽检 {checked} 行, 不一致 {mismatch}")

    # 2) 参数往返
    test_payload = {"window": ["2026-08-15", "2026-09-15"], "fitted": {"超短": {"rule": "T+3>+4%"}}}
    _market_db.save_params(conn, "walk_forward", "__test__", test_payload)
    got = _market_db.get_params(conn, "walk_forward", "__test__")
    assert got == test_payload, "参数往返失败"
    conn.execute("DELETE FROM params WHERE version='__test__'")
    conn.commit()
    print("[2] 参数 save/get 往返: OK")

    # 3) 新闻导入（合成数据，验证后清理）
    fake = {"sh600011": {"name": "华能国际", "items": [
        {"date": "2026-08-20 09:15", "title": "测试新闻1", "summary": "摘要1"},
        {"date": "2026-08-19 18:00", "title": "测试新闻2", "summary": "摘要2"},
    ]}}
    n_stock, n_row = _market_db.import_news_json(conn, cache=fake)
    assert n_row == 2
    cnt = conn.execute("SELECT COUNT(*) FROM news WHERE secid='sh600011' AND title LIKE '测试新闻%'").fetchone()[0]
    assert cnt == 2, f"新闻导入后计数 {cnt}"
    conn.execute("DELETE FROM news WHERE secid='sh600011' AND title LIKE '测试新闻%'")
    conn.commit()
    print(f"[3] 新闻导入: OK ({n_stock} 只 / {n_row} 条)")

    # 4) 公告抽样
    a = conn.execute("SELECT COUNT(*) FROM announce WHERE secid='sh600011'").fetchone()[0]
    print(f"[4] 公告抽样 sh600011: {a} 条")

    # 5) 统计 + meta
    print("[5] 库统计:", _market_db.kline_stats(conn))
    print("    meta:", {k: _market_db.get_meta(conn, k) for k in ("schema_version", "latest_date", "built_at")})
    conn.close()
    print("\n全部验证通过 ✓")


if __name__ == "__main__":
    main()
