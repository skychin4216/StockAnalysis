# Agent 對話框架架構設計

## 概述

對話 Agent 框架分為三層：

1. **意圖層**：識別用戶輸入意圖，提取股票實體
2. **執行層**：根據意圖調度對應子 Agent 完成任務
3. **渲染層**：將結果格式化為對話消息展示給用戶

---

## 架構圖

```
┌─────────────────────────────────────────────────────────────┐
│                        用戶輸入                              │
│              "分析兆易創新" / "上證指數怎麼樣"                │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  ① 意圖層 — IntentPredictionEngine / ChatAgent.detectIntent │
│                                                             │
│  StockEntityExtractor.extractSync()                         │
│    ├─ L1: 精確代碼匹配（6位數字 / sh+6位）                  │
│    ├─ L2: Trie 詞典匹配（精確/前綴/子串/拼音）              │
│    └─ L3: FALLBACK_STOCK_MAP 降級（40+ 龍頭股）             │
│                                                             │
│  返回: UserIntent.{STOCK_ANALYSIS|INDEX_ANALYSIS|            │
│                   STOCK_PICKING|MARKET_BRIEF|GENERAL_CHAT}   │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  ② 執行層 — ChatAgent.handleMessage()                       │
│                                                             │
│  when (intent) {                                            │
│    INDEX_ANALYSIS    → IndexAnalysisAgent.analyze()         │
│    STOCK_PICKING     → StockPickingAgent.pickStocks()       │
│    STOCK_ANALYSIS    → StockAnalysisAgent.analyze()         │
│    MARKET_BRIEF      → generateMarketBrief()                │
│    GENERAL_CHAT      → AgentBase.react() (ReAct)            │
│  }                                                          │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  ③ 渲染層 — ChatTabFragment / ChatAdapter                   │
│                                                             │
│  普通消息 → Text Message                                    │
│  歧義確認 → EntityConfirmCard                               │
│  流式輸出 → 逐字顯示                                        │
└─────────────────────────────────────────────────────────────┘
```

---

## 核心組件

### 1. StockEntityExtractor（實體提取引擎）

```kotlin
object StockEntityExtractor {
    // 同步版本（IntentPredictionEngine 用）
    fun extractSync(input: String): List<ExtractedEntity>

    // 異步版本（帶 Trie 構建）
    suspend fun extract(input: String, context: Context): List<ExtractedEntity>
}

data class ExtractedEntity(
    val text: String,       // "兆易創新"
    val code: String,       // "603986"
    val name: String,       // "兆易創新"
    val matchType: MatchType,   // EXACT_NAME / PREFIX_NAME / PINYIN_ABBR / ...
    val confidence: Float   // 0.0 ~ 1.0
)
```

**匹配優先級**：EXACT_CODE > PREFIX_CODE > EXACT_NAME > PREFIX_NAME > SUBSTRING_NAME > PINYIN_ABBR > PINYIN_FULL > FUZZY_API

### 2. ChatAgent（對話調度中心）

```kotlin
class ChatAgent(context: Context) : AgentBase(...) {
    private val pickingAgent = StockPickingAgent(context)
    private val analysisAgent = StockAnalysisAgent(context)

    suspend fun handleMessage(userMessage: String, onStream: ((String) -> Unit)?): ChatAgentResult
}
```

**意圖判定優先級**：
1. 指數關鍵詞（上證/深證/創業板/大盤）→ INDEX_ANALYSIS
2. StockEntityExtractor 命中實體 → 根據關鍵詞判定 ANALYSIS / PICKING / BRIEF
3. 正則提取代碼 → STOCK_ANALYSIS
4. 其他 → GENERAL_CHAT（走 ReAct）

### 3. StockAnalysisAgent（股票分析）

**設計原則**：並行收集數據，單次 LLM 調用。

```kotlin
class StockAnalysisAgent(context: Context) : AgentBase(...) {
    suspend fun analyze(stockCode: String, onProgress: ((String) -> Unit)?): StockAnalysisResult
}
```

**執行流程**：
```
analyze("sh603986")
  │
  ├─ async { TechnicalAnalysisTool.execute(...) }    ─┐
  ├─ async { FundamentalAnalysisTool.execute(...) }   ├─ 並行（本地 DB，毫秒級）
  └─ async { FundFlowTool.execute(...) }             ─┘
  │
  ├─ 合併數據為 prompt
  ├─ 單次 LLM 調用（60s 超時，invokeOnCancellation）
  └─ 解析 JSON → StockAnalysisResult
```

### 4. IndexAnalysisAgent（指數分析）

```kotlin
object IndexAnalysisAgent {
    suspend fun analyze(context: Context, indexCode: String, indexName: String): IndexAnalysisResult?
}
```

**執行流程**：
```
analyze(context, "sh000001", "上證指數")
  │
  ├─ IndexKlineAnalyzer.analyze() → KlineAnalysis
  │   ├─ DailySnapshotDao.getByCode(indexCode, 20)
  │   ├─ 計算連漲/連跌天數、MA5/10/20、量能比、波動率
  │   └─ 趨勢判定
  │
  ├─ 構建技術面分析 prompt
  ├─ 單次 LLM 調用（60s 超時）
  └─ 解析結果 → IndexAnalysisResult
```

### 5. AgentBase（基類）

```kotlin
abstract class AgentBase(...) {
    // ReAct 模式（通用對話）
    suspend fun react(userMessage: String, ctx: AgentContext, maxSteps: Int): AgentResult

    // Plan-and-Execute 模式（保留但 StockAnalysisAgent 已棄用）
    suspend fun planAndExecute(userMessage: String, ctx: AgentContext): AgentResult

    // 工具註冊
    protected fun registerTool(tool: AgentTool)
}
```

### 6. OpenAiCompatibleProvider（LLM 調用層）

```kotlin
class OpenAiCompatibleProvider(config: ApiProviderConfig) : ApiProvider {
    // 接口方法（5 參數，向後兼容）
    override fun sendMessageStream(messages, systemPrompt, onSuccess, onComplete, onError)

    // 擴展方法（帶 Function Calling）
    fun sendMessageStreamWithTools(messages, systemPrompt, onSuccess, onComplete, onError,
                                    tools, toolChoice, onToolCalls)
}
```

**推理模型適配**：當 `delta.content` 為空時，自動讀取 `delta.reasoning_content`。

---

## 數據流

### 股票分析請求

```
用戶: "分析兆易創新"
  │
  ├─ IntentPredictionEngine
  │   └─ StockEntityExtractor.extractSync("分析兆易創新")
  │       └─ 剝離「分析」→ Trie 命中「兆易創新」→ code=sh603986
  │   └─ 預判: StockQuery(code=sh603986, name=兆易創新, confidence=0.95)
  │
  ├─ ChatAgent.handleMessage()
  │   └─ detectIntent() → STOCK_ANALYSIS
  │   └─ extractStockCode() → "sh603986"
  │   └─ analysisAgent.analyze("sh603986")
  │       ├─ 並行收集技術面/基本面/資金面
  │       ├─ LLM 生成分析（60s 超時）
  │       └─ 解析 JSON → StockAnalysisResult
  │   └─ formatAnalysisResponse() → 自然語言
  │
  └─ ChatTabFragment 渲染結果
```

### 指數分析請求

```
用戶: "上證指數怎麼樣"
  │
  ├─ ChatAgent.handleMessage()
  │   └─ detectIntent() → INDEX_ANALYSIS（命中「上證」關鍵詞）
  │   └─ extractIndexInfo() → ("sh000001", "上證指數")
  │   └─ IndexAnalysisAgent.analyze(context, "sh000001", "上證指數")
  │       ├─ IndexKlineAnalyzer.analyze()
  │       ├─ LLM 生成預判（60s 超時）
  │       └─ 解析 → IndexAnalysisResult
  │
  └─ ChatTabFragment 渲染結果
      "📊 上證指數 技術面分析
       ...
       🔮 短線預判：...
       ⚠️ 風險提示：..."
```

---

## 超時與取消策略

| 場景 | 超時時間 | 取消行為 |
|------|----------|----------|
| 打字預判（IntentPredictionEngine） | 300ms 防抖 | 重新輸入時取消上次請求 |
| 股票分析 LLM 調用 | 60,000ms | `invokeOnCancellation { provider.cancel() }` |
| 指數分析 LLM 調用 | 60,000ms | `invokeOnCancellation { provider.cancel() }` |
| 通用對話 ReAct | 25,000ms × maxSteps | 無（待改進） |

---

## 文件清單

### 框架核心

| 文件 | 路徑 | 職責 |
|------|------|------|
| `AgentBase.kt` | `agent/framework/AgentBase.kt` | ReAct / Plan-and-Execute / 工具註冊 |
| `ChatAgent.kt` | `agent/chat/ChatAgent.kt` | 意圖調度、子 Agent 協作 |
| `StockAnalysisAgent.kt` | `agent/stock/StockAnalysisAgent.kt` | 股票深度分析（並行數據+單次LLM） |
| `IndexAnalysisAgent.kt` | `agent/chat/IndexAnalysisAgent.kt` | 指數 K 線分析 |
| `PlanAgent.kt` | `agent/chat/PlanAgent.kt` | 執行計劃生成 |

### 意圖與實體

| 文件 | 路徑 | 職責 |
|------|------|------|
| `StockEntityExtractor.kt` | `ai/StockEntityExtractor.kt` | 股票實體提取（三層級聯） |
| `StockNameTrie.kt` | `stock/data/StockNameTrie.kt` | Trie 樹名稱索引 |
| `IntentPredictionEngine.kt` | `ai/IntentPredictionEngine.kt` | 打字預判意圖 |

### LLM 提供層

| 文件 | 路徑 | 職責 |
|------|------|------|
| `OpenAiCompatibleProvider.kt` | `OpenAiCompatibleProvider.kt` | SSE 流式調用 + tools + reasoning_content |
| `ChatTools.kt` | `ai/ChatTools.kt` | Function Calling 工具定義 |
| `AiProviderPool.kt` | `ai/AiProviderPool.kt` | Provider 連接池 |

### UI 層

| 文件 | 路徑 | 職責 |
|------|------|------|
| `ChatTabFragment.kt` | `ui/ChatTabFragment.kt` | 對話界面、消息渲染 |
| `ChatAdapter.kt` | `ui/ChatAdapter.kt` | 消息列表適配器（含 EntityConfirmCard） |
| `EntityConfirmCard.kt` | `ui/EntityConfirmCard.kt` | 歧義確認卡片 |
| `Message.kt` | `ui/Message.kt` | 消息數據類 |
