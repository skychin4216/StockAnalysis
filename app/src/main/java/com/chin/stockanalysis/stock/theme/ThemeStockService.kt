package com.chin.stockanalysis.stock.theme

import android.util.Log
import com.chin.stockanalysis.stock.data.MultiSourceStockRepository
import com.chin.stockanalysis.stock.data.sources.BidAskData
import com.chin.stockanalysis.stock.data.sources.EastMoneyBidAskSource
import com.chin.stockanalysis.stock.data.sources.EastMoneySectorSource
import com.chin.stockanalysis.stock.data.sources.SectorStock

/**
 * ## 主题/板块股票服务（整合层）
 *
 * 整合三种数据路径：
 * - **方案A**（ThemeStockLibrary）：内置主题库 → 识别"商业航天"等 → 批量拉实时数据
 * - **方案B**（EastMoneySectorSource）：东方财富板块 API → 识别"有色金属前20" → 动态拉板块成分股
 * - **盘口数据**（EastMoneyBidAskSource）：五档买卖挂单 → 生成低吸评级
 *
 * ### 使用流程（在 ChatActivity 中调用）
 * ```kotlin
 * val result = themeStockService.processThemeQuery(
 *     userInput = "帮我分析有色金属前20的股票",
 *     topN = 20,
 *     withBidAsk = true,
 *     prefManager = userPreferenceManager
 * )
 * if (result != null) {
 *     // 将 result.promptInjection 注入到 system prompt
 * }
 * ```
 */
class ThemeStockService(
    private val repository: MultiSourceStockRepository
) {
    private val tag = "ThemeStockService"
    private val sectorSource = EastMoneySectorSource()
    private val bidAskSource = EastMoneyBidAskSource()

    /**
     * 处理主题/板块查询的总入口
     *
     * 自动判断走方案A（内置主题）还是方案B（东方财富板块 API），
     * 并根据用户需求决定是否附带盘口买卖手数数据。
     *
     * @param userInput 用户原始输入
     * @param topN 取前 N 只（用户没指定时使用此默认值）
     * @param withBidAsk 是否附带五档盘口数据（尾盘买卖手评级）
     * @param prefManager 用户偏好管理器（用于自动应用过滤条件）
     * @return ThemeQueryResult 或 null（未匹配到主题/板块）
     */
    fun processThemeQuery(
        userInput: String,
        topN: Int = 15,
        withBidAsk: Boolean = false,
        prefManager: UserPreferenceManager? = null
    ): ThemeQueryResult? {
        Log.d(tag, "processThemeQuery: '${userInput.take(60)}'")

        // 从用户输入中提取数量要求（"前20" → 20）
        val requestedTopN = extractTopN(userInput) ?: topN
        val minCap = prefManager?.getMinMarketCap() ?: 0L
        val excludeKcb = prefManager?.getExcludeExchanges()?.contains("科创板") ?: true
        val excludeCyb = prefManager?.getExcludeExchanges()?.contains("创业板") ?: false

        // ── 方案B 优先：东方财富板块 API（更实时、更全面）──
        val sectorResult = trySectorQuery(userInput, requestedTopN, excludeKcb, excludeCyb, minCap)
        if (sectorResult != null) {
            val (sectorName, sectorStocks) = sectorResult
            val codes = sectorStocks.map { it.code }
            Log.d(tag, "方案B 成功: $sectorName → ${codes.size} stocks")

            // 同时拉实时行情（覆盖 SectorStock 中已有的数据，确保最新）
            val realtimeMap = try { repository.getRealtime(codes.take(20)) } catch (e: Exception) { emptyMap() }

            // 附带盘口数据（可选）
            val bidAskMap: Map<String, BidAskData> = if (withBidAsk || userInput.containsBidAskKeyword()) {
                try { bidAskSource.fetchBidAsk(codes.take(20)) } catch (e: Exception) { emptyMap() }
            } else emptyMap()

            val prompt = buildSectorPrompt(sectorName, sectorStocks, realtimeMap, bidAskMap, userInput, prefManager)
            return ThemeQueryResult(
                type = QueryType.SECTOR,
                themeName = sectorName,
                stockCodes = codes,
                promptInjection = prompt,
                stockCount = sectorStocks.size
            )
        }

        // ── 方案A：内置主题库 ──
        val themeMatch = ThemeStockLibrary.findTheme(userInput)
        if (themeMatch != null) {
            with(ThemeStockLibrary) {
                val validStocks = themeMatch.themeInfo.validStocks()
                var codes = validStocks.map { it.code }

                // 应用用户偏好过滤（交易所）
                if (prefManager != null) {
                    codes = prefManager.applyCodeFilters(codes)
                }

                Log.d(tag, "方案A 成功: ${themeMatch.themeInfo.name} → ${codes.size} stocks")

                // 拉实时行情
                val realtimeMap = try { repository.getRealtime(codes.take(requestedTopN)) } catch (e: Exception) { emptyMap() }

                // 附带盘口数据（可选）
                val bidAskMap: Map<String, BidAskData> = if (withBidAsk || userInput.containsBidAskKeyword()) {
                    try { bidAskSource.fetchBidAsk(codes.take(requestedTopN)) } catch (e: Exception) { emptyMap() }
                } else emptyMap()

                val prompt = buildThemePrompt(themeMatch, validStocks, realtimeMap, bidAskMap, userInput, prefManager)
                return ThemeQueryResult(
                    type = QueryType.THEME,
                    themeName = themeMatch.themeInfo.name,
                    stockCodes = codes,
                    promptInjection = prompt,
                    stockCount = codes.size
                )
            }
        }

        Log.d(tag, "未匹配任何主题/板块")
        return null
    }

    /**
     * 专门获取盘口低吸评级（不需要主题匹配）
     *
     * 当用户提供了具体股票代码列表时，直接查盘口数据
     */
    fun fetchBidAskForCodes(codes: List<String>): Map<String, BidAskData> {
        return try {
            bidAskSource.fetchBidAsk(codes)
        } catch (e: Exception) {
            Log.e(tag, "fetchBidAskForCodes error: ${e.message}")
            emptyMap()
        }
    }

    // ════════════════════════════════════════
    // 尝试板块查询（方案B）
    // ════════════════════════════════════════

    private fun trySectorQuery(
        userInput: String,
        topN: Int,
        excludeKcb: Boolean,
        excludeCyb: Boolean,
        minCap: Long
    ): Pair<String, List<SectorStock>>? {
        // 检测是否包含行业/板块相关词
        val sectorKeywords = EastMoneySectorSource.SECTOR_CODE_MAP.keys.toList()
        val matchedSector = sectorKeywords.firstOrNull { userInput.contains(it, ignoreCase = true) }
            ?: return null

        return try {
            sectorSource.fetchByName(matchedSector, topN, excludeKcb, excludeCyb, minCap)
        } catch (e: Exception) {
            Log.w(tag, "trySectorQuery failed for $matchedSector: ${e.message}")
            null
        }
    }

    // ════════════════════════════════════════
    // Prompt 构建
    // ════════════════════════════════════════

    /**
     * 构建方案B（板块成分股）的 prompt 注入文本
     */
    private fun buildSectorPrompt(
        sectorName: String,
        sectorStocks: List<SectorStock>,
        realtimeMap: Map<String, com.chin.stockanalysis.stock.StockRealtime>,
        bidAskMap: Map<String, BidAskData>,
        userInput: String,
        prefManager: UserPreferenceManager?
    ): String {
        val sb = StringBuilder()

        // 1. 板块行情概览
        sb.appendLine("\n【${sectorName}板块实时行情（东方财富板块成分股）】")
        sb.appendLine("数据来源：东方财富行情 API  时间：${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())}")
        if (prefManager?.getMinMarketCap() ?: 0L > 0) {
            sb.appendLine("已过滤：市值 ${prefManager!!.getMinMarketCap()} 亿以下，共 ${sectorStocks.size} 只")
        } else {
            sb.appendLine("共 ${sectorStocks.size} 只（已过滤科创板，按市值排序）")
        }
        sb.appendLine()

        sectorStocks.forEachIndexed { index, s ->
            val realtime = realtimeMap[s.code]
            val price = realtime?.price ?: s.price
            val changePct = realtime?.changePercent ?: s.changePercent
            val arrow = when {
                changePct > 0 -> "📈"
                changePct < 0 -> "📉"
                else -> "➡️"
            }
            val capStr = if (s.marketCapBillion > 0) " | 市值:${s.marketCapBillion}亿" else ""
            val bidAsk = bidAskMap[s.code]
            val ratingStr = if (bidAsk != null) " | ${bidAsk.ratingText}" else ""

            sb.appendLine("${index + 1}. $arrow ${s.name}(${s.code.takeLast(6)}) " +
                    "现价:${"%.2f".format(price)}元 " +
                    "涨跌:${if (changePct > 0) "+" else ""}${"%.2f".format(changePct)}%" +
                    "$capStr$ratingStr")
        }

        // 2. 盘口数据（如有）
        if (bidAskMap.isNotEmpty()) {
            sb.append(bidAskSource.formatForPrompt(bidAskMap))
        }

        // 3. 用户偏好提示
        prefManager?.buildPreferencePrompt()?.let { if (it.isNotBlank()) sb.append(it) }

        // 4. AI 分析指令
        sb.appendLine()
        sb.appendLine("【分析任务】")
        sb.appendLine("用户问题：$userInput")
        sb.appendLine(buildAnalysisInstructions(userInput, sectorName, bidAskMap.isNotEmpty()))

        return sb.toString()
    }

    /**
     * 构建方案A（内置主题库）的 prompt 注入文本
     */
    private fun buildThemePrompt(
        themeMatch: ThemeMatch,
        validStocks: List<ThemeStock>,
        realtimeMap: Map<String, com.chin.stockanalysis.stock.StockRealtime>,
        bidAskMap: Map<String, BidAskData>,
        userInput: String,
        prefManager: UserPreferenceManager?
    ): String {
        val sb = StringBuilder()
        val themeName = themeMatch.themeInfo.name

        sb.appendLine("\n【${themeName}主题实时行情数据】")
        sb.appendLine("数据来源：多源并发（新浪/腾讯/东方财富）  时间：${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())}")
        sb.appendLine()

        // 行情 + 主题信息整合
        sb.appendLine("序号 | 股票名称 | 股票代码 | 现价 | 涨跌% | 成交量 | 市值 | 核心业务 | 产业链核心依据")
        sb.appendLine("-----|---------|---------|------|-------|-------|------|---------|------------")

        validStocks.forEachIndexed { index, themeStock ->
            val realtime = realtimeMap[themeStock.code]
            val bidAsk = bidAskMap[themeStock.code]
            val priceStr = if (realtime != null) "${"%.2f".format(realtime.price)}元" else "（待获取）"
            val changePctStr = if (realtime != null) {
                val pct = realtime.changePercent
                "${if (pct > 0) "+" else ""}${"%.2f".format(pct)}%"
            } else "—"
            val volumeStr = if (realtime != null) "${realtime.volume / 10000}万手" else "—"
            val ratingStr = bidAsk?.ratingText ?: "—"

            sb.appendLine("${index + 1} | ${themeStock.name} | ${themeStock.code.takeLast(6)} | $priceStr | $changePctStr | $volumeStr | — | ${themeStock.business} | ${themeStock.chainRationale}")
            if (bidAsk != null) {
                sb.appendLine("   ↳ 盘口: 买${"%,d".format(bidAsk.totalBidVolume)}手 VS 卖${"%,d".format(bidAsk.totalAskVolume)}手 买卖比${"%.2f".format(bidAsk.bidAskRatio)} ${bidAsk.ratingText}")
            }
        }

        // 盘口数据摘要（如有）
        if (bidAskMap.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("【低吸候选股（买卖比 ≥ 1.2 筛选）】")
            bidAskMap.values
                .filter { it.bidAskRatio >= 1.2 }
                .sortedByDescending { it.bidAskRatio }
                .forEach { data ->
                    sb.appendLine("${data.ratingText} ${data.name}(${data.code.takeLast(6)}) 买卖比:${"%.2f".format(data.bidAskRatio)} 现价:${"%.2f".format(data.currentPrice)}元")
                }
        }

        // 用户偏好
        prefManager?.buildPreferencePrompt()?.let { if (it.isNotBlank()) sb.append(it) }

        // 分析指令
        sb.appendLine()
        sb.appendLine("【分析任务】")
        sb.appendLine("用户问题：$userInput")
        sb.appendLine(buildAnalysisInstructions(userInput, themeName, bidAskMap.isNotEmpty()))

        return sb.toString()
    }

    // ════════════════════════════════════════
    // 通用工具
    // ════════════════════════════════════════

    /**
     * 构建 AI 分析指令
     *
     * 格式由 AI 根据用户需求自由选择：
     * - 用户要求"整理成表格" → 输出 Markdown 表格
     * - 用户只问某只股票 → 正常文字分析
     * - 用户要求"前10名" → 表格+龙头逻辑
     */
    private fun buildAnalysisInstructions(userInput: String, themeName: String, hasBidAsk: Boolean): String {
        val today = java.text.SimpleDateFormat("yyyy.MM.dd").format(java.util.Date())
        return buildString {
            appendLine("请基于以上实时行情数据，结合用户问题「$userInput」，选择最合适的格式输出分析结果。")
            appendLine()
            appendLine("【参考输出格式（如用户要求「前N名/排行/整理表格」时使用）】")
            appendLine("${themeName}前 N 龙头股（$today）")
            appendLine("说明：按总市值 + 行业地位 + 业绩确定性综合排序。")
            appendLine()
            appendLine("| 排名 | 股票代码 | 股票名称 | 核心赛道 | 市值（亿） | 核心依据（龙头逻辑） | 尾盘低吸参考 |")
            appendLine("|------|---------|---------|---------|----------|------------------|------------|")
            appendLine("| 1    | xxxxxx  | 公司名称 | 细分方向 | 市值 | 核心竞争力，业绩亮点 | 评级 |")
            appendLine()
            appendLine("【字段说明】")
            appendLine("- 股票代码：6位纯数字")
            appendLine("- 核心赛道：2~4个关键词描述在${themeName}中的细分定位")
            appendLine("- 核心依据：市场地位 + 核心壁垒 + 近期业绩，30字以内")

            if (hasBidAsk) {
                appendLine()
                appendLine("【尾盘低吸评级（基于实时买卖手数据）】")
                appendLine("🟢 买卖比 ≥ 1.5 → 强烈低吸  🟡 1.2~1.5 → 低吸观察  ⚪ 0.8~1.2 → 观望  🔴 < 0.8 → 暂不介入")
            } else {
                appendLine()
                appendLine("【尾盘低吸参考】如用户关注尾盘机会，请根据涨跌幅、量比、换手率等给出简短评价。")
            }

            appendLine()
            appendLine("⚠️ 输出结尾必须加：投资有风险，入市需谨慎，以上均为信息参考，不构成投资建议。")
        }
    }

    /**
     * 从用户输入中提取数量要求
     * "前20只" → 20, "前10名" → 10
     */
    private fun extractTopN(userInput: String): Int? {
        val pattern = Regex("""前(\d+)(?:只|名|个|支)?|top\s*(\d+)""", RegexOption.IGNORE_CASE)
        val match = pattern.find(userInput) ?: return null
        return (match.groupValues[1].ifBlank { match.groupValues[2] }).toIntOrNull()
    }

    /**
     * 判断用户输入是否包含盘口/低吸相关关键词
     */
    private fun String.containsBidAskKeyword(): Boolean {
        return contains("买手") || contains("卖手") || contains("盘口") ||
                contains("低吸") || contains("挂单") || contains("买卖比") ||
                contains("五档") || contains("尾盘")
    }
}

// ═══════════════════════════════════════════════════════
// 结果数据类
// ═══════════════════════════════════════════════════════

enum class QueryType { THEME, SECTOR }

data class ThemeQueryResult(
    val type: QueryType,
    val themeName: String,
    val stockCodes: List<String>,
    val promptInjection: String,   // 注入 system prompt 的文本
    val stockCount: Int
)
