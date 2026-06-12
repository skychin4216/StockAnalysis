package com.chin.stockanalysis.strategy.trade

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import com.chin.stockanalysis.strategy.Strategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ## 多维度智能卖出引擎 v2.0
 *
 * 融合全球量化交易领域10大主流卖出策略，分为四个层级：
 *
 * ### 层级1: 强制风控层（优先级最高）
 * 1. **硬止损 (Hard Stop)**: 亏损 > 8% → 无条件卖出
 * 2. **最大回撤止损 (Max Drawdown Stop)**: 从持仓最高点回撤 > 12% → 卖出
 * 3. **时间强制平仓 (Time Force Close)**: 持仓 > 15天 → 强制卖出
 *
 * ### 层级2: 动态止盈层
 * 4. **阶梯止盈 (Tiered Take Profit)**: +10%卖1/3, +15%卖1/3, +20%卖剩余
 * 5. **Chandelier Exit（吊灯止损）**: 最高价 - N×ATR → 动态止损线
 * 6. **移动止盈 (Trailing Profit)**: 盈利>8%后，回撤超盈利50% → 卖出
 *
 * ### 层级3: 技术指标层
 * 7. **MA死叉 (Death Cross)**: 5日线下穿20日线 → 卖出信号
 * 8. **放量滞涨 (Volume Climax)**: 成交量>2倍均量且涨幅<1% → 主力出货
 * 9. **RSI超买反转 (RSI Overbought)**: RSI>75且连续2日下降 → 卖出
 *
 * ### 层级4: 市场环境层
 * 10. **板块/大盘走弱 (Sector Weakness)**: 所属板块跌幅>2% → 减仓卖出
 */
class AutoSellEngine(private val context: Context) {

    companion object {
        private const val TAG = "AutoSellEngine"
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        const val HARD_STOP_LOSS_PCT = -8.0
        const val MAX_DRAWDOWN_PCT = -12.0
        const val TIME_FORCE_CLOSE_DAYS = 10
        const val CHANDELIER_ATR_MULT = 3.0
        const val TRAIL_PROFIT_ACTIVATE_PCT = 8.0
        const val TRAIL_PROFIT_RETRACE_RATIO = 0.5
        const val VOLUME_SURGE_MULT = 2.0
        const val VOLUME_CLIMAX_PRICE_PCT = 1.0
        const val RSI_OVERBOUGHT = 75
        const val RSI_DECLINE_DAYS = 2
        const val SECTOR_WEAKNESS_PCT = -2.0
        const val MA_SHORT = 5
        const val MA_LONG = 20

        data class TakeProfitTier(val profitPct: Double, val sellRatio: Double)
        val TP_TIERS = listOf(
            TakeProfitTier(10.0, 0.33),
            TakeProfitTier(15.0, 0.33),
            TakeProfitTier(20.0, 1.0)
        )
    }

    data class AutoSellConfig(
        val tradeDate: String = LocalDate.now().format(DATE_FMT),
        val hardStopLossPct: Double = HARD_STOP_LOSS_PCT,
        val maxDrawdownPct: Double = MAX_DRAWDOWN_PCT,
        val timeForceCloseDays: Int = TIME_FORCE_CLOSE_DAYS,
        val chandelierAtrMult: Double = CHANDELIER_ATR_MULT,
        val trailProfitActivatePct: Double = TRAIL_PROFIT_ACTIVATE_PCT,
        val trailProfitRetraceRatio: Double = TRAIL_PROFIT_RETRACE_RATIO,
        val enableTieredTP: Boolean = true,
        val enableChandelierExit: Boolean = true,
        val enableMACross: Boolean = true,
        val enableVolumeClimax: Boolean = true,
        val enableRSIOverbought: Boolean = true,
        val enableSectorWeakness: Boolean = true,
        val volumeSurgeMult: Double = VOLUME_SURGE_MULT,
        val volumeClimaxPricePct: Double = VOLUME_CLIMAX_PRICE_PCT,
        val rsiOverbought: Int = RSI_OVERBOUGHT
    )

    data class SellDecision(
        val order: StrategyTradeOrderEntity,
        val reason: String,
        val currentPrice: Double,
        val profitPct: Double,
        val shouldSell: Boolean,
        val strategy: String,
        val sellRatio: Double = 1.0,
        val urgency: Int = 0,
        val technicalDetails: Map<String, String> = emptyMap()
    )

    data class SellPerformanceStats(
        val strategyName: String,
        val totalSells: Int,
        val avgProfitPct: Double,
        val winRate: Double,
        val maxProfitPct: Double,
        val maxLossPct: Double,
        val avgDaysHeld: Double
    )

    private data class PositionSnapshot(
        val order: StrategyTradeOrderEntity,
        val currentPrice: Double,
        val profitPct: Double,
        val daysHeld: Int,
        val priceHistory: List<Double>,
        val volumeHistory: List<Long>,
        val highestPrice: Double,
        val atr: Double,
        val ma5: Double,
        val ma20: Double,
        val rsi: Double,
        val avgVolume: Double,
        val sectorChangePct: Double = 0.0
    )

    private val db = StockDatabase.getInstance(context)

    // ═══════════════════════════════════════════════
    // 主入口
    // ═══════════════════════════════════════════════

    suspend fun evaluateAll(
        strategies: List<Strategy>,
        config: AutoSellConfig = AutoSellConfig()
    ): List<SellDecision> = withContext(Dispatchers.IO) {
        val decisions = mutableListOf<SellDecision>()
        try {
            val holdingOrders = db.strategyTradeOrderDao().getRecent(500)
                .filter { it.status == "BUYING" || it.status == "PENDING" || it.status == "HELD" }
            if (holdingOrders.isEmpty()) return@withContext decisions

            val todayData = getTradingDayData(config.tradeDate)
            val todayPriceMap = todayData.associate { it.code to it.close }
            val todayVolumeMap = todayData.associate { it.code to it.volume }
            val recentDates = db.dailySnapshotDao().getAvailableDates(30).sorted()
            val priceHistory = buildPriceHistory(recentDates)
            val volumeHistory = buildVolumeHistory(recentDates)
            val sectorChanges = getSectorChangePct(config.tradeDate)

            for (order in holdingOrders) {
                val currentPrice = todayPriceMap[order.stockCode] ?: continue
                val profitPct = (currentPrice - order.buyPrice) / order.buyPrice * 100
                val daysHeld = calculateDaysHeld(order.tradeDate, config.tradeDate)
                val prices = priceHistory[order.stockCode] ?: listOf(currentPrice)
                val volumes = volumeHistory[order.stockCode] ?: listOf(todayVolumeMap[order.stockCode] ?: 0L)
                val highestPrice = prices.maxOrNull() ?: currentPrice
                val atr = calculateATR(prices, 14)
                val ma5 = calculateSMA(prices, 5)
                val ma20 = calculateSMA(prices, 20)
                val rsi = calculateRSI(prices, 14)
                val avgVolume = if (volumes.isNotEmpty()) volumes.map { it.toDouble() }.average() else 0.0

                val snapshot = PositionSnapshot(
                    order, currentPrice, profitPct, daysHeld, prices, volumes,
                    highestPrice, atr, ma5, ma20, rsi, avgVolume,
                    sectorChanges[order.stockCode] ?: 0.0
                )
                decisions.add(evaluatePosition(snapshot, config, strategies))
            }
            Log.i(TAG, "卖出评估: ${decisions.size}持仓, ${decisions.count{it.shouldSell}}触发卖出")
        } catch (e: Exception) { Log.w(TAG, "卖出评估异常: ${e.message}") }
        decisions
    }

    private fun evaluatePosition(
        snap: PositionSnapshot,
        config: AutoSellConfig,
        strategies: List<Strategy>
    ): SellDecision {
        // ① 硬止损
        if (snap.profitPct <= config.hardStopLossPct) {
            return SellDecision(snap.order,
                "🛑 硬止损: 亏损${"%.2f".format(-snap.profitPct)}% > ${"%.1f".format(-config.hardStopLossPct)}%",
                snap.currentPrice, snap.profitPct, true, "HardStop", 1.0, 10,
                mapOf("trigger" to "硬止损", "loss%" to "%.2f".format(-snap.profitPct)))
        }

        // ② 最大回撤止损
        val drawdownFromPeak = (snap.currentPrice - snap.highestPrice) / snap.highestPrice * 100
        if (drawdownFromPeak <= config.maxDrawdownPct) {
            return SellDecision(snap.order,
                "📉 最大回撤止损: 从高点¥${"%.2f".format(snap.highestPrice)}回撤${"%.2f".format(-drawdownFromPeak)}%",
                snap.currentPrice, snap.profitPct, true, "MaxDrawdown", 1.0, 9,
                mapOf("trigger" to "最大回撤", "highestPrice" to "%.2f".format(snap.highestPrice),
                    "drawdown%" to "%.2f".format(-drawdownFromPeak)))
        }

        // ③ 時間+無進展止損 (Time + No Progress Stop)
        // 持仓超时且近3日无正向动量（涨幅<1%）→ 死钱换股
        if (snap.daysHeld >= config.timeForceCloseDays && snap.priceHistory.size >= 4) {
            val recent3 = snap.priceHistory.takeLast(4)
            val momentum3Day = (recent3.last() - recent3.first()) / recent3.first() * 100
            if (momentum3Day < 1.0) {
                return SellDecision(snap.order,
                    "⏰ 时间无进展: 持仓${snap.daysHeld}天 近3日动量${"%.2f".format(momentum3Day)}% < 1%",
                    snap.currentPrice, snap.profitPct, true, "TimeNoProgress", 1.0, 7,
                    mapOf("trigger" to "时间无进展", "daysHeld" to "${snap.daysHeld}天",
                        "momentum3d" to "%.2f%%".format(momentum3Day)))
            }
        }

        // ④ 阶梯止盈
        if (config.enableTieredTP) {
            for (tier in TP_TIERS.reversed()) {
                if (snap.profitPct >= tier.profitPct) {
                    return SellDecision(snap.order,
                        "🎯 阶梯止盈: +${"%.1f".format(snap.profitPct)}% 触发第${TP_TIERS.indexOf(tier)+1}档",
                        snap.currentPrice, snap.profitPct, true, "TieredTP", tier.sellRatio, 5,
                        mapOf("trigger" to "阶梯止盈",
                            "tier" to "${TP_TIERS.indexOf(tier)+1}/${TP_TIERS.size}",
                            "sellRatio" to "${(tier.sellRatio*100).toInt()}%"))
                }
            }
        }

        // ⑤ Chandelier Exit
        if (config.enableChandelierExit && snap.atr > 0) {
            val chandelierStop = snap.highestPrice - config.chandelierAtrMult * snap.atr
            if (snap.currentPrice <= chandelierStop) {
                return SellDecision(snap.order,
                    "🕯️ 吊灯止损: 跌破最高价-${"%.1f".format(config.chandelierAtrMult)}×ATR",
                    snap.currentPrice, snap.profitPct, true, "ChandelierExit", 1.0, 8,
                    mapOf("trigger" to "吊灯止损", "stopLine" to "%.2f".format(chandelierStop)))
            }
        }

        // ⑥ 移动止盈
        if (snap.profitPct >= config.trailProfitActivatePct) {
            val retraceFromPeak = (snap.highestPrice - snap.currentPrice) / snap.highestPrice * 100
            if (retraceFromPeak / snap.profitPct >= config.trailProfitRetraceRatio) {
                return SellDecision(snap.order,
                    "📊 移动止盈: 回撤${"%.2f".format(retraceFromPeak)}% > ${"%.0f".format(config.trailProfitRetraceRatio*100)}%",
                    snap.currentPrice, snap.profitPct, true, "TrailProfit", 1.0, 6,
                    mapOf("trigger" to "移动止盈", "retrace%" to "%.2f".format(retraceFromPeak)))
            }
        }

        // ⑦ MA死叉
        if (config.enableMACross && snap.ma5 > 0 && snap.ma20 > 0 && snap.priceHistory.size >= MA_LONG + 1) {
            val prevPrices = snap.priceHistory.dropLast(1)
            val prevMA5 = calculateSMA(prevPrices, MA_SHORT)
            val prevMA20 = calculateSMA(prevPrices, MA_LONG)
            if (prevMA5 > 0 && prevMA20 > 0 && prevMA5 > prevMA20 && snap.ma5 < snap.ma20) {
                return SellDecision(snap.order,
                    "💀 MA死叉: ${MA_SHORT}日线下穿${MA_LONG}日线",
                    snap.currentPrice, snap.profitPct, true, "MADeathCross", 1.0, 7,
                    mapOf("trigger" to "MA死叉",
                        "ma${MA_SHORT}" to "%.2f".format(snap.ma5),
                        "ma${MA_LONG}" to "%.2f".format(snap.ma20)))
            }
        }

        // ⑧ 放量滞涨
        if (config.enableVolumeClimax && snap.avgVolume > 0 && snap.volumeHistory.isNotEmpty()) {
            val todayVol = snap.volumeHistory.lastOrNull()?.toDouble() ?: 0.0
            val volumeRatio = todayVol / snap.avgVolume
            if (volumeRatio >= config.volumeSurgeMult && snap.priceHistory.size >= 2) {
                val todayChange = (snap.currentPrice - snap.priceHistory[snap.priceHistory.size-2]) /
                        snap.priceHistory[snap.priceHistory.size-2] * 100
                if (todayChange < config.volumeClimaxPricePct && snap.profitPct > -1.0) {
                    return SellDecision(snap.order,
                        "📦 放量滞涨: 成交量${"%.1f".format(volumeRatio)}倍均量 涨幅${"%.2f".format(todayChange)}%",
                        snap.currentPrice, snap.profitPct, true, "VolumeClimax", 1.0, 6,
                        mapOf("trigger" to "放量滞涨", "volumeRatio" to "%.1f".format(volumeRatio)))
                }
            }
        }

        // ⑨ RSI超买反转
        if (config.enableRSIOverbought && snap.rsi >= config.rsiOverbought && snap.priceHistory.size >= RSI_DECLINE_DAYS + 2) {
            val recentPrices = snap.priceHistory.takeLast(RSI_DECLINE_DAYS + 1)
            var declining = true
            for (i in 1 until recentPrices.size) {
                val prevRsi = calculateRSI(snap.priceHistory.take(snap.priceHistory.size - recentPrices.size + i), 14)
                val currRsi = calculateRSI(snap.priceHistory.take(snap.priceHistory.size - recentPrices.size + i + 1), 14)
                if (currRsi >= prevRsi) { declining = false; break }
            }
            if (declining) {
                return SellDecision(snap.order,
                    "📈 RSI超买反转: RSI=${"%.1f".format(snap.rsi)} 连续${RSI_DECLINE_DAYS}日下降",
                    snap.currentPrice, snap.profitPct, true, "RSIOverbought", 1.0, 5,
                    mapOf("trigger" to "RSI超买", "rsi" to "%.1f".format(snap.rsi)))
            }
        }

        // ⑩ 板块弱势
        if (config.enableSectorWeakness && snap.sectorChangePct <= SECTOR_WEAKNESS_PCT) {
            return SellDecision(snap.order,
                "🌪️ 板块弱势: 所属板块跌幅${"%.2f".format(-snap.sectorChangePct)}%",
                snap.currentPrice, snap.profitPct, true, "SectorWeakness", 1.0, 5,
                mapOf("trigger" to "板块弱势", "sectorChange%" to "%.2f".format(snap.sectorChangePct)))
        }

        // ── 继续持有 ──
        val holdTech = mutableMapOf<String, String>(
            "ma5" to (if(snap.ma5>0) "%.2f".format(snap.ma5) else "N/A"),
            "ma20" to (if(snap.ma20>0) "%.2f".format(snap.ma20) else "N/A"),
            "rsi" to (if(snap.rsi>0) "%.1f".format(snap.rsi) else "N/A"),
            "atr" to "%.2f".format(snap.atr)
        )
        val holdReason = buildString {
            append("✅ 持仓中: ${"%.2f".format(snap.profitPct)}%")
            if (snap.ma5 > 0 && snap.ma20 > 0)
                append(" | MA:${if(snap.ma5 > snap.ma20) "多头" else "空头"}")
            if (snap.rsi > 0) append(" | RSI:${"%.1f".format(snap.rsi)}")
        }
        return SellDecision(snap.order, holdReason, snap.currentPrice, snap.profitPct, false, "Hold",
            technicalDetails = holdTech)
    }

    // ═══════════════════════════════════════════════
    // 执行卖出
    // ═══════════════════════════════════════════════

    suspend fun executeSells(decisions: List<SellDecision>, today: String = LocalDate.now().format(DATE_FMT)): Int = withContext(Dispatchers.IO) {
        var executedCount = 0
        for (dec in decisions.filter { it.shouldSell }) {
            try {
                if (dec.sellRatio >= 1.0) {
                    db.strategyTradeOrderDao().updateSellInfo(
                        id = dec.order.id, status = "SOLD", sellPrice = dec.currentPrice,
                        sellTime = "$today 15:00", profitPct = dec.profitPct)
                } else {
                    val soldQuantity = (dec.order.quantity * dec.sellRatio).toInt().coerceAtLeast(1)
                    db.strategyTradeOrderDao().updateQuantity(dec.order.id, dec.order.quantity - soldQuantity)
                    db.strategyTradeOrderDao().insert(StrategyTradeOrderEntity(
                        strategyId = dec.order.strategyId, stockCode = dec.order.stockCode,
                        stockName = dec.order.stockName, tradeDate = dec.order.tradeDate,
                        buyPrice = dec.order.buyPrice, buyTime = dec.order.buyTime,
                        quantity = soldQuantity, orderType = "${dec.order.orderType}_部分卖出",
                        status = "SOLD", reason = dec.reason, scoreAtBuy = dec.order.scoreAtBuy,
                        sellPrice = dec.currentPrice, sellTime = "$today 15:00", profitPct = dec.profitPct))
                }
                Log.i(TAG, "💰 卖出: ${dec.order.stockName} [${dec.strategy}] ${dec.reason}")
                executedCount++
            } catch (e: Exception) { Log.w(TAG, "卖出失败: ${dec.order.stockName} ${e.message}") }
        }
        executedCount
    }

    // ═══════════════════════════════════════════════
    // 技术指标
    // ═══════════════════════════════════════════════

    fun calculateSMA(prices: List<Double>, period: Int): Double {
        if (prices.size < period) return 0.0
        return prices.takeLast(period).average()
    }

    fun calculateATR(prices: List<Double>, period: Int = 14): Double {
        if (prices.size < period + 1) {
            if (prices.size < 2) return 0.02 * (prices.lastOrNull() ?: 10.0)
            return prices.zipWithNext { a, b -> abs(b - a) }.average()
        }
        return prices.zipWithNext { a, b -> abs(b - a) }.takeLast(period).average()
    }

    fun calculateRSI(prices: List<Double>, period: Int = 14): Double {
        if (prices.size < period + 1) return 50.0
        val changes = prices.zipWithNext { a, b -> b - a }.takeLast(period)
        val gains = changes.filter { it > 0 }.sum()
        val losses = changes.filter { it < 0 }.sum().let { abs(it) }
        if (losses == 0.0) return 100.0
        return 100.0 - 100.0 / (1.0 + gains / losses)
    }

    // ═══════════════════════════════════════════════
    // 辅助
    // ═══════════════════════════════════════════════

    private fun calculateDaysHeld(buyDate: String, today: String): Int = try {
        java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(buyDate, DATE_FMT), LocalDate.parse(today, DATE_FMT)).toInt()
    } catch (_: Exception) { 0 }

    private suspend fun getTradingDayData(date: String): List<DailySnapshotEntity> =
        try { db.dailySnapshotDao().getByDate(date) } catch (_: Exception) { emptyList() }

    private suspend fun buildPriceHistory(dates: List<String>): Map<String, List<Double>> {
        val map = mutableMapOf<String, MutableList<Double>>()
        for (date in dates.takeLast(30)) {
            try { db.dailySnapshotDao().getByDate(date).forEach { map.getOrPut(it.code){ mutableListOf() }.add(it.close) } }
            catch (_: Exception) {}
        }
        return map
    }

    private suspend fun buildVolumeHistory(dates: List<String>): Map<String, List<Long>> {
        val map = mutableMapOf<String, MutableList<Long>>()
        for (date in dates.takeLast(30)) {
            try { db.dailySnapshotDao().getByDate(date).forEach { map.getOrPut(it.code){ mutableListOf() }.add(it.volume) } }
            catch (_: Exception) {}
        }
        return map
    }

    private suspend fun getSectorChangePct(date: String): Map<String, Double> {
        val map = mutableMapOf<String, Double>()
        try { db.dailySnapshotDao().getByDate(date).forEach { map[it.code] = it.changePct } }
        catch (_: Exception) {}
        return map
    }

    // ═══════════════════════════════════════════════
    // 绩效统计
    // ═══════════════════════════════════════════════

    suspend fun getSellPerformance(days: Int = 90): List<SellPerformanceStats> = withContext(Dispatchers.IO) {
        val stats = mutableListOf<SellPerformanceStats>()
        try {
            val orders = db.strategyTradeOrderDao().getRecent(1000).filter { it.status == "SOLD" }
            if (orders.isEmpty()) return@withContext stats
            orders.groupBy { extractStrategyFromReason(it.reason) }.forEach { (name, list) ->
                val profits = list.map { it.profitPct }
                val winRate = profits.count { it > 0 }.toDouble() / profits.size
                val avgDays = list.mapNotNull { o ->
                    try { java.time.temporal.ChronoUnit.DAYS.between(
                        LocalDate.parse(o.tradeDate, DATE_FMT), LocalDate.parse(o.sellTime.take(10), DATE_FMT)).toInt().toDouble() }
                    catch (_: Exception) { null }
                }.average()
                stats.add(SellPerformanceStats(name, list.size, profits.average(), winRate,
                    profits.maxOrNull() ?: 0.0, profits.minOrNull() ?: 0.0, avgDays))
            }
            stats.sortByDescending { it.winRate * it.avgProfitPct }
        } catch (e: Exception) { Log.w(TAG, "绩效统计失败: ${e.message}") }
        stats
    }

    private fun extractStrategyFromReason(reason: String): String = when {
        reason.contains("硬止损") -> "HardStop"
        reason.contains("最大回撤") -> "MaxDrawdown"
        reason.contains("时间强制") -> "TimeForceClose"
        reason.contains("时间无进展") -> "TimeNoProgress"
        reason.contains("阶梯止盈") -> "TieredTP"
        reason.contains("吊灯止损") -> "ChandelierExit"
        reason.contains("移动止盈") -> "TrailProfit"
        reason.contains("MA死叉") -> "MADeathCross"
        reason.contains("放量滞涨") -> "VolumeClimax"
        reason.contains("RSI超买") -> "RSIOverbought"
        reason.contains("板块弱势") -> "SectorWeakness"
        reason.contains("ATR止損") || reason.contains("ATR止损") -> "ATRStop"
        reason.contains("動量衰竭") || reason.contains("动量衰竭") -> "MomentumDecay"
        reason.contains("目標止盈") || reason.contains("目标止盈") -> "TakeProfit"
        else -> "Other"
    }
}