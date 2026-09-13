# Trade Engine Skill — 交易执行引擎

## 模块职责
订单生成、执行、自动卖出、持仓管理的核心交易引擎。包括 T+0 交易引擎、自动卖出引擎、
自动调仓、持仓诊断、宏观环境分析等。

## 触发条件
用户提到以下关键词时，优先查阅本 Skill：
- 自动卖出 / 卖出 / 止损 / 止盈 / 调仓
- T+0 / TTradeEngine / 交易执行
- 持仓 / 一键清仓 / 自动交易
- 宏观环境 / 大盘趋势 / 事件场景

## 关键文件（strategy/trade/）
| 文件 | 职责 |
|------|------|
| `TTradeEngine.kt` | **T+0 交易引擎**（核心，订单执行） |
| `AutoSellEngine.kt` | 自动卖出引擎（止损/止盈） |
| `AutoTradePortfolioEngine.kt` | 自动调仓引擎 |
| `HotSectorStockPool.kt` | 热门板块股票池 |
| `StrategyFittingEngine.kt` | 策略拟合 |
| `TradeDecisionHelper.kt` / `TradeModels.kt` / `TTradeModels.kt` | 交易决策辅助/模型 |
| `MarketTrendGuard.kt` | 市场趋势防护 |
| `UnifiedStockClassifier.kt` / `IndustryThemeClassifier.kt` | 股票分类 |
| `macro/` 子目录 | 宏观环境分析 |
| ├─ `MacroEnvironmentAnalyzer.kt` | 宏观环境分析器 |
| ├─ `MacroMarketDataFetcher.kt` | 宏观行情数据 |
| ├─ `EventScenarioEngine.kt` | 事件场景引擎 |
| └─ `IndexDeviationMonitor.kt` | 指数背离监测 |
| `LongTermQuantFragment.kt` / `MidTermQuantFragment.kt` / `ShortTermQuantFragment.kt` / `UltraShortQuantFragment.kt` | 四大周期量化 UI |
| `QuantFragmentBase.kt` | 量化 Fragment 基类 |
| `RealHoldingQuantFragment.kt` / `RealHoldingAnalysisNode` | 真实持仓分析 |

## 常见任务指引
### 1. 修改卖出条件
- 改 `AutoSellEngine.kt` 中的止损/止盈阈值

### 2. 修改 T+0 交易逻辑
- 改 `TTradeEngine.kt` 的订单生成/执行流程

### 3. 宏观环境判断
- 改 `macro/MacroEnvironmentAnalyzer.kt`（一键建仓的市场环境也引用宏观分析）

## 文件清单
- `skills/trade-engine/README.md` — 本文件（skill 定义）
