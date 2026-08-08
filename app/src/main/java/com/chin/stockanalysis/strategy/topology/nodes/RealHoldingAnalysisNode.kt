package com.chin.stockanalysis.strategy.topology.nodes

import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.market.AMarketAnalysisEngine
import com.chin.stockanalysis.strategy.topology.core.*
import com.chin.stockanalysis.strategy.trade.RealPositionEntity
import com.chin.stockanalysis.strategy.trade.StrategyTradeOrderEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * ## 實倉持倉評估節點 v2
 *
 * 讀取 AMarketAnalysisEngine 的大盤結論，
 * 對每只持倉逐股分析 K 線，生成操作建議：
 * - 加倉 / 持有 / 減倉 / 清倉
 * - 做T / 反T / 觀望
 *
 * ### 輸入
 * - n_a_market: AMarketAnalysisEngine.MarketAnalysisResult
 *
 * ### 輸出
 * [RealHoldingAnalysisResult]
 */
data class RealHoldingAnalysisResult(
    val marketSummary: String = "",
    val suggestedPeriod: String = "",
    val suggestedPositionPct: Int = 50,
    val holdingCount: Int = 0,
    val holdingDetails: String = "",
    val overallAdvice: String = ""
)

class RealHoldingAnalysisNode : BaseNode<Any, RealHoldingAnalysisResult>(
    "n_rh_eval", "實倉持倉評估", NodeType.TRADE_ACTION
) {
    companion object {
        private const val TAG = "RealHoldingEval"
    }

    override suspend fun execute(context: PipelineContext, input: Any): RealHoldingAnalysisResult {
        Log.i(TAG, "開始實倉持倉評估...")

        // 1. 讀取大盤分析結論
        val marketResult = context.getStageOutput<AMarketAnalysisEngine.MarketAnalysisResult>("n_a_market")
        val marketSummary = marketResult?.summary ?: "大盤數據不可用"
        val marketCondition = when {
            marketResult == null -> "未知"
            marketResult.isTopDanger -> "危險"
            marketResult.isTrendUp -> "多頭"
            marketResult.isBottomConfirmed -> "觸底"
            else -> "震盪"
        }

        // 2. 讀取持倉數據
        val db = StockDatabase.getInstance(context.androidContext)
        val realPositions = withContext(Dispatchers.IO) {
            try { db.realPositionDao().getAllActive() } catch (_: Exception) { emptyList<RealPositionEntity>() }
        }
        val orders = withContext(Dispatchers.IO) {
            try {
                db.strategyTradeOrderDao().getRecent(500)
                    .filter { it.status == "BUYING" || it.status == "PENDING" || it.status == "HOLDING" }
            } catch (_: Exception) { emptyList<StrategyTradeOrderEntity>() }
        }

        val today = LocalDate.now()

        // 3. 逐持倉分析
        val details = buildString {
            // 真實持倉
            if (realPositions.isNotEmpty()) {
                appendLine("── 真實持倉 (${realPositions.size} 只) ──")
                for (p in realPositions) {
                    val analysis = analyzeHolding(context, p.stockCode, p.avgBuyPrice, today)
                    val buyDate = try { LocalDate.parse(p.buyDate) } catch (_: Exception) { today }
                    val daysHeld = ChronoUnit.DAYS.between(buyDate, today).toInt().coerceAtLeast(0)
                    appendLine("  ${p.stockName}(${p.stockCode}) ${daysHeld}天 ¥${"%.2f".format(p.avgBuyPrice)}")
                    appendLine("    → ${analysis}")
                }
            }
            // 策略持倉
            if (orders.isNotEmpty()) {
                appendLine("── 策略持倉 (${orders.size} 筆) ──")
                for (o in orders.take(15)) {
                    val analysis = analyzeHolding(context, o.stockCode, o.buyPrice, today)
                    val buyDate = try { LocalDate.parse(o.tradeDate) } catch (_: Exception) { today }
                    val daysHeld = ChronoUnit.DAYS.between(buyDate, today).toInt().coerceAtLeast(0)
                    val pnl = o.profitPct
                    val pnlStr = if (pnl >= 0) "+${"%.2f".format(pnl)}%" else "${"%.2f".format(pnl)}%"
                    appendLine("  ${o.stockName}(${o.stockCode}) ${daysHeld}天 $pnlStr")
                    appendLine("    → ${analysis}")
                }
                if (orders.size > 15) appendLine("  ... 共 ${orders.size} 筆")
            }
            if (realPositions.isEmpty() && orders.isEmpty()) {
                appendLine("暫無持倉")
            }
        }

        // 4. 整體建議
        val advice = buildOverallAdvice(marketCondition, marketResult, realPositions.size + orders.size)

        val result = RealHoldingAnalysisResult(
            marketSummary = marketSummary,
            suggestedPeriod = marketResult?.suggestedPeriod?.label ?: "短線",
            suggestedPositionPct = marketResult?.suggestedPositionPct ?: 40,
            holdingCount = realPositions.size + orders.size,
            holdingDetails = details.trimEnd(),
            overallAdvice = advice
        )

        Log.i(TAG, "實倉評估完成: 大盤=$marketCondition, 持倉=${result.holdingCount}只")
        return result
    }

    /**
     * 分析單只持倉：讀取 K 線 → 判斷趨勢 → 生成操作建議
     */
    private suspend fun analyzeHolding(
        context: PipelineContext,
        stockCode: String,
        buyPrice: Double,
        today: LocalDate
    ): String = withContext(Dispatchers.IO) {
        try {
            val db = StockDatabase.getInstance(context.androidContext)
            val snapshots = db.dailySnapshotDao().getByCode(stockCode, 30).sortedBy { it.date }
            if (snapshots.size < 5) return@withContext "數據不足，建議觀望"

            val latest = snapshots.last()
            val currentPrice = latest.close
            val pnlPct = if (buyPrice > 0) (currentPrice - buyPrice) / buyPrice * 100 else 0.0

            val closes = snapshots.map { it.close }
            val ma5 = closes.takeLast(5).average()
            val ma10 = closes.takeLast(10).average()
            val ma20 = if (closes.size >= 20) closes.takeLast(20).average() else ma10

            val aboveMa5 = currentPrice > ma5
            val aboveMa10 = currentPrice > ma10
            val maBullish = ma5 > ma10 && ma10 > ma20
            val maBearish = ma5 < ma10 && ma10 < ma20

            val todayVol = latest.volume.toDouble()
            val avgVol5 = snapshots.takeLast(6).dropLast(1).map { it.volume.toDouble() }.average()
            val volRatio = if (avgVol5 > 0) todayVol / avgVol5 else 1.0

            val recent3Change = if (closes.size >= 4) {
                (closes.last() - closes[closes.size - 4]) / closes[closes.size - 4] * 100
            } else 0.0

            // ── 交易紀律偵測 ──
            val prevClose = if (closes.size >= 2) closes[closes.size - 2] else currentPrice
            val gapUpPct = if (prevClose > 0) (latest.open - prevClose) / prevClose * 100 else 0.0
            val isHighOpenChase = gapUpPct > 5.0  // 紀律一：高開>5%不追
            val nearMa5 = ma5 > 0 && Math.abs(currentPrice - ma5) / ma5 * 100 < 2.0  // 紀律一：MA5附近低吸
            val bodyPct = if (latest.open > 0) (latest.close - latest.open) / latest.open * 100 else 0.0
            val isBigYin = bodyPct < -3.0  // 大陰線
            val upperShadow = latest.high - maxOf(latest.open, latest.close)
            val bodySize = Math.abs(latest.close - latest.open)
            val isLongUpperShadow = bodySize > 0 && upperShadow > bodySize * 2.0  // 長上影線
            val isLimitUp = latest.close >= prevClose * 1.095  // 漲停
            val isLimitUpOpen = isLimitUp && latest.close < latest.high  // 開板
            val isAtHigh = currentPrice > ma20 * 1.15  // 高位（遠離MA20）
            val isBigYinAtHigh = isAtHigh && isBigYin  // 高位大陰

            val sb = StringBuilder()

            // 操作建議（融入交易紀律）
            val advice = when {
                // 紀律四：止損-10%
                pnlPct <= -10 -> "🔴 止損清倉(${String.format("%.1f", pnlPct)}%)(紀律四)"
                // 紀律三：高位大陰必賣
                isBigYinAtHigh -> "🔴 高位大陰，建議減倉(紀律三)"
                // 紀律三：長上影線必賣
                isLongUpperShadow && isAtHigh -> "🔴 高位長上影，建議減倉(紀律三)"
                // 紀律三：漲停開板必賣
                isLimitUpOpen -> "🟡 漲停開板，注意風險(紀律三)"
                // 紀律三：賺錢趨勢減弱→止盈
                pnlPct >= 15 && !maBullish -> "🟢 止盈減倉(${String.format("%.1f", pnlPct)}%，趨勢減弱)(紀律三)"
                // 紀律一：高開>5%不追
                isHighOpenChase -> "⚠ 高開${String.format("%.1f", gapUpPct)}%，不追高(紀律一)"
                // 紀律一：MA5附近低吸信號
                nearMa5 && maBullish -> "📈 MA5附近低吸機會，持有/加倉(紀律一)"
                // 原趨勢判斷
                maBearish && !aboveMa10 && volRatio > 1.5 && recent3Change < -5 -> "📉 反T機會(超跌放量)"
                maBearish && !aboveMa10 -> "⚠ 空頭排列，建議減倉或反T"
                maBullish && aboveMa5 && volRatio > 1.3 -> "📈 多頭放量，持有/加倉"
                maBullish && aboveMa5 -> "📈 多頭持有，可做T降成本"
                aboveMa5 && !aboveMa10 -> "🔄 短線反彈，做T為主"
                !aboveMa5 && aboveMa10 -> "🔄 回調MA10附近，可做T或觀望"
                else -> "⏳ 震盪觀望"
            }

            sb.append(advice)
            sb.append(" | 現價${String.format("%.2f", currentPrice)} 盈虧${String.format("%.1f", pnlPct)}%")
            sb.append(" | MA5${String.format("%.0f", ma5)}${if (aboveMa5) "↑" else "↓"}")
            if (volRatio > 1.3) {
                sb.append(" | 放量${String.format("%.1f", volRatio)}x")
            }
            sb.toString()
        } catch (e: Exception) {
            "分析失敗: ${e.message}"
        }
    }

    private fun buildOverallAdvice(
        marketCondition: String,
        marketResult: AMarketAnalysisEngine.MarketAnalysisResult?,
        holdingCount: Int
    ): String {
        if (holdingCount == 0) return "暫無持倉，等待建倉機會"
        return when (marketCondition) {
            "危險" -> "⚠️ 大盤逃頂信號，建議收緊止損，倉位降至 ${marketResult?.suggestedPositionPct ?: 10}% 以下"
            "多頭" -> "✅ 大盤多頭排列，持倉可繼續持有，倉位可達 ${marketResult?.suggestedPositionPct ?: 70}%"
            "觸底" -> "🔄 大盤底部確認，可逐步建倉，倉位 ${marketResult?.suggestedPositionPct ?: 50}%"
            else -> "⏳ 大盤震盪，高拋低吸為主，倉位 ${marketResult?.suggestedPositionPct ?: 40}%"
        }
    }
}
