package com.chin.stockanalysis.stock.database

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 每日节奏调度器（APK 侧，与 PC 侧 `_publish_candidates.py` 守护 v4 对齐）。
 *
 * | 时刻 | 动作 | 落库 |
 * |---|---|---|
 * | 08:00-08:29 | pre8 盘前情报：宏观 + 美股收盘 → 利好利空板块 → 候选标的 | daily_intel + push_record |
 * | 09:00-09:29 | pre9 亚太情报：日经/KOSPI/恒生/台湾/新加坡/澳洲 + 韩国权重股 | daily_intel + push_record |
 * | 09:30-14:30 | 每 15 分钟一轮盘中推送（由 [AutoPickScheduler] 承载，正文前导每日节奏情报） | push_record |
 * | 15:20-15:59 | review 表格化复盘：a 板块判定对错 / b 当日选股 / c 近5日巡诊 / d 实仓镜像 | daily_intel + push_record |
 *
 * 幂等：每个 `(交易日, 时段)` 只执行一次，标记写入 SharedPreferences `daily_rhythm`。
 */
object DailyRhythmScheduler {

    private const val TAG = "DailyRhythmSched"

    /** 检查间隔：30 秒足够命中整点窗口，又不至于耗电 */
    private const val TICK_MS = 30 * 1000L

    private const val PREFS = "daily_rhythm"

    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    @Volatile
    private var job: Job? = null

    @Volatile
    private var busy = false

    /** 由 AppBackgroundRunner.start 调用一次 */
    fun start(context: Context, scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            Log.i(TAG, "🚀 每日节奏调度启动（08:00 盘前 / 09:00 亚太 / 15:20 复盘）")
            while (isActive) {
                try {
                    tick(context.applicationContext)
                } catch (t: Throwable) {
                    Log.w(TAG, "tick 异常: ${t.message}")
                }
                delay(TICK_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun tick(context: Context) {
        val now = LocalDateTime.now()
        if (now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY) return
        val today = now.format(DATE_FMT)
        val hm = now.hour * 60 + now.minute
        when {
            hm in 8 * 60 until 8 * 60 + 30 -> fire(context, today, "pre8")
            hm in 9 * 60 until 9 * 60 + 30 -> fire(context, today, "pre9")
            hm in 15 * 60 + 20 until 16 * 60 -> fire(context, today, "review")
        }
    }

    private suspend fun fire(context: Context, date: String, slot: String) {
        if (isDone(context, date, slot) || busy) return
        busy = true
        try {
            Log.i(TAG, "▶ 开始 $slot（$date）")
            val started = System.currentTimeMillis()
            when (slot) {
                "pre8", "pre9" -> DailyRhythmEngine.runSlot(context, slot)
                "review" -> DailyRhythmEngine.runReview(context)
            }
            markDone(context, date, slot)
            Log.i(TAG, "✅ $slot 完成，耗时 ${(System.currentTimeMillis() - started) / 1000}s")
        } catch (e: Exception) {
            // 不标记完成 → 下一 tick 自动重试（30 秒后）
            Log.w(TAG, "$slot 失败，稍后重试: ${e.message}")
        } finally {
            busy = false
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 每个交易日自动失效旧标记（key 带日期，无需清理） */
    private fun isDone(context: Context, date: String, slot: String): Boolean =
        prefs(context).getBoolean("$date|$slot", false)

    private fun markDone(context: Context, date: String, slot: String) {
        prefs(context).edit().putBoolean("$date|$slot", true).apply()
    }

    // ─────────────── 供 AutoPickScheduler 复用的「盘中轮」情报前导 ───────────────

    /**
     * 盘中轮推送正文前导（满足「09:30 综合 08:00/09:00 信息」）。
     *
     * 取当日最新一条情报：利好利空板块摘要 + 候选标的 + 最新快讯。
     * 情报缺失时返回空串，不阻塞盘中轮推送。
     */
    suspend fun roundPreamble(context: Context): String {
        val today = LocalDate.now().format(DATE_FMT)
        return try {
            val db = StockDatabase.getInstance(context)
            val rec = db.dailyIntelDao().ofDate(today)
                .lastOrNull { it.slot == "pre9" || it.slot == "pre8" }
                ?: return ""
            val sb = StringBuilder()
            val hm = if (rec.createdAt.length >= 16) rec.createdAt.substring(11, 16) else rec.createdAt
            sb.append("🌍 每日节奏（${rec.slot}｜$hm）\n")
            if (rec.digest.isNotBlank()) sb.append("  ").append(rec.digest).append('\n')
            val sectors = runCatching { org.json.JSONArray(rec.sectorsJson) }.getOrNull()
            val bulls = mutableListOf<String>()
            val bears = mutableListOf<String>()
            for (i in 0 until (sectors?.length() ?: 0)) {
                val o = sectors!!.optJSONObject(i) ?: continue
                val tag = "${o.optString("board")}(${o.optString("strength")})"
                if (o.optString("side") == "利空") bears += tag else bulls += tag
            }
            if (bulls.isNotEmpty()) sb.append("  🟢利好: ").append(bulls.take(4).joinToString(" "))
                .append('\n')
            if (bears.isNotEmpty()) sb.append("  🔴利空: ").append(bears.take(3).joinToString(" "))
                .append('\n')
            val picks = runCatching { org.json.JSONArray(rec.picksJson) }.getOrNull()
            val names = (0 until (picks?.length() ?: 0)).mapNotNull {
                picks!!.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() }
            }
            if (names.isNotEmpty()) sb.append("  ⭐候选: ").append(names.joinToString(" ")).append('\n')
            val news = runCatching { org.json.JSONArray(rec.newsJson) }.getOrNull()
            for (i in 0 until minOf(3, news?.length() ?: 0)) {
                val t = news!!.optJSONObject(i)?.optString("title") ?: continue
                sb.append("  · ").append(t).append('\n')
            }
            sb.toString().trimEnd()
        } catch (e: Exception) {
            Log.w(TAG, "读取盘中轮情报前导失败: ${e.message}")
            ""
        }
    }

    /** 记录一次盘中轮推送（满足「把新闻和推送的股票存储到数据库」）。 */
    suspend fun logRound(context: Context, title: String, body: String, codes: List<String>) {
        runCatching {
            val db = StockDatabase.getInstance(context)
            val now = LocalDateTime.now()
            db.pushRecordDao().insert(
                PushRecordEntity(
                    tradeDate = now.format(DATE_FMT),
                    slot = if (now.hour < 12) "am" else "pm",
                    createdAt = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    kind = "round", title = title,
                    codes = codes.joinToString(","),
                    ok = true, content = body,
                )
            )
        }.onFailure { Log.w(TAG, "盘中轮留痕失败: ${it.message}") }
    }
}
