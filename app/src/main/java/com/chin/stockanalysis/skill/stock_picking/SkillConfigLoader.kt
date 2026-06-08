package com.chin.stockanalysis.skill.stock_picking

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.skill.Skill
import com.chin.stockanalysis.skill.SkillSource
import org.json.JSONArray
import org.json.JSONObject

/**
 * ## Skill 設定檔載入器
 *
 * 從 JSON 設定檔載入 Skill 定義，支援兩個來源：
 * 1. **內建設定** → `assets/skills_config.json`（隨 App 發布，唯讀）
 * 2. **動態設定** → 內部儲存 `skills_dynamic.json`（AI 對話動態建立，可讀寫）
 *
 * 使用 `{stockCode}` 作為 prompt 中的佔位符，執行時自動替換。
 *
 * ### 新增 Skill 方式
 *
 * **方式 A: 修改 assets/skills_config.json**（需重新編譯）
 * ```json
 * {
 *   "skills": [
 *     { "id": "my_skill", "name": "我的技巧", "keywords": ["關鍵詞"], ... }
 *   ]
 * }
 * ```
 *
 * **方式 B: AI 對話動態建立**（無需編譯，重啟後保留）
 * 在對話中輸入：`新增一個選股技能：名稱為"xxx"，觸發關鍵詞為"a,b,c"，提示詞為：...`
 */
object SkillConfigLoader {

    private const val TAG = "SkillConfigLoader"
    private const val BUILTIN_CONFIG = "skills_config.json"
    private const val DYNAMIC_CONFIG = "skills_dynamic.json"

    // ═══════════════════════════════════════
    // 資料模型
    // ═══════════════════════════════════════

    /** 從 JSON 解析出的 Skill 設定 */
    data class SkillConfig(
        val id: String,
        val name: String,
        val icon: String = "🎯",
        val description: String = "",
        val keywords: List<String> = emptyList(),
        val fullPrompt: String = "",
        val quickPrompt: String = "",
        val autoTrigger: Boolean = true,
        val isDynamic: Boolean = false  // true = 來自動態設定, false = 內建
    ) {
        /** 轉換為 Skill 資料物件 */
        fun toSkill(): Skill {
            return Skill(
                id = id,
                name = name,
                icon = icon,
                description = description,
                prompts = listOf(fullPrompt, quickPrompt),
                autoTrigger = autoTrigger,
                triggerPrompt = keywords.joinToString(", "),
                source = SkillSource.USER_CREATED
            )
        }

        /** 將 prompt 中的佔位符替換為實際值 */
        fun getFullPrompt(stockCode: String? = null): String {
            return if (stockCode != null) {
                fullPrompt.replace("{stockCode}", stockCode)
            } else {
                fullPrompt
            }
        }

    }

    // ═══════════════════════════════════════
    // 公開 API
    // ═══════════════════════════════════════

    /**
     * 從 assets 載入內建 Skill 設定
     */
    fun loadBuiltinSkills(context: Context): List<SkillConfig> {
        return try {
            val json = context.assets.open(BUILTIN_CONFIG).bufferedReader().use { it.readText() }
            val configs = parseJson(json)
            Log.i(TAG, "載入內建 Skill: ${configs.size}個")
            configs
        } catch (e: Exception) {
            Log.w(TAG, "載入內建 Skill 設定失敗: ${e.message}")
            emptyList()
        }
    }

    /**
     * 載入動態建立的 Skill 設定（內部儲存）
     */
    fun loadDynamicSkills(context: Context): List<SkillConfig> {
        return try {
            val file = java.io.File(context.filesDir, DYNAMIC_CONFIG)
            if (!file.exists()) {
                Log.d(TAG, "無動態 Skill 設定檔")
                return emptyList()
            }
            val json = file.readText()
            val configs = parseJson(json).map { it.copy(isDynamic = true) }
            Log.i(TAG, "載入動態 Skill: ${configs.size}個")
            configs
        } catch (e: Exception) {
            Log.w(TAG, "載入動態 Skill 設定失敗: ${e.message}")
            emptyList()
        }
    }

    /**
     * 載入所有 Skill（內建 + 動態），合併去重（動態優先）
     */
    fun loadAllSkills(context: Context): List<SkillConfig> {
        val builtin = loadBuiltinSkills(context)
        val dynamic = loadDynamicSkills(context)
        val dynamicIds = dynamic.map { it.id }.toSet()
        val filteredBuiltin = builtin.filter { it.id !in dynamicIds }
        val all = filteredBuiltin + dynamic
        Log.i(TAG, "合併 Skill 設定: 內建${filteredBuiltin.size} + 動態${dynamic.size} = ${all.size}個")
        return all
    }

    /**
     * 保存動態 Skill 設定到內部儲存
     */
    fun saveDynamicSkills(context: Context, configs: List<SkillConfig>) {
        try {
            val json = configsToJson(configs)
            val file = java.io.File(context.filesDir, DYNAMIC_CONFIG)
            file.writeText(json)
            Log.i(TAG, "保存動態 Skill: ${configs.size}個 → $DYNAMIC_CONFIG")
        } catch (e: Exception) {
            Log.w(TAG, "保存動態 Skill 設定失敗: ${e.message}")
        }
    }

    /**
     * 新增一個動態 Skill（讀取 → 添加 → 保存）
     * @return 新增成功回傳 true，ID 衝突回傳 false
     */
    fun addDynamicSkill(context: Context, config: SkillConfig): Boolean {
        val existing = loadDynamicSkills(context)
        if (existing.any { it.id == config.id }) {
            Log.w(TAG, "動態 Skill ID ${config.id} 已存在")
            return false
        }
        val updated = existing + config.copy(isDynamic = true)
        saveDynamicSkills(context, updated)
        return true
    }

    /**
     * 刪除一個動態 Skill
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
                fullPrompt = obj.optString("fullPrompt", ""),
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
                put("fullPrompt", config.fullPrompt)
                put("quickPrompt", config.quickPrompt)
                put("autoTrigger", config.autoTrigger)
            }
            arr.put(obj)
        }
        root.put("skills", arr)
        return root.toString(2)  // Pretty-print 方便手動編輯
    }
}