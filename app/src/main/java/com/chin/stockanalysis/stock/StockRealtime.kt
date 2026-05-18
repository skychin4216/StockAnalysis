package com.chin.stockanalysis.stock

/**
 * 股票实时行情统一数据模型
 */
data class StockRealtime(
    val code: String,           // 股票代码 sh600519
    val name: String,           // 贵州茅台
    val price: Double,          // 当前价
    val open: Double,           // 开盘价
    val yestClose: Double,      // 昨收
    val high: Double,           // 最高
    val low: Double,            // 最低
    val volume: Long,           // 成交量(股)
    val amount: Double,         // 成交额
    val changePercent: Double,  // 涨跌幅 %
    val changeAmount: Double,   // 涨跌额
    val timestamp: Long         // 时间戳
)

