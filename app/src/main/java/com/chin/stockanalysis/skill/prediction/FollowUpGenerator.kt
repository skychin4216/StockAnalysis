package com.chin.stockanalysis.skill.prediction

import com.chin.stockanalysis.skill.SkillEngine

/**
 * ## 智能追问生成器
 *
 * 根据当前查询上下文，生成带来源标注的追问建议。
 *
 * ### 追问生成策略
 * 1. 同板块推荐 → [📊关联行业]
 * 2. 用户常问推荐 → [👤你的习惯]
 * 3. 时段相关 → [🕐时段相关]
 * 4. Skill 推荐 → [🏷️技能推荐]
 */
object FollowUpGenerator {

    /** 同板块/同行股票关联 */
    private val SECTOR_PEERS = mapOf(
        "sh600519" to listOf("sz000858" to "五粮液", "sz000568" to "泸州老窖", "sh600809" to "山西汾酒"),
        "sz000858" to listOf("sh600519" to "贵州茅台", "sz000568" to "泸州老窖"),
        "sz300750" to listOf("sz002594" to "比亚迪", "sz300014" to "亿纬锂能", "sh688567" to "孚能科技"),
        "sz002594" to listOf("sz300750" to "宁德时代", "sh601238" to "广汽集团", "sh600104" to "上汽集团"),
        "sh600183" to listOf("sz002463" to "沪电股份", "sh603228" to "景旺电子", "sz002916" to "深南电路"),
        "sz002463" to listOf("sh600183" to "生益科技", "sh603228" to "景旺电子"),
    )

    /**
     * @param lastQuery 用户最后一次查询
     * @param matchedCodes 当前查询匹配的股票代码
     * @param skillEngine 可选 skill 引擎
     */
    fun generate(
        lastQuery: String,
        matchedCodes: List<String>,
        skillEngine: SkillEngine? = null
    ): List<FollowUp> {
        val results = mutableListOf<FollowUp>()

        // 1. 同板块推荐
        for (code in matchedCodes) {
            val peers = SECTOR_PEERS[code] ?: continue
            for ((peerCode, peerName) in peers.take(2)) {
                results.add(FollowUp(
                    question = String.format("%s 今天怎么样？", peerName),
                    reasons = listOf(FollowUpReason.RELATED_SECTOR),
                    confidence = 0.9f
                ))
            }
        }

        // 2. 自然追问
        if (matchedCodes.isNotEmpty()) {
            results.add(FollowUp(
                question = "${matchedCodes.joinToString("和") { it }}的量价关系如何？",
                reasons = listOf(FollowUpReason.NATURAL_NEXT),
                confidence = 0.85f
            ))
        }

        // 3. 时段相关
        val hour = java.time.LocalTime.now().hour
        if (hour in 9..11) {
            results.add(FollowUp(
                question = "今天早盘哪些板块最活跃？",
                reasons = listOf(FollowUpReason.TIME_CONTEXT),
                confidence = 0.8f
            ))
        } else if (hour in 14..15) {
            results.add(FollowUp(
                question = "尾盘有哪些异动股票？",
                reasons = listOf(FollowUpReason.TIME_CONTEXT),
                confidence = 0.8f
            ))
        }

        // 4. Skill 推荐
        if (skillEngine != null) {
            val autoSkills = skillEngine.getAutoTriggerSkills()
            for (skill in autoSkills.take(2)) {
                if (skill.triggerPrompt != null) {
                    results.add(FollowUp(
                        question = skill.triggerPrompt,
                        reasons = listOf(FollowUpReason.FROM_SKILL),
                        confidence = 0.7f,
                        skillId = skill.id
                    ))
                }
            }
        }

        return results.distinctBy { it.question }.take(5)
    }
}