package com.chin.stockanalysis.strategy.topology.core

import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.strategy.models.StrategySignal

/**
 * ## Pipeline 标准化数据包
 *
 * 定义 Pipeline 各阶段之间传递的数据类型。
 * 每个数据包对应一种典型的 Node 输入/输出：
 *
 * ```
 * DATA_SOURCE       → StockPool
 * STRATEGY          → SignalPack
 * AGGREGATION       → MergedSignalPool
 * FILTER            → FilterResult
 * ```
 *
 * 所有数据包均为不可变 data class，确保线程安全。
 */

// ════════════════════════════════════════════════════
//  数据源 → 策略
// ════════════════════════════════════════════════════

/**
 * ## 股票池
 *
 * 数据源节点的输出类型，包装一组待筛选的股票实时数据。
 *
 * @property stocks 股票实时数据列表
 * @property source 数据来源标识（如 "market_all", "hot_sector", "watchlist"）
 * @property totalCount 原始总数（过滤前的数量，用于计算命中率）
 * @property filterReason 如果经过初步过滤，记录过滤原因
 */
data class StockPool(
    val stocks: List<StockRealtime>,
    val source: String,
    val totalCount: Int = stocks.size,
    val filterReason: String = ""
) {
    /** 股票代码集合（快速查找） */
    val codeSet: Set<String> get() = stocks.map { it.code }.toSet()

    /** 空池判断 */
    val isEmpty: Boolean get() = stocks.isEmpty()

    /** 池大小 */
    val size: Int get() = stocks.size
}

// ════════════════════════════════════════════════════
//  策略 → 增强 / 过滤
// ════════════════════════════════════════════════════

/**
 * ## 信号包
 *
 * 单个策略筛选后的输出，包含命中的信号列表和增强分数。
 *
 * @property strategyId 策略唯一标识
 * @property strategyName 策略名称（人类可读）
 * @property signals 命中的策略信号列表
 * @property newsStrengthScore 新闻因子加分（由新闻增强节点计算，0 表示未增强）
 * @property rotationPenalty 板块轮动惩罚分数（由轮动检测节点计算，0 表示无惩罚）
 */
data class SignalPack(
    val strategyId: String,
    val strategyName: String,
    val signals: List<StrategySignal>,
    val newsStrengthScore: Int = 0,
    val rotationPenalty: Int = 0
) {
    /** 命中数量 */
    val hitCount: Int get() = signals.size

    /** 信号总强度 */
    val totalStrength: Int get() = signals.sumOf { it.strength }

    /** 有效信号数（去掉惩罚后仍为正的信号） */
    val effectiveCount: Int get() = signals.count {
        (it.strength + newsStrengthScore - rotationPenalty) > 0
    }
}

// ════════════════════════════════════════════════════
//  聚合 → 过滤 / 输出
// ════════════════════════════════════════════════════

/**
 * ## 合并信号池
 *
 * 多策略信号聚合后的输出，包含跨策略的股票命中统计。
 *
 * @property stockHits 每只股票被命中的策略列表：stockCode → [(strategyId, strength), ...]
 * @property stockNames 股票代码 → 名称映射
 * @property boostedSignals 经过板块加权 / 新闻增强后的排序信号列表
 */
data class MergedSignalPool(
    val stockHits: Map<String, List<Pair<String, Int>>>,
    val stockNames: Map<String, String>,
    val boostedSignals: List<StrategySignal>
) {
    /** 涉及的股票总数 */
    val totalStocks: Int get() = stockHits.size

    /** 多策略命中的股票（被 >= 2 个策略命中） */
    val multiHitStocks: List<String> get() = stockHits.filter { it.value.size >= 2 }.keys.toList()

    /** 按命中策略数排序的股票列表 */
    val rankedStocks: List<String> get() = stockHits.entries
        .sortedByDescending { it.value.size }
        .map { it.key }
}

// ════════════════════════════════════════════════════
//  过滤 → AI 预测 / 交易动作
// ════════════════════════════════════════════════════

/**
 * ## 过滤结果
 *
 * 过滤节点的输出，明确区分通过和被淘汰的信号。
 *
 * @property passed 通过过滤的信号列表
 * @property rejected 被淘汰的股票信息（含淘汰原因）
 */
data class FilterResult(
    val passed: List<StrategySignal>,
    val rejected: List<FilteredStockInfo>
) {
    /** 通过数量 */
    val passCount: Int get() = passed.size

    /** 淘汰数量 */
    val rejectCount: Int get() = rejected.size

    /** 通过率 */
    val passRate: Float get() {
        val total = passCount + rejectCount
        return if (total > 0) passCount.toFloat() / total else 0f
    }

    /** 合并通过 + 淘汰的总数 */
    val totalCount: Int get() = passCount + rejectCount
}

/**
 * ## 被过滤的股票信息
 *
 * 记录被淘汰股票的详细信息，用于调试分析和日志记录。
 *
 * @property code 股票代码
 * @property name 股票名称
 * @property reason 淘汰原因（人类可读，如 "强度低于门槛 55"）
 * @property originalStrength 原始信号强度（过滤前的值）
 */
data class FilteredStockInfo(
    val code: String,
    val name: String,
    val reason: String,
    val originalStrength: Int
)
