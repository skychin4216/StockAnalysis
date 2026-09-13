# 智能 Agent 綜合架構設計：借鑒 OpenCode + DeepSeek 的多智能體系統

> 日期：2026-07-29
> 狀態：設計中
> 關聯文檔：`agent-architecture-refactoring-plan.md`（技術實施方案）

---

## 一、設計理念

### 1.1 核心思想

借鑒 OpenCode 的多智能體架構和 DeepSeek 的高效推理能力，設計一套**雙軌制智能 Agent 系統**：

- **Multi-Agent Routing（多 Agent 路由）**：多個隔離的 Agent 並行運行，各管各的，按「場景」分流
- **Sub-Agents（子 Agent 派生）**：一個 Agent 在運行中派生子任務，後台並行執行，完成後自動彙報

這兩種機制解決完全不同的問題。Routing 解決「我有多重身份」，Sub-Agents 解決「我需要分身術」。

### 1.2 與現有系統的關係

```
現有架構（agent-architecture-refactoring-plan.md）:
  ├── 體系 A: UnifiedAgentRunner (Quick/Pipeline/V2 三模式)
  └── 體系 B: DAG Pipeline (Kahn 拓撲排序 + NodeRegistry)

目標架構（本文檔）:
  ├── Routing Layer: 按交易場景路由到不同 Agent 群
  │   ├── 超短線場景 → UltraShortAgentCluster
  │   ├── 短線場景   → ShortTermAgentCluster
  │   ├── 中線場景   → MidTermAgentCluster
  │   └── 長線場景   → LongTermAgentCluster
  ├── Sub-Agent Layer: 場景內並行派生子任務
  │   ├── Scout (偵察兵) → 市場環境/板塊輪動/資金流向
  │   ├── Analyst (分析師) → 基本面/技術面/資金面深度分析
  │   ├── Guardian (風控官) → 止損止盈/倉位控制/風險掃描
  │   └── Executor (執行器) → 訂單生成/持倉合併/跨Tab發布
  └── DAG Pipeline: 作為所有 Agent 的統一執行底座
```

### 1.3 設計原則
| 原則 | 說明 | 借鑒來源 |
|------|------|---------|
| 場景隔離 | 不同週期的分析互不干擾，各自獨立上下文 | OpenCode Multi-Agent Routing |
| 子任務並行 | 場景內多個分析維度並行推進，完成後彙總 | OpenCode Sub-Agents |
| 最小權限 | 每個 Agent 只擁有完成其職責所需的最小權限 | OpenCode allow/deny 模型 |
| 上下文隔離 | Sub-Agent 只注入必要的上下文，不繼承全部記憶 | OpenCode Session 隔離 |
| 動態路由 | 按用戶意圖和市場環境動態選擇 Agent 組合 | oh-my-opencode-slim |
| 成本分層 | 主 Agent 用強模型，Sub-Agent 用快速模型 | opencode-hive |
| 結構化彙報 | 子任務完成後以結構化格式彙報，非原始轉發 | OpenCode Announce 機制 |

---

## 二、核心架構：雙軌制 Agent 系統

### 2.1 整體架構圖

```
┌─────────────────────────────────────────────────────────────────────┐
│                        用戶意圖 (UserIntent)                         │
│    "分析這隻股票" / "超短線選股" / "風控掃描" / "深度分析這隻股票"    │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                    ┌──────────▼──────────┐
                    │   IntentRouter      │ ── 意圖識別 + 場景路由
                    │   (意圖路由器)       │
                    └──────────┬──────────┘
                               │
          ┌────────────────────┼────────────────────┐
          │                    │                    │
  ┌───────▼───────┐  ┌────────▼────────┐  ┌───────▼───────┐
  │ Quick Scan     │  │ Deep Analysis   │  │ Risk Check    │
  │ (快速掃描)     │  │ (深度分析)      │  │ (風控掃描)    │
  │               │  │                 │  │               │
  │ 純量化, 無LLM  │  │ 全鏈路 + LLM    │  │ 僅 Guardian    │
  └───────┬───────┘  └────────┬────────┘  └───────┬───────┘
          │                    │                    │
          │           ┌────────▼────────┐           │
          │           │ Orchestrator    │           │
          │           │ (場景編排者)     │           │
          │           └────────┬────────┘           │
          │                    │                    │
          │     ┌──────────────┼──────────────┐     │
          │     │              │              │     │
          │  ┌──▼───┐   ┌──────▼──────┐  ┌──▼───┐  │
          │  │Scout │   │  Analyst    │  │Guard-│  │
          │  │偵察兵 │   │  分析師     │  │ian   │  │
          │  │      │   │             │  │風控官 │  │
          │  │無 LLM│   │  調用 LLM   │  │調用LLM│  │
          │  └──┬───┘   └──────┬──────┘  └──┬───┘  │
          │     │              │              │      │
          │     └──────────────┼──────────────┘      │
          │                    │                     │
          │           ┌────────▼────────┐            │
          │           │  Executor       │            │
          │           │  (執行器)       │            │
          │           │  訂單+持倉+發布 │            │
          │           └────────┬────────┘            │
          │                    │                     │
          └────────────────────┼─────────────────────┘
                               │
                    ┌──────────▼──────────┐
                    │   DAG Pipeline      │ ── 統一執行底座
                    │   (Kahn 拓撲排序)    │
                    └─────────────────────┘
```

### 2.2 Multi-Agent Routing：按場景路由

Routing 的本質是**確定性路由**，基於「消息來源」而非「分析需求」。在股票分析場景中，消息來源對應的是**交易週期**和**用戶意圖類型**。

```
路由規則（優先級從高到低）:

1. IntentType.RISK_CHECK → 僅啟動 Guardian Agent
   (用戶只想看持倉風險，不需要選股)

2. IntentType.QUICK_SCAN → 純量化路徑，無 LLM
   (用戶想快速看市場概覽)

3. IntentType.DEEP_ANALYSIS + HoldingPeriod → 全鏈路 Agent 群
   3a. ULTRA_SHORT → UltraShortAgentCluster (3 Agent, 30s 超時)
   3b. SHORT       → ShortTermAgentCluster  (4 Agent, 60s 超時)
   3c. MID         → MidTermAgentCluster    (5 Agent, 90s 超時)
   3d. LONG        → LongTermAgentCluster   (5 Agent, 120s 超時)

4. IntentType.FOLLOW_UP → 僅啟動 Analyst Agent
   (用戶對已有分析結果追問)
```

**關鍵設計**：Routing 是基於意圖的確定性分流，不是「分析需求後選擇合適 Agent」。意圖識別由 `IntentRouter` 完成，規則明確，無需 LLM 參與。

### 2.3 Sub-Agents：場景內並行派生

在 Deep Analysis 場景中，Orchestrator 派生多個 Sub-Agent 並行執行：

```
Orchestrator (depth 0, 強模型)
 ├── spawn → Scout (depth 1, 無 LLM)
 │            ├── 市場環境感知 (MarketAnalyzer)
 │            ├── 板塊輪動檢測 (SectorRotationEngine)
 │            └── 資金流向掃描 (Level2DataProvider)
 │            → announce: MarketContext { direction, hotSectors, fundFlow }
 │
 ├── spawn → Analyst (depth 1, 強模型)
 │            ├── Agent 1: 基本面拐點價值選股
 │            ├── Agent 2: 產業鏈分析
 │            ├── Agent 3: 技術面分析
 │            ├── Agent 4: 資金面分析
 │            ├── Agent 5: 風控終審
 │            └── Agent D: 數據底座情報採集
 │            → announce: AnalysisResult { score, signals, riskLevel }
 │
 ├── spawn → Guardian (depth 1, 中等模型)
 │            ├── 持倉風險掃描
 │            ├── ATR 止損計算
 │            └── 倉位控制建議
 │            → announce: RiskReport { stopLoss, takeProfit, positionAdvice }
 │
 └── 彙總三個 announce → 生成最終報告 → Executor 執行
```

**Announce 機制**：每個 Sub-Agent 完成後，以結構化格式向 Orchestrator 彙報，Orchestrator 用自己的風格重新組織（不是原始轉發）。

---

## 三、Agent 角色體系與權限模型

### 3.1 四種核心角色

| 角色 | 英文 | 權限等級 | LLM | 並發數 | 超時 | 對應現有組件 |
|------|------|---------|-----|--------|------|------------|
| 編排者 | Orchestrator | 高 | 強模型 | 1 | 120s | UnifiedAgentRunner mode 選擇邏輯 |
| 偵察兵 | Scout | 低（只讀） | 無 | 3 | 15s | MarketAnalyzer, SectorRotationEngine |
| 分析師 | Analyst | 中 | 強模型 | 5 | 60s | StockAnalysisAgent, AgentPipelineOrchestrator |
| 風控官 | Guardian | 中 | 中等模型 | 2 | 30s | RiskManagementAgent |
| 執行器 | Executor | 高（寫入） | 無 | 1 | 10s | DagTradeExecutor, PositionMergeNode |

### 3.2 權限矩陣

```kotlin
enum class AgentPermission {
    // 數據讀取
    READ_MARKET_DATA,      // 行情、K線、財報
    READ_PORTFOLIO,        // 持倉、交易記錄
    READ_NEWS,             // 新聞 API
    READ_SECTOR_DATA,      // 板塊數據

    // LLM 調用
    CALL_LLM_STRONG,       // 強模型（高 max_tokens）
    CALL_LLM_FAST,         // 快速模型（低成本）

    // 寫入操作
    WRITE_TRADE_ORDER,     // 生成/修改交易訂單
    WRITE_WATCHLIST,       // 修改自選股
    PUBLISH_CROSSTAB,      // 跨 Tab 發布

    // 系統操作
    SPAWN_SUBAGENT,        // 派生子 Agent
    MODIFY_STRATEGY,       // 修改策略參數
    EXECUTE_TRADE,         // 執行交易（永遠拒絕 Agent 直接執行）
}
```

| 權限 | Orchestrator | Scout | Analyst | Guardian | Executor |
|------|:---:|:---:|:---:|:---:|:---:|
| READ_MARKET_DATA | ✅ | ✅ | ✅ | ❌ | ✅ |
| READ_PORTFOLIO | ✅ | ❌ | ✅ | ✅ | ✅ |
| READ_NEWS | ✅ | ✅ | ✅ | ❌ | ❌ |
| READ_SECTOR_DATA | ✅ | ✅ | ✅ | ❌ | ❌ |
| CALL_LLM_STRONG | ✅ | ❌ | ✅ | ❌ | ❌ |
| CALL_LLM_FAST | ✅ | ❌ | ❌ | ✅ | ❌ |
| WRITE_TRADE_ORDER | ❌ | ❌ | ❌ | ❌ | ✅ |
| WRITE_WATCHLIST | ✅ | ❌ | ❌ | ❌ | ✅ |
| PUBLISH_CROSSTAB | ❌ | ❌ | ❌ | ❌ | ✅ |
| SPAWN_SUBAGENT | ✅ | ❌ | ❌ | ❌ | ❌ |
| MODIFY_STRATEGY | ❌ | ❌ | ❌ | ❌ | ❌ |
| EXECUTE_TRADE | ❌ | ❌ | ❌ | ❌ | ❌ |

**核心安全約束**：
- **沒有任何 Agent 角色擁有 `EXECUTE_TRADE` 權限**。交易執行由 DAG 的交易節點統一處理，確保交易行為始終受量化規則約束
- **Scout 不能調用 LLM**，純量化計算，節省 token
- **Guardian 不能讀市場數據**，聚焦風控，避免被市場情緒干擾
- **只有 Orchestrator 擁有 `SPAWN_SUBAGENT` 權限**，防止子 Agent 遞歸派生導致失控

### 3.3 權限實現

```kotlin
data class AgentRole(
    val name: String,
    val displayName: String,
    val emoji: String,
    val permissions: Set<AgentPermission>,
    val denyPermissions: Set<AgentPermission> = emptySet(),  // deny 永遠優先
    val llmModel: LlmTier = LlmTier.NONE,
    val maxConcurrent: Int = 1,
    val timeoutMs: Long = 30_000,
    val maxSpawnDepth: Int = 0  // 0=不能派生, 1=可派生一層, 2=編排模式
)

enum class LlmTier {
    NONE,       // 不調用 LLM (Scout, Executor)
    FAST,       // 快速模型 (Guardian)
    STRONG,     // 強模型 (Analyst)
    ULTRA       // 最強模型 (Orchestrator 決策)
}

// 預定義角色
object AgentRoles {
    val ORCHESTRATOR = AgentRole(
        name = "orchestrator",
        displayName = "編排者",
        emoji = "🎯",
        permissions = setOf(
            READ_MARKET_DATA, READ_PORTFOLIO,
            CALL_LLM_STRONG, CALL_LLM_FAST,
            WRITE_WATCHLIST, SPAWN_SUBAGENT
        ),
        llmModel = LlmTier.ULTRA,
        maxConcurrent = 1,
        timeoutMs = 120_000,
        maxSpawnDepth = 1
    )

    val SCOUT = AgentRole(
        name = "scout",
        displayName = "偵察兵",
        emoji = "🔍",
        permissions = setOf(
            READ_MARKET_DATA, READ_SECTOR_DATA, READ_NEWS
        ),
        denyPermissions = setOf(CALL_LLM_STRONG, CALL_LLM_FAST, SPAWN_SUBAGENT),
        llmModel = LlmTier.NONE,
        maxConcurrent = 3,
        timeoutMs = 15_000,
        maxSpawnDepth = 0
    )

    val ANALYST = AgentRole(
        name = "analyst",
        displayName = "分析師",
        emoji = "📊",
        permissions = setOf(
            READ_MARKET_DATA, READ_PORTFOLIO, READ_NEWS, READ_SECTOR_DATA,
            CALL_LLM_STRONG
        ),
        denyPermissions = setOf(SPAWN_SUBAGENT, WRITE_TRADE_ORDER),
        llmModel = LlmTier.STRONG,
        maxConcurrent = 5,
        timeoutMs = 60_000,
        maxSpawnDepth = 0
    )

    val GUARDIAN = AgentRole(
        name = "guardian",
        displayName = "風控官",
        emoji = "🛡️",
        permissions = setOf(
            READ_PORTFOLIO, CALL_LLM_FAST
        ),
        denyPermissions = setOf(
            READ_MARKET_DATA, READ_NEWS, READ_SECTOR_DATA,
            SPAWN_SUBAGENT, WRITE_TRADE_ORDER
        ),
        llmModel = LlmTier.FAST,
        maxConcurrent = 2,
        timeoutMs = 30_000,
        maxSpawnDepth = 0
    )

    val EXECUTOR = AgentRole(
        name = "executor",
        displayName = "執行器",
        emoji = "⚡",
        permissions = setOf(
            READ_MARKET_DATA, READ_PORTFOLIO,
            WRITE_TRADE_ORDER, WRITE_WATCHLIST, PUBLISH_CROSSTAB
        ),
        denyPermissions = setOf(CALL_LLM_STRONG, CALL_LLM_FAST, SPAWN_SUBAGENT),
        llmModel = LlmTier.NONE,
        maxConcurrent = 1,
        timeoutMs = 10_000,
        maxSpawnDepth = 0
    )
}
```

---

## 四、記憶系統與上下文隔離

### 4.1 三層記憶架構

借鑒 OpenCode 的 Sub-Agent 上下文限制設計（Sub-Agent 只注入 AGENTS.md + TOOLS.md，不繼承人格和社交記憶），建立三層記憶：

```
┌─────────────────────────────────────────────────────────┐
│  Layer 1: Global Memory (全局記憶)                       │
│  ├── 市場環境報告 (GlobalMarketCache, 日級別)            │
│  ├── 用戶偏好 (風險承受度、資金量、偏好板塊)              │
│  └── 策略配置 (啟用的策略列表、參數)                      │
│  生命週期: App 進程級別，所有 Agent 共享只讀              │
├─────────────────────────────────────────────────────────┤
│  Layer 2: Session Memory (會話記憶)                      │
│  ├── 當前分析目標 (股票代碼、分析類型)                    │
│  ├── Orchestrator 的任務拆解結果                         │
│  └── 已完成 Sub-Agent 的 announce 結果                   │
│  生命週期: 一次分析任務，Orchestrator 獨佔                │
├─────────────────────────────────────────────────────────┤
│  Layer 3: Agent Memory (Agent 短期記憶)                  │
│  ├── 該 Agent 的工具調用結果                             │
│  ├── 推理過程中的中間結論                               │
│  └── 錯誤日誌和重試記錄                                 │
│  生命週期: Sub-Agent 執行期間，完成後銷毀                 │
└─────────────────────────────────────────────────────────┘
```

### 4.2 上下文隔離規則

| 記憶層 | Orchestrator | Scout | Analyst | Guardian | Executor |
|--------|:---:|:---:|:---:|:---:|:---:|
| Global Memory | 讀寫 | 只讀 | 只讀 | 只讀 | 只讀 |
| Session Memory | 讀寫 | ❌ | 只讀(指定slot) | 只讀(指定slot) | 只讀(指定slot) |
| Agent Memory | 獨立 | 獨立 | 獨立 | 獨立 | 獨立 |

**關鍵規則**：
- Sub-Agent **不能寫回 Session Memory**，只能通過 announce 機制向 Orchestrator 彙報
- Sub-Agent **只能讀取 Orchestrator 指定的 slot**，而非整個 Session Memory
- Global Memory 的寫入權限僅限 Orchestrator（如更新市場環境緩存）

### 4.3 AgentContext 實現

```kotlin
class AgentContext(
    val role: AgentRole,
    val taskId: String,
    val parentSession: AgentSession? = null  // null 表示頂層 Orchestrator
) {
    // Layer 3: Agent 短期記憶（獨立，不共享）
    private val toolResults = mutableMapOf<String, Any>()
    private val reasoningLog = mutableListOf<String>()
    private val errors = mutableListOf<AgentError>()

    // 從全局記憶讀取（只讀）
    fun getGlobalMarketReport(): MarketReport = GlobalMarketCache.getReport()

    // 從 Session 記憶讀取指定 slot（只讀，需權限）
    fun <T> readFromSession(slotName: String): T? {
        if (parentSession == null) return null
        if (!role.permissions.contains(READ_PORTFOLIO) &&
            !role.permissions.contains(READ_MARKET_DATA)) return null
        return parentSession.getSlot(slotName)
    }

    // 寫入 Agent 短期記憶
    fun recordToolResult(toolName: String, result: Any) {
        toolResults[toolName] = result
    }

    // 生成 announce 消息（結構化彙報）
    fun buildAnnounce(): AgentAnnounce {
        return AgentAnnounce(
            taskId = taskId,
            role = role.name,
            status = if (errors.isEmpty()) "completed" else "degraded",
            result = toolResults,
            errors = errors,
            tokenUsed = reasoningLog.size,  // 簡化
            durationMs = 0  // 由調用方填充
        )
    }
}

data class AgentAnnounce(
    val taskId: String,
    val role: String,
    val status: String,  // completed / degraded / failed / timed_out
    val result: Map<String, Any>,
    val errors: List<AgentError>,
    val tokenUsed: Int,
    val durationMs: Long
)
```

### 4.4 Announce 機制

借鑒 OpenCode Sub-Agent 的 Announce 設計，子 Agent 完成後以結構化格式彙報：

```
Scout 完成 → Announce:
  status: "completed"
  result: {
    "marketDirection": "BULLISH",
    "hotSectors": ["AI算力", "光通信", "半導體"],
    "fundFlow": { "northbound": +12.5億, "mainFlow": -3.2億 },
    "sentiment": { "limitUp": 32, "limitDown": 8, "continuation": 4 }
  }

Analyst 完成 → Announce:
  status: "completed"
  result: {
    "score": 78,
    "signals": [...],
    "riskLevel": "MEDIUM",
    "targetPrice": 25.6,
    "stopLoss": 22.1,
    "recommendation": "BUY",
    "stepAnalyses": { 1: "...", 2: "...", 3: "...", 4: "...", 5: "..." }
  }

Guardian 完成 → Announce:
  status: "degraded"  // LLM 超時，使用算法後備
  result: {
    "stopLoss": 22.1,
    "takeProfit": 28.0,
    "positionAdvice": "不超過 15%",
    "riskWarnings": ["ATR 偏高", "近 3 日量能萎縮"]
  }
  errors: [{ "type": "LLM_TIMEOUT", "fallback": "ATR_ALGORITHM" }]
```

Orchestrator 收到三個 Announce 後：
1. 用自己的風格重新組織為用戶可讀的報告
2. 將結構化數據存入 Session Memory 供 Executor 讀取
3. 標記 `degraded` 的結果降低依賴權重

---

## 五、智能任務路由與模型分層

### 5.1 IntentRouter：意圖識別

```kotlin
class IntentRouter {

    fun resolve(
        userInput: String,
        currentStock: String? = null,
        holdingPeriod: HoldingPeriod? = null
    ): UserIntent {
        val normalized = userInput.lowercase()

        return when {
            // 風控掃描：用戶想看持倉風險
            normalized.containsAny("風險", "止損", "止盈", "風控", "持倉安全") ->
                UserIntent(IntentType.RISK_CHECK, target = null)

            // 追問：對已有分析結果的後續提問
            normalized.containsAny("為什麼", "追問", "詳細", "解釋") && currentStock != null ->
                UserIntent(IntentType.FOLLOW_UP, target = currentStock)

            // 快速掃描：市場概覽
            normalized.containsAny("快速", "概覽", "掃描", "市場怎麼樣") ->
                UserIntent(IntentType.QUICK_SCAN, target = null)

            // 深度分析：指定股票或選股
            else -> {
                val period = holdingPeriod ?: inferPeriod(normalized)
                UserIntent(IntentType.DEEP_ANALYSIS, target = currentStock, period = period)
            }
        }
    }

    private fun inferPeriod(text: String): HoldingPeriod {
        return when {
            text.containsAny("超短", "日內", "打板") -> HoldingPeriod.ULTRA_SHORT
            text.containsAny("短線", "幾天") -> HoldingPeriod.SHORT
            text.containsAny("中線", "波段", "幾週") -> HoldingPeriod.MID
            text.containsAny("長線", "長期", "價值") -> HoldingPeriod.LONG
            else -> HoldingPeriod.SHORT  // 默認短線
        }
    }
}

data class UserIntent(
    val type: IntentType,
    val target: String? = null,       // 股票代碼，null 表示全市場掃描
    val period: HoldingPeriod? = null  // 交易週期
)

enum class IntentType {
    QUICK_SCAN,      // 快速掃描：純量化，無 LLM
    DEEP_ANALYSIS,   // 深度分析：全鏈路 Agent 群
    RISK_CHECK,      // 風控掃描：僅 Guardian
    FOLLOW_UP        // 追問：僅 Analyst
}
```

### 5.2 場景路由表

| 意圖類型 | 交易週期 | 啟動的 Agent | LLM 調用 | 預估耗時 | Token 消耗 |
|---------|---------|-------------|---------|---------|-----------|
| QUICK_SCAN | 任意 | Scout only | 無 | 5-10s | 0 |
| RISK_CHECK | 任意 | Guardian only | 1次(快速) | 10-15s | ~2K |
| FOLLOW_UP | 任意 | Analyst only | 1次(強) | 15-30s | ~4K |
| DEEP_ANALYSIS | ULTRA_SHORT | Scout+Analyst(3步)+Guardian+Executor | 3次(強)+1次(快) | 30-60s | ~16K |
| DEEP_ANALYSIS | SHORT | Scout+Analyst(5步)+Guardian+Executor | 5次(強)+1次(快) | 60-90s | ~24K |
| DEEP_ANALYSIS | MID | Scout+Analyst(6步)+Guardian+Executor | 6次(強)+1次(快) | 60-120s | ~28K |
| DEEP_ANALYSIS | LONG | Scout+Analyst(6步)+Guardian+Executor | 6次(強)+1次(快) | 90-150s | ~32K |

### 5.3 模型分層策略

借鑒 OpenCode「主 Agent 用強模型，Sub-Agent 用便宜模型」的思路，結合 DeepSeek 的模型矩陣：

| LlmTier | 推薦模型 | max_tokens | 適用場景 | 成本倍率 |
|---------|---------|-----------|---------|---------|
| ULTRA | DeepSeek-R1 / Claude Opus | 8192 | Orchestrator 決策彙總 | 10x |
| STRONG | DeepSeek-V3 / Claude Sonnet | 6144 | Analyst 深度分析 | 3x |
| FAST | DeepSeek-V3-Lite / GPT-4o-mini | 4096 | Guardian 風控掃描 | 1x |
| NONE | — | — | Scout/Executor 純量化 | 0 |

**動態模型選擇**：在熊市環境下，自動升級 Analyst 到 ULTRA（決策敏感度高）；在牛市環境下，降級到 FAST（信號明確，無需深度推理）。

```kotlin
class ModelSelector {
    fun selectModel(
        role: AgentRole,
        marketEnv: MarketEnvironment,
        userTier: UserTier = UserTier.STANDARD
    ): String {
        if (role.llmModel == LlmTier.NONE) return ""

        return when (role.llmModel) {
            LlmTier.ULTRA -> when {
                marketEnv == MarketEnvironment.BEARISH -> "deepseek-r1"
                userTier == UserTier.PREMIUM -> "claude-opus-4"
                else -> "deepseek-v3"
            }
            LlmTier.STRONG -> when {
                marketEnv == MarketEnvironment.BEARISH -> "deepseek-v3"  // 熊市升級
                marketEnv == MarketEnvironment.BULLISH -> "deepseek-v3-lite"  // 牛市降級
                else -> "deepseek-v3"
            }
            LlmTier.FAST -> "deepseek-v3-lite"
            LlmTier.NONE -> ""
        }
    }
}
```

### 5.4 LLM 響應緩存

```kotlin
class AnalysisCache(
    private val ttlMinutes: Int = 30,
    private val maxSize: Int = 100
) {
    // key: stockCode + analysisType + tradeDate + modelTier
    private val cache = object : LinkedHashMap<String, CacheEntry>() {
        override fun removeEldestEntry(eldest: Map.Entry<String, CacheEntry>): Boolean {
            return size > maxSize
        }
    }

    data class CacheEntry(
        val response: String,
        val timestamp: Long,
        val tokenCount: Int,
        val modelUsed: String
    )

    @Synchronized
    fun get(key: String): CacheEntry? {
        val entry = cache[key] ?: return null
        val age = (System.currentTimeMillis() - entry.timestamp) / 60000
        if (age > ttlMinutes) {
            cache.remove(key)
            return null
        }
        return entry
    }

    @Synchronized
    fun put(key: String, response: String, tokenCount: Int, model: String) {
        cache[key] = CacheEntry(response, System.currentTimeMillis(), tokenCount, model)
    }

    fun makeKey(stockCode: String, analysisType: String, tradeDate: String, tier: LlmTier): String {
        return "${stockCode}_${analysisType}_${tradeDate}_${tier.name}"
    }
}
```

**緩存策略**：
- 相同股票同一天內的相同分析類型，直接返回緩存
- 用戶主動刷新時繞過緩存（傳入 `forceRefresh = true`）
- 緩存命中時在結果中標記 `cached = true`，UI 可顯示「來自緩存」

---

## 六、Sub-Agent 派生與編排

### 6.1 Sub-Agent 生命週期

```
Orchestrator
    │
    ├── 1. spawn(scoutTask) ──────────────────────────┐
    │       │                                         │
    │       ├── 創建 AgentContext(role=SCOUT)         │
    │       ├── 注入只讀 Global Memory                │
    │       ├── 執行 Scout 邏輯 (純量化)              │
    │       └── buildAnnounce() ──────────────────┐   │
    │                                             │   │
    ├── 2. spawn(analystTask) ──────────────┐     │   │
    │       │                               │     │   │
    │       ├── 創建 AgentContext(role=ANALYST)    │   │
    │       ├── 注入 Global Memory + Scout 的 announce │
    │       ├── 調用 LLM (強模型)             │     │   │
    │       └── buildAnnounce() ──────────┐  │     │   │
    │                                     │  │     │   │
    ├── 3. spawn(guardianTask) ──────┐    │  │     │   │
    │       │                        │    │  │     │   │
    │       ├── 創建 AgentContext(role=GUARDIAN)    │   │
    │       ├── 注入持倉數據          │    │  │     │   │
    │       ├── 調用 LLM (快速模型)    │    │  │     │   │
    │       └── buildAnnounce() ──┐  │    │  │     │   │
    │                             │  │    │  │     │   │
    ├── 4. 等待所有 announce ◄────┴──┴────┴──┴─────┘   │
    │                                                   │
    ├── 5. 彙總三個 Announce                             │
    │       ├── 重新組織為用戶可讀報告                   │
    │       ├── 存入 Session Memory                      │
    │       └── 標記 degraded 結果                       │
    │                                                   │
    ├── 6. spawn(executorTask) ◄────────────────────────┘
    │       ├── 讀取 Session Memory 中的彙總結果
    │       ├── 生成交易訂單
    │       ├── 合併持倉
    │       └── 發布到 CrossTabBus
    │
    └── 7. 返回最終結果給 UI
```

### 6.2 Sub-Agent 派生 API

```kotlin
class SubAgentSpawner(
    private val globalMemory: GlobalMemory,
    private val cache: AnalysisCache,
    private val modelSelector: ModelSelector
) {
    /**
     * 派生子 Agent 並等待完成
     * 非阻塞版本返回 Deferred，可並行派生多個
     */
    fun <T> spawn(
        role: AgentRole,
        task: AgentTask,
        sessionMemory: AgentSession,
        inputSlots: List<String> = emptyList()
    ): Deferred<AgentAnnounce> {
        return CoroutineScope(Dispatchers.Default).async {
            val context = AgentContext(
                role = role,
                taskId = task.id,
                parentSession = sessionMemory
            )

            // 注入指定的 session slots
            inputSlots.forEach { slotName ->
                context.readFromSession<Any>(slotName)
            }

            try {
                withTimeout(role.timeoutMs) {
                    task.execute(context, cache, modelSelector)
                }
                context.buildAnnounce()
            } catch (e: TimeoutCancellationException) {
                AgentAnnounce(
                    taskId = task.id,
                    role = role.name,
                    status = "timed_out",
                    result = emptyMap(),
                    errors = listOf(AgentError("TIMEOUT", role.timeoutMs.toString())),
                    tokenUsed = 0,
                    durationMs = role.timeoutMs
                )
            } catch (e: Exception) {
                AgentAnnounce(
                    taskId = task.id,
                    role = role.name,
                    status = "failed",
                    result = emptyMap(),
                    errors = listOf(AgentError("EXCEPTION", e.message ?: "unknown")),
                    tokenUsed = 0,
                    durationMs = 0
                )
            }
        }
    }
}
```

### 6.3 級聯停止機制

借鑒 OpenCode 的級聯停止設計，當用戶取消分析或 Fragment 退出時：

```kotlin
class AgentSessionManager {
    private val activeSessions = ConcurrentHashMap<String, Job>()

    /**
     * 取消指定會話的所有 Agent（級聯停止）
     * - 停止 Orchestrator
     * - 級聯停止所有子 Agent
     * - 清理 Agent Memory
     */
    fun cancelSession(sessionId: String) {
        val job = activeSessions[sessionId] ?: return
        job.cancel(CancellationException("User cancelled session $sessionId"))
        activeSessions.remove(sessionId)
        // CoroutineScope 的 cancel 會自動級聯到所有子 coroutine
    }

    /**
     * 取消所有活躍會話
     * 用於 Fragment 銷毀時的清理
     */
    fun cancelAll() {
        activeSessions.values.forEach { it.cancel() }
        activeSessions.clear()
    }
}
```

---

## 七、容錯降級與自愈機制

### 7.1 三級降級策略

```kotlin
enum class FallbackStrategy {
    SKIP,           // 跳過，使用 null 輸出（非關鍵節點）
    CACHE,          // 使用上次成功的緩存結果
    DEFAULT_VALUE,  // 使用預設值
    RETRY_ONCE,     // 重試一次
    DEGRADE_LLM,    // 降級到更小/更快的模型
    ALGORITHM_BACKUP // 使用純算法後備（不調 LLM）
}

data class NodeResilience(
    val isCritical: Boolean = false,
    val fallback: FallbackStrategy = FallbackStrategy.SKIP,
    val retryCount: Int = 0,
    val timeoutMs: Long = 30_000,
    val algorithmBackup: (() -> Any)? = null  // 算法後備函數
)
```

### 7.2 各角色的降級矩陣

| 角色 | 失敗場景 | 降級策略 | 後備方案 |
|------|---------|---------|---------|
| Scout | 數據源超時 | RETRY_ONCE → DEFAULT_VALUE | 使用昨日緩存的市場環境 |
| Analyst (LLM) | LLM 超時 | DEGRADE_LLM → ALGORITHM_BACKUP | 強模型→快速模型→均值回歸評分 |
| Analyst (LLM) | LLM 返回格式錯誤 | RETRY_ONCE | 重試時加強 JSON 格式約束 |
| Guardian (LLM) | LLM 超時 | DEGRADE_LLM → ALGORITHM_BACKUP | 快速模型→ATR 止損算法 |
| Guardian (LLM) | LLM 不可用 | ALGORITHM_BACKUP | 純量化風控（ATR + 量比衰減） |
| Executor | DB 寫入失敗 | RETRY_ONCE | 記錄到內存隊列，延遲重試 |
| Orchestrator | 子 Agent 全部失敗 | CACHE | 使用上次成功的完整分析結果 |

### 7.3 LLM 節點降級鏈

```
LLM 調用失敗時的降級鏈:

1. 首次失敗 (timeout / network error)
   → 重試一次（換 Provider，如果有備選）
   → 超時時間縮短為原來的 50%

2. 再次失敗
   → 降級模型 (STRONG → FAST)
   → 簡化 Prompt（移除少用維度，保留核心分析）

3. 仍然失敗
   → 使用算法後備
   → Analyst: 均值回歸評分 + 技術指標打分
   → Guardian: ATR 止損 + 固定比例止盈

4. 輸出帶有 degraded = true 標記
   → 下游 Orchestrator 降低該結果的權重
   → UI 顯示「⚠️ 部分分析降級」提示
```

### 7.4 算法後備實現

```kotlin
class AlgorithmFallback {

    /**
     * Analyst 降級：純算法評分
     * 不調用 LLM，基於技術指標計算
     */
    fun analystFallback(stock: StockRealtime, history: List<DailySnapshot>): AgentAnnounce {
        val score = calculateAlgorithmScore(stock, history)
        val signals = generateTechnicalSignals(stock, history)

        return AgentAnnounce(
            taskId = "analyst_fallback",
            role = "analyst",
            status = "degraded",
            result = mapOf(
                "score" to score,
                "signals" to signals,
                "riskLevel" to if (score > 70) "LOW" else if (score > 50) "MEDIUM" else "HIGH",
                "recommendation" to when {
                    score >= 70 -> "BUY"
                    score >= 55 -> "WATCH"
                    score >= 40 -> "HOLD"
                    else -> "SELL"
                },
                "fallback" to "ALGORITHM"
            ),
            errors = listOf(AgentError("LLM_UNAVAILABLE", "Using algorithm fallback")),
            tokenUsed = 0,
            durationMs = 0
        )
    }

    /**
     * Guardian 降級：ATR 止損算法
     */
    fun guardianFallback(
        stock: StockRealtime,
        holdings: List<StrategyTradeOrder>,
        period: HoldingPeriod
    ): AgentAnnounce {
        val stopLossPrices = holdings.map { order ->
            val atr = ATRTrailingStop.checkFromDb(
                db, order.stockCode, stock.price,
                order.buyPrice * 1.05, period
            )
            order.stockCode to atr
        }.toMap()

        return AgentAnnounce(
            taskId = "guardian_fallback",
            role = "guardian",
            status = "degraded",
            result = mapOf(
                "stopLossMap" to stopLossPrices,
                "positionAdvice" to "ATR算法計算，LLM不可用",
                "fallback" to "ATR_ALGORITHM"
            ),
            errors = listOf(AgentError("LLM_UNAVAILABLE", "Using ATR algorithm")),
            tokenUsed = 0,
            durationMs = 0
        )
    }

    private fun calculateAlgorithmScore(stock: StockRealtime, history: List<DailySnapshot>): Int {
        var score = 50
        // 均線多頭排列 +15
        if (history.size >= 60) {
            val ma5 = history.takeLast(5).map { it.close }.average()
            val ma20 = history.takeLast(20).map { it.close }.average()
            val ma60 = history.takeLast(60).map { it.close }.average()
            if (ma5 > ma20 && ma20 > ma60) score += 15
        }
        // RSI 超賣反彈 +10
        // 量比放大 +10
        // 漲幅 +5
        return score.coerceIn(0, 100)
    }
}
```

---

## 八、與現有 DAG Pipeline 的整合

### 8.1 整合策略

現有 DAG Pipeline 作為**統一執行底座**，Agent 系統在其上層運行：

```
┌─────────────────────────────────────────────────┐
│           Agent 系統 (本文檔設計)                │
│  IntentRouter → Orchestrator → Sub-Agents       │
└───────────────────────┬─────────────────────────┘
                        │
                        │ Agent 的分析結果通過
                        │ AgentNode 包裝器流入 DAG
                        ▼
┌─────────────────────────────────────────────────┐
│           DAG Pipeline (現有系統)                │
│  n_market → n_pool → strategy_* → n_merge       │
│  → n_ai_pred → n_risk_guard → n_orders          │
│  → n_merge_pos → n_crosstab                     │
└─────────────────────────────────────────────────┘
```

### 8.2 AgentNode 包裝器

將 Agent 包裝為 DAG 節點，使 LLM 推理與量化計算在同一張圖中流動：

```kotlin
class AgentNode(
    private val role: AgentRole,
    private val spawner: SubAgentSpawner,
    private val inputMapper: (PipelineContext) -> AgentTask,
    private val outputMapper: (AgentAnnounce) -> Any
) : PipelineNode<Unit, Any> {

    override val nodeId: String = "agent_${role.name}"
    override val nodeName: String = role.displayName
    override val nodeType: NodeType = NodeType.AI_PREDICTION

    override suspend fun execute(context: PipelineContext, input: Unit): Any {
        val task = inputMapper(context)
        val session = context.getOrCreateSession()

        val announce = spawner.spawn(
            role = role,
            task = task,
            sessionMemory = session
        ).await()  // 在 DAG 節點中同步等待

        // 將結果存入 PipelineContext
        context.setStageOutput(nodeId, outputMapper(announce))

        // 如果降級了，記錄到 context 日誌
        if (announce.status == "degraded" || announce.status == "failed") {
            context.log(nodeId, "⚠️ ${role.displayName} 降級: ${announce.errors}")
        }

        return outputMapper(announce)
    }
}
```

### 8.3 整合後的數據流

```
用戶意圖 → IntentRouter.resolve()
         → 確定場景和 Agent 組合
         → Orchestrator 啟動

Orchestrator:
  1. spawn(Scout) → 市場環境數據
     └── 寫入 PipelineContext["market_context"]

  2. DAG Pipeline 啟動 (讀取 market_context):
     n_market_ctx → n_pool → candidate_pool → strategy_*
     → n_merge → 信號聚合

  3. spawn(Analyst) → 讀取 DAG 的 n_merge 輸出
     └── LLM 深度分析 → 寫入 PipelineContext["ai_analysis"]

  4. spawn(Guardian) → 讀取持倉數據
     └── LLM 風控 → 寫入 PipelineContext["risk_report"]

  5. DAG Pipeline 繼續:
     n_ai_pred(讀 ai_analysis) → n_risk_guard(讀 risk_report)
     → n_orders → n_merge_pos → n_crosstab

  6. spawn(Executor) → 讀取 DAG 的訂單輸出
     └── 執行交易 + 跨Tab發布
```

### 8.4 與 Hardcode 補齊節點的關係

Phase 0 已完成的 Hardcode 補齊節點在整合後的角色：

| 節點 | 整合後歸屬 | 說明 |
|------|-----------|------|
| CandidatePoolNode | Scout 預處理 | 候選池過濾屬於數據採集，歸 Scout |
| ZiplineFactorNode | Scout 預處理 | 因子預計算屬於數據準備，歸 Scout |
| SectorStockPoolNode | Scout 預處理 | 板塊精選池屬於市場掃描，歸 Scout |
| T1AutoSellNode | Executor 後處理 | T+1 賣出屬於交易執行，歸 Executor |
| CrossTabPublishNode | Executor 後處理 | 跨Tab發布屬於結果分發，歸 Executor |

---

## 九、場景化 Agent 配置

### 9.1 超短線 Agent 群

```kotlin
object UltraShortAgentCluster {
    fun build(): AgentCluster {
        return AgentCluster(
            period = HoldingPeriod.ULTRA_SHORT,
            orchestrator = AgentRoles.ORCHESTRATOR.copy(
                timeoutMs = 30_000,
                maxSpawnDepth = 1
            ),
            agents = listOf(
                // Scout: 市場情緒 + 漲停板分析
                AgentRoles.SCOUT.copy(
                    timeoutMs = 5_000,  // 超短線要求快速
                    maxConcurrent = 2
                ),
                // Analyst: 精簡為 3 步（資金面+技術面+情緒面）
                AgentRoles.ANALYST.copy(
                    timeoutMs = 20_000,
                    maxConcurrent = 3
                ),
                // Guardian: 緊止損
                AgentRoles.GUARDIAN.copy(
                    timeoutMs = 5_000,
                    maxConcurrent = 1
                ),
                // Executor: T+1 賣出 + 跨Tab
                AgentRoles.EXECUTOR.copy(
                    timeoutMs = 5_000
                )
            ),
            config = AgentClusterConfig(
                maxTotalConcurrent = 6,
                sessionTimeoutMs = 30_000,
                cacheTtlMinutes = 5  // 超短線緩存短
            )
        )
    }
}
```

### 9.2 中線 Agent 群

```kotlin
object MidTermAgentCluster {
    fun build(): AgentCluster {
        return AgentCluster(
            period = HoldingPeriod.MID,
            orchestrator = AgentRoles.ORCHESTRATOR.copy(
                timeoutMs = 90_000,
                maxSpawnDepth = 1
            ),
            agents = listOf(
                AgentRoles.SCOUT.copy(
                    timeoutMs = 15_000,
                    maxConcurrent = 3  // 板塊輪動+資金+基本面
                ),
                // Analyst: 完整 6 步
                AgentRoles.ANALYST.copy(
                    timeoutMs = 60_000,
                    maxConcurrent = 6
                ),
                AgentRoles.GUARDIAN.copy(
                    timeoutMs = 30_000,
                    maxConcurrent = 2
                ),
                AgentRoles.EXECUTOR.copy(
                    timeoutMs = 10_000
                )
            ),
            config = AgentClusterConfig(
                maxTotalConcurrent = 12,
                sessionTimeoutMs = 90_000,
                cacheTtlMinutes = 60  // 中線緩存長
            )
        )
    }
}
```

### 9.3 各週期 Agent 配置對照

| 配置項 | 超短線 | 短線 | 中線 | 長線 |
|--------|--------|------|------|------|
| Orchestrator 超時 | 30s | 60s | 90s | 120s |
| Analyst 並發數 | 3 | 5 | 6 | 6 |
| Analyst 步驟數 | 3(資金+技術+情緒) | 5(+基本面) | 6(+產業鏈) | 6(+深度基本面) |
| Guardian 超時 | 5s | 15s | 30s | 30s |
| 緩存 TTL | 5min | 15min | 60min | 120min |
| 最大並發 | 6 | 10 | 12 | 12 |
| LLM 調用次數 | 3強+1快 | 5強+1快 | 6強+1快 | 6強+1快 |
| 預估 Token | ~16K | ~24K | ~28K | ~32K |

---

## 十、實施路線圖

### Phase A：基礎設施（預估 1 週）

1. 實現 `AgentRole` / `AgentPermission` / `LlmTier` 枚舉和預定義角色
2. 實現 `AgentContext` 三層記憶架構
3. 實現 `AgentAnnounce` 結構化彙報機制
4. 實現 `SubAgentSpawner` 基礎派生 API
5. 實現 `AgentSessionManager` 會話管理和級聯停止
6. **驗證標準**：能 spawn 一個 Scout Agent 並收到 announce

### Phase B：路由與編排（預估 1.5 週）

1. 實現 `IntentRouter` 意圖識別
2. 實現 `ModelSelector` 動態模型選擇
3. 實現 `AnalysisCache` LLM 響應緩存
4. 實現 `Orchestrator` 編排邏輯（spawn + await + 彙總）
5. 實現四個週期的 `AgentCluster` 配置
6. **驗證標準**：用戶輸入「超短線分析」能正確路由並產出報告

### Phase C：DAG 整合（預估 1 週）

1. 實現 `AgentNode` 包裝器，將 Agent 嵌入 DAG
2. 改造 `PipelineContext` 支持 `AgentSession`
3. 實現 Scout 預處理結果寫入 PipelineContext
4. 實現 Executor 後處理讀取 DAG 訂單結果
5. **驗證標準**：Agent 分析結果能流入 DAG 的訂單生成節點

### Phase D：容錯與降級（預估 1 週）

1. 實現 `NodeResilience` 降級策略矩陣
2. 實現 `AlgorithmFallback` 算法後備
3. 實現 LLM 降級鏈（重試→換模型→算法後備）
4. 實現 `degraded` 標記傳播和 UI 提示
5. **驗證標準**：LLM 不可用時系統仍能產出降級結果

### Phase E：優化與擴展（預估 0.5 週）

1. 實現 `GlobalMarketCache` 全局市場環境緩存
2. 優化 Sub-Agent 並發調度策略
3. 添加 Token 用量統計和成本報告
4. **驗證標準**：相同股票連續分析兩次，第二次 LLM 調用為 0

---

## 十一、與 agent-architecture-refactoring-plan.md 的關係

| 維度 | refactoring-plan.md | 本文檔 (deepseek_opencode.md) |
|------|--------------------|-----------------------------|
| 定位 | 技術實施方案 | 架構設計藍圖 |
| 重點 | DAG 統一 + 類型安全 + 註解註冊 | 雙軌制 Agent + 權限模型 + 記憶系統 |
| Phase 1 | 統一 DAG 引擎 | Phase C: DAG 整合 |
| Phase 2 | 角色化 Agent | Phase A+B: 角色定義 + 路由編排 |
| Phase 3 | 智能路由 + 成本優化 | Phase B+D: 路由 + 降級 |
| Phase 4 | 註解驅動註冊 | 未涉及（後續擴展） |
| Phase 0 | Hardcode 補齊（已完成） | 整合為 Scout/Executor 預處理/後處理 |

**執行順序建議**：先完成 refactoring-plan.md 的 Phase 1（統一 DAG），再啟動本文檔的 Phase A-D。本文檔的設計依賴統一的 DAG 底座。

---

## 十二、附錄：OpenCode 生態啟發對照

### 12.1 OpenCode 核心概念映射

| OpenCode 概念 | 股票分析場景映射 | 本文檔對應設計 |
|--------------|----------------|--------------|
| Build Agent（完整權限，執行主力） | Analyst（分析主力，LLM 深度推理） | Analyst 角色 + STRONG 模型 |
| Plan Agent（權限受限，規劃為主） | Orchestrator（編排者，不直接分析） | Orchestrator 角色 + ULTRA 模型 |
| Subagent（獨立 session，後台執行） | Scout/Guardian（隔離上下文，並行執行） | Sub-Agent 派生 + AgentContext |
| allow/deny 工具權限 | AgentPermission 權限矩陣 | 第三章權限模型 |
| sessions_spawn（非阻塞派生） | SubAgentSpawner.spawn（Deferred） | 第六章派生 API |
| Announce 機制（結構化彙報） | AgentAnnounce（結構化結果） | 第四章 Announce 設計 |
| 級聯停止 | cancelSession（級聯取消） | 第六章級聯停止 |

### 12.2 OpenCode 插件生態啟發

| 插件 | 核心理念 | 本項目應用 |
|------|---------|-----------|
| OpenCode Ensemble | 多 Agent 並行 + 任務看板 | 四個週期 AgentCluster 並行分析 |
| oh-my-opencode-slim | 任務路由 + 質量/速度/成本平衡 | IntentRouter + ModelSelector |
| OpenCode Configuration | 研究→計劃→審查→實現→驗證流水線 | Scout→Analyst→Guardian→Executor 流水線 |
| opencode-hive | 成本分層（簡單任務用便宜模型） | LlmTier 四級模型分層 |

### 12.3 DeepSeek 模型能力啟發

| DeepSeek 特性 | 本項目應用 |
|--------------|-----------|
| R1 高效推理（低成本接近 o1） | ULTRA 模型層，用於 Orchestrator 決策 |
| V3 多步推理強化 | STRONG 模型層，用於 Analyst 分析 |
| V3-Lite 快速響應 | FAST 模型層，用於 Guardian 風控 |
| 專家模式（多步推理+領域適配） | 熊市環境自動升級模型層級 |




