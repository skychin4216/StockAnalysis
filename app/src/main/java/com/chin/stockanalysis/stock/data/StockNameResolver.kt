package com.chin.stockanalysis.stock.data

import android.util.Log
import com.chin.stockanalysis.config.DataConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * ## 股票名称解析器
 *
 * 当数据库中没有股票名称时，从东方财富 API 动态获取。
 * 利用东方财富的公开行情接口，通过股票代码查询名称。
 *
 * 使用方式：
 * ```kotlin
 * val name = StockNameResolver.resolve("600519") // → "贵州茅台"
 * ```
 */
object StockNameResolver {

    private const val TAG = "StockNameResolver"
    private const val TIMEOUT_MS = 5000

    /** 内存缓存（避免重复请求） */
    private val cache = mutableMapOf<String, String>()

    /**
     * 根据股票代码获取名称
     *
     * @param code 纯数字代码或 sh/sz 前缀代码（如 "600519", "sh600519", "sz000858"）
     * @return 股票名称，失败返回 null
     */
    suspend fun resolve(code: String): String? {
        // 去除前缀
        val rawCode = code.removePrefix("sh").removePrefix("sz").trim()
        if (rawCode.isBlank() || rawCode.length < 6) return null

        // 缓存命中
        cache[rawCode]?.let { return it }

        return try {
            withContext(Dispatchers.IO) {
                fetchFromEastMoney(rawCode)?.also { cache[rawCode] = it }
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取名称失败: $rawCode — ${e.message}")
            null
        }
    }

    /**
     * 批量解析（并发请求，最多 10 个并发）
     */
    suspend fun resolveBatch(codes: List<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val unresolved = codes.filter {
            val raw = it.removePrefix("sh").removePrefix("sz")
            cache[raw]?.let { name -> result[it] = name; false } ?: true
        }
        if (unresolved.isEmpty()) return result

        // 每批最多 20 个
        unresolved.chunked(20).forEach { batch ->
            val batchResult = fetchBatchFromEastMoney(batch)
            batchResult.forEach { (code, name) ->
                val raw = code.removePrefix("sh").removePrefix("sz")
                cache[raw] = name
                result[code] = name
            }
        }
        return result
    }

    // ═══════════════════════════════════════
    // 东方财富 API
    // ═══════════════════════════════════════

    /**
     * 单个股票查询
     * API: https://push2.eastmoney.com/api/qt/stock/get?secid=1.600519&fields=f57,f58
     */
    private fun fetchFromEastMoney(rawCode: String): String? {
        val market = when {
            rawCode.startsWith("6") -> "1"  // 上海
            rawCode.startsWith("0") || rawCode.startsWith("3") -> "0"  // 深圳
            rawCode.startsWith("4") || rawCode.startsWith("8") -> "1"  // 北交所/科创板
            else -> return null
        }
        val secid = "$market.$rawCode"
        val url = "${DataConfig.eastmoneyPush2}/stock/get?secid=$secid&fields=f57,f58"

        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")

            val response = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).readText()
            val obj = JSONObject(response)
            val data = obj.optJSONObject("data")
            data?.optString("f58")?.takeIf { it.isNotBlank() }
                ?: data?.optString("f57")?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "请求失败: $secid — ${e.message}")
            null
        }
    }

    /**
     * 批量查询（最多 50 个）
     * API: https://push2.eastmoney.com/api/qt/clist/get?pn=1&pz=50&fs=m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23&fields=f12,f14
     */
    private fun fetchBatchFromEastMoney(codes: List<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val secids = codes.map { raw ->
            val market = when {
                raw.startsWith("sh") -> { val c = raw.removePrefix("sh"); "1.$c" }
                raw.startsWith("sz") -> { val c = raw.removePrefix("sz"); "0.$c" }
                raw.startsWith("6") -> "1.$raw"
                else -> "0.$raw"
            }
        }
        val filter = secids.joinToString(",")
        val url = "${DataConfig.eastmoneyPush2}/clist/get?pn=1&pz=50&fs=$filter&fields=f12,f14"

        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")

            val response = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).readText()
            val obj = JSONObject(response)
            val data = obj.optJSONObject("data")
            val list = data?.optJSONArray("diff")

            if (list != null) {
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    val code = item.optString("f12", "")
                    val name = item.optString("f14", "")
                    if (code.isNotBlank() && name.isNotBlank()) {
                        result[code] = name
                    }
                }
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "批量请求失败: ${e.message}")
            emptyMap()
        }
    }

    /**
     * 清除缓存
     */
    fun clearCache() {
        cache.clear()
    }
}