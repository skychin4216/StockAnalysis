# Agent 意圖理解與 Plan-and-Execute 升級計劃

> 目標：讓 Agent 能真正理解用戶意圖（如「分析兆易創新」），動態規劃執行步驟，而非依賴硬編碼正則匹配。

## 現狀分析

### 當前意圖處理鏈

```
用戶輸入 "分析兆易創新"
  │
  ├─ IntentPredictionEngine（打字時預判，300ms 防抖）
  │   └─ 步驟8 匹配到「分析」→ StockQuery(code=null, name=null) ← ❌ 這裡失敗
  │
  └─ ChatAgent.handleMessage()
        ├─ detectIntent() — 硬編碼關鍵詞匹配
        ├─ GENERAL_CHAT → AgentBase.react() (ReAct)
        │   └─ LLM 收到 prompt 但無股票數據 → 返回「請提供具體的代碼」← ❌ 無法解析
```

### 現有基礎設施（可復用）

| 組件 | 能力 | 限制 |
|------|------|------|
| `IntentProcessorChain` | StockCodeHandler → IndexHandler → StockNameHandler → **FuzzyStockNameHandler** → GeneralStockHandler → AiIntentHandler | FuzzyStockNameHandler 需要網路調用東方財富 API，3s 超時 |
| `StockNameResolver` | 代碼→名稱（反向查找）| 只有 code→name，沒有 name→code |
| `PinyinUtils` | 拼音首字母/全拼匹配 | 未集成到意圖鏈 |
| `StockDatabaseManager` | 本地 5000+ 股票庫 | 未用於意圖解析 |
| `AgentBase.react()` | ReAct 模式（JSON 約定） | 非原生 Function Calling，依賴 LLM 輸出格式穩定性 |
| `OpenAiCompatibleProvider` | SSE 串流 LLM 調用 | 未支援 tools 參數 |
| `AgentPipelineOrchestrator` | 多智體流水線（A1→A2→...→A5+D） | 僅用於「精選」Tab，未接入對話 |

### 核心問題

1. **無本地 name→code 反向查找** — 5000+ 股票在本地 DB，卻不用于意圖解析
2. **正則提取不穩定** — `[\u4e00-\u9fff]{2,6}` 貪婪匹配，中文詞邊界模糊
3. **無歧義消解** — 匹配失敗直接返回「請提供代碼」，不會跟用戶確認
4. **無真正 Function Calling** — LLM 無法結構化調用工具，依賴 prompt 約定
5. **對話和 Pipeline 割裂** — 對話中的股票分析不觸發 Pipeline

---

## 升級總覽：四階段

```
階段一：本地詞典引擎（L2）  ← 優先實現，立即解決「兆易創新」問題
    ↓
階段二：歧義消解 + 確認卡片
    ↓
階段三：原生 Function Calling
    ↓
階段四：Plan-and-Execute 對話整合
```

---

## 階段一：本地詞典引擎（L2 StockNameLookup）

**目標**：用戶輸入任何 A 股名稱/簡稱/拼音，<10ms 內返回股票代碼。

### 1.1 新增 `StockNameTrie.kt`

**路徑**: `stock/data/StockNameTrie.kt`

基於 Trie 樹的本地股票名稱→代碼反向索引。

```
核心數據結構：
- TrieNode: children(Map<Char, TrieNode>), codes(List<String>)
- 構建：從 StockDatabaseManager 讀取全部股票（code + name），插入 Trie
- 查詢： traverse(input) → 收集所有匹配的 (name, code) 對
- 支持最大前綴匹配和子串匹配
```

**查詢邏輯**：
```
輸入 "兆易創新" → Trie 精確匹配 → [(兆易創新, 603986)]
輸入 "兆易" → Trie 前綴匹配 → [(兆易創新, 603986)]
輸入 "zycx" → 拼音轉換 → Trie 匹配 "兆易創新" 的拼音 → [(兆易創新, 603986)]
輸入 "茅台" → Trie 精確匹配 → [(貴州茅台, 600519)]
```

### 1.2 新增 `StockEntityExtractor.kt`

**路徑**: `ai/StockEntityExtractor.kt`

從自然語言中提取股票實體的引擎。

```kotlin
data class ExtractedEntity(
    val text: String,       // 原文匹配片段，如 "兆易創新"
    val code: String,       // 股票代碼，如 "603986"
    val name: String,       // 標準名稱，如 "兆易創新"
    val matchType: MatchType,
    val confidence: Float
)

enum class MatchType {
    EXACT_CODE,     // 精確代碼匹配: "600519"
    PREFIX_CODE,    // 帶前綴代碼: "sh600519"
    EXACT_NAME,     // 精確名稱匹配: "茅台" → "貴州茅台"
    PREFIX_NAME,    // 前綴名稱匹配: "兆易" → "兆易創新"
    PINYIN_ABBR,    // 拼音縮寫: "zycx" → "兆易創新"
    FUZZY_API       // 網路模糊匹配: FuzzyStockNameHandler
}

object StockEntityExtractor {
    // 從用戶輸入中提取所有股票實體
    fun extract(input: String): List<ExtractedEntity>
}
```

**提取流程**：
```
1. 剝離查詢詞（分析/走势/行情/幫我/查看/多少錢等）
2. 按「和/跟/與/、」拆分多股票
3. 對每個片段：
   a. 精確代碼匹配（6位數字）→ MatchType.EXACT_CODE
   b. 帶前綴代碼匹配（sh/sz+6位數字）→ MatchType.PREFIX_CODE
   c. Trie 精確名稱匹配 → MatchType.EXACT_NAME
   d. Trie 前綴匹配 → MatchType.PREFIX_NAME
   e. 拼音縮寫匹配（PinyinUtils）→ MatchType.PINYIN_ABBR
   f. 全部失敗 → 調用 FuzzyStockNameHandler（已有）→ MatchType.FUZZY_API
```

### 1.3 改造 `IntentPredictionEngine`

在步驟 6（股票名稱提取）之前，先調用 `StockEntityExtractor`：

```kotlin
// 新步驟 5.5：本地詞典匹配
val entities = StockEntityExtractor.extract(t)
if (entities.isNotEmpty()) {
    val best = entities.first()
    return UserIntent.StockQuery(
        code = best.code,
        name = best.name,
        confidence = best.confidence
    )
}
```

這樣「分析兆易創新」→ 剝離「分析」→ `StockEntityExtractor.extract("兆易創新")` → Trie 匹配 → `StockQuery(code="603986", name="兆易創新", confidence=0.95)`

### 1.4 改造 `ChatAgent.detectIntent()`

替換硬編碼關鍵詞匹配為 `StockEntityExtractor`：

```kotlin
// 原來：
// if (input.contains("分析") || input.contains("怎麼樣")) → STOCK_ANALYSIS
//     然後正則提取代碼（經常失敗）

// 改為：
val entities = StockEntityExtractor.extract(input)
if (entities.isNotEmpty()) {
    return when {
        input.contains("分析") || input.contains("走勢") || input.contains("技術面")
            -> UserIntent.STOCK_ANALYSIS(entities.first().code)
        input.contains("買入") || input.contains("操作")
            -> UserIntent.TRADE_PLAN(entities.first().code)
        else -> UserIntent.STOCK_ANALYSIS(entities.first().code)
    }
}
```

### 1.5 改造 `IntentProcessorChain`

在 `StockNameHandler` 和 `FuzzyStockNameHandler` 之間插入 `TrieStockNameHandler`：

```
StockCodeHandler → IndexHandler → StockNameHandler → [TrieStockNameHandler] → FuzzyStockNameHandler → GeneralStockHandler
```

`TrieStockNameHandler` 使用 `StockEntityExtractor`，純本地、零延遲。

### 階段一交付物

| 文件 | 類型 | 說明 |
|------|------|------|
| `stock/data/StockNameTrie.kt` | 新增 | Trie 樹股票名稱索引 |
| `ai/StockEntityExtractor.kt` | 新增 | 實體提取引擎 |
| `ai/IntentPredictionEngine.kt` | 修改 | 步驟 5.5 插入本地詞典 |
| `agent/chat/ChatAgent.kt` | 修改 | detectIntent() 使用 StockEntityExtractor |
| `stock/intent/IntentProcessorChain.kt` | 修改 | 插入 TrieStockNameHandler |
| `stock/intent/handlers/TrieStockNameHandler.kt` | 新增 | Trie 匹配 Handler |

---

## 階段二：歧義消解 + 確認卡片

**目標**：匹配到多個候選時，彈出 UI 確認卡片讓用戶選擇。

### 2.1 歧義檢測

當 `StockEntityExtractor.extract()` 返回多個候選時：

```
輸入 "華為" → 匹配到：
  - 華為概念（89只成分股）板塊
  - 華為汽車（23只成分股）板塊
  - 華為鯨鴻（12只成分股）板塊
  - 華為海思（概念板塊）
```

### 2.2 確認卡片 UI

**路徑**: `ui/EntityConfirmCard.kt`

在 ChatFragment 的消息列表中插入確認卡片（類似微信的確認請求）：

```
┌─────────────────────────────┐
│  您要分析哪個？              │
│                              │
│  ☐ 華為概念（89只）          │
│  ☐ 華為汽車（23只）          │
│  ☐ 華為鯨鴻（12只）          │
│                              │
│  [全選] [取消]               │
└─────────────────────────────┘
```

### 2.3 ChatAgent 歧義流程

```kotlin
// ChatAgent.handleMessage()
val entities = StockEntityExtractor.extract(input)
when {
    entities.size == 1 → 直接執行分析
    entities.size > 1 → 發送確認卡片給 UI，等待用戶選擇
    entities.isEmpty() → 走 LLM 兜底
}
```

### 2.4 IntentPredictionEngine 歧義提示

預判意圖時，如果匹配到多個候選，顯示提示文字：

```
預判：找到 3 個匹配，請選擇 ▼
```

點擊展開候選列表。

### 階段二交付物

| 文件 | 類型 | 說明 |
|------|------|------|
| `ui/EntityConfirmCard.kt` | 新增 | 確認卡片 View |
| `ai/StockEntityExtractor.kt` | 修改 | 返回多候選 + 歧義標記 |
| `agent/chat/ChatAgent.kt` | 修改 | 歧義流程（發送確認→等待回調） |
| `ui/ChatTabFragment.kt` | 修改 | 渲染確認卡片，處理選擇回調 |

---

## 階段三：原生 Function Calling

**目標**：讓 LLM 自動決定調用哪個工具並返回結構化參數，取代現有的 JSON prompt 約定。

### 3.1 擴展 `OpenAiCompatibleProvider`

增加 `tools` 參數支援：

```kotlin
data class ToolDefinition(
    val type: String = "function",   // 目前固定 "function"
    val function: FunctionDef
)

data class FunctionDef(
    val name: String,                // "stock_query"
    val description: String,         // "查詢股票實時行情和基本面數據"
    val parameters: Map<String, Any>  // JSON Schema
)

// sendMessageStream 新增 tools 參數
suspend fun sendMessageStream(
    messages: List<Map<String, String>>,
    systemPrompt: String,
    onChunk: (String) -> Unit,
    tools: List<ToolDefinition>? = null,  // 新增
    toolChoice: String? = null             // "auto" / "required" / "none"
): ChatResponse
```

### 3.2 定義對話工具集

```kotlin
object ChatTools {
    val stockQuery = ToolDefinition(
        function = FunctionDef(
            name = "stock_query",
            description = "根據股票名稱或代碼查詢實時行情、基本面數據、技術指標",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "query" to mapOf(
                        "type" to "string",
                        "description" to "股票名稱（如'兆易創新'）或代碼（如'603986'或'sh603986'）"
                    ),
                    "includeFundamentals" to mapOf(
                        "type" to "boolean",
                        "description" to "是否包含基本面數據（PE/PB/ROE/營收等），默認 true"
                    ),
                    "includeTechnical" to mapOf(
                        "type" to "boolean",
                        "description" to "是否包含技術指標（均線/MACD/KDJ），默認 false"
                    )
                ),
                "required" to listOf("query")
            )
        )
    )

    val sectorQuery = ToolDefinition(
        function = FunctionDef(
            name = "sector_query",
            description = "查詢板塊/行業/概念的熱門程度、成分股、資金流向",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "sectorName" to mapOf(
                        "type" to "string",
                        "description" to "板塊名稱（如'光通信'、'半導體'、'華為概念'）"
                    )
                ),
                "required" to listOf("sectorName")
            )
        )
    )

    val marketBrief = ToolDefinition(
        function = FunctionDef(
            name = "market_brief",
            description = "獲取今日 A 股市場總覽（大盤指數、漲跌停數、熱門板塊、北向資金）",
            parameters = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        )
    )

    // 更多工具...
    val allTools = listOf(stockQuery, sectorQuery, marketBrief)
}
```

### 3.3 處理 tool_call 響應

LLM 返回的 `tool_calls` 是結構化 JSON，不再需要正則解析：

```kotlin
// 解析 LLM 返回的 tool_calls
data class ToolCallResponse(
    val id: String,
    val type: String,          // "function"
    val function: FunctionCall
)

data class FunctionCall(
    val name: String,           // "stock_query"
    val arguments: String      // JSON 字符串 {"query": "兆易創新", ...}
)

// ChatAgent 處理 tool_call
private fun handleToolCall(call: ToolCallResponse): String {
    return when (call.function.name) {
        "stock_query" -> {
            val args = JSONObject(call.function.arguments)
            val query = args.getString("query")
            val entities = StockEntityExtractor.extract(query)
            if (entities.isNotEmpty()) {
                StockService.buildStockPrompt(entities.first().code)
            } else {
                "未找到匹配的股票: $query"
            }
        }
        "sector_query" -> {
            val args = JSONObject(call.function.arguments)
            val sector = args.getString("sectorName")
            ThemeStockService.processThemeQuery(sector).toPromptString()
        }
        "market_brief" -> MarketBriefTool().execute(emptyMap())
        else -> "未知工具: ${call.function.name}"
    }
}
```

### 3.4 改造 `AgentBase.react()`

從 JSON prompt 約定改為原生 Function Calling：

```kotlin
// 原來：LLM 輸出 {"action": "TOOL_CALL", "tool_name": "xxx", "params": {...}}
// 現在：LLM 輸出 tool_calls: [{id, type, function: {name, arguments}}]

suspend fun react(userMessage: String, onStream: (String) -> Unit) {
    val messages = mutableListOf(
        mapOf("role" to "user", "content" to userMessage)
    )

    repeat(maxSteps) { step ->
        val response = provider.sendMessageStream(
            messages = messages,
            systemPrompt = systemPrompt,
            onChunk = onStream,
            tools = ChatTools.allTools    // ← 傳入工具定義
        )

        if (response.toolCalls.isEmpty()) {
            break  // LLM 認為不需要工具，直接回答
        }

        // 執行工具並追加結果
        for (call in response.toolCalls) {
            val result = handleToolCall(call)
            messages.add(mapOf("role" to "tool", "content" to result,
                "tool_call_id" to call.id))
        }
    }
}
```

### 3.5 向後兼容

部分 Provider（如豆包/通義千問）支援 Function Calling，部分可能不支援。需要：
- 在 `ApiProviderConfig` 中增加 `supportsTools: Boolean` 標記
- 不支援 tools 的 Provider 降級為 JSON prompt 約定模式

### 階段三交付物

| 文件 | 類型 | 說明 |
|------|------|------|
| `OpenAiCompatibleProvider.kt` | 修改 | 增加 tools 參數 |
| `ai/ChatTools.kt` | 新增 | 工具定義（stock_query/sector_query/market_brief） |
| `agent/AgentBase.kt` | 修改 | react() 使用原生 Function Calling |
| `agent/chat/ChatAgent.kt` | 修改 | handleToolCall() 實現 |
| `ai/ApiProviderConfig.kt` | 修改 | 增加 supportsTools 標記 |

---

## 階段四：Plan-and-Execute 對話整合

**目標**：用戶在對話中說「全面分析兆易創新」，Agent 自動生成執行計劃，調用 Pipeline 完成多維度分析。

### 4.1 Plan Agent

新增一個輕量級規劃 Agent，使用 LLM 一次性生成執行計劃：

```kotlin
object PlanAgent {
    data class ExecutionPlan(
        val goal: String,              // "全面分析兆易創新"
        val steps: List<PlanStep>
    )

    data class PlanStep(
        val id: String,               // "step_1"
        val action: String,           // "fetch_realtime"
        val description: String,       // "獲取兆易創新實時行情"
        val tool: String,             // "stock_query"
        val params: Map<String, Any>,  // {"query": "603986"}
        val dependsOn: List<String>   // 空列表 = 可並行
    )

    suspend fun generatePlan(userMessage: String, entities: List<ExtractedEntity>): ExecutionPlan
}
```

### 4.2 Plan-and-Execute 流程

```
用戶："全面分析兆易創新"
  │
  ├─ Step 1: StockEntityExtractor → 603986
  │
  ├─ Step 2: PlanAgent.generatePlan()
  │   → PlanStep(fetch_realtime, 獨立)
  │   → PlanStep(fetch_financials, 獨立)
  │   → PlanStep(fetch_sector_heat, 獨立)
  │   → PlanStep(fetch_news, 獨立)
  │   → PlanStep(pipeline_analyze, 依賴上面全部)
  │
  ├─ Step 3: Executor 並行執行獨立步驟
  │   ├─ fetch_realtime(603986)     ┐
  │   ├─ fetch_financials(603986)   ├─ 並行
  │   ├─ fetch_sector_heat(603986) │
  │   └─ fetch_news(603986)        ┘
  │
  ├─ Step 4: Executor 執行依賴步驟
  │   └─ pipeline_analyze(all_data) → AgentPipelineOrchestrator
  │
  └─ Step 5: 綜合回答
```

### 4.3 對話觸發 Pipeline

當用戶意圖是深度分析時，調用已有的 `AgentPipelineOrchestrator`：

```kotlin
// ChatAgent 中
if (intent == UserIntent.STOCK_ANALYSIS && isDeepAnalysis(input)) {
    // 啟動 Pipeline（複用已有的精簡版六智體流程）
    val pipelineResult = AgentPipelineOrchestrator.runPipeline(
        targetStock = entities.first().code,
        mode = AgentPipelineOrchestrator.MODE_SELLER_SIMPLE
    )
    // 將 Pipeline 結果格式化為對話回覆
    return formatPipelineResult(pipelineResult)
}
```

### 4.4 UI 進度展示

Pipeline 執行過程中，在對話區域顯示進度（復用 `PipelineProgressView`）：

```
🤖 正在分析兆易創新...

  ✅ 數據採集完成 (2.3s)
  ✅ Agent 1 基本面初選完成 (8.1s)
  ⏳ Agent 2 產業鏈打分中...
  ⏸ Agent 3 賽道熱度（等待中）
  ⏸ Agent 4 技術面（等待中）
  ⏸ Agent 5 風控（等待中）
  ⏸ Agent D 輿情（並行中）
```

### 4.5 ReAct 回圈支援

對需要動態補充信息的場景（如「兆易創新和韋爾股份哪個更值得買」），使用 ReAct 模式：

```
Thought: 用戶想比較兩只股票，需要分別獲取數據
Action: stock_query({"query": "兆易創新"})
Observation: 兆易創新 603986, PE 45.2, ROE 12.3%...

Thought: 已獲取兆易創新，還需要韋爾股份
Action: stock_query({"query": "韋爾股份"})
Observation: 韋爾股份 603501, PE 62.8, ROE 8.7%...

Thought: 兩只股票數據齊全，可以回答
→ 綜合比較分析回答
```

### 階段四交付物

| 文件 | 類型 | 說明 |
|------|------|------|
| `agent/chat/PlanAgent.kt` | 新增 | 規劃 Agent |
| `agent/chat/PlanExecutor.kt` | 新增 | 計劃執行器（串行+並行） |
| `agent/chat/ChatAgent.kt` | 修改 | 深度分析觸發 Pipeline |
| `ui/ChatTabFragment.kt` | 修改 | Pipeline 進度展示 |
| `agent/pipeline/AgentPipelineOrchestrator.kt` | 修改 | 支持從對話調用 |

---

## 實現優先級與時間線

| 階段 | 預估工作量 | 優先級 | 依賴 |
|------|-----------|--------|------|
| 階段一：本地詞典引擎 | 1-2 天 | **P0** | 無 |
| 階段二：歧義消解 | 1 天 | P1 | 階段一 |
| 階段三：Function Calling | 2-3 天 | P1 | 階段一 |
| 階段四：Plan-and-Execute | 2-3 天 | P2 | 階段一+三 |

**建議順序**：階段一 → 階段二 → 階段三 → 階段四

階段一完成後，「分析兆易創新」這類問題就能立即解決。後續階段逐步增強意圖理解深度。

---

## 驗收標準

### 階段一
- [x] 「兆易創新」→ 正確解析為 603986
- [x] 「茅台」→ 正確解析為 600519
- [x] 「zycx」→ 正確解析為 603986
- [x] 「分析兆易創新」→ STOCK_ANALYSIS + code=603986
- [x] 「兆易創新和韋爾股份」→ 兩只股票都比對
- [x] 本地匹配 < 10ms（日誌打印耗時）

### 階段二
- [ ] 「華為」→ 顯示確認卡片（3個板塊候選）
- [ ] 用戶選擇後觸發對應分析
- [ ] 用戶點取消後不執行任何操作

### 階段三
- [ ] LLM 自動調用 stock_query 工具（日誌可見 tool_calls）
- [ ] 不支援 tools 的 Provider 降級為 JSON prompt 約定
- [ ] Function Calling 失敗時降級到本地詞典

### 階段四
- [ ] 「全面分析兆易創新」→ 觸發 Pipeline（六智體流程）
- [ ] Pipeline 進度在對話區域實時展示
- [ ] 「兆易創新和韋爾股份哪個好」→ ReAct 模式分別查詢後比較
