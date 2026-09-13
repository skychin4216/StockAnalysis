# StockAnalysis 总架构文档（入口）

> 最后更新：2026-09-06 | 对应 DB version: 26 | Kotlin + Android + AutoQuant(Python)
> 本文档是**入口**。双端总览（含 mermaid 图）见 [architecture/dual-end-overview.md](architecture/dual-end-overview.md)；
> APK 单端 5 层 SVG 架构图见 [architecture/index.html](architecture/index.html)。

---

## 〇、双端总览（先看这里）

本项目为双端闭环：**AutoQuant（`AutoQuant/`，Python/PyQt5，Windows exe）** 负责 PC 回测/拟合/选股/Agent，
**StockAnalysis APK（`app/`，Kotlin）** 负责手机行情/工作台/对话/监控。二者经 `backtest_params.json`
（唯一参数源）、`data_service.py HTTP :8888`（PC 桥，⚠️ **2026-09-13 起定位变更：`0.0.0.0:8888` 局域网直连将降为仅本机监听，双端通讯改为腾讯云 COS 中继，不填 IP**，见 [bridge-relay-design.md](bridge-relay-design.md)）、COS/远程控制互相打通。详见
[architecture/dual-end-overview.md](architecture/dual-end-overview.md)。

## 一、文档导航（点击进入子文档）

| 领域 | 子文档 | 说明 |
|------|--------|------|
| 双端总览 | [architecture/dual-end-overview.md](architecture/dual-end-overview.md) | 双端拓扑 mermaid 图 + 职责/链路对照 |
| AutoQuant exe 地图 | `skills/autoquant/README.md` | PC 端逐模块地图（Python） |
| 多 Agent 对话 | [ai-multi-agent-chat-architecture.md](ai-multi-agent-chat-architecture.md) | 对话多 Agent 重构设计（专家注册表+编排器） |
| 智能体体系 | [agent/agent-architecture.md](agent/agent-architecture.md) | 项目 Agent 架构：Orchestrator / Scout / Analyst / Guardian / Executor |
| LLM 接入 | [agent/llm-architecture.md](agent/llm-architecture.md) | LLM 架构（legacy）：Provider / 场景模型选择 / 路由 |
| AI 对话框架 | [agent/ai-conversation-framework.md](agent/ai-conversation-framework.md) | 对话历史持久化、意图解析、分享路由 |
| 智能体 TAB | [ui/agent-tab-architecture.md](ui/agent-tab-architecture.md) | 智能体 Tab：Agent 列表 / 创建 / 对话页 |
| 股票 TAB | [ui/stock-tab-architecture.md](ui/stock-tab-architecture.md) | 股票 Tab：行情 / 自选 / 板块轮动 / 机构推荐 |
| 策略 TAB | [ui/strategy-tab-architecture.md](ui/strategy-tab-architecture.md) | 策略 Tab：工作台(四周期+实仓+ETF低位) / 策略 / 数据 / AI分析 |
| 我的 TAB | [ui/mine-tab-architecture.md](ui/mine-tab-architecture.md) | 我的 Tab：设置 / 备份 / 通知配置 |
| ETF 低位 | `skills/engine-sync` + [SCRIPTS.md](../smalltools/SCRIPTS.md) | ETF 低吸引擎 v0.3 + exe/apk 双端 tab |
| 策略 · 超短线 | [strategy/strategy-ultra-short.md](strategy/strategy-ultra-short.md) | 1 天持有，盘口K线 + 祖训严选 |
| 策略 · 短线 | [strategy/strategy-short-term.md](strategy/strategy-short-term.md) | 1-14 天，主力意图 + 趋势跟随 |
| 策略 · 中线 | [strategy/strategy-mid-term.md](strategy/strategy-mid-term.md) | 30-180 天，打底仓守门 + 腾龙换鸟 |
| 策略 · 长线 | [strategy/strategy-long-term.md](strategy/strategy-long-term.md) | 180-365 天，机构积累 + 低估值 |
| 策略 · 实仓 | [strategy/strategy-real-position.md](strategy/strategy-real-position.md) | 持仓管理 / 做T反T / 风控结算 |
| 选股思路 | [strategy/stock-picking-methodology.md](strategy/stock-picking-methodology.md) | 大盘趋势 / 三天不新低 / 大A祖训 / K线图分析 |
| 后台服务 | [background/background-services.md](background/background-services.md) | AppBackgroundRunner / 通知 / 前台服务 |
| 做T系统 | [topology/t-trade-system.md](topology/t-trade-system.md) | 做T/反T 全流程（含时序图、UI 入口、时段权重） |
| 时段策略 | [topology/t-trade-time-slots.md](topology/t-trade-time-slots.md) | 做T 时段权重专项（已被 t-trade-system 收录） |
| Pipeline 编辑器 | [topology/pipeline-editor-design.md](topology/pipeline-editor-design.md) | DAG 可视化编辑器设计 |

---

## 二、系统概览

A股智能分析 Android App，核心能力：**四周期量化选股 + 日内做T + 持仓风控 + DAG Pipeline 可视化编辑**。

### 技术栈

Kotlin / MVVM / ViewBinding / Room DB / OkHttp / MPAndroidChart / Gradle (KSP)

### 入口结构（底部 5 Tab，豆包风格）

```
MainActivity
├── ChatTabFragment    (对话 · AI 助手，QUICK/DEEP/EXPERT + 多Agent编排)
├── AgentTabFragment   (智能体 · 自建 Agent 列表)
├── StockTabFragment   (股票 · 精选/K线趋势/热门行情/热点新闻 4 子页)
├── StrategyFragment   (量化选股 · 4 子页)
│   ├── Tab 0: QuantWorkbenchFragment (工作台 · 短/中/长/实仓/🧲ETF低位 5 内页)
│   ├── Tab 1: StrategyListFragment   (策略沙盒)
│   ├── Tab 2: StrategyImportFragment (数据管理/拟合/PC参数/远程)
│   └── Tab 3: AIAnalysisFragment     (AI 深度分析)
└── SettingsFragment   (我的 · API配置/云同步/远程控制)
```

> 2026-09 迁移：量化选股主入口已从「四周期直开」改为「工作台 5 页签
> （短/中/长周期 + 💰实仓 + 🧲ETF低位）」，超短引擎并入短线。

---

## 三、DAG Pipeline 引擎

### 核心抽象

```
BaseNode<I, O>          — 所有节点的基类，定义 execute(context, input): O
BasePipeline            — XML 定义的 DAG Pipeline
BaseUseCase             — 业务场景，组合多个 Pipeline
PipelineContext          — 执行上下文（stage 输出、日志、股票流动记录）
DagTradeExecutor        — 通用执行器，供四个周期 Tab 复用
```

### Kahn 拓扑排序

DAG 调度使用 Kahn 算法进行拓扑排序，确保节点按依赖顺序执行。同层节点并行执行。

### UseCase XML 定义

```
assets/usecases/
├── ultra_short_pipeline.xml   — 超短线（含盘口K线分析）
├── short_term_pipeline.xml    — 短线（含盘口K线分析）
├── mid_term_pipeline.xml      — 中线（含打底仓守门 + 持仓风控）
├── long_term_pipeline.xml     — 长线（含打底仓守门 + 持仓风控）
└── t_trade_pipeline.xml       — 做T/反T（12节点 · 5层）
```

### 节点注册 (NodeRegistry)

XML `module` 属性 → Node 工厂函数的映射。当前已注册 ~25 个 module。

### 节点股票流动追踪

每个节点通过 `context.recordStockFlow()` 记录：
- inputCount / outputCount / filterCount
- filterReason（中文过滤原因）
- outputCodes（输出的股票代码列表）

供 UI 显示「哪些节点过滤了多少股票」。

---

## 四、策略体系

### Strategy 接口

```kotlin
interface Strategy {
    val id: String
    val name: String
    val holdingPeriods: List<HoldingPeriod>
    fun screen(params: ScreenParams): List<StockCandidate>
}
```

### 四周期 HoldingPeriod

| 周期 | 持有天数 | 策略数量 | 特殊节点 |
|------|---------|---------|---------|
| ULTRA_SHORT | 1天 | 3 | 盘口K线分析 (5min) |
| SHORT | 1-14天 | 5 | 盘口K线分析 (5min) |
| MID | 30-180天 | 7 | 打底仓守门 + 持仓风控 + 腾龙换鸟 |
| LONG | 180-365天 | 5 | 打底仓守门 + 持仓风控 |

### 策略注册 (StrategyEngineHolder)

启动时注册 20 个内置策略，通过 `StrategyEngine.setEnabled()` 控制启用/禁用，状态持久化到 SharedPreferences。

### 六项严选检查 (StockEvaluationNode)

所有周期共用，`minPassCount` 控制通过门槛：
1. MA 收敛向上
2. 三日不创新低
3. 历史低位 25%
4. PE 低估
5. 周期活跃
6. 冰点买入

超短线/短线：4/6 通过（66.7%）；中线/长线：5/6 通过（83.3%）

### 大A祖训 (AncestralRulesNode)

选股/做T 通用经验规则，详见 [选股思路](strategy/stock-picking-methodology.md)。

---

## 五、做T/反T 系统

### T-Trade Pipeline (12 节点 · 5 层)

```
Layer 0: bg_manager + adaptive_params + t_trade_import
Layer 1: t_holdings_load + market_context + zipline_factor
Layer 2: t_inst_intent + candle_pattern + news_strength + news_guard
Layer 3: t_signal_synthesize (聚合)
Layer 4: t_recommend_save
```

完整流程见 [做T系统文档](topology/t-trade-system.md)（含时序图、UI 展示入口、时段权重表）。

---

## 六、持仓风控

### HoldingGuardNode (n_guard)

持仓风控评估：硬止损 / 最大回撤止损 / 阶梯止盈 / 时间强制平仓 / 趋势反转 / RSI超买 / 放量滞涨 / 板块走弱

### SwapWeakNode (n_swap)

腾龙换鸟：卖出最弱持仓，买入更强股票

### 持仓诊断分析 (HoldingDiagnosticAnalyzer)

腾龙换鸟/风控执行后，自动分析被卖出股票的原因，生成中文改进建议，写入报告。

---

## 七、失败分析 (PipelineFailureAnalyzer)

当 Pipeline 输出订单为 0 时，自动定位「杀手节点」——第一个将输出降为 0 的关键节点。在 UI 以中文显示：失败节点名称 + 原因 + 改进建议。

---

## 八、数据层

### Room Database (version 26)

主要表：daily_snapshot / strategy_trade_order / real_position / t_trade_records / t_trade_recommendations / intraday_kline / daily_period_result / institutional_pick / backtest_meta / backtest_selected_stock / user_focus_sector
（v14→v26 新增：intraday_kline、institutional_pick、backtest_*、user_focus_sector 等）

### 数据源

- 东财 API (EastMoney)：日K (klt=101) / 5分钟K (klt=5) / 实时行情
- 新闻 API：板块新闻 + 个股新闻 + 黑名单过滤
- 外盘数据：纳斯达克 / 韩国 / 恒指

---

## 九、UI 报告体系

### DagExecResult

```
success / ordersCount / mergeSummary / swapSummary / guardSummary
patternSummary / stockFlowLines / failureAnalysis / diagnosticSummary
```

### 报告持久化

`savePipelineReport()` → `daily_period_result` 表
- `pipelineFlowJson`: 完整节点流动 + 严选结果 + 失败分析 + 持仓诊断
- `filteredReasonJson`: 节点流动摘要文本

---

## 十、后台服务

`AppBackgroundRunner` 统一后台调度：定时选股 / 做T监控 / 持仓风控 / 通知推送。详见 [后台服务架构](background/background-services.md)。

---

## 附：历史/可视化文档

| 领域 | 文档 |
|------|------|
| 策略分类 | [strategy-classification-analysis.md](strategy-classification-analysis.md) |
| 策略修复 | [strategy-optimization-plan.md](strategy-optimization-plan.md) |
| 迁移历史 | [hardcode-to-dag-migration-plan.md](hardcode-to-dag-migration-plan.md) |
| 架构重构记录 | [agent-architecture-refactoring-plan.md](agent-architecture-refactoring-plan.md) |
| 任务日志 | [recent-tasks-and-implementation-review.md](recent-tasks-and-implementation-review.md) |
| 持仓利润架构 | [period_holding_profit_architecture.html](period_holding_profit_architecture.html) |
| 策略架构可视化 | [strategy/strategy-architecture/strategy-architecture.html](strategy/strategy-architecture/strategy-architecture.html) |
| 策略文档可视化 | [strategy/strategy-docs/strategy-docs.html](strategy/strategy-docs/strategy-docs.html) |
| 架构可视化 | [architecture/index.html](architecture/index.html) |
| 趋势图 | [trend_charts/index.html](trend_charts/index.html) |
| 智能体选股计划 | [agent-stock-picking-plan/agent-stock-picking-plan.html](agent-stock-picking-plan/agent-stock-picking-plan.html) |
