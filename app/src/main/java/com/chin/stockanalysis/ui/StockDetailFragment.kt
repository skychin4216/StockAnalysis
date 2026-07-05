package com.chin.stockanalysis.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.widget.LinearLayout.LayoutParams
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.news.NewsFactorEntity
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource
import com.chin.stockanalysis.stock.data.sources.SectorSubDivision
import com.chin.stockanalysis.stock.database.ChinaMarketTradingHours as A股TradingHours
import com.chin.stockanalysis.stock.database.StockDataCenter
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.agent.framework.UnifiedAgentRunner
import com.chin.stockanalysis.strategy.data.InstitutionalRatingProvider
import com.github.mikephil.charting.charts.CandleStickChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.CandleData
import com.github.mikephil.charting.data.CandleDataSet
import com.github.mikephil.charting.data.CandleEntry
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ## 股票详情页（v1.0 — 完整版）
 *
 * 展示内容：
 * 1. 股票头部：名称/代码/现价/涨跌幅/涨跌额
 * 2. 所属板块：板块标签 + 子板块标签
 * 3. 热度评分：从板块热度推算
 * 4. 行情数据：开盘/最高/最低/成交量/成交额/换手率/市值
 * 5. 五档盘口：买卖比 + 低吸评级（可选，需网络数据）
 * 6. 相似股票：同子板块的其他股票（优先主板）
 * 7. AI 综合分析：结合新闻因子 + 成交情况的综合分析
 */
class StockDetailFragment : Fragment() {

    companion object {
        private const val ARG_STOCK_CODE = "stock_code"
        private const val ARG_STOCK_NAME = "stock_name"
        private const val ARG_STOCK_PRICE = "stock_price"
        private const val ARG_CHANGE_PCT = "change_pct"
        private const val ARG_SECTOR_NAME = "sector_name"
        private const val ARG_AUTO_EXPAND_AI = "auto_expand_ai"

        fun newInstance(
            stockCode: String,
            stockName: String,
            price: Double = 0.0,
            changePct: Double = 0.0,
            sectorName: String = "",
            autoExpandAi: Boolean = true
        ): StockDetailFragment {
            return StockDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_STOCK_CODE, stockCode)
                    putString(ARG_STOCK_NAME, stockName)
                    putDouble(ARG_STOCK_PRICE, price)
                    putDouble(ARG_CHANGE_PCT, changePct)
                    putString(ARG_SECTOR_NAME, sectorName)
                    putBoolean(ARG_AUTO_EXPAND_AI, autoExpandAi)
                }
            }
        }
    }

    private lateinit var root: LinearLayout
    private lateinit var contentContainer: LinearLayout
    private lateinit var loadingTv: TextView

    private var stockCode = ""
    private var stockName = ""
    private var initialPrice = 0.0
    private var initialChangePct = 0.0
    private var initialSector = ""
    private var autoExpandAi = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            stockCode = it.getString(ARG_STOCK_CODE, "")
            stockName = it.getString(ARG_STOCK_NAME, "")
            initialPrice = it.getDouble(ARG_STOCK_PRICE, 0.0)
            initialChangePct = it.getDouble(ARG_CHANGE_PCT, 0.0)
            initialSector = it.getString(ARG_SECTOR_NAME, "")
            autoExpandAi = it.getBoolean(ARG_AUTO_EXPAND_AI, true)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, b: Bundle?): View {
        root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F6FA"))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        // Header
        buildHeader()
        buildAiAnalysisSection()
        buildMarketStatusBar()
        buildMarketRiskBar()

        // Scroll content
        val sv = ScrollView(requireContext()).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        }
        contentContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 8, 12, 80)
        }

        loadingTv = TextView(requireContext()).apply {
            text = "⏳ 加载中..."; textSize = 14f; setTextColor(Color.GRAY)
            setPadding(24, 24, 24, 24)
        }
        contentContainer.addView(loadingTv)
        sv.addView(contentContainer)
        root.addView(sv)

        // 加載數據
        loadDetailData()
        loadMarketRisk()

        // 自動展開 AI 分析區（跳過簡單頁面，直接顯示 K 線+評級+分析按鈕）
        if (autoExpandAi) {
            aiExpanded = true
            root.findViewWithTag<TextView>("aiHeaderBtn")?.text = "🤖 AI分析 ▼"
            aiDetailContainer.visibility = View.VISIBLE
            if (aiDetailContainer.childCount == 0) {
                loadKlineAndRatings()
            }
        }

        // 攔截返回鍵：AI 結果顯示時先收起，再按才退出
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (aiResultContainer.visibility == View.VISIBLE) {
                    // AI 結果可見 → 收起結果，回到 K 線+評級
                    aiResultContainer.visibility = View.GONE
                    aiDetailContainer.visibility = View.VISIBLE
                } else if (aiExpanded) {
                    // AI 展開區可見 → 收起
                    aiExpanded = false
                    aiDetailContainer.visibility = View.GONE
                    aiResultContainer.visibility = View.GONE
                    root.findViewWithTag<TextView>("aiHeaderBtn")?.text = "🤖 AI分析 ▶"
                    // 恢復深度分析按鈕狀態
                    root.findViewWithTag<Button>("btnRunAi")?.let {
                        it.isEnabled = true
                        it.text = "🤖 運行AI深度分析"
                    }
                } else {
                    // 都已收起 → 正常退出
                    aiAnalysisJob?.cancel()  // 取消正在進行的分析
                    isEnabled = false
                    requireActivity().onBackPressed()
                }
            }
        })

        return root
    }

    private fun buildHeader() {
        val headerCard = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(20, 36, 20, 16)
            elevation = 2f
        }

        // 標題行：名稱 + 價格(小字靠右) + AI 按鈕
        val titleRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // 左側：名稱（左） + 價格（名稱右邊，小字）
        val namePriceCol = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        // 股票名稱
        namePriceCol.addView(TextView(requireContext()).apply {
            text = stockName
            textSize = 20f; setTextColor(Color.parseColor("#1A1A2E"))
            setTypeface(null, Typeface.BOLD)
        })
        // 價格（名稱右邊，小字）
        namePriceCol.addView(TextView(requireContext()).apply {
            tag = "tvPrice"
            text = if (initialPrice > 0) " ¥${String.format("%.2f", initialPrice)}" else ""
            textSize = 12f; setTextColor(Color.parseColor("#E53935"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 0, 0, 0)
        })
        // 漲跌幅（更小字）
        namePriceCol.addView(TextView(requireContext()).apply {
            tag = "tvChangePct"
            val sign = if (initialChangePct >= 0) "+" else ""
            text = if (initialChangePct != 0.0) " $sign${String.format("%.2f", initialChangePct)}%" else ""
            textSize = 10f; setTypeface(null, Typeface.BOLD)
            setTextColor(if (initialChangePct >= 0) Color.parseColor("#E53935") else Color.parseColor("#43A047"))
            gravity = Gravity.CENTER_VERTICAL
        })
        titleRow.addView(namePriceCol)

        // 右側：AI 分析按鈕
        val aiBtn = TextView(requireContext()).apply {
            tag = "aiHeaderBtn"
            text = "🤖 AI分析 ▶"
            textSize = 11f
            setTextColor(Color.parseColor("#FFFFFF"))
            setBackgroundColor(Color.parseColor("#2E7D32"))
            setPadding(12, 6, 12, 6)
            gravity = Gravity.CENTER
            setOnClickListener {
                toggleAiAnalysis()
                if (::aiDetailContainer.isInitialized) {
                    root.post { root.scrollTo(0, aiDetailContainer.top) }
                }
            }
        }
        titleRow.addView(aiBtn)
        headerCard.addView(titleRow)

        // 代碼 + 板塊標籤行
        val codeSectorRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4, 0, 0)
            tag = "codeSectorRow"
        }
        codeSectorRow.addView(TextView(requireContext()).apply {
            text = stockCode.takeLast(6)
            textSize = 11f; setTextColor(Color.parseColor("#999999"))
        })
        // 板塊標籤
        if (initialSector.isNotEmpty()) {
            codeSectorRow.addView(createTagChip(initialSector, "#1565C0"))
        }
        headerCard.addView(codeSectorRow)

        root.addView(headerCard)
    }

    private fun buildMarketStatusBar() {
        val statusBar = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 4, 12, 4)
            setBackgroundColor(Color.parseColor("#FFF3E0"))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            tag = "statusBar"
        }
        val statusText = A股TradingHours.获取状态摘要()
        statusBar.addView(TextView(requireContext()).apply {
            text = statusText
            textSize = 10f; setTextColor(Color.parseColor("#E65100"))
            maxLines = 2
        })
        root.addView(statusBar)
    }

    /** 大盤風險提示條（初始隱藏，加載數據後顯示） */
    private fun buildMarketRiskBar() {
        val riskBar = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 4, 12, 4)
            setBackgroundColor(Color.parseColor("#FFEBEE"))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            visibility = View.GONE  // 初始隱藏
            tag = "marketRiskBar"
        }
        val riskTv = TextView(requireContext()).apply {
            text = ""
            textSize = 10f; setTextColor(Color.parseColor("#C62828"))
            tag = "marketRiskTv"
        }
        riskBar.addView(riskTv)
        root.addView(riskBar)
    }

    /** 加載大盤風險狀態（異步，不阻塞 UI） */
    private fun loadMarketRisk() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val report = com.chin.stockanalysis.strategy.market.MarketAnalyzer.analyze(
                    requireContext().applicationContext, emptyList()
                )
                val trend = report.trend
                val sellType = report.sellType

                val msg = buildString {
                    when (trend.direction) {
                        "BEARISH" -> {
                            append("⚠️ 大盤下行（強度${trend.strength}/100）")
                            if (sellType.sellType == "INSTITUTIONAL_EXIT") append(" | 主力撤資中")
                            else if (sellType.sellType == "QUANT_CRASH") append(" | 量化砸盤")
                            append(" — 建議降低倉位，關注防禦板塊")
                        }
                        "OSCILLATION" -> {
                            if (trend.strength > 40) append("⚡ 大盤震蕩加劇（強度${trend.strength}） — 控制倉位")
                            else append("📊 大盤震蕩（強度${trend.strength}） — 輕倉操作")
                        }
                        else -> return@launch // BULLISH 不顯示風險條
                    }
                }

                withContext(Dispatchers.Main) {
                    val riskBar = root.findViewWithTag<LinearLayout>("marketRiskBar")
                    val riskTv = root.findViewWithTag<TextView>("marketRiskTv")
                    if (riskBar != null && riskTv != null) {
                        riskTv.text = msg
                        riskBar.visibility = View.VISIBLE
                        // 根據風險等級設置背景色
                        riskBar.setBackgroundColor(when (trend.direction) {
                            "BEARISH" -> Color.parseColor("#FFCDD2")
                            else -> Color.parseColor("#FFF3E0")
                        })
                    }
                }
            } catch (_: Exception) {
                // 大盤風險加載失敗不影響頁面
            }
        }
    }

    /** 動態更新 header 中的板塊標籤（loadDetailData 獲取板塊後回調） */
    private fun updateHeaderSectorTags(sectors: List<String>, subSector: String) {
        val codeSectorRow = root.findViewWithTag<LinearLayout>("codeSectorRow")
            ?: return
        // 移除舊板塊標籤（保留第一個 TextView 即股票代碼）
        while (codeSectorRow.childCount > 1) {
            codeSectorRow.removeViewAt(codeSectorRow.childCount - 1)
        }
        // 添加新板塊標籤
        if (subSector.isNotEmpty() && subSector != "-") {
            codeSectorRow.addView(createTagChip(subSector, "#E65100"))
        }
        for (sector in sectors.take(2)) {
            if (sector != subSector) {
                codeSectorRow.addView(createTagChip(sector, "#1565C0"))
            }
        }
    }

    // ── AI 分析展開區域 ──
    private var aiExpanded = false
    private var aiContentAdded = false
    private lateinit var aiDetailContainer: LinearLayout  // K線+機構評級
    private lateinit var aiResultContainer: LinearLayout   // AI Agent 分析結果

    private fun buildAiAnalysisSection() {
        // 容器 1：K線走勢 + 機構評級（點擊展開時立即顯示）
        aiDetailContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 0, 12, 8)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            visibility = View.GONE
            tag = "aiDetailContainer"
        }
        root.addView(aiDetailContainer)

        // 容器 2：AI Agent 分析結果（用戶點擊「運行AI分析」後顯示，替換容器1）
        aiResultContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 0, 12, 8)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            visibility = View.GONE
            tag = "aiResultContainer"
        }
        root.addView(aiResultContainer)
    }

    private fun toggleAiAnalysis() {
        aiExpanded = !aiExpanded
        // 更新標題行 AI 按鈕的箭頭
        root.findViewWithTag<TextView>("aiHeaderBtn")?.text =
            if (aiExpanded) "🤖 AI分析 ▼" else "🤖 AI分析 ▶"

        if (aiExpanded) {
            // 展開：優先顯示 K線 + 機構評級
            aiResultContainer.visibility = View.GONE
            aiDetailContainer.visibility = View.VISIBLE
            if (aiDetailContainer.childCount == 0) {
                loadKlineAndRatings()
            }
        } else {
            aiDetailContainer.visibility = View.GONE
            aiResultContainer.visibility = View.GONE
        }
    }

    /** 構建真正的 K 線圖表（CandleStickChart + 坐標軸） */
    private fun buildCandleStickChart(snaps: List<DailySnapshotEntity>): CandleStickChart {
        val chart = CandleStickChart(requireContext())
        chart.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dpToPx(160))
        chart.setBackgroundColor(Color.WHITE)
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setScaleEnabled(true)
        chart.setPinchZoom(true)

        // 數據
        val entries = ArrayList<CandleEntry>()
        for (i in snaps.indices) {
            val s = snaps[i]
            entries.add(CandleEntry(
                i.toFloat(),
                s.high.toFloat(),       // shadow (上影線)
                s.low.toFloat(),        // shadow (下影線)
                s.open.toFloat(),       // open
                s.close.toFloat()       // close
            ))
        }
        val dataSet = CandleDataSet(entries, "K線").apply {
            color = Color.parseColor("#333333")
            shadowColor = Color.parseColor("#999999")
            shadowWidth = 1f
            increasingPaintStyle = android.graphics.Paint.Style.FILL
            decreasingPaintStyle = android.graphics.Paint.Style.FILL
            increasingColor = Color.parseColor("#E53935")  // 漲紅
            decreasingColor = Color.parseColor("#43A047")  // 跌綠
            valueTextSize = 9f
            isHighlightEnabled = true
        }
        chart.data = CandleData(dataSet)

        // X 軸：日期
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            granularity = 1f
            textSize = 9f
            textColor = Color.parseColor("#999999")
            setDrawGridLines(false)
            valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    val idx = value.toInt()
                    return if (idx in snaps.indices) snaps[idx].date.takeLast(5) else ""
                }
            }
        }

        // Y 軸
        chart.axisLeft.apply {
            textSize = 9f
            textColor = Color.parseColor("#999999")
            setDrawGridLines(true)
            gridColor = Color.parseColor("#EEEEEE")
            valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
                override fun getFormattedValue(value: Float): String =
                    "%.2f".format(value)
            }
        }
        chart.axisRight.isEnabled = false

        chart.invalidate()
        return chart
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()

    /** 加載 K 線走勢 + 機構評級數據 */
    private fun loadKlineAndRatings() {
        aiDetailContainer.removeAllViews()
        // 加載提示
        aiDetailContainer.addView(TextView(requireContext()).apply {
            text = "⏳ 正在加載 K 線和機構評級..."
            textSize = 10f; setTextColor(Color.parseColor("#999999"))
        })

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val sb = StringBuilder()

                // ── 1. K 線走勢（用 CandleStickChart 真正的圖表） ──
                val snaps = db.dailySnapshotDao().getByCode(stockCode, 30).sortedBy { it.date }
                withContext(Dispatchers.Main) { aiDetailContainer.removeAllViews() }

                if (snaps.size >= 5) {
                    withContext(Dispatchers.Main) {
                        aiDetailContainer.addView(TextView(requireContext()).apply {
                            text = "## 近${snaps.size}日K線走勢"
                            textSize = 12f; setTextColor(Color.parseColor("#333333"))
                            setTypeface(null, Typeface.BOLD)
                            setPadding(0, 0, 0, 4)
                        })
                        val chart = buildCandleStickChart(snaps)
                        aiDetailContainer.addView(chart)
                        // MA5 簡評
                        val closes = snaps.map { it.close }
                        val ma5 = closes.takeLast(5).average()
                        val currentPrice = closes.last()
                        aiDetailContainer.addView(TextView(requireContext()).apply {
                            text = "MA5: ¥${"%.2f".format(ma5)} | 現價${if (currentPrice > ma5) ">" else "<"}MA5 → ${if (currentPrice > ma5) "短期偏多" else "短期偏空"}"
                            textSize = 10f; setTextColor(Color.parseColor("#666666"))
                            setPadding(0, 4, 0, 8)
                        })
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        aiDetailContainer.addView(TextView(requireContext()).apply {
                            text = "K線數據不足（需要至少5天）"
                            textSize = 11f; setTextColor(Color.parseColor("#999999"))
                            setPadding(0, 0, 0, 8)
                        })
                    }
                }

                // ── 2. 機構評級 ──
                sb.appendLine("## 🏦 機構評級（近90天）")
                val ratingProvider = com.chin.stockanalysis.strategy.data.InstitutionalRatingProvider()
                val ratingSummary = ratingProvider.getRatingSummary(stockCode, days = 90)

                if (ratingSummary.totalReports == 0) {
                    sb.appendLine("暫無機構評級數據")
                } else {
                    val emoji = when (ratingSummary.consensusRating) {
                        "買入" -> "🔴"
                        "增持" -> "🟠"
                        "中性" -> "⚪"
                        else -> "🟢"
                    }
                    sb.appendLine("共識評級: $emoji ${ratingSummary.consensusRating}（${ratingSummary.totalReports}份研報）")
                    sb.appendLine("買入${ratingSummary.buyCount} / 增持${ratingSummary.overweightCount} / 中性${ratingSummary.neutralCount} / 賣出${ratingSummary.sellCount}")

                    if (ratingSummary.avgTargetPrice != null) {
                        val upside = if (ratingSummary.avgTargetPrice > 0 && snaps.isNotEmpty()) {
                            val last = snaps.last().close
                            (ratingSummary.avgTargetPrice - last) / last * 100
                        } else 0.0
                        sb.appendLine("平均目標價: ¥${"%.2f".format(ratingSummary.avgTargetPrice)}（空間${if (upside >= 0) "↑" else "↓"}${"%.1f".format(kotlin.math.abs(upside))}%）")
                    }

                    if (ratingSummary.latestOrgs.isNotEmpty()) {
                        sb.appendLine("最近評級機構: ${ratingSummary.latestOrgs.take(3).joinToString("、")}")
                    }

                    // 詳細評級
                    val topRatings = ratingSummary.detailList.take(8)
                    sb.appendLine()
                    sb.appendLine("### 詳細評級")
                    for (r in topRatings) {
                        val target = r.targetPriceHigh?.let { "目標¥${"%.1f".format(it)}" } ?: ""
                        val change = if (r.ratingChange != "未知") "【${r.ratingChange}】" else ""
                        val eps = mutableListOf<String>()
                        r.predictEpsThisYear?.let { eps.add("今年¥${"%.2f".format(it)}") }
                        r.predictEpsNextYear?.let { eps.add("明年¥${"%.2f".format(it)}") }
                        sb.appendLine("• ${r.orgName}: ${r.rating} $change $target (${r.publishDate})")
                        if (eps.isNotEmpty()) sb.appendLine("  EPS: ${eps.joinToString(" / ")}")
                    }
                }
                sb.appendLine()

                // ── 3. 基金持倉 ──
                sb.appendLine("## 💰 基金持倉（持倉市值TOP10）")
                val fundHoldings = ratingProvider.getFundHoldings(stockCode, 10)
                if (fundHoldings.isEmpty()) {
                    sb.appendLine("暫無基金持倉數據")
                } else {
                    for (f in fundHoldings) {
                        val capStr = if (f.holdMarketCap >= 10000)
                            "%.1f億".format(f.holdMarketCap / 10000.0)
                        else "%.0f萬".format(f.holdMarketCap)
                        sb.appendLine("• ${f.fundName}(${f.fundCode}): ${capStr} 占比${"%.2f".format(f.holdRatio)}% (${f.reportDate})")
                    }
                    val totalCap = fundHoldings.sumOf { it.holdMarketCap }
                    val totalStr = if (totalCap >= 10000) "%.1f億".format(totalCap / 10000.0) else "%.0f萬".format(totalCap)
                    sb.appendLine("\n合計持倉市值: $totalStr | ${fundHoldings.size}只基金")
                }
                sb.appendLine()

                withContext(Dispatchers.Main) {
                    aiDetailContainer.addView(TextView(requireContext()).apply {
                        text = sb.toString()
                        textSize = 11f; setTextColor(Color.parseColor("#333333"))
                        setLineSpacing(3f, 1f)
                    })
                    // 底部按鈕行：運行AI深度分析 + 向AI追問 同一行
                    val btnRow = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        setPadding(0, 4, 0, 0)
                    }
                    btnRow.addView(Button(requireContext()).apply {
                        tag = "btnRunAi"
                        text = "🤖 運行AI深度分析"
                        textSize = 9f; setTextColor(Color.WHITE)
                        setBackgroundColor(Color.parseColor("#2E7D32"))
                        setPadding(12, 4, 12, 4)
                        minimumHeight = 0; minHeight = 0
                        setOnClickListener {
                            it.isEnabled = false
                            text = "深度分析中..."
                            runAiAgents(UnifiedAgentRunner.MODE_PIPELINE)
                        }
                    })
                    btnRow.addView(Button(requireContext()).apply {
                        text = "💬 向AI追問"
                        textSize = 9f; setTextColor(Color.parseColor("#1565C0"))
                        setBackgroundColor(Color.parseColor("#E3F2FD"))
                        setPadding(12, 4, 12, 4)
                        minimumHeight = 0; minHeight = 0
                        setOnClickListener {
                            val today = java.time.LocalDate.now()
                            val msg = "請使用【最新交易日（${today}）的實時行情數據】，詳細分析股票 $stockName($stockCode) 的投資價值，包括：\n" +
                                "1. 基本面分析（財報、估值、業績）\n" +
                                "2. 技術面分析（K線走勢、支撐阻力位）\n" +
                                "3. 資金面分析（主力資金流向、機構動態）\n" +
                                "4. 風險評估與投資建議\n" +
                                "請嚴格基於實時數據分析，不要使用訓練數據中的舊價格。"
                            val mainActivity = activity as? com.chin.stockanalysis.ui.MainActivity
                            if (mainActivity != null) {
                                activity?.supportFragmentManager?.popBackStack()
                                mainActivity.switchToChatAndSend(msg)
                            }
                        }
                    })
                    aiDetailContainer.addView(btnRow)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    aiDetailContainer.removeAllViews()
                    aiDetailContainer.addView(TextView(requireContext()).apply {
                        text = "加載失敗: ${e.message}"
                        textSize = 11f; setTextColor(Color.parseColor("#C62828"))
                    })
                }
            }
        }
    }

    /** 當前正在進行的 AI 分析 Job（返回鍵可取消） */
    private var aiAnalysisJob: Job? = null

    /** 運行 AI Agent 分析，切換到 aiResultContainer */
    private fun runAiAgents(mode: String = UnifiedAgentRunner.MODE_QUICK) {
        // 取消之前正在進行的分析
        aiAnalysisJob?.cancel()

        aiResultContainer.removeAllViews()
        val loadingRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 4)
        }
        loadingRow.addView(ProgressBar(requireContext()).apply {
            layoutParams = LayoutParams(dpToPx(16), dpToPx(16))
            setPadding(0, 0, 4, 0)
        })
        loadingRow.addView(TextView(requireContext()).apply {
            text = if (mode == UnifiedAgentRunner.MODE_PIPELINE) "深度分析中（需要30-60秒）..." else "AI 分析中..."
            textSize = 9f; setTextColor(Color.parseColor("#999999"))
        })
        aiResultContainer.addView(loadingRow)
        // 切換 view
        aiDetailContainer.visibility = View.GONE
        aiResultContainer.visibility = View.VISIBLE

        aiAnalysisJob = lifecycleScope.launch(Dispatchers.IO) {
            // 統一 Agent 分析入口
            val result = UnifiedAgentRunner.run(
                context = requireContext().applicationContext,
                stockCode = stockCode,
                stockName = stockName,
                mode = mode,
                sector = initialSector.takeIf { it.isNotEmpty() }
            )

            // 檢查是否被取消或 view 已銷毀
            ensureActive()
            if (view == null || !isAdded) return@launch

            withContext(Dispatchers.Main) {
                aiResultContainer.removeAllViews()
                aiResultContainer.addView(TextView(requireContext()).apply {
                    text = result.summaryText
                    textSize = if (result.mode == UnifiedAgentRunner.MODE_PIPELINE) 9f else 10f
                    setTextColor(Color.parseColor("#1B5E20"))
                    setLineSpacing(2f, 1f)
                    setPadding(0, 0, 0, 2)
                })
                // 模式標籤
                if (result.mode == UnifiedAgentRunner.MODE_PIPELINE) {
                    aiResultContainer.addView(TextView(requireContext()).apply {
                        text = "🧠 ${result.elapsedMs}ms · Pipeline 深度分析"
                        textSize = 8f; setTextColor(Color.parseColor("#666666"))
                        gravity = Gravity.CENTER
                        setPadding(0, 2, 0, 2)
                    })
                }
                // 底部按鈕行：返回 + 加倉/減倉/清倉 同一行
                val bottomRow = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setPadding(0, 4, 0, 2)
                }
                // 返回按鈕
                bottomRow.addView(Button(requireContext()).apply {
                    text = "◀ 返回K綫"
                    textSize = 9f; setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#757575"))
                    setPadding(10, 4, 10, 4)
                    minimumHeight = 0; minHeight = 0
                    setOnClickListener {
                        aiResultContainer.visibility = View.GONE
                        aiDetailContainer.visibility = View.VISIBLE
                        // 恢復深度分析按鈕
                        root.findViewWithTag<Button>("btnRunAi")?.let {
                            it.isEnabled = true
                            it.text = "🤖 運行AI深度分析"
                        }
                    }
                })
                // 操作按鈕
                val recommendation = result.recommendation
                if (recommendation != null) {
                    if (recommendation in listOf("BUY", "WATCH")) {
                        bottomRow.addView(Button(requireContext()).apply {
                            text = "➕ 加倉"; textSize = 9f
                            setBackgroundColor(Color.parseColor("#1565C0"))
                            setPadding(10, 4, 10, 4)
                            minimumHeight = 0; minHeight = 0
                            setOnClickListener { Toast.makeText(requireContext(), "請在建倉流程中加倉 $stockName", Toast.LENGTH_LONG).show() }
                        })
                    }
                    if (recommendation in listOf("HOLD", "SELL", "WATCH")) {
                        bottomRow.addView(Button(requireContext()).apply {
                            text = "➖ 減倉"; textSize = 9f
                            setBackgroundColor(Color.parseColor("#F9A825"))
                            setPadding(10, 4, 10, 4)
                            minimumHeight = 0; minHeight = 0
                            setOnClickListener { Toast.makeText(requireContext(), "減倉功能開發中", Toast.LENGTH_SHORT).show() }
                        })
                    }
                    if (recommendation in listOf("HOLD", "SELL")) {
                        bottomRow.addView(Button(requireContext()).apply {
                            text = "❌ 清倉"; textSize = 9f
                            setBackgroundColor(Color.parseColor("#C62828"))
                            setPadding(10, 4, 10, 4)
                            minimumHeight = 0; minHeight = 0
                            setOnClickListener { Toast.makeText(requireContext(), "清倉功能開發中", Toast.LENGTH_SHORT).show() }
                        })
                    }
                }
                aiResultContainer.addView(bottomRow)
            }
        }
    }

    private fun loadDetailData() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. 获取实时行情（优先）
                val leaderData = fetchRealtimeData()

                // 2. 获取所属板块
                val sectors = StockDataCenter.getSectorsByStock(stockCode)
                val subSector = StockDataCenter.getSubSectorByStock(stockCode, stockName)

                // 3. 获取相似股票
                val similarStocks = if (sectors.isNotEmpty()) {
                    fetchSimilarStocks(sectors.first(), subSector)
                } else emptyList()

                // 4. 获取新闻因子
                val newsFactors = fetchNewsFactors()

                withContext(Dispatchers.Main) {
                    loadingTv.visibility = View.GONE
                    // 更新 header：板塊標籤 + 價格/漲跌幅
                    if (leaderData != null) {
                        root.findViewWithTag<TextView>("tvPrice")?.text =
                            " ¥${String.format("%.2f", leaderData.price)}"
                        root.findViewWithTag<TextView>("tvChangePct")?.let { tv ->
                            val sign = if (leaderData.changePercent >= 0) "+" else ""
                            tv.text = " $sign${String.format("%.2f", leaderData.changePercent)}%"
                            tv.setTextColor(if (leaderData.changePercent >= 0)
                                Color.parseColor("#E53935") else Color.parseColor("#43A047"))
                        }
                    }
                    if (sectors.isNotEmpty() && initialSector.isEmpty()) {
                        updateHeaderSectorTags(sectors, subSector)
                    }
                    if (leaderData != null) buildRealtimeSection(leaderData)
                    buildSectorSection(sectors, subSector)
                    if (similarStocks.isNotEmpty()) buildSimilarStocksSection(similarStocks)
                    if (newsFactors.isNotEmpty()) buildNewsSection(newsFactors)
                    buildAIAnalysisSection(leaderData, sectors, subSector, newsFactors)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loadingTv.text = "⚠️ 加载失败: ${e.message}"
                }
            }
        }
    }

    /** 拉取东方财富实时龙头股数据 */
    private suspend fun fetchRealtimeData(): EastMoneyHotSectorSource.LeaderStock? {
        if (initialSector.isEmpty()) return null
        // 尝试通过 sector code 获取实时数据
        try {
            val source = EastMoneyHotSectorSource()
            // 搜索匹配的板块代码
            val allSectors = EastMoneyHotSectorSource.industrySectors +
                    EastMoneyHotSectorSource.conceptSectors
            val sectorMatch = allSectors.find { it.name == initialSector }
            if (sectorMatch != null && sectorMatch.code.isNotEmpty()) {
                val leaders = source.fetchSectorLeaders(sectorMatch.code, 20)
                return leaders.find { it.code == stockCode }
            }
        } catch (_: Exception) {}
        return null
    }

    /** 获取新闻因子 */
    private suspend fun fetchNewsFactors(): List<NewsFactorEntity> {
        return try {
            val db = StockDatabase.getInstance(requireContext())
            db.newsFactorDao().getAllActive(100)
                .filter { it.stockCode == stockCode || it.companyName.contains(stockName) }
                .take(5)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 获取相似股票（同板块优先主板） */
    private suspend fun fetchSimilarStocks(sectorName: String, subSectorName: String): List<SectorSubDivision.EnrichedStock> {
        val subSectors = SectorSubDivision.getSubSectors(sectorName)
        if (subSectors.isEmpty()) return emptyList()

        // 优先匹配子板块
        val matched = subSectors.firstOrNull { it.name == subSectorName || it.name.contains(subSectorName) }
            ?: subSectors.firstOrNull()

        return matched?.let { ss ->
            // 主板优先
            val main = ss.mainBoardStocks.filter { it.code != stockCode }.take(4)
            val gem = ss.gemKcbStocks.filter { it.code != stockCode }.take(2)
            main + gem
        } ?: emptyList()
    }

    // ═══════════════════════════════════════════════════════
    // UI Sections
    // ═══════════════════════════════════════════════════════

    /** 实时行情数据卡片 */
    private fun buildRealtimeSection(data: EastMoneyHotSectorSource.LeaderStock) {
        val card = createSectionCard()
        card.addView(createSectionTitle("📊 实时行情"))

        val grid = TableLayout(requireContext()).apply {
            isStretchAllColumns = true; setPadding(4, 4, 4, 4)
        }
        val kvPairs = listOf(
            "现价" to String.format("%.2f", data.price),
            "涨跌" to "${if (data.changePercent >= 0) "+" else ""}${String.format("%.2f", data.changePercent)}%",
            "换手率" to if (data.turnoverRate > 0) String.format("%.1f%%", data.turnoverRate) else "—",
            "主力流入" to if (data.mainNetInflow != 0.0) "${if (data.mainNetInflow > 0) "+" else ""}${String.format("%.1f", data.mainNetInflow)}亿" else "—",
            "市值" to if (data.marketCap > 0) "${String.format("%.0f", data.marketCap)}亿" else "—",
            "涨停板" to if (data.isBoard) "🔴 涨停" else "-",
            "连板天数" to if (data.limitDays > 0) "${data.limitDays}天" else "-",
            "3日流入" to if (data.threeDayInflow > 0) "${String.format("%.1f", data.threeDayInflow)}亿" else "-"
        )

        var count = 0
        var row: TableRow? = null
        for ((label, value) in kvPairs) {
            if (count % 2 == 0) {
                row = TableRow(requireContext())
                grid.addView(row)
            }
            val cell = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(4, 6, 4, 6)
                layoutParams = TableRow.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            }
            cell.addView(TextView(requireContext()).apply {
                text = "$label: "; textSize = 11f; setTextColor(Color.parseColor("#999999"))
            })
            cell.addView(TextView(requireContext()).apply {
                text = value; textSize = 12f; setTextColor(Color.parseColor("#333333"))
                setTypeface(null, Typeface.BOLD)
            })
            row?.addView(cell)
            count++
        }
        card.addView(grid)
        contentContainer.addView(card)
    }

    /** 所属板块区域 */
    private fun buildSectorSection(sectors: List<String>, subSector: String) {
        val card = createSectionCard()
        card.addView(createSectionTitle("🏷 所属板块"))

        val tagsLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(4, 6, 4, 4)
            tag = "sectorTags"
        }

        if (subSector.isNotEmpty() && subSector != "-") {
            tagsLayout.addView(createTagChip(subSector, "#E65100"))
        }

        for (sector in sectors.take(3)) {
            if (sector != subSector) {
                tagsLayout.addView(createTagChip(sector, "#1565C0"))
            }
        }

        if (sectors.isEmpty()) {
            tagsLayout.addView(TextView(requireContext()).apply {
                text = "暂无板块数据"; textSize = 12f; setTextColor(Color.GRAY)
            })
        }

        card.addView(tagsLayout)
        contentContainer.addView(card)

        // 趨勢圖片區
        buildTrendImagesSection()
    }

    /** 趨勢圖片展示（從 assets/trend_images 加載） */
    private fun buildTrendImagesSection() {
        val ctx = requireContext()
        val assets = ctx.assets
        val imageNames = try {
            assets.list("trend_images")?.filter { it.endsWith(".jpg") || it.endsWith(".png") } ?: emptyList()
        } catch (_: Exception) { return }

        if (imageNames.isEmpty()) return

        val card = createSectionCard()
        card.addView(createSectionTitle("📈 趨勢參考圖"))

        val scroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(4, 4, 4, 4)
        }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

        for (name in imageNames.take(6)) { // 最多顯示6張
            val iv = ImageView(ctx).apply {
                layoutParams = LayoutParams(dpToPx(140), dpToPx(140)).apply {
                    setMargins(4, 0, 4, 0)
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.parseColor("#F5F5F5"))
            }
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val bmp = android.graphics.BitmapFactory.decodeStream(assets.open("trend_images/$name"))
                    withContext(Dispatchers.Main) { iv.setImageBitmap(bmp) }
                } catch (_: Exception) {}
            }
            // 點擊全屏
            iv.setOnClickListener {
                val dialog = android.app.Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                val fullIv = ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setOnClickListener { dialog.dismiss() }
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val bmp = assets.open("trend_images/$name").use {
                            android.graphics.BitmapFactory.decodeStream(it)
                        }
                        withContext(Dispatchers.Main) { fullIv.setImageBitmap(bmp) }
                    } catch (_: Exception) {}
                }
                dialog.setContentView(fullIv)
                dialog.show()
            }
            row.addView(iv)
        }

        scroll.addView(row)
        card.addView(scroll)
        contentContainer.addView(card)
    }

    /** 相似股票区域 */
    private fun buildSimilarStocksSection(stocks: List<SectorSubDivision.EnrichedStock>) {
        val card = createSectionCard()
        card.addView(createSectionTitle("🔗 相似股票（同板块·主板优先）"))

        val listLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(4, 4, 4, 4)
        }

        for ((idx, s) in stocks.withIndex()) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(4, 6, 4, 6)
                if (idx % 2 == 1) setBackgroundColor(Color.parseColor("#F8F9FC"))

                setOnClickListener {
                    // 点击相似股票 → 打开新的股票详情页
                    val d = StockDetailFragment.newInstance(
                        s.code, s.name, 0.0, 0.0,
                        "" // 保留在同一板块内
                    )
                    activity?.supportFragmentManager
                        ?.beginTransaction()
                        ?.replace(android.R.id.content, d)
                        ?.addToBackStack(null)
                        ?.commit()
                }
            }

            // 主板标识
            val boardBadge = if (s.isMainBoard) "🔵" else "🟣"
            row.addView(TextView(requireContext()).apply {
                text = "$boardBadge ${s.name}"
                textSize = 13f; setTextColor(Color.parseColor("#333333"))
                setTypeface(null, Typeface.BOLD)
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(requireContext()).apply {
                text = s.business.take(15) + if (s.business.length > 15) "..." else ""
                textSize = 11f; setTextColor(Color.parseColor("#888888"))
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.5f)
            })

            listLayout.addView(row)
        }

        card.addView(listLayout)
        contentContainer.addView(card)
    }

    /** 新闻因子区域 */
    private fun buildNewsSection(newsFactors: List<NewsFactorEntity>) {
        val card = createSectionCard()
        card.addView(createSectionTitle("📰 相关新闻情绪（${newsFactors.size}条）"))

        for (news in newsFactors.take(5)) {
            val newsRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(4, 6, 4, 6)
                gravity = Gravity.CENTER_VERTICAL
            }
            val sentimentEmoji = when {
                news.sentiment > 0 -> "📈"
                news.sentiment < 0 -> "📉"
                else -> "📊"
            }
            val impactText = when {
                news.impactStrength >= 70 -> "高影响"
                news.impactStrength >= 40 -> "中影响"
                else -> "低影响"
            }
            val sentimentColor = when {
                news.sentiment > 0 -> Color.parseColor("#E53935")
                news.sentiment < 0 -> Color.parseColor("#43A047")
                else -> Color.parseColor("#999999")
            }

            newsRow.addView(TextView(requireContext()).apply {
                text = "$sentimentEmoji $impactText"
                textSize = 10f; setTypeface(null, Typeface.BOLD)
                setTextColor(sentimentColor)
                setPadding(0, 0, 4, 0)
            })
            newsRow.addView(TextView(requireContext()).apply {
                text = news.title.take(40)
                textSize = 11f; setTextColor(Color.parseColor("#333333"))
                maxLines = 2
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            })

            card.addView(newsRow)
        }

        contentContainer.addView(card)
    }

    /** AI 综合分析区域 */
    private fun buildAIAnalysisSection(
        leaderData: EastMoneyHotSectorSource.LeaderStock?,
        sectors: List<String>,
        subSector: String,
        newsFactors: List<NewsFactorEntity>
    ) {
        val card = createSectionCard()
        card.addView(createSectionTitle("🤖 AI 综合分析"))

        val ctx = requireContext()

        // 分析模板
        val analysis = StringBuilder()
        analysis.appendLine("📋 **$stockName ($stockCode)** 综合分析报告")
        analysis.appendLine()

        // 板块定位
        val sectorStr = if (subSector.isNotEmpty() && subSector != "-") subSector else sectors.firstOrNull() ?: "未分类"
        analysis.appendLine("**所属赛道**: $sectorStr")
        analysis.appendLine("**行业地位**: ${if ((leaderData?.marketCap ?: 0.0) > 500.0) "行业龙头/核心标的" else if ((leaderData?.marketCap ?: 0.0) > 100.0) "板块重要成员" else "板块关联标的"}")
        analysis.appendLine()

        // 行情分析
        if (leaderData != null) {
            analysis.appendLine("**行情分析**:")
            analysis.appendLine("- 现价: ${String.format("%.2f", leaderData.price)} (${if (leaderData.changePercent >= 0) "+" else ""}${String.format("%.2f", leaderData.changePercent)}%)")
            analysis.appendLine("- 换手率: ${if (leaderData.turnoverRate > 0) String.format("%.1f%%", leaderData.turnoverRate) else "暂无数据"}")
            analysis.appendLine("- 主力资金: ${if (leaderData.mainNetInflow != 0.0) "${if (leaderData.mainNetInflow > 0) "+" else ""}${String.format("%.1f", leaderData.mainNetInflow)}亿" else "暂无数据"}")
            if (leaderData.isBoard) analysis.appendLine("- ⚠️ 该股已涨停，注意追高风险")
            if (leaderData.limitDays > 0) analysis.appendLine("- 🚀 连板${leaderData.limitDays}天，市场情绪极强")
            analysis.appendLine()
        }

        // 新闻情绪分析
        if (newsFactors.isNotEmpty()) {
            val bullishCount = newsFactors.count { it.sentiment > 0 }
            val bearishCount = newsFactors.count { it.sentiment < 0 }
            val neutralCount = newsFactors.count { it.sentiment == 0 }
            val avgSentiment = newsFactors.map { it.sentiment }.average()
            val avgImpact = newsFactors.map { it.impactStrength }.average()

            analysis.appendLine("**新闻情绪分析** (${newsFactors.size}条相关新闻):")
            analysis.appendLine("- 利好: $bullishCount 条 | 利空: $bearishCount 条 | 中性: $neutralCount 条")
            analysis.appendLine("- 平均情绪: ${if (avgSentiment > 0) "偏暖" else if (avgSentiment < 0) "偏冷" else "中性"}(${String.format("%.0f", avgSentiment)}%)")
            analysis.appendLine("- 影响力评分: ${String.format("%.0f", avgImpact)}/100")
            analysis.appendLine()
        }

        // 综合建议
        analysis.appendLine("**综合建议**:")
        analysis.appendLine("- 短期趋势: ${if ((leaderData?.changePercent ?: 0.0) > 0.0) "偏强 📈" else if ((leaderData?.changePercent ?: 0.0) < 0.0) "偏弱 📉" else "平稳 📊"}")
        analysis.appendLine("- 资金面: ${if ((leaderData?.mainNetInflow ?: 0.0) > 0.0) "主力净流入" else if ((leaderData?.mainNetInflow ?: 0.0) < 0.0) "主力净流出" else "资金平衡"}")
        val bullishCount = newsFactors.count { it.sentiment > 0 }
        val bearishCount = newsFactors.count { it.sentiment < 0 }
        analysis.appendLine("- 新闻面: ${if (bullishCount > bearishCount) "利好偏多" else if (bearishCount > bullishCount) "利空偏多" else "消息面中性"}")
        analysis.appendLine()
        analysis.appendLine("⚠️ 投资有风险，入市需谨慎。以上分析仅供参考，不构成投资建议。")

        val analysisLabel = TextView(ctx).apply {
            text = analysis.toString()
            textSize = 11f; setTextColor(Color.parseColor("#333333"))
            setLineSpacing(4f, 1.2f)
            setPadding(4, 6, 4, 8)
        }
        card.addView(analysisLabel)

        contentContainer.addView(card)
    }

    // ═══════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════

    private fun createSectionCard(): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(12, 10, 12, 10)
            elevation = 2f
            (layoutParams as? LayoutParams)?.setMargins(0, 0, 0, 10)
        }
    }

    private fun createSectionTitle(title: String): TextView {
        return TextView(requireContext()).apply {
            text = title
            textSize = 15f; setTextColor(Color.parseColor("#1A1A2E"))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        }
    }

    private fun createTagChip(text: String, colorHex: String): TextView {
        return TextView(requireContext()).apply {
            this.text = text; textSize = 11f
            setTextColor(Color.parseColor(colorHex))
            setBackgroundColor(Color.parseColor(colorHex) and 0x00FFFFFF or 0x20000000) // 20% alpha
            setPadding(10, 3, 10, 3)
            (layoutParams as? LayoutParams)?.setMargins(0, 0, 8, 0)
        }
    }
}