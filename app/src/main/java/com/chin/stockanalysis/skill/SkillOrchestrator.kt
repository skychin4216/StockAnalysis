package com.chin.stockanalysis.skill

import android.util.Log

/**
 * ## Skill 執行引擎
 *
 * 在 AI 對話中，用戶輸入個股後，自動執行所有匹配的 Skill，
 * 返回結構化的選股分析結果，供：
 * 1. AI prompt 注入（讓 AI 基於 Skill 分析回答）
 * 2. 模擬交易導入（SkillPick 可直接用於交易候選池）
 *
 * ### 數據流
 * ```
 * ChatTabFragment.sendMessage("兆易創新")
 *   ↓
 * StockQueryEngine.buildSystemPrompt()
 *   ↓
 * SkillOrchestrator.runSkills("603986", "兆易創新", userInput)
 *   ↓
 * 返回 List<SkillExecutionResult>
 *   ├─ 注入 AI prompt
 *   └─ 存入模擬交易可用數據
 * ```
 */
class SkillOrchestrator(private val skillEngine: SkillEngine) {

    companion object {
        private const val TAG = "SkillOrchestrator"
    }

    /**
     * 單個 Skill 對單只股票的執行結果
     */
    data class SkillExecutionResult(
        val skillId: String,
        val skillName: String,
        val stockCode: String,
        val stockName: String,
        /** 注入給 AI 的完整 prompt（含用戶輸入上下文） */
        val prompt: String,
        /** 結構化選股結果（供模擬交易使用，AI 分析後填充） */
        val picks: List<SkillPick> = emptyList(),
        val executionTimeMs: Long = 0L
    )

    /**
     * 結構化選股結果
     */
    data class SkillPick(
        val rank: Int,
        val stockCode: String,
        val stockName: String,
        /** 選股理由 */
        val reason: String,
        /** 信心度 0-1 */
        val confidence: Float = 0.5f,
        /** 來源 Skill ID */
        val sourceSkillId: String,
        /** 選股來源類型 */
        val pickSource: String = "ai_skill_pick"
    )

    /**
     * 對單只股票執行所有匹配的 Skill
     *
     * @param stockCode 股票代碼（如 "603986"）
     * @param stockName 股票名稱（如 "兆易創新"）
     * @param userInput 用戶原始輸入
     * @return 每個匹配 Skill 的執行結果列表
     */
    suspend fun runSkills(
        stockCode: String,
        stockName: String,
        userInput: String = "",
        sectors: List<String> = emptyList()
    ): List<SkillExecutionResult> {
        val startTime = System.currentTimeMillis()
        val results = mutableListOf<SkillExecutionResult>()
        Log.i(TAG, "═══════════════════════════════════════")
        Log.i(TAG, "🚀 SkillOrchestrator.runSkills()")
        Log.i(TAG, "   股票: $stockName ($stockCode)")
        Log.i(TAG, "   板塊: ${sectors.ifEmpty { listOf("無") }.joinToString(", ")}")
        Log.i(TAG, "   用戶輸入: ${userInput.take(80)}")

        // 獲取所有啟用的自動觸發 Skill
        val allSkills = skillEngine.getAll()
        val autoSkills = skillEngine.getAutoTriggerSkills()
        Log.i(TAG, "   已註冊 Skill 總數: ${allSkills.size}, 自動觸發: ${autoSkills.size}")
        allSkills.forEach { s ->
            Log.d(TAG, "     [${if (s.enabled) "✓" else "✗"}] ${s.id} (autoTrigger=${s.autoTrigger}) - ${s.name}")
        }

        // 也獲取用戶手動觸發的 Skill（關鍵詞匹配）
        val triggeredSkills = allSkills.filter { skill ->
            skill.enabled && skill.triggerPrompt != null &&
                skill.triggerPrompt.split(",").any { keyword ->
                    userInput.contains(keyword.trim(), ignoreCase = true)
                }
        }
        if (triggeredSkills.isNotEmpty()) {
            Log.i(TAG, "   關鍵詞觸發 Skill: ${triggeredSkills.joinToString { it.id }}")
        }

        // 合併去重
        val matchedSkills = (autoSkills + triggeredSkills).distinctBy { it.id }
        Log.i(TAG, "   匹配 Skill: ${matchedSkills.joinToString { it.id }.ifEmpty { "無" }}")

        if (matchedSkills.isEmpty()) {
            Log.d(TAG, "⚠️ 無匹配 Skill for $stockCode $stockName")
            Log.i(TAG, "═══════════════════════════════════════")
            return emptyList()
        }

        for (skill in matchedSkills) {
            try {
                val skillStart = System.currentTimeMillis()
                val prompt = buildSkillPrompt(
                    skill = skill,
                    stockCode = stockCode,
                    stockName = stockName,
                    userInput = userInput,
                    sectors = sectors
                )
                results.add(
                    SkillExecutionResult(
                        skillId = skill.id,
                        skillName = skill.name,
                        stockCode = stockCode,
                        stockName = stockName,
                        prompt = prompt,
                        picks = emptyList(),  // AI 分析後由 parsePicksFromAiResponse() 填充
                        executionTimeMs = System.currentTimeMillis() - skillStart
                    )
                )
                // 記錄使用次數
                skillEngine.recordUsage(skill.id)
                Log.i(TAG, "   ✅ ${skill.id} | prompt=${prompt.length}字 | ${System.currentTimeMillis() - skillStart}ms")
            } catch (e: Exception) {
                Log.w(TAG, "   ❌ Skill ${skill.id} 執行失敗: ${e.message}")
            }
        }

        Log.i(TAG, "📊 完成 ${results.size} 個 Skill 執行 for $stockCode (${System.currentTimeMillis() - startTime}ms)")
        Log.i(TAG, "═══════════════════════════════════════")
        return results
    }

    /**
     * 處理「動態建立 Skill」意圖
     *
     * 偵測使用者輸入是否包含建立新技能的意圖，
     * 若有則自動建立並持久化，返回結果訊息供 AI 回覆。
     *
     * @return 建立結果的描述字串，若無意圖則回傳 null
     */
    fun handleDynamicSkillCreation(userInput: String): String? {
        val intent = com.chin.stockanalysis.skill.stock_picking.SkillIntentDetector.detect(userInput)
            ?: return null

        val created = skillEngine.createDynamicSkill(
            id = intent.id,
            name = intent.name,
            description = intent.description,
            keywords = intent.keywords,
            icon = intent.icon,
            prompts = intent.prompts
        )

        return if (created != null) {
            "✅ 已建立新技能「${created.name}」\n" +
                "- ID: ${created.id}\n" +
                "- 描述: ${created.description}\n" +
                "- 觸發關鍵詞: ${intent.keywords.joinToString(", ")}\n" +
                "- 自動觸發: 已啟用\n\n" +
                "之後對話中提到相關關鍵詞時，此技能會自動執行。"
        } else {
            "⚠️ 技能建立失敗：ID「${intent.id}」已存在，請更換名稱後再試。"
        }
    }

    /**
     * 將 Skill 結果格式化為 AI prompt 注入片段
     */
    fun formatForPrompt(results: List<SkillExecutionResult>): String {
        if (results.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine("【精选选股分析】")
        sb.appendLine("以下为多套选股方法对当前股票的分析，请基于每种方法逐一给出详细的选股分析，并尽量表格对比。")
        sb.appendLine("如果是选股方法，请列出你的筛选过程和最终挑选的股票。")
        sb.appendLine()

        for ((index, result) in results.withIndex()) {
            sb.appendLine("## ${index + 1}. ${result.skillName}")
            sb.appendLine()
            sb.appendLine(result.prompt)
            sb.appendLine()
            sb.appendLine("---")
            sb.appendLine()
        }

        sb.appendLine("【输出要求】")
        sb.appendLine("1. 对每套方法，给出该方法的筛选过程和最终挑选的股票（含分析理由）")
        sb.appendLine("2. 如果有多种方法选出了相同的股票，请特别说明")
        sb.appendLine("3. 最后给出综合推荐排序（考虑买入信号、行业趋势、个股基本面等）")
        sb.appendLine()

        return sb.toString()
    }

    /**
     * 為單個 Skill 構建完整的 prompt
     */
    private fun buildSkillPrompt(
        skill: Skill,
        stockCode: String,
        stockName: String,
        userInput: String,
        sectors: List<String> = emptyList()
    ): String {
        val sb = StringBuilder()

        // Skill 的描述和目標
        sb.appendLine("分析目标: 使用「${skill.name}」方法分析「$stockName ($stockCode)」")

        // 注入板塊信息
        if (sectors.isNotEmpty()) {
            sb.appendLine("所属板块: ${sectors.joinToString(", ")}")
            sb.appendLine()
        }

        // Skill 的所有 prompt
        for ((i, prompt) in skill.prompts.withIndex()) {
            if (skill.prompts.size > 1) {
                sb.appendLine("### Step ${i + 1}")
            }
            // 替換提示中的佔位符
            val resolvedPrompt = prompt
                .replace("{stockCode}", stockCode)
                .replace("{stockName}", stockName)
                .replace("{userInput}", userInput)
            sb.appendLine(resolvedPrompt)
            sb.appendLine()
        }

        return sb.toString()
    }
}