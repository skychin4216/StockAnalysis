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
 * Moat Leader Strategy
 *
 * Identifies industry leaders with economic moats in high-barrier sectors.
 * Combines moat sector keyword matching with fundamental metrics (market cap,
 * gross margin, ROE) to select dominant companies with strong pricing power
 * and capital efficiency.
 */
class MoatLeaderStrategy(
    private val screener: StockScreener
) : Strategy {

    override val id = "moat_leader"
    override var name = "行业龙头护城河"
    override var description =
        "筛选高壁垒行业龙头，结合护城河板块匹配、市值规模、毛利率和ROE选出定价权强的行业领先者"
    override val category = StrategyCategory.VALUE
    override val holdingPeriods = listOf(HoldingPeriod.LONG)
    override val source = StrategySource.BUILTIN
    override val signalExpiryHours = 720   // 30个交易日

    override val config = StrategyConfig.custom(
        params = mapOf(
            "market_cap_min" to 300e8,
            "leader_cap_min" to 500e8,
            "roe_min" to 18.0,
            "gross_margin_min" to 25.0
        ),
        maxResults = 15
    )

    override var weightFactors: List<WeightFactor> = listOf(
        WeightFactor("moat", "护城河", 30, "高壁垒行业板块匹配度"),
        WeightFactor("leader", "市值龙头", 25, "行业内市值排名靠前"),
        WeightFactor("margin", "毛利率", 20, "毛利率反映定价权强弱"),
        WeightFactor("roe", "ROE", 15, "资本运用效率"),
        WeightFactor("cashflow", "现金流", 10, "经营现金流验证盈利质量")
    )

    /** 高壁垒行业关键词集合 */
    private val moatKeywords = setOf(
        "光刻机", "芯片", "半导体", "芯片制造", "光模块", "算力",
        "量子计算", "航空发动机", "操作系统", "EDA",
        "GPU", "CPU", "FPGA", "ASIC", "存储芯片",
        "先进封装", "半导体设备", "晶圆", "刻蚀",
        "CPO", "硅光", "激光雷达", "卫星互联网",
        "创新药", "CXO", "疫苗", "基因治疗",
        "航空发动机", "燃气轮机", "高端装备",
        "工业软件", "基础软件", "数据库", "中间件"
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

        // 大盘环境预检
        val marketDir = try {
            screener.detectMarketDirection()
        } catch (_: Exception) { "OSCILLATION" }
        val isBearish = marketDir == "BEARISH"
        val strengthThreshold = if (isBearish) 40 else 20
        Log.i(
            id, "大盘环境: $marketDir → 护城河龙头门槛 ${
                if (isBearish) "20->40, 仅保留有护城河" else "标准门槛20"
            }"
        )

        // Step 1: 基础过滤
        val marketCapMin = config.getDouble("market_cap_min", 300e8)
        val step1 = pool.filter { stock ->
            stock.marketCap >= marketCapMin &&
            stock.pe > 0 &&
            !stock.name.contains("ST", true) &&
            !stock.name.contains("退", true)
        }
        Log.i(id, "pool=${pool.size} -> 基础过滤(mcap>=${marketCapMin/1e8}亿, PE>0, 非ST)=${step1.size}")

        // Step 2: 护城河 + 龙头判定
        val leaderCapMin = config.getDouble("leader_cap_min", 500e8)
        val roeMin = config.getDouble("roe_min", 18.0)
        val grossMarginMin = config.getDouble("gross_margin_min", 25.0)
        val step2 = step1.filter { stock ->
            hasMoat(stock.name) &&
            stock.marketCap >= leaderCapMin &&
            stock.grossMarginTTM >= grossMarginMin &&
            stock.roeTTM >= roeMin
        }
        Log.i(id, "护城河+龙头过滤(护城河匹配, mcap>=${leaderCapMin/1e8}亿, 毛利>=$grossMarginMin%, ROE>=$roeMin%)=${step2.size}")

        // Step 3: BEARISH 时只保留有护城河的（已在 step2 确保）
        // Step 4: 打分
        val scored = step2.map { calculateSignal(it) }
            .filter { it.strength >= strengthThreshold }
        Log.i(id, "打分后 strength>=$strengthThreshold: ${scored.size}")

        // Step 5: 排序截取
        val signals = scored
            .sortedByDescending { it.strength }
            .take(config.maxResults)
        return success(signals, pool.size, startTime)
    }

    // ── Moat detection ──

    private fun hasMoat(name: String): Boolean =
        moatKeywords.any { keyword -> name.contains(keyword) }

    // ── Scoring ──

    private fun calculateSignal(stock: StockRealtime): StrategySignal {
        val w = weightFactors.associateBy { it.key }

        // 护城河评分 (满分30)
        val matchedKeywords = moatKeywords.filter { stock.name.contains(it) }
        val moatScore = when {
            matchedKeywords.size >= 3 -> 30
            matchedKeywords.size >= 2 -> 25
            matchedKeywords.size >= 1 -> 20
            else -> 0
        } * (w["moat"]?.weight ?: 30) / 100

        // 市值龙头评分 (满分25)
        val leaderScore = when {
            stock.marketCap >= 2000e8 -> 25
            stock.marketCap >= 1000e8 -> 22
            stock.marketCap >= 800e8 -> 18
            stock.marketCap >= 500e8 -> 14
            stock.marketCap >= 300e8 -> 10
            else -> 0
        } * (w["leader"]?.weight ?: 25) / 100

        // 毛利率评分 (满分20)
        val marginScore = when {
            stock.grossMarginTTM >= 50.0 -> 20
            stock.grossMarginTTM >= 40.0 -> 16
            stock.grossMarginTTM >= 30.0 -> 12
            stock.grossMarginTTM >= 25.0 -> 8
            else -> 0
        } * (w["margin"]?.weight ?: 20) / 100

        // ROE评分 (满分15)
        val roeScore = when {
            stock.roeTTM >= 25.0 -> 15
            stock.roeTTM >= 22.0 -> 12
            stock.roeTTM >= 18.0 -> 10
            stock.roeTTM > 0 -> 5
            else -> 0
        } * (w["roe"]?.weight ?: 15) / 100

        // 现金流评分 (满分10)
        val cashflowScore = when {
            stock.operatingCashFlow > 100e8 -> 10
            stock.operatingCashFlow > 30e8 -> 8
            stock.operatingCashFlow > 0 -> 5
            else -> 0
        } * (w["cashflow"]?.weight ?: 10) / 100

        val rawStrength = moatScore + leaderScore + marginScore + roeScore + cashflowScore
        val strength = rawStrength.coerceIn(0, 100)

        val sb = StringBuilder("护城河龙头")
        sb.append(" | Moat[").append(matchedKeywords.joinToString(",")).append("]")
        if (stock.marketCap > 0) sb.append(" | MCap").append(String.format("%.0f", stock.marketCap / 1e8)).append("B")
        if (stock.grossMarginTTM > 0) sb.append(" | GM").append(String.format("%.1f", stock.grossMarginTTM)).append("%")
        if (stock.roeTTM > 0) sb.append(" | ROE").append(String.format("%.1f", stock.roeTTM)).append("%")
        if (stock.operatingCashFlow > 0) sb.append(" | OCF").append(String.format("%.1f", stock.operatingCashFlow / 1e8)).append("B")
        if (stock.pe > 0) sb.append(" | PE").append(String.format("%.1f", stock.pe))

        val action = when {
            strength >= 75 -> SignalAction.BUY
            strength >= 50 -> SignalAction.WATCH
            else -> SignalAction.HOLD
        }

        return StrategySignal(
            stockCode = stock.code,
            stockName = stock.name,
            strategyId = id,
            category = category,
            strength = strength,
            action = action,
            reason = "护城河龙头: ${matchedKeywords.firstOrNull() ?: "N/A"}, " +
                    "市值${String.format("%.0f", stock.marketCap / 1e8)}亿, " +
                    "毛利率${String.format("%.1f", stock.grossMarginTTM)}%, " +
                    "ROE=${String.format("%.1f", stock.roeTTM)}%",
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
