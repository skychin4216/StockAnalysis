package com.chin.stockanalysis.agent.hub

/**
 * ## 专家注册表（AgentHub）—— 对齐 CodeBuddy "agents 声明 + AgentManager"
 *
 * 内置一批领域专家（首批 10 个），全部映射到**仓库已存在的执行器能力**：
 * | id          | 领域      | 对应现有实现（下一轮接线 invoke）                          |
 * |-------------|-----------|------------------------------------------------------------|
 * | technical   | TECHNICAL | StockAnalysisAgent / core.AgentOrchestrator(QUICK/DEEP)    |
 * | fundamental | FUNDAMENTAL | 基本面模块(财务/估值) + 机构持仓                            |
 * | index       | INDEX     | chat.IndexAnalysisAgent                                    |
 * | sector      | SECTOR    | SectorRotationTool / 龙头图谱 / MarketHot 排行             |
 * | picking     | PICKING   | StockPickingAgent + AiSelectionTool + 候选清单             |
 * | etf_flow    | ETF_FLOW  | SectorRotationTool + PcCandidates(etf_holdings) / _etf_buy |
 * | news        | NEWS      | agent/news（机构线索提取，ChatAgent.tryExtractInstitutionalTips）|
 * | macro       | MACRO     | 宏观事件日历（usecase 中长周期 pipeline）                  |
 * | portfolio   | PORTFOLIO | PortfolioHealthTool                                        |
 * | dag         | DAG       | QuickBuildExpertRunner / usecase XML DAG 专家              |
 *
 * 匹配策略：1) 关键词 trigger 命中 2) 股票/指数实体命中 3) 都未命中 → 协调者 react 兜底。
 * P0.2 将由 ChatAgent 路由适配器按 match 结果分派到具体执行器（行为与现状一致，
 * 只是把 if-else 换成注册表驱动）。
 */
object AgentHub {

    private val specs: Map<String, ChatExpertSpec> = listOf(
        ChatExpertSpec(
            id = "technical", name = "技术面专家", icon = "📈",
            domain = ExpertDomain.TECHNICAL,
            description = "个股/ETF 的K线、周期、形态与买卖点分析（快速/深度/专家 3 档）",
            triggers = listOf("技术面", "K线", "形态", "均线", "买点", "卖点", "短线", "MACD", "KDJ"),
        ),
        ChatExpertSpec(
            id = "fundamental", name = "基本面专家", icon = "📊",
            domain = ExpertDomain.FUNDAMENTAL,
            description = "财务、估值、机构持仓与公司质地分析",
            triggers = listOf("基本面", "财务", "估值", "市盈率", "PE", "机构", "业绩", "财报"),
        ),
        ChatExpertSpec(
            id = "index", name = "指数专家", icon = "🏛",
            domain = ExpertDomain.INDEX,
            description = "上证/深成/创业板等指数技术面与大盘环境判断",
            triggers = listOf("指数", "上证", "深成", "创业板指", "大盘", "点位"),
        ),
        ChatExpertSpec(
            id = "sector", name = "板块轮动专家", icon = "🎡",
            domain = ExpertDomain.SECTOR,
            description = "当日热点板块、月度轮动方向与板块龙头图谱",
            triggers = listOf("板块", "轮动", "行业", "热点", "龙头", "题材"),
        ),
        ChatExpertSpec(
            id = "picking", name = "一键选股专家", icon = "🎯",
            domain = ExpertDomain.PICKING,
            description = "聚合 AI 精选 + 候选清单 + 龙头/备选池，按周期给出机会票",
            triggers = listOf("选股", "推荐", "机会", "备选", "AI精选"),
        ),
        ChatExpertSpec(
            id = "etf_flow", name = "ETF·资金专家", icon = "🧲",
            domain = ExpertDomain.ETF_FLOW,
            description = "ETF 资金流向、热门板块 ETF 重仓与低吸观察（含 60 日回撤位置）",
            triggers = listOf("ETF", "资金", "低吸", "重仓"),
        ),
        ChatExpertSpec(
            id = "news", name = "情报雷达", icon = "🛰",
            domain = ExpertDomain.NEWS,
            description = "机构动态/消息面情报，附相关板块与标的",
            triggers = listOf("消息", "情报", "机构", "动态", "内幕"),
        ),
        ChatExpertSpec(
            id = "macro", name = "宏观专家", icon = "🌍",
            domain = ExpertDomain.MACRO,
            description = "宏观事件日历与中长周期环境研判",
            triggers = listOf("宏观", "日历", "事件", "CPI", "利率", "政策"),
        ),
        ChatExpertSpec(
            id = "portfolio", name = "持仓健康专家", icon = "💼",
            domain = ExpertDomain.PORTFOLIO,
            description = "持仓盈亏、健康度评分与卖出信号",
            triggers = listOf("持仓", "我的股票", "盈亏", "卖出", "健康"),
        ),
        ChatExpertSpec(
            id = "dag", name = "DAG 策略专家", icon = "🤖",
            domain = ExpertDomain.DAG,
            description = "一键构建季度/半年期 DAG 量化策略并执行",
            triggers = listOf("构建策略", "DAG", "回测", "专家", "pipeline"),
        ),
    ).associateBy { it.id }

    fun all(): List<ChatExpertSpec> = specs.values.toList()
    fun get(id: String): ChatExpertSpec? = specs[id]

    /** 高置信命中：任意 trigger 子串命中即返回该专家（首个命中）；可多个并列返回按序 */
    fun match(message: String): List<ChatExpertSpec> {
        val hits = mutableListOf<ChatExpertSpec>()
        for (s in specs.values) {
            if (s.triggers.any { message.contains(it) }) hits += s
        }
        return hits
    }

    /** 领域分组渲染（UI 按域做彩色 chip） */
    fun byDomain(): Map<ExpertDomain, List<ChatExpertSpec>> = specs.values.groupBy { it.domain }
}
