package com.chin.stockanalysis.strategy.trade

/**
 * ## 共用交易决策辅助类
 *
 * 短线量化和中线量化共用的卖出/买入判断逻辑
 */
object TradeDecisionHelper {

    /**
     * 判断是否应该卖出某只股票
     * @param stockCode 股票代码
     * @param buyPrice 买入价格
     * @param currentPrice 当前价格
     * @param holdDays 持有天数
     * @return 卖出原因（null 表示不卖出）
     */
    fun shouldSell(stockCode: String, buyPrice: Double, currentPrice: Double, holdDays: Int): String? {
        if (buyPrice <= 0 || currentPrice <= 0) return null
        val profitPct = (currentPrice - buyPrice) / buyPrice * 100

        // 止损：亏损超过 8%
        if (profitPct < -8.0) return "止损：亏损 ${"%.1f".format(profitPct)}%"
        // 止盈：盈利超过 15%
        if (profitPct > 15.0) return "止盈：盈利 ${"%.1f".format(profitPct)}%"
        // 超时：持有超过 5 天且盈利 < 3%
        if (holdDays > 5 && profitPct < 3.0) return "超时：持有 $holdDays 天，盈利不足"

        return null
    }

    /**
     * 判断是否应该买入某只股票
     * @param stockCode 股票代码
     * @param currentPrice 当前价格
     * @param changePct 当日涨幅
     * @param volumeRatio 量比
     * @param score 综合评分
     * @return 买入原因（null 表示不买入）
     */
    fun shouldBuy(stockCode: String, currentPrice: Double, changePct: Double, volumeRatio: Double, score: Float): String? {
        if (currentPrice <= 0) return null

        // 高分股票直接买入
        if (score >= 80f) return "高分买入：评分 $score"
        // 放量上涨
        if (changePct > 3.0 && volumeRatio > 1.5) return "放量上涨：涨幅 ${"%.1f".format(changePct)}%，量比 $volumeRatio"
        // 回调买入
        if (changePct < -3.0 && score >= 60f) return "回调买入：评分 $score"

        return null
    }

    /**
     * 腾龙换鸟判断：比较现有持仓最弱和新选股最强
     * @param weakestScore 现有持仓最弱评分
     * @param strongestScore 新选股最强评分
     * @param weakestName 现有持仓最弱名称
     * @param strongestName 新选股最强名称
     * @return 换股建议（null 表示不换）
     */
    fun shouldSwap(weakestScore: Int, strongestScore: Float, weakestName: String, strongestName: String): String? {
        if (strongestScore > weakestScore * 1.3f) {
            return "腾龙换鸟：卖出 $weakestName(${weakestScore}分) → 买入 $strongestName(${strongestScore}分)"
        }
        return null
    }
}
