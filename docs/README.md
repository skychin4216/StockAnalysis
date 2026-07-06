# StockAnalysis - A股智能投研分析平台

## 項目總覽

StockAnalysis 是一款融合 AI 對話、實時行情、量化策略、全鏈路投研決策的 Android 應用，面向 A 股市場提供從數據獲取到投資決策的完整閉環。

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          StockAnalysis App                                  │
│                                                                             │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐                   │
│  │  AI對話   │  │ 股票行情  │  │ 量化策略  │  │ 模擬交易  │  ← 四大核心 Tab   │
│  │ ChatTab  │  │ StockTab │  │ Strategy │  │ Settings │                   │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘                   │
│       │             │             │             │                          │
│       └─────────────┴─────────────┴─────────────┘                          │
│                         │                                                  │
│                         ▼                                                  │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                        數據層 (Data Layer)                          │   │
│  │  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────────┐    │   │
│  │  │ 實時行情    │  │ 基本面數據  │  │ 板塊熱度    │  │ AI模型接口  │    │   │
│  │  │ Realtime   │  │ Fundamental│  │ Sector     │  │ AI Provider│    │   │
│  │  └────────────┘  └────────────┘  └────────────┘  └────────────┘    │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                    AI Agent 層 (Agent Layer)                        │   │
│  │                                                                     │   │
│  │  ┌──────────────────┐        ┌──────────────────────────┐          │   │
│  │  │  Legacy 快速分析  │        │  Pipeline 全鏈路投研決策   │          │   │
│  │  │ UnifiedAgentRunner│        │ AgentPipelineOrchestrator│          │   │
│  │  │  ├─ StockAnalysis │        │  ├─ 7步Agent流水線        │          │   │
│  │  │  ├─ RiskManagement│        │  ├─ 數據鐵律校驗          │          │   │
│  │  │  └─ Sentiment     │        │  ├─ 季度环比分析          │          │   │
│  │  └──────────────────┘        │  └─ 結構化輸出解析          │          │   │
│  │                               └──────────────────────────┘          │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

## 技術棧

| 層級 | 技術 |
|------|------|
| 語言 | Kotlin 100% |
| 最低 SDK | Android API 26 |
| UI 框架 | Jetpack Fragment + RecyclerView + MPAndroidChart |
| 網絡 | OkHttp + Gson |
| 數據庫 | Room |
| AI 模型 | DeepSeek / 硅基流動 / 豆包 / 阿里 Qwen (可切換) |
| 架構 | Clean Architecture + Repository Pattern |

## 核心模塊導航

| 模塊 | 文檔 | 說明 |
|------|------|------|
| 數據層 | [DATA_LAYER_ARCHITECTURE.md](DATA_LAYER_ARCHITECTURE.md) | 5源並發行情 + 基本面數據 + 板塊熱度 |
| AI Agent | [AGENT_ARCHITECTURE.md](AGENT_ARCHITECTURE.md) | 意圖識別 + 多 Agent 調度 + ReAct 推理 |
| Pipeline 投研 | [PIPELINE_ARCHITECTURE.md](PIPELINE_ARCHITECTURE.md) | 全鏈路 7 步流水線投研決策 |
| 季度环比 | [QUARTERLY_MODULE_ARCHITECTURE.md](QUARTERLY_MODULE_ARCHITECTURE.md) | Q2/Q3/Q4/Q1 任意季度环比分析 |
| UI 層 | [UI_ARCHITECTURE.md](UI_ARCHITECTURE.md) | 4 Tab 導航 + 股票詳情頁 + 對話頁 |
| API 參考 | [API_REFERENCE.md](API_REFERENCE.md) | 完整的 API 調用說明 |

## 快速開始

```bash
# 1. 配置 API Key（編輯項目根目錄 api_keys_local.properties）
# 2. 清除舊數據庫（升級必須）
adb shell pm clear com.chin.stockanalysis
# 3. 構建並安裝
./gradlew assembleDebug && ./gradlew installDebug
```
