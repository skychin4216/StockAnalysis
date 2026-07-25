package com.chin.stockanalysis.strategy.topology.core

import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.strategy.models.StrategySignal

/**
 * ## Pipeline 標準化數據包
 *
 * 定義 Pipeline 各階段之間傳遞的數據類型。
 * 每個數據包對應一種典型的 Node 輸入/輸出：
 *
 * ```
 * DATA_SOURCE       → StockPool
 * STRATEGY          → SignalPack
 * AGGREGATION       → MergedSignalPool
 * FILTER            → FilterResult
 * ```
 *
 * 所有數據包均為不可變 data class，確保線程安全。
 */

// ════════════════════════════════════════════════════
//  數據源 → 策略
// ════════════════════════════════════════════════════

/**
 * ## 股票池
 *
 * 數據源節點的輸出類型，包裝一組待篩選的股票實時數據。
 *
 * @property stocks 股票實時數據列表
 * @property source 數據來源標識（如 "market_all", "hot_sector", "watchlist"）
 * @property totalCount 原始總數（過濾前的數量，用於計算命中率）
 * @property filterReason 如果經過初步過濾，記錄過濾原因
 */
data class StockPool(
    val stocks: List<StockRealtime>,
    val source: String,
    val totalCount: Int = stocks.size,
    val filterReason: String = ""
) {
    /** 股票代碼集合（快速查找） */
    val codeSet: Set<String> get() = stocks.map { it.code }.toSet()

    /** 空池判斷 */
    val isEmpty: Boolean get() = stocks.isEmpty()

    /** 池大小 */
    val size: Int get() = stocks.size
}

// ════════════════════════════════════════════════════
//  策略 → 增強 / 過濾
// ════════════════════════════════════════════════════

/**
 * ## 信號包
 *
 * 單個策略篩選後的輸出，包含命中的信號列表和增強分數。
 *
 * @property strategyId 策略唯一標識
 * @property strategyName 策略名稱（人類可讀）
 * @property signals 命中的策略信號列表
 * @property newsStrengthScore 新聞因子加分（由新聞增強節點計算，0 表示未增強）
 * @property rotationPenalty 板塊輪動懲罰分數（由輪動檢測節點計算，0 表示無懲罰）
 */
data class SignalPack(
    val strategyId: String,
    val strategyName: String,
    val signals: List<StrategySignal>,
    val newsStrengthScore: Int = 0,
    val rotationPenalty: Int = 0
) {
    /** 命中數量 */
    val hitCount: Int get() = signals.size

    /** 信號總強度 */
    val totalStrength: Int get() = signals.sumOf { it.strength }

    /** 有效信號數（去掉懲罰後仍為正的信號） */
    val effectiveCount: Int get() = signals.count {
        (it.strength + newsStrengthScore - rotationPenalty) > 0
    }
}

// ════════════════════════════════════════════════════
//  聚合 → 過濾 / 輸出
// ════════════════════════════════════════════════════

/**
 * ## 合併信號池
 *
 * 多策略信號聚合後的輸出，包含跨策略的股票命中統計。
 *
 * @property stockHits 每隻股票被命中的策略列表：stockCode → [(strategyId, strength), ...]
 * @property stockNames 股票代碼 → 名稱映射
 * @property boostedSignals 經過板塊加權 / 新聞增強後的排序信號列表
 */
data class MergedSignalPool(
    val stockHits: Map<String, List<Pair<String, Int>>>,
    val stockNames: Map<String, String>,
    val boostedSignals: List<StrategySignal>
) {
    /** 涉及的股票總數 */
    val totalStocks: Int get() = stockHits.size

    /** 多策略命中的股票（被 >= 2 個策略命中） */
    val multiHitStocks: List<String> get() = stockHits.filter { it.value.size >= 2 }.keys.toList()

    /** 按命中策略數排序的股票列表 */
    val rankedStocks: List<String> get() = stockHits.entries
        .sortedByDescending { it.value.size }
        .map { it.key }
}

// ════════════════════════════════════════════════════
//  過濾 → AI 預測 / 交易動作
// ════════════════════════════════════════════════════

/**
 * ## 過濾結果
 *
 * 過濾節點的輸出，明確區分通過和被淘汰的信號。
 *
 * @property passed 通過過濾的信號列表
 * @property rejected 被淘汰的股票信息（含淘汰原因）
 */
data class FilterResult(
    val passed: List<StrategySignal>,
    val rejected: List<FilteredStockInfo>
) {
    /** 通過數量 */
    val passCount: Int get() = passed.size

    /** 淘汰數量 */
    val rejectCount: Int get() = rejected.size

    /** 通過率 */
    val passRate: Float get() {
        val total = passCount + rejectCount
        return if (total > 0) passCount.toFloat() / total else 0f
    }

    /** 合併通過 + 淘汰的總數 */
    val totalCount: Int get() = passCount + rejectCount
}

/**
 * ## 被過濾的股票信息
 *
 * 記錄被淘汰股票的詳細信息，用於調試分析和日誌記錄。
 *
 * @property code 股票代碼
 * @property name 股票名稱
 * @property reason 淘汰原因（人類可讀，如 "強度低於門檻 55"）
 * @property originalStrength 原始信號強度（過濾前的值）
 */
data class FilteredStockInfo(
    val code: String,
    val name: String,
    val reason: String,
    val originalStrength: Int
)
