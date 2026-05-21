package com.chin.stockanalysis.stock.theme

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * ## 用户偏好记忆管理器（智能过滤）
 *
 * 当用户说"过滤掉市值 200 亿以下的"时，系统自动记住这个偏好，
 * 后续每次查询都自动应用，无需再次说明。
 *
 * ### 支持的偏好类型
 * | 偏好 | 示例触发语 | 持久化 Key |
 * |------|-----------|-----------|
 * | 最小市值 | "过滤200亿以下" | min_market_cap |
 * | 排除交易所 | "剔除科创板" "去掉创业板" | exclude_exchanges |
 * | 排除行业 | "不要银行股" | exclude_sectors |
 * | 最大价格 | "只看50元以下" | max_price |
 * | 最小价格 | "不要垃圾股，5元以上" | min_price |
 * | 排序方式 | "按涨幅排序" | sort_by |
 * | 偏好数量 | "给我前10只" | result_limit |
 *
 * ### 工作原理
 * 1. `ChatActivity` 每次收到用户消息时，先调用 `learnFromMessage()` 学习偏好
 * 2. `buildPreferencePrompt()` 将当前偏好汇总成一段文字，注入 system prompt
 * 3. AI 收到偏好提示后，自动在回答中应用过滤逻辑
 * 4. `StockService` 也可调用 `applyFilters()` 在实时数据层面过滤
 */
class UserPreferenceManager(context: Context) {

    companion object {
        private const val TAG = "UserPrefManager"
        private const val PREFS_NAME = "user_stock_preferences"

        // SharedPreferences keys
        private const val KEY_MIN_MARKET_CAP = "min_market_cap"      // 最小市值（亿元）
        private const val KEY_MAX_MARKET_CAP = "max_market_cap"      // 最大市值（亿元）
        private const val KEY_MIN_PRICE = "min_price"                // 最低股价（元）
        private const val KEY_MAX_PRICE = "max_price"                // 最高股价（元）
        private const val KEY_EXCLUDE_EXCHANGES = "exclude_exchanges" // 排除的交易所，逗号分隔
        private const val KEY_EXCLUDE_SECTORS = "exclude_sectors"    // 排除的板块，逗号分隔
        private const val KEY_SORT_BY = "sort_by"                    // 排序方式
        private const val KEY_RESULT_LIMIT = "result_limit"          // 返回数量上限
        private const val KEY_PREFERENCE_VERSION = "pref_version"    // 版本号，用于检测变化

        @Volatile
        private var instance: UserPreferenceManager? = null

        fun getInstance(context: Context): UserPreferenceManager {
            return instance ?: synchronized(this) {
                instance ?: UserPreferenceManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ════════════════════════════════════════
    // 从用户消息中学习偏好
    // ════════════════════════════════════════

    /**
     * 从用户消息中自动提取并保存偏好
     *
     * 支持的模式：
     * - "过滤掉市值200亿以下的" → minMarketCap = 200
     * - "剔除科创板" / "去掉创业板" → excludeExchanges += "科创板"/"创业板"
     * - "只看50元以下的股票" → maxPrice = 50
     * - "5元以上" → minPrice = 5
     * - "给我前20只" / "前10名" → resultLimit = 20
     *
     * @return true 表示发现了新偏好并已保存
     */
    fun learnFromMessage(message: String): Boolean {
        var learned = false

        // ── 市值过滤 ──────────────────────────────────────────
        // "过滤掉市值200亿以下" / "市值500亿以上" / "200亿以下的不要"
        val capPatterns = listOf(
            Regex("""市值[^\d]*(\d+)[^\d亿]*亿以[下低小]"""),
            Regex("""(\d+)亿以[下低小][^\d]*市值"""),
            Regex("""过滤[^\d]*(\d+)[^\d亿]*亿"""),
            Regex("""去掉[^\d]*(\d+)[^\d亿]*亿以[下低小]"""),
            Regex("""剔除[^\d]*(\d+)[^\d亿]*亿以[下低小]"""),
        )
        for (pattern in capPatterns) {
            val match = pattern.find(message)
            if (match != null) {
                val cap = match.groupValues[1].toLongOrNull() ?: continue
                setMinMarketCap(cap)
                Log.i(TAG, "📌 学习到偏好: 最小市值 ${cap}亿")
                learned = true
                break
            }
        }

        // "市值1000亿以上" / "大盘股500亿以上"
        val capAbovePattern = Regex("""市值[^\d]*(\d+)[^\d亿]*亿以[上高大]""")
        capAbovePattern.find(message)?.let {
            val cap = it.groupValues[1].toLongOrNull()
            if (cap != null) {
                setMinMarketCap(cap)
                Log.i(TAG, "📌 学习到偏好: 最小市值 ${cap}亿")
                learned = true
            }
        }

        // ── 交易所/板块过滤 ──────────────────────────────────────
        val exchangeKeywords = mapOf(
            "科创板" to "科创板",
            "创业板" to "创业板",
            "北交所" to "北交所",
            "688" to "科创板",
            "300" to "创业板",
        )
        val excludeTriggers = listOf("剔除", "去掉", "过滤", "不要", "排除", "忽略")

        for (trigger in excludeTriggers) {
            if (message.contains(trigger)) {
                for ((keyword, exchange) in exchangeKeywords) {
                    if (message.contains(keyword)) {
                        addExcludeExchange(exchange)
                        Log.i(TAG, "📌 学习到偏好: 排除 $exchange")
                        learned = true
                    }
                }
            }
        }

        // ── 价格过滤 ──────────────────────────────────────────
        // "只看50元以下" / "价格50以下"
        val maxPricePattern = Regex("""[只仅].*?(\d+(?:\.\d+)?).*?元以[下低小]|价格.*?(\d+(?:\.\d+)?).*?以[下低小]""")
        maxPricePattern.find(message)?.let {
            val price = (it.groupValues[1].ifBlank { it.groupValues[2] }).toDoubleOrNull()
            if (price != null && price > 0) {
                setMaxPrice(price)
                Log.i(TAG, "📌 学习到偏好: 最高价格 ${price}元")
                learned = true
            }
        }

        // "5元以上" / "不要低价股3元以下"
        val minPricePattern = Regex("""(\d+(?:\.\d+)?).*?元以[上高大]|价格.*?(\d+(?:\.\d+)?).*?以[上高大]""")
        minPricePattern.find(message)?.let {
            val price = (it.groupValues[1].ifBlank { it.groupValues[2] }).toDoubleOrNull()
            if (price != null && price > 0) {
                setMinPrice(price)
                Log.i(TAG, "📌 学习到偏好: 最低价格 ${price}元")
                learned = true
            }
        }

        // ── 数量限制 ──────────────────────────────────────────
        // "给我前20只" / "前10名" / "最多15只"
        val limitPattern = Regex("""前(\d+)(?:只|名|个|支|条)|最多(\d+)(?:只|名|个|支|条)|给.*?(\d+)只""")
        limitPattern.find(message)?.let {
            val limit = (it.groupValues[1].ifBlank { it.groupValues[2].ifBlank { it.groupValues[3] } }).toIntOrNull()
            if (limit != null && limit in 1..50) {
                setResultLimit(limit)
                Log.i(TAG, "📌 学习到偏好: 返回数量 $limit")
                learned = true
            }
        }

        // ── 排序方式 ──────────────────────────────────────────
        when {
            message.contains("按涨幅") || message.contains("涨幅排") -> { setSortBy("change_desc"); learned = true }
            message.contains("按跌幅") || message.contains("跌幅排") -> { setSortBy("change_asc"); learned = true }
            message.contains("按市值") || message.contains("市值排") -> { setSortBy("cap_desc"); learned = true }
            message.contains("按成交量") || message.contains("量排") -> { setSortBy("volume_desc"); learned = true }
        }

        if (learned) {
            // 版本号自增，让调用方知道偏好有变化
            prefs.edit().putInt(KEY_PREFERENCE_VERSION, getPreferenceVersion() + 1).apply()
        }

        return learned
    }

    // ════════════════════════════════════════
    // 生成注入 AI prompt 的偏好描述
    // ════════════════════════════════════════

    /**
     * 将当前偏好转换为 AI 可理解的指令文本，注入 system prompt。
     *
     * 示例输出：
     * ```
     * 【用户长期偏好设置 - 每次回答都必须遵守】
     * • 过滤条件: 市值 200 亿以下的股票不纳入分析
     * • 排除板块: 科创板、创业板
     * • 返回数量: 最多展示 10 只
     * ```
     */
    fun buildPreferencePrompt(): String {
        val sb = StringBuilder()
        val hasPrefs = hasAnyPreference()
        if (!hasPrefs) return ""

        sb.appendLine("\n【用户长期偏好设置 - 每次回答都必须遵守】")

        val minCap = getMinMarketCap()
        val maxCap = getMaxMarketCap()
        if (minCap > 0 || maxCap > 0) {
            val capStr = buildString {
                if (minCap > 0) append("市值 ${minCap} 亿以下的股票不纳入分析")
                if (maxCap > 0) {
                    if (minCap > 0) append("，")
                    append("市值 ${maxCap} 亿以上的股票不纳入")
                }
            }
            sb.appendLine("• 市值过滤: $capStr")
        }

        val excluded = getExcludeExchanges()
        if (excluded.isNotEmpty()) {
            sb.appendLine("• 排除板块/交易所: ${excluded.joinToString("、")}")
        }

        val minPrice = getMinPrice()
        val maxPrice = getMaxPrice()
        if (minPrice > 0 || maxPrice > 0) {
            val priceStr = buildString {
                if (minPrice > 0) append("股价 ${minPrice} 元以下不考虑")
                if (maxPrice > 0) {
                    if (minPrice > 0) append("，")
                    append("股价 ${maxPrice} 元以上不考虑")
                }
            }
            sb.appendLine("• 价格过滤: $priceStr")
        }

        val limit = getResultLimit()
        if (limit > 0) {
            sb.appendLine("• 返回数量: 最多展示 $limit 只")
        }

        val sort = getSortBy()
        if (sort.isNotBlank()) {
            val sortDesc = when (sort) {
                "change_desc" -> "按涨幅从高到低排序"
                "change_asc" -> "按跌幅从大到小排序"
                "cap_desc" -> "按市值从大到小排序"
                "volume_desc" -> "按成交量从大到小排序"
                else -> sort
            }
            sb.appendLine("• 排序方式: $sortDesc")
        }

        return sb.toString()
    }

    /**
     * 判断当前是否有需要过滤的代码列表（根据交易所偏好）
     *
     * @param codes 待过滤的股票代码列表
     * @return 过滤后的列表
     */
    fun applyCodeFilters(codes: List<String>): List<String> {
        val excluded = getExcludeExchanges()
        if (excluded.isEmpty()) return codes

        return codes.filter { code ->
            val raw = code.takeLast(6)
            val isKCB = raw.startsWith("688") || raw.startsWith("689")  // 科创板
            val isCYB = raw.startsWith("300") || raw.startsWith("301")  // 创业板
            val isBJ = raw.startsWith("8") || raw.startsWith("4")       // 北交所

            when {
                excluded.contains("科创板") && isKCB -> false
                excluded.contains("创业板") && isCYB -> false
                excluded.contains("北交所") && isBJ -> false
                else -> true
            }
        }
    }

    // ════════════════════════════════════════
    // 偏好摘要（用于展示给用户）
    // ════════════════════════════════════════

    fun getPreferenceSummary(): String {
        if (!hasAnyPreference()) return "（暂无已记忆的偏好）"
        return buildPreferencePrompt().trim()
    }

    fun clearAllPreferences() {
        prefs.edit().clear().apply()
        Log.i(TAG, "🗑️ 已清除所有用户偏好")
    }

    fun getPreferenceVersion() = prefs.getInt(KEY_PREFERENCE_VERSION, 0)

    // ════════════════════════════════════════
    // Getter / Setter
    // ════════════════════════════════════════

    fun getMinMarketCap(): Long = prefs.getLong(KEY_MIN_MARKET_CAP, 0L)
    fun setMinMarketCap(cap: Long) = prefs.edit().putLong(KEY_MIN_MARKET_CAP, cap).apply()

    fun getMaxMarketCap(): Long = prefs.getLong(KEY_MAX_MARKET_CAP, 0L)
    fun setMaxMarketCap(cap: Long) = prefs.edit().putLong(KEY_MAX_MARKET_CAP, cap).apply()

    fun getMinPrice(): Double = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_MIN_PRICE, 0L))
    fun setMinPrice(price: Double) = prefs.edit().putLong(KEY_MIN_PRICE, java.lang.Double.doubleToLongBits(price)).apply()

    fun getMaxPrice(): Double = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_MAX_PRICE, 0L))
    fun setMaxPrice(price: Double) = prefs.edit().putLong(KEY_MAX_PRICE, java.lang.Double.doubleToLongBits(price)).apply()

    fun getExcludeExchanges(): List<String> {
        val raw = prefs.getString(KEY_EXCLUDE_EXCHANGES, "") ?: ""
        return if (raw.isBlank()) emptyList() else raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }

    fun addExcludeExchange(exchange: String) {
        val current = getExcludeExchanges().toMutableList()
        if (exchange !in current) {
            current.add(exchange)
            prefs.edit().putString(KEY_EXCLUDE_EXCHANGES, current.joinToString(",")).apply()
        }
    }

    fun removeExcludeExchange(exchange: String) {
        val current = getExcludeExchanges().toMutableList()
        current.remove(exchange)
        prefs.edit().putString(KEY_EXCLUDE_EXCHANGES, current.joinToString(",")).apply()
    }

    fun getSortBy(): String = prefs.getString(KEY_SORT_BY, "") ?: ""
    fun setSortBy(sortBy: String) = prefs.edit().putString(KEY_SORT_BY, sortBy).apply()

    fun getResultLimit(): Int = prefs.getInt(KEY_RESULT_LIMIT, 0)
    fun setResultLimit(limit: Int) = prefs.edit().putInt(KEY_RESULT_LIMIT, limit).apply()

    private fun hasAnyPreference(): Boolean {
        return getMinMarketCap() > 0 ||
                getMaxMarketCap() > 0 ||
                getMinPrice() > 0 ||
                getMaxPrice() > 0 ||
                getExcludeExchanges().isNotEmpty() ||
                getSortBy().isNotBlank() ||
                getResultLimit() > 0
    }
}
