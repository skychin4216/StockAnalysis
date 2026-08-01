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
//  SectorTrendForecaster — 月度熱點前瞻引擎
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 月度熱點前瞻引擎
 *
 * 融合多源信號預測未來一個月的熱門板塊方向，填補現有框架僅能預測次日板塊的空白。
 *
 * ### 與現有板塊分析模塊的區別
 * - `SectorRotationEngine.predictTomorrow()` — 純量化，僅預測**次日**板塊（動量+資金）
 * - `AIHotSectorProvider.monthlySectors` — AI 查詢**當前**月度熱門（描述性，非預測）
 * - `NewsStrengthNode` — 僅分析**當日**新聞情緒，無趨勢聚合
 * - **本引擎** — 聚合 30 天多源數據，預測**下個月**熱門方向（前瞻性）
 *
 * ### 五維評分模型
 * | 維度 | 權重 | 數據源 | 說明 |
 * |------|------|--------|------|
 * | 新聞熱度趨勢 | 25% | daily_news_hot_picks | 30天內板塊上榜次數 + 趨勢方向 |
 * | 資金持續性 | 22% | sector_daily_record | 連續淨流入天數 + 流入金額穩定性 |
 * | 動量加速 | 18% | sector_daily_record | 20日均幅 vs 5日均幅，檢測加速/減速 |
 * | 機構線索熱度 | 20% | institutional_tips | 用戶輸入的機構推薦板塊聚集度 |
 * | 新聞因子質量 | 10% | news_factors | 板塊相關新聞數 × 平均影響強度 |
 * | 輪動預測一致性 | 5% | SectorRotationEngine | 近期 predictTomorrow 中持續出現 |
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

        // 評分權重
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
     * 單個板塊的月度前瞻預測結果
     */
    data class SectorForecast(
        val sectorName: String,
        val sectorCode: String,
        /** 綜合趨勢分數 0-100，越高越看好 */
        val compositeScore: Double,
        /** 趨勢方向 */
        val trend: TrendDirection,
        /** 置信度 0-1 */
        val confidence: Double,
        // 各維度得分
        val newsTrendScore: Double,
        val capitalScore: Double,
        val momentumScore: Double,
        val instTipsScore: Double,
        val newsFactorScore: Double,
        val rotationScore: Double,
        /** 支撐證據摘要 */
        val evidence: List<String>,
        /** 相關股票代碼（如有） */
        val relatedStockCodes: List<String>
    )

    /**
     * 趨勢方向
     */
    enum class TrendDirection(val symbol: String, val label: String) {
        RISING("↑↑", "加速上升"),
        STABLE_RISING("↑", "穩步上升"),
        STABLE("→", "橫盤震盪"),
        FALLING("↓", "降溫回落"),
        UNKNOWN("?", "數據不足")
    }

    /**
     * 月度前瞻報告
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
     * 執行月度熱點前瞻預測
     *
     * @return 月度前瞻報告，含 Top-K 板塊預測
     */
    suspend fun forecast(): MonthlyForecastReport = withContext(Dispatchers.IO) {
        Log.i(TAG, "▶ 開始月度熱點前瞻預測...")
        val db = StockDatabase.getInstance(context)
        val sdf = SimpleDateFormat("yyyy-MM", Locale.getDefault())
        val cal = Calendar.getInstance()
        val currentMonth = sdf.format(cal.time)
        cal.add(Calendar.MONTH, 1)
        val targetMonth = sdf.format(cal.time)
        val today = DATE_FMT.format(Date())

        // ══ 1. 採集多源數據 ══
        val sectorRecords = collectSectorRecords(db)
        val newsHotPicks = collectNewsHotPicks(db)
        val newsFactors = collectNewsFactors(db)
        val rotationPredictions = collectRotationPredictions(db)
        val instTips = collectInstitutionalTips(db)
        val aiMonthlySectors = try {
            AIHotSectorProvider.getHotSectors(context).monthlySectors
        } catch (e: Exception) {
            Log.w(TAG, "AI 月度板塊查詢失敗: ${e.message}")
            emptyList()
        }

        Log.i(TAG, "  數據採集: sector_records=${sectorRecords.size}, " +
            "news_hot_picks=${newsHotPicks.size}, " +
            "news_factors=${newsFactors.size}, " +
            "inst_tips=${instTips.size}, " +
            "rotation_preds=${rotationPredictions.size}, " +
            "ai_monthly=${aiMonthlySectors.size}")

        // ══ 2. 匯總所有板塊名稱 ══
        val allSectorNames = mutableSetOf<String>()
        allSectorNames.addAll(sectorRecords.map { it.sectorName })
        allSectorNames.addAll(newsHotPicks.map { it.sectorName })
        allSectorNames.addAll(newsFactors.mapNotNull { it.sector.takeIf { s -> s.isNotEmpty() } })
        allSectorNames.addAll(instTips.mapNotNull { it.sector.takeIf { s -> s.isNotEmpty() } })
        allSectorNames.addAll(aiMonthlySectors)

        if (allSectorNames.isEmpty()) {
            Log.w(TAG, "⚠ 無可用板塊數據，返回空報告")
            return@withContext MonthlyForecastReport(
                targetMonth = targetMonth,
                generatedDate = today,
                sectors = emptyList(),
                marketContext = "數據不足",
                dataCoverage = "0 天",
                narrative = "當前無足夠的板塊數據進行月度前瞻預測。建議先運行量化選股累積數據。"
            )
        }

        // ══ 3. 逐板塊計算五維評分 ══
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

        // ══ 5. 生成敘述 ══
        val dataDays = sectorRecords.map { it.date }.distinct().size
        val narrative = buildNarrative(topSectors, targetMonth, dataDays, aiMonthlySectors)
        val marketContext = buildMarketContext(sectorRecords, topSectors)

        Log.i(TAG, "✅ 月度前瞻完成: ${topSectors.size} 個板塊, 目標月=$targetMonth, 數據覆蓋=$dataDays 天")

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
    //  數據採集
    // ══════════════════════════════════════════════════════════════════════════

    private suspend fun collectSectorRecords(db: StockDatabase): List<SectorDailyRecordEntity> {
        return try {
            db.sectorDailyRecordDao().getRecentDays(LOOKBACK_DAYS)
        } catch (e: Exception) {
            Log.w(TAG, "採集 sector_daily_record 失敗: ${e.message}")
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
            Log.w(TAG, "採集 daily_news_hot_picks 失敗: ${e.message}")
            emptyList()
        }
    }

    private suspend fun collectNewsFactors(db: StockDatabase): List<NewsFactorEntity> {
        return try {
            val cutoff = getCutoffDate(LOOKBACK_DAYS)
            db.newsFactorDao().getActiveByDateRange(cutoff, DATE_FMT.format(Date()))
        } catch (e: Exception) {
            Log.w(TAG, "採集 news_factors 失敗: ${e.message}")
            emptyList()
        }
    }

    private suspend fun collectRotationPredictions(db: StockDatabase): List<String> {
        return try {
            val engine = SectorRotationEngine(context)
            engine.predictTomorrow(10).map { it.sectorName }
        } catch (e: Exception) {
            Log.w(TAG, "採集 rotation predictions 失敗: ${e.message}")
            emptyList()
        }
    }

    /**
     * 採集機構線索（用戶在 AI 對話框輸入的機構推薦）
     * 取最近 30 天內未過期的所有線索
     */
    private suspend fun collectInstitutionalTips(db: StockDatabase): List<InstitutionalTipEntity> {
        return try {
            val today = DATE_FMT.format(Date())
            db.institutionalTipDao().getActiveTips(today)
        } catch (e: Exception) {
            Log.w(TAG, "採集 institutional_tips 失敗: ${e.message}")
            emptyList()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  五維評分
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

        // ── 維度 1: 新聞熱度趨勢 (0-100) ──
        val newsTrendScore = scoreNewsTrend(newsHotPicks, evidence)

        // ── 維度 2: 資金持續性 (0-100) ──
        val capitalScore = scoreCapitalPersistence(sectorRecords, evidence)

        // ── 維度 3: 動量加速 (0-100) ──
        val momentumScore = scoreMomentumDivergence(sectorRecords, evidence)

        // ── 維度 4: 機構線索熱度 (0-100) ──
        val instTipsScore = scoreInstitutionalTips(instTips, evidence)

        // ── 維度 5: 新聞因子質量 (0-100) ──
        val newsFactorScore = scoreNewsFactorQuality(newsFactors, evidence)

        // ── 維度 6: 輪動預測一致性 (0-100) ──
        val rotationScore = scoreRotationConsistency(sectorName, rotationPredictions, aiMonthlySectors, evidence)

        // ── 綜合評分 ──
        val composite = newsTrendScore * W_NEWS_TREND +
            capitalScore * W_CAPITAL +
            momentumScore * W_MOMENTUM +
            instTipsScore * W_INST_TIPS +
            newsFactorScore * W_NEWS_FACTOR +
            rotationScore * W_ROTATION

        // ── 趨勢方向判定 ──
        val trend = determineTrend(momentumScore, capitalScore, newsTrendScore)

        // ── 置信度 ──
        val dataRichness = listOf(sectorRecords.size, newsHotPicks.size, newsFactors.size, instTips.size).count { it > 0 }
        val confidence = (composite / 100.0) * (0.5 + dataRichness * 0.12)

        // ── 相關股票代碼 ──
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
     * 維度 1: 新聞熱度趨勢
     * - 上榜次數 × 平均熱度
     * - 近期 vs 早期對比，檢測上升/下降趨勢
     */
    private fun scoreNewsTrend(
        picks: List<DailyNewsHotPickEntity>,
        evidence: MutableList<String>
    ): Double {
        if (picks.isEmpty()) return 20.0 // 無新聞不一定是壞事，給基礎分

        val totalCount = picks.size
        val avgHotScore = picks.map { it.hotScore }.average()
        val baseScore = (totalCount.toDouble() / LOOKBACK_DAYS * 100).coerceAtMost(50.0) +
            (avgHotScore / 100.0 * 30.0)

        // 趨勢方向：近期10天 vs 早期10天
        val sorted = picks.sortedBy { it.newsDate }
        val recentCount = sorted.takeLastWhile { it.newsDate >= getCutoffDate(10) }.size
        val earlyCount = sorted.takeWhile { it.newsDate < getCutoffDate(10) }.size

        val trendBonus = when {
            recentCount > earlyCount && earlyCount > 0 -> {
                evidence.add("新聞熱度上升（早期${earlyCount}次→近期${recentCount}次）")
                20.0
            }
            recentCount > 0 && earlyCount == 0 -> {
                evidence.add("新晉熱點（近10天上榜${recentCount}次）")
                15.0
            }
            recentCount == earlyCount && recentCount > 0 -> {
                evidence.add("新聞熱度穩定（${totalCount}次上榜）")
                5.0
            }
            recentCount < earlyCount && earlyCount > 0 -> {
                evidence.add("新聞熱度降溫（早期${earlyCount}次→近期${recentCount}次）")
                -10.0
            }
            else -> 0.0
        }

        return (baseScore + trendBonus).coerceIn(0.0, 100.0)
    }

    /**
     * 維度 2: 資金持續性
     * - 連續淨流入天數
     * - 流入金額穩定性（標準差越小越好）
     */
    private fun scoreCapitalPersistence(
        records: List<SectorDailyRecordEntity>,
        evidence: MutableList<String>
    ): Double {
        if (records.size < MIN_DATA_DAYS) {
            evidence.add("資金數據不足（僅${records.size}天）")
            return 25.0
        }

        val sorted = records.sortedBy { it.date }
        val inflows = sorted.map { it.mainNetInflow }
        val positiveDays = inflows.count { it > 0 }
        val positiveRatio = positiveDays.toDouble() / inflows.size

        // 連續流入
        var maxConsecutive = 0
        var current = 0
        for (inf in inflows) {
            if (inf > 0) { current++; maxConsecutive = maxOf(maxConsecutive, current) }
            else current = 0
        }

        // 穩定性：標準差 / 均值（變異係數），越小越穩定
        val avgInflow = inflows.average()
        val variance = inflows.map { (it - avgInflow).pow(2.0) }.average()
        val stdDev = sqrt(variance)
        val cv = if (avgInflow > 0) stdDev / avgInflow else 1.0
        val stabilityScore = (1.0 - cv.coerceIn(0.0, 1.0)) * 100

        val score = positiveRatio * 50 + (maxConsecutive.toDouble() / records.size * 100).coerceAtMost(30.0) + stabilityScore * 0.2

        when {
            maxConsecutive >= 10 -> evidence.add("連續${maxConsecutive}天資金淨流入（強勢）")
            maxConsecutive >= 5 -> evidence.add("連續${maxConsecutive}天資金淨流入")
            positiveRatio > 0.6 -> evidence.add("資金淨流入占比${(positiveRatio * 100).round0()}%")
            positiveRatio < 0.3 -> evidence.add("資金淨流出占比${((1 - positiveRatio) * 100).round0()}%（弱勢）")
        }

        return score.coerceIn(0.0, 100.0)
    }

    /**
     * 維度 3: 動量加速
     * - 20日均幅 vs 5日均幅，檢測加速/減速
     * - 類似 MACD 的快慢線概念
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

        // 動量差（加速因子）
        val momentumDiff = recent5Avg - recent20Avg

        // 基礎分：20日均幅 × 5
        val baseScore = (recent20Avg * 5).coerceIn(-30.0, 40.0) + 40

        // 加速/減速加成
        val accelBonus = when {
            momentumDiff > 2.0 -> {
                evidence.add("動量加速（5日均幅${recent5Avg.round2()}% > 20日均幅${recent20Avg.round2()}%）")
                25.0
            }
            momentumDiff > 0.5 -> {
                evidence.add("動量溫和加速")
                10.0
            }
            momentumDiff > -0.5 -> {
                0.0
            }
            momentumDiff > -2.0 -> {
                evidence.add("動量溫和減速")
                -10.0
            }
            else -> {
                evidence.add("動量明顯減速（5日均幅${recent5Avg.round2()}% < 20日均幅${recent20Avg.round2()}%）")
                -25.0
            }
        }

        // 熱度等級加分
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
     * 維度 4: 機構線索熱度
     * - 用戶在 AI 對話框輸入的機構推薦中，與本板塊相關的線索數量
     * - 線索類型加權：target(目標價) > rating(評級) > research(研報)
     * - 多個機構推薦同一板塊 = 板塊共識度上升
     */
    private fun scoreInstitutionalTips(
        tips: List<InstitutionalTipEntity>,
        evidence: MutableList<String>
    ): Double {
        if (tips.isEmpty()) return 20.0 // 無機構線索不扣分，給基礎分

        val totalTips = tips.size
        val uniqueStocks = tips.map { it.stockCode }.distinct().size

        // 線索類型加權
        val typeWeight = tips.sumOf { tip ->
            when (tip.tipType) {
                "target" -> 3.0  // 目標價最有價值
                "rating" -> 2.0  // 評級次之
                else -> 1.0      // 研報基礎
            }
        }

        // 基礎分：線索數量（上限50）+ 涉及股票數（上限20）+ 類型加權（上限20）
        val baseScore = (totalTips.toDouble() / 10 * 50).coerceAtMost(50.0) +
            (uniqueStocks.toDouble() / 5 * 20).coerceAtMost(20.0) +
            (typeWeight / 10 * 20).coerceAtMost(20.0)

        // 聚集度加成：多個機構推薦同一板塊 = 共識
        val consensusBonus = when {
            uniqueStocks >= 5 -> {
                evidence.add("機構共識度高（${uniqueStocks}隻股票被推薦）")
                15.0
            }
            uniqueStocks >= 3 -> {
                evidence.add("機構推薦聚集（${uniqueStocks}隻股票）")
                8.0
            }
            uniqueStocks >= 1 -> {
                evidence.add("機構線索${totalTips}條（涉及${uniqueStocks}隻股票）")
                0.0
            }
            else -> 0.0
        }

        return (baseScore + consensusBonus).coerceIn(0.0, 100.0)
    }

    /**
     * 維度 4: 新聞因子質量
     * - 板塊相關新聞數量 × 平均影響強度 × 情緒方向
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

        // 基礎分：新聞數量（上限40）+ 平均影響強度（上限30）
        val baseScore = (count.toDouble() / 50 * 40).coerceAtMost(40.0) + (avgImpact / 100.0 * 30.0)

        // 情緒加成
        val sentimentBonus = when {
            sentimentRatio > 0.7 -> {
                evidence.add("新聞情緒強烈利好（利好${bullishCount}條/利空${bearishCount}條）")
                25.0
            }
            sentimentRatio > 0.5 -> {
                evidence.add("新聞情緒偏利好")
                10.0
            }
            sentimentRatio < 0.3 -> {
                evidence.add("新聞情緒偏利空（利好${bullishCount}/利空${bearishCount}）")
                -15.0
            }
            else -> 0.0
        }

        return (baseScore + sentimentBonus).coerceIn(0.0, 100.0)
    }

    /**
     * 維度 5: 輪動預測一致性
     * - SectorRotationEngine.predictTomorrow 中是否持續出現
     * - AI 月度板塊中是否出現
     */
    private fun scoreRotationConsistency(
        sectorName: String,
        rotationPredictions: List<String>,
        aiMonthlySectors: List<String>,
        evidence: MutableList<String>
    ): Double {
        var score = 30.0 // 基礎分

        // 出現在日常輪動預測中
        if (rotationPredictions.any { it.contains(sectorName) || sectorName.contains(it) }) {
            score += 30.0
            evidence.add("入選次日輪動預測")
        }

        // 出現在 AI 月度板塊中
        if (aiMonthlySectors.any { it.contains(sectorName) || sectorName.contains(it) }) {
            score += 40.0
            evidence.add("AI月度板塊推薦")
        }

        return score.coerceIn(0.0, 100.0)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  輔助方法
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
            return "數據不足，無法生成 $targetMonth 月度前瞻報告。建議先運行量化選股累積至少 10 天數據。"
        }

        val sb = StringBuilder()
        sb.appendLine("📅 $targetMonth 月度熱點前瞻報告")
        sb.appendLine("━".repeat(42))
        sb.appendLine()

        if (dataDays < MIN_DATA_DAYS) {
            sb.appendLine("⚠ 注意：目前僅有 $dataDays 天數據，預測置信度較低。")
            sb.appendLine("   建議累積 15+ 天數據後再參考。")
            sb.appendLine()
        }

        sb.appendLine("🏆 預測 Top-${topSectors.size} 熱門板塊：")
        sb.appendLine()
        for ((idx, s) in topSectors.withIndex()) {
            val medal = when (idx) { 0 -> "🥇"; 1 -> "🥈"; 2 -> "🥉"; else -> "${idx + 1}." }
            sb.appendLine("$medal ${s.sectorName} ${s.trend.symbol} 綜合分${s.compositeScore.round0()}")
            sb.appendLine("   趨勢: ${s.trend.label} | 置信度: ${(s.confidence * 100).round0()}%")
            sb.appendLine("   新聞熱度${s.newsTrendScore.round0()} | 資金${s.capitalScore.round0()} | " +
                "動量${s.momentumScore.round0()} | 機構${s.instTipsScore.round0()} | " +
                "因子${s.newsFactorScore.round0()} | 輪動${s.rotationScore.round0()}")
            if (s.evidence.isNotEmpty()) {
                sb.appendLine("   證據: ${s.evidence.joinToString("；")}")
            }
            if (s.relatedStockCodes.isNotEmpty()) {
                sb.appendLine("   關聯: ${s.relatedStockCodes.take(5).joinToString(",")}")
            }
            sb.appendLine()
        }

        // 綜合判斷
        val risingCount = topSectors.count { it.trend == TrendDirection.RISING || it.trend == TrendDirection.STABLE_RISING }
        val fallingCount = topSectors.count { it.trend == TrendDirection.FALLING }
        sb.appendLine("📊 綜合判斷：")
        when {
            risingCount >= 5 -> sb.appendLine("  $targetMonth 市場可能呈結構性行情，${risingCount}個板塊趨勢向上，建議重點佈局。")
            risingCount >= 3 -> sb.appendLine("  $targetMonth 市場可能呈分化行情，${risingCount}個板塊有結構性機會。")
            fallingCount >= 5 -> sb.appendLine("  $targetMonth 市場可能延續調整，多數板塊降溫，建議防禦為主。")
            else -> sb.appendLine("  $targetMonth 市場可能呈震盪格局，板塊輪動較快，短線操作為宜。")
        }

        if (aiMonthlySectors.isNotEmpty()) {
            val aiMatch = topSectors.count { f -> aiMonthlySectors.any { it.contains(f.sectorName) || f.sectorName.contains(it) } }
            sb.appendLine("  AI月度推薦匹配: $aiMatch/${aiMonthlySectors.size}")
        }

        return sb.toString()
    }

    private fun buildMarketContext(
        records: List<SectorDailyRecordEntity>,
        topSectors: List<SectorForecast>
    ): String {
        if (records.isEmpty()) return "無市場數據"

        val latestDate = records.maxByOrNull { it.date }?.date ?: "N/A"
        val totalSectors = records.filter { it.date == latestDate }.size
        val hotSectors = records.filter { it.date == latestDate && it.isHot in listOf("S", "A") }.size

        return "數據截至 $latestDate | 覆蓋 $totalSectors 個板塊 | 當日S/A級 $hotSectors 個"
    }

    private fun getCutoffDate(daysAgo: Int): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo)
        return DATE_FMT.format(cal.time)
    }

    // ── 數值格式化擴展 ──
    private fun Double.round0() = kotlin.math.round(this).toInt()
    private fun Double.round1() = kotlin.math.round(this * 10) / 10.0
    private fun Double.round2() = kotlin.math.round(this * 100) / 100.0
}
