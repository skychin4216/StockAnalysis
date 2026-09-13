# StockAnalysis 项目功能模块索引

> 本文档是项目功能地图。接任务时先看这里确定入口,再进入对应 skill 目录查看细节。

> ⚡ **双端工程速查**：本仓库是 **Android APK（`app/`，Kotlin）** + **Windows 桌面 exe（`AutoQuant/`，Python/PyQt5，独立 git 仓库、主仓 .gitignore 排除）** 双端工程。
> exe 端模块地图见 [skills/autoquant/README.md](autoquant/README.md)；双端 mermaid 拓扑见 [docs/architecture/dual-end-overview.md](../docs/architecture/dual-end-overview.md)。
> 修改 exe 端文件要在 `AutoQuant/.git` 单独 commit；主仓管理 `smalltools/`（对照参考层）与 `app/src/main/assets/usecases/`（XML DAG 主线事实源）。
>
> ⚡ **选股架构主线（2026-09-07 定，勿再混淆）**：**XML DAG = 选股主线**——事实源 = `app/src/main/assets/usecases/*.xml`（usecase + 各周期/环境 pipeline XML，单一事实源）；APK(Kotlin UseCaseLoader) 与 Python 引擎（同目录 `usecase_pipeline.py`，exe/smalltools 经 sys.path 指向该目录 import）读**同一份 XML**；exe 当日选股 = `AutoQuant/usecase_screen.py`。**smalltools/ 旧选股引擎（backtest_guangmo 粘合链等）与 backtest_params.json 链 = 对照参考，非主线**；三状态回溯自拟合生产工具 = `AutoQuant/_self_fit_pipeline.py`（震荡/熊市 strict 写回 XML pipeline、牛市 tf 写回 backtest_params.json `trend_follow`）。

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
| AutoQuant PC量化引擎(exe) | `skills/autoquant/` | `AutoQuant/`（Python: 回测/拟合/Agent/多Agent/参数导出 + PyQt5 GUI 10页签）|
| ETF 低位(双端tab) | `skills/engine-sync` + exe `gui/etf_tab.py` + app `EtfDipFragment.kt` | 事实源 `assets/usecases/etf_dip_usecase.xml` + `etf_dip_pipeline.xml`（v0.3 dip_buy 定稿）；APK 本地 UseCaseLoader 执行（`etf_cache.json`），PC 桥 `/etf_live` 仅回退；`smalltools/_etf_buy.py` 为规则出处与行情抓取 |
| 多Agent(双端对话) | exe `multi_agent.py` + app `ChatAgent.kt`(AgentHub) | exe 父规划拆解子Agent；APK 命中≥2专家自动编排 |
| 双端总览架构 | `docs/architecture/dual-end-overview.md` | mermaid 拓扑 + 职责/链路对照 |

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
