package com.chin.stockanalysis

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 股票實時數據提供商
 *
 * 封裝新浪財經免費 API，用於獲取A股實時行情。
 * 原理：在發送給 AI 之前，先掃描用戶輸入中的股票代碼，
 * 調用免費接口獲取實時數據，然後注入到 system prompt 中。
 *
 * 這樣做的好處：
 * - 不需要改動 LLM 接口（仍用 OpenAI 兼容格式）
 * - 不需要 Function Calling 支持
 * - 兼容 DeepSeek/硅基流動/OpenAI 所有模型
 *
 * 新浪財經 API (免費、無需 Key)：
 *   URL: https://hq.sinajs.cn/list={prefix}{code}
 *   prefix: sh=上海, sz=深圳, bj=北交所
 *   返回格式：var hq_str_sh600519="名稱,開盤價,昨收,當前價,最高,最低,..."
 */
class StockDataProvider {

    companion object {
        // 常見股票代碼 → 前綴映射
        private val PREFIX_MAP = mapOf(
            "6" to "sh",    // 6開頭: 上海主板
            "9" to "sh",    // 9開頭: 上海B股
            "0" to "sz",    // 0開頭: 深圳主板
            "3" to "sz",    // 3開頭: 創業板
            "4" to "bj",    // 4開頭: 北交所
            "8" to "bj"     // 8開頭: 北交所
        )

        // 股票名稱 → 代碼映射（熱門股票）
        private val NAME_TO_CODE = mapOf(
            // 白酒
            "貴州茅台" to "600519", "茅台" to "600519",
            "五糧液" to "000858", "瀘州老窖" to "000568",
            // 銀行
            "工商銀行" to "601398", "建設銀行" to "601939",
            "招商銀行" to "600036",
            // 科技
            "寧德時代" to "300750", "比亞迪" to "002594",
            "中興通訊" to "000063", "華為" to "002502", // 間接
            // 醫藥
            "恒瑞醫藥" to "600276", "藥明康德" to "603259",
            // 保險
            "中國平安" to "601318", "中國人壽" to "601628",
            // 其他
            "萬科A" to "000002", "格力電器" to "000651",
            "美的集團" to "000333", "海康威視" to "002415",
            // 指數
            "上證指數" to "000001", "上證" to "000001",
            "深證成指" to "399001", "深成指" to "399001",
            "創業板指" to "399006"
        )

        private val TIMEOUT_SECONDS = 10L

        // A股主要指數代碼
        private val INDEX_CODES = setOf("000001", "399001", "399006")

        // 用於識別股票代碼的正則
        private val STOCK_CODE_REGEX = Regex("""[0-9]{6}""")

        // 共享 OkHttpClient 实例，避免重复创建
        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        }
    }

    private val client: OkHttpClient get() = sharedClient

    /**
     * 從用戶消息中提取股票代碼列表
     *
     * 例如：
     * - "600519" → ["sh600519"]
     * - "茅台今天怎麼樣" → ["sh600519"]
     * - "上證指數和茅台" → ["sh000001", "sh600519"]
     */
    fun extractStockCodes(text: String): List<String> {
        val codes = mutableSetOf<String>()

        // 1. 先按股票名稱匹配
        for ((name, code) in NAME_TO_CODE) {
            if (text.contains(name)) {
                codes.add(formatCode(code))
            }
        }

        // 2. 再按純數字代碼匹配
        STOCK_CODE_REGEX.findAll(text).forEach { match ->
            val rawCode = match.value
            codes.add(formatCode(rawCode))
        }

        return codes.toList()
    }

    /**
     * 將6位數字代碼轉為新浪格式（前綴+代碼）
     * 例如: "600519" → "sh600519", "000001" → "sh000001"
     */
    private fun formatCode(code: String): String {
        // 如果已經是前綴格式，直接返回
        if (code.length > 6 && code.substring(2).all { it.isDigit() }) {
            return code
        }

        // 指數特殊處理
        if (code in INDEX_CODES) {
            return when (code) {
                "000001" -> "sh000001"  // 上證指數
                "399001" -> "sz399001"  // 深證成指
                "399006" -> "sz399006"  // 創業板指
                else -> "sh$code"
            }
        }

        val prefix = PREFIX_MAP.entries.find { code.startsWith(it.key) }?.value ?: "sh"
        return "$prefix$code"
    }

    /**
     * 批量查詢股票實時數據
     *
     * @param stockCodes 股票代碼列表（如 ["sh600519", "sz000858"]）
     * @return Map<代碼, Map<字段名, 值>>
     */
    fun fetchBatch(stockCodes: List<String>): Map<String, Map<String, String>> {
        if (stockCodes.isEmpty()) return emptyMap()

        val result = mutableMapOf<String, Map<String, String>>()

        try {
            // 新浪 API 支持批量查詢，多個代碼用逗號分隔
            val codesParam = stockCodes.joinToString(",")
            val url = "https://hq.sinajs.cn/list=$codesParam"

            val request = Request.Builder()
                .url(url)
                .header("Referer", "https://finance.sina.com.cn")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return result
            }

            val body = response.body?.string() ?: return result

            // 解析返回的每一行
            // 格式: var hq_str_sh600519="貴州茅台,1690.00,1688.00,1702.00,1710.00,1685.00,....";
            val lines = body.split("\n")
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue

                // 提取 var hq_str_{code}="..."
                val match = Regex("""var hq_str_(\w+)="(.*)";?""").find(trimmed)
                if (match != null) {
                    val code = match.groupValues[1]
                    val dataStr = match.groupValues[2]
                    val fields = dataStr.split(",")

                    if (fields.size >= 32) {
                        result[code] = mapOf(
                            "name" to fields[0],           // 股票名稱
                            "open" to fields[1],           // 開盤價
                            "yestClose" to fields[2],      // 昨日收盤價
                            "price" to fields[3],          // 當前價
                            "high" to fields[4],           // 最高
                            "low" to fields[5],            // 最低
                            "volume" to fields[8],         // 成交量(手)
                            "amount" to fields[9],         // 成交額
                            "buy1" to fields[11],          // 買一
                            "sell1" to fields[21],         // 賣一
                            "date" to fields[30],          // 日期
                            "time" to fields[31]           // 時間
                        )
                    } else if (fields.size >= 4 && (code.startsWith("sh000") || code.startsWith("sz399"))) {
                        // 指數格式較短
                        result[code] = mapOf(
                            "name" to fields[0],
                            "open" to fields[1],
                            "yestClose" to fields[2],
                            "price" to fields[3],
                            "high" to fields[4],
                            "low" to fields[5],
                            "volume" to fields[6],
                            "amount" to fields[7]
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // 靜默失敗，不影響主流程
        }

        return result
    }

    /**
     * 從用戶消息中提取股票代碼並異步獲取實時數據
     * 返回格式化的字符串，適合注入 system prompt
     *
     * ⚠️ 此方法包含網絡請求，必須在後台線程調用！
     *
     * @param userMessage 用戶輸入的消息
     * @return 格式化後的實時數據字符串，如果沒檢測到股票代碼則返回 null
     */
    fun getRealtimeDataForPromptAsync(userMessage: String): String? {
        val stockCodes = extractStockCodes(userMessage)
        if (stockCodes.isEmpty()) return null

        val data = fetchBatch(stockCodes)
        if (data.isEmpty()) return null

        return formatStockData(data)
    }

    /**
     * 格式化股票數據為可讀字符串
     */
    private fun formatStockData(data: Map<String, Map<String, String>>): String {
        val sb = StringBuilder()
        sb.appendLine("【實時行情數據】")
        sb.appendLine("（以下是用戶提問涉及的股票實時數據，請根據這些數據回答）")

        for ((code, fields) in data) {
            val name = fields["name"] ?: code
            val price = fields["price"] ?: "--"
            val change = calculateChange(price, fields["yestClose"] ?: "")
            val high = fields["high"] ?: "--"
            val low = fields["low"] ?: "--"
            val volume = fields["volume"] ?: "--"
            val time = fields["time"] ?: ""

            sb.appendLine("• $name ($code)：")
            sb.appendLine("  當前價: $price 元")
            if (change != null) {
                sb.appendLine("  漲跌: ${change.first} (${change.second})")
            }
            sb.appendLine("  最高: $high  最低: $low")
            if (volume != "--") {
                val volumeNum = volume.toLongOrNull()
                if (volumeNum != null) {
                    sb.appendLine("  成交量: ${volumeNum / 10000} 萬手")
                }
            }
            if (time.isNotEmpty()) {
                sb.appendLine("  更新時間: $time")
            }
            sb.appendLine()
        }

        return sb.toString()
    }

    /**
     * 計算漲跌幅
     * @return (漲跌額, 漲跌幅%) 或 null（計算失敗）
     */
    private fun calculateChange(price: String, yestClose: String): Pair<String, String>? {
        val p = price.toDoubleOrNull() ?: return null
        val yc = yestClose.toDoubleOrNull() ?: return null
        if (yc == 0.0) return null

        val diff = p - yc
        val percent = (diff / yc) * 100

        val arrow = if (diff > 0) "▲" else if (diff < 0) "▼" else "─"
        return Pair(
            String.format("$arrow %.2f", diff),
            String.format("%+.2f%%", percent)
        )
    }
}