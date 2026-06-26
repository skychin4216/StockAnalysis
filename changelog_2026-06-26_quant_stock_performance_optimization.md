# 量化選股性能優化 Changelog

**日期**: 2026-06-26  
**分支**: dev  
**狀態**: 已修改，未提交  
**關聯日誌**: `E:\test1.log` 診斷報告

---

## 診斷結論

「中綫選股」及「量化選股 -> 導入數據」耗時過長的核心原因：

| 排名 | 瓶頸 | 影響 |
|------|------|------|
| 1 | EastMoney K-line API 大量 `unexpected end of stream`，且無重試機制 | 每次失敗浪費 8~15 秒超時等待 |
| 2 | `fillMissingNames()` 每次都遍歷全部股票重新獲取名稱 | 相當於把 222 只股票再請求一遍 |
| 3 | K-line 併發度 20，可能觸發服務器限流 | 連接頻繁斷開 |
| 4 | AI 大模型調用超時 + 單一共享實例排隊 | 單次可阻塞 1 分 38 秒 |

---

## 修改文件清單

| 文件 | 行數變化 | 修改類型 |
|------|---------|---------|
| `HistoricalDataFetcher.kt` | +83/-13 | 性能優化 + 耗時日誌 |
| `ShortTermQuantFragment.kt` | +63/-5 | 耗時日誌（6 步） |
| `MidTermQuantFragment.kt` | +29/-3 | 耗時日誌（3 步） |
| `SimulationTradeEngine.kt` | +59/-12 | 耗時日誌（9 步精選全流程） |

---

## 1. HistoricalDataFetcher.kt

### 1.1 降低 K-line API 併發度

```kotlin
// Before
stocks.chunked(20).forEachIndexed { chunkIdx, batch ->

// After
val concurrency = 10 // 降低併發，避免被限流
stocks.chunked(concurrency).forEachIndexed { chunkIdx, batch ->
```

### 1.2 EastMoney 增加重試機制

```kotlin
// Before: 只嘗試 1 次
for (attempt in 0..0) {

// After: 最多重試 3 次 + 指數退避
val maxRetries = 2
for (attempt in 0..maxRetries) {
    if (attempt > 0) {
        val delayMs = 500L * (1 shl attempt) // 1000ms, 2000ms
        kotlinx.coroutines.delay(delayMs)
    }
    // ...
    if (attempt == maxRetries) {
        Log.w(TAG, "  EastMoney gave up on $code after $maxRetries retries")
    }
}
```

### 1.3 fillMissingNames 只補缺失名稱的股票

```kotlin
// Before: 遍歷所有股票
val allCodes = mutableSetOf<String>()
for (s in shots) allCodes.add(s.code)

// After: 只處理 name.isBlank() 的股票
val missingNameCodes = mutableSetOf<String>()
for (s in shots) {
    if (s.name.isBlank()) missingNameCodes.add(s.code)
}
if (missingNameCodes.isEmpty()) {
    Log.d(TAG, "  fillMissingNames: all stocks have names, skip")
    return 0
}
```

### 1.4 添加三步耗時日誌

```
========== FETCH START ==========
--- Step 1: Realtime API ---
  Step 1 done: xxxms, records=xx
--- Step 2: K-line API (222 stocks, concurrency=10) ---
  K-line chunk 0: 10/222 done, xxx records, xxxms
  Step 2 done: xxxms, success=xx, failed=xx
  Step 3 done: xxxms
========== FETCH COMPLETE ==========
  Records: xx  Stocks: 222  Time: xxxms
  Breakdown: Step1=xx  Step2=xx  Step3=xx
```

---

## 2. ShortTermQuantFragment.kt（短線選股）

`runPipeline()` 中添加 6 步耗時統計：

```
[ShortTerm] Step 1: importing data, todaySnaps=xx, lastImport=...
[ShortTerm] Step 1 done: import=xxxms
[ShortTerm] Step 2 done: prepareFromDb=xxxms, stocks=xx
[ShortTerm] Step 3 done: Zipline computeAll=xxxms, valid=xx
[ShortTerm] Step 4 done: x strategies=xxxms, hits=xx
[ShortTerm] Step 5 done: merge pool=xxxms, size=xx
[ShortTerm] Step 6 done: AI predict=xxxms, picks=xx
[ShortTerm] ====== TOTAL: xxxms ======
[ShortTerm] Breakdown: import=xx feed=xx zipline=xx strategy=xx merge=xx ai=xx
```

同時為單個策略添加慢查詢日誌（超過 1000ms 打印）：
```kotlin
val sElapsed = System.currentTimeMillis() - sStart
if (sElapsed > 1000) {
    Log.d(TAG, "[ShortTerm] Strategy ${strategy.id} took ${sElapsed}ms")
}
```

---

## 3. MidTermQuantFragment.kt（中線選股）

`executeTrade()` 中添加 3 步耗時統計：

```
[MidTerm] Step 1: importing data, todaySnaps=xx, lastImport=...
[MidTerm] Step 1 done: import=xxxms
[MidTerm] Step 2 done: runTradeSession=xxxms, buyOrders=xx
[MidTerm] Step 3 done: swapWeakHoldings=xxxms, swapped=x
[MidTerm] ====== TOTAL: xxxms ======
[MidTerm] Breakdown: import=xx session=xx swap=xx
```

---

## 4. SimulationTradeEngine.kt（中線引擎內部）

`runTradeSession()` 中為 9 步精選流程的每一步添加毫秒級計時：

```
【Step 1-4】精選池: xx 隻, xxxms
获取交易日数据: xx只, xxxms
統一數據層: xx隻 → xx隻 (主板=true), xxxms
熱度計算: xx隻, xxxms
【Step 5】x 個策略命中..., xxxms
  策略名: 输出Top15 — ..., xxxms  (單策略也有計時)
【Step 6a】跨5天聚合 → Topxx: ..., xxxms
【Step 6b】多周期热门股: +xx隻 — ..., xxxms
【Step 7】板塊精選: +xx 隻, xxxms
【Step 8】用戶搜索xx隻 + 智能體xx隻 + 自選xx隻, xxxms
【Step 9】組裝過濾: xxxms
AI 精选: xx只, xxxms
生成买入订单: xx只, xxxms
拟合完成: xxxms
━━━ 9步精選完成 ━━━ TOTAL: xxxms
Breakdown: pool=xx data=xx convert=xx heat=xx strategy=xx crossDay=xx hot=xx sector=xx user=xx filter=xx ai=xx order=xx
```

---

## 預期優化效果

| 優化項 | 預計效果 |
|--------|---------|
| EastMoney 重試 (3 次) | 減少 50%~70% 因網絡斷開導致的失敗 |
| 併發度 20→10 | 減少被服務器限流的概率 |
| fillMissingNames 只補缺失 | 數據完整時完全跳過，節省 1~2 分鐘 |
| 詳細日誌 | 精準定位耗時瓶頸，便於後續持續優化 |

**合計預計**: 從 5 分鐘+ 降至 30 秒~2 分鐘

---

## Logcat 過濾關鍵字

運行後可在 logcat 中搜索以下關鍵字查看耗時：

- `[ShortTerm]` — 短線選股各步驟
- `[MidTerm]` — 中線選股各步驟
- `HDFetcher` — 數據導入詳情
- `SimTradeEngine` — 引擎內部 9 步精選
- `EastMoney retry` — 重試記錄
- `fillMissingNames` — 名稱補全記錄

---

## 未來可進一步優化的方向

1. **增量更新**: `fetchAllHistoricalData(60)` 每次拉 60 天全量，可改為只拉「缺失的日期段」
2. **AI 調用異步化**: `AIPredictionEngine.predict` 目前是同步阻塞，可改為異步或手動觸發
3. **後台預加載**: 在非交易時段預先導入數據，避免用戶操作時等待
4. **數據庫索引**: `dailySnapshotDao().getByDate(date)` 若無索引，大數據量時會變慢
