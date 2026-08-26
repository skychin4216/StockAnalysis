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
 * ### 综合分析方案（V2.0 全周期融合）
 * - **技术面分析**: 近5日/10日 OHLCV 序列 + 均线趋势 + 量价关系
 * - **消息面分析**: NewsFactor 利好利空因子 + 板块舆情
 * - **大盘环境适应**: 自动检测 BULLISH/BEARISH/OSCILLATION，动态调整推荐门槛
 * - **市场时机**: 结合大盘趋势、板块轮动、主力资金流向
 * AI 不再二选一，而是综合所有维度做全周期研判。
 *
 * ### 使用方式
 * ```kotlin
 * val engine = AIPredictionEngine(context)
 * val prediction = engine.predict(
 *     strategyResults = results,
 *     selectedDate = "2026-05-30"
 * )
 * // prediction.topPicks → 3-5 只推荐股票
 * // prediction.mode → "COMPOSITE" (综合方案)
 * ```
 */
class AIPredictionEngine(private val context: Context) {

    companion object {
        private const val TAG = "AIPredictionEngine"
    }

    private val db = StockDatabase.getInstance(context)
    private val newsManager = NewsFactorManager(context)

    /**
     * 板块上下文：用户关注 + 回弹板块 + AI 大年检测
     */
    data class SectorContext(
        /** 用户设置的关注板块关键词列表 */
        val userFocusSectors: List<String> = emptyList(),
        /** 今日热门板块（动态获取） */
        val todayHotSectors: List<String> = emptyList(),
        /** 回弹板块详情（连热天数、回调幅度、今日反弹） */
        val bounceSectors: List<BounceSectorInfo> = emptyList(),
        /** AI 检测的板块大年结论 */
        val aiYearDetection: String = "未检测",
        /** 回调加权规则：回调 N 天加 N 分 */
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
        /** 使用的方案: "COMPOSITE"(综合方案) 或 "A" 或 "B" */
        val mode: String,
        /** 方案选择的理由 */
        val modeReason: String,
        /** 综合推荐 Top 3-5 只股票 */
        val topPicks: List<AIPick>,
        /** 市场总体判断 */
        val marketOutlook: String,
        /** 风险提示 */
        val riskWarning: String,
        /** 大盘方向: BULLISH/BEARISH/OSCILLATION */
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
     * @param includeExtendedSources 是否启用扩展候选来源（AI量化选股模式）：
     *        在策略结果基础上补充 龙头/AI精选/自选/热门板块前10 候选，
     *        并按「主板 5 只 + 科创/创业合计 5 只」配额推荐。
     */
    suspend fun predict(
        strategyResults: List<ScreeningResult>,
        selectedDate: String,
        onProgress: ((String) -> Unit)? = null,
        useEnhancedAi: Boolean = true,
        marketContext: String = "",
        sectorContext: SectorContext = SectorContext(),
        includeExtendedSources: Boolean = false
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
            val candidateStocks = collectCandidateStocks(strategyResults, selectedDate, includeExtendedSources)

            onProgress?.invoke("正在获取多日特征...")
            val multiDayFeatures = buildMultiDayFeatures(candidateStocks, selectedDate)

            onProgress?.invoke("正在获取新闻因子...")
            val newsFactors = newsManager.getActiveFactors(50)

            // 计算新闻因子得分
            onProgress?.invoke("正在计算新闻评分...")
            val candidatePairs = candidateStocks.map { it.stockCode to it.stockName }
            val newsScores = NewsScoreCalculator.score(candidatePairs, newsFactors, selectedDate)
            val newsScoreMap = newsScores.associate { it.stockCode to it }

            // 计算混合评分（策略分 + 新闻分）
            val marketDir = if (marketContext.contains("BULLISH", ignoreCase = true)) "BULLISH"
                else if (marketContext.contains("BEARISH", ignoreCase = true)) "BEARISH"
                else "OSCILLATION"
            val (strategyW, newsW) = when (marketDir) {
                "BULLISH" -> 0.5 to 0.5   // 进攻行情，消息面催化更重要
                "BEARISH" -> 0.7 to 0.3   // 防御行情，技术面优先
                else -> 0.6 to 0.4        // 震荡均衡
            }

            // 策略分归一化
            val maxStrength = candidateStocks.maxOfOrNull { it.totalStrength } ?: 1
            val minStrength = candidateStocks.minOfOrNull { it.totalStrength } ?: 0
            val strengthRange = (maxStrength - minStrength).coerceAtLeast(1)

            val hybridScores = candidateStocks.associate { cand ->
                val normStrategy = ((cand.totalStrength - minStrength) / strengthRange * 100).toInt().coerceIn(0, 100)
                val normNews = newsScoreMap[cand.stockCode]?.normalizedScore ?: 50
                val hybrid = (strategyW * normStrategy + newsW * normNews).toInt().coerceIn(0, 100)
                cand.stockCode to hybrid
            }

            // 自动检测大盘环境（如果外部未传入）
            val effectiveMarketContext = if (marketContext.isNotBlank()) {
                marketContext
            } else {
                detectMarketDirection(selectedDate)
            }

            onProgress?.invoke("正在构建AI提示（含新闻权重）...")
            val prompt = buildPredictionPrompt(
                strategyResults = strategyResults,
                candidateStocks = candidateStocks,
                multiDayFeatures = multiDayFeatures,
                newsFactors = newsFactors,
                selectedDate = selectedDate,
                marketContext = effectiveMarketContext,
                sectorContext = sectorContext,
                newsScoreMap = newsScoreMap,
                hybridScores = hybridScores,
                strategyWeight = strategyW,
                newsWeight = newsW,
                requireBoardBalance = includeExtendedSources
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
            // 后处理：板块权重加权（回调天数越多加分越多）+ 新闻校验
            val boosted = rawPrediction?.let { applySectorBoost(it, candidateStocks, sectorContext, newsScoreMap) }
            // AI量化选股模式：主板/双创配额控制（主板≤5、科创/创业合计≤5）
            return if (includeExtendedSources) boosted?.let { enforceBoardQuota(it) } else boosted

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

    /**
     * 从各策略结果中收集所有候选股票（去重）。
     *
     * 扩展模式（AI量化选股，includeExtendedSources=true）下，额外补充候选来源：
     * 龙头（产业主线+每日龙头）、AI精选、自选、热门板块/周期板块/龙头板块动量前10。
     */
    private suspend fun collectCandidateStocks(
        results: List<ScreeningResult>,
        selectedDate: String,
        includeExtendedSources: Boolean = false
    ): List<StockStrategyScore> {
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

        // AI量化选股：补充 龙头/AI精选/自选/热门板块前10 候选
        if (includeExtendedSources) {
            val date = selectedDate.ifBlank { java.time.LocalDate.now().toString() }
            for ((code, name) in collectExtendedCandidateSources(date)) {
                if (code.isBlank()) continue
                map.getOrPut(code) {
                    StockStrategyScore(code, name, mutableListOf(), 0, 0.0)
                }
            }
        }

        // 扩展模式放宽候选数量（覆盖板块/龙头来源），普通模式保持 Top 20
        return map.values
            .sortedByDescending { it.totalStrength }
            .take(if (includeExtendedSources) 80 else 20)
    }

    /**
     * 收集扩展候选来源的股票（code → name）：
     * 1. 龙头：产业主线龙头 + 每日龙头（子板块 Top3）
     * 2. AI精选：ai_selected_stock 最近入库记录
     * 3. 自选：user_watchlist
     * 4. 热门板块/周期板块/龙头板块 动量 Top10 的成分股
     */
    private suspend fun collectExtendedCandidateSources(date: String): Map<String, String> {
        val result = linkedMapOf<String, String>()

        // 1. 龙头：产业主线龙头
        try {
            for (code in com.chin.stockanalysis.strategy.data.LeaderStockPool.getMainlineCodes(context)) {
                if (code.isNotBlank()) result.putIfAbsent(code, "")
            }
        } catch (_: Exception) {}
        // 1.1 每日龙头（子板块 Top3）
        try {
            val pool = com.chin.stockanalysis.strategy.data.LeaderStockPool(context)
            val leaders = pool.getDailyLeaders(date)
            for (l in leaders) result.putIfAbsent(l.code, l.name)
        } catch (_: Exception) {}

        // 2. AI精选（最近入库）
        try {
            for (p in db.aiSelectedStockDao().getAll().take(80)) {
                result.putIfAbsent(p.stockCode, p.stockName)
            }
        } catch (_: Exception) {}

        // 3. 自选
        try {
            for (w in db.userWatchlistDao().getAll().take(120)) {
                result.putIfAbsent(w.stockCode, w.stockName)
            }
        } catch (_: Exception) {}

        // 4. 热门板块/周期板块/龙头板块 动量 Top10 的成分股
        try {
            val pool = com.chin.stockanalysis.strategy.data.LeaderStockPool(context)
            val topSectors = pool.getTopSectorsByMomentum(date, rankDays = 5, topN = 10)
            val hotNames = topSectors.map { it.first }.toSet()
            if (hotNames.isNotEmpty()) {
                for (cfg in com.chin.stockanalysis.strategy.data.LeaderStockPool.loadConfigs(context)) {
                    if (cfg.name in hotNames) {
                        for (sub in cfg.subSectors) {
                            for (code in sub.stocks) {
                                if (code.isNotBlank()) result.putIfAbsent(code, "")
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 名称兜底：从当日快照补全缺失名称
        if (result.values.any { it.isBlank() }) {
            try {
                val snapMap = db.dailySnapshotDao().getByDate(date).associate { it.code to it.name }
                for ((code, name) in result) {
                    if (name.isBlank()) {
                        val n = snapMap[code]
                        if (!n.isNullOrBlank()) result[code] = n
                    }
                }
            } catch (_: Exception) {}
        }

        return result
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

    /** 获取候选股票近 N 日的 OHLCV 特征（批量查询避免 N+1） */
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

        // 批量查询：每个日期一次查全部，然后在内存中过滤候选股
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

        // 组装每只股票的特征序列
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

    /** 自动检测大盘环境（上证指数MA排列） */
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
                // 构建环境描述
                val changePct = if (indexSnaps.isNotEmpty()) indexSnaps.last().changePct else 0.0
                val trendDesc = when (direction) {
                    "BULLISH" -> "多头排列（MA5>MA10>MA20），大盘处于上升趋势"
                    "BEARISH" -> "空头排列（MA5<MA10<MA20），大盘处于下降趋势"
                    else -> "均线纠缠，大盘震荡格局"
                }
                "大盘方向: $direction | $trendDesc | 上证指数最新日涨跌幅: ${"%.2f".format(changePct)}% | 上证MA5=${"%.2f".format(ma5)} MA10=${"%.2f".format(ma10)} MA20=${"%.2f".format(ma20)}\n" +
                "选股策略建议: ${when(direction) {
                    "BULLISH" -> "可适度进攻，优先选择多策略命中且放量的领涨股"
                    "BEARISH" -> "防御为主，优先选择抗跌+逆势板块（医药/食品/公用事业），提高入选门槛至70分以上"
                    else -> "高抛低吸，优先选择震荡区间底部反弹+有新闻催化的股票"
                }}"
            } else {
                "大盘环境数据不足（<20个交易日），无法判断方向。建议保守选股。"
            }
        } catch (e: Exception) {
            Log.w(TAG, "检测大盘环境失败: ${e.message}")
            "大盘环境检测失败，建议保守选股。"
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
        sectorContext: SectorContext = SectorContext(),
        newsScoreMap: Map<String, NewsScoreCalculator.StockNewsScore> = emptyMap(),
        hybridScores: Map<String, Int> = emptyMap(),
        strategyWeight: Double = 0.6,
        newsWeight: Double = 0.4,
        requireBoardBalance: Boolean = false
    ): String {
        val sb = StringBuilder()

        sb.appendLine("你是一个A股量化选股AI助手（V2.0全周期融合版本）。请综合技术面+消息面+大盘环境，预测下一个交易日最可能上涨的股票。")
        sb.appendLine()
        if (requireBoardBalance) {
            sb.appendLine("## 推荐数量要求（重要！必须遵守）")
            sb.appendLine("- 主板（60/00开头）推荐 **5 只**（rank 1-5）")
            sb.appendLine("- 科创（688）/创业（300/301）合计推荐 **5 只**（rank 6-10）")
            sb.appendLine("- 共推荐 **10 只**，两板均衡配置，不要全部集中在同一板块")
            sb.appendLine("- 若某类候选不足，则按实际数量推荐，但必须在 top_picks 中优先覆盖强逻辑股票")
            sb.appendLine()
        } else {
            sb.appendLine("请预测下一个交易日最可能上涨的3-5只股票。")
            sb.appendLine()
        }
        sb.appendLine("## 分析框架（综合方案，非二选一）")
        sb.appendLine("你必须同时考虑以下三个维度，综合打分：")
        sb.appendLine("1. **技术面**: 从OHLCV序列中识别趋势、支撑阻力、量价背离")
        sb.appendLine("2. **消息面**: 从新闻因子中识别催化剂（利好）和风险（利空）")
        sb.appendLine("3. **大盘环境**: 根据大盘方向调整选股策略（见下方大盘环境段落）")
        sb.appendLine()
        sb.appendLine("### 新闻因子评分规则（重要！必须遵守）")
        sb.appendLine("候选表中的「新闻分」是量化计算结果（0-100），你**必须**以此为基础：")
        sb.appendLine("- 新闻分 ≥ 70：强消息催化，composite_score 应在混合分基础上 +5~10")
        sb.appendLine("- 新闻分 40-70：一般消息面，composite_score 以混合分为基准 ±5")
        sb.appendLine("- 新闻分 < 30 且有利空新闻：composite_score 应在混合分基础上 -5~10")
        sb.appendLine("- **不得忽略新闻分**：如果某只股票新闻分 > 70 但你给了低 composite_score，必须在 reason 中解释原因")
        sb.appendLine("- 当前权重配比：策略技术分占 ${"%.0f".format(strategyWeight * 100)}%，消息面占 ${"%.0f".format(newsWeight * 100)}%")
        sb.appendLine()

        // ── 大盘环境分析 ──
        sb.appendLine("## 当前大盘环境（重要参考）")
        if (marketContext.isNotBlank()) {
            sb.appendLine(marketContext)
        } else {
            sb.appendLine("⚠️ 未获取到大盘环境数据，建议保守选股。")
        }
        sb.appendLine()
        sb.appendLine("### 选股门槛规则（必须遵守）")
        sb.appendLine("- BULLISH（多头）: composite_score ≥ 60 即可入选")
        sb.appendLine("- OSCILLATION（震荡）: composite_score ≥ 65")
        sb.appendLine("- BEARISH（空头）: composite_score ≥ 75，且只推荐防御板块（医药/食品/银行/公用事业）或逆势强势股")
        sb.appendLine("- 如果大盘环境中标注了 BEARISH，你必须在 risk_warning 中明确提醒「大盘空头，控制仓位」")
        sb.appendLine()

        // ── 板块轮动与用户关注 ──
        sb.appendLine("## 板块权重与回调加分（重要参考）")
        if (sectorContext.userFocusSectors.isNotEmpty()) {
            sb.appendLine("### 用户关注板块（年度热门，需加权）")
            sb.appendLine("用户持续追踪: ${sectorContext.userFocusSectors.joinToString("、")}")
            sb.appendLine("选股规则：命中用户关注板块的股票，composite_score 额外 +10~15 分")
            sb.appendLine()
        }
        if (sectorContext.bounceSectors.isNotEmpty()) {
            sb.appendLine("### 回弹板块（回调后加权：回调1天+1分，2天+2分...）")
            for (b in sectorContext.bounceSectors.take(8)) {
                val dropDays = (-b.recentDropPct / 1.0).toInt().coerceAtMost(5).coerceAtLeast(1)
                sb.appendLine("- ${b.sectorName}: 连热${b.consecutiveHotDays}天 | 近3天${"%.2f".format(b.recentDropPct)}%（回调${dropDays}天）| 今日反弹${"%.2f".format(b.todayBouncePct)}% | 基础反弹分${"%.1f".format(b.bounceScore)}")
                sb.appendLine("  → 该板块股票 composite_score 额外 +$dropDays 分（回调天数加分）")
            }
            sb.appendLine()
        }
        if (sectorContext.aiYearDetection != "未检测" && sectorContext.aiYearDetection != "检测失败") {
            sb.appendLine("### AI 板块大年检测")
            sb.appendLine("结论：${sectorContext.aiYearDetection}")
            sb.appendLine("选股规则：顺应大年风格的股票给予额外 +5 分")
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
        sb.appendLine("| 代码 | 名称 | 策略分 | 新闻分 | 混合分 | 命中策略数 | 总强度 | 涨跌 | 新闻匹配 |")
        sb.appendLine("|------|------|--------|--------|--------|-----------|--------|------|---------|")
        for (c in candidateStocks.take(15)) {
            val newsScore = newsScoreMap[c.stockCode]
            val newsScoreStr = newsScore?.normalizedScore?.toString() ?: "50"
            val hybridStr = hybridScores[c.stockCode]?.toString() ?: "-"
            val newsMatchStr = newsScore?.matchSummary?.take(30) ?: "无"
            sb.appendLine("| ${c.stockCode.takeLast(6)} | ${c.stockName} | - | $newsScoreStr | $hybridStr | ${c.strategyScores.size} | ${c.totalStrength} | ${"%.2f".format(c.changePercent)}% | $newsMatchStr |")
        }
        sb.appendLine()
        sb.appendLine("**重要**: composite_score 请以「混合分」为基准，结合新闻分做 ±10 分调整。")
        sb.appendLine()

        // ── 技术面分析数据 ──
        if (multiDayFeatures.isNotEmpty()) {
            sb.appendLine("## 技术面分析数据：近5日 OHLCV 序列")
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

        // ── 消息面分析数据 ──
        if (newsFactors.isNotEmpty()) {
            sb.appendLine("## 消息面分析数据：近期新闻利好利空因子")
            val bullish = newsFactors.filter { it.sentiment > 0 }.take(10)
            val bearish = newsFactors.filter { it.sentiment < 0 }.take(10)
            if (bullish.isNotEmpty()) {
                sb.appendLine("### 利好因子")
                for (f in bullish) {
                    sb.appendLine("- [${f.companyName}] ${f.title}  (强度:${f.impactStrength}) [${f.newsDate}] 标签:${f.tags} 板块:${f.sector}")
                }
            }
            if (bearish.isNotEmpty()) {
                sb.appendLine("### 利空因子")
                for (f in bearish) {
                    sb.appendLine("- [${f.companyName}] ${f.title}  (强度:${f.impactStrength}) [${f.newsDate}] 标签:${f.tags} 板块:${f.sector}")
                }
            }
            // 每只候选股的新闻匹配摘要
            if (newsScoreMap.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("### 候选股新闻匹配摘要")
                for ((code, ns) in newsScoreMap.entries.sortedByDescending { it.value.normalizedScore }.take(10)) {
                    if (ns.matchedFactorCount > 0) {
                        sb.appendLine("- ${ns.stockName}(${code.takeLast(6)}): 新闻分=${ns.normalizedScore} | 利好${ns.bullishCount}条 利空${ns.bearishCount}条 | ${ns.matchSummary}")
                    }
                }
            }
            sb.appendLine()
        }

        // ── 输出格式要求 ──
        sb.appendLine("## 请按以下 JSON 格式输出（仅输出 JSON，不要其他文字）")
        sb.appendLine("```json")
        sb.appendLine("{")
        sb.appendLine("  \"selected_mode\": \"COMPOSITE\",")
        sb.appendLine("  \"mode_reason\": \"综合技术面+消息面分析(20字内)\",")
        sb.appendLine("  \"market_direction\": \"BULLISH/BEARISH/OSCILLATION\",")
        sb.appendLine("  \"market_outlook\": \"市场总体判断(30字内)\",")
        sb.appendLine("  \"risk_warning\": \"风险提示(30字内，大盘空头时必须提醒)\",")
        sb.appendLine("  \"top_picks\": [")
        sb.appendLine("    {")
        sb.appendLine("      \"rank\": 1,")
        sb.appendLine("      \"stock_code\": \"sh600519\",")
        sb.appendLine("      \"stock_name\": \"贵州茅台\",")
        sb.appendLine("      \"composite_score\": 85,")
        sb.appendLine("      \"up_probability\": 70,")
        sb.appendLine("      \"reason\": \"综合理由: 技术面均线金叉+放量突破, 消息面新闻利好催化(新闻分:XX), 混合分:XX(30字内)\",")
        sb.appendLine("      \"action\": \"建议逢低建仓，止损位-3%\"")
        sb.appendLine("    }")
        if (requireBoardBalance) {
            sb.appendLine("    ,{ \"rank\": 2, \"stock_code\": \"sz000001\", \"stock_name\": \"平安银行\", \"composite_score\": 82, \"up_probability\": 66, \"reason\": \"...\", \"action\": \"建议逢低建仓\" }")
            sb.appendLine("    // ... 主板共 5 只（rank 1-5），科创/创业共 5 只（rank 6-10），共 10 只")
        } else {
            sb.appendLine("    // ... 共 3-5 只")
        }
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
    // 板块权重后处理
    // ════════════════════════════════════════

    /**
     * 对 AI 预测结果应用板块权重加权：
     * - 用户关注板块：+10~15 分
     * - 回弹板块：回调 N 天 + N 分（1天+1, 2天+2...最多+5）
     * - 板块大年顺应：+5 分
     * - 新闻校验：高分新闻未被AI充分反映 → +5；利空新闻 → -5
     */
    private fun applySectorBoost(
        prediction: AIPrediction,
        @Suppress("UNUSED_PARAMETER") candidateStocks: List<StockStrategyScore>,
        sectorContext: SectorContext,
        newsScoreMap: Map<String, NewsScoreCalculator.StockNewsScore> = emptyMap()
    ): AIPrediction {
        if (sectorContext.userFocusSectors.isEmpty() && sectorContext.bounceSectors.isEmpty() && newsScoreMap.isEmpty()) {
            return prediction
        }

        val boostedPicks = prediction.topPicks.map { pick ->
            var bonus = 0
            val stockName = pick.stockName
            val stockCode = pick.stockCode

            // 1. 用户关注板块加成
            if (sectorContext.userFocusSectors.any {
                    stockName.contains(it) || it.contains(stockName.take(2))
                }) {
                bonus += 12
            }

            // 2. 回弹板块加成（回调天数越多加分越多）
            val matchedBounce = sectorContext.bounceSectors.find {
                stockName.contains(it.sectorName) || it.sectorName.contains(stockName.take(2))
            }
            matchedBounce?.let { b ->
                val dropDays = (-b.recentDropPct / 1.0).toInt().coerceAtMost(5).coerceAtLeast(1)
                bonus += dropDays  // 回调1天+1, 2天+2, 3天+3...
            }

            // 3. 板块大年顺应加成
            val yearDetection = sectorContext.aiYearDetection
            if (yearDetection.contains("科技") && (stockName.contains("芯") || stockName.contains("半导") || stockName.contains("光") || stockName.contains("AI") || stockName.contains("软件"))) {
                bonus += 5
            } else if (yearDetection.contains("主板") && (stockName.contains("银行") || stockName.contains("保险") || stockName.contains("地产") || stockName.contains("煤炭") || stockName.contains("钢铁"))) {
                bonus += 5
            } else if (yearDetection.contains("成长") && (stockName.contains("新能") || stockName.contains("生物") || stockName.contains("医药") || stockName.contains("创新"))) {
                bonus += 5
            }

            // 4. 新闻校验：确保AI没有忽略强新闻信号
            val newsScore = newsScoreMap[stockCode]
            if (newsScore != null) {
                if (newsScore.normalizedScore >= 70 && bonus < 5) {
                    // 强新闻催化但板块加分不足，额外补充
                    bonus += 5
                } else if (newsScore.normalizedScore < 20 && newsScore.bearishCount > 0) {
                    // 有利空新闻且新闻分很低，惩罚
                    bonus -= 5
                }
            }

            if (bonus != 0) {
                val newScore = (pick.compositeScore + bonus).coerceIn(0, 100)
                val bonusDesc = if (bonus > 0) "板块加权+${bonus}分" else "新闻校验${bonus}分"
                pick.copy(
                    compositeScore = newScore,
                    upProbability = (pick.upProbability + bonus / 2).coerceIn(5, 95),
                    reason = pick.reason + " [$bonusDesc]"
                )
            } else pick
        }.sortedByDescending { it.compositeScore }
            // 重新排名
            .mapIndexed { index, pick -> pick.copy(rank = index + 1) }

        return prediction.copy(topPicks = boostedPicks)
    }

    /**
     * AI量化选股模式下的板块配额控制：
     * 主板（60/00 开头）最多保留 5 只；科创（688）/创业（300/301）合计最多保留 5 只。
     * 在 AI 排序基础上按配额截断，保持推荐逻辑优先，再按原 rank 重排。
     */
    private fun enforceBoardQuota(prediction: AIPrediction): AIPrediction {
        if (prediction.topPicks.isEmpty()) return prediction
        val mainBoard = mutableListOf<AIPick>()
        val growthBoard = mutableListOf<AIPick>()
        for (pick in prediction.topPicks) {
            val code = pick.stockCode.takeLast(6)
            val isMain = code.startsWith("60") || code.startsWith("00")
            if (isMain) mainBoard.add(pick) else growthBoard.add(pick)
        }
        val picks = (mainBoard.take(5) + growthBoard.take(5))
            .sortedBy { it.rank }
            .mapIndexed { index, pick -> pick.copy(rank = index + 1) }
        return prediction.copy(topPicks = picks)
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
                riskWarning = obj.optString("risk_warning", "投资有风险，入市需谨慎"),
                marketDirection = obj.optString("market_direction", "UNKNOWN")
            )
        } catch (e: Exception) {
            Log.w(TAG, "解析AI预测失败: ${e.message}")
            null
        }
    }

    /** 发送同步请求（30s 超时，最多 2 次重试，DNS 错误快速跳过） */
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
                // DNS 错误不重试，直接抛出让外层换 provider
                if (e.message?.contains("Unable to resolve host") == true ||
                    e.message?.contains("UnknownHostException") == true ||
                    e.message?.contains("No address associated") == true) {
                    Log.w(TAG, "DNS 解析失败，跳过此 Provider: ${e.message}")
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