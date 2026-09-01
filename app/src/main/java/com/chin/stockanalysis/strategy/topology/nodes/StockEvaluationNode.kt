package com.chin.stockanalysis.strategy.topology.nodes

import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.topology.core.*
import com.chin.stockanalysis.strategy.topology.pipelines.StockCheckPipeline
import com.chin.stockanalysis.strategy.topology.pipelines.StockCheckPipeline.Companion.AnalysisMode

/**
 * ## 个股评估检查节点（均线多头粘合选股）
 *
 * 对 MergedSignalPool 中的每只股票运行 StockCheckPipeline，
 * 基于粘合度为核心的多项检查，不同周期通过 XML 配不同参数。
 */
data class StockEvaluationResult(
    val passedStocks: Map<String, StockEvaluationDetail> = emptyMap(),
    val totalCount: Int = 0,
    val passedCount: Int = 0
)

data class StockEvaluationDetail(
    val code: String,
    val name: String,
    /** 粘合度（%） */
    val convergenceDegree: Double = 999.0,
    /** 粘合度达标 */
    val convergenceOk: Boolean = false,
    /** 多头排列 */
    val bullishAligned: Boolean = false,
    /** 粘合持续天数达标 */
    val convergenceDurationOk: Boolean = false,
    /** 量能条件达标 */
    val volumeConditionOk: Boolean = false,
    /** 距高点跌幅达标 */
    val drawdownOk: Boolean = false,
    /** MA60 上升 */
    val ma60Rising: Boolean = false,
    /** MA250 上升（长线） */
    val ma250Rising: Boolean = true,
    /** 收盘远离粘合区上沿 >2%（超短） */
    val closeAboveConvergenceTop: Boolean = true,
    /** 开盘低于三线且收盘站上5日线（超短） */
    val openBelowMAs: Boolean = true,
    /** 站稳年线 */
    val aboveYearLine: Boolean = false,
    /** 涨幅达标 */
    val changePctOk: Boolean = true,
    /** 站上所有均线 */
    val aboveAllMAs: Boolean = true,
    /** 通过项数 */
    val passCount: Int = 0,
    /** 本周期适用检查总数 */
    val totalChecks: Int = 7,
    /** 是否通过 */
    val allPassed: Boolean = false
)

class StockEvaluationNode(
    val convergenceThreshold: Double = 2.5,
    val useMA60: Boolean = true,
    val convergenceDurationDays: Int = 15,
    val convergenceDurationRatio: Double = 0.8,
    val volumeBreakoutRatio: Double = 1.5,
    val minChangePct: Double = 0.0,
    val requireChangePct: Boolean = false,
    val minDrawdownPct: Double = 30.0,
    val requireMA60Rising: Boolean = true,
    val maRisingDays: Int = 5,
    val requireMA250Rising: Boolean = false,
    val useMA250InBullish: Boolean = false,
    val requireCloseAboveConvergenceTop: Boolean = false,
    val requireOpenBelowMAs: Boolean = false,
    val requireVolumeShrink: Boolean = false,
    val requireAboveYearLine: Boolean = false,
    val requireAboveAllMAs: Boolean = true,
    val moderateVolumeLower: Double = 1.2,
    val moderateVolumeUpper: Double = 1.8,
    val lookbackDays: Int = 120,
    val minPassCount: Int = 7,
    /** v9: 低位埋伏模式（中长线）：均线粘合+三日不新低即可通过 */
    val allowLowAmbush: Boolean = false,
    /** v9: 缩量缓涨允许（超短/短线）：量能放宽 */
    val allowQuietRise: Boolean = false,
    /** v9: 缩量缓涨最低量比 */
    val quietVolumeRatio: Double = 1.0,
    /** v9: 宏观偏好板块关键词（油价≥80化工 / 国债低高股息） */
    val macroSectorKeywords: List<String> = emptyList(),
    /** 本节点服务的周期（ultra_short / short / mid / long），决定牛市时是否启用趋势跟随模式（B11） */
    val period: String = ""
) : BaseNode<MergedSignalPool, MergedSignalPool>("strict_selection", "均线粘合严选", NodeType.FILTER) {

    companion object {
        private const val TAG = "StockEvaluation"
    }

    /** 超短/短线在牛市启用趋势跟随（对应 StockCheckPipeline.ultraShortParams/shortTermParams），否则恒走均线粘合 */
    private fun resolveMode(marketTrend: String?): AnalysisMode {
        val trendFollow = period in setOf("ultra_short", "short") &&
            marketTrend?.contains("BULL", ignoreCase = true) == true
        return if (trendFollow) AnalysisMode.TREND_FOLLOW else AnalysisMode.CONVERGENCE
    }

    /** 构建 StockCheckPipeline，注入大盘趋势（运行时从 context 获取） */
    private fun buildPipeline(marketTrend: String? = null) = StockCheckPipeline(
        convergenceThreshold = convergenceThreshold,
        useMA60 = useMA60,
        convergenceDurationDays = convergenceDurationDays,
        convergenceDurationRatio = convergenceDurationRatio,
        volumeBreakoutRatio = volumeBreakoutRatio,
        minChangePct = minChangePct,
        requireChangePct = requireChangePct,
        minDrawdownPct = minDrawdownPct,
        requireMA60Rising = requireMA60Rising,
        maRisingDays = maRisingDays,
        requireMA250Rising = requireMA250Rising,
        useMA250InBullish = useMA250InBullish,
        requireCloseAboveConvergenceTop = requireCloseAboveConvergenceTop,
        requireOpenBelowMAs = requireOpenBelowMAs,
        requireVolumeShrink = requireVolumeShrink,
        requireAboveYearLine = requireAboveYearLine,
        requireAboveAllMAs = requireAboveAllMAs,
        moderateVolumeLower = moderateVolumeLower,
        moderateVolumeUpper = moderateVolumeUpper,
        lookbackDays = lookbackDays,
        minPassCount = minPassCount,
        marketTrend = marketTrend,
        mode = resolveMode(marketTrend),
        requireThreeDayConfirm = true,
        allowLowAmbush = allowLowAmbush,
        allowQuietRise = allowQuietRise,
        quietVolumeRatio = quietVolumeRatio,
        macroSectorKeywords = macroSectorKeywords
    )

    override suspend fun execute(context: PipelineContext, input: MergedSignalPool): MergedSignalPool {
        // 从上下文获取大盘趋势，注入 pipeline 实现动态参数调整
        val marketTrend = try {
            val marketCtx = context.getStageOutput<com.chin.stockanalysis.strategy.sector.StrategyMarketContext>("n_ctx")
                ?: context.getStageOutput<com.chin.stockanalysis.strategy.sector.StrategyMarketContext>("market_context")
            marketCtx?.indexSnapshot?.tripleVote ?: marketCtx?.indexSnapshot?.shDirection
        } catch (_: Exception) { null }

        val pipeline = buildPipeline(marketTrend)
        if (marketTrend != null) {
            context.log(nodeId, "ℹ️ 大盘趋势: $marketTrend → 模式:${pipeline.mode}（周期:$period）" +
                if (pipeline.mode == AnalysisMode.TREND_FOLLOW) "，超短/短线启用趋势跟随" else "，均线粘合严选")
        }
        // v9/v10: 宏观因子自动注入（app_config.json → macro_environment）
        //   油价>85且上涨趋势 → 化工板块低位埋伏优先（80仅为中高位，不足为强信号）
        //   国债利率低 → 高股息(银行/煤炭/化工)优先
        try {
            val cfg = com.chin.stockanalysis.config.DataConfig
            val oilPrice = cfg.get("macro_environment.oil_price", "0").toDoubleOrNull() ?: 0.0
            val oilHighThreshold = cfg.get("macro_environment.oil_price_high", "85").toDoubleOrNull() ?: 85.0
            val oilTrendRising = cfg.get("macro_environment.oil_trend_rising", "true").toBooleanStrictOrNull() ?: true
            val oilHigh = oilPrice > oilHighThreshold && oilTrendRising
            val cnYieldLow = (cfg
                .get("macro_environment.cn_10y_yield_low", "2.2").toDoubleOrNull() ?: 2.2) >=
                (cfg.get("macro_environment.cn_10y_yield_pct", "0").toDoubleOrNull() ?: 0.0)
            val macroSectors = pipeline.macroSectorKeywords.toMutableList()
            if (oilHigh) macroSectors += listOf("化工", "石油", "煤炭")
            if (cnYieldLow) macroSectors += listOf("银行", "电力", "保险", "煤炭", "化工")
            if (macroSectors.isNotEmpty() && macroSectors != pipeline.macroSectorKeywords) {
                pipeline.macroSectorKeywords = macroSectors.distinct()
                context.log(nodeId, "🧭 宏观因子注入: 油价${oilPrice}${if (oilHigh) ">$oilHighThreshold↑" else "≤$oilHighThreshold"} 国债${if (cnYieldLow) "低" else "高"} → 偏好板块:${pipeline.macroSectorKeywords.joinToString("、")}")
            }
        } catch (_: Exception) {}
        // v10: 大盘量能注入——上证指数当日量/前5日均量（<1 大盘缩量，个股量能阈值动态下调，
        // 避免大盘缩量导致个股缩量而被误杀。大盘优于个股。）
        try {
            val db = com.chin.stockanalysis.stock.database.StockDatabase.getInstance(context.androidContext)
            val idxSnaps = db.dailySnapshotDao().getByCode("sh000001", 60).sortedBy { it.date }
            if (idxSnaps.size >= 6) {
                val vol5Avg = idxSnaps.takeLast(6).dropLast(1)
                    .map { it.volume.toDouble() }.average()
                if (vol5Avg > 0) {
                    pipeline.marketVolumeRatio = idxSnaps.last().volume / vol5Avg
                    context.log(nodeId, "📊 大盘量比: ${"%.2f".format(pipeline.marketVolumeRatio!!)}" +
                        if ((pipeline.marketVolumeRatio ?: 1.0) < 1.0) "（大盘缩量→个股量能阈值下调）" else "")
                }
            }
        } catch (_: Exception) {}

        val passedMap = mutableMapOf<String, StockEvaluationDetail>()
        var insufficientCount = 0
        var errorCount = 0
        val insufficientCodes = mutableListOf<String>()
        val errorCodes = mutableListOf<String>()

        for ((code, _) in input.stockHits) {
            try {
                val result = pipeline.analyze(context.androidContext, code)
                if (result.stockName == "数据不足") {
                    insufficientCount++
                    insufficientCodes.add(code)
                    continue
                }
                if (result.stockName == "异常") {
                    errorCount++
                    errorCodes.add(code)
                    continue
                }

                val name = input.stockNames[code] ?: result.stockName

                val detail = StockEvaluationDetail(
                    code = code,
                    name = name,
                    convergenceDegree = result.convergenceDegree,
                    convergenceOk = result.convergenceOk,
                    bullishAligned = result.bullishAligned,
                    convergenceDurationOk = result.convergenceDurationOk,
                    volumeConditionOk = result.volumeConditionOk,
                    drawdownOk = result.drawdownOk,
                    ma60Rising = result.ma60Rising,
                    ma250Rising = result.ma250Rising,
                    closeAboveConvergenceTop = result.closeAboveConvergenceTop,
                    openBelowMAs = result.openBelowMAs,
                    aboveYearLine = result.aboveYearLine,
                    changePctOk = result.changePctOk,
                    aboveAllMAs = result.aboveAllMAs,
                    passCount = result.passCount,
                    totalChecks = result.totalChecks,
                    allPassed = result.passed
                )

                if (result.passed) {
                    passedMap[code] = detail
                }
            } catch (e: Exception) {
                errorCount++
                errorCodes.add(code)
                context.log(nodeId, "⚠ $nodeName 单股分析异常: $code - ${e.message}")
            }
        }

        // 诊断：候选全部被过滤时，输出失败原因，避免「输入 10 只、输出 0 只」无从查证
        val inputCount = input.stockHits.size
        if (inputCount > 0 && passedMap.isEmpty()) {
            val reasonParts = buildList {
                if (insufficientCount > 0) add("数据不足 $insufficientCount 只(${insufficientCodes.joinToString(",")})")
                if (errorCount > 0) add("异常 $errorCount 只(${errorCodes.joinToString(",")})")
                val otherCount = inputCount - insufficientCount - errorCount
                if (otherCount > 0) add("未达标 $otherCount 只")
                if (isEmpty()) add("无候选")
            }.joinToString("；")
            context.log(nodeId,
                "⛔ $nodeName 输出 0：$inputCount 只候选全部被过滤（$reasonParts）。" +
                    "建议检查上游候选股 K 线数据完整性（daily_snapshot ≥20 条）或放宽严选阈值")
        }

        val evalResult = StockEvaluationResult(
            passedStocks = passedMap,
            totalCount = input.stockHits.size,
            passedCount = passedMap.count { it.value.allPassed }
        )
        context.setStageOutput(nodeId + "_eval", evalResult)

        context.log(nodeId, "$nodeName: ${input.stockHits.size} 只候选 → " +
            "${passedMap.size} 只通过(≥${minPassCount}项)，${evalResult.passedCount} 只全部通过" +
            (if (insufficientCount > 0 || errorCount > 0)
                "（数据不足 $insufficientCount / 异常 $errorCount）" else ""))

        // 记录股票流动（fail-fast 熔断依赖 outputCount 感知输出 0）
        context.recordStockFlow(
            nodeId = nodeId,
            nodeName = nodeName,
            inputCount = inputCount,
            outputCount = passedMap.size,
            filterCount = inputCount - passedMap.size,
            filterReason = "数据不足$insufficientCount,异常$errorCount"
        )

        if (passedMap.isNotEmpty()) {
            val top3 = passedMap.values.sortedByDescending { it.passCount }.take(3)
            context.log(nodeId, "$nodeName TOP3: ${top3.joinToString { "${it.name}(${it.passCount}/${it.totalChecks})" }}")
        }

        val passedCodes = passedMap.keys
        // ── 双通道架构：粘合严选输出 0 时，根据大盘趋势决定是否透传 ──
        if (passedMap.isEmpty() && inputCount > 0) {
            val isBearish = marketTrend != null &&
                marketTrend.contains("BEAR", ignoreCase = true)
            if (isBearish) {
                // 熊市/下行：不勉强选股，提示用户关注实仓管理
                context.log(nodeId,
                    "🛑 $nodeName 大盘$marketTrend，通道A无合格标的，终止选股。" +
                    "建议重点优化实仓持仓：做T/反T降低成本，或逢高减仓控制风险")
                return MergedSignalPool(emptyMap(), emptyMap(), emptyList())
            } else {
                // 牛市/震荡：通道A无输出，候选流入通道B（趋势策略仍可处理）。
                // B12: 过滤数据不足/异常的候选，避免无效股票一并透传给趋势通道
                val invalidCodes = insufficientCodes.toSet() + errorCodes.toSet()
                val passHits = input.stockHits.filterKeys { it !in invalidCodes }
                val passNames = input.stockNames.filterKeys { it in passHits }
                val passSignals = input.boostedSignals.filter { it.stockCode in passHits }
                context.log(nodeId,
                    "ℹ️ $nodeName 大盘${marketTrend ?: "未知"}，通道A无候选通过，" +
                    "${passHits.size} 只有效候选透传给通道B趋势通道" +
                    (if (invalidCodes.isNotEmpty()) "（已过滤数据不足/异常 ${invalidCodes.size} 只）" else ""))
                if (passHits.isEmpty()) return MergedSignalPool(emptyMap(), emptyMap(), emptyList())
                return MergedSignalPool(passHits, passNames, passSignals)
            }
        }
        val filteredSignals = input.boostedSignals.filter { it.stockCode in passedCodes }
        val filteredHits = input.stockHits.filterKeys { it in passedCodes }
        val filteredNames = input.stockNames.filterKeys { it in passedCodes }

        return MergedSignalPool(filteredHits, filteredNames, filteredSignals)
    }
}

/**
 * ## 个股评估单股检查（供 Agent 分析流程调用）
 */
object StockEvaluationChecker {

    suspend fun evaluate(
        stockCode: String,
        db: StockDatabase,
        pipeline: StockCheckPipeline = StockCheckPipeline.midTermParams()
    ): StockEvaluationDetail {
        val result = pipeline.analyze(db, stockCode)
        if (result.stockName == "数据不足" || result.stockName == "异常") {
            return StockEvaluationDetail(code = stockCode, name = result.stockName, passCount = 0)
        }

        return StockEvaluationDetail(
            code = stockCode,
            name = result.stockName,
            convergenceDegree = result.convergenceDegree,
            convergenceOk = result.convergenceOk,
            bullishAligned = result.bullishAligned,
            convergenceDurationOk = result.convergenceDurationOk,
            volumeConditionOk = result.volumeConditionOk,
            drawdownOk = result.drawdownOk,
            ma60Rising = result.ma60Rising,
            ma250Rising = result.ma250Rising,
            closeAboveConvergenceTop = result.closeAboveConvergenceTop,
            openBelowMAs = result.openBelowMAs,
            aboveYearLine = result.aboveYearLine,
            changePctOk = result.changePctOk,
            aboveAllMAs = result.aboveAllMAs,
            passCount = result.passCount,
            totalChecks = result.totalChecks,
            allPassed = result.passed
        )
    }

    /** 格式化成可读摘要，供报告附加 */
    fun formatResult(detail: StockEvaluationDetail): String = buildString {
        val passed = detail.allPassed
        append(if (passed) "✅" else "⚠️")
        append(" 粘合选股: ${detail.passCount}/${detail.totalChecks}")
        if (detail.name.isNotBlank()) append(" (${detail.name})")
        appendLine()
        append("  ${if (detail.convergenceOk) "✓" else "✗"}粘合度${"%.1f".format(detail.convergenceDegree)}%")
        append("  ${if (detail.bullishAligned) "✓" else "✗"}多头排列")
        append("  ${if (detail.convergenceDurationOk) "✓" else "✗"}粘合持续")
        appendLine()
        append("  ${if (detail.volumeConditionOk) "✓" else "✗"}量能条件")
        append("  ${if (detail.drawdownOk) "✓" else "✗"}跌幅达标")
        append("  ${if (detail.ma60Rising) "✓" else "✗"}MA60上升")
        append("  ${if (detail.ma250Rising) "✓" else "✗"}MA250上升")
        append("  ${if (detail.closeAboveConvergenceTop) "✓" else "✗"}远离上沿")
        append("  ${if (detail.openBelowMAs) "✓" else "✗"}开盘条件")
        append("  ${if (detail.aboveYearLine) "✓" else "✗"}站稳年线")
        append("  ${if (detail.changePctOk) "✓" else "✗"}涨幅达标")
        append("  ${if (detail.aboveAllMAs) "✓" else "✗"}站上均线")
        if (!passed) appendLine("\n  ⚠️ 未达通过标准，建议观望")
    }
}
