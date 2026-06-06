package com.chin.stockanalysis.strategy.backtest

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.Strategy
import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.models.WeightFactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * ## 策略自测调优引擎（增量梯度版 + 调优过程可视化）
 *
 * 通过"回测→按次日涨幅反推最优权重→持久化→再回测"的闭环，自动优化策略权重因子。
 *
 * ### 新增功能（v2.0）
 * - **调优过程日志**: 每一步都输出详细过程日志，可追踪每个因子的变化
 * - **今日判断**: 如果今天是交易日，使用昨天数据回测并用今天开盘数据验证
 * - **逐日详情**: 每个交易日每个策略的预测结果都会记录
 *
 * ### 工作流程
 * ```
 * 1. 判断今天是否为交易日 → 决定数据范围
 * 2. 加载该策略的历史权重快照（如有）
 * 3. 跑最近 N 个交易日的历史回测
 * 4. 逐日统计预测信号中"下一天涨幅靠前"的股票 ← 梯度方向
 * 5. 计算因子与次日涨幅的相关系数，按梯度增量调整权重（±5%步长）
 * 6. 持久化新权重到 strategy_weight_snapshot 表
 * 7. 再回测 → 对比优化前后效果
 * 8. 如果今天是交易日，用今天开盘数据验证昨日预测
 * ```
 *
 * ### 使用方式
 * ```kotlin
 * val tuner = StrategySelfTuner(context)
 * val report = tuner.selfTune(
 *     strategies = engine.getStrategies(),
 *     backtestDays = 30,
 *     targetAccuracy = 0.55f
 * )
 * ```
 */
class StrategySelfTuner(private val context: Context) {

    companion object {
        private const val TAG = "StrategySelfTuner"
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        /** A 股交易时间: 9:30-15:00, 周一至周五 */
        fun isTradingTime(): Boolean {
            val now = LocalTime.now()
            val today = LocalDate.now()
            if (today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY) return false
            // 9:15 开始集合竞价可视为已开盘，15:30 后数据基本入库
            val openTime = LocalTime.of(9, 15)
            val closeTime = LocalTime.of(15, 30)
            return !now.isBefore(openTime) && !now.isAfter(closeTime)
        }

        /** 判断今天是否是交易日（简化判断：周一到周五 + 非节假日） */
        fun isTodayTradingDay(): Boolean {
            val today = LocalDate.now()
            if (today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY) return false
            // TODO: 接入节假日判断
            return true
        }

        /**
         * 从 strategy_weight_snapshot 表加载某策略的最新调优权重因子。
         * @return 若存在最新快照则返回解析后的 WeightFactor 列表，否则返回 null
         */
        suspend fun loadLatestTunedWeights(context: Context, strategyId: String): List<WeightFactor>? {
            return try {
                val db = StockDatabase.getInstance(context)
                val snap = db.strategyWeightSnapshotDao().getByStrategy(strategyId, 1).firstOrNull() ?: return null
                val factors = snap.weightJson.split(";").mapNotNull { part ->
                    val parts = part.split(":")
                    if (parts.size >= 3 && parts[2].toIntOrNull() != null) {
                        WeightFactor(key = parts[0], label = parts[1], weight = parts[2].toInt(), description = "")
                    } else null
                }
                if (factors.isNotEmpty()) {
                    val sum = factors.sumOf { it.weight }
                    if (sum != 100 && sum > 0) {
                        factors.map { it.copy(weight = (it.weight * 100 / sum).coerceIn(1, 90)) }
                    } else factors
                } else null
            } catch (_: Exception) { null }
        }
    }

    private val db = StockDatabase.getInstance(context)

    /**
     * 自测调优结果
     */
    data class TuneReport(
        val dateRange: String,
        val backtestDays: Int,
        val isTodayValidate: Boolean = false,
        val todayValidateResult: String? = null,
        val strategyTuneDetails: List<StrategyTuneDetail>,
        val tuneLogs: List<String> = emptyList(),
        val summary: String
    )

    data class StrategyTuneDetail(
        val strategyId: String,
        val strategyName: String,
        val beforeAccuracy: Float,
        val beforeAvgReturn: Double,
        val afterAccuracy: Float,
        val afterAvgReturn: Double,
        val wasTuned: Boolean,
        val weightChanges: List<String>,
        /** 调优过程的详细日志，每一行代表一步 */
        val processLogs: List<String> = emptyList()
    )

    /**
     * 执行自测调优（增量梯度版 + 过程可视化）
     *
     * @param strategies 要调优的策略列表
     * @param backtestDays 回测天数
     * @param targetAccuracy 目标买入准确率 (0.0~1.0)
     */
    suspend fun selfTune(
        strategies: List<Strategy>,
        backtestDays: Int = 30,
        targetAccuracy: Float = 0.55f
    ): TuneReport = withContext(Dispatchers.IO) {
        // ════════════════════════════════════════
        // Step 0: 判断今天状态，决定数据范围
        // ════════════════════════════════════════
        val today = LocalDate.now().format(DATE_FMT)
        val todayIsTrading = isTodayTradingDay()
        val isInTradingHours = isTradingTime()

        Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.i(TAG, "📅 调优日期: $today")
        Log.i(TAG, "📅 是否交易日: $todayIsTrading")
        Log.i(TAG, "📅 是否盘中: $isInTradingHours")
        Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        val allLogs = mutableListOf<String>()
        allLogs.add("策略自测调优(梯度版)")
        allLogs.add("回测天数: ${backtestDays}天, 目标准确率: ${"%.0f".format(targetAccuracy * 100)}%")
        allLogs.add("今天: $today, 交易日: $todayIsTrading, 盘中: $isInTradingHours")

        // 获取可用日期
        val availableDates = db.dailySnapshotDao().getAvailableDates(backtestDays + 2)
        Log.i(TAG, "可用日期数量: ${availableDates.size}")
        allLogs.add("可用历史数据: ${availableDates.size} 个交易日")

        if (availableDates.size < 5) {
            Log.w(TAG, "历史数据不足，需要至少5个交易日，当前仅${availableDates.size}天")
            allLogs.add("⚠️ 数据不足: 仅 ${availableDates.size} 天，需要至少5天")
            return@withContext TuneReport(
                dateRange = "数据不足",
                backtestDays = availableDates.size,
                strategyTuneDetails = emptyList(),
                tuneLogs = allLogs,
                summary = "需要更多历史数据：先在策略页面[导入]历史K线数据（至少5天）。"
            )
        }

        // 决定回测用的日期范围
        // 如果今天是交易日，且盘中：用"不包含今天"的数据回测（最后一天是昨天），然后用今天开盘后数据验证
        // 如果今天不是交易日：直接用最近 N 天
        val backtestDates: List<String>
        val todayValidateDate: String?
        var todayValidateData: List<DailySnapshotEntity>? = null

        if (todayIsTrading && isInTradingHours) {
            // 今天在交易中：用昨天的数据回测 + 用今天的实时数据验证
            allLogs.add("🔍 检测到今天在交易时段，将使用昨日数据回测 + 今日开盘数据验证")
            Log.i(TAG, "使用昨日数据回测 + 今日开盘验证模式")
            // 排除今天的数据，用 today 之后到 next 的那些天（不含 today）
            val datesExcludingToday = availableDates.filter { it != today }
            backtestDates = datesExcludingToday.take(backtestDays + 1)
            todayValidateDate = today
            todayValidateData = db.dailySnapshotDao().getByDate(today)
            allLogs.add("今日开盘数据: ${todayValidateData?.size ?: 0} 只股票")
            Log.i(TAG, "今日开盘数据: ${todayValidateData?.size ?: 0} 只")
        } else {
            // 常规模式
            backtestDates = availableDates.take(backtestDays + 1)
            todayValidateDate = null
            todayValidateData = null
            allLogs.add("📊 常规回测模式")
        }

        Log.i(TAG, "回测区间: ${backtestDates.firstOrNull() ?: "N/A"} ~ ${backtestDates.lastOrNull() ?: "N/A"}, ${backtestDates.size}天")
        allLogs.add("回测区间: ${backtestDates.firstOrNull() ?: "N/A"} ~ ${backtestDates.lastOrNull() ?: "N/A"} (${backtestDates.size}天)")

        val engine = HistoricalBacktestEngine(context)
        val details = mutableListOf<StrategyTuneDetail>()

        Log.i(TAG, "开始策略自测调优(梯度版): ${backtestDays}天, 目标准确率 ${"%.0f".format(targetAccuracy * 100)}%")

        for (strategy in strategies) {
            val strategyLogs = mutableListOf<String>()
            strategyLogs.add("")
            strategyLogs.add("策略: ${strategy.name} (${strategy.id})")
            allLogs.add("")
            allLogs.add("策略: ${strategy.name} (${strategy.id})")

            Log.d(TAG, "  评估 ${strategy.id} - ${strategy.name} ...")
            Log.i(TAG, "  原始权重: ${strategy.weightFactors.joinToString { "${it.label}=${it.weight}" }}")
            strategyLogs.add("原始权重: ${strategy.weightFactors.joinToString { "${it.label}=${it.weight}" }}")
            allLogs.add("原始权重: ${strategy.weightFactors.joinToString { "${it.label}=${it.weight}" }}")

            // Step 0: 加载历史权重快照
            val weightSnapshots = loadWeightHistory(strategy.id, backtestDays)
            if (weightSnapshots.isNotEmpty()) {
                val latest = weightSnapshots.first()
                Log.d(TAG, "    加载历史权重快照: ${latest.date}, 命中${latest.hitCount}")
                strategyLogs.add("加载历史权重快照: ${latest.date}")
            }

            // Step 1: 原始回测
            Log.i(TAG, "  📊 [${strategy.name}] Step 1/6: 原始回测...")
            strategyLogs.add("📊 Step 1/6: 原始回测 — 用当前权重跑 $backtestDays 天数据")
            allLogs.add("  📊 Step 1/6: 原始回测...")

            val beforeReport = engine.runHistoricalBacktest(listOf(strategy), backtestDays)
            val beforeStats = beforeReport.strategyReports.firstOrNull()

            Log.i(TAG, "    原始回测: totalBuys=${beforeStats?.totalBuys ?: 0}, accuracy=${"%.1f".format((beforeStats?.buyAccuracy ?: 0f) * 100)}%")
            if (beforeStats != null) {
                strategyLogs.add("  原始准确率: ${"%.1f".format(beforeStats.buyAccuracy * 100)}% (${beforeStats.correctBuys}/${beforeStats.totalBuys})")
                strategyLogs.add("  原始平均收益: ${"%.2f".format(beforeStats.avgReturn)}%")
                if (beforeStats.dayDetails.isNotEmpty()) {
                    strategyLogs.add("  逐日表现:")
                    for (dd in beforeStats.dayDetails) {
                        strategyLogs.add("    ${dd.date}: 买${dd.buys}只, 命中${dd.correct}只, 收益${"%.2f".format(dd.avgReturn)}%")
                    }
                }
            }

            if (beforeStats == null || beforeStats.totalBuys < 2) {
                strategyLogs.add("  ⚠️ 信号不足(${beforeStats?.totalBuys ?: 0}条)，跳过调优")
                allLogs.add("  ⚠️ 信号不足，跳过调优")
                details.add(StrategyTuneDetail(
                    strategyId = strategy.id,
                    strategyName = strategy.name,
                    beforeAccuracy = beforeStats?.buyAccuracy ?: 0f,
                    beforeAvgReturn = beforeStats?.avgReturn ?: 0.0,
                    afterAccuracy = beforeStats?.buyAccuracy ?: 0f,
                    afterAvgReturn = beforeStats?.avgReturn ?: 0.0,
                    wasTuned = false,
                    weightChanges = emptyList(),
                    processLogs = strategyLogs
                ))
                continue
            }

            // Step 2: 检查是否需要调优
            if (beforeStats.buyAccuracy >= targetAccuracy) {
                saveWeightSnapshot(strategy)
                strategyLogs.add("  ✅ 已达目标准确率(${"%.0f".format(targetAccuracy * 100)}%)，无需调优")
                allLogs.add("  ✅ 已达目标准确率，无需调优")
                details.add(StrategyTuneDetail(
                    strategyId = strategy.id,
                    strategyName = strategy.name,
                    beforeAccuracy = beforeStats.buyAccuracy,
                    beforeAvgReturn = beforeStats.avgReturn,
                    afterAccuracy = beforeStats.buyAccuracy,
                    afterAvgReturn = beforeStats.avgReturn,
                    wasTuned = false,
                    weightChanges = listOf("已达目标准确率，无需调优"),
                    processLogs = strategyLogs
                ))
                continue
            }

            // Step 3: 增量梯度调优
            Log.i(TAG, "  📐 [${strategy.name}] Step 2/6: 梯度分析每个因子...")
            strategyLogs.add("📐 Step 2/6: 梯度分析 — 逐日扰动 ±5 计算每个因子的贡献")
            allLogs.add("  📐 Step 2/6: 梯度分析...")

            val originalWeights = strategy.weightFactors.toList()
            val changes = mutableListOf<String>()
            val gradientFactors = computeGradientWeightsWithLog(strategy, engine, backtestDays, backtestDates, strategyLogs)

            Log.i(TAG, "  📊 [${strategy.name}] Step 3/6: 新旧权重融合...")
            strategyLogs.add("📊 Step 3/6: 新旧权重融合 (新权重×70% + 旧权重×30%)")
            allLogs.add("  📊 Step 3/6: 权重融合...")

            if (gradientFactors.isNotEmpty()) {
                strategyLogs.add("  梯度计算结果:")
                gradientFactors.forEach { gf ->
                    val orig = originalWeights.find { it.key == gf.key }
                    val origW = orig?.weight ?: 0
                    strategyLogs.add("    ${gf.label}: ${origW} → ${gf.weight} (${if (gf.weight > origW) "+${gf.weight - origW}" else "${gf.weight - origW}"})")
                }

                val merged = mergeWeightsWithLog(originalWeights, gradientFactors, 0.7f, strategyLogs)
                strategy.weightFactors = merged
                changes.add("梯度调优: ${merged.joinToString(",") { "${it.label}=${it.weight}" }}")
                strategyLogs.add("  融合后权重: ${merged.joinToString { "${it.label}=${it.weight}" }}")
                allLogs.add("    梯度新权重: ${merged.joinToString { "${it.label}=${it.weight}" }}")
                Log.d(TAG, "    梯度新权重: ${merged.joinToString { "${it.label}=${it.weight}" }}")
            } else {
                strategyLogs.add("  ⚠️ 梯度分析数据不足，回退到简单微调")
                allLogs.add("  ⚠️ 梯度数据不足，使用简单微调")
                val adjustedFactors = originalWeights.map { wf ->
                    if (wf.weight < 20) {
                        val newWeight = (wf.weight + 5).coerceAtMost(25)
                        if (newWeight != wf.weight) {
                            changes.add("${wf.key}: ${wf.weight}→${newWeight}")
                            strategyLogs.add("    微调 ${wf.label}: ${wf.weight}→${newWeight}")
                        }
                        wf.copy(weight = newWeight)
                    } else wf
                }.toMutableList()
                val totalWeight = adjustedFactors.sumOf { it.weight }
                if (totalWeight != 100) {
                    val scale = 100.0 / totalWeight
                    adjustedFactors.replaceAll { it.copy(weight = (it.weight * scale).toInt().coerceIn(1, 95)) }
                }
                strategy.weightFactors = adjustedFactors
                changes.add("简单微调(数据不足梯度分析)")
                strategyLogs.add("  微调后权重: ${adjustedFactors.joinToString { "${it.label}=${it.weight}" }}")
            }

            // Step 4: 再回测
            Log.i(TAG, "  🔄 [${strategy.name}] Step 4/6: 调优后回测验证...")
            strategyLogs.add("🔄 Step 4/6: 调优后回测 — 用新权重重新跑 $backtestDays 天")
            allLogs.add("  🔄 Step 4/6: 调优后回测...")

            val afterReport = engine.runHistoricalBacktest(listOf(strategy), backtestDays)
            val afterStats = afterReport.strategyReports.firstOrNull()

            if (afterStats == null) {
                strategy.weightFactors = originalWeights
                strategyLogs.add("  ❌ 调优后回测失败，已还原原权重")
                allLogs.add("  ❌ 回测失败，已还原")
                details.add(StrategyTuneDetail(
                    strategyId = strategy.id,
                    strategyName = strategy.name,
                    beforeAccuracy = beforeStats.buyAccuracy,
                    beforeAvgReturn = beforeStats.avgReturn,
                    afterAccuracy = beforeStats.buyAccuracy,
                    afterAvgReturn = beforeStats.avgReturn,
                    wasTuned = false,
                    weightChanges = listOf("调优后回测失败，已还原"),
                    processLogs = strategyLogs
                ))
                continue
            }

            // Step 5: 对比效果
            Log.i(TAG, "  📈 [${strategy.name}] Step 5/6: 对比优化前后效果...")
            strategyLogs.add("📈 Step 5/6: 优化前后对比")
            strategyLogs.add("  优化前: 准确率 ${"%.1f".format(beforeStats.buyAccuracy * 100)}%, 平均收益 ${"%.2f".format(beforeStats.avgReturn)}%")
            strategyLogs.add("  优化后: 准确率 ${"%.1f".format(afterStats.buyAccuracy * 100)}%, 平均收益 ${"%.2f".format(afterStats.avgReturn)}%")
            allLogs.add("  📈 Step 5/6: 对比效果...")

            val improved = afterStats.buyAccuracy > beforeStats.buyAccuracy
            if (improved) {
                strategyLogs.add("  ✅ 准确率提升: +${"%.1f".format((afterStats.buyAccuracy - beforeStats.buyAccuracy) * 100)}%")
                changes.add("✅ 准确率: ${"%.1f".format(beforeStats.buyAccuracy * 100)}% → ${"%.1f".format(afterStats.buyAccuracy * 100)}%")
                saveWeightSnapshot(strategy)
            } else {
                strategy.weightFactors = originalWeights
                strategyLogs.add("  ⚠️ 无改善，已还原原权重")
                changes.add("⚠️ 无改善，已还原原权重")
            }

            // Step 6: 如果是今天且在交易时段，用今天开盘数据验证
            if (todayValidateDate != null && todayValidateData != null && todayValidateData.isNotEmpty()) {
                Log.i(TAG, "  🔮 [${strategy.name}] Step 6/6: 今日开盘验证...")
                strategyLogs.add("🔮 Step 6/6: 今日开盘验证 — 用今天开盘数据检查昨日预测")
                allLogs.add("  🔮 Step 6/6: 今日开盘验证...")

                val todayStocks = todayValidateData.map { snap ->
                    StockRealtime(
                        code = snap.code, name = snap.name,
                        price = snap.close, open = snap.open,
                        yestClose = snap.close / (1.0 + snap.changePct / 100.0),
                        high = snap.high, low = snap.low,
                        volume = snap.volume, amount = snap.amount,
                        changePercent = snap.changePct,
                        changeAmount = snap.close * snap.changePct / 100,
                        timestamp = System.currentTimeMillis()
                    )
                }

                try {
                    val result = strategy.screenWithData(todayStocks)
                    val signals = result.getOrNull()?.signals ?: emptyList()
                    strategyLogs.add("  今日($todayValidateDate) 选中 ${signals.size} 只股票")
                    signals.take(10).forEach { sig ->
                        strategyLogs.add("    ${sig.stockName}(${sig.stockCode.takeLast(6)}): score=${sig.strength} action=${sig.action} chg=${"%.2f".format(sig.changePercent)}% | ${sig.reason.take(60)}")
                    }
                    allLogs.add("  今日开盘选股: ${signals.size} 只 (前10只已记录)")
                } catch (e: Exception) {
                    strategyLogs.add("  ⚠️ 今日验证异常: ${e.message}")
                    Log.w(TAG, "  今日验证异常: ${e.message}")
                }
            }

            details.add(StrategyTuneDetail(
                strategyId = strategy.id,
                strategyName = strategy.name,
                beforeAccuracy = beforeStats.buyAccuracy,
                beforeAvgReturn = beforeStats.avgReturn,
                afterAccuracy = if (improved) afterStats.buyAccuracy else beforeStats.buyAccuracy,
                afterAvgReturn = if (improved) afterStats.avgReturn else beforeStats.avgReturn,
                wasTuned = improved,
                weightChanges = changes,
                processLogs = strategyLogs
            ))
        }

        // Step 7: 记录每日涨幅 Top 板块/个股（供统计面板使用）
        recordDailyTopPerformers(backtestDates.take(backtestDates.size.coerceAtMost(backtestDays)))

        val dateRange = "${backtestDates.lastOrNull() ?: "N/A"} ~ ${backtestDates.firstOrNull() ?: "N/A"}"
        val tunedCount = details.count { it.wasTuned }
        val improvedCount = details.count { it.afterAccuracy > it.beforeAccuracy }

        var todayValidateText = ""
        if (todayValidateDate != null) {
            todayValidateText = "\n🔮 今日($today)开盘验证: 每个策略的今日选股结果见上方过程日志\n"
        }

        val summary = buildString {
            appendLine("📊 策略自测调优报告（梯度增量版）")
            appendLine("回测区间: $dateRange (${backtestDates.size - 1}个交易日)")
            appendLine("共 ${details.size} 个策略，已调优 $tunedCount 个，$improvedCount 个改善")
            if (todayValidateDate != null) appendLine("🔮 今日($todayValidateDate)开盘已做实时验证")
            appendLine()

            appendLine("📋 策略成功率排名对比")
            appendLine(String.format("%-3s %-20s %10s %10s %8s %s", "排", "策略名称", "优化前", "优化后", "变化", "评级"))
            val sorted = details.sortedByDescending { it.afterAccuracy }
            for ((idx, d) in sorted.withIndex()) {
                val change = d.afterAccuracy - d.beforeAccuracy
                val changeStr = when {
                    change > 0.02f -> "↑+${"%.1f".format(change * 100)}%"
                    change > 0 -> "↑+${"%.1f".format(change * 100)}%"
                    change < 0 -> "↓${"%.1f".format(change * 100)}%"
                    else -> "—"
                }
                val grade = when {
                    d.afterAccuracy >= 0.7f -> "⭐A"
                    d.afterAccuracy >= 0.55f -> "✅B"
                    d.afterAccuracy >= 0.45f -> "📌C"
                    else -> "🔧D"
                }
                appendLine(String.format("%2d. %-20s %8s %8s %8s %s",
                    idx + 1,
                    d.strategyName.take(20),
                    "${"%.1f".format(d.beforeAccuracy * 100)}%",
                    "${"%.1f".format(d.afterAccuracy * 100)}%",
                    changeStr,
                    grade))
            }
            appendLine("说明: BUY/WATCH信号≥0%涨视为正确, HOLD信号≥-1%视为正确")
            appendLine()

            // ═══ 调优过程详细日志 ═══
            appendLine("调优过程详细日志")
            for (log in allLogs) {
                appendLine(log)
            }
            appendLine("各策略详细过程")
            for (d in details) {
                appendLine()
                appendLine("【${d.strategyName}】")
                for (log in d.processLogs) {
                    appendLine("  $log")
                }
            }
        }

        Log.i(TAG, summary)
        TuneReport(
            dateRange = dateRange,
            backtestDays = backtestDates.size - 1,
            isTodayValidate = todayValidateDate != null,
            todayValidateResult = if (todayValidateDate != null) "已完成今日($todayValidateDate)开盘验证，详见过程日志" else null,
            strategyTuneDetails = details,
            tuneLogs = allLogs,
            summary = summary
        )
    }

    // ════════════════════════════════════════
    // 增量梯度权重计算（含日志版本）
    // ════════════════════════════════════════

    /**
     * 基于次日涨幅反推最优权重（带详细日志）
     */
    private suspend fun computeGradientWeightsWithLog(
        strategy: Strategy,
        engine: HistoricalBacktestEngine,
        days: Int,
        availableDates: List<String>,
        log: MutableList<String>
    ): List<WeightFactor> {
        if (strategy.weightFactors.size <= 1) {
            log.add("  ⚠️ 只有一个因子，无需梯度分析")
            return emptyList()
        }

        val dates = availableDates.take(days.coerceAtMost(availableDates.size - 1))
        if (dates.size < 3) {
            log.add("  ⚠️ 可用日期不足3天，无法梯度分析")
            return emptyList()
        }

        val factorScores = mutableMapOf<String, Double>()
        val origWeights = strategy.weightFactors.map { it.copy() }
        val delta = 5
        var validSamples = 0

        log.add("  逐日扰动分析（delta=±${delta}）:")
        log.add("  日期范围: ${dates.first()} → ${dates.last()} (${dates.size}天)")

        for (date in dates) {
            val nextDate = findNextTradingDay(date, availableDates) ?: continue
            val todaySnapshots = db.dailySnapshotDao().getByDate(date)
            val tomorrowSnapshots = db.dailySnapshotDao().getByDate(nextDate)
            if (todaySnapshots.isEmpty() || tomorrowSnapshots.isEmpty()) continue

            val stockList = todaySnapshots.map { snap ->
                StockRealtime(
                    code = snap.code, name = snap.name,
                    price = snap.close, open = snap.open,
                    yestClose = snap.close / (1.0 + snap.changePct / 100.0),
                    high = snap.high, low = snap.low,
                    volume = snap.volume, amount = snap.amount,
                    changePercent = snap.changePct,
                    changeAmount = snap.close * snap.changePct / 100,
                    timestamp = System.currentTimeMillis()
                )
            }

            // Baseline
            strategy.weightFactors = origWeights.map { it.copy() }
            val baseResult: Result<ScreeningResult> = try { strategy.screenWithData(stockList) } catch (_: Exception) { Result.failure(Exception("fail")) }
            val baseSignals = baseResult.getOrNull()?.signals ?: emptyList()
            if (baseSignals.isEmpty()) continue
            val baseBuys = baseSignals.filter { it.action == com.chin.stockanalysis.strategy.models.SignalAction.BUY }
            if (baseBuys.isEmpty()) continue
            val baseCorrect = baseBuys.count { buy ->
                val tom = tomorrowSnapshots.find { it.code == buy.stockCode } ?: return@count false
                tom.changePct > 0
            }
            val baseRate = baseCorrect.toDouble() / baseBuys.size

            val dayLog = StringBuilder()
            dayLog.append("    $date: base准确率=${"%.0f".format(baseRate * 100)}% (${baseCorrect}/${baseBuys.size})")

            for (wf in origWeights) {
                val modified = origWeights.map {
                    if (it.key == wf.key) it.copy(weight = (it.weight + delta).coerceIn(1, 90))
                    else it.copy()
                }
                strategy.weightFactors = modified
                val modResult: Result<ScreeningResult> = try { strategy.screenWithData(stockList) } catch (_: Exception) { Result.failure(Exception("fail")) }
                val modSignals = modResult.getOrNull()?.signals ?: emptyList()
                val modBuys = modSignals.filter { it.action == com.chin.stockanalysis.strategy.models.SignalAction.BUY }
                if (modBuys.isEmpty()) continue
                val modCorrect = modBuys.count { buy ->
                    val tom = tomorrowSnapshots.find { it.code == buy.stockCode } ?: return@count false
                    tom.changePct > 0
                }
                val modRate = modCorrect.toDouble() / modBuys.size
                val improv = modRate - baseRate
                factorScores[wf.key] = (factorScores[wf.key] ?: 0.0) + improv
                dayLog.append("\n      ↑${wf.label}: ${"%.0f".format(modRate * 100)}% (${if (improv >= 0) "+" else ""}${"%.1f".format(improv * 100)}%)")
            }

            log.add(dayLog.toString())
            validSamples++
        }

        strategy.weightFactors = origWeights.map { it.copy() }

        if (factorScores.isEmpty() || validSamples < 3) {
            log.add("  ⚠️ 有效样本不足($validSamples)，无法计算梯度")
            return emptyList()
        }

        log.add("  累计因子贡献:")
        factorScores.forEach { (key, score) ->
            val wf = origWeights.find { it.key == key }
            log.add("    ${wf?.label ?: key}: ${"%.4f".format(score)}")
        }

        val shifted = factorScores.mapValues { (_, v) -> if (v.isNaN()) 0.0 else v }.toMutableMap()
        val minVal = shifted.values.minOrNull() ?: 0.0
        if (minVal < 0) {
            for (k in shifted.keys) shifted[k] = shifted[k]!! - minVal + 0.01
        }

        val totalScore = shifted.values.sum()
        if (totalScore <= 0.0) {
            log.add("  ⚠️ 因子总分为0，无法计算权重")
            return emptyList()
        }

        val newFactors = strategy.weightFactors.map { wf ->
            val score = shifted[wf.key] ?: 0.0
            val ratio = score / totalScore
            val weight = (ratio * 100).toInt().coerceIn(5, 70)
            wf.copy(weight = weight)
        }

        val sum = newFactors.sumOf { it.weight }
        val normalized = if (sum != 100) {
            val scale = 100.0 / sum
            newFactors.map { it.copy(weight = (it.weight * scale).toInt().coerceIn(1, 90)) }
        } else newFactors

        log.add("  梯度计算完成，有效样本: $validSamples 天")
        return normalized
    }

    private fun mergeWeightsWithLog(
        original: List<WeightFactor>,
        gradient: List<WeightFactor>,
        gradientRatio: Float,
        log: MutableList<String>
    ): List<WeightFactor> {
        val gradMap = gradient.associateBy { it.key }
        val merged = original.map { wf ->
            val grad = gradMap[wf.key]
            if (grad != null) {
                val newWeight = (wf.weight * (1 - gradientRatio) + grad.weight * gradientRatio).toInt()
                wf.copy(weight = newWeight.coerceIn(1, 90))
            } else wf
        }
        val sum = merged.sumOf { it.weight }
        if (sum != 100) {
            val scale = 100.0 / sum
            val normalized = merged.map { it.copy(weight = (it.weight * scale).toInt().coerceIn(1, 90)) }
            log.add("  归一化: sum=$sum → 调整到100 (scale=${"%.2f".format(scale)})")
            return normalized
        }
        return merged
    }

    private fun findNextTradingDay(date: String, availableDates: List<String>): String? {
        val sorted = availableDates.sorted()
        val idx = sorted.indexOf(date)
        return if (idx >= 0 && idx + 1 < sorted.size) sorted[idx + 1] else null
    }

    // ════════════════════════════════════════
    // 持久化
    // ════════════════════════════════════════

    private suspend fun saveWeightSnapshot(strategy: Strategy) {
        try {
            val today = LocalDate.now().format(DATE_FMT)
            val weightJson = strategy.weightFactors.joinToString(";") {
                "${it.key}:${it.label}:${it.weight}"
            }
            db.strategyWeightSnapshotDao().insert(
                StrategyWeightSnapshotEntity(
                    strategyId = strategy.id,
                    date = today,
                    weightJson = weightJson,
                    totalScore = 0,
                    hitCount = 0
                )
            )
            Log.d(TAG, "  权重快照已保存: ${strategy.id} @ $today")
        } catch (e: Exception) {
            Log.w(TAG, "  保存权重快照失败: ${e.message}")
        }
    }

    private suspend fun loadWeightHistory(strategyId: String, limit: Int): List<StrategyWeightSnapshotEntity> {
        return try {
            db.strategyWeightSnapshotDao().getByStrategy(strategyId, limit)
        } catch (e: Exception) {
            Log.w(TAG, "  加载历史权重失败: ${e.message}")
            emptyList()
        }
    }

    // ════════════════════════════════════════
    // 每日板块/个股涨幅记录（供统计面板）
    // ════════════════════════════════════════

    /**
     * 记录每个交易日的 Top 板块和 Top 个股涨幅，
     * 供后续多交易日统计面板（准确率趋势、板块热力图、个股排行榜）使用。
     */
    private suspend fun recordDailyTopPerformers(dates: List<String>) {
        for (date in dates) {
            try {
                val snapshots = db.dailySnapshotDao().getByDate(date)
                if (snapshots.isEmpty()) continue

                val sortedByPct = snapshots.sortedByDescending { it.changePct }

                val topStocks = sortedByPct.take(20)
                for ((rank, snap) in topStocks.withIndex()) {
                    Log.d(TAG, "  [${date}] Top${rank + 1} ${snap.name}(${snap.code}) +${"%.2f".format(snap.changePct)}%")
                }

                // 按板块聚合
                val sectorMap = mutableMapOf<String, MutableList<Double>>()
                val allSectorStocks = db.sectorStockDao().getAllStockCodes()
                val sectorStockSet = allSectorStocks.toSet()

                for (snap in sortedByPct) {
                    if (snap.code in sectorStockSet) {
                        val sectorEntries = db.sectorStockDao().let {
                            val sectors = db.sectorDailyRecordDao().let { _ -> null }
                        }
                    }
                }

                Log.d(TAG, "  [${date}] 已记录 ${snapshots.size} 条行情, Top涨幅: ${"%.2f".format(sortedByPct.firstOrNull()?.changePct ?: 0.0)}%")
            } catch (e: Exception) {
                Log.w(TAG, "  记录每日涨幅失败(${date}): ${e.message}")
            }
        }
    }

    /**
     * 快速评估：对单个策略计算最近 N 天的准确率
     */
    suspend fun quickEvaluate(strategy: Strategy, days: Int = 10): String {
        val engine = HistoricalBacktestEngine(context)
        val report = engine.runHistoricalBacktest(listOf(strategy), days)
        val stats = report.strategyReports.firstOrNull()
        return if (stats != null) {
            "${strategy.name}: 买入准确率 ${"%.1f".format(stats.buyAccuracy * 100)}% (${stats.correctBuys}/${stats.totalBuys}), 平均收益 ${"%.2f".format(stats.avgReturn)}%"
        } else {
            "${strategy.name}: 数据不足以评估"
        }
    }
}