package com.chin.stockanalysis.strategy.gsp

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * ## 板块共振编排引擎（方案一 ETF/锚定网络 + 方案二 龙头驱动扩散）
 *
 * 输入：板块成分股日线收盘 + 大盘基准日线收盘
 * 输出：板块共振状态（萌芽/共振/分化/衰竭）、扩散衰减 τ、补涨候选排序、龙头与扩散类型
 *
 * 说明：文档中的「板块 ETF 锚节点」在本工程用**板块市值加权综合指数**替代
 * （成分股收盘按市值加权合成），避免依赖 ETF 映射表；其余算法完全按文档 5.1~5.7。
 */
object SectorResonanceEngine {

    enum class BoardState(val label: String) {
        BOOTING("萌芽"),
        RESONATING("共振"),
        DIVERGING("分化"),
        DECAYING("衰竭")
    }

    data class Stock(val code: String, val name: String, val close: DoubleArray, val marketCap: Double = 0.0)

    data class Candidate(
        val code: String,
        val name: String,
        val price: Double,
        val changePct: Double,
        /** 共模跟随度：最近 20 日共模分量的日均斜率（%） */
        val commonFollow: Double,
        /** 残差未兑现度：残差 20 日累计的历史分位（越小越未兑现） */
        val specPercentile: Double,
        /** 价格位置：20 日区间内的相对位置 0=最低 1=最高 */
        val position: Double,
        val score: Double,
        val relayScore: Double,
        val energy: Double,
        val tag: String
    )

    data class Result(
        val boardCode: String,
        val boardName: String,
        val analyzedAt: String,
        val state: BoardState,
        val tau: Double,
        val lambdaMax: Double,
        val breadth: Double,
        val anchorSlope: Double,
        val anchorTrendUp: Boolean,
        val nStocks: Int,
        val nBars: Int,
        val dates: List<String>,
        val anchorNorm: List<Double>,
        val anchorEma: List<Double>,
        val candidates: List<Candidate>,   // 原始顺序（热力图索引对应）
        val ranked: List<Candidate>,       // 方案一：补涨排序
        val relay: List<Candidate>,        // 方案二：接力排序
        val leaderCode: String?,
        val leaderName: String?,
        /** 龙头判定口径：0=拐点型；1=动量型 */
        val leaderMode: Int,
        val diffusion: GspEngine.DiffusionType?,
        val edges: List<Triple<Int, Int, Double>>
    )

    /**
     * @param window   邻接矩阵相关性窗口（日）
     * @param order    切比雪夫阶数 4~6
     * @param diffuseT 热核扩散时间 t（方案二超参）
     * @param lookback 参与计算的最近 K 线根数
     * @param leaderMode 龙头判定：0=拐点型（度中心 + 底部拐点）；1=动量型（度中心 + 近 5 日涨幅）
     */
    fun analyze(
        boardCode: String,
        boardName: String,
        stocks: List<Stock>,
        benchmarkClose: DoubleArray,
        dates: List<String>,
        window: Int = 40,
        order: Int = 5,
        diffuseT: Double = 2.0,
        lookback: Int = 140,
        leaderMode: Int = 0
    ): Result? {
        val valid = stocks.filter { it.close.size >= 60 && !it.name.contains("ST", true) }
        if (valid.size < 5 || benchmarkClose.size < 60 || dates.size < 60) return null

        val m = min(minOf(valid.minOf { it.close.size }, benchmarkClose.size), min(dates.size, lookback))
        if (m < 60) return null

        val closes = valid.map { it.close.copyOfRange(it.close.size - m, it.close.size) }
        val bench = benchmarkClose.copyOfRange(benchmarkClose.size - m, benchmarkClose.size)
        val ds = dates.subList(dates.size - m, dates.size)

        // 板块综合指数（市值加权，缺市值则等权）
        val wts = DoubleArray(valid.size) { if (valid[it].marketCap > 0) valid[it].marketCap else 1.0 }
        val wSum = wts.sum().coerceAtLeast(1e-9)
        val anchor = DoubleArray(m) { t ->
            var s = 0.0
            for (i in closes.indices) s += wts[i] * closes[i][t]
            s / wSum
        }

        // 收益率：节点 0 = 板块综合指数，节点 i+1 = 成分股
        val anchorRet = GspEngine.logReturns(anchor)
        val marketRet = GspEngine.logReturns(bench)
        val nS = valid.size
        val N = nS + 1
        val D = anchorRet.size
        if (D < 40) return null

        val residuals = Array(N) { DoubleArray(D) }
        residuals[0] = GspEngine.residualReturns(anchorRet, marketRet)
        for (i in 0 until nS) {
            residuals[i + 1] = GspEngine.residualReturns(GspEngine.logReturns(closes[i]), marketRet)
        }

        val w = GspEngine.buildAdjacency(residuals, window)
        val l = GspEngine.normalizedLaplacian(w)
        val lmax = GspEngine.lambdaMax(l)
        val tau = GspEngine.decayTau(l, 0, order)

        // 逐日拆分共模/残差（最近 K 日用于斜率，全历史用于分位）
        val kDays = min(20, D - 1)
        val commonLast = Array(nS) { DoubleArray(kDays) }
        val spec = Array(nS) { DoubleArray(D) }
        for (t in 0 until D) {
            val vec = DoubleArray(N) { residuals[it][t] }
            val (common, specific) = GspEngine.split(l, vec, order)
            for (i in 0 until nS) {
                spec[i][t] = specific[i + 1]
                if (t >= D - kDays) commonLast[i][t - (D - kDays)] = common[i + 1]
            }
        }

        val commonSlope = DoubleArray(nS) { i -> slope(commonLast[i]) }
        val specPct = DoubleArray(nS) { i -> rollingPercentile(spec[i], kDays) }
        val pos = DoubleArray(nS) { i ->
            val last = closes[i][m - 1]
            val win = closes[i].copyOfRange(m - 20, m)
            val hi = win.maxOrNull() ?: 0.0
            val lo = win.minOrNull() ?: 0.0
            if (hi - lo < 1e-9) 0.5 else ((last - lo) / (hi - lo)).coerceIn(0.0, 1.0)
        }
        val slopeRank = rankNormalize(commonSlope)
        val breadth = commonSlope.count { it > 0 }.toDouble() / nS

        // 锚趋势：ln 收盘 20 日回归斜率 + EMA20
        val lnAnchor = DoubleArray(m) { ln(anchor[it].coerceAtLeast(1e-9)) }
        val anchorSlope = slope(lnAnchor.copyOfRange(m - 20, m))
        val ema = emaSeries(anchor, 20)
        val trendUp = anchorSlope > 0.0004 && anchor[m - 1] > ema[m - 1]

        val state = when {
            !trendUp -> BoardState.DECAYING
            breadth < 0.40 -> BoardState.DIVERGING
            breadth < 0.60 || tau < 1.5 -> BoardState.BOOTING
            else -> BoardState.RESONATING
        }

        // ── 龙头（方案二）──
        val deg = GspEngine.degrees(w)
        val turning = BooleanArray(nS) { i ->
            val c = closes[i]
            val prev = c[m - 2]
            var low = prev
            for (j in m - 7 until m - 1) if (j >= 0) low = min(low, c[j])
            c[m - 1] > prev && prev <= low
        }
        // 龙头判定：0=拐点型（度中心 + 底部拐点）；1=动量型（度中心 + 近 5 日涨幅排名）
        val leaderSc = if (leaderMode == 1) {
            val degMx = deg.copyOfRange(1, N).maxOrNull()?.coerceAtLeast(1e-9) ?: 1.0
            val mom = DoubleArray(nS) { i ->
                if (m >= 6) closes[i][m - 1] / closes[i][m - 6].coerceAtLeast(1e-9) - 1.0 else 0.0
            }
            val momRank = rankNormalize(mom)
            DoubleArray(nS) { i -> 0.5 * (deg[i + 1] / degMx) + 0.5 * momRank[i] }
        } else {
            GspEngine.leaderScores(DoubleArray(nS) { deg[it + 1] }, turning)
        }
        val leaderIdx = (0 until nS).maxByOrNull { leaderSc[it] } ?: 0
        val f = GspEngine.diffuse(l, leaderIdx + 1, diffuseT, order)
        val diffusion = GspEngine.classifyDiffusion(f, leaderIdx + 1)
        val fMax = f.copyOfRange(1, N).maxOrNull()?.coerceAtLeast(1e-9) ?: 1e-9

        val candidates = ArrayList<Candidate>(nS)
        for (i in 0 until nS) {
            val energy = (f[i + 1] / fMax).coerceIn(0.0, 1.0)
            val score = 0.40 * slopeRank[i] + 0.35 * (1.0 - specPct[i]) + 0.25 * (1.0 - pos[i])
            val relayScore = 0.50 * (1.0 - energy) + 0.30 * (1.0 - pos[i]) + 0.20 * (1.0 - specPct[i])
            val tag = when {
                pos[i] < 0.35 && specPct[i] < 0.40 -> "低位·未兑现"
                pos[i] < 0.35 -> "低位"
                specPct[i] < 0.40 -> "未兑现"
                else -> "中高位"
            }
            candidates.add(
                Candidate(
                    code = valid[i].code,
                    name = valid[i].name,
                    price = closes[i][m - 1],
                    changePct = if (m >= 2) (closes[i][m - 1] / closes[i][m - 2].coerceAtLeast(1e-9) - 1.0) * 100 else 0.0,
                    commonFollow = commonSlope[i] * 100,
                    specPercentile = specPct[i],
                    position = pos[i],
                    score = score,
                    relayScore = relayScore,
                    energy = energy,
                    tag = tag
                )
            )
        }

        val edges = ArrayList<Triple<Int, Int, Double>>()
        for (i in 0 until nS) for (j in i + 1 until nS) {
            val e = w[i + 1][j + 1]
            if (e > 0.25) edges.add(Triple(i, j, e))
        }
        edges.sortByDescending { it.third }

        val chartWin = min(60, m)
        val base = anchor[m - chartWin]
        return Result(
            boardCode = boardCode,
            boardName = boardName,
            analyzedAt = nowStr(),
            state = state,
            tau = tau,
            lambdaMax = lmax,
            breadth = breadth,
            anchorSlope = anchorSlope,
            anchorTrendUp = trendUp,
            nStocks = nS,
            nBars = m,
            dates = ds.subList(ds.size - chartWin, ds.size).toList(),
            anchorNorm = (0 until chartWin).map { anchor[m - chartWin + it] / base * 100.0 },
            anchorEma = (0 until chartWin).map { ema[m - chartWin + it] / base * 100.0 },
            candidates = candidates,
            ranked = candidates.sortedByDescending { it.score },
            relay = candidates.sortedByDescending { it.relayScore },
            leaderCode = valid[leaderIdx].code,
            leaderName = valid[leaderIdx].name,
            leaderMode = leaderMode,
            diffusion = diffusion,
            edges = edges.take(90)
        )
    }

    // ─────────────────────── 小工具 ───────────────────────

    /** 最小二乘斜率（数组下标为 x） */
    private fun slope(y: DoubleArray): Double {
        val n = y.size
        if (n < 3) return 0.0
        val mx = (n - 1) / 2.0
        val my = y.average()
        var sxy = 0.0; var sxx = 0.0
        for (i in 0 until n) {
            val dx = i - mx
            sxy += dx * (y[i] - my); sxx += dx * dx
        }
        return if (sxx > 1e-12) sxy / sxx else 0.0
    }

    /** 当前值在滚动 w 日累计和分布中的分位（0~1） */
    private fun rollingPercentile(x: DoubleArray, w: Int): Double {
        val n = x.size
        if (n <= w) return 0.5
        val sums = DoubleArray(n - w + 1)
        var run = 0.0
        for (i in 0 until w) run += x[i]
        sums[0] = run
        for (i in w until n) {
            run += x[i] - x[i - w]
            sums[i - w + 1] = run
        }
        val cur = sums.last()
        var less = 0
        for (s in sums) if (s < cur) less++
        return less.toDouble() / sums.size
    }

    private fun rankNormalize(a: DoubleArray): DoubleArray {
        val order = a.indices.sortedBy { a[it] }
        val out = DoubleArray(a.size)
        order.forEachIndexed { rank, idx ->
            out[idx] = if (a.size <= 1) 0.5 else rank.toDouble() / (a.size - 1)
        }
        return out
    }

    private fun emaSeries(x: DoubleArray, span: Int): DoubleArray {
        val a = 2.0 / (span + 1.0)
        val out = DoubleArray(x.size)
        out[0] = x[0]
        for (i in 1 until x.size) out[i] = a * x[i] + (1 - a) * out[i - 1]
        return out
    }

    private fun nowStr(): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
        return f.format(java.util.Date())
    }
}
