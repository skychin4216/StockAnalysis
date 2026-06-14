# 策略模塊架構分析 v3.0

> 基於 2026-06-14 代碼庫分析，涵蓋 v2.0→v3.0 演進

---

## 1. 系統總覽 — 三層架構

```
┌──────────────────────────────────────────────────────────────┐
│  UI 層 (Fragments)                                            │
│  StrategyListFragment  │  SimulationTradeFragment  │  AutoQuantFragment │  ChatTabFragment │
├──────────────────────────────────────────────────────────────┤
│  Bus 層 (CrossTabBus)                                         │
│  strategyResults │ aiTopPicks │ mergedPool │ command           │
├──────────────────────────────────────────────────────────────┤
│  Engine 層 (策略 + 量化 + 擬合)                                │
│  StrategyEngineHolder │ SimulationTradeEngine │ StockAnalyzerService │ AIPredictionEngine │ StrategyConfigGenerator │
├──────────────────────────────────────────────────────────────┤
│  Data 層                                                    │
│  HistoricalDataFetcher │ StrategyDataFeed │ ZiplinePipeline │ StockDatabase (Room) │
└──────────────────────────────────────────────────────────────┘
```

---

## 2. 策略註冊 (全局共享)

```kotlin
StrategyEngineHolder.get()  // 全局單例
  ├── MovingAverageStrategy  (均線金叉)
  ├── VolumeBreakStrategy    (放量突破)
  ├── LowValuationStrategy   (低估值)
  ├── GapUpMomentumStrategy  (高開高走)
  ├── TurnoverFilterStrategy (換手率)
  ├── BollingerBandStrategy  (布林帶突破)
  ├── RSIDivergenceStrategy  (RSI背離)
  ├── EarlyMorningChaseStrategy (早盤追漲)
  ├── TailLowPickStrategy    (超短線)
  └── AIPredictionStrategy   (AI預測)
```

### v3.0 新增: AI元程式設計策略生成

```kotlin
用戶輸入: "創建 MACD金叉+量能放大策略"
  → IntentDispatcher → CREATE_STRATEGY command
  → StrategyConfigGenerator.generate(description)
  → DeepSeek AI → JSON {id, name, weightFactors}
  → StrategyEngineHolder.registerStrategy(newStrategy)
  → 下次量化選股自動包含新策略
```

---

## 3. 三個 Tab 的數據流

### 3.1 量化選股 Tab (StrategyListFragment)

```
導入按鈕 (唯一入口)
  ├── HistoricalDataFetcher.fetchAllHistoricalData(1~100天)
  └── 寫入 daily_snapshot 表 + SharedPreferences(last_import_date)

執行策略
  ├── 檢查緩存 (10分鐘)
  ├── 無數據 → executeRealTime() → StockScreener
  └── 有數據 → doExecute() → DB查詢 → 10個策略篩選
       └── 導入完成 → CrossTabBus.postCommand("AUTO_FIT") → 自動擬合
```

### 3.2 模擬交易 Tab (SimulationTradeFragment)

```
擬合
  ├── runFitting() → 對比 nextDay 驗證 Top15 準確率
  └── 保存到 strategy_trade_fitting_params

回溯
  ├── runNextDayBacktrack() → 對每個買入訂單驗證次日漲跌
  └── 記錄到 BACKTRACK entity

智能賣出
  └── AutoSellEngine → 10維智能賣出評估
```

### 3.3 自動量化 Tab (AutoQuantFragment)

```
Pipeline 按鈕
  ├── 自動檢查數據 → needImport → 自動導入 (60天)
  ├── ZiplinePipeline.computeAll() → 因子計算 (MA5/MA20/MACD/RSI/ATR)
  ├── 10個策略 → screenWithData()
  ├── 合並池 (多策略交集)
  ├── AIPredictionEngine → 精選 Top5
  └── CrossTabBus 發布結果

🔧 擬合按鈕 (v3.0 新增)
  ├── SimulationTradeEngine.autoFit(strategies, recentDates)
  └── 遍歷最近30個交易日 → 驗證每個策略準確率
```

---

## 4. CrossTabBus — 跨Tab數據總線

| 通道 | 發佈端 | 訂閱端 |
|------|--------|--------|
| `strategyResults` | StrategyListFragment, AutoQuantFragment | ChatTabFragment |
| `aiTopPicks` | AutoQuantFragment | ChatTabFragment |
| `mergedPool` | AutoQuantFragment | ChatTabFragment |
| `command` | IntentDispatcher | ChatTabFragment, StrategyFragment |

### 支持的命令 (v3.0)

| 命令 | 觸發方式 | 效果 |
|------|---------|------|
| `EXECUTE_SIMULATE_TRADE` | 對話框輸入"執行模擬交易" | 自動切換模擬交易Tab→買入 |
| `RUN_PIPELINE` | 對話框輸入"運行Pipeline" | 自動切換自動量化→執行Pipeline |
| `ANALYZE_SINGLE_STOCK` | 對話框分析股票 | StockAnalyzerService→5步分析 |
| `CREATE_STRATEGY` | 對話框輸入"創建XXX策略" | AI生成JSON→動態註冊策略 |
| `AUTO_FIT` | 導入完成後觸發 | SimulationTradeEngine.autoFit(30) |

---

## 5. 對話框股票分析流程 (v3.0)

```
用戶輸入 "分析利通電子"
  → IntentDispatcher.lookupStock() → stockBasicDao.searchByName()
  → 找到代碼 → postCommand(ANALYZE_SINGLE_STOCK)
  → ChatTabFragment collect
  → StockAnalyzerService:
      Step 1: 獲取最新行情數據
      Step 2: 記錄到 userSearchHistory
      Step 3: 執行10個策略 → onProgress(每條獨立顯示)
      Step 4: 智能體技術分析 → onProgress(技術指標)
      Step 5: AIPredictionEngine → 綜合評估
  → 如果有策略命中 → showBuyConfirmationDialog()
  → 買入確認 → 最多5只持倉 → 騰籠換鳥
```

---

## 6. 數據層核心表

| 表 | 用途 |
|----|------|
| `daily_snapshot` | 每日OHLCV快照 (歷史回測) |
| `stock_basics` | 股票基本信息 (名稱映射) |
| `sector_stocks` | 板塊→股票映射 |
| `daily_period_result` | 策略周期結果 (各策略Top15) |
| `strategy_trade_fitting_params` | 擬合參數 (準確率/平均收益) |
| `strategy_trade_orders` | 交易訂單 (買入/賣出) |
| `strategy_weight_snapshots` | 權重快照 |

---

## 7. 線程安全修復 (v3.0)

| 問題 | 修復 |
|------|------|
| `fetchAllHistoricalData` 進度卡住 | `TOTAL_COMPLETE++` → `AtomicInteger.addAndGet()` |
| `ChatAdapter` 重新生成崩潰 | `notifyItemRangeRemoved` 計數修正 |
| 導入重複執行 | 新增 `last_import_date` 檢查 → SharedPreferences |

---

## 8. 關鍵設計決策

1. **導入按鈕統一入口** — 量化選股為唯一導入入口，自動量化執行前自動檢查
2. **擬合按鈕取代導入按鈕** — AutoQuant 中導入按鈕改為擬合按鈕
3. **自動擬合** — 導入完成後觸發 AUTO_FIT command → SimulationTradeEngine.autoFit(30)
4. **AtomicInteger** — 消除多線程環境下的計數器競爭
5. **AI元程式設計策略** — 用戶用自然語言描述策略邏輯，AI生成JSON配置動態註冊