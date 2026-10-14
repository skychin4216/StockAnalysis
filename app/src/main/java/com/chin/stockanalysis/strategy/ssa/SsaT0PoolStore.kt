package com.chin.stockanalysis.strategy.ssa

import android.content.Context
import com.chin.stockanalysis.strategy.ssa.SsaT0Engine.LayerCfg
import com.chin.stockanalysis.strategy.ssa.SsaT0Engine.Params
import org.json.JSONArray
import org.json.JSONObject

/**
 * ## 三层 SSA 稳态做T 持仓池持久化
 *
 * 与混合策略页的 [SsaPoolStore] 完全隔离（pref/快照文件名不同，互不覆盖）。
 * - 池成员（code/name/加入日/锁定参数）存 SharedPreferences（JSON 字符串）
 * - 最近一次一键更新的池快照（每票状态/建议/绩效）存 filesDir JSON
 *
 * 锁定参数口径 = 加入时 UI「三层窗口 + 仓位/成本 + 两个保留选项（cyRule/winHalf）」，
 * 与 [SsaT0Engine.Params] 的构造顺序一致，保证"锁定参数 = 加入时所见"。
 */
object SsaT0PoolStore {

    /** 池成员：3 层窗口 + corePct/tPct/slippage + 两个保留选项（cycle 拐点 / WINDOW 半仓） */
    data class PoolMember(
        val code: String, val name: String, val addedDate: String,
        val wl: Int, val wm: Int, val wn: Int,
        val dl: Int, val dm: Int, val dn: Int,
        val hl: Int, val hm: Int, val hn: Int,
        val cp: Double, val tp: Double, val slip: Double,
        val cyRule: Boolean, val winHalf: Boolean
    ) {
        fun params(): Params {
            val base = Params(LayerCfg(wl, wm, wn), LayerCfg(dl, dm, dn), LayerCfg(hl, hm, hn),
                cp, tp, slippageRate = slip)
            val withWin = if (winHalf) base.copy(winPosPct = 50.0) else base
            return if (cyRule) withWin.copy(useCycleRule = true, cyLayer = "D", cyConfirm = 2) else withWin
        }
    }

    /** 由 [SsaT0Engine.Params]（UI 当前值）反构池成员 */
    fun of(code: String, name: String, addedDate: String, p: Params): PoolMember = PoolMember(
        code, name, addedDate,
        p.week.embedL, p.week.windowM, p.week.lookbackN,
        p.day.embedL, p.day.windowM, p.day.lookbackN,
        p.hour.embedL, p.hour.windowM, p.hour.lookbackN,
        p.corePct, p.tPct, p.slippageRate,
        p.useCycleRule, p.winPosPct < 100.0
    )

    private const val PREF = "ssa_t0_pool"
    private const val KEY_MEMBERS = "members"
    private const val SNAP_FILE = "ssa_t0_pool_snapshot.json"

    // ── 池成员 ────────────────────────────────────────────

    fun load(ctx: Context): MutableList<PoolMember> {
        val s = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_MEMBERS, null)
            ?: return mutableListOf()
        return mutableListOf<PoolMember>().apply {
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(PoolMember(
                    o.getString("code"), o.getString("name"), o.optString("addedDate", ""),
                    o.optInt("wl", 20), o.optInt("wm", 80), o.optInt("wn", 12),
                    o.optInt("dl", 30), o.optInt("dm", 120), o.optInt("dn", 20),
                    o.optInt("hl", 30), o.optInt("hm", 120), o.optInt("hn", 20),
                    o.optDouble("cp", 80.0), o.optDouble("tp", 30.0), o.optDouble("slip", 0.0005),
                    o.optBoolean("cyRule", false), o.optBoolean("winHalf", false)))
            }
        }
    }

    private fun save(ctx: Context, list: List<PoolMember>) {
        val arr = JSONArray()
        list.forEach { m ->
            arr.put(JSONObject().apply {
                put("code", m.code); put("name", m.name); put("addedDate", m.addedDate)
                put("wl", m.wl); put("wm", m.wm); put("wn", m.wn)
                put("dl", m.dl); put("dm", m.dm); put("dn", m.dn)
                put("hl", m.hl); put("hm", m.hm); put("hn", m.hn)
                put("cp", m.cp); put("tp", m.tp); put("slip", m.slip)
                put("cyRule", m.cyRule); put("winHalf", m.winHalf)
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
        saveSnapshot(ctx, loadSnapshot(ctx).filterNot { it.code == code })
    }

    fun updateParams(ctx: Context, code: String, p: Params) {
        val list = load(ctx)
        val idx = list.indexOfFirst { it.code == code }
        if (idx >= 0) {
            list[idx] = of(list[idx].code, list[idx].name, list[idx].addedDate, p)
            save(ctx, list)
        }
    }

    // ── 快照（最近一次一键更新结果）──────────────────────

    /** 单票快照：截至末根1H + 当前状态/建议/持仓% + 回测绩效 */
    data class T0Snapshot(
        val code: String, val name: String, val updated: String, val barLabel: String,
        val close: Double, val state: Int, val action: Int, val reason: String, val holdPct: Double,
        val ret: Double, val mdd: Double, val winRate: Double, val trades: Int
    )

    fun saveSnapshot(ctx: Context, snaps: List<T0Snapshot>) {
        val arr = JSONArray()
        snaps.forEach { s ->
            arr.put(JSONObject().apply {
                put("code", s.code); put("name", s.name); put("updated", s.updated)
                put("barLabel", s.barLabel); put("close", s.close)
                put("state", s.state); put("action", s.action); put("reason", s.reason)
                put("holdPct", s.holdPct); put("ret", s.ret); put("mdd", s.mdd)
                put("winRate", s.winRate); put("trades", s.trades)
            })
        }
        ctx.getFileStreamPath(SNAP_FILE).parentFile?.mkdirs()
        ctx.openFileOutput(SNAP_FILE, Context.MODE_PRIVATE).use { it.write(arr.toString().toByteArray()) }
    }

    fun loadSnapshot(ctx: Context): List<T0Snapshot> {
        val f = ctx.getFileStreamPath(SNAP_FILE)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                T0Snapshot(
                    o.getString("code"), o.optString("name", ""), o.optString("updated", ""),
                    o.optString("barLabel", ""), o.optDouble("close", 0.0),
                    o.optInt("state", 0), o.optInt("action", 3), o.optString("reason", ""),
                    o.optDouble("holdPct", 0.0), o.optDouble("ret", 0.0), o.optDouble("mdd", 0.0),
                    o.optDouble("winRate", 0.0), o.optInt("trades", 0))
            }
        } catch (e: Exception) { emptyList() }
    }
}
