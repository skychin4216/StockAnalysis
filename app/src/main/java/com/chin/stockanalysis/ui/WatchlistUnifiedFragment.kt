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

    /** 仅主板开关（仅备选池模式可见）；默认关闭以显示中小票（科创/创业），2026-09-06 */
    private var showOnlyMainBoard = false
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

    /** 渲染代数：切换模式/过滤时递增，旧协程结果不再应用（避免串台覆盖） */
    private var displayGen = 0

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
            isChecked = false
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

                // 加载自选
                watchlistData = db.userWatchlistDao().getAll()

                // 加载来源列表
                availableSources = db.userWatchlistDao().getDistinctSources()

                // 加载 AI 精选（近 5 天）
                val minDate = LocalDate.now().minusDays(5).format(DATE_FMT)
                aiStocksData = db.aiSelectedStockDao().getRecentDays(minDate)

                // 本地最新交易日快照（不依赖网络，供秒开/失败回退展示）
                val allCodes = (watchlistData.map { it.stockCode } + aiStocksData.map { it.stockCode }).distinct()
                snapshotCache.clear()
                snapshotCache.putAll(latestDailySnapshots(allCodes))

                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    renderSourceFilterChips()
                    renderList()
                    if (allCodes.isEmpty()) {
                        statusTv.text = "✅ 共 0 只"
                        lastUpdateTv.text = nowTime()
                    }
                }
            } catch (e: Exception) {
                // DB 读取异常（极罕见）：尽力用已有缓存渲染，不再整表报错
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    renderList()
                    statusTv.text = "❌ 本地数据读取失败，请稍后重试"
                    lastUpdateTv.text = nowTime()
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
                    if (!isAdded) return@withContext
                    // 优先回退：本次会话已有备选池快照
                    if (candidatePoolSnapshot != null && candidatePoolSnapshot?.stocks?.isNotEmpty() == true) {
                        renderList()
                        statusTv.text = "⚠️ 备选池刷新失败：显示上次数据${errSuffix(e)}"
                        lastUpdateTv.text = "更新: ${candidatePoolSnapshot?.updateTime}"
                    } else {
                        // 无内存快照：用本地预存 pool_codes 构建离线展示
                        val ctx = requireContext()
                        val prefs = ctx.getSharedPreferences("candidate_pool_prefs", android.content.Context.MODE_PRIVATE)
                        val poolCodes = prefs.getStringSet("pool_codes", emptySet())?.toList() ?: emptyList()
                        if (poolCodes.isEmpty()) {
                            listContainer.removeAllViews()
                            listContainer.addView(emptyHintView("备选池刷新失败，请检查网络后重试"))
                            statusTv.text = "❌ 备选池加载失败${errSuffix(e)}"
                        } else {
                            loadCandidateOffline(poolCodes)
                        }
                    }
                }
            }
        }
    }

    /** 备选池联网失败且无内存快照时：用本地预存代码 + 最近交易日快照直接展示 */
    private fun loadCandidateOffline(codes: List<String>) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val snaps = latestDailySnapshots(codes)
                val names = HashMap<String, String>()
                try {
                    StockDatabase.getInstance(requireContext()).stockBasicDao().getAll()
                        .forEach { if (it.name.isNotBlank()) names[it.code] = it.name }
                } catch (_: Exception) {}
                val stocks = codes.mapNotNull { code ->
                    val s = snaps[code]
                    if (s == null && !names.containsKey(code)) return@mapNotNull null
                    CandidatePool.CandidateStock(
                        code = code,
                        name = names[code]?.takeIf { it.isNotBlank() }
                            ?: s?.name?.takeIf { it.isNotBlank() } ?: code,
                        sector = "",
                        subSector = "",
                        source = "ai",
                        rankInSector = 0,
                        changePct = s?.changePct ?: 0.0
                    )
                }.sortedByDescending { it.changePct }
                candidatePoolSnapshot = CandidatePool.PoolSnapshot(
                    stocks = stocks,
                    hotSectors = emptyList(),
                    etfSectors = emptyList(),
                    updateTime = snaps.values.firstOrNull()?.date ?: "",
                    totalCount = stocks.size
                )
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    renderList()
                    statusTv.text = "⚠️ 备选池离线缓存 ${stocks.size} 只（联网后自动更新）"
                    lastUpdateTv.text = nowTime()
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    listContainer.removeAllViews()
                    listContainer.addView(emptyHintView("备选池加载失败，请检查网络后重试"))
                    statusTv.text = "❌ 备选池加载失败"
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

    private fun emptyHintView(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.parseColor("#999999"))
        gravity = Gravity.CENTER
        setPadding(0, 48, 0, 48)
    }

    private fun nowTime(): String =
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())

    private fun errSuffix(e: Exception): String =
        if (e.message.isNullOrBlank()) "" else "（${e.message!!.take(20)}）"

    /** 最近一个可用交易日的本地日快照（≤今天，无网络依赖）；DAO 为挂起函数 */
    private suspend fun latestDailySnapshots(codes: List<String>): Map<String, DailySnapshotEntity> {
        if (codes.isEmpty()) return emptyMap()
        return try {
            val db = StockDatabase.getInstance(requireContext())
            val today = LocalDate.now().format(DATE_FMT)
            val dates = db.dailySnapshotDao().getAvailableDates(10)
            val target = dates.filter { it <= today }.maxOrNull() ?: dates.maxOrNull()
            if (target == null) emptyMap()
            else db.dailySnapshotDao().getByDate(target).filter { it.code in codes }.associateBy { it.code }
        } catch (_: Exception) { emptyMap() }
    }

    /** 用本地记录（名称）+ 最近快照构建展示项；缓存缺失的代码自动补一次本地快照 */
    private suspend fun buildLocalWatchlistItems(codes: List<String>): List<StockTableHelper.StockDisplayItem> {
        if (codes.isEmpty()) return emptyList()
        val names = HashMap<String, String>()
        watchlistData.forEach { if (it.stockName.isNotBlank()) names[it.stockCode] = it.stockName }
        aiStocksData.forEach { if (it.stockName.isNotBlank()) names[it.stockCode] = it.stockName }
        if (codes.any { !snapshotCache.containsKey(it) }) {
            snapshotCache.putAll(latestDailySnapshots(codes))
        }
        if (codes.any { (names[it] ?: "").isBlank() }) {
            try {
                StockDatabase.getInstance(requireContext()).stockBasicDao().getAll()
                    .forEach { if (it.name.isNotBlank()) names.putIfAbsent(it.code, it.name) }
            } catch (_: Exception) {}
        }
        return codes.mapNotNull { code ->
            val snap = snapshotCache[code]
            StockTableHelper.StockDisplayItem(
                code = code,
                name = names[code]?.takeIf { it.isNotBlank() } ?: code,
                changePct = snap?.changePct ?: 0.0,
                price = snap?.close ?: 0.0,
                high = snap?.high ?: 0.0,
                low = snap?.low ?: 0.0,
                volume = snap?.volume ?: 0L,
                turnoverRate = snap?.turnoverRate ?: 0.0,
                hasSnapshot = snap != null
            )
        }
    }

    /** 自选/AI精选 表格（删除/清空回调按模式分发） */
    private fun showWatchlistOrAiTable(items: List<StockTableHelper.StockDisplayItem>, mode: ViewMode) {
        listContainer.removeAllViews()
        listContainer.addView(StockTableHelper.createDynamicTable(
            context = requireContext(),
            columns = StockTableHelper.extendedColumns(),
            items = items,
            onItemClick = { item ->
                StockDetailNavigator.navigateFromFragment(
                    this@WatchlistUnifiedFragment,
                    item.code, item.name, item.price, item.changePct, item.sector
                )
            },
            onClearAll = {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val db = StockDatabase.getInstance(requireContext())
                        if (mode == ViewMode.AI) db.aiSelectedStockDao().clearAll()
                        else db.userWatchlistDao().clearAll()
                        withContext(Dispatchers.Main) { loadData() }
                    } catch (_: Exception) {}
                }
            },
            onDelete = { deleted ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val db = StockDatabase.getInstance(requireContext())
                        if (mode == ViewMode.AI) db.aiSelectedStockDao().deleteByCode(deleted.code)
                        else db.userWatchlistDao().deleteByCode(deleted.code)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "✅ 已移除: ${deleted.name}", Toast.LENGTH_SHORT).show()
                            loadData()
                        }
                    } catch (_: Exception) {}
                }
            }
        ))
    }

    /** 备选池表格（删除/清空只改本地缓存） */
    private fun showCandidateTable(items: List<StockTableHelper.StockDisplayItem>) {
        val ctx = requireContext()
        listContainer.removeAllViews()
        listContainer.addView(StockTableHelper.createDynamicTable(
            context = ctx,
            columns = StockTableHelper.extendedColumns(),
            items = items,
            onItemClick = { item ->
                StockDetailNavigator.navigateFromFragment(
                    this@WatchlistUnifiedFragment,
                    item.code, item.name, item.price, item.changePct, item.sector
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
        ))
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
        val mode = currentMode
        val codes = if (mode == ViewMode.AI) {
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
        if (codes.isEmpty()) {
            listContainer.removeAllViews()
            listContainer.addView(emptyHintView(
                if (mode == ViewMode.AI) "暂无 AI 精选数据，请先运行策略" else "暂无自选股，请添加"
            ))
            return
        }
        val ctx = requireContext()
        val gen = ++displayGen

        // 第一遍：本地记录 + 最近交易日快照直接展示（秒开，不依赖网络）
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val localItems = buildLocalWatchlistItems(codes)
            withContext(Dispatchers.Main) {
                if (!isAdded || gen != displayGen || currentMode != mode) return@withContext
                showWatchlistOrAiTable(localItems, mode)
                statusTv.text = "📄 本地 ${localItems.size} 只（最近交易日），实时行情更新中…"
            }
            // 第二遍：联网补全实时行情；失败保留第一遍的本地列表
            try {
                val liveItems = StockDataService.enrich(ctx, codes)
                withContext(Dispatchers.Main) {
                    if (!isAdded || gen != displayGen || currentMode != mode) return@withContext
                    showWatchlistOrAiTable(liveItems, mode)
                    statusTv.text = "✅ 共 ${liveItems.size} 只"
                    lastUpdateTv.text = nowTime()
                    // 表格构建完成后，异步计算利润质量标签
                    computeProfitQualityLabels(liveItems)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!isAdded || gen != displayGen || currentMode != mode) return@withContext
                    statusTv.text = "⚠️ 实时行情更新失败：显示本地记录${errSuffix(e)}"
                    lastUpdateTv.text = nowTime()
                }
            }
        }
    }

    private fun renderCandidateList() {
        val snapshot = candidatePoolSnapshot
        if (snapshot == null || snapshot.stocks.isEmpty()) {
            listContainer.removeAllViews()
            listContainer.addView(emptyHintView("暂无备选池数据，请点击上方「备选池」加载"))
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
            listContainer.addView(emptyHintView("暂无符合条件的股票"))
            return
        }

        val gen = ++displayGen

        // 第一遍：备选池缓存 + 最近交易日快照直接展示（秒开）
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val localCodes = filteredStocks.map { it.code }
            val snaps = latestDailySnapshots(localCodes)
            snapshotCache.putAll(snaps)
            val localItems = filteredStocks.map { c ->
                val s = snaps[c.code]
                StockTableHelper.StockDisplayItem(
                    code = c.code,
                    name = c.name.takeIf { it.isNotBlank() } ?: c.code,
                    sector = c.sector,
                    subSector = c.subSector,
                    changePct = if (c.changePct != 0.0) c.changePct else s?.changePct ?: 0.0,
                    price = s?.close ?: 0.0,
                    high = s?.high ?: 0.0,
                    low = s?.low ?: 0.0,
                    volume = s?.volume ?: 0L,
                    turnoverRate = s?.turnoverRate ?: 0.0,
                    hasSnapshot = s != null || c.changePct != 0.0
                )
            }
            withContext(Dispatchers.Main) {
                if (!isAdded || gen != displayGen || currentMode != ViewMode.CANDIDATE) return@withContext
                showCandidateTable(localItems)
                statusTv.text = "📄 备选池 ${localItems.size} 只（缓存数据），实时行情更新中…"
                lastUpdateTv.text = "更新: ${snapshot.updateTime}"
            }
            // 第二遍：联网补全实时行情；失败保留缓存列表
            try {
                val liveItems = StockDataService.enrich(ctx, localCodes)
                withContext(Dispatchers.Main) {
                    if (!isAdded || gen != displayGen || currentMode != ViewMode.CANDIDATE) return@withContext
                    showCandidateTable(liveItems)
                    val filtered = if (showOnlyMainBoard) liveItems.count { isMainBoard(it.code) } else liveItems.size
                    statusTv.text = "✅ 备选池 ${filtered} 只（总 ${liveItems.size}）"
                    lastUpdateTv.text = "更新: ${snapshot.updateTime}"
                    computeProfitQualityLabels(liveItems)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!isAdded || gen != displayGen || currentMode != ViewMode.CANDIDATE) return@withContext
                    statusTv.text = "⚠️ 实时行情更新失败：显示备选池缓存${errSuffix(e)}"
                    lastUpdateTv.text = "更新: ${snapshot.updateTime}"
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