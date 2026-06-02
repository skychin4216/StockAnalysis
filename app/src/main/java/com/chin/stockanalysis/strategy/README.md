# 量化选股策略模块 v3.1 (strategy/)

> 2026-06-03 — 增量梯度自测调优 + AI预测自动回退 + 历史数据导入增强 + 板块限制放宽 + 策略统计面板

## 架构

```
strategy/
├── README.md
├── Strategy.kt                      # 策略接口 + screenWithData() + StrategyCategory + StrategySource
├── StrategyConfig.kt                # 参数配置
├── StrategyEngine.kt                # 策略引擎（runAll + runAllWithData + 注册/删除/启用）
├── data/
│   ├── StockScreener.kt             # 股票扫描器（东方财富全市场 API）
│   ├── HistoricalDataFetcher.kt     # 历史K线拉取（东方财富 + 名称自动提取 + 去重 + 补齐）
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
│   ├── EarlyMorningChaseStrategy.kt # 早盘追涨选股分析 (5维+全板块, maxResult=10)
│   └── TailLowPickStrategy.kt       # 超短线尾盘低吸 (7维+全板块+分级仓位)
├── predict/
│   └── AIPredictionEngine.kt        # 🔥 AI 综合预测（方案A:多日OHLCV / 方案B:新闻+技术指标 / 自动回退豆包）
├── backtest/
│   ├── HistoricalDataStore.kt       # Room 实体 + DAO（3表）
│   ├── BacktestEngine.kt            # T+1 / 5日评估引擎
│   ├── HistoricalBacktestEngine.kt  # 🔥 历史N天逐日回测 + 次日对比（支持 screenWithData）
│   ├── MathIndicators.kt            # RSI/布林/MACD/KDJ/量比/MA趋势
│   ├── SectorDailyRecord.kt         # 板块日记录 Entity + DAO
│   ├── SectorRotationEngine.kt      # 板块轮动分析（动量/相关性/轮动速度）
│   ├── StrategyOptimizer.kt         # 权重自动优化（网格搜索 + AI确认）
│   └── StrategySelfTuner.kt         # 🔥 增量梯度自测调优（回测→反推最优权重→持久化→再回测闭环）
└── ui/
    ├── StrategyAdapter.kt
    ├── StrategyFragment.kt          # 🔥 双模式 + AI预测 + 调优按钮
    ├── StrategyListFragment.kt      # 🔥 策略列表视图（列表/网格切换）
    ├── StrategyStatsFragment.kt     # 🔥 策略统计面板（排名/最优持仓周期/Top股票）
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
| 6 | 早盘追涨选股分析 | `early_morning_chase` | **5维+全板块, maxResults=10** 🔥 | 自定义 |
| 7 | 超短线股票筛选逻辑 | `tail_low_pick` | **7维+全板块+回踩+分级仓位** 🔥 | 自定义 |

## 🔥 v3.1 新增功能（2026-06-03）

### 1. 增量梯度自测调优 (StrategySelfTuner 升级)

**旧版**：准确率 < 阈值 → 微调权重因子（±5 偏置），缺乏方向性。

**新版（增量梯度版）**：
```
1. 加载该策略的历史权重快照（如有）
2. 跑最近 N 个交易日的历史回测
3. 逐日统计预测信号中"下一天涨幅靠前"的股票 ← 梯度方向
4. 计算因子与次日涨幅的相关系数，按梯度增量调整权重（±5%步长）
5. 持久化新权重到 strategy_weight_snapshot 表
6. 再回测 → 对比优化前后效果
7. 记录每日涨幅 Top 板块/个股供统计面板使用
```

**核心改进**：从"盲目微调"变为"有方向性的梯度优化"，每次都向收益更高的方向调整。

### 2. AI 预测引擎自动回退

`AIPredictionEngine` 现在支持自动回退：
- 当前配置的 AI 后端无有效 API Key 时 → 自动回退到豆包（assets 内置 key）
- 无需用户手动切换后端
- 日志清晰显示回退路径：`"当前后端(X)无有效API Key，尝试回退到豆包"`

### 3. 历史数据导入增强 (HistoricalDataFetcher)

| 改进 | 说明 |
|------|------|
| **去重检测** | 导入前检查已有数据日期，如今天已存在则跳过，避免重复导入 |
| **名称自动提取** | 从东方财富 K线 API 顶层 JSON 直接提取股票名称（不再留空） |
| **同步写入 stock_basics** | 导入 K线时自动将名称写入 `stock_basics` 表 |
| **名称补齐 (fillMissingNames)** | 遍历所有出现过的股票代码，从 API 查询正确名称，覆盖 `daily_snapshot` 和 `stock_basics` |
| **返回类型变更** | `fetchOneStock()` 从 `List<DailySnapshotEntity>` 变为 `Pair<List<DailySnapshotEntity>, String>` |
| **更严格数据验证** | K线解析要求 `parts.size >= 9`（原来是 8），确保涨跌幅字段存在 |

### 4. 策略筛选放宽

两个自定义策略移除了**板块限制**（原来只允许 000/600 开头的主板股票）：

| 策略 | 变更 |
|------|------|
| **EarlyMorningChaseStrategy** | 移除 `000/600` 板块限制 → 允许创业板(300)和科创板(688) |
| **TailLowPickStrategy** | 移除 `000/600` 板块限制 → 覆盖全板块 |
| **EarlyMorningChaseStrategy** | `maxResults`: 5 → **10** |

### 5. 新增 UI 组件

| 新文件 | 功能 |
|--------|------|
| `StrategyListFragment.kt` | 🔥 策略列表视图 — 支持列表/网格切换，展示所有7个策略卡片 |
| `StrategyStatsFragment.kt` | 🔥 策略统计面板 — 排名/最优持仓周期/每日Top涨幅板块与个股对比 |
| `StrategyDataIntegrityTest.kt` | 数据完整性测试 — 验证回测数据的完整性和准确性（androidTest） |

### 6. 策略引擎日志增强

- 扫描结果日志从 `命中 X 只` 变为 `扫描X只 命中Y只`，方便发现"扫了很多但零命中"的问题
- 零命中时自动输出警告：`扫描了X只但零命中，检查筛选条件`
- 超时信息更明确：`超时（30s无响应）`

---

## 🔥 v3.0 新增功能（2026-05-31）

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

## 🔥 策略统计面板 (v3.1 新增)

`StrategyStatsFragment` 提供跨策略对比分析：

| 统计维度 | 说明 |
|----------|------|
| 🔢 策略排名 | 按历史准确率/均收益/胜率排序 |
| ⏱ 最优持仓周期 | 对比 1日/3日/5日/10日 持有下的均收益和胜率 |
| 🏆 每日涨幅 Top 板块 | 昨日涨幅最高的板块排行 |
| 🏆 每日涨幅 Top 个股 | 昨日涨幅最高的个股排行 |

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
| `stock_basics` | 股票基本信息（名称映射） | v6+ |