package com.chin.stockanalysis.strategy.backtest

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.strategy.data.IndustrySeasonalityCalendar
import com.chin.stockanalysis.strategy.topology.pipelines.StockCheckPipeline
import org.json.JSONObject
import java.io.File

/**
 * 固化参数加载器（smalltools 三年 walk-forward 拟合 → assets/backtest_params.json）
 *
 * 设计目标（用户需求 3）：
 * - 新用户无需导入多年历史 K 线：安装即带拟合好的选股参数 + 卖出参数矩阵，
 *   直接用于选股 / 买卖评估；使用时再逐步积累 K 线。
 * - 支持从文件导入他人导出的参数 JSON（filesDir/imported_backtest_params.json 持久化），
 *   也支持把当前参数导出为 JSON 文件分享。
 *
 * 加载优先级：
 *   用户导入的参数文件 > APK 内置固化参数 > 代码默认参数。
 * 运行时优先级：
 *   用户本机「工作台回溯+拟合」落库的矩阵 > 上述固化参数 > 代码默认参数。
 */
object BacktestParamsLoader {

    private const val TAG = "BacktestParamsLoader"
    private const val FILE = "backtest_params.json"
    private const val IMPORT_FILE = "imported_backtest_params.json"

    @Volatile
    private var root: JSONObject? = null

    /** 当前参数来源：builtin=APK内置 / imported=用户导入 */
    @Volatile
    private var sourceName: String = "builtin"

    /** 幂等加载；失败时静默降级（后续使用代码默认参数）。导入文件优先。 */
    fun load(context: Context) {
        if (root != null) return
        synchronized(this) {
            if (root != null) return
            val imported = File(context.filesDir, IMPORT_FILE)
            root = try {
                if (imported.exists()) {
                    sourceName = "imported"
                    JSONObject(imported.readText())
                } else {
                    sourceName = "builtin"
                    JSONObject(context.assets.open(FILE).bufferedReader().use { it.readText() })
                }
            } catch (e: Exception) {
                Log.w(TAG, "加载固化参数失败，使用代码默认参数: ${e.message}")
                null
            }
            if (root != null) {
                val n = try { root!!.optJSONObject("sell_rules")?.length() ?: 0 } catch (e: Exception) { 0 }
                Log.i(TAG, "✅ 已加载 PC 拟合参数: 来源=$sourceName 文件=$FILE 周期数=$n")
            }
        }
    }

    /**
     * 从文件导入参数 JSON：校验结构 → 写入 filesDir 持久化 → 立即生效。
     * 成功返回 null；失败返回错误信息（供 UI Toast 展示）。
     */
    fun importParams(context: Context, jsonText: String): String? {
        val parsed = try {
            JSONObject(jsonText)
        } catch (e: Exception) {
            return "JSON 解析失败: ${e.message}"
        }
        if (!parsed.has("sell_rules") && !parsed.has("select_params")) {
            return "不是有效的参数文件（缺少 sell_rules/select_params 字段）"
        }
        synchronized(this) {
            File(context.filesDir, IMPORT_FILE).writeText(jsonText)
            root = parsed
            sourceName = "imported"
        }
        Log.i(TAG, "已导入参数文件，立即生效")
        return null
    }

    /** 导出当前生效参数为 JSON 文本（用于分享/备份）；无参数时返回 null */
    fun exportParams(context: Context): String? {
        load(context)
        return try {
            root?.toString(2)
        } catch (e: Exception) {
            Log.w(TAG, "导出参数序列化失败: ${e.message}")
            null
        }
    }

    /** 删除导入文件，恢复 APK 内置参数；返回是否成功 */
    fun resetToBuiltIn(context: Context): Boolean {
        synchronized(this) {
            File(context.filesDir, IMPORT_FILE).delete()
            sourceName = "builtin"
            root = try {
                JSONObject(context.assets.open(FILE).bufferedReader().use { it.readText() })
            } catch (e: Exception) {
                Log.w(TAG, "恢复内置参数失败: ${e.message}")
                null
            }
            return root != null
        }
    }

    /** 当前参数来源描述（UI 展示用） */
    fun sourceLabel(context: Context): String {
        load(context)
        return if (sourceName == "imported") "用户导入文件" else "APK 内置（三年 walk-forward）"
    }

    /** 内置参数 JSON 的 version 字段（UI 展示用）；无则返回 "-" */
    fun version(context: Context): String {
        load(context)
        return root?.optString("version", "-") ?: "-"
    }

    // ───────────────────────── 选股参数覆盖 ─────────────────────────

    /**
     * 用固化参数覆盖默认模板（period: 超短/短线/中线/长线）。
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

    // ───────────────────────── IC 排序权重（rank_factors） ─────────────────────────

    /**
     * IC 排序权重：某周期的 因子名→ICIR 映射（带符号）。
     * 来自 backtest_params.json 的 rank_factors.<周期> 区块（smalltools/_factor_ic.py 全量 IC 检验）。
     * 负权重 = 因子值越小越优先（如中线距MA250乖离、长线近5日动量，都是负相关）。
     * 未配置或加载失败返回空 Map → 调用方回退原 passCount 排序。
     */
    fun rankFactors(context: Context, period: String): Map<String, Double> {
        load(context)
        val pf = root?.optJSONObject("rank_factors") ?: return emptyMap()
        // 兼容双键：代码用 ultra_short/short/mid/long，JSON 固化用 超短/短线/中线/长线
        var obj = pf.optJSONObject(period)
        if (obj == null) obj = pf.optJSONObject(periodCn(period))
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, Double>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.optDouble(k, 0.0)
            if (v != 0.0) out[k] = v
        }
        return out
    }

    /** 周期键 → JSON 中文键（与 select_params/sell_rules 保持一致） */
    private fun periodCn(period: String): String = when (period) {
        "ultra_short" -> "超短"
        "short" -> "短线"
        "mid" -> "中线"
        "long" -> "长线"
        else -> period
    }

    // ───────────────────────── 行业季节/周期日历（seasonality） ─────────────────────────

    /**
     * 商品锚定方向表：品种("油价"/"锂价"/"铜价"/"金价") → "up"/"down"。
     * 来自 backtest_params.json 的 seasonality.anchors（可被 AI/人工每日更新）。
     * 未配置返回空表（所有锚定主题不生效，仅日历季节生效）。
     */
    fun seasonalityAnchors(context: Context): Map<String, String> {
        load(context)
        val obj = root?.optJSONObject("seasonality")?.optJSONObject("anchors") ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.optString(k)
            if (v == IndustrySeasonalityCalendar.ANCHOR_UP || v == IndustrySeasonalityCalendar.ANCHOR_DOWN) {
                out[k] = v
            }
        }
        return out
    }

    /** 季节日历开关（默认开） */
    fun seasonalityEnabled(context: Context): Boolean {
        load(context)
        return root?.optJSONObject("seasonality")?.optBoolean("enabled", true) ?: true
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

    // ───────────────────────── 做T信号参数（t_trade） ─────────────────────────

    /**
     * 做T/反T 信号阈值参数（来自 backtest_params.json 的 t_trade 区块，
     * 由 smalltools/_ttrade_walk_forward.py 做T walk-forward 拟合；未配置时用代码默认值）。
     */
    fun tTradeParams(context: Context): TTradeParams {
        load(context)
        val o = root?.optJSONObject("t_trade") ?: return TTradeParams.DEFAULT
        fun d(name: String, def: Double) = o.optDouble(name, def)
        fun i(name: String, def: Int) = o.optInt(name, def)
        return TTradeParams(
            // 支撑/阻力位系数
            supportMa5Factor = d("supportMa5Factor", TTradeParams.DEFAULT.supportMa5Factor),
            supportMa10Factor = d("supportMa10Factor", TTradeParams.DEFAULT.supportMa10Factor),
            resistanceMa5Factor = d("resistanceMa5Factor", TTradeParams.DEFAULT.resistanceMa5Factor),
            resistanceMa10Factor = d("resistanceMa10Factor", TTradeParams.DEFAULT.resistanceMa10Factor),
            // 触发阈值
            nearSupportThreshold = d("nearSupportThreshold", TTradeParams.DEFAULT.nearSupportThreshold),
            nearResistanceThreshold = d("nearResistanceThreshold", TTradeParams.DEFAULT.nearResistanceThreshold),
            minExpectedProfitPct = d("minExpectedProfitPct", TTradeParams.DEFAULT.minExpectedProfitPct),
            tQtyRatio = d("tQtyRatio", TTradeParams.DEFAULT.tQtyRatio),
            // 配对目标幅度（%）
            pairProfitPct = d("pairProfitPct", TTradeParams.DEFAULT.pairProfitPct),
            // 做T买入置信度
            baseConfidence = i("baseConfidence", TTradeParams.DEFAULT.baseConfidence),
            rsiOversold = d("rsiOversold", TTradeParams.DEFAULT.rsiOversold),
            rsiLow = d("rsiLow", TTradeParams.DEFAULT.rsiLow),
            rsiOverbought = d("rsiOverbought", TTradeParams.DEFAULT.rsiOverbought),
            confRsiOversold = i("confRsiOversold", TTradeParams.DEFAULT.confRsiOversold),
            confRsiLow = i("confRsiLow", TTradeParams.DEFAULT.confRsiLow),
            confRsiOverbought = i("confRsiOverbought", TTradeParams.DEFAULT.confRsiOverbought),
            volumeShrink = d("volumeShrink", TTradeParams.DEFAULT.volumeShrink),
            volumeSurge = d("volumeSurge", TTradeParams.DEFAULT.volumeSurge),
            confVolumeShrink = i("confVolumeShrink", TTradeParams.DEFAULT.confVolumeShrink),
            confVolumeSurge = i("confVolumeSurge", TTradeParams.DEFAULT.confVolumeSurge),
            confPatternBullish = i("confPatternBullish", TTradeParams.DEFAULT.confPatternBullish),
            confPatternBearish = i("confPatternBearish", TTradeParams.DEFAULT.confPatternBearish),
            confTrendUp = i("confTrendUp", TTradeParams.DEFAULT.confTrendUp),
            confTrendDown = i("confTrendDown", TTradeParams.DEFAULT.confTrendDown),
            // 反T卖出置信度
            rtRsiOverbought = d("rt_rsiOverbought", TTradeParams.DEFAULT.rtRsiOverbought),
            rtRsiHigh = d("rt_rsiHigh", TTradeParams.DEFAULT.rtRsiHigh),
            rtRsiOversold = d("rt_rsiOversold", TTradeParams.DEFAULT.rtRsiOversold),
            rtConfRsiOverbought = i("rt_confRsiOverbought", TTradeParams.DEFAULT.rtConfRsiOverbought),
            rtConfRsiHigh = i("rt_confRsiHigh", TTradeParams.DEFAULT.rtConfRsiHigh),
            rtConfRsiOversold = i("rt_confRsiOversold", TTradeParams.DEFAULT.rtConfRsiOversold),
            rtVolumeRise = d("rt_volumeRise", TTradeParams.DEFAULT.rtVolumeRise),
            rtVolumeDry = d("rt_volumeDry", TTradeParams.DEFAULT.rtVolumeDry),
            rtConfVolumeRise = i("rt_confVolumeRise", TTradeParams.DEFAULT.rtConfVolumeRise),
            rtConfVolumeDry = i("rt_confVolumeDry", TTradeParams.DEFAULT.rtConfVolumeDry),
            rtConfPatternBearish = i("rt_confPatternBearish", TTradeParams.DEFAULT.rtConfPatternBearish),
            rtConfPatternBullish = i("rt_confPatternBullish", TTradeParams.DEFAULT.rtConfPatternBullish),
            rtConfTrendDown = i("rt_confTrendDown", TTradeParams.DEFAULT.rtConfTrendDown),
            rtConfTrendUp = i("rt_confTrendUp", TTradeParams.DEFAULT.rtConfTrendUp)
        )
    }
}

/** 做T/反T 信号阈值参数（默认值 = 2026-08 TTradeEngine 固定规则） */
data class TTradeParams(
    // 支撑/阻力位系数
    val supportMa5Factor: Double = 0.98,
    val supportMa10Factor: Double = 0.97,
    val resistanceMa5Factor: Double = 1.02,
    val resistanceMa10Factor: Double = 1.03,
    // 触发阈值
    val nearSupportThreshold: Double = 0.02,
    val nearResistanceThreshold: Double = 0.02,
    val minExpectedProfitPct: Double = 0.5,
    val tQtyRatio: Double = 0.4,
    // 配对目标幅度（%）：做T 1+pairProfitPct/100，反T 1-pairProfitPct/100
    val pairProfitPct: Double = 0.5,
    // 做T买入置信度
    val baseConfidence: Int = 50,
    val rsiOversold: Double = 30.0,
    val rsiLow: Double = 40.0,
    val rsiOverbought: Double = 70.0,
    val confRsiOversold: Int = 20,
    val confRsiLow: Int = 10,
    val confRsiOverbought: Int = -20,
    val volumeShrink: Double = 0.7,
    val volumeSurge: Double = 2.0,
    val confVolumeShrink: Int = 10,
    val confVolumeSurge: Int = -10,
    val confPatternBullish: Int = 15,
    val confPatternBearish: Int = -15,
    val confTrendUp: Int = 10,
    val confTrendDown: Int = -15,
    // 反T卖出置信度
    val rtRsiOverbought: Double = 70.0,
    val rtRsiHigh: Double = 60.0,
    val rtRsiOversold: Double = 30.0,
    val rtConfRsiOverbought: Int = 20,
    val rtConfRsiHigh: Int = 10,
    val rtConfRsiOversold: Int = -20,
    val rtVolumeRise: Double = 1.5,
    val rtVolumeDry: Double = 0.5,
    val rtConfVolumeRise: Int = 10,
    val rtConfVolumeDry: Int = -10,
    val rtConfPatternBearish: Int = 15,
    val rtConfPatternBullish: Int = -15,
    val rtConfTrendDown: Int = 10,
    val rtConfTrendUp: Int = -15
) {
    companion object {
        val DEFAULT = TTradeParams()
    }
}
