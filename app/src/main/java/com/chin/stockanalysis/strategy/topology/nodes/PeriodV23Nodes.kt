package com.chin.stockanalysis.strategy.topology.nodes

import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.backtest.BacktestParamsLoader
import com.chin.stockanalysis.strategy.data.DirectionAnalyzer
import com.chin.stockanalysis.strategy.data.IndividualDirection
import com.chin.stockanalysis.strategy.data.IndustrySeasonalityCalendar
import com.chin.stockanalysis.strategy.data.LeaderStockPool
import com.chin.stockanalysis.strategy.data.LeaderTracker
import com.chin.stockanalysis.strategy.models.StrategySignal
import com.chin.stockanalysis.strategy.topology.core.*

/**
 * ## v6 节点一：个股方向标签（先判方向，再定周期）
 *
 * 对每只候选股判定方向（上升/下降/蓄势/突破/震荡），
 * 中线/长线将「下降趋势/区间震荡」剔除（默认），其余周期按需配置。
 *
 * 输入：MergedSignalPool（须在 strict_selection 之后运行）
 * 输出：携带 direction 标签的 MergedSignalPool；context.stageOutputs["direction_labels"]
 *
 * XML 用法：`<node module="direction_label" />`
 *   - exclude="DOWNTREND,OSCILLATION" 要剔除的方向（默认两者）
 *   - penalty="25" 剔除方向的扣分（低于后续过滤线则被淘汰）
 */
class DirectionLabelNode(
    private val exclude: List<String> = listOf("DOWNTREND", "OSCILLATION"),
    private val penalty: Int = 25
) : BaseNode<Any, MergedSignalPool>("direction_label", "个股方向标签", NodeType.FILTER) {

    override suspend fun execute(context: PipelineContext, input: Any): MergedSignalPool {
        val pool = input as? MergedSignalPool
            ?: return MergedSignalPool(emptyMap(), emptyMap(), emptyList())
        if (pool.boostedSignals.isEmpty()) return pool

        // 复用 strict_selection 已算好的粘合指标（存在则免于重复计算）
        val evalDetail = context.getStageOutput<StockEvaluationResult>("strict_selection_eval")

        return try {
            val db = StockDatabase.getInstance(context.androidContext)
            val dao = db.dailySnapshotDao()

            // 先批量取 K 线（suspend 调用必须在 execute 内顺序执行）
            val snapCache = HashMap<String, List<com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity>>()
            val needSnaps = pool.boostedSignals.filter { s ->
                val d = evalDetail?.passedStocks?.get(s.stockCode)
                d == null
            }
            for (s in needSnaps) {
                snapCache[s.stockCode] = try {
                    dao.getByCode(s.stockCode, 65).sortedBy { it.date }
                } catch (_: Exception) { emptyList() }
            }

            val labels = LinkedHashMap<String, IndividualDirection>()
            var excludedCount = 0
            val adjusted = pool.boostedSignals.map { signal ->
                val dir = computeDirection(signal, evalDetail?.passedStocks?.get(signal.stockCode), snapCache[signal.stockCode])
                labels[signal.stockCode] = dir
                if (dir.name in exclude) {
                    excludedCount++
                    val newStrength = (signal.strength - penalty).coerceIn(0, 100)
                    signal.copy(
                        strength = newStrength,
                        details = signal.details + ("direction" to "⛔${dir.cn}(${signal.strength}→$newStrength)")
                    )
                } else {
                    signal.copy(
                        details = signal.details + ("direction" to "🧭${dir.cn}")
                    )
                }
            }.sortedByDescending { it.strength }

            context.setStageOutput("direction_labels", labels)
            val dist = labels.values.groupingBy { it.name }.eachCount()
                .toSortedMap().map { (k, v) -> "$k:$v" }.joinToString(" ")
            context.log(nodeId, "🧭 $nodeName: ${labels.size} 只打方向标签($dist)，" +
                "剔除[$exclude] $excludedCount 只")

            MergedSignalPool(pool.stockHits, pool.stockNames, adjusted)
        } catch (e: Exception) {
            context.log(nodeId, "⚠ $nodeName 执行异常: ${e.message}")
            pool
        }
    }

    /** 单股方向判定：优先用 strict_selection 评估结果，缺数据时从 K 线重算 */
    private fun computeDirection(
        signal: StrategySignal,
        detail: StockEvaluationDetail?,
        snaps: List<com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity>?
    ): IndividualDirection {
        var ma5 = 0.0
        var ma10 = 0.0
        var ma20 = 0.0
        var ma60Rising = detail?.ma60Rising ?: false
        var convergenceDegree = detail?.convergenceDegree ?: 999.0
        var convergenceOk = detail?.convergenceOk ?: false
        var closeAboveTop = detail?.closeAboveConvergenceTop ?: false
        var aboveAllMAs = detail?.aboveAllMAs ?: false
        var volumeRatio = 1.0

        if (detail == null && snaps != null && snaps.size >= 30) {
            val closes = snaps.map { it.close }
            val latest = snaps.last()
            ma5 = closes.takeLast(5).average()
            ma10 = closes.takeLast(10).average()
            ma20 = closes.takeLast(20).average()
            val ma60 = if (closes.size >= 60) closes.takeLast(60).average() else null
            ma60Rising = ma60 != null && closes.size >= 66 && closes[closes.size - 61] < ma60
            val high20 = snaps.takeLast(20).maxOf { it.high }
            val low20 = snaps.takeLast(20).minOf { it.low }
            convergenceDegree = if (low20 > 0) (high20 - low20) / low20 * 100 else 999.0
            convergenceOk = convergenceDegree <= 6.0
            closeAboveTop = latest.close >= high20 * 0.995
            aboveAllMAs = latest.close > ma20
            val vol5 = snaps.takeLast(5).map { it.volume.toDouble() }.average()
            val vol20 = snaps.takeLast(20).map { it.volume.toDouble() }.average()
            volumeRatio = if (vol20 > 0) vol5 / vol20 else 1.0
        } else if (detail == null) {
            return IndividualDirection.OSCILLATION
        }

        return DirectionAnalyzer.analyze(
            close = signal.currentPrice,
            ma5 = ma5,
            ma10 = ma10,
            ma20 = ma20,
            ma60Rising = ma60Rising,
            convergenceDegree = convergenceDegree,
            convergenceDays = if (convergenceOk) 10 else 0,
            closeAboveConvergenceTop = closeAboveTop,
            volumeRatio = volumeRatio,
            aboveAllMAs = aboveAllMAs
        )
    }
}

/**
 * ## v6 节点二：行业季节/周期日历加分（②）
 *
 * 命中当期季节主题（春耕/夏季用电高峰/金九银十/年底备货…）或
 * 商品锚定（油价/锂价/铜价/金价方向确认）的板块，候选股 strength 加分。
 *
 * 输入：MergedSignalPool
 * 输出：加分后的 MergedSignalPool；context.stageOutputs["seasonality_themes"]
 *
 * XML 用法：`<node module="seasonality_boost" />`
 *   - multiplier="1.0" 加分倍率（默认 = 日历权重 ×10 × multiplier）
 */
class SeasonalityBoostNode(
    private val multiplier: Double = 1.0
) : BaseNode<Any, MergedSignalPool>("seasonality_boost", "行业季节日历", NodeType.FACTOR_COMPUTE) {

    override suspend fun execute(context: PipelineContext, input: Any): MergedSignalPool {
        val pool = input as? MergedSignalPool
            ?: return MergedSignalPool(emptyMap(), emptyMap(), emptyList())
        if (pool.boostedSignals.isEmpty()) return pool

        val month = try {
            context.tradeDate.takeIf { it.length >= 7 }?.substring(5, 7)?.toInt()
                ?: java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1
        } catch (_: Exception) {
            java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1
        }

        val anchors = BacktestParamsLoader.seasonalityAnchors(context.androidContext)
        if (!BacktestParamsLoader.seasonalityEnabled(context.androidContext)) {
            context.log(nodeId, "📅 $nodeName: seasonality.enabled=false，跳过")
            return pool
        }
        val active = IndustrySeasonalityCalendar.activeThemes(month, anchors)
        if (active.isEmpty()) {
            context.log(nodeId, "📅 $nodeName: ${month}月无生效季节主题（锚定:$anchors）")
            return pool
        }

        var boostedCount = 0
        val adjusted = pool.boostedSignals.map { signal ->
            val hit = IndustrySeasonalityCalendar.matchStock(signal.stockName, month, anchors)
            if (hit.isEmpty()) {
                signal
            } else {
                boostedCount++
                val bonus = (hit.sumOf { it.weight } * 10 * multiplier).toInt().coerceIn(1, 30)
                val newStrength = (signal.strength + bonus).coerceIn(0, 100)
                signal.copy(
                    strength = newStrength,
                    details = signal.details + ("seasonality" to "📅${hit.joinToString("+") { it.theme }}(+$bonus)")
                )
            }
        }.sortedByDescending { it.strength }

        context.setStageOutput("seasonality_themes", active.map { it.entry.theme })
        context.log(nodeId, "📅 $nodeName: ${month}月生效 ${active.size} 个主题，" +
            "${boostedCount} 只命中（${active.joinToString(" | ") { it.entry.theme }}）")

        return MergedSignalPool(pool.stockHits, pool.stockNames, adjusted)
    }
}

/**
 * ## v6 节点三：龙头股跟踪（③）
 *
 * 同一产业主线板块内，用 [LeaderTracker.leaderScore] 打分识别龙头；
 * 龙头 +8 分、状态标记（强势/走弱）；板块龙头与状态写入 context。
 *
 * 输入：MergedSignalPool（含 direction_labels 时可参考方向）
 * 输出：龙头加分后的 MergedSignalPool；context.stageOutputs["leader_map"] / ["leader_status"]
 *
 * XML 用法：`<node module="leader_track" />`
 *   - bonus="8" 龙头加分（默认 8）
 *   - onlyMainline="true" 只用产业主线板块（默认 true）
 */
class LeaderTrackNode(
    private val bonus: Int = 8,
    private val onlyMainline: Boolean = true
) : BaseNode<Any, MergedSignalPool>("leader_track", "龙头股跟踪", NodeType.ENRICHMENT) {

    override suspend fun execute(context: PipelineContext, input: Any): MergedSignalPool {
        val pool = input as? MergedSignalPool
            ?: return MergedSignalPool(emptyMap(), emptyMap(), emptyList())
        if (pool.boostedSignals.isEmpty()) return pool

        val configs = if (onlyMainline) LeaderStockPool.getMainlineConfigs(context.androidContext)
        else LeaderStockPool.getAllConfigs(context.androidContext)

        // code → 板块名（产业主线下的首个匹配子板块）
        val sectorOfCode = HashMap<String, String>()
        for (cfg in configs) {
            for (ss in cfg.subSectors) {
                for (c in ss.stocks) sectorOfCode.putIfAbsent(c, cfg.name)
            }
        }

        val labels = context.getStageOutput<Map<String, IndividualDirection>>("direction_labels")
        val items = pool.boostedSignals.map { s ->
            LeaderTracker.LeaderItem(
                code = s.stockCode,
                name = s.stockName,
                score = LeaderTracker.leaderScore(
                    strength = s.strength.toDouble(),
                    changePct = s.changePercent,
                    volumeRatio = 1.0,
                    passCount = 1,
                    direction = labels?.get(s.stockCode),
                    drawdownPct = 0.0
                )
            )
        }
        val (leaderMap, leaderCodes) = LeaderTracker.tagLeaders(items) { sectorOfCode[it] }
        if (leaderMap.isEmpty()) {
            context.log(nodeId, "🐲 $nodeName: 候选股未命中产业主线板块，跳过")
            return pool
        }

        var leaderCount = 0
        val adjusted = pool.boostedSignals.map { s ->
            if (s.stockCode in leaderCodes) {
                leaderCount++
                val newStrength = (s.strength + bonus).coerceIn(0, 100)
                val status = LeaderTracker.statusOf(s.changePercent, 0.0, true).cn
                s.copy(
                    strength = newStrength,
                    details = s.details + ("leader" to "🐲板块龙头(${sectorOfCode[s.stockCode]})(+$bonus)")
                        + ("leader_status" to status)
                )
            } else {
                s
            }
        }.sortedByDescending { it.strength }

        context.setStageOutput("leader_map", leaderMap)
        context.setStageOutput("leader_status", leaderMap.entries.joinToString(";") { (s, c) ->
            "$s:$c(${pool.stockNames[c] ?: ""})" })
        context.log(nodeId, "🐲 $nodeName: ${leaderMap.size} 个板块标龙头 " +
            leaderMap.entries.joinToString(" | ") { (s, c) -> "$s→${pool.stockNames[c] ?: c}" } +
            "；龙头加分 $bonus，共 $leaderCount 只")

        return MergedSignalPool(pool.stockHits, pool.stockNames, adjusted)
    }
}
