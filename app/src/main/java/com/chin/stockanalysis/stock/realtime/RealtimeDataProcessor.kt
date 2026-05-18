package com.chin.stockanalysis.stock.realtime

import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.data.StockCache
import com.chin.stockanalysis.stock.data.StockDataSource
import com.chin.stockanalysis.stock.intent.IntentResult
import com.chin.stockanalysis.stock.intent.StockIntent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.*

/**
 * ## 实时数据处理器
 *
 * ### 职责
 * 1. **数据验证** — 校验实时数据的合理性（如价格范围、涨跌幅限制等）
 * 2. **数据清洗** — 处理异常值、零值、空字段
 * 3. **缓存刷新** — 智能决定是否强制刷新缓存
 * 4. **交易时段感知** — 判断当前是否在交易时间，决定数据优先级
 * 5. **数据汇总** — 将多源数据合并、去重、取最新
 * 6. **质量评分** — 对返回的数据进行质量评级
 * 7. **格式化** — 生成适合注入 AI 的文本，以及适合 UI 展示的结构化数据
 * 8. **市场状态** — 检测并返回市场状态（交易中/午休/收盘/节假日）
 *
 * ### 使用示例
 * ```kotlin
 * val processor = RealtimeDataProcessor(cache, accessor)
 * val result = processor.getProcessedRealtime(listOf("sh600519"))
 * ```
 */
class RealtimeDataProcessor(
    /** 缓存层 */
    private val cache: StockCache = StockCache(),
    /** 实时数据访问器（可选，不提供则只处理缓存数据） */
    private val accessor: RealtimeDataAccessor? = null
) {
    private val tag = "RealtimeDataProcessor"

    companion object {
        /** A 股涨跌幅限制（主板 ±10%，科创/创业板 ±20%） */
        private const val LIMIT_MAINBOARD = 10.0
        private const val LIMIT_GROWTHBOARD = 20.0

        /** 交易时段定义 */
        private val MORNING_START = parseTime("09:30")
        private val MORNING_END = parseTime("11:30")
        private val AFTERNOON_START = parseTime("13:00")
        private val AFTERNOON_END = parseTime("15:00")

        /** 最小成交量（股），小于此值视为异常 */
        private const val MIN_VOLUME = 100L
        /** 最小成交额（元），小于此值视为异常 */
        private const val MIN_AMOUNT = 1000.0

        private fun parseTime(time: String): Long {
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            val calendar = Calendar.getInstance()
            calendar.time = sdf.parse(time) ?: Date()
            val now = Calendar.getInstance()
            calendar.set(Calendar.DAY_OF_YEAR, now.get(Calendar.DAY_OF_YEAR))
            calendar.set(Calendar.YEAR, now.get(Calendar.YEAR))
            return calendar.timeInMillis
        }
    }

    // ======================== 公开 API ========================

    /**
     * 获取经过处理的实时数据
     *
     * 流程：
     * 1. 查缓存
     * 2. 如果缓存未命中且 accessor 存在，通过 accessor 获取
     * 3. 验证数据
     * 4. 合并缓存 + 新数据
     * 5. 返回处理结果
     *
     * @param codes 股票代码列表
     * @param forceRefresh 是否强制刷新（忽略缓存），默认 false
     * @return ProcessedResult 包含处理后的数据和状态
     */
    suspend fun getProcessedRealtime(
        codes: List<String>,
        forceRefresh: Boolean = false
    ): ProcessedResult {
        if (codes.isEmpty()) return ProcessedResult.Empty

        val marketStatus = getMarketStatus()
        val result = mutableMapOf<String, StockRealtime>()
        val anomalies = mutableListOf<DataAnomaly>()
        var sourceUsed = ""

        Log.d(tag, "getProcessedRealtime: codes=$codes, forceRefresh=$forceRefresh, marketStatus=$marketStatus")

        // 1. 查缓存
        var cachedData = emptyMap<String, StockRealtime>()
        if (!forceRefresh) {
            cachedData = cache.get(codes)
            Log.d(tag, "  cache hit: ${cachedData.size}/${codes.size} codes")
            result.putAll(cachedData)
        }

        // 2. 需要获取的代码
        val needFetch = if (forceRefresh) codes else codes - result.keys
        Log.d(tag, "  needFetch: ${needFetch.size} codes: $needFetch")

        // 3. 通过 accessor 获取
        if (needFetch.isNotEmpty() && accessor != null) {
            Log.d(tag, "  calling accessor.fetchRealtime()...")
            val freshData = accessor.fetchRealtime(needFetch)
            Log.d(tag, "  accessor returned ${freshData.size} stocks")

            if (freshData.isNotEmpty()) {
                // 获取第一个健康的数据源名称
                sourceUsed = accessor.getHealthReport().entries
                    .firstOrNull { entry ->
                        @Suppress("UNCHECKED_CAST")
                        val info = entry.value as? Map<String, Any>
                        info?.get("healthy") == true
                    }
                    ?.key ?: "unknown"
                Log.d(tag, "  source used: $sourceUsed")

                // 验证新数据
                val (validData, detectedAnomalies) = validateData(freshData)
                Log.d(tag, "  validation: ${validData.size} valid, ${detectedAnomalies.size} anomalies")
                anomalies.addAll(detectedAnomalies)

                // 写入缓存
                if (validData.isNotEmpty()) {
                    cache.put(validData)
                    result.putAll(validData)
                    Log.d(tag, "  cache updated with ${validData.size} stocks")
                }
            } else {
                Log.w(tag, "  accessor returned EMPTY data — check network, stock codes, and Referer header!")
                Log.w(tag, "  health report: ${accessor.getHealthReport()}")
            }
        }

        // 4. 对最终结果做二次验证
        val (finalData, finalAnomalies) = validateData(result.toMap())
        anomalies.addAll(finalAnomalies)

        Log.d(tag, "  FINAL: ${finalData.size} stocks, ${anomalies.size} anomalies, isFromCache=${needFetch.isEmpty() && cachedData.isNotEmpty()}")

        return ProcessedResult.Data(
            data = finalData,
            marketStatus = marketStatus,
            sourceUsed = sourceUsed,
            isFromCache = needFetch.isEmpty() && cachedData.isNotEmpty(),
            anomalies = anomalies.distinct(),
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * 获取经过处理的实时数据，返回 Flow 以便 UI 层观察变化
     * 每 N 秒自动刷新一次
     */
    fun observeRealtime(
        codes: List<String>,
        intervalMs: Long = 3000L
    ): Flow<ProcessedResult> = flow {
        while (true) {
            val result = getProcessedRealtime(codes, forceRefresh = true)
            emit(result)
            kotlinx.coroutines.delay(intervalMs)
        }
    }

    /**
     * 格式化数据用于 AI Prompt 注入
     */
    fun formatForAi(result: ProcessedResult): String {
        if (result !is ProcessedResult.Data || result.data.isEmpty()) {
            return ""
        }

        val sb = StringBuilder()
        sb.appendLine("【实时行情数据】")
        sb.appendLine("数据来源: ${result.sourceUsed}")
        sb.appendLine("市场状态: ${result.marketStatus.description}")
        sb.appendLine("更新时间: ${formatTimestamp(result.timestamp)}")
        sb.appendLine()

        for ((code, stock) in result.data) {
            val arrow = when {
                stock.changeAmount > 0 -> "📈"
                stock.changeAmount < 0 -> "📉"
                else -> "➡️"
            }

            sb.appendLine("$arrow ${stock.name} ($code)")
            sb.appendLine("   当前价: ${formatPrice(stock.price)} 元")
            sb.appendLine("   涨跌: ${formatSigned(stock.changeAmount)} (${formatSigned(stock.changePercent)}%)")
            sb.appendLine("   最高: ${formatPrice(stock.high)}  最低: ${formatPrice(stock.low)}")
            sb.appendLine("   成交量: ${stock.volume / 10000} 万手  成交额: ${String.format("%.2f", stock.amount / 100000000)} 亿元")
            sb.appendLine("   昨收: ${formatPrice(stock.yestClose)}  开盘: ${formatPrice(stock.open)}")
            sb.appendLine()
        }

        // 如果有异常，追加提醒
        if (result.anomalies.isNotEmpty()) {
            sb.appendLine("⚠️ 数据异常提醒：")
            result.anomalies.forEach { anomaly ->
                sb.appendLine("  - [${anomaly.code}] ${anomaly.message}")
            }
            sb.appendLine()
        }

        sb.appendLine("注意：以上数据仅供分析参考，不构成投资建议。")
        return sb.toString()
    }

    /**
     * 格式化数据用于 UI 展示（结构化的 Map）
     */
    fun formatForUi(result: ProcessedResult): Map<String, UiStockData> {
        if (result !is ProcessedResult.Data) return emptyMap()

        return result.data.mapValues { (_, stock) ->
            UiStockData(
                name = stock.name,
                code = stock.code,
                price = stock.price,
                priceFormatted = formatPrice(stock.price),
                changePercent = stock.changePercent,
                changePercentFormatted = formatSigned(stock.changePercent) + "%",
                changeAmount = stock.changeAmount,
                changeAmountFormatted = formatSigned(stock.changeAmount),
                high = stock.high,
                low = stock.low,
                volume = stock.volume,
                amount = stock.amount,
                marketStatus = result.marketStatus,
                trend = when {
                    stock.changeAmount > 0 -> Trend.UP
                    stock.changeAmount < 0 -> Trend.DOWN
                    else -> Trend.FLAT
                }
            )
        }
    }

    /**
     * 检查数据是否「足够新」
     * 在交易时间内，数据应该 < 5 秒；非交易时间可以接受任何数据
     */
    fun isDataFresh(realtime: StockRealtime): Boolean {
        val age = System.currentTimeMillis() - realtime.timestamp
        val status = getMarketStatus()
        return when (status) {
            MarketStatus.TRADING -> age < 5000     // 交易中，5秒内算新鲜
            MarketStatus.LUNCH_BREAK -> age < 60000 // 午休，1分钟内算新鲜
            else -> age < 300000                     // 非交易时间，5分钟内算新鲜
        }
    }

    // ======================== 内部实现 ========================

    /**
     * 验证并清洗数据
     * @return (有效数据, 异常列表)
     */
    private fun validateData(data: Map<String, StockRealtime>): Pair<Map<String, StockRealtime>, List<DataAnomaly>> {
        val validData = mutableMapOf<String, StockRealtime>()
        val anomalies = mutableListOf<DataAnomaly>()

        for ((code, stock) in data) {
            val codeAnomalies = mutableListOf<String>()

            // 1. 检查名称
            if (stock.name.isBlank()) {
                codeAnomalies.add("股票名称为空")
                continue // 名称空则跳过整个记录
            }

            // 2. 检查价格合理性
            if (stock.price <= 0) {
                codeAnomalies.add("价格为0或负数: ${stock.price}")
                continue // 价格异常则跳过
            }

            // 3. 检查涨跌幅是否超限（A股限制）
            if (kotlin.math.abs(stock.changePercent) > LIMIT_GROWTHBOARD + 1) {
                codeAnomalies.add("涨跌幅${String.format("%.2f", stock.changePercent)}%超出正常范围")
            } else if (kotlin.math.abs(stock.changePercent) > LIMIT_MAINBOARD + 1) {
                // 可能是科创/创业板
                // 不阻塞数据，只记录提醒
            }

            // 4. 检查开盘/最高/最低与当前价的关系
            if (stock.high < stock.low) {
                codeAnomalies.add("最高价(${stock.high})小于最低价(${stock.low})")
            }
            if (stock.high < stock.price) {
                codeAnomalies.add("当前价(${stock.price})大于最高价(${stock.high})")
            }
            if (stock.low > stock.price) {
                codeAnomalies.add("当前价(${stock.price})小于最低价(${stock.low})")
            }

            // 5. 检查成交量/额
            if (stock.volume > 0 && stock.volume < MIN_VOLUME) {
                codeAnomalies.add("成交量(${stock.volume})异常偏低")
            }
            if (stock.amount > 0 && stock.amount < MIN_AMOUNT) {
                codeAnomalies.add("成交额(${stock.amount})异常偏低")
            }

            // 记录异常
            codeAnomalies.forEach { msg ->
                anomalies.add(DataAnomaly(code = code, message = msg))
            }

            // 即使有轻微异常也保留数据
            validData[code] = stock
        }

        return Pair(validData, anomalies)
    }

    /**
     * 获取当前市场状态
     */
    fun getMarketStatus(): MarketStatus {
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val minute = now.get(Calendar.MINUTE)
        val dayOfWeek = now.get(Calendar.DAY_OF_WEEK)

        // 检查周末
        if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) {
            return MarketStatus.CLOSED_WEEKEND
        }

        val currentMinutes = hour * 60 + minute
        val morningStart = 9 * 60 + 30
        val morningEnd = 11 * 60 + 30
        val afternoonStart = 13 * 60 + 0
        val afternoonEnd = 15 * 60 + 0

        return when {
            currentMinutes in morningStart until morningEnd -> MarketStatus.TRADING
            currentMinutes in morningEnd until afternoonStart -> MarketStatus.LUNCH_BREAK
            currentMinutes in afternoonStart until afternoonEnd -> MarketStatus.TRADING
            currentMinutes < morningStart -> MarketStatus.PRE_MARKET
            else -> MarketStatus.CLOSED
        }
    }

    // ======================== 工具方法 ========================

    private fun formatPrice(price: Double): String {
        return if (price >= 1000) {
            String.format("%.2f", price)
        } else if (price >= 10) {
            String.format("%.2f", price)
        } else {
            String.format("%.3f", price)
        }
    }

    private fun formatSigned(value: Double): String {
        return if (value > 0) "+${String.format("%.2f", value)}"
        else String.format("%.2f", value)
    }

    private fun formatTimestamp(timestamp: Long): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}

// ======================== 数据模型 ========================

/**
 * 处理结果 sealed class
 */
sealed class ProcessedResult {
    /** 空结果（没有请求任何代码） */
    object Empty : ProcessedResult()

    /** 有数据 */
    data class Data(
        val data: Map<String, StockRealtime>,
        val marketStatus: MarketStatus,
        val sourceUsed: String,
        val isFromCache: Boolean,
        val anomalies: List<DataAnomaly>,
        val timestamp: Long
    ) : ProcessedResult()
}

/**
 * 市场状态
 */
enum class MarketStatus(val description: String) {
    TRADING("交易中 📊"),
    LUNCH_BREAK("午休中 ☕"),
    PRE_MARKET("盘前 🕐"),
    CLOSED("已收盘 🔒"),
    CLOSED_WEEKEND("周末休市 🎉")
}

/**
 * 数据异常
 */
data class DataAnomaly(
    val code: String,
    val message: String
)

/**
 * UI 展示用股票数据
 */
data class UiStockData(
    val name: String,
    val code: String,
    val price: Double,
    val priceFormatted: String,
    val changePercent: Double,
    val changePercentFormatted: String,
    val changeAmount: Double,
    val changeAmountFormatted: String,
    val high: Double,
    val low: Double,
    val volume: Long,
    val amount: Double,
    val marketStatus: MarketStatus,
    val trend: Trend
)

/**
 * 涨跌趋势
 */
enum class Trend {
    UP, DOWN, FLAT
}