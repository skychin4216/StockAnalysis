package com.chin.stockanalysis.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.widget.LinearLayout.LayoutParams
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.models.StrategySignal

/**
 * ## 策略结果对话框（BottomSheet）
 *
 * 用户点击策略扫描结果 → 弹出此对话框：
 * - 上半部分：策略名称 + 参数 + CSV 表格展示结果
 * - 下半部分：输入框 + AI 追问（后续可接入 ChatTabFragment 逻辑）
 */
class StrategyResultDialogFragment : BottomSheetDialogFragment() {

    var result: ScreeningResult? = null
    var onAskQuestion: ((String) -> Unit)? = null

    private lateinit var rootLayout: LinearLayout
    private lateinit var inputEdit: EditText

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        rootLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 48)
            setBackgroundColor(Color.WHITE)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        val r = result ?: return rootLayout
        buildUI(r)
        return rootLayout
    }

    private fun buildUI(r: ScreeningResult) {
        val ctx = requireContext()

        // 标题
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }
        titleRow.addView(TextView(ctx).apply {
            text = "🎯 ${r.strategyName}"
            textSize = 20f
            setTextColor(Color.parseColor("#1A1A2E"))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(TextView(ctx).apply {
            text = "命中 ${r.hitCount}/${r.totalScanned}"
            textSize = 12f
            setTextColor(Color.parseColor("#999999"))
        })
        rootLayout.addView(titleRow)

        // 摘要
        val summaryTv = TextView(ctx).apply {
            text = "扫描: ${r.totalScanned}只 | 命中: ${r.hitCount}只 | 耗时: ${r.scanTimeMs}ms"
            textSize = 12f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 0, 0, 16)
        }
        rootLayout.addView(summaryTv)

        // CSV 表格
        if (r.signals.isNotEmpty()) {
            rootLayout.addView(createCsvTable(r.signals))
        } else {
            rootLayout.addView(TextView(ctx).apply {
                text = "无命中信号"
                textSize = 14f
                setTextColor(Color.GRAY)
                setPadding(0, 16, 0, 16)
            })
        }

        // 分隔线
        rootLayout.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#EEEEEE"))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 2).apply {
                setMargins(0, 16, 0, 16)
            }
        })

        // 追问区域标题
        rootLayout.addView(TextView(ctx).apply {
            text = "💬 基于结果继续提问"
            textSize = 14f
            setTextColor(Color.parseColor("#333333"))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        })

        // 输入 + 发送
        val inputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#F5F6FA"))
            setPadding(8, 8, 8, 8)
        }
        inputEdit = EditText(ctx).apply {
            hint = "例如: 这些股票中哪个最值得关注？"
            textSize = 14f
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8 }
        }
        inputRow.addView(inputEdit)
        val sendBtn = Button(ctx).apply {
            text = "发送"
            textSize = 13f
            setBackgroundColor(Color.parseColor("#1565C0"))
            setTextColor(Color.WHITE)
            setPadding(16, 8, 16, 8)
            setOnClickListener {
                val q = inputEdit.text.toString().trim()
                if (q.isNotBlank()) {
                    onAskQuestion?.invoke(q)
                    inputEdit.setText("")
                    Toast.makeText(ctx, "✅ 已发送追问 (完整AI对话将在后续版本接入)", Toast.LENGTH_SHORT).show()
                }
            }
        }
        inputRow.addView(sendBtn)
        rootLayout.addView(inputRow)
    }

    /**
     * 构建 CSV 风格的表格视图
     * 列：序号 | 股票名称 | 股票代码 | 信号强度 | 涨跌幅 | 评分
     */
    private fun createCsvTable(signals: List<StrategySignal>): LinearLayout {
        val ctx = requireContext()
        val table = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F8F9FC"))
            setPadding(4, 4, 4, 4)
        }

        // 表头
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#E3F2FD"))
            setPadding(4, 8, 4, 8)
        }
        val headers = listOf("#", "名称", "代码", "强度%", "涨跌%", "评分")
        val weights = listOf(0.8f, 2.5f, 1.5f, 1.0f, 1.0f, 0.8f)
        for ((i, h) in headers.withIndex()) {
            header.addView(TextView(ctx).apply {
                text = h
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#1565C0"))
                gravity = Gravity.CENTER
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, weights[i])
            })
        }
        table.addView(header)

        // 数据行
        for ((idx, s) in signals.take(15).withIndex()) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(4, 6, 4, 6)
                if (idx % 2 == 1) setBackgroundColor(Color.parseColor("#F5F5F5"))
            }
            row.addView(cell("${idx + 1}", weights[0], Color.GRAY))
            row.addView(cell(s.stockName, weights[1], Color.parseColor("#333333")))
            row.addView(cell(s.stockCode.takeLast(6), weights[2], Color.parseColor("#666666")))
            row.addView(cell("${s.strength}%", weights[3],
                if (s.strength >= 70) Color.parseColor("#E53935") else Color.parseColor("#FF9800")))
            row.addView(cell(
                if (s.changePercent != 0.0)
                    "${if (s.changePercent > 0) "+" else ""}${"%.2f".format(s.changePercent)}%"
                else "—",
                weights[4],
                if (s.changePercent > 0) Color.parseColor("#E53935") else Color.parseColor("#43A047")
            ))
            val score = (s.strength * (1.0 + s.changePercent / 100.0).coerceAtLeast(0.0) / 10.0)
            row.addView(cell("${"%.1f".format(score)}", weights[5], Color.parseColor("#1565C0")))
            table.addView(row)
        }

        return table
    }

    private fun cell(text: String, weight: Float, color: Int): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            textSize = 11f
            setTextColor(color)
            gravity = Gravity.CENTER
            setSingleLine(true)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, weight)
        }
    }
}