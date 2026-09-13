package com.chin.stockanalysis.stock

/**
 * Stock realtime data model v3.
 * Added optional financial fields for three-layer screening.
 * All new fields default to 0/sentinel meaning "no data available".
 */
data class StockRealtime(
    val code: String,
    val name: String,
    val price: Double,
    val open: Double,
    val yestClose: Double,
    val high: Double,
    val low: Double,
    val volume: Long,
    val amount: Double,
    val changePercent: Double,
    val changeAmount: Double,
    val turnoverRate: Double = 0.0,
    val pe: Double = 0.0,
    val pb: Double = 0.0,
    val marketCap: Double = 0.0,
    // ── Financial quality (optional, 0 = no data) ──
    val roeTTM: Double = 0.0,
    val grossMarginTTM: Double = 0.0,
    val debtToAsset: Double = 0.0,
    val operatingCashFlow: Double = 0.0,
    // ── Level2 data (optional, 0 = no data) ──
    /** 特大单买入占比（单笔>50万），0 = 无数据 */
    val largeOrderBuyRatio: Double = 0.0,
    /** 买卖价差（流动性指标），0 = 无数据 */
    val bidAskSpread: Double = 0.0,
    val timestamp: Long
)