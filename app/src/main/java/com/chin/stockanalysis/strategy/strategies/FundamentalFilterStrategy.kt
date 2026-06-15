package com.chin.stockanalysis.strategy.strategies

import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.strategy.Strategy
import com.chin.stockanalysis.strategy.StrategyCategory
import com.chin.stockanalysis.strategy.StrategyConfig
import com.chin.stockanalysis.strategy.StrategySource
import com.chin.stockanalysis.strategy.data.StockScreener
import com.chin.stockanalysis.strategy.models.WeightFactor
import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.models.StrategySignal
import com.chin.stockanalysis.strategy.models.SignalAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Three-layer fundamental screening system based on 三层分级筛选系统.txt.
 *
 * Flow: Base Exclusion -> Layer 1 (Basic Value) -> Layer 2 (Quality Growth) -> Layer 3 (Core)
 */
class FundamentalFilterStrategy(
    private val screener: StockScreener
) : Strategy {

    override val id = "fundamental_filter"
    override var name = "基本面三层分级筛选"
    override var description =
        "基础排除(4项) -> 第一层基础价值(8项) -> 第二层优质成长(12项) -> 第三层核心精选(15项) -> 换手率出货警告"
    override val category = StrategyCategory.VALUE
    override val source = StrategySource.BUILTIN

    override val config = StrategyConfig.custom(
        params = mapOf("layer" to 2.0, "max_results" to 30.0),
        maxResults = 30
    )

    override var weightFactors: List<WeightFactor> = listOf(
        WeightFactor("valuation", "估值", 35, "PE+PB"),
        WeightFactor("profitability", "盈利能力", 25, "ROE+毛利率"),
        WeightFactor("liquidity", "流动性", 20, "换手率+成交额"),
        WeightFactor("quality", "财务健康", 20, "负债率+经营现金流")
    )

    private val techPrefixes = setOf("sh688", "sz300", "sz301", "sz002")
    private val techKeywords = setOf(
        "芯片", "半导体", "AI", "算力", "光模块", "光通信", "PCB", "软件",
        "新能源", "锂电", "光伏", "储能", "风电", "创新药", "CXO", "医疗器械",
        "电子", "计算机", "通信", "互联网", "信息技术", "生物科技"
    )

    private fun isTech(code: String, name: String): Boolean =
        techPrefixes.any { code.startsWith(it) } ||
        techKeywords.any { name.contains(it) }

    // ── Screening entry ──

    override suspend fun screen(): Result<ScreeningResult> = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val pool = if (config.stockPool.isEmpty()) screener.scanFullMarket()
            else screener.scanSpecific(config.stockPool).values.toList()
            doScreen(pool, start)
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun screenWithData(stocks: List<StockRealtime>): Result<ScreeningResult> {
        val start = System.currentTimeMillis()
        return try {
            val pool = if (config.stockPool.isNotEmpty()) stocks.filter { it.code in config.stockPool } else stocks
            doScreen(pool, start)
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun isAvailable(): Boolean = true

    // ── Base exclusion ──

    private fun baseExcluded(s: StockRealtime): Boolean =
        s.name.contains("ST", true) || s.name.contains("退", true) ||
        s.amount <= 0.0 || s.volume <= 0L || s.price <= 1.0 ||
        (s.marketCap > 0.001 && s.marketCap < 100000000000.0) ||
        s.pe < 0.0

    // ── Layer 1: Basic Value ──

    private fun l1(s: StockRealtime): Boolean {
        if (baseExcluded(s)) return false
        val tech = isTech(s.code, s.name)
        if (s.marketCap > 0 && s.marketCap > 500000000000.0) return false
        if (s.pe > 0) {
            if (tech && s.pe >= 80.0) return false
            if (!tech && s.pe >= 25.0) return false
        }
        if (s.pb > 0 && s.pb >= 3.0) return false
        if (s.turnoverRate > 0 && (s.turnoverRate < 3.0 || s.turnoverRate > 15.0)) return false
        if (s.amount > 0 && s.amount <= 200000000.0) return false
        if (s.roeTTM > 0 && s.roeTTM <= 10.0) return false
        return true
    }

    // ── Layer 2: Quality Growth ──

    private fun l2(s: StockRealtime): Boolean {
        if (!l1(s)) return false
        val tech = isTech(s.code, s.name)
        if (s.marketCap > 0 && s.marketCap > 1000000000000.0) return false
        if (s.pe > 0) {
            if (tech && s.pe >= 80.0) return false
            if (!tech && s.pe >= 20.0) return false
        }
        if (s.pb > 0 && s.pb >= 2.0) return false
        if (s.turnoverRate > 0 && (s.turnoverRate < 3.0 || s.turnoverRate > 10.0)) return false
        if (s.amount > 0 && s.amount <= 300000000.0) return false
        if (s.roeTTM > 0 && s.roeTTM <= 15.0) return false
        if (s.grossMarginTTM > 0 && s.grossMarginTTM <= 20.0) return false
        if (s.debtToAsset > 0 && s.debtToAsset >= 60.0) return false
        if (s.operatingCashFlow < 0.0) return false
        return true
    }

    // ── Layer 3: Core Selection ──

    private fun l3(s: StockRealtime): Boolean {
        if (!l2(s)) return false
        val tech = isTech(s.code, s.name)
        if (s.marketCap > 0 && (s.marketCap < 200000000000.0 || s.marketCap > 1000000000000.0)) return false
        if (s.pe > 0) {
            if (tech && s.pe >= 80.0) return false
            if (!tech && s.pe >= 18.0) return false
        }
        val pbMax = if (s.roeTTM > 20.0) 2.0 else 1.5
        if (s.pb > 0 && s.pb >= pbMax) return false
        if (s.turnoverRate > 0 && (s.turnoverRate < 3.0 || s.turnoverRate > 8.0)) return false
        if (s.amount > 0 && s.amount <= 500000000.0) return false
        if (s.roeTTM > 0 && s.roeTTM <= 20.0) return false
        if (s.grossMarginTTM > 0 && s.grossMarginTTM <= 30.0) return false
        if (s.debtToAsset > 0 && s.debtToAsset >= 50.0) return false
        if (s.operatingCashFlow < 0.0) return false
        return true
    }

    // ── Distribution warning ──

    private fun warn(s: StockRealtime): Boolean =
        s.turnoverRate > 15.0 && s.changePercent > 7.0

    // ── Pipeline ──

    private fun doScreen(pool: List<StockRealtime>, startTime: Long): Result<ScreeningResult> {
        if (pool.isEmpty()) return success(emptyList(), 0, startTime)
        val layer = (config.params["layer"] as? Number)?.toInt() ?: 2
        val base = pool.filter { !baseExcluded(it) }
        val filtered = when (layer) {
            1 -> base.filter { l1(it) }
            3 -> base.filter { l3(it) }
            else -> base.filter { l2(it) }
        }
        val scored = filtered.map { signal(it, layer) }
            .filter { it.strength >= 20 }
            .sortedByDescending { it.strength }
        return success(scored.take(config.maxResults), pool.size, startTime)
    }

    // ── Scoring ──

    private fun signal(s: StockRealtime, layer: Int): StrategySignal {
        val w = weightFactors.associateBy { it.key }
        val vScore = (peScore(s.pe, s.pb) * (w["valuation"]?.weight ?: 35) / 100.0)
        val pScore = (profScore(s) * (w["profitability"]?.weight ?: 25) / 100.0)
        val lScore = (liqScore(s) * (w["liquidity"]?.weight ?: 20) / 100.0)
        val qScore = (qualScore(s) * (w["quality"]?.weight ?: 20) / 100.0)
        val str = (vScore + pScore + lScore + qScore).toInt().coerceIn(0, 100)
        val label = when (layer) { 1 -> "L1"; 3 -> "L3"; else -> "L2" }
        val sb = StringBuilder(label)
        if (s.marketCap > 0) sb.append(" | MCap").append(String.format("%.0f", s.marketCap / 1e8)).append("B")
        if (s.pe > 0) sb.append(" | PE").append(String.format("%.1f", s.pe))
        if (s.pb > 0) sb.append(" | PB").append(String.format("%.2f", s.pb))
        sb.append(" | TOR").append(String.format("%.1f", s.turnoverRate)).append("%")
        if (s.roeTTM > 0) sb.append(" | ROE").append(String.format("%.1f", s.roeTTM)).append("%")
        if (warn(s)) sb.append(" [WARN]")
        val act = when {
            warn(s) -> SignalAction.HOLD
            str >= 75 -> SignalAction.BUY
            str >= 55 -> SignalAction.WATCH
            else -> SignalAction.HOLD
        }
        return StrategySignal(
            s.code, s.name, id, category, str, act,
            sb.toString(), mapOf(), s.price, s.changePercent
        )
    }

    private fun peScore(pe: Double, pb: Double): Double {
        val a = when { pe > 0 && pe < 10 -> 35.0; pe > 0 && pe < 18 -> 30.0; pe > 0 && pe < 25 -> 25.0; pe > 0 && pe < 40 -> 15.0; pe > 0 && pe < 80 -> 10.0; pe > 0 -> 5.0; else -> 15.0 }
        val b = when { pb > 0 && pb < 1.0 -> 15.0; pb > 0 && pb < 1.5 -> 12.0; pb > 0 && pb < 2.0 -> 10.0; pb > 0 && pb < 3.0 -> 6.0; pb > 0 -> 3.0; else -> 8.0 }
        return a + b
    }

    private fun profScore(s: StockRealtime): Double {
        val a = when { s.roeTTM > 20 -> 25.0; s.roeTTM > 15 -> 20.0; s.roeTTM > 10 -> 15.0; s.roeTTM > 0 -> 8.0; else -> 10.0 }
        val b = when { s.grossMarginTTM > 40 -> 15.0; s.grossMarginTTM > 30 -> 12.0; s.grossMarginTTM > 20 -> 8.0; s.grossMarginTTM > 0 -> 4.0; else -> 6.0 }
        return a + b
    }

    private fun liqScore(s: StockRealtime): Double {
        val a = when { s.turnoverRate in 3.0..8.0 -> 30.0; s.turnoverRate in 8.0..10.0 -> 20.0; s.turnoverRate in 1.0..3.0 -> 15.0; s.turnoverRate > 10.0 -> 8.0; else -> 12.0 }
        val b = when { s.amount > 10e9 -> 15.0; s.amount > 5e9 -> 12.0; s.amount > 3e9 -> 10.0; s.amount > 2e9 -> 7.0; s.amount > 500e6 -> 5.0; else -> 3.0 }
        return a + b
    }

    private fun qualScore(s: StockRealtime): Double {
        val a = when { s.debtToAsset > 0 && s.debtToAsset < 30 -> 15.0; s.debtToAsset > 0 && s.debtToAsset < 50 -> 12.0; s.debtToAsset > 0 && s.debtToAsset < 60 -> 8.0; s.debtToAsset > 0 -> 3.0; else -> 8.0 }
        val b = when { s.operatingCashFlow > 0 -> 10.0; s.operatingCashFlow < 0 -> 0.0; else -> 5.0 }
        val c = when { abs(s.changePercent) < 2.0 -> 5.0; abs(s.changePercent) < 5.0 -> 3.0; else -> 1.0 }
        return a + b + c
    }

    private fun success(
        signals: List<StrategySignal>, total: Int, startTime: Long
    ) = Result.success(ScreeningResult(
        id, name, category, signals, total,
        System.currentTimeMillis() - startTime
    ))
}