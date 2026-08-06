package com.chin.stockanalysis.strategy.trade

import android.graphics.Color
import android.widget.*
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.HoldingPeriod
import com.chin.stockanalysis.strategy.topology.xml.DagTradeExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * ## 實倉管理 Tab — 用戶真實持倉 + 大盤分析 Pipeline
 *
 * 擁有獨立的 UseCase (real_holding) 和 DAG Pipeline：
 * - 大盤行情分析（冷/熱/溫和 + 板塊輪動方向）
 * - 結論適用於所有持倉股票的評估
 *
 * ### 週期自動分類規則
 * - 持倉 ≤1 天 → 超短線
 * - 持倉 2-14 天 → 短線
 * - 持倉 15-180 天 → 中線
 * - 持倉 >180 天 → 長線
 */
class RealHoldingQuantFragment : QuantFragmentBase() {

    companion object {
        private const val TAG = "RealHolding"
    }

    override fun getQuantType() = "RealHolding"

    /** 建倉按鈕 → 執行實倉分析 Pipeline（大盤行情 + 持倉評估） */
    override fun onBuildClick() {
        runDagPipeline(
            holdingPeriod = HoldingPeriod.MID,  // 實倉涵蓋所有週期，用 MID 作為默認
            useCaseId = "real_holding",
            orderType = "RealHolding",
            importDays = 30,
            titlePrefix = "實倉分析"
        )
    }

    override fun onFittingClick() {
        Toast.makeText(requireContext(), "實倉無擬合功能", Toast.LENGTH_SHORT).show()
    }

    override fun onBacktrackClick() {
        Toast.makeText(requireContext(), "實倉無回溯功能", Toast.LENGTH_SHORT).show()
    }

    override fun onClearClick() {
        Toast.makeText(requireContext(), "實倉數據不可清除", Toast.LENGTH_SHORT).show()
    }

    override fun initEngine() {
        super.initEngine()
    }

    override fun buildUI() {
        addTitleRow("🏦 實倉管理（真實持倉）", textSize = 18f)
        rootLayout.addView(createProgressRow())
        rootLayout.addView(createButtonRow())
        addSeparator()
        rootLayout.addView(createContentScrollArea())
        refreshPositions()
    }

    override fun refreshPositions() {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val report = withContext(Dispatchers.Default) {
                buildRealHoldingReport(ctx)
            }
            val contentArea = rootLayout.findViewWithTag<LinearLayout>("content_area")
            contentArea?.post {
                contentArea.removeAllViews()
                contentArea.addView(report)
            }
        }
    }

    private suspend fun buildRealHoldingReport(ctx: android.content.Context): LinearLayout {
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 4, 8, 4)
        }

        val db = StockDatabase.getInstance(ctx)
        val orders = withContext(Dispatchers.IO) {
            db.strategyTradeOrderDao().getRecent(500)
        }.filter { it.status == "BUYING" || it.status == "PENDING" || it.status == "HOLDING" }

        // 也讀取真實持倉
        val realPositions = withContext(Dispatchers.IO) {
            db.realPositionDao().getAllActive()
        }

        if (orders.isEmpty() && realPositions.isEmpty()) {
            container.addView(TextView(ctx).apply {
                text = "暫無持倉記錄\n\n點擊「📈建倉」執行大盤分析 Pipeline\n點擊「📦持倉」添加真實持倉"
                setTextColor(Color.GRAY)
                textSize = 14f
                setPadding(16, 32, 16, 32)
            })
            return container
        }

        val today = LocalDate.now()

        // 真實持倉區
        if (realPositions.isNotEmpty()) {
            container.addView(TextView(ctx).apply {
                text = "🏦 真實持倉 (${realPositions.size} 只)"
                setTextColor(Color.parseColor("#1565C0"))
                textSize = 14f
                setPadding(0, 8, 0, 4)
            })
            for (p in realPositions) {
                container.addView(TextView(ctx).apply {
                    text = buildString {
                        append("▸ ${p.stockName}(${p.stockCode}) ")
                        append("${p.quantity}股 ¥${"%.2f".format(p.avgBuyPrice)}")
                        if (p.periodType.isNotEmpty()) append(" [${p.periodType}]")
                    }
                    setTextColor(Color.parseColor("#333333"))
                    textSize = 12f
                    setPadding(16, 2, 0, 2)
                })
            }
        }

        // 策略持倉區
        if (orders.isNotEmpty()) {
            // 按週期分組
            val grouped = orders.groupBy { order ->
                val buyDate = try { LocalDate.parse(order.tradeDate) } catch (_: Exception) { today }
                val daysHeld = ChronoUnit.DAYS.between(buyDate, today).toInt().coerceAtLeast(0)
                classifyPeriod(daysHeld)
            }

            container.addView(TextView(ctx).apply {
                text = "📊 策略持倉 (${orders.size} 筆)"
                setTextColor(Color.parseColor("#E65100"))
                textSize = 14f
                setPadding(0, 12, 0, 4)
            })

            val periodOrder = listOf(HoldingPeriod.ULTRA_SHORT, HoldingPeriod.SHORT, HoldingPeriod.MID, HoldingPeriod.LONG)
            for (period in periodOrder) {
                val periodOrders = grouped[period] ?: continue
                val label = period.label
                val icon = period.icon

                container.addView(TextView(ctx).apply {
                    text = "$icon $label（${periodOrders.size} 筆）"
                    setTextColor(Color.parseColor("#E65100"))
                    textSize = 13f
                    setPadding(8, 8, 0, 2)
                })

                for (order in periodOrders) {
                    val buyDate = try { LocalDate.parse(order.tradeDate) } catch (_: Exception) { today }
                    val daysHeld = ChronoUnit.DAYS.between(buyDate, today).toInt().coerceAtLeast(0)
                    val pnl = order.profitPct

                    container.addView(TextView(ctx).apply {
                        text = buildString {
                            append("${order.stockName}(${order.stockCode}) ")
                            append("持倉${daysHeld}天 ")
                            append("買入¥${"%.2f".format(order.buyPrice)} ")
                            append("盈虧${"%.2f".format(pnl)}%")
                        }
                        setTextColor(if (pnl >= 0) Color.parseColor("#C62828") else Color.parseColor("#2E7D32"))
                        textSize = 12f
                        setPadding(16, 2, 0, 2)
                    })
                }
            }
        }

        return container
    }

    private fun classifyPeriod(daysHeld: Int): HoldingPeriod {
        return when {
            daysHeld <= 1 -> HoldingPeriod.ULTRA_SHORT
            daysHeld <= 14 -> HoldingPeriod.SHORT
            daysHeld <= 180 -> HoldingPeriod.MID
            else -> HoldingPeriod.LONG
        }
    }

    override fun getDefaultUseCaseId(): String = "real_holding"
}
