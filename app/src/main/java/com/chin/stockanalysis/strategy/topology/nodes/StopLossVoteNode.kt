package com.chin.stockanalysis.strategy.topology.nodes

import android.util.Log
import com.chin.stockanalysis.strategy.analysis.TechTags
import com.chin.stockanalysis.strategy.topology.core.BaseNode
import com.chin.stockanalysis.strategy.topology.core.NodeType
import com.chin.stockanalysis.strategy.topology.core.PipelineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ## 多理论投票止损节点（2026-09-12 新增）
 *
 * 规则出处：用户《设置止损线.txt》——六套理论各给一个止损价，取**最保守（最高）**者作为
 * 最终止损，再叠加「动量收紧」上浮与「棘轮」只上移不下移。与 Python 端
 * `usecase_pipeline.stop_loss_vote` 同口径（参数全部走 pipeline XML config）。
 *
 * | # | 理论 | 公式 |
 * |---|------|------|
 * | ① | ATR 吊灯（Chandelier） | 最高价 − k×ATR(14)，k=超短1.5 / 短2.0 / 中2.5 / 长3.0 |
 * | ② | 结构止损 | 信号 K 线低点（近 10 日最低 low） |
 * | ③ | 趋势止损 | 跌破周期均线（超短·短 MA20 / 中 MA60 / 长 MA120） |
 * | ④ | 风险预算 | 入场价 ×(1 − 固定止损%)；弱市 ×0.7 收紧 |
 * | ⑤ | 移动止盈 | 最高价 ×(1 − 回撤%)（超短 4% … 长线 12%） |
 * | ⑥ | 动量收紧 | MACD 死叉 / SAR 刚翻绿或绿↓ / OBV 下行 → 最终止损上浮 1.5% |
 *
 * 用法（pipeline XML）：
 * - ETF：`n_etf_signal → n_etf_stop(stop_loss_vote, target=etf)`，给每行补 stop/stopPct/broken；
 * - 实仓：`n_holding_predict → n_rh_stop(stop_loss_vote, target=holding)`，读 RealPositionDao
 *   + 策略持仓，逐仓输出止损价与「已破位→清仓」判定。
 */
class StopLossVoteNode(
    private val target: String = "auto",
    private val period: String = "",
    private val marketState: String = "auto",
    private val trailUp: Boolean = true,
    private val atrMult: Double = -1.0,
    private val trailPct: Double = -1.0,
    private val maWin: Int = 0,
    private val fixedPct: Double = -1.0,
    private val lookback: Int = 130,
    private val sourceNode: String = ""
) : BaseNode<Any, JSONObject>("stop_loss_vote", "多理论投票止损", NodeType.ENRICHMENT) {

    companion object {
        private const val TAG = "StopLossVote"
    }

    /** 周期档：(ATR 倍数, 移动止盈回撤%, 趋势均线, 固定比例止损%, 最高价窗口) */
    private fun profileOf(p: String): DoubleArray = when (p.uppercase(Locale.US)) {
        "ULTRA_SHORT", "ULTRA", "超短" -> doubleArrayOf(1.5, 4.0, 20.0, 5.0, 20.0)
        "MID", "中线" -> doubleArrayOf(2.5, 8.0, 60.0, 10.0, 40.0)
        "LONG", "长线" -> doubleArrayOf(3.0, 12.0, 120.0, 15.0, 60.0)
        else -> doubleArrayOf(2.0, 6.0, 20.0, 8.0, 20.0)   // SHORT 默认
    }

    override suspend fun execute(context: PipelineContext, input: Any): JSONObject {
        val p = resolvePeriod(context)
        val mkt = resolveMarketState(context)
        return try {
            when {
                target.equals("holding", true) -> holdingsBranch(context, p, mkt)
                target.equals("etf", true) -> etfBranch(context, input, p, mkt)
                target.equals("picks", true) -> picksBranch(context, input, p, mkt)
                input is JSONObject && input.optJSONArray("rows") != null ->
                    etfBranch(context, input, p, mkt)          // auto：ETF 行优先
                else -> holdingsBranch(context, p, mkt)
            }
        } catch (e: Exception) {
            Log.e(TAG, "止损投票异常: ${e.message}", e)
            context.log(nodeId, "⚠ $nodeName: 异常(${e.message})")
            JSONObject().put("error", e.message ?: "unknown").put("period", p)
        }
    }

    // ───────────────────────── ETF 分支 ─────────────────────────

    private suspend fun etfBranch(
        context: PipelineContext,
        input: Any,
        p: String,
        mkt: String
    ): JSONObject {
        val src = (input as? JSONObject) ?: (context.stageOutputs["n_etf_signal"] as? JSONObject)
            ?: return JSONObject().put("rows", JSONArray()).put("period", p)
        val rows = src.optJSONArray("rows") ?: JSONArray()
        val cache = EtfCacheStore.load(context.androidContext)
        val out = JSONObject()
        val outRows = JSONArray()
        var broken = 0
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: continue
            val code = r.optString("code")
            val ent = cache?.optJSONObject(code)
            val snaps = ent?.optJSONArray("snaps")
            val row = JSONObject(r.toString())
            if (snaps != null && snaps.length() >= 25) {
                val v = vote(snapsToBars(snaps), p, r.optDouble("cost", 0.0), mkt,
                    r.optDouble("stop", 0.0))
                row.put("stop", v.stop)
                row.put("stopPct", v.stopPct)
                row.put("spacePct", v.spacePct)
                row.put("broken", v.broken)
                row.put("stopAction", v.action)
                row.put("stopTheory", v.theoryJson())
                row.put("momentum", v.momentum)
                if (v.broken) broken++
            }
            outRows.put(row)
        }
        out.put("as_of", src.optString("as_of", ""))
        out.put("period", p)
        out.put("market_state", mkt)
        out.put("rows", outRows)
        out.put("n_broken", broken)
        out.put("stop_vote", descOf(p, mkt))
        context.log(nodeId, "🛑 止损投票(ETF $p/$mkt): ${outRows.length()} 只 · $broken 只已破位")
        return out
    }

    // ───────────────────────── 个股清单分支（ETF 全行业扫描 / 持股 top5） ─────────────────────────

    /** target=picks：消费上游（sourceNode）行内嵌 `bars`（ETF 全行业扫描/持股 top5 输出）。 */
    private suspend fun picksBranch(
        context: PipelineContext,
        input: Any,
        p: String,
        mkt: String
    ): JSONObject {
        val src = (if (sourceNode.isNotBlank()) context.stageOutputs[sourceNode] as? JSONObject else null)
            ?: (input as? JSONObject)
            ?: (context.stageOutputs["n_industry_scan"] as? JSONObject)
            ?: (context.stageOutputs["n_holdings_top5"] as? JSONObject)
            ?: return JSONObject().put("rows", JSONArray()).put("period", p)
        val rows = src.optJSONArray("rows") ?: JSONArray()
        val outRows = JSONArray()
        var priced = 0
        var broken = 0
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: continue
            val row = JSONObject(r.toString())
            val bars = parseBars(r.optJSONArray("bars"))
            row.remove("bars")
            if (bars != null && bars.size >= 25) {
                val v = vote(bars, p, r.optDouble("entry", r.optDouble("cost", 0.0)), mkt,
                    r.optDouble("stop", 0.0))
                row.put("stop", v.stop).put("stopPct", v.stopPct).put("spacePct", v.spacePct)
                    .put("broken", v.broken).put("stopAction", v.action)
                    .put("stopTheory", v.theoryJson()).put("momentum", v.momentum)
                if (v.stop > 0) priced++
                if (v.broken) broken++
            }
            outRows.put(row)
        }
        val out = JSONObject(src.toString())
            .put("as_of", src.optString("as_of", ""))
            .put("period", p).put("marketState", mkt)
            .put("rows", outRows).put("priced", priced).put("n_broken", broken)
            .put("stop_vote", descOf(p, mkt))
        context.setStageOutput(nodeId, out)
        context.log(nodeId, "🛑 止损投票(个股 $p): $priced 只定价 / ${outRows.length()} 只 · $broken 只已破位")
        return out
    }

    private fun parseBars(arr: JSONArray?): List<DoubleArray>? {
        if (arr == null || arr.length() == 0) return null
        return (0 until arr.length()).map { i ->
            val s = arr.optJSONObject(i) ?: JSONObject()
            doubleArrayOf(
                TechTags.num(s.optDouble("open")), TechTags.num(s.optDouble("high")),
                TechTags.num(s.optDouble("low")), TechTags.num(s.optDouble("close")),
                TechTags.num(s.optDouble("volume"))
            )
        }
    }

    // ───────────────────────── 实仓分支 ─────────────────────────

    private suspend fun holdingsBranch(
        context: PipelineContext,
        p: String,
        mkt: String
    ): JSONObject = withContext(Dispatchers.IO) {
        val db = com.chin.stockanalysis.stock.database.StockDatabase
            .getInstance(context.androidContext)
        val rows = JSONArray()
        var broken = 0
        try {
            val positions = runCatching { db.realPositionDao().getAllActive() }
                .getOrDefault(emptyList())
            for (pos in positions) {
                val snaps = runCatching { db.dailySnapshotDao().getByCode(pos.stockCode, lookback) }
                    .getOrDefault(emptyList()).sortedBy { it.date }
                val row = JSONObject()
                    .put("code", pos.stockCode)
                    .put("name", pos.stockName)
                    .put("cost", pos.avgBuyPrice)
                    .put("shares", pos.quantity)
                if (snaps.size >= 25) {
                    val v = vote(
                        snaps.map {
                            doubleArrayOf(it.open, it.high, it.low, it.close, it.volume.toDouble())
                        }, p, pos.avgBuyPrice, mkt
                    )
                    row.put("stop", v.stop).put("stopPct", v.stopPct).put("spacePct", v.spacePct)
                        .put("broken", v.broken).put("stopAction", v.action)
                        .put("stopTheory", v.theoryJson()).put("momentum", v.momentum)
                    if (v.broken) broken++
                }
                rows.put(row)
            }
        } catch (e: Exception) {
            Log.w(TAG, "实仓读取失败: ${e.message}")
        }
        val out = JSONObject()
            .put("period", p).put("market_state", mkt)
            .put("holdings", rows).put("n_broken", broken)
            .put("stop_vote", descOf(p, mkt))
        context.log(nodeId, "🛑 止损投票(实仓 $p/$mkt): ${rows.length()} 只 · $broken 只已破位")
        out
    }

    // ───────────────────────── 核心投票 ─────────────────────────

    /** 六理论投票结果。 */
    private data class Vote(
        val stop: Double, val stopPct: Double, val spacePct: Double,
        val broken: Boolean, val action: String, val momentum: String,
        val theory: Map<String, Double>
    ) {
        fun theoryJson(): JSONObject {
            val j = JSONObject()
            theory.forEach { (k, v) -> j.put(k, roundTo(v, 3)) }
            return j
        }
    }

    /** bars[i] = [open, high, low, close, volume]，升序；prevStop=上次止损价（棘轮用）。 */
    private fun vote(
        bars: List<DoubleArray>, period: String, entry: Double, mkt: String,
        prevStop: Double = 0.0
    ): Vote {
        val prof = profileOf(period)
        val k = if (atrMult > 0) atrMult else prof[0]
        val trail = if (trailPct > 0) trailPct else prof[1]
        val maW = if (maWin > 0) maWin else prof[2].toInt()
        var fixed = if (fixedPct > 0) fixedPct else prof[3]
        val hiWin = prof[4].toInt()
        if (mkt.uppercase(Locale.US) == "BEARISH") fixed *= 0.7

        val n = bars.size
        val closes = bars.map { it[3] }
        val highs = bars.map { it[1] }
        val lows = bars.map { it[2] }
        val vols = bars.map { it[4] }
        val px = closes.last()
        val top = highs.takeLast(hiWin).max()

        val cand = LinkedHashMap<String, Double>()
        atr(bars, 14)?.let { cand["①ATR吊灯"] = top - k * it }
        cand["②结构低点"] = lows.takeLast(10).min()
        ma(closes, maW)?.let { cand["③趋势MA$maW"] = it }
        cand["④固定比例"] = (if (entry > 0) entry else px) * (1 - fixed / 100.0)
        cand["⑤移动止盈"] = top * (1 - trail / 100.0)

        val macdT = TechTags.macdText(closes)
        val sarT = TechTags.sarText(closes, highs, lows)
        val obvT = TechTags.obvText(closes, vols) ?: ""
        val weak = macdT == "死叉" || sarT.startsWith("刚翻绿") ||
            sarT.startsWith("绿↓") || obvT == "OBV下行"

        var stop = cand.values.max()
        if (weak) stop *= 1.015
        if (trailUp && prevStop > 0) stop = max(stop, prevStop)   // 棘轮：只上移不下移
        val action = if (px <= stop) "已破位→清仓"
        else cand.maxByOrNull { it.value }?.key ?: ""
        return Vote(
            stop = round(stop, 3),
            stopPct = round((stop / px - 1) * 100, 2),
            spacePct = if (stop > 0) round((px / stop - 1) * 100, 2) else 0.0,
            broken = px <= stop,
            action = action,
            momentum = (if (weak) "动量转弱⚠️ " else "动量仍强✅ ") +
                listOf(macdT, sarT, obvT).filter { it.isNotBlank() }.joinToString(" "),
            theory = cand
        )
    }

    /** Wilder ATR(period)。 */
    private fun atr(bars: List<DoubleArray>, period: Int = 14): Double? {
        if (bars.size < period + 1) return null
        val trs = (1 until bars.size).map { i ->
            val h = bars[i][1]
            val l = bars[i][2]
            val pc = bars[i - 1][3]
            maxOf(h - l, abs(h - pc), abs(l - pc))
        }
        var a = trs.take(period).average()
        for (i in period until trs.size) a = (a * (period - 1) + trs[i]) / period
        return a
    }

    private fun ma(vals: List<Double>, period: Int): Double? =
        if (vals.size < period) null else vals.takeLast(period).average()

    private fun snapsToBars(snaps: JSONArray): List<DoubleArray> =
        (0 until snaps.length()).map { i ->
            val s = snaps.optJSONObject(i)
            doubleArrayOf(
                TechTags.num(s.optDouble("open")), TechTags.num(s.optDouble("high")),
                TechTags.num(s.optDouble("low")), TechTags.num(s.optDouble("close")),
                TechTags.num(s.optDouble("volume"))
            )
        }

    // ───────────────────────── 环境解析 ─────────────────────────

    private fun resolvePeriod(context: PipelineContext): String {
        if (period.isNotBlank()) return period.uppercase(Locale.US)
        val hp = runCatching { context.config.holdingPeriod }.getOrDefault("short")
        return when (hp.lowercase(Locale.US)) {
            "ultra_short", "ultra" -> "ULTRA_SHORT"
            "mid" -> "MID"
            "long" -> "LONG"
            else -> "SHORT"
        }
    }

    /** 大盘三态：优先 XML config，其次沪深300 均线结构（close>MA20>MA60=牛 / 反之=熊 / 其余=震荡）。 */
    private fun resolveMarketState(context: PipelineContext): String {
        if (marketState.isNotBlank() && !marketState.equals("auto", true)) {
            return marketState.uppercase(Locale.US)
        }
        return try {
            val cache = EtfCacheStore.load(context.androidContext) ?: return "OSCILLATION"
            val snaps = cache.optJSONObject("sh000300")?.optJSONArray("snaps")
                ?: return "OSCILLATION"
            val closes = (0 until snaps.length()).mapNotNull { i ->
                snaps.optJSONObject(i)?.optDouble("close", Double.NaN)?.takeIf { !it.isNaN() }
            }
            if (closes.size < 60) return "OSCILLATION"
            val f = closes.takeLast(20).average()
            val s = closes.takeLast(60).average()
            when {
                closes.last() > f && f > s -> "BULLISH"
                closes.last() < f && f < s -> "BEARISH"
                else -> "OSCILLATION"
            }
        } catch (e: Exception) {
            "OSCILLATION"
        }
    }

    private fun descOf(p: String, mkt: String): String {
        val prof = profileOf(p)
        return "period=$p k=${prof[0]} trail=${prof[1]}% ma=MA${prof[2].toInt()} market=$mkt"
    }

    private fun round(v: Double, digits: Int): Double = roundTo(v, digits)
}

/** 文件级取整（嵌套类 Vote 无法访问外部类成员，故下沉为顶层函数）。 */
private fun roundTo(v: Double, digits: Int): Double {
    val m = Math.pow(10.0, digits.toDouble())
    return Math.round(v * m) / m
}
