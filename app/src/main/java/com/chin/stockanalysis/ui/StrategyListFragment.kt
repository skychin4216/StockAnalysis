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
import com.chin.stockanalysis.strategy.backtest.StrategySelfTuner
import com.chin.stockanalysis.strategy.backtest.WeightCalibrator
import com.chin.stockanalysis.strategy.data.StockScreener
import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.models.WeightFactor
import com.chin.stockanalysis.strategy.predict.AIPredictionEngine
import com.chin.stockanalysis.strategy.strategies.*
import com.chin.stockanalysis.strategy.ui.StrategyAdapter
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
    private lateinit var adapter: StrategyAdapter
    private lateinit var statusTv: TextView
    private lateinit var scanBtn: Button
    private lateinit var tuneBtn: Button
    private lateinit var progressBar: ProgressBar

    private lateinit var dateLabelTv: TextView
    private lateinit var datePicker: TradingDayPickerView
    private lateinit var resetBtn: Button
    private lateinit var hotSectorSpinner: Spinner
    private var selectedHotPeriod = 0
    private var browsingDate: LocalDate = TradingDayPickerView.recentTradingDay()
    private var isBrowsing = false

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
        // 確保熱門板塊調度器已啟動 (不管 MarketHotFragment 有沒有建立)
        EastMoneyHotSectorSource.startPoolScheduler(lifecycleScope)
        StrategyEngineHolder.init(ctx)
        engine = StrategyEngineHolder.get()
        strategyCount = engine?.getStrategies()?.size ?: 8
        // 初始化 StockScreener（實時掃描用）
        val repo = StockDataSourceFactory.createDefaultRepository(ctx)
        screener = StockScreener(repo)
        lifecycleScope.launch(Dispatchers.IO) {
            engine?.getStrategies()?.forEach { strategy ->
                StrategySelfTuner.loadLatestTunedWeights(requireContext(), strategy.id)?.let { tuned ->
                    strategy.weightFactors = tuned; Log.i("SLF", "加载调优权重: ${strategy.id}")
                }
            }
        }
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
            text = "📌 热门"; textSize = 13f; setTextColor(Color.parseColor("#E65100")); setTypeface(null, Typeface.BOLD); setPadding(0, 0, 4, 0)
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

        val row2 = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(8,4,8,4); setBackgroundColor(Color.WHITE) }
        scanBtn = Button(requireContext()).apply { text = "执行策略"; textSize = 11f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#E65100")); setPadding(6,6,6,6); setMinWidth(0); setMinimumWidth(0); layoutParams = LayoutParams(0,48,1.3f).apply { marginEnd = 3 }; setOnClickListener { runSelectedStrategies() } }; row2.addView(scanBtn)
        tuneBtn = Button(requireContext()).apply { text = "调优(90%)"; textSize = 11f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#EF6C00")); setPadding(6,6,6,6); setMinWidth(0); setMinimumWidth(0); layoutParams = LayoutParams(0,48,1.3f).apply { marginEnd = 3 }; setOnClickListener { runSelfTune() } }; row2.addView(tuneBtn)
        val importBtn = Button(requireContext()).apply { text = "导入"; textSize = 11f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#2E7D32")); setPadding(6,6,6,6); setMinWidth(0); setMinimumWidth(0); layoutParams = LayoutParams(0,48,1.2f).apply { marginEnd = 3 }; setOnClickListener { importHistoricalData() } }; row2.addView(importBtn)
        val addCustomBtn = Button(requireContext()).apply { text = "+策略"; textSize = 11f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#1565C0")); setPadding(6,6,6,6); setMinWidth(0); setMinimumWidth(0); layoutParams = LayoutParams(0,48,1.0f); setOnClickListener { showAddDialog() } }; row2.addView(addCustomBtn)
        layout.addView(row2)

        val statusRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(16,2,16,4); setBackgroundColor(Color.WHITE) }
        progressBar = ProgressBar(requireContext()).apply { visibility = View.GONE; layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = 8 } }; statusRow.addView(progressBar)
        statusTv = TextView(requireContext()).apply { text = "$strategyCount 个策略已就绪"; textSize = 11f; setTextColor(Color.parseColor("#AAAAAA")) }; statusRow.addView(statusTv)
        layout.addView(statusRow)

        recyclerView = RecyclerView(requireContext()).apply { layoutManager = LinearLayoutManager(context); layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f); setPadding(0,4,0,4); clipToPadding = false }; layout.addView(recyclerView)
        refreshList(); refreshDateUI(); loadHotSectors()
    }

    /**
     * 获取热门板块及其子板块
     * 规则：
     * 1. 取实时概念板块前三名
     * 2. 每个板块如果有子板块，展开子板块
     * 3. 如果没有子板块，直接显示板块名称
     * 4. 子板块包含：MLCC(三环/风华/火炬/洁美)、光模块(中际/新易盛/天孚/光迅/德科立/联特)、
     *    光纤(长飞/亨通/中天)、光材料等
     */
    private fun loadHotSectors() {
        lifecycleScope.launch(Dispatchers.IO) {
            val days = when (selectedHotPeriod) { 0->1; 1->3; 2->10; 3->30; 4->50; 5->100; else->1 }

            // 获取热门板块（取前三）
            val top3Sectors: List<String> = when {
                days == 1 -> {
                    val live = EastMoneyHotSectorSource.conceptSectors
                    if (live.isNotEmpty()) {
                        live.map { it.name }.take(3)
                    } else {
                        try {
                            val direct = EastMoneyHotSectorSource().fetchSectorsByTypeDirect(3, 5)
                            direct.map { it.name }.take(3)
                        } catch (_: Exception) {
                            StockDataCenter.getHotSectorsByPeriod(3).take(3)
                        }
                    }
                }
                else -> StockDataCenter.getHotSectorsByPeriod(days).take(3)
            }

            if (top3Sectors.isEmpty()) {
                currentHotSectors = (EastMoneyHotSectorSource.conceptSectors + EastMoneyHotSectorSource.industrySectors)
                    .map { it.name }.distinct().sorted().take(3)
            } else {
                // 展开子板块-不限制数量，列出所有相关子板块
                // 比如光通信板块会展开：光芯片、光模块、CPO、光材料等
                val expandedSectors = mutableListOf<String>()
                for (sector in top3Sectors) {
                    val subSectors = try {
                        com.chin.stockanalysis.stock.data.sources.SectorSubDivision
                            .getSubSectors(sector).map { it.name }
                    } catch (_: Exception) { emptyList() }
                    
                    if (subSectors.isNotEmpty()) {
                        // 列出所有子板块（不限制数量）
                        expandedSectors.addAll(subSectors)
                    } else {
                        // 没有子板块直接显示板块
                        expandedSectors.add(sector)
                    }
                }
                currentHotSectors = expandedSectors.distinct()
            }

            // 最终fallback
            if (currentHotSectors.isEmpty()) {
                try {
                    val db = StockDatabase.getInstance(requireContext())
                    val latestDates = db.dailySnapshotDao().getAvailableDates(3)
                    if (latestDates.isNotEmpty()) {
                        val snaps = db.dailySnapshotDao().getByDate(latestDates.first())
                        currentHotSectors = snaps.sortedByDescending { it.changePct }.take(20)
                            .mapNotNull { snap -> db.sectorStockDao().getSectorNamesByStockCode(snap.code).firstOrNull() }
                            .distinct().take(10)
                    }
                } catch (_: Exception) {}
            }

            val hasSubSectors = currentHotSectors.any { sector ->
                try { 
                    com.chin.stockanalysis.stock.data.sources.SectorSubDivision
                        .getSubSectors(sector).isNotEmpty() 
                } catch (_: Exception) { false }
            }

            withContext(Dispatchers.Main) {
                statusTv.text = "  🔥 已加载热门板块(前三子板块): ${currentHotSectors.take(5).joinToString("、")}"
                updateSpinnerLabels(hasSubSectors)
            }
        }
    }

    private fun resetToRecent() { browsingDate = TradingDayPickerView.recentTradingDay(); isBrowsing = false; datePicker.selectedDate = browsingDate; refreshDateUI() }

    private fun refreshDateUI() {
        resetBtn.visibility = if (isBrowsing) View.VISIBLE else View.GONE
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun isMainBoard(code: String): Boolean = !(code.startsWith("sz300") || code.startsWith("sz301") || code.startsWith("sh688") || code.startsWith("bj"))

    // LruCache for sector labels (200 entries max)
    private val sectorLabelCache = object : LinkedHashMap<String, String>(200, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 200
    }

    private fun getSectorLabel(stockCode: String, stockName: String = ""): String {
        val cacheKey = "$stockCode|$stockName"
        sectorLabelCache[cacheKey]?.let { return it }
        val result = try {
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                StockDataCenter.getSubSectorByStock(stockCode, stockName)
            }
        } catch (_: Exception) {
            if (stockName.isNotEmpty()) hardcodedSubSector(stockName) else "-"
        }
        sectorLabelCache[cacheKey] = result
        return result
    }

    private fun updateSpinnerLabels(hasSubSectors: Boolean) {
        val s = if (hasSubSectors) "(板块/子板块)" else ""
        val labels = listOf("当日$s", "近三日$s", "近10日$s", "近30日$s", "近50日", "近100日$s")
        val newAdapter = object : ArrayAdapter<String>(requireContext(), android.R.layout.simple_spinner_item, ArrayList(labels)) {
            init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
                val tv = super.getView(pos, cv, parent) as TextView
                tv.textSize = 12f; tv.setTextColor(Color.parseColor("#E65100")); tv.typeface = Typeface.DEFAULT_BOLD
                return tv
            }
        }
        hotSectorSpinner.adapter = newAdapter
        // 恢复之前的选中位置
        hotSectorSpinner.setSelection(selectedHotPeriod)
    }

    private fun hardcodedSubSector(name: String): String {
        val map = mapOf("生益" to "覆铜板","沪电" to "PCB","深南" to "基板","鹏鼎" to "软板","景旺" to "PCB","世运" to "PCB","超声" to "PCB","三环" to "MLCC","风华" to "MLCC","火炬" to "MLCC","洁美" to "MLCC","中际" to "光模块","新易盛" to "光模块","天孚" to "光器件","光迅" to "光模块","德科立" to "光模块","联特" to "光模块","意华" to "连接器","鼎通" to "连接器","立讯" to "代工","博创" to "光器件","太辰" to "光器件","东山" to "软板","信维" to "射频","闻泰" to "代工","韦尔" to "CIS","兆易" to "存储","长电" to "封测","通富" to "封测","华天" to "封测","北方华创" to "设备","中微" to "刻蚀","盛美" to "清洗","拓荆" to "镀膜","芯源" to "涂胶","江丰" to "靶材","安集" to "抛光液","中芯" to "代工","华虹" to "代工","斯达" to "IGBT","时代电气" to "IGBT","中兴" to "通信","烽火" to "通信","宁德" to "电池","比亚迪" to "整车","亿纬" to "电池","赣锋" to "锂矿","天齐" to "锂矿","华友" to "钴镍","中矿" to "铯矿","紫金" to "金铜","洛阳钼业" to "钼矿","西部矿业" to "铜矿","中科" to "超算","浪潮" to "服务器","曙光" to "超算","海光" to "CPU","寒武纪" to "AI芯","金山" to "办公","中望" to "CAD","德赛西威" to "智驾","均胜" to "安全","阳光" to "逆变器","固德" to "逆变器","锦浪" to "逆变器","晶澳" to "组件","隆基" to "硅片","通威" to "硅料","福莱" to "玻璃","福斯" to "胶膜","泰格" to "CXO","药明" to "CXO","康龙" to "CXO","凯莱英" to "CXO","迈瑞" to "器械","联影" to "影像","鱼跃" to "家用","恒瑞" to "创新药","百济" to "创新药","爱尔" to "眼科","通策" to "口腔")
        for ((kw, label) in map) { if (name.contains(kw)) return label }
        return ""
    }

    private fun runSelectedStrategies() {
        val eng = engine ?: return
        val nowMs = System.currentTimeMillis()
        val selectedDate = browsingDate.toString(); val sectorLabel = currentHotSectors.take(3).joinToString("、").ifEmpty { "全市场" }

        // 缓存檢查：10分鐘內相同條件 ⇒ 直接輸出緩存結果
        val withinCacheWindow = (nowMs - lastExecTimeMs) < 600_000L
        val sameConditions = (browsingDate == lastExecDate && selectedHotPeriod == lastExecPeriod)
        if (withinCacheWindow && sameConditions && cachedResults != null) {
            statusTv.text = "  📋 使用快取結果（${(nowMs - lastExecTimeMs) / 1000}秒前）"
            showResults(cachedResults!!); return
        }

        scanBtn.isEnabled = false; scanBtn.text = "⏳"; progressBar.visibility = View.VISIBLE
        val today = TradingDayPickerView.recentTradingDay()
        statusTv.text = "  正在执行 $browsingDate（$sectorLabel）..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val snapshots = db.dailySnapshotDao().getByDate(selectedDate)
                if (snapshots.isEmpty()) {
                    // 如果選擇的是今天（當日）且沒數據，且市場已開盤 → 直接用實時行情
                    if (browsingDate == today && A股TradingHours.a股是否交易中()) {
                        executeRealTime(eng, selectedDate); return@launch
                    }
                    val availableDates = db.dailySnapshotDao().getAvailableDates(5)
                    if (availableDates.isNotEmpty()) {
                        val latestDate = availableDates.first()
                        // 不要修改 browsingDate，只在内部使用 latestDate 的数据
                        val latestSnapshots = db.dailySnapshotDao().getByDate(latestDate)
                        if (latestSnapshots.isNotEmpty()) {
                            doExecute(eng, db, latestSnapshots, latestDate, sectorLabel)
                            return@launch
                        }
                    }
                    executeRealTime(eng, selectedDate); return@launch
                }
                doExecute(eng, db, snapshots, selectedDate, sectorLabel)
            } catch (e: Exception) { withContext(Dispatchers.Main) { scanBtn.isEnabled = true; scanBtn.text = "执行策略"; progressBar.visibility = View.GONE; statusTv.text = "  执行失败: ${e.message}" } }
        }
    }

    /** 多日合并快照 */
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
        val sectorStockCodes = if (currentHotSectors.isEmpty()) emptySet()
        else { val codes = mutableSetOf<String>(); for (name in currentHotSectors) codes.addAll(db.sectorStockDao().getStockCodesBySector(name)); codes }
        val codeToName = db.stockBasicDao().getAll().associate { it.code to it.name }
        val onlyMainBoard = (view?.findViewWithTag<Switch>("mainBoardSwitch")?.isChecked == true)
        val stockList = effectiveSnapshots.filter { (sectorStockCodes.isEmpty() || it.code in sectorStockCodes) && (!onlyMainBoard || isMainBoard(it.code)) }.map { snap ->
            val dn = snap.name.takeIf { it.isNotBlank() } ?: codeToName[snap.code] ?: snap.code
            val yc = if (snap.changePct != 0.0 && snap.close != 0.0) snap.close / (1.0 + snap.changePct / 100.0) else snap.close
            com.chin.stockanalysis.stock.StockRealtime(code = snap.code, name = dn, price = snap.close, open = snap.open, yestClose = yc, high = snap.high, low = snap.low, volume = snap.volume, amount = snap.amount, changePercent = snap.changePct, changeAmount = snap.close * snap.changePct / 100, timestamp = System.currentTimeMillis())
        }
        val results = mutableListOf<ScreeningResult>()
        for (s in eng.getStrategies()) { if (!eng.isEnabled(s.id)) continue; if (s.id == "ai_prediction") continue; try { s.screenWithData(stockList).getOrNull()?.let { results.add(it) } } catch (e: Exception) { Log.w("SLF", "策略 ${s.id} 异常: ${e.message}") } }
        // 更新缓存
        cachedResults = results
        lastExecTimeMs = System.currentTimeMillis()
        lastExecDate = browsingDate
        lastExecPeriod = selectedHotPeriod
        if (isAdded) { withContext(Dispatchers.Main) { scanBtn.isEnabled = true; scanBtn.text = "执行策略"; progressBar.visibility = View.GONE; statusTv.text = "  已完成 · $selectedDate（$sectorLabel）"; saveBacktestData(results); showResults(results) } }
        else { pendingResults = results }
    }

    private fun runSelfTune() {
        val eng = engine ?: return
        tuneBtn.isEnabled = false; tuneBtn.text = "⏳"; progressBar.visibility = View.VISIBLE; statusTv.text = "  🔧 正在自测调优(目标90%)..."
        lifecycleScope.launch {
            try { val report = StrategySelfTuner(requireContext()).selfTune(eng.getEnabledStrategies(), 30, 0.90f); withContext(Dispatchers.Main) { tuneBtn.isEnabled = true; tuneBtn.text = "调优(90%)"; progressBar.visibility = View.GONE; statusTv.text = "  ✅ 调优完成"; showFullScreenTuneReport(report.summary) } }
            catch (e: Exception) { withContext(Dispatchers.Main) { tuneBtn.isEnabled = true; tuneBtn.text = "调优(90%)"; progressBar.visibility = View.GONE; statusTv.text = "  调优失败: ${e.message?.take(30)}" } }
        }
    }

    private fun showFullScreenTuneReport(text: String) {
        ScrollView(requireContext()).also { sv ->
            sv.addView(TextView(requireContext()).apply { this.text = text; setTextColor(Color.parseColor("#333333")); textSize = 10f; setPadding(dp(16), dp(12), dp(16), dp(12)); setLineSpacing(2f, 1.1f); setTypeface(Typeface.MONOSPACE); isVerticalScrollBarEnabled = true })
            AlertDialog.Builder(requireContext()).setTitle("策略自测调优报告(目标90%)").setView(sv).setPositiveButton("关闭", null).create().apply { show(); window?.setLayout(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT) }
        }
    }

    private fun executeRealTime(eng: StrategyEngine, selectedDate: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val rts = screener?.scanFullMarket() ?: emptyList()
                val results = mutableListOf<ScreeningResult>()
                if (rts.isNotEmpty()) { for (s in eng.getStrategies()) { if (!eng.isEnabled(s.id)) continue; if (s.id == "ai_prediction") continue; try { s.screenWithData(rts).getOrNull()?.let { results.add(it) } } catch (e: Exception) { Log.w("SLF", "实时 ${s.id}: ${e.message}") } } }
                // 更新缓存
                cachedResults = results.takeIf { it.isNotEmpty() }
                lastExecTimeMs = System.currentTimeMillis()
                lastExecDate = browsingDate
                lastExecPeriod = selectedHotPeriod
                if (isAdded) { withContext(Dispatchers.Main) { scanBtn.isEnabled = true; scanBtn.text = "执行策略"; progressBar.visibility = View.GONE; statusTv.text = "  已完成 · $selectedDate (实时)" + if (rts.isEmpty()) " ⚠️ 扫描无数据" else ""; saveBacktestData(results); showResults(results) } }
                else { pendingResults = results }
            } catch (e: Exception) { if (isAdded) withContext(Dispatchers.Main) { scanBtn.isEnabled = true; scanBtn.text = "执行策略"; progressBar.visibility = View.GONE; statusTv.text = "  执行失败: ${e.message}" } }
        }
    }

    private fun showResults(results: List<ScreeningResult>) {
        if (!isAdded) return
        val totalHits = results.sumOf { it.hitCount }; val totalScanned = results.sumOf { it.totalScanned }
        if (results.isEmpty() || totalHits == 0) { 
            statusTv.text = "  ⚠️ 扫描${totalScanned}只，未产生命中信号"; 
            Log.i("SLF", "扫描${totalScanned}只，未产生命中信号")
            AlertDialog.Builder(requireContext()).setTitle("策略执行结果").setMessage("扫描 $totalScanned 只股票，未产生命中信号。\n\n可能原因:\n• 当前市场情绪偏弱\n• 策略阈值较高\n• 数据源未就绪").setPositiveButton("确定", null).show()
            return 
        }
        showResultsDialog(results)
    }

    private fun showResultsDialog(results: List<ScreeningResult>) {
        if (!isAdded || results.isEmpty()) return
        val sv = ScrollView(requireContext()); val c = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        val rm = results.associateBy { it.strategyId }; val all = engine?.getStrategies()?.filter { engine!!.isEnabled(it.id) } ?: emptyList()
        for (r in all.map { s -> rm[s.id] ?: ScreeningResult(strategyId = s.id, strategyName = s.name, category = s.category, signals = emptyList(), totalScanned = 0, scanTimeMs = 0) }) {
            c.addView(TextView(requireContext()).apply { text = "${r.category.icon} ${r.strategyName}  (${r.hitCount}只 / ${r.scanTimeMs}ms)"; textSize = 15f; setTextColor(Color.parseColor("#333333")); setTypeface(null, Typeface.BOLD); setPadding(0, 16, 0, 8); setOnClickListener { if (r.signals.isNotEmpty()) openResultDialog(r) } })
            if (r.signals.isEmpty()) { c.addView(TextView(requireContext()).apply { text = "  ⚠️ 无命中信号"; textSize = 12f; setTextColor(Color.parseColor("#999999")); setPadding(0, 0, 0, 8) }); continue }
            val t = TableLayout(requireContext()).apply { isStretchAllColumns = true }
            val hr = TableRow(requireContext()); for (h in listOf("名称", "子板块", "代码", "强度", "价格", "涨幅")) hr.addView(TextView(requireContext()).apply { text = h; textSize = 11f; setTextColor(Color.parseColor("#999999")); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(4, 4, 4, 4) }); t.addView(hr)
            for (s in r.signals.distinctBy { it.stockCode }.take(10)) {
                val row = TableRow(requireContext()); row.setOnClickListener { val detail = StockDetailFragment.newInstance(s.stockCode, s.stockName, s.currentPrice, s.changePercent, getSectorLabel(s.stockCode, s.stockName)); activity?.supportFragmentManager?.beginTransaction()?.replace(android.R.id.content, detail)?.addToBackStack(null)?.commit() }
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
        c.addView(TextView(requireContext()).apply { text = "🤖 AI 量化预测（多策略+新闻因子+周期轮动）"; textSize = 16f; setTextColor(Color.parseColor("#1565C0")); setTypeface(null, Typeface.BOLD); setPadding(0, 8, 0, 8) })
        val ail = TextView(requireContext()).apply { text = "  ⏳ AI 正在分析中，请稍候..."; textSize = 12f; setTextColor(Color.parseColor("#999999")) }; c.addView(ail)
        // 动态标题：多日模式用label
        val dialogTitle = when {
            isBrowsing && selectedHotPeriod == 0 -> "扫描结果 ($browsingDate)"
            selectedHotPeriod > 0 -> { val label = when(selectedHotPeriod){1->"近三日";2->"近10日";3->"近30日";4->"近50日";5->"近100日"; else->"当日"}; "扫描结果 ($label)" }
            else -> "扫描结果 ($browsingDate)"
        }
        sv.addView(c); AlertDialog.Builder(requireContext()).setTitle(dialogTitle).setView(sv).setPositiveButton("关闭", null).show()
        lifecycleScope.launch { try { val ai = AIPredictionEngine(requireContext()); val pr = ai.predict(results, browsingDate.toString()); requireActivity().runOnUiThread { if (pr != null && pr.topPicks.isNotEmpty()) { ail.text = ""; c.addView(TextView(requireContext()).apply { text = "  📋 方案${pr.mode}: ${pr.modeReason}"; textSize = 11f; setTextColor(Color.parseColor("#E65100")); setPadding(0, 4, 0, 8) }); c.addView(TextView(requireContext()).apply { text = "  📊 市场判断: ${pr.marketOutlook}"; textSize = 11f; setTextColor(Color.parseColor("#666666")); setPadding(0, 0, 0, 4) }); c.addView(TextView(requireContext()).apply { text = "  ⚠ ${pr.riskWarning}"; textSize = 11f; setTextColor(Color.parseColor("#EF6C00")); setPadding(0, 0, 0, 8) }); val tpTable = TableLayout(requireContext()).apply { isStretchAllColumns = true }; val tpHr = TableRow(requireContext()); for (h in listOf("排名", "名称", "代码", "综分", "概率", "建议")) tpHr.addView(TextView(requireContext()).apply { text = h; textSize = 10f; setTextColor(Color.parseColor("#999999")); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(2, 4, 2, 4) }); tpTable.addView(tpHr); for (p in pr.topPicks) { val tpRow = TableRow(requireContext()); for (cell in listOf("#${p.rank}", p.stockName, p.stockCode.takeLast(6), "${p.compositeScore}", "${p.upProbability}%", p.actionSuggestion)) tpRow.addView(TextView(requireContext()).apply { text = cell; textSize = 10f; setTextColor(Color.parseColor("#333333")); gravity = Gravity.CENTER; setPadding(2, 4, 2, 4) }); tpTable.addView(tpRow) }; c.addView(tpTable) } else { ail.text = "  ⚠️ AI 预测暂不可用" } } } catch (e: Exception) { requireActivity().runOnUiThread { ail.text = "  ⚠️ AI 预测失败: ${e.message?.take(30)}" } } }
    }

    private fun refreshList() { engine?.let { adapter = StrategyAdapter(it.getStrategies(), ::onStrategyClick, ::onStrategyToggle); recyclerView.adapter = adapter } }
    private fun onStrategyClick(s: Strategy) { StrategyDetailFragment().apply { this.strategy = s; onSave = { u -> engine?.apply { removeStrategy(u.id); registerStrategy(u); refreshList(); strategyCount = engine?.getStrategies()?.size ?: strategyCount } } }.show(parentFragmentManager, "detail") }
    private fun onStrategyToggle(s: Strategy) { engine?.setEnabled(s.id, !engine!!.isEnabled(s.id)) }
    private fun openResultDialog(result: ScreeningResult) { StrategyResultDialogFragment().apply { this.result = result; onAskQuestion = { q -> val ctx = buildString { appendLine("基于以下策略扫描结果，请回答用户问题："); appendLine("策略: ${result.strategyName} | 扫描: ${result.totalScanned}只 | 命中: ${result.hitCount}只"); for ((i, s) in result.signals.take(10).withIndex()) appendLine("| ${i + 1} | ${s.stockName} | ${s.stockCode.takeLast(6)} | ${s.strength}% | ${"%.2f".format(s.currentPrice)} | ${"%.2f".format(s.changePercent)}% |"); appendLine(); appendLine("用户问题: $q") }; if (activity is MainActivity) (activity as MainActivity).switchToChatAndSend(ctx) else Toast.makeText(requireContext(), "提问已记录: $q", Toast.LENGTH_SHORT).show() } }.show(parentFragmentManager, "result") }
    private fun showAddDialog() { val name = EditText(requireContext()).apply { hint = "策略名称"; setSingleLine() }; val desc = EditText(requireContext()).apply { hint = "策略描述"; setSingleLine() }; AlertDialog.Builder(requireContext()).setTitle("添加自定义策略").setView(LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 8); addView(name, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = 12 }); addView(desc, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) }).setPositiveButton("创建") { _, _ -> val n = name.text.toString().trim(); if (n.isNotBlank()) { val id = "custom_${System.currentTimeMillis()}"; engine?.registerStrategy(object : Strategy { override val id = id; override var name = n; override var description = desc.text.toString().trim().ifEmpty { "自定义策略" }; override val category = StrategyCategory.CUSTOM; override val config = StrategyConfig.fullMarket(20); override var weightFactors = listOf(WeightFactor("default", "综合评分", 100, "默认权重")); override val source = StrategySource.USER_CUSTOM; override suspend fun screen() = Result.success(ScreeningResult(strategyId = id, strategyName = n, category = StrategyCategory.CUSTOM, signals = emptyList(), totalScanned = 0, scanTimeMs = 0)); override suspend fun isAvailable() = false }); refreshList(); strategyCount = engine?.getStrategies()?.size ?: strategyCount } }.setNegativeButton("取消", null).show() }
    private fun importHistoricalData() { scanBtn.isEnabled = false; scanBtn.text = "⏳"; progressBar.visibility = View.VISIBLE; statusTv.text = "  正在从东方财富拉取历史K线..."; lifecycleScope.launch { try { val f = com.chin.stockanalysis.strategy.data.HistoricalDataFetcher(requireContext()); val t = f.fetchAllHistoricalData(60) { p -> lifecycleScope.launch(Dispatchers.Main) { statusTv.text = "  进度: ${p.completedStocks}/${p.totalStocks} 只 · ${p.totalRecords} 条" } }; withContext(Dispatchers.Main) { scanBtn.isEnabled = true; scanBtn.text = "执行策略"; progressBar.visibility = View.GONE; statusTv.text = "  ✅ 导入完成 · $t 条历史记录" } } catch (e: Exception) { withContext(Dispatchers.Main) { scanBtn.isEnabled = true; scanBtn.text = "执行策略"; progressBar.visibility = View.GONE; statusTv.text = "  导入失败: ${e.message}" } } } }
    private fun saveBacktestData(results: List<ScreeningResult>) { lifecycleScope.launch { try { val be = com.chin.stockanalysis.strategy.backtest.BacktestEngine(requireContext()); for (r in results) be.savePredictions(r.strategyId, r.strategyName, r) } catch (e: Exception) { Log.w("SLF", "保存预测失败: ${e.message}") } } }
    override fun onResume() { super.onResume(); loadHotSectors(); pendingResults?.let { showResults(it); pendingResults = null } }
    override fun onDestroyView() { super.onDestroyView(); engine?.cancelScan() }
}