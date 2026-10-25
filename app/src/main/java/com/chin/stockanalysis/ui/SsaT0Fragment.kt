package com.chin.stockanalysis.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.Matrix
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.agent.stock.StockAnalysisAgent
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import com.chin.stockanalysis.strategy.data.HistoricalDataFetcher
import com.chin.stockanalysis.strategy.data.IntradayKlineFetcher
import com.chin.stockanalysis.strategy.ssa.SsaAlertBatch
import com.chin.stockanalysis.strategy.ssa.SsaAlertEngine
import com.chin.stockanalysis.strategy.ssa.SsaAlertStore
import com.chin.stockanalysis.strategy.ssa.SsaBbiErEngine
import com.chin.stockanalysis.strategy.ssa.SsaBbiPivotEngine
import com.chin.stockanalysis.strategy.ssa.SsaCycleForecast
import com.chin.stockanalysis.strategy.ssa.SsaErLocal
import com.chin.stockanalysis.strategy.ssa.SsaErQuantile
import com.chin.stockanalysis.strategy.ssa.SsaHybridEngine
import com.chin.stockanalysis.strategy.ssa.SsaRangeTEngine
import com.chin.stockanalysis.strategy.ssa.SsaT0Engine
import com.chin.stockanalysis.strategy.ssa.PredictionLedger
import com.chin.stockanalysis.strategy.ssa.SsaT0Engine.Bar
import com.chin.stockanalysis.strategy.ssa.SsaT0Engine.Layer
import com.chin.stockanalysis.strategy.ssa.SsaT0Engine.LayerCfg
import com.chin.stockanalysis.strategy.ssa.SsaT0Engine.Params
import com.chin.stockanalysis.strategy.ssa.SsaT0Engine.Result
import com.chin.stockanalysis.strategy.ssa.SsaT0Engine.SegStats
import com.chin.stockanalysis.strategy.ssa.SsaT0PoolStore
import com.chin.stockanalysis.service.SsaAlertOverlayService
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.data.PcBridgeClient
import com.chin.stockanalysis.stock.data.StockDataFacade
import com.chin.stockanalysis.stock.database.StockDatabase
import com.github.mikephil.charting.charts.CombinedChart
import com.github.mikephil.charting.charts.BarLineChartBase
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis.AxisDependency
import com.github.mikephil.charting.data.CandleData
import com.github.mikephil.charting.data.CandleDataSet
import com.github.mikephil.charting.data.CandleEntry
import com.github.mikephil.charting.data.CombinedData
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.ChartTouchListener.ChartGesture
import com.github.mikephil.charting.listener.OnChartGestureListener
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    private val CYAN = Color.parseColor("#00ACC1")

    private lateinit var codeInput: EditText
    private lateinit var runBtn: Button
    private lateinit var statusTv: TextView
    private lateinit var resultBox: LinearLayout
    private val cfgInputs = ArrayList<EditText>()   // 顺序：周L M N 日L M N 时L M N
    private lateinit var corePctEt: EditText
    private lateinit var tPctEt: EditText
    private lateinit var slipEt: EditText

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

    // ── 预警（短线技术指标：JAX/OBV/KDJ/WR/BB）──
    private lateinit var modeBtnAlert: Button
    private lateinit var alertTools: LinearLayout
    private lateinit var alertCodeEt: EditText
    private lateinit var alertRunBtn: Button
    private lateinit var alertBox: LinearLayout
    private lateinit var alertOverlayRow: LinearLayout
    private lateinit var alertOverlaySw: SwitchCompat
    private lateinit var alertOverlayTv: TextView
    private lateinit var alertBatchRow: LinearLayout
    private lateinit var alertBatchBtn: Button
    private lateinit var scanPoolBtn: Button
    /** 预警页策略多选区（4 个复选框，key = [SsaAlertStore.STRAT_*]） */
    private val alertStratBoxes = LinkedHashMap<String, CheckBox>()
    private lateinit var alertStratRow: LinearLayout
    private lateinit var alertStratTv: TextView

    // ── ER分位（PC 策略推送候选 + 持仓池 · 跨票横截面 Kaufman 效率比分位）──
    private lateinit var modeBtnEr: Button
    private lateinit var erTools: LinearLayout
    private lateinit var erUpdBtn: Button
    private lateinit var erUpdTv: TextView
    private lateinit var erBox: LinearLayout
    /** PC 推送的 ER 分位表：6 位代码 → 0~100（池=每日荐股核心池；空=未拉取/不可达 → UI 显示「—」） */
    private var erMap: Map<String, Double> = emptyMap()
    /** 当前 erMap 是否来自「本机备选计算」（PC 推送不可用时的降级口径） */
    private var erSourceLocal: Boolean = false
    /** 当前 erMap 的池口径文案（如「每日荐股池 172 只」/「全池 688 只」/「本机池 3 只」） */
    private var erPoolNote: String = ""
    /** 持仓票并入横截面补算的文案（如「 · 持仓 3 只并入补算」；无补算为空串） */
    private var erBackfillNote: String = ""

    /** 策略勾选项：id（持久化） → 展示文案（2026-10-21 新增「区间做T」） */
    private val STRAT_ITEMS = listOf(
        SsaAlertStore.STRAT_MIX_PURE_BBI12 to "融合①纯 BBI12 拐点（最平稳）",
        SsaAlertStore.STRAT_ERBBI_STEADY to "BBI12拐点+止盈8%+Δ5ER过滤（稳健）",
        SsaAlertStore.STRAT_ERBBI_AGGRO to "BBI12拐点+止盈8%（进取）",
        SsaAlertStore.STRAT_RANGE_T to "区间做T（N14唐奇安+ER10≤0.45，震荡低吸高抛）"
    )

    /** 单票建议策略多选区（预警页四种策略 + 混合策略；默认全选） */
    private val singleStratBoxes = LinkedHashMap<String, CheckBox>()

    /** 仅单票页提供的第五项：混合策略（SSA 三态，沿用现有三态图） */
    private val SINGLE_HYBRID_KEY = "HYBRID_SSA"
    private val SINGLE_STRAT_ITEMS = STRAT_ITEMS + (SINGLE_HYBRID_KEY to "混合策略（SSA 三态做T，含三态图）")

    /** 单票策略的建议结果（展示用） */
    private data class StratAdvice(val label: String, val advice: String, val reason: String)

    /** 单票策略计算产物：建议 + 各策略画图所需序列 */
    private data class StratOutput(
        val picked: Set<String>,
        val advices: List<StratAdvice>,
        val calc: List<SsaAlertEngine.Bar>,
        val rangeState: SsaRangeTEngine.State?,
        val er10: DoubleArray?,
        val donLo: DoubleArray?,
        val donHi: DoubleArray?,
        val bbiBundle: SsaBbiPivotEngine.MixBundle?,
        val bbiSteady: SsaBbiErEngine.State?,
        val bbiAggro: SsaBbiErEngine.State?
    )

    private fun singlePicked(): Set<String> {
        val saved = requireContext().getSharedPreferences("ssa_single", Context.MODE_PRIVATE)
            .getStringSet("strategies", null)
        return saved ?: STRAT_ITEMS.map { it.first }.toSet()
    }

    private fun persistSinglePicked() {
        requireContext().getSharedPreferences("ssa_single", Context.MODE_PRIVATE).edit()
            .putStringSet("strategies", singleStratBoxes.filterValues { it.isChecked }.keys.toSet())
            .apply()
    }

    private fun mixModeOf(key: String): SsaBbiPivotEngine.MixMode? = when (key) {
        SsaAlertStore.STRAT_MIX_PURE_BBI12 -> SsaBbiPivotEngine.MixMode.PURE_BBI12
        SsaAlertStore.STRAT_MIX_BBI12_BB6_OR -> SsaBbiPivotEngine.MixMode.BBI12_BB6_OR
        SsaAlertStore.STRAT_MIX_BBI12_BB6_BBWIN -> SsaBbiPivotEngine.MixMode.BBI12_BB6_BBWIN
        else -> null
    }

    /** 勾选变更：持久化 + 刷新提示 */
    private val stratCbListener = CompoundButton.OnCheckedChangeListener { _, _ ->
        SsaAlertStore.setAlertStrategies(requireContext(),
            alertStratBoxes.filterValues { it.isChecked }.keys.toSet())
        updateAlertStratTv()
    }

    private fun updateAlertStratTv() {
        val n = alertStratBoxes.count { it.value.isChecked }
        alertStratTv.text = if (n == 0)
            "⚠️ 未勾选任何策略（请在分析前至少勾选一项）"
        else "已勾选 $n 项；点【▶ 预警分析】一次全部输出。"
    }

    /** 申请「显示在其他应用上层」权限的返回处理 */
    private val overlayPermLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val ok = Settings.canDrawOverlays(requireContext())
        alertOverlaySw.setOnCheckedChangeListener(null)
        alertOverlaySw.isChecked = ok
        alertOverlaySw.setOnCheckedChangeListener(overlaySwListener)
        if (ok) {
            SsaAlertStore.setOverlayEnabled(requireContext(), true)
            alertOverlayTv.text = "已开启 · 退出 App 后浮窗出现"
            Toast.makeText(requireContext(), "✅ 已开启，退出 App 后浮窗出现", Toast.LENGTH_SHORT).show()
        } else {
            alertOverlayTv.text = "未授权「显示在其他应用上层」，浮窗无法显示"
            Toast.makeText(requireContext(), "⚠️ 未获得悬浮窗权限", Toast.LENGTH_SHORT).show()
        }
    }

    private val overlaySwListener: CompoundButton.OnCheckedChangeListener =
        CompoundButton.OnCheckedChangeListener { _, on ->
            val ctx = requireContext()
            if (on) {
                if (!Settings.canDrawOverlays(ctx)) {
                    alertOverlayTv.text = "请先在系统设置中允许「显示在其他应用上层」"
                    runCatching {
                        overlayPermLauncher.launch(Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + ctx.packageName)
                        ))
                    }.onFailure {
                        alertOverlaySw.setOnCheckedChangeListener(null)
                        alertOverlaySw.isChecked = false
                        alertOverlaySw.setOnCheckedChangeListener(overlaySwListener)
                    }
                } else {
                    SsaAlertStore.setOverlayEnabled(ctx, true)
                    alertOverlayTv.text = "已开启 · 退出 App 后浮窗出现"
                    Toast.makeText(ctx, "✅ 已开启，退出 App 后浮窗出现", Toast.LENGTH_SHORT).show()
                }
            } else {
                SsaAlertStore.setOverlayEnabled(ctx, false)
                SsaAlertOverlayService.stop(ctx)
                alertOverlayTv.text = "已关闭"
            }
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

        box.addView(sectionTitle("🔄 三层 SSA 稳态做T"))
        box.addView(hint("主体层=小时线**三态机**：以「中位线 vs 趋势线」(=cycle符号) 划分 UP/WINDOW/FLAT。" +
            "UP(cycle>0) 核心做多、超极限卖/破极限买回做T；WINDOW(cycle<0且窗口开) 上穿中位买、触正常上沿卖；" +
            "FLAT(cycle<0窗口关) 空仓、仅极限突破做T。固定滚动窗口、无未来函数；含佣金+印花税+滑点；70%训练/30%样本外。"))

        // ── 模式切换：单票建议 / 持仓池 / 每小时简报 / 预警 / ER分位（两行排布，避免 5 键挤压）──
        val modeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        val modeRow2 = LinearLayout(ctx).apply {
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
        modeBtnAlert = Button(ctx).apply {
            text = "⚠ 预警"; textSize = 12f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#B0BEC5"))
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(6) }
            setOnClickListener { switchMode(3) }
        }
        modeBtnEr = Button(ctx).apply {
            text = "📊 ER分位"; textSize = 12f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#B0BEC5"))
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
            setOnClickListener { switchMode(4) }
        }
        modeRow.addView(modeBtnSingle); modeRow.addView(modeBtnPool); modeRow.addView(modeBtnBrief)
        modeRow2.addView(modeBtnAlert); modeRow2.addView(modeBtnEr)
        box.addView(modeRow); box.addView(modeRow2)

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

        // 单票策略多选（五种：预警页四种 + 混合策略；默认全选，勾选结果独立持久化）
        inputCard0.addView(TextView(ctx).apply {
            text = "单票策略（可多选，勾选后输出各自下一根建议 + 对应图表）："; textSize = 11f
            setTypeface(typeface, Typeface.BOLD); setTextColor(Color.parseColor("#333333"))
            setPadding(dp(2), dp(4), 0, dp(2))
        })
        val pickedSingle = singlePicked()
        singleStratBoxes.clear()
        SINGLE_STRAT_ITEMS.forEach { (key, label) ->
            val cb = CheckBox(ctx).apply {
                text = label; textSize = 11.5f; setTextColor(Color.parseColor("#333333"))
                isChecked = key in pickedSingle
                setOnCheckedChangeListener { _, _ -> persistSinglePicked() }
            }
            singleStratBoxes[key] = cb
            inputCard0.addView(cb)
        }

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

        // ── 预警（短线技术指标）容器 ──
        alertTools = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(2))
        }
        alertCodeEt = EditText(ctx).apply {
            hint = "股票代号 / 名称"; textSize = 13f; isSingleLine = true
            setTextColor(Color.parseColor("#333333")); setHintTextColor(Color.parseColor("#AAAAAA"))
            background = roundedBg("#F5F5F5", "#DDDDDD", 6)
            setPadding(dp(10), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(6) }
        }
        alertRunBtn = Button(ctx).apply {
            text = "▶ 预警分析"; textSize = 12f; setTextColor(Color.WHITE); setBackgroundColor(TEAL)
            setPadding(dp(10), 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40))
            setOnClickListener { onAlertRun() }
        }
        alertTools.addView(alertCodeEt); alertTools.addView(alertRunBtn)
        box.addView(alertTools); alertTools.visibility = View.GONE

        // ── 预警 · 策略多选（4 项；默认勾选「纯 BBI12」）──
        val picked = SsaAlertStore.getAlertStrategies(ctx)
        alertStratBoxes.clear()
        alertStratTv = TextView(ctx).apply {
            textSize = 10.5f; setTextColor(GRAY); setPadding(dp(2), dp(2), 0, 0)
        }
        alertStratRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(4))
            addView(TextView(ctx).apply {
                text = "分析策略（可多选，一次全部输出）："; textSize = 11f
                setTypeface(typeface, Typeface.BOLD); setTextColor(Color.parseColor("#333333"))
                setPadding(dp(2), 0, 0, dp(2))
            })
            STRAT_ITEMS.forEach { (key, label) ->
                val cb = CheckBox(ctx).apply {
                    text = label; textSize = 11.5f; setTextColor(Color.parseColor("#333333"))
                    isChecked = key in picked
                }
                alertStratBoxes[key] = cb
                addView(cb)
            }
            alertStratBoxes.values.forEach { it.setOnCheckedChangeListener(stratCbListener) }
            addView(alertStratTv)
        }
        updateAlertStratTv()
        box.addView(alertStratRow); alertStratRow.visibility = View.GONE

        // ── 预警 · 系统悬浮窗开关（退出 App 后显示，预警股 = 持仓池）──
        alertOverlayTv = TextView(ctx).apply {
            textSize = 10.5f; setTextColor(GRAY); setPadding(dp(2), 0, 0, 0)
        }
        alertOverlaySw = SwitchCompat(ctx).apply {
            text = "退出后显示浮窗（预警股=持仓池）"; textSize = 12f
            setTextColor(Color.parseColor("#333333"))
            isChecked = SsaAlertStore.isOverlayEnabled(ctx)
            setOnCheckedChangeListener(overlaySwListener)
        }
        alertOverlayRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(4))
            addView(alertOverlaySw)
            addView(alertOverlayTv)
        }
        box.addView(alertOverlayRow); alertOverlayRow.visibility = View.GONE

        // ── 预警 · 一键更新全部持仓股 ──
        alertBatchBtn = Button(ctx).apply {
            text = "🔄 一键更新全部持仓股"; textSize = 12f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0288D1"))
            setPadding(dp(10), 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38))
            setOnClickListener { onAlertBatchRun() }
        }
        // 🔍 荐股池扫描（2026-10-20 方案A：每日自动推荐入场，手动+自动双触发）
        scanPoolBtn = Button(ctx).apply {
            text = "🔍 扫描荐股池（每日推荐入场）"; textSize = 12f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#00695C"))
            setPadding(dp(10), 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38))
            setOnClickListener { onScanPoolRun() }
        }
        alertBatchRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(4))
            addView(alertBatchBtn)
            addView(scanPoolBtn)
        }
        box.addView(alertBatchRow); alertBatchRow.visibility = View.GONE
        alertBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(alertBox); alertBox.visibility = View.GONE

        // ── ER分位（PC 策略推送候选 + 持仓池）容器 ──
        erTools = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(2))
        }
        erUpdBtn = Button(ctx).apply {
            text = "🔄 拉取策略推送"; textSize = 12f; setTextColor(Color.WHITE)
            setBackgroundColor(TEAL)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38))
            setOnClickListener { renderErTab() }
        }
        erTools.addView(erUpdBtn)
        box.addView(erTools); erTools.visibility = View.GONE
        erUpdTv = TextView(ctx).apply {
            textSize = 10.5f; setTextColor(GRAY); setPadding(dp(2), dp(4), dp(2), 0)
        }
        box.addView(erUpdTv); erUpdTv.visibility = View.GONE
        erBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(erBox); erBox.visibility = View.GONE

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
                status("⏳ 加载 1H K线（本地缓存优先；仅首次或盘中过期才增量拉取）…")
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
                // 单票策略建议（与预警页同一口径：最近 200 根 1H）
                val picked = singleStratBoxes.filterValues { it.isChecked }.keys.toList()
                if (picked.isEmpty()) { status("⚠️ 请至少勾选一项单票策略"); return@launch }
                val stratCalc = hourlyEnt
                    .filter { it.close > 0 && it.high > 0 && it.low > 0 }
                    .sortedBy { it.datetime }
                    .takeLast(200)
                    .map {
                        SsaAlertEngine.Bar(it.date, it.datetime.substringAfter(' ', ""),
                            it.open, it.high, it.low, it.close, it.volume)
                    }
                status("⏳ 策略建议 + 三层滚动 SSA + 做T回测（${hBars.size} 根1H / ${days.size} 根日K）…")
                val stratOut = withContext(Dispatchers.Default) {
                    runCatching { buildSingleOutput(stratCalc, picked) }
                        .getOrElse { StratOutput(picked.toSet(), emptyList(), stratCalc,
                            null, null, null, null, null, null, null) }
                }
                val useHybrid = SINGLE_HYBRID_KEY in stratOut.picked
                val res = withContext(Dispatchers.Default) {
                    runCatching {
                        val rr = SsaT0Engine.run(hBars, days, p)
                        if (useHybrid) {
                            val f = SsaCycleForecast.forecast(hBars, SsaCycleForecast.DEFAULT_HORIZON)
                            val ev = SsaCycleForecast.evaluateSplit(hBars, SsaCycleForecast.DEFAULT_HORIZON)
                            Triple(rr, f, ev)
                        } else Triple(rr, null, null)
                    }
                }
                res.onSuccess { (rr, f, ev) ->
                    val advices = ArrayList(stratOut.advices)
                    if (useHybrid) {
                        val ad = SsaT0Engine.adviceOf(rr)
                        advices.add(StratAdvice("混合策略（SSA 三态）", advWord(ad.action).first, ad.reason))
                    }
                    render(name ?: code, code, rr, f, ev, stratOut.copy(advices = advices))
                    status("✅ 完成：${name ?: code}($code)")
                }
                res.onFailure { e ->
                    // 降级：三层主引擎失败（如周K不足，新股/次新股常见）——单票策略仅用1H数据，照常渲染
                    val err = e.message ?: "未知原因"
                    render(name ?: code, code, null, null, null, stratOut, mainError = err)
                    status("⚠️ 三层回测不可用（$err），已降级展示单票策略")
                }
            } finally {
                runBtn.isEnabled = true
            }
        }
    }

    /** 单票：按勾选策略给出「下一根建议」并保留画图序列（t 收盘信号 → t+1 开盘执行） */
    private fun buildSingleOutput(
        calc: List<SsaAlertEngine.Bar>,
        picked: List<String>
    ): StratOutput {
        if (calc.size < 30) return StratOutput(picked.toSet(), emptyList(), calc,
            null, null, null, null, null, null, null)
        val closes = DoubleArray(calc.size) { calc[it].close }
        val highs = DoubleArray(calc.size) { calc[it].high }
        val lows = DoubleArray(calc.size) { calc[it].low }
        val labels = calc.map { "${it.date} ${it.time}" }
        val out = ArrayList<StratAdvice>(picked.size)

        // BBI 图数据（纯 BBI12 的建议也复用同一份）
        var bundle: SsaBbiPivotEngine.MixBundle? = null
        if (picked.any {
                it == SsaAlertStore.STRAT_MIX_PURE_BBI12 ||
                    it == SsaAlertStore.STRAT_ERBBI_STEADY ||
                    it == SsaAlertStore.STRAT_ERBBI_AGGRO
            }) {
            bundle = SsaBbiPivotEngine.runMix(
                calc, window = 120, modes = listOf(SsaBbiPivotEngine.MixMode.PURE_BBI12)
            )
        }
        var steady: SsaBbiErEngine.State? = null
        var aggro: SsaBbiErEngine.State? = null
        var rangeState: SsaRangeTEngine.State? = null

        picked.forEach { key ->
            when (key) {
                SsaAlertStore.STRAT_MIX_PURE_BBI12 -> {
                    val piv = bundle!!.bbiPivots.sortedBy { it.index }
                    var holding = false
                    for (s in piv) holding = s.buy
                    val pending = piv.lastOrNull()?.takeIf { it.index == calc.size - 1 }
                    val lastBuy = piv.lastOrNull { it.buy }
                    val advice = when {
                        pending?.buy == true -> "买入"
                        pending?.buy == false -> "卖出"
                        holding -> "持有"
                        else -> "观望"
                    }
                    val reason = when {
                        pending != null -> pending.reason
                        holding -> "BBI12 多头持仓中" +
                            (lastBuy?.let { "（最近买入 ${it.date} ${it.time} @${fmt2(it.price)}）" } ?: "") +
                            "，等 MA(BBI,12) 峰顶确认后卖出"
                        else -> "BBI12 空仓，等谷底确认后买入"
                    }
                    out.add(StratAdvice("纯 BBI12 拐点", advice, reason))
                }
                SsaAlertStore.STRAT_ERBBI_STEADY, SsaAlertStore.STRAT_ERBBI_AGGRO -> {
                    val mode = if (key == SsaAlertStore.STRAT_ERBBI_STEADY)
                        SsaBbiErEngine.Mode.STEADY else SsaBbiErEngine.Mode.AGGRESSIVE
                    val st = SsaBbiErEngine.run(closes, labels, mode)
                    if (mode == SsaBbiErEngine.Mode.STEADY) steady = st else aggro = st
                    val label = if (mode == SsaBbiErEngine.Mode.STEADY)
                        "BBI12+止盈8%（稳健）" else "BBI12+止盈8%（进取）"
                    out.add(StratAdvice(label, st.advice, st.reason))
                }
                SsaAlertStore.STRAT_RANGE_T -> {
                    val v = rangeVariantPref(requireContext())
                    val st = SsaRangeTEngine.run(closes, labels, v, highs, lows)
                    rangeState = st
                    out.add(StratAdvice("区间做T·${v.label}", st.advice, st.reason))
                }
            }
        }

        // 区间图：唐奇安上下沿 + ER10 序列
        var er: DoubleArray? = null
        var lo: DoubleArray? = null
        var hi: DoubleArray? = null
        if (rangeState != null) {
            er = SsaErQuantile.erSeries(closes, SsaRangeTEngine.ER_W)
            val n = closes.size
            val nn = SsaRangeTEngine.DON_N
            val useHL = rangeState.variant.srcHL
            val l = DoubleArray(n) { Double.NaN }
            val h = DoubleArray(n) { Double.NaN }
            for (t in nn until n) {
                var mn = Double.MAX_VALUE; var mx = -Double.MAX_VALUE
                for (k in (t - nn) until t) {
                    val lv = if (useHL) lows[k] else closes[k]
                    val hv = if (useHL) highs[k] else closes[k]
                    if (lv < mn) mn = lv
                    if (hv > mx) mx = hv
                }
                l[t] = mn; h[t] = mx
            }
            lo = l; hi = h
        }
        return StratOutput(picked.toSet(), out, calc, rangeState, er, lo, hi, bundle, steady, aggro)
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
        val base = Params(LayerCfg(wl, wm, wn), LayerCfg(dl, dm, dn), LayerCfg(hl, hm, hn), cp, tp, slippageRate = sl)
        return base
    }

    // ═══════════════════════ 三模式：单票 / 持仓池 / 每小时简报 ═══════════════════════

    /** 池/简报使用的小时线根数（单票分析仍用 800） */
    private val POOL_HOURLY = 500

    /** m: 0=单票建议 1=持仓池 2=每小时简报 3=预警 4=ER分位 */
    private fun switchMode(m: Int) {
        val pool = m == 1
        val brief = m == 2
        val alert = m == 3
        val er = m == 4
        val single = !pool && !brief && !alert && !er
        inputCard.visibility = if (single) View.VISIBLE else View.GONE
        resultBox.visibility = if (single) View.VISIBLE else View.GONE
        poolTools.visibility = if (pool) View.VISIBLE else View.GONE
        lastUpdTv.visibility = if (pool) View.VISIBLE else View.GONE
        poolBox.visibility = if (pool) View.VISIBLE else View.GONE
        briefTools.visibility = if (brief) View.VISIBLE else View.GONE
        briefUpdTv.visibility = if (brief) View.VISIBLE else View.GONE
        briefFilterRow.visibility = if (brief) View.VISIBLE else View.GONE
        briefBox.visibility = if (brief) View.VISIBLE else View.GONE
        alertTools.visibility = if (alert) View.VISIBLE else View.GONE
        alertStratRow.visibility = if (alert) View.VISIBLE else View.GONE
        alertOverlayRow.visibility = if (alert) View.VISIBLE else View.GONE
        alertBatchRow.visibility = if (alert) View.VISIBLE else View.GONE
        alertBox.visibility = if (alert) View.VISIBLE else View.GONE
        erTools.visibility = if (er) View.VISIBLE else View.GONE
        erUpdTv.visibility = if (er) View.VISIBLE else View.GONE
        erBox.visibility = if (er) View.VISIBLE else View.GONE
        val idle = Color.parseColor("#B0BEC5")
        modeBtnSingle.setBackgroundColor(if (single) TEAL else idle)
        modeBtnPool.setBackgroundColor(if (pool) TEAL else idle)
        modeBtnBrief.setBackgroundColor(if (brief) TEAL else idle)
        modeBtnAlert.setBackgroundColor(if (alert) TEAL else idle)
        modeBtnEr.setBackgroundColor(if (er) TEAL else idle)
        if (pool) { status(""); renderPool() }
        if (brief) { status(""); renderBrief() }
        if (alert) { status(""); renderAlertIntro(); autoScanPoolDaily() }
        if (er) { status(""); renderErTab() }
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
        val li = r.h.bars.size - 1   // 末根点位（供预警页做T卡片渲染点位条）
        return SsaT0PoolStore.T0Snapshot(
            m.code, m.name, now, ad.time, ad.close, ad.state, ad.action, ad.reason, ad.holdPct,
            r.full.totalReturnPct, r.full.maxDrawdownPct, r.full.winRatePct, r.full.tradeCount,
            mid = r.h.mid.getOrElse(li) { Double.NaN },
            eUp = r.h.eUp.getOrElse(li) { Double.NaN },
            eDn = r.h.eDn.getOrElse(li) { Double.NaN }
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
                // 快照已变，混合做T缓存失效（下次进 Tab 重算）
                hybridCards = null; clearHybridCache(ctx); hybridBarsCache.clear()
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

    // ═══════════════════════ 预警（短线技术指标）═══════════════════════

    private fun renderAlertIntro() {
        alertBox.removeAllViews()
        alertBox.addView(hint("输入股票代号 / 名称 → 勾选上方策略 → 点【▶ 预警分析】一次全部输出（策略可多选）。\n" +
            "① **纯 BBI12 拐点（默认，最平稳）**：BBI=(MA3+MA6+MA12+MA24)/4，BBI12=MA(BBI,12)；" +
            "谷底拐点确认买入、峰顶拐点确认卖出。\n" +
            "② **BBI12拐点+移动止盈8%（稳健/进取，2026-10-20）**：BBI12 谷底拐点买入；" +
            "卖出取先到者——**峰顶拐点确认** 或 **自持仓最高价回撤≥8% 止盈**；" +
            "**稳健模式**额外要求买入时 Δ5ER>-0.05（过滤 ER 急降中的下跌中继拐点），**进取模式**不过滤；" +
            "Δ5ER 金叉仅作预警（领先拐点约 6 根，不直接买入）。" +
            "回测（171 只池 1H）：进取 600176 +235.65%/回撤-18.31%、池均-3.02%；稳健 600176 +172.72%/回撤-14.53%、池均转正附近。\n" +
            "③ **区间做T（震荡低吸高抛，2026-10-21）**：收盘 ≤ 前14根最低收盘 且 ER10≤0.45 → 买入；" +
            "收盘 ≥ 前14根最高收盘 且 ER10≤0.45 → 卖出；t 收盘出信号、t+1 开盘成交。" +
            "ER 上限过滤趋势段（急涨急跌不做区间）。回测 600188·1H：+76.3%/回撤-12.8%/17笔/胜率82.4%，" +
            "震荡期（2026-06~10）+34.9% 显著优于混合策略 -11.7%。\n" +
            "拐点族统一口径：i 根拐点须 i+1 根收盘确认 → 信号根 = 确认根（天然滞后 1 根），触发价 = 收盘价。" +
            "点【🔄 一键更新全部持仓股】可批量重算持仓池全部股票的评分与建议（预警股 = 持仓股）。"))
    }

    private fun onAlertRun() {
        val raw = alertCodeEt.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) { status("请输入股票代号或名称"); return }
        // 2026-10-20：过滤掉已从 UI 移除的旧策略键（旧用户持久化里可能残留 BASE/BBPIVOT/融合②③）
        val validKeys = STRAT_ITEMS.map { it.first }.toSet()
        val checked = alertStratBoxes.filterValues { it.isChecked }.keys.filter { it in validKeys }
        if (checked.isEmpty()) { status("⚠️ 请至少勾选一个分析策略"); return }
        val wantBase = SsaAlertStore.STRAT_BASE in checked
        val wantBb = SsaAlertStore.STRAT_BBPIVOT in checked
        val mixModes = checked.mapNotNull { mixModeOf(it) }
        val wantErBbiSteady = SsaAlertStore.STRAT_ERBBI_STEADY in checked
        val wantErBbiAggro = SsaAlertStore.STRAT_ERBBI_AGGRO in checked
        alertRunBtn.isEnabled = false
        alertBox.removeAllViews()
        lifecycleScope.launch {
            try {
                status("⏳ 解析股票 / 拉取 1H K线…")
                val (code, name) = withContext(Dispatchers.IO) { resolveCode(raw) }
                if (code == null) { status("⚠️ 未找到「$raw」"); return@launch }
                val ent = withContext(Dispatchers.IO) {
                    IntradayKlineFetcher(requireContext()).fetchHourlyHistory(code, 200)
                }
                val all = ent.filter { it.close > 0 && it.high > 0 && it.low > 0 }
                    .sortedBy { it.datetime }
                    .map {
                        SsaAlertEngine.Bar(it.date, it.datetime.substringAfter(' ', ""),
                            it.open, it.high, it.low, it.close, it.volume)
                    }
                if (all.size < 160) { status("⚠️ 1H K线仅 ${all.size} 根（需≥160），网络数据不足"); return@launch }
                val calc = all.takeLast(200)
                status("⏳ 分析中（${calc.size} 根 1H · ${checked.size} 项策略）…")
                var baseRes: SsaAlertEngine.Result? = null
                var bbRes: SsaBbiPivotEngine.Result? = null
                var mixRes: SsaBbiPivotEngine.MixBundle? = null
                if (wantBase) {
                    val res = withContext(Dispatchers.Default) {
                        runCatching { SsaAlertEngine.analyze(calc, SsaAlertEngine.AlertParams(devPct = 0.0)) }
                    }
                    res.onSuccess { baseRes = it; renderAlert(name ?: code, code, it, clear = false) }
                        .onFailure { status("⚠️ 现有策略分析失败：${it.message}") }
                }
                if (wantBb) {
                    val res = withContext(Dispatchers.Default) {
                        runCatching { SsaBbiPivotEngine.run(calc, window = 120) }
                    }
                    res.onSuccess { bbRes = it; renderBbPivot(name ?: code, code, it, clear = false) }
                        .onFailure { status("⚠️ BB 拐点分析失败：${it.message}") }
                }
                if (mixModes.isNotEmpty()) {
                    val res = withContext(Dispatchers.Default) {
                        runCatching { SsaBbiPivotEngine.runMix(calc, window = 120, modes = mixModes) }
                    }
                    res.onSuccess { mixRes = it; renderMix(it, mixModes, name ?: code, code) }
                        .onFailure { status("⚠️ 融合策略分析失败：${it.message}") }
                }
                val erBbiModes = buildList {
                    if (wantErBbiSteady) add(SsaBbiErEngine.Mode.STEADY)
                    if (wantErBbiAggro) add(SsaBbiErEngine.Mode.AGGRESSIVE)
                }
                erBbiModes.forEach { m ->
                    val res = withContext(Dispatchers.Default) {
                        runCatching {
                            SsaBbiErEngine.run(
                                DoubleArray(calc.size) { calc[it].close },
                                calc.map { "${it.date} ${it.time}" }, m)
                        }
                    }
                    res.onSuccess { renderErBbi(name ?: code, code, it, m, clear = false) }
                        .onFailure { status("⚠️ BBI12拐点策略(${m})分析失败：${it.message}") }
                }
                if (SsaAlertStore.STRAT_RANGE_T in checked) {
                    val res = withContext(Dispatchers.Default) {
                        runCatching {
                            SsaRangeTEngine.run(
                                DoubleArray(calc.size) { calc[it].close },
                                calc.map { "${it.date} ${it.time}" },
                                SsaRangeTEngine.VARIANTS[0],
                                DoubleArray(calc.size) { calc[it].high },
                                DoubleArray(calc.size) { calc[it].low })
                        }
                    }
                    res.onSuccess { renderRangeT(name ?: code, code, it) }
                        .onFailure { status("⚠️ 区间做T分析失败：${it.message}") }
                }
                renderStrategyCharts(name ?: code, code, calc, baseRes, bbRes, mixRes, mixModes, 0.0)
                val ok = alertBox.childCount > 0
                if (ok) status("✅ 完成：${name ?: code}($code) · ${checked.size} 项策略")
            } finally {
                alertRunBtn.isEnabled = true
            }
        }
    }

    // ── BBI12拐点+移动止盈8% 双模式渲染（2026-10-20 回测定型） ───────────────

    /** 状态卡 + 信号明细 */
    private fun renderErBbi(name: String, code: String, s: SsaBbiErEngine.State,
                            mode: SsaBbiErEngine.Mode, clear: Boolean = true) {
        if (clear) alertBox.removeAllViews()
        val ctx = requireContext()
        val modeTag = if (mode == SsaBbiErEngine.Mode.STEADY) "稳健·Δ5ER过滤" else "进取·无过滤"
        alertBox.addView(card { c ->
            c.addView(cardTitle("🎯 BBI12拐点+移动止盈8%（$modeTag）· $name ($code)"))
            c.addView(statGrid(listOf(
                "末根" to s.lastDate,
                "末根收盘" to fmt2(s.lastPrice),
                "当前状态" to (if (s.holding) "持仓中（${s.buyDate}）" else "空仓"),
                "买入价" to (if (s.buyPrice.isFinite()) fmt2(s.buyPrice) else "—"),
                "浮盈" to (if (s.pnlPct.isFinite()) "%+.2f%%".format(s.pnlPct) else "—"),
                "持仓最高" to (if (s.peakPrice.isFinite()) fmt2(s.peakPrice) else "—"),
                "止盈线(-8%)" to (if (s.trailLine.isFinite()) fmt2(s.trailLine) else "—"),
                "Δ5ER" to (if (s.d5er.isFinite()) "%+.3f".format(s.d5er) else "—"),
                "信号数" to "${s.signals.size}"
            )))
            val ac = when (s.advice) {
                "持仓" -> "#1565C0"
                "金叉预警" -> "#E65100"
                "止盈卖出" -> "#6A1B9A"
                "峰顶卖出" -> "#00838F"
                "过滤观望" -> "#B71C1C"
                else -> "#555555"
            }
            c.addView(TextView(ctx).apply {
                text = "▶ ${s.advice} · ${s.reason}"
                textSize = 13f; setTextColor(Color.parseColor(ac))
                setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(6), 0, 0)
            })
            c.addView(hint("口径：BBI12 谷底拐点确认买入；峰顶确认 或 自最高价回撤≥8% 止盈（先到先卖）。" +
                (if (mode == SsaBbiErEngine.Mode.STEADY) "稳健模式额外要求买入时 Δ5ER>-0.05（过滤急降中继拐点）。" else "") +
                "Δ5ER 金叉仅作预警（领先拐点约6根）。回测：600176 进取+235.65%/回撤-18.31%，稳健+172.72%/回撤-14.53%。"))
        })
        if (s.signals.isNotEmpty()) {
            alertBox.addView(card { c ->
                c.addView(cardTitle("📋 信号明细（最近 20 条）"))
                val widths = listOf(96, 52, 48, 180)
                val hsv = HorizontalScrollView(requireContext()).apply { isHorizontalScrollBarEnabled = false }
                val tb = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
                tb.addView(row(listOf("时间", "价格", "方向", "原因"), widths, true))
                s.signals.takeLast(20).asReversed().forEach { sg ->
                    val dir = when (sg.kind) {
                        SsaBbiErEngine.Kind.BUY -> "买入"
                        SsaBbiErEngine.Kind.SELL_PEAK -> "峰顶卖"
                        SsaBbiErEngine.Kind.SELL_TRAIL -> "止盈卖"
                        SsaBbiErEngine.Kind.SELL_STOP -> "止损卖"
                        SsaBbiErEngine.Kind.SKIP -> "过滤"
                    }
                    tb.addView(row(listOf(sg.date, fmt2(sg.price), dir, sg.reason), widths, false))
                }
                hsv.addView(tb); c.addView(hsv)
            })
        }
    }

    // ── 区间做T 渲染（2026-10-21 固化：N14 唐奇安 + ER10≤0.45）────────────────

    /** 状态卡 + 信号明细 */
    private fun renderRangeT(name: String, code: String, s: SsaRangeTEngine.State) {
        val ctx = requireContext()
        alertBox.addView(card { c ->
            c.addView(cardTitle("📐 区间做T（震荡低吸高抛）· $name ($code)"))
            c.addView(statGrid(listOf(
                "末根" to s.lastDate,
                "末根收盘" to fmt2(s.lastPrice),
                "当前状态" to if (s.holding) "持仓中（${s.buyDate}）" else "空仓",
                "买入价" to (if (s.buyPrice.isFinite()) fmt2(s.buyPrice) else "—"),
                "浮盈" to (if (s.pnlPct.isFinite()) "%+.2f%%".format(s.pnlPct) else "—"),
                "区间下沿(前14最低)" to (if (s.lower.isFinite()) fmt2(s.lower) else "—"),
                "区间上沿(前14最高)" to (if (s.upper.isFinite()) fmt2(s.upper) else "—"),
                "距下沿" to (if (s.toLowerPct.isFinite()) "%+.2f%%".format(s.toLowerPct) else "—"),
                "距上沿" to (if (s.toUpperPct.isFinite()) "%+.2f%%".format(s.toUpperPct) else "—"),
                "ER10" to (if (s.er.isFinite()) "%.3f".format(s.er) else "—"),
                "信号数" to "${s.signals.size}"
            )))
            val ac = when (s.advice) {
                "买入" -> "#C62828"
                "卖出" -> "#2E7D32"
                "持仓" -> "#1565C0"
                else -> "#555555"
            }
            c.addView(TextView(ctx).apply {
                text = "▶ ${s.advice} · ${s.reason}"
                textSize = 13f; setTextColor(Color.parseColor(ac))
                setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(6), 0, 0)
            })
            c.addView(hint("口径：收盘 ≤ 前${SsaRangeTEngine.DON_N}根最低收盘 且 ER10 ≤ ${SsaRangeTEngine.ER_MAX} → 买入；" +
                "收盘 ≥ 前${SsaRangeTEngine.DON_N}根最高收盘 且 ER10 ≤ ${SsaRangeTEngine.ER_MAX} → 卖出；" +
                "均为 t 收盘出信号、t+1 开盘成交（无未来函数）；卖出再按交易日校验 T+1（当日买入当日不可卖，占用信号则顺延）。" +
                "ER 上限用于过滤趋势段（急涨急跌不做区间）。" +
                "回测 600188·1H：+76.3%/回撤-12.8%/17笔/胜率82.4%；2026-06~10 震荡期 +34.9%（同期混合 -11.7%）。"))
        })
        if (s.signals.isNotEmpty()) {
            alertBox.addView(card { c ->
                c.addView(cardTitle("📋 信号明细（最近 20 条）"))
                val widths = listOf(96, 52, 48, 180)
                val hsv = HorizontalScrollView(requireContext()).apply { isHorizontalScrollBarEnabled = false }
                val tb = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
                tb.addView(row(listOf("时间", "价格", "方向", "原因"), widths, true))
                s.signals.takeLast(20).asReversed().forEach { sg ->
                    val dir = when (sg.kind) {
                        SsaRangeTEngine.Kind.BUY -> "买入"
                        SsaRangeTEngine.Kind.SELL -> "卖出"
                    }
                    tb.addView(row(listOf(sg.date, fmt2(sg.price), dir, sg.reason), widths, false))
                }
                hsv.addView(tb); c.addView(hsv)
            })
        }
    }

    // ── BB 布林极限拐点策略渲染 ────────────────────────────────────────

    /** 概览 + 平滑口径收益对比 + 选定口径信号明细 + BB 曲线 */
    private fun renderBbPivot(name: String, code: String, r: SsaBbiPivotEngine.Result, clear: Boolean = true) {
        if (clear) alertBox.removeAllViews()
        val n = r.bars.size
        val last = r.bars.last()

        alertBox.addView(card { c ->
            c.addView(cardTitle("⚠ BB 布林极限拐点 · $name ($code)"))
            c.addView(statGrid(listOf(
                "样本根数" to "$n 根 1H",
                "末根" to "${last.date} ${last.time}",
                "末根收盘" to fmt2(last.close),
                "末根 BB" to if (r.bb[n - 1].isNaN()) "—" else "%.2f".format(r.bb[n - 1]),
                "末根 BB均线" to if (r.bbMa[n - 1].isNaN()) "—" else "%.2f".format(r.bbMa[n - 1]),
                "评估区间" to "${n - r.windowFrom} 根"
            )))
            c.addView(hint("口径：BB = 布林极限（通达信系统原版）= 25 + 25×(C−REF(MA(C,20),1))/REF(√MA((C−MA(C,20))²,20),1)，中心 25、量程约 −100~+150（**不是 BBI**）。" +
                "拐点族：序列 i 根为谷底/峰顶须等 i+1 根收盘确认 → 信号根 = 确认根（天然滞后 1 根），触发价 = 收盘价；" +
                "谷底确认买入 / 峰顶确认卖出，单边做多最多一仓，末根强平。**展示用、无未来函数**。"))
        })

        alertBox.addView(card { c ->
            c.addView(cardTitle("📊 平滑口径收益对比（按超额降序）"))
            val widths = listOf(96, 46, 46, 46, 32, 36)
            val hsv = HorizontalScrollView(requireContext()).apply { isHorizontalScrollBarEnabled = false }
            val tb = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            tb.addView(row(listOf("口径", "策略%", "买持%", "超额%", "笔数", "胜率%"), widths, true))
            val sorted = r.variants.sortedByDescending { it.perf.excessPct }
            sorted.forEach { v -> tb.addView(bbVariantRow(v, widths)) }
            hsv.addView(tb); c.addView(hsv)
            val best = sorted.firstOrNull()
            c.addView(hint("同区间基线（现有多指标策略）：策略 %+.2f%% / 买持 %+.2f%% / 超额 %+.2f%% / %d 笔 / 胜率 %.1f%%。最优口径 = %s。"
                .format(r.baseline.strategyReturnPct, r.baseline.buyHoldReturnPct, r.baseline.excessPct,
                    r.baseline.trades, r.baseline.winRatePct, best?.label ?: "—")))
        })

        val focus = r.variants.firstOrNull { it.variant == SsaBbiPivotEngine.Smooth.MA12 && !it.gated }
            ?: r.variants.maxByOrNull { it.perf.excessPct }
        if (focus != null) {
            alertBox.addView(card { c ->
                c.addView(cardTitle("📋 信号明细 · ${focus.label}（%+.2f%% / %d 笔 / 胜率 %.1f%%）"
                    .format(focus.perf.strategyReturnPct, focus.perf.trades, focus.perf.winRatePct)))
                val sigs = focus.signals.filter { it.index >= r.windowFrom }
                if (sigs.isEmpty()) { c.addView(hint("区间内无信号")); return@card }
                val widths = listOf(70, 42, 32, 168)
                val hsv = HorizontalScrollView(requireContext()).apply { isHorizontalScrollBarEnabled = false }
                val tb = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
                tb.addView(row(listOf("时间", "价格", "方向", "原因"), widths, true))
                sigs.asReversed().forEach { s -> tb.addView(bbSignalRow(s, widths)) }
                hsv.addView(tb); c.addView(hsv)
            })
        }

        val cbars = r.bars.map { Bar(it.date.replace("-", ""), it.time, it.open, it.high, it.low, it.close) }
        alertBox.addView(card { c ->
            c.addView(cardTitle("① BB 布林极限 与买入窗均线 MA(BB, 3)"))
            c.addView(miniLine(listOf(line(r.bb, "BB", BLUE, 1.2f), line(r.bbMa, "MA(BB)", ORANGE, 1.3f)),
                cbars, 116, timeFmt(cbars), false))
        })
    }

    private fun bbVariantRow(v: SsaBbiPivotEngine.VariantResult, widths: List<Int>): LinearLayout {
        val cells = listOf(v.label, "%+.2f".format(v.perf.strategyReturnPct),
            "%+.2f".format(v.perf.buyHoldReturnPct), "%+.2f".format(v.perf.excessPct),
            v.perf.trades.toString(), "%.1f".format(v.perf.winRatePct))
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            cells.forEachIndexed { i, txt ->
                addView(TextView(requireContext()).apply {
                    text = txt; textSize = 9f
                    gravity = Gravity.CENTER_VERTICAL or
                        (if (i == 0) Gravity.START else Gravity.CENTER_HORIZONTAL)
                    setTextColor(when {
                        i == 3 -> if (v.perf.excessPct >= 0) RED else GREEN
                        i == 1 -> if (v.perf.strategyReturnPct >= 0) RED else GREEN
                        else -> Color.parseColor("#333333")
                    })
                    if (i == 3) setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(4), dp(5), dp(4), dp(5))
                    layoutParams = LinearLayout.LayoutParams(dp(widths[i]), LinearLayout.LayoutParams.WRAP_CONTENT)
                })
            }
        }
    }

    private fun bbSignalRow(s: SsaAlertEngine.Signal, widths: List<Int>): LinearLayout {
        val cells = listOf("${s.date.substring(5)} ${s.time}", "%.2f".format(s.price),
            if (s.buy) "买入" else "卖出", s.reason)
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            cells.forEachIndexed { i, txt ->
                addView(TextView(requireContext()).apply {
                    text = txt; textSize = 9f
                    gravity = Gravity.CENTER_VERTICAL or
                        (if (i == 0 || i == cells.lastIndex) Gravity.START else Gravity.CENTER_HORIZONTAL)
                    setTextColor(if (i == 2) (if (s.buy) RED else GREEN) else Color.parseColor("#333333"))
                    if (i == 2) setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(4), dp(5), dp(4), dp(5))
                    layoutParams = LinearLayout.LayoutParams(dp(widths[i]), LinearLayout.LayoutParams.WRAP_CONTENT)
                })
            }
        }
    }


    // ── BB×BBI 融合策略渲染（信号层分工） ──────────────────────────────

    private fun mixColor(m: SsaBbiPivotEngine.MixMode): Int = when (m) {
        SsaBbiPivotEngine.MixMode.PURE_BBI12 -> ORANGE
        SsaBbiPivotEngine.MixMode.BBI12_BB6_OR -> PURPLE
        SsaBbiPivotEngine.MixMode.BBI12_BB6_CYCLE_OR -> TEAL
        SsaBbiPivotEngine.MixMode.BBI12_BB6_BBWIN -> CYAN
    }

    /** 融合策略：概览 + 每条模式一卡（绩效 + 信号明细）+ 拐点核对图 + 拐点清单 */
    private fun renderMix(bundle: SsaBbiPivotEngine.MixBundle, modes: List<SsaBbiPivotEngine.MixMode>,
                          name: String, code: String) {
        val n = bundle.bars.size
        alertBox.addView(card { c ->
            c.addView(cardTitle("🔀 BB×BBI 融合策略 · $name ($code)"))
            c.addView(statGrid(listOf(
                "样本根数" to "$n 根 1H",
                "评估区间" to "${n - bundle.windowFrom} 根",
                "勾选模式" to "${bundle.results.size} 条",
                "基线(多指标)超额" to "%+.2f%%".format(bundle.baseline.excessPct)
            )))
            c.addView(hint("融合思路：BB（σ 归一化振荡量，无量纲）与 BBI（价格尺度均线）**不能混成一根线**，" +
                "只在**拐点信号层**分工 —— BBI(MA12) 平滑抗扰负责**入场**，BB(MA6) 灵敏负责**早避跌出场**。" +
                "拐点口径同前：i 根拐点须 i+1 根收盘确认（信号根 = 确认根），成交价 = 收盘价，单边做多最多一仓，末根强平。"))
        })
        bundle.results.forEach { r ->
            alertBox.addView(card { c ->
                c.addView(cardTitle("📈 ${r.mode.label}（%+.2f%% / %d 笔 / 胜率 %.1f%%）"
                    .format(r.perf.strategyReturnPct, r.perf.trades, r.perf.winRatePct)))
                c.addView(statGrid(listOf(
                    "策略%" to "%+.2f%%".format(r.perf.strategyReturnPct),
                    "买持%" to "%+.2f%%".format(r.perf.buyHoldReturnPct),
                    "超额%" to "%+.2f%%".format(r.perf.excessPct),
                    "笔数" to "${r.perf.trades}"
                )))
                val sigs = r.signals.filter { it.index >= bundle.windowFrom }
                if (sigs.isEmpty()) { c.addView(hint("区间内无信号")); return@card }
                val widths = listOf(70, 42, 32, 168)
                val hsv = HorizontalScrollView(requireContext()).apply { isHorizontalScrollBarEnabled = false }
                val tb = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
                tb.addView(row(listOf("时间", "价格", "方向", "原因"), widths, true))
                sigs.asReversed().forEach { s -> tb.addView(bbSignalRow(s, widths)) }
                hsv.addView(tb); c.addView(hsv)
            })
        }
        renderPivotLists(bundle, modes)
    }

    /**
     * 拐点清单（BBI(MA12) 峰谷，始终显示；BB(MA6) 峰谷仅在所选模式用到 BB 侧信号时才显示）
     * ——图表已改为按策略独立出图（见 [renderStrategyCharts]）。
     *
     * `PURE_BBI12` 只吃 BBI 侧拐点，列出 BB 清单属噪声；另两条融合模式用 BB(MA6) 顶做卖出出口，需一并列出。
     */
    private fun renderPivotLists(bundle: SsaBbiPivotEngine.MixBundle, modes: List<SsaBbiPivotEngine.MixMode>) {
        val widths = listOf(70, 42, 32, 168)
        if (modes.any { it != SsaBbiPivotEngine.MixMode.PURE_BBI12 }) {
            alertBox.addView(card { c ->
                c.addView(cardTitle("📋 拐点清单 · BB 布林极限 MA(BB,6) 峰谷"))
                val sigs = bundle.bbPivots.filter { it.index >= bundle.windowFrom }
                if (sigs.isEmpty()) { c.addView(hint("区间内无 BB(MA6) 拐点")); return@card }
                val hsv = HorizontalScrollView(requireContext()).apply { isHorizontalScrollBarEnabled = false }
                val tb = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
                tb.addView(row(listOf("时间", "价格", "方向", "原因"), widths, true))
                sigs.asReversed().forEach { s -> tb.addView(bbSignalRow(s, widths)) }
                hsv.addView(tb); c.addView(hsv)
            })
        }
        alertBox.addView(card { c ->
            c.addView(cardTitle("📋 拐点清单 · BBI 多空指标 MA(BBI,12) 峰谷"))
            val sigs = bundle.bbiPivots.filter { it.index >= bundle.windowFrom }
            if (sigs.isEmpty()) { c.addView(hint("区间内无 BBI(MA12) 拐点")); return@card }
            val hsv = HorizontalScrollView(requireContext()).apply { isHorizontalScrollBarEnabled = false }
            val tb = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            tb.addView(row(listOf("时间", "价格", "方向", "原因"), widths, true))
            sigs.asReversed().forEach { s -> tb.addView(bbSignalRow(s, widths)) }
            hsv.addView(tb); c.addView(hsv)
        })
    }

    // ── 策略回测对照图（WebView + ECharts，每策略一张独立图） ──────────────

    /** 兜底单张图高度（dp）：可见区域实测值取不到时使用 */
    private val STRAT_CHART_H_DP = 220

    /** 取不到实测高度时，从屏高里扣除的页面占位（dp）：状态栏 + 顶部标题/Tab + 模式按键 + 策略勾选 + 输入按键 */
    private val STRAT_PAGE_CHROME_DP = 340f

    /**
     * 图卡片内的非绘图开销（dp）：卡片内边距 20 + 图标题 22 + 图例 24 + HTML 面板标题与边框 26
     * （实测 1 CSS px = 1 dp，故可直接用 px 值当 dp 估）
     */
    private val STRAT_CARD_CHROME_DP = 92f

    /**
     * 单张策略图高度（dp）：**按本页中间的可见区域自适应**。
     *
     * 基准 = Fragment 根视图实测高度 —— 它已经是扣掉 App 顶部标题/Tab、本页模式按键、策略勾选框、
     * 输入区与【预警分析】按键之后剩下的「中间显示区」，再减去卡片自身的标题/图例等占位，
     * 让「主图 + 副图」一张卡**基本占满**这块可见区（一屏内完整看全，不必上下拖动）。
     * 实测高度取不到时退化为「屏高 − 页面占位」。
     *
     * @param sub 是否为带副图（主图 + WR 副图双面板）的图，双面板留更多纵向空间
     */
    private fun stratChartHDp(sub: Boolean): Int {
        val dm = resources.displayMetrics
        val density = if (dm.density > 0f) dm.density else 1f
        val vh = view?.height ?: 0
        val availDp = if (vh > 0) vh / density
        else (dm.heightPixels / density - STRAT_PAGE_CHROME_DP).coerceAtLeast(320f)
        val h = availDp - STRAT_CARD_CHROME_DP
        return h.toInt().coerceIn(200, if (sub) 620 else 620)
    }

    /**
     * 收集当前勾选的**每一条策略**，各自生成一张独立图（K线 + 指标线 + 买卖标记 + 持仓段），
     * 汇成一个 JSON 交给 [WebView]（`assets/pivot_chart.html` + `echarts.min.js`）渲染。
     * 语义与报告里的回测对照图一致：末 [120] 根 1H 区间、收盘价成交、单边最多一仓、末根强平。
     */
    private fun renderStrategyCharts(
        name: String, code: String,
        bars: List<SsaAlertEngine.Bar>,
        base: SsaAlertEngine.Result?,
        bb: SsaBbiPivotEngine.Result?,
        mix: SsaBbiPivotEngine.MixBundle?,
        mixModes: List<SsaBbiPivotEngine.MixMode>,
        devPct: Double
    ) {
        val n = bars.size
        if (n < 30) return
        val from = (n - 120).coerceAtLeast(0)
        val p = SsaAlertEngine.AlertParams(devPct = devPct)
        // 图高按本页中间可见区自适应（单位 dp）；模板里按 CSS px 用，本机 1 CSS px = 1 dp，禁止再乘 density
        val hDp = stratChartHDp(false)
        val hSubDp = stratChartHDp(true)
        val dateArr = JSONArray().apply {
            for (i in from until n) put("${bars[i].date.takeLast(5)} ${bars[i].time}")
        }
        val klineArr = JSONArray().apply {
            for (i in from until n) {
                val b = bars[i]
                put(JSONArray().apply { put(r3(b.open)); put(r3(b.close)); put(r3(b.low)); put(r3(b.high)) })
            }
        }
        val charts = JSONArray()

        // ① BASE 现有多指标策略
        base?.let { b ->
            val (trades, perf) = SsaBbiPivotEngine.simulate(bars, b.signals, b.buyWindowOpen, from, n - 1, 0.0, p)
            charts.put(JSONObject().apply {
                put("title", "① 现有多指标策略（JAX/OBV/KDJ/WR/BB）")
                put("sub", perfLine(perf))
                put("h", hDp)
                put("series", JSONArray().apply {
                    put(markJson(b.signals.filter { it.buy }, "#e53935", true, bars, from))
                    put(markJson(b.signals.filter { !it.buy }, "#2e7d32", false, bars, from))
                })
                put("areas", areasJson(trades, from))
            })
        }

        // ② BB 布林极限拐点（通达信原版公式）
        bb?.let { r ->
            val focus = r.variants.firstOrNull { it.variant == SsaBbiPivotEngine.Smooth.MA12 && !it.gated }
                ?: r.variants.maxByOrNull { it.perf.excessPct }
            if (focus != null) {
                val bbVals = (r.bb.toList() + r.bbMa.toList()).filter { !it.isNaN() }
                charts.put(JSONObject().apply {
                    put("title", "② BB 布林极限拐点（通达信原版公式）· ${focus.label}")
                    put("sub", perfLine(focus.perf))
                    put("h", hDp)
                    put("y2", JSONObject().apply {
                        put("name", "BB")
                        put("min", (bbVals.minOrNull() ?: -20.0) - 5.0)
                        put("max", (bbVals.maxOrNull() ?: 80.0) + 5.0)
                    })
                    put("series", JSONArray().apply {
                        put(lineJson("BB", "#e53935", 1.3, 1, r.bb, from))
                        put(lineJson("MA(BB,${SsaAlertEngine.AlertParams().bbiMaN})", "#00838f", 1.4, 1, r.bbMa, from))
                        put(markJson(focus.signals.filter { it.buy }, "#e53935", true, bars, from))
                        put(markJson(focus.signals.filter { !it.buy }, "#2e7d32", false, bars, from))
                    })
                    put("areas", areasJson(focus.trades, from))
                })
            }
        }

        // ③④⑤⑥ 融合策略（每条模式一张独立图；主图 K线 + BBI/MA(BBI,12)，副图1 = 平滑 WR，副图2 = SSA 1H 周期分量）
        mix?.let { b ->
            val wrSmooth = SsaAlertEngine.sma(SsaBbiPivotEngine.wrOf(bars, p.wrN), 3)
            val cyc = b.cycle
            val cycBuys = b.cyclePivots.filter { it.buy }
            val cycSells = b.cyclePivots.filter { !it.buy }
            mixModes.forEach { m ->
                val r = b.results.firstOrNull { it.mode == m } ?: return@forEach
                charts.put(JSONObject().apply {
                    put("title", mixModeTitle(m))
                    put("sub", perfLine(r.perf))
                    put("h", hSubDp)
                    put("series", JSONArray().apply {
                        put(lineJson("BBI", "#fb8c00", 1.2, 0, b.bbi, from))
                        put(lineJson("MA(BBI,12)", "#1e88e5", 1.3, 0, b.bbiMa12, from))
                        put(markJson(r.signals.filter { it.buy }, "#e53935", true, bars, from))
                        put(markJson(r.signals.filter { !it.buy }, "#2e7d32", false, bars, from))
                    })
                    put("subs", JSONArray().apply {
                        // 副图1：平滑威廉指标（0~100，越大越超卖）
                        put(JSONObject().apply {
                            put("name", "WR")
                            put("min", 0); put("max", 100)
                            put("series", JSONArray().apply {
                                put(lineJson("MA(WR,3)", "#6d4c41", 1.4, 1, wrSmooth, from))
                            })
                        })
                        // 副图2：SSA 1H 周期分量（价格尺度、绕 0 轴振荡，L=30、无未来函数）+ 其峰谷拐点
                        put(JSONObject().apply {
                            put("name", "SSA周期")
                            put("zero", true)
                            put("series", JSONArray().apply {
                                put(lineJson("SSA周期分量", "#00897b", 1.4, 1, cyc, from))
                                put(subScatterJson(cycBuys, "#e53935", 0, cyc, from))
                                put(subScatterJson(cycSells, "#2e7d32", 180, cyc, from))
                            })
                        })
                    })
                    put("areas", areasJson(r.trades, from))
                })
            }
        }

        if (charts.length() == 0) return
        // 初始高度 = 各图自身高度（JSON 里是 dp）+ 每张 20dp（面板标题/边框/间距），页面渲染完成后由脚本实测校正
        var estH = 0
        for (i in 0 until charts.length()) {
            estH += dp(charts.getJSONObject(i).optInt("h", STRAT_CHART_H_DP))
        }
        val data = JSONObject().apply {
            put("dates", dateArr); put("kline", klineArr); put("charts", charts)
        }
        val tpl = runCatching {
            requireContext().assets.open("pivot_chart.html").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return
        alertBox.addView(card { c ->
            c.addView(cardTitle("📈 策略回测对照图 · $name ($code)（每策略单独一张，区间末 ${n - from} 根 1H）"))
            c.addView(legend("▲红=买入 ▼绿=卖出；黄色底纹=持仓区间；**双指捏合**可缩放区间（主图+副图同步），" +
                "拖动下方滑块可平移。" +
                "BB = 通达信系统原版布林极限；融合策略副图1 = 平滑威廉指标 MA(WR,3)（0~100，越大越超卖），" +
                "副图2 = **SSA 1H 周期分量**（价格尺度、绕 0 轴振荡、L=30、无未来函数）及其峰谷拐点" +
                "（▲=周期谷底 ▼=周期峰顶，信号根=确认根=次根收盘）。"))
            val wv = WebView(requireContext()).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // 关掉 WebView 自带的页面缩放，把双指手势留给 ECharts（图内缩放）
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                setBackgroundColor(Color.WHITE)
                isVerticalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    estH + dp(charts.length() * 20))
            }
            // 双指时禁止外层 ScrollView 抢手势（单指仍可上下滚动整页）
            wv.setOnTouchListener { v, e ->
                v.parent?.requestDisallowInterceptTouchEvent(e.pointerCount >= 2)
                false
            }
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    // 内容高度单位是 CSS px（viewport 为 device-width，1 CSS px = 1 dp），故按 dp 换算
                    view.evaluateJavascript("document.body.scrollHeight") { raw ->
                        val cssH = raw?.trim('"', ' ')?.toDoubleOrNull() ?: return@evaluateJavascript
                        if (cssH <= 0.0) return@evaluateJavascript
                        val lp = view.layoutParams
                        val exact = dp(cssH.toInt() + 2)
                        if (lp != null && lp.height != exact) {
                            lp.height = exact
                            view.layoutParams = lp
                        }
                    }
                }
            }
            wv.loadDataWithBaseURL("file:///android_asset/", tpl.replace("__DATA__", data.toString()),
                "text/html", "UTF-8", null)
            c.addView(wv)
        })
    }

    private fun perfLine(p: SsaAlertEngine.AlertPerf): String =
        "策略 %+.2f%% · 买持 %+.2f%% · 超额 %+.2f%% · %d 笔 · 胜率 %.1f%%"
            .format(p.strategyReturnPct, p.buyHoldReturnPct, p.excessPct, p.trades, p.winRatePct)

    private fun mixModeTitle(m: SsaBbiPivotEngine.MixMode): String = when (m) {
        SsaBbiPivotEngine.MixMode.PURE_BBI12 -> "③ 融合①纯 BBI12 拐点（BBI12 底买 → BBI12 顶卖）"
        SsaBbiPivotEngine.MixMode.BBI12_BB6_OR -> "④ 融合②BBI12 底买 → (BB6∪BBI12) 顶卖"
        SsaBbiPivotEngine.MixMode.BBI12_BB6_CYCLE_OR -> "⑥ 融合④BBI12 底买 → (BB6∪BBI12∪SSA周期) 顶卖"
        SsaBbiPivotEngine.MixMode.BBI12_BB6_BBWIN -> "⑤ 融合③BBI12 底买 → BB6 顶卖 · BB 窗"
    }

    private fun r3(v: Double): Double = Math.round(v * 1000.0) / 1000.0

    /** 指标线序列（线型数据，可按轴 0/1） */
    private fun lineJson(name: String, color: String, width: Double, axis: Int,
                         values: DoubleArray, from: Int): JSONObject {
        val d = JSONArray()
        for (i in from until values.size) {
            val v = values[i]
            d.put(if (v.isNaN()) JSONObject.NULL else r3(v))
        }
        return JSONObject().apply {
            put("n", name); put("t", "line"); put("ax", axis); put("c", color); put("w", width); put("d", d)
        }
    }

    /** 买卖三角标记（scatter，落在左轴 K 线价尺；买入在低位向上三角、卖出在高位向下三角） */
    private fun markJson(sig: List<SsaAlertEngine.Signal>, color: String, buy: Boolean,
                         bars: List<SsaAlertEngine.Bar>, from: Int): JSONObject {
        val d = JSONArray()
        sig.filter { it.index >= from }.forEach { s ->
            val b = bars[s.index]
            d.put(JSONArray().apply { put(s.index - from); put(r3(if (buy) b.low * 0.99 else b.high * 1.01)) })
        }
        return JSONObject().apply {
            put("n", if (buy) "买入" else "卖出"); put("t", "scatter"); put("ax", 0); put("c", color)
            put("sym", "triangle"); put("rot", if (buy) 0 else 180); put("sz", 9); put("d", d)
        }
    }

    /** 副图散点标记（如 SSA 1H 周期分量拐点），落在副图自己的 y 轴：x = 类别下标（窗口内），y = values[index] */
    private fun subScatterJson(sig: List<SsaAlertEngine.Signal>, color: String, rot: Int,
                               values: DoubleArray, from: Int): JSONObject {
        val d = JSONArray()
        sig.filter { it.index >= from && it.index < values.size }.forEach { s ->
            val v = values[s.index]
            d.put(JSONArray().apply { put(s.index - from); put(if (v.isNaN()) JSONObject.NULL else r3(v)) })
        }
        return JSONObject().apply {
            put("n", ""); put("t", "scatter"); put("c", color)
            put("sym", "triangle"); put("rot", rot); put("sz", 6); put("d", d)
        }
    }

    /** 持仓区间（markArea，成对 [起点, 终点]） */
    private fun areasJson(trades: List<SsaBbiPivotEngine.Trade>, from: Int): JSONArray {
        val a = JSONArray()
        trades.forEach { t ->
            a.put(JSONArray().apply {
                put(JSONObject().apply { put("xAxis", t.openIndex - from) })
                put(JSONObject().apply { put("xAxis", t.closeIndex - from) })
            })
        }
        return a
    }


    /** 一键更新全部持仓股：批量拉 1H K 线 → 短线评分 → 写缓存 → 软件内展示 + 同步系统浮窗 */
    private fun onAlertBatchRun() {
        val ctx = requireContext()
        val members = SsaT0PoolStore.load(ctx)
        if (members.isEmpty()) { status("⚠️ 持仓池为空，请先在「持仓池」页加入股票"); return }
        alertBatchBtn.isEnabled = false
        lifecycleScope.launch {
            try {
                Log.i(TAG_BATCH, "开始：${members.size} 只持仓股")
                status("⏳ 一键更新 ${members.size} 只持仓股（拉取 1H K线 + 短线评分）…")
                val rows0 = withContext(Dispatchers.IO) { SsaAlertBatch.run(ctx, members) }
                Log.i(TAG_BATCH, "评分完成 ${rows0.size} 只：${rows0.groupBy { it.advice }.mapValues { it.value.size }}")
                status("⏳ 短线评分完成 ${rows0.size} 只 → 拉取 ER 分位…")
                // 2026-10-18 用户需求：给持仓股补 ER分位 —— PC 推送的分位表优先；
                // PC 推送不可用（中继不可达 / 旧版无 er_pct_map）时走 **App 端本机备选**
                // = 每日荐股核心池 + Room 日线 W=60（与 PC 同池同口径；本地读库不联网）。
                var erLocal = false
                var er = withContext(Dispatchers.IO) {
                    val pcm = runCatching {
                        val j = fetchPcCandidatesJson()
                        if (j.isNullOrBlank()) emptyMap<String, Double>() else parseErMap(JSONObject(j))
                    }.getOrDefault(emptyMap())
                    if (pcm.isNotEmpty()) pcm else {
                        val local = runCatching { SsaErLocal.corePoolPercentiles(ctx) }
                            .getOrDefault(emptyMap())
                        erLocal = local.isNotEmpty()
                        local
                    }
                }
                // 2026-10-18 用户方案A：池外持仓票并入横截面补算（universe = 当前表 ∪ 全部持仓票）
                var erBackfillN = 0
                Log.i(TAG_BATCH, "ER 表=${er.size}（本机=$erLocal）；开始池外补算判定")
                val missingHolds = rows0.map { code6(it.code) }.filter { it !in er }.distinct()
                if (missingHolds.isNotEmpty()) {
                    status("⏳ ${missingHolds.size} 只持仓票不在 ER 池 → 本机日线补算…")
                    val ext = withContext(Dispatchers.IO) {
                        runCatching { SsaErLocal.percentilesFor(ctx, er.keys + missingHolds) }
                            .getOrDefault(emptyMap())
                    }
                    missingHolds.forEach { c6 -> ext[c6]?.let { er = er + (c6 to it); erBackfillN++ } }
                    Log.i(TAG_BATCH, "池外补算：${missingHolds.size} 只尝试，成功 $erBackfillN 只")
                }
                val rows = rows0.map { r ->
                    val p = er[code6(r.code)]
                    if (p != null) r.copy(erPct = p) else r
                }
                SsaAlertStore.saveRows(ctx, rows)
                SsaAlertStore.setLastRefreshAt(ctx, System.currentTimeMillis())
                // 系统浮窗若正在运行，同步刷新一次
                if (SsaAlertStore.isOverlayEnabled(ctx)) SsaAlertOverlayService.refresh(ctx)
                alertBox.removeAllViews()
                renderAlertBatchResult(rows, erLocal)
                Log.i(TAG_BATCH, "渲染完成：${rows.size} 张卡片")
                val miss = rows.count { it.advice == "数据缺失" }
                status("✅ 已更新 ${rows.size} 只：买入 ${rows.count { it.advice == "买入" }} / " +
                    "关注 ${rows.count { it.advice == "关注" }} / 卖出 ${rows.count { it.advice == "卖出" }}" +
                    (if (miss > 0) " / 数据缺失 $miss" else "") +
                    (if (erLocal) " · ER分位：🔧 本机备选" else "") +
                    (if (erBackfillN > 0) " · ER 池外持仓 $erBackfillN 只并入补算" else ""))
            } catch (e: Exception) {
                Log.w(TAG_BATCH, "批量更新失败", e)
                status("⚠️ 批量更新失败：${e.message}")
            } finally {
                alertBatchBtn.isEnabled = true
            }
        }
    }

    private companion object {
        /** 一键更新/ER 分位链路日志 tag（logcat -s SsaT0Batch 定位卡点） */
        const val TAG_BATCH = "SsaT0Batch"
    }

    // 预警批结果子 Tab 状态（0=入场推荐 1=持仓做T 2=混合做T 3=历史）
    private var alertTabIdx = 0
    private var alertRowsCache: List<SsaAlertStore.OverlayRow> = emptyList()
    private var alertErLocalCache = false
    private var alertTabContentBox: LinearLayout? = null
    private var alertTabBtns: MutableList<TextView> = mutableListOf()

    private fun renderAlertBatchResult(rows: List<SsaAlertStore.OverlayRow>, erLocal: Boolean = false) {
        alertRowsCache = rows; alertErLocalCache = erLocal
        val ctx = requireContext()
        alertBox.addView(card { c ->
            c.addView(cardTitle("🔄 持仓池短线预警 · 一键更新（${rows.size} 只）"))
            c.addView(statGrid(listOf(
                "买入" to rows.count { it.advice == "买入" }.toString(),
                "关注" to rows.count { it.advice == "关注" || it.advice.startsWith("关注(") }.toString(),
                "卖出" to rows.count { it.advice.contains("卖出") }.toString(),
                "观望/持仓" to rows.count { it.advice == "观望" || it.advice.startsWith("持仓") }.toString(),
                "数据缺失" to rows.count { it.advice == "数据缺失" }.toString(),
                "更新于" to (rows.firstOrNull()?.updated ?: "—")
            )))
            c.addView(hint("口径：60分钟K取末200根，统一跑 BBI12 冠军组合（STEADY + Δ5ER>-0.05 + 固定成本止损）。" +
                "建议判定：最近4根内峰顶/移动止盈/固定止损→对应卖出；谷底拐点确认→买入；" +
                "持仓→持仓(止损线)；空仓且 Δ5ER 金叉→金叉预警；否则空仓观望。" +
                "买入建议另过 ER70 横截面闸，未过降为「关注(ER<70)」。仅提示不构成投资建议。\n" +
                "（2026-10-22 起旧多指标评分 B1..B5 已停用；评分列仅保留排序：买入100 / 关注80 / 金叉70 / 持仓60）\n" +
                "ER分位 = " + (if (erLocal) "🔧 本机备选：每日荐股池横截面 Kaufman 效率比分位（日线 W=60）"
                                else "PC 推送的每日荐股池横截面 Kaufman 效率比分位") +
                "（0~100，≥70 标 ↑；池外持仓票已并入横截面本机补算）。"))
            // ── 子 Tab 行：入场推荐 / 持仓做T / 混合做T / 历史 / 验证 ──
            val tabRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0)
            }
            alertTabBtns.clear()
            listOf("🟢 入场", "🔧 做T", "🔀 混合", "📜 历史", "📊 验证").forEachIndexed { i, label ->
                val tv = TextView(ctx).apply {
                    text = label; textSize = 12f; setPadding(0, dp(6), 0, dp(6))
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener { alertTabIdx = i; renderAlertTabContent() }
                }
                alertTabBtns.add(tv); tabRow.addView(tv)
            }
            c.addView(tabRow)
            c.addView(View(ctx).apply {
                setBackgroundColor(Color.parseColor("#E0E0E0"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            })
            val contentBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            alertTabContentBox = contentBox
            c.addView(contentBox)
        })
        renderAlertTabContent()
    }

    /** 按 alertTabIdx 渲染当前子 Tab 内容（清空容器后重绘 + Tab 高亮） */
    private fun renderAlertTabContent() {
        val box = alertTabContentBox ?: return
        val ctx = requireContext()
        box.removeAllViews()
        alertTabBtns.forEachIndexed { i, tv ->
            tv.setTextColor(if (i == alertTabIdx) TEAL else GRAY)
            tv.setTypeface(null, if (i == alertTabIdx) Typeface.BOLD else Typeface.NORMAL)
        }
        when (alertTabIdx) {
            1 -> renderT0HoldCards(box)
            2 -> renderHybridHoldCards(box)
            3 -> renderAlertLedger(box)
            4 -> renderVerificationTab(box)
            else -> {
                // 🔍 荐股池扫描结果（每日自动+手动；2026-10-20 方案A）排在持仓预警行之前
                SsaAlertStore.loadScan(ctx)?.takeIf { it.rows.isNotEmpty() }?.let { res ->
                    box.addView(scanPoolCard(ctx, res))
                }
                // 入场 Tab 只展示可入场方向（买入拐点/关注/金叉预警），卖出类归「做T/持仓」卡片
                alertRowsCache.asSequence()
                    .filter {
                        it.advice == "买入" || it.advice.startsWith("关注") || it.advice == "金叉预警"
                    }
                    .sortedWith(compareByDescending<SsaAlertStore.OverlayRow> { it.score }.thenBy { it.code })
                    .forEach { box.addView(alertStockCard(it)) }
            }
        }
    }

    /** 🔍 荐股池扫描卡（每日推荐入场：核心池 1H 冠军口径，最近 4 根内出买入信号 → 入场候选） */
    private fun scanPoolCard(ctx: android.content.Context, res: SsaAlertStore.ScanResult): View = card { c ->
        c.addView(cardTitle(
            "🔍 荐股池扫描 · ${res.rows.count { it.advice == "买入" }} 买入 / ${res.rows.size} 出信号" +
            "（扫 ${res.scannedN} 只 · ${res.updated}）"))
        c.addView(hint("口径：每日荐股核心池逐只跑 1H 冠军组合（BBI12+Δ5ER+固定成本止损），最近 4 根内出现买入信号 → 入场候选；" +
            "ER70 横截面闸同持仓口径（<70 降为「关注(ER<70)」）。买入票已自动落「📜 历史」台账。" +
            "每日首次进入预警页自动扫描一次，也可手动重扫。"))
        res.rows.forEach { c.addView(alertStockCard(it)) }
    }

    private var scanPoolRunning = false

    /** 🔍 扫描荐股池（手动按钮 / 每日自动共用；自动触发见 autoScanPoolDaily） */
    private fun runScanPool(auto: Boolean) {
        val ctx = context ?: return
        if (scanPoolRunning) return
        scanPoolRunning = true
        if (!auto) { scanPoolBtn.isEnabled = false; scanPoolBtn.text = "⏳ 扫描中…约 1 分钟" }
        lifecycleScope.launch {
            try {
                status(if (auto) "⏳ 今日荐股池自动扫描中…" else "⏳ 荐股池全池扫描中（171 只 1H K线 + 冠军口径，约 1 分钟）…")
                val res = withContext(Dispatchers.IO) { SsaAlertBatch.scanPool(ctx) }
                status("✅ 荐股池扫描：${res.rows.size}/${res.scannedN} 只出信号，买入 ${res.rows.count { it.advice == "买入" }}（${res.updated}）")
                alertTabIdx = 0
                if (alertTabContentBox != null) renderAlertTabContent()
            } catch (e: Exception) {
                status("⚠️ 荐股池扫描失败：${e.message}")
            } finally {
                scanPoolRunning = false
                scanPoolBtn.isEnabled = true
                scanPoolBtn.text = "🔍 扫描荐股池（每日推荐入场）"
            }
        }
    }

    private fun onScanPoolRun() = runScanPool(false)

    /** 每日自动扫描：首次进入预警页且当日未扫过 → 后台跑一次（结果落盘，入场推荐 Tab 自动刷新） */
    private fun autoScanPoolDaily() {
        val ctx = context ?: return
        val last = SsaAlertStore.loadScan(ctx)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
        if (last?.date == today) return
        runScanPool(true)
    }

    /** 子Tab2 持仓做T：读持仓池快照（含末根点位 mid/eUp/eDn），渲染点位条卡 */
    private fun renderT0HoldCards(box: LinearLayout) {
        val ctx = requireContext()
        val snaps = SsaT0PoolStore.loadSnapshot(ctx)
        if (snaps.isEmpty()) {
            box.addView(card { c ->
                c.addView(cardTitle("🔧 持仓做T（暂无快照）"))
                c.addView(hint("先到「持仓池」页点「一键更新」生成快照；点位条口径：eDn—现价—eUp，" +
                    "触 eUp 且 RSI>70 卖T、破 eDn 且 ER闸过 买T（rsi+adx+erq 已验证组合）。"))
            })
            return
        }
        box.addView(card { c ->
            c.addView(cardTitle("🔧 持仓做T点位（${snaps.size} 只 · 快照 ${snaps.firstOrNull()?.updated ?: "—"}）"))
            c.addView(hint("点位条：左 eDn(买T点) — ●现价 — 右 eUp(卖T点)；状态 = 三态机末根；建议含 ER闸/RSI确认 拦截原因。点卡片看 T0 点位图。"))
        })
        snaps.sortedByDescending { it.holdPct }.forEach { s ->
            val cv = card { c ->
                val stateTxt = when (s.state) { 1 -> "UP"; 2 -> "FLAT"; 3 -> "窗口"; else -> "—" }
                // 2026-10-20 文案修正：卖出≠清仓。UP/FLAT 的"卖出"= 超上沿卖T（卖持仓30%），窗口态才是全卖
                val actTxt = when {
                    s.action == 1 && s.state == 3 -> "满仓买"
                    s.action == 1 -> "买T/回补"
                    s.action == 2 && s.state == 3 -> "全卖"
                    s.action == 2 -> "卖T(卖持仓30%)"
                    s.action == 3 -> "持有"
                    else -> "—"
                }
                val actColor = when (s.action) { 1 -> RED; 2 -> GREEN; else -> GRAY }
                c.addView(TextView(ctx).apply {
                    text = "${s.name.ifBlank { s.code }}(${s.code})  $stateTxt · $actTxt ｜ 持仓${"%.0f".format(s.holdPct)}%"
                    textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(actColor)
                })
                c.addView(TextView(ctx).apply {
                    text = "${s.barLabel} @${fmt2(s.close)} ｜ 策略收益 ${"%.1f".format(s.ret)}% / 回撤 ${"%.1f".format(s.mdd)}% / 胜率 ${"%.0f".format(s.winRate)}% / ${s.trades}笔"
                    textSize = 11f; setTextColor(GRAY); setPadding(0, dp(2), 0, 0)
                })
                if (s.mid.isFinite() && s.eUp.isFinite() && s.eDn.isFinite() && s.eUp > s.eDn) {
                    // 点位条：eDn ━━━ ●现价 ━━━ eUp（现价位置按区间比例）
                    val pos = ((s.close - s.eDn) / (s.eUp - s.eDn)).coerceIn(0.0, 1.0)
                    val bar = "━".repeat((pos * 20).toInt()) + "●" + "━".repeat(20 - (pos * 20).toInt())
                    c.addView(TextView(ctx).apply {
                        text = "${fmt2(s.eDn)} $bar ${fmt2(s.eUp)}\n中位 ${fmt2(s.mid)} ｜ 现价位于带内 ${"%.0f".format(pos * 100)}%"
                        textSize = 11f; setTextColor(if (pos > 0.8) RED else if (pos < 0.2) GREEN else Color.parseColor("#333333"))
                        setPadding(0, dp(4), 0, 0)
                    })
                }
                if (s.reason.isNotBlank()) c.addView(TextView(ctx).apply {
                    text = s.reason; textSize = 10.5f; setTextColor(GRAY); setPadding(0, dp(3), 0, 0)
                })
            }
            // 2026-10-20：点卡片 → T0 点位图弹窗（K线+中枢/极限带+T买卖点+SSA周期分量副图）
            cv.setOnClickListener { showT0Chart(s.name.ifBlank { s.code }, s.code) }
            box.addView(cv)
        }
    }

    // ═══════════ 子Tab3 混合做T（2026-10-21）：与持仓做T同股票集，SSA混合策略(1H)信号；点卡片自动寻优 ═══════════

    private data class HybridCard(
        val code: String, val name: String, val advice: Int, val date: String, val close: Double,
        val tsLast: Double, val tlLast: Double,
        val ret: Double, val mdd: Double, val winRate: Double, val trades: Int, val err: String?
    )
    private var hybridCards: List<HybridCard>? = null
    private var hybridRunning = false
    /** 单票更新中（防重复点击） */
    private var hybridUpdating = false
    /** 本次会话已拉的 1H bars：点卡片画图直接复用，不再重复拉网 */
    private val hybridBarsCache = HashMap<String, List<SsaHybridEngine.Bar>>()

    // 混合做T 卡片本地缓存（“像做T快照一样保持”：切 Tab / 重启不重算）
    // cards_v2：2026-10-22 T+1 交易日约束上线，旧缓存口径失效（v1 键名不再读取，自动重算一次）
    private val HYBRID_PREF = "ssa_hybrid_cards"
    private val HYBRID_KEY = "cards_v2"

    /** 区间做T 通道变体（反转/海龟S1/海龟S2），持久化在 ssa_range_t prefs */
    private fun rangeVariantPref(ctx: Context): SsaRangeTEngine.Variant {
        val key = ctx.getSharedPreferences("ssa_range_t", Context.MODE_PRIVATE)
            .getString("variant", null)
        return SsaRangeTEngine.variantByKey(key)
    }

    private fun saveHybridCache(ctx: Context, cards: List<HybridCard>) {
        runCatching {
            val arr = JSONArray()
            cards.forEach { h ->
                arr.put(JSONObject().apply {
                    put("code", h.code); put("name", h.name); put("advice", h.advice)
                    put("date", h.date); put("close", h.close.ifFinite())
                    put("tsLast", h.tsLast.ifFinite()); put("tlLast", h.tlLast.ifFinite())
                    put("ret", h.ret.ifFinite()); put("mdd", h.mdd.ifFinite())
                    put("winRate", h.winRate.ifFinite()); put("trades", h.trades)
                    h.err?.let { put("err", it) }
                })
            }
            ctx.getSharedPreferences(HYBRID_PREF, Context.MODE_PRIVATE).edit()
                .putString(HYBRID_KEY, arr.toString()).apply()
        }
    }

    private fun loadHybridCache(ctx: Context): List<HybridCard>? {
        val raw = ctx.getSharedPreferences(HYBRID_PREF, Context.MODE_PRIVATE)
            .getString(HYBRID_KEY, null) ?: return null
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HybridCard(
                    o.getString("code"), o.optString("name", ""),
                    o.optInt("advice", SsaHybridEngine.HOLD_EMPTY),
                    o.optString("date", "—"), o.optDouble("close", 0.0),
                    o.optDouble("tsLast", Double.NaN), o.optDouble("tlLast", Double.NaN),
                    o.optDouble("ret", 0.0), o.optDouble("mdd", 0.0),
                    o.optDouble("winRate", 0.0), o.optInt("trades", 0),
                    if (o.has("err")) o.getString("err") else null
                )
            }.ifEmpty { null }
        }.getOrNull()
    }

    private fun clearHybridCache(ctx: Context) {
        ctx.getSharedPreferences(HYBRID_PREF, Context.MODE_PRIVATE).edit().remove(HYBRID_KEY).apply()
    }

    private fun Double.ifFinite(): Any = if (isFinite()) this else JSONObject.NULL

    private fun hybridAdvice(a: Int): Pair<String, Int> = when (a) {
        SsaHybridEngine.BUY -> "买入" to RED
        SsaHybridEngine.SELL -> "卖出" to GREEN
        SsaHybridEngine.HOLD_POS -> "持仓" to BLUE
        else -> "空仓" to GRAY
    }

    /**
     * 拉 1H K线并转为混合引擎 Bar（date 列带时间，供图/台账标签用）。
     *
     * ⚠️ `fetchHourlyHistory` 首次会拉全量 1023 根（约1.7年），之后走磁盘缓存+最近80根增量；
     * 这里只截取末 [POOL_HOURLY] 根参与计算（与做T同口径），避免对 1023 根逐K跑 SSA。
     */
    private suspend fun fetchHybridBars(ctx: android.content.Context, c6: String): List<SsaHybridEngine.Bar> =
        withContext(Dispatchers.IO) {
            runCatching {
                IntradayKlineFetcher(ctx)
                    .fetchHourlyHistory(c6, POOL_HOURLY)
                    .filter { it.close > 0 && it.high > 0 && it.low > 0 }.sortedBy { it.datetime }
                    .takeLast(POOL_HOURLY)
                    .map {
                        SsaHybridEngine.Bar("${it.date} ${it.datetime.substringAfter(' ', "")}",
                            it.open, it.high, it.low, it.close)
                    }
            }.getOrDefault(emptyList()).also { if (it.isNotEmpty()) hybridBarsCache[c6] = it }
        }

    /** 子Tab3 混合做T：读持仓池快照同批股票，逐股跑混合策略1H（默认参数）出信号卡 */
    private fun renderHybridHoldCards(box: LinearLayout) {
        val ctx = requireContext()
        val snaps = SsaT0PoolStore.loadSnapshot(ctx)
        if (snaps.isEmpty()) {
            box.addView(card { c ->
                c.addView(cardTitle("🔀 混合做T（暂无持仓快照）"))
                c.addView(hint("先到「持仓池」页点「一键更新」生成快照；本 Tab 与持仓做T 同一批股票，策略换成 SSA 混合策略（1H）。"))
            })
            return
        }
        // 先读本地缓存（像做T快照一样切 Tab / 重启都保持），无缓存才全量重算
        val cards = hybridCards ?: loadHybridCache(ctx)?.also { hybridCards = it }
        if (cards == null) {
            box.addView(card { c ->
                c.addView(cardTitle("🔀 混合做T（计算中…）"))
                c.addView(hint("逐股拉 1H K线跑混合策略（默认 ts10/tl20/cy60/s2）。" +
                    "首次每票拉全量约1023根，之后走本地缓存+80根增量，并发4路。"))
            })
            runHybridCards()
            return
        }
        box.addView(card { c ->
            c.addView(cardTitle("🔀 混合做T（${cards.size} 只 · 混合策略1H 默认参数 ts10/tl20/cy60/s2）"))
            c.addView(hint("买 = 短趋势斜率转正/周期谷底确认；卖 = 周期峰顶确认/趋势死叉。" +
                "卡片结果已本地保持（不随切 Tab 重算）；点「🔄 更新」只重算该票，点卡片 → 90 组合寻优+点位图。" +
                "回测结论：1H 通道混合策略不及 T0/BBI12 双通道（寻优最优 -4.83%/-24.88%），此 Tab 供调参对照用。"))
            c.addView(Button(ctx).apply {
                text = "↻ 重算全部混合信号"; textSize = 12f
                setOnClickListener {
                    hybridCards = null; clearHybridCache(ctx)
                    hybridBarsCache.clear()
                    renderAlertTabContent()
                }
            })
        })
        val byCode = cards.associateBy { it.code }
        snaps.forEach { s -> byCode[s.code]?.let { box.addView(hybridCardView(it)) } }
    }

    /** 单票计算（拉 1H + 默认参数跑混合策略），全量重算与「🔄 更新」共用 */
    private suspend fun computeHybridCard(ctx: Context, code: String, name: String): HybridCard {
        val bars = fetchHybridBars(ctx, code)
        if (bars.size < 90) return HybridCard(code, name, SsaHybridEngine.HOLD_EMPTY, "—", 0.0,
            Double.NaN, Double.NaN, 0.0, 0.0, 0.0, 0, "1H K线仅 ${bars.size} 根，数据不足")
        val r = withContext(Dispatchers.Default) {
            runCatching { SsaHybridEngine.run(bars, SsaHybridEngine.Params()) }.getOrNull()
        } ?: return HybridCard(code, name, SsaHybridEngine.HOLD_EMPTY, "—", 0.0,
            Double.NaN, Double.NaN, 0.0, 0.0, 0.0, 0, "计算失败")
        val n = r.bars.size
        return HybridCard(code, name, r.latestAdvice, r.latestDate, r.latestClose,
            r.trendShortLine[n - 1], r.trendLongLine[n - 1],
            r.stats.totalReturnPct, r.stats.maxDrawdownPct, r.stats.winRatePct,
            r.stats.tradeCount, null)
    }

    /** 全量计算混合策略1H信号（并发 4 路；结果写本地缓存，切 Tab / 重启不再重算） */
    private fun runHybridCards() {
        val ctx = context ?: return
        if (hybridRunning) return
        hybridRunning = true
        lifecycleScope.launch {
            try {
                val snaps = withContext(Dispatchers.IO) { SsaT0PoolStore.loadSnapshot(ctx) }
                val total = snaps.size
                var done = 0
                val out = coroutineScope {
                    val sem = Semaphore(4)
                    snaps.map { s ->
                        async {
                            val card = sem.withPermit { computeHybridCard(ctx, s.code, s.name) }
                            done++
                            status("⏳ 混合做T $done/$total：${s.name.ifBlank { s.code }}…")
                            card
                        }
                    }.awaitAll()
                }
                hybridCards = out
                saveHybridCache(ctx, out)
                status("✅ 混合做T信号已更新（${out.size} 只 · 已本地保存）")
            } catch (e: Exception) {
                status("⚠️ 混合做T计算失败：${e.message}")
            } finally {
                hybridRunning = false
            }
            if (isAdded && alertTabIdx == 2 && alertTabContentBox != null) renderAlertTabContent()
        }
    }

    /** 单票更新：只重算这一只，替换缓存中的卡片并落盘（其余票不受影响） */
    private fun updateHybridOne(code: String, name: String) {
        val ctx = context ?: return
        if (hybridUpdating) { status("⏳ 已有单票更新进行中…"); return }
        hybridUpdating = true
        lifecycleScope.launch {
            try {
                status("⏳ 更新混合信号 ${name.ifBlank { code }}($code)…")
                val card = computeHybridCard(ctx, code, name)
                val list = (hybridCards ?: loadHybridCache(ctx) ?: emptyList()).toMutableList()
                val i = list.indexOfFirst { it.code == code }
                if (i >= 0) list[i] = card else list.add(card)
                hybridCards = list
                saveHybridCache(ctx, list)
                status("✅ ${name.ifBlank { code }} 混合信号：${hybridAdvice(card.advice).first}")
            } catch (e: Exception) {
                status("⚠️ 单票更新失败：${e.message}")
            } finally {
                hybridUpdating = false
            }
            if (isAdded && alertTabIdx == 2 && alertTabContentBox != null) renderAlertTabContent()
        }
    }

    /** 混合做T 单股卡片：信号 + 末根趋势/现价位置 + 默认参数绩效 */
    private fun hybridCardView(h: HybridCard): View {
        val ctx = requireContext()
        val (aTxt, aColor) = hybridAdvice(h.advice)
        val v = card { c ->
            val row1 = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            row1.addView(TextView(ctx).apply {
                text = "${h.name.ifBlank { h.code }} (${h.code})"
                textSize = 15f; setTextColor(Color.parseColor("#222222"))
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row1.addView(TextView(ctx).apply {
                text = "▶ $aTxt"; textSize = 15f; setTextColor(aColor)
                setTypeface(typeface, Typeface.BOLD); setPadding(dp(8), 0, 0, 0)
            })
            c.addView(row1)
            if (h.err != null) {
                c.addView(TextView(ctx).apply {
                    text = h.err; textSize = 11f; setTextColor(GRAY); setPadding(0, dp(4), 0, 0)
                })
            } else {
                c.addView(TextView(ctx).apply {
                    text = "${h.date} @${fmt2(h.close)} ｜ 策略收益 ${"%.1f".format(h.ret)}% / 回撤 ${"%.1f".format(h.mdd)}% / 胜率 ${"%.0f".format(h.winRate)}% / ${h.trades}笔"
                    textSize = 11f; setTextColor(GRAY); setPadding(0, dp(2), 0, 0)
                })
                if (h.tsLast.isFinite() && h.tlLast.isFinite() && h.tsLast > 0) {
                    val dev = (h.close / h.tsLast - 1) * 100
                    c.addView(TextView(ctx).apply {
                        text = "短趋势 ${fmt2(h.tsLast)}（现价 ${"%+.1f".format(dev)}%）｜ 长趋势 ${fmt2(h.tlLast)} ｜ ${if (h.tsLast > h.tlLast) "多头排列" else "空头排列"}"
                        textSize = 11f; setPadding(0, dp(2), 0, 0)
                        setTextColor(if (h.tsLast > h.tlLast) RED else GREEN)
                    })
                }
            }
            // 单票更新：只重算该票（其余卡片与本地缓存保持不变）
            val btnRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, 0)
            }
            btnRow.addView(Button(ctx).apply {
                text = "🔄 更新"; textSize = 11f; setTextColor(Color.WHITE)
                setBackgroundColor(BLUE); backgroundTintList = null
                layoutParams = LinearLayout.LayoutParams(dp(74), dp(32))
                setOnClickListener { updateHybridOne(h.code, h.name) }
            })
            btnRow.addView(TextView(ctx).apply {
                text = "点卡片 → 参数寻优 + 点位图"
                textSize = 10.5f; setTextColor(TEAL); gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, 0, 0)
            })
            c.addView(btnRow)
        }
        if (h.err == null) v.setOnClickListener { showHybridChart(h.name.ifBlank { h.code }, h.code) }
        return v
    }

    /** 点卡片：拉 1H → 90 组合寻优（ts/tl/cy × span1/2）→ 最优参数回算 → 弹点位图 */
    private fun showHybridChart(name: String, c6: String) {
        val ctx = requireContext()
        lifecycleScope.launch {
            val cached = hybridBarsCache[c6]
            status(if (cached != null) "⏳ [$c6] 复用本会话 1H K线（${cached.size} 根）…"
                   else "⏳ [$c6] 拉取 1H K线…")
            val bars = cached ?: fetchHybridBars(ctx, c6)
            if (bars.size < 90) { status("⚠️ [$c6] 1H K线仅 ${bars.size} 根，数据不足"); return@launch }
            status("⏳ [$c6] 混合策略参数寻优（90 组合）…")
            val rows = withContext(Dispatchers.Default) {
                runCatching {
                    SsaHybridEngine.scanGrid(bars, SsaHybridEngine.Params(),
                        trendGrid = listOf(5, 10, 20, 30, 40, 60), cycleGrid = listOf(20, 30, 60),
                        spanGrid = listOf(1, 2))
                }
            }.getOrElse { status("⚠️ [$c6] 寻优失败：${it.message}"); return@launch }
            if (rows.isEmpty()) { status("⚠️ [$c6] 寻优无有效组合"); return@launch }
            val best = rows.first()
            status("⏳ [$c6] 最优 ts${best.params.trendShort}/tl${best.params.trendLong}/cy${best.params.cycleL}/s${best.params.smoothSpan} 回算…")
            val r = withContext(Dispatchers.Default) {
                runCatching { SsaHybridEngine.run(bars, best.params) }.getOrNull()
            }
            if (r == null) { status("⚠️ [$c6] 最优参数回算失败"); return@launch }
            showHybridDialog(name, c6, rows.take(5), r)
            status("✅ [$c6] 混合策略寻优：最优 ${"%.1f".format(best.total)}% / 回撤 ${"%.1f".format(best.mdd)}%")
        }
    }

    /** 混合策略点位图弹窗：Top5 组合表 + 主图(K线+双趋势线+买▲卖▼信号+持仓区间黄底) + 副图(SSA周期分量) */
    private fun showHybridDialog(name: String, code: String, top: List<SsaHybridEngine.ScanRow>,
                                 r: SsaHybridEngine.Result) {
        val ctx = requireContext()
        val bars = r.bars
        val n = bars.size
        val from = (n - 120).coerceAtLeast(0)
        val hDp = stratChartHDp(true)
        val p = r.params
        val dateArr = JSONArray().apply { for (i in from until n) put(bars[i].date) }
        val klineArr = JSONArray().apply {
            for (i in from until n) {
                val b = bars[i]
                put(JSONArray().apply { put(r3(b.open)); put(r3(b.close)); put(r3(b.low)); put(r3(b.high)) })
            }
        }
        // 信号点：advice 数组 BUY→▲红 / SELL→▼绿（信号根收盘价）
        val buys = JSONArray(); val sells = JSONArray()
        for (i in from until n) when (r.advice[i]) {
            SsaHybridEngine.BUY -> buys.put(JSONArray().apply { put(i - from); put(r3(bars[i].close)) })
            SsaHybridEngine.SELL -> sells.put(JSONArray().apply { put(i - from); put(r3(bars[i].close)) })
        }
        // 持仓区间黄底：已平仓 T 单 + 当前未平仓段
        val areas = JSONArray()
        r.trades.forEach { t ->
            if (t.sellIndex >= from) areas.put(JSONArray().apply {
                put(JSONObject().apply { put("xAxis", maxOf(t.buyIndex, from) - from) })
                put(JSONObject().apply { put("xAxis", t.sellIndex - from) })
            })
        }
        if (r.simSnapshot.shT > 0 && r.simSnapshot.biT >= 0) areas.put(JSONArray().apply {
            put(JSONObject().apply { put("xAxis", maxOf(r.simSnapshot.biT, from) - from) })
            put(JSONObject().apply { put("xAxis", n - 1 - from) })
        })
        val (aTxt, aColor) = hybridAdvice(r.latestAdvice)
        val data = JSONObject().apply {
            put("dates", dateArr); put("kline", klineArr)
            put("charts", JSONArray().put(JSONObject().apply {
                put("title", "混合策略1H · $name ($code)")
                put("sub", "最优 ts${p.trendShort}/tl${p.trendLong}/cy${p.cycleL}/s${p.smoothSpan}" +
                    " · T单 ${r.stats.tradeCount} 笔 · 胜率 ${"%.0f".format(r.stats.winRatePct)}%" +
                    " · 全期 ${"%.1f".format(r.stats.totalReturnPct)}% · 展示末 ${n - from} 根 1H")
                put("h", hDp)
                put("series", JSONArray().apply {
                    put(lineJson("短趋势ts", "#00897b", 1.3, 0, r.trendShortLine, from))
                    put(lineJson("长趋势tl", "#8e24aa", 1.3, 0, r.trendLongLine, from))
                    put(JSONObject().apply {
                        put("n", "买入"); put("t", "scatter"); put("ax", 0); put("c", "#e53935")
                        put("sym", "triangle"); put("rot", 0); put("sz", 9); put("d", buys)
                    })
                    put(JSONObject().apply {
                        put("n", "卖出"); put("t", "scatter"); put("ax", 0); put("c", "#2e7d32")
                        put("sym", "triangle"); put("rot", 180); put("sz", 9); put("d", sells)
                    })
                })
                put("subs", JSONArray().put(JSONObject().apply {
                    put("name", "SSA周期"); put("zero", true)
                    put("series", JSONArray().apply {
                        put(lineJson("SSA周期分量", "#fb8c00", 1.4, 1, r.cycleLine, from))
                    })
                }))
                put("areas", areas)
            }))
        }
        val tpl = runCatching {
            ctx.assets.open("pivot_chart.html").bufferedReader().use { it.readText() }
        }.getOrNull()
        if (tpl == null) { status("⚠️ 图表模板 pivot_chart.html 缺失"); return }
        val wv = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportZoom(false); settings.builtInZoomControls = false; settings.displayZoomControls = false
            setBackgroundColor(Color.WHITE)
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(hDp))
        }
        wv.setOnTouchListener { v, e ->
            v.parent?.requestDisallowInterceptTouchEvent(e.pointerCount >= 2)
            false
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript("document.body.scrollHeight") { raw ->
                    val cssH = raw?.trim('"', ' ')?.toDoubleOrNull() ?: return@evaluateJavascript
                    if (cssH <= 0.0) return@evaluateJavascript
                    val lp = view.layoutParams
                    val exact = dp(cssH.toInt() + 2)
                    if (lp != null && lp.height != exact) { lp.height = exact; view.layoutParams = lp }
                }
            }
        }
        wv.loadDataWithBaseURL("file:///android_asset/", tpl.replace("__DATA__", data.toString()),
            "text/html", "UTF-8", null)
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(cardTitle("📈 $name ($code) · 混合策略1H 寻优"))
            // 末根信号 + 最优参数绩效
            addView(TextView(ctx).apply {
                text = "末根信号：$aTxt（${r.latestDate} @${fmt2(r.latestClose)}）"
                textSize = 14f; setTextColor(aColor); setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(4))
            })
            // Top5 参数组合表
            addView(TextView(ctx).apply {
                text = "🏆 参数寻优 Top${top.size}（ts短/tl长/cy周期/s平滑 ｜ 收益/回撤/笔数/胜率）"
                textSize = 12f; setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(4), 0, dp(2))
            })
            top.forEachIndexed { i, row ->
                val bp = row.params
                addView(TextView(ctx).apply {
                    text = "${"①②③④⑤"[i]} ts${bp.trendShort}/tl${bp.trendLong}/cy${bp.cycleL}/s${bp.smoothSpan}" +
                        " ｜ ${"%.1f".format(row.total)}% / ${"%.1f".format(row.mdd)}% / ${row.tradeCount}笔 / ${"%.0f".format(row.winRate)}%" +
                        if (i == 0) "  ← 采用" else ""
                    textSize = 11.5f
                    setTextColor(if (i == 0) RED else Color.parseColor("#555555"))
                    setPadding(0, dp(1), 0, dp(1))
                })
            }
            addView(legend("青线=短趋势 紫线=长趋势（多头排列=ts>tl）；▲红=买入信号（斜率转正/谷底） ▼绿=卖出信号（峰顶/死叉），" +
                "黄底=持仓区间。副图=SSA周期分量（橙线，绕0轴振荡，谷底买/峰顶卖依据）。" +
                "展示口径与做T页一致：末 120 根 1H。双指捏合缩放、拖动滑块平移。"))
            addView(wv)
        }
        val scroll = ScrollView(ctx).apply { addView(content) }
        val dlg = AlertDialog.Builder(ctx)
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .create()
        dlg.setOnDismissListener { wv.destroy() }
        dlg.show()
        dlg.window?.setLayout(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.82).toInt())
    }

    // ═══════════ 子Tab5 信号验证台账（两阶段：拍早照→收盘核对，2026-10-21）═══════════════════════

    private var verifyLoading = false

    /** 预测子 Tab：两个动作按钮 + 日期分组列表（含命中率统计） */
    private fun renderVerificationTab(box: LinearLayout) {
        val ctx = requireContext()
        box.addView(card { c ->
            c.addView(cardTitle("📊 信号验证台账 · ${PredictionLedger.today()}"))
            c.addView(hint("两阶段：📸 早盘快照（9:25 前后冻结信号+预测价位）→ 🔍 收盘核对（15:05+ 拉日线验证对错）。" +
                "当前覆盖 T0 做T + 混合策略，BBI12 入场推荐下一版补。滚动 300 条。"))
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(Button(ctx).apply {
                text = "📸 拍早照 · 全策略"; textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { runSnapshot() }
            })
            row.addView(Button(ctx).apply {
                text = "🔍 收盘核对"; textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { runVerify() }
            })
            c.addView(row)
        })
        renderVerificationList(box)
    }

    /** 渲染验证台账列表（日期分组 + 汇总命中率 + 每条预测 vs 实际） */
    private suspend fun loadVerifyGrouped(ctx: android.content.Context) =
        withContext(Dispatchers.IO) { PredictionLedger.loadGroupedByDate(ctx) }

    private fun renderVerificationList(box: LinearLayout) {
        val ctx = context ?: return
        lifecycleScope.launch {
            val groups = loadVerifyGrouped(ctx)
            if (groups.isEmpty()) {
                box.addView(card { c ->
                    c.addView(cardTitle("📊 暂无预测记录"))
                    c.addView(hint("点「📸 拍早照」开始记录早盘信号；收盘后点「🔍 收盘核对」回填验证结果。"))
                })
                return@launch
            }
            // 全部汇总行
            val all = groups.flatMap { it.second }
            val verified = all.filter { it.isVerified() }
            val correct = verified.count { it.correct == true }
            val avgScore = run {
                val scores = verified.mapNotNull { it.score }
                if (scores.isNotEmpty()) scores.average() else 0.0
            }
            box.addView(card { c ->
                c.addView(cardTitle("📈 总体 · ${verified.size}/${all.size} 已验证"))
                c.addView(hint("命中率 ${if (verified.isNotEmpty()) "%.1f%%".format(correct * 100.0 / verified.size) else "—"}" +
                    " · 平均分 ${"%.2f".format(avgScore)}" +
                    " · 未验证 ${all.size - verified.size} 条（收盘后可核对）"))
            })
            // 按日期分组
            groups.forEach { (date, recs) ->
                val v = recs.filter { it.isVerified() }
                val corr = v.count { it.correct == true }
                box.addView(card { c ->
                    c.addView(cardTitle(
                        "📅 ${date.take(4)}-${date.substring(4,6)}-${date.substring(6)}" +
                            "  · 已验 ${v.size}/${recs.size}" +
                            (if (v.isNotEmpty()) "  命中率 ${"%.0f%%".format(corr * 100.0 / v.size)}" else "")))
                    recs.forEach { c.addView(verifyCard(it)) }
                })
            }
        }
    }

    /** 单条预测验证卡：左=预测 右=实际 下=命中判定 */
    private fun verifyCard(r: PredictionLedger.PredictionRecord): View {
        val ctx = requireContext()
        val p = r.pricePred
        return card { c ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val signalColor = when (r.signal) {
                "BUY" -> RED; "SELL" -> GREEN; "HOLD" -> BLUE; else -> GRAY
            }
            // 策略标签
            row.addView(TextView(ctx).apply {
                text = r.strategy; textSize = 11f; setTypeface(null, Typeface.BOLD)
                setPadding(0, 0, dp(6), 0); setTextColor(TEAL)
            })
            // 代码名
            row.addView(TextView(ctx).apply {
                text = "${r.name.ifBlank { r.code }} (${r.code})"
                textSize = 13f; setTypeface(null, Typeface.BOLD); setPadding(0, 0, dp(6), 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            // 信号
            row.addView(TextView(ctx).apply {
                text = "▶ ${r.signal}"; textSize = 13f; setTextColor(signalColor); setTypeface(null, Typeface.BOLD)
            })
            c.addView(row)
            c.addView(TextView(ctx).apply {
                text = "📸 快照 ${r.snapshotAt}  ·  ${r.signalDesc}"
                textSize = 11f; setTextColor(GRAY); setPadding(0, dp(2), 0, 0)
            })
            // 预测价位行
            val pred = buildString {
                append("🔮 预测基价 ").append("%.2f".format(p.openLast))
                p.targetUp?.let { append("  上界 ").append("%.2f".format(it)) }
                p.targetDn?.let { append("  下界 ").append("%.2f".format(it)) }
                p.stopLoss?.let { append("  止损 ").append("%.2f".format(it)) }
            }
            c.addView(TextView(ctx).apply {
                text = pred; textSize = 11f; setTextColor(Color.parseColor("#555555")); setPadding(0, dp(2), 0, 0)
            })
            // 实际行（如已验证）
            if (r.isVerified()) {
                val actualStr = buildString {
                    append("📉 实际 O ").append("%.2f".format(r.actualOpen!!))
                    append("  H ").append("%.2f".format(r.actualHigh!!))
                    append("  L ").append("%.2f".format(r.actualLow!!))
                    append("  C ").append("%.2f".format(r.actualClose!!))
                    append("  ${"%+.2f".format(r.actualChangePct!!)}%")
                }
                c.addView(TextView(ctx).apply {
                    text = actualStr; textSize = 11.5f; setPadding(0, dp(2), 0, 0)
                    setTextColor(if (r.actualChangePct!! >= 0) RED else GREEN)
                })
                c.addView(TextView(ctx).apply {
                    text = "🏷️ ${if (r.correct == true) "✅" else "❌"} ${"%.0f".format(r.score!! * 100)}分" +
                        "  ·  ${r.verifyNote}"
                    textSize = 11f; setTypeface(null, Typeface.BOLD); setPadding(0, dp(2), 0, 0)
                    setTextColor(if (r.correct == true) TEAL else Color.parseColor("#888888"))
                })
            } else {
                c.addView(TextView(ctx).apply {
                    text = "⏳ 待验证（收盘后点「🔍 收盘核对」回填日线 OHLC）"
                    textSize = 11f; setTextColor(GRAY); setPadding(0, dp(2), 0, 0)
                })
            }
        }
    }

    /** 📸 拍早照：遍历 T0 持仓 + Hybrid 混合持仓 + BBI12 入场，写 PredictionLedger */
    private fun runSnapshot() {
        val ctx = context ?: return
        lifecycleScope.launch {
            val today = PredictionLedger.today()
            val now = PredictionLedger.nowStr()
            val recs = ArrayList<PredictionLedger.PredictionRecord>()

            // ── 1. T0 持仓做T（来自最新快照）──────────────
            val snaps = withContext(Dispatchers.IO) { SsaT0PoolStore.loadSnapshot(ctx) }
            snaps.forEach { s ->
                if (s.close <= 0) return@forEach
                val (sig, desc) = when (s.state) {
                    1 -> when (s.action) {
                        1 -> "BUY" to "UP态·${s.reason}"        // UP 里可能触发 buyT
                        2 -> "SELL" to "UP态·${s.reason}"       // UP 卖T
                        else -> "HOLD" to "UP态·持仓${"%.0f".format(s.holdPct)}%"
                    }
                    2 -> when (s.action) {
                        1 -> "BUY" to "FLAT·${s.reason}"
                        2 -> "SELL" to "FLAT·${s.reason}"
                        else -> "EMPTY" to "FLAT·空仓等待"
                    }
                    3 -> when (s.action) {
                        1 -> "BUY" to "WINDOW·上穿中位满仓买"
                        2 -> "SELL" to "WINDOW·触nUp全卖"
                        else -> "HOLD" to "WINDOW·窗口运行中"
                    }
                    else -> "HOLD" to "未知态·${s.reason}"
                }
                recs.add(PredictionLedger.PredictionRecord(
                    date = today, snapshotAt = now,
                    code = s.code, name = s.name,
                    strategy = "T0", signal = sig, signalDesc = desc,
                    pricePred = PredictionLedger.PricePred(
                        openLast = s.close,
                        targetUp = s.eUp.takeIf { it.isFinite() && it > 0 },
                        targetDn = s.eDn.takeIf { it.isFinite() && it > 0 }
                    )
                ))
            }
            // ── 2. 混合做T（来自内存缓存 hybridCards）──────────
            (hybridCards ?: runHybridCardsSync()).forEach { h ->
                if (h.err != null || h.close <= 0) return@forEach
                val (sig, desc) = when (h.advice) {
                    SsaHybridEngine.BUY -> "BUY" to "混合·斜率转正/谷底确认"
                    SsaHybridEngine.SELL -> "SELL" to "混合·峰顶确认/死叉"
                    SsaHybridEngine.HOLD_POS -> "HOLD" to "混合·持仓中"
                    else -> "EMPTY" to "混合·空仓"
                }
                recs.add(PredictionLedger.PredictionRecord(
                    date = today, snapshotAt = now,
                    code = h.code, name = h.name,
                    strategy = "Hybrid", signal = sig, signalDesc = desc,
                    pricePred = PredictionLedger.PricePred(
                        openLast = h.close,
                        targetUp = h.tsLast.takeIf { it.isFinite() && it > 0 },
                        targetDn = h.tlLast.takeIf { it.isFinite() && it > 0 }
                    )
                ))
            }
            // ── 3. BBI12 入场推荐（来自荐股池扫描）──────────────
            val scan = withContext(Dispatchers.IO) { SsaAlertStore.loadScan(ctx) }
            scan?.rows?.forEach { r ->
                if (r.advice != "买入") return@forEach
                recs.add(PredictionLedger.PredictionRecord(
                    date = today, snapshotAt = now,
                    code = r.code, name = r.name,
                    strategy = "BBI12", signal = "BUY", signalDesc = "BBI12 拐点底·买入推荐",
                    pricePred = PredictionLedger.PricePred(openLast = r.price)
                ))
            }
            // ── 写台账 ──
            if (recs.isEmpty()) {
                status("⚠️ 拍早照：当前无持仓快照，先到「持仓做T」页点「一键更新」")
                return@launch
            }
            withContext(Dispatchers.IO) { PredictionLedger.appendAll(ctx, recs) }
            status("📸 已拍早照：${recs.size} 条预测（T0 ${snaps.size} · Hybrid ${hybridCards?.size ?: 0} · BBI12 ${scan?.rows?.count { it.advice == "买入" } ?: 0}）")
            renderAlertTabContent()
        }
    }

    /** 同步方式取 hybridCards（如内存无则返回空，不阻塞） */
    private fun runHybridCardsSync(): List<HybridCard> {
        // 如果没算过，后台启动一次让它尽快出结果
        if (hybridCards == null && !hybridRunning) runHybridCards()
        return hybridCards ?: emptyList()
    }

    /** 🔍 收盘核对：拉当日日线，回填 verification 阶段 */
    private fun runVerify() {
        val ctx = context ?: return
        if (verifyLoading) return
        verifyLoading = true
        lifecycleScope.launch {
            val unv = withContext(Dispatchers.IO) { PredictionLedger.loadUnverified(ctx) }
            if (unv.isEmpty()) {
                status("✅ 没有待验证的预测")
                verifyLoading = false; return@launch
            }
            status("⏳ 收盘核对：拉取 ${unv.size} 只当日日线…")
            val done = HashSet<String>()
            var verified = 0; var skipped = 0
            val fetcher = IntradayKlineFetcher(ctx)
            unv.forEachIndexed { i, rec ->
                val key = "${rec.code}|${rec.strategy}"
                if (done.contains(key)) { skipped++; return@forEachIndexed }
                done.add(key)
                if (i % 10 == 0) status("⏳ 核对中 ${i + 1}/${unv.size}…")
                val daily = withContext(Dispatchers.IO) {
                    var arr: DoubleArray? = null
                    try { arr = fetcher.fetchDailyOnce("sh${rec.code}") } catch (_: Exception) {}
                    if (arr == null) try { arr = fetcher.fetchDailyOnce("sz${rec.code}") } catch (_: Exception) {}
                    arr
                }
                if (daily == null) { skipped++; return@forEachIndexed }
                val o = daily[0]; val h = daily[1]; val l = daily[2]; val c = daily[3]
                val filled = PredictionLedger.verify(rec, o, h, l, c)
                withContext(Dispatchers.IO) { PredictionLedger.updateVerified(ctx, filled) }
                verified++
            }
            status("🔍 收盘核对完成：验证 $verified 条 · 跳过 $skipped 条")
            verifyLoading = false
            renderAlertTabContent()
        }
    }

    /** 子Tab4 推荐历史台账 */
    private fun renderAlertLedger(box: LinearLayout) {
        val ctx = requireContext()
        val recs = SsaAlertStore.loadRecs(ctx)
        if (recs.isEmpty()) {
            box.addView(card { c ->
                c.addView(cardTitle("📜 推荐历史（空）"))
                c.addView(hint("暂无台账。每次「一键更新持仓股」产出可执行建议（买入/卖出/止盈/止损/峰顶）自动落库。"))
            })
            return
        }
        box.addView(card { c ->
            val buys = recs.count { it.action == "买入" }
            val sells = recs.size - buys
            c.addView(cardTitle("📜 推荐历史（${recs.size} 条 · 买入 $buys / 卖出类 $sells）"))
            recs.asReversed().take(30).forEach { r ->
                val actColor = when (r.action) {
                    "买入" -> RED; "卖出" -> GREEN
                    "止盈卖出", "峰顶卖出" -> TEAL; "止损卖出" -> GRAY
                    else -> GRAY
                }
                c.addView(TextView(ctx).apply {
                    text = "${r.updated}  ${r.action}  ${r.name.ifBlank { r.code }}(${r.code}) @${fmt2(r.price)}" +
                        (r.erPct?.let { "  ER ${Math.round(it)}" } ?: "")
                    textSize = 11.5f; setTextColor(actColor); setPadding(0, dp(3), 0, 0)
                })
                if (r.reason.isNotBlank()) c.addView(TextView(ctx).apply {
                    text = "   ${r.reason}"; textSize = 10f; setTextColor(GRAY)
                })
            }
            if (recs.size > 30) c.addView(hint("仅展示最近 30 条，共 ${recs.size} 条（滚动保留 500）。"))
            c.addView(hint("口径：可执行建议自动落库；收益跟踪（T+1/T+5）随后续批次补齐。"))
        })
    }

    /** 预警结果 · 单只持仓股小卡片（与「📋 持仓池」poolRow 同构：头部名称+建议，下方指标行） */
    private fun alertStockCard(r: SsaAlertStore.OverlayRow): View {
        val ctx = requireContext()
        val advColor = when (r.advice) {
            "买入" -> RED; "关注" -> TEAL; "卖出" -> GREEN
            "持仓" -> BLUE; else -> GRAY
        }
        val v = card { c ->
            val row1 = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            row1.addView(TextView(ctx).apply {
                text = "${r.name.ifBlank { r.code }} (${r.code})"
                textSize = 15f; setTextColor(Color.parseColor("#222222"))
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row1.addView(TextView(ctx).apply {
                text = "▶ ${r.advice}"; textSize = 15f; setTextColor(advColor)
                setTypeface(typeface, Typeface.BOLD); setPadding(dp(8), 0, 0, 0)
            })
            c.addView(row1)
            c.addView(TextView(ctx).apply {
                text = "现价 ${if (r.price > 0) fmt2(r.price) else "—"} · 涨跌 ${pct(r.changePct)}"
                textSize = 13f
                setTextColor(if (r.changePct >= 0) RED else GREEN)
                setPadding(0, dp(4), 0, 0)
            })
            c.addView(TextView(ctx).apply {
                val stage = when (r.score) {
                    100 -> "BBI12 买入拐点"; 80 -> "BBI12 拐点(ER闸未过)";
                    70 -> "Δ5ER 金叉预警"; 60 -> "BBI12 持仓"; else -> "BBI12 空仓观望"
                }
                text = "$stage · 更新于 ${r.updated.ifBlank { "—" }}"
                textSize = 11f; setTextColor(GRAY); setPadding(0, dp(2), 0, 0)
            })
            val (erTxt, erColor) = erChip(r.erPct)
            c.addView(TextView(ctx).apply {
                text = "ER分位 $erTxt（跨票横截面 · ≥70 标 ↑）· 点卡片看图"
                textSize = 11f; setTextColor(erColor); setPadding(0, dp(2), 0, 0)
            })
            if (r.reason.isNotBlank()) c.addView(TextView(ctx).apply {
                text = "依据：${r.reason}"
                textSize = 10.5f; setTextColor(Color.parseColor("#555555")); setPadding(0, dp(2), 0, 0)
            })
        }
        // 2026-10-20：点击卡片 → BBI12 拐点图弹窗（K线+BBI+MA(BBI,12)+买卖点+SSA周期分量副图）
        v.setOnClickListener { showErPivotChart(r.name.ifBlank { r.code }, r.code) }
        return v
    }

    // ═══════════════════════ ER分位（PC 策略推送候选 + 持仓池）═══════════════════════

    /** 6 位代码归一：去掉 sh/sz/bj 前缀 */
    private fun code6(raw: String): String =
        if (raw.length > 6 && (raw.startsWith("sh") || raw.startsWith("sz") || raw.startsWith("bj")))
            raw.substring(2) else raw

    /** 6 位代码 → 带市场前缀（6/5/9 → sh；其余 → sz，与 IntradayKlineFetcher 同规则） */
    private fun secidOf(c6: String): String = when {
        c6.startsWith("sh") || c6.startsWith("sz") || c6.startsWith("bj") -> c6
        c6.startsWith("6") || c6.startsWith("5") || c6.startsWith("9") -> "sh$c6"
        else -> "sz$c6"
    }

    /** ER分位文案 + 颜色（取自当前 erMap）：≥70 红（↑ 回测推荐闸门）、≥40 青、否则灰；缺表显示「—」 */
    private fun erPctChip(code: String): Pair<String, Int> = erChip(erMap[code6(code)])

    /** ER分位值 → 文案 + 颜色（与 [erPctChip] 同口径；供预警结果卡片用行内 erPct） */
    private fun erChip(p: Double?): Pair<String, Int> {
        if (p == null || p != p) return "—" to GRAY
        val t = "%d%s".format(Math.round(p).toInt(), if (p >= 70) "↑" else "")
        return t to (if (p >= 70) RED else if (p >= 40) TEAL else GRAY)
    }

    /** 拉取 PC 推送候选 JSON：联网中继优先，兜底本地 pc_candidates.json 缓存 */
    private suspend fun fetchPcCandidatesJson(): String? {
        val ctx = requireContext()
        runCatching { PcBridgeClient.init(ctx) }
        val s = runCatching { PcBridgeClient.fetchCandidates() }.getOrNull()
        if (!s.isNullOrBlank()) return s
        val f = java.io.File(ctx.filesDir, "pc_candidates.json")
        return if (f.exists()) runCatching { f.readText() }.getOrNull() else null
    }

    /** 拉取 PC 推送 → 解析全池 ER 分位表 → 渲染「候选票 + 持仓票」两分区；
     *  PC 推送不可用 / 缺 er_pct_map 时，走 **App 端本机备选**（[SsaErLocal]，候选票 ∪ 持仓票 池内横截面）继续分析。 */
    private fun renderErTab() {
        erBox.removeAllViews()
        erUpdTv.text = "⏳ 拉取 PC 策略推送…"
        erUpdBtn.isEnabled = false
        lifecycleScope.launch {
            try {
                val ctx = requireContext()
                val json = withContext(Dispatchers.IO) { fetchPcCandidatesJson() }
                val obj = json?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { withContext(Dispatchers.Default) { JSONObject(it) } }.getOrNull() }
                val cands = parseCands(obj)
                val members = SsaT0PoolStore.load(ctx)
                erMap = parseErMap(obj ?: JSONObject())
                erSourceLocal = false
                // PC 表为空（推送不可达 / 旧版无 er_pct_map）→ 本机备选：
                // 每日荐股核心池 + Room 日线 W=60（与 PC 同池同口径；本地读库不联网）
                if (erMap.isEmpty()) {
                    erUpdTv.text = "🔧 PC 推送不可用 → 本机备选：每日荐股池 · 日线 ER 分位…"
                    erMap = withContext(Dispatchers.IO) { SsaErLocal.corePoolPercentiles(ctx) }
                    erSourceLocal = erMap.isNotEmpty()
                }
                erPoolNote = if (erSourceLocal) "每日荐股池 ${erMap.size} 只（本机算·日线）" else {
                    val n = obj?.optInt("er_pct_pool_n", erMap.size) ?: erMap.size
                    if (obj?.optString("er_pct_pool") == "daily_reco") "每日荐股池 $n 只" else "全池 $n 只"
                }
                // 2026-10-18 用户方案A：持仓票并入 ER 横截面 —— 表里缺失的持仓票用本机日线补算，
                // universe = 当前表口径池 ∪ 全部持仓票（分位在扩展池内算，池外持仓票永远有值）
                erBackfillNote = ""
                val holdCodes = members.map { code6(it.code) }.toSet()
                val missingHolds = holdCodes.filter { it !in erMap }
                if (missingHolds.isNotEmpty()) {
                    erUpdTv.text = "🔧 ${missingHolds.size} 只持仓票不在 ER 池内 → 本机日线并入补算…"
                    val ext = withContext(Dispatchers.IO) {
                        runCatching { SsaErLocal.percentilesFor(ctx, erMap.keys + missingHolds) }
                            .getOrDefault(emptyMap())
                    }
                    var n = 0
                    missingHolds.forEach { c6 -> ext[c6]?.let { erMap = erMap + (c6 to it); n++ } }
                    if (n > 0) erBackfillNote = " · 持仓 $n 只并入补算"
                }
                // 2026-10-19 用户需求：🚀 ER×BBI12 上行 · Top20（本机 Room 日线，与 PC 筛选脚本同口径）
                erUpdTv.text = "⏳ 计算 ER×BBI12 上行榜…"
                val ups = runCatching { SsaErLocal.upscreen(ctx, 20) }
                    .onFailure { Log.w(TAG_BATCH, "upscreen 失败", it) }
                    .getOrNull()
                // 阶段标签（2026-10-19 用户需求）：池内来自 upscreen.stages；池外持仓票本机补算
                val stageMap = HashMap(ups?.stages ?: emptyMap())
                var stageBackfillN = 0
                members.map { code6(it.code) }.distinct()
                    .filter { it !in stageMap && erMap.containsKey(it) }
                    .forEach { c6 ->
                        SsaErLocal.stageFor(ctx, c6, erMap[c6]!!)?.let { stageMap[c6] = it; stageBackfillN++ }
                    }
                Log.i(TAG_BATCH, "阶段标签 ${stageMap.size} 只（池外补算 $stageBackfillN）")
                // 2026-10-19 用户口径：Top10 改为 ER 分位最低的 10 只 —— 拉行情也按最低分取
                val top10 = erMap.entries.sortedBy { it.value }.take(10).map { it.key }
                val quoteCodes = (top10 + (ups?.rows?.map { it.c6 } ?: emptyList())).distinct()
                val quotes = if (quoteCodes.isEmpty()) emptyMap<String, StockRealtime>()
                else withContext(Dispatchers.IO) {
                    runCatching {
                        StockDataFacade.getInstance(ctx).getRealtimeQuotes(quoteCodes.map { secidOf(it) })
                    }.getOrDefault(emptyMap())
                }
                renderErContent(obj, cands, members, quotes, ups, stageMap)
                erUpdTv.text = when {
                    erSourceLocal -> "🔧 本机备选：每日荐股池 ER 分位 ${erMap.size} 只（日线 W=60 · 本机算）$erBackfillNote"
                    obj != null -> "✅ PC $erPoolNote$erBackfillNote · ER 分位 ${erMap.size} 只 · " +
                        "asof ${obj.optString("asof", "—")} · 生成 ${obj.optString("generated_at", "—")}"
                    else -> "⚠️ 未取到 PC 策略推送，且本机备选池样本不足（<2 只有效）"
                }
            } catch (e: Exception) {
                erUpdTv.text = "⚠️ 拉取失败：${e.message}"
            } finally {
                erUpdBtn.isEnabled = true
            }
        }
    }

    /** 从 PC 推送 JSON 解析候选票（四周期 groups 去重）；obj 为 null 时返回空 */
    private fun parseCands(o: JSONObject?): List<JSONObject> {
        val groups = o?.optJSONObject("groups") ?: return emptyList()
        val seen = LinkedHashSet<String>()
        val cands = ArrayList<JSONObject>()
        groups.keys().forEach { per ->
            val arr = groups.optJSONArray(per) ?: return@forEach
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val secid = it.optString("secid")
                if (secid.isEmpty() || !seen.add(code6(secid))) continue
                it.put("_period", per)
                cands.add(it)
            }
        }
        return cands
    }

    private fun parseErMap(o: JSONObject): Map<String, Double> {
        val m = o.optJSONObject("er_pct_map") ?: return emptyMap()
        val out = HashMap<String, Double>(m.length())
        m.keys().forEach { k ->
            val v = m.optDouble(k, Double.NaN)
            if (v == v) out[k] = v
        }
        return out
    }

    private fun renderErContent(
        o: JSONObject?,
        cands: List<JSONObject>,
        members: List<SsaT0PoolStore.PoolMember>,
        quotes: Map<String, StockRealtime> = emptyMap(),
        ups: SsaErLocal.UpScreen? = null,
        stages: Map<String, String> = emptyMap()
    ) {
        erBox.removeAllViews()
        if (erMap.isEmpty()) {
            erBox.addView(hint(
                if (o == null) "PC 推送不可达；本机备选需「每日荐股池」日线数据（先在【📈 每日荐股】页跑过全量构建），池内有效样本 <2 只。"
                else "PC 推送 JSON 里没有 er_pct_map（PC 守护脚本版本较旧）；本机备选池内有效样本 <2 只。"))
        }
        // 🚀 ER×BBI12 上行 · Top 20（2026-10-19 用户需求：高 ER 且 BBI12 谷底拐点之后仍上行，排除下行票）
        if (ups != null) {
            erBox.addView(card { c ->
                c.addView(cardTitle("🚀 ER×BBI12 上行 · Top ${ups.rows.size}" +
                    "（上行 ${ups.upN}/${ups.poolN} 只）"))
                c.addView(hint("口径：每日荐股池内 ER 分位（日线 W=60 中位秩）× BBI12（SMA(BBI,12)）" +
                    "末个已确认拐点=谷底 且 末根仍上升；峰顶后/BBI12 走低的票已排除，只留上行趋势。" +
                    "与 PC 筛选脚本 _er_bbi12_screen.py 同口径。数据截至 ${ups.asof}（本机 Room 日线）。" +
                    "点击卡片看该票 BBI12 拐点图。"))
                c.addView(hint("阶段口径：市况按池内 ER 分位（≥70 趋势 / ≤30 震荡 / 其间过渡，与回测 P70 闸门同口径），" +
                    "进度按近 5 根 ΔER（>±0.03 判抬升/回落）。趋势：抬升=前段、持续=中段、回落=末段（防衰竭）；" +
                    "震荡：抬升=末段（酝酿突破）、回落=前段（刚入盘整）。ER 评分高 = 趋势段。"))
            })
            if (ups.rows.isEmpty()) {
                erBox.addView(hint("本机日线不足（先在【📈 每日荐股】页跑全量构建）或当前池内无符合条件的票。"))
            }
            ups.rows.forEachIndexed { i, r -> erBox.addView(erUpCard(i, r, quotes[secidOf(r.c6)])) }
        }
        // ⓪ 🎯 ER Bottom10 · 池内 ER 分位最低（2026-10-19 用户口径：改为最低分 10 只）
        val top10 = erMap.entries.sortedBy { it.value }.take(10)
        erBox.addView(card { c ->
            c.addView(cardTitle("🎯 ER Bottom10 · 池内 ER 分位最低（${top10.size} 只）"))
            c.addView(hint("口径：池内按 ER 分位升序取最低 10 只 —— 低 ER = 价格路径效率低、波动主导（" +
                (if (erSourceLocal) "🔧 本机备选：$erPoolNote，可能不足 10 只" else "PC $erPoolNote") +
                "）。观察榜，不直接下单。**点击卡片**看该票 BBI12 拐点图。"))
        })
        if (top10.isEmpty()) erBox.addView(hint("暂无可用 ER 分位（拉取策略推送后可见）。"))
        top10.forEach { (c6, p) -> erBox.addView(erTopCard(c6, p, quotes[secidOf(c6)], stages[c6])) }

        // ① 策略推送候选（按 ER 分位降序）
        val sorted = cands.sortedWith(
            compareByDescending<JSONObject> { erMap[code6(it.optString("secid"))] ?: -1.0 })

        erBox.addView(card { c ->
            c.addView(cardTitle("📊 策略推送 · 候选票（${sorted.size} 只）"))
            c.addView(hint("口径：跨票横截面 Kaufman 效率比 ER 分位（" +
                (if (erSourceLocal) "🔧 本机备选 日线 W=60 · 横截面=每日荐股池" else "PC " + erPoolNote) +
                "，0~100）；≥70 标 ↑（回测推荐闸门）；按 ER 分位降序。仅提示不构成投资建议。" +
                "**点击卡片**看该票 BBI12 拐点图。"))
        })
        if (sorted.isEmpty()) erBox.addView(hint("本次推送无候选票（或 PC 推送不可达）。"))
        sorted.forEach { cand -> erBox.addView(erCandCard(cand, stages[code6(cand.optString("secid"))])) }

        // ② 持仓票（持仓池 + ER 分位；有预警缓存则带评分/建议）
        val ctx = requireContext()
        val rows = SsaAlertStore.loadRows(ctx).associateBy { code6(it.code) }
        erBox.addView(card { c ->
            c.addView(cardTitle("🧾 持仓票（${members.size} 只）"))
            c.addView(hint("持仓池全部股票 + ER 分位（池外票已并入横截面本机补算）；" +
                "若已跑过预警「一键更新」，附带末次评分与建议。**点击卡片**看该票 BBI12 拐点图。"))
        })
        if (members.isEmpty()) erBox.addView(hint("持仓池为空：到【📋 持仓池】页添加持仓票。"))
        members.forEach { m -> erBox.addView(erHoldCard(m.code, m.name, rows[code6(m.code)], stages[code6(m.code)])) }
    }

    /** 🚀 ER×BBI12 上行榜 · 单只卡片（样式同 erTopCard + BBI12 趋势行；点击看拐点图） */
    private fun erUpCard(rank: Int, r: SsaErLocal.UpRow, q: StockRealtime?): View {
        val ctx = requireContext()
        val (erTxt, erColor) = erChip(r.pct)
        val name = q?.name?.ifBlank { r.c6 } ?: r.c6
        return card { c ->
            val row1 = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            row1.addView(TextView(ctx).apply {
                text = "$rank. $name (${r.c6})"
                textSize = 15f; setTextColor(Color.parseColor("#222222"))
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row1.addView(TextView(ctx).apply {
                text = "ER $erTxt"; textSize = 15f; setTextColor(erColor)
                setTypeface(typeface, Typeface.BOLD); setPadding(dp(8), 0, 0, 0)
            })
            c.addView(row1)
            c.addView(TextView(ctx).apply {
                text = (if (r.stage.isNotEmpty()) "${r.stage} · " else "") + "谷底 ${r.troughDate}" +
                    (if (r.gain.isFinite()) " · 较谷底 %+.2f%%".format(r.gain) else "")
                textSize = 12f; setTextColor(GRAY); setPadding(0, dp(3), 0, 0)
            })
            // 止损线 = 谷底确认价；跌破即触发止损（回测最优框架口径）
            if (r.close > 0 && r.gain.isFinite()) {
                val stopLine = r.close / (1 + r.gain / 100)
                val distPct = r.gain
                val danger = distPct < 3.0
                c.addView(TextView(ctx).apply {
                    text = "止损线 ${fmt2(stopLine)} · 现价距止损 ${"%+.2f".format(distPct)}%"
                    textSize = 12f
                    setTextColor(if (danger) Color.parseColor("#C62828") else GRAY)
                    if (danger) setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(3), 0, 0)
                })
            }
            if (q != null && q.price > 0) {
                c.addView(TextView(ctx).apply {
                    text = "现价 ${fmt2(q.price)} · 涨跌 ${pct(q.changePercent)}"
                    textSize = 12f
                    setTextColor(if (q.changePercent >= 0) RED else GREEN)
                    setPadding(0, dp(2), 0, 0)
                })
            }
            c.setOnClickListener { showErPivotChart(name, r.c6) }
        }
    }

    /** ER Bottom10 观察榜 · 单只卡片（名称/现价来自实时行情，缺则仅显示代码） */
    private fun erTopCard(c6: String, pct: Double, q: StockRealtime?, stage: String? = null): View {
        val ctx = requireContext()
        val (erTxt, erColor) = erChip(pct)
        return card { c ->
            val row1 = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            row1.addView(TextView(ctx).apply {
                text = "${q?.name?.ifBlank { c6 } ?: c6} ($c6)"
                textSize = 15f; setTextColor(Color.parseColor("#222222"))
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row1.addView(TextView(ctx).apply {
                text = "ER分位 $erTxt"; textSize = 15f; setTextColor(erColor)
                setTypeface(typeface, Typeface.BOLD); setPadding(dp(8), 0, 0, 0)
            })
            c.addView(row1)
            if (!stage.isNullOrBlank()) {
                c.addView(TextView(ctx).apply {
                    text = "阶段 $stage"
                    textSize = 12f; setTextColor(GRAY); setPadding(0, dp(3), 0, 0)
                })
            }
            if (q != null && q.price > 0) {
                c.addView(TextView(ctx).apply {
                    text = "现价 ${fmt2(q.price)} · 涨跌 ${pct(q.changePercent)}"
                    textSize = 12f
                    setTextColor(if (q.changePercent >= 0) RED else GREEN)
                    setPadding(0, dp(3), 0, 0)
                })
            }
            c.setOnClickListener { showErPivotChart(q?.name?.ifBlank { c6 } ?: c6, c6) }
        }
    }

    private fun erCandCard(o: JSONObject, stage: String? = null): View {
        val ctx = requireContext()
        val code = code6(o.optString("secid"))
        val name = o.optString("name").ifBlank { code }
        val (erTxt, erColor) = erPctChip(code)
        return card { c ->
            val row1 = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            row1.addView(TextView(ctx).apply {
                text = "$name ($code)"
                textSize = 15f; setTextColor(Color.parseColor("#222222"))
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row1.addView(TextView(ctx).apply {
                text = "ER分位 $erTxt"; textSize = 15f; setTextColor(erColor)
                setTypeface(typeface, Typeface.BOLD); setPadding(dp(8), 0, 0, 0)
            })
            c.addView(row1)
            if (!stage.isNullOrBlank()) {
                c.addView(TextView(ctx).apply {
                    text = "阶段 $stage"
                    textSize = 12f; setTextColor(GRAY); setPadding(0, dp(3), 0, 0)
                })
            }
            val ind = o.optString("industry").ifBlank { "—" }
            val per = o.optString("_period").ifBlank { "—" }
            val ratio = o.optDouble("ratio", 0.0)
            val pass = o.optInt("pass", 0); val total = o.optInt("total", 0)
            c.addView(TextView(ctx).apply {
                text = "$per · $ind · 强度 ${"%.1f%%".format(ratio * 100)}" +
                    (if (o.optBoolean("limit_up")) " · 涨停" else "")
                textSize = 12f; setTextColor(Color.parseColor("#555555")); setPadding(0, dp(3), 0, 0)
            })
            c.addView(TextView(ctx).apply {
                text = "命中 $pass/$total 项"
                textSize = 11f; setTextColor(GRAY); setPadding(0, dp(2), 0, 0)
            })
            c.setOnClickListener { showErPivotChart(name, code) }
        }
    }

    private fun erHoldCard(code: String, name: String, r: SsaAlertStore.OverlayRow?,
                           stage: String? = null): View {
        val ctx = requireContext()
        val c6 = code6(code)
        val (erTxt, erColor) = erPctChip(c6)
        return card { c ->
            val row1 = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            row1.addView(TextView(ctx).apply {
                text = "${name.ifBlank { c6 }} ($c6)"
                textSize = 15f; setTextColor(Color.parseColor("#222222"))
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row1.addView(TextView(ctx).apply {
                text = "ER分位 $erTxt"; textSize = 15f; setTextColor(erColor)
                setTypeface(typeface, Typeface.BOLD); setPadding(dp(8), 0, 0, 0)
            })
            c.addView(row1)
            if (!stage.isNullOrBlank()) {
                c.addView(TextView(ctx).apply {
                    text = "阶段 $stage"
                    textSize = 12f; setTextColor(GRAY); setPadding(0, dp(3), 0, 0)
                })
            }
            if (r != null) {
                c.addView(TextView(ctx).apply {
                    text = "▶ ${r.advice} · 评分 ${r.score}/100 · 买入窗${if (r.windowOpen) "开" else "关"} · " +
                        "现价 ${if (r.price > 0) fmt2(r.price) else "—"} · 更新于 ${r.updated.ifBlank { "—" }}"
                    textSize = 11.5f
                    setTextColor(
                        when (r.advice) {
                            "止盈卖出", "峰顶卖出", "过滤观望" -> Color.parseColor("#C62828")
                            "金叉预警" -> Color.parseColor("#E65100")
                            "买入" -> Color.parseColor("#1565C0")
                            else -> Color.parseColor("#555555")
                        }
                    )
                    if (r.advice == "止盈卖出" || r.advice == "峰顶卖出" || r.advice == "金叉预警")
                        setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(3), 0, 0)
                })
            } else {
                c.addView(TextView(ctx).apply {
                    text = "尚未跑过预警「一键更新」→ 无评分/建议（可到【⚠ 预警】页更新）"
                    textSize = 11f; setTextColor(GRAY); setPadding(0, dp(3), 0, 0)
                })
            }
            c.setOnClickListener { showErPivotChart(name.ifBlank { c6 }, c6) }
        }
    }

    // ── ER 票卡点击 → BBI12 拐点图（2026-10-18 用户需求）──────────────────

    /** 拉取 1H K线 → 算 BBI12（MA(BBI,12)）拐点 → 弹窗出图；数据不足/失败给 status 提示。 */
    private fun showErPivotChart(name: String, c6: String) {
        val ctx = requireContext()
        lifecycleScope.launch {
            status("⏳ [$c6] 拉取 1H K线…")
            val ent = withContext(Dispatchers.IO) {
                runCatching { IntradayKlineFetcher(ctx).fetchHourlyHistory(c6, 200) }.getOrDefault(emptyList())
            }
            val all = ent.filter { it.close > 0 && it.high > 0 && it.low > 0 }
                .sortedBy { it.datetime }
                .map {
                    SsaAlertEngine.Bar(it.date, it.datetime.substringAfter(' ', ""),
                        it.open, it.high, it.low, it.close, it.volume)
                }
            if (all.size < 160) { status("⚠️ [$c6] 1H K线仅 ${all.size} 根（需≥160），网络数据不足"); return@launch }
            val calc = all.takeLast(200)
            status("⏳ [$c6] 计算 BBI12 拐点…")
            val bundle = withContext(Dispatchers.Default) {
                runCatching {
                    SsaBbiPivotEngine.runMix(calc, window = 120,
                        modes = listOf(SsaBbiPivotEngine.MixMode.PURE_BBI12))
                }.getOrNull()
            }
            if (bundle == null) { status("⚠️ [$c6] BBI12 拐点计算失败"); return@launch }
            showBbiPivotDialog(name, c6, calc, bundle)
            status("✅ [$c6] BBI12 拐点图")
        }
    }

    /** BBI12 拐点图弹窗：主图 = K线 + BBI + MA(BBI,12) 曲线 + 拐点圆点（红=谷底买 / 绿=峰顶卖）。 */
    private fun showBbiPivotDialog(
        name: String, code: String,
        bars: List<SsaAlertEngine.Bar>,
        b: SsaBbiPivotEngine.MixBundle
    ) {
        val ctx = requireContext()
        val n = bars.size
        val from = (n - 120).coerceAtLeast(0)
        val dateArr = JSONArray().apply {
            for (i in from until n) put("${bars[i].date.takeLast(5)} ${bars[i].time}")
        }
        val klineArr = JSONArray().apply {
            for (i in from until n) {
                val bar = bars[i]
                put(JSONArray().apply { put(r3(bar.open)); put(r3(bar.close)); put(r3(bar.low)); put(r3(bar.high)) })
            }
        }
        val buys = b.bbiPivots.filter { it.buy }
        val sells = b.bbiPivots.filter { !it.buy }
        val hDp = stratChartHDp(false)
        val hSubDp = stratChartHDp(true)
        val data = JSONObject().apply {
            put("dates", dateArr); put("kline", klineArr)
            put("charts", JSONArray().put(JSONObject().apply {
                put("title", "BBI12 拐点 · $name ($code)")
                put("sub", "谷底拐点（买）${buys.size} 个 · 峰顶拐点（卖）${sells.size} 个 · 区间末 ${n - from} 根 1H")
                put("h", hSubDp)
                put("series", JSONArray().apply {
                    put(lineJson("BBI", "#fb8c00", 1.2, 0, b.bbi, from))
                    put(lineJson("MA(BBI,12)", "#1e88e5", 1.4, 0, b.bbiMa12, from))
                    put(dotJson(buys, "#e53935", b.bbiMa12, from, "谷底拐点·买"))
                    put(dotJson(sells, "#2e7d32", b.bbiMa12, from, "峰顶拐点·卖"))
                })
                // 2026-10-20：副图 = SSA 1H 周期分量（价格尺度、绕 0 轴振荡）+ 其峰谷拐点（模板键名 subs）
                put("subs", JSONArray().put(JSONObject().apply {
                    put("name", "SSA周期"); put("zero", true)
                    put("series", JSONArray().apply {
                        put(lineJson("SSA周期分量", "#00897b", 1.4, 1, b.cycle, from))
                        put(subScatterJson(b.cyclePivots.filter { it.buy }, "#e53935", 0, b.cycle, from))
                        put(subScatterJson(b.cyclePivots.filter { !it.buy }, "#2e7d32", 180, b.cycle, from))
                    })
                }))
            }))
        }
        val tpl = runCatching {
            ctx.assets.open("pivot_chart.html").bufferedReader().use { it.readText() }
        }.getOrNull()
        if (tpl == null) { status("⚠️ 图表模板 pivot_chart.html 缺失"); return }
        val wv = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportZoom(false); settings.builtInZoomControls = false; settings.displayZoomControls = false
            setBackgroundColor(Color.WHITE)
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(hSubDp))
        }
        // 双指时禁止外层 ScrollView 抢手势（单指仍可滚动）
        wv.setOnTouchListener { v, e ->
            v.parent?.requestDisallowInterceptTouchEvent(e.pointerCount >= 2)
            false
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript("document.body.scrollHeight") { raw ->
                    val cssH = raw?.trim('"', ' ')?.toDoubleOrNull() ?: return@evaluateJavascript
                    if (cssH <= 0.0) return@evaluateJavascript
                    val lp = view.layoutParams
                    val exact = dp(cssH.toInt() + 2)
                    if (lp != null && lp.height != exact) { lp.height = exact; view.layoutParams = lp }
                }
            }
        }
        wv.loadDataWithBaseURL("file:///android_asset/", tpl.replace("__DATA__", data.toString()),
            "text/html", "UTF-8", null)
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(cardTitle("📈 $name ($code) · BBI12 拐点图"))
            addView(legend("橙线=BBI · 蓝线=MA(BBI,12)（BBI12 拐点线）；圆点=MA(BBI,12) 拐点：" +
                "红=谷底（确认买）· 绿=峰顶（确认卖），信号根=确认根（天然滞后 1 根），触发价=收盘价。" +
                "副图 = SSA 1H 周期分量（青线，绕 0 轴振荡）及其峰谷拐点（▲红=周期谷底 ▼绿=周期峰顶）。" +
                "展示口径与预警页一致：60分钟K取末200根计算、末120根展示。双指捏合缩放、拖动滑块平移。"))
            addView(wv)
        }
        val scroll = ScrollView(ctx).apply { addView(content) }
        val dlg = AlertDialog.Builder(ctx)
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .create()
        dlg.setOnDismissListener { wv.destroy() }
        dlg.show()
        dlg.window?.setLayout(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.82).toInt())
    }

    /** 做T卡片点击：拉 1H/日 K → SsaT0Engine 重跑（池内锁定参数）→ T0 点位图弹窗 */
    private fun showT0Chart(name: String, c6: String) {
        val ctx = requireContext()
        lifecycleScope.launch {
            status("⏳ [$c6] 拉取 1H/日 K线…")
            val p = SsaT0PoolStore.load(ctx).find { it.code == c6 }?.params() ?: SsaT0Engine.Params()
            val days = withContext(Dispatchers.IO) { loadDaily(c6) }
            if (days == null) { status("⚠️ [$c6] 日K缺失，无法画图"); return@launch }
            val hBars = withContext(Dispatchers.IO) {
                runCatching {
                    IntradayKlineFetcher(ctx).fetchHourlyHistory(c6, POOL_HOURLY)
                        .filter { it.close > 0 && it.high > 0 && it.low > 0 }.sortedBy { it.datetime }
                        .map { SsaT0Engine.Bar(it.date, it.datetime.substringAfter(' ', ""),
                            it.open, it.high, it.low, it.close) }
                }.getOrDefault(emptyList())
            }
            if (hBars.size < p.hour.windowM + 60) { status("⚠️ [$c6] 1H K线仅 ${hBars.size} 根，数据不足"); return@launch }
            status("⏳ [$c6] 计算 T0 三态机…")
            val r = withContext(Dispatchers.Default) { runCatching { SsaT0Engine.run(hBars, days, p) } }
                .getOrElse { status("⚠️ [$c6] T0 计算失败：${it.message}"); return@launch }
            showT0Dialog(name, c6, r)
            status("✅ [$c6] T0 点位图")
        }
    }

    /** T0 点位图弹窗：主图 = K线 + 中枢/上下极限带/正常带 + T买卖点▲▼ + T单持仓区间黄底；副图 = SSA 1H 周期分量。 */
    private fun showT0Dialog(name: String, code: String, r: SsaT0Engine.Result) {
        val ctx = requireContext()
        val bars = r.h.bars
        val n = bars.size
        val from = (n - 120).coerceAtLeast(0)
        val hDp = stratChartHDp(true)
        val dateArr = JSONArray().apply {
            for (i in from until n) put("${bars[i].date.takeLast(5)} ${bars[i].time}")
        }
        val klineArr = JSONArray().apply {
            for (i in from until n) {
                val b = bars[i]
                put(JSONArray().apply { put(r3(b.open)); put(r3(b.close)); put(r3(b.low)); put(r3(b.high)) })
            }
        }
        val buys = JSONArray().apply {
            r.markers.filter { it.buy && it.index >= from }.forEach { mk ->
                put(JSONArray().apply { put(mk.index - from); put(r3(mk.price)) })
            }
        }
        val sells = JSONArray().apply {
            r.markers.filter { !it.buy && it.index >= from }.forEach { mk ->
                put(JSONArray().apply { put(mk.index - from); put(r3(mk.price)) })
            }
        }
        val areas = JSONArray().apply {
            r.trades.forEach { t ->
                put(JSONArray().apply {
                    put(JSONObject().apply { put("xAxis", t.openIndex - from) })
                    put(JSONObject().apply { put("xAxis", t.closeIndex - from) })
                })
            }
        }
        val data = JSONObject().apply {
            put("dates", dateArr); put("kline", klineArr)
            put("charts", JSONArray().put(JSONObject().apply {
                put("title", "T0 三态机点位 · $name ($code)")
                put("sub", "T单 ${r.trades.size} 笔 · 胜率 ${"%.0f".format(r.full.winRatePct)}% · 全期 ${"%.1f".format(r.full.totalReturnPct)}% · 展示末 ${n - from} 根 1H")
                put("h", hDp)
                put("series", JSONArray().apply {
                    put(lineJson("中枢mid", "#8e24aa", 1.3, 0, r.h.mid, from))
                    put(lineJson("上极限eUp", "#e53935", 1.1, 0, r.h.eUp, from))
                    put(lineJson("下极限eDn", "#2e7d32", 1.1, 0, r.h.eDn, from))
                    put(lineJson("正常上nUp", "#ef9a9a", 0.9, 0, r.h.nUp, from))
                    put(lineJson("正常下nDn", "#a5d6a7", 0.9, 0, r.h.nDn, from))
                    put(JSONObject().apply {
                        put("n", "买T"); put("t", "scatter"); put("ax", 0); put("c", "#e53935")
                        put("sym", "triangle"); put("rot", 0); put("sz", 9); put("d", buys)
                    })
                    put(JSONObject().apply {
                        put("n", "卖T"); put("t", "scatter"); put("ax", 0); put("c", "#2e7d32")
                        put("sym", "triangle"); put("rot", 180); put("sz", 9); put("d", sells)
                    })
                })
                // 2026-10-20：副图 = SSA 1H 周期分量（周期 >0=UP / <0=FLAT 的三态判定依据）（模板键名 subs）
                put("subs", JSONArray().put(JSONObject().apply {
                    put("name", "SSA周期"); put("zero", true)
                    put("series", JSONArray().apply {
                        put(lineJson("SSA周期分量", "#00897b", 1.4, 1, r.h.cycle, from))
                    })
                }))
                put("areas", areas)
            }))
        }
        val tpl = runCatching {
            ctx.assets.open("pivot_chart.html").bufferedReader().use { it.readText() }
        }.getOrNull()
        if (tpl == null) { status("⚠️ 图表模板 pivot_chart.html 缺失"); return }
        val wv = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportZoom(false); settings.builtInZoomControls = false; settings.displayZoomControls = false
            setBackgroundColor(Color.WHITE)
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(hDp))
        }
        wv.setOnTouchListener { v, e ->
            v.parent?.requestDisallowInterceptTouchEvent(e.pointerCount >= 2)
            false
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript("document.body.scrollHeight") { raw ->
                    val cssH = raw?.trim('"', ' ')?.toDoubleOrNull() ?: return@evaluateJavascript
                    if (cssH <= 0.0) return@evaluateJavascript
                    val lp = view.layoutParams
                    val exact = dp(cssH.toInt() + 2)
                    if (lp != null && lp.height != exact) { lp.height = exact; view.layoutParams = lp }
                }
            }
        }
        wv.loadDataWithBaseURL("file:///android_asset/", tpl.replace("__DATA__", data.toString()),
            "text/html", "UTF-8", null)
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(cardTitle("📈 $name ($code) · T0 点位图"))
            addView(legend("紫线=中枢mid · 红/绿=上/下极限带(eUp/eDn) · 浅红/浅绿=正常带(nUp/nDn)；" +
                "▲红=买T/回补 ▼绿=卖T，黄底=T单持仓区间。" +
                "副图 = SSA 1H 周期分量（青线，绕 0 轴振荡）：>0→UP、<0→FLAT，为三态机判定依据。" +
                "展示口径与做T页一致：末 120 根 1H。双指捏合缩放、拖动滑块平移。"))
            addView(wv)
        }
        val scroll = ScrollView(ctx).apply { addView(content) }
        val dlg = AlertDialog.Builder(ctx)
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .create()
        dlg.setOnDismissListener { wv.destroy() }
        dlg.show()
        dlg.window?.setLayout(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.82).toInt())
    }

    /** 拐点圆点标记（scatter·circle，y 落在指定曲线值上：拐点画在 MA(BBI,12) 曲线上） */
    private fun dotJson(sig: List<SsaAlertEngine.Signal>, color: String,
                        line: DoubleArray, from: Int, label: String): JSONObject {
        val d = JSONArray()
        sig.filter { it.index >= from && it.index < line.size }.forEach { s ->
            val v = line[s.index]
            if (v.isNaN()) return@forEach
            d.put(JSONArray().apply { put(s.index - from); put(r3(v)) })
        }
        return JSONObject().apply {
            put("n", label); put("t", "scatter"); put("ax", 0); put("c", color)
            put("sym", "circle"); put("sz", 9); put("d", d)
        }
    }

    private fun renderAlert(name: String, code: String, r: SsaAlertEngine.Result, clear: Boolean = true) {
        if (clear) alertBox.removeAllViews()
        val n = r.bars.size
        val showFrom = (n - 120).coerceAtLeast(0)
        val shown = r.signals.filter { it.index >= showFrom }
        val cbars = r.bars.map { Bar(it.date.replace("-", ""), it.time, it.open, it.high, it.low, it.close) }
        val last = r.bars.last()

        // 概览
        alertBox.addView(card { c ->
            c.addView(cardTitle("⚠ 短线预警 · $name ($code)"))
            c.addView(statGrid(listOf(
                "分析根数" to "${n} 根(展示末 ${n - showFrom})",
                "末根" to "${last.date} ${last.time}",
                "末根收盘" to fmt2(last.close),
                "末根评分" to "${r.lastScore} 分",
                "买入窗" to if (r.buyWindowOpen[n - 1]) "开(可买)" else "关(观望)",
                "信号数" to "买 ${shown.count { it.buy }} / 卖 ${shown.count { !it.buy }}"
            )))
            c.addView(hint("触发价 = 信号根收盘，次日开盘执行；共振 ${shown.count { it.resonance }} 条（同根同向 ≥2 条，非独立确认）。" +
                "买入前提：BB(=BBI) 均线趋势向上 且 BB 在其均线上方（买入窗开启）；窗口关闭时买入信号评分置 0 并灰显。" +
                "评分 = 同根命中的买入指标(B1..B5) 各 20 分。若为交易时段，末根 1H 可能未收盘，信号会随收盘变化。"))
        })

        // 📈 收益率对比（插在信号表之前）
        alertBox.addView(card { c ->
            val pf = r.perf
            val title = TextView(requireContext()).apply {
                text = "📈 收益率对比（区间内信号配对 vs 买入持有）"
                textSize = 13f; setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (pf.excessPct >= 0) RED else GREEN)
                setPadding(0, 0, 0, dp(4))
            }
            c.addView(title)
            c.addView(statGrid(listOf(
                "区间" to "${pf.fromLabel} ~ ${pf.toLabel}",
                "策略收益率" to "%+.2f%%".format(pf.strategyReturnPct),
                "买入持有" to "%+.2f%%".format(pf.buyHoldReturnPct),
                "超额" to "%+.2f%%".format(pf.excessPct),
                "交易笔数" to "${pf.trades} 笔",
                "胜率" to "%.1f%%".format(pf.winRatePct),
                "费用" to if (pf.netOfCost) "已扣费" else "未扣费(毛)"
            )))
            c.addView(hint("口径：区间 = 末 ${n - r.windowFrom} 根 1H（${pf.fromIndex}..${pf.toIndex}）；" +
                "空仓遇「买入窗开启」的买入信号以收盘价建仓，遇卖出信号以收盘价平仓，末根强平；" +
                "买入持有 = 区间末收盘 / 区间首收盘。仅统计买入指标 B1..B5，X1 不参与建仓触发。"))
        })

        // 信号表
        alertBox.addView(card { c ->
            c.addView(cardTitle("📋 买入 / 卖出做T 价格列表（附原因）"))
            if (shown.isEmpty()) { c.addView(hint("区间内无信号")); return@card }
            val widths = listOf(70, 42, 32, 28, 34, 168)
            val hsv = HorizontalScrollView(requireContext()).apply { isHorizontalScrollBarEnabled = false }
            val tb = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            tb.addView(row(listOf("时间", "价格", "方向", "信号", "评分", "原因"), widths, true))
            shown.asReversed().forEach { s -> tb.addView(alertSignalRow(s, widths)) }
            hsv.addView(tb); c.addView(hsv)
            c.addView(legend("底色橙=共振（同根同向≥2条）；灰行= BBI 买入窗关闭（评分 0，仅作提示）；" +
                "方向红=买入 / 绿=卖出；★=WR 动能最强(<25)。"))
        })

        // 主图 + 副图
        val main = buildAlertMain(r, cbars, 240)
        alertBox.addView(card { c ->
            c.addView(cardTitle("① 主图：60分钟K + JAX 济安线（A线 / J线）"))
            c.addView(main)
        })
        val subs = ArrayList<BarLineChartBase<*>>()
        alertBox.addView(card { c ->
            c.addView(cardTitle("② OBV 与 MAOBV（能量潮）"))
            val ch = miniLine(listOf(line(r.obv, "OBV", BLUE, 1.2f), line(r.maobv, "MAOBV", ORANGE, 1.3f)),
                cbars, 116, timeFmt(cbars), true)
            subs.add(ch); c.addView(ch)
        })
        alertBox.addView(card { c ->
            c.addView(cardTitle("③ KDJ(9,3,3)：K(橙) / D(蓝) / J(紫)"))
            val ch = miniLine(listOf(
                line(r.k, "K", ORANGE, 1.1f), line(r.d, "D", BLUE, 1.1f), line(r.j, "J", PURPLE, 1.0f)),
                cbars, 116, timeFmt(cbars), false)
            ch.axisLeft.axisMinimum = -20f; ch.axisLeft.axisMaximum = 140f
            ch.axisLeft.addLimitLine(limitLine(20f, "#66BB6A"))
            ch.axisLeft.addLimitLine(limitLine(80f, "#EF5350"))
            ch.post { ch.invalidate() }
            subs.add(ch); c.addView(ch)
        })
        alertBox.addView(card { c ->
            c.addView(cardTitle("④ WR(14)（国内口径：越大越超卖）"))
            val ch = miniLine(listOf(line(r.wr, "WR", TEAL, 1.2f)), cbars, 100, timeFmt(cbars), false)
            ch.axisLeft.axisMinimum = 0f; ch.axisLeft.axisMaximum = 100f
            ch.axisLeft.addLimitLine(limitLine(65f, "#EF5350"))
            ch.axisLeft.addLimitLine(limitLine(25f, "#66BB6A"))
            ch.post { ch.invalidate() }
            subs.add(ch); c.addView(ch)
        })
        alertBox.addView(card { c ->
            c.addView(cardTitle("⑤ BB(=BBI 多空指标) 与其均线"))
            val ch = miniLine(listOf(line(r.bbi, "BB(BBI)", BLUE, 1.2f), line(r.bbiMa, "BB均线", ORANGE, 1.3f)),
                cbars, 116, timeFmt(cbars), false)
            subs.add(ch); c.addView(ch)
        })
        linkCharts(listOf(main) + subs)
    }

    private fun limitLine(v: Float, color: String) = LimitLine(v, "%.0f".format(v)).apply {
        lineColor = Color.parseColor(color); lineWidth = 1f; textSize = 7f
    }

    private fun alertSignalRow(s: SsaAlertEngine.Signal, widths: List<Int>): LinearLayout {
        val ctx = requireContext()
        val windowClosed = s.buy && !s.windowOpen
        val scoreTxt = when {
            !s.buy -> "—"
            windowClosed -> "0·窗关"
            else -> s.score.toString()
        }
        val cells = listOf(
            "${s.date.substring(5)} ${s.time}", "%.2f".format(s.price),
            if (s.buy) "买入" else "卖出", s.cond + (if (s.strong) "★" else ""), scoreTxt, s.reason
        )
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(
                when {
                    windowClosed -> Color.parseColor("#ECEFF1")
                    s.resonance -> Color.parseColor("#FFF3E0")
                    else -> Color.WHITE
                }
            )
            cells.forEachIndexed { i, txt ->
                addView(TextView(ctx).apply {
                    text = txt; textSize = 9f
                    gravity = Gravity.CENTER_VERTICAL or
                        (if (i == 0 || i == cells.lastIndex) Gravity.START else Gravity.CENTER_HORIZONTAL)
                    setTextColor(when {
                        windowClosed -> Color.parseColor("#9E9E9E")
                        i == 2 -> if (s.buy) RED else GREEN
                        i == 4 -> Color.parseColor("#6A1B9A")
                        s.resonance -> Color.parseColor("#BF360C")
                        else -> Color.parseColor("#333333")
                    })
                    if (s.resonance || i == 2 || i == 4) setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(4), dp(5), dp(4), dp(5))
                    layoutParams = LinearLayout.LayoutParams(dp(widths[i]), LinearLayout.LayoutParams.WRAP_CONTENT)
                })
            }
        }
    }

    /** 主图：60 分钟 K 线 + JAX 济安线（A线/J线）+ 买卖圆点（可视区间锁定末 120 根） */
    private fun buildAlertMain(r: SsaAlertEngine.Result, cbars: List<Bar>, h: Int): CombinedChart {
        val chart = RegimeChart(requireContext())
        chart.dots = r.signals.map { s ->
            val b = r.bars[s.index]
            if (s.buy) RegimeChart.Dot(s.index, b.low * 0.99, BUY_TRI)
            else RegimeChart.Dot(s.index, b.high * 1.01, SELL_TRI)
        }
        chart.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h))
        chart.setBackgroundColor(Color.WHITE); chart.description.isEnabled = false
        chart.legend.textSize = 7f; chart.legend.textColor = GRAY
        chart.setScaleEnabled(true); chart.setPinchZoom(true); chart.setDragEnabled(true)
        chart.setHighlightPerTapEnabled(false); chart.setHighlightPerDragEnabled(false)

        val candleEs = ArrayList<CandleEntry>()
        r.bars.forEachIndexed { i, b ->
            candleEs.add(CandleEntry(i.toFloat(), b.high.toFloat(), b.low.toFloat(), b.open.toFloat(), b.close.toFloat()))
        }
        val cs = CandleDataSet(candleEs, "60分钟K").apply {
            increasingColor = RED; increasingPaintStyle = Paint.Style.FILL
            decreasingColor = GREEN; decreasingPaintStyle = Paint.Style.FILL
            neutralColor = Color.parseColor("#9E9E9E")
            shadowColor = Color.parseColor("#999999")
            setDrawValues(false); isHighlightEnabled = false
        }
        chart.data = CombinedData().apply {
            setData(CandleData(cs))
            setData(LineData(
                line(r.jax, "A线(JAX)", RED, 1.4f),
                line(r.jix, "J线(济安线)", BLUE, 1.4f)
            ))
        }
        chart.drawOrder = arrayOf(CombinedChart.DrawOrder.CANDLE, CombinedChart.DrawOrder.LINE)
        styleX(chart, cbars, time = true)
        chart.setVisibleXRangeMaximum(120f); chart.setVisibleXRangeMinimum(30f)
        chart.moveViewToX((r.bars.size - 1).toFloat())
        chart.post { chart.invalidate() }
        return chart
    }

    // ═══════════════════════ 渲染 ═══════════════════════

    // r 可空：三层主引擎失败（如周K不足，新股/次新股常见）时降级渲染——单票策略仅用1H数据，不受影响
    private fun render(name: String, code: String, r: Result?,
                       fc: SsaCycleForecast.Forecast?, ev: SsaCycleForecast.EvalSplit?,
                       so: StratOutput, mainError: String? = null) {
        resultBox.removeAllViews()
        // 📌 单票策略建议（四种策略按勾选输出，各自 t 收盘 → t+1 开盘）
        resultBox.addView(card { c ->
            c.addView(cardTitle("📌 单票策略建议（${name} $code）"))
            if (so.advices.isEmpty()) {
                // 策略计算异常时回退到三层 SSA 建议，避免空白；降级模式（主引擎不可用）时仅提示
                if (r != null) c.addView(adviceView(SsaT0Engine.adviceOf(r)))
                else c.addView(hint("⚠️ 单票策略未产出建议，且三层主引擎不可用：${mainError ?: "未知原因"}"))
            } else {
                so.advices.forEach { sa ->
                    val col = when {
                        sa.advice.contains("买") -> RED
                        sa.advice.contains("卖") -> GREEN
                        sa.advice.contains("持") -> BLUE
                        else -> GRAY
                    }
                    c.addView(TextView(requireContext()).apply {
                        text = "【${sa.label}】${sa.advice}"
                        textSize = 14f; setTextColor(col)
                        setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(4), 0, 0)
                    })
                    c.addView(TextView(requireContext()).apply {
                        text = "   ${sa.reason}"
                        textSize = 11f; setTextColor(Color.parseColor("#555555"))
                    })
                }
                c.addView(hint("口径：t 收盘出信号、t+1 开盘成交（无未来函数）。" +
                    "BBI 类为 BBI12 拐点（稳健版额外带 Δ5ER 过滤 + 8% 移动止盈）；" +
                    "区间做T 为唐奇安通道（双轨档上轨取最高价、下轨取最低价；海龟档取收盘价）。"))
            }
        })

        // 📈 区间做T / 海龟突破：通道 + ER10（变体三键切换，选择持久化；2026-10-22 池级回测海龟显著优于反转）
        if (SsaAlertStore.STRAT_RANGE_T in so.picked && so.er10 != null) {
            val tb = so.calc.map { Bar(it.date, it.time, it.open, it.high, it.low, it.close) }
            val closes = DoubleArray(so.calc.size) { so.calc[it].close }
            val highs = DoubleArray(so.calc.size) { so.calc[it].high }
            val lows = DoubleArray(so.calc.size) { so.calc[it].low }
            val labels = tb.map { "${it.date} ${it.time}" }
            val rCtx = requireContext()
            val rangePref = rCtx.getSharedPreferences("ssa_range_t", Context.MODE_PRIVATE)
            resultBox.addView(card { cardBox ->
                val wrapper = LinearLayout(rCtx).apply { orientation = LinearLayout.VERTICAL }
                cardBox.addView(wrapper)

                fun renderVariant(v: SsaRangeTEngine.Variant) {
                    rangePref.edit().putString("variant", v.key).apply()
                    wrapper.removeAllViews()

                    // 变体选择行（横向滚动，7 档）
                    val selRow = LinearLayout(rCtx).apply { orientation = LinearLayout.HORIZONTAL }
                    SsaRangeTEngine.VARIANTS.forEach { vv ->
                        selRow.addView(Button(rCtx).apply {
                            text = vv.label; textSize = 10f; backgroundTintList = null
                            if (vv.key == v.key) {
                                setBackgroundColor(Color.parseColor("#0288D1")); setTextColor(Color.WHITE)
                            } else {
                                setBackgroundColor(Color.parseColor("#ECEFF1")); setTextColor(Color.parseColor("#37474F"))
                            }
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT, dp(30)).apply { marginEnd = dp(4) }
                            minWidth = 0; minimumWidth = 0
                            setPadding(dp(8), 0, dp(8), 0)
                            setOnClickListener { if (vv.key != v.key) renderVariant(vv) }
                        })
                    }
                    wrapper.addView(HorizontalScrollView(rCtx).apply {
                        isHorizontalScrollBarEnabled = false
                        addView(selRow)
                    })
                    // 票性提示：趋势度 = 500根内 ER10>0.45 占比（2026-10-22 池级验证：
                    // <0.33 震荡票→反转档赢面大；>0.36 趋势票→海龟档赢面大；中间模糊带两者皆可）
                    run {
                        var trendCnt = 0; var erCnt = 0
                        for (t in 10 until closes.size) {
                            var den = 0.0
                            for (k in t - 9..t) den += kotlin.math.abs(closes[k] - closes[k - 1])
                            if (den > 0) {
                                erCnt++
                                if (kotlin.math.abs(closes[t] - closes[t - 10]) / den > 0.45) trendCnt++
                            }
                        }
                        val deg = if (erCnt > 0) trendCnt.toDouble() / erCnt else Double.NaN
                        val (tag, sug) = when {
                            deg.isNaN() -> "—" to "数据不足"
                            deg < 0.33 -> "震荡票" to "建议「反转ER降+贪底」档"
                            deg > 0.36 -> "趋势票" to "建议「海龟S1+2根+ER升」档"
                            else -> "模糊带" to "反转/海龟档均可"
                        }
                        wrapper.addView(TextView(rCtx).apply {
                            text = if (deg.isNaN()) "票性：数据不足"
                                else "票性：%s · 趋势度 %.2f（ER10>0.45 占比）· %s".format(tag, deg, sug)
                            textSize = 11f
                            setTextColor(Color.parseColor(if (deg.isNaN()) "#757575" else if (deg < 0.33) "#E65100" else if (deg > 0.36) "#1565C0" else "#616161"))
                            setPadding(0, dp(2), 0, dp(2))
                        })
                    }
                    wrapper.addView(cardTitle("📈 ${v.label}"))

                    // 按变体重算信号 + 双唐奇安四轨（进通道 nIn、出通道 nOut 各自的完整上下轨）
                    val st = runCatching { SsaRangeTEngine.run(closes, labels, v, highs, lows) }.getOrNull()
                    val n = closes.size
                    val hiSrc = if (v.srcHL) highs else closes
                    val loSrc = if (v.srcHL) lows else closes
                    val hiInArr = DoubleArray(n) { Double.NaN }    // 慢通道上轨（进）
                    val loInArr = DoubleArray(n) { Double.NaN }    // 慢通道下轨
                    val hiOutArr = DoubleArray(n) { Double.NaN }   // 快通道上轨
                    val loOutArr = DoubleArray(n) { Double.NaN }   // 快通道下轨（出）
                    for (t in v.nIn until n) {
                        var mx = -Double.MAX_VALUE; var mn = Double.MAX_VALUE
                        for (k in (t - v.nIn) until t) {
                            if (hiSrc[k] > mx) mx = hiSrc[k]
                            if (loSrc[k] < mn) mn = loSrc[k]
                        }
                        hiInArr[t] = mx; loInArr[t] = mn
                    }
                    for (t in v.nOut until n) {
                        var mx = -Double.MAX_VALUE; var mn = Double.MAX_VALUE
                        for (k in (t - v.nOut) until t) {
                            if (hiSrc[k] > mx) mx = hiSrc[k]
                            if (loSrc[k] < mn) mn = loSrc[k]
                        }
                        hiOutArr[t] = mx; loOutArr[t] = mn
                    }
                    // 触发轨（出信号用的两条）：突破=慢上轨进/快下轨出；反转=慢下轨进/快上轨出
                    val hiArr = if (v.mode == SsaRangeTEngine.Mode.BREAKOUT) hiInArr else hiOutArr
                    val loArr = if (v.mode == SsaRangeTEngine.Mode.BREAKOUT) loOutArr else loInArr
                    val sigs = st?.signals ?: emptyList()
                    val sellSigIdx = sigs.filter { it.kind == SsaRangeTEngine.Kind.SELL }.map { it.index }.toHashSet()
                    val buySigIdx = sigs.filter { it.kind == SsaRangeTEngine.Kind.BUY }.map { it.index }.toHashSet()
                    val dots = ArrayList<RegimeChart.Dot>()
                    sigs.forEach { s ->
                        if (s.index in so.calc.indices) dots.add(RegimeChart.Dot(s.index, so.calc[s.index].close,
                            if (s.kind == SsaRangeTEngine.Kind.BUY) BUY_TRI else SELL_TRI))
                    }
                    // 持仓状态轨迹（用于区分"该有信号却没出"的过滤触及）
                    val sigAt = sigs.associateBy { it.index }
                    val holdingBar = BooleanArray(n)
                    var hld = false
                    for (t in 0 until n) {
                        sigAt[t]?.let { hld = it.kind == SsaRangeTEngine.Kind.BUY }
                        holdingBar[t] = hld
                    }
                    // 灰点：盘中触及通道但当根未出信号（ER过滤/收盘未确认/T+1 拦截）
                    for (t in so.calc.indices) {
                        val up = hiArr[t]; val dn = loArr[t]
                        if (!up.isFinite() || !dn.isFinite()) continue
                        val b = so.calc[t]
                        when (v.mode) {
                            SsaRangeTEngine.Mode.REVERSAL -> {
                                if (holdingBar[t] && b.high >= up && t !in sellSigIdx)
                                    dots.add(RegimeChart.Dot(t, b.close, GRAY))
                                else if (!holdingBar[t] && b.low <= dn && t !in buySigIdx)
                                    dots.add(RegimeChart.Dot(t, b.close, GRAY))
                            }
                            SsaRangeTEngine.Mode.BREAKOUT -> {
                                if (!holdingBar[t] && b.high >= up && t !in buySigIdx)
                                    dots.add(RegimeChart.Dot(t, b.close, GRAY))
                                else if (holdingBar[t] && b.low <= dn && t !in sellSigIdx)
                                    dots.add(RegimeChart.Dot(t, b.close, GRAY))
                            }
                        }
                    }

                    val srcTxt = if (v.srcHL) "价" else "收盘"
                    val upperDesc = when (v.mode) {
                        SsaRangeTEngine.Mode.BREAKOUT -> "突破上沿(前${v.nIn}最高$srcTxt)"
                        SsaRangeTEngine.Mode.REVERSAL -> "反弹卖出上沿(前${v.nOut}最高$srcTxt)"
                    }
                    val lowerDesc = when (v.mode) {
                        SsaRangeTEngine.Mode.BREAKOUT -> "止损下沿(前${v.nOut}最低$srcTxt)"
                        SsaRangeTEngine.Mode.REVERSAL -> "低吸下沿(前${v.nIn}最低$srcTxt)"
                    }
                    // 非触发轨（双通道的另外两条，虚线参考）
                    val otherUp = if (v.mode == SsaRangeTEngine.Mode.BREAKOUT) hiOutArr else hiInArr
                    val otherDn = if (v.mode == SsaRangeTEngine.Mode.BREAKOUT) loInArr else loOutArr
                    val otherUpDesc = "参考轨·前${if (v.mode == SsaRangeTEngine.Mode.BREAKOUT) v.nOut else v.nIn}最高$srcTxt"
                    val otherDnDesc = "参考轨·前${if (v.mode == SsaRangeTEngine.Mode.BREAKOUT) v.nIn else v.nOut}最低$srcTxt"
                    val priceChart = strategyChart(
                        tb,
                        listOf(
                            line(closes, "收盘", Color.BLACK, 1.6f),
                            line(hiArr, upperDesc, BLUE, 1.2f),
                            line(loArr, lowerDesc, ORANGE, 1.2f),
                            line(otherUp, otherUpDesc, BLUE, 0.8f).apply { enableDashedLine(10f, 6f, 0f) },
                            line(otherDn, otherDnDesc, ORANGE, 0.8f).apply { enableDashedLine(10f, 6f, 0f) }
                        ),
                        260,
                        dots = dots,
                        bands = listOf(RegimeChart.BandFill(hiArr, loArr, BLUE, 18))
                    )
                    val er = erChart(so.er10!!, tb, 130)
                    wrapper.addView(priceChart)
                    wrapper.addView(er)
                    linkCharts(listOf(priceChart, er))
                    val legendText = when (v.mode) {
                        SsaRangeTEngine.Mode.REVERSAL ->
                            "买入：${v.describeEntry()} 且 ER10 ≤ ${SsaRangeTEngine.ER_MAX}；卖出：${v.describeExit()}。" +
                                "ER10 高于红线（趋势段）时不做区间。蓝实=$upperDesc 橙实=$lowerDesc 黑线=收盘；虚线=双通道参考轨。" +
                                "圆点：红=买入 绿=卖出；灰=触及通道但被过滤（趋势段/未持仓/收盘未确认/T+1），不出信号。"
                        SsaRangeTEngine.Mode.BREAKOUT ->
                            "买入：${v.describeEntry()}（突破建仓，海龟思想让利润奔跑）；卖出：${v.describeExit()}（跌破止损/止盈）。" +
                                "蓝实=$upperDesc 橙实=$lowerDesc 黑线=收盘；虚线=双通道参考轨。" +
                                "圆点：红=买入 绿=卖出；灰=触及通道但当根未出信号（未持仓/收盘未确认/T+1），不出信号。"
                    }
                    wrapper.addView(legend(legendText + "单指拖动可十字扫描，与 ER10 副图联动缩放。"))
                }
                renderVariant(SsaRangeTEngine.variantByKey(rangePref.getString("variant", null)))
            })
        }

        // 📉 BBI12 拐点：收盘 + BBI + BBI12
        if (so.bbiBundle != null) {
            val tb = so.calc.map { Bar(it.date, it.time, it.open, it.high, it.low, it.close) }
            val closes = DoubleArray(so.calc.size) { so.calc[it].close }
            // BBI12 拐点圆点：锚定信号根的 BBI12 值（拐点就在该线上）
            val bbi12Line = so.bbiBundle!!.bbiMa12
            val bbiDots = so.bbiBundle!!.bbiPivots.mapNotNull { s ->
                val v = bbi12Line.getOrNull(s.index) ?: return@mapNotNull null
                if (v.isNaN()) return@mapNotNull null
                RegimeChart.Dot(s.index, v,
                    if (s.buy) BUY_TRI else SELL_TRI)
            }
            resultBox.addView(card {
                it.addView(cardTitle("📉 BBI12 拐点：收盘 / BBI / BBI12"))
                it.addView(strategyChart(
                    tb,
                    listOf(
                        line(closes, "收盘", Color.BLACK, 1.2f),
                        line(so.bbiBundle!!.bbi, "BBI", BLUE, 1.4f),
                        line(bbi12Line, "BBI12", RED, 1.8f)
                    ),
                    230,
                    dots = bbiDots
                ))
                it.addView(legend("BBI=(MA3+MA6+MA12+MA24)/4；BBI12=MA(BBI,12)。" +
                    "BBI12 谷底确认 → 次日开盘买入，峰顶确认 → 次日开盘卖出。" +
                    "圆点：红=买入 绿=卖出（锚在 BBI12 拐点）。单指拖动可十字扫描、双指缩放。"))
            })
        }

        // 混合策略（SSA 三态）：勾选后才展示原有三层报表与三态图
        if (SINGLE_HYBRID_KEY in so.picked && r == null) {
            // 降级占位：主引擎失败（周K/日K不足等）时说明原因，单票策略已在上方照常渲染
            resultBox.addView(card {
                it.addView(cardTitle("📊 三层SSA主回测"))
                it.addView(hint("⚠️ 不可用：${mainError ?: "未知原因"}。上方单票策略（仅用 1H 数据，含双唐奇安通道）不受影响。"))
            })
        }
        if (SINGLE_HYBRID_KEY in so.picked && r != null) {
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
        // 主图 + 下方 cycle 分量副图 联动（同缩放 + 同十字光标）
        val hourChart = buildHourMain(r, 340)
        val cycleUnder = buildHourCycleUnder(r.h, 140)
        linkCharts(listOf(hourChart, cycleUnder))
        // 主图：1H K线 + 周期扰动区间（激进/保守）同轴 + ABC + 买卖点 + 训练分界
        resultBox.addView(card {
            it.addView(cardTitle("① 1H 主图：三态背景分区 + 收盘折线 + 中线/扰动区间 + ●买●卖（默认近1月）"))
            it.addView(hourRangeBar(hourChart, r.h.bars, listOf(cycleUnder)))
            it.addView(hourChart)
            it.addView(cycleUnder)
            it.addView(legend("背景色 红=UP(核心做多) 绿=WINDOW(上穿中位买) 灰=FLAT(空仓·极限突破T)  橙线=中线(trend+cycle)  " +
                "蓝=正常区间(±1.5σ) 红虚线=极限区间(±2.0σ，含填充)  ●红=买入 ●绿=卖出  竖虚线=训练/样本外分界  " +
                "下方紫线=1H 周期振荡分量 cycle（Y 轴按可视区间自动放大，看清转折）"))
        })
        // ③ 拟合周期预测（未来一天走势 + 目标价位；纯展示，不进信号/损益）
        if (fc != null && ev != null) resultBox.addView(card {
            it.addView(cardTitle("③ 🔮 拟合周期预测（未来一天 ≈ 4 根1H，每日滚动窗口更新）"))
            it.addView(forecastBody(fc, ev, r.h.bars))
        })
        // ④ 净值
        resultBox.addView(card {
            it.addView(cardTitle("④ 总权益（含底仓）vs 买入持有 + 回撤"))
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
            it.addView(cardTitle("📋 混合策略 T单明细（${r.trades.size} 笔，最多显示60笔）"))
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
    }

    private fun pct(v: Double) = "${if (v > 0) "+" else ""}%.2f%%".format(v)

    /** ③ 拟合周期预测卡内容：指标网格 + 预测扇形图 + 命中率回测。纯展示，不进信号/损益。 */
    private fun forecastBody(fc: SsaCycleForecast.Forecast, ev: SsaCycleForecast.EvalSplit,
                             bars: List<Bar>): View {
        val ctx = requireContext()
        val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        if (!fc.ok) {
            wrap.addView(hint("数据不足，暂无法给出预测：${fc.note}"))
            return wrap
        }
        val chg = (fc.target - fc.lastClose) / fc.lastClose * 100.0
        wrap.addView(statGrid(listOf(
            "主周期" to (if (fc.period > 0) "≈%.0f 根".format(fc.period) else "无显著周期"),
            "下一根预测" to "%.2f（%s）".format(fc.predNext, fc.nextLabel),
            "目标价位" to "%.2f（%s）".format(fc.target, fc.targetLabel),
            "较现价" to pct(chg),
            "方向" to (if (fc.up) "▲ 上行" else "▼ 下行"),
            "残差σ" to "%.3f".format(fc.sigma)
        )))
        wrap.addView(buildForecastChart(fc, bars, 180))
        wrap.addView(hint("单谐波拟合（去趋势 + 最小二乘外推）未来 ${fc.horizon} 根1H（≈一个交易日）；" +
            "红=预测中枢 橙=上扇(+σ·√step) 蓝=下扇(-σ·√step)。${fc.note}"))
        wrap.addView(statGrid(listOf(
            "方向命中率(全样本)" to "%.1f%%".format(ev.full.hitDirPct),
            "MAE(全样本)" to "%.3f（%.2f%%）".format(ev.full.mae, ev.full.maePct),
            "命中率(训练)" to "%.1f%%".format(ev.train.hitDirPct),
            "命中率(样本外)" to "%.1f%%".format(ev.test.hitDirPct)
        )))
        wrap.addView(hint("命中率=滚动逐根外推「下一根方向」与真实方向一致比例；MAE=目标价位平均绝对误差。" +
            "预测仅作展示参考，不参与选股/交易信号与损益计算。"))
        return wrap
    }

    /** ③ 预测扇形图：近60根收盘 + 末根起外推 horizon 根的预测中枢/上下扇。 */
    private fun buildForecastChart(fc: SsaCycleForecast.Forecast, bars: List<Bar>, h: Int): LineChart {
        val chart = LineChart(requireContext())
        chart.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h))
        chart.setBackgroundColor(Color.WHITE); chart.description.isEnabled = false
        chart.legend.textSize = 8f; chart.legend.textColor = GRAY
        chart.setScaleEnabled(true); chart.setPinchZoom(true); chart.setDragEnabled(true)
        chart.setHighlightPerTapEnabled(false); chart.setHighlightPerDragEnabled(false)
        val n = bars.size
        val start = maxOf(0, n - 60)
        val closes = ArrayList<Entry>()
        for (i in start until n) closes.add(Entry(i.toFloat(), bars[i].close.toFloat()))
        val mid = ArrayList<Entry>(); val upA = ArrayList<Entry>(); val loA = ArrayList<Entry>()
        val base = bars[n - 1].close.toFloat()
        mid.add(Entry((n - 1).toFloat(), base))
        upA.add(Entry((n - 1).toFloat(), base))
        loA.add(Entry((n - 1).toFloat(), base))
        for (s in 1..fc.horizon) {
            val x = (n - 1 + s).toFloat()
            mid.add(Entry(x, fc.predMid[s - 1].toFloat()))
            upA.add(Entry(x, fc.predUpper[s - 1].toFloat()))
            loA.add(Entry(x, fc.predLower[s - 1].toFloat()))
        }
        chart.data = LineData(
            LineDataSet(closes, "收盘").apply {
                color = Color.parseColor("#222222"); lineWidth = 1.2f
                setDrawCircles(false); setDrawValues(false); isHighlightEnabled = false
            },
            LineDataSet(mid, "预测中枢").apply {
                color = Color.parseColor("#E53935"); lineWidth = 1.8f
                setDrawCircles(true); circleRadius = 3f; setDrawValues(false); isHighlightEnabled = false
            },
            LineDataSet(upA, "上扇(+σ)").apply {
                color = Color.parseColor("#FFB74D"); lineWidth = 1f
                setDrawCircles(false); setDrawValues(false); isHighlightEnabled = false
                enableDashedLine(6f, 4f, 0f)
            },
            LineDataSet(loA, "下扇(-σ)").apply {
                color = Color.parseColor("#4FC3F7"); lineWidth = 1f
                setDrawCircles(false); setDrawValues(false); isHighlightEnabled = false
                enableDashedLine(6f, 4f, 0f)
            }
        )
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            granularity = 1f; labelCount = 7
            textSize = 7f; textColor = GRAY; setDrawGridLines(false)
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(v: Float): String {
                    val i = v.toInt()
                    return if (i in bars.indices) bars[i].time.ifEmpty { bars[i].date.takeLast(5) } else ""
                }
            }
        }
        chart.axisLeft.textSize = 8f; chart.axisLeft.textColor = GRAY
        chart.axisRight.isEnabled = false
        chart.setVisibleXRangeMinimum(10f)
        chart.xAxis.addLimitLine(LimitLine((n - 1).toFloat(), "").apply {
            lineColor = Color.parseColor("#999999"); lineWidth = 1f
            enableDashedLine(4f, 4f, 0f)
        })
        chart.post { chart.invalidate() }
        return chart
    }

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
        /** 买卖圆点：index=K线下标，price=锚点价（买入画在K线下、卖出画在K线上） */
        data class Dot(val index: Int, val price: Double, val color: Int)

        var spans: List<Span> = emptyList()
        var bandFills: List<BandFill> = emptyList()
        var dots: List<Dot> = emptyList()
        /** 圆点半径（dp） */
        var dotRadiusDp = 2.8f

        private val spanPaint = Paint().apply { style = Paint.Style.FILL }
        private val fillPaint = Paint().apply { style = Paint.Style.FILL }
        private val dotPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }

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
            // 买卖圆点画在 super 之后 → 位于 K线/折线之上（对齐 Python scatter zorder）
            if (data != null && dots.isNotEmpty()) {
                val t = getTransformer(AxisDependency.LEFT)
                val content = viewPortHandler.contentRect
                val save = c.save()
                c.clipRect(content)
                val r = dotRadiusDp * resources.displayMetrics.density
                for (d in dots) {
                    val p = t.getPixelForValues(d.index.toFloat(), d.price.toFloat())
                    dotPaint.color = d.color
                    c.drawCircle(p.x.toFloat(), p.y.toFloat(), r, dotPaint)
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

    /** 主图显示范围选择条：近3个月 / 近1月（按实际日期切片，默认近1月）；
     *  also = 与主图同缩放的副图（主图下方 cycle 分量），一并应用同一可见区间 */
    private fun hourRangeBar(chart: CombinedChart, bars: List<Bar>,
                             also: List<BarLineChartBase<*>> = emptyList()): LinearLayout {
        val ctx = requireContext()
        val opts = listOf("近3个月" to barCountSinceMonths(bars, 3),
            "近1月" to barCountSinceMonths(bars, 1))
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
        (group.getChildAt(1) as RadioButton).isChecked = true   // 默认近1月
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

    /** 主图/副图联动：X 视口（缩放/平移）同步 + 十字光标 x 索引同步 */
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
        var linkingHighlight = false
        charts.forEach { c ->
            c.setScaleEnabled(true); c.setPinchZoom(true); c.setDragEnabled(true)
            enableCrosshair(c)   // 十字光标跟随手指（不占用拖动平移）
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
                    charts.forEach { o ->
                        if (o !== c) {
                            // 用像素位置取 Highlight：CombinedChart 的 getEntryForHighlight 不接受
                            // 直接构造的 Highlight（dataIndex=-1 会越界闪退）
                            val pix = o.getTransformer(AxisDependency.LEFT)
                                .getPixelForValues(h.x, 0f)
                            val px = pix.x.toFloat()
                            val content = o.viewPortHandler.contentRect
                            val hl = if (px >= content.left && px <= content.right)
                                o.getHighlightByTouchPoint(px, content.centerY()) else null
                            if (hl != null) o.highlightValue(hl, false)
                        }
                    }
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
        // 买卖圆点：买入落在当根 low 下方，卖出落在当根 high 上方
        chart.dots = r.markers.map { m ->
            val b = bars[m.index]
            if (m.buy) RegimeChart.Dot(m.index, b.low * 0.99, BUY_TRI)
            else RegimeChart.Dot(m.index, b.high * 1.01, SELL_TRI)
        }
        chart.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h))
        chart.setBackgroundColor(Color.WHITE); chart.description.isEnabled = false
        chart.legend.textSize = 7f; chart.legend.textColor = GRAY
        chart.setScaleEnabled(true); chart.setPinchZoom(true); chart.setDragEnabled(true)
        enableCrosshair(chart)
        chart.setVisibleXRangeMaximum(240f); chart.setVisibleXRangeMinimum(60f)
        chart.moveViewToX((bars.size - 1).toFloat())
        chart.drawOrder = arrayOf(CombinedChart.DrawOrder.LINE)

        // 黑色折线（1H 收盘）替代 K 线
        val closeEs = ArrayList<Entry>()
        bars.forEachIndexed { i, b -> closeEs.add(Entry(i.toFloat(), b.close.toFloat())) }
        val closeDs = LineDataSet(closeEs, "1H收盘").apply {
            color = Color.BLACK; lineWidth = 1.2f
            setDrawCircles(false); setDrawValues(false)
            isHighlightEnabled = true   // 供十字光标取点
            setHighlightLineWidth(2.0f); enableDashedHighlightLine(8f, 4f, 0f)
            highLightColor = Color.parseColor("#888888")
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

    /** ① 主图下方的 1H 周期振荡分量副图：Y 轴随可视区间自动重拟合（按 cycle 极值撑满面板），
     *  放大振幅以便看清 cycle 的转折；与主图 X 视口/十字光标联动。 */
    private fun buildHourCycleUnder(layer: Layer, h: Int): LineChart {
        val bars = layer.bars
        val chart = LineChart(requireContext())
        chart.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h))
        chart.setBackgroundColor(Color.WHITE); chart.description.isEnabled = false
        chart.legend.textSize = 7f; chart.legend.textColor = GRAY
        chart.setScaleEnabled(true); chart.setPinchZoom(true); chart.setDragEnabled(true)
        enableCrosshair(chart)
        chart.setAutoScaleMinMaxEnabled(true)   // Y 轴随可见区间重拟合 → 振幅放大、转折清晰
        chart.setVisibleXRangeMaximum((bars.size + 2).toFloat()); chart.setVisibleXRangeMinimum(40f)
        chart.moveViewToX((bars.size - 1).toFloat())
        val ds = line(layer.cycle, "周期振荡分量 cycle", PURPLE, 1.5f).apply {
            isHighlightEnabled = true
            setHighlightLineWidth(2.0f)
            enableDashedHighlightLine(8f, 4f, 0f)
            highLightColor = Color.parseColor("#888888")
        }
        chart.data = LineData(ds)
        chart.axisLeft.apply { textSize = 7f; textColor = GRAY; gridColor = Color.parseColor("#EEEEEE") }
        chart.axisRight.isEnabled = false
        styleX(chart, bars, time = true)
        chart.post { chart.invalidate() }
        return chart
    }

    /** 十字光标：单指按住拖动/点按即按手指位置刷新高亮；不占用拖动平移（每帧 post 重投影，
     *  避免视口平移后 Highlight 像素坐标过期导致「十字光标停住不动」）。 */
    private fun enableCrosshair(c: BarLineChartBase<*>) {
        c.setHighlightPerTapEnabled(false)
        c.setHighlightPerDragEnabled(false)   // 拖动交给图表平移，高亮由下面手动跟随
        var lastX = 0f; var lastY = 0f
        c.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    lastX = e.x; lastY = e.y
                    v.post {
                        val h = c.getHighlightByTouchPoint(lastX, lastY)
                        if (h != null) c.highlightValue(h, true) else c.highlightValue(null, true)
                    }
                }
            }
            false   // 继续交给图表处理平移/缩放
        }
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

    /** 策略图（区间做T / BBI12）：折线 + 区间带填充 + 买卖圆点；
     *  单指拖动 = 十字扫描（可与其他图联动），双指 = 捏合缩放。 */
    private fun strategyChart(
        bars: List<Bar>,
        sets: List<LineDataSet>,
        h: Int,
        dots: List<RegimeChart.Dot> = emptyList(),
        bands: List<RegimeChart.BandFill> = emptyList()
    ): RegimeChart {
        val chart = RegimeChart(requireContext())
        chart.dots = dots
        chart.bandFills = bands
        chart.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h))
        chart.setBackgroundColor(Color.WHITE); chart.description.isEnabled = false
        chart.legend.textSize = 7f; chart.legend.textColor = GRAY
        chart.setScaleEnabled(true); chart.setPinchZoom(true); chart.setDragEnabled(true)
        enableCrosshair(chart)
        chart.setVisibleXRangeMaximum((bars.size + 2).toFloat()); chart.setVisibleXRangeMinimum(40f)
        chart.moveViewToX((bars.size - 1).toFloat())
        // 十字光标样式：粗虚线竖线 + 横向高亮点（收盘线可高亮）
        sets.forEach {
            it.isHighlightEnabled = true
            it.setHighlightLineWidth(2.0f)
            it.enableDashedHighlightLine(8f, 4f, 0f)
            it.highLightColor = Color.parseColor("#888888")
        }
        chart.data = CombinedData().apply { setData(LineData(sets)) }
        styleX(chart, bars, time = true)
        chart.post { chart.invalidate() }
        return chart
    }

    /** 区间做T 的 ER10 指标图（0~1，含 ER_MAX 阈值虚线） */
    private fun erChart(er: DoubleArray, bars: List<Bar>, h: Int): LineChart {
        val chart = LineChart(requireContext())
        chart.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h))
        chart.setBackgroundColor(Color.WHITE); chart.description.isEnabled = false
        chart.legend.textSize = 7f; chart.legend.textColor = GRAY
        chart.setScaleEnabled(true); chart.setPinchZoom(true); chart.setDragEnabled(true)
        enableCrosshair(chart)
        chart.setVisibleXRangeMaximum((bars.size + 2).toFloat()); chart.setVisibleXRangeMinimum(40f)
        chart.moveViewToX((bars.size - 1).toFloat())
        val erDs = line(er, "ER10", PURPLE, 1.4f).apply {
            isHighlightEnabled = true
            setHighlightLineWidth(2.0f)
            enableDashedHighlightLine(8f, 4f, 0f)
            highLightColor = Color.parseColor("#888888")
        }
        chart.data = LineData(erDs)
        chart.axisLeft.apply {
            axisMinimum = 0f; axisMaximum = 1f
            textSize = 7f; textColor = GRAY
            addLimitLine(LimitLine(SsaRangeTEngine.ER_MAX.toFloat(), "ER10≤${SsaRangeTEngine.ER_MAX}").apply {
                lineColor = RED; lineWidth = 1.1f; enableDashedLine(8f, 5f, 0f)
                textSize = 7f; textColor = RED
            })
        }
        chart.axisRight.isEnabled = false
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            granularity = (bars.size / 7).coerceAtLeast(1).toFloat(); labelCount = 7
            textSize = 7f; textColor = GRAY; setDrawGridLines(false)
            valueFormatter = timeFmt(bars)
        }
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
