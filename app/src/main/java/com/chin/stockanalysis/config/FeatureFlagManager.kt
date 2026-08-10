package com.chin.stockanalysis.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.chin.stockanalysis.strategy.HoldingPeriod

/**
 * ## 全局模式
 * - LEGACY: 全部使用 Legacy 系统
 * - AGENT: 全部使用 Agent 框架
 * - HYBRID: 按模块混合配置
 */
enum class GlobalMode {
    LEGACY, AGENT, HYBRID
}

/**
 * ## 路由选择
 * - LEGACY: 旧系统
 * - AGENT_FRAMEWORK: 新 Agent 框架
 * - AUTO: 自动 fallback
 */
enum class AgentRoute {
    LEGACY,
    AGENT_FRAMEWORK,
    AUTO
}

object FeatureFlagManager {

    private const val TAG = "FeatureFlagManager"
    private const val PREFS_NAME = "feature_flags"

    // ── 各模块路线开关（key）
    private const val KEY_GLOBAL_MODE       = "global_mode_v2"
    private const val KEY_STOCK_PICKING     = "route_stock_picking"
    private const val KEY_STOCK_ANALYSIS    = "route_stock_analysis"
    private const val KEY_TRADE_EXECUTION   = "route_trade_execution"
    private const val KEY_CHAT              = "route_chat"
    private const val KEY_NEWS_MONITOR      = "route_news_monitor"
    private const val KEY_RISK_MANAGEMENT   = "route_risk_management"

    // ── 通用 DAG 开关（适用于所有周期） ──
    private const val KEY_USE_DAG_PIPELINE   = "use_dag_pipeline"

    private lateinit var prefs: SharedPreferences

    /** 初始化（在 Application.onCreate 或 MainActivity.onCreate 中调用） */
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
            Log.i(TAG, "全局模式切换: $value")
        }

    /** 是否为「混合」模式（此时模块级开关才可操作） */
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

    /** 一键切换全部模块到 Agent */
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
        Log.i(TAG, "已一键启用全部 Agent 路线")
    }

    /** 一键切换全部模块到 Legacy */
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
        Log.i(TAG, "已一键切换全部到 Legacy 路线")
    }

    // ═══════════════════════════════════════════════════════════════
    // 各模块路线（支持独立配置）
    // ═══════════════════════════════════════════════════════════════

    /** 选股模块路线 */
    var stockPickingRoute: AgentRoute
        get() = getRoute(KEY_STOCK_PICKING)
        set(value) = setRoute(KEY_STOCK_PICKING, value)

    /** 分析模块路线 */
    var stockAnalysisRoute: AgentRoute
        get() = getRoute(KEY_STOCK_ANALYSIS)
        set(value) = setRoute(KEY_STOCK_ANALYSIS, value)

    /** 交易模块路线 */
    var tradeExecutionRoute: AgentRoute
        get() = getRoute(KEY_TRADE_EXECUTION)
        set(value) = setRoute(KEY_TRADE_EXECUTION, value)

    /** 对话模块路线 */
    var chatRoute: AgentRoute
        get() = getRoute(KEY_CHAT)
        set(value) = setRoute(KEY_CHAT, value)

    /** 新闻监控模块路线 */
    var newsMonitoringRoute: AgentRoute
        get() = getRoute(KEY_NEWS_MONITOR)
        set(value) = setRoute(KEY_NEWS_MONITOR, value)

    /** 风控模块路线 */
    var riskManagementRoute: AgentRoute
        get() = getRoute(KEY_RISK_MANAGEMENT)
        set(value) = setRoute(KEY_RISK_MANAGEMENT, value)

    // ═══════════════════════════════════════════════════════════════
    // 通用 DAG 开关（适用于所有周期）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 是否使用 DAG Pipeline（高通风格拓扑引擎）动态链接，适用于所有选股方案。
     * true  → 各周期 Fragment 走 UseCaseLoader.run("<cycle>") → DagPipeline
     *         - 超短线: ultra_short_pipeline.xml
     *         - 短线:   short_term_pipeline.xml
     *         - 中线:   mid_term_pipeline.xml
     *         - 长线:   long_term_pipeline.xml
     * false → 走各 Fragment 内 Hardcode 流程（SimulationTradeEngine / 内联逻辑）
     *
     * 默认 true（全部走 DAG Pipeline）。
     */
    var useDagPipeline: Boolean
        get() = prefs.getBoolean(KEY_USE_DAG_PIPELINE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_USE_DAG_PIPELINE, value).apply()
            Log.i(TAG, "通用 DAG Pipeline 开关: $value")
        }

    // ═══════════════════════════════════════════════════════════════
    // 按周期的路线开关（Phase 8 新增）
    // ═══════════════════════════════════════════════════════════════

    private const val KEY_ROUTE_ULTRA_SHORT = "route_ultra_short"
    private const val KEY_ROUTE_SHORT       = "route_short"
    private const val KEY_ROUTE_MID         = "route_mid"
    private const val KEY_ROUTE_LONG        = "route_long"

    /** 获取指定周期的执行路线 */
    fun getRoute(period: HoldingPeriod): AgentRoute {
        val key = when (period) {
            HoldingPeriod.ULTRA_SHORT -> KEY_ROUTE_ULTRA_SHORT
            HoldingPeriod.SHORT       -> KEY_ROUTE_SHORT
            HoldingPeriod.MID         -> KEY_ROUTE_MID
            HoldingPeriod.LONG        -> KEY_ROUTE_LONG
        }
        return resolveRoute(getRoute(key))
    }

    /** 设置指定周期的执行路线 */
    fun setRoute(period: HoldingPeriod, route: AgentRoute) {
        val key = when (period) {
            HoldingPeriod.ULTRA_SHORT -> KEY_ROUTE_ULTRA_SHORT
            HoldingPeriod.SHORT       -> KEY_ROUTE_SHORT
            HoldingPeriod.MID         -> KEY_ROUTE_MID
            HoldingPeriod.LONG        -> KEY_ROUTE_LONG
        }
        setRoute(key, route)
    }

    /** 获取指定周期推荐的 Agent 子模式 */
    fun getAgentMode(period: HoldingPeriod): String {
        return when (period) {
            HoldingPeriod.ULTRA_SHORT -> "QUICK"
            HoldingPeriod.SHORT       -> "QUICK"
            HoldingPeriod.MID         -> "PIPELINE"
            HoldingPeriod.LONG        -> "V2"
        }
    }

    /** 判断指定周期是否使用 Agent 路线 */
    fun isAgentRoute(period: HoldingPeriod): Boolean {
        return getRoute(period) == AgentRoute.AGENT_FRAMEWORK
    }

    /** 一键切换全部周期到指定路线 */
    fun setAllPeriodRoutes(route: AgentRoute) {
        prefs.edit().apply {
            putString(KEY_ROUTE_ULTRA_SHORT, route.name)
            putString(KEY_ROUTE_SHORT, route.name)
            putString(KEY_ROUTE_MID, route.name)
            putString(KEY_ROUTE_LONG, route.name)
        }.apply()
        Log.i(TAG, "已一键切换全部周期路线: $route")
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════════════════

    /** 获取实际生效的路线（考虑全局模式） */
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

    /** 判断是否使用新 Agent 框架 */
    fun isAgentFramework(route: AgentRoute): Boolean {
        return resolveRoute(route) == AgentRoute.AGENT_FRAMEWORK
    }

    /** 获取所有路线配置（用于调试/展示） */
    fun dumpConfig(): String = buildString {
        appendLine("=== FeatureFlagManager 配置 ===")
        appendLine("全局模式: $globalMode")
        appendLine("选股: ${stockPickingRoute} (实际: ${resolveRoute(stockPickingRoute)})")
        appendLine("分析: ${stockAnalysisRoute} (实际: ${resolveRoute(stockAnalysisRoute)})")
        appendLine("交易: ${tradeExecutionRoute} (实际: ${resolveRoute(tradeExecutionRoute)})")
        appendLine("对话: ${chatRoute} (实际: ${resolveRoute(chatRoute)})")
        appendLine("新闻: ${newsMonitoringRoute} (实际: ${resolveRoute(newsMonitoringRoute)})")
        appendLine("风控: ${riskManagementRoute} (实际: ${resolveRoute(riskManagementRoute)})")
        appendLine("── 周期路线 ──")
        appendLine("超短线: ${getRoute(HoldingPeriod.ULTRA_SHORT)} (${getAgentMode(HoldingPeriod.ULTRA_SHORT)})")
        appendLine("短线: ${getRoute(HoldingPeriod.SHORT)} (${getAgentMode(HoldingPeriod.SHORT)})")
        appendLine("中线: ${getRoute(HoldingPeriod.MID)} (${getAgentMode(HoldingPeriod.MID)})")
        appendLine("长线: ${getRoute(HoldingPeriod.LONG)} (${getAgentMode(HoldingPeriod.LONG)})")
        appendLine("── 通用开关 ──")
        appendLine("DAG Pipeline (所有周期): $useDagPipeline")
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
        Log.i(TAG, "模块路线切换: $key → $route")
    }
}
