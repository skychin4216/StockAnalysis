# 选股思路（经验总纲）

> 最后更新：2026-08-13 | 本文汇总全 App 选股/风控的**经验规则**，供后续开发参考，避免遗漏。

---

## 一、大盘趋势判断（AMarketAnalysisEngine · 六维分析）

> 代码：`strategy/market/AMarketAnalysisEngine.kt`（指数代码 sh000001，60 日窗口）

| 维度 | 检测项 | 输出 |
|------|--------|------|
| 1. 趋势与形态 | 三日不新低（近3日 low ≥ 前3日最低low）/ 均线粘合 / 逃顶信号 | `isBottomConfirmed` / `isTrendUp` / `isTopDanger` |
| 2. 量能与资金 | 放量 / 缩量 / 平量 | `volumeStatus` |
| 3. 市场情绪与波动 | 全市场涨跌比 → 沸腾 / 温和 / 冰点 | `marketTemp` |
| 4. 权重股贡献度 | 大盘股 vs 小盘股涨幅差（黄白线偏离替代） | 预留 |
| 5. 宏观事件日历 | CPI / PPI 等 | 预留接口 |
| 6. 大盘状态机 | 综合决策 | `suggestedPeriod` + `suggestedPositionPct` |

### 状态机决策（decidePeriod）

- 底部确认 + 趋势向上 → **中线/长线**，高仓位（如 70%）
- 冰点情绪（涨跌比极低）+ 底部 → **短线抄底**，中仓位
- 顶部危险 / 趋势向下 → **超短快进快出** 或 空仓，低仓位
- 连涨/连跌预警：`consecutiveUpDays` / `consecutiveDownDays` → `streakRiskLevel`（LOW/MEDIUM/HIGH/EXTREME）
- 量价背离（涨但缩量）→ 风险提示

### MarketMaUnifiedNode（大盘均线统一检查）

- `threshold`：超短 0.015，其他周期 0.02（均线发散容忍度）
- `checkMode`：full（大盘均线粘合/多头排列才放行）
- 大盘弱 → 各节点自动收紧（effectiveLookback/effectiveConvergence 动态调整）

---

## 二、三天不新低（三日不创新低 · 底部确认）

> 代码：`StockCheckPipeline` 第 13 项 + `AMarketAnalysisEngine.checkBottomConfirmed` + `base_position_guard`

**规则**：近 3 个交易日最低价均 ≥ 前 3 天最低价 → 底部确认加分。

```kotlin
val last3 = snaps.takeLast(3)
val prevLow = snaps[snaps.size - 4].low
val threeDayNoNewLow = last3.all { it.low >= prevLow }
```

**使用位置**：
- **六项严选**第 2 项（`requireThreeDayConfirm=true` 时计入，非熊市启用）
- **打底仓守门**：三天不新低 + 均线粘合向上，才允许打底仓
- **大盘**：指数三日不新低 → `isBottomConfirmed`
- **腾龙换鸟**：中线空头排列 OR 创新低即换股；长线空头排列 AND 创新低才换股（更保守）

---

## 三、大A祖训（AncestralRulesNode · 五条量化规则）

> 代码：`strategy/topology/nodes/AncestralRulesNode.kt`（60 日回看窗口）

| # | 祖训 | 量化条件 | 各周期加减分 |
|---|------|---------|-------------|
| 1 | **高开要跑** | 高开>2% 且收阴 | 超短 **-15** / 短 -10 / 中 -5 / 长 -3 |
| 2 | **买无人问津时** | 换手<1% + 60日低位15%以内 | 超短 +3 / 短 +5 / 中 **+12** / 长 **+15** |
| 3 | **卖人声鼎沸时** | 换手>8% + 60日高位5% + 量价背离 | 超短 **-12** / 短 **-15** / 中 -10 / 长 -8 |
| 4 | **低位利空=利好** | 60日低位20% + 跌>3% + 下影线 | 超短 +5 / 短 +8 / 中 +15 / 长 **+20** |
| 5 | **高位利好=利空** | 60日高位5% + 涨>3% + 墓碑线 | 超短 **-10** / 短 **-12** / 中 **-15** / 长 **-15** |

> 解读：短线重情绪（高开/鼎沸），中长线重低位埋伏（无人问津/利空），祖训是**全周期通用经验层**。

---

## 四、K线图分析

### 4.1 盘口K线分析（intraday_analysis）

- 交易时段获取 **5 分钟 K 线**（≥5 根）
- 评估日内趋势与买卖点（冰点/沸点）
- 超短/短线 Pipeline 专有，为 T+1 / 做T 提供日内择时

### 4.2 形态侦测（candle_pattern · CandlePatternDetector）

经典 K 线形态识别，输出 `PatternMatch(patternName, direction)`：
- **看多形态**：锤子线 / 早晨之星 / 看涨吞没 等 → 加分
- **看空形态**：墓碑线 / 黄昏之星 / 看跌吞没 等 → 减分

用于：
- 做T 信号置信度（±15）
- 选股 Pipeline（`candle_pattern` 节点）

### 4.3 趋势方向（analyzeTrendDirection）

综合均线排列 + RSI + K线形态 → "准备上升/上升中/下跌中/准备下跌/盘整"，用于做T 与持仓风控。

---

## 五、六项严选（StockCheckPipeline · 均线多头粘合框架）

> 代码：`strategy/topology/pipelines/StockCheckPipeline.kt`

| # | 检查项 | 说明 |
|---|--------|------|
| 1 | MA 收敛向上 | 5/10/20/60 均线粘合（convergenceThreshold 按周期 3.0/3.0/2.5/2.0） |
| 2 | 三日不创新低 | 近 3 日 low ≥ 前 3 日最低 low |
| 3 | 历史低位 25% | close 在 60 日区间下 25% |
| 4 | PE 低估 | 估值过滤 |
| 5 | 周期活跃 | 成交量/换手处于周期活跃区间 |
| 6 | 冰点买入 | 价格处于日内/区间冰点 |

**通过门槛（minPassCount）**：超短 8 项 / 短线 7 项 / 中线 7 项 / 长线 8 项（含周期专属项）。

### 周期专属严选参数

| 周期 | 粘合阈值 | 粘合天数 | 放量倍率 | 回撤 | 要求 |
|------|---------|---------|---------|------|------|
| 超短 | 3.0 | 5 天 | 2.5x | ≥10% | 涨幅≥4% + 收盘站上粘合区上沿 + 开盘低于三线 |
| 短线 | 3.0 | 10 天 | 1.5x | ≥20% | 涨幅≥3% + 站上所有均线（含60日） |
| 中线 | 2.5 | 15 天 | 温和 1.2~1.8x | ≥30% | MA60 向上 + 站上所有均线 |
| 长线 | 2.0 | 30 天 | 地量 | ≥30% | MA60/MA250 同步上翘 + 站稳年线 |

---

## 六、冰点/沸点（日内择时）

> 代码：`IntradayAnalyzer` / `PricePositionAnalyzer`

- **冰点**：`pricePosition ≤ 0.2`（日内价格处于下 20%）→ 买点加分 +15
- **沸点**：`pricePosition ≥ 0.8`（日内价格处于上 20%）→ 卖点加分 +10
- 用于做T 信号与超短日内择时。

---

## 七、其他经验规则（避免遗漏）

### 7.1 板块轮动惩罚（rotation_penalty）

板块涨幅超阈值天数越多，每超一天罚 10 分（追高惩罚，`thresholdDays=3`）。

### 7.2 新闻拦截（news_guard）

- 利空新闻（impact≥75 / sentiment<0）拦截
- 新闻力度近 3 日评估（news_strength）

### 7.3 主力资金过滤（smart_money_filter）

- 超短 minScore=30（V 型票天然低分）
- 其他周期 minScore=55

### 7.4 防守高息（defensive_dividend）

熊市/震荡启动：银行/电力/高速公路等防御板块，低 PB（≤1.5/1.2）+ 低负债 + 高股息代理。

### 7.5 打底仓守门（base_position_guard）

「长线看势，中线看价，短线看量，超短看情绪；逃顶要快，抄底要慢」

### 7.6 腾龙换鸟（swap_weak）

- 卖出最弱持仓（空头排列/创新低/回撤超限）
- 买入更强股票；长线需双重确认（空头排列 AND 创新低）才换股
- 换股后自动诊断分析（HoldingDiagnosticAnalyzer）

### 7.7 大A 短线口诀

- 「要热点热点热点」—— 短线热度优先
- 「买无人问津时，卖人声鼎沸时」—— 逆向思维
- 「逃顶要快，抄底要慢」—— 顶快底慢

---

## 八、相关文档

- [策略 · 超短线](strategy-ultra-short.md)
- [策略 · 短线](strategy-short-term.md)
- [策略 · 中线](strategy-mid-term.md)
- [策略 · 长线](strategy-long-term.md)
- [做T系统](../topology/t-trade-system.md)
