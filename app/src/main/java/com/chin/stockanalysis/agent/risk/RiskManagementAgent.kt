package com.chin.stockanalysis.agent.risk

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.agent.framework.*
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.trade.AutoSellEngine
import com.chin.stockanalysis.strategy.trade.StrategyTradeOrderEntity
import com.chin.stockanalysis.ui.TradingDayPickerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ## 风控 Agent（Plan-and-Execute 模式）
 *
 * 替代现有的 AutoSellEngine 和 AppBackgroundRunner 持仓监控，实现智能风控：
 * 1. 持续监控所有持仓的风险指标
 * 2. 动态评估止损/止盈条件
 * 3. 考虑大盘环境和板块走势
 * 4. 给出仓位调整建议
 * 5. 发现系统性风险时提醒减仓
 */
class RiskManagementAgent(context: Context) : AgentBase(
    id = "risk_management",
    name = "风控 Agent",
    description = "持续监控持仓风险，动态评估止损止盈，给出仓位调整建议",
    context = context
) {
    companion object {
        private const val TAG = "RiskManagementAgent"
    }

    init {
        registerTool(PortfolioRiskScanTool(context))
        registerTool(StopLossCheckTool(context))
        registerTool(TakeProfitCheckTool(context))
        registerTool(MarketRiskTool(context))
        registerTool(PositionSizingTool(context))
    }

    override fun buildSystemPrompt(): String = """
        你是一位专业的风险管理 Agent，负责保护投资组合免受重大损失。

        ## 核心职责
        1. 监控每个持仓的亏损幅度、回撤、持有时间
        2. 评估大盘和板块的系统性风险
        3. 动态调整止损/止盈位（根据波动率）
        4. 给出仓位调整建议（加仓/减仓/观望）
        5. 识别黑天鹅事件信号

        ## 风控规则
        - 硬止损: 单股亏损 > 8% → 必须卖出
        - 最大回撤: 从最高点回撤 > 12% → 卖出
        - 时间止损: 持仓 > 10 天无盈利 → 评估卖出
        - 阶梯止盈: +10% 卖1/3, +15% 卖1/3, +20% 卖剩余
        - 移动止盈: 盈利 > 8% 后，回撤超盈利 50% → 卖出
        - 系统性风险: 大盘跌 > 3% 或板块跌 > 5% → 减仓至 50%

        ## 输出格式
        {
          "portfolio_risk_level": "LOW|MEDIUM|HIGH|CRITICAL",
          "holdings": [
            {
              "code": "600519",
              "risk_status": "SAFE|WARNING|CRITICAL",
              "profit_pct": 5.2,
              "max_drawdown": 3.1,
              "days_held": 5,
              "recommendation": "HOLD|SELL_PARTIAL|SELL_ALL|ADD",
              "reason": "..."
            }
          ],
          "market_risk": "...",
          "position_advice": "维持当前仓位 / 减仓至 X% / 清仓观望",
          "urgent_alerts": ["..."]
        }
    """.trimIndent()

    /**
     * 执行风控扫描
     */
    suspend fun scanPortfolio(
        onAlert: ((String) -> Unit)? = null
    ): RiskScanResult {
        val ctx = AgentContext().apply {
            put("date", TradingDayPickerView.recentTradingDay().toString())
        }

        val result = planAndExecute(
            goal = "扫描整个投资组合的风险状况，识别需要处理的持仓",
            ctx = ctx,
            maxSteps = 8
        )

        return parseRiskResult(result)
    }

    /**
     * 评估单只股票风险
     */
    suspend fun assessStockRisk(stockCode: String): StockRiskAssessment {
        val ctx = AgentContext().apply {
            put("stock_code", stockCode)
            put("date", TradingDayPickerView.recentTradingDay().toString())
        }

        val result = react(
            input = buildString {
                appendLine("评估股票 $stockCode 的当前风险状况")
                appendLine()
                appendLine("## 最终答案格式要求（严格遵守）")
                appendLine("请直接输出结论，不要包含推理过程。格式如下：")
                appendLine("- 风险等级：低/中/高")
                appendLine("- 止损位：XX 元（-X%）")
                appendLine("- 仓位建议：维持 / 减仓 / 观望")
                appendLine("- 核心风险：一句话概括")
            },
            ctx = ctx,
            maxSteps = 4
        )

        return StockRiskAssessment(
            success = result.success,
            stockCode = stockCode,
            assessment = result.output,
            steps = result.steps
        )
    }

    /**
     * 确定性单股风险评估 — 完全绕过 LLM，直接从 DB 读取数据并计算。
     *
     * 逻辑等价于 assessStockRisk()，但无需 LLM 调用，耗时从 ~100s 降至 <1s。
     * 使用方式：在 IntentRouter 路由到 RISK_CHECK 且 currentStock 不为空时优先调用。
     */
    suspend fun assessStockRiskDirect(stockCode: String): StockRiskAssessment =
        withContext(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(context)
                val today = TradingDayPickerView.recentTradingDay().toString()

                // ── 1. 查找持仓订单（同 PortfolioRiskScanTool） ──
                val orders = db.strategyTradeOrderDao().getRecent(200)
                    .filter { it.stockCode == stockCode && it.status in listOf("BUYING", "PENDING") }

                if (orders.isEmpty()) {
                    return@withContext StockRiskAssessment(
                        success = true,
                        stockCode = stockCode,
                        assessment = buildString {
                            appendLine("- 风险等级：无法评估")
                            appendLine("- 止损位：N/A")
                            appendLine("- 仓位建议：N/A")
                            appendLine("- 核心风险：未找到股票 $stockCode 的持仓记录")
                        },
                        steps = 0
                    )
                }

                val order = orders.first()
                val snapshot = db.dailySnapshotDao().getByDateAndCode(today, stockCode)

                if (snapshot == null) {
                    return@withContext StockRiskAssessment(
                        success = true,
                        stockCode = stockCode,
                        assessment = buildString {
                            appendLine("- 风险等级：无法评估")
                            appendLine("- 止损位：N/A")
                            appendLine("- 仓位建议：N/A")
                            appendLine("- 核心风险：无今日行情数据（日期 $today）")
                        },
                        steps = 0
                    )
                }

                // ── 2. 计算盈亏（同 PortfolioRiskScanTool / StopLossCheckTool） ──
                val profitPct = (snapshot.close - order.buyPrice) / order.buyPrice * 100

                // ── 3. 计算持仓天数（从订单日期推算） ──
                val daysHeld = try {
                    val buyDate = java.time.LocalDate.parse(order.tradeDate)
                    val todayDate = java.time.LocalDate.parse(today)
                    java.time.temporal.ChronoUnit.DAYS.between(buyDate, todayDate).toInt().coerceAtLeast(0)
                } catch (_: Exception) {
                    0
                }

                // ── 4. 计算 ATR（14日）用于动态止损止盈 ──
                val history = db.dailySnapshotDao().getByCode(stockCode, limit = 20)
                val atr14 = computeAtr(history)

                // ── 5. 计算最大回撤（持仓期间） ──
                val maxDrawdown = computeMaxDrawdown(history, order.buyPrice)

                // ── 6. 市场系统性风险（同 MarketRiskTool） ──
                val allToday = db.dailySnapshotDao().getByDate(today)
                val avgChange = if (allToday.isNotEmpty()) allToday.map { it.changePct }.average() else 0.0
                val downRatio = if (allToday.isNotEmpty()) allToday.count { it.changePct < -5 }.toDouble() / allToday.size else 0.0
                val limitDownCount = allToday.count { it.changePct <= -9.9 }
                val marketRiskLevel = when {
                    avgChange < -3 || downRatio > 0.1 || limitDownCount > 50 -> "HIGH"
                    avgChange < -1.5 || downRatio > 0.05 -> "MEDIUM"
                    else -> "LOW"
                }

                // ── 7. 确定止损止盈位（基于 ATR） ──
                val stopLossPrice = if (atr14 > 0) snapshot.close - 2.0 * atr14 else order.buyPrice * 0.92
                val stopLossPct = (stopLossPrice - snapshot.close) / snapshot.close * 100
                val takeProfitPrice = if (atr14 > 0) snapshot.close + 3.0 * atr14 else order.buyPrice * 1.15
                val takeProfitPct = (takeProfitPrice - snapshot.close) / snapshot.close * 100

                // ── 8. 综合风险等级 ──
                val riskLevel = when {
                    profitPct < -8 || maxDrawdown > 12 -> "高"
                    profitPct < -5 || maxDrawdown > 8 || marketRiskLevel == "HIGH" -> "中高"
                    profitPct < 0 || maxDrawdown > 5 || marketRiskLevel == "MEDIUM" -> "中"
                    profitPct > 15 -> "高（止盈区）"
                    else -> "低"
                }

                // ── 9. 仓位建议（同 PositionSizingTool 逻辑） ──
                val positionAdvice = when {
                    profitPct < -8 -> "建议止损卖出"
                    profitPct < -5 -> "建议减仓一半，观察企稳"
                    profitPct > 15 -> "建议分批止盈，先卖 1/3"
                    profitPct > 10 -> "建议上移止盈位，保护利润"
                    daysHeld > 10 && profitPct < 2 -> "持仓超 $daysHeld 天无明显盈利，建议评估换股"
                    marketRiskLevel == "HIGH" -> "大盘高风险，建议减仓至 30% 以下"
                    else -> "维持当前仓位"
                }

                // ── 10. 核心风险一句话 ──
                val coreRisk = when {
                    profitPct < -8 -> "亏损 ${"%.1f".format(profitPct)}%，已触及硬止损线"
                    profitPct < -5 -> "亏损 ${"%.1f".format(profitPct)}%，接近止损警戒区"
                    maxDrawdown > 12 -> "最大回撤 ${"%.1f".format(maxDrawdown)}%，超出安全范围"
                    daysHeld > 10 && profitPct < 2 -> "持有 $daysHeld 天仅 ${"%.1f".format(profitPct)}%，资金效率低"
                    marketRiskLevel == "HIGH" -> "大盘系统性风险偏高（均跌 ${"%.1f".format(avgChange)}%）"
                    profitPct > 15 -> "盈利 ${"%.1f".format(profitPct)}%，注意回调风险"
                    else -> "盈亏 ${"%.1f".format(profitPct)}%，风险可控"
                }

                val assessmentText = buildString {
                    appendLine("- 风险等级：$riskLevel")
                    appendLine("- 止损位：${"%.2f".format(stopLossPrice)} 元（${"%.1f".format(stopLossPct)}%）")
                    appendLine("- 止盈位：${"%.2f".format(takeProfitPrice)} 元（+${"%.1f".format(takeProfitPct)}%）")
                    appendLine("- 当前盈亏：${"%.1f".format(profitPct)}%（买入 ${"%.2f".format(order.buyPrice)} → 现价 ${"%.2f".format(snapshot.close)}）")
                    appendLine("- 最大回撤：${"%.1f".format(maxDrawdown)}%")
                    appendLine("- 持仓天数：$daysHeld 天")
                    appendLine("- 市场环境：$marketRiskLevel（均跌 ${"%.1f".format(avgChange)}%，跌停 $limitDownCount 家）")
                    appendLine("- 仓位建议：$positionAdvice")
                    appendLine("- 核心风险：$coreRisk")
                }

                StockRiskAssessment(
                    success = true,
                    stockCode = stockCode,
                    assessment = assessmentText,
                    steps = 0
                )
            } catch (e: Exception) {
                Log.e(TAG, "assessStockRiskDirect failed for $stockCode", e)
                StockRiskAssessment(
                    success = false,
                    stockCode = stockCode,
                    assessment = "错误: 直接评估失败: ${e.message}",
                    steps = 0
                )
            }
        }

    /**
     * 计算 14 日 ATR（Average True Range）。
     * True Range = max(high - low, |high - prevClose|, |low - prevClose|)
     */
    private fun computeAtr(history: List<com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity>): Double {
        if (history.size < 2) return 0.0
        // history 按 date DESC 排列，反转为时间正序
        val sorted = history.sortedBy { it.date }
        val trueRanges = mutableListOf<Double>()
        for (i in 1 until sorted.size) {
            val prev = sorted[i - 1]
            val cur = sorted[i]
            val tr = maxOf(
                cur.high - cur.low,
                kotlin.math.abs(cur.high - prev.close),
                kotlin.math.abs(cur.low - prev.close)
            )
            trueRanges.add(tr)
        }
        if (trueRanges.isEmpty()) return 0.0
        val period = minOf(14, trueRanges.size)
        return trueRanges.takeLast(period).average()
    }

    /**
     * 计算持仓期间最大回撤。
     * 回撤 = (peak - current) / peak * 100，取历史最大值。
     */
    private fun computeMaxDrawdown(
        history: List<com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity>,
        buyPrice: Double
    ): Double {
        if (history.isEmpty()) return 0.0
        val sorted = history.sortedBy { it.date }
        var peak = buyPrice
        var maxDd = 0.0
        for (snap in sorted) {
            if (snap.close > peak) peak = snap.close
            val dd = (peak - snap.close) / peak * 100
            if (dd > maxDd) maxDd = dd
        }
        return maxDd
    }

    private fun parseRiskResult(result: AgentResult): RiskScanResult {
        return try {
            val json = org.json.JSONObject(result.output)
            val holdings = mutableListOf<HoldingRisk>()
            json.optJSONArray("holdings")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    holdings.add(
                        HoldingRisk(
                            code = obj.getString("code"),
                            riskStatus = obj.optString("risk_status", "SAFE"),
                            profitPct = obj.optDouble("profit_pct", 0.0),
                            maxDrawdown = obj.optDouble("max_drawdown", 0.0),
                            daysHeld = obj.optInt("days_held", 0),
                            recommendation = obj.optString("recommendation", "HOLD"),
                            reason = obj.optString("reason", "")
                        )
                    )
                }
            }

            val alerts = mutableListOf<String>()
            json.optJSONArray("urgent_alerts")?.let { arr ->
                for (i in 0 until arr.length()) {
                    alerts.add(arr.getString(i))
                }
            }

            RiskScanResult(
                success = result.success,
                portfolioRiskLevel = json.optString("portfolio_risk_level", "LOW"),
                holdings = holdings,
                marketRisk = json.optString("market_risk", ""),
                positionAdvice = json.optString("position_advice", ""),
                urgentAlerts = alerts,
                rawOutput = result.output
            )
        } catch (e: Exception) {
            RiskScanResult(success = result.success, rawOutput = result.output)
        }
    }
}

/** 风控扫描结果 */
data class RiskScanResult(
    val success: Boolean,
    val portfolioRiskLevel: String = "LOW",
    val holdings: List<HoldingRisk> = emptyList(),
    val marketRisk: String = "",
    val positionAdvice: String = "",
    val urgentAlerts: List<String> = emptyList(),
    val rawOutput: String = ""
)

/** 单个持仓风险 */
data class HoldingRisk(
    val code: String,
    val riskStatus: String,
    val profitPct: Double,
    val maxDrawdown: Double,
    val daysHeld: Int,
    val recommendation: String,
    val reason: String
)

/** 单股风险评估 */
data class StockRiskAssessment(
    val success: Boolean,
    val stockCode: String,
    val assessment: String,
    val steps: Int = 0
)

/** ================================================================ */
/** 持仓风险扫描工具 */
class PortfolioRiskScanTool(private val ctx: Context) : AgentTool {
    override val name = "portfolio_risk_scan"
    override val description = "扫描所有持仓的风险指标（盈亏、回撤、持仓时间）"
    override val parameters = listOf<String>()

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val c = ctx
        return withContext(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(c)
                val today = TradingDayPickerView.recentTradingDay().toString()

                // 优先使用 realPositionDao（真实持仓表），比 order 表更可靠
                val realPositions = db.realPositionDao().getAllActive()

                if (realPositions.isEmpty()) {
                    // 降级：尝试从 strategyTradeOrderDao 查询
                    val orders = db.strategyTradeOrderDao().getRecent(200)
                        .filter { it.status in listOf("BUYING", "PENDING") }
                    if (orders.isEmpty()) {
                        return@withContext "当前无持仓记录（真实持仓表和订单表均为空）"
                    }
                    // 使用 orders 作为降级数据源
                    return@withContext scanOrders(db, orders, today)
                }

                // 使用 realPositions 扫描
                scanRealPositions(db, realPositions, today)
            } catch (e: Exception) {
                "错误: 扫描失败: ${e.message}"
            }
        }
    }

    private suspend fun scanRealPositions(
        db: com.chin.stockanalysis.stock.database.StockDatabase,
        positions: List<com.chin.stockanalysis.strategy.trade.RealPositionEntity>,
        today: String
    ): String {
        // 按周期分组
        val byPeriod = positions.groupBy { it.periodType.ifEmpty { "未分类" } }

        return buildString {
            appendLine("【持仓风险扫描】共 ${positions.size} 只")
            appendLine()

            for ((period, posList) in byPeriod) {
                appendLine("── $period 周期 ──")
                for (pos in posList) {
                    val snapshot = db.dailySnapshotDao().getByDateAndCode(today, pos.stockCode)
                    val currentPrice = snapshot?.close
                    if (currentPrice == null || currentPrice <= 0) {
                        appendLine("- ${pos.stockCode}(${pos.stockName}): 无法获取当前价格")
                        continue
                    }

                    val buyPrice = pos.avgBuyPrice
                    val profitPct = if (buyPrice > 0) {
                        (currentPrice - buyPrice) / buyPrice * 100
                    } else 0.0

                    val status = when {
                        profitPct < -8 -> "CRITICAL(止损)"
                        profitPct < -5 -> "WARNING(亏损)"
                        profitPct > 15 -> "WARNING(止盈)"
                        else -> "SAFE"
                    }

                    appendLine("- ${pos.stockCode}(${pos.stockName}): 买入价¥${"%.2f".format(buyPrice)} → 现价¥${"%.2f".format(currentPrice)} | 盈亏${"%.1f".format(profitPct)}% | $status")
                }
                appendLine()
            }
        }
    }

    private suspend fun scanOrders(
        db: com.chin.stockanalysis.stock.database.StockDatabase,
        orders: List<com.chin.stockanalysis.strategy.trade.StrategyTradeOrderEntity>,
        today: String
    ): String {
        val results = mutableListOf<String>()
        for (order in orders) {
            val snapshot = db.dailySnapshotDao().getByDateAndCode(today, order.stockCode)
            if (snapshot == null) continue

            val buyPrice = order.buyPrice
            val profitPct = (snapshot.close - buyPrice) / buyPrice * 100
            val status = when {
                profitPct < -8 -> "CRITICAL(止损)"
                profitPct < -5 -> "WARNING(亏损)"
                profitPct > 15 -> "WARNING(止盈)"
                else -> "SAFE"
            }

            results.add("${order.stockCode}(${order.stockName}): 买入价¥${"%.2f".format(buyPrice)} → 现价¥${"%.2f".format(snapshot.close)} | 盈亏${"%.1f".format(profitPct)}% | $status")
        }

        return buildString {
            appendLine("【持仓风险扫描（订单表降级）】共 ${orders.size} 只")
            results.forEach { appendLine("- $it") }
        }
    }
}

/** 止损检查工具 */
class StopLossCheckTool(private val ctx: Context) : AgentTool {
    override val name = "stop_loss_check"
    override val description = "检查是否有持仓触发止损条件"
    override val parameters = listOf("threshold_pct")

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val c = ctx
        return withContext(Dispatchers.IO) {
            try {
                val threshold = params["threshold_pct"]?.toDoubleOrNull() ?: -8.0
                val db = StockDatabase.getInstance(c)
                val today = TradingDayPickerView.recentTradingDay().toString()
                val orders = db.strategyTradeOrderDao().getRecent(200).filter { it.status in listOf("BUYING", "PENDING") }

                val triggered = mutableListOf<String>()
                for (order in orders) {
                    val snapshot = db.dailySnapshotDao().getByDateAndCode(today, order.stockCode)
                    if (snapshot == null) continue
                    val profitPct = (snapshot.close - order.buyPrice) / order.buyPrice * 100
                    if (profitPct <= threshold) {
                        triggered.add("${order.stockCode}: ${"%.1f".format(profitPct)}% <= ${threshold}%")
                    }
                }

                if (triggered.isEmpty()) "✅ 无持仓触发止损（阈值 ${threshold}%）"
                else "⚠️ 止损触发:\n${triggered.joinToString("\n")}"
            } catch (e: Exception) {
                "错误: 止损检查失败: ${e.message}"
            }
        }
    }
}

/** 止盈检查工具 */
class TakeProfitCheckTool(private val ctx: Context) : AgentTool {
    override val name = "take_profit_check"
    override val description = "检查是否有持仓达到止盈条件"
    override val parameters = listOf("threshold_pct")

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val c = ctx
        return withContext(Dispatchers.IO) {
            try {
                val threshold = params["threshold_pct"]?.toDoubleOrNull() ?: 15.0
                val db = StockDatabase.getInstance(c)
                val today = TradingDayPickerView.recentTradingDay().toString()
                val orders = db.strategyTradeOrderDao().getRecent(200).filter { it.status in listOf("BUYING", "PENDING") }

                val triggered = mutableListOf<String>()
                for (order in orders) {
                    val snapshot = db.dailySnapshotDao().getByDateAndCode(today, order.stockCode)
                    if (snapshot == null) continue
                    val profitPct = (snapshot.close - order.buyPrice) / order.buyPrice * 100
                    if (profitPct >= threshold) {
                        triggered.add("${order.stockCode}: +${"%.1f".format(profitPct)}% >= +${threshold}%")
                    }
                }

                if (triggered.isEmpty()) "✅ 无持仓达到止盈（阈值 +${threshold}%）"
                else "🎯 止盈触发:\n${triggered.joinToString("\n")}"
            } catch (e: Exception) {
                "错误: 止盈检查失败: ${e.message}"
            }
        }
    }
}

/** 市场风险工具 */
class MarketRiskTool(private val ctx: Context) : AgentTool {
    override val name = "market_risk"
    override val description = "评估当前市场系统性风险"
    override val parameters = listOf<String>()

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val c = ctx
        return withContext(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(c)
                val today = TradingDayPickerView.recentTradingDay().toString()
                val data = db.dailySnapshotDao().getByDate(today)

                val avgChange = data.map { it.changePct }.average()
                val downRatio = if (data.isNotEmpty()) data.count { it.changePct < -5 }.toDouble() / data.size else 0.0
                val limitDownCount = data.count { it.changePct <= -9.9 }

                val riskLevel = when {
                    avgChange < -3 || downRatio > 0.1 || limitDownCount > 50 -> "HIGH（高风险）"
                    avgChange < -1.5 || downRatio > 0.05 -> "MEDIUM（中风险）"
                    else -> "LOW（低风险）"
                }

                buildString {
                    appendLine("【市场风险评估】")
                    appendLine("- 风险等级: $riskLevel")
                    appendLine("- 平均涨跌: ${"%.2f".format(avgChange)}%")
                    appendLine("- 大跌股比例: ${"%.1f".format(downRatio * 100)}%")
                    appendLine("- 跌停家数: $limitDownCount")
                }
            } catch (e: Exception) {
                "错误: 市场风险评估失败: ${e.message}"
            }
        }
    }
}

/** 仓位调整工具 */
class PositionSizingTool(private val ctx: Context) : AgentTool {
    override val name = "position_sizing"
    override val description = "根据风险等级给出仓位调整建议"
    override val parameters = listOf("risk_level", "current_stock_ratio")

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val riskLevel = params["risk_level"] ?: "LOW"
        val currentRatio = params["current_stock_ratio"]?.toDoubleOrNull() ?: 70.0

        val advice = when (riskLevel.uppercase()) {
            "HIGH", "CRITICAL" -> "建议减仓至 30% 以下，优先卖出弱势股"
            "MEDIUM" -> "建议减仓至 50% 左右，保留强势股"
            else -> if (currentRatio < 80) "可以维持或适度加仓" else "仓位已高，不建议追涨"
        }

        return "【仓位建议】当前股票仓位 ${currentRatio}% | 风险等级 $riskLevel | $advice"
    }
}
