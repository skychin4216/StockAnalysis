package com.chin.stockanalysis.skill.stock_picking

/**
 * 选股技巧 Skill - 豆包技巧2：大A咽喉卡点拆解
 *
 * 四阶段筛选：月度初筛 → 季报复筛 → 买前终审 → 持股监控
 */
object StockPickingSkillDoubao2 : BaseStockPickingSkill() {

    override val tag = "StockPickingSkillDoubao2"
    override val skillId = "stock_picking_doubao_2"
    override val skillName = "豆包选股技巧2 - 大A咽喉卡点拆解"
    override val icon = "\uD83D\uDD2C"  // 🔬
    override val skillDescription = "月度初筛→季报复筛→买前终审→持股监控，四阶段锁定卡脖子垄断标的"

    override val keywords = listOf(
        "卡脖子", "国产化率", "专精特新", "单项冠军",
        "咽喉卡点", "四阶段筛选", "贝叶斯熔断",
        "红队对抗", "证伪排查"
    )

    override fun getFullPrompt(stockCode: String?): String {
        val codePart = if (stockCode != null) "\u3010" + stockCode + "\u3011" else ""  // 【】

        return buildString {
            appendLine("你是产业研究专家，现在按照以下四阶段筛选方法进行选股分析：")
            appendLine()
            appendLine("## 阶段 1: 月度初筛（大A咽喉卡点拆解）")
            appendLine()
            appendLine("核心逻辑：从赛道的 BOM 清单里，逆向寻找卡脖子的垄断环节")
            appendLine()
            appendLine("筛选条件：")
            appendLine("- 国产化率 < 20%")
            appendLine("- 成本占比 < 5%")
            appendLine("- 扩产周期 > 18 个月")
            appendLine("- 企业属性：专精特新 / 单项冠军")
            appendLine("- 市值区间：30 亿 - 150 亿的中小盘标的")
            appendLine()
            appendLine("## 阶段 2: 季报复筛（财务数据硬核穿透）")
            appendLine()
            appendLine("核心逻辑：用近四季度财报，验证基本面是否在悄悄爬坡")
            appendLine()
            appendLine("筛选条件：")
            appendLine("- 取主营驱动的毛利率连续扩张")
            appendLine("- CapEx（资本开支）/ 在建工程数据持续增长")
            appendLine("- 定增资金投向卡脖子环节")
            appendLine("- 剔除：近 3 个月券商研报数量 > 15 篇的标的（规避过度曝光）")
            appendLine()
            appendLine("## 阶段 3: 买前终审（AI 红队对抗证伪）")
            appendLine()
            appendLine("核心逻辑：站在空头视角，对标的做反向证伪排查风险")
            appendLine()
            appendLine("审查维度：")
            appendLine("- 大客户是否有自研 / 引入二供的替代计划")
            appendLine("- 技术路线在未来 18 个月内是否存在被取消的风险")
            appendLine("- 同行是否会通过价格战冲击标的的垄断地位")
            appendLine()
            appendLine("执行规则：任一维度出现明确风险，直接回避标的")
            appendLine()
            appendLine("## 阶段 4: 持股监控（贝叶斯动态熔断）")
            appendLine()
            appendLine("核心逻辑：用里程碑事件，动态验证逻辑是否失效，触发熔断止损")
            appendLine()
            appendLine("执行步骤：")
            appendLine("1. 为标的" + codePart + "列出未来两季度的 3 个硬核里程碑（如打样、量产、客户验证等关键节点）")
            appendLine("2. 为每个里程碑设定最晚完成时限")
            appendLine("3. 监控互动易、公告等渠道的信息，若里程碑未按时确认，则判定逻辑失效")
            appendLine("4. 以「事件 | 最晚时限 | 熔断阈值」的结构，建立监控表格，动态管理持仓")
            appendLine()
            appendLine("---")
            appendLine()
            append("请以结构化的方式输出你的分析，包含所有上述 4 个阶段。")
        }
    }

    override fun getQuickPrompt(): String {
        return "请使用豆包选股技巧2 - 大A咽喉卡点拆解分析：\n" +
            "1. 月度初筛：国产化率<20%、成本占比<5%、扩产周期>18月的专精特新标的\n" +
            "2. 季报复筛：毛利率连续扩张、CapEx持续增长、剔除过度曝光\n" +
            "3. 买前终审：AI红队对抗证伪（大客户自研/技术路线/价格战风险）\n" +
            "4. 持股监控：贝叶斯动态熔断，里程碑验证\n"
    }
}