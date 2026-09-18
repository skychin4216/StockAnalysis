package com.chin.stockanalysis.strategy.trade

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.strategy.topology.xml.UseCaseExecution
import com.chin.stockanalysis.strategy.topology.xml.UseCaseLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * ## 选股系统 Tab（工作台第 5 个页签）
 *
 * 集中展示可运行的选股 UseCase（豆包体系"完整闭环交易系统"排第一），
 * 点击即可执行：进度实时显示 → 结果弹窗（买入信号 / 错误 / 耗时）。
 * 后续可在 assets/usecases/ 下新增 _usecase.xml 扩展更多选股方案。
 */
class StockSelectionSystemFragment : Fragment() {

    private val tag = "StockSelectionSystem"
    private lateinit var progressDialog: AlertDialog
    private lateinit var progressText: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F6FA"))
        }
        // 标题
        root.addView(TextView(requireContext()).apply {
            text = "🧭 选股系统"
            textSize = 16f
            setTextColor(Color.parseColor("#1A1A2E"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(16), dp(14), dp(16), dp(2))
            setBackgroundColor(Color.WHITE)
        })
        root.addView(TextView(requireContext()).apply {
            text = "内置「完整闭环交易系统」（豆包体系）等选股方案，点击即可运行；后续方案在此 Tab 持续扩展。"
            textSize = 12f
            setTextColor(Color.parseColor("#888888"))
            setPadding(dp(16), 0, dp(16), dp(8))
            setBackgroundColor(Color.WHITE)
        })
        // 方案列表
        val list = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(requireContext()).apply { isFillViewport = true }
        scroll.addView(list, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val infos = UseCaseLoader.listUseCases()
            .filter { it.id != "common" && !it.id.endsWith("_period") }
            .sortedBy { if (it.id == "complete_closed_loop") 0 else 1 }
        if (infos.isEmpty()) {
            list.addView(TextView(requireContext()).apply {
                text = "暂无可用选股方案（UseCaseLoader 未初始化）"
                textSize = 13f
                setTextColor(Color.parseColor("#999999"))
                setPadding(dp(16), dp(24), dp(16), dp(24))
                gravity = Gravity.CENTER
            })
        } else {
            infos.forEach { list.addView(buildCard(it)) }
        }
        return root
    }

    /** 构建单个选股方案卡片 */
    private fun buildCard(info: UseCaseLoader.UseCaseInfo): View {
        val highlight = info.id == "complete_closed_loop"
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (highlight) Color.parseColor("#FFF8E1") else Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(12), dp(8), dp(12), 0) }
        }
        card.addView(TextView(requireContext()).apply {
            text = (if (highlight) "🌟 " else "📊 ") + info.name + (if (highlight) "（豆包体系）" else "")
            textSize = 14f
            setTextColor(Color.parseColor("#1A1A2E"))
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        card.addView(TextView(requireContext()).apply {
            text = info.description
            textSize = 11f
            setTextColor(Color.parseColor("#666666"))
            setPadding(0, dp(4), 0, dp(2))
        })
        card.addView(TextView(requireContext()).apply {
            text = "📚 ${info.stepCount} 步流程 · id=${info.id}"
            textSize = 10f
            setTextColor(Color.parseColor("#999999"))
        })
        card.addView(Button(requireContext()).apply {
            text = "🚀 运行此方案"
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(if (highlight) "#E65100" else "#1E88E5"))
            setOnClickListener { runUseCase(info) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(40)
            ).apply { topMargin = dp(6) }
        })
        return card
    }

    /** 执行选股 UseCase：IO 线程运行 + 进度弹窗 + 结果汇总（统一走 UseCaseExecution 入口） */
    private fun runUseCase(info: UseCaseLoader.UseCaseInfo) {
        val ctx = requireContext()
        showProgress(ctx, "正在初始化 ${info.name}...")
        lifecycleScope.launch(Dispatchers.IO) {
            val report = UseCaseExecution.runAndSummarize(ctx, info.id) { pipelineName, nodeName ->
                showProgressMsg("执行中：$pipelineName → $nodeName")
            }
            dismissProgress()
            showResult(ctx, info.name, report)
        }
    }

    // ═══════════ UI 辅助 ═══════════

    private fun showProgress(ctx: Context, msg: String) {
        requireActivity().runOnUiThread {
            try {
                progressDialog?.dismiss()
            } catch (e: Exception) { /* ignore */ }
            progressText = TextView(ctx).apply {
                text = msg
                textSize = 14f
                setPadding(dp(24), dp(20), dp(24), dp(20))
            }
            progressDialog = AlertDialog.Builder(ctx)
                .setTitle("🧭 选股系统")
                .setView(progressText)
                .setCancelable(false)
                .create()
            progressDialog.show()
        }
    }

    private fun showProgressMsg(msg: String) {
        requireActivity().runOnUiThread {
            try { progressText.text = msg } catch (e: Exception) { /* ignore */ }
        }
    }

    private fun dismissProgress() {
        requireActivity().runOnUiThread {
            try { progressDialog?.dismiss() } catch (e: Exception) { /* ignore */ }
        }
    }

    private fun showResult(ctx: Context, title: String, message: String) {
        requireActivity().runOnUiThread {
            val sv = ScrollView(ctx).apply {
                addView(TextView(ctx).apply {
                    text = message
                    textSize = 13f
                    setTextColor(Color.parseColor("#333333"))
                    setPadding(dp(20), dp(16), dp(20), dp(16))
                })
            }
            AlertDialog.Builder(ctx)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density + 0.5f).toInt()
}
