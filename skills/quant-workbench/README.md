# Quant Workbench Skill — 量化工作台 / 一键建仓 / ETF低位

## 模块职责
量化工作台（一键建仓、四大周期自动建仓、公共研判）的 UI 与执行逻辑。
用户点"一键建仓"后，先跑**公共研判流程**（市场环境分析），再按超短/短/中/长四周期生成股票池与订单。
工作台为 5 内页签：短/中/长 + 💰实仓 + 🧲ETF低位（2026-09-06 实仓旁新增第 5 页）。

## 触发条件
用户提到以下关键词时，优先查阅本 Skill：
- 一键建仓 / 自动建仓 / 公共研判 / 市场环境分析
- 一键建仓选出的股票 / 为什么选到 XX / 打印所有股票
- 工作台 / QuantWorkbench / 四大周期 / ETF低位 / EtfDip

## 关键文件
| 文件 | 职责 |
|------|------|
| `app/src/main/java/com/chin/stockanalysis/strategy/trade/QuantWorkbenchFragment.kt` | 工作台容器（5 页签：短/中/长/实仓/ETF低位）+ `runQuickBuild()` 一键建仓入口 |
| `app/src/main/java/com/chin/stockanalysis/strategy/trade/EtfDipFragment.kt` | ETF低位页：经 `PcBridgeClient.fetchEtfLive()` 拉 exe `data_service /etf_live`，离线读 SharedPreferences 缓存 |
| `AutoQuant/autoquant/gui/etf_tab.py` | exe 侧同源页签（读 `smalltools/data/_etf_live_picks.json`） |
| `app/src/main/java/com/chin/stockanalysis/strategy/trade/QuantWorkbenchState.kt` | 工作台状态管理 |
| `app/src/main/java/com/chin/stockanalysis/agent/chat/QuickBuildExpertRunner.kt` | 一键建仓执行器（runCommonPipeline 公共研判 + 四周期建仓） |
| `app/src/main/java/com/chin/stockanalysis/strategy/topology/pipelines/QuantTradingPipeline.kt` | 量化交易 pipeline（含龙头信号融合等选股逻辑） |
| `app/src/main/assets/usecases/stock_picking_common_pipeline.xml` | **公共研判管线**（一键建仓前的市场环境分析） |
| `app/src/main/assets/usecases/merge_boost_pipeline.xml` | 合并增强管线 |
| `app/src/main/assets/usecases/stock_picking_common_pipeline.xml` 同目录下 `screening_pipeline.xml` / `ai_filter_pipeline.xml` | 筛选/AI 过滤管线 |

## 工作原理 / 一键建仓流程
```
QuantWorkbenchFragment.runQuickBuild()
  └─> QuickBuildExpertRunner.run()
        ├─> 第一步: runCommonPipeline()   // 公共研判：市场环境分析（股票池/大盘/龙头）
        │     └─> 执行 stock_picking_common_pipeline.xml
        ├─> 第二步: 四周期并行/串行建仓
        │     ├─> 超短周期 (UltraShort)  → 对应短周期 usecase
        │     ├─> 短周期 (Short)
        │     ├─> 中周期 (Mid)
        │     └─> 长周期 (Long)
        └─> 汇总订单 → UI 显示
```

## 当前已知问题与任务
### 1. 打印所有股票（输入输出具体股票）
用户要求一键建仓时**打印 pipeline 执行的所有股票**（包括输入股票池和输出订单）。
当前 `QuickBuildExpertRunner.run()` 汇总时 `p.orders.take(8)` 截断了输出。
修改点：把截断去掉，打印每周期完整订单 + 每步 pipeline 的输入候选池。

### 2. 公共研判集成龙头监测
用户要求在一键建仓的市场环境分析（`stock_picking_common_pipeline.xml` / `runCommonPipeline`）中
集成**板块龙头前三名监测**（复用 `SectorSignalStore` / `SectorLeaderAnalysisNode`），
让市场环境研判包含龙头板块强弱信号。

## 常见任务指引
### 1. 改一键建仓的选股范围
改对应周期 usecase XML 中的 pipeline 节点参数（如 `stock_evaluation` 的过滤阈值）。

### 2. 看一键建仓的输入输出
在 `QuickBuildExpertRunner.run()` 各步骤间加日志/Toast，打印：
- 公共研判的股票池（哪些股票进入）
- 每个 pipeline 的订单（含股票代码、名称、方向、金额）
- 最终汇总（不要 take(8) 截断）

### 3. 添加市场环境分析内容
修改 `stock_picking_common_pipeline.xml` 增加节点，或在 `runCommonPipeline` 代码中
调用 `SectorSignalStore` 把龙头信号并入研判结果。

## 文件清单
- `skills/quant-workbench/README.md` — 本文件（skill 定义）
