# -*- coding: utf-8 -*-
"""公共市场数据库模块（SQLite）—— exe / smalltools / APK 统一数据源

背景：
  之前三端各自维护数据：
    - smalltools: _kline_cache.json（四年K线）+ _announce_cache.json（三年公告）+ _news_cache.json（新闻）
    - AutoQuant/exe: data/cache/*.csv（仅约一年半，字段不全）
    - APK: 自拉东财 + assets 参数
  本模块提供单文件 SQLite 数据库（StockAnalysis/data/market_data.db），
  三类数据统一入库，两端共用同一数据源。

表结构：
  kline(secid, date, open, high, low, close, volume, change_pct, turnover, src, name)
  announce(secid, date, title)
  news(secid, date, title, summary)
  params(scope, version, generated_at, payload_json)      -- 拟合参数归档
  meta(key, value)                                         -- 元信息（库版本/末端日期）

secid 格式（与 smalltools/_kline_cache.json 一致）：
  sh600519 / sz000338 / 指数 sh000001 sz399001 sz399006
exe 侧 symbol 格式：000338_SZ / 600519_SH / INDEX_SH
"""
import datetime
import json
import os
import sqlite3

SMALLTOOLS_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(SMALLTOOLS_DIR)
DATA_DIR = os.path.join(ROOT, "data")
DB_PATH = os.path.join(DATA_DIR, "market_data.db")

SCHEMA_VERSION = "1.0"

DDL = """
CREATE TABLE IF NOT EXISTS kline (
    secid      TEXT NOT NULL,
    date       TEXT NOT NULL,
    open       REAL, high REAL, low REAL, close REAL,
    volume     REAL, change_pct REAL, turnover REAL,
    src        TEXT, name TEXT,
    PRIMARY KEY (secid, date)
);
CREATE INDEX IF NOT EXISTS idx_kline_date ON kline(date);

CREATE TABLE IF NOT EXISTS announce (
    secid  TEXT NOT NULL,
    date   TEXT NOT NULL,
    title  TEXT NOT NULL,
    PRIMARY KEY (secid, date, title)
);
CREATE INDEX IF NOT EXISTS idx_announce_date ON announce(date);

CREATE TABLE IF NOT EXISTS news (
    secid   TEXT NOT NULL,
    date    TEXT NOT NULL,
    title   TEXT NOT NULL,
    summary TEXT DEFAULT '',
    PRIMARY KEY (secid, date, title)
);
CREATE INDEX IF NOT EXISTS idx_news_date ON news(date);

CREATE TABLE IF NOT EXISTS params (
    scope        TEXT NOT NULL,
    version      TEXT NOT NULL,
    generated_at TEXT DEFAULT '',
    payload_json TEXT NOT NULL,
    PRIMARY KEY (scope, version)
);

CREATE TABLE IF NOT EXISTS meta (
    key   TEXT PRIMARY KEY,
    value TEXT
);
"""


# ---------------------------------------------------------------- 基础 API

def get_conn(db_path: str = None) -> sqlite3.Connection:
    """打开连接（自动建目录与表）。"""
    path = db_path or DB_PATH
    os.makedirs(os.path.dirname(path), exist_ok=True)
    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA synchronous=NORMAL")
    init_db(conn)
    return conn


def init_db(conn: sqlite3.Connection):
    conn.executescript(DDL)
    conn.commit()


def set_meta(conn: sqlite3.Connection, key: str, value: str):
    conn.execute("INSERT OR REPLACE INTO meta(key, value) VALUES(?,?)", (key, str(value)))
    conn.commit()


def get_meta(conn: sqlite3.Connection, key: str, default=None):
    row = conn.execute("SELECT value FROM meta WHERE key=?", (key,)).fetchone()
    return row[0] if row else default


# ---------------------------------------------------------------- secid 映射
# smalltools secid: sh600519 / sz000338 / sh000001
# exe symbol:       600519_SH / 000338_SZ / INDEX_SH

def to_secid(symbol: str) -> str:
    """exe/显示 symbol → secid。支持 '600519_SH'/'sh600519'/'600519' 三种输入。"""
    s = symbol.strip()
    if s.lower().startswith(("sh", "sz", "bj")):
        return s.lower()
    if "_" in s:
        code, mkt = s.rsplit("_", 1)
        return mkt.lower() + code
    if s.startswith(("sh", "sz", "bj")):
        return s.lower()
    if s.startswith("6"):
        return "sh" + s
    if s.startswith(("4", "8")):
        return "bj" + s
    return "sz" + s


def from_secid(secid: str) -> str:
    """secid → exe symbol（000338_SZ）。指数 sh000001→INDEX_SH 不做特殊映射，保留原格式。"""
    s = secid.lower()
    if s.startswith(("sh", "sz", "bj")):
        return s[2:] + "_" + s[:2].upper()
    return s


# ---------------------------------------------------------------- K线

def upsert_kline(conn: sqlite3.Connection, secid: str, snaps: list, src: str = None,
                 name: str = None, commit: bool = True):
    """单只标的增量更新（按 (secid,date) 去重 upsert）。snaps 项: {date,open,close,high,low,volume,changePct,turnover}"""
    rows = [(
        secid, s["date"], s.get("open"), s.get("high"), s.get("low"), s.get("close"),
        s.get("volume"), s.get("changePct"), s.get("turnover"), src or "",
        name or "",
    ) for s in snaps]
    conn.executemany(
        """INSERT INTO kline(secid,date,open,high,low,close,volume,change_pct,turnover,src,name)
           VALUES(?,?,?,?,?,?,?,?,?,?,?)
           ON CONFLICT(secid,date) DO UPDATE SET
             open=excluded.open, high=excluded.high, low=excluded.low, close=excluded.close,
             volume=excluded.volume, change_pct=excluded.change_pct, turnover=excluded.turnover,
             src=excluded.src, name=excluded.name""",
        rows)
    if commit:
        conn.commit()


def import_kline_json(conn: sqlite3.Connection, cache_path: str = None, cache: dict = None):
    """全量导入 _kline_cache.json。返回 (标的数, 行数)。"""
    if cache is None:
        with open(cache_path or os.path.join(SMALLTOOLS_DIR, "_kline_cache.json"),
                  "r", encoding="utf-8") as f:
            cache = json.load(f)
    n_rows = 0
    for secid, ent in cache.items():
        snaps = ent.get("snaps") or []
        if not snaps:
            continue
        upsert_kline(conn, secid, snaps, src=ent.get("src"), name=ent.get("name"), commit=False)
        n_rows += len(snaps)
    conn.commit()
    return len(cache), n_rows


def query_kline(conn: sqlite3.Connection, secid: str, start: str = None, end: str = None,
                fields: tuple = ("date", "open", "high", "low", "close", "volume", "change_pct", "turnover")) -> list:
    """按 (secid, date) 升序查询，返回 dict 列表。"""
    sql = "SELECT %s FROM kline WHERE secid=?" % ", ".join(fields)
    args = [to_secid(secid)]
    if start:
        sql += " AND date>=?"
        args.append(start)
    if end:
        sql += " AND date<=?"
        args.append(end)
    sql += " ORDER BY date"
    return [dict(r) for r in conn.execute(sql, args).fetchall()]


def get_latest_date(conn: sqlite3.Connection) -> str:
    row = conn.execute("SELECT MAX(date) FROM kline").fetchone()
    return row[0] if row and row[0] else ""


def kline_stats(conn: sqlite3.Connection) -> dict:
    row = conn.execute(
        "SELECT COUNT(DISTINCT secid) AS stocks, COUNT(*) AS rows, MIN(date) AS d0, MAX(date) AS d1"
        " FROM kline").fetchone()
    return dict(row)


# ---------------------------------------------------------------- 公告

def import_announce_json(conn: sqlite3.Connection, cache_path: str = None, cache: dict = None):
    """全量导入 _announce_cache.json {secid:{name, items:[{date,title}]}}"""
    if cache is None:
        with open(cache_path or os.path.join(SMALLTOOLS_DIR, "_announce_cache.json"),
                  "r", encoding="utf-8") as f:
            cache = json.load(f)
    n = 0
    for secid, ent in cache.items():
        for it in (ent.get("items") or []):
            if not it.get("date") or not it.get("title"):
                continue
            conn.execute("INSERT OR IGNORE INTO announce(secid,date,title) VALUES(?,?,?)",
                         (secid, it["date"][:10], it["title"]))
            n += 1
    conn.commit()
    return len(cache), n


# ---------------------------------------------------------------- 新闻

def import_news_json(conn: sqlite3.Connection, cache_path: str = None, cache: dict = None):
    """全量导入 _news_cache.json {secid:{name, items:[{date,title,summary}]}}"""
    if cache is None:
        if cache_path is None:
            cache_path = os.path.join(SMALLTOOLS_DIR, "_news_cache.json")
        if not os.path.exists(cache_path):
            return 0, 0
        with open(cache_path, "r", encoding="utf-8") as f:
            cache = json.load(f)
    n = 0
    for secid, ent in cache.items():
        for it in (ent.get("items") or []):
            if not it.get("date") or not it.get("title"):
                continue
            conn.execute(
                "INSERT OR REPLACE INTO news(secid,date,title,summary) VALUES(?,?,?,?)",
                (secid, it["date"], it["title"], it.get("summary") or ""))
            n += 1
    conn.commit()
    return len(cache), n


# ---------------------------------------------------------------- 参数归档

def save_params(conn: sqlite3.Connection, scope: str, version: str, payload: dict,
                generated_at: str = None):
    """拟合参数归档。scope 如 'walk_forward'，version 如 '2026-08'。"""
    conn.execute(
        """INSERT INTO params(scope,version,generated_at,payload_json) VALUES(?,?,?,?)
           ON CONFLICT(scope,version) DO UPDATE SET
             generated_at=excluded.generated_at, payload_json=excluded.payload_json""",
        (scope, version, generated_at or datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
         json.dumps(payload, ensure_ascii=False)))
    conn.commit()


def get_params(conn: sqlite3.Connection, scope: str, version: str = None) -> dict:
    if version:
        row = conn.execute("SELECT payload_json FROM params WHERE scope=? AND version=?",
                           (scope, version)).fetchone()
    else:
        row = conn.execute("SELECT payload_json FROM params WHERE scope=? ORDER BY version DESC LIMIT 1",
                           (scope,)).fetchone()
    return json.loads(row[0]) if row else None


# ---------------------------------------------------------------- 便捷入口

def build_db(cache_path=None, announce_path=None, news_path=None, db_path=None):
    """一次性建库：导入 K线 + 公告 + 新闻，写库元信息。返回统计 dict。"""
    conn = get_conn(db_path)
    stats = {}
    stats["kline"] = import_kline_json(conn, cache_path=cache_path)
    stats["announce"] = import_announce_json(conn, cache_path=announce_path)
    stats["news"] = import_news_json(conn, cache_path=news_path)
    set_meta(conn, "schema_version", SCHEMA_VERSION)
    set_meta(conn, "latest_date", get_latest_date(conn))
    set_meta(conn, "built_at", datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"))
    conn.close()
    return stats


if __name__ == "__main__":
    import sys
    if "--build" in sys.argv:
        s = build_db()
        print("建库完成 → %s" % DB_PATH)
        print("  K线   : %d 只 / %d 行" % s["kline"])
        print("  公告  : %d 只 / %d 条" % s["announce"])
        print("  新闻  : %d 只 / %d 条" % s["news"])
    elif "--stats" in sys.argv:
        c = get_conn()
        print("库: %s" % DB_PATH)
        print("K线: %s" % kline_stats(c))
        print("公告: %s" % dict(c.execute("SELECT COUNT(DISTINCT secid), COUNT(*) FROM announce").fetchone()))
        print("新闻: %s" % dict(c.execute("SELECT COUNT(DISTINCT secid), COUNT(*) FROM news").fetchone()))
        c.close()
    else:
        print("用法: python _market_db.py --build | --stats")
