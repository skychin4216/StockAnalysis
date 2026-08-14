package com.chin.stockanalysis.strategy.trade

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity

/**
 * ## 大盘转弱离场保护（Market Trend Guard）
 *
 * 解决趋势跟随在「大盘转弱」时无保护、追高接盘的问题：
 * 7 月份连续四天下跌，本应在**第一个大跌日**收手离场、快速止损，
 * 等趋势恢复后再入场。
 *
 * ### 核心设计
 * 1. **多指数强弱检测**：不同板块用对应指数判断强弱，解决「科创板严重偏离上证指数」——
 *    - 科创板（sh688*）→ 科创50（sh000688）
 *    - 创业板（sz300*、sz301*）→ 创业板指（sz399006）
 *    - 沪深主板（其余）→ 上证指数（sh000001）
 * 2. **风险状态机**：每个指数维护 正常(RISK_ON) / 风险预警(RISK_OFF) 两态。
 *    一旦判定转弱进入 RISK_OFF，趋势跟随**暂停新买**并**快速离场**；
 *    只有指数重新走强（站回均线、收复跌幅）才恢复 RISK_ON。
 * 3. **首个大跌日判定**（RISK_ON → RISK_OFF 触发条件，命中任一即离场）：
 *    - 当日跌幅超过阈值（如 -2%），且跌破 MA5；
 *    - 连续 2 日下跌累计跌幅超阈值（如 -2.5%）；
 *    - 收盘跌破 MA10 且 MA5 掉头向下（趋势拐头）。
 * 4. **恢复条件**（RISK_OFF → RISK_ON）：
 *    - 收盘重新站上 MA5 且 MA5>MA10，或连续 3 日不创新低且收复前日跌幅。
 *
 * 状态持久化到 SharedPreferences，跨重启保持，避免重复入场/漏退场。
 */
object MarketTrendGuard {

    private const val TAG = "MarketTrendGuard"
    private const val PREFS_NAME = "market_trend_guard"
    private const val KEY_RISK_OFF = "risk_off_"
    private const val KEY_LAST_DATE = "risk_off_last_date_"
    private const val KEY_STREAK = "risk_off_streak_"

    /** 上证指数（沪深主板默认） */
    const val INDEX_SH = "sh000001"
    /** 科创50 指数（科创板） */
    const val INDEX_STAR = "sh000688"
    /** 创业板指（创业板） */
    const val INDEX_GEM = "sz399006"

    /** 覆盖三类指数，供批量扫描 */
    val ALL_INDICES = listOf(INDEX_SH, INDEX_STAR, INDEX_GEM)

    /** 单个大跌日阈值（%） */
    private const val BIG_DOWN_DAY_PCT = 2.0
    /** 连续下跌累计阈值（%） */
    private const val CONSEC_DROP_SUM_PCT = 2.5
    /** 单日跌幅超过该值视为「暴力下杀」，立即离场 */
    private const val CRASH_DAY_PCT = 3.0
    /** 恢复：站上 MA5 所需连续收盘天数 */
    private const val RECOVER_DAYS = 2

    /**
     * 市场强弱判定结果
     */
    data class MarketState(
        val indexCode: String,
        val indexName: String,
        val riskOff: Boolean,           // 是否处于风险预警（转弱离场）状态
        val downStreak: Int,            // 连续下跌天数
        val todayChangePct: Double,     // 指数当日涨跌%
        val close: Double,
        val ma5: Double,
        val ma10: Double,
        val ma20: Double,
        val reason: String              // 触发/恢复原因描述
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ═══════════════════════════════════════════════════
    // 按板块归属指数
    // ═══════════════════════════════════════════════════

    /**
     * 返回某只股票应对应的指数代码（解决科创板/创业板偏离上证）。
     */
    fun indexForStock(stockCode: String): String {
        val c = stockCode.lowercase()
        return when {
            c.startsWith("sh688") -> INDEX_STAR       // 科创板
            c.startsWith("sz300") || c.startsWith("sz301") -> INDEX_GEM  // 创业板
            else -> INDEX_SH                          // 沪深主板/其他
        }
    }

    fun indexName(indexCode: String): String = when (indexCode) {
        INDEX_STAR -> "科创50"
        INDEX_GEM -> "创业板指"
        else -> "上证指数"
    }

    // ═══════════════════════════════════════════════════
    // 指数强弱评估（不读写持久化，纯基于K线）
    // ═══════════════════════════════════════════════════

    /**
     * 评估单只指数当前的强弱状态（基于最近 N 日K线）。
     *
     * @return MarketState，其中 riskOff 由「当前K线信号」决定（不含历史持久化状态）。
     */
    private fun evaluateIndex(
        indexCode: String,
        snaps: List<DailySnapshotEntity>,
        riskOff: Boolean,
        downStreak: Int
    ): MarketState {
        val latest = snaps.last()
        val closes = snaps.map { it.close }
        val todayChange = latest.changePct

        val ma5 = closes.takeLast(5).average()
        val ma10 = if (closes.size >= 10) closes.takeLast(10).average() else ma5
        val ma20 = if (closes.size >= 20) closes.takeLast(20).average() else ma5

        var newRiskOff = riskOff
        var reason = ""
        var newStreak = downStreak

        // 统计连续下跌天数
        if (todayChange < 0) {
            newStreak = downStreak + 1
        } else {
            newStreak = 0
        }

        if (!riskOff) {
            // ── RISK_ON → 判定是否触发转弱离场 ──
            // 1) 单日暴力下杀
            if (todayChange <= -CRASH_DAY_PCT) {
                newRiskOff = true
                reason = "单日暴跌${"%.2f".format(todayChange)}%（≤-${CRASH_DAY_PCT}%），首个大跌日收手离场"
            }
            // 2) 单日大跌且跌破 MA5（趋势拐头第一天）
            else if (todayChange <= -BIG_DOWN_DAY_PCT && latest.close < ma5) {
                newRiskOff = true
                reason = "单日下跌${"%.2f".format(todayChange)}%（≤-${BIG_DOWN_DAY_PCT}%）且跌破MA5，趋势转弱"
            }
            // 3) 连续下跌累计超阈值（如7月连续四天下跌 → 第2天即触发）
            else if (newStreak >= 2 && todayChange < 0) {
                val sumPct = closes.takeLast(newStreak).zipWithNext().sumOf { (prev, cur) -> (cur - prev) / prev * 100 }
                if (sumPct <= -CONSEC_DROP_SUM_PCT) {
                    newRiskOff = true
                    reason = "连续${newStreak}天下跌累计${"%.2f".format(sumPct)}%（≤-${CONSEC_DROP_SUM_PCT}%），停止新买并离场"
                }
            }
            // 4) 收盘跌破 MA10 且 MA5 掉头（趋势拐头）
            else if (latest.close < ma10 && ma5 < closes.dropLast(1).takeLast(5).average()) {
                newRiskOff = true
                reason = "收盘跌破MA10且MA5掉头向下，趋势拐头，离场保护"
            }
        } else {
            // ── RISK_OFF → 判定是否恢复入场 ──
            // 连续 RECOVER_DAYS 日收盘站上各自 MA5，且最新 MA5>MA10（均线修复）
            val maBull = ma5 > ma10
            var recentAbove = true
            if (snaps.size >= RECOVER_DAYS + 4) {
                for (i in 1..RECOVER_DAYS) {
                    val c = snaps[snaps.size - i].close
                    val win = snaps.subList(0, snaps.size - i + 1)
                    val winMa5 = win.map { it.close }.takeLast(5).average()
                    if (c <= winMa5) { recentAbove = false; break }
                }
            }
            val recovered = recentAbove && maBull
            if (recovered) {
                newRiskOff = false
                reason = "指数连续${RECOVER_DAYS}日站上MA5且MA5>MA10，趋势恢复，可重新入场"
            } else {
                reason = "仍在风险预警：等待指数站回MA5并修复均线"
            }
        }

        return MarketState(
            indexCode = indexCode, indexName = indexName(indexCode),
            riskOff = newRiskOff, downStreak = newStreak,
            todayChangePct = todayChange, close = latest.close,
            ma5 = ma5, ma10 = ma10, ma20 = ma20, reason = reason
        )
    }

    // ═══════════════════════════════════════════════════
    // 对外主入口：检测并持久化
    // ═══════════════════════════════════════════════════

    /**
     * 扫描所有指数并更新风险状态（持久化）。每次调用都会用最新K线刷新状态机。
     *
     * @return 各指数的最新 MarketState（indexCode → state）
     */
    suspend fun refreshAll(context: Context): Map<String, MarketState> {
        val db = StockDatabase.getInstance(context)
        val p = prefs(context)
        val result = mutableMapOf<String, MarketState>()
        for (idx in ALL_INDICES) {
            val snaps = try {
                db.dailySnapshotDao().getByCode(idx, 40).sortedBy { it.date }
            } catch (e: Exception) {
                Log.w(TAG, "读取指数 $idx 失败: ${e.message}")
                emptyList()
            }
            if (snaps.size < 20) {
                Log.w(TAG, "指数 $idx 数据不足(${snaps.size}条)，跳过")
                continue
            }
            val prevRiskOff = p.getBoolean(KEY_RISK_OFF + idx, false)
            val prevStreak = p.getInt(KEY_STREAK + idx, 0)
            val state = evaluateIndex(idx, snaps, prevRiskOff, prevStreak)
            p.edit()
                .putBoolean(KEY_RISK_OFF + idx, state.riskOff)
                .putInt(KEY_STREAK + idx, state.downStreak)
                .putString(KEY_LAST_DATE + idx, snaps.last().date)
                .apply()
            result[idx] = state
            Log.i(TAG, "指数[${state.indexName}] ${if (state.riskOff) "🛑风险预警" else "🟢正常"} 今日${"%.2f".format(state.todayChangePct)}% 连续${state.downStreak}跌 | ${state.reason}")
        }
        return result
    }

    /**
     * 单次检测某只股票对应指数的当前风险状态（不持久化，只读当前K线）。
     * 供分类器/引擎快速判断。
     */
    suspend fun stateForStock(context: Context, stockCode: String): MarketState? {
        val idx = indexForStock(stockCode)
        val db = StockDatabase.getInstance(context)
        val snaps = try {
            db.dailySnapshotDao().getByCode(idx, 40).sortedBy { it.date }
        } catch (e: Exception) { null } ?: return null
        if (snaps.size < 20) return null
        return evaluateIndex(idx, snaps, isRiskOff(context, idx), riskOffStreak(context, idx))
    }

    /**
     * 该股票对应指数是否处于风险预警（转弱离场）。
     */
    suspend fun isRiskOffForStock(context: Context, stockCode: String): Boolean {
        return stateForStock(context, stockCode)?.riskOff ?: false
    }

    /** 读取持久化的风险状态 */
    fun isRiskOff(context: Context, indexCode: String): Boolean =
        prefs(context).getBoolean(KEY_RISK_OFF + indexCode, false)

    /** 读取持久化的连续下跌天数 */
    fun riskOffStreak(context: Context, indexCode: String): Int =
        prefs(context).getInt(KEY_STREAK + indexCode, 0)

    // ═══════════════════════════════════════════════════
    // 供交易引擎使用的判断
    // ═══════════════════════════════════════════════════

    /**
     * 是否应暂停对某股票的新建仓（其所属指数处于风险预警）。
     */
    suspend fun shouldPauseNewBuy(context: Context, stockCode: String): Boolean {
        return isRiskOffForStock(context, stockCode)
    }

    /**
     * 是否应对某趋势跟随持仓强制离场（其所属指数处于风险预警）。
     */
    suspend fun shouldForceExit(context: Context, stockCode: String): Boolean {
        return isRiskOffForStock(context, stockCode)
    }
}
