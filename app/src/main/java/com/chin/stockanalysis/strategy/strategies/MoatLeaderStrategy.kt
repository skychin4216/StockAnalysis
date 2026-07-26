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
    override var name = "行業龍頭護城河"
    override var description =
        "篩選高壁壘行業龍頭，結合護城河板塊匹配、市值規模、毛利率和ROE選出定價權強的行業領先者"
    override val category = StrategyCategory.VALUE
    override val holdingPeriods = listOf(HoldingPeriod.LONG)
    override val source = StrategySource.BUILTIN

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
        WeightFactor("moat", "護城河", 30, "高壁壘行業板塊匹配度"),
        WeightFactor("leader", "市值龍頭", 25, "行業內市值排名靠前"),
        WeightFactor("margin", "毛利率", 20, "毛利率反映定價權強弱"),
        WeightFactor("roe", "ROE", 15, "資本運用效率"),
        WeightFactor("cashflow", "現金流", 10, "經營現金流驗證盈利質量")
    )

    /** 高壁壘行業關鍵詞集合 */
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

        // 大盤環境預檢
        val marketDir = try {
            screener.detectMarketDirection()
        } catch (_: Exception) { "OSCILLATION" }
        val isBearish = marketDir == "BEARISH"
        val strengthThreshold = if (isBearish) 40 else 20
        Log.i(
            id, "大盤環境: $marketDir → 護城河龍頭門檻 ${
                if (isBearish) "20->40, 僅保留有護城河" else "標準門檻20"
            }"
        )

        // Step 1: 基礎過濾
        val marketCapMin = config.getDouble("market_cap_min", 300e8)
        val step1 = pool.filter { stock ->
            stock.marketCap >= marketCapMin &&
            stock.pe > 0 &&
            !stock.name.contains("ST", true) &&
            !stock.name.contains("退", true)
        }
        Log.i(id, "pool=${pool.size} -> 基礎過濾(mcap>=${marketCapMin/1e8}億, PE>0, 非ST)=${step1.size}")

        // Step 2: 護城河 + 龍頭判定
        val leaderCapMin = config.getDouble("leader_cap_min", 500e8)
        val roeMin = config.getDouble("roe_min", 18.0)
        val grossMarginMin = config.getDouble("gross_margin_min", 25.0)
        val step2 = step1.filter { stock ->
            hasMoat(stock.name) &&
            stock.marketCap >= leaderCapMin &&
            stock.grossMarginTTM >= grossMarginMin &&
            stock.roeTTM >= roeMin
        }
        Log.i(id, "護城河+龍頭過濾(護城河匹配, mcap>=${leaderCapMin/1e8}億, 毛利>=$grossMarginMin%, ROE>=$roeMin%)=${step2.size}")

        // Step 3: BEARISH 時只保留有護城河的（已在 step2 確保）
        // Step 4: 打分
        val scored = step2.map { calculateSignal(it) }
            .filter { it.strength >= strengthThreshold }
        Log.i(id, "打分後 strength>=$strengthThreshold: ${scored.size}")

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

        // 護城河評分 (满分30)
        val matchedKeywords = moatKeywords.filter { stock.name.contains(it) }
        val moatScore = when {
            matchedKeywords.size >= 3 -> 30
            matchedKeywords.size >= 2 -> 25
            matchedKeywords.size >= 1 -> 20
            else -> 0
        } * (w["moat"]?.weight ?: 30) / 100

        // 市值龍頭評分 (满分25)
        val leaderScore = when {
            stock.marketCap >= 2000e8 -> 25
            stock.marketCap >= 1000e8 -> 22
            stock.marketCap >= 800e8 -> 18
            stock.marketCap >= 500e8 -> 14
            stock.marketCap >= 300e8 -> 10
            else -> 0
        } * (w["leader"]?.weight ?: 25) / 100

        // 毛利率評分 (满分20)
        val marginScore = when {
            stock.grossMarginTTM >= 50.0 -> 20
            stock.grossMarginTTM >= 40.0 -> 16
            stock.grossMarginTTM >= 30.0 -> 12
            stock.grossMarginTTM >= 25.0 -> 8
            else -> 0
        } * (w["margin"]?.weight ?: 20) / 100

        // ROE評分 (满分15)
        val roeScore = when {
            stock.roeTTM >= 25.0 -> 15
            stock.roeTTM >= 22.0 -> 12
            stock.roeTTM >= 18.0 -> 10
            stock.roeTTM > 0 -> 5
            else -> 0
        } * (w["roe"]?.weight ?: 15) / 100

        // 現金流評分 (满分10)
        val cashflowScore = when {
            stock.operatingCashFlow > 100e8 -> 10
            stock.operatingCashFlow > 30e8 -> 8
            stock.operatingCashFlow > 0 -> 5
            else -> 0
        } * (w["cashflow"]?.weight ?: 10) / 100

        val rawStrength = moatScore + leaderScore + marginScore + roeScore + cashflowScore
        val strength = rawStrength.coerceIn(0, 100)

        val sb = StringBuilder("護城河龍頭")
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
            reason = "護城河龍頭: ${matchedKeywords.firstOrNull() ?: "N/A"}, " +
                    "市值${String.format("%.0f", stock.marketCap / 1e8)}億, " +
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
