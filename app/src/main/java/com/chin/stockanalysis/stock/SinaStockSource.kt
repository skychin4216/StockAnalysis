package com.chin.stockanalysis.stock

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.Charset

/**
 * 新浪财经实时行情数据源
 * 
 * 优点：免费、无需 token、支持批量查询
 * 缺点：GBK 编码、可能有频率限制
 * 
 * API: http://hq.sinajs.cn/list=sh600519,sz000001
 * 返回格式（GBK）: var hq_str_sh600519="浦发银行,13.03,13.04,13.10,13.22,12.90,13.09,13.10,34438974,448511198.00,...";
 */
class SinaStockSource(private val httpClient: OkHttpClient = OkHttpClient()) {
    
    companion object {
        private const val TAG = "SinaStockSource"
        private const val BASE_URL = "http://hq.sinajs.cn/list="
    }
    
    suspend fun fetchRealtime(codes: List<String>): Map<String, StockRealtime> {
        if (codes.isEmpty()) return emptyMap()
        
        return try {
            // 构建 URL：将代码列表用逗号分隔
            val codeStr = codes.joinToString(",")
            val url = BASE_URL + codeStr
            
            Log.d(TAG, "Fetching from: $url")
            
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            
            val response = httpClient.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Log.e(TAG, "HTTP Error: ${response.code}")
                return emptyMap()
            }
            
            // ⚠️ 关键步骤：新浪返回 GBK 编码
            val responseBody = response.body?.bytes() ?: return emptyMap()
            val text = String(responseBody, Charset.forName("GBK"))
            
            Log.d(TAG, "Response: ${text.take(200)}")
            
            parseResponse(text, codes)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching data", e)
            emptyMap()
        }
    }
    
    /**
     * 解析新浪返回的数据
     * 
     * 格式示例：
     * var hq_str_sh600519="浦发银行,13.03,13.04,13.10,13.22,12.90,13.09,13.10,34438974,448511198.00,2024-01-15,15:00:00,00";
     * 
     * 字段解析（逗号分隔，共11项）:
     * 0: 股票名称 (浦发银行)
     * 1: 今日开盘价 (13.03)
     * 2: 昨日收盘价 (13.04)
     * 3: 当前价格 (13.10)
     * 4: 最高价 (13.22)
     * 5: 最低价 (12.90)
     * 6: 竞买价 (13.09) - 买一价
     * 7: 竞卖价 (13.10) - 卖一价
     * 8: 成交量 (34438974) - 单位是股
     * 9: 成交额 (448511198.00) - 单位是元
     * 10: 时间戳 (2024-01-15,15:00:00)
     * 11: 状态标志
     */
    private fun parseResponse(text: String, requestedCodes: List<String>): Map<String, StockRealtime> {
        val result = mutableMapOf<String, StockRealtime>()
        
        // 正则表达式：匹配 var hq_str_xxx="..."
        val regex = """var hq_str_(\w+)="([^"]+)"""".toRegex()
        
        regex.findAll(text).forEach { match ->
            try {
                val code = match.groupValues[1] // sh600519
                val data = match.groupValues[2] // "浦发银行,13.03,13.04,..."
                
                val fields = data.split(",")
                
                if (fields.size >= 11) {
                    val name = fields[0]
                    val openPrice = fields[1].toDoubleOrNull() ?: 0.0
                    val yestClose = fields[2].toDoubleOrNull() ?: 0.0
                    val currentPrice = fields[3].toDoubleOrNull() ?: 0.0
                    val highPrice = fields[4].toDoubleOrNull() ?: 0.0
                    val lowPrice = fields[5].toDoubleOrNull() ?: 0.0
                    val volume = fields[8].toLongOrNull() ?: 0L
                    val amount = fields[9].toDoubleOrNull() ?: 0.0
                    
                    val changeAmount = currentPrice - yestClose
                    val changePercent = if (yestClose > 0) (changeAmount / yestClose * 100) else 0.0
                    
                    result[code] = StockRealtime(
                        code = code,
                        name = name,
                        price = currentPrice,
                        open = openPrice,
                        yestClose = yestClose,
                        high = highPrice,
                        low = lowPrice,
                        volume = volume,
                        amount = amount,
                        changeAmount = changeAmount,
                        changePercent = changePercent,
                        timestamp = System.currentTimeMillis()
                    )
                    
                    Log.d(TAG, "Parsed: $code - $name $currentPrice")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing row: ${e.message}")
            }
        }
        
        return result
    }
}
