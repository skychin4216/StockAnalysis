package com.chin.stockanalysis.strategy.topology.pipelines

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.ChinaMarketTradingHours
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.analysis.CandlePatternDetector
import com.chin.stockanalysis.strategy.market.MarketAnalyzer
import com.chin.stockanalysis.strategy.topology.core.BaseNode
import com.chin.stockanalysis.strategy.topology.core.*
import com.chin.stockanalysis.strategy.trade.*
import kotlin.math.abs

// ═══════════════════════════════════════════════════
//  做T Pipeline 数据类
// ═══════════════════════════════════════════════════

/** 做T Pipeline 导入检查结果 */
data class TTradeImportResult(
    val isTradingDay: Boolean,
    val tradeDate: String
)

/** 持仓基本信息 */
data class THolding(
    val stockCode: String,
    val stockName: String,
    val quantity: Int,
    val avgCost: Double,
    val currentPrice: Double,
    val periodType: String,
    val source: String  // "SIMULATED" or "REAL"
)

/** 持仓基础技术指标 */
data class THoldingBasics(
    val ma5: Double,
    val ma10: Double,
    val ma20: Double,
    val support: Double,
    val resistance: Double,
    val avgIntradayRange: Double,
    val volumeRatio: Double,
    val pricePosition: Double
)

/** 持仓载入结果 */
data class THoldingsData(
    val holdings: List<THolding>,
    val snapshots: Map<String, List<com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity>>,
    val basics: Map<String, THoldingBasics>,
    val stockCodes: List<String>
)

/** 机构意图枚举 */
enum class InstIntent(val label: String) {
    ACCUMULATING("建仓吸货"),
    SHAKING("震仓洗盘"),
    PULLING_UP("拉升中"),
    DISTRIBUTING("出货"),
    NEUTRAL("无法判断")
}

/** 机构意图分析结果 */
data class InstIntentResult(
    val stockCode: String,
    val intent: InstIntent,
    val confidence: Double,
    val volumePriceSignal: String,
    val klineSummary: String,
    val rsiZone: String,
    val bollPosition: String,
    val tDirection: String,
    val overseasImpact: String,
    val reasoning: String
)

/** 增强信号 */
data class EnhancedTSignal(
    val baseSignal: TTradeSignal,
    val confidence: Double,
    val instIntent: InstIntentResult?,
    val klinePattern: String?,
    val newsScore: Int,
    val overseasImpact: String,
    val scoreBreakdown: String,
    val priority: String
)

/** 信号合成结果 */
data class TSynthesizeResult(
    val signals: List<EnhancedTSignal>,
    val filteredCount: Int,
    val marketSummary: String,
    val overseasSummary: String,
    val timeSlotLabel: String = "",
    val timeSlotHint: String = ""
)

/** 建议保存结果 */
data class TRecommendSaveResult(
    val saved: Int,
    val tracked: Int,
    val dayEnded: Boolean
)

// ═══════════════════════════════════════════════════
//  Node 1: 交易日检查
// ═══════════════════════════════════════════════════

class TTradeImportNode : BaseNode<Unit, TTradeImportResult>("t_trade_import", "交易日检查", NodeType.DATA_SOURCE) {

    override suspend fun execute(context: PipelineContext, input: Unit): TTradeImportResult {
        val today = context.tradeDate
        val isTrading = !ChinaMarketTradingHours.a股是否休市()
        context.log(nodeId, if (isTrading) "✅ 今日($today)为交易日" else "⛔ 今日($today)休市，Pipeline 跳过")
        return TTradeImportResult(isTradingDay = isTrading, tradeDate = today)
    }
}

// ═══════════════════════════════════════════════════
//  Node 2: 持仓载入 + 日K数据
// ═══════════════════════════════════════════════════

class THoldingsLoadNode(
    private val periodType: String = ""
) : BaseNode<Any, THoldingsData>("t_holdings_load", "持仓载入+日K数据", NodeType.DATA_SOURCE) {

    override suspend fun execute(context: PipelineContext, input: Any): THoldingsData {
        val ctx = context.androidContext
        val db = StockDatabase.getInstance(ctx)
        val holdings = mutableListOf<THolding>()

        // 1. 模拟持仓
        val pt = periodType.ifEmpty {
            // 将 holdingPeriod 配置映射为实际 orderType（与各 Fragment 一致）
            when (context.config.holdingPeriod) {
                "ultra_short" -> "ultra_short"
                "short" -> "shortterm"
                "mid" -> "midterm"
                "long" -> "long_term"
                else -> context.config.holdingPeriod
            }
        }
        try {
            val allOrders = db.strategyTradeOrderDao().getRecent(500)
            val periodHoldings = allOrders.filter {
                (it.status == "BUYING" || it.status == "PENDING") && it.orderType == pt
            }
            for (order in periodHoldings) {
                if (order.quantity <= 0) continue
                holdings.add(THolding(
                    stockCode = order.stockCode,
                    stockName = order.stockName,
                    quantity = order.quantity,
                    avgCost = order.buyPrice,
                    currentPrice = order.buyPrice,
                    periodType = pt,
                    source = "SIMULATED"
                ))
            }
        } catch (e: Exception) {
            context.log(nodeId, "读取模拟持仓失败: ${e.message}")
        }

        // 2. 真实持仓
        try {
            val positions = db.realPositionDao().getAllActive()
            for (pos in positions) {
                if (pos.quantity <= 0) continue
                holdings.add(THolding(
                    stockCode = pos.stockCode,
                    stockName = pos.stockName ?: pos.stockCode,
                    quantity = pos.quantity,
                    avgCost = pos.avgBuyPrice,
                    currentPrice = pos.avgBuyPrice,
                    periodType = "RealPosition",
                    source = "REAL"
                ))
            }
        } catch (e: Exception) {
            context.log(nodeId, "读取真实持仓失败: ${e.message}")
        }

        if (holdings.isEmpty()) {
            context.log(nodeId, "⚠️ 无持仓，跳过做T分析")
            return THoldingsData(emptyList(), emptyMap(), emptyMap(), emptyList())
        }

        // 3. 读取每支持仓的 30 日 K 线 + 计算基础指标
        val dao = db.dailySnapshotDao()
        val snapshots = mutableMapOf<String, List<com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity>>()
        val basics = mutableMapOf<String, THoldingBasics>()

        for (h in holdings) {
            val snaps = dao.getByCode(h.stockCode, 30).sortedBy { it.date }
            if (snaps.size < 5) continue
            snapshots[h.stockCode] = snaps

            val latest = snaps.last()
            val ma5 = snaps.takeLast(5).map { it.close }.average()
            val ma10 = snaps.takeLast(10).map { it.close }.average()
            val ma20 = snaps.takeLast(minOf(20, snaps.size)).map { it.close }.average()
            val window20 = snaps.takeLast(minOf(20, snaps.size))
            val recentLow = window20.minOf { it.low }
            val recentHigh = window20.maxOf { it.high }

            val supportPrice = listOf(recentLow, ma5 * 0.98, ma10 * 0.97).maxOrNull() ?: latest.close
            val resistancePrice = listOf(recentHigh, ma5 * 1.02, ma10 * 1.03).minOrNull() ?: latest.close

            val ranges = snaps.takeLast(10).map { if (it.close > 0) (it.high - it.low) / it.close else 0.0 }
            val avgRange = if (ranges.isNotEmpty()) ranges.average() else 0.0

            val vols = snaps.takeLast(5).map { it.volume.toDouble() }
            val avgVol = if (vols.size >= 2) vols.dropLast(1).average() else vols.firstOrNull() ?: 1.0
            val volRatio = if (avgVol > 0) latest.volume.toDouble() / avgVol else 1.0

            val range20 = recentHigh - recentLow
            val pricePos = if (range20 > 0) (latest.close - recentLow) / range20 else 0.5

            basics[h.stockCode] = THoldingBasics(
                ma5 = ma5, ma10 = ma10, ma20 = ma20,
                support = supportPrice, resistance = resistancePrice,
                avgIntradayRange = avgRange, volumeRatio = volRatio,
                pricePosition = pricePos
            )
        }

        val codes = holdings.map { it.stockCode }.distinct()
        context.log(nodeId, "📥 载入 ${holdings.size} 支持仓（${codes.size} 只不重复），有效K线 ${snapshots.size} 只")
        context.recordStockFlow(nodeId, nodeName, holdings.size, holdings.size, 0,
            outputCodes = codes)

        return THoldingsData(holdings, snapshots, basics, codes)
    }
}

// ═══════════════════════════════════════════════════
//  Node 3: 日K机构意图判断（最核心）
// ═══════════════════════════════════════════════════

class TInstIntentNode : BaseNode<Any, Map<String, InstIntentResult>>("t_inst_intent", "日K机构意图判断", NodeType.FACTOR_COMPUTE) {

    override suspend fun execute(context: PipelineContext, input: Any): Map<String, InstIntentResult> {
        val holdingsData = when (input) {
            is THoldingsData -> input
            else -> context.getStageOutput<THoldingsData>("t_hold")
        }
        if (holdingsData == null || holdingsData.holdings.isEmpty()) {
            context.log(nodeId, "⚠️ 无持仓数据，跳过机构意图分析")
            return emptyMap()
        }

        // 读取外盘数据
        val overseas = try { context.getMarketReport()?.overseas } catch (_: Exception) { null }
        val overseasDir = overseas?.direction ?: "UNKNOWN"
        val overseasHint = overseas?.impactHint ?: ""

        val results = mutableMapOf<String, InstIntentResult>()

        for ((code, snaps) in holdingsData.snapshots) {
            if (snaps.size < 10) continue
            val basics = holdingsData.basics[code] ?: continue
            val holding = holdingsData.holdings.firstOrNull { it.stockCode == code } ?: continue

            val intent = analyzeInstitutionalIntent(snaps, basics, overseasDir)
            val overseasImpact = if (overseasDir != "UNKNOWN" && overseasHint.isNotEmpty()) {
                "外盘${if (overseasDir == "BULLISH") "偏多" else if (overseasDir == "BEARISH") "偏空" else "中性"}，$overseasHint"
            } else ""

            results[code] = intent.copy(overseasImpact = overseasImpact)
        }

        val summary = results.entries.joinToString { (code, r) ->
            "$code:${r.intent.label}(${r.tDirection})"
        }
        context.log(nodeId, "📊 机构意图: $summary")
        context.setStageOutput(nodeId, results)
        return results
    }

    private fun analyzeInstitutionalIntent(
        snaps: List<com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity>,
        basics: THoldingBasics,
        overseasDir: String
    ): InstIntentResult {
        val latest = snaps.last()
        val prev5 = snaps.takeLast(5)
        val prev10 = snaps.takeLast(10)

        // ─── 1. 量价分析 ───
        val avgVol5 = prev5.map { it.volume }.average()
        val avgVol10 = prev10.map { it.volume }.average()
        val volTrend = if (avgVol5 > avgVol10 * 1.1) "放量" else if (avgVol5 < avgVol10 * 0.9) "缩量" else "平量"

        val priceChange5 = if (snaps.size >= 5) {
            (latest.close - snaps[snaps.size - 5].close) / snaps[snaps.size - 5].close * 100
        } else 0.0

        val volumePriceSignal = when {
            volTrend == "放量" && abs(priceChange5) < 1.0 -> "放量滞涨"
            volTrend == "放量" && priceChange5 > 2.0 -> "放量上涨"
            volTrend == "放量" && priceChange5 < -2.0 -> "放量下跌"
            volTrend == "缩量" && abs(priceChange5) < 1.0 -> "量缩价稳"
            volTrend == "缩量" && priceChange5 < -1.0 -> "量缩回调"
            volTrend == "缩量" && priceChange5 > 1.0 -> "量缩反弹"
            else -> "量价正常"
        }

        // ─── 2. K线特征分析 ───
        val lowerShadowCount = prev5.count {
            val body = abs(it.close - it.open)
            val lowerShadow = minOf(it.open, it.close) - it.low
            lowerShadow > body * 1.5 && lowerShadow > 0
        }
        val upperShadowCount = prev5.count {
            val body = abs(it.close - it.open)
            val upperShadow = it.high - maxOf(it.open, it.close)
            upperShadow > body * 1.5 && upperShadow > 0
        }
        val bullishCount = prev5.count { it.close > it.open }
        val bearishCount = prev5.count { it.close < it.open }

        val klineSummary = when {
            lowerShadowCount >= 3 -> "连续下影线，下方有承接"
            upperShadowCount >= 3 -> "连续上影线，上方抛压重"
            bullishCount >= 4 -> "连续阳线，多头强势"
            bearishCount >= 4 -> "连续阴线，空头主导"
            else -> "K线无明显特征"
        }

        // ─── 3. RSI 区间 ───
        val rsi = computeRSI(snaps, 14)
        val rsiZone = when {
            rsi < 30 -> "oversold"
            rsi < 45 -> "weak"
            rsi < 55 -> "neutral"
            rsi < 70 -> "strong"
            else -> "overbought"
        }

        // ─── 4. 布林带位置 ───
        val bollPos = basics.pricePosition  // 0=下轨, 0.5=中轨, 1=上轨
        val bollPosition = when {
            bollPos < 0.2 -> "lower"
            bollPos < 0.4 -> "lower_middle"
            bollPos < 0.6 -> "middle"
            bollPos < 0.8 -> "upper_middle"
            else -> "upper"
        }

        // ─── 5. 均线排列 ───
        val maBullish = basics.ma5 > basics.ma10 && basics.ma10 > basics.ma20
        val maBearish = basics.ma5 < basics.ma10 && basics.ma10 < basics.ma20

        // ─── 6. 综合判断机构意图 ───
        val intent: InstIntent
        val confidence: Double
        val tDirection: String
        val reasoning: String

        when {
            // 拉升中 — 不宜做T
            maBullish && bullishCount >= 3 && volTrend != "缩量" && priceChange5 > 3.0 -> {
                intent = InstIntent.PULLING_UP
                confidence = 0.7
                tDirection = "hold"
                reasoning = "均线多头排列+连续阳线+放量上涨，主力拉升中，不宜做T以免卖飞"
            }
            // 震仓吸货 — 最佳正T时机
            volumePriceSignal == "量缩价稳" && lowerShadowCount >= 2 && rsi in 35.0..50.0 && bollPos < 0.4 -> {
                intent = InstIntent.SHAKING
                confidence = 0.75
                tDirection = "favor_正T"
                reasoning = "量缩价稳+下影线承接+RSI中性偏弱，主力震仓洗盘，正T低接好时机"
            }
            // 建仓吸货
            volumePriceSignal == "量缩价稳" && rsi in 30.0..50.0 && bollPos < 0.5 -> {
                intent = InstIntent.ACCUMULATING
                confidence = 0.6
                tDirection = "favor_正T"
                reasoning = "量缩价稳+RSI偏低区间，浮动筹码减少，主力控盘吸货中"
            }
            // 出货 — 反T时机
            volumePriceSignal == "放量滞涨" && upperShadowCount >= 2 && rsi > 65 -> {
                intent = InstIntent.DISTRIBUTING
                confidence = 0.7
                tDirection = "favor_反T"
                reasoning = "放量滞涨+上影线频现+RSI超买，主力出货信号，反T高卖时机"
            }
            volumePriceSignal == "放量下跌" && bearishCount >= 3 -> {
                intent = InstIntent.DISTRIBUTING
                confidence = 0.65
                tDirection = "favor_反T"
                reasoning = "放量下跌+连续阴线，主力出逃，反T高卖或考虑减仓"
            }
            // 外盘加持判断
            overseasDir == "BEARISH" && rsi > 60 -> {
                intent = InstIntent.DISTRIBUTING
                confidence = 0.5
                tDirection = "favor_反T"
                reasoning = "外盘偏空+RSI偏高，注意防守，反T卖出为宜"
            }
            overseasDir == "BULLISH" && rsi < 40 && bollPos < 0.3 -> {
                intent = InstIntent.ACCUMULATING
                confidence = 0.55
                tDirection = "favor_正T"
                reasoning = "外盘偏多+RSI超卖+接近布林下轨，正T低接机会"
            }
            else -> {
                intent = InstIntent.NEUTRAL
                confidence = 0.3
                tDirection = "neutral"
                reasoning = "量价关系不明确，无法判断机构意图，观望为宜"
            }
        }

        return InstIntentResult(
            stockCode = snaps.last().code,
            intent = intent,
            confidence = confidence,
            volumePriceSignal = volumePriceSignal,
            klineSummary = klineSummary,
            rsiZone = rsiZone,
            bollPosition = bollPosition,
            tDirection = tDirection,
            overseasImpact = "",
            reasoning = reasoning
        )
    }

    private fun computeRSI(snaps: List<com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity>, period: Int): Double {
        return com.chin.stockanalysis.strategy.analysis.RsiCalculator.fromSnaps(snaps, period)
    }
}

// ═══════════════════════════════════════════════════
//  Node 4: 交叉验证 + 置信度评分（聚合节点）
// ═══════════════════════════════════════════════════

class TSignalSynthesizeNode(
    private val minConfidence: Double = 0.3
) : BaseNode<Any, TSynthesizeResult>("t_signal_synthesize", "交叉验证+置信度评分", NodeType.AGGREGATION) {

    override suspend fun execute(context: PipelineContext, input: Any): TSynthesizeResult {
        // AGGREGATION 节点：从 context.stageOutputs 按 XML nodeId 安全读取上游输出
        val holdingsData = context.stageOutputs["t_hold"] as? THoldingsData
        // 机构意图：Map<String, InstIntentResult>，用 value 类型安全区分
        val intentMap: Map<String, InstIntentResult> = run {
            val raw = context.stageOutputs["t_inst"]
            if (raw is Map<*, *> && raw.values.firstOrNull() is InstIntentResult) {
                @Suppress("UNCHECKED_CAST")
                raw as Map<String, InstIntentResult>
            } else emptyMap()
        }
        // K线形态：Map<String, List<PatternMatch>>，用 value 类型安全区分
        val candleMap: Map<String, List<CandlePatternDetector.PatternMatch>> = run {
            val raw = context.stageOutputs["t_kline"]
            if (raw is Map<*, *> && raw.values.firstOrNull() is List<*>) {
                @Suppress("UNCHECKED_CAST")
                raw as Map<String, List<CandlePatternDetector.PatternMatch>>
            } else emptyMap()
        }

        val marketReport = try { context.getMarketReport() } catch (_: Exception) { null }
        val overseas = marketReport?.overseas
        val trendDir = marketReport?.trend?.direction ?: "UNKNOWN"

        if (holdingsData == null || holdingsData.holdings.isEmpty()) {
            context.log(nodeId, "⚠️ 无持仓数据，跳过信号合成")
            return TSynthesizeResult(emptyList(), 0, "", "")
        }

        val tEngine = TTradeEngine(context.androidContext)
        val enhancedSignals = mutableListOf<EnhancedTSignal>()
        var filteredCount = 0

        for (holding in holdingsData.holdings) {
            val code = holding.stockCode
            // 调用 TTradeEngine 生成基础信号
            val baseSignals = try {
                tEngine.generateSignals(code, holding.quantity, holding.periodType)
            } catch (_: Exception) { emptyList() }

            for (signal in baseSignals) {
                // 只处理开仓腿（T_BUY / RT_SELL），配对腿直接保存
                if (signal.signalType == TTradeType.T_SELL || signal.signalType == TTradeType.RT_BUY) {
                    enhancedSignals.add(EnhancedTSignal(
                        baseSignal = signal, confidence = 0.8,
                        instIntent = null, klinePattern = null,
                        newsScore = 0, overseasImpact = "",
                        scoreBreakdown = "配对腿信号，高优先级", priority = "HIGH"
                    ))
                    continue
                }

                var score = 50
                val breakdown = mutableListOf<String>()

                // ─── 机构意图 (权重最高 +20/-15/-30) ───
                val intent = intentMap[code]
                if (intent != null) {
                    when {
                        signal.signalType == TTradeType.T_BUY && intent.intent in listOf(InstIntent.ACCUMULATING, InstIntent.SHAKING) -> {
                            score += 20; breakdown.add("机构+20(${intent.intent.label})")
                        }
                        signal.signalType == TTradeType.RT_SELL && intent.intent == InstIntent.DISTRIBUTING -> {
                            score += 20; breakdown.add("机构+20(${intent.intent.label})")
                        }
                        intent.intent == InstIntent.PULLING_UP && signal.signalType == TTradeType.RT_SELL -> {
                            score -= 30; breakdown.add("机构-30(拉升中勿卖)")
                        }
                        signal.signalType == TTradeType.T_BUY && intent.intent == InstIntent.DISTRIBUTING -> {
                            score -= 15; breakdown.add("机构-15(出货勿接)")
                        }
                        signal.signalType == TTradeType.RT_SELL && intent.intent in listOf(InstIntent.ACCUMULATING, InstIntent.SHAKING) -> {
                            score -= 15; breakdown.add("机构-15(吸货勿卖)")
                        }
                        else -> { breakdown.add("机构+0(中性)") }
                    }
                } else {
                    breakdown.add("机构+0(无数据)")
                }

                // ─── K线形态 (+15/-10) ───
                val patterns = candleMap[code]
                val patternDesc = patterns?.firstOrNull()?.let { "${it.patternName}(${it.direction.signal})" }
                if (patterns != null && patterns.isNotEmpty()) {
                    val bullishPattern = patterns.any { it.direction == CandlePatternDetector.Direction.BULLISH }
                    val bearishPattern = patterns.any { it.direction == CandlePatternDetector.Direction.BEARISH }
                    when {
                        signal.signalType == TTradeType.T_BUY && bullishPattern -> {
                            score += 15; breakdown.add("K线+15(看多形态)")
                        }
                        signal.signalType == TTradeType.RT_SELL && bearishPattern -> {
                            score += 15; breakdown.add("K线+15(看空形态)")
                        }
                        signal.signalType == TTradeType.T_BUY && bearishPattern -> {
                            score -= 10; breakdown.add("K线-10(看空矛盾)")
                        }
                        signal.signalType == TTradeType.RT_SELL && bullishPattern -> {
                            score -= 10; breakdown.add("K线-10(看多矛盾)")
                        }
                        else -> { breakdown.add("K线+0") }
                    }
                } else {
                    breakdown.add("K线+0(无形态)")
                }

                // ─── 外盘情绪 (+10/-15) ───
                val overseasDesc = when (overseas?.direction) {
                    "BULLISH" -> {
                        if (signal.signalType == TTradeType.T_BUY) { score += 10; breakdown.add("外盘+10(多头)") }
                        else { score -= 5; breakdown.add("外盘-5(多头反T)") }
                        "外盘偏多"
                    }
                    "BEARISH" -> {
                        if (signal.signalType == TTradeType.RT_SELL) { score += 10; breakdown.add("外盘+10(空头)") }
                        else { score -= 15; breakdown.add("外盘-15(空头正T)") }
                        "外盘偏空"
                    }
                    else -> { breakdown.add("外盘+0"); "外盘中性" }
                }

                // ─── 新闻 (从 context 尝试读取 news_strength 输出) (+10/-20) ───
                val newsScore = context.stageOutputs["t_news"] as? Int ?: 0
                @Suppress("UNCHECKED_CAST")
                val newsGuardBlocked = run {
                    val raw = context.stageOutputs["t_nguard_blocked"]
                    if (raw is Map<*, *>) raw as Map<String, List<String>> else emptyMap<String, List<String>>()
                }
                if (newsGuardBlocked.containsKey(code)) {
                    score -= 20; breakdown.add("新闻-20(黑名单)")
                } else if (newsScore > 60) {
                    score += 5; breakdown.add("新闻+5(正面)")
                } else {
                    breakdown.add("新闻+0(中性)")
                }

                // ─── 技术指标 (+10/-10) ───
                val basics = holdingsData.basics[code]
                if (basics != null) {
                    val rsi = intent?.let { parseRSIZone(it.rsiZone) } ?: 50.0
                    when {
                        signal.signalType == TTradeType.T_BUY && rsi in 30.0..50.0 -> {
                            score += 10; breakdown.add("技术+10(RSI合理)")
                        }
                        signal.signalType == TTradeType.RT_SELL && rsi in 65.0..85.0 -> {
                            score += 10; breakdown.add("技术+10(RSI超买)")
                        }
                        basics.avgIntradayRange < 0.015 -> {
                            score -= 10; breakdown.add("技术-10(振幅不足)")
                        }
                        else -> { breakdown.add("技术+0") }
                    }
                }

                // ─── 大盘方向过滤 ───
                if (trendDir == "BEARISH" && signal.signalType == TTradeType.T_BUY) {
                    score -= 10; breakdown.add("大盘-10(空头正T)")
                }
                if (trendDir == "BULLISH" && signal.signalType == TTradeType.RT_SELL) {
                    score -= 10; breakdown.add("大盘-10(多头反T)")
                }

                // ─── 时段权重调整（做T七个关键时间点） ───
                val timeAdj = TTimeSlotAdjuster.adjust(signal.signalType)
                val timeScoreAdj = timeAdj.tBuyScoreAdj + timeAdj.rtSellScoreAdj
                if (timeScoreAdj != 0) {
                    score += timeScoreAdj
                    breakdown.add("时段${if (timeScoreAdj >= 0) "+" else ""}$timeScoreAdj(${timeAdj.slot.label})")
                }
                if (timeAdj.slot == TTimeSlot.NON_TRADING) {
                    // 非交易时段不产生信号
                    filteredCount++
                    continue
                }

                val rawConfidence = (score / 100.0).coerceIn(0.0, 1.0)
                val confidence = (rawConfidence * timeAdj.confidenceScale).coerceIn(0.0, 1.0)
                val priority = when {
                    confidence >= 0.7 -> "HIGH"
                    confidence >= 0.5 -> "MEDIUM"
                    else -> "LOW"
                }

                if (confidence < minConfidence) {
                    filteredCount++
                    continue
                }

                // 新闻黑名单直接阻挡
                if (newsGuardBlocked.containsKey(code) && signal.signalType == TTradeType.T_BUY) {
                    filteredCount++
                    continue
                }

                enhancedSignals.add(EnhancedTSignal(
                    baseSignal = signal,
                    confidence = confidence,
                    instIntent = intent,
                    klinePattern = patternDesc,
                    newsScore = newsScore,
                    overseasImpact = overseasDesc,
                    scoreBreakdown = breakdown.joinToString(", "),
                    priority = priority
                ))
            }
        }

        // 每支持仓最多保留 1 个信号（正T/反T互斥，取置信度高的）
        val deduped = enhancedSignals
            .filter { it.baseSignal.signalType == TTradeType.T_BUY || it.baseSignal.signalType == TTradeType.RT_SELL }
            .groupBy { it.baseSignal.stockCode }
            .flatMap { (_, signals) ->
                // 同股票可能有 T_BUY 和 RT_SELL，取置信度高的
                listOf(signals.maxByOrNull { it.confidence }!!)
            }
            .sortedByDescending { it.confidence }

        // 配对腿信号也加入
        val pairingSignals = enhancedSignals.filter {
            it.baseSignal.signalType == TTradeType.T_SELL || it.baseSignal.signalType == TTradeType.RT_BUY
        }
        val finalSignals = (deduped + pairingSignals).sortedByDescending { it.confidence }

        val marketSummary = "大盘${when (trendDir) { "BULLISH" -> "多头"; "BEARISH" -> "空头"; else -> "震荡" }}"
        val overseasSummary = when (overseas?.direction) {
            "BULLISH" -> "外盘偏多（${overseas.weightedChange?.let { "%.2f".format(it) } ?: ""}%）"
            "BEARISH" -> "外盘偏空（${overseas.weightedChange?.let { "%.2f".format(it) } ?: ""}%）"
            else -> "外盘中性"
        }

        val timeSlotSummary = TTimeSlotAdjuster.formatSummary()
        context.log(nodeId, "📊 合成 ${finalSignals.size} 条增强信号（过滤 $filteredCount 条），$marketSummary，$overseasSummary")
        context.log(nodeId, "⏰ $timeSlotSummary")
        context.recordStockFlow(nodeId, nodeName,
            holdingsData.holdings.size, finalSignals.size, filteredCount,
            "置信度<$minConfidence 或新闻黑名单",
            outputCodes = finalSignals.map { it.baseSignal.stockCode })

        val currentSlot = TTimeSlot.fromTime()
        return TSynthesizeResult(
            signals = finalSignals,
            filteredCount = filteredCount,
            marketSummary = marketSummary,
            overseasSummary = overseasSummary,
            timeSlotLabel = "${currentSlot.emoji} ${currentSlot.label}",
            timeSlotHint = currentSlot.actionHint
        )
    }

    private fun parseRSIZone(zone: String): Double = when (zone) {
        "oversold" -> 25.0
        "weak" -> 40.0
        "neutral" -> 50.0
        "strong" -> 65.0
        "overbought" -> 80.0
        else -> 50.0
    }
}

// ═══════════════════════════════════════════════════
//  Node 5: 建议保存 + 追踪结算
// ═══════════════════════════════════════════════════

class TRecommendSaveNode : BaseNode<Any, TRecommendSaveResult>("t_recommend_save", "建议保存+追踪结算", NodeType.TRADE_ACTION) {

    override suspend fun execute(context: PipelineContext, input: Any): TRecommendSaveResult {
        val synthResult = when (input) {
            is TSynthesizeResult -> input
            else -> context.getStageOutput<TSynthesizeResult>("t_synth")
        }
        if (synthResult == null) {
            context.log(nodeId, "⚠️ 无合成结果，跳过保存")
            return TRecommendSaveResult(0, 0, false)
        }

        val tEngine = TTradeEngine(context.androidContext)
        val db = StockDatabase.getInstance(context.androidContext)
        val today = context.tradeDate

        // 1. 保存增强信号为推荐记录（将置信度/机构意图/评分/时段编入 reason）
        val timeSlot = TTimeSlot.fromTime()
        val enhancedSignals = synthResult.signals.map { es ->
            val prefix = buildString {
                append("[置信度${(es.confidence * 100).toInt()}%")
                es.instIntent?.let { append("|机构:${it.intent.label}") }
                es.klinePattern?.let { append("|$it") }
                if (timeSlot != TTimeSlot.NON_TRADING) append("|${timeSlot.emoji}${timeSlot.label}")
                append("] ")
                append(es.scoreBreakdown)
                append(" | ")
            }
            es.baseSignal.copy(reason = prefix + es.baseSignal.reason)
        }
        val saved = if (enhancedSignals.isNotEmpty()) {
            tEngine.saveRecommendations(enhancedSignals, "SIMULATED")
        } else 0

        // 2. 跟踪已有推荐的价格轨迹
        var tracked = 0
        try {
            val currentPrices = mutableMapOf<String, Double>()
            val snapshots = db.dailySnapshotDao().getByDate(today)
            for (snap in snapshots) {
                currentPrices[snap.code] = snap.close
            }
            if (currentPrices.isNotEmpty()) {
                tEngine.trackOutcomeForRecommendations(currentPrices)
                tracked = currentPrices.size
            }
        } catch (_: Exception) {}

        // 3. 收盘结算（15:00~15:05）
        var dayEnded = false
        val now = java.time.LocalDateTime.now()
        if (now.hour >= 15 && now.minute < 5) {
            try {
                tEngine.markDayEnd(today)
                dayEnded = true
            } catch (_: Exception) {}
        }

        context.log(nodeId, "💾 保存 $saved 条推荐，追踪 $tracked 只价格" +
            if (dayEnded) "，收盘结算完成" else "")
        context.recordStockFlow(nodeId, nodeName,
            synthResult.signals.size, saved, synthResult.signals.size - saved,
            "去重/已存在")

        return TRecommendSaveResult(saved = saved, tracked = tracked, dayEnded = dayEnded)
    }
}
