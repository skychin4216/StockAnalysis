package com.chin.stockanalysis.agent.pipeline

import android.util.Log
import org.json.JSONObject
import org.json.JSONArray

/**
 * 结构化输出解析器 — 从 LLM 回复中提取 JSON 分数/评级/交易参数
 *
 * LLM 输出格式约定：
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
     * 从 LLM 回复中提取 JSON 区块
     * 支援 ```json ... ``` 和 { ... } 两种格式
     */
    fun extractJson(text: String): JSONObject? {
        // 尝试 ```json ... ``` 格式
        val fencedPattern = Regex("```json\\s*([\\s\\S]*?)```")
        val fencedMatch = fencedPattern.find(text)
        if (fencedMatch != null) {
            return try { JSONObject(fencedMatch.groupValues[1].trim()) } catch (_: Exception) { null }
        }
        // 尝试直接 { ... } 格式
        val bracePattern = Regex("\\{[\\s\\S]*\\}")
        val braceMatch = bracePattern.find(text)
        if (braceMatch != null) {
            return try { JSONObject(braceMatch.value) } catch (_: Exception) { null }
        }
        return null
    }

    /**
     * 解析 Agent 2 产业链打分
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
     * 解析 Agent 5 风控结果
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
     * 解析 Agent D 舆情微调
     */
    fun parseSentimentResult(text: String): SentimentAdjustResult? {
        val json = extractJson(text)
        if (json != null) {
            return try {
                SentimentAdjustResult(
                    sentimentScore = json.optInt("sentimentScore", 0),
                    positionAdjust = json.optString("positionAdjust", "0%"),
                    reason = json.optString("reason", "")
                )
            } catch (_: Exception) { null }
        }
        // Fallback: 如果 JSON 解析失败，尝试从文本提取分数（部分模型在 jsonMode 下仍返回非结构化文本）
        val trimmed = text.trim()
        val scoreMatch = Regex("(?:sentimentScore|score|得分|评分)[：:\\s]*(\\d+)").find(trimmed)
        val score = scoreMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val adjustMatch = Regex("(?:positionAdjust|position_adjust|仓位|仓位调整)[：:\\s]*([+-]?\\d+%?)").find(trimmed)
        val adjust = adjustMatch?.groupValues?.get(1) ?: "0%"
        if (score == 0 && adjust == "0%" && trimmed.length < 50) {
            Log.w("StructuredOutputParser", "Agent D 舆情解析失败，原始回应: ${trimmed.take(100)}")
            return null
        }
        return SentimentAdjustResult(
            sentimentScore = score,
            positionAdjust = adjust,
            reason = "LLM 未返回标准 JSON，已从文本提取"
        )
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
     * 解析 Agent 1 初选池（从文本中提取股票代码列表）
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
     * 解析 Agent 3 赛道热度
     */
    fun parseSectorHeat(text: String): String? {
        val json = extractJson(text) ?: return null
        return try { json.optString("heatLevel", null) } catch (_: Exception) { null }
    }

    /**
     * 将单个 Pipeline Agent 的结构化 JSON 输出转为可读摘要。
     *
     * Agent 在 jsonMode 下输出纯 JSON（见 skills_config.json 的输出规范），
     * 直接倾倒会产生乱码。此处按 agentId 提取关键栏位渲染为自然语言。
     * 非 JSON（纯文本回复）则截断保留。
     */
    fun formatReadable(agentId: String, rawText: String): String {
        val json = extractJson(rawText) ?: return rawText.trim().take(400)
        return try {
            when (agentId) {
                "pipeline_agent_1" -> {
                    val arr = json.optJSONArray("filteredPool")
                    if (arr == null || arr.length() == 0) return "（未输出初选池）"
                    buildString {
                        appendLine("初选池 ${arr.length()} 档：")
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            appendLine("  • ${o.optString("code")} ${o.optString("name")}：${o.optString("reason")}")
                        }
                    }.trim()
                }
                "pipeline_agent_2" -> {
                    "产业链打分 ${json.optInt("totalScore", 0)}/100（壁垒：${json.optString("barrierLevel", "?")}），" +
                        (if (json.optBoolean("passed", false)) "通过流转" else "未达 40 分淘汰")
                }
                "pipeline_agent_3" -> buildString {
                    append("赛道热度：${json.optString("heatLevel", "未知")}")
                    val theme = json.optString("theme", "")
                    if (theme.isNotBlank()) append("｜主题：$theme")
                    append("｜正宗标的：${if (json.optBoolean("authentic", false)) "是" else "否（蹭热点）"}")
                    val cat = json.optJSONArray("catalysts")
                    if (cat != null && cat.length() > 0) {
                        appendLine()
                        append("催化事件：${(0 until cat.length()).map { cat.optString(it) }.joinToString("；")}")
                    }
                }.trim()
                "pipeline_agent_4" -> buildString {
                    val ez = json.optJSONArray("entryZones")
                    if (ez != null && ez.length() > 0) appendLine("低吸区间：${(0 until ez.length()).map { ez.optString(it) }.joinToString(" / ")}")
                    val sl = json.optString("stopLoss", "")
                    if (sl.isNotBlank()) appendLine("止损位：$sl")
                    val tg = json.optJSONArray("targets")
                    if (tg != null && tg.length() > 0) appendLine("止盈目标：${(0 until tg.length()).map { tg.optString(it) }.joinToString(" / ")}")
                    append("最大仓位：${json.optString("maxPosition", "?")}｜分仓比例：${json.optString("splitRatio", "?")}")
                }.trim()
                "pipeline_agent_5" -> buildString {
                    append("风险等级：${json.optString("riskLevel", "未知")}")
                    val adj = json.optInt("adjustedScore", 0)
                    if (adj > 0) append("｜对冲后分数：$adj")
                    val ded = json.optJSONArray("deductions")
                    if (ded != null && ded.length() > 0) {
                        appendLine()
                        for (i in 0 until ded.length()) {
                            val d = ded.getJSONObject(i)
                            appendLine("  • ${d.optString("item")}：${d.optString("description")}")
                        }
                    }
                }.trim()
                "pipeline_agent_d" -> {
                    "舆情得分 ${json.optInt("sentimentScore", 0)}，仓位微调 ${json.optString("positionAdjust", "0%")}" +
                        json.optString("reason", "").let { if (it.isNotBlank()) "：$it" else "" }
                }
                else -> genericJsonToText(json)  // 竞争格局等其他 Agent：通用提取
            }
        } catch (_: Exception) {
            rawText.trim().take(300)
        }
    }

    /** 通用 fallback：从 JSON 提取可读的字串/阵列栏位，避免倾倒原始结构 */
    private fun genericJsonToText(json: JSONObject): String = buildString {
        val keys = json.keys()
        var count = 0
        while (keys.hasNext() && count < 8) {
            val k = keys.next()
            val v = json.opt(k)
            val s = when (v) {
                is String -> v
                is JSONArray -> (0 until v.length()).mapNotNull { v.opt(it)?.toString() }.joinToString("、")
                null -> ""
                else -> v.toString()
            }
            if (s.isNotBlank() && s != "0" && s.length > 1) {
                appendLine("  $k：${s.take(120)}")
                count++
            }
        }
    }.trim().ifBlank { "（无可读摘要）" }
}