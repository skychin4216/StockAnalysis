package com.chin.stockanalysis.strategy.topology.xml

import com.chin.stockanalysis.strategy.topology.core.PipelineResult
import com.chin.stockanalysis.strategy.topology.core.StockFlowRecord

/**
 * ## Pipeline 失败分析器
 *
 * 分析 DAG Pipeline 执行结果，自动定位导致输出为 0 的「杀手节点」，
 * 并生成中文失败原因描述，供 UI 展示和报告保存。
 *
 * ### 分析逻辑
 * 沿 Pipeline 节点流动链追踪股票数量变化，找到第一个将输出降为 0 的节点。
 * 如果没有节点输出为 0，则分析产出最少的瓶颈节点。
 */
object PipelineFailureAnalyzer {

    /**
     * 失败分析结果
     */
    data class FailureAnalysis(
        /** 是否失败（订单数 = 0） */
        val isFailed: Boolean,
        /** 失败节点 ID */
        val killNodeId: String = "",
        /** 失败节点中文名称 */
        val killNodeName: String = "",
        /** 中文失败原因描述 */
        val reason: String = "",
        /** 完整的节点流动摘要（多行） */
        val flowSummary: String = "",
        /** 建议操作 */
        val suggestion: String = ""
    )

    /** 节点 ID → 中文名称映射 */
    private val nodeNames = mapOf(
        "n_import" to "数据导入",
        "n_ctx" to "市场上下文",
        "n_pool" to "股票池",
        "n_ma_unified" to "大盘均线检查",
        "n_guard" to "持仓风控",
        "n_base_guard" to "打底仓守门",
        "n_cand" to "候选池过滤",
        "n_merge" to "信号合并",
        "n_boost" to "板块加权",
        "n_bounce" to "跌后反弹加分",
        "n_strict" to "六项严选检查",
        "n_ancestral" to "大A祖训",
        "n_inst_tips" to "机构线索加分",
        "n_smart" to "主力资金过滤",
        "n_ai" to "AI精选",
        "n_candle" to "K线形态侦测",
        "n_newsguard" to "新闻拦截",
        "n_news_str" to "新闻力度计算",
        "n_rot_pen" to "板块轮动惩罚",
        "n_orders" to "买入订单生成",
        "n_swap" to "腾龙换鸟",
        "n_merge_pos" to "持仓合并",
        "n_t1sell" to "T+1自动卖出",
        "n_fit" to "拟合计算",
        "n_intraday" to "盘中K线分析",
        "n_heat" to "热度计算",
        "n_crossday" to "跨日聚合",
        "n_defensive" to "防守高息",
        "n_sector_pool" to "板块精选池",
        "n_zipline" to "Zipline因子",
        "n_adaptive" to "自适应参数",
        "n_bg" to "后台管理"
    )

    /**
     * 分析 Pipeline 执行结果，定位失败原因。
     *
     * @param pipelineResults 所有 Pipeline 的执行结果
     * @param ordersCount 最终生成的订单数
     * @return 失败分析结果
     */
    fun analyze(
        pipelineResults: Map<String, PipelineResult>,
        ordersCount: Int
    ): FailureAnalysis {
        if (ordersCount > 0) {
            return FailureAnalysis(isFailed = false)
        }

        // 收集所有节点的 StockFlowRecord
        val allFlows = mutableListOf<StockFlowRecord>()
        val flowLines = mutableListOf<String>()

        for ((pipeName, pr) in pipelineResults) {
            for ((_, flow) in pr.stockFlowLogs) {
                allFlows.add(flow)
            }
            // 也从 stageResults 中提取信息
            for ((nodeId, stageResult) in pr.stageResults) {
                val name = nodeNames[nodeId] ?: nodeId
                val output = stageResult.output
                // 尝试从节点输出中提取数量信息
                val outputDesc = when (output) {
                    is com.chin.stockanalysis.strategy.topology.pipelines.OrderGenerationResult ->
                        "订单${output.orders.size}笔${if (output.emptyTriggered) " [空仓]" else ""}"
                    is com.chin.stockanalysis.strategy.topology.nodes.StockEvaluationResult ->
                        "${output.passedStocks.size}只通过/${output.totalCount}只候选"
                    else -> null
                }
                if (outputDesc != null) {
                    flowLines.add("$name($nodeId): $outputDesc")
                }
            }
        }

        // 找到「杀手节点」— 第一个将输出降为 0 的关键节点
        val killNode = findKillNode(allFlows)
        val killId = killNode?.nodeId ?: ""
        val killName = killNode?.let { nodeNames[it.nodeId] ?: it.nodeName } ?: ""
        val reason = generateReason(killNode, allFlows)
        val suggestion = generateSuggestion(killNode, allFlows)

        // 构建流动摘要
        val summary = buildString {
            appendLine("── 失败分析 ──")
            appendLine("🔴 最终输出: 0 笔订单")
            if (killId.isNotBlank()) {
                appendLine("⛔ 杀手节点: $killName ($killId)")
                appendLine("📋 原因: $reason")
            }
            appendLine("── 节点流动 ──")
            for (flow in allFlows.sortedBy { it.nodeId }) {
                val name = nodeNames[flow.nodeId] ?: flow.nodeName
                val arrow = if (flow.outputCount == 0 && flow.inputCount > 0) "⛔" else "→"
                append("  $name: ${flow.inputCount}$arrow${flow.outputCount}")
                if (flow.filterCount > 0) append(" (过滤${flow.filterCount}: ${flow.filterReason})")
                appendLine()
            }
            if (flowLines.isNotEmpty()) {
                appendLine("── 详细信息 ──")
                for (line in flowLines) appendLine("  $line")
            }
            if (suggestion.isNotBlank()) {
                appendLine("── 建议 ──")
                appendLine("  $suggestion")
            }
        }

        return FailureAnalysis(
            isFailed = true,
            killNodeId = killId,
            killNodeName = killName,
            reason = reason,
            flowSummary = summary.trimEnd(),
            suggestion = suggestion
        )
    }

    /**
     * 从节点流动中找到「杀手节点」— 关键链上第一个将输出降为 0 的节点。
     *
     * 只关注选股主链上的关键节点，忽略辅助节点（如 K 线形态、后台管理等）。
     */
    private fun findKillNode(flows: List<StockFlowRecord>): StockFlowRecord? {
        // 关键节点顺序（选股主链）
        val criticalNodes = listOf(
            "n_pool", "n_cand", "n_merge", "n_boost", "n_bounce",
            "n_strict", "n_ancestral", "n_inst_tips", "n_smart",
            "n_newsguard", "n_ai", "n_orders"
        )

        // 按关键节点顺序查找第一个输出为 0 的节点
        for (nodeId in criticalNodes) {
            val flow = flows.find { it.nodeId == nodeId }
            if (flow != null && flow.outputCount == 0 && flow.inputCount > 0) {
                return flow
            }
        }

        // 如果没有找到明确的杀手节点，找输出最少的关键节点
        return flows
            .filter { it.nodeId in criticalNodes && it.inputCount > 0 }
            .minByOrNull { it.outputCount }
    }

    /**
     * 生成中文失败原因描述。
     */
    private fun generateReason(
        killNode: StockFlowRecord?,
        flows: List<StockFlowRecord>
    ): String {
        if (killNode == null) {
            // 检查是否策略本身就没有信号
            val mergeFlow = flows.find { it.nodeId == "n_merge" }
            if (mergeFlow != null && mergeFlow.inputCount == 0) {
                return "所有策略均未产生任何信号，股票池为空"
            }
            return "Pipeline 执行异常，未能生成订单"
        }

        return when (killNode.nodeId) {
            "n_strict" -> {
                val filterInfo = if (killNode.filterReason.isNotBlank()) "（${killNode.filterReason}）" else ""
                "${killNode.inputCount} 只候选股全部未通过六项严选检查$filterInfo。" +
                        "在 BEARISH 市场环境下，策略阈值自动提高，选股条件更加严格。"
            }
            "n_smart" -> {
                "${killNode.inputCount} 只股票全部被主力资金过滤拦截。" +
                        "可能原因：整体市场资金流出严重，候选股均为主力撤退标的。"
            }
            "n_newsguard" -> {
                "${killNode.inputCount} 只股票全部被新闻拦截。" +
                        "可能原因：重大利空新闻覆盖了所有候选股。"
            }
            "n_ai" -> {
                "${killNode.inputCount} 只股票全部被 AI 精选淘汰。" +
                        "AI 综合评估后认为当前没有足够质量的买入标的。"
            }
            "n_orders" -> {
                "订单生成节点输入为 0，上游节点未传递有效候选股。" +
                        "可能原因：前置过滤链条过严，层层淘汰后无股可买。"
            }
            "n_bounce" -> {
                "跌后反弹条件不满足，跳过处理。" +
                        "大盘或候选股尚未满足「三日不新低」条件。"
            }
            "n_merge" -> {
                "信号合并后无有效候选。策略信号可能全部过期或被去重。"
            }
            "n_pool", "n_cand" -> {
                "股票池/候选池为空。可能原因：数据不足或市场环境极端恶劣。"
            }
            else -> {
                val name = nodeNames[killNode.nodeId] ?: killNode.nodeName
                "${name}节点将 ${killNode.inputCount} 只候选全部过滤（${killNode.filterReason}）"
            }
        }
    }

    /**
     * 生成操作建议。
     */
    private fun generateSuggestion(
        killNode: StockFlowRecord?,
        flows: List<StockFlowRecord>
    ): String {
        if (killNode == null) return "建议检查策略配置和数据完整性"

        return when (killNode.nodeId) {
            "n_strict" -> {
                "建议：1) 检查 minPassCount 配置是否过高；" +
                        "2) BEARISH 市场可考虑适当放宽严选阈值；" +
                        "3) 空仓观望也是策略，等待市场企稳再入场。"
            }
            "n_smart" -> {
                "建议：1) 检查主力资金评分阈值（minScore）是否过高；" +
                        "2) 市场整体资金流出时，可适当降低主力过滤门槛。"
            }
            "n_newsguard" -> {
                "建议：1) 检查新闻拦截阈值是否过严；" +
                        "2) 重大利空期间建议手动暂停交易。"
            }
            "n_ai" -> {
                "建议：AI 判断无优质标的时，尊重 AI 决策，空仓等待更好的机会。"
            }
            "n_orders" -> {
                "建议：上游过滤链过严，可考虑：1) 降低各节点阈值；" +
                        "2) 增加策略数量以扩大信号源；" +
                        "3) 空仓观望。"
            }
            else -> "建议：检查 ${nodeNames[killNode.nodeId] ?: killNode.nodeName} 节点的配置参数。"
        }
    }
}
