# 量化选股策略模块 v3.0 (strategy/)

> 2026-05-31 — 新增 AI 综合预测引擎 + 策略自测调优 + 新闻因子集成 + screenWithData 接口

## 架构

```
strategy/
├── README.md
├── Strategy.kt                      # 策略接口 + screenWithData() + StrategyCategory + StrategySource
├── StrategyConfig.kt                # 参数配置
├── StrategyEngine.kt                # 策略引擎（runAll + runAllWithData + 注册/删除/启用）
├── data/
│   ├── StockScreener.kt             # 股票扫描器（东方财富全市场 API）
│   ├── HistoricalDataFetcher.kt     # 历史K线拉取（东方财富）
│   └── FactorDataProvider.kt        # 统一因子源（Level2资金/财报/新闻）
├── models/
│   ├── ScreeningResult.kt           # 扫描结果
│   ├── StrategySignal.kt            # 策略信号
│   └── WeightFactor.kt              # 权重因子
├── strategies/
│   ├── MovingAverageStrategy.kt     # 均线金叉策略 (3维)
│   ├── VolumeBreakStrategy.kt       # 放量突破策略 (3维)
│   ├── LowValuationStrategy.kt      # 低估值策略 (3维)
│   ├── GapUpMomentumStrategy.kt     # 高开高走策略 (3维)
│   ├── TurnoverFilterStrategy.kt    # 换手率活跃策略 (3维)
│   ├── EarlyMorningChaseStrategy.kt # 早盘追涨选股分析 (5维+9硬过滤)
│   └── TailLowPickStrategy.kt       # 超短线尾盘低吸 (7维+分级仓位)
├── predict/
│   └── AIPredictionEngine.kt        # 🔥 AI 综合预测（方案A:多日OHLCV / 方案B:新闻+技术指标）
├── backtest/
│   ├── HistoricalDataStore.kt       # Room 实体 + DAO（3表）
│   ├── BacktestEngine.kt            # T+1 / 5日评估引擎
│   ├── HistoricalBacktestEngine.kt  # 🔥 历史N天逐日回测 + 次日对比（支持 screenWithData）
│   ├── MathIndicators.kt            # RSI/布林/MACD/KDJ/量比/MA趋势
│   ├── SectorDailyRecord.kt         # 板块日记录 Entity + DAO
│   ├── SectorRotationEngine.kt      # 板块轮动分析（动量/相关性/轮动速度）
│   ├── StrategyOptimizer.kt         # 权重自动优化（网格搜索 + AI确认）
│   └── StrategySelfTuner.kt         # 🔥 自测调优引擎（回测→评估→调优→再回测闭环）
└── ui/
    ├── StrategyAdapter.kt
    ├── StrategyFragment.kt          # 🔥 双模式 + AI预测 + 调优按钮
    └── StrategyDetailFragment.kt
```

## 全部策略（7个）

| # | 策略名 | ID | 维度 | 来源 |
|---|--------|----|------|------|
| 1 | 均线金叉策略 | `ma_golden_cross` | 3维 | 内置 |
| 2 | 放量突破策略 | `volume_break` | 3维 | 内置 |
| 3 | 低估值策略 | `low_valuation` | 3维 | 内置 |
| 4 | 高开高走策略 | `gap_up_momentum` | 3维 | 内置 |
| 5 | 换手率活跃策略 | `turnover_active` | 3维 | 内置 |
| 6 | 早盘追涨选股分析 | `early_morning_chase` | **5维+9硬过滤** 🔥 | 自定义 |
| 7 | 超短线股票筛选逻辑 | `tail_low_pick` | **7维+回踩+分级仓位** 🔥 | 自定义 |

## 🔥 v3.0 新增功能

### 1. screenWithData() 接口（历史回测修复）

**问题**：之前 `HistoricalBacktestEngine` 构建了历史 `StockRealtime` 列表，但调用 `strategy.screen()` 时又去拉实时 API，完全忽略历史数据。

**修复**：
- `Strategy.kt` 新增 `screenWithData(preloadedStocks: List<StockRealtime>)` 接口
- 7 个策略全部覆写，核心逻辑提取到 `screenWithPool()` 复用
- `StrategyEngine.kt` 新增 `runAllWithData()` → 所有策略共享一份预加载数据
- `HistoricalBacktestEngine` + `StrategyFragment` 单日执行均调用 `screenWithData()`

### 2. AI 综合预测引擎 (AIPredictionEngine)

执行策略后，在结果弹窗最底部自动显示 3-5 只综合推荐股票：

```
🤖 AI 综合预测（多策略+新闻因子+周期轮动）
📋 使用方案A: 多日OHLCV数据充分，适合技术分析

📊 市场判断: 沪指短期震荡，半导体板块资金持续流入

| 排名 | 股票 | 得分 | 涨概率 | 建议 |
|------|------|------|--------|------|
| #1 | 贵州茅台 | 85 | 70% | 逢低建仓 |
|  💡 被3个策略共同选中 + 均线金叉 + 放量突破...

⚠️ 投资有风险，入市需谨慎
```

**两套方案（AI动态选择）**：
- **方案A**：近5日 OHLCV 序列特征 → LLM 推理
- **方案B**：NewsFactor（利好利空）+ 技术指标 → LLM 推理

### 3. 策略自测调优引擎 (StrategySelfTuner)

UI 按钮：`执行` | `调优` | `导入` | `+自定义`

**闭环流程**：
```
1. 对最近30个交易日逐日回测每个策略
2. 统计买入准确率 + 平均收益
3. 如果准确率 < 55% → 微调低权重因子（±5偏置）
4. 再回测对比 → 改善则保留新权重，否则还原
5. 弹出调优报告（准确率对比 + 权重变更记录）
```

**使用前提**：需要先点击「导入」拉取历史K线数据（至少5个交易日）。

### 4. 新闻利好利空因子库 (news/)

| 文件 | 功能 |
|------|------|
| `NewsFactorEntity.kt` | Room Entity + DAO（含 sentiment/impact_strength/tags/sector） |
| `NewsFactorManager.kt` | AI提取、每日搜索行业巨头、维护清理（>3月停用, >1年删除） |
| `ChatTabFragment.kt`集成 | AI对话自动检测新闻 → 弹窗确认 → 保存到数据库 |

**生命周期管理**：
- 超过3个月 → 标记 `is_active=false`（不参与策略分析）
- 超过1年 → 物理删除

### 5. 股票去重修复

`ThemeStockService.kt` 方案B路径：东方财富板块API返回的股票按 `stock_code` 去重（`seenCodes: Set<String>`）。

## 🔥 双模式策略执行

### 实时模式（选择"上一个交易日"）
```
从 daily_snapshot 读取历史数据
  → 7个策略通过 screenWithData() 共享同一份数据
  → 显示股票扫描结果表格
  → 异步启动 AI 综合预测（底部显示3-5只推荐股）
  → 自动保存预测到 strategy_prediction 表
```

### 历史回测模式（选择"前3/5/10/30/50/100个交易日"）
```
从 daily_snapshot 表读取历史OHLCV
  → 逐日执行策略打分（screenWithData）
  → 自动对比次日实际涨跌
  → 显示准确率报告：
    ━━━ 📊 策略历史回测报告 ━━━
    期间: 2026-05-15 ~ 2026-05-29 (10天)
    1. 早盘追涨选股分析  准确率: 65.0% (13/20)
       平均收益: +2.35% | 评级: B (良好)
    2. 均线金叉策略      准确率: 52.0% ...
```

## 🔥 因子层

### MathIndicators — 技术指标计算
```
RSI(14)        → 相对强弱 (0-100)
Bollinger(20,2)→ 布林带 (上/中/下轨)
MACD(12,26,9)  → DIF/DEA/柱
KDJ(9,3,3)     → K/D/J 三线
VolumeRatio(10)→ 10日量比
maTrend()      → 多头/空头排列得分
```

## 🔥 权重自动优化（网格搜索 + AI 确认）

```
StrategyOptimizer.autoOptimize(strategy, report)
    │
    ├─ 步骤1: 网格搜索 (Grid Search)
    │   穷举所有权重组合（3因子≈1000种, 5因子≈100,000种）
    │   在历史数据上逐组合评估准确率 + 平均收益
    │   返回准确率最高的候选权重
    │
    ├─ 步骤2: AI 确认合理性
    │   如果准确率提升 < 5% → 自动生成优化 Prompt
    │   Prompt 包含: 当前权重 + 回测结果 + 网格搜索候选
    │   发送给 LLM → AI 给出最终建议 + 调整理由
    │
    └─ 结果: 原始 vs 优化权重对比 + AI 专业分析报告
```

## 🔥 板块轮动分析引擎

### 核心模型
| 模型 | 功能 | 输出 |
|------|------|------|
| 动量延续 | 近3日加权涨幅 → 预测明日热门板块 | Top 5 置信度排序 |
| 资金流向 | 主力连续流入递增 → 看多信号 | 连续热门天数 |
| Pearson相关性 | 板块间相关系数 + 跟涨概率 | A涨→B跟涨概率 |
| 轮动速度 | 排名变化幅度 → 判断市场风格 | 抱团/结构性/快速轮动 |
| 市场诊断 | 综合以上 → 策略建议 | "抱团行情-趋势优先" |

## 数据库表

| 表名 | 用途 | 版本 |
|------|------|------|
| `daily_snapshot` | 每日行情快照（OHLCV） | v4 |
| `strategy_prediction` | 策略预测记录 | v4 |
| `strategy_weight_snapshot` | 策略权重快照 | v4 |
| `sector_daily_record` | 板块每日记录 | v5 |
| `news_factors` | 新闻利好利空因子 | v6 |