package com.chin.stockanalysis.strategy.trade

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.content.Intent
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.data.EtfCacheSync
import com.chin.stockanalysis.strategy.topology.ui.PipelineFlowChart
import com.chin.stockanalysis.strategy.topology.xml.UseCaseLoader
import com.chin.stockanalysis.strategy.trade.ui.QuantUiKit
import com.chin.stockanalysis.strategy.trade.ui.EtfStrategyLabPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ## 工作台 · ETF 选股页（实仓旁 Tab，2026-09-06 起本地化改造）
 *
 * 架构与三周期同构 —— usecase/pipeline 为 **XML 单一事实源**
 * （app/src/main/assets/usecases/etf_dip_usecase.xml + etf_dip_pipeline.xml），
 * 双端（APK UseCaseLoader + AutoQuant Python 引擎）读同一 XML 执行：
 *   门控: 沪深300 结构多头(close>MA20>MA60)——2026-09-09 修复为【最新交易日】判定，
 *         不再回溯历史多头日（旧版会显示过期日期如 2026.07.01 并误报可低吸）
 *   信号: 距60日高回撤-25%~-12% + RSI6<30 + 年线上方 + 收阳/RSI拐头 + 非5日新低
 *   离场: tp+2% / sl-6% / 30 日（参数全在 XML，改 XML 双端自动同步）
 * 行情: 本地 etf_cache.json —— APK 自己拉取（EtfCacheSync：腾讯 fqkline qfq，
 * 13只ETF+sh000300 前复权日K，与 PC _etf_cache.json 同构同源，写 external files）；
 * 缓存缺失/过期时点击"刷新"即自动同步，无需 PC。
 * 已移除 PC 桥回退（2026-09-07）：本地无法执行时仅提示联网自拉。
 *
 * 2026-09-09 UI：表头改深蓝底白字（修白色看不清）；行列加宽、支持横向滚动；
 * 行内新增 SAR / MACD / OBV / 多日跌后K形态（大阳/小阳/大阴/小阴）技术假设列，
 * 口径对齐 PC smalltools/_technicals.py rich_tag —— 与推送选股表同一套判断语言。
 */
@SuppressLint("SetTextI18n")
class EtfDipFragment : Fragment() {

    private lateinit var gateLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var sigTable: TableLayout
    private lateinit var watchTable: TableLayout
    private lateinit var footLabel: TextView

    /** 表头与列宽（dp）—— 与推送选股表同语言：技术假设列 SAR/MACD/OBV/多日跌后形态 */
    private val colHead = listOf("名称", "代码", "收盘", "回撤60%", "RSI6", "SAR", "MACD", "OBV", "跌后K形态", "趋势图", "状态")
    private val colW = intArrayOf(98, 86, 62, 66, 44, 74, 72, 74, 104, 96, 58)

    /** 最近一次成功渲染的 payload 摘要（供 买卖评估/报告 读取，避免反复解析文件） */
    private var latestJson: JSONObject? = null
    private var lastAsOfText: String = ""
    private var lastSigRows: Int = 0
    private var lastWatchRows: Int = 0
    private var lastStrategyText: String = ""
    private var lastExitText: String = ""

    // ── 可折叠执行日志面板（📈选股 时实时显示执行过程，移植自三周期 createExecLogPanel）──
    private lateinit var logPanelHeader: LinearLayout
    private lateinit var logStatusText: TextView
    private lateinit var logPanelToggle: TextView
    private lateinit var logPanelContent: LinearLayout
    private lateinit var logPanelScroll: ScrollView
    private lateinit var logPanelText: TextView
    private lateinit var logBusySpinner: android.widget.ProgressBar
    private var logExpanded = false
    /** 日志缓冲（最多 300 行），渲染/写入均持锁，节点行 key = pipeline::node 原位替换 */
    private val logLines = ArrayDeque<String>()
    private val logNodeKeys = HashMap<String, Int>()
    private val logLock = Any()

    companion object {
        private const val PREFS = "etf_live"
        private const val KEY_JSON = "last_json"
        private const val TAG = "EtfDipFragment"
        /** ETF 持仓在统一 real_positions 表中的 periodType（与短/中/长周期持仓同表隔离） */
        private const val PERIOD_TYPE_ETF = "EtfDipQuant"
    }

    private data class LocalOutcome(val payloadJson: String?, val error: String?)

    override fun onCreateView(inflater: android.view.LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
            setBackgroundColor(Color.parseColor("#0E1116"))
        }

        // 2026-09-11：原「ETF 低位低吸 + 刷新」顶栏整行删除（用户口径：不要这一行 view）；
        // 刷新入口保留在下方按钮行的「📈选股」处（同调 refresh()）。

        // ── 入口按钮行（移植自三周期 QuantFragmentBase.createButtonRow，同配色/同权重）──
        // 📈选股 | 🔀Pipeline | 💰买卖评估 ▾ | 📦持仓 | 📊报告
        root.addView(buildButtonRow())

        // ── 可折叠执行日志面板（点 📈选股 后实时显示同步/执行/结果；点头部可收起/展开）──
        root.addView(createExecLogPanel())

        gateLabel = TextView(ctx).apply {
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            // 正常门控已上移到工作台公共行（「🚀 一键建仓」同行小字），本页横幅仅供异常态，
            // 故默认隐藏，避免出现一行空白占位。
            visibility = View.GONE
        }
        root.addView(gateLabel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        statusLabel = TextView(ctx).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#9E9E9E"))
            setPadding(dp(4), dp(2), dp(4), dp(6))
        }
        root.addView(statusLabel)

        val scroll = ScrollView(ctx).apply { isFillViewport = true }
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        body.addView(sectionTitle(ctx, "今日可低吸信号（次日开盘可买）"))
        sigTable = buildScrollTable(body)
        body.addView(sectionTitle(ctx, "接近低吸区观察池（提前跟踪）"))
        watchTable = buildScrollTable(body)

        footLabel = TextView(ctx).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#8A8F98"))
            setPadding(dp(4), dp(8), dp(4), dp(4))
        }
        body.addView(footLabel)

        return root
    }

    /**
     * 表格骨架（横滑 + 列说明提示）统一交给公共构件 [QuantUiKit] 构造。
     * 表头不在这里加：render() 每次 removeAllViews 后要重新补（见该方法），
     * 故此处 withHeader=false，把「表头补一次」的控制权留给调用方。
     */
    private fun buildScrollTable(body: LinearLayout): TableLayout = QuantUiKit.table(
        requireContext(), QuantUiKit.DARK, colHead, colW, body,
        tip = "← 可左右滑动 →  SAR=红↑多头/绿↓空头 · MACD=金叉死叉/柱收窄扩大 · " +
            "OBV=能量方向 · 跌后K形态=多日下跌后出现的小阳小阴/大阳大阴(±1.5%)",
        withHeader = false
    )

    /** 区块标题：统一走公共构件（深色主题，与三周期页共用同一套区块构造器）。 */
    private fun sectionTitle(ctx: Context, text: String): LinearLayout =
        QuantUiKit.titleRow(ctx, QuantUiKit.DARK, text, textSize = 13f, topPaddingDp = 6)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 先显示上次缓存（离线也可见），随后尝试在线刷新
        val cached = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_JSON, null)
        if (cached != null) render(cached)
        refresh()
    }

    /**
     * 由工作台 refreshAll / onResume / 刷新按钮 调用。
     * 0) 先自拉/校验本地行情 etf_cache.json（EtfCacheSync，腾讯 qfq，与 PC 同构，缺失或过期才拉）；
     * 1) 本地引擎：UseCaseLoader 跑 assets/usecases/etf_dip_usecase.xml（与 PC 同 XML 同源）→ 渲染；
     * 2) 引擎未产出（无缓存/无网络）→ 引导提示。APK 完全自给，无 PC 桥。
     * 执行全过程写入上方「📋 执行日志」面板（节点级进度，可展开查看）。
     */
    fun refresh() {
        lifecycleScope.launch {
            clearLogs()
            setLogBusy(true)
            val t0 = System.currentTimeMillis()
            appendLog("🚀 ETF 选股启动：行情同步 → etf_dip 引擎 → 结果渲染")
            // ── 0) 本地行情自拉：APK 自给（腾讯 fqkline qfq → 手机 etf_cache.json）──
            var syncOk = false
            try {
                setLogStatus("🔄 同步 ETF 行情…")
                appendLog("📡 行情同步：检查/拉取本地 etf_cache.json（腾讯 qfq）…")
                val sync = withContext(Dispatchers.IO) { EtfCacheSync(requireContext()).syncIfStale() }
                syncOk = true
                Log.i(TAG, "ETF 行情同步: ${sync.message}")
                appendLog("✅ 行情就绪：${sync.message}")
            } catch (e: Exception) {
                Log.w(TAG, "本地 ETF 行情同步异常: ${e.message}")
                appendLog("⚠️ 行情同步异常：${e.message?.take(80)}，将尝试用本地已有缓存执行")
            }
            // ── 1) 本地引擎（彻底不依赖 PC），节点级进度实时写入执行日志 ──
            var outcome: LocalOutcome? = null
            try {
                appendLog("🚀 执行 UseCase etf_dip：门控 → 信号扫描 → 离场打包")
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
                Log.w(TAG, "本地 etf_dip 执行异常: ${e.message}")
                appendLog("❌ 本地 etf_dip 执行异常：${e.message?.take(120)}")
            }
            val payloadJson = outcome?.payloadJson
            val elapsed = (System.currentTimeMillis() - t0) / 1000.0
            if (payloadJson != null) {
                requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_JSON, payloadJson).apply()
                render(payloadJson)
                val pj = runCatching { JSONObject(payloadJson) }.getOrNull()
                val sigN = pj?.optJSONArray("signal_today")?.length() ?: 0
                val watchN = pj?.optJSONArray("approach")?.length() ?: 0
                appendLog("✅ 选股完成：今日可低吸信号 ${sigN} 只 · 观察池 ${watchN} 只 · 耗时 ${"%.1f".format(elapsed)} 秒")
                setLogStatus("✅ 选股完成 · 信号 ${sigN} 只 / 观察 ${watchN} 只")
                setLogBusy(false)
                return@launch
            }
            val errMsg = outcome?.error
            if (errMsg != null) Log.w(TAG, "本地 etf_dip 未产出: $errMsg")
            // ── 2) 引擎未产出：本地自拉引导提示（PC 桥已移除，APK 完全自给）──
            showGateProblem("⚠ 本地无 ETF 行情缓存")
            statusLabel.text = (if (errMsg != null) "本地执行失败：$errMsg\n\n" else "") +
                "请确保手机网络可用后点「刷新」——APK 会自动拉取 13只ETF+沪深300\n" +
                "前复权日K到本地 etf_cache.json 并执行选股（无需 PC）。"
            footLabel.text = "低位低吸口径：沪深300 多头门控 → 回撤-25%~-12% + RSI6<30 → 离场 tp+2%/sl-6%/30日"
            appendLog("❌ 引擎未产出结果：${errMsg ?: "本地无 ETF 行情缓存（请联网后点「刷新」）"}")
            setLogStatus(if (syncOk) "❌ 引擎未产出（本地无行情缓存）" else "❌ 行情同步失败")
            setLogBusy(false)
        }
    }

    /** 本地跑 etf_dip usecase：n_etf_exit 阶段输出即发布口径 JSON（与 PC _etf_live_picks.json 同构） */
    private suspend fun runLocalUseCase(
        onNodeProgress: ((pipelineName: String, nodeName: String) -> Unit)? = null,
        onNodeDone: ((pipelineName: String, nodeName: String, output: Any?,
            flow: com.chin.stockanalysis.strategy.topology.core.StockFlowRecord?) -> Unit)? = null
    ): LocalOutcome {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val res = UseCaseLoader.run(
            "etf_dip", date,
            onNodeProgress = onNodeProgress,
            onNodeDone = onNodeDone
        )
        if (!res.success) {
            val msg = res.errors.entries.joinToString("; ") { "${it.key}:${it.value}" }
                .ifBlank { "etf_dip 执行失败" }
            return LocalOutcome(null, msg)
        }
        val payload = res.stageOutputs["n_etf_exit"] as? JSONObject
            ?: return LocalOutcome(null, "引擎未产出 n_etf_exit 发布结果（可能行情缓存缺失）")
        return LocalOutcome(payload.toString(), null)
    }

    private fun render(jsonText: String) {
        try {
            val j = JSONObject(jsonText)
            val err = j.optString("error")
            if (err.isNotEmpty()) {
                showGateProblem("⚠ $err")
                statusLabel.text = j.optString("hint", "本地行情缓存缺失，请联网后点「刷新」自动同步并本地选股")
                return
            }
            // 缓存本次 payload 摘要，供 📈选股/💰买卖评估/📊报告 直接读取
            latestJson = j
            lastAsOfText = j.optString("as_of", "-")
            lastSigRows = j.optJSONArray("signal_today")?.length() ?: 0
            lastWatchRows = j.optJSONArray("approach")?.length() ?: 0
            lastStrategyText = j.optString("strategy", "").trim()
            lastExitText = j.optString("exit", "").trim()
            val gate = j.optJSONObject("gate") ?: JSONObject()
            val gateOk = gate.optBoolean("ok", false)
            val gateState = gate.optString("state", "")
            // 门控判断的是「大盘结构」这一公共前提（三周期低吸与 ETF 低吸同用一套结论），
            // 2026-09-10 起不再在 ETF 页独占整行横幅，改为发布到工作台公共行（「一键建仓」同行小字）。
            val gateNote = gate.optString("note", "")
            val gateLine1 = "沪深300 " + when {
                gateOk -> "多头排列"
                gateState == "bear" -> "空头排列"
                gateState == "mixed" -> "均线纠缠"
                gateNote.contains("数据不足") -> "数据不足"
                else -> "门控未知"
            }
            QuantWorkbenchState.publishEtfGate(gateLine1, gateChainOf(gateNote), gateOk)
            // 本页只保留「异常态」横幅（正常门控已在工作台公共行显示，不再占一整行）
            gateLabel.visibility = View.GONE
            statusLabel.text = "数据截至 ${j.optString("as_of", "-")} · 信号 ${lastSigRows} 只 · 观察 ${lastWatchRows} 只（点击信号/观察行可看详情与持仓）"
            sigTable.removeAllViews()
            watchTable.removeAllViews()
            // 表头每次重建后补回（统一由公共构件 QuantUiKit 构造，列宽/配色一处定义）
            sigTable.addView(QuantUiKit.headerRow(requireContext(), QuantUiKit.DARK, colHead, colW))
            watchTable.addView(QuantUiKit.headerRow(requireContext(), QuantUiKit.DARK, colHead, colW))
            fillRows(sigTable, j.optJSONArray("signal_today"), gateOk)
            fillRows(watchTable, j.optJSONArray("approach"), null)
            val lines = mutableListOf<String>()
            val strategy = j.optString("strategy", "").trim()
            val exit = j.optString("exit", "").trim()
            if (strategy.isNotEmpty()) lines.add(strategy)
            if (exit.isNotEmpty()) lines.add("离场: $exit")
            lines.add("SAR=红↑多头/绿↓空头 · MACD=金叉/死叉或柱收窄扩大 · OBV=能量方向 · 跌后K=大阳小阳/大阴小阴(±1.5%)")
            lines.add("趋势图=均线排列方向(↑上涨/→中性/↓下跌) + 末根经典K线形态(十字星/看涨吞没/看跌吞没/锤子线)")
            footLabel.text = lines.joinToString("\n")
        } catch (e: Exception) {
            showGateProblem("⚠ 解析失败: ${e.message}")
        }
    }

    /**
     * 从引擎 note 提取门控「数值链」，供工作台公共行小字第二行：
     *   「沪深300 空头排列 close(4554)<MA20(4601)<MA60(4703)」→「4554<MA20(4601)<MA60(4703)」
     *   「沪深300 close(4548)>MA20(4601)>MA60(4703)」        →「4548>MA20(4601)>MA60(4703)」
     *   「沪深300 均线纠缠（未呈多头排列）MA20(4601) MA60(4703)」→「MA20(4601) MA60(4703)」
     */
    private fun gateChainOf(note: String): String {
        var s = note.substringAfter("沪深300", "").trim()
        for (p in listOf("空头排列", "多头排列", "均线纠缠（未呈多头排列）", "均线纠缠")) {
            if (s.startsWith(p)) {
                s = s.removePrefix(p).trim()
                break
            }
        }
        return s.replace(Regex("close\\(([\\d.\\-]+)\\)"), "$1").trim()
    }

    /** 异常态：本页横幅展示 + 同步覆盖工作台公共行（避免公共行留着旧门控误导）。 */
    private fun showGateProblem(text: String) {
        gateLabel.visibility = View.VISIBLE
        gateLabel.text = text
        gateLabel.setBackgroundColor(Color.parseColor("#3A1D1D"))
        QuantWorkbenchState.publishEtfGate(text, "", false)
    }

    private fun fillRows(t: TableLayout, arr: org.json.JSONArray?, isSignal: Boolean?) {
        if (arr == null || arr.length() == 0) {
            t.addView(
                QuantUiKit.emptyHint(
                    requireContext(), QuantUiKit.DARK, "  （暂无。低位策略多数时间空仓 = 正确行为）"
                )
            )
            return
        }
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val isSigRow = r.optBoolean("dip", false)   // dip = dip_ok && 门控开
            val name = r.optString("name", r.optString("code", ""))
            val vals = listOf(
                name,
                r.optString("code", ""),
                String.format("%.3f", r.optDouble("close", 0.0)),
                String.format("%.1f", r.optDouble("dd60", 0.0)),
                String.format("%.0f", r.optDouble("rsi6", 0.0)),
                r.optString("sar", "-"),
                r.optString("macd", "-"),
                r.optString("obv", "-"),
                r.optString("kline", "-"),
                r.optString("trend", "—"),
                if (isSignal != null && isSigRow) "信号" else "观察")
            val row = QuantUiKit.row(
                requireContext(), QuantUiKit.DARK, vals, colW,
                aligns = IntArray(vals.size) { if (it in 2..4) Gravity.END else Gravity.CENTER },
                colorOf = { idx, v -> cellColor(idx, v) },
                onClick = {
                    runCatching { showEtfRowDetail(r) }
                        .onFailure { Log.w(TAG, "查看标的详情失败: ${it.message}") }
                }
            )
            t.addView(row)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 📋 可折叠执行日志面板（移植自三周期 QuantFragmentBase.createExecLogPanel）
    // 点「📈选股」时：同步行情 / usecase 执行（节点级）/ 完成统计 全部实时落盘于此。
    // 头 部：状态文字 + 进度圈 + ▶/▾；内容区：等宽字体、上下+左右滚动、自动滚底。
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
        logPanelHeader = LinearLayout(ctx).apply {
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
        logPanelHeader.addView(logBusySpinner)
        logStatusText = TextView(ctx).apply {
            text = "📋 执行日志（点击展开/收起；点「📈选股」可查看执行过程）"
            textSize = 12f
            setTextColor(Color.parseColor("#8A94A6"))
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        logPanelHeader.addView(logStatusText)
        logPanelToggle = TextView(ctx).apply {
            text = "▶"
            textSize = 14f
            setTextColor(Color.parseColor("#8A94A6"))
            setPadding(dp(8), 0, dp(4), 0)
        }
        logPanelHeader.addView(logPanelToggle)
        panel.addView(logPanelHeader)

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

    /** 追加一行日志；key 非空则记录该行位置，供节点完成后原位替换成 ✅ */
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

    /** 节点完成：把「计算中…」原位替换为「✅ 结果摘要」 */
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

    /** 等宽渲染 + 关键词着色（🚀蓝 / ✅绿 / ❌红 / ⚠橙 / ▶黄 / 节点灰） + 自动滚底 */
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

    /** 节点输出摘要（gate.ok / signal 数组长度等），显示在执行日志 ✅ 行 */
    private fun logNodeSummary(o: Any?): String {
        if (o == null) return ""
        return try {
            when (o) {
                is JSONObject -> {
                    val out = StringBuilder()
                    if (o.has("ok")) {
                        out.append("ok=${o.optBoolean("ok")}")
                        o.optString("note").takeIf { it.isNotBlank() }?.let { out.append(" · $it") }
                    }
                    o.optJSONArray("signal_today")?.let { out.append(" · 可低吸 ${it.length()} 只") }
                    o.optJSONArray("approach")?.let { out.append(" · 观察 ${it.length()} 只") }
                    o.optJSONArray("rows")?.let {
                        if (!o.has("signal_today")) out.append(" · 扫描 ${it.length()} 只")
                    }
                    out.toString().trim().ifEmpty { "就绪" }
                }
                is org.json.JSONArray -> "${o.length()} 项"
                else -> o.toString().take(40)
            }
        } catch (e: Exception) { "就绪" }
    }

    /** 点信号/观察行 → 详情卡：技术假设 + 当前状态 + （已持有则含持仓详细与操作） */
    private fun showEtfRowDetail(r: JSONObject) {
        val ctx = requireContext()
        val code = r.optString("code", "")
        lifecycleScope.launch {
            val (pos, cur) = withContext(Dispatchers.IO) {
                val list = runCatching { etfDao().getByPeriod(PERIOD_TYPE_ETF) }.getOrDefault(emptyList())
                val cache = runCatching { EtfCacheSync(ctx).loadLocalCache() }.getOrNull()
                val match = list.firstOrNull { p ->
                    p.stockCode.removePrefix("sh").removePrefix("sz") ==
                        code.removePrefix("sh").removePrefix("sz")
                }
                val price = lastCloseFrom(cache, code) ?: runCatching {
                    if (r.optDouble("close", 0.0) > 0) r.optDouble("close") else null
                }.getOrNull()
                match to price
            }
            if (!isAdded) return@launch
            val isDip = r.optBoolean("dip", false)
            val name = r.optString("name", code)
            val close = r.optDouble("close", 0.0)
            val pnlPct = if (pos != null && pos.avgBuyPrice > 0 && (cur ?: 0.0) > 0)
                (cur!! - pos.avgBuyPrice) / pos.avgBuyPrice * 100 else 0.0
            val sb = StringBuilder()
            sb.appendLine("$name  $code")
            sb.appendLine("收盘 ${"%.3f".format(close)}  |  距60日高 ${"%.1f".format(r.optDouble("dd60", 0.0))}%  |  RSI6 ${"%.0f".format(r.optDouble("rsi6", 0.0))}")
            sb.appendLine("SAR ${r.optString("sar", "-")} · MACD ${r.optString("macd", "-")} · OBV ${r.optString("obv", "-")}")
            val k = r.optString("kline", "-")
            if (k.isNotBlank() && k != "-") sb.appendLine("多日跌后K形态: $k")
            sb.appendLine(if (isDip) "◆ 今日可低吸信号（下一交易日可介入）" else "◇ 接近低吸区观察池（提前跟踪，等信号）")
            if (lastStrategyText.isNotBlank()) sb.appendLine("买点: $lastStrategyText")
            if (lastExitText.isNotBlank()) sb.appendLine("离场: $lastExitText")
            if (pos != null) {
                sb.appendLine()
                sb.appendLine("📦 当前持仓：")
                sb.appendLine("  数量 ${pos.quantity} × 成本 ${"%.3f".format(pos.avgBuyPrice)}")
                sb.appendLine("  现价 ${cur?.let { "%.3f".format(it) } ?: "—"}  |  浮动 ${if (pnlPct >= 0) "+" else ""}${"%.2f".format(pnlPct)}%")
                if (pos.avgBuyPrice > 0 && cur != null && cur > 0) {
                    val pnlAmt = (cur - pos.avgBuyPrice) * pos.quantity
                    sb.appendLine("  浮动额 ${if (pnlAmt >= 0) "+" else ""}${"%.0f".format(pnlAmt)}")
                }
                sb.append("  买入 ${pos.buyDate} · 点下方「✏️ 编辑持仓」可调整/清仓")
            } else {
                sb.appendLine()
                sb.append("未持有该标的 —— 若确认按信号买入，点「➕ 记入持仓」录入，报告将自动纳入统计。")
            }
            AlertDialog.Builder(ctx)
                .setTitle(if (isDip) "🎯 可低吸信号详情" else "👀 观察标的详情")
                .setMessage(sb.toString())
                .setNeutralButton(if (pos != null) "✏️ 编辑持仓" else "➕ 记入持仓") { _, _ -> addEtfPositionDialog(pos) }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    /** 各列配色（深色底上红涨绿跌，信号高亮） */
    private fun cellColor(idx: Int, v: String): Int {
        val dd = if (idx == 3) (v.toDoubleOrNull() ?: 0.0) else 0.0
        return when (idx) {
            0 -> Color.parseColor("#FFE0B2")            // 名称
            1 -> Color.parseColor("#CE93D8")            // 代码
            3 -> if (dd < -20) Color.parseColor("#FF7043") else Color.parseColor("#FFCC00")  // 回撤
            5, 6, 7 -> Color.parseColor("#E0E0E0")      // SAR/MACD/OBV 值本身带红绿字，正文亮灰即可
            8 -> if (v.contains("阳")) Color.parseColor("#FF8A80")
                 else if (v.contains("阴")) Color.parseColor("#64B5F6")
                 else Color.parseColor("#E0E0E0")       // 跌后K形态
            9 -> when {                                // 趋势图（2026-09-10 新增）
                v.startsWith("↑") -> Color.parseColor("#FF8A80")
                v.startsWith("↓") -> Color.parseColor("#64B5F6")
                else -> Color.parseColor("#B0BEC5")
            }
            10 -> if (v == "信号") Color.parseColor("#69F0AE") else Color.parseColor("#9E9E9E")
            else -> Color.parseColor("#E0E0E0")
        }
    }

    // ═══════════════════════════════════════════════════
    // 三周期同款入口按钮行（移植自 QuantFragmentBase.createButtonRow，配色/权重一致）
    // 📈选股 | 🔀Pipeline | 💰买卖评估 ▾ | 📦持仓 | 📊报告
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
        row.addView(mk("📈选股", "#E65100", 4f) { refresh() })              // 重跑本地 etf_dip 引擎（含行情自同步）
        row.addView(mk("🔀Pipeline", "#6A1B9A", 5f) { openEtfPipeline() })  // etf_dip XML 流程图/拓扑编辑器
        row.addView(mk("💰买卖评估 ▾", "#BF360C", 5f) { showTradeEvalMenu() })
        row.addView(mk("📦持仓", "#1565C0", 4f) { showEtfHoldings() })
        row.addView(mk("📊报告", "#455A64", 4f) { showEtfReport() })
        // 🧪 选股思路实验室：专门分析「如何选择 ETF」（五条件选股 + 买卖/做T + 回测 + 拟合修正）
        row.addView(mk("🧪思路", "#00838F", 4f) {
            EtfStrategyLabPanel.show(this@EtfDipFragment)
        })
        return row
    }

    /** 🔀 Pipeline：etf_dip usecase 分层流程图（与三周期同源，可进入拓扑编辑器改 XML） */
    private fun openEtfPipeline() {
        val ctx = requireContext()
        PipelineFlowChart.showFlowChart(
            context = ctx,
            useCaseId = "etf_dip",
            onOpenEditor = {
                runCatching {
                    startActivity(
                        Intent(ctx, com.chin.stockanalysis.strategy.topology.ui.TopologyEditorActivity::class.java)
                            .putExtra("usecase_id", "etf_dip")
                    )
                }.onFailure { e -> Log.w(TAG, "打开 etf_dip 拓扑编辑器失败: ${e.message}") }
            }
        )
    }

    /** 💰 买卖评估：读取本地引擎最新输出（门控+信号+观察池+离场规则），给出决策参考 */
    private fun showTradeEvalMenu() {
        val j = latestJson
        if (j == null) {
            AlertDialog.Builder(requireContext())
                .setTitle("💰 ETF 买卖评估")
                .setMessage("暂无执行结果。\n\n请先点「📈选股」同步行情并执行选股，再查看评估。")
                .setNegativeButton("关闭", null)
                .show()
            return
        }
        val gate = j.optJSONObject("gate")
        val gateOk = gate?.optBoolean("ok", false) ?: false
        val gateNote = gate?.optString("note", "").orEmpty()
        val gateDate = gate?.optString("date", "").orEmpty()
        val sb = StringBuilder()
        sb.appendLine("【门控】${if (gateOk) "✅ 大盘多头排列 —— 可执行低吸" else "⛔ 大盘未呈多头结构 —— 暂停低吸"}")
        if (gateNote.isNotBlank()) sb.appendLine("  $gateNote")
        if (gateDate.isNotBlank()) sb.appendLine("  判定截至 $gateDate")
        sb.appendLine()
        if (lastStrategyText.isNotBlank()) {
            sb.appendLine("【买入口径】").appendLine(lastStrategyText).appendLine()
        }
        if (lastExitText.isNotBlank()) {
            sb.appendLine("【离场规则】").appendLine(lastExitText).appendLine()
        }
        val sig = j.optJSONArray("signal_today")
        if (sig != null && sig.length() > 0) {
            sb.appendLine("【今日可低吸信号 ${sig.length()} 只】")
            for (i in 0 until sig.length()) {
                val r = sig.optJSONObject(i) ?: continue
                sb.appendLine("  · ${r.optString("name")} ${r.optString("code")}  收盘" +
                    "%.3f".format(r.optDouble("close", 0.0)) +
                    "  回撤60 " + "%.1f".format(r.optDouble("dd60", 0.0)) + "%")
            }
            sb.appendLine()
        }
        val app = j.optJSONArray("approach")
        if (app != null && app.length() > 0) {
            sb.appendLine("【接近低吸区观察 ${app.length()} 只】")
            for (i in 0 until app.length()) {
                val r = app.optJSONObject(i) ?: continue
                sb.appendLine("  · ${r.optString("name")} ${r.optString("code")}  收盘" +
                    "%.3f".format(r.optDouble("close", 0.0)) +
                    "  回撤60 " + "%.1f".format(r.optDouble("dd60", 0.0)) + "%")
            }
            sb.appendLine()
        }
        sb.appendLine("数据截至 $lastAsOfText · 依据最新本地行情与低位低吸规则")
        sb.append("以上为量化参考，实际买卖请自行复核。")
        AlertDialog.Builder(requireContext())
            .setTitle("💰 ETF 买卖评估")
            .setMessage(sb.toString())
            .setPositiveButton("重新选股", { _, _ -> refresh() })
            .setNegativeButton("关闭", null)
            .show()
    }

    // ═══════════════════════════════════════════════════
    // 📦 ETF 持仓（与三周期统一存 real_positions 表，periodType=EtfDipQuant 隔离）
    // ═══════════════════════════════════════════════════
    private fun etfDao() = StockDatabase.getInstance(requireContext()).realPositionDao()

    /** 展示 ETF 持仓列表：点击持仓行 → 编辑/清仓；底部「添加持仓」录入 */
    private fun showEtfHoldings() {
        val ctx = requireContext()
        lifecycleScope.launch {
            val (list, prices) = withContext(Dispatchers.IO) {
                val positions = runCatching { etfDao().getByPeriod(PERIOD_TYPE_ETF) }.getOrDefault(emptyList())
                val cache = runCatching { EtfCacheSync(ctx).loadLocalCache() }.getOrNull()
                positions to positions.associate { p -> p.stockCode to lastCloseFrom(cache, p.stockCode) }
            }
            if (!isAdded) return@launch
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(8), dp(18), dp(2))
            }
            if (list.isEmpty()) {
                box.addView(TextView(ctx).apply {
                    text = "（暂无 ETF 持仓）\n点下方「添加持仓」录入；保存后持仓盈亏进入「📊报告」汇总。"
                    textSize = 13f
                    setTextColor(Color.parseColor("#9E9E9E"))
                    setPadding(0, dp(6), 0, dp(6))
                })
            } else {
                for (pos in list) {
                    val cur = prices[pos.stockCode]
                        ?: (if (pos.currentPrice > 0) pos.currentPrice else pos.avgBuyPrice)
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
                        text = "数量 ${pos.quantity} × 成本 ${"%.3f".format(pos.avgBuyPrice)}  |  现价 " +
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
                .setTitle("📦 ETF 持仓（点持仓可编辑/清仓）")
                .setView(scroll)
                .setNeutralButton("➕ 添加持仓") { _, _ -> addEtfPositionDialog(null) }
                .setPositiveButton("关闭", null)
                .show()
        }
    }

    /** 持仓行操作：编辑数量/成本 或 卖出清仓（保留历史，标记 inactive） */
    private fun editOrSellDialog(pos: RealPositionEntity) {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle("${pos.stockName}  ${pos.stockCode}")
            .setItems(arrayOf("✏️ 编辑数量/成本", "💰 卖出/清仓")) { _, which ->
                when (which) {
                    0 -> addEtfPositionDialog(pos)
                    1 -> lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching { etfDao().markInactive(pos.id) }
                        }
                        Toast.makeText(ctx, "已清仓 ${pos.stockName}", Toast.LENGTH_SHORT).show()
                        if (isAdded) showEtfHoldings()
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 添加/编辑 ETF 持仓（编辑时携带 existing 回填） */
    private fun addEtfPositionDialog(existing: RealPositionEntity?) {
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
            addView(label("代码（sh512010 / 512010，5开头自动补 sh）"))
            addView(codeEt)
            addView(label("名称")); addView(nameEt)
            addView(label("数量（份）")); addView(qtyEt)
            addView(label("买入均价")); addView(costEt)
            addView(label("买入日期 yyyy-MM-dd")); addView(dateEt)
            setPadding(dp(18), 0, dp(18), 0)
        }
        AlertDialog.Builder(ctx)
            .setTitle(if (existing == null) "➕ 添加 ETF 持仓" else "✏️ 编辑 ETF 持仓")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val code = normalizeEtfCode(codeEt.text.toString())
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
                    periodType = PERIOD_TYPE_ETF
                )
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching { etfDao().insert(entity) }
                    }
                    Toast.makeText(ctx, "已保存 $name（$code）", Toast.LENGTH_SHORT).show()
                    if (isAdded) showEtfHoldings()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 代码归一：512010 → sh512010；159915 → sz159915；已带前缀原样返回 */
    private fun normalizeEtfCode(raw: String): String {
        var c = raw.trim().lowercase(Locale.US)
        if (c.startsWith("sh") || c.startsWith("sz") || c.startsWith("bj")) return c
        if (c.length == 6 && c.all { it.isDigit() }) {
            c = if (c.startsWith("5")) "sh$c" else "sz$c"
        }
        return c
    }

    /** 📊 报告：引擎口径 + 今日输出 + ETF 持仓浮动盈亏汇总 */
    private fun showEtfReport() {
        val ctx = requireContext()
        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                val positions = runCatching { etfDao().getByPeriod(PERIOD_TYPE_ETF) }.getOrDefault(emptyList())
                val cache = runCatching { EtfCacheSync(ctx).loadLocalCache() }.getOrNull()
                var cost = 0.0
                var mkt = 0.0
                for (p in positions) {
                    val cur = lastCloseFrom(cache, p.stockCode)
                        ?: (if (p.currentPrice > 0) p.currentPrice else p.avgBuyPrice)
                    cost += p.quantity * p.avgBuyPrice
                    mkt += p.quantity * cur
                }
                Triple(positions.size, cost, mkt)
            }
            if (!isAdded) return@launch
            val (cnt, cost, mkt) = data
            val pnl = mkt - cost
            val pnlPct = if (cost > 0) pnl / cost * 100 else 0.0
            val sb = StringBuilder()
            sb.appendLine("【策略】ETF 低位低吸（门控 → 信号 → 离场）")
            if (lastStrategyText.isNotBlank()) sb.appendLine("  买入口径: $lastStrategyText")
            if (lastExitText.isNotBlank()) sb.appendLine("  离场规则: $lastExitText")
            sb.appendLine()
            sb.appendLine("【今日选股输出】数据截至 $lastAsOfText")
            sb.appendLine("  可低吸信号 ${lastSigRows} 只 | 接近低吸区观察 ${lastWatchRows} 只")
            sb.appendLine()
            sb.appendLine("【ETF 持仓（$cnt 笔）】")
            if (cnt == 0) {
                sb.appendLine("  暂无持仓，点「📦持仓」录入。")
            } else {
                sb.appendLine("  总成本    ${"%.0f".format(cost)}")
                sb.appendLine("  参考市值  ${"%.0f".format(mkt)}")
                sb.appendLine("  浮动盈亏  ${"%.0f".format(pnl)}（${if (pnlPct >= 0) "+" else ""}${"%.2f".format(pnlPct)}%）")
                sb.append("  注：现价取本地 etf_cache.json 最新收盘，盘后口径。")
            }
            AlertDialog.Builder(ctx)
                .setTitle("📊 ETF 报告")
                .setMessage(sb.toString())
                .setPositiveButton("重新选股", { _, _ -> refresh() })
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    /** 从 etf_cache.json（与引擎同一数据源）取标的最新收盘价 */
    private fun lastCloseFrom(cache: JSONObject?, code: String): Double? {
        if (cache == null) return null
        val snaps = cache.optJSONObject(code)?.optJSONArray("snaps") ?: return null
        if (snaps.length() == 0) return null
        return snaps.optJSONObject(snaps.length() - 1).optDouble("close", 0.0).takeIf { it > 0 }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
