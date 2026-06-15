package com.chin.stockanalysis.strategy.data

import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.data.MultiSourceStockRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ## 股票筛选器 — 市场数据扫描器
 *
 * 从东方财富获取全市场 A 股实时行情数据，供策略扫描使用。
 *
 * ### 数据流
 * ```
 * StockScreener.scanFullMarket()
 *   → 东方财富全市场实时行情 API
 *   → 解析 JSON → List<StockRealtime>
 *   → 策略逐只筛选
 * ```
 */
class StockScreener(
    private val repository: MultiSourceStockRepository
) {
    companion object {
        private const val TAG = "StockScreener"
        private const val TIMEOUT = 10L

        /** 东方财富全市场实时行情 API（前200只） */
        private const val FULL_MARKET_URL =
            "https://push2.eastmoney.com/api/qt/clist/get?" +
                    "pn=1&pz=200&po=1&np=1&fltt=2&invt=2&fid=f3&fs=m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23" +
                    "&fields=f2,f3,f4,f5,f6,f7,f8,f9,f10,f12,f14,f15,f16,f17,f18,f20,f23"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT, TimeUnit.SECONDS)
        .build()

    /**
     * 扫描全市场 A 股实时行情（前200只，按涨跌幅排名）
     *
     * @return 实时行情列表
     */
    fun scanFullMarket(): List<StockRealtime> {
        return try {
            val request = Request.Builder()
                .url(FULL_MARKET_URL)
                .addHeader("User-Agent", "Mozilla/5.0")
                .addHeader("Referer", "https://quote.eastmoney.com/")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "扫描全市场失败: HTTP ${response.code}")
                return emptyList()
            }
            val body = response.body?.string() ?: ""
            parseFullMarketResponse(body)
        } catch (e: Exception) {
            Log.e(TAG, "扫描全市场异常: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 按指定股票代码获取实时行情
     *
     * @param codes 股票代码列表
     * @return 实时行情 Map
     */
    fun scanSpecific(codes: List<String>): Map<String, StockRealtime> {
        if (codes.isEmpty()) return emptyMap()
        return repository.getRealtime(codes)
    }

    /**
     * 获取集合竞价数据（盘前）
     *
     * @param codes 关注的股票代码
     * @return 竞价数据 Map
     */
    fun getPreMarketData(codes: List<String>): Map<String, StockRealtime> {
        if (codes.isEmpty()) return emptyMap()
        return repository.getRealtime(codes)
    }

    // ═══════════════════════════════
    // 内部解析
    // ═══════════════════════════════

    private fun parseFullMarketResponse(body: String): List<StockRealtime> {
        return try {
            val json = JSONObject(body)
            val data = json.optJSONObject("data")
            val diffList = data?.optJSONArray("diff") ?: return emptyList()

            val results = mutableListOf<StockRealtime>()
            for (i in 0 until diffList.length()) {
                val item = diffList.getJSONObject(i)
                val code = item.optString("f12", "")
                val prefix = when {
                    code.startsWith("6") || code.startsWith("9") -> "sh"
                    code.startsWith("4") || code.startsWith("8") -> "bj"
                    else -> "sz"
                }

                // f9=市盈率(动态PE), f20=总市值(元), f23=市净率(PB)
                val rawPE = item.optDouble("f9", -999.0)
                val rawMC = item.optDouble("f20", -999.0)  // 东方财富返回的是"元"还是其他单位需看实际
                val rawPB = item.optDouble("f23", -999.0)

                results.add(
                    StockRealtime(
                        code = "$prefix$code",
                        name = item.optString("f14", "").let { raw ->
                            if (raw.startsWith("XD") || raw.startsWith("XR") || raw.startsWith("DR"))
                                raw.removePrefix("XD").removePrefix("XR").removePrefix("DR").trim()
                            else raw
                        },
                        price = item.optDouble("f2", 0.0),
                        open = item.optDouble("f17", 0.0),
                        yestClose = item.optDouble("f18", 0.0),
                        high = item.optDouble("f15", 0.0),
                        low = item.optDouble("f16", 0.0),
                        volume = item.optLong("f5", 0L) * 100,  // 东方财富单位手→股
                        amount = item.optDouble("f6", 0.0) * 10000,  // 东方财富万元→元
                        changePercent = item.optDouble("f3", 0.0),
                        changeAmount = item.optDouble("f4", 0.0),
                        turnoverRate = item.optDouble("f8", 0.0),
                        pe = if (rawPE > -900.0 && !rawPE.isNaN() && !rawPE.isInfinite()) rawPE else 0.0,
                        marketCap = if (rawMC > -900.0 && !rawMC.isNaN() && !rawMC.isInfinite()) rawMC else 0.0,
                        pb = if (rawPB > -900.0 && !rawPB.isNaN() && !rawPB.isInfinite()) rawPB else 0.0,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }

            val sampleNames = results.take(5).joinToString(" | ") { "${it.code}=${it.name} price=${it.price} chg=${it.changePercent}%" }
            Log.i(TAG, "扫描全市场: ${results.size} 只股票, 样本: $sampleNames")
            results
        } catch (e: Exception) {
            Log.e(TAG, "解析全市场响应失败: ${e.message}", e)
            emptyList()
        }
    }
}