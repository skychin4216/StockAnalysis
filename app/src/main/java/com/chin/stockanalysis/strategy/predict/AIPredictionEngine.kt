package com.chin.stockanalysis.strategy.predict

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.ApiConfigManager
import com.chin.stockanalysis.ApiProvider
import com.chin.stockanalysis.news.NewsFactorManager
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.models.StrategySignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * ## AI 综合预测引擎
 *
 * 通过 AI（LLM）综合分析多策略打分 + 历史数据特征 + 新闻因子，
 * 动态选择预测方案，输出 3-5 只最可能上涨的股票。
 *
 * ### 两套方案（AI 动态选择）
 * - **方案A（多日特征）**: 近5日 OHLCV 序列特征 → AI 推理
 * - **方案B（新闻+技术指标）**: NewsFactor + 技术指标 → AI 推理
 *
 * ### 使用方式
 * ```kotlin
 * val engine = AIPredictionEngine(context)
 * val prediction = engine.predict(
 *     strategyResults = results,
 *     selectedDate = "2026-05-30"
 * )
 * // prediction.topPicks → 3-5 只推荐股票
 * // prediction.mode → "A" 或 "B"
 * ```
 */
class AIPredictionEngine(private val context: Context) {

    companion object {
        private const val TAG = "AIPredictionEngine"
    }

    private val db = StockDatabase.getInstance(context)
    private val newsManager = NewsFactorManager(context)

    /**
     * AI 预测结果
     */
    data class AIPrediction(
        /** 使用的方案 A/B */
        val mode: String,
        /** 方案选择的理由 */
        val modeReason: String,
        /** 综合推荐 Top 3-5 只股票 */
        val topPicks: List<AIPick>,
        /** 市场总体判断 */
        val marketOutlook: String,
        /** 风险提示 */
        val riskWarning: String
    )

    data class AIPick(
        val stockCode: String,
        val stockName: String,
        val rank: Int,
        /** 综合得分 0-100 */
        val compositeScore: Int,
        /** 上涨概率估计 */
        val upProbability: Int,
        /** 推荐理由 */
        val reason: String,
        /** 建仓建议 */
        val actionSuggestion: String
    )

    /**
     * 执行 AI 预测
     *
     * @param strategyResults 各策略扫描结果
     * @param selectedDate 选定的交易日
     * @param onProgress 进度回调
     */
    suspend fun predict(
        strategyResults: List<ScreeningResult>,
        selectedDate: String,
        onProgress: ((String) -> Unit)? = null
    ): AIPrediction? {
        val provider = ApiConfigManager.getInstance(context).createCurrentProvider() ?: return null

        return try {
            onProgress?.invoke("正在收集历史数据...")
            // 1. 收集候选股票（所有策略命中股票的去重集合）
            val candidateStocks = collectCandidateStocks(strategyResults)

            onProgress?.invoke("正在获取多日特征...")
            // 2. 获取近5日历史数据特征（方案A）
            val multiDayFeatures = buildMultiDayFeatures(candidateStocks, selectedDate)

            onProgress?.invoke("正在获取新闻因子...")
            // 3. 获取活跃新闻因子（方案B）
            val newsFactors = newsManager.getActiveFactors(50)

            onProgress?.invoke("正在构建AI提示...")
            // 4. 构建综合 Prompt
            val prompt = buildPredictionPrompt(
                strategyResults = strategyResults,
                candidateStocks = candidateStocks,
                multiDayFeatures = multiDayFeatures,
                newsFactors = newsFactors,
                selectedDate = selectedDate
            )

            onProgress?.invoke("AI 正在分析预测...")
            // 5. 调用 AI
            val response = withContext(Dispatchers.IO) {
                sendSyncRequest(provider, prompt)
            }

            // 6. 解析结果
            parsePrediction(response)

        } catch (e: Exception) {
            Log.e(TAG, "AI 预测失败: ${e.message}", e)
            null
        }
    }

    // ════════════════════════════════════════
    // 数据收集
    // ════════════════════════════════════════

    /** 从各策略结果中收集所有候选股票（去重） */
    private fun collectCandidateStocks(results: List<ScreeningResult>): List<StockStrategyScore> {
        val map = linkedMapOf<String, StockStrategyScore>()
        for (result in results) {
            for (signal in result.signals) {
                val entry = map.getOrPut(signal.stockCode) {
                    StockStrategyScore(signal.stockCode, signal.stockName, mutableListOf(), 0, 0.0)
                }
                entry.strategyScores.add(StrategyScore(result.strategyName, signal.strength))
                entry.totalStrength += signal.strength
                entry.changePercent = signal.changePercent
            }
        }
        // 按总强度排序取 Top 20
        return map.values.sortedByDescending { it.totalStrength }.take(20)
    }

    data class StockStrategyScore(
        val stockCode: String,
        val stockName: String,
        val strategyScores: MutableList<StrategyScore>,
        var totalStrength: Int,
        var changePercent: Double
    )

    data class StrategyScore(
        val strategyName: String,
        val strength: Int
    )

    /** 获取候选股票近5日的 OHLCV 特征 */
    private suspend fun buildMultiDayFeatures(
        candidates: List<StockStrategyScore>,
        selectedDate: String
    ): Map<String, List<DayFeature>> {
        val result = mutableMapOf<String, List<DayFeature>>()
        val availableDates = db.dailySnapshotDao().getAvailableDates(30)
            .filter { it <= selectedDate }
            .take(5)

        for (cand in candidates) {
            val features = mutableListOf<DayFeature>()
            for (date in availableDates) {
                val snapshots = db.dailySnapshotDao().getByDate(date)
                val snap = snapshots.firstOrNull { it.code == cand.stockCode } ?: continue
                features.add(DayFeature(
                    date = date,
                    open = snap.open,
                    high = snap.high,
                    low = snap.low,
                    close = snap.close,
                    volume = snap.volume,
                    changePct = snap.changePct
                ))
            }
            if (features.isNotEmpty()) result[cand.stockCode] = features
        }
        return result
    }

    data class DayFeature(
        val date: String,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Long,
        val changePct: Double
    )

    // ════════════════════════════════════════
    // Prompt 构建
    // ════════════════════════════════════════

    private fun buildPredictionPrompt(
        strategyResults: List<ScreeningResult>,
        candidateStocks: List<StockStrategyScore>,
        multiDayFeatures: Map<String, List<DayFeature>>,
        newsFactors: List<com.chin.stockanalysis.news.NewsFactorEntity>,
        selectedDate: String
    ): String {
        val sb = StringBuilder()

        sb.appendLine("你是一个A股量化选股AI助手。请综合以下信息，预测下一个交易日最可能上涨的3-5只股票。")
        sb.appendLine()
        sb.appendLine("## 你需要做的")
        sb.appendLine("1. 先从方案A（多日OHLCV序列）和方案B（新闻因子+技术指标）中选择更合适的方案")
        sb.appendLine("2. 用所选方案分析所有候选股票")
        sb.appendLine("3. 输出3-5只综合最可能上涨的股票，附详细理由")
        sb.appendLine()

        // ── 策略打分结果 ──
        sb.appendLine("## 多策略扫描结果（交易日: $selectedDate）")
        sb.appendLine()
        for (r in strategyResults) {
            sb.appendLine("### ${r.strategyName} (命中${r.hitCount}只)")
            for (s in r.signals.take(5)) {
                sb.appendLine("- ${s.stockName}(${s.stockCode.takeLast(6)}) 强度:${s.strength}% 价格:${"%.2f".format(s.currentPrice)} 涨跌:${"%.2f".format(s.changePercent)}% [${s.action}]")
            }
            sb.appendLine()
        }

        // ── 候选股票汇总 ──
        sb.appendLine("## 候选股票综合得分汇总")
        sb.appendLine("| 代码 | 名称 | 命中策略数 | 总强度 | 涨跌幅 | 各策略得分 |")
        sb.appendLine("|------|------|-----------|--------|--------|-----------|")
        for (c in candidateStocks.take(15)) {
            val strategyStr = c.strategyScores.joinToString(",") { "${it.strategyName.take(4)}:${it.strength}" }
            sb.appendLine("| ${c.stockCode.takeLast(6)} | ${c.stockName} | ${c.strategyScores.size} | ${c.totalStrength} | ${"%.2f".format(c.changePercent)}% | $strategyStr |")
        }
        sb.appendLine()

        // ── 方案A：多日特征 ──
        if (multiDayFeatures.isNotEmpty()) {
            sb.appendLine("## 方案A：近5日 OHLCV 序列特征")
            for ((code, features) in multiDayFeatures.entries.take(8)) {
                val name = candidateStocks.firstOrNull { it.stockCode == code }?.stockName ?: code
                sb.appendLine("### $name(${code.takeLast(6)})")
                sb.appendLine("| 日期 | Open | High | Low | Close | Volume | Change% |")
                sb.appendLine("|------|------|------|-----|-------|--------|---------|")
                for (f in features) {
                    sb.appendLine("| ${f.date} | ${"%.2f".format(f.open)} | ${"%.2f".format(f.high)} | ${"%.2f".format(f.low)} | ${"%.2f".format(f.close)} | ${formatVolume(f.volume)} | ${"%.2f".format(f.changePct)}% |")
                }
                sb.appendLine()
            }
        }

        // ── 方案B：新闻因子 ──
        if (newsFactors.isNotEmpty()) {
            sb.appendLine("## 方案B：近期新闻利好利空因子（活跃，3个月内）")
            val bullish = newsFactors.filter { it.sentiment > 0 }.take(10)
            val bearish = newsFactors.filter { it.sentiment < 0 }.take(10)
            if (bullish.isNotEmpty()) {
                sb.appendLine("### 📈 利好因子")
                for (f in bullish) {
                    sb.appendLine("- [${f.companyName}] ${f.title}  (强度:${f.impactStrength}) [${f.newsDate}] 标签:${f.tags}")
                }
            }
            if (bearish.isNotEmpty()) {
                sb.appendLine("### 📉 利空因子")
                for (f in bearish) {
                    sb.appendLine("- [${f.companyName}] ${f.title}  (强度:${f.impactStrength}) [${f.newsDate}] 标签:${f.tags}")
                }
            }
            sb.appendLine()
        }

        // ── 输出格式要求 ──
        sb.appendLine("## 请按以下 JSON 格式输出（仅输出 JSON，不要其他文字）")
        sb.appendLine("```json")
        sb.appendLine("{")
        sb.appendLine("  \"selected_mode\": \"A 或 B\",")
        sb.appendLine("  \"mode_reason\": \"选择该方案的理由(20字内)\",")
        sb.appendLine("  \"market_outlook\": \"市场总体判断(30字内)\",")
        sb.appendLine("  \"risk_warning\": \"风险提示(30字内)\",")
        sb.appendLine("  \"top_picks\": [")
        sb.appendLine("    {")
        sb.appendLine("      \"rank\": 1,")
        sb.appendLine("      \"stock_code\": \"sh600519\",")
        sb.appendLine("      \"stock_name\": \"贵州茅台\",")
        sb.appendLine("      \"composite_score\": 85,")
        sb.appendLine("      \"up_probability\": 70,")
        sb.appendLine("      \"reason\": \"被3个策略共同选中，均线金叉+放量突破，新闻面利好(30字内)\",")
        sb.appendLine("      \"action\": \"建议逢低建仓，止损位-3%\"")
        sb.appendLine("    }")
        sb.appendLine("  ]")
        sb.appendLine("}")
        sb.appendLine("```")

        return sb.toString()
    }

    private fun formatVolume(volume: Long): String {
        return when {
            volume >= 100_000_000 -> "${"%.1f".format(volume / 100_000_000.0)}亿"
            volume >= 10_000 -> "${"%.1f".format(volume / 10_000.0)}万"
            else -> volume.toString()
        }
    }

    // ════════════════════════════════════════
    // 解析
    // ════════════════════════════════════════

    private fun parsePrediction(response: String): AIPrediction? {
        return try {
            val start = response.indexOf('{')
            val end = response.lastIndexOf('}')
            if (start == -1 || end == -1) return null
            val obj = JSONObject(response.substring(start, end + 1))

            val picks = mutableListOf<AIPick>()
            val arr = obj.optJSONArray("top_picks")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    picks.add(AIPick(
                        stockCode = p.optString("stock_code", ""),
                        stockName = p.optString("stock_name", ""),
                        rank = p.optInt("rank", i + 1),
                        compositeScore = p.optInt("composite_score", 50),
                        upProbability = p.optInt("up_probability", 50),
                        reason = p.optString("reason", ""),
                        actionSuggestion = p.optString("action", "")
                    ))
                }
            }

            AIPrediction(
                mode = obj.optString("selected_mode", "B"),
                modeReason = obj.optString("mode_reason", ""),
                topPicks = picks.sortedBy { it.rank },
                marketOutlook = obj.optString("market_outlook", ""),
                riskWarning = obj.optString("risk_warning", "投资有风险，入市需谨慎")
            )
        } catch (e: Exception) {
            Log.w(TAG, "解析AI预测失败: ${e.message}")
            null
        }
    }

    private suspend fun sendSyncRequest(provider: ApiProvider, prompt: String): String {
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            provider.sendMessageStream(
                messages = emptyList(),
                systemPrompt = prompt,
                onSuccess = {},
                onComplete = { full -> cont.resumeWith(Result.success(full)) },
                onError = { err -> cont.resumeWith(Result.failure(Exception(err))) }
            )
        }
    }
}