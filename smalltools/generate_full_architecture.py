# -*- coding: utf-8 -*-
"""生成 exe / smalltool / apk 全链路架构图（SVG + Mermaid）。

覆盖三大端：
  - smalltools (Python 选股引擎/拟合/发布)
  - AutoQuant (exe: 桥接引擎选股 + 回测拟合 + HTTP 服务 + GUI)
  - APK (Kotlin: 参数加载/选股管线/云同步/Agent)
以及它们之间的数据流：K线缓存 → 引擎 → backtest_params.json → 三端；
COS 云同步闭环；微信推送；C/S 控制链路（任务5 设计）。

输出：
  smalltools/output/full_architecture.svg      （大长图，浏览器直接打开）
  smalltools/output/full_architecture.mermaid  （mermaid 文本，GitHub/Notion 渲染）
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pipeline_visualizer import (  # noqa: E402
    PipelineVisualizer,
    ArchitectureDiagramBuilder,
    NodeType,
)

OUTPUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "output")

# 复用既有 Layer 布局常量：调大层间距让图更"长"
import pipeline_visualizer as pv
pv.LAYER_SPACING = 300


def build():
    b = ArchitectureDiagramBuilder()

    # ══════════ Layer 0: 外部数据源 ══════════
    b.add_node("ds_tencent", "腾讯 K线/实时\n(web.ifzq.gtimg.cn)", NodeType.DATA_SOURCE, layer=0)
    b.add_node("ds_east", "东方财富\nK线/实时/行情", NodeType.DATA_SOURCE, layer=0)
    b.add_node("ds_sina", "新浪 K线\n(money.finance.sina.com.cn)", NodeType.DATA_SOURCE, layer=0)
    b.add_node("ds_f10", "东财 F10\n财务/股东户数/北向/研报", NodeType.DATA_SOURCE, layer=0)
    b.add_node("ds_dc", "东财数据中心\nRPT_HOLDERNUMLATEST 等", NodeType.DATA_SOURCE, layer=0)
    b.add_node("ds_news", "新闻/公告\n(关键词情绪)", NodeType.DATA_SOURCE, layer=0)

    # ══════════ Layer 1: smalltools 选股引擎（核心） ══════════
    b.add_node("st_cache", "_kline_cache.json\n(核心池日K缓存 2023起)", NodeType.DATA_SOURCE, layer=1)
    b.add_node("st_guangmo", "backtest_guangmo.py\nanalyze_snaps 13项粘合链", NodeType.STRATEGY, layer=1)
    b.add_node("st_trend", "_trend_proto.py\ntrend_follow_scan 趋势链", NodeType.STRATEGY, layer=1)
    b.add_node("st_market", "market_state/triple_vote\n大盘状态(BULLISH/OSC/BEAR/CRASH)", NodeType.ENRICHMENT, layer=1)
    b.add_node("st_rotation", "_rotation_engine.py\n板块动量轮动", NodeType.ENRICHMENT, layer=1)
    b.add_node("st_filter", "_pool_filters.py\nextra_filter 硬过滤", NodeType.FILTER, layer=1)
    b.add_node("st_wf", "_walk_forward.py\n三年月度滚动拟合", NodeType.AI_PREDICTION, layer=1)

    # ══════════ Layer 2: 参数单一事实源 ══════════
    b.add_node("p_params", "backtest_params.json\n★ 唯一参数源\n(select/trend/sell 9格矩阵)", NodeType.AGGREGATION, layer=2)

    # ══════════ Layer 3: 发布 / 云同步 ══════════
    b.add_node("pb_publish", "_publish_candidates.py\n每日选股+发布", NodeType.AGGREGATION, layer=3)
    b.add_node("pb_cand", "candidates_quant.json\n(40只候选清单)", NodeType.DATA_TRANSFORM, layer=3)
    b.add_node("pb_wechat", "微信推送\n(pushplus/serverchan)", NodeType.TRADE_ACTION, layer=3)
    b.add_node("pb_cos", "COS 腾讯云\ncandidates/params/market_data.db", NodeType.DATA_SOURCE, layer=3)

    # ══════════ Layer 4: EXE (AutoQuant) ══════════
    b.add_node("exe_screen", "screen_smalltools.py\n桥接引擎选股(逻辑零漂移)", NodeType.STRATEGY, layer=4)
    b.add_node("exe_fit", "auto_fit_backtest.py\nwalk_forward.py 回测拟合", NodeType.AI_PREDICTION, layer=4)
    b.add_node("exe_gui", "AutoQuant-GUI.exe\n(PyInstaller 打包)", NodeType.DATA_TRANSFORM, layer=4)
    b.add_node("exe_http", "data_service.py\nHTTP API :8888\n(C/S 控制服务待扩展)", NodeType.DATA_TRANSFORM, layer=4)
    b.add_node("exe_cloud", "cloudsync/\nexe 云同步包(COS)", NodeType.DATA_TRANSFORM, layer=4)

    # ══════════ Layer 5: APK (Kotlin) ══════════
    b.add_node("apk_params", "BacktestParamsLoader\n加载 JSON 覆盖参数", NodeType.DATA_TRANSFORM, layer=5)
    b.add_node("apk_pipeline", "StockCheckPipeline\n四周期选股管线", NodeType.STRATEGY, layer=5)
    b.add_node("apk_factor", "FactorDataProvider\n资金流/财报/情绪/股东户数/北向", NodeType.FACTOR_COMPUTE, layer=5)
    b.add_node("apk_cloud", "CloudSyncManager\n(COS 上下行同步)", NodeType.DATA_TRANSFORM, layer=5)
    b.add_node("apk_agent", "AgentOrchestrator\nAutoQuantAgentRunner\n(AI Agent 后台决策)", NodeType.AI_PREDICTION, layer=5)
    b.add_node("apk_bench", "量化工作台\nPC候选Tab 一键建仓", NodeType.TRADE_ACTION, layer=5)
    b.add_node("apk_adb", "adb 广播接收\n(数据刷新/同步命令)", NodeType.DATA_TRANSFORM, layer=5)

    # ══════════ 连线：数据源 → smalltools ══════════
    b.add_edge("ds_tencent", "st_cache", "日K抓取(重试+回退)")
    b.add_edge("ds_east", "st_cache", "交叉校验")
    b.add_edge("ds_sina", "st_cache", "备用回退")
    b.add_edge("ds_f10", "apk_factor", "F10/股东/北向")
    b.add_edge("ds_dc", "apk_factor", "股东户数/北向")
    b.add_edge("ds_news", "apk_factor", "新闻情绪")
    b.add_edge("st_cache", "st_guangmo", "K线喂入")
    b.add_edge("st_cache", "st_trend", "K线喂入")
    b.add_edge("st_cache", "st_rotation", "板块动量")
    b.add_edge("st_cache", "st_market", "指数方向")
    b.add_edge("st_market", "st_guangmo", "大盘状态路由")
    b.add_edge("st_market", "st_trend", "BULLISH 时启用")
    b.add_edge("st_guangmo", "st_filter", "粘合命中")
    b.add_edge("st_trend", "st_filter", "趋势命中")
    b.add_edge("st_rotation", "st_filter", "板块代理")
    b.add_edge("st_wf", "st_guangmo", "拟合参数")
    b.add_edge("st_wf", "st_trend", "拟合参数")

    # ══════════ 连线：引擎 → 参数 → 三端 ══════════
    b.add_edge("st_wf", "p_params", "众数参数导出(_export_params.py)")
    b.add_edge("p_params", "exe_screen", "sell_rule_for 读矩阵")
    b.add_edge("p_params", "apk_params", "applySelect/TrendFollow")

    # ══════════ 连线：发布 / 云同步 ══════════
    b.add_edge("st_filter", "pb_publish", "精选候选")
    b.add_edge("st_rotation", "pb_publish", "板块轮动")
    b.add_edge("pb_publish", "pb_cand", "生成清单")
    b.add_edge("pb_publish", "pb_wechat", "推送微信")
    b.add_edge("pb_publish", "pb_cos", "PUT candidates")
    b.add_edge("pb_cos", "apk_cloud", "APK 下载候选/参数/行情库")
    b.add_edge("apk_cloud", "pb_cos", "上传手机会话数据 phone_*.zip")
    b.add_edge("apk_cloud", "exe_cloud", "exe 拉取手机数据→自动拟合→参数回流")

    # ══════════ 连线：EXE ══════════
    b.add_edge("st_cache", "exe_screen", "同源K线")
    b.add_edge("exe_fit", "st_cache", "桥接引擎回测")
    b.add_edge("exe_screen", "exe_gui", "选股结果入 GUI")
    b.add_edge("exe_screen", "exe_http", "候选清单 API")
    b.add_edge("exe_http", "apk_bench", "局域网拉取候选(长轮询 /push)")

    # ══════════ 连线：APK 内部 ══════════
    b.add_edge("apk_params", "apk_pipeline", "参数注入")
    b.add_edge("apk_factor", "apk_pipeline", "因子评分")
    b.add_edge("apk_pipeline", "apk_bench", "四周期选股结果")
    b.add_edge("apk_agent", "apk_bench", "AI 决策/报告")
    b.add_edge("apk_cloud", "apk_params", "下载最新拟合参数")
    b.add_edge("apk_adb", "apk_cloud", "adb 命令触发刷新/上传")
    b.add_edge("apk_adb", "apk_pipeline", "adb 命令触发选股")

    return b.build()


def main():
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    graph = build()
    viz = PipelineVisualizer()
    svg_path = os.path.join(OUTPUT_DIR, "full_architecture.svg")
    viz.render_svg(graph, svg_path,
                   title="StockAnalysis 全链路架构图（smalltools 引擎 → exe / apk，COS 云同步闭环）")
    mmd_path = os.path.join(OUTPUT_DIR, "full_architecture.mermaid")
    with open(mmd_path, "w", encoding="utf-8") as f:
        f.write(viz.render_mermaid(graph))
    print(f"SVG 已生成:  {svg_path}")
    print(f"Mermaid 已生成: {mmd_path}")
    print(f"节点数: {len(graph.nodes)}  连线数: {len(graph.links)}")
    print("提示：SVG 可用浏览器打开，另存/打印可导出为 PNG 长图；")
    print("      mermaid 可粘贴到 GitHub/Notion/支持 Mermaid 的编辑器渲染。")


if __name__ == "__main__":
    main()
