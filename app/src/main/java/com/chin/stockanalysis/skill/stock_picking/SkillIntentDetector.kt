package com.chin.stockanalysis.skill.stock_picking

import android.util.Log

/**
 * ## 技能意图侦测器
 *
 * 侦测使用者是否有「动态建立 Skill」的意图，并解析出结构化参数。
 * 支援以下输入格式：
 *
 * ### 格式 1: 自然语言
 * ```
 * 新增一个选股技能：名称为"均线金叉筛选"，触发关键词为"均线,金叉,MA"，提示词为：请根据5日均线上穿20日均线筛选股票
 * ```
 *
 * ### 格式 2: 结构化
 * ```
 * /create-skill name=均线金叉筛选 keywords=均线,金叉,MA prompt=根据5日均线上穿20日均线筛选股票 desc=使用双均线策略筛选
 * ```
 */
object SkillIntentDetector {

    private const val TAG = "SkillIntentDetector"

    /** 解析成功的 Skill 参数 */
    data class SkillIntent(
        val id: String,
        val name: String,
        val description: String,
        val keywords: List<String>,
        val prompts: List<String>,
        val icon: String = "📝"
    )

    // 命令前缀（格式2: 结构化指令）
    private val COMMAND_PREFIX = "/create-skill"

    // 触发关键词（格式1: 自然语言）
    private val triggerPatterns = listOf(
        "新增.*技能", "创建.*技能", "建立.*技能", "添加.*技能",
        "新建.*skill", "create.*skill", "add.*skill",
        "新增选股技能", "新增选股技能"
    )

    /**
     * 侦测使用者输入是否包含「动态建立 Skill」意图
     *
     * @return 解析后的 SkillIntent，若无意图则回传 null
     */
    fun detect(userInput: String): SkillIntent? {
        val trimmed = userInput.trim()

        // 优先尝试结构化指令 (格式2)
        if (trimmed.startsWith(COMMAND_PREFIX, ignoreCase = true)) {
            return parseCommand(trimmed)
        }

        // 尝试自然语言 (格式1)
        if (triggerPatterns.any { trimmed.contains(Regex(it, RegexOption.IGNORE_CASE)) }) {
            return parseNaturalLanguage(trimmed)
        }

        return null
    }

    /**
     * 解析结构化指令: /create-skill name=xxx keywords=a,b,c prompt=xxx desc=xxx
     */
    private fun parseCommand(input: String): SkillIntent? {
        try {
            val params = mutableMapOf<String, String>()
            // 移除指令前缀后按 key=value 解析
            val content = input.removePrefix(COMMAND_PREFIX).trimStart()
            val regex = Regex("""(\w+)=("([^"]*)"|([^\s]+))""")
            for (match in regex.findAll(content)) {
                val key = match.groupValues[1].lowercase()
                val value = match.groupValues[3].ifEmpty { match.groupValues[4] }
                params[key] = value
            }

            val name = params["name"] ?: return null
            val prompt = params["prompt"] ?: return null
            val keywords = params["keywords"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            val desc = params["desc"] ?: params["description"] ?: name
            val id = params["id"] ?: generateSkillId(name)

            return SkillIntent(
                id = id,
                name = name,
                description = desc,
                keywords = keywords,
                prompts = listOf(prompt)
            )
        } catch (e: Exception) {
            Log.w(TAG, "解析结构化指令失败: ${e.message}")
            return null
        }
    }

    /**
     * 解析自然语言输入
     */
    private fun parseNaturalLanguage(input: String): SkillIntent? {
        try {
            // 提取「名称为...」
            val nameRegex = Regex("""名[称为称][为为]?[：:""\s]*([^，,。.\n]*)""")
            val nameMatch = nameRegex.find(input)
            val name = nameMatch?.groupValues?.get(1)?.trim()?.removeSurrounding("\"")
                ?.removeSurrounding("「")?.removeSurrounding("」")
            if (name.isNullOrBlank()) return null

            // 提取「触发关键词为...」
            val keywordRegex = Regex("""(?:触[发发]|触发|关键|关键)词?[为为]?[：:""\s]*([^，,。.\n]*)""")
            val keywordMatch = keywordRegex.find(input)
            val keywordsRaw = keywordMatch?.groupValues?.get(1)?.trim()
            val keywords = keywordsRaw?.split(Regex("[,，、\\s]+"))?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

            // 提取「提示词为...」或「prompt为...」
            val promptRegex = Regex("""(?:提示[词词]|prompt)[为为]?[：:""\s]*(.+)""", RegexOption.IGNORE_CASE)
            val promptMatch = promptRegex.find(input)
            val prompt = promptMatch?.groupValues?.get(1)?.trim()
            if (prompt.isNullOrBlank()) return null

            // 提取「描述为...」
            val descRegex = Regex("""(?:描述|说明|说明)[为为]?[：:""\s]*([^，,。.\n]*)""")
            val descMatch = descRegex.find(input)
            val description = descMatch?.groupValues?.get(1)?.trim() ?: name

            val id = generateSkillId(name)

            return SkillIntent(
                id = id,
                name = name,
                description = description,
                keywords = keywords,
                prompts = listOf(prompt)
            )
        } catch (e: Exception) {
            Log.w(TAG, "解析自然语言失败: ${e.message}")
            return null
        }
    }

    /**
     * 根据名称生成 Skill ID
     */
    private fun generateSkillId(name: String): String {
        val base = name
            .lowercase()
            .replace(Regex("[^a-z0-9\\u4e00-\\u9fff]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
        return "user_${base}_${System.currentTimeMillis() % 100000}"
    }
}