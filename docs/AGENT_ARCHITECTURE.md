# Agent 對話框架架構設計

## 總體架構

Agent 框架分為三層：意圖層、執行層、渲染層。

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              用戶輸入                                        │
│         "分析兆易創新" / "推薦幾隻半導體股" / "上證指數怎麼樣"                  │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  ① 意圖層 — ChatAgent.detectIntent()                                       │
│                                                                             │
│  StockEntityExtractor.extractSync()                                         │
│    ├─ L1: 精確代碼匹配（6位數字 / sh+6位）                                  │
│    ├─ L2: Trie 詞典匹配（精確/前綴/子串/拼音）                              │
│    └─ L3: FALLBACK_STOCK_MAP 降級（40+ 龍頭股）                             │
│                                                                             │
│  返回: UserIntent.{STOCK_ANALYSIS|INDEX_ANALYSIS|STOCK_PICKING|             │
│                   MARKET_BRIEF|GENERAL_CHAT|EXPERT}                         │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  ② 執行層 — ChatAgent.handleMessage()                                      │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  STANDARD 標準模式                                                   │   │
│  │                                                                     │   │
│  │  INDEX_ANALYSIS    → IndexAnalysisAgent.analyze()                   │   │
│  │  STOCK_PICKING     → StockPickingAgent.pickStocks()                 │   │
│  │  STOCK_ANALYSIS    → StockAnalysisAgent.analyze()  (Legacy Quick)   │   │
│  │  MARKET_BRIEF      → generateMarketBrief()                          │   │
│  │  GENERAL_CHAT      → AgentBase.react() (ReAct 推理)                 │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  EXPERT 專家模式 → Pipeline 全鏈路投研決策                           │   │
│  │                                                                     │   │
│  │  PipelineChatAdapter.analyze()                                      │   │
│  │    └─ UnifiedAgentRunner.run(MODE_PIPELINE)                         │   │
│  │         └─ AgentPipelineOrchestrator.execute()                      │   │
│  │              └─ 7步流水線 Agent 串行執行                             │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  ③ 渲染層 — ChatTabFragment / ChatAdapter / PipelineChatAdapter            │
│                                                                             │
│  普通消息 → Text Message                                                    │
│  Pipeline進度 → StepProgressCard（實時顯示每步狀態）                         │
│  Pipeline完成 → Markdown 報告                                              │
│  歧義確認 → EntityConfirmCard                                               │
└─────────────────────────────────────────────────────────────────────────────┘
```

## 核心組件

### ChatAgent（統一入口）

`com.chin.stockanalysis.agent.chat.ChatAgent`

- **職責**：接收用戶輸入 → 檢測意圖 → 調度對應 Agent → 返回格式化結果
- **雙模式**：標準模式（並行 Quick Agent）/ 專家模式（串行 Pipeline Agent）
- **股票代碼提取**：內建 `StockEntityExtractor`，支持多級降級策略

### UnifiedAgentRunner（Agent 統一調度器）

`com.chin.stockanalysis.agent.framework.UnifiedAgentRunner`

- **MODE_QUICK**：並行運行 `StockAnalysisAgent + RiskManagementAgent + SentimentAgent`，適合快速分析
- **MODE_PIPELINE**：串行執行 7 步 Pipeline，適合深度投研決策
- **數據前置**：Pipeline 模式下自動拉取 StockDataFacade + 季度环比數據

### 子 Agent 體系

| Agent | 文件 | 職責 | 調用方 |
|-------|------|------|--------|
| StockAnalysisAgent | `StockAnalysisAgent.kt` | 基本面+技術面綜合分析 | Quick 模式 |
| RiskManagementAgent | `RiskManagementAgent.kt` | 風險評估 | Quick 模式 |
| SentimentAgent | `SentimentAgent.kt` | 輿情分析 | Quick / Pipeline |
| StockPickingAgent | `StockPickingAgent.kt` | 多維度選股 | 標準模式 |
| IndexAnalysisAgent | `IndexAnalysisAgent.kt` | 大盤指數分析 | 標準模式 |
| PipelineChatAdapter | `PipelineChatAdapter.kt` | Pipeline 對話封裝 | 專家模式 |

### AgentBase（所有 Agent 的基類）

`com.chin.stockanalysis.agent.base.AgentBase`

- **核心方法**：`analyze(systemPrompt, userPrompt)` — 調用 AI 模型
- **ReAct 推理**：`react()` 方法支持思考→行動→觀察循環
- **System Prompt 構建**：`buildSystemPrompt()` 自動注入股票上下文
