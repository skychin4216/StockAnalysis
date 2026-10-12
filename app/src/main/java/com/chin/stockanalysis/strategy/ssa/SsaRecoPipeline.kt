package com.chin.stockanalysis.strategy.ssa

import android.content.Context
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.data.HistoricalDataFetcher
import com.chin.stockanalysis.strategy.ssa.SsaHybridEngine.Bar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ## 每日荐股管线（全量构建 + 每日增量更新）
 *
 * 数据链路：688 核心池（[HistoricalDataFetcher.getCoreStockPool]）→
 * Room 日线快照库（dailySnapshotDao，[HistoricalDataFetcher.fetchStockLatest] 联网补拉）→
 * [Bar] → [SsaRecoStore]（seedMissing 幂等构建 / dailyUpdate 增量步进）→ 推荐卡片。
 *
 * 调用约定（全部挂起，UI 层用 lifecycleScope/viewModelScope 后台触发）：
 *  - [buildAll]：首次全量构建，幂等可续跑可取消（中断后再调自动跳过已完成票）；
 *    688 票预计 20~40 分钟（网络拉取为主），进度回调逐票上报。
 *  - [refreshAll]：每日更新，按每票本地最新日自动补拉缺口日线（覆盖节假日），
 *    4 路并行拉取 → 串行增量步进；688 票约 2~5 分钟。
 *  - 触发时机由 UI/调用方决定（如推荐页打开时检查当日是否已更新）。
 */
class SsaRecoPipeline(context: Context) {

    /** 荐股快照存储（filesDir/ssa_reco） */
    val store: SsaRecoStore = SsaRecoStore(File(context.filesDir, "ssa_reco"))

    private val appContext = context.applicationContext

    companion object {
        /** 初次构建联网拉取的历史天数（约 900 自然日 ≈ 600 交易日） */
        private const val SEED_FETCH_DAYS = 900
        /** 初次构建本地最少根数（低于则联网补拉） */
        private const val SEED_MIN_LOCAL = 500
        /** 有效日线最少根数（低于视为无数据） */
        private const val MIN_BARS = 150
        /** 每日增量并行拉取路数 */
        private const val PARALLEL = 4
    }

    /**
     * 单票日线：Room 库优先；[incremental]=false（构建）本地不足 [SEED_MIN_LOCAL] 根时联网补拉；
     * [incremental]=true（每日更新）按本地最新日距今天数补拉缺口（+10 缓冲覆盖节假日，久未打开自动补长段）。
     * @return 过滤无效值后的日期升序 Bar 列表；不足 [MIN_BARS] 根返回 null
     */
    suspend fun loadDailyBars(code: String, incremental: Boolean): List<Bar>? =
        withContext(Dispatchers.IO) {
            val db = StockDatabase.getInstance(appContext)
            suspend fun read() =
                runCatching { db.dailySnapshotDao().getByCode(code, 2000) }.getOrDefault(emptyList())
            val fetcher = HistoricalDataFetcher(appContext)
            var local = read()
            if (incremental) {
                val gapDays = try {
                    val last = local.maxOfOrNull { it.date }
                    if (last == null) SEED_FETCH_DAYS
                    else (ChronoUnit.DAYS.between(LocalDate.parse(last), LocalDate.now()) + 10L)
                        .coerceIn(10L, SEED_FETCH_DAYS.toLong()).toInt()
                } catch (e: Exception) { SEED_FETCH_DAYS }
                runCatching { fetcher.fetchStockLatest(code, gapDays) }
                local = read()
            } else if (local.size < SEED_MIN_LOCAL) {
                runCatching { fetcher.fetchStockLatest(code, SEED_FETCH_DAYS) }
                local = read()
            }
            val bars = local.asSequence()
                .filter { it.close > 0 && it.high > 0 && it.low > 0 }
                .sortedBy { it.date }
                .map { Bar(it.date, it.open.ifZero(it.close), it.high, it.low, it.close) }
                .toList()
            if (bars.size < MIN_BARS) null else bars
        }

    private fun Double.ifZero(f: Double) = if (this == 0.0) f else this

    /**
     * 全量构建（幂等可续跑）：对核心池中缺状态的票逐票 classifyOnly→recommendedParams→seed→落盘。
     * 单票数据拉取失败自动留待下次；[isCancelled] 随时中断。
     * @return 本次完成（成功 seed）票数
     */
    suspend fun buildAll(
        codes: List<String>,
        isCancelled: () -> Boolean = { false },
        onProgress: (done: Int, total: Int, code: String) -> Unit = { _, _, _ -> }
    ): Int = withContext(Dispatchers.IO) {
        store.seedMissing(
            codes,
            barsProvider = { code -> runBlocking { loadDailyBars(code, incremental = false) } },
            isCancelled = isCancelled,
            onProgress = onProgress
        )
    }

    /**
     * 每日更新：[PARALLEL] 路并行按票补拉缺口日线 → [SsaRecoStore.dailyUpdate] 增量步进
     * （重复日期自动去重；label 迁移自动全史重 seed）。
     * @return 更新后的全部推荐卡片（含未变动票的缓存卡片）
     */
    suspend fun refreshAll(
        isCancelled: () -> Boolean = { false },
        onFetch: (done: Int, total: Int) -> Unit = { _, _ -> },
        onUpdate: (done: Int, total: Int, code: String) -> Unit = { _, _, _ -> }
    ): List<SsaRecoStore.RecoCard> = withContext(Dispatchers.IO) {
        val codes = store.loadIndex().keys.toList()
        val barsByCode = HashMap<String, List<Bar>>(codes.size)
        val sem = Semaphore(PARALLEL)
        val done = AtomicInteger(0)
        if (codes.isNotEmpty()) {
            coroutineScopeWide {
                codes.map { code ->
                    launch {
                        sem.withPermit {
                            if (!isCancelled()) {
                                runCatching { loadDailyBars(code, incremental = true) }
                                    .getOrNull()?.let { synchronized(barsByCode) { barsByCode[code] = it } }
                            }
                        }
                        onFetch(done.incrementAndGet(), codes.size)
                    }
                }.joinAll()
            }
        }
        if (isCancelled()) store.loadLatestCards()
        else store.dailyUpdate(barsByCode, onProgress = onUpdate)
    }

    /** 小工具：跨平台协程作用域（避免在 API 26 以下用 coroutineScope 嵌套问题） */
    private suspend fun <T> coroutineScopeWide(block: suspend kotlinx.coroutines.CoroutineScope.() -> T): T =
        kotlinx.coroutines.coroutineScope(block)
}
