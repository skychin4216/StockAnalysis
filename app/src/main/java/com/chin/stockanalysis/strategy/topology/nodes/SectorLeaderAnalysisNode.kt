package com.chin.stockanalysis.strategy.topology.nodes

import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.monitor.SectorSignalStore
import com.chin.stockanalysis.strategy.monitor.SectorTrendType
import com.chin.stockanalysis.strategy.topology.core.BaseNode
import com.chin.stockanalysis.strategy.topology.core.NodeType
import com.chin.stockanalysis.strategy.topology.core.PipelineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 单只持仓的龙头状态
 * @param sectorNames 该股命中的板块名列表
 * @param isLeader 是否位列板块成分股涨幅前3（龙头榜）
 * @param leaderChangePct 该股作为龙头的最新涨跌幅（非龙头时为所属板块首龙头涨跌幅）
 * @param sectorChangePct 主板块当日涨跌幅
 * @param trendType 板块趋势（STRONG/WEAK/PULLBACK_BUY/FISH_TAIL/DIVERGENT/NEUTRAL）
 * @param suggestion 对应操作建议
 * @param board 板块归属（主板/创业板/科创板/北交所）
 */
data class LeaderStateInfo(
    val sectorNames: List<String>,
    val isLeader: Boolean,
    val leaderChangePct: Double,
    val sectorChangePct: Double,
    val trendType: String,
    val suggestion: String,
    val board: String = ""
)

/**
 * 龙头状态分析结果
 * @param leaderStates 持仓代码（带 sh/sz 前缀）→ 龙头状态
 * @param summary 一句话摘要
 */
data class SectorLeaderAnalysisResult(
    val leaderStates: Map<String, LeaderStateInfo> = emptyMap(),
    val summary: String = ""
)

/**
 * ## 龙头状态分析 Node
 *
 * 读取真实持仓，对照 [SectorSignalStore]（由板块龙头监测器每 10 分钟刷新）判断每只持仓：
 * 1. 是否属于某板块的成分股涨幅前 3（龙头榜，自动覆盖 300 创业板 / 688 科创板）
 * 2. 所在板块趋势（强势 / 回调低吸 / 鱼尾 / 弱势 / 背离 / 中性）
 * 3. 龙头当日异动幅度
 *
 * 输出 [SectorLeaderAnalysisResult]，供买卖评估 / 做T评估参考。
 * 不改变上游数据流，作为旁路增强节点挂载于实仓分析 / 做T pipeline。
 */
class SectorLeaderAnalysisNode : BaseNode<Any, SectorLeaderAnalysisResult>(
    "n_leader_analysis", "龙头状态分析", NodeType.ENRICHMENT
) {

    override suspend fun execute(context: PipelineContext, input: Any): SectorLeaderAnalysisResult {
        val db = StockDatabase.getInstance(context.androidContext)
        val positions = withContext(Dispatchers.IO) { db.realPositionDao().getAllActive() }
        if (positions.isEmpty()) {
            context.log(nodeId, "无持仓，跳过龙头状态分析")
            return SectorLeaderAnalysisResult(summary = "无持仓")
        }

        val states = LinkedHashMap<String, LeaderStateInfo>()
        val lines = mutableListOf<String>()
        var leaderCount = 0

        for (pos in positions) {
            val pureCode = pos.stockCode.removePrefix("sh").removePrefix("sz").removePrefix("bj")
            val hits = SectorSignalStore.getLeaderSignals(pureCode)
            if (hits.isEmpty()) {
                lines.add("${pos.stockName}: 非板块龙头")
                continue
            }

            val sectorNames = hits.map { it.sectorName }
            // 取板块强度最高的信号作为主信号（STRONG > 低吸 > 中性 > 背离 > 鱼尾 > 弱势）
            val primary = hits.maxByOrNull { strength(it.trend.trendType) } ?: hits.first()
            val leader = hits.flatMap { it.leaders }.firstOrNull { it.code == pureCode }
            val trendType = primary.trend.trendType
            val (label, suggestion) = describe(trendType)
            if (leader != null) leaderCount++

            states[pos.stockCode] = LeaderStateInfo(
                sectorNames = sectorNames,
                isLeader = leader != null,
                leaderChangePct = leader?.changePercent ?: primary.leaderChangePct,
                sectorChangePct = primary.sectorChangePct,
                trendType = trendType.name,
                suggestion = suggestion,
                board = leader?.board ?: ""
            )
            val boardNote = if (leader != null) "(${leader.board})" else ""
            val chg = leader?.changePercent ?: primary.leaderChangePct
            lines.add("${pos.stockName}${boardNote}: ${if (leader != null) "龙头" else "跟随"} ${sectorNames.joinToString("/")} ${label} 龙头${"%.1f".format(chg)}%")
        }

        val summary = "共${positions.size}只持仓，${leaderCount}只属板块龙头；" + lines.joinToString("；")
        context.log(nodeId, "📊 $summary")
        val result = SectorLeaderAnalysisResult(states, summary)
        context.setStageOutput(nodeId, result)
        return result
    }

    /** 板块趋势强度评分（数值越高越积极） */
    private fun strength(t: SectorTrendType): Int = when (t) {
        SectorTrendType.STRONG -> 3
        SectorTrendType.PULLBACK_BUY -> 2
        SectorTrendType.NEUTRAL -> 1
        SectorTrendType.DIVERGENT -> 0
        SectorTrendType.FISH_TAIL -> -1
        SectorTrendType.WEAK -> -2
    }

    /** 趋势 → (状态标签, 操作建议) */
    private fun describe(t: SectorTrendType): Pair<String, String> = when (t) {
        SectorTrendType.STRONG -> "强势龙头" to "板块强势，持有为主，回踩可低吸"
        SectorTrendType.PULLBACK_BUY -> "回调低吸" to "龙头回调企稳，可低吸做T"
        SectorTrendType.FISH_TAIL -> "鱼尾行情" to "鱼尾行情，谨慎追高，逢高减仓"
        SectorTrendType.WEAK -> "弱势板块" to "板块弱势，优先减仓防补跌"
        SectorTrendType.DIVERGENT -> "龙头背离" to "龙头与板块背离，谨慎防补跌"
        SectorTrendType.NEUTRAL -> "中性观望" to "板块中性，维持持有"
    }
}
