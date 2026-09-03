package com.chin.stockanalysis.strategy.topology.core

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.strategy.topology.xml.DagTradeExecutor
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ## Node 使用率 / 拦截率 持久化累计统计
 *
 * 每次 DAG 交易执行后由 [DagTradeExecutor] 调用 [record]，把每个节点当次运行的
 * 输入/输出/拦截数量累计写入 `<filesDir>/quant_node_stats.json`，并同步生成
 * `<filesDir>/quant_node_stats_summary.txt` 报告。
 *
 * 统计目的：
 * - 找出「多次运行但几乎从不拦截 / 输出=输入（空转）」的节点 → 说明该 node
 *   在当前 pipeline 中可能是冗余的，可考虑移除或并入 common pipeline；
 * - 找出「拦截率极高但影响微弱」的节点 → 评估是否需要放宽参数。
 *
 * 维度说明：以 `nodeId + module(nodeName)` 为键跨 usecase 累计（同一周期不同环境
 * pipeline 结构一致，合并统计更有意义）；当次运行上游为空(input==0)不计入 runs，
 * 避免污染使用率。
 */
object NodeUsageStats {

    private const val TAG = "NodeUsageStats"
    private const val FILE_NAME = "quant_node_stats.json"
    private const val SUMMARY_NAME = "quant_node_stats_summary.txt"

    private data class Agg(
        var runs: Int = 0,
        var totalInput: Long = 0,
        var totalOutput: Long = 0,
        var totalFilter: Long = 0,
        var lastDate: String = "",
        val usecases: LinkedHashSet<String> = LinkedHashSet()
    )

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun statsFile(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun summaryFile(context: Context): File = File(context.filesDir, SUMMARY_NAME)

    /**
     * 记录一次执行的所有节点流动明细。
     * 幂等：同一节点 input==0（上游为空）不计 runs。
     */
    fun record(context: Context, useCaseId: String, details: List<DagTradeExecutor.NodeFlowDetail>) {
        if (details.isEmpty()) return
        try {
            val root = load(context)
            val map = root.optJSONObject("nodes") ?: JSONObject()
            val today = dateFmt.format(Date())
            for (d in details) {
                if (d.inputCount <= 0) continue // 上游为空/未执行到，不计使用率
                val key = keyOf(d.nodeId, d.nodeName)
                val agg = map.optJSONObject(key) ?: JSONObject()
                val runs = agg.optInt("runs", 0) + 1
                agg.put("runs", runs)
                agg.put("totalInput", agg.optLong("totalInput", 0) + d.inputCount)
                agg.put("totalOutput", agg.optLong("totalOutput", 0) + d.outputCount)
                agg.put("totalFilter", agg.optLong("totalFilter", 0) + d.filterCount.coerceAtLeast(0))
                agg.put("lastDate", today)
                val uc = (agg.optJSONArray("usecases") ?: JSONObject.NULL)
                val set = HashSet<String>()
                if (uc is org.json.JSONArray) {
                    for (i in 0 until uc.length()) set.add(uc.getString(i))
                }
                set.add(useCaseId)
                agg.put("usecases", org.json.JSONArray(set.sorted()))
                map.put(key, agg)
            }
            root.put("nodes", map)
            root.put("updatedAt", today)
            statsFile(context).writeText(root.toString(2), Charsets.UTF_8)
            writeSummary(context, map)
            Log.i(TAG, "累计 ${map.length()} 个节点统计 -> $FILE_NAME")
        } catch (e: Exception) {
            Log.w(TAG, "记录 node 统计失败: ${e.message}")
        }
    }

    /** 生成可读报告，标记空转/从不拦截节点。 */
    private fun writeSummary(context: Context, nodes: JSONObject) {
        val sb = StringBuilder()
        sb.appendLine("Node 使用率统计 (nodeId | 运行次数 | 平均输入→输出 | 平均拦截数 | 拦截率 | 最近日期 | 周期)")
        sb.appendLine("=".repeat(120))
        val entries = mutableListOf<JSONObject>()
        nodes.keys().forEach { entries.add(nodes.optJSONObject(it)) }
        entries.sortByDescending { it.optInt("runs", 0) }
        for (e in entries) {
            val runs = e.optInt("runs", 0)
            if (runs == 0) continue
            val totalIn = e.optLong("totalInput", 0)
            val totalOut = e.optLong("totalOutput", 0)
            val totalFilter = e.optLong("totalFilter", 0)
            val avgIn = totalIn.toDouble() / runs
            val avgOut = totalOut.toDouble() / runs
            val avgFilter = totalFilter.toDouble() / runs
            val rate = if (totalIn > 0) totalFilter.toDouble() / totalIn * 100 else 0.0
            val flags = mutableListOf<String>()
            if (runs >= 3 && totalFilter == 0L) flags.add("⚠️从未拦截(空转)")
            if (runs >= 3 && totalIn == totalOut) flags.add("⚠️输出=输入(疑似冗余)")
            if (runs >= 3 && rate >= 95.0) flags.add("⛔拦截率>95%")
            if (runs < 3) flags.add("样本少")
            val uc = e.optJSONArray("usecases")
            val ucStr = if (uc != null) (0 until uc.length()).joinToString(",") { uc.getString(it) } else "-"
            sb.appendLine(
                String.format(
                    Locale.US,
                    "%s | %d 次 | %.1f→%.1f | %.1f | %.0f%% | %s | %s %s",
                    e.optString("nodeName", "-"), runs,
                    avgIn, avgOut, avgFilter, rate,
                    e.optString("lastDate", "-"), ucStr,
                    if (flags.isEmpty()) "" else "[" + flags.joinToString(" ") + "]"
                )
            )
        }
        sb.appendLine()
        sb.appendLine("说明: 累计 N 次真实执行后，从未拦截/输出=输入的节点为低使用率候选，可评估移出或并入 common。")
        try {
            summaryFile(context).writeText(sb.toString(), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    /** 读取原始统计 JSON（调试用）。 */
    fun load(context: Context): JSONObject {
        return try {
            val f = statsFile(context)
            if (f.exists()) JSONObject(f.readText(Charsets.UTF_8)) else JSONObject()
        } catch (e: Exception) {
            Log.w(TAG, "读取统计失败: ${e.message}")
            JSONObject()
        }
    }

    /** 供 UI/报告读取的报告文本。 */
    fun summaryText(context: Context): String {
        return try {
            val f = summaryFile(context)
            if (f.exists()) f.readText(Charsets.UTF_8)
            else "暂无 node 统计，请先执行一次选股/交易 pipeline。"
        } catch (_: Exception) {
            "暂无 node 统计"
        }
    }

    /** 清空累计（重新开始统计）。 */
    fun reset(context: Context) {
        try {
            statsFile(context).delete()
            summaryFile(context).delete()
            Log.i(TAG, "node 统计已清空")
        } catch (_: Exception) {
        }
    }

    private fun keyOf(nodeId: String, nodeName: String): String {
        // module 名可能重复出现（如不同 pipeline 同一 node 名），以 nodeId 为主键但保留 nodeName 展示
        return "$nodeId::$nodeName"
    }
}
