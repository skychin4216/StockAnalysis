package com.chin.stockanalysis.strategy.topology.ui

import android.content.Context
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.chin.stockanalysis.strategy.topology.core.DagEdge
import com.chin.stockanalysis.strategy.topology.core.DagNode
import com.chin.stockanalysis.strategy.topology.core.DagPipeline
import com.chin.stockanalysis.strategy.topology.xml.PipelineXmlParser

/**
 * ## Pipeline 竖屏分层流程图公共工具
 *
 * 参考 57c2215 新版 UI 的显示方式：动态读取 `assets/usecases/{useCaseId}_pipeline.xml`，
 * Kahn 拓扑分层后用 WebView + HTML 弹窗展示（竖屏友好）：
 * - 每一层左侧显示分类名（L0 数据 / L1 环境 / L2 股票池 …）
 * - 层与层之间显示向下的箭头（↓），表达数据流向（从上往下）
 * - 每个节点卡片显示名称 + 模块小字
 *
 * 供 [QuantFragmentBase.openPipelineEditor] 与 [TopologyEditorActivity] 复用：
 * 当点击某个 Usecase 或 Pipeline 时，调用 [showFlowChart] 加载并按分层显示；
 * 弹窗保留「打开编辑器」入口进入画布编辑。
 */
object PipelineFlowChart {

    /**
     * 显示指定 Pipeline 的分层流程图弹窗。
     *
     * @param context 上下文
     * @param useCaseId 周期/场景 ID（用于标题与缺省 asset 路径）
     * @param assetPath 相对 assets 的 XML 路径，缺省为 `usecases/{useCaseId}_pipeline.xml`
     * @param onOpenEditor 用户点击「打开编辑器」时的回调（进入画布编辑器）；为 null 时不显示该按钮。
     *                     若 DAG 解析失败（V1 格式 / 文件不存在），会先提示并直接调用该回调。
     */
    fun showFlowChart(
        context: Context,
        useCaseId: String,
        assetPath: String = "usecases/${useCaseId}_pipeline.xml",
        onOpenEditor: (() -> Unit)? = null
    ) {
        val dag = PipelineXmlParser.loadDagPipelineFromAssets(context, assetPath)
        if (dag == null) {
            Toast.makeText(context, "未找到 DAG Pipeline 配置: $assetPath", Toast.LENGTH_SHORT).show()
            onOpenEditor?.invoke()
            return
        }
        val html = buildPipelineFlowHtml(dag, useCaseId)
        val webView = WebView(context).apply {
            setBackgroundColor(0xFF1A1D29.toInt())
            settings.javaScriptEnabled = true
            loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            // 多层长图：占屏幕高度 72% 左右，内容超长时在弹窗内滚动
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                (context.resources.displayMetrics.heightPixels * 0.72f).toInt()
            )
        }
        val builder = AlertDialog.Builder(context)
            .setTitle("📊 ${useCaseId.uppercase()} 完整流程")
            .setView(webView)
            .setNegativeButton("关闭", null)
        if (onOpenEditor != null) {
            builder.setPositiveButton("打开编辑器") { _, _ -> onOpenEditor() }
        }
        builder.show()
    }

    /**
     * Kahn 拓扑分层：返回每层节点列表。
     *
     * 注意：`DagNode.dependencies` 在解析时未填充，因此必须基于 `dag.edges`
     * 计算入度。忽略引用了不存在节点的边；若存在环（如互依赖），将剩余节点
     * 整体作为最后一层兜底，保证所有节点都被展示。
     */
    fun kahnLayers(dag: DagPipeline): List<List<DagNode>> {
        val nodeIds = dag.nodes.map { it.nodeId }.toSet()
        val inDegree = dag.nodes.associate { it.nodeId to 0 }.toMutableMap()
        val adjacency = mutableMapOf<String, MutableList<DagEdge>>()
        dag.edges.forEach { e ->
            if (e.sourceNodeId in nodeIds && e.targetNodeId in nodeIds) {
                adjacency.getOrPut(e.sourceNodeId) { mutableListOf() }.add(e)
                inDegree[e.targetNodeId] = (inDegree[e.targetNodeId] ?: 0) + 1
            }
        }
        val remaining = nodeIds.toMutableSet()
        val layers = mutableListOf<List<DagNode>>()
        val nodeMap = dag.nodes.associateBy { it.nodeId }
        while (remaining.isNotEmpty()) {
            val ready = remaining.filter { inDegree[it] == 0 }
            if (ready.isEmpty()) {
                // 环保护：把剩余节点按原顺序并入最后一层，避免无限循环
                layers.add(remaining.mapNotNull { nodeMap[it] })
                break
            }
            layers.add(ready.mapNotNull { nodeMap[it] })
            ready.forEach { id ->
                remaining.remove(id)
                adjacency[id]?.forEach { e ->
                    inDegree[e.targetNodeId] = (inDegree[e.targetNodeId] ?: 1) - 1
                }
            }
        }
        return layers
    }

    /** 节点 → CSS 分类（数据/环境/股票池/策略/AI/订单）。 */
    fun nodeCssClass(dagNode: DagNode): String {
        val m = dagNode.node.nodeId.lowercase()
        return when {
            m.startsWith("data") || m == "t_holdings_load" -> "n-data"
            m in setOf(
                "market_context", "bg_manager", "adaptive_params", "market_ma_unified",
                "a_market_analysis", "multi_period_hot", "cross_day_aggregation", "crosstab_publish"
            ) -> "n-env"
            m in setOf(
                "stock_pool", "sector_stock_pool", "candidate_pool", "holding_guard",
                "intraday_analysis", "holding_diagnostic"
            ) -> "n-pool"
            m.contains("ai_") || m in setOf(
                "smart_money_filter", "candle_pattern", "news_guard",
                "holding_prediction", "real_holding_eval"
            ) -> "n-ai"
            m in setOf(
                "generate_orders", "swap_weak", "position_merge", "t1_auto_sell",
                "t_signal_synthesize", "t_trade_import", "t_trade_eval",
                "t_inst_intent", "t_recommend_save"
            ) -> "n-order"
            else -> "n-strat"
        }
    }

    /** 层标签分类名：统计该层出现最多的节点分类。 */
    private fun layerCategory(layer: List<DagNode>): String {
        val counts = HashMap<String, Int>()
        layer.forEach { n ->
            val c = nodeCssClass(n)
            counts[c] = (counts[c] ?: 0) + 1
        }
        val top = counts.maxByOrNull { it.value }?.key ?: "n-strat"
        return when (top) {
            "n-data" -> "数据"
            "n-env" -> "环境"
            "n-pool" -> "股票池"
            "n-ai" -> "AI 过滤"
            "n-order" -> "交易操作"
            else -> "策略"
        }
    }

    /** 生成分层流程图 HTML（参考 57c2215 新版 UI：层标签 + 层间箭头 + 节点卡片）。 */
    fun buildPipelineFlowHtml(dag: DagPipeline, useCaseId: String): String {
        val layers = kahnLayers(dag)
        // 层间边的统计：source 在上层、target 在下层
        val edgeBetween = HashMap<Pair<Int, Int>, Int>()
        dag.edges.forEach { e: DagEdge ->
            val si = layers.indexOfFirst { it.any { n -> n.nodeId == e.sourceNodeId } }
            val ti = layers.indexOfFirst { it.any { n -> n.nodeId == e.targetNodeId } }
            if (si >= 0 && ti >= 0 && si < ti) {
                val k = si to ti
                edgeBetween[k] = (edgeBetween[k] ?: 0) + 1
            }
        }

        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
        sb.append("<style>")
        sb.append("body{background:#1A1D29;color:#E6EDF7;font-family:sans-serif;margin:0;padding:12px;}")
        sb.append("h2{font-size:15px;text-align:center;margin:4px 0 12px;color:#7EB6FF;}")
        sb.append(".layer{display:flex;align-items:flex-start;margin-bottom:2px;}")
        sb.append(".lbl{min-width:58px;font-size:11px;color:#8FA3C0;padding-top:6px;font-weight:600;flex-shrink:0;}")
        sb.append(".nodes{flex:1;display:flex;flex-wrap:wrap;gap:6px;}")
        sb.append(".node{padding:5px 9px;border-radius:8px;font-size:11px;font-weight:600;color:#fff;")
        sb.append("border:1px solid rgba(255,255,255,.22);min-width:86px;text-align:center;line-height:1.25;}")
        sb.append(".node .sub{display:block;font-size:9px;font-weight:400;color:rgba(255,255,255,.72);margin-top:2px;word-break:break-all;}")
        sb.append(".arrow{display:flex;align-items:center;gap:10px;margin:0 0 2px 58px;padding-left:8px;color:#4E7DB3;font-size:13px;line-height:1.2;}")
        sb.append(".n-data{background:#2D6A4F;}.n-env{background:#1B4965;}.n-pool{background:#6C4AB6;}")
        sb.append(".n-strat{background:#B3541E;}.n-ai{background:#9B2226;}.n-order{background:#0B525B;}")
        sb.append(".note{font-size:10px;color:#8FA3C0;margin-top:8px;line-height:1.6;}")
        sb.append("</style></head><body>")
        sb.append("<h2>${escapeHtml(dag.name)}（$useCaseId）</h2>")
        if (dag.description.isNotBlank()) sb.append("<div class=\"note\">${escapeHtml(dag.description)}</div>")

        layers.forEachIndexed { i, layer ->
            sb.append("<div class=\"layer\"><div class=\"lbl\">L$i ${layerCategory(layer)}</div><div class=\"nodes\">")
            layer.forEach { n ->
                val cls = nodeCssClass(n)
                val tip = n.nodeName.ifBlank { n.nodeId }
                val sub = n.node.nodeId.ifBlank { n.nodeId }
                sb.append("<div class=\"node $cls\">${escapeHtml(tip)}<span class=\"sub\">${escapeHtml(sub)}</span></div>")
            }
            sb.append("</div></div>")
            // 层间箭头：统计从本层指向更下层的边数，决定向下箭头数量
            if (i < layers.size - 1) {
                var down = 0
                for (j in (i + 1) until layers.size) down += edgeBetween[i to j] ?: 0
                val arrowCount = down.coerceIn(1, 6)
                sb.append("<div class=\"arrow\">${"↓ ".repeat(arrowCount)}</div>")
            }
        }

        sb.append("<div class=\"note\">颜色分类：数据 / 环境 / 股票池 / 策略 / AI 过滤 / 交易操作；箭头表示上层向下层的数据流向。</div>")
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")
}
