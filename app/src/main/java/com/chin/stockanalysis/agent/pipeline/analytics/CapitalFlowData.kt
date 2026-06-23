package com.chin.stockanalysis.agent.pipeline.analytics

/**
 * Phase 4 — 資金流數據模型
 *
 * 擴展 Agent F 的數據底座，加入：
 * - 主力資金流向（大單/超大單淨流入）
 * - 北向資金（滬股通/深股通）
 * - 融資融券餘額變化
 * - 機構席位買賣動向
 */
data class CapitalFlowData(
    val stockCode: String,
    val stockName: String,
    val date: String,

    /** 主力淨流入（萬元） */
    val mainForceNetInflow: Double = 0.0,

    /** 超大單淨流入（萬元） */
    val superLargeNetInflow: Double = 0.0,

    /** 大單淨流入（萬元） */
    val largeNetInflow: Double = 0.0,

    /** 北向資金淨流入（萬元） */
    val northboundNetInflow: Double = 0.0,

    /** 融資餘額變化（萬元） */
    val marginBalanceChange: Double = 0.0,

    /** 機構買入席位數 */
    val institutionalBuyCount: Int = 0,

    /** 機構賣出席位數 */
    val institutionalSellCount: Int = 0,

    /** 5日主力淨流入均值 */
    val mainForce5dAvg: Double = 0.0,

    /** 資金流評分（-100 ~ +100） */
    val flowScore: Int = 0
) {
    /**
     * 資金流信號強度
     */
    fun signalStrength(): String = when {
        flowScore >= 60 -> "強流入"
        flowScore >= 30 -> "流入"
        flowScore >= -30 -> "中性"
        flowScore >= -60 -> "流出"
        else -> "強流出"
    }

    /**
     * 機構淨買入（正數為買入多於賣出）
     */
    fun institutionalNet(): Int = institutionalBuyCount - institutionalSellCount
}
