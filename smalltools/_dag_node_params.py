# -*- coding: utf-8 -*-
"""
AutoQuant 1:1 同步 · 第①步 参数对齐
====================================
Android DAG 选股引擎「节点参数」的单一事实源
（镜像 Kotlin 节点类硬编码常量 + pipeline XML config，供 smalltools dag_selection.py 消费）。

用法：
    python _export_dag_params.py    # 把本文件写入 app/src/main/assets/backtest_params.json 的 dag_nodes 节

对应关系（修改任一参数须同步三处）：
    1. 本文件      2. Kotlin 节点类      3. pipeline XML config
"""

# ── 公共 L0/L1（stock_picking_common_pipeline.xml）─────────────────────────────
COMMON = {
    "data_import": {
        "days": 60,            # DataImportNode 每次补拉 K 线天数
        "minSnapshots": 100,   # 快照不足判定为数据缺失的阈值
    },
    "market_ma_unified": {     # MarketMAUnifiedNode：大盘 MA 收敛统一检测
        "threshold": 0.02,     # MA5/MA10/MA20 间距 < 2% 视为收敛（收敛时减少新开仓）
    },
    "pool_filter": {           # StockPoolFilterNode
        "excludeST": True,     # 名称含 ST/*ST 剔除
        "excludeStart300": True,  # 创业板 300xxx
        "excludeStart688": True,  # 科创板 688xxx
        "excludeStart8": True,    # 北交所 8xxxxx
        "excludeStart9": True,    # B 股 900xxx
    },
    "candidate_pool": {        # CandidatePoolNode
        "useStockPoolFallback": True,  # 无候选池时兜底读股票池
    },
}

# ── 选股核心：strict_selection（StockEvaluationNode 22 参数）───────────────────
# 与 backtest_params.json select_params 节 / backtest_guangmo.PARAMS 同源，无需重复。
STRICT_SELECTION_REF = "select_params"

# ── 各节点参数（按周期；缺省表示该周期未挂此节点）──────────────────────────────
NODE_PARAMS = {
    "financial_health": {      # FinancialHealthNode 默认值（周期 pipeline 均挂载）
        "minScore": 60,        # 财务健康分及格线（满分 100）
        "maxDebtRatio": 70,    # 最大资产负债率 %
    },
    "smart_money_filter": {    # SmartMoneyFilterNode：智能资金流分及格线
        "超短": {"minScore": 30},
        "短线": {"minScore": 55},
        "中线": {"minScore": 55},
        "长线": {"minScore": 55},
    },
    "generate_orders": {       # GenerateOrdersNode：单次最大持仓数
        "超短": {"maxHoldings": 3},
        "短线": {"maxHoldings": 5},
        "中线": {"maxHoldings": 5},
        "长线": {"maxHoldings": 5},
    },
    "swap_weak": {             # SwapWeakNode：换弱最大持仓数
        "超短": {"maxHoldings": 3},
        "短线": {"maxHoldings": 5},
        "中线": {"maxHoldings": 5},
        # 长线 pipeline 未挂 swap_weak
    },
    "defensive_dividend": {    # DefensiveDividendNode（中线/长线）
        "maxPb": 1.5,          # 最大市净率
        "maxDebtRatio": 70,    # 最大资产负债率 %
        "maxCandidates": 5,    # 单次最多入选数
        "minMarketCapYi": 500, # 最低流通市值（亿）
        "中线": {"maxPerSector": 2},
        "长线": {"maxPerSector": 1},
    },
    "bounce_reversal": {       # BounceReversalNode 常量
        "noNewLowDays": 3,     # 大盘连续 N 天不创新低视为企稳
        "boostPoints": 12,     # 企稳 + 外围不弱 → 候选加 12 分
        "indexCode": "sh000001",
    },
    "direction_label": {       # DirectionLabelNode（短/中/长线）
        "exclude": ["DOWNTREND", "OSCILLATION"],  # 要剔除的方向
        "penalty": 25,         # 剔除方向扣分
    },
    "news_guard": {            # NewsGuardNode（短线）
        "impactThreshold": 75,     # 重大事件影响分阈值
        "sentimentThreshold": -30, # 舆情情绪分阈值（低于则剔除）
    },
    "news_strength": {         # NewsStrengthNode（短线）
        "lookbackDays": 3,
    },
    "rotation_penalty": {      # RotationPenaltyNode（周期 pipeline 均挂载）
        "thresholdDays": 3,    # 强势天数阈值
        "penaltyPerExcess": 10,# 每超出 1 天扣 10 分
    },
    "cross_day_aggregation": { # CrossDayAggregationNode
        "windowDays": 5,
        "topN": 20,
    },
}

# ── 数据敏感节点：smalltools 暂无对应数据源，dag_selection.py 中留接口并跳过 ──
DATA_GATED_NODES = {
    "financial_health":   {"data": "财务快照(pe/pb/roe/debt/marketCap)", "fallback": "数据缺失时跳过"},
    "smart_money_filter": {"data": "智能资金流分 SmartMoneyCache",       "fallback": "数据缺失时跳过"},
    "inst_tips":          {"data": "机构增减持/调研",                    "fallback": "数据缺失时跳过"},
    "news_strength":      {"data": "新闻热度",                           "fallback": "数据缺失时跳过"},
    "news_guard":         {"data": "新闻冲击/情绪分",                    "fallback": "数据缺失时跳过"},
    "ai_predict":         {"data": "AI 涨跌预测模型",                    "fallback": "数据缺失时跳过"},
    "sector_boost":       {"data": "板块强度(StockDataCenter)",          "fallback": "数据缺失时跳过"},
    "seasonality_boost":  {"data": "商品/季节因子",                      "fallback": "数据缺失时跳过"},
}
