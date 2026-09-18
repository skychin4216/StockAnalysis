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

    // ═══════════════════════════════════════════════════
    // 🚀 一键建仓批量会话（三周期全部执行完后由工作台弹聚合结果窗）
    // 2026-09-06：一键建仓不再用 Toast 反馈，各周期页 Pipeline 完成后上报，
    // 全部周期（正常 3）收齐后统一弹出可关闭的聚合结果窗口。
    // ═══════════════════════════════════════════════════

    /** 是否处于"一键建仓批量执行"中（各周期完成时上报进度） */
    @Volatile
    var quickBuildActive: Boolean = false

    /** 期望执行的总周期数（正常 3：短线/中线/长线） */
    @Volatile
    var quickBuildTotal: Int = 3

    /** 已完成的周期数（成功/失败都算完成） */
    @Volatile
    var quickBuildDone: Int = 0

    /** 各周期完成时上报的选中股票：(周期名 -> [(代码, 名称, 评分)]) */
    @Volatile
    var quickBuildPicks: MutableMap<String, List<Triple<String, String, Int>>> = LinkedHashMap()

    /** 全部周期完成后的回调（Main 线程触发） */
    @Volatile
    var quickBuildAllDone: (() -> Unit)? = null

    /** 一键建仓开始：重置批量会话（重复点击会被 runQuickBuild 的 active 守卫拦截） */
    fun startQuickBuild(total: Int = 3) {
        quickBuildActive = true
        quickBuildTotal = total
        quickBuildDone = 0
        quickBuildPicks.clear()
    }

    /** 周期页 Pipeline 完成回调（prefix 如 短线/中线/长线，均在 Main 线程调用） */
    fun onPeriodDone(prefix: String, picks: List<Triple<String, String, Int>>) {
        if (!quickBuildActive) return
        quickBuildPicks[prefix] = picks
        quickBuildDone++
        if (quickBuildDone >= quickBuildTotal) {
            quickBuildActive = false
            quickBuildAllDone?.invoke()
        }
    }

    /** 一键建仓结束（成功收齐自动触发；超时兜底由工作台手动调用） */
    fun finishQuickBuild() {
        quickBuildActive = false
    }

    // ═══════════════════════════════════════════════════
    // 📊 ETF 沪深300 门控（工作台公共行小字，2026-09-10）
    // 门控判断的是「大盘结构」这一公共前提——三周期低吸与 ETF 低吸同用一套结论，
    // 原先在 ETF 页独占一整行横幅纯属浪费，现上移到工作台顶部「🚀 一键建仓」同一行、
    // 小字两行显示：line1 = 状态（沪深300 空头排列），line2 = 数值链（4554<MA20(4601)<MA60(4703)）。
    // ETF 页 render() 成功后发布，工作台监听即时刷新；未跑 ETF 时工作台回落到上次值。
    // ═══════════════════════════════════════════════════

    /** 门控状态行（如「沪深300 空头排列」）；空 = 尚无数据，工作台隐藏小字 */
    @Volatile
    var etfGateLine1: String = ""

    /** 门控数值链（如「4554<MA20(4601)<MA60(4703)」） */
    @Volatile
    var etfGateLine2: String = ""

    /** 门控是否通过（多头排列）：true=可低吸（绿），false=暂停低吸（红） */
    @Volatile
    var etfGateOk: Boolean = true

    /** 工作台监听（Main 线程回调）：ETF 页发布后即时刷新公共行小字 */
    @Volatile
    var etfGateListener: ((String, String, Boolean) -> Unit)? = null

    /** ETF 页发布门控状态（render 成功后调用，Main 线程） */
    fun publishEtfGate(line1: String, line2: String, ok: Boolean) {
        etfGateLine1 = line1
        etfGateLine2 = line2
        etfGateOk = ok
        etfGateListener?.invoke(line1, line2, ok)
    }
}
