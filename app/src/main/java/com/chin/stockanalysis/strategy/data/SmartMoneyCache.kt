package com.chin.stockanalysis.strategy.data

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.abs

/**
 * ## 主力資金行為緩存
 *
 * 基於 MFI / CMF / A/D / 主力淨流入趨勢 四個因子，
 * 計算每隻股票的主力行為綜合評分（0~100）。
 *
 * ### 數據源
 * - MFI / CMF / A/D：基於 DailySnapshotEntity（HIGH/LOW/CLOSE/VOL），純本地計算
 * - 主力淨流入趨勢：FactorDataProvider.getCapitalFlow()（東方財富 f62/f184/f66/f69）
 *
 * ### 評分公式
 * ```
 * combined = MFI(14日) × 30% + CMF(20日) × 25% + A/D背離信號 × 25% + 主力淨流入趨勢 × 20%
 * ```
 *
 * ### 使用方式
 * ```kotlin
 * // 每日刷新一次（盤前）
 * SmartMoneyCache.refresh(context, codes)
 *
 * // 策略篩選時 O(1) 讀取
 * val score = SmartMoneyCache.getScore(code)
 * ```
 */
object SmartMoneyCache {

    private const val TAG = "SmartMoneyCache"

    /** 緩存的評分結果 */
    data class SmartMoneyScore(
        val mfiScore: Double = 50.0,      // MFI 因子分數 0~100
        val cmfScore: Double = 50.0,      // CMF 因子分數 0~100
        val adScore: Double = 50.0,       // A/D 背離信號分數 0~100
        val flowScore: Double = 50.0,     // 主力淨流入趨勢分數 0~100
        val combined: Double = 50.0       // 加權總分 0~100
    )

    /** MFI 計算結果（原始值 + 標準化分數） */
    data class MFIResult(
        val value: Double,               // MFI 原始值 0~100
        val score: Double                // 標準化到 0~100
    )

    /** CMF 計算結果 */
    data class CMFResult(
        val value: Double,               // CMF 原始值 -0.5~0.5
        val score: Double                // 標準化到 0~100
    )

    /** A/D 計算結果 */
    data class ADResult(
        val currentAD: Double,           // 當前 A/D 值
        val adTrend: Double,             // 近 5 日 A/D 變化率
        val priceTrend: Double,          // 近 5 日價格變化率
        val score: Double                // 標準化到 0~100
    )

    private val cache = mutableMapOf<String, SmartMoneyScore>()
    private var lastRefreshDate: String? = null

    // ════════════════════════════════════════════
    //  公共 API
    // ════════════════════════════════════════════

    /** 獲取評分（無緩存時返回中性分數 50） */
    fun getScore(code: String): SmartMoneyScore = cache[code] ?: SmartMoneyScore()

    /** 是否已刷新（當日） */
    fun isFresh(): Boolean = lastRefreshDate == LocalDate.now().toString()

    /**
     * 刷新緩存（每日調用一次）
     * @param context ApplicationContext
     * @param codes 需要計算的股票代碼列表
     */
    suspend fun refresh(context: Context, codes: List<String>) = withContext(Dispatchers.IO) {
        val today = LocalDate.now().toString()
        if (lastRefreshDate == today && cache.isNotEmpty()) {
            Log.i(TAG, "今日已刷新，跳過 (${codes.size} 只)")
            return@withContext
        }

        val db = StockDatabase.getInstance(context)
        val factorProvider = FactorDataProvider()
        val startMs = System.currentTimeMillis()

        // 批量獲取日線數據（近 25 個交易日，足夠計算 MFI14/CMF20 + 背離判斷）
        val snapsMap = mutableMapOf<String, List<DailySnapshotEntity>>()
        for (code in codes) {
            try {
                val snaps = db.dailySnapshotDao().getByCode(code, 25)
                if (snaps.size >= 5) snapsMap[code] = snaps.sortedByDescending { it.date }
            } catch (_: Exception) {}
        }

        // 批量獲取主力淨流入（FactorDataProvider）
        val flowMap = mutableMapOf<String, FactorDataProvider.CapitalFlowResult>()
        // 每批 20 只，避免請求過快
        val batches = codes.chunked(20)
        for (batch in batches) {
            for (code in batch) {
                try {
                    val flow = factorProvider.getCapitalFlow(code)
                    if (flow.mainNetInflow != 0.0 || flow.inflow3Day != 0.0) {
                        flowMap[code] = flow
                    }
                } catch (_: Exception) {}
            }
            if (batches.indexOf(batch) < batches.size - 1) {
                kotlinx.coroutines.delay(200) // 避免頻率限制
            }
        }

        // 計算每隻股票的評分
        cache.clear()
        for (code in codes) {
            val snaps = snapsMap[code]
            if (snaps == null || snaps.size < 5) {
                cache[code] = SmartMoneyScore() // 數據不足，返回中性分數
                continue
            }

            // 按日期升序排列（舊→新）
            val sorted = snaps.sortedBy { it.date }

            val mfi = computeMFI(sorted, 14)
            val cmf = computeCMF(sorted, 20)
            val ad = computeADSignal(sorted)
            val flow = flowMap[code]

            val mfiS = mfi.score
            val cmfS = cmf.score
            val adS = ad.score
            val flowS = computeFlowTrendScore(flow)

            val combined = (mfiS * 0.30 + cmfS * 0.25 + adS * 0.25 + flowS * 0.20)
                .coerceIn(0.0, 100.0)

            cache[code] = SmartMoneyScore(
                mfiScore = mfiS,
                cmfScore = cmfS,
                adScore = adS,
                flowScore = flowS,
                combined = combined
            )
        }

        lastRefreshDate = today
        val elapsed = System.currentTimeMillis() - startMs
        Log.i(TAG, "刷新完成: ${cache.size}/${codes.size} 只, 耗時 ${elapsed}ms")
    }

    // ════════════════════════════════════════════
    //  MFI（Money Flow Index）— 資金流量指數
    // ════════════════════════════════════════════

    /**
     * 計算 MFI(N)
     * TP = (HIGH + LOW + CLOSE) / 3
     * MF = TP × VOL
     * 如果 TP > 昨日 TP → 正資金流
     * MFI = 100 - 100 / (1 + MFR)
     */
    fun computeMFI(snaps: List<DailySnapshotEntity>, period: Int): MFIResult {
        if (snaps.size < period + 1) return MFIResult(50.0, 50.0)

        // 取最近 period+1 個交易日（需要前一天 TP 做比較）
        val recent = snaps.takeLast(period + 1)

        var positiveMF = 0.0
        var negativeMF = 0.0

        for (i in 1..period) {
            val today = recent[i]
            val yesterday = recent[i - 1]
            val tpToday = (today.high + today.low + today.close) / 3.0
            val mf = tpToday * today.volume
            val tpYesterday = (yesterday.high + yesterday.low + yesterday.close) / 3.0

            if (tpToday > tpYesterday) {
                positiveMF += mf
            } else if (tpToday < tpYesterday) {
                negativeMF += mf
            }
            // TP 相等時不計入正負
        }

        val mfr = if (negativeMF > 0) positiveMF / negativeMF else if (positiveMF > 0) 99.0 else 1.0
        val mfi = 100.0 - 100.0 / (1.0 + mfr)

        // 標準化到 0~100 評分
        val score = when {
            mfi > 80.0 -> 60.0   // 超買區，主力可能短期出貨
            mfi > 60.0 -> 100.0  // 資金持續流入，最佳區間
            mfi > 40.0 -> 50.0   // 中性
            mfi > 20.0 -> 20.0   // 資金持續流出
            else -> 0.0          // 超賣，可能觸底反彈
        }

        return MFIResult(mfi, score)
    }

    // ════════════════════════════════════════════
    //  CMF（Chaikin Money Flow）— 蔡金資金流向
    // ════════════════════════════════════════════

    /**
     * 計算 CMF(N)
     * MFM = ((CLOSE - LOW) - (HIGH - CLOSE)) / (HIGH - LOW)  // -1 到 1
     * MFVOL = MFM × VOL
     * CMF = Σ(MFVOL, N) / Σ(VOL, N) × 100
     */
    fun computeCMF(snaps: List<DailySnapshotEntity>, period: Int): CMFResult {
        if (snaps.size < period) return CMFResult(0.0, 50.0)

        val recent = snaps.takeLast(period)
        var sumMFVOL = 0.0
        var sumVOL = 0.0

        for (snap in recent) {
            val range = snap.high - snap.low
            val mfm = if (range > 0) {
                ((snap.close - snap.low) - (snap.high - snap.close)) / range
            } else {
                0.0 // 漲停或跌停，range=0
            }
            sumMFVOL += mfm * snap.volume
            sumVOL += snap.volume
        }

        val cmf = if (sumVOL > 0) sumMFVOL / sumVOL * 100.0 else 0.0

        // 標準化到 0~100
        val score = when {
            cmf > 0.25 -> 100.0  // 強勢流入
            cmf > 0.0 -> 70.0    // 溫和流入
            cmf > -0.25 -> 30.0  // 溫和流出
            else -> 0.0          // 強勢流出
        }

        return CMFResult(cmf, score)
    }

    // ════════════════════════════════════════════
    //  A/D（Accumulation/Distribution）— 背離偵測
    // ════════════════════════════════════════════

    /**
     * 計算 A/D 線並偵測背離信號
     * MFM = (2×CLOSE - HIGH - LOW) / (HIGH - LOW)
     * AD_t = AD_{t-1} + MFM × VOL
     *
     * 背離判斷（取近 10 日）：
     * - 底背離：價格下跌但 A/D 上升 → 主力吸籌（買入信號）
     * - 頂背離：價格上漲但 A/D 下降 → 主力出貨（賣出信號）
     */
    fun computeADSignal(snaps: List<DailySnapshotEntity>): ADResult {
        if (snaps.size < 10) return ADResult(0.0, 0.0, 0.0, 50.0)

        // 計算完整 A/D 線
        val adValues = mutableListOf<Double>()
        var ad = 0.0
        for (snap in snaps) {
            val range = snap.high - snap.low
            val mfm = if (range > 0) {
                (2.0 * snap.close - snap.high - snap.low) / range
            } else {
                0.0
            }
            ad += mfm * snap.volume
            adValues.add(ad)
        }

        // 取近 10 日的 A/D 和價格趨勢
        val recentAD = adValues.takeLast(10)
        val recentPrices = snaps.takeLast(10).map { it.close }

        val adTrend = if (recentAD.size >= 2) {
            (recentAD.last() - recentAD.first()) / (abs(recentAD.first()) + 1.0) * 100.0
        } else 0.0

        val priceTrend = if (recentPrices.size >= 2) {
            (recentPrices.last() - recentPrices.first()) / (abs(recentPrices.first()) + 1.0) * 100.0
        } else 0.0

        val currentAD = recentAD.last()

        // 背離偵測
        val score = when {
            // 底背離：價格跌，A/D 漲 → 主力吸籌
            priceTrend < -3.0 && adTrend > 3.0 -> 100.0
            // 價格微跌，A/D 明顯上漲
            priceTrend < -1.0 && adTrend > 5.0 -> 90.0
            // A/D 持續上升（非背離，正常流入）
            adTrend > 3.0 -> 70.0
            adTrend > 0.0 -> 55.0
            // A/D 持續下降（正常流出）
            adTrend < -3.0 -> 30.0
            // 頂背離：價格漲，A/D 跌 → 主力出貨
            priceTrend > 3.0 && adTrend < -3.0 -> 10.0
            priceTrend > 1.0 && adTrend < -5.0 -> 20.0
            else -> 50.0
        }

        return ADResult(currentAD, adTrend, priceTrend, score)
    }

    // ════════════════════════════════════════════
    //  主力淨流入趨勢評分
    // ════════════════════════════════════════════

    /**
     * 基於 FactorDataProvider 的資金流向數據評分
     * - 3日主力淨流入（f184）× 40% 權重
     * - 5日主力淨流入（f66）× 35% 權重
     * - 10日主力淨流入（f69）× 25% 權重
     */
    fun computeFlowTrendScore(flow: FactorDataProvider.CapitalFlowResult?): Double {
        if (flow == null) return 50.0

        // 3日趨勢分數
        val score3 = when {
            flow.inflow3Day > 10000 -> 100.0  // 3日淨流入>1億
            flow.inflow3Day > 5000 -> 90.0
            flow.inflow3Day > 2000 -> 80.0
            flow.inflow3Day > 500 -> 65.0
            flow.inflow3Day > 0 -> 55.0
            flow.inflow3Day > -500 -> 40.0
            flow.inflow3Day > -2000 -> 25.0
            else -> 10.0
        }

        // 5日趨勢分數
        val score5 = when {
            flow.inflow5Day > 20000 -> 100.0  // 5日淨流入>2億
            flow.inflow5Day > 10000 -> 90.0
            flow.inflow5Day > 5000 -> 80.0
            flow.inflow5Day > 1000 -> 65.0
            flow.inflow5Day > 0 -> 55.0
            flow.inflow5Day > -1000 -> 40.0
            flow.inflow5Day > -5000 -> 25.0
            else -> 10.0
        }

        // 10日趨勢分數
        val score10 = when {
            flow.inflow10Day > 50000 -> 100.0 // 10日淨流入>5億
            flow.inflow10Day > 20000 -> 90.0
            flow.inflow10Day > 10000 -> 80.0
            flow.inflow10Day > 0 -> 60.0
            flow.inflow10Day > -10000 -> 35.0
            else -> 15.0
        }

        // 連續流入加分
        val continuousBonus = if (flow.isContinuousInflow) 10.0 else 0.0

        return (score3 * 0.40 + score5 * 0.35 + score10 * 0.25 + continuousBonus)
            .coerceIn(0.0, 100.0)
    }
}
