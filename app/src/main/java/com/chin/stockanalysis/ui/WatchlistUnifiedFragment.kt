package com.chin.stockanalysis.ui

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.agent.v2.ProfitQualityAnalyzer
import com.chin.stockanalysis.agent.v2.ProfitQualityLevel
import com.chin.stockanalysis.agent.v2.PositionWaterValve
import com.chin.stockanalysis.ai.AiProviderPool
import com.chin.stockanalysis.stock.database.AiSelectedStockEntity
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.stock.database.UserWatchlistEntity
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import com.chin.stockanalysis.strategy.data.CandidatePool
import com.chin.stockanalysis.strategy.data.HistoricalDataFetcher
import com.chin.stockanalysis.strategy.data.LeaderStockPool
import com.chin.stockanalysis.strategy.data.TrendScanMemoryPool
import com.chin.stockanalysis.strategy.market.MarketAnalyzer
import com.chin.stockanalysis.strategy.trade.AutoTradePortfolioEngine
import com.chin.stockanalysis.common.StockDataService
import com.chin.stockanalysis.common.StockTableHelper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * ## 精选股票 统一页面
 *
 * 顶部三个切换按钮：⭐ 自选 | 🤖 AI精选 | 🎯 备选池
 * 共用同一个表格 View，切换时仅刷新数据源：
 *
 * | 股票名称 | 现价 | 涨幅 | 涨跌 |
 * | 603629   | 12.50| +3.2%| +0.39|
 *
 * - 自选模式：读取 user_watchlist 表
 * - AI精选模式：读取 ai_selected_stock 表，当天数据
 * - 备选池模式：CandidatePool.getPool() → 核心龙头 + AI热门板块龙头
 */
class WatchlistUnifiedFragment : Fragment() {

    private lateinit var rootLayout: LinearLayout
    private lateinit var selfSelectBtn: TextView
    private lateinit var aiSelectBtn: TextView
    private lateinit var candidateBtn: TextView
    private lateinit var trendImagesBtn: TextView
    private lateinit var statusTv: TextView
    private lateinit var lastUpdateTv: TextView
    private lateinit var statusRow: LinearLayout
    private lateinit var listContainer: LinearLayout
    private lateinit var headerRow: LinearLayout
    private var trendWebView: android.webkit.WebView? = null

    /** 趋势图 OCR 截图选择器 */
    private val trendOcrPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { processTrendOcr(it) } }

    /** 切换模式 */
    private enum class ViewMode { WATCHLIST, AI, CANDIDATE, TREND_IMAGES }
    private var currentMode = ViewMode.WATCHLIST

    /** 仅主板开关（仅备选池模式可见） */
    private var showOnlyMainBoard = true
    private lateinit var mainBoardSwitch: Switch
    private lateinit var mainBoardRow: LinearLayout

    /** 来源过滤 */
    private var selectedSource: String = "" // 空 = 全部
    private var availableSources: List<String> = emptyList()
    private lateinit var sourceFilterRow: LinearLayout

    /** 自选数据缓存 */
    private var watchlistData: List<UserWatchlistEntity> = emptyList()
    /** AI 精选数据缓存 */
    private var aiStocksData: List<AiSelectedStockEntity> = emptyList()
    /** 备选池数据缓存 */
    private var candidatePoolSnapshot: CandidatePool.PoolSnapshot? = null
    /** 行情缓存 */
    private val snapshotCache = ConcurrentHashMap<String, DailySnapshotEntity?>()

    /** 利润质量计算协程（用于取消） */
    private var qualityJob: Job? = null

    /** 市场环境分析协程 */
    private var marketEnvJob: Job? = null

    companion object {
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private const val MIN_RELOAD_INTERVAL_MS = 2 * 60 * 1000L  // 2分钟内不重复加载
    }

    private var lastLoadTime: Long = 0
    private var dataLoaded: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val sv = ScrollView(requireContext()).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F6FA"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        sv.addView(rootLayout)
        buildUI()
        // loadData 由 onResume 统一触发，避免重复
        return sv
    }

    /** 每次切换回此 Tab 时检查是否需要刷新（2分钟内不重复加载数据） */
    override fun onResume() {
        super.onResume()
        val now = System.currentTimeMillis()
        if (!dataLoaded || now - lastLoadTime > MIN_RELOAD_INTERVAL_MS) {
            loadData()
            lastLoadTime = now
            dataLoaded = true
        }
        loadMarketEnvironment()
    }

    /**
     * 异步加载市场环境数据，更新顶部 marketEnvBar
     */
    private fun loadMarketEnvironment() {
        marketEnvJob?.cancel()
        marketEnvJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val marketEnvBar = rootLayout.findViewWithTag<TextView>("marketEnvBar") ?: return@launch
                marketEnvBar.text = "正在分析市场环境..."
                marketEnvBar.setBackgroundColor(Color.parseColor("#FFF3E0"))
                marketEnvBar.visibility = View.VISIBLE

                val report = withContext(Dispatchers.IO) {
                    MarketAnalyzer.analyze(requireContext(), emptyList())
                }

                val capResult = PositionWaterValve.calculatePositionCap(report)
                val (bgColor, emoji, direction) = when (report.trend.direction) {
                    "BULLISH" -> Triple("#E8F5E9", "📈", "多头")
                    "BEARISH" -> Triple("#FFEBEE", "📉", "空头")
                    else -> Triple("#FFF3E0", "📊", "震荡")
                }

                if (isAdded) {
                    marketEnvBar.text = "$emoji 市场：$direction（强度${report.trend.strength}/100）| 仓位上限 ${capResult.capPercent}% | ${capResult.strategy}"
                    marketEnvBar.setBackgroundColor(Color.parseColor(bgColor))
                    marketEnvBar.setTextColor(when (report.trend.direction) {
                        "BULLISH" -> Color.parseColor("#2E7D32")
                        "BEARISH" -> Color.parseColor("#C62828")
                        else -> Color.parseColor("#E65100")
                    })
                }
            } catch (e: Exception) {
                if (isAdded) {
                    rootLayout.findViewWithTag<TextView>("marketEnvBar")?.let {
                        it.text = "⚠️ 市场环境数据获取失败"
                        it.setBackgroundColor(Color.parseColor("#FFF3E0"))
                        it.setTextColor(Color.parseColor("#999999"))
                    }
                }
            }
        }
    }

    /**
     * 异步计算每只股票的利润质量，并更新表格中的质量标签。
     * 标签通过 tag "qualityLabel_${code}" 定位。
     */
    private fun computeProfitQualityLabels(items: List<StockTableHelper.StockDisplayItem>) {
        qualityJob?.cancel()
        qualityJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            for (item in items) {
                if (!isActive) break
                val emoji = try {
                    val result = withTimeoutOrNull(15_000L) {
                        ProfitQualityAnalyzer.analyze(item.code)
                    }
                    if (result == null) "⚪"  // timeout
                    else when (result.qualityLevel) {
                        ProfitQualityLevel.ENDOGENOUS_GROWTH -> "🟢"
                        ProfitQualityLevel.ONE_TIME_PROFIT -> "🟡"
                        ProfitQualityLevel.PROFIT_INFLATION -> "🔴"
                        ProfitQualityLevel.INSUFFICIENT_DATA -> "⚪"
                    }
                } catch (_: Exception) {
                    "⚪"
                }
                if (!isActive) break
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    try {
                        rootLayout.findViewWithTag<TextView>("qualityLabel_${item.code}")?.text = emoji
                    } catch (_: Exception) {}
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        qualityJob?.cancel()
        marketEnvJob?.cancel()
        trendWebView?.removeAllViews()
        trendWebView?.destroy()
        trendWebView = null
    }

    private fun buildUI() {
        // ── 切换按钮行 (3 个按钮) ──
        val toggleRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(16, 12, 16, 8)
            gravity = Gravity.CENTER
        }
        val toggleBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor("#F0F0F0"))
            cornerRadius = 6f * resources.displayMetrics.density
        }
        val toggleInner = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            background = toggleBg
            setPadding(2, 2, 2, 2)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        selfSelectBtn = createToggleButton("⭐ 自选", selected = true) {
            if (currentMode != ViewMode.WATCHLIST) {
                currentMode = ViewMode.WATCHLIST
                updateToggleState()
                renderList()
            }
        }
        aiSelectBtn = createToggleButton("🤖 AI精选", selected = false) {
            if (currentMode != ViewMode.AI) {
                currentMode = ViewMode.AI
                updateToggleState()
                renderList()
            }
        }
        candidateBtn = createToggleButton("🎯 备选池", selected = false) {
            if (currentMode != ViewMode.CANDIDATE) {
                currentMode = ViewMode.CANDIDATE
                updateToggleState()
                loadCandidatePool(forceRefresh = false)
            }
        }
        trendImagesBtn = createToggleButton("📈 趋势图", selected = false) {
            if (currentMode != ViewMode.TREND_IMAGES) {
                currentMode = ViewMode.TREND_IMAGES
                updateToggleState()
                renderTrendImages()
            }
        }

        toggleInner.addView(selfSelectBtn)
        toggleInner.addView(aiSelectBtn)
        toggleInner.addView(candidateBtn)
        toggleInner.addView(trendImagesBtn)
        toggleRow.addView(toggleInner)
        rootLayout.addView(toggleRow)

        // ── 来源过滤行（自选模式下可见）──
        val sourceFilterScroll = HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.WHITE)
            visibility = View.GONE
        }
        sourceFilterRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 4, 12, 8)
        }
        sourceFilterScroll.addView(sourceFilterRow)
        rootLayout.addView(sourceFilterScroll)

        // ── 仅主板开关（仅备选池模式可见）──
        mainBoardRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 4, 16, 4)
            setBackgroundColor(Color.WHITE)
            visibility = View.GONE
        }
        mainBoardRow.addView(TextView(requireContext()).apply {
            text = "仅主板"
            textSize = 13f
            setTextColor(Color.parseColor("#333333"))
            setPadding(0, 0, 8, 0)
        })
        mainBoardSwitch = Switch(requireContext()).apply {
            isChecked = true
            setOnCheckedChangeListener { _, isChecked ->
                showOnlyMainBoard = isChecked
                renderList()
            }
        }
        mainBoardRow.addView(mainBoardSwitch)
        rootLayout.addView(mainBoardRow)

        // ── 市场环境信息条 ──
        val marketEnvBar = TextView(requireContext()).apply {
            tag = "marketEnvBar"
            text = "正在分析市场环境..."
            textSize = 12f
            setTextColor(Color.parseColor("#333333"))
            setPadding(16, 8, 16, 8)
            setBackgroundColor(Color.parseColor("#FFF3E0"))
            visibility = View.VISIBLE
        }
        rootLayout.addView(marketEnvBar)

        // ── 状态列 ──
        statusRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 6, 16, 6)
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#FFF8E1"))
        }
        statusTv = TextView(requireContext()).apply {
            text = "加载中..."
            textSize = 11f
            setTextColor(Color.parseColor("#F57F17"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusRow.addView(statusTv)
        lastUpdateTv = TextView(requireContext()).apply {
            text = ""
            textSize = 10f
            setTextColor(Color.parseColor("#999999"))
        }
        statusRow.addView(lastUpdateTv)
        rootLayout.addView(statusRow)

        // ── 表头 ──
        headerRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#FAFAFA"))
            setPadding(16, 10, 16, 10)
            visibility = View.GONE
        }
        headerRow.addView(createHeaderCell("股票名称", 2.5f, Gravity.START))
        headerRow.addView(createHeaderCell("现价", 1.0f, Gravity.END))
        headerRow.addView(createHeaderCell("涨幅", 1.0f, Gravity.CENTER))
        headerRow.addView(createHeaderCell("涨跌", 1.0f, Gravity.END))
        rootLayout.addView(headerRow)

        // ── 列表容器 ──
        listContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 80)
            id = View.generateViewId()
        }
        rootLayout.addView(listContainer)
    }

    private fun createHeaderCell(text: String, weight: Float, gravity: Int): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            textSize = 11f
            setTextColor(Color.parseColor("#888888"))
            setTypeface(null, Typeface.BOLD)
            this.gravity = gravity
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
        }
    }

    private fun createToggleButton(text: String, selected: Boolean, onClick: () -> Unit): TextView {
        val dp = resources.displayMetrics.density
        return TextView(requireContext()).apply {
            this.text = text
            textSize = 12f
            setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            setPadding((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
            updateToggleStyle(this, selected)
        }
    }

    private fun updateToggleStyle(btn: TextView, selected: Boolean) {
        val dp = resources.displayMetrics.density
        if (selected) {
            btn.setTextColor(Color.WHITE)
            btn.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#1565C0"))
                cornerRadius = 5f * dp
            }
        } else {
            btn.setTextColor(Color.parseColor("#666666"))
            btn.background = null
        }
    }

    private fun updateToggleState() {
        updateToggleStyle(selfSelectBtn, currentMode == ViewMode.WATCHLIST)
        updateToggleStyle(aiSelectBtn, currentMode == ViewMode.AI)
        updateToggleStyle(candidateBtn, currentMode == ViewMode.CANDIDATE)
        updateToggleStyle(trendImagesBtn, currentMode == ViewMode.TREND_IMAGES)
        selfSelectBtn.setTypeface(null, if (currentMode == ViewMode.WATCHLIST) Typeface.BOLD else Typeface.NORMAL)
        aiSelectBtn.setTypeface(null, if (currentMode == ViewMode.AI) Typeface.BOLD else Typeface.NORMAL)
        candidateBtn.setTypeface(null, if (currentMode == ViewMode.CANDIDATE) Typeface.BOLD else Typeface.NORMAL)
        trendImagesBtn.setTypeface(null, if (currentMode == ViewMode.TREND_IMAGES) Typeface.BOLD else Typeface.NORMAL)

        // 仅主板开关仅在备选池模式可见
        mainBoardRow.visibility = if (currentMode == ViewMode.CANDIDATE) View.VISIBLE else View.GONE
        // 来源过滤仅在自选模式可见
        (sourceFilterRow.parent as? View)?.visibility = if (currentMode == ViewMode.WATCHLIST && availableSources.size > 1) View.VISIBLE else View.GONE
        // 状态栏在趋势图模式隐藏
        statusRow.visibility = if (currentMode == ViewMode.TREND_IMAGES) View.GONE else View.VISIBLE
    }

    // ═══════════════════════════════════════
    // 数据加载
    // ═══════════════════════════════════════

    private fun loadData() {
        statusTv.text = "加载中..."
        when (currentMode) {
            ViewMode.CANDIDATE -> loadCandidatePool(forceRefresh = false)
            else -> loadWatchlistAndAiData()
        }
    }

    private fun loadWatchlistAndAiData() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val today = LocalDate.now().format(DATE_FMT)

                // 加载自选
                watchlistData = db.userWatchlistDao().getAll()

                // 加载来源列表
                availableSources = db.userWatchlistDao().getDistinctSources()

                // 加载 AI 精选（近 5 天）
                val minDate = LocalDate.now().minusDays(5).format(DATE_FMT)
                aiStocksData = db.aiSelectedStockDao().getRecentDays(minDate)

                // 获取行情
                val allCodes = (watchlistData.map { it.stockCode } + aiStocksData.map { it.stockCode }).distinct()
                snapshotCache.clear()
                for (code in allCodes) {
                    val snap = try {
                        db.dailySnapshotDao().getByDateAndCode(today, code)
                    } catch (_: Exception) { null }
                    snapshotCache[code] = snap
                }

                withContext(Dispatchers.Main) {
                    renderSourceFilterChips()
                    renderList()
                    val count = if (currentMode == ViewMode.AI) aiStocksData.size else watchlistData.size
                    statusTv.text = "✅ 共 $count 只"
                    lastUpdateTv.text = java.text.SimpleDateFormat(
                        "HH:mm:ss", java.util.Locale.getDefault()
                    ).format(java.util.Date())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTv.text = "❌ 加载失败: ${e.message?.take(30)}"
                }
            }
        }
    }

    private fun loadCandidatePool(forceRefresh: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            statusTv.text = "加载备选池..."
            listContainer.removeAllViews()
        }
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val snapshot = CandidatePool.getPool(requireContext(), forceRefresh)
                candidatePoolSnapshot = snapshot
                withContext(Dispatchers.Main) {
                    renderList()
                    val filtered = if (showOnlyMainBoard) {
                        snapshot.stocks.count { isMainBoard(it.code) }
                    } else {
                        snapshot.stocks.size
                    }
                    statusTv.text = "✅ 备选池 ${filtered} 只（总 ${snapshot.stocks.size}）"
                    lastUpdateTv.text = "更新: ${snapshot.updateTime}"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTv.text = "❌ 加载失败: ${e.message?.take(30)}"
                }
            }
        }
    }

    // ═══════════════════════════════════════
    // 渲染列表（使用 StockTableHelper 公共函数）
    // ═══════════════════════════════════════

    private fun renderList() {
        listContainer.removeAllViews()
        listContainer.addView(TextView(requireContext()).apply {
            text = "加载中..."
            textSize = 14f; setTextColor(Color.parseColor("#999999"))
            gravity = Gravity.CENTER; setPadding(0, 48, 0, 48)
        })

        when (currentMode) {
            ViewMode.CANDIDATE -> renderCandidateList()
            ViewMode.TREND_IMAGES -> renderTrendImages()
            else -> renderWatchlistOrAiList()
        }
    }

    // ═══════════════════════════════════════
    //  来源过滤 Chips
    // ═══════════════════════════════════════

    private fun renderSourceFilterChips() {
        sourceFilterRow.removeAllViews()
        if (availableSources.isEmpty()) return

        val dp = resources.displayMetrics.density

        // 「全部」 chip
        sourceFilterRow.addView(createSourceChip("全部", selectedSource.isEmpty()) {
            selectedSource = ""
            renderSourceFilterChips()
            renderList()
        })

        for (source in availableSources) {
            val label = sourceLabel(source)
            sourceFilterRow.addView(createSourceChip(label, selectedSource == source) {
                selectedSource = if (selectedSource == source) "" else source
                renderSourceFilterChips()
                renderList()
            })
        }
    }

    private fun createSourceChip(text: String, selected: Boolean, onClick: () -> Unit): TextView {
        val dp = resources.displayMetrics.density
        return TextView(requireContext()).apply {
            this.text = text
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins((3 * dp).toInt(), 0, (3 * dp).toInt(), 0)
            layoutParams = lp
            if (selected) {
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.parseColor("#1565C0"))
                    cornerRadius = 12f * dp
                }
                setTypeface(null, Typeface.BOLD)
            } else {
                setTextColor(Color.parseColor("#666666"))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.parseColor("#F0F0F0"))
                    cornerRadius = 12f * dp
                }
            }
            setOnClickListener { onClick() }
        }
    }

    private fun sourceLabel(source: String): String = when (source) {
        "manual" -> "手动"
        "midterm" -> "中线"
        "shortterm" -> "短线"
        "ultra_short" -> "超短"
        "long_term" -> "长线"
        "AI推荐" -> "AI推荐"
        else -> source
    }

    private fun renderWatchlistOrAiList() {
        val allCodes = if (currentMode == ViewMode.AI) {
            aiStocksData.map { it.stockCode }
        } else {
            // 自选模式：按来源过滤
            val data = if (selectedSource.isNotEmpty()) {
                watchlistData.filter { it.source == selectedSource }
            } else {
                watchlistData
            }
            data.map { it.stockCode }
        }
        if (allCodes.isEmpty()) {
            listContainer.removeAllViews()
            listContainer.addView(TextView(requireContext()).apply {
                text = if (currentMode == ViewMode.AI) "暂无 AI 精选数据，请先运行策略" else "暂无自选股，请添加"
                textSize = 14f; setTextColor(Color.parseColor("#999999"))
                gravity = Gravity.CENTER; setPadding(0, 48, 0, 48)
            })
            return
        }
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val items = StockDataService.enrich(requireContext(), allCodes)
                withContext(Dispatchers.Main) {
                    listContainer.removeAllViews()
                    listContainer.addView(
                        StockTableHelper.createDynamicTable(
                            context = requireContext(),
                            columns = StockTableHelper.extendedColumns(),
                            items = items,
                            onItemClick = { item ->
                                StockDetailNavigator.navigateFromFragment(
                                    this@WatchlistUnifiedFragment,
                                    item.code, item.name,
                                    item.price, item.changePct, item.sector
                                )
                            },
                            onClearAll = {
                                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                                    try {
                                        val db = StockDatabase.getInstance(requireContext())
                                        if (currentMode == ViewMode.AI) {
                                            db.aiSelectedStockDao().clearAll()
                                        } else {
                                            db.userWatchlistDao().clearAll()
                                        }
                                        withContext(Dispatchers.Main) { loadData() }
                                    } catch (_: Exception) {}
                                }
                            },
                            onDelete = { deleted ->
                                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                                    try {
                                        val db = StockDatabase.getInstance(requireContext())
                                        if (currentMode == ViewMode.AI) {
                                            db.aiSelectedStockDao().deleteByCode(deleted.code)
                                        } else {
                                            db.userWatchlistDao().deleteByCode(deleted.code)
                                        }
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(requireContext(), "✅ 已移除: ${deleted.name}", Toast.LENGTH_SHORT).show()
                                            loadData()
                                        }
                                    } catch (_: Exception) {}
                                }
                            }
                        )
                    )
                    // 表格构建完成后，异步计算利润质量标签
                    computeProfitQualityLabels(items)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTv.text = "❌ 加载失败: ${e.message?.take(30)}"
                }
            }
        }
    }

    private fun renderCandidateList() {
        val snapshot = candidatePoolSnapshot
        if (snapshot == null || snapshot.stocks.isEmpty()) {
            listContainer.removeAllViews()
            listContainer.addView(TextView(requireContext()).apply {
                text = "暂无备选池数据"
                textSize = 14f; setTextColor(Color.parseColor("#999999"))
                gravity = Gravity.CENTER; setPadding(0, 48, 0, 48)
            })
            return
        }

        val ctx = requireContext()
        val allStocks = snapshot.stocks
        val filteredStocks = if (showOnlyMainBoard) {
            allStocks.filter { isMainBoard(it.code) }
        } else {
            allStocks
        }.sortedByDescending { it.changePct }

        if (filteredStocks.isEmpty()) {
            listContainer.removeAllViews()
            listContainer.addView(TextView(ctx).apply {
                text = "暂无符合条件的股票"; textSize = 14f
                setTextColor(Color.parseColor("#999999"))
                gravity = Gravity.CENTER; setPadding(0, 48, 0, 48)
            })
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val codes = filteredStocks.map { it.code }
                val items = StockDataService.enrich(ctx, codes)
                withContext(Dispatchers.Main) {
                    listContainer.removeAllViews()
                    listContainer.addView(
                        StockTableHelper.createDynamicTable(
                            context = ctx,
                            columns = StockTableHelper.extendedColumns(),
                            items = items,
                            onItemClick = { item ->
                                StockDetailNavigator.navigateFromFragment(
                                    this@WatchlistUnifiedFragment,
                                    item.code, item.name,
                                    item.price, item.changePct, item.sector
                                )
                            },
                            onClearAll = {
                                candidatePoolSnapshot = candidatePoolSnapshot?.let { it.copy(stocks = emptyList()) }
                                val prefs = ctx.getSharedPreferences("candidate_pool_prefs", android.content.Context.MODE_PRIVATE)
                                prefs.edit().remove("pool_codes").apply()
                                renderList()
                            },
                            onDelete = { deleted ->
                                candidatePoolSnapshot = candidatePoolSnapshot?.let { snap ->
                                    snap.copy(stocks = snap.stocks.filter { it.code != deleted.code })
                                }
                                val prefs = ctx.getSharedPreferences("candidate_pool_prefs", android.content.Context.MODE_PRIVATE)
                                val poolCodes = (prefs.getStringSet("pool_codes", emptySet()) ?: emptySet()).toMutableSet()
                                poolCodes.remove(deleted.code)
                                prefs.edit().putStringSet("pool_codes", poolCodes).apply()
                                renderList()
                            }
                        )
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTv.text = "❌ 渲染失败: ${e.message?.take(30)}"
                }
            }
        }
    }

    private fun isMainBoard(code: String): Boolean {
        return code.startsWith("sh6") || code.startsWith("sz0") || code.startsWith("sz2")
    }

    // ═══════════════════════════════════════
    // 趋势图谱展示（WebView 加载 SVG 图谱）
    // ═══════════════════════════════════════

    private fun renderTrendImages() {
        listContainer.removeAllViews()
        val ctx = requireContext()

        // ── OCR 导入按钮行 ──
        val ocrRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 8, 16, 8)
            setBackgroundColor(Color.WHITE)
        }
        val ocrHint = TextView(ctx).apply {
            text = "截图导入K线形态："
            textSize = 13f
            setTextColor(Color.parseColor("#666666"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        ocrRow.addView(ocrHint)
        val ocrBtn = Button(ctx).apply {
            text = "📷 选择截图"
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#6200EA"))
            setPadding(dp(16), dp(6), dp(16), dp(6))
            setOnClickListener { trendOcrPicker.launch("image/*") }
        }
        ocrRow.addView(ocrBtn)
        // ── 自动扫描股票池按钮 ──
        val scanBtn = Button(ctx).apply {
            text = "📡 扫描股票池"
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#E65100"))
            setPadding(dp(16), dp(6), dp(16), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(8) }
            setOnClickListener { scanPoolsAndUpdateTrends() }
        }
        ocrRow.addView(scanBtn)
        listContainer.addView(ocrRow)

        // ── OCR 状态提示 ──
        val ocrStatusTv = TextView(ctx).apply {
            text = ""
            textSize = 12f
            setTextColor(Color.parseColor("#999999"))
            setPadding(16, 4, 16, 4)
            visibility = View.GONE
            tag = "ocrStatus"
        }
        listContainer.addView(ocrStatusTv)

        // ── WebView 加载图谱 ──
        val webView = android.webkit.WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        trendWebView = webView

        try {
            val html = ctx.assets.open("trend_charts/index.html").bufferedReader().use { it.readText() }
            webView.loadDataWithBaseURL("file:///android_asset/trend_charts/", html, "text/html", "UTF-8", null)
        } catch (e: Exception) {
            listContainer.addView(TextView(ctx).apply {
                text = "趋势图谱加载失败: ${e.message}"
                textSize = 14f; setTextColor(Color.parseColor("#999999"))
                gravity = Gravity.CENTER; setPadding(0, 48, 0, 48)
            })
            return
        }

        listContainer.addView(webView)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ═══════════════════════════════════════
    // 趋势图 OCR 识别 + AI 解析 + 动态注入
    // ═══════════════════════════════════════

    private fun processTrendOcr(uri: android.net.Uri) {
        val ctx = requireContext()
        val ocrStatus = listContainer.findViewWithTag<TextView>("ocrStatus")
        ocrStatus?.apply { text = "🔄 正在识别截图..."; visibility = View.VISIBLE }

        try {
            val inputStream = ctx.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (bitmap == null) {
                ocrStatus?.text = "❌ 无法读取图片"
                return
            }

            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    if (!isAdded) return@addOnSuccessListener
                    val rawText = visionText.text
                    ocrStatus?.text = "🤖 AI 正在分析K线形态..."

                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        val patternJson = parseTrendWithAi(rawText)
                        withContext(Dispatchers.Main) {
                            if (!isAdded) return@withContext
                            if (patternJson != null) {
                                injectPatternToWebView(patternJson)
                                ocrStatus?.text = "✅ 已添加形态：${patternJson.optString("name")}"
                            } else {
                                ocrStatus?.text = "⚠️ 未能识别K线形态，请确保截图包含清晰的K线图表"
                            }
                        }
                    }
                }
                .addOnFailureListener { e ->
                    if (!isAdded) return@addOnFailureListener
                    ocrStatus?.text = "❌ OCR 识别失败: ${e.message}"
                }
        } catch (e: Exception) {
            if (isAdded) ocrStatus?.text = "❌ 图片处理失败: ${e.message}"
        }
    }

    /**
     * 使用 AI 从 OCR 文字中提取 K 线形态数据。
     * 返回 JSONObject 格式：{cat, type, name, en, data:[{o,h,l,c,label}], desc, rules}
     */
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

            // 提取 JSON
            val jsonMatch = Regex("\\{[\\s\\S]*\\}").find(response)
            if (jsonMatch == null) return null

            return try {
                JSONObject(jsonMatch.value)
            } catch (e: Exception) {
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("TrendOCR", "AI 解析失败: ${e.message}", e)
            return null
        } finally {
            AiProviderPool.releaseNonBlocking(slot)
        }
    }

    /**
     * 将解析到的 K 线形态注入 WebView。
     */
    private fun injectPatternToWebView(patternJson: JSONObject) {
        val webView = trendWebView ?: return
        val jsonStr = patternJson.toString().replace("'", "\\'")
        webView.post {
            webView.evaluateJavascript(
                "addPatternFromApp('$jsonStr')",
                android.webkit.ValueCallback<String> { result ->
                    android.util.Log.i("TrendOCR", "注入结果: $result")
                }
            )
        }
    }

    // ═══════════ 自动扫描股票池 → 更新K线 → 形态识别 → 注入趋势图 ═══════════

    /**
     * 自动扫描：自选池 + AI精选 + 备选池 + 龙头股票池 + 实仓 + 持仓 → 去重
     * → 增量更新最新 K 线（公共内存池 [TrendScanMemoryPool] 去重，其他地方已更新则跳过）
     * → 本地形态识别 → 注入趋势图 WebView。
     */
    private fun scanPoolsAndUpdateTrends() {
        val ctx = requireContext()
        val status = listContainer.findViewWithTag<TextView>("ocrStatus")
        fun statusText(s: String) {
            requireActivity().runOnUiThread { status?.apply { text = s; visibility = View.VISIBLE } }
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val db = StockDatabase.getInstance(ctx)
            // ① 收集股票池 → 去重（有序）
            val codes = linkedSetOf<String>()
            try { db.userWatchlistDao().getAll().forEach { codes.add(it.stockCode) } } catch (e: Exception) {}
            try { db.aiSelectedStockDao().getAll().forEach { codes.add(it.stockCode) } } catch (e: Exception) {}
            try { CandidatePool.getPoolCodes(ctx).forEach { codes.add(it) } } catch (e: Exception) {}
            try { LeaderStockPool.getAllCodes(ctx).forEach { codes.add(it) } } catch (e: Exception) {}
            try { db.realPositionDao().getAll().forEach { codes.add(it.stockCode) } } catch (e: Exception) {}
            try { AutoTradePortfolioEngine(ctx).getHoldings().forEach { codes.add(it.stockCode) } } catch (e: Exception) {}
            if (codes.isEmpty()) {
                statusText("⚠️ 各股票池均为空，无法扫描")
                return@launch
            }
            // ② 增量更新 K 线（公共内存池去重）
            val fetcher = HistoricalDataFetcher(ctx)
            val recentDay = com.chin.stockanalysis.ui.TradingDayPickerView
                .recentTradingDay().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            var updated = 0; var skipped = 0; var failed = 0
            val nameMap = try {
                db.stockBasicDao().getByCodes(codes.toList()).associate { it.code to it.name }
            } catch (e: Exception) { emptyMap<String, String>() }
            codes.toList().forEachIndexed { i, code ->
                statusText("📡 扫描 ${i + 1}/${codes.size}：${nameMap[code] ?: code} ($code)")
                if (TrendScanMemoryPool.isUpdated(code)) { skipped++; return@forEachIndexed }
                // 本地已有最近交易日数据 → 无需网络更新，仅标记
                val have = try {
                    db.dailySnapshotDao().getMaxDateByCode().associate { it.code to it.maxDate }[code]
                } catch (e: Exception) { null }
                if (have != null && have >= recentDay) {
                    TrendScanMemoryPool.markUpdated(code); skipped++; return@forEachIndexed
                }
                if (fetcher.fetchStockLatest(code)) { updated++; TrendScanMemoryPool.markUpdated(code) }
                else failed++
            }
            // ③ 本地形态识别 + 注入趋势图
            var injected = 0
            codes.forEach { code ->
                val candles = try { db.dailySnapshotDao().getByCode(code, 30) } catch (e: Exception) { emptyList() }
                if (candles.size < 20) return@forEach
                val pat = detectTrendPattern(candles) ?: return@forEach
                injectPatternToWebView(buildTrendPatternJson(code, nameMap[code] ?: code, candles, pat))
                injected++
            }
            statusText("✅ 扫描完成：共 ${codes.size} 只 → 更新 $updated 只，复用 $skipped 只，失败 $failed 只，识别形态 $injected 个")
        }
    }

    /** 本地 K 线形态识别（与趋势图分类对齐：three/trend/two/single） */
    private fun detectTrendPattern(candles: List<DailySnapshotEntity>): Pair<String, String>? {
        val k = candles.take(20).reversed() // 时间正序
        if (k.size < 20) return null
        val last = k.last()
        // 三白兵：最近3根均阳线且收盘依次抬高
        val last3 = k.takeLast(3)
        if (last3.size == 3 && last3.all { it.close > it.open } &&
            last3[0].close < last3[1].close && last3[1].close < last3[2].close) {
            return "three" to "三白兵"
        }
        // 看涨吞没：前阴后阳，阳线实体吞没前阴实体
        if (k.size >= 2) {
            val prev = k[k.size - 2]
            if (prev.close < prev.open && last.close > last.open &&
                last.close >= prev.open && last.open <= prev.close) {
                return "two" to "看涨吞没"
            }
        }
        // 锤子线：下影线 >= 2 倍实体，收盘偏强
        val body = Math.abs(last.close - last.open)
        val lower = Math.min(last.close, last.open) - last.low
        if (body > 0 && lower >= 2 * body && last.close >= last.open) return "single" to "锤子线(反转)"
        // 上升趋势：MA5 > MA20 且收盘站上 MA5
        fun ma(n: Int): Double = k.takeLast(n).map { it.close }.average()
        if (ma(5) > ma(20) && last.close > ma(5)) return "trend" to "上升趋势"
        return null
    }

    /** 构建 addPatternFromApp 兼容 JSON（含最近 40 根 K 线） */
    private fun buildTrendPatternJson(
        code: String,
        name: String,
        candles: List<DailySnapshotEntity>,
        pat: Pair<String, String>
    ): JSONObject {
        val k = candles.take(40).reversed()
        val data = JSONArray()
        k.forEach { c ->
            data.put(JSONObject().apply {
                put("o", c.open); put("h", c.high); put("l", c.low); put("c", c.close)
                put("label", c.date)
            })
        }
        return JSONObject().apply {
            put("cat", pat.first)
            put("type", "bullish")
            put("name", "$name($code) ${pat.second}")
            put("en", "${pat.first}_$code")
            put("data", data)
            put("desc", "自动扫描识别：${pat.second}（股票池扫描）")
            put("rules", "${pat.second}形态，来源：股票池自动扫描")
        }
    }

    /**
     * 外部分享入口：接收其他应用分享的图片/PDF/文字，
     * 切换到自选 Tab 并过滤 AI 推荐来源。
     */
    fun handleSharedContent(uri: android.net.Uri?, text: String?) {
        // 切换到自选模式并过滤 AI 推荐
        currentMode = ViewMode.WATCHLIST
        selectedSource = "AI推荐"
        updateToggleState()
        loadData()
    }

    /** 切换到自选模式并过滤 AI 推荐来源（从 AI 对话框导航过来时调用） */
    fun switchToInstitutionalMode() {
        currentMode = ViewMode.WATCHLIST
        selectedSource = "AI推荐"
        updateToggleState()
        loadData()
    }
}