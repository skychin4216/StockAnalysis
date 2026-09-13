package com.chin.stockanalysis.strategy.topology.core

/**
 * ## Node -- Pipeline 最小处理单元
 *
 * 无状态（或仅持有配置），不持有业务数据。
 * 泛型 IN = 输入类型, OUT = 输出类型。
 *
 * ### 设计原则
 * - 每个 Node 只做一件事（Single Responsibility）
 * - 通过 [PipelineContext] 读取共享上下文，不直接持有业务数据
 * - 通过泛型 IN/OUT 实现类型安全的数据流
 *
 * ### 使用示例
 * ```kotlin
 * class MarketSourceNode : PipelineNode<Unit, StockPool> {
 *     override val nodeId = "market_source"
 *     override val nodeName = "市场数据源"
 *     override val nodeType = NodeType.DATA_SOURCE
 *
 *     override suspend fun execute(context: PipelineContext, input: Unit): StockPool {
 *         // 从 context 中读取配置，返回 StockPool
 *     }
 * }
 * ```
 *
 * @param IN 输入数据类型
 * @param OUT 输出数据类型
 */
interface PipelineNode<IN, OUT> {

    /** 节点唯一标识（用于日志、调试、DAG 拓扑引用） */
    val nodeId: String

    /** 节点人类可读名称（用于 UI 展示和日志） */
    val nodeName: String

    /** 节点类型（决定在 Pipeline DAG 中的语义角色） */
    val nodeType: NodeType

    /**
     * 执行节点处理逻辑
     *
     * @param context Pipeline 共享上下文（市场数据、缓存、配置等）
     * @param input 节点输入数据
     * @return 节点处理结果
     */
    suspend fun execute(context: PipelineContext, input: IN): OUT
}

/**
 * ## 节点类型枚举
 *
 * 标识节点在 Pipeline 中的语义角色，
 * 用于日志分类、DAG 拓扑校验、UI 可视化等场景。
 */
enum class NodeType {

    /** 数据源：从 API / 数据库 / 缓存加载原始数据 */
    DATA_SOURCE,

    /** 数据转换：数据格式转换、映射、清洗 */
    DATA_TRANSFORM,

    /** 因子计算：MFI / CMF / A/D / ATR 等量化因子计算 */
    FACTOR_COMPUTE,

    /** 策略筛选：执行量化选股策略，生成信号 */
    STRATEGY,

    /** 数据增强：板块加权、新闻因子、主力资金加分 */
    ENRICHMENT,

    /** 过滤：基于阈值或规则过滤信号 */
    FILTER,

    /** AI 预测：调用 LLM 进行综合预测分析 */
    AI_PREDICTION,

    /** 聚合：多策略信号合并、去重、排序 */
    AGGREGATION,

    /** 交易动作：生成买入/卖出/持有等交易决策 */
    TRADE_ACTION
}
