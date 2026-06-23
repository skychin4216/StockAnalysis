package com.chin.stockanalysis.agent.pipeline

import org.json.JSONObject
import org.json.JSONArray

/**
 * 結構化輸出解析器 — 從 LLM 回覆中提取 JSON 分數/評級/交易參數
 *
 * LLM 輸出格式約定：
 * ```
 * [分析文本...]
 *
 * ```json
 * {"score": 78, "material": 20, ...}
 * ```
 * ```
 */
object StructuredOutputParser {

    /**
     * 從 LLM 回覆中提取 JSON 區塊
     * 支援 ```json ... ``` 和 { ... } 兩種格式
     */
    fun extractJson(text: String): JSONObject? {
        // 嘗試 ```json ... ``` 格式
        val fencedPattern = Regex("```json\\s*([\\s\\S]*?)```")
        val fencedMatch = fencedPattern.find(text)
        if (fencedMatch != null) {
            return try { JSONObject(fencedMatch.groupValues[1].trim()) } catch (_: Exception) { null }
        }
        // 嘗試直接 { ... } 格式
        val bracePattern = Regex("\\{[\\s\\S]*\\}")
        val braceMatch = bracePattern.find(text)
        if (braceMatch != null) {
            return try { JSONObject(braceMatch.value) } catch (_: Exception) { null }
        }
        return null
    }

    /**
     * 解析 Agent 2 產業鏈打分
     */
    fun parseChainScore(stockCode: String, stockName: String, text: String): ChainScoreResult? {
        val json = extractJson(text) ?: return null
        return try {
            val base = json.optInt("baseScore", 0)
            val material = json.optInt("material", 0)
            val barrier = json.optInt("barrier", 0)
            val coverage = json.optInt("coverage", 0)
            val irreplace = json.optInt("irreplaceable", 0)
            val resonance = json.optInt("resonance", 0)
            val overseas = json.optInt("overseas", 0)
            val foreign = json.optInt("foreignRating", 0)
            val total = json.optInt("totalScore", base + material + barrier + coverage + irreplace + resonance + overseas + foreign).coerceAtMost(100)
            val level = json.optString("barrierLevel", "中")
            val passed = total >= 40
            ChainScoreResult(
                stockCode = stockCode, stockName = stockName,
                baseScore = base, materialScore = material, barrierScore = barrier,
                coverageScore = coverage, irreplaceScore = irreplace, resonanceBonus = resonance,
                overseasBonus = overseas, foreignRatingBonus = foreign,
                totalScore = total, barrierLevel = level, passed = passed
            )
        } catch (_: Exception) { null }
    }

    /**
     * 解析 Agent 5 風控結果
     */
    fun parseRiskResult(stockCode: String, text: String): RiskValidationResult? {
        val json = extractJson(text) ?: return null
        return try {
            val level = json.optString("riskLevel", "中")
            val deductions = mutableListOf<RiskDeduction>()
            val dedArray = json.optJSONArray("deductions")
            if (dedArray != null) {
                for (i in 0 until dedArray.length()) {
                    val obj = dedArray.getJSONObject(i)
                    deductions.add(RiskDeduction(
                        item = obj.optString("item", ""),
                        description = obj.optString("description", ""),
                        score = obj.optInt("score", 0)
                    ))
                }
            }
            val overseasDed = json.optInt("overseasDeduction", 0)
            val adjustedScore = json.optInt("adjustedScore", 0)
            val passed = level != "高"
            RiskValidationResult(
                stockCode = stockCode, riskLevel = level,
                deductions = deductions, overseasDeduction = overseasDed,
                adjustedScore = adjustedScore, passed = passed
            )
        } catch (_: Exception) { null }
    }

    /**
     * 解析 Agent D 輿情微調
     */
    fun parseSentimentResult(text: String): SentimentAdjustResult? {
        val json = extractJson(text) ?: return null
        return try {
            SentimentAdjustResult(
                sentimentScore = json.optInt("sentimentScore", 0),
                positionAdjust = json.optString("positionAdjust", "0%"),
                reason = json.optString("reason", "")
            )
        } catch (_: Exception) { null }
    }

    /**
     * 解析 Agent 4 交易方案
     */
    fun parseTradePlan(stockCode: String, stockName: String, text: String): TradeExecutionPlan? {
        val json = extractJson(text) ?: return null
        return try {
            val entryZones = mutableListOf<String>()
            val entryArr = json.optJSONArray("entryZones")
            if (entryArr != null) { for (i in 0 until entryArr.length()) entryZones.add(entryArr.getString(i)) }

            val targets = mutableListOf<String>()
            val targetArr = json.optJSONArray("targets")
            if (targetArr != null) { for (i in 0 until targetArr.length()) targets.add(targetArr.getString(i)) }

            val rules = mutableListOf<String>()
            val rulesArr = json.optJSONArray("tradeRules")
            if (rulesArr != null) { for (i in 0 until rulesArr.length()) rules.add(rulesArr.getString(i)) }

            TradeExecutionPlan(
                stockCode = stockCode, stockName = stockName,
                entryZones = entryZones,
                stopLoss = json.optString("stopLoss", ""),
                targets = targets,
                maxPosition = json.optString("maxPosition", "30%"),
                splitRatio = json.optString("splitRatio", "5:3:2"),
                tradeRules = rules
            )
        } catch (_: Exception) { null }
    }

    /**
     * 解析 Agent 1 初選池（從文本中提取股票代碼列表）
     */
    fun parseFilteredPool(text: String): List<FilteredStock> {
        val json = extractJson(text) ?: return emptyList()
        return try {
            val pool = mutableListOf<FilteredStock>()
            val arr = json.optJSONArray("filteredPool") ?: return emptyList()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                pool.add(FilteredStock(
                    stockCode = obj.optString("code", ""),
                    stockName = obj.optString("name", ""),
                    filterReason = obj.optString("reason", "")
                ))
            }
            pool
        } catch (_: Exception) { emptyList() }
    }

    /**
     * 解析 Agent 3 賽道熱度
     */
    fun parseSectorHeat(text: String): String? {
        val json = extractJson(text) ?: return null
        return try { json.optString("heatLevel", null) } catch (_: Exception) { null }
    }
}