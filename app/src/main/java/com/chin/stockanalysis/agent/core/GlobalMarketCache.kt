package com.chin.stockanalysis.agent.core

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.strategy.market.MarketAnalyzer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

/**
 * 全局市场环境缓存（Phase E — GlobalMarketCache）
 *
 * ### 为什么需要
 * Scout / QUICK 并行 / 轻量直连三条链路都要一份「大盘环境」（趋势方向、卖出类型、
 * 板块轮动、外围市场、量能状态），三者都调 `MarketAnalyzer.analyze(ctx, emptyList())`。
 * `MarketAnalyzer` 自带的缓存只有 30 秒，而它内部要做 ADX/MFI/板块轮动/外围市场等
 * 重活，所以只要两次调用间隔超过 30 秒（同一次对话里 QUICK 之后接着 DEEP 很常见），
 * 就会整份重算。
 *
 * 本缓存把「无持仓口径」的市场环境按 **交易日 + TTL** 复用：
 * - 同一交易日、TTL 内重复调用 → 直接返回（第二次调用 < 1ms，达成设计文档
 *   「Scout 第二次执行 < 1s」的验证标准）；
 * - 跨交易日自动失效；
 * - **只缓存 `holdingCodes` 为空的调用**——持仓建议与具体持仓强相关，
 *   把 A 的持仓建议串给 B 是错的，故非空口径一律透传不缓存。

 * ### 关于 TTL（对设计文档的一处偏离，理由在此）
 * 文档写的是「日级别」缓存，但盘中指数点位与主力资金是实时变化的，全天不刷新会让
 * Agent 拿到早盘的旧环境，而本 App 恰恰是盘中在用。故收敛为 [DEFAULT_TTL_MS] = 10 分钟，
 * 与盘中选股 10 分钟节奏对齐：既拿到跨任务复用，又把失真窗口压到可接受范围。
 * 需要更强新鲜度时调用 [invalidate] 强制刷新。
 */
object GlobalMarketCache {

    private const val TAG = "GlobalMarketCache"

    /** 市场环境缓存有效时长（毫秒）。10 分钟，对齐盘中选股节奏。 */
    const val DEFAULT_TTL_MS = 10 * 60 * 1000L

    private data class Entry(
        val report: MarketAnalyzer.MarketReport,
        val at: Long,
        val day: String
    )

    @Volatile
    private var entry: Entry? = null

    /** 命中 / 未命中计数（诊断用，随报告一起展示） */
    @Volatile
    private var hits = 0

    @Volatile
    private var misses = 0

    private val lock = Mutex()

    /**
     * 取当日市场环境报告（无持仓口径）。
     *
     * @param holdingCodes 非空时**不缓存**，直接透传给 [MarketAnalyzer.analyze]
     * @param force true = 忽略缓存强制重算
     */
    suspend fun report(
        context: Context,
        holdingCodes: List<String> = emptyList(),
        force: Boolean = false
    ): MarketAnalyzer.MarketReport {
        val cacheable = holdingCodes.isEmpty()

        if (!force && cacheable) {
            fresh()?.let {
                hits++
                Log.i(TAG, "命中市场环境缓存（${System.currentTimeMillis() - it.at}ms 前）")
                return it.report
            }
        }

        // 未命中：加锁后二次确认，避免多个子 Agent（Scout/QUICK 并行）同时开算同一份报告
        return lock.withLock {
            if (!force && cacheable) {
                fresh()?.let {
                    hits++
                    return@withLock it.report
                }
            }
            misses++
            val report = MarketAnalyzer.analyze(context, holdingCodes)
            if (cacheable) {
                entry = Entry(report, System.currentTimeMillis(), LocalDate.now().toString())
            }
            report
        }
    }

    private fun fresh(): Entry? {
        val e = entry ?: return null
        if (e.day != LocalDate.now().toString()) return null           // 跨交易日失效
        if (System.currentTimeMillis() - e.at >= DEFAULT_TTL_MS) return null
        return e
    }

    /** 清空本缓存，并连带清掉 MarketAnalyzer 内部的 30s 缓存。 */
    fun invalidate() {
        entry = null
        MarketAnalyzer.invalidateCache()
        Log.i(TAG, "市场环境缓存已清空")
    }

    /** 命中/未命中统计（诊断用） */
    fun stats(): String = "市场环境缓存 命中 $hits / 未命中 $misses"

    /** 仅供测试：清零统计 */
    fun resetStats() {
        hits = 0
        misses = 0
    }
}
