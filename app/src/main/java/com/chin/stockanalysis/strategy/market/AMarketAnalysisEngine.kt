package com.chin.stockanalysis.strategy.market

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.HoldingPeriod
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ## A股大盤分析引擎（AMarketAnalysisEngine）
 *
 * 參考 agent-architecture-refactoring-plan.md §12.6 實現。
 * 六維分析：
 *   1. 趨勢與形態（3日不新低 / 均線粘合 / 逃頂信號）
 *   2. 量能與資金（放量/縮量/平量）
 *   3. 市場情緒與波動（漲跌比 → 沸騰/溫和/冰點）
 *   4. 權重股貢獻度（黃白線偏離，暫用大盤股 vs 小盤股漲幅差替代）
 *   5. 宏觀事件日曆（CPI/PPI 等，預留接口）
 *   6. 大盤狀態機（綜合決策 → 推薦交易週期 + 倉位建議）
 *
 * 數據來源：本地 daily_snapshot 表（sh000001 上證指數 + 全市場個股）
 */
object AMarketAnalysisEngine {

    private const val TAG = "AMarketAnalysis"
    private const val INDEX_CODE = "sh000001"

    private const val MA_SHORT = 5
    private const val MA_MID = 10
    private const val MA_LONG = 30
    private const val DISPERSION_THRESHOLD = 0.02

    // ═══════════════════════════════════════
    //  數據類
    // ═══════════════════════════════════════

    /** 大盤分析結果 */
    data class MarketAnalysisResult(
        val isBottomConfirmed: Boolean,
        val isTrendUp: Boolean,
        val isTopDanger: Boolean,
        val volumeStatus: String,
        val marketTemp: String,
        val suggestedPeriod: HoldingPeriod,
        val suggestedPositionPct: Int,
        val ma5: Double = 0.0,
        val ma10: Double = 0.0,
        val ma30: Double = 0.0,
        val dispersion: Double = 0.0,
        val indexClose: Double = 0.0,
        val indexChangePct: Double = 0.0,
        val advanceDeclineRatio: Double = 0.0,
        val summary: String
    )

    // ═══════════════════════════════════════
    //  主入口
    // ═══════════════════════════════════════

    /**
     * 執行大盤全面分析
     * @param ctx Android Context
     * @return MarketAnalysisResult
     */
    suspend fun analyze(ctx: Context): MarketAnalysisResult = withContext(Dispatchers.IO) {
        try {
            val db = StockDatabase.getInstance(ctx)
            val snapshots = db.dailySnapshotDao().getByCode(INDEX_CODE, 60)
                .sortedBy { it.date }

            if (snapshots.size < MA_LONG) {
                return@withContext MarketAnalysisResult(
                    isBottomConfirmed = false, isTrendUp = false, isTopDanger = false,
                    volumeStatus = "數據不足", marketTemp = "未知",
                    suggestedPeriod = HoldingPeriod.SHORT, suggestedPositionPct = 40,
                    summary = "上證指數歷史數據不足 ${MA_LONG} 天，默認短線"
                )
            }

            val latest = snapshots.last()
            val recent3 = snapshots.takeLast(3)
            val prev3 = if (snapshots.size >= 6) snapshots.takeLast(6).dropLast(3) else recent3

            // 1. 趨勢與形態
            val isBottom = checkBottomConfirmed(recent3, prev3)
            val (isTrend, ma5, ma10, ma30, dispersion) = checkMaTrend(snapshots)
            val isTop = checkTopDanger(recent3, latest)

            // 2. 量能
            val volumeStatus = checkVolumeStatus(snapshots)

            // 3. 市場情緒（漲跌比）
            val todayDate = latest.date
            val allToday = db.dailySnapshotDao().getByDate(todayDate)
            val (advCount, decCount) = countAdvanceDecline(allToday)
            val ratio = if (decCount > 0) advCount.toDouble() / decCount.toDouble() else if (advCount > 0) 99.0 else 1.0
            val marketTemp = checkMarketTemperature(ratio)

            // 4. 指數漲跌幅
            val prevClose = if (snapshots.size >= 2) snapshots[snapshots.size - 2].close else latest.open
            val changePct = (latest.close - prevClose) / prevClose * 100

            // 5. 狀態機決策
            val (period, posPct) = decidePeriod(isBottom, isTrend, isTop, volumeStatus, marketTemp)

            // 6. 摘要
            val summary = buildSummary(isBottom, isTrend, isTop, volumeStatus, marketTemp, period, posPct, ma5, ma10, ma30, dispersion, ratio)

            MarketAnalysisResult(
                isBottomConfirmed = isBottom,
                isTrendUp = isTrend,
                isTopDanger = isTop,
                volumeStatus = volumeStatus,
                marketTemp = marketTemp,
                suggestedPeriod = period,
                suggestedPositionPct = posPct,
                ma5 = ma5, ma10 = ma10, ma30 = ma30,
                dispersion = dispersion,
                indexClose = latest.close,
                indexChangePct = changePct,
                advanceDeclineRatio = ratio,
                summary = summary
            )
        } catch (e: Exception) {
            Log.e(TAG, "大盤分析失敗: ${e.message}", e)
            MarketAnalysisResult(
                isBottomConfirmed = false, isTrendUp = false, isTopDanger = false,
                volumeStatus = "異常", marketTemp = "未知",
                suggestedPeriod = HoldingPeriod.SHORT, suggestedPositionPct = 30,
                summary = "分析異常: ${e.message}"
            )
        }
    }

    // ═══════════════════════════════════════
    //  各檢測模塊
    // ═══════════════════════════════════════

    /** 3日不新低：最近3天收盤價最低 > 前3天收盤價最低 */
    private fun checkBottomConfirmed(recent3: List<DailySnapshotEntity>, prev3: List<DailySnapshotEntity>): Boolean {
        if (recent3.size < 3 || prev3.size < 3) return false
        val recentLow = recent3.minOf { it.close }
        val prevLow = prev3.minOf { it.close }
        return recentLow > prevLow
    }

    /** 均線粘合向上：離散率 < 2% 且 MA5 向上 */
    private fun checkMaTrend(history: List<DailySnapshotEntity>): MaTrendResult {
        val closes = history.map { it.close }
        val ma5 = closes.takeLast(MA_SHORT).average()
        val ma10 = closes.takeLast(MA_MID).average()
        val ma30 = closes.takeLast(MA_LONG).average()

        val prevCloses = closes.dropLast(1)
        val prevMa5 = if (prevCloses.size >= MA_SHORT) prevCloses.takeLast(MA_SHORT).average() else ma5

        val maxMa = maxOf(ma5, ma10, ma30)
        val minMa = minOf(ma5, ma10, ma30)
        val dispersion = if (ma30 > 0) (maxMa - minMa) / ma30 else 0.0
        val isMa5Up = ma5 > prevMa5
        val isTrend = dispersion < DISPERSION_THRESHOLD && isMa5Up && ma5 > ma10

        return MaTrendResult(isTrend, ma5, ma10, ma30, dispersion)
    }

    private data class MaTrendResult(val isTrend: Boolean, val ma5: Double, val ma10: Double, val ma30: Double, val dispersion: Double)

    /** 逃頂信號：3天急跌 > 5% 或收盤跌破3日最低 */
    private fun checkTopDanger(recent3: List<DailySnapshotEntity>, latest: DailySnapshotEntity): Boolean {
        if (recent3.size < 3) return false
        val firstClose = recent3.first().close
        val lastClose = recent3.last().close
        val dropPct = (firstClose - lastClose) / firstClose
        val isSharpDrop = dropPct > 0.05
        val recent3Low = recent3.minOf { it.low }
        val isBreakLow = latest.close < recent3Low
        return isSharpDrop || isBreakLow
    }

    /** 量能判定：今日量 vs 5日均量 */
    private fun checkVolumeStatus(history: List<DailySnapshotEntity>): String {
        if (history.size < 6) return "數據不足"
        val todayVol = history.last().volume.toDouble()
        val avgVol5 = history.takeLast(6).dropLast(1).map { it.volume.toDouble() }.average()
        return when {
            todayVol > avgVol5 * 1.5 -> "放量"
            todayVol < avgVol5 * 0.7 -> "縮量"
            else -> "平量"
        }
    }

    /** 漲跌家數統計 */
    private fun countAdvanceDecline(snapshots: List<DailySnapshotEntity>): Pair<Int, Int> {
        var adv = 0; var dec = 0
        for (s in snapshots) {
            if (s.close > s.open) adv++
            else if (s.close < s.open) dec++
        }
        return adv to dec
    }

    /** 市場溫度 */
    private fun checkMarketTemperature(ratio: Double): String = when {
        ratio > 3.0 -> "沸騰"
        ratio > 1.5 -> "溫和偏熱"
        ratio > 0.7 -> "溫和"
        ratio > 0.3 -> "溫和偏冷"
        else -> "冰點"
    }

    /** 狀態機決策：返回 (建議週期, 建議倉位%) */
    private fun decidePeriod(
        isBottom: Boolean, isTrend: Boolean, isTop: Boolean,
        volumeStatus: String, marketTemp: String
    ): Pair<HoldingPeriod, Int> {
        // 優先級1：系統性風險
        if (isTop && volumeStatus == "放量") return HoldingPeriod.ULTRA_SHORT to 10
        if (marketTemp == "冰點") return HoldingPeriod.ULTRA_SHORT to 10

        // 優先級2：趨勢向上
        if (isTrend && volumeStatus in listOf("放量", "平量") && marketTemp in listOf("溫和", "溫和偏熱"))
            return HoldingPeriod.LONG to 70

        // 優先級3：觸底回升
        if (isBottom && marketTemp in listOf("冰點", "溫和偏冷"))
            return HoldingPeriod.MID to 50

        // 優先級4：震盪
        if (!isTop && !isBottom && volumeStatus == "平量")
            return HoldingPeriod.SHORT to 40

        return HoldingPeriod.SHORT to 30
    }

    /** 構建中文摘要 */
    private fun buildSummary(
        isBottom: Boolean, isTrend: Boolean, isTop: Boolean,
        volumeStatus: String, marketTemp: String,
        period: HoldingPeriod, posPct: Int,
        ma5: Double, ma10: Double, ma30: Double,
        dispersion: Double, adRatio: Double
    ): String = buildString {
        append("上證")
        if (isTrend) append(" | 均線多頭粘合向上(離散${"%.2f".format(dispersion * 100)}%)")
        if (isBottom) append(" | 底部確認(3日不新低)")
        if (isTop) append(" | ⚠逃頂信號")
        append(" | $marketTemp(漲跌比${"%.1f".format(adRatio)})")
        append(" | $volumeStatus")
        append(" | MA5/10/30=${"%.0f".format(ma5)}/${"%.0f".format(ma10)}/${"%.0f".format(ma30)}")
        append(" | 建議: ${period.label}(倉位${posPct}%)")
    }
}
