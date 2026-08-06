# StockAnalysis 總架構文檔

> 最後更新：2026-08-06 | 對應 DB version: 21 | Kotlin + Android

---

## 一、系統概覽

A股智能分析 Android App，核心能力：四周期量化選股 + 日內做T + 持倉風控 + DAG Pipeline 可視化編輯。

### 技術棧

Kotlin / MVVM / ViewBinding / Room DB / OkHttp / MPAndroidChart / Gradle (KSP)

### 入口結構

```
MainActivity
├── StockTabFragment (股票行情)
├── AIChatFragment (AI 對話)
├── StrategyFragment (量化選股 · 5 Tabs)
│   ├── Tab 0: UltraShortQuantFragment  (超短線 · 1天)
│   ├── Tab 1: ShortTermQuantFragment   (短線 · 1-14天)
│   ├── Tab 2: MidTermQuantFragment     (中線 · 30-180天)
│   ├── Tab 3: LongTermQuantFragment    (長線 · 180-365天)
│   └── Tab 4: StrategyListFragment     (策略管理 · 按周期分組)
└── SettingsFragment
```

---

## 二、DAG Pipeline 引擎

### 核心抽象

```
BaseNode<I, O>          — 所有節點的基類，定義 execute(context, input): O
BasePipeline            — XML 定義的 DAG Pipeline
BaseUseCase             — 業務場景，組合多個 Pipeline
PipelineContext          — 執行上下文（stage 輸出、日誌、股票流動記錄）
DagTradeExecutor        — 通用執行器，供四個周期 Tab 復用
```

### Kahn 拓撲排序

DAG 調度使用 Kahn 算法進行拓撲排序，確保節點按依賴順序執行。同層節點並行執行。

### UseCase XML 定義

```
assets/usecases/
├── ultra_short_pipeline.xml   — 超短線（含盤中K線分析）
├── short_term_pipeline.xml    — 短線（含盤中K線分析）
├── mid_term_pipeline.xml      — 中線（含打底倉守門 + 持倉風控）
├── long_term_pipeline.xml     — 長線（含打底倉守門 + 持倉風控）
└── t_trade_pipeline.xml       — 做T/反T（12節點 · 5層）
```

### 節點註冊 (NodeRegistry)

XML `module` 屬性 → Node 工廠函數的映射。當前已註冊 ~25 個 module。

### 節點股票流動追蹤

每個節點透過 `context.recordStockFlow()` 記錄：
- inputCount / outputCount / filterCount
- filterReason（中文過濾原因）
- outputCodes（輸出的股票代碼列表）

供 UI 顯示「哪些節點過濾了多少股票」。

---

## 三、策略體系

### Strategy 接口

```kotlin
interface Strategy {
    val id: String
    val name: String
    val holdingPeriods: List<HoldingPeriod>
    fun screen(params: ScreenParams): List<StockCandidate>
}
```

### 四周期 HoldingPeriod

| 周期 | 持有天數 | 策略數量 | 特殊節點 |
|------|---------|---------|---------|
| ULTRA_SHORT | 1天 | 3 | 盤中K線分析 (5min) |
| SHORT | 1-14天 | 5 | 盤中K線分析 (5min) |
| MID | 30-180天 | 7 | 打底倉守門 + 持倉風控 + 騰龍換鳥 |
| LONG | 180-365天 | 5 | 打底倉守門 + 持倉風控 |

### 策略註冊 (StrategyEngineHolder)

啟動時註冊 20 個內置策略，透過 `StrategyEngine.setEnabled()` 控制啟用/禁用，狀態持久化到 SharedPreferences。

### 六項嚴選檢查 (StockEvaluationNode)

所有周期共用，`minPassCount` 控制通過門檻：
1. MA 收斂向上
2. 三日不創新低
3. 歷史低位 25%
4. PE 低估
5. 周期活躍
6. 冰點買入

超短線/短線：4/6 通過（66.7%）
中線/長線：5/6 通過（83.3%）

---

## 四、做T系統

### T-Trade Pipeline (12 節點 · 5 層)

```
Layer 0: bg_manager + adaptive_params + t_trade_import
Layer 1: t_holdings_load + market_context + zipline_factor
Layer 2: t_inst_intent + candle_pattern + news_strength + news_guard
Layer 3: t_signal_synthesize (聚合)
Layer 4: t_recommend_save
```

### 時段感知 (TTimeSlotStrategy)

做T信號合成時，根據當前時間段動態調整正T/反T權重：

| 時間段 | 口訣 | 正T調整 | 反T調整 | 置信度縮放 |
|--------|------|--------|--------|-----------|
| 9:30-9:40 | 跑 | -25 | +20 | 90% |
| 9:50-10:10 | 跑 | -15 | +15 | 85% |
| 10:10-10:40 | 看 | -5 | -5 | 70% |
| 10:40-11:10 | 正常 | 0 | 0 | 100% |
| 11:10-11:30 | 防 | -20 | +10 | 80% |
| 13:00-13:30 | 防 | -15 | +10 | 85% |
| 13:30-14:00 | 等 | -10 | -10 | 60% |
| 14:00-14:30 | 盯 | +10 | +10 | 100% |
| 14:30-15:00 | 決 | +15 | +15 | 110% |

### 機構意圖判斷 (TInstIntentNode)

6 維度分析：量價關係 / K線特徵 / RSI區間 / 布林帶位置 / 均線排列 / 外盤影響

輸出 5 種意圖：建倉吸貨 / 震倉洗盤 / 拉升中 / 出貨 / 無法判斷

---

## 五、持倉風控

### HoldingGuardNode (n_guard)

持倉風控評估：硬止損 / 最大回撤止損 / 階梯止盈 / 時間強制平倉 / 趨勢反轉 / RSI超買 / 放量滯漲 / 板塊走弱

### SwapWeakNode (n_swap)

騰龍換鳥：賣出最弱持倉，買入更強股票

### 持倉診斷分析 (HoldingDiagnosticAnalyzer)

騰龍換鳥/風控執行後，自動分析被賣出股票的原因，生成中文改進建議，寫入報告。

---

## 六、失敗分析 (PipelineFailureAnalyzer)

當 Pipeline 輸出訂單為 0 時，自動定位「殺手節點」——第一個將輸出降為 0 的關鍵節點。

在 UI 以中文顯示：失敗節點名稱 + 原因 + 改進建議。

---

## 七、數據層

### Room Database (version 21)

主要表：daily_snapshot / strategy_trade_order / real_position / t_trade_records / t_trade_recommendations / intraday_kline / daily_period_result

### 數據源

- 東財 API (EastMoney)：日K (klt=101) / 5分鐘K (klt=5) / 實時行情
- 新聞 API：板塊新聞 + 個股新聞 + 黑名單過濾
- 外盤數據：納斯達克 / 韓國 / 恒指

---

## 八、UI 報告體系

### DagExecResult

```
success / ordersCount / mergeSummary / swapSummary / guardSummary
patternSummary / stockFlowLines / failureAnalysis / diagnosticSummary
```

### 報告持久化

`savePipelineReport()` → `daily_period_result` 表
- `pipelineFlowJson`: 完整節點流動 + 嚴選結果 + 失敗分析 + 持倉診斷
- `filteredReasonJson`: 節點流動摘要文本

---

## 細分文檔索引

| 領域 | 文檔 |
|------|------|
| 策略分類 | [strategy-classification-analysis.md](strategy-classification-analysis.md) |
| 策略修復 | [strategy-optimization-plan.md](strategy-optimization-plan.md) |
| Pipeline 編輯器 | [topology/pipeline-editor-design.md](topology/pipeline-editor-design.md) |
| 做T時段策略 | [topology/t-trade-time-slots.md](topology/t-trade-time-slots.md) |
| 遷移歷史 | [hardcode-to-dag-migration-plan.md](hardcode-to-dag-migration-plan.md) |
| 架構重構記錄 | [agent-architecture-refactoring-plan.md](agent-architecture-refactoring-plan.md) |
| 任務日誌 | [recent-tasks-and-implementation-review.md](recent-tasks-and-implementation-review.md) |
| 持倉利潤架構 | [period_holding_profit_architecture.html](period_holding_profit_architecture.html) |
| 可視化圖表 | [architecture/index.html](architecture/index.html) · [strategy/](strategy/) · [trend_charts/](trend_charts/) |
