package com.chin.stockanalysis.stock.prefetch

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * ## 用户查询历史（学习用户习惯）
 *
 * 记录用户查询的股票代码、主题关键词、以及活跃时间段，
 * 供 [StockPrefetchScheduler] 决定何时预取哪些数据。
 *
 * ### 持久化策略
 * 使用 SharedPreferences 存储查询频次（轻量，重启后保留）：
 * - `code_{code}` → 查询次数（Int）
 * - `theme_{theme}` → 查询次数（Int）
 * - `hour_{0~23}` → 该小时被使用的次数（Int）
 *
 * ### 使用示例
 * ```kotlin
 * val history = UserQueryHistory.getInstance(context)
 * history.recordCodes(listOf("sh600519", "sh600309"))
 * history.recordTheme("化工")
 * val topCodes = history.getTopCodes(5)   // ["sh600519", "sh600309", ...]
 * val activeHours = history.getActiveHours()  // [9, 10, 14, 20, 21]
 * ```
 */
class UserQueryHistory private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "UserQueryHistory"
        private const val PREFS_NAME = "stock_query_history"
        private const val PREFIX_CODE = "code_"
        private const val PREFIX_THEME = "theme_"
        private const val PREFIX_HOUR = "hour_"
        private const val MAX_CODES = 30    // 最多保留30个高频股票代码
        private const val MAX_THEMES = 15   // 最多保留15个高频主题

        @Volatile
        private var instance: UserQueryHistory? = null

        fun getInstance(context: Context): UserQueryHistory =
            instance ?: synchronized(this) {
                instance ?: UserQueryHistory(context.applicationContext).also { instance = it }
            }
    }

    // ─────────────────────────────────────────────
    // 写入（记录查询）
    // ─────────────────────────────────────────────

    /**
     * 记录一批股票代码被查询
     */
    fun recordCodes(codes: List<String>) {
        if (codes.isEmpty()) return
        val editor = prefs.edit()
        codes.forEach { code ->
            val key = "$PREFIX_CODE$code"
            editor.putInt(key, (prefs.getInt(key, 0) + 1))
        }
        // 同时记录当前小时
        recordCurrentHour(editor)
        editor.apply()
        Log.v(TAG, "recordCodes: $codes")
    }

    /**
     * 记录一个主题/板块关键词被查询
     */
    fun recordTheme(themeName: String) {
        if (themeName.isBlank()) return
        val key = "$PREFIX_THEME${themeName.trim()}"
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
        Log.v(TAG, "recordTheme: $themeName")
    }

    // ─────────────────────────────────────────────
    // 读取（供预取使用）
    // ─────────────────────────────────────────────

    /**
     * 获取查询频次最高的股票代码列表（降序）
     * @param limit 最多返回条数
     */
    fun getTopCodes(limit: Int = 10): List<String> {
        val all = prefs.all
        return all.entries
            .filter { it.key.startsWith(PREFIX_CODE) }
            .sortedByDescending { it.value as? Int ?: 0 }
            .take(limit)
            .map { it.key.removePrefix(PREFIX_CODE) }
            .also { Log.d(TAG, "topCodes(${it.size}): ${it.take(5)}") }
    }

    /**
     * 获取查询频次最高的主题名称列表（降序）
     */
    fun getTopThemes(limit: Int = 5): List<String> {
        val all = prefs.all
        return all.entries
            .filter { it.key.startsWith(PREFIX_THEME) }
            .sortedByDescending { it.value as? Int ?: 0 }
            .take(limit)
            .map { it.key.removePrefix(PREFIX_THEME) }
            .also { Log.d(TAG, "topThemes(${it.size}): $it") }
    }

    /**
     * 获取用户活跃的小时列表（按活跃度降序，0~23）
     * 用于判断什么时间段应该加快预取频率。
     * 只返回出现次数 ≥ 2 的小时（至少出现过两次才算"活跃"）。
     */
    fun getActiveHours(): List<Int> {
        val all = prefs.all
        return all.entries
            .filter { it.key.startsWith(PREFIX_HOUR) }
            .filter { (it.value as? Int ?: 0) >= 2 }
            .sortedByDescending { it.value as? Int ?: 0 }
            .map { it.key.removePrefix(PREFIX_HOUR).toIntOrNull() ?: -1 }
            .filter { it in 0..23 }
    }

    /**
     * 当前小时是否在用户活跃时段？
     * 如果还没有历史数据，默认 9:00~22:00 都算活跃。
     */
    fun isCurrentHourActive(): Boolean {
        val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val activeHours = getActiveHours()
        // 没有历史数据时，用默认时段
        return if (activeHours.isEmpty()) {
            currentHour in 9..22
        } else {
            currentHour in activeHours
        }
    }

    /**
     * 是否有足够的历史数据（至少3个高频代码或1个高频主题）来做预取
     */
    fun hasEnoughHistory(): Boolean {
        return getTopCodes(3).size >= 2 || getTopThemes(1).isNotEmpty()
    }

    /**
     * 清除所有历史（由用户手动触发）
     */
    fun clear() {
        prefs.edit().clear().apply()
        Log.d(TAG, "Query history cleared")
    }

    /**
     * 摘要信息（调试用）
     */
    fun getSummary(): String {
        val codes = getTopCodes(5)
        val themes = getTopThemes(3)
        val hours = getActiveHours().take(4)
        return "TopCodes=${codes}, TopThemes=${themes}, ActiveHours=${hours}"
    }

    // ─────────────────────────────────────────────
    // 私有工具
    // ─────────────────────────────────────────────

    private fun recordCurrentHour(editor: SharedPreferences.Editor) {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val key = "$PREFIX_HOUR$hour"
        editor.putInt(key, prefs.getInt(key, 0) + 1)
    }
}
