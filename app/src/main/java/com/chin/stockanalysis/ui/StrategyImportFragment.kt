package com.chin.stockanalysis.ui

import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.*
import android.widget.LinearLayout.LayoutParams
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.stock.database.DataExportImport
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.HoldingPeriod
import com.chin.stockanalysis.strategy.Strategy
import com.chin.stockanalysis.strategy.StrategyEngine
import com.chin.stockanalysis.strategy.StrategyEngineHolder
import com.chin.stockanalysis.strategy.backtest.FullCycleBacktestEngine
import com.chin.stockanalysis.strategy.backtest.StrategySelfTuner
import com.chin.stockanalysis.strategy.data.HistoricalDataFetcher
import com.chin.stockanalysis.strategy.sector.StrategyMarketContext
import com.chin.stockanalysis.strategy.sector.UserMarketMemory
import com.chin.stockanalysis.strategy.trade.StrategyTradeBacktestEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * ## 量化 → 数据 Tab — 数据管理 + 导入 + 拟合调优 + 回溯分析（承接工作台全部公共操作）
 *
 * - 🔄 刷新市场上下文（清空 StrategyMarketContext 缓存）
 * - 📊 数据库统计信息 / 🧠 市场记忆设置 / 📥 拉取股票报告
 * - 📤 导出热门板块 / 📤 导出K线快照
 * - 🎯 拟合调优 & 批量数据（自工作台迁移）：四周期自测拟合 / 状态矩阵拟合 / 导出拟合矩阵 / 增量拉取 / JSON 导入
 * - 📌 PC 拟合参数（自工作台迁移）：导出 / 重置内置 / 参数详情 / PC 候选（选股/排序/卖出联动）
 * - 📌 回溯 & 分析（自工作台迁移）：按周期回溯 / 多周期回溯 / AI 跨周期分析 / 选中记录
 * - ⬇️ 导入历史行情：显示导入进度，完成后展示当前热门板块
 */
class StrategyImportFragment : Fragment() {

    private val TAG = "StrategyImportFragment"

    private lateinit var layout: LinearLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var pageData: LinearLayout
    private lateinit var pageFit: LinearLayout
    private lateinit var pageParams: LinearLayout
    private lateinit var pageBacktest: LinearLayout
    private val pages: List<LinearLayout> get() = listOf(pageData, pageFit, pageParams, pageBacktest)
    private lateinit var importBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusTv: TextView
    private lateinit var dateLabelTv: TextView
    private lateinit var datePicker: TradingDayPickerView
    private var selectedHotPeriod = 0
    private var browsingDate: LocalDate = TradingDayPickerView.recentTradingDay()

    private data class PeriodInfo(
        val key: String, // 落库周期键：UltraShortQuant / ShortTermQuant / MidTermQuant / LongTermQuant
        val holdingPeriod: HoldingPeriod?, // null = 使用所有启用策略
        val label: String
    )

    private val FIT_PERIODS = listOf(
        PeriodInfo("UltraShortQuant", HoldingPeriod.ULTRA_SHORT, "超短线"),
        PeriodInfo("ShortTermQuant", null, "短线"),
        PeriodInfo("MidTermQuant", null, "中线"),
        PeriodInfo("LongTermQuant", HoldingPeriod.LONG, "长线")
    )

    /** JSON 数据导入选择器（从工作台迁移：DataExportImport 全量导入） */
    private val importPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { doImport(it) }
    }

    /** PC 拟合参数文件选择器（backtest_params.json 回传） */
    private val paramsPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { doImportParams(it) }
    }

    /** PC 拟合参数导出（写回 backtest_params.json） */
    private var pendingExportParams: String? = null
    private val exportParamsLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val text = pendingExportParams ?: return@registerForActivityResult
            if (uri == null) return@registerForActivityResult
            try {
                requireContext().contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                Toast.makeText(requireContext(), "参数已导出（${text.length} 字符）", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "导出参数失败: ${e.message}", e)
                Toast.makeText(requireContext(), "导出失败: ${e.message?.take(40)}", Toast.LENGTH_LONG).show()
            } finally {
                pendingExportParams = null
            }
        }

    private lateinit var resultTv: TextView
    private lateinit var paramsStatusTv: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        // 外层：顶部 tab 栏（固定）+ 内容滚动区
        val outer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#F5F6FA"))
        }
        tabBar = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
        }
        outer.addView(tabBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#F5F6FA"))
        }
        val scroll = ScrollView(requireContext()).apply {
            isFillViewport = true
            setBackgroundColor(Color.parseColor("#F5F6FA"))
        }
        scroll.addView(layout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        outer.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        buildUI(); return outer
    }

    override fun onResume() {
        super.onResume()
        refreshResults()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUI() {
        // 4 个 tab 页面容器：数据 / 拟合 / PC参数 / 回溯
        pageData = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        pageFit = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        pageParams = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        pageBacktest = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        listOf(pageData, pageFit, pageParams, pageBacktest).forEach { p ->
            layout.addView(p, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        buildTabBar()

        // ── 标题 ──
        pageData.addView(TextView(requireContext()).apply {
            text = "📥 数据管理 & 导入"
            textSize = 16f; setTextColor(Color.parseColor("#1A1A2E")); setTypeface(null, Typeface.BOLD)
            setPadding(dp(16), dp(16), dp(16), dp(4)); setBackgroundColor(Color.WHITE)
        })

        // ── 数据管理按钮区（2 行 × 2 列） ──
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)); setBackgroundColor(Color.WHITE)
        }
        fun buildActionRow(btn: Button): LinearLayout {
            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(btn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
            return row
        }
        fun actionButton(text: String, color: String, onClick: () -> Unit): Button =
            Button(requireContext()).apply {
                this.text = text; textSize = 12f; setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor(color)); setPadding(dp(6), dp(10), dp(6), dp(10))
                setMinWidth(0); setMinimumWidth(0)
                setOnClickListener { onClick() }
            }

        // 行1：刷新市场上下文 | 数据库统计信息
        card.addView(buildActionRow(actionButton("🔄 刷新市场上下文", "#455A64") { refreshMarketContext() }))
        val statsBtn = actionButton("📊 数据库统计信息", "#00897B") { showDbStats() }
        card.addView(buildActionRow(statsBtn))

        // 行2：市场记忆设置 | 拉取股票报告
        val memoryBtn = actionButton("🧠 市场记忆设置", "#6A1B9A") { showMarketMemoryDialog() }
        card.addView(buildActionRow(memoryBtn))
        val fetchBtn = actionButton("📥 拉取股票报告", "#1565C0") { showFetchReport() }
        card.addView(buildActionRow(fetchBtn))

        // 行3：导出热门板块 | 导出K线快照
        val hotBtn = actionButton("📤 导出热门板块", "#E65100") { showHotSectorsReport() }
        card.addView(buildActionRow(hotBtn))
        val snapBtn = actionButton("📤 导出K线快照", "#BF360C") { exportSnapshotData() }
        card.addView(buildActionRow(snapBtn))
        pageData.addView(card)

        // ── 🎯 拟合调优 & 批量数据（自工作台迁移） ──
        val fitCard = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(12)); setBackgroundColor(Color.WHITE)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        fitCard.addView(TextView(requireContext()).apply {
            text = "🎯 拟合调优 & 批量数据"
            textSize = 13f; setTextColor(Color.parseColor("#1A1A2E")); setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(6))
        })
        // 行1：自测拟合 | 状态矩阵拟合
        fitCard.addView(buildActionRow(actionButton("🎯 自测拟合(目标90%)", "#6A1B9A") { runFitting() }))
        fitCard.addView(buildActionRow(actionButton("📐 状态矩阵拟合", "#00838F") { runStateFit() }))
        // 行2：导出拟合矩阵 | 增量拉取历史
        fitCard.addView(buildActionRow(actionButton("📤 导出拟合矩阵", "#E65100") { exportFitMatrix() }))
        fitCard.addView(buildActionRow(actionButton("📥 增量拉取历史", "#1565C0") { fetchBacktestHistory() }))
        // 行3：JSON 数据导入（全量）
        fitCard.addView(buildActionRow(actionButton("📦 JSON 数据导入", "#2E7D32") { importPicker.launch("application/json") }))
        pageFit.addView(fitCard)

        // ── 📌 PC 拟合参数（选股/排序/卖出联动，自工作台迁移） ──
        val paramsCard = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(12)); setBackgroundColor(Color.WHITE)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        paramsCard.addView(TextView(requireContext()).apply {
            text = "📌 PC 拟合参数（选股/排序/卖出联动）"
            textSize = 13f; setTextColor(Color.parseColor("#1A1A2E")); setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(6))
        })
        paramsCard.addView(buildActionRow(actionButton("📤 导出参数", "#E65100") { exportParamsFile() }))
        paramsCard.addView(buildActionRow(actionButton("🔄 重置内置", "#546E7A") { resetParamsFile() }))
        paramsCard.addView(buildActionRow(actionButton("🔍 参数详情", "#1565C0") { showParamsDetail() }))
        paramsCard.addView(buildActionRow(actionButton("📋 PC 候选", "#00838F") {
            com.chin.stockanalysis.strategy.trade.PcCandidatesDialog(requireContext()).show()
        }))
        paramsStatusTv = TextView(requireContext()).apply {
            text = "当前参数: ${com.chin.stockanalysis.strategy.backtest.BacktestParamsLoader.paramName(requireContext())}（${com.chin.stockanalysis.strategy.backtest.BacktestParamsLoader.sourceLabel(requireContext())} v${com.chin.stockanalysis.strategy.backtest.BacktestParamsLoader.version(requireContext())}）"
            textSize = 10f
            setTextColor(Color.parseColor("#1976D2"))
            setPadding(dp(4), 0, dp(4), dp(4))
            setOnClickListener { showParamsDetail() }
        }
        paramsCard.addView(paramsStatusTv)
        pageParams.addView(paramsCard)

        // ── 📌 回溯 & 分析（自工作台迁移） ──
        val backCard = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(12)); setBackgroundColor(Color.WHITE)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        backCard.addView(TextView(requireContext()).apply {
            text = "📌 回溯 & 分析"
            textSize = 13f; setTextColor(Color.parseColor("#1A1A2E")); setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(6))
        })
        backCard.addView(buildActionRow(actionButton("📈 回溯", "#E65100") { runBacktest() }))
        backCard.addView(buildActionRow(actionButton("🔄 多周期回溯", "#00838F") { runFullCycleBacktest() }))
        backCard.addView(buildActionRow(actionButton("🤖 AI 分析", "#6A1B9A") { runCrossPeriodAnalysis() }))
        backCard.addView(buildActionRow(actionButton("📋 选中记录", "#1565C0") { showSelectedRecords() }))
        backCard.addView(TextView(requireContext()).apply {
            text = "── 最近回溯结果对比 ──"
            textSize = 12f
            setTextColor(Color.parseColor("#E65100"))
            setPadding(0, dp(8), 0, dp(4))
        })
        resultTv = TextView(requireContext()).apply {
            text = "暂无回溯数据\n\n点击「📈 回溯」按周期执行历史回溯测试，结果将自动落库。"
            textSize = 12f
            setTextColor(Color.parseColor("#444444"))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setLineSpacing(3f, 1.15f)
            setTypeface(Typeface.MONOSPACE)
            setBackgroundColor(Color.WHITE)
        }
        backCard.addView(resultTv)
        pageBacktest.addView(backCard)

        // ── 导入区域 ──
        val importCard = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(12)); setBackgroundColor(Color.WHITE)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        importCard.addView(TextView(requireContext()).apply {
            text = "⬇️ 导入历史行情（东方财富）"
            textSize = 13f; setTextColor(Color.parseColor("#1A1A2E")); setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(6))
        })
        // 周期选择
        val periodRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val periodSpinner = Spinner(requireContext()).apply {
            val presets = listOf("当日", "近3日", "近10日", "近30日", "近50日", "近100日")
            adapter = object : ArrayAdapter<String>(requireContext(), android.R.layout.simple_spinner_item, presets) {
                init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
                    val tv = super.getView(pos, cv, parent) as TextView
                    tv.textSize = 12f; tv.setTextColor(Color.parseColor("#2E7D32")); tv.typeface = Typeface.DEFAULT_BOLD
                    return tv
                }
            }
            setSelection(0); setBackgroundColor(Color.parseColor("#E8F5E9")); setPadding(dp(6), dp(4), dp(6), dp(4))
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { selectedHotPeriod = pos }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        periodRow.addView(periodSpinner)
        dateLabelTv = TextView(requireContext()).apply {
            text = "  交易日:"; textSize = 12f; setTextColor(Color.parseColor("#999999")); setPadding(dp(8), 0, dp(2), 0)
        }; periodRow.addView(dateLabelTv)
        datePicker = TradingDayPickerView(requireContext()).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            onDateChanged = { d ->
                browsingDate = d
                val isNonTrading = d.dayOfWeek == java.time.DayOfWeek.SATURDAY ||
                        d.dayOfWeek == java.time.DayOfWeek.SUNDAY ||
                        d in TradingDayPickerView.CHINESE_HOLIDAYS
                dateLabelTv.text = if (isNonTrading) "  非交易日:" else "  交易日:"
            }
        }; periodRow.addView(datePicker)
        importCard.addView(periodRow)

        // 进度行
        val statusRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
        progressBar = ProgressBar(requireContext()).apply { visibility = View.GONE; layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) } }
        statusRow.addView(progressBar)
        statusTv = TextView(requireContext()).apply { text = "  选择周期与起始交易日，点击开始导入"; textSize = 11f; setTextColor(Color.parseColor("#888888")) }
        statusRow.addView(statusTv)
        importCard.addView(statusRow)

        // 导入按钮
        importBtn = Button(requireContext()).apply {
            text = "⬇️ 开始导入"; textSize = 13f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#2E7D32"))
            setPadding(dp(8), dp(12), dp(8), dp(12)); layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) }
            setOnClickListener { importHistoricalData() }
        }
        importCard.addView(importBtn)
        pageData.addView(importCard)

        // 说明
        pageData.addView(TextView(requireContext()).apply {
            text = "💡 导入完成后自动展示当前热门板块；市场上下文缓存用于策略执行时聚合最新板块/指数数据，刷新后下次执行策略强制重新拉取。"
            textSize = 10f; setTextColor(Color.parseColor("#AAAAAA")); setPadding(dp(16), dp(8), dp(16), dp(4))
        })
    }

    /** 构建顶部 tab 栏并默认选中第一个页面 */
    private fun buildTabBar() {
        val tabs = listOf(
            "📥 数据" to 0,
            "🎯 拟合" to 1,
            "📌 PC参数" to 2,
            "📈 回溯" to 3
        )
        tabs.forEach { (title, idx) ->
            val tab = TextView(requireContext()).apply {
                text = title
                gravity = Gravity.CENTER
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, dp(12), 0, dp(12))
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { switchTab(idx) }
            }
            tabBar.addView(tab)
        }
        switchTab(0)
    }

    /** 切换 tab：显示对应页面，高亮选中项 */
    private fun switchTab(idx: Int) {
        pages.forEachIndexed { i, page ->
            page.visibility = if (i == idx) View.VISIBLE else View.GONE
        }
        for (i in 0 until tabBar.childCount) {
            val tab = tabBar.getChildAt(i) as TextView
            val selected = i == idx
            tab.setBackgroundColor(if (selected) Color.parseColor("#1E88E5") else Color.WHITE)
            tab.setTextColor(if (selected) Color.WHITE else Color.parseColor("#444444"))
        }
    }

    // ═══════════════ ① 🔄 刷新市场上下文 ═══════════════
    private fun refreshMarketContext() {
        StrategyMarketContext.invalidateCache()
        Toast.makeText(requireContext(), "市场上下文缓存已清空，下次执行策略将重新拉取最新数据", Toast.LENGTH_SHORT).show()
    }

    // ═══════════════ ② 📊 数据库统计信息 ═══════════════
    private fun showDbStats() {
        val exporter = DataExportImport(requireContext())
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val stats = exporter.getDatabaseStats()
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("📊 数据库统计")
                        .setMessage(stats as CharSequence)
                        .setPositiveButton("关闭", null)
                        .show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "获取统计失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ═══════════════ ③ 🧠 市场记忆设置 ═══════════════
    private fun showMarketMemoryDialog() {
        val memory = UserMarketMemory(requireContext())
        val ctx = requireContext()

        val loadingTv = TextView(ctx).apply {
            text = "⏳ 正在载入..."
            textSize = 13f
            setPadding(dp(32), dp(48), dp(32), dp(48))
            gravity = Gravity.CENTER
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("🧠 市场记忆设置")
            .setView(loadingTv)
            .setNegativeButton("关闭", null)
            .show()

        lifecycleScope.launch(Dispatchers.IO) {
            val allSectors = memory.getAllFocusSectors()
            val aiDetection = memory.aiYearDetection
            val hotNames = memory.getRecentHotSectors().map { it.sectorName }
            val newsNames = memory.getRecentNewsSectors()
            val suggestedNames = (hotNames + newsNames).distinct()
                .filter { name -> allSectors.none { it.sectorName == name } }
                .take(8)

            withContext(Dispatchers.Main) {
                if (!isAdded) { dialog.dismiss(); return@withContext }
                dialog.dismiss()
                buildMemoryDialogContent(ctx, memory, allSectors, suggestedNames, aiDetection)
            }
        }
    }

    private fun buildMemoryDialogContent(
        ctx: android.content.Context,
        memory: UserMarketMemory,
        allSectors: List<com.chin.stockanalysis.strategy.sector.UserFocusSectorEntity>,
        suggestedNames: List<String>,
        aiDetection: String
    ) {
        val scrollView = ScrollView(ctx).apply { setPadding(dp(24), dp(16), dp(24), dp(16)) }
        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scrollView.addView(container)

        // AI 检测区
        container.addView(TextView(ctx).apply {
            text = "🤖 AI 市场判断：$aiDetection"
            textSize = 12f
            setTextColor(Color.parseColor("#1565C0"))
            setPadding(0, 0, 0, dp(12))
        })
        container.addView(Button(ctx).apply {
            text = "🔄 重新检测市场风格"
            textSize = 11f
            setOnClickListener {
                text = "⏳ 检测中..."
                isEnabled = false
                lifecycleScope.launch {
                    val result = memory.detectSectorYearByIndex()
                    memory.aiYearDetection = result
                    StrategyMarketContext.invalidateCache()
                    Toast.makeText(ctx, "AI 检测：$result", Toast.LENGTH_LONG).show()
                }
            }
        })

        // 分隔线
        container.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply { topMargin = dp(16); bottomMargin = dp(16) }
            setBackgroundColor(Color.parseColor("#E0E0E0"))
        })

        // 已记录板块区
        container.addView(TextView(ctx).apply {
            text = "📋 已记录的主力板块（点击切换启用/停用）"
            textSize = 12f
            setTextColor(Color.parseColor("#333333"))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        })
        if (allSectors.isEmpty()) {
            container.addView(TextView(ctx).apply {
                text = "（尚无记录，请在下方新增）"
                textSize = 11f
                setTextColor(Color.parseColor("#999999"))
                setPadding(0, dp(4), 0, dp(12))
            })
        } else {
            val chipsFlow = FlowLayout(ctx).apply { setPadding(0, 0, 0, dp(8)) }
            for (sector in allSectors) {
                chipsFlow.addView(buildSectorChip(ctx, memory, sector.sectorName, sector.isActive))
            }
            container.addView(chipsFlow)
        }

        // 分隔线
        container.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply { topMargin = dp(8); bottomMargin = dp(16) }
            setBackgroundColor(Color.parseColor("#E0E0E0"))
        })

        // 新增关注板块
        container.addView(TextView(ctx).apply {
            text = "➕ 新增关注板块"
            textSize = 12f
            setTextColor(Color.parseColor("#E65100"))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        })
        val input = EditText(ctx).apply { hint = "输入板块名称（如：新能源）"; textSize = 12f }
        container.addView(input)
        container.addView(Button(ctx).apply {
            text = "✅ 新增"
            textSize = 11f
            setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isEmpty()) { Toast.makeText(ctx, "请输入板块名称", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                lifecycleScope.launch(Dispatchers.IO) {
                    memory.addOrActivateSector(name)
                    StrategyMarketContext.invalidateCache()
                    val updated = memory.getAllFocusSectors()
                    val ai = memory.aiYearDetection
                    val hot = memory.getRecentHotSectors().map { it.sectorName }
                    val news = memory.getRecentNewsSectors()
                    val suggested = (hot + news).distinct().filter { n -> updated.none { it.sectorName == n } }.take(8)
                    withContext(Dispatchers.Main) {
                        if (isAdded) buildMemoryDialogContent(ctx, memory, updated, suggested, ai)
                        Toast.makeText(ctx, "已新增「$name」", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })

        // 建议板块
        if (suggestedNames.isNotEmpty()) {
            container.addView(TextView(ctx).apply {
                text = "💡 近期热门但未记录的板块（点击新增）："
                textSize = 11f
                setTextColor(Color.parseColor("#888888"))
                setPadding(0, dp(12), 0, dp(4))
            })
            val suggestFlow = FlowLayout(ctx)
            for (name in suggestedNames) {
                val chip = TextView(ctx).apply {
                    text = "+ $name"
                    textSize = 11f
                    setPadding(dp(20), dp(8), dp(20), dp(8))
                    setTextColor(Color.parseColor("#555555"))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = 16f
                        setColor(Color.parseColor("#FFF3E0"))
                    }
                    setOnClickListener {
                        lifecycleScope.launch(Dispatchers.IO) {
                            memory.addOrActivateSector(name)
                            StrategyMarketContext.invalidateCache()
                            val updated = memory.getAllFocusSectors()
                            val ai = memory.aiYearDetection
                            val hot = memory.getRecentHotSectors().map { it.sectorName }
                            val news = memory.getRecentNewsSectors()
                            val suggested = (hot + news).distinct().filter { n -> updated.none { it.sectorName == n } }.take(8)
                            withContext(Dispatchers.Main) {
                                if (isAdded) buildMemoryDialogContent(ctx, memory, updated, suggested, ai)
                                Toast.makeText(ctx, "已新增「$name」", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                val lp = FlowLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, 0, dp(8), dp(8))
                chip.layoutParams = lp
                suggestFlow.addView(chip)
            }
            container.addView(suggestFlow)
        }

        AlertDialog.Builder(ctx)
            .setTitle("🧠 市场记忆设置")
            .setView(scrollView)
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun buildSectorChip(
        ctx: android.content.Context,
        memory: UserMarketMemory,
        name: String,
        isActive: Boolean
    ): TextView {
        return TextView(ctx).apply {
            text = if (isActive) "✓ $name" else "☐ $name"
            textSize = 11f
            setPadding(dp(20), dp(8), dp(20), dp(8))
            setTextColor(if (isActive) Color.WHITE else Color.parseColor("#999999"))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 16f
                setColor(if (isActive) Color.parseColor("#4CAF50") else Color.parseColor("#EEEEEE"))
            }
            setOnClickListener {
                val newActive = !isActive
                lifecycleScope.launch(Dispatchers.IO) {
                    memory.toggleSector(name, newActive)
                    StrategyMarketContext.invalidateCache()
                    val updated = memory.getAllFocusSectors()
                    val ai = memory.aiYearDetection
                    val hot = memory.getRecentHotSectors().map { it.sectorName }
                    val news = memory.getRecentNewsSectors()
                    val suggested = (hot + news).distinct().filter { n -> updated.none { it.sectorName == n } }.take(8)
                    withContext(Dispatchers.Main) {
                        if (isAdded) buildMemoryDialogContent(ctx, memory, updated, suggested, ai)
                    }
                }
            }
        }
    }

    // ═══════════════ ④ 📥 拉取股票报告 ═══════════════
    private fun showFetchReport() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val latestDates = db.dailySnapshotDao().getAvailableDates(20).sorted()
                if (latestDates.size < 2) { withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "需先导入数据", Toast.LENGTH_SHORT).show() }; return@launch }
                val sb = StringBuilder()
                sb.appendLine("📥 拉取股票报告 (最近 ${latestDates.size} 天)")
                sb.appendLine()
                for (date in latestDates.takeLast(5)) {
                    val snaps = db.dailySnapshotDao().getByDate(date)
                    sb.appendLine("━━━ $date ━━━")
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
                sb.appendLine("📅 上次导入: $lastImport")
                withContext(Dispatchers.Main) { AlertDialog.Builder(requireContext()).setTitle("拉取股票报告").setMessage(sb.toString()).setPositiveButton("关闭", null).show() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "加载失败: ${e.message}", Toast.LENGTH_SHORT).show() } }
        }
    }

    // ═══════════════ ⑤ ⬇️ 导入历史行情 ═══════════════
    private fun importHistoricalData() {
        importBtn.isEnabled = false; importBtn.text = "⏳"
        progressBar.visibility = View.VISIBLE
        val days = when (selectedHotPeriod) { 0->1; 1->3; 2->10; 3->30; 4->50; 5->100; else->60 }
        val label = when (selectedHotPeriod) { 0->"当日"; 1->"近3日"; 2->"近10日"; 3->"近30日"; 4->"近50日"; 5->"近100日"; else->"历史" }
        val useStartDate = browsingDate
        statusTv.text = "  正在从东方财富拉取 $browsingDate ~ 至今 的${label}K线..."
        lifecycleScope.launch {
            try {
                val f = HistoricalDataFetcher(requireContext())
                val t = f.fetchAllHistoricalData(days, force = true, startDateOverride = useStartDate) { p ->
                    lifecycleScope.launch(Dispatchers.Main) { statusTv.text = "  进度: ${p.completedStocks}/${p.totalStocks} 只 · ${p.totalRecords} 条" }
                }
                withContext(Dispatchers.Main) {
                    importBtn.isEnabled = true; importBtn.text = "⬇️ 开始导入"; progressBar.visibility = View.GONE
                    statusTv.text = "  ✅ 导入完成 · $t 条历史记录"
                    // 导入完成后展示当前热门板块
                    showHotSectorsReport()
                    val recent = TradingDayPickerView.recentTradingDay()
                    if (browsingDate != recent) { browsingDate = recent; datePicker.selectedDate = recent }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { importBtn.isEnabled = true; importBtn.text = "⬇️ 开始导入"; progressBar.visibility = View.GONE; statusTv.text = "  导入失败: ${e.message}" }
            }
        }
    }

    /** 导入完成后展示热门板块（最近 30 个交易日报告） */
    private fun showHotSectorsReport() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val recentDays = db.sectorDailyRecordDao().getRecentDays(30)
                if (recentDays.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "无热门板块数据（可先运行策略扫描）", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val sb = StringBuilder()
                sb.appendLine("🔥 当前热门板块（最近 30 个交易日）")
                sb.appendLine("导出时间: ${LocalDate.now()}"); sb.appendLine()
                val grouped = recentDays.groupBy { it.date }.toSortedMap()
                for ((date, sectors) in grouped) {
                    val hotCount = sectors.count { it.isHot in listOf("S", "Y", "true", "A") }
                    sb.appendLine("📅 $date（${sectors.size} 板块，${hotCount} 热门）")
                    for (s in sectors.sortedByDescending { it.hotScore }.take(10)) {
                        val tag = when (s.rank) { in 1..3 -> "🔥"; in 4..10 -> "⭐"; else -> "  " }
                        val hotLabel = when (s.isHot) {
                            "S", "Y", "true" -> "🔥热门"
                            "A" -> "⭐关注"
                            else -> ""
                        }
                        val hotSuffix = if (hotLabel.isNotEmpty()) " $hotLabel" else ""
                        val consecLabel = if (s.consecutiveHotDays > 0) " 连板${s.consecutiveHotDays}天" else ""
                        sb.appendLine("  $tag ${s.sectorName} 涨幅:${"%.2f".format(s.changePct)}% 主力:${"%.0f".format(s.mainNetInflow)}万 评分:${"%.1f".format(s.hotScore)}$consecLabel$hotSuffix")
                    }
                    sb.appendLine()
                }
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("🔥 热门板块报告")
                        .setMessage(sb.toString() as CharSequence)
                        .setPositiveButton("关闭", null)
                        .show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "热门板块加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ═══════════════ ⑦ 📤 导出K线快照 ═══════════════
    /** 按所选周期导出 N 日 K 线快照到 Downloads（CSV），移植自旧版策略 Tab */
    private fun exportSnapshotData() {
        val days = when (selectedHotPeriod) { 0->1; 1->3; 2->10; 3->30; 4->50; 5->100; else->1 }
        val label = when (selectedHotPeriod) { 0->"1日"; 1->"3日"; 2->"10日"; 3->"30日"; 4->"50日"; 5->"100日"; else->"当日" }
        statusTv.text = "  正在导出${label}K线数据..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val allDates = db.dailySnapshotDao().getAvailableDates(days + 5).sorted().takeLast(days)
                if (allDates.isEmpty()) {
                    withContext(Dispatchers.Main) { statusTv.text = "  ⚠️ 无可用日期数据"; Toast.makeText(requireContext(), "数据库中没有K线数据，请先导入", Toast.LENGTH_SHORT).show() }
                    return@launch
                }
                val sb = StringBuilder()
                sb.appendLine("stockCode,stockName,date,open,high,low,close,volume,amount,changePct,turnoverRate")
                var totalRows = 0
                for (date in allDates) {
                    try {
                        val snaps = db.dailySnapshotDao().getByDate(date)
                        for (snap in snaps) {
                            sb.appendLine("${snap.code},${snap.name},${snap.date},${snap.open},${snap.high},${snap.low},${snap.close},${snap.volume},${snap.amount},${snap.changePct},${snap.turnoverRate}")
                            totalRows++
                        }
                    } catch (_: Exception) {}
                }
                if (totalRows == 0) {
                    withContext(Dispatchers.Main) { statusTv.text = "  ⚠️ 无快照数据可导出"; Toast.makeText(requireContext(), "无数据可导出", Toast.LENGTH_SHORT).show() }
                    return@launch
                }
                val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val fileName = "StockAnalysis_snapshot_${label}_${LocalDate.now()}.csv"
                val file = java.io.File(dir, fileName)
                file.writeText(sb.toString())
                val sizeKb = file.length() / 1024
                withContext(Dispatchers.Main) {
                    statusTv.text = "  ✅ 已导出 ${allDates.size}天K线数据 (${totalRows}行, ${sizeKb}KB)"
                    Toast.makeText(requireContext(), "已保存到 Downloads/$fileName（$totalRows 行）", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTv.text = "  导出失败: ${e.message?.take(30)}"
                    Toast.makeText(requireContext(), "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ═══════════════ ⑧ 🎯 自测拟合（自工作台迁移） ═══════════════
    /** 按四周期逐周期自测拟合调优（StrategySelfTuner 增量梯度优化，目标准确率 90%） */
    private fun runFitting() {
        val eng = getEngine()
        setBusy(true, "🎯 自测拟合(目标90%)中...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tuner = StrategySelfTuner(requireContext())
                val sb = StringBuilder()
                var totalTuned = 0
                for (p in FIT_PERIODS) {
                    val strategies = strategiesFor(p, eng)
                    if (strategies.isEmpty()) {
                        sb.appendLine("${p.label}: ⚠️ 无启用策略")
                        continue
                    }
                    try {
                        val report = tuner.selfTune(strategies, backtestDays = 30, targetAccuracy = 0.90f)
                        totalTuned += report.strategyTuneDetails.size
                        sb.appendLine("${p.label}: ${strategies.size} 个策略自测调优完成（回测区间 ${report.dateRange}）")
                    } catch (e: Exception) {
                        Log.w(TAG, "${p.label}自测拟合失败: ${e.message}")
                        sb.appendLine("${p.label}: ⚠️ 自测拟合失败: ${e.message?.take(30)}")
                    }
                }
                sb.appendLine()
                sb.appendLine("✅ 共调优 $totalTuned 个策略（权重已落库 strategy_weight_snapshot，下次执行策略自动加载）")
                withContext(Dispatchers.Main) {
                    setBusy(false, "✅ 自测拟合完成")
                    showDialog("🎯 自测拟合报告(目标90%)", sb.toString())
                }
            } catch (e: Exception) {
                Log.e(TAG, "自测拟合失败: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    setBusy(false, "❌ 自测拟合失败: ${e.message?.take(40)}")
                    Toast.makeText(requireContext(), "自测拟合失败: ${e.message?.take(40)}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ═══════════════ ⑨ 📐 状态矩阵拟合（自工作台迁移） ═══════════════
    /** 按大盘状态对中/长线网格拟合卖出参数，产出状态参数矩阵并落库（HoldingGuardNode 自动应用） */
    private fun runStateFit() {
        setBusy(true, "📐 状态矩阵拟合中...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ctx = requireContext()
                val sb = StringBuilder()
                for (period in listOf("中线", "长线")) {
                    try {
                        val res = FullCycleBacktestEngine.fitByState(ctx, period) { msg ->
                            withContext(Dispatchers.Main) { statusTv.text = msg.take(60) }
                        }
                        sb.appendLine(res.report)
                    } catch (e: Exception) {
                        sb.appendLine("[$period] 拟合异常: ${e.message?.take(50)}")
                    }
                }
                sb.appendLine()
                sb.appendLine("✅ 参数矩阵已落库 backtest_meta；HoldingGuardNode 按当前大盘状态自动应用")
                sb.appendLine("暴跌期(CRASH)强制 1 天迅速离场，不依赖矩阵参数")
                withContext(Dispatchers.Main) {
                    setBusy(false, "✅ 状态矩阵拟合完成")
                    showDialog("状态参数矩阵", sb.toString())
                }
            } catch (e: Exception) {
                Log.e(TAG, "状态矩阵拟合失败: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    setBusy(false, "❌ 拟合失败: ${e.message?.take(40)}")
                    Toast.makeText(requireContext(), "拟合失败: ${e.message?.take(40)}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ═══════════════ ⑩ 📤 导出拟合矩阵（自工作台迁移） ═══════════════
    /**
     * 📤 导出本机拟合矩阵（backtest_meta → sell_rules 结构 JSON），
     * 供 PC 端 smalltools 纳入下次 walk-forward 拟合（边买卖边完善 PC 拟合）。
     * 文件写到 app 外部私有目录 pc_fit_export/，Toast 显示完整路径。
     */
    private fun exportFitMatrix() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ctx = requireContext()
                val db = StockDatabase.getInstance(ctx)
                val root = JSONObject()
                root.put("exported_at", LocalDate.now().toString())
                root.put("source", "StockAnalysis APK 本机拟合导出 → PC walk-forward 素材")
                val sellRules = JSONObject()
                for (period in listOf("超短", "短线", "中线", "长线")) {
                    val json = db.backtestMetaDao().get("fit_matrix_$period") ?: continue
                    val matrix = JSONObject(json)
                    val periodObj = JSONObject()
                    val byState = JSONObject()
                    var defaultRule: JSONObject? = null
                    val keys = matrix.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val item = matrix.optJSONObject(k) ?: continue
                        val rule = JSONObject()
                        rule.put("style", when (period) {
                            "超短" -> "nextday"
                            "短线" -> "streak"
                            else -> "hold"
                        })
                        rule.put("maxHold", item.optInt("hold", 15))
                        rule.put("tp", item.optDouble("tp", 20.0))
                        rule.put("sl", item.optDouble("sl", -10.0))
                        if (item.has("avg")) rule.put("avg", item.optDouble("avg"))
                        if (item.has("wr")) rule.put("wr", item.optDouble("wr"))
                        if (item.has("n")) rule.put("n", item.optInt("n"))
                        byState.put(k, rule)
                        if (k == "OSCILLATION") defaultRule = rule
                    }
                    if (defaultRule != null) periodObj.put("default", defaultRule)
                    periodObj.put("by_state", byState)
                    sellRules.put(period, periodObj)
                }
                root.put("sell_rules", sellRules)
                val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "pc_fit_export").apply { mkdirs() }
                val file = File(dir, "fit_matrix_${LocalDate.now()}.json")
                file.writeText(root.toString(2))
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, "已导出拟合矩阵: ${file.absolutePath}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "导出拟合矩阵失败: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "导出失败: ${e.message?.take(50)}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ═══════════════ ⑪ 📥 增量拉取历史（自工作台迁移） ═══════════════
    /** 增量拉取 2024 年至今的历史K线：只补每只股票缺失区间，已同步到最新交易日的不重复拉取 */
    private fun fetchBacktestHistory() {
        setBusy(true, "📥 增量拉取（只补缺失区间）...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val fetcher = HistoricalDataFetcher(requireContext())
                val count = fetcher.fetchAllHistoricalData(days = 550, force = true, incremental = true) { p ->
                    requireActivity().runOnUiThread {
                        statusTv.text = "📥 增量拉取中 ${p.completedStocks}/${p.totalStocks} 只 · ${p.totalRecords} 条 · ${p.currentStock}"
                    }
                }
                withContext(Dispatchers.Main) {
                    setBusy(false, "✅ 增量拉取完成：新增 $count 条")
                    Toast.makeText(requireContext(), "增量拉取完成：新增 $count 条，可执行回溯", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "拉取历史失败: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    setBusy(false, "❌ 拉取失败: ${e.message?.take(40)}")
                    Toast.makeText(requireContext(), "拉取失败: ${e.message?.take(40)}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ═══════════════ ⑫ 📦 JSON 数据导入（自工作台迁移） ═══════════════
    /** JSON 数据导入（SAF 选择文件 → 缓存 → DataExportImport 解析入库） */
    private fun doImport(uri: Uri) {
        setBusy(true, "📥 导入中...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bytes = requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("无法读取所选文件")
                val tmp = File(requireContext().cacheDir, "workbench_import_${System.currentTimeMillis()}.json")
                tmp.writeBytes(bytes)
                val report = DataExportImport(requireContext()).importFromJson(tmp.absolutePath)
                tmp.delete()
                val msg = if (report.success) {
                    "导入成功:\n${report.message}"
                } else {
                    "导入失败: ${report.message}"
                }
                withContext(Dispatchers.Main) {
                    setBusy(false, if (report.success) "✅ 导入完成" else "❌ 导入失败")
                    showDialog("数据导入", msg)
                }
            } catch (e: Exception) {
                Log.e(TAG, "导入失败: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    setBusy(false, "❌ 导入失败: ${e.message?.take(40)}")
                    Toast.makeText(requireContext(), "导入失败: ${e.message?.take(40)}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ═══════════════ ⑬ 📌 PC 拟合参数（自工作台迁移） ═══════════════

    /** 📤 导出当前生效的 PC 拟合参数（backtest_params.json），供 PC 端继续拟合/分享 */
    private fun exportParamsFile() {
        val text = com.chin.stockanalysis.strategy.backtest.BacktestParamsLoader.exportParams(requireContext())
        if (text == null) {
            Toast.makeText(requireContext(), "无参数可导出", Toast.LENGTH_SHORT).show()
            return
        }
        exportParamsLauncher.launch("backtest_params.json")
        pendingExportParams = text
    }

    /** 🔄 重置为 APK 内置参数（删除导入文件） */
    private fun resetParamsFile() {
        if (com.chin.stockanalysis.strategy.backtest.BacktestParamsLoader.resetToBuiltIn(requireContext())) {
            paramsStatusTv.text = "当前参数: ${com.chin.stockanalysis.strategy.backtest.BacktestParamsLoader.sourceLabel(requireContext())}（版本 ${com.chin.stockanalysis.strategy.backtest.BacktestParamsLoader.version(requireContext())}）"
            Toast.makeText(requireContext(), "已恢复内置参数", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "重置失败", Toast.LENGTH_SHORT).show()
        }
    }

    /** 🔍 展示当前生效参数的文本概览（来源/版本/各周期阈值/卖出规则/IC权重/做T阈值） */
    private fun showParamsDetail() {
        val summary = com.chin.stockanalysis.strategy.backtest.BacktestParamsLoader.summary(requireContext())
        showDialog("📦 当前 PC 拟合参数", summary)
    }

    /**
     * 🧬 参数导入：PC 端更新后的 backtest_params.json（含最新 select_params/rank_factors/sell_rules）
     * 导入 APK → BacktestParamsLoader 写入 filesDir 并立即生效。这是「边买卖边完善 PC 拟合」闭环的回传入口。
     */
    private fun doImportParams(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val text = requireContext().contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() }
                if (text.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "文件为空", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val err = com.chin.stockanalysis.strategy.backtest.BacktestParamsLoader
                    .importParams(requireContext().applicationContext, text)
                withContext(Dispatchers.Main) {
                    if (err == null) {
                        paramsStatusTv.text = "当前参数: ${com.chin.stockanalysis.strategy.backtest.BacktestParamsLoader.sourceLabel(requireContext())}（版本 ${com.chin.stockanalysis.strategy.backtest.BacktestParamsLoader.version(requireContext())}）"
                        Toast.makeText(requireContext(), "✅ 参数已导入，选股/排序/卖出全部立即生效", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(requireContext(), "❌ $err", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "参数导入失败: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "导入失败: ${e.message?.take(50)}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ═══════════════ ⑭ 📌 回溯 & 分析（自工作台迁移） ═══════════════

    /** 按四周期逐周期回溯并落库 */
    private fun runBacktest() {
        val selected = FIT_PERIODS
        val eng = getEngine()
        setBusy(true, "⏳ 回溯中...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sb = StringBuilder()
                var totalSaved = 0
                for (p in selected) {
                    val strategies = strategiesFor(p, eng)
                    if (strategies.isEmpty()) {
                        sb.appendLine("${p.label}: ⚠️ 无启用策略")
                        continue
                    }
                    val backtestEngine = com.chin.stockanalysis.strategy.backtest.HistoricalBacktestEngine(requireContext())
                    val report = backtestEngine.runHistoricalBacktest(strategies, tradingDays = 30)

                    val today = LocalDate.now().toString()
                    val entities = report.strategyReports.map { r ->
                        StrategyTradeBacktestEntity(
                            periodKey = p.key,
                            strategyId = r.strategyId,
                            strategyName = r.strategyName,
                            tradeDate = today,
                            totalDays = r.totalDays,
                            signalCount = r.totalBuys,
                            correctCount = r.correctBuys,
                            accuracy = r.buyAccuracy.toDouble(),
                            avgReturn = r.avgReturn,
                            maxGain = r.maxGain,
                            maxLoss = r.maxLoss
                        )
                    }
                    StockDatabase.getInstance(requireContext()).strategyTradeBacktestDao()
                        .insertAll(entities)
                    totalSaved += entities.size
                    val best = report.strategyReports.maxByOrNull { it.buyAccuracy }
                    sb.appendLine("${p.label}: ${strategies.size} 个策略 | 落库 ${entities.size} 条")
                    if (best != null) {
                        sb.appendLine("  最佳: ${best.strategyName} 准确率 ${"%.1f".format(best.buyAccuracy * 100)}% 平均收益 ${"%.2f".format(best.avgReturn)}%")
                    }
                }
                sb.appendLine()
                sb.appendLine("✅ 共落库 $totalSaved 条回溯结果（按周期分别保存）")
                withContext(Dispatchers.Main) {
                    setBusy(false, "✅ 回溯完成")
                    refreshResults()
                    showDialog("回溯完成", sb.toString())
                }
            } catch (e: Exception) {
                Log.e(TAG, "回溯失败: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    setBusy(false, "❌ 回溯失败: ${e.message?.take(40)}")
                    Toast.makeText(requireContext(), "回溯失败: ${e.message?.take(40)}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * 多周期全流程回溯（增量）：
     * 超短隔日卖 / 短线连跌卖 / 中长线做T+止盈止损，固定本金口径统计。
     * 已回溯的信号日不重复跑（backtest_meta 记录进度）；中/长线记录持久化，短期只留30天。
     */
    private fun runFullCycleBacktest() {
        setBusy(true, "🔄 多周期回溯中（增量）...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ctx = requireContext()
                val results = com.chin.stockanalysis.strategy.backtest.FullCycleBacktestEngine.runAll(ctx) { msg ->
                    withContext(Dispatchers.Main) { statusTv.text = msg.take(60) }
                }
                val sb = StringBuilder()
                for (r in results) sb.appendLine(r.report)
                sb.appendLine()
                sb.appendLine("周期   信号  平均       胜率    固定本金累计  盈亏因子")
                for (r in results) {
                    val pf = if (r.profitFactor == Double.POSITIVE_INFINITY) "∞" else "%.2f".format(r.profitFactor)
                    sb.appendLine("${r.period}   ${r.realizedCount}   ${"%.2f".format(r.avgRet).padStart(8)}%  ${"%.1f".format(r.winRate).padStart(5)}%  ${"%.2f".format(r.fixedCum).padStart(9)}%  $pf")
                }
                sb.appendLine()
                sb.appendLine("💾 中/长线选中记录已持久化（backtest_selected_stock），超短/短线仅保留30天")
                sb.appendLine("下次点击「🔄 多周期回溯」只回溯新增区间")
                withContext(Dispatchers.Main) {
                    setBusy(false, "✅ 多周期回溯完成")
                    showDialog("多周期回溯报告（固定本金口径）", sb.toString())
                }
            } catch (e: Exception) {
                Log.e(TAG, "多周期回溯失败: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    setBusy(false, "❌ 回溯失败: ${e.message?.take(40)}")
                    Toast.makeText(requireContext(), "回溯失败: ${e.message?.take(40)}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** 查看历史选中记录（中/长线长期保留） */
    private fun showSelectedRecords() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val list = db.backtestSelectedStockDao().getByPeriods(listOf("中线", "长线"))
                val recent = list.take(60)
                val sb = StringBuilder()
                if (recent.isEmpty()) {
                    sb.append("暂无选中记录。\n\n请先点击「🔄 多周期回溯」执行回溯，再回来查看。")
                } else {
                    sb.appendLine("中/长线选中记录共 ${list.size} 条（显示最近 60 条）")
                    sb.appendLine("─".repeat(48))
                    for (r in recent) {
                        sb.appendLine("${r.signalDate} ${r.period} ${r.name}(${r.code.takeLast(6)})")
                        sb.appendLine("  [${r.marketState}] 买${r.buyDate}@${"%.2f".format(r.buyPrice)} → 卖${r.sellDate ?: "持有中"} 收益${"%.2f".format(r.retPct)}% 做T+${"%.2f".format(r.tProfitPct)}% [${r.exitReason}]")
                    }
                    sb.appendLine()
                    sb.appendLine("（超短/短线记录仅保留30天，不在本列表展示）")
                }
                withContext(Dispatchers.Main) { showDialog("历史选中记录", sb.toString()) }
            } catch (e: Exception) {
                Log.e(TAG, "查看选中记录失败: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "查看记录失败: ${e.message?.take(40)}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** 汇总各周期回溯 + 拟合数据 → 生成 prompt → 跳转聊天页 */
    private fun runCrossPeriodAnalysis() {
        setBusy(true, "🤖 汇总跨周期数据中...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val eng = getEngine()
                val sb = StringBuilder()

                sb.appendLine("请基于以下各周期量化策略的【历史回溯】与【拟合调优】数据，给出综合的交易策略建议：")
                sb.appendLine()
                sb.appendLine("## 一、各周期历史回溯结果（最近一次）")
                for (p in FIT_PERIODS) {
                    val items = db.strategyTradeBacktestDao().getByPeriod(p.key).take(5)
                    if (items.isEmpty()) {
                        sb.appendLine()
                        sb.appendLine("【${p.label}】暂无回溯数据（可先执行「📈 回溯」后重试）")
                        continue
                    }
                    sb.appendLine()
                    sb.appendLine("【${p.label}】回溯日期 ${items.first().tradeDate}：")
                    for (it in items) {
                        sb.appendLine("- ${it.strategyName}: 准确率 ${"%.1f".format(it.accuracy * 100)}% | 平均收益 ${"%.2f".format(it.avgReturn)}% | 信号 ${it.signalCount} 次 | 最大盈 ${"%.1f".format(it.maxGain)}% / 最大亏 ${"%.1f".format(it.maxLoss)}%")
                    }
                }

                sb.appendLine()
                sb.appendLine("## 二、各策略拟合调优最佳参数")
                var fittedCount = 0
                for (strategy in eng.getStrategies()) {
                    if (!eng.isEnabled(strategy.id)) continue
                    val params = db.strategyTradeFittingParamDao().getRecentByStrategy(strategy.id, 50)
                    if (params.isEmpty()) continue
                    fittedCount++
                    val best = params.maxByOrNull { it.accuracy }
                    sb.appendLine()
                    sb.appendLine("【${strategy.name}】共 ${params.size} 条拟合记录")
                    if (best != null) {
                        sb.appendLine("- 最佳: [${best.periodDays}日] 准确率 ${"%.2f".format(best.accuracy * 100)}% | 平均收益 ${"%.2f".format(best.avgReturn)}%")
                        if (best.paramJson.isNotBlank()) sb.appendLine("- 参数: ${best.paramJson.take(200)}")
                    }
                }
                if (fittedCount == 0) sb.appendLine("（暂无拟合数据）")

                sb.appendLine()
                sb.appendLine("请结合以上数据，从以下角度给出可执行建议：")
                sb.appendLine("1. 各周期（超短/短/中/长）策略的有效性对比与取舍")
                sb.appendLine("2. 当前市场环境下建议的仓位配置与周期侧重")
                sb.appendLine("3. 需要注意的风险点与止损建议")
                sb.appendLine("4. 是否需要调整或停用某周期表现较差的策略")

                val prompt = sb.toString()
                withContext(Dispatchers.Main) {
                    setBusy(false, "✅ 已生成跨周期分析请求")
                    (activity as? MainActivity)?.switchToChatAndSend(prompt)
                }
            } catch (e: Exception) {
                Log.e(TAG, "跨周期分析失败: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    setBusy(false, "❌ 汇总失败: ${e.message?.take(40)}")
                    Toast.makeText(requireContext(), "跨周期分析失败: ${e.message?.take(40)}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** 从库里读取各周期最近回溯结果，统一对比展示 */
    private fun refreshResults() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val sb = StringBuilder()
                var anyData = false
                for (p in FIT_PERIODS) {
                    val items = db.strategyTradeBacktestDao().getByPeriod(p.key).take(4)
                    if (items.isEmpty()) continue
                    anyData = true
                    sb.appendLine("【${p.label}】${items.first().tradeDate}")
                    for (it in items) {
                        sb.appendLine("  ${it.strategyName}: 准确率 ${"%.1f".format(it.accuracy * 100)}% | 收益 ${"%.2f".format(it.avgReturn)}% | 信号 ${it.signalCount}")
                    }
                    sb.appendLine()
                }
                if (!anyData) {
                    sb.append("暂无回溯数据\n\n点击「📈 回溯」按周期执行历史回溯测试，结果将自动落库。")
                }
                withContext(Dispatchers.Main) {
                    resultTv.text = sb.toString()
                }
            } catch (e: Exception) {
                Log.w(TAG, "刷新回溯结果失败: ${e.message}")
            }
        }
    }

    // ═══════════════ 辅助方法 ═══════════════
    private fun getEngine(): StrategyEngine {
        val ctx = requireContext().applicationContext
        StrategyEngineHolder.init(ctx)
        return StrategyEngineHolder.get()
    }

    private fun strategiesFor(p: PeriodInfo, eng: StrategyEngine): List<Strategy> =
        if (p.holdingPeriod != null) eng.getEnabledStrategiesByPeriod(p.holdingPeriod)
        else eng.getStrategies().filter { eng.isEnabled(it.id) }

    private fun setBusy(busy: Boolean, tip: String) {
        if (busy) {
            statusTv.text = tip
            progressBar.visibility = View.VISIBLE
        } else {
            statusTv.text = ""
            progressBar.visibility = View.GONE
        }
    }

    private fun showDialog(title: String, content: String) {
        val sv = ScrollView(requireContext())
        sv.addView(TextView(requireContext()).apply {
            text = content
            textSize = 11f
            setTextColor(Color.parseColor("#333333"))
            setPadding(16, 12, 16, 12)
            setLineSpacing(2f, 1.1f)
            setTypeface(Typeface.MONOSPACE)
        })
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(sv)
            .setPositiveButton("确定", null)
            .show()
    }
}
