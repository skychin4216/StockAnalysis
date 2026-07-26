package com.chin.stockanalysis.strategy.strategies

import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.strategy.*
import com.chin.stockanalysis.strategy.data.StockScreener
import com.chin.stockanalysis.strategy.models.WeightFactor
import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.models.StrategySignal
import com.chin.stockanalysis.strategy.models.SignalAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Institutional Accumulation Strategy
 *
 * Simulates institutional accumulation behavior by identifying stocks
 * with high ROE, low debt, positive operating cash flow, moderate turnover,
 * and reasonable price change -- characteristics typical of institutional
 * accumulation phases.
 */
class InstitutionalAccumulationStrategy(
    private val screener: StockScreener
) : Strategy {

    override val id = "institutional_accumulation"
    override var name = "機構增持"
    override var description =
        "篩選高ROE、低負債、正現金流、低換手率的績優股，模擬機構增持吸籌行為"
    override val category = StrategyCategory.VALUE
    override val holdingPeriods = listOf(HoldingPeriod.LONG)
    override val source = StrategySource.BUILTIN
    override val signalExpiryHours = 720   // 30個交易日

    override val config = StrategyConfig.custom(
        params = mapOf(
            "roe_min" to 15.0,
            "debt_max" to 50.0,
            "turnover_min" to 1.0,
            "turnover_max" to 6.0,
            "change_max" to 5.0,
            "market_cap_min" to 200e8,
            "validity_days" to 30.0
        ),
        maxResults = 20
    )

    override var weightFactors: List<WeightFactor> = listOf(
        WeightFactor("roe", "ROE盈利能力", 25, "ROE TTM反映機構青睞程度"),
        WeightFactor("debt", "負債率", 20, "低負債率代表財務穩健"),
        WeightFactor("cashflow", "經營現金流", 15, "真實盈利能力驗證"),
        WeightFactor("mcap", "市值規模", 20, "大市值機構配置偏好"),
        WeightFactor("turnover", "換手率", 10, "低換手率暗示吸籌階段"),
        WeightFactor("valuation", "估值", 10, "PE/PB估值合理性")
    )

    // ── Screening entry ──

    override suspend fun screen(): Result<ScreeningResult> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        return@withContext try {
            val pool = if (config.stockPool.isEmpty()) screener.scanFullMarket()
            else screener.scanSpecific(config.stockPool).values.toList()
            screenWithPool(pool, startTime)
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun screenWithData(preloadedStocks: List<StockRealtime>): Result<ScreeningResult> {
        val startTime = System.currentTimeMillis()
        return try {
            val pool = if (config.stockPool.isNotEmpty())
                preloadedStocks.filter { it.code in config.stockPool }
            else preloadedStocks
            screenWithPool(pool, startTime)
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun isAvailable(): Boolean = true

    // ── Core pipeline ──

    private suspend fun screenWithPool(
        pool: List<StockRealtime>,
        startTime: Long
    ): Result<ScreeningResult> {
        if (pool.isEmpty()) return success(emptyList(), 0, startTime)

        // 大盤環境預檢
        val marketDir = try {
            screener.detectMarketDirection()
        } catch (_: Exception) { "OSCILLATION" }
        val isBearish = marketDir == "BEARISH"
        val strengthThreshold = if (isBearish) 35 else 20
        Log.i(
            id, "大盤環境: $marketDir → 機構增持門檻 ${
                if (isBearish) "20->35" else "標準門檻20"
            }"
        )

        // Step 1: 基礎過濾
        val marketCapMin = config.getDouble("market_cap_min", 200e8)
        val step1 = pool.filter { stock ->
            stock.marketCap >= marketCapMin &&
            stock.pe > 0 &&
            stock.pb > 0 &&
            !stock.name.contains("ST", true) &&
            !stock.name.contains("退", true)
        }
        Log.i(id, "pool=${pool.size} -> 基礎過濾(mcap>=${marketCapMin/1e8}億, PE>0, PB>0, 非ST)=${step1.size}")

        // Step 2: 機構增持信號過濾
        val roeMin = config.getDouble("roe_min", 15.0)
        val debtMax = config.getDouble("debt_max", 50.0)
        val turnoverMin = config.getDouble("turnover_min", 1.0)
        val turnoverMax = config.getDouble("turnover_max", 6.0)
        val changeMax = config.getDouble("change_max", 5.0)
        val step2 = step1.filter { stock ->
            stock.roeTTM >= roeMin &&
            (stock.debtToAsset <= 0 || stock.debtToAsset < debtMax) &&
            stock.operatingCashFlow > 0 &&
            stock.turnoverRate in turnoverMin..turnoverMax &&
            stock.changePercent < changeMax
        }
        Log.i(id, "機構增持信號過濾(ROE>=$roeMin, 負債<$debtMax, OCF>0, 換手$turnoverMin~$turnoverMax%, 漲跌<$changeMax%)=${step2.size}")

        // Step 3: 打分
        val scored = step2.map { calculateSignal(it) }
            .filter { it.strength >= strengthThreshold }
        Log.i(id, "打分後 strength>=$strengthThreshold: ${scored.size}")

        // Step 4: 排序截取
        val signals = scored
            .sortedByDescending { it.strength }
            .take(config.maxResults)
        return success(signals, pool.size, startTime)
    }

    // ── Scoring ──

    private fun calculateSignal(stock: StockRealtime): StrategySignal {
        val w = weightFactors.associateBy { it.key }

        // ROE評分 (满分25)
        val roeScore = when {
            stock.roeTTM >= 25.0 -> 25
            stock.roeTTM >= 20.0 -> 20
            stock.roeTTM >= 15.0 -> 15
            stock.roeTTM > 0 -> 8
            else -> 0
        } * (w["roe"]?.weight ?: 25) / 100

        // 負債率評分 (满分20)
        val debtScore = when {
            stock.debtToAsset <= 0 -> 20
            stock.debtToAsset < 30.0 -> 20
            stock.debtToAsset < 40.0 -> 16
            stock.debtToAsset < 50.0 -> 12
            stock.debtToAsset < 60.0 -> 6
            else -> 0
        } * (w["debt"]?.weight ?: 20) / 100

        // 經營現金流評分 (满分15)
        val cashflowScore = when {
            stock.operatingCashFlow > 50e8 -> 15
            stock.operatingCashFlow > 10e8 -> 12
            stock.operatingCashFlow > 0 -> 8
            else -> 0
        } * (w["cashflow"]?.weight ?: 15) / 100

        // 市值規模評分 (满分20)
        val mcapScore = when {
            stock.marketCap >= 1000e8 -> 20
            stock.marketCap >= 500e8 -> 16
            stock.marketCap >= 300e8 -> 12
            stock.marketCap >= 200e8 -> 8
            else -> 0
        } * (w["mcap"]?.weight ?: 20) / 100

        // 換手率評分 (满分10)
        val turnoverScore = when {
            stock.turnoverRate in 1.0..2.0 -> 10
            stock.turnoverRate in 2.0..4.0 -> 8
            stock.turnoverRate in 4.0..6.0 -> 5
            else -> 0
        } * (w["turnover"]?.weight ?: 10) / 100

        // 估值評分 (满分10)
        val peScore = when {
            stock.pe in 8.0..15.0 -> 10
            stock.pe in 15.0..25.0 -> 7
            stock.pe in 25.0..40.0 -> 4
            stock.pe > 0 -> 2
            else -> 0
        } * (w["valuation"]?.weight ?: 10) / 100

        val rawStrength = roeScore + debtScore + cashflowScore + mcapScore + turnoverScore + peScore
        val strength = rawStrength.coerceIn(0, 100)

        val sb = StringBuilder("機構增持候選")
        if (stock.marketCap > 0) sb.append(" | MCap").append(String.format("%.0f", stock.marketCap / 1e8)).append("B")
        if (stock.roeTTM > 0) sb.append(" | ROE").append(String.format("%.1f", stock.roeTTM)).append("%")
        if (stock.debtToAsset > 0) sb.append(" | D/A").append(String.format("%.1f", stock.debtToAsset)).append("%")
        if (stock.operatingCashFlow > 0) sb.append(" | OCF").append(String.format("%.1f", stock.operatingCashFlow / 1e8)).append("B")
        sb.append(" | TOR").append(String.format("%.1f", stock.turnoverRate)).append("%")
        if (stock.pe > 0) sb.append(" | PE").append(String.format("%.1f", stock.pe))

        val validityDays = config.getInt("validity_days", 30)
        sb.append(" | 有效期${validityDays}天")

        val action = when {
            strength >= 70 -> SignalAction.BUY
            strength >= 45 -> SignalAction.WATCH
            else -> SignalAction.HOLD
        }

        return StrategySignal(
            stockCode = stock.code,
            stockName = stock.name,
            strategyId = id,
            category = category,
            strength = strength,
            action = action,
            reason = "機構增持候選: ROE=${String.format("%.1f", stock.roeTTM)}%, " +
                    "負債率=${String.format("%.1f", stock.debtToAsset)}%, " +
                    "換手${String.format("%.1f", stock.turnoverRate)}%",
            currentPrice = stock.price,
            changePercent = stock.changePercent
        )
    }

    // ── Helper ──

    private fun success(
        signals: List<StrategySignal>,
        total: Int,
        startTime: Long
    ) = Result.success(ScreeningResult(
        id, name, category, signals, total,
        System.currentTimeMillis() - startTime
    ))
}
