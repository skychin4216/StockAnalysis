# Pipeline 全鏈路投研決策系統

## 總體架構

Pipeline 採用 **串行 7 步流水線**設計，每步 Agent 的輸出作為下一步的上下文累積，最終產出結構化的投研決策報告。

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Pipeline 全鏈路投研決策                              │
│                                                                             │
│  ┌─────────────┐   ┌─────────────┐   ┌─────────────┐   ┌─────────────┐    │
│  │  Step 1     │ → │  Step 2     │ → │  Step 3     │ → │  Step 4     │    │
│  │ 初選 Agent  │   │ 賣水人Agent │   │ 賽道 Agent  │   │ 技術/競爭   │    │
│  │             │   │ (產業鏈)    │   │             │   │ Agent       │    │
│  └─────────────┘   └─────────────┘   └─────────────┘   └─────────────┘    │
│         │                 │                 │                 │             │
│         ▼                 ▼                 ▼                 ▼             │
│  ┌─────────────┐   ┌─────────────┐   ┌─────────────┐                     │
│  │  Step 5     │ → │  Step 6     │ → │  Step 7     │                     │
│  │ 風控 Agent  │   │ 輿情 Agent  │   │ 交易 Agent  │                     │
│  │             │   │ (Agent D)   │   │             │                     │
│  └─────────────┘   └─────────────┘   └─────────────┘                     │
│         │                 │                 │                              │
│         └─────────────────┴─────────────────┘                              │
│                         │                                                  │
│                         ▼                                                  │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                  PipelineResult（結構化結果）                         │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐│   │
│  │  │ ChainScore  │  │ RiskResult  │  │ Sentiment   │  │ TradePlan   ││   │
│  │  │ (產業鏈打分) │  │ (風控結果)  │  │ (輿情微調)  │  │ (交易方案)  ││   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘│   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                         │                                                  │
│                         ▼                                                  │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │           PipelineResultFormatter.format() → Markdown 報告          │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

## 三種分析模式

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         分析模式選擇                                         │
│                                                                             │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐             │
│  │   七智體模式     │  │   六智體模式     │  │   精簡版模式     │             │
│  │   (默認推薦)     │  │                 │  │                 │             │
│  └────────┬────────┘  └────────┬────────┘  └────────┬────────┘             │
│           │                    │                    │                       │
│           ▼                    ▼                    ▼                       │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐             │
│  │ Step1: 初選 Agent│  │ Step1: 初選 Agent│  │ Step1: 賣水人Agent│            │
│  │ Step2: 賣水人Agent│  │ Step2: 賣水人Agent│  │ Step2: 風控 Agent │            │
│  │ Step3: 賽道 Agent│  │ Step3: 賽道 Agent│  │ Step3: 交易 Agent │            │
│  │ Step4: 技術/競爭 │  │ Step4: 風控 Agent │  │                 │             │
│  │ Step5: 風控 Agent │  │ Step5: 交易 Agent │  │                 │             │
│  │ Step6: 輿情 Agent │  │                 │  │                 │             │
│  │ Step7: 交易 Agent │  │                 │  │                 │             │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘             │
└─────────────────────────────────────────────────────────────────────────────┘
```

## 核心組件

### AgentPipelineOrchestrator（編排器）

`com.chin.stockanalysis.agent.pipeline.AgentPipelineOrchestrator`

- **職責**：管理整個 Pipeline 的生命週期（初始化 → 逐步執行 → 結果聚合）
- **數據底座**：`execute()` 開頭拉取 `StockDataFacade` + `QuarterlyComparisonProvider`
- **上下文傳遞**：`PipelineContext` 在 7 步之間傳遞累積狀態
- **注入點**：`buildStepPrompt()` 在每步 SystemPrompt 中注入歷史上下文 + 實時數據 + 季度环比

### PipelineContext（上下文）

`com.chin.stockanalysis.agent.pipeline.PipelineResult.PipelineContext`

```
┌─────────────────────────────────────────────────────────────┐
│ PipelineContext（7步之間傳遞的累積狀態）                       │
│                                                             │
│  target: String          ← 用戶輸入的標的                     │
│  sector: String          ← 動態識別的板塊/賽道               │
│  stockData: StockAnalysisData  ← StockDataFacade 獲取        │
│  intelligence: DataFeederResult  ← Agent F 標準化情報        │
│  sectorHeatLevel: String  ← Agent 3 賽道熱度                 │
│  filteredPool: List       ← Agent 1 初選池                   │
│  chainScore: ChainScoreResult  ← Agent 2 產業鏈打分          │
│  riskResult: RiskValidationResult  ← Agent 5 風控            │
│  sentimentResult: SentimentAdjustResult  ← Agent D 輿情     │
│  tradePlan: TradeExecutionPlan  ← Agent 4 交易方案           │
│  quarterlyComparison: QuarterlyComparisonResult  ← 季度环比  │
│  stepAnalyses: Map        ← 每步原始 Markdown               │
└─────────────────────────────────────────────────────────────┘
```

### 數據底座（Data Foundation）

Pipeline 執行第一步即拉取全部前置數據，後續 Agent 共用：

```
┌─────────────────────────────────────────────────────────────┐
│                      數據底座注入流程                         │
│                                                             │
│  AgentPipelineOrchestrator.execute()                        │
│       │                                                     │
│       ├── StockDataFacade.getAnalysisData(stockCode)        │
│       │      ├─ quote: 實時行情                              │
│       │      ├─ fundamental: 基本面數據                      │
│       │      └─ indicators: 技術指標                         │
│       │                                                     │
│       ├── QuarterlyComparisonProvider.fetch(stockCode)      │
│       │      ├─ latest: 最新季度財報                         │
│       │      ├─ previous: 上一季度財報                       │
│       │      ├─ netProfitQoQ: 歸母淨利潤環比                 │
│       │      ├─ revenueQoQ: 營收環比                        │
│       │      ├─ trend: 連續趨勢判定                          │
│       │      └─ scoreAdjustment: 評分調整建議                │
│       │                                                     │
│       └── 注入到 PipelineContext                             │
│                                                             │
│  buildStepPrompt() 將數據格式化成 Markdown 注入每步 Agent    │
└─────────────────────────────────────────────────────────────┘
```

### StructuredOutputParser（結構化輸出解析）

`com.chin.stockanalysis.agent.pipeline.StructuredOutputParser`

- **職責**：將每步 Agent 的 Markdown 輸出解析為結構化數據
- **解析類型**：`ChainScoreResult`, `RiskValidationResult`, `SentimentAdjustResult`, `TradeExecutionPlan`
- **Map 解析**：`parseMap()` 提取 `Key: Value` 對
- **分數提取**：`parseScore()` 從文本中提取數值評分
- **置信度**：`extractConfidence()` 評估每步結果可信度

### PipelineResultFormatter（結果格式化）

`com.chin.stockanalysis.agent.pipeline.PipelineResultFormatter`

- **職責**：將 `PipelineResult` 轉化為用戶可讀的 Markdown 報告
- **實時進度**：`formatStepProgress()` 生成每步的進度狀態
- **最終報告**：`format()` 產出完整報告，含產業鏈評級、風控通過/否決、交易方案、季度环比速覽

## 調用入口

| 入口 | 調用鏈 |
|------|--------|
| AI 對話框（專家模式） | `ChatAgent.handleMessage()` → `PipelineChatAdapter.analyze()` → `UnifiedAgentRunner.run(MODE_PIPELINE)` |
| 股票詳情頁 | `StockDetailFragment.runAiAgents()` → `UnifiedAgentRunner.run(MODE_PIPELINE)` |
| 短線量化頁 | `ShortTermQuantFragment` → `AgentPipelineOrchestrator` |

## 文件清單

| 文件 | 路徑 | 說明 |
|------|------|------|
| AgentPipelineOrchestrator | `agent/pipeline/AgentPipelineOrchestrator.kt` | Pipeline 編排器 |
| PipelineResult | `agent/pipeline/PipelineResult.kt` | 結果數據結構 |
| PipelineResultFormatter | `agent/pipeline/PipelineResultFormatter.kt` | 結果格式化 |
| StructuredOutputParser | `agent/pipeline/StructuredOutputParser.kt` | 輸出解析 |
| DataFeeder | `agent/pipeline/DataFeeder.kt` | 標準化數據供給 |
| QuarterlyComparisonProvider | `agent/pipeline/QuarterlyComparisonProvider.kt` | 季度环比數據 |
| PipelineChatAdapter | `agent/chat/PipelineChatAdapter.kt` | 對話適配 |
| UnifiedAgentRunner | `agent/framework/UnifiedAgentRunner.kt` | 統一入口 |
