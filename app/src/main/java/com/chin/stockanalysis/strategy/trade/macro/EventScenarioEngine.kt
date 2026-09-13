package com.chin.stockanalysis.strategy.trade.macro

import android.content.Context
import android.util.Log

/**
 * ## 宏观事件场景引擎（EventScenarioEngine）
 *
 * 将「大环境」的宏观事件转化为**可量化的板块偏好矩阵**，供选股/交易引擎动态调整仓位。
 *
 * ### 解决的用户诉求
 * 1. **油价 → 化工联动**：油价涨不止利好石油，更会传导到**化工**（成本端油价→产品端化工品价格联动）。
 *    本引擎动态判断油价趋势并据此给化工板块加权。
 * 2. **战争前/中/后期**：不同阶段利好利空板块完全不同——「避险」「军工/军贸」「能源」等会切换。
 * 3. **美国 CPI / 加息**：高通胀+鹰派加息 → 压制成长（科技/半导体估值），利好抗通胀（有色/资源）。
 * 4. **美股 / 韩股**：隔夜美股科技大跌或韩股（半导体产业链）走弱 → 次日 A 股对应板块承压。
 *
 * ### 板块偏好矩阵
 * 输出每个「产业主题」的净偏好分 `biasScore ∈ [-100, +100]`：
 * - 正值 → 该主题当前受宏观顺风（重点配置）
 * - 负值 → 受宏观逆风（规避/减仓）
 *
 * 主题 key 与 [IndustryThemeClassifier] 保持一致，可直接叠加过滤。
 *
 * ### 事件状态机
 * 通过人工/新闻输入维护一个「事件状态」集合（战争阶段、通胀/加息阶段等），
 * 每个状态对主题施加固定偏移 + 动态联动（油价）修正。
 */
object EventScenarioEngine {

    private const val TAG = "EventScenarioEngine"

    // ═══════════════════════════════════════════
    // 事件状态（可持久化，供 Agent/新闻写入）
    // ═══════════════════════════════════════════

    /** 战争阶段 */
    enum class WarPhase(val label: String) {
        NONE("无战争"),
        PRE("战前/紧张升级"),
        OUTBREAK("爆发初期"),
        PERSIST("僵持/相持"),
        POST("战后/停火"),
        ESCALATION("升级/扩大化")
    }

    /** 通胀/货币政策阶段 */
    enum class PolicyPhase(val label: String) {
        EASING("宽松/降息预期"),
        HOLD("观望/按兵不动"),
        HAWKISH("鹰派/加息压力")
    }

    /** 当前宏观事件状态（由 Agent 根据新闻写入，或自动从行情推导） */
    data class EventState(
        val warPhase: WarPhase = WarPhase.NONE,
        val policyPhase: PolicyPhase = PolicyPhase.HOLD,
        val lastUpdated: String = ""
    )

    /**
     * 板块偏好矩阵：产业主题 → 净偏好分
     * 与 [IndustryThemeClassifier.THEME_*] 的 key 对齐。
     */
    data class SectorBias(
        val theme: String,
        val biasScore: Int,            // -100..+100
        val reasons: List<String>      // 施加该偏好分的原因
    )

    /**
     * 事件场景分析结果
     */
    data class ScenarioResult(
        val eventState: EventState,
        val biases: Map<String, SectorBias>,     // theme → 偏好
        val oilTrend: OilTrend,                  // 油价趋势（联动化工）
        val globalSignals: GlobalSignals,        // 美股/韩股信号
        val summary: String
    )

    /** 油价趋势 */
    enum class OilTrend(val label: String) {
        RISING("油价上行"),
        FALLING("油价下行"),
        STABLE("油价震荡")
    }

    /** 全球市场信号 */
    data class GlobalSignals(
        val usTechChange: Double,      // 美股科技（纳斯达克）涨跌 %
        val krSemiconChange: Double,   // 韩股 KOSPI 涨跌 %
        val usTechWeak: Boolean,       // 美股科技是否明显走弱
        val krSemiconWeak: Boolean,    // 韩股是否明显走弱
        val note: String
    )

    // ═══════════════════════════════════════════
    // 主题 key 常量（与 IndustryThemeClassifier 对齐）
    // ═══════════════════════════════════════════
    private const val T_AI = "AI算力硬件"
    private const val T_STORAGE = "存储"
    private const val T_PCB = "PCB"
    private const val T_RARE_METAL = "稀有金属"
    private const val T_RARE_GAS = "稀有气体"
    private const val T_SEMI = "半导体国产替代"
    private const val T_WAR = "战争资源(石油/黄金)"
    private const val T_CHEM = "化工"
    private const val T_GRID = "电网设备"
    private const val T_NEW_ENERGY = "新能源"

    /** 战争期间偏好的主题（军贸/能源/资源/军工） */
    private val WAR_POSITIVE_THEMES = listOf(
        T_WAR, T_RARE_METAL, T_RARE_GAS
    )

    /** 战争期间承压的主题（成长/估值敏感） */
    private val WAR_NEGATIVE_THEMES = listOf(
        T_AI, T_STORAGE, T_PCB, T_SEMI
    )

    // ═══════════════════════════════════════════
    // 对外主入口
    // ═══════════════════════════════════════════

    /**
     * 运行事件场景分析。
     *
     * @param context Context
     * @param eventState 当前事件状态（由 Agent/新闻写入；为空则自动从行情推导默认状态）
     * @param quotes 全球市场 + 大宗商品行情（来自 [MacroMarketDataFetcher.fetch]）
     * @return 板块偏好矩阵 + 场景摘要
     */
    suspend fun analyze(
        context: Context,
        eventState: EventState = readEventState(context),
        quotes: Map<String, MacroMarketDataFetcher.AssetQuote> = emptyMap()
    ): ScenarioResult {
        // 1. 油价趋势（联动化工）
        val oilTrend = assessOilTrend(quotes)
        // 2. 全球信号（美股/韩股）
        val globalSignals = assessGlobalSignals(quotes)
        // 3. 构建板块偏好矩阵
        val biases = buildBiases(eventState, oilTrend, globalSignals)

        val summary = buildSummary(eventState, oilTrend, globalSignals, biases)

        return ScenarioResult(eventState, biases, oilTrend, globalSignals, summary)
    }

    // ═══════════════════════════════════════════
    // 油价 → 化工联动
    // ═══════════════════════════════════════════

    private fun assessOilTrend(quotes: Map<String, MacroMarketDataFetcher.AssetQuote>): OilTrend {
        val wti = quotes[MacroMarketDataFetcher.Key.OIL_WTI]
        val brent = quotes[MacroMarketDataFetcher.Key.OIL_BRENT]
        val oilChange = (wti?.changePct ?: brent?.changePct ?: 0.0)
        return when {
            oilChange >= 1.0 -> OilTrend.RISING
            oilChange <= -1.0 -> OilTrend.FALLING
            else -> OilTrend.STABLE
        }
    }

    // ═══════════════════════════════════════════
    // 美股 / 韩股 信号
    // ═══════════════════════════════════════════

    private fun assessGlobalSignals(
        quotes: Map<String, MacroMarketDataFetcher.AssetQuote>
    ): GlobalSignals {
        val ndx = quotes[MacroMarketDataFetcher.Key.US_NDX]?.changePct
            ?: quotes[MacroMarketDataFetcher.Key.US_SPX]?.changePct ?: 0.0
        val kospi = quotes[MacroMarketDataFetcher.Key.KR_KOSPI]?.changePct ?: 0.0

        val usWeak = ndx <= -1.5
        val krWeak = kospi <= -1.0

        val note = buildString {
            if (usWeak) append("美股科技隔夜走弱(${"%.1f".format(ndx)}%)，A股科技/半导体承压。")
            if (krWeak) append("韩股走弱(${"%.1f".format(kospi)}%)，存储/半导体产业链联动承压。")
            if (!usWeak && !krWeak) append("海外市场平稳，无显著外部冲击。")
        }
        return GlobalSignals(ndx, kospi, usWeak, krWeak, note)
    }

    // ═══════════════════════════════════════════
    // 板块偏好矩阵
    // ═══════════════════════════════════════════

    private fun buildBiases(
        eventState: EventState,
        oilTrend: OilTrend,
        globalSignals: GlobalSignals
    ): Map<String, SectorBias> {
        val bias = LinkedHashMap<String, MutableList<Pair<Int, String>>>()

        fun add(theme: String, score: Int, reason: String) {
            bias.getOrPut(theme) { mutableListOf() }.add(score to reason)
        }

        // ── 战争状态：前/中/后期切换 ──
        when (eventState.warPhase) {
            WarPhase.NONE -> { /* 无影响 */ }
            WarPhase.PRE -> {
                // 战前：避险+军工+资源先行，成长观望
                WAR_POSITIVE_THEMES.forEach { add(it, 20, "战前避险升温") }
                add(T_RARE_METAL, 15, "战前资源囤积预期")
            }
            WarPhase.OUTBREAK -> {
                // 爆发初期：能源/黄金/军工急涨，成长被抽血
                add(T_WAR, 40, "战争爆发，能源黄金避险")
                add(T_RARE_METAL, 25, "战略资源价格飙升")
                add(T_RARE_GAS, 20, "稀有气体供应扰动")
                WAR_NEGATIVE_THEMES.forEach { add(it, -15, "战争抽血成长板块") }
            }
            WarPhase.PERSIST -> {
                // 僵持：化工（能源→化工）接力，军工持续
                add(T_WAR, 25, "战争僵持，能源高位")
                add(T_CHEM, 15, "油价高位传导化工")
                add(T_RARE_METAL, 15, "资源持续偏紧")
            }
            WarPhase.ESCALATION -> {
                // 升级：全面避险，成长大幅承压
                add(T_WAR, 50, "战争升级，全面避险")
                add(T_RARE_METAL, 30, "战略资源告急")
                add(T_CHEM, 20, "能源价格飙升传导")
                WAR_NEGATIVE_THEMES.forEach { add(it, -25, "战争升级抽血+避险") }
            }
            WarPhase.POST -> {
                // 战后：风险偏好修复，成长/周期回补，能源回落
                add(T_AI, 20, "战后风险偏好修复")
                add(T_STORAGE, 15, "战后需求回补")
                add(T_SEMI, 15, "战后科技修复")
                add(T_WAR, -15, "战后能源避险退潮")
            }
        }

        // ── 货币政策 / 通胀 ──
        when (eventState.policyPhase) {
            PolicyPhase.EASING -> {
                add(T_AI, 15, "流动性宽松利好成长")
                add(T_SEMI, 15, "降息预期修复成长估值")
                add(T_STORAGE, 10, "流动性宽松提振科技")
            }
            PolicyPhase.HOLD -> { /* 中性 */ }
            PolicyPhase.HAWKISH -> {
                // 鹰派/加息：压制成长估值，利好抗通胀资源
                add(T_AI, -15, "加息压制高估值成长")
                add(T_STORAGE, -10, "利率上行压制存储估值")
                add(T_PCB, -10, "加息压制PCB估值")
                add(T_RARE_METAL, 15, "抗通胀资源受益")
                add(T_WAR, 10, "高通胀避险资产受益")
            }
        }

        // ── 油价 → 化工联动（动态） ──
        when (oilTrend) {
            OilTrend.RISING -> {
                add(T_WAR, 15, "油价上行利好石油")
                add(T_CHEM, 20, "油价上行传导化工（油头化工/石化产品）")
                add(T_RARE_METAL, 10, "油价上行带动资源")
            }
            OilTrend.FALLING -> {
                add(T_CHEM, -10, "油价下行，化工成本支撑减弱")
                add(T_WAR, -10, "油价回落，石油承压")
            }
            OilTrend.STABLE -> { /* 中性 */ }
        }

        // ── 美股 / 韩股 外部联动 ──
        if (globalSignals.usTechWeak) {
            add(T_AI, -10, "美股科技走弱联动")
            add(T_SEMI, -10, "美股科技走弱联动")
            add(T_STORAGE, -10, "美股科技走弱联动")
        }
        if (globalSignals.krSemiconWeak) {
            add(T_STORAGE, -10, "韩股走弱，存储产业链承压")
            add(T_SEMI, -10, "韩股走弱，半导体联动")
        }

        // ── 汇总为净偏好分 ──
        val result = LinkedHashMap<String, SectorBias>()
        for ((theme, items) in bias) {
            val net = items.sumOf { it.first }.coerceIn(-100, 100)
            result[theme] = SectorBias(theme, net, items.map { it.second })
        }

        // 未显式命中的主题给中性 0
        for (t in listOf(T_AI, T_STORAGE, T_PCB, T_RARE_METAL, T_RARE_GAS, T_SEMI, T_WAR, T_CHEM, T_GRID, T_NEW_ENERGY)) {
            result.putIfAbsent(t, SectorBias(t, 0, emptyList()))
        }

        return result
    }

    private fun buildSummary(
        eventState: EventState, oilTrend: OilTrend,
        globalSignals: GlobalSignals, biases: Map<String, SectorBias>
    ): String {
        val sb = StringBuilder()
        sb.append("【宏观场景】${eventState.warPhase.label} | ${eventState.policyPhase.label} | ${oilTrend.label}")
        if (globalSignals.usTechWeak || globalSignals.krSemiconWeak) sb.append(" | 外部走弱")
        val pos = biases.values.filter { it.biasScore > 0 }.sortedByDescending { it.biasScore }.take(3)
        val neg = biases.values.filter { it.biasScore < 0 }.sortedBy { it.biasScore }.take(3)
        if (pos.isNotEmpty()) sb.append("\n  顺风: " + pos.joinToString { "${it.theme}+${it.biasScore}" })
        if (neg.isNotEmpty()) sb.append("\n  逆风: " + neg.joinToString { "${it.theme}${it.biasScore}" })
        return sb.toString()
    }

    // ═══════════════════════════════════════════
    // 事件状态持久化（供 Agent/新闻写入 & 读取）
    // ═══════════════════════════════════════════

    private const val PREFS_NAME = "macro_event_state"
    private const val KEY_WAR = "war_phase"
    private const val KEY_POLICY = "policy_phase"
    private const val KEY_UPDATED = "last_updated"

    /** 由 Agent/用户根据新闻设置事件状态 */
    fun updateEventState(context: Context, warPhase: WarPhase, policyPhase: PolicyPhase) {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        p.edit()
            .putString(KEY_WAR, warPhase.name)
            .putString(KEY_POLICY, policyPhase.name)
            .putString(KEY_UPDATED, java.time.LocalDate.now().toString())
            .apply()
        Log.i(TAG, "事件状态更新: ${warPhase.label} | ${policyPhase.label}")
    }

    fun readEventState(context: Context): EventState {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val war = runCatching { WarPhase.valueOf(p.getString(KEY_WAR, WarPhase.NONE.name) ?: "") }
            .getOrDefault(WarPhase.NONE)
        val policy = runCatching { PolicyPhase.valueOf(p.getString(KEY_POLICY, PolicyPhase.HOLD.name) ?: "") }
            .getOrDefault(PolicyPhase.HOLD)
        return EventState(war, policy, p.getString(KEY_UPDATED, "") ?: "")
    }
}
