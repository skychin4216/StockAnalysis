package com.chin.stockanalysis.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * ## Feature Flag 管理器
 *
 * 控制新舊 Agent 路線的切換開關。
 * 支持三種模式：
 * - LEGACY: 使用舊系統（Pipeline / Analyzer / SimTradeEngine）
 * - AGENT_FRAMEWORK: 使用新 Agent 框架
 * - AUTO: 自動 fallback，舊系統失敗時自動切換到新 Agent
 */
enum class AgentRoute {
    LEGACY,           // 舊系統
    AGENT_FRAMEWORK,  // 新 Agent 框架
    AUTO              // 自動 fallback
}

object FeatureFlagManager {

    private const val TAG = "FeatureFlagManager"
    private const val PREFS_NAME = "feature_flags"

    // ── 各模塊路線開關（key）
    private const val KEY_GLOBAL_MODE       = "global_mode"
    private const val KEY_STOCK_PICKING     = "route_stock_picking"
    private const val KEY_STOCK_ANALYSIS    = "route_stock_analysis"
    private const val KEY_TRADE_EXECUTION   = "route_trade_execution"
    private const val KEY_CHAT              = "route_chat"
    private const val KEY_NEWS_MONITOR      = "route_news_monitor"
    private const val KEY_RISK_MANAGEMENT   = "route_risk_management"

    private lateinit var prefs: SharedPreferences

    /** 初始化（在 Application.onCreate 或 MainActivity.onCreate 中調用） */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        Log.i(TAG, "FeatureFlagManager 初始化完成")
    }

    // ═══════════════════════════════════════════════════════════════
    // 全局模式
    // ═══════════════════════════════════════════════════════════════

    /** 全局模式：true = 全部默認使用新 Agent（除非模塊單獨設為 LEGACY） */
    var useAgentFramework: Boolean
        get() = prefs.getBoolean(KEY_GLOBAL_MODE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_GLOBAL_MODE, value).apply()
            Log.i(TAG, "全局模式切換: ${if (value) "新 Agent" else "舊系統"}")
        }

    /** 一鍵切換全部模塊到新 Agent */
    fun enableAllAgentFramework() {
        prefs.edit().apply {
            putBoolean(KEY_GLOBAL_MODE, true)
            putString(KEY_STOCK_PICKING, AgentRoute.AGENT_FRAMEWORK.name)
            putString(KEY_STOCK_ANALYSIS, AgentRoute.AGENT_FRAMEWORK.name)
            putString(KEY_TRADE_EXECUTION, AgentRoute.AGENT_FRAMEWORK.name)
            putString(KEY_CHAT, AgentRoute.AGENT_FRAMEWORK.name)
            putString(KEY_NEWS_MONITOR, AgentRoute.AGENT_FRAMEWORK.name)
            putString(KEY_RISK_MANAGEMENT, AgentRoute.AGENT_FRAMEWORK.name)
        }.apply()
        Log.i(TAG, "已一鍵啟用全部新 Agent 路線")
    }

    /** 一鍵切換全部模塊到舊系統 */
    fun disableAllAgentFramework() {
        prefs.edit().apply {
            putBoolean(KEY_GLOBAL_MODE, false)
            putString(KEY_STOCK_PICKING, AgentRoute.LEGACY.name)
            putString(KEY_STOCK_ANALYSIS, AgentRoute.LEGACY.name)
            putString(KEY_TRADE_EXECUTION, AgentRoute.LEGACY.name)
            putString(KEY_CHAT, AgentRoute.LEGACY.name)
            putString(KEY_NEWS_MONITOR, AgentRoute.LEGACY.name)
            putString(KEY_RISK_MANAGEMENT, AgentRoute.LEGACY.name)
        }.apply()
        Log.i(TAG, "已一鍵切換全部到舊系統路線")
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
    // 輔助方法
    // ═══════════════════════════════════════════════════════════════

    /** 獲取實際生效的路線（考慮全局模式） */
    fun resolveRoute(moduleRoute: AgentRoute): AgentRoute {
        return when {
            !useAgentFramework && moduleRoute == AgentRoute.AGENT_FRAMEWORK -> AgentRoute.LEGACY
            useAgentFramework && moduleRoute == AgentRoute.LEGACY -> AgentRoute.AGENT_FRAMEWORK
            else -> moduleRoute
        }
    }

    /** 判斷是否使用新 Agent 框架 */
    fun isAgentFramework(route: AgentRoute): Boolean {
        return resolveRoute(route) == AgentRoute.AGENT_FRAMEWORK
    }

    /** 獲取所有路線配置（用於調試/展示） */
    fun dumpConfig(): String = buildString {
        appendLine("=== FeatureFlagManager 配置 ===")
        appendLine("全局模式: ${if (useAgentFramework) "新 Agent" else "舊系統"}")
        appendLine("選股: ${stockPickingRoute} (實際: ${resolveRoute(stockPickingRoute)})")
        appendLine("分析: ${stockAnalysisRoute} (實際: ${resolveRoute(stockAnalysisRoute)})")
        appendLine("交易: ${tradeExecutionRoute} (實際: ${resolveRoute(tradeExecutionRoute)})")
        appendLine("對話: ${chatRoute} (實際: ${resolveRoute(chatRoute)})")
        appendLine("新聞: ${newsMonitoringRoute} (實際: ${resolveRoute(newsMonitoringRoute)})")
        appendLine("風控: ${riskManagementRoute} (實際: ${resolveRoute(riskManagementRoute)})")
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
