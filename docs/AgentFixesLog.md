# Agent Framework 修復日誌

## 歷史修復（已完成）

| 類別 | 根本原因 | 影響文件 | 狀態 |
|------|---------|---------|------|
| AgentContext vs Context 混淆 | `StockDatabase.getInstance(ctx)` 中 `ctx` 是 `AgentContext` 而非 `android.content.Context` | ChatAgent, NewsMonitoringAgent, RiskManagementAgent, StockAnalysisAgent, StockPickingAgent, TradeExecutionAgent | ✅ 已修復 |
| 實體字段名錯誤 | AI 生成代碼使用了不存在的字段名 | 全部 Agent 文件 | ✅ 已修復 |
| 不存在的 DAO 方法 | 假定了不存在的数据库方法 | 全部 Agent 文件 | ✅ 已修復 |
| 框架 API 錯誤 | `resumeWithException` 不存在於 `CancellableContinuation` | AgentBase.kt | ✅ 已修復 |
| 作用域問題 | `parsePlan()` 中的 `goal` 參數不在作用域內 | AgentBase.kt | ✅ 已修復 |
| 過時引用 | `MultiAgentOrchestrator` 引用不存在的 `key` | MultiAgentOrchestrator.kt | ✅ 已修復 |
| 遺留代碼 | `PipelineProgressView` 引用 `STEPS` | PipelineProgressView.kt, ShortTermQuantFragment.kt | ✅ 已修復 |

---

## 近期修復（2026-06-29）

### 1. 推理模型 content 為空 — ✅ 已修復

**文件**: `OpenAiCompatibleProvider.kt`

**問題**: `doubao-seed-2-0-pro` 等推理模型將輸出放在 `delta.reasoning_content`，`delta.content` 為空，導致流式累積 0 字符。

**修復**:
```kotlin
val content = delta.optString("content", "")
val reasoningContent = delta.optString("reasoning_content", "")
val textToAppend = content.ifBlank { reasoningContent }
if (textToAppend.isNotEmpty()) {
    sb.append(textToAppend)
    onSuccess(textToAppend)
}
```

### 2. finish_reason NPE — ✅ 已修復

**文件**: `OpenAiCompatibleProvider.kt`

**問題**: `choice.optString("finish_reason", null).ifBlank { null }` 在 `null` 上調用 `isBlank()` 拋 NPE，導致首包 JSON 解析被跳過。

**修復**:
```kotlin
// 原代碼
choice.optString("finish_reason", null).ifBlank { null }
// 修復後
choice.optString("finish_reason", "").ifBlank { null }
```

### 3. StockAnalysisAgent 多次 LLM 調用超時 — ✅ 已修復

**文件**: `StockAnalysisAgent.kt`（重寫）

**問題**: `planAndExecute()` 需要多次 LLM 調用（plan → execute steps → summary），推理模型響應慢導致總超時 > 25s。

**修復**: 重寫為 **並行收集數據 + 單次 LLM 調用**:
```kotlin
// 並行收集三維度數據
val technical = async { TechnicalAnalysisTool.execute(...) }
val fundamental = async { FundamentalAnalysisTool.execute(...) }
val fundFlow = async { FundFlowTool.execute(...) }

// 單次 LLM 調用（60s 超時）
val llmOutput = withTimeout(60_000) {
    suspendCancellableCoroutine<String> { cont ->
        cont.invokeOnCancellation { slot.provider.cancel() }
        slot.provider.sendMessageStream(...)
    }
}
```

### 4. IndexAnalysisAgent 超時 — ✅ 已修復

**文件**: `IndexAnalysisAgent.kt`

**問題**: 無顯式超時控制，推理模型響應慢時可能無限等待。

**修復**: 增加 60s 超時 + `suspendCancellableCoroutine` + `invokeOnCancellation`。

### 5. 協程取消未切斷網路請求 — ✅ 已修復

**文件**: `StockAnalysisAgent.kt`, `IndexAnalysisAgent.kt`

**問題**: `suspendCancellableCoroutine` 未掛 cancellation handler，協程取消時網路請求仍在後臺運行。

**修復**:
```kotlin
suspendCancellableCoroutine<String> { cont ->
    cont.invokeOnCancellation {
        slot.provider.cancel()  // 主動取消 OkHttp Call
    }
    slot.provider.sendMessageStream(...)
}
```

---

## 實體字段映射表

| AI 生成字段 | 實際字段 | 實體 |
|-----------|---------|------|
| `stockName` | `name` | DailySnapshotEntity |
| `stockCode` | `code` | DailySnapshotEntity |
| `mainBusiness` | `business` | StockBasicEntity |
| `chainLogic` | `chainRationale` | StockBasicEntity |
| `turnover` | `turnoverRate` | DailySnapshotEntity |

## 數據庫方法映射表

| AI 生成方法 | 實際等價方法或處理方式 |
|-----------|-------------------|
| `getLatestByCode(code)` | `getByDateAndCode(today, code)` |
| `getRecentByCode(code)` | `getByDateAndCode(today, code)` |
| `getActiveByStockCode(code)` | 不存在，替換為 `getByDateAndCode` |
| `getActiveOrders()` | `getRecent(200).filter { it.status in listOf("BUYING","PENDING") }` |
| `getSectorsByStockCode(code)` | `getSectorNamesByStockCode(code)` |
| `getTopSectorsByDate(date)` | 不存在，簡化/註釋待實現 |
| `getByDateAndSector(date, sector)` | 不存在，簡化/註釋待實現 |
| `getStocksBySector(sector)` | `getStockCodesBySector(sector)` |
| `searchByKeyword(keyword)` | `searchByName(keyword)` |
| `getLatestActive(code)` | 不存在，簡化/註釋待實現 |
| `updateStatusByCode(code, status)` | 不存在，使用 `updateSellInfo` 或直接操作 |
| `getActiveByCode(code)` | 不存在，替換為 `getRecent` |
