package com.chin.stockanalysis.strategy.topology.nodes

import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import com.chin.stockanalysis.strategy.topology.core.*

/**
 * ## 大盤均線粘合向上檢查節點
 *
 * 檢查大盤（上證 sh000001）是否處於均線粘合向上狀態。
 * 輸出布林值供下游節點判斷是否適合進場。
 *
 * ### 判定條件
 * - MA5 > MA10 > MA20（多頭排列）
 * - MA5 與 MA20 離散率 < 3%（粘合）
 * - MA5 斜率 > 0（向上）
 *
 * ### 輸出
 * [MarketMaCheckResult] 存入 context.setStageOutput("n_market_ma_check", result)
 */
data class MarketMaCheckResult(
    val isConvergedUpward: Boolean = false,
    val ma5: Double = 0.0,
    val ma10: Double = 0.0,
    val ma20: Double = 0.0,
    val divergencePct: Double = 0.0,  // MA5-MA20 離散率 %
    val ma5Slope: Double = 0.0,       // MA5 斜率（3日變化率）
    val description: String = ""
)

class MarketMaConvergenceCheckNode : PipelineNode<Any, MarketMaCheckResult> {

    companion object {
        private const val TAG = "MarketMaCheck"
        private const val INDEX_CODE = "sh000001"
        private const val DIVERGENCE_THRESHOLD = 0.03  // 3%
    }

    override val nodeId: String = "market_ma_check"
    override val nodeName: String = "大盤均線粘合檢查"
    override val nodeType: NodeType = NodeType.FACTOR_COMPUTE

    override suspend fun execute(context: PipelineContext, input: Any): MarketMaCheckResult {
        return try {
            val db = StockDatabase.getInstance(context.androidContext)
            val snaps = db.dailySnapshotDao().getByCode(INDEX_CODE, 30)
            if (snaps.size < 20) {
                context.log(nodeId, "$nodeName: 指數數據不足(${snaps.size}條)")
                return MarketMaCheckResult(description = "數據不足")
            }

            val sorted = snaps.sortedBy { it.date }
            val closes = sorted.map { it.close }

            val ma5 = closes.takeLast(5).average()
            val ma10 = closes.takeLast(10).average()
            val ma20 = closes.takeLast(20).average()

            // 離散率：(MA5 - MA20) / MA20
            val divergence = if (ma20 > 0) (ma5 - ma20) / ma20 else 0.0

            // MA5 斜率：最近3日 MA5 變化
            val ma5Prev = if (closes.size >= 8) closes.takeLast(8).take(5).average() else ma5
            val slope = if (ma5Prev > 0) (ma5 - ma5Prev) / ma5Prev else 0.0

            // 判定：多頭排列 + 粘合 + 向上
            val isBullishAligned = ma5 > ma10 && ma10 > ma20
            val isConverged = kotlin.math.abs(divergence) < DIVERGENCE_THRESHOLD
            val isUpward = slope > 0

            val result = MarketMaCheckResult(
                isConvergedUpward = isBullishAligned && isConverged && isUpward,
                ma5 = ma5,
                ma10 = ma10,
                ma20 = ma20,
                divergencePct = divergence * 100,
                ma5Slope = slope * 100,
                description = buildString {
                    append("MA5=${"%.2f".format(ma5)} MA10=${"%.2f".format(ma10)} MA20=${"%.2f".format(ma20)}")
                    append(" 離散=${"%.2f".format(divergence * 100)}%")
                    append(" 斜率=${"%.2f".format(slope * 100)}%")
                    append(if (isBullishAligned) " 多頭✓" else " 非多頭")
                    append(if (isConverged) " 粘合✓" else " 未粘合")
                    append(if (isUpward) " 向上✓" else " 向下")
                }
            )

            context.log(nodeId, "$nodeName: ${result.description} → ${if (result.isConvergedUpward) "適合進場" else "不宜進場"}")
            context.setStageOutput(nodeId, result)
            result
        } catch (e: Exception) {
            context.log(nodeId, "$nodeName 異常: ${e.message}")
            MarketMaCheckResult(description = "異常: ${e.message}")
        }
    }
}

/**
 * ## 嚴選條件檢查節點（6 項嚴選）
 *
 * 對 MergedSignalPool 中的每只股票評估 6 項嚴選條件：
 * 1. 均線粘合向上：MA5 > MA10 > MA20，離散率 < 3%
 * 2. 三日不新低：最近 3 日最低價均 > 前低
 * 3. 歷史低位 25%：當前價在 60 日區間底部 25%
 * 4. PE < 30：無泡沫
 * 5. 周期活躍度：60 日內 ≥3 天漲跌幅 > 3%
 * 6. 冰點買入：換手率 < 2% 且 量比 < 1.0
 *
 * ### 輸出
 * [StrictSelectionResult] 存入 context.setStageOutput("n_strict_selection", result)
 */
data class StrictSelectionResult(
    val passedStocks: Map<String, StrictSelectionDetail> = emptyMap(),
    val totalCount: Int = 0,
    val passedCount: Int = 0
)

data class StrictSelectionDetail(
    val code: String,
    val name: String,
    val maConvergedUp: Boolean = false,
    val threeDayNoNewLow: Boolean = false,
    val historicalLow25: Boolean = false,
    val peLow: Boolean = false,
    val cyclicalActive: Boolean = false,
    val freezingPoint: Boolean = false,
    val passCount: Int = 0,  // 通過幾項（滿 6 項）
    val allPassed: Boolean = false
)

class StrictSelectionNode : PipelineNode<MergedSignalPool, StrictSelectionResult> {

    companion object {
        private const val TAG = "StrictSelection"
        private const val MA_DIVERGENCE_THRESHOLD = 0.03  // 3%
        private const val PE_THRESHOLD = 30.0
        private const val HISTORICAL_LOW_PCT = 0.25
        private const val ACTIVE_DAYS_THRESHOLD = 3
        private const val ACTIVE_CHANGE_THRESHOLD = 0.03  // 3%
        private const val TURNOVER_THRESHOLD = 2.0  // 2%
        private const val VOLUME_RATIO_THRESHOLD = 1.0
        private const val LOOKBACK_DAYS = 60
    }

    override val nodeId: String = "strict_selection"
    override val nodeName: String = "六項嚴選檢查"
    override val nodeType: NodeType = NodeType.FILTER

    override suspend fun execute(context: PipelineContext, input: MergedSignalPool): StrictSelectionResult {
        val db = StockDatabase.getInstance(context.androidContext)
        val passedMap = mutableMapOf<String, StrictSelectionDetail>()

        // 遍歷所有候選股票
        for ((code, hits) in input.stockHits) {
            try {
                val snaps = db.dailySnapshotDao().getByCode(code, LOOKBACK_DAYS + 5)
                    .sortedBy { it.date }
                if (snaps.size < 20) continue

                val latest = snaps.last()
                val closes = snaps.map { it.close }
                val name = input.stockNames[code] ?: latest.name

                // 1. 均線粘合向上
                val ma5 = closes.takeLast(5).average()
                val ma10 = closes.takeLast(10).average()
                val ma20 = closes.takeLast(20).average()
                val divergence = if (ma20 > 0) (ma5 - ma20) / ma20 else 1.0
                val maConvergedUp = ma5 > ma10 && ma10 > ma20 && divergence < MA_DIVERGENCE_THRESHOLD

                // 2. 三日不新低
                val recent3 = snaps.takeLast(3)
                val threeDayNoNewLow = if (recent3.size >= 3) {
                    val lows = recent3.map { it.low }
                    // 每日最低價均不低於前一日最低價
                    lows[0] <= lows[1] && lows[1] <= lows[2]
                } else false

                // 3. 歷史低位 25%
                val high60 = closes.maxOrNull() ?: latest.close
                val low60 = closes.minOrNull() ?: latest.close
                val range = high60 - low60
                val positionInRange = if (range > 0) (latest.close - low60) / range else 0.5
                val historicalLow25 = positionInRange <= HISTORICAL_LOW_PCT

                // 4. PE < 30
                val peLow = latest.pe > 0 && latest.pe < PE_THRESHOLD

                // 5. 周期活躍度：60日內 ≥3 天漲跌幅 > 3%
                val activeDays = snaps.takeLast(LOOKBACK_DAYS).count {
                    kotlin.math.abs(it.changePct) > ACTIVE_CHANGE_THRESHOLD * 100
                }
                val cyclicalActive = activeDays >= ACTIVE_DAYS_THRESHOLD

                // 6. 冰點買入：換手率 < 2% 且 量比 < 1.0
                val turnoverOk = latest.turnoverRate < TURNOVER_THRESHOLD
                // 量比 = 今日成交量 / 過去5日平均成交量
                val avgVolume5 = if (snaps.size >= 6) {
                    snaps.takeLast(6).dropLast(1).map { it.volume.toDouble() }.average()
                } else latest.volume.toDouble()
                val volumeRatio = if (avgVolume5 > 0) latest.volume.toDouble() / avgVolume5 else 1.0
                val freezingPoint = turnoverOk && volumeRatio < VOLUME_RATIO_THRESHOLD

                // 統計通過項數
                val passCount = listOf(maConvergedUp, threeDayNoNewLow, historicalLow25,
                    peLow, cyclicalActive, freezingPoint).count { it }

                val detail = StrictSelectionDetail(
                    code = code,
                    name = name,
                    maConvergedUp = maConvergedUp,
                    threeDayNoNewLow = threeDayNoNewLow,
                    historicalLow25 = historicalLow25,
                    peLow = peLow,
                    cyclicalActive = cyclicalActive,
                    freezingPoint = freezingPoint,
                    passCount = passCount,
                    allPassed = passCount == 6
                )

                if (passCount >= 4) {  // 至少通過 4 項才記錄
                    passedMap[code] = detail
                }

            } catch (_: Exception) {}
        }

        val result = StrictSelectionResult(
            passedStocks = passedMap,
            totalCount = input.stockHits.size,
            passedCount = passedMap.count { it.value.allPassed }
        )

        context.log(nodeId, "$nodeName: ${input.stockHits.size} 只候選 → " +
            "${passedMap.size} 只通過≥4項，${result.passedCount} 只全部通過")
        if (passedMap.isNotEmpty()) {
            val top3 = passedMap.values.sortedByDescending { it.passCount }.take(3)
            context.log(nodeId, "$nodeName TOP3: ${top3.joinToString { "${it.name}(${it.passCount}/6)" }}")
        }

        context.setStageOutput(nodeId, result)
        return result
    }
}

/**
 * ## 嚴選條件單股評估（供 Agent 分析流程調用）
 *
 * 從 [StrictSelectionNode] 提取核心邏輯，不依賴 PipelineContext。
 * Agent 完成分析後，作為最終買入關卡調用。
 */
object StrictSelectionChecker {

    suspend fun evaluate(stockCode: String, db: StockDatabase): StrictSelectionDetail {
        val snaps = db.dailySnapshotDao().getByCode(stockCode, 65)
            .sortedBy { it.date }
        if (snaps.size < 20) {
            return StrictSelectionDetail(code = stockCode, name = "", passCount = 0)
        }

        val latest = snaps.last()
        val closes = snaps.map { it.close }
        val name = latest.name

        // 1. 均線粘合向上
        val ma5 = closes.takeLast(5).average()
        val ma10 = closes.takeLast(10).average()
        val ma20 = closes.takeLast(20).average()
        val divergence = if (ma20 > 0) (ma5 - ma20) / ma20 else 1.0
        val maConvergedUp = ma5 > ma10 && ma10 > ma20 && divergence < 0.03

        // 2. 三日不新低
        val recent3 = snaps.takeLast(3)
        val threeDayNoNewLow = if (recent3.size >= 3) {
            val lows = recent3.map { it.low }
            lows[0] <= lows[1] && lows[1] <= lows[2]
        } else false

        // 3. 歷史低位 25%
        val high60 = closes.maxOrNull() ?: latest.close
        val low60 = closes.minOrNull() ?: latest.close
        val range = high60 - low60
        val positionInRange = if (range > 0) (latest.close - low60) / range else 0.5
        val historicalLow25 = positionInRange <= 0.25

        // 4. PE < 30
        val peLow = latest.pe > 0 && latest.pe < 30.0

        // 5. 周期活躍度：60日內 ≥3 天漲跌幅 > 3%
        val activeDays = snaps.takeLast(60).count {
            kotlin.math.abs(it.changePct) > 3.0
        }
        val cyclicalActive = activeDays >= 3

        // 6. 冰點買入：換手率 < 2% 且 量比 < 1.0
        val turnoverOk = latest.turnoverRate < 2.0
        val avgVolume5 = if (snaps.size >= 6) {
            snaps.takeLast(6).dropLast(1).map { it.volume.toDouble() }.average()
        } else latest.volume.toDouble()
        val volumeRatio = if (avgVolume5 > 0) latest.volume.toDouble() / avgVolume5 else 1.0
        val freezingPoint = turnoverOk && volumeRatio < 1.0

        val passCount = listOf(maConvergedUp, threeDayNoNewLow, historicalLow25,
            peLow, cyclicalActive, freezingPoint).count { it }

        return StrictSelectionDetail(
            code = stockCode, name = name,
            maConvergedUp = maConvergedUp,
            threeDayNoNewLow = threeDayNoNewLow,
            historicalLow25 = historicalLow25,
            peLow = peLow,
            cyclicalActive = cyclicalActive,
            freezingPoint = freezingPoint,
            passCount = passCount,
            allPassed = passCount == 6
        )
    }

    /** 格式化成可讀摘要，供報告附加 */
    fun formatResult(detail: StrictSelectionDetail): String = buildString {
        val passed = detail.passCount >= 4
        append(if (passed) "✅" else "⚠️")
        append(" 嚴選檢查: ${detail.passCount}/6")
        if (detail.name.isNotBlank()) append(" (${detail.name})")
        appendLine()
        append("  ${if (detail.maConvergedUp) "✓" else "✗"}均線粘合向上")
        append("  ${if (detail.threeDayNoNewLow) "✓" else "✗"}三日不新低")
        append("  ${if (detail.historicalLow25) "✓" else "✗"}歷史低位")
        appendLine()
        append("  ${if (detail.peLow) "✓" else "✗"}PE<30")
        append("  ${if (detail.cyclicalActive) "✓" else "✗"}周期活躍")
        append("  ${if (detail.freezingPoint) "✓" else "✗"}冰點買入")
        if (!passed) appendLine("\n  ⚠️ 未達≥4項門檻，建議觀望")
    }
}
