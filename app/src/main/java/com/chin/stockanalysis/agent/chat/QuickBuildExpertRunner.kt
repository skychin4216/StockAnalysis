package com.chin.stockanalysis.agent.chat

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.agent.stock.StockAnalysisAgent
import com.chin.stockanalysis.agent.stock.StockAnalysisResult
import com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.HoldingPeriod
import com.chin.stockanalysis.strategy.Strategy
import com.chin.stockanalysis.strategy.StrategyEngineHolder
import com.chin.stockanalysis.strategy.sector.UserFocusSectorEntity
import com.chin.stockanalysis.strategy.trade.TradeOrder
import com.chin.stockanalysis.strategy.topology.pipelines.OrderGenerationResult
import com.chin.stockanalysis.strategy.topology.xml.UseCaseLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 板块多周期全面深度分析（EXPERT 模式）执行器。
 *
 * [analyzeSectorFocus]：当前对话框 EXPERT 入口。解析用户输入中的关注板块/个股 →
 * 定位板块 → 拉取成分股 → 选龙头前10（主板5 + 科创/创业5）→ 逐只 AI 分析（只分析不下单）→
 * 输出评分评价；若合适买入，提示「适合入手」。
 *
 * [run]：旧版「一键建仓」执行器（公共研判 + 四周期并行 seed 播种），与一键建仓 usecase 相互独立，
 * 当前无调用方，保留仅作历史参考。
 */
object QuickBuildExpertRunner {

    private const val TAG = "QuickBuildExpert"
    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    data class PeriodPick(val periodName: String, val orders: List<TradeOrder>, val summary: String)

    data class ExpertResult(
        val ok: Boolean,
        val message: String,
        val commonOk: Boolean = false,
        val periods: List<PeriodPick> = emptyList(),
        val totalElapsedMs: Long = 0L
    )

    /** 解析用户输入中的关注板块与股票代码，返回 (板块名列表, 股票代码列表)。 */
    suspend fun parseFocus(ctx: Context, text: String): Pair<List<String>, List<String>> {
        val codes = Regex("\\d{6}").findAll(text).map { it.value }.toSet()
        val codeReplaced = Regex("\\d{6}").replace(text, " ")
        val words = codeReplaced.split(Regex("[，,、；;\\s/。.（）()【】\\[\\]\"'“”]+"))
            .map { it.trim().removeSuffix("板块").removeSuffix("概念").removeSuffix("指数") }
            .filter { it.length in 2..8 }
        val db = StockDatabase.getInstance(ctx)
        val allSectorNames = try {
            db.sectorDailyRecordDao().getByDate(todayStr()).map { it.sectorName }
        } catch (e: Exception) {
            Log.w(TAG, "parseFocus 读取板块名失败: ${e.message}")
            emptyList()
        }
        val sectors = words.filter { w ->
            allSectorNames.any { it.contains(w) || w.contains(it) }
        }.distinct()
        return sectors to codes.toList()
    }

    /**
     * 执行一键建仓（公共研判一次 + 四周期专属 usecase 并行）。
     * @param focusSectors 注入用户关注板块（候选池优先匹配）
     * @param focusStocks  用户输入的股票代码（暂记录于汇总，候选池注入机制后续扩展）
     */
    suspend fun run(
        ctx: Context,
        focusSectors: List<String>,
        focusStocks: List<String>,
        onProgress: (String) -> Unit = {}
    ): ExpertResult {
        val t0 = System.currentTimeMillis()
        // 本函数在 IO 协程执行（UseCaseLoader/DAG 节点回调均在后台线程），
        // 而调用方 onProgress 常直接操作 UI（如 AI 对话框更新 RecyclerView）。
        // 统一切主线程回调，避免 "Only the original thread that created a view hierarchy can touch its views"。
        val mainOnProgress: (String) -> Unit = { msg ->
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate).launch {
                try {
                    onProgress(msg)
                } catch (e: Exception) {
                    Log.w(TAG, "onProgress 回调异常: ${e.message}")
                }
            }
        }
        return try {
            // ① 注入关注板块（user_focus_sectors 表 active=1，候选池/公共研判优先匹配）
            if (focusSectors.isNotEmpty()) {
                val dao = StockDatabase.getInstance(ctx).userFocusSectorDao()
                focusSectors.forEach { name ->
                    try {
                        dao.insert(UserFocusSectorEntity(sectorName = name, isActive = true))
                        dao.setActive(name, true)
                    } catch (e: Exception) {
                        Log.w(TAG, "注入关注板块 $name 失败: ${e.message}")
                    }
                }
                mainOnProgress("🧭 已注入关注板块: ${focusSectors.joinToString("、")}")
            }

            // ② 引擎初始化（注册全部周期 enabled 策略，useCase 按 holdingPeriod 过滤注入）
            val engine = StrategyEngineHolder.get()
            val strategies: List<Strategy> = HoldingPeriod.entries.flatMap { p ->
                try {
                    engine.getEnabledStrategiesByPeriod(p)
                } catch (e: Exception) {
                    Log.w(TAG, "加载 $p 策略失败: ${e.message}")
                    emptyList()
                }
            }.distinctBy { it.id }
            UseCaseLoader.init(ctx, strategies)
            val tradeDate = todayStr()

            // ③ 市场公共研判（只执行一次，结果播种给四周期并行 pipeline）
            mainOnProgress("① 市场公共研判（大盘→风格→板块）执行中...")
            val common = UseCaseLoader.run("common", tradeDate, onNodeProgress = { _, node ->
                mainOnProgress("公共研判 · $node")
            })
            if (!common.success) {
                return ExpertResult(
                    ok = false,
                    message = "❌ 市场公共研判失败：${common.errors.values.firstOrNull()?.take(80) ?: "未知错误"}",
                    totalElapsedMs = System.currentTimeMillis() - t0
                )
            }
            mainOnProgress("② 公共研判完成，四周期并行建仓中...")

            // ④ 四周期专属 usecase 并行（seed 播种，互不干扰）
            val periodIds = arrayOf("ultra_short_period", "short_term_period", "mid_term_period", "long_term_period")
            val periodNames = arrayOf("短线·极速", "短线", "中线", "长线")
            val results = coroutineScope {
                periodIds.indices.map { i ->
                    async {
                        val r = UseCaseLoader.run(
                            periodIds[i], tradeDate,
                            onNodeProgress = { _, node -> mainOnProgress("${periodNames[i]} · $node") },
                            seedStageOutputs = common.stageOutputs
                        )
                        val orderGen = r.stageOutputs["n_orders"] as? OrderGenerationResult
                        val orders = orderGen?.orders ?: emptyList()
                        val summary = if (r.success) "✅ ${orders.size} 单"
                        else "❌ ${r.errors.values.firstOrNull()?.take(40) ?: "失败"}"
                        PeriodPick(periodNames[i], orders, summary)
                    }
                }.awaitAll()
            }

            // ⑤ 汇总
            val totalOrders = results.sumOf { it.orders.size }
            val sb = StringBuilder()
            sb.appendLine("🧭 量化建仓专家 · 一键建仓完成")
            sb.appendLine("关注板块：${if (focusSectors.isEmpty()) "(无，按今日热门板块)" else focusSectors.joinToString("、")}")
            if (focusStocks.isNotEmpty()) sb.appendLine("关注个股：${focusStocks.joinToString("、")}")
            results.forEach { p ->
                sb.appendLine("• ${p.periodName}：${p.summary}")
                p.orders.take(8).forEach { o ->
                    sb.appendLine("   ${o.stockName}(${o.stockCode}) ${"%.2f".format(o.buyPrice)}×${o.quantity}  ${o.reason.take(28)}")
                }
                if (p.orders.size > 8) sb.appendLine("   ... 共 ${p.orders.size} 单")
            }
            sb.appendLine("⏱ 总耗时 ${(System.currentTimeMillis() - t0) / 1000.0}s，选中 ${totalOrders} 单")
            sb.appendLine("以上不构成投资建议")
            ExpertResult(
                ok = true,
                message = sb.toString(),
                commonOk = true,
                periods = results,
                totalElapsedMs = System.currentTimeMillis() - t0
            )
        } catch (e: Exception) {
            Log.e(TAG, "量化建仓专家执行异常", e)
            ExpertResult(
                ok = false,
                message = "❌ 量化建仓专家执行异常：${e.message?.take(80)}",
                totalElapsedMs = System.currentTimeMillis() - t0
            )
        }
    }

    /**
     * 🎯 板块龙头分析（EXPERT 模式新逻辑）：
     * 无论输入板块还是个股，都定位到板块 → 拉取板块成分股 →
     * 选前 10（5 主板 + 5 科创/创业，市值优先=龙头，剔除 ST/退市）→
     * 逐只 AI 分析（只分析不下单）→ 输出：是否适合入手 + 哪个更适合入手。
     */
    suspend fun analyzeSectorFocus(
        ctx: Context,
        focusSectors: List<String>,
        focusStocks: List<String>,
        onProgress: (String) -> Unit = {}
    ): ExpertResult {
        val t0 = System.currentTimeMillis()
        // onProgress 统一切主线程（调用方直接操作 UI）
        val mainOnProgress: (String) -> Unit = { msg ->
            CoroutineScope(Dispatchers.Main.immediate).launch {
                try { onProgress(msg) } catch (e: Exception) { Log.w(TAG, "onProgress 回调异常: ${e.message}") }
            }
        }
        return try {
            val db = StockDatabase.getInstance(ctx)
            // ① 确定目标板块：板块名优先；仅输入个股时按个股反查所属板块
            var sectorName = focusSectors.firstOrNull()
            if (sectorName.isNullOrBlank() && focusStocks.isNotEmpty()) {
                val owned = try { db.sectorStockDao().getSectorNamesByStockCode(focusStocks.first()) } catch (e: Exception) { emptyList() }
                sectorName = owned.firstOrNull()
                if (sectorName != null) mainOnProgress("🧭 个股 ${focusStocks.first()} 所属板块：$sectorName")
            }
            if (sectorName.isNullOrBlank()) {
                return ExpertResult(ok = false, message = "❌ 请提供板块名称或个股代码（如：输入「有色金属」或「600362」）")
            }

            // ② 板块名 → 板块代码（BKxxxx），优先精确匹配
            val allSectors = EastMoneyHotSectorSource.industrySectors + EastMoneyHotSectorSource.conceptSectors
            val block = allSectors.firstOrNull { it.name == sectorName }
                ?: allSectors.firstOrNull { it.name.contains(sectorName) || sectorName.contains(it.name) }
            if (block == null || block.code.isBlank()) {
                return ExpertResult(ok = false, message = "❌ 未找到板块「$sectorName」的行情代码，请换一个板块试试")
            }

            // ③ 拉取板块成分股行情（含市值/涨幅/换手/主力净流入）
            mainOnProgress("📡 拉取「${block.name}」成分股行情...")
            val all = try {
                EastMoneyHotSectorSource().fetchSectorLeaders(block.code, 60)
            } catch (e: Exception) {
                Log.w(TAG, "fetchSectorLeaders 失败: ${e.message}")
                return ExpertResult(ok = false, message = "❌ 拉取板块成分股失败：${e.message?.take(60)}")
            }
            if (all.isEmpty()) return ExpertResult(ok = false, message = "❌ 板块「$sectorName」暂无成分股数据")

            // ④ 分类：主板（60/00） vs 科创/创业（300/301/688/689），剔除 ST/退市
            fun isGemOrKcb(code: String) =
                code.startsWith("300") || code.startsWith("301") || code.startsWith("688") || code.startsWith("689")
            fun isMainBoardCode(code: String) =
                (code.startsWith("60") || code.startsWith("00")) && !isGemOrKcb(code)
            val clean = all.filter { s ->
                !s.name.contains("ST") && !s.name.contains("退") &&
                        (isMainBoardCode(s.code) || isGemOrKcb(s.code))
            }
            val rank: (List<EastMoneyHotSectorSource.LeaderStock>) -> List<EastMoneyHotSectorSource.LeaderStock> = { list ->
                list.sortedWith(compareByDescending<EastMoneyHotSectorSource.LeaderStock> { it.marketCap }.thenByDescending { it.changePercent })
            }
            val mainBoard = rank(clean.filter { isMainBoardCode(it.code) }).take(5)
            val gemKcb = rank(clean.filter { isGemOrKcb(it.code) }).take(5)
            val picks = (mainBoard + gemKcb).distinctBy { it.code }.take(10)
            if (picks.isEmpty()) {
                return ExpertResult(ok = false, message = "❌ 板块「$sectorName」无符合条件的成分股（主板/科创创业均无）")
            }

            // ⑤ 逐只 AI 分析（只分析、不下单）
            val needCodes = (picks.map { it.code } + focusStocks).distinct()
            val analyzed = mutableListOf<Pair<String, StockAnalysisResult?>>()
            needCodes.forEachIndexed { i, code ->
                val st = picks.firstOrNull { it.code == code }
                val cap = st?.let { if (it.marketCap > 0) " 市值${"%.0f".format(it.marketCap / 1e8)}亿" else "" } ?: ""
                mainOnProgress("🤖 分析 ${i + 1}/${needCodes.size}：${st?.name ?: code}$cap")
                val r = try { StockAnalysisAgent(ctx).analyze(code, st?.name) }
                catch (e: Exception) { Log.w(TAG, "分析 $code 失败: ${e.message}"); null }
                analyzed.add(code to r)
            }

            // ⑥ 汇总报告
            val sb = StringBuilder()
            sb.appendLine("🧭 板块多周期全面深度分析 · ${block.name}")
            sb.appendLine("主板 ${mainBoard.size} 只 + 科创/创业 ${gemKcb.size} 只（市值优先=龙头，剔除 ST/退市）")
            sb.appendLine("")
            val sorted = analyzed.sortedByDescending { it.second?.overallScore ?: -1 }
            sorted.forEach { (code, r) ->
                val st = picks.firstOrNull { it.code == code }
                if (r == null) {
                    sb.appendLine("• ${st?.name ?: code}($code) 分析失败")
                    return@forEach
                }
                val fit = when (r.recommendation) {
                    "BUY" -> "✅ 适合入手"
                    "HOLD" -> "⚖️ 观望"
                    else -> "👀 观察"
                }
                sb.appendLine("• ${st?.name ?: code}($code) 评分${r.overallScore} $fit")
                if (r.reasoning.isNotBlank()) sb.appendLine("   ${r.reasoning.take(60)}")
            }
            val top = sorted.filter { it.second != null }.take(3)
            if (top.isNotEmpty()) {
                sb.appendLine("")
                sb.appendLine("🏆 更适合入手 TOP3：${top.joinToString("、") { (c, r) -> "${picks.firstOrNull { it.code == c }?.name ?: c}（${r?.overallScore}分）" }}")
            }
            focusStocks.firstOrNull()?.let { userCode ->
                val r = analyzed.firstOrNull { it.first == userCode }?.second
                val st = picks.firstOrNull { it.code == userCode }
                if (r != null) {
                    val fit = when (r.recommendation) { "BUY" -> "适合入手"; "HOLD" -> "观望"; else -> "观察" }
                    sb.appendLine("")
                    sb.appendLine("📌 你关注的 ${st?.name ?: userCode}（$userCode）：评分 ${r.overallScore}，$fit（所属板块：$sectorName）")
                }
            }
            sb.appendLine("")
            sb.appendLine("⚠️ 本次仅为分析，未生成买入订单，不构成投资建议")
            sb.appendLine("⏱ 总耗时 ${(System.currentTimeMillis() - t0) / 1000.0}s")
            ExpertResult(ok = true, message = sb.toString(), totalElapsedMs = System.currentTimeMillis() - t0)
        } catch (e: Exception) {
            Log.e(TAG, "板块多周期全面深度分析异常", e)
            ExpertResult(ok = false, message = "❌ 板块多周期全面深度分析异常：${e.message?.take(80)}", totalElapsedMs = System.currentTimeMillis() - t0)
        }
    }

    private fun todayStr(): String = LocalDate.now().format(DATE_FMT)
}
