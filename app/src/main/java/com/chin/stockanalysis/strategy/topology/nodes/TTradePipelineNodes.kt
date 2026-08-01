package com.chin.stockanalysis.strategy.topology.nodes

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.ChinaMarketTradingHours
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.analysis.CandlePatternDetector
import com.chin.stockanalysis.strategy.market.MarketAnalyzer
import com.chin.stockanalysis.strategy.topology.core.*
import com.chin.stockanalysis.strategy.trade.*
import kotlin.math.abs

// ═══════════════════════════════════════════════════
//  做T Pipeline 數據類
// ═══════════════════════════════════════════════════

/** 做T Pipeline 導入檢查結果 */
data class TTradeImportResult(
    val isTradingDay: Boolean,
    val tradeDate: String
)

/** 持倉基本信息 */
data class THolding(
    val stockCode: String,
    val stockName: String,
    val quantity: Int,
    val avgCost: Double,
    val currentPrice: Double,
    val periodType: String,
    val source: String  // "SIMULATED" or "REAL"
)

/** 持倉基礎技術指標 */
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

/** 持倉載入結果 */
data class THoldingsData(
    val holdings: List<THolding>,
    val snapshots: Map<String, List<com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity>>,
    val basics: Map<String, THoldingBasics>,
    val stockCodes: List<String>
)

/** 機構意圖枚舉 */
enum class InstIntent(val label: String) {
    ACCUMULATING("建倉吸貨"),
    SHAKING("震倉洗盤"),
    PULLING_UP("拉升中"),
    DISTRIBUTING("出貨"),
    NEUTRAL("無法判斷")
}

/** 機構意圖分析結果 */
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

/** 增強信號 */
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

/** 信號合成結果 */
data class TSynthesizeResult(
    val signals: List<EnhancedTSignal>,
    val filteredCount: Int,
    val marketSummary: String,
    val overseasSummary: String
)

/** 建議保存結果 */
data class TRecommendSaveResult(
    val saved: Int,
    val tracked: Int,
    val dayEnded: Boolean
)

// ═══════════════════════════════════════════════════
//  Node 1: 交易日檢查
// ═══════════════════════════════════════════════════

class TTradeImportNode : PipelineNode<Unit, TTradeImportResult> {
    override val nodeId = "t_trade_import"
    override val nodeName = "交易日檢查"
    override val nodeType = NodeType.DATA_SOURCE

    override suspend fun execute(context: PipelineContext, input: Unit): TTradeImportResult {
        val today = context.tradeDate
        val isTrading = !ChinaMarketTradingHours.a股是否休市()
        context.log(nodeId, if (isTrading) "✅ 今日($today)為交易日" else "⛔ 今日($today)休市，Pipeline 跳過")
        return TTradeImportResult(isTradingDay = isTrading, tradeDate = today)
    }
}

// ═══════════════════════════════════════════════════
//  Node 2: 持倉載入 + 日K數據
// ═══════════════════════════════════════════════════

class THoldingsLoadNode(
    private val periodType: String = ""
) : PipelineNode<Any, THoldingsData> {
    override val nodeId = "t_holdings_load"
    override val nodeName = "持倉載入+日K數據"
    override val nodeType = NodeType.DATA_SOURCE

    override suspend fun execute(context: PipelineContext, input: Any): THoldingsData {
        val ctx = context.androidContext
        val db = StockDatabase.getInstance(ctx)
        val holdings = mutableListOf<THolding>()

        // 1. 模擬持倉
        val pt = periodType.ifEmpty { context.config.holdingPeriod }
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
            context.log(nodeId, "讀取模擬持倉失敗: ${e.message}")
        }

        // 2. 真實持倉
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
            context.log(nodeId, "讀取真實持倉失敗: ${e.message}")
        }

        if (holdings.isEmpty()) {
            context.log(nodeId, "⚠️ 無持倉，跳過做T分析")
            return THoldingsData(emptyList(), emptyMap(), emptyMap(), emptyList())
        }

        // 3. 讀取每支持倉的 30 日 K 線 + 計算基礎指標
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
        context.log(nodeId, "📥 載入 ${holdings.size} 支持倉（${codes.size} 只不重複），有效K線 ${snapshots.size} 只")
        context.recordStockFlow(nodeId, nodeName, holdings.size, holdings.size, 0,
            outputCodes = codes)

        return THoldingsData(holdings, snapshots, basics, codes)
    }
}

// ═══════════════════════════════════════════════════
//  Node 3: 日K機構意圖判斷（最核心）
// ═══════════════════════════════════════════════════

class TInstIntentNode : PipelineNode<Any, Map<String, InstIntentResult>> {
    override val nodeId = "t_inst_intent"
    override val nodeName = "日K機構意圖判斷"
    override val nodeType = NodeType.FACTOR_COMPUTE

    override suspend fun execute(context: PipelineContext, input: Any): Map<String, InstIntentResult> {
        val holdingsData = when (input) {
            is THoldingsData -> input
            else -> context.getStageOutput<THoldingsData>("t_hold")
        }
        if (holdingsData == null || holdingsData.holdings.isEmpty()) {
            context.log(nodeId, "⚠️ 無持倉數據，跳過機構意圖分析")
            return emptyMap()
        }

        // 讀取外盤數據
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
                "外盤${if (overseasDir == "BULLISH") "偏多" else if (overseasDir == "BEARISH") "偏空" else "中性"}，$overseasHint"
            } else ""

            results[code] = intent.copy(overseasImpact = overseasImpact)
        }

        val summary = results.entries.joinToString { (code, r) ->
            "$code:${r.intent.label}(${r.tDirection})"
        }
        context.log(nodeId, "📊 機構意圖: $summary")
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

        // ─── 1. 量價分析 ───
        val avgVol5 = prev5.map { it.volume }.average()
        val avgVol10 = prev10.map { it.volume }.average()
        val volTrend = if (avgVol5 > avgVol10 * 1.1) "放量" else if (avgVol5 < avgVol10 * 0.9) "縮量" else "平量"

        val priceChange5 = if (snaps.size >= 5) {
            (latest.close - snaps[snaps.size - 5].close) / snaps[snaps.size - 5].close * 100
        } else 0.0

        val volumePriceSignal = when {
            volTrend == "放量" && abs(priceChange5) < 1.0 -> "放量滯漲"
            volTrend == "放量" && priceChange5 > 2.0 -> "放量上漲"
            volTrend == "放量" && priceChange5 < -2.0 -> "放量下跌"
            volTrend == "縮量" && abs(priceChange5) < 1.0 -> "量縮價穩"
            volTrend == "縮量" && priceChange5 < -1.0 -> "量縮回調"
            volTrend == "縮量" && priceChange5 > 1.0 -> "量縮反彈"
            else -> "量價正常"
        }

        // ─── 2. K線特徵分析 ───
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
            lowerShadowCount >= 3 -> "連續下影線，下方有承接"
            upperShadowCount >= 3 -> "連續上影線，上方拋壓重"
            bullishCount >= 4 -> "連續陽線，多頭強勢"
            bearishCount >= 4 -> "連續陰線，空頭主導"
            else -> "K線無明顯特徵"
        }

        // ─── 3. RSI 區間 ───
        val rsi = computeRSI(snaps, 14)
        val rsiZone = when {
            rsi < 30 -> "oversold"
            rsi < 45 -> "weak"
            rsi < 55 -> "neutral"
            rsi < 70 -> "strong"
            else -> "overbought"
        }

        // ─── 4. 布林帶位置 ───
        val bollPos = basics.pricePosition  // 0=下軌, 0.5=中軌, 1=上軌
        val bollPosition = when {
            bollPos < 0.2 -> "lower"
            bollPos < 0.4 -> "lower_middle"
            bollPos < 0.6 -> "middle"
            bollPos < 0.8 -> "upper_middle"
            else -> "upper"
        }

        // ─── 5. 均線排列 ───
        val maBullish = basics.ma5 > basics.ma10 && basics.ma10 > basics.ma20
        val maBearish = basics.ma5 < basics.ma10 && basics.ma10 < basics.ma20

        // ─── 6. 綜合判斷機構意圖 ───
        val intent: InstIntent
        val confidence: Double
        val tDirection: String
        val reasoning: String

        when {
            // 拉升中 — 不宜做T
            maBullish && bullishCount >= 3 && volTrend != "縮量" && priceChange5 > 3.0 -> {
                intent = InstIntent.PULLING_UP
                confidence = 0.7
                tDirection = "hold"
                reasoning = "均線多頭排列+連續陽線+放量上漲，主力拉升中，不宜做T以免賣飛"
            }
            // 震倉吸貨 — 最佳正T時機
            volumePriceSignal == "量縮價穩" && lowerShadowCount >= 2 && rsi in 35.0..50.0 && bollPos < 0.4 -> {
                intent = InstIntent.SHAKING
                confidence = 0.75
                tDirection = "favor_正T"
                reasoning = "量縮價穩+下影線承接+RSI中性偏弱，主力震倉洗盤，正T低接好時機"
            }
            // 建倉吸貨
            volumePriceSignal == "量縮價穩" && rsi in 30.0..50.0 && bollPos < 0.5 -> {
                intent = InstIntent.ACCUMULATING
                confidence = 0.6
                tDirection = "favor_正T"
                reasoning = "量縮價穩+RSI偏低區間，浮動籌碼減少，主力控盤吸貨中"
            }
            // 出貨 — 反T時機
            volumePriceSignal == "放量滯漲" && upperShadowCount >= 2 && rsi > 65 -> {
                intent = InstIntent.DISTRIBUTING
                confidence = 0.7
                tDirection = "favor_反T"
                reasoning = "放量滯漲+上影線頻現+RSI超買，主力出貨信號，反T高賣時機"
            }
            volumePriceSignal == "放量下跌" && bearishCount >= 3 -> {
                intent = InstIntent.DISTRIBUTING
                confidence = 0.65
                tDirection = "favor_反T"
                reasoning = "放量下跌+連續陰線，主力出逃，反T高賣或考慮減倉"
            }
            // 外盤加持判斷
            overseasDir == "BEARISH" && rsi > 60 -> {
                intent = InstIntent.DISTRIBUTING
                confidence = 0.5
                tDirection = "favor_反T"
                reasoning = "外盤偏空+RSI偏高，注意防守，反T賣出為宜"
            }
            overseasDir == "BULLISH" && rsi < 40 && bollPos < 0.3 -> {
                intent = InstIntent.ACCUMULATING
                confidence = 0.55
                tDirection = "favor_正T"
                reasoning = "外盤偏多+RSI超賣+接近布林下軌，正T低接機會"
            }
            else -> {
                intent = InstIntent.NEUTRAL
                confidence = 0.3
                tDirection = "neutral"
                reasoning = "量價關係不明確，無法判斷機構意圖，觀望為宜"
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
        if (snaps.size < period + 1) return 50.0
        val changes = snaps.takeLast(period + 1).zipWithNext { a, b -> b.close - a.close }
        val gains = changes.filter { it > 0 }
        val losses = changes.filter { it < 0 }.map { abs(it) }
        val avgGain = if (gains.isNotEmpty()) gains.sum() / period else 0.0
        val avgLoss = if (losses.isNotEmpty()) losses.sum() / period else 0.0
        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }
}

// ═══════════════════════════════════════════════════
//  Node 4: 交叉驗證 + 置信度評分（聚合節點）
// ═══════════════════════════════════════════════════

class TSignalSynthesizeNode(
    private val minConfidence: Double = 0.3
) : PipelineNode<Any, TSynthesizeResult> {
    override val nodeId = "t_signal_synthesize"
    override val nodeName = "交叉驗證+置信度評分"
    override val nodeType = NodeType.AGGREGATION

    override suspend fun execute(context: PipelineContext, input: Any): TSynthesizeResult {
        // 從 context 讀取各上游輸出
        val holdingsData = context.getStageOutput<THoldingsData>("t_hold")
        @Suppress("UNCHECKED_CAST")
        val intentMap = context.getStageOutput<Map<String, InstIntentResult>>("t_inst") ?: emptyMap()
        @Suppress("UNCHECKED_CAST")
        val candleMap = context.getStageOutput<Map<String, List<CandlePatternDetector.PatternMatch>>>("t_kline") ?: emptyMap()
        val marketReport = try { context.getMarketReport() } catch (_: Exception) { null }
        val overseas = marketReport?.overseas
        val trendDir = marketReport?.trend?.direction ?: "UNKNOWN"

        if (holdingsData == null || holdingsData.holdings.isEmpty()) {
            context.log(nodeId, "⚠️ 無持倉數據，跳過信號合成")
            return TSynthesizeResult(emptyList(), 0, "", "")
        }

        val tEngine = TTradeEngine(context.androidContext)
        val enhancedSignals = mutableListOf<EnhancedTSignal>()
        var filteredCount = 0

        for (holding in holdingsData.holdings) {
            val code = holding.stockCode
            // 調用 TTradeEngine 生成基礎信號
            val baseSignals = try {
                tEngine.generateSignals(code, holding.quantity, holding.periodType)
            } catch (_: Exception) { emptyList() }

            for (signal in baseSignals) {
                // 只處理開倉腿（T_BUY / RT_SELL），配對腿直接保存
                if (signal.signalType == TTradeType.T_SELL || signal.signalType == TTradeType.RT_BUY) {
                    enhancedSignals.add(EnhancedTSignal(
                        baseSignal = signal, confidence = 0.8,
                        instIntent = null, klinePattern = null,
                        newsScore = 0, overseasImpact = "",
                        scoreBreakdown = "配對腿信號，高優先級", priority = "HIGH"
                    ))
                    continue
                }

                var score = 50
                val breakdown = mutableListOf<String>()

                // ─── 機構意圖 (權重最高 +20/-15/-30) ───
                val intent = intentMap[code]
                if (intent != null) {
                    when {
                        signal.signalType == TTradeType.T_BUY && intent.intent in listOf(InstIntent.ACCUMULATING, InstIntent.SHAKING) -> {
                            score += 20; breakdown.add("機構+20(${intent.intent.label})")
                        }
                        signal.signalType == TTradeType.RT_SELL && intent.intent == InstIntent.DISTRIBUTING -> {
                            score += 20; breakdown.add("機構+20(${intent.intent.label})")
                        }
                        intent.intent == InstIntent.PULLING_UP && signal.signalType == TTradeType.RT_SELL -> {
                            score -= 30; breakdown.add("機構-30(拉升中勿賣)")
                        }
                        signal.signalType == TTradeType.T_BUY && intent.intent == InstIntent.DISTRIBUTING -> {
                            score -= 15; breakdown.add("機構-15(出貨勿接)")
                        }
                        signal.signalType == TTradeType.RT_SELL && intent.intent in listOf(InstIntent.ACCUMULATING, InstIntent.SHAKING) -> {
                            score -= 15; breakdown.add("機構-15(吸貨勿賣)")
                        }
                        else -> { breakdown.add("機構+0(中性)") }
                    }
                } else {
                    breakdown.add("機構+0(無數據)")
                }

                // ─── K線形態 (+15/-10) ───
                val patterns = candleMap[code]
                val patternDesc = patterns?.firstOrNull()?.let { "${it.patternName}(${it.direction.signal})" }
                if (patterns != null && patterns.isNotEmpty()) {
                    val bullishPattern = patterns.any { it.direction == CandlePatternDetector.Direction.BULLISH }
                    val bearishPattern = patterns.any { it.direction == CandlePatternDetector.Direction.BEARISH }
                    when {
                        signal.signalType == TTradeType.T_BUY && bullishPattern -> {
                            score += 15; breakdown.add("K線+15(看多形態)")
                        }
                        signal.signalType == TTradeType.RT_SELL && bearishPattern -> {
                            score += 15; breakdown.add("K線+15(看空形態)")
                        }
                        signal.signalType == TTradeType.T_BUY && bearishPattern -> {
                            score -= 10; breakdown.add("K線-10(看空矛盾)")
                        }
                        signal.signalType == TTradeType.RT_SELL && bullishPattern -> {
                            score -= 10; breakdown.add("K線-10(看多矛盾)")
                        }
                        else -> { breakdown.add("K線+0") }
                    }
                } else {
                    breakdown.add("K線+0(無形態)")
                }

                // ─── 外盤情緒 (+10/-15) ───
                val overseasDesc = when (overseas?.direction) {
                    "BULLISH" -> {
                        if (signal.signalType == TTradeType.T_BUY) { score += 10; breakdown.add("外盤+10(多頭)") }
                        else { score -= 5; breakdown.add("外盤-5(多頭反T)") }
                        "外盤偏多"
                    }
                    "BEARISH" -> {
                        if (signal.signalType == TTradeType.RT_SELL) { score += 10; breakdown.add("外盤+10(空頭)") }
                        else { score -= 15; breakdown.add("外盤-15(空頭正T)") }
                        "外盤偏空"
                    }
                    else -> { breakdown.add("外盤+0"); "外盤中性" }
                }

                // ─── 新聞 (從 context 嘗試讀取 news_strength 輸出) (+10/-20) ───
                val newsScore = context.getStageOutput<Int>("t_news") ?: 0
                @Suppress("UNCHECKED_CAST")
                val newsGuardBlocked = context.getStageOutput<Map<String, List<String>>>("t_nguard_blocked") ?: emptyMap()
                if (newsGuardBlocked.containsKey(code)) {
                    score -= 20; breakdown.add("新聞-20(黑名單)")
                } else if (newsScore > 60) {
                    score += 5; breakdown.add("新聞+5(正面)")
                } else {
                    breakdown.add("新聞+0(中性)")
                }

                // ─── 技術指標 (+10/-10) ───
                val basics = holdingsData.basics[code]
                if (basics != null) {
                    val rsi = intent?.let { parseRSIZone(it.rsiZone) } ?: 50.0
                    when {
                        signal.signalType == TTradeType.T_BUY && rsi in 30.0..50.0 -> {
                            score += 10; breakdown.add("技術+10(RSI合理)")
                        }
                        signal.signalType == TTradeType.RT_SELL && rsi in 65.0..85.0 -> {
                            score += 10; breakdown.add("技術+10(RSI超買)")
                        }
                        basics.avgIntradayRange < 0.015 -> {
                            score -= 10; breakdown.add("技術-10(振幅不足)")
                        }
                        else -> { breakdown.add("技術+0") }
                    }
                }

                // ─── 大盤方向過濾 ───
                if (trendDir == "BEARISH" && signal.signalType == TTradeType.T_BUY) {
                    score -= 10; breakdown.add("大盤-10(空頭正T)")
                }
                if (trendDir == "BULLISH" && signal.signalType == TTradeType.RT_SELL) {
                    score -= 10; breakdown.add("大盤-10(多頭反T)")
                }

                val confidence = (score / 100.0).coerceIn(0.0, 1.0)
                val priority = when {
                    confidence >= 0.7 -> "HIGH"
                    confidence >= 0.5 -> "MEDIUM"
                    else -> "LOW"
                }

                if (confidence < minConfidence) {
                    filteredCount++
                    continue
                }

                // 新聞黑名單直接阻擋
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

        // 每支持倉最多保留 1 個信號（正T/反T互斥，取置信度高的）
        val deduped = enhancedSignals
            .filter { it.baseSignal.signalType == TTradeType.T_BUY || it.baseSignal.signalType == TTradeType.RT_SELL }
            .groupBy { it.baseSignal.stockCode }
            .flatMap { (_, signals) ->
                // 同股票可能有 T_BUY 和 RT_SELL，取置信度高的
                listOf(signals.maxByOrNull { it.confidence }!!)
            }
            .sortedByDescending { it.confidence }

        // 配對腿信號也加入
        val pairingSignals = enhancedSignals.filter {
            it.baseSignal.signalType == TTradeType.T_SELL || it.baseSignal.signalType == TTradeType.RT_BUY
        }
        val finalSignals = (deduped + pairingSignals).sortedByDescending { it.confidence }

        val marketSummary = "大盤${when (trendDir) { "BULLISH" -> "多頭"; "BEARISH" -> "空頭"; else -> "震盪" }}"
        val overseasSummary = when (overseas?.direction) {
            "BULLISH" -> "外盤偏多（${overseas.weightedChange?.let { "%.2f".format(it) } ?: ""}%）"
            "BEARISH" -> "外盤偏空（${overseas.weightedChange?.let { "%.2f".format(it) } ?: ""}%）"
            else -> "外盤中性"
        }

        context.log(nodeId, "📊 合成 ${finalSignals.size} 條增強信號（過濾 $filteredCount 條），$marketSummary，$overseasSummary")
        context.recordStockFlow(nodeId, nodeName,
            holdingsData.holdings.size, finalSignals.size, filteredCount,
            "置信度<$minConfidence 或新聞黑名單",
            outputCodes = finalSignals.map { it.baseSignal.stockCode })

        return TSynthesizeResult(
            signals = finalSignals,
            filteredCount = filteredCount,
            marketSummary = marketSummary,
            overseasSummary = overseasSummary
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
//  Node 5: 建議保存 + 追蹤結算
// ═══════════════════════════════════════════════════

class TRecommendSaveNode : PipelineNode<Any, TRecommendSaveResult> {
    override val nodeId = "t_recommend_save"
    override val nodeName = "建議保存+追蹤結算"
    override val nodeType = NodeType.TRADE_ACTION

    override suspend fun execute(context: PipelineContext, input: Any): TRecommendSaveResult {
        val synthResult = when (input) {
            is TSynthesizeResult -> input
            else -> context.getStageOutput<TSynthesizeResult>("t_synth")
        }
        if (synthResult == null) {
            context.log(nodeId, "⚠️ 無合成結果，跳過保存")
            return TRecommendSaveResult(0, 0, false)
        }

        val tEngine = TTradeEngine(context.androidContext)
        val db = StockDatabase.getInstance(context.androidContext)
        val today = context.tradeDate

        // 1. 保存增強信號為推薦記錄（將置信度/機構意圖/評分編入 reason）
        val enhancedSignals = synthResult.signals.map { es ->
            val prefix = buildString {
                append("[置信度${(es.confidence * 100).toInt()}%")
                es.instIntent?.let { append("|機構:${it.intent.label}") }
                es.klinePattern?.let { append("|$it") }
                append("] ")
                append(es.scoreBreakdown)
                append(" | ")
            }
            es.baseSignal.copy(reason = prefix + es.baseSignal.reason)
        }
        val saved = if (enhancedSignals.isNotEmpty()) {
            tEngine.saveRecommendations(enhancedSignals, "SIMULATED")
        } else 0

        // 2. 跟蹤已有推薦的價格軌跡
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

        // 3. 收盤結算（15:00~15:05）
        var dayEnded = false
        val now = java.time.LocalDateTime.now()
        if (now.hour >= 15 && now.minute < 5) {
            try {
                tEngine.markDayEnd(today)
                dayEnded = true
            } catch (_: Exception) {}
        }

        context.log(nodeId, "💾 保存 $saved 條推薦，追蹤 $tracked 只價格" +
            if (dayEnded) "，收盤結算完成" else "")
        context.recordStockFlow(nodeId, nodeName,
            synthResult.signals.size, saved, synthResult.signals.size - saved,
            "去重/已存在")

        return TRecommendSaveResult(saved = saved, tracked = tracked, dayEnded = dayEnded)
    }
}
