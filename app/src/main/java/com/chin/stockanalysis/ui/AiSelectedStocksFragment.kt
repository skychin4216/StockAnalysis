package com.chin.stockanalysis.ui

import android.graphics.Color
import android.graphics.Typeface
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * ## AI 精選股 Tab
 *
 * 顯示當天 AI 精選的股票（從 ai_selected_stock 表讀取），
 * 不合併到自選股（獨立於 WatchlistGroupFragment）。
 *
 * AppBackgroundRunner 會在切換交易日時自動遷移歷史數據到 user_watchlist。
 *
 * UI 採用東方財富風格：
 * - 頂部標題 + 統計摘要
 * - 表格化列表：股票 | 來源 | 分數 | 最新價 | 漲跌幅
 * - 紅漲綠跌
 * - 點擊加入自選股
 */
class AiSelectedStocksFragment : Fragment() {

    private lateinit var rootLayout: LinearLayout
    private lateinit var statusTv: TextView
    private lateinit var stockListContainer: LinearLayout
    private lateinit var lastUpdateTv: TextView

    private var aiStocks: List<AiSelectedStockEntity> = emptyList()
    private val stockDataCache = mutableMapOf<String, com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity?>()

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
        loadAiSelectedStocks()
        return sv
    }

    private fun buildUI() {
        // ── 頂部──
        val topBar = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(16, 16, 16, 12)
            elevation = 2f
        }
        topBar.addView(TextView(requireContext()).apply {
            text = "🤖 AI 精选"
            textSize = 20f
            setTextColor(Color.parseColor("#1A1A2E"))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        // 刷新按鈕
        val refreshBtn = TextView(requireContext()).apply {
            text = "🔄"
            textSize = 18f
            setPadding(12, 8, 8, 8)
            setOnClickListener { loadAiSelectedStocks() }
        }
        topBar.addView(refreshBtn)
        rootLayout.addView(topBar)

        // ── 狀態欄 ──
        val statusRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 8, 16, 8)
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#FFF8E1"))
        }
        statusTv = TextView(requireContext()).apply {
            text = "加载中..."
            textSize = 12f
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

        // ── 說明 ──
        rootLayout.addView(TextView(requireContext()).apply {
            text = "⚡ 僅顯示當天 AI 精選，下一個交易日自動遷移至「我的自選」"
            textSize = 10f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(16, 4, 16, 8)
        })

        // ── 列表容器 ──
        stockListContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 4, 8, 16)
        }
        rootLayout.addView(stockListContainer)
    }

    private fun loadAiSelectedStocks() {
        statusTv.text = "加载中..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val today = LocalDate.now().format(DATE_FMT)

                // 讀取當天 AI 精選
                aiStocks = db.aiSelectedStockDao().getByDate(today)

                // 獲取即時行情
                stockDataCache.clear()
                for (stock in aiStocks) {
                    val snap = try { db.dailySnapshotDao().getByDateAndCode(today, stock.stockCode) }
                        catch (_: Exception) { null }
                    stockDataCache[stock.stockCode] = snap
                }

                withContext(Dispatchers.Main) {
                    renderStockList()
                    statusTv.text = if (aiStocks.isNotEmpty())
                        "✅ 共 ${aiStocks.size} 只 AI 精選股"
                    else
                        "📌 暫無 AI 精選數據，請先在策略頁面運行選股"

                    lastUpdateTv.text = java.text.SimpleDateFormat(
                        "HH:mm:ss", java.util.Locale.getDefault()
                    ).format(java.util.Date())

                    // AI 精選已整合到 WatchlistUnifiedFragment，無需再通知 tab 可見性
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTv.text = "❌ 加載失敗: ${e.message?.take(30)}"
                }
            }
        }
    }

    private fun renderStockList() {
        stockListContainer.removeAllViews()

        if (aiStocks.isEmpty()) {
            stockListContainer.addView(TextView(requireContext()).apply {
                text = "暫無 AI 精選股"
                textSize = 14f
                setTextColor(Color.parseColor("#999999"))
                gravity = Gravity.CENTER
                setPadding(0, 32, 0, 32)
            })
            return
        }

        // ── 表頭 ──
        val headerRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#EEEEEE"))
            setPadding(8, 8, 8, 8)
            gravity = Gravity.CENTER_VERTICAL
        }
        for ((text, weight) in listOf("股票" to 2.5f, "來源" to 1.2f, "分數" to 0.8f, "最新價" to 1.2f, "漲跌幅" to 1.0f)) {
            headerRow.addView(TextView(requireContext()).apply {
                this.text = text
                textSize = 11f
                setTextColor(Color.parseColor("#888888"))
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
            })
        }
        stockListContainer.addView(headerRow)

        // ── 股票行 ──
        for (stock in aiStocks) {
            val row = createStockRow(stock)
            stockListContainer.addView(row)
        }
    }

    private fun createStockRow(stock: AiSelectedStockEntity): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(8, 10, 8, 10)
            gravity = Gravity.CENTER_VERTICAL
            // 點擊加入自選股
            setOnClickListener { addToWatchlist(stock) }
            // 底部分隔線
            val divider = View(requireContext()).apply {
                setBackgroundColor(Color.parseColor("#F0F0F0"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                )
            }
        }

        // 股票名稱 + 代碼
        val nameCell = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2.5f)
            gravity = Gravity.CENTER
        }
        nameCell.addView(TextView(requireContext()).apply {
            text = stock.stockName.take(8)
            textSize = 13f
            setTextColor(Color.parseColor("#1A1A2E"))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        nameCell.addView(TextView(requireContext()).apply {
            text = stock.stockCode.takeLast(6)
            textSize = 9f
            setTextColor(Color.parseColor("#AAAAAA"))
            gravity = Gravity.CENTER
        })
        row.addView(nameCell)

        // 來源
        val sourceLabel = when (stock.source) {
            "shortterm" -> "短線"
            "midterm" -> "中線"
            "agent" -> "Agent"
            else -> stock.source.take(4)
        }
        row.addView(TextView(requireContext()).apply {
            text = sourceLabel
            textSize = 10f
            setTextColor(Color.parseColor("#1565C0"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
        })

        // 分數
        val scoreColor = when {
            stock.score >= 80 -> "#E65100"
            stock.score >= 60 -> "#2E7D32"
            else -> "#666666"
        }
        row.addView(TextView(requireContext()).apply {
            text = "${stock.score}"
            textSize = 12f
            setTextColor(Color.parseColor(scoreColor))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f)
        })

        // 最新價 & 漲跌幅
        val snap = stockDataCache[stock.stockCode]
        if (snap != null) {
            row.addView(TextView(requireContext()).apply {
                text = "¥${"%.2f".format(snap.close)}"
                textSize = 12f
                setTextColor(Color.parseColor("#E53935"))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
            })

            val changePct = snap.changePct
            val changeColor = if (changePct >= 0) "#E53935" else "#43A047"
            row.addView(TextView(requireContext()).apply {
                text = "${if (changePct >= 0) "+" else ""}${"%.2f".format(changePct)}%"
                textSize = 12f
                setTextColor(Color.parseColor(changeColor))
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            })
        } else {
            row.addView(TextView(requireContext()).apply {
                text = "—"
                textSize = 12f
                setTextColor(Color.parseColor("#999999"))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
            })
            row.addView(TextView(requireContext()).apply {
                text = "—"
                textSize = 12f
                setTextColor(Color.parseColor("#999999"))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            })
        }

        // ── 分隔線 ──
        val wrapper = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        wrapper.addView(row)
        wrapper.addView(View(requireContext()).apply {
            setBackgroundColor(Color.parseColor("#F0F0F0"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { setMargins(8, 0, 8, 0) }
        })
        return wrapper
    }

    /**
     * 手動將 AI 精選股加入自選股
     */
    private fun addToWatchlist(stock: AiSelectedStockEntity) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val existing = db.userWatchlistDao().getByCode(stock.stockCode)

                if (existing != null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            requireContext(),
                            "「${stock.stockName}」已在自選股中",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }

                db.userWatchlistDao().insert(UserWatchlistEntity(
                    stockCode = stock.stockCode,
                    stockName = stock.stockName,
                    source = "ai_${stock.source}",
                    addedDate = LocalDate.now().format(DATE_FMT),
                    status = "WATCHING",
                    scoreAtAdd = stock.score
                ))

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        "✅ 已加入自選股: ${stock.stockName}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        "❌ 加入失敗: ${e.message?.take(30)}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}