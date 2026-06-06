package com.chin.stockanalysis.strategy.strategies

import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource
import com.chin.stockanalysis.strategy.*
import com.chin.stockanalysis.strategy.data.StockScreener
import com.chin.stockanalysis.strategy.models.WeightFactor
import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.models.StrategySignal
import com.chin.stockanalysis.strategy.models.SignalAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ## 早盘砸盘拉升（V型反转）选股分析
 *
 * 核心逻辑：在今日热门板块中寻找"开盘后砸盘→迅速拉升"的 V 型反转个股。
 *
 * ### 使用场景
 * - **日内做T**：找到主力砸盘后迅速回拉的个股，低位/中低位入场
 * - **板块确认**：某板块中多只个股出现 V 型反转 → 板块稳健，关注同板块尚未启动的补涨股
 *
 * ### V 型反转判定（基于日线 OHLCV）
 * - 盘中低点曾远低于开盘价（砸盘深度 ≥ 3%）
 * - 收盘/现价从低点大幅回升（回升幅度 ≥ 低点振幅的 60%）
 * - 成交额充足（≥ 2亿，排除无量下跌）
 * - 非冷门板块
 *
 * ### 输出两档信号
 * - **V型反转（做T）**: 满足核心 V 型条件，适合日内做T
 * - **同板块补涨**: 热门板块中尚未启动的个股（当日涨幅温和，有补涨潜力）
 */
class EarlyMorningChaseStrategy(
    private val screener: StockScreener
) : Strategy {

    override val id = "early_morning_chase"
    override var name = "早盘追涨选股"
    override var description = "以单个交易日为单位，分析热门板块中追涨机会（V型反转+补涨信号）"
    override val category = StrategyCategory.MOMENTUM
    override val source = StrategySource.USER_CUSTOM

    override val config = StrategyConfig.custom(
        params = mapOf(
            "dump_depth_min" to 3.0,       // 砸盘深度下限%（低点相对开盘价的跌幅）
            "recovery_ratio_min" to 0.60,   // 回升比例下限（从低点回升的幅度/总振幅）
            "amplitude_min" to 5.0,         // 最低振幅%
            "amount_min" to 200_000_000.0,  // 最低成交额
            "v_score_threshold" to 60,       // V型反转入选门槛
            "catchup_score_threshold" to 50, // 补涨入选门槛
            "max_results" to 8
        ),
        maxResults = 10
    )

    override var weightFactors: List<WeightFactor> = listOf(
        WeightFactor("v_pattern", "V型反转力度", 40, "砸盘深度+回升强度综合评分"),
        WeightFactor("hot_sector", "热门板块绑定", 30, "是否属于当日S/A级热门板块"),
        WeightFactor("capital_quality", "资金质量", 20, "成交额+回升段资金力度"),
        WeightFactor("position_quality", "入场位置", 10, "当前价格在V型中的位置（越低越好）")
    )

    override suspend fun screen(): Result<ScreeningResult> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        return@withContext try {
            val pool = if (config.stockPool.isEmpty()) screener.scanFullMarket()
            else screener.scanSpecific(config.stockPool).values.toList()
            screenWithPool(pool, startTime, isBacktest = false)
        } catch (e: Exception) {
            Log.e(id, "策略执行失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun screenWithData(preloadedStocks: List<StockRealtime>): Result<ScreeningResult> {
        val startTime = System.currentTimeMillis()
        return try {
            val pool = if (config.stockPool.isNotEmpty()) preloadedStocks.filter { it.code in config.stockPool } else preloadedStocks
            screenWithPool(pool, startTime, isBacktest = true)
        } catch (e: Exception) {
            Log.e(id, "策略执行失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun screenWithPool(pool: List<StockRealtime>, startTime: Long, isBacktest: Boolean): Result<ScreeningResult> {
        if (pool.isEmpty()) return Result.success(ScreeningResult(
            strategyId = id, strategyName = name, category = category,
            signals = emptyList(), totalScanned = 0, scanTimeMs = System.currentTimeMillis() - startTime
        ))

        Log.i(id, "========== V型反转筛选 pool=${pool.size} isBacktest=$isBacktest ==========")

        // 获取当日热门板块（实时用概念板块，回测也可使用）
        val hotSectors = EastMoneyHotSectorSource.conceptSectors.map { it.name }.toSet()
        Log.i(id, "当日热门板块(${hotSectors.size}): ${hotSectors.take(8).joinToString(" ")}")

        // Step 1: 识别 V 型反转个股
        val vCandidates = mutableListOf<VReversal>()
        var cntNoV = 0; var cntLowAmt = 0; var cntColdSector = 0

        for (stock in pool) {
            val v = detectVReversal(stock, isBacktest)
            if (v == null) {
                cntNoV++
                continue
            }
            vCandidates.add(v)
        }

        Log.i(id, "V型检测: pool=${pool.size} → V型=${vCandidates.size} | 无V型=${cntNoV}")

        // Step 2: 打分 V 型反转
        val vScored = vCandidates.map { v ->
            val score = scoreVReversal(v)
            v to score
        }.filter { (_, s) -> s >= config.getInt("v_score_threshold", 60) }
            .sortedByDescending { (_, s) -> s }
        Log.i(id, "V型评分≥${config.getInt("v_score_threshold", 60)}: ${vScored.size}")
        vScored.take(5).forEach { (v, s) ->
            Log.i(id, "  V型: ${v.stock.name}(${v.stock.code.takeLast(6)}) score=$s dump=${"%.1f".format(v.dumpDepth)}% recover=${"%.1f".format(v.recoveryRatio*100)}% amp=${"%.1f".format(v.amplitude)}%")
        }

        // Step 3: 生成 V 型反转信号（做T信号）
        val vSignals = vScored.take(config.maxResults / 2).map { (v, score) ->
            buildVSignal(v, score)
        }

        // Step 4: 找热门板块中 V 型反转少的板块 → 寻找补涨机会
        // V型反转多的板块 = 板块稳健 → 找同板块涨幅温和的补涨股
        val sectorVRCount = mutableMapOf<String, Int>()
        for (v in vCandidates) {
            v.sectorNames.forEach { sec -> sectorVRCount[sec] = (sectorVRCount[sec] ?: 0) + 1 }
        }

        // 在 V 型反转 ≥2 只的板块中找补涨股（涨幅 0~4%、成交额>1亿、未见 V 型反转）
        val catchupCandidates = mutableListOf<StockRealtime>()
        val strongSectors = sectorVRCount.filter { it.value >= 2 }.keys
        if (strongSectors.isNotEmpty()) {
            val vCodes = vCandidates.map { it.stock.code }.toSet()
            for (stock in pool) {
                if (stock.code in vCodes) continue
                if (stock.amount < 100_000_000) continue
                if (stock.changePercent !in 0.0..4.0) continue
                if (stock.price <= 1.0) continue
                // 检查是否属于 V 型反转活跃的板块（暂时通过价格特征：处在热门板块中且温和上涨）
                catchupCandidates.add(stock)
            }
        }

        Log.i(id, "补涨候选: V型≥2的板块=${strongSectors.size}个, 补涨候选股=${catchupCandidates.size}")

        // Step 5: 打分补涨股
        val catchupScored = catchupCandidates.map { stock ->
            val s = scoreCatchup(stock)
            stock to s
        }.filter { (_, s) -> s >= config.getInt("catchup_score_threshold", 50) }
            .sortedByDescending { (_, s) -> s }
        Log.i(id, "补涨评分≥${config.getInt("catchup_score_threshold", 50)}: ${catchupScored.size}")

        val catchupSignals = catchupScored.take(config.maxResults / 2).map { (stock, score) ->
            buildCatchupSignal(stock, score)
        }

        val allSignals = vSignals + catchupSignals
        Log.i(id, "========== 最终: V型=${vSignals.size} 补涨=${catchupSignals.size} ==========")

        return Result.success(ScreeningResult(
            strategyId = id, strategyName = name, category = category,
            signals = allSignals, totalScanned = pool.size, scanTimeMs = System.currentTimeMillis() - startTime
        ))
    }

    override suspend fun isAvailable(): Boolean = true

    // ═══════════════════════════════
    // V 型反转检测
    // ═══════════════════════════════

    data class VReversal(
        val stock: StockRealtime,
        val dumpDepth: Double,     // 砸盘深度%（open→low 的跌幅）
        val recoveryRatio: Double, // 回升比例（low 回升了振幅的多少比例，0~1）
        val amplitude: Double,     // 全日振幅%
        val sectorNames: Set<String> // 所属热门板块
    )

    private fun detectVReversal(stock: StockRealtime, isBacktest: Boolean): VReversal? {
        if (stock.high <= 0 || stock.low <= 0 || stock.open <= 0) return null
        if (stock.amount < 200_000_000) return null

        // V型反转核心：盘中低点远低于开盘价（砸盘），然后从低点大幅回升
        // 不强制要求 price > yestClose（日内V型可能在昨收附近甚至略低）

        // 计算砸盘深度：盘中低点相对开盘价的跌幅
        val dumpDepth = if (stock.open > 0) ((stock.open - stock.low) / stock.open) * 100 else 0.0

        // 砸盘深度不够，不构成 V 型
        if (dumpDepth < 3.0) return null

        // 必须从低点有实质回升：现价/收盘价 相对 低点 涨幅 ≥ 2%
        val recoveryFromLow = if (stock.low > 0) ((stock.price - stock.low) / stock.low) * 100 else 0.0
        if (recoveryFromLow < 2.0) return null

        // 计算振幅（基于昨收更合理）
        val basePrice = if (stock.yestClose > 0) stock.yestClose else stock.open
        val amplitude = if (basePrice > 0) ((stock.high - stock.low) / basePrice) * 100 else 0.0
        if (amplitude < 5.0) return null

        // 回升比例：从低点回到当前价，走了振幅的多少
        val recoveryRatio = if (stock.high > stock.low) {
            (stock.price - stock.low) / (stock.high - stock.low)
        } else 0.0

        // 回升比例必须 ≥ 60%（即收在高位附近）
        if (recoveryRatio < 0.60) return null

        // 板块匹配：检查是否属于热门板块
        val hotSectors = EastMoneyHotSectorSource.conceptSectors.map { it.name }.toSet()
        val sectorDataAvailable = hotSectors.isNotEmpty()

        val matched = if (isBacktest || !sectorDataAvailable) {
            // 回测模式或板块数据未加载：放宽板块要求，只要有 V 型形态即可
            setOf(if (isBacktest) "回测模式" else "板块数据加载中")
        } else {
            hotSectors.filter { sector ->
                stock.name.contains(sector) || sector.contains(stock.name.take(2))
            }.toSet()
        }
        // 有板块数据但未匹配到：实时模式下需要板块匹配
        if (!isBacktest && sectorDataAvailable && matched.isEmpty()) return null
        val finalSectors = if (matched.isEmpty()) setOf("V型反转") else matched

        return VReversal(
            stock = stock,
            dumpDepth = dumpDepth,
            recoveryRatio = recoveryRatio,
            amplitude = amplitude,
            sectorNames = matched
        )
    }

    // ═══════════════════════════════
    // V 型反转打分（满分100）
    // ═══════════════════════════════

    private fun scoreVReversal(v: VReversal): Int {
        val s = v.stock

        // 1. V型反转力度 (0-40)
        val vScore = when {
            v.dumpDepth >= 5.0 && v.recoveryRatio >= 0.80 -> 40  // 深砸+强回升
            v.dumpDepth >= 4.0 && v.recoveryRatio >= 0.70 -> 35
            v.dumpDepth >= 3.0 && v.recoveryRatio >= 0.60 -> 25
            v.recoveryRatio >= 0.50 -> 20
            else -> 15
        }

        // 2. 热门板块绑定 (0-30)
        val sectorScore = when {
            v.sectorNames.size >= 2 -> 30  // 跨多板块
            v.sectorNames.size == 1 -> 25
            else -> 15
        }

        // 3. 资金质量 (0-20)：成交额大说明主力参与度高
        val capitalScore = when {
            s.amount > 2_000_000_000 -> 20
            s.amount > 1_000_000_000 -> 16
            s.amount > 500_000_000 -> 12
            s.amount > 200_000_000 -> 8
            else -> 4
        }

        // 4. 入场位置 (0-10)：当前价在 V 型低位更好
        // 如果还在中低位（price 离 low 不太远），入场位置更优
        val positionInV = if (s.high > s.low) {
            (s.price - s.low) / (s.high - s.low)
        } else 1.0
        val positionScore = when {
            positionInV < 0.4 -> 10   // 还在中低位，入场良机
            positionInV < 0.6 -> 8
            positionInV < 0.8 -> 5
            else -> 3                 // 已在接近高位
        }

        return minOf(vScore + sectorScore + capitalScore + positionScore, 100)
    }

    // ═══════════════════════════════
    // 补涨打分（满分100）
    // ═══════════════════════════════

    private fun scoreCatchup(stock: StockRealtime): Int {
        // 补涨候选：温和涨幅 + 充足成交
        val chgScore = when {
            stock.changePercent in 0.5..2.0 -> 30
            stock.changePercent in 0.2..0.5 -> 25
            stock.changePercent in 0.0..0.2 -> 20
            stock.changePercent in 2.0..4.0 -> 15
            else -> 10
        }
        val amtScore = when {
            stock.amount > 1_000_000_000 -> 30
            stock.amount > 500_000_000 -> 24
            stock.amount > 200_000_000 -> 18
            stock.amount > 100_000_000 -> 12
            else -> 6
        }
        val ampScore = when {
            stock.high > 0 && stock.low > 0 -> {
                val amp = ((stock.high - stock.low) / stock.low) * 100
                when { amp < 3 -> 20; amp < 5 -> 15; else -> 10 }
            }
            else -> 15
        }
        // 补涨需要收在开盘价上方（逆势走强）
        val trendScore = if (stock.price > stock.open) 20 else if (stock.price >= stock.open * 0.99) 15 else 5

        return minOf(chgScore + amtScore + ampScore + trendScore, 100)
    }

    // ═══════════════════════════════
    // 信号生成
    // ═══════════════════════════════

    private fun buildVSignal(v: VReversal, score: Int): StrategySignal {
        val s = v.stock
        return StrategySignal(
            stockCode = s.code,
            stockName = s.name,
            strategyId = id,
            category = category,
            strength = score,
            action = when { score >= 80 -> SignalAction.BUY; score >= 60 -> SignalAction.WATCH; else -> SignalAction.HOLD },
            reason = "V型反转(做T): 砸盘${"%.1f".format(v.dumpDepth)}%→回升${"%.0f".format(v.recoveryRatio*100)}% 振幅${"%.1f".format(v.amplitude)}% 板块:${v.sectorNames.joinToString(",")}",
            details = mapOf(
                "pattern" to "V型反转",
                "dump_depth" to "${"%.1f".format(v.dumpDepth)}%",
                "recovery_ratio" to "${"%.0f".format(v.recoveryRatio*100)}%",
                "amplitude" to "${"%.1f".format(v.amplitude)}%",
                "sectors" to v.sectorNames.joinToString(","),
                "entry" to "砸盘后回升至中低位时入场",
                "stop_loss" to "跌破盘中低点止损",
                "take_profit" to "回升至振幅70%以上分批止盈"
            ),
            currentPrice = s.price,
            changePercent = s.changePercent
        )
    }

    private fun buildCatchupSignal(stock: StockRealtime, score: Int): StrategySignal {
        return StrategySignal(
            stockCode = stock.code,
            stockName = stock.name,
            strategyId = id,
            category = category,
            strength = score,
            action = when { score >= 80 -> SignalAction.BUY; score >= 60 -> SignalAction.WATCH; else -> SignalAction.HOLD },
            reason = "同板块补涨: 涨幅温和${"%.1f".format(stock.changePercent)}% 成交${"%.1f".format(stock.amount/1e8)}亿",
            details = mapOf(
                "pattern" to "补涨机会",
                "change" to "${"%.1f".format(stock.changePercent)}%",
                "amount" to "${"%.1f".format(stock.amount/1e8)}亿",
                "entry" to "板块V型反转确认后关注补涨",
                "stop_loss" to "板块龙头走弱止损",
                "take_profit" to "补涨5-8%止盈"
            ),
            currentPrice = stock.price,
            changePercent = stock.changePercent
        )
    }
}