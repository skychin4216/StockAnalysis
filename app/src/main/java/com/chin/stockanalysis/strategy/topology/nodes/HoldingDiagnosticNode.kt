package com.chin.stockanalysis.strategy.topology.nodes

import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.topology.core.*
import com.chin.stockanalysis.strategy.trade.RealPositionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ## 持仓诊断分析节点
 *
 * 对每只持仓进行多维度健康评分，并分析板块风险和组合健康度。
 *
 * ### 输出维度
 * 1. **技术健康分** (0-100): MA排列 + 动量 + 量能
 * 2. **风险分** (0-100): 止损距离 + 回撤 + 波动率 (100=安全, 0=危险)
 * 3. **资金分** (0-100): 量价关系推断主力资金方向
 * 4. **板块风险**: 持仓板块集中度 + 板块趋势
 * 5. **组合健康度**: 加权综合分 + 分散化评估
 *
 * ### 输入
 * - n_a_market: MarketAnalysisResult (可选)
 * - n_news: Int (可选)
 *
 * ### 输出
 * [HoldingDiagnosticResult]
 */
data class HoldingDiagnosticResult(
    val holdings: List<HoldingDiagnosis> = emptyList(),
    val sectorRisks: List<SectorRiskInfo> = emptyList(),
    val portfolioHealth: PortfolioHealth = PortfolioHealth(),
    val summary: String = ""
)

data class HoldingDiagnosis(
    val stockCode: String,
    val stockName: String,
    val daysHeld: Int,
    val pnlPct: Double,
    val currentPrice: Double,
    val avgBuyPrice: Double,
    val technicalScore: Int,     // 0-100
    val riskScore: Int,          // 0-100 (100=安全)
    val capitalScore: Int,       // 0-100
    val healthScore: Int,        // 加权综合 (0-100)
    val sector: String = "",
    val advice: String = "",
    val warnings: List<String> = emptyList()
)

data class SectorRiskInfo(
    val sectorName: String,
    val stockCodes: List<String>,
    val concentration: Double,   // 占比 0-1
    val sectorChangePct: Double, // 板块当日涨跌
    val trend: String,           // "上升"/"下降"/"震荡"
    val riskLevel: String        // "LOW"/"MEDIUM"/"HIGH"
)

data class PortfolioHealth(
    val overallScore: Int = 50,       // 0-100
    val diversification: String = "", // "充分分散"/"轻度集中"/"高度集中"
    val totalRisk: String = "",       // "低风险"/"中等风险"/"高风险"
    val suggestions: List<String> = emptyList()
)

class HoldingDiagnosticNode : BaseNode<Any, HoldingDiagnosticResult>(
    "n_holding_diag", "持仓诊断分析", NodeType.FACTOR_COMPUTE
) {
    companion object {
        private const val TAG = "HoldingDiag"
    }

    override suspend fun execute(context: PipelineContext, input: Any): HoldingDiagnosticResult {
        Log.i(TAG, "开始持仓诊断分析...")

        val db = StockDatabase.getInstance(context.androidContext)
        val today = LocalDate.now()

        // 1. 读取持仓
        val realPositions = withContext(Dispatchers.IO) {
            try { db.realPositionDao().getAllActive() } catch (_: Exception) { emptyList<RealPositionEntity>() }
        }

        if (realPositions.isEmpty()) {
            Log.i(TAG, "无持仓，跳过诊断")
            return HoldingDiagnosticResult(summary = "暂无持仓")
        }

        // 2. 逐股诊断
        val diagnoses = mutableListOf<HoldingDiagnosis>()
        val sectorMap = mutableMapOf<String, MutableList<String>>() // sector -> stockCodes

        for (pos in realPositions) {
            val diag = analyzeSingleHolding(db, pos, today)
            diagnoses.add(diag)
            if (diag.sector.isNotBlank()) {
                sectorMap.getOrPut(diag.sector) { mutableListOf() }.add(pos.stockCode)
            }
        }

        // 3. 板块风险分析
        val sectorRisks = analyzeSectorRisks(db, sectorMap, today)

        // 4. 组合健康度
        val portfolioHealth = assessPortfolioHealth(diagnoses, sectorRisks)

        // 5. 汇总
        val avgHealth = if (diagnoses.isNotEmpty()) diagnoses.map { it.healthScore }.average().toInt() else 50
        val summary = buildString {
            append("持仓${diagnoses.size}只, 综合健康度${avgHealth}分")
            append(" | ${portfolioHealth.diversification}")
            append(" | ${portfolioHealth.totalRisk}")
            val highRisk = diagnoses.count { it.healthScore < 40 }
            if (highRisk > 0) append(" | ⚠${highRisk}只高风险")
        }

        Log.i(TAG, "诊断完成: $summary")
        return HoldingDiagnosticResult(diagnoses, sectorRisks, portfolioHealth, summary)
    }

    private suspend fun analyzeSingleHolding(
        db: StockDatabase,
        pos: RealPositionEntity,
        today: LocalDate
    ): HoldingDiagnosis = withContext(Dispatchers.IO) {
        val code = pos.stockCode
        val buyPrice = pos.avgBuyPrice

        // 读取K线
        val snaps = try {
            db.dailySnapshotDao().getByCode(code, 60).sortedBy { it.date }
        } catch (_: Exception) { emptyList() }

        if (snaps.size < 5) {
            return@withContext HoldingDiagnosis(
                stockCode = code, stockName = pos.stockName,
                daysHeld = 0, pnlPct = 0.0, currentPrice = 0.0, avgBuyPrice = buyPrice,
                technicalScore = 50, riskScore = 50, capitalScore = 50, healthScore = 50,
                advice = "数据不足"
            )
        }

        val latest = snaps.last()
        val currentPrice = latest.close
        val daysHeld = try {
            ChronoUnit.DAYS.between(LocalDate.parse(pos.buyDate), today).toInt().coerceAtLeast(0)
        } catch (_: Exception) { 0 }
        val pnlPct = if (buyPrice > 0) (currentPrice - buyPrice) / buyPrice * 100 else 0.0

        val closes = snaps.map { it.close }
        val volumes = snaps.map { it.volume.toDouble() }

        // ── 技术健康分 (0-100) ──
        val techScore = computeTechnicalScore(closes)

        // ── 风险分 (0-100, 100=安全) ──
        val riskScore = computeRiskScore(pnlPct, closes)

        // ── 资金分 (0-100) ──
        val capScore = computeCapitalScore(closes, volumes)

        // ── 综合健康度 ──
        val healthScore = (techScore * 0.35 + riskScore * 0.35 + capScore * 0.30).toInt()

        // ── 板块 ──
        val sector = try {
            db.sectorStockDao().getSectorNamesByStockCode(code).firstOrNull() ?: ""
        } catch (_: Exception) { "" }

        // ── 警告 ──
        val warnings = mutableListOf<String>()
        if (pnlPct <= -8) warnings.add("接近止损线(${String.format("%.1f", pnlPct)}%)")
        if (techScore < 30) warnings.add("技术面恶化")
        if (capScore < 30) warnings.add("资金流出迹象")
        if (riskScore < 30) warnings.add("风险指标偏高")

        // MA60 check
        val ma60 = if (closes.size >= 60) closes.takeLast(60).average() else 0.0
        if (ma60 > 0 && currentPrice < ma60 * 0.9) warnings.add("低于MA60超过10%")

        // ── 建议 ──
        val advice = when {
            healthScore >= 75 -> "📈 健康持有"
            healthScore >= 55 -> "📊 正常持有，关注变化"
            healthScore >= 40 -> "🔄 偏弱，考虑减仓或做T"
            healthScore >= 25 -> "⚠ 风险偏高，建议减仓"
            else -> "🔴 建议清仓"
        }

        HoldingDiagnosis(
            stockCode = code, stockName = pos.stockName,
            daysHeld = daysHeld, pnlPct = pnlPct,
            currentPrice = currentPrice, avgBuyPrice = buyPrice,
            technicalScore = techScore, riskScore = riskScore,
            capitalScore = capScore, healthScore = healthScore,
            sector = sector, advice = advice, warnings = warnings
        )
    }

    /**
     * 技术健康分: MA排列 + 动量 + 量能趋势
     */
    private fun computeTechnicalScore(closes: List<Double>): Int {
        if (closes.size < 20) return 50

        val ma5 = closes.takeLast(5).average()
        val ma10 = closes.takeLast(10).average()
        val ma20 = closes.takeLast(20).average()
        val price = closes.last()

        var score = 50

        // MA排列 (±20)
        when {
            ma5 > ma10 && ma10 > ma20 -> score += 20  // 多头
            ma5 > ma10 -> score += 10                   // 半多头
            ma5 < ma10 && ma10 < ma20 -> score -= 20  // 空头
            ma5 < ma10 -> score -= 10                   // 半空头
        }

        // 价格vs均线 (±15)
        when {
            price > ma5 && ma5 > ma20 -> score += 15
            price > ma20 -> score += 5
            price < ma5 && ma5 < ma20 -> score -= 15
            price < ma20 -> score -= 5
        }

        // 近5日动量 (±15)
        if (closes.size >= 6) {
            val mom5 = (closes.last() - closes[closes.size - 6]) / closes[closes.size - 6] * 100
            when {
                mom5 > 5 -> score += 15
                mom5 > 2 -> score += 10
                mom5 > 0 -> score += 5
                mom5 > -2 -> score -= 5
                mom5 > -5 -> score -= 10
                else -> score -= 15
            }
        }

        return score.coerceIn(0, 100)
    }

    /**
     * 风险分: 止损距离 + 回撤 + 波动率
     */
    private fun computeRiskScore(pnlPct: Double, closes: List<Double>): Int {
        var score = 70  // 基础分

        // 盈亏 (±30)
        when {
            pnlPct > 10 -> score += 20
            pnlPct > 5 -> score += 15
            pnlPct > 0 -> score += 10
            pnlPct > -3 -> score -= 5
            pnlPct > -5 -> score -= 15
            pnlPct > -8 -> score -= 25
            else -> score -= 30
        }

        // 从最高点回撤 (±20)
        if (closes.isNotEmpty()) {
            val peak = closes.max()
            val drawdown = if (peak > 0) (closes.last() - peak) / peak * 100 else 0.0
            when {
                drawdown > -5 -> score += 10
                drawdown > -10 -> score -= 5
                drawdown > -15 -> score -= 15
                else -> score -= 20
            }
        }

        // 波动率惩罚 (±10)
        if (closes.size >= 10) {
            val returns = closes.zipWithNext { a, b -> if (a > 0) (b - a) / a * 100 else 0.0 }
            val volatility = returns.map { it * it }.average()
            when {
                volatility < 1 -> score += 10   // 低波动
                volatility < 4 -> score += 0    // 正常
                volatility < 9 -> score -= 5    // 偏高
                else -> score -= 10             // 高波动
            }
        }

        return score.coerceIn(0, 100)
    }

    /**
     * 资金分: 量价关系推断
     */
    private fun computeCapitalScore(closes: List<Double>, volumes: List<Double>): Int {
        if (closes.size < 10 || volumes.size < 10) return 50

        var score = 50
        val recent5 = closes.takeLast(5)
        val recentVol5 = volumes.takeLast(5)
        val prev5 = closes.dropLast(5).takeLast(5)
        val prevVol5 = volumes.dropLast(5).takeLast(5)

        // 量价配合 (±25)
        val priceChange = if (prev5.first() > 0) (recent5.last() - prev5.first()) / prev5.first() * 100 else 0.0
        val volChange = if (prevVol5.average() > 0) recentVol5.average() / prevVol5.average() else 1.0

        when {
            priceChange > 0 && volChange > 1.2 -> score += 25  // 价涨量增(健康)
            priceChange > 0 && volChange < 0.8 -> score += 10  // 价涨量缩(可能见顶)
            priceChange < 0 && volChange > 1.3 -> score -= 25  // 价跌量增(恐慌)
            priceChange < 0 && volChange < 0.7 -> score += 5   // 价跌量缩(惜售,可能见底)
            priceChange < -3 && volChange > 1.5 -> score -= 20 // 放量暴跌
        }

        // 近3日量能趋势 (±15)
        if (volumes.size >= 6) {
            val vol3 = volumes.takeLast(3).average()
            val volPrev3 = volumes.dropLast(3).takeLast(3).average()
            val volTrend = if (volPrev3 > 0) vol3 / volPrev3 else 1.0
            when {
                volTrend > 1.3 && priceChange > 0 -> score += 15  // 放量上涨
                volTrend > 1.3 && priceChange < 0 -> score -= 15  // 放量下跌
                volTrend < 0.7 && priceChange > 0 -> score += 5   // 缩量上涨
                volTrend < 0.7 && priceChange < 0 -> score += 10  // 缩量下跌(洗盘)
            }
        }

        // 连续放量/缩量 (±10)
        val vol5trend = if (recentVol5.size >= 3) {
            val inc = recentVol5.zipWithNext { a, b -> if (b > a) 1 else -1 }
            inc.sum()
        } else 0
        score += vol5trend * 3  // 连续放量加分

        return score.coerceIn(0, 100)
    }

    /**
     * 板块风险分析
     */
    private suspend fun analyzeSectorRisks(
        db: StockDatabase,
        sectorMap: Map<String, List<String>>,
        today: LocalDate
    ): List<SectorRiskInfo> = withContext(Dispatchers.IO) {
        val totalStocks = sectorMap.values.sumOf { it.size }.toDouble().coerceAtLeast(1.0)
        val dateStr = today.toString()

        sectorMap.map { (sector, codes) ->
            val concentration = codes.size / totalStocks

            // 读取板块当日涨跌
            val sectorChange = try {
                val records = db.sectorDailyRecordDao().getByDate(dateStr)
                records.filter { it.sectorName == sector }.map { it.changePct }.firstOrNull() ?: 0.0
            } catch (_: Exception) { 0.0 }

            val trend = when {
                sectorChange > 1.0 -> "上升"
                sectorChange < -1.0 -> "下降"
                else -> "震荡"
            }

            val riskLevel = when {
                concentration > 0.5 && sectorChange < -1.0 -> "HIGH"
                concentration > 0.4 || sectorChange < -2.0 -> "HIGH"
                concentration > 0.3 || sectorChange < -1.0 -> "MEDIUM"
                else -> "LOW"
            }

            SectorRiskInfo(sector, codes, concentration, sectorChange, trend, riskLevel)
        }.sortedByDescending { it.concentration }
    }

    /**
     * 组合健康度评估
     */
    private fun assessPortfolioHealth(
        diagnoses: List<HoldingDiagnosis>,
        sectorRisks: List<SectorRiskInfo>
    ): PortfolioHealth {
        if (diagnoses.isEmpty()) return PortfolioHealth()

        // 加权健康度
        val overallScore = diagnoses.map { it.healthScore }.average().toInt()

        // 分散化评估
        val highRiskSectors = sectorRisks.count { it.riskLevel == "HIGH" }
        val maxConcentration = sectorRisks.maxOfOrNull { it.concentration } ?: 0.0
        val diversification = when {
            maxConcentration > 0.5 -> "高度集中(单板块>${String.format("%.0f", maxConcentration * 100)}%)"
            maxConcentration > 0.3 -> "轻度集中(单板块>${String.format("%.0f", maxConcentration * 100)}%)"
            else -> "充分分散"
        }

        // 总风险
        val avgRisk = diagnoses.map { it.riskScore }.average()
        val totalRisk = when {
            avgRisk >= 70 && highRiskSectors == 0 -> "低风险"
            avgRisk >= 50 -> "中等风险"
            else -> "高风险"
        }

        // 建议
        val suggestions = mutableListOf<String>()
        val weakStocks = diagnoses.filter { it.healthScore < 40 }
        if (weakStocks.isNotEmpty()) {
            suggestions.add("${weakStocks.size}只持仓健康度<40，建议优先处理: ${weakStocks.joinToString { it.stockName }}")
        }
        if (highRiskSectors > 0) {
            suggestions.add("板块集中度过高，建议分散到${sectorRisks.filter { it.riskLevel != "HIGH" }.joinToString { it.sectorName }}等板块")
        }
        val lossStocks = diagnoses.filter { it.pnlPct < -5 }
        if (lossStocks.isNotEmpty()) {
            suggestions.add("${lossStocks.size}只亏损>5%，注意止损纪律")
        }
        if (diagnoses.size > 6) {
            suggestions.add("持仓数量偏多(${diagnoses.size}只)，建议集中到5-6只")
        }
        if (suggestions.isEmpty()) {
            suggestions.add("组合状态良好，继续持有")
        }

        return PortfolioHealth(overallScore, diversification, totalRisk, suggestions)
    }
}
