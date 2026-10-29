package com.chin.stockanalysis.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.strategy.analysis.PhaseSpaceEngine
import com.chin.stockanalysis.strategy.analysis.PhaseSpaceLedger
import com.chin.stockanalysis.strategy.ssa.SsaPoolStore
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * ## 🌀 相空间 Tab — Takens 延迟嵌入重构 + 相似态近邻预测（双方法对比）
 *
 * 三个子页：
 *  - 单票：价格法 vs 因果 SSA cycle 法双通道（吸引子/预测对比/滚动回测）
 *  - 持仓池：对混合策略持仓池批量预测（当天小时 + 未来5日），表格 + 方向提示 + 台账保存
 *  - 盘后验证：回填实际价格，统计两法方向命中率
 */
class PhaseSpaceFragment : Fragment() {

    private val TEAL = Color.parseColor("#26A69A")
    private val RED = Color.parseColor("#EF5350")
    private val GREEN = Color.parseColor("#43A047")
    private val ORANGE = Color.parseColor("#FF9800")
    private val GRAY = Color.parseColor("#757575")
    private val BLUE = Color.parseColor("#1E88E5")
    private val PURPLE = Color.parseColor("#8E24AA")
    private val DARK = Color.parseColor("#212121")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun tv(t: String, c: Int, s: Float) = TextView(requireContext()).apply {
        text = t; textSize = s; setTextColor(c)
    }
    private fun tv(t: String, s: Float, b: Boolean) = TextView(requireContext()).apply {
        text = t; textSize = s; setTextColor(DARK)
        if (b) setTypeface(null, android.graphics.Typeface.BOLD)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(16))
            setBackgroundColor(Color.parseColor("#F7F7F7"))
        }
        scroll.addView(root)

        root.addView(tv("🌀 相空间重构预测（价格法 vs SSA cycle 法·因果）", 15f, true))

        // ── 子页切换 ──
        val subRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(4)) }
        val subSingle = subBtn("🔍 单票"); val subPool = subBtn("📋 持仓池"); val subVerify = subBtn("✅ 盘后验证")
        val subFuse = subBtn("🧭 多周期")
        subRow.addView(subSingle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        subRow.addView(subPool, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        subRow.addView(subVerify, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        subRow.addView(subFuse, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(subRow)

        val pageSingle = buildSinglePage()
        val pagePool = buildPoolPage()
        val pageVerify = buildVerifyPage()
        val pageFuse = buildFusePage()
        root.addView(pageSingle); root.addView(pagePool); root.addView(pageVerify); root.addView(pageFuse)
        pagePool.visibility = View.GONE; pageVerify.visibility = View.GONE; pageFuse.visibility = View.GONE

        fun paintSub(sel: Int) {
            listOf(subSingle, subPool, subVerify, subFuse).forEachIndexed { i, b ->
                b.setBackgroundColor(if (i == sel) TEAL else Color.parseColor("#E0E0E0"))
                b.setTextColor(if (i == sel) Color.WHITE else DARK)
            }
            pageSingle.visibility = if (sel == 0) View.VISIBLE else View.GONE
            pagePool.visibility = if (sel == 1) View.VISIBLE else View.GONE
            pageVerify.visibility = if (sel == 2) View.VISIBLE else View.GONE
            pageFuse.visibility = if (sel == 3) View.VISIBLE else View.GONE
        }
        subSingle.setOnClickListener { paintSub(0) }
        subPool.setOnClickListener { paintSub(1); renderPoolCacheIfEmpty() }
        subVerify.setOnClickListener { paintSub(2); refreshVerifyList() }
        subFuse.setOnClickListener { paintSub(3) }
        paintSub(0)
        return scroll
    }

    private fun subBtn(t: String) = TextView(requireContext()).apply {
        text = t; textSize = 12f; gravity = Gravity.CENTER
        setPadding(0, dp(8), 0, dp(8))
    }

    // ════════════════════════ 子页 1：单票双通道 ════════════════════════

    private var poolStatus: TextView? = null
    private var poolBox: LinearLayout? = null
    private var verifyStatus: TextView? = null
    private var verifyBox: LinearLayout? = null
    private var verifyStatTv: TextView? = null

    private fun buildSinglePage(): View {
        val ctx = requireContext()
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(tv("Takens 延迟嵌入：x(t),x(t-τ),… 重构吸引子影子 → 找历史最近邻 → 邻点未来的距离加权投票。" +
            "方法A直接嵌价格（含趋势）；方法B先因果 SSA 拆 cycle 分量去趋势去噪再嵌，预测价=趋势平推+cycle。", GRAY, 10f))

        val codeEt = EditText(ctx).apply {
            hint = "股票代码，如 600188"; textSize = 13f
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        var hourly = false
        val lvRow = RadioGroup(ctx).apply { orientation = RadioGroup.HORIZONTAL }
        val rbD = RadioButton(ctx).apply { text = "日线"; textSize = 12f; isChecked = true }
        val rbH = RadioButton(ctx).apply { text = "1H"; textSize = 12f }
        lvRow.addView(rbD); lvRow.addView(rbH)
        lvRow.setOnCheckedChangeListener { _, id -> hourly = id == rbH.id }
        val btn = Button(ctx).apply { text = "重构+预测" }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(codeEt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f))
        row.addView(lvRow, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f))
        box.addView(row)

        val status = tv("输入代码点「重构+预测」", GRAY, 11f)
        box.addView(status)

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(8).toFloat() }
            visibility = View.GONE
        }
        val paramTvA = tv("", TEAL, 11f)
        val paramTvB = tv("", PURPLE, 11f)
        val paramTvC = tv("", GREEN, 11f)
        val corrTv = tv("", GRAY, 11f)
        val fcTv = tv("", DARK, 12f)
        card.addView(paramTvA); card.addView(paramTvB); card.addView(paramTvC); card.addView(corrTv); card.addView(fcTv)
        box.addView(card)

        fun mkChart(h: Int, dark: Boolean) = LineChart(ctx).apply {
            description.isEnabled = false
            setTouchEnabled(true); isDragEnabled = true; setScaleEnabled(true)
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            axisRight.isEnabled = false
            if (dark) setBackgroundColor(Color.parseColor("#111111"))
        }.also { box.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h))) }

        box.addView(tv("①A 吸引子投影·价格法（青=旧 红=新，白点=现在）", 11f, true)
            .apply { setPadding(0, dp(10), 0, dp(4)) })
        val attChartA = mkChart(240, dark = true).apply { legend.isEnabled = false }
        box.addView(tv("①B 吸引子投影·cycle法（去趋势去噪后若呈环状/结构，说明存在动力学）", 11f, true)
            .apply { setPadding(0, dp(8), 0, dp(4)) })
        val attChartB = mkChart(240, dark = true).apply { legend.isEnabled = false }

        box.addView(tv("② 预测对比（蓝=实际；青/紫=两法走查1步拟合；橙虚线=价格法预测；紫虚线=cycle法预测）", 11f, true)
            .apply { setPadding(0, dp(10), 0, dp(4)) })
        val fcChart = mkChart(240, dark = false)

        box.addView(tv("③ 走查副图：1步命中准确度 10根滑动%（橙A/紫B，红虚线=50）+ 底部灰点=十字星（密集=震荡段，可对照命中低谷是否在趋势段）", 11f, true)
            .apply { setPadding(0, dp(8), 0, dp(4)) })
        val subChart = mkChart(170, dark = false).apply {
            axisLeft.axisMinimum = 0f; axisLeft.axisMaximum = 100f
        }

        box.addView(tv("⑤ 幅度精度散点：实际Δ% vs 预测Δ%（h=1；橙A/紫B/绿C；点贴 45° 灰线=幅度也准，只在一三象限=方向对）", 11f, true)
            .apply { setPadding(0, dp(8), 0, dp(4)) })
        val scatterChart = mkChart(190, dark = false)

        box.addView(tv("⑥ 滚动回测：1步方向命中率 20根滑动（橙=价格法，紫=cycle法，红虚线=50% 随机基准）", 11f, true)
            .apply { setPadding(0, dp(10), 0, dp(4)) })
        val hitChart = mkChart(180, dark = false).apply {
            axisLeft.axisMinimum = 0f; axisLeft.axisMaximum = 100f
        }

        box.addView(tv("⚠️ 方向命中率显著>50% 才有实战参考意义；混沌系统可预测窗口有限，结果不构成投资建议。", GRAY, 10f)
            .apply { setPadding(0, dp(8), 0, 0) })

        btn.setOnClickListener {
            val code = codeEt.text?.toString()?.trim().orEmpty()
            if (code.length != 6) { status.text = "请输入 6 位代码"; return@setOnClickListener }
            btn.isEnabled = false
            lifecycleScope.launch {
                try {
                    status.text = "⏳ 拉取K线…"
                    val (dates, closes, ohlc) = withContext(Dispatchers.IO) { fetchCloses(code, hourly) }
                    if (closes.size < 120) { status.text = "⚠️ K线不足（${closes.size} 根，需≥120）"; return@launch }
                    status.text = "⏳ 双通道重构（${closes.size} 根；cycle法逐点因果SSA分解，约数秒）…"
                    val r = withContext(Dispatchers.Default) {
                        PhaseSpaceEngine.analyze(dates, closes, horizon = 5,
                            opens = ohlc?.first, highs = ohlc?.second, lows = ohlc?.third)
                    }
                    if (r == null) { status.text = "⚠️ 数据异常"; return@launch }
                    status.text = "✅ 完成：价格法 τ=${r.price.tau}/m=${r.price.m}" +
                        (r.cycle?.let { "　cycle法 τ=${it.tau}/m=${it.m}" } ?: "（cycle 分解失败）")
                    renderCard(card, paramTvA, paramTvB, paramTvC, corrTv, fcTv, r)
                    renderAttractor(attChartA, r.price)
                    if (r.cycle != null) renderAttractor(attChartB, r.cycle) else attChartB.clear()
                    renderForecast(fcChart, r)
                    renderHitSub(subChart, r)
                    renderPctScatter(scatterChart, r)
                    renderHit(hitChart, r)
                } catch (e: Exception) {
                    status.text = "❌ ${e.message}"
                } finally {
                    btn.isEnabled = true
                }
            }
        }
        return box
    }

    // ════════════════════════ 子页 2：持仓池批量预测 ════════════════════════

    // ── 持仓池结果缓存（切页/重开 APP 保留上次分析卡片）──
    private class ChC(val pct: List<Double>, val price: List<Double>)
    private class PerC(val last: Double, val a: ChC?, val b: ChC?, val c: ChC?)
    private class RowC(val code: String, val name: String, val dN: Int, val hN: Int,
                       val dLastDt: String?, val hLastDt: String?, val d: PerC?, val h: PerC?)

    private fun passToChC(p: PhaseSpaceEngine.Pass?): ChC? = p?.let { ChC(it.fcPct, it.fcPrices) }
    private fun resToPerC(r: PhaseSpaceEngine.Result?): PerC? =
        r?.let { PerC(it.lastPrice, passToChC(it.price), passToChC(it.cycle), passToChC(it.ret)) }

    private fun poolCacheFile() = java.io.File(requireContext().filesDir, "phase_pool_cache.json")

    private fun savePoolCache(rows: List<RowC>) {
        runCatching {
            fun chJ(c: ChC?): JSONObject? = c?.let {
                JSONObject().apply { put("pct", JSONArray(it.pct)); put("price", JSONArray(it.price)) }
            }
            fun perJ(p: PerC?): JSONObject? = p?.let {
                JSONObject().apply {
                    put("last", it.last)
                    chJ(it.a)?.let { a -> put("a", a) }
                    chJ(it.b)?.let { b -> put("b", b) }
                    chJ(it.c)?.let { c -> put("c", c) }
                }
            }
            val arr = JSONArray()
            rows.forEach { r ->
                arr.put(JSONObject().apply {
                    put("code", r.code); put("name", r.name)
                    put("dN", r.dN); put("hN", r.hN)
                    put("dLastDt", r.dLastDt ?: ""); put("hLastDt", r.hLastDt ?: "")
                    perJ(r.d)?.let { put("d", it) }; perJ(r.h)?.let { put("h", it) }
                })
            }
            poolCacheFile().writeText(JSONObject().apply {
                put("savedAt", PhaseSpaceLedger.nowStr()); put("rows", arr)
            }.toString())
        }
    }

    private fun loadPoolCache(): Pair<String, List<RowC>>? = runCatching {
        val root = JSONObject(poolCacheFile().readText())
        fun jCh(o: JSONObject?): ChC? = o?.let {
            val pa = it.optJSONArray("pct"); val pr = it.optJSONArray("price")
            ChC(if (pa != null) List(pa.length()) { i -> pa.getDouble(i) } else emptyList(),
                if (pr != null) List(pr.length()) { i -> pr.getDouble(i) } else emptyList())
        }
        fun jPer(o: JSONObject?): PerC? = o?.let {
            PerC(it.optDouble("last"), jCh(it.optJSONObject("a")),
                jCh(it.optJSONObject("b")), jCh(it.optJSONObject("c")))
        }
        val arr = root.getJSONArray("rows")
        val rows = ArrayList<RowC>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            rows.add(RowC(o.optString("code"), o.optString("name"), o.optInt("dN"), o.optInt("hN"),
                o.optString("dLastDt").ifEmpty { null }, o.optString("hLastDt").ifEmpty { null },
                jPer(o.optJSONObject("d")), jPer(o.optJSONObject("h"))))
        }
        root.optString("savedAt") to rows
    }.getOrNull()

    /** 持仓池页为空时渲染上次缓存卡片 */
    private fun renderPoolCacheIfEmpty() {
        val pb = poolBox ?: return
        if (pb.childCount > 0) return
        val (savedAt, rows) = loadPoolCache() ?: return
        if (rows.isEmpty()) return
        rows.forEach { r ->
            pb.addView(poolRow(SsaPoolStore.PoolMember(r.code, r.name, "", 10, 20, 60, 2, 0),
                r.d, r.h, r.dN, r.hN, r.dLastDt, r.hLastDt))
        }
        poolStatus?.text = "📦 上次分析缓存（$savedAt）——点「批量预测」刷新"
    }

    private fun buildPoolPage(): View {
        val ctx = requireContext()
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(tv("对「混合策略建议」持仓池逐票双通道预测：小时=当天 1H 未来4根，日线=未来5日。" +
            "方向以 B(cycle) 为主，A/B 一致标注强弱。预测自动存台账，盘后验证页回填。", GRAY, 10f))
        val btn = Button(ctx).apply { text = "▶ 批量预测持仓池"; textSize = 13f }
        box.addView(btn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40))
            .apply { topMargin = dp(6) })
        val btnIn = Button(ctx).apply { text = "🔄 盘中更新小时预测（缓存不新鲜自动联网，与单票页同策略）"; textSize = 13f }
        box.addView(btnIn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40))
            .apply { topMargin = dp(4) })
        poolStatus = tv("等待…", GRAY, 11f)
        box.addView(poolStatus)
        poolBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(poolBox)

        btn.setOnClickListener {
            btn.isEnabled = false
            lifecycleScope.launch { runPoolBatch(); btn.isEnabled = true }
        }
        btnIn.setOnClickListener {
            btnIn.isEnabled = false
            lifecycleScope.launch { runPoolBatch(intradayRefresh = true); btnIn.isEnabled = true }
        }
        return box
    }

    private suspend fun runPoolBatch(intradayRefresh: Boolean = false) {
        val ctx = requireContext()
        val members = withContext(Dispatchers.IO) { SsaPoolStore.load(ctx) }
        val pb = poolBox ?: return; val st = poolStatus ?: return
        pb.removeAllViews()
        if (members.isEmpty()) { st.text = "⚠️ 持仓池为空——请先到「混合策略建议」页添加持仓"; return }
        val fetcher = com.chin.stockanalysis.strategy.data.IntradayKlineFetcher(ctx)
        val today = LocalDate.now().toString()
        val ts = PhaseSpaceLedger.nowStr()
        val recs = ArrayList<PhaseSpaceLedger.Rec>()
        val rowCaches = ArrayList<RowC>()
        members.forEachIndexed { idx, m ->
            val full = fullCode(m.code)
            var rowD: PhaseSpaceEngine.Result? = null
            var rowH: PhaseSpaceEngine.Result? = null
            var dDates = listOf<String>(); var hDts = listOf<String>()
            var dN = 0; var hN = 0
            try {
                st.text = "⏳ ${idx + 1}/${members.size} ${m.name} 日线…"
                val de = withContext(Dispatchers.IO) { fetcher.fetchDailyHistory(full, preferCache = false) }
                dN = de.size
                if (de.size >= 120) {
                    dDates = de.map { it.datetime.substring(0, 10) }
                    rowD = withContext(Dispatchers.Default) {
                        PhaseSpaceEngine.analyze(dDates, de.map { it.close }.toDoubleArray(), horizon = 5)
                    }
                }
                st.text = "⏳ ${idx + 1}/${members.size} ${m.name} 小时…"
                val he = withContext(Dispatchers.IO) { fetcher.fetchHourlyHistory(full, preferCache = false) }
                hN = he.size
                if (he.size >= 120) {
                    hDts = he.map { it.datetime }
                    rowH = withContext(Dispatchers.Default) {
                        PhaseSpaceEngine.analyze(hDts, he.map { it.close }.toDoubleArray(), horizon = 4)
                    }
                }
            } catch (e: Exception) { /* 单票失败不中断 */ }

            // 存台账
            rowD?.let { r ->
                for (h in 1..5) {
                    recs.add(PhaseSpaceLedger.Rec(today, ts, m.code, m.name, "daily", h,
                        r.lastPrice, dDates.lastOrNull() ?: today,
                        r.price.fcPrices.getOrNull(h - 1), r.cycle?.fcPrices?.getOrNull(h - 1)))
                }
            }
            rowH?.let { r ->
                for (h in 1..4) {
                    recs.add(PhaseSpaceLedger.Rec(today, ts, m.code, m.name, "hourly", h,
                        r.lastPrice, hDts.lastOrNull() ?: "",
                        r.price.fcPrices.getOrNull(h - 1), r.cycle?.fcPrices?.getOrNull(h - 1)))
                }
            }
            val dP = resToPerC(rowD); val hP = resToPerC(rowH)
            rowCaches.add(RowC(m.code, m.name, dN, hN, dDates.lastOrNull(), hDts.lastOrNull(), dP, hP))
            pb.addView(poolRow(m, dP, hP, dN, hN, dDates.lastOrNull(), hDts.lastOrNull()))
        }
        PhaseSpaceLedger.appendAll(ctx, recs)
        withContext(Dispatchers.IO) { savePoolCache(rowCaches) }
        st.text = (if (intradayRefresh) "✅ 盘中更新完成" else "✅ 完成") +
            " ${members.size} 票，台账 +${recs.size} 条（重复基准自动去重，盘后验证页回填）"
    }

    /** A股小时 K 4 个交易时段（bar 收盘时刻） */
    private val H_SLOTS = listOf("10:30", "11:30", "14:00", "15:00")

    /** 日线未来 n 根目标日期标签（M/d，跳周末；解析失败回退 T+i） */
    private fun dayTargetLabels(lastDt: String?, n: Int): List<String> {
        var d = runCatching { LocalDate.parse(lastDt?.take(10)) }.getOrNull()
            ?: return (1..n).map { "T+$it" }
        return (1..n).map {
            do { d = d.plusDays(1) } while (d.dayOfWeek.value > 5)
            "${d.monthValue}/${d.dayOfMonth}"
        }
    }

    /** 从最后 bar 时刻推未来 n 根的目标时刻标签：当日省略日期，跨日带 M/d 前缀（跳周末） */
    private fun hourTargetLabels(lastDt: String?, n: Int): List<String> {
        if (lastDt == null || lastDt.length < 16) return (1..n).map { "T+$it" }
        val tm = lastDt.substring(11, 16)
        var idx = H_SLOTS.indexOf(tm)
        var d = runCatching { LocalDate.parse(lastDt.substring(0, 10)) }.getOrNull()
            ?: return (1..n).map { "T+$it" }
        val today = LocalDate.now()
        val out = ArrayList<String>(n)
        repeat(n) {
            idx++
            if (idx >= 4) { idx = 0; do { d = d.plusDays(1) } while (d.dayOfWeek.value > 5) }
            val prefix = if (d == today) "" else "${d.monthValue}/${d.dayOfMonth} "
            out.add(prefix + H_SLOTS[idx])
        }
        return out
    }

    /** 池行：名称代码 / 小时4时段预测价 / 明日 / 5日，方向以 B 为主、A 一致性标色 */
    private fun poolRow(m: SsaPoolStore.PoolMember, d: PerC?, h: PerC?,
                        dN: Int, hN: Int, dLastDt: String?, hLastDt: String?): View {
        val ctx = requireContext()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(6).toFloat() }
        }
        box.addView(tv("${m.name}  ${m.code}", 12f, true))
        if (d == null && h == null) {
            box.addView(tv("数据不足（日${dN}根/时${hN}根，需≥120）——单票页先跑一次可建缓存", RED, 11f))
            return LinearLayout(ctx).apply { setPadding(0, 0, 0, dp(6)); addView(box) }
        }
        fun dirOf(r: PerC?, i: Int): Triple<String, Int, String>? {
            if (r == null) return null
            val b = r.b?.pct?.getOrNull(i) ?: return null
            val a = r.a?.pct?.getOrNull(i)
            val c = r.c?.pct?.getOrNull(i)
            val arrow = when { b > 0.3 -> "↑"; b < -0.3 -> "↓"; else -> "→" }
            val consistent = a != null && (a > 0) == (b > 0)
            val color = when {
                b > 0.3 -> if (consistent) RED else ORANGE      // A股红涨
                b < -0.3 -> if (consistent) GREEN else ORANGE
                else -> GRAY
            }
            return Triple(arrow, color, "B %+.1f%%".format(b) +
                (a?.let { " / A %+.1f%%".format(it) } ?: "") +
                (c?.let { " / C %+.1f%%".format(it) } ?: ""))
        }
        // 小时 4 时段预测价（上行=B 法价+方向；下行=C 收益率法价+方向；A/B 分歧标橙）
        if (h != null) {
            val labels = hourTargetLabels(hLastDt, 4)
            val cells = (0..3).map { i ->
                val b = h.b?.pct?.getOrNull(i)
                val price = h.b?.price?.getOrNull(i)
                if (b == null || price == null) null
                else {
                    val arrow = when { b > 0.3 -> "↑"; b < -0.3 -> "↓"; else -> "→" }
                    val a = h.a?.pct?.getOrNull(i)
                    val consistent = a != null && (a > 0) == (b > 0)
                    val color = when {
                        b > 0.3 -> if (consistent) RED else ORANGE      // A股红涨
                        b < -0.3 -> if (consistent) GREEN else ORANGE
                        else -> GRAY
                    }
                    val cPct = h.c?.pct?.getOrNull(i)
                    val cPrice = h.c?.price?.getOrNull(i)
                    val cLine = if (cPct != null && cPrice != null) {
                        val cArrow = when { cPct > 0.3 -> "↑"; cPct < -0.3 -> "↓"; else -> "→" }
                        "\nC %.2f%s（%+.1f%%）".format(cPrice, cArrow, cPct)
                    } else ""
                    ("%s %.2f%s".format(labels[i], price, arrow) + cLine) to color
                }
            }
            val hBase = hLastDt?.takeIf { it.length >= 16 }?.substring(5, 16)
            box.addView(tv("小时预测（B价+C价，现价 %.2f%s）".format(h.last,
                if (hBase == null) "" else "（$hBase）"), DARK, 11f))
            for (row in 0..1) {
                val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                for (col in 0..1) {
                    val c = cells[row * 2 + col]
                    line.addView(tv(c?.first ?: "—", c?.second ?: GRAY, 11f),
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                }
                box.addView(line)
            }
        }
        val dLabels = dayTargetLabels(dLastDt, 5)
        fun dLab(cap: String, i: Int) =
            if (dLabels[i].startsWith("T+")) cap else "$cap（${dLabels[i]}）"
        dirOf(d, 0)?.let { (arrow, color, detail) ->
            box.addView(tv("${dLab("明日", 0)}  $arrow  $detail", color, 11f))
        }
        dirOf(d, 4)?.let { (arrow, color, detail) ->
            box.addView(tv("${dLab("5日", 4)}  $arrow  $detail", color, 11f))
        }
        // ── 动作结论：日B/日C 定方向，时B/时C 定买点与承接（标签点明时间尺度）──
        run {
            val dB = d?.b?.pct?.getOrNull(0)          // 日线明日 B
            val dC = d?.c?.pct?.getOrNull(0)          // 日线明日 C
            if (dB == null && dC == null) return@run
            val hBPct = h?.b?.pct?.lastOrNull()       // 小时 B 第4时段
            val hCPct = h?.c?.pct?.lastOrNull()       // 小时 C 第4时段
            val hBPrices = h?.b?.price?.filterNotNull() ?: emptyList()
            val hCPrices = h?.c?.price?.filterNotNull() ?: emptyList()
            fun w(v: Double?) = when { v == null -> "—"; v > 0.3 -> "↑"; v < -0.3 -> "↓"; else -> "→" }
            val bUpD = (dB ?: 0.0) > 0.3; val bDnD = (dB ?: 0.0) < -0.3
            val bUpH = (hBPct ?: 0.0) > 0.3; val bDnH = (hBPct ?: 0.0) < -0.3
            val cUpH = (hCPct ?: 0.0) > 0.3; val cDnH = (hCPct ?: 0.0) < -0.3
            val dCdn = (dC ?: 0.0) < -0.3
            val cLo = hCPrices.minOrNull(); val cHi = hCPrices.maxOrNull()
            var lowSlot = -1; var lowPrice = 0.0
            hBPrices.forEachIndexed { i, p -> if (lowSlot < 0 || p < lowPrice) { lowSlot = i; lowPrice = p } }
            val slotLab = if (lowSlot >= 0) hourTargetLabels(hLastDt, 4).getOrElse(lowSlot) { "" } else ""
            val stop = cLo?.let { "；破 %.2f 放弃".format(it * 0.99) } ?: ""
            // 无小时数据时退化为日 B/C 规则
            if (h == null || (hBPct == null && hCPct == null)) {
                val (act, color) = when {
                    bUpD && !dCdn -> "日B/日C 共振向上 → 持仓/可买" to RED
                    bUpD && dCdn -> "看多不追，等回踩低吸" to ORANGE
                    bDnD && (dC ?: 0.0) > 0.3 -> "反弹减仓，不开新仓" to GRAY
                    bDnD && dCdn -> "日B/日C 同空 → 回避" to GREEN
                    else -> "日B 走平 → 观望" to GRAY
                }
                box.addView(tv("▶ 结论：日B${w(dB)}日C${w(dC)} → $act", color, 11f))
                return@run
            }
            val tag = "日B${w(dB)}·时B${w(hBPct)}·时C${w(hCPct)}"
            val note = if (bUpD && dCdn) "；日C↓无趋势加持，到位兑现" else ""
            val (act, color) = when {
                bUpD && bUpH && !cDnH -> "双周期共振 → 持仓/可买" to RED
                bUpD && bUpH && cDnH -> "看多但短线缺动量，回踩时C %.2f~%.2f 低吸".format(
                    cLo ?: 0.0, cHi ?: 0.0) + stop to ORANGE
                bUpD && bDnH && cUpH && lowSlot >= 0 ->
                    "上升周期回踩有承接，买在 $slotLab 附近 %.2f".format(lowPrice) to RED
                bUpD && bDnH -> "回踩偏深，等时C企稳，底线 %.2f".format(cLo ?: lowPrice) + stop to ORANGE
                bDnD && bUpH -> "日内反抽不追，时B高位减仓，不开新仓" to GRAY
                bDnD && bDnH -> "双周期同空 → 回避" to GREEN
                bUpD -> "看多，等小时方向明朗" to ORANGE
                bDnD -> "偏空观望" to GREEN
                else -> "方向走平 → 观望" to GRAY
            }
            box.addView(tv("▶ 结论：$tag → $act$note", color, 11f))
        }
        box.addView(tv("方向以 B(cycle) 为主；橙色=A/B 分歧", GRAY, 9f))
        return LinearLayout(ctx).apply {
            setPadding(0, 0, 0, dp(6)); addView(box)
        }
    }

    // ════════════════════════ 子页 4：多周期融合（日线定方向 + 1H 定时点）+ C 收益率通道 ════════════════════════

    private fun buildFusePage(): View {
        val ctx = requireContext()
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(tv("日线 B(cycle) 法定中期方向，1H B 法定入场/离场时点；" +
            "C=对数收益率嵌入通道（去水平只留变动节奏，预测价=逐步收益率累乘还原）。", GRAY, 10f))
        val codeEt = EditText(ctx).apply {
            hint = "股票代码，如 600188"; textSize = 13f
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val btn = Button(ctx).apply { text = "🧭 融合分析"; textSize = 13f }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(codeEt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f))
        row.addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(row)
        val st = tv("输入代码点「融合分析」", GRAY, 11f)
        box.addView(st)
        val fuseTv = tv("", DARK, 12f)
        box.addView(fuseTv)
        box.addView(tv("C 收益率通道·预测对比（蓝=实际；绿细=走查1步拟合；绿虚线=未来预测）", 11f, true)
            .apply { setPadding(0, dp(10), 0, dp(4)) })
        val cChart = LineChart(ctx).apply {
            description.isEnabled = false
            setTouchEnabled(true); isDragEnabled = true; setScaleEnabled(true)
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            axisRight.isEnabled = false
        }
        box.addView(cChart, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220)))

        btn.setOnClickListener {
            val code = codeEt.text?.toString()?.trim().orEmpty()
            if (code.length != 6) { st.text = "请输入 6 位代码"; return@setOnClickListener }
            btn.isEnabled = false
            lifecycleScope.launch {
                try {
                    st.text = "⏳ 拉取日线+小时线…"
                    val full = fullCode(code)
                    val fetcher = com.chin.stockanalysis.strategy.data.IntradayKlineFetcher(ctx)
                    val de = withContext(Dispatchers.IO) { fetcher.fetchDailyHistory(full, preferCache = true) }
                    val he = withContext(Dispatchers.IO) { fetcher.fetchHourlyHistory(full, preferCache = true) }
                    if (de.size < 120 || he.size < 120) {
                        st.text = "⚠️ 数据不足（日${de.size}根/时${he.size}根，需≥120）——单票页先跑一次可建缓存"
                        return@launch
                    }
                    st.text = "⏳ 双周期重构中（约数秒）…"
                    val d = withContext(Dispatchers.Default) {
                        PhaseSpaceEngine.analyze(de.map { it.datetime.substring(0, 10) },
                            de.map { it.close }.toDoubleArray(), horizon = 5)
                    }
                    val h = withContext(Dispatchers.Default) {
                        PhaseSpaceEngine.analyze(he.map { it.datetime },
                            he.map { it.close }.toDoubleArray(), horizon = 4)
                    }
                    if (d == null || h == null) { st.text = "⚠️ 分析失败"; return@launch }
                    st.text = "✅ 完成（日${de.size}根/时${he.size}根）"
                    fuseTv.text = buildFuseText(d, h, he.last().datetime)
                    renderRetChart(cChart, d)
                } catch (e: Exception) {
                    st.text = "❌ ${e.message}"
                } finally {
                    btn.isEnabled = true
                }
            }
        }
        return box
    }

    /** 融合文本：日线方向 → 小时时点建议 */
    private fun buildFuseText(d: PhaseSpaceEngine.Result, h: PhaseSpaceEngine.Result, hLastDt: String): String {
        val sb = StringBuilder()
        // ── 日线方向（B 为主，A/C 参考）──
        val dB = d.cycle?.fcPct
        val dAvg = dB?.average() ?: d.price.fcPct.average()
        val dirCn = when { dAvg > 0.3 -> "多 ↑"; dAvg < -0.3 -> "空 ↓"; else -> "中性 →" }
        sb.append("【日线方向】%+.2f%%（5日均） → %s\n".format(dAvg, dirCn))
        sb.append("  A ").append(d.price.fcPct.joinToString(" ") { "%+.1f".format(it) })
        dB?.let { sb.append("　B ").append(it.joinToString(" ") { "%+.1f".format(it) }) }
        d.ret?.let { sb.append("　C ").append(it.fcPct.joinToString(" ") { "%+.1f".format(it) }) }
        sb.append("\n")
        // ── 小时时点 ──
        val labels = hourTargetLabels(hLastDt, 4)
        val hB = h.cycle
        sb.append("【1H 时点】\n")
        for (i in 0..3) {
            val pb = hB?.fcPrices?.getOrNull(i)
            val pct = hB?.fcPct?.getOrNull(i)
            sb.append("  %s  %s\n".format(labels[i],
                if (pb != null && pct != null) "%.2f（%+.2f%%）".format(pb, pct) else "—"))
        }
        // ── 融合建议 ──
        sb.append("【融合建议】")
        if (hB == null || hB.fcPrices.size < 4) { sb.append("小时 cycle 通道不可用，仅参考日线方向。"); return sb.toString() }
        val prices = hB.fcPrices
        if (dAvg > 0.3) {
            val dip = prices.indices.minByOrNull { prices[it] } ?: 0
            sb.append("日线多——回调低吸时点看 ${labels[dip]}（预测低点 %.2f）；".format(prices[dip]))
            sb.append(if (prices.last() > prices[dip]) "其后回升，可持有。" else "但其后未回升，低吸后需盯盘止损。")
        } else if (dAvg < -0.3) {
            val top = prices.indices.maxByOrNull { prices[it] } ?: 0
            sb.append("日线空——反弹减仓时点看 ${labels[top]}（预测高点 %.2f）；".format(prices[top]))
            sb.append("不建议新开仓。")
        } else {
            sb.append("日线中性无方向——观望，等日线 B 给出 ±0.3%% 以上的方向再动。")
        }
        // ── C 通道质量参考 ──
        d.ret?.let {
            sb.append("\n【C 收益率通道】滚动命中 ").append(it.rollHit.joinToString("/") { "%.0f".format(it) })
                .append("%，涨跌幅相关 h1=").append("%.2f".format(it.corrPctByH.getOrNull(0) ?: 0.0))
        }
        return sb.toString()
    }

    /** C 收益率通道预测对比图（日线窗口） */
    private fun renderRetChart(chart: LineChart, r: PhaseSpaceEngine.Result) {
        val p = r.ret ?: run { chart.clear(); return }
        val n = p.actual.size
        val sets = ArrayList<LineDataSet>()
        sets.add(LineDataSet(p.actual.mapIndexed { i, v -> Entry(i.toFloat(), v.toFloat()) }, "实际").apply {
            color = BLUE; lineWidth = 2f; setDrawCircles(false); setDrawValues(false)
        })
        sets.add(LineDataSet(p.fitted.mapIndexedNotNull { i, v -> v?.let { Entry(i.toFloat(), it.toFloat()) } }, "C拟合").apply {
            color = GREEN; lineWidth = 1.3f; setDrawCircles(false); setDrawValues(false)
        })
        val e = ArrayList<Entry>()
        e.add(Entry((n - 1).toFloat(), r.lastPrice.toFloat()))
        p.fcPrices.forEachIndexed { i, v -> e.add(Entry((n + i).toFloat(), v.toFloat())) }
        sets.add(LineDataSet(e, "C预测").apply {
            color = GREEN; lineWidth = 2.2f; enableDashedLine(10f, 6f, 0f)
            setDrawCircles(true); circleRadius = 3.5f; setCircleColors(listOf(GREEN))
            setDrawCircleHole(false); setDrawValues(true); valueTextSize = 9f; valueTextColor = GREEN
        })
        chart.data = LineData(sets.map { it as com.github.mikephil.charting.interfaces.datasets.ILineDataSet })
        chart.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val i = value.toInt()
                if (i in p.histDates.indices) return p.histDates[i].substring(5)
                return "T+" + (i - n + 1)
            }
        }
        chart.invalidate()
    }

    // ════════════════════════ 子页 3：盘后验证 ════════════════════════

    private fun buildVerifyPage(): View {
        val ctx = requireContext()
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(tv("收盘后点「回填验证」：对台账中未验证记录拉取实际价格，判定两法方向命中并统计。" +
            "小时预测对当天后续小时，日线预测对后续交易日。", GRAY, 10f))
        val btn = Button(ctx).apply { text = "✅ 回填验证"; textSize = 13f }
        box.addView(btn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40))
            .apply { topMargin = dp(6) })
        verifyStatus = tv("", GRAY, 11f)
        box.addView(verifyStatus)
        verifyStatTv = tv("", DARK, 12f)
        box.addView(verifyStatTv)
        verifyBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(verifyBox)

        btn.setOnClickListener {
            btn.isEnabled = false
            lifecycleScope.launch { runVerify(); refreshVerifyList(); btn.isEnabled = true }
        }
        return box
    }

    private suspend fun runVerify() {
        val ctx = requireContext()
        val st = verifyStatus ?: return
        val removed = PhaseSpaceLedger.compact(ctx)   // 先清理历史重复行
        if (removed > 0) st.text = "🧹 清理重复台账 $removed 条…"
        val all = PhaseSpaceLedger.loadAll(ctx)
        val pending = all.filter { it.actual == null }
        if (pending.isEmpty()) { st.text = "没有待验证记录"; return }
        val fetcher = com.chin.stockanalysis.strategy.data.IntradayKlineFetcher(ctx)
        val verified = ArrayList<PhaseSpaceLedger.Rec>()
        val codes = pending.map { it.code }.distinct()
        codes.forEachIndexed { ci, code ->
            st.text = "⏳ 回填 ${ci + 1}/${codes.size} $code …"
            val full = fullCode(code)
            val dBars = runCatching { withContext(Dispatchers.IO) { fetcher.fetchDailyHistory(full) } }.getOrNull()
            val hBars = runCatching { withContext(Dispatchers.IO) { fetcher.fetchHourlyHistory(full) } }.getOrNull()
            for (r in pending.filter { it.code == code }) {
                val (dt, act) = if (r.mode == "daily") {
                    val bars = dBars ?: continue
                    val idx = bars.indexOfFirst { it.datetime.substring(0, 10) > r.lastDt.substring(0, 10) }
                    if (idx < 0) continue
                    val tgt = bars.getOrNull(idx + r.h - 1) ?: continue
                    tgt.datetime.substring(0, 10) to tgt.close
                } else {
                    val bars = hBars ?: continue
                    val idx = bars.indexOfFirst { it.datetime > r.lastDt }
                    if (idx < 0) continue
                    val tgt = bars.getOrNull(idx + r.h - 1) ?: continue
                    tgt.datetime to tgt.close
                }
                verified.add(r.copy(actual = act, actualDt = dt,
                    hitA = PhaseSpaceLedger.judge(r.last, r.predA, act),
                    hitB = PhaseSpaceLedger.judge(r.last, r.predB, act)))
            }
        }
        PhaseSpaceLedger.updateVerified(ctx, verified)
        st.text = "✅ 回填 ${verified.size} 条"
    }

    private fun refreshVerifyList() {
        val ctx = requireContext(); val vb = verifyBox ?: return
        vb.removeAllViews()
        lifecycleScope.launch {
            val all = PhaseSpaceLedger.loadAll(ctx)
            if (all.isEmpty()) { verifyStatus?.text = "台账为空——先在持仓池页批量预测"; return@launch }
            val done = all.filter { it.actual != null }
            // 统计：按 mode × 方法 命中率
            fun hitRate(rs: List<PhaseSpaceLedger.Rec>, sel: (PhaseSpaceLedger.Rec) -> Boolean?): String {
                val v = rs.mapNotNull(sel); if (v.isEmpty()) return "-"
                return "%.0f%%(%d)".format(v.count { it } * 100.0 / v.size, v.size)
            }
            verifyStatTv?.text = "已验证 ${done.size}/${all.size} 条 方向命中率：\n" +
                "  日线  A=${hitRate(done.filter { it.mode == "daily" }) { it.hitA }}  B=${hitRate(done.filter { it.mode == "daily" }) { it.hitB }}\n" +
                "  小时  A=${hitRate(done.filter { it.mode == "hourly" }) { it.hitA }}  B=${hitRate(done.filter { it.mode == "hourly" }) { it.hitB }}"
            // 按票分组显示：每票最新 12 条（新→旧），避免全局截断导致某些票看不到
            done.groupBy { it.code }.forEach { (_, rs) ->
                vb.addView(tv("── ${rs.first().name} ${rs.first().code} ──", DARK, 10f))
                rs.sortedWith(compareByDescending<PhaseSpaceLedger.Rec> { it.date }
                    .thenByDescending { it.ts }.thenBy { it.mode }.thenBy { it.h })
                    .take(12).forEach { r ->
                        val mark = { hit: Boolean? -> when (hit) { true -> "✓"; false -> "✗"; null -> "·" } }
                        val line = "  %s %s T+%d  基%.2f→实%.2f(%+.1f%%)  A%s B%s".format(
                            r.date.substring(5), if (r.mode == "daily") "日" else "时", r.h,
                            r.last, r.actual ?: 0.0, ((r.actual ?: r.last) / r.last - 1) * 100,
                            mark(r.hitA), mark(r.hitB))
                        val color = if (r.hitB == true) TEAL else if (r.hitB == false) RED else GRAY
                        vb.addView(tv(line, color, 10f))
                    }
            }
        }
    }

    // ════════════════════════ 取数 & 渲染 ════════════════════════

    /** 统一转带前缀代码：先剥离已有 sh/sz/bj 前缀再按首数字判断（持仓池 code 可能已带前缀，直接拼会出 szsh600206） */
    private fun fullCode(code: String): String {
        val pure = code.removePrefix("sh").removePrefix("sz").removePrefix("bj")
        return if (pure[0] in "659") "sh$pure" else "sz$pure"
    }

    // ── 取数：1H=IntradayKlineFetcher 小时线；日线=3 年本地缓存+增量（fetchDailyHistory）──
    // 返回 dates/closes + OHLC（副图十字星判定用；失败时 closes 为空）
    private suspend fun fetchCloses(code: String, hourly: Boolean):
            Triple<List<String>, DoubleArray, Triple<DoubleArray, DoubleArray, DoubleArray>?> {
        val full = fullCode(code)
        val fetcher = com.chin.stockanalysis.strategy.data.IntradayKlineFetcher(requireContext())
        val entities = if (hourly) fetcher.fetchHourlyHistory(full) else fetcher.fetchDailyHistory(full)
        if (entities.isEmpty()) return Triple(emptyList(), DoubleArray(0), null)
        val dts = if (hourly) entities.map { it.datetime } else entities.map { it.datetime.substring(0, 10) }
        return Triple(dts, entities.map { it.close }.toDoubleArray(),
            Triple(entities.map { it.open }.toDoubleArray(),
                entities.map { it.high }.toDoubleArray(),
                entities.map { it.low }.toDoubleArray()))
    }

    private fun renderCard(card: LinearLayout, paramTvA: TextView, paramTvB: TextView, paramTvC: TextView,
                           corrTv: TextView, fcTv: TextView, r: PhaseSpaceEngine.Result) {
        fun paramLine(p: PhaseSpaceEngine.Pass) = "τ=${p.tau}（AMI 首极小）· m=${p.m}（FNN " +
            p.fnnPct.mapIndexed { i, v -> "m${i + 1}=%.0f%%".format(v) }.take(4).joinToString(" ") + "）"
        paramTvA.text = "【A ${r.price.label}】" + paramLine(r.price)
        paramTvB.text = r.cycle?.let { "【B ${it.label}】" + paramLine(it) } ?: "【B cycle法】SSA 分解失败，无此通道"
        paramTvC.text = r.ret?.let { "【C ${it.label}】" + paramLine(it) } ?: "【C 收益率】无"

        val sb = StringBuilder()
        sb.append("末60根走查相关 h=1..${r.price.corrByH.size}：\n")
        sb.append("  A: ").append(r.price.corrByH.joinToString("  ") { "%.3f".format(it) }).append("\n")
        r.cycle?.let { sb.append("  B: ").append(it.corrByH.joinToString("  ") { "%.3f".format(it) }).append("\n") }
        r.ret?.let { sb.append("  C: ").append(it.corrByH.joinToString("  ") { "%.3f".format(it) }).append("\n") }
        sb.append("涨跌幅口径相关（预测Δ% vs 实际Δ%）：\n")
        sb.append("  A: ").append(r.price.corrPctByH.joinToString("  ") { "%.3f".format(it) }).append("\n")
        r.cycle?.let { sb.append("  B: ").append(it.corrPctByH.joinToString("  ") { "%.3f".format(it) }).append("\n") }
        r.ret?.let { sb.append("  C: ").append(it.corrPctByH.joinToString("  ") { "%.3f".format(it) }).append("\n") }
        sb.append("滚动回测（A=${r.price.rollN} 根）方向命中率%（随机=50）：\n")
        sb.append("  A: ").append(r.price.rollHit.joinToString("  ") { "%.0f".format(it) })
        r.cycle?.let { sb.append("\n  B: ").append(it.rollHit.joinToString("  ") { "%.0f".format(it) }) }
        r.ret?.let { sb.append("\n  C: ").append(it.rollHit.joinToString("  ") { "%.0f".format(it) }) }
        corrTv.text = sb.toString()

        val baseDt = r.price.histDates.lastOrNull().orEmpty()
        val fc = StringBuilder("现价 %.2f".format(r.lastPrice))
        if (baseDt.isNotBlank()) fc.append("（$baseDt）")
        fc.append(" → 未来 ${r.price.fcPrices.size} 根预测价：\n")
        // 目标时刻标签：小时线用 4 时段推算，日线用交易日日期（跳周末）
        val tgts = if (baseDt.length >= 16) hourTargetLabels(baseDt, r.price.fcPrices.size)
        else dayTargetLabels(baseDt, r.price.fcPrices.size)
        r.price.fcPrices.indices.forEach { i ->
            fc.append("  T+%d（%s）  A: %.2f（%+.2f%%）".format(i + 1, tgts.getOrElse(i) { "T+${i + 1}" },
                r.price.fcPrices[i], r.price.fcPct[i]))
            r.cycle?.let { c ->
                if (i < c.fcPrices.size)
                    fc.append("   B: %.2f（%+.2f%%）".format(c.fcPrices[i], c.fcPct[i]))
            }
            r.ret?.let { c ->
                if (i < c.fcPrices.size)
                    fc.append("   C: %.2f（%+.2f%%）".format(c.fcPrices[i], c.fcPct[i]))
            }
            fc.append("\n")
        }
        fcTv.text = fc.toString().trimEnd()
        card.visibility = View.VISIBLE
    }

    /** 吸引子：整条轨迹按时间分 12 段，青→红渐变；当前状态白点 */
    private fun renderAttractor(chart: LineChart, p: PhaseSpaceEngine.Pass) {
        val pts = p.attractor
        if (pts.size < 2) { chart.clear(); return }
        val segN = 12
        val per = (pts.size + segN - 1) / segN
        val sets = ArrayList<LineDataSet>()
        for (s in 0 until segN) {
            val from = s * per
            val to = minOf(pts.size, from + per + 1)   // +1 保证段间连续
            if (from >= pts.size) break
            val sub = pts.subList(from, to)
            if (sub.size < 2) continue
            val t = s.toFloat() / (segN - 1)
            val color = Color.rgb(
                (Color.red(TEAL) * (1 - t) + Color.red(RED) * t).toInt(),
                (Color.green(TEAL) * (1 - t) + Color.green(RED) * t).toInt(),
                (Color.blue(TEAL) * (1 - t) + Color.blue(RED) * t).toInt())
            sets.add(LineDataSet(sub.map { Entry(it[0], it[1]) }, "").apply {
                this.color = color; lineWidth = 1.6f
                setDrawCircles(false); setDrawValues(false); isHighlightEnabled = false
            })
        }
        // 当前状态白点
        val last = pts.last()
        sets.add(LineDataSet(listOf(Entry(last[0], last[1])), "现在").apply {
            color = Color.WHITE; setDrawCircles(true); circleRadius = 6f
            setCircleColors(listOf(Color.WHITE)); setDrawCircleHole(false)
            setDrawValues(false); lineWidth = 0f
        })
        chart.data = LineData(sets.map { it as com.github.mikephil.charting.interfaces.datasets.ILineDataSet })
        chart.invalidate()
    }

    /** 走查副图：两法 1步命中准确度 10根滑动曲线（0-100%）+ 50% 基准 + 十字星底部散点（与主图同 60 根窗口对齐） */
    private fun renderHitSub(chart: LineChart, r: PhaseSpaceEngine.Result) {
        fun rollCurve(hit: List<Boolean?>): List<Entry> {
            val out = ArrayList<Entry>()
            for (i in hit.indices) {
                val s = maxOf(0, i - 9)
                val w = hit.subList(s, i + 1).filterNotNull()
                if (w.size >= 5) out.add(Entry(i.toFloat(), 100f * w.count { it } / w.size))
            }
            return out
        }
        val sets = ArrayList<LineDataSet>()
        sets.add(LineDataSet(rollCurve(r.price.walkHit), "A命中%").apply {
            color = ORANGE; lineWidth = 1.6f; setDrawCircles(false); setDrawValues(false)
        })
        r.cycle?.let { c ->
            sets.add(LineDataSet(rollCurve(c.walkHit), "B命中%").apply {
                color = PURPLE; lineWidth = 1.6f; setDrawCircles(false); setDrawValues(false)
            })
        }
        val n60 = r.price.actual.size
        if (n60 > 1) sets.add(LineDataSet(listOf(Entry(0f, 50f), Entry((n60 - 1).toFloat(), 50f)), "").apply {
            color = RED; lineWidth = 1f; enableDashedLine(8f, 6f, 0f); setDrawCircles(false); setDrawValues(false)
        })
        if (r.doji.isNotEmpty()) {
            val pts = r.doji.mapIndexedNotNull { i, d -> if (d) Entry(i.toFloat(), 4f) else null }
            if (pts.isNotEmpty()) sets.add(LineDataSet(pts, "十字星").apply {
                color = GRAY; lineWidth = 0f; setDrawCircles(true); circleRadius = 4.5f
                setCircleColors(listOf(GRAY)); setDrawCircleHole(false); setDrawValues(false)
            })
        }
        chart.data = LineData(sets.map { it as com.github.mikephil.charting.interfaces.datasets.ILineDataSet })
        chart.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val i = value.toInt()
                return if (i in r.price.histDates.indices) r.price.histDates[i].substring(5) else ""
            }
        }
        chart.invalidate()
    }

    /** 幅度精度散点：实际Δ%(x) vs 预测Δ%(y)，h=1；45° 灰虚线为完美预测 */
    private fun renderPctScatter(chart: LineChart, r: PhaseSpaceEngine.Result) {
        fun ds(pairs: List<Pair<Float, Float>>, color: Int, label: String): LineDataSet? {
            if (pairs.isEmpty()) return null
            return LineDataSet(pairs.map { Entry(it.first, it.second) }, label).apply {
                this.color = color; lineWidth = 0f
                setDrawCircles(true); circleRadius = 3.5f; setCircleColors(listOf(color))
                setDrawCircleHole(false); setDrawValues(false)
            }
        }
        val sets = ArrayList<LineDataSet>()
        ds(r.price.pctScatter, ORANGE, "A价格")?.let { sets.add(it) }
        r.cycle?.let { c -> ds(c.pctScatter, PURPLE, "B cycle")?.let { sets.add(it) } }
        r.ret?.let { c -> ds(c.pctScatter, GREEN, "C收益率")?.let { sets.add(it) } }
        val all = r.price.pctScatter + (r.cycle?.pctScatter ?: emptyList()) + (r.ret?.pctScatter ?: emptyList())
        if (all.isNotEmpty()) {
            val mn = all.minOf { minOf(it.first, it.second) }
            val mx = all.maxOf { maxOf(it.first, it.second) }
            sets.add(LineDataSet(listOf(Entry(mn, mn), Entry(mx, mx)), "").apply {
                color = GRAY; lineWidth = 1f; enableDashedLine(8f, 6f, 0f)
                setDrawCircles(false); setDrawValues(false)
            })
        }
        chart.data = LineData(sets.map { it as com.github.mikephil.charting.interfaces.datasets.ILineDataSet })
        chart.invalidate()
    }

    /** 预测对比：实际（蓝）+ 两法走查拟合（青/紫细线）+ 两法未来预测（橙/紫虚线，从末日接上） */
    private fun renderForecast(chart: LineChart, r: PhaseSpaceEngine.Result) {
        val n = r.price.actual.size
        val actDs = LineDataSet(r.price.actual.mapIndexed { i, v -> Entry(i.toFloat(), v.toFloat()) }, "实际").apply {
            color = BLUE; lineWidth = 2f; setDrawCircles(false); setDrawValues(false)
        }
        fun fitDs(p: PhaseSpaceEngine.Pass, color: Int, label: String) =
            LineDataSet(p.fitted.mapIndexedNotNull { i, v -> v?.let { Entry(i.toFloat(), it.toFloat()) } }, label).apply {
                this.color = color; lineWidth = 1.3f; setDrawCircles(false); setDrawValues(false)
            }
        fun fcDs(p: PhaseSpaceEngine.Pass, color: Int, label: String, showVal: Boolean): LineDataSet {
            val e = ArrayList<Entry>()
            e.add(Entry((n - 1).toFloat(), r.lastPrice.toFloat()))
            p.fcPrices.forEachIndexed { i, v -> e.add(Entry((n + i).toFloat(), v.toFloat())) }
            return LineDataSet(e, label).apply {
                this.color = color; lineWidth = 2.2f; enableDashedLine(10f, 6f, 0f)
                setDrawCircles(true); circleRadius = 3.5f; setCircleColors(listOf(color))
                setDrawCircleHole(false); setDrawValues(showVal); valueTextSize = 9f; valueTextColor = color
            }
        }
        val sets = ArrayList<LineDataSet>()
        sets.add(actDs)
        sets.add(fitDs(r.price, TEAL, "A拟合"))
        r.cycle?.let { sets.add(fitDs(it, PURPLE, "B拟合")) }
        sets.add(fcDs(r.price, ORANGE, "A预测", true))
        r.cycle?.let { sets.add(fcDs(it, PURPLE, "B预测", false)) }
        chart.data = LineData(sets.map { it as com.github.mikephil.charting.interfaces.datasets.ILineDataSet })
        chart.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val i = value.toInt()
                if (i in r.price.histDates.indices) return r.price.histDates[i].substring(5)
                return "T+" + (i - n + 1)
            }
        }
        chart.invalidate()
    }

    /** 滚动回测：两法 1步方向命中率滑动曲线 + 50% 随机基准线 */
    private fun renderHit(chart: LineChart, r: PhaseSpaceEngine.Result) {
        if (r.price.hitCurve.size < 2) { chart.clear(); return }
        val dsA = LineDataSet(r.price.hitCurve.mapIndexed { i, v -> Entry(i.toFloat(), v.toFloat()) }, "A价格法%").apply {
            color = ORANGE; lineWidth = 1.8f; setDrawCircles(false); setDrawValues(false)
            setDrawFilled(true); fillColor = ORANGE; fillAlpha = 30
        }
        val sets = ArrayList<LineDataSet>(); sets.add(dsA)
        r.cycle?.let { c ->
            if (c.hitCurve.size >= 2) {
                sets.add(LineDataSet(c.hitCurve.mapIndexed { i, v -> Entry(i.toFloat(), v.toFloat()) }, "B cycle法%").apply {
                    color = PURPLE; lineWidth = 1.8f; setDrawCircles(false); setDrawValues(false)
                })
            }
        }
        chart.data = LineData(sets.map { it as com.github.mikephil.charting.interfaces.datasets.ILineDataSet })
        chart.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val i = value.toInt()
                return if (i in r.price.rollDates.indices) r.price.rollDates[i].substring(5) else ""
            }
        }
        chart.axisLeft.removeAllLimitLines()
        chart.axisLeft.addLimitLine(com.github.mikephil.charting.components.LimitLine(50f, "随机50%").apply {
            lineColor = RED; lineWidth = 1.2f; enableDashedLine(10f, 6f, 0f)
            textSize = 9f; textColor = RED
        })
        chart.invalidate()
    }
}
