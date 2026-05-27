package com.chin.stockanalysis.skill

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * ## 技能引擎
 *
 * 管理 Skill 的 CRUD 和持久化（SharedPreferences JSON），
 * 以及自动触发逻辑。
 */
class SkillEngine(context: Context) {

    companion object {
        private const val TAG = "SkillEngine"
        private const val PREFS_KEY = "skills_json"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("skill_prefs", Context.MODE_PRIVATE)

    private val skills = mutableMapOf<String, Skill>()

    init {
        loadFromPrefs()
        if (skills.isEmpty()) registerDefaults()
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

    // ── 内置默认 Skill ──

    private fun registerDefaults() {
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
}