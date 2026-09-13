#!/usr/bin/env python3
"""
Architecture Scanner — 掃描代碼架構並與上次快照對比，輸出變更摘要。

用法:
  python scan_architecture.py                  # 掃描並對比，輸出變更摘要
  python scan_architecture.py --save-snapshot   # 掃描並保存新快照
  python scan_architecture.py --json            # 以 JSON 格式輸出
"""

import os
import re
import json
import sys
from pathlib import Path
from datetime import datetime

# 項目根目錄
PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
JAVA_ROOT = PROJECT_ROOT / "app" / "src" / "main" / "java" / "com" / "chin" / "stockanalysis"
ASSETS_ROOT = PROJECT_ROOT / "app" / "src" / "main" / "assets"
SNAPSHOT_FILE = PROJECT_ROOT / "docs" / "architecture" / ".arch_snapshot.json"


def scan_fragments():
    """掃描所有 Fragment 類"""
    ui_dir = JAVA_ROOT / "ui"
    strategy_trade_dir = JAVA_ROOT / "strategy" / "trade"
    fragments = []

    for d in [ui_dir, strategy_trade_dir]:
        if not d.exists():
            continue
        for f in d.glob("*.kt"):
            content = f.read_text(encoding="utf-8", errors="ignore")
            if re.search(r'class\s+\w+\s*:\s*Fragment', content) or 'Fragment()' in content:
                class_match = re.search(r'class\s+(\w+)', content)
                if class_match:
                    fragments.append({
                        "name": class_match.group(1),
                        "file": str(f.relative_to(PROJECT_ROOT)),
                    })
    return sorted(fragments, key=lambda x: x["name"])


def scan_agents():
    """掃描所有 Agent 類"""
    agent_dir = JAVA_ROOT / "agent"
    agents = []

    for f in agent_dir.rglob("*.kt"):
        content = f.read_text(encoding="utf-8", errors="ignore")
        class_matches = re.findall(r'class\s+(\w+Agent)\b', content)
        for name in class_matches:
            agents.append({
                "name": name,
                "file": str(f.relative_to(PROJECT_ROOT)),
            })

    # 也掃描 Agent 角色定義
    roles_file = agent_dir / "core" / "AgentRole.kt"
    roles = []
    if roles_file.exists():
        content = roles_file.read_text(encoding="utf-8", errors="ignore")
        roles = re.findall(r'(ORCHESTRATOR|SCOUT|ANALYST|GUARDIAN|EXECUTOR)', content)
        roles = sorted(set(roles))

    return sorted(agents, key=lambda x: x["name"]), roles


def scan_strategies():
    """掃描所有策略類"""
    strat_dir = JAVA_ROOT / "strategy" / "strategies"
    strategies = []

    if not strat_dir.exists():
        return strategies

    for f in strat_dir.glob("*.kt"):
        content = f.read_text(encoding="utf-8", errors="ignore")
        class_match = re.search(r'class\s+(\w+Strategy)\b', content)
        id_match = re.search(r'override\s+val\s+strategyId\s*=\s*"([^"]+)"', content)
        if class_match:
            strategies.append({
                "name": class_match.group(1),
                "id": id_match.group(1) if id_match else "unknown",
                "file": str(f.relative_to(PROJECT_ROOT)),
            })
    return sorted(strategies, key=lambda x: x["name"])


def scan_pipeline_nodes():
    """掃描所有 Pipeline 節點"""
    nodes_dir = JAVA_ROOT / "strategy" / "topology" / "nodes"
    nodes = []

    if not nodes_dir.exists():
        return nodes

    for f in nodes_dir.glob("*.kt"):
        content = f.read_text(encoding="utf-8", errors="ignore")
        class_matches = re.findall(r'class\s+(\w+Node)\b', content)
        for name in class_matches:
            nodes.append({
                "name": name,
                "file": str(f.relative_to(PROJECT_ROOT)),
            })
    return sorted(nodes, key=lambda x: x["name"])


def scan_data_sources():
    """掃描所有數據源"""
    sources_dir = JAVA_ROOT / "stock" / "data" / "sources"
    sources = []

    if not sources_dir.exists():
        return sources

    for f in sources_dir.glob("*.kt"):
        content = f.read_text(encoding="utf-8", errors="ignore")
        class_match = re.search(r'class\s+(\w+(?:Source|Fetcher))\b', content)
        if class_match:
            sources.append({
                "name": class_match.group(1),
                "file": str(f.relative_to(PROJECT_ROOT)),
            })
    return sorted(sources, key=lambda x: x["name"])


def scan_intent_handlers():
    """掃描 Intent Processor Chain handlers"""
    intent_dir = JAVA_ROOT / "stock" / "intent" / "handlers"
    handlers = []

    if not intent_dir.exists():
        return handlers

    for f in intent_dir.glob("*.kt"):
        content = f.read_text(encoding="utf-8", errors="ignore")
        class_match = re.search(r'class\s+(\w+Handler)\b', content)
        if class_match:
            handlers.append({
                "name": class_match.group(1),
                "file": str(f.relative_to(PROJECT_ROOT)),
            })
    return sorted(handlers, key=lambda x: x["name"])


def scan_database():
    """掃描數據庫 Entity 和版本"""
    db_file = JAVA_ROOT / "stock" / "database" / "StockDatabase.kt"
    entities = []
    version = "unknown"

    if db_file.exists():
        content = db_file.read_text(encoding="utf-8", errors="ignore")
        # 提取 @Entity 註解的類
        entity_matches = re.findall(r'@Entity\(.*?\).*?class\s+(\w+)', content, re.DOTALL)
        entities = sorted(set(entity_matches))
        # 提取版本號
        version_match = re.search(r'version\s*=\s*(\d+)', content)
        if version_match:
            version = version_match.group(1)

    # 也掃描其他目錄中的 Entity
    for pattern in ["**/*Entity.kt", "**/*Entities.kt"]:
        for f in JAVA_ROOT.glob(pattern):
            content = f.read_text(encoding="utf-8", errors="ignore")
            class_matches = re.findall(r'@Entity\(.*?\).*?class\s+(\w+)', content, re.DOTALL)
            if not class_matches:
                class_matches = re.findall(r'data class\s+(\w+Entity)\b', content)
            entities.extend(class_matches)

    entities = sorted(set(entities))
    return entities, version


def scan_v2_components():
    """掃描 V2 決策矩陣組件"""
    v2_dir = JAVA_ROOT / "agent" / "v2"
    components = []

    if not v2_dir.exists():
        return components

    for f in v2_dir.glob("*.kt"):
        content = f.read_text(encoding="utf-8", errors="ignore")
        class_match = re.search(r'class\s+(\w+)\b', content)
        if class_match:
            components.append({
                "name": class_match.group(1),
                "file": str(f.relative_to(PROJECT_ROOT)),
            })
    return sorted(components, key=lambda x: x["name"])


def scan_xml_configs():
    """掃描 XML Pipeline 配置文件"""
    usecases_dir = ASSETS_ROOT / "usecases"
    configs = []

    if not usecases_dir.exists():
        return configs

    for f in usecases_dir.iterdir():
        if f.suffix in ['.xml', '.json']:
            configs.append(f.name)
    return sorted(configs)


def scan_backtest():
    """掃描回測引擎組件"""
    bt_dir = JAVA_ROOT / "strategy" / "backtest"
    components = []

    if not bt_dir.exists():
        return components

    for f in bt_dir.glob("*.kt"):
        content = f.read_text(encoding="utf-8", errors="ignore")
        class_match = re.search(r'class\s+(\w+)\b', content)
        if class_match and not class_match.group(1).endswith('Entity') and not class_match.group(1).endswith('Dao'):
            components.append(class_match.group(1))
    return sorted(set(components))


def scan_all():
    """執行完整掃描"""
    fragments = scan_fragments()
    agents, roles = scan_agents()
    strategies = scan_strategies()
    pipeline_nodes = scan_pipeline_nodes()
    data_sources = scan_data_sources()
    intent_handlers = scan_intent_handlers()
    db_entities, db_version = scan_database()
    v2_components = scan_v2_components()
    xml_configs = scan_xml_configs()
    backtest_components = scan_backtest()

    return {
        "scan_time": datetime.now().isoformat(),
        "summary": {
            "fragments": len(fragments),
            "agents": len(agents),
            "agent_roles": len(roles),
            "strategies": len(strategies),
            "pipeline_nodes": len(pipeline_nodes),
            "data_sources": len(data_sources),
            "intent_handlers": len(intent_handlers),
            "db_entities": len(db_entities),
            "db_version": db_version,
            "v2_components": len(v2_components),
            "xml_configs": len(xml_configs),
            "backtest_components": len(backtest_components),
        },
        "details": {
            "fragments": fragments,
            "agents": agents,
            "agent_roles": roles,
            "strategies": strategies,
            "pipeline_nodes": pipeline_nodes,
            "data_sources": data_sources,
            "intent_handlers": intent_handlers,
            "db_entities": db_entities,
            "db_version": db_version,
            "v2_components": v2_components,
            "xml_configs": xml_configs,
            "backtest_components": backtest_components,
        }
    }


def compare_snapshots(old, new):
    """對比新舊快照，輸出變更摘要"""
    changes = []
    old_d = old.get("details", {}) if old else {}
    new_d = new.get("details", {})

    for key in new_d:
        old_val = old_d.get(key, [])
        new_val = new_d.get(key, [])

        if key in ["db_version"]:
            if old_val != new_val:
                changes.append(f"  [Database] 版本變更: {old_val} → {new_val}")
            continue

        # 對於列表類型，比較名稱
        old_names = set()
        new_names = set()

        if isinstance(old_val, list):
            for item in old_val:
                if isinstance(item, dict):
                    old_names.add(item.get("name", str(item)))
                else:
                    old_names.add(str(item))
        elif old_val:
            old_names.add(str(old_val))

        if isinstance(new_val, list):
            for item in new_val:
                if isinstance(item, dict):
                    new_names.add(item.get("name", str(item)))
                else:
                    new_names.add(str(item))
        elif new_val:
            new_names.add(str(new_val))

        added = new_names - old_names
        removed = old_names - new_names

        if added or removed:
            layer_map = {
                "fragments": "UI 層",
                "agents": "Agent 層",
                "agent_roles": "Agent 層",
                "strategies": "Strategy 層",
                "pipeline_nodes": "Strategy 層",
                "data_sources": "Data 層",
                "intent_handlers": "Data 層",
                "db_entities": "Database 層",
                "v2_components": "Agent 層",
                "xml_configs": "Strategy 層",
                "backtest_components": "Strategy 層",
            }
            layer = layer_map.get(key, "未知")

            if added:
                changes.append(f"  [{layer}] 新增 {key}: {', '.join(sorted(added))}")
            if removed:
                changes.append(f"  [{layer}] 刪除 {key}: {', '.join(sorted(removed))}")

    return changes


def main():
    save_snapshot = "--save-snapshot" in sys.argv
    output_json = "--json" in sys.argv

    # 執行掃描
    current = scan_all()

    if output_json:
        print(json.dumps(current, ensure_ascii=False, indent=2))
        return

    # 加載舊快照
    old_snapshot = None
    if SNAPSHOT_FILE.exists():
        try:
            old_snapshot = json.loads(SNAPSHOT_FILE.read_text(encoding="utf-8"))
        except Exception:
            pass

    # 輸出掃描結果
    s = current["summary"]
    print("=" * 60)
    print("StockAnalysis 架構掃描結果")
    print(f"掃描時間: {current['scan_time']}")
    print("=" * 60)
    print(f"  Fragment 數量:     {s['fragments']}")
    print(f"  Agent 數量:        {s['agents']}")
    print(f"  Agent 角色數:      {s['agent_roles']}")
    print(f"  Strategy 數量:     {s['strategies']}")
    print(f"  Pipeline Node 數:  {s['pipeline_nodes']}")
    print(f"  Data Source 數:    {s['data_sources']}")
    print(f"  Intent Handler 數: {s['intent_handlers']}")
    print(f"  DB Entity 數:      {s['db_entities']}")
    print(f"  DB 版本:           v{s['db_version']}")
    print(f"  V2 組件數:         {s['v2_components']}")
    print(f"  XML 配置數:        {s['xml_configs']}")
    print(f"  回測組件數:        {s['backtest_components']}")

    # 對比快照
    if old_snapshot:
        changes = compare_snapshots(old_snapshot, current)
        print("\n" + "=" * 60)
        print("架構變更摘要 (與上次快照對比)")
        print("=" * 60)
        if changes:
            for c in changes:
                print(c)
            print(f"\n共 {len(changes)} 處變更，需要更新 docs/architecture/index.html")
        else:
            print("  無架構變更")
    else:
        print("\n  (首次掃描，無舊快照可對比)")

    # 保存快照
    if save_snapshot:
        SNAPSHOT_FILE.parent.mkdir(parents=True, exist_ok=True)
        SNAPSHOT_FILE.write_text(
            json.dumps(current, ensure_ascii=False, indent=2),
            encoding="utf-8"
        )
        print(f"\n快照已保存到: {SNAPSHOT_FILE.relative_to(PROJECT_ROOT)}")


if __name__ == "__main__":
    main()
