package com.chin.stockanalysis.strategy.monitor

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.notification.TradeNotifier
import com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource
import com.chin.stockanalysis.stock.database.ChinaMarketTradingHours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * ## 板块龙头异动监测器
 *
 * 后台进程定时扫描各板块（行业 + 概念）及其龙头股，
 * 识别板块趋势信号并写入 [SectorSignalStore]，供选股 Pipeline
 * （QuantTradingPipeline）在选股时融合：
 * - 板块强势 + 龙头领涨 → 候选加分（顺势）
 * - 板块弱势 / 鱼尾行情 → 候选降权或剔除（避免接盘）
 * - 板块大跌但龙头企稳 → 低吸信号（跌出机会）
 *
 * 注：2026-09-04 起不再向通知栏独立推送异动（龙头/板块异动一律
 * 作为选股 Pipeline 的输入信号，由选股结果统一呈现）。
 *
 * ### 使用方式
 * ```kotlin
 * SectorLeaderMonitor.startMonitor(appContext, scope)   // 启动周期扫描（只喂信号，不推送）
 * SectorLeaderMonitor.scanOnce(appContext)              // 手动触发一次
 * SectorSignalStore.getSignal("存储芯片")               // 选股时查询
 * ```
 */
object SectorLeaderMonitor {

    private const val TAG = "SectorLeaderMonitor"

    /** 龙头大涨通知阈值（%） */
    private const val LEADER_SURGE_THRESHOLD = 5.0
    /** 龙头大跌通知阈值（%） */
    private const val LEADER_CRASH_THRESHOLD = -5.0
    /** 板块整体大跌阈值（%） */
    private const val SECTOR_CRASH_THRESHOLD = -3.0
    /** 板块整体大涨阈值（%） */
    private const val SECTOR_SURGE_THRESHOLD = 3.0
    /** 每板块监测的龙头数量（成分股涨幅前 N 名，自动覆盖创业板 300/301、科创板 688/689） */
    private const val LEADER_TOP_N = 3
    /** 纳入龙头监测的板块上限（行业+概念合计，按热度 compositeScore 取前 N） */
    private const val LEADER_SCAN_SECTOR_LIMIT = 30
    /** 盘中扫描周期（毫秒）：A股交易时间内每 10 分钟刷新一次龙头榜 */
    private const val SCAN_INTERVAL_TRADING_MS = 10 * 60 * 1000L
    /** 次龙头（第2/3名）单独大幅异动阈值（%），比首龙头更高以避免刷屏 */
    private const val SECONDARY_LEADER_THRESHOLD = 7.0

    /** 通知去重：同板块同方向 30 分钟内只提醒一次 */
    private const val NOTIFY_COOLDOWN_MS = 30 * 60 * 1000L

    private val lastNotifyMap = ConcurrentHashMap<String, Long>()

    @Volatile
    private var isRunning = false

    /**
     * 启动周期扫描（应在后台 Service / AppBackgroundRunner 中调用）
     */
    fun startMonitor(context: Context, scope: CoroutineScope) {
        if (isRunning) return
        isRunning = true
        scope.launch {
            while (isRunning) {
                // 仅 A股交易时间内扫描并刷新信号；非交易时段不刷新，直接睡到下一开盘
                if (ChinaMarketTradingHours.a股是否交易中()) {
                    try {
                        // 只写 SectorSignalStore 供选股 Pipeline 融合，不向通知栏独立推送异动
                        scanOnce(context)
                    } catch (e: Exception) {
                        Log.w(TAG, "板块龙头扫描异常: ${e.message}")
                    }
                    delay(SCAN_INTERVAL_TRADING_MS)
                } else {
                    delay(nextOpenDelayMs())
                }
            }
        }
        Log.i(TAG, "板块龙头异动监测已启动（仅交易时段，盘中每 ${SCAN_INTERVAL_TRADING_MS / 1000}s）")
    }

    /**
     * 距离下一 A股开盘的毫秒数：
     * - 未开盘（9:30 前）→ 等到今日 9:30
     * - 午间休市（11:30-13:00）→ 等到今日 13:00
     * - 已收盘 / 周末休市 → 等到下一交易日 9:30
     */
    private fun nextOpenDelayMs(): Long {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = ZonedDateTime.now(zone)
        val today930 = now.toLocalDate().atTime(9, 30).atZone(zone)
        val today1300 = now.toLocalDate().atTime(13, 0).atZone(zone)
        val weekend = now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY
        val target = when {
            !weekend && now.isBefore(today930) -> today930
            !weekend && now.isBefore(today1300) -> today1300
            else -> {
                var d = now.toLocalDate()
                do { d = d.plusDays(1) } while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY)
                d.atTime(9, 30).atZone(zone)
            }
        }
        return maxOf(1_000L, Duration.between(now, target).toMillis() + 1_000L)
    }

    fun stopMonitor() {
        isRunning = false
    }

    /**
     * 单次扫描：遍历行业板块 + 概念板块，识别龙头异动与板块趋势。
     *
     * 每个板块拉取其成分股涨幅前 [LEADER_TOP_N] 名作为龙头榜
     * （自动覆盖创业板 300/301、科创板 688/689），并按热度取前 [LEADER_SCAN_SECTOR_LIMIT] 个板块。
     *
     * @param notify 是否推送通知（默认 false：仅刷新信号缓存，供选股 Pipeline 融合）
     */
    suspend fun scanOnce(context: Context, notify: Boolean = false) = withContext(Dispatchers.IO) {
        val industry = EastMoneyHotSectorSource.industrySectors
        val concept = EastMoneyHotSectorSource.conceptSectors

        val all = (industry + concept)
            .filter { it.name.isNotBlank() }
            .sortedByDescending { it.compositeScore }
            .take(LEADER_SCAN_SECTOR_LIMIT)

        if (all.isEmpty()) {
            Log.d(TAG, "板块数据为空（数据源未就绪），跳过本次扫描")
            return@withContext
        }

        val source = EastMoneyHotSectorSource()
        val signals = mutableListOf<SectorSignal>()
        // 本次扫描收集到的待推送异动（同一次扫描的多个板块异动合并成一条通知，避免刷屏）
        val notifyItems = mutableListOf<String>()
        for (sector in all) {
            // ── 拉取板块成分股涨幅前 N 龙头（含 300 创业板 / 688 科创板）──
            val leaders = try {
                source.fetchSectorLeaders(sector.code, LEADER_TOP_N, fid = "f3")
            } catch (_: Exception) { emptyList() }

            // 以成分股实时数据为准，板块接口自带的领涨股字段作兜底
            val topLeader = leaders.firstOrNull()
            val leaderChange = topLeader?.changePercent ?: sector.top1ChangePercent
            val leaderCode = topLeader?.code ?: sector.top1StockCode
            val leaderName = topLeader?.name ?: sector.top1StockName

            var alert: SectorAlert? = null
            when {
                leaderChange >= LEADER_SURGE_THRESHOLD -> alert = SectorAlert(
                    type = SectorAlertType.LEADER_SURGE,
                    message = "龙头 ${leaderName}(${leaderCode.takeLast(4)}) 大涨 ${"%.2f".format(leaderChange)}%，板块 ${sector.name} 异动拉升"
                )
                leaderChange <= LEADER_CRASH_THRESHOLD -> alert = SectorAlert(
                    type = SectorAlertType.LEADER_CRASH,
                    message = "龙头 ${leaderName}(${leaderCode.takeLast(4)}) 大跌 ${"%.2f".format(leaderChange)}%，板块 ${sector.name} 承压"
                )
                sector.changePercent >= SECTOR_SURGE_THRESHOLD -> alert = SectorAlert(
                    type = SectorAlertType.SECTOR_SURGE,
                    message = "板块 ${sector.name} 大涨 ${"%.2f".format(sector.changePercent)}%（龙头 ${leaderName} ${"%.2f".format(leaderChange)}%）"
                )
                sector.changePercent <= SECTOR_CRASH_THRESHOLD -> alert = SectorAlert(
                    type = SectorAlertType.SECTOR_CRASH,
                    message = "板块 ${sector.name} 大跌 ${"%.2f".format(sector.changePercent)}%（龙头 ${leaderName} ${"%.2f".format(leaderChange)}%）"
                )
            }

            // 次龙头（第2/3名）单独大幅异动 → 附带到通知中（避免刷屏）
            val secondary = leaders.drop(1).firstOrNull {
                it.changePercent >= SECONDARY_LEADER_THRESHOLD || it.changePercent <= -SECONDARY_LEADER_THRESHOLD
            }
            val secondaryNote = if (secondary != null) {
                " | 次龙头 ${secondary.name}(${secondary.code.takeLast(4)}) ${"%.2f".format(secondary.changePercent)}%"
            } else ""

            // ── 趋势判断：结合 5/10/20 日涨幅与当日涨跌 ──
            val trend = judgeTrend(sector.changePercent, leaderChange, sector.change5d, sector.change10d, sector.change20d)

            // 通知（带去重；仅交易时段 notify=true 才推送）——只收集，循环结束后合并成一条发送
            if (notify && alert != null && shouldNotify(sector.name, alert.type)) {
                notifyItems.add(
                    buildString {
                        appendLine("【${sector.name}】${alert.message}$secondaryNote")
                        appendLine("  5日: ${"%.2f".format(sector.change5d)}% | 10日: ${"%.2f".format(sector.change10d)}% | 20日: ${"%.2f".format(sector.change20d)}% | 资金: ${"%.0f".format(sector.mainNetInflow)}万")
                        if (leaders.isNotEmpty()) {
                            appendLine("  龙头榜: ${leaders.joinToString(" ") { "${it.name}(${it.board})${"%.1f".format(it.changePercent)}%" }}")
                        }
                        appendLine("  建议: ${trend.suggestion}")
                    }
                )
                lastNotifyMap["${sector.name}_${alert.type.name}"] = System.currentTimeMillis()
            }

            signals.add(
                SectorSignal(
                    sectorCode = sector.code,
                    sectorName = sector.name,
                    leaderCode = leaderCode,
                    leaderName = leaderName,
                    leaderChangePct = leaderChange,
                    sectorChangePct = sector.changePercent,
                    change5d = sector.change5d,
                    change10d = sector.change10d,
                    change20d = sector.change20d,
                    mainNetInflow = sector.mainNetInflow,
                    trend = trend,
                    leaders = leaders.map {
                        SectorLeader(
                            code = it.code, name = it.name, price = it.price,
                            changePercent = it.changePercent, mainNetInflow = it.mainNetInflow,
                            board = it.board
                        )
                    },
                    updatedAt = System.currentTimeMillis()
                )
            )
        }

        // ── 同一次扫描的多个板块异动整合成一条通知推送（避免多次刷屏）──
        if (notifyItems.isNotEmpty()) {
            TradeNotifier.send(
                context,
                "🔔 板块异动监测 — ${notifyItems.size} 个板块异动",
                notifyItems.joinToString("\n").trim(),
                tag = "sector_leader_batch"
            )
            Log.i(TAG, "板块异动通知已合并发送: ${notifyItems.size} 个板块")
        }

        SectorSignalStore.updateAll(signals)
        Log.i(TAG, "扫描完成: ${signals.size} 个板块 / 龙头 ${signals.sumOf { it.leaders.size }} 只，异动 ${signals.count { it.trend.trendType != SectorTrendType.NEUTRAL }} 个")
    }

    private fun shouldNotify(sectorName: String, type: SectorAlertType): Boolean {
        val key = "${sectorName}_${type.name}"
        val last = lastNotifyMap[key] ?: 0L
        return System.currentTimeMillis() - last >= NOTIFY_COOLDOWN_MS
    }

    /**
     * 板块趋势判定：
     * - 短期(5日)与中期(20日)同向 → 强势/弱势
     * - 短期跌幅大但中期仍涨 → 低吸（回调中继）
     * - 短期涨幅大且中期涨幅巨大 → 鱼尾（警惕见顶）
     * - 龙头与板块背离 → 谨慎
     */
    private fun judgeTrend(
        sectorChange: Double,
        leaderChange: Double,
        change5d: Double,
        _change10d: Double,
        change20d: Double
    ): SectorTrend {
        val shortTerm = change5d
        val midTerm = change20d

        return when {
            // 短期暴跌但中期仍向上 → 低吸机会
            shortTerm <= -5.0 && midTerm > 0 && leaderChange > sectorChange ->
                SectorTrend(SectorTrendType.PULLBACK_BUY, "低吸", "短期回调(5日${"%.1f".format(shortTerm)}%)，中期趋势未破(20日${"%.1f".format(midTerm)}%)，龙头企稳可低吸")

            // 中期涨幅过大 + 短期仍大涨 → 鱼尾行情
            midTerm >= 20.0 && shortTerm >= 5.0 ->
                SectorTrend(SectorTrendType.FISH_TAIL, "谨慎", "20日已涨${"%.1f".format(midTerm)}%进入鱼尾，短期${"%.1f".format(shortTerm)}%续涨有追高风险")

            // 强势：短中期同向上涨
            shortTerm > 3.0 && midTerm > 5.0 ->
                SectorTrend(SectorTrendType.STRONG, "持有/顺势", "短中期同向走强(5日${"%.1f".format(shortTerm)}%，20日${"%.1f".format(midTerm)}%)")

            // 弱势：短中期同向下跌
            shortTerm < -3.0 && midTerm < -3.0 ->
                SectorTrend(SectorTrendType.WEAK, "回避/抛售", "短中期同向走弱(5日${"%.1f".format(shortTerm)}%，20日${"%.1f".format(midTerm)}%)")

            // 龙头与板块背离：板块跌但龙头涨 → 关注
            sectorChange < -1.0 && leaderChange > 1.0 ->
                SectorTrend(SectorTrendType.DIVERGENT, "关注", "板块跌${"%.1f".format(sectorChange)}%但龙头逆势涨${"%.1f".format(leaderChange)}%，注意龙头独立性")

            else ->
                SectorTrend(SectorTrendType.NEUTRAL, "观望", "趋势不明，按常规纪律执行")
        }
    }
}

// ════════════════════════════════════════
//  信号数据结构
// ════════════════════════════════════════

enum class SectorTrendType {
    /** 强势上涨，可顺势 */
    STRONG,
    /** 弱势下跌，回避 */
    WEAK,
    /** 回调低吸机会 */
    PULLBACK_BUY,
    /** 鱼尾行情，谨慎 */
    FISH_TAIL,
    /** 龙头与板块背离 */
    DIVERGENT,
    /** 中性观望 */
    NEUTRAL
}

enum class SectorAlertType {
    LEADER_SURGE, LEADER_CRASH, SECTOR_SURGE, SECTOR_CRASH
}

data class SectorAlert(
    val type: SectorAlertType,
    val message: String
)

data class SectorTrend(
    val trendType: SectorTrendType,
    val label: String,
    val suggestion: String
)

data class SectorSignal(
    val sectorCode: String,
    val sectorName: String,
    val leaderCode: String,
    val leaderName: String,
    val leaderChangePct: Double,
    val sectorChangePct: Double,
    val change5d: Double,
    val change10d: Double,
    val change20d: Double,
    val mainNetInflow: Double,
    val trend: SectorTrend,
    /** 板块龙头榜（成分股涨幅前 N，含 300 创业板 / 688 科创板） */
    val leaders: List<SectorLeader> = emptyList(),
    val updatedAt: Long
)

/**
 * 板块龙头（成分股涨幅前 N 名）
 * @param board 板块归属：主板/创业板/科创板/北交所
 */
data class SectorLeader(
    val code: String,
    val name: String,
    val price: Double,
    val changePercent: Double,
    val mainNetInflow: Double,
    val board: String = ""
)

/**
 * ## 板块信号存储
 *
 * 内存级板块信号缓存，供选股 Pipeline 实时查询。
 * 由 [SectorLeaderMonitor.scanOnce] 周期刷新。
 */
object SectorSignalStore {

    private val signals = ConcurrentHashMap<String, SectorSignal>()
    private val nameToCode = ConcurrentHashMap<String, String>()

    fun updateAll(list: List<SectorSignal>) {
        signals.clear()
        nameToCode.clear()
        for (s in list) {
            signals[s.sectorCode] = s
            nameToCode[s.sectorName] = s.sectorCode
        }
    }

    /** 按板块代码查询 */
    fun getSignal(code: String): SectorSignal? = signals[code]

    /** 按板块名称查询 */
    fun getSignalByName(name: String): SectorSignal? {
        val code = nameToCode[name] ?: return null
        return signals[code]
    }

    /** 获取所有信号 */
    fun getAll(): List<SectorSignal> = signals.values.toList()

    /**
     * 判断某股票是否处于「鱼尾/弱势」板块（选股时降权或剔除）
     * @param stockSectors 该股票所属板块名称列表
     */
    fun isInWeakOrFishTail(stockSectors: List<String>): Boolean {
        return stockSectors.any {
            val s = getSignalByName(it) ?: return@any false
            s.trend.trendType == SectorTrendType.WEAK ||
                s.trend.trendType == SectorTrendType.FISH_TAIL
        }
    }

    /**
     * 判断某股票是否处于「低吸/强势」板块（选股时加分）
     */
    fun isInBuyZone(stockSectors: List<String>): Boolean {
        return stockSectors.any {
            val s = getSignalByName(it) ?: return@any false
            s.trend.trendType == SectorTrendType.STRONG ||
                s.trend.trendType == SectorTrendType.PULLBACK_BUY
        }
    }

    /**
     * 反查某只股票是否出现在板块龙头榜中（做T/选股时识别"龙头地位"）
     * @return 命中该股票的板块信号列表
     */
    fun getLeaderSignals(stockCode: String): List<SectorSignal> {
        return signals.values.filter { s -> s.leaders.any { it.code == stockCode } }
    }

    /**
     * 获取股票板块信号的摘要（用于选股 reason）
     */
    fun summarize(stockSectors: List<String>): String {
        val hits = stockSectors.mapNotNull { getSignalByName(it) }
            .distinctBy { it.sectorName }
            .take(3)
        if (hits.isEmpty()) return ""
        return hits.joinToString(";") { "${it.sectorName}[${it.trend.label}${"%.1f".format(it.sectorChangePct)}%]" }
    }

    fun clear() {
        signals.clear()
        nameToCode.clear()
    }
}
