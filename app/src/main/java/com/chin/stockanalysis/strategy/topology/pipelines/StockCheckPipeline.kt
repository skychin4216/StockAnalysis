package com.chin.stockanalysis.strategy.topology.pipelines

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.backtest.BacktestParamsLoader
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import com.chin.stockanalysis.strategy.data.DirectionAnalyzer
import kotlin.math.ceil

/**
 * ## 个股分析 Pipeline — 均线多头粘合选股（四周期版）
 *
 * 参考「均线多头粘合选股」框架，以**粘合度**为核心指标，
 * 不同周期使用不同参数和检查条件组合：
 *
 * 粘合度 = (MAX(MAs) - MIN(MAs)) / MIN(MAs) × 100%
 *
 * | 周期   | 均线  | 粘合度 | 时长  | 量能       | 跌幅   | 特殊                   |
 * |--------|-------|--------|-------|-----------|--------|------------------------|
 * | 超短线 | 3线   | ≤3%   | ≥5天  | 爆量2.5x  | 非高位 | 涨幅>4%               |
 * | 短线   | 4线   | ≤3%   | ≥10天 | 放量1.5x  | ≥20%  | 收盘站上所有均线        |
 * | 中线   | 4线   | ≤2.5% | ≥15天 | 温和1.2-1.8x | ≥30% | MA60上翘+站稳均线     |
 * | 长线   | 4线   | ≤2%   | ≥20天 | 地量<50%  | ≥40%  | MA60+MA250上翘+站稳年线 |
 *
 * 检查项（各周期启用不同子集，passCount 只统计本周期要求的项）：
 * 1. 粘合度 ≤ 阈值
 * 2. 多头排列 MA5>MA10>MA20[>MA60]
 * 3. 粘合持续 ≥ N 天
 * 4. 量能条件（爆量/放量/温和/地量）
 * 5. 距高点跌幅 ≥ 阈值
 * 6. MA60 上升（仅 requireMA60Rising=true 时计入）
 * 7. 站稳年线（仅 requireAboveYearLine=true 时计入）
 * 8. 涨幅达标（仅 requireChangePct=true 时计入）
 * 9. 站上所有均线（仅 requireAboveAllMAs=true 时计入）
 */
class StockCheckPipeline(
    /** 粘合度阈值（百分比，如 3.0 = 3%） */
    val convergenceThreshold: Double = 3.0,
    /** 是否使用 MA60（四线 vs 三线） */
    val useMA60: Boolean = true,
    /** 粘合持续天数要求（在 lookback 窗口内） */
    val convergenceDurationDays: Int = 10,
    /** 粘合时长通过比例（设计文档 COUNT(粘合<=X+0.5, N) >= N-1，即 80%：超短 4/5、短 8/10、中 12/15、长 16/20） */
    val convergenceDurationRatio: Double = 0.8,
    /** 放量倍数阈值（当日量 / 5日均量 ≥ 此值） */
    val volumeBreakoutRatio: Double = 1.5,
    /** 最低涨幅要求（%），需配合 requireChangePct=true */
    val minChangePct: Double = 0.0,
    /** 是否启用涨幅检查 */
    val requireChangePct: Boolean = false,
    /** 距高点最低跌幅要求（%），0 = 不要求 */
    val minDrawdownPct: Double = 0.0,
    /** 是否要求 MA60 上升 */
    val requireMA60Rising: Boolean = false,
    /** MA60/MA250 上翘比较天数（默认 5 天，长线设计为 10 天） */
    val maRisingDays: Int = 5,
    /** 是否要求 MA250 上升（长线：MA250>REF(MA250,10)） */
    val requireMA250Rising: Boolean = false,
    /** 多头排列是否包含 MA60>MA250（长线） */
    val useMA250InBullish: Boolean = false,
    /** 是否要求收盘价远离粘合区上沿 >2%（超短线突破强度） */
    val requireCloseAboveConvergenceTop: Boolean = false,
    /** 是否要求开盘价低于三线且收盘站上5日线（超短线开盘条件） */
    val requireOpenBelowMAs: Boolean = false,
    /** 是否要求地量（10日均量 < 60日均量 × volumeShrinkRatio） */
    val requireVolumeShrink: Boolean = false,
    /** 地量比例阈值 */
    val volumeShrinkRatio: Double = 0.5,
    /** 是否要求价格站稳年线（MA250）上方 */
    val requireAboveYearLine: Boolean = false,
    /** 是否要求收盘站上所有均线 */
    val requireAboveAllMAs: Boolean = false,
    /** 温和放量下限 */
    val moderateVolumeLower: Double = 0.0,
    /** 温和放量上限 */
    val moderateVolumeUpper: Double = 0.0,
    /** 回溯天数 */
    val lookbackDays: Int = 60,
    /** 通过所需最少项数（应等于 totalChecks） */
    val minPassCount: Int = 7,
    // ── v2: 大盘感知 + 摆动高点 + 三日确认 ──
    /** 大盘趋势（"BULLISH"/"NEUTRAL"/"BEAR"），null=不调整 */
    val marketTrend: String? = null,
    /** 摆动高点左回望天数 */
    val swingLeftN: Int = 5,
    /** 摆动高点右确认天数 */
    val swingRightN: Int = 2,
    /** 是否启用三日不新低确认 */
    val requireThreeDayConfirm: Boolean = false,
    // ── v3: 趋势跟随模式（超短/短线在牛市启用，替代均线粘合） ──
    /** 分析模式：CONVERGENCE=均线粘合（中/长线及熊市），TREND_FOLLOW=趋势跟随（超短/短牛市）
     *  var：允许工厂方法（PC 拟合参数）返回后再按牛熊覆盖模式 */
    var mode: AnalysisMode = AnalysisMode.CONVERGENCE,
    // ── v6: 趋势跟随参数（smalltools trend_follow 节，单一事实源 backtest_params.json） ──
    /** 多头排列是否含 MA60（超短 false / 短线 true） */
    val trendUseMA60: Boolean = false,
    // ── v7: 周期标识（增强过滤用，对齐 smalltools/_pool_filters.py 周期差异化） ──
    /** 周期名（"超短线"/"短线"/"中线"/"长线"），null=不启用增强过滤 */
    var period: String? = null,
    /** 贴近新高：收盘距20日高点最大回撤（%） */
    val trendNearHighDrawdownPct: Double = 8.0,
    /** 放量：当日量 / 5日均量阈值 */
    val trendVolumeRatio: Double = 1.2,
    /** 通过所需最少项数（6项中） */
    val trendMinPassCount: Int = 5,
    /** 是否要求当日真实上涨 */
    val trendRequireChangePct: Boolean = true
) {

    companion object {
        private const val TAG = "StockCheckPipeline"

        /** 大盘状态枚举 */
        enum class MarketRegime { BULLISH, NEUTRAL, BEARISH }

        /** 分析模式：均线粘合 vs 趋势跟随 */
        enum class AnalysisMode { CONVERGENCE, TREND_FOLLOW }

        /** 解析大盘状态字符串 */
        fun parseMarketRegime(trend: String?): MarketRegime = when {
            trend == null -> MarketRegime.NEUTRAL
            trend.contains("BULL", ignoreCase = true) -> MarketRegime.BULLISH
            trend.contains("BEAR", ignoreCase = true) -> MarketRegime.BEARISH
            else -> MarketRegime.NEUTRAL
        }

        /** 大盘状态对应的回望期乘数：牛市缩短（近期高点更近），熊市拉长 */
        fun marketLookbackMultiplier(regime: MarketRegime): Double = when (regime) {
            MarketRegime.BULLISH -> 0.7
            MarketRegime.NEUTRAL -> 1.0
            MarketRegime.BEARISH -> 1.5
        }

        /** 周期性行业识别（锂矿、有色金属、煤炭等需要更长回望期） */
        fun isCyclicalIndustry(stockName: String): Boolean {
            val keywords = listOf("锂", "矿", "有色", "煤炭", "钢铁", "石化", "稀土", "铜", "铝", "钴", "镍", "黄金", "资源", "能源")
            return keywords.any { stockName.contains(it) }
        }

        /** ST / *ST 退市风险股：不参与趋势跟随 */
        fun isDelistingRisk(name: String): Boolean =
            name.contains("ST", ignoreCase = true) || name.contains("*ST", ignoreCase = true)

        /**
         * 摆动高点检测（Swing High Detection）
         * 在回望窗口内找到最近的局部最高点：high[i] >= 左侧 leftN 根 且 >= 右侧 rightN 根
         * 返回最近一根 K 线的索引（在 snaps 列表中的绝对索引），未找到返回 -1
         */
        fun findSwingHighIndex(snaps: List<DailySnapshotEntity>, startIdx: Int, leftN: Int, rightN: Int): Int {
            for (i in snaps.size - 1 downTo startIdx) {
                if (i - leftN < startIdx || i + rightN >= snaps.size) continue
                val h = snaps[i].high
                val leftMax = (i - leftN until i).maxOf { snaps[it].high }
                val rightMax = (i + 1..i + rightN).maxOf { snaps[it].high }
                if (h >= leftMax && h >= rightMax) return i
            }
            return -1
        }

        /**
         * 超短线 v3：BULLISH 趋势跟随模式（趋势向上、贴近新高、放量、当日上涨）；
         * 不再死守"均线粘合+三日不新低"（上升趋势中会错误过滤强势股）。
         * 有较大利润时配合做T/反T（见 AutoTradePortfolioEngine）。
         */
        fun ultraShortParams(marketTrend: String? = null): StockCheckPipeline {
            val base = StockCheckPipeline(
                convergenceThreshold = 3.0,
                useMA60 = false,
                convergenceDurationDays = 5,
                volumeBreakoutRatio = 2.5,
                // B13: 震荡市均线粘合不追高，4% 降至 2%（TREND_FOLLOW 模式走 analyzeTrendSnaps，不读此参数）
                minChangePct = 2.0,
                requireChangePct = true,
                minDrawdownPct = 10.0,
                requireCloseAboveConvergenceTop = true,
                requireOpenBelowMAs = true,
                lookbackDays = 30,
                minPassCount = 7,
                requireThreeDayConfirm = true,
                mode = AnalysisMode.TREND_FOLLOW,
                marketTrend = marketTrend
            )
            // 固化参数覆盖（smalltools 三年 walk-forward 拟合）+ 趋势跟随阈值（v6）
            return BacktestParamsLoader.applyTrendFollowOverrides(
                "超短",
                BacktestParamsLoader.applySelectOverrides("超短", base, marketTrend)
            ).also { it.period = "超短线" }
        }

        /**
         * 短线 v3：BULLISH 趋势跟随模式（趋势向上、贴近新高、放量、当日上涨）；
         * 不再死守"均线粘合+三日不新低"（上升趋势中会错误过滤强势股）。
         * 有较大利润时配合做T/反T（见 AutoTradePortfolioEngine）。
         */
        fun shortTermParams(marketTrend: String? = null): StockCheckPipeline {
            val base = StockCheckPipeline(
                convergenceThreshold = 3.0,
                useMA60 = true,
                convergenceDurationDays = 10,
                volumeBreakoutRatio = 1.5,
                minChangePct = 3.0,
                requireChangePct = true,
                minDrawdownPct = 20.0,
                requireAboveAllMAs = true,
                lookbackDays = 60,
                minPassCount = 6,
                requireThreeDayConfirm = true,
                mode = AnalysisMode.TREND_FOLLOW,
                marketTrend = marketTrend
            )
            return BacktestParamsLoader.applyTrendFollowOverrides(
                "短线",
                BacktestParamsLoader.applySelectOverrides("短线", base, marketTrend)
            )
        }

        /**
         * 中线：四线粘合 + MA60 上翘 + 温和放量(MA(V,5)/REF(MA(V,10),1) 1.2~1.8) + 站上所有均线
         * 距高点跌幅 ≥ 30%
         * 适用检查：①②③④⑤⑥⑨ = 7 项（全过，对应设计 AND 语义）
         */
        fun midTermParams(marketTrend: String? = null): StockCheckPipeline {
            val base = StockCheckPipeline(
                convergenceThreshold = 2.5,
                useMA60 = true,
                convergenceDurationDays = 15,
                moderateVolumeLower = 1.2,
                moderateVolumeUpper = 1.8,
                minDrawdownPct = 30.0,
                requireMA60Rising = true,
                requireAboveAllMAs = true,
                lookbackDays = 120,
                minPassCount = 6,
                requireThreeDayConfirm = true,
                marketTrend = marketTrend
            )
            return BacktestParamsLoader.applySelectOverrides("中线", base, marketTrend)
                .also { it.period = "中线" }
        }

        /**
         * 长线：MA60/MA250 同步上翘(10日) + 地量 + 站稳年线
         * 距高点跌幅 ≥ 20%，多头排列含 MA60>MA250
         * 适用检查：①②③④⑤⑥⑦⑩ = 8 项（通过 minPassCount 项）
         *
         * 2026-08-15 一年回溯实证：原参数 minDrawdownPct=40 + minPassCount=7 与
         * 「站上年线+MA250上升」几乎互斥（深跌40%的股票难站年线上方），一年仅 6 个信号且
         * 全部亏损；放宽至跌 20% / 粘合 2.5 / 地量 0.7 / 通过 6 项后，信号 222 个、
         * 平均 +4.20%、胜率 49.5%、盈亏因子 2.41（smalltools/_long_param_exp.py 实证）。
         */
        fun longTermParams(marketTrend: String? = null): StockCheckPipeline {
            val base = StockCheckPipeline(
                convergenceThreshold = 2.5,
                useMA60 = true,
                convergenceDurationDays = 20,
                requireVolumeShrink = true,
                volumeShrinkRatio = 0.7,
                minDrawdownPct = 20.0,
                requireMA60Rising = true,
                maRisingDays = 10,
                requireMA250Rising = true,
                useMA250InBullish = true,
                requireAboveYearLine = true,
                lookbackDays = 250,
                minPassCount = 6,
                requireThreeDayConfirm = true,
                marketTrend = marketTrend
            )
            return BacktestParamsLoader.applySelectOverrides("长线", base, marketTrend)
                .also { it.period = "长线" }
        }
    }

    /**
     * 个股分析结果（均线多头粘合框架）
     */
    data class StockCheckResult(
        val stockCode: String,
        val stockName: String,
        // ── 核心指标 ──
        /** 粘合度（百分比，如 1.5 = 1.5%） */
        val convergenceDegree: Double = 999.0,
        /** 粘合度是否达标 */
        val convergenceOk: Boolean = false,
        /** 多头排列 MA5>MA10>MA20[>MA60] */
        val bullishAligned: Boolean = false,
        /** 粘合持续天数 */
        val convergenceDays: Int = 0,
        /** 粘合持续天数是否达标 */
        val convergenceDurationOk: Boolean = false,
        /** 量能条件是否达标 */
        val volumeConditionOk: Boolean = false,
        /** 量比（当日量 / 5日均量） */
        val volumeRatio: Double = 0.0,
        /** 距高点跌幅（%） */
        val drawdownPct: Double = 0.0,
        /** 跌幅是否达标 */
        val drawdownOk: Boolean = false,
        /** MA60 是否上升（不要求时为 true） */
        val ma60Rising: Boolean = false,
        /** MA250 是否上升（不要求时为 true，长线） */
        val ma250Rising: Boolean = true,
        /** 收盘是否远离粘合区上沿 >2%（不要求时为 true，超短） */
        val closeAboveConvergenceTop: Boolean = true,
        /** 开盘是否低于三线且收盘站上5日线（不要求时为 true，超短） */
        val openBelowMAs: Boolean = true,
        /** 价格是否在年线上方（不要求时为 true） */
        val aboveYearLine: Boolean = false,
        /** 涨幅是否达标（不要求时为 true） */
        val changePctOk: Boolean = true,
        /** 收盘是否站上所有均线 */
        val aboveAllMAs: Boolean = false,
        // ── v2: 摆动高点 + 三日确认 + 动态参数 ──
        /** 摆动高点价格（替代全局最高价） */
        val swingHigh: Double = 0.0,
        /** 三日不新低是否通过 */
        val threeDayNoNewLow: Boolean = true,
        /** 实际使用的回望天数（经大盘/周期行业调整） */
        val effectiveLookback: Int = 0,
        /** 实际使用的粘合阈值（经大盘调整） */
        val effectiveConvergence: Double = 0.0,
        // ── 汇总 ──
        /** 通过项数 */
        val passCount: Int = 0,
        /** 本周期适用检查总数 */
        val totalChecks: Int = 7,
        /** 是否通过（passCount >= minPassCount） */
        val passed: Boolean = false,
        /** 当前价格 */
        val currentPrice: Double = 0.0,
        /** PE 值 */
        val pe: Double = 0.0,
        /** 换手率 */
        val turnoverRate: Double = 0.0,
        // ── v5: IC 排序因子（来自 smalltools/_factor_ic.py 全量 IC 检验，NaN=数据不足不参与排序） ──
        /** 当日涨幅%（IC：短线最优，负相关，值越低越优先） */
        val changePct: Double = 0.0,
        /** 近5日动量% = close/MA5-1（IC：长线最优，负相关，值越低越优先） */
        val momentum5: Double = Double.NaN,
        /** 距 MA60 乖离% = close/MA60-1（IC 排序因子；K线不足60根为 NaN） */
        val ma60Bias: Double = Double.NaN,
        /** 距 MA250 乖离% = close/MA250-1（IC：中线最优，负相关；K线不足250根为 NaN） */
        val ma250Bias: Double = Double.NaN,
        /** 个股方向标签（v6 先判方向再定周期）：UP/DOWN/ACCUMULATION/BREAKOUT/OSCILLATION */
        val direction: String = "OSCILLATION",
        /** 文字摘要 */
        val summary: String = "",
        // ── v4: 产业主线（中线/长线） ──
        /** 命中的产业主题（如 AI算力硬件 / 半导体国产替代），null=未命中 */
        val industryTheme: String? = null,
        /** 产业主线是否通过（中线/长线要求命中已知产业主线） */
        val industryOk: Boolean = true
    )

    suspend fun analyze(context: Context, stockCode: String): StockCheckResult {
        val db = StockDatabase.getInstance(context)
        return analyze(db, stockCode)
    }

    suspend fun analyze(db: StockDatabase, stockCode: String): StockCheckResult {
        return try {
            val snaps = db.dailySnapshotDao().getByCode(stockCode, lookbackDays + 10)
                .sortedBy { it.date }
            if (snaps.size < 20) {
                Log.w(TAG, "数据不足: $stockCode 仅 ${snaps.size} 条K线(<20)，跳过严选")
                return StockCheckResult(stockCode, "数据不足", summary = "K线数据不足(${snaps.size}条)")
            }
            analyzeSnaps(snaps)
        } catch (e: Exception) {
            Log.e(TAG, "个股分析异常: $stockCode - ${e.message}", e)
            StockCheckResult(stockCode, "异常", summary = "分析异常: ${e.message}")
        }
    }

    /**
     * 用预载入的快照分析（不回查 DB，供 PipelineBacktestEngine 回溯用）
     */
    fun analyzeSnaps(
        snaps: List<DailySnapshotEntity>,
        marketMaResult: Any? = null  // 大盘分析结果，用于动态调整参数
    ): StockCheckResult {
        if (snaps.size < 20) {
            return StockCheckResult("", "数据不足", summary = "K线数据不足(${snaps.size}条)")
        }

        val latest = snaps.last()
        val stockCode = latest.code
        val name = latest.name

        // ── v3: 趋势跟随模式（超短/短线牛市） ──
        if (mode == AnalysisMode.TREND_FOLLOW) {
            return analyzeTrendSnaps(snaps, marketMaResult)
        }

        val closes = snaps.map { it.close }

        // ── v2: 大盘感知 + 周期性行业 → 动态调整回望期/粘合阈值 ──
        val regime = parseMarketRegime(marketTrend)
        val cyclical = isCyclicalIndustry(name)
        val cyclicalMultiplier = if (cyclical && lookbackDays >= 60) 1.3 else 1.0
        val effectiveLookback = (lookbackDays * marketLookbackMultiplier(regime) * cyclicalMultiplier)
            .toInt().coerceIn(20, snaps.size)
        // 牛市均线自然发散，阈值按比例放宽 50%；熊市收紧 20%（比例调整比固定值更合理）
        val effectiveConvergence = when (regime) {
            MarketRegime.BULLISH -> convergenceThreshold * 1.5
            MarketRegime.BEARISH -> (convergenceThreshold * 0.8).coerceAtLeast(1.0)
            else -> convergenceThreshold
        }

        // ── MA 计算 ──
        val ma5 = closes.takeLast(5).average()
        val ma10 = if (closes.size >= 10) closes.takeLast(10).average() else ma5
        val ma20 = if (closes.size >= 20) closes.takeLast(20).average() else ma5
        val ma60 = if (closes.size >= 60) closes.takeLast(60).average() else null
        val ma250 = if (closes.size >= 250) closes.takeLast(250).average() else null

        val mas = mutableListOf(ma5, ma10, ma20)
        if (useMA60 && ma60 != null) mas.add(ma60)

        // ═══ 1. 粘合度（使用大盘动态调整后的阈值） ═══
        val maMax = mas.max()
        val maMin = mas.min()
        val convergenceDegree = if (maMin > 0) (maMax - maMin) / maMin * 100 else 999.0
        val convergenceOk = convergenceDegree <= effectiveConvergence

        // ═══ 2. 多头排列 ═══
        // 长线设计：MA5>MA10>MA20>MA60>MA250（MA250 参与多头链）
        val bullishAligned = when {
            useMA250InBullish && ma60 != null && ma250 != null ->
                ma5 > ma10 && ma10 > ma20 && ma20 > ma60 && ma60 > ma250
            useMA60 && ma60 != null ->
                ma5 > ma10 && ma10 > ma20 && ma20 > ma60
            else ->
                ma5 > ma10 && ma10 > ma20
        }

        // ═══ 3. 粘合持续天数 ═══
        // 使用宽松阈值（+0.5%）：允许临界附近微小波动，统计的是"近似粘合"天数
        // 通过比例按设计文档 COUNT(粘合<=X+0.5, N) >= 0.8N（超短 4/5、短 8/10、中 12/15、长 16/20）
        val durationWindow = convergenceDurationDays.coerceAtLeast(5)
        val requiredDays = ceil(durationWindow * convergenceDurationRatio).toInt().coerceAtLeast(1)
        val looseThreshold = effectiveConvergence + 0.5
        var convergenceDays = 0
        // 窗口起点向后挪到能算 MA20 的位置，保证窗口内统计天数尽可能完整
        val windowStart = maxOf(closes.size - durationWindow, 19)
        for (i in windowStart until closes.size) {
            val window = closes.subList(0, i + 1)
            val wMa5 = window.takeLast(5).average()
            val wMa10 = if (window.size >= 10) window.takeLast(10).average() else wMa5
            val wMa20 = if (window.size >= 20) window.takeLast(20).average() else wMa5
            val wMas = mutableListOf(wMa5, wMa10, wMa20)
            if (useMA60 && window.size >= 60) {
                val wMa60 = window.takeLast(60).average()
                wMas.add(wMa60)
            }
            val wMax = wMas.max()
            val wMin = wMas.min()
            val wDeg = if (wMin > 0) (wMax - wMin) / wMin * 100 else 999.0
            if (wDeg <= looseThreshold) convergenceDays++
        }
        val convergenceDurationOk = convergenceDays >= requiredDays

        // ═══ 4. 量能条件（三种模式互斥） ═══
        // 展示用：当日量 / 前5日均量
        val vol5Avg = if (snaps.size >= 6) {
            snaps.takeLast(6).dropLast(1).map { it.volume.toDouble() }.average()
        } else latest.volume.toDouble()
        val volumeRatio = if (vol5Avg > 0) latest.volume / vol5Avg else 1.0

        val volumeConditionOk = when {
            // 地量模式（长线）：MA(V,10) < MA(V,60) × ratio（均含当日，设计文档口径）
            requireVolumeShrink -> {
                val vol10Avg = if (snaps.size >= 10) {
                    snaps.takeLast(10).map { it.volume.toDouble() }.average()
                } else latest.volume.toDouble()
                val vol60Avg = if (snaps.size >= 60) {
                    snaps.takeLast(60).map { it.volume.toDouble() }.average()
                } else vol10Avg
                vol60Avg > 0 && vol10Avg < vol60Avg * volumeShrinkRatio
            }
            // 温和放量模式（中线）：MA(V,5) / REF(MA(V,10),1) 在 [lower, upper]（设计文档均量比口径）
            moderateVolumeLower > 0 && moderateVolumeUpper > 0 -> {
                val maVol5Incl = if (snaps.size >= 5) {
                    snaps.takeLast(5).map { it.volume.toDouble() }.average()
                } else latest.volume.toDouble()
                val maVol10Prev = if (snaps.size >= 11) {
                    snaps.dropLast(1).takeLast(10).map { it.volume.toDouble() }.average()
                } else latest.volume.toDouble()
                val moderateRatio = if (maVol10Prev > 0) maVol5Incl / maVol10Prev else 0.0
                moderateRatio >= moderateVolumeLower && moderateRatio <= moderateVolumeUpper
            }
            // 放量突破模式（超短线/短线）：量比 ≥ ratio
            else -> volumeRatio >= volumeBreakoutRatio
        }

        // ═══ 5. 距高点跌幅（v2: 使用摆动高点检测替代全局最高价） ═══
        val lookbackStart = maxOf(snaps.size - effectiveLookback, 0)
        val swingIdx = findSwingHighIndex(snaps, lookbackStart, swingLeftN, swingRightN)
        val swingHigh = if (swingIdx >= 0) snaps[swingIdx].high
            else snaps.takeLast(effectiveLookback).maxOfOrNull { it.high } ?: latest.high
        val drawdownPct = if (swingHigh > 0) (swingHigh - latest.close) / swingHigh * 100 else 0.0
        val drawdownOk = minDrawdownPct <= 0 || drawdownPct >= minDrawdownPct

        // ═══ 6. MA60 上升（仅在要求时计算，比较天数 = maRisingDays，长线 10 天） ═══
        val ma60Rising = if (requireMA60Rising && ma60 != null && closes.size >= 60 + maRisingDays) {
            val ma60Prev = closes.subList(0, closes.size - maRisingDays).takeLast(60).average()
            ma60 > ma60Prev
        } else false

        // ═══ 10. MA250 上升（仅在要求时计算，长线：MA250>REF(MA250,10)） ═══
        val ma250Rising = if (requireMA250Rising && ma250 != null && closes.size >= 250 + maRisingDays) {
            val ma250Prev = closes.subList(0, closes.size - maRisingDays).takeLast(250).average()
            ma250 > ma250Prev
        } else true

        // ═══ 7. 年线位置（仅在要求时计算） ═══
        val aboveYearLine = if (requireAboveYearLine && ma250 != null) {
            latest.close > ma250
        } else false

        // ═══ 8. 涨幅达标（仅在要求时计算） ═══
        val changePctOk = if (requireChangePct) {
            latest.changePct >= minChangePct
        } else true

        // ═══ 9. 收盘站上所有均线（仅在要求时计算） ═══
        val aboveAllMAs = if (requireAboveAllMAs) {
            if (useMA60 && ma60 != null) {
                latest.close > ma5 && latest.close > ma10 && latest.close > ma20 && latest.close > ma60
            } else {
                latest.close > ma5 && latest.close > ma10 && latest.close > ma20
            }
        } else true

        // ═══ 11. 收盘远离粘合区上沿 >2%（仅在要求时计算，超短线突破强度） ═══
        val closeAboveConvergenceTop = if (requireCloseAboveConvergenceTop) {
            latest.close > maMax * 1.02
        } else true

        // ═══ 12. 开盘低于三线且收盘站上5日线（仅在要求时计算，超短线开盘条件） ═══
        val openBelowMAs = if (requireOpenBelowMAs) {
            latest.open < ma5 && latest.open < ma10 && latest.open < ma20 && latest.close > ma5
        } else true

        // ═══ 13. 三日不新低确认（v2: 近3个交易日最低价不创新低，底部确认信号） ═══
        // v3 适配：大盘向上(BULLISH)时趋势跟随，不强制"三日不新低"（上升趋势中天然不满足，
        // 会错误过滤强势股）；仅在大盘下跌/震荡(BEARISH/NEUTRAL)或未启用时执行。
        val requireThreeDayNow = requireThreeDayConfirm &&
            regime != MarketRegime.BULLISH
        val threeDayNoNewLow = if (snaps.size >= 4) {
            val last3 = snaps.takeLast(3)
            val prevLow = snaps[snaps.size - 4].low
            last3.all { it.low >= prevLow }
        } else true
        val threeDayConfirmOk = if (requireThreeDayNow) threeDayNoNewLow else true

        // ═══ 统计通过项数 — 只计本周期要求的检查 ═══
        var passCount = 0
        var totalChecks = 0

        // ① 粘合度（所有周期）
        totalChecks++; if (convergenceOk) passCount++
        // ② 多头排列（所有周期）
        totalChecks++; if (bullishAligned) passCount++
        // ③ 粘合持续（所有周期）
        totalChecks++; if (convergenceDurationOk) passCount++
        // ④ 量能条件（所有周期）
        totalChecks++; if (volumeConditionOk) passCount++
        // ⑤ 跌幅达标（所有周期，minDrawdownPct=0 时自动通过）
        totalChecks++; if (drawdownOk) passCount++
        // ⑥ MA60 上升（仅要求时计入）
        if (requireMA60Rising) { totalChecks++; if (ma60Rising) passCount++ }
        // ⑦ 站稳年线（仅要求时计入）
        if (requireAboveYearLine) { totalChecks++; if (aboveYearLine) passCount++ }
        // ⑧ 涨幅达标（仅要求时计入）
        if (requireChangePct) { totalChecks++; if (changePctOk) passCount++ }
        // ⑨ 站上所有均线（仅要求时计入）
        if (requireAboveAllMAs) { totalChecks++; if (aboveAllMAs) passCount++ }
        // ⑩ MA250 上升（仅要求时计入）
        if (requireMA250Rising) { totalChecks++; if (ma250Rising) passCount++ }
        // ⑪ 收盘远离粘合区上沿（仅要求时计入）
        if (requireCloseAboveConvergenceTop) { totalChecks++; if (closeAboveConvergenceTop) passCount++ }
        // ⑫ 开盘低于三线且收盘站上5日线（仅要求时计入）
        if (requireOpenBelowMAs) { totalChecks++; if (openBelowMAs) passCount++ }
        // ⑬ 三日不新低确认（仅要求时计入，大盘向上时不强制）
        if (requireThreeDayNow) { totalChecks++; if (threeDayConfirmOk) passCount++ }

        var passed = passCount >= minPassCount
        // ── v7: 增强过滤（smalltools/_pool_filters.py extra_filter 搬回，周期差异化，长线豁免） ──
        val p = period
        if (p != null && EnhancedPoolFilter.enabled) {
            // ③ 粘合持续硬性：中线（STICKY_HARD，消灭"瞬间收敛"假粘合）
            if (p in EnhancedPoolFilter.stickyHardPeriods() && !convergenceDurationOk) passed = false
            // ② 多头排列 + ⑬ 三日不新低 硬性：超短/短线（SHORT_HARD；牛市趋势跟随时不强制三日不新低）
            if (p in EnhancedPoolFilter.shortHardPeriods() &&
                (!bullishAligned || !threeDayConfirmOk)) passed = false
            // minPassCount 周期门槛覆盖（PASS_COUNT，如 短线 7 / 中线 7）
            EnhancedPoolFilter.passCountOverride(p)?.let { if (passCount < it) passed = false }
        }

        // ── v5: IC 排序因子（口径与 smalltools/_factor_ic.py 一致：close/均线-1 再*100） ──
        val changePct = latest.changePct
        val momentum5 = if (closes.size >= 5 && ma5 > 0) (latest.close / ma5 - 1) * 100 else Double.NaN
        val ma60Bias = if (ma60 != null && ma60 > 0) (latest.close / ma60 - 1) * 100 else Double.NaN
        val ma250Bias = if (ma250 != null && ma250 > 0) (latest.close / ma250 - 1) * 100 else Double.NaN

        // ── v6: 个股方向标签（先判方向再定周期） ──
        val direction = DirectionAnalyzer.analyze(
            close = latest.close,
            ma5 = ma5, ma10 = ma10, ma20 = ma20,
            ma60Rising = ma60Rising,
            convergenceDegree = convergenceDegree,
            convergenceDays = convergenceDays,
            closeAboveConvergenceTop = closeAboveConvergenceTop,
            volumeRatio = volumeRatio,
            aboveAllMAs = aboveAllMAs
        ).name

        return StockCheckResult(
            stockCode = stockCode,
            stockName = name,
            convergenceDegree = convergenceDegree,
            convergenceOk = convergenceOk,
            bullishAligned = bullishAligned,
            convergenceDays = convergenceDays,
            convergenceDurationOk = convergenceDurationOk,
            volumeConditionOk = volumeConditionOk,
            volumeRatio = volumeRatio,
            drawdownPct = drawdownPct,
            drawdownOk = drawdownOk,
            ma60Rising = ma60Rising,
            ma250Rising = ma250Rising,
            closeAboveConvergenceTop = closeAboveConvergenceTop,
            openBelowMAs = openBelowMAs,
            aboveYearLine = aboveYearLine,
            changePctOk = changePctOk,
            aboveAllMAs = aboveAllMAs,
            swingHigh = swingHigh,
            threeDayNoNewLow = threeDayNoNewLow,
            effectiveLookback = effectiveLookback,
            effectiveConvergence = effectiveConvergence,
            passCount = passCount,
            totalChecks = totalChecks,
            passed = passed,
            currentPrice = latest.close,
            pe = latest.pe,
            turnoverRate = latest.turnoverRate,
            changePct = changePct,
            momentum5 = momentum5,
            ma60Bias = ma60Bias,
            ma250Bias = ma250Bias,
            direction = direction,
            summary = "$name(${stockCode.takeLast(4)}) 粘合${"%.1f".format(convergenceDegree)}% 通过:$passCount/$totalChecks"
        )
    }

    /**
     * ## v3 趋势跟随分析（超短/短线牛市模式）
     *
     * 大盘向上(BULLISH)时，超短/短线不再用"均线多头粘合"（上升趋势中均线发散、粘合持续/三日不新低
     * 天然不满足，会错误过滤强势股）。改为**趋势跟随**：跟随大盘向上趋势，捕捉强势突破股。
     *
     * 检查项（6 项，通过 ≥5 且当日上涨）：
     * 1. 多头排列   MA5>MA10>MA20[>MA60]（趋势向上核心）
     * 2. 贴近新高   收盘距20日高点回撤 < 8%（强势不追高）
     * 3. 放量       当日量/5日均量 ≥ threshold（超短1.2x/短1.0x）
     * 4. 当日上涨   真实涨幅 > 0（突破强度）
     * 5. MA20上行   MA20 > 前日MA20（趋势持续）
     * 6. 站上5日线  收盘 > MA5（强势确认）
     *
     * 风险过滤：剔除 ST、*ST 退市风险股。
     */
    fun analyzeTrendSnaps(
        snaps: List<DailySnapshotEntity>,
        marketMaResult: Any? = null
    ): StockCheckResult {
        val latest = snaps.last()
        val stockCode = latest.code
        val name = latest.name

        // 剔除 ST 退市风险股
        if (isDelistingRisk(name)) {
            return StockCheckResult(stockCode, name, passed = false, passCount = 0,
                summary = "$name 为ST退市风险股，不参与趋势跟随")
        }

        val closes = snaps.map { it.close }
        val vols = snaps.map { it.volume.toDouble() }

        val ma5 = closes.takeLast(5).average()
        val ma10 = if (closes.size >= 10) closes.takeLast(10).average() else ma5
        val ma20 = if (closes.size >= 20) closes.takeLast(20).average() else ma5
        val ma60 = if (closes.size >= 60) closes.takeLast(60).average() else null

        val results = mutableMapOf<String, Pair<Boolean, String>>()

        // ① 多头排列（趋势向上核心）：trendUseMA60=true 时 MA20>MA60 参与（短线），false 仅三均线（超短）
        val bullishAligned = if (trendUseMA60) {
            if (ma60 != null) ma5 > ma10 && ma10 > ma20 && ma20 > ma60
            else ma5 > ma10 && ma10 > ma20
        } else {
            ma5 > ma10 && ma10 > ma20
        }
        results["多头排列"] = bullishAligned to (if (bullishAligned) "多头" else "未多头")

        // ② 贴近新高（突破动量）：回撤 < trendNearHighDrawdownPct
        val hi20 = snaps.takeLast(20).maxOfOrNull { it.high } ?: latest.high
        val drawdownPct = if (hi20 > 0) (hi20 - latest.close) / hi20 * 100 else 999.0
        val nearHigh = drawdownPct < trendNearHighDrawdownPct
        results["贴近新高"] = nearHigh to "回撤${"%.1f".format(drawdownPct)}%"

        // ③ 放量：当日量/5日均量 ≥ trendVolumeRatio（超短 1.2 / 短线 1.0，smalltools 拟合口径）
        val vol5 = if (vols.size >= 6) vols.takeLast(6).dropLast(1).average() else vols.last()
        val volumeRatio = if (vol5 > 0) latest.volume.toDouble() / vol5 else 1.0
        val volumeOk = volumeRatio >= trendVolumeRatio
        results["放量"] = volumeOk to "量比${"%.2f".format(volumeRatio)}"

        // ④ 当日上涨（真实涨幅）
        val changePct = latest.changePct
        val changeOk = changePct > 0
        results["上涨"] = changeOk to "涨${"%.2f".format(changePct)}%"

        // ⑤ MA20 上行（趋势持续）
        val ma20Up = closes.size > 20 && ma20 > closes.dropLast(1).takeLast(20).average()
        results["MA20上行"] = ma20Up to (if (ma20Up) "上行" else "走平/下")

        // ⑥ 站上5日线
        val aboveMa5 = latest.close > ma5
        results["站上5日线"] = aboveMa5 to (if (aboveMa5) "站上" else "跌破")

        val activeOrder = listOf("多头排列", "贴近新高", "放量", "上涨", "MA20上行", "站上5日线")
        var passCount = activeOrder.count { results[it]?.first == true }
        val totalChecks = activeOrder.size
        // 通过标准：≥trendMinPassCount/6 且（如配置）当日须上涨（剔除下跌股）
        val passed = passCount >= trendMinPassCount && (!trendRequireChangePct || changeOk)

        // ── v5: IC 排序因子（口径与 smalltools/_factor_ic.py 一致） ──
        val momentum5 = if (closes.size >= 5 && ma5 > 0) (latest.close / ma5 - 1) * 100 else Double.NaN
        val ma60Bias = if (ma60 != null && ma60 > 0) (latest.close / ma60 - 1) * 100 else Double.NaN
        val ma250Bias = if (closes.size >= 250) {
            val ma250 = closes.takeLast(250).average()
            if (ma250 > 0) (latest.close / ma250 - 1) * 100 else Double.NaN
        } else Double.NaN

        // ── v6: 个股方向标签（趋势跟随模式） ──
        val ma60RisingNow = if (closes.size > 60 && ma60 != null) ma60 > closes.dropLast(1).takeLast(60).average() else false
        val direction = DirectionAnalyzer.analyze(
            close = latest.close,
            ma5 = ma5, ma10 = ma10, ma20 = ma20,
            ma60Rising = ma60RisingNow,
            convergenceDegree = 0.0,
            convergenceDays = 0,
            closeAboveConvergenceTop = false,
            volumeRatio = volumeRatio,
            aboveAllMAs = aboveMa5
        ).name

        return StockCheckResult(
            stockCode = stockCode,
            stockName = name,
            convergenceDegree = 0.0,
            convergenceOk = false,
            bullishAligned = bullishAligned,
            convergenceDays = 0,
            convergenceDurationOk = false,
            volumeConditionOk = volumeOk,
            volumeRatio = volumeRatio,
            drawdownPct = drawdownPct,
            drawdownOk = nearHigh,
            aboveAllMAs = aboveMa5,
            changePctOk = changeOk,
            passCount = passCount,
            totalChecks = totalChecks,
            passed = passed,
            currentPrice = latest.close,
            pe = latest.pe,
            turnoverRate = latest.turnoverRate,
            changePct = changePct,
            momentum5 = momentum5,
            ma60Bias = ma60Bias,
            ma250Bias = ma250Bias,
            direction = direction,
            summary = "$name(${stockCode.takeLast(4)}) 趋势跟随 $passCount/$totalChecks " +
                (if (passed) "✅突破" else "观望")
        )
    }

    suspend fun analyzeBatch(context: Context, stockCodes: List<String>): List<StockCheckResult> {
        return stockCodes.map { analyze(context, it) }
    }

    fun formatResult(result: StockCheckResult): String {
        return buildString {
            appendLine("═══ ${result.stockName}(${result.stockCode}) ═══")
            appendLine("价格: ${result.currentPrice}  PE: ${result.pe}  换手率: ${result.turnoverRate}%")
            appendLine("量比: ${"%.2f".format(result.volumeRatio)}")
            if (result.effectiveLookback > 0 && result.effectiveLookback != lookbackDays) {
                appendLine("动态回望: ${result.effectiveLookback}天(基准${lookbackDays}天)")
            }
            appendLine()
            var idx = 0
            val effConv = if (result.effectiveConvergence > 0) result.effectiveConvergence else convergenceThreshold
            appendLine("${++idx}. 粘合度≤${"%.1f".format(effConv)}%: ${if (result.convergenceOk) "✅" else "❌"} (${"%.2f".format(result.convergenceDegree)}%)")
            appendLine("${++idx}. 多头排列:       ${if (result.bullishAligned) "✅" else "❌"}")
            appendLine("${++idx}. 粘合≥${convergenceDurationDays}天:    ${if (result.convergenceDurationOk) "✅" else "❌"} (${result.convergenceDays}天)")
            val volDesc = when {
                requireVolumeShrink -> "地量"
                moderateVolumeLower > 0 -> "温和${moderateVolumeLower}-${moderateVolumeUpper}x"
                else -> "放量≥${volumeBreakoutRatio}x"
            }
            appendLine("${++idx}. $volDesc: ${if (result.volumeConditionOk) "✅" else "❌"} (量比${"%.1f".format(result.volumeRatio)})")
            if (minDrawdownPct > 0) {
                val highLabel = if (result.swingHigh > 0) "摆动高点${"%.2f".format(result.swingHigh)}" else "高点"
                appendLine("${++idx}. 跌幅≥${"%.0f".format(minDrawdownPct)}%:  ${if (result.drawdownOk) "✅" else "❌"} (${"%.1f".format(result.drawdownPct)}%, $highLabel)")
            }
            if (requireMA60Rising) {
                appendLine("${++idx}. MA60上升:       ${if (result.ma60Rising) "✅" else "❌"}")
            }
            if (requireAboveYearLine) {
                appendLine("${++idx}. 站稳年线:       ${if (result.aboveYearLine) "✅" else "❌"}")
            }
            if (requireChangePct) {
                appendLine("${++idx}. 涨幅≥${"%.1f".format(minChangePct)}%:   ${if (result.changePctOk) "✅" else "❌"}")
            }
            if (requireAboveAllMAs) {
                appendLine("${++idx}. 站上所有均线:   ${if (result.aboveAllMAs) "✅" else "❌"}")
            }
            if (requireMA250Rising) {
                appendLine("${++idx}. MA250上升:       ${if (result.ma250Rising) "✅" else "❌"}")
            }
            if (requireCloseAboveConvergenceTop) {
                appendLine("${++idx}. 收盘远离粘合区上沿>2%: ${if (result.closeAboveConvergenceTop) "✅" else "❌"}")
            }
            if (requireOpenBelowMAs) {
                appendLine("${++idx}. 开盘低于三线+收盘站上5日线: ${if (result.openBelowMAs) "✅" else "❌"}")
            }
            if (requireThreeDayConfirm) {
                appendLine("${++idx}. 三日不新低:     ${if (result.threeDayNoNewLow) "✅" else "❌"}")
            }
            // 产业主线（中线/长线）
            if (result.industryTheme != null) {
                appendLine("${++idx}. 产业主线:       ${if (result.industryOk) "✅ ${result.industryTheme}" else "❌ 非主线产业"}")
            }
            appendLine()
            appendLine("通过: ${result.passCount}/${result.totalChecks} ${if (result.passed) "→ ✅ 符合买入条件" else "→ ⚠ 未达标准"}")
        }
    }

}
