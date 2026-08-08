package com.chin.stockanalysis.strategy.topology.xml

import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.topology.pipelines.HoldingGuardResult
import com.chin.stockanalysis.strategy.topology.pipelines.SwapWeakResult

/**
 * ## 持倉診斷分析器
 *
 * 在騰龍換鳥/持倉風控執行後，分析被賣出的持倉，
 * 診斷選錯股票的原因並生成中文報告。
 *
 * ### 分析維度
 * 1. 持倉虧損原因分類（止損/止盈/趨勢反轉/黑天鵝）
 * 2. 買入時點回顧（買入價 vs 當前價 vs 最高價）
 * 3. 持倉天數與收益分析
 * 4. 選股策略改進建議
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
     * 分析被賣出的持倉。
     *
     * @param guardResult 持倉風控結果（止損/止盈賣出）
     * @param swapResult 騰龍換鳥結果（換股賣出）
     * @param db 數據庫（用於查詢歷史價格）
     * @param tradeDate 交易日
     * @return 診斷結果
     */
    suspend fun analyze(
        guardResult: HoldingGuardResult?,
        swapResult: SwapWeakResult?,
        db: StockDatabase,
        tradeDate: String
    ): DiagnosticResult {
        val soldStocks = mutableListOf<SoldStockInfo>()

        // 收集持倉風控賣出的股票
        if (guardResult != null && guardResult.soldCount > 0) {
            for (stockDesc in guardResult.soldStocks) {
                soldStocks.add(parseSoldStock(stockDesc, "持倉風控"))
            }
        }

        // 收集騰龍換鳥賣出的股票
        if (swapResult != null && swapResult.swappedCount > 0) {
            for (stockDesc in swapResult.soldStocks) {
                soldStocks.add(parseSoldStock(stockDesc, "騰龍換鳥"))
            }
        }

        if (soldStocks.isEmpty()) {
            return DiagnosticResult(hasIssues = false, soldCount = 0, totalLossPct = 0.0)
        }

        // 分析每只被賣出的股票
        val diagnosisLines = mutableListOf<String>()
        var totalLoss = 0.0
        val lossStocks = mutableListOf<String>()
        val profitStocks = mutableListOf<String>()
        val reasons = mutableMapOf<String, Int>()  // 原因分類 → 計數

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
                    // 分類原因
                    val category = categorizeReason(stock.reason)
                    reasons[category] = (reasons[category] ?: 0) + 1
                }
            }
            diagnosisLines.add(line)

            // 查詢歷史價格走勢（僅當代碼有效時）
            if (stock.code.isNotBlank()) {
                try {
                    val snaps = db.dailySnapshotDao().getByCode(stock.code, 20)
                    if (snaps.isNotEmpty()) {
                        val recent = snaps.take(5)
                        val trend = if (recent.size >= 3) {
                            val changes = recent.map { it.changePct }
                            val downDays = changes.count { it < 0 }
                            when {
                                downDays >= 4 -> "連續下跌"
                                downDays >= 3 -> "多數下跌"
                                else -> "震盪"
                            }
                        } else "數據不足"
                        diagnosisLines.add("  近5日走勢: $trend, 最新收盤: ${snaps.first().close}")
                    }
                } catch (_: Exception) {}
            }
        }

        // 生成改進建議
        val suggestions = mutableListOf<String>()
        if (reasons.containsKey("硬止損")) {
            suggestions.add("多只股票觸發硬止損，說明買入時點偏高。建議：1) 在大盤 BEARISH 時降低買入價預期；2) 增加「冰點買入」權重。")
        }
        if (reasons.containsKey("止盈") || reasons.containsKey("階梯止盈")) {
            suggestions.add("止盈賣出說明選股方向正確但賣出過早。可考慮：1) 放寬止盈閾值；2) 使用移動止盈替代固定止盈。")
        }
        if (reasons.containsKey("趨勢反轉")) {
            suggestions.add("趨勢反轉賣出說明持倉時間過長。建議：1) 縮短持倉週期；2) 加強 MA 死叉預警。")
        }
        if (lossStocks.size > soldStocks.size / 2) {
            suggestions.add("超過半數持倉虧損，整體選股質量需要提升。建議：1) 提高嚴選通過門檻；2) 增加主力資金流入確認；3) BEARISH 市場減少建倉數量。")
        }

        val avgLoss = if (soldStocks.isNotEmpty()) totalLoss / soldStocks.size else 0.0
        val summary = buildString {
            appendLine("── 持倉診斷 ──")
            appendLine("被賣出: ${soldStocks.size} 只 (${guardResult?.soldCount ?: 0} 風控 + ${swapResult?.swappedCount ?: 0} 換鳥)")
            if (lossStocks.isNotEmpty()) appendLine("虧損: ${lossStocks.joinToString(", ")}")
            if (profitStocks.isNotEmpty()) appendLine("盈利: ${profitStocks.joinToString(", ")}")
            appendLine("平均收益: ${"%.2f".format(avgLoss)}%")
            if (reasons.isNotEmpty()) {
                appendLine("原因分類: ${reasons.entries.joinToString { "${it.key}×${it.value}" }}")
            }
            if (suggestions.isNotEmpty()) {
                appendLine("── 改進建議 ──")
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

    /** 解析賣出股票描述（格式: "股票名(原因)" 或純股票名） */
    private data class SoldStockInfo(
        val code: String,
        val name: String,
        val reason: String,
        val source: String,
        val profitPct: Double? = null
    )

    private fun parseSoldStock(desc: String, source: String): SoldStockInfo {
        // 嘗試解析 "股票名(原因)" 格式
        val parenStart = desc.indexOf('(')
        val parenEnd = desc.lastIndexOf(')')
        return if (parenStart > 0 && parenEnd > parenStart) {
            val name = desc.substring(0, parenStart).trim()
            val reason = desc.substring(parenStart + 1, parenEnd).trim()
            // 嘗試從 reason 中提取百分比
            val pctMatch = Regex("[-+]?\\d+\\.?\\d*%").find(reason)
            val pct = pctMatch?.value?.trimEnd('%')?.toDoubleOrNull()
            SoldStockInfo(code = "", name = name, reason = reason, source = source, profitPct = pct)
        } else {
            SoldStockInfo(code = "", name = desc.trim(), reason = "", source = source)
        }
    }

    /** 將原因歸類 */
    private fun categorizeReason(reason: String): String = when {
        reason.contains("硬止損") || reason.contains("HardStop") -> "硬止損"
        reason.contains("最大回撤") || reason.contains("Drawdown") -> "最大回撤"
        reason.contains("止盈") || reason.contains("TakeProfit") || reason.contains("Tiered") -> "止盈"
        reason.contains("時間") || reason.contains("TimeForce") -> "時間強制平倉"
        reason.contains("MA") || reason.contains("均線") || reason.contains("死叉") -> "趨勢反轉"
        reason.contains("RSI") -> "RSI超買"
        reason.contains("放量") || reason.contains("Volume") -> "放量滯漲"
        reason.contains("板塊走弱") || reason.contains("Sector") -> "板塊走弱"
        else -> "其他"
    }
}
