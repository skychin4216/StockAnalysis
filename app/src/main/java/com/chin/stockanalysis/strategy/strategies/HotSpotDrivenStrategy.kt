package com.chin.stockanalysis.strategy.strategies

import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.*
import com.chin.stockanalysis.strategy.data.InstitutionalRatingProvider
import com.chin.stockanalysis.strategy.data.StockScreener
import com.chin.stockanalysis.strategy.models.WeightFactor
import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.models.StrategySignal
import com.chin.stockanalysis.strategy.models.SignalAction
import com.chin.stockanalysis.news.NewsFactorManager
import com.chin.stockanalysis.news.NewsFactorEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ## 热点驱动短线策略（午盘精选）
 *
 * 核心逻辑：「短线一定要热点热点热点」
 * 三大驱动因子：
 * 1. 板块热度：今日热门板块 Top 中的龙头股（涨幅前3名）
 * 2. 新闻因子：AI 新闻监控的利好股票
 * 3. 基金增持：基金持仓数据中，本期增持的股票
 *
 * 适用场景：
 * - 午盘追涨（13:00-13:30）：上午放量拉升，下午确认延续
 * - 热点爆发：板块突然暴涨，提前埋伏龙头
 * - 新闻驱动：突发利好，扫描相关板块龙头
 * - 尾盘埋伏：接近收盘时埋伏，第二天卖出
 */
class HotSpotDrivenStrategy(
    private val screener: StockScreener
) : Strategy {

    override val id = "hotspot_driven"
    override var name = "热点驱动短线"
    override var description = "板块热度+新闻因子+基金增持，三重驱动的短线精选策略"
    override val category = StrategyCategory.MOMENTUM
    override val holdingPeriods = listOf(HoldingPeriod.SHORT)
    override val source = StrategySource.BUILTIN
    override val signalExpiryHours = 24    // 24小时

    override val config = StrategyConfig.custom(
        params = mapOf(
            "sector_top_n" to 3,
            "news_factor_hours" to 6,
            "min_change_pct" to 1.0,
            "min_amount" to 50_000_000.0
        ),
        maxResults = 10
    )

    override var weightFactors: List<WeightFactor> = listOf(
        WeightFactor("sector_heat", "板块热度", 40, "板块排名加权"),
        WeightFactor("news_impact", "新闻因子", 30, "新闻利好强度"),
        WeightFactor("fund_boost", "基金增持", 20, "基金持仓变化"),
        WeightFactor("momentum", "动量", 10, "涨幅和量比")
    )

    private val db by lazy { StockDatabase.getInstance(screener.context) }
    private val ratingProvider by lazy { InstitutionalRatingProvider() }
    private val newsManager by lazy { NewsFactorManager(screener.context) }

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
            val pool = if (config.stockPool.isNotEmpty()) preloadedStocks.filter { it.code in config.stockPool } else preloadedStocks
            screenWithPool(pool, startTime)
        } catch (e: Exception) { Result.failure(e) }
    }

    private suspend fun screenWithPool(pool: List<StockRealtime>, startTime: Long): Result<ScreeningResult> {
        if (pool.isEmpty()) return Result.success(ScreeningResult(
            strategyId = id, strategyName = name, category = category,
            signals = emptyList(), totalScanned = 0, scanTimeMs = System.currentTimeMillis() - startTime
        ))

        // ════════════════════════════════════════════════════
        // 因子 1: 板块热度（今日热门板块中的领涨股）
        // ════════════════════════════════════════════════════
        val sectorHotStocks = mutableMapOf<String, Double>()  // code → sectorHeatScore
        try {
            val sectors = db.sectorDailyRecordDao().getRecentDays(1)
                .filter { it.rank <= 5 }.sortedBy { it.rank }
            for (sector in sectors) {
                val sectorStocks = try { db.sectorStockDao().getStockCodesBySector(sector.sectorName) } catch (_: Exception) { emptyList() }
                for (code in sectorStocks) {
                    val heatScore = when (sector.rank) {
                        1 -> 40.0
                        2 -> 30.0
                        3 -> 20.0
                        4 -> 10.0
                        else -> 5.0
                    }
                    sectorHotStocks[code] = maxOf(sectorHotStocks[code] ?: 0.0, heatScore)
                }
            }
            Log.i("HotSpot", "板块热度因子: ${sectorHotStocks.size} 只股票命中热门板块")
        } catch (_: Exception) {}

        // ════════════════════════════════════════════════════
        // 因子 2: 新闻因子（最近6小时的利好新闻）
        // ════════════════════════════════════════════════════
        val newsImpactStocks = mutableMapOf<String, Double>()  // code → newsScore
        try {
            val factors: List<NewsFactorEntity> = newsManager.getActiveFactors(100)
            val recentFactors = factors.filter { it.sentiment == 1 }
            for (f in recentFactors) {
                val score = if (f.impactStrength >= 80) 30.0 else if (f.impactStrength >= 50) 20.0 else 10.0
                // 新闻因子可能包含股票代码或名称
                val codes = extractStockCodes(f, pool)
                for (code in codes) {
                    newsImpactStocks[code] = maxOf(newsImpactStocks[code] ?: 0.0, score)
                }
            }
            Log.i("HotSpot", "新闻因子: ${newsImpactStocks.size} 只股票有利好新闻")
        } catch (_: Exception) {}

        // ════════════════════════════════════════════════════
        // 因子 3: 基金增持（非量化，采样Top5查询）
        // ════════════════════════════════════════════════════
        val fundBoostStocks = mutableSetOf<String>()
        try {
            // 取涨幅前5的股票查询基金持仓
            val topGainers = pool.sortedByDescending { it.changePercent }.take(5)
            for (stock in topGainers) {
                val holdings = ratingProvider.getFundHoldings(stock.code, 5)
                if (holdings.isNotEmpty()) {
                    val totalHoldRatio = holdings.sumOf { it.holdRatio }
                    if (totalHoldRatio > 1.0) {  // 基金合计持股 > 1%
                        fundBoostStocks.add(stock.code)
                        Log.i("HotSpot", "  基金增持: ${stock.name}(${stock.code}) 基金持股${"%.2f".format(totalHoldRatio)}%")
                    }
                }
            }
            Log.i("HotSpot", "基金增持因子: ${fundBoostStocks.size} 只")
        } catch (_: Exception) {}

        // ════════════════════════════════════════════════════
        // 因子 4: 基本过滤（涨幅 > 0.5%，成交额 > 5000万）
        // ════════════════════════════════════════════════════
        val filtered = pool.filter { it.changePercent >= 0.5 && it.amount >= 50_000_000.0 }

        // ════════════════════════════════════════════════════
        // 综合打分
        // ════════════════════════════════════════════════════
        val signals = filtered.map { stock ->
            var strength = 0

            // 板块热度加成 (0-40)
            strength += sectorHotStocks[stock.code]?.toInt() ?: 0

            // 新闻因子加成 (0-30)
            strength += newsImpactStocks[stock.code]?.toInt() ?: 0

            // 基金增持加成 (0-20)
            if (stock.code in fundBoostStocks) strength += 20

            // 动量加成 (0-10)
            strength += when {
                stock.changePercent > 5 -> 10
                stock.changePercent > 3 -> 7
                stock.changePercent > 1 -> 4
                else -> 1
            }

            // 至少需要有一个因子命中才给分（避免无热点股票入选）
            val hasFactor = sectorHotStocks.containsKey(stock.code)
                    || newsImpactStocks.containsKey(stock.code)
                    || fundBoostStocks.contains(stock.code)

            if (!hasFactor) return@map null  // 跳过无热点的股票

            val reason = buildString {
                append("热点驱动:")
                if (sectorHotStocks.containsKey(stock.code)) append("板块热门+${sectorHotStocks[stock.code]?.toInt()};")
                if (newsImpactStocks.containsKey(stock.code)) append("新闻利好+${newsImpactStocks[stock.code]?.toInt()};")
                if (fundBoostStocks.contains(stock.code)) append("基金增持+20;")
                append("涨${"%.2f".format(stock.changePercent)}%")
            }

            StrategySignal(
                stockCode = stock.code, stockName = stock.name, strategyId = id, category = category,
                strength = strength.coerceAtMost(100),
                action = when { strength >= 60 -> SignalAction.BUY; strength >= 35 -> SignalAction.WATCH; else -> SignalAction.HOLD },
                reason = reason,
                currentPrice = stock.price, changePercent = stock.changePercent
            )
        }.filterNotNull().sortedByDescending { it.strength }.take(config.maxResults)

        if (signals.isNotEmpty()) {
            val top3 = signals.take(3)
            Log.i("HotSpot", "Top3: ${top3.joinToString { "${it.stockName}=${it.strength}(${it.reason})" }}")
        }

        return Result.success(ScreeningResult(
            strategyId = id, strategyName = name, category = category,
            signals = signals, totalScanned = pool.size, scanTimeMs = System.currentTimeMillis() - startTime
        ))
    }

    override suspend fun isAvailable(): Boolean = true

    /**
     * 从新闻因子中提取匹配的股票代码
     */
    private suspend fun extractStockCodes(factor: NewsFactorEntity, pool: List<StockRealtime>): List<String> {
        val codes = mutableListOf<String>()
        // 1. 直接匹配代码
        if (factor.stockCode.isNotBlank()) {
            codes.add(factor.stockCode)
        }
        // 2. 名称模糊匹配（取前4个字）
        val namePrefix = factor.companyName.take(4)
        if (namePrefix.length >= 2) {
            val matched = pool.filter { it.name.contains(namePrefix) }.take(3)
            codes.addAll(matched.map { it.code })
        }
        // 3. 板块匹配（新闻因子的 sector 栏位直接包含股票代码或板块名）
        if (factor.sector.isNotBlank()) {
            try {
                // newsFactorDao 按 sector 查不到股票，改用 tags 关联
                val tags = factor.tags.split(",").map { it.trim() }.filter { it.length >= 2 }
                for (tag in tags.take(3)) {
                    val matched = pool.filter { it.name.contains(tag) || tag.contains(it.name.take(2)) }.take(3)
                    codes.addAll(matched.map { it.code })
                }
            } catch (_: Exception) {}
        }
        return codes.distinct()
    }
}
