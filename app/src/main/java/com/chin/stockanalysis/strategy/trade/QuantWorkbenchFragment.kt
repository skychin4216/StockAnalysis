package com.chin.stockanalysis.strategy.trade

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.chin.stockanalysis.strategy.HoldingPeriod
import com.chin.stockanalysis.ui.TradingDayPickerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek

/**
 * ## 我的工作台
 *
 * 顶级「量化选股」Tab 0，与「策略」「数据」「AI 分析」平级。
 * 短/中/长 三周期 + 实仓 已集成于此（顶部页签内嵌），独占整屏展示。
 * 2026-09-05 机构化收敛：超短引擎保留但结果并入短线（⚡极速档），不再独立设页。
 *
 * - 周期页：顶部页签切换，直接复用三周期 Fragment 与实仓页（建仓/Pipeline/持仓/卖出评估/回溯/报告）
 * - 标题行：一键建仓（交易时间跑三周期买入订单 DAG pipeline（含腾笼换鸟）；
 *   非交易时间直接三周期选股保存到 股票Tab→精选股票→AI 精选）
 * - 状态矩阵拟合 / 拟合参数导入 / PC 候选 / PC 拟合参数 / 回溯 & 分析 / 自测拟合 等
 *   公共操作已整合到「量化选股 → 数据」Tab（StrategyImportFragment）
 */
class QuantWorkbenchFragment : Fragment() {

    private data class PeriodInfo(
        val key: String, // 落库周期键：UltraShortQuant / ShortTermQuant / MidTermQuant / LongTermQuant
        val holdingPeriod: HoldingPeriod?, // null = 使用所有启用策略
        val label: String
    )

    companion object {
        private const val TAG = "QuantWorkbench"
        // 2026-09-05 收敛为三档：超短引擎保留，命中并入短线（推送侧 flash 标注）
        private val PERIODS = listOf(
            PeriodInfo("ShortTermQuant", null, "短线"),
            PeriodInfo("MidTermQuant", null, "中线"),
            PeriodInfo("LongTermQuant", HoldingPeriod.LONG, "长线")
        )
    }

    private lateinit var rootLayout: LinearLayout
    private lateinit var periodTabLayout: TabLayout
    private lateinit var periodPager: ViewPager2

    // ── 公共配置（交易日 / 仅主板 / 周期 已上移到工作台顶部统一管理）──
    // 公共参数统一存放在 QuantWorkbenchState（共享单例，相当于各周期页的 base 级公共参数），
    // 周期页执行操作时直接读取最新值，本页只负责写入 + 刷新公共行 UI，无需逐个推送。
    private var currentTab: Int = 0
    private lateinit var dateLabelTv: TextView
    private lateinit var periodTipTv: TextView
    private lateinit var periodRow: LinearLayout
    private lateinit var periodLabelTv: TextView
    private lateinit var periodRadioGroup: RadioGroup

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
        refreshAll()
    }

    // ═══════════════════════════════════════════════════
    // UI 构建
    // ═══════════════════════════════════════════════════

    private fun buildUI() {
        // ── 标题行：仅保留一键建仓（状态矩阵拟合 / 拟合参数导入 / PC 候选 已整合到 量化选股→数据 Tab）──
        rootLayout.addView(LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(12, 10, 12, 4)
            addView(Button(requireContext()).apply {
                text = "🚀 一键建仓"; textSize = 11f; setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#E65100")); isAllCaps = false
                setPadding(2, 6, 2, 6)
                setOnClickListener { runQuickBuild() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
        // ── 公共「交易日」行：日期选择 + 仅主板 + 当前周期提示（随 Tab 切换）──
        rootLayout.addView(LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 4, 8, 4)
            setBackgroundColor(Color.WHITE)
            addView(TextView(requireContext()).apply {
                text = "📅 交易日:"; textSize = 12f
                setTextColor(Color.parseColor("#333333"))
                setTypeface(typeface, Typeface.BOLD)
            }.also { dateLabelTv = it })
            addView(TradingDayPickerView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = 4; marginEnd = 6 }
                // 初始显示共享状态中的日期；写入共享状态后所有周期页直接读到最新值
                selectedDate = QuantWorkbenchState.tradeDate
                onDateChanged = { d ->
                    QuantWorkbenchState.tradeDate = d
                    val isNonTrading = d.dayOfWeek == DayOfWeek.SATURDAY ||
                        d.dayOfWeek == DayOfWeek.SUNDAY || d in TradingDayPickerView.CHINESE_HOLIDAYS
                    dateLabelTv.text = if (isNonTrading) "📅 非交易日:" else "📅 交易日:"
                }
            })
            addView(Switch(requireContext()).apply {
                text = "仅主板"; textSize = 11f; isChecked = QuantWorkbenchState.mainBoardOnly
                setTextColor(Color.parseColor("#333333"))
                setOnCheckedChangeListener { _, checked ->
                    QuantWorkbenchState.mainBoardOnly = checked
                }
            })
            addView(TextView(requireContext()).apply {
                textSize = 10f
                setTextColor(Color.parseColor("#E65100"))
                setPadding(8, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }.also { periodTipTv = it })
        })

        // ── 公共「周期」行：选项随当前 Tab 切换（超短/长线无周期选择时隐藏）──
        rootLayout.addView(LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 2, 8, 4)
            setBackgroundColor(Color.WHITE)
            addView(TextView(requireContext()).apply {
                textSize = 11f
                setTextColor(Color.parseColor("#333333"))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 4, 0)
            }.also { periodLabelTv = it })
            addView(RadioGroup(requireContext()).apply {
                orientation = RadioGroup.HORIZONTAL
            }.also { periodRadioGroup = it }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }.also { periodRow = it })

        // ── 周期集成区：内嵌 短/中/长 三周期页 + 实仓（复用周期 Fragment）──
        // 公共参数走共享状态，周期页创建后自行读取，无需推送；
        // 生命周期回调仅负责在首个周期 Fragment 创建时初始化公共「周期」行 UI。
        childFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
                super.onFragmentViewCreated(fm, f, v, savedInstanceState)
                if (f is QuantFragmentBase) {
                    val pos = (f.tag ?: "").removePrefix("f").toIntOrNull() ?: -1
                    if (pos == currentTab) updateCommonRowsForTab(pos, f)
                }
            }
        }, false)
        periodTabLayout = TabLayout(requireContext()).apply {
            setSelectedTabIndicatorColor(Color.parseColor("#E65100"))
            setTabTextColors(Color.parseColor("#999999"), Color.parseColor("#E65100"))
            setBackgroundColor(Color.WHITE)
            elevation = 2f
            tabMode = TabLayout.MODE_FIXED
        }
        rootLayout.addView(periodTabLayout)
        periodPager = ViewPager2(requireContext()).apply {
            adapter = PeriodTabAdapter(this@QuantWorkbenchFragment)
            offscreenPageLimit = 2
        }
        rootLayout.addView(periodPager, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        TabLayoutMediator(periodTabLayout, periodPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(com.chin.stockanalysis.R.string.tab_short)
                1 -> getString(com.chin.stockanalysis.R.string.tab_mid)
                2 -> getString(com.chin.stockanalysis.R.string.tab_long)
                3 -> "💰 实仓"
                4 -> "🧲 ETF"
                else -> throw IllegalStateException("period tab count mismatch: $position")
            }
        }.attach()
        periodPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                currentTab = position
                childFragmentManager.executePendingTransactions()
                val frag = childFragmentManager.findFragmentByTag("f$position") as? QuantFragmentBase
                updateCommonRowsForTab(position, frag)
                frag?.refreshPositions()
            }
        })
    }

    /** 根据当前 Tab 刷新公共「交易日」行提示 + 公共「周期」行选项/选中态（选项/选中态均来自共享状态） */
    private fun updateCommonRowsForTab(position: Int, frag: QuantFragmentBase?) {
        periodTipTv.text = frag?.getPeriodTipText() ?: ""
        val options = frag?.getPeriodOptions().orEmpty()
        if (options.isEmpty()) {
            periodRow.visibility = View.GONE
            return
        }
        periodRow.visibility = View.VISIBLE
        periodLabelTv.text = frag?.getPeriodRowLabel() ?: "📊 周期:"
        // 重建周期选项，避免旧周期残留
        periodRadioGroup.removeAllViews()
        val selected = frag?.getSelectedPeriod() ?: -1
        for ((period, label) in options) {
            val rb = RadioButton(requireContext()).apply {
                text = label; textSize = 11f; id = period
                isChecked = period == selected
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) frag?.applySelectedPeriod(period)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = -4; marginStart = -4 }
            }
            periodRadioGroup.addView(rb)
        }
    }

    // ═══════════════════════════════════════════════════
    // 公共操作（回溯 / 拟合 / 导入 / 参数 / AI 分析）已全部移到 量化→数据
    // ═══════════════════════════════════════════════════



    /** 🧠 AI 四周期选股：UnifiedStockClassifier 全量扫描（长线含 ml_prob KNN 加权），
     *  结果写入 ai_selected_stock → 股票 Tab → 🤖 AI 精选 查看 */
    private fun runAiSelection() {
        Toast.makeText(
            requireContext(),
            "🧠 AI 四周期选股中（长线含 ML 排序），完成后弹窗展示结果...",
            Toast.LENGTH_LONG
        ).show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val classifier = UnifiedStockClassifier(requireContext())
                // 选股前先刷新候选池今日实时行情（避免使用上次同步如 13:00 的旧价）
                val cands = classifier.collectCandidates()
                val refreshed = com.chin.stockanalysis.strategy.data.HistoricalDataFetcher(requireContext())
                    .refreshTodayRealtime(cands.map { it.code })
                Log.i(TAG, "AI 选股前刷新今日实时行情: $refreshed 只")
                val scan = classifier.classifyAll(cands)
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
                    showDialog("🧠 AI 四周期选股结果", sb.toString())
                }
            } catch (e: Exception) {
                Log.e(TAG, "AI 选股失败: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "AI 选股失败: ${e.message?.take(60)}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** 🚀 一键建仓（交易/非交易同走三周期 DAG pipeline，仅落库行为区分）：
     *  - 交易时间内：DAG 建仓 pipeline = 买入订单 + 持仓合并 + 腾笼换鸟，拟合参数自动生效
     *  - 非交易时间：同样走 DAG pipeline（saveAsAiOnly），跳过 买入订单/持仓合并/腾笼换鸟/拟合，
     *    仅将选股结果写入 股票Tab → 精选股票 → AI 精选，弹窗标题区分
     *  2026-09-05：超短并入短线（极速档引擎保留，不再独立建仓入口）
     */
    private fun runQuickBuild() {
        // 2026-09-06：一键建仓不再弹 Toast；各周期执行完成后统一弹「聚合结果窗」。
        if (QuantWorkbenchState.quickBuildActive) {
            Log.w(TAG, "一键建仓仍在执行中，忽略重复点击")
            return
        }
        val isTrading = com.chin.stockanalysis.stock.database.ChinaMarketTradingHours.a股是否交易中()
        val selected = PERIODS
        // 批量会话：三周期（短线/中线/长线）Pipeline 全部完成后回调聚合窗口
        QuantWorkbenchState.startQuickBuild(3)
        QuantWorkbenchState.quickBuildAllDone = { showQuickBuildAggregateWindow() }
        // 超时兜底：个别周期异常未上报时，8 分钟后按已完成周期提前汇总
        lifecycleScope.launch {
            kotlinx.coroutines.delay(8 * 60 * 1000L)
            if (QuantWorkbenchState.quickBuildActive) {
                Log.w(TAG, "一键建仓超时（${QuantWorkbenchState.quickBuildDone}/${QuantWorkbenchState.quickBuildTotal}），提前汇总")
                QuantWorkbenchState.finishQuickBuild()
                showQuickBuildAggregateWindow()
            }
        }
        // 立即给已创建的周期页反馈"指令已接收"（不等行情刷新，写日志不弹 Toast）：
        // ViewPager2 懒加载,远处页此刻可能尚未创建,创建后由 runDagPipeline 自带日志接力,衔接无缝。
        childFragmentManager.executePendingTransactions()
        childFragmentManager.fragments.filterIsInstance<QuantFragmentBase>().forEach { it.onQuickBuildReceived() }
        lifecycleScope.launch(Dispatchers.IO) {
            // 建仓前先刷新核心池今日实时行情（避免使用上次同步如 13:00 的旧价）
            try {
                val stocks = com.chin.stockanalysis.strategy.data.HistoricalDataFetcher.getTopStocks(requireContext())
                val refreshed = com.chin.stockanalysis.strategy.data.HistoricalDataFetcher(requireContext())
                    .refreshTodayRealtime(stocks)
                Log.i(TAG, "一键建仓前刷新今日实时行情: $refreshed 只")
            } catch (e: Exception) {
                Log.w(TAG, "一键建仓前刷新行情失败: ${e.message}")
            }

            // ① 市场公共研判只执行一次（市场研判 + 选股公共数据准备），结果播种给三周期并行 pipeline
            var commonOutputs: Map<String, Any?> = emptyMap()
            val commonLatch = java.util.concurrent.CountDownLatch(1)
            val firstFrag = childFragmentManager.findFragmentByTag("f0") as? QuantFragmentBase
            if (firstFrag != null) {
                firstFrag.runCommonPipeline { outputs ->
                    commonOutputs = outputs
                    commonLatch.countDown()
                }
                commonLatch.await(3, java.util.concurrent.TimeUnit.MINUTES)
            } else {
                Log.w(TAG, "一键建仓: 短线页未就绪，跳过公共研判，各周期回退完整流程")
            }
            val useCommon = commonOutputs.isNotEmpty()

            // ② 激活中间页（页1=中线），offscreenPageLimit=2 保证三页全部创建并存活
            withContext(Dispatchers.Main) {
                periodPager.setCurrentItem(1, false)
                childFragmentManager.executePendingTransactions()
            }

            // ③ 三周期并行触发（周期专属 pipeline + 公共研判播种；公共失败则回退完整 usecase 串行）
            val periodUseCaseIds = arrayOf("short_term_period", "mid_term_period", "long_term_period")
            withContext(Dispatchers.Main) {
                var triggered = 0
                selected.forEach { p ->
                    val periodIdx = PERIODS.indexOf(p)
                    if (periodIdx < 0) return@forEach
                    if (useCommon) {
                        runOnPeriodTab(
                            periodIdx, "build", saveAsAiOnly = !isTrading,
                            useCaseId = periodUseCaseIds[periodIdx],
                            seedStageOutputs = commonOutputs
                        )
                    } else {
                        runOnPeriodTab(periodIdx, "build", saveAsAiOnly = !isTrading)
                    }
                    triggered++
                }
                // 结果统一由「三周期聚合结果窗」展示，不再弹 Toast
                Log.i(TAG, "一键建仓已触发 $triggered 个周期（${if (useCommon) "公共研判已完成" else "公共研判失败已回退完整流程"}），等待全部完成...")
            }
        }
    }

    /**
     * 供 StrategyFragment 转发跨 Tab 指令（周期操作已集成到工作台内部）：
     * - EXECUTE_SIMULATE_TRADE：切中线并执行买卖评估（T+1 卖出模拟）
     * - RUN_PIPELINE：切短线并执行 DAG 建仓管线
     * - SWITCH_PERIOD_TAB：切到指定周期页并触发 op（build / simulate / pipeline / refresh）
     *   index 语义（三档收敛后）：0=短线 1=中线 2=长线
     */
    fun handleExternalCommand(action: String, period: Int?, op: String?) {
        when (action) {
            "EXECUTE_SIMULATE_TRADE" -> {
                periodPager.setCurrentItem(1, true)  // 中线
                runOnPeriodTab(1, "simulate")
            }
            "RUN_PIPELINE" -> {
                periodPager.setCurrentItem(0, true)  // 短线
                runOnPeriodTab(0, "pipeline")
            }
            "SWITCH_PERIOD_TAB" -> {
                val p = period ?: 0
                if (p !in 0..2) return
                periodPager.setCurrentItem(p, true)
                runOnPeriodTab(p, op ?: "refresh")
            }
            else -> { /* ignore */ }
        }
    }

    /** 刷新内嵌周期持仓：短/中/长 + 实仓 + ETF（供外层 Tab 切换 / onResume 时调用） */
    fun refreshAll() {
        childFragmentManager.executePendingTransactions()
        for (i in 0 until 5) {
            when (val f = childFragmentManager.findFragmentByTag("f$i")) {
                is QuantFragmentBase -> f.refreshPositions()
                is EtfDipFragment -> f.refresh()
                else -> Unit
            }
        }
    }

    /**
     * 切换内嵌周期页并等待目标周期 fragment 就绪后执行操作。
     * ViewPager2 的 setCurrentItem(smoothScroll=true) 是异步的，目标 fragment 在滚动过程中才创建，
     * 因此用延迟重试（每 150ms，最长约 1.8s）确保周期模块就绪后再触发建仓/选股/回溯。
     */
    private fun runOnPeriodTab(
        period: Int,
        op: String,
        attempt: Int = 0,
        saveAsAiOnly: Boolean = false,
        useCaseId: String? = null,
        seedStageOutputs: Map<String, Any?> = emptyMap()
    ) {
        childFragmentManager.executePendingTransactions()
        val frag = childFragmentManager.findFragmentByTag("f$period") as? QuantFragmentBase
        if (frag != null) {
            when (op) {
                "simulate" -> (frag as? MidTermQuantFragment)?.autoExecuteTrade()
                "pipeline" -> (frag as? ShortTermQuantFragment)?.autoRunPipeline()
                "build" -> {
                    if (useCaseId != null) {
                        // 一键建仓并行：公共研判已统一执行，周期专属 pipeline 直接播种执行
                        frag.runParallelBuild(useCaseId, saveAsAiOnly, seedStageOutputs)
                    } else {
                        frag.autoRunPipeline(saveAsAiOnly)
                    }
                }
                else -> frag.refreshPositions()
            }
            return
        }
        if (attempt >= 12) {
            Log.w(TAG, "周期 $period 模块未就绪(op=$op)")
            Toast.makeText(requireContext(), "周期模块未就绪，请稍后重试", Toast.LENGTH_SHORT).show()
            return
        }
        periodPager.postDelayed(
            { runOnPeriodTab(period, op, attempt + 1, saveAsAiOnly, useCaseId, seedStageOutputs) },
            150L
        )
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

    // ═══════════════════════════════════════════════════
    // 🚀 一键建仓 · 三周期聚合结果窗（2026-09-06）
    // ═══════════════════════════════════════════════════

    private data class QuickBuildRow(
        val code: String,        // 6 位数字代码
        val name: String,
        val score: Int,          // 综合评分（买入订单 scoreAtBuy）
        val price: Double,
        val changePct: Double,
        val detail: String       // MACD/RSI/KDJ/MA/量比 摘要
    )

    /** 三周期全部执行完后聚合并展示可关闭结果窗 */
    private fun showQuickBuildAggregateWindow() {
        if (!isAdded) return
        val ctx = requireContext()
        val snapshot = LinkedHashMap<String, List<Triple<String, String, Int>>>()
        QuantWorkbenchState.quickBuildPicks.forEach { (k, v) -> snapshot[k] = v }
        lifecycleScope.launch(Dispatchers.IO) {
            val sections = buildQuickBuildSections(ctx, snapshot)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                showQuickBuildResultDialog(sections)
            }
        }
    }

    /** IO 线程计算各周期股票的指标摘要 */
    private suspend fun buildQuickBuildSections(
        ctx: android.content.Context,
        picks: Map<String, List<Triple<String, String, Int>>>
    ): List<Pair<String, List<QuickBuildRow>>> {
        val order = listOf("短线", "中线", "长线")
        val db = try { com.chin.stockanalysis.stock.database.StockDatabase.getInstance(ctx) } catch (_: Exception) { null }
        val dao = db?.dailySnapshotDao()
        return order.mapNotNull { label ->
            val list = picks[label].orEmpty()
            val rows = mutableListOf<QuickBuildRow>()
            for ((rawCode, name, score) in list) {
                val secid = if (rawCode.length == 6) {
                    when (rawCode.first()) {
                        '6', '9' -> "sh$rawCode"
                        '8', '4' -> "bj$rawCode"
                        else -> "sz$rawCode"
                    }
                } else rawCode
                val code6 = if (secid.length > 6) secid.takeLast(6) else secid
                val snaps = try { dao?.getByCode(secid).orEmpty().sortedBy { it.date } } catch (_: Exception) { emptyList() }
                if (snaps.size < 5) {
                    rows.add(QuickBuildRow(code6, name, score, 0.0, 0.0, "暂无K线数据"))
                } else {
                    val closes = snaps.map { it.close }
                    val highs = snaps.map { it.high }
                    val lows = snaps.map { it.low }
                    val vols = snaps.map { it.volume }
                    val last = snaps.last()
                    val prev = snaps.getOrNull(snaps.size - 2)
                    val chg = if (last.changePct != 0.0) last.changePct
                    else if (prev != null && prev.close > 0) (last.close - prev.close) / prev.close * 100
                    else 0.0
                    val (dif, dea, bar) = com.chin.stockanalysis.strategy.backtest.MathIndicators.macd(closes)
                    val rsi = com.chin.stockanalysis.strategy.backtest.MathIndicators.rsi(closes)
                    val (k, d, j) = com.chin.stockanalysis.strategy.backtest.MathIndicators.kdj(highs, lows, closes)
                    val trend = com.chin.stockanalysis.strategy.backtest.MathIndicators.maTrend(closes).trend
                    val vr = com.chin.stockanalysis.strategy.backtest.MathIndicators.volumeRatio(vols)
                    val barSign = if (bar > 0) "+" else ""
                    val detail = "MACD ${"%.2f".format(dif)}/${"%.2f".format(dea)}/${barSign}${"%.2f".format(bar)}  " +
                        "RSI ${"%.1f".format(rsi)}  KDJ ${"%.0f".format(k)}/${"%.0f".format(d)}/${"%.0f".format(j)}  " +
                        trend + "  量比 ${"%.1f".format(vr)}"
                    rows.add(QuickBuildRow(code6, name, score, last.close, chg, detail))
                }
            }
            label to rows
        }
    }

    /** 弹出可关闭的三周期聚合结果窗（全屏可滚动） */
    private fun showQuickBuildResultDialog(sections: List<Pair<String, List<QuickBuildRow>>>) {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val total = sections.sumOf { it.second.size }
        val isTrading = com.chin.stockanalysis.stock.database.ChinaMarketTradingHours.a股是否交易中()
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
        }
        content.addView(TextView(ctx).apply {
            text = "已完成 短/中/长 三周期一键建仓，共选中 $total 只" +
                (if (isTrading) "（已生成订单并入账）" else "（已保存 AI 精选）")
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#E65100"))
            setPadding(0, 0, 0, (8 * density).toInt())
        })
        sections.forEach { (label, rows) ->
            content.addView(TextView(ctx).apply {
                text = "【$label】${rows.size} 只"
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#1A237E"))
                setPadding(0, (10 * density).toInt(), 0, (4 * density).toInt())
            })
            if (rows.isEmpty()) {
                content.addView(TextView(ctx).apply {
                    text = "   本轮未选中"
                    textSize = 12f
                    setTextColor(Color.parseColor("#999999"))
                })
                return@forEach
            }
            rows.forEachIndexed { i, r ->
                val priceColor = when {
                    r.changePct > 0 -> "#E53935"
                    r.changePct < 0 -> "#43A047"
                    else -> "#666666"
                }
                content.addView(TextView(ctx).apply {
                    text = "  ${i + 1}. ${r.name}(${r.code})  评分 ${r.score}"
                    textSize = 13f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#333333"))
                    setPadding(0, (3 * density).toInt(), 0, 0)
                })
                val priceText = if (r.price > 0) {
                    "  现价 ${"%.2f".format(r.price)}  " +
                        "${if (r.changePct > 0) "+" else ""}${"%.2f".format(r.changePct)}%"
                } else ""
                content.addView(TextView(ctx).apply {
                    text = (priceText + "  ${r.detail}").trim()
                    textSize = 11f
                    setTextColor(Color.parseColor(priceColor))
                    setPadding((14 * density).toInt(), 0, 0, (2 * density).toInt())
                    setLineSpacing(2f, 1.1f)
                })
            }
        }
        val sv = ScrollView(ctx).apply {
            isFillViewport = true
            addView(content)
        }
        AlertDialog.Builder(ctx)
            .setTitle("🚀 一键建仓 · 三周期选股结果")
            .setView(sv)
            .setPositiveButton("关闭", null)
            .create()
            .apply {
                show()
                window?.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
            }
    }

    /** 内嵌周期页适配器：短 / 中 / 长 / 实仓 / ETF（超短引擎并入短线，2026-09-05） */
    private class PeriodTabAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount() = 5

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> ShortTermQuantFragment()
                1 -> MidTermQuantFragment()
                2 -> LongTermQuantFragment()
                3 -> RealHoldingQuantFragment()
                4 -> EtfDipFragment()
                else -> throw IllegalStateException("Unknown position: $position")
            }
        }
    }
}
