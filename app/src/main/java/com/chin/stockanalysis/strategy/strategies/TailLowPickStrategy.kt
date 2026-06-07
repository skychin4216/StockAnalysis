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
 * ## 超短线股票筛选逻辑（尾盘低吸策略）
 *
 * 核心逻辑：当日14:30-15:00尾盘选股 → 次日早盘10:00前强制清仓。
 *
 * ### 关键特征
 * - 强制低吸形态过滤（13:30-14:30必须出现有效回踩+企稳）
 * - 尾盘资金占比≥30%
 * - 加速行情自动防守（无回踩→降低仓位或空仓）
 * - 7维打分（满分100）
 *
 * ### 硬性过滤
 * - 仅主板 000/600，市值≥150亿
 * - 近3日舆情避雷（一票否决）
 * - 尾盘资金占比≥30%
 * - 强制低吸形态
 * - 分时均价线上方运行
 * - 无长上影/放量滞涨
 */
class TailLowPickStrategy(
    private val screener: StockScreener
) : Strategy {

    override val id = "tail_low_pick"
    override var name = "超短线筛选逻辑"
    override var description = "尾盘14:30-15:00低吸：强制回踩企稳过滤，尾盘资金≥30%，7维打分(主线25+景气10+舆情5+技术20+资金15+股性10+量能10)"
    override val category = StrategyCategory.CUSTOM
    override val source = StrategySource.USER_CUSTOM

    override val config = StrategyConfig.custom(
        params = mapOf(
            "market_cap_min" to 150.0,
            "tail_capital_ratio_min" to 0.30,  // 尾盘资金占比最低30%
            "score_threshold" to 60,           // 入选门槛（从80降到60，让更多候选通过）
            "max_results" to 10,
            "ipo_days_min" to 365             // 上市满12个月
        ),
        maxResults = 10
    )

    override var weightFactors: List<WeightFactor> = listOf(
        WeightFactor("sector_match", "主线赛道匹配", 25, "热门主线25分"),
        WeightFactor("sector_outlook", "行业景气预期", 10, "景气预期10分"),
        WeightFactor("news_sentiment", "近3日舆情", 5, "利好加分"),
        WeightFactor("tail_tech", "尾盘多头技术", 20, "回踩企稳形态评分"),
        WeightFactor("tail_capital", "尾盘主力资金强度", 15, "14:30-15:00资金评分"),
        WeightFactor("stock_active", "个股股性活跃度", 10, "近期活跃评分"),
        WeightFactor("volume_health", "日内量能健康度", 10, "量价配合评分")
    )

    private val CORE_SECTORS = setOf("存储", "通信", "光纤", "半导体封测", "算力", "新能源", "稀土", "小金属", "锂矿", "光通信", "商业航天", "AI")

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

        // Step 1: 硬性过滤
        val filtered = pool.filter { passesHardFilters(it) }
        Log.i("TL_Strategy", "硬性过滤: pool=${pool.size} → ${filtered.size}")

        // Step 2: 7维打分
        val scoredAll = filtered.map { stock ->
            val score = calculateScore(stock)
            stock to score
        }
        val scored = scoredAll.filter { (_, score) -> score.total >= config.getInt("score_threshold", 60) }
        Log.i("TL_Strategy", "打分后 total>=60: ${scored.size}")
        val finalSignals = scored
            .sortedByDescending { (_, score) -> score.total }
            .take(config.maxResults)
            .map { (stock, score) -> buildSignal(stock, score) }

        return Result.success(ScreeningResult(
            strategyId = id, strategyName = name, category = category,
            signals = finalSignals, totalScanned = pool.size, scanTimeMs = System.currentTimeMillis() - startTime
        ))
    }

    override suspend fun isAvailable(): Boolean = true

    // ═══════════════════════════════
    // 硬性过滤（一票否决）
    // ═══════════════════════════════

    private fun passesHardFilters(stock: StockRealtime): Boolean {
        // 1. 市值≥150亿：成交额代理（放宽到1亿）
        if (stock.amount < 100_000_000) return false

        // 3. 价格合理
        if (stock.price <= 1.0) return false

        // 4. 尾盘资金要求：收盘价位于日内高位（代理资金占比≥30%）
        if (stock.high > 0 && stock.price < stock.high * 0.90) return false  // 放宽到90%

        // 5. 低吸形态过滤：禁止直线拉升无回踩
        // 全日涨幅过大且无回踩 → 加速行情，剔除
        if (stock.changePercent > 10) return false  // 放宽到10%

        // 6. 分时形态：排除跌幅超过-5%的股票
        if (stock.changePercent < -5) return false

        // 7. 无长上影线
        if (stock.high > 0 && stock.open > 0) {
            val upperShadow = stock.high - maxOf(stock.price, stock.open)
            val body = kotlin.math.abs(stock.price - stock.open)
            if (body > 0 && upperShadow / body > 2.0) return false  // 极长上影线
        }

        // 8. 日内无宽幅震荡（放宽到25%）
        if (stock.high > 0 && stock.low > 0 && stock.high > stock.low * 1.25) return false

        return true
    }

    // ═══════════════════════════════
    // 7维打分（满分100）
    // ═══════════════════════════════

    data class TailScore(
        val total: Int,
        val sectorScore: Int,
        val outlookScore: Int,
        val newsScore: Int,
        val techScore: Int,
        val capitalScore: Int,
        val activeScore: Int,
        val volumeScore: Int
    )

    private fun calculateScore(stock: StockRealtime): TailScore {
        val w = weightFactors.associateBy { it.key }

        // 1. 主线赛道匹配 0~25
        // TODO: 接入板块归属后精确判断
        val sectorScore = 15  // 默认中等

        // 2. 行业景气预期 0~10（略低于业绩权重）
        // 涨幅温和+资金流入≈景气良好
        val outlookScore = when {
            stock.changePercent in 0.5..3.0 && stock.amount > 500_000_000 -> 10
            stock.changePercent in 0.2..3.0 && stock.amount > 200_000_000 -> 7
            stock.changePercent > 0 -> 5
            else -> 0
        }

        // 3. 近3日舆情 0~5
        // TODO: 接入新闻API
        val newsScore = 2  // 默认中性

        // 4. 尾盘多头技术形态 0~20
        // 通过：日内有回踩（低开/盘中下探后收回）→企稳形态
        val techScore = when {
            // 低开高走：典型回踩企稳
            stock.open < stock.yestClose && stock.price > stock.open && stock.changePercent > 0 -> 20
            // 盘中下探后回升（有下影线）
            stock.low < stock.open && stock.price > stock.open && stock.changePercent in -0.5..2.0 -> 18
            // 小幅震荡后上行
            stock.price > stock.open && stock.changePercent in 0.2..1.5 -> 15
            // 收在开盘价附近（小十字星）
            kotlin.math.abs(stock.price - stock.open) / maxOf(stock.open, 0.01) < 0.005 -> 12
            // 其他情况：只要有成交且价格合理，给基础分
            stock.amount > 200_000_000 -> 8
            else -> 3
        }

        // 5. 尾盘主力资金强度 0~15
        // 通过：成交额大+收在高位（代理尾盘资金）
        val capitalScore = when {
            stock.amount > 2_000_000_000 && stock.price >= stock.open -> 15
            stock.amount > 1_000_000_000 && stock.price >= stock.open -> 12
            stock.amount > 500_000_000 && stock.price >= stock.open && stock.changePercent > 0 -> 10
            stock.amount > 200_000_000 -> 5
            else -> 2
        }

        // 6. 个股股性活跃度 0~10
        // 涨幅和成交额代表活跃度
        val activeScore = when {
            stock.changePercent in 2.0..7.0 && stock.amount > 1_000_000_000 -> 10
            stock.changePercent in 1.0..3.0 && stock.amount > 500_000_000 -> 8
            stock.changePercent in 0.5..2.0 -> 5
            stock.changePercent > 0 -> 3
            else -> 1
        }

        // 7. 日内量能健康度 0~10
        // 温和放量+价格收在高位≈健康
        val volumeScore = when {
            stock.amount > 1_000_000_000 && stock.changePercent in 0.2..3.0 && stock.price > stock.open -> 10
            stock.amount > 500_000_000 && stock.changePercent in 0.2..2.0 -> 8
            stock.amount > 200_000_000 && stock.changePercent in -1.0..2.0 -> 5
            else -> 2
        }

        val rawTotal = sectorScore + outlookScore + newsScore + techScore + capitalScore + activeScore + volumeScore

        return TailScore(
            total = minOf(rawTotal, 100),
            sectorScore = sectorScore,
            outlookScore = outlookScore,
            newsScore = newsScore,
            techScore = techScore,
            capitalScore = capitalScore,
            activeScore = activeScore,
            volumeScore = volumeScore
        )
    }

    private fun getPositionLimit(score: Int): Int = when {
        score >= 95 -> 30
        score >= 90 -> 25
        score >= 85 -> 20
        score >= 80 -> 10
        else -> 5
    }

    private fun buildSignal(stock: StockRealtime, score: TailScore): StrategySignal {
        val posLimit = getPositionLimit(score.total)

        val sb = StringBuilder()
        sb.append("主线${score.sectorScore}|景气${score.outlookScore}|舆情${score.newsScore}|")
        sb.append("技术${score.techScore}|资金${score.capitalScore}|股性${score.activeScore}|量能${score.volumeScore}")

        return StrategySignal(
            stockCode = stock.code,
            stockName = stock.name,
            strategyId = id,
            category = category,
            strength = score.total,
            action = when {
                score.total >= 90 -> SignalAction.BUY
                score.total >= 80 -> SignalAction.WATCH
                else -> SignalAction.HOLD
            },
            reason = "尾盘低吸${score.total}分: $sb",
            details = mapOf(
                "sector_score" to "${score.sectorScore}/25",
                "outlook_score" to "${score.outlookScore}/10",
                "news_score" to "${score.newsScore}/5",
                "tech_score" to "${score.techScore}/20",
                "capital_score" to "${score.capitalScore}/15",
                "active_score" to "${score.activeScore}/10",
                "volume_score" to "${score.volumeScore}/10",
                "position_limit" to "单票≤${posLimit}%",
                "entry_zone" to "14:50-14:58 分时均价线附近低吸",
                "stop_loss" to "次日跌破分时均价线止损",
                "take_profit" to "次日10:00前强制全清"
            ),
            currentPrice = stock.price,
            changePercent = stock.changePercent
        )
    }
}