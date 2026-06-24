# StockAnalysis 項目變更日誌 v7

> 日期: 2026-06-24
> 本次變更涵蓋備選池、Agent 流水線重構、UI 優化、共用交易類、股票導入優化、共用基類等多個模塊

---

## 🆕 v14.0 AI 驅動熱門板塊查詢（2026-06-24）

### 核心變更
**熱門板塊判斷方式徹底改為 AI 驅動**，不再依賴 ETF 漲跌或東方財富 compositeScore。

### 之前 vs 現在
| | 之前 | 現在 |
|--|------|------|
| 年度熱門板塊 | ETF 漲跌計算 | **AI 直接查詢** |
| 月度熱門板塊 | 東方財富 compositeScore | **AI 直接查詢** |
| 周度熱門板塊 | 無（僅靠運氣） | **AI 直接查詢** |
| 昨日熱門板塊 | ETF 數據 | **AI 直接查詢** |

### 新增 `AIHotSectorProvider`
```kotlin
object AIHotSectorProvider {
    // 取得熱門板塊（優先緩存，3 小時過期）
    suspend fun getHotSectors(context: Context): HotSectorResult

    // 強制刷新
    suspend fun forceRefresh(context: Context): HotSectorResult

    // 檢查緩存是否過期
    fun isCacheExpired(context: Context): Boolean
}

data class HotSectorResult(
    val annualSectors: List<String>,    // 年度熱門板塊 ~15個
    val monthlySectors: List<String>,   // 月度熱門板塊 ~15個
    val weeklySectors: List<String>,    // 周度熱門板塊 ~15個
    val yesterdaySectors: List<String>  // 昨日熱門板塊 ~15個
) {
    val allSectors: List<String>  // 合併去重 ~40-60個
}
```

### AI 查詢流程
```
1. 構造 Prompt（中文，要求嚴格 JSON 輸出）
2. 調用 DeepSeek/豆包 API（優先當前選中的 Provider）
3. 解析 JSON（含 Markdown 代碼塊處理 + 正則 fallback）
4. 寫入 SharedPreferences 緩存（3 小時）
5. AI 失敗時自動 fallback 到預設 40 個科技熱門板塊
```

### `CandidatePool` v2.0 更新
| | v1.0 | v2.0 |
|--|------|------|
| 熱門板塊來源 | ETF + 東方財富 | **AIHotSectorProvider** |
| 龍頭股獲取 | ETF/東方財富 API | 東方財富 sectorLeaders + DB sectorStockDao |
| 備選池大小 | ~100-200 | ~100-200（不變） |
| 緩存策略 | 跨天 | 跨天 + AI 3小時緩存 |

### `HotSectorStockPool` v2.0
- `build()` 新增 `aiSectorNames: Set<String>` 參數
- 優先使用 AI 提供的板塊名稱
- AI 未提供時 fallback 到東方財富（無感切換）

### `SimulationTradeEngine` 更新
- `getHotSectorStockPool()` 先調用 `AIHotSectorProvider.getHotSectors()` 獲取板塊名稱
- 再傳給 `HotSectorStockPool.build(context, aiSectorNames)`

### 新增/修改檔案
| 檔案 | 類型 | 說明 |
|------|------|------|
| `strategy/data/AIHotSectorProvider.kt` | **新建** | AI 熱門板塊查詢核心（~290行） |
| `strategy/data/CandidatePool.kt` | **重寫** | v2.0 AI 驅動備選池 |
| `strategy/trade/HotSectorStockPool.kt` | **修改** | 支援 AI 板塊名稱參數 |
| `strategy/trade/SimulationTradeEngine.kt` | **修改** | 銜接 AI 板塊查詢 |

---

## 一、備選池 (CandidatePool) — 全新公共類

### 1.1 設計目標
- 通過 ETF 漲跌判斷熱門板塊，只掃描備選池（~200 只核心股），而非全部 A 股 5000+ 只
- 大幅提升策略執行速度

### 1.2 備選池組成
| 來源 | 數量 | 說明 |
|------|------|------|
| 核心龍頭股 | ~81 只 | `LeaderStockPool` 靜態配置 |
| ETF 熱門板塊龍頭 | ~45 只 | 根據 ETF 資金流向動態發現 |
| 東方財富熱門板塊龍頭 | ~30 只 | 根據東方財富熱門板塊動態發現 |
| **總計** | **~100-200 只** | 去重後，主板為主 |

### 1.3 公共 API
```kotlin
// 獲取當前備選池（優先緩存，過期則刷新）
suspend fun getPool(context: Context, forceRefresh: Boolean = false): PoolSnapshot

// 強制刷新備選池
suspend fun refreshPool(context: Context): PoolSnapshot

// 獲取備選池股票代碼列表（用於策略掃描）
suspend fun getPoolCodes(context: Context): List<String>

// 獲取熱門板塊列表
suspend fun getHotSectors(context: Context): List<String>

// 檢查是否需要更新
fun needsUpdate(context: Context): Boolean
```

### 1.4 備選池名稱修復
名稱獲取優先級：
1. `DailySnapshotEntity.name`（日快照）
2. `StockBasicEntity.name`（數據庫緩存）
3. 代碼本身（兜底）

### 1.5 備選池漲跌修復
- 問題：`changePct` 大部分為 0，因為使用當天日期查詢但數據庫沒有當天快照
- 修復：查詢最近 5 個可用交易日，選擇最新的那個作為目標日期

### 1.6 備選池 UI 重構
- 移除分組標題（「核心龍頭」「ETF熱門板塊」「其他」），改為統一列表按漲跌幅排序
- 添加「僅主板」Switch，默認開啟
- 統計信息改為「主板 X 只 / 科創/創業 Y 只」
- 移除市值顯示（`CandidateStock` 沒有市值字段）

### 1.7 相關文件
- **新建**: `app/src/main/java/com/chin/stockanalysis/strategy/data/CandidatePool.kt`
- **新建**: `app/src/main/java/com/chin/stockanalysis/ui/CandidatePoolFragment.kt`

---

## 二、Agent 流水線重構 v2.0（參考豆包思路）

### 2.1 三種分析模式

| 模式 | 智能體流程 | 權重公式 | 適用場景 |
|------|-----------|---------|---------|
| **六智體通用** | A1→A2(賽道)→A3(技術)→A4(競爭格局)→A5(風控) + 並行D | A1×0.2+A2×0.2+A4×0.2+A3×0.3+A5×0.1 | 消費、醫藥、周期等普通公司 |
| **七智體賣水人** | A1→A2(賣水人)→A3(賽道)→A4(競爭格局)→A5(技術)→A6(風控) + 並行D | A1×0.2+A2×0.2+A3×0.2+A5×0.3+A6×0.1 | 光通信、半導體等上下游清晰賽道 |
| **精簡版（默認）** | A1→A2(賣水人)→A3(賽道)→A4(技術)→A5(風控) + 並行D | A1×0.2+A2×0.2+A3×0.2+A4×0.3+A5×0.1 | APP 默認，5+1 常態化 |

### 2.2 AI 動態選擇模式
```
用戶輸入標的
    │
    ├── 本地快速判斷（賣水人關鍵詞匹配）
    │   ├── 匹配 → 精簡版
    │   └── 不匹配 ↓
    │
    └── AI 判斷（調用 LLM 分析標的所屬賽道）
        ├── 消費/醫藥/周期 → 六智體通用
        ├── 光通信/半導體/鋰電 → 七智體賣水人
        └── 其他 → 精簡版
```

### 2.3 全局統一規則
- 綜合總分 = clamp(基礎分 + 板塊加分(0~+8) + 新聞因子(-10~+5), 0, 100)
- 支線（Agent D）與主線第一步同步並行發起，不阻塞主線
- 串行下一級必須攜帶前面所有智能體完整輸出作為入參

### 2.4 豆包風格輸出格式
每個智能體輸出遵循統一模板：
1. 基礎行情總覽（表格）
2. 智能體 1：基本面分析
3. 智能體 2：賣水人/賽道分析
4. 智能體 3：技術面/賽道熱度
5. 智能體 4：競爭格局/技術面
6. 智能體 5/6：風控終審
7. 智能體 D：板塊&輿情評分
8. 綜合總評

### 2.5 相關文件
- **修改**: `app/src/main/java/com/chin/stockanalysis/agent/pipeline/AgentPipelineOrchestrator.kt`
- **修改**: `app/src/main/java/com/chin/stockanalysis/agent/pipeline/PipelineResult.kt`
- **修改**: `app/src/main/java/com/chin/stockanalysis/agent/pipeline/ui/PipelineProgressView.kt`

---

## 三、短線量化 UI 重構

### 3.1 按鈕佈局變更

**之前**（兩行）：
```
第一行：短線選股 | 擬合 | 建倉 | 賣出 | 數據 | 清除
第二行：AI 智能體分析篩選
```

**現在**（一行 6 個按鈕）：
```
🧠 Agent分析 | ▶ 建倉 | 🔧 擬合 | 💰 賣出 | 🗄️ 數據 | 🗑️ 清除
```

### 3.2 建倉智能分流邏輯
```
用戶點擊「建倉」
    │
    ├── 有 Agent 分析結果？
    │   ├── YES → 從 Agent 結果中買入合適買點的股票
    │   │         + 自動騰龍換鳥分析
    │   │
    │   └── NO  → 執行 zipline 全流程
    │             (量化策略篩選 + 建倉 + 騰龍換鳥)
```

### 3.3 zipline 選股後自動建倉
`runPipeline()` 完成後自動觸發 `buyAiPicksInternal()` + `analyzeSwapCandidates()`

### 3.4 相關文件
- **修改**: `app/src/main/java/com/chin/stockanalysis/strategy/trade/AutoQuantFragment.kt`

---

## 四、共用交易類 TradeDecisionHelper

### 4.1 設計目標
短線量化和中線量化共用的賣出/買入判斷邏輯

### 4.2 API
```kotlin
object TradeDecisionHelper {
    // 判斷是否應該賣出
    fun shouldSell(stockCode: String, buyPrice: Double, currentPrice: Double, holdDays: Int): String?
    
    // 判斷是否應該買入
    fun shouldBuy(stockCode: String, currentPrice: Double, changePct: Double, volumeRatio: Double, score: Float): String?
    
    // 騰龍換鳥判斷
    fun shouldSwap(weakestScore: Int, strongestScore: Float, weakestName: String, strongestName: String): String?
}
```

### 4.3 賣出規則
| 條件 | 操作 |
|------|------|
| 虧損超過 8% | 止損 |
| 盈利超過 15% | 止盈 |
| 持有超過 5 天且盈利 < 3% | 超時賣出 |

### 4.4 相關文件
- **新建**: `app/src/main/java/com/chin/stockanalysis/strategy/trade/TradeDecisionHelper.kt`

---

## 五、StockDataCenter 增強

### 5.1 新增方法
```kotlin
// 根據股票代碼查詢名稱 + 最近交易日股價
data class StockQuote(val code: String, val name: String, val price: Double, val changePct: Double, val date: String)
suspend fun getStockQuote(stockCode: String): StockQuote?

// 批量查詢股票名稱
suspend fun getStockNames(codes: Collection<String>): Map<String, String>

// 簡化版名稱查詢
suspend fun getStockName(stockCode: String): String
```

### 5.2 數據來源優先級
1. `DailySnapshotEntity`（日快照，含股價和漲幅）
2. `StockBasicEntity`（數據庫緩存，僅名稱）
3. 返回代碼本身（兜底）

### 5.3 相關文件
- **修改**: `app/src/main/java/com/chin/stockanalysis/stock/database/StockDataCenter.kt`

---

## 六、AI 對話框優化

### 6.1 圖片/文件上傳修復
- **問題**：上傳圖片後顯示「系統正在獲取實時行情數據」
- **原因**：`sendMessage` → `smartContext.getOrBuild()` 嘗試從圖片文字中提取股票信息
- **修復**：圖片/文件輸入使用 `skipStockContext = true`，跳過股票上下文獲取

### 6.2 移除固定追問
- **問題**：追問都是固定的模板，有時連續顯示兩次
- **修復**：移除 `backgroundPredictor.predictAfterConversation`，讓 AI 自然生成後續問題

### 6.3 字體大小調整
| 元素 | 之前 | 現在 |
|------|------|------|
| AI 消息 (`tvBotMessage`) | 15sp | **13sp** |
| 用戶消息 (`tvUserMessage`) | 15sp | **13sp** |

### 6.4 背景色差修復
- `item_message.xml` 背景從 `#FFFFFF` 改為 `@android:color/transparent`

### 6.5 移除模式切換 Toast
- 刪除 `Toast.makeText(requireContext(), modeHint, ...).show()`

### 6.6 修復空的 streaming view 閃現
- RecyclerView 禁用 `ItemAnimator`，避免 loading view 閃現

### 6.7 相關文件
- **修改**: `app/src/main/java/com/chin/stockanalysis/ui/ChatTabFragment.kt`
- **修改**: `app/src/main/res/layout/item_message.xml`

---

## 七、股票導入優化（StockDatabaseManager）

### 7.1 之前 vs 現在
| | 之前 | 現在 |
|--|------|------|
| 導入範圍 | ThemeStockLibrary 全部股票 | 核心股 + 熱門板塊龍頭 |
| 數量 | 無限制（可能數千只） | 200-500 只（無硬性上限） |
| 過濾 | 無 | 去掉 ST + 異常名稱 + 非標準代碼 |

### 7.2 選股流程
1. **核心龍頭股** — `LeaderStockPool.ALL_LEADER_CODES`（~81 只）
2. **年度/月度/周熱門板塊** — 從 `ThemeStockLibrary` 選取 10 個熱門板塊，每個取前 10
3. **ETF 漲跌動態發現** — 查詢最近交易日 ETF 數據，漲幅前 5 的 ETF 對應板塊加入
4. **子板塊前 10** — 通過 `LeaderStockPool.SECTOR_CONFIGS` 查找子板塊龍頭

### 7.3 過濾條件
- 去掉 ST 股票（名稱包含 "ST"）
- 去掉「退市」「摘牌」股票
- 去掉非標準 6 位數字代碼
- 市值 < 100 億（TODO，等數據庫有市值字段後啟用）

### 7.4 同步時機
- App 啟動時自動執行（`ensureInitialized`）
- 每 24 小時增量同步
- 自動移除不再符合條件的股票

### 7.5 相關文件
- **修改**: `app/src/main/java/com/chin/stockanalysis/stock/database/StockDatabaseManager.kt`

---

## 八、策略執行優化

### 8.1 之前 vs 現在
| | 之前 | 現在 |
|--|------|------|
| 掃描範圍 | 全部 A 股 5000+ 只 | 備選池 ~200 只 |
| 速度 | 慢（需數分鐘） | 快（秒級） |

### 8.2 修改點
- `AutoQuantFragment.runPipeline()` — 使用備選池
- `SimulationTradeEngine.buildDailyStockPool()` — 使用備選池

### 8.3 策略股票來源確認
| 模塊 | 股票來源 | 使用備選池？ |
|------|---------|------------|
| 短線量化 (`AutoQuantFragment`) | `CandidatePool.getPoolCodes()` | ✅ |
| 中線量化 (`SimulationTradeEngine`) | `CandidatePool.getPool()` | ✅ |
| 量化選股 (`StrategyListFragment`) | 當日快照 / 全市場掃描 | ❌（預期行為） |

### 8.4 相關文件
- **修改**: `app/src/main/java/com/chin/stockanalysis/strategy/trade/AutoQuantFragment.kt`
- **修改**: `app/src/main/java/com/chin/stockanalysis/strategy/trade/SimulationTradeEngine.kt`

---

## 九、共用基類 QuantFragmentBase

### 9.1 設計目標
中線量化和短線量化共用基類，整合擬合/回調/數據/賣出/清除等共用功能

### 9.2 提供的共用功能

| 功能 | 方法 |
|------|------|
| **UI 組件** | `statusTv`, `progressBar`, `positionContainer`, `clearBtn`, `rootLayout` |
| **按鈕行** | `createButtonRow()` — 擬合/回調/數據/賣出/清除 |
| **賣出評估** | `runAutoSellEvaluation()` — 使用 `AutoSellEngine` 10 維度評估 |
| **執行賣出** | `executeAutoSell()` — 執行緩存的賣出決策 |
| **賣出對話框** | `showSellDecisionsDialog()` — 顯示賣出信號和持有建議 |
| **賣出績效** | `showSellPerformance()` — 90 天歷史績效統計 |
| **數據菜單** | `showDataMenu()` — 查看交易記錄/持倉詳情/導出 |
| **清除數據** | `clearData()`, `clearDataByDate()` — 按類型過濾清除 |
| **刷新持倉** | `refreshPositions()` — 自動過濾當前量化類型 |

### 9.3 抽象方法（子類實現）

| 方法 | 說明 |
|------|------|
| `getQuantType()` | 返回 `"ShortTermQuant"` 或 `"MidTermQuant"` |
| `onFittingClick()` | 擬合按鈕點擊 |
| `onBacktrackClick()` | 回調按鈕點擊 |
| `onClearClick()` | 清除按鈕點擊 |
| `loadPositions()` | 加載持倉 |
| `buildUI()` | 構建 UI |

### 9.4 賣出策略（10 維度，AutoSellEngine）

| # | 策略 | 觸發條件 | 緊急度 |
|---|------|---------|--------|
| 1 | 硬止損 | 虧損 > 8% | 10 |
| 2 | 最大回撤止損 | 從高點回撤 > 12% | 9 |
| 3 | 時間無進展 | 持有 > 10 天且近 3 日動量 < 1% | 7 |
| 4 | 階梯止盈 | +10% 賣 1/3, +15% 賣 1/3, +20% 賣剩餘 | 5 |
| 5 | 吊燈止損 | 跌破最高價 - 3×ATR | 8 |
| 6 | 移動止盈 | 盈利 > 8% 後回撤超盈利 50% | 6 |
| 7 | MA 死叉 | 5 日線下穿 20 日線 | 7 |
| 8 | 放量滯漲 | 成交量 > 2 倍均量且漲幅 < 1% | 6 |
| 9 | RSI 超買反轉 | RSI > 75 且連續 2 日下降 | 5 |
| 10 | 板塊弱勢 | 所屬板塊跌幅 > 2% | 5 |

### 9.5 相關文件
- **新建**: `app/src/main/java/com/chin/stockanalysis/strategy/trade/QuantFragmentBase.kt`

---

## 十、文件變更清單

### 新建文件
| 文件 | 說明 |
|------|------|
| `strategy/data/CandidatePool.kt` | 備選池公共類 |
| `ui/CandidatePoolFragment.kt` | 備選池 UI Fragment |
| `strategy/trade/TradeDecisionHelper.kt` | 共用交易決策類 |
| `strategy/trade/QuantFragmentBase.kt` | 中線/短線量化共用基類 |

### 修改文件
| 文件 | 變更內容 |
|------|---------|
| `agent/pipeline/AgentPipelineOrchestrator.kt` | 三種模式 + AI 動態選擇 + 豆包輸出格式 |
| `agent/pipeline/PipelineResult.kt` | 新增 analysisModeName / weightFormula |
| `agent/pipeline/ui/PipelineProgressView.kt` | 適配新步驟列表 API |
| `stock/database/StockDataCenter.kt` | 新增 getStockQuote / getStockNames / getStockName |
| `stock/database/StockDatabaseManager.kt` | 導入優化：核心股 + 熱門板塊 + ETF 動態 + 過濾 |
| `ui/ChatTabFragment.kt` | 圖片上傳修復、移除追問、移除 Toast、禁用動畫 |
| `res/layout/item_message.xml` | 背景透明、字體 13sp |
| `strategy/trade/AutoQuantFragment.kt` | UI 重構、建倉分流、使用備選池、Agent 分析 |
| `strategy/trade/SimulationTradeEngine.kt` | 使用備選池 |
| `strategy/data/CandidatePool.kt` | 名稱修復、漲跌修復 |
| `ui/CandidatePoolFragment.kt` | UI 重構、僅主板開關 |

---

## 十一、編譯狀態

✅ **BUILD SUCCESSFUL** — 所有修改已通過 Kotlin 編譯驗證
