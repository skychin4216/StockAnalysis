package com.chin.stockanalysis.strategy.chanlun

/**
 * ## 缠论引擎（APP 本地简化版）
 *
 * 外出场景下手机离线可用：分型 → 笔 → 中枢 → 买卖点（一/二/三买卖 + MACD 背驰）。
 * 与 PC 端 chan.py 口径对齐的实用简化：
 *  - 包含处理：方向敏感合并（向上取高高、向下取低低）
 *  - 笔：顶底分型严格交替，间隔 ≥4 根合并K线（chan.py 新笔标准）
 *  - 中枢：连续 3 笔重叠区间 [max(低点), min(高点)]，后续笔有交集则延续
 *  - 一买：两段下跌笔底背驰（后段 MACD 绿柱面积缩小且创新低）
 *  - 二买：一买后首次回调不破一买低点；三买：突破中枢后回抽不破 ZG
 *
 * 输出字段与 PC 端 `_chanlun_engine.py` 的 JSON 契约一致（渲染器复用）。
 */
object ChanlunEngine {

    data class Bar(val time: String, val open: Double, val high: Double,
                   val low: Double, val close: Double, val volume: Double = 0.0)

    /** 合并K线（包含处理后） */
    data class Merged(val high: Double, val low: Double,
                      val beginIdx: Int, val endIdx: Int, val time: String)

    enum class FxType { TOP, BOTTOM }
    /** 分型：mergedIdx 指向合并K线序列下标 */
    data class Fractal(val type: FxType, val mergedIdx: Int, val price: Double, val time: String)

    enum class Dir { UP, DOWN }
    /** 笔：beginIdx/endIdx 为原始K线下标（与输入 bars 对齐） */
    data class Bi(val dir: Dir, val beginPrice: Double, val endPrice: Double,
                  val beginIdx: Int, val endIdx: Int,
                  val beginTime: String, val endTime: String, val sure: Boolean)

    /** 中枢 */
    data class Zs(val low: Double, val high: Double,
                  val beginIdx: Int, val endIdx: Int,
                  val beginTime: String, val endTime: String)

    /** 买卖点：type ∈ {1,2,3a,3b}（3a=中枢上回抽 3b=类三买） */
    data class Bsp(val isBuy: Boolean, val type: String, val price: Double,
                   val idx: Int, val time: String, val sure: Boolean)

    data class Structure(
        val bars: List<Bar>,
        val merged: List<Merged>,
        val bi: List<Bi>,
        val zs: List<Zs>,
        val bsp: List<Bsp>,
        /** 分型序列（逐K回放用：顶/底分型的生成过程） */
        val fx: List<Fractal> = emptyList(),
    )

    // ═══════════════════ 1. 包含处理 ═══════════════════

    fun merge(bars: List<Bar>): List<Merged> {
        if (bars.isEmpty()) return emptyList()
        val out = ArrayList<Merged>(bars.size / 2)
        var cur = Merged(bars[0].high, bars[0].low, 0, 0, bars[0].time)
        var up = true   // 当前合并方向（初始假设向上，第二根起按实际）
        for (i in 1 until bars.size) {
            val b = bars[i]
            val contained = (b.high <= cur.high && b.low >= cur.low) || (b.high >= cur.high && b.low <= cur.low)
            if (!contained) {
                up = b.high > cur.high
                out.add(cur)
                cur = Merged(b.high, b.low, i, i, b.time)
            } else {
                // 有包含关系：按方向取极值
                val nh = if (up) maxOf(cur.high, b.high) else cur.high
                val nl = if (up) cur.low else minOf(cur.low, b.low)
                val nh2 = if (!up) minOf(cur.high, b.high) else nh
                val nl2 = if (up) maxOf(cur.low, b.low) else nl
                cur = Merged(nh2, nl2, cur.beginIdx, i, b.time)
            }
        }
        out.add(cur)
        return out
    }

    // ═══════════════════ 2. 分型 ═══════════════════

    fun fractals(merged: List<Merged>): List<Fractal> {
        val out = ArrayList<Fractal>()
        for (i in 1 until merged.size - 1) {
            val p = merged[i - 1]; val c = merged[i]; val nx = merged[i + 1]
            if (c.high > p.high && c.high > nx.high && c.low > p.low && c.low > nx.low) {
                out.add(Fractal(FxType.TOP, i, c.high, c.time))
            } else if (c.low < p.low && c.low < nx.low && c.high < p.high && c.high < nx.high) {
                out.add(Fractal(FxType.BOTTOM, i, c.low, c.time))
            }
        }
        return out
    }

    // ═══════════════════ 3. 笔 ═══════════════════

    /** 顶底交替 + 间隔≥4根合并K线；同向取更极端分型。 */
    fun buildBi(merged: List<Merged>, fxs: List<Fractal>): List<Bi> {
        if (fxs.isEmpty()) return emptyList()
        val picked = ArrayList<Fractal>()
        for (fx in fxs) {
            if (picked.isEmpty()) { picked.add(fx); continue }
            val last = picked.last()
            when {
                // 异向：间隔够才成立新笔端点
                last.type != fx.type -> {
                    if (fx.mergedIdx - last.mergedIdx >= 4) picked.add(fx)
                    else if (fx.type == FxType.TOP && fx.price > last.price) { /* 距离不足忽略 */ }
                    else if (fx.type == FxType.BOTTOM && fx.price < last.price) { /* 同上 */ }
                    // 距离不足的异向分型：若更极端则也忽略（保持交替由后续分型决定）
                }
                // 同向：取更极端者替换
                fx.type == FxType.TOP && fx.price > last.price -> picked[picked.size - 1] = fx
                fx.type == FxType.BOTTOM && fx.price < last.price -> picked[picked.size - 1] = fx
            }
        }
        val out = ArrayList<Bi>()
        for (i in 1 until picked.size) {
            val a = picked[i - 1]; val b = picked[i]
            val dir = if (a.type == FxType.BOTTOM) Dir.UP else Dir.DOWN
            val sure = i < picked.size - 1   // 最后一笔可能延续 = 虚笔
            out.add(Bi(dir, a.price, b.price,
                merged[a.mergedIdx].run { if (dir == Dir.UP) endIdx else endIdx },
                merged[b.mergedIdx].endIdx,
                a.time, b.time, sure))
        }
        return out
    }

    // ═══════════════════ 4. 中枢 ═══════════════════

    /** 连续 3 笔重叠成中枢；后续笔与中枢有交集则延伸 endIdx。 */
    fun buildZs(bi: List<Bi>): List<Zs> {
        if (bi.size < 3) return emptyList()
        val out = ArrayList<Zs>()
        var i = 0
        while (i + 2 < bi.size) {
            val b1 = bi[i]; val b2 = bi[i + 1]; val b3 = bi[i + 2]
            // 三笔的价格区间（每笔 [min(begin,end), max(begin,end)]）
            fun range(b: Bi) = minOf(b.beginPrice, b.endPrice) to maxOf(b.beginPrice, b.endPrice)
            val (l1, h1) = range(b1); val (l2, h2) = range(b2); val (l3, h3) = range(b3)
            val zd = maxOf(l1, l2, l3); val zg = minOf(h1, h2, h3)
            if (zd < zg) {
                // 成中枢，尝试延伸
                var endIdx = b3.endIdx; var endTime = b3.endTime
                var j = i + 3
                while (j < bi.size) {
                    val (lj, hj) = range(bi[j])
                    if (hj >= zd && lj <= zg) { endIdx = bi[j].endIdx; endTime = bi[j].endTime; j++ }
                    else break
                }
                out.add(Zs(zd, zg, b1.beginIdx, endIdx, b1.beginTime, endTime))
                i = j
            } else i++
        }
        return out
    }

    // ═══════════════════ 5. MACD（背驰用） ═══════════════════

    /** 返回 (dif, dea, hist)；柱 = 2*(dif-dea)，负值面积为下跌动能。 */
    fun macd(closes: DoubleArray): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val n = closes.size
        val dif = DoubleArray(n); val dea = DoubleArray(n); val hist = DoubleArray(n)
        if (n == 0) return Triple(dif, dea, hist)
        var e12 = closes[0]; var e26 = closes[0]
        for (i in closes.indices) {
            e12 += (closes[i] - e12) * 2.0 / 13.0
            e26 += (closes[i] - e26) * 2.0 / 27.0
            dif[i] = e12 - e26
            dea[i] = if (i == 0) dif[0] else dea[i - 1] + (dif[i] - dea[i - 1]) * 2.0 / 10.0
            hist[i] = 2 * (dif[i] - dea[i])
        }
        return Triple(dif, dea, hist)
    }

    /** 笔区间内的 MACD 动能面积：下跌笔取负柱绝对值和，上涨笔取正柱和。 */
    private fun biArea(hist: DoubleArray, b: Bi): Double {
        val from = b.beginIdx.coerceIn(0, hist.size - 1)
        val to = b.endIdx.coerceIn(0, hist.size - 1)
        var s = 0.0
        for (i in from..to) {
            s += if (b.dir == Dir.DOWN) maxOf(0.0, -hist[i]) else maxOf(0.0, hist[i])
        }
        return s
    }

    // ═══════════════════ 6. 买卖点 ═══════════════════

    fun buildBsp(bars: List<Bar>, bi: List<Bi>, zs: List<Zs>): List<Bsp> {
        if (bi.size < 3) return emptyList()
        val closes = DoubleArray(bars.size) { bars[it].close }
        val (_, _, hist) = macd(closes)
        val out = ArrayList<Bsp>()

        for (i in 2 until bi.size) {
            val cur = bi[i]
            val prev = bi[i - 1]
            // 找同向前笔（隔一根）
            val prevSame = bi[i - 2]
            val curSure = cur.sure

            if (cur.dir == Dir.DOWN && prevSame.dir == Dir.DOWN) {
                // ── 一买：底背驰（后段下跌面积缩小 + 价格创新低） ──
                val a1 = biArea(hist, prevSame); val a2 = biArea(hist, cur)
                if (cur.endPrice < prevSame.endPrice && a2 < a1 * 0.9) {
                    // 同一轮下跌段内只保留最新的一买：前面的背驰已被后续新低破坏
                    out.removeAll { it.isBuy && it.type == "1" && it.idx > prevSame.beginIdx }
                    out.add(Bsp(true, "1", cur.endPrice, cur.endIdx, cur.endTime, curSure))
                }
            }
            if (cur.dir == Dir.UP && prevSame.dir == Dir.UP) {
                // ── 一卖：顶背驰 ──
                val a1 = biArea(hist, prevSame); val a2 = biArea(hist, cur)
                if (cur.endPrice > prevSame.endPrice && a2 < a1 * 0.9) {
                    // 同一轮上涨段内只保留最新的一卖：前面的背驰已被后续新高破坏
                    out.removeAll { !it.isBuy && it.type == "1" && it.idx > prevSame.beginIdx }
                    out.add(Bsp(false, "1", cur.endPrice, cur.endIdx, cur.endTime, curSure))
                }
            }
            // ── 二买：一买之后的首根下跌笔，低点 > 一买低点 ──
            val lastBuy1 = out.lastOrNull { it.isBuy && it.type == "1" }
            if (cur.dir == Dir.DOWN && lastBuy1 != null && cur.beginIdx > lastBuy1.idx
                && cur.endPrice > lastBuy1.price
                && out.none { it.type == "2" && it.isBuy && it.idx > lastBuy1.idx }) {
                out.add(Bsp(true, "2", cur.endPrice, cur.endIdx, cur.endTime, curSure))
            }
            // ── 二卖：镜像 ──
            val lastSell1 = out.lastOrNull { !it.isBuy && it.type == "1" }
            if (cur.dir == Dir.UP && lastSell1 != null && cur.beginIdx > lastSell1.idx
                && cur.endPrice < lastSell1.price
                && out.none { it.type == "2" && !it.isBuy && it.idx > lastSell1.idx }) {
                out.add(Bsp(false, "2", cur.endPrice, cur.endIdx, cur.endTime, curSure))
            }
            // ── 三买：向下笔回抽，低点 > 最近中枢 ZG，且起点在中枢上方 ──
            if (cur.dir == Dir.DOWN) {
                val z = zs.lastOrNull { it.endIdx <= cur.beginIdx }
                if (z != null && cur.beginPrice > z.high && cur.endPrice > z.high
                    && out.none { it.type.startsWith("3") && it.isBuy && it.idx > z.endIdx }) {
                    out.add(Bsp(true, "3a", cur.endPrice, cur.endIdx, cur.endTime, curSure))
                }
            }
            // ── 三卖：镜像 ──
            if (cur.dir == Dir.UP) {
                val z = zs.lastOrNull { it.endIdx <= cur.beginIdx }
                if (z != null && cur.beginPrice < z.low && cur.endPrice < z.low
                    && out.none { it.type.startsWith("3") && !it.isBuy && it.idx > z.endIdx }) {
                    out.add(Bsp(false, "3a", cur.endPrice, cur.endIdx, cur.endTime, curSure))
                }
            }
        }
        return out
    }

    // ═══════════════════ 入口 ═══════════════════

    fun compute(bars: List<Bar>): Structure {
        val m = merge(bars)
        val fx = fractals(m)
        val bi = buildBi(m, fx)
        val zs = buildZs(bi)
        val bsp = buildBsp(bars, bi, zs)
        return Structure(bars, m, bi, zs, bsp, fx)
    }
}
