package com.chin.stockanalysis.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.widget.*
import android.widget.LinearLayout.LayoutParams

/**
 * 实体确认卡片
 *
 * 当 StockEntityExtractor 匹配到多个候选时，在对话区域显示此卡片，
 * 让用户选择正确的股票/板块。
 *
 * 使用方式：
 * ```kotlin
 * val card = EntityConfirmCard(context).apply {
 *     setTitle("找到多个匹配")
 *     setSubtitle("您要分析哪个？")
 *     setCandidates(listOf(
 *         EntityConfirmCard.Candidate("兆易创新", "603986", "半导体设计"),
 *         EntityConfirmCard.Candidate("兆易创新", "603986", "MCU芯片"),
 *     ))
 *     onConfirm = { selected -> /* 用户选择了 selected */ }
 *     onCancel = { /* 用户取消 */ }
 * }
 * ```
 */
class EntityConfirmCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    data class Candidate(
        val displayName: String,  // 显示名称
        val code: String,          // 股票代码
        val description: String   // 描述（板块/行业）
    )

    var onConfirm: ((Candidate) -> Unit)? = null
    var onCancel: (() -> Unit)? = null

    private val titleTv: TextView
    private val subtitleTv: TextView
    private val candidateContainer: LinearLayout
    private val confirmBtn: Button
    private val cancelBtn: Button
    private val radioGroup = android.widget.RadioGroup(context)
    private var candidates = listOf<Candidate>()
    private var selectedIndex = -1

    init {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setBackgroundColor(Color.WHITE)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

        // 标题
        titleTv = TextView(context).apply {
            text = "找到多个匹配"; textSize = 16f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A1A2E"))
        }
        addView(titleTv)

        // 副标题
        subtitleTv = TextView(context).apply {
            text = "您要分析哪个？"; textSize = 13f
            setTextColor(Color.parseColor("#666666"))
            setPadding(0, dp(4), 0, dp(8))
        }
        addView(subtitleTv)

        // 候选列表容器
        candidateContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        addView(candidateContainer)

        // 按钮行
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.END
            setPadding(0, dp(12), 0, 0)
        }
        confirmBtn = Button(context).apply {
            text = "确认"; textSize = 13f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1565C0"))
            setPadding(dp(16), dp(6), dp(16), dp(6)); setMinWidth(0); setMinimumWidth(0)
            setOnClickListener { confirm() }
        }
        btnRow.addView(confirmBtn)
        cancelBtn = Button(context).apply {
            text = "取消"; textSize = 13f; setTextColor(Color.parseColor("#1565C0"))
            setBackgroundColor(Color.TRANSPARENT); setPadding(dp(16), dp(6), dp(16), dp(6)); setMinWidth(0); setMinimumWidth(0)
            setOnClickListener { cancel() }
        }
        btnRow.addView(cancelBtn)
        addView(btnRow)
    }

    fun setTitle(title: String) { titleTv.text = title }
    fun setSubtitle(subtitle: String) { subtitleTv.text = subtitle }

    fun setCandidates(candidates: List<Candidate>) {
        this.candidates = candidates
        candidateContainer.removeAllViews()
        radioGroup.removeAllViews()
        candidates.forEachIndexed { idx, c ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(6), dp(4), dp(6))
                setBackgroundResource(android.R.drawable.list_selector_background)
            }
            val rb = android.widget.RadioButton(context).apply {
                id = android.view.View.generateViewId()
                text = "${c.displayName} (${c.code})"
                textSize = 14f; setTextColor(Color.parseColor("#222222"))
                setTypeface(null, Typeface.BOLD)
            }
            radioGroup.addView(rb)
            row.addView(rb)

            val descTv = TextView(context).apply {
                text = c.description; textSize = 12f
                setTextColor(Color.parseColor("#888888"))
                setPadding(0, 0, 0, 0)
            }
            row.addView(descTv)
            candidateContainer.addView(row)
        }
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            selectedIndex = radioGroup.indexOfChild(radioGroup.findViewById(checkedId))
        }
    }

    fun getSelectedCandidate(): Candidate? {
        return if (selectedIndex in candidates.indices) candidates[selectedIndex] else null
    }

    private fun confirm() {
        val selected = getSelectedCandidate()
        if (selected != null) onConfirm?.invoke(selected)
    }

    private fun cancel() { onCancel?.invoke() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}
