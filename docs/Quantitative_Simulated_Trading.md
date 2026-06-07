# 量化选股与模拟交易系统架构

> 基于 2026-06-07 代码库分析，本文描述 StockAnalysis Android App 中量化策略和模拟交易的实际架构。
> 如有疑问，参照 `docs/Strategy_Architecture_Analysis.md` 中详细的调用链分析。

---

## 1. 策略接口 (Kotlin)

所有选股策略实现 `Strategy` 接口：

```kotlin
// 文件: app/src/main/java/com/chin/stockanalysis/strategy/Strategy.kt
interface Strategy {
    val id: String                         // 策略唯一标识, e.g. "ma_cross"
    var name: String                       // 策略名称, e.g. "均线金叉策略"
    var description: String
    val category: StrategyCategory         // TREND | MOMENTUM | VALUE | VOLUME | CUSTOM
    val config: StrategyConfig             // stockPool + maxResults + params
    var weightFactors: List<WeightFactor>  // 权重因子(总和100)
    val source: StrategySource             // BUILTIN | USER_CUSTOM

    suspend fun screen(): Result<ScreeningResult>                   // 实时扫描
    suspend fun screenWithData(preloadedStocks: List<StockRealtime>): Result<ScreeningResult>  // 预加载数据扫描
    suspend fun isAvailable(): Boolean
}
```

### 已注册的策略 (按执行顺序)

| 策略 | ID | 类别 | 来源 |
|------|-----|------|------|
| `MovingAverageStrategy` | `ma_cross` | TREND | BUILTIN |
| `VolumeBreakStrategy` | `volume_break` | VOLUME | BUILTIN |
| `LowValuationStrategy` | `low_valuation` | VALUE | BUILTIN |
| `GapUpMomentumStrategy` | `gap_up` | MOMENTUM | BUILTIN |
| `TurnoverFilterStrategy` | `turnover_filter` | VOLUME | BUILTIN |
| `EarlyMorningChaseStrategy` | `morning_chase` | CUSTOM | BUILTIN |
| `TailLowPickStrategy` | `tail_low_pick` | CUSTOM | USER_CUSTOM |
| `AIPredictionStrategy` | `ai_prediction` | CUSTOM | USER_CUSTOM |

---

## 2. 策略引擎 (StrategyEngine)

```kotlin
// 文件: app/src/main/java/com/chin/stockanalysis/strategy/StrategyEngine.kt
class StrategyEngine(context: Context, screener: StockScreener) {
    fun registerStrategy(strategy: Strategy)   // 注册策略
    fun removeStrategy(id: String): Boolean    // 移除策略
    fun setEnabled(id: String, enabled: Boolean)
    fun isEnabled(id: String): Boolean
    fun getStrategies(): List<Strategy>
    fun getEnabledStrategies(): List<Strategy>
    fun cancelScan()
}
```

### 执行流的实际实现位置

引擎提供 `runAll()` / `runAllWithData()` / `runOne()` 方法，但 **在项目中这些方法并未被使用**。实际的策略执行逻辑直接在 `StrategyListFragment.doExecute()` 和 `executeRealTime()` 中手动迭代策略：

```kotlin
// 文件: app/src/main/java/com/chin/stockanalysis/ui/StrategyListFragment.kt
private suspend fun doExecute(...) {
    // 1. 先执行所有非AI策略
    for (s in strategies) {
        if (s.id == "ai_prediction") continue
        nonAiResults += s.screenWithData(stockList).getOrNull()
    }
    // 2. 注入非AI结果到AI策略
    aiStrategy.strategyResults = nonAiResults
    aiStrategy.targetDate = selectedDate
    aiResult = aiStrategy.screenWithData(stockList).getOrNull()
}
```

---

## 3. 策略结果模型

```kotlin
// 文件: app/src/main/java/com/chin/stockanalysis/strategy/models/ScreeningResult.kt
data class ScreeningResult(
    val strategyId: String,
    val strategyName: String,
    val category: StrategyCategory,
    val signals: List<StrategySignal>,   // 股票信号列表
    val totalScanned: Int,               // 扫描总数
    val scanTimeMs: Long                 // 耗时
)

data class StrategySignal(
    val stockCode: String,      // 股票代码 e.g. "sh600519"
    val stockName: String,      // 名称
    val strategyId: String,
    val category: StrategyCategory,
    val strength: Int,          // 0-100 信号强度
    val action: SignalAction,   // BUY | WATCH | HOLD | SELL
    val reason: String,         // 入选理由
    val details: Map<String, String>,
    val currentPrice: Double,
    val changePercent: Double
)
```

---

## 4. 模拟交易引擎 (SimulationTradeEngine)

```kotlin
// 文件: app/src/main/java/com/chin/stockanalysis/strategy/trade/SimulationTradeEngine.kt
class SimulationTradeEngine(context: Context) {
    val PERIOD_DAYS = listOf(1, 3, 10, 30, 50, 100)
    
    suspend fun runTradeSession(
        strategies: List<Strategy>,
        config: TradeSessionConfig
    ): TradeSessionReport
    
    suspend fun backtrackAndOptimize(
        strategies: List<Strategy>,
        config: TradeSessionConfig,
        oldSessionResults: List<StrategyPeriodResult>,
        boughtStocks: Set<String>
    ): BacktrackReport
}
```

### 数据流
```
策略执行 → 多周期(1/3/10/30/50/100日)结果
  → 新闻力度评分 + 板块轮动惩罚
  → 主板过滤(记录过滤原因) → Top3保留
  → AI精选3只 → 模拟买入订单
  → 次日验证 → 调优拟合参数
```

### 拟合参数
`showFittingParams()` 仅读取已有的历史拟合数据（不执行新拟合）。
实际拟合在 `runTradeSession()` 内自动完成(最多200轮)，参数写入 `strategy_trade_fitting_params` 表。

### 回溯复盘
`runNextDayBacktrack()` 重新执行全部策略，对比已买入的订单与落选股在次日的表现，自动优化过滤参数。

---

## 5. AI 预测引擎 (AIPredictionEngine)

```kotlin
// 文件: app/src/main/java/com/chin/stockanalysis/strategy/predict/AIPredictionEngine.kt
class AIPredictionEngine(context: Context) {
    data class AIPrediction(
        val mode: String,              // "A" 或 "B"
        val modeReason: String,        // 方案选择理由
        val topPicks: List<AIPick>,    // Top 3-5 推荐
        val marketOutlook: String,     // 市场判断
        val riskWarning: String        // 风险提示
    )
    
    data class AIPick(
        val rank: Int,
        val stockCode: String,
        val stockName: String,
        val compositeScore: Int,       // 综合评分 0-100
        val upProbability: Int,        // 上涨概率 %
        val reason: String,
        val actionSuggestion: String   // 操作建议
    )
    
    suspend fun predict(
        strategyResults: List<ScreeningResult>,
        selectedDate: String,
        onProgress: ((String) -> Unit)? = null
    ): AIPrediction?
}
```

### AI Provider 选择
- 优先使用 `ApiConfigManager.createCurrentProvider()`
- 无有效 Key 时自动回退到 assets 内置的 `doubao` Provider
- `predict()` 在异常时返回 `null`

### 当前项目中的 AI 调用位置

| 位置 | 文件 | 触发方式 |
|------|------|---------|
| ① AIPredictionStrategy 作为策略执行 | `AIPredictionStrategy.kt` | `doExecute()` 中在其他策略之后自动执行 |
| ② showResultsDialog 独立调用 | `StrategyListFragment.kt` | 弹出结果对话框后异步调用 |
| ③ 模拟交易引擎 | `SimulationTradeEngine.kt` | `runTradeSession()` 内部 |

**注意**: ① 和 ② 对同一份 `results` 调用 `predict()`，存在重复 LLM 调用。参见 `Strategy_Architecture_Analysis.md` 第3节的详细分析。

---

## 6. 数据库相关

策略模块使用的 Room 表：

| 表 | DAO 方法 | 用途 |
|------|---------|------|
| `daily_snapshot` | `getByDate(date)` / `getAvailableDates(n)` | 每只股票每日OHLCV |
| `stock_basic` | `getAll()` | 股票代码/名称映射 |
| `sector_stocks` | `getSectorNamesByStockCode()` / `getStockCodesBySector()` | 板块-股票映射 |
| `daily_period_result` | `save()` / `getByDate()` | 模拟交易周期结果 |
| `strategy_trade_fitting_params` | `getRecentByStrategy()` | 拟合调优参数 |
| `strategy_trade_orders` | `insert()` / `getRecent()` / `updateSellInfo()` | 模拟交易订单 |
| `daily_news_hot_picks` | `getByDate()` | 每日新闻热点固化 |

---

## 7. 数据导出导入 (DataExportImport)

```kotlin
// 文件: app/src/main/java/com/chin/stockanalysis/stock/database/DataExportImport.kt
class DataExportImport(context: Context) {
    fun exportAllToJson(): String      // 导出全部10张表到 JSON
    fun exportAllToCsv(): List<String> // 分表导出 CSV
    fun importFromJson(path: String): ImportReport
    fun getExportFiles(): Array<File>
    fun getDatabaseStats(): String
}
```

导出路径: `Android/data/com.chin.stockanalysis/files/StockAnalysis_exports/`

---

## 8. 策略管理功能

### 配置策略
- 通过 `StrategyListFragment` UI 的 `+策略` 按钮添加自定义策略
- 每个策略的 Switch 可启用/禁用
- 启用状态持久化到 `SharedPreferences` (key: `enabled_strategy_ids`)

### 权重调优
- `StrategySelfTuner.selfTune()` 回调30日历史数据迭代优化
- 调优结果保存到 `SharedPreferences` (key: `tuned_weights_{strategyId}`)

### 回测数据保存
- `BacktestEngine.savePredictions()` 将策略结果持久化到 `strategy_prediction` 表
- 用于后续回测对比和准确率统计

---

## 9. 当前已知问题

详见 `docs/Strategy_Architecture_Analysis.md` 第4节：

1. **重复实例化**: `StrategyEngine` 和 `StockScreener` 在 `StrategyListFragment` 和 `SimulationTradeFragment` 中各自创建独立实例
2. **重复 AI 调用**: `AIPredictionStrategy` 和 `showResultsDialog()` 对同一批数据各自调用 `AIPredictionEngine.predict()`
3. **死代码**: `runAIPredict()`, `StrategyEngine.runAll()`, `StrategyEngine.runAllWithData()`, `StrategyEngine.runWithSampleData()`
4. **AIPredictionStrategy 股价为0**: `AIPick` 数据类无 `price` 字段，导致显示的股价和涨幅数据都是 0

---

**最后更新**: 2026-06-07  
**基于**: Kotlin/Android 代码库 `git commit 0c927a7`