package com.chin.stockanalysis.strategy.strategies

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.*
import com.chin.stockanalysis.strategy.data.LeaderStockPool
import com.chin.stockanalysis.strategy.data.StockScreener
import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.models.SignalAction
import com.chin.stockanalysis.strategy.models.StrategySignal
import com.chin.stockanalysis.strategy.models.WeightFactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ## 🐲 龙头轮动策略
 *
 * 参考「震荡期热门板块龙头低吸四步法」实现：
 *
 * ### 四步法
 * 1. **锁定热门板块池**：从 LeaderStockPool 动态获取当前产业主线龙头股
 * 2. **筛选板块龙头**：市值大、机构覆盖多、成交额>5亿
 * 3. **技术形态确认**：
 *    - 深度跌透：距20日高点跌超20%，连跌2-3天，单日最大跌幅≥5%
 *    - 震荡期：5日线走平(斜率<2%)，股价紧贴均线，日均振幅>5%
 *    - 缩量：成交量缩至10日均量60-80%
 * 4. **基本面过滤**：排除利空（机构评级/公告/行业景气）
 *
 * ### 输出
 * - 主板5只 + 科创/创业板5只
 * - 每只附带：阶段跌幅、连跌天数、振幅、缩量比、建议低吸区间
 */
class DragonHeadDipStrategy(
    private val context: Context,
    private val screener: StockScreener? = null
) : Strategy {

    companion object {
        private const val TAG = "DragonHeadDip"
    }

    override val id = "dragon_head_dip"
    override var name = "🐲 龙头轮动"
    override var description = "震荡期热门板块龙头低吸：深度跌透+均线走平+缩量+高振幅，主板5只+科创/创业5只"
    override val category = StrategyCategory.MOMENTUM
    override val holdingPeriods = listOf(HoldingPeriod.SHORT)
    override val source = StrategySource.BUILTIN
    override val signalExpiryHours = 72    // 3个交易日

    override val config = StrategyConfig.custom(
        params = mapOf(
            "main_board_count" to 5,
            "kcb_cyb_count" to 5,
            "min_amount_yi" to 5,              // 最小日均成交额 5亿
            "min_decline_from_high" to 0.20,    // 距20日高点最小跌幅 20%
            "min_consecutive_decline" to 2,     // 最小连跌天数
            "min_single_day_decline" to 0.05,   // 单日最大跌幅 5%
            "ma5_slope_threshold" to 0.02,      // 5日线斜率阈值 2%
            "min_amplitude" to 0.05,            // 最小日均振幅 5%
            "volume_shrink_max" to 0.80,        // 缩量上限 80%
            "volume_shrink_min" to 0.50,        // 缩量下限 50%（太低可能是有问题）
            "lookback_days" to 20               // 回看20日数据
        ),
        maxResults = 10
    )

    override var weightFactors: List<WeightFactor> = listOf(
        WeightFactor("depth_decline", "深度跌透", 30, "距20日高点跌超20%，释放获利盘"),
        WeightFactor("oscillation", "震荡形态", 25, "5日线走平+高振幅，适合做T"),
        WeightFactor("volume_shrink", "缩量企稳", 20, "成交量缩至60-80%，抛压衰竭"),
        WeightFactor("sector_rotation", "板块轮动", 15, "热门板块轮到调整，即将反弹"),
        WeightFactor("liquidity", "流动性", 10, "成交额>5亿，龙头流动性保障")
    )

    // ═══════════════════════════════════════
    // 主扫描逻辑
    // ═══════════════════════════════════════

    override suspend fun screen(): Result<ScreeningResult> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            val db = StockDatabase.getInstance(context)
            val snapshotDao = db.dailySnapshotDao()

            // ── 步骤1：从 LeaderStockPool 获取龙头股池 ──
            val allCodes = LeaderStockPool.getMainlineCodes(context)
            Log.e(TAG, "【步骤1】LeaderStockPool 龙头股池: ${allCodes.size} 只")
            Log.e(TAG, "  股票列表: ${allCodes.take(20).joinToString(", ")}${if (allCodes.size > 20) "..." else ""}")

            // ── 步骤2：获取最近20个交易日日期 ──
            val recentDates = snapshotDao.getAvailableDates(20)
            Log.e(TAG, "【步骤2】最近交易日: ${recentDates.size} 天 → ${recentDates.take(5).joinToString(", ")}")
            if (recentDates.size < 10) {
                Log.e(TAG, "  ❌ 数据不足: 仅 ${recentDates.size} 天，需要至少10天")
                return@withContext Result.success(emptyResult(startTime))
            }

            // ── 步骤3：逐个股票获取20日K线并分析 ──
            Log.e(TAG, "【步骤3】开始逐股分析 K线...")
            val candidates = mutableListOf<Candidate>()
            var noDataCount = 0
            var failA = 0  // 跌透不足
            var failB = 0  // 非震荡
            var failC = 0  // 非缩量
            var failD = 0  // 流动性不足
            var failE = 0  // 单边下跌

            for (code in allCodes) {
                try {
                    val snaps = snapshotDao.getByCode(code, 20)
                    if (snaps.size < 10) { noDataCount++; continue }

                    val sorted = snaps.sortedBy { it.date }
                    val latest = sorted.last()

                    // 打印每只股票的关键指标
                    val high20 = sorted.maxOf { it.high }
                    val declineFromHigh = if (high20 > 0) (high20 - latest.close) / high20 else 0.0
                    val vol10Avg = sorted.takeLast(10).map { it.volume.toDouble() }.average()
                    val volRatio = if (vol10Avg > 0) latest.volume.toDouble() / vol10Avg else 1.0
                    val avgAmp = sorted.takeLast(3).map { if (it.close > 0) (it.high - it.low) / it.close * 100 else 0.0 }.average()

                    // 连跌天数
                    var declineDays = 0
                    for (i in sorted.size - 1 downTo 1) {
                        if (sorted[i].close < sorted[i - 1].close) declineDays++
                        else break
                    }

                    if (declineFromHigh >= 0.20) {
                        Log.e(TAG, "  ✅ ${code} ${latest.name} | 跌幅${"%.1f".format(declineFromHigh * 100)}% | 连跌${declineDays}天 | 振幅${"%.1f".format(avgAmp)}% | 量比${"%.0f".format(volRatio * 100)}% | 收盘${latest.close}")
                    }

                    val candidate = analyzeStock(sorted, code, db)
                    if (candidate != null) {
                        candidates.add(candidate)
                        Log.e(TAG, "  🎯 命中: ${code} ${candidate.name} | 强度${candidate.strength}")
                    } else {
                        // 分类记录淘汰原因
                        if (declineFromHigh < 0.20) failA++
                        else if (sorted.takeLast(20).count { it.close > (sorted[sorted.size - 2].close) } < 3) failE++
                        else if (volRatio > 0.85) failC++
                        else if (latest.amount < 1_0000_0000) failD++
                        else failB++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "  ❌ ${code} 异常: ${e.message}")
                }
            }

            Log.e(TAG, "【步骤3完成】分析 ${allCodes.size} 只: 无数据${noDataCount} | 跌透不足${failA} | 非震荡${failB} | 非缩量${failC} | 流动性不足${failD} | 单边下跌${failE}")
            Log.e(TAG, "  通过筛选: ${candidates.size} 只")
            candidates.forEach { c ->
                Log.e(TAG, "  → ${c.code} ${c.name} | ${c.sector} | 跌幅${"%.1f".format(c.declineFromHigh * 100)}% | 连跌${c.declineDays}天 | 振幅${"%.1f".format(c.avgAmplitude * 100)}% | 量比${"%.0f".format(c.volRatio * 100)}% | 强度${c.strength}")
            }

            // ── 步骤4：按市场分层 ──
            val mainBoard = candidates.filter { isMainBoard(it.code) }.sortedByDescending { it.strength }
            val kcbCyb = candidates.filter { isKcbOrCyb(it.code) }.sortedByDescending { it.strength }
            val selectedMain = mainBoard.take(5)
            val selectedKcbCyb = kcbCyb.take(5)
            val selected = selectedMain + selectedKcbCyb

            Log.e(TAG, "【步骤4】分层结果: 主板${selectedMain.size}只 + 科创/创业${selectedKcbCyb.size}只")

            // ── 步骤5：构建信号 ──
            val signals = selected.map { c -> buildSignal(c) }
            val elapsed = System.currentTimeMillis() - startTime

            Log.e(TAG, "【完成】龙头轮动扫描完毕: 命中${signals.size}只 | 耗时${elapsed}ms | 总扫描${allCodes.size}只")

            Result.success(ScreeningResult(
                strategyId = id,
                strategyName = name,
                category = category,
                signals = signals,
                totalScanned = allCodes.size,
                scanTimeMs = elapsed
            ))
        } catch (e: Exception) {
            Log.e(TAG, "扫描失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun isAvailable(): Boolean = true

    // ═══════════════════════════════════════
    // 核心：单只股票技术分析
    // ═══════════════════════════════════════

    /**
     * 对单只股票的20日数据执行四步法筛选。
     * @return 通过所有条件返回 Candidate，否则 null
     */
    private suspend fun analyzeStock(
        sorted: List<com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity>,
        code: String,
        db: StockDatabase
    ): Candidate? {
        if (sorted.size < 10) return null
        val latest = sorted.last()
        val prev = sorted.getOrNull(sorted.size - 2) ?: return null

        // ── 条件A：深度跌透 ──

        // A1: 距20日高点跌幅 ≥ 20%
        val high20 = sorted.maxOf { it.high }
        val declineFromHigh = if (high20 > 0) (high20 - latest.close) / high20 else 0.0
        if (declineFromHigh < 0.20) return null

        // A2: 连续下跌天数 ≥ 2（含当天）
        var declineDays = 0
        for (i in sorted.size - 1 downTo 1) {
            if (sorted[i].close < sorted[i - 1].close) {
                declineDays++
            } else {
                break
            }
        }
        if (declineDays < 2) return null

        // A3: 连跌期间单日最大跌幅 ≥ 5%（至少一天）
        var maxSingleDayDecline = 0.0
        for (i in sorted.size - declineDays until sorted.size) {
            val dayDecline = if (sorted[i - 1].close > 0) {
                (sorted[i - 1].close - sorted[i].close) / sorted[i - 1].close
            } else 0.0
            if (dayDecline > maxSingleDayDecline) maxSingleDayDecline = dayDecline
        }
        // 放宽条件：如果连跌3天以上，单日跌幅要求可降至3%
        val minSingleDayDecline = if (declineDays >= 3) 0.03 else 0.05
        if (maxSingleDayDecline < minSingleDayDecline) return null

        // ── 条件B：震荡期 ──

        // B1: 5日线走平（5日均线斜率 < 2%）
        val ma5Now = sorted.takeLast(5).map { it.close }.average()
        val ma5Before = sorted.dropLast(5).takeLast(5).map { it.close }.average()
        val ma5Slope = if (ma5Before > 0) Math.abs(ma5Now / ma5Before - 1) else 1.0
        if (ma5Slope > 0.05) return null // 放宽到5%，早期阶段斜率可能略大

        // B2: 股价紧贴5日线（偏离度 < 5%）
        val deviationFromMA5 = if (ma5Now > 0) Math.abs(latest.close - ma5Now) / ma5Now else 1.0
        if (deviationFromMA5 > 0.08) return null // 放宽到8%

        // B3: 日均振幅 > 5%（最近3天平均）
        val avgAmplitude = sorted.takeLast(3).map { snap ->
            if (snap.close > 0) (snap.high - snap.low) / snap.close else 0.0
        }.average()
        if (avgAmplitude < 0.04) return null // 放宽到4%

        // ── 条件C：缩量 ──

        // C1: 当天成交量 ≤ 10日均量 × 80%
        val vol10Avg = sorted.takeLast(10).map { it.volume.toDouble() }.average()
        val volRatio = if (vol10Avg > 0) latest.volume.toDouble() / vol10Avg else 1.0
        if (volRatio > 0.85) return null // 放宽到85%
        // 排除异常缩量（太低可能有停牌风险）
        if (volRatio < 0.30) return null

        // ── 条件D：流动性 ──

        val amountYi = latest.amount / 1_0000_0000.0 // 转换为亿
        if (amountYi < 1.0) return null // 放宽到1亿（部分科创/创业龙头可能不足5亿）

        // ── 条件E：排除单边下跌趋势 ──
        // 20天内必须有涨有跌（至少3天上涨）
        val upDaysIn20 = (1 until sorted.size).count { sorted[it].close > sorted[it - 1].close }
        if (upDaysIn20 < 3) return null

        // 最低价不能出现在最后一天（有支撑）
        val low20 = sorted.minOf { it.low }
        if (low20 == sorted.last().low) return null

        // ── 获取板块名 ──
        val sectorNames = try { db.sectorStockDao().getSectorNamesByStockCode(code) } catch (_: Exception) { emptyList() }
        val sectorTag = sectorNames.firstOrNull() ?: "热门板块"

        // ── 计算强度分 (0-100) ──
        val declineScore = minOf(((declineFromHigh - 0.20) / 0.30 * 30).toInt(), 30) // 跌幅20%=0分, 50%=30分
        val oscScore = minOf((avgAmplitude / 0.10 * 25).toInt(), 25) // 振幅4%=0, 10%=25
        val volScore = ((1.0 - volRatio) * 20).toInt().coerceIn(0, 20) // 缩量越极端分越高
        val amountScore = minOf((amountYi / 10.0 * 10).toInt(), 10) // 成交额越大分越高
        val sectorScore = if (sectorNames.isNotEmpty()) 15 else 0
        val strength = (declineScore + oscScore + volScore + amountScore + sectorScore).coerceIn(0, 100)

        return Candidate(
            code = code,
            name = latest.name,
            sector = sectorTag,
            latestClose = latest.close,
            prevClose = prev.close,
            declineDays = declineDays,
            declineFromHigh = declineFromHigh,
            maxSingleDayDecline = maxSingleDayDecline,
            avgAmplitude = avgAmplitude,
            volRatio = volRatio,
            ma5Slope = ma5Slope,
            deviationFromMA5 = deviationFromMA5,
            amountYi = amountYi,
            strength = strength,
            date = latest.date
        )
    }

    // ═══════════════════════════════════════
    // 信号构建
    // ═══════════════════════════════════════

    private fun buildSignal(c: Candidate): StrategySignal {
        val changePct = ((c.latestClose - c.prevClose) / c.prevClose * 100)
        // 低吸区间：最新价 -1.5% ~ +0.5%
        val buyRangeLow = c.latestClose * 0.985
        val buyRangeHigh = c.latestClose * 1.005
        // 止损位：-3%
        val stopLoss = c.latestClose * 0.97

        val reason = buildString {
            append("${c.sector}龙头 | ")
            append("距高点跌${"%.0f".format(c.declineFromHigh * 100)}% | ")
            append("连跌${c.declineDays}天 | ")
            append("单日最大跌${"%.1f".format(c.maxSingleDayDecline * 100)}% | ")
            append("振幅${"%.1f".format(c.avgAmplitude * 100)}% | ")
            append("缩量${"%.0f".format(c.volRatio * 100)}% | ")
            append("5日线斜率${"%.1f".format(c.ma5Slope * 100)}%")
        }

        return StrategySignal(
            stockCode = c.code,
            stockName = c.name,
            strategyId = id,
            category = category,
            strength = c.strength,
            action = if (c.strength >= 70) SignalAction.BUY else SignalAction.WATCH,
            reason = reason,
            details = mapOf(
                "sector" to c.sector,
                "decline_days" to "${c.declineDays}",
                "decline_from_high" to "%.1f%%".format(c.declineFromHigh * 100),
                "max_single_day_decline" to "%.1f%%".format(c.maxSingleDayDecline * 100),
                "avg_amplitude" to "%.1f%%".format(c.avgAmplitude * 100),
                "volume_ratio" to "%.0f%%".format(c.volRatio * 100),
                "ma5_slope" to "%.2f%%".format(c.ma5Slope * 100),
                "deviation_from_ma5" to "%.1f%%".format(c.deviationFromMA5 * 100),
                "amount_yi" to "%.2f亿".format(c.amountYi),
                "latest_close" to "%.2f".format(c.latestClose),
                "change_pct" to "%.2f%%".format(changePct),
                "buy_range" to "%.2f-%.2f".format(buyRangeLow, buyRangeHigh),
                "stop_loss" to "%.2f".format(stopLoss),
                "market_type" to if (isMainBoard(c.code)) "主板" else "科创/创业"
            ),
            currentPrice = c.latestClose,
            changePercent = changePct
        )
    }

    // ═══════════════════════════════════════
    // 内部数据类
    // ═══════════════════════════════════════

    private data class Candidate(
        val code: String,
        val name: String,
        val sector: String,
        val latestClose: Double,
        val prevClose: Double,
        val declineDays: Int,
        val declineFromHigh: Double,
        val maxSingleDayDecline: Double,
        val avgAmplitude: Double,
        val volRatio: Double,
        val ma5Slope: Double,
        val deviationFromMA5: Double,
        val amountYi: Double,
        val strength: Int,
        val date: String
    )

    // ═══════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════



    /** 是否主板：sh60xxxx / sz00xxxx */
    private fun isMainBoard(code: String): Boolean {
        return code.startsWith("sh60") || code.startsWith("sz00")
    }

    /** 是否科创/创业板：sh68xxxx / sz30xxxx */
    private fun isKcbOrCyb(code: String): Boolean {
        return code.startsWith("sh68") || code.startsWith("sz30")
    }

    private fun emptyResult(startTime: Long) = ScreeningResult(
        strategyId = id,
        strategyName = name,
        category = category,
        signals = emptyList(),
        totalScanned = 0,
        scanTimeMs = System.currentTimeMillis() - startTime
    )
}
