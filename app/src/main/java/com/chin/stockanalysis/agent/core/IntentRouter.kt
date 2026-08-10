package com.chin.stockanalysis.agent.core

import com.chin.stockanalysis.strategy.HoldingPeriod

/**
 * 用户意图类型
 */
enum class IntentType {
    QUICK_SCAN,      // 快速扫描：纯量化，无 LLM（仅 Scout）
    DEEP_ANALYSIS,   // 深度分析：全链路 Agent 群
    RISK_CHECK,      // 风控扫描：仅 Guardian
    FOLLOW_UP,       // 追问：仅 Analyst（对已有分析结果的后续提问）
    GENERAL_CHAT     // 通用问答：知识问答、闲聊、非股票相关问题
}

/**
 * 用户意图解析结果
 *
 * @property type 意图类型
 * @property target 目标股票代码（null 表示全市场扫描）
 * @property period 交易周期（DEEP_ANALYSIS 时必填）
 * @property rawInput 原始用户输入
 */
data class UserIntent(
    val type: IntentType,
    val target: String? = null,
    val period: HoldingPeriod? = null,
    val rawInput: String = ""
)

/**
 * 意图路由器 — 确定性路由，无需 LLM
 *
 * 基于「消息来源」（交易周期 + 用户意图类型）进行确定性分流。
 * 规则明确，零 token 消耗。
 *
 * 路由优先级（从高到低）：
 * 1. RISK_CHECK — 用户只想看持仓风险
 * 2. QUICK_SCAN — 快速市场概览
 * 3. DEEP_ANALYSIS + HoldingPeriod — 全链路分析
 * 4. FOLLOW_UP — 对已有结果追问
 */
class IntentRouter {

    fun resolve(
        userInput: String,
        currentStock: String? = null,
        holdingPeriod: HoldingPeriod? = null
    ): UserIntent {
        val normalized = userInput.lowercase()

        return when {
            // 风控扫描：用户想看持仓风险
            normalized.containsAny("风险", "止损", "止盈", "风控", "持仓安全", "仓位") ->
                UserIntent(IntentType.RISK_CHECK, target = currentStock, rawInput = userInput)

            // 快速扫描：市场概览
            normalized.containsAny("快速", "概览", "扫描", "市场怎么样", "大盘", "盘面") ->
                UserIntent(IntentType.QUICK_SCAN, rawInput = userInput)

            // 追问：对已有分析结果的后续提问
            normalized.containsAny("为什么", "追问", "详细", "解释", "怎么理解") && currentStock != null ->
                UserIntent(IntentType.FOLLOW_UP, target = currentStock, rawInput = userInput)

            // 通用问答：知识问答、闲聊、非股票相关问题（优先于 DEEP_ANALYSIS，避免浪费 LLM 调用）
            isGeneralChat(normalized, currentStock) ->
                UserIntent(IntentType.GENERAL_CHAT, rawInput = userInput)

            // 深度分析：指定股票或选股（默认路径）
            else -> {
                val period = holdingPeriod ?: inferPeriod(normalized)
                UserIntent(IntentType.DEEP_ANALYSIS, target = currentStock, period = period, rawInput = userInput)
            }
        }
    }

    /**
     * 从文本推断交易周期
     */
    private fun inferPeriod(text: String): HoldingPeriod {
        return when {
            text.containsAny("超短", "日内", "打板", "隔日", "T+1") -> HoldingPeriod.ULTRA_SHORT
            text.containsAny("短线", "几天", "一周", "短线") -> HoldingPeriod.SHORT
            text.containsAny("中线", "波段", "几周", "一个月") -> HoldingPeriod.MID
            text.containsAny("长线", "长期", "价值", "持有") -> HoldingPeriod.LONG
            else -> HoldingPeriod.SHORT  // 默认短线
        }
    }

    /**
     * 判断是否为通用问答（非股票分析请求）。
     *
     * 策略：如果输入不包含股票代码、股票名称或市场分析关键词，
     * 且看起来像一般问题、闲聊或知识问答，则路由到 GENERAL_CHAT。
     * 宁可误判为 GENERAL_CHAT（1 次 LLM 调用），也不要误判为 DEEP_ANALYSIS（5-17 次 LLM 调用）。
     */
    private fun isGeneralChat(text: String, currentStock: String?): Boolean {
        // 如果有明确的当前股票上下文，且输入较短（可能是追问），不走 GENERAL_CHAT
        // 但如果输入明显是知识问答，即使有 currentStock 也走 GENERAL_CHAT

        // ── 1. 闲聊/问候语 ──
        if (text.containsAny(
                "你好", "您好", "hello", "hi", "嗨", "再见", "bye",
                "谢谢", "感谢", "thanks", "thank you",
                "帮助", "help", "能做什么", "有什么功能", "功能"
            )
        ) return true

        // ── 2. 金融知识/概念问答 ──
        if (text.containsAny(
                "什么是", "是什么", "怎么看", "如何看", "怎么用", "如何用",
                "怎么选股", "如何选股", "怎么分析", "如何分析",
                "怎么设置", "如何设置", "怎么设定", "如何设定",
                "止损怎么", "止盈怎么", "止损如何", "止盈如何",
                "什么意思", "是什么意思", "怎么理解",
                "pe", "pb", "roe", "macd", "kdj", "rsi", "布林", "boll",
                "均线", "成交量", "换手率", "市盈率", "市净率", "净资产",
                "k线", "阳线", "阴线", "十字星", "涨停", "跌停",
                "基本面", "技术面", "消息面", "政策面",
                "价值投资", "趋势交易", "短线技巧", "操盘",
                "仓位管理", "资金管理", "风险管理",
                "etf", "指数", "基金", "债券", "期货", "期权",
                "牛市", "熊市", "震荡", "行情"
            )
        ) {
            // 排除明确包含股票代码的情况（如「600519是什么意思」→ 可能是问股票）
            if (!containsStockCode(text)) return true
        }

        // ── 3. 没有 currentStock 且输入不包含市场分析关键词 → 大概率闲聊 ──
        if (currentStock == null && !text.containsAny(
                "分析", "推荐", "选股", "买入", "卖出", "持仓", "建仓", "加仓",
                "减仓", "清仓", "目标价", "支撑", "压力", "突破",
                "涨", "跌", "走势", "趋势", "板块", "概念", "龙头",
                "主力", "资金流", "北向", "外资", "融资", "融券"
            )
        ) {
            // 输入较短（< 30 字）且是问句形式
            if (text.length < 30 && (text.contains("？") || text.contains("?") ||
                        text.containsAny("吗", "呢", "么", "嘛", "咋", "怎么", "如何", "什么", "为啥", "为什么"))
            ) return true
        }

        return false
    }

    /**
     * 简单检查文本中是否包含股票代码（6位数字，可能带市场前缀）。
     */
    private fun containsStockCode(text: String): Boolean {
        // 匹配 6 位数字股票代码（可选 sh/sz/sh6/sh9 前缀）
        val codePattern = Regex("""(?:sh|sz)?\d{6}""")
        return codePattern.containsMatchIn(text)
    }

    private fun String.containsAny(vararg keywords: String): Boolean {
        return keywords.any { this.contains(it, ignoreCase = true) }
    }
}

/**
 * Agent 集群配置 — 每个交易周期的 Agent 组合和参数
 *
 * @property period 交易周期
 * @property orchestratorTimeout 编排者超时
 * @property analystSteps 分析师步骤数
 * @property analystTimeout 分析师超时
 * @property guardianTimeout 风控官超时
 * @property cacheTtlMinutes 缓存有效期
 * @property maxTotalConcurrent 最大总并发数
 */
data class AgentClusterConfig(
    val period: HoldingPeriod,
    val orchestratorTimeout: Long,
    val analystSteps: Int,
    val analystTimeout: Long,
    val guardianTimeout: Long,
    val cacheTtlMinutes: Int,
    val maxTotalConcurrent: Int
) {
    companion object {
        // DeepAnalystEngine 最多 3 阶段串行 LLM（每阶段 ≤90s），超时需覆盖完整流程
        fun forPeriod(period: HoldingPeriod): AgentClusterConfig = when (period) {
            HoldingPeriod.ULTRA_SHORT -> AgentClusterConfig(
                period = period,
                orchestratorTimeout = 120_000,
                analystSteps = 3,       // 技术+舆情+风控（2阶段）
                analystTimeout = 90_000,
                guardianTimeout = 5_000,
                cacheTtlMinutes = 5,
                maxTotalConcurrent = 6
            )
            HoldingPeriod.SHORT -> AgentClusterConfig(
                period = period,
                orchestratorTimeout = 180_000,
                analystSteps = 5,       // +基本面+赛道
                analystTimeout = 150_000,
                guardianTimeout = 15_000,
                cacheTtlMinutes = 15,
                maxTotalConcurrent = 10
            )
            HoldingPeriod.MID -> AgentClusterConfig(
                period = period,
                orchestratorTimeout = 240_000,
                analystSteps = 6,       // +产业链（全量）
                analystTimeout = 210_000,
                guardianTimeout = 30_000,
                cacheTtlMinutes = 60,
                maxTotalConcurrent = 12
            )
            HoldingPeriod.LONG -> AgentClusterConfig(
                period = period,
                orchestratorTimeout = 240_000,
                analystSteps = 6,       // 全量深度分析
                analystTimeout = 210_000,
                guardianTimeout = 30_000,
                cacheTtlMinutes = 120,
                maxTotalConcurrent = 12
            )
        }
    }
}
