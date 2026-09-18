package com.chin.stockanalysis.skill.stock_picking

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.skill.Skill
import com.chin.stockanalysis.skill.SkillSource
import org.json.JSONArray
import org.json.JSONObject

/**
 * ## Skill 设定档载入器
 *
 * 从 JSON 设定档载入 Skill 定义，支援两个来源：
 * 1. **内建设定** → `assets/skills_config.json`（随 App 发布，唯读）
 * 2. **动态设定** → 内部储存 `skills_dynamic.json`（AI 对话动态建立，可读写）
 *
 * 使用 `{stockCode}` 作为 prompt 中的占位符，执行时自动替换。
 *
 * ### 新增 Skill 方式
 *
 * **方式 A: 修改 assets/skills_config.json**（需重新编译）
 * ```json
 * {
 *   "skills": [
 *     { "id": "my_skill", "name": "我的技巧", "keywords": ["关键词"], ... }
 *   ]
 * }
 * ```
 *
 * **方式 B: AI 对话动态建立**（无需编译，重启后保留）
 * 在对话中输入：`新增一个选股技能：名称为"xxx"，触发关键词为"a,b,c"，提示词为：...`
 */
object SkillConfigLoader {

    private const val TAG = "SkillConfigLoader"
    private const val BUILTIN_CONFIG = "skills_config.json"
    private const val DYNAMIC_CONFIG = "skills_dynamic.json"

    // ═══════════════════════════════════════
    // 资料模型
    // ═══════════════════════════════════════

    /** 从 JSON 解析出的 Skill 设定 */
    data class SkillConfig(
        val id: String,
        val name: String,
        val icon: String = "🎯",
        val description: String = "",
        val keywords: List<String> = emptyList(),
        val systemPrompt: String = "",
        val quickPrompt: String = "",
        val autoTrigger: Boolean = true,
        val isDynamic: Boolean = false  // true = 来自动态设定, false = 内建
    ) {
        /** 转换为 Skill 资料物件 */
        fun toSkill(): Skill {
            return Skill(
                id = id,
                name = name,
                icon = icon,
                description = description,
                prompts = listOf(systemPrompt, quickPrompt),
                autoTrigger = autoTrigger,
                triggerPrompt = keywords.joinToString(", "),
                source = SkillSource.USER_CREATED
            )
        }

        /** 将 prompt 中的占位符替换为实际值 */
        fun getFullPrompt(stockCode: String? = null): String {
            return if (stockCode != null) {
                systemPrompt.replace("{stockCode}", stockCode)
            } else {
                systemPrompt
            }
        }

    }

    // ═══════════════════════════════════════
    // 公开 API
    // ═══════════════════════════════════════

    /**
     * 从 assets 载入内建 Skill 设定
     */
    fun loadBuiltinSkills(context: Context): List<SkillConfig> {
        return try {
            val json = context.assets.open(BUILTIN_CONFIG).bufferedReader().use { it.readText() }
            val configs = parseJson(json)
            Log.i(TAG, "载入内建 Skill: ${configs.size}个")
            configs
        } catch (e: Exception) {
            Log.w(TAG, "载入内建 Skill 设定失败: ${e.message}")
            emptyList()
        }
    }

    /**
     * 载入动态建立的 Skill 设定（内部储存）
     */
    fun loadDynamicSkills(context: Context): List<SkillConfig> {
        return try {
            val file = java.io.File(context.filesDir, DYNAMIC_CONFIG)
            if (!file.exists()) {
                Log.d(TAG, "无动态 Skill 设定档")
                return emptyList()
            }
            val json = file.readText()
            val configs = parseJson(json).map { it.copy(isDynamic = true) }
            Log.i(TAG, "载入动态 Skill: ${configs.size}个")
            configs
        } catch (e: Exception) {
            Log.w(TAG, "载入动态 Skill 设定失败: ${e.message}")
            emptyList()
        }
    }

    /**
     * 载入所有 Skill（内建 + 动态），合并去重（动态优先）
     */
    fun loadAllSkills(context: Context): List<SkillConfig> {
        val builtin = loadBuiltinSkills(context)
        val dynamic = loadDynamicSkills(context)
        val dynamicIds = dynamic.map { it.id }.toSet()
        val filteredBuiltin = builtin.filter { it.id !in dynamicIds }
        val all = filteredBuiltin + dynamic
        Log.i(TAG, "合并 Skill 设定: 内建${filteredBuiltin.size} + 动态${dynamic.size} = ${all.size}个")
        return all
    }

    /**
     * 保存动态 Skill 设定到内部储存
     */
    fun saveDynamicSkills(context: Context, configs: List<SkillConfig>) {
        try {
            val json = configsToJson(configs)
            val file = java.io.File(context.filesDir, DYNAMIC_CONFIG)
            file.writeText(json)
            Log.i(TAG, "保存动态 Skill: ${configs.size}个 → $DYNAMIC_CONFIG")
        } catch (e: Exception) {
            Log.w(TAG, "保存动态 Skill 设定失败: ${e.message}")
        }
    }

    /**
     * 新增一个动态 Skill（读取 → 添加 → 保存）
     * @return 新增成功回传 true，ID 冲突回传 false
     */
    fun addDynamicSkill(context: Context, config: SkillConfig): Boolean {
        val existing = loadDynamicSkills(context)
        if (existing.any { it.id == config.id }) {
            Log.w(TAG, "动态 Skill ID ${config.id} 已存在")
            return false
        }
        val updated = existing + config.copy(isDynamic = true)
        saveDynamicSkills(context, updated)
        return true
    }

    /**
     * 删除一个动态 Skill
     */
    fun removeDynamicSkill(context: Context, skillId: String): Boolean {
        val existing = loadDynamicSkills(context)
        val filtered = existing.filter { it.id != skillId }
        if (filtered.size == existing.size) return false
        saveDynamicSkills(context, filtered)
        return true
    }

    // ═══════════════════════════════════════
    // JSON 解析 / 序列化
    // ═══════════════════════════════════════

    private fun parseJson(json: String): List<SkillConfig> {
        val root = JSONObject(json)
        val arr = root.getJSONArray("skills")
        val configs = mutableListOf<SkillConfig>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val keywordsJa = obj.optJSONArray("keywords")
            val keywords = if (keywordsJa != null) {
                (0 until keywordsJa.length()).map { keywordsJa.getString(it) }
            } else emptyList()

            configs.add(SkillConfig(
                id = obj.getString("id"),
                name = obj.getString("name"),
                icon = obj.optString("icon", "🎯"),
                description = obj.optString("description", ""),
                keywords = keywords,
                systemPrompt = obj.optString("systemPrompt", obj.optString("fullPrompt", "")),
                quickPrompt = obj.optString("quickPrompt", ""),
                autoTrigger = obj.optBoolean("autoTrigger", true)
            ))
        }
        return configs
    }

    private fun configsToJson(configs: List<SkillConfig>): String {
        val root = JSONObject()
        val arr = JSONArray()
        for (config in configs) {
            val obj = JSONObject().apply {
                put("id", config.id)
                put("name", config.name)
                put("icon", config.icon)
                put("description", config.description)
                put("keywords", JSONArray(config.keywords))
                put("systemPrompt", config.systemPrompt)
                put("quickPrompt", config.quickPrompt)
                put("autoTrigger", config.autoTrigger)
            }
            arr.put(obj)
        }
        root.put("skills", arr)
        return root.toString(2)  // Pretty-print 方便手动编辑
    }
}