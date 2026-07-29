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
 * 在整個 Pipeline 執行生命週期中，各 Node 通過此上下文共享數據。
 * 包含市場環境、策略緩存、配置參數、階段間數據傳遞等。
 *
 * ### 設計原則
 * - 不可變配置（[config]）+ 可變階段輸出（[stageOutputs]）
 * - 延遲加載重型對象（[marketReport]、[adaptiveParams]）
 * - nullable 引用避免強制初始化順序依賴
 *
 * ### 使用示例
 * ```kotlin
 * // 構建上下文
 * val context = PipelineContext(
 *     tradeDate = "2026-07-10",
 *     androidContext = appContext,
 *     smartMoneyCache = SmartMoneyCache
 * )
 *
 * // 延遲加載市場報告
 * val report = context.getMarketReport()
 *
 * // 階段間數據傳遞
 * context.setStageOutput("pool", stockPool)
 * val pool = context.getStageOutput<StockPool>("pool")
 * ```
 *
 * @property tradeDate 交易日（格式 "yyyy-MM-dd"）
 * @property androidContext Android Context（用於數據庫訪問等）
 * @property marketContext 策略市場統一上下文（nullable，按需構建）
 * @property sectorContext AI 預測用的板塊上下文（nullable，按需構建）
 * @property smartMoneyCache 主力資金行為緩存引用
 * @property config Pipeline 執行配置
 * @property stageOutputs 階段間數據傳遞的鍵值存儲
 * @property logger 日誌函數引用（可替換為自定義日誌實現）
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
    //  市場環境（nullable，按需構建）
    // ════════════════════════════════════════════════════

    /** 策略市場統一上下文（用戶關注板塊、熱門板塊、回彈板塊、大盤指數快照） */
    var marketContext: StrategyMarketContext? = null

    /** AI 預測用的板塊上下文 */
    var sectorContext: AIPredictionEngine.SectorContext? = null

    // ════════════════════════════════════════════════════
    //  延遲加載的重型對象
    // ════════════════════════════════════════════════════

    private var _marketReport: MarketAnalyzer.MarketReport? = null
    private var _marketReportLoaded: Boolean = false
    private val _marketReportMutex = kotlinx.coroutines.sync.Mutex()

    private var _adaptiveParams: MarketAdaptiveStrategy.AdaptiveParams? = null
    private var _adaptiveParamsLoaded: Boolean = false
    private val _adaptiveParamsMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * 獲取大盤綜合分析報告（延遲加載，只計算一次）
     *
     * @param holdingCodes 持倉股票代碼列表（首次加載時需要）
     * @return 大盤分析報告，加載失敗時返回 null
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
                logger.log("PipelineContext", "大盤分析報告加載失敗: ${e.message}")
                null
            }
        }
    }

    /**
     * 獲取市場自適應參數（延遲加載，依賴 marketReport）
     *
     * @return 自適應參數，加載失敗時返回 null
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
                logger.log("PipelineContext", "自適應參數計算失敗: ${e.message}")
                null
            }
        }
    }

    // ════════════════════════════════════════════════════
    //  階段間數據傳遞
    // ════════════════════════════════════════════════════

    /** 階段輸出存儲（NodeId/StageName → 數據）— 線程安全（同層節點並行寫入） */
    val stageOutputs: MutableMap<String, Any?> = java.util.Collections.synchronizedMap(mutableMapOf())

    /** 錯誤存儲（NodeId/LinkLabel → 錯誤信息）— 線程安全 */
    val errors: MutableMap<String, String> = java.util.Collections.synchronizedMap(mutableMapOf())

    /**
     * 記錄錯誤到上下文。
     */
    fun recordError(key: String, message: String) {
        errors[key] = message
    }

    /**
     * 存入階段輸出
     *
     * @param key 階段標識（通常使用 nodeId）
     * @param value 階段輸出數據
     */
    fun setStageOutput(key: String, value: Any?) {
        stageOutputs[key] = value
    }

    /**
     * 讀取階段輸出（帶類型安全轉換）
     *
     * @param key 階段標識
     * @return 階段輸出數據，不存在或類型不匹配時返回 null
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> getStageOutput(key: String): T? {
        return stageOutputs[key] as? T
    }

    // ════════════════════════════════════════════════════
    //  便捷方法
    // ════════════════════════════════════════════════════

    /**
     * 從市場上下文中獲取大盤方向（fallback: "UNKNOWN"）
     */
    fun getMarketDirection(): String {
        return marketContext?.indexSnapshot?.tripleVote
            ?: _marketReport?.trend?.direction
            ?: "UNKNOWN"
    }

    // ════════════════════════════════════════════════════
    //  股票流動追蹤（輸入/輸出/過濾統計）
    // ════════════════════════════════════════════════════

    /** 各節點的股票流動記錄 — 線程安全（同層節點並行寫入） */
    val stockFlowLogs: MutableList<StockFlowRecord> = java.util.Collections.synchronizedList(mutableListOf())

    /**
     * 記錄股票流動（輸入→輸出→過濾）。
     *
     * 每個節點在執行後調用，用於追蹤股票數量變化和過濾原因。
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
     * 獲取所有節點的股票流動摘要。
     */
    fun getStockFlowSummary(): String {
        if (stockFlowLogs.isEmpty()) return "無股票流動記錄"
        return stockFlowLogs.joinToString("\n") {
            "${it.nodeName}: ${it.inputCount} → ${it.outputCount}" +
                (if (it.filterCount > 0) " (過濾${it.filterCount}: ${it.filterReason})" else "")
        }
    }

    // ════════════════════════════════════════════════════
    //  節點執行進度回調（供 UI 實時顯示）
    // ════════════════════════════════════════════════════

    /**
     * DAG 節點開始執行時的進度回調（可選）。
     *
     * 參數：(pipelineName, nodeName)。由 [DagPipeline] 在每個節點開始執行前調用，
     * UI 層可據此顯示「[DAG] xx Pipeline 的 xx 節點 執行中...」。
     *
     * 注意：同層節點並行執行時可能被並發調用，實現方需自行保證線程安全
     * （如切換到主線程更新 UI）。
     */
    @Volatile
    var onNodeProgress: ((pipelineName: String, nodeName: String) -> Unit)? = null

    /**
     * 記錄日誌
     */
    fun log(tag: String, message: String) {
        logger.log(tag, message)
    }
}

/**
 * ## 單個 Pipeline 節點的股票流動記錄
 *
 * 記錄每個節點的輸入/輸出/過濾統計，用於 UI 展示和報告保存。
 */
data class StockFlowRecord(
    /** 節點 ID */
    val nodeId: String,
    /** 節點名稱 */
    val nodeName: String,
    /** 輸入股票數量 */
    val inputCount: Int,
    /** 輸出股票數量 */
    val outputCount: Int,
    /** 過濾掉的數量 */
    val filterCount: Int = 0,
    /** 過濾原因（簡短描述，如「未入圍 Top10」「主力資金流出」） */
    val filterReason: String = "",
    /** 輸入的股票代碼（前N個，用於調試） */
    val inputCodes: List<String> = emptyList(),
    /** 輸出的股票代碼（前N個） */
    val outputCodes: List<String> = emptyList()
)

/**
 * ## Pipeline 執行配置
 *
 * 控制 Pipeline 行為的全局配置參數。
 *
 * @property onlyMainBoard 是否只篩選主板股票（滬深主板）
 * @property holdingPeriod 持倉周期："short"（短線）/ "mid"（中線）/ "long"（長線）
 * @property orderType 下單類型："limit"（限價）/ "market"（市價）
 * @property maxHoldings 最大持倉數量
 * @property enableNewsFactor 是否啟用新聞因子增強
 * @property enableSmartMoney 是否啟用主力資金過濾
 * @property enableSectorBoost 是否啟用板塊加權
 * @property enableAIPrediction 是否啟用 AI 綜合預測
 * @property customScoreThreshold 自定義評分閾值（null 時使用 AdaptiveParams 的閾值）
 * @property maxSignalsPerStrategy 每個策略最大信號數量
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
    val maxSignalsPerStrategy: Int = 20
)

/**
 * ## Pipeline 日誌接口
 *
 * 可替換的日誌函數引用，用於解耦日誌實現。
 * 默認使用 Android Log.i，測試時可替換為 System.out 等。
 *
 * ### 使用示例
 * ```kotlin
 * // 默認 Android 日誌
 * val logger = PipelineLogger { tag, msg -> Log.i(tag, msg) }
 *
 * // 測試用標準輸出
 * val testLogger = PipelineLogger { tag, msg -> println("[$tag] $msg") }
 * ```
 */
fun interface PipelineLogger {
    /**
     * 記錄日誌
     *
     * @param tag 日誌標籤
     * @param message 日誌消息
     */
    fun log(tag: String, message: String)
}
