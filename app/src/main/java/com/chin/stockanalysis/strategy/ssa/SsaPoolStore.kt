package com.chin.stockanalysis.strategy.ssa

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * ## SSA 混合策略持仓池持久化
 *
 * - 池成员（code/name/加入日/锁定参数）存 SharedPreferences（JSON 字符串）
 * - 最近一次一键更新的池快照（每票建议/绩效/近30日建议历史）存 filesDir JSON
 * - 建议历史验证：确定性算法 + 锁定参数，每次重跑与当日记录一致，快照仅加速展示
 */
object SsaPoolStore {

    /** 池成员：ts/tl/cy/em = 锁定参数（加入时按票性推荐预填），basePct = 底仓%（0~100） */
    data class PoolMember(
        val code: String, val name: String, val addedDate: String,
        val ts: Int, val tl: Int, val cy: Int, val em: Int, val basePct: Int
    ) {
        fun params() = SsaHybridEngine.Params(ts, tl, cy, em, basePct / 100.0)
    }

    private const val PREF = "ssa_hybrid_pool"
    private const val KEY_MEMBERS = "members"
    private const val SNAP_FILE = "ssa_hybrid_pool_snapshot.json"

    // ── 池成员 ────────────────────────────────────────────

    fun load(ctx: Context): MutableList<PoolMember> {
        val s = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_MEMBERS, null) ?: return mutableListOf()
        return mutableListOf<PoolMember>().apply {
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(PoolMember(
                    o.getString("code"), o.getString("name"), o.optString("addedDate", ""),
                    o.optInt("ts", 10), o.optInt("tl", 20), o.optInt("cy", 60),
                    o.optInt("em", 2), o.optInt("basePct", 0)))
            }
        }
    }

    private fun save(ctx: Context, list: List<PoolMember>) {
        val arr = JSONArray()
        list.forEach { m ->
            arr.put(JSONObject().apply {
                put("code", m.code); put("name", m.name); put("addedDate", m.addedDate)
                put("ts", m.ts); put("tl", m.tl); put("cy", m.cy); put("em", m.em); put("basePct", m.basePct)
            })
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_MEMBERS, arr.toString()).apply()
    }

    fun add(ctx: Context, m: PoolMember): Boolean {
        val list = load(ctx)
        if (list.any { it.code == m.code }) return false
        list.add(m); save(ctx, list); return true
    }

    fun remove(ctx: Context, code: String) {
        save(ctx, load(ctx).filterNot { it.code == code })
    }

    fun updateParams(ctx: Context, code: String, p: SsaHybridEngine.Params) {
        val list = load(ctx)
        val idx = list.indexOfFirst { it.code == code }
        if (idx >= 0) {
            list[idx] = list[idx].copy(ts = p.trendShort, tl = p.trendLong, cy = p.cycleL,
                em = p.smoothSpan, basePct = (p.baseFraction * 100).toInt())
            save(ctx, list)
        }
    }

    // ── 快照（最近一次一键更新结果）──────────────────────

    /** 单票快照：hist = 近30日建议（date/advice/close/r1/r3/r5） */
    data class TicketSnapshot(
        val code: String, val name: String, val advice: Int, val date: String, val close: Double,
        val ret: Double, val mdd: Double, val prof: String, val updated: String,
        val hist: List<HistAdvice>
    )

    data class HistAdvice(val date: String, val advice: Int, val close: Double,
                          val r1: Double?, val r3: Double?, val r5: Double?)

    fun saveSnapshot(ctx: Context, snaps: List<TicketSnapshot>) {
        val arr = JSONArray()
        snaps.forEach { s ->
            val hist = JSONArray()
            s.hist.forEach { h ->
                hist.put(JSONObject().apply {
                    put("date", h.date); put("advice", h.advice); put("close", h.close)
                    put("r1", h.r1 ?: JSONObject.NULL); put("r3", h.r3 ?: JSONObject.NULL)
                    put("r5", h.r5 ?: JSONObject.NULL)
                })
            }
            arr.put(JSONObject().apply {
                put("code", s.code); put("name", s.name); put("advice", s.advice)
                put("date", s.date); put("close", s.close); put("ret", s.ret); put("mdd", s.mdd)
                put("prof", s.prof); put("updated", s.updated); put("hist", hist)
            })
        }
        ctx.getFileStreamPath(SNAP_FILE).parentFile?.mkdirs()
        ctx.openFileOutput(SNAP_FILE, Context.MODE_PRIVATE).use { it.write(arr.toString().toByteArray()) }
    }

    fun loadSnapshot(ctx: Context): List<TicketSnapshot> {
        val f = ctx.getFileStreamPath(SNAP_FILE)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val hist = mutableListOf<HistAdvice>()
                val ha = o.optJSONArray("hist")
                if (ha != null) for (j in 0 until ha.length()) {
                    val h = ha.getJSONObject(j)
                    hist.add(HistAdvice(h.getString("date"), h.getInt("advice"), h.getDouble("close"),
                        h.optDouble("r1", Double.NaN).takeUnless { it.isNaN() },
                        h.optDouble("r3", Double.NaN).takeUnless { it.isNaN() },
                        h.optDouble("r5", Double.NaN).takeUnless { it.isNaN() }))
                }
                TicketSnapshot(o.getString("code"), o.getString("name"), o.getInt("advice"),
                    o.getString("date"), o.getDouble("close"), o.getDouble("ret"), o.getDouble("mdd"),
                    o.optString("prof", "MIXED"), o.optString("updated", ""), hist)
            }
        } catch (e: Exception) { emptyList() }
    }
}
