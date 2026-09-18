package com.chin.stockanalysis.agent.v2

import android.util.Log
import com.chin.stockanalysis.agent.pipeline.QuarterlyComparisonProvider
import com.chin.stockanalysis.agent.pipeline.QuarterlyComparisonResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 利润质量分析器（V2.0 中层战术模块）
 *
 * 核心逻辑：解决"当前利润很高，但可能是利空"的问题
 * 通过"利润质量剪刀差"测试，区分内生性增长 vs 一次性浮盈
 */
object ProfitQualityAnalyzer {

    private const val TAG = "ProfitQualityAnalyzer"

    /**
     * 分析单只股票的利润质量
     */
    suspend fun analyze(stockCode: String): ProfitQualityAnalysis = withContext(Dispatchers.IO) {
        try {
            val qc = QuarterlyComparisonProvider.fetch(stockCode)
            if (!qc.hasData || qc.latest == null) {
                return@withContext ProfitQualityAnalysis(
                    qualityLevel = ProfitQualityLevel.INSUFFICIENT_DATA,
                    netProfit = 0.0, deductNetProfit = 0.0,
                    profitGap = 0.0, profitGapRatio = 0.0,
                    operatingCashFlowRatio = 0.0,
                    revenueYoY = 0.0, netProfitYoY = 0.0, deductNpYoY = 0.0,
                    grossMargin = 0.0, roe = 0.0,
                    warningFlags = listOf("⚠️ 季度财报数据不足，无法评估利润质量"),
                    reportDate = ""
                )
            }

            val latest = qc.latest
            val netProfit = latest.parentNetProfit / 100000000.0  // 转为亿元
            val deductNp = latest.deductNetProfit / 100000000.0
            val profitGap = netProfit - deductNp
            val profitGapRatio = if (netProfit != 0.0) profitGap / netProfit else 0.0
            val ocfRatio = latest.operatingCashFlowRatio

            val warnings = mutableListOf<String>()

            // 规则1：利润质量剪刀差（一次性利润识别）
            val qualityLevel = when {
                netProfit <= 0 || deductNp <= 0 -> {
                    warnings.add("⚠️ 净利润或扣非净利润为负，基本面恶化")
                    ProfitQualityLevel.PROFIT_INFLATION
                }
                profitGapRatio > 0.3 -> {
                    warnings.add("🔴 一次性非经常性损益占比 ${(profitGapRatio * 100).toInt()}%，纸面富贵")
                    ProfitQualityLevel.ONE_TIME_PROFIT
                }
                ocfRatio < 0.5 && ocfRatio > 0 -> {
                    warnings.add("🟡 经营现金流覆盖率仅 ${(ocfRatio * 100).toInt()}%，利润质量存疑")
                    ProfitQualityLevel.PROFIT_INFLATION
                }
                ocfRatio >= 0.8 && profitGapRatio < 0.1 -> {
                    warnings.add("🟢 现金流充裕 + 扣非净利润占比高，内生性增长")
                    ProfitQualityLevel.ENDOGENOUS_GROWTH
                }
                else -> {
                    warnings.add("📊 利润质量一般，需持续观察")
                    ProfitQualityLevel.ONE_TIME_PROFIT
                }
            }

            // 规则2：增速一致性检查
            if (latest.netProfitYoY > 50 && latest.revenueYoY < 10) {
                warnings.add("🔴 净利润暴增但营收微增，利润可能来自非主业")
            }
            if (latest.deductNpQoQ < -20 && qc.deductNpQoQ < -20) {
                warnings.add("🔴 扣非净利润连续下滑，主业承压")
            }

            Log.i(TAG, "利润质量分析: $stockCode → $qualityLevel, 剪刀差=${String.format("%.2f", profitGapRatio * 100)}%")

            ProfitQualityAnalysis(
                qualityLevel = qualityLevel,
                netProfit = netProfit,
                deductNetProfit = deductNp,
                profitGap = profitGap,
                profitGapRatio = profitGapRatio,
                operatingCashFlowRatio = ocfRatio,
                revenueYoY = latest.revenueYoY,
                netProfitYoY = latest.netProfitYoY,
                deductNpYoY = qc.deductNpQoQ,
                grossMargin = latest.grossMargin,
                roe = latest.roe,
                warningFlags = warnings,
                reportDate = latest.reportDate
            )
        } catch (e: Exception) {
            Log.w(TAG, "利润质量分析异常: ${e.message}")
            ProfitQualityAnalysis(
                qualityLevel = ProfitQualityLevel.INSUFFICIENT_DATA,
                netProfit = 0.0, deductNetProfit = 0.0,
                profitGap = 0.0, profitGapRatio = 0.0,
                operatingCashFlowRatio = 0.0,
                revenueYoY = 0.0, netProfitYoY = 0.0, deductNpYoY = 0.0,
                grossMargin = 0.0, roe = 0.0,
                warningFlags = listOf("⚠️ 分析异常: ${e.message}"),
                reportDate = ""
            )
        }
    }
}
