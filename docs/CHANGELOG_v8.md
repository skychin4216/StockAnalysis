# StockAnalysis 項目變更日誌 v8

> 日期: 2026-07-08
> V2.0 全周期擴展 + Chat 引擎統一 + 自選股增強 + 缺陷修復

---

## 🆕 v8.0 V2.0 全周期擴展 + Chat 引擎統一 + 自選股增強（2026-07-08）

### 核心變更
1. Chat 對話（Legacy + Agent 雙路徑）統一接入 UnifiedAgentRunner
2. 自選股列表新增利潤質量標籤（🟢🟡🔴）
3. 自選股頂部新增市場環境 + 倉位上限顯示
4. 刪除 Chat 中 ~386 行冗餘的舊分析 prompt

---

### 一、Chat 對話引擎統一

#### ChatTabFragment.kt — Legacy 路徑

- 新增 `extractStockCodeFromText()` — 正則提取股票代碼（sh/sz/bj 前綴 + 純6位數字）
- 新增 `resolveStockName()` — DB 查詢股票名稱
- 新增 `runUnifiedAnalysis()` — 統一引擎入口：
  - ⚡ 快速 → `MODE_QUICK`（StockAnalysisAgent + RiskManagementAgent + MarketAnalyzer 並行）
  - 🔍 深度 → `MODE_PIPELINE`（AgentPipelineOrchestrator 多步流水線）
  - 📊 專家 → `MODE_V2`（市場環境 + 利潤質量 + 決策矩陣）
- 刪除舊方法：`runQuickAnalysis()`、`runDeepAnalysis()`、`runExpertAnalysis()`、`buildSectorAnalysis()`
- 新增 `runGeneralChat()` — 簡潔通用問答（非股票問題 fallback）
- `sendMessageInternal()` 分流：有股票代碼 → UnifiedAgentRunner；無 → runGeneralChat()

#### ChatAgent.kt — Agent 路徑

- `STOCK_ANALYSIS` 意圖：`analysisAgent.analyze()` + `pipelineAdapter.analyze()` → `UnifiedAgentRunner.run()`
- 同樣根據 `analysisMode` 映射 QUICK/DEEP/EXPERT 三種模式
- 歧義實體處理邏輯保留不變

#### 分析模式完整對照

| Chat 模式 | UnifiedAgentRunner 模式 | 引擎 |
|-----------|------------------------|------|
| ⚡ 快速分析 | MODE_QUICK | StockAnalysisAgent + RiskManagementAgent + MarketAnalyzer 並行 |
| 🔍 深度分析 | MODE_PIPELINE | AgentPipelineOrchestrator 多步流水線 |
| 📊 專家分析 | MODE_V2 | V2AgentRunner（市場環境 + 利潤質量 + 決策矩陣） |
| 💬 通用問答 | 純 LLM | sendWithRetry（非股票問題 fallback） |

---

### 二、自選股列表利潤質量標籤

#### StockTableHelper.kt
- `buildCell("name")`：名稱改為水平 LinearLayout（名稱 + qualityLabel）
- qualityLabel 初始 "⚪"，tag `"qualityLabel_${item.code}"`

#### WatchlistUnifiedFragment.kt
- 新增 `computeProfitQualityLabels(items)` — 異步計算利潤質量
- 標籤映射：🟢 內生性增長 / 🟡 一次性浮盈 / 🔴 利潤膨脹 / ⚪ 數據不足
- 每只股票 15 秒超時保護

---

### 三、首頁市場環境 + 倉位上限

#### WatchlistUnifiedFragment.kt
- 新增 `marketEnvBar` — 自選股列表頂部
- 新增 `loadMarketEnvironment()` — MarketAnalyzer + PositionWaterValve
- 顏色方案：BULLISH 綠色 / BEARISH 紅色 / OSCILLATION 橙色
- `onResume` 自動刷新

---

### 四、缺陷修復

| 文件 | 問題 | 修復 |
|------|------|------|
| WatchlistUnifiedFragment | `isActive` Unresolved reference | `import kotlinx.coroutines.isActive` |
| WatchlistUnifiedFragment | 9 處 `lifecycleScope` 生命週期問題 | 全部替換為 `viewLifecycleOwner.lifecycleScope` |
| WatchlistUnifiedFragment | onCreateView + onResume 重複加載 | 移除 onCreateView 中的 loadData() |
| ChatTabFragment | hotSectorsHideJob 未取消 | onDestroyView 添加 cancel() |
| ChatAgent | StockQueryTool/MarketBriefTool 未使用變數 | 移除 `val c = ctx`、`val localCtx` |

---

### 五、文件變更清單

| 文件 | 類型 | 說明 |
|------|------|------|
| `ui/ChatTabFragment.kt` | **重構** | UnifiedAgentRunner 對接 + 刪除 3 個舊方法 + 新增 runGeneralChat |
| `agent/chat/ChatAgent.kt` | **修改** | STOCK_ANALYSIS 分支改用 UnifiedAgentRunner |
| `ui/WatchlistUnifiedFragment.kt` | **修改** | 利潤質量標籤 + 市場環境條 + lifecycleScope 修復 |
| `common/StockTableHelper.kt` | **修改** | 名稱列添加 qualityLabel |
| `docs/CHANGELOG_v8.md` | **新建** | v8.0 變更日誌 |
| `docs/archived/` | **刪除** | 刪除 3 個過期 md + 文件夾 |

---

### 六、策略頁面現狀（未修改）

策略頁面仍使用獨立 `AIPredictionEngine` → `ApiProvider.sendMessageStream`，未整合 UnifiedAgentRunner。批量篩選打分場景不同於單股深度分析，屬合理架構。

### 七、已知待處理

- 圖片解析不工作：`ApiProvider` 不支持 OpenAI Vision 多模態格式
- PDF 解析僅記錄頁數，無法提取文字
