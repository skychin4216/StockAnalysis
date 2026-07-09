package com.chin.stockanalysis.strategy.predict

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.ApiProvider
import com.chin.stockanalysis.ai.AiProviderPool
import com.chin.stockanalysis.news.NewsFactorManager
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.models.StrategySignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * ## AI 综合预测引擎
 *
 * 通过 AI（LLM）综合分析多策略打分 + 历史数据特征 + 新闻因子，
 * 动态选择预测方案，输出 3-5 只最可能上涨的股票。
 *
 * ### 綜合分析方案（V2.0 全周期融合）
 * - **技術面分析**: 近5日/10日 OHLCV 序列 + 均線趨勢 + 量價關係
 * - **消息面分析**: NewsFactor 利好利空因子 + 板塊輿情
 * - **大盤環境適應**: 自動檢測 BULLISH/BEARISH/OSCILLATION，動態調整推薦門檻
 * - **市場時機**: 結合大盤趨勢、板塊輪動、主力資金流向
 * AI 不再二選一，而是綜合所有維度做全周期研判。
 *
 * ### 使用方式
 * ```kotlin
 * val engine = AIPredictionEngine(context)
 * val prediction = engine.predict(
 *     strategyResults = results,
 *     selectedDate = "2026-05-30"
 * )
 * // prediction.topPicks → 3-5 只推荐股票
 * // prediction.mode → "COMPOSITE" (綜合方案)
 * ```
 */
class AIPredictionEngine(private val context: Context) {

    companion object {
        private const val TAG = "AIPredictionEngine"
    }

    private val db = StockDatabase.getInstance(context)
    private val newsManager = NewsFactorManager(context)

    /**
     * 板塊上下文：用戶關注 + 回彈板塊 + AI 大年檢測
     */
    data class SectorContext(
        /** 用戶設置的關注板塊關鍵詞列表 */
        val userFocusSectors: List<String> = emptyList(),
        /** 回彈板塊詳情（連熱天數、回調幅度、今日反彈） */
        val bounceSectors: List<BounceSectorInfo> = emptyList(),
        /** AI 檢測的板塊大年結論 */
        val aiYearDetection: String = "未檢測",
        /** 回調加權規則：回調 N 天加 N 分 */
        val pullbackBonusEnabled: Boolean = true
    ) {
        data class BounceSectorInfo(
            val sectorName: String,
            val consecutiveHotDays: Int,
            val recentDropPct: Double,
            val todayBouncePct: Double,
            val bounceScore: Double
        )
    }

    /**
     * AI 预测结果
     */
    data class AIPrediction(
        /** 使用的方案: "COMPOSITE"(綜合方案) 或 "A" 或 "B" */
        val mode: String,
        /** 方案选择的理由 */
        val modeReason: String,
        /** 综合推荐 Top 3-5 只股票 */
        val topPicks: List<AIPick>,
        /** 市场总体判断 */
        val marketOutlook: String,
        /** 风险提示 */
        val riskWarning: String,
        /** 大盤方向: BULLISH/BEARISH/OSCILLATION */
        val marketDirection: String = "UNKNOWN"
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
        onProgress: ((String) -> Unit)? = null,
        useEnhancedAi: Boolean = true,
        marketContext: String = "",
        sectorContext: SectorContext = SectorContext()
    ): AIPrediction? {
        val slot = if (useEnhancedAi) {
            AiProviderPool.acquire(context, callerTag = "AIPredictionEngine", timeoutMs = 120_000L)
        } else {
            com.chin.stockanalysis.ai.SimpleAiProvider.acquire(context)
        }

        if (slot == null) {
            Log.e(TAG, "❌ 无可用 AI Provider")
            return null
        }

        var currentSlot = slot
        var response: String? = null
        var attempts = 0
        val maxAttempts = if (useEnhancedAi) 3 else 2

        try {
            onProgress?.invoke("正在收集历史数据...")
            val candidateStocks = collectCandidateStocks(strategyResults)

            onProgress?.invoke("正在获取多日特征...")
            val multiDayFeatures = buildMultiDayFeatures(candidateStocks, selectedDate)

            onProgress?.invoke("正在获取新闻因子...")
            val newsFactors = newsManager.getActiveFactors(50)

            // 自動檢測大盤環境（如果外部未傳入）
            val effectiveMarketContext = if (marketContext.isNotBlank()) {
                marketContext
            } else {
                detectMarketDirection(selectedDate)
            }

            onProgress?.invoke("正在构建AI提示（含板塊權重）...")
            val prompt = buildPredictionPrompt(
                strategyResults = strategyResults,
                candidateStocks = candidateStocks,
                multiDayFeatures = multiDayFeatures,
                newsFactors = newsFactors,
                selectedDate = selectedDate,
                marketContext = effectiveMarketContext,
                sectorContext = sectorContext
            )

            // 重试：策略模式用 SimpleAiProvider.switchToNext，增强模式用 AiProviderPool 轮换
            while (attempts < maxAttempts && response == null) {
                try {
                    onProgress?.invoke("AI 正在分析预测(${currentSlot!!.configName})...")
                    response = sendSyncRequest(currentSlot!!.provider, prompt)
                } catch (e: java.io.IOException) {
                    attempts++
                    Log.w(TAG, "AI 请求失败[${currentSlot!!.configName}]（第${attempts}次）: ${e.message}")
                    if (attempts < maxAttempts) {
                        val next = if (useEnhancedAi) {
                            AiProviderPool.invalidateHealthCache()
                            AiProviderPool.releaseNonBlocking(currentSlot)
                            AiProviderPool.acquire(context, callerTag = "AIPredictionEngine-retry", timeoutMs = 120_000L)
                        } else {
                            com.chin.stockanalysis.ai.SimpleAiProvider.release()
                            com.chin.stockanalysis.ai.SimpleAiProvider.switchToNext(context)
                        }
                        if (next != null) {
                            currentSlot = next
                            Log.i(TAG, "🔄 切换到: ${next.configName}")
                        } else {
                            Log.e(TAG, "❌ 无可用 Provider，放弃重试")
                            break
                        }
                    }
                } catch (e: Exception) {
                            Log.e(TAG, "AI 预测失败[${currentSlot!!.configName}]: ${e.message}", e)
                    break
                }
            }

            if (response == null) {
                Log.e(TAG, "❌ 所有 Provider 重试失败")
                return null
            }

            val rawPrediction = parsePrediction(response)
            // 後處理：板塊權重加權（回調天數越多加分越多）
            return rawPrediction?.let { applySectorBoost(it, candidateStocks, sectorContext) }

        } catch (e: Exception) {
            Log.e(TAG, "AI 预测失败: ${e.message}", e)
            return null
        } finally {
            if (useEnhancedAi) {
                AiProviderPool.releaseNonBlocking(currentSlot)
            } else {
                com.chin.stockanalysis.ai.SimpleAiProvider.release()
            }
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

    /** 获取候选股票近 N 日的 OHLCV 特徵（批量查詢避免 N+1） */
    private suspend fun buildMultiDayFeatures(
        candidates: List<StockStrategyScore>,
        selectedDate: String,
        days: Int = 5
    ): Map<String, List<DayFeature>> {
        val result = mutableMapOf<String, List<DayFeature>>()
        val availableDates = db.dailySnapshotDao().getAvailableDates(30)
            .filter { it <= selectedDate }
            .take(days)

        if (availableDates.isEmpty()) return result

        // 批量查詢：每個日期一次查全部，然後在內存中過濾候選股
        val candidateCodes = candidates.map { it.stockCode }.toSet()
        val dateToSnaps = mutableMapOf<String, Map<String, DayFeature>>()
        for (date in availableDates) {
            val snaps = db.dailySnapshotDao().getByDate(date)
            dateToSnaps[date] = snaps.filter { it.code in candidateCodes }.associate {
                it.code to DayFeature(
                    date = date, open = it.open, high = it.high, low = it.low,
                    close = it.close, volume = it.volume, changePct = it.changePct
                )
            }
        }

        // 組裝每隻股票的特徵序列
        for (cand in candidates) {
            val features = availableDates.mapNotNull { date ->
                dateToSnaps[date]?.get(cand.stockCode)
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

    /** 自動檢測大盤環境（上證指數MA排列） */
    private suspend fun detectMarketDirection(selectedDate: String): String {
        return try {
            val indexSnaps = db.dailySnapshotDao().getByCode("sh000001", 30).sortedBy { it.date }
                .filter { it.date <= selectedDate }
            if (indexSnaps.size >= 20) {
                val closes = indexSnaps.map { it.close }
                val ma5 = closes.takeLast(5).average()
                val ma10 = closes.takeLast(10).average()
                val ma20 = closes.takeLast(20).average()
                val direction = when {
                    ma5 > ma10 && ma10 > ma20 -> "BULLISH"
                    ma5 < ma10 && ma10 < ma20 -> "BEARISH"
                    else -> "OSCILLATION"
                }
                // 構建環境描述
                val changePct = if (indexSnaps.isNotEmpty()) indexSnaps.last().changePct else 0.0
                val trendDesc = when (direction) {
                    "BULLISH" -> "多頭排列（MA5>MA10>MA20），大盤處於上升趨勢"
                    "BEARISH" -> "空頭排列（MA5<MA10<MA20），大盤處於下降趨勢"
                    else -> "均線糾纏，大盤震蕩格局"
                }
                "大盤方向: $direction | $trendDesc | 上證指數最新日漲跌幅: ${"%.2f".format(changePct)}% | 上證MA5=${"%.2f".format(ma5)} MA10=${"%.2f".format(ma10)} MA20=${"%.2f".format(ma20)}\n" +
                "選股策略建議: ${when(direction) {
                    "BULLISH" -> "可適度進攻，優先選擇多策略命中且放量的領漲股"
                    "BEARISH" -> "防禦為主，優先選擇抗跌+逆勢板塊（醫藥/食品/公用事業），提高入選門檻至70分以上"
                    else -> "高拋低吸，優先選擇震蕩區間底部反彈+有新聞催化的股票"
                }}"
            } else {
                "大盤環境數據不足（<20個交易日），無法判斷方向。建議保守選股。"
            }
        } catch (e: Exception) {
            Log.w(TAG, "檢測大盤環境失敗: ${e.message}")
            "大盤環境檢測失敗，建議保守選股。"
        }
    }

    // ════════════════════════════════════════
    // Prompt 构建
    // ════════════════════════════════════════

    private fun buildPredictionPrompt(
        strategyResults: List<ScreeningResult>,
        candidateStocks: List<StockStrategyScore>,
        multiDayFeatures: Map<String, List<DayFeature>>,
        newsFactors: List<com.chin.stockanalysis.news.NewsFactorEntity>,
        selectedDate: String,
        marketContext: String = "",
        sectorContext: SectorContext = SectorContext()
    ): String {
        val sb = StringBuilder()

        sb.appendLine("你是一个A股量化选股AI助手（V2.0全周期融合版本）。请综合技术面+消息面+大盤环境，预测下一个交易日最可能上涨的3-5只股票。")
        sb.appendLine()
        sb.appendLine("## 分析框架（综合方案，非二选一）")
        sb.appendLine("你必須同時考慮以下三個維度，綜合打分：")
        sb.appendLine("1. **技術面**: 從OHLCV序列中識別趨勢、支撐阻力、量價背離")
        sb.appendLine("2. **消息面**: 從新聞因子中識別催化劑（利好）和風險（利空）")
        sb.appendLine("3. **大盤環境**: 根據大盤方向調整選股策略（見下方大盤環境段落）")
        sb.appendLine()

        // ── 大盤環境分析 ──
        sb.appendLine("## 当前大盤环境（重要参考）")
        if (marketContext.isNotBlank()) {
            sb.appendLine(marketContext)
        } else {
            sb.appendLine("⚠️ 未獲取到大盤環境數據，建議保守選股。")
        }
        sb.appendLine()
        sb.appendLine("### 選股門檻規則（必須遵守）")
        sb.appendLine("- BULLISH（多頭）: composite_score ≥ 60 即可入選")
        sb.appendLine("- OSCILLATION（震蕩）: composite_score ≥ 65")
        sb.appendLine("- BEARISH（空頭）: composite_score ≥ 75，且只推薦防禦板塊（醫藥/食品/銀行/公用事業）或逆勢強勢股")
        sb.appendLine("- 如果大盤環境中標註了 BEARISH，你必須在 risk_warning 中明確提醒「大盤空頭，控制倉位」")
        sb.appendLine()

        // ── 板塊輪動與用戶關注 ──
        sb.appendLine("## 板塊權重與回調加分（重要參考）")
        if (sectorContext.userFocusSectors.isNotEmpty()) {
            sb.appendLine("### 用戶關注板塊（年度熱門，需加權）")
            sb.appendLine("用戶持續追蹤: ${sectorContext.userFocusSectors.joinToString("、")}")
            sb.appendLine("選股規則：命中用戶關注板塊的股票，composite_score 額外 +10~15 分")
            sb.appendLine()
        }
        if (sectorContext.bounceSectors.isNotEmpty()) {
            sb.appendLine("### 回彈板塊（回調後加權：回調1天+1分，2天+2分...）")
            for (b in sectorContext.bounceSectors.take(8)) {
                val dropDays = (-b.recentDropPct / 1.0).toInt().coerceAtMost(5).coerceAtLeast(1)
                sb.appendLine("- ${b.sectorName}: 連熱${b.consecutiveHotDays}天 | 近3天${"%.2f".format(b.recentDropPct)}%（回調${dropDays}天）| 今日反彈${"%.2f".format(b.todayBouncePct)}% | 基礎反彈分${"%.1f".format(b.bounceScore)}")
                sb.appendLine("  → 該板塊股票 composite_score 額外 +$dropDays 分（回調天數加分）")
            }
            sb.appendLine()
        }
        if (sectorContext.aiYearDetection != "未檢測" && sectorContext.aiYearDetection != "檢測失敗") {
            sb.appendLine("### AI 板塊大年檢測")
            sb.appendLine("結論：${sectorContext.aiYearDetection}")
            sb.appendLine("選股規則：順應大年風格的股票給予額外 +5 分")
            sb.appendLine()
        }

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

        // ── 技術面分析數據 ──
        if (multiDayFeatures.isNotEmpty()) {
            sb.appendLine("## 技術面分析數據：近5日 OHLCV 序列")
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

        // ── 消息面分析數據 ──
        if (newsFactors.isNotEmpty()) {
            sb.appendLine("## 消息面分析數據：近期新聞利好利空因子")
            val bullish = newsFactors.filter { it.sentiment > 0 }.take(10)
            val bearish = newsFactors.filter { it.sentiment < 0 }.take(10)
            if (bullish.isNotEmpty()) {
                sb.appendLine("### 利好因子")
                for (f in bullish) {
                    sb.appendLine("- [${f.companyName}] ${f.title}  (强度:${f.impactStrength}) [${f.newsDate}] 标签:${f.tags}")
                }
            }
            if (bearish.isNotEmpty()) {
                sb.appendLine("### 利空因子")
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
        sb.appendLine("  \"selected_mode\": \"COMPOSITE\",")
        sb.appendLine("  \"mode_reason\": \"綜合技術面+消息面分析(20字內)\",")
        sb.appendLine("  \"market_direction\": \"BULLISH/BEARISH/OSCILLATION\",")
        sb.appendLine("  \"market_outlook\": \"市場總體判斷(30字內)\",")
        sb.appendLine("  \"risk_warning\": \"風險提示(30字內，大盤空頭時必須提醒)\",")
        sb.appendLine("  \"top_picks\": [")
        sb.appendLine("    {")
        sb.appendLine("      \"rank\": 1,")
        sb.appendLine("      \"stock_code\": \"sh600519\",")
        sb.appendLine("      \"stock_name\": \"貴州茅台\",")
        sb.appendLine("      \"composite_score\": 85,")
        sb.appendLine("      \"up_probability\": 70,")
        sb.appendLine("      \"reason\": \"綜合理由: 技術面均線金叉+放量突破, 消息面新聞利好催化(30字內)\",")
        sb.appendLine("      \"action\": \"建議逢低建倉，止損位-3%\"")
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
    // 板塊權重後處理
    // ════════════════════════════════════════

    /**
     * 對 AI 預測結果應用板塊權重加權：
     * - 用戶關注板塊：+10~15 分
     * - 回彈板塊：回調 N 天 + N 分（1天+1, 2天+2...最多+5）
     * - 板塊大年順應：+5 分
     */
    private fun applySectorBoost(
        prediction: AIPrediction,
        candidateStocks: List<StockStrategyScore>,
        sectorContext: SectorContext
    ): AIPrediction {
        if (sectorContext.userFocusSectors.isEmpty() && sectorContext.bounceSectors.isEmpty()) {
            return prediction
        }

        val boostedPicks = prediction.topPicks.map { pick ->
            var bonus = 0
            val stockName = pick.stockName

            // 1. 用戶關注板塊加成
            if (sectorContext.userFocusSectors.any {
                    stockName.contains(it) || it.contains(stockName.take(2))
                }) {
                bonus += 12
            }

            // 2. 回彈板塊加成（回調天數越多加分越多）
            val matchedBounce = sectorContext.bounceSectors.find {
                stockName.contains(it.sectorName) || it.sectorName.contains(stockName.take(2))
            }
            matchedBounce?.let { b ->
                val dropDays = (-b.recentDropPct / 1.0).toInt().coerceAtMost(5).coerceAtLeast(1)
                bonus += dropDays  // 回調1天+1, 2天+2, 3天+3...
            }

            // 3. 板塊大年順應加成
            val yearDetection = sectorContext.aiYearDetection
            if (yearDetection.contains("科技") && (stockName.contains("芯") || stockName.contains("半導") || stockName.contains("光") || stockName.contains("AI") || stockName.contains("軟件"))) {
                bonus += 5
            } else if (yearDetection.contains("主板") && (stockName.contains("銀行") || stockName.contains("保險") || stockName.contains("地產") || stockName.contains("煤炭") || stockName.contains("鋼鐵"))) {
                bonus += 5
            } else if (yearDetection.contains("成長") && (stockName.contains("新能") || stockName.contains("生物") || stockName.contains("醫藥") || stockName.contains("創新"))) {
                bonus += 5
            }

            if (bonus > 0) {
                pick.copy(
                    compositeScore = (pick.compositeScore + bonus).coerceAtMost(100),
                    upProbability = (pick.upProbability + bonus / 2).coerceAtMost(95),
                    reason = pick.reason + " [板塊加權+${bonus}分]"
                )
            } else pick
        }.sortedByDescending { it.compositeScore }
            // 重新排名
            .mapIndexed { index, pick -> pick.copy(rank = index + 1) }

        return prediction.copy(topPicks = boostedPicks)
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
                mode = obj.optString("selected_mode", "COMPOSITE"),
                modeReason = obj.optString("mode_reason", ""),
                topPicks = picks.sortedBy { it.rank },
                marketOutlook = obj.optString("market_outlook", ""),
                riskWarning = obj.optString("risk_warning", "投資有風險，入市需謹慎"),
                marketDirection = obj.optString("market_direction", "UNKNOWN")
            )
        } catch (e: Exception) {
            Log.w(TAG, "解析AI预测失败: ${e.message}")
            null
        }
    }

    /** 發送同步請求（30s 超時，最多 2 次重試，DNS 錯誤快速跳過） */
    private suspend fun sendSyncRequest(provider: ApiProvider, prompt: String): String {
        var lastErr: Exception? = null
        repeat(2) { attempt ->
            try {
                return withTimeoutOrNull(60_000L) {
                    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                        provider.sendMessageStream(
                            messages = emptyList(),
                            systemPrompt = prompt,
                            onSuccess = {},
                            onComplete = { full -> cont.resumeWith(Result.success(full)) },
                            onError = { err -> cont.resumeWith(Result.failure(Exception(err))) }
                        )
                    }
                } ?: throw java.io.IOException("AI 请求超时（60秒）")
            } catch (e: Exception) {
                lastErr = e
                // DNS 錯誤不重試，直接拋出讓外層換 provider
                if (e.message?.contains("Unable to resolve host") == true ||
                    e.message?.contains("UnknownHostException") == true ||
                    e.message?.contains("No address associated") == true) {
                    Log.w(TAG, "DNS 解析失敗，跳過此 Provider: ${e.message}")
                    throw e
                }
                Log.w(TAG, "AI 请求失败（第${attempt+1}/2次）: ${e.message}")
                if (attempt < 1) {
                    kotlinx.coroutines.delay(2000L)  // 2秒后再试
                }
            }
        }
        throw lastErr ?: Exception("AI 请求失败，已重试2次")
    }
}