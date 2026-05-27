package com.chin.stockanalysis.testdata

import com.chin.stockanalysis.stock.StockRealtime
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

/**
 * ## 价格合理性断言工具
 *
 * 用于验证从数据源获取的 [StockRealtime] 数据是否合法。
 *
 * ### 价格校验规则
 * - A股价格: 0 < price < 10000
 * - 涨跌幅: -20% ~ +20%（A股主板 ±10%、科创/创业板 ±20%）
 * - 成交量 > 0（正常交易日）
 * - 最高价 >= 最低价
 * - 时间戳在最近 24 小时内（如果在线实时获取）
 */
object PriceAssertions {

    // ── 价格上下界 ──
    private const val MIN_PRICE = 0.01
    private const val MAX_PRICE = 10_000.0         // 贵州茅台约 1700 元
    private const val MIN_CHANGE_PERCENT = -20.1   // 略低于跌停下限，容差0.1
    private const val MAX_CHANGE_PERCENT = 20.1    // 略高于涨停上限，容差0.1

    // ── 指数上下界（指数点位范围更广）
    private const val INDEX_MIN = 10.0
    private const val INDEX_MAX = 100_000.0

    /**
     * 断言单只股票的实时数据合理
     *
     * @param stock 股票实时数据
     * @param tag 用于错误消息的标签（如 "sh600519"）
     */
    fun assertValidStock(stock: StockRealtime, tag: String = "") {
        val prefix = if (tag.isNotEmpty()) "[$tag] " else ""

        // 价格 > 0
        assertTrue("${prefix}价格应>0，实际: ${stock.price}", stock.price > MIN_PRICE)
        assertTrue("${prefix}价格应<$MAX_PRICE，实际: ${stock.price}", stock.price < MAX_PRICE)

        // 涨跌幅在合理范围内
        assertTrue(
            "${prefix}涨跌幅=${stock.changePercent}% 超出 ±20% 范围",
            stock.changePercent in MIN_CHANGE_PERCENT..MAX_CHANGE_PERCENT
        )

        // 最高价 >= 最低价
        assertTrue(
            "${prefix}最高价(${stock.high})应 >= 最低价(${stock.low})",
            stock.high >= stock.low
        )

        // 成交量 >= 0
        assertTrue("${prefix}成交量应>=0，实际: ${stock.volume}", stock.volume >= 0)

        // 时间戳不能是未来
        val now = System.currentTimeMillis()
        assertTrue(
            "${prefix}时间戳不能是未来时间",
            stock.timestamp <= now + 60_000  // 给 1 分钟的容差
        )

        // 昨收价合理
        if (stock.yestClose > 0) {
            assertTrue("${prefix}昨收价应>0", stock.yestClose > MIN_PRICE)
            assertTrue("${prefix}昨收价应<$MAX_PRICE", stock.yestClose < MAX_PRICE)
        }
    }

    /**
     * 断言指数数据合理（指数点位范围更宽）
     */
    fun assertValidIndex(index: StockRealtime, tag: String = "") {
        val prefix = if (tag.isNotEmpty()) "[$tag] " else ""

        assertTrue("${prefix}指数点位应>$INDEX_MIN，实际: ${index.price}", index.price > INDEX_MIN)
        assertTrue("${prefix}指数点位应<$INDEX_MAX，实际: ${index.price}", index.price < INDEX_MAX)

        // 指数涨跌幅一般较小
        assertTrue(
            "${prefix}指数涨跌幅=${index.changePercent}% 超出 ±15% 范围",
            index.changePercent in -15.0..15.0
        )
    }

    /**
     * 断言数据 Map 中所有股票数据合理
     */
    fun assertAllValid(data: Map<String, StockRealtime>) {
        for ((code, stock) in data) {
            assertValidStock(stock, code)
        }
    }

    /**
     * 批量断言 Map 中至少包含指定数量的有效数据
     */
    fun assertHasAtLeast(data: Map<String, StockRealtime>, minCount: Int) {
        assertTrue(
            "期望至少 $minCount 条数据，实际: ${data.size}",
            data.size >= minCount
        )
    }
}