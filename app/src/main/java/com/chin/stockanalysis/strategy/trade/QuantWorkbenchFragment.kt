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
