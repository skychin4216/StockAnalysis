# Agent 框架雙路線遷移計劃

## 目標

1. **雙路線並行**：新 Agent 框架與舊 Pipeline 系統同時存在，通過開關控制使用哪條路線
2. **穩定後統一**：運行一段時間後，根據實際數據決定哪些模塊可以合並，減少代碼冗餘

---

## Phase 0: 開關基礎設施（立即實施）

### 新增 `FeatureFlagManager`

位置：`app/src/main/java/com/chin/stockanalysis/config/FeatureFlagManager.kt`

功能：
- 基於 `SharedPreferences` 的 Feature Flag 管理
- 支持按模塊獨立開關
- 支持全局一鍵切換

```kotlin
enum class AgentRoute { LEGACY, AGENT_FRAMEWORK, AUTO }

object FeatureFlagManager {
    private const val PREFS_NAME = "feature_flags"
    
    // 各模塊獨立開關
    var stockPickingRoute: AgentRoute      // 選股: 舊 Pipeline / 新 Agent
    var stockAnalysisRoute: AgentRoute     // 分析: 舊 Analyzer / 新 Agent
    var tradeExecutionRoute: AgentRoute    // 交易: 舊 SimTradeEngine / 新 Agent
    var chatRoute: AgentRoute              // 對話: 舊 ExpertPrompt / 新 ChatAgent
    var newsMonitoringRoute: AgentRoute    // 新聞: 舊 HotSectorUpdater / 新 Agent
    var riskManagementRoute: AgentRoute    // 風控: 舊 AutoSell / 新 Agent
    
    // 全局開關
    var useAgentFramework: Boolean         // true = 全部用新 Agent（除非模塊單獨設為 LEGACY）
}
```

### UI 開關位置

在 **設置頁面** 新增「Agent 框架」區塊：

```
[設置] → [Agent 框架]
├── 全局模式: [全部使用舊系統] [全部使用新 Agent] [按模塊配置]
├── 
├── 選股模塊: [舊 Pipeline] [新 Agent] ○
├── 分析模塊: [舊 Analyzer] [新 Agent] ○
├── 交易模塊: [舊 SimTrade] [新 Agent] ○
├── 對話模塊: [舊 Expert]   [新 Agent] ○
├── 新聞模塊: [舊 Updater]  [新 Agent] ○
└── 風控模塊: [舊 AutoSell] [新 Agent] ○
```

---

## Phase 1: 雙路線並行（第 1-2 周）

### 1.1 選股模塊（ShortTermQuantFragment）

**舊路線**：`AgentPipelineOrchestrator.execute(target)`
**新路線**：`StockPickingAgent.pickStocks()`

**改造點**：
```kotlin
// ShortTermQuantFragment.kt
private fun runAIPipeline(target: String) {
    when (FeatureFlagManager.stockPickingRoute) {
        AgentRoute.LEGACY -> {
            // 舊路線不變
            val orchestrator = AgentPipelineOrchestrator(requireContext())
            orchestrator.execute(target)
        }
        AgentRoute.AGENT_FRAMEWORK -> {
            // 新路線
            val agent = StockPickingAgent(requireContext())
            val result = agent.pickStocks()
            displayPickingResult(result)
        }
        AgentRoute.AUTO -> {
            // 自動：先用舊的，失敗 fallback 到新的
            try { /* 舊 */ } catch (e: Exception) { /* 新 */ }
        }
    }
}
```

### 1.2 分析模塊（ChatTabFragment）

**舊路線**：`runExpertAnalysis()` → 純 Prompt 工程
**新路線**：`ChatAgent.handleMessage()` → 意圖識別 + 子 Agent

**改造點**：
```kotlin
// ChatTabFragment.kt
private suspend fun handleUserMessage(message: String) {
    when (FeatureFlagManager.chatRoute) {
        AgentRoute.LEGACY -> sendMessageInternal(message)
        AgentRoute.AGENT_FRAMEWORK -> {
            val agent = ChatAgent(requireContext())
            val result = agent.handleMessage(message)
            displayAgentResult(result)
        }
        else -> { /* AUTO fallback */ }
    }
}
```

### 1.3 交易模塊（MidTermQuantFragment）

**舊路線**：`SimulationTradeEngine.runTradeSession()`
**新路線**：`StockPickingAgent.pickStocks() → TradeExecutionAgent.executeTrade()`

**注意**：交易模塊風險最高，建議先只在新路線中做**模擬/分析**，不下真單。

### 1.4 新聞模塊（AppBackgroundRunner）

**舊路線**：`HotSectorNewsUpdater.updateIfNeeded()`
**新路線**：`NewsMonitoringAgent.monitorSector()`

**改造點**：在後台定時任務中加入開關判斷

### 1.5 風控模塊（AppBackgroundRunner）

**舊路線**：`AutoSellEngine.evaluateAll()` + 手動規則
**新路線**：`RiskManagementAgent.scanPortfolio()`

---

## Phase 2: 穩定期觀測（第 3-4 周）

### 觀測指標

每個模塊需要記錄以下數據到本地數據庫或日志：

| 指標 | 舊路線 | 新路線 | 備註 |
|------|--------|--------|------|
| 執行成功率 | ✓ | ✓ | 是否拋異常 |
| 平均執行時間 | ✓ | ✓ | 端到端耗時 |
| AI 請求次數 | ✓ | ✓ | Token 消耗 |
| 結果質量評分 | ✓ | ✓ | 用戶反饋或自動評估 |
| 代碼複雜度 | - | - | 行數、圈複雜度 |

### 結果對比表（運行後填充）

| 模塊 | 舊路線成功率 | 新路線成功率 | 舊路線耗時 | 新路線耗時 | 結論 |
|------|-----------|-----------|----------|----------|------|
| 選股 | _ | _ | _ | _ | _ |
| 分析 | _ | _ | _ | _ | _ |
| 交易 | _ | _ | _ | _ | _ |
| 對話 | _ | _ | _ | _ | _ |
| 新聞 | _ | _ | _ | _ | _ |
| 風控 | _ | _ | _ | _ | _ |

---

## Phase 3: 統一合並（第 5-6 周，基於數據決策）

### 3.1 確定合並原則

| 情況 | 決策 |
|------|------|
| 新路線明顯優於舊路線（成功率高 + 耗時短） | 刪除舊路線，保留新路線 |
| 新路線與舊路線各有優劣 | 保留雙路線，優化切換邏輯 |
| 新路線不穩定 | 回滾到舊路線，繼續優化新 Agent |
| 兩套系統功能重疊但實現不同 | 提取公共組件，統一接口 |

### 3.2 預計可合並的冗餘點

根據代碼分析，以下模塊確定可以合並：

**高確定性（運行後即可合並）**：
1. `AgentManager` ↔ `MultiAgentOrchestrator`：雙重註冊表 → 統一為 `AgentRegistry`
2. `PipelineContext` ↔ `AgentContext`：雙重 Map 包裝 → 統一為 `SharedContext`
3. `DataFeeder`：重構為 `AgentBase` 子類，复用 `callLLM()`

**中確定性（需數據驗證）**：
4. `AgentPipelineOrchestrator` ↔ `MultiAgentOrchestrator`：如果新路線的 Plan-and-Execute 能覆蓋舊 Pipeline 的所有模式
5. `StockAnalyzerService` ↔ `StockAnalysisAgent`：如果新路線分析質量不輸舊路線

**低確定性（需長期觀測）**：
6. `SimulationTradeEngine` ↔ `TradeExecutionAgent`：交易模塊風險高，需謹慎
7. `AutoSellEngine` ↔ `RiskManagementAgent`：風控規則需大量回測驗證

### 3.3 合並後的目標架構

```
com.chin.stockanalysis.agent/
├── framework/                    # 統一框架（保留）
│   ├── AgentBase.kt
│   ├── AgentTool.kt
│   ├── AgentContext.kt          # 統一 PipelineContext + AgentContext
│   ├── AgentResult.kt
│   ├── AgentRegistry.kt         # 統一 AgentManager + MultiAgentOrchestrator
│   └── AgentMemory.kt
│
├── pipeline/                     # 如果合並，精簡或刪除
│   └── （保留 StructuredOutputParser 等領域工具）
│
├── stock/                        # 業務 Agent（保留）
├── chat/
├── news/
├── risk/
│
└── config/
    └── FeatureFlagManager.kt     # 長期保留，用於 A/B Test
```

---

## 風險控制

| 風險 | 應對措施 |
|------|---------|
| 新路線 AI 超時/失敗 | AUTO 模式自動 fallback 到舊路線 |
| 新路線結果質量差 | 每個模塊保留「一鍵切回舊系統」按鈕 |
| 數據不一致 | 雙路線共用同一套數據庫和數據源 |
| 代碼維護成本上升 | Phase 3 嚴格按數據決策，不穩定的模塊果斷回滾 |

---

## 下一步行動

1. **立即**：創建 `FeatureFlagManager` 和設置頁面 UI
2. **本週**：完成選股 + 對話模塊的雙路線接入
3. **下週**：完成分析 + 新聞 + 風控模塊的雙路線接入
4. **持續**：收集運行數據，填充對比表
5. **3-4 周後**：根據數據決定合並方案
