package com.chin.stockanalysis.skill

/**
 * ## 个人技能（Skill）
 *
 * Skill = 命名 prompt 组合，一键执行复杂查询。
 *
 * ### 来源
 * - AUTO_GENERATED: PatternMiner 从用户习惯自动建议
 * - USER_CREATED: 用户手动创建
 */
data class Skill(
    val id: String,
    val name: String,
    val icon: String = "🎯",
    val description: String = "",
    val prompts: List<String>,
    val autoTrigger: Boolean = false,
    val triggerTime: String? = null,
    val triggerPrompt: String? = null,
    val source: SkillSource = SkillSource.USER_CREATED,
    val enabled: Boolean = true,
    val usageCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

enum class SkillSource { AUTO_GENERATED, USER_CREATED }