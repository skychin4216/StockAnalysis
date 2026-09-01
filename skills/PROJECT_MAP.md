# StockAnalysis 项目功能模块索引

> 本文档是项目功能地图。接任务时先看这里确定入口,再进入对应 skill 目录查看细节。

> ⚡ **全局约定**：任务执行中遇到网络问题必须自动重试（见 [NETWORK_RETRY.md](NETWORK_RETRY.md)）——行情/新闻/研报/F10/数据中心/COS/微信推送等所有外部请求，失败默认重试 3 次（指数退避）+ 多源回退，重试后仍失败才报告。

## 模块 → Skill 对照表

| 功能 | Skill 目录 | 核心入口/文件 |
|------|-----------|--------------|
| 龙头异动监测 | `skills/leader-monitor/` | `strategy/monitor/SectorLeaderMonitor.kt` |
| 拓扑引擎(UseCase/Pipeline) | `skills/topology-engine/` | `strategy/topology/xml/UseCaseExecution.kt` + `assets/usecases/*.xml` |
| 量化工作台/一键建仓 | `skills/quant-workbench/` | `strategy/trade/QuantWorkbenchFragment.kt` + `agent/chat/QuickBuildExpertRunner.kt` |
| 平台策略/豆包体系 | `skills/platform-strategies/` | `ui/StrategyListFragment.kt` + `assets/usecases/complete_closed_loop_usecase.xml` |
| 行情数据源 | `skills/data-sources/` | `stock/data/sources/*.kt` |
| 交易执行引擎 | `skills/trade-engine/` | `strategy/trade/TTradeEngine.kt` + `macro/` |
| Agent 智能体 | `skills/agent-framework/` | `agent/core/AgentOrchestrator.kt` + `agent/autoquant/AutoQuantAgentRunner.kt` |
| 云端同步/COS | `skills/cloud-sync/` | `cloud/CloudSyncManager.kt` + `smalltools/*.py` |
| 市场热度/板块 | `skills/market-hot/` | `ui/MarketHotFragment.kt` + `strategy/sector/` |
| 选股系统 | `skills/stock_picking/` | 量化选股 UI + screening/ai_filter pipeline |
| 回测引擎 | `skills/backtest/` | `strategy/backtest/` |
| 架构同步(自动) | `skills/architecture-sync/` | `docs/architecture/index.html` |
| 引擎同步 | `skills/engine-sync/` | smalltools 引擎同步脚本 |
| 远程控制(C/S) | `AutoQuant/autoquant/remote_control.py` + `app/.../strategy/trade/RemoteControlDialog.kt` | APK 提交任务给 PC 执行，实时看状态/日志（见 `docs/cs_architecture.md`） |
| AutoQuant PC量化引擎 | `skills/autoquant/` | `AutoQuant/`（Python: 回测/拟合/Agent/参数导出）|

## 数据流总览
```
数据源 (stock/data/sources)
  ├──> SectorLeaderMonitor (龙头监测) ──> SectorSignalStore ──> 选股 Pipeline
  ├──> StockDataCenter ──> UI (行情/板块/新闻)
  └──> 行情/新闻 ──> MarketHot / HotNews

拓扑引擎 (assets/usecases/*.xml → UseCaseExecution)
  ├──> 平台策略 (StrategyListFragment)
  ├──> 一键建仓 (QuantWorkbenchFragment → QuickBuildExpertRunner)
  └──> 豆包体系 (complete_closed_loop_usecase.xml)

交易引擎 (strategy/trade) ←── 一键建仓/自动卖出 产生的订单
云同步 (cloud/ + smalltools/) ⇄ COS ⇄ adb 广播
```

## 快速定位口诀
- **改策略流程/新增策略** → topology-engine + assets/usecases XML
- **策略没输出** → 查 pipeline 的 `if="${节点}.字段"` 条件(topology-engine)
- **龙头/板块异动** → leader-monitor
- **一键建仓** → quant-workbench
- **选股不满意/ST/过滤** → stock_picking + quant-workbench
- **数据源/数据不更新** → data-sources
- **卖出/交易** → trade-engine
- **AI/Agent** → agent-framework
- **同步/备份/adb** → cloud-sync

## 修改后必须同步
- 新增/删除 Fragment、Node、Strategy、DataSource、Agent、Entity、XML → 运行 `python skills/architecture-sync/scan_architecture.py` 更新架构图（见 architecture-sync skill）
- **PC 回测/拟合/Agent/参数** → skills/autoquant（AutoQuant/ 目录）；参数修改走 backtest_params.json 单一事实源
