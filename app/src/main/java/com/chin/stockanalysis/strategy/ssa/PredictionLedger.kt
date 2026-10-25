package com.chin.stockanalysis.strategy.ssa

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.io.BufferedReader
import java.io.FileReader
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * ## 信号跟踪验证台账（纸上交易打分）
 *
 * 两阶段 JSONL：
 * - prediction 阶段：早盘（9:25 前后）快照各策略信号 + 预测价位区间
 * - verification 阶段：收盘后（15:05+）拉当日日线回填实际 OHLC + 命中/对错判定
 *
 * 滚动 300 条，原子追加写。文件：`filesDir/prediction_ledger.jsonl`。
 *
 * 验证标准（2026-10-21 第一版，后续调）：
 * - BUY  信号：当日 close 涨幅 > 0 或 high 触及预测上界 → correct
 * - SELL 信号：当日 close 跌幅 > 0（卖对了，没踩坑）→ correct
 * - HOLD 信号：当日 close 落在预测区间 [targetDn, targetUp] 内 → correct
 * - EMPTY信号：当日 close 跌幅 > 0（空仓避开下跌）→ correct
 */
object PredictionLedger {

    private const val TAG = "PredictionLedger"
    private const val FILE_NAME = "prediction_ledger.jsonl"
    private const val MAX_ROWS = 300

    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val DATE_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    data class PricePred(
        val openLast: Double,      // 快照末根 1H 收盘（预测基准）
        val targetUp: Double? = null,     // 预测上界（eUp / 短趋势 / BBI12 顶）
        val targetDn: Double? = null,     // 预测下界（eDn / 长趋势 / BBI12 底）
        val stopLoss: Double? = null
    )

    data class PredictionRecord(
        val date: String,            // "20261021" 预测交易日期
        val snapshotAt: String,      // "2026-10-21 09:25" 快照时间
        val code: String,            // "600176"
        val name: String,
        val strategy: String,        // "T0" / "Hybrid" / "BBI12"
        val signal: String,          // "BUY" / "SELL" / "HOLD" / "EMPTY"
        val signalDesc: String,      // "UP态·收>eUp卖T30%"
        val pricePred: PricePred,
        // verification 阶段（收盘后回填）
        val actualOpen: Double? = null,
        val actualHigh: Double? = null,
        val actualLow: Double? = null,
        val actualClose: Double? = null,
        val actualChangePct: Double? = null,
        val hitTargetUp: Boolean? = null,
        val hitTargetDn: Boolean? = null,
        val correct: Boolean? = null,
        val score: Double? = null,   // 0.0 ~ 1.0
        val verifyNote: String? = null
    ) {
        fun isVerified(): Boolean = actualClose != null
    }

    private fun file(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    // ═══════════ 写：原子追加 JSONL ═══════════

    suspend fun append(ctx: Context, rec: PredictionRecord) = withContext(Dispatchers.IO) {
        val f = file(ctx)
        try {
            FileWriter(f, true).buffered().use { w -> w.write(rec.toJson().toString()); w.newLine() }
            Log.d(TAG, "📝 写入预测 ${rec.strategy}/${rec.code} ${rec.signal} @${rec.date}")
        } catch (e: Exception) {
            Log.w(TAG, "写入失败: ${e.message}")
        }
        rollIfNeeded(ctx)
    }

    /** 批量追加（拍早照时用，避免频繁 IO） */
    suspend fun appendAll(ctx: Context, recs: List<PredictionRecord>) = withContext(Dispatchers.IO) {
        if (recs.isEmpty()) return@withContext
        val f = file(ctx)
        try {
            FileWriter(f, true).buffered().use { w ->
                recs.forEach { w.write(it.toJson().toString()); w.newLine() }
            }
            Log.d(TAG, "📝 批量写入 ${recs.size} 条预测")
        } catch (e: Exception) {
            Log.w(TAG, "批量写入失败: ${e.message}")
        }
        rollIfNeeded(ctx)
    }

    /** 把同一 code+date+strategy 的记录替换成回填 verification 的版本 */
    suspend fun updateVerified(ctx: Context, rec: PredictionRecord) = withContext(Dispatchers.IO) {
        val f = file(ctx)
        val all = loadAllRaw(ctx)
        val idx = all.indexOfFirst {
            it.optString("date") == rec.date &&
            it.optString("code") == rec.code &&
            it.optString("strategy") == rec.strategy &&
            !it.has("actualClose")
        }
        if (idx < 0) return@withContext
        all[idx] = rec.toJson()
        f.writeText(all.joinToString("\n"))
    }

    private suspend fun rollIfNeeded(ctx: Context) = withContext(Dispatchers.IO) {
        val f = file(ctx)
        val all = loadAllRaw(ctx)
        if (all.size > MAX_ROWS) {
            val keep = all.takeLast(MAX_ROWS)
            f.writeText(keep.joinToString("\n"))
            Log.i(TAG, "📦 滚动至 ${keep.size} 条（原 ${all.size}）")
        }
    }

    private fun loadAllRaw(ctx: Context): MutableList<JSONObject> {
        val f = file(ctx)
        if (!f.exists()) return mutableListOf()
        val out = ArrayList<JSONObject>()
        BufferedReader(FileReader(f)).use { r ->
            var line = r.readLine()
            while (line != null) {
                if (line.isNotBlank()) runCatching { out.add(JSONObject(line)) }
                line = r.readLine()
            }
        }
        return out
    }

    // ═══════════ 读 ═══════════

    suspend fun loadAll(ctx: Context): List<PredictionRecord> =
        withContext(Dispatchers.IO) { loadAllRaw(ctx).mapNotNull { fromJson(it) } }

    suspend fun loadUnverified(ctx: Context): List<PredictionRecord> =
        withContext(Dispatchers.IO) { loadAll(ctx).filter { !it.isVerified() } }

    /** 按日期分组（新→旧），每组内按策略→代码排序 */
    suspend fun loadGroupedByDate(ctx: Context): List<Pair<String, List<PredictionRecord>>> =
        withContext(Dispatchers.IO) {
            val all = loadAll(ctx).sortedByDescending { it.date }
            all.groupBy { it.date }.map { (d, rs) ->
                d to rs.sortedWith(compareBy({ it.strategy }, { it.code }))
            }
        }

    // ═══════════ 核心：验证判定 ═══════════

    /**
     * 根据预测信号 + 实际日线 OHLC 回填 verification 字段。
     * score：命中程度 0~1.0。
     */
    fun verify(rec: PredictionRecord, o: Double, h: Double, l: Double, c: Double): PredictionRecord {
        val p = rec.pricePred
        val chg = if (p.openLast > 0) (c / p.openLast - 1) * 100 else 0.0
        val hitUp = p.targetUp != null && h >= p.targetUp
        val hitDn = p.targetDn != null && l <= p.targetDn

        val (correct, score, note) = when (rec.signal) {
            "BUY" -> {
                // 买入：涨了或触及上界 = 对
                val ok = chg > 0 || hitUp
                val s = when {
                    hitUp -> 1.0
                    chg > 3 -> 0.9
                    chg > 0 -> 0.7
                    chg > -2 -> 0.4
                    else -> 0.1
                }
                val n = "BUY→${"%.2f".format(chg)}% 高${"%.2f".format(h)}${if (hitUp) "✓触上界${"%.2f".format(p.targetUp!!)}" else ""}"
                Triple(ok, s, n)
            }
            "SELL" -> {
                // 卖出：跌了 = 卖对了（避开亏损）
                val ok = chg < 0
                val s = when {
                    chg < -3 -> 1.0
                    chg < -1 -> 0.85
                    chg < 0 -> 0.6
                    else -> 0.3  // 卖了但涨了，不算错（本来就该止盈）
                }
                val n = "SELL→${"%.2f".format(chg)}% 卖${if (ok) "对" else "后涨"}"
                Triple(ok, s, n)
            }
            "HOLD" -> {
                // 持仓：close 在 [targetDn, targetUp] 区间内 = 趋势维持
                val dn = p.targetDn ?: Double.NEGATIVE_INFINITY
                val up = p.targetUp ?: Double.POSITIVE_INFINITY
                val inRange = c in dn..up
                val ok = inRange
                val dev = if (p.openLast > 0) kotlin.math.abs(c - p.openLast) / p.openLast else 0.0
                val s = when {
                    inRange && dev < 0.02 -> 1.0
                    inRange -> 0.8
                    chg > 2 -> 0.5   // 涨了但超区间，趋势加速（不算错）
                    chg < -2 -> 0.2  // 跌破区间，应卖出
                    else -> 0.4
                }
                val n = "HOLD→${"%.2f".format(chg)}% ${if (inRange) "区间内" else "破区间"}"
                Triple(ok, s, n)
            }
            "EMPTY" -> {
                // 空仓：跌了 = 避对了
                val ok = chg < 0
                val s = if (ok) 0.9 else 0.4
                val n = "EMPTY→${"%.2f".format(chg)}% ${if (ok) "避跌✓" else "涨了空仓可惜"}"
                Triple(ok, s, n)
            }
            else -> Triple(false, 0.0, "未知信号")
        }
        return rec.copy(
            actualOpen = o, actualHigh = h, actualLow = l, actualClose = c,
            actualChangePct = chg, hitTargetUp = hitUp, hitTargetDn = hitDn,
            correct = correct, score = score, verifyNote = note
        )
    }

    // ═══════════ JSON 序列化 ═══════════

    private fun PredictionRecord.toJson(): JSONObject = JSONObject().apply {
        put("date", date); put("snapshotAt", snapshotAt)
        put("code", code); put("name", name)
        put("strategy", strategy); put("signal", signal); put("signalDesc", signalDesc)
        put("pricePred", JSONObject().apply {
            put("openLast", pricePred.openLast)
            pricePred.targetUp?.let { put("targetUp", it) }
            pricePred.targetDn?.let { put("targetDn", it) }
            pricePred.stopLoss?.let { put("stopLoss", it) }
        })
        actualOpen?.let { put("actualOpen", it) }
        actualHigh?.let { put("actualHigh", it) }
        actualLow?.let { put("actualLow", it) }
        actualClose?.let { put("actualClose", it) }
        actualChangePct?.let { put("actualChangePct", it) }
        hitTargetUp?.let { put("hitTargetUp", it) }
        hitTargetDn?.let { put("hitTargetDn", it) }
        correct?.let { put("correct", it) }
        score?.let { put("score", it) }
        verifyNote?.let { put("verifyNote", it) }
    }

    private fun fromJson(o: JSONObject): PredictionRecord? = runCatching {
        val pp = o.getJSONObject("pricePred")
        PredictionRecord(
            date = o.optString("date"),
            snapshotAt = o.optString("snapshotAt"),
            code = o.optString("code"),
            name = o.optString("name"),
            strategy = o.optString("strategy"),
            signal = o.optString("signal"),
            signalDesc = o.optString("signalDesc"),
            pricePred = PricePred(
                openLast = pp.optDouble("openLast", 0.0),
                targetUp = pp.opt("targetUp")?.let { (it as? Number)?.toDouble() },
                targetDn = pp.opt("targetDn")?.let { (it as? Number)?.toDouble() },
                stopLoss = pp.opt("stopLoss")?.let { (it as? Number)?.toDouble() }
            ),
            actualOpen = o.opt("actualOpen")?.let { (it as? Number)?.toDouble() },
            actualHigh = o.opt("actualHigh")?.let { (it as? Number)?.toDouble() },
            actualLow = o.opt("actualLow")?.let { (it as? Number)?.toDouble() },
            actualClose = o.opt("actualClose")?.let { (it as? Number)?.toDouble() },
            actualChangePct = o.opt("actualChangePct")?.let { (it as? Number)?.toDouble() },
            hitTargetUp = o.optBoolean("hitTargetUp", false).takeIf { o.has("hitTargetUp") },
            hitTargetDn = o.optBoolean("hitTargetDn", false).takeIf { o.has("hitTargetDn") },
            correct = o.optBoolean("correct", false).takeIf { o.has("correct") },
            score = o.opt("score")?.let { (it as? Number)?.toDouble() },
            verifyNote = o.optString("verifyNote").takeIf { it.isNotEmpty() }
        )
    }.getOrNull()

    // ═══════════ 工具：今日日期串 / 快照时间 ═══════════

    fun today(): String = LocalDate.now().format(DATE_FMT)
    fun nowStr(): String = LocalDateTime.now().format(DATE_TIME_FMT)
}
