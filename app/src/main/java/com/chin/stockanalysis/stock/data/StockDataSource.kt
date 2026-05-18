package com.chin.stockanalysis.stock.data

import com.chin.stockanalysis.stock.StockRealtime

/**
 * 股票数据源接口
 */
interface StockDataSource {
    /**
     * 批量获取实时行情
     */
    fun fetchRealtime(codes: List<String>): Map<String, StockRealtime>

    /**
     * 检查数据源是否可用
     */
    fun isAvailable(): Boolean

    /**
     * 优先级（越小越优先）
     */
    fun priority(): Int
}

