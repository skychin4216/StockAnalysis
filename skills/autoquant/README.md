# AutoQuant — PC 端量化引擎（Windows exe）完整架构地图

> **工程位置：`e:/Android/work/dev/StockAnalysis/AutoQuant/`（独立 git 仓库，主仓库 .gitignore 排除）**
> 语言：Python + PyQt5 + matplotlib；APK 对应 Kotlin（`app/`）。
> 双端总览拓扑：见 `docs/architecture/dual-end-overview.md`（mermaid）。
> 接任务先看本文件定位入口，再进入具体模块——不要每次重新遍历整个工程。

---

## 一、工程定位与数据流

```
akshare / 腾讯 / 东财 push2 (autoquant/data.py DataFeed + data/cache/*.csv)
   │
   ├──> PeriodSelector (period_selector.py)   四周期选股（ultra_short/short/mid/long 每日 TOP）
   ├──> DragonFilter (dragon_filter.py)       龙头股池过滤
   ├──> SectorHotRanker / LeaderStockPool     板块热度 / 龙头池（CLI 引擎）
   ├──> MacroPipeline (macro_pipeline.py)     宏观信号 → 板块加减分
   └──> MarketGuard (market_guard.py)         大盘离场/强平保护（688/300/上证 跟随）

GUI (gui/app.py MainWindow，右侧 11 页签 QTabWidget)
   ├── 🤖 AI助手 ChatWidget         （多Agent 前缀 → multi_agent.run_multi_agent）
   ├── 🤖 Agent AgentTab            （plan/review/suggest/多Agent 四模式 + Provider/Key 配置）
   ├── 📈 回测结果 / 📋 策略列表
   ├── 💬 消息消费 MsgConsumerTab   （APK📡远程发消息 → AI 回复回传 APK）
   ├── 💼 持仓评估
   ├── 🧲 ETF低位 EtfTab            （读 smalltools data/_etf_live_picks.json + 一键重扫）
   ├── 🔥 板块热度 MarketHotTab     （东财板块实时榜+成分龙头，双击开本地K线，2026-09）
   ├── 📊 股票 StockTab             （K线趋势扫描+形态检测 / 龙头自选，双击开 KlineDialog）
   ├── 🎯 选股建仓                  （多周期选股 → 整手订单 → ⏰选股推送守护）
   └── 🔬 回测与拟合                （参数评估=固定参数回放 / 滚动拟合=样本外调参）

导出:
  export_params.py ──> app/src/main/assets/backtest_params.json（APK 单一事实源）
  smalltools/_publish_candidates.py ──> 候选股票发布到 APK/COS
```

**服务层（新增，双端桥）**
- `autoquant/data_service.py` — HTTP :8888（GUI 启动自动拉起，供 APK 直连）：`/etf_live` `/candidates` `/rotation` `/push` 等
- `autoquant/remote_control.py` — APK 远程控制任务队列（APK RemoteControlDialog ⇄ exe 执行）
- `autoquant/remote_runner.py` — PyInstaller frozen `--aq-task` 分发

---

## 二、GUI 文件与页签结构

### gui/ 文件
| 文件 | 职责 |
|------|------|
| `gui/app.py`（107KB） | `MainWindow` 主窗口：右侧 11 页签；`main()` 启动后自动起局域网数据服务 :8888 |
| `gui/agent_tab.py` | `AgentTab`：Provider/Key 运行时配置 + plan/review/suggest/multi 四模式后台线程 |
| `gui/stock_tab.py` | `StockTab`：K线趋势扫描(MA5/10/20 标签) + K线形态(CANDLE_PATTERNS) + 龙头自选；`KlineDialog` 全景 K 线(MA+量能+MACD+RSI+OBV+SAR) |
| `gui/etf_tab.py` | `EtfTab`：ETF 低位名单（signal_today/approach/大盘门控），后台跑 `_etf_buy.py --live` |
| `gui/market_hot_tab.py` | `MarketHotTab`（2026-09）：板块实时热度榜（行业/概念，东财 clist 多域名回退）+ 点击板块看成分龙头 Top + 双击开本地 K 线。对齐 APK「热门行情/龙头图谱」 |
| `gui/daemon_tab.py` | 选股推送守护页签（并入「🎯 选股建仓」） |
| `gui/msg_consumer_tab.py` | `MsgConsumerTab`：APK📡消息消费 |

### app.py 右侧页签（对齐 APK「股票」栏思路）
见上方数据流；新增页签统一写法 = 独立 `gui/xxx_tab.py` 模块 + `right_panel.addTab(instance, "标题")`。

---

## 三、模块地图（autoquant/）

| 模块 | 文件 | 职责 |
|------|------|------|
| 数据 | `data.py` | DataFeed + 多 DataSource（akshare/yahoo/…），懒加载、关系统代理 |
| 路径/日志 | `paths.py` / `logger.py` | frozen 资源/可写路径；统一日志（文件/控制台/远程） |
| 选股 | `period_selector.py` | 13 项检查漏斗 + 四周期参数 + 大盘感知 |
| 龙头 | `dragon_filter.py` | 龙头漏斗（DragonStockFilter） |
| 龙头池 | `leader_stock_pool.py` / `LeaderStockPool.py` / `hot_sector_config.py` | 板块龙头静态配置 + 动态龙头池 |
| 板块热度 | `SectorHotRanker.py` | 三因子（动量/资金/相对强度）板块排名（akshare 申万/东财） |
| 宏观 | `macro_pipeline.py` | 油/金/纳指/科创 → 板块加减分 |
| 大盘 | `market_guard.py` | 状态机 NORMAL→WEAKENING→CRISIS |
| 建仓 | `position_planner.py` | 资金规则 + 大盘水阀；`add_position_engine.py` 金字塔加仓 |
| 卖出 | `sell_engine.py` | AutoSellEngine 10 条规则短路，四周期差异 |
| 持仓评估 | `holding_evaluator.py` | 三维健康度 + 卖出触发 + 加仓风控 |
| 做T | `t_trade_engine.py` / `t_trade_scan.py` | T+0 信号引擎 + 持仓/候选扫描 |
| 技术指标 | `technicals.py` | 与 smalltools 同源零依赖指标库 |
| 特征库 | `kotlin_feats.py` | 与 StockCheckPipeline/DirectionAnalyzer 双端同口径 |
| 风险 | `risk.py` | RiskEngine/RiskRule |
| Agent 基础 | `ai_config.py`/`ai_client.py` | Provider 配置 + OpenAI 兼容 HTTP client |
| Agent | `agent_loop.py` / `autonomous_agent.py` | 计划-执行循环 / 自主决策 OTDAR |
| Agent 工具 | `agent_tools.py` | Function Calling 工具注册表 |
| **多Agent** | `multi_agent.py` | 父规划拆解子 Agent（见 §六） |
| **远程** | `remote_control.py` | C/S 任务队列服务端 |
| **桥服务** | `data_service.py` | HTTP :8888 数据服务 |
| 集成 | `vnpy_integration.py` / `qlib_integration.py` | 实盘/Qlib 可选 |
| CLI | `cli.py` | Click 命令行入口 |

### AutoQuant/ 根目录脚本（选股/回测/Agent）
`backtest_selection.py`（主回测，HOLD/STEP 从 APK JSON 回读）、`auto_fit_backtest.py`（网格拟合 IS/OOS）、
`walk_forward.py`、`ttrade_walk_forward.py`、`backtest_by_weekday.py`（星期效应）、`analyze_selection.py`、
`fetch_dragon_stocks.py`、`generate_hot_stocks.py`、`export_params.py`、`run_agent.py`、`hindsight_*.py`、
`fit_experiments.py`、`fit_rerank.py`、`ml_predict.py`、`verify_parity.py`（双端口径校验）、
`build_all.bat` / `AutoQuant-GUI.spec`（PyInstaller 打包）、`run_gui.py`。

---

## 四、参数单一事实源：`app/src/main/assets/backtest_params.json`

**任何参数修改必须走此链路**，否则 APK 与 AutoQuant 不一致：

```
AutoQuant 拟合/决策 → export_params.py → app/src/main/assets/backtest_params.json
   ├──> APK 启动 BacktestParamsLoader 加载
   └──> AutoQuant backtest_selection.py `_load_hold_step_from_json()` 回读 meta.pc_hold/pc_step
```

JSON 区块：`sell_rules`（四周期×状态）、`rank_factors`、`select_params`、`t_trade`、`meta.pc_hold/pc_step`。
修改 checklist：①编辑 JSON 或 `export_params.py --hold {...} --step {...}` ②APK 重装生效 ③AutoQuant 回测自动回读。

---

## 五、ETF 低位链路（2026-09 新增，双端同源）

```
smalltools/_etf_buy.py v0.3（2015→今 13 只宽基/行业 ETF 全历史拟合）
   └── python _etf_buy.py --live ──> smalltools/data/_etf_live_picks.json
         ├── exe gui/etf_tab.py 直读 + 一键后台重扫
         ├── exe data_service.py GET /etf_live（提供 APK）
         └── APK 工作台 EtfDipFragment（PC 桥 /etf_live，失败显示本地缓存）
```
规则：距60日高回撤-25%~-12% + RSI6<30 + 年线上方 + 收阳/RSI拐头 + 非5日新低 + 沪深300结构多头门控；
离场 +2%/-6%/30日。实测 OOS 11/11 全胜。详见 `smalltools/SCRIPTS.md`。

---

## 六、多 Agent 编排（multi_agent.py，2026-09 新增）

- **模式**：`run_multi_agent(goal, provider_id=None, progress=cb)` → `{ok, steps[], sub_agents[], conclusion}`。
- **父规划器**（LLM）把一句话需求拆成 1~4 个结构化子 Agent（step 可含 goal + sub_agent 人设），
  每个子 Agent 独立上下文执行（可调 agent_tools 工具），最后由父级汇总 conclusion。
- **内置子 Agent 池**：板块/选股/持仓/复盘/新闻…（文本中带 `<agent_role>` 时按角色注入工具子集）。
- **接线**：`gui/agent_tab.py`（勾选「🤖 多Agent」模式）与 `gui/app.py` ChatWidget（消息前缀 `多Agent:`）均调用。
- 对齐 CodeBuddy 范式（父规划 + 子 Agent 委派 + 汇总）；详见 `docs/ai-multi-agent-chat-architecture.md`。

---

## 七、运行命令

```bash
cd AutoQuant
python run_gui.py                                    # 启动 GUI（自动起 :8888 数据服务）
python backtest_selection.py --start 2026-07-01      # 指定窗口回测
python auto_fit_backtest.py --start 2025-08-15       # 近1年+IS/OOS 拟合
python export_params.py --hold '{"mid": 12}'         # 导出参数到 APK JSON
python run_agent.py --date 2026-08-18 --capital 1000000
python -m autoquant.cli ...                          # 命令行入口
build_all.bat                                        # PyInstaller 打包 exe（含 GUI spec）
```

## 八、与 APK 的双端对照速查

| APK（app/） | AutoQuant（AutoQuant/） | 备注 |
|---|---|---|
| MainActivity 5 Tab | MainWindow 10 页签 | 定位不同 |
| ChatTab 多Agent（AgentHub 10专家） | multi_agent.py 父规划 | 双端多Agent |
| QuantWorkbenchFragment（5 内页） | 选股建仓 + 持仓评估页签 | 对齐 |
| EtfDipFragment | EtfTab + data_service /etf_live | 同源名单 |
| RemoteControlDialog | remote_control.py | C/S 桥 |
| 股票/机构推荐 OCR | — | 手机端能力，exe 不实现 |
