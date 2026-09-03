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
    private lateinit var statusTv: TextView
    private lateinit var lastUpdateTv: TextView
    private lateinit var statusRow: LinearLayout
    private lateinit var listContainer: LinearLayout
    private lateinit var headerRow: LinearLayout
    /** 切换模式 */
    private enum class ViewMode { WATCHLIST, AI, CANDIDATE }
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
        toggleInner.addView(selfSelectBtn)
        toggleInner.addView(aiSelectBtn)
        toggleInner.addView(candidateBtn)
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
        selfSelectBtn.setTypeface(null, if (currentMode == ViewMode.WATCHLIST) Typeface.BOLD else Typeface.NORMAL)
        aiSelectBtn.setTypeface(null, if (currentMode == ViewMode.AI) Typeface.BOLD else Typeface.NORMAL)
        candidateBtn.setTypeface(null, if (currentMode == ViewMode.CANDIDATE) Typeface.BOLD else Typeface.NORMAL)

        // 仅主板开关仅在备选池模式可见
        mainBoardRow.visibility = if (currentMode == ViewMode.CANDIDATE) View.VISIBLE else View.GONE
        // 来源过滤仅在自选模式可见
        (sourceFilterRow.parent as? View)?.visibility = if (currentMode == ViewMode.WATCHLIST && availableSources.size > 1) View.VISIBLE else View.GONE
        // 状态栏始终可见
        statusRow.visibility = View.VISIBLE
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