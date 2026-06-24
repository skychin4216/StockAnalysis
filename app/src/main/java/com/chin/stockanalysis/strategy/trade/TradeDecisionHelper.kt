package com.chin.stockanalysis.strategy.trade

/**
 * ## 共用交易決策輔助類
 *
 * 短線量化和中線量化共用的賣出/買入判斷邏輯
 */
object TradeDecisionHelper {

    /**
     * 判斷是否應該賣出某只股票
     * @param stockCode 股票代碼
     * @param buyPrice 買入價格
     * @param currentPrice 當前價格
     * @param holdDays 持有天數
     * @return 賣出原因（null 表示不賣出）
     */
    fun shouldSell(stockCode: String, buyPrice: Double, currentPrice: Double, holdDays: Int): String? {
        if (buyPrice <= 0 || currentPrice <= 0) return null
        val profitPct = (currentPrice - buyPrice) / buyPrice * 100

        // 止損：虧損超過 8%
        if (profitPct < -8.0) return "止損：虧損 ${"%.1f".format(profitPct)}%"
        // 止盈：盈利超過 15%
        if (profitPct > 15.0) return "止盈：盈利 ${"%.1f".format(profitPct)}%"
        // 超時：持有超過 5 天且盈利 < 3%
        if (holdDays > 5 && profitPct < 3.0) return "超時：持有 $holdDays 天，盈利不足"

        return null
    }

    /**
     * 判斷是否應該買入某只股票
     * @param stockCode 股票代碼
     * @param currentPrice 當前價格
     * @param changePct 當日漲幅
     * @param volumeRatio 量比
     * @param score 綜合評分
     * @return 買入原因（null 表示不買入）
     */
    fun shouldBuy(stockCode: String, currentPrice: Double, changePct: Double, volumeRatio: Double, score: Float): String? {
        if (currentPrice <= 0) return null

        // 高分股票直接買入
        if (score >= 80f) return "高分買入：評分 $score"
        // 放量上漲
        if (changePct > 3.0 && volumeRatio > 1.5) return "放量上漲：漲幅 ${"%.1f".format(changePct)}%，量比 $volumeRatio"
        // 回調買入
        if (changePct < -3.0 && score >= 60f) return "回調買入：評分 $score"

        return null
    }

    /**
     * 騰龍換鳥判斷：比較現有持倉最弱和新選股最強
     * @param weakestScore 現有持倉最弱評分
     * @param strongestScore 新選股最強評分
     * @param weakestName 現有持倉最弱名稱
     * @param strongestName 新選股最強名稱
     * @return 換股建議（null 表示不換）
     */
    fun shouldSwap(weakestScore: Int, strongestScore: Float, weakestName: String, strongestName: String): String? {
        if (strongestScore > weakestScore * 1.3f) {
            return "騰龍換鳥：賣出 $weakestName(${weakestScore}分) → 買入 $strongestName(${strongestScore}分)"
        }
        return null
    }
}
