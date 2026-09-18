package com.chin.stockanalysis.strategy.topology.nodes

import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.data.EtfCacheSync
import com.chin.stockanalysis.strategy.models.StrategySignal
import com.chin.stockanalysis.strategy.topology.core.*
import kotlin.math.roundToInt

/**
 * ## 量价因子加权节点 (VolumePriceFactorNode)
 *
 * 与 Python 端 `usecase_pipeline.volume_price_factor`（assets/usecases）逐字段同构，
 * 双端读取同一 pipeline XML 的 config 参数。位置：n_merge → **n_vp** → n_boost
 * （覆盖 short/mid/long/ultra_short 各环境 pipeline）。
 *
 * 对 `signal_merge` 输出的候选信号逐只叠加可正可负的 delta：
 * 1) 相对强度：个股近 relWinDays 日涨幅 − 基准指数(默认沪深300 sh000300)
 *    同窗口涨幅；跑赢 ≥ rsHi% → +rsBonus；跑输 ≤ rsLo% → rsPenalty。
 *    基准优先取本地行情库(Room daily_snapshot 的 sh000300)，缺失时兜底
 *    读 EtfCacheSync 的本地 etf_cache.json（APK 每日自拉，含 sh000300）。
 * 2) 缩量档位：量比 vr = 当日量 / 前5日均量。vr < thinVr(0.5) 无量 → thinPenalty；
 *    vr ∈ [thinVr, shrinkVr) 缩量 → shrinkPenalty。
 * 3) 低价龙头加分：收盘价 ≤ lowPriceMax 且排名 ≤ leaderTopK → +leaderBonus。
 */
class VolumePriceFactorNode(
    /**
     * mode：auto=默认（Python 端按大盘状态：牛市全量/非牛市只惩罚，APK 端 pipeline 由
     * direction 选择运行，auto 只出现于牛市 XML=全量）；full/boost=全量加权
     * （追强加分+惩罚）；penalty=仅惩罚（无量/缩量/rs弱），去掉追强加分——A/B 拟合
     * 收敛结论：追强加分在震荡/熊市 analyze 低吸链上为负贡献，已显式写入非牛市 XML。
     */
    private val mode: String = "auto",
    private val benchmark: String = "sh000300",
    private val relWinDays: Int = 10,
    private val rsHi: Double = 3.0,
    private val rsBonus: Double = 4.0,
    private val rsLo: Double = -2.0,
    private val rsPenalty: Double = -3.0,
    private val thinVr: Double = 0.5,
    private val thinPenalty: Double = -4.0,
    private val shrinkVr: Double = 0.8,
    private val shrinkPenalty: Double = -2.0,
    private val lowPriceMax: Double = 10.0,
    private val leaderTopK: Int = 5,
    private val leaderBonus: Double = 3.0
) : BaseNode<Any, MergedSignalPool>("volume_price_factor", "量价因子加权", NodeType.ENRICHMENT) {

    private val fullBoost: Boolean
        get() = mode in setOf("full", "boost") || mode == "auto"

    companion object {
        private const val TAG = "VolumePriceFactorNode"
        private const val DETAIL_KEY = "volume_price"
    }

    override suspend fun execute(context: PipelineContext, input: Any): MergedSignalPool {
        val pool: MergedSignalPool = when (input) {
            is MergedSignalPool -> input
            else -> {
                context.log(nodeId, "$nodeName: 输入非 MergedSignalPool，跳过")
                return MergedSignalPool(emptyMap(), emptyMap(), emptyList())
            }
        }
        if (pool.boostedSignals.isEmpty()) return pool

        return try {
            val dao = StockDatabase.getInstance(context.androidContext).dailySnapshotDao()
            val nDays = relWinDays.coerceAtLeast(3)
            val need = maxOf(nDays + 2, 8)   // 至少 8 根：前5日均量 + N日窗口
            val benchRet = benchReturnPct(context, dao, nDays)
            if (benchRet == null) {
                context.log(nodeId, "$nodeName: 基准 $benchmark 不可用，相对强度项跳过（缩量/低价照常）")
            }

            var boostedCount = 0
            val boostedSignals = pool.boostedSignals.mapIndexed { idx, signal ->
                val (delta, tags) = factorDelta(dao, signal, nDays, benchRet, idx, fullBoost)
                if (delta != 0.0) boostedCount++
                if (delta == 0.0) {
                    signal
                } else {
                    signal.copy(
                        strength = (signal.strength + delta).roundToInt().coerceIn(0, 100),
                        details = signal.details + (DETAIL_KEY to tags.joinToString("；"))
                    )
                }
            }.sortedByDescending { it.strength }

            context.log(nodeId, "✅ $nodeName: ${boostedCount}/${pool.boostedSignals.size} 只获量价因子修正（基准 $benchmark, N=$nDays）")
            context.setStageOutput("vp_active", boostedCount > 0)
            context.recordStockFlow(nodeId, nodeName, pool.boostedSignals.size, boostedSignals.size, 0)

            MergedSignalPool(
                stockHits = pool.stockHits,
                stockNames = pool.stockNames,
                boostedSignals = boostedSignals
            )
        } catch (e: Exception) {
            Log.e(TAG, "量价因子异常: ${e.message}", e)
            context.log(nodeId, "⚠ $nodeName: 异常(${e.message})，原样通过")
            pool
        }
    }

    /**
     * 单只股票量价 delta 计算。snaps 由 dao.getByCode 返回（日期降序，最新在前）。
     * @return (delta, tags)
     */
    private suspend fun factorDelta(
        dao: com.chin.stockanalysis.strategy.backtest.DailySnapshotDao,
        signal: StrategySignal,
        nDays: Int,
        benchRet: Double?,
        rank: Int,
        fullBoost: Boolean
    ): Pair<Double, List<String>> {
        val snaps = dao.getByCode(signal.stockCode, maxOf(nDays + 2, 8))
        if (snaps.size <= nDays || snaps[nDays].close <= 0) return 0.0 to emptyList()

        val curClose = if (signal.currentPrice > 0) signal.currentPrice else snaps[0].close
        if (curClose <= 0) return 0.0 to emptyList()

        var delta = 0.0
        val tags = mutableListOf<String>()

        // ── 1) 相对基准强度（涨幅差 %）：加分项仅在 fullBoost(牛市/趋势语境)生效 ──
        if (benchRet != null && snaps[0].close > 0) {
            val rel = (snaps[0].close / snaps[nDays].close - 1.0) * 100.0 - benchRet
            if (rel >= rsHi) {
                if (fullBoost) {
                    delta += rsBonus
                    tags.add("rs强${"%.1f".format(rel)}%%+${fmt(rsBonus)}")
                }
            } else if (rel <= rsLo) {
                delta += rsPenalty
                tags.add("rs弱${"%.1f".format(rel)}%%${fmt(rsPenalty)}")
            }
        }

        // ── 2) 缩量/无量档位：vr = 当日量 / 前5日均量 ──
        if (snaps.size >= 6) {
            val volToday = snaps[0].volume
            val prev5 = (1..5).map { snaps[it].volume }
            val avg5 = if (prev5.isNotEmpty()) prev5.average() else 0.0
            if (volToday > 0 && avg5 > 0) {
                val vr = volToday / avg5
                if (vr < thinVr) {
                    delta += thinPenalty
                    tags.add("无量vr=${"%.2f".format(vr)}${fmt(thinPenalty)}")
                } else if (vr < shrinkVr) {
                    delta += shrinkPenalty
                    tags.add("缩量vr=${"%.2f".format(vr)}${fmt(shrinkPenalty)}")
                }
            }
        }

        // ── 3) 低价龙头加分（仅 fullBoost；非牛市语境下低价股不加分，弱反弹不该追）──
        if (fullBoost && curClose <= lowPriceMax && rank < leaderTopK) {
            delta += leaderBonus
            tags.add("低价龙头+${fmt(leaderBonus)}")
        }
        return delta to tags
    }

    /**
     * 基准指数近 N 日涨跌幅(%)：优先 Room daily_snapshot；缺失时兜底本地
     * etf_cache.json（EtfCacheSync 每日自拉含 sh000300）。snaps 统一按降序处理。
     */
    private suspend fun benchReturnPct(
        context: PipelineContext,
        dao: com.chin.stockanalysis.strategy.backtest.DailySnapshotDao,
        nDays: Int
    ): Double? {
        val room = dao.getByCode(benchmark, nDays + 2)
        retPctOf(room.map { it.close }, nDays)?.let { return it }

        // 兜底：手机本地 etf_cache.json（腾讯 qfq，含 sh000300）
        return runCatching {
            val cache = EtfCacheSync(context.androidContext).loadLocalCache()
                ?: return@runCatching null
            val snaps = cache.optJSONObject(benchmark)?.optJSONArray("snaps")
                ?: return@runCatching null
            val closes = (0 until snaps.length()).mapNotNull { i ->
                val c = snaps.optJSONObject(i).optDouble("close", 0.0)
                if (c > 0) c else null
            }
            retPctOf(closes.reversed(), nDays)  // etf snaps 升序 → 反转成降序
        }.getOrNull()
    }

    /** 降序 closes：最新 / n 日前 − 1 的百分比；不足 n+1 根返回 null */
    private fun retPctOf(closesDesc: List<Double>, nDays: Int): Double? {
        if (closesDesc.size <= nDays || closesDesc[nDays] <= 0) return null
        return (closesDesc[0] / closesDesc[nDays] - 1.0) * 100.0
    }

    private fun fmt(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)
}
