package com.chin.stockanalysis.stock.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * ## A股交易日历
 *
 * 核心功能：
 * 1. 本地快速判断（周末/时间枚举）
 * 2. 网络查询实际交易日（排除节假日，如春节/国庆）
 * 3. 计算距下一个交易窗口的时间（用于精确 TTL）
 *
 * 嵌套数据类 [MarketStatus] 完整描述当前市场状态。
 */
object TradingCalendar {

    private const val TAG = "TradingCalendar"
    private const val CACHE_VALID_MS = 60 * 60 * 1000L  // 网络查询结果缓存 1 小时

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /** 缓存的网络查询结果 */
    @Volatile
    private var cachedNetworkResult: NetworkTradingInfo? = null

    // ═══════════════════════════════
    // 数据模型
    // ═══════════════════════════════

    enum class Session {
        PRE_OPEN,       // 0:00 ~ 9:30 盘前
        MORNING,        // 9:30 ~ 11:30 早盘
        LUNCH,          // 11:30 ~ 13:00 午休
        AFTERNOON,      // 13:00 ~ 15:00 下午盘
        AFTER_HOURS,    // 15:00 ~ 24:00 收盘后
        WEEKEND_HOLIDAY // 周末/节假日
    }

    data class MarketStatus(
        val isTradingDay: Boolean,
        val session: Session,
        /** 当前会话的开始时间 */
        val sessionStart: LocalDateTime,
        /** 距离下一个交易窗口开始的毫秒数（用于设置 TTL） */
        val nextTickMs: Long
    )

    private data class NetworkTradingInfo(
        val isTradingDay: Boolean,
        val fetchTime: Long = System.currentTimeMillis()
    )

    // ═══════════════════════════════
    // 公开 API
    // ═══════════════════════════════

    /**
     * 获取当前市场状态，优先本地快速判断，
     * 周内时异步查询网络交易日历。
     */
    suspend fun getMarketStatus(): MarketStatus = withContext(Dispatchers.Default) {
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val dayOfWeek = today.dayOfWeek

        // 1. 周末快速返回
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            return@withContext MarketStatus(
                isTradingDay = false,
                session = Session.WEEKEND_HOLIDAY,
                sessionStart = today.atStartOfDay(),
                nextTickMs = millisToNextTradingDay(today, now)
            )
        }

        // 2. 周内 → 本地时间判断会话，网络查询是否实际交易日
        val localSession = getLocalSession(now.toLocalTime())
        val isTradingDay = when (localSession) {
            Session.WEEKEND_HOLIDAY -> false
            else -> queryIsTradingDay(today)
        }

        if (!isTradingDay) {
            return@withContext MarketStatus(
                isTradingDay = false,
                session = Session.WEEKEND_HOLIDAY,
                sessionStart = today.atStartOfDay(),
                nextTickMs = millisToNextTradingDay(today, now)
            )
        }

        // 3. 交易日 → 按会话计算下一个 tick
        MarketStatus(
            isTradingDay = true,
            session = localSession,
            sessionStart = getSessionStart(today, localSession),
            nextTickMs = getNextTickMs(today, now, localSession)
        )
    }

    /**
     * 获取针对当前市场状态的最优缓存 TTL（毫秒）
     */
    suspend fun getOptimalCacheTTL(): Long {
        val status = getMarketStatus()
        return when (status.session) {
            Session.MORNING, Session.AFTERNOON -> 5_000L       // 交易中 5 秒
            Session.LUNCH -> 60_000L                            // 午休 1 分钟
            Session.AFTER_HOURS -> status.nextTickMs            // 收盘 → 到下个交易日
            Session.PRE_OPEN -> minOf(5 * 60_000L, status.nextTickMs)  // 盘前 5 分钟或到开盘
            Session.WEEKEND_HOLIDAY -> status.nextTickMs        // 周末 → 到下个交易日
        }
    }

    /**
     * 返回人类可读的市场状态描述
     */
    suspend fun getStatusDescription(): String {
        val s = getMarketStatus()
        return when {
            !s.isTradingDay && s.session == Session.WEEKEND_HOLIDAY -> "今日休市"
            s.session == Session.MORNING -> "早盘交易中"
            s.session == Session.AFTERNOON -> "下午盘交易中"
            s.session == Session.LUNCH -> "午间休市"
            s.session == Session.AFTER_HOURS -> "已收盘"
            s.session == Session.PRE_OPEN -> "盘前"
            else -> "未知"
        }
    }

    // ═══════════════════════════════
    // 内部实现
    // ═══════════════════════════════

    private fun getLocalSession(time: LocalTime): Session = when {
        time < LocalTime.of(9, 30)  -> Session.PRE_OPEN
        time < LocalTime.of(11, 30) -> Session.MORNING
        time < LocalTime.of(13, 0)  -> Session.LUNCH
        time < LocalTime.of(15, 0)  -> Session.AFTERNOON
        else -> Session.AFTER_HOURS
    }

    private fun getSessionStart(today: LocalDate, session: Session): LocalDateTime = when (session) {
        Session.PRE_OPEN    -> today.atStartOfDay()
        Session.MORNING     -> today.atTime(LocalTime.of(9, 30))
        Session.LUNCH       -> today.atTime(LocalTime.of(11, 30))
        Session.AFTERNOON   -> today.atTime(LocalTime.of(13, 0))
        Session.AFTER_HOURS -> today.atTime(LocalTime.of(15, 0))
        Session.WEEKEND_HOLIDAY -> today.atStartOfDay()
    }

    private fun getNextTickMs(today: LocalDate, now: LocalDateTime, session: Session): Long {
        return when (session) {
            Session.PRE_OPEN -> {
                val open = today.atTime(LocalTime.of(9, 30))
                val diff = Duration.between(now, open).toMillis()
                if (diff > 0) diff else 5_000L
            }
            Session.MORNING -> 5_000L
            Session.LUNCH -> {
                val afternoon = today.atTime(LocalTime.of(13, 0))
                Duration.between(now, afternoon).toMillis()
            }
            Session.AFTERNOON -> 5_000L
            Session.AFTER_HOURS -> millisToNextTradingDay(today, now)
            Session.WEEKEND_HOLIDAY -> millisToNextTradingDay(today, now)
        }
    }

    /**
     * 计算距下一个交易日 9:30 的毫秒数。
     */
    private fun millisToNextTradingDay(today: LocalDate, now: LocalDateTime): Long {
        val next = com.chin.stockanalysis.ui.TradingDayPickerView.addTradingDays(today, 1)
        val target = next.atTime(LocalTime.of(9, 30))
        return Duration.between(now, target).toMillis()
    }

    // ═══════════════════════════════
    // 网络查询
    // ═══════════════════════════════

    /**
     * 通过东方财富交易日历 API 查询今天是否为交易日。
     * 查询结果缓存 1 小时。
     */
    private suspend fun queryIsTradingDay(date: LocalDate): Boolean = withContext(Dispatchers.IO) {
        // 检查缓存
        cachedNetworkResult?.let {
            if (System.currentTimeMillis() - it.fetchTime < CACHE_VALID_MS) {
                Log.v(TAG, "交易日历缓存命中: trading=${it.isTradingDay}")
                return@withContext it.isTradingDay
            }
        }

        try {
            val dateStr = date.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
            val url = "https://push2.eastmoney.com/api/qt/clist/get?" +
                "pn=1&pz=1&po=1&np=1&fltt=2&invt=2&fid=f62" +
                "&fs=m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23" +
                "&fields=f12"

            val request = Request.Builder()
                .url("https://push2.eastmoney.com/api/qt/kline/get?" +
                    "secid=1.000001&klt=101&fqt=0&beg=$dateStr&end=$dateStr")
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            val json = JSONObject(body)

            // 如果当天有 K 线数据返回 → 交易日
            val klines = json.optJSONObject("data")?.optJSONArray("klines")
            val isTrading = klines != null && klines.length() > 0

            cachedNetworkResult = NetworkTradingInfo(isTradingDay = isTrading)
            Log.d(TAG, "📅 交易日历查询: ${date} ${if (isTrading) "是" else "不是"}交易日")
            isTrading
        } catch (e: Exception) {
            Log.w(TAG, "交易日历查询失败，默认假设为交易日: ${e.message}")
            // 网络失败时：周一到周五默认是交易日
            val dayOfWeek = date.dayOfWeek
            val isTrading = dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY
            cachedNetworkResult = NetworkTradingInfo(isTradingDay = isTrading)
            isTrading
        }
    }
}