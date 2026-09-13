package com.chin.stockanalysis.strategy.predict

import android.util.Log
import com.chin.stockanalysis.news.NewsFactorEntity
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * ## 新闻因子量化评分器
 *
 * 为每只候选股票计算新闻得分，将非结构化的新闻因子转化为可量化的数值，
 * 供 AIPredictionEngine 混合评分使用。
 *
 * ### 评分公式
 * ```
 * rawScore = Σ (sentiment × impactStrength × timeDecay)
 *
 * timeDecay:
 *   当日     → 1.0
 *   1-3天前  → 0.8
 *   4-7天前  → 0.6
 *   8-14天前 → 0.4
 *   15天+    → 0.2
 * ```
 *
 * ### 匹配规则（按优先级）
 * 1. stock_code 精确匹配
 * 2. company_name 与 stockName 互相包含
 * 3. tags 中任一标签与 stockName 互相包含
 * 4. sector 与 stockName 互相包含（板块级匹配）
 *
 * ### 归一化
 * rawScore 映射到 0-100 区间：
 * - 先计算所有候选股的 rawScore 范围 [min, max]
 * - normalizedScore = (raw - min) / (max - min) × 100
 * - 如果所有股票得分相同，统一给 50 分
 */
object NewsScoreCalculator {

    private const val TAG = "NewsScoreCalculator"
    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /**
     * 单只股票的新闻评分结果
     */
    data class StockNewsScore(
        val stockCode: String,
        val stockName: String,
        /** 原始加权得分（可能为负） */
        val rawScore: Double,
        /** 归一化得分 0-100 */
        val normalizedScore: Int,
        /** 匹配到的新闻因子数量 */
        val matchedFactorCount: Int,
        /** 利好因子数 */
        val bullishCount: Int,
        /** 利空因子数 */
        val bearishCount: Int,
        /** 匹配摘要（供日志/调试） */
        val matchSummary: String
    )

    /**
     * 为所有候选股票计算新闻得分
     *
     * @param candidates 候选股票列表（code, name）
     * @param factors 活跃新闻因子列表
     * @param referenceDate 参考日期（通常是 selectedDate），用于计算时间衰减
     * @return 每只股票的新闻评分（含归一化得分）
     */
    fun score(
        candidates: List<Pair<String, String>>,
        factors: List<NewsFactorEntity>,
        referenceDate: String
    ): List<StockNewsScore> {
        if (factors.isEmpty() || candidates.isEmpty()) {
            return candidates.map { (code, name) ->
                StockNewsScore(code, name, 0.0, 50, 0, 0, 0, "无新闻数据")
            }
        }

        val refDate = try {
            LocalDate.parse(referenceDate, DATE_FMT)
        } catch (_: Exception) {
            LocalDate.now()
        }

        // 为每只候选股计算原始得分
        val rawScores = candidates.map { (code, name) ->
            val matched = mutableListOf<NewsFactorEntity>()
            var bullish = 0
            var bearish = 0

            for (factor in factors) {
                if (matchesStock(factor, code, name)) {
                    matched.add(factor)
                    if (factor.sentiment > 0) bullish++
                    else if (factor.sentiment < 0) bearish++
                }
            }

            var rawScore = 0.0
            val summaryParts = mutableListOf<String>()

            for (factor in matched) {
                val decay = timeDecay(factor.newsDate, refDate)
                val contribution = factor.sentiment * factor.impactStrength * decay
                rawScore += contribution
            }

            // 构建匹配摘要
            if (matched.isNotEmpty()) {
                val topFactors = matched.sortedByDescending { it.impactStrength }.take(3)
                for (f in topFactors) {
                    val emoji = if (f.sentiment > 0) "+" else if (f.sentiment < 0) "-" else "="
                    summaryParts.add("${emoji}${f.title.take(10)}(${f.impactStrength})")
                }
            }

            StockNewsScore(
                stockCode = code,
                stockName = name,
                rawScore = rawScore,
                normalizedScore = 0, // 稍后归一化
                matchedFactorCount = matched.size,
                bullishCount = bullish,
                bearishCount = bearish,
                matchSummary = summaryParts.joinToString(", ")
            )
        }

        // 归一化到 0-100
        val scores = rawScores.map { it.rawScore }
        val minScore = scores.minOrNull() ?: 0.0
        val maxScore = scores.maxOrNull() ?: 0.0

        return if (maxScore - minScore < 0.001) {
            // 所有股票得分几乎相同，给中间分
            rawScores.map { it.copy(normalizedScore = 50) }
        } else {
            rawScores.map { score ->
                val normalized = ((score.rawScore - minScore) / (maxScore - minScore) * 100)
                    .toInt().coerceIn(0, 100)
                score.copy(normalizedScore = normalized)
            }
        }
    }

    /**
     * 判断新闻因子是否匹配某只股票
     */
    private fun matchesStock(factor: NewsFactorEntity, stockCode: String, stockName: String): Boolean {
        // 1. stock_code 精确匹配
        if (factor.stockCode.isNotBlank() && factor.stockCode == stockCode) return true

        // 2. company_name 与 stockName 互相包含
        if (factor.companyName.isNotBlank()) {
            if (stockName.contains(factor.companyName) || factor.companyName.contains(stockName)) return true
            // 简称匹配：取前2个字
            if (stockName.length >= 2 && factor.companyName.length >= 2) {
                val shortName = stockName.take(2)
                if (factor.companyName.contains(shortName)) return true
            }
        }

        // 3. tags 中任一标签与 stockName 互相包含
        if (factor.tags.isNotBlank()) {
            val tagList = factor.tags.split(",").map { it.trim() }.filter { it.length >= 2 }
            for (tag in tagList) {
                if (stockName.contains(tag) || tag.contains(stockName)) return true
                // 简称匹配
                if (stockName.length >= 2 && tag.contains(stockName.take(2))) return true
            }
        }

        // 4. sector 与 stockName 互相包含（板块级匹配）
        if (factor.sector.isNotBlank()) {
            val sector = factor.sector
            if (stockName.contains(sector) || sector.contains(stockName)) return true
            // 板块关键词匹配
            val sectorKeywords = getSectorKeywords(sector)
            for (kw in sectorKeywords) {
                if (stockName.contains(kw)) return true
            }
        }

        return false
    }

    /**
     * 时间衰减系数
     */
    private fun timeDecay(newsDate: String, refDate: LocalDate): Double {
        val date = try {
            LocalDate.parse(newsDate, DATE_FMT)
        } catch (_: Exception) {
            return 0.3 // 解析失败给较低权重
        }
        val daysAgo = ChronoUnit.DAYS.between(date, refDate).coerceAtLeast(0)
        return when {
            daysAgo == 0L -> 1.0
            daysAgo <= 3L -> 0.8
            daysAgo <= 7L -> 0.6
            daysAgo <= 14L -> 0.4
            else -> 0.2
        }
    }

    /**
     * 板块关键词映射（用于板块级模糊匹配）
     */
    private fun getSectorKeywords(sector: String): List<String> {
        return when {
            sector.contains("半导体") || sector.contains("芯片") -> listOf("芯", "半导", "微电", "集成")
            sector.contains("AI") || sector.contains("人工智能") -> listOf("AI", "智能", "算力", "大模型", "机器人")
            sector.contains("新能源") -> listOf("新能", "光伏", "锂", "储能", "风电")
            sector.contains("银行") -> listOf("银行")
            sector.contains("医药") || sector.contains("医疗") -> listOf("医药", "医疗", "生物", "制药")
            sector.contains("消费") || sector.contains("食品") -> listOf("食品", "饮料", "酒", "乳", "调味")
            sector.contains("汽车") -> listOf("汽车", "车", "新能车")
            sector.contains("军工") || sector.contains("国防") -> listOf("军", "航", "国防")
            sector.contains("地产") -> listOf("地产", "置业", "控股")
            sector.contains("有色") || sector.contains("铝") || sector.contains("铜") -> listOf("铝", "铜", "有色", "矿")
            sector.contains("煤炭") -> listOf("煤", "炭")
            sector.contains("钢铁") -> listOf("钢", "铁")
            sector.contains("软件") || sector.contains("计算机") -> listOf("软件", "信息", "数据", "云", "系统")
            sector.contains("通信") || sector.contains("5G") -> listOf("通信", "5G", "光纤", "基站")
            sector.contains("光") -> listOf("光", "激光", "光电", "光学")
            else -> listOf(sector.take(2)) // 默认取板块名前两个字
        }
    }
}
