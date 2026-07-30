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
import com.chin.stockanalysis.strategy.backtest.WeightCalibrator
import com.chin.stockanalysis.strategy.data.StockScreener
import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.models.StrategySignal
import com.chin.stockanalysis.strategy.models.WeightFactor
import com.chin.stockanalysis.strategy.predict.AIPredictionEngine
import com.chin.stockanalysis.strategy.strategies.*
import com.chin.stockanalysis.agent.core.AgentOrchestrator
import com.chin.stockanalysis.agent.core.AnalysisMode
import com.chin.stockanalysis.agent.core.AnalysisStep
import com.chin.stockanalysis.agent.core.AnalysisStepListener
import com.chin.stockanalysis.agent.core.DeepAnalystEngine
import com.chin.stockanalysis.agent.core.analyzeStock
import com.chin.stockanalysis.agent.pipeline.ui.PipelineProgressView
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
    private lateinit var tuneBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var aiPipelineBtn: Button
    private lateinit var pipelineProgressView: PipelineProgressView

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

    // 板塊上下文（供 AI 選股加權使用）
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
        // 確保熱門板塊調度器已啟動 (不管 MarketHotFragment 有沒有建立)
        EastMoneyHotSectorSource.startPoolScheduler(lifecycleScope)
        StrategyEngineHolder.init(ctx)
        engine = StrategyEngineHolder.get()
        strategyCount = engine?.getStrategies()?.size ?: 8
        // 初始化 StockScreener（實時掃描用）
        val repo = StockDataSourceFactory.createDefaultRepository(ctx)
        screener = StockScreener(repo, ctx)
        lifecycleScope.launch(Dispatchers.IO) {
            engine?.getStrategies()?.forEach { strategy ->
                StrategySelfTuner.loadLatestTunedWeights(requireContext(), strategy.id)?.let { tuned ->
                    strategy.weightFactors = tuned; Log.i("SLF", "加载拟合权重: ${strategy.id}")
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

        val row2 = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(8,4,8,4); setBackgroundColor(Color.WHITE) }
        scanBtn = Button(requireContext()).apply { text = "执行策略"; textSize = 11f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#E65100")); setPadding(6,6,6,6); setMinWidth(0); setMinimumWidth(0); layoutParams = LayoutParams(0,60,1.2f).apply { marginEnd = 3 }; setOnClickListener { runSelectedStrategies() } }; row2.addView(scanBtn)
        tuneBtn = Button(requireContext()).apply { text = "拟合(90%)"; textSize = 11f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#EF6C00")); setPadding(6,6,6,6); setMinWidth(0); setMinimumWidth(0); layoutParams = LayoutParams(0,60,1.1f).apply { marginEnd = 3 }; setOnClickListener { runSelfTune() } }; row2.addView(tuneBtn)
        val dataBtn = Button(requireContext()).apply { text = "数据"; textSize = 11f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#455A64")); setPadding(6,6,6,6); setMinWidth(0); setMinimumWidth(0); layoutParams = LayoutParams(0,60,0.9f).apply { marginEnd = 3 }; setOnClickListener { showDataMenu() } }; row2.addView(dataBtn)
        val importBtn = Button(requireContext()).apply { text = "导入"; textSize = 11f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#2E7D32")); setPadding(6,6,6,6); setMinWidth(0); setMinimumWidth(0); layoutParams = LayoutParams(0,60,1.1f).apply { marginEnd = 3 }; setOnClickListener { importHistoricalData() } }; row2.addView(importBtn)
        val addCustomBtn = Button(requireContext()).apply { text = "+策略"; textSize = 11f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#1565C0")); setPadding(6,6,6,6); setMinWidth(0); setMinimumWidth(0); layoutParams = LayoutParams(0,60,1.1f).apply { marginEnd = 3 }; setOnClickListener { showAddDialog() } }; row2.addView(addCustomBtn)
        aiPipelineBtn = Button(requireContext()).apply { text = "🧠 Agent 分析"; textSize = 11f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#6A1B9A")); setPadding(6,6,6,6); setMinWidth(0); setMinimumWidth(0); layoutParams = LayoutParams(0,60,1.3f).apply { marginEnd = 3 }; setOnClickListener { runAIPipeline() } }; row2.addView(aiPipelineBtn)
        layout.addView(row2)

        val statusRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(16,2,16,4); setBackgroundColor(Color.WHITE) }
        progressBar = ProgressBar(requireContext()).apply { visibility = View.GONE; layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = 8 } }; statusRow.addView(progressBar)
        statusTv = TextView(requireContext()).apply { text = "$strategyCount 个策略已就绪"; textSize = 11f; setTextColor(Color.parseColor("#AAAAAA")) }; statusRow.addView(statusTv)
        layout.addView(statusRow)

        // Agent Pipeline 進度面板（默認隱藏）
        pipelineProgressView = PipelineProgressView(requireContext()).apply { visibility = View.GONE }
        layout.addView(pipelineProgressView)

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
                // API 未就緒時，用 AIHotSectorProvider 作為 fallback
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
    private fun getSectorLabel(stockCode: String, stockName: String = ""): String {
        val cacheKey = "$stockCode|$stockName"
        sectorLabelCache[cacheKey]?.let { return it }
        // 優先使用硬編碼映射（無 IO，不會阻塞主線程）
        val hardcoded = if (stockName.isNotEmpty()) hardcodedSubSector(stockName) else ""
        if (hardcoded.isNotEmpty()) {
            sectorLabelCache[cacheKey] = hardcoded
            return hardcoded
        }
        // 無硬編碼匹配時返回佔位符，避免在 RecyclerView 綁定時阻塞主線程
        sectorLabelCache[cacheKey] = "-"
        return "-"
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
    private fun hardcodedSubSector(name: String): String {
        val map = mapOf("生益" to "覆铜板","沪电" to "PCB","深南" to "基板","鹏鼎" to "软板","景旺" to "PCB","世运" to "PCB","超声" to "PCB","三环" to "MLCC","风华" to "MLCC","火炬" to "MLCC","洁美" to "MLCC","中际" to "光模块","新易盛" to "光模块","天孚" to "光器件","光迅" to "光模块","德科立" to "光模块","联特" to "光模块","意华" to "连接器","鼎通" to "连接器","立讯" to "代工","博创" to "光器件","太辰" to "光器件","东山" to "软板","信维" to "射频","闻泰" to "代工","韦尔" to "CIS","兆易" to "存储","长电" to "封测","通富" to "封测","华天" to "封测","北方华创" to "设备","中微" to "刻蚀","盛美" to "清洗","拓荆" to "镀膜","芯源" to "涂胶","江丰" to "靶材","安集" to "抛光液","中芯" to "代工","华虹" to "代工","斯达" to "IGBT","时代电气" to "IGBT","中兴" to "通信","烽火" to "通信","宁德" to "电池","比亚迪" to "整车","亿纬" to "电池","赣锋" to "锂矿","天齐" to "锂矿","华友" to "钴镍","中矿" to "铯矿","紫金" to "金铜","洛阳钼业" to "钼矿","西部矿业" to "铜矿","中科" to "超算","浪潮" to "服务器","曙光" to "超算","海光" to "CPU","寒武纪" to "AI芯","金山" to "办公","中望" to "CAD","德赛西威" to "智驾","均胜" to "安全","阳光" to "逆变器","固德" to "逆变器","锦浪" to "逆变器","晶澳" to "组件","隆基" to "硅片","通威" to "硅料","福莱" to "玻璃","福斯" to "胶膜","泰格" to "CXO","药明" to "CXO","康龙" to "CXO","凯莱英" to "CXO","迈瑞" to "器械","联影" to "影像","鱼跃" to "家用","恒瑞" to "创新药","百济" to "创新药","爱尔" to "眼科","通策" to "口腔")
        for ((kw, label) in map) { if (name.contains(kw)) return label }; return ""
    }

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
                        if (result != null && result.getOrNull()?.hitCount ?: 0 > 0) {
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
            statusTv.text = "  \uD83D\uDCCB 使用快取結果（${(nowMs - lastExecTimeMs) / 1000}秒前）"
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

        // 統一構建市場上下文（含用戶關注/多周期熱門/回彈板塊/指數/板塊大年）
        val marketCtx = com.chin.stockanalysis.strategy.sector.StrategyMarketContext.build(requireContext(), selectedDate)

        // 1. 當前熱門板塊股票
        val sectorStockCodes = if (currentHotSectors.isEmpty()) emptySet()
        else { val codes = mutableSetOf<String>(); for (name in currentHotSectors) codes.addAll(db.sectorStockDao().getStockCodesBySector(name)); codes }

        // 2. 用戶關注板塊股票（從統一上下文獲取）
        val userFocusCodes = if (marketCtx.userFocusSectors.isEmpty()) emptySet()
        else { val codes = mutableSetOf<String>(); for (name in marketCtx.userFocusSectors) codes.addAll(db.sectorStockDao().getStockCodesBySector(name)); codes }

        // 3. 回彈板塊股票（從統一上下文獲取）
        val bounceSectors = marketCtx.bounceSectors
        val bounceCodes = if (bounceSectors.isEmpty()) emptySet()
        else { val codes = mutableSetOf<String>(); for (b in bounceSectors.take(5)) codes.addAll(db.sectorStockDao().getStockCodesBySector(b.sectorName)); codes }

        // 合併股票池：熱門 + 用戶關注 + 回彈
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
                    // 後處理：用戶關注板塊 + 回彈板塊 額外加權（使用統一上下文方法）
                    val boostedSignals = raw.signals.map { signal ->
                        var bonus = 0
                        // 用戶關注板塊加成
                        bonus += marketCtx.getFocusBoostForStock(signal.stockName)
                        // 回彈板塊加成（回調天數越多加分越多：1天+1, 2天+2, 3天+3...）
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
        // 保存板塊上下文供 AI 選股使用
        lastSectorContext = marketCtx.toAiSectorContext()

        val boostInfo = if (userFocusCodes.isNotEmpty() || bounceCodes.isNotEmpty()) " · 關注${userFocusCodes.size}只·回彈${bounceCodes.size}只" else ""
        if (isAdded) { withContext(Dispatchers.Main) { scanBtn.isEnabled = true; scanBtn.text = "执行策略"; progressBar.visibility = View.GONE; statusTv.text = "  已完成 · $selectedDate（$sectorLabel）$boostInfo"; saveBacktestData(results); showResults(results) } }
        else { pendingResults = results }
    }

    private fun runSelfTune() {
        val eng = engine ?: return
        tuneBtn.isEnabled = false; tuneBtn.text = "\u23F3"; progressBar.visibility = View.VISIBLE; statusTv.text = "  \uD83D\uDD27 正在自测拟合(目标90%)..."
        lifecycleScope.launch {
            try { val report = StrategySelfTuner(requireContext()).selfTune(eng.getEnabledStrategies(), 30, 0.90f); withContext(Dispatchers.Main) { tuneBtn.isEnabled = true; tuneBtn.text = "拟合(90%)"; progressBar.visibility = View.GONE; statusTv.text = "  \u2705 拟合完成"; showFullScreenTuneReport(report.summary) } }
            catch (e: Exception) { withContext(Dispatchers.Main) { tuneBtn.isEnabled = true; tuneBtn.text = "拟合(90%)"; progressBar.visibility = View.GONE; statusTv.text = "  拟合失败: ${e.message?.take(30)}" } }
        }
    }

    private fun showFullScreenTuneReport(text: String) {
        ScrollView(requireContext()).also { sv ->
            sv.addView(TextView(requireContext()).apply { this.text = text; setTextColor(Color.parseColor("#333333")); textSize = 10f; setPadding(dp(16), dp(12), dp(16), dp(12)); setLineSpacing(2f, 1.1f); setTypeface(Typeface.MONOSPACE); isVerticalScrollBarEnabled = true })
            AlertDialog.Builder(requireContext()).setTitle("策略自测拟合报告(目标90%)").setView(sv).setPositiveButton("关闭") { d, _ -> d.dismiss() }.create().apply { show(); window?.setLayout(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT); getButton(AlertDialog.BUTTON_POSITIVE)?.apply { gravity = Gravity.END or Gravity.BOTTOM } }
        }
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
                        // 顯示板塊加權信息
                        val sc = lastSectorContext
                        if (sc != null && (sc.userFocusSectors.isNotEmpty() || sc.bounceSectors.isNotEmpty())) {
                            val sectorInfo = buildString {
                                if (sc.userFocusSectors.isNotEmpty()) append("關注板塊: ${sc.userFocusSectors.joinToString("、")} | ")
                                if (sc.bounceSectors.isNotEmpty()) append("回彈板塊: ${sc.bounceSectors.take(3).joinToString("、") { it.sectorName }}")
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
            // 按持仓周期分组渲染：超短線 / 短線 / 中線 / 長線
            val sections = HoldingPeriod.values().map { period ->
                period to eng.getStrategiesByPeriod(period)
            }
            val resultsMap = cachedResults?.associateBy { it.strategyId } ?: emptyMap()
            adapter = GroupedStrategyAdapter(sections, ::onStrategyClick, ::onStrategyToggle, resultsMap)
            recyclerView.adapter = adapter
        }
    }
    private fun onStrategyClick(s: Strategy) { StrategyDetailFragment().apply { this.strategy = s; onSave = { u -> engine?.apply { removeStrategy(u.id); registerStrategy(u); refreshList(); strategyCount = engine?.getStrategies()?.size ?: strategyCount } } }.show(parentFragmentManager, "detail") }
    private fun onStrategyToggle(s: Strategy) { engine?.setEnabled(s.id, !engine!!.isEnabled(s.id)) }

    /**
     * 按持仓周期分组的策略列表适配器。
     * 沙盒只读模式：保留点击策略执行与启用开关（参与执行过滤），不提供任何买入按钮。
     * 每个周期一组，组标题使用文字标签（超短線 / 短線 / 中線 / 長線），不使用 emoji。
     * 每张策略卡片底部展示该策略当前选出的 Top 3 股票（若有信号）。
     */
    private inner class GroupedStrategyAdapter(
        private val sections: List<Pair<HoldingPeriod, List<Strategy>>>,
        private val onItemClick: (Strategy) -> Unit,
        private val onToggle: (Strategy) -> Unit,
        private val resultsMap: Map<String, ScreeningResult> = emptyMap()
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val typeHeader = 0
        private val typeCard = 1

        private val counts: Map<HoldingPeriod, Int> = sections.associate { it.first to it.second.size }

        // 扁平化：周期标题(HEADER) + 该周期下的策略(CARD)，空周期不渲染
        private val flat: List<Any> = ArrayList<Any>().apply {
            sections.forEach { (period, list) ->
                if (list.isNotEmpty()) { add(period); addAll(list) }
            }
        }

        override fun getItemViewType(position: Int): Int =
            if (flat[position] is HoldingPeriod) typeHeader else typeCard

        override fun getItemCount(): Int = flat.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == typeHeader) HeaderVH(makeHeader(parent)) else CardVH(makeCard(parent))

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = flat[position]
            when (holder) {
                is HeaderVH -> holder.bind(item as HoldingPeriod)
                is CardVH -> holder.bind(item as Strategy)
            }
        }

        private fun makeHeader(parent: ViewGroup): TextView = TextView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(12), dp(14), dp(12), dp(4))
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            textSize = 14f
            setTextColor(Color.parseColor("#1A1A2E"))
            setTypeface(null, Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#EDEFF5"))
        }

        private fun makeCard(parent: ViewGroup): LinearLayout = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(20, 16, 20, 16)
            elevation = 4f
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(12, 6, 12, 6)
            }
        }

        inner class HeaderVH(val tv: TextView) : RecyclerView.ViewHolder(tv) {
            fun bind(period: HoldingPeriod) {
                tv.text = "${period.label}  ·  ${counts[period] ?: 0} 个策略"
            }
        }

        inner class CardVH(val card: LinearLayout) : RecyclerView.ViewHolder(card) {
            fun bind(strategy: Strategy) {
                card.removeAllViews()
                val ctx = card.context
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
            }
        }
    }
    private fun openResultDialog(result: ScreeningResult) { StrategyResultDialogFragment().apply { this.result = result; onAskQuestion = { q -> val ctx = buildString { appendLine("基于以下策略扫描结果，请回答用户问题："); appendLine("策略: ${result.strategyName} | 扫描: ${result.totalScanned}只 | 命中: ${result.hitCount}只"); for ((i, s) in result.signals.take(10).withIndex()) appendLine("| ${i + 1} | ${s.stockName} | ${s.stockCode.takeLast(6)} | ${s.strength}% | ${"%.2f".format(s.currentPrice)} | ${"%.2f".format(s.changePercent)}% |"); appendLine(); appendLine("用户问题: $q") }; if (activity is MainActivity) (activity as MainActivity).switchToChatAndSend(ctx) else Toast.makeText(requireContext(), "提问已记录: $q", Toast.LENGTH_SHORT).show() } }.show(parentFragmentManager, "result") }
    private fun showAddDialog() { val name = EditText(requireContext()).apply { hint = "策略名称"; setSingleLine() }; val desc = EditText(requireContext()).apply { hint = "策略描述"; setSingleLine() }; AlertDialog.Builder(requireContext()).setTitle("添加自定义策略").setView(LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 8); addView(name, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = 12 }); addView(desc, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) }).setPositiveButton("创建") { _, _ -> val n = name.text.toString().trim(); if (n.isNotBlank()) { val id = "custom_${System.currentTimeMillis()}"; engine?.registerStrategy(object : Strategy { override val id = id; override var name = n; override var description = desc.text.toString().trim().ifEmpty { "自定义策略" }; override val category = StrategyCategory.CUSTOM; override val config = StrategyConfig.fullMarket(20); override var weightFactors = listOf(WeightFactor("default", "综合评分", 100, "默认权重")); override val source = StrategySource.USER_CUSTOM; override suspend fun screen() = Result.success(ScreeningResult(strategyId = id, strategyName = n, category = StrategyCategory.CUSTOM, signals = emptyList(), totalScanned = 0, scanTimeMs = 0)); override suspend fun isAvailable() = false }); refreshList(); strategyCount = engine?.getStrategies()?.size ?: strategyCount } }.setNegativeButton("取消", null).show() }
    private fun importHistoricalData() {
        scanBtn.isEnabled = false; scanBtn.text = "\u23F3"; progressBar.visibility = View.VISIBLE
        val days = when (selectedHotPeriod) { 0->1; 1->3; 2->10; 3->30; 4->50; 5->100; else->60 }
        val label = when (selectedHotPeriod) { 0->"当日"; 1->"近3日"; 2->"近10日"; 3->"近30日"; 4->"近50日"; 5->"近100日"; else->"历史" }
        val useStartDate = browsingDate
        statusTv.text = "  正在从东方财富拉取 $browsingDate ~ 至今 的${label}K线..."
        lifecycleScope.launch {
            try {
                val f = com.chin.stockanalysis.strategy.data.HistoricalDataFetcher(requireContext())
                val t = f.fetchAllHistoricalData(days, force = true, startDateOverride = useStartDate) { p ->
                    lifecycleScope.launch(Dispatchers.Main) { statusTv.text = "  进度: ${p.completedStocks}/${p.totalStocks} 只 · ${p.totalRecords} 条" }
                }
                withContext(Dispatchers.Main) {
                    scanBtn.isEnabled = true; scanBtn.text = "执行策略"; progressBar.visibility = View.GONE
                    statusTv.text = "  \u2705 导入完成 · $t 条历史记录"
                    val recent = TradingDayPickerView.recentTradingDay()
                    if (browsingDate != recent) { browsingDate = recent; isBrowsing = false; datePicker.selectedDate = recent; refreshDateUI() }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { scanBtn.isEnabled = true; scanBtn.text = "执行策略"; progressBar.visibility = View.GONE; statusTv.text = "  导入失败: ${e.message}" }
            }
        }
    }
    private fun exportSnapshotData() {
        val days = when (selectedHotPeriod) { 0->1; 1->3; 2->10; 3->30; 4->50; 5->100; else->1 }
        val label = when (selectedHotPeriod) { 0->"1日"; 1->"3日"; 2->"10日"; 3->"30日"; 4->"50日"; 5->"100日"; else->"当日" }
        statusTv.text = "  \uD83D\uDCE4 正在导出${label}K线数据..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val allDates = db.dailySnapshotDao().getAvailableDates(days + 5).sorted().takeLast(days)
                if (allDates.isEmpty()) { withContext(Dispatchers.Main) { statusTv.text = "  \u26A0\uFE0F 无可用日期数据"; Toast.makeText(requireContext(), "数据库中没有K线数据，请先导入", Toast.LENGTH_SHORT).show() }; return@launch }
                val sb = StringBuilder()
                sb.appendLine("stockCode,stockName,date,open,high,low,close,volume,amount,changePct,turnoverRate")
                var totalRows = 0
                for (date in allDates) { try { val snaps = db.dailySnapshotDao().getByDate(date); for (snap in snaps) { sb.appendLine("${snap.code},${snap.name},${snap.date},${snap.open},${snap.high},${snap.low},${snap.close},${snap.volume},${snap.amount},${snap.changePct},${snap.turnoverRate}"); totalRows++ } } catch (_: Exception) {} }
                if (totalRows == 0) { withContext(Dispatchers.Main) { statusTv.text = "  \u26A0\uFE0F 无快照数据可导出"; Toast.makeText(requireContext(), "无数据可导出", Toast.LENGTH_SHORT).show() }; return@launch }
                val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val fileName = "StockAnalysis_snapshot_${label}_${java.time.LocalDate.now()}.csv"
                val file = java.io.File(dir, fileName)
                file.writeText(sb.toString())
                val sizeKb = file.length() / 1024
                withContext(Dispatchers.Main) { statusTv.text = "  \u2705 已导出 ${allDates.size}天K线数据 (" + totalRows + "行, " + sizeKb + "KB)"; Toast.makeText(requireContext(), "已保存到: Downloads/$fileName (" + totalRows + "行)", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { statusTv.text = "  导出失败: ${e.message?.take(30)}"; Toast.makeText(requireContext(), "导出失败: ${e.message}", Toast.LENGTH_LONG).show() } }
        }
    }

    /** 導出熱門板塊數據（使用統一市場上下文） */
    private fun exportHotSectorData() {
        statusTv.text = "  \uD83D\uDD25 正在導出熱門板塊數據..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ctx = com.chin.stockanalysis.strategy.sector.StrategyMarketContext.build(requireContext())
                val sb = StringBuilder()
                sb.appendLine("\uD83D\uDD25 熱門板塊數據導出")
                sb.appendLine("導出時間: ${java.time.LocalDate.now()}")
                sb.appendLine("大盤方向: ${ctx.indexSnapshot.tripleVote}")
                sb.appendLine()

                // 用戶關注板塊
                if (ctx.userFocusSectors.isNotEmpty()) {
                    sb.appendLine("━━ 用戶關注板塊 ━━")
                    for (s in ctx.userFocusSectors) { sb.appendLine("  • $s") }
                    sb.appendLine()
                }

                // 多周期熱門板塊
                if (ctx.todayHotSectors.isNotEmpty()) {
                    sb.appendLine("━━ 今日熱門板塊 Top 3 ━━")
                    for (s in ctx.todayHotSectors) { sb.appendLine("  • $s") }
                    sb.appendLine()
                }
                if (ctx.weeklyHotSectors.isNotEmpty()) {
                    sb.appendLine("━━ 周熱門板塊（近7日）Top 5 ━━")
                    for (s in ctx.weeklyHotSectors) { sb.appendLine("  • $s") }
                    sb.appendLine()
                }
                if (ctx.monthlyHotSectors.isNotEmpty()) {
                    sb.appendLine("━━ 月熱門板塊（近30日）Top 5 ━━")
                    for (s in ctx.monthlyHotSectors) { sb.appendLine("  • $s") }
                    sb.appendLine()
                }
                if (ctx.quarterlyHotSectors.isNotEmpty()) {
                    sb.appendLine("━━ 季度熱門板塊（近90日）Top 5 ━━")
                    for (s in ctx.quarterlyHotSectors) { sb.appendLine("  • $s") }
                    sb.appendLine()
                }

                // 回彈板塊
                if (ctx.bounceSectors.isNotEmpty()) {
                    sb.appendLine("━━ 回彈板塊（連熱≥3天 + 回調後反彈） ━━")
                    for (b in ctx.bounceSectors.take(10)) {
                        sb.appendLine("  • ${b.sectorName}: 連熱${b.consecutiveHotDays}天 | 近3天${"%.2f".format(b.recentDropPct)}% | 今日${"%.2f".format(b.todayBouncePct)}% | 反彈分${"%.1f".format(b.bounceScore)}")
                    }
                    sb.appendLine()
                }

                // AI 板塊大年
                if (ctx.aiYearDetection != "未檢測" && ctx.aiYearDetection != "檢測失敗") {
                    sb.appendLine("━━ AI 板塊大年檢測 ━━")
                    sb.appendLine("  ${ctx.aiYearDetection}")
                }

                val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val fileName = "StockAnalysis_hot_sectors_${java.time.LocalDate.now()}.txt"
                val file = java.io.File(dir, fileName)
                file.writeText(sb.toString())
                withContext(Dispatchers.Main) { statusTv.text = "  \u2705 已導出熱門板塊數據"; Toast.makeText(requireContext(), "已保存到: Downloads/$fileName", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { statusTv.text = "  導出失敗: ${e.message?.take(30)}"; Toast.makeText(requireContext(), "導出失敗: ${e.message}", Toast.LENGTH_LONG).show() } }
        }
    }

    /** 導出策略報告 */
    private fun exportStrategyReport() {
        val eng = engine ?: return
        statusTv.text = "  \uD83D\uDCCB 正在導出策略報告..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val sb = StringBuilder()
                sb.appendLine("\uD83D\uDCCB 策略配置報告")
                sb.appendLine("導出時間: ${java.time.LocalDate.now()}")
                sb.appendLine()

                for (strategy in eng.getStrategies().filter { eng.isEnabled(it.id) }) {
                    sb.appendLine("━━ ${strategy.name} (${strategy.id}) ━━")
                    sb.appendLine("  類別: ${strategy.category.label}")
                    sb.appendLine("  權重因子:")
                    for (f in strategy.weightFactors) { sb.appendLine("    - ${f.label}: ${f.weight}%") }
                    val snapshots = try { db.strategyWeightSnapshotDao().getByStrategy(strategy.id) } catch (_: Exception) { emptyList() }
                    if (snapshots.isNotEmpty()) {
                        val latest = snapshots.first()
                        sb.appendLine("  最近擬合: ${latest.date} | 命中: ${latest.hitCount}")
                    }
                    sb.appendLine()
                }

                val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val fileName = "StockAnalysis_strategy_report_${java.time.LocalDate.now()}.txt"
                val file = java.io.File(dir, fileName)
                file.writeText(sb.toString())
                withContext(Dispatchers.Main) { statusTv.text = "  \u2705 已導出策略報告"; Toast.makeText(requireContext(), "已保存到: Downloads/$fileName", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { statusTv.text = "  導出失敗: ${e.message?.take(30)}"; Toast.makeText(requireContext(), "導出失敗: ${e.message}", Toast.LENGTH_LONG).show() } }
        }
    }

    /** 顯示市場記憶設置對話框 */
    private fun showMarketMemoryDialog() {
        val memory = com.chin.stockanalysis.strategy.sector.UserMarketMemory(requireContext())
        val current = memory.focusSectors.joinToString(", ")
        val input = android.widget.EditText(requireContext()).apply {
            hint = "輸入關注板塊，用逗號分隔（如：科技,半導體,光通信）"
            setText(current)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("\uD83E\uDDE0 市場記憶設置")
            .setMessage("系統會持續追蹤這些板塊，連跌時提醒，選股時優先。\n\nAI 當前判斷：${memory.aiYearDetection}")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val sectors = input.text.toString().split(",").map { it.trim() }.filter { it.isNotBlank() }
                memory.focusSectors = sectors
                // 清空緩存，下次執行時重新構建市場上下文
                com.chin.stockanalysis.strategy.sector.StrategyMarketContext.invalidateCache()
                Toast.makeText(requireContext(), "已保存 ${sectors.size} 個關注板塊，緩存已清空", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("AI 檢測板塊大年") { _, _ ->
                lifecycleScope.launch {
                    val result = memory.detectSectorYearByIndex()
                    memory.aiYearDetection = result
                    com.chin.stockanalysis.strategy.sector.StrategyMarketContext.invalidateCache()
                    Toast.makeText(requireContext(), "AI 檢測：$result", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun saveBacktestData(results: List<ScreeningResult>) { lifecycleScope.launch { try {
        val ctx = requireContext()
        val db = StockDatabase.getInstance(ctx)
        val be = com.chin.stockanalysis.strategy.backtest.BacktestEngine(ctx)
        for (r in results) be.savePredictions(r.strategyId, r.strategyName, r)
        // 同時保存到 dailyPeriodResultDao（供 showScanHistory 查詢）
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

    private fun showDataMenu() {
        val memory = com.chin.stockanalysis.strategy.sector.UserMarketMemory(requireContext())
        val userSectors = memory.focusSectors.takeIf { it.isNotEmpty() }?.joinToString("、") ?: "未設置"
        val options = arrayOf(
            "\uD83D\uDCE5 拉取股票报告",
            "\uD83D\uDCCA 量化选股报告",
            "\uD83D\uDD25 導出熱門板塊數據（用戶關注：$userSectors）",
            "\uD83D\uDCCB 導出策略報告",
            "\uD83E\uDDE0 市場記憶設置",
            "\uD83D\uDD04 刷新市場上下文緩存"
        )
        AlertDialog.Builder(requireContext())
            .setTitle("数据中心")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showFetchReport()
                    1 -> showScanHistory()
                    2 -> exportHotSectorData()
                    3 -> exportStrategyReport()
                    4 -> showMarketMemoryDialog()
                    5 -> { com.chin.stockanalysis.strategy.sector.StrategyMarketContext.invalidateCache(); Toast.makeText(requireContext(), "市場上下文緩存已清空", Toast.LENGTH_SHORT).show() }
                }
            }.show()
    }

    private fun showFetchReport() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val latestDates = db.dailySnapshotDao().getAvailableDates(20).sorted()
                if (latestDates.size < 2) { withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "需先导入数据", Toast.LENGTH_SHORT).show() }; return@launch }
                val sb = StringBuilder()
                sb.appendLine("\uD83D\uDCE5 拉取股票报告 (最近 ${latestDates.size} 天)")
                sb.appendLine()
                for (date in latestDates.takeLast(5)) {
                    val snaps = db.dailySnapshotDao().getByDate(date)
                    sb.appendLine("\u2501\u2501\u2501 $date \u2501\u2501\u2501")
                    sb.appendLine("  总股票数: ${snaps.size}")
                    val avgPct = snaps.map { it.changePct }.average().let { String.format("%.2f", it) }
                    val posCount = snaps.count { it.changePct > 0 }
                    sb.appendLine("  上涨数: $posCount / ${snaps.size} (${(posCount * 100.0 / snaps.size).let { "%.1f".format(it) }}%)")
                    sb.appendLine("  平均涨幅: ${avgPct}%")
                    val topGainers = snaps.sortedByDescending { it.changePct }.take(5)
                    sb.appendLine("  Top5涨幅: ${topGainers.joinToString { "${it.name}(${String.format("%.2f", it.changePct)}%)" }}")
                    sb.appendLine()
                }
                val prefs = requireContext().getSharedPreferences("data_import", android.content.Context.MODE_PRIVATE)
                val lastImport = prefs.getString("last_import_date", "从未")
                sb.appendLine("\uD83D\uDCC5 上次导入: $lastImport")
                withContext(Dispatchers.Main) { AlertDialog.Builder(requireContext()).setTitle("拉取股票报告").setMessage(sb.toString()).setPositiveButton("关闭", null).show() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "加载失败: ${e.message}", Toast.LENGTH_SHORT).show() } }
        }
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
    // ═══════════════════════════════════════
    // 🧠 Agent 分析（AI 動態選擇模式：六智體/七智體）
    // ═══════════════════════════════════════

    private fun runAIPipeline() {
        val inputEt = EditText(requireContext()).apply {
            hint = "輸入標的（如：生益科技、光通信板塊、半導體）"
            textSize = 13f; setPadding(16, 12, 16, 12); setSingleLine(true)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("🧠 Agent 分析")
            .setMessage("輸入要分析的標的或板塊，AI 將根據賽道自動選擇分析模式：\n• 六智體通用（消費/醫藥/周期）\n• 七智體賣水人（光通信/半導體）")
            .setView(inputEt)
            .setPositiveButton("開始分析") { _, _ ->
                val target = inputEt.text.toString().trim()
                if (target.isBlank()) {
                    Toast.makeText(requireContext(), "請輸入標的", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                executeAIPipeline(target)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun executeAIPipeline(target: String) {
        aiPipelineBtn.isEnabled = false
        aiPipelineBtn.text = "⏳"
        pipelineProgressView.visibility = View.VISIBLE
        pipelineProgressView.reset()
        statusTv.text = "🧠 正在解析目標..."

        // 預建步驟卡片（DEEP = SHORT 週期 = 5 子 Agent）
        pipelineProgressView.updateSteps(DeepAnalystEngine.stepsFor(5))

        // 量化信號注入（保留原有邏輯）
        val quantProvider: suspend (String) -> List<StrategySignal> = provider@{ stockCode ->
            val eng = engine ?: return@provider emptyList<StrategySignal>()
            try {
                val feed = com.chin.stockanalysis.strategy.data.StrategyDataFeed(requireContext())
                val today = TradingDayPickerView.recentTradingDay().toString()
                val onlyMain = (view?.findViewWithTag<Switch>("mainBoardSwitch")?.isChecked == true)
                val stocks = feed.prepareFromDb(today, com.chin.stockanalysis.strategy.data.StrategyDataFeed.DataFeedConfig(onlyMainBoard = onlyMain))
                val allSignals = mutableListOf<StrategySignal>()
                for (strategy in eng.getStrategies()) {
                    if (!eng.isEnabled(strategy.id) || strategy.id == "ai_prediction") continue
                    try {
                        val result = strategy.screenWithData(stocks).getOrNull() ?: continue
                        allSignals.addAll(result.signals.filter { it.stockCode == stockCode })
                    } catch (_: Exception) { }
                }
                allSignals
            } catch (_: Exception) { emptyList() }
        }

        // 步驟進度回調 → PipelineProgressView
        val stepListener = object : AnalysisStepListener {
            override fun onStepComplete(step: AnalysisStep, summary: String, result: Map<String, Any?>) {
                lifecycleScope.launch(Dispatchers.Main) {
                    pipelineProgressView.markStepComplete(step, summary)
                    statusTv.text = "🧠 ${step.name} 完成"
                }
            }
            override fun onStepError(step: AnalysisStep, error: String) {
                lifecycleScope.launch(Dispatchers.Main) {
                    pipelineProgressView.markStepError(step, error)
                    statusTv.text = "❌ ${step.name} 錯誤: ${error.take(40)}"
                }
            }
        }

        val appCtx = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // ── 解析目標 → 股票代碼 ──
                val db = StockDatabase.getInstance(appCtx)
                val dao = db.stockBasicDao()
                val trimmed = target.trim()

                // 先嘗試直接當代碼匹配
                var code: String? = null
                var name: String? = null
                val byCode = dao.getByCode(trimmed)
                if (byCode != null) {
                    code = byCode.code; name = byCode.name
                } else {
                    // 按名稱模糊搜索，取第一個精確/最長匹配
                    val candidates = dao.searchByName(trimmed)
                    val best = candidates.firstOrNull { it.name == trimmed }
                        ?: candidates.maxByOrNull { it.name.length }
                    if (best != null) { code = best.code; name = best.name }
                }

                if (code == null) {
                    withContext(Dispatchers.Main) {
                        statusTv.text = "❌ 無法識別「$trimmed」，請輸入股票代碼或名稱"
                        aiPipelineBtn.isEnabled = true
                        aiPipelineBtn.text = "🧠 Agent"
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) { statusTv.text = "🧠 正在分析 $name($code)..." }

                // ── 統一入口：AgentOrchestrator.analyzeStock ──
                val result = AgentOrchestrator(appCtx).analyzeStock(
                    stockCode = code,
                    stockName = name,
                    mode = AnalysisMode.DEEP,
                    useAgentFramework = true,
                    stepListener = stepListener,
                    quantSignalsProvider = quantProvider
                )

                withContext(Dispatchers.Main) {
                    pipelineProgressView.showResult(result)
                    statusTv.text = if (!result.success) {
                        "❌ 分析失敗: ${result.errorMessage?.take(30) ?: "未知錯誤"}"
                    } else {
                        val passedStr = if (result.passed == true) "通過" else "未通過"
                        "✅ $name 分析完成 [${result.overallScore}分/$passedStr] 耗時${result.elapsedMs / 1000}s"
                    }
                    aiPipelineBtn.isEnabled = true
                    aiPipelineBtn.text = "🧠 Agent"

                    // 發布到跨 Tab 總線
                    if (result.success) {
                        CrossTabBus.postAiTopPicks(listOf(
                            AIPredictionEngine.AIPick(
                                stockCode = result.stockCode,
                                stockName = result.stockName,
                                compositeScore = result.overallScore,
                                upProbability = if (result.overallScore >= 60) 75 else 50,
                                rank = 1,
                                reason = "AI智能体分析: ${result.barrierLevel ?: result.recommendation ?: "綜合評估"}",
                                actionSuggestion = if (result.passed == true) "建議關注" else "風控不通過"
                            )
                        ))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTv.text = "❌ AI 智能体分析異常: ${e.message?.take(40)}"
                    aiPipelineBtn.isEnabled = true
                    aiPipelineBtn.text = "🧠 Agent"
                }
            }
        }
    }

    override fun onResume() { super.onResume(); loadHotSectors(); pendingResults?.let { showResults(it); pendingResults = null } }
    override fun onDestroyView() { super.onDestroyView(); engine?.cancelScan() }
}