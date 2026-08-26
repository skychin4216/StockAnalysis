package com.chin.stockanalysis.strategy.topology.xml

import android.content.Context
import com.chin.stockanalysis.strategy.HoldingPeriod
import com.chin.stockanalysis.strategy.Strategy
import com.chin.stockanalysis.strategy.StrategyEngineHolder
import com.chin.stockanalysis.strategy.topology.pipelines.OrderGenerationResult
import com.chin.stockanalysis.ui.TradingDayPickerView
import java.time.format.DateTimeFormatter

/**
 * UseCase 统一执行入口（选股系统 / 策略Tab·平台策略 共用）。
 *
 * 保证所有选股/交易方案都通过 [UseCaseLoader] → pipeline(DAG) 构造并执行，
 * 供 一键建仓 / 实仓 / AI 对话框 / 平台策略 等入口复用同一套初始化与结果汇总逻辑。
 */
object UseCaseExecution {

    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** 周期专属/实仓专属 usecase：不进入「平台策略」分组 */
    private val PERIOD_AND_HOLDING_IDS = setOf(
        "real_holding", "ultra_short", "short_term", "mid_term", "long_term"
    )

    /** 初始化 UseCaseLoader：注入全周期启用策略（与一键建仓保持一致） */
    fun initLoader(ctx: Context) {
        val strategies: List<Strategy> = HoldingPeriod.entries.flatMap { p ->
            try { StrategyEngineHolder.get().getEnabledStrategiesByPeriod(p) } catch (e: Exception) { emptyList() }
        }.distinctBy { it.id }
        UseCaseLoader.init(ctx, strategies)
    }

    /**
     * 平台策略分组：非周期、非实仓的独立选股方案（豆包完整闭环置顶）。
     * 后续 deepseek / 元宝 等新方案只需在 assets/usecases/ 新增 _usecase.xml，即自动出现在该分组。
     */
    fun listPlatformUseCases(): List<UseCaseLoader.UseCaseInfo> =
        UseCaseLoader.listUseCases()
            .filter {
                it.id != "common" &&
                    !it.id.endsWith("_period") &&
                    it.id !in PERIOD_AND_HOLDING_IDS
            }
            .sortedBy { if (it.id == "complete_closed_loop") 0 else 1 }

    /**
     * 执行指定 UseCase 并汇总结果文本（须在 IO 协程中调用）。
     *
     * @param useCaseId UseCase 唯一 ID
     * @param onNodeProgress 节点进度回调 (pipelineName, nodeName)
     * @return 可展示的汇总文本；未找到/异常时返回 ❌ 前缀文本
     */
    suspend fun runAndSummarize(
        ctx: Context,
        useCaseId: String,
        onNodeProgress: (pipelineName: String, nodeName: String) -> Unit = { _, _ -> }
    ): String {
        val info = UseCaseLoader.listUseCases().firstOrNull { it.id == useCaseId }
            ?: return "❌ 未找到选股方案: $useCaseId"
        return try {
            initLoader(ctx)
            val tradeDate = TradingDayPickerView.recentTradingDay().format(dateFmt)
            val result = UseCaseLoader.run(
                useCaseId = useCaseId,
                tradeDate = tradeDate,
                onNodeProgress = onNodeProgress
            )
            val sb = StringBuilder()
            sb.appendLine(if (result.success) "✅ ${info.name} 执行完成" else "⚠️ ${info.name} 部分步骤未成功")
            sb.appendLine("")
            var orderTotal = 0
            val orderSb = StringBuilder()
            result.pipelineResults.values.forEach { pr ->
                if (pr.stageResults["n_orders"]?.output is OrderGenerationResult) {
                    val og = pr.stageResults["n_orders"]!!.output as OrderGenerationResult
                    if (og.orders.isNotEmpty()) {
                        orderTotal += og.orders.size
                        og.orders.forEach { o ->
                            orderSb.appendLine("• ${o.stockCode} ${o.stockName}（评分 ${o.scoreAtBuy}）")
                        }
                    }
                }
                if (pr.errors.isNotEmpty()) {
                    pr.errors.forEach { (k, v) -> sb.appendLine("⚠️ ${pr.pipelineName} $k: $v") }
                }
            }
            if (orderTotal > 0) {
                sb.appendLine("🛒 共 ${orderTotal} 个买入信号：")
                sb.append(orderSb)
            } else if (result.success) {
                val finalOut = result.finalOutput?.toString().orEmpty().take(800)
                sb.appendLine("📋 最终输出：${if (finalOut.isBlank()) "无（可查看上方进度中的中间结果）" else finalOut}")
            }
            if (result.errors.isNotEmpty()) {
                sb.appendLine("")
                result.errors.forEach { (k, v) -> sb.appendLine("❌ $k: $v") }
            }
            sb.appendLine("")
            sb.appendLine("⏱ 总耗时 ${result.totalElapsedMs / 1000.0}s")
            sb.toString()
        } catch (e: Exception) {
            "❌ 执行异常：${e.message?.take(200)}"
        }
    }
}
