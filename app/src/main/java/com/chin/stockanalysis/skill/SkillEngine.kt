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
 * 管理 Skill 的 CRUD 和持久化，架構：
 *
 * - **設定檔** (assets/skills_config.json + files/skills_dynamic.json)
 *   → Skill 定義（id, name, prompts, keywords）
 * - **SharedPreferences** (skill_prefs)
 *   → 執行時狀態（enabled, usageCount, createdAt）
 *
 * 兩者在 init 時合併：設定檔提供定義，SharedPreferences 覆蓋狀態。
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
        // 每次啟動都同步設定檔，確保新增的 Skill 能自動載入
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
     * 動態建立 Skill
     *
     * 同時寫入：
     * 1. 記憶體 (skills map)
     * 2. SharedPreferences (runtime state)
     * 3. skills_dynamic.json (定義持久化，重啟後自動復原)
     *
     * @return 新建的 Skill，若 ID 衝突則回傳 null
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
            Log.w(TAG, "Skill ID $id 已存在，無法動態建立")
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

        // 持久化到 skills_dynamic.json（重啟後仍存在）
        try {
            val config = SkillConfigLoader.SkillConfig(
                id = skill.id,
                name = skill.name,
                icon = skill.icon,
                description = skill.description,
                keywords = keywords,
                fullPrompt = prompts.getOrElse(0) { "" },
                quickPrompt = prompts.getOrElse(1) { prompts.getOrElse(0) { "" } },
                autoTrigger = skill.autoTrigger
            )
            SkillConfigLoader.addDynamicSkill(context, config)
            Log.i(TAG, "🆕 動態 Skill 已存入設定檔: ${skill.id}")
        } catch (e: Exception) {
            Log.w(TAG, "動態 Skill 設定檔寫入失敗: ${e.message}")
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

    // ── 設定檔同步 + 預設 Skill ──

    /**
     * 每次啟動時同步設定檔中的 Skill 定義
     *
     * 與舊版 `registerDefaults()` 不同，此方法**始終執行**（不限於首次啟動）：
     * 1. 確保硬編碼基礎 Skill（早盤、尾盤）始終存在
     * 2. 從設定檔載入所有選股 Skill，對已存在於 SharedPreferences 的 Skill 保留其狀態
     *    （enabled / usageCount / createdAt），僅補充分不存在的新 Skill
     * 3. 設定檔載入失敗時 fallback 到硬編碼 Skill
     */
    private fun syncFromConfig() {
        // 確保硬編碼基礎 Skill 始終存在（若已由 SharedPreferences 載入則保留狀態）
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

        // 從設定檔載入選股 Skill（內建 + 動態）
        try {
            val allConfigs = SkillConfigLoader.loadAllSkills(context)
            var newCount = 0
            var existCount = 0
            for (config in allConfigs) {
                if (config.id !in skills) {
                    // 新 Skill：從設定檔載入定義
                    val skill = config.toSkill()
                    register(skill)
                    newCount++
                    Log.i(TAG, "  📄 新增: ${config.id} (${if (config.isDynamic) "動態" else "內建"})")
                } else {
                    // 已存在的 Skill：保留 SharedPreferences 中的狀態，僅更新定義
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
                        Log.i(TAG, "  🔄 更新: ${config.id} (定義已變更)")
                    }
                    existCount++
                }
            }
            Log.i(TAG, "📋 同步完成: ${existCount}個保留 + ${newCount}個新增 (來自設定檔)")
        } catch (e: Exception) {
            Log.w(TAG, "從設定檔載入 Skill 失敗: ${e.message}")

            // Fallback: 硬編碼 Skill（配置檔損壞或不存在時）
            Log.w(TAG, "使用硬編碼 Fallback Skill")
            val builtinSkills = listOf<com.chin.stockanalysis.skill.stock_picking.BaseStockPickingSkill>(
                com.chin.stockanalysis.skill.stock_picking.StockPickingSkillDoubao1,
                com.chin.stockanalysis.skill.stock_picking.StockPickingSkillDoubao2
            )
            for (skillDef in builtinSkills) {
                if (skillDef.skillId !in skills) {
                    try {
                        val skill = skillDef.createSkill()
                        register(skill)
                        Log.i(TAG, "  硬編碼 Skill: ${skill.id} - ${skill.name}")
                    } catch (e2: Exception) {
                        Log.w(TAG, "  硬編碼 Skill ${skillDef.tag} 失敗: ${e2.message}")
                    }
                }
            }
        }
    }
}