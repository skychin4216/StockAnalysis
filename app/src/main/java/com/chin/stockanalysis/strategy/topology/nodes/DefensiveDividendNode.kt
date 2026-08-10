package com.chin.stockanalysis.strategy.topology.nodes

import android.util.Log
import com.chin.stockanalysis.strategy.StrategyCategory
import com.chin.stockanalysis.strategy.models.SignalAction
import com.chin.stockanalysis.strategy.models.StrategySignal
import com.chin.stockanalysis.strategy.topology.core.BaseNode
import com.chin.stockanalysis.strategy.topology.core.NodeType
import com.chin.stockanalysis.strategy.topology.core.PipelineContext
import com.chin.stockanalysis.strategy.topology.core.PipelineNode
import com.chin.stockanalysis.stock.database.StockDatabase
import kotlin.math.abs

/**
 * ## 防守型高息节点 (DefensiveDividendNode)
 *
 * 大盘 BEARISH / OSCILLATION 时启动，专门寻找「熊市避风港」：
 * 高股息、低估值、大市值、财务健康的防御板块股票。
 *
 * 设计理念：
 * - 大盘下跌 ≠ 没有机会。资金避险时流入银行/电力/高速公路等高息板块。
 * - 常规策略在熊市产出为零时，此节点提供防守型候选。
 * - 输出标记 strategyId="defensive_dividend"，SmartMoneyFilter 对其降阈（minScore=20）。
 *
 * 筛选条件（从 daily_snapshot v12 基本面栏位读取）：
 * 1. PB < maxPb（默认 1.5）
 * 2. 市值 > minMarketCap（默认 500 亿）
 * 3. 负债率 < maxDebt（默认 70%，银行除外）
 * 4. PE > 0（非亏损）
 * 5. 板块关键词匹配（银行/保险/电力/高速公路/煤炭/石油/电信）
 *
 * 仅加入 mid_term / long_term pipeline（短线/超短线靠均值回归，不走防守）。
 */
class DefensiveDividendNode(
    private val maxPb: Double = 1.5,
    private val minMarketCap: Double = 500_0000_0000.0,  // 500 亿（元）
    private val maxDebt: Double = 70.0,                   // 资产负债率上限 %（银行豁免）
    private val maxCandidates: Int = 5
) : BaseNode<Any, List<StrategySignal>>("defensive_dividend", "防守高息", NodeType.STRATEGY) {

    companion object {
        private const val TAG = "DefensiveDividend"

        /** 防御板块关键词 */
        private val DEFENSIVE_SECTORS = listOf(
            "银行", "保险", "电力", "高速公路", "煤炭", "石油", "电信",
            "水务", "燃气", "铁路", "港口", "机场"
        )

        /** 高负债豁免板块（银行天然高负债） */
        private val HIGH_DEBT_EXEMPT = listOf("银行", "保险")
    }

    override suspend fun execute(context: PipelineContext, input: Any): List<StrategySignal> {
        // ── 触发条件：仅 BEARISH / OSCILLATION 时启动 ──
        val marketReport = context.getMarketReport()
        val direction = marketReport?.trend?.direction ?: "UNKNOWN"
        if (direction != "BEARISH" && direction != "OSCILLATION") {
            context.log(nodeId, "$nodeName: 大盘 $direction，非防守模式，跳过")
            return emptyList()
        }

        context.log(nodeId, "📥 $nodeName: 大盘 $direction(强度${marketReport?.trend?.strength ?: 0})，启动防守选股")

        val db = StockDatabase.getInstance(context.androidContext)

        return try {
            // 读取最新快照数据
            val snapshots = db.dailySnapshotDao().getByDate(context.tradeDate)
            if (snapshots.isEmpty()) {
                context.log(nodeId, "$nodeName: 无快照数据，跳过")
                return emptyList()
            }

            // 读取股票名称
            val basics = db.stockBasicDao().getAll()
            val nameMap = basics.associate { it.code to it.name }

            // 第一轮：数值硬性过滤（快速淘汰大部分）
            data class PreCandidate(
                val code: String, val name: String,
                val pb: Double, val pe: Double, val marketCap: Double,
                val roe: Double, val debtToAsset: Double, val close: Double
            )

            val preCandidates = snapshots.mapNotNull { snap ->
                if (snap.pb <= 0 || snap.pb >= maxPb) return@mapNotNull null
                if (snap.pe <= 0) return@mapNotNull null
                if (snap.marketCap < minMarketCap) return@mapNotNull null
                PreCandidate(
                    code = snap.code, name = nameMap[snap.code] ?: snap.code,
                    pb = snap.pb, pe = snap.pe, marketCap = snap.marketCap,
                    roe = snap.roeTTM, debtToAsset = snap.debtToAsset, close = snap.close
                )
            }

            if (preCandidates.isEmpty()) {
                context.log(nodeId, "📤 $nodeName: 数值过滤后无候选")
                return emptyList()
            }

            // 第二轮：板块过滤（仅对数值通过的少量股票查询板块，避免 N+1）
            data class Candidate(
                val pre: PreCandidate, val sector: String, val changePct20d: Double, val score: Double
            )

            val candidates = mutableListOf<Candidate>()

            for (pre in preCandidates) {
                val sectors = db.sectorStockDao().getSectorNamesByStockCode(pre.code)
                val sectorStr = sectors.joinToString("/")
                val isDefensive = DEFENSIVE_SECTORS.any { kw -> sectors.any { it.contains(kw) } }
                if (!isDefensive) continue

                // 负债率检查（银行/保险豁免）
                val isExempt = HIGH_DEBT_EXEMPT.any { kw -> sectors.any { it.contains(kw) } }
                if (!isExempt && pre.debtToAsset > maxDebt && pre.debtToAsset > 0) continue

                // 近 20 日稳定性
                val recentSnaps = db.dailySnapshotDao().getByCode(pre.code, 20)
                val changePct20d = if (recentSnaps.size >= 10) {
                    val oldest = recentSnaps.minByOrNull { it.date }?.close ?: pre.close
                    if (oldest > 0) (pre.close - oldest) / oldest * 100 else 0.0
                } else 0.0
                if (changePct20d < -10.0) continue  // 暴跌票排除

                // 评分
                val pbScore = (maxPb - pre.pb) / maxPb * 30.0
                val roeScore = (pre.roe.coerceAtMost(20.0) / 20.0) * 20.0
                val capScore = (pre.marketCap / 2000_0000_0000.0).coerceAtMost(1.0) * 20.0
                val sectorScore = 15.0
                val stabilityScore = (1.0 - abs(changePct20d) / 10.0).coerceIn(0.0, 1.0) * 15.0
                val totalScore = pbScore + roeScore + capScore + sectorScore + stabilityScore

                candidates.add(Candidate(pre, sectorStr, changePct20d, totalScore))
            }

            // 排序取 Top N
            val topCandidates = candidates.sortedByDescending { it.score }.take(maxCandidates)

            if (topCandidates.isEmpty()) {
                context.log(nodeId, "📤 $nodeName: 无符合条件的防守股")
                return emptyList()
            }

            // 转换为 StrategySignal
            val signals = topCandidates.map { c ->
                StrategySignal(
                    stockCode = c.pre.code,
                    stockName = c.pre.name,
                    strategyId = "defensive_dividend",
                    category = StrategyCategory.VALUE,
                    strength = c.score.toInt(),
                    action = SignalAction.BUY,
                    reason = "防守高息: PB=${"%.2f".format(c.pre.pb)} ROE=${"%.1f".format(c.pre.roe)}% " +
                        "市值${(c.pre.marketCap / 1_0000_0000).toInt()}亿 ${c.sector}"
                )
            }

            context.log(nodeId, "📤 $nodeName 输出: ${signals.size} 只防守候选 " +
                topCandidates.joinToString(", ") { "${it.pre.name}(PB${"%.2f".format(it.pre.pb)})" })

            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = snapshots.size, outputCount = signals.size,
                filterCount = snapshots.size - signals.size,
                filterReason = "非防守板块/估值超标",
                inputCodes = emptyList(),
                outputCodes = signals.map { it.stockCode }
            )

            signals

        } catch (e: Exception) {
            Log.e(TAG, "防守高息节点失败: ${e.message}", e)
            context.recordError(nodeId, "防守高息失败: ${e.message}")
            emptyList()
        }
    }
}
