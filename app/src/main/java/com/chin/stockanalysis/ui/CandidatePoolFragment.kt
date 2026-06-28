package com.chin.stockanalysis.ui

import android.graphics.Color
import android.util.Log
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
import com.chin.stockanalysis.common.StockDataService
import com.chin.stockanalysis.common.StockTableHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ## 備選池 Fragment
 *
 * 顯示 CandidatePool 中的股票列表，支持僅主板過濾。
 * 所有字段通過 StockDataService.enrich() 批量填充。
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

        outer.addView(TextView(ctx).apply {
            text = "核心龍頭 + ETF熱門板塊 + 東方財富熱門板塊，去重後約100-200只主板股票"
            textSize = 11f
            setTextColor(Color.parseColor("#999999"))
            setPadding(0, 0, 0, 12)
        })

        container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(container)

        loadPool(false)
        return root
    }

    private fun loadPool(forceRefresh: Boolean) {
        lifecycleScope.launch {
            refreshBtn.isEnabled = false; refreshBtn.text = "刷新中..."
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
                    textSize = 14f; setTextColor(Color.parseColor("#E53935"))
                    setPadding(0, 20, 0, 20)
                })
            }
            refreshBtn.isEnabled = true; refreshBtn.text = "刷新"
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
        }.sortedByDescending { it.changePct }

        val mainBoardCount = allStocks.count { isMainBoard(it.code) }
        val kcCyCount = allStocks.size - mainBoardCount
        countTv.text = "主板 ${mainBoardCount} 只 / 科創/創業 ${kcCyCount} 只"
        updateTimeTv.text = "更新: ${snapshot.updateTime}"

        if (filteredStocks.isEmpty()) {
            container.addView(TextView(ctx).apply {
                text = "暫無數據"; textSize = 14f
                setTextColor(Color.parseColor("#999999"))
                gravity = Gravity.CENTER; setPadding(0, 48, 0, 48)
            })
            return
        }

        // 異步填充完整數據
        lifecycleScope.launch {
            try {
                val codes = filteredStocks.map { it.code }
                val items = StockDataService.enrich(ctx, codes)
                withContext(Dispatchers.Main) {
                    container.removeAllViews()
                    container.addView(StockTableHelper.createHeaderRow(ctx) {
                        // 點擊"清空"標題：清空當前列表（不重新生成備選池）
                        currentSnapshot = currentSnapshot?.let { it.copy(stocks = emptyList()) }
                        // 從 SharedPreferences 緩存中清空
                        val prefs = ctx.getSharedPreferences("candidate_pool_prefs", android.content.Context.MODE_PRIVATE)
                        prefs.edit().remove("pool_codes").apply()
                        renderList()
                    })
                    for ((index, item) in items.withIndex()) {
                        container.addView(StockTableHelper.createDataRow(ctx, item, index == items.size - 1,
                            onDelete = { deleted ->
                                // 從 currentSnapshot 中移除（不觸發重新生成）
                                currentSnapshot = currentSnapshot?.let { snapshot ->
                                    snapshot.copy(stocks = snapshot.stocks.filter { it.code != deleted.code })
                                }
                                // 從 SharedPreferences 緩存中移除
                                val prefs = ctx.getSharedPreferences("candidate_pool_prefs", android.content.Context.MODE_PRIVATE)
                                val codes = (prefs.getStringSet("pool_codes", emptySet()) ?: emptySet()).toMutableSet()
                                codes.remove(deleted.code)
                                prefs.edit().putStringSet("pool_codes", codes).apply()
                                // 重新渲染列表（僅 UI 刷新，不重新生成備選池）
                                renderList()
                            }
                        ))
                    }
                }
            } catch (e: Exception) {
                Log.e("CandidatePool", "渲染失敗", e)
                withContext(Dispatchers.Main) { /* show empty */ }
            }
        }
    }

    private fun isMainBoard(code: String): Boolean {
        return code.startsWith("sh6") || code.startsWith("sz0") || code.startsWith("sz2")
    }
}