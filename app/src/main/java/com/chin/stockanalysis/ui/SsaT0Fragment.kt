package com.chin.stockanalysis.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.Matrix
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
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.agent.stock.StockAnalysisAgent
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import com.chin.stockanalysis.strategy.data.HistoricalDataFetcher
import com.chin.stockanalysis.strategy.data.IntradayKlineFetcher
import com.chin.stockanalysis.strategy.ssa.SsaT0Engine
import com.chin.stockanalysis.strategy.ssa.SsaT0Engine.Bar
import com.chin.stockanalysis.strategy.ssa.SsaT0Engine.Layer
import com.chin.stockanalysis.strategy.ssa.SsaT0Engine.LayerCfg
import com.chin.stockanalysis.strategy.ssa.SsaT0Engine.Params
import com.chin.stockanalysis.strategy.ssa.SsaT0Engine.Result
import com.chin.stockanalysis.strategy.ssa.SsaT0Engine.SegStats
import com.chin.stockanalysis.strategy.ssa.SsaT0PoolStore
import com.chin.stockanalysis.stock.database.StockDatabase
import com.github.mikephil.charting.charts.CombinedChart
import com.github.mikephil.charting.charts.BarLineChartBase
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis.AxisDependency
import com.github.mikephil.charting.data.CombinedData
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.ChartTouchListener.ChartGesture
import com.github.mikephil.charting.listener.OnChartGestureListener
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ## 三层 SSA 稳态做T（周线→日线→1H）
 *
 * 固定长度滚动窗口 SSA；用**周期振荡分量 S 的滚动振幅**构建扰动区间（中线=前收，趋势不参与）；
 * 1H 触及扰动下沿做正T、触上沿做反T/止盈；突破区间暂停做T；含佣金/印花税/滑点；70% 训练 / 30% 样本外。
 */
class SsaT0Fragment : Fragment() {

    private val RED = Color.parseColor("#E53935")
    private val GREEN = Color.parseColor("#43A047")
    private val ORANGE = Color.parseColor("#E65100")
    private val MID_ORANGE = Color.parseColor("#FF6F00")
    private val BUY_TRI = Color.parseColor("#D32F2F")
    private val SELL_TRI = Color.parseColor("#2E7D32")
    private val BLUE = Color.parseColor("#1976D2")
    private val PURPLE = Color.parseColor("#9C27B0")
    private val TEAL = Color.parseColor("#00897B")
    private val GRAY = Color.parseColor("#999999")

    private lateinit var codeInput: EditText
    private lateinit var runBtn: Button
    private lateinit var statusTv: TextView
    private lateinit var resultBox: LinearLayout
    private val cfgInputs = ArrayList<EditText>()   // 顺序：周L M N 日L M N 时L M N
    private lateinit var corePctEt: EditText
    private lateinit var tPctEt: EditText
    private lateinit var slipEt: EditText
    private lateinit var cycleRuleCb: CheckBox   // 保留选项：日线 cycle 拐点规则（不勾选=默认三态机）
    private lateinit var winHalfCb: CheckBox      // 保留选项：WINDOW 段半仓（降回撤）

    // ── 三模式：单票建议 / 持仓池 / 每小时简报 ──
    private lateinit var inputCard: LinearLayout
    private lateinit var modeBtnSingle: Button
    private lateinit var modeBtnPool: Button
    private lateinit var modeBtnBrief: Button
    private lateinit var poolTools: LinearLayout
    private lateinit var lastUpdTv: TextView
    private lateinit var poolBox: LinearLayout
    private lateinit var briefTools: LinearLayout
    private lateinit var briefUpdTv: TextView
    private lateinit var briefFilterRow: LinearLayout
    private lateinit var briefBox: LinearLayout
    private lateinit var poolUpdBtn: Button
    private lateinit var briefUpdBtn: Button
    /** 简报动作筛选：-1=全部，否则 SsaT0Engine.BUY/SELL/HOLD_POS/HOLD_EMPTY */
    private var briefFilter = -1
    private var busy = false

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

        box.addView(sectionTitle("🔄 三层 SSA 稳态做T"))
        box.addView(hint("主体层=小时线**三态机**：以「中位线 vs 趋势线」(=cycle符号) 划分 UP/WINDOW/FLAT。" +
            "UP(cycle>0) 核心做多、超极限卖/破极限买回做T；WINDOW(cycle<0且窗口开) 上穿中位买、触正常上沿卖；" +
            "FLAT(cycle<0窗口关) 空仓、仅极限突破做T。固定滚动窗口、无未来函数；含佣金+印花税+滑点；70%训练/30%样本外。"))

        // ── 三模式切换：单票建议 / 持仓池 / 每小时简报 ──
        val modeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(4))
        }
        modeBtnSingle = Button(ctx).apply {
            text = "🔍 单票建议"; textSize = 12f; setTextColor(Color.WHITE); setBackgroundColor(TEAL)
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(6) }
            setOnClickListener { switchMode(0) }
        }
        modeBtnPool = Button(ctx).apply {
            text = "📋 持仓池"; textSize = 12f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#B0BEC5"))
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(6) }
            setOnClickListener { switchMode(1) }
        }
        modeBtnBrief = Button(ctx).apply {
            text = "⏱ 每小时简报"; textSize = 12f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#B0BEC5"))
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
            setOnClickListener { switchMode(2) }
        }
        modeRow.addView(modeBtnSingle); modeRow.addView(modeBtnPool); modeRow.addView(modeBtnBrief)
        box.addView(modeRow)

        // ── 单票建议输入区（整块可隐藏）──
        val inputCard0 = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(inputCard0)
        inputCard = inputCard0

        val inputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(4))
        }
        codeInput = EditText(ctx).apply {
            hint = "股票代号 / 名称"
            textSize = 13f; isSingleLine = true
            setTextColor(Color.parseColor("#333333")); setHintTextColor(Color.parseColor("#AAAAAA"))
            background = roundedBg("#F5F5F5", "#DDDDDD", 6)
            setPadding(dp(10), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(8) }
        }
        inputRow.addView(codeInput)
        runBtn = Button(ctx).apply {
            text = "开始做T回测"; textSize = 13f; setTextColor(Color.WHITE); setBackgroundColor(TEAL)
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40))
            setOnClickListener { onRun() }
        }
        inputRow.addView(runBtn)
        inputCard0.addView(inputRow)

        // 三层窗口参数（可横向滑动）
        inputCard0.addView(label("三层固定窗口（周 / 日 / 时：L嵌入, M滚动样本, N回看）"))
        val hsv = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        val prow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val defs = listOf(
            "周L" to "20", "周M" to "80", "周N" to "12",
            "日L" to "30", "日M" to "120", "日N" to "20",
            "时L" to "30", "时M" to "120", "时N" to "20"
        )
        defs.forEach { (cap, dft) ->
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(dp(52), LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = dp(6) }
            }
            cell.addView(TextView(ctx).apply { text = cap; textSize = 9f; setTextColor(GRAY) })
            cell.addView(EditText(ctx).apply {
                setText(dft); textSize = 12f; isSingleLine = true
                setTextColor(Color.parseColor("#333333"))
                background = roundedBg("#FFFFFF", "#CCCCCC", 6)
                setPadding(dp(4), 0, dp(4), 0)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(32))
            }.also { cfgInputs.add(it) })
            prow.addView(cell)
        }
        hsv.addView(prow); inputCard0.addView(hsv)

        // 仓位/成本
        inputCard0.addView(label("仓位与成本（核心仓位% / T仓摆动%，滑点）"))
        val crow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun cell(cap: String, dft: String): EditText {
            val c = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(6) }
            }
            c.addView(TextView(ctx).apply { text = cap; textSize = 9f; setTextColor(GRAY) })
            val et = EditText(ctx).apply {
                setText(dft); textSize = 12f; isSingleLine = true
                setTextColor(Color.parseColor("#333333"))
                background = roundedBg("#FFFFFF", "#CCCCCC", 6)
                setPadding(dp(6), 0, dp(6), 0)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34))
            }
            c.addView(et); crow.addView(c); return et
        }
        corePctEt = cell("核心仓位%", "80")
        tPctEt = cell("T仓摆动%", "30")
        slipEt = cell("滑点(‱→小数0.0005)", "0.0005")
        inputCard0.addView(crow)

        // 保留选项：日线 cycle 拐点规则（勾选启用新规则，不勾选=默认三态机）
        cycleRuleCb = CheckBox(ctx).apply {
            text = "启用「日线 cycle 拐点」做T规则（保留选项；不勾选=默认三态机）"
            textSize = 12f
            isChecked = false
            setTextColor(Color.parseColor("#333333"))
            setPadding(0, dp(6), 0, 0)
        }
        inputCard0.addView(cycleRuleCb)
        inputCard0.addView(hint("勾选后：用**日线** SSA 周期分量(cycle)的谷底/峰顶驱动做T（更平滑、拐点更少），" +
            "拐点延迟1根确认；谷底满仓、峰顶全清、下降段仅上穿中位买入。当前为对比保留方案，默认不启用。"))

        // 保留选项：WINDOW 段半仓（针对 WINDOW 段回撤；实测收益基本不变、回撤 −15.99%→−12.87%）
        winHalfCb = CheckBox(ctx).apply {
            text = "WINDOW 段半仓买入（降回撤，保留选项；不勾选=WINDOW 满仓）"
            textSize = 12f
            isChecked = false
            setTextColor(Color.parseColor("#333333"))
            setPadding(0, dp(6), 0, 0)
        }
        inputCard0.addView(winHalfCb)
        inputCard0.addView(hint("勾选后：三态机 WINDOW(cycle<0 且窗口开) 段改用**半仓**做上穿中位买入，" +
            "实测总收益基本不变、最大回撤由 −15.99% 降到 −12.87%（风险调整后更优）。与上方 cycle 拐点规则互不影响。"))

        // ── 持仓池容器 ──
        poolTools = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(2))
        }
        val addBtn = Button(ctx).apply {
            text = "＋添加"; textSize = 12f; setTextColor(Color.WHITE); setBackgroundColor(BLUE)
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(6) }
            setOnClickListener { onPoolAdd() }
        }
        poolUpdBtn = Button(ctx).apply {
            text = "▶ 一键更新全部"; textSize = 12f; setTextColor(Color.WHITE); setBackgroundColor(TEAL)
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(6) }
            setOnClickListener { onPoolUpdate() }
        }
        poolTools.addView(addBtn); poolTools.addView(poolUpdBtn)
        box.addView(poolTools); poolTools.visibility = View.GONE
        lastUpdTv = TextView(ctx).apply {
            textSize = 10.5f; setTextColor(GRAY); setPadding(dp(2), dp(4), dp(2), 0)
        }
        box.addView(lastUpdTv); lastUpdTv.visibility = View.GONE
        poolBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(poolBox); poolBox.visibility = View.GONE

        // ── 每小时简报容器 ──
        briefTools = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(2))
        }
        briefUpdBtn = Button(ctx).apply {
            text = "▶ 刷新简报"; textSize = 12f; setTextColor(Color.WHITE); setBackgroundColor(TEAL)
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
            setOnClickListener { onBriefRefresh() }
        }
        briefTools.addView(briefUpdBtn)
        box.addView(briefTools); briefTools.visibility = View.GONE
        briefUpdTv = TextView(ctx).apply {
            textSize = 10.5f; setTextColor(GRAY); setPadding(dp(2), dp(4), dp(2), 0)
        }
        box.addView(briefUpdTv); briefUpdTv.visibility = View.GONE
        briefFilterRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(2))
        }
        box.addView(briefFilterRow); briefFilterRow.visibility = View.GONE
        briefBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(briefBox); briefBox.visibility = View.GONE

        statusTv = TextView(ctx).apply { textSize = 11f; setTextColor(GRAY); setPadding(dp(2), dp(6), dp(2), dp(2)) }
        box.addView(statusTv)
        resultBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(resultBox)
        box.addView(hint("⚠️ 三层 SSA 均为固定长度滚动窗口逐K重算；中线=trend+cycle（价格尺度中位价）、" +
            "正常区间=中线±1.5σ、极限区间=中线±2.0σ（σ=噪声分量滚动标准差）；t 收盘决策、t+1 开盘成交；" +
            "A/B/C 为已收盘K状态（A=正常区间内 B=极限区间内 C=突破极限）；末尾窗口存在边缘效应。结果不构成投资建议。"))
        return root
    }

    // ═══════════════════════ 执行 ═══════════════════════

    private fun onRun() {
        val raw = codeInput.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) { status("请输入股票代号或名称"); return }
        val p = readParams() ?: return
        runBtn.isEnabled = false
        resultBox.removeAllViews()
        lifecycleScope.launch {
            try {
                status("⏳ 解析股票 / 加载日K…")
                val (code, name) = withContext(Dispatchers.IO) { resolveCode(raw) }
                if (code == null) { status("⚠️ 未找到「$raw」"); return@launch }
                val days = withContext(Dispatchers.IO) { loadDaily(code) }
                if (days == null) { status("⚠️ $name 日K不足（需约2年）"); return@launch }
                status("⏳ 在线拉取 1H K线（东财 klt=60，约2年）…")
                val hourlyEnt = withContext(Dispatchers.IO) {
                    IntradayKlineFetcher(requireContext()).fetchHourlyHistory(code, 800)
                }
                if (hourlyEnt.size < p.hour.windowM + 60) {
                    status("⚠️ 1H K线仅 ${hourlyEnt.size} 根（需≥${p.hour.windowM + 60}），网络数据不足"); return@launch
                }
                val hBars = hourlyEnt
                    .filter { it.close > 0 && it.high > 0 && it.low > 0 }
                    .sortedBy { it.datetime }
                    .map { Bar(it.date, it.datetime.substringAfter(' ', ""), it.open, it.high, it.low, it.close) }
                status("⏳ 三层滚动 SSA + 做T回测（${hBars.size} 根1H / ${days.size} 根日K）…")
                val r = withContext(Dispatchers.Default) { runCatching { SsaT0Engine.run(hBars, days, p) } }
                r.onSuccess { render(name ?: code, code, it) }
                    .onFailure { status("⚠️ 回测失败：${it.message}") }
                if (r.isSuccess) status("✅ 完成：${name ?: code}($code)")
            } finally {
                runBtn.isEnabled = true
            }
        }
    }

    private fun readParams(): Params? {
        fun iv(i: Int, n: String): Int? {
            val v = cfgInputs[i].text?.toString()?.trim()?.toIntOrNull()
            if (v == null) status("⚠️ 「$n」需为整数")
            return v
        }
        val wl = iv(0, "周L") ?: return null; val wm = iv(1, "周M") ?: return null; val wn = iv(2, "周N") ?: return null
        val dl = iv(3, "日L") ?: return null; val dm = iv(4, "日M") ?: return null; val dn = iv(5, "日N") ?: return null
        val hl = iv(6, "时L") ?: return null; val hm = iv(7, "时M") ?: return null; val hn = iv(8, "时N") ?: return null
        val cp = corePctEt.text?.toString()?.trim()?.toDoubleOrNull(); if (cp == null) { status("⚠️ 核心仓位%无效"); return null }
        val tp = tPctEt.text?.toString()?.trim()?.toDoubleOrNull(); if (tp == null) { status("⚠️ T仓摆动%无效"); return null }
        val sl = slipEt.text?.toString()?.trim()?.toDoubleOrNull(); if (sl == null) { status("⚠️ 滑点无效"); return null }
        // 勾选=启用日线 cycle 拐点保留规则；不勾选=默认三态机基线
        val base = Params(LayerCfg(wl, wm, wn), LayerCfg(dl, dm, dn), LayerCfg(hl, hm, hn), cp, tp, slippageRate = sl)
        val withWin = if (winHalfCb.isChecked) base.copy(winPosPct = 50.0) else base
        return if (cycleRuleCb.isChecked) {
            withWin.copy(useCycleRule = true, cyLayer = "D", cyConfirm = 2)
        } else withWin
    }

    // ═══════════════════════ 三模式：单票 / 持仓池 / 每小时简报 ═══════════════════════

    /** 池/简报使用的小时线根数（单票分析仍用 800） */
    private val POOL_HOURLY = 500

    /** m: 0=单票建议 1=持仓池 2=每小时简报 */
    private fun switchMode(m: Int) {
        val pool = m == 1
        val brief = m == 2
        inputCard.visibility = if (pool || brief) View.GONE else View.VISIBLE
        resultBox.visibility = if (pool || brief) View.GONE else View.VISIBLE
        poolTools.visibility = if (pool) View.VISIBLE else View.GONE
        lastUpdTv.visibility = if (pool) View.VISIBLE else View.GONE
        poolBox.visibility = if (pool) View.VISIBLE else View.GONE
        briefTools.visibility = if (brief) View.VISIBLE else View.GONE
        briefUpdTv.visibility = if (brief) View.VISIBLE else View.GONE
        briefFilterRow.visibility = if (brief) View.VISIBLE else View.GONE
        briefBox.visibility = if (brief) View.VISIBLE else View.GONE
        val idle = Color.parseColor("#B0BEC5")
        modeBtnSingle.setBackgroundColor(if (!pool && !brief) TEAL else idle)
        modeBtnPool.setBackgroundColor(if (pool) TEAL else idle)
        modeBtnBrief.setBackgroundColor(if (brief) TEAL else idle)
        if (pool) { status(""); renderPool() }
        if (brief) { status(""); renderBrief() }
    }

    private fun advWord(a: Int): Pair<String, Int> = when (a) {
        SsaT0Engine.BUY -> "买入" to RED
        SsaT0Engine.SELL -> "卖出" to GREEN
        SsaT0Engine.HOLD_POS -> "持有" to BLUE
        else -> "观望" to GRAY
    }

    private fun stateCn(s: Int): String = when (s) {
        1 -> "UP(核心做多)"
        3 -> "WINDOW(上穿中位买)"
        2 -> "FLAT(空仓·极限T)"
        else -> "暖机中"
    }

    private fun fmt2(v: Double) = "%.2f".format(v)

    /** 单票顶部的「下一根操作建议」展示 */
    private fun adviceView(ad: SsaT0Engine.Advice): View {
        val ctx = requireContext()
        val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val (w, c) = advWord(ad.action)
        val row1 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        row1.addView(TextView(ctx).apply {
            text = "▶ $w"; textSize = 22f; setTextColor(c); setTypeface(typeface, Typeface.BOLD)
        })
        row1.addView(TextView(ctx).apply {
            text = "  ${stateCn(ad.state)}"; textSize = 13f; setTextColor(GRAY)
            setPadding(dp(8), 0, 0, 0)
        })
        wrap.addView(row1)
        wrap.addView(statGrid(listOf(
            "建议时间" to ad.time,
            "收盘价" to fmt2(ad.close),
            "当前持仓" to "%.1f%%".format(ad.holdPct),
            "状态" to stateCn(ad.state)
        )))
        wrap.addView(TextView(ctx).apply {
            text = "依据：${ad.reason}"
            textSize = 11f; setTextColor(Color.parseColor("#555555")); setPadding(dp(2), dp(2), dp(2), 0)
        })
        wrap.addView(hint("口径：t 收盘决策、t+1 开盘成交（与混合策略页「下一根操作建议」一致）。"))
        return wrap
    }

    /** 用当前 UI 参数回填三层窗口/仓位/保留选项（供池行「📊 详情」载入锁定参数） */
    private fun prefillParams(p: Params) {
        fun set(i: Int, v: Int) { cfgInputs[i].setText(v.toString()) }
        set(0, p.week.embedL); set(1, p.week.windowM); set(2, p.week.lookbackN)
        set(3, p.day.embedL); set(4, p.day.windowM); set(5, p.day.lookbackN)
        set(6, p.hour.embedL); set(7, p.hour.windowM); set(8, p.hour.lookbackN)
        corePctEt.setText(p.corePct.toString())
        tPctEt.setText(p.tPct.toString())
        slipEt.setText(p.slippageRate.toString())
        cycleRuleCb.isChecked = p.useCycleRule
        winHalfCb.isChecked = p.winPosPct < 100.0
    }

    /** 添加持仓票：解析 → 锁定当前三层参数入库 → 立即跑一次生成快照 */
    private fun onPoolAdd() {
        val ctx = requireContext()
        val et = EditText(ctx).apply {
            hint = "股票代号 / 名称"; textSize = 13f; isSingleLine = true
            setTextColor(Color.parseColor("#333333")); setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        AlertDialog.Builder(ctx)
            .setTitle("＋ 添加持仓票（锁定当前三层参数）")
            .setView(et)
            .setPositiveButton("加入") { _, _ ->
                val raw = et.text.toString().trim()
                if (raw.isEmpty()) return@setPositiveButton
                val p = readParams() ?: return@setPositiveButton
                lifecycleScope.launch {
                    status("⏳ 解析 $raw …")
                    val (code, name) = withContext(Dispatchers.IO) { resolveCode(raw) }
                    if (code == null) { status("⚠️ 未找到「$raw」"); return@launch }
                    if (SsaT0PoolStore.load(ctx).any { it.code == code }) {
                        status("⚠️ ${name ?: code} 已在池中"); return@launch
                    }
                    val today = SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date())
                    val m = SsaT0PoolStore.of(code, name ?: code, today, p)
                    SsaT0PoolStore.add(ctx, m)
                    status("✅ 已加入 ${name ?: code}($code)，正在生成首份快照…")
                    val snap = updateOne(m, POOL_HOURLY)
                    val snaps = SsaT0PoolStore.loadSnapshot(ctx).filterNot { it.code == code }.toMutableList()
                    if (snap != null) snaps.add(snap)
                    SsaT0PoolStore.saveSnapshot(ctx, snaps)
                    renderPool()
                    status(if (snap != null) "✅ ${name ?: code} 快照完成（建议：${advWord(snap.action).first}）"
                           else "⚠️ ${name ?: code} 已入池，但首份快照失败（K线不足），可稍后一键更新")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 单票更新：最新日K + 最新1H K线 + 锁定参数重跑 → 快照（含建议/状态/持仓/绩效） */
    private suspend fun updateOne(m: SsaT0PoolStore.PoolMember, nHourly: Int): SsaT0PoolStore.T0Snapshot? {
        val ctx = requireContext()
        val p = m.params()
        val days = withContext(Dispatchers.IO) { loadDaily(m.code) } ?: return null
        val hourlyEnt = withContext(Dispatchers.IO) {
            runCatching { IntradayKlineFetcher(ctx).fetchHourlyHistory(m.code, nHourly) }.getOrDefault(emptyList())
        }
        if (hourlyEnt.size < p.hour.windowM + 60) return null
        val hBars = hourlyEnt
            .filter { it.close > 0 && it.high > 0 && it.low > 0 }
            .sortedBy { it.datetime }
            .map { Bar(it.date, it.datetime.substringAfter(' ', ""), it.open, it.high, it.low, it.close) }
        val r = withContext(Dispatchers.Default) { runCatching { SsaT0Engine.run(hBars, days, p) } }
            .getOrElse { return null }
        val ad = withContext(Dispatchers.Default) { SsaT0Engine.adviceOf(r) }
        val now = SimpleDateFormat("yyyyMMdd HH:mm", Locale.CHINA).format(Date())
        return SsaT0PoolStore.T0Snapshot(
            m.code, m.name, now, ad.time, ad.close, ad.state, ad.action, ad.reason, ad.holdPct,
            r.full.totalReturnPct, r.full.maxDrawdownPct, r.full.winRatePct, r.full.tradeCount
        )
    }

    /** 一键更新全部：逐票拉最新1H K线 → 锁定参数重跑 → 覆盖快照 */
    private fun onPoolUpdate() {
        val ctx = requireContext()
        val members = SsaT0PoolStore.load(ctx)
        if (members.isEmpty()) { status("⚠️ 池为空，先【＋添加】持仓票"); return }
        if (busy) { status("⏳ 正在更新中…"); return }
        busy = true; poolUpdBtn.isEnabled = false
        lifecycleScope.launch {
            try {
                members.forEachIndexed { i, m ->
                    status("⏳ 池更新 ${i + 1}/${members.size}：${m.name}(${m.code})…")
                    val snap = updateOne(m, POOL_HOURLY)
                    val snaps = SsaT0PoolStore.loadSnapshot(ctx).filterNot { it.code == m.code }.toMutableList()
                    if (snap != null) snaps.add(snap)
                    SsaT0PoolStore.saveSnapshot(ctx, snaps)
                }
                status("✅ 池更新完成（${members.size} 票）")
            } finally {
                busy = false; poolUpdBtn.isEnabled = true
            }
            if (isAdded) renderPool()
        }
    }

    private fun renderPool() {
        val ctx = requireContext()
        poolBox.removeAllViews()
        val members = SsaT0PoolStore.load(ctx)
        val snaps = SsaT0PoolStore.loadSnapshot(ctx)
        lastUpdTv.text = "上次更新：${snaps.maxOfOrNull { it.updated } ?: "从未"} · 池内 ${members.size} 票 · " +
            "更新=拉最新1H K线重跑并刷新下一根建议"
        if (members.isEmpty()) {
            poolBox.addView(hint("池为空：点【＋添加】加入持仓票（锁定当前三层参数），" +
                "之后点【▶ 一键更新全部】即可刷新状态、建议与回测绩效。"))
            return
        }
        members.forEach { m -> poolBox.addView(poolRow(m, snaps.find { it.code == m.code })) }
    }

    private fun poolRow(m: SsaT0PoolStore.PoolMember, s: SsaT0PoolStore.T0Snapshot?): View {
        val ctx = requireContext()
        return card {
            val row1 = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            row1.addView(TextView(ctx).apply {
                text = "${m.name} (${m.code})"; textSize = 15f; setTextColor(Color.parseColor("#222222"))
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (s != null) {
                val (w1, c1) = advWord(s.action)
                row1.addView(TextView(ctx).apply {
                    text = "▶ $w1"; textSize = 15f; setTextColor(c1)
                    setTypeface(typeface, Typeface.BOLD); setPadding(dp(8), 0, 0, 0)
                })
            }
            it.addView(row1)
            it.addView(TextView(ctx).apply {
                text = if (s == null) "尚未生成快照：点【🔄 更新】跑一次"
                else "截至 ${s.barLabel} 收盘 ${fmt2(s.close)} · ${stateCn(s.state)} · 持仓 ${"%.1f%%".format(s.holdPct)}"
                textSize = 11f; setTextColor(GRAY); setPadding(0, dp(4), 0, 0)
            })
            if (s != null) {
                it.addView(TextView(ctx).apply {
                    text = "回测收益 ${pct(s.ret)} · 回撤 ${pct(s.mdd)} · 胜率 ${"%.1f%%".format(s.winRate)} · T单 ${s.trades} 笔"
                    textSize = 10.5f; setTextColor(GRAY); setPadding(0, dp(2), 0, 0)
                })
                if (s.reason.isNotBlank()) it.addView(TextView(ctx).apply {
                    text = "依据：${s.reason}"
                    textSize = 10.5f; setTextColor(Color.parseColor("#555555")); setPadding(0, dp(2), 0, 0)
                })
            }
            val row4 = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0)
            }
            fun smallBtn(text: String, color: Int, weight: Float, onClick: () -> Unit) =
                Button(ctx).apply {
                    this.text = text; textSize = 11f; setTextColor(Color.WHITE); setBackgroundColor(color)
                    layoutParams = LinearLayout.LayoutParams(0, dp(32), weight).apply { marginEnd = dp(6) }
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
                if (busy) { status("⏳ 正在更新中…"); return@smallBtn }
                busy = true
                lifecycleScope.launch {
                    try {
                        status("⏳ 更新 ${m.name}(${m.code})…")
                        val snap = updateOne(m, POOL_HOURLY)
                        val snaps = SsaT0PoolStore.loadSnapshot(ctx).filterNot { it.code == m.code }.toMutableList()
                        if (snap != null) snaps.add(snap)
                        SsaT0PoolStore.saveSnapshot(ctx, snaps)
                        if (isAdded) renderPool()
                        status(if (snap != null) "✅ ${m.name} 已更新（建议：${advWord(snap.action).first}）"
                               else "⚠️ ${m.name} 更新失败（K线不足）")
                    } finally { busy = false }
                }
            })
            row4.addView(smallBtn("🗑 移除", RED, 1f) {
                SsaT0PoolStore.remove(ctx, m.code); renderPool(); status("已移除 ${m.name}(${m.code})")
            })
            it.addView(row4)
        }
    }

    /** 每小时简报：池内按 买入→卖出→持有→观望 排序的清单（手动刷新；无后台定时器） */
    private fun onBriefRefresh() {
        val ctx = requireContext()
        val members = SsaT0PoolStore.load(ctx)
        if (members.isEmpty()) { status("⚠️ 池为空：请先到【📋 持仓池】添加持仓票"); return }
        if (busy) { status("⏳ 正在更新中…"); return }
        busy = true; briefUpdBtn.isEnabled = false
        lifecycleScope.launch {
            try {
                members.forEachIndexed { i, m ->
                    status("⏳ 简报刷新 ${i + 1}/${members.size}：${m.name}(${m.code})…")
                    val snap = updateOne(m, POOL_HOURLY)
                    val snaps = SsaT0PoolStore.loadSnapshot(ctx).filterNot { it.code == m.code }.toMutableList()
                    if (snap != null) snaps.add(snap)
                    SsaT0PoolStore.saveSnapshot(ctx, snaps)
                }
                status("✅ 简报刷新完成（${members.size} 票）")
            } finally {
                busy = false; briefUpdBtn.isEnabled = true
            }
            if (isAdded) renderBrief()
        }
    }

    private fun renderBrief() {
        val ctx = requireContext()
        briefBox.removeAllViews()
        val members = SsaT0PoolStore.load(ctx)
        val snaps = SsaT0PoolStore.loadSnapshot(ctx).filter { sc -> members.any { it.code == sc.code } }
        briefUpdTv.text = "上次刷新：${snaps.maxOfOrNull { it.updated } ?: "从未"} · 池内 ${members.size} 票 · " +
            "手动刷新（无后台定时器）"
        val cnt = { a: Int -> snaps.count { it.action == a } }
        briefFilterRow.removeAllViews()
        listOf(-1 to "全部 ${snaps.size}",
            SsaT0Engine.BUY to "买入 ${cnt(SsaT0Engine.BUY)}",
            SsaT0Engine.SELL to "卖出 ${cnt(SsaT0Engine.SELL)}",
            SsaT0Engine.HOLD_POS to "持有 ${cnt(SsaT0Engine.HOLD_POS)}",
            SsaT0Engine.HOLD_EMPTY to "观望 ${cnt(SsaT0Engine.HOLD_EMPTY)}"
        ).forEach { (key, lbl) ->
            briefFilterRow.addView(chip(lbl, briefFilter == key) { briefFilter = key; renderBrief() })
        }
        val order = mapOf(SsaT0Engine.BUY to 0, SsaT0Engine.SELL to 1,
            SsaT0Engine.HOLD_POS to 2, SsaT0Engine.HOLD_EMPTY to 3)
        val shown = snaps.filter { briefFilter == -1 || it.action == briefFilter }
            .sortedWith(compareBy({ order[it.action] ?: 9 }, { it.code }))
        when {
            members.isEmpty() -> briefBox.addView(hint("池为空：请先到【📋 持仓池】添加持仓票，再回来刷新简报。"))
            snaps.isEmpty() -> briefBox.addView(hint("尚无快照：点【▶ 刷新简报】拉最新1H K线并按池内参数重跑。"))
            shown.isEmpty() -> briefBox.addView(hint("当前筛选下暂无持仓票"))
            else -> shown.forEachIndexed { i, s -> briefBox.addView(briefRow(i + 1, s)) }
        }
    }

    private fun briefRow(rank: Int, s: SsaT0PoolStore.T0Snapshot): View {
        val ctx = requireContext()
        val (w, c) = advWord(s.action)
        return card {
            val row1 = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            row1.addView(TextView(ctx).apply {
                text = "#$rank ${s.name} (${s.code})"; textSize = 14f; setTextColor(Color.parseColor("#222222"))
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row1.addView(TextView(ctx).apply {
                text = "▶ $w"; textSize = 15f; setTextColor(c)
                setTypeface(typeface, Typeface.BOLD); setPadding(dp(8), 0, 0, 0)
            })
            it.addView(row1)
            it.addView(TextView(ctx).apply {
                text = "${stateCn(s.state)} · 收盘 ${fmt2(s.close)} · 持仓 ${"%.1f%%".format(s.holdPct)} · ${s.barLabel}"
                textSize = 11f; setTextColor(GRAY); setPadding(0, dp(3), 0, 0)
            })
            if (s.reason.isNotBlank()) it.addView(TextView(ctx).apply {
                text = s.reason
                textSize = 11f; setTextColor(Color.parseColor("#444444")); setPadding(0, dp(2), 0, 0)
            })
        }
    }

    private fun chip(label: String, on: Boolean, onClick: () -> Unit): Button =
        Button(requireContext()).apply {
            text = label; textSize = 10.5f
            setTextColor(if (on) Color.WHITE else GRAY)
            setBackgroundColor(if (on) TEAL else Color.parseColor("#EFEFEF"))
            setPadding(dp(2), 0, dp(2), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(30), 1f).apply { marginEnd = dp(4) }
            setOnClickListener { onClick() }
        }

    // ═══════════════════════ 渲染 ═══════════════════════

    private fun render(name: String, code: String, r: Result) {
        resultBox.removeAllViews()
        // 📌 当前状态 & 下一根操作建议（与混合策略页「下一根操作建议」同口径）
        val ad = SsaT0Engine.adviceOf(r)
        resultBox.addView(card {
            it.addView(cardTitle("📌 当前状态 & 下一根操作建议（${name} $code）"))
            it.addView(adviceView(ad))
        })
        // 分段指标
        resultBox.addView(card {
            it.addView(cardTitle("📊 分段绩效（参数固定，样本外不回调）"))
            it.addView(segTable(listOf(r.train, r.test, r.full)))
        })
        // 周线下跌环境
        val wd = r.weekDown
        resultBox.addView(card {
            it.addView(cardTitle("🔻 周线下跌环境下的独立做T绩效"))
            it.addView(statGrid(listOf(
                "下跌环境T单数" to "${wd.trades} 笔",
                "胜率" to "%.1f%%".format(wd.winRatePct),
                "平均每笔" to pct(wd.avgPnlPct),
                "T单累计" to pct(wd.totalPnlPct)
            )))
        })
        // 周期扰动区间评估
        val be = r.bandEval
        resultBox.addView(card {
            it.addView(cardTitle("🎯 周期扰动区间评估（${be.layerName}，${be.bars} 根有效K）"))
            it.addView(statGrid(listOf(
                "区间命中率" to "%.1f%%".format(be.insideRatePct),
                "触上沿次数" to "${be.touchUp}",
                "触上沿回落率" to "%.1f%%".format(be.touchUpReversalPct),
                "触下沿次数" to "${be.touchDn}",
                "触下沿反弹率" to "%.1f%%".format(be.touchDnReversalPct),
                "平均偏离" to "%.2f%%".format(be.avgDevPct)
            )))
            it.addView(hint("区间由 SSA 残差噪声分量的滚动标准差 σ 生成，中线=trend+cycle（该段趋势中位价）；" +
                "命中率=收盘落于正常区间(±1.5σ)比例，回落/反弹率=触轨后下一根收盘反向比例。"))
        })
        // 主图 + ② 分量图 联动（同缩放）
        val hourChart = buildHourMain(r, 340)
        val hourLinked = ArrayList<BarLineChartBase<*>>()
        val cyclePanel = buildCyclePanel(r, hourChart) { list -> hourLinked.clear(); hourLinked.addAll(list) }
        // 主图：1H K线 + 周期扰动区间（激进/保守）同轴 + ABC + 买卖点 + 训练分界
        resultBox.addView(card {
            it.addView(cardTitle("① 1H 主图：三态背景分区 + 收盘折线 + 中线/扰动区间 + ▲买▼卖"))
            it.addView(hourRangeBar(hourChart, r.h.bars, hourLinked))
            it.addView(hourChart)
            it.addView(legend("背景色 红=UP(核心做多) 绿=WINDOW(上穿中位买) 灰=FLAT(空仓·极限突破T)  橙线=中线(trend+cycle)  " +
                "蓝=正常区间(±1.5σ) 红虚线=极限区间(±2.0σ，含填充)  ▲红=买入 ▼绿=卖出  竖虚线=训练/样本外分界"))
        })
        // ② 趋势/cycle 分量（可切时间周期；选「时」时与①主图同缩放）
        resultBox.addView(card {
            it.addView(cardTitle("② SSA 趋势中枢 / 周期振荡分量 S（区间宽度来源）"))
            it.addView(cyclePanel)
        })
        // 日线层
        resultBox.addView(card {
            it.addView(cardTitle("③ 日线层：收盘 + 周期扰动区间（中周期过滤 / 突破止损基准）"))
            it.addView(buildBandLayer(r.d, 200, dateFmt(r.d.bars)))
        })
        // 周线层
        resultBox.addView(card {
            it.addView(cardTitle("④ 周线层：收盘 + 周期扰动区间 + 趋势（环境总闸）"))
            it.addView(buildBandLayer(r.w, 170, dateFmt(r.w.bars)))
        })
        // 净值
        resultBox.addView(card {
            it.addView(cardTitle("⑤ 总权益（含底仓）vs 买入持有 + 回撤"))
            it.addView(buildEquity(r, 180))
        })
        // 风控日志
        resultBox.addView(card {
            it.addView(cardTitle("🛡️ 风控触发（${r.riskEvents.size} 次，最多显示40条）"))
            val txt = if (r.riskEvents.isEmpty()) "无风控触发。"
            else r.riskEvents.takeLast(40).joinToString("\n") { e -> "${e.time}  [${e.level}] ${e.reason}" }
            it.addView(TextView(requireContext()).apply {
                text = txt; textSize = 10f; setTextColor(Color.parseColor("#555555"))
            })
        })
        // 交易明细
        resultBox.addView(card {
            it.addView(cardTitle("📋 T单明细（${r.trades.size} 笔，最多显示60笔）"))
            val scroll2 = HorizontalScrollView(requireContext())
            val table = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            val headers = listOf("#", "方向", "开仓", "开价", "平仓", "平价", "收益", "1H根数", "周线", "离场")
            val widths = listOf(24, 38, 92, 56, 92, 56, 62, 56, 40, 120)
            table.addView(row(headers, widths, true))
            r.trades.takeLast(60).forEachIndexed { i, t ->
                val rr = tableRow(listOf(
                    "${i + 1}", t.direction, t.openTime.takeLast(11), "%.2f".format(t.openPrice),
                    t.closeTime.takeLast(11), "%.2f".format(t.closePrice),
                    "${if (t.returnPct > 0) "+" else ""}%.2f%%".format(t.returnPct),
                    "${t.barsHeld}", if (t.weeklyUpAtOpen) "↑" else "↓", t.exitReason
                ), widths)
                (rr.getChildAt(6) as TextView).apply {
                    setTextColor(if (t.returnPct > 0) RED else GREEN); setTypeface(typeface, Typeface.BOLD)
                }
                (rr.getChildAt(1) as TextView).setTextColor(if (t.direction == "正T") BLUE else PURPLE)
                (rr.getChildAt(8) as TextView).setTextColor(if (t.weeklyUpAtOpen) RED else GREEN)
                table.addView(rr)
            }
            scroll2.addView(table); it.addView(scroll2)
        })
    }

    private fun pct(v: Double) = "${if (v > 0) "+" else ""}%.2f%%".format(v)

    private fun segTable(segs: List<SegStats>): View {
        val scroll = HorizontalScrollView(requireContext())
        val table = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        val headers = listOf("分段", "总收益", "年化", "最大回撤", "T单数", "胜率", "笔均盈亏",
            "正T/反T", "平均持仓", "每周笔数", "买持基准", "超额")
        val widths = listOf(96, 70, 66, 70, 52, 58, 66, 64, 64, 66, 72, 66)
        table.addView(row(headers, widths, true))
        segs.forEach { s ->
            val rr = tableRow(listOf(
                s.name, pct(s.totalReturnPct), pct(s.annualizedPct), pct(s.maxDrawdownPct),
                "${s.tradeCount}", "%.1f%%".format(s.winRatePct), pct(s.avgPnlPct),
                "${s.longT}/${s.shortT}", "${s.avgBarsHeld}根",
                "%.2f".format(s.tradesPerWeek), pct(s.benchReturnPct), pct(s.excessPct)
            ), widths)
            (rr.getChildAt(1) as TextView).apply {
                setTextColor(if (s.totalReturnPct > 0) RED else GREEN); setTypeface(typeface, Typeface.BOLD)
            }
            // 每周笔数 <1 标橙提示
            (rr.getChildAt(9) as TextView).setTextColor(if (s.tradesPerWeek < 1.0) ORANGE else Color.parseColor("#333333"))
            table.addView(rr)
        }
        scroll.addView(table)
        return scroll
    }

    private fun statGrid(cells: List<Pair<String, String>>): View {
        val grid = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        cells.chunked(4).forEach { rc ->
            val r2 = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            rc.forEach { (k, v) ->
                val c = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6))
                    background = roundedBg("#FAFAFA", "#EEEEEE", 6)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginEnd = dp(6); bottomMargin = dp(6) }
                }
                c.addView(TextView(requireContext()).apply { text = k; textSize = 9f; setTextColor(GRAY) })
                c.addView(TextView(requireContext()).apply {
                    text = v; textSize = 12f; setTypeface(typeface, Typeface.BOLD)
                    setTextColor(when { v.startsWith("+") -> RED; v.startsWith("-") -> GREEN
                        else -> Color.parseColor("#333333") })
                })
                r2.addView(c)
            }
            repeat(4 - rc.size) { r2.addView(View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            }) }
            grid.addView(r2)
        }
        return grid
    }

    // ═══════════════════════ 图表 ═══════════════════════

    private fun dateFmt(bars: List<Bar>) = object : ValueFormatter() {
        override fun getFormattedValue(v: Float): String {
            val i = v.toInt()
            if (i !in bars.indices) return ""
            val b = bars[i]
            return "${b.date.substring(4, 6)}/${b.date.substring(6, 8)}"
        }
    }
    private fun timeFmt(bars: List<Bar>) = object : ValueFormatter() {
        override fun getFormattedValue(v: Float): String {
            val i = v.toInt()
            if (i !in bars.indices) return ""
            val b = bars[i]
            return "${b.date.substring(4, 6)}/${b.date.substring(6, 8)} ${b.time.takeLast(5)}"
        }
    }

    private fun styleX(chart: com.github.mikephil.charting.charts.BarLineChartBase<*>, bars: List<Bar>, time: Boolean) {
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            granularity = (bars.size / 7).coerceAtLeast(1).toFloat()
            labelCount = 7; textSize = 7f; textColor = GRAY
            setDrawGridLines(false); setAvoidFirstLastClipping(true)
            valueFormatter = if (time) timeFmt(bars) else dateFmt(bars)
        }
        chart.axisLeft.apply { textSize = 8f; textColor = GRAY; gridColor = Color.parseColor("#EEEEEE") }
        chart.axisRight.isEnabled = false
    }

    private fun splitLine(x: Float): LimitLine = LimitLine(x, "训练/样本外").apply {
        lineColor = Color.parseColor("#7B1FA2"); lineWidth = 1.2f
        enableDashedLine(8f, 6f, 0f); textSize = 7f; textColor = PURPLE
    }

    private fun bandSet(arr: DoubleArray, label: String, color: Int, dashed: Boolean, width: Float = 1f) =
        LineDataSet(entries(arr), label).apply {
            this.color = color; lineWidth = width
            setDrawCircles(false); setDrawValues(false); isHighlightEnabled = false
            if (dashed) enableDashedLine(7f, 5f, 0f)
        }

    private fun entries(a: DoubleArray) = ArrayList<Entry>().also { es ->
        a.forEachIndexed { i, v -> if (!v.isNaN()) es.add(Entry(i.toFloat(), v.toFloat())) }
    }

    /**
     * 支持「三态背景分区（axvspan 效果）」与「区间带填充」的 CombinedChart。
     * 背景色块/填充在 super.onDraw 之前绘制 → 位于网格与K线之下、图背景之上（对齐 Python 参考图）。
     */
    private class RegimeChart(context: android.content.Context) : CombinedChart(context) {
        data class Span(val from: Float, val to: Float, val color: Int)
        data class BandFill(val up: DoubleArray, val dn: DoubleArray, val color: Int, val alpha: Int)
        /** 买卖三角标记：index=K线下标，price=锚点价，up=true 为买入▲（画在下方）/false 为卖出▼（画在上方） */
        data class Tri(val index: Int, val price: Double, val up: Boolean, val color: Int)

        var spans: List<Span> = emptyList()
        var bandFills: List<BandFill> = emptyList()
        var triangles: List<Tri> = emptyList()

        private val spanPaint = Paint().apply { style = Paint.Style.FILL }
        private val fillPaint = Paint().apply { style = Paint.Style.FILL }
        private val triPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }

        override fun onDraw(c: Canvas) {
            if (data != null) {
                val t = getTransformer(AxisDependency.LEFT)
                val content = viewPortHandler.contentRect
                val save = c.save()
                c.clipRect(content)
                for (s in spans) {
                    val x1 = t.getPixelForValues(s.from - 0.5f, 0f).x.toFloat()
                    val x2 = t.getPixelForValues(s.to + 0.5f, 0f).x.toFloat()
                    spanPaint.color = s.color; spanPaint.alpha = 24
                    c.drawRect(x1, content.top, x2, content.bottom, spanPaint)
                }
                for (b in bandFills) {
                    val path = Path()
                    var started = false
                    for (i in b.up.indices) {
                        if (b.up[i].isNaN() || b.dn[i].isNaN()) continue
                        val yUp = t.getPixelForValues(i.toFloat(), b.up[i].toFloat()).y.toFloat()
                        val x = t.getPixelForValues(i.toFloat(), 0f).x.toFloat()
                        if (!started) { path.moveTo(x, yUp); started = true } else path.lineTo(x, yUp)
                    }
                    for (i in b.dn.indices.reversed()) {
                        if (b.up[i].isNaN() || b.dn[i].isNaN()) continue
                        val yDn = t.getPixelForValues(i.toFloat(), b.dn[i].toFloat()).y.toFloat()
                        val x = t.getPixelForValues(i.toFloat(), 0f).x.toFloat()
                        path.lineTo(x, yDn)
                    }
                    if (started) {
                        path.close()
                        fillPaint.color = b.color; fillPaint.alpha = b.alpha
                        c.drawPath(path, fillPaint)
                    }
                }
                c.restoreToCount(save)
            }
            super.onDraw(c)
            // 买卖三角画在 super 之后 → 位于 K线之上（对齐 Python scatter zorder）
            if (data != null && triangles.isNotEmpty()) {
                val t = getTransformer(AxisDependency.LEFT)
                val content = viewPortHandler.contentRect
                val save = c.save()
                c.clipRect(content)
                val s = 9f
                for (tri in triangles) {
                    val p = t.getPixelForValues(tri.index.toFloat(), tri.price.toFloat())
                    val x = p.x.toFloat(); val y = p.y.toFloat()
                    val path = Path()
                    if (tri.up) {
                        path.moveTo(x, y - s); path.lineTo(x - s, y + s); path.lineTo(x + s, y + s)
                    } else {
                        path.moveTo(x, y + s); path.lineTo(x - s, y - s); path.lineTo(x + s, y - s)
                    }
                    path.close()
                    triPaint.color = tri.color
                    c.drawPath(path, triPaint)
                }
                c.restoreToCount(save)
            }
        }
    }

    /** 由 regime 序列生成连续同态区段（1=UP 红 / 3=WINDOW 绿 / 2=FLAT 灰） */
    private fun regimeSpans(reg: IntArray): List<RegimeChart.Span> {
        val out = ArrayList<RegimeChart.Span>()
        var i = 0
        while (i < reg.size) {
            val rv = reg[i]; var j = i
            while (j + 1 < reg.size && reg[j + 1] == rv) j++
            val c = when (rv) { 1 -> RED; 3 -> GREEN; 2 -> GRAY; else -> 0 }
            if (c != 0) out.add(RegimeChart.Span(i.toFloat(), j.toFloat(), c))
            i = j + 1
        }
        return out
    }

    /** 主图显示范围选择条：全部 / 近6个月 / 近3个月（按实际日期切片，默认近3个月）；
     *  also = 与主图同缩放的副图（② 分量图「时」层），一并应用同一可见区间 */
    private fun hourRangeBar(chart: CombinedChart, bars: List<Bar>,
                             also: List<BarLineChartBase<*>> = emptyList()): LinearLayout {
        val ctx = requireContext()
        val opts = listOf("全部" to 0, "近6个月" to barCountSinceMonths(bars, 6), "近3个月" to barCountSinceMonths(bars, 3))
        val group = RadioGroup(ctx).apply { orientation = RadioGroup.HORIZONTAL }
        group.setOnCheckedChangeListener { g, id ->
            val rb = g.findViewById<RadioButton>(id) ?: return@setOnCheckedChangeListener
            val n = rb.tag as Int
            applyHourRange(chart, bars.size, n)
            also.forEach { applyHourRange(it, bars.size, n) }
        }
        opts.forEach { (label, n) ->
            group.addView(RadioButton(ctx).apply {
                id = View.generateViewId(); text = label; textSize = 12f; tag = n
                setPadding(0, 0, dp(16), 0)
            })
        }
        (group.getChildAt(2) as RadioButton).isChecked = true   // 默认近3个月
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2)); addView(group)
        }
    }

    /** 设置可见区间：n<=0 或 >=total 显示全部，否则显示最后 n 根（post 到布局完成后执行） */
    private fun applyHourRange(chart: BarLineChartBase<*>, total: Int, n: Int) {
        chart.post {
            if (n <= 0 || n >= total) {
                chart.setVisibleXRangeMaximum(total.toFloat())
                chart.moveViewToX(0f)
            } else {
                chart.setVisibleXRangeMaximum(n.toFloat())
                chart.moveViewToX((total - n).toFloat())
            }
            chart.invalidate()
        }
    }

    /** 主图/副图 X 视口联动（scaleX/transX 同步；Y 各自适应），用于 ② 与 ① 同缩放 */
    private fun linkCharts(charts: List<BarLineChartBase<*>>) {
        fun syncFrom(src: BarLineChartBase<*>) {
            val sv = FloatArray(9)
            src.viewPortHandler.matrixTouch.getValues(sv)
            charts.forEach { dst ->
                if (dst !== src) {
                    val dm = Matrix(dst.viewPortHandler.matrixTouch)
                    val dv = FloatArray(9); dm.getValues(dv)
                    dv[Matrix.MSCALE_X] = sv[Matrix.MSCALE_X]
                    dv[Matrix.MTRANS_X] = sv[Matrix.MTRANS_X]
                    dm.setValues(dv)
                    dst.viewPortHandler.refresh(dm, dst, true)
                }
            }
        }
        charts.forEach { c ->
            c.setScaleEnabled(true); c.setPinchZoom(true); c.setDragEnabled(true)
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
        }
    }

    /** 最近 months 个月内的 K 线根数 */
    private fun barCountSinceMonths(bars: List<Bar>, months: Long): Int {
        if (bars.isEmpty()) return 0
        val fmt = DateTimeFormatter.BASIC_ISO_DATE
        val cutoff = LocalDate.parse(bars.last().date, fmt).minusMonths(months)
        var idx = bars.size - 1
        while (idx > 0 && LocalDate.parse(bars[idx - 1].date, fmt) >= cutoff) idx--
        return bars.size - idx
    }

    /** ① 1H 主图（三态背景分区 + 收盘折线 + 中线/四轨道同轴 + 区间填充 + 买卖点 + 分界） */
    private fun buildHourMain(r: Result, h: Int): CombinedChart {
        val layer = r.h; val bars = layer.bars
        val chart = RegimeChart(requireContext())
        chart.spans = regimeSpans(r.regime)
        chart.bandFills = listOf(
            RegimeChart.BandFill(layer.eUp, layer.eDn, RED, 12),
            RegimeChart.BandFill(layer.nUp, layer.nDn, Color.parseColor("#607D8B"), 34)
        )
        // 买卖三角：买入▲落在当根 low 下方，卖出▼落在当根 high 上方
        chart.triangles = r.markers.map { m ->
            val b = bars[m.index]
            if (m.buy) RegimeChart.Tri(m.index, b.low * 0.99, true, BUY_TRI)
            else RegimeChart.Tri(m.index, b.high * 1.01, false, SELL_TRI)
        }
        chart.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h))
        chart.setBackgroundColor(Color.WHITE); chart.description.isEnabled = false
        chart.legend.textSize = 7f; chart.legend.textColor = GRAY
        chart.setScaleEnabled(true); chart.setPinchZoom(true); chart.setDragEnabled(true)
        chart.setHighlightPerTapEnabled(false); chart.setHighlightPerDragEnabled(false)
        chart.setVisibleXRangeMaximum(240f); chart.setVisibleXRangeMinimum(60f)
        chart.moveViewToX((bars.size - 1).toFloat())
        chart.drawOrder = arrayOf(CombinedChart.DrawOrder.LINE)

        // 黑色折线（1H 收盘）替代 K 线
        val closeEs = ArrayList<Entry>()
        bars.forEachIndexed { i, b -> closeEs.add(Entry(i.toFloat(), b.close.toFloat())) }
        val closeDs = LineDataSet(closeEs, "1H收盘").apply {
            color = Color.BLACK; lineWidth = 1.2f
            setDrawCircles(false); setDrawValues(false); isHighlightEnabled = false
        }
        val ld = LineData(
            bandSet(layer.eDn, "保守下轨", RED, true, 0.9f),
            bandSet(layer.nDn, "激进下轨", BLUE, false, 1.1f),
            bandSet(layer.nUp, "激进上轨", BLUE, false, 1.1f),
            bandSet(layer.eUp, "保守上轨", RED, true, 0.9f),
            bandSet(layer.mid, "中线=trend+cycle", MID_ORANGE, false, 1.6f),
            closeDs
        )
        chart.data = CombinedData().apply { setData(ld) }
        styleX(chart, bars, time = true)
        chart.xAxis.addLimitLine(splitLine(r.splitIndex.toFloat()))
        chart.post { chart.invalidate() }
        return chart
    }

    /** ② SSA 分量面板：可切换时间周期（时/日/周），显示该层「趋势中枢 trend」与「周期振荡分量 cycle」；
     *  选「时(1H)」时与①主图 X 视口同缩放联动；选「日/周」时仅面板内两图互相联动。
     *  onHourChartsReady：当前层若为小时线，回调其图表列表供主图区间选择条一并应用同一可见区间。 */
    private fun buildCyclePanel(r: Result, mainChart: CombinedChart,
                                onHourChartsReady: (List<BarLineChartBase<*>>) -> Unit): View {
        val ctx = requireContext()
        val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val holder = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val layers = listOf("时(1H)" to r.h, "日线" to r.d, "周线" to r.w)

        fun show(idx: Int) {
            holder.removeAllViews()
            val (label, layer) = layers[idx]
            val isHour = idx == 0
            val fmt = if (isHour) timeFmt(layer.bars) else dateFmt(layer.bars)
            val trendChart = miniLine(listOf(line(layer.trend, "趋势中枢", ORANGE, 1.5f)),
                layer.bars, if (isHour) 120 else 140, fmt, false)
            val cycChart = miniLine(listOf(line(layer.cycle, "cycle", PURPLE, 1.2f)),
                layer.bars, 96, fmt, true)
            holder.addView(hint("趋势中枢 trend（$label 层，${layer.bars.size} 根）"))
            holder.addView(trendChart)
            holder.addView(hint("周期振荡分量 cycle（$label 层；>0=UP 中位线在趋势线上方，<0=WINDOW/FLAT）"))
            holder.addView(cycChart)
            if (isHour) {
                onHourChartsReady(listOf(trendChart, cycChart))
                linkCharts(listOf(mainChart, trendChart, cycChart))
            } else {
                onHourChartsReady(emptyList())
                linkCharts(listOf(trendChart, cycChart))
            }
        }

        val group = RadioGroup(ctx).apply { orientation = RadioGroup.HORIZONTAL }
        layers.forEachIndexed { i, _ ->
            group.addView(RadioButton(ctx).apply {
                id = View.generateViewId(); text = layers[i].first; textSize = 12f; tag = i
                setPadding(0, 0, dp(20), 0)
            })
        }
        (group.getChildAt(0) as RadioButton).isChecked = true
        group.setOnCheckedChangeListener { g, id ->
            val rb = g.findViewById<RadioButton>(id) ?: return@setOnCheckedChangeListener
            show(rb.tag as Int)
        }
        wrap.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2)); addView(group)
        })
        wrap.addView(holder)
        show(0)   // 默认「时(1H)」层，与主图同缩放
        return wrap
    }

    private fun line(a: DoubleArray, nm: String, c: Int, w: Float): LineDataSet =
        LineDataSet(entries(a), nm).apply {
            color = c; lineWidth = w; setDrawCircles(false); setDrawValues(false); isHighlightEnabled = false
        }

    private fun miniLine(sets: List<LineDataSet>, bars: List<Bar>, h: Int,
                         fmt: ValueFormatter, zeroLine: Boolean): LineChart {
        val chart = LineChart(requireContext())
        chart.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h))
        chart.setBackgroundColor(Color.WHITE); chart.description.isEnabled = false
        chart.legend.textSize = 7f; chart.legend.textColor = GRAY
        chart.setScaleEnabled(true); chart.setPinchZoom(true); chart.setDragEnabled(true)
        chart.setHighlightPerTapEnabled(false); chart.setHighlightPerDragEnabled(false)
        chart.setVisibleXRangeMaximum((bars.size + 2).toFloat()); chart.setVisibleXRangeMinimum(40f)
        chart.moveViewToX((bars.size - 1).toFloat())
        chart.data = LineData(sets)
        if (zeroLine) chart.axisLeft.addLimitLine(LimitLine(0f, "").apply {
            lineColor = Color.parseColor("#CCCCCC"); lineWidth = 1f
        })
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            granularity = (bars.size / 7).coerceAtLeast(1).toFloat(); labelCount = 7
            textSize = 7f; textColor = GRAY; setDrawGridLines(false); valueFormatter = fmt
        }
        chart.axisLeft.textSize = 7f; chart.axisLeft.textColor = GRAY
        chart.axisRight.isEnabled = false
        chart.post { chart.invalidate() }
        return chart
    }

    /** ③④ 日/周层：收盘+四轨道+ABC（全同轴） */
    private fun buildBandLayer(layer: Layer, h: Int, fmt: ValueFormatter): LineChart {
        val chart = LineChart(requireContext())
        chart.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h))
        chart.setBackgroundColor(Color.WHITE); chart.description.isEnabled = false
        chart.legend.textSize = 7f; chart.legend.textColor = GRAY
        chart.setScaleEnabled(true); chart.setPinchZoom(true); chart.setDragEnabled(true)
        chart.setHighlightPerTapEnabled(false); chart.setHighlightPerDragEnabled(false)
        chart.setVisibleXRangeMaximum((layer.bars.size + 2).toFloat()); chart.setVisibleXRangeMinimum(30f)
        chart.moveViewToX((layer.bars.size - 1).toFloat())
        val closeSet = LineDataSet(entries(DoubleArray(layer.bars.size) { i -> layer.bars[i].close }), "收盘").apply {
            color = Color.parseColor("#222222"); lineWidth = 1.3f
            setDrawCircles(false); setDrawValues(false); isHighlightEnabled = false
        }
        chart.data = LineData(
            bandSet(layer.eDn, "保守下轨", RED, true, 0.9f),
            bandSet(layer.nDn, "激进下轨", BLUE, false),
            closeSet,
            bandSet(layer.nUp, "激进上轨", BLUE, false),
            bandSet(layer.eUp, "保守上轨", RED, true, 0.9f)
        )
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            granularity = (layer.bars.size / 7).coerceAtLeast(1).toFloat(); labelCount = 7
            textSize = 7f; textColor = GRAY; setDrawGridLines(false); valueFormatter = fmt
        }
        chart.axisLeft.textSize = 8f; chart.axisLeft.textColor = GRAY
        chart.axisLeft.gridColor = Color.parseColor("#EEEEEE")
        chart.axisRight.isEnabled = false
        chart.post { chart.invalidate() }
        return chart
    }

    /** ⑤ 权益/基准/回撤 */
    private fun buildEquity(r: Result, h: Int): LineChart {
        val bars = r.h.bars
        val chart = LineChart(requireContext())
        chart.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h))
        chart.setBackgroundColor(Color.WHITE); chart.description.isEnabled = false
        chart.legend.textSize = 8f; chart.legend.textColor = GRAY
        chart.setScaleEnabled(true); chart.setPinchZoom(true); chart.setDragEnabled(true)
        chart.setHighlightPerTapEnabled(false); chart.setHighlightPerDragEnabled(false)
        chart.setVisibleXRangeMaximum((bars.size + 2).toFloat()); chart.setVisibleXRangeMinimum(60f)
        chart.moveViewToX((bars.size - 1).toFloat())
        val es = ArrayList<Entry>(); val bs = ArrayList<Entry>(); val ds = ArrayList<Entry>()
        bars.indices.forEach { i ->
            es.add(Entry(i.toFloat(), r.equity[i].toFloat()))
            bs.add(Entry(i.toFloat(), r.benchmark[i].toFloat()))
        }
        val data = LineData(
            LineDataSet(es, "总权益(含底仓)").apply {
                color = TEAL; lineWidth = 1.6f; setDrawCircles(false); setDrawValues(false); isHighlightEnabled = false
            },
            LineDataSet(bs, "买入持有").apply {
                color = GRAY; lineWidth = 1.1f; setDrawCircles(false); setDrawValues(false); isHighlightEnabled = false
                enableDashedLine(6f, 4f, 0f)
            }
        )
        chart.data = data
        styleX(chart, bars, time = true)
        chart.xAxis.addLimitLine(splitLine(r.splitIndex.toFloat()))
        chart.axisLeft.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(v: Float) = "%,.0f".format(v)
        }
        chart.post { chart.invalidate() }
        return chart
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
            .map { Bar(it.date.replace("-", ""), "", it.open.ifZero(it.close), it.high, it.low, it.close) }
        return if (bars.size < 150) null else bars
    }

    private fun Double.ifZero(f: Double) = if (this == 0.0) f else this

    // ═══════════════════════ UI 工具 ═══════════════════════

    private fun status(s: String) { if (isAdded) statusTv.text = s }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun roundedBg(fill: String, stroke: String, r: Int) = GradientDrawable().apply {
        cornerRadius = dp(r).toFloat(); setColor(Color.parseColor(fill)); setStroke(dp(1), Color.parseColor(stroke))
    }
    private fun sectionTitle(t: String) = TextView(requireContext()).apply {
        text = t; textSize = 17f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#222222")); setPadding(0, dp(4), 0, dp(2))
    }
    private fun label(t: String) = TextView(requireContext()).apply {
        text = t; textSize = 11f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#666666")); setPadding(dp(2), dp(8), 0, dp(3))
    }
    private fun hint(t: String) = TextView(requireContext()).apply {
        text = t; textSize = 10f; setTextColor(GRAY); setPadding(dp(2), dp(3), dp(2), dp(3))
    }
    private fun legend(t: String) = TextView(requireContext()).apply {
        text = t; textSize = 9f; setTextColor(GRAY); setPadding(0, dp(4), 0, 0)
    }
    private fun cardTitle(t: String) = TextView(requireContext()).apply {
        text = t; textSize = 12f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#333333")); setPadding(0, 0, 0, dp(6))
    }
    private fun card(build: (LinearLayout) -> Unit) = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(10))
        background = roundedBg("#FFFFFF", "#E8E8E8", 8)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        build(this)
    }
    private fun row(cells: List<String>, widthsDp: List<Int>, header: Boolean): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(if (header) Color.parseColor("#F0F0F0") else Color.WHITE)
            cells.forEachIndexed { i, txt ->
                addView(TextView(requireContext()).apply {
                    text = txt; textSize = 9f
                    gravity = Gravity.CENTER_VERTICAL or
                        (if (i == 0 || i == cells.lastIndex) Gravity.START else Gravity.CENTER_HORIZONTAL)
                    setTextColor(if (header) Color.parseColor("#666666") else Color.parseColor("#333333"))
                    if (header) setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(4), dp(5), dp(4), dp(5))
                    layoutParams = LinearLayout.LayoutParams(dp(widthsDp[i]), LinearLayout.LayoutParams.WRAP_CONTENT)
                })
            }
        }
    }
    private fun tableRow(cells: List<String>, widthsDp: List<Int>) = row(cells, widthsDp, false)
}
