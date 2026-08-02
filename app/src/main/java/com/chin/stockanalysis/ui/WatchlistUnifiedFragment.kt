package com.chin.stockanalysis.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.agent.v2.ProfitQualityAnalyzer
import com.chin.stockanalysis.agent.v2.ProfitQualityLevel
import com.chin.stockanalysis.agent.v2.PositionWaterValve
import com.chin.stockanalysis.stock.database.AiSelectedStockEntity
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.stock.database.UserWatchlistEntity
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import com.chin.stockanalysis.strategy.data.CandidatePool
import com.chin.stockanalysis.strategy.market.MarketAnalyzer
import com.chin.stockanalysis.common.StockDataService
import com.chin.stockanalysis.common.StockTableHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * ## 精選股票 統一頁面
 *
 * 頂部三個切換按鈕：⭐ 自選 | 🤖 AI精選 | 🎯 備選池
 * 共用同一個表格 View，切換時僅刷新數據源：
 *
 * | 股票名稱 | 現價 | 漲幅 | 漲跌 |
 * | 603629   | 12.50| +3.2%| +0.39|
 *
 * - 自選模式：讀取 user_watchlist 表
 * - AI精選模式：讀取 ai_selected_stock 表，當天數據
 * - 備選池模式：CandidatePool.getPool() → 核心龍頭 + AI熱門板塊龍頭
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

    /** 切換模式 */
    private enum class ViewMode { WATCHLIST, AI, CANDIDATE, TREND_IMAGES }
    private var currentMode = ViewMode.WATCHLIST

    /** 僅主板開關（僅備選池模式可見） */
    private var showOnlyMainBoard = true
    private lateinit var mainBoardSwitch: Switch
    private lateinit var mainBoardRow: LinearLayout

    /** 自選數據緩存 */
    private var watchlistData: List<UserWatchlistEntity> = emptyList()
    /** AI 精選數據緩存 */
    private var aiStocksData: List<AiSelectedStockEntity> = emptyList()
    /** 備選池數據緩存 */
    private var candidatePoolSnapshot: CandidatePool.PoolSnapshot? = null
    /** 行情緩存 */
    private val snapshotCache = ConcurrentHashMap<String, DailySnapshotEntity?>()

    /** 利潤質量計算協程（用於取消） */
    private var qualityJob: Job? = null

    /** 市場環境分析協程 */
    private var marketEnvJob: Job? = null

    companion object {
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private const val MIN_RELOAD_INTERVAL_MS = 2 * 60 * 1000L  // 2分鐘內不重複加載
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
        // loadData 由 onResume 統一觸發，避免重複
        return sv
    }

    /** 每次切換回此 Tab 時檢查是否需要刷新（2分鐘內不重複加載數據） */
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
     * 異步加載市場環境數據，更新頂部 marketEnvBar
     */
    private fun loadMarketEnvironment() {
        marketEnvJob?.cancel()
        marketEnvJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val marketEnvBar = rootLayout.findViewWithTag<TextView>("marketEnvBar") ?: return@launch
                marketEnvBar.text = "正在分析市場環境..."
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
     * 異步計算每只股票的利潤質量，並更新表格中的質量標籤。
     * 標籤通過 tag "qualityLabel_${code}" 定位。
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
        // ── 切換按鈕行 (3 個按鈕) ──
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

        // ── 僅主板開關（僅備選池模式可見）──
        mainBoardRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 4, 16, 4)
            setBackgroundColor(Color.WHITE)
            visibility = View.GONE
        }
        mainBoardRow.addView(TextView(requireContext()).apply {
            text = "僅主板"
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

        // ── 市場環境信息條 ──
        val marketEnvBar = TextView(requireContext()).apply {
            tag = "marketEnvBar"
            text = "正在分析市場環境..."
            textSize = 12f
            setTextColor(Color.parseColor("#333333"))
            setPadding(16, 8, 16, 8)
            setBackgroundColor(Color.parseColor("#FFF3E0"))
            visibility = View.VISIBLE
        }
        rootLayout.addView(marketEnvBar)

        // ── 狀態列 ──
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

        // ── 表頭 ──
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

        // 僅主板開關僅在備選池模式可見
        mainBoardRow.visibility = if (currentMode == ViewMode.CANDIDATE) View.VISIBLE else View.GONE
        // 狀態欄在趨勢圖模式隱藏
        statusRow.visibility = if (currentMode == ViewMode.TREND_IMAGES) View.GONE else View.VISIBLE
    }

    // ═══════════════════════════════════════
    // 數據加載
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

                // 加載自選
                watchlistData = db.userWatchlistDao().getAll()

                // 加載 AI 精選（近 5 天）
                val minDate = LocalDate.now().minusDays(5).format(DATE_FMT)
                aiStocksData = db.aiSelectedStockDao().getRecentDays(minDate)

                // 獲取行情
                val allCodes = (watchlistData.map { it.stockCode } + aiStocksData.map { it.stockCode }).distinct()
                snapshotCache.clear()
                for (code in allCodes) {
                    val snap = try {
                        db.dailySnapshotDao().getByDateAndCode(today, code)
                    } catch (_: Exception) { null }
                    snapshotCache[code] = snap
                }

                withContext(Dispatchers.Main) {
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
            statusTv.text = "加載備選池..."
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
                    statusTv.text = "✅ 備選池 ${filtered} 只（總 ${snapshot.stocks.size}）"
                    lastUpdateTv.text = "更新: ${snapshot.updateTime}"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTv.text = "❌ 加載失敗: ${e.message?.take(30)}"
                }
            }
        }
    }

    // ═══════════════════════════════════════
    // 渲染列表（使用 StockTableHelper 公共函數）
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

    private fun renderWatchlistOrAiList() {
        val codes = if (currentMode == ViewMode.AI) aiStocksData.map { it.stockCode } else watchlistData.map { it.stockCode }
        if (codes.isEmpty()) {
            listContainer.removeAllViews()
            listContainer.addView(TextView(requireContext()).apply {
                text = if (currentMode == ViewMode.AI) "暂無 AI 精選数据，请先運行策略" else "暂無自選股，請添加"
                textSize = 14f; setTextColor(Color.parseColor("#999999"))
                gravity = Gravity.CENTER; setPadding(0, 48, 0, 48)
            })
            return
        }
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val items = StockDataService.enrich(requireContext(), codes)
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
                    // 表格構建完成後，異步計算利潤質量標籤
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
                text = "暫無備選池數據"
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
                text = "暫無符合條件的股票"; textSize = 14f
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
                    statusTv.text = "❌ 渲染失敗: ${e.message?.take(30)}"
                }
            }
        }
    }

    private fun isMainBoard(code: String): Boolean {
        return code.startsWith("sh6") || code.startsWith("sz0") || code.startsWith("sz2")
    }

    // ═══════════════════════════════════════
    // 趨勢圖譜展示（WebView 加載 SVG 圖譜）
    // ═══════════════════════════════════════

    private fun renderTrendImages() {
        listContainer.removeAllViews()

        val ctx = requireContext()
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
                text = "趨勢圖譜加載失敗: ${e.message}"
                textSize = 14f; setTextColor(Color.parseColor("#999999"))
                gravity = Gravity.CENTER; setPadding(0, 48, 0, 48)
            })
            return
        }

        listContainer.addView(webView)
    }
}