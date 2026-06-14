package com.chin.stockanalysis.strategy.trade

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.util.Log
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.Strategy
import com.chin.stockanalysis.strategy.StrategyEngine
import com.chin.stockanalysis.strategy.StrategyEngineHolder
import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.models.StrategySignal
import com.chin.stockanalysis.strategy.data.StrategyDataFeed
import com.chin.stockanalysis.strategy.data.ZiplinePipeline
import com.chin.stockanalysis.strategy.predict.AIPredictionEngine
import com.chin.stockanalysis.ui.CrossTabBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * ## 自动量化 Tab — Zipline Pipeline + 定时选股 + 独立持仓
 *
 * 流程:
 *   1. Pipeline 因子计算 → 各策略独立打分
 *   2. 合并池 (多策略交集)
 *   3. AI 精选 Top3-5 → 最终推荐
 *   4. 独立持仓 (orderType="AutoQuant")  顶部显示
 */
class AutoQuantFragment : Fragment() {

    private lateinit var rootLayout: LinearLayout
    private lateinit var statusTv: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var runPipelineBtn: Button
    private lateinit var positionContainer: LinearLayout
    private lateinit var reportDivider: View
    private lateinit var resultsContainer: LinearLayout
    private var browsingDate: LocalDate = com.chin.stockanalysis.ui.TradingDayPickerView.recentTradingDay()

    private var engine: StrategyEngine? = null
    private var pipelineFactors: ZiplinePipeline.FactorSet? = null
    private var allScreenings: Map<Strategy, ScreeningResult> = emptyMap()
    private var todayStocks: List<com.chin.stockanalysis.stock.StockRealtime> = emptyList()
    private var lastTradeDate: String = ""
    private var hasPipelineResult: Boolean = false

    private var aiPicks: List<AIPredictionEngine.AIPick> = emptyList()
    private var mergedPool: Map<String, List<Pair<String, Int>>> = emptyMap()
    private var mergedStockNames: Map<String, String> = emptyMap()

    companion object { private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd") }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        rootLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#F5F6FA"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        }
        initEngine(); buildUI(); refreshPositions(); return rootLayout
    }

    private fun initEngine() {
        val ctx = requireContext().applicationContext; StrategyEngineHolder.init(ctx); engine = StrategyEngineHolder.get()
    }

    private fun buildUI() {
        rootLayout.addView(TextView(requireContext()).apply {
            text = "🤖 自动量化系统 (Zipline Pipeline + AI精选)"
            textSize = 16f; setTextColor(Color.parseColor("#1A1A2E")); setTypeface(null, Typeface.BOLD); setPadding(16, 16, 16, 8)
        })
        val configRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(8, 6, 8, 6); setBackgroundColor(Color.WHITE) }
        val dateLabelTv = TextView(requireContext()).apply { text = "📅 交易日:"; textSize = 12f; setTextColor(Color.parseColor("#333333")); setTypeface(null, Typeface.BOLD) }
        configRow.addView(dateLabelTv)
        val datePicker = com.chin.stockanalysis.ui.TradingDayPickerView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = 4; marginEnd = 6 }
            onDateChanged = { d ->
                browsingDate = d
                val isNonTrading = d.dayOfWeek == java.time.DayOfWeek.SATURDAY || d.dayOfWeek == java.time.DayOfWeek.SUNDAY || d in com.chin.stockanalysis.ui.TradingDayPickerView.CHINESE_HOLIDAYS
                dateLabelTv.text = if (isNonTrading) "📅 非交易日:" else "📅 交易日:"
            }
        }
        configRow.addView(datePicker)
        val mainBoardSwitch = Switch(requireContext()).apply { text = "仅主板"; textSize = 11f; isChecked = true; setTextColor(Color.parseColor("#333333")) }
        configRow.addView(mainBoardSwitch)
        rootLayout.addView(configRow)

        // ── 按钮行 ──
        val btnRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(4, 1, 4, 1) }
        runPipelineBtn = Button(requireContext()).apply { text = "▶ Pipeline"; textSize = 10f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#1565C0")); setPadding(4,1,4,1); setMinWidth(0); setMinimumWidth(0); layoutParams = LinearLayout.LayoutParams(0, dpToPx(22), 1f).apply { marginEnd = 1 }; setOnClickListener { runPipeline() } }; btnRow.addView(runPipelineBtn)
        val fitBtn = Button(requireContext()).apply { text = "🔧 拟合"; textSize = 10f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#EF6C00")); setPadding(4,1,4,1); setMinWidth(0); setMinimumWidth(0); layoutParams = LinearLayout.LayoutParams(0, dpToPx(22), 0.9f).apply { marginEnd = 1 }; setOnClickListener { autoFit() } }; btnRow.addView(fitBtn)
        val buyBtn = Button(requireContext()).apply { text = "▶ 买入"; textSize = 10f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#E65100")); setPadding(4,1,4,1); setMinWidth(0); setMinimumWidth(0); layoutParams = LinearLayout.LayoutParams(0, dpToPx(22), 1f).apply { marginEnd = 1 }; setOnClickListener { buyAiPicks() } }; btnRow.addView(buyBtn)
        val sellBtn = Button(requireContext()).apply { text = "💰 卖出 ▾"; textSize = 10f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#00897B")); setPadding(4,1,4,1); setMinWidth(0); setMinimumWidth(0); layoutParams = LinearLayout.LayoutParams(0, dpToPx(22), 1.3f).apply { marginEnd = 1 }; setOnClickListener { showSellMenu(it) } }; btnRow.addView(sellBtn)
        val dataBtn = Button(requireContext()).apply { text = "🗄️ 数据"; textSize = 10f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#455A64")); setPadding(4,1,4,1); setMinWidth(0); setMinimumWidth(0); layoutParams = LinearLayout.LayoutParams(0, dpToPx(22), 0.9f).apply { marginEnd = 1 }; setOnClickListener { showDataMenu() } }; btnRow.addView(dataBtn)
        val clearBtn = Button(requireContext()).apply { text = "🗑️ 清除"; textSize = 9f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#C62828")); setPadding(4,1,4,1); setMinWidth(0); setMinimumWidth(0); layoutParams = LinearLayout.LayoutParams(0, dpToPx(22), 0.8f); setOnClickListener { clearAutoQuantData() } }; btnRow.addView(clearBtn)
        rootLayout.addView(btnRow)

        val progressRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(16, 4, 16, 4) }
        progressBar = ProgressBar(requireContext()).apply { visibility = View.GONE; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = 8 } }
        progressRow.addView(progressBar)
        statusTv = TextView(requireContext()).apply { text = "就绪"; textSize = 12f; setTextColor(Color.parseColor("#AAAAAA")) }
        progressRow.addView(statusTv); rootLayout.addView(progressRow)

        rootLayout.addView(View(requireContext()).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1); setBackgroundColor(Color.parseColor("#DDDDDD")) })

        // ── 持仓区 (顶部) ──
        positionContainer = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(8, 4, 8, 4) }
        rootLayout.addView(positionContainer)

        // ── 分割线 (有结果时显示) ──
        reportDivider = View(requireContext()).apply { visibility = View.GONE; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { topMargin = 4 }; setBackgroundColor(Color.parseColor("#DDDDDD")) }
        rootLayout.addView(reportDivider)

        // ── 结果区 (底部，参考模拟交易 UI) ──
        resultsContainer = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(8, 4, 8, 4) }
        rootLayout.addView(resultsContainer)
    }

    private fun updateViewSplit() {
        if (hasPipelineResult) {
            resultsContainer.visibility = View.VISIBLE; reportDivider.visibility = View.VISIBLE
        } else { resultsContainer.visibility = View.GONE; reportDivider.visibility = View.GONE }
        rootLayout.requestLayout()
    }

    // ═══════════════════════════════════════
    // 持仓
    // ═══════════════════════════════════════

    private fun refreshPositions() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val orders = db.strategyTradeOrderDao().getRecent(100)
                    .filter { it.orderType == "AutoQuant" && (it.status == "BUYING" || it.status == "PENDING") }
                    .sortedByDescending { it.tradeDate }
                withContext(Dispatchers.Main) { renderPositionTable(orders) }
            } catch (_: Exception) {}
        }
    }

    private fun renderPositionTable(orders: List<StrategyTradeOrderEntity>) {
        positionContainer.removeAllViews()
        if (orders.isEmpty()) { positionContainer.addView(TextView(requireContext()).apply { text = "📌 暂无持仓记录"; textSize = 11f; setTextColor(Color.parseColor("#999999")); setPadding(0, 4, 0, 4) }); return }
        positionContainer.addView(TextView(requireContext()).apply { text = "📌 自动量化持仓 (${orders.size}只)"; textSize = 12f; setTextColor(Color.parseColor("#1A1A2E")); setTypeface(null, Typeface.BOLD); setPadding(0, 4, 0, 6) })
        val table = TableLayout(requireContext()).apply { isStretchAllColumns = true }
        val hr = TableRow(requireContext()); for (h in listOf("名称", "代码", "买入价", "数量", "建仓日", "操作")) hr.addView(TextView(requireContext()).apply { text = h; textSize = 9f; setTextColor(Color.parseColor("#999999")); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(2, 4, 2, 4) })
        table.addView(hr)
        for (order in orders) {
            val row = TableRow(requireContext())
            row.addView(TextView(requireContext()).apply { text = order.stockName.take(6); textSize = 10f; setTextColor(Color.parseColor("#222222")); gravity = Gravity.CENTER; setPadding(1, 3, 1, 3) })
            row.addView(TextView(requireContext()).apply { text = order.stockCode.takeLast(6); textSize = 9f; setTextColor(Color.parseColor("#666666")); gravity = Gravity.CENTER; setPadding(1, 3, 1, 3) })
            row.addView(TextView(requireContext()).apply { text = "¥${"%.2f".format(order.buyPrice)}"; textSize = 10f; setTextColor(Color.parseColor("#E53935")); gravity = Gravity.CENTER; setPadding(1, 3, 1, 3) })
            row.addView(TextView(requireContext()).apply { text = "${order.quantity}"; textSize = 10f; setTextColor(Color.parseColor("#333333")); gravity = Gravity.CENTER; setPadding(1, 3, 1, 3) })
            row.addView(TextView(requireContext()).apply { text = order.tradeDate.takeLast(5); textSize = 9f; setTextColor(Color.parseColor("#999999")); gravity = Gravity.CENTER; setPadding(1, 3, 1, 3) })
            val sellBtn = Button(requireContext()).apply { text = "卖出"; textSize = 9f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#C62828")); setMinWidth(0); setMinimumWidth(0); setPadding(4, 2, 4, 2)
                setOnClickListener { lifecycleScope.launch(Dispatchers.IO) { try { val db = StockDatabase.getInstance(requireContext()); db.strategyTradeOrderDao().updateSellInfo(order.id, "SOLD", order.buyPrice, LocalDate.now().format(DATE_FMT)+" 15:00", 0.0); withContext(Dispatchers.Main) { refreshPositions(); statusTv.text = "✅ 已卖出 ${order.stockName}" } } catch (_: Exception) {} } } }
            row.addView(sellBtn); table.addView(row)
        }
        positionContainer.addView(table)
    }

    // ═══════════════════════════════════════
    // Pipeline
    // ═══════════════════════════════════════

    private fun runPipeline() {
        val eng = engine ?: return
        runPipelineBtn.isEnabled = false; runPipelineBtn.text = "⏳"; progressBar.visibility = View.VISIBLE; statusTv.text = "检查数据..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 检查是否需要先导入数据
                val today = com.chin.stockanalysis.ui.TradingDayPickerView.recentTradingDay().format(DATE_FMT)
                val prefs = requireContext().getSharedPreferences("data_import", android.content.Context.MODE_PRIVATE)
                val lastImport = prefs.getString("last_import_date", "") ?: ""
                val db = StockDatabase.getInstance(requireContext())
                val todaySnaps = db.dailySnapshotDao().getByDate(today)
                val needImport = todaySnaps.size < 100 || lastImport != today
                if (needImport) {
                    withContext(Dispatchers.Main) { statusTv.text = "📥 数据不足，自动导入..." }
                    val f = com.chin.stockanalysis.strategy.data.HistoricalDataFetcher(requireContext())
                    f.fetchAllHistoricalData(60) { p ->
                        lifecycleScope.launch(Dispatchers.Main) { statusTv.text = "📥 导入: ${p.completedStocks}/${p.totalStocks}" }
                    }
                    prefs.edit().putString("last_import_date", today).apply()
                    withContext(Dispatchers.Main) { statusTv.text = "✅ 导入完成，开始 Pipeline" }
                }
                // 建仓日使用最近的交易日（避免非交易日）
                val tradeDay = com.chin.stockanalysis.ui.TradingDayPickerView.recentTradingDay().format(DATE_FMT)
                lastTradeDate = tradeDay
                val feed = StrategyDataFeed(requireContext())
                val stocks = feed.prepareFromDb(today, StrategyDataFeed.DataFeedConfig(onlyMainBoard = true))
                todayStocks = stocks
                if (stocks.isEmpty()) { withContext(Dispatchers.Main) { statusTv.text="⚠️ 无交易日数据"; runPipelineBtn.isEnabled=true; runPipelineBtn.text="▶ Pipeline"; progressBar.visibility=View.GONE }; return@launch }
                val pipeline = ZiplinePipeline(requireContext()); val factors = pipeline.computeAll(stocks, today, 30); pipelineFactors = factors
                val codeToName = try { StockDatabase.getInstance(requireContext()).stockBasicDao().getAll().associate { it.code to it.name } } catch (_: Exception) { emptyMap() }
                val screenings = mutableMapOf<Strategy, ScreeningResult>(); val screeningList = mutableListOf<ScreeningResult>()
                for (strategy in eng.getStrategies()) { if (!eng.isEnabled(strategy.id) || strategy.id == "ai_prediction") continue; try { val r = strategy.screenWithData(stocks); r.getOrNull()?.let { screenings[strategy]=it; screeningList.add(it) } } catch (_: Exception) {} }
                allScreenings = screenings
                // 合并池
                val pool = mutableMapOf<String, MutableList<Pair<String, Int>>>()
                for ((s, sc) in screenings) for (sig in sc.signals.distinctBy { it.stockCode }) pool.getOrPut(sig.stockCode){ mutableListOf() }.add(s.name to sig.strength)
                mergedPool = pool.mapValues { it.value.sortedByDescending { p->p.second } }
                mergedStockNames = pool.keys.associateWith { c -> screenings.values.firstNotNullOfOrNull { sc->sc.signals.find{it.stockCode==c}?.stockName } ?: codeToName[c] ?: c }
                // AI 精选
                if (screeningList.isNotEmpty()) { withContext(Dispatchers.Main) { statusTv.text = "🤖 AI 正在精选..." }; try { val p = AIPredictionEngine(requireContext()).predict(screeningList, today); if (p!=null && p.topPicks.isNotEmpty()) aiPicks = p.topPicks else aiPicks = emptyList() } catch (_: Exception) { aiPicks = emptyList() } }
                // 发布到跨Tab总线
                CrossTabBus.postMergedPool(mergedPool)
                CrossTabBus.postAiTopPicks(aiPicks)
                CrossTabBus.postStrategyResults(screeningList)
                withContext(Dispatchers.Main) {
                    showPipelineTable(screenings, factors, today); hasPipelineResult = true; updateViewSplit()
                    statusTv.text = "✅ Pipeline 完成 (${factors.ma5.size} 只有效)"; runPipelineBtn.isEnabled = true; runPipelineBtn.text = "▶ Pipeline"; progressBar.visibility = View.GONE
                }
            } catch (e: Exception) { withContext(Dispatchers.Main) { statusTv.text="❌ Pipeline 失败: ${e.message?.take(40)}"; runPipelineBtn.isEnabled=true; runPipelineBtn.text="▶ Pipeline"; progressBar.visibility=View.GONE } }
        }
    }

    private fun buyAiPicks() {
        if (aiPicks.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val existingCodes = db.strategyTradeOrderDao().getRecent(200).filter { it.orderType == "AutoQuant" && (it.status == "BUYING" || it.status == "PENDING") }.map { it.stockCode }.toSet()
                val toInsert = mutableListOf<StrategyTradeOrderEntity>()
                for (pick in aiPicks) { if (pick.stockCode in existingCodes) continue; toInsert.add(StrategyTradeOrderEntity(strategyId="AI_Selected", stockCode=pick.stockCode, stockName=pick.stockName, tradeDate=lastTradeDate, buyPrice=0.0, buyTime="", quantity=100, orderType="AutoQuant", status="BUYING", reason="AI精选: ${pick.reason.take(60)}", scoreAtBuy=pick.compositeScore, createdAt=System.currentTimeMillis())) }
                if (toInsert.isNotEmpty()) { db.strategyTradeOrderDao().insertAll(toInsert); withContext(Dispatchers.Main) { statusTv.text="✅ 已买入 ${toInsert.size} 只AI精选股"; refreshPositions(); Toast.makeText(requireContext(), "已买入 ${toInsert.size} 只", Toast.LENGTH_SHORT).show() } }
                else withContext(Dispatchers.Main) { statusTv.text="⚠️ AI精选股已全部持仓" }
            } catch (e: Exception) { withContext(Dispatchers.Main) { statusTv.text="❌ 买入失败: ${e.message?.take(30)}" } }
        }
    }

    // ═══════════════════════════════════════
    // UI 辅助
    // ═══════════════════════════════════════

    private fun getSector(code: String): String = try { kotlinx.coroutines.runBlocking { com.chin.stockanalysis.stock.database.StockDataCenter.getSectorsByStock(code).firstOrNull() ?: "" } } catch (_: Exception) { "" }

    private fun tableHeaderRow(): TableRow { val hr = TableRow(requireContext()); for (h in listOf("名称","板块","代码","强度","价格","涨幅")) hr.addView(TextView(requireContext()).apply { text=h; textSize=10f; setTextColor(Color.parseColor("#999999")); setTypeface(null,Typeface.BOLD); gravity=Gravity.CENTER; setPadding(2,4,2,4) }); return hr }

    private fun tableDataRow(name: String, code: String, strength: Int, price: Double, changePct: Double): TableRow {
        val row = TableRow(requireContext()); val sc = when { strength>=80 -> Color.parseColor("#E65100"); strength>=60 -> Color.parseColor("#2E7D32"); else -> Color.parseColor("#666666") }
        row.addView(TextView(requireContext()).apply { text=name.take(6); textSize=10f; setTextColor(Color.parseColor("#222222")); gravity=Gravity.CENTER; setPadding(2,4,2,4) })
        row.addView(TextView(requireContext()).apply { text=getSector(code).take(6).ifEmpty{"-"}; textSize=9f; setTextColor(Color.parseColor("#1565C0")); gravity=Gravity.CENTER; setPadding(2,4,2,4) })
        row.addView(TextView(requireContext()).apply { text=code.takeLast(6); textSize=10f; setTextColor(sc); gravity=Gravity.CENTER; setPadding(2,4,2,4) })
        row.addView(TextView(requireContext()).apply { text="${strength}%"; textSize=10f; setTextColor(sc); gravity=Gravity.CENTER; setPadding(2,4,2,4) })
        row.addView(TextView(requireContext()).apply { text="%.2f".format(price); textSize=10f; setTextColor(Color.parseColor("#E53935")); gravity=Gravity.CENTER; setPadding(2,4,2,4) })
        row.addView(TextView(requireContext()).apply { text="${if(changePct>=0)"+" else ""}${"%.2f".format(changePct)}%"; textSize=10f; setTextColor(if(changePct>=0) Color.parseColor("#E53935") else Color.parseColor("#43A047")); gravity=Gravity.CENTER; setPadding(2,4,2,4) })
        return row
    }

    private fun showPipelineTable(allScreenings: Map<Strategy, ScreeningResult>, factors: ZiplinePipeline.FactorSet, today: String) {
        resultsContainer.removeAllViews()
        val sv = ScrollView(requireContext())
        val container = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(8, 8, 8, 8) }
        container.addView(TextView(requireContext()).apply { text="📊 Pipeline 因子计算结果 ($today)"; textSize=14f; setTextColor(Color.parseColor("#1A1A2E")); setTypeface(null,Typeface.BOLD); setPadding(0,8,0,8) })
        container.addView(TextView(requireContext()).apply { text="有效股票: ${factors.ma5.size}只 | 合并池: ${mergedPool.size}只 | AI精选: ${aiPicks.size}只"; textSize=11f; setTextColor(Color.parseColor("#666666")); setPadding(0,0,0,8) })

        // ① AI 精选 Top3-5
        if (aiPicks.isNotEmpty()) {
            container.addView(TextView(requireContext()).apply { text="🤖 AI 精选 Top3-5"; textSize=15f; setTextColor(Color.parseColor("#E65100")); setTypeface(null,Typeface.BOLD); setPadding(0,12,0,6) })
            val t1 = TableLayout(requireContext()).apply { isStretchAllColumns=true }; t1.addView(tableHeaderRow())
            for (p in aiPicks) { val snap = todayStocks.find { it.code==p.stockCode }; t1.addView(tableDataRow(p.stockName, p.stockCode, p.compositeScore, snap?.price?:0.0, snap?.changePercent?:0.0)) }
            container.addView(t1)
        }

        // ② 合并池
        container.addView(View(requireContext()).apply { layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,1).apply{topMargin=12}; setBackgroundColor(Color.parseColor("#DDDDDD")) })
        container.addView(TextView(requireContext()).apply { text="📋 策略合并池 (${mergedPool.size}只)"; textSize=13f; setTextColor(Color.parseColor("#333333")); setTypeface(null,Typeface.BOLD); setPadding(0,8,0,6) })
        val t2 = TableLayout(requireContext()).apply { isStretchAllColumns=true }
        val hr2 = TableRow(requireContext()); for (h in listOf("名称","板块","代码","命中","策略","热度")) hr2.addView(TextView(requireContext()).apply { text=h; textSize=10f; setTextColor(Color.parseColor("#999999")); setTypeface(null,Typeface.BOLD); gravity=Gravity.CENTER; setPadding(2,4,2,4) })
        t2.addView(hr2)
        for ((code, hits) in mergedPool.entries.sortedByDescending { it.value.size*50+(it.value.firstOrNull()?.second?:0) }.take(20)) {
            val name = mergedStockNames[code] ?: code.takeLast(6); val maxStr = hits.firstOrNull()?.second ?: 0
            val row = TableRow(requireContext())
            row.addView(TextView(requireContext()).apply { text=name.take(6); textSize=10f; setTextColor(Color.parseColor("#222222")); gravity=Gravity.CENTER; setPadding(2,4,2,4) })
            row.addView(TextView(requireContext()).apply { text=getSector(code).take(6).ifEmpty{"-"}; textSize=9f; setTextColor(Color.parseColor("#1565C0")); gravity=Gravity.CENTER; setPadding(2,4,2,4) })
            row.addView(TextView(requireContext()).apply { text=code.takeLast(6); textSize=10f; setTextColor(Color.parseColor("#333333")); gravity=Gravity.CENTER; setPadding(2,4,2,4) })
            row.addView(TextView(requireContext()).apply { text="${hits.size}次"; textSize=9f; setTextColor(Color.parseColor("#E65100")); gravity=Gravity.CENTER; setPadding(2,4,2,4) })
            row.addView(TextView(requireContext()).apply { text=hits.take(3).joinToString { "${it.first.take(3)}${it.second}" }; textSize=8f; setTextColor(Color.parseColor("#666666")); gravity=Gravity.CENTER; setPadding(2,4,2,4) })
            row.addView(TextView(requireContext()).apply { text="${maxStr}%"; textSize=10f; setTextColor(Color.parseColor(if(maxStr>=70)"#E65100" else "#333333")); gravity=Gravity.CENTER; setPadding(2,4,2,4) })
            t2.addView(row)
        }; container.addView(t2)

        // ③ 各策略明细
        container.addView(View(requireContext()).apply { layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,1).apply{topMargin=12}; setBackgroundColor(Color.parseColor("#DDDDDD")) })
        for ((strategy, screening) in allScreenings) {
            container.addView(TextView(requireContext()).apply { text="${strategy.category.icon} ${strategy.name}  (${screening.hitCount}只 / ${screening.scanTimeMs}ms)"; textSize=13f; setTextColor(Color.parseColor("#333333")); setTypeface(null,Typeface.BOLD); setPadding(0,12,0,6) })
            if (screening.signals.isEmpty()) { container.addView(TextView(requireContext()).apply { text="  ⚠️ 无命中信号"; textSize=11f; setTextColor(Color.parseColor("#999999")); setPadding(0,0,0,8) }); continue }
            val table = TableLayout(requireContext()).apply { isStretchAllColumns=true }; table.addView(tableHeaderRow())
            for (sig in screening.signals.distinctBy{it.stockCode}.take(15)) table.addView(tableDataRow(sig.stockName.take(6), sig.stockCode, sig.strength, sig.currentPrice, sig.changePercent))
            container.addView(table)
        }
        sv.addView(container); resultsContainer.addView(sv)
    }

    // ═══════════════════════════════════════
    // 数据查看
    // ═══════════════════════════════════════

    private fun showDataMenu() {
        if (allScreenings.isEmpty() && mergedPool.isEmpty()) { Toast.makeText(requireContext(), "请先运行 Pipeline", Toast.LENGTH_SHORT).show(); return }
        val options = arrayOf("📊 策略明细 (${allScreenings.size}个)", "📋 合并池 (${mergedPool.size}只)", "🤖 AI精选 (${aiPicks.size}只)")
        androidx.appcompat.app.AlertDialog.Builder(requireContext()).setTitle("Pipeline 数据查看").setItems(options) { _, which ->
            when (which) { 0->showPerStrategyDetail(); 1->showMergedPoolDetail(); 2->showAiPicksDetail() }
        }.setNegativeButton("关闭", null).show()
    }

    private fun showPerStrategyDetail() {
        val sb = StringBuilder(); sb.appendLine("📊 策略明细 (合并前)")
        for ((strategy, screening) in allScreenings) {
            sb.appendLine(); sb.appendLine("${strategy.category.icon} ${strategy.name} (${screening.hitCount}只 / ${screening.scanTimeMs}ms)")
            if (screening.signals.isEmpty()) { sb.appendLine("  ⚠️ 无命中信号"); continue }
            for (sig in screening.signals.distinctBy{it.stockCode}.take(10)) { val s = getSector(sig.stockCode).take(6).ifEmpty{"-"}; sb.appendLine("  ${sig.stockName.take(6)}(${sig.stockCode.takeLast(6)}) [$s] ${sig.strength}% ¥${"%.2f".format(sig.currentPrice)} ${if(sig.changePercent>=0)"+" else ""}${"%.2f".format(sig.changePercent)}%") }
        }; showDialog("策略明细", sb.toString())
    }

    private fun showMergedPoolDetail() {
        val sb = StringBuilder(); sb.appendLine("📋 合并池 (${mergedPool.size}只)")
        for ((code, hits) in mergedPool.entries.sortedByDescending { it.value.size*50+(it.value.firstOrNull()?.second?:0) }.take(30)) {
            val name = mergedStockNames[code] ?: code.takeLast(6); val s = getSector(code).take(6).ifEmpty{"-"}; val maxStr = hits.firstOrNull()?.second ?: 0
            sb.appendLine("${name.take(6)}(${code.takeLast(6)}) [$s] ${hits.size}次命中 最高${maxStr}%")
            sb.appendLine("  策略: ${hits.joinToString { "${it.first.take(4)}(${it.second})" }}")
        }; showDialog("合并池", sb.toString())
    }

    private fun showAiPicksDetail() {
        val sb = StringBuilder(); sb.appendLine("🤖 AI 精选 Top3-5"); if (aiPicks.isEmpty()) sb.appendLine("(暂无结果)"); else for (p in aiPicks) { val snap = todayStocks.find{it.code==p.stockCode}; val s = getSector(p.stockCode).take(6).ifEmpty{"-"}; val price = snap?.price ?:0.0; val chg = snap?.changePercent ?:0.0; sb.appendLine("#${p.rank} ${p.stockName}(${p.stockCode.takeLast(6)}) [$s] 评分:${p.compositeScore} 概率:${p.upProbability}%"); sb.appendLine("  ¥${"%.2f".format(price)} ${if(chg>=0)"+" else ""}${"%.2f".format(chg)}% ${p.actionSuggestion}"); sb.appendLine("  ${p.reason}"); sb.appendLine() }; showDialog("AI精选", sb.toString())
    }

    // ═══════════════════════════════════════
    // 操作
    // ═══════════════════════════════════════

    private fun importData() {
        runPipelineBtn.isEnabled=false; runPipelineBtn.text="⏳"; progressBar.visibility=View.VISIBLE; statusTv.text="正在从东方财富拉取历史K线..."
        lifecycleScope.launch(Dispatchers.IO) { try { val f=com.chin.stockanalysis.strategy.data.HistoricalDataFetcher(requireContext()); val total=f.fetchAllHistoricalData(60){p-> lifecycleScope.launch(Dispatchers.Main){statusTv.text="进度: ${p.completedStocks}/${p.totalStocks} 只 · ${p.totalRecords} 条"} }; withContext(Dispatchers.Main){runPipelineBtn.isEnabled=true; runPipelineBtn.text="▶ Pipeline"; progressBar.visibility=View.GONE; statusTv.text="✅ 导入完成 · $total 条历史记录"} } catch (e:Exception){withContext(Dispatchers.Main){runPipelineBtn.isEnabled=true; runPipelineBtn.text="▶ Pipeline"; progressBar.visibility=View.GONE; statusTv.text="导入失败: ${e.message}"} } }
    }

    private fun showSellMenu(anchor: View) { val popup=PopupMenu(requireContext(), anchor, Gravity.END); popup.menu.add(0,1,0,"💰 卖出评估"); popup.menu.add(0,2,0,"⚡ 一键全卖"); popup.setOnMenuItemClickListener { when(it.itemId){1->Toast.makeText(requireContext(),"卖出评估功能开发中",Toast.LENGTH_SHORT).show(); 2->sellAllAutoQuant()}; true }; popup.show() }

    private fun sellAllAutoQuant() { lifecycleScope.launch(Dispatchers.IO) { try { val db=StockDatabase.getInstance(requireContext()); val orders=db.strategyTradeOrderDao().getRecent(200).filter{it.orderType=="AutoQuant"&&it.status=="BUYING"}; for(o in orders) db.strategyTradeOrderDao().updateSellInfo(o.id,"SOLD",o.buyPrice,LocalDate.now().format(DATE_FMT)+" 15:00",0.0); withContext(Dispatchers.Main){refreshPositions(); statusTv.text="✅ 已卖出 ${orders.size} 只"; Toast.makeText(requireContext(),"已卖出 ${orders.size} 只",Toast.LENGTH_SHORT).show()} } catch(_:Exception){} } }

    /** 自动拟合 — 遍历最近交易日，验证预测准确率并更新策略权重 */
    private fun autoFit() {
        val eng = engine ?: return
        runPipelineBtn.isEnabled = false; runPipelineBtn.text = "⏳"; progressBar.visibility = View.VISIBLE; statusTv.text = "🔧 自动拟合中..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val te = SimulationTradeEngine(requireContext())
                val strategies = eng.getStrategies().filter { eng.isEnabled(it.id) }
                val recentDates = StockDatabase.getInstance(requireContext()).dailySnapshotDao().getAvailableDates(30).sorted()
                if (recentDates.size < 2) {
                    withContext(Dispatchers.Main) { statusTv.text = "⚠️ 数据不足"; runPipelineBtn.isEnabled = true; runPipelineBtn.text = "▶ Pipeline"; progressBar.visibility = View.GONE }
                    return@launch
                }
                val results = te.autoFit(strategies, recentDates)
                withContext(Dispatchers.Main) {
                    statusTv.text = "✅ 拟合完成: ${results.size} 策略优化"; runPipelineBtn.isEnabled = true; runPipelineBtn.text = "▶ Pipeline"; progressBar.visibility = View.GONE
                    Toast.makeText(requireContext(), "拟合完成", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { statusTv.text = "❌ 拟合失败: ${e.message?.take(30)}"; runPipelineBtn.isEnabled = true; runPipelineBtn.text = "▶ Pipeline"; progressBar.visibility = View.GONE }
            }
        }
    }

    /** 供外部调用的自动触发 Pipeline */
    fun autoRunPipeline() {
        if (runPipelineBtn.isEnabled) runPipeline()
    }

    private fun clearAutoQuantData() { lifecycleScope.launch(Dispatchers.IO) { try { val db=StockDatabase.getInstance(requireContext()); db.strategyTradeOrderDao().deleteByDate(LocalDate.now().format(DATE_FMT)); withContext(Dispatchers.Main){resultsContainer.removeAllViews(); hasPipelineResult=false; updateViewSplit(); refreshPositions(); statusTv.text="✅ 已清除今日AutoQuant数据"; Toast.makeText(requireContext(),"已清除",Toast.LENGTH_SHORT).show()} } catch(_:Exception){} } }

    private fun showDialog(title: String, content: String) {
        val sv = ScrollView(requireContext()); sv.addView(TextView(requireContext()).apply { text=content; textSize=10f; setTextColor(Color.parseColor("#333333")); setPadding(16,12,16,12); setLineSpacing(2f,1.1f); setTypeface(Typeface.MONOSPACE) })
        androidx.appcompat.app.AlertDialog.Builder(requireContext()).setTitle(title).setView(sv).setPositiveButton("关闭",null).create().apply { show(); window?.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT) }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()
}