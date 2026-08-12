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
import com.chin.stockanalysis.strategy.analysis.MaConvergenceAnalyzer
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

    // K 线图时间范围状态
    private var allKlineSnaps: List<DailySnapshotEntity> = emptyList()
    private var klineRangeDays = 90  // 默认显示近3月
    private lateinit var klineContentContainer: LinearLayout  // K线专用容器，按钮切换时只清空此容器
    private var klineTabIndex = 0  // 0=个股, 1=上证指数, 2=科创50, 3=创业板指
    private val klineTabLabels = arrayOf("个股", "上证", "科创", "创业")
    private val klineIndexCodes = arrayOf("", "sh000001", "sh000688", "sz399006")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            val rawCode = it.getString(ARG_STOCK_CODE, "")
            // 解析中文名称 → 代码（如 "兆易创新" → "603986"）
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

        // 加载数据
        loadDetailData()
        loadMarketRisk()

        // 自动展开 AI 分析区（跳过简单页面，直接显示 K 线+评级+分析按钮）
        if (autoExpandAi) {
            aiExpanded = true
            root.findViewWithTag<TextView>("aiHeaderBtn")?.text = "🤖 AI分析 ⌯"
            aiDetailScrollView.visibility = View.VISIBLE
            if (aiDetailContainer.childCount == 0) {
                loadKlineAndRatings()
            }
        }

        // 拦截返回键：AI 结果显示时先收起，再按才退出
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (aiResultScrollView.visibility == View.VISIBLE) {
                    // AI 结果可见 → 收起结果，回到 K 线+评级
                    aiResultScrollView.visibility = View.GONE
                    contentScrollView.visibility = View.VISIBLE
                    aiDetailScrollView.visibility = View.VISIBLE
                } else if (aiExpanded) {
                    // AI 展开区可见 → 收起
                    aiExpanded = false
                    aiDetailScrollView.visibility = View.GONE
                    aiResultScrollView.visibility = View.GONE
                    root.findViewWithTag<TextView>("aiHeaderBtn")?.text = "🤖 AI分析 ⌵"
                    // 恢复分析按钮状态
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
                    aiAnalysisJob?.cancel()  // 取消正在进行的分析
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

        // 标题行：名称 + 价格(小字靠右) + AI 按钮
        val titleRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // 左侧：名称（左） + 价格（名称右边，小字）
        val namePriceCol = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        // 股票名称
        namePriceCol.addView(TextView(requireContext()).apply {
            text = stockName
            textSize = 20f; setTextColor(Color.parseColor("#1A1A2E"))
            setTypeface(null, Typeface.BOLD)
        })
        // 价格（名称右边，小字）
        namePriceCol.addView(TextView(requireContext()).apply {
            tag = "tvPrice"
            text = if (initialPrice > 0) " ¥${String.format("%.2f", initialPrice)}" else ""
            textSize = 12f; setTextColor(Color.parseColor("#E53935"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 0, 0, 0)
        })
        // 涨跌幅（更小字）
        namePriceCol.addView(TextView(requireContext()).apply {
            tag = "tvChangePct"
            val sign = if (initialChangePct >= 0) "+" else ""
            text = if (initialChangePct != 0.0) " $sign${String.format("%.2f", initialChangePct)}%" else ""
            textSize = 10f; setTypeface(null, Typeface.BOLD)
            setTextColor(if (initialChangePct >= 0) Color.parseColor("#E53935") else Color.parseColor("#43A047"))
            gravity = Gravity.CENTER_VERTICAL
        })
        titleRow.addView(namePriceCol)

        // 右侧：AI 分析按钮
        val aiBtn = TextView(requireContext()).apply {
            tag = "aiHeaderBtn"
            text = "🤖 AI分析 ⌵"
            textSize = 11f
            setTextColor(Color.parseColor("#FFFFFF"))
            setBackgroundColor(Color.parseColor("#2E7D32"))
            setPadding(12, 6, 12, 6)
            gravity = Gravity.CENTER
            setOnClickListener {
                aiExpanded = !aiExpanded
                if (aiExpanded) {
                    aiDetailScrollView.visibility = View.VISIBLE
                    this@apply.text = "🤖 AI分析 ⌯"
                    if (aiDetailContainer.childCount == 0) {
                        loadKlineAndRatings()
                    }
                } else {
                    aiDetailScrollView.visibility = View.GONE
                    aiResultScrollView.visibility = View.GONE
                    this@apply.text = "🤖 AI分析 ⌵"
                }
                if (::aiDetailContainer.isInitialized) {
                    root.post { root.scrollTo(0, aiDetailContainer.top) }
                }
            }
        }
        titleRow.addView(aiBtn)
        headerCard.addView(titleRow)

        // 代码 + 板块标签行
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
        // 板块标签
        if (initialSector.isNotEmpty()) {
            codeSectorRow.addView(createTagChip(initialSector, "#1565C0"))
        }
        headerCard.addView(codeSectorRow)

        root.addView(headerCard)
    }

    /**
     * 将交易动作（加仓/减仓/清仓）路由到 AI 对话闭环：
     * 若宿主为 MainActivity 则切换对话 Tab 并发送指令，否则提示用户。
     */
    private fun routeTradeAction(action: String) {
        val activity = activity
        if (activity is MainActivity) {
            val prompt = when (action) {
                "加仓" -> "帮我分析 $stockName（$stockCode）现在是否适合加仓，并给出加仓建议"
                "减仓" -> "帮我分析 $stockName（$stockCode）现在是否适合减仓，并给出减仓建议"
                else   -> "帮我分析 $stockName（$stockCode）现在是否适合清仓离场"
            }
            Toast.makeText(requireContext(), "${action}指令已发送给 AI", Toast.LENGTH_SHORT).show()
            activity.switchToChatAndSend(prompt)
        } else {
            Toast.makeText(
                requireContext(),
                "请在策略页使用「建仓/持仓」功能对 $stockName 执行$action",
                Toast.LENGTH_LONG
            ).show()
        }
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

    /** 大盘风险提示条（初始隐藏，加载数据后显示） */
    private fun buildMarketRiskBar() {
        val riskBar = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 4, 12, 4)
            setBackgroundColor(Color.parseColor("#FFEBEE"))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            visibility = View.GONE  // 初始隐藏
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

    /** 加载大盘风险状态（异步，不阻塞 UI） */
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
                            append("⚠️ 大盘下行（强度${trend.strength}/100）")
                            if (sellType.sellType == "INSTITUTIONAL_EXIT") append(" | 主力撤资中")
                            else if (sellType.sellType == "QUANT_CRASH") append(" | 量化砸盘")
                            append(" — 建议降低仓位，关注防御板块")
                        }
                        "OSCILLATION" -> {
                            if (trend.strength > 40) append("⚡ 大盘震荡加剧（强度${trend.strength}） — 控制仓位")
                            else append("📊 大盘震荡（强度${trend.strength}） — 轻仓操作")
                        }
                        else -> return@launch // BULLISH 不显示风险条
                    }
                }

                withContext(Dispatchers.Main) {
                    val riskBar = root.findViewWithTag<LinearLayout>("marketRiskBar")
                    val riskTv = root.findViewWithTag<TextView>("marketRiskTv")
                    if (riskBar != null && riskTv != null) {
                        riskTv.text = msg
                        riskBar.visibility = View.VISIBLE
                        // 根据风险等级设置背景色
                        riskBar.setBackgroundColor(when (trend.direction) {
                            "BEARISH" -> Color.parseColor("#FFCDD2")
                            else -> Color.parseColor("#FFF3E0")
                        })
                    }
                }
            } catch (_: Exception) {
                // 大盘风险加载失败不影响页面
            }
        }
    }

    /** 动态更新 header 中的板块标签（loadDetailData 获取板块后回调） */
    private fun updateHeaderSectorTags(sectors: List<String>, subSector: String) {
        val codeSectorRow = root.findViewWithTag<LinearLayout>("codeSectorRow")
            ?: return
        // 移除旧板块标签（保留第一个 TextView 即股票代码）
        while (codeSectorRow.childCount > 1) {
            codeSectorRow.removeViewAt(codeSectorRow.childCount - 1)
        }
        // 添加新板块标签
        if (subSector.isNotEmpty() && subSector != "-") {
            codeSectorRow.addView(createTagChip(subSector, "#E65100"))
        }
        for (sector in sectors.take(2)) {
            if (sector != subSector) {
                codeSectorRow.addView(createTagChip(sector, "#1565C0"))
            }
        }
    }

    // ── AI 分析展开区域 ──
    private var aiExpanded = false
    private var aiContentAdded = false
    private lateinit var aiDetailContainer: LinearLayout  // K线+机构评级
    private lateinit var aiDetailScrollView: ScrollView   // K线+机构评级的滚动容器
    private lateinit var aiResultContainer: LinearLayout   // AI Agent 分析结果
    private lateinit var aiResultScrollView: ScrollView     // AI 结果的滚动容器

    private fun buildAiAnalysisSection() {
        // 容器 1：K线走势 + 机构评级（外层 ScrollView 确保可以滚动）
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

        // 容器 2：AI Agent 分析结果（用户点击「运行AI分析」后显示，替换容器1）
        // 外层 ScrollView 确保长文本可以滚动
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

        // 底部按钮行：V1.0 / V2.0 / 向AI追问（立即显示，不等待 K 线加载）
        val btnRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 4)
            tag = "aiBtnRow"
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
                (it as Button).text = "V1.0分析中..."
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
                (it as Button).text = "V2.0分析中..."
                runAiAgents(AnalysisMode.EXPERT)
            }
        })
        btnRow.addView(Button(requireContext()).apply {
            text = "💬 向AI追问"
            textSize = 9f; setTextColor(Color.parseColor("#1565C0"))
            setBackgroundColor(Color.parseColor("#E3F2FD"))
            setPadding(12, 4, 12, 4)
            minimumHeight = 0; minHeight = 0
            setOnClickListener {
                val today = java.time.LocalDate.now()
                val msg = "请使用【最新交易日（${today}）的实时行情数据】，详细分析股票 $stockName($stockCode) 的投资价值，包括：\n" +
                    "1. 基本面分析（财报、估值、业绩）\n" +
                    "2. 技术面分析（K线走势、支撑阻力位）\n" +
                    "3. 资金面分析（主力资金流向、机构动态）\n" +
                    "4. 风险评估与投资建议\n" +
                    "请严格基于实时数据分析，不要使用训练数据中的旧价格。"
                val mainActivity = activity as? com.chin.stockanalysis.ui.MainActivity
                if (mainActivity != null) {
                    activity?.supportFragmentManager?.popBackStack()
                    mainActivity.switchToChatAndSend(msg)
                }
            }
        })
        root.addView(btnRow)
    }

    private fun toggleAiAnalysis() {
        aiExpanded = !aiExpanded
        // 更新标题行 AI 按钮的箭头
        root.findViewWithTag<TextView>("aiHeaderBtn")?.text =
            if (aiExpanded) "🤖 AI分析 ⌯" else "🤖 AI分析 ⌵"

        if (aiExpanded) {
            // 展开：优先显示 K线 + 机构评级
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

    /** 构建真正的 K 线图表（CombinedChart = K线 + 均线 + 坐标轴） */
    private fun buildCandleStickChart(snaps: List<DailySnapshotEntity>): CombinedChart {
        val chart = CombinedChart(requireContext())
        chart.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dpToPx(280))
        chart.setBackgroundColor(Color.WHITE)
        chart.description.isEnabled = false
        chart.legend.isEnabled = true
        chart.legend.textSize = 9f
        chart.legend.textColor = Color.parseColor("#666666")
        chart.setScaleEnabled(true)
        chart.setPinchZoom(true)       // 双指缩放
        chart.setDragEnabled(true)     // 拖动平移
        chart.setDoubleTapToZoomEnabled(true)  // 双击缩放
        chart.setHighlightPerTapEnabled(true)  // 点击高亮
        chart.setHighlightPerDragEnabled(true) // 拖动时持续显示十字光标
        chart.setVisibleXRangeMaximum(250f)  // 最多可见 250 根（1年）
        chart.setVisibleXRangeMinimum(15f)   // 最少可见 15 根，防止过度放大
        chart.drawOrder = arrayOf(
            CombinedChart.DrawOrder.CANDLE,
            CombinedChart.DrawOrder.LINE
        )
        // 定位到最新数据（右侧）
        if (snaps.size > 60) {
            chart.moveViewToX((snaps.size - 60).toFloat())
        } else {
            chart.moveViewToX(0f)
        }

        // 数据
        val entries = ArrayList<CandleEntry>()
        for (i in snaps.indices) {
            val s = snaps[i]
            entries.add(CandleEntry(
                i.toFloat(),
                s.high.toFloat(),       // shadow (上影线)
                s.low.toFloat(),        // shadow (下影线)
                s.open.toFloat(),       // open
                s.close.toFloat()       // close
            ))
        }
        val candleDataSet = CandleDataSet(entries, "K线").apply {
            color = Color.parseColor("#333333")
            shadowColor = Color.parseColor("#999999")
            shadowWidth = 1f
            increasingPaintStyle = android.graphics.Paint.Style.FILL
            decreasingPaintStyle = android.graphics.Paint.Style.FILL
            increasingColor = Color.parseColor("#E53935")  // 涨红
            decreasingColor = Color.parseColor("#43A047")  // 跌绿
            valueTextSize = 9f
            isHighlightEnabled = true
            setDrawValues(false)
            // 十字光标：虚线
            setDrawHighlightIndicators(true)
            setHighLightColor(Color.parseColor("#999999"))
            setHighlightLineWidth(1f)
            enableDashedHighlightLine(8f, 4f, 0f)  // 虚线：实线8px 间隙4px
        }

        // 均线计算
        val closes = snaps.map { it.close }
        val lineData = LineData()

        // MA5 均线
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

        // MA10 均线
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

        // MA20 均线
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

        // 组合数据：K线 + 均线
        val combinedData = com.github.mikephil.charting.data.CombinedData()
        combinedData.setData(CandleData(candleDataSet))
        combinedData.setData(lineData)
        chart.data = combinedData

        // X 轴：日期
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

        // Y 轴
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

        // ── 十字光标弹窗（点击/拖动时显示日期+开高低收） ──
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
                    tv.text = "$date\n开${"%.2f".format(e.open)} 高${"%.2f".format(e.high)}\n低${"%.2f".format(e.low)} 收${"%.2f".format(e.close)}$change"
                } else if (e != null) {
                    tv.text = "%.2f".format(e.y)
                }
                super.refreshContent(e, highlight)
            }

            override fun getOffset(): MPPointF {
                // 弹窗显示在触摸点上方居中
                return MPPointF.getInstance(-(width / 2f), -(height + 6 * dp))
            }

            override fun getOffsetForDrawingAtPoint(posX: Float, posY: Float): MPPointF {
                val offset = getOffset()
                // 如果弹窗超出右边界，向左偏移
                val adjustedX = when {
                    posX + offset.x + width > chart.width -> -(posX + width - chart.width + 4 * dp)
                    posX + offset.x < 0 -> -posX + 4 * dp
                    else -> offset.x
                }
                // 如果弹窗超出顶部，改为显示在下方
                val adjustedY = if (posY + offset.y < 0) 6 * dp else offset.y
                return MPPointF.getInstance(adjustedX, adjustedY)
            }
        }
        chart.marker = marker

        chart.invalidate()
        return chart
    }

    /**
     * 渲染 K 线图区块（标题 + 时间范围按钮 + 图表 + MA 简评）
     * 根据 allKlineSnaps 和 klineRangeDays 动态裁剪数据并重建图表
     */
    private fun renderKlineChart() {
        if (allKlineSnaps.isEmpty()) return
        // 清空 K 线专用容器（不影响后续评级内容）
        klineContentContainer.removeAllViews()

        // ── 按选择的时间范围裁剪数据 ──
        val displaySnaps = if (klineRangeDays <= 0) {
            allKlineSnaps  // 全部
        } else {
            allKlineSnaps.takeLast(klineRangeDays)
        }

        if (displaySnaps.size < 2) {
            klineContentContainer.addView(TextView(requireContext()).apply {
                text = "所选范围数据不足"
                textSize = 11f; setTextColor(Color.parseColor("#999999"))
                setPadding(0, 0, 0, 8)
            })
            return
        }

        // ── Tab 切换行（个股/上证/科创/创业） ──
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
                    klineRangeDays = 90  // 切换 Tab 时重置为默认范围
                    if (i == 0) {
                        // 个股：重新从数据库加载
                        switchToStockKline()
                    } else {
                        // 指数：异步加载
                        switchToIndexKline(i)
                    }
                }
            }
            tabRow.addView(tabBtn)
        }
        klineContentContainer.addView(tabRow)

        // ── 标题行 ──
        val rangeLabel = when (klineRangeDays) {
            30 -> "近1月"
            90 -> "近3月"
            120 -> "近6月"
            250 -> "近1年"
            0 -> "全部(${allKlineSnaps.size}天)"
            else -> "近${klineRangeDays}日"
        }
        val tabTitle = if (klineTabIndex == 0) "$stockName 日K线" else "${klineTabLabels[klineTabIndex]}指数 日K线"
        klineContentContainer.addView(TextView(requireContext()).apply {
            text = "## $tabTitle（$rangeLabel · ${displaySnaps.size}根）"
            textSize = 12f; setTextColor(Color.parseColor("#333333"))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 4)
        })

        // ── 时间范围选择按钮行 ──
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
                    val count = if (klineRangeDays <= 0) allKlineSnaps.size else allKlineSnaps.takeLast(klineRangeDays).size
                    android.widget.Toast.makeText(requireContext(), "${rb.label}: $count 根K线 / 总计 ${allKlineSnaps.size} 根", android.widget.Toast.LENGTH_SHORT).show()
                    renderKlineChart()
                }
            }
            rangeRow.addView(btn)
        }
        rangeRow.addView(TextView(requireContext()).apply {
            text = "  ← 双指缩放/拖动"
            textSize = 8f; setTextColor(Color.parseColor("#AAAAAA"))
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        })
        // 趋势图谱按钮
        rangeRow.addView(TextView(requireContext()).apply {
            text = " 📐图谱"
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

        // ── K 线图表 ──
        val chart = buildCandleStickChart(displaySnaps)
        klineContentContainer.addView(chart)

        // ── 综合趋势分析（自动识别，用户无需自行判断） ──
        val trendText = buildTrendAnalysis(displaySnaps)
        klineContentContainer.addView(TextView(requireContext()).apply {
            text = trendText
            textSize = 10f; setTextColor(Color.parseColor("#666666"))
            setLineSpacing(2f, 1f)
            setPadding(0, 4, 0, 8)
        })

        // 确保容器重新布局
        klineContentContainer.requestLayout()
        klineContentContainer.invalidate()
    }

    /** 切换回个股 K 线 */
    private fun switchToStockKline() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                allKlineSnaps = db.dailySnapshotDao().getByCode(stockCode, 500).sortedBy { it.date }
                // 数据不足时从网络补充
                if (allKlineSnaps.size < 250) {
                    try {
                        val fetcher = com.chin.stockanalysis.strategy.data.HistoricalDataFetcher(requireContext())
                        val endDate = java.time.LocalDate.now()
                        val startDate = endDate.minusDays(800)
                        val (fetched, _) = fetcher.fetchOneStock(stockCode, startDate, endDate)
                        if (fetched.isNotEmpty()) {
                            db.dailySnapshotDao().insertAll(fetched)
                            allKlineSnaps = db.dailySnapshotDao().getByCode(stockCode, 500).sortedBy { it.date }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "网络补充个股K线失败: ${e.message}")
                    }
                }
                withContext(Dispatchers.Main) { renderKlineChart() }
            } catch (e: Exception) {
                Log.w(TAG, "切换个股K线失败: ${e.message}")
            }
        }
    }

    /** 切换到指数 K 线（异步加载） */
    private fun switchToIndexKline(tabIndex: Int) {
        val indexCode = klineIndexCodes[tabIndex]
        klineContentContainer.removeAllViews()
        klineContentContainer.addView(TextView(requireContext()).apply {
            text = "⏳ 正在加载${klineTabLabels[tabIndex]}指数 K 线..."
            textSize = 10f; setTextColor(Color.parseColor("#999999"))
            setPadding(0, 8, 0, 8)
        })
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                var snaps = db.dailySnapshotDao().getByCode(indexCode, 500).sortedBy { it.date }
                // 如果本地数据不足，从网络补充
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
                        Log.w(TAG, "网络补充指数K线失败: ${e.message}")
                    }
                }
                allKlineSnaps = snaps
                withContext(Dispatchers.Main) {
                    if (snaps.size >= 5) {
                        renderKlineChart()
                    } else {
                        klineContentContainer.removeAllViews()
                        klineContentContainer.addView(TextView(requireContext()).apply {
                            text = "${klineTabLabels[tabIndex]}指数数据不足（当前${snaps.size}天）"
                            textSize = 11f; setTextColor(Color.parseColor("#999999"))
                            setPadding(0, 8, 0, 8)
                        })
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    klineContentContainer.removeAllViews()
                    klineContentContainer.addView(TextView(requireContext()).apply {
                        text = "加载失败: ${e.message}"
                        textSize = 11f; setTextColor(Color.parseColor("#C62828"))
                        setPadding(0, 8, 0, 8)
                    })
                }
            }
        }
    }

    /** 显示 K 线趋势参考图谱（WebView 加载 assets/trend_charts/index.html） */
    private fun showTrendChartReference() {
        val dialog = android.app.Dialog(requireContext())
        dialog.setTitle("K 线趋势参考图谱")
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
            webView.loadData("<html><body><h3>趋势图谱加载失败: ${e.message}</h3></body></html>", "text/html", "UTF-8")
        }
        dialog.show()
    }

    /**
     * 综合趋势分析 — 自动识别趋势类型，用户无需自行判断
     * 包含：均线排列、三天不新低、均线粘合向上、综合趋势类型
     */
    private fun buildTrendAnalysis(snaps: List<DailySnapshotEntity>): String {
        val sb = StringBuilder()
        val closes = snaps.map { it.close }
        val lows = snaps.map { it.low }
        val highs = snaps.map { it.high }
        val currentPrice = closes.last()

        // ── MA 计算 ──
        val ma5 = if (closes.size >= 5) closes.takeLast(5).average() else currentPrice
        val ma10 = if (closes.size >= 10) closes.takeLast(10).average() else null
        val ma20 = if (closes.size >= 20) closes.takeLast(20).average() else null

        sb.append("MA5: ${"%.2f".format(ma5)}")
        if (ma10 != null) sb.append(" | MA10: ${"%.2f".format(ma10)}")
        if (ma20 != null) sb.append(" | MA20: ${"%.2f".format(ma20)}")
        sb.append("\n现价 ${"%.2f".format(currentPrice)}")

        // ── 1. 均线排列趋势 ──
        val maTrend = if (ma20 != null && ma10 != null) {
            when {
                currentPrice > ma5 && ma5 > ma10 && ma10 > ma20 -> "多头排列 ↑↑"
                currentPrice < ma5 && ma5 < ma10 && ma10 < ma20 -> "空头排列 ↓↓"
                currentPrice > ma20 -> "中期偏多 ↑"
                else -> "中期偏空 ↓"
            }
        } else if (currentPrice > ma5) "短期偏多" else "短期偏空"
        sb.append(" → $maTrend")

        // ── 2. 三天不新低 ──
        // 最近3天的最低价都高于前10天的最低价 = 止跌企稳信号
        val threeDayNoNewLow = if (lows.size >= 13) {
            val recent3Lows = lows.takeLast(3)
            val prevMinLow = lows.subList(lows.size - 13, lows.size - 3).minOrNull() ?: Double.MAX_VALUE
            recent3Lows.all { it > prevMinLow }
        } else false
        sb.append("\n三天不新低: ${if (threeDayNoNewLow) "✓ 是（止跌企稳）" else "✗ 否"}")

        // ── 3. 均线粘合向上（共用工具） ──
        val maConvResult = MaConvergenceAnalyzer.analyze(snaps)
        val maConvergingUp = maConvResult.convergedAndUp
        sb.append(" | 均线粘合向上: ${if (maConvergingUp) "✓ 是（蓄势突破）" else "✗ 否"} (偏离${"%.2f".format(maConvResult.divergencePct)}%)")

        // ── 4. 近期高低点突破判断 ──
        val recentHighBreak = if (highs.size >= 20) {
            val prev20High = highs.subList(0, highs.size - 1).takeLast(20).maxOrNull() ?: 0.0
            currentPrice > prev20High
        } else false
        val recentLowBreak = if (lows.size >= 20) {
            val prev20Low = lows.subList(0, lows.size - 1).takeLast(20).minOrNull() ?: Double.MAX_VALUE
            currentPrice < prev20Low
        } else false

        // ── 5. 综合趋势类型（自动判断，用户无需自行分析图形） ──
        val trendType = when {
            maTrend.contains("多头排列") && threeDayNoNewLow -> "上升趋势（强势）"
            maTrend.contains("多头排列") -> "上升趋势"
            maConvergingUp && threeDayNoNewLow -> "底部企稳 + 蓄势突破（关注）"
            maConvergingUp -> "蓄势待发（均线粘合）"
            maTrend.contains("空头排列") && recentLowBreak -> "下跌趋势（破位）"
            maTrend.contains("空头排列") -> "下跌趋势"
            threeDayNoNewLow && maTrend.contains("偏多") -> "震荡偏多（企稳）"
            threeDayNoNewLow -> "底部企稳（待确认）"
            maTrend.contains("偏多") -> "震荡偏多"
            maTrend.contains("偏空") -> "震荡偏空"
            recentHighBreak -> "突破上行（关注）"
            else -> "震荡整理"
        }
        val trendColor = when {
            trendType.contains("上升") || trendType.contains("突破") -> "🔴"
            trendType.contains("下跌") || trendType.contains("破位") -> "🟢"
            trendType.contains("企稳") || trendType.contains("蓄势") -> "🟡"
            else -> "⚪"
        }
        sb.append("\n趋势类型: $trendColor $trendType")

        // ── 6. 操作建议 ──
        val advice = when {
            trendType.contains("上升趋势（强势）") -> "持仓为主，回调可加仓"
            trendType.contains("上升趋势") -> "顺势持仓，注意止盈"
            trendType.contains("蓄势突破") || trendType.contains("突破上行") -> "关注突破方向，放量可跟进"
            trendType.contains("底部企稳") -> "观察确认，轻仓试探"
            trendType.contains("蓄势待发") -> "等待方向选择"
            trendType.contains("下跌趋势（破位）") -> "及时止损，空仓观望"
            trendType.contains("下跌趋势") -> "观望为主，不抢反弹"
            trendType.contains("震荡偏多") -> "轻仓做多，设好止损"
            trendType.contains("震荡偏空") -> "谨慎操作，注意风险"
            else -> "观望等待方向"
        }
        sb.append("\n建议: $advice")

        // ── 7. K 线经典形态识别（自动匹配，对照趋势参考图） ──
        val patterns = CandlePatternDetector.detect(snaps)
        if (patterns.isNotEmpty()) {
            sb.append("\n\n📋 K线形态:")
            for (p in patterns) {
                val stars = "★".repeat(p.strength) + "☆".repeat(5 - p.strength)
                val emoji = if (p.direction == CandlePatternDetector.Direction.BULLISH) "🔴" else "🟢"
                sb.append("\n  $emoji ${p.patternName} $stars (${p.direction.label})")
                sb.append("\n     ${p.description}")
            }
        } else {
            sb.append("\n\n📋 K线形态: 无明显经典形态")
        }

        return sb.toString()
    }

    /** 计算移动平均线 Entry 列表 */
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

    /** 加载 K 线走势 + 机构评级数据 */
    private fun loadKlineAndRatings() {
        aiDetailContainer.removeAllViews()
        // 加载提示
        aiDetailContainer.addView(TextView(requireContext()).apply {
            text = "⏳ 正在加载 K 线走势..."
            textSize = 10f; setTextColor(Color.parseColor("#999999"))
        })

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val sb = StringBuilder()

                // ── 1. K 线走势（用 CandleStickChart 真正的图表） ──
                // 一次性拉取 500 天历史数据，按时间范围按钮动态裁剪
                allKlineSnaps = db.dailySnapshotDao().getByCode(stockCode, 500).sortedBy { it.date }

                // 如果本地数据不足 250 天（约1年），尝试从网络补充日K数据
                if (allKlineSnaps.size < 250) {
                    try {
                        val fetcher = com.chin.stockanalysis.strategy.data.HistoricalDataFetcher(requireContext())
                        val endDate = java.time.LocalDate.now()
                        val startDate = endDate.minusDays(800) // 约 2 年多
                        val (fetched, _) = fetcher.fetchOneStock(stockCode, startDate, endDate)
                        if (fetched.isNotEmpty()) {
                            // 写入数据库
                            db.dailySnapshotDao().insertAll(fetched)
                            // 重新读取
                            allKlineSnaps = db.dailySnapshotDao().getByCode(stockCode, 500).sortedBy { it.date }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "网络补充K线数据失败: ${e.message}")
                    }
                }
                withContext(Dispatchers.Main) { aiDetailContainer.removeAllViews() }

                if (allKlineSnaps.size >= 5) {
                    withContext(Dispatchers.Main) {
                        // 创建 K 线专用容器，按钮切换时只清空此容器，不影响后续评级内容
                        klineContentContainer = LinearLayout(requireContext()).apply {
                            orientation = LinearLayout.VERTICAL
                        }
                        aiDetailContainer.addView(klineContentContainer)
                        renderKlineChart()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        aiDetailContainer.addView(TextView(requireContext()).apply {
                            text = "K线数据不足（需要至少5天，当前${allKlineSnaps.size}天）"
                            textSize = 11f; setTextColor(Color.parseColor("#999999"))
                            setPadding(0, 0, 0, 8)
                        })
                    }
                }

                // ── 2. 机构评级 ──
                sb.appendLine("## 🏦 机构评级（近90天）")
                val ratingProvider = com.chin.stockanalysis.strategy.data.InstitutionalRatingProvider()
                val ratingSummary = ratingProvider.getRatingSummary(stockCode, days = 90)

                if (ratingSummary.totalReports == 0) {
                    sb.appendLine("⚠️ 暂无机构评级覆盖（该股票近90天无研究报告）")
                } else {
                    val emoji = when (ratingSummary.consensusRating) {
                        "买入" -> "🔴"
                        "增持" -> "🟠"
                        "中性" -> "⚪"
                        else -> "🟢"
                    }
                    sb.appendLine("共识评级: $emoji ${ratingSummary.consensusRating}（${ratingSummary.totalReports}份研报）")
                    sb.appendLine("买入${ratingSummary.buyCount} / 增持${ratingSummary.overweightCount} / 中性${ratingSummary.neutralCount} / 卖出${ratingSummary.sellCount}")

                    if (ratingSummary.avgTargetPrice != null) {
                        val upside = if (ratingSummary.avgTargetPrice > 0 && allKlineSnaps.isNotEmpty()) {
                            val last = allKlineSnaps.last().close
                            (ratingSummary.avgTargetPrice - last) / last * 100
                        } else 0.0
                        sb.appendLine("平均目标价: ¥${"%.2f".format(ratingSummary.avgTargetPrice)}（空间${if (upside >= 0) "↑" else "↓"}${"%.1f".format(kotlin.math.abs(upside))}%）")
                    }

                    if (ratingSummary.latestOrgs.isNotEmpty()) {
                        sb.appendLine("最近评级机构: ${ratingSummary.latestOrgs.take(3).joinToString("、")}")
                    }

                    val topRatings = ratingSummary.detailList.take(8)
                    sb.appendLine()
                    sb.appendLine("### 详细评级")
                    for (r in topRatings) {
                        val target = r.targetPriceHigh?.let { "目标¥${"%.1f".format(it)}" } ?: ""
                        val change = if (r.ratingChange != "未知") "【${r.ratingChange}】" else ""
                        val eps = mutableListOf<String>()
                        r.predictEpsThisYear?.let { eps.add("今年¥${"%.2f".format(it)}") }
                        r.predictEpsNextYear?.let { eps.add("明年¥${"%.2f".format(it)}") }
                        sb.appendLine("• ${r.orgName}: ${r.rating} $change $target (${r.publishDate})")
                        if (eps.isNotEmpty()) sb.appendLine("  EPS: ${eps.joinToString(" / ")}")
                    }
                }
                sb.appendLine()

                // ── 3. 基金持仓 ──
                sb.appendLine("## 💰 基金持仓（持仓市值TOP10）")
                val fundHoldings = ratingProvider.getFundHoldings(stockCode, 10)
                if (fundHoldings.isEmpty()) {
                    sb.appendLine("⚠️ 暂无基金持仓数据（数据源维护中）")
                } else {
                    for (f in fundHoldings) {
                        val capStr = if (f.holdMarketCap >= 10000)
                            "%.1f亿".format(f.holdMarketCap / 10000.0)
                        else "%.0f万".format(f.holdMarketCap)
                        sb.appendLine("• ${f.fundName}(${f.fundCode}): ${capStr} 占比${"%.2f".format(f.holdRatio)}% (${f.reportDate})")
                    }
                    val totalCap = fundHoldings.sumOf { it.holdMarketCap }
                    val totalStr = if (totalCap >= 10000) "%.1f亿".format(totalCap / 10000.0) else "%.0f万".format(totalCap)
                    sb.appendLine("\n合计持仓市值: $totalStr | ${fundHoldings.size}只基金")
                }
                sb.appendLine()

                withContext(Dispatchers.Main) {
                    aiDetailContainer.addView(TextView(requireContext()).apply {
                        text = sb.toString()
                        textSize = 11f; setTextColor(Color.parseColor("#333333"))
                        setLineSpacing(3f, 1f)
                    })
                    // 按钮行已在 buildAiAnalysisSection() 中创建，此处不再重复
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    aiDetailContainer.removeAllViews()
                    aiDetailContainer.addView(TextView(requireContext()).apply {
                        text = "加载失败: ${e.message}"
                        textSize = 11f; setTextColor(Color.parseColor("#C62828"))
                    })
                }
            }
        }
    }

    /** 当前正在进行的 AI 分析 Job（返回键可取消） */
    private var aiAnalysisJob: Job? = null

    /**
     * 统一分析入口（AgentOrchestrator.analyzeStock）。
     *
     * flag 控制编排深度：
     * - stockAnalysisRoute = AGENT_FRAMEWORK → 完整角色编排（Scout+Analyst+Guardian 并行）
     * - 否则 → 轻量直连（DeepAnalystEngine + 决策矩阵）
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

    /** 运行 AI Agent 分析，切换到 aiResultContainer */
    private fun runAiAgents(mode: AnalysisMode = AnalysisMode.DEEP) {
        // 取消之前正在进行的分析
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
                AnalysisMode.EXPERT -> "V2.0全周期分析中（市场环境+利润质量+决策矩阵）..."
                else -> "AI 分析中..."
            }
            textSize = 9f; setTextColor(Color.parseColor("#999999"))
        })
        aiResultContainer.addView(loadingRow)
        // 切换 view：隐藏主内容，显示AI结果
        aiDetailScrollView.visibility = View.GONE
        contentScrollView.visibility = View.GONE
        aiResultScrollView.visibility = View.VISIBLE

        aiAnalysisJob = lifecycleScope.launch(Dispatchers.IO) {
            val appCtx = requireContext().applicationContext
            // 统一入口：AgentOrchestrator.analyzeStock（flag 控制完整编排 / 轻量直连）
            val result = runAnalysis(appCtx, mode)

            // 检查是否被取消或 view 已销毁
            ensureActive()
            if (view == null || !isAdded) return@launch

            withContext(Dispatchers.Main) {
                aiResultContainer.removeAllViews()
                // 清理 summaryText 中可能残留的 JSON 碎片
                val cleanedSummary = cleanJsonArtifacts(result.summaryText)
                aiResultContainer.addView(TextView(requireContext()).apply {
                    text = cleanedSummary
                    textSize = if (result.mode == AnalysisMode.DEEP) 9f else 10f
                    setTextColor(Color.parseColor("#1B5E20"))
                    setLineSpacing(2f, 1f)
                    setPadding(0, 0, 0, 2)
                })
                // 模式标签
                if (result.mode != AnalysisMode.QUICK) {
                    aiResultContainer.addView(TextView(requireContext()).apply {
                        text = "🧠 ${result.elapsedMs}ms · ${if (result.mode == AnalysisMode.EXPERT) "全周期" else "深度"}分析"
                        textSize = 8f; setTextColor(Color.parseColor("#666666"))
                        gravity = Gravity.CENTER
                        setPadding(0, 2, 0, 2)
                    })
                }
                // 底部按钮行：返回 + 加仓/减仓/清仓 同一行
                val bottomRow = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setPadding(0, 4, 0, 2)
                }
                // 返回按钮
                bottomRow.addView(Button(requireContext()).apply {
                    text = "◀ 返回K线"
                    textSize = 9f; setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#757575"))
                    setPadding(10, 4, 10, 4)
                    minimumHeight = 0; minHeight = 0
                    setOnClickListener {
                        aiResultScrollView.visibility = View.GONE
                        contentScrollView.visibility = View.VISIBLE
                        aiDetailScrollView.visibility = View.VISIBLE
                        // 恢复分析按钮
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
                // 操作按钮（跳转 AI 对话闭环：将加仓/减仓/清仓指令发给 AI 执行）
                val recommendation = result.recommendation
                if (recommendation != null) {
                    if (recommendation in listOf("BUY", "WATCH")) {
                        bottomRow.addView(Button(requireContext()).apply {
                            text = "➕ 加仓"; textSize = 9f
                            setBackgroundColor(Color.parseColor("#1565C0"))
                            setPadding(10, 4, 10, 4)
                            minimumHeight = 0; minHeight = 0
                            setOnClickListener { routeTradeAction("加仓") }
                        })
                    }
                    if (recommendation in listOf("HOLD", "SELL", "WATCH")) {
                        bottomRow.addView(Button(requireContext()).apply {
                            text = "➖ 减仓"; textSize = 9f
                            setBackgroundColor(Color.parseColor("#F9A825"))
                            setPadding(10, 4, 10, 4)
                            minimumHeight = 0; minHeight = 0
                            setOnClickListener { routeTradeAction("减仓") }
                        })
                    }
                    if (recommendation in listOf("HOLD", "SELL")) {
                        bottomRow.addView(Button(requireContext()).apply {
                            text = "❌ 清仓"; textSize = 9f
                            setBackgroundColor(Color.parseColor("#C62828"))
                            setPadding(10, 4, 10, 4)
                            minimumHeight = 0; minHeight = 0
                            setOnClickListener { routeTradeAction("清仓") }
                        })
                    }
                }
                aiResultContainer.addView(bottomRow)

                // 同步更新 AI 综合分析区块
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
                    // 更新 header：板块标签 + 价格/涨跌幅
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

    /** 所属板块区域（含趋势 + 热度 + 资金流向） */
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

        // 异步获取板块实时数据（趋势 + 热度 + 资金流向）
        if (sectors.isNotEmpty()) {
            val detailLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(4, 2, 4, 6)
                tag = "sectorDetail"
            }
            val loadingTv = TextView(requireContext()).apply {
                text = "⏳ 板块趋势载入中..."
                textSize = 11f; setTextColor(Color.parseColor("#999999"))
                tag = "sectorLoading"
            }
            detailLayout.addView(loadingTv)
            card.addView(detailLayout)

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val allSectors = com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource.industrySectors +
                            com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource.conceptSectors
                    val matched = allSectors.filter { hot ->
                        sectors.any { s -> hot.name.contains(s) || s.contains(hot.name) }
                    }

                    withContext(Dispatchers.Main) {
                        loadingTv.visibility = View.GONE
                        if (matched.isNotEmpty()) {
                            for (hot in matched.take(3)) {
                                val row = LinearLayout(requireContext()).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    setPadding(4, 4, 4, 4)
                                }
                                row.addView(TextView(requireContext()).apply {
                                    text = hot.name
                                    textSize = 11f; setTextColor(Color.parseColor("#333333"))
                                    layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                                })
                                val trendColor = if (hot.changePercent >= 0) "#E53935" else "#43A047"
                                val trendSign = if (hot.changePercent >= 0) "+" else ""
                                row.addView(TextView(requireContext()).apply {
                                    text = "📈 $trendSign${String.format("%.2f", hot.changePercent)}%"
                                    textSize = 11f; setTextColor(Color.parseColor(trendColor))
                                    setPadding(8, 0, 8, 0)
                                })
                                val heatLabel = when {
                                    hot.hotScore >= 80 -> "🔥极热"
                                    hot.hotScore >= 60 -> "🟠活跃"
                                    hot.hotScore >= 40 -> "🟡温和"
                                    else -> "🟢冷清"
                                }
                                row.addView(TextView(requireContext()).apply {
                                    text = heatLabel
                                    textSize = 11f; setTextColor(Color.parseColor("#666666"))
                                    setPadding(8, 0, 8, 0)
                                })
                                val inflowStr = when {
                                    hot.mainNetInflow > 0 -> "+${String.format("%.1f", hot.mainNetInflow / 10000)}亿"
                                    hot.mainNetInflow < 0 -> "${String.format("%.1f", hot.mainNetInflow / 10000)}亿"
                                    else -> "-"
                                }
                                val inflowColor = if (hot.mainNetInflow >= 0) "#E53935" else "#43A047"
                                row.addView(TextView(requireContext()).apply {
                                    text = "💰 $inflowStr"
                                    textSize = 11f; setTextColor(Color.parseColor(inflowColor))
                                })
                                detailLayout.addView(row)
                            }
                        } else {
                            detailLayout.addView(TextView(requireContext()).apply {
                                text = "板块实时数据暂不可用"
                                textSize = 11f; setTextColor(Color.parseColor("#999999"))
                            })
                        }
                    }
                } catch (_: Exception) {
                    withContext(Dispatchers.Main) {
                        loadingTv.text = "板块趋势数据载入失败"
                    }
                }
            }
        }

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

    /** AI 综合分析区域 — 自动调用 DeepSeek 快速分析 */
    private fun buildAIAnalysisSection(
        leaderData: EastMoneyHotSectorSource.LeaderStock?,
        sectors: List<String>,
        subSector: String,
        newsFactors: List<NewsFactorEntity>
    ) {
        val card = createSectionCard()
        card.addView(createSectionTitle("🤖 AI 综合分析"))

        val ctx = requireContext()
        
        // 先显示加载提示
        val loadingLabel = TextView(ctx).apply {
            text = "⏳ 正在载入 AI 分析..."
            textSize = 12f; setTextColor(Color.parseColor("#888888"))
            setPadding(4, 4, 4, 8)
            tag = "aiQuickLoading"
        }
        card.addView(loadingLabel)

        // 结果容器
        val resultLabel = TextView(ctx).apply {
            textSize = 11f; setTextColor(Color.parseColor("#333333"))
            setLineSpacing(4f, 1.2f)
            setPadding(4, 6, 4, 8)
            visibility = View.GONE
            tag = "aiQuickResult"
        }
        card.addView(resultLabel)

        contentContainer.addView(card)

        // 自动触发 DeepSeek 快速分析
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val agent = com.chin.stockanalysis.agent.stock.StockAnalysisAgent(requireContext().applicationContext)
                val result = agent.analyze(stockCode, stockName)
                
                withContext(Dispatchers.Main) {
                    loadingLabel.visibility = View.GONE
                    resultLabel.visibility = View.VISIBLE
                    // 格式化显示分析结果
                    val displayText = buildString {
                        if (result.success) {
                            if (result.overallScore > 0) {
                                appendLine("📊 综合评分: ${result.overallScore}/100")
                                appendLine("建议: ${result.recommendation} | 置信度: ${result.confidence}")
                                appendLine()
                                // 各维度结构化结论
                                appendLine("📈 技术面: ${result.technicalScore}/100 — ${scoreVerdict(result.technicalScore)}")
                                appendLine("💼 基本面: ${result.fundamentalScore}/100 — ${scoreVerdict(result.fundamentalScore)}")
                                appendLine("💰 资金面: ${result.fundFlowScore}/100 — ${scoreVerdict(result.fundFlowScore)}")
                                appendLine()
                            }
                            if (result.targetPrice.isNotBlank()) appendLine("🎯 目标价: ${result.targetPrice}")
                            if (result.stopLoss.isNotBlank()) appendLine("🛑 止损位: ${result.stopLoss}")
                            if (result.riskFactors.isNotEmpty()) {
                                appendLine()
                                appendLine("⚠️ 风险因素:")
                                result.riskFactors.forEach { appendLine("  • $it") }
                            }
                            if (result.reasoning.isNotBlank()) {
                                appendLine()
                                // 清理 reasoning 中可能残留的 JSON 碎片
                                val cleanedReasoning = cleanJsonArtifacts(result.reasoning)
                                appendLine(cleanedReasoning)
                            }
                        } else {
                            append("⚠️ AI 分析载入失败: ${cleanJsonArtifacts(result.rawOutput)}")
                        }
                    }
                    resultLabel.text = displayText
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loadingLabel.text = "⚠️ AI 分析载入失败: ${e.message}"
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

    /** 根据评分给出一句话结论 */
    private fun scoreVerdict(score: Int): String = when {
        score >= 70 -> "偏多，表现较好"
        score >= 50 -> "中性，观望为主"
        score >= 30 -> "偏弱，注意风险"
        else -> "较差，谨慎操作"
    }

    /** 清理文本中残留的 JSON 碎片（花括号、键值对等），保留可读分析文字 */
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