# 最近任務整理與代碼實現對比

> 生成日期: 2026-08-07（最後更新）
> 來源: Memory Store + Session History + DAG 日誌分析

---

## 一、任務清單（按時間倒序）

### 2026-08-07 會話

| # | 任務 | 狀態 | 備註 |
|---|------|------|------|
| 49 | SwapWeakNode 加交易時段檢查 | ✅ 完成 | 非交易時段跳過騰籠換鳥 |
| 48 | refreshPositions 持倉顯示 bug | ✅ 完成 | 用 orderTypePeriod() 替代精確匹配 + getRecent(500) |
| 47 | 持倉+選股雙區塊 UI | ✅ 完成 | 所有週期：持倉區 + 選股區並列顯示 |
| 46 | 一鍵建倉實際寫 DB | ✅ 完成 | convertPicksToPositions() 創建 StrategyTradeOrderEntity |
| 45 | DAG 日誌分析（4 個週期） | ✅ 完成 | 超短/短/中/長線日誌分析 |
| 44 | 合併自選+機構為統一推薦系統 | ✅ 完成 | user_watchlist + source 字段 + DB v23 |
| 43 | 分享流程重構 | ✅ 完成 | 微信/QQ → AI 對話框 → OCR → 分析 → 詢問保存 |
| 42 | OCR 輸入源豐富化 | ✅ 完成 | PDF/拍照/剪貼板/批量圖片等 8 種 |
| 41 | 機構推薦股票功能 | ✅ 完成 | 自定義分組 + OCR 識別 |

### 2026-08-06 會話

| # | 任務 | 狀態 | 備註 |
|---|------|------|------|
| 40 | Hardcode → DAG 全面遷移 | ✅ 完成 | 刪除硬編碼 UseCase，統一 XML DAG |
| 39 | 四周期 DAG Pipeline 實現 | ✅ 完成 | 超短線/短線/中線/長線 DAG |

### 2026-08-05 重構會話

| # | 任務 | 狀態 | 備註 |
|---|------|------|------|
| 38 | 刪除 topology/usecase/ 硬編碼 UseCase（3 個） | ✅ 完成 | ScreeningUseCase, ShortTermUseCase, MidTermUseCase → 回收站 |
| 37 | 重命名 MidTermPipelineNodes.kt → QuantTradingPipeline.kt | ✅ 完成 | 反映實際功能：量化交易 pipeline |
| 36 | PipelineNodes.kt 移到 nodes/ 文件夾 | ✅ 完成 | 9 個共用 Node 實現 |
| 35 | 設計 BaseNode / BasePipeline / BaseUseCase 基類 | ✅ 完成 | 三層抽象：Node → Pipeline → UseCase |
| 34 | 遷移 37 個 Node 到 BaseNode | ✅ 完成 | 消除重複 override val 樣板代碼 |
| 33 | 創建 topology/pipelines/ 目錄 | ✅ 完成 | 從 nodes/ 分離 composite pipeline |
| 32 | 移動 StockCheckPipeline, MidTermPipelineNodes, TTradePipelineNodes, PipelineNodes 到 pipelines/ | ✅ 完成 | 修復所有 import |
| 31 | CommonAnalysisNodes.kt 拆分 → MarketMaCheckNode.kt + StockEvaluationNode.kt | ✅ 完成 | 按功能命名 |
| 30 | StrictSelection* → StockEvaluation* 重命名 | ✅ 完成 | 更直觀的名字 |
| 29 | StockEvaluationNode XML 可配置化 | ✅ 完成 | NodeRegistry 讀 config param |
| 28 | 4 個 usecase XML 配置不同 strict_selection 參數 | ✅ 完成 | PE/離散率/活躍度/回溯天數各異 |
| 27 | 抽取 StockCheckHelpers.kt 共用工具 | ✅ 完成 | 6 個 object，消除 ~470 行重複 |
| 26 | 遷移 13 個文件使用 StockCheckHelpers | ✅ 完成 | StabilityChecker, RsiCalculator 等 |

### 2026-08-04 會話

| # | 任務 | 狀態 | 備註 |
|---|------|------|------|
| 25 | UserFocusSectorEntity + DAO | ✅ 完成 | DB v20 |
| 24 | SharedPreferences → DB 遷移（板塊記憶） | ✅ 完成 | suspend 函數 |
| 23 | showMarketMemoryDialog() 重寫 UI | ✅ 完成 | Chips + 自定義板塊 + AI 偵測 |
| 22 | StrategyMarketContext.build() 改用 DB | ✅ 完成 | |

### 2026-07-26 會話

| # | 任務 | 狀態 | 備註 |
|---|------|------|------|
| 21 | 四周期策略系統實現 | ✅ 完成 | UltraShort/Short/Mid/Long |
| 20 | HoldingPeriod 加入 Strategy 接口 | ✅ 完成 | |
| 19 | DAG path orderType 不一致 bug | ⚠️ 發現 | `ultra_short_dag` vs `UltraShortQuant` |
| 18 | checkT1AutoSell 用緩存價格 bug | ⚠️ 發現 | 應用實時價格 |
| 17 | 設計文檔未實現需求審查 | ⚠️ 部分 | defaultStopLoss/TakeProfit 下沉到策略等 |
| 16 | HoldingPeriod.holdingDays 不一致 | ⚠️ 發現 | SHORT 1..14 vs 文檔 3-5 天 |
| 15 | StrategyEngine 線程安全 | ⚠️ 發現 | plain mutableMap 非線程安全 |

### 2026-07-25 會話

| # | 任務 | 狀態 | 備註 |
|---|------|------|------|
| 14 | 閱讀全部架構文檔 | ✅ 完成 | 7 個 md 文件 |
| 13 | 理解完整項目架構 | ✅ 完成 | |
| 12 | 審查策略系統 | ✅ 完成 | 13 策略 |
| 11 | 審查 Agent 遷移計劃 | ✅ 完成 | |
| 10 | 審查拓撲編輯器 | ✅ 完成 | |

### MEMORY.md 記錄的歷史任務

| # | 任務 | 狀態 | 備註 |
|---|------|------|------|
| 9 | 海外市場分析 | ✅ 完成 | MarketAnalyzer.analyzeOverseasMarkets() |
| 8 | DB v18: t_trade_recommendations + institutional_tips | ✅ 完成 | |
| 7 | StockEntityExtractor 中文名稱解析 | ✅ 完成 | |
| 6 | getQuantType() / orderType 映射修復 | ✅ 完成 | |
| 5 | SectorPeriodTracker + sector_stocks 表 | ✅ 完成 | DB v19 |
| 4 | Room DAO 批量處理 bug 修復 | ✅ 完成 | per-ID 更新 |
| 3 | BasePositionGuardNode (n_guard) | ✅ 完成 | |
| 2 | ChatTabFragment.cleanAgentResponse() | ✅ 完成 | 清理 LLM 輸出 |
| 1 | Pipeline grouping 重構 | ✅ 完成 | MarketMaUnifiedNode + Pipeline 分組 |

---

## 二、發現的 Bug（可能未修復）

| Bug | 描述 | 嚴重度 | 狀態 |
|-----|------|--------|------|
| ~~DAG orderType 不一致~~ | ~~`orderType="ultra_short_dag"` vs 手動 `"UltraShortQuant"`~~ | ~~高~~ | ✅ 已修復 |
| ~~refreshPositions 精確匹配~~ | ~~UI 用 `==` 匹配 orderType，pipeline 用 `orderTypePeriod()` 寬鬆匹配，導致持倉數不一致~~ | ~~高~~ | ✅ 已修復 (#48) |
| ~~SwapWeakNode 無交易時段檢查~~ | ~~非交易時段仍執行騰籠換鳥~~ | ~~中~~ | ✅ 已修復 (#49) |
| ~~選股區空持倉時不顯示~~ | ~~refreshPositions 空持倉時 early return，選股區被跳過~~ | ~~中~~ | ✅ 已修復 (#47) |
| checkT1AutoSell 用緩存價格 | 用 todayStocks 緩存價格而非實時價格，可能導致止損/止盈判斷錯誤 | 高 | 待修復 |
| holdingDays 不一致 | SHORT=1..14 天（文檔說 3-5），MID=30..180（文檔說 10-30） | 中 | 待修復 |
| StrategyEngine 線程安全 | strategies/lastResults 用 plain mutableMapOf，多線程訪問可能 crash | 中 | 待修復 |

---

## 三、代碼實現 vs 任務需求對比

### 已完整實現
- ✅ 四周期策略系統（超短/短/中/長）
- ✅ DAG Pipeline XML 架構（V2 拓撲排序）
- ✅ 6 項嚴選檢查（StockCheckPipeline + StockEvaluationNode）
- ✅ XML 可配置化（每個周期不同參數）
- ✅ 共用工具抽取（StockCheckHelpers）
- ✅ 三層基類（BaseNode / BasePipeline / BaseUseCase）
- ✅ T 交易系統（TTradeEngine + DAG pipeline）
- ✅ 拓撲編輯器 UI
- ✅ Agent/Legacy 雙路由
- ✅ 板塊記憶 DB 遷移
- ✅ Pipeline 分組 + 模板變量
- ✅ 海外市場分析
- ✅ K 線形態偵測
- ✅ 新聞攔截 + 技術過濾
- ✅ 統一推薦系統（自選+機構合併，user_watchlist + source 字段）
- ✅ OCR 多源輸入（PDF/拍照/剪貼板/批量圖片等 8 種）
- ✅ 分享流程（微信/QQ → AI 對話框 → OCR → 分析 → 保存）
- ✅ 持倉+選股雙區塊 UI（所有週期並列顯示）
- ✅ 一鍵建倉（選股區 → DB 寫入正式持倉）
- ✅ SwapWeakNode 交易時段守門

### 可能遺漏 / 需確認
- ⚠️ **T+1 自動賣**：checkT1AutoSell 是否已改用實時價格？
- ⚠️ **非交易時段信號記錄**：generate_orders 在非交易時段應記錄信號但不生成訂單（目前直接跳過）
- ⚠️ **盤中 K 線分析**：系統只看收盤狀態，不區分盤中走勢
- ⚠️ **Level2 數據質量檢測**：佔位值（1.000/0.1000）應被偵測並跳過過濾
- ⚠️ **長線持倉消失**：用戶報告長線 5 只持倉 UI 只顯示 2 只，DB 無交易記錄，需排查是否有其他進程修改了 status

---

## 四、DAG 日誌分析結論（2026-08-07）

### 分析文件
- `agent_dag_on_超短綫.log`（21:50 執行）
- `agent_dag_on_短綫.log`（21:54 執行）
- `agent_dag_on_中綫.log`（22:01 執行）
- `agent_dag_on_长綫.log`（22:06 執行）

### 發現

**1. 騰籠換鳥非交易時段執行**
- 所有 4 個 DAG 的 `n_swap` 節點都在非交易時段執行
- 原因：`SwapWeakNode` 沒有交易時段檢查
- 影響：實際無害（倉位足夠，未觸發換股）
- 修復：已加 `ChinaMarketTradingHours.a股是否交易中()` 守門

**2. 長線持倉未被 DAG 賣出**
- AutoSellEngine 評估 12 持倉，3 觸發賣出
- `holding_guard` 長期持倉保護過濾掉全部 3 個（常規止盈止損 → 不賣）
- 最終：5 只長線持倉全部健康，無賣出信號
- 但用戶 UI 只顯示 2 只 → 根因是 `refreshPositions()` 用精確匹配 `== "LongTermQuant"`，漏掉 `orderType = "long_term"` 的記錄

**3. 短線/中線各賣出 1 只**
- 短線：厦门钨业（最大回撤止損 -33.74%）
- 中線：钒钛股份（吊燈止損：跌破最高價-3.0×ATR）
- 這兩隻是合規的風控賣出

**4. 持倉顯示不一致根因**
- Pipeline 用 `orderTypePeriod()` 寬鬆匹配（substring, case-insensitive）+ `getRecent(500)`
- UI 用 `==` 精確匹配 + `getRecent(100)`
- 修復：UI 改用 `orderTypePeriod()` + `getRecent(500)`
