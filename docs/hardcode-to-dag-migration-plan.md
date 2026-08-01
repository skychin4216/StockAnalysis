# Hardcode → DAG Pipeline 完整遷移計劃

> 目標：刪除所有 Fragment 中的 hardcode 路徑，統一使用 DAG Pipeline。
> 前提：DAG 必須完整覆蓋 hardcode 的每個步驟，零功能損失。DAG 代表 Directed Acyclic Graph（有向无环图）

---

## 一、Log 分析結論（2026-07-30）

### 1.1 超短線：騰籠換鳥「一直在執行」

**結論：非 Bug，設計行為。**

```
swap_weak: 騰龍換鳥: 持倉 0 + 新買 2 ≤ 5, 倉位足夠, 無需換股
swap_weak: 📤 騰龍換鳥 輸出: 0 只換股，持倉不變 0  (1ms)
```

SwapWeakNode 是 AUXILIARY 節點，永遠不阻斷 pipeline。當 `持倉 + 新買 ≤ maxHoldings` 時立即返回空結果（1ms）。日誌看起來「一直在執行」但實際是 no-op。

**優化（可選）**：持倉=0 且新買=0 時跳過日誌輸出，減少噪音。

### 1.2 短線：成功

```
signal_merge: 7 個策略, 涉及 22 只股票, 多策略命中 1 只
smart_money_filter: 通過 2 只, 淘汰 3 只, 通過率 40.0%
generate_orders: 2 只候選 [海天味业, 工商银行] → 最終 1 個訂單 [工商银行]
```

短線策略池豐富（gap_up/volume_break/turnover_active/heat_score 等），即使 BEARISH(87) 仍能找到機會。smart_money 對短線票過濾合理。

### 1.3 中線：失敗

```
strategy_3: ✗ 失敗: 策略: AI量化选股 (30002ms) — Node 超時
generate_orders: 🛡 大盤防守: BEARISH(強度87) + 高分候選僅 0 只(<2) → 空倉觀望
```

**根因**：AI 策略超時 30s → 無候選 → 防守觸發空倉。非策略邏輯問題，是 LLM 響應慢。

### 1.4 長線：失敗（核心問題）

```
strategy_low_valuation: 命中 15 只
smart_money_filter: 通過 0 只, 淘汰 5 只, 通過率 0.0%  ← 全滅
generate_orders: 🛡 大盤防守: BEARISH(強度87) + 高分候選僅 0 只(<2) → 空倉觀望
```

**根因**：
1. **smart_money_filter 對長線價值股過於嚴格** — 建設銀行/工商銀行等低波動高息股，主力資金分天然低（錢不是「流入」而是「沉澱」），minScore=55 直接全滅
2. **缺少防守型選股邏輯** — 熊市/震盪時，資金從科技股切換到銀行/電力/高速公路等高息防禦板塊，但當前系統只會「空倉觀望」

---

## 二、新增節點：DefensiveDividendNode（防守型高息節點）

### 2.1 設計理念

> 大盤下跌 ≠ 沒有機會。資金避險時會流入高息、低波動、現金流穩定的板塊。
> 這個節點在 BEARISH/OSCILLATION 時啟動，專門尋找「熊市避風港」。

### 2.2 觸發條件

```kotlin
// 僅在以下條件同時滿足時啟動：
// 1. marketDirection == BEARISH 或 OSCILLATION
// 2. 常規策略產出的候選 < 2 只（即正常路徑「失敗」）
// 3. 週期為 MID 或 LONG（短線/超短線靠均值回歸，不走防守）
```

### 2.3 選股邏輯

```kotlin
class DefensiveDividendNode : PipelineNode<SignalMergeResult, List<StockSignal>> {

    override val nodeId = "defensive_dividend"
    override val nodeName = "防守高息"
    override val nodeType = NodeType.STRATEGY

    // 篩選條件（從 daily_snapshot v12 基本面欄位讀取）：
    // 1. 股息率 ≥ 3%（dividend_yield，需新增欄位或從 PE/PB 推算）
    // 2. PB < 1.5（低估值）
    // 3. 市值 > 500 億（大盤穩定）
    // 4. 負債率 < 70%（財務健康）
    // 5. 近 20 日跌幅 < 10%（非暴跌票，是穩定票）
    // 6. 板塊關鍵詞：銀行/保險/電力/高速公路/煤炭/石油/電信

    // 評分公式：
    // score = 股息率×30 + (1.5-PB)×20 + (500億/市值)×10 + 穩定性×20 + 板塊熱度×20
}
```

### 2.4 數據來源

| 指標 | 來源 | 備註 |
|------|------|------|
| PE/PB/市值 | daily_snapshot (v12) | 已有 |
| 負債率 | daily_snapshot.debt_to_asset | 已有 |
| 股息率 | **需新增**：push2 f9=PE, 股息率=每股分紅/股價 | 或從東財 F10 抓取 |
| 板塊歸屬 | StockBasicDao.sector | 已有 |
| 近 20 日漲跌 | daily_snapshot 計算 | 已有 |

### 2.5 與 smart_money_filter 的關係

**防守高息節點的輸出應繞過 smart_money_filter**（或降低 minScore 到 20）。

理由：銀行/電力股的主力資金分天然低，這不是「資金不關注」，而是「資金已沉澱」。用短線的主力資金指標過濾長線價值股是邏輯錯誤。

實現方式：
- 方案 A：DefensiveDividendNode 輸出直接接入 generate_orders（跳過 smart_money）
- 方案 B：smart_money_filter 對 `source=defensive` 的候選使用 minScore=20
- **推薦方案 B**：保持 DAG 拓撲不變，在 SmartMoneyFilterNode 內部按 source 分流

### 2.6 DAG 拓撲位置

```
n_signal_merge ──→ n_smart_money ──→ n_news_guard ──→ n_orders
                                          ↑
n_defensive_dividend ─────────────────────┘  (並行輸入，source=defensive)
```

或者更簡單：作為 signal_merge 的並行輸入源，與策略節點同層：

```xml
<Node id="n_defensive" name="防守高息" module="defensive_dividend"
      timeout="10" critical="false">
    <DependsOn>n_ctx</DependsOn>
    <DependsOn>n_pool</DependsOn>
</Node>
<Edge from="n_defensive" to="n_merge"/>
```

### 2.7 XML 配置

僅加入 mid_term 和 long_term pipeline：

```xml
<!-- mid_term_pipeline.xml / long_term_pipeline.xml -->
<Node id="n_defensive" name="防守高息" module="defensive_dividend"
      timeout="10" critical="false"
      config="min_dividend_yield=3.0;max_pb=1.5;min_market_cap=500;max_debt=70;sectors=銀行,保險,電力,高速公路,煤炭,石油,電信"/>
```

---

## 三、Hardcode 步驟完整清單（遷移對照表）

### 3.1 超短線 Hardcode 步驟

| # | 步驟 | DAG 對應節點 | 狀態 |
|---|------|-------------|------|
| 1 | 數據導入檢查（<100 快照時拉 30 天） | 無（DAG 假設數據已存在） | ⚠️ 需補充 |
| 2 | StrategyDataFeed.prepareFromDb() | n_stock_pool (StockPoolNode) | ✅ 已有 |
| 3 | CandidatePool.getPoolCodes() 過濾 | n_cand (CandidatePoolNode) | ✅ 已有 |
| 4 | getEnabledStrategiesByPeriod(ULTRA_SHORT) | strategy_* 節點（UseCaseLoader 按周期注入） | ✅ 已有 |
| 5 | SmartMoney 過濾（14:30 前動態啟用） | n_smart_money (SmartMoneyFilterNode) | ✅ 已有 |
| 6 | 合併排序 Top N | n_signal_merge | ✅ 已有 |
| 7 | 倉位限制 + 插入訂單 | n_orders (GenerateOrdersNode) | ✅ 已有 |
| 8 | T+1 自動賣出 | n_t1_sell (T1AutoSellNode) | ✅ 已有 |
| 9 | 加入自選股 | n_orders 內部 addBatchToWatchlist | ✅ 已有 |

**缺口**：步驟 1（數據導入）。DAG 路徑假設 DB 已有數據，首次使用時可能空池。

### 3.2 短線 Hardcode 步驟

| # | 步驟 | DAG 對應節點 | 狀態 |
|---|------|-------------|------|
| 1 | 異步新聞因子獲取 | n_news_strength / n_news_guard | ✅ 已有 |
| 2 | 數據導入檢查（60 天） | 無 | ⚠️ 需補充 |
| 3 | StrategyDataFeed.prepareFromDb() | n_stock_pool | ✅ 已有 |
| 4 | CandidatePool 過濾 | n_cand | ✅ 已有 |
| 5 | ZiplinePipeline.computeAll() | n_zipline (ZiplineFactorNode) | ✅ 已有 |
| 6 | getEnabledStrategiesByPeriod(SHORT) | strategy_* 節點 | ✅ 已有 |
| 7 | StrategyMarketContext.build() + 板塊加成 | n_ctx (MarketContextNode) + n_sector_boost | ✅ 已有 |
| 8 | AIPredictionEngine.predict() | n_ai_pred (AiPredictNode) | ✅ 已有 |
| 9 | SmartMoney 過濾（score≥55） | n_smart_money | ✅ 已有 |
| 10 | CrossTabBus 發布 | n_crosstab (CrossTabPublishNode) | ✅ 已有 |
| 11 | 騰龍換鳥（建議性） | n_swap (SwapWeakNode) | ✅ 已有 |
| 12 | 加入自選股 + AiSelectedStock 保存 | n_orders + n_fit | ✅ 已有 |

**缺口**：步驟 2（數據導入）。

### 3.3 中線 Hardcode 步驟（SimulationTradeEngine）

| # | 步驟 | DAG 對應節點 | 狀態 |
|---|------|-------------|------|
| 1 | StrategyMarketContext.build() | n_ctx | ✅ 已有 |
| 2 | 數據導入檢查（60 天） | 無 | ⚠️ 需補充 |
| 3 | buildDailyStockPool | n_stock_pool + n_sector_pool | ✅ 已有 |
| 4 | StrategyDataFeed.convertSnapshots | n_stock_pool 內部 | ✅ 已有 |
| 5 | 熱度計算 | n_heat (HeatScoreNode) | ✅ 已有 |
| 6 | SmartMoneyCache.refresh() | n_smart_money 內部 | ✅ 已有 |
| 7 | 策略信號生成（排除 ai_prediction） | strategy_* 節點 | ✅ 已有 |
| 8 | 信號過濾 + 新聞強度 + 輪動懲罰 + 主板過濾 | n_signal_merge + n_rotation + n_main_board | ✅ 已有 |
| 9 | 跨日聚合 + 多周期熱門 + 熱門板塊 | n_cross_day + n_multi_hot + n_sector_boost | ✅ 已有 |
| 10 | 用戶搜索 + Agent/Skill 選股 + 自選股 | n_cand (CandidatePoolNode 內 getUserStockCodes) | ✅ 已有 |
| 11 | 新聞攔截 checkNewsBeforeBuy | n_news_guard | ✅ 已有 |
| 12 | MarketAnalyzer.analyze() + 自適應參數 | n_adaptive (AdaptiveParamsNode) | ✅ 已有 |
| 13 | AI 預測（AIPredictionStrategy） | n_ai_pred | ✅ 已有 |
| 14 | 訂單生成：SmartMoney → 防守 → V2 ProfitQuality → ETF 過濾 | n_orders (GenerateOrdersNode) | ⚠️ 需確認 V2 |
| 15 | 持倉合併 + 去重 | n_merge_pos (PositionMergeNode) | ✅ 已有 |
| 16 | 擬合計算 + 保存 | n_fit (FittingSaveNode) | ✅ 已有 |
| 17 | 騰龍換鳥 swapWeakHoldings | n_swap (SwapWeakNode) | ✅ 已有 |

**缺口**：
- 步驟 2（數據導入）
- 步驟 14 中 V2 ProfitQualityAnalyzer 是否已在 GenerateOrdersNode 中實現？需確認

### 3.4 長線 Hardcode 步驟

| # | 步驟 | DAG 對應節點 | 狀態 |
|---|------|-------------|------|
| 1 | 數據導入檢查（60 天） | 無 | ⚠️ 需補充 |
| 2 | StrategyDataFeed.prepareFromDb() | n_stock_pool | ✅ 已有 |
| 3 | CandidatePool 過濾 | n_cand | ✅ 已有 |
| 4 | getEnabledStrategiesByPeriod(LONG) | strategy_* 節點 | ✅ 已有 |
| 5 | 合併排序 Top N | n_signal_merge | ✅ 已有 |
| 6 | 倉位限制 + 插入訂單 | n_orders | ✅ 已有 |
| 7 | 加入自選股 | n_orders 內部 | ✅ 已有 |

**缺口**：步驟 1（數據導入）。長線最簡單，幾乎已完全覆蓋。

---

## 四、遷移前必須補齊的缺口

### 4.1 數據導入節點（DataImportNode）

所有 4 個周期的 hardcode 都有「如果 DB 快照不足則先拉歷史數據」的邏輯。DAG 路徑缺少這一步。

```kotlin
class DataImportNode(
    private val days: Int = 60,
    private val minSnapshots: Int = 100
) : PipelineNode<Unit, Int> {
    override val nodeId = "data_import"
    override val nodeName = "數據導入檢查"
    override val nodeType = NodeType.DATA_SOURCE

    override suspend fun execute(context: PipelineContext, input: Unit): Int {
        val dao = StockDatabase.getInstance(context.androidContext).dailySnapshotDao()
        val count = dao.getCount()
        if (count >= minSnapshots) return count  // 數據充足，跳過

        // 拉取歷史數據（阻塞，但僅首次觸發）
        HistoricalDataFetcher(context.androidContext).fetchAllHistoricalData(days)
        return dao.getCount()
    }
}
```

XML 配置：作為 Layer 0 的第一個節點，所有下游節點依賴它。

### 4.2 V2 ProfitQualityAnalyzer 確認

中線 hardcode 的 `generateBuyOrders` 內部調用 `ProfitQualityAnalyzer.analyze()`。需確認 GenerateOrdersNode 是否已包含此邏輯，若無需補充。

### 4.3 smart_money 長線分流

長線/中線的 value 類策略產出的候選，smart_money minScore 應降低或繞過（見第二節方案 B）。

### 4.4 防守高息節點

見第二節設計。僅加入 mid_term / long_term XML。

---

## 五、遷移步驟（執行順序）

### Phase 1：補齊缺口（預估 1-2 天）

1. 實現 `DataImportNode`，加入 4 個 XML 的 Layer 0
2. 實現 `DefensiveDividendNode`，加入 mid_term / long_term XML
3. SmartMoneyFilterNode 增加 `source=defensive` 分流（minScore=20）
4. 確認 GenerateOrdersNode 的 V2 ProfitQuality 邏輯
5. 編譯 + 4 周期 DAG 路徑全量測試

### Phase 2：切換默認路徑（預估 0.5 天）

1. `FeatureFlagManager.useDagPipeline` 默認值改為 `true`
2. 觀察 1-2 天，確認 4 周期 DAG 產出與 hardcode 一致
3. 確認 UI 層（進度條、報告展示）正常

### Phase 3：刪除 Hardcode（預估 1 天）

刪除以下代碼：

| 文件 | 刪除內容 |
|------|---------|
| UltraShortQuantFragment.kt | `runBuildAndBuy()` 中 DAG check 之後的 hardcode 分支（~200 行） |
| ShortTermQuantFragment.kt | `runPipeline()`, `buyAiPicks()`, `buyAiPicksInternal()`, `analyzeSwapCandidates()`, `showPipelineTable()`（~400 行） |
| MidTermQuantFragment.kt | `executeTrade()` 中 DAG check 之後的 hardcode 分支（~115 行） |
| LongTermQuantFragment.kt | `runBuildAndBuy()` 中 DAG check 之後的 hardcode 分支（~170 行） |
| SimulationTradeEngine.kt | 執行邏輯已是死碼，但 **不可整檔刪除**（TradeOrder / StrategyTradeOrderEntity / DAO 被 DAG 節點引用）→ Phase 5 拆分 |
| ZiplinePipeline.kt | 確認 DAG 的 ZiplineFactorNode 是否完全替代，若是則刪除 |
| AIPredictionEngine.kt | 確認 DAG 的 AiPredictNode 是否完全替代，若是則刪除 |
| FeatureFlagManager.kt | 移除 `useDagPipeline` 開關 |

### Phase 4：清理（預估 0.5 天）

1. 移除 XML 中 `critical="false"` 的冗餘註釋
2. 統一 orderType 命名（DAG 用 UltraShortQuant/ShortTermQuant/MidTermQuant/LongTermQuant）
3. 更新 docs/agent-architecture-refactoring-plan.md 標記完成
4. 移除 FeatureFlagManager 中其他已完成的 flag

### Phase 5：SimulationTradeEngine 拆分（預估 0.5 天）

SimulationTradeEngine.kt 的執行邏輯（runSimulation / executeBuy / executeSell 等）已是死碼，但以下 data class / Entity / DAO 仍被 DAG 節點引用：

| 被引用類 | 引用方 |
|---------|--------|
| `TradeOrder` | GenerateOrdersNode, PositionMergeNode, SwapWeakNode |
| `StrategyTradeOrderEntity` | PositionMergeNode (入庫) |
| `StrategyTradeOrderDao` | HoldingGuardNode, GenerateOrdersNode |

**拆分步驟**：
1. 新建 `strategy/trade/model/TradeModels.kt`，移入 TradeOrder + StrategyTradeOrderEntity
2. 新建 `strategy/trade/db/TradeOrderDao.kt`，移入 DAO interface
3. 更新所有 import
4. 確認編譯通過後，刪除 SimulationTradeEngine.kt 剩餘死碼

---

## 5.5、測試 Case 規劃

### 單元測試（JVM，不依賴 Android）

| 測試目標 | 覆蓋點 | 備註 |
|---------|--------|------|
| PipelineXmlParser | 自閉合 `<Node/>` 解析、Link 拓撲、參數讀取 | 用 assets XML 做 fixture |
| DagPipeline 拓撲排序 | Kahn 分層正確性、環檢測 | 構造 mock node |
| NodeRegistry | module→factory 映射完整性（4 XML 所有 module 都有註冊） | 反射掃描 |
| SmartMoneyFilterNode | defensive 降閾、V型主力分低分通過 | mock StrategySignal |
| GenerateOrdersNode | shouldForceEmpty 邏輯、buyCap 計算、todayHoldingCodes 過濾 | mock context |
| SwapWeakNode | newBuyCount==0 早退、scoreAtBuy 比較 | mock |
| DefensiveDividendNode | BEARISH 激活、BULLISH 跳過、PB/負債過濾 | mock DailySnapshot |
| MarketAnalyzer.analyzeOverseasMarkets | 權重計算、方向判斷 | mock GlobalIndex |

### 整合測試（需 Android / Robolectric）

| 測試目標 | 覆蓋點 |
|---------|--------|
| DAG 全鏈路（ultra_short XML） | n_import→...→n_orders 輸出非空、orderType 正確 |
| PositionMerge 加倉 | 跨日同股票加倉→加權平均成本 |
| HoldingGuard 止損 | 模擬虧損觸發→AutoSellEngine 賣出 |
| FeatureFlag 回退 | useDagPipeline=false 時 Fragment 不崩潰（路徑已刪，應 graceful fallback） |

### 執行方式

- JVM 測試：`./gradlew.bat :app:testDebugUnitTest`
- 整合測試：`./gradlew.bat :app:connectedDebugAndroidTest`（需模擬器）
- 優先級：單元測試 > 整合測試（DAG 框架穩定性 > 業務邏輯）

---

## 六、風險與回退

| 風險 | 影響 | 緩解 |
|------|------|------|
| DataImportNode 首次拉數據耗時長（中線 3m+） | 用戶首次體驗差 | 加進度回調 + 後台預拉取 |
| 刪除 SimulationTradeEngine 後中線行為不一致 | 選股結果變化 | Phase 2 觀察期對比 |
| 防守高息節點誤選「價值陷阱」 | 虧損 | 加 ATR 止損 + 持倉風控節點兜底 |
| smart_money 分流後長線候選過多 | 倉位分散 | 限制 defensive 候選最多 3 只 |

**回退方案**：Phase 2 觀察期內，`useDagPipeline` 仍可切回 `false`。Phase 3 刪除後無法回退，需確保 Phase 2 充分驗證。

---

## 七、最終 DAG 拓撲（以 long_term 為例）

```
Layer 0: [n_import, n_bg]
Layer 1: [n_ctx, n_pool, n_sector_pool]
Layer 2: [n_cand, strategy_0..N, n_defensive]    ← 新增防守高息
Layer 3: [n_signal_merge]
Layer 4: [n_cross_day, n_multi_hot]
Layer 5: [n_heat]
Layer 6: [n_smart_money]                         ← 對 defensive source 降閾
Layer 7: [n_news_guard]
Layer 8: [n_adaptive, n_guard]
Layer 9: [n_orders]
Layer 10: [n_swap, n_merge_pos]
Layer 11: [n_fit, n_crosstab]
```

---

## 八、遷移完成狀態（2026-08-01）

### 8.1 總體狀態：✅ 已完成

4 個 Fragment（UltraShort/Short/Mid/Long）的 hardcode 路徑已全部刪除，`useDagPipeline` 默認 `true`，Settings 保留開關可回退。

### 8.2 已實現的關鍵節點

| 節點 | 文件 | 狀態 |
|------|------|------|
| DataImportNode | `DataImportNode.kt` | ✅ |
| DefensiveDividendNode | `DefensiveDividendNode.kt` | ✅ |
| CandidatePoolNode | `HardcodeCompatNodes.kt` | ✅ |
| ZiplineFactorNode | `HardcodeCompatNodes.kt` | ✅ |
| SectorStockPoolNode | `HardcodeCompatNodes.kt` | ✅ |
| T1AutoSellNode | `HardcodeCompatNodes.kt` | ✅ |
| CrossTabPublishNode | `HardcodeCompatNodes.kt` | ✅ |
| HoldingGuardNode | `MidTermPipelineNodes.kt` | ✅ |
| PositionMergeNode | `MidTermPipelineNodes.kt` | ✅ |
| SwapWeakNode | `MidTermPipelineNodes.kt` | ✅ |
| InstitutionalTipsNode | `InstitutionalTipsNode.kt` | ✅ |
| MaConvergenceNode | `MaConvergenceNode.kt` | ✅ |
| BounceReversalNode | `BounceReversalNode.kt` | ✅ |

### 8.3 已刪除的 Hardcode

| Fragment | 刪除行數 | 備註 |
|----------|---------|------|
| UltraShortQuantFragment | ~200 行 | 完整 DAG 路徑替換 |
| ShortTermQuantFragment | ~200 行 | 精簡冗餘代碼 |
| MidTermQuantFragment | ~250 行 | DAG + HoldingGuard |
| LongTermQuantFragment | ~250 行 | DAG + DefensiveDividend |

### 8.4 剩餘注意事項

- `SimulationTradeEngine.kt` 保留：雖然執行邏輯是死碼，但 `TradeOrder`、`StrategyTradeOrderEntity`、相關 DAO 仍被 DAG 節點引用
- `AgentBase.kt` / `AgentTool.kt` 保留：`TradeExecutionAgent` 有 wildcard import 依賴
- `PipelineResult.kt` 精簡版保留：`DeepAnalystEngine` 引用其數據類

```
