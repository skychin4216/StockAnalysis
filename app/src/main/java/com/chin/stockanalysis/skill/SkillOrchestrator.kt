package com.chin.stockanalysis.skill

import android.util.Log

/**
 * ## Skill 执行引擎
 *
 * 在 AI 对话中，用户输入个股后，自动执行所有匹配的 Skill，
 * 返回结构化的选股分析结果，供：
 * 1. AI prompt 注入（让 AI 基于 Skill 分析回答）
 * 2. 模拟交易导入（SkillPick 可直接用于交易候选池）
 *
 * ### 数据流
 * ```
 * ChatTabFragment.sendMessage("兆易创新")
 *   ↓
 * StockQueryEngine.buildSystemPrompt()
 *   ↓
 * SkillOrchestrator.runSkills("603986", "兆易创新", userInput)
 *   ↓
 * 返回 List<SkillExecutionResult>
 *   ├─ 注入 AI prompt
 *   └─ 存入模拟交易可用数据
 * ```
 */
class SkillOrchestrator(private val skillEngine: SkillEngine) {

    companion object {
        private const val TAG = "SkillOrchestrator"
    }

    /**
     * 单个 Skill 对单只股票的执行结果
     */
    data class SkillExecutionResult(
        val skillId: String,
        val skillName: String,
        val stockCode: String,
        val stockName: String,
        /** 注入给 AI 的完整 prompt（含用户输入上下文） */
        val prompt: String,
        /** 结构化选股结果（供模拟交易使用，AI 分析后填充） */
        val picks: List<SkillPick> = emptyList(),
        val executionTimeMs: Long = 0L
    )

    /**
     * 结构化选股结果
     */
    data class SkillPick(
        val rank: Int,
        val stockCode: String,
        val stockName: String,
        /** 选股理由 */
        val reason: String,
        /** 信心度 0-1 */
        val confidence: Float = 0.5f,
        /** 来源 Skill ID */
        val sourceSkillId: String,
        /** 选股来源类型 */
        val pickSource: String = "ai_skill_pick"
    )

    /**
     * 对单只股票执行所有匹配的 Skill
     *
     * @param stockCode 股票代码（如 "603986"）
     * @param stockName 股票名称（如 "兆易创新"）
     * @param userInput 用户原始输入
     * @return 每个匹配 Skill 的执行结果列表
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
        Log.i(TAG, "   板块: ${sectors.ifEmpty { listOf("无") }.joinToString(", ")}")
        Log.i(TAG, "   用户输入: ${userInput.take(80)}")

        // 获取所有启用的自动触发 Skill
        val allSkills = skillEngine.getAll()
        val autoSkills = skillEngine.getAutoTriggerSkills()
        Log.i(TAG, "   已注册 Skill 总数: ${allSkills.size}, 自动触发: ${autoSkills.size}")
        allSkills.forEach { s ->
            Log.d(TAG, "     [${if (s.enabled) "✓" else "✗"}] ${s.id} (autoTrigger=${s.autoTrigger}) - ${s.name}")
        }

        // 也获取用户手动触发的 Skill（关键词匹配）
        val triggeredSkills = allSkills.filter { skill ->
            skill.enabled && skill.triggerPrompt != null &&
                skill.triggerPrompt.split(",").any { keyword ->
                    userInput.contains(keyword.trim(), ignoreCase = true)
                }
        }
        if (triggeredSkills.isNotEmpty()) {
            Log.i(TAG, "   关键词触发 Skill: ${triggeredSkills.joinToString { it.id }}")
        }

        // 合并去重
        val matchedSkills = (autoSkills + triggeredSkills).distinctBy { it.id }
        Log.i(TAG, "   匹配 Skill: ${matchedSkills.joinToString { it.id }.ifEmpty { "无" }}")

        if (matchedSkills.isEmpty()) {
            Log.d(TAG, "⚠️ 无匹配 Skill for $stockCode $stockName")
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
                        picks = emptyList(),  // AI 分析后由 parsePicksFromAiResponse() 填充
                        executionTimeMs = System.currentTimeMillis() - skillStart
                    )
                )
                // 记录使用次数
                skillEngine.recordUsage(skill.id)
                Log.i(TAG, "   ✅ ${skill.id} | prompt=${prompt.length}字 | ${System.currentTimeMillis() - skillStart}ms")
            } catch (e: Exception) {
                Log.w(TAG, "   ❌ Skill ${skill.id} 执行失败: ${e.message}")
            }
        }

        Log.i(TAG, "📊 完成 ${results.size} 个 Skill 执行 for $stockCode (${System.currentTimeMillis() - startTime}ms)")
        Log.i(TAG, "═══════════════════════════════════════")
        return results
    }

    /**
     * 处理「动态建立 Skill」意图
     *
     * 侦测使用者输入是否包含建立新技能的意图，
     * 若有则自动建立并持久化，返回结果讯息供 AI 回复。
     *
     * @return 建立结果的描述字串，若无意图则回传 null
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
                "- 触发关键词: ${intent.keywords.joinToString(", ")}\n" +
                "- 自动触发: 已启用\n\n" +
                "之后对话中提到相关关键词时，此技能会自动执行。"
        } else {
            "⚠️ 技能建立失败：ID「${intent.id}」已存在，请更换名称后再试。"
        }
    }

    /**
     * 将选股 Skill 结果格式化为 AI prompt 注入片段
     *
     * 过滤掉基础 Skill（早盘关注、尾盘异动等），只保留选股相关 Skill，
     * 每个选股 Skill 单独输出一个分析区块，方便 AI 逐一分析。
     */
    fun formatForPrompt(results: List<SkillExecutionResult>): String {
        // 过滤：只保留选股相关 Skill（排除 morning_check, afternoon_review 等）
        val pickingSkills = results.filter { it.skillId.startsWith("stock_picking_") }
        if (pickingSkills.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine("【精选选股分析】")
        sb.appendLine("以下为多套选股方法对当前股票的分析，请基于每种方法**逐一**给出详细的选股分析，并尽量表格对比。")
        sb.appendLine("如果是选股方法，请列出你的筛选过程和最终挑选的股票。")
        sb.appendLine()

        for ((index, result) in pickingSkills.withIndex()) {
            sb.appendLine("## Skill ${index + 1}: ${result.skillName}")
            sb.appendLine()
            sb.appendLine(result.prompt)
            sb.appendLine()
            sb.appendLine("---")
            sb.appendLine()
        }

        sb.appendLine("【输出要求】")
        sb.appendLine("1. 对每套选股方法，**单独**给出该方法的筛选过程和最终挑选的股票（含分析理由）")
        sb.appendLine("2. 如果有多种方法选出了相同的股票，请特别说明")
        sb.appendLine("3. 最后给出综合推荐排序（考虑买入信号、行业趋势、个股基本面等）")
        sb.appendLine()

        return sb.toString()
    }

    /**
     * 为单个 Skill 构建完整的 prompt
     */
    private fun buildSkillPrompt(
        skill: Skill,
        stockCode: String,
        stockName: String,
        userInput: String,
        sectors: List<String> = emptyList()
    ): String {
        val sb = StringBuilder()

        // Skill 的描述和目标
        sb.appendLine("分析目标: 使用「${skill.name}」方法分析「$stockName ($stockCode)」")

        // 注入板块信息
        if (sectors.isNotEmpty()) {
            sb.appendLine("所属板块: ${sectors.joinToString(", ")}")
            sb.appendLine()
        }

        // Skill 的所有 prompt
        for ((i, prompt) in skill.prompts.withIndex()) {
            if (skill.prompts.size > 1) {
                sb.appendLine("### Step ${i + 1}")
            }
            // 替换提示中的占位符
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