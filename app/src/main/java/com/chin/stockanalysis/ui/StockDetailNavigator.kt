package com.chin.stockanalysis.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.chin.stockanalysis.agent.stock.StockAnalysisAgent
import com.chin.stockanalysis.ai.StockEntityExtractor

/**
 * 统一的股票详情页跳转导航器
 * 任何 Fragment/Activity 中点击股票名称/代码，调用此类跳转到 StockDetailFragment
 */
object StockDetailNavigator {

    /** 解析中文名称 → 代码，再标准化 */
    private fun resolveAndNormalize(code: String): String {
        val resolved = StockEntityExtractor.resolveSync(code) ?: code
        return StockAnalysisAgent.normalizeStockCode(resolved)
    }

    /**
     * 从 Fragment 跳转到股票详情页
     * @param fragment 当前 Fragment
     * @param stockCode 股票代码（如 "sh600519"）或中文名称
     * @param stockName 股票名称
     * @param price 当前价格（可选）
     * @param changePct 涨跌幅（可选）
     * @param sectorName 所属板块（可选）
     * @param autoExpandAi 是否自动展开 AI 分析区（默认 true，跳过简单页面）
     */
    fun navigateFromFragment(
        fragment: Fragment,
        stockCode: String,
        stockName: String,
        price: Double = 0.0,
        changePct: Double = 0.0,
        sectorName: String = "",
        autoExpandAi: Boolean = true
    ) {
        val detail = StockDetailFragment.newInstance(
            stockCode = resolveAndNormalize(stockCode),
            stockName = stockName,
            price = price,
            changePct = changePct,
            sectorName = sectorName,
            autoExpandAi = autoExpandAi
        )
        fragment.activity?.supportFragmentManager
            ?.beginTransaction()
            ?.replace(android.R.id.content, detail)
            ?.addToBackStack(null)
            ?.commit()
    }

    /**
     * 从 Activity 跳转到股票详情页
     */
    fun navigateFromActivity(
        activity: FragmentActivity,
        stockCode: String,
        stockName: String,
        price: Double = 0.0,
        changePct: Double = 0.0,
        sectorName: String = "",
        autoExpandAi: Boolean = true
    ) {
        val detail = StockDetailFragment.newInstance(
            stockCode = resolveAndNormalize(stockCode),
            stockName = stockName,
            price = price,
            changePct = changePct,
            sectorName = sectorName,
            autoExpandAi = autoExpandAi
        )
        activity.supportFragmentManager
            .beginTransaction()
            .replace(android.R.id.content, detail)
            .addToBackStack(null)
            .commit()
    }
}
