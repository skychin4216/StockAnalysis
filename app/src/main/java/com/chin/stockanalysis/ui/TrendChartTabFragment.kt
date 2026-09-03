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
 * - 点击扫描结果中的某行「个股 K 线」→ 自动向右滑到「趋势图」子页，
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
    private var scanSerial = 0

    private val trendScanMemory = ConcurrentHashMap<String, Long>()

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
        root.addView(toolRow)

        // ── 状态栏（单行小字，兼作操作引导） ──
        statusTv = TextView(ctx).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#888888"))
            setPadding(12, 1, 12, 2)
            visibility = View.VISIBLE
            text = "▶ 扫描股票池；点结果行=个股详情；点右侧形态=趋势图谱"
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
        return scroll
    }

    /** 子页 1：趋势图谱 WebView */
    private fun buildTrendPage(): View {
        val ctx = requireContext()
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
        return wv
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

    /** 在扫描结果列表中加入一行（每个已注入形态对应一行） */
    private fun addScanRow(
        code: String,
        name: String,
        en: String,
        tag: String,
        stateLabel: String? = null,
        stateBull: Boolean = false
    ) {
        val ctx = requireContext()
        requireActivity().runOnUiThread {
            if (!isAdded) return@runOnUiThread
            val listBox = scanListBox ?: return@runOnUiThread
            scanSerial++
            val idx = scanSerial
            val displayName = name.ifBlank { code }

            // 整行：点击 → 大盘股票详情页（展示最新K线）
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(6), dp(8), dp(6))
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(5) }
                setOnClickListener { openStockDetail(code, displayName) }
                val bg = GradientDrawable().apply { cornerRadius = dp(10).toFloat() }
                bg.setColor(Color.parseColor("#FAFAFA"))
                bg.setStroke(dp(1), Color.parseColor("#EEEEEE"))
                background = bg
            }
            row.addView(TextView(ctx).apply {
                text = "$idx"
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#E65100"))
                setPadding(dp(6), dp(2), dp(6), dp(2))
            })
            row.addView(TextView(ctx).apply {
                text = "  $displayName($code)  "
                textSize = 13f
                setTextColor(Color.parseColor("#333333"))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })

            // RSA 趋势状态标签（加权标记；点击同样进入趋势图）
            if (!stateLabel.isNullOrBlank()) {
                row.addView(TextView(ctx).apply {
                    text = "RSA·$stateLabel"
                    textSize = 10f
                    setTextColor(if (stateBull) Color.parseColor("#E53935") else Color.parseColor("#43A047"))
                    setPadding(dp(4), dp(2), dp(4), dp(2))
                    setOnClickListener { jumpToTrendAndFocus(displayName, code, en, tag) }
                })
            }

            // 右侧形态标签（点击 → 跳到趋势图子页并定位该股图谱）
            row.addView(TextView(ctx).apply {
                text = "📈 $tag"
                textSize = 11f
                setTextColor(Color.parseColor("#E65100"))
                setPadding(dp(6), dp(2), dp(6), dp(2))
                setOnClickListener { jumpToTrendAndFocus(displayName, code, en, tag) }
            })
            listBox.addView(row)
        }
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

            // ②c 增量更新 K 线（内存去重）
            val fetcher = HistoricalDataFetcher(ctx)
            val recentDay = com.chin.stockanalysis.ui.TradingDayPickerView
                .recentTradingDay().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            var updated = 0; var skipped = 0; var failed = 0
            targets.forEachIndexed { i, code ->
                status("📡 扫描 ${i + 1}/${targets.size}：${nameMap[code] ?: code} ($code)")
                if (trendScanMemory.containsKey(code)) { skipped++; return@forEachIndexed }
                val have = try {
                    db.dailySnapshotDao().getMaxDateByCode().associate { it.code to it.maxDate }[code]
                } catch (e: Exception) { null }
                if (have != null && have >= recentDay) {
                    trendScanMemory[code] = System.currentTimeMillis(); skipped++; return@forEachIndexed
                }
                if (fetcher.fetchStockLatest(code)) {
                    updated++; trendScanMemory[code] = System.currentTimeMillis()
                } else failed++
            }

            // ③ 形态识别 → 按"上涨概率"（形态权重 + RSA 偏多）从高到低排序 → 注入 + 生成结果行
            data class Hit(
                val code: String,
                val name: String,
                val candles: List<DailySnapshotEntity>,
                val match: TrendPatternEngine.Match
            )
            val hits = mutableListOf<Hit>()
            for (code in targets) {
                val candles = try { db.dailySnapshotDao().getByCode(code, 40) } catch (e: Exception) { emptyList() }
                if (candles.size < 20) continue
                val match = TrendPatternEngine.match(candles.take(40).reversed()) ?: continue
                hits.add(Hit(code, nameMap[code] ?: code, candles, match))
            }
            // 上涨概率排序：形态权重(三白兵/上升趋势=3 > 看涨吞没=2 > 锤子线=1)最高在前；
            // 同权重 RSA 状态偏多优先；再按代码排序保证多次扫描顺序稳定
            hits.sortWith(
                compareByDescending<Hit> { it.match.weight }
                    .thenByDescending { if (it.match.stateBull) 1 else 0 }
                    .thenBy { it.code }
            )
            var injected = 0
            for (h in hits) {
                val en = buildAndInject(h.code, h.name, h.candles, h.match.cat to h.match.tag)
                addScanRow(
                    code = h.code,
                    name = h.name,
                    en = en,
                    tag = h.match.tag,
                    stateLabel = h.match.stateLabel,
                    stateBull = h.match.stateBull
                )
                injected++
            }
            val indexLine = indexTrendText(db)
            val stNote = if (stCount > 0) " 剔除ST/退市$stCount 只" else ""
            val marketNote = if (indexLine.isNotBlank())
                "\n$indexLine（形态识别仅针对个股，反映个股相对强弱）" else ""
            status("✅ 扫描完成：共 ${codes.size} 只（$countText）→ 更新 $updated/复用 $skipped/失败 $failed$stNote，识别看多形态 $injected 个$marketNote")
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

    override fun onDestroyView() {
        webView?.removeAllViews()
        webView?.destroy()
        webView = null
        super.onDestroyView()
    }
}
