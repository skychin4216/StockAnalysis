# -*- coding: utf-8 -*-
"""
AutoQuant 1:1 同步 · 第①步 参数对齐
把 _dag_node_params.py（DAG 节点参数事实源）写入
app/src/main/assets/backtest_params.json 的 dag_nodes 节，
使 Android 与 smalltools 共享同一份节点参数。

用法：
    python _export_dag_params.py
"""
import json
import os
import sys
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _dag_node_params as p  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TARGET = os.path.join(ROOT, "app", "src", "main", "assets", "backtest_params.json")


def main():
    with open(TARGET, "r", encoding="utf-8") as f:
        data = json.load(f)

    data["dag_nodes"] = {
        "version": 1,
        "generated": datetime.now().strftime("%Y-%m-%d %H:%M"),
        "source": "AutoQuant _dag_node_params.py (Kotlin 节点硬编码 + pipeline XML config 镜像)",
        "common": p.COMMON,
        "strict_selection_ref": p.STRICT_SELECTION_REF,
        "node_params": p.NODE_PARAMS,
        "data_gated_nodes": p.DATA_GATED_NODES,
    }

    with open(TARGET, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)

    n_nodes = len(p.NODE_PARAMS)
    n_gated = len(p.DATA_GATED_NODES)
    print(f"OK: dag_nodes 已写入 {TARGET}")
    print(f"    节点参数 {n_nodes} 组, 数据敏感节点 {n_gated} 个(留接口跳过)")
    print(f"    common: {list(p.COMMON.keys())}")
    print(f"    严格选股参数引用: {p.STRICT_SELECTION_REF}")


if __name__ == "__main__":
    main()
