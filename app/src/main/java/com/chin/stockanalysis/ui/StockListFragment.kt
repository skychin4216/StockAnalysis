package com.chin.stockanalysis.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * ## 股票列表 Fragment
 *
 * 按类型显示不同的股票列表（A股、ETF、热门、涨幅榜、跌幅榜）。
 * 用户点击某支股票后，通过 [onStockSelected] 回调通知父级（StockBrowserFragment）。
 *
 * ### 数据说明
 * 当前使用示例数据演示布局，后续可对接 StockQueryEngine 获取实时数据。
 */
class StockListFragment : Fragment() {

    enum class Type {
        A_SHARE,    // A股主板（沪深两市）
        ETF,        // ETF 基金
        HOT,        // 热门股票
        GAIN,       // 涨幅排行
        LOSE        // 跌幅排行
    }

    /** 股票被点击时的回调（由 StockBrowserFragment 设置）*/
    var onStockSelected: ((StockItem) -> Unit)? = null

    private lateinit var type: Type
    private lateinit var recyclerView: RecyclerView

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

        recyclerView = RecyclerView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            layoutManager = LinearLayoutManager(context)
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
        }
        addView(recyclerView)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadStocks()
    }

    // ─────────────────────────────────────────────
    // 数据加载
    // ─────────────────────────────────────────────

    private fun loadStocks() {
        val data = when (type) {
            Type.A_SHARE -> aShareSampleData()
            Type.ETF     -> etfSampleData()
            Type.HOT     -> hotSampleData()
            Type.GAIN    -> gainSampleData()
            Type.LOSE    -> loseSampleData()
        }

        recyclerView.adapter = StockListAdapter(data) { stock ->
            onStockSelected?.invoke(stock)
        }
    }

    // ─────────────────────────────────────────────
    // 示例数据（后续替换为实时数据）
    // ─────────────────────────────────────────────

    private fun aShareSampleData() = listOf(
        StockItem("sh600519", "贵州茅台", "1734.50", "+2.15%", "🟢"),
        StockItem("sh600036", "招商银行", "35.80", "+1.10%", "🟢"),
        StockItem("sh601318", "中国平安", "43.60", "+0.92%", "🟢"),
        StockItem("sh600000", "浦发银行", "7.42", "+1.23%", "🟢"),
        StockItem("sh601988", "中国银行", "4.15", "-0.58%", "🔴"),
        StockItem("sz000858", "五粮液", "134.80", "+3.45%", "🟢"),
        StockItem("sz000651", "格力电器", "40.25", "-2.34%", "🔴"),
        StockItem("sz000333", "美的集团", "58.30", "+1.88%", "🟢"),
        StockItem("sz002594", "比亚迪", "285.40", "+4.20%", "🟢"),
        StockItem("sh600900", "长江电力", "26.80", "+0.75%", "🟢"),
        StockItem("sh601166", "兴业银行", "18.50", "+0.54%", "🟢"),
        StockItem("sh601628", "中国人寿", "31.20", "-1.26%", "🔴"),
        StockItem("sz000001", "平安银行", "10.88", "+0.37%", "🟢"),
        StockItem("sz300750", "宁德时代", "225.60", "+5.10%", "🟢"),
        StockItem("sh688111", "金山办公", "278.30", "+2.80%", "🟢"),
    )

    private fun etfSampleData() = listOf(
        StockItem("sh510300", "沪深300ETF", "4.012", "+0.88%", "🟢"),
        StockItem("sh510050", "50ETF", "3.128", "+0.34%", "🟢"),
        StockItem("sz159915", "创业板ETF", "1.823", "+1.23%", "🟢"),
        StockItem("sh588000", "科创50ETF", "0.924", "-1.23%", "🔴"),
        StockItem("sz159919", "沪深300ETF(2)", "4.015", "+0.90%", "🟢"),
        StockItem("sh512880", "证券ETF", "0.754", "+2.10%", "🟢"),
        StockItem("sh512010", "医疗ETF", "0.891", "-0.55%", "🔴"),
        StockItem("sz159941", "纳指ETF", "1.245", "+0.40%", "🟢"),
        StockItem("sh518880", "黄金ETF", "6.250", "+0.80%", "🟢"),
        StockItem("sh515050", "5GETF", "0.632", "+1.75%", "🟢"),
    )

    private fun hotSampleData() = listOf(
        StockItem("sz300750", "宁德时代", "225.60", "+5.10%", "🟢"),
        StockItem("sz002594", "比亚迪", "285.40", "+4.20%", "🟢"),
        StockItem("sh600519", "贵州茅台", "1734.50", "+2.15%", "🟢"),
        StockItem("sh688111", "金山办公", "278.30", "+2.80%", "🟢"),
        StockItem("sz000858", "五粮液", "134.80", "+3.45%", "🟢"),
        StockItem("sh600036", "招商银行", "35.80", "+1.10%", "🟢"),
        StockItem("sh601288", "农业银行", "3.92", "+0.77%", "🟢"),
        StockItem("sz002415", "海康威视", "29.60", "-1.34%", "🔴"),
        StockItem("sh601012", "隆基绿能", "18.50", "+3.35%", "🟢"),
        StockItem("sh600276", "恒瑞医药", "35.60", "+1.25%", "🟢"),
    )

    private fun gainSampleData() = listOf(
        StockItem("sz002305", "濮阳惠成", "23.45", "+10.00%", "🟢"),
        StockItem("sz300759", "康龙化成", "34.56", "+9.87%", "🟢"),
        StockItem("sz300750", "宁德时代", "225.60", "+5.10%", "🟢"),
        StockItem("sz002594", "比亚迪", "285.40", "+4.20%", "🟢"),
        StockItem("sz000858", "五粮液", "134.80", "+3.45%", "🟢"),
        StockItem("sh688111", "金山办公", "278.30", "+2.80%", "🟢"),
        StockItem("sh512880", "证券ETF", "0.754", "+2.10%", "🟢"),
        StockItem("sh600519", "贵州茅台", "1734.50", "+2.15%", "🟢"),
        StockItem("sz000333", "美的集团", "58.30", "+1.88%", "🟢"),
        StockItem("sz159915", "创业板ETF", "1.823", "+1.23%", "🟢"),
    )

    private fun loseSampleData() = listOf(
        StockItem("sz002305", "濮阳惠成", "19.80", "-10.00%", "🔴"),
        StockItem("sh603185", "上机数控", "45.60", "-8.45%", "🔴"),
        StockItem("sz000651", "格力电器", "40.25", "-2.34%", "🔴"),
        StockItem("sh601628", "中国人寿", "31.20", "-1.26%", "🔴"),
        StockItem("sz002415", "海康威视", "29.60", "-1.34%", "🔴"),
        StockItem("sh588000", "科创50ETF", "0.924", "-1.23%", "🔴"),
        StockItem("sh601988", "中国银行", "4.15", "-0.58%", "🔴"),
        StockItem("sh512010", "医疗ETF", "0.891", "-0.55%", "🔴"),
        StockItem("sh600276", "恒瑞医药", "34.20", "-0.45%", "🔴"),
        StockItem("sz000001", "平安银行", "10.75", "-0.28%", "🔴"),
    )

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
        private const val ARG_TYPE = "type"

        fun newInstance(type: Type) = StockListFragment().apply {
            arguments = Bundle().apply { putSerializable(ARG_TYPE, type) }
        }
    }
}
