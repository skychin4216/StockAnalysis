package com.chin.stockanalysis.agent.v2

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.agent.stock.StockAnalysisAgent
import com.chin.stockanalysis.agent.stock.StockAnalysisResult
import com.chin.stockanalysis.stock.data.StockDataFacade
import com.chin.stockanalysis.strategy.market.MarketAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * ## V2.0 全周期投研与交易决策系统运行入口
 *
 * 在 V1.0（基本面分析 + 估值定价 + Pipeline/Quick Agent）基础上，新增：
 * - 市场环境量化分类（上升/震荡/下跌）→ 仓位水阀
 * - 利润质量剪刀差分析 → 区分内生性增长 vs 一次性浮盈
 * - 决策矩阵 → 环境 × 利润质量 × 估值 → 最终操作指令
 *
 * 公共模块复用：
 * - MarketAnalyzer（已有）→ 市场环境判断
 * - StockDataFacade（已有）→ 实时行情 + 历史数据
 * - QuarterlyComparisonProvider（已有）→ 季度财报数据
 * - StockAnalysisAgent（已有）→ AI 综合分析
 */
object V2AgentRunner {

    private const val TAG = "V2AgentRunner"

    /**
     * 运行 V2.0 全周期分析
     *
     * 并行执行：
     * 1. 市场环境分析（MarketAnalyzer）
     * 2. 利润质量分析（ProfitQualityAnalyzer）
     * 3. AI 综合分析（StockAnalysisAgent）
     *
     * 然后融合：
     * 4. 仓位水阀（PositionWaterValve）
     * 5. 决策矩阵（V2DecisionMatrix）
     */
    suspend fun run(
        context: Context,
        stockCode: String,
        stockName: String? = null
    ): V2Result = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        try {
            coroutineScope {
                // 并行启动三个分析任务
                val marketDeferred = async {
                    try {
                        MarketAnalyzer.analyze(context.applicationContext, emptyList())
                    } catch (e: Exception) {
                        Log.w(TAG, "市場分析失敗: ${e.message}")
                        null
                    }
                }

                val profitQualityDeferred = async {
                    ProfitQualityAnalyzer.analyze(stockCode)
                }

                val aiAnalysisDeferred = async {
                    try {
                        StockAnalysisAgent(context).analyze(stockCode, stockName)
                    } catch (e: Exception) {
                        Log.w(TAG, "AI分析失敗: ${e.message}")
                        StockAnalysisResult(
                            success = false,
                            stockCode = stockCode,
                            rawOutput = "AI分析失敗: ${e.message}"
                        )
                    }
                }

                // 等待所有任务完成
                val marketReport = marketDeferred.await()
                val profitQuality = profitQualityDeferred.await()
                val aiResult = aiAnalysisDeferred.await()

                // 获取实时数据用于估值
                val quote = try {
                    StockDataFacade.getInstance(context)
                        .getAnalysisData(stockCode).quote
                } catch (_: Exception) { null }

                // 计算仓位水阀
                val positionCap = marketReport?.let {
                    PositionWaterValve.calculatePositionCap(it)
                } ?: PositionCapResult(50, "保守觀望", "市場數據不足，建議控制倉位", false)

                // 构建市场环境对象
                val marketEnv = marketReport?.let {
                    MarketEnvironment(
                        direction = it.trend.direction,
                        strength = it.trend.strength,
                        description = it.trend.description,
                        sellType = it.sellType.sellType
                    )
                } ?: MarketEnvironment("OSCILLATION", 30, "數據不足，默認震蕩", "NONE")

                // 估值评估
                val pe = quote?.pe ?: 0.0
                val pb = quote?.pb ?: 0.0
                val peBand = when {
                    pe <= 0 -> "無法評估"
                    pe < 20 -> "低估"
                    pe < 40 -> "合理"
                    else -> "高估"
                }
                val valuationWarning = if (profitQuality.qualityLevel == ProfitQualityLevel.ONE_TIME_PROFIT && pe > 0 && pe < 20) {
                    "⚠️ 市盈率陷阱：一次性浮盈導致靜態PE極低，建議使用扣非PE重新估值"
                } else null

                val valuation = ValuationAssessment(
                    pe = pe,
                    pb = pb,
                    peBandPosition = peBand,
                    valuationWarning = valuationWarning
                )

                // 决策矩阵
                val decision = V2DecisionMatrix.evaluate(
                    V2DecisionMatrix.DecisionInput(
                        environment = marketEnv,
                        positionCap = positionCap.capPercent,
                        profitQuality = profitQuality,
                        pe = pe,
                        pb = pb
                    )
                )

                // 生成 summaryText
                val summaryText = buildV2Summary(
                    stockCode, stockName,
                    marketEnv, positionCap,
                    profitQuality, valuation, decision,
                    aiResult
                )

                val elapsed = System.currentTimeMillis() - startTime

                Log.i(TAG, "V2.0分析完成: $stockCode, 耗時=${elapsed}ms, 決策=${decision.action}")

                V2Result(
                    stockCode = stockCode,
                    stockName = stockName ?: stockCode,
                    success = true,
                    marketEnvironment = marketEnv,
                    positionCap = positionCap.capPercent,
                    profitQuality = profitQuality,
                    valuation = valuation,
                    finalDecision = decision,
                    overallScore = aiResult.overallScore,
                    recommendation = aiResult.recommendation,
                    confidence = aiResult.confidence,
                    riskLevel = if (decision.riskWarning != null) "HIGH" else aiResult.confidence,
                    targetPrice = aiResult.targetPrice,
                    stopLoss = aiResult.stopLoss,
                    summaryText = summaryText,
                    rawOutput = aiResult.rawOutput,
                    elapsedMs = elapsed
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "V2.0分析異常: ${e.message}")
            V2Result(
                stockCode = stockCode,
                stockName = stockName ?: stockCode,
                success = false,
                marketEnvironment = MarketEnvironment("OSCILLATION", 30, "分析異常", "NONE"),
                positionCap = 30,
                profitQuality = ProfitQualityAnalysis(
                    qualityLevel = ProfitQualityLevel.INSUFFICIENT_DATA,
                    netProfit = 0.0, deductNetProfit = 0.0,
                    profitGap = 0.0, profitGapRatio = 0.0,
                    operatingCashFlowRatio = 0.0,
                    revenueYoY = 0.0, netProfitYoY = 0.0, deductNpYoY = 0.0,
                    grossMargin = 0.0, roe = 0.0,
                    warningFlags = listOf("⚠️ V2.0分析異常: ${e.message}"),
                    reportDate = ""
                ),
                valuation = ValuationAssessment(0.0, 0.0, "無法評估"),
                finalDecision = FinalDecision(
                    action = "觀望",
                    positionPercent = 0,
                    strategy = "系統異常，暫不操作",
                    stopLossRule = "暫停交易"
                ),
                errorMessage = e.message,
                elapsedMs = System.currentTimeMillis() - startTime
            )
        }
    }

    /**
     * 构建 V2.0 分析摘要文本（Markdown 格式）
     */
    private fun buildV2Summary(
        stockCode: String,
        stockName: String?,
        marketEnv: MarketEnvironment,
        positionCap: PositionCapResult,
        profitQuality: ProfitQualityAnalysis,
        valuation: ValuationAssessment,
        decision: FinalDecision,
        aiResult: StockAnalysisResult
    ): String {
        return buildString {
            appendLine("# 📊 V2.0 全周期投研決策報告")
            appendLine()
            appendLine("**股票**: ${stockName ?: stockCode}（$stockCode）")
            appendLine()

            // 市场环境
            appendLine("## 🌍 一、市場環境（頂層戰略）")
            val envEmoji = when (marketEnv.direction) {
                "BULLISH" -> "🚀"
                "BEARISH" -> "📉"
                else -> "〰️"
            }
            appendLine("$envEmoji 環境: ${marketEnv.description}（強度${marketEnv.strength}/100）")
            appendLine("💧 倉位上限: **${positionCap.capPercent}%**")
            appendLine("📌 策略基調: ${positionCap.strategy}")
            appendLine()

            // 利润质量
            appendLine("## 💰 二、利潤質量（中層戰術）")
            val qualityEmoji = when (profitQuality.qualityLevel) {
                ProfitQualityLevel.ENDOGENOUS_GROWTH -> "🟢"
                ProfitQualityLevel.ONE_TIME_PROFIT -> "🟡"
                ProfitQualityLevel.PROFIT_INFLATION -> "🔴"
                ProfitQualityLevel.INSUFFICIENT_DATA -> "⚪"
            }
            val qualityLabel = when (profitQuality.qualityLevel) {
                ProfitQualityLevel.ENDOGENOUS_GROWTH -> "內生性高增（真成長）"
                ProfitQualityLevel.ONE_TIME_PROFIT -> "一次性浮盈（紙面富貴）"
                ProfitQualityLevel.PROFIT_INFLATION -> "利潤注水（質量堪憂）"
                ProfitQualityLevel.INSUFFICIENT_DATA -> "數據不足"
            }
            appendLine("$qualityEmoji 質量等級: **$qualityLabel**")
            if (profitQuality.reportDate.isNotEmpty()) {
                appendLine("📅 報告期: ${profitQuality.reportDate}")
            }
            if (profitQuality.netProfit > 0) {
                appendLine("💵 淨利潤: ${String.format("%.2f", profitQuality.netProfit)}億")
                appendLine("💵 扣非淨利潤: ${String.format("%.2f", profitQuality.deductNetProfit)}億")
                appendLine("✂️ 剪刀差: ${String.format("%.2f", profitQuality.profitGap)}億 (${String.format("%.1f", profitQuality.profitGapRatio * 100)}%)")
            }
            if (profitQuality.revenueYoY != 0.0) {
                appendLine("📈 營收同比: ${String.format("%.1f", profitQuality.revenueYoY)}%")
            }
            if (profitQuality.netProfitYoY != 0.0) {
                appendLine("📈 淨利潤同比: ${String.format("%.1f", profitQuality.netProfitYoY)}%")
            }
            if (profitQuality.grossMargin > 0) {
                appendLine("📊 毛利率: ${String.format("%.1f", profitQuality.grossMargin)}%")
            }
            if (profitQuality.roe > 0) {
                appendLine("📊 ROE: ${String.format("%.1f", profitQuality.roe)}%")
            }
            if (profitQuality.operatingCashFlowRatio > 0) {
                appendLine("💧 經營現金流覆蓋率: ${String.format("%.1f", profitQuality.operatingCashFlowRatio * 100)}%")
            }
            if (profitQuality.warningFlags.isNotEmpty()) {
                appendLine()
                appendLine("⚠️ **警告標籤**:")
                profitQuality.warningFlags.forEach { appendLine("  - $it") }
            }
            appendLine()

            // 估值
            appendLine("## 📈 三、估值評估")
            appendLine("PE: ${if (valuation.pe > 0) String.format("%.2f", valuation.pe) else "--"}")
            appendLine("PB: ${if (valuation.pb > 0) String.format("%.2f", valuation.pb) else "--"}")
            appendLine("估值分位: ${valuation.peBandPosition}")
            if (valuation.valuationWarning != null) {
                appendLine()
                appendLine("🚨 ${valuation.valuationWarning}")
            }
            appendLine()

            // 最终决策
            appendLine("## 🎯 四、最終決策（V2.0融合引擎）")
            appendLine()
            appendLine("**建議操作: ${decision.action}**")
            appendLine("**建議倉位: ${decision.positionPercent}%**（上限${positionCap.capPercent}%）")
            appendLine()
            appendLine("📋 策略: ${decision.strategy}")
            if (decision.tTradingAdvice != null) {
                appendLine()
                appendLine("🔄 做T建議: ${decision.tTradingAdvice}")
            }
            appendLine()
            appendLine("🛡 止損規則: ${decision.stopLossRule}")
            if (decision.riskWarning != null) {
                appendLine()
                appendLine("⚠️ 風險提示: ${decision.riskWarning}")
            }
            appendLine()

            // AI 综合分析（V1.0 兼容）
            if (aiResult.success) {
                appendLine("---")
                appendLine("## 🤖 AI 綜合分析（V1.0 參考）")
                appendLine("評分: ${aiResult.overallScore}/100")
                appendLine("建議: ${aiResult.recommendation}（置信度: ${aiResult.confidence}）")
                if (aiResult.targetPrice.isNotEmpty()) {
                    appendLine("目標價: ${aiResult.targetPrice}")
                }
                if (aiResult.stopLoss.isNotEmpty()) {
                    appendLine("止損位: ${aiResult.stopLoss}")
                }
                if (aiResult.riskFactors.isNotEmpty()) {
                    appendLine()
                    appendLine("風險因素:")
                    aiResult.riskFactors.forEach { appendLine("  - $it") }
                }
            }
        }
    }
}
