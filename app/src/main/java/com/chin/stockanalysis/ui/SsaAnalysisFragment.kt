package com.chin.stockanalysis.ui

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
import com.chin.stockanalysis.strategy.ssa.SsaEngine
import com.chin.stockanalysis.strategy.ssa.SsaEngine.Bar
import com.chin.stockanalysis.strategy.ssa.SsaEngine.Params
import com.chin.stockanalysis.strategy.ssa.SsaEngine.Result
import com.chin.stockanalysis.stock.database.StockDatabase
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.CombinedChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.CandleData
import com.github.mikephil.charting.data.CandleDataSet
import com.github.mikephil.charting.data.CandleEntry
import com.github.mikephil.charting.data.CombinedData
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ## 股票栏目 —「🌊 SSA」页
 *
 * SSA 奇异谱分析：趋势/周期/噪声分解 → 逐K无未来动态扰动区间 → A/B/C 情景分类
 * → 情景风控回测 → 命中率与分情景绩效评估 → 6 张标准图。
 *
 * ⚠️ SSA 是历史时序分解，不预测精准价格；动态区间只是短期波动范围的概率推断；
 * C 类突破代表结构改变、模型失效；末尾 L 根K线存在边缘效应。
 */
class SsaAnalysisFragment : Fragment() {

    private val RED = Color.parseColor("#E53935")
    private val GREEN = Color.parseColor("#43A047")
    private val ORANGE = Color.parseColor("#E65100")
    private val BLUE = Color.parseColor("#1976D2")
    private val PURPLE = Color.parseColor("#9C27B0")
    private val TEAL = Color.parseColor("#00897B")
    private val GRAY = Color.parseColor("#999999")
    private val A_COLOR = Color.parseColor("#90A4AE")   // A 稳态灰蓝
    private val B_COLOR = ORANGE                        // B 扰动橙
    private val C_COLOR = RED                           // C 突破红

    private lateinit var codeInput: EditText
    private lateinit var runBtn: Button
    private lateinit var statusTv: TextView
    private lateinit var resultBox: LinearLayout
    private val paramInputs = ArrayList<EditText>()
    private var windowBars = 250
    /** 模式：0=单票情景风控SSA；1+=独立子 Fragment */
    private var mode = 0
    private var scenarioScroll: View? = null
    private var modeHostRow: LinearLayout? = null
    private val modeHosts = HashMap<Int, android.widget.FrameLayout>()
    private val modeAdded = HashSet<Int>()
    private val modeBtns = LinkedHashMap<Int, TextView>()

    /**
     * 策略注册表——新增策略只需加一行（id 唯一 + 按钮文案 + 分类），
     * 分组、按钮、懒加载 host、切换全部自动生成。
     */
    private data class StrategyTab(val id: Int, val label: String, val category: String)
    private val strategyTabs = listOf(
        StrategyTab(0, "📊 情景风控", "回测·策略"),
        StrategyTab(1, "🔄 三层做T", "回测·策略"),
        StrategyTab(2, "🧭 混合策略", "回测·策略"),
        StrategyTab(3, "📐 缠论", "回测·策略"),
        StrategyTab(4, "🌊 大势分析", "板块·大盘"),
        StrategyTab(5, "📈 量价策略", "板块·大盘"),
        StrategyTab(8, "🕸 板块共振", "板块·大盘"),
        StrategyTab(6, "🌀 相空间", "预测"),
        StrategyTab(7, "📡 ARMA谱", "预测"),
    )

    /** 新策略在这里登记对应的 Fragment */
    private fun createStrategyFragment(id: Int): Fragment? = when (id) {
        1 -> SsaT0Fragment()
        2 -> SsaHybridFragment()
        3 -> ChanlunFragment()
        4 -> MacroTrendFragment()
        5 -> VolumePriceFragment()
        6 -> PhaseSpaceFragment()
        7 -> ArmaFragment()
        8 -> SectorResonanceFragment()
        else -> null
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

        box.addView(sectionTitle("🌊 SSA 奇异谱分析"))
        box.addView(hint("把价格拆成 趋势中枢 + 周期波动 + 随机噪声，逐K只用历史数据推断下一根K的正常/极限波动区间，" +
            "用真实下一根K线做事后 A正常 / B强扰动 / C结构突破 分类，并按情景自动风控回测。不预测精准价格。"))

        // 输入行
        val inputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(4))
        }
        codeInput = EditText(ctx).apply {
            hint = "股票代号 / 名称，如 600519 或 贵州茅台"
            textSize = 13f; isSingleLine = true
            setTextColor(Color.parseColor("#333333")); setHintTextColor(Color.parseColor("#AAAAAA"))
            background = roundedBg("#F5F5F5", "#DDDDDD", 6)
            setPadding(dp(10), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(8) }
        }
        inputRow.addView(codeInput)
        runBtn = Button(ctx).apply {
            text = "开始分析"; textSize = 13f; setTextColor(Color.WHITE); setBackgroundColor(TEAL)
            setPadding(dp(14), 0, dp(14), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40))
            setOnClickListener { onRun() }
        }
        inputRow.addView(runBtn)
        box.addView(inputRow)

        // 区间
        box.addView(label("分析区间"))
        run {
            val hs = chipScroll(listOf(120 to "近6个月", 250 to "近1年", 500 to "近2年")) { key ->
                windowBars = key
            }
            box.addView(hs)
        }

        // 参数
        box.addView(label("SSA 参数（可改）"))
        val defs = listOf(
            "L窗口" to "30", "回看N" to "20", "正常倍数" to "1.0",
            "极限倍数" to "1.0", "A止损%" to "8", "B止损%" to "4"
        )
        val prow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        defs.forEach { (cap, dft) ->
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(6) }
            }
            cell.addView(TextView(ctx).apply { text = cap; textSize = 9f; setTextColor(GRAY) })
            cell.addView(EditText(ctx).apply {
                setText(dft); textSize = 12f; isSingleLine = true
                setTextColor(Color.parseColor("#333333"))
                background = roundedBg("#FFFFFF", "#CCCCCC", 6)
                setPadding(dp(6), 0, dp(6), 0)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34))
            }.also { paramInputs.add(it) })
            prow.addView(cell)
        }
        box.addView(prow)

        statusTv = TextView(ctx).apply { textSize = 11f; setTextColor(GRAY); setPadding(dp(2), dp(6), dp(2), dp(2)) }
        box.addView(statusTv)
        resultBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(resultBox)
        box.addView(hint("⚠️ 信号/推断只用历史数据，无未来函数；情景分类是事后评估不参与下单；" +
            "末尾 L 根K线有边缘效应；结果不构成投资建议。"))

        // ── 策略切换：按分类分组的 chip 行；新增策略只改 strategyTabs 注册表 ──
        val tabBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(6))
            setBackgroundColor(Color.parseColor("#F7F7F7"))
        }
        strategyTabs.groupBy { it.category }.forEach { (cat, list) ->
            tabBox.addView(TextView(ctx).apply {
                text = cat; textSize = 10f; setTextColor(Color.parseColor("#999999"))
                setPadding(0, dp(4), 0, dp(2))
            })
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            list.forEach { def ->
                val b = modeBtn(def.label)
                modeBtns[def.id] = b
                b.setOnClickListener { switchMode(def.id) }
                row.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(6) })
            }
            tabBox.addView(row)
        }
        root.addView(tabBox, 0)

        // 各策略的懒加载宿主（一次只有一个可见）
        val hostRow = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(hostRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        modeHostRow = hostRow
        scenarioScroll = scroll
        paintMode(0)

        return root
    }

    private fun hostFor(m: Int): android.widget.FrameLayout {
        modeHosts[m]?.let { return it }
        val h = android.widget.FrameLayout(requireContext()).apply {
            id = View.generateViewId(); visibility = View.GONE
        }
        modeHostRow?.addView(h, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT
        ))
        modeHosts[m] = h
        return h
    }

    private fun modeBtn(t: String) = TextView(requireContext()).apply {
        text = t; textSize = 12f; gravity = Gravity.CENTER
        setPadding(dp(8), dp(7), dp(8), dp(7))
    }

    private fun paintMode(m: Int) {
        modeBtns.forEach { (id, tv) -> paintBtn(tv, id == m) }
    }

    private fun paintBtn(tv: TextView, on: Boolean) {
        tv.background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            if (on) { setColor(Color.parseColor("#E0F2F1")); setStroke(dp(1), TEAL) }
            else { setColor(Color.parseColor("#F2F2F2")); setStroke(dp(1), Color.parseColor("#DDDDDD")) }
        }
        tv.setTextColor(if (on) TEAL else Color.parseColor("#666666"))
    }

    private fun switchMode(m: Int) {
        if (mode == m) return
        mode = m
        paintMode(m)
        scenarioScroll?.visibility = if (m == 0) View.VISIBLE else View.GONE
        modeHosts.values.forEach { it.visibility = View.GONE }
        if (m == 0) return
        val host = hostFor(m)
        host.visibility = View.VISIBLE
        if (m !in modeAdded) {
            val f = createStrategyFragment(m) ?: return
            modeAdded.add(m)
            childFragmentManager.beginTransaction()
                .replace(host.id, f)
                .commitAllowingStateLoss()
        }
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
                status("⏳ 解析股票并加载日K…")
                val (code, name) = withContext(Dispatchers.IO) { resolveCode(raw) }
                if (code == null) { status("⚠️ 未找到「$raw」"); return@launch }
                val barsAll = withContext(Dispatchers.IO) { loadBars(code) }
                if (barsAll == null) { status("⚠️ $name 日K获取失败"); return@launch }
                val bars = barsAll.takeLast(windowBars)
                if (bars.size < p.windowL + 20) {
                    status("⚠️ 有效K线仅 ${bars.size} 根（需≥${p.windowL + 20}），请换大区间"); return@launch
                }
                status("⏳ SSA 分解 + 逐K滚动推断中（${bars.size} 根，L=${p.windowL}）…")
                val r = withContext(Dispatchers.Default) { runCatching { SsaEngine.run(bars, p) } }
                r.onSuccess { render(name ?: code, code, it) }
                    .onFailure { status("⚠️ 分析失败：${it.message}") }
                if (r.isSuccess) status("✅ 分析完成：${name ?: code}($code)")
            } finally {
                runBtn.isEnabled = true
            }
        }
    }

    private fun readParams(): Params? {
        fun d(i: Int, n: String): Double? {
            val v = paramInputs[i].text?.toString()?.trim()?.toDoubleOrNull()
            if (v == null) status("⚠️ 参数「$n」需为数字")
            return v
        }
        val L = d(0, "L窗口")?.toInt() ?: return null
        val N = d(1, "回看N")?.toInt() ?: return null
        val cm = d(2, "正常倍数") ?: return null
        val nm = d(3, "极限倍数") ?: return null
        val sa = d(4, "A止损") ?: return null
        val sb = d(5, "B止损") ?: return null
        return Params(L, N, cm, nm, sa, sb).also { it.validate()?.let { e -> status("⚠️ $e"); return null } }
    }

    // ═══════════════════════ 渲染 ═══════════════════════

    private fun render(name: String, code: String, r: Result) {
        resultBox.removeAllViews()
        val s = r.stats
        // ── 常规回测指标 ──
        resultBox.addView(card {
            it.addView(TextView(requireContext()).apply {
                text = "$name($code)"; textSize = 15f; setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#222222"))
            })
            it.addView(TextView(requireContext()).apply {
                text = "SSA 情景风控回测 · ${s.startDate} ~ ${s.endDate} · ${s.barCount} 根日K · " +
                    "L=${r.params.windowL} N=${r.params.lookbackStd}"
                textSize = 10f; setTextColor(GRAY); setPadding(0, dp(2), 0, dp(8))
            })
            it.addView(statGrid(listOf(
                "总收益率" to pct(s.totalReturnPct),
                "年化收益" to pct(s.annualizedPct),
                "最大回撤" to pct(s.maxDrawdownPct),
                "胜率" to "%.1f%% (%d/%d)".format(s.winRatePct, s.winCount, s.tradeCount),
                "交易次数" to "${s.tradeCount} 笔",
                "盈亏比" to if (s.profitFactor.isInfinite()) "∞" else "%.2f".format(s.profitFactor),
                "平均盈利" to pct(s.avgWinPct),
                "平均亏损" to pct(s.avgLossPct),
                "平均持仓" to "${s.avgHoldBars} 根",
                "基准(买持)" to pct(s.benchmarkReturnPct),
                "超额收益" to pct(s.excessReturnPct),
                "期末权益" to "¥%,.0f".format(SsaEngine.INITIAL_CAPITAL * (1 + s.totalReturnPct / 100))
            )))
        })

        // ── 图1 主交易图 ──
        resultBox.addView(card {
            it.addView(cardTitle("① 主交易图：收盘 + SSA趋势中枢 + 买卖点"))
            it.addView(hint("⚠️ 序列末尾 ${r.params.windowL} 根K线存在 SSA 边缘效应（Hankel 对角平均样本递减），区间推断可信度下降。"))
            it.addView(buildMainChart(r, 300))
        })
        // ── 图2 周期副图 ──
        resultBox.addView(card {
            it.addView(cardTitle("② SSA 自适应周期振荡（cycle）"))
            it.addView(buildCycleChart(r, 130))
        })
        // ── 图3 区间预测演示 ──
        resultBox.addView(card {
            it.addView(cardTitle("③ 动态扰动区间预测 + A/B/C 事后验证（蓝A正常 橙B扰动 红C突破）"))
            it.addView(hint("⚠️ 灰带=正常扰动区间（逐K仅用历史滚动推断，供下一根K使用）；末尾 ${r.params.windowL} 根为 SSA 边缘效应区，可信度下降；A/B/C 由真实收盘事后标记，不参与当根下单。"))
            it.addView(buildBandChart(r, 240))
        })
        // ── 图4 四层分解 ──
        resultBox.addView(card {
            it.addView(cardTitle("④ SSA 分量拆解：价格 / 趋势 / 周期 / 噪声"))
            it.addView(buildCloseTrendMini(r, 110))
            it.addView(buildSingleLineMini(r.cycleFull, "周期 cycle", PURPLE, 80))
            it.addView(buildSingleLineMini(r.noiseFull, "噪声 noise", GRAY, 80))
        })
        // ── 图5 奇异谱 ──
        resultBox.addView(card {
            it.addView(cardTitle("⑤ 奇异谱（奇异值衰减：分量1趋势，2-3周期，余为噪声）"))
            it.addView(buildSpectrumChart(r, 140))
        })
        // ── 图6 回测绩效 ──
        resultBox.addView(card {
            it.addView(cardTitle("⑥ 回测绩效：净值 / 基准 / 回撤（右轴）"))
            it.addView(buildEquityChart(r, 180))
        })

        // ── 命中率报告 ──
        val e = r.eval
        resultBox.addView(card {
            it.addView(cardTitle("🎯 区间预测准确度（有效样本 ${e.validBars} 根）"))
            it.addView(statGrid(listOf(
                "A 正常命中" to "${e.aCount} (%.1f%%)".format(e.aRatioPct),
                "B 强扰动" to "${e.bCount} (%.1f%%)".format(e.bRatioPct),
                "C 结构突破" to "${e.cCount} (%.1f%%)".format(e.cRatioPct),
                "平均偏差|C-mid|" to "%.2f%%".format(e.meanAbsBiasPct),
                "正常区间均宽" to "%.2f%%".format(e.meanNormalWidthPct),
                "极限区间均宽" to "%.2f%%".format(e.meanExtremeWidthPct),
                "最大单根偏离" to "%.2f%%".format(e.maxDevPct),
                "最长连续A" to "${e.maxConsecutiveA} 根",
                "最长连续C" to "${e.maxConsecutiveC} 根"
            )))
        })

        // ── 分情景绩效 ──
        resultBox.addView(card {
            it.addView(cardTitle("🧩 分情景策略绩效（策略吃什么行情、怕什么行情）"))
            val scroll = HorizontalScrollView(requireContext())
            val table = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            val headers = listOf("情景", "全样本K线", "持仓K线", "持仓均涨跌", "持仓阳线率", "平仓笔数", "交易胜率", "交易均收益")
            val widths = listOf(64, 74, 70, 80, 76, 66, 66, 80)
            table.addView(tableRow(headers, widths, true))
            e.scenarioStats.forEach { st ->
                val name2 = when (st.scenario) {
                    SsaEngine.SCN_A -> "A 正常稳态"; SsaEngine.SCN_B -> "B 强扰动"; else -> "C 结构突破"
                }
                val row = tableRow(listOf(
                    name2, "${st.barCount}", "${st.holdingBars}",
                    "%.2f%%".format(st.meanBarRetPct), "%.0f%%".format(st.upRatioPct),
                    "${st.tradeCount}", "%.0f%%".format(st.tradeWinRatePct),
                    "%.2f%%".format(st.tradeAvgRetPct)
                ), widths, false)
                (row.getChildAt(0) as TextView).setTextColor(
                    when (st.scenario) { SsaEngine.SCN_B -> B_COLOR; SsaEngine.SCN_C -> C_COLOR; else -> BLUE }
                )
                table.addView(row)
            }
            scroll.addView(table); it.addView(scroll)
        })

        // ── 风控触发日志 ──
        resultBox.addView(card {
            it.addView(cardTitle("🛡️ 情景风控触发日志"))
            it.addView(statGrid(listOf(
                "C类无条件清仓" to "${e.cClearExits} 次",
                "B类仓位减半" to "${e.bHalveExits} 次",
                "B类收紧止损" to "${e.tightStopExits} 次",
                "持仓期遇B" to "${e.bCountHolding} 根",
                "C/B情景K线" to "C ${e.cCount} / B ${e.bCount}",
                "风控动作总数" to "${r.riskEvents.size} 条"
            )))
            val evs = r.riskEvents
            if (evs.isEmpty()) {
                it.addView(TextView(requireContext()).apply {
                    text = "区间内未触发 B/C 风控（全程 A 稳态或未持仓）。"; textSize = 10f; setTextColor(GRAY)
                    setPadding(0, dp(6), 0, 0)
                })
            } else {
                val scroll = HorizontalScrollView(requireContext())
                val table = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
                val headers = listOf("日期", "情景", "价格", "执行动作")
                val widths = listOf(86, 50, 66, 300)
                table.addView(tableRow(headers, widths, true))
                evs.forEach { ev ->
                    val tag = if (ev.scenario == SsaEngine.SCN_C) "C 突破" else "B 扰动"
                    val row = tableRow(listOf(
                        ev.date, tag, "%.2f".format(ev.price), ev.action
                    ), widths, false)
                    (row.getChildAt(1) as TextView).setTextColor(
                        if (ev.scenario == SsaEngine.SCN_C) C_COLOR else B_COLOR
                    )
                    table.addView(row)
                }
                scroll.addView(table); it.addView(scroll)
            }
        })

        // ── 交易明细 ──
        resultBox.addView(card {
            it.addView(cardTitle("📋 交易明细（${r.trades.size} 笔，含离场原因与持仓最差情景）"))
            if (r.trades.isEmpty()) {
                it.addView(TextView(requireContext()).apply {
                    text = "该区间未产生完整交易。"; textSize = 11f; setTextColor(GRAY)
                })
            } else {
                val scroll = HorizontalScrollView(requireContext())
                val table = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
                val headers = listOf("#", "买入日", "买价", "卖出日", "卖价", "收益", "持仓", "最差", "离场原因")
                val widths = listOf(26, 78, 58, 78, 58, 66, 44, 40, 150)
                table.addView(tableRow(headers, widths, true))
                r.trades.forEachIndexed { i, t ->
                    val worst = when (t.worstScenario) {
                        SsaEngine.SCN_A -> "A"; SsaEngine.SCN_B -> "B"; else -> "C"
                    }
                    val row = tableRow(listOf(
                        "${i + 1}", t.buyDate, "%.2f".format(t.buyPrice),
                        t.sellDate, "%.2f".format(t.sellPrice),
                        "${if (t.returnPct > 0) "+" else ""}%.2f%%".format(t.returnPct),
                        "${t.barsHeld}", worst, t.exitReason
                    ), widths, false)
                    (row.getChildAt(5) as TextView).apply {
                        setTextColor(if (t.returnPct > 0) RED else GREEN); setTypeface(typeface, Typeface.BOLD)
                    }
                    (row.getChildAt(7) as TextView).setTextColor(
                        when (t.worstScenario) { SsaEngine.SCN_B -> B_COLOR; SsaEngine.SCN_C -> C_COLOR; else -> A_COLOR }
                    )
                    table.addView(row)
                }
                scroll.addView(table); it.addView(scroll)
            }
        })
    }

    private fun pct(v: Double) = "${if (v > 0) "+" else ""}%.2f%%".format(v)

    private fun statGrid(cells: List<Pair<String, String>>): View {
        val ctx = requireContext()
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        cells.chunked(3).forEach { rc ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            rc.forEach { (k, v) ->
                val cell = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6))
                    background = roundedBg("#FAFAFA", "#EEEEEE", 6)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginEnd = dp(6); bottomMargin = dp(6) }
                }
                cell.addView(TextView(ctx).apply { text = k; textSize = 9f; setTextColor(GRAY) })
                cell.addView(TextView(ctx).apply {
                    text = v; textSize = 12f; setTypeface(typeface, Typeface.BOLD)
                    setTextColor(when { v.startsWith("+") -> RED; v.startsWith("-") -> GREEN
                        else -> Color.parseColor("#333333") })
                })
                row.addView(cell)
            }
            repeat(3 - rc.size) {
                row.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })
            }
            grid.addView(row)
        }
        return grid
    }

    private fun tableRow(cells: List<String>, widthsDp: List<Int>, header: Boolean): LinearLayout {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(if (header) Color.parseColor("#F0F0F0") else Color.WHITE)
            cells.forEachIndexed { i, txt ->
                addView(TextView(ctx).apply {
                    text = txt
                    textSize = 9f
                    gravity = Gravity.CENTER_VERTICAL or (if (i == 0 || i == cells.lastIndex) Gravity.START else Gravity.CENTER_HORIZONTAL)
                    setTextColor(if (header) Color.parseColor("#666666") else Color.parseColor("#333333"))
                    if (header) setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(4), dp(5), dp(4), dp(5))
                    layoutParams = LinearLayout.LayoutParams(dp(widthsDp[i]), LinearLayout.LayoutParams.WRAP_CONTENT)
                })
            }
        }
    }

    // ═══════════════════════ 6 张图 ═══════════════════════

    private fun dateFmt(bars: List<Bar>) = object : ValueFormatter() {
        override fun getFormattedValue(v: Float): String {
            val i = v.toInt(); return if (i in bars.indices) bars[i].date.takeLast(5) else ""
        }
    }

    private fun styleX(chart: com.github.mikephil.charting.charts.BarLineChartBase<*>, bars: List<Bar>) {
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            granularity = (bars.size / 6).coerceAtLeast(1).toFloat()
            labelCount = 6; textSize = 8f; textColor = GRAY
            setDrawGridLines(false); setAvoidFirstLastClipping(true)
            valueFormatter = dateFmt(bars)
        }
        chart.axisLeft.apply { textSize = 8f; textColor = GRAY; gridColor = Color.parseColor("#EEEEEE") }
        chart.axisRight.isEnabled = false
    }

    private fun arrowSet(es: List<Entry>, up: Boolean, color: Int): LineDataSet =
        LineDataSet(es, if (up) "买入" else "卖出").apply {
            this.color = Color.TRANSPARENT; setDrawCircles(false); setDrawValues(true)
            valueTextSize = 11f; valueTextColor = color; isHighlightEnabled = false
            valueFormatter = object : ValueFormatter() {
                override fun getPointLabel(entry: Entry?) = if (up) "▲" else "▼"
            }
        }

    /** ① 主交易图：蜡烛 + 趋势中枢 + 买卖点 */
    private fun buildMainChart(r: Result, h: Int): CombinedChart {
        val bars = r.bars
        val chart = CombinedChart(requireContext())
        chart.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h))
        chart.setBackgroundColor(Color.WHITE); chart.description.isEnabled = false
        chart.legend.textSize = 8f; chart.legend.textColor = GRAY
        chart.setScaleEnabled(true); chart.setPinchZoom(true); chart.setDragEnabled(true)
        chart.setHighlightPerTapEnabled(false); chart.setHighlightPerDragEnabled(false)
        chart.setVisibleXRangeMaximum((bars.size + 2).toFloat()); chart.setVisibleXRangeMinimum(40f)
        chart.setAutoScaleMinMaxEnabled(true)
        chart.moveViewToX((bars.size - 1).toFloat())
        chart.drawOrder = arrayOf(CombinedChart.DrawOrder.CANDLE, CombinedChart.DrawOrder.LINE)

        val ce = ArrayList<CandleEntry>()
        bars.forEachIndexed { i, b -> ce.add(CandleEntry(i.toFloat(), b.high.toFloat(), b.low.toFloat(), b.open.toFloat(), b.close.toFloat())) }
        val cds = CandleDataSet(ce, "K线").apply {
            shadowColor = Color.parseColor("#999999"); shadowWidth = 1f
            increasingPaintStyle = Paint.Style.FILL; decreasingPaintStyle = Paint.Style.FILL
            increasingColor = RED; decreasingColor = GREEN
            isHighlightEnabled = false; setDrawValues(false)
        }
        val te = ArrayList<Entry>()
        r.trendFull.forEachIndexed { i, v -> if (!v.isNaN()) te.add(Entry(i.toFloat(), v.toFloat())) }
        val trendDs = LineDataSet(te, "SSA趋势中枢").apply {
            color = ORANGE; lineWidth = 1.6f; setDrawCircles(false); setDrawValues(false); isHighlightEnabled = false
        }
        val ld = LineData(trendDs)
        ld.addDataSet(arrowSet(r.markers.filter { it.buy }.map {
            Entry(it.index.toFloat(), (bars[it.index].low * 0.985).toFloat())
        }, true, RED))
        ld.addDataSet(arrowSet(r.markers.filter { !it.buy }.map {
            Entry(it.index.toFloat(), (bars[it.index].high * 1.015).toFloat())
        }, false, GREEN))
        chart.data = CombinedData().apply { setData(CandleData(cds)); setData(ld) }
        styleX(chart, bars); chart.post { chart.invalidate() }
        return chart
    }

    /** ② 周期副图 */
    private fun buildCycleChart(r: Result, h: Int): LineChart {
        val chart = lineChartBase(r.bars, h)
        val data = LineData(
            LineDataSet(entries(r.cycleFull), "周期 cycle").apply {
                color = PURPLE; lineWidth = 1.3f; setDrawCircles(false); setDrawValues(false); isHighlightEnabled = false
            })
        chart.axisLeft.addLimitLine(LimitLine(0f, "").apply { lineColor = Color.parseColor("#CCCCCC"); lineWidth = 1f })
        chart.data = data; styleX(chart, r.bars); chart.post { chart.invalidate() }
        return chart
    }

    /** ③ 区间预测 + A/B/C 点（正常区间浅灰半透明填充；与K线同一Y轴） */
    private fun buildBandChart(r: Result, h: Int): CombinedChart {
        val bars = r.bars
        val chart = CombinedChart(requireContext())
        chart.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h))
        chart.setBackgroundColor(Color.WHITE); chart.description.isEnabled = false
        chart.legend.textSize = 7f; chart.legend.textColor = GRAY
        chart.setScaleEnabled(true); chart.setPinchZoom(true); chart.setDragEnabled(true)
        chart.setHighlightPerTapEnabled(false); chart.setHighlightPerDragEnabled(false)
        chart.setVisibleXRangeMaximum((bars.size + 2).toFloat()); chart.setVisibleXRangeMinimum(40f)
        chart.setAutoScaleMinMaxEnabled(true)
        chart.moveViewToX((bars.size - 1).toFloat())
        chart.drawOrder = arrayOf(CombinedChart.DrawOrder.BAR, CombinedChart.DrawOrder.LINE)

        val close = ArrayList<Entry>(); val nU = ArrayList<Entry>(); val nD = ArrayList<Entry>()
        val eU = ArrayList<Entry>(); val eD = ArrayList<Entry>()
        val bandBars = ArrayList<BarEntry>()
        val aPts = ArrayList<Entry>(); val bPts = ArrayList<Entry>(); val cPts = ArrayList<Entry>()
        bars.indices.forEach { i ->
            val c = bars[i].close
            close.add(Entry(i.toFloat(), c.toFloat()))
            if (!r.normalUp[i].isNaN()) {
                nU.add(Entry(i.toFloat(), r.normalUp[i].toFloat()))
                nD.add(Entry(i.toFloat(), r.normalDn[i].toFloat()))
                eU.add(Entry(i.toFloat(), r.extremeUp[i].toFloat()))
                eD.add(Entry(i.toFloat(), r.extremeDn[i].toFloat()))
                // 堆叠柱：底段=正常下轨（透明），顶段=正常带宽（浅灰半透明）→ 填充正常区间
                bandBars.add(BarEntry(i.toFloat(),
                    floatArrayOf(r.normalDn[i].toFloat(), (r.normalUp[i] - r.normalDn[i]).toFloat())))
                when (r.scenario[i]) {
                    SsaEngine.SCN_A -> aPts.add(Entry(i.toFloat(), c.toFloat()))
                    SsaEngine.SCN_B -> bPts.add(Entry(i.toFloat(), c.toFloat()))
                    SsaEngine.SCN_C -> cPts.add(Entry(i.toFloat(), c.toFloat()))
                }
            }
        }
        val bandDs = BarDataSet(bandBars, "正常扰动区间").apply {
            setDrawValues(false); isHighlightEnabled = false
            // 底段完全透明，顶段浅灰半透明（堆叠顺序与 y 值顺序一致）
            colors = listOf(Color.TRANSPARENT, Color.argb(46, 120, 120, 120))
            barBorderWidth = 0f
        }
        val barData = BarData(bandDs).apply { barWidth = 0.92f }

        fun boundary(es: List<Entry>, label: String, color: Int, dashed: Boolean) = LineDataSet(es, label).apply {
            this.color = color; lineWidth = if (dashed) 0.8f else 1f
            setDrawCircles(false); setDrawValues(false); isHighlightEnabled = false
            if (dashed) enableDashedLine(7f, 5f, 0f)
        }
        val data = LineData()
        data.addDataSet(boundary(eD, "极限下轨", C_COLOR, true))
        data.addDataSet(boundary(nD, "正常下轨", BLUE, false))
        data.addDataSet(LineDataSet(close, "收盘价").apply {
            color = Color.parseColor("#222222"); lineWidth = 1.4f
            setDrawCircles(false); setDrawValues(false); isHighlightEnabled = false
        })
        data.addDataSet(boundary(nU, "正常上轨", BLUE, false))
        data.addDataSet(boundary(eU, "极限上轨", C_COLOR, true))
        data.addDataSet(scatterSet(aPts, "A", A_COLOR))
        data.addDataSet(scatterSet(bPts, "B", B_COLOR))
        data.addDataSet(scatterSet(cPts, "C", C_COLOR))

        // 边缘效应起点（末尾 L 根）
        chart.xAxis.addLimitLine(LimitLine((bars.size - r.params.windowL).toFloat(),
            "← 末尾${r.params.windowL}根边缘效应").apply {
            lineColor = Color.parseColor("#BBBBBB"); lineWidth = 1f
            enableDashedLine(5f, 4f, 0f); textColor = GRAY; textSize = 7f
            labelPosition = LimitLine.LimitLabelPosition.RIGHT_TOP
        })

        chart.data = CombinedData().apply { setData(barData); setData(data) }
        styleX(chart, bars)
        chart.axisLeft.spaceTop = 0.08f; chart.axisLeft.spaceBottom = 0.08f
        chart.post { chart.invalidate() }
        return chart
    }

    private fun scatterSet(es: List<Entry>, label: String, color: Int) = LineDataSet(es, label).apply {
        this.color = color; setDrawCircles(true); circleRadius = 2.4f; setCircleColor(color)
        lineWidth = 0f; setDrawValues(false); isHighlightEnabled = false
    }

    /** ④ 迷你图：收盘+趋势叠加 */
    private fun buildCloseTrendMini(r: Result, h: Int): LineChart {
        val chart = lineChartBase(r.bars, h)
        val data = LineData(
            LineDataSet(entries(DoubleArray(r.bars.size) { i -> r.bars[i].close }), "收盘").apply {
                color = Color.parseColor("#222222"); lineWidth = 1f; setDrawCircles(false); setDrawValues(false); isHighlightEnabled = false
            },
            LineDataSet(entries(r.trendFull), "趋势").apply {
                color = ORANGE; lineWidth = 1.4f; setDrawCircles(false); setDrawValues(false); isHighlightEnabled = false
            })
        chart.data = data; styleX(chart, r.bars); chart.post { chart.invalidate() }
        return chart
    }

    private fun buildSingleLineMini(v: DoubleArray, label: String, color: Int, h: Int): LineChart {
        val chart = lineChartBase(emptyList(), h).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h))
        }
        val data = LineData(LineDataSet(entries(v), label).apply {
            this.color = color; lineWidth = 1.1f; setDrawCircles(false); setDrawValues(false); isHighlightEnabled = false
        })
        chart.data = data
        chart.xAxis.isEnabled = false; chart.axisRight.isEnabled = false
        chart.axisLeft.textSize = 7f; chart.axisLeft.textColor = GRAY
        chart.post { chart.invalidate() }
        return chart
    }

    /** ⑤ 奇异谱柱状 */
    private fun buildSpectrumChart(r: Result, h: Int): BarChart {
        val chart = BarChart(requireContext())
        chart.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h))
        chart.setBackgroundColor(Color.WHITE); chart.description.isEnabled = false
        chart.legend.textSize = 8f; chart.legend.textColor = GRAY
        chart.setScaleEnabled(false); chart.setPinchZoom(false)
        chart.setHighlightPerTapEnabled(false); chart.setHighlightPerDragEnabled(false)
        val sv = r.singularValues
        val be = ArrayList<BarEntry>()
        val colors = ArrayList<Int>()
        sv.forEachIndexed { i, v ->
            be.add(BarEntry(i.toFloat(), v.toFloat()))
            colors.add(when (i) { 0 -> ORANGE; 1, 2 -> PURPLE; else -> A_COLOR })
        }
        val ds = BarDataSet(be, "奇异值 σ").apply { setColors(colors); setDrawValues(false); isHighlightEnabled = false }
        chart.data = BarData(ds).apply { barWidth = 0.7f }
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM; granularity = 1f; textSize = 7f; textColor = GRAY
            setDrawGridLines(false)
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(v: Float) = "σ${v.toInt() + 1}"
            }
        }
        chart.axisLeft.textSize = 8f; chart.axisLeft.textColor = GRAY
        chart.axisRight.isEnabled = false
        chart.post { chart.invalidate() }
        return chart
    }

    /** ⑥ 净值/基准/回撤（右轴） */
    private fun buildEquityChart(r: Result, h: Int): LineChart {
        val bars = r.bars
        val chart = lineChartBase(bars, h)
        val strat = ArrayList<Entry>(); val bench = ArrayList<Entry>(); val dd = ArrayList<Entry>()
        bars.indices.forEach { i ->
            strat.add(Entry(i.toFloat(), r.equityCurve[i].toFloat()))
            bench.add(Entry(i.toFloat(), r.benchmarkCurve[i].toFloat()))
            dd.add(Entry(i.toFloat(), r.drawdownPct[i].toFloat()))
        }
        val data = LineData()
        data.addDataSet(LineDataSet(strat, "策略净值").apply {
            color = TEAL; lineWidth = 1.6f; setDrawCircles(false); setDrawValues(false); isHighlightEnabled = false
        })
        data.addDataSet(LineDataSet(bench, "买入持有").apply {
            color = GRAY; lineWidth = 1.1f; setDrawCircles(false); setDrawValues(false); isHighlightEnabled = false
            enableDashedLine(6f, 4f, 0f)
        })
        data.addDataSet(LineDataSet(dd, "回撤%").apply {
            color = RED; lineWidth = 0.9f; setDrawCircles(false); setDrawValues(false); isHighlightEnabled = false
            axisDependency = com.github.mikephil.charting.components.YAxis.AxisDependency.RIGHT
        })
        chart.axisRight.isEnabled = true
        chart.axisRight.apply {
            textSize = 8f; textColor = RED; gridColor = Color.TRANSPARENT
            axisMaximum = 2f
        }
        chart.data = data; styleX(chart, bars)
        chart.axisLeft.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(v: Float) = "%,.0f".format(v)
        }
        chart.post { chart.invalidate() }
        return chart
    }

    private fun entries(v: DoubleArray): List<Entry> {
        val es = ArrayList<Entry>()
        v.forEachIndexed { i, x -> if (!x.isNaN()) es.add(Entry(i.toFloat(), x.toFloat())) }
        return es
    }

    private fun lineChartBase(bars: List<Bar>, h: Int): LineChart {
        val chart = LineChart(requireContext())
        chart.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h))
        chart.setBackgroundColor(Color.WHITE); chart.description.isEnabled = false
        chart.legend.textSize = 7f; chart.legend.textColor = GRAY
        chart.setScaleEnabled(true); chart.setPinchZoom(true); chart.setDragEnabled(true)
        chart.setHighlightPerTapEnabled(false); chart.setHighlightPerDragEnabled(false)
        if (bars.isNotEmpty()) {
            chart.setVisibleXRangeMaximum((bars.size + 2).toFloat()); chart.setVisibleXRangeMinimum(40f)
            chart.setAutoScaleMinMaxEnabled(true)
            chart.moveViewToX((bars.size - 1).toFloat())
        }
        return chart
    }

    // ═══════════════════════ 数据加载 ═══════════════════════

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

    private suspend fun loadBars(code: String): List<Bar>? {
        val ctx = requireContext()
        val db = StockDatabase.getInstance(ctx)
        fun conv(list: List<DailySnapshotEntity>) = list
            .filter { it.close > 0 && it.high > 0 && it.low > 0 }
            .sortedBy { it.date }
            .map { Bar(it.date, it.open.ifZero(it.close), it.high, it.low, it.close, it.volume) }
        var local = runCatching { db.dailySnapshotDao().getByCode(code, 2000) }.getOrDefault(emptyList())
        if (local.size < 400) {
            val ok = HistoricalDataFetcher(ctx).fetchStockLatest(code, 850)
            if (ok) local = runCatching { db.dailySnapshotDao().getByCode(code, 2000) }.getOrDefault(emptyList())
        }
        val bars = conv(local)
        return if (bars.size < 40) null else bars
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
    private fun chipScroll(items: List<Pair<Int, String>>, onSelect: (Int) -> Unit): HorizontalScrollView {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, dp(2)) }
        items.forEach { (key, txt) ->
            row.addView(TextView(ctx).apply {
                text = txt; textSize = 11f; gravity = Gravity.CENTER
                setPadding(dp(10), dp(5), dp(10), dp(5)); isClickable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6) }
                paintChip(this, key == 250)
                setOnClickListener {
                    onSelect(key)
                    for (ci in 0 until row.childCount) (row.getChildAt(ci) as? TextView)?.let { paintChip(it, false) }
                    paintChip(this, true)
                }
            })
        }
        return HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    }

    private fun paintChip(tv: TextView, on: Boolean) {
        tv.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            if (on) { setColor(Color.parseColor("#E0F2F1")); setStroke(dp(1), TEAL) }
            else { setColor(Color.parseColor("#F2F2F2")); setStroke(dp(1), Color.parseColor("#DDDDDD")) }
        }
        tv.setTextColor(if (on) TEAL else Color.parseColor("#666666"))
        tv.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
    }
}
