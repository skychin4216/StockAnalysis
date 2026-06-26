package com.chin.stockanalysis.stock.database

import java.time.*
import java.time.format.DateTimeFormatter

/**
 * ## 全球市场交易时段检测工具
 *
 * 支持：
 * - A股：9:30-11:30, 13:00-15:00（北京时间 CST, UTC+8）
 * - 美股（纳斯达克/纽交所）：21:30-04:00 冬令时 / 21:30-04:00 夏令时（美东时间 EST/EDT, UTC-5/UTC-4）
 * - 韩股（KOSPI）：08:00-14:30（韩国时间 KST, UTC+9）
 * - 港股（恒生）：09:30-12:00, 13:00-16:00（香港时间 HKT, UTC+8）
 */
object ChinaMarketTradingHours {

    /** 市场类型 */
    enum class Market {
        A股,  // 上海/深圳
        美股,  // 纳斯达克/纽交所
        韩股,  // KOSPI
        港股   // 恒生
    }

    /** 市场状态 */
    enum class MarketStatus {
        交易中,   // 正在交易
        已收盘,   // 今日已收盘
        未开盘,   // 今日尚未开盘
        休市      // 周末/节假日
    }

    /** A股北京时间（CST = UTC+8） */
    private val A股上午开盘 = LocalTime.of(9, 30)
    private val A股上午收盘 = LocalTime.of(11, 30)
    private val A股下午开盘 = LocalTime.of(13, 0)
    private val A股下午收盘 = LocalTime.of(15, 0)

    /** 韩股韩国时间（KST = UTC+9 → 北京时间 = KST - 1h） */
    private val 韩股开盘 = LocalTime.of(7, 0)   // KST 08:00 → CST 07:00
    private val 韩股收盘 = LocalTime.of(13, 30)  // KST 14:30 → CST 13:30

    /** 港股香港时间（HKT = UTC+8，与北京同） */
    private val 港股上午开盘 = LocalTime.of(9, 30)
    private val 港股上午收盘 = LocalTime.of(12, 0)
    private val 港股下午开盘 = LocalTime.of(13, 0)
    private val 港股下午收盘 = LocalTime.of(16, 0)

    /** A股节假日（2025-2026） */
    private val A股休市日: Set<LocalDate> by lazy {
        val raw = listOf(
            "2025-01-01", "2025-01-27","2025-01-28","2025-01-29","2025-01-30","2025-01-31","2025-02-03","2025-02-04",
            "2025-04-04","2025-04-05","2025-04-06", "2025-05-01","2025-05-02","2025-05-03","2025-05-04","2025-05-05",
            "2025-05-31","2025-06-01","2025-06-02",
            "2025-10-01","2025-10-02","2025-10-03","2025-10-04","2025-10-05","2025-10-06","2025-10-07","2025-10-08",
            "2026-01-01","2026-01-02","2026-01-03",
            "2026-02-16","2026-02-17","2026-02-18","2026-02-19","2026-02-20","2026-02-21","2026-02-22","2026-02-23",
            "2026-04-04","2026-04-05","2026-04-06",
            "2026-05-01","2026-05-02","2026-05-03","2026-05-04","2026-05-05",
            "2026-05-31","2026-06-01",
            "2026-10-01","2026-10-02","2026-10-03","2026-10-04","2026-10-05","2026-10-06","2026-10-07"
        )
        raw.map { LocalDate.parse(it) }.toSet()
    }

    /** 获取北京时间的当前时刻 */
    private fun now(): ZonedDateTime = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))

    /** 获取北京时间今天的日期 */
    private fun today(): LocalDate = now().toLocalDate()

    /** 获取北京时间当前时间 */
    private fun nowTime(): LocalTime = now().toLocalTime()

    // ═══════════════════════════════════════════════════════
    // A股
    // ═══════════════════════════════════════════════════════

    /** 判断A股今天是否休市 */
    fun a股是否休市(): Boolean {
        val today = today()
        val day = today.dayOfWeek
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return true
        if (today in A股休市日) return true
        return false
    }

    /** 获取A股当前状态 */
    fun a股状态(): MarketStatus {
        if (a股是否休市()) return MarketStatus.休市
        val now = nowTime()
        return when {
            now < A股上午开盘 -> MarketStatus.未开盘
            now in A股上午开盘..A股上午收盘 -> MarketStatus.交易中
            now in A股上午收盘..A股下午开盘 -> MarketStatus.交易中
            now in A股下午开盘..A股下午收盘 -> MarketStatus.交易中
            else -> MarketStatus.已收盘
        }
    }

    /** A股是否正在交易 */
    fun a股是否交易中(): Boolean = a股状态() == MarketStatus.交易中

    /** A股今日是否已收盘 */
    fun a股是否已收盘(): Boolean = a股状态() == MarketStatus.已收盘

    /** 获取A股收盘时间（北京时间） */
    fun a股收盘时间(): LocalDateTime {
        val today = today()
        return LocalDateTime.of(today, A股下午收盘)
    }

    // ═══════════════════════════════════════════════════════
    // 美股（纳斯达克/纽交所）
    // ═══════════════════════════════════════════════════════

    private fun 美股是否夏令时(): Boolean {
        val today = today()
        val month = today.monthValue
        if (month in 4..10) return true
        if (month == 3) {
            val secondSunday = LocalDate.of(today.year, 3, 1)
                .with(java.time.temporal.TemporalAdjusters.dayOfWeekInMonth(2, DayOfWeek.SUNDAY))
            return today >= secondSunday
        }
        if (month == 11) {
            val firstSunday = LocalDate.of(today.year, 11, 1)
                .with(java.time.temporal.TemporalAdjusters.firstInMonth(DayOfWeek.SUNDAY))
            return today < firstSunday
        }
        return false
    }

    private fun 美股交易时间(): Pair<LocalTime, LocalTime> {
        return if (美股是否夏令时()) {
            LocalTime.of(21, 30) to LocalTime.of(4, 0)
        } else {
            LocalTime.of(22, 30) to LocalTime.of(5, 0)
        }
    }

    fun 美股状态(): MarketStatus {
        val today = today()
        val day = today.dayOfWeek
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return MarketStatus.休市
        val now = nowTime()
        val (open, close) = 美股交易时间()
        return when {
            now >= open -> MarketStatus.交易中
            now < close -> MarketStatus.交易中
            now < open && now >= close -> MarketStatus.已收盘
            else -> MarketStatus.未开盘
        }
    }

    fun 美股是否交易中(): Boolean = 美股状态() == MarketStatus.交易中

    // ═══════════════════════════════════════════════════════
    // 韩股
    // ═══════════════════════════════════════════════════════

    fun 韩股状态(): MarketStatus {
        val today = today()
        val day = today.dayOfWeek
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return MarketStatus.休市
        val now = nowTime()
        return when {
            now < 韩股开盘 -> MarketStatus.未开盘
            now in 韩股开盘..韩股收盘 -> MarketStatus.交易中
            else -> MarketStatus.已收盘
        }
    }

    fun 韩股是否交易中(): Boolean = 韩股状态() == MarketStatus.交易中

    // ═══════════════════════════════════════════════════════
    // 港股
    // ═══════════════════════════════════════════════════════

    fun 港股状态(): MarketStatus {
        val today = today()
        val day = today.dayOfWeek
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return MarketStatus.休市
        val now = nowTime()
        return when {
            now < 港股上午开盘 -> MarketStatus.未开盘
            now in 港股上午开盘..港股上午收盘 -> MarketStatus.交易中
            now in 港股上午收盘..港股下午开盘 -> MarketStatus.交易中
            now in 港股下午开盘..港股下午收盘 -> MarketStatus.交易中
            else -> MarketStatus.已收盘
        }
    }

    // ═══════════════════════════════════════════════════════
    // 综合
    // ═══════════════════════════════════════════════════════

    fun 是否有市场在交易(): Boolean {
        return a股是否交易中() || 美股是否交易中() || 韩股是否交易中() || 港股状态() == MarketStatus.交易中
    }

    fun 获取刷新间隔(): Long {
        return when {
            a股是否交易中() -> 5_000L
            美股是否交易中() -> 10_000L
            韩股是否交易中() -> 10_000L
            港股状态() == MarketStatus.交易中 -> 10_000L
            else -> 60_000L
        }
    }

    fun 获取股票刷新间隔(): Long {
        return when {
            a股是否交易中() -> 3_000L
            美股是否交易中() -> 10_000L
            韩股是否交易中() -> 10_000L
            else -> 60_000L
        }
    }

    fun 获取状态摘要(): String {
        val sb = StringBuilder()
        val 时间格式 = DateTimeFormatter.ofPattern("HH:mm:ss")
        val now = now()
        when (a股状态()) {
            MarketStatus.交易中 -> sb.append("🟢 A股交易中")
            MarketStatus.已收盘 -> sb.append("📌 A股已收盘 ${a股收盘时间().format(时间格式)}")
            MarketStatus.未开盘 -> sb.append("⏳ A股待开盘")
            MarketStatus.休市 -> sb.append("⚫ A股休市")
        }
        sb.append(" | ")
        when (美股状态()) {
            MarketStatus.交易中 -> sb.append("🟢 美股交易中")
            else -> sb.append("")
        }
        if (美股是否交易中()) sb.append(" | ")
        when (韩股状态()) {
            MarketStatus.交易中 -> sb.append("🟢 韩股交易中")
            else -> { /* skip */ }
        }
        sb.append(" | 更新 ${now.format(时间格式)}")
        return sb.toString().trimEnd(' ', '|')
    }

    fun 最近A股交易日(): LocalDate = com.chin.stockanalysis.ui.TradingDayPickerView.recentTradingDay()
}