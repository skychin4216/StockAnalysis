# 量化選股 (StrategyListFragment) 實現文檔

> 記錄日期: 2026-07-12
> 目的: 後續長線選股改造參考（相關代碼暫時保留）

---

## 一、概述

量化選股是策略 Tab 的第一個子 Tab，功能是**純篩選**（不涉及交易）。
用戶選擇交易日和熱門周期後，執行多策略並行篩選，展示命中的股票信號，
並異步調用 AI 預測生成最終推薦。

**核心特點**:
- 純篩選，無買入/賣出
- AI 在 UI 層異步調用（不在 Pipeline 內部）
- 結果展示為彈窗（非持倉）
- 策略管理（+策略/啟用/禁用）在此 Fragment 中

---

## 二、UI 布局

```
┌────────────────────────────────────────────┐
│ 標題: N種策略 · 多維度綜合打分 · 熱門板塊驅動 │
│ 日期: 2024-01-15                           │
├────────────────────────────────────────────┤
│ [熱門周期 ▾] [交易日 ◀ 2024-01-15 ▶] [重置] │
│ [☐ 主板過濾]                              │
├────────────────────────────────────────────┤
│ [執行策略] [擬合(90%)] [數據] [導入] [+策略] │
├────────────────────────────────────────────┤
│ 狀態: 就緒 / 執行中... / 熱門板塊信息       │
│ ████████░░░░ 進度條                         │
├────────────────────────────────────────────┤
│ RecyclerView: 策略列表                      │
│ ┌─────────────────────────────────────┐   │
│ │ ☐ 均線策略    TREND   [編輯]        │   │
│ │ ☑ 布林帶策略  TREND   [編輯]        │   │
│ │ ☑ 放量突破    VOLUME  [編輯]        │   │
│ │ ☑ 低估值      VALUE   [編輯]        │   │
│ │ ...                                │   │
│ └─────────────────────────────────────┘   │
└────────────────────────────────────────────┘
```

---

## 三、完整執行流程

### 3.1 入口: `runSelectedStrategies()`

```
用戶點擊「執行策略」
  │
  ├─ 緩存檢查（10分鐘內相同條件？）
  │   └─ 命中 → 直接顯示緩存結果
  │
  ├─ 從 StockDatabase 加載指定日期快照
  │   ├─ 無快照 + 正在交易中 → executeRealTime()（實時掃描）
  │   ├─ 無快照 + 非交易中 → 嘗試最近可用日期 → 仍無 → executeRealTime()
  │   └─ 有快照 → doExecute()
  │
  └─ UI: scanBtn 禁用 + 顯示進度條
```

### 3.2 核心執行: `doExecute()`

```
doExecute(eng, db, snapshots, selectedDate, sectorLabel)
  │
  ├─ 1. 多日快照合併（getMultiDaySnapshots）
  │     若 selectedHotPeriod > 0，加載多天數據，取 distinct by code，最多 1000 只
  │     預加載所有股票的板塊信息到 StockDataCenter 緩存
  │
  ├─ 2. 構建統一市場上下文
  │     StrategyMarketContext.build(context, selectedDate)
  │     包含: 用戶關注板塊、多周期熱門板塊、反彈板塊、指數快照、板塊大年檢測
  │
  ├─ 3. 構建股票池
  │     a) 熱門板塊股票（currentHotSectors → sectorStockDao）
  │     b) 用戶關注板塊股票（userFocusSectors → sectorStockDao）
  │     c) 反彈板塊股票（bounceSectors → sectorStockDao）
  │     合併 → allSectorCodes
  │     StrategyDataFeed.convertSnapshots() 轉換快照
  │     主板開關過濾: isMainBoard(code) 排除 sz300/sz301/sh688/bj
  │     若 allSectorCodes 非空，只保留相關股票
  │
  ├─ 4. 逐策略執行篩選（跳過禁用策略和 ai_prediction）
  │     for s in eng.getStrategies():
  │       if !isEnabled || id=="ai_prediction" → skip
  │       s.screenWithData(stockList) → raw ScreeningResult
  │
  ├─ 5. 後處理: 板塊加權
  │     遍歷每條 signal:
  │       bonus += marketCtx.getFocusBoostForStock(stockName)
  │       bonus += marketCtx.getBounceBoostForStock(stockName)
  │       strength = (strength + bonus).coerceAtMost(100)
  │
  ├─ 6. 結果緩存 + 持久化
  │     cachedResults = results
  │     lastSectorContext = marketCtx.toAiSectorContext()
  │     CrossTabBus.postStrategyResults(results)
  │     saveBacktestData(results) → BacktestEngine.savePredictions()
  │                             → dailyPeriodResultDao().insert()
  │
  └─ 7. 顯示結果 → showResultsDialog(results)
```

### 3.3 AI 預測（異步）

在 `showResultsDialog()` 中異步執行：

```
AIPredictionEngine.predict(
    results, browsingDate,
    useEnhancedAi = true,
    sectorContext = lastSectorContext
)
  │
  └─ 展示 AI 預測結果:
       • 方案模式 + 原因
       • 大盤方向 (BULLISH/BEARISH/NEUTRAL)
       • 市場判斷
       • 風險提示
       • 板塊加權信息
       • Top推薦表格: 排名/名稱/代碼/綜分/概率/建議
```

---

## 四、數據源

| 數據源 | 用途 | 調用位置 |
|--------|------|----------|
| `StockDatabase.dailySnapshotDao` | 日線快照數據 | `doExecute()` 加載指定日期快照 |
| `StockScreener.scanFullMarket()` | 實時全市場掃描 | `executeRealTime()` |
| `EastMoneyHotSectorSource` | 熱門板塊數據 | `loadHotSectors()` |
| `StockDataCenter` | 板塊-股票映射、熱門板塊按周期查詢 | `getHotSectorsByPeriod()`/`getSectorsByStock()` |
| `StrategyMarketContext` | 統一市場上下文 | `doExecute()` 中 `build()` |
| `UserMarketMemory` | 用戶持久化的關注板塊 | `showMarketMemoryDialog()` |
| `StrategyDataFeed` | 快照數據轉換 | `convertSnapshots()` |

---

## 五、策略引擎

### 5.1 StrategyEngine

核心管理類：
- 策略註冊/刪除 (`registerStrategy` / `removeStrategy`)
- 啟用/禁用持久化 (SharedPreferences `"strategy_prefs"`)
- 執行掃描 (`runAll` / `runAllWithData` / `runOne`)，30 秒超時
- 結果緩存 (`lastResults`)

### 5.2 內置策略清單

| 策略 | 類別 | 文件 |
|------|------|------|
| MovingAverageStrategy | TREND | 均線策略 |
| BollingerBandStrategy | TREND | 布林帶策略 |
| RSIDivergenceStrategy | TREND | RSI 背離策略 |
| VolumeBreakStrategy | VOLUME | 放量突破策略 |
| TurnoverFilterStrategy | VOLUME | 換手率過濾策略 |
| SmartMoneyDetectionStrategy | VOLUME | 主力資金檢測策略 |
| LowValuationStrategy | VALUE | 低估值策略 |
| FundamentalFilterStrategy | VALUE | 基本面過濾策略 |
| GapUpMomentumStrategy | MOMENTUM | 缺口動量策略 |
| HotSpotDrivenStrategy | MOMENTUM | 熱點驅動策略 |
| TailLowPickStrategy | MOMENTUM | 尾盤低吸策略 |
| EarlyMorningChaseStrategy | MOMENTUM | 早盤追漲策略 |
| AIPredictionStrategy | — | AI 預測策略（執行時被跳過，單獨異步處理） |

### 5.3 策略接口

```kotlin
interface Strategy {
    val id: String
    var name: String
    var description: String
    val category: StrategyCategory  // TREND/MOMENTUM/VALUE/VOLUME/CUSTOM
    val config: StrategyConfig
    var weightFactors: List<WeightFactor>
    val source: StrategySource      // BUILTIN / USER_CUSTOM

    suspend fun screen(): Result<ScreeningResult>
    suspend fun screenWithData(preloadedStocks: List<StockRealtime>): Result<ScreeningResult>
    suspend fun isAvailable(): Boolean
}
```

---

## 六、+策略（自定義策略）

### 6.1 創建流程

```
用戶點擊「+策略」
  │
  ├─ 彈出 AlertDialog:
  │   • 名稱輸入框 (hint="策略名稱")
  │   • 描述輸入框 (hint="策略描述")
  │
  ├─ 點擊「創建」:
  │   id = "custom_${System.currentTimeMillis()}"
  │   category = StrategyCategory.CUSTOM
  │   source = StrategySource.USER_CUSTOM
  │   config = StrategyConfig.fullMarket(20)
  │   weightFactors = [WeightFactor("default", "綜合評分", 100, "默認權重")]
  │
  ├─ engine.registerStrategy(自定義策略)
  │   └─ 自動 setEnabled(id, true)
  │
  └─ refreshList() → 刷新 RecyclerView
```

**注意**: 自定義策略 `isAvailable()` 返回 false，`screen()` 返回空結果，
即用戶創建的自定義策略不會產生實際篩選信號，需要手動實現篩選邏輯。

### 6.2 管理

- **啟用/禁用**: 點擊策略條目的開關
- **編輯/刪除**: 點擊策略條目 → `StrategyDetailFragment`
- **存儲**:
  - 啟用狀態：SharedPreferences (`strategy_prefs`)
  - 自定義策略實例：內存中（`StrategyEngineHolder` 單例）

---

## 七、擬合功能

### 7.1 入口

```kotlin
StrategySelfTuner(context).selfTune(
    strategies = engine.getEnabledStrategies(),
    backtestDays = 30,
    targetAccuracy = 0.90f
)
```

### 7.2 工作流

```
Step 1: 原始回測
  HistoricalBacktestEngine.runHistoricalBacktest(strategy, 30天)
  → 統計買入準確率、平均收益

Step 2: 檢查是否需要調優
  if accuracy >= 90% → 跳過

Step 3: 增量梯度分析
  逐日擾動 ±5，計算每個權重因子的貢獻

Step 4: 新舊權重融合
  新權重 × 70% + 舊權重 × 30% → 歸一化到 100

Step 5: 調優後回測驗證
  if improved → 保存權重快照
  else → 還原

Step 6: 今日驗證（交易日+盤中）
```

### 7.3 持久化

- 權重快照表: `strategy_weight_snapshot`
- 啟動時加載: `StrategySelfTuner.loadLatestTunedWeights()`

---

## 八、結果展示

### 8.1 掃描結果彈窗

全屏 AlertDialog，包含：
1. 各策略獨立結果（分類圖標 + 名稱 + 命中數 + 耗時）
2. 命中信號表格（名稱/子板塊/代碼/強度/價格/漲幅）
3. 強度顏色規則:
   - >= 80: `#E65100` (深橙)
   - >= 60: `#2E7D32` (綠)
   - < 60: `#666666` (灰)
4. 每行可點擊 → 跳轉 StockDetailNavigator
5. AI 量化選股區域（異步加載）

### 8.2 子板塊標籤

- 優先 StockDataCenter 查詢
- Fallback 硬編碼映射表（~80 個關鍵詞→子板塊）
- LRU 緩存 (200 條)

---

## 九、緩存機制

| 緩存類型 | TTL | 用途 |
|---------|-----|------|
| 執行結果 | 10 分鐘 + 相同條件 | 避免重複執行 |
| 市場上下文 | 5 分鐘 | 避免重複構建 |
| 子板塊標籤 | LRU 200 條 | getSectorLabel() |
| 策略啟用狀態 | 永久 (SharedPreferences) | 持久化 |
| 權重快照 | 永久 (DB) | 啟動時加載最新 |

---

## 十、與中線/短線的差異

| 方面 | 量化選股 | 中線量化 | 短線量化 |
|------|---------|---------|---------|
| 目標 | 純篩選 | 模擬交易 | Pipeline 自動交易 |
| 交易 | 無 | 有 | 有 |
| AI 調用 | UI 層異步 | Pipeline 內 | Pipeline 內 |
| Pipeline | 有（但未使用） | UseCaseLoader | 直接 TradeEngine |
| 回測 | 擬合中間接使用 | 有獨立回測 | Pipeline 內含 |
| 結果展示 | 彈窗 | 持倉列表 | 持倉列表 |

---

## 十一、Pipeline 架構（已定義但未使用）

### 11.1 UseCase XML

```xml
<usecase id="screening" name="量化選股">
  <steps>
    1. data_prep_pipeline.xml — 數據準備
    2. strategy_screening_pipeline.xml [parallel] — 策略篩選
    3. merge_boost_pipeline.xml — 合併加權
  </steps>
  <config: orderType=Screening, maxHoldings=0 />
</usecase>
```

### 11.2 ScreeningUseCase 三階段

| Stage | 名稱 | 節點 |
|-------|------|------|
| Stage 1 | 數據準備 | MarketContextNode |
| Stage 2 | 策略篩選 | StockPoolNode → StrategyNode×N |
| Stage 3 | 合併加權 | SignalMergeNode → SectorBoostNode |

---

## 十二、關鍵文件

| 文件 | 路徑 | 用途 |
|------|------|------|
| StrategyListFragment.kt | `ui/StrategyListFragment.kt` | 主 Fragment（773 行） |
| StrategyFragment.kt | `ui/StrategyFragment.kt` | Tab 容器 |
| ScreeningUseCase.kt | `topology/usecase/ScreeningUseCase.kt` | Pipeline UseCase |
| StrategyEngine.kt | `strategy/StrategyEngine.kt` | 策略管理 |
| Strategy.kt | `strategy/Strategy.kt` | 策略接口 |
| StrategySelfTuner.kt | `strategy/backtest/StrategySelfTuner.kt` | 擬合調優（749 行） |
| screening_usecase.xml | `assets/usecases/` | UseCase XML |
| strategy_screening_pipeline.xml | `assets/usecases/` | 策略篩選 Pipeline |
| data_prep_pipeline.xml | `assets/usecases/` | 數據準備 Pipeline |
| merge_boost_pipeline.xml | `assets/usecases/` | 合併加權 Pipeline |

---

## 十三、後續改造要點（長線選股參考）

1. **策略引擎共用**: 長線選股應與中線/短線共用同一個 StrategyEngine
2. **Pipeline 模式**: 將 `doExecute()` 邏輯遷移到 UseCaseLoader（走 ScreeningUseCase）
3. **UI 公用**: 繼承 QuantFragmentBase，共用按鈕行
4. **AI 集成**: AI 預測可以在 Pipeline 中通過 AIPredictNode 實現（不需要 UI 層異步）
5. **交易擴展**: 長線選股可以加入模擬交易功能（生成訂單 + 持倉管理）
6. **擬合遷移**: 將 StrategySelfTuner 的擬合邏輯移入數據管理菜單（FittingSaveNode）
7. **+策略共用**: 策略管理從 StrategyListFragment 遷移到 StrategyFragment 層級的 FAB
