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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * ## A 股大盤綜合分析引擎
 *
 * 純量化計算，不依賴 LLM，速度快，可被多處調用：
 * - ShortTermQuantFragment / MidTermQuantFragment
 * - SimulationTradeEngine
 * - AutoSellEngine
 * - AI 對話模組
 *
 * ### 核心功能
 * 1. 大盤趨勢判斷（MA 多空排列 + ADX）
 * 2. 主力撤資 vs 量化砸盤識別（MFI / CMF / A/D 因子）
 * 3. 板塊輪動推薦（SectorRotationEngine）
 * 4. 持倉股票風險評估（相對強弱 + 板塊資金 + ATR 止損）
 * 5. 持倉股票賣出建議（綜合所有分析結果）
 *
 * ### 使用方式
 * ```kotlin
 * val report = MarketAnalyzer.analyze(context, listOf("sh600519", "sz000858"))
 * // report.trend.direction      → BULLISH / BEARISH / OSCILLATION
 * // report.sellType.sellType   → INSTITUTIONAL_EXIT / QUANT_CRASH / NORMAL_SELLING
 * // report.sectorAdvice        → 板塊輪動建議
 * // report.holdings            → 每個持倉的 HOLD / REDUCE / SELL
 * // report.summary             → 可直接展示在 UI 的文字摘要
 * ```
 */
object MarketAnalyzer {

    private const val TAG = "MarketAnalyzer"
    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** 上證指數代碼 */
    private const val INDEX_CODE = "sh000001"

    /** 主力撤資閾值：當日主力淨流入 < -50 億（萬元）*/
    private const val MAIN_NET_EXIT_THRESHOLD = -500000.0

    /** 量化砸盤 MFI 閾值 */
    private const val QUANT_CRASH_MFI_THRESHOLD = 30.0

    /** 量化砸盤 CMF 閾值 */
    private const val QUANT_CRASH_CMF_THRESHOLD = 0.0

    /** 量化砸盤量比閾值（今日量 / MA20量 > 此值視為異常）*/
    private const val VOLUME_SPIKE_THRESHOLD = 2.0

    /** ADX 趨勢閾值：> 25 為明確趨勢 */
    private const val ADX_TREND_THRESHOLD = 25.0

    /** 防禦板塊名單（大盤下行時優先推薦）*/
    private val DEFENSIVE_SECTOR_KEYWORDS = listOf("醫藥", "醫療", "食品", "銀行", "保險", "公用事業", "高速公路", "電力")

    // ════════════════════════════════════════════════════
    //  輸出數據結構
    // ════════════════════════════════════════════════════

    /**
     * 大盤綜合分析報告
     */
    data class MarketReport(
        /** 大盤趨勢分析 */
        val trend: TrendAnalysis,
        /** 主力/量化賣出類型識別 */
        val sellType: SellTypeAnalysis,
        /** 板塊輪動建議 */
        val sectorAdvice: SectorAdvice,
        /** 持倉股票評估列表 */
        val holdings: List<HoldingAdvice>,
        /** 文字摘要（可直接展示在 UI）*/
        val summary: String,
        /** 分析時間戳 */
        val timestamp: Long
    )

    /**
     * 大盤趨勢分析結果
     */
    data class TrendAnalysis(
        /** 趨勢方向：BULLISH / BEARISH / OSCILLATION */
        val direction: String,
        /** 趨勢強度分數 0-100 */
        val strength: Int,
        /** MA5 值 */
        val ma5: Double? = null,
        /** MA10 值 */
        val ma10: Double? = null,
        /** MA20 值 */
        val ma20: Double? = null,
        /** ADX(14) 值 */
        val adx: Double? = null,
        /** 趨勢描述 */
        val description: String
    )

    /**
     * 賣出類型分析結果
     */
    data class SellTypeAnalysis(
        /** 賣出類型：INSTITUTIONAL_EXIT / QUANT_CRASH / NORMAL_SELLING / NONE */
        val sellType: String,
        /** 置信度 0-100 */
        val confidence: Int,
        /** 當日主力淨流入（萬元）*/
        val mainNetInflow: Double,
        /** MFI 值 */
        val mfi: Double? = null,
        /** 量比（今日成交量 / MA20成交量）*/
        val volumeRatio: Double,
        /** 賣出類型描述 */
        val description: String
    )

    /**
     * 板塊輪動建議
     */
    data class SectorAdvice(
        /** 市場風格描述 */
        val marketStyle: String,
        /** 輪動速度 */
        val rotationSpeed: Double,
        /** 推薦板塊列表 */
        val recommendedSectors: List<SectorRecommendation>,
        /** 防禦板塊資金佔比 0~1 */
        val defensivePct: Double
    )

    /**
     * 板塊推薦
     */
    data class SectorRecommendation(
        /** 板塊名稱 */
        val sectorName: String,
        /** 置信度 0~1 */
        val confidence: Double,
        /** 推薦理由 */
        val reason: String
    )

    /**
     * 持倉股票評估建議
     */
    data class HoldingAdvice(
        /** 股票代碼 */
        val stockCode: String,
        /** 股票名稱 */
        val stockName: String,
        /** 建議操作：HOLD / REDUCE / SELL */
        val action: String,
        /** 建議理由 */
        val reason: String,
        /** 相對大盤強弱（個股漲幅 - 大盤漲幅）*/
        val relativeStrength: Double,
        /** 板塊資金流向（萬元）*/
        val sectorFlow: Double,
        /** ATR 動態止損位 */
        val atrStopLoss: Double? = null,
        /** 機構共識評級 */
        val institutionalRating: String? = null,
        /** 機構目標價 */
        val ratingTargetPrice: Double? = null,
        /** 研報數量 */
        val ratingCount: Int = 0
    )

    // ════════════════════════════════════════════════════
    //  主入口
    // ════════════════════════════════════════════════════

    /**
     * 主入口方法 — 綜合分析大盤與持倉
     *
     * @param context ApplicationContext
     * @param holdingCodes 持倉股票代碼列表（如 ["sh600519", "sz000858"]）
     * @return MarketReport 完整分析報告
     */
    suspend fun analyze(context: Context, holdingCodes: List<String>): MarketReport =
        withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            Log.i(TAG, "========== 開始大盤綜合分析 ==========")
            Log.i(TAG, "持倉數量: ${holdingCodes.size}, 代碼: $holdingCodes")

            // 並行執行五大分析模組，任何一個失敗不阻塞其他
            val trendDeferred = async {
                try { analyzeTrend(context) } catch (e: Exception) {
                    Log.w(TAG, "趨勢分析失敗: ${e.message}")
                    null
                }
            }
            val sellTypeDeferred = async {
                try { analyzeSellType(context) } catch (e: Exception) {
                    Log.w(TAG, "賣出類型分析失敗: ${e.message}")
                    null
                }
            }
            val sectorDeferred = async {
                try { analyzeSectorAdvice(context) } catch (e: Exception) {
                    Log.w(TAG, "板塊分析失敗: ${e.message}")
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
                    Log.w(TAG, "持倉分析失敗: ${e.message}")
                    holdingCodes.map { code ->
                        HoldingAdvice(
                            stockCode = code, stockName = "", action = "HOLD",
                            reason = "分析失敗，建議觀察", relativeStrength = 0.0,
                            sectorFlow = 0.0, atrStopLoss = null
                        )
                    }
                }
            }

            // 取得各模組結果（失敗的模組返回預設值）
            val trend = trendDeferred.await() ?: TrendAnalysis(
                direction = "OSCILLATION", strength = 50,
                description = "趨勢分析失敗，預設為震盪"
            )

            val sellType = sellTypeDeferred.await() ?: SellTypeAnalysis(
                sellType = "NONE", confidence = 0, mainNetInflow = 0.0,
                volumeRatio = 0.0, description = "賣出類型分析失敗，預設為正常"
            )

            val sectorAdvice = sectorDeferred.await() ?: SectorAdvice(
                marketStyle = "數據不足", rotationSpeed = 0.0,
                recommendedSectors = emptyList(), defensivePct = 0.0
            )

            val holdings = holdingsDeferred.await()

            // 生成文字摘要
            val summary = buildSummary(trend, sellType, sectorAdvice, holdings)

            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "========== 大盤綜合分析完成，耗時 ${elapsed}ms ==========")
            Log.i(TAG, "摘要: $summary")

            MarketReport(
                trend = trend,
                sellType = sellType,
                sectorAdvice = sectorAdvice,
                holdings = holdings,
                summary = summary,
                timestamp = System.currentTimeMillis()
            )
        }

    // ════════════════════════════════════════════════════
    //  模組 1：大盤趨勢判斷
    // ════════════════════════════════════════════════════

    /**
     * 分析大盤趨勢
     * - 讀取上證指數(sh000001)近30天K線
     * - 計算 MA5/MA10/MA20 多空排列
     * - 計算 ADX(14) 區分趨勢/震盪
     *
     * @return TrendAnalysis 趨勢分析結果
     */
    private suspend fun analyzeTrend(context: Context): TrendAnalysis {
        Log.i(TAG, "[趨勢] 開始分析上證指數 $INDEX_CODE")

        val db = StockDatabase.getInstance(context)
        val snaps = db.dailySnapshotDao().getByCode(INDEX_CODE, 30)
            .sortedBy { it.date }

        if (snaps.size < 20) {
            Log.w(TAG, "[趨勢] 上證指數本地數據不足: ${snaps.size} 條，嘗試從東方財富 API 實時拉取...")
            val fetched = fetchIndexKlineFromApi(context)
            if (fetched != null && fetched.size >= 20) {
                Log.i(TAG, "[趨勢] API 拉取成功: ${fetched.size} 條，寫入本地數據庫")
                // 將拉取的數據批量寫入本地數據庫供後續使用
                try { db.dailySnapshotDao().insertAll(fetched) } catch (_: Exception) { }
                return analyzeTrend(context) // 用新數據重新分析
            }
            Log.w(TAG, "[趨勢] API 拉取失敗或數據仍不足，使用默認震蕩")
            return TrendAnalysis(
                direction = "OSCILLATION",
                strength = 50,
                description = "數據不足，無法判斷趨勢（需要至少 20 個交易日數據）"
            )
        }

        val closes = snaps.map { it.close }

        // 計算 MA5/MA10/MA20
        val ma5 = closes.takeLast(5).average()
        val ma10 = closes.takeLast(10).average()
        val ma20 = closes.takeLast(20).average()

        Log.i(TAG, "[趨勢] MA5=$ma5, MA10=$ma10, MA20=$ma20")

        // 判斷多空排列
        val isBullishAlign = ma5 > ma10 && ma10 > ma20  // 多頭排列
        val isBearishAlign = ma5 < ma10 && ma10 < ma20  // 空頭排列

        // 計算 ADX(14)
        val adx = computeADX(snaps)
        Log.i(TAG, "[趨勢] ADX(14)=$adx")

        // 趨勢方向判定
        val direction = when {
            isBullishAlign && adx > ADX_TREND_THRESHOLD -> "BULLISH"
            isBearishAlign && adx > ADX_TREND_THRESHOLD -> "BEARISH"
            isBullishAlign -> "BULLISH"
            isBearishAlign -> "BEARISH"
            else -> "OSCILLATION"
        }

        // 趨勢強度計算（0-100）
        val strength = when (direction) {
            "BULLISH" -> {
                // 多頭排列越完美 + ADX 越高 = 強度越高
                val alignScore = if (isBullishAlign) 40 else 0
                val adxScore = minOf(adx / 50.0 * 40, 40.0)
                // 近 5 日漲幅貢獻
                val recentReturn = (closes.last() - closes[closes.size - 6]) / closes[closes.size - 6] * 100
                val returnScore = minOf(maxOf(recentReturn / 5.0 * 20, 0.0), 20.0)
                (alignScore + adxScore + returnScore).toInt().coerceIn(0, 100)
            }
            "BEARISH" -> {
                val alignScore = if (isBearishAlign) 40 else 0
                val adxScore = minOf(adx / 50.0 * 40, 40.0)
                val recentReturn = (closes.last() - closes[closes.size - 6]) / closes[closes.size - 6] * 100
                // 跌幅越大，空頭強度越高
                val returnScore = minOf(maxOf(-recentReturn / 5.0 * 20, 0.0), 20.0)
                (alignScore + adxScore + returnScore).toInt().coerceIn(0, 100)
            }
            else -> {
                // 震盪：ADX 越低 = 震盪越明確，強度中等
                (50 + (30 - adx) * 0.5).toInt().coerceIn(20, 80)
            }
        }

        val description = buildTrendDescription(direction, strength, isBullishAlign, isBearishAlign, adx)
        Log.i(TAG, "[趨勢] 方向=$direction, 強度=$strength, 描述=$description")

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
     * 計算 ADX(Average Directional Index, 14 週期)
     * ADX > 25 表示趨勢明確，< 20 表示震盪
     */
    private fun computeADX(snaps: List<DailySnapshotEntity>): Double {
        if (snaps.size < 28) return 20.0  // ADX(14) 至少需要 28 根 K 線

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

        // 計算平滑 TR、+DM、-DM（Wilder 平滑）
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

        // 計算 +DI / -DI / DX / ADX
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
     * 構建趨勢描述文字
     */
    private fun buildTrendDescription(
        direction: String,
        strength: Int,
        isBullishAlign: Boolean,
        isBearishAlign: Boolean,
        adx: Double
    ): String {
        val alignDesc = when {
            isBullishAlign -> "均線多頭排列（MA5>MA10>MA20）"
            isBearishAlign -> "均線空頭排列（MA5<MA10<MA20）"
            else -> "均線糾纏，未形成明確排列"
        }
        val adxDesc = when {
            adx > 40 -> "趨勢非常強烈"
            adx > 25 -> "趨勢明確"
            adx > 20 -> "趨勢一般"
            else -> "趨勢不明顯，震盪格局"
        }
        val dirDesc = when (direction) {
            "BULLISH" -> "看多"
            "BEARISH" -> "看空"
            else -> "震盪觀望"
        }
        return "$dirDesc（強度 $strength/100），$alignDesc，$adxDesc（ADX=${"%.1f".format(adx)}）"
    }

    // ════════════════════════════════════════════════════
    //  模組 2：主力撤資 vs 量化砸盤識別
    // ════════════════════════════════════════════════════

    /**
     * 從東方財富 API 實時拉取上證指數 K 線數據作為 fallback
     * 當本地 daily_snapshot 中 sh000001 數據不足 20 條時調用
     */
    private suspend fun fetchIndexKlineFromApi(context: Context): List<DailySnapshotEntity>? {
        return try {
            withContext(Dispatchers.IO) {
                val endDate = LocalDate.now().format(DATE_FMT)
                val startDate = LocalDate.now().minusDays(45).format(DATE_FMT)
                // 上證指數 secid: 1.000001
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
                        date = date, code = INDEX_CODE, name = "上證指數",
                        open = open, close = close, high = high, low = low,
                        volume = volume, amount = 0.0, changePct = changePct
                    ))
                }
                result.sortedByDescending { it.date }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[趨勢] API 拉取上證指數失敗: ${e.message}")
            null
        }
    }

    /**
     * ## 主力/量化賣出類型識別
     *
     * 規則：
     * - mainNetInflow < threshold 且 isContinuousInflow == false → 主力撤資
     * - MFI < 30 且 CMF < 0 且成交量異常（量/MA20量 > 2）→ 量化砸盤
     * - 其他 → 正常賣出
     */
    private suspend fun analyzeSellType(context: Context): SellTypeAnalysis {
        Log.i(TAG, "[賣出類型] 開始分析 $INDEX_CODE")

        // 1. 獲取大盤資金流向
        val factorProvider = FactorDataProvider()
        val capitalFlow = try {
            factorProvider.getCapitalFlow(INDEX_CODE)
        } catch (e: Exception) {
            Log.w(TAG, "[賣出類型] 資金流向獲取失敗: ${e.message}")
            FactorDataProvider.CapitalFlowResult()
        }

        Log.i(TAG, "[賣出類型] 主力淨流入=${capitalFlow.mainNetInflow}萬, 連續流入=${capitalFlow.isContinuousInflow}")

        // 2. 計算 MFI / CMF / A/D 因子
        val db = StockDatabase.getInstance(context)
        val snaps = db.dailySnapshotDao().getByCode(INDEX_CODE, 25)
            .sortedBy { it.date }

        val mfiResult = SmartMoneyCache.computeMFI(snaps, 14)
        val cmfResult = SmartMoneyCache.computeCMF(snaps, 20)

        Log.i(TAG, "[賣出類型] MFI=${mfiResult.value}, CMF=${cmfResult.value}")

        // 3. 計算量比（今日成交量 / MA20成交量）
        val volumeRatio = computeVolumeRatio(snaps)
        Log.i(TAG, "[賣出類型] 量比=$volumeRatio")

        // 4. 判斷賣出類型
        val (sellType, confidence, description) = determineSellType(
            capitalFlow, mfiResult.value, cmfResult.value, volumeRatio
        )

        Log.i(TAG, "[賣出類型] 類型=$sellType, 置信度=$confidence, 描述=$description")

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
     * 計算量比：今日成交量 / MA20 成交量
     */
    private fun computeVolumeRatio(snaps: List<DailySnapshotEntity>): Double {
        if (snaps.size < 21) return 0.0

        val todayVolume = snaps.last().volume.toDouble()
        val ma20Volume = snaps.takeLast(21).dropLast(1).map { it.volume }.average()

        return if (ma20Volume > 0) todayVolume / ma20Volume else 0.0
    }

    /**
     * 判斷賣出類型
     */
    private fun determineSellType(
        capitalFlow: FactorDataProvider.CapitalFlowResult,
        mfi: Double,
        cmf: Double,
        volumeRatio: Double
    ): Triple<String, Int, String> {
        // 優先判斷量化砸盤（條件最嚴格，需要三個因子同時滿足）
        val isQuantCrash = mfi < QUANT_CRASH_MFI_THRESHOLD
            && cmf < QUANT_CRASH_CMF_THRESHOLD
            && volumeRatio > VOLUME_SPIKE_THRESHOLD

        // 主力撤資判斷
        val isInstitutionalExit = capitalFlow.mainNetInflow < MAIN_NET_EXIT_THRESHOLD
            && !capitalFlow.isContinuousInflow

        return when {
            // 同時出現主力撤資 + 量化砸盤：雙重危險
            isInstitutionalExit && isQuantCrash -> Triple(
                "INSTITUTIONAL_EXIT",
                95,
                "主力持續撤資（淨流入${"%.0f".format(capitalFlow.mainNetInflow)}萬元）且出現量化砸盤特徵" +
                    "（MFI=${"%.1f".format(mfi)}，CMF=${"%.3f".format(cmf)}，量比=${"%.1f".format(volumeRatio)}），" +
                    "建議立即減倉防禦"
            )

            // 主力撤資
            isInstitutionalExit -> {
                val severity = abs(capitalFlow.mainNetInflow)
                val conf = when {
                    severity > 1000000 -> 90  // 超 100 億
                    severity > 500000 -> 80   // 超 50 億
                    else -> 70
                }
                Triple(
                    "INSTITUTIONAL_EXIT",
                    conf,
                    "主力明顯撤資（淨流入${"%.0f".format(capitalFlow.mainNetInflow)}萬元，" +
                        "3日=${"%.0f".format(capitalFlow.inflow3Day)}萬，5日=${"%.0f".format(capitalFlow.inflow5Day)}萬），" +
                        "建議減倉，避開主動賣壓"
                )
            }

            // 量化砸盤
            isQuantCrash -> Triple(
                "QUANT_CRASH",
                75,
                "偵測到量化砸盤特徵（MFI=${"%.1f".format(mfi)}低位，CMF=${"%.3f".format(cmf)}為負，" +
                    "量比=${"%.1f".format(volumeRatio)}倍異常放量），" +
                    "短期回避，不恐慌拋售，等待量能回落"
            )

            // 正常賣出（有輕微資金流出但不嚴重）
            capitalFlow.mainNetInflow < -50000 -> Triple(
                "NORMAL_SELLING",
                40,
                "市場正常獲利了結（淨流入${"%.0f".format(capitalFlow.mainNetInflow)}萬元），" +
                    "暫不構成系統性風險"
            )

            // 無明顯賣出
            else -> Triple(
                "NONE",
                10,
                "未偵測到異常賣出行為，市場資金面正常"
            )
        }
    }

    // ════════════════════════════════════════════════════
    //  模組 3：板塊輪動推薦
    // ════════════════════════════════════════════════════

    /**
     * 分析板塊輪動情況
     * - 使用 SectorRotationEngine 預測明日熱門板塊
     * - 使用 SectorRotationEngine 判斷市場風格
     * - 如果大盤下行，優先推薦防禦板塊
     */
    private suspend fun analyzeSectorAdvice(context: Context): SectorAdvice {
        Log.i(TAG, "[板塊] 開始分析板塊輪動")

        val engine = SectorRotationEngine(context)

        // 1. 預測明日熱門板塊
        val predictions = try {
            engine.predictTomorrow(5)
        } catch (e: Exception) {
            Log.w(TAG, "[板塊] 預測失敗: ${e.message}")
            emptyList()
        }

        // 2. 市場風格診斷
        val marketStyle = try {
            engine.marketStyleDiagnosis()
        } catch (e: Exception) {
            Log.w(TAG, "[板塊] 風格診斷失敗: ${e.message}")
            "數據不足，無法診斷"
        }

        // 3. 輪動速度
        val rotationSpeed = try {
            engine.rotationSpeed()
        } catch (e: Exception) {
            Log.w(TAG, "[板塊] 輪動速度獲取失敗: ${e.message}")
            0.0
        }

        Log.i(TAG, "[板塊] 風格=$marketStyle, 輪動速度=$rotationSpeed, 預測板塊數=${predictions.size}")

        // 4. 構建推薦板塊列表
        val recommendations = mutableListOf<SectorRecommendation>()

        for (pred in predictions) {
            recommendations.add(
                SectorRecommendation(
                    sectorName = pred.sectorName,
                    confidence = pred.confidence,
                    reason = "動量=${"%.2f".format(pred.momentum)}，資金=${"%.2f".format(pred.capitalScore)}，" +
                        "置信度=${"%.0f".format(pred.confidence * 100)}%"
                )
            )
        }

        // 5. 如果預測列表中沒有防禦板塊，補充推薦
        val hasDefensive = recommendations.any { rec ->
            DEFENSIVE_SECTOR_KEYWORDS.any { rec.sectorName.contains(it) }
        }

        // 判斷是否需要推薦防禦板塊（根據預測和風格）
        val needsDefensive = predictions.isEmpty() || rotationSpeed > 5.0 ||
            marketStyle.contains("快速輪動") || marketStyle.contains("數據不足")

        if (!hasDefensive && needsDefensive) {
            Log.i(TAG, "[板塊] 補充防禦板塊推薦")
            for (keyword in DEFENSIVE_SECTOR_KEYWORDS.take(3)) {
                recommendations.add(
                    SectorRecommendation(
                        sectorName = keyword,
                        confidence = 0.3,
                        reason = "防禦性板塊，市場不確定時的避風港"
                    )
                )
            }
        }

        // 6. 計算防禦板塊佔比
        val defensiveCount = recommendations.count { rec ->
            DEFENSIVE_SECTOR_KEYWORDS.any { rec.sectorName.contains(it) }
        }
        val defensivePct = if (recommendations.isNotEmpty()) {
            defensiveCount.toDouble() / recommendations.size
        } else 0.0

        Log.i(TAG, "[板塊] 推薦 ${recommendations.size} 個板塊，防禦佔比=${"%.0f".format(defensivePct * 100)}%")

        return SectorAdvice(
            marketStyle = marketStyle,
            rotationSpeed = rotationSpeed,
            recommendedSectors = recommendations,
            defensivePct = defensivePct
        )
    }

    // ════════════════════════════════════════════════════
    //  模組 4 & 5：持倉股票風險評估 + 賣出建議
    // ════════════════════════════════════════════════════

    /**
     * 分析持倉股票，給出 HOLD / REDUCE / SELL 建議
     *
     * 綜合考量：
     * - 大盤趨勢方向
     * - 主力/量化賣出類型
     * - 個股相對大盤強弱
     * - 板塊資金流向
     * - ATR 動態止損位
     */
    private suspend fun analyzeHoldings(
        context: Context,
        holdingCodes: List<String>,
        trend: TrendAnalysis,
        sellType: SellTypeAnalysis
    ): List<HoldingAdvice> {
        if (holdingCodes.isEmpty()) {
            Log.i(TAG, "[持倉] 無持倉，跳過分析")
            return emptyList()
        }

        Log.i(TAG, "[持倉] 開始分析 ${holdingCodes.size} 只持倉股票")

        val db = StockDatabase.getInstance(context)
        val factorProvider = FactorDataProvider()
        val ratingProvider = InstitutionalRatingProvider()

        // 獲取大盤近 5 日漲跌幅（用於計算相對強弱）
        val indexSnaps = db.dailySnapshotDao().getByCode(INDEX_CODE, 6).sortedBy { it.date }
        val indexChangePct5d = if (indexSnaps.size >= 6) {
            (indexSnaps.last().close - indexSnaps[indexSnaps.size - 6].close) / indexSnaps[indexSnaps.size - 6].close * 100
        } else 0.0

        // 獲取持倉股票的 ATR（通過 ZiplinePipeline）
        val pipeline = ZiplinePipeline(context)
        val today = LocalDate.now().format(DATE_FMT)

        // 構造 StockRealtime 列表用於 ZiplinePipeline
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
                Log.w(TAG, "[持倉] 獲取 $code 數據失敗: ${e.message}")
                stockNameMap[code] = ""
            }
        }

        // 計算 ATR 因子
        val factors = try {
            pipeline.computeAll(holdingStocks, today, 30)
        } catch (e: Exception) {
            Log.w(TAG, "[持倉] ATR 計算失敗: ${e.message}")
            ZiplinePipeline.FactorSet()
        }

        // 逐一分析每個持倉
        val advices = mutableListOf<HoldingAdvice>()
        var batchDelay = 0L

        for (code in holdingCodes) {
            try {
                // 獲取個股近 5 日漲跌幅
                val stockSnaps = db.dailySnapshotDao().getByCode(code, 6).sortedBy { it.date }
                val stockChangePct5d = if (stockSnaps.size >= 6) {
                    (stockSnaps.last().close - stockSnaps[stockSnaps.size - 6].close) / stockSnaps[stockSnaps.size - 6].close * 100
                } else 0.0

                // 相對大盤強弱
                val relativeStrength = stockChangePct5d - indexChangePct5d

                // 板塊資金流向（個股資金流向近似）
                if (batchDelay > 0) delay(batchDelay)
                val flowResult = factorProvider.getCapitalFlow(code)
                batchDelay = 200L  // 避免頻率限制

                // ATR 止損位
                val atr = factors.atr14[code]
                val currentPrice = stockSnaps.lastOrNull()?.close ?: 0.0
                val atrStopLoss = if (atr != null && atr > 0 && currentPrice > 0) {
                    currentPrice - atr * 1.5  // 1.5 倍 ATR 止損
                } else null

                // 機構評級
                val ratingSummary = try {
                    ratingProvider.getRatingSummary(code, days = 90)
                } catch (_: Exception) {
                    null
                }

                // 綜合判斷操作建議
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
                Log.i(TAG, "[持倉] $code ${stockNameMap[code]}: $action, 相對強弱=${"%.2f".format(relativeStrength)}%, " +
                    "板塊資金=${"%.0f".format(flowResult.mainNetInflow)}萬, ATR止損=${if (atrStopLoss != null) "%.2f".format(atrStopLoss) else "N/A"}")

            } catch (e: Exception) {
                Log.w(TAG, "[持倉] 分析 $code 失敗: ${e.message}")
                advices.add(
                    HoldingAdvice(
                        stockCode = code,
                        stockName = stockNameMap[code] ?: "",
                        action = "HOLD",
                        reason = "分析過程出錯，建議觀察",
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
     * 綜合判斷持倉操作建議
     *
     * 規則優先級（由高到低）：
     * 1. 量化砸盤 → 短期回避，不恐慌拋售 → HOLD（附帶警告）
     * 2. 主力撤資 + 個股弱於大盤 → SELL
     * 3. 主力撤資 + 個股強於大盤 → REDUCE
     * 4. 大盤 BEARISH + 個股弱於大盤 → SELL
     * 5. 大盤 BEARISH + 個股強於大盤 + 板塊有資金流入 → HOLD
     * 6. ATR 止損被觸發 → SELL
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

        // 量化砸盤：短期回避，不恐慌拋售
        if (type == "QUANT_CRASH") {
            return "HOLD" to "量化砸盤期間，不建議恐慌拋售。觀察量能回落後再決策，" +
                "嚴格執行 ATR 止損"
        }

        // 主力撤資 + 個股弱於大盤 → 建議賣出
        if (type == "INSTITUTIONAL_EXIT" && relativeStrength < -1.0) {
            return "SELL" to "主力撤資環境下，該股相對大盤弱（落後${"%.1f".format(abs(relativeStrength))}%），" +
                "建議減倉或清倉"
        }

        // 主力撤資 + 個股強於大盤 → 建議減倉
        if (type == "INSTITUTIONAL_EXIT") {
            return "REDUCE" to "主力撤資環境下，雖然該股相對大盤偏強（領先${"%.1f".format(relativeStrength)}%），" +
                "但仍建議適度減倉，降低風險暴露"
        }

        // 大盤下行 + 個股弱於大盤 → 建議賣出
        if (dir == "BEARISH" && relativeStrength < -2.0 && trend.strength > 60) {
            return "SELL" to "大盤明確下行（強度${trend.strength}），個股相對大盤弱（落後${"%.1f".format(abs(relativeStrength))}%），" +
                "建議止損離場"
        }

        // 大盤下行 + 個股強於大盤 + 板塊有資金流入 → 持有觀察
        if (dir == "BEARISH" && relativeStrength > 1.0 && sectorFlow > 0 && isContinuousInflow) {
            return "HOLD" to "大盤雖然下行，但該股相對偏強（領先${"%.1f".format(relativeStrength)}%），" +
                "且板塊有資金持續流入（${"%.0f".format(sectorFlow)}萬元），持有觀察"
        }

        // 大盤下行 + 個股強於大盤但板塊無資金流入 → 謹慎減倉
        if (dir == "BEARISH" && relativeStrength > 0) {
            return "REDUCE" to "大盤下行中，該股雖然相對偏強，但板塊資金未見持續流入，" +
                "建議適度減倉防禦"
        }

        // ATR 止損位檢查
        if (atr != null && atr > 0 && currentPrice > 0) {
            // 這裡不直接判斷是否觸發止損，因為需要知道成本價
            // 僅在理由中提示止損位
            val stopLossPrice = currentPrice - atr * 1.5
            if (dir == "BEARISH") {
                return "HOLD" to "大盤偏弱，建議設置 ATR 止損位 ${"%.2f".format(stopLossPrice)}，" +
                    "跌破即止損"
            }
        }

        // 機構評級為賣出/減持 → 建議減倉或賣出
        if (institutionalRating == "賣出" || institutionalRating == "減持") {
            return "SELL" to "機構共識評級為${institutionalRating}，建議減倉或清倉"
        }

        // 機構評級為中性 → 謹慎持有
        if (institutionalRating == "中性") {
            return "HOLD" to "機構共識評級為中性，暫時觀望"
        }

        // 大盤看多 + 個股偏強 → 持有
        if (dir == "BULLISH" && relativeStrength > 0) {
            return "HOLD" to "大盤看多（強度${trend.strength}），個股相對偏強，建議持有"
        }

        // 默認持有觀察
        return "HOLD" to "當前無明確賣出信號，建議繼續持有觀察"
    }

    // ════════════════════════════════════════════════════
    //  文字摘要生成
    // ════════════════════════════════════════════════════

    /**
     * 構建可展示在 UI 的文字摘要
     */
    private fun buildSummary(
        trend: TrendAnalysis,
        sellType: SellTypeAnalysis,
        sectorAdvice: SectorAdvice,
        holdings: List<HoldingAdvice>
    ): String {
        val sb = StringBuilder()

        // 趨勢摘要
        val trendEmoji = when (trend.direction) {
            "BULLISH" -> "[多]"
            "BEARISH" -> "[空]"
            else -> "[震]"
        }
        sb.append("$trendEmoji 大盤趨勢: ${trend.direction}(強度${trend.strength})。")
        sb.append(trend.description).append("。\n")

        // 賣出類型摘要
        when (sellType.sellType) {
            "INSTITUTIONAL_EXIT" -> sb.append("!! 主力撤資警報(置信度${sellType.confidence}%)。")
                .append(sellType.description).append("。\n")
            "QUANT_CRASH" -> sb.append("! 量化砸盤偵測(置信度${sellType.confidence}%)。")
                .append(sellType.description).append("。\n")
            "NORMAL_SELLING" -> sb.append("正常賣出。").append(sellType.description).append("。\n")
            else -> {} // NONE 不顯示
        }

        // 板塊摘要
        if (sectorAdvice.recommendedSectors.isNotEmpty()) {
            sb.append("板塊: ${sectorAdvice.marketStyle}。")
            sb.append("推薦關注: ")
            sb.append(sectorAdvice.recommendedSectors.take(3).joinToString("、") { it.sectorName })
            sb.append("。\n")
        }

        // 持倉操作摘要
        if (holdings.isNotEmpty()) {
            val sellCount = holdings.count { it.action == "SELL" }
            val reduceCount = holdings.count { it.action == "REDUCE" }
            val holdCount = holdings.count { it.action == "HOLD" }

            sb.append("持倉建議: ")
            when {
                sellCount > 0 -> sb.append("$sellCount 只建議賣出")
                reduceCount > 0 -> sb.append("$reduceCount 只建議減倉")
                else -> sb.append("全部持有觀察")
            }
            if (holdCount > 0 && (sellCount > 0 || reduceCount > 0)) {
                sb.append("，$holdCount 只持有")
            }
            sb.append("。")

            // 列出需要操作的股票
            val actionStocks = holdings.filter { it.action in listOf("SELL", "REDUCE") }
            if (actionStocks.isNotEmpty()) {
                sb.append(" 具體: ")
                sb.append(actionStocks.joinToString("；") {
                    "${it.stockName ?: it.stockCode}→${it.action}"
                })
            }
            sb.append("。")

            // 機構評級摘要
            val ratedHoldings = holdings.filter { !it.institutionalRating.isNullOrBlank() && it.institutionalRating != "無數據" }
            if (ratedHoldings.isNotEmpty()) {
                sb.append("\n機構評級: ")
                sb.append(ratedHoldings.take(3).joinToString("；") {
                    val target = if (it.ratingTargetPrice != null) "目標價${"%.1f".format(it.ratingTargetPrice)}" else ""
                    "${it.stockName ?: it.stockCode}=${it.institutionalRating}(${it.ratingCount}份)$target"
                })
                sb.append("。")
            }
        }

        return sb.toString()
    }
}
