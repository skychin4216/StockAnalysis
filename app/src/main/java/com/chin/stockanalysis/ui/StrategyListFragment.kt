package com.chin.stockanalysis.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.*
import android.widget.LinearLayout.LayoutParams
import android.widget.AdapterView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chin.stockanalysis.stock.data.StockDataSourceFactory
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.*
import com.chin.stockanalysis.strategy.HoldingPeriod
import com.chin.stockanalysis.strategy.backtest.StrategySelfTuner
import com.chin.stockanalysis.strategy.data.StockScreener
import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.models.StrategySignal
import com.chin.stockanalysis.strategy.models.WeightFactor
import com.chin.stockanalysis.strategy.predict.AIPredictionEngine
import com.chin.stockanalysis.strategy.strategies.*
import com.chin.stockanalysis.strategy.topology.xml.UseCaseExecution
import com.chin.stockanalysis.strategy.topology.xml.UseCaseLoader
import com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource
import com.chin.stockanalysis.stock.database.StockDataCenter
import com.chin.stockanalysis.stock.database.ChinaMarketTradingHours as A股TradingHours
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * 量化选股列表 (v3.4 — 主板开关 + 多日扫描标题)
 */
class StrategyListFragment : Fragment() {

    private lateinit var layout: LinearLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: GroupedStrategyAdapter
    private lateinit var statusTv: TextView
    private lateinit var scanBtn: Button
    private lateinit var progressBar: ProgressBar

    private lateinit var dateLabelTv: TextView
    private lateinit var datePicker: TradingDayPickerView
    private lateinit var resetBtn: Button
    private lateinit var hotSectorSpinner: Spinner
    private var selectedHotPeriod = 0
    private var browsingDate: LocalDate = TradingDayPickerView.recentTradingDay()
    private var isBrowsing = false

    // 分组折叠状态：key = "platform"（平台策略，默认展开）或 HoldingPeriod.name（周期，默认折叠）
    private val collapsedKeys = mutableSetOf<String>().apply {
        addAll(HoldingPeriod.values().map { it.name })
    }
    // 平台策略执行进度/结果弹窗
    private lateinit var useCaseProgressDialog: AlertDialog
    private lateinit var useCaseProgressText: TextView

    private var currentHotSectors: List<String> = emptyList()
    private var selectedSectors: Set<String> = emptySet()

    private var engine: StrategyEngine? = null
    private var screener: StockScreener? = null
    private var strategyCount = 0
    private var pendingResults: List<ScreeningResult>? = null

    // 缓存：避免10分钟内重复执行相同条件
    private var lastExecTimeMs: Long = 0L
    private var lastExecDate: LocalDate? = null
    private var lastExecPeriod: Int = -1  // selectedHotPeriod
    private var cachedResults: List<ScreeningResult>? = null

    // 板块上下文（供 AI 选股加权使用）
    private var lastSectorContext: com.chin.stockanalysis.strategy.predict.AIPredictionEngine.SectorContext? = null

    companion object {
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#F5F6FA"))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        initEngine(); buildUI(); return layout
    }

    private fun initEngine() {
        val ctx = requireContext().applicationContext
        // 确保热门板块调度器已启动 (不管 MarketHotFragment 有没有建立)
        EastMoneyHotSectorSource.startPoolScheduler(lifecycleScope)
        StrategyEngineHolder.init(ctx)
        engine = StrategyEngineHolder.get()
        strategyCount = engine?.getStrategies()?.size ?: 8
        // 初始化 StockScreener（实时扫描用）
        val repo = StockDataSourceFactory.createDefaultRepository(ctx)
        screener = StockScreener(repo, ctx)
        lifecycleScope.launch(Dispatchers.IO) {
            engine?.getStrategies()?.forEach { strategy ->
                StrategySelfTuner.loadLatestTunedWeights(requireContext(), strategy.id)?.let { tuned ->
                    strategy.weightFactors = tuned; Log.i("SLF", "加载拟合权重: ${strategy.id}")
                }
            }
        }
        preloadSectorLabelMap()
    }

    private fun buildUI() {
        val header = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 8); setBackgroundColor(Color.WHITE)
        }
        val titleRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(requireContext()).apply {
            text = "$strategyCount 种策略 · 多维度综合打分 · 热门板块驱动"
            textSize = 16f; setTextColor(Color.parseColor("#1A1A2E")); setTypeface(null, Typeface.BOLD)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(TextView(requireContext()).apply {
            text = "${LocalDate.now()}"; textSize = 11f; setTextColor(Color.parseColor("#2E7D32")); setTypeface(null, Typeface.BOLD)
        })
        header.addView(titleRow)

        val hotSectorRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 6, 0, 0)
        }
        hotSectorRow.addView(TextView(requireContext()).apply {
            text = "\uD83D\uDCCC 热门"; textSize = 13f; setTextColor(Color.parseColor("#E65100")); setTypeface(null, Typeface.BOLD); setPadding(0, 0, 4, 0)
        })
        hotSectorSpinner = Spinner(requireContext()).apply {
            val presets = listOf("当日(板块/子板块)", "近三日(板块/子板块)", "近10日(板块/子板块)", "近30日(板块/子板块)", "近50日", "近100日(板块/子板块)")
            adapter = object : ArrayAdapter<String>(requireContext(), android.R.layout.simple_spinner_item, presets) {
                init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
                    val tv = super.getView(pos, cv, parent) as TextView
                    tv.textSize = 12f; tv.setTextColor(Color.parseColor("#E65100")); tv.typeface = Typeface.DEFAULT_BOLD
                    return tv
                }
            }
            setSelection(0); setBackgroundColor(Color.parseColor("#FFF3E0")); setPadding(6, 4, 6, 4)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    selectedHotPeriod = pos; selectedSectors = emptySet(); loadHotSectors()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        hotSectorRow.addView(hotSectorSpinner)
        dateLabelTv = TextView(requireContext()).apply {
            text = "  交易日:"; textSize = 12f; setTextColor(Color.parseColor("#999999")); setPadding(dp(8), 0, dp(2), 0)
        }; hotSectorRow.addView(dateLabelTv)
        datePicker = TradingDayPickerView(requireContext()).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            onDateChanged = { d ->
                browsingDate = d
                isBrowsing = (browsingDate != TradingDayPickerView.recentTradingDay())
                val isNonTrading = d.dayOfWeek == java.time.DayOfWeek.SATURDAY ||
                        d.dayOfWeek == java.time.DayOfWeek.SUNDAY ||
                        d in TradingDayPickerView.CHINESE_HOLIDAYS
                dateLabelTv.text = if (isNonTrading) "  非交易日:" else "  交易日:"
                refreshDateUI()
            }
        }; hotSectorRow.addView(datePicker)
        // 重置：与箭头等高 (dp(20))
        resetBtn = Button(requireContext()).apply { text = "重置"; textSize = 11f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#EF6C00")); setPadding(dp(4),dp(0),dp(4),dp(0)); setMinWidth(0); setMinimumWidth(0); visibility = View.GONE; layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, dp(20)).apply { marginStart = 2 }; setOnClickListener { resetToRecent() } }; hotSectorRow.addView(resetBtn)
        // 主板开关
        val mainBoardSwitch = Switch(requireContext()).apply {
            text = "主板"; textSize = 10f; isChecked = true; setPadding(dp(2),0,0,0); setMinWidth(0); setMinimumWidth(0)
            setTextColor(Color.parseColor("#999999")); layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, dp(20)).apply { marginStart = 2 }
            tag = "mainBoardSwitch"
        }; hotSectorRow.addView(mainBoardSwitch)
        header.addView(hotSectorRow)
        layout.addView(header)

        // v12.0：量化选股按钮行精简为「执行策略 / +添加策略 / 清空报告 / 导出策略报告 / 量化选股报告」
        //（拟合 → 工作台；数据/导入 → 数据 Tab；Agent 分析 → AI 分析 Tab）
        val row2 = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(8,4,8,4); setBackgroundColor(Color.WHITE) }
        scanBtn = Button(requireContext()).apply { text = "执行策略"; textSize = 11f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#E65100")); setPadding(6,6,6,6); setMinWidth(0); setMinimumWidth(0); layoutParams = LayoutParams(0,60,1.1f).apply { marginEnd = 3 }; setOnClickListener { runSelectedStrategies() } }; row2.addView(scanBtn)
        val addCustomBtn = Button(requireContext()).apply { text = "+添加策略"; textSize = 11f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#1565C0")); setPadding(6,6,6,6); setMinWidth(0); setMinimumWidth(0); layoutParams = LayoutParams(0,60,1.1f).apply { marginEnd = 3 }; setOnClickListener { showAddDialog() } }; row2.addView(addCustomBtn)
        val clearReportsBtn = Button(requireContext()).apply { text = "🧹清空报告"; textSize = 11f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#C62828")); setPadding(6,6,6,6); setMinWidth(0); setMinimumWidth(0); layoutParams = LayoutParams(0,60,1.1f).apply { marginEnd = 3 }; setOnClickListener { confirmAndClearReports() } }; row2.addView(clearReportsBtn)
        val exportReportBtn = Button(requireContext()).apply { text = "📋导出策略报告"; textSize = 11f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#455A64")); setPadding(6,6,6,6); setMinWidth(0); setMinimumWidth(0); layoutParams = LayoutParams(0,60,1.2f).apply { marginEnd = 3 }; setOnClickListener { exportStrategyReport() } }; row2.addView(exportReportBtn)
        val scanHistoryBtn = Button(requireContext()).apply { text = "📊量化选股报告"; textSize = 11f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#6A1B9A")); setPadding(6,6,6,6); setMinWidth(0); setMinimumWidth(0); layoutParams = LayoutParams(0,60,1.2f).apply { marginEnd = 3 }; setOnClickListener { showScanHistory() } }; row2.addView(scanHistoryBtn)
        layout.addView(row2)

        val statusRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(16,2,16,4); setBackgroundColor(Color.WHITE) }
        progressBar = ProgressBar(requireContext()).apply { visibility = View.GONE; layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = 8 } }; statusRow.addView(progressBar)
        statusTv = TextView(requireContext()).apply { text = "$strategyCount 个策略已就绪"; textSize = 11f; setTextColor(Color.parseColor("#AAAAAA")) }; statusRow.addView(statusTv)
        layout.addView(statusRow)

        recyclerView = RecyclerView(requireContext()).apply { layoutManager = LinearLayoutManager(context); layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f); setPadding(0,4,0,4); clipToPadding = false }; layout.addView(recyclerView)
        refreshList(); refreshDateUI(); loadHotSectors()
    }

    private fun loadHotSectors() {
        lifecycleScope.launch(Dispatchers.IO) {
            val days = when (selectedHotPeriod) { 0->1; 1->3; 2->10; 3->30; 4->50; 5->100; else->1 }
            val top3Sectors: List<String> = when {
                days == 1 -> {
                    val live = EastMoneyHotSectorSource.conceptSectors
                    if (live.isNotEmpty()) live.map { it.name }.take(3)
                    else try { EastMoneyHotSectorSource().fetchSectorsByTypeDirect(3, 5).map { it.name }.take(3) }
                    catch (_: Exception) { StockDataCenter.getHotSectorsByPeriod(3).take(3) }
                }
                else -> StockDataCenter.getHotSectorsByPeriod(days).take(3)
            }
            if (top3Sectors.isEmpty()) {
                currentHotSectors = (EastMoneyHotSectorSource.conceptSectors + EastMoneyHotSectorSource.industrySectors)
                    .map { it.name }.distinct().sorted().take(3)
            } else {
                val expandedSectors = mutableListOf<String>()
                for (sector in top3Sectors) {
                    val subSectors = try { com.chin.stockanalysis.stock.data.sources.SectorSubDivision.getSubSectors(sector).map { it.name } } catch (_: Exception) { emptyList() }
                    if (subSectors.isNotEmpty()) expandedSectors.addAll(subSectors) else expandedSectors.add(sector)
                }
                currentHotSectors = expandedSectors.distinct()
            }
            if (currentHotSectors.isEmpty()) {
                // API 未就绪时，用 AIHotSectorProvider 作为 fallback
                try {
                    val aiResult = com.chin.stockanalysis.strategy.data.AIHotSectorProvider.getHotSectors(requireContext())
                    currentHotSectors = aiResult.allSectors.take(10)
                } catch (_: Exception) {}
            }
            val hasSubSectors = currentHotSectors.any { sector ->
                try { com.chin.stockanalysis.stock.data.sources.SectorSubDivision.getSubSectors(sector).isNotEmpty() } catch (_: Exception) { false }
            }
            withContext(Dispatchers.Main) {
                statusTv.text = "  \uD83D\uDD25 已加载热门板块(前三子板块): ${currentHotSectors.take(5).joinToString("\u3001")}"
                updateSpinnerLabels(hasSubSectors)
            }
        }
    }

    private fun resetToRecent() { browsingDate = TradingDayPickerView.recentTradingDay(); isBrowsing = false; datePicker.selectedDate = browsingDate; refreshDateUI() }
    private fun refreshDateUI() { resetBtn.visibility = if (isBrowsing) View.VISIBLE else View.GONE }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun isMainBoard(code: String): Boolean = !(code.startsWith("sz300") || code.startsWith("sz301") || code.startsWith("sh688") || code.startsWith("bj"))
    private val sectorLabelCache = object : LinkedHashMap<String, String>(200, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 200
    }
    /** 动态板块映射（从 DB sector_stocks 预载入，stockCode → sectorName） */
    @Volatile
    private var dynamicSectorMap: Map<String, String> = emptyMap()

    private fun getSectorLabel(stockCode: String, stockName: String = ""): String {
        val cacheKey = "$stockCode|$stockName"
        sectorLabelCache[cacheKey]?.let { return it }
        // 从动态 DB 映射查找
        val dynamic = dynamicSectorMap[stockCode]
        if (!dynamic.isNullOrEmpty()) {
            sectorLabelCache[cacheKey] = dynamic
            return dynamic
        }
        sectorLabelCache[cacheKey] = "-"
        return "-"
    }

    /** 后台预载入 stockCode → sectorName 映射（取代硬编码） */
    private fun preloadSectorLabelMap() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val pairs = db.sectorStockDao().getAllStockSectorPairs()
                dynamicSectorMap = pairs.associate { it.stock_code to it.sector_name }
                Log.i("SLF", "预载板块映射: ${dynamicSectorMap.size} 笔")
            } catch (e: Exception) {
                Log.w("SLF", "预载板块映射失败: ${e.message}")
            }
        }
    }
    private fun updateSpinnerLabels(hasSubSectors: Boolean) {
        val s = if (hasSubSectors) "(板块/子板块)" else ""
        val labels = listOf("当日$s", "近三日$s", "近10日$s", "近30日$s", "近50日", "近100日$s")
        val newAdapter = object : ArrayAdapter<String>(requireContext(), android.R.layout.simple_spinner_item, ArrayList(labels)) {
            init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
                val tv = super.getView(pos, cv, parent) as TextView
                tv.textSize = 12f; tv.setTextColor(Color.parseColor("#E65100")); tv.typeface = Typeface.DEFAULT_BOLD; return tv
            }
        }
        hotSectorSpinner.adapter = newAdapter; hotSectorSpinner.setSelection(selectedHotPeriod)
    }
    // hardcodedSubSector 已删除，改用 preloadSectorLabelMap() 从 DB 动态载入

    /** 执行龙头轮动策略 */
    private fun runDragonHeadDip() {
        scanBtn.isEnabled = false; scanBtn.text = "\u23F3"; progressBar.visibility = View.VISIBLE
        statusTv.text = "  🐲 龙头轮动扫描中..."
        android.util.Log.e("DragonHeadDip_UI", "====== 龙头轮动开始执行 ======")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val strategy = com.chin.stockanalysis.strategy.strategies.DragonHeadDipStrategy(requireContext())
                android.util.Log.e("DragonHeadDip_UI", "策略实例创建成功，开始 screen()...")
                val result = strategy.screen()
                android.util.Log.e("DragonHeadDip_UI", "screen() 返回: isSuccess=${result.isSuccess}, ${result.getOrNull()?.let { "命中${it.hitCount}只/扫描${it.totalScanned}只/耗时${it.scanTimeMs}ms" } ?: result.exceptionOrNull()?.message}")
                if (isAdded) {
                    withContext(Dispatchers.Main) {
                        scanBtn.isEnabled = true; scanBtn.text = "执行策略"; progressBar.visibility = View.GONE
                        if (result != null && (result.getOrNull()?.hitCount ?: 0) > 0) {
                            val r = result.getOrNull()!!
                            statusTv.text = "  🐲 龙头轮动: 主板+科创/创业 共${r.hitCount}只 | 耗时${r.scanTimeMs}ms | 扫描${r.totalScanned}只"
                            showDragonHeadDipResult(r)
                        } else if (result.getOrNull() != null) {
                            val r = result.getOrNull()!!
                            statusTv.text = "  🐲 龙头轮动: 无符合条件的标的 | 扫描${r.totalScanned}只 | 耗时${r.scanTimeMs}ms"
                            Toast.makeText(requireContext(), "龙头轮动扫描${r.totalScanned}只，无命中", Toast.LENGTH_SHORT).show()
                        } else {
                            val err = result.exceptionOrNull()
                            statusTv.text = "  🐲 龙头轮动执行失败: ${err?.message}"
                            android.util.Log.e("DragonHeadDip_UI", "执行失败: ${err?.message}", err)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("DragonHeadDip_UI", "异常: ${e.message}", e)
                if (isAdded) withContext(Dispatchers.Main) {
                    scanBtn.isEnabled = true; scanBtn.text = "执行策略"; progressBar.visibility = View.GONE
                    statusTv.text = "  龙头轮动执行失败: ${e.message}"
                }
            }
        }
    }

    /** 直接展示龙头轮动结果（不走 engine 策略过滤） */
    private fun showDragonHeadDipResult(result: com.chin.stockanalysis.strategy.models.ScreeningResult) {
        if (!isAdded || result.signals.isEmpty()) return
        val sv = ScrollView(requireContext())
        val c = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24)
        }
        c.addView(TextView(requireContext()).apply {
            text = "🐲 龙头轮动 (${result.hitCount}只 / ${result.scanTimeMs}ms | 扫描${result.totalScanned}只)"
            textSize = 15f; setTextColor(Color.parseColor("#333333"))
            setTypeface(null, Typeface.BOLD); setPadding(0, 8, 0, 8)
        })
        for (signal in result.signals.sortedByDescending { it.strength }) {
            val card = buildResultCard(signal)
            c.addView(card)
            c.addView(View(requireContext()).apply { setBackgroundColor(Color.parseColor("#E0E0E0")); layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 1); setPadding(0, 8, 0, 8) })
        }
        AlertDialog.Builder(requireContext())
            .setTitle("🐲 龙头轮动结果")
            .setView(sv)
            .setPositiveButton("关闭", null)
            .show()
    }

    /** 构建单个信号卡片 */
    private fun buildResultCard(signal: com.chin.stockanalysis.strategy.models.StrategySignal): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(12, 12, 12, 12); setBackgroundColor(Color.parseColor("#F5F5F5"))
            addView(TextView(requireContext()).apply {
                text = "${signal.stockName}(${signal.stockCode})"
                textSize = 15f; setTextColor(Color.parseColor("#1565C0"))
                setTypeface(null, Typeface.BOLD)
            })
            addView(TextView(requireContext()).apply {
                text = "${signal.category.icon} 强度: ${signal.strength}/100 | ${signal.action.label}"
                textSize = 13f; setTextColor(Color.parseColor("#666666"))
            })
            addView(TextView(requireContext()).apply {
                text = signal.reason
                textSize = 12f; setTextColor(Color.parseColor("#444444")); setPadding(0, 4, 0, 4)
            })
            if (signal.details.isNotEmpty()) {
                val detailText = signal.details.entries.joinToString("\n") { "  • ${it.key}: ${it.value}" }
                addView(TextView(requireContext()).apply {
                    text = detailText; textSize = 11f; setTextColor(Color.parseColor("#888888")); setPadding(0, 4, 0, 0)
                })
            }
        }
    }

    private fun runSelectedStrategies() {
        val eng = engine ?: return
        val nowMs = System.currentTimeMillis()
        val selectedDate = browsingDate.toString(); val sectorLabel = currentHotSectors.take(3).joinToString("\u3001").ifEmpty { "全市场" }
        val withinCacheWindow = (nowMs - lastExecTimeMs) < 600_000L
        val sameConditions = (browsingDate == lastExecDate && selectedHotPeriod == lastExecPeriod)
        if (withinCacheWindow && sameConditions && cachedResults != null) {
            statusTv.text = "  \uD83D\uDCCB 使用快取结果（${(nowMs - lastExecTimeMs) / 1000}秒前）"
            showResults(cachedResults!!); return
        }
        scanBtn.isEnabled = false; scanBtn.text = "\u23F3"; progressBar.visibility = View.VISIBLE
        val today = TradingDayPickerView.recentTradingDay()
        statusTv.text = "  正在执行 $browsingDate（$sectorLabel）..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val snapshots = db.dailySnapshotDao().getByDate(selectedDate)
                if (snapshots.isEmpty()) {
                    if (browsingDate == today && A股TradingHours.a股是否交易中()) { executeRealTime(eng, selectedDate); return@launch }
                    val availableDates = db.dailySnapshotDao().getAvailableDates(5)
                    if (availableDates.isNotEmpty()) {
                        val latestDate = availableDates.first()
                        val latestSnapshots = db.dailySnapshotDao().getByDate(latestDate)
                        if (latestSnapshots.isNotEmpty()) { doExecute(eng, db, latestSnapshots, latestDate, sectorLabel); return@launch }
                    }
                    executeRealTime(eng, selectedDate); return@launch
                }
                doExecute(eng, db, snapshots, selectedDate, sectorLabel)
            } catch (e: Exception) { withContext(Dispatchers.Main) { scanBtn.isEnabled = true; scanBtn.text = "执行策略"; progressBar.visibility = View.GONE; statusTv.text = "  执行失败: ${e.message}" } }
        }
    }

    private suspend fun getMultiDaySnapshots(db: StockDatabase, baseDate: String): List<com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity> {
        if (selectedHotPeriod <= 0) return db.dailySnapshotDao().getByDate(baseDate)
        val days = when (selectedHotPeriod) { 1->3; 2->10; 3->30; 4->50; 5->100; else->1 }
        val allDates = db.dailySnapshotDao().getAvailableDates(days)
        val result = mutableListOf<com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity>()
        for (date in allDates.take(days)) { result.addAll(db.dailySnapshotDao().getByDate(date)) }
        return result.distinctBy { it.code }.take(1000)
    }

    private suspend fun doExecute(eng: StrategyEngine, db: StockDatabase, snapshots: List<com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity>, selectedDate: String, sectorLabel: String) {
        val effectiveSnapshots = if (selectedHotPeriod > 0) getMultiDaySnapshots(db, selectedDate) else snapshots
        for (code in effectiveSnapshots.map { it.code }.distinct()) StockDataCenter.getSectorsByStock(code)

        // 统一构建市场上下文（含用户关注/多周期热门/回弹板块/指数/板块大年）
        val marketCtx = com.chin.stockanalysis.strategy.sector.StrategyMarketContext.build(requireContext(), selectedDate)

        // 1. 当前热门板块股票
        val sectorStockCodes = if (currentHotSectors.isEmpty()) emptySet()
        else { val codes = mutableSetOf<String>(); for (name in currentHotSectors) codes.addAll(db.sectorStockDao().getStockCodesBySector(name)); codes }

        // 2. 用户关注板块股票（从统一上下文获取）
        val userFocusCodes = if (marketCtx.userFocusSectors.isEmpty()) emptySet()
        else { val codes = mutableSetOf<String>(); for (name in marketCtx.userFocusSectors) codes.addAll(db.sectorStockDao().getStockCodesBySector(name)); codes }

        // 3. 回弹板块股票（从统一上下文获取）
        val bounceSectors = marketCtx.bounceSectors
        val bounceCodes = if (bounceSectors.isEmpty()) emptySet()
        else { val codes = mutableSetOf<String>(); for (b in bounceSectors.take(5)) codes.addAll(db.sectorStockDao().getStockCodesBySector(b.sectorName)); codes }

        // 合并股票池：热门 + 用户关注 + 回弹
        val allSectorCodes = sectorStockCodes + userFocusCodes + bounceCodes

        val onlyMainBoard = (view?.findViewWithTag<Switch>("mainBoardSwitch")?.isChecked == true)
        val feed = com.chin.stockanalysis.strategy.data.StrategyDataFeed(requireContext())
        val allStocks = feed.convertSnapshots(effectiveSnapshots, com.chin.stockanalysis.strategy.data.StrategyDataFeed.DataFeedConfig(onlyMainBoard = onlyMainBoard))
        val stockList = if (allSectorCodes.isEmpty()) allStocks else allStocks.filter { it.code in allSectorCodes }

        val results = mutableListOf<ScreeningResult>()
        for (s in eng.getStrategies()) {
            if (!eng.isEnabled(s.id)) continue
            if (s.id == "ai_prediction") continue
            try {
                s.screenWithData(stockList).getOrNull()?.let { raw ->
                    // 后处理：用户关注板块 + 回弹板块 额外加权（使用统一上下文方法）
                    val boostedSignals = raw.signals.map { signal ->
                        var bonus = 0
                        // 用户关注板块加成
                        bonus += marketCtx.getFocusBoostForStock(signal.stockName)
                        // 回弹板块加成（回调天数越多加分越多：1天+1, 2天+2, 3天+3...）
                        bonus += marketCtx.getBounceBoostForStock(signal.stockName)
                        if (bonus > 0) signal.copy(strength = (signal.strength + bonus).coerceAtMost(100))
                        else signal
                    }.sortedByDescending { it.strength }
                    results.add(raw.copy(signals = boostedSignals))
                }
            } catch (e: Exception) { Log.w("SLF", "策略 ${s.id} 异常: ${e.message}") }
        }
        CrossTabBus.postStrategyResults(results)
        cachedResults = results
        lastExecTimeMs = System.currentTimeMillis()
        lastExecDate = browsingDate
        lastExecPeriod = selectedHotPeriod
        // 保存板块上下文供 AI 选股使用
        lastSectorContext = marketCtx.toAiSectorContext()

        val boostInfo = if (userFocusCodes.isNotEmpty() || bounceCodes.isNotEmpty()) " · 关注${userFocusCodes.size}只·回弹${bounceCodes.size}只" else ""
        if (isAdded) { withContext(Dispatchers.Main) { scanBtn.isEnabled = true; scanBtn.text = "执行策略"; progressBar.visibility = View.GONE; statusTv.text = "  已完成 · $selectedDate（$sectorLabel）$boostInfo"; saveBacktestData(results); showResults(results) } }
        else { pendingResults = results }
    }

    private fun executeRealTime(eng: StrategyEngine, selectedDate: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val rts = screener?.scanFullMarket() ?: emptyList()
                val results = mutableListOf<ScreeningResult>()
                if (rts.isNotEmpty()) { for (s in eng.getStrategies()) { if (!eng.isEnabled(s.id)) continue; if (s.id == "ai_prediction") continue; try { s.screenWithData(rts).getOrNull()?.let { results.add(it) } } catch (e: Exception) { Log.w("SLF", "实时 ${s.id}: ${e.message}") } } }
                cachedResults = results.takeIf { it.isNotEmpty() }
                lastExecTimeMs = System.currentTimeMillis(); lastExecDate = browsingDate; lastExecPeriod = selectedHotPeriod
                if (isAdded) { withContext(Dispatchers.Main) { scanBtn.isEnabled = true; scanBtn.text = "执行策略"; progressBar.visibility = View.GONE; statusTv.text = "  已完成 · $selectedDate (实时)" + if (rts.isEmpty()) " \u26A0\uFE0F 扫描无数据" else ""; saveBacktestData(results); showResults(results) } }
                else { pendingResults = results }
            } catch (e: Exception) { if (isAdded) withContext(Dispatchers.Main) { scanBtn.isEnabled = true; scanBtn.text = "执行策略"; progressBar.visibility = View.GONE; statusTv.text = "  执行失败: ${e.message}" } }
        }
    }

    private fun showResults(results: List<ScreeningResult>) {
        if (!isAdded) return
        val totalHits = results.sumOf { it.hitCount }; val totalScanned = results.sumOf { it.totalScanned }
        if (results.isEmpty() || totalHits == 0) { 
            statusTv.text = "  \u26A0\uFE0F 扫描${totalScanned}只，未产生命中信号"
            AlertDialog.Builder(requireContext()).setTitle("策略执行结果").setMessage("扫描 $totalScanned 只股票，未产生命中信号。\n\n可能原因:\n• 当前市场情绪偏弱\n• 策略阈值较高\n• 数据源未就绪").setPositiveButton("确定", null).show()
            return 
        }
        showResultsDialog(results)
    }

    private fun showResultsDialog(results: List<ScreeningResult>) {
        if (!isAdded || results.isEmpty()) return
        val sv = ScrollView(requireContext()); val c = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        val rm = results.associateBy { it.strategyId }; val all = engine?.getStrategies()?.filter { engine!!.isEnabled(it.id) && it.id != "ai_prediction" } ?: emptyList()
        for (r in all.map { s -> rm[s.id] ?: ScreeningResult(strategyId = s.id, strategyName = s.name, category = s.category, signals = emptyList(), totalScanned = 0, scanTimeMs = 0) }) {
            c.addView(TextView(requireContext()).apply { text = "${r.category.icon} ${r.strategyName}  (${r.hitCount}只 / ${r.scanTimeMs}ms)"; textSize = 15f; setTextColor(Color.parseColor("#333333")); setTypeface(null, Typeface.BOLD); setPadding(0, 16, 0, 8); setOnClickListener { if (r.signals.isNotEmpty()) openResultDialog(r) } })
            if (r.signals.isEmpty()) { c.addView(TextView(requireContext()).apply { text = "  \u26A0\uFE0F 无命中信号"; textSize = 12f; setTextColor(Color.parseColor("#999999")); setPadding(0, 0, 0, 8) }); continue }
            val t = TableLayout(requireContext()).apply { isStretchAllColumns = true }
            val hr = TableRow(requireContext()); for (h in listOf("名称", "子板块", "代码", "强度", "价格", "涨幅")) hr.addView(TextView(requireContext()).apply { text = h; textSize = 11f; setTextColor(Color.parseColor("#999999")); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(4, 4, 4, 4) }); t.addView(hr)
            for (s in r.signals.distinctBy { it.stockCode }.take(10)) {
                val row = TableRow(requireContext()); row.setOnClickListener { StockDetailNavigator.navigateFromFragment(this, s.stockCode, s.stockName, s.currentPrice, s.changePercent, getSectorLabel(s.stockCode, s.stockName)) }
                val strengthColor = when { s.strength >= 80 -> Color.parseColor("#E65100"); s.strength >= 60 -> Color.parseColor("#2E7D32"); else -> Color.parseColor("#666666") }
                row.addView(TextView(requireContext()).apply { text = s.stockName; textSize = 11f; setTextColor(Color.parseColor("#222222")); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER_VERTICAL; setPadding(2, 6, 2, 6) })
                row.addView(TextView(requireContext()).apply { text = getSectorLabel(s.stockCode, s.stockName); textSize = 9f; setTextColor(Color.parseColor("#1565C0")); gravity = Gravity.CENTER; setPadding(2, 6, 2, 6) })
                row.addView(TextView(requireContext()).apply { text = s.stockCode.takeLast(6); textSize = 11f; setTextColor(strengthColor); gravity = Gravity.CENTER; setPadding(2, 6, 2, 6) })
                row.addView(TextView(requireContext()).apply { text = "${s.strength}%"; textSize = 11f; setTextColor(strengthColor); gravity = Gravity.CENTER; setPadding(2, 6, 2, 6) })
                row.addView(TextView(requireContext()).apply { text = "%.2f".format(s.currentPrice); textSize = 12f; setTextColor(Color.parseColor("#E53935")); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(2, 6, 2, 6) })
                row.addView(TextView(requireContext()).apply { text = "${if (s.changePercent >= 0) "+" else ""}${"%.2f".format(s.changePercent)}%"; textSize = 12f; setTextColor(if (s.changePercent >= 0) Color.parseColor("#E53935") else Color.parseColor("#43A047")); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(2, 6, 2, 6) })
                t.addView(row)
            }; c.addView(t)
        }
        c.addView(View(requireContext()).apply { layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 2).apply { topMargin = 16; bottomMargin = 8 }; setBackgroundColor(Color.parseColor("#DDDDDD")) })
        c.addView(TextView(requireContext()).apply { text = "\uD83E\uDD16 AI 量化选股（多策略+新闻因子+周期轮动）"; textSize = 16f; setTextColor(Color.parseColor("#1565C0")); setTypeface(null, Typeface.BOLD); setPadding(0, 8, 0, 8) })
        val ail = TextView(requireContext()).apply { text = "  \u23F3 AI 正在分析中，请稍候..."; textSize = 12f; setTextColor(Color.parseColor("#999999")) }; c.addView(ail)
        val dialogTitle = when {
            isBrowsing && selectedHotPeriod == 0 -> "扫描结果 ($browsingDate)"
            selectedHotPeriod > 0 -> { val label = when(selectedHotPeriod){1->"近三日";2->"近10日";3->"近30日";4->"近50日";5->"近100日"; else->"当日"}; "扫描结果 ($label)" }
            else -> "扫描结果 ($browsingDate)"
        }
        sv.addView(c)
        val dialog = AlertDialog.Builder(requireContext()).setTitle(dialogTitle).setView(sv).setPositiveButton("关闭") { d, _ -> d.dismiss() }.create()
        dialog.show()
        dialog.window?.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply { gravity = Gravity.END or Gravity.BOTTOM }
        lifecycleScope.launch {
            try {
                val ai = AIPredictionEngine(requireContext())
                val pr = ai.predict(
                    results,
                    browsingDate.toString(),
                    useEnhancedAi = true,
                    sectorContext = lastSectorContext ?: com.chin.stockanalysis.strategy.predict.AIPredictionEngine.SectorContext()
                )
                requireActivity().runOnUiThread {
                    if (pr != null && pr.topPicks.isNotEmpty()) {
                        ail.text = ""
                        c.addView(TextView(requireContext()).apply { text = "  \uD83D\uDCCB 方案${pr.mode}: ${pr.modeReason}"; textSize = 11f; setTextColor(Color.parseColor("#E65100")); setPadding(0, 4, 0, 8) })
                        c.addView(TextView(requireContext()).apply { text = "  \uD83C\uDF0C 大盘方向: ${pr.marketDirection}"; textSize = 11f; setTextColor(if (pr.marketDirection == "BULLISH") Color.parseColor("#E53935") else if (pr.marketDirection == "BEARISH") Color.parseColor("#43A047") else Color.parseColor("#EF6C00")); setPadding(0, 4, 0, 4) })
                        c.addView(TextView(requireContext()).apply { text = "  \uD83D\uDCCA 市场判断: ${pr.marketOutlook}"; textSize = 11f; setTextColor(Color.parseColor("#666666")); setPadding(0, 0, 0, 4) })
                        c.addView(TextView(requireContext()).apply { text = "  \u26A0 ${pr.riskWarning}"; textSize = 11f; setTextColor(Color.parseColor("#EF6C00")); setPadding(0, 0, 0, 8) })
                        // 显示板块加权信息
                        val sc = lastSectorContext
                        if (sc != null && (sc.todayHotSectors.isNotEmpty() || sc.bounceSectors.isNotEmpty())) {
                            val sectorInfo = buildString {
                                if (sc.todayHotSectors.isNotEmpty()) append("今日热门: ${sc.todayHotSectors.take(5).joinToString("、")} | ")
                                if (sc.bounceSectors.isNotEmpty()) append("回弹板块: ${sc.bounceSectors.take(3).joinToString("、") { it.sectorName }}")
                            }
                            c.addView(TextView(requireContext()).apply { text = "  \uD83D\uDD25 $sectorInfo"; textSize = 10f; setTextColor(Color.parseColor("#1565C0")); setPadding(0, 0, 0, 8) })
                        }
                        val tpTable = TableLayout(requireContext()).apply { isStretchAllColumns = true }
                        val tpHr = TableRow(requireContext())
                        for (h in listOf("排名", "名称", "代码", "综分", "概率", "建议")) tpHr.addView(TextView(requireContext()).apply { text = h; textSize = 10f; setTextColor(Color.parseColor("#999999")); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(2, 4, 2, 4) })
                        tpTable.addView(tpHr)
                        for (p in pr.topPicks) {
                            val tpRow = TableRow(requireContext())
                            for (cell in listOf("#${p.rank}", p.stockName, p.stockCode.takeLast(6), "${p.compositeScore}", "${p.upProbability}%", p.actionSuggestion)) tpRow.addView(TextView(requireContext()).apply { text = cell; textSize = 10f; setTextColor(Color.parseColor("#333333")); gravity = Gravity.CENTER; setPadding(2, 4, 2, 4) })
                            tpTable.addView(tpRow)
                        }
                        c.addView(tpTable)
                    } else {
                        ail.text = "  \u26A0\uFE0F AI 预测暂不可用"
                    }
                }
            } catch (e: Exception) {
                requireActivity().runOnUiThread { ail.text = "  \u26A0\uFE0F AI 预测失败: ${e.message?.take(30)}" }
            }
        }
    }

    private fun refreshList() {
        engine?.let { eng ->
            // 分组：平台策略（默认展开）+ 超短/短/中/长（默认折叠）
            val items = mutableListOf<SectionItem>()
            val platformCases = UseCaseExecution.listPlatformUseCases()
            if (platformCases.isNotEmpty()) {
                items.add(SectionItem(
                    key = "platform",
                    title = "🛡 平台策略",
                    isPlatform = true,
                    useCases = platformCases
                ))
            }
            HoldingPeriod.values().forEach { period ->
                val list = eng.getStrategiesByPeriod(period)
                if (list.isNotEmpty()) {
                    items.add(SectionItem(
                        key = period.name,
                        title = period.label,
                        isPlatform = false,
                        strategies = list
                    ))
                }
            }
            val resultsMap = cachedResults?.associateBy { it.strategyId } ?: emptyMap()
            adapter = GroupedStrategyAdapter(items, collapsedKeys, ::onStrategyClick, ::onStrategyToggle, ::onToggleSection, ::onRunUseCase, resultsMap)
            recyclerView.adapter = adapter
        }
    }
    private fun onStrategyClick(s: Strategy) { StrategyDetailFragment().apply { this.strategy = s; onSave = { u -> engine?.apply { removeStrategy(u.id); registerStrategy(u); refreshList(); strategyCount = engine?.getStrategies()?.size ?: strategyCount } } }.show(parentFragmentManager, "detail") }
    private fun onStrategyToggle(s: Strategy) { engine?.setEnabled(s.id, !engine!!.isEnabled(s.id)) }
    private fun onToggleSection(item: SectionItem) {
        if (collapsedKeys.contains(item.key)) collapsedKeys.remove(item.key) else collapsedKeys.add(item.key)
        adapter?.notifyDataSetChanged()
    }

    /**
     * 平台策略：点击运行按钮 → 统一 UseCaseExecution 入口执行 pipeline(DAG)。
     * 与 一键建仓 / 实仓 / AI 对话框 共用同一套 usecase 执行机制。
     */
    private fun onRunUseCase(info: UseCaseLoader.UseCaseInfo) {
        val ctx = requireContext()
        showUseCaseProgress(ctx, "正在初始化 ${info.name}...")
        lifecycleScope.launch(Dispatchers.IO) {
            val report = UseCaseExecution.runAndSummarize(ctx, info.id) { pipelineName, nodeName ->
                showUseCaseProgressMsg("执行中：$pipelineName → $nodeName")
            }
            withContext(Dispatchers.Main) {
                dismissUseCaseProgress()
                showUseCaseResult(ctx, info.name, report)
            }
        }
    }

    private fun showUseCaseProgress(ctx: android.content.Context, msg: String) {
        requireActivity().runOnUiThread {
            try { useCaseProgressDialog.dismiss() } catch (e: Exception) {}
            useCaseProgressText = TextView(ctx).apply {
                text = msg; textSize = 14f; setTextColor(Color.parseColor("#333333"))
                setPadding(dp(24), dp(20), dp(24), dp(20))
            }
            useCaseProgressDialog = AlertDialog.Builder(ctx)
                .setTitle("🛡 平台策略")
                .setView(useCaseProgressText)
                .setCancelable(false)
                .create()
            useCaseProgressDialog.show()
        }
    }

    private fun showUseCaseProgressMsg(msg: String) {
        requireActivity().runOnUiThread {
            try { useCaseProgressText.text = msg } catch (e: Exception) {}
        }
    }

    private fun dismissUseCaseProgress() {
        requireActivity().runOnUiThread {
            try { useCaseProgressDialog.dismiss() } catch (e: Exception) {}
        }
    }

    private fun showUseCaseResult(ctx: android.content.Context, title: String, message: String) {
        requireActivity().runOnUiThread {
            val sv = ScrollView(ctx).apply {
                addView(TextView(ctx).apply {
                    text = message; textSize = 13f; setTextColor(Color.parseColor("#333333"))
                    setPadding(dp(20), dp(16), dp(20), dp(16))
                })
            }
            AlertDialog.Builder(ctx).setTitle(title).setView(sv).setPositiveButton("知道了", null).show()
        }
    }

    /** 一个分组：平台策略 或 超短/短/中/长 周期分组 */
    private data class SectionItem(
        val key: String,               // "platform" 或 period.name（折叠状态标识）
        val title: String,             // 分组标题
        val isPlatform: Boolean,
        val strategies: List<Strategy> = emptyList(),
        val useCases: List<UseCaseLoader.UseCaseInfo> = emptyList()
    )

    /**
     * 策略列表适配器：平台策略（默认展开）+ 超短/短/中/长（默认折叠）。
     * 沙盒只读模式：保留点击策略执行与启用开关（参与执行过滤），不提供任何买入按钮。
     * 周期分组标题使用文字标签（超短线 / 短线 / 中线 / 长线）；平台策略卡片提供「运行此方案」按钮。
     */
    private inner class GroupedStrategyAdapter(
        private val items: List<SectionItem>,
        private val collapsedKeys: MutableSet<String>,
        private val onItemClick: (Strategy) -> Unit,
        private val onToggle: (Strategy) -> Unit,
        private val onToggleSection: (SectionItem) -> Unit,
        private val onRunUseCase: (UseCaseLoader.UseCaseInfo) -> Unit,
        private val resultsMap: Map<String, ScreeningResult> = emptyMap()
    ) : RecyclerView.Adapter<GroupedStrategyAdapter.SectionVH>() {

        // 每个分组一个大容器 view（section）：组标题 + 内容卡片
        private val visibleSections = items

        override fun getItemCount(): Int = visibleSections.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SectionVH =
            SectionVH(makeSection(parent))

        override fun onBindViewHolder(holder: SectionVH, position: Int) {
            val item = visibleSections[position]
            val section = holder.section
            section.removeAllViews()
            val ctx = section.context
            val collapsed = collapsedKeys.contains(item.key)
            val count = if (item.isPlatform) item.useCases.size else item.strategies.size
            section.addView(makeSectionHeader(ctx, item, count, collapsed))
            if (!collapsed) {
                if (item.isPlatform) {
                    item.useCases.forEach { info -> section.addView(makePlatformCard(ctx, info)) }
                } else {
                    item.strategies.forEach { strategy -> section.addView(makeStrategyCard(ctx, strategy)) }
                }
            }
        }

        private fun makeSection(parent: ViewGroup): LinearLayout = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            elevation = 4f
            setPadding(0, dp(4), 0, dp(8))
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(12), dp(6), dp(12), dp(6))
            }
        }

        private fun makeSectionHeader(
            ctx: android.content.Context,
            item: SectionItem,
            count: Int,
            collapsed: Boolean
        ): TextView = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(8), dp(4), dp(8), dp(2))
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            textSize = 14f
            setTextColor(if (item.isPlatform) Color.parseColor("#8D6E63") else Color.parseColor("#1A1A2E"))
            setTypeface(null, Typeface.BOLD)
            setBackgroundColor(if (item.isPlatform) Color.parseColor("#FBE9E7") else Color.parseColor("#EDEFF5"))
            text = (if (collapsed) "▶ " else "▼ ") + item.title + "  ·  ${count} 个" +
                if (item.isPlatform) "方案" else "策略"
            setOnClickListener { onToggleSection(item) }
        }

        private fun makePlatformCard(ctx: android.content.Context, info: UseCaseLoader.UseCaseInfo): LinearLayout {
            val highlight = info.id == "complete_closed_loop"
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(if (highlight) Color.parseColor("#FFF8E1") else Color.parseColor("#F8F9FC"))
                setPadding(20, 16, 20, 16)
                elevation = 2f
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(8), dp(4), dp(8), dp(4))
                }
            }
            card.addView(TextView(ctx).apply {
                text = (if (highlight) "🌟 " else "📊 ") + info.name + (if (highlight) "（豆包体系）" else "")
                textSize = 15f; setTextColor(Color.parseColor("#222222")); setTypeface(null, Typeface.BOLD)
            })
            card.addView(TextView(ctx).apply {
                text = info.description
                textSize = 12f; setTextColor(Color.parseColor("#888888"))
                setPadding(28, 6, 0, 4); maxLines = 2
            })
            card.addView(TextView(ctx).apply {
                text = "📚 ${info.stepCount} 步流程 · pipeline(DAG) 构造"
                textSize = 11f; setTextColor(Color.parseColor("#AAAAAA"))
                setPadding(28, 0, 0, 8)
            })
            card.addView(Button(ctx).apply {
                text = "🚀 运行此方案"
                textSize = 12f; isAllCaps = false; setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor(if (highlight) "#E65100" else "#1E88E5"))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(2) }
                setOnClickListener { onRunUseCase(info) }
            })
            return card
        }

        private fun makeStrategyCard(ctx: android.content.Context, strategy: Strategy): LinearLayout {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#F8F9FC"))
                setPadding(20, 16, 20, 16)
                elevation = 2f
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(8), dp(4), dp(8), dp(4))
                }
            }
                // header row: icon + name + source badge + enable switch
                val header = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                }
                val icon = TextView(ctx).apply {
                    text = strategy.category.icon; textSize = 20f
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = 8 }
                }
                header.addView(icon)
                val nameTv = TextView(ctx).apply {
                    text = strategy.name; textSize = 16f; setTextColor(Color.parseColor("#222222"))
                    setTypeface(null, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                header.addView(nameTv)
                val badge = TextView(ctx).apply {
                    text = strategy.source.label; textSize = 10f; setTextColor(Color.WHITE)
                    setBackgroundColor(if (strategy.source == StrategySource.BUILTIN) Color.parseColor("#4CAF50") else Color.parseColor("#FF9800"))
                    setPadding(8, 2, 8, 2); gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = 8 }
                }
                header.addView(badge)
                val sw = androidx.appcompat.widget.SwitchCompat(ctx).apply {
                    isChecked = true
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    setOnCheckedChangeListener { _, _ -> onToggle(strategy) }
                }
                header.addView(sw)
                card.addView(header)

                // description
                val desc = TextView(ctx).apply {
                    text = strategy.description; textSize = 12f; setTextColor(Color.parseColor("#888888"))
                    setPadding(28, 6, 0, 4); maxLines = 2
                }
                card.addView(desc)

                // weight factors preview
                if (strategy.weightFactors.isNotEmpty()) {
                    val wtext = strategy.weightFactors.joinToString("  ") { "${it.label} ${it.weight}%" }
                    val wpreview = TextView(ctx).apply {
                        text = wtext; textSize = 11f; setTextColor(Color.parseColor("#AAAAAA"))
                        setPadding(28, 0, 0, 6)
                    }
                    card.addView(wpreview)
                }

                // ── Top 3 选股结果（沙盒只读展示） ──
                val screeningResult = resultsMap[strategy.id]
                if (screeningResult != null && screeningResult.signals.isNotEmpty()) {
                    val topSignals = screeningResult.topN(3)
                    val resultContainer = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(28, 6, 0, 4)
                    }
                    // 标题行：命中数 + 扫描数
                    val resultHeader = TextView(ctx).apply {
                        text = "📋 Top ${topSignals.size} | 命中 ${screeningResult.hitCount}/${screeningResult.totalScanned}"
                        textSize = 11f; setTextColor(Color.parseColor("#333333"))
                        setTypeface(null, Typeface.BOLD)
                        setPadding(0, 4, 0, 2)
                    }
                    resultContainer.addView(resultHeader)
                    // 每只股票一行
                    for (signal in topSignals) {
                        val stockRow = LinearLayout(ctx).apply {
                            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                            setPadding(0, 2, 0, 2)
                        }
                        val emojiTv = TextView(ctx).apply {
                            text = signal.emoji; textSize = 12f
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = 4 }
                        }
                        stockRow.addView(emojiTv)
                        val nameTv = TextView(ctx).apply {
                            text = signal.stockName
                            textSize = 12f; setTextColor(Color.parseColor("#222222"))
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        }
                        stockRow.addView(nameTv)
                        val codeTv = TextView(ctx).apply {
                            text = signal.stockCode.takeLast(6)
                            textSize = 10f; setTextColor(Color.parseColor("#999999"))
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = 8 }
                        }
                        stockRow.addView(codeTv)
                        val strengthTv = TextView(ctx).apply {
                            text = "${signal.strength}%"
                            textSize = 11f; setTextColor(Color.parseColor("#E65100"))
                            setTypeface(null, Typeface.BOLD)
                        }
                        stockRow.addView(strengthTv)
                        resultContainer.addView(stockRow)
                    }
                    card.addView(resultContainer)
                }

                card.setOnClickListener { onItemClick(strategy) }
            return card
        }

        inner class SectionVH(val section: LinearLayout) : RecyclerView.ViewHolder(section)
    }
    private fun openResultDialog(result: ScreeningResult) { StrategyResultDialogFragment().apply { this.result = result; onAskQuestion = { q -> val ctx = buildString { appendLine("基于以下策略扫描结果，请回答用户问题："); appendLine("策略: ${result.strategyName} | 扫描: ${result.totalScanned}只 | 命中: ${result.hitCount}只"); for ((i, s) in result.signals.take(10).withIndex()) appendLine("| ${i + 1} | ${s.stockName} | ${s.stockCode.takeLast(6)} | ${s.strength}% | ${"%.2f".format(s.currentPrice)} | ${"%.2f".format(s.changePercent)}% |"); appendLine(); appendLine("用户问题: $q") }; if (activity is MainActivity) (activity as MainActivity).switchToChatAndSend(ctx) else Toast.makeText(requireContext(), "提问已记录: $q", Toast.LENGTH_SHORT).show() } }.show(parentFragmentManager, "result") }
    private fun showAddDialog() { val name = EditText(requireContext()).apply { hint = "策略名称"; setSingleLine() }; val desc = EditText(requireContext()).apply { hint = "策略描述"; setSingleLine() }; AlertDialog.Builder(requireContext()).setTitle("添加自定义策略").setView(LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 8); addView(name, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = 12 }); addView(desc, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) }).setPositiveButton("创建") { _, _ -> val n = name.text.toString().trim(); if (n.isNotBlank()) { val id = "custom_${System.currentTimeMillis()}"; engine?.registerStrategy(object : Strategy { override val id = id; override var name = n; override var description = desc.text.toString().trim().ifEmpty { "自定义策略" }; override val category = StrategyCategory.CUSTOM; override val config = StrategyConfig.fullMarket(20); override var weightFactors = listOf(WeightFactor("default", "综合评分", 100, "默认权重")); override val source = StrategySource.USER_CUSTOM; override suspend fun screen() = Result.success(ScreeningResult(strategyId = id, strategyName = n, category = StrategyCategory.CUSTOM, signals = emptyList(), totalScanned = 0, scanTimeMs = 0)); override suspend fun isAvailable() = false }); refreshList(); strategyCount = engine?.getStrategies()?.size ?: strategyCount } }.setNegativeButton("取消", null).show() }
    /** 导出策略报告 */
    private fun exportStrategyReport() {
        val eng = engine ?: return
        statusTv.text = "  \uD83D\uDCCB 正在导出策略报告..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val sb = StringBuilder()
                sb.appendLine("\uD83D\uDCCB 策略配置报告")
                sb.appendLine("导出时间: ${java.time.LocalDate.now()}")
                sb.appendLine()

                for (strategy in eng.getStrategies().filter { eng.isEnabled(it.id) }) {
                    sb.appendLine("━━ ${strategy.name} (${strategy.id}) ━━")
                    sb.appendLine("  类别: ${strategy.category.label}")
                    sb.appendLine("  权重因子:")
                    for (f in strategy.weightFactors) { sb.appendLine("    - ${f.label}: ${f.weight}%") }
                    val snapshots = try { db.strategyWeightSnapshotDao().getByStrategy(strategy.id) } catch (_: Exception) { emptyList() }
                    if (snapshots.isNotEmpty()) {
                        val latest = snapshots.first()
                        sb.appendLine("  最近拟合: ${latest.date} | 命中: ${latest.hitCount}")
                    }
                    sb.appendLine()
                }

                val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val fileName = "StockAnalysis_strategy_report_${java.time.LocalDate.now()}.txt"
                val file = java.io.File(dir, fileName)
                file.writeText(sb.toString())
                withContext(Dispatchers.Main) { statusTv.text = "  \u2705 已导出策略报告"; Toast.makeText(requireContext(), "已保存到: Downloads/$fileName", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { statusTv.text = "  导出失败: ${e.message?.take(30)}"; Toast.makeText(requireContext(), "导出失败: ${e.message}", Toast.LENGTH_LONG).show() } }
        }
    }

    private fun saveBacktestData(results: List<ScreeningResult>) { lifecycleScope.launch { try {
        val ctx = requireContext()
        val db = StockDatabase.getInstance(ctx)
        val be = com.chin.stockanalysis.strategy.backtest.BacktestEngine(ctx)
        for (r in results) be.savePredictions(r.strategyId, r.strategyName, r)
        // 同时保存到 dailyPeriodResultDao（供 showScanHistory 查询）
        val top3Json = org.json.JSONArray(results.filter { it.signals.isNotEmpty() }.flatMap { res ->
            res.signals.take(3).map { s -> org.json.JSONObject().apply { put("name", s.stockName); put("code", s.stockCode); put("score", s.strength) } }
        }).toString()
        val allCodes = org.json.JSONArray(results.flatMap { it.signals.map { it.stockCode } }).toString()
        db.dailyPeriodResultDao().insert(com.chin.stockanalysis.strategy.trade.DailyPeriodResultEntity(
            strategyId = "STRATEGY_SCAN", strategyName = "量化选股",
            tradeDate = browsingDate.toString(), periodDays = 1,
            stockCodesJson = allCodes, stockCount = results.sumOf { it.hitCount },
            newsStrengthScore = 0, rotationPenalty = 0, mainBoardFilter = true,
            filteredCodesJson = "[]", filteredReasonJson = "[]",
            finalTop3Json = top3Json, aiSelectionReason = "",
            createdAt = System.currentTimeMillis()))
    } catch (e: Exception) { Log.w("SLF", "保存预测失败: ${e.message}") } } }

    /** 清空报告确认 */
    private fun confirmAndClearReports() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("清空报告")
            .setMessage("确定要清空所有量化报告记录吗？此操作不可恢复。")
            .setPositiveButton("确定清空") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val db = StockDatabase.getInstance(requireContext())
                        val entities = db.dailyPeriodResultDao().getRecent(1000)
                        for (e in entities) {
                            try { db.dailyPeriodResultDao().deleteByDate(e.tradeDate) } catch (_: Exception) {}
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "已清空 ${entities.size} 笔报告", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "清空失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showScanHistory() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val entities = db.dailyPeriodResultDao().getRecent(100).filter { it.strategyId == "STRATEGY_SCAN" }.sortedByDescending { it.tradeDate }
                if (entities.isEmpty()) { withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "暂无量化选股历史报告", Toast.LENGTH_SHORT).show() }; return@launch }
                withContext(Dispatchers.Main) {
                    val sb = StringBuilder()
                    sb.appendLine("\uD83D\uDCCA 量化选股历史报告 (共 ${entities.size} 条)"); sb.appendLine()
                    for (entity in entities) {
                        val codes = try { org.json.JSONArray(entity.stockCodesJson) } catch (_: Exception) { org.json.JSONArray() }
                        sb.appendLine("\u2501\u2501\u2501 ${entity.tradeDate} \u2501\u2501\u2501")
                        sb.appendLine("  热门板块: ${entity.strategyName}")
                        sb.appendLine("  共 ${codes.length()} 只股票命中信号")
                        try {
                            val top3 = org.json.JSONArray(entity.finalTop3Json)
                            if (top3.length() > 0) {
                                sb.appendLine("  精选Top:")
                                for (i in 0 until minOf(top3.length(), 10)) {
                                    val obj = top3.optJSONObject(i) ?: continue
                                    sb.appendLine("    ${i+1}. ${obj.optString("name")}(${obj.optString("code").takeLast(6)}) 得分:${obj.optInt("score")}")
                                }
                            }
                        } catch (_: Exception) {}
                        sb.appendLine()
                    }
                    AlertDialog.Builder(requireContext()).setTitle("量化选股报告").setMessage(sb.toString()).setPositiveButton("关闭", null).show()
                }
            } catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "加载失败: ${e.message}", Toast.LENGTH_SHORT).show() } }
        }
    }
    override fun onResume() { super.onResume(); loadHotSectors(); pendingResults?.let { showResults(it); pendingResults = null } }
    override fun onDestroyView() { super.onDestroyView(); engine?.cancelScan() }
}