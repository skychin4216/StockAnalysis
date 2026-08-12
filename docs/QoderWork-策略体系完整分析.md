# QoderWork - 量化交易策略体系完整分析（v3）

> 生成时间：2026-08-12（v3 新增大盘择时增强 + 板块轮动惩罚优化）
> 基于 StockAnalysis 项目全量代码审查
> v3 更新：AMarketAnalysisEngine 新增连涨/连跌天数预警、量价背离检测、沸腾极值强制降仓；RotationPenaltyNode 升级为三维度机制（对数衰减 + 板块生命周期 + 跨日轮动检测）
> v2 修正说明：v1 中 4 条 pipeline 的节点数、data_import 天数、market 阈值、smart_money 阈值、news_guard 阈值、defensive_dividend 参数、链路方向等共 20+ 处与代码不符，本节已全部按 `assets/usecases/*.xml` 实际内容修正；实仓 pipeline 已确认升级为 v4（8 节点）。

---

## 一、系统架构总览

StockAnalysis 采用 **模板方法模式 + DAG Pipeline** 双引擎架构。

**模板方法层**：`QuantFragmentBase` 定义通用框架（持仓/选股渲染、刷新缓存、T-trade 集成），5 个子类各自实现差异化逻辑。

**DAG Pipeline 层**：NodeRegistry 注册 49 种节点模块，通过 XML 配置组装成不同周期的选股流水线。Kahn 拓扑排序 + 分层并行执行。

**数据流**：
```
用户点击"建仓" → runDagPipeline() → DagTradeExecutor.execute()
  → XML 解析 → NodeRegistry 实例化节点 → Kahn 排序 → 分层并行执行
  → 各节点通过 context.stageOutputs 传递数据
  → generate_orders 输出订单 → position_merge 合并 → UI 渲染
```

### 1.1 四周期 + 实仓 Tab 对照（v2 修正）

| 维度 | 超短线 | 短线 | 中线 | 长线 | 实仓 |
|------|--------|------|------|------|------|
| Fragment | UltraShortQuantFragment | ShortTermQuantFragment | MidTermQuantFragment | LongTermQuantFragment | RealHoldingQuantFragment |
| getQuantType() | UltraShortQuant | ShortTermQuant | MidTermQuant | LongTermQuant | RealHolding |
| useCaseId | ultra_short | short_term | mid_term | long_term | real_holding |
| HoldingPeriod | ULTRA_SHORT | SHORT | MID | LONG | MID (pipeline用) |
| 数据导入天数 (XML data_import) | 30 | 60 | 60 | 60 | **120** |
| 严格选股回望天数 (lookbackDays) | 30 | 60 | 120 | 250 | — |
| 持仓上限 (XML generate_orders) | **6** | **6** | **6** | **6** | 不限(OCR导入) |
| 持仓上限 (UI 提示) | 最多3只 | 最多5只 | 最多5只 | 最多5只 | 不限 |
| 多日价格列 | ✗ (仅当日) | ✓ | ✓ | ✓ | 自有渲染 |
| 周期选择器 | ✗ | ✓ (1-14日) | ✓ (1-100日) | ✗ | ✗ |
| T+1 自动卖 | ✓ | ✗ | ✗ | ✗ | ✗ |
| 拟合/回测 | 禁用(固定参数) | 通用 | 通用(含次日回测) | 禁用(信息弹窗) | 禁用 |
| OCR 导入 | ✗ | ✗ | ✗ | ✗ | ✓ |
| 数据来源 | strategy_trade_orders | 同左 | 同左 | 同左 | real_positions + user_watchlist |

> ⚠️ **持仓上限矛盾（见 8.2-问题A1）**：四个策略 pipeline 的 `generate_orders.maxHoldings` 与 `swap_weak.maxHoldings` 均为 6，而各 Fragment 的 UI 提示为 3/5/5/5；超短线 `resolveMaxHoldings()` 取已启用策略 `maxPositions` 的最小值（早盘/尾盘/情绪均为 3），与 UI 一致，但 DAG 内实际放行 6 只，两套口径不一致。

---

## 二、选股核心：StockCheckPipeline（均线粘合度框架）

### 2.1 核心指标

**粘合度** = (MAX(MA5,MA10,MA20[,MA60]) - MIN(MA5,MA10,MA20[,MA60])) / MIN(MAs) × 100%

粘合度越小，说明均线越收敛，即将变盘的概率越大。

### 2.2 九项检查

| # | 检查项 | 说明 | 适用条件 |
|---|--------|------|----------|
| 1 | 粘合度 ≤ 阈值 | 核心指标 | 始终 |
| 2 | 多头排列 | MA5>MA10>MA20[>MA60] | 始终 |
| 3 | 粘合持续 ≥ N天 | 松散阈值(+0.5%)下持续天数 | 始终 |
| 4 | 量能条件 | 3种模式互斥 | 始终 |
| 5 | 距高点跌幅 ≥ 阈值 | 从回望期最高价回撤 | 始终 |
| 6 | MA60 上升 | MA60 与 5天前比较 | requireMA60Rising |
| 7 | 站稳年线 | close > MA250 | requireAboveYearLine |
| 8 | 涨幅达标 | 日涨幅 ≥ minChangePct | requireChangePct |
| 9 | 站上所有均线 | close > 所有MA | requireAboveAllMAs |

**passCount 只统计当前周期要求的检查项**，totalChecks 动态变化。

### 2.3 四周期参数差异（已核对 XML）

| 参数 | 超短线 | 短线 | 中线 | 长线 |
|------|--------|------|------|------|
| MA线数 | 3 (5/10/20) | 4 (5/10/20/60) | 4 | 4 |
| 粘合度阈值 | ≤3.0% | ≤3.0% | ≤2.5% | ≤2.0% |
| 粘合持续天数 | ≥5天 | ≥10天 | ≥15天 | ≥20天 |
| 量能模式 | 爆量 ≥2.5x | 放量 ≥1.5x | 温和放量 1.2-1.8x | 地量 <50% |
| 跌幅要求 | ≥10% | ≥20% | ≥30% | ≥40% |
| MA60上升 | ✗ | ✗ | ✓ | ✓ |
| 站稳年线 | ✗ | ✗ | ✗ | ✓ |
| 涨幅达标 | ✓ >4% | ✗ | ✗ | ✗ |
| 站上均线 | ✗ | ✓ | ✓ | ✗ |
| 回望天数 | 30 | 60 | 120 | 250 |
| minPassCount | 6 | 6 | 7 | 7 |

### 2.4 量能条件三种模式（v2 修正）

实际实现均以**当日成交量 / 前5日(不含当日)均量**为核心口径（`takeLast(N+1).dropLast(1)` 排除当日，避免当日未收盘数据污染）：

- **爆量/放量突破**（超短/短线）：`当日量 / 前5日均量 ≥ ratio`（超短 2.5x、短线 1.5x），要求资金突然涌入。
- **温和放量**（中线）：`当日量 / 前5日均量 ∈ [1.2, 1.8]`，避免过热和过冷。
- **地量萎缩**（长线）：`前10日均量 < 前60日均量 × 0.5`，要求充分洗盘（v2 修正：并非"近5日均量<ratio×60日均量"）。

---

## 三、DAG Pipeline 节点配置（v2 全部按 XML 核对修正）

> 说明：各 XML 头部注释中的节点数与实际 NodeList 不符（注释滞后，如超短注释 20 实为 22）。以下以 **实际 NodeList** 为准。链路均以 `<Links>` 为准。

### 3.1 超短线 Pipeline（实际 22 节点）

```
主链：n_import(30d) → n_pool(stock_pool) → n_cand → n_merge → n_boost → n_bounce
     → n_strict(3.0%/爆量2.5x/回望30d) → n_ancestral → n_inst_tips → n_smart(30)
     → n_ai → n_orders(maxHoldings=6) → n_swap(腾龙换鸟) → n_merge_pos
外部注入：n_ctx → n_pool | n_ma_unified(0.015) → n_boost | n_adaptive → n_orders
并行节点：n_ctx、n_bg、n_guard(持仓风控)、n_candle(K线形态)
分支：n_cand → n_intraday(5分钟K线) ← 【无出边，见 8.1-问题B1】
终端：n_t1sell(t1_auto_sell, -2%/+3%)
```

**特点（v2 修正）**：
- **超短线是全周期中唯一没有 `multi_period_hot` 注入的周期**——不跨日聚合热点，只靠当日策略信号 + 大盘过滤。
- smart_money 阈值最低(30)，超短线不苛求主力参与。
- market_ma 最紧(0.015)，大盘微跌就停止选股。
- 独有 `intraday_analysis`(5分钟K线) + `t1_auto_sell`。
- 无新闻节点、无板块精选池、无热度/跨日聚合/腾龙换鸟之外的增强。
- 候选池用的是 `stock_pool`（普通股票池），**不是**板块精选池。

### 3.2 短线 Pipeline（实际 28 节点，最复杂）

```
主链：n_import(60d) → n_pool(stock_pool) → n_cand → n_merge → n_boost → n_bounce
     → n_strict(3.0%/放量1.5x/回望60d) → n_ancestral → n_inst_tips → n_smart(55)
     → n_newsguard(75/-30) → n_ai → n_orders(maxHoldings=6) → n_swap → n_merge_pos
并行注入：n_multihot → n_merge | n_zipline → n_merge | n_ma_unified(0.02) → n_boost
        | n_heat → n_boost | n_news_str(lookback=3d) → n_smart
        | n_boost → n_rot_pen(轮动惩罚, thresholdDays=3, penaltyPerExcess=10) ← 无出边，结果写 context 供 smart 读取
后置：n_merge_pos → n_crosstab(跨Tab发布)
```

**特点（v2 修正链路）**：
- 节点最多(28)，信息维度最丰富。
- **独有**：`zipline_factor`(趋势因子)、`news_strength`、`rotation_penalty`、`crosstab_publish`。
- `multi_period_hot`(多周期热点) → **喂给 signal_merge**（不是直接喂订单）。
- smart_money 阈值最高(55)，要求主力明确参与。
- news_guard 参数 75/-30（四周期完全一致，见 8.2-问题B4）。
- `n_intraday` 同超短，为叶子节点（见 8.1-问题B1）。

### 3.3 中线 Pipeline（实际 30 节点，全周期最完整）

```
主链：n_import(60d) → n_pool(stock_pool) + n_sector_pool(板块精选池) → n_cand → n_merge
     → n_boost → n_bounce → n_strict(2.5%/温和放量1.2-1.8/回望120d)
     → n_base_guard(打底仓, MID) → n_ancestral → n_inst_tips → n_smart(55)
     → n_newsguard(75/-30) → n_ai → n_orders(maxHoldings=6) → n_swap → n_merge_pos → n_crosstab
并行/增强：
  n_adaptive → n_merge | n_multihot → n_crossday(跨日聚合, window=5d, topN=20) ← 无出边(8.1-问题B1)
  n_pool → n_heat → n_boost | n_pool → n_defensive(防守高息, PB≤1.5/负债≤70/至多5只) → n_merge
  n_boost → n_news_str → n_smart | n_boost → n_rot_pen(轮动惩罚) ← 无出边
```

**特点（v2 修正）**：
- data_import 为 **60d**（v1 误写 120d；120 是 strict_selection 的回望天数）。
- market_ma 阈值 **0.02**（v1 误写 0.025）。
- smart_money 阈值 **55**（v1 误写 45）。
- **中线有新闻节点**（v1 误写"无新闻节点"）：`news_strength → smart_money_filter` 与 `news_guard(75/-30) → ai_predict` 都存在。
- `cross_day_aggregation`(5日窗口) 为**中线独有**；`base_position_guard`、`defensive_dividend` 长线也有，`adaptive_params` 各周期都有（v1 "独有"表述错误）。
- defensive_dividend 参数为 PB≤1.5 / 负债≤70%（与长线相同，v1 误写 1.2/60 为长线专属）。

### 3.4 长线 Pipeline（实际 24 节点）

```
主链：n_import(60d) → n_pool(stock_pool) + n_sector_pool → n_cand → n_merge
     → n_boost → n_bounce → n_strict(2.0%/地量<0.5/回望250d/站稳年线)
     → n_base_guard(打底仓, LONG) → n_ancestral → n_inst_tips → n_smart(55)
     → n_newsguard(75/-30) → n_ai → n_orders(maxHoldings=6) → n_merge_pos
并行/增强：n_adaptive → n_orders | n_ma_unified(0.02) → n_boost | n_heat → n_boost
        | n_pool → n_defensive(PB≤1.5/负债≤70) → n_merge
```

**特点（v2 修正）**：
- data_import 为 **60d**（v1 误写 250d；250 是 strict_selection 的回望天数）。
- market_ma 阈值 **0.02**（v1 误写 0.03，实际与短/中相同，仅超短为 0.015）。
- smart_money 阈值 **55**（v1 误写 45）。
- news_guard 参数 **75/-30**（v1 误写 80/-40；四周期完全一致，长线并非最严）。
- defensive_dividend 参数 **PB≤1.5/负债≤70%**（v1 误写 1.2/60，实际与中线一致）。
- **无** swap_weak（长线不做弱股替换）、无 multi_period_hot、无 news_strength、无 rotation_penalty、无 intraday、无 crosstab。
- 独有：`requireVolumeShrink`(地量<50%) + `requireAboveYearLine`(MA250) + `requireMA60Rising`。

### 3.5 四周期 pipeline 模块矩阵

| 模块 | 超短 | 短 | 中 | 长 |
|------|:---:|:---:|:---:|:---:|
| data_import(天数) | 30 | 60 | 60 | 60 |
| stock_pool | ✓ | ✓ | ✓ | ✓ |
| sector_stock_pool(板块精选池) | ✗ | ✗ | ✓ | ✓ |
| multi_period_hot(跨日热点) | ✗ | ✓ | ✓ | ✗ |
| market_ma_unified(阈值) | ✓(0.015) | ✓(0.02) | ✓(0.02) | ✓(0.02) |
| adaptive_params | ✓ | ✓ | ✓ | ✓ |
| candidate_pool | ✓ | ✓ | ✓ | ✓ |
| intraday_analysis(5min) | ✓ | ✓ | ✗ | ✗ |
| zipline_factor | ✗ | ✓ | ✗ | ✗ |
| heat_score | ✗ | ✓ | ✓ | ✓ |
| cross_day_aggregation | ✗ | ✗ | ✓ | ✗ |
| news_strength | ✗ | ✓(3d) | ✓(默认) | ✗ |
| rotation_penalty | ✗ | ✓(3d/10) | ✓(默认) | ✗ |
| defensive_dividend | ✗ | ✗ | ✓ | ✓ |
| strict_selection | ✓ | ✓ | ✓ | ✓ |
| base_position_guard | ✗ | ✗ | ✓ | ✓ |
| ancestral_rules / inst_tips | ✓ | ✓ | ✓ | ✓ |
| smart_money_filter(阈值) | ✓(30) | ✓(55) | ✓(55) | ✓(55) |
| news_guard(75/-30) | ✗ | ✓ | ✓ | ✓ |
| candle_pattern | ✓ | ✓ | ✓ | ✓ |
| generate_orders(上限) | ✓(6) | ✓(6) | ✓(6) | ✓(6) |
| swap_weak(上限) | ✓(6) | ✓(6) | ✓(6) | ✗ |
| t1_auto_sell | ✓ | ✗ | ✗ | ✗ |
| crosstab_publish | ✗ | ✓ | ✓ | ✗ |

### 3.6 实仓 Pipeline（v4，实际 8 节点）

```
Layer 0: n_import(120d, minSnapshots=50) ∥ n_bg(bg_manager)
Layer 1: n_a_market(大盘K线分析) ∥ n_news(news_strength, useRealTime=true)
Layer 2: n_rh_eval(实仓评估) ∥ n_holding_diag(持仓诊断：技术/风险/资金三维健康分+板块风险+组合健康度)
Layer 3: n_holding_predict(走势预测：短期趋势+支撑/阻力+场景分析+操作计划)
Layer 4: n_t_trade(做T评估, basePositionRatio=0.3, maxSignalsPerStock=2)
```

---

## 四、24 个策略详解

### 4.1 超短线策略（ULTRA_SHORT, 1天）

**EarlyMorningChaseStrategy（早盘追涨）**
- 逻辑：热门板块 V 型反转检测，利用 L2 数据
- 止损 -2%，止盈 +3%，信号有效期 2h，maxPositions=3
- 熊市自动提高阈值

**TailLowPickStrategy（尾盘低吸）**
- 逻辑：14:30-15:00 低位吸纳，7维评分（主线25+情绪5+技术20+资金15+股性10+量能10）
- 止损 -2%，止盈 +3%，信号有效期 1h，maxPositions=3

**MarketSentimentStrategy（情绪周期）**
- 逻辑：冰点日（跌停>2×涨停）买入超跌反弹，规避高潮日
- 止损 -3%，止盈 +5%，信号有效期 4h，maxPositions=3

### 4.2 短线策略（SHORT, 1-14天）

**GapUpMomentumStrategy（高开高走）**
- 跳空 ≥2% + 盘中动量 ≥0.5% + 涨幅 ≥3%
- 熊市自动提高阈值

**HotSpotDrivenStrategy（热点驱动）**
- 三重驱动：板块热度(40%) + 新闻情绪(30%) + 资金推动(20%) + 动量(10%)

**DragonHeadDipStrategy（龙头回调）**
- 龙头股深度回调 ≥20%(20日高点) + MA5走平 + 缩量至60-80%

**SectorRotationStrategy（板块轮动）**
- 追踪板块资金流入加速度(3日)，选加速板块内的龙头股

**TurnoverFilterStrategy（换手率活跃）**
- 换手率 3%-15% + 涨幅 ≥1%，连续3日换手递增加分

**VolumeBreakStrategy（放量突破）**
- 真实量比 ≥2x + 价格突破开盘价 + 涨幅 ≥2%

**CandlePatternStrategy（K线形态）**
- 16种K线形态检测，仅看涨形态，按形态强度评分，maxPositions=5

### 4.3 中线策略（MID, 30-180天）

**MovingAverageStrategy（均线金叉）**
- MA5 金叉 MA20 + 量能确认，熊市提高阈值

**BollingerBandStrategy（布林带突破）**
- 价格突破上轨(MA20±2σ) + 量能确认 + 缩口突破加分

**RSIDivergenceStrategy（RSI背离）**
- RSI(14) 超卖 <30 + 看涨背离(价格新低但RSI不新低) + 量能确认

**TrendFollowingStrategy（趋势跟踪）**
- MA20>MA60>MA120 多头排列 + MACD零上 + 回踩MA20不破MA60 + 看涨K线

**StrictSelectionStrategy（均线粘合严选）**
- 委托 StockCheckPipeline.midTermParams()：粘合≤2.5% + 多头 + 持续15天 + 温和放量 + 回撤≥30% + MA60上升 + 站上均线
- 需 7/7 全通过

**InstitutionalIntentStrategy（主力意图）**
- 量价关系 + K线特征 + RSI区间 + 布林位置 + 均线排列 → 识别建仓/洗盘

**SmartMoneyDetectionStrategy（主力资金侦测）**
- MFI(30%) + CMF(25%) + A/D背离(25%) + 主力净流入趋势(20%)
- 识别伏击/拉升/出货模式

**AIPredictionStrategy（AI量化）**
- 多策略评分 + 历史模式 + 新闻情绪(LLM)，输出 Top 3-5

**TrendScoreStrategy（趋势加减分）**
- 非过滤型"修正器"：基于趋势(MA排列+ADX+动量+量价)加减分，基础分50

### 4.4 长线策略（LONG, 180-365天）

**LowValuationStrategy（低估值）**
- 3D评分：估值PE(40%) + 质量ROE/利润率(30%) + 安全PB/市值(30%)

**FundamentalFilterStrategy（基本面三层分级）**
- 基础排除 → 第一层(基本价值) → 第二层(质量成长) → 第三层(核心)，15+项标准

**InstitutionalAccumulationStrategy（机构增持）**
- ROE≥15% + 负债<50% + 正经营现金流 + 低换手1-6% + 合理PE

**MoatLeaderStrategy（护城河龙头）**
- 护城河板块关键词匹配 + 市值≥500亿 + 毛利率≥25% + ROE≥18%

**CyclicalLowPositionStrategy（周期低位）**
- 周期行业(锂电/金属/煤炭等) + 破产安全 + 52周低位30%区间
- 止损 -15%，止盈 +50%

---

## 五、自动卖出引擎（AutoSellEngine）

### 5.1 十项卖出条件（按优先级）

| # | 条件 | 紧急度 | 触发条件 | 卖出比例 |
|---|------|--------|----------|----------|
| 1 | 硬止损 | 10 | 亏损 ≥ 8% | 100% |
| 2 | 最大回撤 | 9 | 从峰值回撤 ≥ 12% | 100% |
| 3 | 时间无进展 | 7 | 持仓 ≥ 10天 且 3日动量 < 1% | 100% |
| 4 | 阶梯止盈 | 5 | 10%/15%/20% 三档 | 33%/33%/100% |
| 5 | 吊灯止损 | 8 | 价格 ≤ 最高价 - 3×ATR | 100% |
| 6 | 移动止盈 | 6 | 盈利 ≥ 8% 且 回撤/利润 ≥ 50% | 100% |
| 7 | MA死叉 | 7 | MA5 下穿 MA20 | 100% |
| 8 | 放量滞涨 | 6 | 量 ≥ 2×均量 且 涨幅<1% | 100% |
| 9 | RSI超买 | 5 | RSI ≥ 75 且 连续2日下降 | 100% |
| 10 | 板块弱势 | 5 | 所属板块涨跌 ≤ -2% | 100% |

### 5.2 阶梯止盈机制

```
第一档：盈利 10% → 卖出 33%
第二档：盈利 15% → 卖出 33%
第三档：盈利 20% → 清仓 100%
```

通过 `[TP_TIER_N]` 标记写入 order.reason，`takenTpTiers()` 解析已执行档位，防止重复触发。

### 5.3 板块弱势数据链

```
持仓股票代码 → sectorStockDao().getSectorNamesByStockCode(code)
→ sectorDailyRecordDao().getByDate(date) → 取最差(最小)板块跌幅
```

---

## 六、做T引擎（TTradeEngine）

### 6.1 四种信号类型

| 类型 | 方向 | 说明 | 盈利判定 |
|------|------|------|----------|
| T_BUY | 先买后卖 | 支撑位附近买入 | 执行价 > 建议价 |
| T_SELL | 配对卖出 | 正T的卖出腿 | 执行价 > 建议价 |
| RT_SELL | 先卖后买 | 阻力位附近先卖 | 执行价 < 建议价 |
| RT_BUY | 配对买回 | 反T的买回腿 | 执行价 < 建议价 |

### 6.2 信号生成流程

1. 计算 MA / 支撑位 / 阻力位
   - 支撑 = max(recentLow, MA5×0.98, MA10×0.97)
   - 阻力 = min(recentHigh, MA5×1.02, MA10×1.03)
2. RSI / 成交量 / K线形态分析（量比 = 当日量/前5日均量）
3. 趋势方向评分(7种状态)：上升中/准备上升/盘整偏多/盘整/盘整偏空/准备下跌/下跌中
4. 生成 T_BUY（近支撑）和 RT_SELL（近阻力）信号，`expectedPct > 0.5%` 才触发
5. 检查未平仓交易，生成配对腿 T_SELL / RT_BUY（confidence=80 直接给高置信）

### 6.3 置信度评分

基础50 → RSI调整(±20) → 成交量(±10) → K线形态(±15) → 趋势(±10/15)

### 6.4 配对与结算

- T_SELL 触发：close ≥ openTrade.price × 1.005 (赚0.5%)
- RT_BUY 触发：close ≤ openTrade.price × 0.995 (赚0.5%)
- 做T数量 = 基础持仓 × **40%**（取整到100股，至少100股）
- 孤立配对腿（找不到开仓腿）→ `return 0L`，不落库

> ⚠️ 注意：DAG 实仓 pipeline 的 `t_trade_eval` 使用 `basePositionRatio=0.3`，与 TTradeEngine 的 40% 不一致（见 8.2-问题B3）。

### 6.5 推荐管理

- saveRecommendations()：自动去重（同股/同日/同信号类型）
- trackOutcomeForRecommendations()：更新峰值/谷值，检查目标达成（四种信号类型全覆盖）
- markDayEnd()：计算虚拟盈亏（含配对腿方向盈亏）
- getDailySummary()：虚拟成功率 + 实际成功率（方向感知）

---

## 七、实仓 Tab 特殊逻辑

### 7.1 数据来源

实仓不依赖 `strategy_trade_orders`，而是使用独立的 `real_positions` 表 + `user_watchlist`(source="RealHolding")。Fragment 以 `holdingPeriod = MID` 运行 `real_holding` DAG。

### 7.2 OCR 导入流程

```
截图选择 → ML Kit OCR(中文) → AI 解析(AiProviderPool, 60s+90s超时)
  → 策略1: 逐对象 regex 提取
  → 策略2: 完整 JSON array 解析
  → Fallback: 纯 regex（代码区+价格区位置匹配）
→ 确认弹窗 → enrichWithRealtimeData(更新现价/PE/换手率/名称，保留OCR成本价)
→ 写入 real_positions
```

### 7.3 周期自动分类

```
daysHeld ≤ 1 → ULTRA_SHORT
daysHeld ≤ 29 → SHORT
daysHeld ≤ 180 → MID
else → LONG
```

> 注意：此分类仅用于实仓持仓的评估口径，与 HoldingPeriod 枚举定义（SHORT=1..14）存在边界不一致（见 8.1-问题A2）。

### 7.4 实仓 Pipeline（v4，8 节点，v2 修正）

```
Layer 0: n_import(120d) ∥ n_bg
Layer 1: n_a_market(大盘K线分析) ∥ n_news(新闻力度, useRealTime)
Layer 2: n_rh_eval(逐股评估) ∥ n_holding_diag(持仓诊断：技术/风险/资金三维健康分+板块风险+组合健康度)
Layer 3: n_holding_predict(走势预测：短期趋势+支撑/阻力+场景分析+操作计划)
Layer 4: n_t_trade(做T/反T建议, basePositionRatio=0.3, maxSignalsPerStock=2)
```

v4 相对早期版本已补齐"持仓诊断 + 走势预测"两大能力，不再是 v1 文档所述的 6 节点简化版。仍缺：板块轮动退潮检测、资金流向、组合集中度/相关性风险（见 8.3-建议C6）。

---

## 八、AI 思维审查：逻辑错误 / 参数矛盾 / 完善建议 / 选股思路丰富

### 8.1 逻辑错误（硬伤，建议优先修复）

**问题A1（P0）持仓上限三套口径不一致**
- `generate_orders.maxHoldings` / `swap_weak.maxHoldings` XML 均为 6；
- Fragment UI 提示"最多 3/5/5/5 只"；
- 超短线 `resolveMaxHoldings()` 实际取已启用策略 `maxPositions` 最小值（3 只策略均为 3，恰好一致），若未来新增默认 5 的策略，UI 会显示 5 而 DAG 仍放行 6。
- **影响**：用户看到的持仓上限与实际可建仓数可能不符；超短线"最多3只"的预期在极端情况下可被打破。
- **修正**：以 XML 为唯一真源，Fragment 读取 pipeline 配置展示；或为每个周期统一 maxHoldings 常量。

**问题A2（P0）HoldingPeriod 枚举与 classifyPeriod 边界不一致**
- `HoldingPeriod.SHORT.holdingDays = 1..14`，但 `classifyPeriod` 用 `≤29` 归为 SHORT；MID=30..180。
- **影响**：信号有效期（SHORT 信号 3 天过期）与实际持仓天数（可持有 29 天）脱节；14~29 天区间的持仓既不算 SHORT 的"常规持有期"，也没有专属卖出策略，落入真空区。
- **修正**：统一枚举 SHORT=1..29、MID=30..180；或让 classifyPeriod 严格对齐枚举。

**问题A3（P0）T+1 自动卖双重实现**
- 超短 pipeline 的 XML 已含 `t1_auto_sell` 节点（终端），同时 `UltraShortQuantFragment` 还有 `checkT1AutoSell()` 在 `onComplete` 触发。
- **影响**：两套实现若参数（-2%/+3%）或触发时机不同步，可能重复卖出或互相冲突；且两者都是"打开 app 才执行"，错过开盘时段则失效。
- **修正**：保留 DAG 节点为主实现，Fragment 仅负责展示；将 T+1 到期检查迁入 `QuantTaskScheduler` 定时任务（开盘后自动执行）。

**问题A4（P0）超短/短线 `intraday_analysis` 与中线 `cross_day_aggregation` 无出边（潜在死节点）**
- 超短/短线 XML 中 `n_intraday`（5分钟K线）只有入边（cand→intraday），**没有任何出边**；
- 中线 `n_crossday`（跨日聚合）同样只有入边（multihot→crossday、pool→crossday），无出边。
- **影响**：除非下游通过 `context.stageOutputs` 按 key 读取（XML 无法体现），否则这两个节点的计算结果不会被任何后续节点消费，白算。
- **修正**：确认执行器/下游是否按约定 key 读取；若无消费方，要么把结果显式接入 `signal_merge`/`generate_orders`，要么移出主链作为纯展示节点。

**问题A5（P0）长线 market 阈值与"穿越牛熊"定位冲突**
- 长线 `market_ma_unified.threshold = 0.02`，与短线/中线完全相同，仅超短更紧(0.015)。
- **影响**：长线在熊市会与短线一样严格停摆，违背"长线深度价值、穿越周期"的定位；且 v1 文档"长线最严(0.03)"的认知是错的。
- **修正**：长线可放宽到 0.03~0.04 或改用"长期均线（MA60/MA120）是否仍向上"而非单日阈值；或长线完全不设大盘开关，仅靠 defensive_dividend 防守。

**问题A6（P1）新闻因子口径不统一**
- `news_strength` 仅短线配置了 `lookbackDays=3`，中线用默认值；长线完全没有 `news_strength`（只有 news_guard）。
- `news_guard` 四周期参数**完全相同**(75/-30)，"长线最严"不成立。
- **修正**：为每个周期显式声明新闻参数；长线建议提高 impactThreshold（如 85）并收紧 sentimentThreshold（如 -40）；中线可放宽（如 65/-20）。

**问题A7（P1）做T仓位比例两套口径**
- 手动做T `TTradeEngine` 用底仓 40%；DAG 实仓 `t_trade_eval` 用 `basePositionRatio=0.3`。
- **修正**：统一为同一常量，且接入用户风险偏好。

### 8.2 参数与一致性矛盾（建议修复）

**问题B1**：各 pipeline XML 头注释的节点数滞后于 NodeList（超短 20/实 22、短 22/实 28、中 20/实 30、长 19/实 24）。建议删掉注释里的硬编码数字，或加 CI 校验。
**问题B2**：超短线是全周期唯一无 `multi_period_hot` 的周期，但其定位恰恰是"情绪/热点博弈"。建议给超短增加轻量级跨日热点（仅看 1-3 日热度聚合，规避连板尾盘风险）。
**问题B3**：超短 signal_expiry 1~4h，但 pipeline 结果缓存到下次手动刷新，用户看到时信号可能已过期。建议超短信号有效期至少覆盖到当日收盘，或到期自动失效重算。
**问题B4**：AutoSellEngine 硬止损 -8%、最大回撤 -12% 全周期统一，与超短策略止损 -2%/-3% 不匹配——超短可能先被策略止损打掉，AutoSell 的 -8% 形同虚设；长线 -8% 又偏紧。建议按周期注入止损参数（超短 -4%/-6%、短 -6%/-8%、中 -8%/-12%、长 -12%/-20%），并让 AutoSell 读取策略 defaultStopLoss/defaultTakeProfit。
**问题B5**：StrategyEngine.strategies 用普通 `mutableMapOf()`，多线程 async 并发读写存在竞态风险。建议改 `ConcurrentHashMap`。
**问题B6**：长线 pipeline 24 节点但"无 swap_weak"——若组合中某长线股基本面恶化，只能等 AutoSell 卖出，无法"腾龙换鸟"替换为同板块更优标的。可考虑给长线加"基本面恶化替换"（阈值宽松版 swap）。
**问题B7**：中线 `rotation_penalty` 未配置参数（用默认），短线程式化配置了 (3d/10)。中线板块轮动周期更长，建议显式配置（如 thresholdDays=10）。
**问题B8**：短线 `crosstab_publish` 依赖 `position_merge` 输出，但持仓合并结果对其他 Tab 的"跨Tab发布"具体如何消费、是否参与选股未闭环。

### 8.3 完善建议（AI 视角）

**建议C1：多周期共振选股通道**
当前各周期 pipeline 完全独立。建议把 `crosstab_publish` 真正做成共振引擎：同一股票若在 ≥2 个周期 pipeline 中通过（如短线+中线同时命中），赋予"共振加分"，单独列表展示，作为高置信选股入口。已有 `runCrossPeriodFitting()` 框架，可先复用。

**建议C2：动态阈值（市场状态分档）**
粘合度阈值固定（3.0/3.0/2.5/2.0）。建议结合 `market_context` / `adaptive_params` 输出分档：牛市放宽（3.5/3.5/3.0/2.5），熊市收紧（2.5/2.5/2.0/1.5）；同时把 `market_ma_unified` 从"0/1 开关"升级为"连续市场强度分"，驱动各周期参数平滑调整。

**建议C3：持仓健康度评分（卖出端升级）**
AutoSellEngine 目前是 10 个独立条件逐条判定。建议增加 0-100 持仓健康分（权重：止损距离 30% + 动量 20% + 板块强度 20% + 均线结构 15% + 基本面 15%），触发阈值才进入卖出清单；UI 用颜色渐变展示。实仓 pipeline 的 `holding_diagnostic` 已有三维健康评分，可直接复用/反向注入 AutoSell。

**建议C4：做T与选股信号联动**
TTradeEngine 独立于选股 pipeline。建议把 pipeline 对个股的状态判定（粘合待突破/高位放量/趋势上升）写入订单/自选标记，做T引擎据此调整置信度加减分与配对目标（例如"粘合待突破"时正T更积极、"高位放量"时反T更积极）。

**建议C5：新闻 guard 差异化**
四周期 news_guard 参数相同，建议按持仓周期差异化（短线宽松防误杀、长线严格防黑天鹅），并把新闻影响落到"个股分 + 板块分 + 大盘分"三级。

**建议C6：实仓 pipeline 继续增强**
v4 已补诊断与预测，仍建议增加：板块轮动退潮检测（持仓板块近 3/5 日排名下滑则预警）、主力资金流向（CLOSE/大单净额）、组合集中度/相关性风险（同板块持仓占比上限、行业分散约束）、以及"诊断→卖出建议"的自动闭环（目前 holding_diagnostic 输出只到预测与做T，未触达 AutoSellEngine）。

**建议C7：卖出时机引擎化**
目前卖出评估是手动点击触发。建议把 AutoSellEngine 接入定时任务（收盘后自动评估 + 推送），并把"部分卖出比例"从固定 33% 改为动态比例（按盈利幅度、波动率、板块强度调整）。

### 8.4 选股思路丰富建议（策略层）

1. **板块生命周期前置**：在选股入口增加"板块阶段识别"（启动→发酵→高潮→退潮），超短只做"启动+发酵"、短做"发酵"、中做"启动回踩"、长线回避"高潮"。可与现有 `heat_score` + `rotation_penalty` 联动，但需要把阶段标签作为 pipeline 上下文传递。
2. **主力行为分阶段因子**：`smart_money_filter` 目前只有单一分数。建议拆为"吸筹/拉升/出货"三阶段评分，超短只信"拉升初期"，中线只信"吸筹中后期"，长线信"吸筹+基本面双确认"。
3. **事件日历过滤**：增加财报披露、解禁、减持、定增、高送转等事件日历，作为各周期 pipeline 的前置过滤（长线最敏感），避免"消息落地即变盘"。
4. **分时+量能微观因子（超短）**：为超短 pipeline 增加封板率、炸板率、大单净流入占比、开盘 30 分钟量能占比等 L2 因子，弥补"无跨日热点 + 无新闻"的信息缺口。
5. **北向/机构持仓变动（中长线）**：把机构增持策略扩展为"北向资金连续净买入 + 基金季报加仓 + 高管增持"多信号共振，作为中长线管道权重的强加分项。
6. **组合级约束回填选股**：选股时即考虑组合约束：同板块持仓 ≤2 只、行业分散 ≥4 个、最大单一持仓 ≤30%、组合波动率目标。把约束违反的候选自动降权或剔除。
7. **AI 解读层**：把 `ai_predict` 从"分数裁切"升级为"给每个候选输出 1-2 句可解释理由（粘合度/资金/板块/新闻）"，既提升可解释性，也为后续"AI 复盘（为什么这只票当初该买/不该买）"沉淀训练语料。
8. **回测反哺**：把 `PipelineBacktestEngine` 的周期级胜率/盈亏比回填为 pipeline 参数的先验权重，实现"数据驱动调参"闭环，替代手工改 XML。

### 8.5 v3 改进：大盘择时增强 + 板块轮动惩罚优化（已实现）

#### 8.5.1 大盘连涨/连跌预警系统

**核心原理**：大盘涨跌优先于个股。"覆巢之下无完卵"——大盘环境是个股操作的天花板。

**AMarketAnalysisEngine v2 新增三个维度**：

**1. 连涨/连跌天数检测** (`countConsecutiveDays`)
- 从最新一天向前回溯，统计连续上涨或下跌的天数
- 连涨和连跌互斥，确保只计一个方向

**2. 量价背离检测** (`checkVolumePriceDivergence`)
- 近3天指数累计涨幅 > 1%，但近3天平均量能 < 前5天平均量能的 80%
- 经典的"无量空涨"见顶信号

**3. 连涨风险评估** (`assessStreakRisk`)

根据 A 股历史统计规律：

| 连涨天数 | 回调概率 | 风险等级 | 建议仓位 |
|----------|----------|----------|----------|
| ≤3天 | ~40% | LOW | 正常 |
| 4-5天 | ~50-60% | MEDIUM | 降至50% |
| 6-7天 | ~65% | HIGH | 降至35% |
| ≥8天 | >75% | EXTREME | 降至10% |

**叠加修正**：
- 量价背离 → 风险等级上调一档（LOW→MEDIUM, MEDIUM→HIGH）
- 市场"沸腾" → 风险等级上调一档

**状态机决策增强** (`decidePeriod` 新增优先级)：
```
优先级0：连涨极值
  streakRisk == EXTREME → ULTRA_SHORT, 10%仓位
  streakRisk == HIGH 且 量价背离 → ULTRA_SHORT, 15%仓位

优先级1（新增）：沸腾极值
  marketTemp == "沸腾" 且 streakRisk != LOW → SHORT, 25%仓位

优先级2（增强）：趋势向上但动态调仓
  正常 → LONG, 70%
  streakRisk == MEDIUM → LONG, 50%（降一档）
  streakRisk == HIGH → LONG, 35%（降两档）
```

**MarketAnalysisResult 新增字段**：
- `consecutiveUpDays: Int` — 连涨天数
- `consecutiveDownDays: Int` — 连跌天数
- `streakRiskLevel: String` — 连涨风险等级 (LOW/MEDIUM/HIGH/EXTREME)
- `volumePriceDivergence: Boolean` — 量价背离标记

这些数据通过 `context.getStageOutput<MarketAnalysisResult>("n_a_market")` 自动注入 pipeline context，所有下游节点均可读取。

#### 8.5.2 板块轮动惩罚 v2（三维度机制）

**原算法问题**：
1. 线性惩罚过于激进（每多一只超额股罚10分，4只就罚20分）
2. 不区分板块强弱方向（主升浪板块和退潮板块一视同仁）
3. 没有时间维度（只看当天集中度，不看板块是否在轮动）

**v2 三维度惩罚机制**：

**维度1：集中度惩罚（对数衰减）**
```
原公式：penalty = -(count - threshold + 1) × 10  （线性）
新公式：penalty = -log2(count - threshold + 2) × 10  （对数衰减）
```

| 超额数 | 原惩罚 | 新惩罚 |
|--------|--------|--------|
| 1 | -10 | -6 |
| 2 | -20 | -10 |
| 3 | -30 | -13 |
| 4 | -40 | -16 |

**维度2：板块生命周期系数**

利用 `SectorDailyRecordEntity.consecutiveHotDays`（板块连续热门天数）：

| 连续热门天数 | 阶段 | 生命周期系数 | 含义 |
|-------------|------|-------------|------|
| ≤2天 | 启动期 | 0.5 | 惩罚减半（刚启动，多配合理） |
| 3-4天 | 高潮期 | 1.0 | 正常惩罚 |
| ≥5天 | 退潮期 | 1.5 | 惩罚加重50%（大概率见顶回落） |

最终板块惩罚 = 集中度惩罚 × 生命周期系数

**维度3：跨日轮动检测**

比较今天和昨天的选股板块分布（取昨日 rank≤10 的板块）：

| 重合度 | 轮动速度 | 轮动因子 | 含义 |
|--------|----------|----------|------|
| <30% | 快速轮动 | 1.3 | 惩罚加重（板块切换快，不宜集中） |
| 30-70% | 正常 | 1.0 | 正常 |
| >70% | 板块持续 | 0.7 | 惩罚放宽（主线明确，可集中持有） |

最终惩罚 = 各板块惩罚之和 × 轮动因子，范围 [-100, 0]

---

## 九、数据流完整路径

### 9.1 策略 Tab 选股流程

```
用户点击"建仓"
  → QuantFragmentBase.runDagPipeline(holdingPeriod, useCaseId, orderType, importDays)
    → QuantTaskScheduler.submit() (串行化 + 180s超时 + 前台服务)
      → DagTradeExecutor.execute()
        → PipelineXmlParser 解析 XML → NodeRegistry 实例化节点
        → Kahn 拓扑排序 → 分层并行执行
          → Layer 0: data_import + bg_manager
          → Layer 1: stock_pool/sector_pool (市场MA过滤)
          → Layer 2: candidate_pool (多周期热门注入)
          → Layer 3-N: 信号处理链 (merge → boost → bounce → strict_selection → ...)
          → 后段: smart_money → [news_guard] → ai_predict → generate_orders → [swap_weak] → position_merge
        → 结果写入 strategy_trade_orders + user_watchlist(选股)
      → onComplete: 刷新 UI + 超短线触发 checkT1AutoSell
```

### 9.2 自动卖出流程

```
用户点击"卖出评估"
  → runAutoSellEvaluation()
    → AutoSellEngine.evaluateAll(strategies, config)
      → 过滤: orderType == getQuantType() (按周期)
      → 获取实时价格 → 构建价格/量能历史(60天)
      → getSectorChangePct() (真实板块数据)
      → evaluatePosition() × N (10项卖出条件)
    → 缓存到 sellDecisionsCache
    → 显示卖出建议弹窗
用户点击"执行卖出"
  → executeAutoSell()
    → 使用缓存的 holdingToSell (不再重新评估)
    → AutoSellEngine.executeSells()
      → 全卖: 更新订单 SOLD
      → 部分卖: 减少数量 + 插入部分卖出记录 + 标记 [TP_TIER_N]
```

### 9.3 做T流程

```
用户点击"做T信号"
  → showTTradeMenu() / showTTradeDialog()
    → TTradeEngine.generateSignals(periodType)
      → 计算支撑/阻力 + RSI + 量价 + K线 + 趋势方向
      → 生成 T_BUY / RT_SELL 信号
      → 检查未平仓 → 生成 T_SELL / RT_BUY 配对腿
    → saveRecommendations(signals, source)
    → 显示信号列表 + 执行按钮
用户执行做T
  → TTradeEngine.executeTTrade(signal)
    → 开仓腿: 插入 OPEN 记录
    → 配对腿: 找到匹配 OPEN → 关闭 + 计算盈亏
    → 孤立配对腿: return 0L (不落库)
```

---

## 十、关键文件索引

| 文件 | 职责 |
|------|------|
| strategy/trade/QuantFragmentBase.kt | 模板方法基类(~4700行) |
| strategy/trade/UltraShortQuantFragment.kt | 超短线(T+1自动卖) |
| strategy/trade/ShortTermQuantFragment.kt | 短线(周期选择器) |
| strategy/trade/MidTermQuantFragment.kt | 中线(次日回测) |
| strategy/trade/LongTermQuantFragment.kt | 长线(最简) |
| strategy/trade/RealHoldingQuantFragment.kt | 实仓(OCR+独立数据) |
| strategy/trade/AutoSellEngine.kt | 10项卖出条件 |
| strategy/trade/TTradeEngine.kt | 做T引擎(手动) |
| strategy/trade/TradeModels.kt | 订单/拟合数据模型 |
| strategy/topology/pipelines/StockCheckPipeline.kt | 9项检查核心 |
| strategy/topology/nodes/StockEvaluationNode.kt | DAG评估节点 |
| strategy/topology/xml/NodeRegistry.kt | 47种节点注册 |
| strategy/topology/xml/DagTradeExecutor.kt | DAG执行器 |
| strategy/strategies/StrictSelectionStrategy.kt | 粘合严选策略 |
| strategy/Strategy.kt | Strategy接口+HoldingPeriod枚举 |
| strategy/StrategyEngine.kt | 策略引擎(加载/调度) |
| strategy/backtest/PipelineBacktestEngine.kt | 回测引擎 |
| assets/usecases/ultra_short_pipeline.xml | 超短pipeline(22节点) |
| assets/usecases/short_term_pipeline.xml | 短线pipeline(28节点) |
| assets/usecases/mid_term_pipeline.xml | 中线pipeline(30节点) |
| assets/usecases/long_term_pipeline.xml | 长线pipeline(24节点) |
| assets/usecases/real_holding_pipeline.xml | 实仓pipeline v4(8节点) |
