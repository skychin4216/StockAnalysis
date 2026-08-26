package com.chin.stockanalysis.strategy.topology.nodes

import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.backtest.SectorDailyRecordEntity
import com.chin.stockanalysis.strategy.market.AMarketAnalysisEngine
import com.chin.stockanalysis.strategy.topology.core.*

/**
 * ## 市场公共研判 Pipeline 节点（大盘多周期研判 → 风格轮动判断 → 板块强弱监测）
 *
 * 该组节点为「公共 pipeline」专用：输入 Any（自包含，从 DB 读取市场/板块数据），
 * 输出市场级研判结果，存入 context.stageOutputs，供四周期私有 pipeline 后续消费。
 */

// ═══════════════════════════════════════════════════════════════
//  板块强弱监测（sector_strength）
// ═══════════════════════════════════════════════════════════════

/** 单个板块的强弱统计 */
data class SectorStrength(
    val sectorCode: String,
    val sectorName: String,
    val avgChangePct: Double,
    val totalHotScore: Double,
    val mainNetInflow: Double,
    val hotDays: Int,
    val compositeScore: Double,
    val rank: Int
)

/** 板块强弱监测结果 */
data class SectorStrengthResult(
    val topSectors: List<SectorStrength>,
    val hotSectorCodes: List<String>,
    val dateRange: String,
    val summary: String
)

/**
 * ## 板块强弱监测节点
 *
 * 从 sector_daily_record 读取近 N 个交易日的板块记录，按「涨幅 + 热度 + 主力资金 + 热门天数」
 * 计算板块综合强弱，输出 Top N 强势板块及其轮动阶段。
 *
 * XML 用法：`<node module="sector_strength" />`
 *   - lookbackDays="20" 统计窗口（默认 20）
 *   - topN="15" 输出板块数量（默认 15）
 *
 * 输出 [SectorStrengthResult]，存入 context.stageOutputs["n_sector_strength"]。
 */
class SectorStrengthNode(
    private val lookbackDays: Int = 20,
    private val topN: Int = 15
) : BaseNode<Any, SectorStrengthResult>("n_sector_strength", "板块强弱监测", NodeType.FACTOR_COMPUTE) {

    override suspend fun execute(context: PipelineContext, input: Any): SectorStrengthResult {
        val db = StockDatabase.getInstance(context.androidContext)
        val records = try {
            db.sectorDailyRecordDao().getRecentDays(lookbackDays)
        } catch (e: Exception) {
            Log.w("SectorStrengthNode", "读取板块记录失败: ${e.message}")
            emptyList()
        }
        if (records.isEmpty()) {
            context.log(nodeId, "⚠️ 无板块历史数据（sector_daily_record 为空），请先导入板块数据或运行板块轮动引擎")
            return SectorStrengthResult(emptyList(), emptyList(), "", "无板块数据")
        }

        val dateRange = records.minOf { it.date } + " ~ " + records.maxOf { it.date }
        val byCode = records.groupBy { it.sectorCode }
        val stats = byCode.map { (code, recs) ->
            val name = recs.firstOrNull()?.sectorName ?: code
            val avgChange = recs.map { it.changePct }.average()
            val hotScore = recs.sumOf { it.hotScore }
            val inflow = recs.sumOf { it.mainNetInflow }
            val hotDays = recs.count { it.isHot == "S" || it.isHot == "A" }
            val composite = recs.map { it.compositeScore }.maxOrNull() ?: 0.0
            SectorStrength(code, name, avgChange, hotScore, inflow, hotDays, composite, 0)
        }
        val sorted = stats.sortedWith(
            compareByDescending<SectorStrength> { it.hotDays }
                .thenByDescending { it.compositeScore }
                .thenByDescending { it.avgChangePct }
                .thenByDescending { it.mainNetInflow }
        ).mapIndexed { idx, s -> s.copy(rank = idx + 1) }
        val top = sorted.take(topN)

        val sb = StringBuilder()
        sb.appendLine("📊 板块强弱监测（近 ${lookbackDays} 日 ${dateRange}）")
        sb.appendLine("最强板块 Top ${top.size}：")
        top.take(8).forEach { s ->
            sb.appendLine(
                "  #${s.rank} ${s.sectorName}  日均涨跌 ${"%.2f".format(s.avgChangePct)}%  " +
                    "热度 ${s.hotDays} 天  主力净流入 ${"%.1f".format(s.mainNetInflow / 1e8)} 亿"
            )
        }
        if (top.size > 8) sb.appendLine("  ... 共 ${top.size} 个板块上榜")
        sb.appendLine("热门板块代码: ${top.take(8).map { it.sectorCode }.joinToString(",")}")

        context.log(nodeId, sb.toString().trimEnd())
        context.recordStockFlow(
            nodeId = nodeId, nodeName = nodeName,
            inputCount = stats.size, outputCount = top.size,
            filterCount = 0,
            filterReason = "板块强弱排序 Top$topN",
            inputCodes = byCode.keys.take(5), outputCodes = top.take(5).map { it.sectorCode }
        )
        return SectorStrengthResult(top, top.map { it.sectorCode }, dateRange, sb.toString().trim())
    }
}

// ═══════════════════════════════════════════════════════════════
//  风格轮动判断（style_rotation）
// ═══════════════════════════════════════════════════════════════

/** 风格轮动判断结果 */
data class StyleRotationResult(
    val styleLabel: String,
    val leadingSectors: List<String>,
    val suggestedPeriod: String,
    val riskLevel: String,
    val summary: String
)

/**
 * ## 风格轮动判断节点
 *
 * 综合大盘环境（a_market_analysis 输出）+ 板块强弱（sector_strength 输出），
 * 判断当前市场风格：题材轮动活跃 / 成长进攻 / 价值防守 / 周期共振 / 均衡震荡，
 * 并给出建议持仓周期与风险等级。
 *
 * XML 用法：`<node module="style_rotation" />`
 *
 * 输出 [StyleRotationResult]，存入 context.stageOutputs["n_style_rotation"]。
 * 依赖前置节点：n_a_market（a_market_analysis）、n_sector_strength（sector_strength）。
 */
class StyleRotationNode : BaseNode<Any, StyleRotationResult>("n_style_rotation", "风格轮动判断", NodeType.FACTOR_COMPUTE) {

    override suspend fun execute(context: PipelineContext, input: Any): StyleRotationResult {
        // 1. 读取大盘环境（若公共 pipeline 中 a_market_analysis 先行）
        val market = context.getStageOutput<AMarketAnalysisEngine.MarketAnalysisResult>("n_a_market")

        // 2. 读取板块强弱（若 sector_strength 先行）
        val sector = context.getStageOutput<SectorStrengthResult>("n_sector_strength")

        val styleLabel: String
        val riskLevel: String
        val suggestedPeriod: String
        val leadingSectors = sector?.topSectors?.take(5)?.map { it.sectorName } ?: emptyList()

        if (market != null) {
            when {
                market.isTopDanger -> {
                    styleLabel = "高位风险（防守观望）"
                    riskLevel = "高"
                    suggestedPeriod = "观望/空仓等待"
                }
                market.isBottomConfirmed -> {
                    styleLabel = "底部确认（进攻布局）"
                    riskLevel = "低"
                    suggestedPeriod = market.suggestedPeriod.name
                }
                !market.isTrendUp && market.marketTemp == "冷" -> {
                    styleLabel = "弱势防守（价值防御）"
                    riskLevel = "中高"
                    suggestedPeriod = "长线/高股息防御"
                }
                market.isTrendUp -> {
                    styleLabel = "趋势上行（顺势进攻）"
                    riskLevel = "中低"
                    suggestedPeriod = market.suggestedPeriod.name
                }
                else -> {
                    styleLabel = "均衡震荡（结构性行情）"
                    riskLevel = "中"
                    suggestedPeriod = "超短/短线快进快出"
                }
            }
        } else {
            styleLabel = "均衡震荡（结构性行情）"
            riskLevel = "中"
            suggestedPeriod = "超短/短线快进快出"
        }

        // 3. 叠加板块风格特征修正（强势板块命名特征 → 风格倾向）
        val leadingNames = leadingSectors.joinToString("")
        val styleHint = when {
            leadingNames.contains("银行") || leadingNames.contains("煤炭") ||
                leadingNames.contains("电力") || leadingNames.contains("保险") ||
                leadingNames.contains("石油") || leadingNames.contains("高速公路") -> "（偏价值/高股息防守）"
            leadingNames.contains("半导体") || leadingNames.contains("通信") ||
                leadingNames.contains("软件") || leadingNames.contains("电子") ||
                leadingNames.contains("传媒") || leadingNames.contains("游戏") -> "（偏成长/科技）"
            leadingNames.contains("有色") || leadingNames.contains("化工") ||
                leadingNames.contains("钢铁") || leadingNames.contains("基建") ||
                leadingNames.contains("地产") -> "（偏周期）"
            else -> ""
        }

        // 4. 季节性提示（文档：12-1月春耕备耕，2-4月主升浪，油价>80 煤化工强盈利）
        val month = java.time.LocalDate.now().monthValue
        val seasonHint = when (month) {
            12, 1 -> "📅 冬播春耕季：关注化肥/草甘膦/农化板块（12-1月备耕、2-4月主升浪）"
            2, 3, 4 -> "📅 春季主升浪窗口：题材活跃度提升，可适当提高短线参与度"
            5, 6, 7 -> "📅 年中震荡期：业绩窗口临近，回避纯题材炒作"
            8, 9 -> "📅 中报密集期：关注业绩确定性（银行/资源/高股息）"
            10, 11 -> "📅 四季度：关注低估值修复 + 来年春季行情预演"
            else -> ""
        }

        val sb = StringBuilder()
        sb.appendLine("🎨 风格轮动判断（5→1）：$styleLabel $styleHint")
        sb.appendLine("   风格候选池: ①题材轮动 ②成长进攻 ③价值防守 ④周期共振 ⑤均衡震荡")
        sb.appendLine("   综合大盘环境+板块强弱 → 命中: $styleLabel")
        sb.appendLine("   当前强势板块: ${if (leadingSectors.isEmpty()) "暂无" else leadingSectors.joinToString("、")}")
        sb.appendLine("   建议持仓周期: $suggestedPeriod")
        sb.appendLine("   风险等级: $riskLevel")
        if (seasonHint.isNotEmpty()) sb.appendLine("   $seasonHint")

        context.log(nodeId, sb.toString().trimEnd())
        context.recordStockFlow(
            nodeId = nodeId, nodeName = nodeName,
            inputCount = leadingSectors.size, outputCount = 1,
            filterCount = 0,
            filterReason = "风格轮动规则判断",
            inputCodes = sector?.topSectors?.take(3)?.map { it.sectorCode } ?: emptyList(),
            outputCodes = emptyList()
        )
        return StyleRotationResult(
            styleLabel = styleLabel + styleHint,
            leadingSectors = leadingSectors,
            suggestedPeriod = suggestedPeriod,
            riskLevel = riskLevel,
            summary = sb.toString().trim()
        )
    }
}

// ═══════════════════════════════════════════════════════════════
//  大盘方向研判（market_direction / n_adaptive）
// ═══════════════════════════════════════════════════════════════

/** 大盘方向研判结果 */
data class MarketDirectionResult(
    val direction: String,   // BULLISH / OSCILLATION / BEARISH
    val summary: String
)

/**
 * ## 大盘方向研判节点
 *
 * 将大盘多周期研判（n_a_market 的 MarketAnalysisResult）映射为三态方向：
 *   BULLISH（多头） / OSCILLATION（震荡） / BEARISH（空头）。
 *
 * 供 complete_closed_loop 第四层「周期×环境矩阵路由」的 if 条件使用：
 *   `<pipeline ... if="${n_adaptive}.direction == 'BULLISH'" />`
 * 此前该条件读取不存在的 n_adaptive 节点，导致 12 条周期流水线全部被跳过。
 *
 * XML 用法：`<node module="market_direction" />`
 * 依赖：n_a_market（a_market_analysis）。
 * 输出 [MarketDirectionResult]，存入 context.stageOutputs["n_adaptive"]。
 */
class MarketDirectionNode : BaseNode<Any, MarketDirectionResult>("n_adaptive", "大盘方向研判", NodeType.FACTOR_COMPUTE) {

    override suspend fun execute(context: PipelineContext, input: Any): MarketDirectionResult {
        val market = context.getStageOutput<AMarketAnalysisEngine.MarketAnalysisResult>("n_a_market")
        if (market == null) {
            val msg = "⚠ 大盘方向研判：缺少大盘研判输出（n_a_market 为空），默认 OSCILLATION"
            context.log(nodeId, msg)
            return MarketDirectionResult("OSCILLATION", msg)
        }

        val hotTemps = setOf("沸腾", "温和偏热")
        val coldTemps = setOf("冰点", "温和偏冷", "冷")

        val direction = when {
            // 顶部危险 / 底部确认 → 防御（空头或探底）
            market.isTopDanger || market.isBottomConfirmed -> "BEARISH"
            // 趋势向上 + 市场偏热 → 多头
            market.isTrendUp && market.marketTemp in hotTemps -> "BULLISH"
            // 市场冰冷 + 趋势向下 → 空头
            !market.isTrendUp && market.marketTemp in coldTemps -> "BEARISH"
            // 其余 → 震荡
            else -> "OSCILLATION"
        }

        val summary = "📡 大盘方向研判：${directionText(direction)}（趋势${if (market.isTrendUp) "上行" else "不明/下行"} · 温度 ${market.marketTemp} · " +
            "建议周期 ${market.suggestedPeriod.name} · 建议仓位 ${market.suggestedPositionPct}%）"
        context.log(nodeId, summary)

        context.recordStockFlow(
            nodeId = nodeId, nodeName = nodeName,
            inputCount = 1, outputCount = 1,
            filterCount = 0,
            filterReason = "大盘方向映射",
            inputCodes = emptyList(), outputCodes = emptyList()
        )
        return MarketDirectionResult(direction, summary)
    }

    private fun directionText(d: String) = when (d) {
        "BULLISH" -> "牛市（进攻）"
        "BEARISH" -> "熊市（防御）"
        else -> "震荡（均衡）"
    }
}
