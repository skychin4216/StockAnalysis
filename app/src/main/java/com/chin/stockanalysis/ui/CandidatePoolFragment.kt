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
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.strategy.data.CandidatePool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ## 備選池 Fragment
 *
 * 顯示 CandidatePool 中的股票列表，支持僅主板過濾。
 * 支持：刷新、點擊查看詳情
 */
class CandidatePoolFragment : Fragment() {

    private lateinit var container: LinearLayout
    private lateinit var refreshBtn: TextView
    private lateinit var countTv: TextView
    private lateinit var updateTimeTv: TextView
    private lateinit var mainBoardSwitch: Switch

    private var currentSnapshot: CandidatePool.PoolSnapshot? = null
    private var showOnlyMainBoard: Boolean = true

    override fun onCreateView(
        inflater: LayoutInflater, containerView: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val root = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        val outer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F6FA"))
            setPadding(16, 16, 16, 80)
        }
        root.addView(outer)

        // ── 標題欄 ──
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(TextView(ctx).apply {
            text = "備選池"
            textSize = 18f
            setTextColor(Color.parseColor("#1A1A2E"))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })

        // 僅主板 Switch
        val switchRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        switchRow.addView(TextView(ctx).apply {
            text = "僅主板"
            textSize = 13f
            setTextColor(Color.parseColor("#333333"))
            setPadding(0, 0, 8, 0)
        })
        mainBoardSwitch = Switch(ctx).apply {
            isChecked = true
            setOnCheckedChangeListener { _, isChecked ->
                showOnlyMainBoard = isChecked
                renderList()
            }
        }
        switchRow.addView(mainBoardSwitch)
        headerRow.addView(switchRow)

        refreshBtn = TextView(ctx).apply {
            text = "刷新"
            textSize = 13f
            setTextColor(Color.parseColor("#1565C0"))
            setPadding(12, 6, 12, 6)
            setOnClickListener { loadPool(true) }
        }
        headerRow.addView(refreshBtn)
        outer.addView(headerRow)

        // ── 統計信息 ──
        val statsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 12)
        }
        countTv = TextView(ctx).apply {
            text = "加載中..."
            textSize = 12f
            setTextColor(Color.parseColor("#666666"))
        }
        statsRow.addView(countTv)
        updateTimeTv = TextView(ctx).apply {
            text = ""
            textSize = 11f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(12, 0, 0, 0)
        }
        statsRow.addView(updateTimeTv)
        outer.addView(statsRow)

        // ── 說明文字 ──
        outer.addView(TextView(ctx).apply {
            text = "核心龍頭 + ETF熱門板塊 + 東方財富熱門板塊，去重後約100-200只主板股票"
            textSize = 11f
            setTextColor(Color.parseColor("#999999"))
            setPadding(0, 0, 0, 12)
        })

        // ── 股票列表容器 ──
        container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        outer.addView(container)

        loadPool(false)
        return root
    }

    private fun loadPool(forceRefresh: Boolean) {
        lifecycleScope.launch {
            refreshBtn.isEnabled = false
            refreshBtn.text = "刷新中..."
            container.removeAllViews()

            try {
                val snapshot = withContext(Dispatchers.IO) {
                    CandidatePool.getPool(requireContext(), forceRefresh)
                }
                currentSnapshot = snapshot
                renderList()

            } catch (e: Exception) {
                container.addView(TextView(requireContext()).apply {
                    text = "加載失敗: ${e.message}"
                    textSize = 14f
                    setTextColor(Color.parseColor("#E53935"))
                    setPadding(0, 20, 0, 20)
                })
            }

            refreshBtn.isEnabled = true
            refreshBtn.text = "刷新"
        }
    }

    private fun renderList() {
        val snapshot = currentSnapshot ?: return
        val ctx = requireContext()
        container.removeAllViews()

        val allStocks = snapshot.stocks
        val filteredStocks = if (showOnlyMainBoard) {
            allStocks.filter { isMainBoard(it.code) }
        } else {
            allStocks
        }

        val mainBoardCount = allStocks.count { isMainBoard(it.code) }
        val kcCyCount = allStocks.size - mainBoardCount

        countTv.text = "主板 ${mainBoardCount} 只 / 科創/創業 ${kcCyCount} 只"
        updateTimeTv.text = "更新: ${snapshot.updateTime}"

        // 統一列表，按漲跌幅排序
        filteredStocks.sortedByDescending { it.changePct }.forEach { stock ->
            addStockRow(stock)
        }
    }

    private fun addStockRow(stock: CandidatePool.CandidateStock) {
        val ctx = requireContext()
        val dp = { v: Int -> (v * resources.displayMetrics.density + 0.5f).toInt() }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        // 股票名稱 + 代碼
        val nameCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 2f)
        }
        nameCol.addView(TextView(ctx).apply {
            text = stock.name
            textSize = 14f
            setTextColor(Color.parseColor("#222222"))
            setTypeface(null, Typeface.BOLD)
        })
        nameCol.addView(TextView(ctx).apply {
            text = "${stock.code} · ${stock.sector}"
            textSize = 10f
            setTextColor(Color.parseColor("#999999"))
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(nameCol)

        // 漲跌幅
        val changeColor = when {
            stock.changePct > 0 -> Color.parseColor("#E53935")
            stock.changePct < 0 -> Color.parseColor("#43A047")
            else -> Color.parseColor("#666666")
        }
        row.addView(TextView(ctx).apply {
            text = if (stock.changePct >= 0) "+${"%.2f".format(stock.changePct)}%" else "${"%.2f".format(stock.changePct)}%"
            textSize = 13f
            setTextColor(changeColor)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.END
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })

        container.addView(row)

        // 分隔線
        container.addView(View(ctx).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(dp(12), 0, dp(12), 0)
            }
            setBackgroundColor(Color.parseColor("#F0F0F0"))
        })
    }

    /**
     * 判斷是否為主板股票（非科創非創業）
     */
    private fun isMainBoard(code: String): Boolean {
        return code.startsWith("sh6") || code.startsWith("sz0") || code.startsWith("sz2")
    }
}
