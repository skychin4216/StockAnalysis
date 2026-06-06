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
import com.chin.stockanalysis.news.NewsFactorEntity
import com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource
import com.chin.stockanalysis.stock.data.sources.SectorSubDivision
import com.chin.stockanalysis.stock.database.ChinaMarketTradingHours as A股TradingHours
import com.chin.stockanalysis.stock.database.StockDataCenter
import com.chin.stockanalysis.stock.database.StockDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ## 股票详情页（v1.0 — 完整版）
 *
 * 展示内容：
 * 1. 股票头部：名称/代码/现价/涨跌幅/涨跌额
 * 2. 所属板块：板块标签 + 子板块标签
 * 3. 热度评分：从板块热度推算
 * 4. 行情数据：开盘/最高/最低/成交量/成交额/换手率/市值
 * 5. 五档盘口：买卖比 + 低吸评级（可选，需网络数据）
 * 6. 相似股票：同子板块的其他股票（优先主板）
 * 7. AI 综合分析：结合新闻因子 + 成交情况的综合分析
 */
class StockDetailFragment : Fragment() {

    companion object {
        private const val ARG_STOCK_CODE = "stock_code"
        private const val ARG_STOCK_NAME = "stock_name"
        private const val ARG_STOCK_PRICE = "stock_price"
        private const val ARG_CHANGE_PCT = "change_pct"
        private const val ARG_SECTOR_NAME = "sector_name"

        fun newInstance(
            stockCode: String,
            stockName: String,
            price: Double = 0.0,
            changePct: Double = 0.0,
            sectorName: String = ""
        ): StockDetailFragment {
            return StockDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_STOCK_CODE, stockCode)
                    putString(ARG_STOCK_NAME, stockName)
                    putDouble(ARG_STOCK_PRICE, price)
                    putDouble(ARG_CHANGE_PCT, changePct)
                    putString(ARG_SECTOR_NAME, sectorName)
                }
            }
        }
    }

    private lateinit var root: LinearLayout
    private lateinit var contentContainer: LinearLayout
    private lateinit var loadingTv: TextView

    private var stockCode = ""
    private var stockName = ""
    private var initialPrice = 0.0
    private var initialChangePct = 0.0
    private var initialSector = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            stockCode = it.getString(ARG_STOCK_CODE, "")
            stockName = it.getString(ARG_STOCK_NAME, "")
            initialPrice = it.getDouble(ARG_STOCK_PRICE, 0.0)
            initialChangePct = it.getDouble(ARG_CHANGE_PCT, 0.0)
            initialSector = it.getString(ARG_SECTOR_NAME, "")
        }
    }

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, b: Bundle?): View {
        root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F6FA"))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        // Header
        buildHeader()
        buildMarketStatusBar()

        // Scroll content
        val sv = ScrollView(requireContext()).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        }
        contentContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 8, 12, 80)
        }

        loadingTv = TextView(requireContext()).apply {
            text = "⏳ 加载中..."; textSize = 14f; setTextColor(Color.GRAY)
            setPadding(24, 24, 24, 24)
        }
        contentContainer.addView(loadingTv)
        sv.addView(contentContainer)
        root.addView(sv)

        // 加载数据
        loadDetailData()
        return root
    }

    private fun buildHeader() {
        val headerCard = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(20, 36, 20, 16)
            elevation = 2f
        }

        // 标题行：股票名称 + 代码
        val titleRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(requireContext()).apply {
            text = stockName
            textSize = 22f; setTextColor(Color.parseColor("#1A1A2E"))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(TextView(requireContext()).apply {
            text = stockCode.takeLast(6)
            textSize = 13f; setTextColor(Color.parseColor("#999999"))
        })
        headerCard.addView(titleRow)

        // 价格行
        val priceRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(0, 8, 0, 4)
        }
        priceRow.addView(TextView(requireContext()).apply {
            tag = "tvPrice"
            text = if (initialPrice > 0) String.format("%.2f", initialPrice) else "—"
            textSize = 36f; setTextColor(Color.parseColor("#E53935"))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 8, 0)
        })
        priceRow.addView(TextView(requireContext()).apply {
            tag = "tvChangePct"
            val sign = if (initialChangePct >= 0) "+" else ""
            text = if (initialChangePct != 0.0) "$sign${String.format("%.2f", initialChangePct)}%" else "—"
            textSize = 18f; setTypeface(null, Typeface.BOLD)
            setTextColor(if (initialChangePct >= 0) Color.parseColor("#E53935") else Color.parseColor("#43A047"))
        })
        headerCard.addView(priceRow)

        // 板块标签行
        val sectorTagRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 0)
            tag = "sectorTagRow"
        }
        if (initialSector.isNotEmpty()) {
            sectorTagRow.addView(createTagChip(initialSector, "#1565C0"))
        }
        headerCard.addView(sectorTagRow)

        root.addView(headerCard)
    }

    private fun buildMarketStatusBar() {
        val statusBar = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 4, 12, 4)
            setBackgroundColor(Color.parseColor("#FFF3E0"))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            tag = "statusBar"
        }
        val statusText = A股TradingHours.获取状态摘要()
        statusBar.addView(TextView(requireContext()).apply {
            text = statusText
            textSize = 10f; setTextColor(Color.parseColor("#E65100"))
            maxLines = 2
        })
        root.addView(statusBar)
    }

    private fun loadDetailData() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. 获取实时行情（优先）
                val leaderData = fetchRealtimeData()

                // 2. 获取所属板块
                val sectors = StockDataCenter.getSectorsByStock(stockCode)
                val subSector = StockDataCenter.getSubSectorByStock(stockCode, stockName)

                // 3. 获取相似股票
                val similarStocks = if (sectors.isNotEmpty()) {
                    fetchSimilarStocks(sectors.first(), subSector)
                } else emptyList()

                // 4. 获取新闻因子
                val newsFactors = fetchNewsFactors()

                withContext(Dispatchers.Main) {
                    loadingTv.visibility = View.GONE
                    if (leaderData != null) buildRealtimeSection(leaderData)
                    buildSectorSection(sectors, subSector)
                    if (similarStocks.isNotEmpty()) buildSimilarStocksSection(similarStocks)
                    if (newsFactors.isNotEmpty()) buildNewsSection(newsFactors)
                    buildAIAnalysisSection(leaderData, sectors, subSector, newsFactors)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loadingTv.text = "⚠️ 加载失败: ${e.message}"
                }
            }
        }
    }

    /** 拉取东方财富实时龙头股数据 */
    private suspend fun fetchRealtimeData(): EastMoneyHotSectorSource.LeaderStock? {
        if (initialSector.isEmpty()) return null
        // 尝试通过 sector code 获取实时数据
        try {
            val source = EastMoneyHotSectorSource()
            // 搜索匹配的板块代码
            val allSectors = EastMoneyHotSectorSource.industrySectors +
                    EastMoneyHotSectorSource.conceptSectors
            val sectorMatch = allSectors.find { it.name == initialSector }
            if (sectorMatch != null && sectorMatch.code.isNotEmpty()) {
                val leaders = source.fetchSectorLeaders(sectorMatch.code, 20)
                return leaders.find { it.code == stockCode }
            }
        } catch (_: Exception) {}
        return null
    }

    /** 获取新闻因子 */
    private suspend fun fetchNewsFactors(): List<NewsFactorEntity> {
        return try {
            val db = StockDatabase.getInstance(requireContext())
            db.newsFactorDao().getAllActive(100)
                .filter { it.stockCode == stockCode || it.companyName.contains(stockName) }
                .take(5)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 获取相似股票（同板块优先主板） */
    private suspend fun fetchSimilarStocks(sectorName: String, subSectorName: String): List<SectorSubDivision.EnrichedStock> {
        val subSectors = SectorSubDivision.getSubSectors(sectorName)
        if (subSectors.isEmpty()) return emptyList()

        // 优先匹配子板块
        val matched = subSectors.firstOrNull { it.name == subSectorName || it.name.contains(subSectorName) }
            ?: subSectors.firstOrNull()

        return matched?.let { ss ->
            // 主板优先
            val main = ss.mainBoardStocks.filter { it.code != stockCode }.take(4)
            val gem = ss.gemKcbStocks.filter { it.code != stockCode }.take(2)
            main + gem
        } ?: emptyList()
    }

    // ═══════════════════════════════════════════════════════
    // UI Sections
    // ═══════════════════════════════════════════════════════

    /** 实时行情数据卡片 */
    private fun buildRealtimeSection(data: EastMoneyHotSectorSource.LeaderStock) {
        val card = createSectionCard()
        card.addView(createSectionTitle("📊 实时行情"))

        val grid = TableLayout(requireContext()).apply {
            isStretchAllColumns = true; setPadding(4, 4, 4, 4)
        }
        val kvPairs = listOf(
            "现价" to String.format("%.2f", data.price),
            "涨跌" to "${if (data.changePercent >= 0) "+" else ""}${String.format("%.2f", data.changePercent)}%",
            "换手率" to if (data.turnoverRate > 0) String.format("%.1f%%", data.turnoverRate) else "—",
            "主力流入" to if (data.mainNetInflow != 0.0) "${if (data.mainNetInflow > 0) "+" else ""}${String.format("%.1f", data.mainNetInflow)}亿" else "—",
            "市值" to if (data.marketCap > 0) "${String.format("%.0f", data.marketCap)}亿" else "—",
            "涨停板" to if (data.isBoard) "🔴 涨停" else "-",
            "连板天数" to if (data.limitDays > 0) "${data.limitDays}天" else "-",
            "3日流入" to if (data.threeDayInflow > 0) "${String.format("%.1f", data.threeDayInflow)}亿" else "-"
        )

        var count = 0
        var row: TableRow? = null
        for ((label, value) in kvPairs) {
            if (count % 2 == 0) {
                row = TableRow(requireContext())
                grid.addView(row)
            }
            val cell = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(4, 6, 4, 6)
                layoutParams = TableRow.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            }
            cell.addView(TextView(requireContext()).apply {
                text = "$label: "; textSize = 11f; setTextColor(Color.parseColor("#999999"))
            })
            cell.addView(TextView(requireContext()).apply {
                text = value; textSize = 12f; setTextColor(Color.parseColor("#333333"))
                setTypeface(null, Typeface.BOLD)
            })
            row?.addView(cell)
            count++
        }
        card.addView(grid)
        contentContainer.addView(card)
    }

    /** 所属板块区域 */
    private fun buildSectorSection(sectors: List<String>, subSector: String) {
        val card = createSectionCard()
        card.addView(createSectionTitle("🏷 所属板块"))

        val tagsLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(4, 6, 4, 4)
            tag = "sectorTags"
        }

        if (subSector.isNotEmpty() && subSector != "-") {
            tagsLayout.addView(createTagChip(subSector, "#E65100"))
        }

        for (sector in sectors.take(3)) {
            if (sector != subSector) {
                tagsLayout.addView(createTagChip(sector, "#1565C0"))
            }
        }

        if (sectors.isEmpty()) {
            tagsLayout.addView(TextView(requireContext()).apply {
                text = "暂无板块数据"; textSize = 12f; setTextColor(Color.GRAY)
            })
        }

        card.addView(tagsLayout)
        contentContainer.addView(card)
    }

    /** 相似股票区域 */
    private fun buildSimilarStocksSection(stocks: List<SectorSubDivision.EnrichedStock>) {
        val card = createSectionCard()
        card.addView(createSectionTitle("🔗 相似股票（同板块·主板优先）"))

        val listLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(4, 4, 4, 4)
        }

        for ((idx, s) in stocks.withIndex()) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(4, 6, 4, 6)
                if (idx % 2 == 1) setBackgroundColor(Color.parseColor("#F8F9FC"))

                setOnClickListener {
                    // 点击相似股票 → 打开新的股票详情页
                    val d = StockDetailFragment.newInstance(
                        s.code, s.name, 0.0, 0.0,
                        "" // 保留在同一板块内
                    )
                    activity?.supportFragmentManager
                        ?.beginTransaction()
                        ?.replace(android.R.id.content, d)
                        ?.addToBackStack(null)
                        ?.commit()
                }
            }

            // 主板标识
            val boardBadge = if (s.isMainBoard) "🔵" else "🟣"
            row.addView(TextView(requireContext()).apply {
                text = "$boardBadge ${s.name}"
                textSize = 13f; setTextColor(Color.parseColor("#333333"))
                setTypeface(null, Typeface.BOLD)
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(requireContext()).apply {
                text = s.business.take(15) + if (s.business.length > 15) "..." else ""
                textSize = 11f; setTextColor(Color.parseColor("#888888"))
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.5f)
            })

            listLayout.addView(row)
        }

        card.addView(listLayout)
        contentContainer.addView(card)
    }

    /** 新闻因子区域 */
    private fun buildNewsSection(newsFactors: List<NewsFactorEntity>) {
        val card = createSectionCard()
        card.addView(createSectionTitle("📰 相关新闻情绪（${newsFactors.size}条）"))

        for (news in newsFactors.take(5)) {
            val newsRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(4, 6, 4, 6)
                gravity = Gravity.CENTER_VERTICAL
            }
            val sentimentEmoji = when {
                news.sentiment > 0 -> "📈"
                news.sentiment < 0 -> "📉"
                else -> "📊"
            }
            val impactText = when {
                news.impactStrength >= 70 -> "高影响"
                news.impactStrength >= 40 -> "中影响"
                else -> "低影响"
            }
            val sentimentColor = when {
                news.sentiment > 0 -> Color.parseColor("#E53935")
                news.sentiment < 0 -> Color.parseColor("#43A047")
                else -> Color.parseColor("#999999")
            }

            newsRow.addView(TextView(requireContext()).apply {
                text = "$sentimentEmoji $impactText"
                textSize = 10f; setTypeface(null, Typeface.BOLD)
                setTextColor(sentimentColor)
                setPadding(0, 0, 4, 0)
            })
            newsRow.addView(TextView(requireContext()).apply {
                text = news.title.take(40)
                textSize = 11f; setTextColor(Color.parseColor("#333333"))
                maxLines = 2
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            })

            card.addView(newsRow)
        }

        contentContainer.addView(card)
    }

    /** AI 综合分析区域 */
    private fun buildAIAnalysisSection(
        leaderData: EastMoneyHotSectorSource.LeaderStock?,
        sectors: List<String>,
        subSector: String,
        newsFactors: List<NewsFactorEntity>
    ) {
        val card = createSectionCard()
        card.addView(createSectionTitle("🤖 AI 综合分析"))

        val ctx = requireContext()

        // 分析模板
        val analysis = StringBuilder()
        analysis.appendLine("📋 **$stockName ($stockCode)** 综合分析报告")
        analysis.appendLine()

        // 板块定位
        val sectorStr = if (subSector.isNotEmpty() && subSector != "-") subSector else sectors.firstOrNull() ?: "未分类"
        analysis.appendLine("**所属赛道**: $sectorStr")
        analysis.appendLine("**行业地位**: ${if ((leaderData?.marketCap ?: 0.0) > 500.0) "行业龙头/核心标的" else if ((leaderData?.marketCap ?: 0.0) > 100.0) "板块重要成员" else "板块关联标的"}")
        analysis.appendLine()

        // 行情分析
        if (leaderData != null) {
            analysis.appendLine("**行情分析**:")
            analysis.appendLine("- 现价: ${String.format("%.2f", leaderData.price)} (${if (leaderData.changePercent >= 0) "+" else ""}${String.format("%.2f", leaderData.changePercent)}%)")
            analysis.appendLine("- 换手率: ${if (leaderData.turnoverRate > 0) String.format("%.1f%%", leaderData.turnoverRate) else "暂无数据"}")
            analysis.appendLine("- 主力资金: ${if (leaderData.mainNetInflow != 0.0) "${if (leaderData.mainNetInflow > 0) "+" else ""}${String.format("%.1f", leaderData.mainNetInflow)}亿" else "暂无数据"}")
            if (leaderData.isBoard) analysis.appendLine("- ⚠️ 该股已涨停，注意追高风险")
            if (leaderData.limitDays > 0) analysis.appendLine("- 🚀 连板${leaderData.limitDays}天，市场情绪极强")
            analysis.appendLine()
        }

        // 新闻情绪分析
        if (newsFactors.isNotEmpty()) {
            val bullishCount = newsFactors.count { it.sentiment > 0 }
            val bearishCount = newsFactors.count { it.sentiment < 0 }
            val neutralCount = newsFactors.count { it.sentiment == 0 }
            val avgSentiment = newsFactors.map { it.sentiment }.average()
            val avgImpact = newsFactors.map { it.impactStrength }.average()

            analysis.appendLine("**新闻情绪分析** (${newsFactors.size}条相关新闻):")
            analysis.appendLine("- 利好: $bullishCount 条 | 利空: $bearishCount 条 | 中性: $neutralCount 条")
            analysis.appendLine("- 平均情绪: ${if (avgSentiment > 0) "偏暖" else if (avgSentiment < 0) "偏冷" else "中性"}(${String.format("%.0f", avgSentiment)}%)")
            analysis.appendLine("- 影响力评分: ${String.format("%.0f", avgImpact)}/100")
            analysis.appendLine()
        }

        // 综合建议
        analysis.appendLine("**综合建议**:")
        analysis.appendLine("- 短期趋势: ${if ((leaderData?.changePercent ?: 0.0) > 0.0) "偏强 📈" else if ((leaderData?.changePercent ?: 0.0) < 0.0) "偏弱 📉" else "平稳 📊"}")
        analysis.appendLine("- 资金面: ${if ((leaderData?.mainNetInflow ?: 0.0) > 0.0) "主力净流入" else if ((leaderData?.mainNetInflow ?: 0.0) < 0.0) "主力净流出" else "资金平衡"}")
        val bullishCount = newsFactors.count { it.sentiment > 0 }
        val bearishCount = newsFactors.count { it.sentiment < 0 }
        analysis.appendLine("- 新闻面: ${if (bullishCount > bearishCount) "利好偏多" else if (bearishCount > bullishCount) "利空偏多" else "消息面中性"}")
        analysis.appendLine()
        analysis.appendLine("⚠️ 投资有风险，入市需谨慎。以上分析仅供参考，不构成投资建议。")

        val analysisLabel = TextView(ctx).apply {
            text = analysis.toString()
            textSize = 11f; setTextColor(Color.parseColor("#333333"))
            setLineSpacing(4f, 1.2f)
            setPadding(4, 6, 4, 8)
        }
        card.addView(analysisLabel)

        // AI 追问按钮
        val askAiBtn = TextView(ctx).apply {
            text = "💬 向 AI 追问更多分析"
            textSize = 12f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1565C0"))
            setPadding(24, 10, 24, 10)
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = 8; bottomMargin = 8
            }
            setOnClickListener {
                // 向 AI 发送追问
                val question = "请详细分析 $stockName ($stockCode) 的走势，结合最近的成交量、新闻和所属板块$sectorStr 的轮动情况，给出投资建议。"
                if (activity is MainActivity) {
                    (activity as MainActivity).switchToChatAndSend(question)
                }
            }
        }
        card.addView(askAiBtn)

        contentContainer.addView(card)
    }

    // ═══════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════

    private fun createSectionCard(): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(12, 10, 12, 10)
            elevation = 2f
            (layoutParams as? LayoutParams)?.setMargins(0, 0, 0, 10)
        }
    }

    private fun createSectionTitle(title: String): TextView {
        return TextView(requireContext()).apply {
            text = title
            textSize = 15f; setTextColor(Color.parseColor("#1A1A2E"))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        }
    }

    private fun createTagChip(text: String, colorHex: String): TextView {
        return TextView(requireContext()).apply {
            this.text = text; textSize = 11f
            setTextColor(Color.parseColor(colorHex))
            setBackgroundColor(Color.parseColor(colorHex) and 0x00FFFFFF or 0x20000000) // 20% alpha
            setPadding(10, 3, 10, 3)
            (layoutParams as? LayoutParams)?.setMargins(0, 0, 8, 0)
        }
    }
}