package com.chin.stockanalysis.stock.database

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.strategy.trade.DailyNewsHotPickEntity
import com.chin.stockanalysis.strategy.trade.DailyPeriodResultEntity
import com.chin.stockanalysis.strategy.trade.StrategyTradeFittingParamEntity
import com.chin.stockanalysis.strategy.trade.StrategyTradeOrderEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class DataExportImport(private val context: Context) {

    companion object {
        private const val TAG = "DataExportImport"
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private const val EXPORT_DIR = "StockAnalysis_exports"
    }

    private val db = StockDatabase.getInstance(context)

    suspend fun exportAllToJson(): String = withContext(Dispatchers.IO) {
        val root = JSONObject()
        val meta = JSONObject().apply {
            put("export_time", LocalDate.now().format(DATE_FMT))
            put("app_version", "v8.0")
            put("description", "StockAnalysis 股票数据库导出")
        }
        root.put("meta", meta)
        root.put("stock_basics", exportStockBasics())
        root.put("sector_stocks", exportSectorStocks())
        root.put("daily_snapshot", exportDailySnapshot())
        root.put("strategy_predictions", exportStrategyPredictions())
        root.put("sector_daily_records", exportSectorDailyRecords())
        root.put("news_factors", exportNewsFactors())
        root.put("daily_period_results", exportDailyPeriodResults())
        root.put("strategy_trade_orders", exportTradeOrders())
        root.put("daily_news_hot_picks", exportDailyNewsHotPicks())
        root.put("strategy_trade_fitting_params", exportFittingParams())

        val jsonStr = root.toString(2)
        val file = getExportFile("stock_analysis_${LocalDate.now()}.json")
        file.writeText(jsonStr)
        Log.i(TAG, "导出完成: ${file.absolutePath} (${jsonStr.length} bytes)")
        file.absolutePath
    }

    suspend fun exportAllToCsv(): List<String> = withContext(Dispatchers.IO) {
        listOf(
            exportTableToCsv("trade_orders", generateTradeOrdersCsv()),
            exportTableToCsv("period_results", generatePeriodResultsCsv()),
            exportTableToCsv("news_hot_picks", generateNewsHotPicksCsv()),
            exportTableToCsv("fitting_params", generateFittingParamsCsv()),
            exportTableToCsv("news_factors", generateNewsFactorsCsv())
        )
    }

    suspend fun importFromJson(filePath: String): ImportReport = withContext(Dispatchers.IO) {
        val file = File(filePath)
        if (!file.exists()) {
            return@withContext ImportReport(success = false, message = "文件不存在: $filePath")
        }
        val jsonStr = file.readText()
        val root = JSONObject(jsonStr)
        val report = ImportReport(success = true, message = "开始导入")
        try {
            if (root.has("daily_period_results")) {
                val arr = root.getJSONArray("daily_period_results")
                importPeriodResults(arr)
                report.periodResults = arr.length()
            }
            if (root.has("strategy_trade_orders")) {
                val arr = root.getJSONArray("strategy_trade_orders")
                importTradeOrders(arr)
                report.tradeOrders = arr.length()
            }
            if (root.has("daily_news_hot_picks")) {
                val arr = root.getJSONArray("daily_news_hot_picks")
                importNewsHotPicks(arr)
                report.newsHotPicks = arr.length()
            }
            if (root.has("strategy_trade_fitting_params")) {
                val arr = root.getJSONArray("strategy_trade_fitting_params")
                importFittingParams(arr)
                report.fittingParams = arr.length()
            }
            if (root.has("news_factors")) {
                val arr = root.getJSONArray("news_factors")
                importNewsFactors(arr)
                report.newsFactors = arr.length()
            }
            if (root.has("daily_snapshot")) {
                val arr = root.getJSONArray("daily_snapshot")
                report.dailySnapshots = arr.length()
            }
            report.message = "导入成功: 周期结果${report.periodResults}条, 订单${report.tradeOrders}条, 新闻热点${report.newsHotPicks}条, 拟合参数${report.fittingParams}条"
        } catch (e: Exception) {
            Log.e(TAG, "导入失败: ${e.message}", e)
            report.success = false
            report.message = "导入失败: ${e.message}"
        }
        report
    }

    data class ImportReport(
        var success: Boolean = false,
        var message: String = "",
        var periodResults: Int = 0,
        var tradeOrders: Int = 0,
        var newsHotPicks: Int = 0,
        var fittingParams: Int = 0,
        var newsFactors: Int = 0,
        var dailySnapshots: Int = 0
    )

    // ═══ JSON 导出 ═══

    private suspend fun exportStockBasics(): JSONArray {
        val arr = JSONArray()
        try {
            for (s in db.stockBasicDao().getAll()) {
                arr.put(JSONObject().apply {
                    put("code", s.code)
                    put("name", s.name)
                    put("business", s.business)
                    put("chain_rationale", s.chainRationale)
                })
            }
        } catch (_: Exception) {}
        return arr
    }

    private suspend fun exportSectorStocks(): JSONArray {
        val arr = JSONArray()
        try {
            for (key in db.sectorStockDao().getAllSectorKeys()) {
                val codes = db.sectorStockDao().getStockCodesBySector(key)
                val name = db.sectorStockDao().getSectorName(key) ?: key
                arr.put(JSONObject().apply {
                    put("sector_key", key)
                    put("sector_name", name)
                    put("stock_codes", JSONArray(codes))
                })
            }
        } catch (_: Exception) {}
        return arr
    }

    private suspend fun exportDailySnapshot(): JSONArray {
        val arr = JSONArray()
        try {
            for (date in db.dailySnapshotDao().getAvailableDates(100)) {
                val snaps = db.dailySnapshotDao().getByDate(date)
                val dateArr = JSONArray()
                for (s in snaps) {
                    dateArr.put(JSONObject().apply {
                        put("code", s.code); put("name", s.name)
                        put("date", s.date); put("open", s.open)
                        put("close", s.close); put("high", s.high)
                        put("low", s.low); put("volume", s.volume)
                        put("amount", s.amount); put("change_pct", s.changePct)
                        put("turnover_rate", s.turnoverRate)
                        put("main_net_inflow", s.mainNetInflow)
                    })
                }
                arr.put(JSONObject().apply {
                    put("date", date)
                    put("count", snaps.size)
                    put("snapshots", dateArr)
                })
            }
        } catch (_: Exception) {}
        return arr
    }

    private suspend fun exportStrategyPredictions(): JSONArray {
        val arr = JSONArray()
        try {
            for (date in db.dailySnapshotDao().getAvailableDates(30)) {
                for (p in db.strategyPredictionDao().getByDate(date)) {
                    arr.put(JSONObject().apply {
                        put("strategy_id", p.strategyId)
                        put("strategy_name", p.strategyName)
                        put("date", p.date)
                        put("stock_code", p.stockCode)
                        put("stock_name", p.stockName)
                        put("predicted_score", p.predictedScore)
                        put("predicted_action", p.predictedAction)
                        put("actual_next_day_pct", p.actualNextDayPct ?: 0.0)
                        put("was_correct", p.wasCorrect ?: 0)
                    })
                }
            }
        } catch (_: Exception) {}
        return arr
    }

    private suspend fun exportSectorDailyRecords(): JSONArray {
        val arr = JSONArray()
        try {
            for (date in db.sectorDailyRecordDao().getAvailableDates(60)) {
                for (r in db.sectorDailyRecordDao().getByDate(date)) {
                    arr.put(JSONObject().apply {
                        put("date", r.date)
                        put("sector_code", r.sectorCode)
                        put("sector_name", r.sectorName)
                        put("change_pct", r.changePct)
                        put("hot_score", r.hotScore)
                        put("composite_score", r.compositeScore)
                        put("rank", r.rank)
                    })
                }
            }
        } catch (_: Exception) {}
        return arr
    }

    private suspend fun exportNewsFactors(): JSONArray {
        val arr = JSONArray()
        try {
            for (n in db.newsFactorDao().getAll(200)) {
                arr.put(JSONObject().apply {
                    put("stock_code", n.stockCode)
                    put("company_name", n.companyName)
                    put("title", n.title)
                    put("content", n.content.take(200))
                    put("news_date", n.newsDate)
                    put("sentiment", n.sentiment)
                    put("impact_strength", n.impactStrength)
                    put("sector", n.sector)
                    put("tags", n.tags)
                })
            }
        } catch (_: Exception) {}
        return arr
    }

    private suspend fun exportDailyPeriodResults(): JSONArray {
        val arr = JSONArray()
        try {
            for (r in db.dailyPeriodResultDao().getRecent(500)) {
                arr.put(JSONObject().apply {
                    put("strategy_id", r.strategyId)
                    put("strategy_name", r.strategyName)
                    put("trade_date", r.tradeDate)
                    put("period_days", r.periodDays)
                    put("stock_codes", r.stockCodesJson)
                    put("stock_count", r.stockCount)
                    put("news_strength_score", r.newsStrengthScore)
                    put("rotation_penalty", r.rotationPenalty)
                    put("filtered_codes", r.filteredCodesJson)
                    put("filtered_reason", r.filteredReasonJson)
                    put("final_top3", r.finalTop3Json)
                    put("ai_selection_reason", r.aiSelectionReason)
                })
            }
        } catch (_: Exception) {}
        return arr
    }

    private suspend fun exportTradeOrders(): JSONArray {
        val arr = JSONArray()
        try {
            for (o in db.strategyTradeOrderDao().getRecent(500)) {
                arr.put(JSONObject().apply {
                    put("strategy_id", o.strategyId)
                    put("stock_code", o.stockCode)
                    put("stock_name", o.stockName)
                    put("trade_date", o.tradeDate)
                    put("buy_price", o.buyPrice)
                    put("buy_time", o.buyTime)
                    put("quantity", o.quantity)
                    put("order_type", o.orderType)
                    put("sell_price", o.sellPrice)
                    put("sell_time", o.sellTime)
                    put("profit_pct", o.profitPct)
                    put("status", o.status)
                    put("score_at_buy", o.scoreAtBuy)
                })
            }
        } catch (_: Exception) {}
        return arr
    }

    private suspend fun exportDailyNewsHotPicks(): JSONArray {
        val arr = JSONArray()
        try {
            for (date in db.dailyNewsHotPickDao().getAvailableDates()) {
                val picks = db.dailyNewsHotPickDao().getByDate(date)
                val picksArr = JSONArray()
                for (p in picks) {
                    picksArr.put(JSONObject().apply {
                        put("rank", p.rank)
                        put("sector_name", p.sectorName)
                        put("sub_sector_name", p.subSectorName)
                        put("hot_score", p.hotScore)
                        put("news_title", p.newsTitle)
                        put("related_stock_codes", p.relatedStockCodes)
                    })
                }
                arr.put(JSONObject().apply {
                    put("news_date", date)
                    put("picks", picksArr)
                })
            }
        } catch (_: Exception) {}
        return arr
    }

    private suspend fun exportFittingParams(): JSONArray {
        val arr = JSONArray()
        try {
            for (p in db.strategyTradeFittingParamDao().getRecentByStrategy("all", 1000)) {
                arr.put(JSONObject().apply {
                    put("strategy_id", p.strategyId)
                    put("trade_date", p.tradeDate)
                    put("period_days", p.periodDays)
                    put("param_json", p.paramJson)
                    put("fitting_round", p.fittingRound)
                    put("accuracy", p.accuracy)
                    put("avg_return", p.avgReturn)
                })
            }
        } catch (_: Exception) {}
        return arr
    }

    // ═══ CSV ═══

    private suspend fun generateTradeOrdersCsv(): String {
        val sb = StringBuilder()
        sb.appendLine("trade_date,strategy_id,stock_code,stock_name,buy_price,quantity,status,sell_price,profit_pct,score_at_buy")
        try {
            for (o in db.strategyTradeOrderDao().getRecent(1000)) {
                sb.appendLine("${o.tradeDate},${o.strategyId},${o.stockCode},${o.stockName},${o.buyPrice},${o.quantity},${o.status},${o.sellPrice},${o.profitPct},${o.scoreAtBuy}")
            }
        } catch (_: Exception) {}
        return sb.toString()
    }

    private suspend fun generatePeriodResultsCsv(): String {
        val sb = StringBuilder()
        sb.appendLine("trade_date,strategy_name,period_days,stock_count,news_strength,rotation_penalty,final_top3")
        try {
            for (r in db.dailyPeriodResultDao().getRecent(500)) {
                sb.appendLine("${r.tradeDate},${r.strategyName},${r.periodDays},${r.stockCount},${r.newsStrengthScore},${r.rotationPenalty},${r.finalTop3Json.take(100)}")
            }
        } catch (_: Exception) {}
        return sb.toString()
    }

    private suspend fun generateNewsHotPicksCsv(): String {
        val sb = StringBuilder()
        sb.appendLine("news_date,rank,sector_name,hot_score,news_title,related_stocks")
        try {
            for (date in db.dailyNewsHotPickDao().getAvailableDates()) {
                for (p in db.dailyNewsHotPickDao().getByDate(date)) {
                    sb.appendLine("${p.newsDate},${p.rank},${p.sectorName},${p.hotScore},${p.newsTitle},${p.relatedStockCodes}")
                }
            }
        } catch (_: Exception) {}
        return sb.toString()
    }

    private suspend fun generateFittingParamsCsv(): String {
        val sb = StringBuilder()
        sb.appendLine("strategy_id,trade_date,period_days,fitting_round,accuracy,avg_return")
        try {
            for (p in db.strategyTradeFittingParamDao().getRecentByStrategy("all", 500)) {
                sb.appendLine("${p.strategyId},${p.tradeDate},${p.periodDays},${p.fittingRound},${p.accuracy},${p.avgReturn}")
            }
        } catch (_: Exception) {}
        return sb.toString()
    }

    private suspend fun generateNewsFactorsCsv(): String {
        val sb = StringBuilder()
        sb.appendLine("news_date,sector,company_name,title,sentiment,impact_strength,tags")
        try {
            for (n in db.newsFactorDao().getAll(200)) {
                sb.appendLine("${n.newsDate},${n.sector},${n.companyName},${n.title.take(50)},${n.sentiment},${n.impactStrength},${n.tags.take(50)}")
            }
        } catch (_: Exception) {}
        return sb.toString()
    }

    // ═══ JSON 导入 ═══

    private suspend fun importPeriodResults(arr: JSONArray) {
        for (i in 0 until arr.length()) {
            try {
                val obj = arr.getJSONObject(i)
                db.dailyPeriodResultDao().insert(DailyPeriodResultEntity(
                    strategyId = obj.getString("strategy_id"),
                    strategyName = obj.getString("strategy_name"),
                    tradeDate = obj.getString("trade_date"),
                    periodDays = obj.getInt("period_days"),
                    stockCodesJson = obj.optString("stock_codes", "[]"),
                    stockCount = obj.optInt("stock_count", 0),
                    newsStrengthScore = obj.optInt("news_strength_score", 50),
                    rotationPenalty = obj.optInt("rotation_penalty", 0),
                    mainBoardFilter = true,
                    filteredCodesJson = obj.optString("filtered_codes", "[]"),
                    filteredReasonJson = obj.optString("filtered_reason", "[]"),
                    finalTop3Json = obj.optString("final_top3", "[]"),
                    aiSelectionReason = obj.optString("ai_selection_reason", ""),
                    createdAt = System.currentTimeMillis()
                ))
            } catch (_: Exception) {}
        }
    }

    private suspend fun importTradeOrders(arr: JSONArray) {
        for (i in 0 until arr.length()) {
            try {
                val obj = arr.getJSONObject(i)
                db.strategyTradeOrderDao().insert(StrategyTradeOrderEntity(
                    strategyId = obj.getString("strategy_id"),
                    stockCode = obj.getString("stock_code"),
                    stockName = obj.getString("stock_name"),
                    tradeDate = obj.getString("trade_date"),
                    buyPrice = obj.getDouble("buy_price"),
                    buyTime = obj.optString("buy_time", ""),
                    quantity = obj.getInt("quantity"),
                    orderType = obj.optString("order_type", "模拟买入"),
                    sellPrice = obj.optDouble("sell_price", 0.0),
                    sellTime = obj.optString("sell_time", ""),
                    profitPct = obj.optDouble("profit_pct", 0.0),
                    status = obj.getString("status"),
                    reason = obj.optString("reason", ""),
                    scoreAtBuy = obj.optInt("score_at_buy", 0)
                ))
            } catch (_: Exception) {}
        }
    }

    private suspend fun importNewsHotPicks(arr: JSONArray) {
        for (i in 0 until arr.length()) {
            try {
                val obj = arr.getJSONObject(i)
                val picksArr = obj.optJSONArray("picks") ?: continue
                for (j in 0 until picksArr.length()) {
                    val p = picksArr.getJSONObject(j)
                    db.dailyNewsHotPickDao().insert(DailyNewsHotPickEntity(
                        newsDate = obj.getString("news_date"),
                        rank = p.getInt("rank"),
                        sectorName = p.getString("sector_name"),
                        subSectorName = p.optString("sub_sector_name", ""),
                        hotScore = p.optInt("hot_score", 0),
                        newsTitle = p.optString("news_title", ""),
                        relatedStockCodes = p.optString("related_stock_codes", "")
                    ))
                }
            } catch (_: Exception) {}
        }
    }

    private suspend fun importFittingParams(arr: JSONArray) {
        val entities = mutableListOf<StrategyTradeFittingParamEntity>()
        for (i in 0 until arr.length()) {
            try {
                val obj = arr.getJSONObject(i)
                entities.add(StrategyTradeFittingParamEntity(
                    strategyId = obj.getString("strategy_id"),
                    tradeDate = obj.getString("trade_date"),
                    periodDays = obj.getInt("period_days"),
                    paramJson = obj.optString("param_json", "{}"),
                    fittingRound = obj.getInt("fitting_round"),
                    accuracy = obj.optDouble("accuracy", 0.0),
                    avgReturn = obj.optDouble("avg_return", 0.0),
                    createdAt = System.currentTimeMillis()
                ))
            } catch (_: Exception) {}
        }
        if (entities.isNotEmpty()) {
            db.strategyTradeFittingParamDao().insertAll(entities)
        }
    }

    private suspend fun importNewsFactors(arr: JSONArray) {
        val entities = mutableListOf<com.chin.stockanalysis.news.NewsFactorEntity>()
        for (i in 0 until arr.length()) {
            try {
                val obj = arr.getJSONObject(i)
                entities.add(com.chin.stockanalysis.news.NewsFactorEntity(
                    stockCode = obj.optString("stock_code", ""),
                    companyName = obj.optString("company_name", ""),
                    title = obj.optString("title", ""),
                    content = obj.optString("content", ""),
                    newsDate = obj.optString("news_date", ""),
                    sentiment = obj.optInt("sentiment", 0),
                    impactStrength = obj.optInt("impact_strength", 50),
                    sector = obj.optString("sector", ""),
                    tags = obj.optString("tags", ""),
                    source = "imported"
                ))
            } catch (_: Exception) {}
        }
        if (entities.isNotEmpty()) {
            db.newsFactorDao().insertAll(entities)
        }
    }

    // ═══ 工具 ═══

    private fun getExportDir(): File {
        val dir = File(context.getExternalFilesDir(null), EXPORT_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getExportFile(name: String): File = File(getExportDir(), name)

    private fun exportTableToCsv(tableName: String, content: String): String {
        val file = getExportFile("${tableName}_${LocalDate.now()}.csv")
        file.writeText(content)
        return file.absolutePath
    }

    fun getExportFiles(): List<File> {
        val dir = getExportDir()
        return dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    suspend fun getDatabaseStats(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        sb.appendLine("📊 股票数据库统计")
        sb.appendLine("=" .repeat(30))
        try {
            sb.appendLine("股票基本信息: ${db.stockBasicDao().count()} 只")
            sb.appendLine("板块数量: ${db.sectorStockDao().getAllSectorKeys().size} 个")
            val snapDates = db.dailySnapshotDao().getAvailableDates(100)
            sb.appendLine("每日快照: ${snapDates.size} 个交易日")
            sb.appendLine("新闻因子: ${db.newsFactorDao().count()} 条")
            sb.appendLine("策略周期结果: ${db.dailyPeriodResultDao().getAvailableDates().size} 个交易日有数据")
            sb.appendLine("交易订单: ${db.strategyTradeOrderDao().getRecent(1).size}+ 条")
            sb.appendLine("拟合参数: ${db.strategyTradeFittingParamDao().getRecentByStrategy("all", 1).size}+ 条")
        } catch (e: Exception) {
            sb.appendLine("获取统计失败: ${e.message}")
        }
        sb.toString()
    }
}