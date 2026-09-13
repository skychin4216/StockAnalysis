package com.chin.stockanalysis.strategy.trade

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.topology.ui.PipelineFlowChart
import com.chin.stockanalysis.strategy.topology.xml.UseCaseLoader
import com.chin.stockanalysis.strategy.trade.ui.QuantUiKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ## 工作台 · 热门板块「埋伏」页（实仓 / ETF 旁独立 Tab，2026-09-11 新增）
 *
 * 架构与 ETF 低吸页同构 —— usecase/pipeline 为 **XML 单一事实源**
 * （app/src/main/assets/usecases/sector_ambush_usecase.xml + sector_ambush_pipeline.xml），
 * 双端（APK UseCaseLoader + AutoQuant Python 引擎）读同一 XML 执行：
 *   板块: sector_daily_record 近 20 日热度 Top5 → sector_stocks 成分股合并去重
 *   信号: 2~3连阳 + close>MA5>MA10>MA20 且 MA20 抬升 + 梯量温和放量(1.2~3倍)
 *         + OBV 上行 + 启动前缩量洗盘不破 MA20 + 距60日高回撤 -25%~-1%
 *   离场: tp+8% / sl-5% / 10 日（参数全在 XML，改 XML 双端自动同步）
 * 数据: 完全本地（复用 App 已同步的日K快照 + 板块表），无行情同步步骤。
 *
 * 与 ETF 页 UI 对齐：深色底 #0E1116、可折叠执行日志面板、横滑技术表、
 * 按钮行「📈埋伏 | 🔀Pipeline | 📦持仓 | 📊报告」；持仓落统一 real_positions 表
 * （periodType=SectorAmbushQuant 与短/中/长/ETF 隔离）。
 */
@SuppressLint("SetTextI18n")
class SectorAmbushFragment : Fragment() {

    private lateinit var statusLabel: TextView
    private lateinit var signalTable: TableLayout
    private lateinit var footLabel: TextView

    /** 表头与列宽（dp）—— 与埋伏节点输出字段一一对应 */
    private val colHead = listOf("名称", "代码", "板块", "连阳", "量比", "距60高%", "OBV", "洗盘", "评分", "收盘")
    private val colW = intArrayOf(88, 78, 72, 44, 50, 66, 52, 50, 50, 62)

    private var latestJson: JSONObject? = null
    private var lastAsOfText: String = ""
    private var lastSigRows: Int = 0
    private var lastScanned: Int = 0
    private var lastHotSectors: String = ""
    private var lastStrategyText: String = ""
    private var lastExitText: String = ""

    // ── 可折叠执行日志面板（📈埋伏 时实时显示执行过程，与 ETF 页同一实现）──
    private lateinit var logStatusText: TextView
    private lateinit var logPanelToggle: TextView
    private lateinit var logPanelContent: LinearLayout
    private lateinit var logPanelScroll: ScrollView
    private lateinit var logPanelText: TextView
    private lateinit var logBusySpinner: android.widget.ProgressBar
    private var logExpanded = false
    private val logLines = ArrayDeque<String>()
    private val logNodeKeys = HashMap<String, Int>()
    private val logLock = Any()

    companion object {
        private const val PREFS = "sector_ambush_live"
        private const val KEY_JSON = "last_json"
        private const val TAG = "SectorAmbushFragment"
        /** 埋伏持仓在统一 real_positions 表中的 periodType（与短/中/长/ETF 同表隔离） */
        private const val PERIOD_TYPE_AMBUSH = "SectorAmbushQuant"
    }

    private data class LocalOutcome(val payloadJson: String?, val error: String?)

    override fun onCreateView(inflater: android.view.LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
            setBackgroundColor(Color.parseColor("#0E1116"))
        }

        // ── 入口按钮行（与 ETF 页同规格）──
        root.addView(buildButtonRow())

        // ── 可折叠执行日志面板 ──
        root.addView(createExecLogPanel())

        statusLabel = TextView(ctx).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#9E9E9E"))
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        root.addView(statusLabel)

        val scroll = ScrollView(ctx).apply { isFillViewport = true }
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        body.addView(sectionTitle(ctx, "今日埋伏信号（热门板块 → 主力启动票）"))
        signalTable = buildScrollTable(body)

        footLabel = TextView(ctx).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#8A8F98"))
            setPadding(dp(4), dp(8), dp(4), dp(4))
        }
        body.addView(footLabel)

        return root
    }

    private fun buildScrollTable(body: LinearLayout): TableLayout = QuantUiKit.table(
        requireContext(), QuantUiKit.DARK, colHead, colW, body,
        tip = "← 可左右滑动 →  连阳=启动阳线根数(2~3) · 量比=阳线段均量/前10日均量(1.2~3) · " +
            "距60高%=距60日高点回撤(-25%~-1%) · OBV=资金累积方向 · 洗盘=启动前缩量不破MA20",
        withHeader = false
    )

    private fun sectionTitle(ctx: Context, text: String): LinearLayout =
        QuantUiKit.titleRow(ctx, QuantUiKit.DARK, text, textSize = 13f, topPaddingDp = 6)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val cached = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_JSON, null)
        if (cached != null) render(cached)
        refresh()
    }

    /**
     * 由工作台 refreshAll / 按钮 调用：本地 UseCaseLoader 跑 sector_ambush usecase
     * → 渲染 n_ambush_exit 发布口径 JSON（与 PC _ambush_live_picks.json 同构）。
     */
    fun refresh() {
        lifecycleScope.launch {
            clearLogs()
            setLogBusy(true)
            val t0 = System.currentTimeMillis()
            appendLog("🚀 板块埋伏启动：板块热度 → 埋伏信号扫描 → 离场打包")
            var outcome: LocalOutcome? = null
            try {
                outcome = withContext(Dispatchers.IO) {
                    runLocalUseCase(
                        onNodeProgress = { _, node ->
                            lifecycleScope.launch(Dispatchers.Main) {
                                if (isAdded) {
                                    logStatusText.text = "🔄 $node 执行中…"
                                    appendLog("   ├─ $node 计算中…", key = node)
                                }
                            }
                        },
                        onNodeDone = { _, node, output, _ ->
                            lifecycleScope.launch(Dispatchers.Main) {
                                if (isAdded) replaceNodeLog("   └─ $node ✅ ${logNodeSummary(output)}", node)
                            }
                        }
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "本地 sector_ambush 执行异常: ${e.message}")
                appendLog("❌ 本地 sector_ambush 执行异常：${e.message?.take(120)}")
            }
            val payloadJson = outcome?.payloadJson
            val elapsed = (System.currentTimeMillis() - t0) / 1000.0
            if (payloadJson != null) {
                requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_JSON, payloadJson).apply()
                render(payloadJson)
                val pj = runCatching { JSONObject(payloadJson) }.getOrNull()
                val sigN = pj?.optJSONArray("signal_today")?.length() ?: 0
                appendLog("✅ 埋伏选股完成：命中 ${sigN} 只 · 扫描 ${lastScanned} 只 · 耗时 ${"%.1f".format(elapsed)} 秒")
                setLogStatus("✅ 完成 · 命中 ${sigN} 只（扫描 ${lastScanned} 只）")
                setLogBusy(false)
                return@launch
            }
            val errMsg = outcome?.error
            if (errMsg != null) Log.w(TAG, "本地 sector_ambush 未产出: $errMsg")
            statusLabel.text = "本地执行失败：" + (errMsg ?: "无结果") +
                "\n\n请确认 App 已同步日K快照与板块数据（板块热度表 sector_daily_record / 成分股表 sector_stocks）后重试。"
            footLabel.text = "埋伏口径：板块热度Top5 → 2~3连阳 + 多头排列(MA20抬升) + 梯量放量 + OBV上行 + 缩量洗盘不破MA20 + 距60高回撤-25%~-1%"
            appendLog("❌ 引擎未产出结果：${errMsg ?: "无结果"}")
            setLogStatus("❌ 引擎未产出")
            setLogBusy(false)
        }
    }

    /** 本地跑 sector_ambush usecase：n_ambush_exit 阶段输出即发布口径 JSON */
    private suspend fun runLocalUseCase(
        onNodeProgress: ((pipelineName: String, nodeName: String) -> Unit)? = null,
        onNodeDone: ((pipelineName: String, nodeName: String, output: Any?,
            flow: com.chin.stockanalysis.strategy.topology.core.StockFlowRecord?) -> Unit)? = null
    ): LocalOutcome {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val res = UseCaseLoader.run(
            "sector_ambush", date,
            onNodeProgress = onNodeProgress,
            onNodeDone = onNodeDone
        )
        if (!res.success) {
            val msg = res.errors.entries.joinToString("; ") { "${it.key}:${it.value}" }
                .ifBlank { "sector_ambush 执行失败" }
            return LocalOutcome(null, msg)
        }
        val payload = res.stageOutputs["n_ambush_exit"] as? JSONObject
            ?: return LocalOutcome(null, "引擎未产出 n_ambush_exit 发布结果（可能板块/行情数据缺失）")
        return LocalOutcome(payload.toString(), null)
    }

    private fun render(jsonText: String) {
        try {
            val j = JSONObject(jsonText)
            latestJson = j
            lastAsOfText = j.optString("as_of", "-").ifBlank { "-" }
            lastSigRows = j.optJSONArray("signal_today")?.length() ?: 0
            lastScanned = j.optInt("scanned", 0)
            lastHotSectors = j.optJSONArray("hotSectors")?.let { a ->
                (0 until a.length()).joinToString("、") { a.optString(it) }
            }.orEmpty()
            lastStrategyText = j.optString("strategy", "").trim()
            val exit = j.optJSONObject("exit")
            lastExitText = if (exit != null) {
                "tp+${fmt(exit.optDouble("tp", 0.0))}% / sl${fmt(exit.optDouble("sl", 0.0))}% / 持 ${exit.optInt("hold", 0)} 日"
            } else ""

            statusLabel.text = "数据截至 $lastAsOfText · 热门板块 ${lastHotSectors.ifBlank { "-" }} · " +
                "命中 $lastSigRows 只（扫描 $lastScanned 只，点击行可看详情与持仓）"

            signalTable.removeAllViews()
            signalTable.addView(QuantUiKit.headerRow(requireContext(), QuantUiKit.DARK, colHead, colW))
            fillRows(signalTable, j.optJSONArray("signal_today"))

            val lines = mutableListOf<String>()
            if (lastStrategyText.isNotEmpty()) lines.add(lastStrategyText)
            if (lastExitText.isNotEmpty()) lines.add("离场: $lastExitText")
            lines.add("连阳=启动阳线根数 · 量比=阳线段均量/前10日均量(1.2~3) · 距60高%=距60日高回撤(-25%~-1%)")
            lines.add("OBV=资金累积方向(↑流入) · 洗盘=启动前缩量回调不破MA20（主力控盘）")
            footLabel.text = lines.joinToString("\n")
        } catch (e: Exception) {
            statusLabel.text = "⚠ 解析失败: ${e.message}"
        }
    }

    private fun fillRows(t: TableLayout, arr: org.json.JSONArray?) {
        if (arr == null || arr.length() == 0) {
            t.addView(
                QuantUiKit.emptyHint(
                    requireContext(), QuantUiKit.DARK, "  （暂无埋伏信号。该类形态多数时间空仓 = 正确行为）"
                )
            )
            return
        }
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val vals = listOf(
                r.optString("name", r.optString("code", "")),
                r.optString("code", ""),
                r.optString("sector", "-"),
                r.optInt("bull", 0).toString(),
                fmt(r.optDouble("vr", 0.0)),
                fmt(r.optDouble("dd60", 0.0)),
                if (r.optBoolean("obvUp", false)) "↑" else "→",
                if (r.optBoolean("wash", false)) "✓" else "-",
                fmt(r.optDouble("score", 0.0)),
                "%.2f".format(r.optDouble("close", 0.0))
            )
            val row = QuantUiKit.row(
                requireContext(), QuantUiKit.DARK, vals, colW,
                aligns = IntArray(vals.size) { if (it in 3..5 || it == 8 || it == 9) Gravity.END else Gravity.CENTER },
                colorOf = { idx, v -> cellColor(idx, v) },
                onClick = {
                    runCatching { showRowDetail(r) }
                        .onFailure { Log.w(TAG, "查看标的详情失败: ${it.message}") }
                }
            )
            t.addView(row)
        }
    }

    /** 各列配色（深色底上红涨绿跌，形态列高亮） */
    private fun cellColor(idx: Int, v: String): Int = when (idx) {
        0 -> Color.parseColor("#FFE0B2")            // 名称
        1 -> Color.parseColor("#CE93D8")            // 代码
        2 -> Color.parseColor("#80CBC4")            // 板块
        3 -> Color.parseColor("#FFCC00")            // 连阳
        4 -> Color.parseColor("#FFCC00")            // 量比
        5 -> {                                        // 距60高%
            val dd = v.toDoubleOrNull() ?: 0.0
            if (dd < -18) Color.parseColor("#FF7043") else Color.parseColor("#FFCC00")
        }
        6 -> if (v == "↑") Color.parseColor("#69F0AE") else Color.parseColor("#9E9E9E")  // OBV
        7 -> if (v == "✓") Color.parseColor("#69F0AE") else Color.parseColor("#9E9E9E")  // 洗盘
        8 -> Color.parseColor("#FFAB91")            // 评分
        else -> Color.parseColor("#E0E0E0")
    }

    // ═══════════════════════════════════════════════════════════════
    // 📋 可折叠执行日志面板（与 ETF 页一致）
    // ═══════════════════════════════════════════════════════════════
    private fun createExecLogPanel(): LinearLayout {
        val ctx = requireContext()
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x0F2A6BBF.toInt())
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(2), dp(2), dp(2), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                logExpanded = !logExpanded
                refreshLogPanelVisibility()
            }
        }
        logBusySpinner = android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleSmall).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) }
        }
        header.addView(logBusySpinner)
        logStatusText = TextView(ctx).apply {
            text = "📋 执行日志（点击展开/收起；点「📈埋伏」可查看执行过程）"
            textSize = 12f
            setTextColor(Color.parseColor("#8A94A6"))
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(logStatusText)
        logPanelToggle = TextView(ctx).apply {
            text = "▶"
            textSize = 14f
            setTextColor(Color.parseColor("#8A94A6"))
            setPadding(dp(8), 0, dp(4), 0)
        }
        header.addView(logPanelToggle)
        panel.addView(header)

        logPanelContent = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        logPanelScroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(170))
        }
        val hScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = true
            isFillViewport = true
        }
        logPanelText = TextView(ctx).apply {
            text = "（暂无执行记录）"
            textSize = 11.5f
            setTextColor(Color.parseColor("#D0D6E0"))
            setLineSpacing(2f, 1.0f)
            setTypeface(Typeface.MONOSPACE)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(Color.parseColor("#101820"))
        }
        hScroll.addView(logPanelText, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        logPanelScroll.addView(hScroll, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        logPanelContent.addView(logPanelScroll)
        panel.addView(logPanelContent)
        refreshLogPanelVisibility()
        return panel
    }

    private fun refreshLogPanelVisibility() {
        if (!::logPanelContent.isInitialized || !::logPanelToggle.isInitialized) return
        logPanelContent.visibility = if (logExpanded) View.VISIBLE else View.GONE
        logPanelToggle.text = if (logExpanded) "▾" else "▶"
    }

    private fun ensureLogExpanded() {
        if (!logExpanded) {
            logExpanded = true
            refreshLogPanelVisibility()
        }
    }

    private fun clearLogs() {
        synchronized(logLock) {
            logLines.clear()
            logNodeKeys.clear()
        }
        if (::logPanelText.isInitialized) {
            logPanelText.post {
                if (isAdded) logPanelText.text = ""
            }
        }
    }

    private fun setLogBusy(busy: Boolean) {
        if (!::logBusySpinner.isInitialized) return
        logBusySpinner.post {
            if (isAdded) logBusySpinner.visibility = if (busy) View.VISIBLE else View.GONE
        }
    }

    private fun setLogStatus(text: String) {
        if (!::logStatusText.isInitialized) return
        logStatusText.post {
            if (isAdded) logStatusText.text = text
        }
    }

    private fun appendLog(text: String, key: String? = null) {
        if (!::logPanelText.isInitialized) return
        val ts = java.time.LocalTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
        synchronized(logLock) {
            if (key != null) logNodeKeys[key] = logLines.size
            logLines.addLast("[$ts] $text")
            while (logLines.size > 300) logLines.removeFirst()
        }
        logPanelText.post {
            if (isAdded) {
                ensureLogExpanded()
                renderLogs()
            }
        }
    }

    private fun replaceNodeLog(text: String, key: String) {
        if (!::logPanelText.isInitialized) return
        val ts = java.time.LocalTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
        synchronized(logLock) {
            val idx = logNodeKeys[key]
            if (idx != null && idx >= 0 && idx < logLines.size) {
                logLines[idx] = "[$ts] $text"
            }
        }
        logPanelText.post {
            if (isAdded) renderLogs()
        }
    }

    private fun renderLogs() {
        if (!::logPanelText.isInitialized) return
        val sb = android.text.SpannableStringBuilder()
        synchronized(logLock) {
            for (line in logLines) {
                val s = android.text.SpannableString(line)
                val color = when {
                    line.contains("❌") -> 0xFFE57373.toInt()
                    line.contains("⚠️") || line.contains("⚠") -> 0xFFFFB74D.toInt()
                    line.contains("✅") -> 0xFF81C784.toInt()
                    line.contains("🚀") -> 0xFF64B5F6.toInt()
                    line.contains("▶") -> 0xFFFFD54F.toInt()
                    line.contains("├─") || line.contains("└─") -> 0xFF90A4AE.toInt()
                    else -> 0xFFD0D6E0.toInt()
                }
                s.setSpan(android.text.style.ForegroundColorSpan(color), 0, s.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append(s).append('\n')
            }
        }
        logPanelText.text = sb
        logPanelText.post { logPanelScroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** 节点输出摘要（热门板块 / 命中数等） */
    private fun logNodeSummary(o: Any?): String {
        if (o == null) return ""
        return try {
            when (o) {
                is JSONObject -> {
                    val out = StringBuilder()
                    o.optJSONArray("hotSectors")?.let {
                        if (it.length() > 0) out.append("热门板块 ${it.length()} 个")
                    }
                    o.optJSONArray("signal_today")?.let { out.append(" · 埋伏 ${it.length()} 只") }
                    o.optJSONArray("rows")?.let {
                        if (!o.has("signal_today")) out.append(" · 命中 ${it.length()} 只")
                    }
                    if (o.has("scanned")) out.append(" · 扫描 ${o.optInt("scanned", 0)} 只")
                    out.toString().trim().ifEmpty { "就绪" }
                }
                is org.json.JSONArray -> "${o.length()} 项"
                else -> o.toString().take(40)
            }
        } catch (e: Exception) { "就绪" }
    }

    /** 点行 → 详情卡：埋伏证据 + 当前状态 +（已持有则含持仓详细与操作） */
    private fun showRowDetail(r: JSONObject) {
        val ctx = requireContext()
        val code = r.optString("code", "")
        lifecycleScope.launch {
            val pos = withContext(Dispatchers.IO) {
                runCatching { ambushDao().getByPeriod(PERIOD_TYPE_AMBUSH) }.getOrDefault(emptyList())
                    .firstOrNull { p ->
                        p.stockCode.removePrefix("sh").removePrefix("sz") ==
                            code.removePrefix("sh").removePrefix("sz")
                    }
            }
            if (!isAdded) return@launch
            val name = r.optString("name", code)
            val close = r.optDouble("close", 0.0)
            val sb = StringBuilder()
            sb.appendLine("$name  $code  [${r.optString("sector", "-")}]")
            sb.appendLine("收盘 ${"%.2f".format(close)}  |  连阳 ${r.optInt("bull", 0)} 根  |  量比 ${fmt(r.optDouble("vr", 0.0))}")
            sb.appendLine("距60日高回撤 ${fmt(r.optDouble("dd60", 0.0))}%  |  OBV ${if (r.optBoolean("obvUp", false)) "上行↑" else "走平→"}  |  洗盘 ${if (r.optBoolean("wash", false)) "确认✓" else "未确认"}  |  评分 ${fmt(r.optDouble("score", 0.0))}")
            sb.appendLine("信号日期 ${r.optString("date", lastAsOfText)}")
            sb.appendLine()
            sb.appendLine("◆ 主力埋伏三证据：梯量温和放量(1.2~3倍) + OBV上行 + 启动前缩量洗盘不破MA20")
            if (lastStrategyText.isNotBlank()) sb.appendLine("买入口径: $lastStrategyText")
            if (lastExitText.isNotBlank()) sb.appendLine("离场: $lastExitText")
            if (pos != null) {
                sb.appendLine()
                sb.appendLine("📦 当前持仓：")
                sb.appendLine("  数量 ${pos.quantity} × 成本 ${"%.3f".format(pos.avgBuyPrice)}")
                sb.appendLine("  买入 ${pos.buyDate} · 点下方「✏️ 编辑持仓」可调整/清仓")
            } else {
                sb.appendLine()
                sb.append("未持有该标的 —— 若确认买入，点「➕ 记入持仓」录入，报告将自动纳入统计。")
            }
            AlertDialog.Builder(ctx)
                .setTitle("🎯 埋伏标详情")
                .setMessage(sb.toString())
                .setNeutralButton(if (pos != null) "✏️ 编辑持仓" else "➕ 记入持仓") { _, _ -> addAmbushPositionDialog(pos) }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    private fun fmt(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.2f".format(v)

    // ═══════════════════════════════════════════════════
    // 入口按钮行（与 ETF 页同规格）
    // 📈埋伏 | 🔀Pipeline | 📦持仓 | 📊报告
    // ═══════════════════════════════════════════════════
    private fun buildButtonRow(): LinearLayout {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(1), dp(4), dp(1))
        }
        fun mk(text: String, color: String, weight: Float, onClick: () -> Unit): Button =
            Button(ctx).apply {
                this.text = text
                textSize = 10f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor(color))
                setPadding(dp(4), dp(1), dp(4), dp(1))
                setMinWidth(0); setMinimumWidth(0)
                layoutParams = LinearLayout.LayoutParams(0, dp(22), weight).apply { marginEnd = dp(1) }
                setOnClickListener { onClick() }
            }
        row.addView(mk("📈埋伏", "#E65100", 5f) { refresh() })
        row.addView(mk("🔀Pipeline", "#6A1B9A", 5f) { openAmbushPipeline() })
        row.addView(mk("📦持仓", "#1565C0", 4f) { showAmbushHoldings() })
        row.addView(mk("📊报告", "#455A64", 4f) { showAmbushReport() })
        return row
    }

    /** 🔀 Pipeline：sector_ambush usecase 分层流程图（可进入拓扑编辑器改 XML） */
    private fun openAmbushPipeline() {
        val ctx = requireContext()
        PipelineFlowChart.showFlowChart(
            context = ctx,
            useCaseId = "sector_ambush",
            onOpenEditor = {
                runCatching {
                    startActivity(
                        Intent(ctx, com.chin.stockanalysis.strategy.topology.ui.TopologyEditorActivity::class.java)
                            .putExtra("usecase_id", "sector_ambush")
                    )
                }.onFailure { e -> Log.w(TAG, "打开 sector_ambush 拓扑编辑器失败: ${e.message}") }
            }
        )
    }

    // ═══════════════════════════════════════════════════
    // 📦 埋伏持仓（统一存 real_positions 表，periodType=SectorAmbushQuant 隔离）
    // ═══════════════════════════════════════════════════
    private fun ambushDao() = StockDatabase.getInstance(requireContext()).realPositionDao()

    private fun showAmbushHoldings() {
        val ctx = requireContext()
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching { ambushDao().getByPeriod(PERIOD_TYPE_AMBUSH) }.getOrDefault(emptyList())
            }
            if (!isAdded) return@launch
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(8), dp(18), dp(2))
            }
            if (list.isEmpty()) {
                box.addView(TextView(ctx).apply {
                    text = "（暂无埋伏持仓）\n点下方「添加持仓」录入；保存后持仓盈亏进入「📊报告」汇总。"
                    textSize = 13f
                    setTextColor(Color.parseColor("#9E9E9E"))
                    setPadding(0, dp(6), 0, dp(6))
                })
            } else {
                for (pos in list) {
                    val cur = if (pos.currentPrice > 0) pos.currentPrice else pos.avgBuyPrice
                    val pnl = if (pos.avgBuyPrice > 0 && cur > 0)
                        (cur - pos.avgBuyPrice) / pos.avgBuyPrice * 100 else 0.0
                    val row = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(8), dp(6), dp(8), dp(6))
                        background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(0x141A24.toInt()); cornerRadius = dp(6).toFloat()
                        }
                        isClickable = true
                        setOnClickListener { editOrSellDialog(pos) }
                    }
                    row.addView(TextView(ctx).apply {
                        text = "${pos.stockName}  ${pos.stockCode}"
                        textSize = 14f
                        setTextColor(Color.parseColor("#FFE0B2"))
                        setTypeface(typeface, Typeface.BOLD)
                    })
                    row.addView(TextView(ctx).apply {
                        text = "数量 ${pos.quantity} × 成本 ${"%.3f".format(pos.avgBuyPrice)}  |  参考价 " +
                            "%.3f".format(cur) + "  |  浮动 " + (if (pnl >= 0) "+" else "") +
                            "%.2f".format(pnl) + "%"
                        textSize = 12f
                        setTextColor(if (pnl >= 0) Color.parseColor("#FF8A80") else Color.parseColor("#64B5F6"))
                        setPadding(0, dp(2), 0, 0)
                    })
                    box.addView(row, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6) })
                }
            }
            val scroll = ScrollView(ctx).apply { isFillViewport = true }
            scroll.addView(box)
            AlertDialog.Builder(ctx)
                .setTitle("📦 埋伏持仓（点持仓可编辑/清仓）")
                .setView(scroll)
                .setNeutralButton("➕ 添加持仓") { _, _ -> addAmbushPositionDialog(null) }
                .setPositiveButton("关闭", null)
                .show()
        }
    }

    private fun editOrSellDialog(pos: RealPositionEntity) {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle("${pos.stockName}  ${pos.stockCode}")
            .setItems(arrayOf("✏️ 编辑数量/成本", "💰 卖出/清仓")) { _, which ->
                when (which) {
                    0 -> addAmbushPositionDialog(pos)
                    1 -> lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching { ambushDao().markInactive(pos.id) }
                        }
                        Toast.makeText(ctx, "已清仓 ${pos.stockName}", Toast.LENGTH_SHORT).show()
                        if (isAdded) showAmbushHoldings()
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun addAmbushPositionDialog(existing: RealPositionEntity?) {
        val ctx = requireContext()
        fun label(t: String) = TextView(ctx).apply {
            text = t
            textSize = 12f
            setTextColor(Color.parseColor("#FFB74D"))
            setPadding(0, dp(8), 0, dp(2))
        }
        fun field(text: String) = EditText(ctx).apply {
            setText(text)
            textSize = 13f
            setSingleLine(true)
        }
        val codeEt = field(existing?.stockCode ?: "")
        val nameEt = field(existing?.stockName ?: "")
        val qtyEt = field(existing?.quantity?.toString() ?: "")
        qtyEt.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        val costText = existing?.let { if (it.avgBuyPrice > 0) "%.3f".format(it.avgBuyPrice) else "" } ?: ""
        val costEt = field(costText)
        costEt.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val dateEt = field(existing?.buyDate ?: today)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("代码（600000 / sh600000）"))
            addView(codeEt)
            addView(label("名称")); addView(nameEt)
            addView(label("数量（股）")); addView(qtyEt)
            addView(label("买入均价")); addView(costEt)
            addView(label("买入日期 yyyy-MM-dd")); addView(dateEt)
            setPadding(dp(18), 0, dp(18), 0)
        }
        AlertDialog.Builder(ctx)
            .setTitle(if (existing == null) "➕ 添加埋伏持仓" else "✏️ 编辑埋伏持仓")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val code = normalizeCode(codeEt.text.toString())
                val name = nameEt.text.toString().trim().ifBlank { code }
                val qty = qtyEt.text.toString().toIntOrNull() ?: 0
                val cost = costEt.text.toString().toDoubleOrNull() ?: 0.0
                if (code.isEmpty() || qty <= 0 || cost <= 0) {
                    Toast.makeText(ctx, "代码不能为空，数量/均价需 > 0", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val entity = RealPositionEntity(
                    id = existing?.id ?: 0,
                    stockCode = code,
                    stockName = name,
                    quantity = qty,
                    avgBuyPrice = cost,
                    buyDate = dateEt.text.toString().trim().ifBlank { today },
                    periodType = PERIOD_TYPE_AMBUSH
                )
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching { ambushDao().insert(entity) }
                    }
                    Toast.makeText(ctx, "已保存 $name（$code）", Toast.LENGTH_SHORT).show()
                    if (isAdded) showAmbushHoldings()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 代码归一：600000 → sh600000；000001 → sz000001；已带前缀原样返回 */
    private fun normalizeCode(raw: String): String {
        var c = raw.trim().lowercase(Locale.US)
        if (c.startsWith("sh") || c.startsWith("sz") || c.startsWith("bj")) return c
        if (c.length == 6 && c.all { it.isDigit() }) {
            c = if (c.startsWith("6") || c.startsWith("9")) "sh$c" else "sz$c"
        }
        return c
    }

    /** 📊 报告：埋伏口径 + 今日输出 + 持仓浮动盈亏汇总 */
    private fun showAmbushReport() {
        val ctx = requireContext()
        lifecycleScope.launch {
            val positions = withContext(Dispatchers.IO) {
                runCatching { ambushDao().getByPeriod(PERIOD_TYPE_AMBUSH) }.getOrDefault(emptyList())
            }
            if (!isAdded) return@launch
            var cost = 0.0
            var mkt = 0.0
            for (p in positions) {
                val cur = if (p.currentPrice > 0) p.currentPrice else p.avgBuyPrice
                cost += p.quantity * p.avgBuyPrice
                mkt += p.quantity * cur
            }
            val pnl = mkt - cost
            val pnlPct = if (cost > 0) pnl / cost * 100 else 0.0
            val sb = StringBuilder()
            sb.appendLine("【策略】热门板块埋伏（板块热度 → 埋伏信号 → 离场）")
            if (lastStrategyText.isNotBlank()) sb.appendLine("  买入口径: $lastStrategyText")
            if (lastExitText.isNotBlank()) sb.appendLine("  离场规则: $lastExitText")
            sb.appendLine()
            sb.appendLine("【今日选股输出】数据截至 $lastAsOfText")
            sb.appendLine("  热门板块: ${lastHotSectors.ifBlank { "-" }}")
            sb.appendLine("  埋伏信号 ${lastSigRows} 只 | 扫描 ${lastScanned} 只")
            sb.appendLine()
            sb.appendLine("【持仓（${positions.size} 笔）】")
            if (positions.isEmpty()) {
                sb.appendLine("  暂无持仓，点「📦持仓」录入。")
            } else {
                sb.appendLine("  总成本    ${"%.0f".format(cost)}")
                sb.appendLine("  参考市值  ${"%.0f".format(mkt)}")
                sb.appendLine("  浮动盈亏  ${"%.0f".format(pnl)}（${if (pnlPct >= 0) "+" else ""}${"%.2f".format(pnlPct)}%）")
            }
            AlertDialog.Builder(ctx)
                .setTitle("📊 埋伏报告")
                .setMessage(sb.toString())
                .setPositiveButton("重新选股", { _, _ -> refresh() })
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
