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
 * 超短/短/中/长 四周期 + 实仓 + 选股系统 已集成于此（顶部页签内嵌），独占整屏展示。
 *
 * - 周期页：顶部页签切换，直接复用四周期 Fragment 与实仓页（建仓/Pipeline/持仓/卖出评估/回溯/报告）
 * - 标题行：一键建仓（交易时间跑四周期买入订单 DAG pipeline（含腾笼换鸟）；
 *   非交易时间直接四周期选股保存到 股票Tab→精选股票→AI 精选）
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
        private val PERIODS = listOf(
            PeriodInfo("UltraShortQuant", HoldingPeriod.ULTRA_SHORT, "超短线"),
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

        // ── 周期集成区：内嵌超短/短/中/长 四周期页（复用周期 Fragment）──
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
                0 -> getString(com.chin.stockanalysis.R.string.tab_ultra_short)
                1 -> getString(com.chin.stockanalysis.R.string.tab_short)
                2 -> getString(com.chin.stockanalysis.R.string.tab_mid)
                3 -> getString(com.chin.stockanalysis.R.string.tab_long)
                4 -> "💰 实仓"
                5 -> "🧭 选股系统"
                else -> ""
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

    /** 🚀 一键建仓（交易/非交易同走四周期 DAG pipeline，仅落库行为区分）：
     *  - 交易时间内：DAG 建仓 pipeline = 买入订单 + 持仓合并 + 腾笼换鸟，拟合参数自动生效
     *  - 非交易时间：同样走 DAG pipeline（saveAsAiOnly），跳过 买入订单/持仓合并/腾笼换鸟/拟合，
     *    仅将选股结果写入 股票Tab → 精选股票 → AI 精选，弹窗标题区分
     */
    private fun runQuickBuild() {
        val isTrading = com.chin.stockanalysis.stock.database.ChinaMarketTradingHours.a股是否交易中()
        val selected = PERIODS
        // 立即反馈，避免点击后无响应（前置刷新行情可能耗时数秒）
        Toast.makeText(
            requireContext(),
            if (isTrading) "🚀 一键建仓启动：刷新行情 + 四周期并行建仓中，请稍候..."
            else "🚀 一键建仓启动：刷新行情 + 四周期选股（仅保存 AI 精选）中，请稍候...",
            Toast.LENGTH_SHORT
        ).show()
        // 立即给已创建的周期页反馈"指令已接收"（不等行情刷新）：
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

            // ① 市场公共研判只执行一次（市场研判 + 选股公共数据准备），结果播种给四周期并行 pipeline
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
                Log.w(TAG, "一键建仓: 超短周期页未就绪，跳过公共研判，各周期回退完整流程")
            }
            val useCommon = commonOutputs.isNotEmpty()

            // ② 激活中间页（页1），offscreenPageLimit=2 保证四页全部创建并存活
            withContext(Dispatchers.Main) {
                periodPager.setCurrentItem(1, false)
                childFragmentManager.executePendingTransactions()
            }

            // ③ 四周期并行触发（周期专属 pipeline + 公共研判播种；公共失败则回退完整 usecase 串行）
            val periodUseCaseIds = arrayOf("ultra_short_period", "short_term_period", "mid_term_period", "long_term_period")
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
                Toast.makeText(
                    requireContext(),
                    if (isTrading)
                        "一键建仓已全部触发（$triggered 个周期${if (useCommon) "，公共研判已完成" else "，公共研判失败已回退完整流程"}）"
                    else
                        "非交易时间选股已触发（$triggered 个周期${if (useCommon) "，已保存 AI 精选" else "，公共研判失败已回退完整流程"}）",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * 供 StrategyFragment 转发跨 Tab 指令（周期操作已集成到工作台内部）：
     * - EXECUTE_SIMULATE_TRADE：切中线并执行买卖评估（T+1 卖出模拟）
     * - RUN_PIPELINE：切短线并执行 DAG 建仓管线
     * - SWITCH_PERIOD_TAB：切到指定周期页并触发 op（build / simulate / pipeline / refresh）
     */
    fun handleExternalCommand(action: String, period: Int?, op: String?) {
        when (action) {
            "EXECUTE_SIMULATE_TRADE" -> {
                periodPager.setCurrentItem(2, true)  // 中线
                runOnPeriodTab(2, "simulate")
            }
            "RUN_PIPELINE" -> {
                periodPager.setCurrentItem(1, true)  // 短线
                runOnPeriodTab(1, "pipeline")
            }
            "SWITCH_PERIOD_TAB" -> {
                val p = period ?: 0
                if (p !in 0..3) return
                periodPager.setCurrentItem(p, true)
                runOnPeriodTab(p, op ?: "refresh")
            }
            else -> { /* ignore */ }
        }
    }

    /** 刷新内嵌四周期持仓（供外层 Tab 切换 / onResume 时调用） */
    fun refreshAll() {
        childFragmentManager.executePendingTransactions()
        for (i in 0 until 4) {
            (childFragmentManager.findFragmentByTag("f$i") as? QuantFragmentBase)?.refreshPositions()
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

    /** 内嵌周期页适配器：超短 / 短 / 中 / 长 / 实仓 / 选股系统 */
    private class PeriodTabAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount() = 6

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> UltraShortQuantFragment()
                1 -> ShortTermQuantFragment()
                2 -> MidTermQuantFragment()
                3 -> LongTermQuantFragment()
                4 -> RealHoldingQuantFragment()
                5 -> StockSelectionSystemFragment()
                else -> throw IllegalStateException("Unknown position: $position")
            }
        }
    }
}
