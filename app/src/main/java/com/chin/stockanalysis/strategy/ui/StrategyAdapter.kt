package com.chin.stockanalysis.strategy.ui

import android.graphics.Color
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import com.chin.stockanalysis.strategy.Strategy
import com.chin.stockanalysis.strategy.StrategySource

class StrategyAdapter(
    private var items: List<Strategy>,
    private val onItemClick: (Strategy) -> Unit,
    private val onToggle: (Strategy) -> Unit
) : RecyclerView.Adapter<StrategyAdapter.VH>() {

    fun update(newItems: List<Strategy>) {
        items = newItems.sortedWith(compareBy<Strategy> {
            if (it.source == StrategySource.BUILTIN) 0 else 1
        }.thenBy { it.name })
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, vt: Int): VH = VH(createCard(parent))
    override fun onBindViewHolder(h: VH, i: Int) = h.bind(items[i], onItemClick, onToggle)
    override fun getItemCount() = items.size

    private fun createCard(parent: ViewGroup): LinearLayout {
        val ctx = parent.context
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(20, 16, 20, 16)
            elevation = 4f
            val margin = 12
            (layoutParams as? RecyclerView.LayoutParams)?.setMargins(margin, margin, margin, 0)
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    inner class VH(val card: LinearLayout) : RecyclerView.ViewHolder(card) {
        fun bind(strategy: Strategy, onClick: (Strategy) -> Unit, onToggle: (Strategy) -> Unit) {
            card.removeAllViews()
            val ctx = card.context

            // header row: icon + name + source badge + switch
            val header = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val icon = TextView(ctx).apply {
                text = strategy.category.icon; textSize = 20f
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = 8 }
            }
            header.addView(icon)
            val nameTv = TextView(ctx).apply {
                text = strategy.name; textSize = 16f; setTextColor(Color.parseColor("#222222"))
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            header.addView(nameTv)
            val badge = TextView(ctx).apply {
                text = strategy.source.label; textSize = 10f; setTextColor(Color.WHITE)
                setBackgroundColor(if (strategy.source == StrategySource.BUILTIN) Color.parseColor("#4CAF50") else Color.parseColor("#FF9800"))
                setPadding(8, 2, 8, 2); gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = 8 }
            }
            header.addView(badge)
            val sw = androidx.appcompat.widget.SwitchCompat(ctx).apply {
                isChecked = true; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                setOnCheckedChangeListener { _, _ -> onToggle(strategy) }
                // hide for now - TODO: wire with engine setEnabled
            }
            header.addView(sw)
            card.addView(header)

            // description
            val desc = TextView(ctx).apply {
                text = strategy.description; textSize = 12f; setTextColor(Color.parseColor("#888888"))
                setPadding(28, 6, 0, 4); maxLines = 2
            }
            card.addView(desc)

            // weight factors preview
            if (strategy.weightFactors.isNotEmpty()) {
                val wtext = strategy.weightFactors.joinToString("  ") { "${it.label} ${it.weight}%" }
                val wpreview = TextView(ctx).apply {
                    text = wtext; textSize = 11f; setTextColor(Color.parseColor("#AAAAAA"))
                    setPadding(28, 0, 0, 6)
                }
                card.addView(wpreview)
            }

            card.setOnClickListener { onClick(strategy) }
        }
    }
}