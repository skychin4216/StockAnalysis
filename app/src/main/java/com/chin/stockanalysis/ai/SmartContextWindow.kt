package com.chin.stockanalysis.ai

import android.util.Log
import com.chin.stockanalysis.stock.StockQueryEngine

/**
 * ## 智能上下文窗口（参考豆包AI 分层缓存）
 *
 * 缓存 `buildSystemPrompt()` 结果，避免每次对话重复查询 DB/新闻/行情数据。
 *
 * ### 分层策略
 * - **L1 内存 LRU**: 最近 5 分钟使用的行情数据，容量 20 条
 * - **L2 DB 回退**: L1 miss 时从 Room DB 查询（StockQueryEngine 已有）
 *
 * ### TTL 策略
 * - 交易时段 (9:25-11:30, 13:00-15:00): 30s TTL（快速失效，保持实时）
 * - 非交易时段: 5min TTL
 *
 * ### 缓存 Key
 * 基于 userText 的简化 hash + 当前交易日，确保同一问题 + 同一天不重复查询。
 */
class SmartContextWindow(private val queryEngine: StockQueryEngine) {

    companion object {
        private const val TAG = "SmartContext"
        private const val MAX_CACHE_SIZE = 20
        private const val TRADING_TTL_MS = 30_000L      // 交易时段 30s
        private const val IDLE_TTL_MS = 300_000L         // 非交易时段 5min
    }

    data class CachedContext(
        val dataPrompt: String,
        val timestamp: Long,
        val userTextHash: Int
    )

    // 简单 LRU：最近使用的在末尾
    private val cache = object : LinkedHashMap<String, CachedContext>(MAX_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedContext>?): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }

    /**
     * 获取或构建 system prompt 数据部分（含行情/新闻/记忆）。
     *
     * @param userText 用户输入
     * @param baseSystemPrompt 基础 system prompt 模板
     * @param onPreferenceLeaned 偏好学习回调
     * @return 拼接了行情数据的完整 system prompt 字符串
     */
    suspend fun getOrBuild(
        userText: String,
        baseSystemPrompt: String,
        onPreferenceLeaned: (String) -> Unit
    ): String {
        val key = buildCacheKey(userText)
        val now = System.currentTimeMillis()
        val ttl = getCurrentTTL()

        // 检查缓存命中且未过期
        val cached = cache[key]
        if (cached != null && (now - cached.timestamp) < ttl) {
            Log.d(TAG, "✅ 缓存命中 (age=${(now - cached.timestamp) / 1000}s)")
            return cached.dataPrompt
        }

        // 缓存 miss → 实际查询
        Log.d(TAG, "🔄 缓存 miss，重新构建 system prompt")
        val dataPrompt = queryEngine.buildSystemPrompt(
            userText = userText,
            baseSystemPrompt = baseSystemPrompt,
            onPreferenceLeaned = onPreferenceLeaned
        )

        // 存入缓存
        cache[key] = CachedContext(
            dataPrompt = dataPrompt,
            timestamp = now,
            userTextHash = userText.hashCode()
        )

        return dataPrompt
    }

    /**
     * 智能失效：用户输入明显变化时清除相关缓存。
     * 例如从 "600519" 变为 "000858"，缓存失效。
     */
    fun invalidateIfNeeded(newUserText: String) {
        val newHash = newUserText.hashCode()
        val entriesToRemove = cache.entries.filter {
            it.value.userTextHash != newHash
        }
        if (entriesToRemove.isNotEmpty()) {
            entriesToRemove.forEach { cache.remove(it.key) }
            Log.d(TAG, "🧹 失效 ${entriesToRemove.size} 条过期缓存")
        }
    }

    /** 清空全部缓存 */
    fun invalidateAll() {
        cache.clear()
        Log.d(TAG, "🧹 清空全部上下文缓存")
    }

    /** 当前缓存条目数 */
    val size: Int get() = cache.size

    // ── Private ──

    private fun buildCacheKey(userText: String): String {
        // 简化：取用户输入前 30 字符作为 key
        val simplified = userText.trim().take(30).replace(Regex("\\s+"), "")
        // 加上当天日期，确保跨天自动失效
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.CHINA).format(java.util.Date())
        return "$today:$simplified"
    }

    /** 根据当前时间判断 TTL */
    private fun getCurrentTTL(): Long {
        val cal = java.util.Calendar.getInstance()
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = cal.get(java.util.Calendar.MINUTE)
        val time = hour * 100 + minute

        // A 股交易时段：9:25-11:30, 13:00-15:00
        return if ((time in 925..1130) || (time in 1300..1500)) {
            TRADING_TTL_MS   // 30s
        } else {
            IDLE_TTL_MS      // 5min
        }
    }
}