package com.chin.stockanalysis.strategy.chanlun

/**
 * 缠论 3 情景预测（2026-10-23 从 ChanlunFragment 抽取，供预测页与滚动回测共用）。
 * 每个情景带方向标签 dir，滚动验证时直接比对方向，不解析文本。
 */
object ChanlunPredictor {

    enum class Dir3 { UP, DOWN, RANGE }

    data class Scenario(
        val name: String, val prob: Int,
        val targetText: String,       // 展示用（"19.32" 或 "19.20~21.05"）
        val target: Double?,          // 数值目标（区间情景为 null）
        val dir: Dir3,
        val desc: String
    )

    private fun f2(v: Double) = "%.2f".format(v)

    /** 基于当前结构推演 3 情景（延续/转折/盘整） */
    fun predict(st: ChanlunEngine.Structure): List<Scenario> {
        val bi = st.bi.lastOrNull()
            ?: return listOf(Scenario("数据不足", 0, "-", null, Dir3.RANGE, ""))
        // 基准必须用最新收盘价：最后一笔的终点价在盘中/新K线后天然滞后
        val cur = st.bars.lastOrNull()?.close ?: bi.endPrice
        val up = bi.dir == ChanlunEngine.Dir.UP
        val z = st.zs.lastOrNull()
        val out = ArrayList<Scenario>()

        // 方向保护：确保目标价与情景的涨跌方向一致
        fun dn(t: Double) = f2(if (t < cur) t else cur * 0.97)
        fun upT(t: Double) = f2(if (t > cur) t else cur * 1.03)
        fun dnV(t: Double) = if (t < cur) t else cur * 0.97
        fun upV(t: Double) = if (t > cur) t else cur * 1.03

        if (z == null) {
            if (up) {
                out.add(Scenario("延续上涨", 55, upT(cur * 1.03), upV(cur * 1.03), Dir3.UP, "无中枢，沿最后上涨笔方向"))
                out.add(Scenario("转折下跌", 45, dn(bi.beginPrice), dnV(bi.beginPrice), Dir3.DOWN, "回撤至笔起点"))
            } else {
                out.add(Scenario("延续下跌", 55, dn(cur * 0.97), dnV(cur * 0.97), Dir3.DOWN, "无中枢，沿最后下跌笔方向"))
                out.add(Scenario("转折反弹", 45, upT(bi.beginPrice), upV(bi.beginPrice), Dir3.UP, "反弹至笔起点"))
            }
        } else {
            val zw = (z.high - z.low).coerceAtLeast(cur * 0.01)
            when {
                cur > z.high -> {
                    out.add(Scenario("延续上涨", 45, upT(cur + zw), upV(cur + zw), Dir3.UP, "中枢上方，等幅延续"))
                    out.add(Scenario("回落中枢", 35, dn(z.high), dnV(z.high), Dir3.DOWN, "回抽中枢上沿"))
                    out.add(Scenario("跌破中枢", 20, dn(z.low), dnV(z.low), Dir3.DOWN, "弱势回穿"))
                }
                cur < z.low -> {
                    out.add(Scenario("延续下跌", 45, dn(cur - zw), dnV(cur - zw), Dir3.DOWN, "中枢下方，等幅延续"))
                    out.add(Scenario("反弹回中枢", 35, upT(z.low), upV(z.low), Dir3.UP, "回抽中枢下沿"))
                    out.add(Scenario("反转站上", 20, upT(z.high), upV(z.high), Dir3.UP, "强势反转"))
                }
                else -> {
                    out.add(Scenario("中枢内盘整", 50, "${f2(z.low)}~${f2(z.high)}", null, Dir3.RANGE, "中枢内震荡"))
                    out.add(Scenario("向上突破", 25, upT(z.high + zw), upV(z.high + zw), Dir3.UP, "突破中枢上沿等幅"))
                    out.add(Scenario("向下突破", 25, dn(z.low - zw), dnV(z.low - zw), Dir3.DOWN, "跌破中枢下沿等幅"))
                }
            }
        }
        if (!bi.sure && out.isNotEmpty()) {
            val s = out[0]
            out[0] = Scenario(s.name, maxOf(10, s.prob - 10), s.targetText, s.target, s.dir, s.desc + "（虚笔，置信降）")
        }
        return out
    }
}
