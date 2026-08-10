package com.chin.stockanalysis.strategy.topology.core

/**
 * 将 orderType 归一化为持仓周期标识。
 *
 * 不同写入路径的 orderType 取值不一致（如 DAG 写 "ShortTermQuant"、
 * Fragment 写 "shortterm"），持仓统计必须按周期聚合而非跨周期累加，
 * 否则短线会把中线持仓也算进自己的仓位数。
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
