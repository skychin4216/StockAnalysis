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
 * ## A股大盘分析引擎（AMarketAnalysisEngine）
 *
 * 参考 agent-architecture-refactoring-plan.md §12.6 实现。
 * 六维分析：
 *   1. 趋势与形态（3日不新低 / 均线粘合 / 逃顶信号）
 *   2. 量能与资金（放量/缩量/平量）
 *   3. 市场情绪与波动（涨跌比 → 沸腾/温和/冰点）
 *   4. 权重股贡献度（黄白线偏离，暂用大盘股 vs 小盘股涨幅差替代）
 *   5. 宏观事件日历（CPI/PPI 等，预留接口）
 *   6. 大盘状态机（综合决策 → 推荐交易周期 + 仓位建议）
 *
 * 数据来源：本地 daily_snapshot 表（sh000001 上证指数 + 全市场个股）
 */
object AMarketAnalysisEngine {

    private const val TAG = "AMarketAnalysis"
    private const val INDEX_CODE = "sh000001"

    private const val MA_SHORT = 5
    private const val MA_MID = 10
    private const val MA_LONG = 30
    private const val DISPERSION_THRESHOLD = 0.02

    // ═══════════════════════════════════════
    //  数据类
    // ═══════════════════════════════════════

    /** 大盘分析结果 */
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
        val advanceCount: Int = 0,     // 全市场上涨家数（沸点/冰点判据）
        val declineCount: Int = 0,     // 全市场下跌家数
        // ── v2 新增：连涨/连跌预警 ──
        val consecutiveUpDays: Int = 0,
        val consecutiveDownDays: Int = 0,
        val streakRiskLevel: String = "LOW",    // LOW / MEDIUM / HIGH / EXTREME
        val volumePriceDivergence: Boolean = false,  // 量价背离（涨但缩量）
        val summary: String
    )

    // ═══════════════════════════════════════
    //  主入口
    // ═══════════════════════════════════════

    /**
     * 执行大盘全面分析
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
                    volumeStatus = "数据不足", marketTemp = "未知",
                    suggestedPeriod = HoldingPeriod.SHORT, suggestedPositionPct = 40,
                    summary = "上证指数历史数据不足 ${MA_LONG} 天，默认短线"
                )
            }

            val latest = snapshots.last()
            val recent3 = snapshots.takeLast(3)
            val prev3 = if (snapshots.size >= 6) snapshots.takeLast(6).dropLast(3) else recent3

            // 1. 趋势与形态
            val isBottom = checkBottomConfirmed(recent3, prev3)
            val (isTrend, ma5, ma10, ma30, dispersion) = checkMaTrend(snapshots)
            val isTop = checkTopDanger(recent3, latest)

            // 2. 量能
            val volumeStatus = checkVolumeStatus(snapshots)

            // 3. 市场情绪（涨跌家数 + 涨跌比）
            val todayDate = latest.date
            val allToday = db.dailySnapshotDao().getByDate(todayDate)
            val (advCount, decCount) = countAdvanceDecline(allToday)
            val ratio = if (decCount > 0) advCount.toDouble() / decCount.toDouble() else if (advCount > 0) 99.0 else 1.0
            val marketTemp = checkMarketTemperature(ratio, advCount, decCount)

            // 4. 指数涨跌幅
            val prevClose = if (snapshots.size >= 2) snapshots[snapshots.size - 2].close else latest.open
            val changePct = (latest.close - prevClose) / prevClose * 100

            // 5. 连涨/连跌天数 + 量价背离
            val (consecUp, consecDown) = countConsecutiveDays(snapshots)
            val vpDivergence = checkVolumePriceDivergence(snapshots)
            val streakRisk = assessStreakRisk(consecUp, consecDown, vpDivergence, marketTemp)

            // 6. 状态机决策（含连涨预警 + 沸腾极值）
            val (period, posPct) = decidePeriod(
                isBottom, isTrend, isTop, volumeStatus, marketTemp,
                streakRisk, vpDivergence
            )

            // 7. 摘要
            val summary = buildSummary(
                isBottom, isTrend, isTop, volumeStatus, marketTemp, period, posPct,
                ma5, ma10, ma30, dispersion, ratio,
                advCount, decCount,
                consecUp, consecDown, streakRisk, vpDivergence
            )

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
                advanceCount = advCount,
                declineCount = decCount,
                consecutiveUpDays = consecUp,
                consecutiveDownDays = consecDown,
                streakRiskLevel = streakRisk,
                volumePriceDivergence = vpDivergence,
                summary = summary
            )
        } catch (e: Exception) {
            Log.e(TAG, "大盘分析失败: ${e.message}", e)
            MarketAnalysisResult(
                isBottomConfirmed = false, isTrendUp = false, isTopDanger = false,
                volumeStatus = "异常", marketTemp = "未知",
                suggestedPeriod = HoldingPeriod.SHORT, suggestedPositionPct = 30,
                summary = "分析异常: ${e.message}"
            )
        }
    }

    // ═══════════════════════════════════════
    //  各检测模块
    // ═══════════════════════════════════════

    /** 3日不新低：最近3天收盘价最低 > 前3天收盘价最低 */
    private fun checkBottomConfirmed(recent3: List<DailySnapshotEntity>, prev3: List<DailySnapshotEntity>): Boolean {
        if (recent3.size < 3 || prev3.size < 3) return false
        val recentLow = recent3.minOf { it.close }
        val prevLow = prev3.minOf { it.close }
        return recentLow > prevLow
    }

    /** 均线粘合向上：离散率 < 2% 且 MA5 向上 */
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

    /** 逃顶信号：3天急跌 > 5% 或收盘跌破3日最低 */
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
        if (history.size < 6) return "数据不足"
        val todayVol = history.last().volume.toDouble()
        val avgVol5 = history.takeLast(6).dropLast(1).map { it.volume.toDouble() }.average()
        return when {
            todayVol > avgVol5 * 1.5 -> "放量"
            todayVol < avgVol5 * 0.7 -> "缩量"
            else -> "平量"
        }
    }

    /**
     * 连涨/连跌天数统计
     * 从最新一天向前回溯，统计连续上涨或下跌的天数。
     */
    private fun countConsecutiveDays(history: List<DailySnapshotEntity>): Pair<Int, Int> {
        if (history.size < 2) return 0 to 0
        var upDays = 0
        var downDays = 0
        for (i in history.size - 1 downTo 1) {
            val changePct = (history[i].close - history[i - 1].close) / history[i - 1].close * 100
            // 方向反转检查必须前置：已确定连涨/连跌方向后，若出现反向走势（含平盘）
            // 立即中断，避免把反向的第一天误计入（原实现会先 ++ 再 break 导致误计数）
            if (upDays > 0 && changePct <= 0) break
            if (downDays > 0 && changePct >= 0) break
            when {
                changePct > 0 -> upDays++
                changePct < 0 -> downDays++
                else -> break  // 首个交易日即平盘则中断
            }
        }
        // 连涨与连跌天然互斥
        return if (upDays > 0) upDays to 0 else 0 to downDays
    }

    /**
     * 量价背离检测
     *
     * 检测"指数上涨但量能持续萎缩"的经典见顶信号：
     * - 近3天指数累计涨幅 > 1%
     * - 但近3天平均量能 < 前5天平均量能的 80%
     *
     * @return true 表示存在量价背离（涨但缩量），回调风险增大
     */
    private fun checkVolumePriceDivergence(history: List<DailySnapshotEntity>): Boolean {
        if (history.size < 9) return false
        val recent3 = history.takeLast(3)
        val priceChange = (recent3.last().close - recent3.first().open) / recent3.first().open * 100
        val recent3Vol = recent3.map { it.volume.toDouble() }.average()
        val prev5Vol = history.takeLast(8).dropLast(3).map { it.volume.toDouble() }.average()
        // 上涨但缩量：价格涨了但量能萎缩到前5日的80%以下
        return priceChange > 1.0 && prev5Vol > 0 && recent3Vol < prev5Vol * 0.8
    }

    /**
     * 连涨风险评估
     *
     * 根据 A 股历史统计规律：
     * - 连涨 ≤3天：正常震荡，LOW
     * - 连涨 4-5天：回调概率~50%，MEDIUM
     * - 连涨 6-7天：回调概率~65%，HIGH
     * - 连涨 ≥8天：回调概率>75%，EXTREME
     * - 如果同时存在量价背离，风险等级上调一档
     * - 如果市场"沸腾"，风险等级上调一档
     */
    private fun assessStreakRisk(
        consecUp: Int, consecDown: Int,
        vpDivergence: Boolean, marketTemp: String
    ): String {
        var level = when {
            consecUp >= 8 -> "EXTREME"
            consecUp >= 6 -> "HIGH"
            consecUp >= 4 -> "MEDIUM"
            consecDown >= 5 -> "LOW"  // 连跌5天反而可能是机会
            else -> "LOW"
        }
        // 量价背离 → 上调一档
        if (vpDivergence && level == "LOW") level = "MEDIUM"
        if (vpDivergence && level == "MEDIUM") level = "HIGH"
        // 沸腾 → 上调一档
        if (marketTemp == "沸腾" && level == "LOW") level = "MEDIUM"
        if (marketTemp == "沸腾" && level == "MEDIUM") level = "HIGH"
        if (marketTemp == "沸腾" && level == "HIGH") level = "EXTREME"
        return level
    }

    /** 涨跌家数统计 */
    private fun countAdvanceDecline(snapshots: List<DailySnapshotEntity>): Pair<Int, Int> {
        var adv = 0; var dec = 0
        for (s in snapshots) {
            if (s.close > s.open) adv++
            else if (s.close < s.open) dec++
        }
        return adv to dec
    }

    /**
     * 市场温度（沸点/冰点判断）
     *
     * 以**绝对上涨家数**为主判据（A股全市场约 5400 只）：
     * - 上涨 >= 4000 家 → 沸点（过热，不追高）
     * - 上涨 <= 1000 家 → 冰点（恐慌，可低吸）
     * 其余区间按涨跌比细分；全市场快照缺失（涨跌家数合计=0）时退回涨跌比。
     */
    private fun checkMarketTemperature(ratio: Double, advCount: Int = 0, decCount: Int = 0): String = when {
        advCount + decCount > 0 && advCount >= 4000 -> "沸腾"
        advCount + decCount > 0 && advCount <= 1000 -> "冰点"
        ratio > 3.0 -> "沸腾"
        ratio > 1.5 -> "温和偏热"
        ratio > 0.7 -> "温和"
        ratio > 0.3 -> "温和偏冷"
        else -> "冰点"
    }

    /** 状态机决策：返回 (建议周期, 建议仓位%) */
    private fun decidePeriod(
        isBottom: Boolean, isTrend: Boolean, isTop: Boolean,
        volumeStatus: String, marketTemp: String,
        streakRisk: String = "LOW", vpDivergence: Boolean = false
    ): Pair<HoldingPeriod, Int> {
        // 优先级0：连涨极值 + 量价背离 → 强制降仓
        if (streakRisk == "EXTREME") return HoldingPeriod.ULTRA_SHORT to 10
        if (streakRisk == "HIGH" && vpDivergence) return HoldingPeriod.ULTRA_SHORT to 15

        // 优先级1：系统性风险
        if (isTop && volumeStatus == "放量") return HoldingPeriod.ULTRA_SHORT to 10
        if (marketTemp == "冰点") return HoldingPeriod.ULTRA_SHORT to 10
        // 沸腾极值：即使趋势向好也要控制仓位
        if (marketTemp == "沸腾" && streakRisk != "LOW") return HoldingPeriod.SHORT to 25

        // 优先级2：趋势向上（但连涨中等风险时降一档）
        if (isTrend && volumeStatus in listOf("放量", "平量") && marketTemp in listOf("温和", "温和偏热")) {
            val posPct = if (streakRisk == "MEDIUM") 50 else if (streakRisk == "HIGH") 35 else 70
            return HoldingPeriod.LONG to posPct
        }

        // 优先级3：触底回升
        if (isBottom && marketTemp in listOf("冰点", "温和偏冷"))
            return HoldingPeriod.MID to 50

        // 优先级4：震荡
        if (!isTop && !isBottom && volumeStatus == "平量")
            return HoldingPeriod.SHORT to 40

        return HoldingPeriod.SHORT to 30
    }

    /** 构建中文摘要 */
    private fun buildSummary(
        isBottom: Boolean, isTrend: Boolean, isTop: Boolean,
        volumeStatus: String, marketTemp: String,
        period: HoldingPeriod, posPct: Int,
        ma5: Double, ma10: Double, ma30: Double,
        dispersion: Double, adRatio: Double,
        advCount: Int = 0, decCount: Int = 0,
        consecUp: Int = 0, consecDown: Int = 0,
        streakRisk: String = "LOW", vpDivergence: Boolean = false
    ): String = buildString {
        append("上证")
        if (isTrend) append(" | 均线多头粘合向上(离散${"%.2f".format(dispersion * 100)}%)")
        if (isBottom) append(" | 底部确认(3日不新低)")
        if (isTop) append(" | ⚠逃顶信号")
        append(" | $marketTemp(涨${advCount}家/跌${decCount}家, 比${"%.1f".format(adRatio)})")
        append(" | $volumeStatus")
        // 连涨/连跌预警
        if (consecUp > 0) append(" | 连涨${consecUp}天")
        if (consecDown > 0) append(" | 连跌${consecDown}天")
        if (streakRisk != "LOW") append(" | ⚠连涨风险${streakRisk}")
        if (vpDivergence) append(" | ⚠量价背离")
        append(" | MA5/10/30=${"%.0f".format(ma5)}/${"%.0f".format(ma10)}/${"%.0f".format(ma30)}")
        append(" | 建议: ${period.label}(仓位${posPct}%)")
    }
}
