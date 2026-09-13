#!/usr/bin/env python3
"""
StockAnalysis 項目流程圖生成腳本
使用 pipeline_visualizer 工具庫生成所有 Pipeline 和架構圖

生成內容：
  1. 四個週期（超短/短/中/長線）的 Pipeline 流程圖（SVG + Mermaid）
  2. 整個項目架構流程圖（8 層架構，SVG + Mermaid）
  3. 四個週期對比圖（節點差異對比，SVG + Mermaid）

輸出目錄：smalltools/output/
"""
import os
import sys

# 將當前目錄加入 path，確保可以 import pipeline_visualizer
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pipeline_visualizer import (
    PipelineVisualizer,
    ArchitectureDiagramBuilder,
    NodeType,
    PipelineNode,
    PipelineLink,
    PipelineGraph,
)

# ---------------------------------------------------------------------------
# 路徑定義
# ---------------------------------------------------------------------------
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
USECASES_DIR = os.path.join(PROJECT_ROOT, "app", "src", "main", "assets", "usecases")
OUTPUT_DIR = os.path.join(SCRIPT_DIR, "output")

# ---------------------------------------------------------------------------
# 四個週期 Pipeline 配置
# ---------------------------------------------------------------------------
PIPELINE_CONFIGS = [
    {
        "xml": "ultra_short_pipeline.xml",
        "svg": "ultra_short_pipeline.svg",
        "mermaid": "ultra_short_pipeline.mermaid",
        "title": "超短線量化 DAG Pipeline（持倉1天，純技術面，快進快出）",
    },
    {
        "xml": "short_term_pipeline.xml",
        "svg": "short_term_pipeline.svg",
        "mermaid": "short_term_pipeline.mermaid",
        "title": "短線量化 DAG Pipeline（持倉3-5天，熱點+輪動+新聞情緒）",
    },
    {
        "xml": "mid_term_pipeline.xml",
        "svg": "mid_term_pipeline.svg",
        "mermaid": "mid_term_pipeline.mermaid",
        "title": "中線量化 DAG Pipeline（持倉1-2週，板塊+跨日聚合+防禦分紅）",
    },
    {
        "xml": "long_term_pipeline.xml",
        "svg": "long_term_pipeline.svg",
        "mermaid": "long_term_pipeline.mermaid",
        "title": "長線量化 DAG Pipeline（持倉2週以上，板塊+防禦分紅，無騰龍換鳥）",
    },
]


# ===========================================================================
# 1. 四個週期 Pipeline 流程圖
# ===========================================================================
def generate_pipeline_diagrams():
    """從 XML 文件解析並生成四個週期的 Pipeline 流程圖（SVG + Mermaid）"""
    print("=" * 70)
    print("【1/3】生成四個週期 Pipeline 流程圖")
    print("=" * 70)

    visualizer = PipelineVisualizer()

    for cfg in PIPELINE_CONFIGS:
        xml_path = os.path.join(USECASES_DIR, cfg["xml"])
        if not os.path.exists(xml_path):
            print(f"\n[警告] 找不到 XML 文件: {xml_path}，跳過")
            continue

        print(f"\n--- 處理: {cfg['title']} ---")
        print(f"    源文件: {xml_path}")

        # 解析 XML
        graph = visualizer.parse_file(xml_path)
        print(f"    解析完成: {len(graph.nodes)} 個節點, {len(graph.links)} 條連線")

        # 生成 SVG
        svg_path = os.path.join(OUTPUT_DIR, cfg["svg"])
        visualizer.render_svg(graph, svg_path, title=cfg["title"])
        print(f"    -> SVG 已生成: {svg_path}")

        # 生成 Mermaid
        mermaid_str = visualizer.render_mermaid(graph)
        mermaid_path = os.path.join(OUTPUT_DIR, cfg["mermaid"])
        with open(mermaid_path, "w", encoding="utf-8") as f:
            f.write(mermaid_str)
        print(f"    -> Mermaid 已生成: {mermaid_path}")

    print("\n[完成] 四個週期 Pipeline 流程圖生成完畢\n")


# ===========================================================================
# 2. 整個項目架構流程圖
# ===========================================================================
def generate_project_architecture():
    """用 ArchitectureDiagramBuilder 手動構建項目完整架構流程圖（8 層）"""
    print("=" * 70)
    print("【2/3】生成項目架構流程圖（8 層）")
    print("=" * 70)

    builder = ArchitectureDiagramBuilder()

    # ------------------------------------------------------------------
    # Layer 0: 數據採集層
    # ------------------------------------------------------------------
    builder.add_node("stock_screener", "StockScreener\n(全市場掃描)", NodeType.DATA_SOURCE, layer=0)
    builder.add_node("historical_fetcher", "HistoricalDataFetcher\n(歷史數據導入)", NodeType.DATA_SOURCE, layer=0)
    builder.add_node("multi_source_repo", "MultiSourceStockRepository\n(多源數據)", NodeType.DATA_SOURCE, layer=0)

    # ------------------------------------------------------------------
    # Layer 1: 策略分析層
    # ------------------------------------------------------------------
    builder.add_node("strategy_engine", "StrategyEngine\n(策略引擎)", NodeType.AGGREGATION, layer=1)
    builder.add_node("strategies", "20+ Strategies\n(20+策略並發)", NodeType.STRATEGY, layer=1)
    builder.add_node("market_analyzer", "MarketAnalyzer\n(市場環境分析)", NodeType.ENRICHMENT, layer=1)

    # ------------------------------------------------------------------
    # Layer 2: DAG Pipeline 層
    # ------------------------------------------------------------------
    builder.add_node("usecase_loader", "UseCaseLoader\n(UseCase加載)", NodeType.DATA_TRANSFORM, layer=2)
    builder.add_node("dag_pipeline", "DagPipeline\n(Kahn拓撲排序)", NodeType.AGGREGATION, layer=2)
    builder.add_node("node_registry", "NodeRegistry\n(節點工廠)", NodeType.DATA_TRANSFORM, layer=2)
    builder.add_node("pipeline_nodes", "20+ Pipeline Nodes\n(20+節點)", NodeType.FACTOR_COMPUTE, layer=2)

    # ------------------------------------------------------------------
    # Layer 3: 交易執行層
    # ------------------------------------------------------------------
    builder.add_node("dag_trade_executor", "DagTradeExecutor\n(DAG交易執行器)", NodeType.TRADE_ACTION, layer=3)
    builder.add_node("generate_orders", "GenerateOrdersNode\n(訂單生成)", NodeType.TRADE_ACTION, layer=3)
    builder.add_node("swap_weak", "SwapWeakNode\n(騰龍換鳥)", NodeType.TRADE_ACTION, layer=3)

    # ------------------------------------------------------------------
    # Layer 4: 持倉管理層
    # ------------------------------------------------------------------
    builder.add_node("quant_fragment_base", "QuantFragmentBase\n(持倉管理基類)", NodeType.DATA_TRANSFORM, layer=4)
    builder.add_node("auto_sell_engine", "AutoSellEngine\n(智能賣出引擎10策略)", NodeType.FILTER, layer=4)
    builder.add_node("period_holding_profit", "PeriodHoldingProfitEntity\n(各週期收益固化)", NodeType.DATA_TRANSFORM, layer=4)

    # ------------------------------------------------------------------
    # Layer 5: AI 輔助層（並行）
    # ------------------------------------------------------------------
    builder.add_node("ai_orchestrator", "AiOrchestrator\n(AI編排)", NodeType.AI_PREDICTION, layer=5)
    builder.add_node("agent_orchestrator", "AgentOrchestrator\n(多智能體)", NodeType.AI_PREDICTION, layer=5)
    builder.add_node("stock_picking_agent", "StockPickingAgent\n(選股Agent)", NodeType.AI_PREDICTION, layer=5)

    # ------------------------------------------------------------------
    # Layer 6: 數據庫層
    # ------------------------------------------------------------------
    builder.add_node("stock_database", "StockDatabase\n(Room DB v14)", NodeType.DATA_SOURCE, layer=6)
    builder.add_node("db_tables", "14 張表\n(strategy_trade_orders,\ndaily_snapshot,\nperiod_holding_profit 等)", NodeType.DATA_SOURCE, layer=6)

    # ------------------------------------------------------------------
    # Layer 7: UI 層
    # ------------------------------------------------------------------
    builder.add_node("main_activity", "MainActivity\n(主界面)", NodeType.DATA_TRANSFORM, layer=7)
    builder.add_node("quant_fragments", "4個 QuantFragment\n(超短/短/中/長線)", NodeType.DATA_TRANSFORM, layer=7)
    builder.add_node("topology_editor", "TopologyEditorActivity\n(拓撲編輯器)", NodeType.DATA_TRANSFORM, layer=7)

    # ------------------------------------------------------------------
    # 連線關係
    # ------------------------------------------------------------------
    # 數據採集 → 策略分析
    builder.add_edge("stock_screener", "strategy_engine", "全市場掃描結果")
    builder.add_edge("historical_fetcher", "stock_database", "歷史數據導入")
    builder.add_edge("multi_source_repo", "strategy_engine", "多源數據匯入")

    # 策略分析 → DAG Pipeline
    builder.add_edge("strategy_engine", "usecase_loader", "策略信號")
    builder.add_edge("strategies", "strategy_engine", "策略並發結果")
    builder.add_edge("market_analyzer", "dag_pipeline", "市場環境上下文")

    # DAG Pipeline 內部
    builder.add_edge("usecase_loader", "dag_pipeline", "UseCase 配置")
    builder.add_edge("node_registry", "dag_pipeline", "節點工廠實例化")
    builder.add_edge("pipeline_nodes", "dag_pipeline", "因子計算結果")

    # DAG Pipeline → 交易執行
    builder.add_edge("dag_pipeline", "dag_trade_executor", "Pipeline 執行結果")
    builder.add_edge("dag_trade_executor", "generate_orders", "觸發訂單生成")
    builder.add_edge("dag_trade_executor", "swap_weak", "持倉滿時換鳥")

    # 交易執行 → 持倉管理
    builder.add_edge("dag_trade_executor", "quant_fragment_base", "交易結果入持倉")
    builder.add_edge("generate_orders", "stock_database", "訂單持久化")

    # 持倉管理 → 賣出引擎
    builder.add_edge("quant_fragment_base", "auto_sell_engine", "持倉委託賣出評估")

    # 賣出引擎 → 數據庫
    builder.add_edge("auto_sell_engine", "stock_database", "賣出訂單持久化")

    # AI 輔助 → 策略分析
    builder.add_edge("ai_orchestrator", "strategy_engine", "AI 策略增強")
    builder.add_edge("agent_orchestrator", "stock_picking_agent", "多智能體調度")

    # AI 輔助 → DAG Pipeline（交易執行）
    builder.add_edge("stock_picking_agent", "dag_trade_executor", "AI 選股結果注入")

    # 數據庫 → 持倉管理
    builder.add_edge("stock_database", "quant_fragment_base", "持倉數據讀取")
    builder.add_edge("stock_database", "db_tables", "表結構映射")

    # 數據庫 → UI
    builder.add_edge("stock_database", "main_activity", "UI 數據源")
    builder.add_edge("stock_database", "quant_fragments", "各週期持倉展示")

    # UI → 持倉管理
    builder.add_edge("main_activity", "quant_fragment_base", "界面觸發持倉操作")
    builder.add_edge("quant_fragments", "quant_fragment_base", "Fragment 持倉交互")
    builder.add_edge("quant_fragment_base", "topology_editor", "打開拓撲編輯")

    # 持倉管理 → 收益固化
    builder.add_edge("quant_fragment_base", "period_holding_profit", "週期結算收益固化")

    # 收益固化 → 數據庫
    builder.add_edge("period_holding_profit", "stock_database", "收益數據持久化")

    # ------------------------------------------------------------------
    # 構建並渲染
    # ------------------------------------------------------------------
    graph = builder.build()

    svg_path = os.path.join(OUTPUT_DIR, "project_architecture.svg")
    visualizer = PipelineVisualizer()
    visualizer.render_svg(graph, svg_path, title="StockAnalysis 項目架構流程圖（8層）")
    print(f"\n  -> SVG 已生成:  {svg_path}")

    mermaid_str = visualizer.render_mermaid(graph)
    mermaid_path = os.path.join(OUTPUT_DIR, "project_architecture.mermaid")
    with open(mermaid_path, "w", encoding="utf-8") as f:
        f.write(mermaid_str)
    print(f"  -> Mermaid 已生成: {mermaid_path}")

    print(f"\n  架構節點數: {len(graph.nodes)}")
    print(f"  架構連線數: {len(graph.links)}")
    print("\n[完成] 項目架構流程圖生成完畢\n")


# ===========================================================================
# 3. 四個週期對比圖
# ===========================================================================
def generate_pipeline_comparison():
    """構建四個週期節點差異對比圖（SVG + Mermaid）"""
    print("=" * 70)
    print("【3/3】生成四週期對比圖")
    print("=" * 70)

    # 四個週期的對比數據
    CYCLE_DATA = [
        {
            "id": "ultra_short",
            "name": "超短線 Pipeline",
            "desc": "持倉1天 · 純技術面 · 快進快出",
            "node_count": 20,
            "unique_nodes": [
                ("t1_auto_sell", "T+1自動賣出"),
                ("candle_pattern", "K線形態偵測"),
                ("inst_tips", "機構線索加分"),
            ],
        },
        {
            "id": "short_term",
            "name": "短線 Pipeline",
            "desc": "持倉3-5天 · 熱點+輪動+新聞",
            "node_count": 22,
            "unique_nodes": [
                ("multi_period_hot", "多週期熱點"),
                ("heat_score", "熱度評分"),
                ("zipline_factor", "Zipline因子"),
                ("news_strength", "新聞強度"),
                ("rotation_penalty", "輪動懲罰"),
            ],
        },
        {
            "id": "mid_term",
            "name": "中線 Pipeline",
            "desc": "持倉1-2週 · 板塊+跨日聚合",
            "node_count": 20,
            "unique_nodes": [
                ("sector_stock_pool", "板塊股票池"),
                ("cross_day_aggregation", "跨日聚合"),
                ("defensive_dividend", "防禦分紅"),
            ],
        },
        {
            "id": "long_term",
            "name": "長線 Pipeline",
            "desc": "持倉2週+ · 板塊+防禦 · 無騰龍換鳥",
            "node_count": 19,
            "unique_nodes": [
                ("sector_stock_pool", "板塊股票池"),
                ("defensive_dividend", "防禦分紅"),
            ],
            "missing": ["swap_weak"],
        },
    ]

    graph = PipelineGraph(
        id="pipeline_comparison",
        name="四週期 Pipeline 對比圖",
        description="超短/短/中/長線 Pipeline 節點數量與獨有節點對比",
    )

    # 為每個週期添加一個匯總節點 + 獨有節點
    for cycle in CYCLE_DATA:
        cycle_id = cycle["id"]
        # 匯總節點：顯示週期名稱 + 節點數
        summary_name = (
            f"{cycle['name']}\n"
            f"{cycle['desc']}\n"
            f"節點數: {cycle['node_count']}"
        )
        summary_node = PipelineNode(
            id=cycle_id,
            name=summary_name,
            module=cycle_id,
            node_type=NodeType.AGGREGATION,
        )
        graph.nodes.append(summary_node)

        # 獨有節點
        for uid, uname in cycle["unique_nodes"]:
            unique_node = PipelineNode(
                id=f"{cycle_id}_{uid}",
                name=f"[獨有] {uname}",
                module=uid,
                node_type=NodeType.FACTOR_COMPUTE,
            )
            graph.nodes.append(unique_node)
            graph.links.append(
                PipelineLink(source=cycle_id, target=f"{cycle_id}_{uid}", label="獨有節點")
            )

        # 缺失節點（如長線無 swap_weak）
        missing_list = cycle.get("missing", [])
        for mid in missing_list:
            missing_node = PipelineNode(
                id=f"{cycle_id}_no_{mid}",
                name=f"[缺失] {mid}",
                module=f"no_{mid}",
                node_type=NodeType.FILTER,
            )
            graph.nodes.append(missing_node)
            graph.links.append(
                PipelineLink(source=cycle_id, target=f"{cycle_id}_no_{mid}", label="無此節點")
            )

    # 渲染
    visualizer = PipelineVisualizer()

    svg_path = os.path.join(OUTPUT_DIR, "pipeline_comparison.svg")
    visualizer.render_svg(graph, svg_path, title="四週期 Pipeline 節點對比圖")
    print(f"\n  -> SVG 已生成:  {svg_path}")

    mermaid_str = visualizer.render_mermaid(graph)
    mermaid_path = os.path.join(OUTPUT_DIR, "pipeline_comparison.mermaid")
    with open(mermaid_path, "w", encoding="utf-8") as f:
        f.write(mermaid_str)
    print(f"  -> Mermaid 已生成: {mermaid_path}")

    # 打印對比摘要
    print("\n  --- 四週期對比摘要 ---")
    for cycle in CYCLE_DATA:
        unique_str = ", ".join(u[0] for u in cycle["unique_nodes"])
        missing_str = ", ".join(cycle.get("missing", []))
        line = f"  {cycle['name']}: {cycle['node_count']}節點 | 獨有: {unique_str}"
        if missing_str:
            line += f" | 無: {missing_str}"
        print(line)

    print("\n[完成] 四週期對比圖生成完畢\n")


# ===========================================================================
# 主入口
# ===========================================================================
def print_banner():
    """打印啟動橫幅"""
    print()
    print("╔" + "═" * 68 + "╗")
    print("║" + " StockAnalysis 項目流程圖生成腳本 ".center(54) + "║")
    print("║" + " 使用 pipeline_visualizer 工具庫 ".center(54) + "║")
    print("╚" + "═" * 68 + "╝")
    print()
    print(f"  項目根目錄: {PROJECT_ROOT}")
    print(f"  UseCase 目錄: {USECASES_DIR}")
    print(f"  輸出目錄:   {OUTPUT_DIR}")
    print()


def main():
    """主函數：依次生成所有圖表"""
    print_banner()

    os.makedirs(OUTPUT_DIR, exist_ok=True)

    # 檢查 pipeline_visualizer 是否可用
    try:
        _ = PipelineVisualizer()
    except Exception as e:
        print(f"[錯誤] 無法初始化 PipelineVisualizer: {e}")
        print("       請確認 pipeline_visualizer.py 已放置在同一目錄下。")
        sys.exit(1)

    # 檢查 UseCase 目錄
    if not os.path.isdir(USECASES_DIR):
        print(f"[錯誤] UseCase 目錄不存在: {USECASES_DIR}")
        sys.exit(1)

    # 1. 四個週期 Pipeline 流程圖
    generate_pipeline_diagrams()

    # 2. 項目架構流程圖
    generate_project_architecture()

    # 3. 四週期對比圖
    generate_pipeline_comparison()

    # 匯總
    print("=" * 70)
    print("全部圖表生成完畢！")
    print("=" * 70)
    print(f"\n所有文件已輸出到: {OUTPUT_DIR}")
    print()

    # 列出所有生成的文件
    if os.path.isdir(OUTPUT_DIR):
        generated = sorted(os.listdir(OUTPUT_DIR))
        print("生成的文件列表:")
        for fname in generated:
            fpath = os.path.join(OUTPUT_DIR, fname)
            size = os.path.getsize(fpath)
            print(f"  - {fname}  ({size:,} bytes)")
    print()


if __name__ == "__main__":
    main()
