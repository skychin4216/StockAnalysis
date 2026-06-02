package com.chin.stockanalysis.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.*
import android.widget.*
import android.widget.LinearLayout.LayoutParams
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.chin.stockanalysis.news.NewsFactorEntity
import com.chin.stockanalysis.stock.database.StockDatabase
import kotlinx.coroutines.*
import androidx.lifecycle.lifecycleScope

/**
 * ## 热点新闻详情 (BottomSheet)
 *
 * 点击热点新闻 → 弹出：
 * - 上半部分：完整新闻内容 + 板块标签 + 利好/利空
 * - 下半部分：关联的 A 股主板股票列表
 *
 * ### 关联股票匹配逻辑
 * - 按 stock_code 直接匹配
 * - 按 tags 匹配 stock_basics 表中的业务描述
 */
class HotNewsDetailFragment : BottomSheetDialogFragment() {

    var newsItem: NewsFactorEntity? = null
    var openStockTab: Boolean = false  // 是否直接展示股票列表

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 48)
            setBackgroundColor(Color.WHITE)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        val n = newsItem ?: return root

        // 标题
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }
        titleRow.addView(TextView(ctx).apply {
            text = if (n.sentiment > 0) "📈" else if (n.sentiment < 0) "📉" else "➖"
            textSize = 24f
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = 8 }
        })
        titleRow.addView(TextView(ctx).apply {
            text = n.title; textSize = 18f; setTextColor(Color.parseColor("#1A1A2E"))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(titleRow)

        // 日期 + 板块
        val metaRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, 12)
        }
        metaRow.addView(TextView(ctx).apply {
            text = "🕐 ${n.newsDate}"; textSize = 12f; setTextColor(Color.parseColor("#888888"))
        })
        if (n.sector.isNotBlank()) {
            metaRow.addView(TextView(ctx).apply {
                text = "  🏷 ${n.sector}"; textSize = 12f; setTextColor(Color.parseColor("#1565C0"))
            })
        }
        val sentimentColor = if (n.sentiment > 0) Color.parseColor("#2E7D32") else Color.parseColor("#E53935")
        metaRow.addView(TextView(ctx).apply {
            text = "  ${if (n.sentiment > 0) "利好" else "利空"} (${n.impactStrength})"
            textSize = 12f; setTextColor(sentimentColor)
            setTypeface(null, Typeface.BOLD)
        })
        root.addView(metaRow)

        // 分隔线
        root.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#EEEEEE"))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 2).apply { bottomMargin = 12 }
        })

        // 内容
        root.addView(TextView(ctx).apply {
            text = "📰 详细内容:"
            textSize = 14f; setTextColor(Color.parseColor("#333333"))
            setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, 4)
        })
        root.addView(TextView(ctx).apply {
            text = n.content.ifBlank { "暂无详细内容" }; textSize = 14f
            setTextColor(Color.parseColor("#444444")); setPadding(0, 0, 0, 12)
        })

        // 标签
        if (n.tags.isNotBlank()) {
            root.addView(TextView(ctx).apply {
                text = "🏷 标签: ${n.tags}"; textSize = 12f
                setTextColor(Color.parseColor("#666666")); setPadding(0, 0, 0, 12)
            })
        }

        // 分隔线
        root.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#EEEEEE"))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 2).apply { topMargin = 4; bottomMargin = 12 }
        })

        // 关联股票标题
        root.addView(TextView(ctx).apply {
            text = "📊 关联 A 股主板股票"
            textSize = 15f; setTextColor(Color.parseColor("#1A1A2E"))
            setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, 8)
        })

        // 关联股票列表容器
        val stocksContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, 8)
        }
        val loadingTv = TextView(ctx).apply {
            text = "  ⏳ 正在匹配关联股票..."
            textSize = 12f; setTextColor(Color.parseColor("#999999"))
        }
        stocksContainer.addView(loadingTv)
        root.addView(stocksContainer)

        // 后台加载关联股票
        lifecycleScope.launch {
            val stocks = findRelatedStocks(n)
            withContext(Dispatchers.Main) {
                stocksContainer.removeView(loadingTv)
                if (stocks.isEmpty()) {
                    stocksContainer.addView(TextView(ctx).apply {
                        text = "  ⚠️ 未找到直接关联的主板股票\n  可尝试在\"量化选股\"中按板块筛选"
                        textSize = 12f; setTextColor(Color.parseColor("#999999")); setPadding(0, 4, 0, 0)
                    })
                } else {
                    for (stock in stocks) {
                        val row = LinearLayout(ctx).apply {
                            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                            setPadding(4, 6, 4, 6)
                            setBackgroundColor(Color.parseColor("#F5F6FA"))
                            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 4 }
                        }
                        row.addView(TextView(ctx).apply {
                            text = "${stock.name} (${stock.code.takeLast(6)})"
                            textSize = 14f; setTextColor(Color.parseColor("#333333"))
                            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                        })
                        val copyBtn = TextView(ctx).apply {
                            text = "📋"; textSize = 18f; setPadding(8, 4, 8, 4)
                            setOnClickListener {
                                android.content.ClipboardManager.OnPrimaryClipChangedListener {
                                    Toast.makeText(ctx, "已复制: ${stock.name}(${stock.code})", Toast.LENGTH_SHORT).show()
                                }
                                val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("stock", "${stock.name} ${stock.code}"))
                                Toast.makeText(ctx, "已复制: ${stock.name}(${stock.code})", Toast.LENGTH_SHORT).show()
                            }
                        }
                        row.addView(copyBtn)
                        stocksContainer.addView(row)
                    }
                }
            }
        }

        return root
    }

    data class RelatedStock(val name: String, val code: String, val business: String)

    private suspend fun findRelatedStocks(news: NewsFactorEntity): List<RelatedStock> = withContext(Dispatchers.IO) {
        try {
            val db = StockDatabase.getInstance(requireContext())
            val results = mutableListOf<RelatedStock>()

            // 1. 直接按 stock_code 匹配
            if (news.stockCode.isNotBlank()) {
                val basic = db.stockBasicDao().getByCode(news.stockCode)
                if (basic != null) {
                    results.add(RelatedStock(basic.name, basic.code, basic.business))
                }
            }

            // 2. 按 tags 关键词搜索
            val tags = news.tags.split(",").map { it.trim() }.filter { it.isNotBlank() }
            for (tag in tags.take(3)) {
                val matches = db.stockBasicDao().searchByName(tag)
                for (m in matches) {
                    if (results.none { it.code == m.code }) {
                        results.add(RelatedStock(m.name, m.code, m.business))
                    }
                }
            }

            // 3. 按板块 sector 搜索
            if (news.sector.isNotBlank()) {
                try {
                    val sectorCodes = db.sectorStockDao().getStockCodesBySector(news.sector)
                    val basics = db.stockBasicDao().getByCodes(sectorCodes.take(10))
                    for (b in basics) {
                        if (results.none { it.code == b.code } && results.size < 10) {
                            results.add(RelatedStock(b.name, b.code, b.business))
                        }
                    }
                } catch (_: Exception) { /* sector may not exist */ }
            }

            // 只保留主板 (sh/sz)
            results.filter { it.code.startsWith("sh") || it.code.startsWith("sz") }.take(10)
        } catch (e: Exception) {
            android.util.Log.w("HotNewsDetail", "查找关联股票失败: ${e.message}")
            emptyList()
        }
    }
}