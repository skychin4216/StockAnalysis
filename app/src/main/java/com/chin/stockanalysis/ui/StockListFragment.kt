package com.chin.stockanalysis.ui

import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chin.stockanalysis.stock.data.StockDataSourceFactory
import com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource
import com.chin.stockanalysis.stock.database.StockDataCenter
import com.chin.stockanalysis.stock.database.StockDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * ## 股票列表 Fragment
 *
 * 按类型显示不同的股票列表（A股、ETF、热门、涨幅榜、跌幅榜、主线）。
 * 用户点击某支股票后，通过 [onStockSelected] 回调通知父级（StockBrowserFragment）。
 *
 * ### 数据来源（v2.0）
 * - A股 / ETF：从 `stock_basics` 缓存 + 多源实时行情（新浪/腾讯/东财）批量拉取
 * - 热门：用户搜索历史（重点关注池）+ 实时热门板块领涨股
 * - 涨幅 / 跌幅榜：最近交易日 `daily_snapshot` 按涨跌幅排序
 * - 主线：东方财富实时概念板块领涨股 + 全球指数
 */
class StockListFragment : Fragment() {

    enum class Type {
        A_SHARE,    // A股主板（沪深两市）
        ETF,        // ETF 基金
        HOT,        // 热门股票
        GAIN,       // 涨幅排行
        LOSE,       // 跌幅排行
        MAINLINE    // 日/周/月主线
    }

    /** 股票被点击时的回调（由 StockBrowserFragment 设置）*/
    var onStockSelected: ((StockItem) -> Unit)? = null

    private lateinit var type: Type
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyView: TextView

    private val repository by lazy {
        StockDataSourceFactory.createDefaultRepository(requireContext())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        type = arguments?.getSerializable(ARG_TYPE) as? Type ?: Type.A_SHARE
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = LinearLayout(requireContext()).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(context.getColor(android.R.color.white))

        progressBar = ProgressBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        addView(progressBar)

        recyclerView = RecyclerView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            layoutManager = LinearLayoutManager(context)
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
            visibility = View.GONE
        }
        addView(recyclerView)

        emptyView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            gravity = Gravity.CENTER
            text = "暂无数据"
            textSize = 14f
            setTextColor(0xFF999999.toInt())
            visibility = View.GONE
        }
        addView(emptyView)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadStocks()
    }

    // ─────────────────────────────────────────────
    // 数据加载
    // ─────────────────────────────────────────────

    private fun loadStocks() {
        showLoading()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val data = withContext(Dispatchers.IO) { loadRealData() }
                if (view == null) return@launch
                if (data.isEmpty()) showEmpty() else showList(data)
            } catch (e: Exception) {
                Log.w(TAG, "加载股票列表失败: ${e.message}", e)
                if (view == null) return@launch
                showEmpty()
                Toast.makeText(requireContext(), "加载失败，请稍后重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun loadRealData(): List<StockItem> = when (type) {
        Type.A_SHARE  -> loadAShare()
        Type.ETF      -> loadETF()
        Type.HOT      -> loadHot()
        Type.GAIN     -> loadRank(descending = true)
        Type.LOSE     -> loadRank(descending = false)
        Type.MAINLINE -> loadMainline()
    }

    /** A 股主板：stock_basics 缓存 + 批量实时行情 */
    private suspend fun loadAShare(): List<StockItem> {
        val ctx = requireContext()
        val basics = try {
            StockDatabase.getInstance(ctx).stockBasicDao().getAll()
                .filter { it.code.startsWith("sh6") || it.code.startsWith("sz0") || it.code.startsWith("sz3") }
        } catch (e: Exception) {
            Log.w(TAG, "读取 stock_basics 失败: ${e.message}")
            emptyList()
        }
        if (basics.isEmpty()) return emptyList()

        val codes = basics.take(MAX_ITEMS).map { it.code }
        return buildItemsFromRealtime(codes, basics.associate { it.code to it.name })
    }

    /** ETF：代码 sh5x / sz1x 前缀 */
    private suspend fun loadETF(): List<StockItem> {
        val ctx = requireContext()
        val basics = try {
            StockDatabase.getInstance(ctx).stockBasicDao().getAll()
                .filter { it.code.startsWith("sh5") || it.code.startsWith("sz1") }
        } catch (e: Exception) {
            Log.w(TAG, "读取 stock_basics 失败: ${e.message}")
            emptyList()
        }
        // 数据库无 ETF 时，回退到实时热门板块中已知 ETF 代码
        val knownEtf = listOf(
            "sh510300" to "沪深300ETF", "sh510050" to "50ETF", "sz159915" to "创业板ETF",
            "sh588000" to "科创50ETF", "sh512880" to "证券ETF", "sh512010" to "医疗ETF",
            "sh518880" to "黄金ETF", "sh515050" to "5GETF", "sh512690" to "酒ETF",
            "sh515790" to "光伏ETF", "sh516160" to "新能源ETF", "sh515880" to "通信ETF"
        ).filter { (code, _) -> basics.none { it.code == code } }

        val merged = basics.associate { it.code to it.name } + knownEtf.toMap()
        val codes = merged.keys.toList().take(MAX_ITEMS)
        return buildItemsFromRealtime(codes, merged)
    }

    /** 热门：用户搜索历史（重点关注）+ 实时概念板块领涨股 */
    private suspend fun loadHot(): List<StockItem> {
        val userCodes = StockDataCenter.getUserStockEntries()
            .map { it.stockCode to it.stockName }
            .toMap()

        val sectorLeaders = EastMoneyHotSectorSource.conceptSectors
            .asSequence()
            .filter { it.top1StockCode.isNotBlank() }
            .take(15)
            .map { it.top1StockCode to it.top1StockName }
            .toList()
            .toMap()

        val merged = (userCodes + sectorLeaders).toMap()
        val codes = merged.keys.toList().take(MAX_ITEMS)
        return buildItemsFromRealtime(codes, merged)
    }

    /** 涨幅 / 跌幅榜：最近交易日快照按 changePct 排序 */
    private suspend fun loadRank(descending: Boolean): List<StockItem> {
        val ctx = requireContext()
        val db = StockDatabase.getInstance(ctx)
        val date = try { db.dailySnapshotDao().getAvailableDates(1).firstOrNull() } catch (e: Exception) { null }
            ?: return emptyList()
        val snaps = try { db.dailySnapshotDao().getByDate(date) } catch (e: Exception) { emptyList() }
        val sorted = if (descending) snaps.sortedByDescending { it.changePct } else snaps.sortedBy { it.changePct }
        return sorted.take(MAX_ITEMS).map {
            StockItem(
                code = it.code,
                name = it.name,
                price = formatPrice(it.close),
                change = formatPercent(it.changePct),
                arrow = arrowOf(it.changePct)
            )
        }
    }

    /** 主线：实时概念板块领涨股（前 10）+ 全球指数 */
    private suspend fun loadMainline(): List<StockItem> {
        val result = mutableListOf<StockItem>()

        // 全球指数（优先实时）
        val indices = EastMoneyHotSectorSource.globalIndices
            .take(4)
            .map {
                StockItem(
                    code = "INDEX",
                    name = "📊 ${it.name}",
                    price = formatPrice(it.price),
                    change = formatPercent(it.changePercent),
                    arrow = arrowOf(it.changePercent)
                )
            }
        result.addAll(indices)

        // 热门概念板块领涨股
        val sectors = EastMoneyHotSectorSource.conceptSectors
            .filter { it.top1StockCode.isNotBlank() }
            .take(10)
        if (sectors.isNotEmpty()) {
            val codes = sectors.map { it.top1StockCode }
            val names = sectors.associate { it.top1StockCode to it.top1StockName }
            val rtMap = try { repository.getRealtimeSuspend(codes) } catch (e: Exception) { emptyMap() }
            sectors.forEach { s ->
                val rt = rtMap[s.top1StockCode]
                val price = rt?.price ?: -1.0
                val pct = rt?.changePercent ?: s.top1ChangePercent
                result.add(
                    StockItem(
                        code = s.top1StockCode,
                        name = "🎯 ${s.name}·${s.top1StockName}",
                        price = formatPrice(price),
                        change = formatPercent(pct),
                        arrow = arrowOf(pct)
                    )
                )
            }
        }
        return result
    }

    /** 批量拉实时行情并构建 StockItem */
    private suspend fun buildItemsFromRealtime(
        codes: List<String>,
        nameMap: Map<String, String>
    ): List<StockItem> {
        if (codes.isEmpty()) return emptyList()
        val rtMap = try { repository.getRealtimeSuspend(codes) } catch (e: Exception) {
            Log.w(TAG, "实时行情拉取失败: ${e.message}")
            emptyMap()
        }
        return codes.mapNotNull { code ->
            val rt = rtMap[code] ?: return@mapNotNull null
            StockItem(
                code = code,
                name = rt.name.ifBlank { nameMap[code] ?: code },
                price = formatPrice(rt.price),
                change = formatPercent(rt.changePercent),
                arrow = arrowOf(rt.changePercent)
            )
        }
    }

    // ─────────────────────────────────────────────
    // UI 状态切换
    // ─────────────────────────────────────────────

    private fun showLoading() {
        progressBar.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.GONE
    }

    private fun showList(data: List<StockItem>) {
        progressBar.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        recyclerView.adapter = StockListAdapter(data) { stock ->
            onStockSelected?.invoke(stock)
        }
    }

    private fun showEmpty() {
        progressBar.visibility = View.GONE
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.VISIBLE
        emptyView.text = when (type) {
            Type.A_SHARE  -> "暂无 A 股数据\n请先在 AI 对话中搜索或分析股票"
            Type.ETF      -> "暂无 ETF 数据"
            Type.HOT      -> "暂无热门股票\n去 AI 对话中搜索股票建立关注池"
            Type.GAIN,
            Type.LOSE     -> "暂无行情数据\n请稍后下拉刷新或重试"
            Type.MAINLINE -> "暂无主线数据\n实时板块拉取中，请稍后重试"
        }
    }

    // ─────────────────────────────────────────────
    // 格式化工具
    // ─────────────────────────────────────────────

    private fun formatPrice(p: Double): String =
        if (p <= 0) "--" else String.format(Locale.US, "%.2f", p)

    private fun formatPercent(p: Double): String =
        if (p == 0.0) "0.00%" else String.format(Locale.US, "%+.2f%%", p)

    private fun arrowOf(p: Double): String = when {
        p > 0 -> "🟢"
        p < 0 -> "🔴"
        else  -> "⚪"
    }

    // ─────────────────────────────────────────────
    // 数据类 + 伴生对象
    // ─────────────────────────────────────────────

    data class StockItem(
        val code: String,    // 完整代码，如 sh600519
        val name: String,    // 股票名称
        val price: String,   // 当前价格
        val change: String,  // 涨跌幅，如 +2.15% / -0.58%
        val arrow: String    // 涨跌图标 🟢/🔴（兼容旧代码）
    )

    companion object {
        private const val TAG = "StockListFragment"
        private const val ARG_TYPE = "type"
        private const val MAX_ITEMS = 60

        fun newInstance(type: Type) = StockListFragment().apply {
            arguments = Bundle().apply { putSerializable(ARG_TYPE, type) }
        }
    }
}
