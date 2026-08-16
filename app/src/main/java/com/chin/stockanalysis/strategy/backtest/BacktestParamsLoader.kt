package com.chin.stockanalysis.strategy.backtest

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.strategy.topology.pipelines.StockCheckPipeline
import org.json.JSONObject

/**
 * 固化参数加载器（smalltools 三年 walk-forward 拟合 → assets/backtest_params.json）
 *
 * 设计目标（用户需求 3）：
 * - 新用户无需导入多年历史 K 线：安装即带拟合好的选股参数 + 卖出参数矩阵，
 *   直接用于选股 / 买卖评估；使用时再逐步积累 K 线。
 * - 支持导入他人导出的参数文件：后续可扩展从文件覆盖 assets 默认值。
 *
 * 优先级：用户本机「工作台回溯+拟合」落库的矩阵 > 固化参数 > 代码默认参数。
 */
object BacktestParamsLoader {

    private const val TAG = "BacktestParamsLoader"
    private const val FILE = "backtest_params.json"

    @Volatile
    private var root: JSONObject? = null

    /** 幂等加载；失败时静默降级（后续使用代码默认参数） */
    fun load(context: Context) {
        if (root != null) return
        synchronized(this) {
            if (root != null) return
            root = try {
                JSONObject(context.assets.open(FILE).bufferedReader().use { it.readText() })
            } catch (e: Exception) {
                Log.w(TAG, "加载固化参数失败，使用代码默认参数: ${e.message}")
                null
            }
        }
    }

    // ───────────────────────── 选股参数覆盖 ─────────────────────────

    /**
     * 用 assets 固化参数覆盖默认模板（period: 超短/短线/中线/长线）。
     * 未加载或 JSON 无该周期时原样返回 base，保证安全降级。
     */
    fun applySelectOverrides(
        period: String,
        base: StockCheckPipeline,
        marketTrend: String?
    ): StockCheckPipeline {
        val o = root?.optJSONObject("select_params")?.optJSONObject(period) ?: return base
        return StockCheckPipeline(
            convergenceThreshold = o.optDouble("convergenceThreshold", base.convergenceThreshold),
            useMA60 = o.optBoolean("useMA60", base.useMA60),
            convergenceDurationDays = o.optInt("convergenceDurationDays", base.convergenceDurationDays),
            convergenceDurationRatio = o.optDouble("convergenceDurationRatio", base.convergenceDurationRatio),
            volumeBreakoutRatio = o.optDouble("volumeBreakoutRatio", base.volumeBreakoutRatio),
            minChangePct = o.optDouble("minChangePct", base.minChangePct),
            requireChangePct = o.optBoolean("requireChangePct", base.requireChangePct),
            minDrawdownPct = o.optDouble("minDrawdownPct", base.minDrawdownPct),
            requireMA60Rising = o.optBoolean("requireMA60Rising", base.requireMA60Rising),
            maRisingDays = o.optInt("maRisingDays", base.maRisingDays),
            requireMA250Rising = o.optBoolean("requireMA250Rising", base.requireMA250Rising),
            useMA250InBullish = o.optBoolean("useMA250InBullish", base.useMA250InBullish),
            requireCloseAboveConvergenceTop = o.optBoolean("requireCloseAboveConvergenceTop", base.requireCloseAboveConvergenceTop),
            requireOpenBelowMAs = o.optBoolean("requireOpenBelowMAs", base.requireOpenBelowMAs),
            requireVolumeShrink = o.optBoolean("requireVolumeShrink", base.requireVolumeShrink),
            volumeShrinkRatio = o.optDouble("volumeShrinkRatio", base.volumeShrinkRatio),
            requireAboveYearLine = o.optBoolean("requireAboveYearLine", base.requireAboveYearLine),
            requireAboveAllMAs = o.optBoolean("requireAboveAllMAs", base.requireAboveAllMAs),
            moderateVolumeLower = o.optDouble("moderateVolumeLower", base.moderateVolumeLower),
            moderateVolumeUpper = o.optDouble("moderateVolumeUpper", base.moderateVolumeUpper),
            lookbackDays = o.optInt("lookbackDays", base.lookbackDays),
            minPassCount = o.optInt("minPassCount", base.minPassCount),
            requireThreeDayConfirm = o.optBoolean("requireThreeDayConfirm", base.requireThreeDayConfirm),
            swingLeftN = base.swingLeftN,
            swingRightN = base.swingRightN,
            marketTrend = marketTrend,
            mode = base.mode
        )
    }

    // ───────────────────────── 卖出参数（状态矩阵） ─────────────────────────

    /**
     * 固化卖出参数：某周期某大盘状态的规则；该状态无拟合样本时回退 default。
     * 返回 null 表示 JSON 不可用（调用方回退到代码默认）。
     */
    fun sellRule(
        period: String,
        state: FullCycleBacktestEngine.MarketState
    ): FullCycleBacktestEngine.FittedParams? {
        val periodObj = root?.optJSONObject("sell_rules")?.optJSONObject(period) ?: return null
        val byState = periodObj.optJSONObject("by_state") ?: return null
        byState.optJSONObject(state.name)?.let {
            ruleToFitted(period, state, it)?.let { p -> return p }
        }
        return periodObj.optJSONObject("default")?.let { ruleToFitted(period, state, it) }
    }

    private fun ruleToFitted(
        period: String,
        state: FullCycleBacktestEngine.MarketState,
        rule: JSONObject
    ): FullCycleBacktestEngine.FittedParams? {
        val style = rule.optString("style", "hold")
        // 超短/短线由 AutoSellEngine 的固定卖出风格执行，这里给出等价的持有天数/保护
        val maxHold = rule.optInt("maxHold", 1).coerceAtLeast(1)
        return FullCycleBacktestEngine.FittedParams(
            period = period,
            marketState = state,
            maxHoldDays = maxHold,
            takeProfitPct = rule.optDouble("tp", 0.0),
            stopLossPct = rule.optDouble("sl", 0.0),
            avgRet = 0.0,
            winRate = 0.0,
            sampleCount = 0
        )
    }
}
