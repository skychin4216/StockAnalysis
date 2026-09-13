package com.chin.stockanalysis.strategy.trade

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import com.chin.stockanalysis.strategy.trade.macro.MacroEnvironmentAnalyzer
import java.time.LocalDate

/**
 * ## 自动交易持仓引擎（跨周期统一持仓）
 *
 * 满足用户核心诉求：
 * - **总投资 100 万**，跨周期（超短/短/中/长）统一一个资金池
 * - **统一持仓 ≤ 5 只**（选股 ≤ 5 只，一次建仓 ≤ 3 只）
 * - **新票若不如持仓强势，则不做腾笼换鸟**（保留强势持仓）
 * - **每周期符合做T/反T条件 → 自动执行买卖**（复用 TTradeEngine 的做T信号）
 * - 做T买卖的**手数由引擎自动决策**，无需人工
 *
 * ### 四周期 usercase（v3 策略重规划）
 * | 周期 | 选股模式 | 持仓纪律 | 做T策略 |
 * |------|---------|---------|--------|
 * | 超短 | 趋势跟随（牛市） | 快进快出，止盈/止损快 | 有大利润可做T/反T |
 * | 短   | 趋势跟随（牛市） | 跟随趋势，波段持有 | 有大利润可做T/反T |
 * | 中   | 均线粘合 | 底仓不动 | 以**做T降成本**为主 |
 * | 长   | 均线粘合 | 坚持持有 | 极少做T，仅极端波动 |
 *
 * ### 资金分配（单只上限 20%，遵循「单只不超两成仓位」）
 * - 底仓：单只建仓 ≤ 总资金的 20%（20 万）
 * - 做T资金：不超过单只持仓市值的 40%
 * - 现金保留：始终保留 ≥ 10%（10 万）应对回撤/新机会
 */
class AutoTradePortfolioEngine(private val context: Context) {

    companion object {
        private const val TAG = "AutoTradePortfolio"

        /** 总投资上限（元） */
        const val TOTAL_CAPITAL = 1_000_000.0

        /** 单只持仓市值占比上限 */
        const val MAX_SINGLE_POSITION_RATIO = 0.20

        /** 统一持仓数量上限 */
        const val MAX_HOLDINGS = 5

        /** 单次建仓数量上限 */
        const val MAX_OPEN_PER_ROUND = 3

        /** 选股池上限 */
        const val MAX_SELECT = 5

        /** 现金保留比例 */
        const val MIN_CASH_RATIO = 0.10

        /** 做T数量占底仓比例 */
        const val T_QTY_RATIO = 0.40

        /** 趋势跟随模式周期：超短/短线 */
        val TREND_FOLLOW_PERIODS = setOf("UltraShortQuant", "ShortTermQuant")

        /** 周期映射 */
        const val PERIOD_ULTRA_SHORT = "UltraShortQuant"
        const val PERIOD_SHORT = "ShortTermQuant"
        const val PERIOD_MID = "MidTermQuant"
        const val PERIOD_LONG = "LongTermQuant"

        /** 周期中文名 */
        fun periodTitle(period: String): String = when (period) {
            PERIOD_ULTRA_SHORT -> "超短线"
            PERIOD_SHORT -> "短线"
            PERIOD_MID -> "中线"
            PERIOD_LONG -> "长线"
            else -> period
        }
    }

    /**
     * 统一持仓项
     */
    data class PortfolioPosition(
        val stockCode: String,
        val stockName: String,
        val periodType: String,
        var quantity: Int,          // 底仓股数
        var avgCost: Double,        // 底仓平均成本
        var marketValue: Double,    // 最新市值
        var pnlPct: Double,         // 盈亏%
        var strengthScore: Int      // 强势评分（越高越强）
    )

    /**
     * 一次做T/反T决策（引擎自动决定买卖数量）
     */
    data class TTradeDecision(
        val stockCode: String,
        val stockName: String,
        val periodType: String,
        val tradeType: TTradeType,
        val price: Double,
        val quantity: Int,
        val reason: String,
        val expectedProfitPct: Double
    )

    // ── 运行时状态 ──
    private val holdings = mutableListOf<PortfolioPosition>()

    /** 当前可用现金 */
    var cash: Double = TOTAL_CAPITAL
        private set

    /** 是否启用自动做T */
    var autoTEnable: Boolean = true

    /** 是否启用自动建仓 */
    var autoOpenEnable: Boolean = true

    /** 做T引擎（复用现有做T信号与执行） */
    private val tEngine = TTradeEngine(context)

    // ═══════════════════════════════════════════════════
    // 1. 持仓状态
    // ═══════════════════════════════════════════════════

    /** 当前统一持仓（跨周期合并） */
    fun getHoldings(): List<PortfolioPosition> = holdings.toList()

    /** 当前持仓数量 */
    fun holdingCount(): Int = holdings.size

    /** 总持仓市值 */
    fun totalMarketValue(): Double = holdings.sumOf { it.marketValue }

    /** 总资产 = 现金 + 持仓市值 */
    fun totalAsset(): Double = cash + totalMarketValue()

    /** 已用仓位比例 */
    fun positionRatio(): Double = if (TOTAL_CAPITAL > 0) totalMarketValue() / TOTAL_CAPITAL else 0.0

    // ═══════════════════════════════════════════════════
    // 2. 建仓（一次最多 3 只，持仓 ≤ 5）
    // ═══════════════════════════════════════════════════

    /**
     * 自动建仓
     *
     * @param candidates 当日选股（已按强度降序），每项 (code, name, period, price, score)
     * @param snapsOf 取K线快照函数（供做T信号与风控）
     * @return 执行的建仓决策
     */
    suspend fun autoOpen(
        candidates: List<Candidate>,
        snapsOf: suspend (String) -> List<DailySnapshotEntity>?
    ): List<Pair<PortfolioPosition, TTradeDecision?>> {
        if (!autoOpenEnable) return emptyList()
        val executed = mutableListOf<Pair<PortfolioPosition, TTradeDecision?>>()
        var openedCount = 0

        for (c in candidates.take(MAX_OPEN_PER_ROUND)) {
            // 持仓已达上限，或已持有该股则跳过
            if (holdings.size >= MAX_HOLDINGS) break
            if (holdings.any { it.stockCode == c.code }) continue

            // ── 大盘转弱 + 指数偏离回调双重保护：暂停新建仓（趋势跟随） ──
            // 1) 大盘转弱：7月连续四天下跌，首个大跌日应收手，等趋势恢复再入场。
            // 2) 板块指数偏离：科创50/创业板涨幅远超上证(均值回归风险)，即使大盘未转弱，
            //    该板块高位趋势股也可能补跌 → 暂停追高。
            if (c.period in TREND_FOLLOW_PERIODS) {
                try {
                    if (MarketTrendGuard.shouldPauseNewBuy(context, c.code)) {
                        Log.w(TAG, "🛑 大盘转弱保护：暂停建仓 ${c.name}(${c.code}) [${c.period}]")
                        continue
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "大盘转弱保护检查失败: ${e.message}")
                }
                try {
                    if (MacroEnvironmentAnalyzer.shouldRiskControlTrendFollow(context, c.code)) {
                        Log.w(TAG, "🛑 板块偏离回调保护：暂停追高 ${c.name}(${c.code}) [${c.period}]")
                        continue
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "板块偏离回调检查失败: ${e.message}")
                }
            }

            // 单只上限 20%（约 20 万）
            val budget = minOf(TOTAL_CAPITAL * MAX_SINGLE_POSITION_RATIO, cash)
            if (budget <= 0 || c.price <= 0) continue

            // 算出手数（100 股/手），买整手
            var lots = (budget / c.price / 100).toInt()
            if (lots <= 0) continue
            // 买入后保留至少 10% 现金（新机会 + 回撤缓冲）
            val cost = lots * 100 * c.price
            if (cash - cost < TOTAL_CAPITAL * MIN_CASH_RATIO) {
                // 收紧到手数，确保现金底线
                val maxLots = ((cash - TOTAL_CAPITAL * MIN_CASH_RATIO) / c.price / 100).toInt()
                if (maxLots <= 0) continue
                lots = maxLots
            }

            val qty = lots * 100
            val costFinal = qty * c.price
            cash -= costFinal

            val pos = PortfolioPosition(
                stockCode = c.code, stockName = c.name, periodType = c.period,
                quantity = qty, avgCost = c.price, marketValue = costFinal,
                pnlPct = 0.0, strengthScore = c.score
            )
            holdings.add(pos)

            // 建仓后立即评估做T（若符合则自动执行首笔做T）
            var tDecision: TTradeDecision? = null
            if (autoTEnable) {
                val snaps = snapsOf(c.code)
                if (snaps != null) {
                    tDecision = decideTTrade(pos, snaps)
                    tDecision?.let { executeTTrade(it) }
                }
            }
            executed.add(pos to tDecision)
            openedCount++
            if (openedCount >= MAX_OPEN_PER_ROUND) break
        }
        return executed
    }

    // ═══════════════════════════════════════════════════
    // 3. 自动做T/反T（每周期符合即自动执行买卖）
    // ═══════════════════════════════════════════════════

    /**
     * 对全持仓扫描，自动执行符合的做T/反T（自动决定手数）
     *
     * @param snapsOf 取K线快照函数
     * @return 本次执行的做T决策列表
     */
    suspend fun autoRunTTrades(
        snapsOf: suspend (String) -> List<DailySnapshotEntity>?
    ): List<TTradeDecision> {
        if (!autoTEnable) return emptyList()
        val executed = mutableListOf<TTradeDecision>()
        for (pos in holdings.toList()) {
            val snaps = snapsOf(pos.stockCode) ?: continue
            val decision = decideTTrade(pos, snaps)
            if (decision != null) {
                executeTTrade(decision)
                executed.add(decision)
            }
        }
        return executed
    }

    /**
     * 判断并生成某持仓的做T/反T决策（引擎自动决定数量）。
     *
     * 基于日内做T信号（TTradeEngine.generateSignals），取置信度最高的可执行信号，
     * 数量 = 底仓 × T_QTY_RATIO（四舍五入到整手）。
     */
    private suspend fun decideTTrade(
        pos: PortfolioPosition,
        snaps: List<DailySnapshotEntity>
    ): TTradeDecision? {
        if (snaps.isEmpty()) return null

        // 中线以做T降成本为主：更高频（更低 T 阈值）
        // 长线极少做T：仅当日内振幅极大
        val period = pos.periodType
        val latest = snaps.last()
        val price = latest.close
        if (price <= 0) return null

        // 计算日内振幅（近 5 日 high/low），作为做T可行性门槛
        val win = snaps.takeLast(5)
        val hi = win.maxOfOrNull { it.high } ?: return null
        val lo = win.minOfOrNull { it.low } ?: return null
        val amplitudePct = if (lo > 0) (hi - lo) / lo * 100 else 0.0

        // 各周期做T的最低振幅门槛（波动太小不值得做T）
        val minAmp = when (period) {
            PERIOD_MID -> 2.0      // 中线做T为主，要求 ≥2% 日内空间
            PERIOD_ULTRA_SHORT -> 2.5
            PERIOD_SHORT -> 2.0
            PERIOD_LONG -> 4.0     // 长线仅极端波动才做T
            else -> 2.0
        }
        if (amplitudePct < minAmp) return null

        // 复用 TTradeEngine 生成做T信号
        val signals = tEngine.generateSignals(pos.stockCode, pos.quantity, period)
        if (signals.isEmpty()) return null

        // 选择最优信号：开仓腿（T_BUY 低吸 / RT_SELL 高抛）优先，配对腿自动完成
        val best = signals.maxByOrNull {
            it.confidence + it.expectedProfitPct
        } ?: return null
        if (best.confidence < 55) return null   // 置信度门槛，过滤噪声

        // 做T数量：底仓 × 40%，四舍五入到整手
        // B5: 不足一手不做T，避免小底仓（如100股）被放大为满仓做T
        var tQty = ((pos.quantity * T_QTY_RATIO).toInt() / 100) * 100
        if (tQty <= 0) return null

        // 做T买入/卖出数量均不能超过底仓（做T买入在T+1后也须能卖出；反T卖出保证当日能买回）
        tQty = minOf(tQty, pos.quantity / 100 * 100)

        // 买入类决策（T_BUY 做T买入 / RT_BUY 反T买回）需确保现金充足
        if ((best.signalType == TTradeType.T_BUY || best.signalType == TTradeType.RT_BUY) &&
            cash < price * tQty
        ) return null

        return TTradeDecision(
            stockCode = pos.stockCode, stockName = pos.stockName, periodType = period,
            tradeType = best.signalType, price = price, quantity = tQty,
            reason = best.reason, expectedProfitPct = best.expectedProfitPct
        )
    }

    /**
     * 执行做T决策（调用 TTradeEngine 落库）
     */
    private suspend fun executeTTrade(decision: TTradeDecision) {
        val signal = TTradeSignal(
            stockCode = decision.stockCode, stockName = decision.stockName,
            signalType = decision.tradeType, suggestedPrice = decision.price,
            targetPrice = decision.price, quantity = decision.quantity,
            reason = decision.reason, expectedProfitPct = decision.expectedProfitPct,
            periodType = decision.periodType, confidence = 60
        )
        val tradeId = tEngine.executeTTrade(signal, decision.periodType)
        // B6: 做T/反T资金与持仓同步，做T利润计入现金与总资产
        if (tradeId > 0) {
            val cost = decision.price * decision.quantity
            val pos = holdings.firstOrNull {
                it.stockCode == decision.stockCode && it.periodType == decision.periodType
            }
            when (decision.tradeType) {
                TTradeType.T_BUY -> cash -= cost                       // 做T买入：占用现金（T+1后由T_SELL收回）
                TTradeType.T_SELL -> cash += cost                      // 做T卖出：收回现金（含做T利润）
                TTradeType.RT_SELL -> {                                // 反T卖出：底仓临时减少，现金增加
                    if (pos != null && pos.quantity >= decision.quantity) {
                        cash += cost
                        pos.quantity -= decision.quantity
                        pos.marketValue = pos.avgCost * pos.quantity
                    }
                }
                TTradeType.RT_BUY -> {                                 // 反T买回：底仓恢复，现金减少（净效果=反T利润）
                    if (pos != null && cash >= cost) {
                        cash -= cost
                        pos.quantity += decision.quantity
                        pos.marketValue = pos.avgCost * pos.quantity
                    }
                }
            }
        }
        Log.i(TAG, "🤖 自动${decision.tradeType.label} ${decision.stockName} " +
            "${decision.quantity}股 @${decision.price} (${decision.periodType})")
    }

    // ═══════════════════════════════════════════════════
    // 4. 持仓更新 + 强弱评估 + 腾笼换鸟
    // ═══════════════════════════════════════════════════

    /**
     * 用最新K线更新持仓市值/盈亏，并计算强势评分。
     * 新票若不如现有持仓强，则不腾笼换鸟（保留强势）。
     */
    suspend fun refreshPositions(
        snapsOf: suspend (String) -> List<DailySnapshotEntity>?
    ) {
        for (pos in holdings) {
            val snaps = snapsOf(pos.stockCode) ?: continue
            val latest = snaps.last()
            pos.marketValue = latest.close * pos.quantity
            pos.pnlPct = if (pos.avgCost > 0) (latest.close - pos.avgCost) / pos.avgCost * 100 else 0.0
            pos.strengthScore = scoreStrength(pos.stockCode, snaps, pos.pnlPct)
        }
    }

    /**
     * 强势评分：用于「新票不如持仓强则不换」的决策。
     * 综合：多头排列、贴近新高、动量、盈亏。
     */
    private fun scoreStrength(code: String, snaps: List<DailySnapshotEntity>, pnlPct: Double): Int {
        if (snaps.size < 20) return 0
        var score = 0
        val closes = snaps.map { it.close }
        val latest = snaps.last()

        // 多头排列 MA5>MA10>MA20
        val ma5 = closes.takeLast(5).average()
        val ma10 = closes.takeLast(10).average()
        val ma20 = closes.takeLast(20).average()
        if (ma5 > ma10 && ma10 > ma20) score += 30
        else if (ma5 > ma10) score += 15

        // 贴近新高（距20日高点回撤 < 8%）
        val hi20 = snaps.takeLast(20).maxOfOrNull { it.high } ?: latest.high
        val dd = if (hi20 > 0) (hi20 - latest.close) / hi20 * 100 else 99.0
        if (dd < 3.0) score += 25
        else if (dd < 8.0) score += 15

        // 动量（近5日涨幅）
        if (closes.size >= 6) {
            val chg5 = (latest.close - closes[closes.size - 6]) / closes[closes.size - 6] * 100
            if (chg5 > 5) score += 20
            else if (chg5 > 0) score += 10
        }

        // 盈亏正向加成
        if (pnlPct > 5) score += 15
        else if (pnlPct > 0) score += 8
        return score
    }

    /**
     * 腾笼换鸟：判断是否用新候选替换最弱持仓。
     * 规则：仅当新票强势评分 **显著高于** 当前最弱持仓（如 ≥ +15 分）才换，
     * 否则保留强势持仓（不腾笼换鸟）。
     *
     * @return 需要被替换的持仓（null=不换）
     */
    fun shouldSwap(newScore: Int): PortfolioPosition? {
        if (holdings.isEmpty()) return null
        val weakest = holdings.minByOrNull { it.strengthScore } ?: return null
        // 新票不够强 → 不换
        if (newScore <= weakest.strengthScore + 15) return null
        return weakest
    }

    // ═══════════════════════════════════════════════════
    // 5. 卖出（止盈/止损）
    // ═══════════════════════════════════════════════════

    /**
     * 自动止盈/止损扫描。
     * - 止损：超短 -2%、短 -5%、中 -8%、长 -10%（按周期差异化）
     * - 止盈：趋势转弱（多头排列破坏）时获利了结
     *
     * @return 卖出的持仓
     */
    suspend fun autoScanSell(
        snapsOf: suspend (String) -> List<DailySnapshotEntity>?
    ): List<PortfolioPosition> {
        val toSell = mutableListOf<PortfolioPosition>()
        for (pos in holdings.toList()) {
            val snaps = snapsOf(pos.stockCode) ?: continue
            val latest = snaps.last()
            val price = latest.close
            val pnl = pos.pnlPct

            // 止损线（周期差异化）
            val stopLoss = when (pos.periodType) {
                PERIOD_ULTRA_SHORT -> -2.0
                PERIOD_SHORT -> -5.0
                PERIOD_MID -> -8.0
                PERIOD_LONG -> -10.0
                else -> -8.0
            }
            // 止盈：多头排列破坏 + 有利润
            val closes = snaps.map { it.close }
            val ma5 = closes.takeLast(5).average()
            val ma10 = closes.takeLast(10).average()
            val ma20 = closes.takeLast(20).average()
            val trendBroken = !(ma5 > ma10 && ma10 > ma20)

            // ── 大盘转弱 + 板块偏离回调双重离场保护：趋势跟随持仓（超短/短）立即离场 ──
            // 1) 大盘转弱：不等个股跌到止损线，大盘首个大跌日就快速卖出，避免连续下跌被深套。
            // 2) 板块偏离回调：板块涨幅远超大盘(均值回归风险)，高位趋势股可能补跌 → 提前止盈/离场。
            if (pos.periodType in TREND_FOLLOW_PERIODS) {
                try {
                    if (MarketTrendGuard.shouldForceExit(context, pos.stockCode)) {
                        Log.w(TAG, "🛑 大盘转弱保护：强制离场 ${pos.stockName}(${pos.stockCode}) [${pos.periodType}]")
                        toSell.add(pos)
                        continue
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "大盘转弱离场检查失败: ${e.message}")
                }
                try {
                    if (MacroEnvironmentAnalyzer.shouldRiskControlTrendFollow(context, pos.stockCode)) {
                        Log.w(TAG, "🛑 板块偏离回调保护：提前离场 ${pos.stockName}(${pos.stockCode}) [${pos.periodType}]")
                        toSell.add(pos)
                        continue
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "板块偏离回调检查失败: ${e.message}")
                }
            }

            if (pnl <= stopLoss) {
                toSell.add(pos)
            } else if (pnl > 5 && trendBroken) {
                toSell.add(pos)
            }
        }
        for (pos in toSell) {
            sellPosition(pos, snapsOf(pos.stockCode)?.last()?.close ?: pos.avgCost)
        }
        return toSell
    }

    /**
     * 卖出持仓：回收资金（底仓市值入账）
     */
    private suspend fun sellPosition(pos: PortfolioPosition, price: Double) {
        cash += pos.quantity * price
        holdings.remove(pos)
        Log.i(TAG, "💸 清仓 ${pos.stockName}(${pos.stockCode}) @${price} (${pos.periodType})")
    }

    /**
     * 候选股（建仓输入）
     */
    data class Candidate(
        val code: String,
        val name: String,
        val period: String,
        val price: Double,
        val score: Int
    )
}
