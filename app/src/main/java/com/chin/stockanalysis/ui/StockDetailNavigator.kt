package com.chin.stockanalysis.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity

/**
 * 統一的股票詳情頁跳轉導航器
 * 任何 Fragment/Activity 中點擊股票名稱/代碼，調用此類跳轉到 StockDetailFragment
 */
object StockDetailNavigator {

    /**
     * 從 Fragment 跳轉到股票詳情頁
     * @param fragment 當前 Fragment
     * @param stockCode 股票代碼（如 "sh600519"）
     * @param stockName 股票名稱
     * @param price 當前價格（可選）
     * @param changePct 漲跌幅（可選）
     * @param sectorName 所屬板塊（可選）
     * @param autoExpandAi 是否自動展開 AI 分析區（默認 true，跳過簡單頁面）
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
            stockCode = stockCode,
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
     * 從 Activity 跳轉到股票詳情頁
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
            stockCode = stockCode,
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
