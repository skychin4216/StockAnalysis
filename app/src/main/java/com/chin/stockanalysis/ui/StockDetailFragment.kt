package com.chin.stockanalysis.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
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
import com.chin.stockanalysis.agent.core.AgentOrchestrator
import com.chin.stockanalysis.agent.core.AnalysisMode
import com.chin.stockanalysis.agent.core.AnalysisResult
import com.chin.stockanalysis.agent.core.analyzeStock
import com.chin.stockanalysis.agent.stock.StockAnalysisAgent
import com.chin.stockanalysis.config.FeatureFlagManager
import com.chin.stockanalysis.strategy.analysis.CandlePatternDetector
import com.chin.stockanalysis.strategy.data.InstitutionalRatingProvider
import com.github.mikephil.charting.charts.CombinedChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.data.CandleData
import com.github.mikephil.charting.data.CandleDataSet
import com.github.mikephil.charting.data.CandleEntry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
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
        private const val TAG = "StockDetailFragment"
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
    private lateinit var contentScrollView: ScrollView
    private lateinit var loadingTv: TextView

    private var stockCode = ""
    private var stockName = ""
    private var initialPrice = 0.0
    private var initialChangePct = 0.0
    private var initialSector = ""
    private var autoExpandAi = true

    // K 線圖時間範圍狀態
    private var allKlineSnaps: List<DailySnapshotEntity> = emptyList()
    private var klineRangeDays = 90  // 默認顯示近3月
    private lateinit var klineContentContainer: LinearLayout  // K線專用容器，按鈕切換時只清空此容器
    private var klineTabIndex = 0  // 0=個股, 1=上證指數, 2=科創50, 3=創業板指
    private val klineTabLabels = arrayOf("個股", "上證", "科創", "創業")
    private val klineIndexCodes = arrayOf("", "sh000001", "sh000688", "sz399006")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            val rawCode = it.getString(ARG_STOCK_CODE, "")
            // 解析中文名稱 → 代碼（如 "兆易創新" → "603986"）
            val resolvedCode = com.chin.stockanalysis.ai.StockEntityExtractor.resolveSync(rawCode) ?: rawCode
            stockCode = StockAnalysisAgent.normalizeStockCode(resolvedCode)
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
        contentScrollView = ScrollView(requireContext()).apply {
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
        contentScrollView.addView(contentContainer)
        root.addView(contentScrollView)

        // 加載數據
        loadDetailData()
        loadMarketRisk()

        // 自動展開 AI 分析區（跳過簡單頁面，直接顯示 K 線+評級+分析按鈕）
        if (autoExpandAi) {
            aiExpanded = true
            root.findViewWithTag<TextView>("aiHeaderBtn")?.text = "🤖 AI分析 ▼"
            aiDetailScrollView.visibility = View.VISIBLE
            if (aiDetailContainer.childCount == 0) {
                loadKlineAndRatings()
            }
        }

        // 攔截返回鍵：AI 結果顯示時先收起，再按才退出
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (aiResultScrollView.visibility == View.VISIBLE) {
                    // AI 結果可見 → 收起結果，回到 K 線+評級
                    aiResultScrollView.visibility = View.GONE
                    contentScrollView.visibility = View.VISIBLE
                    aiDetailScrollView.visibility = View.VISIBLE
                } else if (aiExpanded) {
                    // AI 展開區可見 → 收起
                    aiExpanded = false
                    aiDetailScrollView.visibility = View.GONE
                    aiResultScrollView.visibility = View.GONE
                    root.findViewWithTag<TextView>("aiHeaderBtn")?.text = "🤖 AI分析 ▶"
                    // 恢復分析按鈕狀態
                    root.findViewWithTag<Button>("btnRunAi")?.let {
                        it.isEnabled = true
                        it.text = "🤖 V1.0深度分析"
                    }
                    root.findViewWithTag<Button>("btnRunV2")?.let {
                        it.isEnabled = true
                        it.text = "📊 V2.0全周期"
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
                aiExpanded = !aiExpanded
                if (aiExpanded) {
                    aiDetailScrollView.visibility = View.VISIBLE
                    this@apply.text = "🤖 AI分析 ▼"
                    if (aiDetailContainer.childCount == 0) {
                        loadKlineAndRatings()
                    }
                } else {
                    aiDetailScrollView.visibility = View.GONE
                    aiResultScrollView.visibility = View.GONE
                    this@apply.text = "🤖 AI分析 ▶"
                }
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
    private lateinit var aiDetailScrollView: ScrollView   // K線+機構評級的滾動容器
    private lateinit var aiResultContainer: LinearLayout   // AI Agent 分析結果
    private lateinit var aiResultScrollView: ScrollView     // AI 結果的滾動容器

    private fun buildAiAnalysisSection() {
        // 容器 1：K線走勢 + 機構評級（外層 ScrollView 確保可以滾動）
        aiDetailScrollView = ScrollView(requireContext()).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            visibility = View.GONE
            tag = "aiDetailScrollView"
            isVerticalScrollBarEnabled = true
        }
        aiDetailContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 0, 12, 8)
            tag = "aiDetailContainer"
        }
        aiDetailScrollView.addView(aiDetailContainer)
        root.addView(aiDetailScrollView)

        // 容器 2：AI Agent 分析結果（用戶點擊「運行AI分析」後顯示，替換容器1）
        // 外層 ScrollView 確保長文本可以滾動
        aiResultScrollView = ScrollView(requireContext()).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            visibility = View.GONE
            tag = "aiResultScrollView"
            isVerticalScrollBarEnabled = true
        }
        aiResultContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 0, 12, 8)
            tag = "aiResultContainer"
        }
        aiResultScrollView.addView(aiResultContainer)
        root.addView(aiResultScrollView)
    }

    private fun toggleAiAnalysis() {
        aiExpanded = !aiExpanded
        // 更新標題行 AI 按鈕的箭頭
        root.findViewWithTag<TextView>("aiHeaderBtn")?.text =
            if (aiExpanded) "🤖 AI分析 ▼" else "🤖 AI分析 ▶"

        if (aiExpanded) {
            // 展開：優先顯示 K線 + 機構評級
            aiResultScrollView.visibility = View.GONE
            aiDetailScrollView.visibility = View.VISIBLE
            if (aiDetailContainer.childCount == 0) {
                loadKlineAndRatings()
            }
        } else {
            aiDetailScrollView.visibility = View.GONE
            aiResultScrollView.visibility = View.GONE
        }
    }

    /** 構建真正的 K 線圖表（CombinedChart = K線 + 均線 + 坐標軸） */
    private fun buildCandleStickChart(snaps: List<DailySnapshotEntity>): CombinedChart {
        val chart = CombinedChart(requireContext())
        chart.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dpToPx(280))
        chart.setBackgroundColor(Color.WHITE)
        chart.description.isEnabled = false
        chart.legend.isEnabled = true
        chart.legend.textSize = 9f
        chart.legend.textColor = Color.parseColor("#666666")
        chart.setScaleEnabled(true)
        chart.setPinchZoom(true)       // 雙指縮放
        chart.setDragEnabled(true)     // 拖動平移
        chart.setDoubleTapToZoomEnabled(true)  // 雙擊縮放
        chart.setHighlightPerTapEnabled(true)  // 點擊高亮
        chart.setHighlightPerDragEnabled(true) // 拖動時持續顯示十字光標
        chart.setVisibleXRangeMaximum(250f)  // 最多可見 250 根（1年）
        chart.setVisibleXRangeMinimum(15f)   // 最少可見 15 根，防止過度放大
        chart.drawOrder = arrayOf(
            CombinedChart.DrawOrder.CANDLE,
            CombinedChart.DrawOrder.LINE
        )
        // 定位到最新數據（右側）
        if (snaps.size > 60) {
            chart.moveViewToX((snaps.size - 60).toFloat())
        } else {
            chart.moveViewToX(0f)
        }

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
        val candleDataSet = CandleDataSet(entries, "K線").apply {
            color = Color.parseColor("#333333")
            shadowColor = Color.parseColor("#999999")
            shadowWidth = 1f
            increasingPaintStyle = android.graphics.Paint.Style.FILL
            decreasingPaintStyle = android.graphics.Paint.Style.FILL
            increasingColor = Color.parseColor("#E53935")  // 漲紅
            decreasingColor = Color.parseColor("#43A047")  // 跌綠
            valueTextSize = 9f
            isHighlightEnabled = true
            setDrawValues(false)
            // 十字光標：虛線
            setDrawHighlightIndicators(true)
            setHighLightColor(Color.parseColor("#999999"))
            setHighlightLineWidth(1f)
            enableDashedHighlightLine(8f, 4f, 0f)  // 虛線：實線8px 間隙4px
        }

        // 均線計算
        val closes = snaps.map { it.close }
        val lineData = LineData()

        // MA5 均線
        val ma5Entries = calcMA(closes, 5, snaps)
        if (ma5Entries.isNotEmpty()) {
            lineData.addDataSet(LineDataSet(ma5Entries, "MA5").apply {
                color = Color.parseColor("#FF9800")
                lineWidth = 1.2f
                setDrawCircles(false)
                setDrawValues(false)
                isHighlightEnabled = false
            })
        }

        // MA10 均線
        val ma10Entries = calcMA(closes, 10, snaps)
        if (ma10Entries.isNotEmpty()) {
            lineData.addDataSet(LineDataSet(ma10Entries, "MA10").apply {
                color = Color.parseColor("#2196F3")
                lineWidth = 1.2f
                setDrawCircles(false)
                setDrawValues(false)
                isHighlightEnabled = false
            })
        }

        // MA20 均線
        val ma20Entries = calcMA(closes, 20, snaps)
        if (ma20Entries.isNotEmpty()) {
            lineData.addDataSet(LineDataSet(ma20Entries, "MA20").apply {
                color = Color.parseColor("#9C27B0")
                lineWidth = 1.2f
                setDrawCircles(false)
                setDrawValues(false)
                isHighlightEnabled = false
            })
        }

        // 組合數據：K線 + 均線
        val combinedData = com.github.mikephil.charting.data.CombinedData()
        combinedData.setData(CandleData(candleDataSet))
        combinedData.setData(lineData)
        chart.data = combinedData

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

        // ── 十字光標彈窗（點擊/拖動時顯示日期+開高低收） ──
        val marker = object : MarkerView(requireContext(), android.R.layout.simple_list_item_1) {
            private val tv: TextView = findViewById(android.R.id.text1)
            private val dp = resources.displayMetrics.density

            init {
                tv.apply {
                    textSize = 9f
                    setTextColor(Color.parseColor("#FFFFFF"))
                    setBackgroundColor(Color.parseColor("#CC333333"))
                    setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
                }
            }

            override fun refreshContent(e: Entry?, highlight: Highlight?) {
                if (e is CandleEntry) {
                    val idx = e.x.toInt()
                    val snap = if (idx in snaps.indices) snaps[idx] else null
                    val date = snap?.date?.takeLast(5) ?: ""
                    val change = if (snap != null && snap.open != 0.0) {
                        val pct = (snap.close - snap.open) / snap.open * 100
                        val sign = if (pct >= 0) "+" else ""
                        " $sign${"%.2f".format(pct)}%"
                    } else ""
                    tv.text = "$date\n開${"%.2f".format(e.open)} 高${"%.2f".format(e.high)}\n低${"%.2f".format(e.low)} 收${"%.2f".format(e.close)}$change"
                } else if (e != null) {
                    tv.text = "%.2f".format(e.y)
                }
                super.refreshContent(e, highlight)
            }

            override fun getOffset(): MPPointF {
                // 彈窗顯示在觸摸點上方居中
                return MPPointF.getInstance(-(width / 2f), -(height + 6 * dp))
            }

            override fun getOffsetForDrawingAtPoint(posX: Float, posY: Float): MPPointF {
                val offset = getOffset()
                // 如果彈窗超出右邊界，向左偏移
                val adjustedX = when {
                    posX + offset.x + width > chart.width -> -(posX + width - chart.width + 4 * dp)
                    posX + offset.x < 0 -> -posX + 4 * dp
                    else -> offset.x
                }
                // 如果彈窗超出頂部，改為顯示在下方
                val adjustedY = if (posY + offset.y < 0) 6 * dp else offset.y
                return MPPointF.getInstance(adjustedX, adjustedY)
            }
        }
        chart.marker = marker

        chart.invalidate()
        return chart
    }

    /**
     * 渲染 K 線圖區塊（標題 + 時間範圍按鈕 + 圖表 + MA 簡評）
     * 根據 allKlineSnaps 和 klineRangeDays 動態裁剪數據並重建圖表
     */
    private fun renderKlineChart() {
        if (allKlineSnaps.isEmpty()) return
        // 清空 K 線專用容器（不影響後續評級內容）
        klineContentContainer.removeAllViews()

        // ── 按選擇的時間範圍裁剪數據 ──
        val displaySnaps = if (klineRangeDays <= 0) {
            allKlineSnaps  // 全部
        } else {
            allKlineSnaps.takeLast(klineRangeDays)
        }

        if (displaySnaps.size < 2) {
            klineContentContainer.addView(TextView(requireContext()).apply {
                text = "所選範圍數據不足"
                textSize = 11f; setTextColor(Color.parseColor("#999999"))
                setPadding(0, 0, 0, 8)
            })
            return
        }

        // ── Tab 切換行（個股/上證/科創/創業） ──
        val tabRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 4)
        }
        for (i in klineTabLabels.indices) {
            val isActive = klineTabIndex == i
            val tabBtn = TextView(requireContext()).apply {
                text = klineTabLabels[i]
                textSize = 10f
                setTextColor(if (isActive) Color.WHITE else Color.parseColor("#666666"))
                setBackgroundColor(if (isActive) Color.parseColor("#6200EA") else Color.parseColor("#EEEEEE"))
                setPadding(16, 4, 16, 4)
                layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = 4
                }
                setOnClickListener {
                    if (klineTabIndex == i) return@setOnClickListener
                    klineTabIndex = i
                    klineRangeDays = 90  // 切換 Tab 時重置為默認範圍
                    if (i == 0) {
                        // 個股：重新從數據庫加載
                        switchToStockKline()
                    } else {
                        // 指數：異步加載
                        switchToIndexKline(i)
                    }
                }
            }
            tabRow.addView(tabBtn)
        }
        klineContentContainer.addView(tabRow)

        // ── 標題行 ──
        val rangeLabel = when (klineRangeDays) {
            30 -> "近1月"
            90 -> "近3月"
            120 -> "近6月"
            250 -> "近1年"
            0 -> "全部(${allKlineSnaps.size}天)"
            else -> "近${klineRangeDays}日"
        }
        val tabTitle = if (klineTabIndex == 0) "$stockName 日K線" else "${klineTabLabels[klineTabIndex]}指數 日K線"
        klineContentContainer.addView(TextView(requireContext()).apply {
            text = "## $tabTitle（$rangeLabel · ${displaySnaps.size}根）"
            textSize = 12f; setTextColor(Color.parseColor("#333333"))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 4)
        })

        // ── 時間範圍選擇按鈕行 ──
        val rangeRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 2, 0, 4)
        }
        data class RangeBtn(val label: String, val days: Int)
        val rangeBtns = listOf(
            RangeBtn("1月", 30),
            RangeBtn("3月", 90),
            RangeBtn("6月", 120),
            RangeBtn("1年", 250),
            RangeBtn("全部", 0)
        )
        for (rb in rangeBtns) {
            val isActive = klineRangeDays == rb.days
            val btn = TextView(requireContext()).apply {
                text = rb.label
                textSize = 10f
                setTextColor(if (isActive) Color.WHITE else Color.parseColor("#666666"))
                setBackgroundColor(if (isActive) Color.parseColor("#1976D2") else Color.parseColor("#EEEEEE"))
                setPadding(12, 4, 12, 4)
                layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = 4
                }
                setOnClickListener {
                    klineRangeDays = rb.days
                    renderKlineChart()
                }
            }
            rangeRow.addView(btn)
        }
        rangeRow.addView(TextView(requireContext()).apply {
            text = "  ← 雙指縮放/拖動"
            textSize = 8f; setTextColor(Color.parseColor("#AAAAAA"))
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        })
        // 趨勢圖譜按鈕
        rangeRow.addView(TextView(requireContext()).apply {
            text = " 📐圖譜"
            textSize = 10f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#7B1FA2"))
            setPadding(12, 4, 12, 4)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = 8
            }
            setOnClickListener { showTrendChartReference() }
        })
        klineContentContainer.addView(rangeRow)

        // ── K 線圖表 ──
        val chart = buildCandleStickChart(displaySnaps)
        klineContentContainer.addView(chart)

        // ── 綜合趨勢分析（自動識別，用戶無需自行判斷） ──
        val trendText = buildTrendAnalysis(displaySnaps)
        klineContentContainer.addView(TextView(requireContext()).apply {
            text = trendText
            textSize = 10f; setTextColor(Color.parseColor("#666666"))
            setLineSpacing(2f, 1f)
            setPadding(0, 4, 0, 8)
        })

        // 確保容器重新佈局
        klineContentContainer.requestLayout()
        klineContentContainer.invalidate()
    }

    /** 切換回個股 K 線 */
    private fun switchToStockKline() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                allKlineSnaps = db.dailySnapshotDao().getByCode(stockCode, 500).sortedBy { it.date }
                withContext(Dispatchers.Main) { renderKlineChart() }
            } catch (e: Exception) {
                Log.w(TAG, "切換個股K線失敗: ${e.message}")
            }
        }
    }

    /** 切換到指數 K 線（異步加載） */
    private fun switchToIndexKline(tabIndex: Int) {
        val indexCode = klineIndexCodes[tabIndex]
        klineContentContainer.removeAllViews()
        klineContentContainer.addView(TextView(requireContext()).apply {
            text = "⏳ 正在加載${klineTabLabels[tabIndex]}指數 K 線..."
            textSize = 10f; setTextColor(Color.parseColor("#999999"))
            setPadding(0, 8, 0, 8)
        })
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                var snaps = db.dailySnapshotDao().getByCode(indexCode, 500).sortedBy { it.date }
                // 如果本地數據不足，從網絡補充
                if (snaps.size < 30) {
                    try {
                        val fetcher = com.chin.stockanalysis.strategy.data.HistoricalDataFetcher(requireContext())
                        val endDate = java.time.LocalDate.now()
                        val startDate = endDate.minusDays(800)
                        val (fetched, _) = fetcher.fetchOneStock(indexCode, startDate, endDate)
                        if (fetched.isNotEmpty()) {
                            db.dailySnapshotDao().insertAll(fetched)
                            snaps = db.dailySnapshotDao().getByCode(indexCode, 500).sortedBy { it.date }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "網絡補充指數K線失敗: ${e.message}")
                    }
                }
                allKlineSnaps = snaps
                withContext(Dispatchers.Main) {
                    if (snaps.size >= 5) {
                        renderKlineChart()
                    } else {
                        klineContentContainer.removeAllViews()
                        klineContentContainer.addView(TextView(requireContext()).apply {
                            text = "${klineTabLabels[tabIndex]}指數數據不足（當前${snaps.size}天）"
                            textSize = 11f; setTextColor(Color.parseColor("#999999"))
                            setPadding(0, 8, 0, 8)
                        })
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    klineContentContainer.removeAllViews()
                    klineContentContainer.addView(TextView(requireContext()).apply {
                        text = "加載失敗: ${e.message}"
                        textSize = 11f; setTextColor(Color.parseColor("#C62828"))
                        setPadding(0, 8, 0, 8)
                    })
                }
            }
        }
    }

    /** 顯示 K 線趨勢參考圖譜（WebView 加載 assets/trend_charts/index.html） */
    private fun showTrendChartReference() {
        val dialog = android.app.Dialog(requireContext())
        dialog.setTitle("K 線趨勢參考圖譜")
        val webView = android.webkit.WebView(requireContext())
        dialog.setContentView(webView)
        dialog.window?.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT
        )
        webView.settings.javaScriptEnabled = true
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false
        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true
        try {
            val html = requireContext().assets.open("trend_charts/index.html").bufferedReader().use { it.readText() }
            webView.loadDataWithBaseURL("file:///android_asset/trend_charts/", html, "text/html", "UTF-8", null)
        } catch (e: Exception) {
            webView.loadData("<html><body><h3>趨勢圖譜加載失敗: ${e.message}</h3></body></html>", "text/html", "UTF-8")
        }
        dialog.show()
    }

    /**
     * 綜合趨勢分析 — 自動識別趨勢類型，用戶無需自行判斷
     * 包含：均線排列、三天不新低、均線粘合向上、綜合趨勢類型
     */
    private fun buildTrendAnalysis(snaps: List<DailySnapshotEntity>): String {
        val sb = StringBuilder()
        val closes = snaps.map { it.close }
        val lows = snaps.map { it.low }
        val highs = snaps.map { it.high }
        val currentPrice = closes.last()

        // ── MA 計算 ──
        val ma5 = if (closes.size >= 5) closes.takeLast(5).average() else currentPrice
        val ma10 = if (closes.size >= 10) closes.takeLast(10).average() else null
        val ma20 = if (closes.size >= 20) closes.takeLast(20).average() else null

        sb.append("MA5: ${"%.2f".format(ma5)}")
        if (ma10 != null) sb.append(" | MA10: ${"%.2f".format(ma10)}")
        if (ma20 != null) sb.append(" | MA20: ${"%.2f".format(ma20)}")
        sb.append("\n現價 ${"%.2f".format(currentPrice)}")

        // ── 1. 均線排列趨勢 ──
        val maTrend = if (ma20 != null && ma10 != null) {
            when {
                currentPrice > ma5 && ma5 > ma10 && ma10 > ma20 -> "多頭排列 ↑↑"
                currentPrice < ma5 && ma5 < ma10 && ma10 < ma20 -> "空頭排列 ↓↓"
                currentPrice > ma20 -> "中期偏多 ↑"
                else -> "中期偏空 ↓"
            }
        } else if (currentPrice > ma5) "短期偏多" else "短期偏空"
        sb.append(" → $maTrend")

        // ── 2. 三天不新低 ──
        // 最近3天的最低價都高於前10天的最低價 = 止跌企穩信號
        val threeDayNoNewLow = if (lows.size >= 13) {
            val recent3Lows = lows.takeLast(3)
            val prevMinLow = lows.subList(lows.size - 13, lows.size - 3).minOrNull() ?: Double.MAX_VALUE
            recent3Lows.all { it > prevMinLow }
        } else false
        sb.append("\n三天不新低: ${if (threeDayNoNewLow) "✓ 是（止跌企穩）" else "✗ 否"}")

        // ── 3. 均線粘合向上 ──
        // 三條均線差距 < 2% 且 MA5 向上 = 蓄勢突破信號
        val maConvergingUp = if (ma10 != null && ma20 != null && closes.size >= 6) {
            val maMax = maxOf(ma5, ma10, ma20)
            val maMin = minOf(ma5, ma10, ma20)
            val convergePct = (maMax - maMin) / maMin * 100
            val isConverging = convergePct < 2.0
            val ma5Yesterday = closes.subList(closes.size - 6, closes.size - 1).average()
            val isUpward = ma5 > ma5Yesterday
            isConverging && isUpward
        } else false
        val convergePctStr = if (ma10 != null && ma20 != null) {
            val maMax = maxOf(ma5, ma10, ma20)
            val maMin = minOf(ma5, ma10, ma20)
            "%.2f%%".format((maMax - maMin) / maMin * 100)
        } else "N/A"
        sb.append(" | 均線粘合向上: ${if (maConvergingUp) "✓ 是（蓄勢突破）" else "✗ 否"} (偏離$convergePctStr)")

        // ── 4. 近期高低點突破判斷 ──
        val recentHighBreak = if (highs.size >= 20) {
            val prev20High = highs.subList(0, highs.size - 1).takeLast(20).maxOrNull() ?: 0.0
            currentPrice > prev20High
        } else false
        val recentLowBreak = if (lows.size >= 20) {
            val prev20Low = lows.subList(0, lows.size - 1).takeLast(20).minOrNull() ?: Double.MAX_VALUE
            currentPrice < prev20Low
        } else false

        // ── 5. 綜合趨勢類型（自動判斷，用戶無需自行分析圖形） ──
        val trendType = when {
            maTrend.contains("多頭排列") && threeDayNoNewLow -> "上升趨勢（強勢）"
            maTrend.contains("多頭排列") -> "上升趨勢"
            maConvergingUp && threeDayNoNewLow -> "底部企穩 + 蓄勢突破（關注）"
            maConvergingUp -> "蓄勢待發（均線粘合）"
            maTrend.contains("空頭排列") && recentLowBreak -> "下跌趨勢（破位）"
            maTrend.contains("空頭排列") -> "下跌趨勢"
            threeDayNoNewLow && maTrend.contains("偏多") -> "震盪偏多（企穩）"
            threeDayNoNewLow -> "底部企穩（待確認）"
            maTrend.contains("偏多") -> "震盪偏多"
            maTrend.contains("偏空") -> "震盪偏空"
            recentHighBreak -> "突破上行（關注）"
            else -> "震盪整理"
        }
        val trendColor = when {
            trendType.contains("上升") || trendType.contains("突破") -> "🔴"
            trendType.contains("下跌") || trendType.contains("破位") -> "🟢"
            trendType.contains("企穩") || trendType.contains("蓄勢") -> "🟡"
            else -> "⚪"
        }
        sb.append("\n趨勢類型: $trendColor $trendType")

        // ── 6. 操作建議 ──
        val advice = when {
            trendType.contains("上升趨勢（強勢）") -> "持倉為主，回調可加倉"
            trendType.contains("上升趨勢") -> "順勢持倉，注意止盈"
            trendType.contains("蓄勢突破") || trendType.contains("突破上行") -> "關注突破方向，放量可跟進"
            trendType.contains("底部企穩") -> "觀察確認，輕倉試探"
            trendType.contains("蓄勢待發") -> "等待方向選擇"
            trendType.contains("下跌趨勢（破位）") -> "及時止損，空倉觀望"
            trendType.contains("下跌趨勢") -> "觀望為主，不搶反彈"
            trendType.contains("震盪偏多") -> "輕倉做多，設好止損"
            trendType.contains("震盪偏空") -> "謹慎操作，注意風險"
            else -> "觀望等待方向"
        }
        sb.append("\n建議: $advice")

        // ── 7. K 線經典形態識別（自動匹配，對照趨勢參考圖） ──
        val patterns = CandlePatternDetector.detect(snaps)
        if (patterns.isNotEmpty()) {
            sb.append("\n\n📋 K線形態:")
            for (p in patterns) {
                val stars = "★".repeat(p.strength) + "☆".repeat(5 - p.strength)
                val emoji = if (p.direction == CandlePatternDetector.Direction.BULLISH) "🔴" else "🟢"
                sb.append("\n  $emoji ${p.patternName} $stars (${p.direction.label})")
                sb.append("\n     ${p.description}")
            }
            sb.append("\n  💡 參考 docs/trend_charts/index.html 查看形態圖譜")
        } else {
            sb.append("\n\n📋 K線形態: 無明顯經典形態")
        }

        return sb.toString()
    }

    /** 計算移動平均線 Entry 列表 */
    private fun calcMA(
        closes: List<Double>,
        period: Int,
        snaps: List<DailySnapshotEntity>
    ): List<Entry> {
        if (closes.size < period) return emptyList()
        val result = mutableListOf<Entry>()
        for (i in (period - 1) until closes.size) {
            val ma = closes.subList(i - period + 1, i + 1).average()
            result.add(Entry(i.toFloat(), ma.toFloat()))
        }
        return result
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()

    /** 加載 K 線走勢 + 機構評級數據 */
    private fun loadKlineAndRatings() {
        aiDetailContainer.removeAllViews()
        // 加載提示
        aiDetailContainer.addView(TextView(requireContext()).apply {
            text = "⏳ 正在加載 K 線走勢..."
            textSize = 10f; setTextColor(Color.parseColor("#999999"))
        })

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val sb = StringBuilder()

                // ── 1. K 線走勢（用 CandleStickChart 真正的圖表） ──
                // 一次性拉取 500 天歷史數據，按時間範圍按鈕動態裁剪
                allKlineSnaps = db.dailySnapshotDao().getByCode(stockCode, 500).sortedBy { it.date }

                // 如果本地數據不足 30 天，嘗試從網絡補充日K數據
                if (allKlineSnaps.size < 30) {
                    try {
                        val fetcher = com.chin.stockanalysis.strategy.data.HistoricalDataFetcher(requireContext())
                        val endDate = java.time.LocalDate.now()
                        val startDate = endDate.minusDays(800) // 約 2 年多
                        val (fetched, _) = fetcher.fetchOneStock(stockCode, startDate, endDate)
                        if (fetched.isNotEmpty()) {
                            // 寫入數據庫
                            db.dailySnapshotDao().insertAll(fetched)
                            // 重新讀取
                            allKlineSnaps = db.dailySnapshotDao().getByCode(stockCode, 500).sortedBy { it.date }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "網絡補充K線數據失敗: ${e.message}")
                    }
                }
                withContext(Dispatchers.Main) { aiDetailContainer.removeAllViews() }

                if (allKlineSnaps.size >= 5) {
                    withContext(Dispatchers.Main) {
                        // 創建 K 線專用容器，按鈕切換時只清空此容器，不影響後續評級內容
                        klineContentContainer = LinearLayout(requireContext()).apply {
                            orientation = LinearLayout.VERTICAL
                        }
                        aiDetailContainer.addView(klineContentContainer)
                        renderKlineChart()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        aiDetailContainer.addView(TextView(requireContext()).apply {
                            text = "K線數據不足（需要至少5天，當前${allKlineSnaps.size}天）"
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
                    sb.appendLine("⚠️ 暫無機構評級覆蓋（該股票近90天無研究報告）")
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
                        val upside = if (ratingSummary.avgTargetPrice > 0 && allKlineSnaps.isNotEmpty()) {
                            val last = allKlineSnaps.last().close
                            (ratingSummary.avgTargetPrice - last) / last * 100
                        } else 0.0
                        sb.appendLine("平均目標價: ¥${"%.2f".format(ratingSummary.avgTargetPrice)}（空間${if (upside >= 0) "↑" else "↓"}${"%.1f".format(kotlin.math.abs(upside))}%）")
                    }

                    if (ratingSummary.latestOrgs.isNotEmpty()) {
                        sb.appendLine("最近評級機構: ${ratingSummary.latestOrgs.take(3).joinToString("、")}")
                    }

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
                    sb.appendLine("⚠️ 暫無基金持倉數據（數據源維護中）")
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
                        text = "🤖 V1.0深度分析"
                        textSize = 9f; setTextColor(Color.WHITE)
                        setBackgroundColor(Color.parseColor("#2E7D32"))
                        setPadding(12, 4, 12, 4)
                        minimumHeight = 0; minHeight = 0
                        setOnClickListener {
                            it.isEnabled = false
                            text = "V1.0分析中..."
                            runAiAgents(AnalysisMode.DEEP)
                        }
                    })
                    btnRow.addView(Button(requireContext()).apply {
                        tag = "btnRunV2"
                        text = "📊 V2.0全周期"
                        textSize = 9f; setTextColor(Color.WHITE)
                        setBackgroundColor(Color.parseColor("#E65100"))
                        setPadding(12, 4, 12, 4)
                        minimumHeight = 0; minHeight = 0
                        setOnClickListener {
                            it.isEnabled = false
                            text = "V2.0分析中..."
                            runAiAgents(AnalysisMode.EXPERT)
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

    /**
     * 統一分析入口（AgentOrchestrator.analyzeStock）。
     *
     * flag 控制編排深度：
     * - stockAnalysisRoute = AGENT_FRAMEWORK → 完整角色編排（Scout+Analyst+Guardian 並行）
     * - 否則 → 輕量直連（DeepAnalystEngine + 決策矩陣）
     */
    private suspend fun runAnalysis(appCtx: Context, mode: AnalysisMode): AnalysisResult {
        val useAgent = FeatureFlagManager.isAgentFramework(FeatureFlagManager.stockAnalysisRoute)
        return AgentOrchestrator(appCtx).analyzeStock(
            stockCode = stockCode,
            stockName = stockName,
            mode = mode,
            useAgentFramework = useAgent
        )
    }

    /** 運行 AI Agent 分析，切換到 aiResultContainer */
    private fun runAiAgents(mode: AnalysisMode = AnalysisMode.DEEP) {
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
            text = when (mode) {
                AnalysisMode.DEEP -> "V1.0深度分析中（需要30-60秒）..."
                AnalysisMode.EXPERT -> "V2.0全周期分析中（市場環境+利潤質量+決策矩陣）..."
                else -> "AI 分析中..."
            }
            textSize = 9f; setTextColor(Color.parseColor("#999999"))
        })
        aiResultContainer.addView(loadingRow)
        // 切換 view：隱藏主內容，顯示AI結果
        aiDetailScrollView.visibility = View.GONE
        contentScrollView.visibility = View.GONE
        aiResultScrollView.visibility = View.VISIBLE

        aiAnalysisJob = lifecycleScope.launch(Dispatchers.IO) {
            val appCtx = requireContext().applicationContext
            // 統一入口：AgentOrchestrator.analyzeStock（flag 控制完整編排 / 輕量直連）
            val result = runAnalysis(appCtx, mode)

            // 檢查是否被取消或 view 已銷毀
            ensureActive()
            if (view == null || !isAdded) return@launch

            withContext(Dispatchers.Main) {
                aiResultContainer.removeAllViews()
                // 清理 summaryText 中可能殘留的 JSON 碎片
                val cleanedSummary = cleanJsonArtifacts(result.summaryText)
                aiResultContainer.addView(TextView(requireContext()).apply {
                    text = cleanedSummary
                    textSize = if (result.mode == AnalysisMode.DEEP) 9f else 10f
                    setTextColor(Color.parseColor("#1B5E20"))
                    setLineSpacing(2f, 1f)
                    setPadding(0, 0, 0, 2)
                })
                // 模式標籤
                if (result.mode != AnalysisMode.QUICK) {
                    aiResultContainer.addView(TextView(requireContext()).apply {
                        text = "🧠 ${result.elapsedMs}ms · ${if (result.mode == AnalysisMode.EXPERT) "全周期" else "深度"}分析"
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
                        aiResultScrollView.visibility = View.GONE
                        contentScrollView.visibility = View.VISIBLE
                        aiDetailScrollView.visibility = View.VISIBLE
                        // 恢復分析按鈕
                        root.findViewWithTag<Button>("btnRunAi")?.let {
                            it.isEnabled = true
                            it.text = "🤖 V1.0深度分析"
                        }
                        root.findViewWithTag<Button>("btnRunV2")?.let {
                            it.isEnabled = true
                            it.text = "📊 V2.0全周期"
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

                // 同步更新 AI 綜合分析區塊
                root.findViewWithTag<TextView>("aiQuickResult")?.let { tv ->
                    tv.visibility = View.VISIBLE
                    tv.text = cleanedSummary
                }
                root.findViewWithTag<TextView>("aiQuickLoading")?.visibility = View.GONE
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

    /** AI 綜合分析区域 — 自動調用 DeepSeek 快速分析 */
    private fun buildAIAnalysisSection(
        leaderData: EastMoneyHotSectorSource.LeaderStock?,
        sectors: List<String>,
        subSector: String,
        newsFactors: List<NewsFactorEntity>
    ) {
        val card = createSectionCard()
        card.addView(createSectionTitle("🤖 AI 綜合分析"))

        val ctx = requireContext()
        
        // 先顯示加載提示
        val loadingLabel = TextView(ctx).apply {
            text = "⏳ 正在載入 AI 分析..."
            textSize = 12f; setTextColor(Color.parseColor("#888888"))
            setPadding(4, 4, 4, 8)
            tag = "aiQuickLoading"
        }
        card.addView(loadingLabel)

        // 結果容器
        val resultLabel = TextView(ctx).apply {
            textSize = 11f; setTextColor(Color.parseColor("#333333"))
            setLineSpacing(4f, 1.2f)
            setPadding(4, 6, 4, 8)
            visibility = View.GONE
            tag = "aiQuickResult"
        }
        card.addView(resultLabel)

        contentContainer.addView(card)

        // 自動觸發 DeepSeek 快速分析
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val agent = com.chin.stockanalysis.agent.stock.StockAnalysisAgent(requireContext().applicationContext)
                val result = agent.analyze(stockCode, stockName)
                
                withContext(Dispatchers.Main) {
                    loadingLabel.visibility = View.GONE
                    resultLabel.visibility = View.VISIBLE
                    // 格式化顯示分析結果
                    val displayText = buildString {
                        if (result.success) {
                            if (result.overallScore > 0) {
                                appendLine("📊 綜合評分: ${result.overallScore}/100")
                                appendLine("建議: ${result.recommendation} | 置信度: ${result.confidence}")
                                appendLine()
                                // 各維度結構化結論
                                appendLine("📈 技術面: ${result.technicalScore}/100 — ${scoreVerdict(result.technicalScore)}")
                                appendLine("💼 基本面: ${result.fundamentalScore}/100 — ${scoreVerdict(result.fundamentalScore)}")
                                appendLine("💰 資金面: ${result.fundFlowScore}/100 — ${scoreVerdict(result.fundFlowScore)}")
                                appendLine()
                            }
                            if (result.targetPrice.isNotBlank()) appendLine("🎯 目標價: ${result.targetPrice}")
                            if (result.stopLoss.isNotBlank()) appendLine("🛑 止損位: ${result.stopLoss}")
                            if (result.riskFactors.isNotEmpty()) {
                                appendLine()
                                appendLine("⚠️ 風險因素:")
                                result.riskFactors.forEach { appendLine("  • $it") }
                            }
                            if (result.reasoning.isNotBlank()) {
                                appendLine()
                                // 清理 reasoning 中可能殘留的 JSON 碎片
                                val cleanedReasoning = cleanJsonArtifacts(result.reasoning)
                                appendLine(cleanedReasoning)
                            }
                        } else {
                            append("⚠️ AI 分析載入失敗: ${cleanJsonArtifacts(result.rawOutput)}")
                        }
                    }
                    resultLabel.text = displayText
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loadingLabel.text = "⚠️ AI 分析載入失敗: ${e.message}"
                }
            }
        }
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

    /** 根據評分給出一句話結論 */
    private fun scoreVerdict(score: Int): String = when {
        score >= 70 -> "偏多，表現較好"
        score >= 50 -> "中性，觀望為主"
        score >= 30 -> "偏弱，注意風險"
        else -> "較差，謹慎操作"
    }

    /** 清理文本中殘留的 JSON 碎片（花括號、鍵值對等），保留可讀分析文字 */
    private fun cleanJsonArtifacts(text: String): String {
        return text
            .replace(Regex("```json[\\s\\S]*?```"), "")
            .replace(Regex("\\{\\s*\"[^\"]*\"\\s*:[^}]*\\}"), "")
            .lines()
            .filter { line ->
                val trimmed = line.trim()
                trimmed.isNotBlank() &&
                    !trimmed.matches(Regex("^[{}\\[\\],:]\\s*$")) &&
                    !trimmed.startsWith("\"") &&
                    !trimmed.matches(Regex("^\\d+\\s*[,:]?$"))
            }
            .joinToString("\n")
            .trim()
    }
}