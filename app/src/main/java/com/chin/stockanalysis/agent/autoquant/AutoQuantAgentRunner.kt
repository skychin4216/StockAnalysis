package com.chin.stockanalysis.agent.autoquant

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.notification.TradeNotifier
import com.chin.stockanalysis.stock.database.ChinaMarketTradingHours
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.stock.realtime.RealtimeConfig
import com.chin.stockanalysis.strategy.monitor.SectorSignalStore
import com.chin.stockanalysis.strategy.monitor.SectorTrendType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong

/**
 * ## AutoQuant 自主决策 Agent（Android 后台版）
 *
 * 对齐 AutoQuant/run_agent.py 的 OTDAR 闭环（仅用实时数据，可直接指导实盘）：
 * - **OBSERVE**：真实持仓 + `RealtimeDataAccessor` 实时价 + `SectorSignalStore` 板块信号 + 上证指数大盘状态
 * - **THINK / DECIDE**：动作优先级 `EMERGENCY > STOP > PROFIT > ADD > T > HOLD`
 * - **ACT / REVIEW**：生成决策报告（通知 + log + SharedPreferences 持久化）
 *
 * 仅在 A股交易时段内执行，同一交易时段 60 分钟限流一次。
 */
object AutoQuantAgentRunner {

    private const val TAG = "AutoQuantAgent"

    /** 轮询间隔：每 30 分钟检查一次是否满足执行条件 */
    private const val CHECK_INTERVAL_MS = 30 * 60 * 1000L

    /** 同一交易时段内两次决策的最小间隔（防频繁打扰） */
    private const val RUN_COOLDOWN_MS = 60 * 60 * 1000L

    // ── 阈值（与 run_agent.py AgentConfig 对齐）──
    private const val STOP_LOSS_PCT = -8.0        // 止损
    private const val TAKE_PROFIT_PCT = 15.0      // 止盈 1/3
    private const val TAKE_PROFIT_MAX_PCT = 20.0  // 止盈清仓
    private const val CRISIS_INDEX_PCT = -3.0     // 大盘恶化（清仓）
    private const val WEAKENING_INDEX_PCT = -1.0  // 大盘走弱

    @Volatile
    private var isRunning = false
    private val lastRunAt = AtomicLong(0L)

    /** 启动后台 Agent（幂等） */
    fun start(context: Context, scope: CoroutineScope) {
        if (isRunning) return
        isRunning = true
        scope.launch(Dispatchers.IO) {
            Log.i(TAG, "🤖 AutoQuant Agent 已启动（仅交易时段，每 30 分钟检查 / 60 分钟限流）")
            while (isActive) {
                try {
                    if (ChinaMarketTradingHours.a股是否交易中() &&
                        System.currentTimeMillis() - lastRunAt.get() >= RUN_COOLDOWN_MS
                    ) {
                        runAgentOnce(context)
                        lastRunAt.set(System.currentTimeMillis())
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Agent 决策异常: ${e.message}")
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    /**
     * 执行一轮完整决策（可手动触发，如收盘复盘）
     * @return 决策报告文本
     */
    suspend fun runAgentOnce(context: Context): String {
        val db = StockDatabase.getInstance(context)
        val positions = withContext(Dispatchers.IO) { db.realPositionDao().getAllActive() }
        if (positions.isEmpty()) {
            Log.i(TAG, "🤖 无持仓，Agent 本轮无决策")
            return "无持仓，本轮无决策"
        }

        // ═══════════ OBSERVE：实时价 + 大盘 + 板块信号 ═══════════
        val accessor = RealtimeConfig.createAccessor()
        val codes = positions.map { it.stockCode } + "sh000001"
        val quotes = accessor.fetchRealtime(codes)

        val index = quotes["sh000001"]
        val indexChg = index?.changePercent ?: 0.0
        val marketState = marketStateOf(indexChg)
        val indexNote = "上证${"%.1f".format(indexChg)}%"

        val sectorSignals = SectorSignalStore.getAll()
        val weakSectorCount = sectorSignals.count {
            it.trend.trendType == SectorTrendType.WEAK || it.trend.trendType == SectorTrendType.FISH_TAIL
        }

        // ═══════════ THINK / DECIDE ═══════════
        val actionLines = mutableListOf<String>()
        var emergency = marketState == "CRISIS"
        var stopCount = 0; var profitCount = 0; var addCount = 0; var tCount = 0; var holdCount = 0

        if (emergency) {
            actionLines += "⚠️ 大盘恶化(CRISIS)，建议今日全部持仓清仓 / 大幅减仓"
        }

        for (pos in positions) {
            val q = quotes[pos.stockCode]
            val price = q?.price ?: pos.currentPrice
            if (price <= 0) {
                actionLines += "• ${pos.stockName}: 无实时价，跳过"
                continue
            }
            val cost = pos.avgBuyPrice
            val pnlPct = if (cost > 0) (price - cost) / cost * 100 else 0.0
            val qty = pos.quantity
            val pureCode = pos.stockCode.removePrefix("sh").removePrefix("sz").removePrefix("bj")
            val leaderHits = SectorSignalStore.getLeaderSignals(pureCode)
            val leaderNote = if (leaderHits.isNotEmpty()) {
                "龙头[${leaderHits.map { it.sectorName }.joinToString("/")}]"
            } else "非龙头"

            when {
                // EMERGENCY：大盘恶化 → 清仓
                emergency -> actionLines += "• ${pos.stockName}: 大盘CRISIS → 清仓"
                // STOP：止损
                pnlPct <= STOP_LOSS_PCT -> {
                    stopCount++
                    actionLines += "• ${pos.stockName}: 止损（盈亏${"%.1f".format(pnlPct)}%）→ 卖出 $qty 股"
                }
                // PROFIT：止盈
                pnlPct >= TAKE_PROFIT_MAX_PCT -> {
                    profitCount++
                    actionLines += "• ${pos.stockName}: 止盈清仓（盈亏${"%.1f".format(pnlPct)}%）→ 全部卖出"
                }
                pnlPct >= TAKE_PROFIT_PCT -> {
                    profitCount++
                    actionLines += "• ${pos.stockName}: 止盈1/3（盈亏${"%.1f".format(pnlPct)}%）→ 卖 ${qty / 3} 股"
                }
                // ADD：下跌有预期（现价<成本 且 板块强势/低吸 或属龙头）
                price < cost && canAdd(leaderHits) -> {
                    addCount++
                    val addQty = computeAddQty(qty, price, cost)
                    actionLines += "• ${pos.stockName}: 下跌加仓（现价${"%.2f".format(price)}<成本${"%.2f".format(cost)}，$leaderNote）→ 可加 $addQty 股"
                }
                // T：做T机会（优先盘中增强信号：震荡市/关键时段/拉升/急跌；再回退日K快照信号）
                else -> {
                    val tLine = try {
                        val tEngine = com.chin.stockanalysis.strategy.trade.TTradeEngine(context)
                        val period = pos.periodType.ifBlank { "ShortTermQuant" }
                        // v4：盘中增强信号（实时价 + 大盘状态 + 指数近30日快照）
                        val intraday = tEngine.generateIntradaySignals(
                            pos.stockCode, qty, period, q, indexChg, indexSnapsOf(context)
                        )
                        val signals = if (intraday.isNotEmpty()) intraday else {
                            tEngine.generateSignals(pos.stockCode, qty, period)
                        }
                        signals.firstOrNull()?.let {
                            "做T[${it.signalType}] ${"%.2f".format(it.suggestedPrice)}→目标${"%.2f".format(it.targetPrice)} ${"%.0f".format(it.expectedProfitPct)}%（${it.reason}）"
                        }
                    } catch (_: Exception) { null }
                    if (tLine != null) {
                        tCount++
                        actionLines += "• ${pos.stockName}: $tLine"
                    } else {
                        holdCount++
                        actionLines += "• ${pos.stockName}: 持有（盈亏${"%.1f".format(pnlPct)}%，$leaderNote）"
                    }
                }
            }
        }

        // ═══════════ ACT / REVIEW：报告 ═══════════
        val report = buildString {
            appendLine("🤖 AutoQuant 自主决策 ${LocalDate.now()}")
            appendLine("大盘: $indexNote ($marketState) | 弱势/鱼尾板块: $weakSectorCount 个")
            appendLine("持仓 ${positions.size} 只 | 清仓${if (emergency) "✓" else "✗"} 止损$stopCount 止盈$profitCount 加仓$addCount 做T$tCount 持有$holdCount")
            appendLine("── 决策明细 ──")
            actionLines.forEach { appendLine(it) }
            if (weakSectorCount > 0) {
                appendLine("⚠️ 存在 $weakSectorCount 个弱势/鱼尾板块，谨防持仓补跌")
            }
        }.trim()

        Log.i(TAG, report)

        // 有实质动作才推送通知
        if (emergency || stopCount > 0 || profitCount > 0 || addCount > 0 || tCount > 0) {
            try {
                TradeNotifier.send(context, "🤖 AutoQuant 决策", report, "AUTOQUANT_AGENT")
            } catch (_: Exception) {}
        }

        // 持久化最近报告
        try {
            context.getSharedPreferences("autoquant_agent", Context.MODE_PRIVATE)
                .edit()
                .putString("last_report", report)
                .putLong("last_run", System.currentTimeMillis())
                .apply()
        } catch (_: Exception) {}

        return report
    }

    /** 获取上证指数近30日日K快照（用于大盘震荡市判断） */
    private suspend fun indexSnapsOf(context: Context): List<com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity> = try {
        withContext(Dispatchers.IO) {
            StockDatabase.getInstance(context).dailySnapshotDao().getByCode("sh000001", 30)
        }
    } catch (_: Exception) { emptyList() }

    /** 大盘状态判定（对齐 MarketGuard 简化版） */
    private fun marketStateOf(indexChgPct: Double): String = when {
        indexChgPct <= CRISIS_INDEX_PCT -> "CRISIS"
        indexChgPct <= WEAKENING_INDEX_PCT -> "WEAKENING"
        else -> "NORMAL"
    }

    /** 是否可以下跌加仓：股票属板块龙头 且 板块趋势为强势/回调低吸 */
    private fun canAdd(leaderHits: List<com.chin.stockanalysis.strategy.monitor.SectorSignal>): Boolean {
        if (leaderHits.isEmpty()) return false
        return leaderHits.any {
            it.trend.trendType == SectorTrendType.STRONG ||
                it.trend.trendType == SectorTrendType.PULLBACK_BUY
        }
    }

    /**
     * 金字塔加仓数量：跌幅越深仓位越重（20%~50% 现有仓位），按 100 股整手取整
     */
    private fun computeAddQty(holdQty: Int, price: Double, cost: Double): Int {
        if (cost <= 0 || holdQty <= 0) return 0
        val depth = ((cost - price) / cost * 100).coerceIn(2.0, 20.0)
        val ratio = (0.20 + depth / 100.0).coerceAtMost(0.50)
        return (holdQty * ratio / 100).toInt() * 100
    }
}
