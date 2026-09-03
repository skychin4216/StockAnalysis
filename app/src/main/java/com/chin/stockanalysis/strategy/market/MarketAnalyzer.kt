package com.chin.stockanalysis.strategy.market

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import com.chin.stockanalysis.strategy.backtest.SectorRotationEngine
import com.chin.stockanalysis.strategy.data.FactorDataProvider
import com.chin.stockanalysis.strategy.data.InstitutionalRatingProvider
import com.chin.stockanalysis.strategy.data.SmartMoneyCache
import com.chin.stockanalysis.strategy.data.ZiplinePipeline
import com.chin.stockanalysis.stock.database.StockDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.chin.stockanalysis.config.DataConfig
import com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * ## A 股大盘综合分析引擎
 *
 * 纯量化计算，不依赖 LLM，速度快，可被多处调用：
 * - ShortTermQuantFragment / MidTermQuantFragment
 * - SimulationTradeEngine
 * - AutoSellEngine
 * - AI 对话模组
 *
 * ### 核心功能
 * 1. 大盘趋势判断（MA 多空排列 + ADX）
 * 2. 主力撤资 vs 量化砸盘识别（MFI / CMF / A/D 因子）
 * 3. 板块轮动推荐（SectorRotationEngine）
 * 4. 持仓股票风险评估（相对强弱 + 板块资金 + ATR 止损）
 * 5. 持仓股票卖出建议（综合所有分析结果）
 *
 * ### 使用方式
 * ```kotlin
 * val report = MarketAnalyzer.analyze(context, listOf("sh600519", "sz000858"))
 * // report.trend.direction      → BULLISH / BEARISH / OSCILLATION
 * // report.sellType.sellType   → INSTITUTIONAL_EXIT / QUANT_CRASH / NORMAL_SELLING
 * // report.sectorAdvice        → 板块轮动建议
 * // report.holdings            → 每个持仓的 HOLD / REDUCE / SELL
 * // report.summary             → 可直接展示在 UI 的文字摘要
 * ```
 */
object MarketAnalyzer {

    private const val TAG = "MarketAnalyzer"
    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** 上证指数代码 */
    private const val INDEX_CODE = "sh000001"

    /** 主力撤资阈值：当日主力净流入 < -50 亿（万元）*/
    private const val MAIN_NET_EXIT_THRESHOLD = -500000.0

    /** 量化砸盘 MFI 阈值 */
    private const val QUANT_CRASH_MFI_THRESHOLD = 30.0

    /** 量化砸盘 CMF 阈值 */
    private const val QUANT_CRASH_CMF_THRESHOLD = 0.0

    /** 量化砸盘量比阈值（今日量 / MA20量 > 此值视为异常）*/
    private const val VOLUME_SPIKE_THRESHOLD = 2.0

    /** ADX 趋势阈值：> 25 为明确趋势 */
    private const val ADX_TREND_THRESHOLD = 25.0

    /** 防御板块名单（大盘下行时优先推荐）*/
    private val DEFENSIVE_SECTOR_KEYWORDS = listOf("医药", "医疗", "食品", "银行", "保险", "公用事业", "高速公路", "电力")

    // ── TTL 快取：避免短时间内重复计算 ADX/MFI/板块轮动 ──
    private const val CACHE_TTL_MS = 30_000L  // 30 seconds

    @Volatile
    private var cachedReport: MarketReport? = null
    @Volatile
    private var cachedTimestamp: Long = 0L
    private val cacheLock = Any()

    // ════════════════════════════════════════════════════
    //  输出数据结构
    // ════════════════════════════════════════════════════

    /**
     * 大盘综合分析报告
     */
    data class MarketReport(
        /** 大盘趋势分析 */
        val trend: TrendAnalysis,
        /** 主力/量化卖出类型识别 */
        val sellType: SellTypeAnalysis,
        /** 板块轮动建议 */
        val sectorAdvice: SectorAdvice,
        /** 持仓股票评估列表 */
        val holdings: List<HoldingAdvice>,
        /** 外围市场分析（纳指/KOSPI/恒生等隔夜表现） */
        val overseas: OverseasMarketAnalysis = OverseasMarketAnalysis(),
        /** 文字摘要（可直接展示在 UI）*/
        val summary: String,
        /** 分析时间戳 */
        val timestamp: Long,
        /** 大盘量能状态（动态缩量冰点检测）*/
        val volumeState: VolumeState = VolumeState()
    )

    /**
     * 大盘趋势分析结果
     */
    data class TrendAnalysis(
        /** 趋势方向：BULLISH / BEARISH / OSCILLATION */
        val direction: String,
        /** 趋势强度分数 0-100 */
        val strength: Int,
        /** MA5 值 */
        val ma5: Double? = null,
        /** MA10 值 */
        val ma10: Double? = null,
        /** MA20 值 */
        val ma20: Double? = null,
        /** ADX(14) 值 */
        val adx: Double? = null,
        /** 趋势描述 */
        val description: String
    )

    /**
     * 卖出类型分析结果
     */
    data class SellTypeAnalysis(
        /** 卖出类型：INSTITUTIONAL_EXIT / QUANT_CRASH / NORMAL_SELLING / NONE */
        val sellType: String,
        /** 置信度 0-100 */
        val confidence: Int,
        /** 当日主力净流入（万元）*/
        val mainNetInflow: Double,
        /** MFI 值 */
        val mfi: Double? = null,
        /** 量比（今日成交量 / MA20成交量）*/
        val volumeRatio: Double,
        /** 卖出类型描述 */
        val description: String
    )

    /**
     * ## 大盘量能状态（动态缩量冰点检测）
     *
     * 阈值不写死绝对值，全部相对上证指数自身近 20 个交易日动态计算：
     * - volumeRatio20：今日成交量 / 前 20 日均量
     * - quantile20：今日成交量处于近 20 日的分位（0~1，越小越接近地量）
     * - isIcePoint：下跌途中缩量到近 20 日底部区域 → 多为"地量见地价"冰点，暂缓割肉
     */
    data class VolumeState(
        val volumeRatio20: Double = 0.0,
        val quantile20: Double = 0.0,
        val isIcePoint: Boolean = false,
        val hint: String = ""
    )

    /**
     * 板块轮动建议
     */
    data class SectorAdvice(
        /** 市场风格描述 */
        val marketStyle: String,
        /** 轮动速度 */
        val rotationSpeed: Double,
        /** 推荐板块列表 */
        val recommendedSectors: List<SectorRecommendation>,
        /** 防御板块资金占比 0~1 */
        val defensivePct: Double
    )

    /**
     * 板块推荐
     */
    data class SectorRecommendation(
        /** 板块名称 */
        val sectorName: String,
        /** 置信度 0~1 */
        val confidence: Double,
        /** 推荐理由 */
        val reason: String
    )

    /**
     * 持仓股票评估建议
     */
    data class HoldingAdvice(
        /** 股票代码 */
        val stockCode: String,
        /** 股票名称 */
        val stockName: String,
        /** 建议操作：HOLD / REDUCE / SELL */
        val action: String,
        /** 建议理由 */
        val reason: String,
        /** 相对大盘强弱（个股涨幅 - 大盘涨幅）*/
        val relativeStrength: Double,
        /** 板块资金流向（万元）*/
        val sectorFlow: Double,
        /** ATR 动态止损位 */
        val atrStopLoss: Double? = null,
        /** 机构共识评级 */
        val institutionalRating: String? = null,
        /** 机构目标价 */
        val ratingTargetPrice: Double? = null,
        /** 研报数量 */
        val ratingCount: Int = 0
    )

    /**
     * 外围市场分析结果
     *
     * 综合纳斯达克、KOSPI、恒生、道琼、标普的隔夜/实时涨跌，
     * 加权计算对 A 股的影响方向和强度。
     *
     * 权重设计：
     * - 纳指 0.35（科技/AI/半导体链与 A 股科技板块高度联动）
     * - KOSPI 0.25（三星/SK 海力士 → A 股存储/芯片链）
     * - 恒生 0.25（A/H 溢价、南向资金直接联动）
     * - 道琼 0.10（整体风险偏好）
     * - 标普 0.05（广义市场情绪）
     */
    data class OverseasMarketAnalysis(
        /** 加权方向：BULLISH / BEARISH / NEUTRAL / UNKNOWN */
        val direction: String = "UNKNOWN",
        /** 影响强度 0-100（abs(加权涨跌) 映射，±3% 封顶） */
        val strength: Int = 0,
        /** 加权涨跌幅（%） */
        val weightedChange: Double = 0.0,
        /** 各指数明细 */
        val indices: List<IndexMove> = emptyList(),
        /** 对 A 股板块的影响提示 */
        val impactHint: String = "",
        /** 数据是否为隔夜（A 股收盘后更新）还是实时 */
        val isOvernight: Boolean = false
    )

    data class IndexMove(
        val code: String,
        val name: String,
        val changePercent: Double,
        val weight: Double
    )

    // ════════════════════════════════════════════════════
    //  主入口
    // ════════════════════════════════════════════════════

    /**
     * 主入口方法 — 综合分析大盘与持仓（带 30 秒 TTL 快取）
     *
     * @param context ApplicationContext
     * @param holdingCodes 持仓股票代码列表（如 ["sh600519", "sz000858"]）
     * @return MarketReport 完整分析报告
     */
    suspend fun analyze(context: Context, holdingCodes: List<String>): MarketReport {
        val now = System.currentTimeMillis()
        val cached = cachedReport
        if (cached != null && now - cachedTimestamp < CACHE_TTL_MS) {
            Log.i(TAG, "使用快取报告（${now - cachedTimestamp}ms 前）")
            return cached
        }

        val report = analyzeInternal(context, holdingCodes)

        synchronized(cacheLock) {
            cachedReport = report
            cachedTimestamp = System.currentTimeMillis()
        }

        return report
    }

    /**
     * 清除快取（强制下次重新计算）
     */
    fun invalidateCache() {
        synchronized(cacheLock) {
            cachedReport = null
            cachedTimestamp = 0L
        }
        Log.i(TAG, "快取已清除")
    }

    /**
     * 内部实现 — 实际执行分析计算
     */
    private suspend fun analyzeInternal(context: Context, holdingCodes: List<String>): MarketReport =
        withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            Log.i(TAG, "========== 开始大盘综合分析 ==========")
            Log.i(TAG, "持仓数量: ${holdingCodes.size}, 代码: $holdingCodes")

            // 并行执行五大分析模组，任何一个失败不阻塞其他
            val trendDeferred = async {
                try { analyzeTrend(context) } catch (e: Exception) {
                    Log.w(TAG, "趋势分析失败: ${e.message}")
                    null
                }
            }
            val sellTypeDeferred = async {
                try { analyzeSellType(context) } catch (e: Exception) {
                    Log.w(TAG, "卖出类型分析失败: ${e.message}")
                    null
                }
            }
            val sectorDeferred = async {
                try { analyzeSectorAdvice(context) } catch (e: Exception) {
                    Log.w(TAG, "板块分析失败: ${e.message}")
                    null
                }
            }
            val holdingsDeferred = async {
                try {
                    val trend = trendDeferred.await()
                    val sellType = sellTypeDeferred.await()
                    if (trend != null && sellType != null) {
                        analyzeHoldings(context, holdingCodes, trend, sellType)
                    } else emptyList()
                } catch (e: Exception) {
                    Log.w(TAG, "持仓分析失败: ${e.message}")
                    holdingCodes.map { code ->
                        HoldingAdvice(
                            stockCode = code, stockName = "", action = "HOLD",
                            reason = "分析失败，建议观察", relativeStrength = 0.0,
                            sectorFlow = 0.0, atrStopLoss = null
                        )
                    }
                }
            }

            // 取得各模组结果（失败的模组返回预设值）
            val trend = trendDeferred.await() ?: TrendAnalysis(
                direction = "OSCILLATION", strength = 50,
                description = "趋势分析失败，预设为震荡"
            )

            val sellType = sellTypeDeferred.await() ?: SellTypeAnalysis(
                sellType = "NONE", confidence = 0, mainNetInflow = 0.0,
                volumeRatio = 0.0, description = "卖出类型分析失败，预设为正常"
            )

            val sectorAdvice = sectorDeferred.await() ?: SectorAdvice(
                marketStyle = "数据不足", rotationSpeed = 0.0,
                recommendedSectors = emptyList(), defensivePct = 0.0
            )

            val holdings = holdingsDeferred.await()

            // 外围市场（纯内存读取，无 I/O）
            val overseas = analyzeOverseasMarkets()
            if (overseas.direction != "UNKNOWN") {
                Log.i(TAG, "外围市场: ${overseas.direction}(强度${overseas.strength}) 加权${"%.2f".format(overseas.weightedChange)}% ${overseas.impactHint}")
            }

            // 大盘量能状态（动态缩量冰点检测，阈值相对近20日自身计算）
            val volumeState = analyzeVolumeState(context)
            val volumeHint = volumeState.hint.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: ""

            // 生成文字摘要
            val summary = buildSummary(trend, sellType, sectorAdvice, holdings, overseas) + volumeHint

            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "========== 大盘综合分析完成，耗时 ${elapsed}ms ==========")
            Log.i(TAG, "摘要: $summary")

            MarketReport(
                trend = trend,
                sellType = sellType,
                sectorAdvice = sectorAdvice,
                holdings = holdings,
                overseas = overseas,
                summary = summary,
                timestamp = System.currentTimeMillis(),
                volumeState = volumeState
            )
        }

    // ════════════════════════════════════════════════════
    //  模组 1：大盘趋势判断
    // ════════════════════════════════════════════════════

    /**
     * 分析大盘趋势
     * - 读取上证指数(sh000001)近30天K线
     * - 计算 MA5/MA10/MA20 多空排列
     * - 计算 ADX(14) 区分趋势/震荡
     *
     * @return TrendAnalysis 趋势分析结果
     */
    private suspend fun analyzeTrend(context: Context): TrendAnalysis {
        Log.i(TAG, "[趋势] 开始分析上证指数 $INDEX_CODE")

        val db = StockDatabase.getInstance(context)
        val snaps = db.dailySnapshotDao().getByCode(INDEX_CODE, 30)
            .sortedBy { it.date }

        if (snaps.size < 20) {
            Log.w(TAG, "[趋势] 上证指数本地数据不足: ${snaps.size} 条，尝试从东方财富 API 实时拉取...")
            val fetched = fetchIndexKlineFromApi(context)
            if (fetched != null && fetched.size >= 20) {
                Log.i(TAG, "[趋势] API 拉取成功: ${fetched.size} 条，写入本地数据库")
                // 将拉取的数据批量写入本地数据库供后续使用
                try { db.dailySnapshotDao().insertAll(fetched) } catch (_: Exception) { }
                return analyzeTrend(context) // 用新数据重新分析
            }
            Log.w(TAG, "[趋势] API 拉取失败或数据仍不足，使用默认震荡")
            return TrendAnalysis(
                direction = "OSCILLATION",
                strength = 50,
                description = "数据不足，无法判断趋势（需要至少 20 个交易日数据）"
            )
        }

        val closes = snaps.map { it.close }

        // 计算 MA5/MA10/MA20
        val ma5 = closes.takeLast(5).average()
        val ma10 = closes.takeLast(10).average()
        val ma20 = closes.takeLast(20).average()

        Log.i(TAG, "[趋势] MA5=$ma5, MA10=$ma10, MA20=$ma20")

        // 判断多空排列
        val isBullishAlign = ma5 > ma10 && ma10 > ma20  // 多头排列
        val isBearishAlign = ma5 < ma10 && ma10 < ma20  // 空头排列

        // 计算 ADX(14)
        val adx = computeADX(snaps)
        Log.i(TAG, "[趋势] ADX(14)=$adx")

        // 趋势方向判定
        val direction = when {
            isBullishAlign && adx > ADX_TREND_THRESHOLD -> "BULLISH"
            isBearishAlign && adx > ADX_TREND_THRESHOLD -> "BEARISH"
            isBullishAlign -> "BULLISH"
            isBearishAlign -> "BEARISH"
            else -> "OSCILLATION"
        }

        // 趋势强度计算（0-100）
        val strength = when (direction) {
            "BULLISH" -> {
                // 多头排列越完美 + ADX 越高 = 强度越高
                val alignScore = if (isBullishAlign) 40 else 0
                val adxScore = minOf(adx / 50.0 * 40, 40.0)
                // 近 5 日涨幅贡献
                val recentReturn = (closes.last() - closes[closes.size - 6]) / closes[closes.size - 6] * 100
                val returnScore = minOf(maxOf(recentReturn / 5.0 * 20, 0.0), 20.0)
                (alignScore + adxScore + returnScore).toInt().coerceIn(0, 100)
            }
            "BEARISH" -> {
                val alignScore = if (isBearishAlign) 40 else 0
                val adxScore = minOf(adx / 50.0 * 40, 40.0)
                val recentReturn = (closes.last() - closes[closes.size - 6]) / closes[closes.size - 6] * 100
                // 跌幅越大，空头强度越高
                val returnScore = minOf(maxOf(-recentReturn / 5.0 * 20, 0.0), 20.0)
                (alignScore + adxScore + returnScore).toInt().coerceIn(0, 100)
            }
            else -> {
                // 震荡：ADX 越低 = 震荡越明确，强度中等
                (50 + (30 - adx) * 0.5).toInt().coerceIn(20, 80)
            }
        }

        val description = buildTrendDescription(direction, strength, isBullishAlign, isBearishAlign, adx)
        Log.i(TAG, "[趋势] 方向=$direction, 强度=$strength, 描述=$description")

        return TrendAnalysis(
            direction = direction,
            strength = strength,
            ma5 = ma5,
            ma10 = ma10,
            ma20 = ma20,
            adx = adx,
            description = description
        )
    }

    /**
     * 计算 ADX(Average Directional Index, 14 周期)
     * ADX > 25 表示趋势明确，< 20 表示震荡
     */
    private fun computeADX(snaps: List<DailySnapshotEntity>): Double {
        if (snaps.size < 28) return 20.0  // ADX(14) 至少需要 28 根 K 线

        val period = 14
        val trueRanges = mutableListOf<Double>()
        val plusDMs = mutableListOf<Double>()
        val minusDMs = mutableListOf<Double>()

        for (i in 1 until snaps.size) {
            val high = snaps[i].high
            val low = snaps[i].low
            val prevHigh = snaps[i - 1].high
            val prevLow = snaps[i - 1].low
            val prevClose = snaps[i - 1].close

            // True Range
            val tr = maxOf(high - low, abs(high - prevClose), abs(low - prevClose))
            trueRanges.add(tr)

            // +DM / -DM
            val upMove = high - prevHigh
            val downMove = prevLow - low
            plusDMs.add(if (upMove > downMove && upMove > 0) upMove else 0.0)
            minusDMs.add(if (downMove > upMove && downMove > 0) downMove else 0.0)
        }

        // 计算平滑 TR、+DM、-DM（Wilder 平滑）
        val smoothedTR = mutableListOf<Double>()
        val smoothedPlusDM = mutableListOf<Double>()
        val smoothedMinusDM = mutableListOf<Double>()

        for (i in trueRanges.indices) {
            if (i < period) continue
            if (i == period) {
                smoothedTR.add(trueRanges.subList(0, period).sum())
                smoothedPlusDM.add(plusDMs.subList(0, period).sum())
                smoothedMinusDM.add(minusDMs.subList(0, period).sum())
            } else {
                smoothedTR.add(smoothedTR.last() - smoothedTR.last() / period + trueRanges[i])
                smoothedPlusDM.add(smoothedPlusDM.last() - smoothedPlusDM.last() / period + plusDMs[i])
                smoothedMinusDM.add(smoothedMinusDM.last() - smoothedMinusDM.last() / period + minusDMs[i])
            }
        }

        // 计算 +DI / -DI / DX / ADX
        val dxList = mutableListOf<Double>()
        for (i in smoothedTR.indices) {
            if (smoothedTR[i] == 0.0) continue
            val plusDI = smoothedPlusDM[i] / smoothedTR[i] * 100.0
            val minusDI = smoothedMinusDM[i] / smoothedTR[i] * 100.0
            val diSum = plusDI + minusDI
            val dx = if (diSum > 0) abs(plusDI - minusDI) / diSum * 100.0 else 0.0
            dxList.add(dx)
        }

        // ADX = DX 的平滑平均
        if (dxList.size < period) return 20.0

        var adx = dxList.take(period).average()
        for (i in period until dxList.size) {
            adx = (adx * (period - 1) + dxList[i]) / period
        }

        return adx
    }

    /**
     * 构建趋势描述文字
     */
    private fun buildTrendDescription(
        direction: String,
        strength: Int,
        isBullishAlign: Boolean,
        isBearishAlign: Boolean,
        adx: Double
    ): String {
        val alignDesc = when {
            isBullishAlign -> "均线多头排列（MA5>MA10>MA20）"
            isBearishAlign -> "均线空头排列（MA5<MA10<MA20）"
            else -> "均线纠缠，未形成明确排列"
        }
        val adxDesc = when {
            adx > 40 -> "趋势非常强烈"
            adx > 25 -> "趋势明确"
            adx > 20 -> "趋势一般"
            else -> "趋势不明显，震荡格局"
        }
        val dirDesc = when (direction) {
            "BULLISH" -> "看多"
            "BEARISH" -> "看空"
            else -> "震荡观望"
        }
        return "$dirDesc（强度 $strength/100），$alignDesc，$adxDesc（ADX=${"%.1f".format(adx)}）"
    }

    // ════════════════════════════════════════════════════
    //  模组 2：主力撤资 vs 量化砸盘识别
    // ════════════════════════════════════════════════════

    /**
     * 动态量能状态分析：下跌缩量冰点检测。
     *
     * 规则（全部相对自身近 20 日动态计算，不写死绝对值）：
     * - 今日量 / 前20日均量 ratio；ratio <= 0.9 → 明显缩量
     * - 今日量在近20日分位 quantile；quantile <= 0.35 → 接近地量区
     * - 近 5 日收盘下跌 且 缩量到地量区 → 判定"下跌缩量冰点"，提示暂缓割肉
     */
    private suspend fun analyzeVolumeState(context: Context): VolumeState {
        val empty = VolumeState()
        return try {
            val db = StockDatabase.getInstance(context)
            val snaps = db.dailySnapshotDao().getByCode(INDEX_CODE, 40)
                .sortedBy { it.date }
            if (snaps.size < 21) return empty
            val vols = snaps.map { it.volume.toDouble() }
            val closes = snaps.map { it.close }
            val today = vols.last()
            val prev20 = vols.takeLast(21).dropLast(1)
            val ma20 = prev20.average()
            val ratio = if (ma20 > 0) today / ma20 else 1.0
            val window = vols.takeLast(20)
            val below = window.count { it <= today }
            val quantile = below.toDouble() / window.size
            val chg5 = if (closes.size >= 6)
                (closes.last() - closes[closes.size - 6]) / closes[closes.size - 6] * 100.0
            else 0.0

            val shrinking = ratio <= 0.9
            val nearLow = quantile <= 0.35
            val isIce = shrinking && nearLow && chg5 < 0.0

            val hint = when {
                isIce -> "量能冰点提示：上证成交量为近20日分位${"%.0f".format(quantile * 100)}%（仅${"%.2f".format(ratio)}倍MA20量），近5日跌${"%.2f".format(chg5)}%且持续缩量到地量区（多数资金已躺平）。建议：此位置先不割肉，等反弹放量或出现反转信号再决策。"
                shrinking && nearLow -> "缩量提示：上证成交量为近20日分位${"%.0f".format(quantile * 100)}%（${"%.2f".format(ratio)}倍MA20量），接近地量冰点但尚未确认，防最后一跌；暂缓割肉并留意放量企稳信号。"
                ratio >= 1.5 -> "放量提示：今日上证成交量达20日均量的${"%.2f".format(ratio)}倍。若下跌放量需防恐慌宣泄/主力出货；若上涨放量则为有效放量，可提高仓位关注。"
                else -> ""
            }
            VolumeState(ratio, quantile, isIce, hint)
        } catch (e: Exception) {
            Log.w(TAG, "[量能] 冰点分析失败: ${e.message}")
            empty
        }
    }

    /**
     * 从东方财富 API 实时拉取上证指数 K 线数据作为 fallback
     * 当本地 daily_snapshot 中 sh000001 数据不足 20 条时调用
     */
    private suspend fun fetchIndexKlineFromApi(context: Context): List<DailySnapshotEntity>? {
        return try {
            withContext(Dispatchers.IO) {
                val endDate = LocalDate.now().format(DATE_FMT)
                val startDate = LocalDate.now().minusDays(45).format(DATE_FMT)
                // 上证指数 secid: 1.000001
                val url = "${DataConfig.eastmoneyPush2his}/stock/kline/get?" +
                        "secid=1.000001&klt=101&fqt=1" +
                        "&fields1=f1,f2,f3&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f61" +
                        "&beg=$startDate&end=$endDate&lmt=60"
                val client = OkHttpClient()
                val req = Request.Builder().url(url)
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .build()
                val resp = client.newCall(req).execute()
                if (!resp.isSuccessful) return@withContext null

                val body = resp.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val data = json.optJSONObject("data") ?: return@withContext null
                val klines = data.optJSONArray("klines") ?: return@withContext null

                val result = mutableListOf<DailySnapshotEntity>()
                for (i in 0 until klines.length()) {
                    val line = klines.getString(i).split(",")
                    if (line.size < 7) continue
                    val date = line[0]
                    val open = line[1].toDoubleOrNull() ?: continue
                    val close = line[2].toDoubleOrNull() ?: continue
                    val high = line[3].toDoubleOrNull() ?: continue
                    val low = line[4].toDoubleOrNull() ?: continue
                    val volume = line[5].toDoubleOrNull()?.toLong() ?: 0L
                    val changePct = line[6].toDoubleOrNull() ?: 0.0
                    result.add(DailySnapshotEntity(
                        date = date, code = INDEX_CODE, name = "上证指数",
                        open = open, close = close, high = high, low = low,
                        volume = volume, amount = 0.0, changePct = changePct
                    ))
                }
                result.sortedByDescending { it.date }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[趋势] API 拉取上证指数失败: ${e.message}")
            null
        }
    }

    /**
     * ## 主力/量化卖出类型识别
     *
     * 规则：
     * - mainNetInflow < threshold 且 isContinuousInflow == false → 主力撤资
     * - MFI < 30 且 CMF < 0 且成交量异常（量/MA20量 > 2）→ 量化砸盘
     * - 其他 → 正常卖出
     */
    private suspend fun analyzeSellType(context: Context): SellTypeAnalysis {
        Log.i(TAG, "[卖出类型] 开始分析 $INDEX_CODE")

        // 1. 获取大盘资金流向
        val factorProvider = FactorDataProvider()
        val capitalFlow = try {
            factorProvider.getCapitalFlow(INDEX_CODE)
        } catch (e: Exception) {
            Log.w(TAG, "[卖出类型] 资金流向获取失败: ${e.message}")
            FactorDataProvider.CapitalFlowResult()
        }

        Log.i(TAG, "[卖出类型] 主力净流入=${capitalFlow.mainNetInflow}万, 连续流入=${capitalFlow.isContinuousInflow}")

        // 2. 计算 MFI / CMF / A/D 因子
        val db = StockDatabase.getInstance(context)
        val snaps = db.dailySnapshotDao().getByCode(INDEX_CODE, 25)
            .sortedBy { it.date }

        val mfiResult = SmartMoneyCache.computeMFI(snaps, 14)
        val cmfResult = SmartMoneyCache.computeCMF(snaps, 20)

        Log.i(TAG, "[卖出类型] MFI=${mfiResult.value}, CMF=${cmfResult.value}")

        // 3. 计算量比（今日成交量 / MA20成交量）
        val volumeRatio = computeVolumeRatio(snaps)
        Log.i(TAG, "[卖出类型] 量比=$volumeRatio")

        // 4. 判断卖出类型
        val (sellType, confidence, description) = determineSellType(
            capitalFlow, mfiResult.value, cmfResult.value, volumeRatio
        )

        Log.i(TAG, "[卖出类型] 类型=$sellType, 置信度=$confidence, 描述=$description")

        return SellTypeAnalysis(
            sellType = sellType,
            confidence = confidence,
            mainNetInflow = capitalFlow.mainNetInflow,
            mfi = mfiResult.value,
            volumeRatio = volumeRatio,
            description = description
        )
    }

    /**
     * 计算量比：今日成交量 / MA20 成交量
     */
    private fun computeVolumeRatio(snaps: List<DailySnapshotEntity>): Double {
        if (snaps.size < 21) return 0.0

        val todayVolume = snaps.last().volume.toDouble()
        val ma20Volume = snaps.takeLast(21).dropLast(1).map { it.volume }.average()

        return if (ma20Volume > 0) todayVolume / ma20Volume else 0.0
    }

    /**
     * 判断卖出类型
     */
    private fun determineSellType(
        capitalFlow: FactorDataProvider.CapitalFlowResult,
        mfi: Double,
        cmf: Double,
        volumeRatio: Double
    ): Triple<String, Int, String> {
        // 优先判断量化砸盘（条件最严格，需要三个因子同时满足）
        val isQuantCrash = mfi < QUANT_CRASH_MFI_THRESHOLD
            && cmf < QUANT_CRASH_CMF_THRESHOLD
            && volumeRatio > VOLUME_SPIKE_THRESHOLD

        // 主力撤资判断
        val isInstitutionalExit = capitalFlow.mainNetInflow < MAIN_NET_EXIT_THRESHOLD
            && !capitalFlow.isContinuousInflow

        return when {
            // 同时出现主力撤资 + 量化砸盘：双重危险
            isInstitutionalExit && isQuantCrash -> Triple(
                "INSTITUTIONAL_EXIT",
                95,
                "主力持续撤资（净流入${"%.0f".format(capitalFlow.mainNetInflow)}万元）且出现量化砸盘特征" +
                    "（MFI=${"%.1f".format(mfi)}，CMF=${"%.3f".format(cmf)}，量比=${"%.1f".format(volumeRatio)}），" +
                    "建议立即减仓防御"
            )

            // 主力撤资
            isInstitutionalExit -> {
                val severity = abs(capitalFlow.mainNetInflow)
                val conf = when {
                    severity > 1000000 -> 90  // 超 100 亿
                    severity > 500000 -> 80   // 超 50 亿
                    else -> 70
                }
                Triple(
                    "INSTITUTIONAL_EXIT",
                    conf,
                    "主力明显撤资（净流入${"%.0f".format(capitalFlow.mainNetInflow)}万元，" +
                        "3日=${"%.0f".format(capitalFlow.inflow3Day)}万，5日=${"%.0f".format(capitalFlow.inflow5Day)}万），" +
                        "建议减仓，避开主动卖压"
                )
            }

            // 量化砸盘
            isQuantCrash -> Triple(
                "QUANT_CRASH",
                75,
                "侦测到量化砸盘特征（MFI=${"%.1f".format(mfi)}低位，CMF=${"%.3f".format(cmf)}为负，" +
                    "量比=${"%.1f".format(volumeRatio)}倍异常放量），" +
                    "短期回避，不恐慌抛售，等待量能回落"
            )

            // 正常卖出（有轻微资金流出但不严重）
            capitalFlow.mainNetInflow < -50000 -> Triple(
                "NORMAL_SELLING",
                40,
                "市场正常获利了结（净流入${"%.0f".format(capitalFlow.mainNetInflow)}万元），" +
                    "暂不构成系统性风险"
            )

            // 无明显卖出
            else -> Triple(
                "NONE",
                10,
                "未侦测到异常卖出行为，市场资金面正常"
            )
        }
    }

    // ════════════════════════════════════════════════════
    //  模组 3：板块轮动推荐
    // ════════════════════════════════════════════════════

    /**
     * 分析板块轮动情况
     * - 使用 SectorRotationEngine 预测明日热门板块
     * - 使用 SectorRotationEngine 判断市场风格
     * - 如果大盘下行，优先推荐防御板块
     */
    private suspend fun analyzeSectorAdvice(context: Context): SectorAdvice {
        Log.i(TAG, "[板块] 开始分析板块轮动")

        val engine = SectorRotationEngine(context)

        // 1. 预测明日热门板块
        val predictions = try {
            engine.predictTomorrow(5)
        } catch (e: Exception) {
            Log.w(TAG, "[板块] 预测失败: ${e.message}")
            emptyList()
        }

        // 2. 市场风格诊断
        val marketStyle = try {
            engine.marketStyleDiagnosis()
        } catch (e: Exception) {
            Log.w(TAG, "[板块] 风格诊断失败: ${e.message}")
            "数据不足，无法诊断"
        }

        // 3. 轮动速度
        val rotationSpeed = try {
            engine.rotationSpeed()
        } catch (e: Exception) {
            Log.w(TAG, "[板块] 轮动速度获取失败: ${e.message}")
            0.0
        }

        Log.i(TAG, "[板块] 风格=$marketStyle, 轮动速度=$rotationSpeed, 预测板块数=${predictions.size}")

        // 4. 构建推荐板块列表
        val recommendations = mutableListOf<SectorRecommendation>()

        for (pred in predictions) {
            recommendations.add(
                SectorRecommendation(
                    sectorName = pred.sectorName,
                    confidence = pred.confidence,
                    reason = "动量=${"%.2f".format(pred.momentum)}，资金=${"%.2f".format(pred.capitalScore)}，" +
                        "置信度=${"%.0f".format(pred.confidence * 100)}%"
                )
            )
        }

        // 5. 如果预测列表中没有防御板块，补充推荐
        val hasDefensive = recommendations.any { rec ->
            DEFENSIVE_SECTOR_KEYWORDS.any { rec.sectorName.contains(it) }
        }

        // 判断是否需要推荐防御板块（根据预测和风格）
        val needsDefensive = predictions.isEmpty() || rotationSpeed > 5.0 ||
            marketStyle.contains("快速轮动") || marketStyle.contains("数据不足")

        if (!hasDefensive && needsDefensive) {
            Log.i(TAG, "[板块] 补充防御板块推荐")
            for (keyword in DEFENSIVE_SECTOR_KEYWORDS.take(3)) {
                recommendations.add(
                    SectorRecommendation(
                        sectorName = keyword,
                        confidence = 0.3,
                        reason = "防御性板块，市场不确定时的避风港"
                    )
                )
            }
        }

        // 6. 计算防御板块占比
        val defensiveCount = recommendations.count { rec ->
            DEFENSIVE_SECTOR_KEYWORDS.any { rec.sectorName.contains(it) }
        }
        val defensivePct = if (recommendations.isNotEmpty()) {
            defensiveCount.toDouble() / recommendations.size
        } else 0.0

        Log.i(TAG, "[板块] 推荐 ${recommendations.size} 个板块，防御占比=${"%.0f".format(defensivePct * 100)}%")

        return SectorAdvice(
            marketStyle = marketStyle,
            rotationSpeed = rotationSpeed,
            recommendedSectors = recommendations,
            defensivePct = defensivePct
        )
    }

    // ════════════════════════════════════════════════════
    //  模组 4 & 5：持仓股票风险评估 + 卖出建议
    // ════════════════════════════════════════════════════

    /**
     * 分析持仓股票，给出 HOLD / REDUCE / SELL 建议
     *
     * 综合考量：
     * - 大盘趋势方向
     * - 主力/量化卖出类型
     * - 个股相对大盘强弱
     * - 板块资金流向
     * - ATR 动态止损位
     */
    private suspend fun analyzeHoldings(
        context: Context,
        holdingCodes: List<String>,
        trend: TrendAnalysis,
        sellType: SellTypeAnalysis
    ): List<HoldingAdvice> {
        if (holdingCodes.isEmpty()) {
            Log.i(TAG, "[持仓] 无持仓，跳过分析")
            return emptyList()
        }

        Log.i(TAG, "[持仓] 开始分析 ${holdingCodes.size} 只持仓股票")

        val db = StockDatabase.getInstance(context)
        val factorProvider = FactorDataProvider()
        val ratingProvider = InstitutionalRatingProvider()

        // 获取大盘近 5 日涨跌幅（用于计算相对强弱）
        val indexSnaps = db.dailySnapshotDao().getByCode(INDEX_CODE, 6).sortedBy { it.date }
        val indexChangePct5d = if (indexSnaps.size >= 6) {
            (indexSnaps.last().close - indexSnaps[indexSnaps.size - 6].close) / indexSnaps[indexSnaps.size - 6].close * 100
        } else 0.0

        // 获取持仓股票的 ATR（通过 ZiplinePipeline）
        val pipeline = ZiplinePipeline(context)
        val today = LocalDate.now().format(DATE_FMT)

        // 构造 StockRealtime 列表用于 ZiplinePipeline
        val holdingStocks = mutableListOf<com.chin.stockanalysis.stock.StockRealtime>()
        val stockNameMap = mutableMapOf<String, String>()

        for (code in holdingCodes) {
            try {
                val latestSnap = db.dailySnapshotDao().getByCode(code, 1).firstOrNull()
                if (latestSnap != null) {
                    val yc = if (latestSnap.changePct != 0.0 && latestSnap.close != 0.0) {
                        latestSnap.close / (1.0 + latestSnap.changePct / 100.0)
                    } else latestSnap.close

                    holdingStocks.add(
                        com.chin.stockanalysis.stock.StockRealtime(
                            code = latestSnap.code,
                            name = latestSnap.name,
                            price = latestSnap.close,
                            open = latestSnap.open,
                            yestClose = yc,
                            high = latestSnap.high,
                            low = latestSnap.low,
                            volume = latestSnap.volume,
                            amount = latestSnap.amount,
                            changePercent = latestSnap.changePct,
                            changeAmount = latestSnap.close * latestSnap.changePct / 100,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                    stockNameMap[code] = latestSnap.name
                } else {
                    stockNameMap[code] = ""
                }
            } catch (e: Exception) {
                Log.w(TAG, "[持仓] 获取 $code 数据失败: ${e.message}")
                stockNameMap[code] = ""
            }
        }

        // 计算 ATR 因子
        val factors = try {
            pipeline.computeAll(holdingStocks, today, 30)
        } catch (e: Exception) {
            Log.w(TAG, "[持仓] ATR 计算失败: ${e.message}")
            ZiplinePipeline.FactorSet()
        }

        // 逐一分析每个持仓
        val advices = mutableListOf<HoldingAdvice>()
        var batchDelay = 0L

        for (code in holdingCodes) {
            try {
                // 获取个股近 5 日涨跌幅
                val stockSnaps = db.dailySnapshotDao().getByCode(code, 6).sortedBy { it.date }
                val stockChangePct5d = if (stockSnaps.size >= 6) {
                    (stockSnaps.last().close - stockSnaps[stockSnaps.size - 6].close) / stockSnaps[stockSnaps.size - 6].close * 100
                } else 0.0

                // 相对大盘强弱
                val relativeStrength = stockChangePct5d - indexChangePct5d

                // 板块资金流向（个股资金流向近似）
                if (batchDelay > 0) delay(batchDelay)
                val flowResult = factorProvider.getCapitalFlow(code)
                batchDelay = 200L  // 避免频率限制

                // ATR 止损位
                val atr = factors.atr14[code]
                val currentPrice = stockSnaps.lastOrNull()?.close ?: 0.0
                val atrStopLoss = if (atr != null && atr > 0 && currentPrice > 0) {
                    currentPrice - atr * 1.5  // 1.5 倍 ATR 止损
                } else null

                // 机构评级
                val ratingSummary = try {
                    ratingProvider.getRatingSummary(code, days = 90)
                } catch (_: Exception) {
                    null
                }

                // 综合判断操作建议
                val (action, reason) = determineHoldingAction(
                    trend = trend,
                    sellType = sellType,
                    relativeStrength = relativeStrength,
                    sectorFlow = flowResult.mainNetInflow,
                    isContinuousInflow = flowResult.isContinuousInflow,
                    atr = atr,
                    currentPrice = currentPrice,
                    institutionalRating = ratingSummary?.consensusRating
                )

                val advice = HoldingAdvice(
                    stockCode = code,
                    stockName = stockNameMap[code] ?: "",
                    action = action,
                    reason = reason,
                    relativeStrength = relativeStrength,
                    sectorFlow = flowResult.mainNetInflow,
                    atrStopLoss = atrStopLoss,
                    institutionalRating = ratingSummary?.consensusRating,
                    ratingTargetPrice = ratingSummary?.avgTargetPrice,
                    ratingCount = ratingSummary?.totalReports ?: 0
                )

                advices.add(advice)
                Log.i(TAG, "[持仓] $code ${stockNameMap[code]}: $action, 相对强弱=${"%.2f".format(relativeStrength)}%, " +
                    "板块资金=${"%.0f".format(flowResult.mainNetInflow)}万, ATR止损=${if (atrStopLoss != null) "%.2f".format(atrStopLoss) else "N/A"}")

            } catch (e: Exception) {
                Log.w(TAG, "[持仓] 分析 $code 失败: ${e.message}")
                advices.add(
                    HoldingAdvice(
                        stockCode = code,
                        stockName = stockNameMap[code] ?: "",
                        action = "HOLD",
                        reason = "分析过程出错，建议观察",
                        relativeStrength = 0.0,
                        sectorFlow = 0.0,
                        atrStopLoss = null
                    )
                )
            }
        }

        return advices
    }

    /**
     * 综合判断持仓操作建议
     *
     * 规则优先级（由高到低）：
     * 1. 量化砸盘 → 短期回避，不恐慌抛售 → HOLD（附带警告）
     * 2. 主力撤资 + 个股弱于大盘 → SELL
     * 3. 主力撤资 + 个股强于大盘 → REDUCE
     * 4. 大盘 BEARISH + 个股弱于大盘 → SELL
     * 5. 大盘 BEARISH + 个股强于大盘 + 板块有资金流入 → HOLD
     * 6. ATR 止损被触发 → SELL
     * 7. 其他 → HOLD
     */
    private fun determineHoldingAction(
        trend: TrendAnalysis,
        sellType: SellTypeAnalysis,
        relativeStrength: Double,
        sectorFlow: Double,
        isContinuousInflow: Boolean,
        atr: Double?,
        currentPrice: Double,
        institutionalRating: String? = null
    ): Pair<String, String> {
        val dir = trend.direction
        val type = sellType.sellType

        // 量化砸盘：短期回避，不恐慌抛售
        if (type == "QUANT_CRASH") {
            return "HOLD" to "量化砸盘期间，不建议恐慌抛售。观察量能回落后再决策，" +
                "严格执行 ATR 止损"
        }

        // 主力撤资 + 个股弱于大盘 → 建议卖出
        if (type == "INSTITUTIONAL_EXIT" && relativeStrength < -1.0) {
            return "SELL" to "主力撤资环境下，该股相对大盘弱（落后${"%.1f".format(abs(relativeStrength))}%），" +
                "建议减仓或清仓"
        }

        // 主力撤资 + 个股强于大盘 → 建议减仓
        if (type == "INSTITUTIONAL_EXIT") {
            return "REDUCE" to "主力撤资环境下，虽然该股相对大盘偏强（领先${"%.1f".format(relativeStrength)}%），" +
                "但仍建议适度减仓，降低风险暴露"
        }

        // 大盘下行 + 个股弱于大盘 → 建议卖出
        if (dir == "BEARISH" && relativeStrength < -2.0 && trend.strength > 60) {
            return "SELL" to "大盘明确下行（强度${trend.strength}），个股相对大盘弱（落后${"%.1f".format(abs(relativeStrength))}%），" +
                "建议止损离场"
        }

        // 大盘下行 + 个股强于大盘 + 板块有资金流入 → 持有观察
        if (dir == "BEARISH" && relativeStrength > 1.0 && sectorFlow > 0 && isContinuousInflow) {
            return "HOLD" to "大盘虽然下行，但该股相对偏强（领先${"%.1f".format(relativeStrength)}%），" +
                "且板块有资金持续流入（${"%.0f".format(sectorFlow)}万元），持有观察"
        }

        // 大盘下行 + 个股强于大盘但板块无资金流入 → 谨慎减仓
        if (dir == "BEARISH" && relativeStrength > 0) {
            return "REDUCE" to "大盘下行中，该股虽然相对偏强，但板块资金未见持续流入，" +
                "建议适度减仓防御"
        }

        // ATR 止损位检查
        if (atr != null && atr > 0 && currentPrice > 0) {
            // 这里不直接判断是否触发止损，因为需要知道成本价
            // 仅在理由中提示止损位
            val stopLossPrice = currentPrice - atr * 1.5
            if (dir == "BEARISH") {
                return "HOLD" to "大盘偏弱，建议设置 ATR 止损位 ${"%.2f".format(stopLossPrice)}，" +
                    "跌破即止损"
            }
        }

        // 机构评级为卖出/减持 → 建议减仓或卖出
        if (institutionalRating == "卖出" || institutionalRating == "减持") {
            return "SELL" to "机构共识评级为${institutionalRating}，建议减仓或清仓"
        }

        // 机构评级为中性 → 谨慎持有
        if (institutionalRating == "中性") {
            return "HOLD" to "机构共识评级为中性，暂时观望"
        }

        // 大盘看多 + 个股偏强 → 持有
        if (dir == "BULLISH" && relativeStrength > 0) {
            return "HOLD" to "大盘看多（强度${trend.strength}），个股相对偏强，建议持有"
        }

        // 默认持有观察
        return "HOLD" to "当前无明确卖出信号，建议继续持有观察"
    }

    // ════════════════════════════════════════════════════
    //  模组 6：外围市场分析
    // ════════════════════════════════════════════════════

    /** 外围指数权重（对 A 股影响力） */
    private val OVERSEAS_WEIGHTS = mapOf(
        "NDX" to 0.35,   // 纳斯达克 → 科技/半导体联动
        "KS11" to 0.25,  // 韩国 KOSPI → 半导体/面板
        "HSI" to 0.25,   // 恒生 → A/H 溢价、南向资金
        "DJI" to 0.10,   // 道琼 → 整体风险偏好
        "SPX" to 0.05    // 标普 → 广义情绪
    )

    /** 板块联动映射：指数大跌时提示受压板块 */
    private val INDEX_SECTOR_IMPACT = mapOf(
        "NDX" to listOf("科技", "半导体", "芯片", "AI", "软件"),
        "KS11" to listOf("半导体", "面板", "存储"),
        "HSI" to listOf("港股通", "金融", "地产"),
        "DJI" to listOf("外贸", "航运"),
        "SPX" to listOf("消费", "医药")
    )

    /**
     * 分析外围市场对 A 股的隔夜影响。
     * 纯内存读取（globalIndices 由后台调度器刷新），无 I/O。
     */
    private fun analyzeOverseasMarkets(): OverseasMarketAnalysis {
        val indices = EastMoneyHotSectorSource.globalIndices
        if (indices.isEmpty()) return OverseasMarketAnalysis()

        val moves = OVERSEAS_WEIGHTS.mapNotNull { (codeSuffix, weight) ->
            val idx = indices.firstOrNull { it.code.equals(codeSuffix, ignoreCase = true) } ?: return@mapNotNull null
            IndexMove(code = idx.code, name = idx.name, changePercent = idx.changePercent, weight = weight)
        }
        if (moves.isEmpty()) return OverseasMarketAnalysis()

        val weightedChange = moves.sumOf { it.changePercent * it.weight }
        val direction = when {
            weightedChange > 0.5 -> "BULLISH"
            weightedChange < -0.5 -> "BEARISH"
            else -> "NEUTRAL"
        }
        val strength = (abs(weightedChange) / 3.0 * 100).toInt().coerceIn(0, 100)

        // 生成影响提示：找出跌幅 > 1% 的指数，提示受压板块
        val impactHint = buildString {
            val bigMovers = moves.filter { abs(it.changePercent) >= 1.0 }
            if (bigMovers.isNotEmpty()) {
                val sectors = bigMovers.flatMap { INDEX_SECTOR_IMPACT[it.code.uppercase()] ?: emptyList() }.distinct()
                val worst = bigMovers.minByOrNull { it.changePercent }
                if (worst != null && worst.changePercent < -1.0) {
                    append("${worst.name}大跌${"%.1f".format(worst.changePercent)}%")
                    if (sectors.isNotEmpty()) append("，A股${sectors.take(3).joinToString("/")}板块承压")
                } else {
                    val best = bigMovers.maxByOrNull { it.changePercent }
                    if (best != null && best.changePercent > 1.0) {
                        append("${best.name}大涨${"%.1f".format(best.changePercent)}%")
                        if (sectors.isNotEmpty()) append("，A股${sectors.take(3).joinToString("/")}板块受益")
                    }
                }
            }
        }

        // 判断是否隔夜：A股交易时段(9:30-15:00 CST)外围数据视为隔夜
        val hour = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Shanghai")).hour
        val isOvernight = hour in 9..15

        return OverseasMarketAnalysis(
            direction = direction,
            strength = strength,
            weightedChange = weightedChange,
            indices = moves,
            impactHint = impactHint,
            isOvernight = isOvernight
        )
    }

    private fun buildSummary(
        trend: TrendAnalysis,
        sellType: SellTypeAnalysis,
        sectorAdvice: SectorAdvice,
        holdings: List<HoldingAdvice>,
        overseas: OverseasMarketAnalysis = OverseasMarketAnalysis()
    ): String {
        val sb = StringBuilder()

        // 趋势摘要
        val trendEmoji = when (trend.direction) {
            "BULLISH" -> "[多]"
            "BEARISH" -> "[空]"
            else -> "[震]"
        }
        sb.append("$trendEmoji 大盘趋势: ${trend.direction}(强度${trend.strength})。")
        sb.append(trend.description).append("。\n")

        // 外围市场摘要
        if (overseas.direction != "UNKNOWN" && overseas.indices.isNotEmpty()) {
            val ovsEmoji = when (overseas.direction) {
                "BULLISH" -> "[外多]"
                "BEARISH" -> "[外空]"
                else -> "[外平]"
            }
            sb.append("$ovsEmoji 外围: 加权${"%.2f".format(overseas.weightedChange)}%(强度${overseas.strength})。")
            if (overseas.impactHint.isNotEmpty()) sb.append(overseas.impactHint).append("。")
            sb.append("\n")
        }

        // 卖出类型摘要
        when (sellType.sellType) {
            "INSTITUTIONAL_EXIT" -> sb.append("!! 主力撤资警报(置信度${sellType.confidence}%)。")
                .append(sellType.description).append("。\n")
            "QUANT_CRASH" -> sb.append("! 量化砸盘侦测(置信度${sellType.confidence}%)。")
                .append(sellType.description).append("。\n")
            "NORMAL_SELLING" -> sb.append("正常卖出。").append(sellType.description).append("。\n")
            else -> {} // NONE 不显示
        }

        // 板块摘要
        if (sectorAdvice.recommendedSectors.isNotEmpty()) {
            sb.append("板块: ${sectorAdvice.marketStyle}。")
            sb.append("推荐关注: ")
            sb.append(sectorAdvice.recommendedSectors.take(3).joinToString("、") { it.sectorName })
            sb.append("。\n")
        }

        // 持仓操作摘要
        if (holdings.isNotEmpty()) {
            val sellCount = holdings.count { it.action == "SELL" }
            val reduceCount = holdings.count { it.action == "REDUCE" }
            val holdCount = holdings.count { it.action == "HOLD" }

            sb.append("持仓建议: ")
            when {
                sellCount > 0 -> sb.append("$sellCount 只建议卖出")
                reduceCount > 0 -> sb.append("$reduceCount 只建议减仓")
                else -> sb.append("全部持有观察")
            }
            if (holdCount > 0 && (sellCount > 0 || reduceCount > 0)) {
                sb.append("，$holdCount 只持有")
            }
            sb.append("。")

            // 列出需要操作的股票
            val actionStocks = holdings.filter { it.action in listOf("SELL", "REDUCE") }
            if (actionStocks.isNotEmpty()) {
                sb.append(" 具体: ")
                sb.append(actionStocks.joinToString("；") {
                    "${it.stockName ?: it.stockCode}→${it.action}"
                })
            }
            sb.append("。")

            // 机构评级摘要
            val ratedHoldings = holdings.filter { !it.institutionalRating.isNullOrBlank() && it.institutionalRating != "无数据" }
            if (ratedHoldings.isNotEmpty()) {
                sb.append("\n机构评级: ")
                sb.append(ratedHoldings.take(3).joinToString("；") {
                    val target = if (it.ratingTargetPrice != null) "目标价${"%.1f".format(it.ratingTargetPrice)}" else ""
                    "${it.stockName ?: it.stockCode}=${it.institutionalRating}(${it.ratingCount}份)$target"
                })
                sb.append("。")
            }
        }

        return sb.toString()
    }
}
