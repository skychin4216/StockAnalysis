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
    val timestamp: Long
)