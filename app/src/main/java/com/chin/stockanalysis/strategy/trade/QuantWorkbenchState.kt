package com.chin.stockanalysis.strategy.trade

import com.chin.stockanalysis.ui.TradingDayPickerView
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * ## 量化工作台公共状态（共享单例）
 *
 * 「交易日 / 仅主板 / 周期选择」是工作台所有周期 Tab 的**公共参数**，
 * 本对象是它们的唯一数据源（相当于各周期页直接访问的 base 级公共参数）。
 *
 * - **周期页**（QuantFragmentBase 子类）在执行建仓/回溯/评估时**主动读取**，
 *   无需工作台逐个推送——未创建的 Tab 创建后天然拿到最新值。
 * - **工作台** 只负责**写入**本状态 + 刷新自己的公共行 UI。
 *
 * 各周期页的周期选择用各自固定的 key（如 "short" / "mid"）存取。
 */
object QuantWorkbenchState {

    /** 公共交易日（📅 交易日行） */
    @Volatile
    var tradeDate: LocalDate = TradingDayPickerView.recentTradingDay()

    /** 公共「仅主板」开关 */
    @Volatile
    var mainBoardOnly: Boolean = true

    /** 各周期页的周期选择（Key = 周期页固定 key，如 "short" / "mid"） */
    private val periodSelections = ConcurrentHashMap<String, Int>()

    /** 读取某周期页的周期选择，未设置时返回 [default] */
    fun selectedPeriodFor(key: String, default: Int): Int = periodSelections[key] ?: default

    /** 写入某周期页的周期选择 */
    fun setSelectedPeriod(key: String, period: Int) {
        periodSelections[key] = period
    }
}
