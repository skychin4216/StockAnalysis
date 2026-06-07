# 策略模块架构分析与重复调用诊断

> 基于 2026-06-07 代码库分析，涵盖调用链、数据流、重复执行检测和设计评审。

---

## 1. 策略 Tab — "执行策略" 和 "调优" 调用链

### 1.1 "执行策略" 按钮 → `runSelectedStrategies()`

```
用户点击 "执行策略"
  │
  ├─ StrategyListFragment.runSelectedStrategies()
  │   ├─ 检查缓存（10分钟内相同条件 → 直接用 cachedResults）
  │   │
  │   ├─ 选当日 & 市场在交易 & DB无数据 → executeRealTime()
  │   │   ├─ screener.scanFullMarket()          ← StockScreener 全市场扫描
  │   │   ├─ for each strategy (排除 ai_prediction) → s.screenWithData(rts)
  │   │   │    每个策略独立复用同一份 StockRealtime 列表
  │   │   └─ AIPredictionStrategy (id=ai_prediction)
  │   │        注入 nonAiResults → screen() → AIPredictionEngine.predict()
  │   │
  │   └─ 有DB数据 → doExecute()
  │       ├─ db.dailySnapshotDao().getByDate(selectedDate)  ← Room DB
  │       ├─ 多日模式 → getMultiDaySnapshots()  ← 合并多日 DB 快照
  │       ├─ StockDataCenter.getSectorsByStock(code)        ← 板块归属查询
  │       ├─ db.sectorStockDao().getStockCodesBySector()    ← 板块→股票映射
  │       ├─ db.stockBasicDao().getAll()                    ← 股票基本信息
  │       ├─ for each strategy (排除 ai_prediction) → s.screenWithData(stockList)
  │       └─ AIPredictionStrategy → 注入 nonAiResults → screen()
  │
  └─ saveBacktestData(results)  ← BacktestEngine 持久化预测
       showResults(results)     ← 弹出结果对话框
```

**使用的数据源：**
| 数据 | 来源 | 方法 |
|------|------|------|
| 股票快照 (OHLCV) | Room DB `daily_snapshot` | `getByDate(date)` |
| 股票基本信息 | Room DB `stock_basic` | `getAll()` |
| 板块-股票映射 | Room DB `sector_stocks` | `getStockCodesBySector()` |
| 板块归属 | `StockDataCenter` 缓存 | `getSectorsByStock()` |
| 实时行情 (盘中) | `StockScreener.scanFullMarket()` | 东方财富 API |
| AI 预测 | `AIPredictionEngine.predict()` | LLM API |

### 1.2 "调优(90%)" 按钮 → `runSelfTune()`

```
StrategyListFragment.runSelfTune()
  └─ StrategySelfTuner.selfTune(
       strategies = eng.getEnabledStrategies(),  ← 当前启用的策略
       historyDays = 30,                          ← 回溯30个交易日
       targetAccuracy = 0.90f                     ← 目标准确率90%
     )
     └─ 使用 StockScreener + DB 历史数据迭代调优
        └─ 保存优化后的 WeightFactors 到 SharedPreferences
```

---

## 2. 模拟交易 Tab — "模拟交易"、"拟合"、"回溯" 调用链

### 2.1 "模拟交易" 按钮 → `executeTrade()`

```
SimulationTradeFragment.executeTrade()
  │
  ├─ 构建 TradeSessionConfig(date, periods[], onlyMainBoard)
  │
  └─ tradeEngine.runTradeSession(strategies, config)
      │  SimulationTradeEngine (独立实例!)
      │
      ├─ 创建独立的 StrategyEngine + StockScreener ← **重复实例化**
      │   (与 StrategyListFragment 中的引擎不共享)
      │
      ├─ for each strategy × period (6个周期):
      │   ├─ executeStrategy() → 策略打分流式
      │   │    内部调用 strategy.screen() / screenWithData()
      │   ├─ calculateNewsStrength() → 新闻热度评分
      │   ├─ calculateRotationPenalty() → 板块轮动惩罚
      │   ├─ filterByMainBoard() → 主板过滤
      │   ├─ selectTop3() → 选出Top3
      │   └─ savePeriodResultToDb() → 写入 daily_period_result
      │
      ├─ AI 精选 (所有周期结果汇总):
      │   └─ AIPredictionEngine.predict()
      │       → 输出 TradeSessionReport.aiTop3
      │
      └─ 生成买入建议 → TradeOrders
         └─ 写入 strategy_trade_orders (DB)
```

**使用的数据源：**
| 数据 | 来源 | 说明 |
|------|------|------|
| 股票快照 | Room DB | 与策略Tab同源，但引擎独立重建 |
| 新闻热度 | `calculateNewsStrength()` | 基于策略信号的板块加权 |
| 板块轮动 | `calculateRotationPenalty()` | 检测连续上涨≥3天的板块 |
| AI 预测 | `AIPredictionEngine.predict()` | 独立 LLM 调用 |
| 周期结果 | Room DB `daily_period_result` | 每次执行都会写入 |

### 2.2 "拟合" 按钮 → `showFittingParams()`

```
SimulationTradeFragment.showFittingParams()
  └─ db.strategyTradeFittingParamDao().getRecentByStrategy(id, 50)
     → 仅读取已有拟合参数，**不执行新的拟合**
```

实际的拟合在 `SimulationTradeEngine.runTradeSession()` 内部自动完成：
- 每个策略×周期 执行 `runFittingOptimization()`（最多200轮）
- 将最佳参数写入 `strategy_trade_fitting_params` 表

### 2.3 "回溯" 按钮 → `runNextDayBacktrack()`

```
SimulationTradeFragment.runNextDayBacktrack()
  │
  └─ tradeEngine.backtrackAndOptimize()
      │
      ├─ 获取 BUYING 状态订单
      ├─ **重新执行全部策略筛选**（与模拟交易相同流程）
      ├─ 对比买入订单 vs 落选股的次日表现
      ├─ 分析遗漏机会 ← 被过滤但次日大涨的股票
      ├─ 重新调优拟合参数
      └─ 写入优化参数到 DB
```

---

## 3. AI 量化选股 vs AI 量化预测 — 重复调用分析

### 3.1 现有架构中的三处 AI 调用

| # | 位置 | 触发方式 | 用途 |
|---|------|---------|------|
| ① | `AIPredictionStrategy.screen()` | 作为策略在 `doExecute()` 中执行 | 策略列表中显示结果 |
| ② | `StrategyListFragment.showResultsDialog()` | 弹出结果对话框后异步调用 | 对话框底部 "🤖 AI 量化预测" |
| ③ | `SimulationTradeEngine.runTradeSession()` | 模拟交易引擎内部 | AI 精选 Top3 |

### 3.2 ① 和 ② 本质相同，存在重复调用

**① AIPredictionStrategy** (作为策略)
```
AIPredictionStrategy.screen()
  └─ AIPredictionEngine.predict(strategyResults, date)
     → 返回 AIPrediction.topPicks → 转为 StrategySignal 列表
     → currentPrice = 0.0, changePercent = 0.0  ← 问题!
```

**② showResultsDialog 中的 AI 预测** (对话框底部)
```kotlin
lifecycleScope.launch {
    val ai = AIPredictionEngine(requireContext())
    val pr = ai.predict(results, browsingDate.toString())
    // 在对话框中追加显示 AI 预测结果
}
```

### 3.3 问题总结

1. **重复调用**：① 和 ② 都对同一份 `results` 调用 `AIPredictionEngine.predict()`，造成两次 LLM API 调用，浪费费用和时间
2. **AIPredictionStrategy 的股价为 0**：因为 `AIPick` 数据类没有 `price`/`changePercent` 字段，`AIPredictionStrategy` 只能设 `currentPrice = 0.0, changePercent = 0.0`
3. **showResultsDialog 中的 AI 预测未使用策略输出**：它独立调用 AI，未复用 `AIPredictionStrategy` 已经执行的结果
4. **③ 模拟交易中的 AI 调用**：完全独立的 `AIPredictionEngine` 实例，使用模拟交易的 `allPeriodResults` 而非策略列表的 `results`

### 3.4 修复建议

- **删除 `showResultsDialog()` 中的独立 AI 调用**，改为直接使用 `AIPredictionStrategy` 的结果（已在 results 中）
- **在 AIPredictionStrategy 中补齐股价数据**：从 `DailySnapshotEntity` 或 `ScreeningResult` 中提取对应股票的 `close` 和 `changePct`
- ③ 模拟交易中的 AI 调用可保留（输入数据不同，逻辑独立）

---

## 4. 重复执行与不合理设计汇总

### 4.1 重复实例化

| 问题 | 位置 | 影响 |
|------|------|------|
| `StrategyEngine` 重复创建 | `StrategyListFragment.initEngine()` 和 `SimulationTradeFragment.initEngine()` 各自创建独立引擎 | 策略注册、状态完全独立，内存浪费 |
| `StockScreener` 重复创建 | 同上，两个 Fragment 各自创建 | 重复初始化，数据源配置不一致风险 |
| `AIPredictionEngine` 重复创建 | `AIPredictionStrategy.screen()` + `showResultsDialog()` + `SimulationTradeEngine` | 三次独立 AI 调用 |

### 4.2 重复数据查询

| 问题 | 证据 | 影响 |
|------|------|------|
| `db.dailySnapshotDao().getByDate()` 多次调用 | `doExecute()` 和 `SimulationTradeEngine` 各自独立查询 | 重复 IO |
| `db.stockBasicDao().getAll()` 每次执行都查 | 无缓存 | 浪费 IO |
| `StockDataCenter.getSectorsByStock()` 批量查 | `doExecute()` 对每个 code 单独调用 | 应批量查询 |

### 4.3 死代码 / 未使用代码

| 代码 | 位置 | 状态 |
|------|------|------|
| `runAIPredict()` | `StrategyListFragment` line 412 | **死代码** — 按钮已删除，但方法未删 |
| `StrategyEngine.runAll()` | `StrategyEngine` | **未使用** — Fragment 手动迭代策略 |
| `StrategyEngine.runAllWithData()` | `StrategyEngine` | **未使用** — Fragment 手动迭代策略 |
| `StrategyEngine.runWithSampleData()` | `StrategyEngine` | **未使用** — demo 用，已过时 |
| `showResultsDialog()` 中的内联 AI 调用 | `StrategyListFragment` | **冗余** — AIPredictionStrategy 已产出同样结果 |

### 4.4 架构设计问题

| 问题 | 描述 | 建议 |
|------|------|------|
| **两套并行引擎** | `StrategyListFragment` 和 `SimulationTradeFragment` 各自创建 `StrategyEngine` + `StockScreener`，互不通信 | 抽到 Application 级别的单例共享 |
| **策略执行不打分即过** | 每个策略 `screen()` 返回结果后，AI 策略再次处理同一批数据 | AI 策略应作为后处理器而非独立策略 |
| **模拟交易未使用策略列表结果** | `SimulationTradeEngine.runTradeSession()` 重新调用 `strategy.screen()`，而不是复用 `StrategyListFragment` 已有的 `cachedResults` | 应传递策略列表的结果，避免重复执行 |
| **showResultsDialog 过于臃肿** | 500+ 行代码内嵌 UI 构建 + 数据查询 + AI 调用 | 应拆分为独立的 ResultFragment |
| **AIPredictionEngine.predict() 无缓存** | 每次调用都是全新 LLM 请求 | 增加内存缓存（相同 results + date 不重复调） |

---

## 5. 现有 docs/ 文档评审

### 5.1 `docs/README.md` (项目总览)

| 问题 | 说明 |
|------|------|
| ✅ 架构图清晰 | v8.0 架构图展示了3-Tab结构 |
| ❌ 版本号过时 | 标注 v8.0，但代码已有 v9.0 特征 (ChatTabFragment) |
| ❌ 文件清单不完整 | 缺少 `AIPredictionStrategy.kt`、`DataCompletenessChecker.kt` 等新文件 |
| ❌ 未提及 AI 量化选股策略 | 文档未反映最近的重构变化 |

### 5.2 `docs/Quantitative_Simulated_Trading.md` (量化选股)

| 问题 | 说明 |
|------|------|
| ❌ **Python 伪代码不适配** | 文档使用 Python 风格的接口定义，但项目是 Kotlin/Android |
| ❌ **描述的 API 不存在** | 提到 `POST /api/ai/analyze_stock`、`Flask/FastAPI` 等后端 API，但项目是纯客户端 Android App |
| ❌ **与代码实际行为不一致** | 描述的策略合并、星级权重等逻辑在代码中不存在 |
| ❌ **文档最后建议"写入 docs 并提交 PR"** | 说明这是一份 AI 生成的规范文档，未经过与代码的验证 |

### 5.3 `docs/requirement.md`

> 未读取内容，但从文件名推断为需求文档。

### 5.4 缺失的文档

| 应存在但缺失 | 建议内容 |
|-------------|---------|
| 策略调用流程图 | 本文 1-2 节的内容应正式归档 |
| 数据库 Schema 说明 | Entity/Dao 关系图 |
| AI 预测引擎使用规范 | 何时调用、如何缓存、限流策略 |
| 重复调用排查指南 | 本文第4节内容 |

---

## 附录：核心文件与调用关系速查

| 文件 | 角色 | 被谁调用 |
|------|------|---------|
| `StrategyListFragment.kt` | 策略Tab UI | `MainActivity` |
| `StrategyEngine.kt` | 策略注册/执行管理 | `StrategyListFragment`, `SimulationTradeFragment` |
| `StockScreener.kt` | 全市场扫描 | `StrategyEngine`, `doExecute()` |
| `AIPredictionStrategy.kt` | AI选股策略(封装AI引擎为策略) | `doExecute()`, `executeRealTime()` |
| `AIPredictionEngine.kt` | AI预测核心(LLM调用) | `AIPredictionStrategy`, `showResultsDialog()`, `SimulationTradeEngine` |
| `SimulationTradeFragment.kt` | 模拟交易UI | `MainActivity` |
| `SimulationTradeEngine.kt` | 模拟交易核心 | `SimulationTradeFragment` |
| `DataCompletenessChecker.kt` | 板块数据补全 | `ChatTabFragment.showHotSectors()` |
| `StockDatabase.kt` | Room 数据库 | 几乎所有模块 |

---

**最后更新**: 2026-06-07  
**分析版本**: 基于 `git commit 0c927a7`