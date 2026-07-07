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
                    warningFlags = listOf("⚠️ 季度財報數據不足，無法評估利潤質量"),
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
                    warnings.add("⚠️ 淨利潤或扣非淨利潤為負，基本面惡化")
                    ProfitQualityLevel.PROFIT_INFLATION
                }
                profitGapRatio > 0.3 -> {
                    warnings.add("🔴 一次性非經常性損益佔比 ${(profitGapRatio * 100).toInt()}%，紙面富貴")
                    ProfitQualityLevel.ONE_TIME_PROFIT
                }
                ocfRatio < 0.5 && ocfRatio > 0 -> {
                    warnings.add("🟡 經營現金流覆蓋率僅 ${(ocfRatio * 100).toInt()}%，利潤質量存疑")
                    ProfitQualityLevel.PROFIT_INFLATION
                }
                ocfRatio >= 0.8 && profitGapRatio < 0.1 -> {
                    warnings.add("🟢 現金流充裕 + 扣非淨利潤佔比高，內生性增長")
                    ProfitQualityLevel.ENDOGENOUS_GROWTH
                }
                else -> {
                    warnings.add("📊 利潤質量一般，需持續觀察")
                    ProfitQualityLevel.ONE_TIME_PROFIT
                }
            }

            // 规则2：增速一致性检查
            if (latest.netProfitYoY > 50 && latest.revenueYoY < 10) {
                warnings.add("🔴 淨利潤暴增但營收微增，利潤可能來自非主業")
            }
            if (latest.deductNpQoQ < -20 && qc.deductNpQoQ < -20) {
                warnings.add("🔴 扣非淨利潤連續下滑，主業承壓")
            }

            Log.i(TAG, "利潤質量分析: $stockCode → $qualityLevel, 剪刀差=${String.format("%.2f", profitGapRatio * 100)}%")

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
            Log.w(TAG, "利潤質量分析異常: ${e.message}")
            ProfitQualityAnalysis(
                qualityLevel = ProfitQualityLevel.INSUFFICIENT_DATA,
                netProfit = 0.0, deductNetProfit = 0.0,
                profitGap = 0.0, profitGapRatio = 0.0,
                operatingCashFlowRatio = 0.0,
                revenueYoY = 0.0, netProfitYoY = 0.0, deductNpYoY = 0.0,
                grossMargin = 0.0, roe = 0.0,
                warningFlags = listOf("⚠️ 分析異常: ${e.message}"),
                reportDate = ""
            )
        }
    }
}
