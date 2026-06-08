package com.chin.stockanalysis.skill.stock_picking

import com.chin.stockanalysis.skill.Skill
import com.chin.stockanalysis.skill.SkillSource

/**
 * ## 選股技巧基礎類別
 *
 * 所有內建選股 Skill 繼承此類別，只需覆寫 metadata + prompt 內容，
 * `createSkill()` / `shouldTrigger()` 等樣板程式碼由基礎類別統一提供。
 *
 * ### 使用方式
 * ```kotlin
 * object StockPickingSkillDoubao1 : BaseStockPickingSkill() {
 *     override val skillId = "stock_picking_doubao_1"
 *     override val skillName = "豆包选股技巧1 - 寻找细分领域隐形冠军"
 *     override val icon = "🎯"
 *     override val skillDescription = "通过逆向工程拆解BOM..."
 *     override val keywords = listOf("选股技巧", "BOM", ...)
 *     override val tag = "StockPickingSkillDoubao1"
 *
 *     override fun getFullPrompt(stockCode: String?) = """..."""
 *     override fun getQuickPrompt() = """..."""
 * }
 * ```
 */
abstract class BaseStockPickingSkill {

    // ── 子類別必須覆寫的屬性 ──

    abstract val skillId: String
    abstract val skillName: String
    abstract val icon: String
    abstract val skillDescription: String
    abstract val keywords: List<String>
    abstract val tag: String

    // ── 子類別必須覆寫的方法 ──

    /** 完整版 prompt（可包含 stockCode 佔位符） */
    abstract fun getFullPrompt(stockCode: String? = null): String

    /** 簡化版 prompt（用於快速觸發） */
    abstract fun getQuickPrompt(): String

    // ── 基礎類別提供的共用實作 ──

    /**
     * 建立 Skill 資料實例
     */
    open fun createSkill(): Skill {
        return Skill(
            id = skillId,
            name = skillName,
            icon = icon,
            description = skillDescription,
            prompts = listOf(getFullPrompt(), getQuickPrompt()),
            autoTrigger = true,
            triggerPrompt = keywords.joinToString(", "),
            source = SkillSource.USER_CREATED
        )
    }

    /**
     * 檢查使用者輸入是否觸發此技巧
     */
    open fun shouldTrigger(userInput: String): Boolean {
        return keywords.any { keyword ->
            userInput.contains(keyword, ignoreCase = true)
        }
    }
}