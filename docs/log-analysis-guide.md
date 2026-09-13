# StockAnalysis App 日誌分析指南

## 核心原則

1. **先按 App PID 過濾** — 日誌文件通常 100MB+，混雜大量系統日誌。用 PID 篩選是第一步。
2. **從關鍵 TAG 入手** — 不要全文搜索，用已知 TAG 定位關鍵流程。
3. **按時間線重建流程** — DAG Pipeline 有明確的 Layer 執行順序，按時間排列可還原完整流程。

## 關鍵 TAG 速查表

| TAG | 含義 | 搜索時機 |
|-----|------|----------|
| `DagPipeline` | DAG 拓撲分層、Layer 執行、超時 | Pipeline 整體流程 |
| `DagTradeExecutor` | 訂單生成、持倉合併、風控賣出、報告 | Pipeline 結果摘要 |
| `UseCaseLoader` | UseCase 加載、策略注入 | Pipeline 啟動 |
| `QuantFragmentBase` | UI 層接收結果、selectedStocks | UI 交互 |
| `n_orders` / `generate_orders` | 買入訂單生成、候選過濾 | 選股邏輯 |
| `n_guard` / `holding_guard` | 持倉風控（止損/止盈賣出） | 賣出邏輯 |
| `n_merge` / `signal_merge` | 多策略信號聚合 | 候選合併 |
| `n_strict` / `n_strict_eval` | 嚴選檢查（6 項） | 過濾邏輯 |
| `n_fit` / `fitting_save` | 擬合計算（權重優化） | 性能問題 |
| `n_swap` / `swap_weak` | 騰龍換鳥 | 換股邏輯 |
| `n_merge_pos` / `position_merge` | 持倉合併 | 持倉更新 |
| `AutoSellEngine` | 自動賣出引擎 | 止損/止盈執行 |
| `AppBgRunner` | 後台監控（自選股價格） | 後台任務 |
| `t_trade/*` | 做T決策各周期 | 做T流程 |

## 分析流程模板

### 1. Pipeline 執行結果（快速概覽）

```
grep "DagTradeExecutor\|Pipeline 完成\|UseCaseLoader.*執行完成" log.txt
```

關注：
- `訂單生成: N 筆` — 選了多少股票
- `持倉合併: 新增N筆, 總持倉N筆` — 持倉變化
- `持倉風控: 賣出N筆` — 風控結果
- `Pipeline 完成: 成功/失敗, 耗時 Nms` — 是否超時

### 2. 訂單生成詳情（選股邏輯）

```
grep "generate_orders\|n_orders" log.txt
```

關注：
- `輸入: N 只候選 [sh600xxx(股票名), ...]` — 候選列表，檢查是否有重複
- `過濾 1 只 sh600xxx: 今日已買入` — 過濾原因
- `輸出: N 個訂單` — 最終結果
- `無候選通過嚴格條件，寧缺勿濫` — 全部被過濾

### 3. 持倉風控分析（賣出邏輯）

```
grep "holding_guard\|AutoSellEngine" log.txt
```

關注：
- `輸入: N 只 xxx 持倉` — 評估範圍
- `賣出: 股票名(原因)` — 賣出決策
- `最大回撤止損: 從高點¥XX回撤XX%` — 止損原因
- `全部健康，無賣出信號` — 正常

### 4. 性能問題排查

```
grep "Layer.*完成\|Node 超時\|失敗" log.txt
```

關注：
- 每個 Layer 的耗時（正常 < 1s，異常 > 10s）
- `Node 超時: 擬合計算+保存` — n_fit 超時（120s/180s）
- `DAG Pipeline 完成: 成功, 耗時 Nms` — 總耗時

### 5. 做T決策分析

```
grep "t_trade\|t_synth\|t_hold" log.txt
```

關注：
- `完成: N 條信號` — 是否產生做T信號
- `持倉載入` — 是否有持倉數據（0信號常見原因）
- `periodType` — 周期映射是否正確

## 常見 Bug 模式

| 症狀 | 可能原因 | 排查方法 |
|------|----------|----------|
| 總耗時 > 120s | n_fit 擬合超時 | grep "Layer.*完成" 找最慢 Layer |
| 候選股重複 | SignalMergeNode 未去重 | grep "輸入: N 只候選" 檢查列表 |
| 先賣後買同一只 | guard 賣出未傳遞到 orders | 對比 n_guard 賣出列表和 n_orders 輸入 |
| 做T全周期 0 信號 | periodType 未傳遞 | grep "t_hold" 檢查持倉載入 |
| 總虧損 > 100% | 百分比直接相加 | 檢查 HoldingDiagnosticAnalyzer |
| 長線 0 候選 | 寧缺勿濫（正常） | grep "嚴選詳情" 查看過濾原因 |

## 日誌提取技巧

### 按 PID 過濾
```bash
# Windows
adb logcat | findstr "PID"
# 或從文件
grep " 18170 " log.txt  # PID 在進程名後的數字

# Linux/Mac
grep "^$(date +%m-%d) $(date +%H:%M:%S).*PID" log.txt
```

### 按時間範圍
```bash
grep "^08-07 12:3[0-5]" log.txt  # 12:30-12:39
```

### 按進程名
```bash
grep "com.chin.stockanalysis" log.txt
```

## DAG Pipeline 層級結構參考

### 超短線 (ultra_short) — 15 層
```
Layer 0: n_import, n_ctx, n_bg, n_adaptive
Layer 1: n_pool, n_ma_unified, n_guard
Layer 2: n_cand, strategy_0~2
Layer 3: n_intraday, n_merge
Layer 4-10: n_boost → n_bounce → n_strict → n_ancestral → n_inst_tips → n_smart → n_ai/n_candle
Layer 11: n_orders
Layer 12: n_swap, n_fit  ← 性能瓶頸
Layer 13: n_merge_pos
Layer 14: n_t1sell
```

### 短線 (short_term) — 17 層
比超短線多: n_multihot, n_heat, n_zipline, n_news_str, n_rot_pen, n_newsguard, n_crosstab

### 中線 (mid_term) — 15 層
比短線多: n_sector_pool, n_crossday, n_defensive, n_base_guard

## 數據流圖

```
策略節點(strategy_0~N) → SignalMergeNode(n_merge) → 去重+排序
  → BoostNode(n_boost) → BounceNode(n_bounce) → StrictNode(n_strict)
  → SmartMoney(n_smart) → AI/News → GenerateOrders(n_orders)
  → SwapWeak(n_swap) + FittingSave(n_fit)  [並行]
  → PositionMerge(n_merge_pos) → T1Sell(n_t1sell)
```

## 關鍵文件位置

| 功能 | 文件 |
|------|------|
| DAG 執行器 | `topology/xml/DagTradeExecutor.kt` |
| DAG 核心 | `topology/core/DagPipeline.kt` |
| 節點註冊 | `topology/xml/NodeRegistry.kt` |
| 通用節點 | `topology/nodes/PipelineNodes.kt` |
| 交易節點 | `topology/pipelines/QuantTradingPipeline.kt` |
| 做T節點 | `topology/pipelines/TTradePipelineNodes.kt` |
| UseCase XML | `assets/usecases/{period}_usecase.xml` |
| Pipeline XML | `assets/usecases/{period}_pipeline.xml` |
| UI 基類 | `strategy/trade/QuantFragmentBase.kt` |
| 持倉診斷 | `topology/xml/HoldingDiagnosticAnalyzer.kt` |
