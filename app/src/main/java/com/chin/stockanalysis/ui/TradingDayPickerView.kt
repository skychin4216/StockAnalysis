package com.chin.stockanalysis.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * ## 交易日选择器组件
 *
 * 格式: `[<] 2026-06-05 [>]`
 * - `<` 切换到上一个交易日（自动跳过周末/假期）
 * - `>` 切换到下一个交易日，到最近交易日时自动禁用
 * - 点击日期文本弹出滚轮样式日期选择器（年/月/日 NumberPicker）
 *
 * 可复用于：模拟交易、量化选股等需要选择历史交易日的场景。
 */
class TradingDayPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    companion object {
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        /** 中国股市假日（2025-2026），需要每年更新 */
        val CHINESE_HOLIDAYS: Set<LocalDate> by lazy {
            val raw = listOf(
                "2025-01-01","2025-01-27","2025-01-28","2025-01-29","2025-01-30","2025-01-31","2025-02-03","2025-02-04",
                "2025-04-04","2025-04-05","2025-04-06","2025-05-01","2025-05-02","2025-05-03","2025-05-04","2025-05-05",
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

        /** 今天（自然日） */
        fun today(): LocalDate = LocalDate.now()

        /**
         * 获取最近一个交易日。
         * < 9:30 返回昨天；跳过周末和中国假期。
         */
        fun recentTradingDay(): LocalDate {
            var d = LocalDate.now()
            if (LocalTime.now() < LocalTime.of(9, 30)) d = d.minusDays(1)
            while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY || d in CHINESE_HOLIDAYS) {
                d = d.minusDays(1)
            }
            return d
        }

        /**
         * 获取上一个交易日（不含当天）。
         * 用于回测拟合：需要 T+1 验证，T 必须是历史日期。
         */
        fun previousTradingDay(): LocalDate {
            var d = recentTradingDay().minusDays(1)
            while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY || d in CHINESE_HOLIDAYS) {
                d = d.minusDays(1)
            }
            return d
        }

        /**
         * 获取下一个交易日。
         * 注意：如果最近交易日 = 今天，则 nextTradingDay 就是今天。
         * 仅当最近交易日 < 今天时，nextTradingDay 才是真正的未来。
         */
        fun nextTradingDay(): LocalDate {
            var d = recentTradingDay().plusDays(1)
            while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY || d in CHINESE_HOLIDAYS) {
                d = d.plusDays(1)
            }
            return d
        }

        /**
         * 判断给定的日期是否是交易日（非周末且非假期）。
         */
        fun isTradingDay(date: LocalDate): Boolean {
            return date.dayOfWeek != DayOfWeek.SATURDAY &&
                   date.dayOfWeek != DayOfWeek.SUNDAY &&
                   date !in CHINESE_HOLIDAYS
        }

        /**
         * 判断 date 是否 >= 最近交易日。
         * true 表示 date 是「今天或未来」，不存在历史交易数据，不适合做回测验证。
         */
        fun isFutureOrToday(date: LocalDate): Boolean = date >= recentTradingDay()

        /**
         * 从指定日期开始，向前推进 n 个交易日。
         * 用于回测中需要跳过周末和假日的场景。
         */
        fun addTradingDays(from: LocalDate, days: Int): LocalDate {
            var d = from
            var added = 0
            while (added < days) {
                d = d.plusDays(1)
                if (isTradingDay(d)) added++
            }
            return d
        }

        /**
         * 从指定日期开始，向后回退 n 个交易日。
         */
        fun subtractTradingDays(from: LocalDate, days: Int): LocalDate {
            var d = from
            var removed = 0
            while (removed < days) {
                d = d.minusDays(1)
                if (isTradingDay(d)) removed++
            }
            return d
        }

        /**
         * 根据日期字符串从数据库查询下一个交易日。
         * 用于回测拟合等需要精确交易日序列的场景。
         * @return 下一个交易日的日期字符串，如果查不到则返回 null
         */
        suspend fun getNextTradingDayFromDb(date: String, datesLimit: Int = 10, getContext: () -> android.content.Context): String? = try {
            val db = com.chin.stockanalysis.stock.database.StockDatabase.getInstance(getContext())
            val dates = db.dailySnapshotDao().getAvailableDates(datesLimit).sorted()
            val idx = dates.indexOf(date)
            if (idx >= 0 && idx + 1 < dates.size) dates[idx + 1] else null
        } catch (_: Exception) { null }
    }

    private val prevBtn: Button
    private val dateTv: TextView
    private val nextBtn: Button

    private var _selectedDate: LocalDate = recentTradingDay()

    /** 当前选中的交易日 */
    var selectedDate: LocalDate
        get() = _selectedDate
        set(value) {
            _selectedDate = value
            refreshUI()
        }

    /** 日期变化回调 (参数: 新的 LocalDate) */
    var onDateChanged: ((LocalDate) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        prevBtn = Button(context).apply {
            text = "<"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1565C0"))
            setPadding(dp(3), dp(0), dp(3), dp(0))
            setMinWidth(0); setMinimumWidth(0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(20)
            )
            setOnClickListener { navigate(-1) }
        }
        addView(prevBtn)

        dateTv = TextView(context).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#E65100"))
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener { showWheelDatePicker() }
        }
        addView(dateTv)

        nextBtn = Button(context).apply {
            text = ">"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1565C0"))
            setPadding(dp(3), dp(0), dp(3), dp(0))
            setMinWidth(0); setMinimumWidth(0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(20)
            )
            setOnClickListener { navigate(+1) }
        }
        addView(nextBtn)

        refreshUI()
    }

    /** 按 delta 天切换交易日，自动跳过非交易日 */
    private fun navigate(delta: Int) {
        var d = _selectedDate.plusDays(delta.toLong())
        while (d.dayOfWeek == DayOfWeek.SATURDAY ||
            d.dayOfWeek == DayOfWeek.SUNDAY ||
            d in CHINESE_HOLIDAYS
        ) {
            d = d.plusDays(delta.toLong())
        }
        val today = recentTradingDay()
        if (d.isAfter(today)) return
        _selectedDate = d
        refreshUI()
        onDateChanged?.invoke(d)
    }

    /** 弹出滚轮样式日期选择器（年/月/日 NumberPicker） */
    private fun showWheelDatePicker() {
        val ctx = context
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        val today = LocalDate.now()
        val minYear = 2020
        val maxYear = today.year

        // ── Year picker ──
        val yearPicker = NumberPicker(ctx).apply {
            minValue = minYear
            maxValue = maxYear
            value = _selectedDate.year
            wrapSelectorWheel = true
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
        }
        val yearLabel = TextView(ctx).apply {
            text = "年"
            textSize = 12f
            setTextColor(Color.parseColor("#666666"))
            setPadding(dp(2), 0, dp(4), 0)
        }
        container.addView(yearPicker)
        container.addView(yearLabel)

        // ── Month picker ──
        val monthPicker = NumberPicker(ctx).apply {
            minValue = 1
            maxValue = 12
            value = _selectedDate.monthValue
            wrapSelectorWheel = true
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setFormatter { i -> String.format("%02d", i) }
        }
        val monthLabel = TextView(ctx).apply {
            text = "月"
            textSize = 12f
            setTextColor(Color.parseColor("#666666"))
            setPadding(dp(2), 0, dp(4), 0)
        }
        container.addView(monthPicker)
        container.addView(monthLabel)

        // ── Day picker ──
        val dayPicker = NumberPicker(ctx).apply {
            minValue = 1
            maxValue = 31
            value = _selectedDate.dayOfMonth
            wrapSelectorWheel = true
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setFormatter { i -> String.format("%02d", i) }
        }
        val dayLabel = TextView(ctx).apply {
            text = "日"
            textSize = 12f
            setTextColor(Color.parseColor("#666666"))
            setPadding(dp(2), 0, 0, 0)
        }
        container.addView(dayPicker)
        container.addView(dayLabel)

        // Sync day max when month/year changes
        val onYmChanged: () -> Unit = {
            val y = yearPicker.value
            val m = monthPicker.value
            val maxDay = LocalDate.of(y, m, 1).lengthOfMonth()
            dayPicker.maxValue = maxDay
            if (dayPicker.value > maxDay) dayPicker.value = maxDay
        }
        yearPicker.setOnValueChangedListener { _, _, _ -> onYmChanged() }
        monthPicker.setOnValueChangedListener { _, _, _ -> onYmChanged() }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("选择日期")
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                val y = yearPicker.value
                val m = monthPicker.value
                val d = dayPicker.value
                val picked = try {
                    LocalDate.of(y, m, d)
                } catch (ex: Exception) {
                    LocalDate.of(y, m, 1)
                }
                recurDate(picked)
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        // Adjust width
        dialog.window?.setLayout(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun recurDate(date: LocalDate) {
        val today = recentTradingDay()
        if (date.isAfter(today)) return
        _selectedDate = date
        refreshUI()
        onDateChanged?.invoke(date)
    }

    /** 刷新日期文本和箭头状态 */
    private fun refreshUI() {
        val today = recentTradingDay()
        val isNonTradingDay = _selectedDate.dayOfWeek == DayOfWeek.SATURDAY ||
                _selectedDate.dayOfWeek == DayOfWeek.SUNDAY ||
                _selectedDate in CHINESE_HOLIDAYS
        dateTv.text = _selectedDate.format(DATE_FMT)
        dateTv.setTextColor(if (isNonTradingDay) Color.parseColor("#EF6C00") else Color.parseColor("#E65100"))
        nextBtn.isEnabled = _selectedDate < today
        nextBtn.alpha = if (_selectedDate < today) 1.0f else 0.4f
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}