package com.chin.stockanalysis.skill.stock_picking

import android.util.Log

/**
 * ## 技能意圖偵測器
 *
 * 偵測使用者是否有「動態建立 Skill」的意圖，並解析出結構化參數。
 * 支援以下輸入格式：
 *
 * ### 格式 1: 自然語言
 * ```
 * 新增一個選股技能：名稱為"均線金叉篩選"，觸發關鍵詞為"均線,金叉,MA"，提示詞為：請根據5日均線上穿20日均線篩選股票
 * ```
 *
 * ### 格式 2: 結構化
 * ```
 * /create-skill name=均線金叉篩選 keywords=均線,金叉,MA prompt=根據5日均線上穿20日均線篩選股票 desc=使用雙均線策略篩選
 * ```
 */
object SkillIntentDetector {

    private const val TAG = "SkillIntentDetector"

    /** 解析成功的 Skill 參數 */
    data class SkillIntent(
        val id: String,
        val name: String,
        val description: String,
        val keywords: List<String>,
        val prompts: List<String>,
        val icon: String = "📝"
    )

    // 命令前綴（格式2: 結構化指令）
    private val COMMAND_PREFIX = "/create-skill"

    // 觸發關鍵詞（格式1: 自然語言）
    private val triggerPatterns = listOf(
        "新增.*技能", "创建.*技能", "建立.*技能", "添加.*技能",
        "新建.*skill", "create.*skill", "add.*skill",
        "新增選股技能", "新增选股技能"
    )

    /**
     * 偵測使用者輸入是否包含「動態建立 Skill」意圖
     *
     * @return 解析後的 SkillIntent，若無意圖則回傳 null
     */
    fun detect(userInput: String): SkillIntent? {
        val trimmed = userInput.trim()

        // 優先嘗試結構化指令 (格式2)
        if (trimmed.startsWith(COMMAND_PREFIX, ignoreCase = true)) {
            return parseCommand(trimmed)
        }

        // 嘗試自然語言 (格式1)
        if (triggerPatterns.any { trimmed.contains(Regex(it, RegexOption.IGNORE_CASE)) }) {
            return parseNaturalLanguage(trimmed)
        }

        return null
    }

    /**
     * 解析結構化指令: /create-skill name=xxx keywords=a,b,c prompt=xxx desc=xxx
     */
    private fun parseCommand(input: String): SkillIntent? {
        try {
            val params = mutableMapOf<String, String>()
            // 移除指令前綴後按 key=value 解析
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
            Log.w(TAG, "解析結構化指令失敗: ${e.message}")
            return null
        }
    }

    /**
     * 解析自然語言輸入
     */
    private fun parseNaturalLanguage(input: String): SkillIntent? {
        try {
            // 提取「名稱為...」
            val nameRegex = Regex("""名[称为稱][为為]?[：:""\s]*([^，,。.\n]*)""")
            val nameMatch = nameRegex.find(input)
            val name = nameMatch?.groupValues?.get(1)?.trim()?.removeSurrounding("\"")
                ?.removeSurrounding("「")?.removeSurrounding("」")
            if (name.isNullOrBlank()) return null

            // 提取「觸發關鍵詞為...」
            val keywordRegex = Regex("""(?:觸[发發]|触发|关键|關鍵)詞?[为為]?[：:""\s]*([^，,。.\n]*)""")
            val keywordMatch = keywordRegex.find(input)
            val keywordsRaw = keywordMatch?.groupValues?.get(1)?.trim()
            val keywords = keywordsRaw?.split(Regex("[,，、\\s]+"))?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

            // 提取「提示詞為...」或「prompt為...」
            val promptRegex = Regex("""(?:提示[词詞]|prompt)[为為]?[：:""\s]*(.+)""", RegexOption.IGNORE_CASE)
            val promptMatch = promptRegex.find(input)
            val prompt = promptMatch?.groupValues?.get(1)?.trim()
            if (prompt.isNullOrBlank()) return null

            // 提取「描述為...」
            val descRegex = Regex("""(?:描述|说明|說明)[为為]?[：:""\s]*([^，,。.\n]*)""")
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
            Log.w(TAG, "解析自然語言失敗: ${e.message}")
            return null
        }
    }

    /**
     * 根據名稱生成 Skill ID
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