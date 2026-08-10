package com.chin.stockanalysis.strategy.topology.xml

import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.topology.pipelines.HoldingGuardResult
import com.chin.stockanalysis.strategy.topology.pipelines.SwapWeakResult

/**
 * ## 持仓诊断分析器
 *
 * 在腾龙换鸟/持仓风控执行后，分析被卖出的持仓，
 * 诊断选错股票的原因并生成中文报告。
 *
 * ### 分析维度
 * 1. 持仓亏损原因分类（止损/止盈/趋势反转/黑天鹅）
 * 2. 买入时点回顾（买入价 vs 当前价 vs 最高价）
 * 3. 持仓天数与收益分析
 * 4. 选股策略改进建议
 */
object HoldingDiagnosticAnalyzer {

    data class DiagnosticResult(
        val hasIssues: Boolean,
        val soldCount: Int,
        val totalLossPct: Double,
        val diagnosisLines: List<String> = emptyList(),
        val summary: String = "",
        val improvementSuggestions: List<String> = emptyList()
    )

    /**
     * 分析被卖出的持仓。
     *
     * @param guardResult 持仓风控结果（止损/止盈卖出）
     * @param swapResult 腾龙换鸟结果（换股卖出）
     * @param db 数据库（用于查询历史价格）
     * @param tradeDate 交易日
     * @return 诊断结果
     */
    suspend fun analyze(
        guardResult: HoldingGuardResult?,
        swapResult: SwapWeakResult?,
        db: StockDatabase,
        tradeDate: String
    ): DiagnosticResult {
        val soldStocks = mutableListOf<SoldStockInfo>()

        // 收集持仓风控卖出的股票
        if (guardResult != null && guardResult.soldCount > 0) {
            for (stockDesc in guardResult.soldStocks) {
                soldStocks.add(parseSoldStock(stockDesc, "持仓风控"))
            }
        }

        // 收集腾龙换鸟卖出的股票
        if (swapResult != null && swapResult.swappedCount > 0) {
            for (stockDesc in swapResult.soldStocks) {
                soldStocks.add(parseSoldStock(stockDesc, "腾龙换鸟"))
            }
        }

        if (soldStocks.isEmpty()) {
            return DiagnosticResult(hasIssues = false, soldCount = 0, totalLossPct = 0.0)
        }

        // 分析每只被卖出的股票
        val diagnosisLines = mutableListOf<String>()
        var totalLoss = 0.0
        val lossStocks = mutableListOf<String>()
        val profitStocks = mutableListOf<String>()
        val reasons = mutableMapOf<String, Int>()  // 原因分类 → 计数

        for (stock in soldStocks) {
            val line = buildString {
                append("• ${stock.name}(${stock.code})")
                if (stock.profitPct != null) {
                    val pct = stock.profitPct!!
                    totalLoss += pct
                    if (pct < 0) lossStocks.add("${stock.name}(${"%.1f".format(pct)}%)")
                    else profitStocks.add("${stock.name}(+${"%.1f".format(pct)}%)")
                }
                append(" [${stock.source}]")
                if (stock.reason.isNotBlank()) {
                    append(" — ${stock.reason}")
                    // 分类原因
                    val category = categorizeReason(stock.reason)
                    reasons[category] = (reasons[category] ?: 0) + 1
                }
            }
            diagnosisLines.add(line)

            // 查询历史价格走势（仅当代码有效时）
            if (stock.code.isNotBlank()) {
                try {
                    val snaps = db.dailySnapshotDao().getByCode(stock.code, 20)
                    if (snaps.isNotEmpty()) {
                        val recent = snaps.take(5)
                        val trend = if (recent.size >= 3) {
                            val changes = recent.map { it.changePct }
                            val downDays = changes.count { it < 0 }
                            when {
                                downDays >= 4 -> "连续下跌"
                                downDays >= 3 -> "多数下跌"
                                else -> "震荡"
                            }
                        } else "数据不足"
                        diagnosisLines.add("  近5日走势: $trend, 最新收盘: ${snaps.first().close}")
                    }
                } catch (_: Exception) {}
            }
        }

        // 生成改进建议
        val suggestions = mutableListOf<String>()
        if (reasons.containsKey("硬止损")) {
            suggestions.add("多只股票触发硬止损，说明买入时点偏高。建议：1) 在大盘 BEARISH 时降低买入价预期；2) 增加「冰点买入」权重。")
        }
        if (reasons.containsKey("止盈") || reasons.containsKey("阶梯止盈")) {
            suggestions.add("止盈卖出说明选股方向正确但卖出过早。可考虑：1) 放宽止盈阈值；2) 使用移动止盈替代固定止盈。")
        }
        if (reasons.containsKey("趋势反转")) {
            suggestions.add("趋势反转卖出说明持仓时间过长。建议：1) 缩短持仓周期；2) 加强 MA 死叉预警。")
        }
        if (lossStocks.size > soldStocks.size / 2) {
            suggestions.add("超过半数持仓亏损，整体选股质量需要提升。建议：1) 提高严选通过门槛；2) 增加主力资金流入确认；3) BEARISH 市场减少建仓数量。")
        }

        val avgLoss = if (soldStocks.isNotEmpty()) totalLoss / soldStocks.size else 0.0
        val summary = buildString {
            appendLine("── 持仓诊断 ──")
            appendLine("被卖出: ${soldStocks.size} 只 (${guardResult?.soldCount ?: 0} 风控 + ${swapResult?.swappedCount ?: 0} 换鸟)")
            if (lossStocks.isNotEmpty()) appendLine("亏损: ${lossStocks.joinToString(", ")}")
            if (profitStocks.isNotEmpty()) appendLine("盈利: ${profitStocks.joinToString(", ")}")
            appendLine("平均收益: ${"%.2f".format(avgLoss)}%")
            if (reasons.isNotEmpty()) {
                appendLine("原因分类: ${reasons.entries.joinToString { "${it.key}×${it.value}" }}")
            }
            if (suggestions.isNotEmpty()) {
                appendLine("── 改进建议 ──")
                for (s in suggestions) appendLine("  $s")
            }
        }

        return DiagnosticResult(
            hasIssues = soldStocks.isNotEmpty(),
            soldCount = soldStocks.size,
            totalLossPct = avgLoss,
            diagnosisLines = diagnosisLines,
            summary = summary.trimEnd(),
            improvementSuggestions = suggestions
        )
    }

    /** 解析卖出股票描述（格式: "股票名(原因)" 或纯股票名） */
    private data class SoldStockInfo(
        val code: String,
        val name: String,
        val reason: String,
        val source: String,
        val profitPct: Double? = null
    )

    private fun parseSoldStock(desc: String, source: String): SoldStockInfo {
        // 尝试解析 "股票名(原因)" 格式
        val parenStart = desc.indexOf('(')
        val parenEnd = desc.lastIndexOf(')')
        return if (parenStart > 0 && parenEnd > parenStart) {
            val name = desc.substring(0, parenStart).trim()
            val reason = desc.substring(parenStart + 1, parenEnd).trim()
            // 尝试从 reason 中提取百分比
            val pctMatch = Regex("[-+]?\\d+\\.?\\d*%").find(reason)
            val pct = pctMatch?.value?.trimEnd('%')?.toDoubleOrNull()
            SoldStockInfo(code = "", name = name, reason = reason, source = source, profitPct = pct)
        } else {
            SoldStockInfo(code = "", name = desc.trim(), reason = "", source = source)
        }
    }

    /** 将原因归类 */
    private fun categorizeReason(reason: String): String = when {
        reason.contains("硬止损") || reason.contains("HardStop") -> "硬止损"
        reason.contains("最大回撤") || reason.contains("Drawdown") -> "最大回撤"
        reason.contains("止盈") || reason.contains("TakeProfit") || reason.contains("Tiered") -> "止盈"
        reason.contains("时间") || reason.contains("TimeForce") -> "时间强制平仓"
        reason.contains("MA") || reason.contains("均线") || reason.contains("死叉") -> "趋势反转"
        reason.contains("RSI") -> "RSI超买"
        reason.contains("放量") || reason.contains("Volume") -> "放量滞涨"
        reason.contains("板块走弱") || reason.contains("Sector") -> "板块走弱"
        else -> "其他"
    }
}
