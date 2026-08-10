package com.chin.stockanalysis.strategy.market

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.news.NewsFactorEntity
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.backtest.SectorDailyRecordEntity
import com.chin.stockanalysis.strategy.backtest.SectorRotationEngine
import com.chin.stockanalysis.strategy.data.AIHotSectorProvider
import com.chin.stockanalysis.strategy.topology.nodes.InstitutionalTipEntity
import com.chin.stockanalysis.strategy.trade.DailyNewsHotPickEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.pow
import kotlin.math.sqrt

// ════════════════════════════════════════════════════════════════════════════
//  SectorTrendForecaster — 月度热点前瞻引擎
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 月度热点前瞻引擎
 *
 * 融合多源信号预测未来一个月的热门板块方向，填补现有框架仅能预测次日板块的空白。
 *
 * ### 与现有板块分析模块的区别
 * - `SectorRotationEngine.predictTomorrow()` — 纯量化，仅预测**次日**板块（动量+资金）
 * - `AIHotSectorProvider.monthlySectors` — AI 查询**当前**月度热门（描述性，非预测）
 * - `NewsStrengthNode` — 仅分析**当日**新闻情绪，无趋势聚合
 * - **本引擎** — 聚合 30 天多源数据，预测**下个月**热门方向（前瞻性）
 *
 * ### 五维评分模型
 * | 维度 | 权重 | 数据源 | 说明 |
 * |------|------|--------|------|
 * | 新闻热度趋势 | 25% | daily_news_hot_picks | 30天内板块上榜次数 + 趋势方向 |
 * | 资金持续性 | 22% | sector_daily_record | 连续净流入天数 + 流入金额稳定性 |
 * | 动量加速 | 18% | sector_daily_record | 20日均幅 vs 5日均幅，检测加速/减速 |
 * | 机构线索热度 | 20% | institutional_tips | 用户输入的机构推荐板块聚集度 |
 * | 新闻因子质量 | 10% | news_factors | 板块相关新闻数 × 平均影响强度 |
 * | 轮动预测一致性 | 5% | SectorRotationEngine | 近期 predictTomorrow 中持续出现 |
 *
 * @author StockAnalysis Team
 * @since 2026-08-01
 */
class SectorTrendForecaster(private val context: Context) {

    companion object {
        private const val TAG = "SectorTrendForecaster"
        private const val LOOKBACK_DAYS = 30
        private const val TOP_K = 10
        private const val MIN_DATA_DAYS = 5

        // 评分权重
        private const val W_NEWS_TREND = 0.25
        private const val W_CAPITAL = 0.22
        private const val W_MOMENTUM = 0.18
        private const val W_INST_TIPS = 0.20
        private const val W_NEWS_FACTOR = 0.10
        private const val W_ROTATION = 0.05

        // 日期格式
        private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    }

    /**
     * 单个板块的月度前瞻预测结果
     */
    data class SectorForecast(
        val sectorName: String,
        val sectorCode: String,
        /** 综合趋势分数 0-100，越高越看好 */
        val compositeScore: Double,
        /** 趋势方向 */
        val trend: TrendDirection,
        /** 置信度 0-1 */
        val confidence: Double,
        // 各维度得分
        val newsTrendScore: Double,
        val capitalScore: Double,
        val momentumScore: Double,
        val instTipsScore: Double,
        val newsFactorScore: Double,
        val rotationScore: Double,
        /** 支撑证据摘要 */
        val evidence: List<String>,
        /** 相关股票代码（如有） */
        val relatedStockCodes: List<String>
    )

    /**
     * 趋势方向
     */
    enum class TrendDirection(val symbol: String, val label: String) {
        RISING("↑↑", "加速上升"),
        STABLE_RISING("↑", "稳步上升"),
        STABLE("→", "横盘震荡"),
        FALLING("↓", "降温回落"),
        UNKNOWN("?", "数据不足")
    }

    /**
     * 月度前瞻报告
     */
    data class MonthlyForecastReport(
        val targetMonth: String,
        val generatedDate: String,
        val sectors: List<SectorForecast>,
        val marketContext: String,
        val dataCoverage: String,
        val narrative: String
    )

    /**
     * 执行月度热点前瞻预测
     *
     * @return 月度前瞻报告，含 Top-K 板块预测
     */
    suspend fun forecast(): MonthlyForecastReport = withContext(Dispatchers.IO) {
        Log.i(TAG, "▶ 开始月度热点前瞻预测...")
        val db = StockDatabase.getInstance(context)
        val sdf = SimpleDateFormat("yyyy-MM", Locale.getDefault())
        val cal = Calendar.getInstance()
        val currentMonth = sdf.format(cal.time)
        cal.add(Calendar.MONTH, 1)
        val targetMonth = sdf.format(cal.time)
        val today = DATE_FMT.format(Date())

        // ══ 1. 采集多源数据 ══
        val sectorRecords = collectSectorRecords(db)
        val newsHotPicks = collectNewsHotPicks(db)
        val newsFactors = collectNewsFactors(db)
        val rotationPredictions = collectRotationPredictions(db)
        val instTips = collectInstitutionalTips(db)
        val aiMonthlySectors = try {
            AIHotSectorProvider.getHotSectors(context).monthlySectors
        } catch (e: Exception) {
            Log.w(TAG, "AI 月度板块查询失败: ${e.message}")
            emptyList()
        }

        Log.i(TAG, "  数据采集: sector_records=${sectorRecords.size}, " +
            "news_hot_picks=${newsHotPicks.size}, " +
            "news_factors=${newsFactors.size}, " +
            "inst_tips=${instTips.size}, " +
            "rotation_preds=${rotationPredictions.size}, " +
            "ai_monthly=${aiMonthlySectors.size}")

        // ══ 2. 汇总所有板块名称 ══
        val allSectorNames = mutableSetOf<String>()
        allSectorNames.addAll(sectorRecords.map { it.sectorName })
        allSectorNames.addAll(newsHotPicks.map { it.sectorName })
        allSectorNames.addAll(newsFactors.mapNotNull { it.sector.takeIf { s -> s.isNotEmpty() } })
        allSectorNames.addAll(instTips.mapNotNull { it.sector.takeIf { s -> s.isNotEmpty() } })
        allSectorNames.addAll(aiMonthlySectors)

        if (allSectorNames.isEmpty()) {
            Log.w(TAG, "⚠ 无可用板块数据，返回空报告")
            return@withContext MonthlyForecastReport(
                targetMonth = targetMonth,
                generatedDate = today,
                sectors = emptyList(),
                marketContext = "数据不足",
                dataCoverage = "0 天",
                narrative = "当前无足够的板块数据进行月度前瞻预测。建议先运行量化选股累积数据。"
            )
        }

        // ══ 3. 逐板块计算五维评分 ══
        val forecasts = mutableListOf<SectorForecast>()
        for (sectorName in allSectorNames) {
            val forecast = scoreSector(
                sectorName = sectorName,
                sectorRecords = sectorRecords.filter { it.sectorName == sectorName || it.sectorName.contains(sectorName) },
                newsHotPicks = newsHotPicks.filter { it.sectorName == sectorName || it.sectorName.contains(sectorName) },
                newsFactors = newsFactors.filter { it.sector.contains(sectorName) || it.title.contains(sectorName) },
                instTips = instTips.filter { it.sector.contains(sectorName) || it.stockName.contains(sectorName) || it.summary.contains(sectorName) },
                rotationPredictions = rotationPredictions,
                aiMonthlySectors = aiMonthlySectors
            )
            forecasts.add(forecast)
        }

        // ══ 4. 排序取 Top-K ══
        val topSectors = forecasts
            .sortedByDescending { it.compositeScore }
            .take(TOP_K)

        // ══ 5. 生成叙述 ══
        val dataDays = sectorRecords.map { it.date }.distinct().size
        val narrative = buildNarrative(topSectors, targetMonth, dataDays, aiMonthlySectors)
        val marketContext = buildMarketContext(sectorRecords, topSectors)

        Log.i(TAG, "✅ 月度前瞻完成: ${topSectors.size} 个板块, 目标月=$targetMonth, 数据覆盖=$dataDays 天")

        MonthlyForecastReport(
            targetMonth = targetMonth,
            generatedDate = today,
            sectors = topSectors,
            marketContext = marketContext,
            dataCoverage = "$dataDays 天",
            narrative = narrative
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  数据采集
    // ══════════════════════════════════════════════════════════════════════════

    private suspend fun collectSectorRecords(db: StockDatabase): List<SectorDailyRecordEntity> {
        return try {
            db.sectorDailyRecordDao().getRecentDays(LOOKBACK_DAYS)
        } catch (e: Exception) {
            Log.w(TAG, "采集 sector_daily_record 失败: ${e.message}")
            emptyList()
        }
    }

    private suspend fun collectNewsHotPicks(db: StockDatabase): List<DailyNewsHotPickEntity> {
        return try {
            val dates = db.dailyNewsHotPickDao().getAvailableDates()
            val cutoff = getCutoffDate(LOOKBACK_DAYS)
            val recentDates = dates.filter { it >= cutoff }
            val result = mutableListOf<DailyNewsHotPickEntity>()
            for (date in recentDates) {
                result.addAll(db.dailyNewsHotPickDao().getByDate(date))
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "采集 daily_news_hot_picks 失败: ${e.message}")
            emptyList()
        }
    }

    private suspend fun collectNewsFactors(db: StockDatabase): List<NewsFactorEntity> {
        return try {
            val cutoff = getCutoffDate(LOOKBACK_DAYS)
            db.newsFactorDao().getActiveByDateRange(cutoff, DATE_FMT.format(Date()))
        } catch (e: Exception) {
            Log.w(TAG, "采集 news_factors 失败: ${e.message}")
            emptyList()
        }
    }

    private suspend fun collectRotationPredictions(db: StockDatabase): List<String> {
        return try {
            val engine = SectorRotationEngine(context)
            engine.predictTomorrow(10).map { it.sectorName }
        } catch (e: Exception) {
            Log.w(TAG, "采集 rotation predictions 失败: ${e.message}")
            emptyList()
        }
    }

    /**
     * 采集机构线索（用户在 AI 对话框输入的机构推荐）
     * 取最近 30 天内未过期的所有线索
     */
    private suspend fun collectInstitutionalTips(db: StockDatabase): List<InstitutionalTipEntity> {
        return try {
            val today = DATE_FMT.format(Date())
            db.institutionalTipDao().getActiveTips(today)
        } catch (e: Exception) {
            Log.w(TAG, "采集 institutional_tips 失败: ${e.message}")
            emptyList()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  五维评分
    // ══════════════════════════════════════════════════════════════════════════

    private fun scoreSector(
        sectorName: String,
        sectorRecords: List<SectorDailyRecordEntity>,
        newsHotPicks: List<DailyNewsHotPickEntity>,
        newsFactors: List<NewsFactorEntity>,
        instTips: List<InstitutionalTipEntity>,
        rotationPredictions: List<String>,
        aiMonthlySectors: List<String>
    ): SectorForecast {
        val evidence = mutableListOf<String>()

        // ── 维度 1: 新闻热度趋势 (0-100) ──
        val newsTrendScore = scoreNewsTrend(newsHotPicks, evidence)

        // ── 维度 2: 资金持续性 (0-100) ──
        val capitalScore = scoreCapitalPersistence(sectorRecords, evidence)

        // ── 维度 3: 动量加速 (0-100) ──
        val momentumScore = scoreMomentumDivergence(sectorRecords, evidence)

        // ── 维度 4: 机构线索热度 (0-100) ──
        val instTipsScore = scoreInstitutionalTips(instTips, evidence)

        // ── 维度 5: 新闻因子质量 (0-100) ──
        val newsFactorScore = scoreNewsFactorQuality(newsFactors, evidence)

        // ── 维度 6: 轮动预测一致性 (0-100) ──
        val rotationScore = scoreRotationConsistency(sectorName, rotationPredictions, aiMonthlySectors, evidence)

        // ── 综合评分 ──
        val composite = newsTrendScore * W_NEWS_TREND +
            capitalScore * W_CAPITAL +
            momentumScore * W_MOMENTUM +
            instTipsScore * W_INST_TIPS +
            newsFactorScore * W_NEWS_FACTOR +
            rotationScore * W_ROTATION

        // ── 趋势方向判定 ──
        val trend = determineTrend(momentumScore, capitalScore, newsTrendScore)

        // ── 置信度 ──
        val dataRichness = listOf(sectorRecords.size, newsHotPicks.size, newsFactors.size, instTips.size).count { it > 0 }
        val confidence = (composite / 100.0) * (0.5 + dataRichness * 0.12)

        // ── 相关股票代码 ──
        val relatedCodes = (newsHotPicks.flatMap { it.relatedStockCodes.split(",").filter { c -> c.isNotEmpty() } }
            + newsFactors.map { it.stockCode }.filter { it.isNotEmpty() }
            + instTips.map { it.stockCode }.filter { it.isNotEmpty() })
            .distinct().take(10)

        return SectorForecast(
            sectorName = sectorName,
            sectorCode = sectorRecords.firstOrNull()?.sectorCode ?: "",
            compositeScore = composite.round1(),
            trend = trend,
            confidence = confidence.coerceIn(0.0, 1.0).round2(),
            newsTrendScore = newsTrendScore.round1(),
            capitalScore = capitalScore.round1(),
            momentumScore = momentumScore.round1(),
            instTipsScore = instTipsScore.round1(),
            newsFactorScore = newsFactorScore.round1(),
            rotationScore = rotationScore.round1(),
            evidence = evidence,
            relatedStockCodes = relatedCodes
        )
    }

    /**
     * 维度 1: 新闻热度趋势
     * - 上榜次数 × 平均热度
     * - 近期 vs 早期对比，检测上升/下降趋势
     */
    private fun scoreNewsTrend(
        picks: List<DailyNewsHotPickEntity>,
        evidence: MutableList<String>
    ): Double {
        if (picks.isEmpty()) return 20.0 // 无新闻不一定是坏事，给基础分

        val totalCount = picks.size
        val avgHotScore = picks.map { it.hotScore }.average()
        val baseScore = (totalCount.toDouble() / LOOKBACK_DAYS * 100).coerceAtMost(50.0) +
            (avgHotScore / 100.0 * 30.0)

        // 趋势方向：近期10天 vs 早期10天
        val sorted = picks.sortedBy { it.newsDate }
        val recentCount = sorted.takeLastWhile { it.newsDate >= getCutoffDate(10) }.size
        val earlyCount = sorted.takeWhile { it.newsDate < getCutoffDate(10) }.size

        val trendBonus = when {
            recentCount > earlyCount && earlyCount > 0 -> {
                evidence.add("新闻热度上升（早期${earlyCount}次→近期${recentCount}次）")
                20.0
            }
            recentCount > 0 && earlyCount == 0 -> {
                evidence.add("新晋热点（近10天上榜${recentCount}次）")
                15.0
            }
            recentCount == earlyCount && recentCount > 0 -> {
                evidence.add("新闻热度稳定（${totalCount}次上榜）")
                5.0
            }
            recentCount < earlyCount && earlyCount > 0 -> {
                evidence.add("新闻热度降温（早期${earlyCount}次→近期${recentCount}次）")
                -10.0
            }
            else -> 0.0
        }

        return (baseScore + trendBonus).coerceIn(0.0, 100.0)
    }

    /**
     * 维度 2: 资金持续性
     * - 连续净流入天数
     * - 流入金额稳定性（标准差越小越好）
     */
    private fun scoreCapitalPersistence(
        records: List<SectorDailyRecordEntity>,
        evidence: MutableList<String>
    ): Double {
        if (records.size < MIN_DATA_DAYS) {
            evidence.add("资金数据不足（仅${records.size}天）")
            return 25.0
        }

        val sorted = records.sortedBy { it.date }
        val inflows = sorted.map { it.mainNetInflow }
        val positiveDays = inflows.count { it > 0 }
        val positiveRatio = positiveDays.toDouble() / inflows.size

        // 连续流入
        var maxConsecutive = 0
        var current = 0
        for (inf in inflows) {
            if (inf > 0) { current++; maxConsecutive = maxOf(maxConsecutive, current) }
            else current = 0
        }

        // 稳定性：标准差 / 均值（变异系数），越小越稳定
        val avgInflow = inflows.average()
        val variance = inflows.map { (it - avgInflow).pow(2.0) }.average()
        val stdDev = sqrt(variance)
        val cv = if (avgInflow > 0) stdDev / avgInflow else 1.0
        val stabilityScore = (1.0 - cv.coerceIn(0.0, 1.0)) * 100

        val score = positiveRatio * 50 + (maxConsecutive.toDouble() / records.size * 100).coerceAtMost(30.0) + stabilityScore * 0.2

        when {
            maxConsecutive >= 10 -> evidence.add("连续${maxConsecutive}天资金净流入（强势）")
            maxConsecutive >= 5 -> evidence.add("连续${maxConsecutive}天资金净流入")
            positiveRatio > 0.6 -> evidence.add("资金净流入占比${(positiveRatio * 100).round0()}%")
            positiveRatio < 0.3 -> evidence.add("资金净流出占比${((1 - positiveRatio) * 100).round0()}%（弱势）")
        }

        return score.coerceIn(0.0, 100.0)
    }

    /**
     * 维度 3: 动量加速
     * - 20日均幅 vs 5日均幅，检测加速/减速
     * - 类似 MACD 的快慢线概念
     */
    private fun scoreMomentumDivergence(
        records: List<SectorDailyRecordEntity>,
        evidence: MutableList<String>
    ): Double {
        if (records.size < MIN_DATA_DAYS) return 30.0

        val sorted = records.sortedBy { it.date }
        val allChanges = sorted.map { it.changePct }

        // 近5日均幅
        val recent5 = allChanges.takeLast(5)
        val recent5Avg = recent5.average()

        // 近20日均幅（或全部）
        val recent20 = allChanges.takeLast(minOf(20, allChanges.size))
        val recent20Avg = recent20.average()

        // 动量差（加速因子）
        val momentumDiff = recent5Avg - recent20Avg

        // 基础分：20日均幅 × 5
        val baseScore = (recent20Avg * 5).coerceIn(-30.0, 40.0) + 40

        // 加速/减速加成
        val accelBonus = when {
            momentumDiff > 2.0 -> {
                evidence.add("动量加速（5日均幅${recent5Avg.round2()}% > 20日均幅${recent20Avg.round2()}%）")
                25.0
            }
            momentumDiff > 0.5 -> {
                evidence.add("动量温和加速")
                10.0
            }
            momentumDiff > -0.5 -> {
                0.0
            }
            momentumDiff > -2.0 -> {
                evidence.add("动量温和减速")
                -10.0
            }
            else -> {
                evidence.add("动量明显减速（5日均幅${recent5Avg.round2()}% < 20日均幅${recent20Avg.round2()}%）")
                -25.0
            }
        }

        // 热度等级加分
        val hotBonus = sorted.lastOrNull()?.let { rec ->
            when (rec.isHot) {
                "S" -> 15.0
                "A" -> 10.0
                "B" -> 5.0
                else -> 0.0
            }
        } ?: 0.0

        return (baseScore + accelBonus + hotBonus).coerceIn(0.0, 100.0)
    }

    /**
     * 维度 4: 机构线索热度
     * - 用户在 AI 对话框输入的机构推荐中，与本板块相关的线索数量
     * - 线索类型加权：target(目标价) > rating(评级) > research(研报)
     * - 多个机构推荐同一板块 = 板块共识度上升
     */
    private fun scoreInstitutionalTips(
        tips: List<InstitutionalTipEntity>,
        evidence: MutableList<String>
    ): Double {
        if (tips.isEmpty()) return 20.0 // 无机构线索不扣分，给基础分

        val totalTips = tips.size
        val uniqueStocks = tips.map { it.stockCode }.distinct().size

        // 线索类型加权
        val typeWeight = tips.sumOf { tip ->
            when (tip.tipType) {
                "target" -> 3.0  // 目标价最有价值
                "rating" -> 2.0  // 评级次之
                else -> 1.0      // 研报基础
            }
        }

        // 基础分：线索数量（上限50）+ 涉及股票数（上限20）+ 类型加权（上限20）
        val baseScore = (totalTips.toDouble() / 10 * 50).coerceAtMost(50.0) +
            (uniqueStocks.toDouble() / 5 * 20).coerceAtMost(20.0) +
            (typeWeight / 10 * 20).coerceAtMost(20.0)

        // 聚集度加成：多个机构推荐同一板块 = 共识
        val consensusBonus = when {
            uniqueStocks >= 5 -> {
                evidence.add("机构共识度高（${uniqueStocks}只股票被推荐）")
                15.0
            }
            uniqueStocks >= 3 -> {
                evidence.add("机构推荐聚集（${uniqueStocks}只股票）")
                8.0
            }
            uniqueStocks >= 1 -> {
                evidence.add("机构线索${totalTips}条（涉及${uniqueStocks}只股票）")
                0.0
            }
            else -> 0.0
        }

        return (baseScore + consensusBonus).coerceIn(0.0, 100.0)
    }

    /**
     * 维度 4: 新闻因子质量
     * - 板块相关新闻数量 × 平均影响强度 × 情绪方向
     */
    private fun scoreNewsFactorQuality(
        factors: List<NewsFactorEntity>,
        evidence: MutableList<String>
    ): Double {
        if (factors.isEmpty()) return 15.0

        val count = factors.size
        val avgImpact = factors.map { it.impactStrength }.average()
        val bullishCount = factors.count { it.sentiment > 0 }
        val bearishCount = factors.count { it.sentiment < 0 }
        val sentimentRatio = if (count > 0) bullishCount.toDouble() / count else 0.5

        // 基础分：新闻数量（上限40）+ 平均影响强度（上限30）
        val baseScore = (count.toDouble() / 50 * 40).coerceAtMost(40.0) + (avgImpact / 100.0 * 30.0)

        // 情绪加成
        val sentimentBonus = when {
            sentimentRatio > 0.7 -> {
                evidence.add("新闻情绪强烈利好（利好${bullishCount}条/利空${bearishCount}条）")
                25.0
            }
            sentimentRatio > 0.5 -> {
                evidence.add("新闻情绪偏利好")
                10.0
            }
            sentimentRatio < 0.3 -> {
                evidence.add("新闻情绪偏利空（利好${bullishCount}/利空${bearishCount}）")
                -15.0
            }
            else -> 0.0
        }

        return (baseScore + sentimentBonus).coerceIn(0.0, 100.0)
    }

    /**
     * 维度 5: 轮动预测一致性
     * - SectorRotationEngine.predictTomorrow 中是否持续出现
     * - AI 月度板块中是否出现
     */
    private fun scoreRotationConsistency(
        sectorName: String,
        rotationPredictions: List<String>,
        aiMonthlySectors: List<String>,
        evidence: MutableList<String>
    ): Double {
        var score = 30.0 // 基础分

        // 出现在日常轮动预测中
        if (rotationPredictions.any { it.contains(sectorName) || sectorName.contains(it) }) {
            score += 30.0
            evidence.add("入选次日轮动预测")
        }

        // 出现在 AI 月度板块中
        if (aiMonthlySectors.any { it.contains(sectorName) || sectorName.contains(it) }) {
            score += 40.0
            evidence.add("AI月度板块推荐")
        }

        return score.coerceIn(0.0, 100.0)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  辅助方法
    // ══════════════════════════════════════════════════════════════════════════

    private fun determineTrend(
        momentumScore: Double,
        capitalScore: Double,
        newsTrendScore: Double
    ): TrendDirection {
        val avg = (momentumScore + capitalScore + newsTrendScore) / 3.0
        val momentumRising = momentumScore > 55
        val capitalRising = capitalScore > 55
        val newsRising = newsTrendScore > 55

        return when {
            avg < 25 -> TrendDirection.UNKNOWN
            momentumRising && capitalRising && newsRising -> TrendDirection.RISING
            (momentumRising || capitalRising) && newsRising -> TrendDirection.STABLE_RISING
            avg < 45 -> TrendDirection.FALLING
            else -> TrendDirection.STABLE
        }
    }

    private fun buildNarrative(
        topSectors: List<SectorForecast>,
        targetMonth: String,
        dataDays: Int,
        aiMonthlySectors: List<String>
    ): String {
        if (topSectors.isEmpty()) {
            return "数据不足，无法生成 $targetMonth 月度前瞻报告。建议先运行量化选股累积至少 10 天数据。"
        }

        val sb = StringBuilder()
        sb.appendLine("📅 $targetMonth 月度热点前瞻报告")
        sb.appendLine("━".repeat(42))
        sb.appendLine()

        if (dataDays < MIN_DATA_DAYS) {
            sb.appendLine("⚠ 注意：目前仅有 $dataDays 天数据，预测置信度较低。")
            sb.appendLine("   建议累积 15+ 天数据后再参考。")
            sb.appendLine()
        }

        sb.appendLine("🏆 预测 Top-${topSectors.size} 热门板块：")
        sb.appendLine()
        for ((idx, s) in topSectors.withIndex()) {
            val medal = when (idx) { 0 -> "🥇"; 1 -> "🥈"; 2 -> "🥉"; else -> "${idx + 1}." }
            sb.appendLine("$medal ${s.sectorName} ${s.trend.symbol} 综合分${s.compositeScore.round0()}")
            sb.appendLine("   趋势: ${s.trend.label} | 置信度: ${(s.confidence * 100).round0()}%")
            sb.appendLine("   新闻热度${s.newsTrendScore.round0()} | 资金${s.capitalScore.round0()} | " +
                "动量${s.momentumScore.round0()} | 机构${s.instTipsScore.round0()} | " +
                "因子${s.newsFactorScore.round0()} | 轮动${s.rotationScore.round0()}")
            if (s.evidence.isNotEmpty()) {
                sb.appendLine("   证据: ${s.evidence.joinToString("；")}")
            }
            if (s.relatedStockCodes.isNotEmpty()) {
                sb.appendLine("   关联: ${s.relatedStockCodes.take(5).joinToString(",")}")
            }
            sb.appendLine()
        }

        // 综合判断
        val risingCount = topSectors.count { it.trend == TrendDirection.RISING || it.trend == TrendDirection.STABLE_RISING }
        val fallingCount = topSectors.count { it.trend == TrendDirection.FALLING }
        sb.appendLine("📊 综合判断：")
        when {
            risingCount >= 5 -> sb.appendLine("  $targetMonth 市场可能呈结构性行情，${risingCount}个板块趋势向上，建议重点布局。")
            risingCount >= 3 -> sb.appendLine("  $targetMonth 市场可能呈分化行情，${risingCount}个板块有结构性机会。")
            fallingCount >= 5 -> sb.appendLine("  $targetMonth 市场可能延续调整，多数板块降温，建议防御为主。")
            else -> sb.appendLine("  $targetMonth 市场可能呈震荡格局，板块轮动较快，短线操作为宜。")
        }

        if (aiMonthlySectors.isNotEmpty()) {
            val aiMatch = topSectors.count { f -> aiMonthlySectors.any { it.contains(f.sectorName) || f.sectorName.contains(it) } }
            sb.appendLine("  AI月度推荐匹配: $aiMatch/${aiMonthlySectors.size}")
        }

        return sb.toString()
    }

    private fun buildMarketContext(
        records: List<SectorDailyRecordEntity>,
        topSectors: List<SectorForecast>
    ): String {
        if (records.isEmpty()) return "无市场数据"

        val latestDate = records.maxByOrNull { it.date }?.date ?: "N/A"
        val totalSectors = records.filter { it.date == latestDate }.size
        val hotSectors = records.filter { it.date == latestDate && it.isHot in listOf("S", "A") }.size

        return "数据截至 $latestDate | 覆盖 $totalSectors 个板块 | 当日S/A级 $hotSectors 个"
    }

    private fun getCutoffDate(daysAgo: Int): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo)
        return DATE_FMT.format(cal.time)
    }

    // ── 数值格式化扩展 ──
    private fun Double.round0() = kotlin.math.round(this).toInt()
    private fun Double.round1() = kotlin.math.round(this * 10) / 10.0
    private fun Double.round2() = kotlin.math.round(this * 100) / 100.0
}
