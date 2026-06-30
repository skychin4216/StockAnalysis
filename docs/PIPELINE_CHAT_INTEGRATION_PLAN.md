# Pipeline 多智能體接入對話方案

## 背景

`AgentPipelineOrchestrator` 是一套 5-7 步的多智能體流水線，當前掛載在 `ShortTermQuantFragment`（獨立頁面）中使用。用戶希望在對話框中也能觸發專家級分析，讓「快速 / 深度 / 專家」三級模式在 Agent 框架下真正有意義。

---

## 目標

1. 對話框輸入「深度分析 兆易創新」或切換到「專家模式」後發送任意股票名稱，觸發 Pipeline 流水線
2. 對話內實時展示每步進度（Agent 1 → Agent 2 → ... → Agent D）
3. 最終輸出結構化報告（含產業鏈打分、風控、輿情微調、交易方案）
4. 總耗時控制在 60s 內，超時或取消時優雅降級

---

## 現狀分析

### Pipeline 現有架構

```
AgentPipelineOrchestrator.execute(target: String)
  │
  ├─ selectMode(target, sector)          ← AI / 本地關鍵詞選擇模式
  │   ├─ MODE_NORMAL_SIX   (6 步)        ← 通用賽道
  │   ├─ MODE_SELLER_SEVEN (7 步)        ← 賣水人賽道
  │   └─ MODE_SELLER_SIMPLE (5+1 步)     ← 精簡版（默認）
  │
  ├─ 主線串行執行 steps[]
  │   ├─ Step 0: 選股策略初始化（Agent 1，系統提示詞）
  │   ├─ Step 1: 基本面選股（Agent 1）
  │   ├─ Step 2: 產業鏈選股（Agent 2）→ 打分 <40 則淘汰
  │   ├─ Step 3: 賽道熱度（Agent 3）
  │   ├─ Step 4: 技術面交易（Agent 4）→ 與 Agent D 並行啟動
  │   ├─ Step 5: 風控終審（Agent 5）
  │   └─ Step 6: 賣水人深度（Agent 6，僅七智體模式）
  │
  ├─ 支線並行
  │   └─ Agent D: 板塊 & 輿情評分（與 Agent 4 並行）
  │
  ├─ applyHedgeMechanism()               ← 海外供應鏈對沖清零
  └─ buildFinalResult() → PipelineResult
```

### PipelineResult 輸出結構

```kotlin
data class PipelineResult(
    val target: String,
    val sector: String,
    val stepsCompleted: Int,
    val totalSteps: Int,
    val stocks: List<PipelineStockResult>,
    val analysisMode: String       // "六智體通用" / "七智體賣水人" / "精簡版"
)

data class PipelineStockResult(
    val stockCode: String,
    val stockName: String,
    val chainScore: ChainScoreResult?,      // 產業鏈打分
    val riskResult: RiskValidationResult?,  // 風控終審
    val sentimentResult: SentimentAdjustResult?, // 輿情微調
    val tradePlan: TradeExecutionPlan?,     // 交易方案
    val finalPosition: String,              // 最終建議倉位
    val passed: Boolean                     // 是否通過全部流水線
)
```

### 回調機制

| 回調 | 觸發時機 | 參數 |
|------|---------|------|
| `onStepStart` | 每步開始前 | `(index, PipelineStep, PipelineContext)` |
| `onStepComplete` | 每步完成後 | `(index, PipelineStep, PipelineContext)` |
| `onModeSelected` | 模式選定後 | `(AnalysisMode, reason)` |
| `onError` | 某步異常 | `(failedStepIdx, errorMessage)` |

### 耗時估算

| 步驟 | 預估耗時 | 備註 |
|------|---------|------|
| 模式選擇（AI） | 3-5s | 僅本地無法確定時走 AI |
| 每個 Agent 步驟 | 3-8s | 取決於模型和 prompt 長度 |
| 精簡版（5+1） | 20-35s | 6 個 LLM 調用 |
| 六智體（6） | 25-45s | 6 個 LLM 調用 |
| 七智體（7） | 30-55s | 7 個 LLM 調用 |

---

## 方案設計

### 1. 觸發條件

用戶有兩種方式觸發專家模式：

**方式 A：通過模式按鈕切換**
```
用戶點擊「專家」按鈕 → analysisMode = EXPERT
用戶輸入「兆易創新」→ ChatAgent 檢測到 STOCK_ANALYSIS + EXPERT 模式
→ 調用 PipelineOrchestrator.execute("兆易創新")
```

**方式 B：通過自然語言關鍵詞**
```
用戶輸入「深度分析 兆易創新」「專家分析一下 比亞迪」
→ ChatAgent.detectIntent() 檢測到 EXPERT 關鍵詞
→ 解析股票名稱 → 調用 PipelineOrchestrator.execute()
```

**關鍵詞列表**：
- 深度分析、專家分析、全量分析、流水線分析
- Pipeline、專家模式、專家級

### 2. 對話進度展示

Pipeline 每步約 3-8 秒，對話內需要實時反饋，避免用戶以為卡死。

**設計**：使用一條流式消息，每步完成時更新內容。

```
🤖 專家模式分析中：兆易創新

[0/6] ⏳ 基本面拐點價值選股...        (第 1 秒)
[1/6] ✅ 基本面選股完成                (第 5 秒)
[2/6] ⏳ 產業鏈賣水人選股...          (第 6 秒)
...
[6/6] ✅ 風控終審完成                  (第 32 秒)

📊 正在生成報告...
```

**技術實現**：

```kotlin
// PipelineChatAdapter（新增）
class PipelineChatAdapter(
    private val onProgress: (String) -> Unit   // 回傳給 ChatAdapter 更新 UI
) {
    fun buildProgressOrchestrator(context: Context): AgentPipelineOrchestrator {
        return AgentPipelineOrchestrator(context).apply {
            onStepStart = { index, step, _ ->
                onProgress(buildProgressMessage(index, step, "running"))
            }
            onStepComplete = { index, step, _ ->
                onProgress(buildProgressMessage(index, step, "done"))
            }
            onError = { failedIdx, err ->
                onProgress("❌ 第 ${failedIdx + 1} 步失敗: $err，繼續後續步驟...")
            }
        }
    }
}
```

### 3. 超時與取消

| 場景 | 策略 |
|------|------|
| **單步超時** | 每步設置 20s 超時，超時後記錄錯誤但繼續後續步驟（降級輸出） |
| **總超時** | 整個 Pipeline 設置 90s 超時，超時後返回已完成的結果 |
| **用戶取消** | 點擊對話框外 / 切換 Tab / 發送新消息 → 取消協程 |
| **網路中斷** | `onError` 回調記錄，後續步驟繼續（該步數據為 null） |

```kotlin
// ChatAgent 中的調用
val result = withTimeoutOrNull(90_000) {
    val orchestrator = pipelineAdapter.buildProgressOrchestrator(context)
    orchestrator.execute(target)
} ?: PipelineResult(
    target = target,
    errorMessage = "分析超時，已返回部分結果",
    stepsCompleted = currentStep
)
```

### 4. 結果格式化

`PipelineResult` 需要轉換為自然語言對話格式。

```kotlin
object PipelineResultFormatter {
    fun format(result: PipelineResult): String = buildString {
        appendLine("📊 ${result.target} 專家分析報告")
        appendLine("分析模式：${result.analysisMode}（${result.stepsCompleted}/${result.totalSteps} 步完成）")
        appendLine()

        if (result.errorMessage != null) {
            appendLine("⚠️ 部分步驟異常：${result.errorMessage}")
            appendLine()
        }

        result.stocks.forEach { stock ->
            appendLine("【${stock.stockName} ${stock.stockCode}】")
            appendLine("${if (stock.passed) "✅ 通過流水線" else "❌ 未通過流水線"}")
            appendLine()

            stock.chainScore?.let {
                appendLine("🎯 產業鏈打分")
                appendLine("  總分：${it.totalScore}/100（壁壘：${it.barrierLevel}）")
                appendLine("  競爭優勢：${it.competitiveMoat}")
            }

            stock.riskResult?.let {
                appendLine()
                appendLine("🛡 風控終審")
                appendLine("  風險等級：${it.riskLevel}")
                appendLine("  海外供應鏈：${it.overseasDeduction} 分")
            }

            stock.sentimentResult?.let {
                appendLine()
                appendLine("📢 輿情微調")
                appendLine("  輿情得分：${it.sentimentScore}")
                appendLine("  倉位調整：${it.positionAdjust}")
                appendLine("  理由：${it.reason}")
            }

            stock.tradePlan?.let {
                appendLine()
                appendLine("📈 交易方案")
                appendLine("  低吸區間：${it.entryZones.joinToString(" / ")}")
                appendLine("  止損位：${it.stopLoss}")
                appendLine("  止盈目標：${it.targets.joinToString(" / ")}")
                appendLine("  最大倉位：${it.maxPosition}")
                appendLine("  分倉比例：${it.splitRatio}")
            }

            appendLine()
            appendLine("💡 最終建議：${stock.finalPosition}")
            appendLine()
            appendLine("—".repeat(30))
        }
    }
}
```

### 5. 整體數據流

```
用戶輸入「深度分析 兆易創新」
  │
  ├─ ChatAgent.handleMessage()
  │   ├─ detectIntent() → STOCK_ANALYSIS
  │   ├─ analysisMode == EXPERT → 走 Pipeline 分支
  │   ├─ StockEntityExtractor.extractSync() → "兆易創新" → sh603986
  │   └─ 調用 PipelineOrchestrator.execute("兆易創新")
  │
  ├─ AgentPipelineOrchestrator
  │   ├─ selectMode("兆易創新", "存儲芯片") → MODE_SELLER_SIMPLE（本地關鍵詞命中）
  │   ├─ 串行執行 6 步，每步通過 onStepStart/onStepComplete 回傳進度
  │   ├─ Agent D 與 Step 4 並行
  │   ├─ applyHedgeMechanism()
  │   └─ PipelineResult
  │
  ├─ PipelineResultFormatter.format(result)
  │   └─ 結構化自然語言報告
  │
  └─ ChatTabFragment 渲染最終消息
```

---

## 文件改動清單

| 文件 | 路徑 | 改動內容 | 優先級 |
|------|------|---------|--------|
| `AnalysisMode` enum | `ui/ChatTabFragment.kt` | 保留 EXPERT，或改名為 PIPELINE | P0 |
| `ChatAgent.kt` | `agent/chat/ChatAgent.kt` | handleMessage() 增加 EXPERT 分支，調用 PipelineOrchestrator | P0 |
| `PipelineChatAdapter.kt` | 新增 | 封裝 Pipeline 調用 + 進度回調 + 結果格式化 | P0 |
| `ChatTabFragment.kt` | `ui/ChatTabFragment.kt` | runAgentAnalysis() 傳入 analysisMode | P0 |
| `Message.kt` | `ui/Message.kt` | 增加 `isPipeline` 標記（用於 UI 特殊渲染） | P1 |
| `ChatAdapter.kt` | `ui/ChatAdapter.kt` | 支持 Pipeline 進度消息的實時更新 | P1 |
| `AgentPipelineOrchestrator.kt` | `agent/pipeline/AgentPipelineOrchestrator.kt` | 增加單步超時（20s）和總超時（90s） | P1 |
| `PipelineResultFormatter.kt` | 新增 | PipelineResult → 自然語言 | P0 |

---

## 風險與回退

| 風險 | 概率 | 影響 | 回退方案 |
|------|------|------|----------|
| Pipeline 總時長 >60s | 中 | 用戶以為卡死 | 進度消息緩解；超時後返回部分結果 |
| Agent 2 打分 <40 淘汰 | 中 | 用戶問的股票被 Pipeline 淘汰 | 輸出「該標的未通過產業鏈篩選」+ 說明原因 |
| 某步 LLM 失敗 | 低 | 該步數據缺失 | onError 後繼續執行，最終報告標註「該維度數據缺失」 |
| 模式選擇 AI 調用失敗 | 低 | 無法確定賽道 | 回退到 MODE_SELLER_SIMPLE（現有邏輯已處理） |
| 並行 Agent D 失敗 | 低 | 缺少輿情數據 | 主線不受影響，最終報告無輿情微調部分 |

---

## 驗收標準

- [ ] 對話框切換「專家」模式後輸入股票名稱，觸發 Pipeline
- [ ] 對話內實時顯示每步進度（至少顯示「第 X/Y 步：Agent 名稱」）
- [ ] 最終輸出包含：產業鏈打分、風控結果、交易方案、最終倉位建議
- [ ] 總耗時 < 90s，超時後返回已完成部分 + 降級提示
- [ ] 用戶切離對話 / 發送新消息時，Pipeline 協程正確取消
- [ ] Agent 2 打分 <40 時，輸出友好提示（非空消息）
