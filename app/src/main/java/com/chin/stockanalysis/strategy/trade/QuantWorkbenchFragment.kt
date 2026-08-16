package com.chin.stockanalysis.strategy.trade

import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.stock.database.DataExportImport
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.HoldingPeriod
import com.chin.stockanalysis.strategy.backtest.StrategySelfTuner
import com.chin.stockanalysis.strategy.Strategy
import com.chin.stockanalysis.strategy.StrategyEngine
import com.chin.stockanalysis.strategy.StrategyEngineHolder
import com.chin.stockanalysis.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ## 量化工作台（P1/P2）
 *
 * 公共操作统一入口：周期多选 + 回溯 / 拟合 / 导入。
 *
 * - 回溯：对勾选周期逐周期执行历史回溯，结果按 periodKey 分别落库（strategy_trade_backtests）
 * - 自测拟合：对勾选周期逐周期执行增量梯度调优（StrategySelfTuner，目标准确率 90%，
 *   直接优化策略权重因子并落库 strategy_weight_snapshot，下次执行策略自动加载）
 * - 导入：JSON 数据一键导入（DataExportImport.importFromJson）
 * - AI 跨周期分析：汇总各周期回溯 + 拟合数据生成 prompt，跳转聊天页由 Agent 分析
 *
 * 所有操作按周期分别保存，供工作台统一对比展示。
 */
class QuantWorkbenchFragment : Fragment() {

    private data class PeriodInfo(
        val key: String, // 落库周期键：UltraShortQuant / ShortTermQuant / MidTermQuant / LongTermQuant
        val holdingPeriod: HoldingPeriod?, // null = 使用所有启用策略
        val label: String
    )

    companion object {
        private const val TAG = "QuantWorkbench"
        private val PERIODS = listOf(
            PeriodInfo("UltraShortQuant", HoldingPeriod.ULTRA_SHORT, "超短线"),
            PeriodInfo("ShortTermQuant", null, "短线"),
            PeriodInfo("MidTermQuant", null, "中线"),
            PeriodInfo("LongTermQuant", HoldingPeriod.LONG, "长线")
        )
    }

    private lateinit var rootLayout: LinearLayout
    private lateinit var statusTv: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var resultTv: TextView
    private val periodChecks = mutableMapOf<String, CheckBox>()

    /** JSON 导入文件选择器 */
    private val importPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { doImport(it) }
    }

    /** PC 拟合参数文件选择器（backtest_params.json 回传） */
    private val paramsPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { doImportParams(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        rootLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F6FA"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        buildUI()
        return rootLayout
    }

    override fun onResume() {
        super.onResume()
        refreshResults()
    }

    // ═══════════════════════════════════════════════════
    // UI 构建
    // ═══════════════════════════════════════════════════

    private fun buildUI() {
        rootLayout.addView(TextView(requireContext()).apply {
            text = "🧰 量化工作台"
            textSize = 16f
            setTextColor(Color.parseColor("#E65100"))
            setTypeface(typeface, Typeface.BOLD.toInt())
            setPadding(16, 16, 16, 4)
        })
        rootLayout.addView(TextView(requireContext()).apply {
            text = "勾选周期后执行公共操作，结果按周期分别落库、统一对比"
            textSize = 11f
            setTextColor(Color.parseColor("#888888"))
            setPadding(16, 0, 16, 8)
        })

        val periodRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 4, 12, 4)
        }
        for (p in PERIODS) {
            val cb = CheckBox(requireContext()).apply {
                text = p.label
                textSize = 12f
                isChecked = true
                setTextColor(Color.parseColor("#333333"))
            }
            periodChecks[p.key] = cb
            periodRow.addView(cb, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        rootLayout.addView(periodRow)

        val btnRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 8, 12, 8)
        }
        btnRow.addView(makeActionBtn("📈 回溯") { runBacktest() })
        btnRow.addView(makeActionBtn("🔧 拟合") { runFitting() })
        btnRow.addView(makeActionBtn("📥 导入") { importPicker.launch("application/json") })
        btnRow.addView(makeActionBtn("🤖 AI 分析") { runCrossPeriodAnalysis() })
        rootLayout.addView(btnRow)

        // ── AI 四周期选股：UnifiedStockClassifier 全量扫描（长线含 ml_prob KNN 排序）──
        val aiRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 0, 12, 8)
        }
        aiRow.addView(makeActionBtn("🧠 AI 选股") { runAiSelection() })
        rootLayout.addView(aiRow)
        rootLayout.addView(TextView(requireContext()).apply {
            text = "AI 选股：全量扫描四周期（超短/短/中/长），长线叠加 PC 端 KNN 模型 ml_prob 加权排序，结果写入「股票→🤖 AI 精选」"
            textSize = 10f
            setTextColor(Color.parseColor("#888888"))
            setPadding(16, 0, 16, 6)
        })

        // ── 四周期统一管理：一键切到对应周期页并触发建仓（DAG 管线，买卖评估自动套用 PC 拟合卖出参数）──
        val mgrRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 8, 12, 8)
        }
        mgrRow.addView(makeActionBtn("⚡ 超短建仓") { switchToPeriod(0, "build") })
        mgrRow.addView(makeActionBtn("⚡ 短线建仓") { switchToPeriod(1, "build") })
        mgrRow.addView(makeActionBtn("⚡ 中线建仓") { switchToPeriod(2, "build") })
        mgrRow.addView(makeActionBtn("⚡ 长线建仓") { switchToPeriod(3, "build") })
        rootLayout.addView(mgrRow)
        rootLayout.addView(TextView(requireContext()).apply {
            text = "四周期统一管理：切换到对应周期页并自动建仓；选股过滤/排序/卖出参数均套用 PC 端三年 walk-forward 拟合结果（backtest_params.json）"
            textSize = 10f
            setTextColor(Color.parseColor("#888888"))
            setPadding(16, 0, 16, 6)
        })

        // ── 多周期「回溯+拟合」引擎（超短隔日卖 / 短线连跌卖 / 中长线做T）──
        rootLayout.addView(TextView(requireContext()).apply {
            text = "── 多周期回溯 + 状态矩阵拟合（超短隔日/短线连跌/中长线做T） ──"
            textSize = 11f
            setTextColor(Color.parseColor("#2E7D32"))
            setPadding(16, 8, 16, 4)
            setTypeface(typeface, Typeface.BOLD.toInt())
        })
        val cycleRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 8, 12, 8)
        }
        cycleRow.addView(makeActionBtn("🔄 多周期回溯") { runFullCycleBacktest() })
        cycleRow.addView(makeActionBtn("📐 状态矩阵拟合") { runStateFit() })
        cycleRow.addView(makeActionBtn("📤 导出拟合") { exportFitMatrix() })
        cycleRow.addView(makeActionBtn("📋 选中记录") { showSelectedRecords() })
        rootLayout.addView(cycleRow)
        val dataRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 0, 12, 8)
        }
        dataRow.addView(makeActionBtn("📥 拉取2年历史") { fetchBacktestHistory() })
        dataRow.addView(makeActionBtn("🧬 参数导入") { paramsPicker.launch("application/json") })
        rootLayout.addView(dataRow)
        rootLayout.addView(TextView(requireContext()).apply {
            text = "增量回溯：已回溯区间不重复跑；中/长线选中记录长期保留，超短/短线仅保留30天"
            textSize = 10f
            setTextColor(Color.parseColor("#888888"))
            setPadding(16, 0, 16, 6)
        })

        progressBar = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            setPadding(16, 8, 16, 4)
        }
        rootLayout.addView(progressBar)
        statusTv = TextView(requireContext()).apply {
            text = ""
            textSize = 12f
            setTextColor(Color.parseColor("#666666"))
            setPadding(16, 0, 16, 8)
        }
        rootLayout.addView(statusTv)

        rootLayout.addView(TextView(requireContext()).apply {
            text = "── 最近回溯结果对比 ──"
            textSize = 12f
            setTextColor(Color.parseColor("#E65100"))
            setPadding(16, 8, 16, 4)
        })
        val scroll = ScrollView(requireContext()).apply {
            setBackgroundColor(Color.WHITE)
        }
        resultTv = TextView(requireContext()).apply {
            text = "暂无回溯数据\n\n点击「📈 回溯」按周期执行历史回溯测试，结果将自动落库。"
            textSize = 12f
            setTextColor(Color.parseColor("#444444"))
            setPadding(14, 10, 14, 10)
            setLineSpacing(3f, 1.15f)
            setTypeface(Typeface.MONOSPACE)
        }
        scroll.addView(resultTv)
        rootLayout.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
    }

    private fun makeActionBtn(text: String, onClick: () -> Unit): Button {
        val btn = Button(requireContext())
        btn.text = text
        btn.textSize = 12f
        btn.setTextColor(Color.WHITE)
        btn.setBackgroundColor(Color.parseColor("#E65100"))
        btn.isAllCaps = false
        btn.setPadding(4, 8, 4, 8)
        btn.setOnClickListener { onClick() }
        btn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(4, 0, 4, 0)
        }
        return btn
    }

    // ═══════════════════════════════════════════════════
    // 周期选择与引擎
    // ═══════════════════════════════════════════════════

    private fun getSelectedPeriods(): List<PeriodInfo> =
        PERIODS.filter { periodChecks[it.key]?.isChecked == true }

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

    // ═══════════════════════════════════════════════════
    // 公共操作：回溯 / 拟合 / 导入 / AI 分析
    // ═══════════════════════════════════════════════════

    /** 按勾选周期逐周期回溯并落库 */
    private fun runBacktest() {
        val selected = getSelectedPeriods()
        if (selected.isEmpty()) {
            Toast.makeText(requireContext(), "请先勾选至少一个周期", Toast.LENGTH_SHORT).show()
            return
        }
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

                    val today = java.time.LocalDate.now().toString()
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

    /** 按勾选周期逐周期自测拟合调优（StrategySelfTuner 增量梯度优化，目标准确率 90%） */
    private fun runFitting() {
        val selected = getSelectedPeriods()
        if (selected.isEmpty()) {
            Toast.makeText(requireContext(), "请先勾选至少一个周期", Toast.LENGTH_SHORT).show()
            return
        }
        val eng = getEngine()
        setBusy(true, "🎯 自测拟合(目标90%)中...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tuner = StrategySelfTuner(requireContext())
                val sb = StringBuilder()
                var totalTuned = 0
                for (p in selected) {
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
                    refreshResults()
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

    // ═══════════════════════════════════════════════════
    // 多周期「回溯+拟合」引擎
    // ═══════════════════════════════════════════════════

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

    /** 按大盘状态对中/长线网格拟合卖出参数，产出状态参数矩阵并落库（HoldingGuardNode 自动应用） */
    private fun runStateFit() {
        setBusy(true, "📐 状态矩阵拟合中...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ctx = requireContext()
                val sb = StringBuilder()
                for (period in listOf("中线", "长线")) {
                    try {
                        val res = com.chin.stockanalysis.strategy.backtest.FullCycleBacktestEngine.fitByState(ctx, period) { msg ->
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
                val root = org.json.JSONObject()
                root.put("exported_at", java.time.LocalDate.now().toString())
                root.put("source", "StockAnalysis APK 本机拟合导出 → PC walk-forward 素材")
                val sellRules = org.json.JSONObject()
                for (period in listOf("超短", "短线", "中线", "长线")) {
                    val json = db.backtestMetaDao().get("fit_matrix_$period") ?: continue
                    val matrix = org.json.JSONObject(json)
                    val periodObj = org.json.JSONObject()
                    val byState = org.json.JSONObject()
                    var defaultRule: org.json.JSONObject? = null
                    val keys = matrix.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val item = matrix.optJSONObject(k) ?: continue
                        val rule = org.json.JSONObject()
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
                val file = File(dir, "fit_matrix_${java.time.LocalDate.now()}.json")
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

    /**
     * 🧬 参数导入：PC 端更新后的 backtest_params.json（含最新 select_params/rank_factors/sell_rules）
     * 导入 APK → BacktestParamsLoader 写入 filesDir 并立即生效。这是「边买卖边完善 PC 拟合」闭环的回传入口。
     */
    private fun doImportParams(uri: android.net.Uri) {
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

    /** 🧠 AI 四周期选股：UnifiedStockClassifier 全量扫描（长线含 ml_prob KNN 加权），
     *  结果写入 ai_selected_stock → 股票 Tab → 🤖 AI 精选 查看 */
    private fun runAiSelection() {
        setBusy(true, "🧠 AI 四周期选股中（长线含 ML 排序）...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val classifier = UnifiedStockClassifier(requireContext())
                val scan = classifier.classifyAll()
                val saved = classifier.saveToAiSelection(scan)
                val sb = StringBuilder()
                sb.appendLine("扫描候选 ${scan.candidates.size} 只 | 命中 ${scan.classified.size} 条 | 写入 AI 精选 $saved 只")
                if (scan.dataDate != null) sb.appendLine("K线截止: ${scan.dataDate}")
                sb.appendLine("─".repeat(44))
                val titles = mapOf(
                    UnifiedStockClassifier.PERIOD_ULTRA_SHORT to "超短",
                    UnifiedStockClassifier.PERIOD_SHORT to "短线",
                    UnifiedStockClassifier.PERIOD_MID to "中线",
                    UnifiedStockClassifier.PERIOD_LONG to "长线"
                )
                for (p in UnifiedStockClassifier.ALL_PERIODS) {
                    val list = scan.byPeriod[p].orEmpty()
                    if (list.isEmpty()) {
                        sb.appendLine("【${titles[p]}】无命中")
                        continue
                    }
                    sb.appendLine("【${titles[p]}】${list.size} 只（Top ${minOf(5, list.size)}）:")
                    for (s in list.take(5)) {
                        if (p == UnifiedStockClassifier.PERIOD_LONG) {
                            val pv = MlKnnModel.probability(requireContext(), s.result)
                            sb.appendLine("  ${s.name}(${s.code.takeLast(6)}) ${"%.2f".format(s.price)}  ML=${if (pv == null) "无" else "%.1f%%".format(pv * 100)}")
                        } else {
                            sb.appendLine("  ${s.name}(${s.code.takeLast(6)}) ${"%.2f".format(s.price)}")
                        }
                    }
                }
                sb.appendLine("─".repeat(44))
                sb.appendLine("结果已写入 AI 精选 → 股票 Tab → 🤖 AI 精选 查看完整名单")
                withContext(Dispatchers.Main) {
                    setBusy(false, "✅ AI 选股完成")
                    showDialog("🧠 AI 四周期选股结果", sb.toString())
                }
            } catch (e: Exception) {
                Log.e(TAG, "AI 选股失败: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    setBusy(false, "❌ AI 选股失败")
                    Toast.makeText(requireContext(), "AI 选股失败: ${e.message?.take(60)}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** ⚡ 四周期统一管理：发跨Tab指令，切到对应周期页并触发建仓/选股（PC 拟合参数自动生效） */
    private fun switchToPeriod(period: Int, op: String) {
        com.chin.stockanalysis.ui.CrossTabBus.tryPostCommand(
            com.chin.stockanalysis.ui.CrossTabCommand(
                action = "SWITCH_PERIOD_TAB",
                extraParams = mapOf("period" to period.toString(), "op" to op)
            )
        )
        Toast.makeText(
            requireContext(),
            "已切换并触发${if (op == "build") "该周期建仓" else "该周期操作"}",
            Toast.LENGTH_SHORT
        ).show()
    }

    /** 拉取 2024 年至今的历史K线（回填一年回溯窗口 + MA250 回看） */
    private fun fetchBacktestHistory() {
        setBusy(true, "📥 拉取 2024 年至今历史K线...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val fetcher = com.chin.stockanalysis.strategy.data.HistoricalDataFetcher(requireContext())
                val count = fetcher.fetchAllHistoricalData(days = 550, force = true) { p ->
                    requireActivity().runOnUiThread {
                        statusTv.text = "📥 拉取中 ${p.completedStocks}/${p.totalStocks} 只 · ${p.totalRecords} 条 · ${p.currentStock}"
                    }
                }
                withContext(Dispatchers.Main) {
                    setBusy(false, "✅ 历史数据拉取完成：$count 条")
                    Toast.makeText(requireContext(), "历史数据已更新：$count 条，可执行回溯", Toast.LENGTH_LONG).show()
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
                    refreshResults()
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

    // ═══════════════════════════════════════════════════
    // P2: Agent 跨周期分析
    // ═══════════════════════════════════════════════════

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
                for (p in PERIODS) {
                    val items = db.strategyTradeBacktestDao().getByPeriod(p.key).take(5)
                    if (items.isEmpty()) {
                        sb.appendLine()
                        sb.appendLine("【${p.label}】暂无回溯数据（可在量化工作台执行回溯后重试）")
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

    // ═══════════════════════════════════════════════════
    // 结果展示
    // ═══════════════════════════════════════════════════

    /** 从库里读取各周期最近回溯结果，统一对比展示 */
    private fun refreshResults() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val sb = StringBuilder()
                var anyData = false
                for (p in PERIODS) {
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
