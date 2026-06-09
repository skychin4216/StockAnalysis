package com.chin.stockanalysis.agent

import android.util.Log

/**
 * ## 智能体选股解析器
 *
 * 从 AI 回复文本中提取推荐的股票代码和名称，
 * 用于自动填充 skill_pick_stocks 数组。
 *
 * ### 支持的识别模式
 * 1. A股代码格式: sh600519, sz000001, sh688xxx
 * 2. 纯数字代码: 600519
 * 3. 股票名+代码: 贵州茅台(600519)
 * 4. AI 结构化输出: 推荐股票: 600519 贵州茅台
 */
object AgentPickParser {

    private const val TAG = "AgentPickParser"

    // 匹配 A股代码: sh600519, sz000001, sh688xxx, bjxxxxx
    private val STOCK_CODE_REGEX = Regex(
        """\b((?:sh|sz|bj)\d{6})\b""",
        RegexOption.IGNORE_CASE
    )

    // 匹配 纯数字6位代码（去除前后非字母确认不是更大的数字串）
    private val RAW_CODE_REGEX = Regex(
        """(?<!\d)(\d{6})(?!\d)"""
    )

    // 匹配 股票名+代码: 贵州茅台(600519), 茅台(600519.SH)
    private val NAME_CODE_REGEX = Regex(
        """([\u4e00-\u9fffA-Za-z]{2,8})\s*[\(（]\s*(\d{6})\s*[\)）]"""
    )

    // 匹配 AI 推荐模式: 推荐**：600519，推荐: 贵州茅台
    private val RECOMMEND_REGEX = Regex(
        """(?:推荐|建议|买入|关注|精选|选中)[：:]*\s*([\u4e00-\u9fffA-Za-z0-9（）()，,、\s]{2,40})"""
    )

    /**
     * 从 AI 回复文本中提取选股结果
     *
     * @param aiResponse AI 返回的完整文本
     * @param sourceAgentId 来源智能体 ID
     * @return 解析出的选股列表
     */
    fun parse(aiResponse: String, sourceAgentId: String): List<AgentPick> {
        val picks = mutableListOf<AgentPick>()
        val seenCodes = mutableSetOf<String>()

        // 1. 匹配 A股完整代码 (sh600519)
        for (match in STOCK_CODE_REGEX.findAll(aiResponse)) {
            val code = match.groupValues[1]
            if (seenCodes.add(code)) {
                val name = extractStockName(aiResponse, code)
                picks.add(AgentPick(
                    rank = picks.size + 1,
                    stockCode = code,
                    stockName = name,
                    reason = extractNearbyReason(aiResponse, code),
                    confidence = calculateConfidence(aiResponse, code),
                    sourceAgentId = sourceAgentId
                ))
            }
        }

        // 2. 匹配 股票名+代码格式
        for (match in NAME_CODE_REGEX.findAll(aiResponse)) {
            val name = match.groupValues[1]
            val codeDigits = match.groupValues[2]
            val code = inferCodePrefix(codeDigits)
            if (seenCodes.add(code)) {
                picks.add(AgentPick(
                    rank = picks.size + 1,
                    stockCode = code,
                    stockName = name,
                    reason = extractNearbyReason(aiResponse, code),
                    confidence = calculateConfidence(aiResponse, code),
                    sourceAgentId = sourceAgentId
                ))
            }
        }

        // 3. 匹配纯6位代码（补充未通过上面方式识别的）
        for (match in RAW_CODE_REGEX.findAll(aiResponse)) {
            val codeDigits = match.groupValues[1]
            // 过滤掉不太可能是股票代码的（如 000000, 999999 等）
            if (!isLikelyStockCode(codeDigits)) continue
            val code = inferCodePrefix(codeDigits)
            if (seenCodes.add(code)) {
                val name = extractStockName(aiResponse, code)
                picks.add(AgentPick(
                    rank = picks.size + 1,
                    stockCode = code,
                    stockName = name,
                    reason = extractNearbyReason(aiResponse, code),
                    confidence = calculateConfidence(aiResponse, code),
                    sourceAgentId = sourceAgentId
                ))
            }
        }

        Log.i(TAG, "从 Agent[$sourceAgentId] 回复中解析出 ${picks.size} 只股票: " +
            picks.joinToString { "${it.stockName}(${it.stockCode})" })

        return picks
    }

    /**
     * 从文本中提取股票名称（位于代码附近）
     */
    private fun extractStockName(text: String, code: String): String {
        // 找代码前后的中文名称
        val codeIndex = text.indexOf(code)
        if (codeIndex < 0) return code

        // 向前查找中文名称
        val before = text.substring(maxOf(0, codeIndex - 20), codeIndex)
        val nameBeforeRegex = Regex("""([\u4e00-\u9fff]{2,8})\s*$""")
        val nameBeforeMatch = nameBeforeRegex.find(before)
        if (nameBeforeMatch != null) return nameBeforeMatch.groupValues[1]

        // 向后查找中文名称
        val after = text.substring(codeIndex + code.length, minOf(text.length, codeIndex + code.length + 20))
        val nameAfterRegex = Regex("""^\s*([\u4e00-\u9fff]{2,8})""")
        val nameAfterMatch = nameAfterRegex.find(after)
        if (nameAfterMatch != null) return nameAfterMatch.groupValues[1]

        return code
    }

    /**
     * 提取代码附近的理由文本
     */
    private fun extractNearbyReason(text: String, code: String): String {
        val codeIndex = text.indexOf(code)
        if (codeIndex < 0) return ""
        val start = maxOf(0, codeIndex - 60)
        val end = minOf(text.length, codeIndex + 100)
        val context = text.substring(start, end).trim()
        // 取一行或60字符
        return context.take(80).replace("\n", " ")
    }

    /**
     * 计算信心度（基于文本中的积极词语密度）
     */
    private fun calculateConfidence(text: String, code: String): Float {
        val codeIndex = text.indexOf(code)
        if (codeIndex < 0) return 0.5f
        val context = text.substring(
            maxOf(0, codeIndex - 100),
            minOf(text.length, codeIndex + 150)
        )
        val positiveWords = listOf("推荐", "强烈", "买入", "看好", "优秀", "龙头", "冠军",
            "高增长", "潜力", "优质", "首选", "重点关注", "超额", "领先")
        val negativeWords = listOf("风险", "谨慎", "观望", "卖出", "减持", "注意", "警惕", "泡沫")

        val positiveCount = positiveWords.count { context.contains(it) }
        val negativeCount = negativeWords.count { context.contains(it) }

        return (0.5f + positiveCount * 0.1f - negativeCount * 0.08f).coerceIn(0.1f, 0.95f)
    }

    /**
     * 根据数字代码推断交易所前缀
     */
    private fun inferCodePrefix(codeDigits: String): String {
        return when {
            codeDigits.startsWith("688") -> "sh$codeDigits"
            codeDigits.startsWith("300") || codeDigits.startsWith("301") -> "sz$codeDigits"
            codeDigits.startsWith("000") || codeDigits.startsWith("001") ||
                codeDigits.startsWith("002") || codeDigits.startsWith("003") -> "sz$codeDigits"
            codeDigits.startsWith("8") -> "bj$codeDigits"
            codeDigits.startsWith("4") -> "bj$codeDigits"
            codeDigits.startsWith("6") -> "sh$codeDigits"
            else -> "sz$codeDigits"
        }
    }

    /**
     * 判断是否可能是A股代码
     */
    private fun isLikelyStockCode(codeDigits: String): Boolean {
        val num = codeDigits.toIntOrNull() ?: return false
        // A股代码范围: 000001-999999，排除纯0
        return num in 1..999999 && codeDigits != "000000"
    }
}