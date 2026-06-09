package com.chin.stockanalysis.agent

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * ## 智能体管理器
 *
 * 管理 Agent 的 CRUD 和持久化。
 *
 * ### 持久化策略
 * - SharedPreferences (agent_prefs) — 存储所有 Agent 的完整定义
 * - 每次启动时从 SharedPreferences 加载
 * - 首次启动时自动从 Skill 配置迁移创建默认 Agent
 *
 * ### 与 Skill 的关系
 * - Agent 是 Skill 的升级版：每个选股 Skill 自动对应一个 Agent
 * - Agent 拥有独立的对话上下文，而 Skill 只是 prompt 注入
 * - Agent 对话结果会触发 AgentPickParser 提取选股
 */
class AgentManager(private val context: Context) {

    companion object {
        private const val TAG = "AgentManager"
        private const val PREFS_NAME = "agent_prefs"
        private const val KEY_AGENTS = "agents_json"
        private const val KEY_INITIALIZED = "agents_initialized_v2"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val agents = mutableMapOf<String, Agent>()

    init {
        val isV2Migrated = prefs.getBoolean("agents_migrated_v2", false)
        loadFromPrefs()
        if (!prefs.getBoolean(KEY_INITIALIZED, false)) {
            initializeDefaultAgents()
            prefs.edit().putBoolean(KEY_INITIALIZED, true).putBoolean("agents_migrated_v2", true).apply()
        } else if (!isV2Migrated) {
            // v2 升级：用 skill_config.json 正确值强制覆寫舊髒數據
            reMigrateFromSkills()
            prefs.edit().putBoolean("agents_migrated_v2", true).apply()
        }
    }

    // ═══════════════════════════════════════
    // CRUD
    // ═══════════════════════════════════════

    fun register(agent: Agent) {
        agents[agent.id] = agent
        saveToPrefs()
        Log.i(TAG, "注册智能体: ${agent.id} - ${agent.name}")
    }

    fun remove(id: String): Boolean {
        val removed = agents.remove(id) != null
        if (removed) saveToPrefs()
        return removed
    }

    fun setEnabled(id: String, enabled: Boolean) {
        agents[id]?.let { register(it.copy(enabled = enabled)) }
    }

    fun get(id: String): Agent? = agents[id]
    fun getAll(): List<Agent> = agents.values.toList()
    fun getEnabled(): List<Agent> = agents.values.filter { it.enabled }

    fun recordUsage(id: String) {
        agents[id]?.let { register(it.copy(usageCount = it.usageCount + 1)) }
    }

    /**
     * 创建新智能体
     * @return 新建的 Agent，若 ID 冲突则返回 null
     */
    fun create(
        name: String,
        description: String,
        quickPrompt: String = "",
        systemPrompt: String = "",
        icon: String = "🤖",
        triggerKeywords: List<String> = emptyList()
    ): Agent? {
        val id = generateAgentId(name)
        if (agents.containsKey(id)) {
            Log.w(TAG, "智能体 ID $id 已存在")
            return null
        }
        val agent = Agent(
            id = id,
            name = name,
            icon = icon,
            description = description,
            quickPrompt = quickPrompt,
            systemPrompt = systemPrompt,
            triggerKeywords = triggerKeywords,
            createdAt = System.currentTimeMillis()
        )
        register(agent)
        return agent
    }

    /**
     * 更新智能体
     */
    fun update(id: String, update: (Agent) -> Agent): Agent? {
        val existing = agents[id] ?: return null
        val updated = update(existing)
        register(updated)
        return updated
    }

    // ═══════════════════════════════════════
    // 从 Skill 迁移默认 Agent
    // ═══════════════════════════════════════

    private fun initializeDefaultAgents() {
        Log.i(TAG, "📋 首次启动，从 Skill 配置迁移默认智能体...")
        try {
            val skillConfigs = com.chin.stockanalysis.skill.stock_picking.SkillConfigLoader.loadAllSkills(context)
            var count = 0
            for (config in skillConfigs) {
                if (config.id in agents) continue
                val agent = Agent(
                    id = config.id,
                    name = config.name,
                    icon = config.icon.ifBlank { "🎯" },
                    description = config.description,
                    quickPrompt = config.quickPrompt,
                    systemPrompt = config.systemPrompt,
                    triggerKeywords = config.keywords,
                    source = if (config.isDynamic) AgentSource.USER_CREATED else AgentSource.FROM_SKILL,
                    createdAt = System.currentTimeMillis()
                )
                agents[agent.id] = agent
                count++
            }
            if (count > 0) {
                saveToPrefs()
                Log.i(TAG, "✅ 已迁移 $count 个 Skill → Agent")
            } else {
                createDefaultExampleAgent()
                saveToPrefs()
                Log.i(TAG, "📝 创建了默认示例 Agent")
            }
        } catch (e: Exception) {
            Log.w(TAG, "默认 Agent 迁移失败: ${e.message}")
            createDefaultExampleAgent()
            saveToPrefs()
        }
    }

    private fun createDefaultExampleAgent() {
        val example = Agent(
            id = "stock_picking_default",
            name = "默认选股助手",
            icon = "🎯",
            description = "通用选股分析助手，帮助筛选有潜力的股票",
            quickPrompt = "你是一位专业的股票分析师。请根据用户提供的信息，分析相关股票的投资价值，包括基本面、技术面、行业前景等维度，并给出具体的股票推荐和分析理由。",
            systemPrompt = "",
            triggerKeywords = listOf("选股", "推荐", "分析"),
            source = AgentSource.AUTO_GENERATED
        )
        agents[example.id] = example
    }

    /**
     * v2 升级迁移：用 skill_config.json 正确值覆寫 FROM_SKILL 來源的舊髒數據
     */
    private fun reMigrateFromSkills() {
        Log.i(TAG, "🔄 v2 升级：重新修正 FROM_SKILL 智能体的 quickPrompt/systemPrompt...")
        try {
            val skillConfigs = com.chin.stockanalysis.skill.stock_picking.SkillConfigLoader.loadAllSkills(context)
            var fixed = 0
            for (config in skillConfigs) {
                val existing = agents[config.id] ?: continue
                // 只修正來源為 FROM_SKILL 的，保留用戶親手創建/編輯過的
                if (existing.source != AgentSource.FROM_SKILL) continue
                // 只修正 quickPrompt 為空 或 systemPrompt 為空 的髒數據
                if (existing.quickPrompt.isNotBlank() && existing.systemPrompt.isNotBlank()) continue
                val corrected = existing.copy(
                    quickPrompt = config.quickPrompt,
                    systemPrompt = config.systemPrompt
                )
                agents[corrected.id] = corrected
                fixed++
                Log.d(TAG, "  ✅ 修正 ${existing.id}: quickPrompt=${config.quickPrompt.take(50)}... / systemPrompt=${config.systemPrompt.take(50)}...")
            }
            if (fixed > 0) {
                saveToPrefs()
                Log.i(TAG, "✅ v2 升级完成：修正了 $fixed 个智能体")
            } else {
                Log.i(TAG, "✅ v2 升级：无需修正")
            }
        } catch (e: Exception) {
            Log.w(TAG, "v2 升级失败: ${e.message}")
        }
    }

    // ═══════════════════════════════════════
    // 持久化
    // ═══════════════════════════════════════

    private fun loadFromPrefs() {
        val json = prefs.getString(KEY_AGENTS, null) ?: return
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val agent = Agent(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    icon = obj.optString("icon", "🤖"),
                    description = obj.optString("description", ""),
                    quickPrompt = obj.optString("quickPrompt", obj.optString("systemPrompt", "")),
                    systemPrompt = obj.optString("systemPrompt", obj.optString("fullPrompt", obj.optString("autoCommandSource", ""))),
                    triggerKeywords = (0 until obj.getJSONArray("keywords").length())
                        .map { obj.getJSONArray("keywords").getString(it) },
                    enabled = obj.optBoolean("enabled", true),
                    usageCount = obj.optInt("usageCount", 0),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                    source = try {
                        AgentSource.valueOf(obj.optString("source", "USER_CREATED"))
                    } catch (_: Exception) { AgentSource.USER_CREATED }
                )
                agents[agent.id] = agent
            }
        } catch (e: Exception) {
            Log.w(TAG, "加载智能体失败: ${e.message}")
        }
    }

    private fun saveToPrefs() {
        val arr = JSONArray()
        for (agent in agents.values) {
            val obj = JSONObject().apply {
                put("id", agent.id)
                put("name", agent.name)
                put("icon", agent.icon)
                put("description", agent.description)
                put("quickPrompt", agent.quickPrompt)
                put("systemPrompt", agent.systemPrompt)
                put("keywords", JSONArray(agent.triggerKeywords))
                put("enabled", agent.enabled)
                put("usageCount", agent.usageCount)
                put("createdAt", agent.createdAt)
                put("source", agent.source.name)
            }
            arr.put(obj)
        }
        prefs.edit().putString(KEY_AGENTS, arr.toString()).apply()
    }

    private fun generateAgentId(name: String): String {
        val base = name
            .lowercase()
            .replace(Regex("[^a-z0-9\\u4e00-\\u9fff]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
        return "agent_${base}_${System.currentTimeMillis() % 100000}"
    }
}