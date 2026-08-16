package com.chin.stockanalysis.strategy.trade

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.backtest.BacktestParamsLoader
import com.chin.stockanalysis.strategy.data.CandidatePool
import com.chin.stockanalysis.strategy.data.DirectionAnalyzer
import com.chin.stockanalysis.strategy.data.IndividualDirection
import com.chin.stockanalysis.strategy.data.IndustrySeasonalityCalendar
import com.chin.stockanalysis.strategy.topology.pipelines.StockCheckPipeline
import com.chin.stockanalysis.strategy.topology.pipelines.StockCheckPipeline.Companion.AnalysisMode
import com.chin.stockanalysis.strategy.trade.macro.IndexDeviationMonitor
import com.chin.stockanalysis.strategy.trade.macro.MacroEnvironmentAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * ## 统一四周期分类引擎（选股 | pipeline | 买卖评估 | 持仓 | 报告）
 *
 * 将「全部候选股」扫描后**分类**到 超短/短/中/长 四个周期。
 * 同一只股票可同时进入多个周期（如趋势强势既适合超短也适合短线）。
 *
 * ### 候选来源（全量扫描）
 * 1. **龙头**      — LeaderStockPool 核心产业龙头（CandidatePool core）
 * 2. **备选池**    — CandidatePool AI 动态板块龙头
 * 3. **AI 精选**   — ai_selected_stock 表（近 5 天）
 * 4. **自选**      — user_watchlist 表（用户自选）
 *
 * ### 周期分类
 * | 周期    | 分类策略                        | 逻辑                                     |
 * |---------|--------------------------------|------------------------------------------|
 * | 超短    | TREND_FOLLOW（牛市）/ CONVERGENCE | 趋势跟随：≥5/6 + 当日上涨；否则粘合    |
 * | 短      | TREND_FOLLOW（牛市）/ CONVERGENCE | 同上，参数略宽松                        |
 * | 中      | CONVERGENCE（均线粘合）         | 粘合+量能+MA60 上翘                    |
 * | 长      | CONVERGENCE（均线粘合）         | 粘合+年线+基本面                        |
 */
class UnifiedStockClassifier(private val context: Context) {

    companion object {
        private const val TAG = "UnifiedStockClassifier"
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        /** 周期标识（与 orderTypePeriod 归一化一致） */
        const val PERIOD_ULTRA_SHORT = "ultra_short"
        const val PERIOD_SHORT = "short"
        const val PERIOD_MID = "mid"
        const val PERIOD_LONG = "long"
        val ALL_PERIODS = listOf(PERIOD_ULTRA_SHORT, PERIOD_SHORT, PERIOD_MID, PERIOD_LONG)

        fun periodTitle(p: String): String = when (p) {
            PERIOD_ULTRA_SHORT -> "超短线"
            PERIOD_SHORT -> "短线"
            PERIOD_MID -> "中线"
            PERIOD_LONG -> "长线"
            else -> p
        }

        /**
         * v5 IC 加权排序：对同周期命中股按 rank_factors 权重打分，得分高者优先买入。
         * 权重来自 smalltools/_factor_ic.py 全量 IC 检验（ICIR 带符号）：
         *   负权重 = 因子值越低越优先（如中线距MA250乖离 -0.57、长线近5日动量 -0.69）。
         * 算法：逐因子算百分位秩（平均秩法 0..1）→ score = Σ weight×rank → 降序。
         * 无权重配置 / 候选 ≤1 时回退原 passCount 排序，保证向后兼容。
         */
        fun icRank(
            stocks: List<ClassifiedStock>,
            weights: Map<String, Double>,
            extraColumns: Map<String, List<Double>> = emptyMap()
        ): List<ClassifiedStock> {
            if (stocks.size <= 1 || weights.isEmpty()) {
                return stocks.sortedByDescending { it.result.passCount }
            }
            val factors = weights.keys.toList()
            val pctRanks = factors.map { f ->
                // direction / seasonality 为伪因子（由调用方注入），其余读 StockCheckResult
                icPctRank(extraColumns[f] ?: stocks.map { factorValueOf(it.result, f) })
            }
            return stocks.indices
                .map { i -> i to factors.indices.sumOf { j -> weights[factors[j]]!! * pctRanks[j][i] } }
                .sortedWith(compareByDescending<Pair<Int, Double>> { it.second }
                    .thenByDescending { stocks[it.first].result.passCount })
                .map { stocks[it.first] }
        }

        /** 方向 → 分值（越高越优，用于 IC 排序伪因子 direction） */
        private fun directionScore(dir: String): Double = try {
            when (IndividualDirection.valueOf(dir)) {
                IndividualDirection.BREAKOUT -> 4.0
                IndividualDirection.UPTREND -> 3.0
                IndividualDirection.ACCUMULATION -> 2.0
                IndividualDirection.OSCILLATION -> 0.0
                IndividualDirection.DOWNTREND -> -2.0
            }
        } catch (_: Exception) { 0.0 }

        /** 百分位秩（平均秩法）：并列取平均，结果 0..1；NaN 视为缺失给 0.5 中性值（不偏不倚） */
        private fun icPctRank(values: List<Double>): List<Double> {
            val n = values.size
            val ranks = MutableList(n) { 0.5 }
            val present = values.mapIndexedNotNull { i, v -> if (v.isNaN()) null else i to v }
            val m = present.size
            if (m < 2) return ranks
            val idxOf = present.map { it.first }
            val order = present.indices.sortedBy { present[it].second }
            var i = 0
            while (i < m) {
                var j = i
                while (j + 1 < m && present[order[j + 1]].second == present[order[i]].second) j++
                val avgRank = (i + j) / 2.0 / (m - 1)
                for (k in i..j) ranks[idxOf[order[k]]] = avgRank
                i = j + 1
            }
            return ranks
        }

        /** 因子取值：命名与 smalltools/_factor_ic.py 的 FACTORS 对齐；未知因子 → NaN（不参与排序） */
        private fun factorValueOf(r: StockCheckPipeline.StockCheckResult, f: String): Double = when (f) {
            "convergenceDegree" -> r.convergenceDegree
            "volumeRatio" -> r.volumeRatio
            "drawdownPct" -> r.drawdownPct
            "convergenceDays" -> r.convergenceDays.toDouble()
            "changePct", "changePctReal" -> r.changePct
            "turnoverRate", "turnover" -> r.turnoverRate
            "momentum5" -> r.momentum5
            "ma60Bias" -> r.ma60Bias
            "ma250Bias" -> r.ma250Bias
            else -> Double.NaN
        }
    }

    /** 候选股（含来源标注） */
    data class CandidateStock(
        val code: String,
        val name: String,
        val source: String,   // 龙头 / 备选池 / AI精选 / 自选
        val price: Double = 0.0
    )

    /** 单只股票的分类结果（同股可进多周期） */
    data class ClassifiedStock(
        val code: String,
        val name: String,
        val price: Double,
        val period: String,               // 所属周期
        val result: StockCheckPipeline.StockCheckResult,
        val isTrend: Boolean              // 是否趋势跟随判定
    )

    /** 一次扫描结果（全量或单周期） */
    data class ScanResult(
        val candidates: List<CandidateStock>,
        val classified: List<ClassifiedStock>,
        val byPeriod: Map<String, List<ClassifiedStock>>,
        val sourceCount: Map<String, Int>,
        val elapsedMs: Long,
        /** 本次扫描所用K线的数据截止日期（yyyy-MM-dd），无数据为 null */
        val dataDate: String? = null,
        /** 本次实际扫描的周期集合（单周期扫描时仅含一个周期） */
        val scannedPeriods: Set<String> = ALL_PERIODS.toSet(),
        /** 各指数是否处于大盘转弱（离场保护），用于诊断展示 */
        val riskOffIndices: Map<String, Boolean> = emptyMap()
    )

    // ════════════════════════════════════════════
    // 1. 候选聚合
    // ════════════════════════════════════════════

    /**
     * 聚合全部候选来源（龙头 + 备选池 + AI精选 + 自选），去重。
     */
    suspend fun collectCandidates(): List<CandidateStock> = withContext(Dispatchers.IO) {
        val db = StockDatabase.getInstance(context)
        val today = LocalDate.now().format(DATE_FMT)
        val seen = LinkedHashMap<String, CandidateStock>()

        fun add(codes: Collection<String>, nameMap: Map<String, String>, source: String, preferSource: Boolean = false) {
            for (code in codes) {
                val existing = seen[code]
                // 龙头/备选池优先保留，其次 AI 精选，再次自选
                if (existing == null) {
                    seen[code] = CandidateStock(code, nameMap[code] ?: code, source)
                } else if (preferSource && source == "龙头") {
                    seen[code] = CandidateStock(code, nameMap[code] ?: existing.name, source)
                }
            }
        }

        // 名称映射（stock_basics + 快照）
        val nameMap = mutableMapOf<String, String>()
        try {
            db.stockBasicDao().getAll().forEach { nameMap[it.code] = it.name }
        } catch (_: Exception) {}

        // 1. 龙头 + 备选池（CandidatePool）
        try {
            val pool = CandidatePool.getPool(context)
            val poolCodes = pool.stocks.map { it.code }.toSet()
            val coreCodes = pool.stocks.filter { it.source == "core" }.map { it.code }.toSet()
            // 龙头（core）优先标记
            add(coreCodes, nameMap, "龙头", preferSource = true)
            add(poolCodes, nameMap, "备选池")
        } catch (e: Exception) {
            Log.w(TAG, "候选池获取失败: ${e.message}")
        }

        // 2. AI 精选（近 5 天）
        try {
            val minDate = LocalDate.now().minusDays(5).format(DATE_FMT)
            val ai = db.aiSelectedStockDao().getRecentDays(minDate)
            ai.map { it.stockCode to (it.stockName.ifBlank { nameMap[it.stockCode] ?: it.stockCode }) }
                .forEach { (code, name) -> if (seen[code] == null) seen[code] = CandidateStock(code, name, "AI精选") }
        } catch (e: Exception) {
            Log.w(TAG, "AI 精选获取失败: ${e.message}")
        }

        // 3. 自选（user_watchlist，排除 DAG 策略自动写入的来源）
        try {
            val excludeSources = setOf("ultra_short", "shortterm", "midterm", "long_term", "short", "mid", "long")
            val watchlist = db.userWatchlistDao().getAll()
                .filter { it.source !in excludeSources }
            watchlist.map { it.stockCode to (it.stockName.ifBlank { nameMap[it.stockCode] ?: it.stockCode }) }
                .forEach { (code, name) -> if (seen[code] == null) seen[code] = CandidateStock(code, name, "自选") }
        } catch (e: Exception) {
            Log.w(TAG, "自选获取失败: ${e.message}")
        }

        seen.values.toList().also { Log.i(TAG, "候选聚合: ${it.size} 只（龙头=${it.count { s -> s.source == "龙头" }}, 备选池=${it.count { s -> s.source == "备选池" }}, AI精选=${it.count { s -> s.source == "AI精选" }}, 自选=${it.count { s -> s.source == "自选" }}）") }
    }

    // ════════════════════════════════════════════
    // 2. 四周期分类
    // ════════════════════════════════════════════

    /**
     * 对全部候选股执行四周期分类。同一股票可同时命中多周期。
     *
     * @param candidates 候选股列表（默认自动聚合全部来源）
     * @param marketRegime 大盘状态（BULLISH/NEUTRAL/BEAR），用于决定超短/短线用趋势跟随还是粘合
     * @return 分类结果
     */
    suspend fun classifyAll(
        candidates: List<CandidateStock>? = null,
        marketRegime: String? = null,
        periods: Set<String>? = null
    ): ScanResult = coroutineScope {
        val start = System.currentTimeMillis()
        val cands = candidates ?: collectCandidates()
        val db = StockDatabase.getInstance(context)
        // 本次需要扫描的周期集合（null = 全量四周期；单周期扫描传入 setOf(周期)）
        val scanPeriods = periods ?: ALL_PERIODS.toSet()

        // 大盘状态（默认自动探测）
        val regime = marketRegime ?: detectMarketRegime(db)
        val bullish = regime == "BULLISH"

        // 每周期 pipeline 配置（仅保留本次需要扫描的周期）
        val pipelines = buildPeriodPipelines(bullish).filter { it.first in scanPeriods }

        // 预取各指数风险状态（大盘转弱离场保护）：每只股票按板块归属指数判断。
        // 趋势跟随（超短/短）是"跟随大盘向上动量"的策略，所属指数一旦转弱(RISK_OFF)即失效，
        // 应立即停止选入并让持仓离场；而中线/长线看产业逻辑 + 低位粘合埋伏，可继续保留。
        val indexRiskOff = mutableMapOf<String, Boolean>()
        try {
            for (idx in MarketTrendGuard.ALL_INDICES) {
                indexRiskOff[idx] = MarketTrendGuard.isRiskOff(context, idx)
            }
            // 若持久化状态为空（首次），触发一次全量检测
            if (indexRiskOff.values.none { it }) {
                val states = MarketTrendGuard.refreshAll(context)
                states.forEach { (idx, s) -> indexRiskOff[idx] = s.riskOff }
            }
        } catch (e: Exception) {
            Log.w(TAG, "大盘转弱状态读取失败: ${e.message}")
        }

        // ── 宏观环境动态分析：板块偏好矩阵（顺风重仓 / 逆风规避）──
        // 聚合 大盘转弱 + 指数偏离回调 + 事件场景(战争/加息/油价→化工) + 全球市场(美股/韩股)
        // 产出的 sectorBias（产业主题→净偏好分）用于动态调整中/长线产业过滤：
        //   逆风产业（偏好分 ≤ -15）不进中/长线；顺风产业维持正常。
        val macroBias = mutableMapOf<String, Int>()
        try {
            val cached = MacroEnvironmentAnalyzer.cached(context)
            val snap = cached ?: MacroEnvironmentAnalyzer.analyze(context)
            snap.sectorBias.forEach { (theme, score) -> macroBias[theme] = score }
            Log.i(TAG, "宏观板块偏好: ${snap.sectorBias.entries.sortedByDescending { it.value }.take(5).joinToString { "${it.key}=${it.value}" }} | 风险${snap.overallRisk}(${snap.riskLevel.label})")
        } catch (e: Exception) {
            Log.w(TAG, "宏观板块偏好读取失败: ${e.message}")
        }

        // ── 板块指数偏离回调 → 趋势跟随风控（超短/短暂停追高）──
        // 科创50/创业板相对上证偏离到 HIGH/EXTREME，即使大盘未转弱，该板块高位趋势股也面临均值回归补跌。
        val riskControlTrend = mutableMapOf<String, Boolean>()
        try {
            val deviations = IndexDeviationMonitor.monitor(context)
            for ((idx, d) in deviations) {
                riskControlTrend[idx] =
                    d.risk == IndexDeviationMonitor.PullbackRisk.HIGH ||
                    d.risk == IndexDeviationMonitor.PullbackRisk.EXTREME
            }
        } catch (e: Exception) {
            Log.w(TAG, "指数偏离监测失败: ${e.message}")
        }

        // 并发分类所有候选股 × 所有周期
        val jobs = cands.map { c ->
            async(Dispatchers.IO) {
                val stockIdx = MarketTrendGuard.indexForStock(c.code)
                val riskOff = indexRiskOff[stockIdx] ?: false
                val hit = mutableMapOf<String, ClassifiedStock>()
                for ((period, pipe) in pipelines) {
                    // 大盘转弱：趋势跟随周期（超短/短）禁止选入（离场保护）
                    if (riskOff && pipe.mode == AnalysisMode.TREND_FOLLOW) continue
                    // 板块指数偏离回调：板块涨幅远超大盘(均值回归风险) → 趋势跟随暂停追高（超短/短）
                    if (pipe.mode == AnalysisMode.TREND_FOLLOW &&
                        riskControlTrend[stockIdx] == true) continue
                    try {
                        var result = pipe.analyze(db, c.code)
                        if (result.passed) {
                            // ── 产业主线过滤（中线/长线）：只选属于受关注产业主线的股票 ──
                            // 中线/长线看产业逻辑（AI算力/存储/PCB/稀有金属/稀有气体/石油黄金/半导体国产替代），
                            // 而非超短/短线的高弹性动量。未命中已知产业主线 → 不进中线/长线。
                            if (period == PERIOD_MID || period == PERIOD_LONG) {
                                val ind = IndustryThemeClassifier.classify(context, c.code, c.name)
                                result = result.copy(
                                    industryTheme = ind.theme,
                                    industryOk = ind.known
                                )
                                if (!ind.known) continue  // 无产业逻辑，剔除
                                // ── 宏观逆风产业过滤：中/长线若落于当前宏观逆风主题(偏好≤-15)则剔除 ──
                                // 例如加息压制成长(存储/PCB估值)、战争升级抽血科技 → 规避这些产业的中/长线埋伏。
                                val indTheme = ind.theme
                                if (indTheme != null && (macroBias[indTheme] ?: 0) <= -15) continue
                                // ── v6 先判方向再定周期：中线/长线只买 蓄势/上升/突破，剔除 下降/震荡 ──
                                val dir = try { IndividualDirection.valueOf(result.direction) }
                                    catch (_: Exception) { null }
                                if (dir != null && !DirectionAnalyzer.allowedForHolding(dir)) continue
                            }
                            hit[period] = ClassifiedStock(
                                code = c.code, name = result.stockName.ifBlank { c.name },
                                price = result.currentPrice.ifTake(c.price),
                                period = period, result = result,
                                isTrend = pipe.mode == AnalysisMode.TREND_FOLLOW
                            )
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "分类异常 ${c.code} [$period]: ${e.message}")
                    }
                }
                // ── 逻辑互斥：超短/短线（趋势跟随/弹性选股）不应归入中线/长线（低位粘合埋伏） ──
                // 超短/短线追求放量突破、贴近新高（回撤<8%）、趋势向上；
                // 而中/长线要求地量、均线粘合、回撤≥30%/40%、站稳年线。两者本质互斥：
                // 一只弹性大、贴近新高的强势股不可能同时是"低位地量埋伏"标的。
                // 因此一旦命中超短或短线，强制剔除同股的中线/长线，避免前后矛盾。
                val hitShort = hit.containsKey(PERIOD_ULTRA_SHORT) || hit.containsKey(PERIOD_SHORT)
                if (hitShort) {
                    hit.remove(PERIOD_MID)
                    hit.remove(PERIOD_LONG)
                }
                c to hit.values.toList()
            }
        }

        val results = jobs.awaitAll()
        val classified = results.flatMap { it.second }
        // v6: 行业季节日历 → 每只命中股的季节加成（命中当期主题/商品锚定板块）
        val month = LocalDate.now().monthValue
        val seasonEnabled = BacktestParamsLoader.seasonalityEnabled(context)
        val seasonAnchors = BacktestParamsLoader.seasonalityAnchors(context)
        val seasonBonus = if (seasonEnabled) classified.associate { s ->
            s.code to IndustrySeasonalityCalendar.matchStock(s.name, month, seasonAnchors).sumOf { it.weight } * 10
        } else emptyMap()
        // 未扫描的周期保持空列表，UI 据此区分「未扫描」与「无命中」
        // v5+v6: 命中股用 IC 加权排序（rank_factors 来自 backtest_params.json，未配置时回退 passCount）。
        // 额外注入 direction/seasonality 两个伪因子（同为百分位秩，权重在 JSON 里可调）
        val byPeriod = ALL_PERIODS.associateWith { p ->
            if (p in scanPeriods) {
                val inPeriod = classified.filter { it.period == p }
                val extra = mapOf(
                    "seasonality" to inPeriod.map { seasonBonus[it.code] ?: 0.0 },
                    "direction" to inPeriod.map { directionScore(it.result.direction) }
                )
                icRank(inPeriod, BacktestParamsLoader.rankFactors(context, p), extra)
            } else emptyList()
        }
        val sourceCount = cands.groupingBy { it.source }.eachCount()

        // 本次扫描所用K线的数据截止日期（供 UI 提示数据新鲜度）
        val dataDate = try {
            db.dailySnapshotDao().getAvailableDates(1).firstOrNull()
        } catch (_: Exception) { null }
        val riskOffCount = cands.count { indexRiskOff[MarketTrendGuard.indexForStock(it.code)] == true }

        val scan = ScanResult(
            candidates = cands,
            classified = classified,
            byPeriod = byPeriod,
            sourceCount = sourceCount,
            elapsedMs = System.currentTimeMillis() - start,
            dataDate = dataDate,
            scannedPeriods = scanPeriods,
            riskOffIndices = indexRiskOff
        )
        val scanName = if (scanPeriods.size == ALL_PERIODS.size) "全量" else scanPeriods.joinToString("/") { periodTitle(it) }
        Log.i(TAG, "${scanName}扫描完成: 候选${cands.size}只, 命中${classified.size}条(超短${byPeriod[PERIOD_ULTRA_SHORT]!!.size}/短${byPeriod[PERIOD_SHORT]!!.size}/中${byPeriod[PERIOD_MID]!!.size}/长${byPeriod[PERIOD_LONG]!!.size}), ${scan.elapsedMs}ms, K线截止${dataDate ?: "无"}, 大盘转弱跳过${riskOffCount}只")
        scan
    }

    /** 保留非零值（避免 price=0 覆盖） */
    private fun Double.ifTake(fallback: Double): Double = if (this <= 0) fallback else this

    /**
     * 构建四周期 pipeline。
     *
     * 超短/短线在牛市用**趋势跟随**；熊市/震荡用均线粘合。
     * 中线/长线始终用均线粘合。
     */
    private fun buildPeriodPipelines(bullish: Boolean): List<Pair<String, StockCheckPipeline>> {
        val trendMode = if (bullish) AnalysisMode.TREND_FOLLOW
            else AnalysisMode.CONVERGENCE

        return listOf(
            PERIOD_ULTRA_SHORT to StockCheckPipeline(
                mode = trendMode,
                convergenceThreshold = 3.0,
                convergenceDurationDays = 5,
                convergenceDurationRatio = 0.8,
                volumeBreakoutRatio = 1.2,
                minDrawdownPct = 0.0,
                requireChangePct = true,
                minChangePct = 0.0,
                requireCloseAboveConvergenceTop = true,
                requireOpenBelowMAs = true,
                requireThreeDayConfirm = true,
                minPassCount = 7,
                marketTrend = if (bullish) "BULLISH" else null
            ),
            PERIOD_SHORT to StockCheckPipeline(
                mode = trendMode,
                convergenceThreshold = 3.0,
                convergenceDurationDays = 10,
                convergenceDurationRatio = 0.8,
                volumeBreakoutRatio = 1.5,
                minDrawdownPct = 20.0,
                requireChangePct = true,
                minChangePct = 0.0,
                requireAboveAllMAs = true,
                requireThreeDayConfirm = true,
                minPassCount = 7,
                marketTrend = if (bullish) "BULLISH" else null
            ),
            PERIOD_MID to StockCheckPipeline(
                mode = AnalysisMode.CONVERGENCE,
                convergenceThreshold = 2.5,
                convergenceDurationDays = 15,
                convergenceDurationRatio = 0.8,
                moderateVolumeLower = 1.2,
                moderateVolumeUpper = 1.8,
                minDrawdownPct = 30.0,
                requireMA60Rising = true,
                maRisingDays = 5,
                minPassCount = 7,
                marketTrend = if (bullish) "BULLISH" else null
            ),
            PERIOD_LONG to StockCheckPipeline(
                mode = AnalysisMode.CONVERGENCE,
                convergenceThreshold = 2.0,
                convergenceDurationDays = 20,
                convergenceDurationRatio = 0.8,
                requireVolumeShrink = true,
                volumeShrinkRatio = 0.5,
                minDrawdownPct = 40.0,
                requireMA60Rising = true,
                maRisingDays = 10,
                requireMA250Rising = true,
                requireAboveYearLine = true,
                minPassCount = 8,
                marketTrend = if (bullish) "BULLISH" else null
            )
        )
    }

    /**
     * 将四周期分类结果写入「精选股票」（ai_selected_stock 表）。
     *
     * 满足用户诉求：非交易时间扫描选到的股票也能记录到精选股票 tab。
     * 分类命中（超短/短/中/长任一周期）的股票都会写入，score = 命中周期数×20 + 最高 passCount。
     *
     * @param scan 分类结果（可为空，空则自动执行一次 classifyAll）
     * @param source 写入的来源标识：默认 "unified"（选股），预选页传 "preselection"（预选）
     */
    suspend fun saveToAiSelection(scan: ScanResult? = null, source: String = "unified"): Int = withContext(Dispatchers.IO) {
        val result = scan ?: classifyAll()
        val db = StockDatabase.getInstance(context)
        val today = LocalDate.now().format(DATE_FMT)

        // 去重：同一股票合并各周期命中数
        val grouped = result.classified.groupBy { it.code }
        val entities = grouped.map { (code, list) ->
            val name = list.first().name
            val maxPass = list.maxOfOrNull { it.result.passCount } ?: 0
            val periods = list.map { it.period }.toSet().size
            val score = (periods * 20 + maxPass).coerceIn(0, 100)
            val reasons = list.joinToString("；") {
                val ind = it.result.industryTheme?.let { t -> "[产业:$t]" } ?: ""
                "${UnifiedStockClassifier.periodTitle(it.period)}:${it.result.passCount}/${it.result.totalChecks}$ind"
            }
            com.chin.stockanalysis.stock.database.AiSelectedStockEntity(
                stockCode = code,
                stockName = name,
                source = source,
                selectedDate = today,
                score = score,
                reason = reasons.ifEmpty { if (source == "preselection") "预选四周期分类命中" else "统一四周期分类命中" },
                buyPrice = list.first().price
            )
        }
        db.aiSelectedStockDao().insertAll(entities)
        Log.i(TAG, "已写入精选股票: ${entities.size} 只 (source=$source, $today)")
        entities.size
    }

    /**
     * 自动探测大盘状态（BULLISH/NEUTRAL/BEAR）。
     * 基于指数日K（上证 000001）近 20 日均线排列。
     */
    private suspend fun detectMarketRegime(db: StockDatabase): String {
        return try {
            val idx = db.dailySnapshotDao().getByCode("sh000001", 30)
                .map { it.close }
            if (idx.size < 20) return "NEUTRAL"
            val ma5 = idx.takeLast(5).average()
            val ma10 = idx.takeLast(10).average()
            val ma20 = idx.takeLast(20).average()
            val latest = idx.last()
            when {
                latest > ma5 && ma5 > ma10 && ma10 > ma20 -> "BULLISH"
                latest < ma5 && ma5 < ma10 && ma10 < ma20 -> "BEAR"
                else -> "NEUTRAL"
            }
        } catch (e: Exception) {
            Log.w(TAG, "大盘状态探测失败: ${e.message}")
            "NEUTRAL"
        }
    }
}
