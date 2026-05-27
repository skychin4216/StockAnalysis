package com.chin.stockanalysis.stock.data.sources

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 东方财富 ETF 市场 & 板块资金流向数据源
 *
 * 数据来源：
 * - 板块资金流向：push2.eastmoney.com/api/qt/clist/get
 * - ETF 实时行情：push2.eastmoney.com/api/qt/ulist.np/get
 *
 * 功能：
 * 1. 获取 A 股主要板块的资金流入流出排行
 * 2. 获取 ETF 实时行情
 * 3. 按板块分类，各板块 Top 10 个股
 * 4. 资金流向分析（主力净流入/流出）
 */
object EftMarketDataSource {

    private const val TAG = "EftMarketDS"
    private const val TIMEOUT = 15L

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT, TimeUnit.SECONDS)
        .build()

    // ════════════════════════════════════════
    // 数据模型
    // ════════════════════════════════════════

    data class SectorFlow(
        val sectorName: String,        // 板块名称
        val sectorCode: String,        // 板块代码
        val mainNetInflow: Double,     // 主力净流入（亿元）
        val totalAmount: Double,       // 成交额（亿元）
        val changePercent: Double,     // 涨跌幅 %
        val topStocks: List<StockBrief> = emptyList()  // 板块内前 10 股票
    )

    data class StockBrief(
        val code: String,              // 股票代码
        val name: String,              // 股票名称
        val currentPrice: Double,      // 最新价
        val changePercent: Double,     // 涨跌幅
        val mainNetInflow: Double,     // 主力净流入（万元）
        val totalAmount: Double        // 成交额（万元）
    )

    data class EtfInfo(
        val code: String,
        val name: String,
        val currentPrice: Double,
        val changePercent: Double,
        val totalAmount: Double,       // 成交额（万元）
        val fundFlow: String           // 资金方向："流入"/"流出"/"持平"
    )

    data class MarketSummary(
        val sectors: List<SectorFlow>,
        val topEtfs: List<EtfInfo>,
        val updateTime: String
    )

    // ════════════════════════════════════════
    // 公开 API
    // ════════════════════════════════════════

    /**
     * 获取完整的市场概览：板块排行 + Top ETF
     */
    suspend fun getMarketSummary(): MarketSummary = withContext(Dispatchers.IO) {
        val sectors = fetchSectorFlows()
        val etfs = fetchTopEtfs()
        MarketSummary(
            sectors = sectors.take(15),
            topEtfs = etfs.take(10),
            updateTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date())
        )
    }

    /**
     * 获取板块资金流向排行（取资金净流入排序）
     */
    suspend fun fetchSectorFlows(): List<SectorFlow> = withContext(Dispatchers.IO) {
        try {
            // 东方财富行业板块资金流向
            val url = buildString {
                append("https://push2.eastmoney.com/api/qt/clist/get?")
                append("pn=1&pz=30&po=1&np=1&fltt=2&invt=2&fid=f62")
                append("&fs=m:90+t:2")
                append("&fields=f12,f14,f2,f3,f62,f184,f66")
            }

            val response = fetchJson(url)
            val data = response.optJSONObject("data")
            if (data == null) {
                Log.w(TAG, "板块数据为空，尝试备用 API")
                return@withContext fetchFallbackSectors()
            }

            val items = data.optJSONArray("diff") ?: JSONArray()
            val sectors = mutableListOf<SectorFlow>()
            for (i in 0 until minOf(items.length(), 30)) {
                val item = items.getJSONObject(i)
                sectors.add(
                    SectorFlow(
                        sectorName = item.optString("f14", ""),
                        sectorCode = item.optString("f12", ""),
                        mainNetInflow = item.optDouble("f62", 0.0) / 10000,  // 元→亿元
                        totalAmount = item.optDouble("f66", 0.0) / 10000,
                        changePercent = item.optDouble("f3", 0.0)
                    )
                )
            }
            Log.d(TAG, "✅ 获取 ${sectors.size} 个板块数据")
            sectors
        } catch (e: Exception) {
            Log.e(TAG, "获取板块数据失败: ${e.message}")
            fetchFallbackSectors()
        }
    }

    /**
     * 获取板块内 Top 10 股票（按成交额排序）
     */
    suspend fun fetchTopStocksInSector(sectorCode: String): List<StockBrief> = withContext(Dispatchers.IO) {
        try {
            val url = buildString {
                append("https://push2.eastmoney.com/api/qt/clist/get?")
                append("pn=1&pz=10&po=1&np=1&fltt=2&invt=2&fid=f66")
                append("&fs=b:$sectorCode+f:!50")  // 去掉 ST
                append("&fields=f12,f14,f2,f3,f62,f66")
            }

            val response = fetchJson(url)
            val data = response.optJSONObject("data")
            val items = data?.optJSONArray("diff") ?: JSONArray()
            val stocks = mutableListOf<StockBrief>()
            for (i in 0 until minOf(items.length(), 10)) {
                val item = items.getJSONObject(i)
                stocks.add(
                    StockBrief(
                        code = item.optString("f12", ""),
                        name = item.optString("f14", ""),
                        currentPrice = item.optDouble("f2", 0.0),
                        changePercent = item.optDouble("f3", 0.0),
                        mainNetInflow = item.optDouble("f62", 0.0),
                        totalAmount = item.optDouble("f66", 0.0)
                    )
                )
            }
            stocks
        } catch (e: Exception) {
            Log.e(TAG, "获取板块股票失败: ${e.message}")
            emptyList()
        }
    }

    /**
     * 获取热门 ETF 排行
     */
    suspend fun fetchTopEtfs(): List<EtfInfo> = withContext(Dispatchers.IO) {
        try {
            val url = buildString {
                append("https://push2.eastmoney.com/api/qt/clist/get?")
                append("pn=1&pz=15&po=1&np=1&fltt=2&invt=2&fid=f66")
                append("&fs=b:MK0021+b:MK0022+b:MK0023")  // ETF分类
                append("&fields=f12,f14,f2,f3,f62,f66")
            }

            val response = fetchJson(url)
            val data = response.optJSONObject("data")
            val items = data?.optJSONArray("diff") ?: JSONArray()
            val etfs = mutableListOf<EtfInfo>()
            for (i in 0 until minOf(items.length(), 15)) {
                val item = items.getJSONObject(i)
                val inflow = item.optDouble("f62", 0.0)
                etfs.add(
                    EtfInfo(
                        code = item.optString("f12", ""),
                        name = item.optString("f14", ""),
                        currentPrice = item.optDouble("f2", 0.0),
                        changePercent = item.optDouble("f3", 0.0),
                        totalAmount = item.optDouble("f66", 0.0),
                        fundFlow = when {
                            inflow > 1000 -> "流入"
                            inflow < -1000 -> "流出"
                            else -> "持平"
                        }
                    )
                )
            }
            etfs
        } catch (e: Exception) {
            Log.e(TAG, "获取 ETF 数据失败: ${e.message}")
            emptyList()
        }
    }

    // ════════════════════════════════════════
    // AI 分析
    // ════════════════════════════════════════

    /**
     * 生成板块方向的 AI 分析 prompt（基于 ETF 资金流动）
     */
    fun buildEtfAnalysisPrompt(sectors: List<SectorFlow>, etfs: List<EtfInfo>): String {
        if (sectors.isEmpty() && etfs.isEmpty()) return "暂无 ETF/板块数据"

        return buildString {
            appendLine("【板块资金流向 + ETF 数据分析】")
            appendLine()

            // 板块排行
            appendLine("一、板块资金净流入排行（主力资金）：")
            val sorted = sectors.sortedByDescending { it.mainNetInflow }
            for ((i, s) in sorted.take(10).withIndex()) {
                val arrow = if (s.mainNetInflow >= 0) "🔴" else "🟢"
                appendLine("${i + 1}. ${s.sectorName} 净流入${String.format("%.2f", s.mainNetInflow)}亿元 成交${String.format("%.2f", s.totalAmount)}亿元 涨跌${String.format("%.2f", s.changePercent)}% $arrow")
            }

            // ETF 流向
            if (etfs.isNotEmpty()) {
                appendLine()
                appendLine("二、热门 ETF 资金动向：")
                for (etf in etfs.take(10)) {
                    val signal = when (etf.fundFlow) {
                        "流入" -> "📈 资金流入"
                        "流出" -> "📉 资金流出"
                        else -> "➡️ 持平"
                    }
                    appendLine("${etf.name}(${etf.code}) ${String.format("%.2f", etf.currentPrice)} ${String.format("%.2f", etf.changePercent)}% 成交${String.format("%.0f", etf.totalAmount)}万 $signal")
                }
            }

            appendLine()
            appendLine("请基于以上数据，分析当前市场板块方向：")
            appendLine("1. 哪些板块受到主力资金青睐？")
            appendLine("2. ETF 资金流向是否印证板块热点？")
            appendLine("3. 给出 2-3 个值得关注的板块及原因")
        }
    }

    // ════════════════════════════════════════
    // 内部方法
    // ════════════════════════════════════════

    private fun fetchJson(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "Mozilla/5.0")
            .addHeader("Referer", "https://quote.eastmoney.com/")
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: "{}"
        return JSONObject(body)
    }

    private fun fetchFallbackSectors(): List<SectorFlow> {
        // 备用：使用概念板块
        return try {
            val url = buildString {
                append("https://push2.eastmoney.com/api/qt/clist/get?")
                append("pn=1&pz=20&po=1&np=1&fltt=2&invt=2&fid=f62")
                append("&fs=m:90+t:3")
                append("&fields=f12,f14,f2,f3,f62,f184,f66")
            }
            val response = fetchJson(url)
            val data = response.optJSONObject("data")
            val items = data?.optJSONArray("diff") ?: return emptyList()
            val sectors = mutableListOf<SectorFlow>()
            for (i in 0 until minOf(items.length(), 20)) {
                val item = items.getJSONObject(i)
                sectors.add(
                    SectorFlow(
                        sectorName = item.optString("f14", ""),
                        sectorCode = item.optString("f12", ""),
                        mainNetInflow = item.optDouble("f62", 0.0) / 10000,
                        totalAmount = item.optDouble("f66", 0.0) / 10000,
                        changePercent = item.optDouble("f3", 0.0)
                    )
                )
            }
            sectors
        } catch (e: Exception) {
            Log.e(TAG, "备用板块数据也失败: ${e.message}")
            emptyList()
        }
    }
}