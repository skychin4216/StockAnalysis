package com.chin.stockanalysis.stock.database

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.notification.TradeNotifier
import com.chin.stockanalysis.strategy.HoldingPeriod
import com.chin.stockanalysis.strategy.Strategy
import com.chin.stockanalysis.strategy.StrategyEngineHolder
import com.chin.stockanalysis.strategy.data.HistoricalDataFetcher
import com.chin.stockanalysis.strategy.topology.xml.DagTradeExecutor
import com.chin.stockanalysis.strategy.topology.xml.UseCaseLoader
import com.chin.stockanalysis.ui.TradingDayPickerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 盘中自动四周期全市场选股调度器（交易时段每 15 分钟一轮）。
 *
 * 机制（与工作台「一键建仓」useCommon 模式一致）：
 *   1. 先跑公共研判 usecase "common"，拿到 n_market_direction / n_pool / n_adaptive 等 stageOutputs；
 *   2. 依次对 4 个周期专属 usecase（ultra_short_period / short_term_period / mid_term_period / long_term_period）
 *      播种执行，saveAsAiOnly=true 只选股不建仓；
 *   3. DagTradeExecutor 落库：不写 user_watchlist（避免覆盖用户自选盯盘状态），AI 精选以 auto_ 前缀
 *      source 标记（监控/迁移自动跳过，调度器每轮开始清理上一轮残留）；
 *   4. 四周期登记到 AiSelectionQualityGate，最后一周期执行完成即自动过滤收敛；
 *   5. 读当天 auto_* 候选，与上一轮名单有变化才推送「仅系统通知栏」（不触发微信渠道）。
 *
 * 数据前提：当日 daily_snapshot 需足够（AppBackgroundRunner 启动同步 + 盘中 30 分钟优先池刷新保障），
 * 不足时本轮跳过等下一轮。
 */
object AutoPickScheduler {
    private const val TAG = "AutoPickScheduler"

    /** 调度周期 */
    private const val ROUND_INTERVAL_MS = 15 * 60 * 1000L
    private const val TICK_MS = 60 * 1000L

    /** 当日快照数量下限（不足说明当日数据未同步，放弃本轮） */
    private const val MIN_SNAPS = 120

    /** 通知标题固定 → 同 id 覆盖旧通知，避免通知栏堆积 */
    private const val NOTIFY_TITLE = "🤖 盘中自动选股·四周期"
    private const val AUTO_PREFIX = "auto_"

    private data class PeriodPlan(
        val usecaseId: String,
        val shortKey: String,
        val label: String,
        val holding: HoldingPeriod
    )

    /** 与 AiSelectionQualityGate.EXPECTED_PERIODS 保持一致的全 id usecase（seed 播种后走周期专属 pipeline） */
    private val PERIOD_PLANS = listOf(
        PeriodPlan("ultra_short_period", "ultra_short", "超短", HoldingPeriod.ULTRA_SHORT),
        PeriodPlan("short_term_period", "shortterm", "短线", HoldingPeriod.SHORT),
        PeriodPlan("mid_term_period", "midterm", "中线", HoldingPeriod.MID),
        PeriodPlan("long_term_period", "longterm", "长线", HoldingPeriod.LONG)
    )

    @Volatile
    private var job: Job? = null

    @Volatile
    private var roundBusy = false

    private var lastRoundStartAt = 0L

    @Volatile
    private var lastNotifiedSig = emptySet<String>()

    /** 由 AppBackgroundRunner.start 调用一次 */
    fun start(context: Context, scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            Log.i(TAG, "🚀 盘中自动四周期选股调度启动（交易时段每 15 分钟一轮）")
            while (isActive) {
                try {
                    tick(context)
                } catch (t: Throwable) {
                    Log.w(TAG, "tick 异常: ${t.message}")
                }
                delay(TICK_MS)
            }
        }
    }

    private suspend fun tick(context: Context) {
        // 非交易时段重置计时（下一交易时段进入即跑首轮）
        if (!ChinaMarketTradingHours.a股是否交易中()) {
            lastRoundStartAt = 0L
            return
        }
        if (roundBusy) return
        val now = System.currentTimeMillis()
        if (now - lastRoundStartAt < ROUND_INTERVAL_MS) return
        runRound(context.applicationContext)
    }

    private suspend fun runRound(context: Context) {
        if (roundBusy) return
        roundBusy = true
        lastRoundStartAt = System.currentTimeMillis()
        val startedAt = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        try {
            val db = StockDatabase.getInstance(context)
            val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

            // 1) 数据就绪检查：当日快照不足则本轮跳过（等后台同步完成后下一轮再跑）
            val snapCount = try { db.dailySnapshotDao().getByDate(today).size } catch (_: Exception) { 0 }
            if (snapCount < MIN_SNAPS) {
                Log.i(TAG, "⏭️ $startedAt 本轮跳过：当日快照仅 $snapCount 条（< $MIN_SNAPS），等待数据同步")
                return
            }

            // 2) 刷新当日实时行情（复用一键建仓数据准备，保障技术面/打分用当日数据）
            try {
                val fetcher = HistoricalDataFetcher(context)
                fetcher.refreshTodayRealtime(HistoricalDataFetcher.getTopStocks(context))
            } catch (_: Exception) {}

            // 3) 清理上一轮 auto_* AI 精选残留（本轮结果 = 全新一轮，避免堆积）
            try {
                db.aiSelectedStockDao().deleteByDateAndSourcePrefix(today, "$AUTO_PREFIX%")
            } catch (_: Exception) {}

            // 4) 市场公共研判（大盘→风格→板块 + 选股公共数据准备），产出播种 stageOutputs
            StrategyEngineHolder.init(context)
            val engine = StrategyEngineHolder.get()
            val commonStrategies = engine.getEnabledStrategiesByPeriod(HoldingPeriod.ULTRA_SHORT)
            UseCaseLoader.init(context, commonStrategies)
            val tradeDate = TradingDayPickerView.recentTradingDay().format(DateTimeFormatter.ISO_LOCAL_DATE)
            val seed: Map<String, Any?>
            try {
                val common = UseCaseLoader.run("common", tradeDate)
                seed = if (common.success) common.stageOutputs else emptyMap()
                if (seed.isEmpty()) Log.w(TAG, "⚠️ 公共研判未产出 stageOutputs，周期 pipeline 将按缺失处理")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ 公共研判异常，本轮放弃: ${e.message}", e)
                return
            }

            // 5) 四周期顺序执行（saveAsAiOnly + autoPick：不写自选，AI 精选用 auto_ 源标记）
            for (plan in PERIOD_PLANS) {
                try {
                    val strategies = engine.getEnabledStrategiesByPeriod(plan.holding)
                    if (strategies.isEmpty()) {
                        Log.i(TAG, "[${plan.label}] 无启用策略，跳过")
                        continue
                    }
                    DagTradeExecutor.execute(
                        context = context,
                        useCaseId = plan.usecaseId,
                        tradeDate = tradeDate,
                        today = today,
                        strategies = strategies,
                        orderType = plan.shortKey,
                        importDays = 0,
                        saveAsAiOnly = true,
                        autoPick = true,
                        seedStageOutputs = seed
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "[自动选股-${plan.label}] 执行失败: ${e.message}", e)
                }
            }

            // 6) 读本轮候选并推送（仅系统通知栏；名单与上轮无变化不重复打扰）
            val total = try {
                db.aiSelectedStockDao().getByDate(today).count { it.source.startsWith(AUTO_PREFIX) }
            } catch (_: Exception) { 0 }
            notifyIfChanged(context, db, today)
            Log.i(TAG, "✅ $startedAt 自动选股本轮完成，候选 $total 只")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ 自动选股本轮异常: ${e.message}", e)
        } finally {
            roundBusy = false
        }
    }

    private suspend fun notifyIfChanged(context: Context, db: StockDatabase, today: String) {
        val rows = try {
            db.aiSelectedStockDao().getByDate(today).filter { it.source.startsWith(AUTO_PREFIX) }
        } catch (_: Exception) {
            return
        }
        if (rows.isEmpty()) return

        // 本轮信号指纹（stock + 分数）：仅用于正文标注是否与上轮一致
        val sig = rows.map { "${it.stockCode}_${it.score.toInt()}" }.toSet()
        val unchanged = sig.isNotEmpty() && sig == lastNotifiedSig
        lastNotifiedSig = sig

        // 每日节奏前导（08:00/09:00 情报：板块 + 候选 + 快讯），满足「09:30 综合 1 和 2 的信息」
        val preamble = try {
            DailyRhythmScheduler.roundPreamble(context)
        } catch (_: Exception) {
            ""
        }

        val bySource = rows.groupBy { it.source }
        val body = buildString {
            if (preamble.isNotBlank()) {
                append(preamble).append('\n')
            }
            append("🕙 ${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))} 盘中轮")
            if (unchanged) append("（名单较上轮无变化）")
            append("\n")
            for ((source, list) in bySource) {
                val label = PERIOD_PLANS.firstOrNull { "$AUTO_PREFIX${it.shortKey}" == source }?.label
                    ?: source.removePrefix(AUTO_PREFIX)
                val top = list.sortedByDescending { it.score }.take(5)
                append('「').append(label).append("」")
                append(' ')
                append(top.joinToString("  ") { st ->
                    "${st.stockName.take(4)} ${"%.0f".format(st.score)}分"
                })
                append('\n')
            }
            append("打开 App 查看「AI 精选」详情")
        }.trim()

        try {
            // 用户要求：09:30 起每 15 分钟推送一次 → 不再因名单未变而静默
            TradeNotifier.sendSystemOnly(context, NOTIFY_TITLE, body, tag = "AUTO_PICK_$today")
            Log.i(TAG, "🔔 自动选股通知已推送（${rows.size} 只候选${if (unchanged) "，名单未变" else ""}）")
        } catch (e: Exception) {
            Log.w(TAG, "自动选股通知失败: ${e.message}")
        }
        // 推送账本留痕（新闻 + 推送的股票，满足「存储到数据库」）
        try {
            DailyRhythmScheduler.logRound(
                context, NOTIFY_TITLE, body, rows.map { it.stockCode }
            )
        } catch (_: Exception) {
        }
    }
}
