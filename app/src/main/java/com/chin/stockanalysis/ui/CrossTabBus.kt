package com.chin.stockanalysis.ui

import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.models.StrategySignal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ## 跨Tab数据总线
 *
 * 参考业界 MVI 架构（如 Airbnb MvRx / Redux）：
 *   - 各Tab通过 StateFlow 发布数据
 *   - 对话Tab通过 collect 接收，AI上下文自动注入
 *
 * ### 使用方式
 * ```kotlin
 * // 发布端（策略Tab / 自动量化Tab）
 * CrossTabBus.postStrategyResults(listOf(screeningResult))
 * CrossTabBus.postStockContext(mapOf("sh600519" to "贵州茅台"))
 *
 * // 接收端（对话Tab）
 * CrossTabBus.strategyResults.collect { results ->
 *     // 注入到 AI 上下文
 * }
 * ```
 */
object CrossTabBus {

    // ═══════════════════════════════════════
    // 数据频道
    // ═══════════════════════════════════════

    /** 策略选股结果（量化选股Tab → 对话Tab） */
    private val _strategyResults = MutableStateFlow<List<ScreeningResult>>(emptyList())
    val strategyResults: StateFlow<List<ScreeningResult>> = _strategyResults.asStateFlow()

    /** AI精选Top3（自动量化Tab → 对话Tab） */
    private val _aiTopPicks = MutableStateFlow<List<com.chin.stockanalysis.strategy.predict.AIPredictionEngine.AIPick>>(emptyList())
    val aiTopPicks: StateFlow<List<com.chin.stockanalysis.strategy.predict.AIPredictionEngine.AIPick>> = _aiTopPicks.asStateFlow()

    /** 合并池（自动量化Tab → 对话Tab） */
    private val _mergedPool = MutableStateFlow<Map<String, List<Pair<String, Int>>>>(emptyMap())
    val mergedPool: StateFlow<Map<String, List<Pair<String, Int>>>> = _mergedPool.asStateFlow()

    /** 股票上下文（任意Tab → 对话Tab） */
    private val _stockContext = MutableStateFlow<Map<String, String>>(emptyMap())
    val stockContext: StateFlow<Map<String, String>> = _stockContext.asStateFlow()

    /** 对话指令（对话Tab → 任意Tab） */
    private val _command = MutableStateFlow<CrossTabCommand?>(null)
    val command: StateFlow<CrossTabCommand?> = _command.asStateFlow()

    /** 智能体建议（智能体Tab → 对话Tab） */
    private val _agentSuggestion = MutableStateFlow<String?>(null)
    val agentSuggestion: StateFlow<String?> = _agentSuggestion.asStateFlow()

    // ═══════════════════════════════════════
    // 发布方法
    // ═══════════════════════════════════════

    fun postStrategyResults(results: List<ScreeningResult>) { _strategyResults.value = results }
    fun postAiTopPicks(picks: List<com.chin.stockanalysis.strategy.predict.AIPredictionEngine.AIPick>) { _aiTopPicks.value = picks }
    fun postMergedPool(pool: Map<String, List<Pair<String, Int>>>) { _mergedPool.value = pool }
    fun postStockContext(ctx: Map<String, String>) { _stockContext.value = ctx }
    fun postCommand(cmd: CrossTabCommand) { _command.value = cmd }
    fun postAgentSuggestion(text: String?) { _agentSuggestion.value = text }

    /** 消费指令（一次性） */
    fun consumeCommand(): CrossTabCommand? { val cmd = _command.value; _command.value = null; return cmd }

    // ═══════════════════════════════════════
    // 序列化（AI上下文构建用）
    // ═══════════════════════════════════════

    fun buildAiContext(): String = buildString {
        val results = _strategyResults.value
        if (results.isNotEmpty()) {
            appendLine("=== 策略选股结果 ===")
            for (r in results) {
                appendLine("${r.strategyName}: ${r.hitCount}只命中")
                for (s in r.signals.distinctBy { it.stockCode }.take(5)) {
                    appendLine("  ${s.stockName}(${s.stockCode.takeLast(6)}) 强度:${s.strength}% ¥${"%.2f".format(s.currentPrice)} ${if(s.changePercent>=0)"+" else ""}${"%.2f".format(s.changePercent)}%")
                }
            }
        }
        val picks = _aiTopPicks.value
        if (picks.isNotEmpty()) {
            appendLine(); appendLine("=== AI精选 Top3 ===")
            for (p in picks) appendLine("#${p.rank} ${p.stockName}(${p.stockCode.takeLast(6)}) 评分:${p.compositeScore} ${p.actionSuggestion}")
        }
        val ctx = _stockContext.value
        if (ctx.isNotEmpty()) {
            appendLine(); appendLine("=== 用户上下文 ===")
            ctx.forEach { (k, v) -> appendLine("$k: $v") }
        }
    }
}

data class CrossTabCommand(
    val action: String,           // "BUY", "SELL", "SCREEN", "RUN_PIPELINE"
    val stockCode: String = "",
    val stockName: String = "",
    val extraParams: Map<String, String> = emptyMap()
)