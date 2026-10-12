package com.chin.stockanalysis.strategy.indicatorbt

import android.content.Context
import org.json.JSONArray

/**
 * ## 已保存的扫描筛选股票池（K线趋势扫描页 → 回测页批量回测）
 *
 * 用 SharedPreferences 持久化代码+名称列表，两个页面共享同一份数据。
 */
object SavedScanPool {

    data class Item(val code: String, val name: String)

    private const val PREFS = "saved_scan_pool"
    private const val KEY_JSON = "items_json"
    private const val KEY_TIME = "saved_at"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 保存筛选结果（覆盖旧池），返回保存数量 */
    fun save(ctx: Context, items: List<Item>): Int {
        val arr = JSONArray()
        items.forEach { arr.put(JSONArray().put(it.code).put(it.name)) }
        prefs(ctx).edit()
            .putString(KEY_JSON, arr.toString())
            .putLong(KEY_TIME, System.currentTimeMillis())
            .apply()
        return items.size
    }

    fun load(ctx: Context): List<Item> {
        val raw = prefs(ctx).getString(KEY_JSON, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val a = arr.getJSONArray(it)
                Item(a.getString(0), a.optString(1, ""))
            }
        }.getOrDefault(emptyList())
    }

    fun count(ctx: Context): Int = load(ctx).size

    fun savedAt(ctx: Context): Long = prefs(ctx).getLong(KEY_TIME, 0L)
}
