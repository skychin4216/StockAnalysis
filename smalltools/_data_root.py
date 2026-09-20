# -*- coding: utf-8 -*-
"""统一数据根目录（2026-09-19：让 exe / smalltools / PC 守护共用同一份数据）。

规则（与 `_kline_store.store_path()` 同模式，两者必须保持一致）：
  · 源码态（python 脚本 / 盘段守护）→ 仓库根 `data/`
  · frozen 态（PyInstaller 打包的 exe）→ exe 同级 `data/`
  · 环境变量 `STOCKANALYSIS_DATA_DIR` 可覆盖（自定义部署 / 多实例隔离）

用法：
    from _data_root import data_dir
    path = os.path.join(data_dir(), "_daily_intel.json")

背景：此前 `smalltools/data/` 与仓库根 `data/` 各存一份（其中 `_kline_cybc.json`
39MB 是 K 线源统一后遗留的废弃缓存）。新代码一律走本入口，避免再产生第二份；
`smalltools/data/` 仅作历史兼容保留。
"""
import os
import sys


def data_dir():
    """返回数据目录绝对路径（不存在时自动创建）。"""
    env = os.environ.get("STOCKANALYSIS_DATA_DIR")
    if env:
        d = env
    elif getattr(sys, "frozen", False):
        d = os.path.join(os.path.dirname(sys.executable), "data")
    else:
        d = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "data")
    try:
        os.makedirs(d, exist_ok=True)
    except OSError:
        pass
    return d


def data_path(*parts):
    """便捷：data_dir() + 子路径。"""
    return os.path.join(data_dir(), *parts)


if __name__ == "__main__":
    print(data_dir())
