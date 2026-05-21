package com.chin.stockanalysis.ui

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * 策略 Tab - 占位页（后续实现量化策略、选股模板等功能）
 */
class StrategyFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setGravity(Gravity.CENTER)
            setBackgroundColor(android.graphics.Color.parseColor("#FAFAFA"))
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
        }

        val tv = TextView(requireContext()).apply {
            text = "🎯 策略中心\n\n量化选股策略\n金融模型分析\n\n即将上线，敬请期待"
            textSize = 16f
            setGravity(Gravity.CENTER)
            setTextColor(android.graphics.Color.parseColor("#888888"))
            setLineSpacing(0f, 1.6f)
        }

        layout.addView(tv)
        return layout
    }
}
