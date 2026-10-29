package com.chin.stockanalysis.strategy.analysis

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * ## 相空间预测台账（JSONL）
 *
 * 每条记录 = 某票某周期某 horizon 的一次预测（A/B 两法同存一行）：
 * - 预测时写入：date/code/name/mode(daily|hourly)/h/last/lastDt/predA/predB
 * - 盘后验证回填：actual/actualDt/hitA/hitB（方向命中：sign(actual-last)==sign(pred-last)）
 *
 * 文件 `filesDir/phase_space_ledger.jsonl`，滚动 3000 条。
 */
object PhaseSpaceLedger {

    private const val TAG = "PhaseSpaceLedger"
    private const val FILE_NAME = "phase_space_ledger.jsonl"
    private const val MAX_ROWS = 3000
    private val TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    data class Rec(
        val date: String,          // 预测日 yyyy-MM-dd
        val ts: String,            // 预测时刻 yyyy-MM-dd HH:mm
        val code: String,          // 6 位
        val name: String,
        val mode: String,          // "daily" / "hourly"
        val h: Int,                // 预测第 h 根之后
        val last: Double,          // 预测基准价
        val lastDt: String,        // 基准K线日期/时刻（验证定位用）
        val predA: Double?,        // 价格法预测价（ null=该 h 无预测）
        val predB: Double?,        // cycle法预测价
        val actual: Double? = null,
        val actualDt: String? = null,
        val hitA: Boolean? = null,
        val hitB: Boolean? = null
    )

    private fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    fun nowStr(): String = LocalDateTime.now().format(TS_FMT)

    suspend fun appendAll(ctx: Context, recs: List<Rec>) = withContext(Dispatchers.IO) {
        if (recs.isEmpty()) return@withContext
        // 去重：同 票+周期+h+基准时刻 的预测只记一次（盘中重复刷新同一根 bar 不产生重复记录）
        val existing = loadAll(ctx).mapTo(HashSet()) { it.code + it.mode + it.h + it.lastDt }
        val fresh = recs.filter { (it.code + it.mode + it.h + it.lastDt) !in existing }
        if (fresh.isEmpty()) return@withContext
        try {
            file(ctx).appendText(fresh.joinToString("\n") { toJson(it).toString() } + "\n")
            Log.d(TAG, "📝 写入 ${fresh.size} 条相空间预测（跳过 ${recs.size - fresh.size} 条重复）")
        } catch (e: Exception) { Log.w(TAG, "写入失败: ${e.message}") }
        rollIfNeeded(ctx)
    }

    suspend fun loadAll(ctx: Context): List<Rec> = withContext(Dispatchers.IO) {
        val f = file(ctx)
        if (!f.exists()) return@withContext emptyList()
        f.readLines().mapNotNull { line ->
            if (line.isBlank()) null else runCatching { fromJson(JSONObject(line)) }.getOrNull()
        }
    }

    /** 回填验证：按 (code,mode,h,lastDt) 定位未验证记录并替换（与 appendAll 去重键一致，防止同票多基准互相覆盖） */
    suspend fun updateVerified(ctx: Context, verified: List<Rec>) = withContext(Dispatchers.IO) {
        if (verified.isEmpty()) return@withContext
        val vmap = verified.associateBy { it.code + it.mode + it.h + it.lastDt }
        val all = loadAll(ctx).map { r ->
            val v = vmap[r.code + r.mode + r.h + r.lastDt]
            if (v != null && r.actual == null) v else r
        }
        file(ctx).writeText(all.joinToString("\n") { toJson(it).toString() } + "\n")
    }

    /** 台账压缩：同 (code,mode,h,lastDt) 只保留最后一条（清理旧匹配键错乱留下的重复行）。返回删除条数 */
    suspend fun compact(ctx: Context): Int = withContext(Dispatchers.IO) {
        val all = loadAll(ctx)
        val lastIdx = HashMap<String, Int>()
        all.forEachIndexed { i, r -> lastIdx[r.code + r.mode + r.h + r.lastDt] = i }
        if (lastIdx.size == all.size) return@withContext 0
        val keep = lastIdx.values.toSet()
        val compacted = all.filterIndexed { i, _ -> i in keep }
        file(ctx).writeText(compacted.joinToString("\n") { toJson(it).toString() } + "\n")
        all.size - compacted.size
    }

    private suspend fun rollIfNeeded(ctx: Context) = withContext(Dispatchers.IO) {
        val all = loadAll(ctx)
        if (all.size > MAX_ROWS) file(ctx).writeText(
            all.takeLast(MAX_ROWS).joinToString("\n") { toJson(it).toString() } + "\n")
    }

    private fun toJson(r: Rec) = JSONObject().apply {
        put("date", r.date); put("ts", r.ts); put("code", r.code); put("name", r.name)
        put("mode", r.mode); put("h", r.h); put("last", r.last); put("lastDt", r.lastDt)
        r.predA?.let { put("predA", it) }; r.predB?.let { put("predB", it) }
        r.actual?.let { put("actual", it) }; r.actualDt?.let { put("actualDt", it) }
        r.hitA?.let { put("hitA", it) }; r.hitB?.let { put("hitB", it) }
    }

    private fun fromJson(o: JSONObject) = Rec(
        date = o.optString("date"), ts = o.optString("ts"),
        code = o.optString("code"), name = o.optString("name"),
        mode = o.optString("mode"), h = o.optInt("h"),
        last = o.optDouble("last"), lastDt = o.optString("lastDt"),
        predA = if (o.has("predA")) o.getDouble("predA") else null,
        predB = if (o.has("predB")) o.getDouble("predB") else null,
        actual = if (o.has("actual")) o.getDouble("actual") else null,
        actualDt = if (o.has("actualDt")) o.getString("actualDt") else null,
        hitA = if (o.has("hitA")) o.getBoolean("hitA") else null,
        hitB = if (o.has("hitB")) o.getBoolean("hitB") else null
    )

    /** 方向命中判定：sign(actual-last) == sign(pred-last)，|变动|<0.3% 视为平（不算命中也不算错→返回 null） */
    fun judge(last: Double, pred: Double?, actual: Double): Boolean? {
        if (pred == null || last <= 0) return null
        val dp = pred - last; val da = actual - last
        if (kotlin.math.abs(da / last) < 0.003) return null
        return (dp > 0) == (da > 0)
    }
}
