package com.chin.stockanalysis.strategy.topology.nodes

import android.util.Log
import com.chin.stockanalysis.strategy.models.StrategySignal
import com.chin.stockanalysis.strategy.topology.core.BaseNode
import com.chin.stockanalysis.strategy.topology.core.MergedSignalPool
import com.chin.stockanalysis.strategy.topology.core.NodeType
import com.chin.stockanalysis.strategy.topology.core.PipelineContext
import com.chin.stockanalysis.stock.database.StockDatabase

/**
 * ## 行业相对估值节点 (SectorRelativePeNode)
 *
 * 用户诉求（2026-09-04）：科技成长股不该拿「传统行业低动态 PE」的标尺衡量，
 * 而应与同行业/科技板块的 PE 分布对比——例如 AI 服务器 20x 相对传统行业显贵，
 * 但相对半导体/计算机同侪可能反而便宜；反之传统行业 20x 不一定便宜。
 *
 * 实现：以当日 daily_snapshot 全部有效 PE（东财动态 PE, f9）为样本，
 * 按板块(sector_stocks)分组取行业中位数 PE；
 * - 行业内有效样本 ≥ minPeerSamples(3) 只 → 用该行业中位数；
 * - 不足 → 升级「宽分类」中位数（科技成长大类 / 其余行业）兜底；
 * - 无板块映射 → 全池中位数兜底。
 *
 * 加权规则（只改 strength/reason，不剔除任何候选）：
 *  ratio=个股PE/同侪中位PE ≤0.75 → +8（相对行业低估）
 *  ratio ≤1.0                → +4（低于行业中位）
 *  ratio ≤1.5                → 0（估值中性）
 *  ratio ≤2.0                → -4（相对行业偏高）
 *  ratio >2.0                → -8（相对行业高估）
 *  亏损股(PE≤0) 不参与比较，仅打标提醒。
 */
class SectorRelativePeNode(
    private val topRatio: Double = 0.75,
    private val highRatio: Double = 2.0,
    private val minPeerSamples: Int = 3
) : BaseNode<Any, MergedSignalPool>("sector_relative_pe", "行业相对估值", NodeType.ENRICHMENT) {

    companion object {
        private const val TAG = "SectorRelativePe"

        /** 科技成长大类关键词（命中即按科技同侪比较，避免与传统行业混比） */
        private val TECH_KEYWORDS = listOf(
            "半导体", "电子", "元件", "计算机", "软件", "通信", "互联网", "传媒",
            "消费电子", "光学", "芯片", "集成电路", "算力", "人工智能", "AI", "信息技术", "游戏"
        )
    }

    override suspend fun execute(context: PipelineContext, input: Any): MergedSignalPool {
        val pool: MergedSignalPool = when (input) {
            is MergedSignalPool -> input
            else -> context.getStageOutput<MergedSignalPool>("sector_boost")
                ?: context.getStageOutput<MergedSignalPool>("signal_merge")
                ?: return MergedSignalPool(emptyMap(), emptyMap(), emptyList())
        }
        if (pool.boostedSignals.isEmpty()) return pool

        return try {
            val db = StockDatabase.getInstance(context.androidContext)
            val snaps = db.dailySnapshotDao().getByDate(context.tradeDate).associateBy { it.code }
            // stock_code -> 板块（GROUP BY 已去重为每只一个主板块；sector_stocks 仅覆盖热门板块）
            val sectorByCode = try {
                db.sectorStockDao().getAllStockSectorPairs()
                    .associate { it.stock_code to it.sector_name }
            } catch (_: Exception) {
                emptyMap()
            }

            // ① 板块样本：sector -> 有效 PE 列表
            val sectorPes = HashMap<String, MutableList<Double>>()
            for ((code, sec) in sectorByCode) {
                val pe = snaps[code]?.pe ?: 0.0
                if (pe <= 0) continue
                sectorPes.getOrPut(sec) { mutableListOf() }.add(pe)
            }
            // ② 宽分类样本：科技成长大类 / 其余（行业内样本不足时的兜底）
            val techPes = mutableListOf<Double>()
            val otherPes = mutableListOf<Double>()
            for ((sec, pes) in sectorPes) {
                (if (sec.isTech()) techPes else otherPes).addAll(pes)
            }
            val techMedian = median(techPes)
            val otherMedian = median(otherPes)
            val allMedian = median(techPes + otherPes)

            val enhanced = pool.boostedSignals.map { sig ->
                val pe = snaps[sig.stockCode]?.pe ?: 0.0
                if (pe <= 0) {
                    sig.copy(reason = "${sig.reason} · ⚠亏损股无相对估值")
                } else {
                    val sec = sectorByCode[sig.stockCode]
                    val secPes = sec?.let { sectorPes[it] }
                    // 选择同侪中位数：优先自身板块(样本足) → 宽分类(科技/其余) → 全池
                    val peerMedian = when {
                        secPes != null && secPes.size >= minPeerSamples -> median(secPes)
                        sec != null && sec.isTech() -> techMedian
                        sec != null -> otherMedian
                        else -> allMedian
                    }
                    if (peerMedian == null || peerMedian <= 0) {
                        sig // 无同侪样本可比，不动
                    } else {
                        val ratio = pe / peerMedian
                        val delta = when {
                            ratio <= topRatio -> 8
                            ratio <= 1.0 -> 4
                            ratio <= 1.5 -> 0
                            ratio <= highRatio -> -4
                            else -> -8
                        }
                        val peerLabel = when {
                            secPes != null && secPes.size >= minPeerSamples -> sec
                            sec != null && sec.isTech() -> "科技类"
                            sec != null -> "一般行业"
                            else -> "全市场"
                        }
                        val tag = when {
                            delta >= 8 -> "行业低估"
                            delta >= 4 -> "低于行业中位"
                            delta <= -8 -> "行业高估"
                            delta < 0 -> "高于行业中位"
                            else -> "估值中性"
                        }
                        val note = "·同侪PE ${ratioToText(ratio)}x(行业中位%.1f·%s·%s)".format(peerMedian, peerLabel, tag)
                        sig.copy(
                            strength = (sig.strength + delta).coerceIn(0, 100),
                            reason = "${sig.reason} $note"
                        )
                    }
                }
            }

            context.log(
                nodeId,
                "📤 $nodeName: 同侪PE加权完成(全池中位%.1f/科技类%.1f/其他%.1f, %d只候选)".format(
                    allMedian ?: 0.0, techMedian ?: 0.0, otherMedian ?: 0.0, enhanced.size
                )
            )
            pool.copy(boostedSignals = enhanced)
        } catch (e: Exception) {
            Log.e(TAG, "行业相对估值失败: ${e.message}", e)
            context.recordError(nodeId, "行业相对估值失败: ${e.message}")
            pool // 容错：失败透传原池，不阻断
        }
    }

    private fun ratioToText(ratio: Double): String =
        if (ratio >= 100) ratio.toInt().toString() else "%.2f".format(ratio)

    private fun String.isTech(): Boolean =
        TECH_KEYWORDS.any { contains(it, ignoreCase = true) }

    /** 有效 PE 中位数（无有效样本返回 null） */
    private fun median(values: Collection<Double>): Double? {
        val s = values.filter { it > 0 }.sorted()
        if (s.isEmpty()) return null
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }
}
