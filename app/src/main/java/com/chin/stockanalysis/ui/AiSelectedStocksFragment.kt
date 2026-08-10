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
 * ## AI 精选股 Tab
 *
 * 显示当天 AI 精选的股票（从 ai_selected_stock 表读取），
 * 不合并到自选股（独立于 WatchlistGroupFragment）。
 *
 * AppBackgroundRunner 会在切换交易日时自动迁移历史数据到 user_watchlist。
 *
 * UI 采用东方财富风格：
 * - 顶部标题 + 统计摘要
 * - 表格化列表：股票 | 来源 | 分数 | 最新价 | 涨跌幅
 * - 红涨绿跌
 * - 点击加入自选股
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
        // ── 顶部──
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

        // 刷新按钮
        val refreshBtn = TextView(requireContext()).apply {
            text = "🔄"
            textSize = 18f
            setPadding(12, 8, 8, 8)
            setOnClickListener { loadAiSelectedStocks() }
        }
        topBar.addView(refreshBtn)
        rootLayout.addView(topBar)

        // ── 状态栏 ──
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

        // ── 说明 ──
        rootLayout.addView(TextView(requireContext()).apply {
            text = "⚡ 显示近 5 天 AI 精选，不同日期用分隔线区分"
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
                val minDate = LocalDate.now().minusDays(5).format(DATE_FMT)

                // 读取近 5 天 AI 精选（按日期降序）
                val allStocks = db.aiSelectedStockDao().getRecentDays(minDate)

                // 补全名称：从 daily_snapshot 查询缺失名称的股票
                stockDataCache.clear()
                val nameFixedStocks = mutableListOf<AiSelectedStockEntity>()
                for (stock in allStocks) {
                    val fixedName = if (stock.stockName.isBlank() || stock.stockName == stock.stockCode) {
                        try {
                            val snap = db.dailySnapshotDao().getByDateAndCode(today, stock.stockCode)
                            snap?.name ?: stock.stockCode.takeLast(6)
                        } catch (_: Exception) { stock.stockCode.takeLast(6) }
                    } else stock.stockName
                    nameFixedStocks.add(stock.copy(stockName = fixedName))

                    // 缓存即时行情
                    val snap = try { db.dailySnapshotDao().getByDateAndCode(today, stock.stockCode) }
                        catch (_: Exception) { null }
                    stockDataCache[stock.stockCode] = snap
                }

                aiStocks = nameFixedStocks

                withContext(Dispatchers.Main) {
                    renderStockList()
                    val dates = aiStocks.map { it.selectedDate }.distinct().sortedDescending()
                    statusTv.text = if (aiStocks.isNotEmpty())
                        "✅ 共 ${aiStocks.size} 只 AI 精选股（${dates.size} 天）"
                    else
                        "📌 暂无 AI 精选数据，请先在策略页面运行选股"

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

    private fun renderStockList() {
        stockListContainer.removeAllViews()

        if (aiStocks.isEmpty()) {
            stockListContainer.addView(TextView(requireContext()).apply {
                text = "暂无 AI 精选股"
                textSize = 14f
                setTextColor(Color.parseColor("#999999"))
                gravity = Gravity.CENTER
                setPadding(0, 32, 0, 32)
            })
            return
        }

        // 按日期分组（降序）
        val today = LocalDate.now().format(DATE_FMT)
        val grouped = aiStocks.groupBy { it.selectedDate }.toSortedMap(compareByDescending { it })

        for ((date, stocks) in grouped) {
            // ── 日期标题分隔线 ──
            val isToday = date == today
            val dateLabel = if (isToday) "📅 今天 ($date)" else "📅 $date"
            val dateBg = if (isToday) "#FFF8E1" else "#F5F6FA"
            val dateColor = if (isToday) "#E65100" else "#666666"

            stockListContainer.addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.parseColor(dateBg))
                setPadding(12, 8, 12, 8)
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(requireContext()).apply {
                    text = dateLabel
                    textSize = 12f
                    setTextColor(Color.parseColor(dateColor))
                    setTypeface(null, Typeface.BOLD)
                })
                addView(TextView(requireContext()).apply {
                    text = "  ${stocks.size} 只"
                    textSize = 10f
                    setTextColor(Color.parseColor("#AAAAAA"))
                })
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, if (date != grouped.keys.first()) 12 else 0, 0, 0) }
            })

            // ── 该日期的表头 ──
            val headerRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.parseColor("#EEEEEE"))
                setPadding(8, 6, 8, 6)
                gravity = Gravity.CENTER_VERTICAL
            }
            for ((text, weight) in listOf("股票" to 2.5f, "来源" to 1.2f, "分数" to 0.8f, "最新价" to 1.2f, "涨跌幅" to 1.0f)) {
                headerRow.addView(TextView(requireContext()).apply {
                    this.text = text
                    textSize = 10f
                    setTextColor(Color.parseColor("#888888"))
                    setTypeface(null, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
                })
            }
            stockListContainer.addView(headerRow)

            // ── 股票行 ──
            for (stock in stocks) {
                stockListContainer.addView(createStockRow(stock))
            }
        }
    }

    private fun createStockRow(stock: AiSelectedStockEntity): View {
        val snap = stockDataCache[stock.stockCode]
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(8, 10, 8, 10)
            gravity = Gravity.CENTER_VERTICAL
            // 点击跳转到股票详情页
            setOnClickListener {
                StockDetailNavigator.navigateFromFragment(
                    this@AiSelectedStocksFragment,
                    stock.stockCode,
                    stock.stockName,
                    price = snap?.close ?: 0.0,
                    changePct = snap?.changePct ?: 0.0
                )
            }
            // 底部分隔线
            val divider = View(requireContext()).apply {
                setBackgroundColor(Color.parseColor("#F0F0F0"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                )
            }
        }

        // 股票名称 + 代码
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

        // 来源
        val sourceLabel = when (stock.source) {
            "shortterm" -> "短线"
            "midterm" -> "中线"
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

        // 分数
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

        // 最新价 & 涨跌幅
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

        // ── 加入自选按钮（独立点击，不触发行跳转） ──
        row.addView(TextView(requireContext()).apply {
            text = "+自选"
            textSize = 10f
            setTextColor(Color.parseColor("#FFFFFF"))
            setBackgroundColor(Color.parseColor("#43A047"))
            gravity = Gravity.CENTER
            setPadding(6, 3, 6, 3)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { addToWatchlist(stock) }
        })

        // ── 分隔线 ──
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
     * 手动将 AI 精选股加入自选股
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
                            "「${stock.stockName}」已在自选股中",
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
                        "✅ 已加入自选股: ${stock.stockName}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        "❌ 加入失败: ${e.message?.take(30)}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}