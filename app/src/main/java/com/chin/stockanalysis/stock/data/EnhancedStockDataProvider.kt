// 创建文件: app/src/main/java/com/chin/stockanalysis/stock/data/EnhancedStockDataProvider.kt

package com.chin.stockanalysis.stock.data

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.data.sources.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit

// ============================================================================
// 功能 A: 智能缓存系统
// ============================================================================

/**
 * 智能缓存 - 根据A股交易时段自动调整TTL
 */
class SmartStockCache {
    private data class CacheEntry(val data: StockRealtime, val timestamp: Long, val ttlMs: Long)

    private val cache = LinkedHashMap<String, CacheEntry>(200)
    private val maxSize = 200
    private val tag = "SmartStockCache"

    fun get(codes: List<String>): Map<String, StockRealtime> {
        val now = System.currentTimeMillis()
        val result = mutableMapOf<String, StockRealtime>()
        synchronized(cache) {
            for (code in codes) {
                val entry = cache[code]
                if (entry != null && (now - entry.timestamp) < entry.ttlMs) {
                    result[code] = entry.data
                }
            }
        }
        return result
    }

    fun put(data: Map<String, StockRealtime>) {
        if (data.isEmpty()) return
        val now = System.currentTimeMillis()
        val ttl = calculateSmartTTL()
        synchronized(cache) {
            for ((code, realtime) in data) {
                cache[code] = CacheEntry(realtime, now, ttl)
                if (cache.size > maxSize) {
                    cache.remove(cache.keys.firstOrNull())
                }
            }
        }
        Log.d(tag, "Cached ${data.size} with TTL=${ttl / 1000}s")
    }

    private fun calculateSmartTTL(): Long {
        val now = LocalTime.now()
        val isWorkday = LocalDate.now().dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

        return when {
            isWorkday && now >= LocalTime.of(9, 30) && now <= LocalTime.of(15, 0) -> 1000
            isWorkday && now > LocalTime.of(15, 0) && now < LocalTime.of(22, 0) -> 5 * 60 * 1000
            isWorkday && (now >= LocalTime.of(22, 0) || now < LocalTime.of(9, 30)) -> 30 * 60 * 1000
            !isWorkday -> 60 * 60 * 1000
            else -> 5 * 60 * 1000
        }
    }

    fun clear() {
        synchronized(cache) { cache.clear() }
        Log.d(tag, "Cache cleared")
    }

    fun getStats(): String {
        synchronized(cache) {
            return "Cache: ${cache.size}/$maxSize | TTL: ${calculateSmartTTL() / 1000}s"
        }
    }
}

// ============================================================================
// 功能 B: 并发多源仓储
// ============================================================================

/**
 * 多源股票数据仓储 - 并发请求、自动选源、健康检查
 */
class MultiSourceStockRepository(
    private val sources: List<StockDataSource>,
    private val cache: SmartStockCache = SmartStockCache()
) {
    private val tag = "MultiSourceRepository"
    private val sourceHealth = mutableMapOf<StockDataSource, Boolean>()
    private val requestTimeouts = mutableMapOf<StockDataSource, Long>()

    init {
        for (source in sources) {
            sourceHealth[source] = true
            requestTimeouts[source] = 0L
        }
        Log.d(tag, "Init with ${sources.size} sources")
    }

    suspend fun getRealtime(codes: List<String>): Map<String, StockRealtime> =
        withContext(Dispatchers.IO) {
            if (codes.isEmpty()) return@withContext emptyMap()

            // 查缓存
            val cached = cache.get(codes)
            val uncached = codes - cached.keys

            if (uncached.isEmpty()) {
                Log.d(tag, "All from cache")
                return@withContext cached
            }

            // 并发请求所有健康源
            val healthySources = sources.filter { sourceHealth[it] != false }
            if (healthySources.isEmpty()) {
                Log.w(tag, "No healthy sources")
                return@withContext cached
            }

            Log.d(tag, "Concurrent request from ${healthySources.size} sources")

            val tasks = healthySources.map { source ->
                async {
                    try {
                        val startTime = System.currentTimeMillis()
                        val result = source.fetchRealtime(uncached)
                        val elapsed = System.currentTimeMillis() - startTime

                        val prevTimeout = requestTimeouts[source] ?: 0L
                        requestTimeouts[source] = if (prevTimeout == 0L) elapsed else (prevTimeout + elapsed) / 2

                        Log.d(tag, "✓ ${source::class.simpleName}: ${result.size}/${uncached.size} (${elapsed}ms)")

                        if (sourceHealth[source] != true) sourceHealth[source] = true

                        result to source
                    } catch (e: Exception) {
                        Log.w(tag, "✗ ${source::class.simpleName}: ${e.message}")
                        sourceHealth[source] = false
                        emptyMap<String, StockRealtime>() to source
                    }
                }
            }

            val results = tasks.awaitAll()
            val freshData = results.filter { (data, _) -> data.isNotEmpty() }.firstOrNull()?.first ?: emptyMap()

            if (freshData.isNotEmpty()) {
                cache.put(freshData)
            }

            Log.d(tag, "Complete: ${(cached + freshData).size}/${codes.size}")
            cached + freshData
        }

    suspend fun healthCheck() = withContext(Dispatchers.IO) {
        Log.d(tag, "Health check...")
        sources.map { source ->
            async {
                try {
                    val isAvailable = source.isAvailable()
                    sourceHealth[source] = isAvailable
                    Log.d(tag, "  ${source::class.simpleName}: ${if (isAvailable) "✓" else "✗"}")
                } catch (e: Exception) {
                    sourceHealth[source] = false
                }
            }
        }.awaitAll()
    }

    fun clearCache() = cache.clear()

    fun getDiagnostics(): String = buildString {
        appendLine("═══════════════════════════════════════")
        appendLine("Repository Diagnostics")
        appendLine("═══════════════════════════════════════")
        appendLine("Sources: ${sourceHealth.count { it.value }}/${sources.size} healthy")
        for ((source, isHealthy) in sourceHealth) {
            val status = if (isHealthy) "✓" else "✗"
            val timeout = requestTimeouts[source] ?: 0L
            appendLine("$status ${source::class.simpleName} (${timeout}ms, p=${source.priority()})")
        }
        appendLine("═══════════════════════════════════════")
        appendLine(cache.getStats())
        appendLine("═══════════════════════════════════════")
    }
}

// ============================================================================
// 功能 C: 数据源工厂和管理
// ============================================================================

/**
 * 数据源工厂 - 管理优先级和配置
 */
object StockDataSourceFactory {
    private val tag = "StockDataSourceFactory"

    fun createDefaultSources(context: Context): List<StockDataSource> {
        val sources = mutableListOf<StockDataSource>()

        // 1. 聱宽（优先级2，需要Token）
        try {
            val jqToken = loadJoinQuantsToken(context)
            if (jqToken.isNotEmpty()) {
                sources.add(JoinQuantsSource(jqToken))
                Log.d(tag, "✓ JoinQuants (priority=2)")
            }
        } catch (e: Exception) {
            Log.w(tag, "JoinQuants failed")
        }

        // 2. 新浪财经（优先级1，核心源）
        sources.add(SinaStockSource())
        Log.d(tag, "✓ Sina (priority=1)")

        // 3. AKShare（优先级4，免费）
        sources.add(AKShareSource())
        Log.d(tag, "✓ AKShare (priority=4)")

        // 4. 腾讯财经（优先级2，备用）
        sources.add(TencentStockSource())
        Log.d(tag, "✓ Tencent (priority=2)")

        // 5. 东方财富（优先级3，备用）
        sources.add(EastMoneyStockSource())
        Log.d(tag, "✓ EastMoney (priority=3)")

        return sources.sortedBy { it.priority() }
    }

    fun createDefaultRepository(context: Context): MultiSourceStockRepository {
        val sources = createDefaultSources(context)
        return MultiSourceStockRepository(sources, SmartStockCache())
    }

    fun createLightweightRepository(context: Context): MultiSourceStockRepository {
        val sources = listOf(
            SinaStockSource(),
            AKShareSource(),
            TencentStockSource()
        ).sortedBy { it.priority() }
        return MultiSourceStockRepository(sources, SmartStockCache())
    }

    private fun loadJoinQuantsToken(context: Context): String {
        val sp = context.getSharedPreferences("stock_config", Context.MODE_PRIVATE)
        return sp.getString("joinquants_token", "") ?: ""
    }

    fun saveJoinQuantsToken(context: Context, token: String) {
        val sp = context.getSharedPreferences("stock_config", Context.MODE_PRIVATE)
        sp.edit().putString("joinquants_token", token).apply()
        Log.d(tag, "Token saved")
    }
}

// ============================================================================
// 新数据源：AKShare
// ============================================================================
class AKShareSource : StockDataSource {
    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val tag = "AKShareSource"

    override fun fetchRealtime(codes: List<String>): Map<String, StockRealtime> {
        if (codes.isEmpty()) return emptyMap()

        return try {
            val allData = fetchAllSpotData()
            allData.filterKeys { it in codes }.also {
                Log.d(tag, "Got ${it.size}/${codes.size}")
            }
        } catch (e: Exception) {
            Log.e(tag, "Error: ${e.message}")
            emptyMap()
        }
    }

    private fun fetchAllSpotData(): Map<String, StockRealtime> {
        val result = mutableMapOf<String, StockRealtime>()

        try {
            val request = okhttp3.Request.Builder()
                .url("http://api.akshare.tech/stock_zh_a_spot")
                .header("User-Agent", "Mozilla/5.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyMap()

            val body = response.body?.string() ?: return emptyMap()
            val jsonResponse = org.json.JSONObject(body)
            val dataArray = jsonResponse.optJSONArray("data") ?: org.json.JSONArray()

            for (i in 0 until dataArray.length()) {
                val item = dataArray.optJSONObject(i) ?: continue

                try {
                    val code = item.optString("code").ifBlank {
                        val symbol = item.optString("symbol")
                        when {
                            symbol.startsWith("6") || symbol.startsWith("9") -> "sh$symbol"
                            symbol.startsWith("4") || symbol.startsWith("8") -> "bj$symbol"
                            else -> "sz$symbol"
                        }
                    }

                    if (code.isBlank()) continue

                    val price = item.optDouble("price", 0.0)
                    if (price <= 0) continue

                    val yestClose = item.optDouble("last_close", price)

                    val stock = StockRealtime(
                        code = code,
                        name = item.optString("name"),
                        price = price,
                        open = item.optDouble("open", 0.0),
                        yestClose = yestClose,
                        high = item.optDouble("high", 0.0),
                        low = item.optDouble("low", 0.0),
                        volume = item.optLong("volume", 0L),
                        amount = item.optDouble("amount", 0.0),
                        changePercent = if (yestClose == 0.0) 0.0 else ((price - yestClose) / yestClose) * 100,
                        changeAmount = price - yestClose,
                        timestamp = System.currentTimeMillis()
                    )

                    result[code] = stock
                } catch (e: Exception) {
                    Log.v(tag, "Parse error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Exception: ${e.message}")
        }

        return result
    }

    override fun isAvailable(): Boolean {
        return try {
            val request = okhttp3.Request.Builder()
                .url("http://api.akshare.tech/stock_zh_a_spot")
                .header("User-Agent", "Mozilla/5.0")
                .build()

            val client2 = okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            client2.newCall(request).execute().isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    override fun priority(): Int = 4
}


// ============================================================================
// 新数据源：JoinQuants
// ============================================================================

class JoinQuantsSource(private val token: String = "") : StockDataSource {
    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val tag = "JoinQuantsSource"

    override fun fetchRealtime(codes: List<String>): Map<String, StockRealtime> {
        if (codes.isEmpty() || token.isBlank()) return emptyMap()

        Log.d(tag, "Fetching ${codes.size}")

        val result = mutableMapOf<String, StockRealtime>()

        try {
            val jqCodes = codes.map { normalizeToJQFormat(it) }
            val url = "https://api.joinquants.com/api/query_quote?security=${jqCodes.joinToString(",")}&token=$token"

            val request = okhttp3.Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) return emptyMap()

            val body = response.body?.string() ?: return emptyMap()
            val jsonResponse = org.json.JSONObject(body)

            if (jsonResponse.optInt("error_code") != 0) return emptyMap()

            val dataObj = jsonResponse.optJSONObject("data") ?: org.json.JSONObject()
            val iterator = dataObj.keys()

            while (iterator.hasNext()) {
                val jqCode = iterator.next()
                val item = dataObj.optJSONObject(jqCode) ?: continue

                try {
                    val androidCode = reverseNormalizeFromJQFormat(jqCode)
                    val price = item.optDouble("current", 0.0)

                    if (price <= 0) continue

                    val yestClose = item.optDouble("last_close", price)

                    val stock = StockRealtime(
                        code = androidCode,
                        name = item.optString("name", ""),
                        price = price,
                        open = item.optDouble("open", 0.0),
                        yestClose = yestClose,
                        high = item.optDouble("high", 0.0),
                        low = item.optDouble("low", 0.0),
                        volume = item.optLong("volume", 0L),
                        amount = item.optDouble("money", 0.0),
                        changePercent = if (yestClose == 0.0) 0.0 else ((price - yestClose) / yestClose) * 100,
                        changeAmount = price - yestClose,
                        timestamp = System.currentTimeMillis()
                    )

                    result[androidCode] = stock
                } catch (e: Exception) {
                    Log.v(tag, "Parse error: ${e.message}")
                }
            }

            Log.d(tag, "Got ${result.size}/${codes.size}")
        } catch (e: Exception) {
            Log.e(tag, "Exception: ${e.message}")
        }

        return result
    }

    private fun normalizeToJQFormat(androidCode: String): String {
        val code = androidCode.takeLast(6)
        return when {
            androidCode.startsWith("sh") -> "$code.XSHG"
            androidCode.startsWith("sz") -> "$code.XSHE"
            androidCode.startsWith("bj") -> "$code.XBJS"
            else -> "$code.XSHG"
        }
    }

    private fun reverseNormalizeFromJQFormat(jqCode: String): String {
        val code = jqCode.substringBefore(".")
        val market = jqCode.substringAfter(".")
        return when (market) {
            "XSHG" -> "sh$code"
            "XSHE" -> "sz$code"
            "XBJS" -> "bj$code"
            else -> "sh$code"
        }
    }

    override fun isAvailable(): Boolean {
        if (token.isBlank()) return false

        return try {
            val url = "https://api.joinquants.com/api/query_quote?security=000001.XSHE&token=$token"
            val request = okhttp3.Request.Builder()
                .url(url)
                .build()

            val client2 = okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            client2.newCall(request).execute().isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    override fun priority(): Int = 2
}