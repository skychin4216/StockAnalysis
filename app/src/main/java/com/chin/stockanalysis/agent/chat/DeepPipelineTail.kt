package com.chin.stockanalysis.agent.chat

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.AiSelectedStockEntity
import com.chin.stockanalysis.stock.database.AppBackgroundRunner
import com.chin.stockanalysis.strategy.HoldingPeriod
import com.chin.stockanalysis.strategy.Strategy
import com.chin.stockanalysis.strategy.StrategyEngineHolder
import com.chin.stockanalysis.strategy.topology.pipelines.OrderGenerationResult
import com.chin.stockanalysis.strategy.topology.xml.UseCaseLoader
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 「深度分析尾段」：多 Agent 深度分析完成后的 usecase 落地（DEEP 模式）。
 *
 * 流程（复用一键建仓的公共研判 + 周期 usecase pipeline，只做评估与保存，不下单）：
 * ① 解析用户输入中的关注板块/个股
 * ② 市场公共研判一次（common usecase）→ 判断大盘风格适合短线还是中线
 * ③ 短线 + 中线专属 usecase 并行（seed 播种），取通过选股的订单
 * ④ 把选中股票保存到 股票Tab→精选股票→AI 精选（交易时间可再一键建仓执行）
 */
object DeepPipelineTail {

    private const val TAG = "DeepPipelineTail"
    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /**
     * 执行深度尾段。无可用输入(无板块/无个股)或公共研判失败时返回空串（不打扰主流程）。
     * @return 追加到深度分析结果后的建仓建议文本
     */
    suspend fun run(
        ctx: Context,
        userText: String,
        onProgress: (String) -> Unit = {}
    ): String {
        val t0 = System.currentTimeMillis()
        return try {
            val (sectors, codes) = QuickBuildExpertRunner.parseFocus(ctx, userText)
            if (sectors.isEmpty() && codes.isEmpty()) {
                Log.i(TAG, "无可用板块/个股输入，跳过深度尾段")
                return ""
            }

            // ① 注入关注板块（user_focus_sectors 表 active=1，候选池/公共研判优先匹配）
            if (sectors.isNotEmpty()) {
                val dao = com.chin.stockanalysis.stock.database.StockDatabase.getInstance(ctx).userFocusSectorDao()
                sectors.forEach { name ->
                    try {
                        dao.insert(com.chin.stockanalysis.strategy.sector.UserFocusSectorEntity(sectorName = name, isActive = true))
                        dao.setActive(name, true)
                    } catch (e: Exception) {
                        Log.w(TAG, "注入关注板块 $name 失败: ${e.message}")
                    }
                }
            }

            // ② 引擎初始化（注册全部周期 enabled 策略）
            val engine = StrategyEngineHolder.get()
            val strategies: List<Strategy> = HoldingPeriod.entries.flatMap { p ->
                try { engine.getEnabledStrategiesByPeriod(p) } catch (e: Exception) { emptyList() }
            }.distinctBy { it.id }
            UseCaseLoader.init(ctx, strategies)
            val tradeDate = LocalDate.now().format(DATE_FMT)

            // ③ 市场公共研判（判断适合短线还是中线）
            onProgress("① 市场公共研判（大盘→风格→板块）执行中...")
            val common = UseCaseLoader.run("common", tradeDate, onNodeProgress = { _, node ->
                onProgress("公共研判 · $node")
            })
            if (!common.success) {
                Log.w(TAG, "公共研判失败，跳过深度尾段: ${common.errors.values.firstOrNull()}")
                return ""
            }
            onProgress("② 公共研判完成，短线/中线选股中...")

            // ④ 短线 + 中线 usecase 并行（seed 播种）
            val picks = coroutineScope {
                listOf(
                    "short_term_period" to "短线",
                    "mid_term_period" to "中线"
                ).map { (id, name) ->
                    async {
                        val r = UseCaseLoader.run(
                            id, tradeDate,
                            onNodeProgress = { _, node -> onProgress("$name · $node") },
                            seedStageOutputs = common.stageOutputs
                        )
                        val orderGen = r.stageOutputs["n_orders"] as? OrderGenerationResult
                        val orders = orderGen?.orders ?: emptyList()
                        name to orders
                    }
                }.awaitAll()
            }

            // ⑤ 保存到 AI 精选
            val allOrders = picks.flatMap { it.second }.distinctBy { it.stockCode }
            if (allOrders.isEmpty()) {
                onProgress("短线/中线均无通过选股的标的")
                return ""
            }
            val today = LocalDate.now().format(DATE_FMT)
            val entities = allOrders.map { o ->
                AiSelectedStockEntity(
                    stockCode = o.stockCode,
                    stockName = o.stockName,
                    source = "deep_pipeline",
                    selectedDate = today,
                    score = 80,
                    reason = o.reason.take(60)
                )
            }
            AppBackgroundRunner.saveAiSelectedStocks(ctx, entities)

            // ⑥ 汇总文本
            val elapsed = (System.currentTimeMillis() - t0) / 1000.0
            buildString {
                appendLine("📈 深度建仓建议 · 已保存 ${allOrders.size} 只到 AI 精选")
                picks.forEach { (name, orders) ->
                    appendLine("• $name：${orders.size} 只")
                    orders.take(6).forEach { o ->
                        appendLine("   ${o.stockName}(${o.stockCode}) ${"%.2f".format(o.buyPrice)}  ${o.reason.take(26)}")
                    }
                }
                appendLine("⏱ 尾段耗时 ${elapsed}s；交易时间可在 工作台→一键建仓 下单执行")
                appendLine("以上不构成投资建议")
            }
        } catch (e: Exception) {
            Log.e(TAG, "深度尾段异常", e)
            ""
        }
    }
}
