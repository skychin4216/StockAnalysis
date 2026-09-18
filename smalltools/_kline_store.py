# -*- coding: utf-8 -*-
"""K 线统一存储（2026-09-16 用户决策：删除 smalltools/_kline_cache.json，
全链路统一单一数据源 StockAnalysis/data/kline_store.json）。

背景：
  此前有两份不同步的 K 线缓存——
  - smalltools/_kline_cache.json（291 只，_update_cache_inc 保鲜，旧 ETF/机构池）
  - smalltools/data/_kline_pool_hist.json（434 只，_cybc_pool --history 产出）
  两池重合仅 37 只，exe 回测/守护读前者，双创核心池回测读后者，口径分裂。
  2026-09-16 合并为 data/kline_store.json（688 只全历史 2008 起），
  smalltools 只保留回溯拟合/选股思路，数据文件全部收敛到项目 data/。

用法（所有 smalltools/AutoQuant 脚本统一 import 本模块，禁止再各自拼路径）：
    from _kline_store import store_path, load_store

    path = store_path()      # str，绝对路径（frozen/源码双态）
    cache = load_store()     # dict {secid: {name, industry?, src?, snaps:[...]}}
                             # snaps 字段 date/open/close/high/low/volume/
                             #        changePct/turnover（两旧格式完全一致）

写入方（保持唯一性，勿另开新写端）：
  - 日常保鲜：smalltools/_update_cache_inc.py（增量补当日根，daemon/exe 保鲜线程）
  - 月度池扩展：smalltools/_cybc_pool.py --history（merge 模式，只增不删）
"""
import json
import os
import sys


def store_path() -> str:
    """返回统一存储绝对路径（frozen/源码双态感知）。

    frozen(PyInstaller onefile)：与 _market_db 同一模式（2026-09-15 保鲜修复）——
    `__file__` 指向 _MEIPASS 临时目录，退出即焚，不能当持久数据位置。
    持久存储放 exe 同级 data/kline_store.json：
      · exe 旁已有 → 直接用（保鲜线程增量更新的持久文件）；
      · 首次运行 → 把包内种子(_MEIPASS/data/)复制到 exe 旁，之后写读到持久位。
    源码态：仓库 data/kline_store.json。
    """
    if getattr(sys, "frozen", False):
        exe_dir = os.path.dirname(os.path.abspath(sys.executable))
        persistent = os.path.join(exe_dir, "data", "kline_store.json")
        if os.path.isfile(persistent):
            return persistent
        bundled = os.path.join(getattr(sys, "_MEIPASS", exe_dir),
                               "data", "kline_store.json")
        if os.path.isfile(bundled):
            try:
                import shutil
                os.makedirs(os.path.dirname(persistent), exist_ok=True)
                shutil.copy2(bundled, persistent)
            except OSError:
                return bundled          # 只读安装目录等场景：退化为包内只读
        return persistent               # 均无：返回目标路径（写端自行创建）
    here = os.path.dirname(os.path.abspath(__file__))
    return os.path.normpath(os.path.join(here, "..", "data", "kline_store.json"))


_STORE_MEMO = {"data": None, "mtime": 0.0}  # 2026-09-16 加：守护一轮多次 load_store 时复用


def load_store() -> dict:
    """加载统一存储。文件不存在时抛 FileNotFoundError（调用方显式处理）。

    2026-09-16 优化：mtime 单例缓存——同进程内多次调用复用上次解析结果，
    当文件被 _update_cache_inc 增量写后自动失效（mtime 变化触发重载）。
    守护一轮推送可能 7-8 个工具各自 load_store，原每次 ~13 秒，
    现在第二次起 ≈0 秒（JSON 内存对象复用）。
    """
    path = store_path()
    try:
        mtime = os.path.getmtime(path)
    except OSError:
        mtime = 0.0
    if _STORE_MEMO["data"] is not None and _STORE_MEMO["mtime"] == mtime:
        return _STORE_MEMO["data"]
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    _STORE_MEMO["data"] = data
    _STORE_MEMO["mtime"] = mtime
    return data


def save_store(data: dict) -> None:
    """原子落盘统一存储（tmp + replace，供写端使用）。"""
    import tempfile
    path = store_path()
    os.makedirs(os.path.dirname(path), exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path),
                               prefix=".kline_store_", suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            try:
                os.remove(tmp)
            except OSError:
                pass
    # 2026-09-16：主动失效 memo——若写盘与 load_store 落在同一 mtime 刻度
    # （低精度文件系统），仅靠 mtime 对比会误判"文件未变"而返回旧数据。
    # 写端明确写入的是最新内存对象，直接用它刷新缓存。
    _STORE_MEMO["data"] = data
    try:
        _STORE_MEMO["mtime"] = os.path.getmtime(path)
    except OSError:
        _STORE_MEMO["data"] = None
        _STORE_MEMO["mtime"] = 0.0


if __name__ == "__main__":
    p = store_path()
    print("store_path:", p)
    print("exists:", os.path.isfile(p))
    if os.path.isfile(p):
        d = load_store()
        print("股票数:", len(d))
