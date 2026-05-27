package com.chin.stockanalysis.skill.prediction

/**
 * ## 追问原因枚举
 *
 * AI 回答结束后推荐的追问，每个追问都标注来源原因，
 * 让用户知道"为什么推荐这个问题"。
 */
enum class FollowUpReason(val emoji: String, val label: String) {
    /** [📊] 同板块/同行业关联 */
    RELATED_SECTOR("📊", "关联行业"),

    /** [👤] 基于你的查询习惯 */
    YOUR_HABIT("👤", "你的习惯"),

    /** [🔥] 当前市场热点 */
    MARKET_HOT("🔥", "市场热点"),

    /** [➡️] 自然追问（从当前回答推导） */
    NATURAL_NEXT("➡️", "自然追问"),

    /** [🕐] 时段相关（盘前/盘中/盘后） */
    TIME_CONTEXT("🕐", "时段相关"),

    /** [🏷️] 来自你的 Skill */
    FROM_SKILL("🏷️", "技能推荐"),

    /** [📈] 技术指标信号触发 */
    TECHNICAL_SIGNAL("📈", "技术信号")
}

/**
 * ## 智能追问数据类
 */
data class FollowUp(
    val question: String,
    val reasons: List<FollowUpReason>,
    val confidence: Float,
    /** 如果来自 Skill，对应的 skillId */
    val skillId: String? = null
) {
    /** 人类可读的原因标签 */
    val reasonLabel: String
        get() = reasons.joinToString(" ") { "${it.emoji}${it.label}" }

    /** 显示追问提示文本 */
    fun display(): String = "$reasonLabel: $question"
}