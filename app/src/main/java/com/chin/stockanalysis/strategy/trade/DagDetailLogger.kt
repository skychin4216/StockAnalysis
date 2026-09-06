package com.chin.stockanalysis.strategy.trade

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.topology.core.DagDetailBridge
import com.chin.stockanalysis.strategy.topology.core.FilterResult
import com.chin.stockanalysis.strategy.topology.core.MergedSignalPool
import com.chin.stockanalysis.strategy.topology.core.SignalPack
import com.chin.stockanalysis.strategy.topology.core.StockPool
import com.chin.stockanalysis.strategy.topology.pipelines.NewsGuardResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * ## 一键建仓详细日志（logcat）
 *
 * 在 DAG Pipeline 每个 Node 进入前 / 退出后，按【板块】分组打印股票明细，
 * 每个板块占一行（板块池设计为每板块 5 大票 + 5 中小盘，单行不会过长），
 * 并打印本节点被过滤掉的股票（输入 − 输出）。
 *
 * 实现说明：
 * - 经 [DagDetailBridge] 由引擎 DagPipeline.executeNode 调用（suspend，IO 线程）；
 * - 板块归属 / 股票名：首次使用时从 Room（sector_stocks + stock_basics）一次性
 *   全量加载到内存缓存，后续纯内存分组，不影响执行耗时；
 * - 只写 Logcat（TAG="一键建仓"），不修改 UI 节点日志，避免刷屏。
 */
object DagDetailLogger {

    private const val TAG = "一键建仓"

    /** 单行最大长度（字符）。超出拆行，避免 logcat 单行过长被截断。 */
    private const val MAX_LINE = 200

    /** 股票清单超过该数量时不逐只展开（按板块统计行数 + 每板块仅列前 40 只），
     *  避免全市场池触发时 logcat 刷屏。 */
    private const val MAX_PRINT_TOTAL = 400
    private const val MAX_PRINT_PER_SECTOR = 40

    @Volatile
    private var appContext: Context? = null

    private val mutex = Mutex()
    private var loaded = false

    /** code → 板块名（sector_stocks，每只股票一条） */
    private var sectorOf: Map<String, String> = emptyMap()

    /** code → 股票名（stock_basics） */
    private var nameOf: Map<String, String> = emptyMap()

    /**
     * 幂等挂载：一键建仓 runDagPipeline 入口调用一次。
     * 使用 application context，不持有 Activity，无泄漏。
     */
    fun ensureAttached(ctx: Context) {
        if (DagDetailBridge.isAttached) return
        appContext = ctx.applicationContext
        DagDetailBridge.hook = { pipeline, node, phase, input, output ->
            onNodeDetail(pipeline, node, phase, input, output)
        }
        Log.i(TAG, "📡 已挂接 DAG 节点详细日志（按板块打印输入/输出/被过滤股票）")
    }

    // ── UI 执行日志共用：板块/名称缓存 + 按板块格式化（与 logcat 详细日志同源） ──

    /** 执行前预热：确保板块/名称缓存已加载（全进程仅一次，IO 线程调用，幂等） */
    suspend fun ensureCached(ac: Context) {
        ensureLoaded(ac)
    }

    /** 从各类 Pipeline 数据包中提取股票代码列表（UI 执行日志“按板块展示”用） */
    fun extractStockCodes(v: Any?): List<String> = extractCodes(v)

    /**
     * 按板块把股票清单格式化为多行，供 UI 执行日志“先显示板块、再显示具体股票”：
     * 每行 = 一个板块及其股票明细；超过 [maxStocks] 的板块只列前几只并标注剩余。
     */
    fun formatStockBySector(
        codes: List<String>,
        maxStocks: Int = 8
    ): List<String> {
        if (codes.isEmpty()) return emptyList()
        val sector = sectorOf
        val names = nameOf
        val distinct = codes.distinct()
        val grouped = LinkedHashMap<String, MutableList<String>>()
        for (c in distinct) grouped.getOrPut(sector[c] ?: "其他") { mutableListOf() }.add(c)
        val lines = mutableListOf<String>()
        var shownCount = 0
        for ((secName, list) in grouped) {
            if (lines.size >= 15) break
            val shown = list.take(maxStocks)
            val stockText = shown.joinToString("  ") { c ->
                val n = names[c] ?: ""
                if (n.isNotEmpty()) "$c $n" else c
            }
            val tail = if (list.size > shown.size) "…等${list.size - shown.size}只" else ""
            lines.add("      【$secName】${list.size}只：$stockText$tail")
            shownCount += shown.size
        }
        val hidden = distinct.size - shownCount
        if (hidden > 0) {
            lines.add("      … 其余 ${hidden} 只（共 ${grouped.size} 个板块，仅列前 $shownCount 只）")
        }
        return lines
    }

    private suspend fun onNodeDetail(
        pipeline: String,
        nodeName: String,
        phase: DagDetailBridge.Phase,
        input: Any?,
        output: Any?
    ) {
        val ac = appContext ?: return
        try {
            ensureLoaded(ac)
            val inCodes = extractCodes(input)
            val outCodes = if (phase == DagDetailBridge.Phase.EXIT) extractCodes(output) else emptyList()

            when (phase) {
                DagDetailBridge.Phase.ENTER -> {
                    if (inCodes.isEmpty()) return
                    Log.i(TAG, "══════ 📡[$pipeline] ▶ 进入 Node「$nodeName」｜输入 ${inCodes.size} 只 ══════")
                    formatSectorLines(inCodes).forEach { Log.i(TAG, it) }
                }

                DagDetailBridge.Phase.EXIT -> {
                    if (inCodes.isEmpty() && outCodes.isEmpty()) return
                    val filtered = if (inCodes.isNotEmpty()) inCodes - outCodes.toSet() else emptyList()
                    Log.i(
                        TAG,
                        "══════ 📡[$pipeline] ✓ 退出 Node「$nodeName」｜" +
                            "输出 ${outCodes.size} 只 · 本节点过滤 ${filtered.size} 只 ══════"
                    )
                    if (outCodes.isNotEmpty()) {
                        Log.i(TAG, "   ↓ 输出股票（按板块）：")
                        formatSectorLines(outCodes).forEach { Log.i(TAG, it) }
                    }
                    if (filtered.isNotEmpty()) {
                        Log.i(TAG, "   ✗ 被过滤股票（按板块，输入−输出）：")
                        formatSectorLines(filtered).forEach { Log.i(TAG, it) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "详细日志异常（不影响执行）: ${e.message}")
        }
    }

    // ── 板块归属/股票名缓存 ────────────────────────────────

    private suspend fun ensureLoaded(ac: Context) {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            val db = StockDatabase.getInstance(ac)
            sectorOf = db.sectorStockDao().getAllStockSectorPairs()
                .associate { it.stock_code to it.sector_name }
            nameOf = db.stockBasicDao().getAll().associate { it.code to it.name }
            loaded = true
        }
    }

    // ── 数据包 → 股票代码 ──────────────────────────────────

    /** 从各类 Pipeline 数据包中递归提取股票代码列表。 */
    private fun extractCodes(v: Any?): List<String> = when (v) {
        null, is Unit -> emptyList()
        is StockPool -> v.stocks.map { it.code }
        is SignalPack -> v.signals.map { it.stockCode }
        is MergedSignalPool -> v.stockHits.keys.toList()
        is FilterResult -> v.passed.map { it.stockCode } + v.rejected.map { it.code }
        is NewsGuardResult -> v.passedCodes.toList()
        is StockRealtime -> listOf(v.code)
        is List<*> -> v.flatMap { extractCodes(it) }
        is Map<*, *> -> v.keys.mapNotNull { it as? String }
        else -> emptyList()
    }

    // ── 按板块分行 ─────────────────────────────────────────

    /** 将股票代码按板块分组，每个板块输出为一行（过长自动续行）。 */
    private fun formatSectorLines(codes: List<String>): List<String> {
        if (codes.isEmpty()) return emptyList()
        val distinct = codes.distinct()
        val truncate = distinct.size > MAX_PRINT_TOTAL
        val grouped = LinkedHashMap<String, MutableList<String>>()
        for (c in distinct) {
            grouped.getOrPut(sectorOf[c] ?: "其他") { mutableListOf() }.add(c)
        }
        val lines = mutableListOf<String>()
        for ((sector, list) in grouped) {
            val shown = if (truncate) list.take(MAX_PRINT_PER_SECTOR) else list
            val suffix = if (truncate && list.size > shown.size) "（仅列前${shown.size}只）" else ""
            val header = "   【$sector】(${list.size}只)$suffix"
            val stocks = shown.joinToString("  ") { c ->
                val name = nameOf[c] ?: ""
                if (name.isNotEmpty()) "$c $name" else c
            }
            if (stocks.length <= MAX_LINE - header.length) {
                lines.add("$header $stocks")
            } else {
                lines.add(header)
                val buf = StringBuilder("        ")
                for (c in shown) {
                    val name = nameOf[c] ?: ""
                    val item = (if (name.isNotEmpty()) "$c $name" else c) + "  "
                    if (buf.length + item.length > MAX_LINE) {
                        lines.add(buf.toString().trimEnd())
                        buf.setLength(0)
                        buf.append("        ")
                    }
                    buf.append(item)
                }
                if (buf.toString().trim().isNotEmpty()) lines.add(buf.toString().trimEnd())
            }
        }
        return lines
    }
}
