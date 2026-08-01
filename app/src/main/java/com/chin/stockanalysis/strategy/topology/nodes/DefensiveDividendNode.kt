package com.chin.stockanalysis.strategy.topology.nodes

import android.util.Log
import com.chin.stockanalysis.strategy.StrategyCategory
import com.chin.stockanalysis.strategy.models.SignalAction
import com.chin.stockanalysis.strategy.models.StrategySignal
import com.chin.stockanalysis.strategy.topology.core.NodeType
import com.chin.stockanalysis.strategy.topology.core.PipelineContext
import com.chin.stockanalysis.strategy.topology.core.PipelineNode
import com.chin.stockanalysis.stock.database.StockDatabase
import kotlin.math.abs

/**
 * ## 防守型高息節點 (DefensiveDividendNode)
 *
 * 大盤 BEARISH / OSCILLATION 時啟動，專門尋找「熊市避風港」：
 * 高股息、低估值、大市值、財務健康的防禦板塊股票。
 *
 * 設計理念：
 * - 大盤下跌 ≠ 沒有機會。資金避險時流入銀行/電力/高速公路等高息板塊。
 * - 常規策略在熊市產出為零時，此節點提供防守型候選。
 * - 輸出標記 strategyId="defensive_dividend"，SmartMoneyFilter 對其降閾（minScore=20）。
 *
 * 篩選條件（從 daily_snapshot v12 基本面欄位讀取）：
 * 1. PB < maxPb（默認 1.5）
 * 2. 市值 > minMarketCap（默認 500 億）
 * 3. 負債率 < maxDebt（默認 70%，銀行除外）
 * 4. PE > 0（非虧損）
 * 5. 板塊關鍵詞匹配（銀行/保險/電力/高速公路/煤炭/石油/電信）
 *
 * 僅加入 mid_term / long_term pipeline（短線/超短線靠均值回歸，不走防守）。
 */
class DefensiveDividendNode(
    private val maxPb: Double = 1.5,
    private val minMarketCap: Double = 500_0000_0000.0,  // 500 億（元）
    private val maxDebt: Double = 70.0,                   // 資產負債率上限 %（銀行豁免）
    private val maxCandidates: Int = 5
) : PipelineNode<Any, List<StrategySignal>> {

    override val nodeId: String = "defensive_dividend"
    override val nodeName: String = "防守高息"
    override val nodeType: NodeType = NodeType.STRATEGY

    companion object {
        private const val TAG = "DefensiveDividend"

        /** 防禦板塊關鍵詞 */
        private val DEFENSIVE_SECTORS = listOf(
            "銀行", "保險", "電力", "高速公路", "煤炭", "石油", "電信",
            "水務", "燃氣", "鐵路", "港口", "機場"
        )

        /** 高負債豁免板塊（銀行天然高負債） */
        private val HIGH_DEBT_EXEMPT = listOf("銀行", "保險")
    }

    override suspend fun execute(context: PipelineContext, input: Any): List<StrategySignal> {
        // ── 觸發條件：僅 BEARISH / OSCILLATION 時啟動 ──
        val marketReport = context.getMarketReport()
        val direction = marketReport?.trend?.direction ?: "UNKNOWN"
        if (direction != "BEARISH" && direction != "OSCILLATION") {
            context.log(nodeId, "$nodeName: 大盤 $direction，非防守模式，跳過")
            return emptyList()
        }

        context.log(nodeId, "📥 $nodeName: 大盤 $direction(強度${marketReport?.trend?.strength ?: 0})，啟動防守選股")

        val db = StockDatabase.getInstance(context.androidContext)

        return try {
            // 讀取最新快照數據
            val snapshots = db.dailySnapshotDao().getByDate(context.tradeDate)
            if (snapshots.isEmpty()) {
                context.log(nodeId, "$nodeName: 無快照數據，跳過")
                return emptyList()
            }

            // 讀取股票名稱
            val basics = db.stockBasicDao().getAll()
            val nameMap = basics.associate { it.code to it.name }

            // 第一輪：數值硬性過濾（快速淘汰大部分）
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
                context.log(nodeId, "📤 $nodeName: 數值過濾後無候選")
                return emptyList()
            }

            // 第二輪：板塊過濾（僅對數值通過的少量股票查詢板塊，避免 N+1）
            data class Candidate(
                val pre: PreCandidate, val sector: String, val changePct20d: Double, val score: Double
            )

            val candidates = mutableListOf<Candidate>()

            for (pre in preCandidates) {
                val sectors = db.sectorStockDao().getSectorNamesByStockCode(pre.code)
                val sectorStr = sectors.joinToString("/")
                val isDefensive = DEFENSIVE_SECTORS.any { kw -> sectors.any { it.contains(kw) } }
                if (!isDefensive) continue

                // 負債率檢查（銀行/保險豁免）
                val isExempt = HIGH_DEBT_EXEMPT.any { kw -> sectors.any { it.contains(kw) } }
                if (!isExempt && pre.debtToAsset > maxDebt && pre.debtToAsset > 0) continue

                // 近 20 日穩定性
                val recentSnaps = db.dailySnapshotDao().getByCode(pre.code, 20)
                val changePct20d = if (recentSnaps.size >= 10) {
                    val oldest = recentSnaps.minByOrNull { it.date }?.close ?: pre.close
                    if (oldest > 0) (pre.close - oldest) / oldest * 100 else 0.0
                } else 0.0
                if (changePct20d < -10.0) continue  // 暴跌票排除

                // 評分
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
                context.log(nodeId, "📤 $nodeName: 無符合條件的防守股")
                return emptyList()
            }

            // 轉換為 StrategySignal
            val signals = topCandidates.map { c ->
                StrategySignal(
                    stockCode = c.pre.code,
                    stockName = c.pre.name,
                    strategyId = "defensive_dividend",
                    category = StrategyCategory.VALUE,
                    strength = c.score.toInt(),
                    action = SignalAction.BUY,
                    reason = "防守高息: PB=${"%.2f".format(c.pre.pb)} ROE=${"%.1f".format(c.pre.roe)}% " +
                        "市值${(c.pre.marketCap / 1_0000_0000).toInt()}億 ${c.sector}"
                )
            }

            context.log(nodeId, "📤 $nodeName 輸出: ${signals.size} 只防守候選 " +
                topCandidates.joinToString(", ") { "${it.pre.name}(PB${"%.2f".format(it.pre.pb)})" })

            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = snapshots.size, outputCount = signals.size,
                filterCount = snapshots.size - signals.size,
                filterReason = "非防守板塊/估值超標",
                inputCodes = emptyList(),
                outputCodes = signals.map { it.stockCode }
            )

            signals

        } catch (e: Exception) {
            Log.e(TAG, "防守高息節點失敗: ${e.message}", e)
            context.recordError(nodeId, "防守高息失敗: ${e.message}")
            emptyList()
        }
    }
}
