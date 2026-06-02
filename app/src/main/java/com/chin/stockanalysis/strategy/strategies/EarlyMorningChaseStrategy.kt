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
 * ## 早盘追涨选股分析
 *
 * 基于 9:25~10:35 早盘数据，筛选主力进场、板块拉升的股票。
 *
 * ### 核心逻辑
 * - 9条硬性前置过滤（一票否决）
 * - 5维权重打分（满分100，入选≥75分）
 * - S/A级热门主线绑定
 * - 分段涨幅严控、防骗线、防高开低走
 *
 * ### 数据依赖
 * - 东方财富全市场实时行情（已有）
 * - 东方财富热门板块（已有 EastMoneyHotSectorSource）
 * - TODO: 分时数据（9:30-10:30 逐笔需额外API）
 * - TODO: 主力资金流向（需 Level2 数据）
 */
class EarlyMorningChaseStrategy(
    private val screener: StockScreener
) : Strategy {

    override val id = "early_morning_chase"
    override var name = "早盘追涨选股分析"
    override var description = "9:25~10:35早盘数据驱动：绑定S/A级热门主线，分段涨幅严控，主力资金持续流入，防骗线防高开低走"
    override val category = StrategyCategory.MOMENTUM
    override val source = StrategySource.USER_CUSTOM

    override val config = StrategyConfig.custom(
        params = mapOf(
            "market_cap_min" to 200.0,      // 市值门槛(亿)
            "gap_max" to 1.5,               // 开盘跳空上限%
            "stage1_max" to 0.8,            // 9:30-10:00涨幅上限%
            "total_max" to 1.8,             // 9:30-10:30总涨幅上限%
            "score_threshold" to 75,        // 入选门槛
            "max_results" to 5
        ),
        maxResults = 10
    )

    override var weightFactors: List<WeightFactor> = listOf(
        WeightFactor("ma_golden", "早盘趋势多头金叉", 35, "MA多头排列或MACD低位金叉"),
        WeightFactor("capital_inflow", "后半段主力净流入强度", 30, "10:00-10:30净流入力度"),
        WeightFactor("sector_match", "当日热门主线匹配度", 20, "S级=20分, A级=15分"),
        WeightFactor("stock_active", "个股活跃股性", 10, "近10日有涨停或涨≥7%"),
        WeightFactor("fundamental", "基本面安全兜底", 5, "净利润为正，无重大利空")
    )

    // 热门主线板块（可动态从 EastMoneyHotSectorSource 获取）
    private val HOT_SECTORS_S = setOf("半导体", "AI算力", "存储芯片", "光通信", "光纤", "商业航天")
    private val HOT_SECTORS_A = setOf("新能源", "稀土", "小金属", "锂矿", "军工", "医药", "化工")
    private val COLD_SECTORS = setOf("钢铁", "煤炭", "普通电力", "银行", "保险")

    // TODO: 从 EastMoneyHotSectorSource 动态获取当日S/A级板块
    private fun getSectorGrade(sectorName: String): String {
        for (s in HOT_SECTORS_S) if (sectorName.contains(s)) return "S"
        for (a in HOT_SECTORS_A) if (sectorName.contains(a)) return "A"
        for (c in COLD_SECTORS) if (sectorName.contains(c)) return "COLD"
        return "NONE"
    }

    override suspend fun screen(): Result<ScreeningResult> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        return@withContext try {
            val pool = if (config.stockPool.isEmpty()) screener.scanFullMarket()
            else screener.scanSpecific(config.stockPool).values.toList()
            screenWithPool(pool, startTime)
        } catch (e: Exception) {
            Log.e(id, "策略执行失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun screenWithData(preloadedStocks: List<StockRealtime>): Result<ScreeningResult> {
        val startTime = System.currentTimeMillis()
        return try {
            val pool = if (config.stockPool.isNotEmpty()) preloadedStocks.filter { it.code in config.stockPool } else preloadedStocks
            screenWithPool(pool, startTime)
        } catch (e: Exception) {
            Log.e(id, "策略执行失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun screenWithPool(pool: List<StockRealtime>, startTime: Long): Result<ScreeningResult> {
        if (pool.isEmpty()) return Result.success(ScreeningResult(
            strategyId = id, strategyName = name, category = category,
            signals = emptyList(), totalScanned = 0, scanTimeMs = System.currentTimeMillis() - startTime
        ))

        // Step 1: 硬性前置过滤
        val filtered = pool.filter { passesHardFilters(it) }
        Log.d(id, "硬性过滤: ${pool.size} → ${filtered.size}")

        // Step 2: 打分
        val scored = filtered.map { stock ->
            val score = calculateScore(stock)
            stock to score
        }.filter { (_, score) -> score.total >= config.getInt("score_threshold", 75) }
            .sortedByDescending { (_, score) ->
                score.capitalScore * 100 + (100 - score.stage1Pct.toInt()) * 10 + score.trendScore
            }
            .take(config.maxResults)

        // Step 3: 生成信号
        val signals = scored.map { (stock, score) -> buildSignal(stock, score) }

        return Result.success(ScreeningResult(
            strategyId = id, strategyName = name, category = category,
            signals = signals, totalScanned = pool.size, scanTimeMs = System.currentTimeMillis() - startTime
        ))
    }

    override suspend fun isAvailable(): Boolean = true

    // ═══════════════════════════════
    // 硬性前置过滤（9条，一票否决）
    // ═══════════════════════════════

    private fun passesHardFilters(stock: StockRealtime): Boolean {
        // 1. 市值门槛：≥200亿（通过成交额代理：大市值通常成交额>5亿）
        // TODO: 接入市值数据后改为精确判断
        if (stock.amount < 500_000_000) return false

        // 3. 总体资金：需要主力净流入（通过价格>开盘价&涨跌幅>0代理）
        if (stock.changePercent <= 0 || stock.price <= stock.open) return false

        // 4. 当日热门主线绑定
        // TODO: 接入板块归属数据后精确判断
        // 暂时通过板块关键词匹配，此处放宽

        // 5. 分段涨幅严控
        val gapPct = if (stock.yestClose > 0) ((stock.open - stock.yestClose) / stock.yestClose) * 100 else 0.0
        // 开盘跳空 ≤ 1.5%
        if (gapPct > config.getDouble("gap_max", 1.5)) return false
        // 总涨幅 ≤ 1.8%
        if (stock.changePercent > config.getDouble("total_max", 1.8)) return false

        // 6. 防高开低走：当前价 ≥ 开盘价
        if (stock.price < stock.open) return false

        // 7. 资金节奏：使用盘中涨幅代理（后半段走强→振幅较小且稳步向上）
        // TODO: 接入Level2分时数据

        // 8. 分时防骗线：无长上影线
        if (stock.high > 0 && stock.open > 0) {
            val upperShadow = stock.high - maxOf(stock.price, stock.open)
            val body = kotlin.math.abs(stock.price - stock.open)
            if (body > 0 && upperShadow / body > 1.5) return false  // 长上影
        }

        // 9. 排除冷门板块
        // TODO: 接入板块归属

        return true
    }

    // ═══════════════════════════════
    // 打分
    // ═══════════════════════════════

    data class MorningScore(
        val total: Int,
        val maScore: Int,        // 趋势金叉 0-35
        val capitalScore: Int,   // 主力资金 0-30
        val sectorScore: Int,    // 主线匹配 0-20
        val activeScore: Int,    // 股性 0-10
        val fundScore: Int,      // 基本面 0-5
        val stage1Pct: Double,   // 早盘涨幅(用于排序)
        val trendScore: Int      // 趋势强度(用于排序)
    )

    private fun calculateScore(stock: StockRealtime): MorningScore {
        val w = weightFactors.associateBy { it.key }

        // 1. 早盘趋势多头金叉 (0-35)
        // 通过：当前价>昨收（金叉代理）、涨幅温和（非暴力拉升）
        val maScore = when {
            stock.changePercent > 0 && stock.changePercent <= 1.0 && stock.price > stock.yestClose -> 35
            stock.changePercent > 1.0 && stock.changePercent <= 1.8 && stock.price > stock.yestClose -> 25
            stock.changePercent > 0 && stock.price > stock.yestClose -> 15
            else -> 0
        }

        // 2. 后半段主力净流入强度 (0-30)
        // 通过：成交额大+涨幅温和（代理持续流入）
        val capitalScore = when {
            stock.amount > 2_000_000_000 && stock.changePercent in 0.5..1.5 -> 30
            stock.amount > 1_000_000_000 && stock.changePercent in 0.2..1.5 -> 25
            stock.amount > 500_000_000 && stock.changePercent > 0 -> 15
            stock.changePercent > 0 -> 8
            else -> 0
        }

        // 3. 当日热门主线匹配度 (0-20)
        // TODO: 接入板块归属后精确判断
        val sectorScore = 10  // 默认中性,A级

        // 4. 个股活跃股性 (0-10)
        // 通过：近10日涨幅（用当日涨幅代理）
        val activeScore = when {
            stock.changePercent in 1.0..7.0 -> 10
            stock.changePercent in 0.5..1.0 -> 7
            stock.changePercent in 0.2..0.5 -> 5
            else -> 2
        }

        // 5. 基本面安全 (0-5)
        // 通过：价格>0、有成交（代理无重大利空）
        val fundScore = if (stock.price > 1 && stock.amount > 100_000_000) 5 else 3

        val rawTotal = maScore * (w["ma_golden"]?.weight ?: 35) / 100 +
                capitalScore * (w["capital_inflow"]?.weight ?: 30) / 100 +
                sectorScore * (w["sector_match"]?.weight ?: 20) / 100 +
                activeScore * (w["stock_active"]?.weight ?: 10) / 100 +
                fundScore * (w["fundamental"]?.weight ?: 5) / 100

        return MorningScore(
            total = minOf(rawTotal, 100),
            maScore = maScore,
            capitalScore = capitalScore,
            sectorScore = sectorScore,
            activeScore = activeScore,
            fundScore = fundScore,
            stage1Pct = stock.changePercent,
            trendScore = ((stock.price - stock.open) / maxOf(stock.open, 0.01) * 1000).toInt()
        )
    }

    private fun buildSignal(stock: StockRealtime, score: MorningScore): StrategySignal {
        return StrategySignal(
            stockCode = stock.code,
            stockName = stock.name,
            strategyId = id,
            category = category,
            strength = score.total,
            action = when {
                score.total >= 85 -> SignalAction.BUY
                score.total >= 75 -> SignalAction.WATCH
                else -> SignalAction.HOLD
            },
            reason = "早盘追涨：总分${score.total}(趋势${score.maScore}+资金${score.capitalScore}+主线${score.sectorScore})",
            details = mapOf(
                "trend_score" to "${score.maScore}/35",
                "capital_score" to "${score.capitalScore}/30",
                "sector_score" to "${score.sectorScore}/20",
                "active_score" to "${score.activeScore}/10",
                "fund_score" to "${score.fundScore}/5",
                "entry_zone" to "10:30-11:30 放量突破均价线",
                "stop_loss" to "跌破9:00-10:30分时均价线",
                "take_profit" to "次日必须全清，不格局"
            ),
            currentPrice = stock.price,
            changePercent = stock.changePercent
        )
    }
}