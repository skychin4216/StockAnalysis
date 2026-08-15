package com.chin.stockanalysis.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.*
import android.widget.*
import android.widget.LinearLayout.LayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.agent.core.*
import com.chin.stockanalysis.agent.pipeline.ui.PipelineProgressView
import com.chin.stockanalysis.stock.database.StockDataCenter
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.StrategyEngine
import com.chin.stockanalysis.strategy.StrategyEngineHolder
import com.chin.stockanalysis.strategy.models.StrategySignal
import com.chin.stockanalysis.strategy.predict.AIPredictionEngine
import com.chin.stockanalysis.strategy.sector.UserMarketMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ## AI 分析 Tab — v12.0
 *
 * 由量化选股的「🧠 Agent 分析」升级为独立页面：
 * - 设置/点选用户关注的板块或股票
 * - 显示分析过程（PipelineProgressView）与结果
 * - 后续可扩展更多 AI 分析能力
 */
class AIAnalysisFragment : Fragment() {

    private lateinit var layout: LinearLayout
    private lateinit var inputEt: EditText
    private lateinit var statusTv: TextView
    private lateinit var pipelineProgressView: PipelineProgressView
    private lateinit var analyzeBtn: Button
    private var engine: StrategyEngine? = null
    private lateinit var mainBoardSwitch: Switch

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        StrategyEngineHolder.init(requireContext())
        engine = StrategyEngineHolder.get()
        layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#F5F6FA"))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        buildUI(); return layout
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUI() {
        val scroll = ScrollView(requireContext()).apply { isVerticalScrollBarEnabled = false }
        val content = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(20)) }
        scroll.addView(content)
        layout.addView(scroll)

        // ── 标题区 ──
        val titleRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, 0, dp(4)) }
        titleRow.addView(TextView(requireContext()).apply { text = "🤖"; textSize = 20f; setPadding(0, 0, dp(8), 0) })
        titleRow.addView(TextView(requireContext()).apply {
            text = "AI 分析"; textSize = 18f; setTextColor(Color.parseColor("#333333")); typeface = Typeface.DEFAULT_BOLD
        })
        content.addView(titleRow)

        content.addView(TextView(requireContext()).apply {
            text = "可设置关注的板块或股票，AI 自动选择分析模式（六智体/七智体）"
            textSize = 12f; setTextColor(Color.parseColor("#888888")); setPadding(dp(4), 0, 0, dp(10))
        })

        // ── 输入行 ──
        val inputRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        inputEt = EditText(requireContext()).apply {
            hint = "载入热门板块中..."; textSize = 14f; setTextColor(Color.parseColor("#333333")); setHintTextColor(Color.parseColor("#AAAAAA"))
            setPadding(dp(14), dp(12), dp(14), dp(12)); setSingleLine(true)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#FFFFFF")); setCornerRadius(12f); setStroke(2, Color.parseColor("#E0E0E0"))
            }
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
        }
        inputRow.addView(inputEt)
        mainBoardSwitch = Switch(requireContext()).apply {
            text = "主板"; textSize = 11f; isChecked = true
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(4) }
        }
        inputRow.addView(mainBoardSwitch)
        analyzeBtn = Button(requireContext()).apply {
            text = "🚀 分析"; textSize = 13f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#6A1B9A"))
            setPadding(dp(14), dp(10), dp(14), dp(10)); setMinWidth(0); setMinimumWidth(0)
            setOnClickListener { executeAIPipeline(inputEt.text.toString().trim()) }
        }
        inputRow.addView(analyzeBtn)
        content.addView(inputRow)

        // ── 关注设置区 ──
        content.addView(TextView(requireContext()).apply {
            text = "🎯 已关注的板块/股票（点击填入分析框）"
            textSize = 13f; setTextColor(Color.parseColor("#666666")); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(12), 0, dp(6))
        })
        val focusFlow = FlowLayout(requireContext()).apply { setPadding(dp(4), 0, 0, 0) }
        content.addView(focusFlow)
        loadFocusSectors(focusFlow)
        content.addView(TextView(requireContext()).apply {
            text = "➕ 添加关注：输入板块名或股票名后点这里"
            textSize = 11f; setTextColor(Color.parseColor("#888888")); setPadding(dp(4), dp(6), 0, dp(2))
        })
        content.addView(Button(requireContext()).apply {
            text = "➕ 加入关注列表"; textSize = 11f; setTextColor(Color.parseColor("#1565C0"))
            setBackgroundColor(Color.parseColor("#E3F2FD")); setMinWidth(0); setMinimumWidth(0)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(4); topMargin = dp(2) }
            setOnClickListener { addFocusFromInput() }
        })

        // ── 热门板块区 ──
        content.addView(TextView(requireContext()).apply {
            text = "🔥 近期热门板块（点击填入分析框）"
            textSize = 13f; setTextColor(Color.parseColor("#666666")); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(14), 0, dp(6))
        })
        val hotFlow = FlowLayout(requireContext()).apply { setPadding(dp(4), 0, 0, 0) }
        content.addView(hotFlow)
        loadHotSectors(hotFlow)

        // ── 状态 ──
        statusTv = TextView(requireContext()).apply {
            text = "💡 输入股票代码/名称或点击上方板块开始分析"
            textSize = 12f; setTextColor(Color.parseColor("#888888")); setPadding(dp(4), dp(12), dp(4), dp(6))
        }
        content.addView(statusTv)

        // ── 分析过程/结果面板 ──
        pipelineProgressView = PipelineProgressView(requireContext()).apply { visibility = View.GONE }
        content.addView(pipelineProgressView)
    }

    // ═══════════════ 关注设置 ═══════════════
    private fun loadFocusSectors(flow: FlowLayout) {
        lifecycleScope.launch(Dispatchers.IO) {
            val memory = UserMarketMemory(requireContext())
            val sectors = memory.getAllFocusSectors()
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                flow.removeAllViews()
                if (sectors.isEmpty()) {
                    flow.addView(TextView(requireContext()).apply {
                        text = "（暂无，可从下方输入添加）"; textSize = 12f; setTextColor(Color.parseColor("#AAAAAA")); setPadding(dp(4), dp(4), dp(4), dp(4))
                    })
                } else {
                    for (s in sectors) {
                        flow.addView(buildChip(s.sectorName, Color.parseColor("#E8F5E9"), Color.parseColor("#2E7D32")) {
                            inputEt.setText(s.sectorName)
                            inputEt.setSelection(s.sectorName.length)
                        })
                    }
                }
            }
        }
    }

    private fun addFocusFromInput() {
        val text = inputEt.text.toString().trim()
        if (text.isEmpty()) { Toast.makeText(requireContext(), "请先输入板块名或股票名", Toast.LENGTH_SHORT).show(); return }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                UserMarketMemory(requireContext()).addOrActivateSector(text)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "已加入关注：$text", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "添加失败: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    // ═══════════════ 热门板块 ═══════════════
    private fun loadHotSectors(flow: FlowLayout) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ctx = requireContext().applicationContext
                val tracker = com.chin.stockanalysis.strategy.backtest.SectorPeriodTracker(ctx)
                val weekly = tracker.getCurrentWeekTopSectors(8)
                val monthly = tracker.getCurrentMonthTopSectors(8)
                val score = mutableMapOf<String, Int>()
                weekly.forEach { score[it] = (score[it] ?: 0) + 2 }
                monthly.forEach { score[it] = (score[it] ?: 0) + 1 }
                val top = score.entries.sortedByDescending { it.value }.map { it.key }
                val sectors = if (top.isEmpty()) StockDataCenter.getHotSectorsByPeriod(30).take(10) else top
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    flow.removeAllViews()
                    if (sectors.isEmpty()) {
                        flow.addView(TextView(requireContext()).apply {
                            text = "暂无热门板块数据"; textSize = 12f; setTextColor(Color.parseColor("#AAAAAA")); setPadding(dp(4), dp(4), dp(4), dp(4))
                        })
                    } else {
                        for (s in sectors.take(10)) {
                            flow.addView(buildChip(s, Color.parseColor("#F3E5F5"), Color.parseColor("#6A1B9A")) {
                                inputEt.setText(s)
                                inputEt.setSelection(s.length)
                            })
                        }
                    }
                    inputEt.hint = "例如：${sectors.take(2).joinToString("、")}"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    flow.addView(TextView(requireContext()).apply {
                        text = "载入失败: ${e.message?.take(30)}"; textSize = 12f; setTextColor(Color.parseColor("#FF5252")); setPadding(dp(4), dp(4), dp(4), dp(4))
                    })
                }
            }
        }
    }

    private fun buildChip(text: String, bg: Int, fg: Int, onClick: () -> Unit): View {
        val ctx = requireContext()
        return TextView(ctx).apply {
            this.text = text; textSize = 12f; setTextColor(fg)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(bg); setCornerRadius(20f); setStroke(1, fg and 0x66FFFFFF) }
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8); topMargin = dp(4) }
            setOnClickListener { onClick() }
        }
    }

    // ═══════════════ 执行分析（移植自量化选股 executeAIPipeline） ═══════════════
    private fun executeAIPipeline(target: String) {
        if (target.isBlank()) {
            Toast.makeText(requireContext(), "请输入标的或选择板块", Toast.LENGTH_SHORT).show()
            return
        }
        analyzeBtn.isEnabled = false
        analyzeBtn.text = "⏳"
        pipelineProgressView.visibility = View.VISIBLE
        pipelineProgressView.reset()
        statusTv.text = "🧠 正在解析目标..."
        pipelineProgressView.updateSteps(DeepAnalystEngine.stepsFor(5))

        val quantProvider: suspend (String) -> List<StrategySignal> = provider@{ stockCode ->
            val eng = engine ?: return@provider emptyList<StrategySignal>()
            try {
                val feed = com.chin.stockanalysis.strategy.data.StrategyDataFeed(requireContext())
                val today = TradingDayPickerView.recentTradingDay().toString()
                val stocks = feed.prepareFromDb(today, com.chin.stockanalysis.strategy.data.StrategyDataFeed.DataFeedConfig(onlyMainBoard = mainBoardSwitch.isChecked))
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
                    statusTv.text = "❌ ${step.name} 错误: ${error.take(40)}"
                }
            }
        }

        val appCtx = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(appCtx)
                val dao = db.stockBasicDao()
                val trimmed = target.trim()
                var code: String? = null
                var name: String? = null
                val byCode = dao.getByCode(trimmed)
                if (byCode != null) {
                    code = byCode.code; name = byCode.name
                } else {
                    val candidates = dao.searchByName(trimmed)
                    val best = candidates.firstOrNull { it.name == trimmed } ?: candidates.maxByOrNull { it.name.length }
                    if (best != null) { code = best.code; name = best.name }
                }

                if (code == null) {
                    // ── 尝试解析为板块：取成分股 Top N 逐个分析，汇总结果 ──
                    val sectorKey = resolveSectorKey(db, trimmed)
                    if (sectorKey != null) {
                        val sectorName = db.sectorStockDao().getSectorName(sectorKey) ?: trimmed
                        withContext(Dispatchers.Main) { statusTv.text = "🧠 板块「$sectorName」解析成功，正在加载成分股..." }
                        analyzeSector(
                            appCtx = appCtx, sectorKey = sectorKey, sectorName = sectorName,
                            quantProvider = quantProvider, stepListener = stepListener
                        )
                        return@launch
                    }
                    withContext(Dispatchers.Main) {
                        statusTv.text = "❌ 无法识别「$trimmed」，请输入股票代码/名称 或 板块名称"
                        analyzeBtn.isEnabled = true; analyzeBtn.text = "🚀 分析"
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) { statusTv.text = "🧠 正在分析 $name($code)..." }

                val result = AgentOrchestrator(appCtx).analyzeStock(
                    stockCode = code, stockName = name,
                    mode = AnalysisMode.DEEP, useAgentFramework = true,
                    stepListener = stepListener, quantSignalsProvider = quantProvider
                )

                withContext(Dispatchers.Main) {
                    pipelineProgressView.showResult(result)
                    statusTv.text = if (!result.success) {
                        "❌ 分析失败: ${result.errorMessage?.take(30) ?: "未知错误"}"
                    } else {
                        val passedStr = if (result.passed == true) "通过" else "未通过"
                        "✅ $name 分析完成 [${result.overallScore}分/$passedStr] 耗时${result.elapsedMs / 1000}s"
                    }
                    analyzeBtn.isEnabled = true; analyzeBtn.text = "🚀 分析"
                    if (result.success) {
                        CrossTabBus.postAiTopPicks(listOf(
                            AIPredictionEngine.AIPick(
                                stockCode = result.stockCode, stockName = result.stockName,
                                compositeScore = result.overallScore,
                                upProbability = if (result.overallScore >= 60) 75 else 50,
                                rank = 1,
                                reason = "AI智能体分析: ${result.barrierLevel ?: result.recommendation ?: "综合评估"}",
                                actionSuggestion = if (result.passed == true) "建议关注" else "风控不通过"
                            )
                        ))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTv.text = "❌ AI 智能体分析异常: ${e.message?.take(40)}"
                    analyzeBtn.isEnabled = true; analyzeBtn.text = "🚀 分析"
                }
            }
        }
    }

    /** 解析输入是否为板块（支持板块 key 或中文板块名） */
    private suspend fun resolveSectorKey(db: StockDatabase, keyword: String): String? {
        val keys = db.sectorStockDao().getAllSectorKeys()
        // 1. 精确匹配 key
        keys.firstOrNull { it.equals(keyword, ignoreCase = true) }?.let { return it }
        // 2. 按板块中文名匹配（精确 or 包含）
        for (key in keys) {
            val name = db.sectorStockDao().getSectorName(key) ?: continue
            if (name == keyword || name.contains(keyword) || keyword.contains(name)) return key
        }
        return null
    }

    /** 板块成分股 Top N 逐个跑 Agent 流水线，汇总结果展示 */
    private fun analyzeSector(
        appCtx: android.content.Context,
        sectorKey: String,
        sectorName: String,
        quantProvider: suspend (String) -> List<StrategySignal>,
        stepListener: AnalysisStepListener
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(appCtx)
                val codes = db.sectorStockDao().getStockCodesBySector(sectorKey)
                val basics = db.stockBasicDao().getByCodes(codes)
                // 主板过滤 + Top 10
                val candidates = basics
                    .filter { !mainBoardSwitch.isChecked || isMainBoard(it.code) }
                    .sortedBy { it.code }
                    .take(10)

                if (candidates.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        statusTv.text = "❌ 板块「$sectorName」暂无成分股数据，请先拉取市场数据"
                        analyzeBtn.isEnabled = true; analyzeBtn.text = "🚀 分析"
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    pipelineProgressView.reset()
                    statusTv.text = "🧠 板块「$sectorName」共 ${candidates.size} 只成分股，逐个分析中..."
                }

                val results = mutableListOf<AnalysisResult>()
                for ((index, stock) in candidates.withIndex()) {
                    withContext(Dispatchers.Main) {
                        statusTv.text = "🧠 ($index/${candidates.size}) 正在分析 ${stock.name}(${stock.code})..."
                        pipelineProgressView.updateSteps(DeepAnalystEngine.stepsFor(5))
                    }
                    val r = try {
                        AgentOrchestrator(appCtx).analyzeStock(
                            stockCode = stock.code, stockName = stock.name,
                            mode = AnalysisMode.DEEP, useAgentFramework = true,
                            stepListener = stepListener, quantSignalsProvider = quantProvider
                        )
                    } catch (e: Exception) {
                        AnalysisResult(
                            stockCode = stock.code, stockName = stock.name,
                            mode = AnalysisMode.DEEP, success = false,
                            errorMessage = e.message
                        )
                    }
                    results.add(r)
                }

                // ── 汇总展示 ──
                withContext(Dispatchers.Main) {
                    analyzeBtn.isEnabled = true; analyzeBtn.text = "🚀 分析"
                    showSectorSummary(sectorName, results)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTv.text = "❌ 板块分析异常: ${e.message?.take(40)}"
                    analyzeBtn.isEnabled = true; analyzeBtn.text = "🚀 分析"
                }
            }
        }
    }

    /** 板块分析汇总报告 */
    private fun showSectorSummary(sectorName: String, results: List<AnalysisResult>) {
        val sb = StringBuilder()
        sb.appendLine("🔥 板块「$sectorName」成分股分析汇总")
        sb.appendLine("分析时间: ${java.time.LocalDateTime.now().toLocalDate()} | 共 ${results.size} 只")
        sb.appendLine()

        val successList = results.filter { it.success }
        val passedList = results.filter { it.passed == true }
        val failList = results.filter { !it.success }

        // Top 排行
        sb.appendLine("【推荐 Top 5】")
        successList.sortedByDescending { it.overallScore }.take(5).forEachIndexed { i, r ->
            val passedStr = if (r.passed == true) "通过" else "未通过"
            sb.appendLine("  ${i + 1}. ${r.stockName}(${r.stockCode}) ${r.overallScore}分/$passedStr")
            sb.appendLine("     建议: ${r.barrierLevel ?: r.recommendation ?: "—"}")
        }
        sb.appendLine()

        // 通过清单
        sb.appendLine("【通过六项严选】${passedList.size} 只")
        if (passedList.isNotEmpty()) {
            passedList.sortedByDescending { it.overallScore }.forEach { r ->
                sb.appendLine("  ✓ ${r.stockName}(${r.stockCode}) ${r.overallScore}分 · ${r.recommendation ?: "建议关注"}")
            }
        } else {
            sb.appendLine("  （无）")
        }
        sb.appendLine()

        // 失败清单
        if (failList.isNotEmpty()) {
            sb.appendLine("【分析失败】${failList.size} 只")
            failList.forEach { r ->
                sb.appendLine("  ✗ ${r.stockName}(${r.stockCode}) ${r.errorMessage?.take(40) ?: "未知错误"}")
            }
            sb.appendLine()
        }

        // 平均分
        if (successList.isNotEmpty()) {
            val avg = successList.map { it.overallScore }.average()
            sb.appendLine("📊 平均评分: %.1f / 100".format(avg))
        }
        statusTv.text = "✅ 板块「$sectorName」分析完成：${passedList.size}/${results.size} 通过"

        val scroll = ScrollView(requireContext()).apply { isVerticalScrollBarEnabled = true }
        scroll.addView(TextView(requireContext()).apply {
            text = sb.toString()
            setTextColor(Color.parseColor("#333333")); textSize = 12f
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setLineSpacing(2f, 1.1f); typeface = Typeface.MONOSPACE
        })
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("📊 板块分析汇总")
            .setView(scroll)
            .setPositiveButton("关闭") { d, _ -> d.dismiss() }
            .show()

        // 发布通过股票到跨 Tab 总线
        val picks = passedList.sortedByDescending { it.overallScore }.take(5).mapIndexed { i, r ->
            AIPredictionEngine.AIPick(
                stockCode = r.stockCode, stockName = r.stockName,
                compositeScore = r.overallScore,
                upProbability = if (r.overallScore >= 60) 75 else 50,
                rank = i + 1,
                reason = "板块分析($sectorName): ${r.barrierLevel ?: r.recommendation ?: "综合评估"}",
                actionSuggestion = "建议关注"
            )
        }
        if (picks.isNotEmpty()) CrossTabBus.postAiTopPicks(picks)
    }

    /** 判断是否主板（排除创业板300/301、科创板688、北交所bj） */
    private fun isMainBoard(code: String): Boolean =
        !(code.startsWith("sz300") || code.startsWith("sz301") || code.startsWith("sh688") || code.startsWith("bj"))
}
