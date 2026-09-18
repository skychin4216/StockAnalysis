package com.chin.stockanalysis.strategy.trade.macro

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.strategy.trade.MarketTrendGuard
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * ## 宏观环境动态分析器（MacroEnvironmentAnalyzer）
 *
 * ### 作用
 * 将「大环境」各类信号聚合为一份**统一的宏观快照 [MacroSnapshot]**，
 * 供选股（[UnifiedStockClassifier]）和交易（[AutoTradePortfolioEngine]）消费，
 * 实现**根据大环境动态分析规划**，而非机械套用固定参数。
 *
 * ### 聚合的信号维度
 * | 维度 | 数据源 | 说明 |
 * |------|--------|------|
 * | 大盘转弱 | [MarketTrendGuard] | 三类指数 RISK_OFF 状态机（趋势离场保护） |
 * | 指数偏离 | [IndexDeviationMonitor] | 科创50/创业板 vs 上证偏离 → 均值回归回调风险 |
 * | 事件场景 | [EventScenarioEngine] | 战争前/中/后期 + 通胀/加息 + 油价→化工联动 |
 * | 全球市场 | [MacroMarketDataFetcher] | 美股/韩股/原油/黄金/美债/美元 |
 *
 * ### 输出
 * 1. **综合风险分** [overallRisk]（0-100），越高越危险，用于动态调整整体仓位。
 * 2. **板块偏好矩阵** [sectorBias]，叠加产业主线过滤，实现「顺风重仓、逆风规避」。
 * 3. **指数专属信号**，供按板块归属动态调整趋势跟随纪律。
 *
 * 结果持久化到 SharedPreferences，供交易时段内一致消费（避免频繁重算）。
 */
object MacroEnvironmentAnalyzer {

    private const val TAG = "MacroEnvironmentAnalyzer"
    private const val PREFS_NAME = "macro_env_snapshot"
    private const val KEY_JSON = "snapshot_json"

    /**
     * 宏观快照
     */
    data class MacroSnapshot(
        val timestamp: Long,
        val overallRisk: Int,                    // 0-100，越高越危险
        val riskLevel: RiskLevel,                // SAFE / CAUTION / DANGER / CRITICAL
        val sectorBias: Map<String, Int>,        // 产业主题 → 净偏好分(-100..+100)
        val indexDeviation: Map<String, IndexDeviationMonitor.IndexDeviation>,
        val indexRiskOff: Map<String, Boolean>,  // indexCode → 大盘是否转弱
        val eventSummary: String,
        val summary: String
    )

    enum class RiskLevel(val label: String) {
        SAFE("安全"),
        CAUTION("谨慎"),
        DANGER("危险"),
        CRITICAL("极端")
    }

    // ═══════════════════════════════════════════
    // 对外主入口
    // ═══════════════════════════════════════════

    /**
     * 执行一次完整的宏观分析，返回最新快照（并持久化）。
     *
     * @param context Context
     * @param refreshTrendGuard 是否同时刷新大盘转弱状态机（默认 true）
     */
    suspend fun analyze(
        context: Context,
        refreshTrendGuard: Boolean = true
    ): MacroSnapshot = coroutineScope {
        // ── 并行采集四类信号 ──
        val trendGuardDeferred = async {
            try {
                if (refreshTrendGuard) MarketTrendGuard.refreshAll(context)
                    .mapValues { it.value.riskOff }
                else MarketTrendGuard.ALL_INDICES.associateWith { MarketTrendGuard.isRiskOff(context, it) }
            } catch (e: Exception) {
                Log.w(TAG, "大盘转弱刷新失败: ${e.message}")
                emptyMap()
            }
        }
        val deviationDeferred = async {
            try { IndexDeviationMonitor.monitor(context) } catch (e: Exception) {
                Log.w(TAG, "指数偏离监测失败: ${e.message}")
                emptyMap()
            }
        }
        val globalDeferred = async {
            try { MacroMarketDataFetcher.fetch(context).quotes } catch (e: Exception) {
                Log.w(TAG, "全球数据拉取失败: ${e.message}")
                emptyMap()
            }
        }
        val eventStateDeferred = async {
            try { EventScenarioEngine.readEventState(context) } catch (e: Exception) {
                EventScenarioEngine.EventState()
            }
        }

        val riskOffMap = trendGuardDeferred.await()
        val deviations = deviationDeferred.await()
        val quotes = globalDeferred.await()
        val eventState = eventStateDeferred.await()

        // ── 事件场景（板块偏好矩阵） ──
        val scenario = try {
            EventScenarioEngine.analyze(context, eventState, quotes)
        } catch (e: Exception) {
            Log.w(TAG, "事件场景分析失败: ${e.message}")
            EventScenarioEngine.ScenarioResult(eventState, emptyMap(),
                EventScenarioEngine.OilTrend.STABLE,
                EventScenarioEngine.GlobalSignals(0.0, 0.0, false, false, ""), "")
        }

        // ── 指数偏离 → 回调风险加成到对应板块 ──
        val sectorBias = applyDeviationAdjustment(scenario.biases, deviations)

        // ── 综合风险分 ──
        val overallRisk = computeOverallRisk(riskOffMap, deviations, scenario, quotes)

        val snapshot = MacroSnapshot(
            timestamp = System.currentTimeMillis(),
            overallRisk = overallRisk,
            riskLevel = levelOf(overallRisk),
            sectorBias = sectorBias,
            indexDeviation = deviations,
            indexRiskOff = riskOffMap,
            eventSummary = scenario.summary,
            summary = buildSummary(overallRisk, riskOffMap, deviations, scenario)
        )

        persist(context, snapshot)
        Log.i(TAG, "宏观分析完成: 风险$overallRisk(${snapshot.riskLevel.label}) | ${snapshot.summary}")
        snapshot
    }

    /**
     * 读取最近一次持久化的宏观快照（若未过期）。用于交易时段内一致消费。
     *
     * @param maxAgeMs 允许的最大快照年龄（默认 10 分钟）
     */
    fun cached(context: Context, maxAgeMs: Long = 10 * 60_000L): MacroSnapshot? {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = p.getString(KEY_JSON, null) ?: return null
        return try {
            val snap = JsonCodec.decode(json)
            if (System.currentTimeMillis() - snap.timestamp > maxAgeMs) null else snap
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 便捷：获取某只股票所属指数当前的「趋势跟随离场 + 回调」双重风控状态。
     *
     * @return 是否需要对该股所在板块的**趋势跟随**做风控（暂停追高 / 减仓）
     */
    suspend fun shouldRiskControlTrendFollow(context: Context, stockCode: String): Boolean {
        // 1) 大盘转弱 → 强制离场
        if (MarketTrendGuard.shouldForceExit(context, stockCode)) return true
        // 2) 板块指数偏离到 HIGH/EXTREME → 规避追高
        val risk = IndexDeviationMonitor.riskForStock(context, stockCode)
        return risk == IndexDeviationMonitor.PullbackRisk.HIGH ||
            risk == IndexDeviationMonitor.PullbackRisk.EXTREME
    }

    // ═══════════════════════════════════════════
    // 计算逻辑
    // ═══════════════════════════════════════════

    /** 将指数偏离回调风险加成到对应板块主题偏好（偏离开得越高，该板块趋势股越危险 → 负向） */
    private fun applyDeviationAdjustment(
        biases: Map<String, EventScenarioEngine.SectorBias>,
        deviations: Map<String, IndexDeviationMonitor.IndexDeviation>
    ): Map<String, Int> {
        val out = HashMap<String, Int>()
        for ((theme, b) in biases) {
            var score = b.biasScore
            // 科创50 偏离 → 影响 AI/半导体/PCB/存储 等成长科技
            val star = deviations[MarketTrendGuard.INDEX_STAR]
            if (star != null && theme in setOf("AI算力硬件", "半导体国产替代", "PCB", "存储")) {
                score -= penalty(star.risk)
            }
            // 创业板指 偏离 → 影响 AI/新能源/创新成长
            val gem = deviations[MarketTrendGuard.INDEX_GEM]
            if (gem != null && theme in setOf("AI算力硬件", "新能源")) {
                score -= penalty(gem.risk)
            }
            out[theme] = score.coerceIn(-100, 100)
        }
        return out
    }

    private fun penalty(risk: IndexDeviationMonitor.PullbackRisk): Int = when (risk) {
        IndexDeviationMonitor.PullbackRisk.LOW -> 0
        IndexDeviationMonitor.PullbackRisk.MEDIUM -> 5
        IndexDeviationMonitor.PullbackRisk.HIGH -> 15
        IndexDeviationMonitor.PullbackRisk.EXTREME -> 25
    }

    /**
     * 综合风险分（0-100）：
     * - 大盘转弱（每触发一个指数 +25）
     * - 板块指数严重偏离回调（HIGH +10 / EXTREME +20）
     * - 战争升级 / 外部市场走弱 / 鹰派加息
     */
    private fun computeOverallRisk(
        riskOff: Map<String, Boolean>,
        deviations: Map<String, IndexDeviationMonitor.IndexDeviation>,
        scenario: EventScenarioEngine.ScenarioResult,
        quotes: Map<String, MacroMarketDataFetcher.AssetQuote>
    ): Int {
        var risk = 0
        // 大盘转弱
        risk += riskOff.count { it.value } * 25
        // 指数偏离
        for (d in deviations.values) {
            when (d.risk) {
                IndexDeviationMonitor.PullbackRisk.HIGH -> risk += 10
                IndexDeviationMonitor.PullbackRisk.EXTREME -> risk += 20
                else -> {}
            }
        }
        // 事件
        when (scenario.eventState.warPhase) {
            EventScenarioEngine.WarPhase.OUTBREAK -> risk += 15
            EventScenarioEngine.WarPhase.ESCALATION -> risk += 25
            EventScenarioEngine.WarPhase.PERSIST -> risk += 10
            else -> {}
        }
        when (scenario.eventState.policyPhase) {
            EventScenarioEngine.PolicyPhase.HAWKISH -> risk += 10
            else -> {}
        }
        // 外部市场
        if (scenario.globalSignals.usTechWeak) risk += 10
        if (scenario.globalSignals.krSemiconWeak) risk += 10
        // 美元走强 → 新兴市场承压
        val usd = quotes[MacroMarketDataFetcher.Key.USD_INDEX]?.changePct ?: 0.0
        if (usd >= 0.5) risk += 5

        return risk.coerceIn(0, 100)
    }

    private fun levelOf(risk: Int): RiskLevel = when {
        risk >= 70 -> RiskLevel.CRITICAL
        risk >= 45 -> RiskLevel.DANGER
        risk >= 25 -> RiskLevel.CAUTION
        else -> RiskLevel.SAFE
    }

    private fun buildSummary(
        overallRisk: Int,
        riskOff: Map<String, Boolean>,
        deviations: Map<String, IndexDeviationMonitor.IndexDeviation>,
        scenario: EventScenarioEngine.ScenarioResult
    ): String {
        val sb = StringBuilder()
        sb.append("宏观风险${overallRisk}/100(${levelOf(overallRisk).label})")
        val offNames = riskOff.filter { it.value }.keys.map { MarketTrendGuard.indexName(it) }
        if (offNames.isNotEmpty()) sb.append(" | 转弱:${offNames.joinToString()}")
        val risky = deviations.filter { it.value.risk.ordinal >= IndexDeviationMonitor.PullbackRisk.HIGH.ordinal }
        if (risky.isNotEmpty()) sb.append(" | 回调预警:" + risky.map { "${it.value.indexName}(${it.value.risk.label})" }.joinToString())
        if (scenario.summary.isNotBlank()) sb.append("\n").append(scenario.summary)
        return sb.toString()
    }

    // ═══════════════════════════════════════════
    // 持久化
    // ═══════════════════════════════════════════

    private fun persist(context: Context, snapshot: MacroSnapshot) {
        try {
            val json = JsonCodec.encode(snapshot)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_JSON, json).apply()
        } catch (e: Exception) {
            Log.w(TAG, "持久化失败: ${e.message}")
        }
    }

    /**
     * 轻量 JSON 编解码（避免引入额外依赖）。
     */
    private object JsonCodec {
        private val SEP = "\u0001"
        private val KV = "\u0002"

        fun encode(s: MacroSnapshot): String = buildString {
            append(s.timestamp); append(SEP)
            append(s.overallRisk); append(SEP)
            append(s.riskLevel.name); append(SEP)
            append(s.eventSummary.replace("\n", " ")); append(SEP)
            append(s.summary.replace("\n", " ")); append(SEP)
            append(s.indexRiskOff.entries.joinToString(",") { "${it.key}${KV}${it.value}" }); append(SEP)
            append(s.sectorBias.entries.joinToString(",") { "${it.key}${KV}${it.value}" })
        }

        fun decode(json: String): MacroSnapshot {
            val parts = json.split(SEP)
            val indexRiskOff = if (parts.size > 5)
                parts[5].split(",").filter { it.isNotBlank() }.associate {
                    val kv = it.split(KV); kv[0] to (kv.getOrNull(1) == "true")
                } else emptyMap()
            val sectorBias = if (parts.size > 6)
                parts[6].split(",").filter { it.isNotBlank() }.associate {
                    val kv = it.split(KV); kv[0] to (kv.getOrNull(1)?.toIntOrNull() ?: 0)
                } else emptyMap()
            return MacroSnapshot(
                timestamp = parts.getOrNull(0)?.toLongOrNull() ?: 0,
                overallRisk = parts.getOrNull(1)?.toIntOrNull() ?: 0,
                riskLevel = runCatching { RiskLevel.valueOf(parts.getOrNull(2) ?: "") }
                    .getOrDefault(RiskLevel.SAFE),
                sectorBias = sectorBias,
                indexDeviation = emptyMap(),   // 偏离详情不持久化，需实时重算
                indexRiskOff = indexRiskOff,
                eventSummary = parts.getOrNull(3) ?: "",
                summary = parts.getOrNull(4) ?: ""
            )
        }
    }
}
