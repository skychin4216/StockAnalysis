package com.chin.stockanalysis.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.*
import android.widget.*
import android.widget.LinearLayout.LayoutParams
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chin.stockanalysis.stock.data.StockDataSourceFactory
import com.chin.stockanalysis.strategy.*
import com.chin.stockanalysis.strategy.data.StockScreener
import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.models.WeightFactor
import com.chin.stockanalysis.strategy.strategies.*
import com.chin.stockanalysis.strategy.ui.StrategyAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StrategyFragment : Fragment() {

    private lateinit var layout: LinearLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: StrategyAdapter
    private lateinit var statusTv: TextView
    private lateinit var scanBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var scopeSelector: TextView

    private var engine: StrategyEngine? = null
    private var screener: StockScreener? = null
    private var currentScope: ScanScope = ScanScope.FULL_MARKET
    private var currentScopeParam: String = ""

    private val SECTORS = listOf(
        "全市场" to "", "化工" to "BK0423", "有色金属" to "BK0478", "半导体" to "BK0467",
        "医药" to "BK0465", "新能源" to "BK0493", "军工" to "BK0469",
        "消费" to "BK0473", "金融" to "BK0471", "AI算力" to "BK0800", "商业航天" to "BK0812"
    )
    private val WATCHLIST_SAMPLE = listOf(
        "sh600519" to "贵州茅台", "sz002594" to "比亚迪", "sz300750" to "宁德时代",
        "sh600183" to "生益科技", "sz002463" to "沪电股份",
        "sh601318" to "中国平安", "sz000858" to "五粮液", "sh688981" to "中芯国际"
    )

    enum class ScanScope(val label: String) { FULL_MARKET("全市场"), SECTOR("板块"), WATCHLIST("自选股") }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F6FA"))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        initEngine()
        buildUI()
        return layout
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
        }
    }

    private fun buildUI() {
        val header = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 48, 24, 16); setBackgroundColor(Color.WHITE); elevation = 2f
        }
        header.addView(TextView(requireContext()).apply {
            text = "🎯 量化选股"; textSize = 24f; setTextColor(Color.parseColor("#1A1A2E")); setTypeface(null, Typeface.BOLD)
        })
        header.addView(TextView(requireContext()).apply {
            text = "5 种策略 · 全市场/板块/自选股"; textSize = 13f; setTextColor(Color.parseColor("#999999")); setPadding(0, 4, 0, 0)
        })
        layout.addView(header)

        val scopeRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 10, 24, 10); setBackgroundColor(Color.WHITE)
        }
        scopeRow.addView(TextView(requireContext()).apply {
            text = "扫描范围: "; textSize = 13f; setTextColor(Color.parseColor("#888888"))
        })
        scopeSelector = TextView(requireContext()).apply {
            text = "全市场 ▼"; textSize = 14f; setTextColor(Color.parseColor("#1565C0"))
            setTypeface(null, Typeface.BOLD); setPadding(12, 6, 12, 6)
            setBackgroundColor(Color.parseColor("#E3F2FD"))
            setOnClickListener { showScopeDialog() }
        }
        scopeRow.addView(scopeSelector)
        layout.addView(scopeRow)

        val scanBar = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 8, 24, 8); setBackgroundColor(Color.WHITE)
        }
        scanBtn = Button(requireContext()).apply {
            text = "▶  执行全部策略"; textSize = 15f; setBackgroundColor(Color.parseColor("#E65100")); setTextColor(Color.WHITE)
            setPadding(24, 12, 24, 12); setOnClickListener { runAllStrategies() }
        }
        scanBar.addView(scanBtn)
        progressBar = ProgressBar(requireContext()).apply { visibility = View.GONE }
        scanBar.addView(progressBar)
        statusTv = TextView(requireContext()).apply {
            text = "  5 个策略已就绪"; textSize = 12f; setTextColor(Color.parseColor("#AAAAAA"))
        }
        scanBar.addView(statusTv)
        layout.addView(scanBar)

        recyclerView = RecyclerView(requireContext()).apply {
            layoutManager = LinearLayoutManager(context)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setPadding(0, 8, 0, 80); clipToPadding = false
        }
        layout.addView(recyclerView)
        refreshList()

        val fab = Button(requireContext()).apply {
            text = "+ 添加自定义策略"; textSize = 14f; setBackgroundColor(Color.parseColor("#1565C0")); setTextColor(Color.WHITE)
            setOnClickListener { showAddDialog() }
        }
        layout.addView(fab, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { setMargins(24, 16, 24, 32) })
    }

    private fun refreshList() {
        engine?.let {
            adapter = StrategyAdapter(it.getStrategies(), ::onStrategyClick, ::onStrategyToggle)
            recyclerView.adapter = adapter
            statusTv.text = "  ${it.getStrategies().size} 个策略已就绪"
        }
    }

    private fun showScopeDialog() {
        val items = SECTORS.map { it.first }
        AlertDialog.Builder(requireContext())
            .setTitle("选择扫描范围")
            .setSingleChoiceItems(items.toTypedArray(), 0) { _, _ -> }
            .setPositiveButton("确定") { dialog, _ ->
                val listView = (dialog as AlertDialog).listView
                val checkedPos = listView.checkedItemPosition
                if (checkedPos >= 0 && checkedPos < SECTORS.size) {
                    val (name, _) = SECTORS[checkedPos]
                    currentScopeParam = name
                    currentScope = if (name == "全市场") ScanScope.FULL_MARKET else ScanScope.SECTOR
                }
                scopeSelector.text = "${currentScopeParam.ifEmpty { "全市场" }} ▼"
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun getScopePool(): List<String> {
        return when (currentScope) {
            ScanScope.FULL_MARKET -> emptyList()
            ScanScope.SECTOR -> emptyList()
            ScanScope.WATCHLIST -> WATCHLIST_SAMPLE.map { it.first }
        }
    }

    private fun applyScope(): List<String> {
        return when (currentScope) {
            ScanScope.FULL_MARKET -> emptyList()
            ScanScope.WATCHLIST -> WATCHLIST_SAMPLE.map { it.first }
            ScanScope.SECTOR -> emptyList()
        }
    }

    private fun onStrategyClick(strategy: Strategy) {
        val detail = StrategyDetailFragment()
        detail.strategy = strategy
        detail.onSave = { updated ->
            engine?.apply { removeStrategy(updated.id); registerStrategy(updated); refreshList() }
        }
        detail.show(parentFragmentManager, "strategy_detail")
    }

    private fun onStrategyToggle(strategy: Strategy) {
        engine?.setEnabled(strategy.id, !engine!!.isEnabled(strategy.id))
    }

    private fun runAllStrategies() {
        val eng = engine ?: return
        scanBtn.isEnabled = false; scanBtn.text = "⏳ 扫描中..."
        progressBar.visibility = View.VISIBLE; statusTv.text = "  正在获取市场数据..."

        eng.runAll(lifecycleScope, onComplete = { results ->
            lifecycleScope.launch(Dispatchers.Main) {
                scanBtn.isEnabled = true; scanBtn.text = "▶  执行全部策略"
                progressBar.visibility = View.GONE
                val totalHits = results.sumOf { it.hitCount }
                if (totalHits > 0) showResultsDialog(results)
                else showResultsDialogSimple(results)
            }
        })
    }

    // ======================== v6.0: 结果对话框 ========================

    private fun openResultDialog(result: ScreeningResult) {
        val dialog = StrategyResultDialogFragment()
        dialog.result = result
        dialog.onAskQuestion = { question ->
            val contextMsg = buildStrategyContextMessage(result, question)
            // 切换到对话 Tab 并发送消息
            if (activity is MainActivity) {
                (activity as MainActivity).switchToChatAndSend(contextMsg)
            } else {
                Toast.makeText(requireContext(), "提问已记录: $question", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show(parentFragmentManager, "strategy_result")
    }

    private fun buildStrategyContextMessage(result: ScreeningResult, question: String): String {
        val sb = StringBuilder()
        sb.appendLine("基于以下策略扫描结果，请回答用户问题：")
        sb.appendLine("策略: ${result.strategyName} | 扫描: ${result.totalScanned}只 | 命中: ${result.hitCount}只")
        sb.appendLine()
        sb.appendLine("| 排名 | 名称 | 代码 | 强度% | 价格 | 涨跌% |")
        sb.appendLine("|------|------|------|-------|------|-------|")
        for ((i, s) in result.signals.take(10).withIndex()) {
            sb.appendLine("| ${i + 1} | ${s.stockName} | ${s.stockCode.takeLast(6)} | ${s.strength}% | ${"%.2f".format(s.currentPrice)} | ${"%.2f".format(s.changePercent)}% |")
        }
        sb.appendLine()
        sb.appendLine("用户问题: $question")
        return sb.toString()
    }

    // ======================== 结果展示 ========================

    private fun showResultsDialogSimple(results: List<ScreeningResult>) {
        val sb = StringBuilder()
        for (r in results) {
            sb.append("${r.category.icon} ${r.strategyName}: 扫描${r.totalScanned}只, 命中${r.hitCount}只\n")
            if (r.signals.isEmpty()) sb.append("  未命中\n")
            else {
                for (sig in r.signals.take(5)) {
                    sb.append("  ${sig.emoji} ${sig.stockName}(${sig.stockCode}) 强度:${sig.strength}%\n")
                }
            }
        }
        AlertDialog.Builder(requireContext())
            .setTitle("📊 扫描结果")
            .setMessage(sb.toString().trim())
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun showResultsDialog(results: List<ScreeningResult>) {
        val scrollView = ScrollView(requireContext())
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24)
        }
        for (result in results) {
            val titleRow = TextView(requireContext()).apply {
                text = "${result.category.icon} ${result.strategyName}  (${result.hitCount}只 / ${result.scanTimeMs}ms)"
                textSize = 15f; setTextColor(Color.parseColor("#333333"))
                setTypeface(null, Typeface.BOLD); setPadding(0, 16, 0, 8)
            }
            titleRow.setOnClickListener { openResultDialog(result) }
            container.addView(titleRow)

            val table = TableLayout(requireContext()).apply { isStretchAllColumns = true }
            val headerRow = TableRow(requireContext())
            for (hdr in listOf("名称", "代码", "强度", "价格", "涨幅")) {
                headerRow.addView(TextView(requireContext()).apply {
                    text = hdr; textSize = 11f; setTextColor(Color.parseColor("#999999"))
                    setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(4, 4, 4, 4)
                })
            }
            table.addView(headerRow)
            for (sig in result.signals.take(10)) {
                val row = TableRow(requireContext())
                val color = when { sig.strength >= 80 -> Color.parseColor("#E65100"); sig.strength >= 60 -> Color.parseColor("#2E7D32"); else -> Color.parseColor("#666666") }
                row.setOnClickListener { openResultDialog(result) }
                for (cell in listOf(sig.stockName, sig.stockCode, "${sig.strength}%", String.format("%.2f", sig.currentPrice), "${String.format("%+.2f", sig.changePercent)}%")) {
                    row.addView(TextView(requireContext()).apply {
                        text = cell; textSize = 11f; setTextColor(color); gravity = Gravity.CENTER; setPadding(2, 4, 2, 4)
                    })
                }
                table.addView(row)
            }
            container.addView(table)
        }
        scrollView.addView(container)
        AlertDialog.Builder(requireContext()).setTitle("📊 扫描结果（点击策略名查看详情）").setView(scrollView).setPositiveButton("关闭", null).show()
    }

    private fun showAddDialog() {
        val nameInput = EditText(requireContext()).apply { hint = "策略名称"; setSingleLine() }
        val descInput = EditText(requireContext()).apply { hint = "策略描述"; setSingleLine() }
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 8)
            addView(nameInput, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = 12 })
            addView(descInput, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(requireContext())
            .setTitle("添加自定义策略")
            .setView(container)
            .setPositiveButton("创建") { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isNotBlank()) {
                    val id = "custom_${System.currentTimeMillis()}"
                    engine?.registerStrategy(object : Strategy {
                        override val id = id; override var name = name
                        override var description = descInput.text.toString().trim().ifEmpty { "自定义策略" }
                        override val category = StrategyCategory.CUSTOM
                        override val config = StrategyConfig.fullMarket(20)
                        override var weightFactors = listOf(WeightFactor("default", "综合评分", 100, "默认权重"))
                        override val source = StrategySource.USER_CUSTOM
                        override suspend fun screen() = Result.success(ScreeningResult(
                            strategyId = id, strategyName = name, category = StrategyCategory.CUSTOM,
                            signals = emptyList(), totalScanned = 0, scanTimeMs = 0
                        ))
                        override suspend fun isAvailable() = false
                    })
                    refreshList()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        engine?.cancelScan()
    }
}