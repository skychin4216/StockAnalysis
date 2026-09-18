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
 * ## 周期低位策略（长线左侧布局）
 *
 * 核心思想：周期性行业（锂矿/有色/钢铁/煤炭/化工/航运/证券等）的商品价格与股价
 * 随供需周期大幅波动。在「行业有真实需求、公司不会暴雷」的前提下，于商品与股价
 * 均处低位时左侧建仓，等待周期反转。对应用户需求：
 * > 锂矿有市场需求，但碳酸锂与股价均处低位 → 可适当建仓。
 *
 * 四层筛选：
 * 1. **周期行业**（硬过滤）：所属板块命中周期性行业关键词。
 * 2. **不会暴雷**（硬过滤）：非 ST/退市、正净资产(PB>0)、负债率<85%、
 *    ROE 或经营现金流为正、市值>30亿（排除微盘股）。
 * 3. **股价低位**（硬过滤）：现价位于 52 周区间下 30%（proxies 商品低位）。
 * 4. **综合评分**：低位深度(35) + 稳健性(30) + 估值PB(20) + 市值安全(15)。
 *
 * 局限：无外部商品期货价格数据，以「股价 52 周低位」近似「商品低位」；
 * 「市场需求」以行业本身为基础需求代理，暂不做需求侧量化。
 */
class CyclicalLowPositionStrategy(
    private val context: Context,
    private val screener: StockScreener
) : Strategy {

    override val id = "cyclical_low_position"
    override var name = "周期低位策略"
    override var description = "筛选行业周期底部、股价低位、基本面稳健的周期性行业龙头，左侧布局等待周期反转"
    override val category = StrategyCategory.VALUE
    override val holdingPeriods = listOf(HoldingPeriod.LONG)
    override val source = StrategySource.BUILTIN
    override val signalExpiryHours = 720

    // 长线左侧：止损放宽、止盈放大，给周期反转留足空间
    override val defaultStopLoss = -0.15f
    override val defaultTakeProfit = 0.50f

    override val config = StrategyConfig.custom(
        params = mapOf(
            "position_max" to 0.30,   // 52 周低位阈值（现价位于区间下 30%）
            "pb_max" to 3.0,          // PB 上限
            "debt_max" to 85.0,       // 资产负债率上限(%)
            "cap_min" to 3.0e9        // 最小市值（排除微盘股，防暴雷）
        ),
        maxResults = 10
    )

    override var weightFactors: List<WeightFactor> = listOf(
        WeightFactor("lowPosition", "低位深度", 35, "52周区间位置越低得分越高"),
        WeightFactor("safety", "稳健性", 30, "ROE/负债率/现金流，确保不暴雷"),
        WeightFactor("valuation", "估值", 20, "PB 越低得分越高"),
        WeightFactor("cap", "市值安全", 15, "市值越大越抗风险")
    )

    /** 周期性行业关键词（按子行业分组，命中任一即视为周期股，可自行增删） */
    private val CYCLICAL_KEYWORDS = listOf(
        // 金属矿产（锂/稀土/基本金属等，对应用户的锂矿需求）
        "锂", "钴", "镍", "稀土", "有色", "铜", "铝", "锌", "黄金", "钢铁", "铁矿",
        "钨", "锡", "钼", "锑", "钛", "镁", "贵金属", "能源金属", "小金属",
        // 能源
        "煤炭", "焦炭", "石油", "石化", "油服", "燃气", "油气",
        // 化工
        "化工", "化纤", "化肥", "农药", "纯碱", "氯碱", "氟", "磷", "有机硅",
        "钛白粉", "甲醇", "烯烃", "聚氨酯", "橡胶", "塑料", "化学原料", "化学制品",
        // 航运物流
        "航运", "船舶", "港口", "海运", "集运", "远洋",
        // 金融（强周期）
        "证券", "保险", "信托",
        // 地产基建
        "房地产", "地产", "建材", "水泥", "玻璃", "玻纤", "工程机械", "重卡",
        // 农牧（猪周期/糖周期）
        "养殖", "猪", "禽", "鸡", "种业", "糖", "棉花",
        // 汽车机械
        "汽车", "商用车", "机床",
        // 造纸
        "造纸", "纸浆",
        // 纺织（出口周期）
        "纺织", "服装", "家纺",
        // 电子/电力设备周期（面板/存储/光伏/锂电材料）
        "面板", "存储", "光伏", "硅", "太阳能", "锂电池", "电池"
    )

    override suspend fun screen(): Result<ScreeningResult> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        return@withContext try {
            val pool = if (config.stockPool.isEmpty()) screener.scanFullMarket()
            else screener.scanSpecific(config.stockPool).values.toList()
            screenWithPool(pool, startTime)
        } catch (e: Exception) {
            Log.e(TAG, "周期低位策略扫描失败", e)
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
            Log.e(TAG, "周期低位策略扫描失败", e)
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

        // ── 1. 硬性淘汰（纯内存，快速缩小池子）──
        val baseCandidates = pool.filter { stock ->
            // 防暴雷：剔除 ST / 退市风险
            !stock.name.contains("ST", ignoreCase = true) && !stock.name.contains("退") &&
            // 正净资产（PB>0；PB=0 为无数据，为安全起见剔除）
            stock.pb > 0 && stock.pb <= pbMax &&
            // 负债率红线（debtToAsset<=0 视为无数据放行，但有数据须 < debtMax）
            (stock.debtToAsset <= 0 || stock.debtToAsset < debtMax) &&
            // 基本面尚存：ROE 或经营现金流至少一项为正（周期底部允许利润薄，但不能both为负）
            (stock.roeTTM > 0 || stock.operatingCashFlow > 0) &&
            // 市值下限（排除微盘股，抗暴雷）
            stock.marketCap >= capMin &&
            // 流动性保障
            stock.amount > 50_000_000 &&
            // 价格稳定（排除涨跌停）
            stock.changePercent in -9.0..9.0
        }
        Log.i(TAG, "pool=${pool.size} → 硬性过滤后=${baseCandidates.size}")

        // ── 2. 周期行业过滤（查板块，命中关键词才保留）──
        val cyclicalCandidates = mutableListOf<Pair<StockRealtime, String>>() // stock → 命中的板块
        for (stock in baseCandidates) {
            val sectors = try { StockDataCenter.getSectorsByStock(stock.code) } catch (_: Exception) { emptyList() }
            val matched = matchCyclicalSector(sectors)
            if (matched != null) cyclicalCandidates.add(stock to matched)
        }
        Log.i(TAG, "硬性过滤后=${baseCandidates.size} → 周期行业命中=${cyclicalCandidates.size}")

        // ── 3. 股价低位过滤（查 52 周历史，现价须位于区间低位）──
        val db = StockDatabase.getInstance(context)
        val lowPositionCandidates = mutableListOf<CyclicalCandidate>()
        for ((stock, sector) in cyclicalCandidates) {
            val history = try { db.dailySnapshotDao().getByCode(stock.code, 250) } catch (_: Exception) { emptyList() }
            if (history.size < 120) continue // 历史不足，无法判断 52 周低位
            val high52w = history.maxOf { it.high }
            val low52w = history.minOf { it.low }
            if (high52w <= low52w) continue // 区间异常
            val position = (stock.price - low52w) / (high52w - low52w)
            if (position <= positionMax) {
                lowPositionCandidates.add(CyclicalCandidate(stock, sector, position, high52w, low52w))
            }
        }
        Log.i(TAG, "周期行业=${cyclicalCandidates.size} → 52周低位(≤${positionMax})=${lowPositionCandidates.size}")

        // ── 4. 综合评分 ──
        val signals = lowPositionCandidates.map { calculateSignal(it) }
            .filter { it.strength >= STRENGTH_THRESHOLD }
            .sortedByDescending { it.strength }
            .take(config.maxResults)

        Log.i(TAG, "评分后 strength>=$STRENGTH_THRESHOLD: ${signals.size}")
        return Result.success(ScreeningResult(
            strategyId = id, strategyName = name, category = category,
            signals = signals, totalScanned = pool.size, scanTimeMs = System.currentTimeMillis() - startTime
        ))
    }

    /** 返回命中的周期板块名；无命中返回 null */
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

        // ── 低位深度 (0-35)：52 周位置越低，安全边际与反转空间越大 ──
        val lowPositionScore = when {
            c.position <= 0.05 -> 35
            c.position <= 0.10 -> 31
            c.position <= 0.15 -> 27
            c.position <= 0.20 -> 23
            c.position <= 0.25 -> 19
            c.position <= 0.30 -> 15
            else -> 8
        }

        // ── 稳健性 (0-30)：确保「不会暴雷」──
        val roeScore = when {
            stock.roeTTM >= 20 -> 12
            stock.roeTTM >= 15 -> 10
            stock.roeTTM >= 10 -> 8
            stock.roeTTM >= 5 -> 6
            stock.roeTTM > 0 -> 4
            else -> 0
        }
        val debtScore = when {
            stock.debtToAsset <= 0 -> 4    // 无数据给基础分
            stock.debtToAsset <= 40 -> 9
            stock.debtToAsset <= 55 -> 7
            stock.debtToAsset <= 70 -> 5
            stock.debtToAsset <= 85 -> 3
            else -> 1
        }
        val cashScore = if (stock.operatingCashFlow > 0) 9 else 0
        val safetyScore = (roeScore + debtScore + cashScore).coerceAtMost(30)

        // ── 估值 (0-20)：周期底部看 PB（利润波动大，PE 失真）──
        val valuationScore = when {
            stock.pb <= 1.0 -> 20
            stock.pb <= 1.5 -> 16
            stock.pb <= 2.0 -> 13
            stock.pb <= 2.5 -> 10
            stock.pb <= 3.0 -> 7
            else -> 4
        }

        // ── 市值安全 (0-15)：龙头更抗周期冲击 ──
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
            append("[${c.sector}] 52周低位${"%.0f".format(c.position * 100)}%")
            append(" PB=${"%.2f".format(stock.pb)}")
            if (stock.roeTTM > 0) append(" ROE=${"%.1f".format(stock.roeTTM)}%")
            if (stock.debtToAsset > 0) append(" 负债${"%.0f".format(stock.debtToAsset)}%")
            append(" 市值${"%.0f".format(stock.marketCap / 100_000_000)}亿")
        }

        return StrategySignal(
            stockCode = stock.code, stockName = stock.name, strategyId = id, category = category,
            strength = strength,
            action = when { strength >= 70 -> SignalAction.BUY; strength >= 55 -> SignalAction.WATCH; else -> SignalAction.HOLD },
            reason = reason,
            currentPrice = stock.price, changePercent = stock.changePercent
        )
    }

    /** 候选股 + 周期板块 + 52 周位置信息 */
    private data class CyclicalCandidate(
        val stock: StockRealtime,
        val sector: String,
        val position: Double,   // 0=52周最低, 1=52周最高
        val high52w: Double,
        val low52w: Double
    )

    companion object {
        private const val TAG = "CyclicalLowPos"
        private const val STRENGTH_THRESHOLD = 50
    }
}
