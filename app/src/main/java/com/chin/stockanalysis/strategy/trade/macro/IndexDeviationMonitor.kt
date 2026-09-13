package com.chin.stockanalysis.strategy.trade.macro

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import com.chin.stockanalysis.strategy.trade.MarketTrendGuard

/**
 * ## 指数偏离监测器（IndexDeviationMonitor）
 *
 * 解决「科创板/创业板严重偏离上证指数」时的**均值回归回调风险**：
 *
 * 7 月科创50大涨而上证横盘，两者相对强弱比值冲到历史高位，
 * 这种「一枝独秀」的极度偏离往往意味着：一旦板块资金退潮，**高位指数会向均值快速回归**，
 * 从而拖累该板块内的高位趋势股（即便上证仍健康，板块内个股也可能补跌）。
 *
 * ### 核心指标
 * 1. **相对强弱偏离度**（RS deviation）
 *    ```
 *    RS = 板块指数 / 上证指数
 *    偏离度 = (当前RS - 20日均RS) / 20日均RS × 100%
 *    ```
 *    偏离度为正且过大 → 板块涨幅已远超大盘，存在回调收敛压力。
 * 2. **自身斜率加速**（bubble / 泡沫化）
 *    - 近10日累计涨幅 vs 近20日，检测短线上涨是否过热（连涨后易急跌）。
 *    - 结合连涨天数（借鉴 A 股连涨回撤统计）。
 * 3. **结构背离**：板块指数涨而大盘滞涨（RS 快速上升）+ 板块个股普遍放量滞涨 = 顶部特征。
 *
 * ### 输出
 * 返回各板块指数的「回调风险等级」：
 * - LOW   正常（RS 在均值附近）
 * - MEDIUM  偏离扩大，开始留意（趋势跟随可继续，但需收紧）
 * - HIGH  严重偏离（趋势跟随**暂停追高**，规避均值回归补跌）
 * - EXTREME 极端偏离（强制风控：板块内高位持仓减仓）
 *
 * 风险等级会注入 [MacroEnvironmentAnalyzer]，用于动态调整该板块内趋势跟随的仓位纪律。
 */
object IndexDeviationMonitor {

    private const val TAG = "IndexDeviationMonitor"
    private const val RS_WINDOW = 20      // 相对强弱均值窗口
    private const val FAST_LOOKBACK = 5    // 短期斜率窗口
    private const val SLOW_LOOKBACK = 10   // 中期斜率窗口
    private const val CONSEC_UP_LIMIT = 6  // 连涨警戒天数

    /** 回调风险等级 */
    enum class PullbackRisk(val label: String, val score: Int) {
        LOW("正常", 0),
        MEDIUM("偏离扩大", 1),
        HIGH("严重偏离", 2),
        EXTREME("极端偏离", 3)
    }

    /** 单个指数的偏离监测结果 */
    data class IndexDeviation(
        val indexCode: String,
        val indexName: String,
        val relativeRs: Double,          // 当前 RS（板块/上证）
        val rsMean: Double,              // RS 均值
        val rsDeviationPct: Double,      // 偏离度 %
        val shortSlopePct: Double,       // 近5日累计涨幅 %
        val midSlopePct: Double,         // 近10日累计涨幅 %
        val consecUpDays: Int,           // 连涨天数
        val risk: PullbackRisk,
        val reason: String
    )

    // ═══════════════════════════════════════════
    // 对外主入口
    // ═══════════════════════════════════════════

    /**
     * 监测两个高弹性指数（科创50 / 创业板指）相对上证的偏离与回调风险。
     *
     * @param context Context
     * @param benchIndex 基准指数（默认上证），作为「大盘」锚
     * @return indexCode → 偏离监测结果
     */
    suspend fun monitor(
        context: Context,
        benchIndex: String = MarketTrendGuard.INDEX_SH
    ): Map<String, IndexDeviation> {
        val db = StockDatabase.getInstance(context)
        val result = mutableMapOf<String, IndexDeviation>()

        // 目标高弹性指数：科创50、创业板指
        val targets = listOf(
            MarketTrendGuard.INDEX_STAR to MarketTrendGuard.indexName(MarketTrendGuard.INDEX_STAR),
            MarketTrendGuard.INDEX_GEM to MarketTrendGuard.indexName(MarketTrendGuard.INDEX_GEM)
        )

        val benchSnaps = loadSnaps(db, benchIndex) ?: return result

        for ((code, name) in targets) {
            val snaps = loadSnaps(db, code) ?: continue
            result[code] = evaluate(code, name, snaps, benchSnaps)
        }
        return result
    }

    /**
     * 查询某只股票所属指数的回调风险等级（用于该板块内趋势跟随的风控）。
     */
    suspend fun riskForStock(context: Context, stockCode: String): PullbackRisk {
        val idx = MarketTrendGuard.indexForStock(stockCode)
        if (idx == MarketTrendGuard.INDEX_SH) return PullbackRisk.LOW  // 主板本身是基准
        val map = monitor(context)
        return map[idx]?.risk ?: PullbackRisk.LOW
    }

    // ═══════════════════════════════════════════
    // 评估逻辑
    // ═══════════════════════════════════════════

    private fun evaluate(
        code: String, name: String,
        snaps: List<DailySnapshotEntity>,
        benchSnaps: List<DailySnapshotEntity>
    ): IndexDeviation {
        val closes = snaps.map { it.close }
        val benchCloses = benchSnaps.map { it.close }
        val n = minOf(closes.size, benchCloses.size)
        if (n < RS_WINDOW + 2) {
            return IndexDeviation(code, name, 0.0, 0.0, 0.0, 0.0, 0.0, 0,
                PullbackRisk.LOW, "数据不足")
        }

        // 相对强弱 RS = 板块 / 基准，取最近 RS_WINDOW+1 天
        val rsSeries = ArrayList<Double>(n)
        for (i in 0 until n) {
            val b = benchCloses[i]
            if (b > 0) rsSeries.add(closes[i] / b)
        }
        if (rsSeries.size < RS_WINDOW + 1) {
            return IndexDeviation(code, name, 0.0, 0.0, 0.0, 0.0, 0.0, 0,
                PullbackRisk.LOW, "数据不足")
        }

        val currentRs = rsSeries.last()
        val rsWindow = rsSeries.takeLast(RS_WINDOW).dropLast(1) // 前20日RS（不含今日）
        val rsMean = rsWindow.average()
        val devPct = if (rsMean > 0) (currentRs - rsMean) / rsMean * 100 else 0.0

        // 自身斜率：近5日 / 近10日累计涨幅
        val s = rsSeries.size
        val shortSlope = if (s >= FAST_LOOKBACK + 1)
            (closes.last() - closes[s - FAST_LOOKBACK - 1]) / closes[s - FAST_LOOKBACK - 1] * 100 else 0.0
        val midSlope = if (s >= SLOW_LOOKBACK + 1)
            (closes.last() - closes[s - SLOW_LOOKBACK - 1]) / closes[s - SLOW_LOOKBACK - 1] * 100 else 0.0

        // 连涨天数（最近）
        var consecUp = 0
        for (i in closes.size - 1 downTo 1) {
            if (closes[i] > closes[i - 1]) consecUp++
            else break
        }

        val risk = assess(devPct, shortSlope, midSlope, consecUp)
        val reason = buildReason(risk, devPct, shortSlope, midSlope, consecUp)

        return IndexDeviation(
            indexCode = code, indexName = name,
            relativeRs = currentRs, rsMean = rsMean, rsDeviationPct = devPct,
            shortSlopePct = shortSlope, midSlopePct = midSlope,
            consecUpDays = consecUp, risk = risk, reason = reason
        )
    }

    /**
     * 综合判定回调风险等级。
     *
     * 偏离度是核心：RS 相对 20 日均值大幅抬升（板块涨幅远超大盘）→ 回归压力大。
     * 叠加「短期急涨（斜率加速）+ 连涨天数」作为泡沫化确认。
     */
    private fun assess(
        devPct: Double, shortSlope: Double, midSlope: Double, consecUp: Int
    ): PullbackRisk {
        // 偏离度阈值：科创/创业板弹性大，正常偏离在 ±8% 内
        val dev = devPct
        // 斜率加速：短期涨幅显著大于中期（近5日 > 近10日一半以上 视为加速）
        val accelerating = shortSlope > 0 && shortSlope > midSlope * 0.6 && shortSlope > 3.0
        val overheated = consecUp >= CONSEC_UP_LIMIT && shortSlope > 5.0

        return when {
            // 极端：RS 偏离超 +20% 且加速/连涨确认
            dev > 20.0 && (accelerating || overheated) -> PullbackRisk.EXTREME
            // 严重：RS 偏离超 +15%，或 +12% 且有泡沫化确认
            dev > 15.0 || (dev > 12.0 && (accelerating || overheated)) -> PullbackRisk.HIGH
            // 中等：RS 偏离超 +6%
            dev > 6.0 -> PullbackRisk.MEDIUM
            else -> PullbackRisk.LOW
        }
    }

    private fun buildReason(
        risk: PullbackRisk, dev: Double, short: Double, mid: Double, consec: Int
    ): String {
        val sb = StringBuilder(risk.label)
        sb.append("（RS偏离${"%.1f".format(dev)}%，5日涨${"%.1f".format(short)}%，10日涨${"%.1f".format(mid)}%")
        if (consec > 0) sb.append("，连涨${consec}天")
        sb.append("）")
        when (risk) {
            PullbackRisk.EXTREME -> sb.append("：板块涨幅远超大盘且加速，均值回归补跌风险极高，高位趋势股应减仓/规避追高")
            PullbackRisk.HIGH -> sb.append("：板块显著偏离大盘，警惕快速回调收敛，趋势跟随暂停追高")
            PullbackRisk.MEDIUM -> sb.append("：偏离开始扩大，留意板块资金退潮")
            PullbackRisk.LOW -> sb.append("：相对强弱在均值附近，正常")
        }
        return sb.toString()
    }

    private suspend fun loadSnaps(db: StockDatabase, code: String): List<DailySnapshotEntity>? {
        return try {
            db.dailySnapshotDao().getByCode(code, 40).sortedBy { it.date }
        } catch (e: Exception) {
            Log.w(TAG, "读取指数 $code 失败: ${e.message}")
            null
        }
    }
}
