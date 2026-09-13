# 策略體系優化計劃 v1.0

> 日期：2026-07-28
> 狀態：✅ 已完成（2026-08-01）

## 一、修復現有策略缺陷

### 1.1 BollingerBandStrategy — 指標計算錯誤（嚴重）

**問題：** 把股票池中不同股票的價格按 timestamp 排序當作「時間序列」計算布林帶。數學上完全無意義。

**修復方案：**
- 改為從 DB (`DailySnapshotDao.getByCode(code, 20)`) 讀取單股連續 20 天收盤價
- 計算標準布林帶：MA20 ± 2σ
- 突破判定：當日收盤價 > upper band 且成交量 > 1.5× 均量
- 新增：帶寬收窄後突破（squeeze breakout）加分

### 1.2 RSIDivergenceStrategy — 指標計算錯誤（嚴重）

**問題：** 同 BollingerBand，用不同股票的價格算 RSI，結果是隨機噪聲。

**修復方案：**
- 從 DB 讀取單股連續 15 天收盤價（period+1）
- 計算標準 RSI(14)：avgGain / avgLoss
- 超賣反彈判定：RSI < 30 且當日漲幅 > 0（反轉確認）
- 新增：底背離檢測（股價新低但 RSI 未新低）加分

### 1.3 LowValuationStrategy — 用股價代替估值（邏輯錯誤）

**問題：** 給 5 元股 40 分、200 元股 5 分。會系統性買入垃圾股。

**修復方案：**
- 廢除股價評分，改用 StockRealtime 已有的 `pe`、`pb`、`roeTTM` 字段
- 估值評分(40%)：PE < 15 滿分，PE 15-25 遞減，PE > 40 或 < 0 淘汰
- 質量評分(30%)：ROE > 15% 滿分，毛利率 > 30% 加分
- 安全邊際(30%)：PB < 2 滿分，市值 > 200 億加分（流動性保障）
- 淘汰條件：PE < 0（虧損）、ST 股、日均成交 < 5000 萬

### 1.4 VolumeBreakStrategy — 用絕對成交額代替量比

**問題：** 大盤股常年日成交 10 億，10 億對它不是放量。當前邏輯系統性偏好大盤股。

**修復方案：**
- 從 DB 讀取該股近 10 日均量（`getByCode(code, 10)` 取 volume 均值）
- 計算真實量比 = 今日成交量 / 10 日均量
- 篩選條件：量比 >= 2.0 且漲幅 >= 2% 且價格突破開盤價
- 評分：量比(40%) + 突破幅度(30%) + 漲幅(30%)

### 1.5 TurnoverFilterStrategy — 名不副實

**問題：** 策略名叫「換手率活躍」但實際用成交額。StockRealtime 已有 `turnoverRate` 字段。

**修復方案：**
- 直接使用 `stock.turnoverRate` 字段
- 篩選條件：換手率 3%-15%（過低不活躍，過高可能是出貨）且漲幅 >= 1%
- 評分：換手率適中度(40%) + 漲幅(30%) + 量價配合(30%)
- 新增：連續 3 日換手率遞增加分（從 DB 讀取）

---

## 二、新增策略

### 2.1 TrendFollowingStrategy — 中線均線趨勢跟蹤

**定位：** 填補中線缺少真正趨勢策略的空白

**邏輯：**
- 均線多頭排列：MA20 > MA60 > MA120
- MACD 確認：DIF > DEA 且 DIF > 0（零軸上方）
- 回踩確認：近 3 日最低價觸及 MA20 但未跌破 MA60
- 入場：回踩後首日收陽（close > open）
- 評分：均線排列強度(35%) + MACD 動能(30%) + 回踩精準度(20%) + 量能(15%)
- 數據：DB 讀取 120 天日 K

**參數：**
- holdingPeriods = [MID]
- signalExpiryHours = 120
- maxResults = 10

### 2.2 SectorRotationStrategy — 板塊輪動加速度

**定位：** 短線板塊策略增強，追蹤「加速度」而非靜態排名

**邏輯：**
- 從 DB sector_daily_record 讀取近 5 日板塊排名
- 計算加速度：排名連續上升（如 5→3→1）的板塊
- 選板塊內漲幅前 3 且量比 > 1.5 的個股
- 過濾：板塊內跌多漲少時不選（板塊分化風險）
- 評分：板塊加速度(40%) + 個股相對強度(30%) + 量能(30%)

**參數：**
- holdingPeriods = [SHORT]
- signalExpiryHours = 48
- maxResults = 10

### 2.3 MarketSentimentStrategy — 情緒週期策略

**定位：** 超短線，基於市場情緒指標擇時

**邏輯：**
- 情緒指標：漲停數/跌停數比、連板高度（最高連板天數）、炸板率
- 從 DB 當日快照計算：漲停（changePct >= 9.5%）數量、跌停數量
- 冰點信號：跌停 > 漲停 × 2 且連板高度 <= 2 → 次日反彈概率大
- 高潮信號：漲停 > 50 且連板高度 >= 5 → 風險警示，不買入
- 冰點日選股：超跌反彈（近 3 日跌幅 > 10% 但今日企穩）
- 評分：情緒位置(40%) + 超跌程度(30%) + 企穩信號(30%)

**參數：**
- holdingPeriods = [ULTRA_SHORT]
- signalExpiryHours = 4
- defaultStopLoss = -3%
- defaultTakeProfit = +5%
- maxPositions = 3

---

## 三、基礎設施增強

### 3.1 ATR 動態追蹤止損

**定位：** 不是選股策略，是持倉管理工具，供 AutoSellEngine 調用

**邏輯：**
- ATR(14) = 14 日 True Range 均值
- 追蹤止損線 = 持倉期間最高價 - N × ATR（N 默認 2.5）
- 止損線只上移不下移
- 不同週期 N 值：ULTRA_SHORT=1.5, SHORT=2.0, MID=2.5, LONG=3.0
- 觸發條件：當前價 < 止損線 → 賣出

**實現位置：** `strategy/risk/ATRTrailingStop.kt`

### 3.2 策略註冊

新增策略註冊到 `StrategyEngineHolder.init()`：
- TrendFollowingStrategy
- SectorRotationStrategy
- MarketSentimentStrategy

---

## 四、實施順序

| 優先級 | 任務 | 原因 |
|--------|------|------|
| P0 | 修復 BollingerBand + RSI | 信號完全無效，等於隨機買入 |
| P0 | 重寫 LowValuation | 系統性買入垃圾股 |
| P1 | 修復 VolumeBreak + TurnoverFilter | 邏輯名不副實，影響信號質量 |
| P1 | 新增 TrendFollowing | 中線核心空白 |
| P2 | 新增 ATRTrailingStop | 持倉風控增強 |
| P2 | 新增 SectorRotation + MarketSentiment | 策略池豐富度 |

---

## 五、修改記錄（未提交工作區變更）

> 截至 2026-07-29，以下變更存在於工作區但尚未 git commit。

### 5.1 P0 策略修復 — 已完成

| 文件 | 變更內容 |
|------|---------|
| `BollingerBandStrategy.kt` | 改為從 DB 讀取單股連續 20 天收盤價計算標準 MA20±2σ 布林帶；新增 squeeze breakout 帶寬收窄檢測加分；量能確認改為今日量/均量比；熊市閾值 45、其他 30 |
| `RSIDivergenceStrategy.kt` | 改為從 DB 讀取單股連續 15 天收盤價計算標準 RSI(14)；超賣反彈判定 RSI<30 且當日漲幅>0；新增底背離檢測加分 |
| `LowValuationStrategy.kt` | 廢除股價評分，改用 PE/PB/ROE 字段；估值評分 40% + 質量評分 30% + 安全邊際 30%；淘汰 PE<0、ST 股、低流動性標的 |
| `VolumeBreakStrategy.kt` | 改為從 DB 讀取近 10 日均量計算真實量比；篩選量比≥2.0 且漲幅≥2%；評分：量比 40% + 突破幅度 30% + 漲幅 30% |
| `TurnoverFilterStrategy.kt` | 改用 `stock.turnoverRate` 字段；篩選換手率 3%-15% 且漲幅≥1%；評分：換手率適中度 40% + 漲幅 30% + 量價配合 30% |

### 5.2 新增策略 — 已完成

| 文件 | 類型 | 說明 |
|------|------|------|
| `TrendFollowingStrategy.kt` | 新建 | 中線均線趨勢跟蹤：MA20>MA60>MA120 多頭排列 + MACD 確認 + 回踩入場 |
| `SectorRotationStrategy.kt` | 新建 | 短線板塊輪動加速度：從 DB 讀取近 5 日板塊排名，計算加速度選股 |
| `MarketSentimentStrategy.kt` | 新建 | 超短線情緒週期：漲停/跌停比、連板高度、炸板率，冰點日選超跌反彈 |
| `TrendScoreStrategy.kt` | 新建 | 趨勢加減分策略：基礎分 50，均線排列+ADX+動量+量價調整；≥70 買入、<40 賣出參考；不過濾標的，保留所有輸入 |
| `CyclicalLowPositionStrategy.kt` | 新建 | 長線週期低位左側佈局：週期性行業商品價格與股價背離時建倉 |
| `ATRTrailingStop.kt` | 新建 | ATR 動態追蹤止損工具：ATR(14) 計算止損線，棘輪機制只上移；ULTRA_SHORT N=1.5 至 LONG N=3.0 |

### 5.3 DAG Pipeline Hardcode 補齊

| 文件 | 變更類型 | 內容 |
|------|---------|------|
| `HardcodeCompatNodes.kt` | 新建 | 5 個 Hardcode 相容節點：CandidatePoolNode（候選池過濾）、ZiplineFactorNode（因子預計算）、SectorStockPoolNode（板塊精選池）、T1AutoSellNode（T+1 自動賣出）、CrossTabPublishNode（跨 Tab 發布） |
| `MidTermPipelineNodes.kt` | 修改 | PositionMergeNode 訂單狀態 `PENDING` → `BUYING`，與 Hardcode 路徑對齊 |
| `NodeRegistry.kt` | 修改 | 註冊 5 個新 module：`candidate_pool`、`zipline_factor`、`sector_stock_pool`、`t1_auto_sell`、`crosstab_publish` |
| `ultra_short_pipeline.xml` | 修改 | +n_cand（候選池過濾）、+n_t1sell（T+1 賣出），9→11 節點 |
| `short_term_pipeline.xml` | 修改 | +n_cand、+n_zipline、+n_crosstab，14→17 節點 |
| `mid_term_pipeline.xml` | 修改 | +n_sector_pool、+n_cand、+n_crosstab，17→20 節點 |
| `long_term_pipeline.xml` | 修改 | +n_cand，11→12 節點 |

### 5.4 AI 熱門板塊動態化

| 文件 | 變更內容 |
|------|---------|
| `AIHotSectorProvider.kt` | 熱門板塊改為動態獲取實時市場數據，移除硬編碼板塊名稱 |
| `AgentPipelineOrchestrator.kt` | 整合動態熱門板塊，Agent F 數據採集使用實時賽道 |
| `DataFeeder.kt` | 數據底座支持動態賽道注入 |
| `skills_config.json` | Agent F/3/1/2/5/D 的 systemPrompt 中 `{today_hot_sectors}` 佔位符，運行時注入實時板塊 |

### 5.5 四週期 Fragment UI 統一

| 文件 | 變更內容 |
|------|---------|
| `UltraShortQuantFragment.kt` | 統一持倉週期信息格式；僅主板開關；DAG Pipeline 分支 |
| `ShortTermQuantFragment.kt` | 精簡 200 行冗餘代碼；遷移 Agent 分析按鈕 |
| `MidTermQuantFragment.kt` | DAG Pipeline 分支；持倉週期信息統一 |
| `LongTermQuantFragment.kt` | 持倉週期信息統一；僅主板開關 |
| `StrategyListFragment.kt` | row2「龍頭輪動」按鈕替換為「Agent 分析」（紫色 #6A1B9A，觸發 runAIPipeline）；移除 hotSectorRow 冗餘 Agent 按鈕 |

### 5.6 基礎設施增強

| 文件 | 變更內容 |
|------|---------|
| `DagPipeline.kt` | DAG 核心執行器改進 |
| `PipelineContext.kt` | Pipeline 上下文增強 |
| `PipelineNodes.kt` | Pipeline 節點更新 |
| `DagTradeExecutor.kt` | DAG 交易執行器統一邏輯 |
| `PipelineXmlParser.kt` | XML 解析器更新 |
| `UseCaseLoader.kt` | `injectStrategiesToDag()` 動態注入策略節點 |
| `StrategyEngine.kt` | 策略引擎改進 |
| `StrategyEngineHolder.kt` | 策略註冊更新 |
| `MarketAdaptiveStrategy.kt` | 市場自適應參數改進 |
| `CandidatePool.kt` | 候選池邏輯改進 |
| `Level2DataProvider.kt` | Level2 數據提供器增強 |
| `AutoSellEngine.kt` | 自動賣出引擎改進 |

### 5.7 文檔與測試

| 文件 | 變更類型 | 內容 |
|------|---------|------|
| `agent-architecture-refactoring-plan.md` | 新建 | Agent 架構重構計劃，含 Phase 0 Hardcode 補齊方案 |
| `strategy-optimization-plan.md` | 新建 | 本文檔，策略體系優化計劃 |
| `deepseek_opencode.md` | 新建 | DeepSeek 開發參考文檔 |
| `StrategyDataIntegrityTest.kt` | 修改 | 策略數據完整性測試更新 |
| `docs/deepseek_strategy-classification-analysis.md` | 刪除 | 過時分類分析文檔 |

### 5.8 變更統計

| 類別 | 修改文件 | 新建文件 | 刪除文件 |
|------|---------|---------|---------|
| 策略修復 | 5 | 0 | 0 |
| 新增策略 | 0 | 6 | 0 |
| DAG Pipeline | 7 | 1 | 0 |
| AI 動態化 | 4 | 0 | 0 |
| Fragment UI | 5 | 0 | 0 |
| 基礎設施 | 12 | 0 | 0 |
| 文檔/測試 | 1 | 3 | 1 |
| **合計** | **34** | **10** | **1** |

---

## 六、做T系統優化（2026-08-01）

### 6.1 問題診斷

原有做T系統存在以下問題：
1. 後台監控僅掃描真實持倉（`RealPosition`），未覆蓋各週期模擬持倉
2. 推薦發出後無持續跟蹤，無法知道目標價是否曾觸及
3. 缺少收盤統計，無法評估做T建議的準確率
4. 推薦記錄無週期屬性，無法按週期查看統計

### 6.2 優化方案

**1. 全週期監控**

`AppBackgroundRunner.monitorTTradeOpportunities()` 擴展為掃描 4 個週期（UltraShort/Short/Mid/Long）的模擬持倉 + 真實持倉，每 5 分鐘執行一次。

**2. 價格軌跡跟蹤**

新增 `TTradeEngine.trackOutcomeForRecommendations()`：
- 每次監控時更新推薦的 `peak_price_after` / `trough_price_after`
- 檢查目標價是否觸及 → `markTargetHit()`
- T_BUY：目標價 ≥ targetPrice 即成功
- RT_SELL：目標價 ≤ targetPrice 即成功

**3. 收盤統計**

新增 `TTradeEngine.markDayEnd()`：
- 15:00-15:05 自動執行
- 將所有 PENDING 推薦標記為 TARGET_MISSED（未觸及）或保留 TARGET_HIT
- 計算虛擬盈虧（基於 peak/trough 價格）

**4. 週期維度**

`TTradeRecommendationEntity` 新增 `period_type` 字段，信號生成時自動帶入所屬週期。UI 按週期展示統計。

### 6.3 新增統計指標

| 指標 | 計算方式 | 用途 |
|------|---------|------|
| 虛擬成功率 | targetHit 數 / 總推薦數 × 100% | 評估信號質量 |
| 實際成功率 | 已執行中盈利數 / 已執行數 × 100% | 評估用戶執行效果 |
| 平均虛擬盈虧 | AVG(virtual_profit_pct) | 評估信號預期收益 |

### 6.4 DB 遷移（v17 → v18）

```sql
ALTER TABLE t_trade_recommendations ADD COLUMN period_type TEXT NOT NULL DEFAULT '';
ALTER TABLE t_trade_recommendations ADD COLUMN peak_price_after REAL NOT NULL DEFAULT 0.0;
ALTER TABLE t_trade_recommendations ADD COLUMN trough_price_after REAL NOT NULL DEFAULT 0.0;
ALTER TABLE t_trade_recommendations ADD COLUMN target_hit INTEGER NOT NULL DEFAULT 0;
ALTER TABLE t_trade_recommendations ADD COLUMN virtual_profit_pct REAL NOT NULL DEFAULT 0.0;
```

### 6.5 修改文件清單

| 文件 | 變更類型 | 內容 |
|------|---------|------|
| `TTradeModels.kt` | 修改 | TTradeRecommendationEntity 新增 5 字段；TTradeSignal 新增 periodType；新增 OutcomeStatsRow、DailyTSummary 數據類；DAO 新增 6 個查詢方法 |
| `TTradeEngine.kt` | 修改 | generateSignals 帶入 periodType；saveRecommendations 支持 periodType；新增 trackOutcomeForRecommendations()、markDayEnd()、getDailySummary()、getDailyAllPeriodSummary() |
| `AppBackgroundRunner.kt` | 修改 | monitorTTradeOpportunities 擴展為全週期掃描 + 價格軌跡跟蹤 + 收盤結算 |
| `QuantFragmentBase.kt` | 修改 | showTTradeMenu 獲取 dailySummary；showTTradeDialog 顯示虛擬成功率；推薦歷史支持 TARGET_HIT/TARGET_MISSED 狀態 |
| `StockDatabase.kt` | 修改 | DB version 17→18；新增 MIGRATION_17_18 |

