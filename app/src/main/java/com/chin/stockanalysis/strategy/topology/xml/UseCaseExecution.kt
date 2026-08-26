package com.chin.stockanalysis.strategy.topology.xml

import android.content.Context
import com.chin.stockanalysis.strategy.HoldingPeriod
import com.chin.stockanalysis.strategy.Strategy
import com.chin.stockanalysis.strategy.StrategyEngineHolder
import com.chin.stockanalysis.strategy.backtest.PeriodDeepAnalysisRunner
import com.chin.stockanalysis.strategy.models.StrategySignal
import com.chin.stockanalysis.strategy.topology.core.MergedSignalPool
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
     * 个股豆包深度分析（详情页「深度分析」/ AI 对话框 个股分析统一入口）。
     * 运行 stock_deep_analysis useCase（市场研判 + 四周期深度打分），
     * 通过 seedStageOutputs 注入当前个股，须在 IO 协程中调用。
     */
    suspend fun runStockDeepAnalysis(ctx: Context, stockCode: String): PeriodDeepAnalysisRunner.Result {
        return try {
            initLoader(ctx)
            val tradeDate = TradingDayPickerView.recentTradingDay().format(dateFmt)
            val result = UseCaseLoader.run(
                useCaseId = "stock_deep_analysis",
                tradeDate = tradeDate,
                seedStageOutputs = mapOf("n_target" to stockCode)
            )
            val pr = result.pipelineResults.values
                .flatMap { it.stageResults.values }
                .mapNotNull { it.output as? PeriodDeepAnalysisRunner.Result }
                .firstOrNull()
            pr ?: PeriodDeepAnalysisRunner.Result(
                items = emptyList(), bestPeriod = null, overallScore = 0, recommendation = "HOLD",
                report = "❌ 深度分析未产出结果" + if (result.errors.isNotEmpty()) "：${result.errors.values.joinToString("; ")}" else "",
                elapsedMs = result.totalElapsedMs
            )
        } catch (e: Exception) {
            PeriodDeepAnalysisRunner.Result(
                items = emptyList(), bestPeriod = null, overallScore = 0, recommendation = "HOLD",
                report = "❌ 深度分析失败：${e.message}", elapsedMs = 0
            )
        }
    }

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

            // 跳过步骤检测（if 条件未命中的 pipeline，如方向路由）
            val skippedSteps = try {
                PipelineXmlParser.loadUseCaseFromAssets(ctx, "usecases/${useCaseId}_usecase.xml")
                    ?.steps?.filter { st ->
                        when (st) {
                            is PipelineXmlParser.StepRef.pipeline -> {
                                val key = st.name.ifBlank { st.ref.substringAfterLast("/") }
                                result.pipelineResults.keys.none { it == key || it.contains(key) || key.contains(it) }
                            }
                            is PipelineXmlParser.StepRef.node -> false
                        }
                    } ?: emptyList()
            } catch (e: Exception) { emptyList() }
            if (skippedSteps.isNotEmpty()) {
                sb.appendLine("⏭ 跳过 ${skippedSteps.size} 个步骤（条件未满足）：${skippedSteps.joinToString("、") {
                    when (it) {
                        is PipelineXmlParser.StepRef.pipeline -> it.name.ifBlank { it.ref.substringAfterLast("/") }
                        is PipelineXmlParser.StepRef.node -> it.id
                    }
                }}")
                sb.appendLine("")
            }

            var orderTotal = 0
            val orderSb = StringBuilder()
            val intermediateSb = StringBuilder()
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
                // 中间选股结果（无订单时用于定位：是没选出股票，还是选出了但未下单）
                pr.stageResults["n_merge"]?.output?.let { out ->
                    if (out is MergedSignalPool && out.boostedSignals.isNotEmpty()) {
                        intermediateSb.appendLine("📊 ${pr.pipelineName}·信号合并: ${out.boostedSignals.size} 只（${out.boostedSignals.take(8).joinToString("、") { it.stockName }}）")
                    }
                }
                pr.stageResults["n_pool"]?.output?.let { out ->
                    if (out is List<*> && out.isNotEmpty() && out.first() is StrategySignal) {
                        intermediateSb.appendLine("📊 ${pr.pipelineName}·候选池: ${out.size} 只")
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
                if (intermediateSb.isNotEmpty()) {
                    sb.appendLine("🔍 本次无买入信号，各步骤中间结果：")
                    sb.append(intermediateSb)
                } else {
                    val finalOut = result.finalOutput?.toString().orEmpty().take(800)
                    sb.appendLine("📋 最终输出：${if (finalOut.isBlank()) "无（未选出股票，请检查数据日期与候选池）" else finalOut}")
                }
            }
            if (result.errors.isNotEmpty()) {
                sb.appendLine("")
                result.errors.forEach { (k, v) -> sb.appendLine("❌ $k: $v") }
            }
            sb.appendLine("")
            sb.appendLine("⏱ 总耗时 ${result.totalElapsedMs / 1000.0}s")
            // 慢步骤耗时（帮助定位卡顿点）
            result.pipelineResults.values.filter { it.totalElapsedMs > 5000 }
                .sortedByDescending { it.totalElapsedMs }
                .forEach { sb.appendLine("  ⏳ ${it.pipelineName}: ${it.totalElapsedMs / 1000.0}s") }
            sb.toString()
        } catch (e: Exception) {
            "❌ 执行异常：${e.message?.take(200)}"
        }
    }
}
