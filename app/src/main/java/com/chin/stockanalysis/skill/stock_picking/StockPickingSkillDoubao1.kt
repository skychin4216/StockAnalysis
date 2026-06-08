package com.chin.stockanalysis.skill.stock_picking

/**
 * 选股技巧 Skill - 豆包技巧1
 *
 * 通过产业研究和供应链分析，寻找细分领域的隐形冠军
 */
object StockPickingSkillDoubao1 : BaseStockPickingSkill() {

    override val tag = "StockPickingSkillDoubao1"
    override val skillId = "stock_picking_doubao_1"
    override val skillName = "豆包选股技巧1 - 寻找细分领域隐形冠军"
    override val icon = "🎯"
    override val skillDescription = "通过逆向工程拆解BOM，寻找物理机瓶颈环节的隐形冠军"

    override val keywords = listOf(
        "选股技巧", "选股", "BOM", "物料清单", "隐形冠军",
        "扩产周期", "物理机瓶颈", "毛利率拐点", "CapEx", "资本支出",
        "豆包选股", "产业研究", "供应链分析"
    )

    override fun getFullPrompt(stockCode: String?): String {
        val codePart = if (stockCode != null) "【" + stockCode + "】" else ""

        val step1 = """## 步骤 1: 逆向工程拆解物料清单（BOM）
针对目标行业/标的，请通过逆向工程拆解其物料清单（BOM），找出：
- 哪个底层环节处于扩产周期
- 该环节是否具有技术门槛高的特征
- 该环节是否是不可替代的物理机瓶颈"""

        val step2 = """## 步骤 2: 筛选细分领域隐形冠军
针对该瓶颈环节，扫描符合以下条件的标的：
- 市值在 5-30 亿美元区间（约 35-210 亿人民币）
- 必须是细分领域的隐形冠军
- 具有不可替代性
- 具有低成本占比、高失效风险的特征"""

        val step3 = """## 步骤 3: 穿透财报审查关键指标
穿透上述中小盘标的的财报，重点审查两个核心指标：
a. 过去两个季度毛利率是否因供需失衡出现爆发性拐点？
b. 资本支出（CapEx）是否在秘密爬坡以承接未来的爆发需求？"""

        val step4 = "## 步骤 4: 空头分析师证伪报告\n" +
            "你扮演一名持有大量空头的分析师，从以下三个维度为标的" + codePart + "写一份证伪报告：\n" +
            "1. 技术路径替代风险\n" +
            "2. 大客户自研风险\n" +
            "3. 供应链断裂风险\n" +
            "列举该标的所有可能的\"死法\""

        val step5 = """## 步骤 5: 未来 6 个月关键可证伪里程碑
为该标的拟定未来 6 个月的关键可证伪里程碑，请以表格形式列出：
一旦哪些核心事件（如订单、良率、专利）未按时发生，必须判定逻辑失效并强制清仓？

---

请以结构化的方式输出你的分析，包含所有上述 5 个步骤。"""

        return "你是产业研究专家，现在按照以下步骤进行选股分析：\n\n" +
            step1 + "\n\n" +
            step2 + "\n\n" +
            step3 + "\n\n" +
            step4 + "\n\n" +
            step5
    }

    override fun getQuickPrompt(): String {
        return """请使用"豆包选股技巧1"分析股票：
1. 拆解 BOM 找物理机瓶颈环节
2. 筛选 5-30 亿美元市值的隐形冠军
3. 审查毛利率拐点和 CapEx 爬坡
4. 空头分析师视角的证伪报告
5. 未来 6 个月可证伪里程碑
"""
    }
}