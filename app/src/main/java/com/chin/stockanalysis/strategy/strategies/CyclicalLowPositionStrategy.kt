package com.chin.stockanalysis.strategy.strategies

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.database.StockDataCenter
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.*
import com.chin.stockanalysis.strategy.data.StockScreener
import com.chin.stockanalysis.strategy.models.WeightFactor
import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.models.StrategySignal
import com.chin.stockanalysis.strategy.models.SignalAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ## 週期低位策略（長線左側佈局）
 *
 * 核心思想：週期性行業（鋰礦/有色/鋼鐵/煤炭/化工/航運/證券等）的商品價格與股價
 * 隨供需週期大幅波動。在「行業有真實需求、公司不會暴雷」的前提下，於商品與股價
 * 均處低位時左側建倉，等待週期反轉。對應用戶需求：
 * > 鋰礦有市場需求，但碳酸鋰與股價均處低位 → 可適當建倉。
 *
 * 四層篩選：
 * 1. **週期行業**（硬過濾）：所屬板塊命中週期性行業關鍵詞。
 * 2. **不會暴雷**（硬過濾）：非 ST/退市、正淨資產(PB>0)、負債率<85%、
 *    ROE 或經營現金流為正、市值>30億（排除微盤股）。
 * 3. **股價低位**（硬過濾）：現價位於 52 週區間下 30%（proxies 商品低位）。
 * 4. **綜合評分**：低位深度(35) + 穩健性(30) + 估值PB(20) + 市值安全(15)。
 *
 * 局限：無外部商品期貨價格數據，以「股價 52 週低位」近似「商品低位」；
 * 「市場需求」以行業本身為基礎需求代理，暫不做需求側量化。
 */
class CyclicalLowPositionStrategy(
    private val context: Context,
    private val screener: StockScreener
) : Strategy {

    override val id = "cyclical_low_position"
    override var name = "週期低位策略"
    override var description = "篩選行業週期底部、股價低位、基本面穩健的週期性行業龍頭，左側佈局等待週期反轉"
    override val category = StrategyCategory.VALUE
    override val holdingPeriods = listOf(HoldingPeriod.LONG)
    override val source = StrategySource.BUILTIN
    override val signalExpiryHours = 720

    // 長線左側：止损放寬、止盈放大，給週期反轉留足空間
    override val defaultStopLoss = -0.15f
    override val defaultTakeProfit = 0.50f

    override val config = StrategyConfig.custom(
        params = mapOf(
            "position_max" to 0.30,   // 52 週低位閾值（現價位於區間下 30%）
            "pb_max" to 3.0,          // PB 上限
            "debt_max" to 85.0,       // 資產負債率上限(%)
            "cap_min" to 3.0e9        // 最小市值（排除微盤股，防暴雷）
        ),
        maxResults = 10
    )

    override var weightFactors: List<WeightFactor> = listOf(
        WeightFactor("lowPosition", "低位深度", 35, "52週區間位置越低得分越高"),
        WeightFactor("safety", "穩健性", 30, "ROE/負債率/現金流，確保不暴雷"),
        WeightFactor("valuation", "估值", 20, "PB 越低得分越高"),
        WeightFactor("cap", "市值安全", 15, "市值越大越抗風險")
    )

    /** 週期性行業關鍵詞（按子行業分組，命中任一即視為週期股，可自行增刪） */
    private val CYCLICAL_KEYWORDS = listOf(
        // 金屬礦產（鋰/稀土/基本金屬等，對應用戶的鋰礦需求）
        "锂", "鈷", "鎳", "稀土", "有色", "銅", "鋁", "鋅", "黃金", "鋼鐵", "鐵礦",
        "鎢", "錫", "鉬", "銻", "鈦", "鎂", "貴金屬", "能源金屬", "小金屬",
        // 能源
        "煤炭", "焦炭", "石油", "石化", "油服", "燃氣", "油氣",
        // 化工
        "化工", "化纖", "化肥", "農藥", "純鹼", "氯鹼", "氟", "磷", "有機硅",
        "鈦白粉", "甲醇", "烯烴", "聚氨酯", "橡膠", "塑料", "化學原料", "化學製品",
        // 航運物流
        "航運", "船舶", "港口", "海運", "集運", "遠洋",
        // 金融（強週期）
        "證券", "保險", "信託",
        // 地產基建
        "房地產", "地產", "建材", "水泥", "玻璃", "玻纖", "工程機械", "重卡",
        // 農牧（豬週期/糖週期）
        "養殖", "豬", "禽", "雞", "種業", "糖", "棉花",
        // 汽車機械
        "汽車", "商用車", "機床",
        // 造紙
        "造紙", "紙漿",
        // 紡織（出口週期）
        "紡織", "服裝", "家紡",
        // 電子/電力設備週期（面板/存儲/光伏/鋰電材料）
        "面板", "存儲", "光伏", "硅", "太陽能", "鋰電池", "電池"
    )

    override suspend fun screen(): Result<ScreeningResult> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        return@withContext try {
            val pool = if (config.stockPool.isEmpty()) screener.scanFullMarket()
            else screener.scanSpecific(config.stockPool).values.toList()
            screenWithPool(pool, startTime)
        } catch (e: Exception) {
            Log.e(TAG, "週期低位策略掃描失敗", e)
            Result.failure(e)
        }
    }

    override suspend fun screenWithData(preloadedStocks: List<StockRealtime>): Result<ScreeningResult> {
        val startTime = System.currentTimeMillis()
        return try {
            val pool = if (config.stockPool.isNotEmpty())
                preloadedStocks.filter { it.code in config.stockPool } else preloadedStocks
            screenWithPool(pool, startTime)
        } catch (e: Exception) {
            Log.e(TAG, "週期低位策略掃描失敗", e)
            Result.failure(e)
        }
    }

    override suspend fun isAvailable(): Boolean = true

    private suspend fun screenWithPool(pool: List<StockRealtime>, startTime: Long): Result<ScreeningResult> {
        if (pool.isEmpty()) return Result.success(ScreeningResult(
            strategyId = id, strategyName = name, category = category,
            signals = emptyList(), totalScanned = 0, scanTimeMs = System.currentTimeMillis() - startTime
        ))

        val positionMax = (config.params["position_max"] as? Number)?.toDouble() ?: 0.30
        val pbMax = (config.params["pb_max"] as? Number)?.toDouble() ?: 3.0
        val debtMax = (config.params["debt_max"] as? Number)?.toDouble() ?: 85.0
        val capMin = (config.params["cap_min"] as? Number)?.toDouble() ?: 3.0e9

        // ── 1. 硬性淘汰（純內存，快速縮小池子）──
        val baseCandidates = pool.filter { stock ->
            // 防暴雷：剔除 ST / 退市風險
            !stock.name.contains("ST", ignoreCase = true) && !stock.name.contains("退") &&
            // 正淨資產（PB>0；PB=0 為無數據，為安全起見剔除）
            stock.pb > 0 && stock.pb <= pbMax &&
            // 負債率紅線（debtToAsset<=0 視為無數據放行，但有數據須 < debtMax）
            (stock.debtToAsset <= 0 || stock.debtToAsset < debtMax) &&
            // 基本面尚存：ROE 或經營現金流至少一項為正（週期底部允許利潤薄，但不能both為負）
            (stock.roeTTM > 0 || stock.operatingCashFlow > 0) &&
            // 市值下限（排除微盤股，抗暴雷）
            stock.marketCap >= capMin &&
            // 流動性保障
            stock.amount > 50_000_000 &&
            // 價格穩定（排除漲跌停）
            stock.changePercent in -9.0..9.0
        }
        Log.i(TAG, "pool=${pool.size} → 硬性過濾後=${baseCandidates.size}")

        // ── 2. 週期行業過濾（查板塊，命中關鍵詞才保留）──
        val cyclicalCandidates = mutableListOf<Pair<StockRealtime, String>>() // stock → 命中的板塊
        for (stock in baseCandidates) {
            val sectors = try { StockDataCenter.getSectorsByStock(stock.code) } catch (_: Exception) { emptyList() }
            val matched = matchCyclicalSector(sectors)
            if (matched != null) cyclicalCandidates.add(stock to matched)
        }
        Log.i(TAG, "硬性過濾後=${baseCandidates.size} → 週期行業命中=${cyclicalCandidates.size}")

        // ── 3. 股價低位過濾（查 52 週歷史，現價須位於區間低位）──
        val db = StockDatabase.getInstance(context)
        val lowPositionCandidates = mutableListOf<CyclicalCandidate>()
        for ((stock, sector) in cyclicalCandidates) {
            val history = try { db.dailySnapshotDao().getByCode(stock.code, 250) } catch (_: Exception) { emptyList() }
            if (history.size < 120) continue // 歷史不足，無法判斷 52 週低位
            val high52w = history.maxOf { it.high }
            val low52w = history.minOf { it.low }
            if (high52w <= low52w) continue // 區間異常
            val position = (stock.price - low52w) / (high52w - low52w)
            if (position <= positionMax) {
                lowPositionCandidates.add(CyclicalCandidate(stock, sector, position, high52w, low52w))
            }
        }
        Log.i(TAG, "週期行業=${cyclicalCandidates.size} → 52週低位(≤${positionMax})=${lowPositionCandidates.size}")

        // ── 4. 綜合評分 ──
        val signals = lowPositionCandidates.map { calculateSignal(it) }
            .filter { it.strength >= STRENGTH_THRESHOLD }
            .sortedByDescending { it.strength }
            .take(config.maxResults)

        Log.i(TAG, "評分後 strength>=$STRENGTH_THRESHOLD: ${signals.size}")
        return Result.success(ScreeningResult(
            strategyId = id, strategyName = name, category = category,
            signals = signals, totalScanned = pool.size, scanTimeMs = System.currentTimeMillis() - startTime
        ))
    }

    /** 返回命中的週期板塊名；無命中返回 null */
    private fun matchCyclicalSector(sectors: List<String>): String? {
        for (sector in sectors) {
            for (kw in CYCLICAL_KEYWORDS) {
                if (sector.contains(kw)) return sector
            }
        }
        return null
    }

    private fun calculateSignal(c: CyclicalCandidate): StrategySignal {
        val stock = c.stock

        // ── 低位深度 (0-35)：52 週位置越低，安全邊際與反轉空間越大 ──
        val lowPositionScore = when {
            c.position <= 0.05 -> 35
            c.position <= 0.10 -> 31
            c.position <= 0.15 -> 27
            c.position <= 0.20 -> 23
            c.position <= 0.25 -> 19
            c.position <= 0.30 -> 15
            else -> 8
        }

        // ── 穩健性 (0-30)：確保「不會暴雷」──
        val roeScore = when {
            stock.roeTTM >= 20 -> 12
            stock.roeTTM >= 15 -> 10
            stock.roeTTM >= 10 -> 8
            stock.roeTTM >= 5 -> 6
            stock.roeTTM > 0 -> 4
            else -> 0
        }
        val debtScore = when {
            stock.debtToAsset <= 0 -> 4    // 無數據給基礎分
            stock.debtToAsset <= 40 -> 9
            stock.debtToAsset <= 55 -> 7
            stock.debtToAsset <= 70 -> 5
            stock.debtToAsset <= 85 -> 3
            else -> 1
        }
        val cashScore = if (stock.operatingCashFlow > 0) 9 else 0
        val safetyScore = (roeScore + debtScore + cashScore).coerceAtMost(30)

        // ── 估值 (0-20)：週期底部看 PB（利潤波動大，PE 失真）──
        val valuationScore = when {
            stock.pb <= 1.0 -> 20
            stock.pb <= 1.5 -> 16
            stock.pb <= 2.0 -> 13
            stock.pb <= 2.5 -> 10
            stock.pb <= 3.0 -> 7
            else -> 4
        }

        // ── 市值安全 (0-15)：龍頭更抗週期衝擊 ──
        val capScore = when {
            stock.marketCap >= 100_000_000_000 -> 15
            stock.marketCap >= 50_000_000_000 -> 12
            stock.marketCap >= 20_000_000_000 -> 9
            stock.marketCap >= 10_000_000_000 -> 6
            stock.marketCap >= 3_000_000_000 -> 4
            else -> 2
        }

        val strength = (lowPositionScore + safetyScore + valuationScore + capScore).coerceIn(0, 100)

        val reason = buildString {
            append("[${c.sector}] 52週低位${"%.0f".format(c.position * 100)}%")
            append(" PB=${"%.2f".format(stock.pb)}")
            if (stock.roeTTM > 0) append(" ROE=${"%.1f".format(stock.roeTTM)}%")
            if (stock.debtToAsset > 0) append(" 負債${"%.0f".format(stock.debtToAsset)}%")
            append(" 市值${"%.0f".format(stock.marketCap / 100_000_000)}億")
        }

        return StrategySignal(
            stockCode = stock.code, stockName = stock.name, strategyId = id, category = category,
            strength = strength,
            action = when { strength >= 70 -> SignalAction.BUY; strength >= 55 -> SignalAction.WATCH; else -> SignalAction.HOLD },
            reason = reason,
            currentPrice = stock.price, changePercent = stock.changePercent
        )
    }

    /** 候選股 + 週期板塊 + 52 週位置信息 */
    private data class CyclicalCandidate(
        val stock: StockRealtime,
        val sector: String,
        val position: Double,   // 0=52週最低, 1=52週最高
        val high52w: Double,
        val low52w: Double
    )

    companion object {
        private const val TAG = "CyclicalLowPos"
        private const val STRENGTH_THRESHOLD = 50
    }
}
