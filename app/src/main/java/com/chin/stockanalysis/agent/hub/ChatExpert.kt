package com.chin.stockanalysis.agent.hub

/**
 * ## 对话专家（ChatExpert）—— 对齐 CodeBuddy "子 Agent" 概念
 *
 * 领域拆分的执行单元：`spec` 声明身份/触发词（供 AgentHub 匹配与 UI 渲染），
 * `invoke` 是真正干活入口（内部可复用现有执行器：IndexAnalysisAgent、
 * StockAnalysisAgent / AgentOrchestrator / SectorRotationTool / 推送候选等）。
 *
 * 设计约束：
 * - 专家是**声明式**的：加一个专家 = 在 AgentHub 注册一行 + 实现 invoke，不改 ChatAgent/UI
 * - `stateful=true` 的专家允许挂载 ExpertMemory（P2 落地），默认无状态
 * - `maxSteps>0` 表示该专家内部可跑工具自循环（ReAct），0 = 单轮直答
 */
interface ChatExpert {
    val spec: ChatExpertSpec
    /** 专家执行入口。userMessage 为原始提问；ctx 带本轮共享状态与记忆。 */
    suspend fun invoke(userMessage: String, ctx: ExpertContext): ExpertReply
}

/** 专家领域（用于分组/聚合/UI 颜色） */
enum class ExpertDomain {
    TECHNICAL,      // 个股技术面（K线/周期/形态）
    FUNDAMENTAL,    // 基本面（财务/估值/机构）
    INDEX,          // 指数与大盘
    SECTOR,         // 板块轮动/龙头/热力
    PICKING,        // 一键选股/AI精选
    ETF_FLOW,       // ETF·资金流·重仓低吸
    NEWS,           // 情报雷达（机构/消息驱动）
    MACRO,          // 宏观事件日历
    PORTFOLIO,      // 持仓健康/风控
    RISK,           // 排雷/合规
    DAG,            // DAG 量化/专家 runner
}

/** 专家声明（纯元数据，UI/匹配/编排只读它） */
data class ChatExpertSpec(
    val id: String,
    val name: String,
    val icon: String,
    val domain: ExpertDomain,
    val description: String,
    val triggers: List<String> = emptyList(),
    val maxSteps: Int = 0,
    val stateful: Boolean = false,
)

/** 本轮执行上下文：共享状态 + 可选记忆（P2）+ 可选强制指定专家 */
class ExpertContext {
    val extra = mutableMapOf<String, Any?>()
    var forcedExpertId: String? = null   // UI 点了专家 chip 时指定
    var memory: Any? = null              // P2: ExpertMemory
    fun put(k: String, v: Any?) { extra[k] = v }
    fun <T> get(k: String): T? { @Suppress("UNCHECKED_CAST") return extra[k] as T? }
}

/** 专家回复：text 直接上屏；data 可携带结构化结果（选股列表/图表/股票实体） */
data class ExpertReply(
    val text: String,
    val data: Any? = null,
    val ok: Boolean = true,
    val tookMs: Long = 0,
)
