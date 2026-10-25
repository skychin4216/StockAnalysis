package com.chin.stockanalysis.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.CombinedChart
import com.github.mikephil.charting.charts.ScatterChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.CandleData
import com.github.mikephil.charting.data.CandleDataSet
import com.github.mikephil.charting.data.CandleEntry
import com.github.mikephil.charting.data.CombinedData
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.data.ScatterData
import com.github.mikephil.charting.data.ScatterDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 📐 缠论 Tab — 六大功能入口
 *
 * 子模式：荐股 / 单股 / 持仓池 / 回测 / 预测 / 验证
 * 数据源：assets/data/chanlun/（PC 端 chan.py 计算 → JSON 推送）
 */
class ChanlunFragment : Fragment() {

    private val RED = Color.parseColor("#E53935")
    private val GREEN = Color.parseColor("#43A047")
    private val BLUE = Color.parseColor("#1976D2")
    private val TEAL = Color.parseColor("#00897B")
    private val GRAY = Color.parseColor("#999999")
    private val ORANGE = Color.parseColor("#E65100")

    // 子模式：0=荐股 1=单股 2=持仓池 3=回测 4=预测 5=验证
    private var subMode = 0
    private lateinit var subBtns: Array<TextView>
    private var contentHost: FrameLayout? = null
    // 各页视图缓存：切走再切回保留上次分析结果（点页内「分析/刷新」才更新）
    private val pageViews = arrayOfNulls<View>(6)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F7F7F7"))
        }

        // ── 子模式按钮行（横向滚动） ──
        val labels = arrayOf("🎯 荐股", "🔍 单股", "📋 持仓池", "📈 回测", "🔮 预测", "✅ 验证")
        subBtns = Array(6) { i ->
            TextView(ctx).apply {
                text = labels[i]; textSize = 11f; gravity = Gravity.CENTER
                setPadding(dp(10), dp(5), dp(10), dp(5))
                setOnClickListener { switchSubMode(i) }
            }
        }
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        for (b in subBtns) {
            btnRow.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(2); marginEnd = dp(2) })
        }
        val btnScroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        btnScroll.addView(btnRow)
        root.addView(btnScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ── 内容宿主 ──
        val host = FrameLayout(ctx).apply { id = View.generateViewId() }
        contentHost = host
        root.addView(host, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        paintSubMode(0)
        // 默认加载荐股页
        switchSubMode(0)
        return root
    }

    private fun paintSubMode(m: Int) {
        for (i in subBtns.indices) {
            val on = i == m
            subBtns[i].background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                if (on) { setColor(Color.parseColor("#E0F2F1")); setStroke(dp(1), TEAL) }
                else { setColor(Color.parseColor("#F2F2F2")); setStroke(dp(1), Color.parseColor("#DDDDDD")) }
            }
            subBtns[i].setTextColor(if (on) TEAL else Color.parseColor("#666666"))
        }
    }

    private fun switchSubMode(m: Int) {
        replayJob?.cancel(); replayJob = null   // 切页时停掉逐K回放
        subMode = m
        paintSubMode(m)
        val host = contentHost ?: return
        host.removeAllViews()
        // 命中缓存直接复用（保留上次结果），未命中才构建
        val page = pageViews[m] ?: when (m) {
            0 -> buildRecommendPage()
            1 -> buildSinglePage()
            2 -> buildPoolPage()
            3 -> buildBacktestPage()
            4 -> buildPredictPage()
            else -> buildVerifyPage()
        }.also { pageViews[m] = it }
        host.addView(page, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    override fun onDestroyView() {
        replayJob?.cancel(); replayJob = null
        pageViews.fill(null)   // 视图树销毁时清缓存，避免旧 Context 泄漏
        contentHost = null
        super.onDestroyView()
    }

    // ═══════════════════ 页面 ═══════════════════

    /** 🎯 荐股：本地引擎扫描核心池买点 */
    private fun buildRecommendPage(): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(16)) }
        box.addView(tv("🎯 缠论荐股", 14f, true))
        box.addView(tv("本地引擎扫描核心池（近5根K线内出现一/二/三买）", GRAY, 10f))
        val listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(listBox)
        val btn = android.widget.Button(ctx).apply { text = "开始扫描（约1分钟）" }
        box.addView(btn, 0)
        val prog = tv("", GRAY, 10f)
        box.addView(prog, 2)
        scroll.addView(box)

        btn.setOnClickListener {
            btn.isEnabled = false
            listBox.removeAllViews()
            val pool = loadCorePool()
            val results = java.util.Collections.synchronizedList(ArrayList<SignalRow>())
            viewLifecycleOwner.lifecycleScope.launch {
                prog.text = "扫描中 0/${pool.size}…"
                var done = 0
                for (code in pool) {
                    done++
                    if (done % 10 == 0) prog.text = "扫描中 $done/${pool.size} 信号 ${results.size}…"
                    try {
                        val r = scanOneLocal(code)
                        if (r != null) results.add(r)
                    } catch (_: Exception) {}
                }
                results.sortByDescending { it.score }
                prog.text = "完成：${pool.size} 只 → ${results.size} 个信号"
                renderSignalList(listBox, results)
                btn.isEnabled = true
            }
        }
        return scroll
    }

    private data class SignalRow(val code: String, val bspType: String, val sure: Boolean,
                                 val price: Double, val dist: Int, val zsCnt: Int, val score: Int)

    private suspend fun scanOneLocal(code: String): SignalRow? = withContext(kotlinx.coroutines.Dispatchers.Default) {
        val bars = fetchBars(code, "1h")
        if (bars.size < 60) return@withContext null
        val st = com.chin.stockanalysis.strategy.chanlun.ChanlunEngine.compute(bars)
        val n = bars.size
        val buys = st.bsp.filter { it.isBuy && n - 1 - it.idx <= 5 }
        if (buys.isEmpty()) return@withContext null
        val best = buys.maxByOrNull { b ->
            val base = when (b.type) { "1" -> 30; "2" -> 25; else -> 20 }
            val sureB = if (b.sure) 20 else 0
            val d = n - 1 - b.idx
            val rec = if (d <= 1) 15 else if (d <= 3) 10 else 5
            base + sureB + rec
        }!!
        val d = n - 1 - best.idx
        val base = when (best.type) { "1" -> 30; "2" -> 25; else -> 20 }
        SignalRow(code, best.type, best.sure, best.price, d, st.zs.size,
            base + (if (best.sure) 20 else 0) + (if (d <= 1) 15 else if (d <= 3) 10 else 5))
    }

    private fun renderSignalList(box: LinearLayout, rows: List<SignalRow>) {
        box.removeAllViews()
        if (rows.isEmpty()) { box.addView(tv("无信号", GRAY, 12f)); return }
        // 按买点类型分组卡片：一买 / 二买 / 三买
        val groups = listOf(
            Triple("1", "一买", GREEN),
            Triple("2", "二买", BLUE),
            Triple("3", "三买", ORANGE)
        )
        for ((type, title, color) in groups) {
            val list = rows.filter { it.bspType == type }.sortedByDescending { it.score }
            if (list.isEmpty()) continue
            val card = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.WHITE)
                    setStroke(dp(1), Color.parseColor("#E4E4E4"))
                }
            }
            card.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            // 卡片头：彩色圆点 + 类型 + 数量
            val head = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(6))
            }
            head.addView(android.view.View(requireContext()).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(6) }
            })
            head.addView(tv("$title · ${list.size} 只", color, 13f, true))
            card.addView(head)
            list.forEachIndexed { i, r ->
                if (i > 0) card.addView(android.view.View(requireContext()).apply {
                    setBackgroundColor(Color.parseColor("#F0F0F0"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(7); bottomMargin = dp(7) }
                })
                val rowBox = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(6), dp(4), dp(6), dp(4))
                    setOnClickListener { openSingle(r.code) }
                }
                rowBox.addView(tv(
                    "${r.code}   ${fmt2(r.price)}   ${if (r.sure) "✔ 已确认" else "· 待确认"}",
                    if (r.sure) color else Color.parseColor("#333333"), 13f
                ).apply { setTypeface(null, android.graphics.Typeface.BOLD) })
                rowBox.addView(tv("中枢 ${r.zsCnt} 个 · ${r.dist} 根前 · 评分 ${r.score}", GRAY, 10f))
                card.addView(rowBox)
            }
            box.addView(card)
        }
    }

    private fun fmt2(v: Double) = String.format("%.2f", v)

    /** 🔍 单股：本地引擎计算 + 结构图 + 状态卡 */
    private var singleCode = "600188"
    private var singleLv = "1h"
    private var lastStructure: com.chin.stockanalysis.strategy.chanlun.ChanlunEngine.Structure? = null
    // ── 逐K回放状态 ──
    private var lastBars: List<com.chin.stockanalysis.strategy.chanlun.ChanlunEngine.Bar>? = null
    private var replayJob: kotlinx.coroutines.Job? = null
    private var replaySpeedMs = 500L
    private fun buildSinglePage(): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(16)) }
        box.addView(tv("🔍 单股缠论结构", 14f, true))

        // 输入行：代码 + 级别 + 分析按钮
        val inputRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val codeEt = android.widget.EditText(ctx).apply {
            setText(singleCode); textSize = 13f
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val lvGroup = RadioGroup(ctx).apply { orientation = RadioGroup.HORIZONTAL }
        val rb1h = RadioButton(ctx).apply { text = "1H"; isChecked = singleLv == "1h"; textSize = 12f }
        val rbDay = RadioButton(ctx).apply { text = "日"; isChecked = singleLv == "day"; textSize = 12f }
        lvGroup.addView(rb1h); lvGroup.addView(rbDay)
        lvGroup.setOnCheckedChangeListener { _, id -> singleLv = if (id == rb1h.id) "1h" else "day" }
        val btn = android.widget.Button(ctx).apply { text = "分析" }
        inputRow.addView(codeEt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
        inputRow.addView(lvGroup, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
        inputRow.addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(inputRow)

        // ── 图层开关行：笔 / 中枢 / 买卖点 / 支撑压力 / 黄金分割 ──
        val toggleRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        toggleRow.addView(tv("图层:", GRAY, 12f))
        val cbBi = android.widget.CheckBox(ctx).apply { text = "笔"; isChecked = true; textSize = 11f }
        val cbZs = android.widget.CheckBox(ctx).apply { text = "中枢"; isChecked = true; textSize = 11f }
        val cbBsp = android.widget.CheckBox(ctx).apply { text = "买卖点"; isChecked = true; textSize = 11f }
        val cbFx = android.widget.CheckBox(ctx).apply { text = "分型"; isChecked = true; textSize = 11f }
        toggleRow.addView(cbBi); toggleRow.addView(cbZs); toggleRow.addView(cbBsp); toggleRow.addView(cbFx)
        box.addView(toggleRow)
        val toggleRow2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        toggleRow2.addView(tv("　　　", GRAY, 12f))
        val cbSP = android.widget.CheckBox(ctx).apply { text = "支撑压力"; isChecked = true; textSize = 11f }
        val cbFib = android.widget.CheckBox(ctx).apply { text = "黄金分割"; isChecked = false; textSize = 11f }
        toggleRow2.addView(cbSP); toggleRow2.addView(cbFib)
        box.addView(toggleRow2)

        val status = tv("输入代码点「分析」", GRAY, 11f)
        box.addView(status)
        val chart = CombinedChart(ctx)
        box.addView(chart, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(320)))
        // 副图① OBV 能量潮（亿手）
        val obvChart = com.github.mikephil.charting.charts.LineChart(ctx)
        box.addView(obvChart, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(90)))
        // 副图② SSA cycle 分量（L 可选 30/60）
        val cycleChart = com.github.mikephil.charting.charts.LineChart(ctx)
        box.addView(cycleChart, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(90)))
        // cycle 嵌入窗口 L 切换
        var ssaL = 30
        val lRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        lRow.addView(tv("SSA cycle 窗口L：", GRAY, 10f))
        val btnL30 = android.widget.Button(ctx).apply { text = "L=30"; textSize = 10f }
        val btnL60 = android.widget.Button(ctx).apply { text = "L=60"; textSize = 10f }
        lRow.addView(btnL30); lRow.addView(btnL60)
        box.addView(lRow)
        fun refreshAux() { lastBars?.let { renderAuxCharts(obvChart, cycleChart, it, ssaL) } }
        btnL30.setOnClickListener { if (ssaL != 30) { ssaL = 30; refreshAux() } }
        btnL60.setOnClickListener { if (ssaL != 60) { ssaL = 60; refreshAux() } }

        // ── 图例说明 ──
        val legend = """
            📖 图例：
            ▪ 红蜡烛=上涨K线  绿蜡烛=下跌K线（A股红涨绿跌）
            ▪ 红/绿粗线=笔（上涨笔红/下跌笔绿，虚线=待确认虚笔）
            ▪ 蓝虚线框=中枢（多空平衡区）
            ▪ 红圆点=买点（下方）  绿圆点=卖点（上方）
            ▪ 紫▽=顶分型  青△=底分型（分型确认后标记）
        """.trimIndent()
        box.addView(tv(legend, GRAY, 10f).apply { setPadding(0, dp(4), 0, dp(4)) })

        // ── ▶ 逐K回放面板：滚动回放历史K线，动态看 分型/笔/中枢/买卖点 的生成过程 ──
        val replayRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val btnPlay = android.widget.Button(ctx).apply { text = "▶ 逐K回放"; textSize = 11f }
        val btnPause = android.widget.Button(ctx).apply { text = "⏸ 暂停"; textSize = 11f }
        val btnSpeed = android.widget.Button(ctx).apply { text = "速度:中"; textSize = 11f }
        val btnReset = android.widget.Button(ctx).apply { text = "⏮ 重置"; textSize = 11f }
        val tvProg = tv("先点「分析」加载K线", GRAY, 10f).apply { setPadding(dp(6), 0, 0, 0) }
        replayRow.addView(btnPlay); replayRow.addView(btnPause); replayRow.addView(btnSpeed); replayRow.addView(btnReset)
        replayRow.addView(tvProg, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(replayRow)
        val logTv = tv("", GRAY, 10f)
        val logScroll = ScrollView(ctx).apply { addView(logTv) }
        box.addView(logScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(110)))

        var replayPaused = false   // 暂停标志：回放循环挂起等待，idx 不前进

        fun stopReplay(restore: Boolean) {
            replayJob?.cancel(); replayJob = null
            replayPaused = false; btnPause.text = "⏸ 暂停"
            btnPlay.text = "▶ 逐K回放"
            if (restore) lastStructure?.let {
                ChanlunChartRenderer.render(chart, it, 250, cbBi.isChecked, cbZs.isChecked, cbBsp.isChecked,
                    cbSP.isChecked, cbFib.isChecked, cbFx.isChecked)
                renderAuxCharts(obvChart, cycleChart, it.bars, ssaL)
            }
        }

        fun startReplay() {
            val bars = lastBars ?: return
            btnPlay.text = "⏹ 停止"
            logTv.text = ""
            var idx = 40.coerceAtMost(bars.size - 1)   // 回放起点：前 40 根做结构暖机
            var prevFx = 0; var prevBi = 0; var prevZs = 0; var prevBsp = 0; var prevLastSure = true
            replayJob = viewLifecycleOwner.lifecycleScope.launch {
                while (isActive && idx < bars.size) {
                    while (isActive && replayPaused) delay(120)   // 暂停挂起，继续后从当前根接着走
                    if (!isActive) break
                    val sub = bars.subList(0, idx + 1)
                    val st = withContext(kotlinx.coroutines.Dispatchers.Default) {
                        runCatching { com.chin.stockanalysis.strategy.chanlun.ChanlunEngine.compute(sub) }.getOrNull()
                    }
                    if (st != null) {
                        ChanlunChartRenderer.render(chart, st, 250, cbBi.isChecked, cbZs.isChecked,
                            cbBsp.isChecked, cbSP.isChecked, cbFib.isChecked, cbFx.isChecked)
                        renderAuxCharts(obvChart, cycleChart, st.bars, ssaL)
                        val bar = bars[idx]
                        tvProg.text = "${idx + 1}/${bars.size}  ${bar.time}  收${fmt2(bar.close)}"
                        // ── 结构事件 diff：新分型 / 新笔 / 笔确认 / 新中枢 / 新买卖点 ──
                        val sbEvt = StringBuilder()
                        if (st.fx.size > prevFx) {
                            for (k in prevFx until st.fx.size) {
                                val f = st.fx[k]
                                sbEvt.append(if (f.type == com.chin.stockanalysis.strategy.chanlun.ChanlunEngine.FxType.TOP)
                                    "▽ 顶分型 ${fmt2(f.price)}（${f.time}）\n" else "△ 底分型 ${fmt2(f.price)}（${f.time}）\n")
                            }
                            prevFx = st.fx.size
                        }
                        if (st.bi.size > prevBi) {
                            for (k in prevBi until st.bi.size) {
                                val b = st.bi[k]
                                val dir = if (b.dir == com.chin.stockanalysis.strategy.chanlun.ChanlunEngine.Dir.UP) "↑上涨笔" else "↓下跌笔"
                                sbEvt.append("✏ 第${k + 1}笔成立 $dir ${fmt2(b.beginPrice)}→${fmt2(b.endPrice)}${if (b.sure) "" else "（虚笔）"}\n")
                            }
                            prevBi = st.bi.size
                            prevLastSure = st.bi.lastOrNull()?.sure ?: true
                        } else {
                            val lb = st.bi.lastOrNull()
                            if (lb != null && !prevLastSure && lb.sure) { sbEvt.append("✔ 第${st.bi.size}笔确认（虚转实）\n"); prevLastSure = true }
                        }
                        if (st.zs.size > prevZs) {
                            val z = st.zs.last()
                            sbEvt.append("▭ 中枢${st.zs.size}形成 [${fmt2(z.low)} ~ ${fmt2(z.high)}]\n")
                            prevZs = st.zs.size
                        }
                        if (st.bsp.size > prevBsp) {
                            for (k in prevBsp until st.bsp.size) {
                                val p = st.bsp[k]
                                sbEvt.append((if (p.isBuy) "🔴 ${p.type}买" else "🟢 ${p.type}卖") +
                                    (if (p.sure) "" else "（虚）") + " @${fmt2(p.price)}（${p.time}）\n")
                            }
                            prevBsp = st.bsp.size
                        }
                        if (sbEvt.isNotEmpty()) {
                            logTv.append(sbEvt.toString())
                            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
                        }
                    }
                    idx++
                    delay(replaySpeedMs)
                }
                if (idx >= bars.size) { stopReplay(false); tvProg.text = "回放完成 ${bars.size}/${bars.size}" }
            }
        }

        btnPlay.setOnClickListener {
            val bars = lastBars
            if (replayJob != null) { stopReplay(true); status.text = "已停止回放（恢复完整结构图）"; return@setOnClickListener }
            if (bars == null || bars.size < 60) { status.text = "请先点「分析」加载K线（需≥60根）"; return@setOnClickListener }
            startReplay()
        }
        btnPause.setOnClickListener {
            if (replayJob == null) return@setOnClickListener   // 未在回放时无效
            replayPaused = !replayPaused
            btnPause.text = if (replayPaused) "▶ 继续" else "⏸ 暂停"
            tvProg.text = if (replayPaused) "已暂停（点「▶ 继续」接着走）" else tvProg.text
        }
        btnReset.setOnClickListener {
            val wasPlaying = replayJob != null
            stopReplay(false); logTv.text = ""; tvProg.text = "已重置到起点"
            if (wasPlaying) startReplay()
        }
        btnSpeed.setOnClickListener {
            replaySpeedMs = when (replaySpeedMs) { 1200L -> 500L; 500L -> 200L; 200L -> 80L; else -> 1200L }
            btnSpeed.text = "速度:" + when (replaySpeedMs) { 1200L -> "慢"; 500L -> "中"; 200L -> "快"; else -> "极快" }
        }

        val cardBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(cardBox)
        scroll.addView(box)

        // 重渲染辅助
        fun rerender() {
            val st = lastStructure ?: return
            ChanlunChartRenderer.render(chart, st, 250, cbBi.isChecked, cbZs.isChecked, cbBsp.isChecked,
                cbSP.isChecked, cbFib.isChecked, cbFx.isChecked)
            renderAuxCharts(obvChart, cycleChart, st.bars, ssaL)
        }
        cbBi.setOnCheckedChangeListener { _, _ -> rerender() }
        cbZs.setOnCheckedChangeListener { _, _ -> rerender() }
        cbBsp.setOnCheckedChangeListener { _, _ -> rerender() }
        cbSP.setOnCheckedChangeListener { _, _ -> rerender() }
        cbFib.setOnCheckedChangeListener { _, _ -> rerender() }
        cbFx.setOnCheckedChangeListener { _, _ -> rerender() }

        btn.setOnClickListener {
            singleCode = codeEt.text.toString().trim()
            if (singleCode.isEmpty()) return@setOnClickListener
            btn.isEnabled = false
            status.text = "计算中…"
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val bars = fetchBars(singleCode, singleLv)
                    if (bars.size < 30) { status.text = "数据不足（${bars.size}根）"; btn.isEnabled = true; return@launch }
                    val st = withContext(kotlinx.coroutines.Dispatchers.Default) {
                        com.chin.stockanalysis.strategy.chanlun.ChanlunEngine.compute(bars)
                    }
                    lastStructure = st
                    lastBars = bars
                    rerender()
                    renderStatusCard(cardBox, st)
                    status.text = "${bars.size}根K线  笔${st.bi.size} 中枢${st.zs.size} 买卖点${st.bsp.size}"
                } catch (e: Exception) {
                    status.text = "失败: ${e.message}"
                }
                btn.isEnabled = true
            }
        }
        return scroll
    }

    /** 副图：① OBV 能量潮（亿） ② SSA cycle 分量（嵌入窗口 L 可选 30/60）。bars 为当前显示窗口（回放时=截断序列，无未来） */
    private fun renderAuxCharts(obvChart: com.github.mikephil.charting.charts.LineChart,
                                cycleChart: com.github.mikephil.charting.charts.LineChart,
                                bars: List<com.chin.stockanalysis.strategy.chanlun.ChanlunEngine.Bar>,
                                ssaL: Int) {
        val n0 = bars.size
        val show = minOf(250, n0)
        val off = n0 - show

        // ── ① OBV ──
        val obv = DoubleArray(n0)
        for (i in 1 until n0) {
            val v = bars[i].volume
            obv[i] = obv[i - 1] + (if (bars[i].close > bars[i - 1].close) v
                                   else if (bars[i].close < bars[i - 1].close) -v else 0.0)
        }
        val obvE = ArrayList<com.github.mikephil.charting.data.Entry>(show)
        for (i in 0 until show) obvE.add(com.github.mikephil.charting.data.Entry(i.toFloat(), (obv[off + i] / 1e8).toFloat()))
        val obvDs = com.github.mikephil.charting.data.LineDataSet(obvE, "OBV(亿)").apply {
            color = BLUE; lineWidth = 1.3f; setDrawCircles(false); setDrawValues(false)
        }
        obvChart.apply {
            description.isEnabled = false; legend.isEnabled = false
            setTouchEnabled(false); setDrawGridBackground(false)
            data = com.github.mikephil.charting.data.LineData(obvDs)
            xAxis.apply { setDrawLabels(false); setDrawGridLines(false); axisMinimum = -0.5f; axisMaximum = show - 0.5f }
            axisLeft.apply { textColor = GRAY; textSize = 8f; setDrawGridLines(false) }
            axisRight.isEnabled = false
            setViewPortOffsets(dp(34).toFloat(), dp(2).toFloat(), dp(8).toFloat(), dp(2).toFloat())
            invalidate()
        }

        // ── ② SSA cycle ──
        cycleChart.clear()
        if (n0 > ssaL + 5) {
            val closes = DoubleArray(n0) { bars[it].close }
            val cyc = runCatching { com.chin.stockanalysis.strategy.ssa.SsaEngine.decompose(closes, ssaL).cycle }.getOrNull()
            if (cyc != null) {
                val cycE = ArrayList<com.github.mikephil.charting.data.Entry>(show)
                for (i in 0 until show) {
                    val v = cyc[off + i]
                    if (!v.isNaN()) cycE.add(com.github.mikephil.charting.data.Entry(i.toFloat(), v.toFloat()))
                }
                val cycDs = com.github.mikephil.charting.data.LineDataSet(cycE, "cycle L=$ssaL").apply {
                    color = TEAL; lineWidth = 1.4f; setDrawCircles(false); setDrawValues(false)
                }
                cycleChart.apply {
                    description.isEnabled = false; legend.isEnabled = false
                    setTouchEnabled(false); setDrawGridBackground(false)
                    data = com.github.mikephil.charting.data.LineData(cycDs)
                    xAxis.apply { setDrawLabels(false); setDrawGridLines(false); axisMinimum = -0.5f; axisMaximum = show - 0.5f }
                    axisLeft.apply { textColor = GRAY; textSize = 8f; setDrawGridLines(false) }
                    axisRight.isEnabled = false
                    axisLeft.removeAllLimitLines()
                    axisLeft.addLimitLine(com.github.mikephil.charting.components.LimitLine(0f).apply {
                        lineColor = GRAY; lineWidth = 0.8f
                    })
                    setViewPortOffsets(dp(34).toFloat(), dp(2).toFloat(), dp(8).toFloat(), dp(2).toFloat())
                    invalidate()
                }
            }
        }
    }

    private fun renderStatusCard(box: LinearLayout, st: com.chin.stockanalysis.strategy.chanlun.ChanlunEngine.Structure) {
        box.removeAllViews()
        val bi = st.bi.lastOrNull() ?: return
        val cur = bi.endPrice
        val dirTxt = if (bi.dir == com.chin.stockanalysis.strategy.chanlun.ChanlunEngine.Dir.UP) "↑向上笔" else "↓向下笔"
        val sureTxt = if (bi.sure) "" else "（虚笔待确认）"
        box.addView(tv("当前: $dirTxt$sureTxt  价 ${fmt2(cur)}", Color.parseColor("#333333"), 13f, true))

        val z = st.zs.lastOrNull()
        if (z != null) {
            val pos = when {
                cur > z.high -> "中枢上方"
                cur < z.low -> "中枢下方"
                else -> "中枢内"
            }
            box.addView(tv("中枢[${fmt2(z.low)}, ${fmt2(z.high)}]  位于$pos", BLUE, 12f))
        }
        val p = st.bsp.lastOrNull()
        if (p != null) {
            val side = if (p.isBuy) "买" else "卖"
            val c = if (p.isBuy) RED else GREEN
            box.addView(tv("最近信号: ${p.type}$side @${fmt2(p.price)} ${p.time}${if (p.sure) "" else "（待确认）"}", c, 12f))
        }
    }

    private fun openSingle(code: String) {
        if (code != singleCode) pageViews[1] = null   // 换了票：废弃单股页缓存，重建为新代码
        singleCode = code
        switchSubMode(1)
    }

    // ── 数据辅助 ──

    /** 核心池代码列表（去 sh/sz 前缀） */
    private fun loadCorePool(): List<String> = try {
        val j = readAssetJson("core_stock_pool.json") ?: return emptyList()
        val arr = j.optJSONArray("stocks") ?: return emptyList()
        (0 until arr.length()).map { arr.getString(it).removePrefix("sh").removePrefix("sz") }
    } catch (_: Exception) { emptyList() }

    /** 拉K线 → ChanlunEngine.Bar；lv="1h" 走小时线缓存，"day" 由 1H 聚合 */
    private suspend fun fetchBars(code: String, lv: String): List<com.chin.stockanalysis.strategy.chanlun.ChanlunEngine.Bar> =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val full = if (code.startsWith("sh") || code.startsWith("sz")) code
            else (if (code[0] in "659") "sh" else "sz") + code
            val entities = com.chin.stockanalysis.strategy.data.IntradayKlineFetcher(requireContext())
                .fetchHourlyHistory(full)
            val bars = entities.map {
                com.chin.stockanalysis.strategy.chanlun.ChanlunEngine.Bar(
                    it.datetime, it.open, it.high, it.low, it.close, it.volume.toDouble())
            }
            if (lv == "day") aggregateDaily(bars) else bars
        }

    /** 1H → 日线聚合（按日期分组） */
    private fun aggregateDaily(bars: List<com.chin.stockanalysis.strategy.chanlun.ChanlunEngine.Bar>)
        : List<com.chin.stockanalysis.strategy.chanlun.ChanlunEngine.Bar> {
        if (bars.isEmpty()) return emptyList()
        val out = ArrayList<com.chin.stockanalysis.strategy.chanlun.ChanlunEngine.Bar>()
        var day = bars[0].time.substring(0, 10)
        var o = bars[0].open; var h = bars[0].high; var l = bars[0].low; var c = bars[0].close
        var v = bars[0].volume
        for (i in 1 until bars.size) {
            val b = bars[i]; val d = b.time.substring(0, 10)
            if (d == day) {
                h = maxOf(h, b.high); l = minOf(l, b.low); c = b.close; v += b.volume
            } else {
                out.add(com.chin.stockanalysis.strategy.chanlun.ChanlunEngine.Bar(day, o, h, l, c, v))
                day = d; o = b.open; h = b.high; l = b.low; c = b.close; v = b.volume
            }
        }
        out.add(com.chin.stockanalysis.strategy.chanlun.ChanlunEngine.Bar(day, o, h, l, c, v))
        return out
    }

    /** 📋 持仓池：共享做T持仓池（SsaT0PoolStore），本地引擎算缠论状态 */
    private fun buildPoolPage(): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(16)) }
        box.addView(tv("📋 缠论持仓池", 14f, true))
        box.addView(tv("共享三层稳态做T持仓池；风险：🟢安全/🟡观察/🔴警戒/⛔离场", GRAY, 10f))
        val btn = android.widget.Button(ctx).apply { text = "刷新缠论状态" }
        box.addView(btn)
        val tableBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(tableBox)
        scroll.addView(box)

        btn.setOnClickListener {
            btn.isEnabled = false
            tableBox.removeAllViews()
            tableBox.addView(tv("计算中…", GRAY, 11f))
            viewLifecycleOwner.lifecycleScope.launch {
                val members = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.chin.stockanalysis.strategy.ssa.SsaT0PoolStore.load(ctx)
                }
                tableBox.removeAllViews()
                if (members.isEmpty()) {
                    tableBox.addView(tv("持仓池为空（在做T页添加）", GRAY, 12f))
                    btn.isEnabled = true; return@launch
                }
                // 表头
                tableBox.addView(tv("代码    笔向  中枢位置    最近信号    风险", GRAY, 10f, true))
                for (m in members) {
                    try {
                        val bars = fetchBars(m.code, "1h")
                        if (bars.size < 30) { tableBox.addView(tv("${m.code} 数据不足", GRAY, 11f)); continue }
                        val st = withContext(kotlinx.coroutines.Dispatchers.Default) {
                            com.chin.stockanalysis.strategy.chanlun.ChanlunEngine.compute(bars)
                        }
                        tableBox.addView(poolChanlunRow(m.code, m.name, st))
                    } catch (e: Exception) {
                        tableBox.addView(tv("${m.code} ${e.message}", GRAY, 11f))
                    }
                }
                btn.isEnabled = true
            }
        }
        return scroll
    }

    /** 持仓池行：代码+笔向+中枢位置+最近信号+风险分级 */
    private fun poolChanlunRow(code: String, name: String,
                               st: com.chin.stockanalysis.strategy.chanlun.ChanlunEngine.Structure): View {
        val bi = st.bi.lastOrNull()
        val cur = bi?.endPrice ?: 0.0
        val dirTxt = if (bi?.dir == com.chin.stockanalysis.strategy.chanlun.ChanlunEngine.Dir.UP) "↑" else "↓"
        val z = st.zs.lastOrNull()
        val zPos = if (z == null) "-" else when {
            cur > z.high -> "上方"; cur < z.low -> "下方"; else -> "内"
        }
        // 最近卖点（只找卖）
        val lastSell = st.bsp.lastOrNull { !it.isBuy }
        val sellTxt = if (lastSell != null) "${lastSell.type}卖@${fmt2(lastSell.price)}" else "无"
        // 风险分级
        val risk = when {
            lastSell != null && lastSell.type.startsWith("3") -> "⛔" to Color.parseColor("#B71C1C")
            lastSell != null && lastSell.type == "1" && lastSell.sure -> "🔴" to RED
            lastSell != null && lastSell.type == "1" -> "🟡" to ORANGE
            else -> "🟢" to GREEN
        }
        val t = "$code $name  $dirTxt  中枢$zPos  $sellTxt  ${risk.first}"
        return tv(t, risk.second, 11f).apply {
            setPadding(0, dp(5), 0, dp(5))
            setOnClickListener { openSingle(code) }
        }
    }

    /** 📈 回测：本地引擎增量回放（每5根重算，避免未来信息） */
    private var btCode = "600188"
    private fun buildBacktestPage(): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(16)) }
        box.addView(tv("📈 缠论回测", 14f, true))
        box.addView(tv("增量回放：每5根重算结构，实买点买→实卖点卖，无未来信息", GRAY, 10f))

        val inputRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val codeEt = android.widget.EditText(ctx).apply {
            setText(btCode); textSize = 13f; inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val btn = android.widget.Button(ctx).apply { text = "回测" }
        inputRow.addView(codeEt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f))
        inputRow.addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(inputRow)

        // 滚动预测验证按钮
        val btnRoll = android.widget.Button(ctx).apply { text = "🔁 滚动预测验证（每10根推演一次，验证后10根方向）" }
        box.addView(btnRoll)

        val status = tv("", GRAY, 10f)
        box.addView(status)

        // ── 买卖点图 + 点击交易卡片（回测完成后显示） ──
        val btChart = CombinedChart(ctx).apply { visibility = View.GONE }
        box.addView(btChart, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(300)))
        val tradeCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
        }
        box.addView(tradeCard)

        val resultBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(resultBox)
        scroll.addView(box)

        btn.setOnClickListener {
            btCode = codeEt.text.toString().trim()
            btn.isEnabled = false; resultBox.removeAllViews()
            btChart.visibility = View.GONE; tradeCard.visibility = View.GONE
            status.text = "回测中（约10-30秒）…"
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val r = runBacktest(btCode, "1h")
                    status.text = ""
                    if (r == null) { status.text = "数据不足"; btn.isEnabled = true; return@launch }
                    val sumColor = when {
                        r.totalRet > 0 -> RED; r.totalRet < 0 -> GREEN; else -> Color.parseColor("#333333")
                    }
                    resultBox.addView(tv("交易${r.trades}笔  胜率${"%.0f".format(r.winRate)}%  总收益${"%+.1f".format(r.totalRet)}%  最大回撤${"%.1f".format(r.maxDd)}%",
                        sumColor, 13f, true))
                    if (r.tradeList.isNotEmpty()) {
                        btChart.visibility = View.VISIBLE
                        resultBox.addView(tv("👆 点击图上买卖点查看该笔交易明细", GRAY, 10f))
                        renderBtChart(btChart, tradeCard, r)
                    }
                    for (t in r.details.takeLast(15).reversed()) {
                        resultBox.addView(tv(t, if (t.contains("+")) RED else Color.parseColor("#333333"), 10f))
                    }
                } catch (e: Exception) { status.text = "失败: ${e.message}" }
                btn.isEnabled = true
            }
        }

        btnRoll.setOnClickListener {
            btCode = codeEt.text.toString().trim()
            btnRoll.isEnabled = false; resultBox.removeAllViews()
            btChart.visibility = View.GONE; tradeCard.visibility = View.GONE
            status.text = "滚动验证中（约20-60秒）…"
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val r = runRollingPredict(btCode, "1h")
                    status.text = ""
                    if (r == null) { status.text = "数据不足（需≥130根）"; btnRoll.isEnabled = true; return@launch }
                    val hitPct = if (r.total > 0) r.hit * 100.0 / r.total else 0.0
                    resultBox.addView(tv("滚动预测 ${r.total} 次 · 方向命中 ${r.hit} 次（${"%.0f".format(hitPct)}%）",
                        hitColor(hitPct), 13f, true))
                    resultBox.addView(tv("只跟「上涨」情景持有10根：累计 ${"%+.1f".format(r.followRet)}% · 最大回撤 ${"%.1f".format(r.followDd)}%",
                        if (r.followRet > 0) RED else GREEN, 11f, true))
                    resultBox.addView(tv("按情景命中率：", GRAY, 11f, true))
                    for (s in r.byName) resultBox.addView(tv("  $s", Color.parseColor("#333333"), 11f))
                    resultBox.addView(tv("最近明细：", GRAY, 11f, true))
                    for (d in r.details) resultBox.addView(tv(d, Color.parseColor("#555555"), 10f))
                } catch (e: Exception) { status.text = "失败: ${e.message}" }
                btnRoll.isEnabled = true
            }
        }
        return scroll
    }

    private fun hitColor(pct: Double) = when {
        pct >= 55 -> RED; pct >= 45 -> Color.parseColor("#E65100"); else -> GREEN
    }

    private data class RollResult(val total: Int, val hit: Int, val byName: List<String>,
                                  val followRet: Double, val followDd: Double, val details: List<String>)

    /**
     * 滚动预测验证：从第100根起，每10根用历史数据重算结构并推演3情景，
     * 取概率最高的情景，用随后10根实际涨跌判定方向命中（±0.5% 内算盘整）。
     * 全程只用 t 时刻之前的数据，无未来信息。
     */
    private suspend fun runRollingPredict(code: String, lv: String): RollResult? =
        withContext(kotlinx.coroutines.Dispatchers.Default) {
            val bars = fetchBars(code, lv)
            if (bars.size < 130) return@withContext null
            val step = 10; val horizon = 10; val th = 0.005
            var total = 0; var hit = 0
            var eq = 1.0; var peak = 1.0; var dd = 0.0
            val nameStat = LinkedHashMap<String, IntArray>()   // 情景名 -> [总次数, 命中次数]
            val details = ArrayList<String>()
            var t = 100
            while (t + horizon < bars.size) {
                val st = com.chin.stockanalysis.strategy.chanlun.ChanlunEngine.compute(bars.subList(0, t + 1))
                val sc = com.chin.stockanalysis.strategy.chanlun.ChanlunPredictor.predict(st).firstOrNull()
                if (sc != null && sc.prob > 0 && sc.name != "数据不足") {
                    val cur = bars[t].close
                    val ret = bars[t + horizon].close / cur - 1
                    val actual = when {
                        ret > th -> com.chin.stockanalysis.strategy.chanlun.ChanlunPredictor.Dir3.UP
                        ret < -th -> com.chin.stockanalysis.strategy.chanlun.ChanlunPredictor.Dir3.DOWN
                        else -> com.chin.stockanalysis.strategy.chanlun.ChanlunPredictor.Dir3.RANGE
                    }
                    val ok = sc.dir == actual
                    total++; if (ok) hit++
                    val acc = nameStat.getOrPut(sc.name) { IntArray(2) }
                    acc[0]++; if (ok) acc[1]++
                    // 跟单模拟：只在看涨情景做多（A股无做空），其余空仓
                    if (sc.dir == com.chin.stockanalysis.strategy.chanlun.ChanlunPredictor.Dir3.UP) {
                        eq *= (1 + ret); peak = maxOf(peak, eq)
                        dd = maxOf(dd, (peak - eq) / peak)
                    }
                    val dirCn = mapOf(
                        com.chin.stockanalysis.strategy.chanlun.ChanlunPredictor.Dir3.UP to "涨",
                        com.chin.stockanalysis.strategy.chanlun.ChanlunPredictor.Dir3.DOWN to "跌",
                        com.chin.stockanalysis.strategy.chanlun.ChanlunPredictor.Dir3.RANGE to "盘")
                    details.add("${bars[t].time.take(10)} ${sc.name}→预${dirCn[sc.dir]} 实${dirCn[actual]} " +
                        "${if (ok) "✔" else "✘"} ${"%+.1f".format(ret * 100)}%")
                }
                t += step
            }
            val byName = nameStat.entries.map { (k, v) ->
                "$k  ${v[1]}/${v[0]}（${"%.0f".format(if (v[0] > 0) v[1] * 100.0 / v[0] else 0.0)}%）"
            }
            RollResult(total, hit, byName, (eq - 1) * 100, dd * 100, details.takeLast(10))
        }

    /** 一笔完整买卖（回测成交） */
    private data class BtTrade(
        val buyIdx: Int, val buyPrice: Double, val buyTime: String, val buyType: String,
        val sellIdx: Int, val sellPrice: Double, val sellTime: String, val sellType: String) {
        val ret get() = (sellPrice - buyPrice) / buyPrice
    }

    private data class BtResult(val trades: Int, val winRate: Double, val totalRet: Double,
                                val maxDd: Double, val details: List<String>,
                                val bars: List<com.chin.stockanalysis.strategy.chanlun.ChanlunEngine.Bar>,
                                val tradeList: List<BtTrade>)

    private suspend fun runBacktest(code: String, lv: String): BtResult? =
        withContext(kotlinx.coroutines.Dispatchers.Default) {
            val bars = fetchBars(code, lv)
            if (bars.size < 100) return@withContext null
            var holding = false; var buyPrice = 0.0; var buyTime = ""
            var buyIdx = 0; var buyType = ""
            var wins = 0; var losses = 0; var eq = 1.0; var peak = 1.0; var maxDd = 0.0
            val details = ArrayList<String>()
            val tradeList = ArrayList<BtTrade>()
            val seen = HashSet<Long>()
            var end = 60
            while (end <= bars.size) {
                val st = com.chin.stockanalysis.strategy.chanlun.ChanlunEngine.compute(bars.subList(0, end))
                for (p in st.bsp) {
                    if (!p.sure) continue
                    val key = p.idx * 2L + (if (p.isBuy) 1L else 0L)
                    if (!seen.add(key)) continue
                    if (p.isBuy && !holding) {
                        holding = true; buyPrice = p.price; buyTime = p.time
                        buyIdx = p.idx; buyType = p.type
                    } else if (!p.isBuy && holding) {
                        holding = false
                        val ret = (p.price - buyPrice) / buyPrice
                        eq *= (1 + ret); peak = maxOf(peak, eq)
                        maxDd = maxOf(maxDd, (peak - eq) / peak)
                        if (ret > 0) wins++ else losses++
                        val pct = "%.1f".format(ret * 100)
                        details.add("${buyTime.take(10)} 买${fmt2(buyPrice)} → ${p.time.take(10)} 卖${fmt2(p.price)}  ${if (ret >= 0) "+" else ""}$pct%")
                        tradeList.add(BtTrade(buyIdx, buyPrice, buyTime, buyType,
                            p.idx, p.price, p.time, p.type))
                    }
                }
                end += 5
            }
            val total = wins + losses
            BtResult(total, if (total > 0) wins * 100.0 / total else 0.0,
                (eq - 1) * 100, maxDd * 100, details, bars, tradeList)
        }

    /**
     * 回测买卖点图：K线 + 每笔买→卖连线（盈红亏绿）+ 买卖点散点。
     * 点击散点（Entry.data = 交易序号）弹出该笔交易卡片；点空白收起。
     */
    private fun renderBtChart(chart: CombinedChart, card: LinearLayout, r: BtResult) {
        val bars = r.bars
        val n = bars.size
        if (n < 5) return

        // ── K线蜡烛（禁用高亮：让点击只命中买卖点散点） ──
        val ces = ArrayList<CandleEntry>(n)
        for (i in 0 until n) {
            val b = bars[i]
            ces.add(CandleEntry(i.toFloat(), b.high.toFloat(), b.low.toFloat(),
                b.open.toFloat(), b.close.toFloat()))
        }
        val cds = CandleDataSet(ces, "K线").apply {
            color = GRAY; shadowColor = GRAY; shadowWidth = 0.8f
            increasingColor = RED; increasingPaintStyle = android.graphics.Paint.Style.FILL
            decreasingColor = GREEN; decreasingPaintStyle = android.graphics.Paint.Style.FILL
            neutralColor = GRAY; setDrawValues(false)
            isHighlightEnabled = false
        }

        // ── 买→卖连线（每笔一条，盈红亏绿） + 买卖点散点 ──
        val lineData = LineData()
        val buyE = ArrayList<Entry>(); val sellE = ArrayList<Entry>()
        r.tradeList.forEachIndexed { ti, t ->
            buyE.add(Entry(t.buyIdx.toFloat(), (t.buyPrice * 0.997).toFloat(), ti))
            sellE.add(Entry(t.sellIdx.toFloat(), (t.sellPrice * 1.003).toFloat(), ti))
            lineData.addDataSet(LineDataSet(arrayListOf(
                Entry(t.buyIdx.toFloat(), t.buyPrice.toFloat()),
                Entry(t.sellIdx.toFloat(), t.sellPrice.toFloat())), "t$ti").apply {
                color = if (t.ret >= 0) RED else GREEN
                lineWidth = 1.8f
                setDrawCircles(false); setDrawValues(false); isHighlightEnabled = false
            })
        }
        val scatterData = ScatterData()
        scatterData.addDataSet(ScatterDataSet(buyE, "买点").apply {
            color = RED; scatterShapeSize = 30f; setDrawValues(false)
            setScatterShape(ScatterChart.ScatterShape.CHEVRON_UP)
            highLightColor = Color.parseColor("#FF6F00")
        })
        scatterData.addDataSet(ScatterDataSet(sellE, "卖点").apply {
            color = GREEN; scatterShapeSize = 30f; setDrawValues(false)
            setScatterShape(ScatterChart.ScatterShape.CHEVRON_DOWN)
            highLightColor = Color.parseColor("#FF6F00")
        })

        val cd = CombinedData()
        cd.setData(CandleData(cds))
        if (lineData.dataSetCount > 0) cd.setData(lineData)
        cd.setData(scatterData)
        chart.data = cd

        chart.apply {
            description.isEnabled = false
            legend.isEnabled = true; legend.textSize = 10f
            setTouchEnabled(true); setScaleEnabled(true); isDragEnabled = true
            setPinchZoom(true); setDrawGridBackground(false)
            setMaxVisibleValueCount(60)
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false); granularity = 1f
                textColor = GRAY; textSize = 8f
                axisMinimum = -0.5f; axisMaximum = (n - 1) + 3.5f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        val i = value.toInt()
                        if (i < 0 || i >= n) return ""
                        val t = bars[i].time
                        return if (i % maxOf(1, n / 6) == 0) t.substring(0, minOf(10, t.length)) else ""
                    }
                }
            }
            axisLeft.apply { textColor = GRAY; textSize = 9f; setDrawGridLines(true); gridColor = Color.parseColor("#EEEEEE") }
            axisRight.isEnabled = false
            setVisibleXRangeMaximum(160f)
            setVisibleXRangeMinimum(15f)
            moveViewToX(maxOf(0f, (n - 160).toFloat()))

            setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
                override fun onValueSelected(e: Entry?, h: Highlight?) {
                    val ti = (e?.data as? Int) ?: return
                    showTradeCard(card, r, ti)
                }
                override fun onNothingSelected() { card.visibility = View.GONE }
            })
            invalidate()
        }
    }

    /** 某笔交易的明细卡片：买点/卖点/收益/持有期 */
    private fun showTradeCard(card: LinearLayout, r: BtResult, ti: Int) {
        val t = r.tradeList.getOrNull(ti) ?: return
        card.removeAllViews()
        card.visibility = View.VISIBLE
        val win = t.ret >= 0
        val edge = if (win) RED else GREEN
        card.background = GradientDrawable().apply {
            setColor(Color.WHITE); cornerRadius = dp(8).toFloat(); setStroke(dp(2), edge)
        }
        card.setPadding(dp(10), dp(8), dp(10), dp(8))
        (card.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(6)
        fun cn(tp: String) = mapOf("1" to "一", "2" to "二", "3a" to "三", "3b" to "类三")[tp] ?: tp
        card.addView(tv("第${ti + 1}/${r.tradeList.size}笔  ${if (win) "盈" else "亏"} ${"%+.1f".format(t.ret * 100)}%",
            edge, 14f, true))
        card.addView(tv("🔴 ${cn(t.buyType)}买  ${t.buyTime}  @${fmt2(t.buyPrice)}",
            Color.parseColor("#333333"), 11f))
        card.addView(tv("🟢 ${cn(t.sellType)}卖  ${t.sellTime}  @${fmt2(t.sellPrice)}",
            Color.parseColor("#333333"), 11f))
        card.addView(tv("持有 ${t.sellIdx - t.buyIdx} 根K线 · 买卖点可双指缩放定位", GRAY, 10f))
    }

    /** 🔮 预测：本地引擎 3 情景推演 */
    private var pdCode = "600188"
    private fun buildPredictPage(): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(16)) }
        box.addView(tv("🔮 缠论预测", 14f, true))
        box.addView(tv("基于当前结构推演 3 情景（延续/转折/盘整），存本地供验证", GRAY, 10f))

        val inputRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val codeEt = android.widget.EditText(ctx).apply {
            setText(pdCode); textSize = 13f; inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val btn = android.widget.Button(ctx).apply { text = "预测" }
        inputRow.addView(codeEt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f))
        inputRow.addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(inputRow)

        val status = tv("", GRAY, 10f)
        box.addView(status)
        val infoTv = tv("", Color.parseColor("#1976D2"), 11f, true)
        box.addView(infoTv)
        val chart = CombinedChart(ctx)
        box.addView(chart, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(260)))
        val resultBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(resultBox)
        scroll.addView(box)

        btn.setOnClickListener {
            pdCode = codeEt.text.toString().trim()
            btn.isEnabled = false; status.text = "计算中…"; resultBox.removeAllViews(); infoTv.text = ""
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val bars = fetchBars(pdCode, "1h")
                    if (bars.size < 60) { status.text = "数据不足"; btn.isEnabled = true; return@launch }
                    val st = withContext(kotlinx.coroutines.Dispatchers.Default) {
                        com.chin.stockanalysis.strategy.chanlun.ChanlunEngine.compute(bars)
                    }
                    val sc = predictScenarios(st)
                    val base = bars.last()
                    val until = estimateUntil(bars, PRED_HORIZON)
                    savePrediction(pdCode, base.time, base.close, bars.size - 1, sc)
                    status.text = "已存本地（验证页事后核对）"
                    infoTv.text = "当前价 ${fmt2(base.close)}  ·  ${base.time}\n" +
                        "预测有效期：未来 ${PRED_HORIZON} 根1H（约至 $until）"
                    renderPredictChart(chart, bars, sc)
                    for ((i, s) in sc.withIndex()) {
                        resultBox.addView(tv("情景${i + 1} ${s.name}  概率${s.prob}%  目标 ${s.target}",
                            if (i == 0) RED else Color.parseColor("#333333"), 12f, i == 0).apply { setPadding(0, dp(6), 0, 0) })
                        resultBox.addView(tv("   ${s.desc}", GRAY, 10f))
                    }
                } catch (e: Exception) { status.text = "失败: ${e.message}" }
                btn.isEnabled = true
            }
        }
        return scroll
    }

    /** 预测有效期（1H 根数），与验证页判定阈值保持一致 */
    private val PRED_HORIZON = 10

    /** 用最近K线的实际时间间隔外推有效期终点（自动跳过夜间/休市） */
    private fun estimateUntil(
        bars: List<com.chin.stockanalysis.strategy.chanlun.ChanlunEngine.Bar>, horizon: Int
    ): String {
        if (bars.size < 2) return "-"
        val pats = listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd")
        fun parse(s: String): java.util.Date? {
            for (p in pats) try {
                return java.text.SimpleDateFormat(p, java.util.Locale.CHINA).parse(s)
            } catch (_: Exception) {}
            return null
        }
        val take = minOf(20, bars.size)
        val times = bars.takeLast(take).mapNotNull { parse(it.time) }
        if (times.size < 2) return "-"
        val span = (times.last().time - times.first().time).toDouble()
        val avg = span / (times.size - 1)
        val end = java.util.Date(times.last().time + (avg * horizon).toLong())
        return java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(end)
    }

    private data class Scenario(val name: String, val prob: Int, val target: String, val desc: String)

    /** 预测情景图：最近60根K线 + 3条情景目标线（向右延伸） */
    private fun renderPredictChart(chart: CombinedChart,
                                   bars: List<com.chin.stockanalysis.strategy.chanlun.ChanlunEngine.Bar>,
                                   sc: List<Scenario>) {
        val show = minOf(60, bars.size)
        val off = bars.size - show
        val future = 12   // 向右延伸的推演空间
        val entries = ArrayList<com.github.mikephil.charting.data.CandleEntry>()
        val labels = ArrayList<String>()
        for (i in 0 until show) {
            val b = bars[off + i]
            labels.add(b.time)
            entries.add(com.github.mikephil.charting.data.CandleEntry(
                i.toFloat(), b.high.toFloat(), b.low.toFloat(), b.open.toFloat(), b.close.toFloat()))
        }
        val cds = com.github.mikephil.charting.data.CandleDataSet(entries, "K线").apply {
            color = GRAY; shadowColor = GRAY; shadowWidth = 0.8f
            increasingColor = RED; increasingPaintStyle = android.graphics.Paint.Style.FILL
            decreasingColor = GREEN; decreasingPaintStyle = android.graphics.Paint.Style.FILL
            neutralColor = GRAY; setDrawValues(false)
        }
        val lineData = com.github.mikephil.charting.data.LineData()
        // 当前价参考线（灰色实线）
        val cur = bars.last().close.toFloat()
        lineData.addDataSet(com.github.mikephil.charting.data.LineDataSet(
            arrayListOf(com.github.mikephil.charting.data.Entry(0f, cur),
                com.github.mikephil.charting.data.Entry((show + future).toFloat(), cur)), "cur").apply {
            color = GRAY; lineWidth = 1f; setDrawCircles(false); setDrawValues(false)
        })
        // 情景目标线：从最新K线向右延伸
        val scColors = intArrayOf(RED, ORANGE, BLUE)
        val x0 = (show - 1).toFloat(); val x1 = (show - 1 + future).toFloat()
        for ((i, s) in sc.take(3).withIndex()) {
            // target 可能是 "20.85" 或 "19.20~21.05"（取区间中点）
            val tv_ = s.target.replace("~", " ").split(" ")
                .mapNotNull { it.toDoubleOrNull() }
            if (tv_.isEmpty()) continue
            val tp = tv_.average().toFloat()
            lineData.addDataSet(com.github.mikephil.charting.data.LineDataSet(
                arrayListOf(com.github.mikephil.charting.data.Entry(x0, cur),
                        com.github.mikephil.charting.data.Entry(x1, tp)), "s$i").apply {
                color = scColors[i % scColors.size]
                lineWidth = if (i == 0) 2f else 1.2f
                enableDashedLine(10f, 5f, 0f)
                setDrawCircles(false); setDrawValues(false)
            })
        }
        val cd = com.github.mikephil.charting.data.CombinedData()
        cd.setData(com.github.mikephil.charting.data.CandleData(cds))
        cd.setData(lineData)
        chart.data = cd
        chart.apply {
            description.isEnabled = false; legend.isEnabled = false
            setTouchEnabled(true); setScaleEnabled(true); isDragEnabled = true
            setPinchZoom(true); setDrawGridBackground(false)
            xAxis.apply {
                position = com.github.mikephil.charting.components.XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false); textColor = GRAY; textSize = 8f
                axisMinimum = 0f; axisMaximum = (show + future).toFloat()
                valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        val i = value.toInt()
                        if (i < 0 || i >= show) return ""
                        val t = labels[i]
                        return if (i % 15 == 0) t.substring(5, minOf(10, t.length)) else ""
                    }
                }
            }
            axisLeft.textColor = GRAY; axisLeft.textSize = 9f
            axisRight.isEnabled = false
            setVisibleXRangeMaximum((show + future).toFloat())
            moveViewToX(0f); invalidate()
        }
    }

    /** 3 情景推演：按价格与中枢位置关系 */
    private fun predictScenarios(st: com.chin.stockanalysis.strategy.chanlun.ChanlunEngine.Structure): List<Scenario> =
        com.chin.stockanalysis.strategy.chanlun.ChanlunPredictor.predict(st)
            .map { Scenario(it.name, it.prob, it.targetText, it.desc) }

    /** 预测存本地 SharedPreferences（验证页用） */
    private fun savePrediction(code: String, date: String, price: Double, barIdx: Int, sc: List<Scenario>) {
        try {
            val sp = requireContext().getSharedPreferences("chanlun_preds", 0)
            val arr = try { org.json.JSONArray(sp.getString("list", "[]")) } catch (_: Exception) { org.json.JSONArray() }
            arr.put(org.json.JSONObject().apply {
                put("code", code); put("date", date); put("price", price); put("bar_idx", barIdx)
                put("top1", sc.firstOrNull()?.name ?: ""); put("target", sc.firstOrNull()?.target ?: "")
            })
            sp.edit().putString("list", arr.toString()).apply()
        } catch (_: Exception) {}
    }

    /** ✅ 验证：核对本地预测与实际走势 */
    private fun buildVerifyPage(): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(16)) }
        box.addView(tv("✅ 预测验证", 14f, true))
        box.addView(tv("对本地预测记录做事后验证（预测后满10根K线判定）", GRAY, 10f))
        val btn = android.widget.Button(ctx).apply { text = "刷新验证" }
        box.addView(btn)
        val resultBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(resultBox)
        scroll.addView(box)

        btn.setOnClickListener {
            btn.isEnabled = false; resultBox.removeAllViews()
            viewLifecycleOwner.lifecycleScope.launch {
                resultBox.addView(tv("验证中…", GRAY, 11f))
                val lines = verifyPredictions()
                resultBox.removeAllViews()
                if (lines.isEmpty()) resultBox.addView(tv("暂无预测记录（先到预测页生成）", GRAY, 12f))
                else for (l in lines) resultBox.addView(tv(l, Color.parseColor("#333333"), 11f).apply { setPadding(0, dp(4), 0, 0) })
                btn.isEnabled = true
            }
        }
        return scroll
    }

    private suspend fun verifyPredictions(): List<String> = withContext(kotlinx.coroutines.Dispatchers.Default) {
        val sp = requireContext().getSharedPreferences("chanlun_preds", 0)
        val arr = try { org.json.JSONArray(sp.getString("list", "[]")) } catch (_: Exception) { return@withContext emptyList() }
        val out = ArrayList<String>()
        var hit = 0; var total = 0
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val code = p.getString("code")
            try {
                val bars = fetchBars(code, "1h")
                val baseIdx = p.getInt("bar_idx")
                val horizon = bars.size - 1 - baseIdx
                val basePrice = p.getDouble("price")
                val curPrice = bars.last().close
                val ret = (curPrice - basePrice) / basePrice * 100
                val top1 = p.getString("top1")
                if (horizon >= 10) {
                    total++
                    val predUp = top1.contains("上涨") || top1.contains("突破") || top1.contains("反转") || top1.contains("反弹")
                    val actualUp = curPrice > basePrice
                    val ok = predUp == actualUp
                    if (ok) hit++
                    out.add("$code ${p.getString("date").take(10)} 预测[$top1] 实际${if (actualUp) "涨" else "跌"}${"%.1f".format(ret)}%  ${if (ok) "✔命中" else "✘"}")
                } else {
                    out.add("$code ${p.getString("date").take(10)} 预测[$top1] ⏳未满10根（已${horizon}根）")
                }
            } catch (e: Exception) { out.add("$code 验证失败 ${e.message}") }
        }
        if (total > 0) out.add(0, "—— 命中率 $hit/$total = ${"%.0f".format(hit * 100.0 / total)}% ——")
        out
    }

    // ═══════════════════ 工具 ═══════════════════

    private fun tv(text: String, size: Float, bold: Boolean = false): TextView =
        TextView(requireContext()).apply {
            this.text = text; textSize = size
            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
        }

    private fun tv(text: String, color: Int, size: Float): TextView =
        TextView(requireContext()).apply {
            this.text = text; textSize = size; setTextColor(color)
        }

    private fun tv(text: String, color: Int, size: Float, bold: Boolean): TextView =
        TextView(requireContext()).apply {
            this.text = text; textSize = size; setTextColor(color)
            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    /** 读 assets JSON，失败返回 null */
    private fun readAssetJson(path: String): JSONObject? = try {
        JSONObject(requireContext().assets.open(path).bufferedReader().use { it.readText() })
    } catch (_: Exception) { null }
}
