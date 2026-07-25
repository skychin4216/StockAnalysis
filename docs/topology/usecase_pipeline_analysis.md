# UseCase / Pipeline / Node 共享分析報告

> 生成時間: 2026-07-10

## 一、三個 UseCase 流程對比

### 1.1 XML 聲明式流程

| 步驟 | screening (量化選股) | short_term (短線量化) | mid_term (中線量化) |
|------|:---:|:---:|:---:|
| Step 1 | data_prep_pipeline | data_prep_pipeline | data_prep_pipeline |
| Step 2 | strategy_screening_pipeline (並行) | strategy_screening_pipeline (並行) | strategy_screening_pipeline (並行) |
| Step 3 | merge_boost_pipeline | merge_boost_pipeline | merge_boost_pipeline |
| Step 4 | — | ai_filter_pipeline | ai_filter_pipeline |
| Config | maxHoldings=0 | maxHoldings=3 | maxHoldings=5 |

### 1.2 實際代碼執行流程

| 階段 | screening | short_term | mid_term |
|------|-----------|------------|----------|
| 數據準備 | 市場上下文 + 快照 + 板塊股票池 | 新聞因子 + 數據導入 + CandidatePool + **ZiplinePipeline** | 市場上下文 + 新聞因子 + **熱度計算** + **主力資金緩存** |
| 策略篩選 | screenWithData + 板塊加權(內聯) | screenWithData | executeStrategy + **買入前過濾** + **新聞力度** + **輪動懲罰** + **主板過濾** |
| 信號合併 | 直接收集(無獨立合併) | 手動構建 mergedPool + 板塊加權(內聯) | **跨日聚合**(5日) + **多周期熱門** + 板塊精選 |
| AI 精選 | UI 層異步(非 Pipeline) | AIPredictionEngine.predict | **大盤分析** + **自適應參數** + AIPredictionStrategy |
| 主力過濾 | 無 | 內聯 (combined<55 攔截) | 結合 adaptiveParams 過濾 |
| 交易/建倉 | 無 | **自動建倉** + **騰籠換鳥** | **新聞攔截** + **持倉合併** + **擬合** + **騰籠換鳥** |

## 二、共享 Pipeline 清單

| Pipeline | screening | short_term | mid_term | 備註 |
|----------|:---------:|:----------:|:--------:|------|
| `data_prep_pipeline` | ✅ | ✅ | ✅ | **三者共享** — market_context → stock_pool |
| `strategy_screening_pipeline` | ✅ | ✅ | ✅ | **三者共享** — 策略 Node 動態注入，並行執行 |
| `merge_boost_pipeline` | ✅ | ✅ | ✅ | **三者共享** — signal_merge → sector_boost |
| `ai_filter_pipeline` | ❌ | ✅ | ✅ | **短線+中線共享** — ai_predict → smart_money_filter |

## 三、共享 Node 清單

| Node | module 名 | screening | short_term | mid_term | 備註 |
|------|-----------|:---------:|:----------:|:--------:|------|
| MarketContextNode | `market_context` | ✅ | ✅ | ✅ | 三者共享 |
| StockPoolNode | `stock_pool` | ✅ | ✅ | ✅ | 三者共享 |
| StrategyNode | `strategy:{id}` | ✅ | ✅ | ✅ | 三者共享，動態注入 |
| SignalMergeNode | `signal_merge` | ✅ | ✅ | ✅ | 三者共享 |
| SectorBoostNode | `sector_boost` | ✅ | ✅ | ✅ | 三者共享 |
| AIPredictNode | `ai_predict` | ❌ (UI異步) | ✅ | ✅ | 短線+中線 |
| SmartMoneyFilterNode | `smart_money_filter` | ❌ | ✅ | ✅ | 短線+中線 |
| MainBoardFilterNode | `main_board_filter` | ❌ | ❌ | ❌ | **已註冊但未使用** |

## 四、獨有步驟清單

### 4.1 screening 獨有

| 獨有項 | 說明 |
|--------|------|
| 多日快照掃描 | 支持 1/3/10/30/50/100 日週期 |
| 10 分鐘結果緩存 | 避免重複執行 |
| AI 在 UI 層異步 | 不阻塞 Pipeline |
| 實時掃描回退 | 數據不足時 scanFullMarket() |

### 4.2 short_term 獨有

| 獨有項 | 說明 |
|--------|------|
| ZiplinePipeline 因子計算 | MA5/MA20/RSI/BB 等因子 |
| CandidatePool 備選池 | 候選股票池加速篩選 |
| Agent Pipeline 分析 | 六/七智體 AI 分析 |
| 持倉週期 3 天 | 短線持倉 |

### 4.3 mid_term 獨有

| 獨有項 | 說明 |
|--------|------|
| 跨日聚合 | 5 日窗口 Top20 |
| 多周期熱門股 | 多周期板塊熱門 |
| 買入前信號過濾 | 4 條規則 |
| 新聞力度 + 輪動懲罰 | 加分/減分 |
| 大盤環境分析 | MarketAnalyzer + 自適應參數 |
| 新聞攔截 | 利空股剔除 |
| 持倉合併 | 已持倉則加權平均 |
| 擬合計算 | 策略參數優化 |
| 持倉週期 10 天 | 中線持倉 |

## 五、建議改進

### 5.1 新增中線獨有 Pipeline

| 新 Pipeline | Nodes | 說明 |
|-------------|-------|------|
| `cross_day_aggregate_pipeline` | cross_day_merge → multi_period_hot | 封裝跨日聚合 + 多周期熱門 |
| `market_analysis_pipeline` | market_analyzer → adaptive_params | 封裝大盤分析 + 自適應參數 |
| `order_generation_pipeline` | news_check → buy_filter → order_gen → holding_merge → fitting | 封裝訂單生成全流程 |

### 5.2 新增短線獨有 Pipeline

| 新 Pipeline | Nodes | 說明 |
|-------------|-------|------|
| `factor_compute_pipeline` | factor_compute (ZiplinePipeline) | 封裝因子計算 |
| `order_gen_short_pipeline` | auto_buy → swap_analyze | 封裝自動建倉 + 騰籠換鳥 |

### 5.3 目標 UseCase 結構

```
screening_usecase.xml (3-4 步):
  data_prep → strategy_screening → merge_boost → [可選] ai_filter

short_term_usecase.xml (5-6 步):
  data_prep → factor_compute → strategy_screening → merge_boost → ai_filter → order_gen_short

mid_term_usecase.xml (7-8 步):
  data_prep → strategy_screening → merge_boost → cross_day_aggregate
  → market_analysis → ai_filter → order_gen_mid
```

### 5.4 啟用 MainBoardFilterNode

將中線 Engine 中的 `filterByMainBoard()` 內聯邏輯替換為 Pipeline 中的 `main_board_filter` Node，加入 `data_prep_pipeline` 或 `strategy_screening_pipeline` 的前置階段。

## 六、XML 中文命名問題

**結論：可以解析。**

`PipelineXmlParser` 使用 `XmlPullParser` 解析 XML，屬性值（如 `name="短線量化"`、`ref="topologies/數據準備.xml"`）是純字符串，支持 UTF-8 中文。

但需要注意：
- **文件名用中文**：Android assets 支持中文文件名，但不推薦（某些構建工具可能有編碼問題）
- **XML 中的 name/description 屬性用中文**：完全沒問題，當前已在用
- **module 名用中文**：可以但增加調試難度，不推薦
