# -*- coding: utf-8 -*-
"""
市场库结构检查工具（由 _tmp_dbschema.py 转正）
================================================
用于检查 PC 端公共市场库（默认 data/market_data.db）的表结构、
行数、索引与数据概览，方便维护云端同步 DB（App 端 Room 导入依赖该库）。

用法：
    python inspect_market_db.py                 # 检查默认市场库
    python inspect_market_db.py --path D:/x/market_data.db
    python inspect_market_db.py --full          # 打印每表前 3 行样例数据
"""
import argparse
import os
import sqlite3
import sys

DEFAULT_DB = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "data", "market_data.db"
)


def _fmt(v):
    if v is None:
        return "NULL"
    s = str(v)
    return s if len(s) <= 40 else s[:37] + "..."


def inspect(path, full=False):
    if not os.path.exists(path):
        print("数据库不存在: %s" % path)
        return 1
    size = os.path.getsize(path)
    print("=" * 72)
    print("市场库: %s (%.1f MB)" % (path, size / 1048576.0))
    print("=" * 72)

    con = sqlite3.connect(path)
    con.row_factory = sqlite3.Row
    cur = con.cursor()

    tables = [r[0] for r in cur.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")]
    if not tables:
        print("（无业务表）")
        con.close()
        return 1

    for t in tables:
        n = cur.execute("SELECT COUNT(*) FROM %s" % t).fetchone()[0]
        print("\n【%s】%d 行" % (t, n))

        cols = cur.execute("PRAGMA table_info(%s)" % t).fetchall()
        print("  字段: " + ", ".join("%s(%s%s)" % (
            c["name"], c["type"] or "?", " PK" if c["pk"] else "") for c in cols))

        idxs = cur.execute("PRAGMA index_list(%s)" % t).fetchall()
        if idxs:
            for ix in idxs:
                cols_ix = cur.execute(
                    "PRAGMA index_info(%s)" % ix["name"]).fetchall()
                print("  索引: %s%s → %s" % (
                    ix["name"], " UNIQUE" if ix["unique"] else "",
                    ",".join(c["name"] for c in cols_ix)))

        if full and n:
            print("  样例(前3行):")
            for row in cur.execute("SELECT * FROM %s LIMIT 3" % t).fetchall():
                print("    " + " | ".join("%s=%s" % (k, _fmt(row[k])) for k in row.keys()))

    # 数据概览
    if "kline" in tables:
        row = cur.execute(
            "SELECT MIN(date), MAX(date), COUNT(DISTINCT secid) FROM kline").fetchone()
        print("\nK线概览: 日期 %s ~ %s, %d 只标的" % (row[0], row[1], row[2]))
    if "announce" in tables:
        row = cur.execute(
            "SELECT MIN(date), MAX(date), COUNT(DISTINCT secid) FROM announce").fetchone()
        print("公告概览: 日期 %s ~ %s, %d 只标的" % (row[0], row[1], row[2]))
    if "meta" in tables:
        metas = {r["key"]: r["value"] for r in cur.execute("SELECT * FROM meta")}
        print("meta: " + ", ".join("%s=%s" % (k, v) for k, v in metas.items()))

    con.close()
    print("\n检查完成 ✓")
    return 0


def main():
    ap = argparse.ArgumentParser(description="市场库结构检查工具")
    ap.add_argument("--path", default=DEFAULT_DB, help="市场库路径（默认 data/market_data.db）")
    ap.add_argument("--full", action="store_true", help="打印样例数据")
    args = ap.parse_args()
    sys.exit(inspect(args.path, args.full))


if __name__ == "__main__":
    main()
