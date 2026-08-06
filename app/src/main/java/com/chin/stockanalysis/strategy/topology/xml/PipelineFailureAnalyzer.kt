package com.chin.stockanalysis.strategy.topology.xml

import com.chin.stockanalysis.strategy.topology.core.PipelineResult
import com.chin.stockanalysis.strategy.topology.core.StockFlowRecord

/**
 * ## Pipeline 失敗分析器
 *
 * 分析 DAG Pipeline 執行結果，自動定位導致輸出為 0 的「殺手節點」，
 * 並生成中文失敗原因描述，供 UI 展示和報告保存。
 *
 * ### 分析邏輯
 * 沿 Pipeline 節點流動鏈追蹤股票數量變化，找到第一個將輸出降為 0 的節點。
 * 如果沒有節點輸出為 0，則分析產出最少的瓶頸節點。
 */
object PipelineFailureAnalyzer {

    /**
     * 失敗分析結果
     */
    data class FailureAnalysis(
        /** 是否失敗（訂單數 = 0） */
        val isFailed: Boolean,
        /** 失敗節點 ID */
        val killNodeId: String = "",
        /** 失敗節點中文名稱 */
        val killNodeName: String = "",
        /** 中文失敗原因描述 */
        val reason: String = "",
        /** 完整的節點流動摘要（多行） */
        val flowSummary: String = "",
        /** 建議操作 */
        val suggestion: String = ""
    )

    /** 節點 ID → 中文名稱映射 */
    private val nodeNames = mapOf(
        "n_import" to "數據導入",
        "n_ctx" to "市場上下文",
        "n_pool" to "股票池",
        "n_ma_unified" to "大盤均線檢查",
        "n_guard" to "持倉風控",
        "n_base_guard" to "打底倉守門",
        "n_cand" to "候選池過濾",
        "n_merge" to "信號合併",
        "n_boost" to "板塊加權",
        "n_bounce" to "跌後反彈加分",
        "n_strict" to "六項嚴選檢查",
        "n_ancestral" to "大A祖訓",
        "n_inst_tips" to "機構線索加分",
        "n_smart" to "主力資金過濾",
        "n_ai" to "AI精選",
        "n_candle" to "K線形態偵測",
        "n_newsguard" to "新聞攔截",
        "n_news_str" to "新聞力度計算",
        "n_rot_pen" to "板塊輪動懲罰",
        "n_orders" to "買入訂單生成",
        "n_swap" to "騰龍換鳥",
        "n_merge_pos" to "持倉合併",
        "n_t1sell" to "T+1自動賣出",
        "n_fit" to "擬合計算",
        "n_intraday" to "盤中K線分析",
        "n_heat" to "熱度計算",
        "n_crossday" to "跨日聚合",
        "n_defensive" to "防守高息",
        "n_sector_pool" to "板塊精選池",
        "n_zipline" to "Zipline因子",
        "n_adaptive" to "自適應參數",
        "n_bg" to "後臺管理"
    )

    /**
     * 分析 Pipeline 執行結果，定位失敗原因。
     *
     * @param pipelineResults 所有 Pipeline 的執行結果
     * @param ordersCount 最終生成的訂單數
     * @return 失敗分析結果
     */
    fun analyze(
        pipelineResults: Map<String, PipelineResult>,
        ordersCount: Int
    ): FailureAnalysis {
        if (ordersCount > 0) {
            return FailureAnalysis(isFailed = false)
        }

        // 收集所有節點的 StockFlowRecord
        val allFlows = mutableListOf<StockFlowRecord>()
        val flowLines = mutableListOf<String>()

        for ((pipeName, pr) in pipelineResults) {
            for ((_, flow) in pr.stockFlowLogs) {
                allFlows.add(flow)
            }
            // 也從 stageResults 中提取信息
            for ((nodeId, stageResult) in pr.stageResults) {
                val name = nodeNames[nodeId] ?: nodeId
                val output = stageResult.output
                // 嘗試從節點輸出中提取數量信息
                val outputDesc = when (output) {
                    is com.chin.stockanalysis.strategy.topology.pipelines.OrderGenerationResult ->
                        "訂單${output.orders.size}筆${if (output.emptyTriggered) " [空倉]" else ""}"
                    is com.chin.stockanalysis.strategy.topology.nodes.StockEvaluationResult ->
                        "${output.passedStocks.size}只通過/${output.totalCount}只候選"
                    else -> null
                }
                if (outputDesc != null) {
                    flowLines.add("$name($nodeId): $outputDesc")
                }
            }
        }

        // 找到「殺手節點」— 第一個將輸出降為 0 的關鍵節點
        val killNode = findKillNode(allFlows)
        val killId = killNode?.nodeId ?: ""
        val killName = killNode?.let { nodeNames[it.nodeId] ?: it.nodeName } ?: ""
        val reason = generateReason(killNode, allFlows)
        val suggestion = generateSuggestion(killNode, allFlows)

        // 構建流動摘要
        val summary = buildString {
            appendLine("── 失敗分析 ──")
            appendLine("🔴 最終輸出: 0 筆訂單")
            if (killId.isNotBlank()) {
                appendLine("⛔ 殺手節點: $killName ($killId)")
                appendLine("📋 原因: $reason")
            }
            appendLine("── 節點流動 ──")
            for (flow in allFlows.sortedBy { it.nodeId }) {
                val name = nodeNames[flow.nodeId] ?: flow.nodeName
                val arrow = if (flow.outputCount == 0 && flow.inputCount > 0) "⛔" else "→"
                append("  $name: ${flow.inputCount}$arrow${flow.outputCount}")
                if (flow.filterCount > 0) append(" (過濾${flow.filterCount}: ${flow.filterReason})")
                appendLine()
            }
            if (flowLines.isNotEmpty()) {
                appendLine("── 詳細信息 ──")
                for (line in flowLines) appendLine("  $line")
            }
            if (suggestion.isNotBlank()) {
                appendLine("── 建議 ──")
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
     * 從節點流動中找到「殺手節點」— 關鍵鏈上第一個將輸出降為 0 的節點。
     *
     * 只關注選股主鏈上的關鍵節點，忽略輔助節點（如 K 線形態、後臺管理等）。
     */
    private fun findKillNode(flows: List<StockFlowRecord>): StockFlowRecord? {
        // 關鍵節點順序（選股主鏈）
        val criticalNodes = listOf(
            "n_pool", "n_cand", "n_merge", "n_boost", "n_bounce",
            "n_strict", "n_ancestral", "n_inst_tips", "n_smart",
            "n_newsguard", "n_ai", "n_orders"
        )

        // 按關鍵節點順序查找第一個輸出為 0 的節點
        for (nodeId in criticalNodes) {
            val flow = flows.find { it.nodeId == nodeId }
            if (flow != null && flow.outputCount == 0 && flow.inputCount > 0) {
                return flow
            }
        }

        // 如果沒有找到明確的殺手節點，找輸出最少的關鍵節點
        return flows
            .filter { it.nodeId in criticalNodes && it.inputCount > 0 }
            .minByOrNull { it.outputCount }
    }

    /**
     * 生成中文失敗原因描述。
     */
    private fun generateReason(
        killNode: StockFlowRecord?,
        flows: List<StockFlowRecord>
    ): String {
        if (killNode == null) {
            // 檢查是否策略本身就沒有信號
            val mergeFlow = flows.find { it.nodeId == "n_merge" }
            if (mergeFlow != null && mergeFlow.inputCount == 0) {
                return "所有策略均未產生任何信號，股票池為空"
            }
            return "Pipeline 執行異常，未能生成訂單"
        }

        return when (killNode.nodeId) {
            "n_strict" -> {
                val filterInfo = if (killNode.filterReason.isNotBlank()) "（${killNode.filterReason}）" else ""
                "${killNode.inputCount} 只候選股全部未通過六項嚴選檢查$filterInfo。" +
                        "在 BEARISH 市場環境下，策略閾值自動提高，選股條件更加嚴格。"
            }
            "n_smart" -> {
                "${killNode.inputCount} 只股票全部被主力資金過濾攔截。" +
                        "可能原因：整體市場資金流出嚴重，候選股均為主力撤退標的。"
            }
            "n_newsguard" -> {
                "${killNode.inputCount} 只股票全部被新聞攔截。" +
                        "可能原因：重大利空新聞覆蓋了所有候選股。"
            }
            "n_ai" -> {
                "${killNode.inputCount} 只股票全部被 AI 精選淘汰。" +
                        "AI 綜合評估後認為當前沒有足夠質量的買入標的。"
            }
            "n_orders" -> {
                "訂單生成節點輸入為 0，上游節點未傳遞有效候選股。" +
                        "可能原因：前置過濾鏈條過嚴，層層淘汰後無股可買。"
            }
            "n_bounce" -> {
                "跌後反彈條件不滿足，跳過處理。" +
                        "大盤或候選股尚未滿足「三日不新低」條件。"
            }
            "n_merge" -> {
                "信號合併後無有效候選。策略信號可能全部過期或被去重。"
            }
            "n_pool", "n_cand" -> {
                "股票池/候選池為空。可能原因：數據不足或市場環境極端惡劣。"
            }
            else -> {
                val name = nodeNames[killNode.nodeId] ?: killNode.nodeName
                "${name}節點將 ${killNode.inputCount} 只候選全部過濾（${killNode.filterReason}）"
            }
        }
    }

    /**
     * 生成操作建議。
     */
    private fun generateSuggestion(
        killNode: StockFlowRecord?,
        flows: List<StockFlowRecord>
    ): String {
        if (killNode == null) return "建議檢查策略配置和數據完整性"

        return when (killNode.nodeId) {
            "n_strict" -> {
                "建議：1) 檢查 minPassCount 配置是否過高；" +
                        "2) BEARISH 市場可考慮適當放寬嚴選閾值；" +
                        "3) 空倉觀望也是策略，等待市場企穩再入場。"
            }
            "n_smart" -> {
                "建議：1) 檢查主力資金評分閾值（minScore）是否過高；" +
                        "2) 市場整體資金流出時，可適當降低主力過濾門檻。"
            }
            "n_newsguard" -> {
                "建議：1) 檢查新聞攔截閾值是否過嚴；" +
                        "2) 重大利空期間建議手動暫停交易。"
            }
            "n_ai" -> {
                "建議：AI 判斷無優質標的時，尊重 AI 決策，空倉等待更好的機會。"
            }
            "n_orders" -> {
                "建議：上游過濾鏈過嚴，可考慮：1) 降低各節點閾值；" +
                        "2) 增加策略數量以擴大信號源；" +
                        "3) 空倉觀望。"
            }
            else -> "建議：檢查 ${nodeNames[killNode.nodeId] ?: killNode.nodeName} 節點的配置參數。"
        }
    }
}
