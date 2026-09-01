package com.chin.stockanalysis.strategy.topology.nodes

import android.util.Log
import com.chin.stockanalysis.notification.TradeNotifier
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.topology.core.*
import com.chin.stockanalysis.strategy.trade.TTradeEngine
import com.chin.stockanalysis.strategy.trade.TTradeSignal
import com.chin.stockanalysis.strategy.trade.TTradeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ## 做T评估节点（v2 - 增强版）
 *
 * 基于真实持仓，调用 TTradeEngine 生成做T/反T信号。
 * 包含 RSI、量能、K线形态、趋势方向分析。
 * 信号生成后通过 TradeNotifier 推送微信通知。
 *
 * ### 输入
 * - n_rh_eval: RealHoldingAnalysisResult（可选，用于结合市场判断）
 *
 * ### 输出
 * [TTradeEvalResult]
 */
data class TTradeEvalResult(
    val signals: List<TTradeSignal> = emptyList(),
    val summary: String = ""
)

class TTradeEvalNode : BaseNode<Any, TTradeEvalResult>(
    "n_t_trade", "做T评估", NodeType.TRADE_ACTION
) {
    companion object {
        private const val TAG = "TTradeEval"
    }

    override suspend fun execute(context: PipelineContext, input: Any): TTradeEvalResult {
        Log.i(TAG, "开始做T评估...")

        val db = StockDatabase.getInstance(context.androidContext)

        // 读取真实持仓
        val positions = withContext(Dispatchers.IO) {
            db.realPositionDao().getAllActive()
        }

        if (positions.isEmpty()) {
            context.log("n_t_trade", "⚠️ 无真实持仓，跳过做T评估")
            return TTradeEvalResult(summary = "无持仓")
        }

        val tEngine = TTradeEngine(context.androidContext)
        val allSignals = mutableListOf<TTradeSignal>()

        for (pos in positions) {
            try {
                val signals = tEngine.generateSignals(
                    stockCode = pos.stockCode,
                    basePositionQty = pos.quantity,
                    periodType = pos.periodType
                )
                allSignals.addAll(signals)
                for (sig in signals) {
                    val trendIcon = when {
                        sig.trendDirection.contains("上升") -> "📈"
                        sig.trendDirection.contains("下跌") -> "📉"
                        else -> "➡️"
                    }
                    // v5: 高低点区间——低吸/高抛区给用户明确的可执行区间
                    val zoneStr = when {
                        sig.inLowZone && sig.lowZoneHigh > 0 ->
                            " | 🎯低吸区(${"%.2f".format(sig.lowZoneLow)}~${"%.2f".format(sig.lowZoneHigh)})可大胆买"
                        sig.inHighZone && sig.highZoneLow > 0 ->
                            " | 🎯高抛区(${"%.2f".format(sig.highZoneLow)}~${"%.2f".format(sig.highZoneHigh)})可大胆卖"
                        sig.lowZoneHigh > 0 || sig.highZoneLow > 0 ->
                            " | 区间(低${"%.2f".format(sig.lowZoneLow)}~${"%.2f".format(sig.lowZoneHigh)}/高${"%.2f".format(sig.highZoneLow)}~${"%.2f".format(sig.highZoneHigh)})"
                        else -> ""
                    }
                    context.log("n_t_trade",
                        "  $trendIcon ${pos.stockName}(${pos.stockCode}): " +
                        "${sig.signalType.label} | 趋势:${sig.trendDirection} | " +
                        "RSI:${"%.0f".format(sig.rsi)} | 量比:${"%.1f".format(sig.volumeRatio)} | " +
                        "置信度:${sig.confidence}%" + zoneStr +
                        if (sig.patternName.isNotEmpty()) " | 形态:${sig.patternName}" else ""
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "生成 ${pos.stockCode} 做T信号失败: ${e.message}")
            }
        }

        // 构建摘要
        val summary = buildString {
            appendLine("做T信号: ${allSignals.size} 个")
            val buySignals = allSignals.filter { it.signalType == TTradeType.T_BUY }
            val sellSignals = allSignals.filter { it.signalType == TTradeType.RT_SELL }
            val pairSignals = allSignals.filter {
                it.signalType == TTradeType.T_SELL || it.signalType == TTradeType.RT_BUY
            }

            if (buySignals.isNotEmpty()) {
                appendLine("  🟢 做T买入: ${buySignals.size} 个")
                for (s in buySignals) {
                    val zone = if (s.inLowZone && s.lowZoneHigh > 0)
                        " 低吸区(${"%.2f".format(s.lowZoneLow)}~${"%.2f".format(s.lowZoneHigh)})"
                    else ""
                    appendLine("    ${s.stockName} ${s.trendDirection} 置信度${s.confidence}%$zone")
                }
            }
            if (sellSignals.isNotEmpty()) {
                appendLine("  🔴 反T卖出: ${sellSignals.size} 个")
                for (s in sellSignals) {
                    val zone = if (s.inHighZone && s.highZoneLow > 0)
                        " 高抛区(${"%.2f".format(s.highZoneLow)}~${"%.2f".format(s.highZoneHigh)})"
                    else ""
                    appendLine("    ${s.stockName} ${s.trendDirection} 置信度${s.confidence}%$zone")
                }
            }
            if (pairSignals.isNotEmpty()) {
                appendLine("  🔵 配对腿: ${pairSignals.size} 个")
            }

            // 趋势预警
            val risingStocks = allSignals.filter {
                it.trendDirection.contains("上升") || it.trendDirection.contains("准备上升")
            }
            val fallingStocks = allSignals.filter {
                it.trendDirection.contains("下跌") || it.trendDirection.contains("准备下跌")
            }
            if (risingStocks.isNotEmpty()) {
                appendLine("  ⚡ 准备上升: ${risingStocks.joinToString { it.stockName }}")
            }
            if (fallingStocks.isNotEmpty()) {
                appendLine("  ⚡ 准备下跌: ${fallingStocks.joinToString { it.stockName }}")
            }
        }

        context.log("n_t_trade", summary)

        // 推送微信通知
        if (allSignals.isNotEmpty()) {
            try {
                TradeNotifier.sendTTradeSignals(context.androidContext, allSignals, "RealHolding")
            } catch (e: Exception) {
                Log.w(TAG, "发送做T通知失败: ${e.message}")
            }
        }

        return TTradeEvalResult(signals = allSignals, summary = summary.trim())
    }
}
