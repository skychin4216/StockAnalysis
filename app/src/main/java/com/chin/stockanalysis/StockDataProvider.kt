package com.chin.stockanalysis

import com.chin.stockanalysis.config.DataConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 股票实时数据提供商
 *
 * 封装新浪财经免费 API，用于获取A股实时行情。
 * 原理：在发送给 AI 之前，先扫描用户输入中的股票代码，
 * 调用免费接口获取实时数据，然后注入到 system prompt 中。
 *
 * 这样做的好处：
 * - 不需要改动 LLM 接口（仍用 OpenAI 兼容格式）
 * - 不需要 Function Calling 支持
 * - 兼容 DeepSeek/硅基流动/OpenAI 所有模型
 *
 * 新浪财经 API (免费、无需 Key)：
 *   URL: https://hq.sinajs.cn/list={prefix}{code}
 *   prefix: sh=上海, sz=深圳, bj=北交所
 *   返回格式：var hq_str_sh600519="名称,开盘价,昨收,当前价,最高,最低,..."
 */
class StockDataProvider {

    companion object {
        // 常见股票代码 → 前缀映射
        private val PREFIX_MAP = mapOf(
            "6" to "sh",    // 6开头: 上海主板
            "9" to "sh",    // 9开头: 上海B股
            "0" to "sz",    // 0开头: 深圳主板
            "3" to "sz",    // 3开头: 创业板
            "4" to "bj",    // 4开头: 北交所
            "8" to "bj"     // 8开头: 北交所
        )

        // 股票名称 → 代码映射（热门股票）
        private val NAME_TO_CODE = mapOf(
            // 白酒
            "贵州茅台" to "600519", "茅台" to "600519",
            "五粮液" to "000858", "泸州老窖" to "000568",
            // 银行
            "工商银行" to "601398", "建设银行" to "601939",
            "招商银行" to "600036",
            // 科技
            "宁德时代" to "300750", "比亚迪" to "002594",
            "中兴通讯" to "000063", "华为" to "002502", // 间接
            // 医药
            "恒瑞医药" to "600276", "药明康德" to "603259",
            // 保险
            "中国平安" to "601318", "中国人寿" to "601628",
            // 其他
            "万科A" to "000002", "格力电器" to "000651",
            "美的集团" to "000333", "海康威视" to "002415",
            // 指数
            "上证指数" to "000001", "上证" to "000001",
            "深证成指" to "399001", "深成指" to "399001",
            "创业板指" to "399006"
        )

        private val TIMEOUT_SECONDS = 10L

        // A股主要指数代码
        private val INDEX_CODES = setOf("000001", "399001", "399006")

        // 用于识别股票代码的正则
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
     * 从用户消息中提取股票代码列表
     *
     * 例如：
     * - "600519" → ["sh600519"]
     * - "茅台今天怎么样" → ["sh600519"]
     * - "上证指数和茅台" → ["sh000001", "sh600519"]
     */
    fun extractStockCodes(text: String): List<String> {
        val codes = mutableSetOf<String>()

        // 1. 先按股票名称匹配
        for ((name, code) in NAME_TO_CODE) {
            if (text.contains(name)) {
                codes.add(formatCode(code))
            }
        }

        // 2. 再按纯数字代码匹配
        STOCK_CODE_REGEX.findAll(text).forEach { match ->
            val rawCode = match.value
            codes.add(formatCode(rawCode))
        }

        return codes.toList()
    }

    /**
     * 将6位数字代码转为新浪格式（前缀+代码）
     * 例如: "600519" → "sh600519", "000001" → "sh000001"
     */
    private fun formatCode(code: String): String {
        // 如果已经是前缀格式，直接返回
        if (code.length > 6 && code.substring(2).all { it.isDigit() }) {
            return code
        }

        // 指数特殊处理
        if (code in INDEX_CODES) {
            return when (code) {
                "000001" -> "sh000001"  // 上证指数
                "399001" -> "sz399001"  // 深证成指
                "399006" -> "sz399006"  // 创业板指
                else -> "sh$code"
            }
        }

        val prefix = PREFIX_MAP.entries.find { code.startsWith(it.key) }?.value ?: "sh"
        return "$prefix$code"
    }

    /**
     * 批量查询股票实时数据
     *
     * @param stockCodes 股票代码列表（如 ["sh600519", "sz000858"]）
     * @return Map<代码, Map<字段名, 值>>
     */
    fun fetchBatch(stockCodes: List<String>): Map<String, Map<String, String>> {
        if (stockCodes.isEmpty()) return emptyMap()

        val result = mutableMapOf<String, Map<String, String>>()

        try {
            // 新浪 API 支持批量查询，多个代码用逗号分隔
            val codesParam = stockCodes.joinToString(",")
            val url = "${DataConfig.sinaHq}/list=$codesParam"

            val request = Request.Builder()
                .url(url)
                .header("Referer", DataConfig.sinaFinance)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return result
            }

            val body = response.body?.string() ?: return result

            // 解析返回的每一行
            // 格式: var hq_str_sh600519="贵州茅台,1690.00,1688.00,1702.00,1710.00,1685.00,....";
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
                            "name" to fields[0],           // 股票名称
                            "open" to fields[1],           // 开盘价
                            "yestClose" to fields[2],      // 昨日收盘价
                            "price" to fields[3],          // 当前价
                            "high" to fields[4],           // 最高
                            "low" to fields[5],            // 最低
                            "volume" to fields[8],         // 成交量(手)
                            "amount" to fields[9],         // 成交额
                            "buy1" to fields[11],          // 买一
                            "sell1" to fields[21],         // 卖一
                            "date" to fields[30],          // 日期
                            "time" to fields[31]           // 时间
                        )
                    } else if (fields.size >= 4 && (code.startsWith("sh000") || code.startsWith("sz399"))) {
                        // 指数格式较短
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
            // 静默失败，不影响主流程
        }

        return result
    }

    /**
     * 从用户消息中提取股票代码并异步获取实时数据
     * 返回格式化的字符串，适合注入 system prompt
     *
     * ⚠️ 此方法包含网络请求，必须在后台线程调用！
     *
     * @param userMessage 用户输入的消息
     * @return 格式化后的实时数据字符串，如果没检测到股票代码则返回 null
     */
    fun getRealtimeDataForPromptAsync(userMessage: String): String? {
        val stockCodes = extractStockCodes(userMessage)
        if (stockCodes.isEmpty()) return null

        val data = fetchBatch(stockCodes)
        if (data.isEmpty()) return null

        return formatStockData(data)
    }

    /**
     * 格式化股票数据为可读字符串
     */
    private fun formatStockData(data: Map<String, Map<String, String>>): String {
        val sb = StringBuilder()
        sb.appendLine("【实时行情数据】")
        sb.appendLine("（以下是用户提问涉及的股票实时数据，请根据这些数据回答）")

        for ((code, fields) in data) {
            val name = fields["name"] ?: code
            val price = fields["price"] ?: "--"
            val change = calculateChange(price, fields["yestClose"] ?: "")
            val high = fields["high"] ?: "--"
            val low = fields["low"] ?: "--"
            val volume = fields["volume"] ?: "--"
            val time = fields["time"] ?: ""

            sb.appendLine("• $name ($code)：")
            sb.appendLine("  当前价: $price 元")
            if (change != null) {
                sb.appendLine("  涨跌: ${change.first} (${change.second})")
            }
            sb.appendLine("  最高: $high  最低: $low")
            if (volume != "--") {
                val volumeNum = volume.toLongOrNull()
                if (volumeNum != null) {
                    sb.appendLine("  成交量: ${volumeNum / 10000} 万手")
                }
            }
            if (time.isNotEmpty()) {
                sb.appendLine("  更新时间: $time")
            }
            sb.appendLine()
        }

        return sb.toString()
    }

    /**
     * 计算涨跌幅
     * @return (涨跌额, 涨跌幅%) 或 null（计算失败）
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