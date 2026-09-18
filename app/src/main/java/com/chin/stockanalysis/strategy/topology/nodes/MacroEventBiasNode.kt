package com.chin.stockanalysis.strategy.topology.nodes

import android.content.Context
import com.chin.stockanalysis.strategy.topology.core.BaseNode
import com.chin.stockanalysis.strategy.topology.core.MergedSignalPool
import com.chin.stockanalysis.strategy.topology.core.NodeType
import com.chin.stockanalysis.strategy.topology.core.PipelineContext
import org.json.JSONObject
import java.time.LocalDate

/**
 * ## 宏观事件驱动节点（macro_event_bias，2026-09-05 新增）
 *
 * 数据单一事实源：assets/macro_events/event_library.json
 * （与 AutoQuant Python/exe 回放引擎、smalltools/_event_kb.py 共用同一事件库）
 *
 * 机制（与 Python `_macro_event_bias` 对齐）：
 * - 事件库 = event_types（类型定义：seed_map 关键词→基础 delta）+ instances（历史/当前实例：start/end/magnitude）；
 * - 节点评估"今天"（运行时 LocalDate）落在哪些实例窗口内；
 * - 命中事件按 seed_map × magnitude × boostScale 得到关键词效应；
 * - 对上游 MergedSignalPool 的信号按 stockName 命中关键词做 strength 加减（取 |delta| 大者，不叠加）；
 * - 只调权重不增删信号；事件库缺失/无活跃事件时原样透传，不阻断主流程。
 *
 * 动态判断：同一类型每次发生以 start/end/magnitude 区分强度与窗口；受益面由 seed_map
 * 关键词按当期候选名动态命中；实例结束后的实际板块表现可回写 learned（后续学习工具）。
 */
class MacroEventBiasNode(
    private val boostScale: Double = 1.0,
    private val label: String = "宏观事件驱动"
) : BaseNode<Any, MergedSignalPool>("macro_event_bias", label, NodeType.ENRICHMENT) {

    override suspend fun execute(context: PipelineContext, input: Any): MergedSignalPool {
        val pool: MergedSignalPool = when (input) {
            is MergedSignalPool -> input
            else -> context.getStageOutput<MergedSignalPool>("n_pe_peer")
                ?: context.getStageOutput<MergedSignalPool>("n_leader")
                ?: context.getStageOutput<MergedSignalPool>("n_boost")
                ?: context.getStageOutput<MergedSignalPool>("n_merge")
                ?: return MergedSignalPool(emptyMap(), emptyMap(), emptyList())
        }
        if (pool.boostedSignals.isEmpty()) return pool

        val today = LocalDate.now().toString()
        val eff = EventLibrary.loadEff(context.androidContext, today, boostScale)
        if (eff.isEmpty()) return pool

        val boosted = pool.boostedSignals.map { signal ->
            var add = 0.0
            for ((kw, d) in eff) {
                if (kw.isNotEmpty() && signal.stockName.contains(kw)) {
                    val adj = d
                    if (add == 0.0 || kotlin.math.abs(adj) > kotlin.math.abs(add)) add = adj
                }
            }
            if (add != 0.0) {
                signal.copy(
                    strength = (signal.strength + add)
                        .toInt().coerceIn(0, 100)
                )
            } else signal
        }
        val out = pool.copy(boostedSignals = boosted)
        val changed = boosted.count { s ->
            pool.boostedSignals.find { it.stockCode == s.stockCode && it.strategyId == s.strategyId }
                ?.strength != s.strength
        }
        context.log(nodeId, "宏观事件加权: 事件=${EventLibrary.lastEventNames} 命中调整=${changed}只")
        return out
    }
}

/** 事件库加载缓存（assets JSON 单一源，与 Python/smalltools 同构） */
object EventLibrary {
    private const val ASSET = "macro_events/event_library.json"
    private var cachedRaw: String? = null
    private var cachedTypes: Map<String, JSONObject> = emptyMap()
    private var cachedInstances: List<JSONObject> = emptyList()
    var lastEventNames: String = ""
        private set

    fun loadRaw(context: Context): String? {
        cachedRaw?.let { return it }
        return try {
            context.assets.open(ASSET).bufferedReader().use { it.readText() }.also { cachedRaw = it }
        } catch (e: Exception) {
            null
        }
    }

    private fun ensureLoaded(context: Context) {
        if (cachedTypes.isNotEmpty() || cachedRaw != null) return
        val raw = loadRaw(context) ?: return
        try {
            val root = JSONObject(raw)
            val types = mutableMapOf<String, JSONObject>()
            val typesArr = root.optJSONArray("event_types") ?: return
            for (i in 0 until typesArr.length()) {
                val t = typesArr.getJSONObject(i)
                types[t.optString("id")] = t
            }
            cachedTypes = types
            val instArr = root.optJSONArray("instances")
            cachedInstances = if (instArr != null) {
                (0 until instArr.length()).map { instArr.getJSONObject(it) }
            } else emptyList()
        } catch (e: Exception) {
            cachedRaw = null
        }
    }

    /** 聚合活跃事件效应：关键词 → delta×magnitude×boostScale（同类型并存取 |delta| 大者） */
    fun loadEff(context: Context, asof: String, boostScale: Double = 1.0): Map<String, Double> {
        ensureLoaded(context)
        if (cachedTypes.isEmpty()) return emptyMap()
        val eff = mutableMapOf<String, Double>()
        val names = mutableListOf<String>()
        for (ins in cachedInstances) {
            val start = ins.optString("start", "9999")
            val end = ins.optString("end", "9999")
            if (asof < start || asof > end) continue
            val type = cachedTypes[ins.optString("event")] ?: continue
            names.add(type.optString("name", ins.optString("event")))
            val mag = ins.optDouble("magnitude", 1.0)
            val seed = type.optJSONObject("seed_map") ?: continue
            val keys = seed.keys()
            while (keys.hasNext()) {
                val kw = keys.next()
                val adj = seed.optDouble(kw, 0.0) * mag * boostScale
                val old = eff[kw]
                if (old == null || kotlin.math.abs(adj) > kotlin.math.abs(old)) eff[kw] = adj
            }
        }
        lastEventNames = names.joinToString(" | ")
        return eff
    }
}
