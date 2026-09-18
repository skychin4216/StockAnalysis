package com.chin.stockanalysis.strategy.backtest

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.BacktestMetaEntity
import com.chin.stockanalysis.stock.database.BacktestSelectedStockEntity
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.data.HistoricalDataFetcher
import com.chin.stockanalysis.strategy.data.LeaderStockPool
import com.chin.stockanalysis.strategy.topology.pipelines.StockCheckPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.min

/**
 * ## 工作台「回溯+拟合」多周期全流程引擎
 *
 * 移植自 smalltools/_full_cycle_backtest.py（固定本金口径，无未来函数）。
 *
 * - 超短线：隔日卖（T+1 开盘买入 → T+2 收盘卖出）
 * - 短线：连跌 3 日 / 跌破 5 日线 / 10 天到期卖出
 * - 中线/长线：持有 + 做T降成本（高抛 +0.5% / 低吸 -0.5%）+ 止盈 / 止损 / 到期卖出
 * - 大盘状态：先判 CRASH（近 6 日三指数均跌 ≥4%），否则三指数 MA 排列 triple_vote
 * - 统计口径：固定本金（每笔等额 1000 股、收益累加不复利）
 * - 增量回溯：按周期记录「最后已回溯信号日」，只回溯新增区间
 * - 选中记录：中/长线长期保留；超短/短线只保留最近 30 天
 */
object FullCycleBacktestEngine {

    private const val TAG = "FullCycleBacktest"
    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** 每笔固定本金基准（股） */
    const val FIXED_QTY = 1000

    /** 做T仓位比例 */
    const val DEFAULT_T_RATIO = 0.4

    /** 高抛/低吸触发（相对买入价） */
    const val T_UP_PCT = 0.5
    const val T_DOWN_PCT = -0.5

    /** 数据加载上限（天）：配合 550+ 天留存，取 900 保障 MA250 回看 */
    private const val LOAD_LIMIT = 900

    /** 指数代码（大盘状态判定） */
    private val INDEX_CODES = listOf("sh000001", "sz399001", "sz399006")

    // ───────────────────────── 枚举 / 数据类 ─────────────────────────

    enum class MarketState(val key: String, val label: String, val selTrend: String) {
        BULLISH("BULLISH", "结构性牛市", "BULLISH"),
        OSCILLATION("OSCILLATION", "震荡期", "NEUTRAL"),
        BEARISH("BEARISH", "下跌期", "BEAR"),
        CRASH("CRASH", "暴跌期", "BEAR");

        companion object {
            fun fromKey(k: String?): MarketState? = values().firstOrNull { it.key == k }
        }
    }

    enum class SellStyle { NEXTDAY, STREAK, HOLD }

    /** 卖出参数（超短/短线由 style 决定，中线/长线由 止盈/止损/持有 决定） */
    data class SellParams(
        val period: String,
        val style: SellStyle,
        val maxHoldDays: Int,
        val takeProfitPct: Double = 20.0,
        val stopLossPct: Double = -10.0,
        val tRatio: Double = DEFAULT_T_RATIO,
        val streakSellDays: Int = 3,
        val maBreakDays: Int = 5
    )

    data class Signal(
        val code: String,
        val name: String,
        val signalDate: String,
        val marketState: MarketState,
        val buyDate: String,
        val buyPrice: Double
    )

    data class TradeRecord(
        val period: String,
        val code: String,
        val name: String,
        val signalDate: String,
        val marketState: MarketState,
        val buyDate: String,
        val buyPrice: Double,
        val sellDate: String?,
        val sellPrice: Double?,
        val retPct: Double,
        val exitReason: String,
        val tProfitPct: Double
    )

    data class PeriodStats(
        val period: String,
        val signalCount: Int,
        val realizedCount: Int,
        val avgRet: Double,
        val winRate: Double,
        val fixedCum: Double,
        val profitFactor: Double,
        val maxDrawdown: Double,
        val byState: Map<MarketState, StateStats>,
        val report: String
    )

    data class StateStats(
        val count: Int,
        val avgRet: Double,
        val winRate: Double,
        val fixedCum: Double,
        val profitFactor: Double
    )

    data class FittedParams(
        val period: String,
        val marketState: MarketState,
        val maxHoldDays: Int,
        val takeProfitPct: Double,
        val stopLossPct: Double,
        val avgRet: Double,
        val winRate: Double,
        val sampleCount: Int
    )

    data class FitResult(
        val period: String,
        val fitted: Map<MarketState, FittedParams>,
        val matrixJson: String,
        val report: String
    )

    // ───────────────────────── 周期模板 ─────────────────────────

    private val PERIOD_SELL_PARAMS: Map<String, SellParams> = mapOf(
        "超短" to SellParams(period = "超短", style = SellStyle.NEXTDAY, maxHoldDays = 2),
        "短线" to SellParams(period = "短线", style = SellStyle.STREAK, maxHoldDays = 10, streakSellDays = 3, maBreakDays = 5),
        "中线" to SellParams(period = "中线", style = SellStyle.HOLD, maxHoldDays = 15, takeProfitPct = 20.0, stopLossPct = -10.0),
        "长线" to SellParams(period = "长线", style = SellStyle.HOLD, maxHoldDays = 30, takeProfitPct = 40.0, stopLossPct = -12.0)
    )

    private fun pipelineFor(period: String, marketTrend: String?): StockCheckPipeline = when (period) {
        "超短" -> StockCheckPipeline.ultraShortParams(marketTrend)
        "短线" -> StockCheckPipeline.shortTermParams(marketTrend)
        "中线" -> StockCheckPipeline.midTermParams(marketTrend)
        "长线" -> StockCheckPipeline.longTermParams(marketTrend)
        else -> StockCheckPipeline.midTermParams(marketTrend)
    }

    /** 拟合网格（中线/长线） */
    private val FIT_GRIDS: Map<String, Triple<IntArray, DoubleArray, DoubleArray>> = mapOf(
        "中线" to Triple(intArrayOf(8, 10, 12, 15, 20), doubleArrayOf(15.0, 20.0, 25.0), doubleArrayOf(-6.0, -8.0, -10.0)),
        "长线" to Triple(intArrayOf(15, 20, 25, 30, 40), doubleArrayOf(25.0, 30.0, 40.0, 50.0), doubleArrayOf(-8.0, -10.0, -12.0))
    )

    private fun metaKeyLastBacktest(period: String) = "last_backtest_$period"
    private fun metaKeyFitMatrix(period: String) = "fit_matrix_$period"

    // ═══════════════════════════════════════════════════════════
    // 公开 API
    // ═══════════════════════════════════════════════════════════

    /**
     * 对某个周期做增量回溯。
     * [windowStart] 无历史记录时的起始日期（yyyy-MM-dd）；已有 last_backtest 记录则从其后继续。
     * 结果落库到 backtest_selected_stock，并返回统计报告。
     */
    suspend fun runPeriod(
        context: Context,
        period: String,
        windowStart: String? = null,
        progress: suspend (String) -> Unit = {}
    ): PeriodStats = withContext(Dispatchers.Default) {
        val db = StockDatabase.getInstance(context.applicationContext)
        // 代码模板仅作兜底：卖出参数以 backtest_params.json 的 9 格 sell_rules 为准
        val fallbackParams = PERIOD_SELL_PARAMS[period] ?: return@withContext emptyStats(period)
        BacktestParamsLoader.load(context)
        progress("[$period] 加载数据…")
        val universe = loadUniverse(context)
        val stockSnaps = loadStockSnaps(db, universe)
        val indexSnaps = loadStockSnaps(db, INDEX_CODES.toSet())
        val allDates = db.dailySnapshotDao().getAvailableDates(LOAD_LIMIT).sorted()
        if (allDates.size < 40) return@withContext emptyStats(period).also {
            Log.w(TAG, "[$period] 历史数据不足（${allDates.size} 天）")
        }

        // 增量：已回溯过的信号日不再回溯
        val lastMeta = db.backtestMetaDao().get(metaKeyLastBacktest(period))
        var startIdx = 0
        if (lastMeta != null && lastMeta.isNotBlank()) {
            val idx = allDates.indexOfFirst { it > lastMeta }
            if (idx < 0) return@withContext emptyStats(period).also {
                progress("[$period] 无新增交易日，跳过")
            }
            startIdx = idx
        } else if (windowStart != null) {
            val idx = allDates.indexOfFirst { it >= windowStart }
            if (idx > 0) startIdx = idx
        }
        // 留出持仓期，保证卖出模拟完整（按该周期所有状态中最大的 maxHold 计算缓冲）
        val bufferHold = BacktestParamsLoader.maxHold(period, fallbackParams.maxHoldDays)
        val endIdx = allDates.size - (bufferHold + 2)
        if (endIdx <= startIdx) {
            return@withContext emptyStats(period).also {
                progress("[$period] 无新增可回溯区间（需 $bufferHold 天持仓缓冲）")
            }
        }

        val trades = mutableListOf<TradeRecord>()
        val records = mutableListOf<BacktestSelectedStockEntity>()
        val now = System.currentTimeMillis()

        for (di in startIdx until endIdx) {
            val asof = allDates[di]
            if (di % 10 == 0) progress("[$period] 回溯中 ${di - startIdx}/${endIdx - startIdx} 日（$asof）")
            val state = marketStateAt(indexSnaps, allDates, di)
            val pipeline = pipelineFor(period, state.selTrend)
            val signals = selectSignals(stockSnaps, pipeline, allDates, di, state)
            if (signals.isEmpty()) continue
            // 按大盘状态取 9 格卖出参数（smalltools 拟合矩阵），JSON 不可用时回退代码模板
            val params = BacktestParamsLoader.sellParams(context, period, state) ?: fallbackParams
            for (sig in signals) {
                val trade = simulateTrade(sig, stockSnaps, allDates, params)
                if (trade == null) continue
                trades.add(trade)
                records.add(BacktestSelectedStockEntity(
                    period = period,
                    code = trade.code,
                    name = trade.name,
                    signalDate = trade.signalDate,
                    marketState = trade.marketState.key,
                    buyDate = trade.buyDate,
                    buyPrice = trade.buyPrice,
                    sellDate = trade.sellDate,
                    sellPrice = trade.sellPrice,
                    retPct = trade.retPct,
                    exitReason = trade.exitReason,
                    tProfitPct = trade.tProfitPct,
                    createdAt = now
                ))
            }
        }

        // 落库 + 更新进度
        if (records.isNotEmpty()) {
            db.backtestSelectedStockDao().insertAll(records)
            Log.i(TAG, "[$period] 记录 ${records.size} 笔选中 -> 落库")
        }
        // 无论当日有无信号，都记录最后扫描日 → 增量回溯只跑新增区间
        db.backtestMetaDao().put(BacktestMetaEntity(metaKeyLastBacktest(period), allDates[endIdx - 1]))
        // 短期只保留 30 天
        pruneShortTerm(db)

        val stats = buildStats(period, trades)
        progress(stats.report)
        stats
    }

    /** 一次性跑完四个周期 */
    suspend fun runAll(
        context: Context,
        windowStart: String? = null,
        progress: suspend (String) -> Unit = {}
    ): List<PeriodStats> {
        val results = mutableListOf<PeriodStats>()
        for (period in PERIOD_SELL_PARAMS.keys) {
            try {
                results.add(runPeriod(context, period, windowStart, progress))
            } catch (e: Exception) {
                Log.e(TAG, "[$period] 回溯异常: ${e.message}", e)
                progress("[$period] 回溯异常: ${e.message?.take(80)}")
            }
        }
        return results
    }

    /**
     * 按大盘状态对中线/长线做网格拟合（固定本金口径），产出状态参数矩阵并落库。
     */
    suspend fun fitByState(
        context: Context,
        period: String,
        progress: suspend (String) -> Unit = {}
    ): FitResult = withContext(Dispatchers.Default) {
        val grid = FIT_GRIDS[period] ?: return@withContext FitResult(period, emptyMap(), "{}", "周期不支持拟合")
        val db = StockDatabase.getInstance(context.applicationContext)
        progress("[$period] 收集信号…")
        val universe = loadUniverse(context)
        val stockSnaps = loadStockSnaps(db, universe)
        val indexSnaps = loadStockSnaps(db, INDEX_CODES.toSet())
        val allDates = db.dailySnapshotDao().getAvailableDates(LOAD_LIMIT).sorted()
        val baseParams = PERIOD_SELL_PARAMS[period] ?: return@withContext FitResult(period, emptyMap(), "{}", "周期不支持")

        // 收集所有可用信号（含买入日后的前瞻行情，最多 maxHoldMax 天）
        val maxHoldMax = grid.first.max()
        val startIdx = allDates.size - (maxHoldMax + 2)
        val sigs = mutableListOf<ForwardSignal>()
        val seen = HashSet<String>()
        for (di in 0 until startIdx) {
            val asof = allDates[di]
            val state = marketStateAt(indexSnaps, allDates, di)
            val pipeline = pipelineFor(period, state.selTrend)
            val signals = selectSignals(stockSnaps, pipeline, allDates, di, state)
            for (sig in signals) {
                val key = "${sig.code}_$asof"
                if (!seen.add(key)) continue
                val fwd = forwardSeries(stockSnaps, allDates, sig.code, di + 1, maxHoldMax) ?: continue
                sigs.add(ForwardSignal(sig, fwd))
            }
        }
        progress("[$period] 共 ${sigs.size} 个信号，开始网格拟合…")

        val byState = sigs.groupBy { it.sig.marketState }
        val fitted = LinkedHashMap<MarketState, FittedParams>()
        val matrix = JSONObject()
        val reportLines = StringBuilder()

        for (st in MarketState.values()) {
            val group = byState[st] ?: emptyList()
            if (group.size < 3) {
                progress("[$period] ${st.label}(${st.key}) 样本 ${group.size} < 3，跳过拟合")
                continue
            }
            var best: FittedParams? = null
            for (hold in grid.first) {
                for (tp in grid.second) {
                    for (sl in grid.third) {
                        val rets = group.mapNotNull {
                            simulateHold(it.fwd, it.sig.buyPrice, hold, tp, sl, baseParams.tRatio)?.retPct
                        }
                        if (rets.isEmpty()) continue
                        val s = statsOf(rets)
                        val avg = s[0]; val wr = s[1]
                        if (best == null || avg > best!!.avgRet || (avg == best!!.avgRet && wr > best!!.winRate)) {
                            best = FittedParams(period, st, hold, tp, sl, avg, wr, rets.size)
                        }
                    }
                }
            }
            if (best != null) {
                fitted[st] = best
                matrix.put(st.key, JSONObject().apply {
                    put("hold", best.maxHoldDays)
                    put("tp", best.takeProfitPct)
                    put("sl", best.stopLossPct)
                    put("avg", best.avgRet)
                    put("wr", best.winRate)
                    put("n", best.sampleCount)
                })
                reportLines.append("\n  [${st.label} ${st.key}] 信号 ${best.sampleCount} → 持有${best.maxHoldDays}天 止盈+${best.takeProfitPct}% 止损${best.stopLossPct}%  (平均${best.avgRet.let { "%.2f".format(it) }}% 胜率${best.winRate.let { "%.1f".format(it) }}%)")
            }
        }

        val matrixJson = matrix.toString()
        db.backtestMetaDao().put(BacktestMetaEntity(metaKeyFitMatrix(period), matrixJson))
        val report = "[$period] 状态参数矩阵拟合完成（固定本金口径）" + reportLines.toString()
        progress(report)
        FitResult(period, fitted, matrixJson, report)
    }

    /** 读取已拟合的参数矩阵 */
    suspend fun loadFitMatrix(context: Context, period: String): Map<MarketState, FittedParams> {
        return try {
            val db = StockDatabase.getInstance(context.applicationContext)
            val json = db.backtestMetaDao().get(metaKeyFitMatrix(period)) ?: return emptyMap()
            parseFitMatrix(period, json)
        } catch (_: Exception) { emptyMap() }
    }

    /**
     * 当前大盘状态检测（实时）。
     * CRASH：近 6 个交易日三指数平均跌幅 ≤ -4%；否则三指数 MA 排列 triple_vote。
     */
    suspend fun detectCurrentState(context: Context): MarketState = withContext(Dispatchers.Default) {
        try {
            val db = StockDatabase.getInstance(context.applicationContext)
            val indexSnaps = loadStockSnaps(db, INDEX_CODES.toSet())
            val allDates = db.dailySnapshotDao().getAvailableDates(30).sorted()
            if (allDates.isEmpty()) MarketState.OSCILLATION else marketStateAt(indexSnaps, allDates, allDates.size - 1)
        } catch (_: Exception) { MarketState.OSCILLATION }
    }

    /** 清理短期记录（>30 天） */
    suspend fun pruneShortTerm(db: StockDatabase) {
        try {
            val cutoff = LocalDate.now().minusDays(30).format(DATE_FMT)
            val n = db.backtestSelectedStockDao().pruneShortTerm(listOf("超短", "短线"), cutoff)
            if (n > 0) Log.i(TAG, "清理超短/短线 >30 天记录 $n 条")
        } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════════════════════
    // 内部实现
    // ═══════════════════════════════════════════════════════════

    private data class Day(val date: String, val open: Double, val high: Double, val low: Double, val close: Double)
    private data class ForwardSignal(val sig: Signal, val fwd: List<Day>)

    private fun loadUniverse(context: Context): Set<String> {
        val indexes = HistoricalDataFetcher.getDefaultIndexCodes()
        val pool = HistoricalDataFetcher.getCoreStockPool(context) + LeaderStockPool.getMainlineCodes(context)
        return pool - indexes
    }

    private suspend fun loadStockSnaps(db: StockDatabase, codes: Set<String>): Map<String, List<DailySnapshotEntity>> {
        val map = HashMap<String, List<DailySnapshotEntity>>(codes.size)
        for (code in codes) {
            try {
                map[code] = db.dailySnapshotDao().getByCode(code, LOAD_LIMIT).sortedBy { it.date }
            } catch (e: Exception) {
                Log.w(TAG, "加载 $code 失败: ${e.message}")
            }
        }
        return map
    }

    /**
     * 大盘状态判定（移植 Python market_state）。
     * CRASH：近 6 个交易日（含当日）三指数各自 (最新/最早-1)，均值 ≤ -4%。
     * 否则三指数 MA5/MA10/MA20 排列投票：≥2 多 → BULLISH，≥2 空 → BEARISH，否则 OSCILLATION。
     */
    private fun marketStateAt(indexSnaps: Map<String, List<DailySnapshotEntity>>, allDates: List<String>, pos: Int): MarketState {
        val look = min(pos + 1, 6)
        val recDates = if (look <= 0) emptyList() else allDates.subList(maxOf(0, pos + 1 - look), pos + 1)
        if (recDates.size >= 2) {
            val drops = INDEX_CODES.mapNotNull { code ->
                val snaps = indexSnaps[code] ?: return@mapNotNull null
                val byDate = snaps.filter { it.date in recDates }
                if (byDate.size < 2) null
                else (byDate.last().close / byDate.first().close - 1) * 100
            }
            if (drops.isNotEmpty() && drops.average() <= -4.0) return MarketState.CRASH
        }
        var up = 0
        var down = 0
        for (code in INDEX_CODES) {
            val snaps = indexSnaps[code] ?: continue
            val upTo = snaps.filter { it.date <= allDates[pos] }
            if (upTo.size < 20) continue
            val closes = upTo.map { it.close }
            val ma5 = closes.takeLast(5).average()
            val ma10 = closes.takeLast(10).average()
            val ma20 = closes.takeLast(20).average()
            when {
                ma5 > ma10 && ma10 > ma20 -> up++
                ma5 < ma10 && ma10 < ma20 -> down++
            }
        }
        return when {
            down >= 2 -> MarketState.BEARISH
            up >= 2 -> MarketState.BULLISH
            else -> MarketState.OSCILLATION
        }
    }

    /** 选出当日通过管道的股票（全量，不截断 TopN，与 Python 一致） */
    private fun selectSignals(
        stockSnaps: Map<String, List<DailySnapshotEntity>>,
        pipeline: StockCheckPipeline,
        allDates: List<String>,
        di: Int,
        state: MarketState
    ): List<Signal> {
        val asof = allDates[di]
        val result = mutableListOf<Signal>()
        for ((code, snaps) in stockSnaps) {
            val idx = upperBoundDate(snaps, asof)
            if (idx < 20) continue
            val sub = snaps.subList(0, idx)
            try {
                val check = pipeline.analyzeSnaps(sub)
                if (check.passed && check.stockName != "数据不足" && check.stockName != "异常") {
                    val buyDate = if (di + 1 < allDates.size) allDates[di + 1] else continue
                    val buySnap = findSnap(snaps, buyDate) ?: continue
                    if (buySnap.open <= 0.0) continue
                    result.add(Signal(code, check.stockName.ifBlank { buySnap.name }, asof, state, buyDate, buySnap.open))
                }
            } catch (_: Exception) {}
        }
        return result
    }

    /**
     * 模拟交易（移植 Python simulate_nextday / simulate_streak / simulate_hold）。
     * 返回 null 表示无后续数据（跳过该信号）。
     */
    private fun simulateTrade(
        sig: Signal,
        stockSnaps: Map<String, List<DailySnapshotEntity>>,
        allDates: List<String>,
        params: SellParams
    ): TradeRecord? {
        val snaps = stockSnaps[sig.code] ?: return null
        val buyIdx = allDates.indexOf(sig.buyDate)
        if (buyIdx < 0) return null
        val fwd = forwardSeries(stockSnaps, allDates, sig.code, buyIdx, params.maxHoldDays) ?: return null
        val outcome = when (params.style) {
            SellStyle.NEXTDAY -> simulateNextday(fwd, sig.buyPrice, params.maxHoldDays)
            SellStyle.STREAK -> simulateStreak(fwd, sig.buyPrice, params, snaps, allDates, buyIdx)
            SellStyle.HOLD -> simulateHold(fwd, sig.buyPrice, params.maxHoldDays, params.takeProfitPct, params.stopLossPct, params.tRatio)
        } ?: return null
        return TradeRecord(
            period = params.period, code = sig.code, name = sig.name,
            signalDate = sig.signalDate, marketState = sig.marketState,
            buyDate = sig.buyDate, buyPrice = sig.buyPrice,
            sellDate = outcome.sellDate, sellPrice = outcome.sellPrice,
            retPct = outcome.retPct, exitReason = outcome.reason, tProfitPct = outcome.tProfitPct
        )
    }

    private data class Outcome(val retPct: Double, val sellDate: String?, val sellPrice: Double?, val reason: String, val tProfitPct: Double)

    /** 超短线：隔日卖（T+1 开盘买入 → T+2 收盘卖出，缺失则顺延） */
    private fun simulateNextday(fwd: List<Day>, entry: Double, maxHold: Int): Outcome? {
        if (fwd.isEmpty()) return null
        val day = fwd.firstOrNull() ?: return null
        val ret = (day.close / entry - 1) * 100
        return Outcome(ret, day.date, day.close, "隔日卖", 0.0)
    }

    /** 短线：连跌 3 日 / 跌破 5 日线 / 10 天到期 */
    private fun simulateStreak(
        fwd: List<Day>,
        entry: Double,
        params: SellParams,
        snaps: List<DailySnapshotEntity>,
        allDates: List<String>,
        buyIdx: Int
    ): Outcome? {
        if (fwd.isEmpty()) return null
        var prevClose = entry
        var streak = 0
        var lastDay: Day? = null
        var lastRet = 0.0
        for (k in fwd.indices) {
            val day = fwd[k]
            lastDay = day
            lastRet = (day.close / entry - 1) * 100
            if (day.close < prevClose) streak++ else streak = 0
            prevClose = day.close
            // 跌破 N 日线（maBreakDays，smalltools 9 格参数，短线 BULLISH=5 / OSCI·BEARISH=8）：取当日之前（不含）N 日收盘
            val histIdx = upperBoundDate(snaps, day.date)
            if (histIdx >= params.maBreakDays) {
                val maN = snaps.subList(histIdx - params.maBreakDays, histIdx).map { it.close }.average()
                if (day.close < maN) {
                    return Outcome(lastRet, day.date, day.close, "跌破${params.maBreakDays}日线", 0.0)
                }
            }
            if (streak >= params.streakSellDays) {
                return Outcome(lastRet, day.date, day.close, "连跌${params.streakSellDays}日", 0.0)
            }
        }
        // 到期卖出
        val last = lastDay ?: return null
        return Outcome(lastRet, last.date, last.close, "持有到期", 0.0)
    }

    /**
     * 中线/长线：持有 + 做T降成本 + 止盈/止损/到期。
     * 做T现金账户模型：高抛（当日最高 ≥ 买入价+0.5%）卖 tQty，低吸（最低 ≤ -0.5%）买回。
     * 止盈/止损先于当日做T检查；到期按收盘结算。
     */
    private fun simulateHold(
        fwd: List<Day>,
        entry: Double,
        maxHold: Int,
        tp: Double,
        sl: Double,
        tRatio: Double
    ): Outcome? {
        if (fwd.isEmpty()) return null
        val qty = FIXED_QTY
        val tQty = (qty * tRatio).toInt().coerceAtLeast(100)
        var holdQty = qty
        var cash = 0.0
        val cost = qty * entry
        var lastRet = 0.0
        var lastDay: Day? = null
        for (k in fwd.indices) {
            val day = fwd[k]
            lastDay = day
            val value = holdQty * day.close + cash
            lastRet = (value / cost - 1) * 100
            if (lastRet >= tp) {
                val tProfit = (cash + (holdQty - qty) * day.close) / cost * 100
                return Outcome(lastRet, day.date, day.close, "止盈", tProfit)
            }
            if (lastRet <= sl) {
                val tProfit = (cash + (holdQty - qty) * day.close) / cost * 100
                return Outcome(lastRet, day.date, day.close, "止损", tProfit)
            }
            // 做T：高抛 +0.5%
            val hiPct = (day.high / entry - 1) * 100
            if (hiPct >= T_UP_PCT && holdQty >= tQty) {
                cash += day.high * tQty
                holdQty -= tQty
            }
            // 做T：低吸 -0.5%
            val loPct = (day.low / entry - 1) * 100
            if (loPct <= T_DOWN_PCT && holdQty < qty) {
                val buyBack = min(tQty, qty - holdQty)
                cash -= day.low * buyBack
                holdQty += buyBack
            }
        }
        val last = lastDay ?: return null
        val tProfit = (cash + (holdQty - qty) * last.close) / cost * 100
        return Outcome(lastRet, last.date, last.close, "持有到期", tProfit)
    }

    /** 买入后最多 maxHold 天的前瞻行情（从买入日次日开始） */
    private fun forwardSeries(
        stockSnaps: Map<String, List<DailySnapshotEntity>>,
        allDates: List<String>,
        code: String,
        buyIdx: Int,
        maxHold: Int
    ): List<Day>? {
        val snaps = stockSnaps[code] ?: return null
        val end = min(buyIdx + maxHold, allDates.size - 1)
        if (end <= buyIdx) return null
        val list = mutableListOf<Day>()
        for (i in (buyIdx + 1)..end) {
            val s = findSnap(snaps, allDates[i]) ?: continue
            list.add(Day(s.date, s.open, s.high, s.low, s.close))
        }
        return if (list.isEmpty()) null else list
    }

    /** 有序数组二分：第一个 date > target 的下标 */
    private fun upperBoundDate(snaps: List<DailySnapshotEntity>, target: String): Int {
        var lo = 0
        var hi = snaps.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (snaps[mid].date <= target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun findSnap(snaps: List<DailySnapshotEntity>, date: String): DailySnapshotEntity? {
        var lo = 0
        var hi = snaps.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val d = snaps[mid].date
            when {
                d < date -> lo = mid + 1
                d > date -> hi = mid - 1
                else -> return snaps[mid]
            }
        }
        return null
    }

    /** 固定本金统计（每笔等额、收益累加不复利） */
    private fun statsOf(rets: List<Double>): DoubleArray {
        if (rets.isEmpty()) return doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0)
        val wins = rets.filter { it > 0 }
        val losses = rets.filter { it <= 0 }
        val avg = rets.average()
        val wr = wins.size.toDouble() / rets.size * 100
        val cum = rets.sum()
        val pf = if (losses.isNotEmpty() && losses.sum() != 0.0) wins.sum() / kotlin.math.abs(losses.sum()) else Double.POSITIVE_INFINITY
        var nav = 0.0
        var peak = 0.0
        var mdd = 0.0
        for (r in rets) {
            nav += r / 100
            peak = maxOf(peak, nav)
            mdd = minOf(mdd, nav - peak)
        }
        return doubleArrayOf(avg, wr, cum, pf, mdd * 100)
    }

    private fun buildStats(period: String, trades: List<TradeRecord>): PeriodStats {
        if (trades.isEmpty()) return emptyStats(period)
        val rets = trades.map { it.retPct }
        val s = statsOf(rets)
        val avg = s[0]; val wr = s[1]; val cum = s[2]; val pf = s[3]; val mdd = s[4]
        val byState = LinkedHashMap<MarketState, StateStats>()
        for (st in MarketState.values()) {
            val sub = trades.filter { it.marketState == st }
            if (sub.isEmpty()) continue
            val sr = statsOf(sub.map { it.retPct })
            byState[st] = StateStats(sub.size, sr[0], sr[1], sr[2], sr[3])
        }
        val pfStr = if (pf == Double.POSITIVE_INFINITY) "∞" else "%.2f".format(pf)
        val sb = StringBuilder()
        sb.append("\n========== [$period] 回溯结果（固定本金口径） ==========")
        sb.append("\n  已实现: ${trades.size}")
        sb.append("\n  平均(每笔期望): ${"%.2f".format(avg)}%   胜率: ${"%.1f".format(wr)}%   固定本金累计: ${"%.2f".format(cum)}%   盈亏因子: $pfStr   最大回撤: ${"%.2f".format(mdd)}%")
        if (rets.isNotEmpty()) sb.append("\n  最大单笔: +${"%.2f".format(rets.maxOrNull() ?: 0.0)}% / ${"%.2f".format(rets.minOrNull() ?: 0.0)}%")
        sb.append("\n  按大盘状态分组:")
        for (st in MarketState.values()) {
            val ss = byState[st] ?: continue
            val spf = if (ss.profitFactor == Double.POSITIVE_INFINITY) "∞" else "%.2f".format(ss.profitFactor)
            sb.append("\n    ${st.label}(${st.key}) 信号${ss.count} 平均${"%.2f".format(ss.avgRet)}% 胜率${"%.1f".format(ss.winRate)}% 固定本金累计${"%.2f".format(ss.fixedCum)}% 因子$spf")
        }
        return PeriodStats(period, trades.size, trades.size, avg, wr, cum, pf, mdd, byState, sb.toString())
    }

    private fun emptyStats(period: String): PeriodStats = PeriodStats(
        period, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, emptyMap(),
        "\n[$period] 无信号或数据不足"
    )

    private fun parseFitMatrix(period: String, json: String): Map<MarketState, FittedParams> {
        val result = LinkedHashMap<MarketState, FittedParams>()
        try {
            val obj = JSONObject(json)
            for (st in MarketState.values()) {
                val item = obj.optJSONObject(st.key) ?: continue
                result[st] = FittedParams(
                    period = period, marketState = st,
                    maxHoldDays = item.optInt("hold", 15),
                    takeProfitPct = item.optDouble("tp", 20.0),
                    stopLossPct = item.optDouble("sl", -10.0),
                    avgRet = item.optDouble("avg", 0.0),
                    winRate = item.optDouble("wr", 0.0),
                    sampleCount = item.optInt("n", 0)
                )
            }
        } catch (_: Exception) {}
        return result
    }
}
