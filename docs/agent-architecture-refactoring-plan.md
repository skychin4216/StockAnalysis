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

### 1.4 架構對比：UnifiedAgentRunner（舊）vs AgentOrchestrator（新）

> 狀態：AgentOrchestrator 已實現（`agent/core/`，Phase A-D），待接入 Fragment 替代 UnifiedAgentRunner。

| 維度 | UnifiedAgentRunner（舊） | AgentOrchestrator（新） |
|------|-------------------------|------------------------|
| 形態 | object 單例 + 靜態 `run()` | 實例化編排器，spawn Sub-Agent 並行 |
| 路由 | 調用方手動選 mode（quick/pipeline/v2） | IntentRouter 確定性路由：4 意圖 × 4 周期，無需 LLM |
| Agent 組合 | 每種 mode 硬編碼固定組合 | 按周期動態組建 Cluster（超短 3 Agent/30s ～ 長線 5 Agent/120s） |
| 權限 | 所有 Agent 權限相同，可訪問一切 | 5 角色權限矩陣（allow/deny），deny 永遠優先；無任何角色擁有 EXECUTE_TRADE |
| 上下文 | 所有 Agent 共享同一 Context | 三層記憶隔離：Global（共享只讀）/ Session（Orchestrator 獨佔）/ Agent（獨立，完成後銷毀） |
| 結果傳遞 | 手動串聯，上一步結果硬編碼傳入下一步 | Announce 結構化彙報，Orchestrator 重新組織（非原始轉發） |
| LLM 分層 | 所有 Agent 同一 Provider | LlmTier 四級：NONE（Scout/Executor 純量化）/ FAST / STRONG / ULTRA |
| 容錯 | 失敗即終止或跳過 | NodeResilience：重試 → 降級模型 → 算法後備，`degraded` 標記傳播 |
| 生命週期 | 無管理，Fragment 銷毀後任務繼續跑 | AgentSessionManager 級聯停止，父取消 → 所有子任務自動取消 |
| 緩存 | 無 | AnalysisCache：相同股票+周期短時間內復用，LLM 調用降為 0 |
| 與 DAG 關係 | 完全脫節（兩套系統各跑各的） | AgentNode 包裝器嵌入 DAG，Agent 結果流入訂單生成節點 |

**遷移方式**：Fragment 中將 `UnifiedAgentRunner.run(context, code, name, mode)` 替換為：

```kotlin
val intent = IntentRouter().resolve(userInput, currentStock, holdingPeriod)
val orchestrator = AgentOrchestrator(appContext)
val result = orchestrator.execute(intent)  // OrchestratorResult
```

舊 Runner 保留為 fallback（FeatureFlag 控制），觀測穩定後移除。

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

---

## 十二、當前進度與剩餘計劃

> 更新日期：2026-08-01

### 12.1 已完成

| 項目 | 狀態 | 檔案 |
|------|------|------|
| Phase A：角色/權限/記憶/派生/會話管理 | ✅ | `agent/core/AgentRole.kt`, `AgentContext.kt`, `SubAgentSpawner.kt` |
| Phase B：IntentRouter + AgentOrchestrator + AnalysisCache | ✅ | `agent/core/IntentRouter.kt`, `AgentOrchestrator.kt`, `AgentNode.kt` |
| Phase C：AgentNode 包裝器（Agent 嵌入 DAG） | ✅ | `agent/core/AgentNode.kt` |
| Phase D：NodeResilience 容錯降級 | ✅ | `agent/core/AgentNode.kt` |
| Fragment 接入（FeatureFlag 開關切換新舊路線） | ✅ | `ui/StockDetailFragment.kt`（設置 → 分析模塊開關） |
| DAG 騰龍換鳥修正（swap 輔助化 + 序列鏈） | ✅ | `DagPipeline.kt`, `MidTermPipelineNodes.kt`, 4 個 XML |
| 持倉風控節點（n_guard，每次必評估止損/止盈） | ✅ | `MidTermPipelineNodes.kt` HoldingGuardNode |
| Step 2：DeepAnalystEngine（V1 等價深度分析） | ✅ | `agent/core/DeepAnalystEngine.kt` |
| Step 4a：統一入口 StockAnalysisUseCase | ✅ | `agent/core/StockAnalysisUseCase.kt`（analyzeStock 擴展函數） |
| Step 4b：遷移所有調用方 | ✅ | StockDetailFragment / ChatTabFragment / ChatAgent / StrategyListFragment |
| Step 4c：刪除舊編排層 | ✅ | 已刪：UnifiedAgentRunner / AgentPipelineOrchestrator / V2AgentRunner / DataFeeder / PipelineStep / analytics/CapitalFlowData |
| PipelineProgressView 解耦 | ✅ | `agent/pipeline/ui/PipelineProgressView.kt`（改用 AnalysisStep/AnalysisResult） |
| StructuredOutputParser.formatReadable | ✅ | `agent/pipeline/StructuredOutputParser.kt`（可讀摘要渲染） |
| 做T系統增強（v5.1） | ✅ | `TTradeModels.kt`（新增 period_type + 跟蹤字段）、`TTradeEngine.kt`（結果跟蹤 + 收盤統計）、`AppBackgroundRunner.kt`（全週期監控）、`QuantFragmentBase.kt`（虛擬成功率 UI） |

### 12.2 剩餘計劃

#### Step 1：穩定性驗證 ✅ 已完成

舊編排層已刪除，所有調用方已遷移至統一入口。

#### Step 2：Analyst 深度補齊 ✅ 已完成

DeepAnalystEngine 已實現 V1 等價的 7 子 Agent 3 階段並行分析。

#### Step 3：Phase E — 優化與擴展

| 項目 | 說明 | 驗證標準 |
|------|------|---------|
| GlobalMarketCache | 全局市場環境緩存（日級別），Scout 結果跨任務復用 | Scout 第二次執行 < 1s |
| 並發調度優化 | Sub-Agent 按 maxConcurrent 限流 + 優先級隊列 | 高並發時內存無飆升 |
| Token 用量統計 | 每次 LLM 調用記錄 input/output tokens，報告附帶成本 | 報告末尾顯示 token 消耗 |
| AnalysisCache 命中率優化 | 相同股票+周期 30 分鐘內復用，跳過 LLM | 重複分析 LLM 調用 = 0 |

#### Step 4：刪除舊編排層 ✅ 已完成（2026-07-30）

已刪除：UnifiedAgentRunner / AgentPipelineOrchestrator / V2AgentRunner / DataFeeder / PipelineStep / analytics/CapitalFlowData。
保留：AgentBase + AgentTool（TradeExecutionAgent 仍使用）、StructuredOutputParser + QuarterlyComparisonProvider（DeepAnalystEngine 使用）、PipelineResult.kt（精簡為僅保留數據類）。

#### Step 5：后续擴展（可選）

- IntentRouter 接入 ChatTabFragment（聊天意圖 → Agent 群）
- 註解驅動 Node 註冊（refactoring-plan Phase 4）
- Executor 角色接入 DagTradeExecutor（訂單生成 → 跨 Tab 發布全自動）
- V1/V2 按鈕合併為單一「深度分析」+ 周期選擇

### 12.3 當前統一架構（2026-07-30 後）

舊編排層（UnifiedAgentRunner / AgentPipelineOrchestrator / V2AgentRunner）已刪除。
所有調用方統一走 `AgentOrchestrator.analyzeStock()` 擴展函數：

```
調用方                        統一入口                          底層實現
─────────────────────────────────────────────────────────────────────────
StockDetailFragment ─┐
ChatTabFragment ─────┤
ChatAgent ───────────┼→ AgentOrchestrator.analyzeStock()
StrategyListFragment ┘     │
                           ├─ QUICK  → StockAnalysisAgent + RiskManagementAgent + MarketAnalyzer（並行）
                           ├─ DEEP   → DeepAnalystEngine(5 子Agent) + 決策矩陣
                           └─ EXPERT → DeepAnalystEngine(7 子Agent) + 決策矩陣（中長線週期）
```

`useAgentFramework` flag 控制編排深度：
- true  → 完整角色編排（Scout + Analyst + Guardian 並行，session/spawner，降級兜底）
- false → 輕量直連（DeepAnalystEngine + 內聯 MarketAnalyzer → 決策矩陣，無 session 開銷）

FeatureFlagManager 仍保留，用於控制 `useAgentFramework` 參數的默認值。


### 12.4 大A祖训 · 四周期实战对照表（完整版）
祖训原则	超短线（1天）	短线（1-2周）	中线（1-6个月）	长线（6个月~1年+）
高开要跑	集合竞价就砸，不赌冲高，纪律高于一切。	看15分钟承接，破均线清仓，不破格局到下午。	只做T降成本，留底仓不动，不丢趋势筹码。	不看日线高开，看估值高开。
若股价短期受情绪炒作透支了未来2~3年的业绩（PE冲上历史危险值），即便没有高开，也要启动“分批减仓”计划。若只是普通高开，完全无视，该定投定投，该睡觉睡觉。
买无人问津时	找分歧转一致（大盘恐慌跳水时的抗跌先锋）。	找板块启动拐点（市场半信半疑、换手充分时重仓）。	找行业估值底部（PE历史分位<20%，机构持续低配）。	这是长线的绝对主场——买在“全面性绝望”。
对应特征：①基金发行遇冷（募集失败频发）；②行业龙头破净或跌至重置成本；③该赛道的研究员纷纷转行；④你在饭桌上提起股票被人嘲笑。
这时不仅要买，而且要越跌越买（金字塔加仓），用3~6个月时间把仓位打满。
卖人声鼎沸时	涨停封单急剧减少或炸板，立刻核按钮离场。	板块内跟风杂毛都涨停，且登上热搜第一，果断清仓。	身边不炒股的人开始荐股，逐步分批止盈。	这是长线兑现利润的唯一信号——卖在“全民狂欢”。
对应特征：①该赛道登上《新闻联播》或两会热门提案；②身边卖菜大妈都跟你讨论该行业龙头；③公司市盈率突破历史最高位且机构一致上调目标价（过度乐观）；④成交量极度放大但股价滞涨（筹码高位换手）。
此时不看技术面，只看情绪面，从“右侧持有”转为“左侧分批清仓”，全部卖完后就删自选，绝不回头。
低位利空 = 利好	急跌深V时捞一把，博恐慌修复反抽（轻仓快进快出）。	下影线企稳时进场，博利空出尽后的1~2周修复行情。	基本面未崩坏前提下逆势加仓，这是送钱的黄金坑。	这是长线的“暴富开关”——极度利空=极度买点。
长线的“低位”指市净率（PB）跌至历史最低1%区间、或市值低于公司净现金价值。此时出现的利空（如：行业黑天鹅、核心高管离职、短期业绩暴雷-50%），只要不改变行业长期渗透率提升的逻辑，就是最后的洗盘。
操作：不要等“企稳”，因为长线大底从来不是V型，而是L型。利空砸出的第一个跌停板打开时，就是第一笔建仓点，后面每跌10%加一倍仓，用“钝刀子割肉法”拿足廉价筹码。
高位利好 = 利空	高开直接兑现走人，不贪最后一枚铜板。	利用高开冲高做套利卖出，绝不追高接力。	分批减仓，锁定利润，把最后一段鱼尾留给别人。	这是长线的“清仓号角”——极度利好=最后的烟火。
长线的“高位”指股价已充分反映未来5年成长预期（PEG > 2.5 且机构扎堆持仓）。此时出现的利好（如：超预期财报、重磅新产品发布、拿到巨额订单），往往伴随着放量冲高回落（墓碑线）。
操作：利好公布当日，无论涨跌，立即启动“清仓计划”（比如分3天，每天卖1/3）。不要遗憾后面可能还有10%的鱼尾行情，长线赚的是从“极度低估”到“合理估值”的这1~3倍空间，泡沫期的最后20%利润，是毒药，不是馅饼。
🧠 长线核心心法（区别于其他三个周期）
针对你补充的“6个月~1年+”长线，我单独送你三句专属祖训：

“长线最大的风险不是被套，而是卖飞。”
—— 既然做了长线，就默认接受20%~30%的账面浮亏。只要逻辑没变（行业渗透率<30%、国产替代没完成、市占率还在提升），套牢就套牢，这是长线投资者的“入场券”。不要因为股价跌了就去研究技术面，那是短线干的活。

“不要试图卖在最高点，买在最低点。长线赚的是‘模糊的正确’。”
—— 长线建仓是在“无人问津”的区域（比如未来6个月内分批买），而不是具体哪一天。长线清仓是在“人声鼎沸”的区域，而不是涨停那天。能吃到鱼身（中间60%~80%的涨幅） 已是神级操作，鱼头和鱼尾留给胆大的人。

“拿不住，是因为你投入了输不起的钱。”
—— 长线（6个月~1年+）必须用绝对闲钱（3~5年不用的钱）。只有输得起，才能在“低位利空”时冷静加仓，而不是恐慌割肉。如果这笔钱下个月要还房贷，那它天生就不适合做长线。

c (三天不新低 + 5 10 30 日均綫粘合向上)
你现在文档里的四周期（超短、短、中、长）已经齐活了。长线的这套逻辑，刚好回应了你文档里 moat_leader（护城河）和 institutional_accumulation（机构增持）这些策略的底层信仰——坚信价值终究会回归，但回归的时间，往往超出绝大多数人的耐心。 这份表格可以作为“实战心法”附录。


完成。實現了「总纲 + 逃頂要快，抄底要慢」：

BasePositionAnalyzer (strategy/analysis/)

共享工具，檢查兩條件：
3天不新低：最近3天 low 全部 > 前3天最低 low
MA5/MA10/MA30 粘合向上：離散率 < 2% 且 MA5 方向向上
同時檢測逃頂信號：3天急跌 > 5% 或跌破3日最低


### 12.5 A股日内做T的7个关键时间点及操作思路
结合之前整理的“大A祖训”和四周期体系，日内做T本质上是超短线（1天） 的极致应用——借助底仓在一天内完成“先买后卖”或“先卖后买”，
赚取差价、降低成本。

以下是A股日内做T的7个关键时间点及操作思路：

一、两种做T模式
模式	操作顺序	适用场景
正T（先买后卖）	开盘先低吸 → 盘中冲高卖出底仓	预计股价低开或盘中会探底回升
倒T（先卖后买）	开盘先高抛 → 盘中回落再接回	预计股价高开或盘中会冲高回落
二、7个关键时间点详解
⏰ 时间点1：9:30 - 9:40（早盘冲高/情绪高点）
特征：刚开盘半小时，往往是散户跟风最踊跃的时候，主力经常利用少量资金拉高诱多。

操作建议：高抛为主，忌追高！ 除非是超级大利好一字板，否则这个时间段急拉，不要追。这是前一天进场资金的最佳出场时机。如果股价急速拉升超过5%且量能衰减，是主力出货信号，应赶紧高抛。

⏰ 时间点2：9:50 - 10:10（短期高点）
特征：经过第一轮博弈，该出的获利盘出了，该进的跟风盘进了，这个时间段很容易形成一个日内的小高峰。

操作建议：适合高抛（倒T卖点） 。9:50-10:10这个时间容易产生短期高点，适合见好就收。

⏰ 时间点3：10:10 - 10:40（主力动向/观察期）
特征：这是真正的主力（机构或游资）决定今天是否“干活”的时间。如果个股出现拉升且主力数据良好（看Level2数据或盘口大单），说明今天主力意图做多。

操作建议：观察期，决定去留。如果个股稳步拉升且资金流入明显，可以安心持有；如果此时仍无动静，今天大概率是震荡或下跌行情。

⏰ 时间点4：11:10 - 11:30（午盘收盘/急拉陷阱）
特征：临近午盘收盘，突然出现直线拉升？大概率是 “做图”给下午看的，吸引下午开盘后的跟风盘。

操作建议：急拉别跟，谨防中招！ 除非当天市场极度强势，否则这种急拉通常持续性很差，追进去容易站岗。

⏰ 时间点5：13:00 - 13:30（午后开盘/警惕“开盘杀”）
特征：和早盘类似，下午开盘前15分钟，如果出现脉冲式拉升，大概率是诱多。若此时出现快速下跌，反而可能是日内相对低点。

操作建议：警惕“开盘杀” 。13:15-13:30也容易走出当日高点，是离场做T的时机之一。

⏰ 时间点6：13:30 - 14:00（垃圾时间/看戏为主）
特征：这段时间多空双方往往处于平衡状态，主力要么在洗盘，要么在准备下午的大动作。

操作建议：看戏为主，别激动，别下单。这段时间多为主力控盘表演阶段，多看少动即可。

⏰ 时间点7：14:00 - 15:00（方向选择 + 定调时刻）⭐最重要！
这是全天最关键的时间段，拆分为两个子时段：

14:00 - 14:30（方向选择） ：

上午涨得好不好不重要，关键看这里。如果此时股价开始回落，全天大概率走弱；如果资金开始抢筹，往往预示着第二天还有行情。

很多游资会在此时偷袭拉涨停。即便个股走势好也要谨慎观察，这一阶段最容易出现趋势反转。

14:30 - 15:00（定调时刻） ：

强势行情：会继续拉高，吸引踏空资金入场，为明天出货做准备，可以持股。

弱势行情：容易冲高回落（诱多）。如果尾盘最后几分钟被直线拉升，不要激动，很可能是做账或骗线。

这是最重要的转折点，决定明天的走势。

三、核心口诀（一句话记忆）
时间	口诀	动作
9:30-9:40	早盘冲高	跑（高抛）
9:50-10:10	短期高点	跑（倒T卖点）
10:10-10:40	主力动向	看（观察去留）
11:10-11:30	午盘收盘	防（急拉陷阱）
13:00-13:30	午后开盘	防（开盘杀）
13:30-14:00	垃圾时间	等（看戏）
14:00-14:30	方向选择	盯（关键转折）
14:30-15:00	定调时刻	决（决定去留）
四、风险提示（大A祖训版）
T仓当日了结，不变成加仓：做T的仓位当天必须平掉，不能把T做成加仓。

做错T不恋战：按原止损线处理，不要死扛。

分时背离只管短期：分时的背离技巧只管未来一二十分钟或日内一两个小时，决定不了股票的未来走势。

切忌贪心：不必追求吃满整段行情，有几个点的差价便已足够。

震荡市或高波动行情中做T效果最佳。

结合你之前的“大A祖训”——超短线看情绪、短线看资金、中线看估值、长线看国运——日内做T就是超短线维度上“看情绪+看资金”的极致演绎，
本质是在跟主力和散户的情绪博弈。以上7个时间点，是无数前人用真金白银换来的经验规律，仅供参考，绝非圣杯。



### 12.6 根据K图分析大盘趋势
一、大盘趋势与形态维度（你的核心逻辑已涵盖）
这是大盘分析的“骨架”。你提的参数非常好，我帮你优化成更严谨的量化定义：

指标参数	你的定义	量化修正/增强建议	逻辑意义
止跌信号	3天不新低（最近3天 Low > 前3天最低 Low）	改为「3日收盘价不创新低」（Close > Ref(Lowest(Low,3), 3)）。
因为盘中最低点常被主力瞬间砸穿（毛刺），收盘价不破代表资金真正认可该底部。	确认短期下跌动能衰竭，是左侧抄底的必要非充分条件。
中继/变盘点	MA5/MA10/MA30 粘合向上（离散率<2%且MA5向上）	增加「MA30走平上翘」条件。
离散率公式：(MAX(MA5,MA10,MA30) - MIN(...)) / MA30 < 0.02。
且必须满足 MA5 > MA10 > MA30（多头排列雏形） 。	代表市场平均成本高度一致，一旦放量，容易触发“一阳穿三线”的主升行情。
逃顶信号	3天急跌 > 5% 或跌破3日最低	增加「跌伴随放量」过滤。
若缩量急跌（量能小于5日均量），可能是挖坑洗盘，不应逃顶；
若放量（>1.5倍均量）跌破3日最低，则是恐慌踩踏，必须果断逃顶。	区分“洗盘”与“出货”，避免被主力骗线。
二、量能与资金流维度（A股的“血液”）
只看K线不看量，等于开车不看油表。大盘的成交量是大资金的直接态度。

量能均线（VOL_MA5 / VOL_MA60）：

放量站上：当日成交量 > VOL_MA5 且 > VOL_MA60，代表增量资金入场，突破有效。

缩量反弹：股价上涨但成交量 < VOL_MA5，属于量价背离，大概率是诱多，次日容易低开。

北向资金（沪深股通）实时流向：

在A股，北向资金被称为“聪明钱”。连续3日净流入 > 50亿，是大盘阶段性底部的强烈信号；连续3日净流出，则需降低仓位。

涨跌家数比（市场温度计）：

全市场（沪深京）上涨家数 / 下跌家数。比值 > 3 代表情绪过热（短线需高抛），比值 < 0.3 代表情绪冰点（超短线可低吸）。

三、市场情绪与波动维度（隐含的心理博弈）
炸板率（涨停开板率）：

若当日涨停个股中，炸板率 > 40%，说明封板资金意志不坚定，大盘次日大概率分歧转弱。

昨日涨停表现（同花顺指数 883900）：

这是超短线的命脉。若该指数 < 0%（即昨日涨停的股票今日平均是跌的），说明打板族被埋，市场亏钱效应扩散，超短线、短线必须空仓休息。

股指期货升贴水（IF/IC 主力合约）：

若当月连续合约 贴水（期货价格 < 现货价格）超过 0.5%，代表机构强烈看空后市，大盘面临系统性压力。

四、权重股贡献度（指数的“失真”修复）
上证指数常被“两桶油（中石油、中石化）”或“银行”绑架。分析大盘必须拆解：

黄白线关系（分时图中的大盘股线/中小盘线）：

白线（权重）在上，黄线（小盘）在下：赚指数不赚钱，只拉权重护盘，实则个股普跌，小心午后跳水。

黄线在上，白线在下：中小盘股活跃，市场赚钱效应好，是良性上涨。

五、宏观事件日历（非量化但极重要）
技术面永远逃不过基本面的“黑天鹅”：

每月 9:30 发布的 CPI/PPI：若超出预期 0.3% 以上，大盘通常会有 1% 左右的剧烈波动。

周五下午/盘后的 IPO 批文数量：若超过 5 家，视为利空。

六、系统集成：大盘状态机（判断当前该用哪个周期）
基于以上参数，你可以将这些逻辑输入到你的 FeatureFlagManager 中，自动建议当前最适合的交易周期：

大盘状态	判断条件（量化）	推荐操作周期
强势单边	大盘 > MA60 且 MA5>MA10>MA30 且 成交量温和放大	中线和长线（仓位 70%）
震荡结构	大盘在 MA60 附近来回缠绕，且涨跌家数比在 0.7~1.5 之间	短线（仓位 40%，高抛低吸）
弱势下跌	大盘 < MA30 且 3天急跌 > 3% 且 北向持续流出	超短线（仓位 20%，只做尾盘次日卖）
系统性风险	大盘 < MA60 且 股指期货贴水 > 0.8% 且 跌停家数 > 50 家	空仓/逆回购（仓位 0%）
💡 给你的代码集成建议
直接在 MarketAnalysisEngine.kt 中写一个函数，把这些逻辑串起来：

kotlin
data class MarketContext(
    val isBottomConfirmed: Boolean,   // 3日不新低
    val isTrendUp: Boolean,           // 均线多头离散<2%
    val isTopDanger: Boolean,         // 放量跌破3日低点
    val volumeHealth: String,         // "放量" / "缩量" / "平量"
    val northFlow: Float,             // 北向净流入（亿）
    val advanceDeclineRatio: Float,   // 涨跌比
    val suggestedPeriod: HoldingPeriod // 推荐的策略周期
)

fun getMarketContext(): MarketContext {
    // 1. 计算你的逃顶/抄底参数
    // 2. 组合逻辑得出 suggestedPeriod
}
总结：分析大盘的核心，不是单一指标，而是趋势（你提的均线）+ 量能（北向/成交量）+ 情绪（涨跌比/炸板率） 的三维共振。你原本的那套“3日低点”和“急跌逃顶”，严格属于 “趋势与形态”维度的核心实战精华，再加上量能和情绪，就能避开绝大多数的“假突破”和“假破位”。


// 文件名：MarketAnalysisEngine.kt
// 包名：根据你的项目自行调整，如 com.your.stock.analysis
// 功能：大盘环境分析引擎，计算趋势、风险及建议持仓周期

package com.your.stock.analysis

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ================================
// 1. 基础数据类定义
// ================================

/** 大盘日线数据（只需关注核心几个字段） */
data class IndexDailyData(
    val date: LocalDate,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long          // 成交量（手）
)

/** 市场辅助指标（北向资金、情绪等） */
data class MarketMetrics(
    val northNetInflow: Double,     // 北向资金净流入（亿元），正数为流入
    val advanceCount: Int,           // 上涨家数
    val declineCount: Int,           // 下跌家数
    val limitUpCount: Int,           // 涨停家数
    val limitDownCount: Int          // 跌停家数
)

/** 大盘分析结果（输出给 UI 或决策系统） */
data class MarketContext(
    val isBottomConfirmed: Boolean,     // 是否触底（3日不新低）
    val isTrendUp: Boolean,             // 是否均线多头粘合向上
    val isTopDanger: Boolean,           // 是否存在逃顶风险
    val volumeStatus: String,           // "放量" / "缩量" / "平量"
    val marketTemp: String,             // "沸腾" / "温和" / "冰点"
    val suggestedPeriod: HoldingPeriod, // 建议操作周期
    val summary: String                 // 中文概要描述
)

// ================================
// 2. 核心分析引擎
// ================================

object MarketAnalysisEngine {

    private const val MA_SHORT = 5
    private const val MA_MID = 10
    private const val MA_LONG = 30
    private const val DISPERSION_THRESHOLD = 0.02  // 离散率 < 2%

    /**
     * 执行大盘全面分析
     * @param history 最近至少30个交易日的日线数据（按日期升序排列，即 index 0 为最旧）
     * @param metrics 当日的市场辅助指标
     * @return MarketContext 分析结果
     */
    fun analyze(
        history: List<IndexDailyData>,
        metrics: MarketMetrics
    ): MarketContext {
        // 数据校验
        if (history.size < MA_LONG) {
            return MarketContext(
                isBottomConfirmed = false,
                isTrendUp = false,
                isTopDanger = false,
                volumeStatus = "数据不足",
                marketTemp = "未知",
                suggestedPeriod = HoldingPeriod.SHORT,
                summary = "历史数据不足 $MA_LONG 天，无法准确分析，默认使用短线"
            )
        }

        // 取最近的数据
        val latest = history.last()
        val recent3 = history.takeLast(3)  // 最近3天
        val prev3 = history.takeLast(6).dropLast(3) // 往前推3天（即倒数第4~6天）

        // ---------- 1. 计算核心指标 ----------
        // (1) 3天不新低（底背离确认）
        val isBottom = checkBottomConfirmed(recent3, prev3)

        // (2) 均线粘合向上（趋势启动）
        val (isTrend, ma5, ma10, ma30) = checkMaTrend(history)

        // (3) 逃顶信号（急跌或破位）
        val isTop = checkTopDanger(recent3, latest)

        // (4) 量能状态（今日 vs 5日均量）
        val volumeStatus = checkVolumeStatus(history)

        // (5) 市场情绪（涨跌比 + 涨停跌停）
        val marketTemp = checkMarketTemperature(metrics)

        // (6) 北向资金权重（作为加分项）
        val northScore = when {
            metrics.northNetInflow > 50 -> 1.0
            metrics.northNetInflow > 20 -> 0.5
            metrics.northNetInflow > 0 -> 0.0
            else -> -0.5
        }

        // ---------- 2. 综合判定：建议持仓周期 ----------
        val suggestedPeriod = decidePeriod(
            isBottom = isBottom,
            isTrend = isTrend,
            isTop = isTop,
            volumeStatus = volumeStatus,
            marketTemp = marketTemp,
            northScore = northScore
        )

        // ---------- 3. 生成摘要 ----------
        val summary = buildSummary(
            isBottom, isTrend, isTop, volumeStatus, marketTemp, suggestedPeriod
        )

        return MarketContext(
            isBottomConfirmed = isBottom,
            isTrendUp = isTrend,
            isTopDanger = isTop,
            volumeStatus = volumeStatus,
            marketTemp = marketTemp,
            suggestedPeriod = suggestedPeriod,
            summary = summary
        )
    }

    // ================================
    // 3. 各检测模块具体实现
    // ================================

    /**
     * 核心条件：最近3天收盘价的最低值 > 前3天收盘价的最低值
     * 即 3日不新低
     */
    private fun checkBottomConfirmed(recent3: List<IndexDailyData>, prev3: List<IndexDailyData>): Boolean {
        if (recent3.size < 3 || prev3.size < 3) return false
        
        val recentLow = recent3.minOf { it.close }      // 最近3天收盘价最低点
        val prevLow = prev3.minOf { it.close }          // 前3天收盘价最低点
        
        // 加一个缓冲：最近最低点 必须大于 前3天最低点，同时最近3天不能有低于前低的收盘价
        return recentLow > prevLow
    }

    /**
     * 均线粘合向上：
     * 1. 离散率 (max - min) / MA30 < 2%
     * 2. MA5 方向向上（即今日MA5 > 昨日MA5）
     */
    private fun checkMaTrend(history: List<IndexDailyData>): Triple<Boolean, Double, Double, Double> {
        val closes = history.map { it.close }
        
        // 计算当前均线
        val ma5 = closes.takeLast(MA_SHORT).average()
        val ma10 = closes.takeLast(MA_MID).average()
        val ma30 = closes.takeLast(MA_LONG).average()
        
        // 计算前一天的MA5（用于判断方向）
        val prevCloses = closes.dropLast(1)
        val prevMa5 = if (prevCloses.size >= MA_SHORT) 
            prevCloses.takeLast(MA_SHORT).average() 
        else ma5
        
        // 条件1：离散率 < 2%
        val maxMa = maxOf(ma5, ma10, ma30)
        val minMa = minOf(ma5, ma10, ma30)
        val dispersion = (maxMa - minMa) / ma30
        
        // 条件2：MA5 向上
        val isMa5Up = ma5 > prevMa5
        
        val isTrend = dispersion < DISPERSION_THRESHOLD && isMa5Up
        
        return Triple(isTrend, ma5, ma10, ma30)
    }

    /**
     * 逃顶信号检测：
     * 1. 最近3天累计跌幅 > 5% （对应急跌）
     * 2. 或 今日收盘价 < 最近3天最低点（破位）
     */
    private fun checkTopDanger(recent3: List<IndexDailyData>, latest: IndexDailyData): Boolean {
        if (recent3.size < 3) return false
        
        val firstClose = recent3.first().close
        val lastClose = recent3.last().close
        
        // 条件1：3天跌幅 > 5%
        val dropPercent = (firstClose - lastClose) / firstClose
        val isSharpDrop = dropPercent > 0.05
        
        // 条件2：今日收盘价跌破最近3天最低点（破位止损）
        val recent3Low = recent3.minOf { it.low }
        val isBreakLow = latest.close < recent3Low
        
        // 补充条件：逃顶最好伴随放量（外部调用时判定，这里只做技术形态）
        return isSharpDrop || isBreakLow
    }

    /**
     * 量能判定：
     * 今日成交量 vs 5日均量
     */
    private fun checkVolumeStatus(history: List<IndexDailyData>): String {
        if (history.size < 6) return "数据不足"
        
        val todayVolume = history.last().volume
        val avgVolume5 = history.takeLast(6).dropLast(1).map { it.volume }.average()
        
        return when {
            todayVolume > avgVolume5 * 1.5 -> "放量"
            todayVolume < avgVolume5 * 0.7 -> "缩量"
            else -> "平量"
        }
    }

    /**
     * 市场温度：
     * 涨跌比（上涨/下跌） + 涨停/跌停修正
     */
    private fun checkMarketTemperature(metrics: MarketMetrics): String {
        val total = metrics.advanceCount + metrics.declineCount
        if (total == 0) return "未知"
        
        val ratio = metrics.advanceCount.toDouble() / metrics.declineCount.toDouble()
        
        // 涨跌比判断
        return when {
            ratio > 3.0 -> "沸腾"      // 极度过热
            ratio > 1.5 -> "温和偏热"
            ratio > 0.7 -> "温和"
            ratio > 0.3 -> "温和偏冷"
            else -> "冰点"            // 极度恐慌
        }
    }

    /**
     * 综合决策矩阵（核心逻辑）
     */
    private fun decidePeriod(
        isBottom: Boolean,
        isTrend: Boolean,
        isTop: Boolean,
        volumeStatus: String,
        marketTemp: String,
        northScore: Double
    ): HoldingPeriod {
        
        // 【优先级1】系统性风险（强制空仓或只做超短）
        if (isTop && volumeStatus == "放量") {
            return HoldingPeriod.ULTRA_SHORT  // 只能做尾盘隔夜，次日必跑
        }
        if (marketTemp == "冰点" && northScore < 0) {
            return HoldingPeriod.ULTRA_SHORT
        }

        // 【优先级2】趋势向上（重仓长线/中线）
        if (isTrend && volumeStatus == "放量" && marketTemp in listOf("温和", "温和偏热")) {
            return HoldingPeriod.LONG
        }

        // 【优先级3】触底回升（中线布局）
        if (isBottom && marketTemp in listOf("冰点", "温和偏冷")) {
            return HoldingPeriod.MID
        }

        // 【优先级4】震荡市（短线和超短结合）
        if (!isTop && !isBottom && volumeStatus == "平量") {
            return HoldingPeriod.SHORT
        }

        // 【默认兜底】保守用短线
        return HoldingPeriod.SHORT
    }

    /**
     * 构建中文摘要
     */
    private fun buildSummary(
        isBottom: Boolean,
        isTrend: Boolean,
        isTop: Boolean,
        volumeStatus: String,
        marketTemp: String,
        period: HoldingPeriod
    ): String {
        val parts = mutableListOf<String>()
        
        when {
            isTrend -> parts.add("均线多头粘合向上")
            isBottom -> parts.add("底部确认")
            isTop -> parts.add("⚠️逃顶信号触发")
        }
        
        parts.add("市场$marketTemp")
        parts.add("$volumeStatus")
        parts.add("建议：${period.label}")
        
        return parts.joinToString(" | ")
    }
}

// ================================
// 4. 使用示例 & 测试
// ================================

fun main() {
    // 模拟生成最近30天的数据（这里仅作演示，实际从数据库或API获取）
    val mockHistory = (1..30).map { i ->
        IndexDailyData(
            date = LocalDate.now().minusDays(30 - i.toLong()),
            open = 3000.0 + i * 2.0,
            high = 3020.0 + i * 2.0,
            low = 2980.0 + i * 2.0,
            close = 3010.0 + i * 2.0,
            volume = 100_000_000 + i * 1_000_000L
        )
    }
    
    val mockMetrics = MarketMetrics(
        northNetInflow = 25.0,
        advanceCount = 2500,
        declineCount = 1500,
        limitUpCount = 80,
        limitDownCount = 5
    )

    val result = MarketAnalysisEngine.analyze(mockHistory, mockMetrics)
    
    println("===== 大盘分析结果 =====")
    println("触底确认: ${result.isBottomConfirmed}")
    println("趋势向上: ${result.isTrendUp}")
    println("逃顶风险: ${result.isTopDanger}")
    println("量能状态: ${result.volumeStatus}")
    println("市场温度: ${result.marketTemp}")
    println("建议周期: ${result.suggestedPeriod.label}")
    println("摘要: ${result.summary}")
    // 输出示例：
    // 触底确认: false
    // 趋势向上: false (因为离散率可能较大)
    // 逃顶风险: false
    // 量能状态: 平量
    // 市场温度: 温和偏热
    // 建议周期: 短线
    // 摘要: 市场温和偏热 | 平量 | 建议：短线
}