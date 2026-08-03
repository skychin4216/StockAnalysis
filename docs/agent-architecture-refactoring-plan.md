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

总纲：长线看势，中线看价，短线看量，超短看情绪. 逃頂要快,抄底要慢 (三天不新低 + 5 10 30 日均綫粘合向上)
你现在文档里的四周期（超短、短、中、长）已经齐活了。长线的这套逻辑，刚好回应了你文档里 moat_leader（护城河）和 institutional_accumulation（机构增持）这些策略的底层信仰——坚信价值终究会回归，但回归的时间，往往超出绝大多数人的耐心。 这份表格可以作为“实战心法”附录。


完成。實現了「总纲 + 逃頂要快，抄底要慢」：

BasePositionAnalyzer (strategy/analysis/)

共享工具，檢查兩條件：
3天不新低：最近3天 low 全部 > 前3天最低 low
MA5/MA10/MA30 粘合向上：離散率 < 2% 且 MA5 方向向上
同時檢測逃頂信號：3天急跌 > 5% 或跌破3日最低