package com.chin.stockanalysis.strategy.topology.nodes

import com.chin.stockanalysis.strategy.monitor.SectorSignal
import com.chin.stockanalysis.strategy.monitor.SectorSignalStore
import com.chin.stockanalysis.strategy.monitor.SectorTrendType
import com.chin.stockanalysis.strategy.topology.core.BaseNode
import com.chin.stockanalysis.strategy.topology.core.NodeType
import com.chin.stockanalysis.strategy.topology.core.PipelineContext

/**
 * 市场板块龙头全景分析结果
 * @param strongSectors 强势板块（STRONG / PULLBACK_BUY，按涨幅降序）
 * @param weakSectors   弱势板块（WEAK / FISH_TAIL，按跌幅降序）
 * @param allSectors    全量板块信号（按当日涨幅降序）
 * @param summary       一句话摘要（含领涨板块前三名龙头）
 */
data class MarketSectorLeadersResult(
    val strongSectors: List<SectorSignal> = emptyList(),
    val weakSectors: List<SectorSignal> = emptyList(),
    val allSectors: List<SectorSignal> = emptyList(),
    val summary: String = ""
)

/**
 * ## 市场板块龙头全景 Node
 *
 * 集成板块龙头前三名监测到市场环境分析（一键建仓 common pipeline）：
 * 读取 [SectorSignalStore]（由板块龙头监测器周期刷新，每个板块含龙头榜前 N 名），
 * 输出当前市场各板块的龙头强弱全景，供市场环境研判 / 顺势选股参考。
 *
 * 挂载位置：`stock_picking_common_pipeline.xml` Layer 1，与股票池并行。
 * 不改变上游数据流，作为旁路增强节点。
 */
class MarketSectorLeadersNode : BaseNode<Any, MarketSectorLeadersResult>(
    "n_sector_leaders", "市场板块龙头全景", NodeType.ENRICHMENT
) {

    override suspend fun execute(context: PipelineContext, input: Any): MarketSectorLeadersResult {
        val all = SectorSignalStore.getAll().sortedByDescending { it.sectorChangePct }
        if (all.isEmpty()) {
            context.log(nodeId, "板块信号为空（龙头监测尚未运行或非交易时段），跳过龙头全景分析")
            return MarketSectorLeadersResult(summary = "板块龙头信号暂缺（监测未运行/非交易时段）")
        }

        // 强势/低吸板块（顺势参考），按涨幅降序
        val strong = all.filter {
            it.trend.trendType == SectorTrendType.STRONG || it.trend.trendType == SectorTrendType.PULLBACK_BUY
        }.sortedByDescending { it.sectorChangePct }

        // 弱势/鱼尾板块（规避参考），按涨幅升序（跌幅最大在前）
        val weak = all.filter {
            it.trend.trendType == SectorTrendType.WEAK || it.trend.trendType == SectorTrendType.FISH_TAIL
        }.sortedBy { it.sectorChangePct }

        // 摘要：领涨板块 Top5 龙头全景
        val topLines = all.take(5).joinToString("；") { s ->
            val leaders = s.leaders.take(3).joinToString("/") { "${it.name}${"%.1f".format(it.changePercent)}%" }
            "${s.sectorName}${"%.1f".format(s.sectorChangePct)}%[${s.trend.label}](${leaders})"
        }
        val summary = "板块龙头全景: ${all.size}个板块,强势${strong.size}个/弱势${weak.size}个。领涨Top5: $topLines"

        context.log(nodeId, "📊 $summary")
        val result = MarketSectorLeadersResult(strong, weak, all, summary)
        context.setStageOutput(nodeId, result)
        return result
    }
}
