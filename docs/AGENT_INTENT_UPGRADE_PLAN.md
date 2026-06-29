# Agent 意圖理解與執行框架升級計劃

> 目標：讓 Agent 能真正理解用戶意圖（如「分析兆易創新」），動態規劃執行步驟，而非依賴硬編碼正則匹配。

---

## 現狀分析（已解決）

### 問題根因

1. **無本地 name→code 反向查找** — ✅ 已通過 Trie 樹解決
2. **正則提取不穩定** — ✅ 已通過 StockEntityExtractor 三層級聯解決
3. **無歧義消解** — ✅ 已實現歧義檢測 + 確認卡片
4. **無真正 Function Calling** — ✅ 已實現原生 tools 參數支援
5. **對話和 Pipeline 割裂** — ✅ ChatAgent 已整合子 Agent 調度
6. **Plan-and-Execute 多次 LLM 調用超時** — ✅ 已重寫為並行數據 + 單次 LLM

---

## 升級總覽

```
階段一(P0)：本地詞典引擎  ✅ 已完成
    ↓
階段二(P1)：歧義消解 + 確認卡片  ✅ 已完成
    ↓
階段三(P2)：原生 Function Calling  ✅ 已完成
    ↓
階段四(P3)：指數 K 線分析 + 架構優化  ✅ 已完成
```

---

## 階段一(P0)：本地詞典引擎 — ✅ 已完成

**目標**：用戶輸入任何 A 股名稱/簡稱/拼音，<10ms 內返回股票代碼。

### 實現文件

| 文件 | 路徑 | 說明 |
|------|------|------|
| `StockNameTrie.kt` | `stock/data/StockNameTrie.kt` | Trie 樹股票名稱索引，雙檢查鎖構建 |
| `StockEntityExtractor.kt` | `ai/StockEntityExtractor.kt` | 實體提取引擎，三層級聯 + FALLBACK 映射 |
| `TrieStockNameHandler.kt` | `stock/intent/handlers/TrieStockNameHandler.kt` | Trie 匹配 Handler |
| `IntentPredictionEngine.kt` | `ai/IntentPredictionEngine.kt` | 步驟 5.5 插入本地詞典匹配 |
| `ChatAgent.kt` | `agent/chat/ChatAgent.kt` | detectIntent() 使用 StockEntityExtractor |
| `IntentProcessorChain.kt` | `stock/intent/IntentProcessorChain.kt` | 插入 TrieStockNameHandler |
| `MainActivity.kt` | `ui/MainActivity.kt` | 啟動時觸發 Trie 構建 |

### 核心邏輯

```
用戶輸入 "分析兆易創新"
  │
  ├─ 剝離查詢詞 → "兆易創新"
  ├─ StockEntityExtractor.extractSync()
  │   ├─ L1: 精確代碼匹配（6位數字）→ 無
  │   ├─ L2: Trie 詞典匹配 → 命中 "兆易創新" / 603986 / EXACT_NAME
  │   └─ 返回 StockQuery(code=603986, name=兆易創新, confidence=0.95)
  │
  └─ ChatAgent.detectIntent() → STOCK_ANALYSIS
```

### FALLBACK 降級機制

當 Trie 未構建時（首次安裝或構建失敗），`FALLBACK_STOCK_MAP` 硬編碼 40+ 隻常見龍頭股，確保核心股票可被識別。

---

## 階段二(P1)：歧義消解 + 確認卡片 — ✅ 已完成

**目標**：匹配到多個候選時，彈出 UI 確認卡片讓用戶選擇。

### 實現文件

| 文件 | 路徑 | 說明 |
|------|------|------|
| `EntityConfirmCard.kt` | `ui/EntityConfirmCard.kt` | 確認卡片 View（LinearLayout 子類） |
| `Message.kt` | `ui/Message.kt` | 增加 `ambiguousEntities` / `onEntityConfirm` / `onEntityCancel` |
| `ChatAdapter.kt` | `ui/ChatAdapter.kt` | 渲染確認卡片，處理選擇回調 |
| `ChatAgent.kt` | `agent/chat/ChatAgent.kt` | 歧義流程：entities.size > 1 時返回歧義結果 |

### 歧義流程

```kotlin
// ChatAgent.handleMessage() — STOCK_ANALYSIS 分支
val entities = StockEntityExtractor.extractSync(userMessage)
when {
    entities.size == 1 → 直接調用 analysisAgent.analyze(code)
    entities.size > 1  → 返回 ChatAgentResult(ambiguousEntities = entities)
                        → UI 顯示 EntityConfirmCard
                        → 用戶選擇後重新觸發分析
    else               → "請提供具體的股票代碼或名稱"
}
```

---

## 階段三(P2)：原生 Function Calling — ✅ 已完成

**目標**：讓 LLM 自動決定調用哪個工具並返回結構化參數。

### 實現文件

| 文件 | 路徑 | 說明 |
|------|------|------|
| `ChatTools.kt` | `ai/ChatTools.kt` | Tool 定義（stock_query / sector_query / market_brief） |
| `OpenAiCompatibleProvider.kt` | `OpenAiCompatibleProvider.kt` | `sendMessageStreamWithTools()` 擴展方法 |
| `AgentBase.kt` | `agent/framework/AgentBase.kt` | `react()` 支援 Function Calling 模式 |
| `PlanAgent.kt` | `agent/chat/PlanAgent.kt` | 規劃 Agent，生成執行計劃 |

### API 設計

```kotlin
// 接口方法（向後兼容，5 參數）
override fun sendMessageStream(
    messages, systemPrompt, onSuccess, onComplete, onError
)

// 擴展方法（帶 Function Calling，8 參數）
fun sendMessageStreamWithTools(
    messages, systemPrompt, onSuccess, onComplete, onError,
    tools, toolChoice, onToolCalls
)
```

---

## 階段四(P3)：指數 K 線分析 + 架構優化 — ✅ 已完成

### 3.1 指數 K 線分析

**目標**：用戶問「上證指數怎麼樣」「大盤上周漲了4天，周五大跌」時，AI 基於 K 線數據給出技術面預判。

#### 實現文件

| 文件 | 路徑 | 說明 |
|------|------|------|
| `IndexKlineAnalyzer.kt` | `stock/analysis/IndexKlineAnalyzer.kt` | K 線形態計算（連漲天數、MA5/10/20、量能、波動率） |
| `IndexAnalysisAgent.kt` | `agent/chat/IndexAnalysisAgent.kt` | 構建 Prompt → 調用 LLM → 解析結果 |
| `ChatAgent.kt` | `agent/chat/ChatAgent.kt` | `INDEX_ANALYSIS` 意圖分支 + `extractIndexInfo()` |

#### K 線形態指標

```kotlin
data class KlineAnalysis(
    val indexCode: String,
    val indexName: String,
    val latestClose: Double,
    val latestChangePct: Double,
    val consecutiveUpDays: Int,    // 連漲天數
    val consecutiveDownDays: Int,  // 連跌天數
    val totalUpDays: Int,          // 上漲天數
    val totalDownDays: Int,        // 下跌天數
    val ma5: Double, val ma10: Double, val ma20: Double,
    val aboveMa5: Boolean, val aboveMa10: Boolean, val aboveMa20: Boolean,
    val latestVolumeRatio: Double, // 最新量能 / 均量
    val maxHighInPeriod: Double, val minLowInPeriod: Double,
    val volatility: Double,        // 區間波動率
    val trend: String,             // 趨勢判定
    val keyEvents: List<String>    // 關鍵事件
)
```

### 3.2 StockAnalysisAgent 架構重寫

**原問題**：`planAndExecute()` 需要多次 LLM 調用（plan → execute steps → summary），推理模型慢導致 25s 超時。

**新架構**：並行收集三維度數據 → 單次 LLM 調用 → 解析 JSON

```kotlin
suspend fun analyze(stockCode: String): StockAnalysisResult {
    // Step 1: 並行收集（全部本地 DB，毫秒級）
    val technical = async { TechnicalAnalysisTool.execute(...) }
    val fundamental = async { FundamentalAnalysisTool.execute(...) }
    val fundFlow = async { FundFlowTool.execute(...) }

    // Step 2: 單次 LLM 調用，60s 超時
    val llmOutput = withTimeout(60_000) {
        suspendCancellableCoroutine<String> { cont ->
            cont.invokeOnCancellation { slot.provider.cancel() }
            slot.provider.sendMessageStream(...)
        }
    }

    // Step 3: 解析 JSON 結果
    return parseJsonResult(llmOutput)
}
```

### 3.3 OpenAiCompatibleProvider 推理模型適配

**問題**：`doubao-seed-2-0-pro` 等推理模型把輸出放在 `delta.reasoning_content`，`delta.content` 為空。

**修復**：
```kotlin
val content = delta.optString("content", "")
val reasoningContent = delta.optString("reasoning_content", "")
val textToAppend = content.ifBlank { reasoningContent }
if (textToAppend.isNotEmpty()) {
    sb.append(textToAppend)
    onSuccess(textToAppend)
}
```

### 3.4 超時與取消機制

| 組件 | 原超時 | 新超時 | 取消機制 |
|------|--------|--------|----------|
| `AgentBase.callLLM()` | 25,000ms | 25,000ms | 無 |
| `StockAnalysisAgent.analyze()` | 無（planAndExecute 累積超時） | 60,000ms | `invokeOnCancellation { provider.cancel() }` |
| `IndexAnalysisAgent.analyze()` | 無 | 60,000ms | `invokeOnCancellation { provider.cancel() }` |

### 3.5 NPE 修復

**問題**：`choice.optString("finish_reason", null).ifBlank { null }` 在 `null` 上調用 `isBlank()` 拋 NPE。

**修復**：`choice.optString("finish_reason", "").ifBlank { null }`

---

## 意圖處理流程（最終版）

```
用戶輸入 "分析兆易創新"
  │
  ├─ IntentPredictionEngine（打字時預判，300ms 防抖）
  │   ├─ 步驟 5.5: StockEntityExtractor.extractSync()
  │   │   └─ Trie 命中 → StockQuery(code=sh603986, name=兆易創新, confidence=0.95)
  │   └─ 返回 UserIntent.STOCK_ANALYSIS
  │
  └─ ChatAgent.handleMessage()
        ├─ detectIntent() → STOCK_ANALYSIS
        ├─ extractStockCode() → "sh603986"（通過 StockEntityExtractor）
        ├─ analysisAgent.analyze("sh603986")
        │   ├─ 並行：TechnicalAnalysisTool + FundamentalAnalysisTool + FundFlowTool
        │   ├─ 單次 LLM 調用（60s 超時，支援 reasoning_content）
        │   └─ 解析 JSON → StockAnalysisResult
        └─ formatAnalysisResponse() → 自然語言回覆
```

---

## 驗收標準

### P0 本地詞典
- [x] 「兆易創新」→ 正確解析為 603986
- [x] 「茅台」→ 正確解析為 600519
- [x] 「zycx」→ 正確解析為 603986
- [x] 「分析兆易創新」→ STOCK_ANALYSIS + code=603986
- [x] 「兆易創新和韋爾股份」→ 兩只股票都比對
- [x] 本地匹配 < 10ms

### P1 歧義消解
- [x] 多匹配時返回歧義實體列表
- [x] UI 顯示確認卡片
- [x] 用戶選擇後觸發對應分析

### P2 Function Calling
- [x] `sendMessageStreamWithTools()` 擴展方法
- [x] `AgentBase.react()` 支援 tools 模式
- [x] 向後兼容（不支援 tools 的 Provider 走普通模式）

### P3 指數分析 + 架構優化
- [x] 「上證指數怎麼樣」→ 觸發 IndexAnalysisAgent
- [x] K 線形態計算（連漲/連跌/MA/量能/波動率）
- [x] AI 給出技術面觀點 + 短線預判 + 風險提示
- [x] StockAnalysisAgent 單次 LLM 調用成功（不再超時）
- [x] 推理模型（doubao-seed）輸出正常（reasoning_content fallback）
- [x] 協程取消時主動切斷網路請求
