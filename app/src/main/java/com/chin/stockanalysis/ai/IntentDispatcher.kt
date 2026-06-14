package com.chin.stockanalysis.ai

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.ui.CrossTabBus
import com.chin.stockanalysis.ui.CrossTabCommand

/**
 * ## 对话意图分发器
 *
 * 解析用户在对话框中的指令，触发跨Tab操作。
 */
object IntentDispatcher {

    private const val TAG = "IntentDispatcher"

    /** 尝试分发用户指令，返回 true 表示已处理，false 表示继续常规AI对话 */
    fun dispatch(userText: String, context: Context): Boolean {
        val text = userText.trim()

        // ── 模拟交易指令 ──
        if (text.contains("执行模拟交易") || text.contains("开始模拟交易") || text.contains("模拟交易")) {
            CrossTabBus.postCommand(CrossTabCommand(action = "EXECUTE_SIMULATE_TRADE"))
            Log.i(TAG, "📢 分发指令: 执行模拟交易"); return true
        }
        if (text.contains("一键买入") || text.contains("自动买入") || text.contains("全部买入")) {
            CrossTabBus.postCommand(CrossTabCommand(action = "BUY_ALL"))
            Log.i(TAG, "📢 分发指令: 一键买入"); return true
        }
        if (text.contains("运行pipeline") || text.contains("运行Pipeline") || text.contains("pipeline")) {
            CrossTabBus.postCommand(CrossTabCommand(action = "RUN_PIPELINE"))
            Log.i(TAG, "📢 分发指令: 运行Pipeline"); return true
        }
        if (text.contains("打开量化选股") || text.contains("量化选股") || text.contains("执行策略")) {
            CrossTabBus.postCommand(CrossTabCommand(action = "SWITCH_TO_STRATEGY_TAB"))
            Log.i(TAG, "📢 分发指令: 切换到策略Tab"); return true
        }

        // ── 股票名称/代码分析（核心：先查名称→代码，优先本地实时数据） ──
        val (stockCode, stockName) = lookupStock(text, context)
        if (stockCode.isNotBlank()) {
            // 名称匹配到代码 → 走完整 StockAnalyzer 流程（不以旧数据训练 AI）
            CrossTabBus.postCommand(CrossTabCommand(
                action = "ANALYZE_SINGLE_STOCK",
                stockCode = stockCode,
                stockName = stockName
            ))
            Log.i(TAG, "📢 分发指令: 分析 $stockName($stockCode)")
            return true
        }

        // ── 创建策略 ──
        if (text.contains("创建") && text.contains("策略")) {
            val desc = text.replace("创建", "").replace("策略", "").trim()
            if (desc.isNotBlank()) {
                CrossTabBus.postCommand(CrossTabCommand(
                    action = "CREATE_STRATEGY",
                    stockName = desc
                ))
                Log.i(TAG, "📢 分发指令: 创建策略")
                return true
            }
        }

        // 板块/修改/通用查询 → 走常规AI对话
        return false
    }

    /** 名称/代码 → stockCode 查找 */
    private fun lookupStock(text: String, context: Context): Pair<String, String> {
        // 1. 尝试提取代码
        val code = extractStockCode(text)
        if (code.isNotBlank()) return code to extractStockName(text, code)

        // 2. 从 DB 搜索名称
        return try {
            kotlinx.coroutines.runBlocking {
                val db = com.chin.stockanalysis.stock.database.StockDatabase.getInstance(context)
                val namePattern = Regex("""([\u4e00-\u9fff]{2,6})""")
                val names = namePattern.findAll(text).map { it.groupValues[1] }.toList()
                for (name in names) {
                    val results = db.stockBasicDao().searchByName(name)
                    if (results.isNotEmpty()) {
                        val match = results.first()
                        return@runBlocking match.code to match.name
                    }
                }
                "" to ""
            }
        } catch (_: Exception) { "" to "" }
    }

    /** 板块名称查找 */
    private fun lookupSector(text: String): String {
        val sectorNames = listOf("有色" to "有色金属", "稀土" to "稀土永磁", "锂电" to "锂电池",
            "光伏" to "光伏", "芯片" to "半导体", "AI" to "人工智能", "算力" to "算力",
            "医药" to "医药", "白酒" to "白酒", "新能源" to "新能源", "军工" to "军工",
            "煤炭" to "煤炭", "钢铁" to "钢铁", "汽车" to "汽车")
        for ((kw, full) in sectorNames) {
            if (text.contains(kw)) return full
        }
        return ""
    }

    private fun extractStockCode(text: String): String {
        Regex("""[sS][hHzZ](\d{6})""").find(text)?.let {
            return "sh${it.groupValues[1]}".takeIf { it.length == 8 } ?: "sz${it.groupValues[1]}"
        }
        Regex("""\b(\d{6})\b""").find(text)?.let {
            val raw = it.groupValues[1]
            return when {
                raw.startsWith("6") || raw.startsWith("5") -> "sh$raw"
                raw.startsWith("0") || raw.startsWith("1") || raw.startsWith("2") || raw.startsWith("3") -> "sz$raw"
                else -> "sh$raw"
            }
        }
        return ""
    }

    private fun extractStockName(text: String, code: String): String {
        val pattern = Regex("""(\p{IsHan}{2,6})\s*(?:$code|\b${code.takeLast(6)}\b)""")
        return pattern.find(text)?.groupValues?.get(1)?.trim() ?: ""
    }
}