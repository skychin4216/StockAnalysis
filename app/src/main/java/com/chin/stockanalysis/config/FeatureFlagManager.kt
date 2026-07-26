package com.chin.stockanalysis.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.chin.stockanalysis.strategy.HoldingPeriod

/**
 * ## 全局模式
 * - LEGACY: 全部使用 Legacy 系統
 * - AGENT: 全部使用 Agent 框架
 * - HYBRID: 按模塊混合配置
 */
enum class GlobalMode {
    LEGACY, AGENT, HYBRID
}

/**
 * ## 路由選擇
 * - LEGACY: 舊系統
 * - AGENT_FRAMEWORK: 新 Agent 框架
 * - AUTO: 自動 fallback
 */
enum class AgentRoute {
    LEGACY,
    AGENT_FRAMEWORK,
    AUTO
}

object FeatureFlagManager {

    private const val TAG = "FeatureFlagManager"
    private const val PREFS_NAME = "feature_flags"

    // ── 各模塊路線開關（key）
    private const val KEY_GLOBAL_MODE       = "global_mode_v2"
    private const val KEY_STOCK_PICKING     = "route_stock_picking"
    private const val KEY_STOCK_ANALYSIS    = "route_stock_analysis"
    private const val KEY_TRADE_EXECUTION   = "route_trade_execution"
    private const val KEY_CHAT              = "route_chat"
    private const val KEY_NEWS_MONITOR      = "route_news_monitor"
    private const val KEY_RISK_MANAGEMENT   = "route_risk_management"

    // ── 通用 DAG 開關（適用於所有週期） ──
    private const val KEY_USE_DAG_PIPELINE   = "use_dag_pipeline"

    private lateinit var prefs: SharedPreferences

    /** 初始化（在 Application.onCreate 或 MainActivity.onCreate 中調用） */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        Log.i(TAG, "FeatureFlagManager 初始化完成")
    }

    // ═══════════════════════════════════════════════════════════════
    // 全局模式
    // ═══════════════════════════════════════════════════════════════

    /** 全局模式：LEGACY / AGENT / HYBRID */
    var globalMode: GlobalMode
        get() = loadGlobalMode()
        set(value) {
            prefs.edit().putString(KEY_GLOBAL_MODE, value.name).apply()
            Log.i(TAG, "全局模式切換: $value")
        }

    /** 是否為「混合」模式（此時模塊級開關才可操作） */
    val isHybrid: Boolean
        get() = globalMode == GlobalMode.HYBRID

    private fun loadGlobalMode(): GlobalMode {
        val name = prefs.getString(KEY_GLOBAL_MODE, GlobalMode.LEGACY.name)
        return try {
            GlobalMode.valueOf(name ?: GlobalMode.LEGACY.name)
        } catch (_: IllegalArgumentException) {
            GlobalMode.LEGACY
        }
    }

    /** 一鍵切換全部模塊到 Agent */
    fun enableAllAgentFramework() {
        globalMode = GlobalMode.AGENT
        prefs.edit().apply {
            putString(KEY_STOCK_PICKING, AgentRoute.AGENT_FRAMEWORK.name)
            putString(KEY_STOCK_ANALYSIS, AgentRoute.AGENT_FRAMEWORK.name)
            putString(KEY_TRADE_EXECUTION, AgentRoute.AGENT_FRAMEWORK.name)
            putString(KEY_CHAT, AgentRoute.AGENT_FRAMEWORK.name)
            putString(KEY_NEWS_MONITOR, AgentRoute.AGENT_FRAMEWORK.name)
            putString(KEY_RISK_MANAGEMENT, AgentRoute.AGENT_FRAMEWORK.name)
        }.apply()
        Log.i(TAG, "已一鍵啟用全部 Agent 路線")
    }

    /** 一鍵切換全部模塊到 Legacy */
    fun disableAllAgentFramework() {
        globalMode = GlobalMode.LEGACY
        prefs.edit().apply {
            putString(KEY_STOCK_PICKING, AgentRoute.LEGACY.name)
            putString(KEY_STOCK_ANALYSIS, AgentRoute.LEGACY.name)
            putString(KEY_TRADE_EXECUTION, AgentRoute.LEGACY.name)
            putString(KEY_CHAT, AgentRoute.LEGACY.name)
            putString(KEY_NEWS_MONITOR, AgentRoute.LEGACY.name)
            putString(KEY_RISK_MANAGEMENT, AgentRoute.LEGACY.name)
        }.apply()
        Log.i(TAG, "已一鍵切換全部到 Legacy 路線")
    }

    // ═══════════════════════════════════════════════════════════════
    // 各模塊路線（支持獨立配置）
    // ═══════════════════════════════════════════════════════════════

    /** 選股模塊路線 */
    var stockPickingRoute: AgentRoute
        get() = getRoute(KEY_STOCK_PICKING)
        set(value) = setRoute(KEY_STOCK_PICKING, value)

    /** 分析模塊路線 */
    var stockAnalysisRoute: AgentRoute
        get() = getRoute(KEY_STOCK_ANALYSIS)
        set(value) = setRoute(KEY_STOCK_ANALYSIS, value)

    /** 交易模塊路線 */
    var tradeExecutionRoute: AgentRoute
        get() = getRoute(KEY_TRADE_EXECUTION)
        set(value) = setRoute(KEY_TRADE_EXECUTION, value)

    /** 對話模塊路線 */
    var chatRoute: AgentRoute
        get() = getRoute(KEY_CHAT)
        set(value) = setRoute(KEY_CHAT, value)

    /** 新聞監控模塊路線 */
    var newsMonitoringRoute: AgentRoute
        get() = getRoute(KEY_NEWS_MONITOR)
        set(value) = setRoute(KEY_NEWS_MONITOR, value)

    /** 風控模塊路線 */
    var riskManagementRoute: AgentRoute
        get() = getRoute(KEY_RISK_MANAGEMENT)
        set(value) = setRoute(KEY_RISK_MANAGEMENT, value)

    // ═══════════════════════════════════════════════════════════════
    // 通用 DAG 開關（適用於所有週期）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 是否使用 DAG Pipeline（高通風格拓撲引擎）動態鏈接，適用於所有選股方案。
     * true  → 各週期 Fragment 走 UseCaseLoader.run("<cycle>") → DagPipeline
     *         - 超短線: ultra_short_pipeline.xml
     *         - 短線:   short_term_pipeline.xml
     *         - 中線:   mid_term_pipeline.xml
     *         - 長線:   long_term_pipeline.xml
     * false → 走各 Fragment 內 Hardcode 流程（SimulationTradeEngine / 內聯邏輯）
     *
     * 默認 false（全部走 Hardcode）。
     */
    var useDagPipeline: Boolean
        get() = prefs.getBoolean(KEY_USE_DAG_PIPELINE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_USE_DAG_PIPELINE, value).apply()
            Log.i(TAG, "通用 DAG Pipeline 開關: $value")
        }

    /** @deprecated 已遷移至 [useDagPipeline]，保留以兼容舊調用點 */
    @Deprecated("使用 useDagPipeline", ReplaceWith("useDagPipeline"))
    var useDagPipelineMidTerm: Boolean
        get() = useDagPipeline
        set(value) { useDagPipeline = value }

    // ═══════════════════════════════════════════════════════════════
    // 按週期的路線開關（Phase 8 新增）
    // ═══════════════════════════════════════════════════════════════

    private const val KEY_ROUTE_ULTRA_SHORT = "route_ultra_short"
    private const val KEY_ROUTE_SHORT       = "route_short"
    private const val KEY_ROUTE_MID         = "route_mid"
    private const val KEY_ROUTE_LONG        = "route_long"

    /** 獲取指定週期的執行路線 */
    fun getRoute(period: HoldingPeriod): AgentRoute {
        val key = when (period) {
            HoldingPeriod.ULTRA_SHORT -> KEY_ROUTE_ULTRA_SHORT
            HoldingPeriod.SHORT       -> KEY_ROUTE_SHORT
            HoldingPeriod.MID         -> KEY_ROUTE_MID
            HoldingPeriod.LONG        -> KEY_ROUTE_LONG
        }
        return resolveRoute(getRoute(key))
    }

    /** 設置指定週期的執行路線 */
    fun setRoute(period: HoldingPeriod, route: AgentRoute) {
        val key = when (period) {
            HoldingPeriod.ULTRA_SHORT -> KEY_ROUTE_ULTRA_SHORT
            HoldingPeriod.SHORT       -> KEY_ROUTE_SHORT
            HoldingPeriod.MID         -> KEY_ROUTE_MID
            HoldingPeriod.LONG        -> KEY_ROUTE_LONG
        }
        setRoute(key, route)
    }

    /** 獲取指定週期推薦的 Agent 子模式 */
    fun getAgentMode(period: HoldingPeriod): String {
        return when (period) {
            HoldingPeriod.ULTRA_SHORT -> "QUICK"
            HoldingPeriod.SHORT       -> "QUICK"
            HoldingPeriod.MID         -> "PIPELINE"
            HoldingPeriod.LONG        -> "V2"
        }
    }

    /** 判斷指定週期是否使用 Agent 路線 */
    fun isAgentRoute(period: HoldingPeriod): Boolean {
        return getRoute(period) == AgentRoute.AGENT_FRAMEWORK
    }

    /** 一鍵切換全部週期到指定路線 */
    fun setAllPeriodRoutes(route: AgentRoute) {
        prefs.edit().apply {
            putString(KEY_ROUTE_ULTRA_SHORT, route.name)
            putString(KEY_ROUTE_SHORT, route.name)
            putString(KEY_ROUTE_MID, route.name)
            putString(KEY_ROUTE_LONG, route.name)
        }.apply()
        Log.i(TAG, "已一鍵切換全部週期路線: $route")
    }

    // ═══════════════════════════════════════════════════════════════
    // 輔助方法
    // ═══════════════════════════════════════════════════════════════

    /** 獲取實際生效的路線（考慮全局模式） */
    fun resolveRoute(moduleRoute: AgentRoute): AgentRoute {
        return when (globalMode) {
            GlobalMode.LEGACY -> AgentRoute.LEGACY
            GlobalMode.AGENT -> AgentRoute.AGENT_FRAMEWORK
            GlobalMode.HYBRID -> when (moduleRoute) {
                AgentRoute.AUTO -> AgentRoute.AGENT_FRAMEWORK
                else -> moduleRoute
            }
        }
    }

    /** 判斷是否使用新 Agent 框架 */
    fun isAgentFramework(route: AgentRoute): Boolean {
        return resolveRoute(route) == AgentRoute.AGENT_FRAMEWORK
    }

    /** 獲取所有路線配置（用於調試/展示） */
    fun dumpConfig(): String = buildString {
        appendLine("=== FeatureFlagManager 配置 ===")
        appendLine("全局模式: $globalMode")
        appendLine("選股: ${stockPickingRoute} (實際: ${resolveRoute(stockPickingRoute)})")
        appendLine("分析: ${stockAnalysisRoute} (實際: ${resolveRoute(stockAnalysisRoute)})")
        appendLine("交易: ${tradeExecutionRoute} (實際: ${resolveRoute(tradeExecutionRoute)})")
        appendLine("對話: ${chatRoute} (實際: ${resolveRoute(chatRoute)})")
        appendLine("新聞: ${newsMonitoringRoute} (實際: ${resolveRoute(newsMonitoringRoute)})")
        appendLine("風控: ${riskManagementRoute} (實際: ${resolveRoute(riskManagementRoute)})")
        appendLine("── 週期路線 ──")
        appendLine("超短線: ${getRoute(HoldingPeriod.ULTRA_SHORT)} (${getAgentMode(HoldingPeriod.ULTRA_SHORT)})")
        appendLine("短線: ${getRoute(HoldingPeriod.SHORT)} (${getAgentMode(HoldingPeriod.SHORT)})")
        appendLine("中線: ${getRoute(HoldingPeriod.MID)} (${getAgentMode(HoldingPeriod.MID)})")
        appendLine("長線: ${getRoute(HoldingPeriod.LONG)} (${getAgentMode(HoldingPeriod.LONG)})")
        appendLine("── 通用開關 ──")
        appendLine("DAG Pipeline (所有週期): $useDagPipeline")
    }

    private fun getRoute(key: String): AgentRoute {
        val name = prefs.getString(key, AgentRoute.LEGACY.name)
        return try {
            AgentRoute.valueOf(name ?: AgentRoute.LEGACY.name)
        } catch (_: IllegalArgumentException) {
            AgentRoute.LEGACY
        }
    }

    private fun setRoute(key: String, route: AgentRoute) {
        prefs.edit().putString(key, route.name).apply()
        Log.i(TAG, "模塊路線切換: $key → $route")
    }
}
