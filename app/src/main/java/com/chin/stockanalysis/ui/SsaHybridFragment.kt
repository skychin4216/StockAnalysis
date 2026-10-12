package com.chin.stockanalysis.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.agent.stock.StockAnalysisAgent
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import com.chin.stockanalysis.strategy.data.HistoricalDataFetcher
import com.chin.stockanalysis.strategy.ssa.SsaHybridEngine
import com.chin.stockanalysis.strategy.ssa.SsaHybridEngine.Bar
import com.chin.stockanalysis.strategy.ssa.SsaPoolStore
import com.chin.stockanalysis.strategy.ssa.SsaRecoPipeline
import com.chin.stockanalysis.strategy.ssa.SsaRecoStore
import com.chin.stockanalysis.stock.database.StockDatabase
import com.github.mikephil.charting.charts.BarLineChartBase
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.ChartTouchListener.ChartGesture
import com.github.mikephil.charting.listener.OnChartGestureListener
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * ## 🧭 SSA 混合策略 + 次日建议（PC 172 票全池回测固化）
 *
 * trend 斜率向上开买入窗口、窗口中 cycle 谷底买回；cycle 峰顶止盈、trend 死叉兜底；
 * 每根已收盘K给出次日 买入/卖出/持有/观望 建议，并用次日开盘成交滚动评估命中率；
 * 支持底仓比例（斜率转正买、死叉卖的不动仓）。信号只用历史，t+1 开盘成交，全程无未来函数。
 */
class SsaHybridFragment : Fragment() {

    private val RED = Color.parseColor("#E53935")
    private val GREEN = Color.parseColor("#43A047")
    private val BLUE = Color.parseColor("#1976D2")
    private val ORANGE = Color.parseColor("#E65100")
    private val TEAL = Color.parseColor("#00897B")
    private val PURPLE = Color.parseColor("#9C27B0")
    private val GRAY = Color.parseColor("#999999")
    private val BLACK = Color.parseColor("#333333")

    private lateinit var codeInput: EditText
    private lateinit var runBtn: Button
    private lateinit var statusTv: TextView
    private lateinit var resultBox: LinearLayout
    private lateinit var etShort: EditText
    private lateinit var etLong: EditText
    private lateinit var etCycle: EditText
    private lateinit var etEma: EditText
    private lateinit var etBase: EditText
    private lateinit var etBars: EditText
    private lateinit var autoFit: CheckBox
    private lateinit var optFit: CheckBox
    private lateinit var modeBtnSingle: Button
    private lateinit var modeBtnPool: Button
    private lateinit var inputCard: LinearLayout
    private lateinit var poolTools: LinearLayout
    private lateinit var poolBox: LinearLayout
    private lateinit var poolFilterRow: LinearLayout
    private lateinit var lastUpdTv: TextView
    private var inPoolMode = false
    private var poolFilter = "ALL"
    // ── 每日荐股 ──
    private lateinit var modeBtnReco: Button
    private lateinit var recoTools: LinearLayout
    private lateinit var recoTools2: LinearLayout
    private lateinit var recoInfoTv: TextView
    private lateinit var recoFilterRow: LinearLayout
    private lateinit var recoProfRow: LinearLayout
    private lateinit var recoBox: LinearLayout
    private var inRecoMode = false
    /** 建议筛选：-1=全部，否则 SsaHybridEngine.BUY/SELL/HOLD_POS/HOLD_EMPTY */
    private var recoFilter = -1
    /** 票性筛选：ALL/TREND/MIXED/OSCILLATION/SLOWBULL */
    private var recoProfFilter = "ALL"
    private var recoBusy = false
    private val recoNameCache = HashMap<String, String>()
    private val recoPipeline by lazy { SsaRecoPipeline(requireContext()) }
    // ── 板块分析 ──
    private lateinit var sectorBox: LinearLayout
    private var inSectorDetail = false   // false=板块列表 true=板块成分股详情

    // ── 荐股备份/恢复（SAF：备份=创建zip，恢复=选zip覆盖） ──
    private val backupLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) onBackupTo(uri)
        }
    private val restoreLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) confirmRestore(uri)
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F7F7F7"))
        }
        val scroll = ScrollView(ctx)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(16))
        }
        scroll.addView(box)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT
        ))

        box.addView(sectionTitle("🧭 SSA 混合策略 · 次日建议"))
        box.addView(hint("短trend斜率向上=买入窗口，窗口内 cycle 谷底买入 + cycle 峰顶/死叉卖出（trend/cycle 经 EMA 平滑）。" +
            "每根收盘K给出次日【买入/卖出/持有/观望】建议，次日开盘成交，滚动统计命中率，全程无未来函数。"))

        val inputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(4))
        }
        codeInput = EditText(ctx).apply {
            hint = "股票代号 / 名称，如 600176 或 中国巨石"
            textSize = 13f; isSingleLine = true
            setTextColor(BLACK); setHintTextColor(Color.parseColor("#AAAAAA"))
            background = roundedBg("#F5F5F5", "#DDDDDD", 6)
            setPadding(dp(10), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(8) }
        }
        inputRow.addView(codeInput)
        runBtn = Button(ctx).apply {
            text = "开始分析"; textSize = 13f; setTextColor(Color.WHITE); setBackgroundColor(TEAL)
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40))
            setOnClickListener { onRun() }
        }
        inputRow.addView(runBtn)
        // 模式切换：单票分析 / 持仓池
        val modeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(4))
        }
        modeBtnSingle = Button(ctx).apply {
            text = "🔍 单票分析"; textSize = 12f; setTextColor(Color.WHITE); setBackgroundColor(TEAL)
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(6) }
            setOnClickListener { switchMode(0) }
        }
        modeBtnPool = Button(ctx).apply {
            text = "📋 持仓池"; textSize = 12f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#B0BEC5"))
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(6) }
            setOnClickListener { switchMode(1) }
        }
        modeBtnReco = Button(ctx).apply {
            text = "📈 每日荐股"; textSize = 12f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#B0BEC5"))
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
            setOnClickListener { switchMode(2) }
        }
        modeRow.addView(modeBtnSingle); modeRow.addView(modeBtnPool); modeRow.addView(modeBtnReco)
        box.addView(modeRow)

        val inputCard0 = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(inputCard0)
        inputCard0.addView(inputRow)

        // 参数行
        val hsv = HorizontalScrollView(ctx)
        val prow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "短trend" to "10", "长trend" to "20", "周期L" to "60",
            "EMA" to "2", "底仓%" to "0", "K线根数" to "300"
        ).forEach { (cap, dft) ->
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = dp(6) }
            }
            cell.addView(TextView(ctx).apply { text = cap; textSize = 9f; setTextColor(GRAY) })
            val et = EditText(ctx).apply {
                setText(dft); textSize = 12f; isSingleLine = true
                setTextColor(BLACK)
                background = roundedBg("#FFFFFF", "#CCCCCC", 6)
                setPadding(dp(4), 0, dp(4), 0)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(32))
            }
            cell.addView(et); prow.addView(cell)
            when (cap) {
                "短trend" -> etShort = et
                "长trend" -> etLong = et
                "周期L" -> etCycle = et
                "EMA" -> etEma = et
                "底仓%" -> etBase = et
                "K线根数" -> etBars = et
            }
        }
        hsv.addView(prow); inputCard0.addView(hsv)
        inputCard0.addView(hint("默认参数来自 PC 全池 172 票统计；高波趋势票建议 短10/长20/周期60；" +
            "底仓%=100 即只做大波段（斜率转正买、死叉卖）。"))
        autoFit = CheckBox(ctx).apply {
            text = "按票性自动预填推荐参数（趋势 t5/10+c60 · 混合/震荡 t30/60+c60 · 慢牛 +底仓30%）"
            textSize = 10.5f; setTextColor(BLACK); isChecked = true
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        inputCard0.addView(autoFit)
        optFit = CheckBox(ctx).apply {
            text = "🔍 参数寻优（网格扫描全组合自动选最优，忽略上方 t/周期 手动值）"
            textSize = 10.5f; setTextColor(BLACK)
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        inputCard0.addView(optFit)
        inputCard = inputCard0
        poolTools = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(2))
        }
        val addBtn = Button(ctx).apply {
            text = "＋添加"; textSize = 12f; setTextColor(Color.WHITE); setBackgroundColor(BLUE)
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(6) }
            setOnClickListener { onPoolAdd() }
        }
        val updBtn = Button(ctx).apply {
            text = "▶ 一键更新全部"; textSize = 12f; setTextColor(Color.WHITE); setBackgroundColor(TEAL)
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(6) }
            setOnClickListener { onPoolUpdate() }
        }
        poolTools.addView(addBtn); poolTools.addView(updBtn)
        box.addView(poolTools)
        poolTools.visibility = View.GONE
        lastUpdTv = TextView(ctx).apply {
            textSize = 10.5f; setTextColor(GRAY); setPadding(dp(2), dp(4), dp(2), 0)
        }
        box.addView(lastUpdTv)
        poolFilterRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(2))
        }
        box.addView(poolFilterRow)
        poolFilterRow.visibility = View.GONE
        poolBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(poolBox)
        poolBox.visibility = View.GONE

        // ── 每日荐股容器组 ──
        recoTools = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(2))
        }
        val recoBuildBtn = Button(ctx).apply {
            text = "🏗 全量构建"; textSize = 12f; setTextColor(Color.WHITE); setBackgroundColor(BLUE)
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(6) }
            setOnClickListener { onRecoBuild() }
        }
        val recoUpdBtn = Button(ctx).apply {
            text = "▶ 每日更新"; textSize = 12f; setTextColor(Color.WHITE); setBackgroundColor(TEAL)
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
            setOnClickListener { onRecoRefresh() }
        }
        recoTools.addView(recoBuildBtn); recoTools.addView(recoUpdBtn)
        box.addView(recoTools)
        recoTools.visibility = View.GONE
        // 备份/恢复行（低频，第二行）
        recoTools2 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }
        val recoBakBtn = Button(ctx).apply {
            text = "💾 备份"; textSize = 11f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#78909C"))
            layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginEnd = dp(6) }
            setOnClickListener { onRecoBackupPick() }
        }
        val recoRstBtn = Button(ctx).apply {
            text = "📥 恢复"; textSize = 11f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#78909C"))
            layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f)
            setOnClickListener { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }
        }
        val recoSecBtn = Button(ctx).apply {
            text = "📊 板块"; textSize = 11f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#5C6BC0"))
            layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginStart = dp(6) }
            setOnClickListener { renderSectorList() }
        }
        recoTools2.addView(recoBakBtn); recoTools2.addView(recoRstBtn); recoTools2.addView(recoSecBtn)
        box.addView(recoTools2)
        recoTools2.visibility = View.GONE
        recoInfoTv = TextView(ctx).apply {
            textSize = 10.5f; setTextColor(GRAY); setPadding(dp(2), dp(4), dp(2), 0)
        }
        box.addView(recoInfoTv)
        recoInfoTv.visibility = View.GONE
        recoFilterRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(2))
        }
        box.addView(recoFilterRow)
        recoFilterRow.visibility = View.GONE
        recoProfRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(2))
        }
        box.addView(recoProfRow)
        recoProfRow.visibility = View.GONE
        recoBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(recoBox)
        recoBox.visibility = View.GONE
        sectorBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(sectorBox)
        sectorBox.visibility = View.GONE

        statusTv = TextView(ctx).apply { textSize = 11f; setTextColor(GRAY); setPadding(dp(2), dp(6), dp(2), dp(2)) }
        box.addView(statusTv)
        resultBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(resultBox)
        box.addView(hint("⚠️ 逐K滚动 SSA，信号只用历史数据；SSA 为历史时序分解不预测精准价格；" +
            "末尾 L 根K有边缘效应；结果不构成投资建议。"))
        return root
    }

    // ═══════════════════════ 执行 ═══════════════════════

    private fun onRun() {
        val raw = codeInput.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) { status("请输入股票代号或名称"); return }
        val s = etShort.text.toString().trim().toIntOrNull()
        val l = etLong.text.toString().trim().toIntOrNull()
        val c = etCycle.text.toString().trim().toIntOrNull()
        val ema = etEma.text.toString().trim().toIntOrNull()
        val basePct = etBase.text.toString().trim().toDoubleOrNull()
        val nbars = etBars.text.toString().trim().toIntOrNull()
        if (s == null || l == null || c == null || ema == null || basePct == null || nbars == null) {
            status("⚠️ 参数需全部为数字"); return
        }
        var p = SsaHybridEngine.Params(s, l, c, ema, (basePct / 100.0).coerceIn(0.0, 1.0))
        p.validate()?.let { status("⚠️ $it"); return }
        val auto = autoFit.isChecked
        runBtn.isEnabled = false
        resultBox.removeAllViews()
        lifecycleScope.launch {
            try {
                status("⏳ 解析股票 / 加载日K…")
                val (code, name) = withContext(Dispatchers.IO) { resolveCode(raw) }
                if (code == null) { status("⚠️ 未找到「$raw」"); return@launch }
                val all = withContext(Dispatchers.IO) { loadDaily(code) }
                if (all == null) { status("⚠️ $name 日K不足"); return@launch }
                val bars = if (all.size > nbars) all.takeLast(nbars) else all
                if (auto) {
                    status("⏳ 票性识别中（L30 滚动 SSA）…")
                    val prof = withContext(Dispatchers.Default) { runCatching { SsaHybridEngine.classifyOnly(bars) } }
                        .getOrElse { status("⚠️ 票性识别失败：${it.message}"); return@launch }
                    p = SsaHybridEngine.recommendedParams(prof.label)
                    prefillParams(p)
                    status("🏷️ 识别为${labelCn(prof.label)} → 已预填 t${p.trendShort}/${p.trendLong}+c${p.cycleL}" +
                        " EMA${p.smoothSpan} 底仓${(p.baseFraction * 100).toInt()}%，开始回测")
                }
                if (optFit.isChecked) {
                    status("⏳ 参数寻优：滚动 SSA 网格扫描（trend×cycle 全组合，约 20~60 秒）…")
                    val t0 = System.currentTimeMillis()
                    val grid = withContext(Dispatchers.Default) {
                        runCatching { SsaHybridEngine.scanGrid(bars, p) }
                    }.getOrElse { status("⚠️ 寻优失败：${it.message}"); return@launch }
                    if (grid.isEmpty()) { status("⚠️ K线不足，无可扫描组合"); return@launch }
                    val best = grid[0]
                    p = best.params
                    prefillParams(p)
                    val mono = TextView(requireContext()).apply {
                        typeface = Typeface.MONOSPACE; textSize = 11f; setTextColor(BLACK)
                        text = buildString {
                            appendLine("🔍 参数寻优 top5（共 ${grid.size} 组 · ${"%.1f".format((System.currentTimeMillis() - t0) / 1000.0)}s）")
                            grid.take(5).forEachIndexed { i, row ->
                                appendLine(String.format("%s t%-3d/%-3d c%-3d 收益%+7.1f%% 回撤%6.1f%% %2d笔 胜率%3.0f%%",
                                    if (i == 0) "🏆" else "  ",
                                    row.params.trendShort, row.params.trendLong, row.params.cycleL,
                                    row.total, row.mdd, row.tradeCount, row.winRate))
                            }
                        }
                    }
                    resultBox.addView(card { it.addView(mono) })
                    status("🏆 最优 t${p.trendShort}/${p.trendLong}+c${p.cycleL}（+${"%.1f".format(best.total)}%），已回填，开始完整回测")
                }
                if (bars.size < maxOf(p.trendLong, p.cycleL, 30) + 30) {
                    status("⚠️ K线仅 ${bars.size} 根，需 ≥${maxOf(p.trendLong, p.cycleL, 30) + 30}"); return@launch
                }
                status("⏳ 逐K滚动 SSA + 回测（${bars.size} 根，手机约需 10~40 秒）…")
                val r = withContext(Dispatchers.Default) { runCatching { SsaHybridEngine.run(bars, p) } }
                r.onSuccess { render(name ?: code, code, it) }
                    .onFailure { status("⚠️ 回测失败：${it.message}") }
                if (r.isSuccess) status("✅ 完成：${name ?: code}($code) 截至 ${r.getOrNull()?.latestDate}")
            } finally {
                runBtn.isEnabled = true
            }
        }
    }

    // ═══════════════════════ 持仓池 ═══════════════════════

    /** m: 0=单票分析 1=持仓池 2=每日荐股 */
    private fun switchMode(m: Int) {
        val pool = m == 1
        val reco = m == 2
        inPoolMode = pool
        inRecoMode = reco
        inputCard.visibility = if (pool || reco) View.GONE else View.VISIBLE
        resultBox.visibility = if (pool || reco) View.GONE else View.VISIBLE
        poolTools.visibility = if (pool) View.VISIBLE else View.GONE
        poolFilterRow.visibility = if (pool) View.VISIBLE else View.GONE
        poolBox.visibility = if (pool) View.VISIBLE else View.GONE
        recoTools.visibility = if (reco) View.VISIBLE else View.GONE
        recoTools2.visibility = if (reco) View.VISIBLE else View.GONE
        recoInfoTv.visibility = if (reco) View.VISIBLE else View.GONE
        recoFilterRow.visibility = if (reco) View.VISIBLE else View.GONE
        recoProfRow.visibility = if (reco) View.VISIBLE else View.GONE
        recoBox.visibility = if (reco) View.VISIBLE else View.GONE
        sectorBox.visibility = if (reco) View.VISIBLE else View.GONE
        if (reco) { inSectorDetail = false; sectorBox.removeAllViews() }
        val idle = Color.parseColor("#B0BEC5")
        modeBtnSingle.setBackgroundColor(if (!pool && !reco) TEAL else idle)
        modeBtnPool.setBackgroundColor(if (pool) TEAL else idle)
        modeBtnReco.setBackgroundColor(if (reco) TEAL else idle)
        if (pool) { resultBox.removeAllViews(); status(""); renderPool() }
        if (reco) { status(""); renderReco() }
    }

    private fun advWord(a: Int): Triple<String, Int, String> = when (a) {
        SsaHybridEngine.BUY -> Triple("买入", GREEN, "adv")
        SsaHybridEngine.SELL -> Triple("卖出", RED, "adv")
        SsaHybridEngine.HOLD_POS -> Triple("持有", BLUE, "adv")
        else -> Triple("观望", GRAY, "adv")
    }

    /** 添加持仓票：解析 → 票性识别 → 锁定推荐参数入库 → 立即跑一次生成快照 */
    private fun onPoolAdd() {
        val ctx = requireContext()
        val et = EditText(ctx).apply {
            hint = "股票代号 / 名称"; textSize = 13f; isSingleLine = true
            setTextColor(BLACK); setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("＋ 添加持仓票（自动识别票性并锁定参数）")
            .setView(et)
            .setPositiveButton("加入") { _, _ ->
                val raw = et.text.toString().trim()
                if (raw.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    status("⏳ 解析 $raw …")
                    val (code, name) = withContext(Dispatchers.IO) { resolveCode(raw) }
                    if (code == null) { status("⚠️ 未找到「$raw」"); return@launch }
                    val members = SsaPoolStore.load(ctx)
                    if (members.any { it.code == code }) { status("⚠️ ${name ?: code} 已在池中"); return@launch }
                    val all = withContext(Dispatchers.IO) { loadDaily(code) }
                    if (all == null) { status("⚠️ ${name ?: code} 日K不足"); return@launch }
                    val bars = if (all.size > 300) all.takeLast(300) else all
                    status("🏷️ 识别 ${name ?: code} 票性…")
                    val prof = withContext(Dispatchers.Default) { runCatching { SsaHybridEngine.classifyOnly(bars) } }
                        .getOrElse { status("⚠️ 票性识别失败：${it.message}"); return@launch }
                    val p = SsaHybridEngine.recommendedParams(prof.label)
                    val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.CHINA)
                        .format(java.util.Date())
                    val m = SsaPoolStore.PoolMember(code, name ?: code, today,
                        p.trendShort, p.trendLong, p.cycleL, p.smoothSpan, (p.baseFraction * 100).toInt())
                    SsaPoolStore.add(ctx, m)
                    status("✅ 已加入 ${name ?: code}($code) · ${labelCn(prof.label)} · t${p.trendShort}/${p.trendLong}+c${p.cycleL}，正在生成首份快照…")
                    val snap = updateOne(m, 300)
                    val snaps = SsaPoolStore.loadSnapshot(ctx).toMutableList()
                    snaps.removeAll { it.code == code }
                    if (snap != null) snaps.add(snap)
                    SsaPoolStore.saveSnapshot(ctx, snaps)
                    renderPool()
                    status(if (snap != null) "✅ ${name ?: code} 快照完成（建议：${advWord(snap.advice).first}）"
                           else "⚠️ ${name ?: code} 已入池，但首份快照生成失败（K线不足），可稍后一键更新")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 单票更新：最新日K + 锁定参数重跑 → 快照（含近30日建议历史） */
    private suspend fun updateOne(m: SsaPoolStore.PoolMember, nbars: Int): SsaPoolStore.TicketSnapshot? {
        val all = withContext(Dispatchers.IO) { loadDaily(m.code) } ?: return null
        val bars = if (all.size > nbars) all.takeLast(nbars) else all
        if (bars.size < maxOf(m.tl, m.cy, 30) + 30) return null
        val r = withContext(Dispatchers.Default) { runCatching { SsaHybridEngine.run(bars, m.params()) } }
            .getOrElse { return null }
        val now = java.text.SimpleDateFormat("yyyyMMdd HH:mm", java.util.Locale.CHINA)
            .format(java.util.Date())
        return SsaPoolStore.TicketSnapshot(
            m.code, m.name, r.latestAdvice, r.latestDate, r.latestClose,
            r.stats.totalReturnPct, r.stats.maxDrawdownPct, r.profile.label, now,
            r.recentAdvices.map { SsaPoolStore.HistAdvice(it.date, it.advice, it.close, it.r1, it.r3, it.r5) })
    }

    /** 一键更新全部：逐票拉最新K线 → 锁定参数重跑 → 覆盖快照 */
    private fun onPoolUpdate() {
        val ctx = requireContext()
        val members = SsaPoolStore.load(ctx)
        if (members.isEmpty()) { status("⚠️ 池为空，先【＋添加】持仓票"); return }
        val nbars = etBars.text.toString().trim().toIntOrNull() ?: 300
        lifecycleScope.launch {
            members.forEachIndexed { i, m ->
                status("⏳ 池更新 ${i + 1}/${members.size}：${m.name}(${m.code})…")
                val snap = updateOne(m, nbars)
                val snaps = SsaPoolStore.loadSnapshot(ctx).toMutableList()
                snaps.removeAll { it.code == m.code }
                if (snap != null) snaps.add(snap)
                SsaPoolStore.saveSnapshot(ctx, snaps)
            }
            status("✅ 池更新完成（${members.size} 票）")
            renderPool()
        }
    }

    private fun renderPool() {
        val ctx = requireContext()
        poolBox.removeAllViews()
        val members = SsaPoolStore.load(ctx)
        val snaps = SsaPoolStore.loadSnapshot(ctx)
        lastUpdTv.text = "上次更新：${snaps.maxOfOrNull { it.updated } ?: "从未"} · 池内 ${members.size} 票 · 更新=拉最新K线重跑并刷新次日建议"
        // 票性筛选 chips（带计数；未生成快照的票仅在"全部"中显示）
        val profOf = { code: String -> snaps.find { it.code == code }?.prof }
        val cntTREND = members.count { profOf(it.code) == "TREND" }
        val cntMIXED = members.count { profOf(it.code) == "MIXED" }
        val cntOSC = members.count { profOf(it.code) == "OSCILLATION" }
        val cntSLOW = members.count { profOf(it.code) == "SLOWBULL" }
        poolFilterRow.removeAllViews()
        listOf("ALL" to "全部 ${members.size}", "TREND" to "趋势 $cntTREND",
            "MIXED" to "混合 $cntMIXED", "OSCILLATION" to "震荡 $cntOSC", "SLOWBULL" to "慢牛 $cntSLOW"
        ).forEach { (key, label) ->
            val on = poolFilter == key
            poolFilterRow.addView(Button(ctx).apply {
                text = label; textSize = 10.5f
                setTextColor(if (on) Color.WHITE else GRAY)
                setBackgroundColor(if (on) TEAL else Color.parseColor("#EFEFEF"))
                setPadding(dp(2), 0, dp(2), 0)
                layoutParams = LinearLayout.LayoutParams(0, dp(30), 1f).apply { marginEnd = dp(4) }
                setOnClickListener { poolFilter = key; renderPool() }
            })
        }
        val shown = members.filter { m ->
            poolFilter == "ALL" || profOf(m.code) == poolFilter
        }
        if (members.isEmpty()) {
            poolBox.addView(hint("池为空：点【＋添加】加入持仓票（自动识别票性并锁定参数），" +
                "之后每天点【▶ 一键更新全部】即可刷新图表、建议与建议历史验证。"))
            return
        }
        if (shown.isEmpty()) {
            poolBox.addView(hint("当前筛选「${labelCn(poolFilter)}」下暂无持仓票（未生成快照的票仅在「全部」中显示）"))
            return
        }
        shown.forEach { m -> poolBox.addView(poolRow(m, snaps.find { it.code == m.code })) }
    }

    private fun poolRow(m: SsaPoolStore.PoolMember, s: SsaPoolStore.TicketSnapshot?): View {
        val ctx = requireContext()
        return card {
            // 行1：名称+代码 | 建议徽章
            val row1 = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            row1.addView(TextView(ctx).apply {
                text = "${m.name} (${m.code})"; textSize = 15f; setTextColor(BLACK)
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (s != null) {
                val (w1, c1, _) = advWord(s.advice)
                row1.addView(TextView(ctx).apply {
                    text = "▶ $w1"; textSize = 15f; setTextColor(c1)
                    setTypeface(typeface, Typeface.BOLD); setPadding(dp(8), 0, 0, 0)
                })
            }
            it.addView(row1)
            // 行2：摘要
            it.addView(TextView(ctx).apply {
                text = if (s == null) "尚未生成快照：点【更新】跑一次"
                else "截至 ${s.date} 收盘 ${fmt2(s.close)} · ${labelCn(s.prof)} · 回测收益 ${pct(s.ret)} · 回撤 ${pct(s.mdd)}"
                textSize = 11f; setTextColor(GRAY); setPadding(0, dp(4), 0, 0)
            })
            // 行3：近30日建议命中摘要（3日口径）
            if (s != null && s.hist.isNotEmpty()) {
                val bh = s.hist.filter { it.advice == SsaHybridEngine.BUY && it.r3 != null }
                val sh = s.hist.filter { it.advice == SsaHybridEngine.SELL && it.r3 != null }
                val bHit = bh.count { (it.r3 ?: 0.0) > 0 }; val sHit = sh.count { (it.r3 ?: 0.0) < 0 }
                it.addView(TextView(ctx).apply {
                    text = "近30日建议 ${s.hist.size} 条 · 买入3日命中 $bHit/${bh.size}" +
                        (if (sh.isNotEmpty()) " · 卖出3日命中 $sHit/${sh.size}" else "") +
                        " · 最新 ${advWord(s.advice).first}"
                    textSize = 10.5f; setTextColor(GRAY); setPadding(0, dp(2), 0, dp(2))
                })
            }
            // 行4：操作按钮
            val row4 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
            fun smallBtn(text: String, color: Int, weight: Float, onClick: () -> Unit) =
                Button(ctx).apply {
                    this.text = text; textSize = 11f; setTextColor(Color.WHITE); setBackgroundColor(color)
                    layoutParams = LinearLayout.LayoutParams(0, dp(32), weight)
                        .apply { marginEnd = dp(6) }
                    setOnClickListener { onClick() }
                }
            row4.addView(smallBtn("📊 详情", TEAL, 1f) {
                switchMode(0)
                codeInput.setText(m.code)
                prefillParams(m.params())
                status("已载入 ${m.name}(${m.code}) 锁定参数，分析中…")
                onRun()
            })
            row4.addView(smallBtn("🔄 更新", BLUE, 1f) {
                lifecycleScope.launch {
                    status("⏳ 更新 ${m.name}(${m.code})…")
                    val snap = updateOne(m, etBars.text.toString().trim().toIntOrNull() ?: 300)
                    val snaps = SsaPoolStore.loadSnapshot(ctx).toMutableList()
                    snaps.removeAll { it.code == m.code }
                    if (snap != null) snaps.add(snap)
                    SsaPoolStore.saveSnapshot(ctx, snaps)
                    renderPool()
                    status(if (snap != null) "✅ ${m.name} 已更新（建议：${advWord(snap.advice).first}）" else "⚠️ ${m.name} 更新失败")
                }
            })
            row4.addView(smallBtn("🗑 移除", RED, 1f) {
                SsaPoolStore.remove(ctx, m.code); renderPool(); status("已移除 ${m.name}(${m.code})")
            })
            it.addView(row4)
        }
    }



    // ═══════════════════════ 每日荐股 ═══════════════════════

    /** 每日荐股页：读缓存卡片即时渲染（无网络）；全量构建/每日更新手动触发 */
    private fun renderReco() {
        val ctx = requireContext()
        lifecycleScope.launch {
            val index = withContext(Dispatchers.IO) { recoPipeline.store.loadIndex() }
            val cards = withContext(Dispatchers.IO) { recoPipeline.store.loadLatestCards() }
            recoInfoTv.text = "已建 ${index.size}/688 票 · 卡片 ${cards.size} 份 · 数据截至 " +
                (index.values.maxOfOrNull { it.lastDate } ?: "—") +
                " · 全量构建约20~40分钟（可中断续跑），每日更新约2~5分钟"
            recoBox.removeAllViews()
            if (cards.isEmpty()) {
                recoBox.addView(hint("暂无荐股卡片。点【🏗 全量构建】初始化 688 核心池" +
                    "（逐票识别票性→锁定推荐参数→全量回测，可随时中断、下次自动续跑），" +
                    "之后每天点【▶ 每日更新】增量步进即可（无新K线自动跳过）。"))
                if (index.isEmpty()) return@launch
                recoBox.addView(hint("已建 ${index.size} 票但无卡片（构建中途停止），点【▶ 每日更新】补齐。"))
            }
            // 建议筛选 chips（带计数）
            val cntAdv = { a: Int -> cards.count { it.snap.advice == a } }
            recoFilterRow.removeAllViews()
            listOf(-1 to "全部 ${cards.size}",
                SsaHybridEngine.BUY to "买入 ${cntAdv(SsaHybridEngine.BUY)}",
                SsaHybridEngine.SELL to "卖出 ${cntAdv(SsaHybridEngine.SELL)}",
                SsaHybridEngine.HOLD_POS to "持有 ${cntAdv(SsaHybridEngine.HOLD_POS)}",
                SsaHybridEngine.HOLD_EMPTY to "观望 ${cntAdv(SsaHybridEngine.HOLD_EMPTY)}"
            ).forEach { (key, label) ->
                recoFilterRow.addView(recoChip(label, recoFilter == key) { recoFilter = key; renderReco() })
            }
            // 票性筛选 chips（带计数）
            val cntProf = { l: String -> cards.count { it.snap.profile.label == l } }
            recoProfRow.removeAllViews()
            listOf("ALL" to "全部", "TREND" to "趋势 ${cntProf("TREND")}",
                "MIXED" to "混合 ${cntProf("MIXED")}", "OSCILLATION" to "震荡 ${cntProf("OSCILLATION")}",
                "SLOWBULL" to "慢牛 ${cntProf("SLOWBULL")}"
            ).forEach { (key, label) ->
                recoProfRow.addView(recoChip(label, recoProfFilter == key) { recoProfFilter = key; renderReco() })
            }
            val order = mapOf(SsaHybridEngine.BUY to 0, SsaHybridEngine.SELL to 1,
                SsaHybridEngine.HOLD_POS to 2, SsaHybridEngine.HOLD_EMPTY to 3)
            val shown = cards.filter {
                (recoFilter == -1 || it.snap.advice == recoFilter) &&
                    (recoProfFilter == "ALL" || it.snap.profile.label == recoProfFilter)
            }.sortedWith(compareBy({ order[it.snap.advice] ?: 9 }, { it.code }))
            if (shown.isEmpty() && cards.isNotEmpty()) {
                recoBox.addView(hint("当前筛选下暂无荐股卡片"))
                return@launch
            }
            // 名称解析（缓存优先，缺失的查基础库）
            val missing = shown.map { it.code }.filter { it !in recoNameCache }
            if (missing.isNotEmpty()) {
                val db = StockDatabase.getInstance(ctx)
                withContext(Dispatchers.IO) {
                    missing.forEach { c ->
                        val nm = runCatching { db.stockBasicDao().getByCode(c)?.name }.getOrNull()
                        if (!nm.isNullOrBlank()) recoNameCache[c] = nm
                    }
                }
            }
            shown.forEach { c -> recoBox.addView(recoRow(c, recoNameCache[c.code] ?: c.code)) }
        }
    }

    private fun recoChip(label: String, on: Boolean, onClick: () -> Unit): Button =
        Button(requireContext()).apply {
            text = label; textSize = 10.5f
            setTextColor(if (on) Color.WHITE else GRAY)
            setBackgroundColor(if (on) TEAL else Color.parseColor("#EFEFEF"))
            setPadding(dp(2), 0, dp(2), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(30), 1f).apply { marginEnd = dp(4) }
            setOnClickListener { onClick() }
        }

    private fun recoRow(c: SsaRecoStore.RecoCard, name: String): View {
        val ctx = requireContext()
        val s = c.snap
        val (w, ac, _) = advWord(s.advice)
        val p = s.params
        return card {
            // 行1：名称+代码 | 建议徽章
            val row1 = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            row1.addView(TextView(ctx).apply {
                text = "$name (${c.code})"; textSize = 15f; setTextColor(BLACK)
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row1.addView(TextView(ctx).apply {
                text = "▶ $w"; textSize = 15f; setTextColor(ac)
                setTypeface(typeface, Typeface.BOLD); setPadding(dp(8), 0, 0, 0)
            })
            it.addView(row1)
            // 行2：摘要
            it.addView(TextView(ctx).apply {
                text = "截至 ${s.date} 收盘 ${fmt2(s.close)} · ${labelCn(s.profile.label)} · " +
                    "t${p.trendShort}/${p.trendLong}+c${p.cycleL} 底仓${(p.baseFraction * 100).toInt()}%"
                textSize = 11f; setTextColor(GRAY); setPadding(0, dp(4), 0, 0)
            })
            // 行3：近30日建议命中摘要 + 建议5日均收益
            val hist = s.recentAdvices
            if (hist.isNotEmpty()) {
                val bh = hist.filter { it.advice == SsaHybridEngine.BUY && it.r3 != null }
                val sh = hist.filter { it.advice == SsaHybridEngine.SELL && it.r3 != null }
                val bHit = bh.count { (it.r3 ?: 0.0) > 0 }
                val sHit = sh.count { (it.r3 ?: 0.0) < 0 }
                it.addView(TextView(ctx).apply {
                    text = "近30日建议 ${hist.size} 条 · 买入3日命中 $bHit/${bh.size}" +
                        (if (sh.isNotEmpty()) " · 卖出3日命中 $sHit/${sh.size}" else "") +
                        " · 买5日均 ${pct(s.buyStat.avgRet5Pct)} · 卖5日均 ${pct(s.sellStat.avgRet5Pct)}"
                    textSize = 10.5f; setTextColor(GRAY); setPadding(0, dp(2), 0, dp(2))
                })
            }
            // 行4：详情
            val row4 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
            row4.addView(Button(ctx).apply {
                text = "📊 详情"; textSize = 11f; setTextColor(Color.WHITE); setBackgroundColor(TEAL)
                layoutParams = LinearLayout.LayoutParams(0, dp(32), 1f).apply { marginEnd = dp(6) }
                setOnClickListener {
                    switchMode(0)
                    codeInput.setText(c.code)
                    prefillParams(p)
                    status("已载入 $name(${c.code}) 票性推荐参数，分析中…")
                    onRun()
                }
            })
            it.addView(row4)
        }
    }

    // ═══════════════════════ 板块分析 ═══════════════════════

    /** 板块趋势汇总：名称 + 成分股数 + 有荐股的成分数 + 买/卖/持有/观望计数 + 趋势分 */
    private data class SectorSummary(
        val name: String, val total: Int, val covered: Int,
        val buy: Int, val sell: Int, val hold: Int, val wait: Int,
        val score: Double
    )

    /** 渲染板块列表：从 sector_stocks 读全部板块→成分，与荐股卡片匹配，按趋势分降序 */
    private fun renderSectorList() {
        val ctx = requireContext()
        inSectorDetail = false
        sectorBox.removeAllViews()
        recoBox.visibility = View.GONE   // 板块分析时隐藏荐股列表
        lifecycleScope.launch {
            status("⏳ 加载板块成分与荐股数据…")
            val db = StockDatabase.getInstance(ctx)
            val (sectors, cards) = withContext(Dispatchers.IO) {
                val pairs = runCatching { db.sectorStockDao().getAllStockSectorPairs() }.getOrDefault(emptyList())
                val bySector = HashMap<String, MutableList<String>>()
                pairs.forEach { p ->
                    bySector.getOrPut(p.sector_name) { mutableListOf() }.add(p.stock_code)
                }
                // 只保留成分≥3 的板块（过滤零碎映射）
                val list = bySector.filter { it.value.size >= 3 }
                    .map { (name, codes) -> name to codes.distinct() }
                val c = runCatching { recoPipeline.store.loadLatestCards() }.getOrDefault(emptyList())
                list to c
            }
            // 返回荐股列表按钮
            val backRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, dp(4))
            }
            backRow.addView(Button(ctx).apply {
                text = "← 返回荐股列表"; textSize = 11f; setTextColor(Color.WHITE); setBackgroundColor(BLUE)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34))
                setOnClickListener { recoBox.visibility = View.VISIBLE; sectorBox.removeAllViews() }
            })
            sectorBox.addView(backRow)
            val cardMap = cards.associateBy { it.code }
            val summaries = sectors.map { (name, codes) ->
                var buy = 0; var sell = 0; var hold = 0; var wait = 0
                codes.forEach { code ->
                    when (cardMap[code]?.snap?.advice) {
                        SsaHybridEngine.BUY -> buy++
                        SsaHybridEngine.SELL -> sell++
                        SsaHybridEngine.HOLD_POS -> hold++
                        SsaHybridEngine.HOLD_EMPTY -> wait++
                    }
                }
                val covered = buy + sell + hold + wait
                // 趋势分：(买-卖)/覆盖数，乘100便于显示；覆盖为0则-100（排末尾）
                val score = if (covered > 0) (buy - sell) * 100.0 / covered else -100.0
                SectorSummary(name, codes.size, covered, buy, sell, hold, wait, score)
            }.sortedWith(compareByDescending<SectorSummary> { it.score }.thenBy { it.name })
            // 标题 + 统计
            sectorBox.addView(cardTitle("📊 板块趋势分析（${summaries.size} 个板块 · 按荐股趋势分降序）"))
            sectorBox.addView(hint("趋势分 = (买入数 − 卖出数) ÷ 有荐股成分数 × 100；" +
                "仅统计有荐股快照的成分股（需先完成全量构建）。点击板块查看成分股，点击成分股进入单票分析。"))
            if (summaries.isEmpty()) {
                sectorBox.addView(hint("⚠️ sector_stocks 无板块数据，请先在「数据」页预热板块成分，或完成荐股全量构建。"))
                status("")
                return@launch
            }
            // 板块列表
            summaries.forEachIndexed { i, s ->
                sectorBox.addView(card {
                    val row = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(4), 0, dp(2))
                    }
                    row.addView(TextView(ctx).apply {
                        text = "${i + 1}"; textSize = 13f; setTextColor(GRAY)
                        layoutParams = LinearLayout.LayoutParams(dp(28), LinearLayout.LayoutParams.WRAP_CONTENT)
                    })
                    row.addView(TextView(ctx).apply {
                        text = s.name; textSize = 14f; setTextColor(BLACK)
                        setTypeface(typeface, Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    val sc = when {
                        s.score > 20 -> GREEN
                        s.score > 0 -> Color.parseColor("#43A047")
                        s.score == 0.0 -> GRAY
                        s.score > -20 -> Color.parseColor("#E65100")
                        else -> RED
                    }
                    row.addView(TextView(ctx).apply {
                        text = "%+.0f".format(s.score); textSize = 14f; setTextColor(sc)
                        setTypeface(typeface, Typeface.BOLD); setPadding(dp(8), 0, dp(8), 0)
                    })
                    it.addView(row)
                    it.addView(TextView(ctx).apply {
                        text = "成分 ${s.total} · 已覆盖 ${s.covered} · " +
                            "🟢买入 ${s.buy} · 🔴卖出 ${s.sell} · 🔵持有 ${s.hold} · ⚪观望 ${s.wait}"
                        textSize = 10.5f; setTextColor(GRAY)
                    })
                }.apply {
                    setOnClickListener { renderSectorDetail(s) }
                })
            }
            status("板块分析完成：${summaries.size} 个板块")
        }
    }

    /** 板块详情：显示该板块所有成分股的荐股卡片（买入优先排序） */
    private fun renderSectorDetail(s: SectorSummary) {
        val ctx = requireContext()
        inSectorDetail = true
        sectorBox.removeAllViews()
        lifecycleScope.launch {
            val db = StockDatabase.getInstance(ctx)
            val codes = withContext(Dispatchers.IO) {
                runCatching { db.sectorStockDao().getStockCodesBySector(s.name) }.getOrDefault(emptyList())
            }
            val cards = withContext(Dispatchers.IO) {
                runCatching { recoPipeline.store.loadLatestCards() }.getOrDefault(emptyList())
            }
            val cardMap = cards.associateBy { it.code }
            // 标题 + 返回
            val head = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            head.addView(Button(ctx).apply {
                text = "← 返回板块"; textSize = 11f; setTextColor(Color.WHITE); setBackgroundColor(BLUE)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34))
                setOnClickListener { renderSectorList() }
            })
            head.addView(TextView(ctx).apply {
                text = "  ${s.name}（趋势分 %+.0f）".format(s.score)
                textSize = 14f; setTextColor(BLACK); setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(8), 0, 0, 0)
            })
            sectorBox.addView(head)
            val order = mapOf(SsaHybridEngine.BUY to 0, SsaHybridEngine.SELL to 1,
                SsaHybridEngine.HOLD_POS to 2, SsaHybridEngine.HOLD_EMPTY to 3)
            val rows = codes.mapNotNull { code -> cardMap[code] }
                .sortedWith(compareBy({ order[it.snap.advice] ?: 9 }, { it.code }))
            // 名称解析
            val missing = rows.map { it.code }.filter { it !in recoNameCache }
            if (missing.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    missing.forEach { c ->
                        val nm = runCatching { db.stockBasicDao().getByCode(c)?.name }.getOrNull()
                        if (!nm.isNullOrBlank()) recoNameCache[c] = nm
                    }
                }
            }
            if (rows.isEmpty()) {
                sectorBox.addView(hint("⚠️ 该板块 ${codes.size} 只成分股均无荐股快照，请先完成全量构建。"))
            } else {
                sectorBox.addView(hint("共 ${rows.size}/${codes.size} 只成分股有荐股快照，点击「📊 详情」进入单票 SSA 分析。"))
                rows.forEach { c ->
                    sectorBox.addView(recoRow(c, recoNameCache[c.code] ?: c.code))
                }
            }
        }
    }

    private fun setRecoButtons(enabled: Boolean) {
        for (i in 0 until recoTools.childCount) (recoTools.getChildAt(i) as? Button)?.isEnabled = enabled
    }

    /** 全量构建：688 核心池逐票识别票性→锁定推荐参数→seed 落盘（幂等可续跑可中断） */
    private fun onRecoBuild() {
        if (recoBusy) return
        val codes = HistoricalDataFetcher.getCoreStockPool(requireContext()).sorted()
        if (codes.isEmpty()) { status("⚠️ 核心池为空，请先在数据管理中初始化核心池"); return }
        recoBusy = true; setRecoButtons(false)
        status("⏳ 全量构建 ${codes.size} 票启动（可离开页面中断，进度已存档）…")
        lifecycleScope.launch {
            try {
                val done = recoPipeline.buildAll(codes, isCancelled = { !isActive },
                    onProgress = { d, t, c -> status("⏳ 构建 $d/$t：$c") })
                status("✅ 全量构建完成（本次 $done 票）")
                renderReco()
            } catch (e: Exception) {
                status("⚠️ 构建中断：${e.message}（进度已保存，重新点击可续跑）")
            } finally {
                recoBusy = false; setRecoButtons(true)
            }
        }
    }

    /** 每日更新：并行补拉缺口日线 → 增量步进（重复调用幂等，票性迁移自动重seed） */
    private fun onRecoRefresh() {
        if (recoBusy) return
        lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) { recoPipeline.store.loadIndex().size }
            if (n == 0) { status("⚠️ 尚未构建：先点【🏗 全量构建】"); return@launch }
            recoBusy = true; setRecoButtons(false)
            try {
                val cards = recoPipeline.refreshAll(
                    isCancelled = { !isActive },
                    onFetch = { d, t -> status("⏳ 补拉日线 $d/$t…") },
                    onUpdate = { d, t, c -> status("⏳ 增量步进 $d/$t：$c") })
                status("✅ 每日更新完成（${cards.size} 份卡片）")
                renderReco()
            } catch (e: Exception) {
                status("⚠️ 更新中断：${e.message}（已完成的票已保存，重新点击可续跑）")
            } finally {
                recoBusy = false; setRecoButtons(true)
            }
        }
    }

    // ── 荐股状态备份/恢复（防卸载重装丢失，zip 约 5~10MB） ──

    private fun onRecoBackupPick() {
        lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) { recoPipeline.store.stateCount() }
            if (n == 0) { status("⚠️ 尚无荐股状态可备份"); return@launch }
            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.CHINA)
                .format(java.util.Date())
            backupLauncher.launch("ssa_reco_backup_$ts.zip")
        }
    }

    private fun onBackupTo(uri: android.net.Uri) {
        lifecycleScope.launch {
            try {
                status("⏳ 备份打包中…")
                val n = withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openOutputStream(uri)?.use {
                        recoPipeline.store.exportZip(it)
                    } ?: throw IllegalStateException("无法写入所选位置")
                }
                status("✅ 备份完成：$n 个状态文件已写入所选位置（可在文件管理器查看）")
            } catch (e: Exception) {
                status("⚠️ 备份失败：${e.message}")
            }
        }
    }

    private fun confirmRestore(uri: android.net.Uri) {
        lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) { recoPipeline.store.stateCount() }
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("📥 恢复荐股备份")
                .setMessage("将用备份文件覆盖当前荐股状态（当前已建 $n 票将被整体替换，中断安全）。确定继续？")
                .setPositiveButton("恢复") { _, _ -> doRestore(uri) }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun doRestore(uri: android.net.Uri) {
        lifecycleScope.launch {
            try {
                status("⏳ 恢复解包中…")
                val n = withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)?.use {
                        recoPipeline.store.importZip(it)
                    } ?: throw IllegalStateException("无法读取所选文件")
                }
                recoNameCache.clear()
                recoFilter = -1; recoProfFilter = "ALL"
                status("✅ 恢复完成：$n 个状态文件，列表已刷新")
                renderReco()
            } catch (e: Exception) {
                status("⚠️ 恢复失败：${e.message}")
            }
        }
    }

    /** 自动配参后把推荐值写回输入框（K线根数不动） */
    private fun prefillParams(p: SsaHybridEngine.Params) {
        etShort.setText(p.trendShort.toString())
        etLong.setText(p.trendLong.toString())
        etCycle.setText(p.cycleL.toString())
        etEma.setText(p.smoothSpan.toString())
        etBase.setText((p.baseFraction * 100).toInt().toString())
    }

    private fun labelCn(label: String) = when (label) {
        "TREND" -> "趋势票"
        "OSCILLATION" -> "窄幅震荡票"
        "SLOWBULL" -> "慢牛票"
        else -> "震荡混合票"
    }

    private fun render(name: String, code: String, r: SsaHybridEngine.Result) {
        resultBox.removeAllViews()

        // ① 次日建议大卡
        resultBox.addView(card {
            it.addView(cardTitle("🧭 次日操作建议（截至 ${r.latestDate} 收盘 ${fmt2(r.latestClose)}）"))
            val (word, color, desc) = when (r.latestAdvice) {
                SsaHybridEngine.BUY -> Quad("买 入", GREEN,
                    "短trend斜率转正（或多头窗口中 cycle 谷底确认）→ 次日开盘可买入")
                SsaHybridEngine.SELL -> Quad("卖 出", RED,
                    "cycle 峰顶确认止盈（或 trend 死叉兜底离场）→ 次日开盘卖出")
                SsaHybridEngine.HOLD_POS -> Quad("持 有", BLUE, "多头波段未结束，持仓不动")
                else -> Quad("观 望", GRAY, "无趋势/无买点信号，空仓等待")
            }
            it.addView(TextView(requireContext()).apply {
                text = word; textSize = 30f; setTextColor(color)
                setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(4))
            })
            it.addView(TextView(requireContext()).apply {
                text = "$name($code)　$desc"; textSize = 12f; setTextColor(BLACK)
                gravity = Gravity.CENTER; setPadding(dp(6), dp(2), dp(6), dp(8))
            })
        })

        // ② 票性识别（PC 172 票报告结论固化）
        val pf = r.profile
        resultBox.addView(card {
            it.addView(cardTitle("🏷️ 票性识别（L30+EMA2 固定口径 · 滞后5日无未来函数）"))
            val (word, color, tip) = when (pf.label) {
                "TREND" -> Quad("趋势票", GREEN,
                    "全池50只趋势票验证：买入建议5日均收益+1.69%（四类建议中最强），趋势票买信号可较高信任；" +
                        "参数可用 短5/长10+周期60（趋势票固定参数中位+36.3%）；" +
                        "不建议加底仓——底仓越重回撤越深（满仓底仓回撤中位-49.8% vs 纯波段-26.9%）。")
                "MIXED" -> Quad("震荡混合票", ORANGE,
                    "全池120只混合票验证：买入建议胜率仅50%（5日均+0.66%）建议轻仓试错；" +
                        "卖出建议价值最大（卖后5日均-0.40%避跌明显）；" +
                        "固定参数在全池为负收益，务必先用本页回测挑选本票参数。")
                "OSCILLATION" -> Quad("窄幅震荡票", GRAY,
                    "买卖信号质量均弱（买入5日均-0.05%），建议以观望为主，降低操作频率。")
                else -> Quad("慢牛票", BLUE,
                    "买持优于频繁波段（峰顶止盈易卖早，卖后5日基本持平）；" +
                        "如需操作，小底仓+低频波段更稳。")
            }
            it.addView(TextView(requireContext()).apply {
                text = word; textSize = 22f; setTextColor(color)
                setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(4))
            })
            it.addView(statGrid(listOf(
                "高噪后3日均" to pct(pf.hi3),
                "高噪后5日均" to pct(pf.hi5),
                "超跌后5日均" to pct(pf.lo5),
                "周期振幅比" to "%.1f%%".format(pf.cycAmp * 100)
            )))
            it.addView(TextView(requireContext()).apply {
                text = "口径：hi5>0.6 且振幅>4% → 趋势；hi5<-0.2 且振幅<3% → 震荡；" +
                    "-0.2≤hi5<0.4 且振幅<2.5% → 慢牛；其余混合。" +
                    "高噪样本 ${pf.hiCount} 个 / 超跌样本 ${pf.loCount} 个。$tip"
                textSize = 10.5f; setTextColor(GRAY); setPadding(dp(4), dp(6), dp(4), dp(2))
            })
        })

        // ③ 建议命中率
        resultBox.addView(card {
            it.addView(cardTitle("🎯 建议滚动命中率（次日开盘成交）"))
            it.addView(table(listOf(
                listOf("建议", "次数", "次日对", "3日对", "5日均收益"),
                adviceRow("买入", r.buyStat, true),
                adviceRow("卖出", r.sellStat, false),
                adviceRow("持有", r.holdPosStat, true),
                adviceRow("观望", r.holdEmptyStat, false)
            )))
        })

        // ④ 绩效
        val st = r.stats
        resultBox.addView(card {
            it.addView(cardTitle("📊 回测绩效（参数 t${r.params.trendShort}/${r.params.trendLong}+c${r.params.cycleL}" +
                " EMA${r.params.smoothSpan} 底仓${(r.params.baseFraction * 100).toInt()}%）"))
            it.addView(statGrid(listOf(
                "总收益" to pct(st.totalReturnPct),
                "买持基准" to pct(st.benchmarkReturnPct),
                "超额" to pct(st.excessReturnPct),
                "最大回撤" to pct(st.maxDrawdownPct),
                "交易笔数" to "${st.tradeCount} 笔",
                "胜率" to "%.1f%%".format(st.winRatePct),
                "盈亏比" to if (st.profitFactor.isInfinite()) "∞" else "%.2f".format(st.profitFactor),
                "均持仓" to "${st.avgHoldBars} 根K"
            )))
            it.addView(TextView(requireContext()).apply {
                text = "区间 ${st.startDate} ~ ${st.endDate}（${st.barCount} 根日K）"
                textSize = 10f; setTextColor(GRAY); setPadding(dp(4), dp(6), dp(4), 0)
            })
        })

        // ⑤ 图（主图/副图联动：捏合缩放视口同步 + 十字光标竖线跨图）
        val pChart = priceChart(r)
        val dChart = kdjChart(r)
        val cChart = lineChart(r) { _, _ -> null }
        val eChart = equityChart(r)
        linkCharts(listOf(pChart, dChart, cChart, eChart))
        resultBox.addView(card {
            it.addView(cardTitle("📈 价格 + trend 双线 + 买卖点"))
            it.addView(pChart)
        })
        resultBox.addView(card {
            it.addView(cardTitle("📊 KDJ 副图（9,3,3 · K橙/D蓝/J紫 · 20/80 超卖超买线）"))
            it.addView(dChart)
        })
        resultBox.addView(card {
            it.addView(cardTitle("🌊 周期分量 cycle L${r.params.cycleL}（EMA${r.params.smoothSpan}）"))
            it.addView(cChart)
        })
        resultBox.addView(card {
            it.addView(cardTitle("💰 策略净值 vs 买持"))
            it.addView(eChart)
        })

        // ⑥ 交易明细
        resultBox.addView(card {
            it.addView(cardTitle("📝 交易明细（最多显示最近 40 笔）"))
            val head = listOf("#", "买入日", "买价", "方式", "卖出日", "卖价", "收益", "持仓", "离场")
            val rows = listOf(head) + r.trades.takeLast(40).mapIndexed { i, t ->
                listOf("${i + 1}", t.buyDate, fmt2(t.buyPrice), t.buyWhy, t.sellDate, fmt2(t.sellPrice),
                    pct(t.returnPct), "${t.barsHeld}", t.exitReason)
            }
            it.addView(table(rows))
        })

        // ⑦ 近30日建议验证（持仓跟踪：建议 vs 实际）
        val rec = r.recentAdvices.filter { it.r1 != null } // 只显示已收盘可验证的行（最新一条待验证）
        if (rec.isNotEmpty()) {
            val buyH = rec.filter { it.advice == SsaHybridEngine.BUY && it.r3 != null }
            val sellH = rec.filter { it.advice == SsaHybridEngine.SELL && it.r3 != null }
            resultBox.addView(card {
                it.addView(cardTitle("📜 近30日建议验证（t 收盘 → t+k 实际涨跌）"))
                val rows2 = mutableListOf(listOf("日期", "建议", "收盘", "次日%", "3日%", "5日%", "判定"))
                rec.reversed().forEach { a ->
                    val (w, _, _) = advWord(a.advice)
                    val r3 = a.r3
                    val judge = when {
                        r3 == null -> "…"
                        a.advice == SsaHybridEngine.BUY -> if (r3 > 0) "✅对" else "❌错"
                        a.advice == SsaHybridEngine.SELL -> if (r3 < 0) "✅对" else "❌错"
                        else -> "—"
                    }
                    rows2.add(listOf(a.date, w, fmt2(a.close),
                        a.r1?.let { "%+.2f".format(it) } ?: "-",
                        a.r3?.let { "%+.2f".format(it) } ?: "-",
                        a.r5?.let { "%+.2f".format(it) } ?: "-", judge))
                }
                it.addView(table(rows2))
                it.addView(TextView(requireContext()).apply {
                    text = "3日口径：买入命中 ${buyH.count { (it.r3 ?: 0.0) > 0 }}/${buyH.size}" +
                        " · 卖出命中 ${sellH.count { (it.r3 ?: 0.0) < 0 }}/${sellH.size}" +
                        "（确定性算法+锁定参数，历史建议与当日记录一致）"
                    textSize = 10f; setTextColor(GRAY); setPadding(dp(4), dp(6), dp(4), 0)
                })
            })
        }
    }

    private data class Quad(val a: String, val b: Int, val c: String)

    private fun adviceRow(label: String, s: SsaHybridEngine.AdviceStat, _up: Boolean) =
        listOf(label, "${s.count}", "%.0f%%".format(s.win1Pct), "%.0f%%".format(s.win3Pct), pct(s.avgRet5Pct))

    // ═══════════════════════ 图表 ═══════════════════════

    private fun baseChart(setup: (LineChart) -> Unit): LineChart {
        val chart = LineChart(requireContext())
        chart.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(220)
        ).apply { topMargin = dp(6) }
        chart.description.isEnabled = false
        chart.legend.textSize = 9f
        chart.setTouchEnabled(true); chart.isDragEnabled = true; chart.setScaleEnabled(true)
        chart.setPinchZoom(true)                  // 双指捏合缩放时间轴
        chart.setAutoScaleMinMaxEnabled(true)     // Y 轴随可见区间自动重拟合 → 放大后看得清
        chart.setVisibleXRangeMinimum(30f)        // 最多放大到 30 根K
        chart.axisRight.isEnabled = false
        chart.xAxis.position = XAxis.XAxisPosition.BOTTOM
        chart.xAxis.textSize = 8f
        chart.axisLeft.textSize = 9f
        setup(chart)
        return chart
    }

    private fun dateFormatter(r: SsaHybridEngine.Result) = object : ValueFormatter() {
        override fun getFormattedValue(v: Float): String {
            val i = v.toInt()
            return if (i in r.bars.indices) r.bars[i].date.substring(4) else ""
        }
    }

    private fun priceChart(r: SsaHybridEngine.Result): LineChart = baseChart { chart ->
        val n = r.bars.size
        fun ds(vals: DoubleArray, label: String, color: Int, width: Float, skipNaN: Boolean = true): LineDataSet {
            val entries = ArrayList<Entry>()
            for (i in 0 until n) {
                val v = vals[i]
                if (skipNaN && v.isNaN()) continue
                entries.add(Entry(i.toFloat(), v.toFloat()))
            }
            return LineDataSet(entries, label).apply {
                this.color = color; lineWidth = width; setDrawCircles(false)
                setDrawValues(false); mode = LineDataSet.Mode.LINEAR
            }
        }
        val close = DoubleArray(n) { r.bars[it].close }
        val data = LineData(
            ds(close, "收盘", BLACK, 1.0f, false).apply {
                // 十字光标样式：竖线 + 横线（贴收盘价）
                setHighlightLineWidth(2.2f)
                enableDashedHighlightLine(8f, 4f, 0f)
                highLightColor = Color.parseColor("#888888")
            },
            ds(r.trendShortLine, "短trend", BLUE, 1.1f),
            ds(r.trendLongLine, "长trend", ORANGE, 1.1f)
        )
        // 买卖点
        val buys = ArrayList<Entry>(); val sells = ArrayList<Entry>()
        r.trades.forEach { t ->
            buys.add(Entry(t.buyIndex.toFloat(), t.buyPrice.toFloat()))
            sells.add(Entry(t.sellIndex.toFloat(), t.sellPrice.toFloat()))
        }
        data.addDataSet(LineDataSet(buys, "买入").apply {
            setDrawValues(false); color = GREEN; setDrawCircles(true)
            setCircleColor(GREEN); circleRadius = 3f; lineWidth = 0f
        })
        data.addDataSet(LineDataSet(sells, "卖出").apply {
            setDrawValues(false); color = RED; setDrawCircles(true)
            setCircleColor(RED); circleRadius = 3f; lineWidth = 0f
        })
        chart.data = data
        chart.xAxis.valueFormatter = dateFormatter(r)
        chart.invalidate()
    }

    private fun lineChart(r: SsaHybridEngine.Result, @Suppress("UNUSED_PARAMETER") unused: (Int, Int) -> Unit): LineChart =
        baseChart { chart ->
            val n = r.bars.size
            val entries = ArrayList<Entry>()
            for (i in 0 until n) if (!r.cycleLine[i].isNaN())
                entries.add(Entry(i.toFloat(), r.cycleLine[i].toFloat()))
            val ds = LineDataSet(entries, "cycle").apply {
                color = PURPLE; lineWidth = 1.0f; setDrawCircles(false); setDrawValues(false)
            }
            chart.data = LineData(ds)
            chart.xAxis.valueFormatter = dateFormatter(r)
            chart.invalidate()
        }

    /** KDJ 副图：K/D/J 三线（9,3,3 标准参数）+ 20/80 超卖超买参考线，支持十字光标联动与捏合缩放 */
    private fun kdjChart(r: SsaHybridEngine.Result): LineChart = baseChart { chart ->
        val n = r.bars.size
        val kArr = DoubleArray(n); val dArr = DoubleArray(n); val jArr = DoubleArray(n)
        var kp = 50.0; var dp = 50.0
        for (i in 0 until n) {
            var lo = r.bars[i].low; var hi = r.bars[i].high
            for (j in max(0, i - 8) until i) {
                if (r.bars[j].low < lo) lo = r.bars[j].low
                if (r.bars[j].high > hi) hi = r.bars[j].high
            }
            val rsv = if (hi == lo) 50.0 else (r.bars[i].close - lo) / (hi - lo) * 100
            val kv = rsv / 3 + kp * 2 / 3
            val dv = kv / 3 + dp * 2 / 3
            kArr[i] = kv; dArr[i] = dv; jArr[i] = 3 * kv - 2 * dv
            kp = kv; dp = dv
        }
        fun ds(vals: DoubleArray, label: String, color: Int, width: Float) =
            LineDataSet(ArrayList<Entry>().apply {
                for (i in 0 until n) add(Entry(i.toFloat(), vals[i].toFloat()))
            }, label).apply {
                this.color = color; lineWidth = width; setDrawCircles(false); setDrawValues(false)
                setHighlightLineWidth(2.2f)                      // 十字光标竖线：粗虚线，与主图一致
                enableDashedHighlightLine(8f, 4f, 0f)
                highLightColor = Color.parseColor("#888888")
                setDrawHorizontalHighlightIndicator(false)
            }
        chart.data = LineData(
            ds(kArr, "K", ORANGE, 1.1f),
            ds(dArr, "D", BLUE, 1.1f),
            ds(jArr, "J", PURPLE, 1.0f)
        )
        chart.setAutoScaleMinMaxEnabled(false)   // KDJ 值域固定 0~100，用固定轴，放大后也稳定
        chart.axisLeft.axisMinimum = -20f        // J 可短暂越界
        chart.axisLeft.axisMaximum = 140f
        chart.axisLeft.addLimitLine(LimitLine(80f, "80").apply {
            lineColor = Color.parseColor("#E57373"); lineWidth = 0.8f; textSize = 8f
        })
        chart.axisLeft.addLimitLine(LimitLine(20f, "20").apply {
            lineColor = Color.parseColor("#81C784"); lineWidth = 0.8f; textSize = 8f
        })
        chart.xAxis.valueFormatter = dateFormatter(r)
        chart.invalidate()
    }

    /**
     * ## 主图/副图联动
     * - 捏合缩放/平移：任意图的手势把 X 视口（scaleX/transX）同步到其余图（Y 各自适应不复制）
     * - 十字光标：轻点或按住拖动出现（横线贴数据线、竖线为高亮指示线），
     *   任意图高亮变化时把同一 x 索引同步到其余图，竖线视觉上同时穿过主图与副图
     * - 单指拖动 = 十字扫描；双指 = 捏合缩放与平移
     */
    private fun linkCharts(charts: List<BarLineChartBase<*>>) {
        fun syncFrom(src: BarLineChartBase<*>) {
            val sv = FloatArray(9)
            src.viewPortHandler.matrixTouch.getValues(sv)
            charts.forEach { dst ->
                if (dst !== src) {
                    val dm = Matrix(dst.viewPortHandler.matrixTouch)
                    val dv = FloatArray(9)
                    dm.getValues(dv)
                    dv[Matrix.MSCALE_X] = sv[Matrix.MSCALE_X]
                    dv[Matrix.MTRANS_X] = sv[Matrix.MTRANS_X]
                    dm.setValues(dv)
                    dst.viewPortHandler.refresh(dm, dst, true)
                }
            }
        }
        var linkingHighlight = false
        charts.forEach { c ->
            c.setHighlightPerDragEnabled(true)   // 按住拖动 = 十字扫描
            c.onChartGestureListener = object : OnChartGestureListener {
                override fun onChartGestureStart(me: MotionEvent?, lastPerformedGesture: ChartGesture?) {}
                override fun onChartGestureEnd(me: MotionEvent?, lastPerformedGesture: ChartGesture?) { syncFrom(c) }
                override fun onChartLongPressed(me: MotionEvent?) {}
                override fun onChartDoubleTapped(me: MotionEvent?) {}
                override fun onChartSingleTapped(me: MotionEvent?) {}
                override fun onChartFling(me: MotionEvent?, me2: MotionEvent?, vx: Float, vy: Float) {}
                override fun onChartScale(me: MotionEvent?, scaleX: Float, scaleY: Float) { syncFrom(c) }
                override fun onChartTranslate(me: MotionEvent?, dX: Float, dY: Float) { syncFrom(c) }
            }
            c.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
                override fun onValueSelected(e: Entry, h: Highlight) {
                    if (linkingHighlight) return
                    linkingHighlight = true
                    charts.forEach { o -> if (o !== c) o.highlightValue(h.x, 0, false) }
                    linkingHighlight = false
                }
                override fun onNothingSelected() {
                    if (linkingHighlight) return
                    linkingHighlight = true
                    charts.forEach { o -> if (o !== c) o.highlightValue(null, false) }
                    linkingHighlight = false
                }
            })
        }
    }

    private fun equityChart(r: SsaHybridEngine.Result): LineChart = baseChart { chart ->
        val n = r.bars.size
        val e1 = ArrayList<Entry>(); val e2 = ArrayList<Entry>()
        for (i in 0 until n) {
            e1.add(Entry(i.toFloat(), (r.equityCurve[i] / 10000.0).toFloat()))
            e2.add(Entry(i.toFloat(), (r.benchmarkCurve[i] / 10000.0).toFloat()))
        }
        val ds1 = LineDataSet(e1, "策略(万)").apply {
            color = RED; lineWidth = 1.2f; setDrawCircles(false); setDrawValues(false)
        }
        val ds2 = LineDataSet(e2, "买持(万)").apply {
            color = GRAY; lineWidth = 1.0f; setDrawCircles(false); setDrawValues(false)
        }
        chart.data = LineData(ds1, ds2)
        chart.xAxis.valueFormatter = dateFormatter(r)
        chart.invalidate()
    }

    // ═══════════════════════ 数据 ═══════════════════════

    private suspend fun resolveCode(raw: String): Pair<String?, String?> {
        val normalized = StockAnalysisAgent.normalizeStockCode(raw)
        val db = StockDatabase.getInstance(requireContext())
        if (normalized.startsWith("sh") || normalized.startsWith("sz") || normalized.startsWith("bj")) {
            val nm = runCatching { db.stockBasicDao().getByCode(normalized)?.name }.getOrNull()
            return normalized to (nm?.ifBlank { normalized })
        }
        val hits = runCatching { db.stockBasicDao().searchByName(raw) }.getOrDefault(emptyList())
        val pick = hits.firstOrNull { it.name == raw }
            ?: hits.firstOrNull { it.name.contains(raw) || raw.contains(it.name) }
            ?: hits.firstOrNull()
        return pick?.code to pick?.name
    }

    private suspend fun loadDaily(code: String): List<Bar>? {
        val ctx = requireContext()
        val db = StockDatabase.getInstance(ctx)
        var local: List<DailySnapshotEntity> =
            runCatching { db.dailySnapshotDao().getByCode(code, 2000) }.getOrDefault(emptyList())
        if (local.size < 500) {
            val ok = HistoricalDataFetcher(ctx).fetchStockLatest(code, 900)
            if (ok) local = runCatching { db.dailySnapshotDao().getByCode(code, 2000) }.getOrDefault(emptyList())
        }
        val bars = local.filter { it.close > 0 && it.high > 0 && it.low > 0 }
            .sortedBy { it.date }
            .map { Bar(it.date, it.open.ifZero(it.close), it.high, it.low, it.close) }
        return if (bars.size < 150) null else bars
    }

    private fun Double.ifZero(f: Double) = if (this == 0.0) f else this

    // ═══════════════════════ UI 工具 ═══════════════════════

    private fun fmt2(v: Double) = "%.2f".format(v)
    private fun pct(v: Double) = "${if (v > 0) "+" else ""}%.2f%%".format(v)

    private fun table(rows: List<List<String>>): View {
        val hsv = HorizontalScrollView(requireContext())
        val ll = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        rows.forEachIndexed { ri, row ->
            val rl = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            val widths = intArrayOf(34, 78, 60, 64, 78, 60, 66, 44, 80)
            row.forEachIndexed { ci, txt ->
                val w = widths.getOrElse(ci) { 60 }
                rl.addView(TextView(requireContext()).apply {
                    text = txt; textSize = 9.5f
                    setTextColor(if (ri == 0) GRAY else BLACK)
                    if (ci == 6 && ri > 0) setTextColor(if (txt.startsWith("+")) GREEN else RED)
                    setPadding(dp(4), dp(3), dp(4), dp(3))
                    background = GradientDrawable().apply {
                        setColor(if (ri == 0) Color.parseColor("#F2F2F2") else Color.TRANSPARENT)
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(w), LinearLayout.LayoutParams.WRAP_CONTENT)
                })
            }
            ll.addView(rl)
        }
        hsv.addView(ll)
        return hsv
    }

    private fun statGrid(cells: List<Pair<String, String>>): View {
        val gl = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(2), dp(6), dp(2), dp(2))
        }
        cells.chunked(4).forEach { rowCells ->
            val rl = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            rowCells.forEach { (k, v) ->
                val cell = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                cell.addView(TextView(requireContext()).apply { text = k; textSize = 9f; setTextColor(GRAY) })
                cell.addView(TextView(requireContext()).apply {
                    text = v; textSize = 14f; setTextColor(BLACK); setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(2), 0, 0)
                })
                rl.addView(cell)
            }
            gl.addView(rl)
        }
        return gl
    }

    private fun card(build: (LinearLayout) -> Unit) = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBg("#FFFFFF", "#E4E4E4", 8)
        setPadding(dp(10), dp(8), dp(10), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        build(this)
    }

    private fun cardTitle(t: String) = TextView(requireContext()).apply {
        text = t; textSize = 13f; setTextColor(TEAL)
        setTypeface(typeface, Typeface.BOLD); setPadding(dp(2), dp(2), dp(2), dp(6))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun roundedBg(fill: String, stroke: String, r: Int) = GradientDrawable().apply {
        cornerRadius = r.toFloat(); setColor(Color.parseColor(fill))
        setStroke(dp(1), Color.parseColor(stroke))
    }
    private fun sectionTitle(t: String) = TextView(requireContext()).apply {
        text = t; textSize = 17f; setTextColor(BLACK)
        setTypeface(typeface, Typeface.BOLD); setPadding(dp(2), dp(8), dp(2), dp(4))
    }
    private fun hint(t: String) = TextView(requireContext()).apply {
        text = t; textSize = 10.5f; setTextColor(GRAY)
        setPadding(dp(2), dp(4), dp(2), dp(4))
    }
    private fun status(t: String) { statusTv.text = t }
}
