package com.chin.stockanalysis.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.stock.database.AiSelectedStockEntity
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.stock.database.UserWatchlistEntity
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * ## 自選 / AI精選 統一頁面
 *
 * 頂部兩個切換按鈕：⭐ 自選 | 🤖 AI精選
 * 共用同一個表格 View，切換時僅刷新數據源：
 *
 * | 股票名稱 | 現價 | 漲幅 | 漲跌 |
 * | 603629   | 12.50| +3.2%| +0.39|
 *
 * - 自選模式：讀取 user_watchlist 表
 * - AI精選模式：讀取 ai_selected_stock 表，當天數據
 */
class WatchlistUnifiedFragment : Fragment() {

    private lateinit var rootLayout: LinearLayout
    private lateinit var selfSelectBtn: TextView
    private lateinit var aiSelectBtn: TextView
    private lateinit var statusTv: TextView
    private lateinit var lastUpdateTv: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var headerRow: LinearLayout

    /** 當前模式：true=AI精選, false=自選 */
    private var isAiMode = false

    /** 自選數據緩存 */
    private var watchlistData: List<UserWatchlistEntity> = emptyList()
    /** AI 精選數據緩存 */
    private var aiStocksData: List<AiSelectedStockEntity> = emptyList()
    /** 行情緩存 */
    private val snapshotCache = mutableMapOf<String, DailySnapshotEntity?>()

    companion object {
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val sv = ScrollView(requireContext()).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F6FA"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        sv.addView(rootLayout)
        buildUI()
        loadData()
        return sv
    }

    private fun buildUI() {
        // ── 頂部標題 ──
        val topBar = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(16, 48, 16, 12)
            elevation = 2f
        }
        topBar.addView(TextView(requireContext()).apply {
            text = "📊 自选/AI精选"
            textSize = 20f
            setTextColor(Color.parseColor("#1A1A2E"))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        val refreshBtn = TextView(requireContext()).apply {
            text = "🔄"
            textSize = 18f
            setPadding(12, 8, 8, 8)
            setOnClickListener { loadData() }
        }
        topBar.addView(refreshBtn)
        rootLayout.addView(topBar)

        // ── 切換按鈕行 ──
        val toggleRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(16, 0, 16, 8)
            gravity = Gravity.CENTER
        }
        val toggleBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor("#F0F0F0"))
            cornerRadius = 6f * resources.displayMetrics.density
        }
        val toggleInner = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            background = toggleBg
            setPadding(2, 2, 2, 2)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        selfSelectBtn = createToggleButton("⭐ 自选", selected = true) {
            if (isAiMode) {
                isAiMode = false
                updateToggleState()
                renderList()
            }
        }
        aiSelectBtn = createToggleButton("🤖 AI精选", selected = false) {
            if (!isAiMode) {
                isAiMode = true
                updateToggleState()
                renderList()
            }
        }

        toggleInner.addView(selfSelectBtn)
        toggleInner.addView(aiSelectBtn)
        toggleRow.addView(toggleInner)
        rootLayout.addView(toggleRow)

        // ── 狀態列 ──
        val statusRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 6, 16, 6)
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#FFF8E1"))
        }
        statusTv = TextView(requireContext()).apply {
            text = "加载中..."
            textSize = 11f
            setTextColor(Color.parseColor("#F57F17"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusRow.addView(statusTv)
        lastUpdateTv = TextView(requireContext()).apply {
            text = ""
            textSize = 10f
            setTextColor(Color.parseColor("#999999"))
        }
        statusRow.addView(lastUpdateTv)
        rootLayout.addView(statusRow)

        // ── 表頭 ──
        headerRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#FAFAFA"))
            setPadding(16, 10, 16, 10)
            visibility = View.GONE
        }
        headerRow.addView(createHeaderCell("股票名称", 2.5f, Gravity.START))
        headerRow.addView(createHeaderCell("现价", 1.0f, Gravity.END))
        headerRow.addView(createHeaderCell("涨幅", 1.0f, Gravity.CENTER))
        headerRow.addView(createHeaderCell("涨跌", 1.0f, Gravity.END))
        rootLayout.addView(headerRow)

        // ── 列表容器 ──
        listContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 80)
        }
        rootLayout.addView(listContainer)
    }

    private fun createHeaderCell(text: String, weight: Float, gravity: Int): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            textSize = 11f
            setTextColor(Color.parseColor("#888888"))
            setTypeface(null, Typeface.BOLD)
            this.gravity = gravity
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
        }
    }

    private fun createToggleButton(text: String, selected: Boolean, onClick: () -> Unit): TextView {
        val dp = resources.displayMetrics.density
        return TextView(requireContext()).apply {
            this.text = text
            textSize = 13f
            setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
            updateToggleStyle(this, selected)
        }
    }

    private fun updateToggleStyle(btn: TextView, selected: Boolean) {
        val dp = resources.displayMetrics.density
        if (selected) {
            btn.setTextColor(Color.WHITE)
            btn.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#1565C0"))
                cornerRadius = 5f * dp
            }
        } else {
            btn.setTextColor(Color.parseColor("#666666"))
            btn.background = null
        }
    }

    private fun updateToggleState() {
        updateToggleStyle(selfSelectBtn, !isAiMode)
        updateToggleStyle(aiSelectBtn, isAiMode)
        selfSelectBtn.setTypeface(null, if (!isAiMode) Typeface.BOLD else Typeface.NORMAL)
        aiSelectBtn.setTypeface(null, if (isAiMode) Typeface.BOLD else Typeface.NORMAL)
    }

    // ═══════════════════════════════════════
    // 數據加載
    // ═══════════════════════════════════════

    private fun loadData() {
        statusTv.text = "加载中..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val today = LocalDate.now().format(DATE_FMT)

                // 加載自選
                watchlistData = db.userWatchlistDao().getAll()

                // 加載 AI 精選
                aiStocksData = db.aiSelectedStockDao().getByDate(today)

                // 獲取行情
                val allCodes = (watchlistData.map { it.stockCode } + aiStocksData.map { it.stockCode }).distinct()
                snapshotCache.clear()
                for (code in allCodes) {
                    val snap = try {
                        db.dailySnapshotDao().getByDateAndCode(today, code)
                    } catch (_: Exception) { null }
                    snapshotCache[code] = snap
                }

                withContext(Dispatchers.Main) {
                    renderList()
                    val count = if (isAiMode) aiStocksData.size else watchlistData.size
                    statusTv.text = "✅ 共 $count 只"
                    lastUpdateTv.text = java.text.SimpleDateFormat(
                        "HH:mm:ss", java.util.Locale.getDefault()
                    ).format(java.util.Date())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTv.text = "❌ 加载失败: ${e.message?.take(30)}"
                }
            }
        }
    }

    // ═══════════════════════════════════════
    // 渲染列表
    // ═══════════════════════════════════════

    private fun renderList() {
        listContainer.removeAllViews()

        val data: List<DisplayItem> = if (isAiMode) {
            aiStocksData.map { DisplayItem(
                code = it.stockCode,
                name = it.stockName,
                source = it.source,
                score = it.score,
                isAi = true
            ) }
        } else {
            watchlistData.map { DisplayItem(
                code = it.stockCode,
                name = it.stockName,
                source = it.source,
                score = it.scoreAtAdd,
                isAi = false
            ) }
        }

        headerRow.visibility = if (data.isNotEmpty()) View.VISIBLE else View.GONE

        if (data.isEmpty()) {
            listContainer.addView(TextView(requireContext()).apply {
                text = if (isAiMode) "暂無 AI 精選数据，请先運行策略" else "暂無自選股，請添加"
                textSize = 14f
                setTextColor(Color.parseColor("#999999"))
                gravity = Gravity.CENTER
                setPadding(0, 48, 0, 48)
            })
            return
        }

        for ((index, item) in data.withIndex()) {
            listContainer.addView(createDataRow(item, index == data.size - 1))
        }
    }

    private data class DisplayItem(
        val code: String,
        val name: String,
        val source: String,
        val score: Int,
        val isAi: Boolean
    )

    private fun createDataRow(item: DisplayItem, isLast: Boolean): View {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density

        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
        }

        // 股票名稱 + 代碼
        val nameCell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2.5f)
        }
        if (item.isAi) {
            val labelRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            labelRow.addView(TextView(ctx).apply {
                text = item.name.take(8)
                textSize = 14f
                setTextColor(Color.parseColor("#1A1A2E"))
                setTypeface(null, Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            // AI 來源標籤
            val sourceLabel = when (item.source) {
                "shortterm" -> "短"
                "midterm" -> "中"
                "agent" -> "A"
                else -> item.source.take(1)
            }
            val labelBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#E3F2FD"))
                cornerRadius = 2f * dp
            }
            labelRow.addView(TextView(ctx).apply {
                text = sourceLabel
                textSize = 9f
                setTextColor(Color.parseColor("#1565C0"))
                setPadding((4 * dp).toInt(), (1 * dp).toInt(), (4 * dp).toInt(), (1 * dp).toInt())
                background = labelBg
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = (6 * dp).toInt() }
            })
            nameCell.addView(labelRow)
        } else {
            nameCell.addView(TextView(ctx).apply {
                text = item.name.take(8)
                textSize = 14f
                setTextColor(Color.parseColor("#1A1A2E"))
                setTypeface(null, Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        nameCell.addView(TextView(ctx).apply {
            text = item.code.takeLast(6)
            textSize = 10f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(0, (2 * dp).toInt(), 0, 0)
        })
        row.addView(nameCell)

        val snap = snapshotCache[item.code]

        // 現價
        val price = snap?.close ?: 0.0
        val priceColor = when {
            snap == null -> Color.parseColor("#999999")
            snap.changePct > 0 -> Color.parseColor("#E53935")
            snap.changePct < 0 -> Color.parseColor("#43A047")
            else -> Color.parseColor("#333333")
        }
        row.addView(TextView(ctx).apply {
            text = if (snap != null) String.format("%.2f", price) else "--"
            textSize = 13f
            setTextColor(priceColor)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        })

        // 漲幅（帶背景色）
        val changePct = snap?.changePct ?: 0.0
        val changeText = when {
            snap == null -> "--"
            changePct > 0 -> "+${String.format("%.2f", changePct)}%"
            else -> "${String.format("%.2f", changePct)}%"
        }
        val changeBg = when {
            snap == null -> Color.TRANSPARENT
            changePct > 0 -> Color.parseColor("#FFF0F0")
            changePct < 0 -> Color.parseColor("#F0FFF0")
            else -> Color.parseColor("#F5F5F5")
        }
        row.addView(TextView(ctx).apply {
            text = changeText
            textSize = 12f
            setTextColor(priceColor)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding((6 * dp).toInt(), (2 * dp).toInt(), (6 * dp).toInt(), (2 * dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(changeBg)
                cornerRadius = 3f * dp
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        })

        // 漲跌額
        row.addView(TextView(ctx).apply {
            text = if (snap != null) {
                val changeAmount = snap.close - snap.open
                "${if (changeAmount >= 0) "+" else ""}${String.format("%.2f", changeAmount)}"
            } else "--"
            textSize = 12f
            setTextColor(priceColor)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        })

        wrapper.addView(row)

        if (!isLast) {
            wrapper.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).apply { setMargins((16 * dp).toInt(), 0, (16 * dp).toInt(), 0) }
                setBackgroundColor(Color.parseColor("#F0F0F0"))
            })
        }

        return wrapper
    }
}