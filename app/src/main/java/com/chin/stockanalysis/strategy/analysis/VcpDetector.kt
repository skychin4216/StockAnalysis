package com.chin.stockanalysis.strategy.analysis

/**
 * ## VCP 波动收缩形态检测（Volatility Contraction Pattern, Mark Minervini）
 *
 * 纯算法、无 Android 依赖，供量价页/选股管线复用。
 *
 * 判定四要素（基于 zigzag 波段链）：
 *  1. 回落深度逐级收缩：d_{i+1} ≤ 0.75 × d_i，至少 2 次收缩
 *  2. 高点走平/微降：各反弹高点与锚点 H0 偏差 ≤ 5%（不允许大幅新高）
 *  3. 低点递升：L_{i+1} > L_i
 *  4. 量能萎缩：回落段日均量递减，末段 ≤ 首段 0.7；base 整体均量 < 前导上升段均量
 *
 * 突破判定：收盘 > 压力×1.01 且当日量 > base 日均量×1.5 → 放量突破（买点）；
 * 收盘在压力下方 3% 内且末次收缩 ≤ 8% → 口袋紧缩（临突破）；其余 = 构建中。
 *
 * 输入为轻量 data class，调用方（Fragment）负责从自己的 KBar/Pivot/Leg 转换。
 */
object VcpDetector {

    /** 最小输入：日期 + 收盘 + 成交量（手） */
    data class Bar(val date: String, val close: Double, val vol: Long)
    data class Pivot(val idx: Int, val price: Double, val isHigh: Boolean, val confirmed: Boolean)
    data class Leg(val up: Boolean, val chgPct: Double, val volAvg: Double)

    enum class Status { BREAKOUT, FORMING, INVALID }

    /** 一次收缩：回落深度% + 该回落段日均量(万股) + 低点日期 */
    data class Contraction(val depthPct: Double, val downVolAvg: Double, val lowDate: String)

    /** 收缩链上的关键点（供主图画楔形轮廓）：H0, L1, H1, L2, ... */
    data class WPoint(val idx: Int, val price: Double, val isHigh: Boolean)

    data class Result(
        val status: Status,
        val reason: String,
        val anchorIdx: Int,          // H0 在 bars 中的下标（-1=无有效锚点）
        val anchorDate: String,
        val resistance: Double,      // 压力位 = max(H0, 各反弹高点)
        val contractions: List<Contraction>,
        val wedge: List<WPoint>,     // 收缩链关键点（仅成立/构建中时有值）
        val baseVolAvg: Double,      // base 区间日均量（万股）
        val leadUpVolAvg: Double,    // 前导上升段日均量（万股）
        val lastVol: Double,         // 最新一根量（万股）
        val volMult: Double,         // 最新量 / base 日均量
        val distToResistPct: Double, // 现价距压力位 %（>0 未突破）
        val score: Int,              // 0~100
        val tailUnconfirmed: Boolean // 末端拐点未确认（形态可能继续演化）
    )

    // ── 参数（先写死，后续需要再抽 UI） ──
    private const val MIN_LEAD_UP_PCT = 15.0   // 前导上升段最小涨幅%
    private const val CONTRACTION_RATIO = 0.75 // 相邻收缩深度递减比
    private const val HIGH_TOLERANCE = 0.05    // 反弹高点与 H0 允许偏差
    private const val MIN_CONTRACTIONS = 2
    private const val MAX_CONTRACTIONS = 4
    private const val VOL_DRY_RATIO = 0.7      // 末段量 ≤ 首段 ×0.7
    private const val BREAKOUT_VOL_MULT = 1.5  // 突破量倍数
    private const val BREAKOUT_MARGIN = 1.01   // 收盘 > 压力×1.01 算突破
    private const val NEAR_PCT = 3.0           // 距压力 3% 内 = 临突破
    private const val TIGHT_LAST_PCT = 8.0     // 末次收缩 ≤8% = 紧致

    /** 单锚点版本：从最近锚点往前找，返回第一条成立的链；都不成立则返回最近锚点的失败原因（供卡片展示） */
    fun detect(bars: List<Bar>, pivots: List<Pivot>, legs: List<Leg>): Result {
        if (bars.size < 30) return invalidResult(bars, "K线不足（${bars.size} 根）")
        if (pivots.size < 4) return invalidResult(bars, "波段数不足（${pivots.size} 个拐点），无法构成收缩链")
        var firstInvalid: Result? = null
        for (j in pivots.size - 3 downTo 1) {   // 至少还需要 L1,H1,L2 三个后续 pivot（最后一次收缩的低点）
            val p = pivots[j]
            if (!p.isHigh) continue
            val lead = legs.getOrNull(j - 1) ?: continue
            if (!(lead.up && lead.chgPct >= MIN_LEAD_UP_PCT)) continue
            val r = evalAnchor(bars, pivots, legs, j)
            if (r.status != Status.INVALID) return r
            if (firstInvalid == null) firstInvalid = r
        }
        return firstInvalid
            ?: invalidResult(bars, "未找到锚点：最近没有「涨幅≥${MIN_LEAD_UP_PCT.toInt()}% 的上升段」之后的高点")
    }

    /**
     * 多锚点扫描：遍历全部合格锚点（前导上升 ≥15% 的高点），返回所有成立的收缩链。
     * 大 base 内常嵌套小 VCP，单锚点只能看到最新一条。
     * 仅保留楔形末端距今 ≤ maxEndAgeBars 根的形态（早已完成、价格跑远的老形态不返回）。
     * 结果按锚点从近到远排序，first = 卡片/主压力线用的主形态。
     */
    fun detectAll(bars: List<Bar>, pivots: List<Pivot>, legs: List<Leg>, maxEndAgeBars: Int = 60): List<Result> {
        if (bars.size < 30 || pivots.size < 4) return emptyList()
        val out = ArrayList<Result>()
        for (j in pivots.size - 3 downTo 1) {
            val p = pivots[j]
            if (!p.isHigh) continue
            val lead = legs.getOrNull(j - 1) ?: continue
            if (!(lead.up && lead.chgPct >= MIN_LEAD_UP_PCT)) continue
            val r = evalAnchor(bars, pivots, legs, j)
            if (r.status == Status.INVALID) continue
            if (bars.size - 1 - (r.wedge.lastOrNull()?.idx ?: 0) > maxEndAgeBars) continue
            out.add(r)
        }
        return out
    }

    private fun invalidResult(bars: List<Bar>, reason: String) =
        Result(Status.INVALID, reason, -1, "", 0.0, emptyList(), emptyList(), 0.0, 0.0,
            bars.lastOrNull()?.vol?.div(10000.0) ?: 0.0, 0.0, 0.0, 0, false)

    /** 评估单个锚点 H0=pivots[anchorJ]：收缩链 + 量能 + 突破判定；anchor 是 pivots[j]（isHigh），其后 pivots[j+1]=L1, j+2=H1 ... 每个 low 产生一次收缩 */
    private fun evalAnchor(bars: List<Bar>, pivots: List<Pivot>, legs: List<Leg>, anchorJ: Int): Result {
        val h0 = pivots[anchorJ]
        val tail = pivots.subList(anchorJ + 1, pivots.size)
        if (tail.size < 3) return invalidResult(bars, "锚点 ${bars[h0.idx].date} 之后波段太少（${tail.size} 个），收缩链尚未形成")

        // ── ② 收缩链：每个 low pivot 产生一次回落深度 d=(前高-低)/前高 ──
        data class C(val depth: Double, val lowIdx: Int, val lowPivotIdx: Int, val downLegIdx: Int)
        val cs = ArrayList<C>()
        val wedge = ArrayList<WPoint>()
        wedge.add(WPoint(h0.idx, h0.price, true))
        var prevHigh = h0.price
        var prevHighIdx = h0.idx
        var k = 0
        while (k < tail.size && cs.size < MAX_CONTRACTIONS) {
            val low = tail[k]
            if (low.isHigh) break // 序列必须低-高交替，第一个是 low
            val depth = (prevHigh - low.price) / prevHigh * 100.0
            if (depth <= 0.3) break // 深度过小说明 pivot 序列异常
            // 该回落段 = legs[anchorJ + k]（pivot[anchorJ]→pivot[anchorJ+1+k]）
            cs.add(C(depth, low.idx, anchorJ + 1 + k, anchorJ + k))
            wedge.add(WPoint(low.idx, low.price, false))
            k++
            if (k >= tail.size) break
            val hi = tail[k]
            if (!hi.isHigh) break
            // 高点约束：与前高偏差 ≤5%（允许微降，不允许明显新高）
            if (hi.price > prevHigh * (1 + HIGH_TOLERANCE) ||
                hi.price < prevHigh * (1 - HIGH_TOLERANCE) * 0.85 // 反弹明显无力（低于前高 8.5% 以上）也视为结构走坏
            ) break
            prevHigh = hi.price
            prevHighIdx = hi.idx
            wedge.add(WPoint(hi.idx, hi.price, true))
            k++
        }
        if (cs.size < MIN_CONTRACTIONS)
            return invalidResult(bars, "锚点 ${bars[h0.idx].date} 后仅 ${cs.size} 次有效收缩（需≥$MIN_CONTRACTIONS）")

        // ── ③ 收缩递减校验 ──
        for (i in 1 until cs.size) {
            if (cs[i].depth >= cs[i - 1].depth)
                return invalidResult(bars, "第${i + 1}次收缩（%.1f%%）未小于第${i}次（%.1f%%），波动未收敛".format(cs[i].depth, cs[i - 1].depth))
            if (cs[i].depth > cs[i - 1].depth * CONTRACTION_RATIO)
                return invalidResult(bars, "收缩递减不足：%.1f%% → %.1f%%（需 ≤ 前次 %.0f%%）".format(cs[i - 1].depth, cs[i].depth, CONTRACTION_RATIO * 100))
        }

        // ── ④ 低点递升校验 ──
        for (i in 1 until cs.size) {
            if (bars[cs[i].lowIdx].close >= bars[cs[i - 1].lowIdx].close)
                return invalidResult(bars, "低点未递升：${bars[cs[i - 1].lowIdx].date} %.2f → ${bars[cs[i].lowIdx].date} %.2f".format(
                    bars[cs[i - 1].lowIdx].close, bars[cs[i].lowIdx].close))
        }

        // ── ⑤ 量能萎缩：回落段日均量递减，末段 ≤ 首段×0.7；base 均量 < 前导上升段均量 ──
        val downVols = cs.map { legs.getOrNull(it.downLegIdx)?.volAvg ?: 0.0 }
        if (downVols.any { it <= 0 }) return invalidResult(bars, "回落段量能数据异常")
        val reasons = StringBuilder()
        for (i in 1 until downVols.size) {
            if (downVols[i] > downVols[i - 1] * 1.05)
                reasons.append("第${i + 1}次回落放量（%.2f→%.2f 万手）；".format(downVols[i - 1], downVols[i]))
        }
        val volDry = downVols.last() / downVols.first()
        if (volDry > VOL_DRY_RATIO)
            reasons.append("末段量缩不足（%.0f%%，需≤%.0f%%）；".format(volDry * 100, VOL_DRY_RATIO * 100))
        val leadUpVol = legs.getOrNull(anchorJ - 1)?.volAvg ?: 0.0
        val baseLegs = legs.subList(anchorJ, legs.size.coerceAtMost(anchorJ + 1 + 2 * cs.size))
        val baseVolAvg = if (baseLegs.isEmpty()) 0.0 else baseLegs.sumOf { it.volAvg } / baseLegs.size
        if (leadUpVol > 0 && baseVolAvg > leadUpVol)
            reasons.append("base 均量（%.2f）高于前导上升段（%.2f）；".format(baseVolAvg, leadUpVol))
        if (reasons.isNotEmpty())
            return invalidResult(bars, "量能未萎缩：$reasons".removeSuffix("；"))

        // ── ⑥ 突破/临突破判定 ──
        val resistance = maxOf(h0.price, prevHigh)
        val lastClose = bars.last().close
        val lastVol = bars.last().vol / 10000.0
        val volMult = if (baseVolAvg > 0) lastVol / baseVolAvg else 0.0
        val distPct = (resistance - lastClose) / resistance * 100.0
        val lastDepth = cs.last().depth
        val tailUnconfirmed = pivots.last().let { !it.confirmed }

        val status: Status
        val reason: String
        when {
            lastClose > resistance * BREAKOUT_MARGIN && volMult >= BREAKOUT_VOL_MULT -> {
                status = Status.BREAKOUT
                reason = "收盘 %.2f 放量突破压力 %.2f（量 %.1f×base均量）".format(lastClose, resistance, volMult)
            }
            distPct in 0.0..NEAR_PCT && lastDepth <= TIGHT_LAST_PCT -> {
                status = Status.FORMING
                reason = "口袋紧缩：末次收缩 %.1f%%，现价距压力仅 %.1f%%，等放量突破".format(lastDepth, distPct)
            }
            else -> {
                status = Status.FORMING
                reason = "收缩链成立（${cs.size} 次），现价距压力 %.1f%%，构建中".format(distPct)
            }
        }

        // ── ⑦ 评分：收缩次数 + 紧致度 + 量缩 + 位置 ──
        var score = when (cs.size) { 2 -> 20; 3 -> 30; else -> 40 }
        score += when { lastDepth <= 3 -> 25; lastDepth <= 5 -> 20; lastDepth <= 8 -> 15; else -> 5 }
        score += when { volDry <= 0.4 -> 20; volDry <= 0.55 -> 15; volDry <= 0.7 -> 12; else -> 5 }
        score += when {
            status == Status.BREAKOUT -> 15
            distPct <= NEAR_PCT -> 15
            distPct <= 8.0 -> 10
            else -> 5
        }
        score = score.coerceIn(0, 100)

        return Result(
            status = status, reason = reason,
            anchorIdx = h0.idx, anchorDate = bars[h0.idx].date,
            resistance = resistance,
            contractions = cs.map { Contraction(it.depth, legs[it.downLegIdx].volAvg, bars[it.lowIdx].date) },
            wedge = wedge,
            baseVolAvg = baseVolAvg, leadUpVolAvg = leadUpVol,
            lastVol = lastVol, volMult = volMult,
            distToResistPct = distPct, score = score,
            tailUnconfirmed = tailUnconfirmed
        )
    }
}
