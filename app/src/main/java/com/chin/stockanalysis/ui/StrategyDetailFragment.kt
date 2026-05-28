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
import com.chin.stockanalysis.strategy.Strategy
import com.chin.stockanalysis.strategy.StrategySource
import com.chin.stockanalysis.strategy.models.WeightFactor

/**
 * ## 策略详情 / 编辑页（BottomSheet）
 *
 * 支持编辑策略名称、描述、权重因子。
 */
class StrategyDetailFragment : BottomSheetDialogFragment() {

    var strategy: Strategy? = null
    var onSave: ((Strategy) -> Unit)? = null

    private lateinit var nameEdit: EditText
    private lateinit var descEdit: EditText
    private lateinit var weightContainer: LinearLayout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 48)
            setBackgroundColor(Color.WHITE)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        val s = strategy ?: return root

        // title
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }
        val categoryTv = TextView(ctx).apply {
            text = s.category.icon; textSize = 24f
        }
        titleRow.addView(categoryTv)
        val sourceBadge = TextView(ctx).apply {
            text = s.source.label; textSize = 11f; setTextColor(Color.WHITE)
            setPadding(10, 3, 10, 3)
            setBackgroundColor(if (s.source == StrategySource.BUILTIN) Color.parseColor("#4CAF50") else Color.parseColor("#FF9800"))
        }
        titleRow.addView(sourceBadge)
        root.addView(titleRow)

        // name
        root.addView(label("策略名称"))
        nameEdit = editText(s.name)
        root.addView(nameEdit)

        // desc
        root.addView(label("策略描述"))
        descEdit = editText(s.description)
        root.addView(descEdit)

        // weight section
        root.addView(sectionDivider())
        val wLabel = TextView(ctx).apply {
            text = "权重配置 (合计应为 100%)"
            textSize = 15f; setTextColor(Color.parseColor("#333333"))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 12, 0, 12)
        }
        root.addView(wLabel)

        weightContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        buildWeightRows(s.weightFactors)
        root.addView(weightContainer)

        // summary bar
        val sumText = TextView(ctx).apply {
            text = weightSummary(s.weightFactors)
            textSize = 12f; setTextColor(Color.parseColor("#E65100"))
            setPadding(0, 8, 0, 8)
            tag = "sum_text"
        }
        root.addView(sumText)

        // add factor button
        val addFactorBtn = Button(ctx).apply {
            text = "+ 添加因子"
            textSize = 13f
            setBackgroundColor(Color.parseColor("#1565C0"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                addWeightRow("new_factor", "新因子", 0)
                updateWeightSummary()
            }
        }
        root.addView(addFactorBtn, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = 8
        })

        // save
        root.addView(sectionDivider())
        val saveBtn = Button(ctx).apply {
            text = "💾 保存修改"
            textSize = 15f
            setBackgroundColor(Color.parseColor("#E65100"))
            setTextColor(Color.WHITE)
            setPadding(32, 16, 32, 16)
            setOnClickListener { saveAndDismiss() }
        }
        root.addView(saveBtn, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = 16
        })

        return root
    }

    private fun buildWeightRows(factors: List<WeightFactor>) {
        weightContainer.removeAllViews()
        for ((i, f) in factors.withIndex()) {
            addWeightRow(f.key, f.label, f.weight)
        }
    }

    private fun addWeightRow(key: String, label: String, weight: Int) {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4, 0, 4)
            tag = key
        }

        val labelEdit = EditText(ctx).apply {
            setText(label); setSingleLine(); textSize = 13f
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 2f).apply { marginEnd = 8 }
        }
        row.addView(labelEdit)

        val weightEdit = EditText(ctx).apply {
            setText(weight.toString()); setSingleLine(); textSize = 13f
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8 }
        }
        row.addView(weightEdit)

        val sb = SeekBar(ctx).apply {
            max = 100; progress = weight
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 3f).apply { marginEnd = 8 }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    weightEdit.setText(progress.toString())
                    if (fromUser) updateWeightSummary()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        }
        row.addView(sb)

        val delBtn = Button(ctx).apply {
            text = "✕"; textSize = 14f
            setBackgroundColor(Color.parseColor("#E53935")); setTextColor(Color.WHITE)
            setPadding(12, 4, 12, 4)
            setOnClickListener {
                weightContainer.removeView(row)
                updateWeightSummary()
            }
        }
        row.addView(delBtn)
        weightContainer.addView(row)
    }

    private fun updateWeightSummary() {
        val sumView = view?.findViewWithTag<TextView>("sum_text") ?: return
        val factors = collectWeightFactors()
        val sum = factors.sumOf { it.weight }
        val color = if (sum != 100) Color.parseColor("#E53935") else Color.parseColor("#2E7D32")
        sumView.text = "合计: $sum%  ${if (sum != 100) "⚠️ 不等于100%！" else "✅"}"
        sumView.setTextColor(color)
    }

    private fun collectWeightFactors(): List<WeightFactor> {
        val result = mutableListOf<WeightFactor>()
        for (i in 0 until weightContainer.childCount) {
            val row = weightContainer.getChildAt(i) as? LinearLayout ?: continue
            val labelEdit = row.getChildAt(0) as? EditText ?: continue
            val weightEdit = row.getChildAt(1) as? EditText ?: continue
            val key = row.tag as? String ?: "factor_$i"
            val label = labelEdit.text.toString().trim().ifEmpty { "因子$i" }
            val weight = weightEdit.text.toString().toIntOrNull() ?: 0
            result.add(WeightFactor(key, label, weight.coerceIn(0, 100), ""))
        }
        return result
    }

    private fun weightSummary(factors: List<WeightFactor>): String {
        val sum = factors.sumOf { it.weight }
        return "合计: $sum%  ${if (sum != 100) "⚠️ 不等于100%！" else "✅"}"
    }

    private fun saveAndDismiss() {
        val s = strategy ?: return
        val updated = object : Strategy {
            override val id = s.id
            override var name = nameEdit.text.toString().trim().ifEmpty { s.name }
            override var description = descEdit.text.toString().trim().ifEmpty { s.description }
            override val category = s.category
            override val config = s.config
            override var weightFactors = collectWeightFactors()
            override val source = s.source
            override suspend fun screen() = s.screen()
            override suspend fun isAvailable() = s.isAvailable()
        }
        onSave?.invoke(updated)
        dismiss()
    }

    private fun label(text: String) = TextView(requireContext()).apply {
        this.text = text; textSize = 13f; setTextColor(Color.parseColor("#888888"))
        setPadding(0, 12, 0, 4)
    }

    private fun editText(text: String) = EditText(requireContext()).apply {
        setText(text); setSingleLine(); textSize = 14f
        setTextColor(Color.parseColor("#333333"))
        setBackgroundColor(Color.parseColor("#F5F5F5"))
        setPadding(16, 12, 16, 12)
    }

    private fun sectionDivider() = View(requireContext()).apply {
        setBackgroundColor(Color.parseColor("#EEEEEE"))
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 2).apply {
            topMargin = 16; bottomMargin = 8
        }
    }

    companion object {
        fun newInstance(strategyId: String): StrategyDetailFragment {
            return StrategyDetailFragment().apply {
                arguments = Bundle().apply { putString("strategy_id", strategyId) }
            }
        }
    }
}