package com.chin.stockanalysis.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.Typeface
import android.os.Bundle
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
import com.chin.stockanalysis.strategy.data.StockScreener
import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.models.WeightFactor
import com.chin.stockanalysis.strategy.predict.AIPredictionEngine
import com.chin.stockanalysis.strategy.strategies.*
import com.chin.stockanalysis.strategy.ui.StrategyAdapter
import com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * ## 量化选股列表
 */
class StrategyListFragment : Fragment() {

    private lateinit var layout: LinearLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: StrategyAdapter
    private lateinit var statusTv: TextView
    private lateinit var scanBtn: Button
    private lateinit var tuneBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var scopeSpinner: Spinner
    private lateinit var tradingDaySpinner: Spinner

    private lateinit var dateLabelTv: TextView
    private lateinit var prevBtn: Button
    private lateinit var nextBtn: Button
    private lateinit var resetBtn: Button
    private lateinit var hotSectorTv: TextView
    private lateinit var hotSectorDropBtn: Button
    private var selectedPreset = 0 // 0=当日热门,1=最近交易日,2=近10日,3=近100日
    private val selectedSectors = mutableSetOf<String>()

    private var browsingDate: LocalDate = recentTradingDay()
    private var isBrowsing = false

    private var engine: StrategyEngine? = null
    private var screener: StockScreener? = null
    private var currentScope: ScanScope = ScanScope.FULL_MARKET
    private var selectedTradingDay: TradingDayOption = TradingDayOption.LAST_3

    enum class TradingDayOption(val label: String, val offset: Int) {
        LAST_3("前三个交易日", 3), LAST_5("前五个交易日", 5),
        LAST_10("前十个交易日", 10), LAST_30("前三十个交易日", 30),
        LAST_50("前五十个交易日", 50), LAST_100("前一百个交易日", 100);

        fun actualTradingDay(fromDate: LocalDate): String {
            var d = fromDate.minusDays(offset.toLong())
            while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.minusDays(1)
            return d.toString()
        }
        fun displayLabel(fromDate: LocalDate): String {
            val dateStr = actualTradingDay(fromDate)
            return "$label ($dateStr)"
        }
    }

    enum class ScanScope(val label: String) { FULL_MARKET("全市场"), SECTOR("板块"), WATCHLIST("自选股") }

    private val SECTORS = listOf("全市场", "化工", "有色金属", "半导体", "医药", "新能源", "军工", "消费", "金融", "AI算力", "商业航天")

    companion object {
        private val CHINESE_HOLIDAYS: Set<LocalDate> by lazy {
            val raw = listOf(
                "2025-01-01", "2025-01-27","2025-01-28","2025-01-29","2025-01-30","2025-01-31","2025-02-03","2025-02-04",
                "2025-04-04","2025-04-05","2025-04-06", "2025-05-01","2025-05-02","2025-05-03","2025-05-04","2025-05-05",
                "2025-05-31","2025-06-01","2025-06-02",
                "2025-10-01","2025-10-02","2025-10-03","2025-10-04","2025-10-05","2025-10-06","2025-10-07","2025-10-08",
                "2026-01-01","2026-01-02","2026-01-03",
                "2026-02-16","2026-02-17","2026-02-18","2026-02-19","2026-02-20","2026-02-21","2026-02-22","2026-02-23",
                "2026-04-04","2026-04-05","2026-04-06",
                "2026-05-01","2026-05-02","2026-05-03","2026-05-04","2026-05-05",
                "2026-05-31","2026-06-01",
                "2026-10-01","2026-10-02","2026-10-03","2026-10-04","2026-10-05","2026-10-06","2026-10-07"
            )
            raw.map { LocalDate.parse(it) }.toSet()
        }

        fun recentTradingDay(): LocalDate {
            var d = LocalDate.now()
            if (LocalTime.now() < LocalTime.of(9, 30)) d = d.minusDays(1)
            while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY || d in CHINESE_HOLIDAYS) d = d.minusDays(1)
            return d
        }
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
        val repo = StockDataSourceFactory.createDefaultRepository(ctx)
        screener = StockScreener(repo)
        engine = StrategyEngine(ctx, screener!!).apply {
            registerStrategy(MovingAverageStrategy(screener!!))
            registerStrategy(VolumeBreakStrategy(screener!!))
            registerStrategy(LowValuationStrategy(screener!!))
            registerStrategy(GapUpMomentumStrategy(screener!!))
            registerStrategy(TurnoverFilterStrategy(screener!!))
            registerStrategy(EarlyMorningChaseStrategy(screener!!))
            registerStrategy(TailLowPickStrategy(screener!!))
        }
    }

    private fun buildUI() {
        val header = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 8); setBackgroundColor(Color.WHITE)
        }
        header.addView(TextView(requireContext()).apply {
            text = "🎯 量化选股"; textSize = 22f; setTextColor(Color.parseColor("#1A1A2E")); setTypeface(null, Typeface.BOLD)
        })
        header.addView(TextView(requireContext()).apply {
            text = "7 种策略 · 全市场/板块/自选股 · 多维度综合打分"; textSize = 12f; setTextColor(Color.parseColor("#999999")); setPadding(0, 2, 0, 0)
        })

        val hotSectorRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 8, 0, 0)
        }
        hotSectorRow.addView(TextView(requireContext()).apply { text = "🏷"; textSize = 13f; setPadding(0, 0, 4, 0) })
        hotSectorTv = TextView(requireContext()).apply {
            text = "加载中..."; textSize = 12f; setTextColor(Color.parseColor("#E65100"))
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        hotSectorRow.addView(hotSectorTv)
        hotSectorDropBtn = Button(requireContext()).apply {
            text = "当日热门 ▼"; textSize = 12f; setTextColor(Color.BLACK)
            setBackgroundColor(Color.parseColor("#FFF3E0"))
            setPadding(dp(6), 0, dp(6), 0); setMinWidth(0); setMinimumWidth(0)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            setOnClickListener { showSectorDropdown() }
        }
        hotSectorRow.addView(hotSectorDropBtn)
        header.addView(hotSectorRow)
        layout.addView(header)

        val navRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 0, 4, 0); setBackgroundColor(Color.parseColor("#FFF8E1"))
        }
        val todayLbl = TextView(requireContext()).apply {
            val todayStr = LocalDate.now().toString(); text = "今天 $todayStr"; textSize = 11f
            setTextColor(Color.parseColor("#2E7D32")); setTypeface(null, Typeface.BOLD); setPadding(4, 0, 6, 0)
        }; navRow.addView(todayLbl)
        prevBtn = Button(requireContext()).apply {
            text = "◀"; textSize = 10f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#1565C0"))
            setPadding(8, 0, 8, 0); setMinWidth(0); setMinimumWidth(0)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, 28).apply { marginEnd = 1 }
            setOnClickListener { navigateDate(-1) }
        }; navRow.addView(prevBtn)
        dateLabelTv = TextView(requireContext()).apply {
            textSize = 12f; setTextColor(Color.parseColor("#E65100")); setTypeface(null, Typeface.BOLD); setPadding(3, 0, 3, 0)
        }; navRow.addView(dateLabelTv)
        nextBtn = Button(requireContext()).apply {
            text = "▶"; textSize = 10f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#1565C0"))
            setPadding(8, 0, 8, 0); setMinWidth(0); setMinimumWidth(0)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, 28).apply { marginStart = 1 }
            setOnClickListener { navigateDate(+1) }
        }; navRow.addView(nextBtn)
        resetBtn = Button(requireContext()).apply {
            text = "重置"; textSize = 11f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#EF6C00"))
            setPadding(10, 4, 10, 4); setMinWidth(0); setMinimumWidth(0); visibility = View.GONE
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, 40)
            setOnClickListener { resetToRecent() }
        }; navRow.addView(resetBtn)
        layout.addView(navRow)

        val row1 = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(8, 4, 8, 2); setBackgroundColor(Color.WHITE) }
        row1.addView(TextView(requireContext()).apply { text = "范围:"; textSize = 16f; setTextColor(Color.parseColor("#333333")); layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = 8 } })
        scopeSpinner = Spinner(requireContext()).apply {
            adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, SECTORS).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(0); setBackgroundColor(Color.parseColor("#E3F2FD")); setPadding(12, 8, 12, 8)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = 8 }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { currentScope = if (SECTORS[pos] == "全市场") ScanScope.FULL_MARKET else ScanScope.SECTOR }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        row1.addView(scopeSpinner)
        row1.addView(TextView(requireContext()).apply { text = "回溯:"; textSize = 16f; setTextColor(Color.parseColor("#333333")); layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = 8 } })
        tradingDaySpinner = Spinner(requireContext()).apply {
            adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, TradingDayOption.values().map { it.displayLabel(browsingDate) }).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(0); setBackgroundColor(Color.parseColor("#FFF3E0")); setPadding(12, 8, 12, 8)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.5f)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener { override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { selectedTradingDay = TradingDayOption.values()[pos] }; override fun onNothingSelected(p: AdapterView<*>?) {} }
        }; row1.addView(tradingDaySpinner)
        layout.addView(row1)

        val row2 = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(8, 2, 8, 6); setBackgroundColor(Color.WHITE) }
        scanBtn = Button(requireContext()).apply { text = "执行"; textSize = 11f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#E65100")); setPadding(6, 6, 6, 6); setMinWidth(0); setMinimumWidth(0); layoutParams = LayoutParams(0, 48, 1.0f).apply { marginEnd = 3 }; setOnClickListener { runSelectedStrategies() } }; row2.addView(scanBtn)
        tuneBtn = Button(requireContext()).apply { text = "调优"; textSize = 11f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#EF6C00")); setPadding(6, 6, 6, 6); setMinWidth(0); setMinimumWidth(0); layoutParams = LayoutParams(0, 48, 1.0f).apply { marginEnd = 3 }; setOnClickListener { runSelfTune() } }; row2.addView(tuneBtn)
        val importBtn = Button(requireContext()).apply { text = "导入"; textSize = 11f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#2E7D32")); setPadding(6, 6, 6, 6); setMinWidth(0); setMinimumWidth(0); layoutParams = LayoutParams(0, 48, 1.0f).apply { marginEnd = 3 }; setOnClickListener { importHistoricalData() } }; row2.addView(importBtn)
        val addCustomBtn = Button(requireContext()).apply { text = "+自定义"; textSize = 11f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#1565C0")); setPadding(6, 6, 6, 6); setMinWidth(0); setMinimumWidth(0); layoutParams = LayoutParams(0, 48, 1.0f); setOnClickListener { showAddDialog() } }; row2.addView(addCustomBtn)
        layout.addView(row2)

        val statusRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(16, 2, 16, 4); setBackgroundColor(Color.WHITE) }
        progressBar = ProgressBar(requireContext()).apply { visibility = View.GONE; layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = 8 } }; statusRow.addView(progressBar)
        statusTv = TextView(requireContext()).apply { text = "7 个策略已就绪"; textSize = 11f; setTextColor(Color.parseColor("#AAAAAA")) }; statusRow.addView(statusTv)
        layout.addView(statusRow)

        recyclerView = RecyclerView(requireContext()).apply {
            layoutManager = LinearLayoutManager(context); layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f); setPadding(0, 4, 0, 4); clipToPadding = false
        }; layout.addView(recyclerView)

        refreshList(); refreshDateUI(); refreshHotSectors(); startHotSectorRefresh()
    }

    private fun refreshHotSectors() {
        if (selectedSectors.isNotEmpty()) {
            hotSectorTv.text = selectedSectors.take(5).joinToString("  ")
            return
        }
        val sectors = EastMoneyHotSectorSource.conceptSectors
        if (sectors.isNotEmpty()) { hotSectorTv.text = sectors.take(5).joinToString("  ") { it.name }; return }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val news = db.newsFactorDao().getAllActive(50).map { it.sector }.filter { it.isNotBlank() }.distinct().take(5)
                if (news.isNotEmpty()) { withContext(Dispatchers.Main) { hotSectorTv.text = news.joinToString("  ") }; return@launch }
                val latest = db.sectorDailyRecordDao().getByDate(recentTradingDay().toString())
                val names = if (latest.isNotEmpty()) latest.take(5).joinToString("  ") { it.sectorName } else "暂无热门板块"
                withContext(Dispatchers.Main) { hotSectorTv.text = names }
            } catch (_: Exception) { withContext(Dispatchers.Main) { hotSectorTv.text = "暂无热门板块" } }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun startHotSectorRefresh() { lifecycleScope.launch(Dispatchers.IO) { while (isActive) { delay(3 * 60_000L); withContext(Dispatchers.Main) { refreshHotSectors() } } } }

    private fun navigateDate(delta: Int) {
        var d = browsingDate.plusDays(delta.toLong())
        while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY || d in CHINESE_HOLIDAYS) d = d.plusDays(delta.toLong())
        val today = recentTradingDay(); if (d.isAfter(today)) return
        browsingDate = d; isBrowsing = (browsingDate != today); refreshDateUI(); refreshSpinnerLabels()
    }
    private fun resetToRecent() { browsingDate = recentTradingDay(); isBrowsing = false; refreshDateUI(); refreshSpinnerLabels() }

    private fun refreshDateUI() {
        val today = recentTradingDay(); val prefix = if (isBrowsing) "当前交易日" else "最近交易日"
        dateLabelTv.text = "$prefix: $browsingDate"; nextBtn.isEnabled = browsingDate < today
        nextBtn.alpha = if (browsingDate < today) 1.0f else 0.4f; resetBtn.visibility = if (isBrowsing) View.VISIBLE else View.GONE
    }
    private fun refreshSpinnerLabels() { val ops = TradingDayOption.values().map { it.displayLabel(browsingDate) }; (tradingDaySpinner.adapter as ArrayAdapter<String>).let { a -> a.clear(); a.addAll(ops); a.notifyDataSetChanged() } }

    // ── Sector Dropdown ──
    private fun showSectorDropdown() {
        lifecycleScope.launch(Dispatchers.IO) {
            val presets = listOf("当日热门", "最近交易日", "近10日", "近100日")
            val allSectors = mutableSetOf<String>()
            val live = EastMoneyHotSectorSource.conceptSectors
            if (live.isNotEmpty()) allSectors.addAll(live.map { it.name })
            try { val db = StockDatabase.getInstance(requireContext()); allSectors.addAll(db.newsFactorDao().getAllActive(100).map { it.sector }.filter { it.isNotBlank() }); allSectors.addAll(db.sectorDailyRecordDao().getRecentDays(100).map { it.sectorName }.filter { it.isNotBlank() }) } catch (_: Exception) {}
            val sorted = allSectors.filter { it.isNotEmpty() }.sorted()
            withContext(Dispatchers.Main) {
                val popup = buildCombinedPopup(presets, sorted)
                hotSectorDropBtn.tag = popup; popup.showAsDropDown(hotSectorDropBtn, 0, 4, Gravity.END)
            }
        }
    }

    private fun buildCombinedPopup(presets: List<String>, sortedSectors: List<String>): PopupWindow {
        val ctx = requireContext(); val w = dp(170)
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        root.addView(TextView(ctx).apply { text = "🏷 选择板块"; textSize = 11f; setTextColor(Color.parseColor("#1565C0")); setTypeface(null, Typeface.BOLD); setPadding(dp(4), dp(2), dp(4), dp(4)) })
        // 4 presets as radio-like
        for ((i, p) in presets.withIndex()) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(1), dp(4), dp(1)) }
            row.addView(TextView(ctx).apply { text = if (selectedPreset == i) "●" else "○"; textSize = 12f; setTextColor(Color.parseColor("#1565C0")); setPadding(0, 0, dp(6), 0) })
            row.addView(TextView(ctx).apply { text = p; textSize = 12f; setTextColor(Color.parseColor("#333333")); layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f) })
            row.setOnClickListener {
                selectedPreset = i; selectedSectors.clear()
                hotSectorDropBtn.text = "$p ▼"; refreshHotSectors()
                try { (hotSectorDropBtn.tag as? PopupWindow)?.dismiss() } catch (_: Exception) {}
            }
            root.addView(row)
        }
        // divider
        root.addView(View(ctx).apply { layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 1).apply { setMargins(0, dp(4), 0, dp(4)) }; setBackgroundColor(Color.parseColor("#DDDDDD")) })
        // all sectors with checkboxes
        val scroll = ScrollView(ctx).apply { layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(180)) }
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for (name in sortedSectors) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(2), dp(1), dp(2), dp(1)) }
            val cb = CheckBox(ctx).apply { isChecked = name in selectedSectors; setPadding(0, 0, dp(4), 0); setOnCheckedChangeListener { _, chk -> if (chk) selectedSectors.add(name) else selectedSectors.remove(name) } }
            row.addView(cb)
            row.addView(TextView(ctx).apply { text = name; textSize = 11f; setTextColor(Color.parseColor("#333333")); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f) })
            list.addView(row)
        }
        scroll.addView(list); root.addView(scroll)
        // bottom buttons
        val btnRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
        listOf("清除" to { selectedSectors.clear(); selectedPreset = 0; hotSectorDropBtn.text = "当日热门 ▼"; refreshHotSectors() },
               "确定" to { hotSectorDropBtn.text = if (selectedSectors.isEmpty()) "${presets[selectedPreset]} ▼" else "✓${selectedSectors.size} ▼"; refreshHotSectors() })
            .forEach { (lbl, act) -> btnRow.addView(TextView(ctx).apply { text = lbl; textSize = 11f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#1565C0")); setPadding(0, dp(6), 0, dp(6)); layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f); setOnClickListener { act(); try { (hotSectorDropBtn.tag as? PopupWindow)?.dismiss() } catch (_: Exception) {} } }) }
        root.addView(btnRow)
        return PopupWindow(root, w, LayoutParams.WRAP_CONTENT, true).apply { setBackgroundDrawable(ColorDrawable(Color.WHITE)); elevation = 8f }
    }

    // ── strategy execution (unchanged) ──
    private fun runSelectedStrategies() {
        val eng = engine ?: return
        scanBtn.isEnabled = false; scanBtn.text = "⏳"; progressBar.visibility = View.VISIBLE
        val selectedDate = browsingDate.toString(); val predictNextDate = nextTradingDay(browsingDate).toString()
        statusTv.text = "  正在执行 ${browsingDate} → 预测 $predictNextDate ..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext()); val snapshots = db.dailySnapshotDao().getByDate(selectedDate)
                if (snapshots.isEmpty()) { withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "$selectedDate 无历史数据，将使用实时行情执行。", Toast.LENGTH_LONG).show(); executeRealTime(eng, selectedDate) }; return@launch }
                val codeToName = db.stockBasicDao().getAll().associate { it.code to it.name }
                val stockList = snapshots.map { snap ->
                    val dn = snap.name.takeIf { it.isNotBlank() } ?: codeToName[snap.code] ?: snap.code
                    val yc = if (snap.changePct != 0.0 && snap.close != 0.0) snap.close / (1.0 + snap.changePct / 100.0) else snap.close
                    com.chin.stockanalysis.stock.StockRealtime(code = snap.code, name = dn, price = snap.close, open = snap.open, yestClose = yc, high = snap.high, low = snap.low, volume = snap.volume, amount = snap.amount, changePercent = snap.changePct, changeAmount = snap.close * snap.changePct / 100, timestamp = System.currentTimeMillis())
                }
                val results = mutableListOf<ScreeningResult>()
                for (s in eng.getStrategies()) { if (!eng.isEnabled(s.id)) continue; try { s.screenWithData(stockList).getOrNull()?.let { results.add(it) } } catch (e: Exception) { android.util.Log.w("SLF", "策略 ${s.id} 异常: ${e.message}") } }
                withContext(Dispatchers.Main) { scanBtn.isEnabled = true; scanBtn.text = "执行"; progressBar.visibility = View.GONE; statusTv.text = "  已完成 · $selectedDate"; if (results.sumOf { it.hitCount } > 0) showResultsDialog(results) else showResultsDialogSimple(results); saveBacktestData(results) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { scanBtn.isEnabled = true; scanBtn.text = "执行"; progressBar.visibility = View.GONE; statusTv.text = "  执行失败: ${e.message}" } }
        }
    }
    private fun nextTradingDay(from: LocalDate): LocalDate { var d = from.plusDays(1); while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.plusDays(1); return d }
    private fun runSelfTune() {
        val eng = engine ?: return; tuneBtn.isEnabled = false; tuneBtn.text = "⏳"; progressBar.visibility = View.VISIBLE; statusTv.text = "  🔧 正在自测调优..."
        lifecycleScope.launch { try { val tuner = StrategySelfTuner(requireContext()); val report = tuner.selfTune(eng.getEnabledStrategies(), 30, 0.55f); withContext(Dispatchers.Main) { tuneBtn.isEnabled = true; tuneBtn.text = "调优"; progressBar.visibility = View.GONE; statusTv.text = "  ✅ 调优完成"; AlertDialog.Builder(requireContext()).setTitle("🔧 策略自测调优报告").setMessage(report.summary).setPositiveButton("关闭", null).show() } } catch (e: Exception) { withContext(Dispatchers.Main) { tuneBtn.isEnabled = true; tuneBtn.text = "调优"; progressBar.visibility = View.GONE; statusTv.text = "  调优失败: ${e.message?.take(30)}" } } }
    }
    private fun executeRealTime(eng: StrategyEngine, selectedDate: String) {
        lifecycleScope.launch(Dispatchers.IO) { try { val rts = screener?.scanFullMarket() ?: emptyList(); if (rts.isEmpty()) { withContext(Dispatchers.Main) { scanBtn.isEnabled = true; scanBtn.text = "执行"; progressBar.visibility = View.GONE; statusTv.text = "  ❌ 实时数据拉取失败" }; return@launch }; val results = mutableListOf<ScreeningResult>(); for (s in eng.getStrategies()) { if (!eng.isEnabled(s.id)) continue; try { s.screenWithData(rts).getOrNull()?.let { results.add(it) } } catch (e: Exception) { android.util.Log.w("SLF", "实时策略 ${s.id} 异常: ${e.message}") } }; withContext(Dispatchers.Main) { scanBtn.isEnabled = true; scanBtn.text = "执行"; progressBar.visibility = View.GONE; statusTv.text = "  已完成 · $selectedDate (实时数据)"; if (results.sumOf { it.hitCount } > 0) showResultsDialog(results) else showResultsDialogSimple(results); saveBacktestData(results) } } catch (e: Exception) { withContext(Dispatchers.Main) { scanBtn.isEnabled = true; scanBtn.text = "执行"; progressBar.visibility = View.GONE; statusTv.text = "  执行失败: ${e.message}" } } }
    }
    private fun refreshList() { engine?.let { adapter = StrategyAdapter(it.getStrategies(), ::onStrategyClick, ::onStrategyToggle); recyclerView.adapter = adapter; statusTv.text = "  ${it.getStrategies().size} 个策略已就绪" } }
    private fun onStrategyClick(s: Strategy) { StrategyDetailFragment().apply { this.strategy = s; onSave = { u -> engine?.apply { removeStrategy(u.id); registerStrategy(u); refreshList() } } }.show(parentFragmentManager, "detail") }
    private fun onStrategyToggle(s: Strategy) { engine?.setEnabled(s.id, !engine!!.isEnabled(s.id)) }
    private fun showResultsDialogSimple(results: List<ScreeningResult>) { AlertDialog.Builder(requireContext()).setTitle("📊 扫描结果").setMessage(results.joinToString("\n") { r -> "${r.category.icon} ${r.strategyName}: 扫描${r.totalScanned}只, 命中${r.hitCount}只\n" + r.signals.take(5).joinToString("\n") { "  ${it.emoji} ${it.stockName}(${it.stockCode}) 强度:${it.strength}%" } }).setPositiveButton("关闭", null).show() }
    private fun showResultsDialog(results: List<ScreeningResult>) {
        val sv = ScrollView(requireContext()); val c = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        val rm = results.associateBy { it.strategyId }; val all = engine?.getStrategies()?.filter { engine!!.isEnabled(it.id) } ?: emptyList()
        for (r in all.map { s -> rm[s.id] ?: ScreeningResult(strategyId = s.id, strategyName = s.name, category = s.category, signals = emptyList(), totalScanned = 0, scanTimeMs = 0) }) {
            c.addView(TextView(requireContext()).apply { text = "${r.category.icon} ${r.strategyName}  (${r.hitCount}只 / ${r.scanTimeMs}ms)"; textSize = 15f; setTextColor(Color.parseColor("#333333")); setTypeface(null, Typeface.BOLD); setPadding(0, 16, 0, 8); setOnClickListener { if (r.signals.isNotEmpty()) openResultDialog(r) } })
            if (r.signals.isEmpty()) { c.addView(TextView(requireContext()).apply { text = "  ⚠️ 无命中信号"; textSize = 12f; setTextColor(Color.parseColor("#999999")); setPadding(0, 0, 0, 8) }); continue }
            val t = TableLayout(requireContext()).apply { isStretchAllColumns = true }; val hr = TableRow(requireContext()); for (h in listOf("名称","代码","强度","价格","涨幅")) hr.addView(TextView(requireContext()).apply { text = h; textSize = 11f; setTextColor(Color.parseColor("#999999")); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(4,4,4,4) }); t.addView(hr)
            for (s in r.signals.distinctBy { it.stockCode }.take(10)) { val row = TableRow(requireContext()); row.setOnClickListener { openResultDialog(r) }; val col = when { s.strength >= 80 -> Color.parseColor("#E65100"); s.strength >= 60 -> Color.parseColor("#2E7D32"); else -> Color.parseColor("#666666") }; for (cell in listOf(s.stockName, s.stockCode, "${s.strength}%", "%.2f".format(s.currentPrice), "${"%.2f".format(s.changePercent)}%")) row.addView(TextView(requireContext()).apply { text = cell; textSize = 11f; setTextColor(col); gravity = Gravity.CENTER; setPadding(2,4,2,4) }); t.addView(row) }
            c.addView(t)
        }
        c.addView(View(requireContext()).apply { layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 2).apply { topMargin = 16; bottomMargin = 8 }; setBackgroundColor(Color.parseColor("#DDDDDD")) })
        c.addView(TextView(requireContext()).apply { text = "🤖 AI 综合预测（多策略+新闻因子+周期轮动）"; textSize = 16f; setTextColor(Color.parseColor("#1565C0")); setTypeface(null, Typeface.BOLD); setPadding(0,8,0,8) })
        val ail = TextView(requireContext()).apply { text = "  ⏳ AI 正在分析中，请稍候..."; textSize = 12f; setTextColor(Color.parseColor("#999999")) }; c.addView(ail)
        sv.addView(c); AlertDialog.Builder(requireContext()).setTitle("📊 扫描结果 · 点击策略名查看详情").setView(sv).setPositiveButton("关闭", null).show()
        lifecycleScope.launch { try { val ai = AIPredictionEngine(requireContext()); val pr = ai.predict(results, browsingDate.toString()); if (pr != null && pr.topPicks.isNotEmpty()) requireActivity().runOnUiThread { ail.text = ""; c.addView(TextView(requireContext()).apply { text = "  📋 使用方案${pr.mode}: ${pr.modeReason}"; textSize = 11f; setTextColor(Color.parseColor("#E65100")); setPadding(0,4,0,8) }); c.addView(TextView(requireContext()).apply { text = "  📊 市场判断: ${pr.marketOutlook}"; textSize = 11f; setTextColor(Color.parseColor("#666666")); setPadding(0,0,0,4) }) } else requireActivity().runOnUiThread { ail.text = "  ⚠️ AI 预测暂不可用" } } catch (e: Exception) { requireActivity().runOnUiThread { ail.text = "  ⚠️ AI 预测失败: ${e.message?.take(30)}" } } }
    }
    private fun openResultDialog(result: ScreeningResult) { StrategyResultDialogFragment().apply { this.result = result; onAskQuestion = { q -> val ctx = buildString { appendLine("基于以下策略扫描结果，请回答用户问题："); appendLine("策略: ${result.strategyName} | 扫描: ${result.totalScanned}只 | 命中: ${result.hitCount}只"); for ((i,s) in result.signals.take(10).withIndex()) appendLine("| ${i+1} | ${s.stockName} | ${s.stockCode.takeLast(6)} | ${s.strength}% | ${"%.2f".format(s.currentPrice)} | ${"%.2f".format(s.changePercent)}% |"); appendLine(); appendLine("用户问题: $q") }; if (activity is MainActivity) (activity as MainActivity).switchToChatAndSend(ctx) else Toast.makeText(requireContext(), "提问已记录: $q", Toast.LENGTH_SHORT).show() } }.show(parentFragmentManager, "result") }
    private fun showAddDialog() { val name = EditText(requireContext()).apply { hint = "策略名称"; setSingleLine() }; val desc = EditText(requireContext()).apply { hint = "策略描述"; setSingleLine() }; AlertDialog.Builder(requireContext()).setTitle("添加自定义策略").setView(LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(32,16,32,8); addView(name, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = 12 }); addView(desc, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) }).setPositiveButton("创建") { _, _ -> val n = name.text.toString().trim(); if (n.isNotBlank()) { val id = "custom_${System.currentTimeMillis()}"; engine?.registerStrategy(object : Strategy { override val id = id; override var name = n; override var description = desc.text.toString().trim().ifEmpty { "自定义策略" }; override val category = StrategyCategory.CUSTOM; override val config = StrategyConfig.fullMarket(20); override var weightFactors = listOf(WeightFactor("default","综合评分",100,"默认权重")); override val source = StrategySource.USER_CUSTOM; override suspend fun screen() = Result.success(ScreeningResult(strategyId=id, strategyName=n, category=StrategyCategory.CUSTOM, signals=emptyList(), totalScanned=0, scanTimeMs=0)); override suspend fun isAvailable() = false }); refreshList() } }.setNegativeButton("取消", null).show() }
    private fun importHistoricalData() { scanBtn.isEnabled = false; scanBtn.text = "⏳"; progressBar.visibility = View.VISIBLE; statusTv.text = "  正在从东方财富拉取历史K线..."; lifecycleScope.launch { try { val f = com.chin.stockanalysis.strategy.data.HistoricalDataFetcher(requireContext()); val t = f.fetchAllHistoricalData(60) { p -> lifecycleScope.launch(Dispatchers.Main) { statusTv.text = "  进度: ${p.completedStocks}/${p.totalStocks} 只 · ${p.totalRecords} 条" } }; withContext(Dispatchers.Main) { scanBtn.isEnabled = true; scanBtn.text = "执行"; progressBar.visibility = View.GONE; statusTv.text = "  ✅ 导入完成 · $t 条历史记录" } } catch (e: Exception) { withContext(Dispatchers.Main) { scanBtn.isEnabled = true; scanBtn.text = "执行"; progressBar.visibility = View.GONE; statusTv.text = "  导入失败: ${e.message}" } } } }
    private fun saveBacktestData(results: List<ScreeningResult>) { lifecycleScope.launch { try { val be = com.chin.stockanalysis.strategy.backtest.BacktestEngine(requireContext()); for (r in results) be.savePredictions(r.strategyId, r.strategyName, r) } catch (e: Exception) { android.util.Log.w("SLF", "保存预测失败: ${e.message}") } } }

    override fun onResume() { super.onResume(); refreshHotSectors() }
    override fun onDestroyView() { super.onDestroyView(); engine?.cancelScan() }
}