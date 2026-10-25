package com.chin.stockanalysis.strategy.ssa

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ## 短线预警 · 悬浮窗状态与结果缓存
 *
 * - 开关（「退出 App 后显示浮窗」）存 SharedPreferences；
 * - 最近一次批量重算结果（每只持仓股的评分 / 建议 / 现价）存 filesDir JSON（原子写）。
 *
 * pref 名与文件名均与 [SsaT0PoolStore]（`ssa_t0_pool` / `ssa_t0_pool_snapshot.json`）隔离，互不覆盖。
 */
object SsaAlertStore {

    private const val PREF = "ssa_alert_overlay"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_REFRESH = "last_refresh_at"
    private const val KEY_ALERT_STRATEGIES = "alert_strategies"
    private const val SNAP_FILE = "ssa_alert_snapshot.json"

    // ── 预警页策略勾选项 id（持久化用；与 UI 复选框一一对应）──
    /** 现有多指标策略（默认勾选） */
    const val STRAT_BASE = "BASE"
    /** BB 布林极限拐点策略（全 16 平滑口径对比） */
    const val STRAT_BBPIVOT = "BBPIVOT"
    /** 融合①纯 BBI12 拐点 */
    const val STRAT_MIX_PURE_BBI12 = "PURE_BBI12"
    /** 融合②BBI12 底 → (BB6∪BBI12) 顶 */
    const val STRAT_MIX_BBI12_BB6_OR = "MIX_OR"
    /** 融合③BBI12 底 → BB6 顶 · BB 窗 */
    const val STRAT_MIX_BBI12_BB6_BBWIN = "MIX_BBWIN"

    /** BBI12 拐点 + 移动止盈8% + Δ5ER>-0.05 过滤（稳健，2026-10-20 回测定型） */
    const val STRAT_ERBBI_STEADY = "ERBBI_STEADY"
    /** BBI12 拐点 + 移动止盈8%（进取，无过滤） */
    const val STRAT_ERBBI_AGGRO = "ERBBI_AGGRO"

    /** 区间做T：收盘 ≤ 前14根最低收盘 且 ER10≤0.45 买入；收盘 ≥ 前14根最高收盘 且 ER10≤0.45 卖出 */
    const val STRAT_RANGE_T = "RANGE_T"

    // ── 开关 ────────────────────────────────────────────

    fun isOverlayEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setOverlayEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /** 预警页已勾选的策略集合（2026-10-20 默认改为纯 BBI12；BASE/BBPIVOT/融合②③ 已从 UI 移除） */
    fun getAlertStrategies(ctx: Context): Set<String> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_ALERT_STRATEGIES, null) ?: return setOf(STRAT_MIX_PURE_BBI12)
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .toSet().let { saved ->
                val valid = setOf(STRAT_MIX_PURE_BBI12, STRAT_ERBBI_STEADY, STRAT_ERBBI_AGGRO, STRAT_RANGE_T)
                saved.intersect(valid).ifEmpty { setOf(STRAT_MIX_PURE_BBI12) }
            }
    }

    fun setAlertStrategies(ctx: Context, on: Set<String>) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_ALERT_STRATEGIES, on.joinToString(",")).apply()
    }

    /** 最近一次联网重算的时间戳（ms） */
    fun lastRefreshAt(ctx: Context): Long =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong(KEY_LAST_REFRESH, 0L)

    fun setLastRefreshAt(ctx: Context, at: Long) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putLong(KEY_LAST_REFRESH, at).apply()
    }

    // ── 浮窗行数据 ──────────────────────────────────────

    /** 单只持仓股的浮窗展示行 */
    data class OverlayRow(
        val code: String,
        val name: String,
        /** 当前价（实时行情优先，缺失回落末根 1H 收盘） */
        val price: Double,
        /** 当日涨跌幅（%），无行情时为 0 */
        val changePct: Double,
        /** 末根 1H 买入评分 0/20/…/100 */
        val score: Int,
        /** 末根 BBI 买入窗是否开启 */
        val windowOpen: Boolean,
        /** 买入 / 关注 / 卖出 / 持仓 / 观望 / 数据缺失 */
        val advice: String,
        /** 触发该建议的主因文案 */
        val reason: String,
        /** "MM-dd HH:mm" */
        val updated: String,
        /** 跨票横截面 ER 分位 0~100（PC 推送 er_pct_map；缺失=null → UI 显示「—」） */
        val erPct: Double? = null
    )

    /** 原子写：先写 .tmp 再 rename，避免读到半截 JSON */
    fun saveRows(ctx: Context, rows: List<OverlayRow>) {
        val arr = JSONArray()
        rows.forEach { r ->
            arr.put(JSONObject().apply {
                put("code", r.code); put("name", r.name)
                put("price", r.price); put("changePct", r.changePct)
                put("score", r.score); put("windowOpen", r.windowOpen)
                put("advice", r.advice); put("reason", r.reason); put("updated", r.updated)
                r.erPct?.let { put("erPct", it) }
            })
        }
        runCatching {
            val dir = ctx.filesDir
            val tmp = File(dir, "$SNAP_FILE.tmp")
            val dst = File(dir, SNAP_FILE)
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(dst)) {
                dst.writeText(arr.toString())
                tmp.delete()
            }
        }
    }

    fun loadRows(ctx: Context): List<OverlayRow> {
        val f = File(ctx.filesDir, SNAP_FILE)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                OverlayRow(
                    o.getString("code"), o.optString("name", ""),
                    o.optDouble("price", 0.0), o.optDouble("changePct", 0.0),
                    o.optInt("score", 0), o.optBoolean("windowOpen", false),
                    o.optString("advice", "—"), o.optString("reason", ""), o.optString("updated", ""),
                    if (o.has("erPct")) o.getDouble("erPct") else null
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ── 推荐历史台账（2026-10-20 新增；JSONL 追加，上限 500 条滚动） ──

    private const val LEDGER_FILE = "ssa_alert_ledger.jsonl"
    private const val LEDGER_MAX = 500

    /** 一条推荐记录：每次批量重算产出可执行建议（买入/卖出/止盈/止损/峰顶）时落库 */
    data class Rec(
        val ts: Long,           // 落库时刻 ms
        val code: String,
        val name: String,
        val channel: String,    // ENTRY_BBI（入场）/ HOLD_T0（做T，预留）
        val action: String,     // 买入 / 卖出 / 止盈卖出 / 止损卖出 / 峰顶卖出
        val price: Double,
        val erPct: Double?,     // 横截面分位（缺失=null）
        val reason: String,
        val updated: String     // "MM-dd HH:mm"（信号 bar 时间）
    )

    /** 追加推荐记录（超上限滚动截断；原子写） */
    fun appendRecs(ctx: Context, recs: List<Rec>) {
        if (recs.isEmpty()) return
        runCatching {
            val old = loadRecs(ctx)
            val merged = (old + recs).takeLast(LEDGER_MAX)
            val sb = StringBuilder()
            merged.forEach { r ->
                sb.append(JSONObject().apply {
                    put("ts", r.ts); put("code", r.code); put("name", r.name)
                    put("channel", r.channel); put("action", r.action); put("price", r.price)
                    r.erPct?.let { put("erPct", it) }
                    put("reason", r.reason); put("updated", r.updated)
                }.toString()).append('\n')
            }
            val dir = ctx.filesDir
            val tmp = File(dir, "$LEDGER_FILE.tmp")
            val dst = File(dir, LEDGER_FILE)
            tmp.writeText(sb.toString())
            if (!tmp.renameTo(dst)) { dst.writeText(sb.toString()); tmp.delete() }
        }
    }

    /** 读取全部推荐记录（升序，老的在前） */
    fun loadRecs(ctx: Context): List<Rec> {
        val f = File(ctx.filesDir, LEDGER_FILE)
        if (!f.exists()) return emptyList()
        return runCatching {
            f.readLines().filter { it.isNotBlank() }.map { line ->
                val o = JSONObject(line)
                Rec(
                    o.getLong("ts"), o.getString("code"), o.optString("name", ""),
                    o.optString("channel", "ENTRY_BBI"), o.getString("action"),
                    o.optDouble("price", 0.0),
                    if (o.has("erPct")) o.getDouble("erPct") else null,
                    o.optString("reason", ""), o.optString("updated", "")
                )
            }
        }.getOrDefault(emptyList())
    }

    // ── 荐股池扫描结果（2026-10-20 方案A；每日一次，入场推荐 Tab 数据源）──

    private const val SCAN_FILE = "ssa_pool_scan.json"

    /** 扫描结果口径版本：旧版（多指标/含卖出）落盘文件读出后直接作废，等当日重扫 */
    private const val SCAN_VERSION = 2

    /** 一次全池扫描的落盘结果：date 用于每日自动触发判定 */
    data class ScanResult(
        val date: String, val updated: String, val scannedN: Int, val rows: List<OverlayRow>
    )

    fun saveScan(ctx: Context, date: String, updated: String, scannedN: Int, rows: List<OverlayRow>) {
        runCatching {
            val arr = JSONArray()
            rows.forEach { r ->
                arr.put(JSONObject().apply {
                    put("code", r.code); put("name", r.name); put("price", r.price)
                    put("changePct", r.changePct); put("score", r.score); put("windowOpen", r.windowOpen)
                    put("advice", r.advice); put("reason", r.reason); put("updated", r.updated)
                    r.erPct?.let { put("erPct", it) }
                })
            }
            val o = JSONObject().apply {
                put("v", SCAN_VERSION)
                put("date", date); put("updated", updated); put("scannedN", scannedN); put("rows", arr)
            }
            ctx.openFileOutput(SCAN_FILE, Context.MODE_PRIVATE).use { it.write(o.toString().toByteArray()) }
        }
    }

    fun loadScan(ctx: Context): ScanResult? {
        val f = ctx.getFileStreamPath(SCAN_FILE)
        if (!f.exists()) return null
        return runCatching {
            val o = JSONObject(f.readText())
            // 旧口径文件（无 v 或 v<SCAN_VERSION）作废 → 当日自动重扫，避免展示旧的卖出/多指标结果
            if (o.optInt("v", 0) < SCAN_VERSION) return null
            val arr = o.getJSONArray("rows")
            val rows = (0 until arr.length()).map { i ->
                val r = arr.getJSONObject(i)
                OverlayRow(
                    r.getString("code"), r.optString("name", ""),
                    r.optDouble("price", 0.0), r.optDouble("changePct", 0.0),
                    r.optInt("score", 0), r.optBoolean("windowOpen", false),
                    r.optString("advice", "—"), r.optString("reason", ""), r.optString("updated", ""),
                    if (r.has("erPct")) r.getDouble("erPct") else null
                )
            }
                // 只保留 BBI12 谷底买入拐点（防御：历史文件/异常数据混入的卖出票不展示）
                .filter {
                    (it.advice == "买入" || it.advice.startsWith("关注(ER<70)")) &&
                        it.reason.contains("BBI12 谷底拐点")
                }
            ScanResult(o.getString("date"), o.optString("updated", ""), o.optInt("scannedN", 0), rows)
        }.getOrNull()
    }
}
