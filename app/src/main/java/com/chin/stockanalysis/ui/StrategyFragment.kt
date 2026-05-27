package com.chin.stockanalysis.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.widget.LinearLayout.LayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.stock.data.MultiSourceStockRepository
import com.chin.stockanalysis.stock.data.StockDataSourceFactory
import com.chin.stockanalysis.strategy.StrategyEngine
import com.chin.stockanalysis.strategy.data.StockScreener
import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.strategies.LowValuationStrategy
import com.chin.stockanalysis.strategy.strategies.MovingAverageStrategy
import com.chin.stockanalysis.strategy.strategies.VolumeBreakStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ## 策略中心 — 量化选股策略
 *
 * 集成了 [StrategyEngine] 和三种内置策略：
 * - 均线金叉策略
 * - 放量突破策略
 * - 低估值策略
 *
 * 支持策略扫描、查看结果、启停策略。
 */
class StrategyFragment : Fragment() {

    private lateinit var layout: LinearLayout
    private lateinit var statusTv: TextView
    private lateinit var resultContainer: LinearLayout
    private lateinit var scanBtn: Button
    private lateinit var progressBar: ProgressBar

    private var engine: StrategyEngine? = null
    private var repository: MultiSourceStockRepository? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            setBackgroundColor(Color.parseColor("#FAFAFA"))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        initEngine()
        buildUI()

        return layout
    }

    private fun initEngine() {
        val ctx = requireContext().applicationContext
        repository = StockDataSourceFactory.createDefaultRepository(ctx)
        val screener = StockScreener(repository!!)
        engine = StrategyEngine(ctx, screener).apply {
            registerStrategy(MovingAverageStrategy(screener))
            registerStrategy(VolumeBreakStrategy(screener))
            registerStrategy(LowValuationStrategy(screener))
        }
    }

    private fun buildUI() {
        // ── 标题 ──
        val titleTv = TextView(requireContext()).apply {
            text = "🎯 量化选股策略"
            textSize = 22f
            setTextColor(Color.parseColor("#333333"))
            setPadding(0, 0, 0, 8)
        }
        layout.addView(titleTv)

        val subtitleTv = TextView(requireContext()).apply {
            text = "均线金叉 · 放量突破 · 低估值 · 更多策略持续添加中"
            textSize = 13f
            setTextColor(Color.parseColor("#999999"))
            setPadding(0, 0, 0, 20)
        }
        layout.addView(subtitleTv)

        // ── 扫描按钮 ──
        scanBtn = Button(requireContext()).apply {
            text = "▶ 开始选股扫描"
            textSize = 16f
            setBackgroundColor(Color.parseColor("#E65100"))
            setTextColor(Color.WHITE)
            setPadding(32, 16, 32, 16)
            setOnClickListener { runStrategies() }
        }
        val btnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 16 }
        layout.addView(scanBtn, btnParams)

        // ── 进度条 ──
        progressBar = ProgressBar(requireContext()).apply {
            visibility = View.GONE
        }
        layout.addView(progressBar)

        // ── 状态文本 ──
        statusTv = TextView(requireContext()).apply {
            text = "已加载 3 个策略，点击按钮开始扫描"
            textSize = 14f
            setTextColor(Color.parseColor("#666666"))
            setPadding(0, 8, 0, 16)
        }
        layout.addView(statusTv)

        // ── 结果容器（可滚动） ──
        val scrollView = ScrollView(requireContext()).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        }
        resultContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(resultContainer)
        layout.addView(scrollView)
    }

    private fun runStrategies() {
        val eng = engine ?: return

        scanBtn.isEnabled = false
        scanBtn.text = "⏳ 扫描中..."
        progressBar.visibility = View.VISIBLE
        statusTv.text = "正在从东方财富获取市场数据并执行策略扫描..."
        resultContainer.removeAllViews()

        eng.runAll(lifecycleScope,
            onProgress = { result ->
                lifecycleScope.launch(Dispatchers.Main) {
                    addResultCard(result)
                }
            },
            onComplete = { results ->
                lifecycleScope.launch(Dispatchers.Main) {
                    scanBtn.isEnabled = true
                    scanBtn.text = "▶ 重新扫描"
                    progressBar.visibility = View.GONE
                    statusTv.text = buildString {
                        append("扫描完成 — ")
                        val totalHits = results.sumOf { it.hitCount }
                        val totalScanned = results.firstOrNull()?.totalScanned ?: 0
                        append("$totalScanned 只股票中命中 $totalHits 只")
                        append("\n")
                        results.forEach { r ->
                            append("  ${r.strategyName}: 命中 ${r.hitCount} 只 | ${r.scanTimeMs}ms")
                            append("\n")
                        }
                    }
                }
            }
        )
    }

    private fun addResultCard(result: ScreeningResult) {
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            setBackgroundColor(Color.WHITE)
            val margin = 12
            (layoutParams as? LinearLayout.LayoutParams)?.setMargins(0, margin, 0, margin)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            elevation = 4f
        }

        // 标题行
        val titleRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val iconTv = TextView(requireContext()).apply {
            text = result.category.icon
            textSize = 18f
        }
        titleRow.addView(iconTv)

        val nameTv = TextView(requireContext()).apply {
            text = "  ${result.strategyName}"
            textSize = 16f
            setTextColor(Color.parseColor("#333333"))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        titleRow.addView(nameTv)

        card.addView(titleRow)

        // 统计行
        val statsTv = TextView(requireContext()).apply {
            text = "扫描 ${result.totalScanned} 只 | 命中 ${result.hitCount} 只 | 命中率 ${String.format("%.1f", result.hitRate * 100)}% | ${result.scanTimeMs}ms"
            textSize = 12f
            setTextColor(Color.parseColor("#999999"))
            setPadding(0, 4, 0, 8)
        }
        card.addView(statsTv)

        // 分隔线
        val divider = View(requireContext()).apply {
            setBackgroundColor(Color.parseColor("#EEEEEE"))
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 2)
        }
        card.addView(divider)

        // 信号列表（Top 5）
        val topSignals = result.topN(5)
        if (topSignals.isEmpty()) {
            val emptyTv = TextView(requireContext()).apply {
                text = "  ⚪ 未命中符合条件的股票"
                textSize = 13f
                setTextColor(Color.parseColor("#AAAAAA"))
                setPadding(0, 8, 0, 0)
            }
            card.addView(emptyTv)
        } else {
            for (signal in topSignals) {
                val signalRow = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 6, 0, 0)
                }

                val emojiTv = TextView(requireContext()).apply {
                    text = signal.emoji
                    textSize = 16f
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { rightMargin = 4 }
                }
                signalRow.addView(emojiTv)

                val detailTv = TextView(requireContext()).apply {
                    text = buildString {
                        append("${signal.stockName}(${signal.stockCode})  ")
                        append("强度:${signal.strength}%  ")
                        append("¥${String.format("%.2f", signal.currentPrice)}  ")
                        append("${String.format("%+.2f", signal.changePercent)}%")
                    }
                    textSize = 12f
                    setTextColor(Color.parseColor("#555555"))
                }
                signalRow.addView(detailTv)

                card.addView(signalRow)

                // 理由
                val reasonTv = TextView(requireContext()).apply {
                    text = "     ${signal.reason}"
                    textSize = 11f
                    setTextColor(Color.parseColor("#888888"))
                    setPadding(24, 2, 0, 0)
                }
                card.addView(reasonTv)
            }

            if (result.hitCount > 5) {
                val moreTv = TextView(requireContext()).apply {
                    text = "  ... 还有 ${result.hitCount - 5} 只"
                    textSize = 12f
                    setTextColor(Color.parseColor("#BBBBBB"))
                    setPadding(0, 6, 0, 0)
                }
                card.addView(moreTv)
            }
        }

        resultContainer.addView(card)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        engine?.cancelScan()
    }
}