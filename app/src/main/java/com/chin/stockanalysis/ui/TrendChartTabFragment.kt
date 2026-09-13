package com.chin.stockanalysis.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.mikephil.charting.charts.CombinedChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.listener.OnChartGestureListener
import com.github.mikephil.charting.data.CandleData
import com.github.mikephil.charting.data.CandleDataSet
import com.github.mikephil.charting.data.CombinedData
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.chin.stockanalysis.ai.AiProviderPool
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.analysis.TrendPatternEngine
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import com.chin.stockanalysis.strategy.data.HistoricalDataFetcher
import com.chin.stockanalysis.strategy.data.StockPoolAggregator
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import android.graphics.BitmapFactory

/**
 * ## 股票栏目 — 「K线趋势」页签（v1）
 *
 * 页内两个子页（点击顶部按钮切换 / 左右滑动切换）：
 * - 子页 0「📡 扫描股票池」：一键扫描（自选/AI精选/备选池/龙头池/实仓/持仓 → 去重
 *   → 增量更新K线 → 本地形态识别 → 注入趋势图谱）。扫描结果以列表展示，
 *   每行 = 该股的 K 线形态卡片。
 * - 子页 1「📈 趋势图」：WebView 加载 trend_charts/index.html 形态图谱。
 *
 * 交互约定：
 * - 点击扫描结果「左侧（股票名称区域）」→ 直接在该行下方展开：该股 K 线图 + 匹配的图谱迷你图
 *   （再点一次收起），无需离开扫描结果。
 * - 点击扫描结果「右侧 RSA / 📈形态 标签」→ 自动滑到「趋势图」子页，
 *   并 focusPatternByEn 自动匹配定位到该股对应的图谱 index（高亮 + 滚动到视野中央）。
 * - 输入股票名称或代码 → 自动补数据并注入/定位该股形态。
 * - 「📷 选择截图」→ OCR 识别截图 K 线形态，AI 解析后注入图谱并定位。
 */
class TrendChartTabFragment : Fragment() {

    private lateinit var statusTv: TextView
    private lateinit var scanToggle: TextView
    private lateinit var trendToggle: TextView
    private lateinit var inputEt: EditText
    private lateinit var pager: ViewPager2

    private var webView: WebView? = null
    private var webReady = false
    private var lastFocusEn: String? = null
    private var scanListBox: LinearLayout? = null
    private var scanScroll: ScrollView? = null
    private var scanSerial = 0

    private val trendScanMemory = ConcurrentHashMap<String, Long>()

    /** 大盘4指数叠加折叠卡（子页1“趋势图”顶部），2026-09-06 */
    private var indexCardHeader: TextView? = null
    private var indexCardBody: LinearLayout? = null
    private var indexChartArea: LinearLayout? = null
    private var indexCardLoaded = false

    /** 截图 OCR 选择器 */
    private val ocrPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { processTrendOcr(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        // ── 顶部子页切换：扫描股票池 | 趋势图（紧凑） ──
        val toggleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(8, 2, 8, 0)
        }
        scanToggle = createToggle("📡 扫描股票池", selected = true) { pager.setCurrentItem(0, true) }
        trendToggle = createToggle("📈 趋势图", selected = false) { pager.setCurrentItem(1, true) }
        toggleRow.addView(scanToggle)
        toggleRow.addView(trendToggle)
        root.addView(toggleRow)

        // ── 工具行：▶扫描 | 📷截图 | [名称/代码 搜索框 + 🔍]（整行统一高度 dp(34)，搜索框圆角并垂直居中） ──
        val toolRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 2, 8, 2)
        }
        val toolH = dp(34)
        val scanBtn = Button(ctx).apply {
            text = "▶ 扫描"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#E65100"))
            setPadding(dp(10), 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, toolH
            ).apply { marginEnd = dp(5) }
            setOnClickListener { scanPoolsAndUpdateTrends() }
        }
        toolRow.addView(scanBtn)

        val ocrBtn = Button(ctx).apply {
            text = "📷 截图"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#6200EA"))
            setPadding(dp(8), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, toolH
            ).apply { marginEnd = dp(5) }
            setOnClickListener { ocrPicker.launch("image/*") }
        }
        toolRow.addView(ocrBtn)

        // 搜索框：与扫描/截图按钮同一高度；提示词「名称 / 代码」垂直居中，不再被挤扁/截断
        val inputBg = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(Color.parseColor("#F5F5F5"))
            setStroke(dp(1), Color.parseColor("#DDDDDD"))
        }
        inputEt = EditText(ctx).apply {
            hint = "名称 / 代码"
            textSize = 13f
            isSingleLine = true
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.parseColor("#333333"))
            setHintTextColor(Color.parseColor("#999999"))
            background = inputBg
            setPadding(dp(10), 0, dp(6), 0)
            setMinHeight(toolH)
            layoutParams = LinearLayout.LayoutParams(0, toolH, 1f)
        }
        toolRow.addView(inputEt)

        val searchBtn = Button(ctx).apply {
            text = "🔍"
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#E65100"))
            layoutParams = LinearLayout.LayoutParams(toolH, toolH)
            setOnClickListener { onSearchAction() }
        }
        toolRow.addView(searchBtn)

        val etfBtn = Button(ctx).apply {
            text = "🧲 ETF池"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1976D2"))
            setPadding(dp(8), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, toolH
            )
            setOnClickListener {
                try {
                    EtfHoldingsDialog(requireContext()).show()
                } catch (_: Exception) {
                    Toast.makeText(ctx, "ETF 重仓打开失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
        toolRow.addView(etfBtn)
        root.addView(toolRow)

        // ── 状态栏（单行小字，兼作操作引导） ──
        statusTv = TextView(ctx).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#888888"))
            setPadding(12, 1, 12, 2)
            visibility = View.VISIBLE
            text = "🖱 点左侧名称=行内展开K线/匹配图谱；扫描结果仅本地展示，不再写入趋势图库"
        }
        root.addView(statusTv)

        // ── 内容区：ViewPager2（扫描股票池 / 趋势图），支持左右滑动 ──
        val scanPage = buildScanPage()
        val trendPage = buildTrendPage()
        pager = ViewPager2(ctx).apply {
            adapter = ViewPageAdapter(scanPage, trendPage)
            offscreenPageLimit = 1
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    syncToggle(position == 0, position == 1)
                }
            })
        }
        root.addView(pager, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        return root
    }

    // ═══════════════════════════ 子页构建 ═══════════════════════════

    /** 子页 0：扫描股票池（纯结果列表，扫描按钮已上移到顶部工具行） */
    private fun buildScanPage(): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx).apply { isFillViewport = true }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 2, 8, 12)
        }
        scroll.addView(box)

        val listBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 2, 0, 0)
        }
        box.addView(listBox)
        scanListBox = listBox
        scanScroll = scroll
        return scroll
    }

    /** 子页 1：大盘4指数叠加折叠卡 + 趋势图谱 WebView（2026-09-06） */
    private fun buildTrendPage(): View {
        val ctx = requireContext()
        val page = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        // ── 大盘强弱折叠卡（上证/深证/科创/创业板 归一化K线叠加 + 历史经验分析） ──
        indexCardHeader = TextView(ctx).apply {
            text = "📊 大盘K线：上证/深证/科创/创业板 叠加 ▾"
            textSize = 12f
            setTextColor(Color.parseColor("#E65100"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#FFF8E1"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener {
                val body = indexCardBody ?: return@setOnClickListener
                if (body.visibility == View.VISIBLE) {
                    body.visibility = View.GONE
                    indexCardHeader?.text = "📊 大盘K线：上证/深证/科创/创业板 叠加 ▸"
                } else {
                    body.visibility = View.VISIBLE
                    indexCardHeader?.text = "📊 大盘K线：上证/深证/科创/创业板 叠加 ▾"
                    if (!indexCardLoaded) {
                        indexCardLoaded = true
                        loadIndexOverview()
                    }
                }
            }
        }
        page.addView(indexCardHeader)
        indexCardBody = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(6), dp(2), dp(6), dp(2))
            setBackgroundColor(Color.WHITE)
        }
        indexChartArea = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        indexCardBody?.addView(indexChartArea)
        page.addView(indexCardBody)

        val wv = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    webReady = true
                    lastFocusEn?.let { focusPattern(it) }
                }
            }
        }
        try {
            val html = ctx.assets.open("trend_charts/index.html")
                .bufferedReader().use { it.readText() }
            wv.loadDataWithBaseURL(
                "file:///android_asset/trend_charts/", html, "text/html", "UTF-8", null
            )
        } catch (e: Exception) {
            status("⚠️ 趋势图谱加载失败: ${e.message}")
        }
        webView = wv
        page.addView(wv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        return page
    }

    /** 极简 ViewPager2 适配器（两个预构建 View 页面） */
    private class ViewPageAdapter(
        private val scanPage: View,
        private val trendPage: View
    ) : RecyclerView.Adapter<ViewPageAdapter.PageHolder>() {

        class PageHolder(view: View) : RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val page = if (viewType == 0) scanPage else trendPage
            if (page.layoutParams == null) {
                page.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            return PageHolder(page)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {}

        override fun getItemCount() = 2

        override fun getItemViewType(position: Int) = position
    }

    // ═══════════════════════════ UI 工具 ═══════════════════════════

    private fun createToggle(text: String, selected: Boolean, onClick: () -> Unit): TextView {
        val ctx = requireContext()
        return TextView(ctx).apply {
            this.text = text
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(4))
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
            val bg = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
            }
            background = bg
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            val sel = selected
            updateToggleStyle(this, bg, sel)
        }
    }

    private fun updateToggleStyle(tv: TextView, bg: GradientDrawable, selected: Boolean) {
        if (selected) {
            tv.setTextColor(Color.parseColor("#E65100"))
            bg.setColor(Color.parseColor("#FFF3E0"))
            tv.setTypeface(null, android.graphics.Typeface.BOLD)
        } else {
            tv.setTextColor(Color.parseColor("#666666"))
            bg.setColor(Color.parseColor("#F5F5F5"))
            tv.setTypeface(null, android.graphics.Typeface.NORMAL)
        }
    }

    private fun syncToggle(scanSel: Boolean, trendSel: Boolean) {
        val scanBg = scanToggle.background as GradientDrawable
        val trendBg = trendToggle.background as GradientDrawable
        updateToggleStyle(scanToggle, scanBg, scanSel)
        updateToggleStyle(trendToggle, trendBg, trendSel)
    }

    private fun status(s: String) {
        requireActivity().runOnUiThread {
            if (!isAdded) return@runOnUiThread
            statusTv.apply { text = s; visibility = View.VISIBLE }
        }
    }

    private fun toast(s: String) {
        requireActivity().runOnUiThread {
            if (isAdded) Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 在扫描结果列表中加入一行；左侧点击 → 行内展开K线+匹配图谱。
     *  @param jumpable 该行的图谱是否已注入 WebView 趋势图库（手动搜索/截图注入为 true，
     *                  扫描自动命中为 false —— 扫描结果不再写入趋势图库）。 */
    private fun addScanRow(
        code: String,
        name: String,
        en: String,
        tag: String,
        stateLabel: String? = null,
        stateBull: Boolean = false,
        jumpable: Boolean = true
    ) {
        val ctx = requireContext()
        requireActivity().runOnUiThread {
            if (!isAdded) return@runOnUiThread
            val listBox = scanListBox ?: return@runOnUiThread
            scanSerial++
            val idx = scanSerial
            val displayName = name.ifBlank { code }

            // cell = 卡片行 + 行内展开区（默认隐藏）
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(5) }
            }

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(6), dp(8), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                val bg = GradientDrawable().apply { cornerRadius = dp(10).toFloat() }
                bg.setColor(Color.parseColor("#FAFAFA"))
                bg.setStroke(dp(1), Color.parseColor("#EEEEEE"))
                background = bg
            }

            // ── 左侧可点击区：序号 + 名称 + 展开箭头（weight=1 占满剩余，右侧标签不被遮挡） ──
            val left = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            left.addView(TextView(ctx).apply {
                text = "$idx"
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#E65100"))
                setPadding(dp(6), dp(2), dp(6), dp(2))
            })
            left.addView(TextView(ctx).apply {
                text = "  $displayName($code)  "
                textSize = 13f
                setTextColor(Color.parseColor("#333333"))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            val chev = TextView(ctx).apply {
                text = "▾"
                textSize = 12f
                setTextColor(Color.parseColor("#AAAAAA"))
                setPadding(dp(2), 0, dp(4), 0)
            }
            left.addView(chev)
            val info = ExpandInfo(code, displayName, en, tag, stateLabel, stateBull, jumpable)
            left.setOnClickListener {
                toggleRowExpand(cell, chev, info)
            }
            row.addView(left)

            // RSA 趋势状态标签（仅展示；已注入 WebView 的行保留跳转定位）
            if (!stateLabel.isNullOrBlank()) {
                row.addView(TextView(ctx).apply {
                    text = "RSA·$stateLabel"
                    textSize = 10f
                    setTextColor(if (stateBull) Color.parseColor("#E53935") else Color.parseColor("#43A047"))
                    setPadding(dp(4), dp(2), dp(4), dp(2))
                    if (jumpable) setOnClickListener { jumpToTrendAndFocus(displayName, code, en, tag) }
                })
            }

            // 右侧形态标签（展示形态；手动注入的行可点跳趋势图定位，扫描命中行为纯标注）
            row.addView(TextView(ctx).apply {
                text = "📈 $tag"
                textSize = 11f
                setTextColor(Color.parseColor("#E65100"))
                setPadding(dp(6), dp(2), dp(6), dp(2))
                if (jumpable) setOnClickListener { jumpToTrendAndFocus(displayName, code, en, tag) }
            })
            cell.addView(row)

            // 行内展开区：该股K线图 + 匹配的图谱（懒加载）
            val expand = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(dp(8), dp(6), dp(8), dp(6))
                val bg = GradientDrawable().apply { cornerRadius = dp(8).toFloat() }
                bg.setColor(Color.parseColor("#FFFDF7"))
                bg.setStroke(dp(1), Color.parseColor("#FFE0B2"))
                background = bg
            }
            cell.addView(expand)
            listBox.addView(cell)
        }
    }

    private class ExpandInfo(
        val code: String,
        val name: String,
        val en: String,
        val tag: String,
        val stateLabel: String?,
        val stateBull: Boolean,
        val jumpable: Boolean
    )

    /** 扫描命中记录（用于扫描结束后按上涨概率重排结果行） */
    private data class ScanHit(
        val code: String,
        val name: String,
        val candles: List<DailySnapshotEntity>,
        val weight: Int,
        val bull: Boolean
    )

    /** 扫描结束后按上涨概率重排结果行（若某行正展开则不打扰，直接跳过重排） */
    private fun resortScanRows(hits: List<ScanHit>) {
        if (hits.size < 2) return
        val box = scanListBox ?: return
        requireActivity().runOnUiThread {
            if (!isAdded) return@runOnUiThread
            if (box.childCount != hits.size) return@runOnUiThread
            for (i in 0 until box.childCount) {
                val cell = box.getChildAt(i) as? LinearLayout ?: continue
                if (cell.childCount > 1 && cell.getChildAt(1).visibility == View.VISIBLE) return@runOnUiThread
            }
            val cells = (0 until box.childCount).map { box.getChildAt(it) }
            val sorted = hits.withIndex()
                .sortedWith(
                    compareByDescending<IndexedValue<ScanHit>> { it.value.weight }
                        .thenByDescending { if (it.value.bull) 1 else 0 }
                        .thenBy { it.value.code }
                )
            box.removeAllViews()
            for (si in sorted) box.addView(cells[si.index])
        }
    }

    private fun toggleRowExpand(cell: LinearLayout, chev: TextView, info: ExpandInfo) {
        val expand = cell.getChildAt(1) as? LinearLayout ?: return
        if (expand.visibility == View.VISIBLE) {
            expand.visibility = View.GONE
            chev.rotation = 0f
            return
        }
        // 收起其它已展开的行，保持同时只展开一个
        scanListBox?.let { box ->
            for (i in 0 until box.childCount) {
                val c = box.getChildAt(i) as? LinearLayout ?: continue
                if (c === cell) continue
                (c.getChildAt(1) as? View)?.visibility = View.GONE
                val r = c.getChildAt(0) as? LinearLayout
                val l = r?.getChildAt(0) as? LinearLayout
                (l?.getChildAt(2) as? TextView)?.rotation = 0f
            }
        }
        if (expand.tag == null) {
            expand.tag = true
            loadExpandContent(expand, info)
        }
        chev.rotation = 180f
        expand.visibility = View.VISIBLE
        // 展开后尽量滚动到该行可见
        scanScroll?.post {
            val loc = IntArray(2)
            cell.getLocationInWindow(loc)
            val sloc = IntArray(2)
            scanScroll?.getLocationInWindow(sloc)
            scanScroll?.smoothScrollBy(0, loc[1] - sloc[1] - dp(20))
        }
    }

    /** 行内展开：显示 标题 → 40日K线(MA5/10/20) → 20日匹配图谱(MA5) → 操作按钮 */
    private fun loadExpandContent(expand: LinearLayout, info: ExpandInfo) {
        val ctx = requireContext()
        expand.removeAllViews()
        expand.addView(TextView(ctx).apply {
            text = "⏳ 加载 ${info.name}(${info.code}) K线…"
            textSize = 11f
            setTextColor(Color.parseColor("#999999"))
            setPadding(0, 0, 0, dp(2))
        })
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val snaps = try {
                StockDatabase.getInstance(ctx).dailySnapshotDao()
                    .getByCode(info.code, 60).sortedBy { it.date }
            } catch (e: Exception) { emptyList() }
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (expand.visibility != View.VISIBLE) return@withContext
                expand.removeAllViews()
                if (snaps.size < 20) {
                    expand.addView(TextView(ctx).apply {
                        text = "⚠️ ${info.name}(${info.code}) 本地K线不足 20 根，无法展示"
                        textSize = 11f
                        setTextColor(Color.parseColor("#E65100"))
                    })
                    return@withContext
                }
                expand.addView(TextView(ctx).apply {
                    text = "${info.name}(${info.code}) · 识别形态：${info.tag}"
                    textSize = 11f
                    setTextColor(Color.parseColor("#333333"))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, 0, 0, dp(4))
                })
                // K线图（最近40根）
                expand.addView(TextView(ctx).apply {
                    text = "K线图（最近 ${snaps.takeLast(40).size} 根 · MA5/10/20 · 可拖动/双指缩放）"
                    textSize = 10f
                    setTextColor(Color.parseColor("#666666"))
                    setPadding(0, dp(2), 0, dp(2))
                })
                expand.addView(buildStaticKline(snaps.takeLast(40), 180, listOf(5, 10, 20)))
                // 匹配趋势图：K线 + 未来5日情景预测（2026-09-06 替换原“20根裁剪K线”）
                expand.addView(buildTrendMatchBox(snaps.takeLast(40), info))
                // 操作行
                val act = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(6), 0, 0)
                }
                act.addView(smallBtn("📄 个股详情") {
                    openStockDetail(info.code, info.name)
                })
                act.addView(smallBtn("🧭 趋势图定位") {
                    jumpToTrendAndFocus(info.name, info.code, info.en, info.tag)
                })
                act.addView(TextView(ctx).apply {
                    text = "再次点名称收起 ▲"
                    textSize = 9f
                    setTextColor(Color.parseColor("#AAAAAA"))
                    gravity = Gravity.END
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                expand.addView(act)
            }
        }
    }

    private fun smallBtn(text: String, onClick: () -> Unit): TextView {
        val ctx = requireContext()
        return TextView(ctx).apply {
            this.text = text
            textSize = 10f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1976D2"))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    /** 静态迷你K线图（蜡烛 + MA 线），纯展示不缩放 */
    private fun buildStaticKline(
        snaps: List<DailySnapshotEntity>, heightDp: Int, lines: List<Int>
    ): CombinedChart {
        val ctx = requireContext()
        val chart = CombinedChart(ctx)
        chart.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp)
        )
        chart.setBackgroundColor(Color.WHITE)
        chart.description.isEnabled = false
        chart.legend.isEnabled = lines.isNotEmpty()
        chart.legend.textSize = 8f
        chart.legend.textColor = Color.parseColor("#999999")
        // 可交互：上下左右拖动 + 双指缩放；手势结束后 Y 轴自动贴合当前可见区间
        chart.setScaleEnabled(true)
        chart.setPinchZoom(true)
        chart.isScaleXEnabled = true
        chart.isScaleYEnabled = true
        chart.setDragEnabled(true)
        chart.setDoubleTapToZoomEnabled(true)
        chart.setHighlightPerTapEnabled(false)
        chart.setHighlightPerDragEnabled(false)
        chart.setVisibleXRangeMaximum((snaps.size + 2).toFloat())
        chart.setVisibleXRangeMinimum((snaps.size / 6).coerceAtLeast(6).toFloat())
        chart.moveViewToX(0f)
        chart.drawOrder = arrayOf(CombinedChart.DrawOrder.CANDLE, CombinedChart.DrawOrder.LINE)

        val entries = ArrayList<com.github.mikephil.charting.data.CandleEntry>(snaps.size)
        for (i in snaps.indices) {
            val s = snaps[i]
            entries.add(
                com.github.mikephil.charting.data.CandleEntry(
                    i.toFloat(), s.high.toFloat(), s.low.toFloat(), s.open.toFloat(), s.close.toFloat()
                )
            )
        }
        val cds = CandleDataSet(entries, "").apply {
            color = Color.parseColor("#333333")
            shadowColor = Color.parseColor("#999999")
            shadowWidth = 1f
            increasingPaintStyle = android.graphics.Paint.Style.FILL
            decreasingPaintStyle = android.graphics.Paint.Style.FILL
            increasingColor = Color.parseColor("#E53935")
            decreasingColor = Color.parseColor("#43A047")
            isHighlightEnabled = false
            setDrawValues(false)
        }
        val closes = snaps.map { it.close }
        val lineData = LineData()
        val lineColors = mapOf(
            5 to Color.parseColor("#FF9800"),
            10 to Color.parseColor("#2196F3"),
            20 to Color.parseColor("#9C27B0")
        )
        for (p in lines) {
            val en = maEntries(closes, p)
            if (en.isEmpty()) continue
            lineData.addDataSet(LineDataSet(en, "MA$p").apply {
                color = lineColors[p] ?: Color.parseColor("#FF9800")
                lineWidth = 1f
                setDrawCircles(false)
                setDrawValues(false)
                isHighlightEnabled = false
            })
        }
        val combined = CombinedData()
        combined.setData(CandleData(cds))
        combined.setData(lineData)
        chart.data = combined
        chart.xAxis.apply {
            position = com.github.mikephil.charting.components.XAxis.XAxisPosition.BOTTOM
            granularity = (snaps.size / 4).coerceAtLeast(1).toFloat()
            textSize = 8f
            textColor = Color.parseColor("#999999")
            labelCount = 4
            setDrawGridLines(false)
            setAvoidFirstLastClipping(true)
            valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    val ix = value.toInt()
                    return if (ix in snaps.indices) snaps[ix].date.takeLast(5) else ""
                }
            }
        }
        chart.axisLeft.apply {
            textSize = 8f
            textColor = Color.parseColor("#999999")
            setDrawGridLines(true)
            gridColor = Color.parseColor("#EEEEEE")
            valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
                override fun getFormattedValue(value: Float): String = "%.2f".format(value)
            }
        }
        chart.axisRight.isEnabled = false
        // 手势结束 → Y 轴贴合当前可见区间（放大横向看区间，纵向涨跌始终清晰可辨）
        val fitY = Runnable { fitVisibleY(chart, snaps) }
        chart.setOnChartGestureListener(object : OnChartGestureListener {
            override fun onChartGestureStart(
                e: android.view.MotionEvent?,
                lastPerformedGesture: com.github.mikephil.charting.listener.ChartTouchListener.ChartGesture?
            ) {}
            override fun onChartGestureEnd(
                e: android.view.MotionEvent?,
                lastPerformedGesture: com.github.mikephil.charting.listener.ChartTouchListener.ChartGesture?
            ) { fitY.run() }
            override fun onChartLongPressed(e: android.view.MotionEvent?) {}
            override fun onChartDoubleTapped(e: android.view.MotionEvent?) {}
            override fun onChartSingleTapped(e: android.view.MotionEvent?) {}
            override fun onChartFling(
                e1: android.view.MotionEvent?, e2: android.view.MotionEvent?, vx: Float, vy: Float
            ) {}
            override fun onChartScale(e: android.view.MotionEvent?, scaleX: Float, scaleY: Float) {}
            override fun onChartTranslate(e: android.view.MotionEvent?, dx: Float, dy: Float) {}
        })
        chart.post { fitY.run() }
        chart.invalidate()
        return chart
    }

    /** Y 轴随可见区自适应：按当前窗口内K线最低-最高重新定轴（避免涨跌被压成一条线） */
    private fun fitVisibleY(chart: CombinedChart, snaps: List<DailySnapshotEntity>) {
        if (snaps.size < 4) return
        val low = chart.lowestVisibleX.toInt().coerceIn(0, snaps.size - 1)
        val high = chart.highestVisibleX.toInt().coerceIn(0, snaps.size - 1)
        if (high - low + 1 < 3) return
        var mn = Double.MAX_VALUE
        var mx = -Double.MAX_VALUE
        for (i in low..high) {
            val s = snaps[i]
            if (s.low < mn) mn = s.low
            if (s.high > mx) mx = s.high
        }
        if (mn >= mx) return
        val pad = (mx - mn) * 0.06
        chart.axisLeft.axisMinimum = (mn - pad).toFloat()
        chart.axisLeft.axisMaximum = (mx + pad).toFloat()
        chart.invalidate()
    }

    /** 简单移动平均序列（x 对齐 K 线下标） */
    private fun maEntries(closes: List<Double>, period: Int): List<Entry> {
        val out = ArrayList<Entry>()
        if (closes.size < period) return out
        var sum = 0.0
        for (i in closes.indices) {
            sum += closes[i]
            if (i >= period) sum -= closes[i - period]
            if (i >= period - 1) out.add(Entry(i.toFloat(), (sum / period).toFloat()))
        }
        return out
    }

    /** 行内「匹配趋势图」：真实40根K线 + 形态识别说明 + 未来5日情景虚线（2026-09-06） */
    private fun buildTrendMatchBox(real: List<DailySnapshotEntity>, info: ExpandInfo): LinearLayout {
        val ctx = requireContext()
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        var tag = info.tag
        var state = info.stateLabel
        var bullish = !(info.stateLabel == "红翻绿")
        try {
            val m = com.chin.stockanalysis.strategy.analysis.TrendPatternEngine.match(real)
            if (m != null) {
                tag = m.tag
                state = m.stateLabel ?: state
                if (m.stateBull) bullish = true
            }
        } catch (_: Exception) {}
        box.addView(TextView(ctx).apply {
            text = "匹配趋势：$tag" + (if (!state.isNullOrBlank()) " · RSA·$state" else "") +
                (if (bullish) " · 方向:偏多" else " · 方向:震荡/谨慎")
            textSize = 10f
            setTextColor(Color.parseColor("#E65100"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(2))
        })
        val (up, mid, dn) = forecastScenarioPaths(real.map { it.close }, bullish)
        box.addView(buildForecastChart(real, 170, listOf(5, 10, 20), up, mid, dn))
        box.addView(TextView(ctx).apply {
            text = "虚线=未来5日情景(乐观/中性/谨慎) · 基于形态统计外推，仅供参考，不构成投资建议"
            textSize = 9f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(0, dp(2), 0, 0)
        })
        return box
    }

    /** 未来5日情景路径：以最近真实日波动为带宽，按形态方向温和外推 */
    private fun forecastScenarioPaths(
        closes: List<Double>, bullish: Boolean
    ): Triple<List<Double>, List<Double>, List<Double>> {
        if (closes.isEmpty()) return Triple(emptyList(), emptyList(), emptyList())
        val base = closes.last()
        val w = closes.takeLast(6)
        val avgAmp = (if (w.size >= 2) {
            w.zipWithNext().map { (a, b) -> if (a > 0) kotlin.math.abs(b - a) / a else 0.0 }.average()
        } else 0.01).coerceIn(0.004, 0.045)
        // 偏多形态温和上行漂移；偏空形态小幅阴跌（历史统计：形态成立后5日倾向延续方向）
        val drift = if (bullish) avgAmp * 0.45 else -avgAmp * 0.15
        val up = ArrayList<Double>()
        val mid = ArrayList<Double>()
        val dn = ArrayList<Double>()
        for (i in 1..5) {
            val f = i.toDouble()
            val m = base * (1 + drift * f * 0.5)
            mid.add(m)
            val half = avgAmp * 0.55 * kotlin.math.sqrt(f)
            up.add(m * (1 + half))
            dn.add(m * (1 - half))
        }
        return Triple(up, mid, dn)
    }

    /** K线 + 未来5日情景虚线（真实蜡烛右侧追加 T+1~T+5 虚拟交易日） */
    private fun buildForecastChart(
        snaps: List<DailySnapshotEntity>, heightDp: Int, lines: List<Int>,
        up: List<Double>, mid: List<Double>, dn: List<Double>
    ): CombinedChart {
        val ctx = requireContext()
        val future = 5
        val chart = CombinedChart(ctx)
        chart.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp)
        )
        chart.setBackgroundColor(Color.WHITE)
        chart.description.isEnabled = false
        chart.legend.textSize = 8f
        chart.legend.textColor = Color.parseColor("#999999")
        chart.setScaleEnabled(true)
        chart.setPinchZoom(true)
        chart.isScaleXEnabled = true
        chart.isScaleYEnabled = true
        chart.setDragEnabled(true)
        chart.setDoubleTapToZoomEnabled(true)
        chart.setHighlightPerTapEnabled(false)
        chart.setHighlightPerDragEnabled(false)
        chart.setVisibleXRangeMaximum((snaps.size + future + 1).toFloat())
        chart.setVisibleXRangeMinimum((snaps.size / 6).coerceAtLeast(6).toFloat())
        chart.moveViewToX(0f)
        chart.drawOrder = arrayOf(CombinedChart.DrawOrder.CANDLE, CombinedChart.DrawOrder.LINE)

        val entries = ArrayList<com.github.mikephil.charting.data.CandleEntry>(snaps.size)
        for (i in snaps.indices) {
            val s = snaps[i]
            entries.add(
                com.github.mikephil.charting.data.CandleEntry(
                    i.toFloat(), s.high.toFloat(), s.low.toFloat(), s.open.toFloat(), s.close.toFloat()
                )
            )
        }
        val cds = CandleDataSet(entries, "").apply {
            color = Color.parseColor("#333333")
            shadowColor = Color.parseColor("#999999")
            shadowWidth = 1f
            increasingPaintStyle = android.graphics.Paint.Style.FILL
            decreasingPaintStyle = android.graphics.Paint.Style.FILL
            increasingColor = Color.parseColor("#E53935")
            decreasingColor = Color.parseColor("#43A047")
            isHighlightEnabled = false
            setDrawValues(false)
        }
        val closes = snaps.map { it.close }
        val lineData = LineData()
        val lineColors = mapOf(
            5 to Color.parseColor("#FF9800"),
            10 to Color.parseColor("#2196F3"),
            20 to Color.parseColor("#9C27B0")
        )
        for (p in lines) {
            val en = maEntries(closes, p)
            if (en.isEmpty()) continue
            lineData.addDataSet(LineDataSet(en, "MA$p").apply {
                color = lineColors[p] ?: Color.parseColor("#FF9800")
                lineWidth = 1f
                setDrawCircles(false)
                setDrawValues(false)
                isHighlightEnabled = false
            })
        }
        // 未来情景线：起点锚定最后一根真实收盘
        val lastIx = (snaps.size - 1).toFloat()
        val lastClose = snaps.last().close.toFloat()
        fun path(values: List<Double>, color: Int, label: String, dashed: Boolean): LineDataSet {
            val es = ArrayList<Entry>(values.size + 1)
            es.add(Entry(lastIx, lastClose))
            values.forEachIndexed { k, v -> es.add(Entry(lastIx + 1 + k, v.toFloat())) }
            return LineDataSet(es, label).apply {
                this.color = color
                lineWidth = if (dashed) 1f else 1.6f
                setDrawCircles(false)
                setDrawValues(false)
                isHighlightEnabled = false
                if (dashed) enableDashedLine(6f, 4f, 0f)
            }
        }
        if (up.isNotEmpty() && mid.isNotEmpty() && dn.isNotEmpty()) {
            lineData.addDataSet(path(up, Color.parseColor("#FB8C00"), "乐观", true))
            lineData.addDataSet(path(mid, Color.parseColor("#1976D2"), "中性", false))
            lineData.addDataSet(path(dn, Color.parseColor("#90A4AE"), "谨慎", true))
        }
        val combined = CombinedData()
        combined.setData(CandleData(cds))
        combined.setData(lineData)
        chart.data = combined
        chart.xAxis.apply {
            position = com.github.mikephil.charting.components.XAxis.XAxisPosition.BOTTOM
            granularity = (snaps.size / 4).coerceAtLeast(1).toFloat()
            textSize = 8f
            textColor = Color.parseColor("#999999")
            labelCount = 4
            setDrawGridLines(false)
            setAvoidFirstLastClipping(true)
            valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    val ix = value.toInt()
                    if (ix in snaps.indices) return snaps[ix].date.takeLast(5)
                    val d = ix - (snaps.size - 1)
                    return if (d in 1..future) "T+$d" else ""
                }
            }
        }
        chart.axisLeft.apply {
            textSize = 8f
            textColor = Color.parseColor("#999999")
            setDrawGridLines(true)
            gridColor = Color.parseColor("#EEEEEE")
            valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
                override fun getFormattedValue(value: Float): String = "%.2f".format(value)
            }
        }
        chart.axisRight.isEnabled = false
        chart.invalidate()
        return chart
    }

    /** 点击结果行 → 打开大盘股票详情页（展示最新K线 + 下方匹配趋势区） */
    private fun openStockDetail(code: String, name: String) {
        status("📄 打开 $name($code) 详情页…")
        StockDetailNavigator.navigateFromFragment(this, code, name)
    }

    /** 点击形态/状态标签 → 跳到「趋势图」子页并自动定位该股图谱 */
    private fun jumpToTrendAndFocus(name: String, code: String, en: String, tag: String) {
        pager.setCurrentItem(1, true)
        lastFocusEn = en
        if (webReady) {
            focusPattern(en)
            status("🔍 $name($code) → $tag，正在定位图谱…")
        } else {
            status("⏳ 图谱加载中，$name($code)($tag) 定位将在完成后自动执行…")
        }
    }

    // ═══════════════════════════ 图谱注入 / 定位 ═══════════════════════════

    /** 在 WebView 中定位某个形态（按 en 精确匹配） */
    private fun focusPattern(en: String) {
        val wv = webView ?: return
        if (!webReady) return
        wv.evaluateJavascript("focusPatternByEn('$en')") { result ->
            requireActivity().runOnUiThread {
                if (isAdded) {
                    val r = (result ?: "").trim().trim('"')
                    if (r.startsWith("NOT_FOUND") || r.startsWith("INDEX_NOT_FOUND") || r.startsWith("ERR")) {
                        status("⚠️ 未找到该股对应图谱（$r），请先截图导入或在图谱库中查找")
                    } else {
                        status("✅ 已匹配图谱 #$r：$en（点击扫描结果卡片即自动定位此处）")
                    }
                }
            }
        }
    }

    /** 将形态 JSON 注入 WebView（en 为唯一标识） */
    private fun injectPatternToWebView(patternJson: JSONObject) {
        val wv = webView ?: return
        val jsonStr = patternJson.toString().replace("'", "\\'")
        wv.post {
            wv.evaluateJavascript(
                "addPatternFromApp('$jsonStr')",
                android.webkit.ValueCallback<String> { result ->
                    android.util.Log.i("TrendChart", "注入结果: $result")
                }
            )
        }
    }

    /**
     * 供 OCR / 输入跳转 / 扫描共用：
     * 为单只股票补数据 → 本地形态识别 → 注入 → 返回 en 用于定位
     */
    private suspend fun injectSingleStock(
        code: String, nameHint: String? = null
    ): Pair<String, String>? {
        val ctx = requireContext()
        val db = StockDatabase.getInstance(ctx)
        val name = nameHint
            ?: try { db.stockBasicDao().getByCode(code)?.name } catch (e: Exception) { null }
            ?: code
        val fetcher = HistoricalDataFetcher(ctx)
        val have = try {
            db.dailySnapshotDao().getMaxDateByCode().associate { it.code to it.maxDate }[code]
        } catch (e: Exception) { null }
        val recentDay = com.chin.stockanalysis.ui.TradingDayPickerView
            .recentTradingDay().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        if (have == null || have < recentDay) {
            if (!fetcher.fetchStockLatest(code)) return null
        }
        val candles = try { db.dailySnapshotDao().getByCode(code, 40) } catch (e: Exception) { emptyList() }
        if (candles.size < 20) return null
        val pat = detectTrendPattern(candles) ?: return null
        val en = buildAndInject(code, name, candles, pat)
        return en to pat.second
    }

    private fun buildAndInject(
        code: String, name: String, candles: List<DailySnapshotEntity>, pat: Pair<String, String>
    ): String {
        val k = candles.take(40).reversed()
        val data = JSONArray()
        k.forEach { c ->
            data.put(JSONObject().apply {
                put("o", c.open); put("h", c.high); put("l", c.low); put("c", c.close)
                put("label", c.date)
            })
        }
        val json = JSONObject().apply {
            put("cat", pat.first)
            put("type", "bullish")
            put("name", "$name($code) ${pat.second}")
            put("en", "${pat.first}_$code")
            put("data", data)
            put("desc", "自动扫描识别：${pat.second}（K线趋势）")
            put("rules", "${pat.second}形态，来源：K线趋势页")
        }
        injectPatternToWebView(json)
        return json.optString("en")
    }

    // ═══════════════════════════ 扫描主流程 ═══════════════════════════

    private fun scanPoolsAndUpdateTrends() {
        val ctx = requireContext()
        status("📡 正在整合 7 类股票池…")
        lifecycleScope.launch(Dispatchers.IO) {
            val db = StockDatabase.getInstance(ctx)
            // ① 统一池整合：自选/AI精选/备选池/龙头池/实仓/持仓 + 板块精选池(每板块5大+5小) → 去重
            val pool = try {
                StockPoolAggregator.collect(ctx)
            } catch (e: Exception) {
                status("⚠️ 股票池整合失败: ${e.message}")
                return@launch
            }
            val codes = pool.codes
            if (codes.isEmpty()) {
                status("⚠️ 各股票池均为空，无法扫描")
                return@launch
            }
            val countText = pool.countBySource.entries.joinToString("+") { "${it.key}${it.value}" }
            status("📡 池内共 ${codes.size} 只（$countText）…")

            // ② 名称解析：stock_basics 优先；缺失名称的用「最新一根日K」的名称回填
            //    （解决扫描后部分股票只有代码、没有名称：该类股在 stock_basics 无行但日K有数据）
            val nameMap = HashMap<String, String>()
            try {
                db.stockBasicDao().getByCodes(codes).forEach { if (it.name.isNotBlank()) nameMap[it.code] = it.name }
            } catch (e: Exception) {}
            try {
                val missing = codes.filter { nameMap[it].isNullOrBlank() }
                if (missing.isNotEmpty()) {
                    db.dailySnapshotDao().getLastNames(missing)
                        .forEach { if (nameMap[it.code].isNullOrBlank() && it.name.isNotBlank()) nameMap[it.code] = it.name }
                }
            } catch (e: Exception) {}

            // ②b 剔除 ST/*ST/退市 风险股（板块精选池等引入成分股时无 ST 过滤，这里统一拦截）
            val stCount = codes.count { isRiskName(nameMap[it] ?: "") }
            val targets = codes.filterNot { isRiskName(nameMap[it] ?: "") }

            // ②c/③ 逐只处理：先增量补K线 → 命中形态立即注入并追加结果行（一边扫描一边显示）
            val fetcher = HistoricalDataFetcher(ctx)
            // 名称首次导入（2026-09-06）：若 basics 与日K都无名称（如恢复出厂/新装），
            // 自动联网批量补齐一次，避免扫描池里大量只有代码没有名称
            try {
                val stillMissing = targets.filter { nameMap[it].isNullOrBlank() }
                if (stillMissing.isNotEmpty()) {
                    val fixedN = fetcher.fillNamesForCodes(stillMissing)
                    if (fixedN > 0) {
                        db.stockBasicDao().getByCodes(stillMissing).forEach {
                            if (nameMap[it.code].isNullOrBlank() && it.name.isNotBlank()) nameMap[it.code] = it.name
                        }
                        android.util.Log.i("TrendChart", "📛 首次导入补齐 $fixedN 只股票名称")
                    }
                }
            } catch (e: Exception) {}
            val recentDay = com.chin.stockanalysis.ui.TradingDayPickerView
                .recentTradingDay().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            var updated = 0; var skipped = 0; var failed = 0; var injected = 0
            val hits = ArrayList<ScanHit>()
            // 新一轮扫描：先清空上次结果列表，随后逐行实时追加
            val box0 = scanListBox
            if (box0 != null) requireActivity().runOnUiThread { if (isAdded) box0.removeAllViews() }
            for ((i, code) in targets.withIndex()) {
                val nm = nameMap[code] ?: code
                status("📡 扫描 ${i + 1}/${targets.size}：$nm ($code)")
                if (trendScanMemory.containsKey(code)) {
                    skipped++
                } else {
                    val have = try {
                        db.dailySnapshotDao().getMaxDateByCode().associate { it.code to it.maxDate }[code]
                    } catch (e: Exception) { null }
                    if (have != null && have >= recentDay) {
                        trendScanMemory[code] = System.currentTimeMillis(); skipped++
                    } else if (fetcher.fetchStockLatest(code)) {
                        updated++; trendScanMemory[code] = System.currentTimeMillis()
                    } else failed++
                }
                // 形态识别（数据齐全的股票命中后立即出结果行，无需等全量扫完）
                // 2026-09-05：命中只进本页列表（行内展开本地K线），不再注入趋势图 WebView 库
                // —— 趋势图库只保留模板/手动搜索注入的图谱，避免扫描结果刷屏污染模板区
                val candles = try { db.dailySnapshotDao().getByCode(code, 40) } catch (e: Exception) { emptyList() }
                if (candles.size < 20) continue
                val match = TrendPatternEngine.match(candles.take(40).reversed()) ?: continue
                hits.add(ScanHit(code, nm, candles, match.weight, match.stateBull))
                addScanRow(
                    code = code,
                    name = nm,
                    en = "",
                    tag = match.tag,
                    stateLabel = match.stateLabel,
                    stateBull = match.stateBull,
                    jumpable = false
                )
                injected++
            }
            // 全部扫完 → 按上涨概率重排结果行（形态权重 + RSA 偏多优先；展开中的行不打扰）
            resortScanRows(hits)
            val indexLine = indexTrendText(db)
            val stNote = if (stCount > 0) " 剔除ST/退市$stCount 只" else ""
            val marketNote = if (indexLine.isNotBlank())
                "\n$indexLine（形态识别仅针对个股，反映个股相对强弱）" else ""
            status("✅ 扫描完成：共 ${codes.size} 只（$countText）→ 更新 $updated/复用 $skipped/失败 $failed$stNote，识别看多形态 $injected 个（仅本地列表，未写入趋势图库）$marketNote")
        }
    }

    /** 输入框定位：支持 代码(002384/sz002384) 或 股票名称 模糊匹配 */
    private fun onSearchAction() {
        val q = inputEt.text.toString().trim()
        if (q.isEmpty()) { toast("请输入股票名称或代码"); return }
        lifecycleScope.launch(Dispatchers.IO) {
            val ctx = requireContext()
            val db = StockDatabase.getInstance(ctx)
            var code: String? = null
            var name: String? = null
            try {
                // 数字/带前缀代码
                val digits = q.replace(Regex("[^0-9]"), "")
                if (digits.length >= 6) {
                    val cands = listOf("sz$digits", "sh$digits")
                    for (c in cands) {
                        db.stockBasicDao().getByCode(c)?.let { code = c; name = it.name; return@let }
                        if (code != null) break
                    }
                }
                if (code == null) {
                    val hits = db.stockBasicDao().searchByName(q).take(3)
                    if (hits.isNotEmpty()) {
                        code = hits.first().code
                        name = hits.first().name
                    }
                }
            } catch (e: Exception) { }
            if (code == null) {
                status("⚠️ 未找到「$q」，请尝试输入完整名称或 6 位代码")
                return@launch
            }
            status("🔍 ${name}($code)：正在补数据并生成形态…")
            val enPat = injectSingleStock(code!!, name)
            if (enPat == null) {
                status("⚠️ ${name}($code) 数据不足或未识别出形态，请先截图导入")
                return@launch
            }
            withContext(Dispatchers.Main) {
                if (isAdded) {
                    pager.setCurrentItem(1, true)
                    lastFocusEn = enPat.first
                    if (webReady) focusPattern(enPat.first)
                    status("✅ ${name}($code)：${enPat.second} 已注入，正在定位图谱…")
                }
            }
        }
    }

    /**
     * 外部跳转入口（个股详情页 / 其他页联动）：把指定股票 补数据→识别→注入趋势图谱 并定位。
     * @param code 股票代码（如 sh600519 / 600519）
     * @param nameHint 股票名称提示（可空，空则从库中查询）
     */
    fun focusStockOnTrend(code: String, nameHint: String?) {
        if (!isAdded) return
        val label = nameHint?.takeIf { it.isNotBlank() } ?: code
        status("📈 $label($code)：正在生成形态并定位…")
        lifecycleScope.launch(Dispatchers.IO) {
            val pat = try {
                withTimeoutOrNull(90_000L) { injectSingleStock(code, label) }
            } catch (e: Exception) { null }
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (pat == null) {
                    status("⚠️ $label($code)：数据不足或暂未识别出看多形态")
                    return@withContext
                }
                pager.setCurrentItem(1, true)
                lastFocusEn = pat.first
                if (webReady) focusPattern(pat.first)
                status("✅ $label($code)：${pat.second} 已注入，正在定位图谱…")
            }
        }
    }

    // ═══════════════════════════ OCR 识别 ═══════════════════════════

    private fun processTrendOcr(uri: android.net.Uri) {
        val ctx = requireContext()
        status("🔄 正在识别截图…")
        try {
            val inputStream = ctx.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            if (bitmap == null) { status("❌ 无法读取图片"); return }
            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    if (!isAdded) return@addOnSuccessListener
                    val rawText = visionText.text
                    status("🤖 AI 正在分析 K 线形态…")
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        val patternJson = parseTrendWithAi(rawText)
                        withContext(Dispatchers.Main) {
                            if (!isAdded) return@withContext
                            if (patternJson != null) {
                                val en = patternJson.optString("en")
                                injectPatternToWebView(patternJson)
                                pager.setCurrentItem(1, true)
                                if (en.isNotEmpty()) {
                                    lastFocusEn = en
                                    if (webReady) focusPattern(en)
                                }
                                status("✅ 已添加形态：${patternJson.optString("name")}（$en）")
                            } else {
                                status("⚠️ 未能识别K线形态，请确保截图包含清晰的K线图表")
                            }
                        }
                    }
                }
                .addOnFailureListener { e ->
                    if (!isAdded) return@addOnFailureListener
                    status("❌ OCR 识别失败: ${e.message}")
                }
        } catch (e: Exception) {
            status("❌ 图片处理失败: ${e.message}")
        }
    }

    /** 使用 AI 从 OCR 文字中提取 K 线形态数据（JSONObject） */
    private suspend fun parseTrendWithAi(ocrText: String): JSONObject? {
        val slot = AiProviderPool.acquire(
            requireContext(),
            callerTag = "TrendOcr",
            timeoutMs = 60_000L
        ) ?: return null
        try {
            val prompt = buildString {
                appendLine("你是一个专业的K线形态分析助手。")
                appendLine("以下是从K线截图中OCR识别出的文字，可能包含K线走势、价格数据、技术指标等信息。")
                appendLine()
                appendLine("OCR文字内容：")
                appendLine("---")
                appendLine(ocrText)
                appendLine("---")
                appendLine()
                appendLine("请分析这些内容，识别出K线形态，并返回JSON格式数据：")
                appendLine("{")
                appendLine("  \"cat\": \"single|two|three|five|trend|complex\",")
                appendLine("  \"type\": \"bull|bear|neutral\",")
                appendLine("  \"name\": \"形态中文名称\",")
                appendLine("  \"en\": \"English Pattern Name\",")
                appendLine("  \"data\": [{\"o\":开盘价,\"h\":最高价,\"l\":最低价,\"c\":收盘价,\"label\":\"D1\"}, ...],")
                appendLine("  \"desc\": \"形态描述（含<strong>标签</strong>）\",")
                appendLine("  \"rules\": \"识别要点\"")
                appendLine("}")
                appendLine()
                appendLine("cat分类规则：")
                appendLine("- single: 单根K线（大阳线、锤子线、十字星等）")
                appendLine("- two: 双K线组合（吞没、孕线等）")
                appendLine("- three: 三K线组合（早晨之星、红三兵等）")
                appendLine("- five: 多K线组合（4-5根K线形态）")
                appendLine("- trend: 趋势形态（上升/下跌趋势、通道等）")
                appendLine("- complex: 复杂形态（10-30日的大型形态）")
                appendLine()
                appendLine("data中的价格应为合理的相对价格，反映截图中的走势。")
                appendLine("如果无法确定准确价格，请根据走势描述估算合理的OHLC数据。")
                appendLine("只返回JSON，不要其他文字。")
            }
            val response = withTimeoutOrNull(90_000L) {
                kotlinx.coroutines.suspendCancellableCoroutine<String> { cont ->
                    slot.provider.sendMessageStream(
                        messages = emptyList(),
                        systemPrompt = prompt,
                        onSuccess = {},
                        onComplete = { full -> cont.resumeWith(Result.success(full)) },
                        onError = { err -> cont.resumeWith(Result.failure(Exception(err))) }
                    )
                }
            }
            if (response.isNullOrBlank()) return null
            val jsonMatch = Regex("\\{[\\s\\S]*\\}").find(response)
            if (jsonMatch == null) return null
            return try {
                JSONObject(jsonMatch.value)
            } catch (e: Exception) { null }
        } catch (e: Exception) {
            android.util.Log.e("TrendChart", "AI 解析失败: ${e.message}", e)
            return null
        } finally {
            AiProviderPool.releaseNonBlocking(slot)
        }
    }

    // ═══════════════════════════ 本地形态识别 ═══════════════════════════

    /** 本地 K 线形态识别（与趋势图分类对齐；基于共享引擎，含 RSA 状态加权拦截/放行） */
    private fun detectTrendPattern(candles: List<DailySnapshotEntity>): Pair<String, String>? =
        TrendPatternEngine.match(candles.take(40).reversed())?.let { it.cat to it.tag }

    /** ST与退市风险股判断（名称含 ST 或 退，扫描与池过滤统一口径） */
    private fun isRiskName(name: String): Boolean =
        name.contains("ST", ignoreCase = true) || name.contains("退")

    /** 大盘指数方向：沪/深/创 最新一根日K相对上一交易日的涨跌，给扫描结果提供大盘上下文 */
    private suspend fun indexTrendText(db: StockDatabase): String {
        val parts = mutableListOf<String>()
        val indices = listOf(
            Triple("sh000001", "上证", true),
            Triple("sz399001", "深成", true),
            Triple("sz399006", "创业板", true)
        )
        for ((code, label, _) in indices) {
            try {
                // getByCode 返回日期倒序（最新在前）
                val snaps = db.dailySnapshotDao().getByCode(code, 2)
                if (snaps.size < 2) continue
                val prev = snaps[1].close
                val last = snaps[0].close
                if (prev <= 0 || last <= 0) continue
                val pct = (last / prev - 1) * 100
                parts.add("$label${if (pct >= 0) "▲" else "▼"}${if (pct >= 0) "+" else ""}${"%.2f".format(pct)}%")
            } catch (e: Exception) {}
        }
        return if (parts.isEmpty()) "" else "📊 大盘 " + parts.joinToString("  ")
    }

    // ═══════════════════════════ 大盘4指数叠加 ═══════════════════════════

    /** 加载上证/深证/科创50/创业板指K线：本地优先，不足30根自动联网补充 */
    private fun loadIndexOverview() {
        val ctx = requireContext()
        indexChartArea?.removeAllViews()
        indexChartArea?.addView(TextView(ctx).apply {
            text = "⏳ 加载 上证/深证/科创50/创业板 K线（本地优先，不足自动联网补充）…"
            textSize = 11f
            setTextColor(Color.parseColor("#999999"))
            setPadding(0, dp(4), 0, dp(4))
        })
        lifecycleScope.launch(Dispatchers.IO) {
            val db = StockDatabase.getInstance(ctx)
            val fetcher = HistoricalDataFetcher(ctx)
            val defs = listOf(
                "sh000001" to "上证指数",
                "sz399001" to "深证成指",
                "sh000688" to "科创50",
                "sz399006" to "创业板指"
            )
            val series = mutableListOf<Pair<String, List<DailySnapshotEntity>>>()
            for ((code, label) in defs) {
                try {
                    // getByCode 返回日期倒序 → 翻转成升序绘制
                    var snaps = db.dailySnapshotDao().getByCode(code, 80).sortedBy { it.date }
                    if (snaps.size < 30) {
                        val end = java.time.LocalDate.now()
                        val start = end.minusDays(420)
                        val (fetched, _) = fetcher.fetchOneStock(code, start, end)
                        if (fetched.size > snaps.size) {
                            db.dailySnapshotDao().insertAll(fetched)
                            snaps = db.dailySnapshotDao().getByCode(code, 80).sortedBy { it.date }
                        }
                    }
                    series.add(label to snaps)
                } catch (e: Exception) {
                    android.util.Log.w("TrendChart", "指数K线获取失败 $code: ${e.message}")
                }
            }
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                renderIndexOverview(series)
            }
        }
    }

    /** 绘制归一化4指数叠加折线 + 强弱分析文案 */
    private fun renderIndexOverview(series: List<Pair<String, List<DailySnapshotEntity>>>) {
        val ctx = requireContext()
        indexChartArea?.removeAllViews()
        val usable = series.filter { it.second.isNotEmpty() }
        if (usable.isEmpty()) {
            indexChartArea?.addView(TextView(ctx).apply {
                text = "⚠️ 本地无指数K线，且联网补充失败。\n请在 PC 端运行选股推送（小工具/云同步）后再刷新，或稍后重试。"
                textSize = 11f
                setTextColor(Color.parseColor("#E65100"))
                setPadding(0, dp(4), 0, dp(4))
            })
            return
        }
        // 对齐到公共交易日长度（各指数交易日基本一致，取最短）
        val len = usable.minOf { it.second.size }
        if (len < 8) {
            indexChartArea?.addView(TextView(ctx).apply {
                text = "⚠️ 指数K线不足 8 根（$len），暂无法绘制叠加走势，请先同步数据。"
                textSize = 11f
                setTextColor(Color.parseColor("#E65100"))
                setPadding(0, dp(4), 0, dp(4))
            })
            return
        }
        val colors = mapOf(
            "上证指数" to Color.parseColor("#E53935"),
            "深证成指" to Color.parseColor("#FB8C00"),
            "科创50" to Color.parseColor("#7E57C2"),
            "创业板指" to Color.parseColor("#43A047")
        )
        val chart = LineChart(ctx)
        chart.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(170)
        )
        chart.setBackgroundColor(Color.WHITE)
        chart.description.isEnabled = false
        chart.legend.textSize = 8f
        chart.legend.textColor = Color.parseColor("#999999")
        chart.setScaleEnabled(true)
        chart.setPinchZoom(true)
        chart.setDragEnabled(true)
        chart.setHighlightPerTapEnabled(false)
        chart.setVisibleXRangeMaximum(len.toFloat())
        chart.moveViewToX(0f)
        val ld = LineData()
        for ((label, snapsAll) in usable) {
            val arr = snapsAll.takeLast(len)
            val base = arr.first().close
            if (base <= 0) continue
            val entries = arr.mapIndexed { i, s ->
                Entry(i.toFloat(), (s.close / base * 100).toFloat())
            }
            ld.addDataSet(LineDataSet(entries, label).apply {
                color = colors[label] ?: Color.parseColor("#1976D2")
                lineWidth = 1.4f
                setDrawCircles(false)
                setDrawValues(false)
                isHighlightEnabled = false
            })
        }
        if (ld.dataSetCount == 0) {
            indexChartArea?.addView(TextView(ctx).apply {
                text = "⚠️ 指数价格数据异常，无法绘制。"
                textSize = 11f
                setTextColor(Color.parseColor("#E65100"))
                setPadding(0, dp(4), 0, dp(4))
            })
            return
        }
        chart.data = ld
        val dates = usable.first().second.takeLast(len).map { it.date }
        chart.xAxis.apply {
            position = com.github.mikephil.charting.components.XAxis.XAxisPosition.BOTTOM
            granularity = (len / 4).coerceAtLeast(1).toFloat()
            textSize = 8f
            textColor = Color.parseColor("#999999")
            labelCount = 4
            setDrawGridLines(false)
            setAvoidFirstLastClipping(true)
            valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    val ix = value.toInt()
                    return if (ix in dates.indices) dates[ix].takeLast(5) else ""
                }
            }
        }
        chart.axisLeft.apply {
            textSize = 8f
            textColor = Color.parseColor("#999999")
            setDrawGridLines(true)
            gridColor = Color.parseColor("#EEEEEE")
            valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
                override fun getFormattedValue(value: Float): String = "%.0f".format(value)
            }
        }
        chart.axisRight.isEnabled = false
        chart.invalidate()
        indexChartArea?.addView(chart)
        indexChartArea?.addView(TextView(ctx).apply {
            text = indexAnalysisText(usable)
            textSize = 11f
            setTextColor(Color.parseColor("#333333"))
            setPadding(0, dp(6), 0, 0)
        })
    }

    /**
     * 大盘强弱分析（基于近5/10日累计涨跌 + MA5/MA20 排列）。
     * 强弱优先级（结合历史经验）：
     *   上证↑+科创↑ 最强共振 → 上证↑+科创震荡 权重搭台 → 科创↑+上证↓/震荡 题材结构性 → 双弱防御
     * 深成/创业板作为辅助印证。
     */
    private fun indexAnalysisText(series: List<Pair<String, List<DailySnapshotEntity>>>): String {
        fun pctN(snaps: List<DailySnapshotEntity>, n: Int): Double? {
            if (snaps.size <= n) return null
            val a = snaps[snaps.size - 1 - n].close
            val b = snaps.last().close
            if (a <= 0) return null
            return (b / a - 1) * 100
        }
        fun maP(snaps: List<DailySnapshotEntity>, p: Int): Double? {
            if (snaps.size < p) return null
            return snaps.takeLast(p).map { it.close }.average()
        }
        fun stateOf(snaps: List<DailySnapshotEntity>): String {
            val p5 = pctN(snaps, 5) ?: return "数据不足"
            val m5 = maP(snaps, 5)
            val m20 = maP(snaps, 20)
            val maUp = m5 != null && m20 != null && m5 > m20
            return when {
                p5 > 0.8 && maUp -> "强势"
                p5 < -0.8 && !maUp -> "弱势"
                p5 > 1.5 -> "强势"
                p5 < -1.5 -> "弱势"
                else -> "震荡"
            }
        }
        fun line(label: String, snaps: List<DailySnapshotEntity>): String {
            val p5 = pctN(snaps, 5)
            val p10 = pctN(snaps, 10)
            val s5 = if (p5 != null && p5 >= 0) "+" else ""
            val s10 = if (p10 != null && p10 >= 0) "+" else ""
            return "$label ${stateOf(snaps)} · 5日${s5}${"%.2f".format(p5 ?: 0.0)}% · 10日${s10}${"%.2f".format(p10 ?: 0.0)}%"
        }
        fun byLabel(label: String): List<DailySnapshotEntity>? = series.firstOrNull { it.first == label }?.second

        val sb = StringBuilder()
        for ((label, snaps) in series) {
            sb.appendLine(line(label, snaps))
        }
        // 主判据：上证 × 科创50
        val sh = byLabel("上证指数")
        val kc = byLabel("科创50")
        val sz = byLabel("深证成指")
        val cy = byLabel("创业板指")
        val shUp = sh?.let { pctN(it, 5) ?: 0.0 } ?: 0.0
        val kcUp = kc?.let { pctN(it, 5) ?: 0.0 } ?: 0.0
        val szUp = sz?.let { pctN(it, 5) ?: 0.0 } ?: 0.0
        val cyUp = cy?.let { pctN(it, 5) ?: 0.0 } ?: 0.0
        val shS = stateOf(sh ?: emptyList())
        val kcS = stateOf(kc ?: emptyList())
        val verdict: String = when {
            shUp > 0.8 && kcUp > 0.8 ->
                "共振强势（上证+科创同涨）：历史经验最利于做多，量能配合时普涨概率大。可提高仓位至 6-8 成，围绕强势板块低吸龙头，避免盘中追高。"
            shUp > 0.8 && kcS == "震荡" ->
                "权重搭台、科创休整：指数稳但缺赚钱效应，适合精选低吸而非追涨，仓位 5-6 成。若深成/创业板同步走强可视为共振确认。"
            kcUp > 0.8 && shUp <= 0.8 ->
                "题材结构行情（科创强、上证弱/震荡）：科技成长活跃但指数不稳，以快进快出为主，仓位 3-5 成，严守止损。"
            shUp <= 0.8 && kcUp <= 0.8 && shS != "弱势" ->
                "弱势整理/存量博弈：控制仓位（3 成内），只做确定性高的强势股，等待上证重新站上 MA20。"
            else ->
                "防御状态（指数偏弱）：历史经验宜降低仓位（0-2 成）或空仓等待企稳信号；反弹需站稳 MA5 后再参与。"
        }
        sb.append("结论：").append(verdict)
        val _sz = szUp
        val _cy = cyUp
        sb.append("（深成5日${if (_sz >= 0) "+" else ""}${"%.2f".format(_sz)}%，创业板5日${if (_cy >= 0) "+" else ""}${"%.2f".format(_cy)}% 佐证）")
        return sb.toString()
    }

    override fun onDestroyView() {
        webView?.removeAllViews()
        webView?.destroy()
        webView = null
        super.onDestroyView()
    }
}
