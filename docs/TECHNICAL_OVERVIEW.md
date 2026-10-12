# StockAnalysis 技术文档（系统总览）

> 版本：对应 APK v1.1.0（versionCode 2）/ Room v27 / 2026-09-22 代码现状
> 本文是面向开发者的**全仓库技术总览**：架构、模块、数据流、调度、构建与扩展指南。
> 业务策略口径与 AI 协作约定见根目录 [AGENTS.md](../AGENTS.md)；逐脚本索引见 [smalltools/SCRIPTS.md](../smalltools/SCRIPTS.md)。

---

## 1. 项目定位

A 股选股引擎，**双端闭环**：

| 端 | 目录 | 技术栈 | 职责 |
|----|------|--------|------|
| **Android APK**（本仓库主工程） | `app/` | Kotlin + ViewBinding + Room + OkHttp | 手机行情、量化工作台、对话/Agent、监控推送、本地跑选股 DAG |
| **PC 工具集**（本仓库） | `smalltools/` | Python 3（纯标准库为主，200+ 脚本） | 盘段守护、选股推送、情报采集、回测拟合、数据抓取、COS 同步 |
| **AutoQuant exe**（外部独立仓库 `AutoQuant/`，不在本仓库内） | — | Python + PyQt5 | PC 回测/拟合 GUI、Agent 决策、参数产出方 |

**第一设计原则——单一事实源**：

```
app/src/main/assets/   ← 双端唯一事实源
├── usecases/*.xml     ← 选股 DAG（usecase + pipeline，60+ XML）
├── usecases/usecase_pipeline.py  ← XML 的 Python 执行引擎（与 XML 同居）
├── backtest_params.json          ← 四周期拟合参数
├── data/*.json                   ← 机构持仓/国家队/ETF 份额等镜像数据
├── datasources.json              ← 47 个 HTTP 数据源登记
└── macro_events/event_library.json ← 宏观事件库
```

APK 的 Kotlin 引擎与 PC 的 Python 引擎**读同一份 XML**：改 XML 即改选股逻辑，双端无需各自改代码。
PC 产出的数据 JSON 镜像进 assets，APK 只读不算，杜绝双端口径漂移。

---

## 2. 仓库结构

```
StockAnalysis/
├── app/                              # Android 主模块
│   ├── src/main/assets/              # ★ 单一事实源（XML DAG / 参数 / 数据 / 配置）
│   ├── src/main/java/com/chin/stockanalysis/
│   │   ├── strategy/topology/        # ★ Kotlin DAG 引擎（core/xml/nodes/pipelines/ui/viz）
│   │   ├── agent/                    # 多 Agent 对话（hub 10 专家 / router / framework）
│   │   ├── ai/                       # AI 编排、Provider 池、意图预测
│   │   ├── stock/                    # 行情数据层（多源竞速/缓存/意图/数据库）
│   │   ├── ui/                       # 5 Tab 的全部 Fragment
│   │   ├── cloud/                    # 腾讯云 COS 同步 + adb 指令接收
│   │   ├── news/ skill/ memory/ notification/ ...
│   ├── build.gradle.kts
├── smalltools/                       # ★ PC Python 工具集（200+ 脚本）
│   ├── usecase 引擎入口（经 sys.path 指向 assets/usecases/usecase_pipeline.py）
│   ├── _publish_candidates.py        # 盘段守护主进程（~5000 行）
│   ├── _market_scan.py / _daily_intel.py / _news_watch.py / _offhour_watch.py
│   ├── _daemon_ctl.py                # 四个守护的统一控制层
│   ├── _stock_fundflow.py / _sector_fundflow.py / _main_force.py / _chip_dist.py
│   ├── _walk_forward.py / _profit_backtest.py / _export_params.py
│   └── SCRIPTS.md / README.md
├── data/                             # PC 侧运行态数据（kline_store.json 208MB、各类缓存、推送产物）
├── docs/                             # 架构/策略/UI 文档（本文所在）
├── skills/                           # 各功能域的开发说明（PROJECT_MAP.md 是功能地图）
├── .codebuddy/skills/stock-daemon/   # 守护启停 PowerShell 封装
├── build.gradle.kts / settings.gradle.kts / gradlew
└── AGENTS.md                         # AI 协作约定 + 已确认设计口径
```

> 说明：`assets/topologies/*.xml`（hot_spot / news_first / parallel_engine 等）为早期拓扑实验文件，
> 当前代码无引用；选股主线一律走 `assets/usecases/`。

---

## 3. 核心：XML DAG 选股引擎

### 3.1 概念模型

```
BaseNode<I,O>   节点：execute(context, input) -> output，可记录股票流动（进/出/过滤原因）
DagPipeline     一张有向无环图：NodeList + Links（SourceNodeId→TargetNodeId）
BaseUseCase     业务场景：按 <steps> 顺序引用若干 pipeline，支持 if 条件路由
PipelineContext 跨 pipeline 共享：stageOutputs（按 nodeId 取上游输出）、市场状态、日志、订单
DagTradeExecutor 通用执行器，四周期/资金流/ETF/做T 全部复用
```

- 调度算法：**Kahn 拓扑排序**（同层可并行；Python/Kotlin 两端实现一致，均按 XML 出现顺序稳定排序，含环检测）。
- 节点间数据传递：每个节点输出写入 `stageOutputs[nodeId]`，下游通过 config 中的 `sourceNode` 声明读哪个上游。
- 股票流动追踪：每节点记录 inputCount/outputCount/filterCount/filterReason/outputCodes，供 UI 展示「哪层杀了多少票」。

### 3.2 XML Schema

**Pipeline XML**（`*_pipeline.xml`，根元素 `<DagPipeline>`）：

```xml
<DagPipeline id="fund_flow_dag" name="资金流选股Pipeline">
  <NodeList>
    <Node id="n_ff_sector" name="板块资金流榜" module="fund_flow_sector">
      <config><param name="topN" value="15" /></config>
    </Node>
    <!-- 更多节点 -->
  </NodeList>
  <Links>
    <Link><SourceNodeId>n_ff_sector</SourceNodeId><TargetNodeId>n_ff_screen</TargetNodeId></Link>
  </Links>
</DagPipeline>
```

**UseCase XML**（`*_usecase.xml`）：用 `<steps>` 组合 pipeline，`if` 支持表达式条件路由：

```xml
<usecase id="short_term" name="短线量化">
  <steps>
    <pipeline ref="usecases/market_rotation_pipeline.xml" />           <!-- 公共：大盘研判 -->
    <pipeline ref="usecases/stock_picking_common_pipeline.xml" />      <!-- 公共：股票池/参数 -->
    <pipeline ref="usecases/short_term_bullish_pipeline.xml"
              if="${n_market_direction}.direction == 'BULLISH'" />     <!-- 牛市分支 -->
    <pipeline ref="usecases/short_term_oscillation_pipeline.xml"
              if="${n_market_direction}.direction == 'OSCILLATION'" /> <!-- 震荡分支 -->
    <pipeline ref="usecases/short_term_bearish_pipeline.xml"
              if="${n_market_direction}.direction == 'BEARISH'" />     <!-- 熊市分支 -->
  </steps>
  <config><param name="maxHoldings" value="5" /></config>
</usecase>
```

**周期 × 大盘环境矩阵**：超短/短/中/长四个周期各有 bullish / oscillation / bearish 三套环境 pipeline，
由大盘方向节点（三指数 MA 投票：BULLISH / OSCILLATION / BEARISH）在运行时路由，共 12 条分支 + 公共 pipeline。

### 3.3 双端引擎对照

| 维度 | Python 端 | Kotlin 端 |
|------|-----------|-----------|
| 引擎文件 | [usecase_pipeline.py](../app/src/main/assets/usecases/usecase_pipeline.py)（~6400 行，与 XML 同居 assets） | `strategy/topology/core` + `xml` 包 |
| 图/解析 | `Node` / `Pipeline.build()` Kahn 排序 / `parse_pipeline()`（L62-147） | `DagPipeline` / `PipelineXmlParser.kt` |
| 注册表 | `NODE_IMPLS` 字典 + `@register("module")` 装饰器（L220-228） | `NodeRegistry.kt` 单例（module → 工厂函数） |
| 上下文 | `Ctx`（stage_outputs / market / orders / asof 回测日期） | `PipelineContext`（stageOutputs / 日志 / 股票流动） |
| 执行入口 | `UseCaseRunner(usecase_id, cache, asof).run(with_stages=True)` → `{orders, market_state, stages, candidates}` | `UseCaseLoader.init()` → `DagTradeExecutor` → `DagExecResult` |
| Android 依赖节点 | 回测中降级为「通过/空操作」并记 `ctx.notes` | 完整实现（AI/新闻/持仓/Room） |

语义版本机制：Python 端对引擎 `.py`（AST dump）与 `.xml`（canonicalize）算 **sha1 语义摘要**，
仅在代码/流水线真正变化时判定版本变化（改注释空行不算）。

### 3.4 节点（module）目录

Python 注册 ~50 个 module，Kotlin 注册 ~70 个（含兼容/UI 辅助节点），主要分类：

| 分类 | 代表 module | 功能 |
|------|-------------|------|
| 数据准备 | `data_import` `market_context` `adaptive_params` `stock_pool` `pool_filter` `candidate_pool` | K 线充足性校验、大盘上下文、周期参数自适应、股票池 |
| 大盘研判 | `market_direction` `a_market_analysis` `market_ma_unified` `style_rotation` `sector_strength` `direction_label` | 三指数投票定方向、风格轮动、板块强弱 |
| 评分/增强 | `sector_boost` `volume_price_factor` `macro_event_bias` `bounce_reversal` `seasonality_boost` `leader_track` `news_strength` `reversal_confirm` `main_force_stage` | 板块加权、相对强度、事件库偏置、季节日历、龙头跟踪、主力四态（建仓/拉升/洗盘/出货） |
| 过滤/严选 | `strict_selection`(六项严选) `ancestral_rules`(大A祖训) `kline_idiom`(K线口诀) `smart_money_filter` `news_guard` `rotation_penalty` `ma_convergence` `financial_health` `defensive_dividend` | 均线粘合、三日不新低、PE 低估等硬门槛 |
| 聚合 | `signal_merge` `cross_day_aggregation` `position_merge` | 多策略信号合并打分、跨日聚合、持仓合并 |
| 交易动作 | `generate_orders` `swap_weak`(腾龙换鸟) `holding_guard`(持仓风控) `base_position_guard` `stop_loss_vote`(六理论止损) `t1_auto_sell` | 订单生成（含三重拦截 + 保送直达）、风控 |
| 机构/国家队 | `inst_buy_recent` `inst_pool_build` `inst_holding_judge` `super_holder_gate` `national_flow` `national_etf_share` | 机构增持保送、机构持仓 A/B/C 评级、国家队+社保双买超级闸、十大流通股东、宽基 ETF 份额 |
| 资金流 | `fund_flow_sector` `fund_flow_screen` `fund_flow_level` `etf_flow_leader` | 板块资金流榜 → 个股 v2.1 双层评分（趋势 0-6 + 质量 0-4）→ 级别闸 |
| ETF 系列 | `etf_gate` `etf_dip_signal` `etf_exit_policy` `etf_holdings_rank` `etf_industry_scan` `etf_pure_screen` | ETF 低吸、重仓股 top5、行业扫描、纯 ETF 本体筛选 |
| 恐慌抄底/埋伏 | `dip_market_gate` `dip_stock_signal` `dip_exit_policy` `sector_ambush_signal` `ambush_exit_policy` | 大盘连跌门控、缩量深跌选股、热门板块（动量榜∪净流入榜）埋伏 |
| 做T | `t_trade_import` `t_holdings_load` `t_inst_intent` `candle_pattern` `news_strength` `news_guard` `t_signal_synthesize` `t_recommend_save` | 12 节点 5 层做T/反T 链 |
| 其他 | `ai_predict` `zipline_factor` `bg_manager` `fitting_save` `crosstab_publish` `intraday_analysis` | AI 预测、滑索因子、后台管理、拟合落盘 |

> 新增节点的参数一律在 XML `<config>` 中声明并给默认值，NodeRegistry 工厂读 config 构造，
> 保证「调参不改代码、双端同源」。

### 3.5 订单生成与「保送直达」机制

`generate_orders` 是所有选股链的终点，常规候选要过**三重拦截**：趋势门控（`_trend_match_3way`）、
K 线形态否决（`_candle_veto_bearish`）、口诀否决（`_idiom_buy_veto`），外加评分阈值、同日去重、仓位预算。

两类**直达订单**（跳过三重拦截，回测验证后放行）：

- `instBuyHits`（默认）：`inst_buy_recent` / `super_holder_gate` 命中的机构保送票
  （东财十大流通股东：披露 ≤35 天 + 增持 + 机构身份权重 国家队95/社保90/大基金88/公募78/险资76/QFII74/北向72；
  香港中央结算（代理人）≠ 北向，双端排除；机构已退出最新期则旧增持作废）。
- `ffDirectHits`：资金流级别闸 `level=4`（direct，≥8 分）；`directField` 参数可配。
  级别 1=strict(≥8) / 2=normal(≥6，默认) / 3=aggressive(≥5) / 4=direct。

业务硬口径：单轮买入上限 **5 只**（2026-09-17 由 3 调 5，三态 XML 均已同步）。

### 3.6 执行报告体系

Kotlin 端 `DagExecResult`：success / ordersCount / mergeSummary / swapSummary / guardSummary /
patternSummary / stockFlowLines / failureAnalysis / diagnosticSummary。

- `PipelineFailureAnalyzer`：输出 0 单时自动定位第一个把输出杀到 0 的「杀手节点」，给中文原因与改进建议。
- `HoldingDiagnosticAnalyzer`：腾龙换鸟/风控卖出后自动生成中文改进建议。
- 报告持久化到 Room `daily_period_result` 表（`pipelineFlowJson` 完整流动 + `filteredReasonJson` 摘要）。

### 3.7 主要 UseCase 清单

| UseCase | 内容 |
|----------|------|
| ultra_short / short / mid / long_term | 四周期（各带牛/震荡/熊三态 pipeline） |
| dip_buy | 恐慌日抄底；t_trade | 做T/反T |
| fund_flow | 板块→个股资金流双层评分→级别闸 |
| sector_ambush / etf_ambush | 板块埋伏（topSectors 动量 ∪ inflowSectors 净流入，各取 10） |
| etf_dip / etf_holdings_top5 / etf_industry_scan / etf_pure_screen | ETF 四条选股线 |
| inst_holding | 机构池汇总 → 机构 A/B/C 评级 → 六理论止损（《交易决策工作台》数据源） |
| national_etf_share | 宽基 ETF 份额观测国家队（汇金/证金）申赎 |
| market_analysis / stock_deep_analysis / real_holding / complete_closed_loop | 公共研判、个股深度、实仓、豆包闭环 |
| ai_filter / screening / strategy_screening / data_prep | 筛选与 AI 过滤 |

---

## 4. Android APK 架构

### 4.1 构建与技术栈

- Gradle Kotlin DSL + KSP；`compileSdk/targetSdk = 34`，`minSdk = 26`，Java 11。
- 依赖：AndroidX、Material、ViewPager2/Fragment、**Room 2.6.1**、**OkHttp 4.12**、Gson、
  **MPAndroidChart**（K 线图）、**Markwon**（Markdown 渲染）、**ML Kit 中文 OCR**（截图导入持仓）、
  **WorkManager**（OS 级定时兜底）、**腾讯云 COS 官方 SDK 5.9.52**（自研 CosSigner 作 fallback）。
- Release 签名读本地 `keystore.properties`（不入库）；`secret.masterKey`（AES-256-GCM）注入 BuildConfig，
  用于解密 `assets/data/secrets.enc` 中的云端密钥（密文可入库）。
- 构建：`gradlew assembleDebug`；CI：`.github/workflows/android-test.yml`。

### 4.2 入口与 UI（底部 5 Tab）

```
ui.MainActivity（singleTop，接收微信截图/PDF/文本分享）
├── ChatTabFragment     对话：QUICK/DEEP/EXPERT，多 Agent 编排
├── AgentTabFragment    智能体：自建 Agent 列表 + AgentChatFragment
├── StockTabFragment    股票：精选 / K线趋势 / 热门行情（全球指数+板块+龙头图谱）/ 热点新闻
├── StrategyFragment    量化选股：
│   ├── QuantWorkbenchFragment  工作台（短/中/长 + 实仓 + 🧲ETF低位）
│   ├── StrategyListFragment    策略沙盒
│   ├── StrategyImportFragment  数据管理/拟合/PC参数/远程
│   └── AIAnalysisFragment      AI 深度分析
└── SettingsFragment    我的：API 配置 / 云同步 / 远程控制 / 通知
```

其他组件：`TopologyEditorActivity`（DAG 可视化编辑器，`strategy/topology/ui/` 含画布、自动布局、流程图）；
`QuantForegroundService`（pipeline 执行期前台服务防杀）；
`cloud.CommandReceiver`（adb 广播 `com.chin.stockanalysis.CMD` 触发「查新鲜度→下载→同步 COS」）。

### 4.3 包结构（`com.chin.stockanalysis`）

| 包 | 职责 |
|----|------|
| `strategy.topology.core` | BaseNode/BasePipeline/BaseUseCase/DagPipeline/PipelineContext 等核心抽象 |
| `strategy.topology.xml` | NodeRegistry / UseCaseLoader / PipelineXmlParser / DagTradeExecutor / FailureAnalyzer |
| `strategy.topology.nodes` | ~40 个节点实现文件（PipelineNodes、FundFlowNodes、EtfScreenNodes、InstHoldingNodes、NationalFlowNodes、DipBuyNodes、SectorAmbushNodes、TTradePipelineNodes 等） |
| `strategy.topology.pipelines` | 旧版硬编码管线（StockCheckPipeline/QuantTradingPipeline，DAG 化前的兼容链） |
| `strategy` | Strategy 接口、StrategyEngine（20 个内置策略启用/禁用）、回测、交易模型 |
| `stock.data` | 行情数据层：SmartStockCache（按时段 TTL 1s/5m/30m/1h）、多源并发竞速、StockDataFacade、SmartStockCache、StockNameTrie |
| `stock.database` | Room v27（见 4.5） |
| `stock.intent` | 意图识别职责链（代码→名称→指数→AI 兜底） |
| `agent` | Agent 框架：core（AgentNode/IntentRouter/PlanningAgent）、hub（AgentHub）、router、chat |
| `ai` | AiOrchestrator、AiProviderPool/Selector（多厂商）、BackgroundPredictor、IntentPredictionEngine、SectorDetector、TokenUsageTracker |
| `cloud` | CloudSyncManager、CosXmlClient/CosSigner、CommandReceiver |
| `news` | NewsFactor 实体与评分、热点板块新闻更新 |
| `skill` | Skill 引擎（skills_config.json / skill_pick_pool.json） |
| `memory` | KeyMemory Room 持久化（对话长期记忆） |
| `notification` `update` `storage` `util` `config` | 通知、App 自更新、备份、日志/拼音/JSON、FeatureFlag/多语言 |

### 4.4 AI / 多 Agent

- **Provider**：OpenAI 兼容协议统一接入（`OpenAiCompatibleProvider`：SSE 流式 + 自动重试 + 模型回退），
  `ApiConfigManager` 多厂商配置（DeepSeek / 硅基流动 / 豆包 / 阿里百炼等），密钥经 `ApiKeysLoader` 加载。
- **AgentHub 10 专家域**：TECHNICAL 技术面、FUNDAMENTAL 基本面、INDEX 指数、SECTOR 板块、
  PICKING 选股、ETF_FLOW、NEWS 新闻、MACRO 宏观、PORTFOLIO 组合、DAG。对话命中 ≥2 域时自动编排多专家。
- 另有 `IntentRouter`（Chat/News/Risk/Trade 路由）、PlanningAgent、ChatTools（ReAct 工具调用）、
  BackgroundPredictor（后台预预测）、SmartContextWindow（上下文窗口管理）。

### 4.5 Room 本地数据库（v27，29 个实体）

`stock/database/StockDatabase.kt`，主要表：

- 行情/基础：stock_basic、sector_stock、user_watchlist、intraday_kline、daily_snapshot
- 选股/交易：strategy_trade_order、daily_period_result（DAG 报告）、strategy_trade_fitting_param、
  real_position（实仓）、t_trade_records / t_trade_recommendations（做T）、period_holding_profit、
  ai_selected_stock、daily_news_hot_pick、institutional_pick、institutional_tip
- 回测：backtest_meta、backtest_selected_stock、strategy_trade_backtest、strategy_prediction、
  strategy_weight_snapshot、sector_daily_record、sector_period_summary
- 其他：conversation（对话）、key_memory（记忆）、news_factor、user_focus_sector、daily_intel、push_record、weight_calibration

### 4.6 行情数据层

- 5 源（新浪/腾讯/东财/JoinQuants/AKShare）**并发竞速**取最快返回 + 30 秒后台健康检查 + 自动故障转移；
- `SmartStockCache` 按交易时段动态 TTL（交易中 1s / 盘后 5min / 夜间 30min / 周末 1h）；
- 单位修正（东财 手→股、万元→元）、新浪 GBK + Referer 特殊处理；
- 手机直连行情源，PC 桥仅回退；云端可下载 PC 产出的 `market_data.db` 免逐日拉取。

---

## 5. PC 工具集（smalltools）

### 5.1 脚本分类（200+ 个，完整索引见 SCRIPTS.md）

| 类别 | 代表 |
|------|------|
| 核心库（被 import） | `backtest_guangmo.py`（均线粘合选股复刻+四周期 PARAMS）、`_market_db.py`（market_data.db 读写+情报/推送账本）、`_kline_store.py`（K 线统一存储入口）、`_sources.py`（47 数据源配置中心）、`_trade_calendar.py`（交易日历）、`_table_csv/_table_xlsx/_table_img.py`（统一推送表三件套）、`_multi_source.py`（多源分组熔断抓取）、`push_channel.py`、`cos_utils.py` |
| 守护/推送 | `_publish_candidates.py`、`_market_scan.py`、`_daily_intel.py`、`_news_watch.py`、`_offhour_watch.py`、`_holdings_flow_daemon.py`、`_bridge_daemon.py`、`_daemon_ctl.py`、`_inbox_analyze.py` |
| 回测/拟合 | `_profit_backtest.py`、`_year_backtest.py`、`_walk_forward.py`（三年 36 月滚动样本外）、`_refit_experiment.py`、`_factor_ic.py`、`_history_dag_run.py`（2008 起 4543 交易日逐日跑真实 XML DAG）、`_export_params.py`（众数参数→assets）、`_weekly_refit.py`（周一计划任务） |
| 数据抓取 | `_update_cache_inc.py`（日K增量保鲜）、`_baostock_import.py`（2020 起 5 分钟线）、`_qmt_import.py`（券商分钟线/实盘备用）、`_cybc_cache/_cybc_pool.py`（双创池）、`_etf_holdings.py`、`_inst_holdings.py`、`_holder_signals.py`、`_national_flow.py`、`_etf_share_flow.py` |
| 盘面/因子 | `_stock_fundflow.py`（资金流 v2.1 评分）、`_main_force.py`（主力四态）、`_chip_dist.py`（筹码分布/主力成本）、`_macro_sentinel.py`（美债/日元/油价/费半四哨兵）、`_macro_env_score.py`、`_sector_fundflow.py`、`_industry_leader_map.py`、`_board_peg_report.py`、`_intraday.py`（可成交最高价 tx 口径）、`_contract_scan.py` |
| 复盘 | `_review_tools.py`（pick/signal/holdings/fedhike/ambush 五子命令出 PNG 表）、`_review_backtest.py`、`_patrol_recent.py`、`_maintain_caliber.py`（口径B「维持 30 分钟」胜率）、`publish_close_round.py` |
| 交易/云 | `_trade_gateway.py`（Paper/QMT 网关，默认 dry_run）、`cloud_download.py`、`cloud_upload_params.py`、`cloud_upload_db.py` |
| 可视化 | `pipeline_visualizer.py`（XML→SVG/Mermaid）、`generate_diagrams.py`、`generate_full_architecture.py` |

### 5.2 四个常驻守护（统一控制层 `_daemon_ctl.py`）

```
publish         _publish_candidates.py --daemon   盘段推送（选股/情报/复盘，主守护）
scan            _market_scan.py --daemon          盘中情报扫描（快讯/研报），DEFAULT_INTERVAL=3600
holdings_flow   _holdings_flow_daemon.py --daemon 持仓阈值预警（任何时段）+ 板块资金流（仅交易时段）
bridge          _bridge_daemon.py                 拉起并监管 data_service.py + relay_worker.py（手机桥/COS 中继）
```

- 三方共用同一控制实现：exe 守护 Tab / CodeBuddy skill（`daemon_start.ps1` 薄封装）/ 命令行。
- 命令：`python _daemon_ctl.py status [--json] | start | stop | restart | logs <name> [n] | send <name> <text>`。
- 存活判定：PID 文件 + 进程探测 + 心跳（日志 mtime，超时 20 分钟判 `stale` 疑假死）；状态 `running/stale/stopped`。
- 日志：`smalltools/_daemon_*.log`；启动前 PS 脚本自动轮转归档（`*.YYYYmmdd_HHMMSS.bak`，保留 7 份）。

### 5.3 主守护的一天（`daemon_serve`，交易日）

| 时间 | 动作 |
|------|------|
| 08:00 | **pre8 完整盘前情报**：权威新闻（美/中各 1财经+1政治+1机构）+ 宏观 + 美股收盘 → 利好利空板块 → 候选；消费夜间新闻池（消费即清空）；随后跑板块 PEG/营收增速早报 |
| 09:00 | K 线预热下载 + **pre9 亚太情报**（日经/KOSPI/恒生等） |
| 09:20 | 开盘前大盘速览（4 指数同轴叠加图 + 期货/库存速览） |
| 09:30–11:30 | **每 10 分钟一轮选股推送**（候选/实仓建议有变化才推；无变化仍上传 COS 供 APK 查） |
| 11:31–12:00 | 午间盘中总结（每日一次） |
| 13:00–14:30 | 继续每 10 分钟一轮；之后停止盘中选股 |
| 15:00 | 尾盘最后一次 K 线拉取（仅下载）+ 当日选股入账本/到期信号结算 |
| 15:10 | 收盘总结（选股汇总 + 持仓回顾 + 纪律，无买卖点指令） |
| 15:12 | ETF 低位 usecase 当日发布 + adb 推行情到手机；宽基 ETF 份额快照同步 assets |
| 15:20 | 表格化复盘（板块判定对错 / 当日选股 / 近 5 日信号巡诊 / 实仓镜像，PNG 落 `data/review_tables/`） |
| 15:25 | `kline_store.json` 增量更新当日根 |
| 15:30 | 月度：每月首个交易日重算双创池（幂等） |
| 非交易时段 | **不推送**：每小时只采集/去重/写新闻池（`_offhour_watch` push=False）；周末节假日全天睡眠至下一交易日 08:00 |

每轮选股 `_do_round` 步骤：刷新盘中实时快照 → **COS 探测手机端持仓变更并同步镜像**（先同步再分析，失败不阻塞）
→ `run_once()` 跑 XML DAG 主线选股 → 29 列统一表（主力成本 + PE静/PB/营收%/净利%/ROE%/PEG 等基本面回填）
→ 有变化才企微推送（长图 PNG + XLSX）+ 写候选账本 `_records/_pick_ledger.jsonl` + 上传 COS。

### 5.4 推送与中继

- `push_channel.py`：**企业微信群机器人 webhook** 为主，文本自动分片、失败重试限频、失败落盘排队（`_push_queue`），
  守护启动/通道恢复时自动补发；文件/图片走企业微信 media 接口；并并联 PC→APK 的 relay 中继推送。
- `_bridge_daemon.py`：监管 `data_service.py`（HTTP，已收敛为仅本机监听）与 `relay_worker.py`（COS 任务队列），
  双端通讯方向 = 腾讯云 COS 中继（不填局域网 IP）。
- Inbox/Outbox：手机/CS 网页上传素材到 `data/_inbox/`，守护每 60 秒扫描 → 分类提取指标 →
  写 `data/_outbox/` → 归档 `data/_processed/` → 推送摘要。

### 5.5 回测 → 拟合 → 参数回流

```
_history_dag_run.py（2008 起逐日真实 DAG 跑单，验证口径）
        │
_walk_forward.py（36 个月度窗口，样本外回测 + 样本内拟合，增量续跑）
        │  结论：全量三年信号拟合最优（_refit_experiment.py 对比实验）
        ▼
_export_params.py（取各周期×市场状态规则众数）
        ▼
app/src/main/assets/backtest_params.json   ← APK 启动即代入
        ▼
cloud_upload_params.py → 腾讯云 COS → 手机端「下载最新拟合参数」
```

铁律：t 日选股、t+1 开盘买入（无未来函数）；机构数据 NOTICE_DATE ≤ 信号日；
历史回放用逐日增量生长的截断缓存，防止未来泄漏。

---

## 6. 数据流总览

```mermaid
flowchart LR
    subgraph SRC[外部数据源]
      EM[东财 push2delay/datacenter/F10]
      TX[腾讯 qt.gtimg/ifzq/fqkline]
      SINA[新浪 hq/资金流/分钟线]
      AK[akshare/baostock/FRED/交易所]
    end

    subgraph PC[PC smalltools 守护]
      FETCH[抓取/多源熔断]
      DAGPY[usecase_pipeline.py\n读 assets XML]
      INTEL[_daily_intel/_market_scan\n新闻池/情报]
    end

    subgraph STORE[(存储)]
      KLINE[data/kline_store.json\n688只 2008起 日K]
      MDB[data/market_data.db\nK线/公告/情报/推送账本]
      ASSETS[app/src/main/assets\nXML + 参数 + JSON 镜像]
      ROOM[(APK Room v27)]
    end

    subgraph CLOUD[腾讯云 COS]
      PARAMS[backtest_params.json]
      DB[market_data.db]
      PHONE[phone_*.zip 手机持仓包]
      QUEUE[任务/中继队列]
    end

    subgraph APP[Android APK]
      DAGKT[Kotlin DAG 引擎\n读同一份 assets XML]
      UI[5 Tab UI]
    end

    SRC --> FETCH --> KLINE & MDB
    KLINE --> DAGPY
    MDB --> DAGPY
    ASSETS --> DAGPY
    ASSETS --> DAGKT
    DAGPY -->|推送/候选| INTEL
    INTEL -->|企业微信| USER[用户微信]
    DAGPY -->|候选 JSON| CLOUD
    FETCH -->|镜像 JSON| ASSETS
    PC -->|参数/市场库上传| CLOUD
    CLOUD -->|下载参数/市场库| APP
    APP -->|改仓后上传 phone 包| CLOUD
    CLOUD -->|每轮盘中先同步| PC
    KLINE -.可选下载.-> ROOM
    DAGKT --> ROOM --> UI
    SRC -->|手机直连行情| APP
```

关键数据资产：

| 资产 | 说明 |
|------|------|
| `data/kline_store.json` | 唯一日 K 数据源（688 只，2008 起，~208MB；旧 `_kline_cache.json` 已废弃） |
| `data/market_data.db` | SQLite 公共市场库：K 线/公告/大盘状态/`intel_report` 情报留痕/`push_record` 推送账本 |
| `assets/backtest_params.json` | 四周期拟合参数单一事实源（另有 merged/2023 版本用于实验） |
| `assets/data/_inst_holdings.json` `_national_flow_hist.json` `_inst_pool.json` | 机构持仓/国家队数据镜像（PC 算、双端只读） |
| `assets/data/_etf_share_*.json` `assets/data/_etf_holdings.json` | ETF 份额信号/重仓股 |
| `data/datasources.json`（镜像 assets） | 47 个 HTTP 源登记（url/备用 host/headers/编码），`_sources.py --sync-apk` 同时生成 Kotlin 常量表 `DataSourceConfig.kt` |
| `assets/macro_events/event_library.json` | 宏观事件类型 + 实例（magnitude 0~2，窗口 start/end） |
| `data/_records/*.jsonl` | 运行留痕：选股账本、新闻池、推送队列、守护节奏/心跳等 |

---

## 7. 外部数据源

- **行情/K 线**：腾讯（qt.gtimg 批量实时、ifzq 分钟、fqkline 前复权历史）、东方财富（push2delay 实时板块/资金流、datacenter、F10 股东）、新浪（hq 实时、MoneyFlow 历史资金流、scale=5 分钟线）、akshare、baostock（2020 起分钟线）。
- **F10/机构**：东财 RPT_F10_EH_FREEHOLDERS（十大流通股东全史）、RPT_MAIN_ORGHOLD（机构持股）、基金移动端 F10（ETF 重仓）。
- **宏观**：FRED（DGS10/DGS30/TIPS）、新浪外盘期货（原油/黄金）、东财 USDJPY、费城半导体；akshare 交易日历。
- **新闻**：六类权威源收敛（美/中 各 1 财经 + 1 政治 + 1 机构），源级 5 分钟缓存，不可达时东财+新浪关键词过滤兜底；全源留档 `_records/_news_links.jsonl`。
- 网络纪律：全部外部请求默认重试 3 次（指数退避）+ 多源回退；个股历史缺口只准前复权源（保证序列一致）。

---

## 8. 构建、运行与运维

### 8.1 APK

```powershell
.\gradlew.bat assembleDebug          # 出包
.\gradlew.bat test                   # 单元测试
```

Release：配置 `keystore.properties`（参考 `keystore.properties.example`），`assembleRelease`；
应用内自更新走 `release/publish_update.ps1` + COS（`release/cos_config.example.json`）。

### 8.2 PC 守护

```powershell
# 推荐（Python 统一控制层）
python smalltools/_daemon_ctl.py status
python smalltools/_daemon_ctl.py start all
python smalltools/_daemon_ctl.py logs publish 80

# 或旧入口（等价薄封装）
powershell -File .codebuddy/skills/stock-daemon/daemon_start.ps1 -Daemon all
```

常用排查（约定优先脚本）：守护日志 `Get-Content smalltools/_daemon_publish.log -Tail 80`；
选股账本 `_records/_pick_ledger.jsonl`；网络诊断 `python smalltools/_netdiag.py --all`；
数据源连通 `python smalltools/_sources.py --check`。

### 8.3 每周/每月例行

- 每周一 16:00：`_weekly_refit.py`（增量回溯 → 导出参数 → 上传 COS → 推送简报，独立计划任务）。
- 每月首个交易日 15:30：守护自动重算双创股票池并增量拉全史。

---

## 9. 开发者扩展指南

### 9.1 新增一个选股节点（双端必须同口径）

1. 在目标 `*_pipeline.xml` 加 `<Node module="my_node">` + `<config>` 默认参数，并接好 `<Links>`；
2. Kotlin：`nodes/` 下新建节点类继承 `PipelineNode<I,O>`，在 [NodeRegistry.kt](../app/src/main/java/com/chin/stockanalysis/strategy/topology/xml/NodeRegistry.kt) `register("my_node")`；
3. Python：在 [usecase_pipeline.py](../app/src/main/assets/usecases/usecase_pipeline.py) 写实现并加 `@register("my_node")`；
4. 依赖 Android 能力的逻辑，Python 端要安全降级并写 `ctx.notes`；
5. 跑 `python skills/architecture-sync/scan_architecture.py` 更新架构图。

### 9.2 新增/调整一条选股链

复制最接近的 usecase+pipeline XML（如资金流链），改 steps/config 即可被 `UseCaseLoader.listUseCases` 自动发现
（文件名以 `_usecase.xml` / `_pipeline.xml` 结尾；目录内 `.py` 被后缀过滤自动忽略，不进 APK 解析）。
调门槛优先改 XML 参数，不要改代码。

### 9.3 新增行情数据源

- APK：实现 `StockDataSource`（priority/fetchRealtime），在数据源工厂注册即自动参与并发竞速。
- PC：在 `data/datasources.json` 登记，跑 `_sources.py --sync-apk` 同步 assets 并生成 Kotlin 常量表；
  配置缺失时代码自动回退硬编码默认值。

### 9.4 新增 Room 表

加 Entity → 注册进 `StockDatabase` entities → version +1 并提供 Migration（当前 v27）。

---

## 10. 关键文档索引

| 主题 | 文档 |
|------|------|
| AI 协作约定/已确认口径 | [AGENTS.md](../AGENTS.md) |
| 双端 mermaid 拓扑 | [architecture/dual-end-overview.md](architecture/dual-end-overview.md) |
| 旧版总架构入口 | [ARCHITECTURE.md](ARCHITECTURE.md) |
| 功能模块地图 | [skills/PROJECT_MAP.md](../skills/PROJECT_MAP.md) |
| Python 脚本全量索引 | [smalltools/SCRIPTS.md](../smalltools/SCRIPTS.md) |
| 多 Agent 对话架构 | [ai-multi-agent-chat-architecture.md](ai-multi-agent-chat-architecture.md)、[agent/agent-architecture.md](agent/agent-architecture.md)、[agent/llm-architecture.md](agent/llm-architecture.md) |
| 做T系统 | [topology/t-trade-system.md](topology/t-trade-system.md) |
| Pipeline 编辑器 | [topology/pipeline-editor-design.md](topology/pipeline-editor-design.md) |
| COS 中继/桥 | [bridge-relay-design.md](bridge-relay-design.md)、[cs_architecture.md](cs_architecture.md) |
| 市场库 | [market-db.md](market-db.md)、[background/background-services.md](background/background-services.md) |
| 策略方法论 | [strategy/stock-picking-methodology.md](strategy/stock-picking-methodology.md) 及四周期策略文档、[选股口诀.md](选股口诀.md) |
| UI 架构 | [ui/](ui/) 下 stock/strategy/agent/mine 四份 tab 架构 |
