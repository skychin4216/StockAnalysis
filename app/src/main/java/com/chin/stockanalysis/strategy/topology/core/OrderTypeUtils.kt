package com.chin.stockanalysis.strategy.topology.core

/**
 * 將 orderType 歸一化為持倉周期標識。
 *
 * 不同寫入路徑的 orderType 取值不一致（如 DAG 寫 "ShortTermQuant"、
 * Fragment 寫 "shortterm"），持倉統計必須按周期聚合而非跨周期累加，
 * 否則短線會把中線持倉也算進自己的倉位数。
 */
fun orderTypePeriod(orderType: String): String = when {
    orderType.contains("UltraShort", ignoreCase = true) ||
        orderType.contains("ultra_short", ignoreCase = true) -> "ultra_short"
    orderType.contains("ShortTerm", ignoreCase = true) ||
        orderType.contains("short_term", ignoreCase = true) ||
        orderType.equals("shortterm", ignoreCase = true) -> "short"
    orderType.contains("MidTerm", ignoreCase = true) ||
        orderType.contains("mid_term", ignoreCase = true) -> "mid"
    orderType.contains("LongTerm", ignoreCase = true) ||
        orderType.contains("long_term", ignoreCase = true) -> "long"
    else -> "other"
}
