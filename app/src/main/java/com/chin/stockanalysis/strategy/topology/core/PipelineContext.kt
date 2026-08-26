package com.chin.stockanalysis.strategy.topology.core

import android.content.Context
import com.chin.stockanalysis.strategy.data.SmartMoneyCache
import com.chin.stockanalysis.strategy.market.MarketAdaptiveStrategy
import com.chin.stockanalysis.strategy.market.MarketAnalyzer
import com.chin.stockanalysis.strategy.predict.AIPredictionEngine
import com.chin.stockanalysis.strategy.sector.StrategyMarketContext
import kotlinx.coroutines.sync.withLock

/**
 * ## Pipeline 共享上下文
 *
 * 在整个 Pipeline 执行生命周期中，各 Node 通过此上下文共享数据。
 * 包含市场环境、策略缓存、配置参数、阶段间数据传递等。
 *
 * ### 设计原则
 * - 不可变配置（[config]）+ 可变阶段输出（[stageOutputs]）
 * - 延迟加载重型对象（[marketReport]、[adaptiveParams]）
 * - nullable 引用避免强制初始化顺序依赖
 *
 * ### 使用示例
 * ```kotlin
 * // 构建上下文
 * val context = PipelineContext(
 *     tradeDate = "2026-07-10",
 *     androidContext = appContext,
 *     smartMoneyCache = SmartMoneyCache
 * )
 *
 * // 延迟加载市场报告
 * val report = context.getMarketReport()
 *
 * // 阶段间数据传递
 * context.setStageOutput("pool", stockPool)
 * val pool = context.getStageOutput<StockPool>("pool")
 * ```
 *
 * @property tradeDate 交易日（格式 "yyyy-MM-dd"）
 * @property androidContext Android Context（用于数据库访问等）
 * @property marketContext 策略市场统一上下文（nullable，按需构建）
 * @property sectorContext AI 预测用的板块上下文（nullable，按需构建）
 * @property smartMoneyCache 主力资金行为缓存引用
 * @property config Pipeline 执行配置
 * @property stageOutputs 阶段间数据传递的键值存储
 * @property logger 日志函数引用（可替换为自定义日志实现）
 */
class PipelineContext(
    val tradeDate: String,
    val androidContext: Context,
    val smartMoneyCache: SmartMoneyCache,
    val config: PipelineConfig = PipelineConfig(),
    val logger: PipelineLogger = PipelineLogger { tag, msg ->
        android.util.Log.i(tag, msg)
    }
) {

    // ════════════════════════════════════════════════════
    //  市场环境（nullable，按需构建）
    // ════════════════════════════════════════════════════

    /** 策略市场统一上下文（用户关注板块、热门板块、回弹板块、大盘指数快照） */
    var marketContext: StrategyMarketContext? = null

    /** AI 预测用的板块上下文 */
    var sectorContext: AIPredictionEngine.SectorContext? = null

    // ════════════════════════════════════════════════════
    //  延迟加载的重型对象
    // ════════════════════════════════════════════════════

    private var _marketReport: MarketAnalyzer.MarketReport? = null
    private var _marketReportLoaded: Boolean = false
    private val _marketReportMutex = kotlinx.coroutines.sync.Mutex()

    private var _adaptiveParams: MarketAdaptiveStrategy.AdaptiveParams? = null
    private var _adaptiveParamsLoaded: Boolean = false
    private val _adaptiveParamsMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * 获取大盘综合分析报告（延迟加载，只计算一次）
     *
     * @param holdingCodes 持仓股票代码列表（首次加载时需要）
     * @return 大盘分析报告，加载失败时返回 null
     */
    suspend fun getMarketReport(holdingCodes: List<String> = emptyList()): MarketAnalyzer.MarketReport? {
        if (_marketReportLoaded) return _marketReport
        return _marketReportMutex.withLock {
            if (_marketReportLoaded) return _marketReport
            _marketReportLoaded = true
            try {
                _marketReport = MarketAnalyzer.analyze(androidContext, holdingCodes)
                _marketReport
            } catch (e: Exception) {
                logger.log("PipelineContext", "大盘分析报告加载失败: ${e.message}")
                null
            }
        }
    }

    /**
     * 获取市场自适应参数（延迟加载，依赖 marketReport）
     *
     * @return 自适应参数，加载失败时返回 null
     */
    suspend fun getAdaptiveParams(): MarketAdaptiveStrategy.AdaptiveParams? {
        if (_adaptiveParamsLoaded) return _adaptiveParams
        return _adaptiveParamsMutex.withLock {
            if (_adaptiveParamsLoaded) return _adaptiveParams
            _adaptiveParamsLoaded = true
            try {
                val report = getMarketReport()
                if (report != null) {
                    _adaptiveParams = MarketAdaptiveStrategy.calculate(report)
                }
                _adaptiveParams
            } catch (e: Exception) {
                logger.log("PipelineContext", "自适应参数计算失败: ${e.message}")
                null
            }
        }
    }

    // ════════════════════════════════════════════════════
    //  阶段间数据传递
    // ════════════════════════════════════════════════════

    /** 阶段输出存储（NodeId/StageName → 数据）— 线程安全（同层节点并行写入） */
    val stageOutputs: MutableMap<String, Any?> = java.util.Collections.synchronizedMap(mutableMapOf())

    /** 错误存储（NodeId/LinkLabel → 错误信息）— 线程安全 */
    val errors: MutableMap<String, String> = java.util.Collections.synchronizedMap(mutableMapOf())

    /**
     * 记录错误到上下文。
     */
    fun recordError(key: String, message: String) {
        errors[key] = message
    }

    /**
     * 存入阶段输出
     *
     * @param key 阶段标识（通常使用 nodeId）
     * @param value 阶段输出数据
     */
    fun setStageOutput(key: String, value: Any?) {
        stageOutputs[key] = value
    }

    /**
     * 读取阶段输出（带类型安全转换）
     *
     * @param key 阶段标识
     * @return 阶段输出数据，不存在或类型不匹配时返回 null
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> getStageOutput(key: String): T? {
        return stageOutputs[key] as? T
    }

    // ════════════════════════════════════════════════════
    //  便捷方法
    // ════════════════════════════════════════════════════

    /**
     * 从市场上下文中获取大盘方向（fallback: "UNKNOWN"）
     */
    fun getMarketDirection(): String {
        return marketContext?.indexSnapshot?.tripleVote
            ?: _marketReport?.trend?.direction
            ?: "UNKNOWN"
    }

    // ════════════════════════════════════════════════════
    //  股票流动追踪（输入/输出/过滤统计）
    // ════════════════════════════════════════════════════

    /** 各节点的股票流动记录 — 线程安全（同层节点并行写入） */
    val stockFlowLogs: MutableList<StockFlowRecord> = java.util.Collections.synchronizedList(mutableListOf())

    /**
     * 记录股票流动（输入→输出→过滤）。
     *
     * 每个节点在执行后调用，用于追踪股票数量变化和过滤原因。
     */
    fun recordStockFlow(
        nodeId: String,
        nodeName: String,
        inputCount: Int,
        outputCount: Int,
        filterCount: Int = 0,
        filterReason: String = "",
        inputCodes: List<String> = emptyList(),
        outputCodes: List<String> = emptyList()
    ) {
        stockFlowLogs.add(
            StockFlowRecord(
                nodeId = nodeId,
                nodeName = nodeName,
                inputCount = inputCount,
                outputCount = outputCount,
                filterCount = filterCount,
                filterReason = filterReason,
                inputCodes = inputCodes,
                outputCodes = outputCodes
            )
        )
    }

    /**
     * 获取所有节点的股票流动摘要。
     */
    fun getStockFlowSummary(): String {
        if (stockFlowLogs.isEmpty()) return "无股票流动记录"
        return stockFlowLogs.joinToString("\n") {
            "${it.nodeName}: ${it.inputCount} → ${it.outputCount}" +
                (if (it.filterCount > 0) " (过滤${it.filterCount}: ${it.filterReason})" else "")
        }
    }

    // ════════════════════════════════════════════════════
    //  节点执行进度回调（供 UI 实时显示）
    // ════════════════════════════════════════════════════

    /**
     * DAG 节点开始执行时的进度回调（可选）。
     *
     * 参数：(pipelineName, nodeName)。由 [DagPipeline] 在每个节点开始执行前调用，
     * UI 层可据此显示「[DAG] xx Pipeline 的 xx 节点 执行中...」。
     *
     * 注意：同层节点并行执行时可能被并发调用，实现方需自行保证线程安全
     * （如切换到主线程更新 UI）。
     */
    @Volatile
    var onNodeProgress: ((pipelineName: String, nodeName: String) -> Unit)? = null

    /**
     * 节点完成回调（pipelineName, nodeName, 输出摘要）。
     * 在节点执行成功/失败后回传，供 UI 展示节点结果（如"风格轮动判断 → 均衡震荡"）。
     * 注意：同层节点并行执行时可能被并发调用，实现方需自行保证线程安全。
     */
    @Volatile
    var onNodeDone: ((pipelineName: String, nodeName: String, output: Any?) -> Unit)? = null

    /**
     * 记录日志
     */
    fun log(tag: String, message: String) {
        logger.log(tag, message)
    }
}

/**
 * ## 单个 Pipeline 节点的股票流动记录
 *
 * 记录每个节点的输入/输出/过滤统计，用于 UI 展示和报告保存。
 */
data class StockFlowRecord(
    /** 节点 ID */
    val nodeId: String,
    /** 节点名称 */
    val nodeName: String,
    /** 输入股票数量 */
    val inputCount: Int,
    /** 输出股票数量 */
    val outputCount: Int,
    /** 过滤掉的数量 */
    val filterCount: Int = 0,
    /** 过滤原因（简短描述，如「未入围 Top10」「主力资金流出」） */
    val filterReason: String = "",
    /** 输入的股票代码（前N个，用于调试） */
    val inputCodes: List<String> = emptyList(),
    /** 输出的股票代码（前N个） */
    val outputCodes: List<String> = emptyList()
)

/**
 * ## Pipeline 执行配置
 *
 * 控制 Pipeline 行为的全局配置参数。
 *
 * @property onlyMainBoard 是否只筛选主板股票（沪深主板）
 * @property holdingPeriod 持仓周期："short"（短线）/ "mid"（中线）/ "long"（长线）
 * @property orderType 下单类型："limit"（限价）/ "market"（市价）
 * @property maxHoldings 最大持仓数量
 * @property enableNewsFactor 是否启用新闻因子增强
 * @property enableSmartMoney 是否启用主力资金过滤
 * @property enableSectorBoost 是否启用板块加权
 * @property enableAIPrediction 是否启用 AI 综合预测
 * @property customScoreThreshold 自定义评分阈值（null 时使用 AdaptiveParams 的阈值）
 * @property maxSignalsPerStrategy 每个策略最大信号数量
 * @property saveAsAiOnly 非交易时间一键建仓的「仅选股」模式：跳过买入订单/持仓合并/换仓/拟合，
 *   仅将选股结果写入 user_watchlist + ai_selected_stock（股票Tab → 🤖 AI 精选）
 */
data class PipelineConfig(
    val onlyMainBoard: Boolean = true,
    val holdingPeriod: String = "short",
    val orderType: String = "limit",
    val maxHoldings: Int = 5,
    val enableNewsFactor: Boolean = true,
    val enableSmartMoney: Boolean = true,
    val enableSectorBoost: Boolean = true,
    val enableAIPrediction: Boolean = true,
    val customScoreThreshold: Int? = null,
    val maxSignalsPerStrategy: Int = 20,
    val saveAsAiOnly: Boolean = false
)

/**
 * ## Pipeline 日志接口
 *
 * 可替换的日志函数引用，用于解耦日志实现。
 * 默认使用 Android Log.i，测试时可替换为 System.out 等。
 *
 * ### 使用示例
 * ```kotlin
 * // 默认 Android 日志
 * val logger = PipelineLogger { tag, msg -> Log.i(tag, msg) }
 *
 * // 测试用标准输出
 * val testLogger = PipelineLogger { tag, msg -> println("[$tag] $msg") }
 * ```
 */
fun interface PipelineLogger {
    /**
     * 记录日志
     *
     * @param tag 日志标签
     * @param message 日志消息
     */
    fun log(tag: String, message: String)
}
