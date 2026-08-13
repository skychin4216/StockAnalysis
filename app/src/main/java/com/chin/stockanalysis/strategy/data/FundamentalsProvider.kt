package com.chin.stockanalysis.strategy.data

import android.util.Log
import com.chin.stockanalysis.config.DataConfig
import com.chin.stockanalysis.stock.data.HttpClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * ## 基本面批量数据源 (FundamentalsProvider)
 *
 * daily_snapshot 的 OHLCV 来自 K 线 API，不含估值/财务字段。
 * 本对象提供两个批量接口，供 [HistoricalDataFetcher]（同步时持久化）
 * 与 [StrategyDataFeed]（运行时兜底）共用：
 *
 *  1. 行情批量（push2 ulist）：PE(f9) / 市值(f20) / PB(f23) / ROE(f37) / 换手(f8)
 *     —— 复用 EastMoneyStockSource.fetchRealtime，此处不重复实现
 *  2. 财务批量（datacenter RPT_F10_FINANCE_MAINFINADATA）：
 *     ROEJQ(ROE加权) / XSMLL(毛利率) / ZCFZL(资产负债率) / NETCASH_OPERATE_PK(经营现金流)
 *     —— 按报告期倒序分页拉取，每股取最新一期，覆盖全市场仅需 ~14 页
 */
object FundamentalsProvider {

    private const val TAG = "FundamentalsProvider"
    private val client = HttpClientProvider.realtimeClient

    /** 单股财务行（最新报告期） */
    data class FinanceRow(
        val roe: Double = 0.0,               // ROE加权 %
        val grossMargin: Double = 0.0,       // 销售毛利率 %
        val debtToAsset: Double = 0.0,       // 资产负债率 %
        val operatingCashFlow: Double = 0.0  // 经营现金流净额(元)
    )

    /** 单股单报告期财务行（用于历史回填） */
    data class HistoricalFinanceRow(
        val reportDate: String,              // 报告期 YYYY-MM-DD (如 2026-03-31)
        val roe: Double = 0.0,
        val grossMargin: Double = 0.0,
        val debtToAsset: Double = 0.0,
        val operatingCashFlow: Double = 0.0
    )

    /**
     * 批量拉取全市场最新财务数据。
     *
     * @param neededCodes 需要的股票代码（sh/sz/bj 前缀格式）；全部命中后提前结束分页。
     *                    null = 不限制（扫满 maxPages）
     * @param maxPages    最大分页数（一页 500 条，14 页 ≈ 覆盖最新一个完整季报期）
     */
    suspend fun fetchBulkFinance(
        neededCodes: Collection<String>? = null,
        maxPages: Int = 14
    ): Map<String, FinanceRow> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, FinanceRow>()
        val needed = neededCodes?.toSet()
        try {
            for (page in 1..maxPages) {
                val url = "${DataConfig.eastmoneyDatacenter}?" +
                        "reportName=RPT_F10_FINANCE_MAINFINADATA" +
                        "&columns=SECURITY_CODE,REPORT_DATE,ROEJQ,XSMLL,ZCFZL,NETCASH_OPERATE_PK" +
                        "&pageNumber=$page&pageSize=500&sortColumns=REPORT_DATE&sortTypes=-1"
                val body = executeGet(url) ?: break
                val data = JSONObject(body).optJSONObject("result")?.optJSONArray("data")
                if (data == null || data.length() == 0) break

                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val raw = item.optString("SECURITY_CODE")
                    if (raw.length != 6) continue
                    val code = normalizeCode(raw)
                    // 按报告期倒序排列，首次出现即最新一期
                    if (code !in result) {
                        result[code] = FinanceRow(
                            roe = item.optDouble("ROEJQ"),
                            grossMargin = item.optDouble("XSMLL"),
                            debtToAsset = item.optDouble("ZCFZL"),
                            operatingCashFlow = item.optDouble("NETCASH_OPERATE_PK")
                        )
                    }
                }
                if (needed != null && result.keys.containsAll(needed)) break
            }
            Log.i(TAG, "财务批量拉取: ${result.size} 只 (pages<=${maxPages})")
        } catch (e: Exception) {
            Log.w(TAG, "财务批量拉取异常: ${e.message}")
        }
        result
    }

    /**
     * 批量拉取多报告期财务数据（用于历史基本面回填）。
     *
     * 与 fetchBulkFinance 不同，本方法保留每只股票的多个报告期数据，
     * 而非仅取最新一期。
     *
     * @param maxPages 最大分页数（一页 500 条，20 页 ≈ 覆盖 ~2 年季报）
     * @param maxPeriodsPerStock 每只股票最多保留的报告期数（默认 8 ≈ 2 年）
     * @return Map<stockCode, List<HistoricalFinanceRow>>，按报告期倒序
     */
    suspend fun fetchBulkFinanceHistory(
        maxPages: Int = 20,
        maxPeriodsPerStock: Int = 8
    ): Map<String, List<HistoricalFinanceRow>> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, MutableList<HistoricalFinanceRow>>()
        try {
            for (page in 1..maxPages) {
                val url = "${DataConfig.eastmoneyDatacenter}?" +
                        "reportName=RPT_F10_FINANCE_MAINFINADATA" +
                        "&columns=SECURITY_CODE,REPORT_DATE,ROEJQ,XSMLL,ZCFZL,NETCASH_OPERATE_PK" +
                        "&pageNumber=$page&pageSize=500&sortColumns=REPORT_DATE&sortTypes=-1"
                val body = executeGet(url) ?: break
                val data = JSONObject(body).optJSONObject("result")?.optJSONArray("data")
                if (data == null || data.length() == 0) break

                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val raw = item.optString("SECURITY_CODE")
                    if (raw.length != 6) continue
                    val code = normalizeCode(raw)
                    val reportDate = item.optString("REPORT_DATE", "").take(10)
                    if (reportDate.isEmpty()) continue

                    val list = result.getOrPut(code) { mutableListOf() }
                    if (list.size >= maxPeriodsPerStock) continue
                    list.add(HistoricalFinanceRow(
                        reportDate = reportDate,
                        roe = item.optDouble("ROEJQ"),
                        grossMargin = item.optDouble("XSMLL"),
                        debtToAsset = item.optDouble("ZCFZL"),
                        operatingCashFlow = item.optDouble("NETCASH_OPERATE_PK")
                    ))
                }
                // 所有股票都已收集满则提前结束
                if (result.isNotEmpty() && result.values.all { it.size >= maxPeriodsPerStock }) break
            }
            Log.i(TAG, "财务历史拉取: ${result.size} 只, 平均 ${result.values.map { it.size }.average().toInt()} 期/只")
        } catch (e: Exception) {
            Log.w(TAG, "财务历史拉取异常: ${e.message}")
        }
        result
    }

    /** 600519 -> sh600519 / 000858 -> sz000858 / 83xxxx -> bj83xxxx */
    private fun normalizeCode(raw: String): String = when {
        raw.startsWith("6") || raw.startsWith("9") -> "sh$raw"
        raw.startsWith("4") || raw.startsWith("8") -> "bj$raw"
        else -> "sz$raw"
    }

    private fun executeGet(url: String): String? = try {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", "https://data.eastmoney.com/")
            .build()
        val resp = client.newCall(req).execute()
        if (resp.isSuccessful) resp.body?.string() else null
    } catch (e: Exception) {
        Log.w(TAG, "HTTP 失败: ${e.message?.take(60)}")
        null
    }
}
