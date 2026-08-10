package com.chin.stockanalysis.strategy.data

import android.util.Log
import com.chin.stockanalysis.config.DataConfig
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.data.HttpClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * ## Level2 数据提供者
 *
 * 批量获取东方财富资金流向和买卖盘数据，为 [StockRealtime] 填充 Level2 字段：
 * - [StockRealtime.largeOrderBuyRatio]：特大单买入占比（超大单净流入 / 成交额）
 * - [StockRealtime.bidAskSpread]：买卖价差（卖1价 - 买1价） / 买1价）
 *
 * ### 数据来源
 * 东方财富 ulist.np 批量行情 API，支持一次请求最多 50 只股票。
 *
 * ### 使用方式
 * ```kotlin
 * val enriched = Level2DataProvider.enrichStocks(filteredStocks)
 * // enriched 中的 StockRealtime 已填充 largeOrderBuyRatio 和 bidAskSpread
 * ```
 *
 * ### 设计原则
 * - **批量请求**：50 只股票一批，减少 HTTP 请求次数
 * - **容错降级**：API 异常或字段缺失时返回原始数据（不阻塞策略执行）
 * - **按需调用**：仅在策略 `requiresL2Data == true` 时调用
 */
object Level2DataProvider {

    private const val TAG = "Level2DataProvider"
    private const val BATCH_SIZE = 50

    /**
     * 批量获取 Level2 数据并填充到 [StockRealtime] 中。
     *
     * @param stocks 待填充的股票列表（通常是经过硬性过滤后的候选股）
     * @return 填充了 `largeOrderBuyRatio` 和 `bidAskSpread` 的新列表（原始对象不变）
     */
    suspend fun enrichStocks(stocks: List<StockRealtime>): List<StockRealtime> =
        withContext(Dispatchers.IO) {
            if (stocks.isEmpty()) return@withContext stocks

            val level2Map = fetchLevel2Batch(stocks.map { it.code })
            if (level2Map.isEmpty()) {
                Log.w(TAG, "Level2 数据获取失败（全量），返回原始数据")
                return@withContext stocks
            }

            stocks.map { stock ->
                val l2 = level2Map[stock.code]
                if (l2 != null) {
                    stock.copy(
                        largeOrderBuyRatio = l2.largeOrderBuyRatio,
                        bidAskSpread = l2.bidAskSpread
                    )
                } else {
                    stock
                }
            }
        }

    /**
     * Level2 数据容器
     */
    data class Level2Data(
        /** 超大单买入占比（超大单净流入 / 成交额），0 = 无数据或净流出 */
        val largeOrderBuyRatio: Double = 0.0,
        /** 买卖价差比例（卖1-买1）/ 买1，0 = 无数据 */
        val bidAskSpread: Double = 0.0
    )

    /**
     * 批量获取 Level2 数据
     *
     * @param codes 股票代码列表（如 sh600519, sz000858）
     * @return 代码 → Level2 数据 的映射
     */
    private suspend fun fetchLevel2Batch(codes: List<String>): Map<String, Level2Data> =
        withContext(Dispatchers.IO) {
            val result = mutableMapOf<String, Level2Data>()
            val client = HttpClientProvider.realtimeClient

            codes.chunked(BATCH_SIZE).forEach { batch ->
                try {
                    val secids = batch.joinToString(",") { toSecId(it) }
                    // f12=代码, f6=成交额, f62=主力净流入, f135=超大单净流入,
                    // f31=买1价, f41=卖1价
                    val url = "${DataConfig.eastmoneyPush2}/ulist.np/get" +
                        "?fltt=2&invt=2&secids=$secids" +
                        "&fields=f12,f6,f62,f135,f31,f41"

                    val req = Request.Builder().url(url)
                        .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .addHeader("Referer", DataConfig.eastmoneyQuote)
                        .build()

                    val resp = client.newCall(req).execute()
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "API 请求失败: HTTP ${resp.code}")
                        resp.close()
                        return@forEach
                    }

                    val body = resp.body?.string()
                    resp.close()
                    if (body.isNullOrBlank()) {
                        Log.w(TAG, "API 返回空响应")
                        return@forEach
                    }

                    parseLevel2Response(body, result)
                } catch (e: Exception) {
                    Log.w(TAG, "批量获取 Level2 异常: ${e.message}")
                }
            }

            Log.i(TAG, "Level2 数据获取完成: ${result.size}/${codes.size} 只股票有数据")
            result
        }

    /**
     * 解析东方财富 ulist.np 响应，提取 Level2 数据
     */
    private fun parseLevel2Response(body: String, result: MutableMap<String, Level2Data>) {
        try {
            val json = JSONObject(body)
            val data = json.optJSONObject("data") ?: return
            val diff = data.optJSONArray("diff") ?: return

            for (i in 0 until diff.length()) {
                val item = diff.optJSONObject(i) ?: continue
                val rawCode = item.optString("f12")
                if (rawCode.isBlank()) continue
                val code = normalizeCode(rawCode)

                val amount = optDoubleSafe(item, "f6")
                val superLargeInflow = optDoubleSafe(item, "f135")
                val bid1 = optDoubleSafe(item, "f31")
                val ask1 = optDoubleSafe(item, "f41")

                // 超大单买入占比：超大单净流入 / 成交额
                // 仅当净流入为正时才有意义（表示主力在买入）
                val largeOrderBuyRatio = if (amount > 0 && superLargeInflow > 0) {
                    (superLargeInflow / amount).coerceIn(0.0, 1.0)
                } else {
                    0.0
                }

                // 买卖价差比例：（卖1 - 买1）/ 买1
                val bidAskSpread = if (bid1 > 0 && ask1 > 0 && ask1 >= bid1) {
                    ((ask1 - bid1) / bid1).coerceIn(0.0, 0.1)
                } else {
                    0.0
                }

                if (largeOrderBuyRatio > 0 || bidAskSpread > 0) {
                    result[code] = Level2Data(largeOrderBuyRatio, bidAskSpread)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析 Level2 响应异常: ${e.message}")
        }
    }

    /**
     * 安全读取 JSON Double 值（兼容 Number 和 String 类型）
     */
    private fun optDoubleSafe(json: JSONObject, key: String): Double {
        val value = json.opt(key) ?: return 0.0
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
    }

    /**
     * sh600519 -> 1.600519, sz000858 -> 0.000858
     */
    private fun toSecId(code: String): String {
        val raw = code.takeLast(6)
        val market = when {
            code.startsWith("sh") || raw.startsWith("6") || raw.startsWith("9") -> "1"
            else -> "0"
        }
        return "$market.$raw"
    }

    /**
     * 将纯代码（600519）还原为带前缀的完整代码（sh600519）
     */
    private fun normalizeCode(rawCode: String): String {
        return when {
            rawCode.startsWith("6") || rawCode.startsWith("9") -> "sh$rawCode"
            rawCode.startsWith("8") || rawCode.startsWith("4") -> "bj$rawCode"
            else -> "sz$rawCode"
        }
    }
}
