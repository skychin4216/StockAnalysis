package com.chin.stockanalysis.strategy.trade

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.analysis.TrendPatternEngine
import java.util.concurrent.ConcurrentHashMap

/**
 * ## AI 精选质量闸门
 *
 * 一键建仓（非交易时间，仅选股）时四个周期各自独立跑 DagTradeExecutor，
 * 每只股票会被一个或多个周期选中（多周期共振）。为避免把各周期的兜底 TopN
 * 弱票全部塞进 AI 精选，这里在四周期都跑完后统一做一次质量过滤：
 *
 * - 一只股票被 **≥3 个周期** 同时选中（多周期共振）→ 通过
 * - 或 该股在本轮中的 **最高 DAG 评分 ≥ [GATE_SCORE]**（单周期高分）→ 通过
 * - 其余（弱周期只出现 1~2 次且分不高的兜底票）→ 从当天 AI 精选剔除
 *
 * 由 DagTradeExecutor.execute 在 saveAsAiOnly 模式下按周期登记，最后一个周期
 * 到齐后自动触发过滤；若部分周期 12 分钟仍未到齐（如单个周期手动运行），
 * 到 12 分钟也会按已有数据执行，避免闸门永远不生效。
 */
object AiSelectionQualityGate {

    private const val TAG = "AiSelectionQualityGate"

    /** 四周期 useCaseId（一键建仓并行模式的周期专属 pipeline） */
    private val EXPECTED_PERIODS = setOf(
        "ultra_short_period", "short_term_period", "mid_term_period", "long_term_period"
    )

    /** 单周期高分即入库的最低分（DAG 综合分达标） */
    private const val GATE_SCORE = 85

    /** 多周期共振即入库的最少周期数 */
    private const val RESONANCE = 3

    /** 部分周期缺失时的兜底等待时长（毫秒） */
    private const val STALE_MS = 12 * 60 * 1000L

    /** 闸门完成后的一小时内不再对同一天重复执行（防止迟到周期二次误过滤） */
    private const val DONE_SKIP_MS = 60 * 60 * 1000L

    // date -> useCaseId/source -> 该周期选中的代码集合
    private val sourceCodes = ConcurrentHashMap<String, ConcurrentHashMap<String, MutableSet<String>>>()

    // date -> code -> 最高分（跨周期取 max）
    private val bestScores = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()

    // date -> 首笔登记时间
    private val startMs = ConcurrentHashMap<String, Long>()

    // date -> 闸门已执行完成时间（完成后 1 小时内跳过）
    private val doneAtMs = ConcurrentHashMap<String, Long>()

    private val mainHandler = Handler(Looper.getMainLooper())

    private val lock = Any()

    /**
     * 登记一个周期的选股结果。
     *
     * @param context   Android Context（最终过滤需读库）
     * @param tradeDate 交易日（yyyy-MM-dd，与 ai_selected_stock.selected_date 一致）
     * @param source    周期标识（一键建仓并行模式 = 周期 useCaseId）
     * @param codeScores 该周期选中的股票 code -> scoreAtBuy（可为空，表示该周期 0 命中）
     */
    suspend fun registerPeriod(
        context: Context,
        tradeDate: String,
        source: String,
        codeScores: Map<String, Int>
    ) {
        var ready: Boolean
        synchronized(lock) {
            startMs.putIfAbsent(tradeDate, System.currentTimeMillis())
            val day = sourceCodes.getOrPut(tradeDate) { ConcurrentHashMap() }
            val codes = day.getOrPut(source) { java.util.concurrent.ConcurrentHashMap.newKeySet() }
            codes.addAll(codeScores.keys)
            val scores = bestScores.getOrPut(tradeDate) { ConcurrentHashMap() }
            for ((code, score) in codeScores) {
                val old = scores[code]
                if (old == null || score > old) scores[code] = score
            }
            ready = shouldFinalize(tradeDate)
        }
        if (ready) finalizeGate(context, tradeDate)
    }

    /** 由调用方主动触发最终过滤（若四周期登记已完成） */
    suspend fun flushIfReady(context: Context, tradeDate: String) {
        val ready = synchronized(lock) { shouldFinalize(tradeDate) }
        if (ready) finalizeGate(context, tradeDate)
    }

    private fun shouldFinalize(date: String): Boolean {
        val doneAt = doneAtMs[date]
        if (doneAt != null && System.currentTimeMillis() - doneAt < DONE_SKIP_MS) return false
        val day = sourceCodes[date] ?: return false
        val recordedSources = day.keys
        val start = startMs[date] ?: System.currentTimeMillis()
        val stale = System.currentTimeMillis() - start > STALE_MS
        val allReady = EXPECTED_PERIODS.all { it in recordedSources }
        return allReady || (stale && recordedSources.isNotEmpty())
    }

    private suspend fun finalizeGate(context: Context, tradeDate: String) {
        val day: ConcurrentHashMap<String, MutableSet<String>>
        val scores: ConcurrentHashMap<String, Int>
        synchronized(lock) {
            day = sourceCodes.remove(tradeDate) ?: return
            scores = bestScores.remove(tradeDate) ?: ConcurrentHashMap()
            startMs.remove(tradeDate)
        }
        try {
            val db = StockDatabase.getInstance(context)
            val dao = db.aiSelectedStockDao()
            val rows = dao.getByDate(tradeDate)
            if (rows.isEmpty()) {
                Log.i(TAG, "[$tradeDate] 当天无 AI 精选行，闸门跳过")
                return
            }

            // 趋势加权（RSA 状态机，对应“中长线趋势权重更高；低权重因素不硬拦截，看多则放行”）：
            // - 趋势偏多 → +1 权重；若该股同时被中线/长线周期选中 → +2
            // - 通过条件 = 共振数 + 趋势权重 >= RESONANCE（或单周期高分），弱票不再被一刀切拦截
            val midLong = (day["mid_term_period"] ?: emptySet()) +
                    (day["long_term_period"] ?: emptySet())
            // 先找出未达基础通过条件、需要趋势加权的候选
            val needTrend = mutableListOf<String>()
            for (row in rows) {
                val resonance = day.count { (_, codes) -> row.stockCode in codes }
                val best = scores[row.stockCode] ?: row.score
                if (resonance < RESONANCE && best < GATE_SCORE) needTrend.add(row.stockCode)
            }
            // 预取 K 线判断 RSA 是否偏多（在挂起上下文中以普通循环执行）
            val bullMap = HashMap<String, Boolean>()
            for (code in needTrend) {
                bullMap[code] = try {
                    val snaps = db.dailySnapshotDao().getByCode(code, 60).sortedBy { it.date }
                    TrendPatternEngine.isBullish(snaps)
                } catch (_: Exception) { false }
            }

            val passed = mutableListOf<com.chin.stockanalysis.stock.database.AiSelectedStockEntity>()
            // code -> (共振周期数, 最高分, 趋势权重)
            val dropped = mutableListOf<Triple<String, Int, Int>>()
            var trendPassed = 0
            for (row in rows) {
                val resonance = day.count { (_, codes) -> row.stockCode in codes }
                val best = scores[row.stockCode] ?: row.score
                val basePass = resonance >= RESONANCE || best >= GATE_SCORE
                val trendBonus = if (basePass || bullMap[row.stockCode] != true) 0
                else if (row.stockCode in midLong) 2 else 1
                val pass = basePass || resonance + trendBonus >= RESONANCE
                if (pass) {
                    if (!basePass) trendPassed++
                    passed.add(row)
                } else {
                    dropped.add(Triple(row.stockCode, resonance, best))
                }
            }
            // 重新写回：先删当天再插入通过的（REPLACE 语义下保持唯一索引不冲突）
            if (dropped.isNotEmpty()) {
                dao.deleteByDate(tradeDate)
                if (passed.isNotEmpty()) dao.insertAll(passed)
            }
            val summary = "AI精选质量闸门：保留 ${passed.size} 只（共振≥$RESONANCE/分≥$GATE_SCORE，趋势加权放行 $trendPassed 只），剔除 ${dropped.size} 只弱票"
            Log.i(TAG, "[$tradeDate] $summary")
            if (dropped.isNotEmpty()) {
                Log.i(TAG, "[$tradeDate] 剔除明细: " + dropped.take(20).joinToString(", ") {
                    val reso = it.second
                    "$it.first(共振$reso, 分${it.third})"
                })
            }
            mainHandler.post {
                android.widget.Toast.makeText(context, summary, android.widget.Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.w(TAG, "[$tradeDate] AI 精选质量闸门执行失败: ${e.message}", e)
        } finally {
            doneAtMs[tradeDate] = System.currentTimeMillis()
        }
    }
}
