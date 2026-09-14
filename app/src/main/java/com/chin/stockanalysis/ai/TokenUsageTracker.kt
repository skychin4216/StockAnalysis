package com.chin.stockanalysis.ai

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * LLM Token 用量统计（Phase E — Token 用量统计）
 *
 * ### 数据来源
 * OpenAI 兼容流式响应末尾的 `usage` 段。注意：**服务端默认不回传**（流式模式下
 * 只在最后一个 chunk 带 usage，且多数实现要求请求体显式带
 * `stream_options.include_usage = true`），因此 [OpenAiCompatibleProvider.buildBody]
 * 里已补上该开关，否则这里永远统计不到东西。
 *
 * ### 统计口径
 * 按「模型 ID」累计，进程内共享（object）。报告只在 [report] 中输出一行，
 * 不改变任何业务逻辑。
 */
object TokenUsageTracker {

    private const val TAG = "TokenUsage"

    /** 单个模型的累计用量 */
    data class Stat(
        var calls: Int = 0,
        var promptTokens: Long = 0L,
        var completionTokens: Long = 0L,
        /** 命中的上下文缓存 token（`prompt_tokens_details.cached_tokens`） */
        var cachedTokens: Long = 0L
    ) {
        val totalTokens: Long get() = promptTokens + completionTokens
    }

    /**
     * 参考单价：`(模型名前缀, 输入元/百万token, 输出元/百万token)`，前缀匹配、越靠前优先。
     *
     * ⚠️ 豆包 2.0 Pro 官方是**阶梯定价**（按输入长度分档，本表取 32k 档），且此处为
     * 公开转述价而非控制台实时价，仅用于「量级感知」。要精确成本请按火山方舟控制台
     * 单价校准本表；未收录的模型只统计 token、不算钱。
     */
    private val PRICES: List<Triple<String, Double, Double>> = listOf(
        Triple("doubao-seed-2-0-pro", 3.2, 16.0)
    )

    private val stats = ConcurrentHashMap<String, Stat>()

    /** 记录一次调用的用量（usage 段缺失时不要调用本方法） */
    fun record(model: String, promptTokens: Long, completionTokens: Long, cachedTokens: Long = 0L) {
        if (model.isBlank()) return
        val s = stats.getOrPut(model) { Stat() }
        synchronized(s) {
            s.calls++
            s.promptTokens += promptTokens.coerceAtLeast(0L)
            s.completionTokens += completionTokens.coerceAtLeast(0L)
            s.cachedTokens += cachedTokens.coerceAtLeast(0L)
            Log.i(TAG, "用量 model=$model ↑$promptTokens ↓$completionTokens | 累计 ${s.totalTokens} tokens / ${s.calls} 次")
        }
    }

    private fun priceOf(model: String): Pair<Double, Double>? =
        PRICES.firstOrNull { model.startsWith(it.first) }?.let { it.second to it.third }

    /** 单模型估算成本（元）；无单价返回 null */
    fun costOf(model: String, stat: Stat): Double? {
        val (inPrice, outPrice) = priceOf(model) ?: return null
        return stat.promptTokens / 1_000_000.0 * inPrice +
                stat.completionTokens / 1_000_000.0 * outPrice
    }

    /** 汇总文本（附在分析报告末尾） */
    fun report(): String {
        val snap = stats.toMap()
        if (snap.isEmpty()) return "Token 用量：未记录到 usage（服务端未回传，检查 stream_options.include_usage）"

        var totalTokens = 0L
        var totalCost = 0.0
        var hasCost = false
        val parts = snap.map { (model, s) ->
            totalTokens += s.totalTokens
            val c = costOf(model, s)
            if (c != null) {
                totalCost += c
                hasCost = true
            }
            buildString {
                append(model).append(' ').append(s.calls).append("次 ")
                append("↑").append(s.promptTokens).append(" ↓").append(s.completionTokens)
                if (s.cachedTokens > 0) append("(缓存 ").append(s.cachedTokens).append(')')
                if (c != null) append(" ≈¥").append("%.4f".format(c))
            }
        }
        return buildString {
            append("Token 用量：").append(parts.joinToString("；"))
            append("；合计 ").append(totalTokens).append(" tokens")
            if (hasCost) append("，估算成本 ≈¥").append("%.4f".format(totalCost))
            else append("（单价未收录，仅统计 token）")
        }
    }

    /** 清空累计（新会话开始时调用） */
    fun reset() {
        stats.clear()
    }

    /** 只读快照 */
    fun snapshot(): Map<String, Stat> = stats.toMap()
}
