package com.chin.stockanalysis.agent.stock

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.agent.framework.*
import com.chin.stockanalysis.ai.AiProviderPool
import com.chin.stockanalysis.ai.AiProviderSelector
import com.chin.stockanalysis.stock.data.StockDataFacade
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ## 股票分析 Agent
 *
 * 对单只股票进行多维度深度分析，给出买卖建议。
 *
 * 优化：通过 StockDataFacade 统一获取数据，仅做 1 次 LLM 调用，
 * 避免多次 plan-and-execute 超时。
 */
class StockAnalysisAgent(context: Context) : AgentBase(
    id = "stock_analysis",
    name = "股票分析 Agent",
    description = "对单只股票进行多维度深度分析，给出买卖建议",
    context = context
) {
    companion object {
        private const val TAG = "StockAnalysisAgent"
        /** 单次 LLM 调用超时（推理模型较慢） */
        private const val ANALYSIS_LLM_TIMEOUT_MS = 120_000L

        /**
         * 正规化股票代码：603986 → sh603986
         * Sina API、Tencent API、DB 都需要前缀格式
         */
        fun normalizeStockCode(code: String): String {
            val trimmed = code.trim()
            // 已有前缀的格式直接返回
            if (trimmed.length > 6 && (trimmed.startsWith("sh") || trimmed.startsWith("sz") || trimmed.startsWith("bj"))) {
                return trimmed
            }
            // 6位纯数字代码，根据首字添加对应前缀
            if (trimmed.length == 6 && trimmed.all { it.isDigit() }) {
                return when (trimmed[0]) {
                    '6', '9' -> "sh$trimmed"
                    '0', '3' -> "sz$trimmed"
                    '4', '8' -> "bj$trimmed"
                    else -> "sh$trimmed" // fallback
                }
            }
            return trimmed
        }
    }

    init {
        // 注册工具：让 LLM 主动获取补充数据
        registerTool(StockQueryTool(context))
        registerTool(SectorQueryTool(context))
        registerTool(MarketBriefTool(context))
    }

    override fun buildSystemPrompt(): String = """
        你是一位专业的 A 股股票分析师，擅长多维度深度分析单只股票。

        ## 核心规则（必须遵守）
        - 你只能使用下方数据中提供的股票名称、代码和价格进行分析
        - 绝对禁止使用你的训练数据中的旧价格、旧信息
        - 如果数据中显示股票名称是「兆易创新」，你必须分析兆易创新，不能替换为其他股票
        - 所有价格、涨跌幅必须以「实时行情」为准，忽略历史数据中的过期价格
        - 对于未提供的数据（如营收、PE、融资余额等），必须标注「数据不可用」，禁止编造
        - 如果某项分析所需数据缺失，直接跳过该维度，不要使用训练数据推断
        - 永远不要说「据我所知」「根据我的训练数据」等暗示来自旧数据的表述

        ## 分析框架
        1. 技术面（必须严格按以下步骤分析）：
           a) 大盘环境判断：结合下方注入的大盘趋势和热点板块，确定当前市场处于上升/下降/震荡趋势
              - 「形态生效的土壤」：上升三法等持续形态仅在明确趋势中有效，震荡市中形态成功率极低
              - 结合热点板块：个股属于当前市场热点板块时，形态突破更容易获得市场共鸣
           b) K线形态识别（必须逐一检查，发现时在 reasoning 中明确指出）：
              * 看涨反转：锤子线、启明星、刺透形态、看涨吞没（阳包阴）、上升三法、仙人指路、双锤打击
              * 看跌反转：射击之星、黄昏之星、看跌吞没（阴包阳）、高位实体吞噬、涨势尽头线、高位掉线、顶部阴包阳
              * 多K线组合：红三兵、三只乌鸦、塔形反转、弃婴/岛形反转
              * 中性/观望：十字星、纺锤线
           c) 量价配合（核心验证标准）：
              - 启动信号（第一根K线）：必须放量，显示强劲突破动能（成交量 > 前5日均量1.5倍）
              - 整理阶段（中间小K线）：成交量应逐步萎缩，代表抛压枯竭（洗盘而非出货）
              - 确认信号（最后一根K线）：必须再次放量，且最好超过第一根K线的量能
              - 无量形态多为诱多或诱空，需特别警惕
           d) 图表形态：头肩顶/底、双顶/双底（M顶/W底）、杯柄形态、旗形整理、三角形收斮
           e) 背驰信号：价格创新高/新低但动量指标（RSI/MACD）未能确认 → 可能即将反转
           f) 均线排列：多头/空头排列、金叉/死叉
           g) 形态确认标准：必须结合前后K线、成交量、位置（高位/低位/盘整）综合判断，不可单凭一根K线下结论
           h) 失败场景警示：震荡市中形态易失败、消息驱动个股K线易被扭曲、高位滞涨的「上升三法」可能是诱多陷阱、小盘股中形态可能被主力刻意画出
        2. 基本面：主营业务、产业链地位
        3. 资金面：主力流向、换手率

        ## 输出格式（严格 JSON）
        ```json
        {
          "stock_name": "数据中提供的股票名称",
          "stock_code": "数据中提供的股票代码",
          "current_price": "实时行情中的当前价",
          "overall_score": 75,
          "recommendation": "BUY|HOLD|SELL|WATCH",
          "confidence": "HIGH|MEDIUM|LOW",
          "technical_score": 80,
          "fundamental_score": 85,
          "fund_flow_score": 70,
          "reasoning": "基于提供的实时数据进行分析",
          "risk_factors": ["风险1", "风险2"],
          "target_price": "基于实时价格推算的目标价",
          "stop_loss": "基于实时价格推算的止损价"
        }
        ```
        请只输出 JSON，不要有其他文字。如果无法计算具体分数，给出合理估算。
    """.trimIndent()

    /**
     * 分析股票
     *
     * 优化策略：通过 StockDataFacade 统一获取数据 → 1 次 LLM 调用生成结果
     */
    suspend fun analyze(
        stockCode: String,
        stockName: String? = null,
        onProgress: ((String) -> Unit)? = null
    ): StockAnalysisResult {
        val normalizedCode = normalizeStockCode(stockCode)
        onProgress?.invoke("🔍 正在收集 ${stockName ?: normalizedCode} 数据...")

        // Step 1: 使用 StockDataFacade 一键获取所有数据
        val data = StockDataFacade.getInstance(context)
            .getAnalysisData(normalizedCode)

        val resolvedName = stockName ?: data.quote?.name ?: data.fundamental.name ?: normalizedCode

        onProgress?.invoke("🤖 AI 分析 $resolvedName 中...")

        // Step 2: 构建 prompt（使用完整的 system prompt + 数据）
        // 异步获取大盘环境（失败不阻塞分析）
        val marketReportStr = try {
            val report = com.chin.stockanalysis.strategy.market.MarketAnalyzer.analyze(
                context, emptyList()
            )
            buildString {
                appendLine("## 大盘环境（实时分析）")
                appendLine("- 大盘趋势: ${report.trend.direction}（强度${report.trend.strength}/100）")
                appendLine("- 趋势描述: ${report.trend.description}")
                appendLine("- 卖出信号: ${report.sellType.sellType}")
                if (report.sectorAdvice.recommendedSectors.isNotEmpty()) {
                    val sectorNames = report.sectorAdvice.recommendedSectors.map { it.sectorName }.joinToString(",")
                    appendLine("- 板块建议: ${report.sectorAdvice.marketStyle}，推荐: $sectorNames")
                }
                appendLine("- 大盘摘要: ${report.summary}")
            }
        } catch (_: Exception) { "" }

        val promptData = buildAnalysisPrompt(data, resolvedName, marketReportStr)
        val systemPrompt = buildSystemPrompt() + "\n\n## 待分析股票数据\n\n" + promptData
        val prompt = "请分析股票 $resolvedName（$normalizedCode），严格按上方 JSON 格式输出。"

        // Step 3: 单次 LLM 调用，60s 超时
        val llmOutput = try {
            // 使用场景选择器：Agent 分析用结构化输出模型
            val provider = AiProviderSelector.getProvider(
                context = context,
                scenario = AiProviderSelector.AiScenario.CHAT_AGENT
            ) ?: throw IllegalStateException("无可用 AI Provider")

            val startTime = System.currentTimeMillis()
            var lastTokenTime = startTime
            var hasReceivedToken = false

            withTimeout(ANALYSIS_LLM_TIMEOUT_MS) {
                coroutineScope {
                    var resumed = false

                    // 无活动检测：20s 无 token 则认为卡住
                    val activityJob = launch {
                        while (isActive) {
                            delay(5_000)
                            val idle = System.currentTimeMillis() - lastTokenTime
                            if (idle > 20_000 && hasReceivedToken) {
                                Log.w(TAG, "⏱ 20s 无新 token，取消")
                                resumed = true
                                break
                            }
                        }
                    }

                    val result = suspendCancellableCoroutine<String> { cont ->
                        cont.invokeOnCancellation {
                            provider.cancel()
                        }

                        provider.sendMessageStreamJson(
                            messages = listOf(com.chin.stockanalysis.ui.Message(content = prompt, isUser = true)),
                            systemPrompt = systemPrompt,
                            onSuccess = { _ ->
                                lastTokenTime = System.currentTimeMillis()
                                hasReceivedToken = true
                            },
                            onComplete = { full ->
                                if (!resumed) { cont.resume(full) {} }
                            },
                            onError = { err ->
                                if (!resumed) { cont.resumeWith(Result.failure(Exception(err))) }
                            }
                        )
                    }

                    activityJob.cancel()
                    result
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "LLM 分析失败: ${e.message}")
            return StockAnalysisResult(
                success = false,
                stockCode = normalizedCode,
                rawOutput = "AI 分析超时，请稍后重试",
                steps = 1
            )
        }

        // Step 4: 解析 JSON 结果
        return try {
            val jsonStr = llmOutput.substringAfter("```json").substringBefore("```").trim()
                .ifEmpty { llmOutput.trim() }
            val json = org.json.JSONObject(jsonStr)

            // 解析 LLM 返回的目标价/止损位
            var targetPrice = json.optString("target_price", "")
            var stopLoss = json.optString("stop_loss", "")

            // 算法 fallback：如果 LLM 返回空或「数据不足」等无效值，使用算法计算
            val h = data.history
            if (h.snapshots.size >= 5) {
                val highs = h.snapshots.map { it.high }
                val lows = h.snapshots.map { it.low }
                val prices = h.snapshots.map { it.close }
                val resistance = highs.take(60).maxOrNull() ?: 0.0
                val support = lows.take(60).minOrNull() ?: 0.0
                val recentRanges = (0 until minOf(20, highs.size, lows.size)).map {
                    highs[it] - lows[it]
                }
                val atr = if (recentRanges.isNotEmpty()) recentRanges.average() * 1.5 else 0.0
                val latestPrice = prices.first()

                // 判断趋势
                val trend = when {
                    prices.first() > prices.last() * 1.05 -> "上升"
                    prices.first() < prices.last() * 0.95 -> "下降"
                    else -> "震荡"
                }

                // 合理目标价范围：现价上浮 3%~10%（封顶），确保不偏离现价太远
                val maxTargetPrice = latestPrice * 1.10
                val minTargetPrice = latestPrice * 1.03

                // 如果 targetPrice 无效或偏离现价太远，使用算法计算
                val llmTargetNum = targetPrice.replace(Regex("[^\\d.]"), "").toDoubleOrNull()
                if (targetPrice.isBlank() || targetPrice.contains("不足") || targetPrice.contains("无法") || targetPrice == "null"
                    || (llmTargetNum != null && (llmTargetNum > maxTargetPrice || llmTargetNum < latestPrice * 0.95))) {
                    val algoTarget = when {
                        trend.contains("上升") -> (resistance * 1.02).coerceIn(minTargetPrice, maxTargetPrice)
                        trend.contains("下降") -> (latestPrice * 1.03).coerceIn(minTargetPrice, maxTargetPrice)
                        else -> (latestPrice * 1.03).coerceIn(minTargetPrice, maxTargetPrice)
                    }.takeIf { it > 0 }
                    if (algoTarget != null) targetPrice = "¥%.2f".format(algoTarget)
                }

                // 如果 stopLoss 无效
                if (stopLoss.isBlank() || stopLoss.contains("不足") || stopLoss.contains("无法") || stopLoss == "null") {
                    val algoStop = maxOf(latestPrice * 0.97, latestPrice - atr * 2, support * 0.97)
                    stopLoss = "¥%.2f".format(algoStop)
                }
            }

            StockAnalysisResult(
                success = true,
                stockCode = normalizedCode,
                overallScore = json.optInt("overall_score", 0),
                recommendation = json.optString("recommendation", "WATCH"),
                confidence = json.optString("confidence", "LOW"),
                technicalScore = json.optInt("technical_score", 0),
                fundamentalScore = json.optInt("fundamental_score", 0),
                fundFlowScore = json.optInt("fund_flow_score", 0),
                reasoning = json.optString("reasoning", ""),
                riskFactors = json.optJSONArray("risk_factors")?.let {
                    (0 until it.length()).map { i -> it.getString(i) }
                } ?: emptyList(),
                targetPrice = targetPrice,
                stopLoss = stopLoss,
                rawOutput = llmOutput,
                steps = 1
            )
        } catch (e: Exception) {
            Log.w(TAG, "解析分析结果失败: ${e.message}")
            // JSON 解析失败时，清理原始文本（去掉 JSON 区块，保留分析文字）
            val cleanedOutput = llmOutput
                .replace(Regex("```json[\\s\\S]*?```"), "")
                .replace(Regex("\\{[^{}]*\\}"), "")
                .trim()
                .lines()
                .filter { it.isNotBlank() && !it.matches(Regex("^\\s*[{}\\[\\],:]\\s*$")) }
                .joinToString("\n")
                .take(1500)
            StockAnalysisResult(
                success = true,
                stockCode = normalizedCode,
                reasoning = cleanedOutput.ifBlank { "AI 分析完成但格式异常，请查看 K 线图和评级数据" },
                rawOutput = llmOutput,
                steps = 1
            )
        }
    }

    /**
     * 构建分析用的 prompt 数据部分
     */
    private fun buildAnalysisPrompt(
        data: StockDataFacade.StockAnalysisData,
        resolvedName: String,
        marketReport: String = ""
    ): String {
        val sb = StringBuilder()

        // 实时行情
        if (data.quote != null) {
            val q = data.quote
            sb.appendLine("## 实时行情（${q.name}）")
            sb.appendLine("- 当前价: ${q.price} (${if (q.changePercent >= 0) "+" else ""}${"%.2f".format(q.changePercent)}%)")
            sb.appendLine("- 最高: ${q.high}, 最低: ${q.low}")
            sb.appendLine("- 成交量: ${q.volume}, 成交额: ${"%.0f".format(q.amount)}")
            sb.appendLine("- 换手率: ${"%.2f".format(q.turnoverRate)}%")
            if (q.pe > 0) sb.appendLine("- PE(TTM): ${"%.2f".format(q.pe)}")
            sb.appendLine()
        }

        // 大盘环境（从外部传入，在 analyze() 中异步获取）
        if (marketReport.isNotEmpty()) {
            sb.appendLine(marketReport)
        } else {
            sb.appendLine("## 大盘环境")
            sb.appendLine("- ⚠️ 大盘分析数据暂时不可用")
            sb.appendLine()
        }

        // 基本面
        val f = data.fundamental
        sb.appendLine("## 基本面数据（${f.source}）")
        sb.appendLine("- 股票名称: ${f.name}")
        if (f.business.isNotBlank()) sb.appendLine("- 主营业务: ${f.business}")
        if (f.sectorNames.isNotEmpty()) sb.appendLine("- 所属板块: ${f.sectorNames.joinToString(", ")}")
        if (f.chainRationale.isNotBlank()) sb.appendLine("- 产业链逻辑: ${f.chainRationale}")
        if (!f.isFresh) sb.appendLine("⚠️ 基本面数据可能不是最新")
        sb.appendLine()

        // 技术面
        val h = data.history
        if (h.snapshots.size >= 5) {
            val snaps = h.snapshots
            val prices = snaps.map { it.close }
            val opens = snaps.map { it.open }
            val highs = snaps.map { it.high }
            val lows = snaps.map { it.low }
            val volumes = snaps.map { it.volume }
            val ma5 = prices.take(5).average()
            val ma10 = if (prices.size >= 10) prices.take(10).average() else ma5
            val ma20 = if (prices.size >= 20) prices.take(20).average() else ma10
            val avgVol5 = volumes.take(5).average()
            val latestVol = volumes.first()
            val latestPrice = prices.first()
            val latestOpen = opens.first()
            val latestHigh = highs.first()
            val latestLow = lows.first()

            // 趋势判断
            val trend = when {
                prices.first() > prices.last() * 1.05 -> "上升趋势"
                prices.first() < prices.last() * 0.95 -> "下降趋势"
                else -> "震荡整理"
            }

            // 均线排列
            val maAlign = when {
                ma5 > ma10 && ma10 > ma20 -> "多头排列（MA5>MA10>MA20）"
                ma5 < ma10 && ma10 < ma20 -> "空头排列（MA5<MA10<MA20）"
                ma5 > ma10 && ma10 < ma20 -> "短期偏强但中期承压"
                ma5 < ma10 && ma10 > ma20 -> "短期回调但中期趋势仍在"
                else -> "均线纠缠"
            }

            // 量能分析
            val volStatus = when {
                latestVol > avgVol5 * 2.0 -> "大幅放量（${"%.1f".format(latestVol / avgVol5)}倍均量）"
                latestVol > avgVol5 * 1.5 -> "放量（${"%.1f".format(latestVol / avgVol5)}倍均量）"
                latestVol < avgVol5 * 0.5 -> "大幅缩量（${"%.1f".format(latestVol / avgVol5)}倍均量）"
                latestVol < avgVol5 * 0.7 -> "缩量（${"%.1f".format(latestVol / avgVol5)}倍均量）"
                else -> "量能正常"
            }

            // K线形态初步识别（为 AI 提供数据基础）
            val bodySize = abs(latestPrice - latestOpen)
            val upperShadow = latestHigh - max(latestPrice, latestOpen)
            val lowerShadow = min(latestPrice, latestOpen) - latestLow
            val totalRange = latestHigh - latestLow
            val bodyRatio = if (totalRange > 0) bodySize / totalRange else 0.0

            val candlePatterns = mutableListOf<String>()
            // 大阳/大阴线
            val changePctDay = if (latestOpen > 0) (latestPrice - latestOpen) / latestOpen * 100 else 0.0
            if (changePctDay > 3.0) candlePatterns.add("大阳线（涨${"%.1f".format(changePctDay)}%）")
            else if (changePctDay < -3.0) candlePatterns.add("大阴线（跌${"%.1f".format(abs(changePctDay))}%）")
            // 十字星
            if (bodyRatio < 0.1 && totalRange > 0) candlePatterns.add("十字星（上下影线较长，多空分歧）")
            // 锤子线（长下影线 + 小实体 + 出现在下跌后）
            if (lowerShadow > bodySize * 2 && upperShadow < bodySize * 0.5 && trend == "下降趋势")
                candlePatterns.add("锤子线（长下影线 ${"%.2f".format(lowerShadow)}，看涨反转信号）")
            // 射击之星（长上影线 + 小实体 + 出现在上涨后）
            if (upperShadow > bodySize * 2 && lowerShadow < bodySize * 0.5 && trend == "上升趋势")
                candlePatterns.add("射击之星（长上影线 ${"%.2f".format(upperShadow)}，看跌反转信号）")
            // 阳包阴/阴包阳
            if (snaps.size >= 2) {
                val prevOpen = opens[1]; val prevClose = prices[1]
                if (prevClose < prevOpen && latestPrice > latestOpen && latestPrice > prevOpen && latestOpen < prevClose)
                    candlePatterns.add("阳包阴（看涨吞没，今日实体完全包裹昨日阴线实体）")
                if (prevClose > prevOpen && latestPrice < latestOpen && latestPrice < prevOpen && latestOpen > prevClose)
                    candlePatterns.add("阴包阳（看跌吞没，今日实体完全包裹昨日阳线实体）")
            }

            // 算法计算目标价和止损位
            val resistance = highs.take(60).maxOrNull() ?: 0.0
            val support = lows.take(60).minOrNull() ?: 0.0

            // ATR（平均真实波幅，取最近 20 日）
            val recentRanges = (0 until minOf(20, highs.size, lows.size)).map {
                highs[it] - lows[it]
            }
            val atr = if (recentRanges.isNotEmpty()) recentRanges.average() * 1.5 else 0.0

            // 目标价计算（封顶在现价上浮 10%，确保不偏离现价太远）
            val maxTargetPrice = latestPrice * 1.10
            val minTargetPrice = latestPrice * 1.03
            val targetPrice = when {
                trend.contains("上升") -> (resistance * 1.02).coerceIn(minTargetPrice, maxTargetPrice)
                trend.contains("下降") -> (latestPrice * 1.03).coerceIn(minTargetPrice, maxTargetPrice)
                else -> (latestPrice * 1.03).coerceIn(minTargetPrice, maxTargetPrice)
            }.takeIf { it > 0 }

            // 止损位计算（取多个保护位的最大值）
            val stopLoss = maxOf(
                latestPrice * 0.97,           // 3% 保本位
                latestPrice - (atr * 2),       // ATR止损
                support * 0.97               // 支撑位
            )

            sb.appendLine("## 技术面数据（${h.source}）")
            sb.appendLine("- 趋势: $trend")
            sb.appendLine("- 近60日阻力位: ¥${"%.2f".format(resistance)}（${if (targetPrice != null) "若突破可上看至" else ""}¥${"%.2f".format(targetPrice ?: 0)}）")
            sb.appendLine("- 近60日支撑位: ¥${"%.2f".format(support)}")
            sb.appendLine("- 动态止损位: ¥${"%.2f".format(stopLoss)}（下方2倍ATR+支撑位）")
            sb.appendLine("- ATR: ${"%.2f".format(atr)}（近20日平均波幅×1.5）")
            sb.appendLine("- 均线: MA5=${"%.2f".format(ma5)}, MA10=${"%.2f".format(ma10)}, MA20=${"%.2f".format(ma20)}")
            sb.appendLine("- 均线排列: $maAlign")
            sb.appendLine("- 量能: $volStatus（5日均量=${"%.0f".format(avgVol5)}）")
            sb.appendLine("- 今日K线: 开${"%.2f".format(latestOpen)} 收${"%.2f".format(latestPrice)} 高${"%.2f".format(latestHigh)} 低${"%.2f".format(latestLow)}")
            if (candlePatterns.isNotEmpty())
                sb.appendLine("- 识别到K线形态: ${candlePatterns.joinToString("；")}")
            // 最近5日K线概要（帮助AI识别多K线组合）
            if (snaps.size >= 5) {
                sb.appendLine("- 近5日K线走势:")
                for (i in 0 until minOf(5, snaps.size)) {
                    val s = snaps[i]
                    val dayChange = if (s.open > 0) (s.close - s.open) / s.open * 100 else 0.0
                    val volVsAvg = if (avgVol5 > 0) s.volume / avgVol5 else 1.0
                    val type = when {
                        dayChange > 1.0 -> "阳"
                        dayChange < -1.0 -> "阴"
                        else -> "小"
                    }
                    sb.appendLine("  D${i}: ${s.date} ${type}(${"%.1f".format(dayChange)}%) 量比${"%.1f".format(volVsAvg)}")
                }
            }
            if (!h.isFresh) sb.appendLine("⚠️ 历史数据截至 ${h.latestDate}")
            sb.appendLine()
        } else {
            sb.appendLine("## 技术面数据")
            sb.appendLine("- 历史数据不足（仅 ${h.snapshots.size} 条），无法计算技术指标")
            sb.appendLine()
        }

        // 资金面
        val ff = data.fundFlow
        sb.appendLine("## 资金面数据（${ff.source}）")
        if (!ff.isEmpty) {
            sb.appendLine("- 主力净流入合计: ${"%.2f".format(ff.totalNetInflow)}万")
            sb.appendLine("- 平均换手率: ${"%.2f".format(ff.avgTurnoverRate)}%")
            sb.appendLine("- 资金趋势: ${if (ff.totalNetInflow > 0) "流入" else "流出"}")
            if (!ff.isFresh) sb.appendLine("⚠️ 最新数据日期: ${ff.latestDate}")
        } else {
            sb.appendLine("- 没有资金流向数据（本地数据库无记录）")
        }

        return sb.toString()
    }
}

/** 分析结果 */
data class StockAnalysisResult(
    val success: Boolean,
    val stockCode: String,
    val overallScore: Int = 0,
    val recommendation: String = "WATCH",
    val confidence: String = "LOW",
    val technicalScore: Int = 0,
    val fundamentalScore: Int = 0,
    val fundFlowScore: Int = 0,
    val reasoning: String = "",
    val riskFactors: List<String> = emptyList(),
    val targetPrice: String = "",
    val stopLoss: String = "",
    val rawOutput: String = "",
    val steps: Int = 0
)

// ════════════════════════════════════════════════════════════════
//  工具实现：让 Agent 主动获取补充数据
// ════════════════════════════════════════════════════════════════

/**
 * 股票查询工具 — 查询指定股票的实时行情和基本面
 */
class StockQueryTool(private val ctx: Context) : AgentTool {
    override val name = "stock_query"
    override val description = "查询股票实时行情、基本面数据。支援股票名称和代码。"
    override val parameters = listOf("stock_code")

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val code = params["stock_code"] ?: return "错误: 缺少 stock_code 参数"
        val normalizedCode = StockAnalysisAgent.normalizeStockCode(code)

        return try {
            val data = StockDataFacade.getInstance(ctx).getAnalysisData(normalizedCode)
            val quote = data.quote
            val fundamental = data.fundamental

            buildString {
                appendLine("## ${fundamental.name}($normalizedCode)")
                if (quote != null) {
                    appendLine("- 当前价: ${quote.price} (${if (quote.changePercent >= 0) "+" else ""}${"%.2f".format(quote.changePercent)}%)")
                    appendLine("- 最高: ${quote.high}, 最低: ${quote.low}")
                    appendLine("- 成交量: ${quote.volume}, 成交额: ${"%.0f".format(quote.amount)}万")
                    appendLine("- 换手率: ${"%.2f".format(quote.turnoverRate)}%")
                    if (quote.pe > 0) appendLine("- PE(TTM): ${"%.2f".format(quote.pe)}")
                    if (quote.pb > 0) appendLine("- PB: ${"%.2f".format(quote.pb)}")
                }
                if (fundamental.business.isNotBlank()) {
                    appendLine("- 主营业务: ${fundamental.business}")
                }
                if (fundamental.sectorNames.isNotEmpty()) {
                    appendLine("- 所属板块: ${fundamental.sectorNames.joinToString(", ")}")
                }
            }
        } catch (e: Exception) {
            "查询失败: ${e.message}"
        }
    }
}

/**
 * 板块查询工具 — 查询板块/行业信息
 */
class SectorQueryTool(private val ctx: Context) : AgentTool {
    override val name = "sector_query"
    override val description = "查询板块/行业的热门程度、成分股、资金流向。"
    override val parameters = listOf("sector_name")

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val sectorName = params["sector_name"] ?: return "错误: 缺少 sector_name 参数"

        return try {
            // 使用 MarketAnalyzer 获取板块信息
            val marketReport = com.chin.stockanalysis.strategy.market.MarketAnalyzer.analyze(ctx, emptyList())
            val matchingSector = marketReport.sectorAdvice.recommendedSectors.firstOrNull {
                it.sectorName.contains(sectorName) || sectorName.contains(it.sectorName)
            }

            if (matchingSector != null) {
                buildString {
                    appendLine("## 板块: ${matchingSector.sectorName}")
                    appendLine("- 置信度: ${"%.0f".format(matchingSector.confidence * 100)}%")
                    appendLine("- 推荐理由: ${matchingSector.reason}")
                }
            } else {
                // 返回当前热门板块列表
                buildString {
                    appendLine("未找到精确匹配的板块「$sectorName」")
                    appendLine()
                    appendLine("## 当前热门板块")
                    marketReport.sectorAdvice.recommendedSectors.take(10).forEach { sector ->
                        appendLine("- ${sector.sectorName}（置信度:${"%.0f".format(sector.confidence * 100)}%）")
                    }
                }
            }
        } catch (e: Exception) {
            "板块查询失败: ${e.message}"
        }
    }
}

/**
 * 市场简报工具 — 获取 A 股市场总览
 */
class MarketBriefTool(private val ctx: Context) : AgentTool {
    override val name = "market_brief"
    override val description = "获取 A 股市场总览（大盘指数、涨跌停数、热门板块、北向资金）。"
    override val parameters = emptyList<String>()

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        return try {
            val report = com.chin.stockanalysis.strategy.market.MarketAnalyzer.analyze(ctx, emptyList())

            buildString {
                appendLine("## A 股市场总览")
                appendLine("- 大盘趋势: ${report.trend.direction}（强度${report.trend.strength}/100）")
                appendLine("- 趋势描述: ${report.trend.description}")
                appendLine("- 卖出信号: ${report.sellType.sellType}")
                if (report.sectorAdvice.recommendedSectors.isNotEmpty()) {
                    val sectorNames = report.sectorAdvice.recommendedSectors.map { it.sectorName }.joinToString(",")
                    appendLine("- 推荐板块: $sectorNames")
                }
                appendLine("- 市场摘要: ${report.summary}")
                if (report.overseas.direction != "UNKNOWN") {
                    appendLine("- 外围市场: ${report.overseas.direction}（${report.overseas.impactHint}）")
                }
            }
        } catch (e: Exception) {
            "市场数据获取失败: ${e.message}"
        }
    }
}
