package com.chin.stockanalysis.skill

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.chin.stockanalysis.skill.stock_picking.SkillConfigLoader
import org.json.JSONArray
import org.json.JSONObject

/**
 * ## 技能引擎
 *
 * 管理 Skill 的 CRUD 和持久化，架构：
 *
 * - **设定档** (assets/skills_config.json + files/skills_dynamic.json)
 *   → Skill 定义（id, name, prompts, keywords）
 * - **SharedPreferences** (skill_prefs)
 *   → 执行时状态（enabled, usageCount, createdAt）
 *
 * 两者在 init 时合并：设定档提供定义，SharedPreferences 覆盖状态。
 */
class SkillEngine(private val context: Context) {

    companion object {
        private const val TAG = "SkillEngine"
        private const val PREFS_KEY = "skills_json"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("skill_prefs", Context.MODE_PRIVATE)

    private val skills = mutableMapOf<String, Skill>()

    init {
        loadFromPrefs()
        // 每次启动都同步设定档，确保新增的 Skill 能自动载入
        syncFromConfig()
    }

    // ── CRUD ──

    fun register(skill: Skill) {
        skills[skill.id] = skill
        saveToPrefs()
        Log.i(TAG, "注册技能: ${skill.id} - ${skill.name}")
    }

    fun remove(id: String): Boolean {
        val removed = skills.remove(id) != null
        if (removed) saveToPrefs()
        return removed
    }

    fun setEnabled(id: String, enabled: Boolean) {
        skills[id]?.let { register(it.copy(enabled = enabled)) }
    }

    fun get(id: String): Skill? = skills[id]
    fun getAll(): List<Skill> = skills.values.toList()
    fun getEnabled(): List<Skill> = skills.values.filter { it.enabled }
    fun getBySource(source: SkillSource): List<Skill> = skills.values.filter { it.source == source }
    fun getAutoTriggerSkills(): List<Skill> = skills.values.filter { it.enabled && it.autoTrigger }

    fun recordUsage(id: String) {
        skills[id]?.let { register(it.copy(usageCount = it.usageCount + 1)) }
    }

    /**
     * 动态建立 Skill
     *
     * 同时写入：
     * 1. 记忆体 (skills map)
     * 2. SharedPreferences (runtime state)
     * 3. skills_dynamic.json (定义持久化，重启后自动复原)
     *
     * @return 新建的 Skill，若 ID 冲突则回传 null
     */
    fun createDynamicSkill(
        id: String,
        name: String,
        description: String,
        keywords: List<String>,
        icon: String = "📝",
        prompts: List<String>
    ): Skill? {
        if (skills.containsKey(id)) {
            Log.w(TAG, "Skill ID $id 已存在，无法动态建立")
            return null
        }
        val skill = Skill(
            id = id,
            name = name,
            icon = icon,
            description = description,
            prompts = prompts,
            autoTrigger = true,
            triggerPrompt = keywords.joinToString(", "),
            source = SkillSource.USER_CREATED,
            createdAt = System.currentTimeMillis()
        )
        register(skill)

        // 持久化到 skills_dynamic.json（重启后仍存在）
        try {
            val config = SkillConfigLoader.SkillConfig(
                id = skill.id,
                name = skill.name,
                icon = skill.icon,
                description = skill.description,
                keywords = keywords,
                systemPrompt = prompts.getOrElse(0) { "" },
                quickPrompt = prompts.getOrElse(1) { prompts.getOrElse(0) { "" } },
                autoTrigger = skill.autoTrigger
            )
            SkillConfigLoader.addDynamicSkill(context, config)
            Log.i(TAG, "🆕 动态 Skill 已存入设定档: ${skill.id}")
        } catch (e: Exception) {
            Log.w(TAG, "动态 Skill 设定档写入失败: ${e.message}")
        }

        return skill
    }

    // ── 持久化 ──

    private fun loadFromPrefs() {
        val json = prefs.getString(PREFS_KEY, null) ?: return
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val skill = Skill(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    icon = obj.optString("icon", "🎯"),
                    description = obj.optString("desc", ""),
                    prompts = (0 until obj.getJSONArray("prompts").length()).map { obj.getJSONArray("prompts").getString(it) },
                    autoTrigger = obj.optBoolean("auto", false),
                    triggerTime = obj.optString("time", null),
                    triggerPrompt = obj.optString("trigger", null),
                    source = SkillSource.valueOf(obj.optString("source", "USER_CREATED")),
                    enabled = obj.optBoolean("enabled", true),
                    usageCount = obj.optInt("usage", 0),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                )
                skills[skill.id] = skill
            }
        } catch (e: Exception) {
            Log.w(TAG, "加载技能失败: ${e.message}")
        }
    }

    private fun saveToPrefs() {
        val arr = JSONArray()
        for (skill in skills.values) {
            val obj = JSONObject().apply {
                put("id", skill.id)
                put("name", skill.name)
                put("icon", skill.icon)
                put("desc", skill.description)
                put("prompts", JSONArray(skill.prompts))
                put("auto", skill.autoTrigger)
                if (skill.triggerTime != null) put("time", skill.triggerTime)
                if (skill.triggerPrompt != null) put("trigger", skill.triggerPrompt)
                put("source", skill.source.name)
                put("enabled", skill.enabled)
                put("usage", skill.usageCount)
                put("createdAt", skill.createdAt)
            }
            arr.put(obj)
        }
        prefs.edit().putString(PREFS_KEY, arr.toString()).apply()
    }

    // ── 设定档同步 + 预设 Skill ──

    /**
     * 每次启动时同步设定档中的 Skill 定义
     *
     * 与旧版 `registerDefaults()` 不同，此方法**始终执行**（不限于首次启动）：
     * 1. 确保硬编码基础 Skill（早盘、尾盘）始终存在
     * 2. 从设定档载入所有选股 Skill，对已存在于 SharedPreferences 的 Skill 保留其状态
     *    （enabled / usageCount / createdAt），仅补充分不存在的新 Skill
     * 3. 设定档载入失败时 fallback 到硬编码 Skill
     */
    private fun syncFromConfig() {
        // 确保硬编码基础 Skill 始终存在（若已由 SharedPreferences 载入则保留状态）
        val existingIds = skills.keys
        if ("morning_check" !in existingIds) {
            register(Skill(
                id = "morning_check",
                name = "每日早盘关注",
                icon = "🌅",
                description = "查看自选股价格+ETF竞价+板块热度",
                prompts = listOf("查看我的自选股最新价格", "哪些板块今天竞价最强"),
                autoTrigger = true,
                triggerTime = "09:00-09:30",
                triggerPrompt = "查看今日早盘关注",
                source = SkillSource.AUTO_GENERATED
            ))
        }
        if ("afternoon_review" !in existingIds) {
            register(Skill(
                id = "afternoon_review",
                name = "尾盘异动监控",
                icon = "🔔",
                description = "尾盘15分钟内涨跌幅超过3%的股票",
                prompts = listOf("尾盘异动股票有哪些"),
                autoTrigger = true,
                triggerTime = "14:45-15:00",
                triggerPrompt = "查看尾盘异动",
                source = SkillSource.AUTO_GENERATED
            ))
        }

        // 从设定档载入选股 Skill（内建 + 动态）
        try {
            val allConfigs = SkillConfigLoader.loadAllSkills(context)
            var newCount = 0
            var existCount = 0
            for (config in allConfigs) {
                if (config.id !in skills) {
                    // 新 Skill：从设定档载入定义
                    val skill = config.toSkill()
                    register(skill)
                    newCount++
                    Log.i(TAG, "  📄 新增: ${config.id} (${if (config.isDynamic) "动态" else "内建"})")
                } else {
                    // 已存在的 Skill：保留 SharedPreferences 中的状态，仅更新定义
                    val existing = skills[config.id]!!
                    val updatedPrompt = config.toSkill().prompts
                    val updatedTrigger = config.toSkill().triggerPrompt
                    val updatedName = config.toSkill().name
                    val updatedDesc = config.toSkill().description
                    val updatedIcon = config.toSkill().icon
                    val needsUpdate = existing.prompts != updatedPrompt ||
                        existing.triggerPrompt != updatedTrigger ||
                        existing.name != updatedName ||
                        existing.description != updatedDesc ||
                        existing.icon != updatedIcon
                    if (needsUpdate) {
                        val refreshed = existing.copy(
                            prompts = updatedPrompt,
                            triggerPrompt = updatedTrigger,
                            name = updatedName,
                            description = updatedDesc,
                            icon = updatedIcon
                        )
                        skills[config.id] = refreshed
                        saveToPrefs()
                        Log.i(TAG, "  🔄 更新: ${config.id} (定义已变更)")
                    }
                    existCount++
                }
            }
            Log.i(TAG, "📋 同步完成: ${existCount}个保留 + ${newCount}个新增 (来自设定档)")
        } catch (e: Exception) {
            Log.w(TAG, "从设定档载入 Skill 失败: ${e.message}", e)
        }
    }
}