# Agent 架構重構方案：借鑒 OpenCode 多智能體設計

## 一、現狀診斷

### 1.1 現有架構全貌

當前系統存在 **兩套並行但互不打通的執行體系**：

```
體系 A: AI Agent 層                              體系 B: DAG Pipeline 層
┌──────────────────────────┐                   ┌──────────────────────────┐
│ UnifiedAgentRunner       │                   │ UseCaseLoader             │
│  ├─ MODE_QUICK (3路並行) │                   │  ├─ XML 解析              │
│  ├─ MODE_PIPELINE (6/7步)│                   │  ├─ DagPipeline (Kahn)   │
│  └─ MODE_V2 (3路+決策矩陣)│                   │  └─ NodeRegistry (19種)  │
│ AgentPipelineOrchestrator│                   │ DagTradeExecutor         │
│ AgentBase (ReAct/P&E)    │                   │ StrategyEngine           │
│ StockAnalysisAgent       │ ◄── 幾乎無數據互通 ──► PipelineContext          │
│ RiskManagementAgent      │                   │ PipelineNode<IN,OUT>      │
│ V2AgentRunner            │                   │                          │
└──────────────────────────┘                   └──────────────────────────┘
```

體系 A 負責**單股深度 AI 分析**（LLM 推理為主），體系 B 負責**批量選股和交易執行**（量化計算為主）。兩者之間唯一的橋接點是 `quantSignalsProvider`，且為可選。

### 1.2 核心問題清單

| # | 問題 | 影響範圍 | 嚴重程度 |
|---|------|---------|---------|
| P1 | Agent 層與 DAG 層數據割裂，分析結果無法流入選股流程 | 全週期 | 高 |
| P2 | V1 Stage-based Pipeline 和 V2 DagPipeline 共存，維護成本翻倍 | 全系統 | 高 |
| P3 | AgentBase 的 JSON Prompt 解析脆弱，LLM 輸出格式偏差會導致推理空轉 | 所有 ReAct Agent | 中 |
| P4 | NodeRegistry 硬編碼 19 個 Node，擴展需改代碼而非配置 | DAG 層 | 中 |
| P5 | LLM 調用無緩存，相同股票重複分析浪費 token | 所有 Agent 調用 | 中 |
| P6 | 市場環境分析（MarketAnalyzer）在不同路徑中重複計算，無跨路徑緩存 | 全系統 | 低 |
| P7 | PipelineContext 的 stageOutputs 用 Map<String, Any?> 存儲，類型擦除導致 ClassCastException | DAG 層 | 高 |
| P8 | 策略註冊分散在 StrategyEngineHolder 和 NodeRegistry 兩處，需手動同步 | 策略層 | 低 |

### 1.3 與 OpenCode 的差距對照

| 維度 | OpenCode | 當前系統 | 差距 |
|------|----------|---------|------|
| Agent 權限模型 | allow/ask/deny 三級工具權限 | 所有 Agent 權限相同 | 缺少權限隔離 |
| 上下文隔離 | Subagent 獨立 session，不污染主上下文 | 所有 Agent 共享 PipelineContext | 上下文洩漏風險 |
| 任務路由 | 按任務性質動態匹配專職 Agent | 硬編碼 mode 選擇（Quick/Pipeline/V2） | 缺少動態路由 |
| 並行編排 | DAG 依賴圖 + 同層並行 | Kahn 拓撲排序（已具備）但與 Agent 層脫節 | DAG 未覆蓋 Agent |
| 模型分配 | 按 Agent 角色指定不同模型（成本/質量平衡） | 所有 Agent 使用同一 Provider | 無差異化模型 |
| 容錯降級 | 非關鍵節點失敗不阻塞 + 自動重試 | 非關鍵節點跳過，但無重試和降級 | 缺少自愈能力 |

---

## 二、重構目標

**不追求一步到位的大重寫**，而是分三個階段，每階段交付可獨立運行的系統：

1. **統一執行引擎** — 將 Agent 層和 DAG 層合併為一套 DAG，讓 LLM 推理和量化計算在同一張圖裡流動
2. **角色化 Agent 體系** — 借鑒 OpenCode 的 Build/Plan/Subagent 分離，建立權限隔離和上下文隔離
3. **智能路由與成本優化** — 按任務複雜度動態選擇模型和 Agent 組合，實現 token 成本與分析質量的平衡

---

## 三、階段一：統一執行引擎（Unified DAG）

### 3.1 核心思路

廢除 V1 Stage-based Pipeline，將所有執行路徑統一到 DagPipeline。關鍵變化：**PipelineNode 不再只包裝量化計算，也可以包裝 LLM Agent 調用**。

```
統一後的 DAG：

┌─────────────┐
│ n_market_ctx│ ── 市場上下文構建（量化）
└──────┬──────┘
       ├──────────────────┐
┌──────▼──────┐    ┌──────▼──────┐
│  n_pool     │    │ n_bg_mgr   │ ── 後台任務暫停（非關鍵）
└──────┬──────┘    └─────────────┘
       │
  ┌────┼────────────────────┐
  ▼    ▼                    ▼
n_main  n_smart    n_news_ctx ── 新聞採集
filter  money_filter
  │    │                    │
  └────┼────────────────────┘
       ▼
  ┌─────────────┐
  │ 策略節點群   │ ── 量化策略並行篩選
  │ (strategy_*) │
  └──────┬──────┘
         ▼
  ┌─────────────┐
  │   n_merge    │ ── 信號聚合
  └──────┬──────┘
         │
    ┌────┼────────────────┐
    ▼    ▼                ▼
n_ai   n_risk          n_sector ── 板塊資金（量化）
pred   guard
(LLM)  (LLM)
    │    │                │
    └────┼────────────────┘
         ▼
  ┌─────────────┐
  │  n_orders   │ ── 訂單生成
  └──────┬──────┘
         ▼
  ┌─────────────┐
  │ n_merge_pos │ ── 持倉合併
  └─────────────┘
```

### 3.2 關鍵改動

**新增 AgentNode 包裝器** — 將任何 AgentBase 子類包裝為 PipelineNode：

```kotlin
class AgentNode<T>(
    private val agent: AgentBase,
    private val inputMapper: (PipelineContext) -> T,
    private val outputMapper: (AgentResult) -> Any
) : PipelineNode<Unit, Any> {

    override val nodeId: String = "agent_${agent::class.simpleName}"
    override val nodeName: String = agent::class.simpleName ?: "agent"
    override val nodeType: NodeType = NodeType.AI_PREDICTION

    override suspend fun execute(context: PipelineContext, input: Unit): Any {
        val agentInput = inputMapper(context)
        val result = agent.execute(agentInput)
        return outputMapper(result)
    }
}
```

這樣，`StockAnalysisAgent`、`RiskManagementAgent` 都可以作為 DAG 中的一個節點，與量化節點在同一張圖裡流動，解決 P1（數據割裂）問題。

**統一 PipelineContext 類型系統** — 解決 P7（ClassCastException）：

```kotlin
// 替代 Map<String, Any?>，使用帶類型聲明的輸出槽
data class TypedSlot<T : Any>(
    val name: String,
    val type: KClass<T>,
    val value: T? = null
)

class TypedOutputRegistry {
    private val slots = ConcurrentHashMap<String, TypedSlot<*>>()

    inline fun <reified T : Any> declare(name: String)
    fun <T : Any> set(name: String, value: T)
    inline fun <reified T : Any> get(name: String): T?
}
```

每個 Node 在創建時聲明自己產出的類型，消費方按類型安全讀取。

**廢除 V1 Pipeline** — UseCaseLoader 只保留 DAG 路徑：

```kotlin
// 刪除 loadPipelineFromAssets() 和 V1 相關代碼
// UseCaseLoader.init() 統一走 DAG
fun run(useCaseId: String, tradeDate: String): MultiPipelineResult {
    val dag = loadDagPipelineFromAssets(useCaseId)
    injectStrategiesToDag(dag)  // 動態注入策略
    return dag.execute(context)
}
```

### 3.3 解決的問題

- P1：Agent 產出的分析結果（評分、風險等級、目標價）直接存入 PipelineContext，後續的訂單生成節點可以讀取
- P2：V1 Pipeline 代碼整體移除，只保留 DagPipeline 一套引擎
- P7：TypedOutputRegistry 消除類型擦除帶來的 ClassCastException

---

## 四、階段二：角色化 Agent 體系

### 4.1 借鑒 OpenCode 的 Agent 角色模型

OpenCode 的核心設計是**權限分離**：Build Agent 可讀寫文件和執行命令，Plan Agent 只能讀和規劃，Subagent 有獨立的上下文窗口。

映射到股票分析場景，定義四種 Agent 角色：

| 角色 | 權限 | 職責 | 對應現有組件 |
|------|------|------|------------|
| **Orchestrator** | 讀全局數據、分派任務、彙總結果 | 接收用戶指令，拆解為子任務，協調執行順序 | UnifiedAgentRunner 的 mode 選擇邏輯 |
| **Analyst** | 讀股票數據、調用 LLM、輸出分析報告 | 單股或多股的深度分析 | StockAnalysisAgent, AgentPipelineOrchestrator 的各步驟 |
| **Guardian** | 讀持倉數據、調用 LLM、輸出風控指令 | 風險掃描、止損止盈、倉位控制 | RiskManagementAgent |
| **Scout** | 只讀市場數據，不調用 LLM | 市場環境感知、板塊輪動、資金流向 | MarketAnalyzer, SectorRotationEngine |

### 4.2 權限模型設計

```kotlin
enum class AgentPermission {
    READ_MARKET_DATA,    // 讀取行情、K線、財報
    READ_PORTFOLIO,      // 讀取持倉、交易記錄
    CALL_LLM,            // 調用 LLM API
    WRITE_TRADE_ORDER,   // 生成/修改交易訂單
    MODIFY_STRATEGY,     // 修改策略參數
    EXECUTE_TRADE,       // 執行交易（模擬/實盤）
    ACCESS_NEWS,          // 訪問新聞 API
}

data class AgentRole(
    val name: String,
    val permissions: Set<AgentPermission>,
    val llmModel: String? = null,        // 該角色推薦的 LLM 模型
    val maxConcurrent: Int = 1,          // 最大並發數
    val timeoutMs: Long = 30_000          // 默認超時
)
```

預定義四種角色的權限：

```
Orchestrator: READ_MARKET_DATA, READ_PORTFOLIO
Analyst:      READ_MARKET_DATA, READ_PORTFOLIO, CALL_LLM, ACCESS_NEWS
Guardian:     READ_PORTFOLIO, CALL_LLM
Scout:        READ_MARKET_DATA, ACCESS_NEWS
```

注意：**沒有任何角色擁有 EXECUTE_TRADE 權限**。交易執行由 DAG 的交易節點（n_orders, n_swap）統一處理，不經由 Agent。這確保交易行為始終受量化規則約束，而非 LLM 自主決策。

### 4.3 上下文隔離

借鑒 OpenCode 的獨立 session 設計，每個 Agent 在 DAG 中執行時擁有**獨立的 AgentContext**：

```kotlin
class AgentContext(
    val parentContext: PipelineContext,   // 只讀引用，可讀全局數據
    val role: AgentRole,
    val taskId: String
) {
    // 獨立的短期記憶，不會污染其他 Agent
    private val memory = mutableListOf<String>()
    private val toolResults = mutableMapOf<String, Any>()

    fun readFromParent(slotName: String): Any? = parentContext.getTypedOutput(slotName)
    // 不能寫回 parentContext，只能通過 DAG 節點的 output 寫入
}
```

這樣 Agent 之間的數據流完全通過 DAG 的邊來傳遞，而非通過共享可變狀態。

### 4.4 解決的問題

- Agent 上下文洩漏：每個 Agent 只能看到自己需要的數據
- 權限隔離：Scout 不能調用 LLM（節省 token），Guardian 不能讀市場數據（聚焦風控）
- LLM 調用可控：只有 Analyst 和 Guardian 角色擁有 CALL_LLM 權限

---

## 五、階段三：智能路由與成本優化

### 5.1 任務路由器（TaskRouter）

借鑒 oh-my-opencode-slim 的 Orchestrator 思路，引入 **TaskRouter** 根據用戶意圖動態選擇 Agent 組合和模型：

```kotlin
class TaskRouter {
    /**
     * 根據任務描述和上下文，返回最優的 DAG 配置
     */
    fun resolve(
        intent: UserIntent,
        marketEnv: MarketEnvironment,
        holdingPeriod: HoldingPeriod
    ): DagConfig {
        return when (intent.type) {
            IntentType.QUICK_SCAN -> dagConfig {
                // 快速掃描：不用 LLM，純量化
                nodes = listOf("n_market_ctx", "n_pool", "策略群", "n_merge", "n_orders")
                llmNodes = emptyList()  // 不包含任何 LLM 節點
            }
            IntentType.DEEP_ANALYSIS -> dagConfig {
                // 深度分析：全鏈路 + LLM
                nodes = fullPipeline()
                llmNodes = listOf("n_ai_pred", "n_risk_guard")
                llmModel = selectModelByComplexity(marketEnv)
            }
            IntentType.RISK_CHECK -> dagConfig {
                // 風控掃描：只跑 Guardian
                nodes = listOf("n_market_ctx", "n_risk_guard")
                llmNodes = listOf("n_risk_guard")
                llmModel = "fast-model"  // 風控用快速模型
            }
        }
    }
}
```

### 5.2 模型分層策略

借鑒 OpenCode 的「主 Agent 用強模型，Subagent 用便宜模型」思路：

| 任務類型 | 推薦模型 | 理由 |
|---------|---------|------|
| 產業鏈分析（Agent 2） | 強模型（高 max_tokens） | 需要深度推理 |
| 技術面分析（Agent 3） | 中等模型 | K 線識別任務相對確定 |
| 風控終審（Agent 5） | 強模型 | 決策敏感度高 |
| 新聞採集（Agent D） | 快速模型 + 緩存 | 數據採集為主，推理少 |
| 市場環境（Scout） | 不需要 LLM | 純量化計算 |

實現方式：在 DagConfig 中為每個 Node 指定模型覆蓋：

```kotlin
data class DagNodeConfig(
    val nodeId: String,
    val module: String,
    val config: Map<String, String> = emptyMap(),
    val modelOverride: String? = null  // 可選：覆蓋該節點的 LLM 模型
)
```

### 5.3 LLM 響應緩存

解決 P5（重複分析浪費 token）：

```kotlin
class AnalysisCache(
    private val ttl: Duration = Duration.ofMinutes(30),
    private val maxSize: Int = 100
) {
    // key: stockCode + analysisType + tradeDate
    // value: LLM 響應 JSON
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    data class CacheEntry(
        val response: String,
        val timestamp: Instant,
        val tokenCount: Int
    )
}
```

相同股票在同一天內的相同分析類型，直接返回緩存結果。在 DAG 節點層面透明生效，Agent 無感知。

### 5.4 市場環境全局緩存

解決 P6（MarketAnalyzer 重複計算）：

```kotlin
object GlobalMarketCache {
    private var cachedReport: MarketReport? = null
    private var cacheDate: String = ""
    private val lock = Any()

    fun getReport(context: Context, tradeDate: String): MarketReport {
        synchronized(lock) {
            if (cacheDate == tradeDate && cachedReport != null) {
                return cachedReport!!
            }
            cachedReport = MarketAnalyzer.analyze(context, tradeDate)
            cacheDate = tradeDate
            return cachedReport!!
        }
    }
}
```

所有路徑（Agent 層、DAG 層、UI 層）共享同一個 MarketReport 實例。

---

## 六、Node 註冊機制改造

解決 P4（NodeRegistry 硬編碼）和 P8（策略註冊分散），借鑒 OpenCode 的 Plugin/Skills 註冊思路：

### 6.1 註解驅動註冊

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class DagNodeModule(
    val id: String,
    val name: String,
    val nodeType: NodeType
)

// 使用示例
@DagNodeModule(id = "market_context", name = "市場上下文構建", nodeType = NodeType.DATA_SOURCE)
class MarketContextNode(...) : PipelineNode<Unit, StrategyMarketContext> { ... }
```

NodeRegistry 在 init 時通過 ClassPath 掃描自動發現所有帶 `@DagNodeModule` 註解的類，無需手動 register。

### 6.2 策略節點自動注入

策略不再需要在兩處註冊。`@DagNodeModule` 加上策略標記後，UseCaseLoader 的 `injectStrategiesToDag()` 自動從 StrategyEngineHolder 獲取所有啟用策略，並按週期注入到 DAG 中：

```kotlin
@DagNodeModule(id = "strategy_{id}", name = "{name}", nodeType = NodeType.STRATEGY)
class StrategyNode(strategy: Strategy) : PipelineNode<StockPool, SignalPack> { ... }
```

---

## 七、容錯與降級機制

### 7.1 節點級降級策略

借鑒 OpenCode 的容錯設計，為每個 DAG 節點定義降級策略：

```kotlin
enum class FallbackStrategy {
    SKIP,           // 跳過，使用 null 輸出（非關鍵節點）
    CACHE,          // 使用上次成功的緩存結果
    DEFAULT_VALUE,  // 使用預設值
    RETRY_ONCE,    // 重試一次
    DEGRADE_LLM     // 降級到更小/更快的模型
}

data class NodeResilience(
    val isCritical: Boolean = false,
    val fallback: FallbackStrategy = FallbackStrategy.SKIP,
    val retryCount: Int = 0,
    val timeoutMs: Long = 30_000
)
```

XML 配置中可聲明降級策略：

```xml
<Node id="n_ai_pred" name="AI 預測" module="ai_predict"
      timeout="60" critical="true"
      fallback="DEGRADE_LLM" fallbackModel="fast-model"/>
```

### 7.2 LLM 節點專屬降級

AI 節點（如 n_ai_pred、n_risk_guard）在 LLM 超時或失敗時：

1. 首次失敗 → 重試一次（換 Provider，如果有備選）
2. 再次失敗 → 降級到更小模型（gpt-4o-mini → gpt-3.5-turbo 級別）
3. 仍然失敗 → 使用算法後備（ATR 止損、均值回歸評分等）
4. 輸出帶有 `degraded = true` 標記，下游節點可感知並降低依賴權重

---

## 八、數據流設計

### 8.1 統一數據流

重構後的數據流只有一條主路徑：

```
用戶意圖 → TaskRouter.resolve()
         → 生成 DagConfig（包含哪些節點、哪個節點用哪個模型）
         → UseCaseLoader 根據 DagConfig 動態組裝 DAG
         → DagPipeline.execute()
           → 每個節點按角色執行（Scout 不調 LLM，Analyst 調 LLM）
           → 節點輸出寫入 TypedOutputRegistry
           → 下游節點按類型安全讀取
         → 後處理（訂單生成、自選股保存）
         → UI 展示
```

### 8.2 Agent 結果流入選股

當前 Agent 分析結果無法影響選股，重構後：

```
n_ai_pred (Analyst) 輸出:
  └─ AIAnalysisResult { score, riskLevel, recommendation }
     │
     ▼
n_merge (聚合節點) 可以讀取:
  └─ 將 AI 評分作為信號加權因子，與量化信號合併
     │
     ▼
n_orders (訂單生成) 可以讀取:
  └─ 結合 AI 目標價/止損位，生成更精準的訂單參數
```

---

## 九、遷移路線

### Phase 1: 統一 DAG（預估 2 週）

1. 實現 AgentNode 包裝器，將現有 Agent 包裝為 PipelineNode
2. 實現 TypedOutputRegistry，替換 stageOutputs 的 Map<String, Any?>
3. 將 StockAnalysisAgent 和 RiskManagementAgent 作為節點嵌入各週期 DAG XML
4. 移除 V1 Stage-based Pipeline 相關代碼
5. **驗證標準**：所有週期的 Hardcode 和 DAG 路徑產出結果一致

### Phase 2: 角色化 Agent（預估 1.5 週）

1. 定義 AgentRole 和 AgentPermission 枚舉
2. 實現 AgentContext（獨立上下文，不污染全局）
3. 重構 AgentBase，注入 role 和 permission check
4. 為現有 Agent 分配角色（StockAnalysisAgent → Analyst, MarketAnalyzer → Scout 等）
5. **驗證標準**：Scout 角色無法調用 LLM，Guardian 角色無法讀取市場行情

### Phase 3: 路由與成本優化（預估 1 週）

1. 實現 TaskRouter，支持按意圖動態選擇 DAG 配置
2. 實現 LLM 響應緩存（AnalysisCache）
3. 實現市場環境全局緩存（GlobalMarketCache）
4. 為 DagNodeConfig 添加 modelOverride 支持
5. 實現節點級降級策略（FallbackStrategy）
6. **驗證標準**：相同股票連續分析兩次，第二次 LLM 調用次數為 0

### Phase 4: 註解驅動（預估 0.5 週）

1. 實現 @DagNodeModule 註解
2. 改造 NodeRegistry 為 ClassPath 掃描模式
3. 統一策略註冊到一處
4. **驗證標準**：新增一個 Node 只需添加註解，無需修改 NodeRegistry

---

## 十、風險與約束

### 10.1 不改變的部分

- **ReAct/Plan-and-Execute 框架**保留，但逐步遷移到原生 Function Calling
- **現有策略的量化邏輯**不動，只改裝載方式（從 StrategyEngine 直接調用 → 包裝為 DAG 節點）
- **UI 層**不改動，UnifiedAgentRunner 的外部 API 保持不變，內部實現替換
- **XML 配置格式**保持向後兼容，新屬性（critical、fallback）為可選

### 10.2 已知風險

- DAG 中嵌入 LLM 節點會增加端到端延遲（從 30s 到 60-90s），需在 UI 上做好進度反饋
- Function Calling 依賴 Provider 支持，東方財富等國內 Provider 可能不支持，需保留 JSON Prompt 作為 fallback
- ClassPath 掃描在 Android 上性能較差，可在編譯期通過 KSP 生成註冊表而非運行時掃描

---

## 十一、Phase 0：Hardcode 補齊計劃（DAG 路徑對齊）

### 11.1 問題描述

DAG Pipeline 與 Hardcode 路徑目前**不等價**。Hardcode 路徑包含若干 DAG 缺失的步驟，導致 `useDagPipeline=true` 時無法完整替代 Hardcode：

- DAG 缺少：CandidatePool、ZiplinePipeline、UserStockSource、SectorStockPool、T1AutoSell、CrossTabBus
- `PositionMergeNode` 使用 `"PENDING"` 狀態，而 Hardcode 使用 `"BUYING"`，狀態值不一致導致下游誤判

### 11.2 P0 修復（已完成）

| 節點 | 修復內容 | 說明 |
|------|---------|------|
| PositionMergeNode | 狀態值 `PENDING` → `BUYING` | 與 Hardcode 對齊，避免持倉合併時狀態誤判 |
| CandidatePoolNode | 新增 FILTER 節點 | 按 CandidatePool codes 過濾 StockPool，對齊 Hardcode 的候選池邏輯 |

### 11.3 P1 新增節點（已實現）

| 節點 | NodeType | 職責 |
|------|----------|------|
| ZiplineFactorNode | FACTOR_COMPUTE | 預計算 RSI / BB / ATR / MA 等因子，供下游策略複用 |
| SectorStockPoolNode | DATA_SOURCE | 封裝 `HotSectorStockPool.build()`，取得熱門板塊股票池 |
| T1AutoSellNode | TRADE_ACTION | 超短線 T+1 強制賣出，對齊 Hardcode 自動賣出邏輯 |
| CrossTabPublishNode | TRADE_ACTION | 將結果發布至 CrossTabBus，供跨 Tab 模組消費 |

> **設計變更**：原計劃的 `UserStockSourceNode` 已合併到 `CandidatePoolNode` 內部（`getUserStockCodes()` 方法讀取自選股 + 搜索歷史），避免增加不必要的 DAG 節點和類型轉換風險。

### 11.4 XML Pipeline 影響範圍（已實現）

| Pipeline | 新增節點 | 節點總數 |
|----------|---------|---------|
| ultra_short | +CandidatePoolNode, +T1AutoSellNode | 9 → 11 |
| short_term | +CandidatePoolNode, +ZiplineFactorNode, +CrossTabPublishNode | 14 → 17 |
| mid_term | +SectorStockPoolNode, +CandidatePoolNode, +CrossTabPublishNode | 17 → 20 |
| long_term | +CandidatePoolNode | 11 → 12 |

### 11.5 驗證標準

當 `useDagPipeline=true` 時，DAG 路徑在四個週期（ultra_short / short_term / mid_term / long_term）下，必須產出：

1. **相同股票池**（stock pool）
2. **相同信號**（signals）
3. **相同訂單**（orders）

與 Hardcode 路徑完全一致，方視為對齊完成。

### 11.6 實作檔案

所有新增節點集中於 `HardcodeCompatNodes.kt`，作為 Hardcode 相容層的統一入口，便於日後隨 Hardcode 移除而一併清理。

### 11.7 實現摘要

| 檔案 | 變更類型 | 內容 |
|------|---------|------|
| `HardcodeCompatNodes.kt` | 新建 | 5 個節點：CandidatePoolNode, ZiplineFactorNode, SectorStockPoolNode, T1AutoSellNode, CrossTabPublishNode |
| `MidTermPipelineNodes.kt` | 修改 | PositionMergeNode 訂單狀態 `PENDING` → `BUYING` |
| `NodeRegistry.kt` | 修改 | 註冊 5 個新 module：`candidate_pool`, `zipline_factor`, `sector_stock_pool`, `t1_auto_sell`, `crosstab_publish` |
| `ultra_short_pipeline.xml` | 修改 | +n_cand, +n_t1sell（9→11 nodes） |
| `short_term_pipeline.xml` | 修改 | +n_cand, +n_zipline, +n_crosstab（14→17 nodes） |
| `mid_term_pipeline.xml` | 修改 | +n_sector_pool, +n_cand, +n_crosstab（17→20 nodes） |
| `long_term_pipeline.xml` | 修改 | +n_cand（11→12 nodes） |
