package com.chin.stockanalysis.ai

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ## 意图预判引擎（参考豆包AI 打字时预判）
 *
 * 当用户输入 ≥3 字符时，在后台并行识别用户意图（查股票/问策略/闲聊），
 * 提前准备相关上下文数据，不等用户发送。
 *
 * ### 工作流程
 * 1. 用户输入 ≥3 字符 → 300ms 防抖后触发预测
 * 2. 基于规则 + 关键词快速分类：StockQuery / StrategyAdvice / GeneralChat
 * 3. 预测结果传递给回调，UI 层可提前预取数据
 *
 * ### 使用方式
 * ```kotlin
 * val engine = IntentPredictionEngine()
 * engine.onInputChanged("600519") { intent ->
 *     // intent = StockQuery(code="600519")
 *     // 提前预取行情数据
 * }
 * ```
 */
class IntentPredictionEngine {

    companion object {
        private const val TAG = "IntentEngine"
        private const val MIN_INPUT_LENGTH = 3
        private const val DEBOUNCE_MS = 300L
    }

    /**
     * 识别的用户意图
     */
    sealed class UserIntent {
        data class StockQuery(
            val code: String?,
            val name: String?,
            val confidence: Float
        ) : UserIntent()

        data class StrategyAdvice(
            val type: String,   // "选股" / "历史回测" / "排名" / "建议"
            val confidence: Float
        ) : UserIntent()

        data class SectorQuery(
            val sectorName: String,
            val confidence: Float
        ) : UserIntent()

        data class SimulationTrade(
            val action: String,  // "买入" / "卖出" / "持仓" / "调仓"
            val stockCode: String?,
            val confidence: Float
        ) : UserIntent()

        object GeneralChat : UserIntent()
        object Unknown : UserIntent()
    }

    private var job: Job? = null

    /**
     * 输入文本变化时调用（带防抖）。
     * 300ms 内无新输入 → 触发预测。
     */
    fun onInputChanged(text: String, scope: CoroutineScope, callback: (UserIntent) -> Unit) {
        job?.cancel()
        val trimmed = text.trim()
        if (trimmed.length < MIN_INPUT_LENGTH) {
            callback(UserIntent.Unknown)
            return
        }
        job = scope.launch {
            delay(DEBOUNCE_MS)
            val intent = predictSync(trimmed)
            callback(intent)
        }
    }

    /** 取消进行中的预测 */
    fun cancel() {
        job?.cancel()
        job = null
    }

    // ── 规则引擎（轻量级，无需调用 AI） ──

    private fun predictSync(text: String): UserIntent {
        val t = text.lowercase().trim()

        // 1. 6位纯数字 → 极可能是股票代码
        val stockCodeRegex = Regex("""\b(\d{6})\b""")
        val codeMatch = stockCodeRegex.find(t)
        if (codeMatch != null) {
            val code = codeMatch.groupValues[1]
            Log.d(TAG, "🔮 预判意图: 股票查询 ($code)")
            return UserIntent.StockQuery(
                code = code,
                name = null,
                confidence = 0.95f
            )
        }

        // 2. SH/SZ + 数字
        val shszRegex = Regex("""[sS][hHzZ](\d{6})""")
        val shszMatch = shszRegex.find(t)
        if (shszMatch != null) {
            val code = shszMatch.groupValues[1]
            return UserIntent.StockQuery(code = code, name = null, confidence = 0.98f)
        }

        // 3. 策略/回测关键词
        val strategyKeywords = listOf("选股", "策略", "量化", "回测", "排名", "打分", "推荐")
        val matchedStrategy = strategyKeywords.firstOrNull { t.contains(it) }
        if (matchedStrategy != null) {
            val type = when (matchedStrategy) {
                "选股", "推荐" -> "选股"
                "回测" -> "历史回测"
                "排名", "打分" -> "排名"
                else -> "分析"
            }
            return UserIntent.StrategyAdvice(type = type, confidence = 0.80f)
        }

        // 4. 量化交易关键词
        val tradeKeywords = mapOf(
            "买入" to "buy", "买" to "buy", "建仓" to "buy",
            "卖出" to "sell", "卖" to "sell",
            "持仓" to "hold", "仓位" to "hold",
            "调仓" to "rebalance"
        )
        val matchedTrade = tradeKeywords.entries.firstOrNull { t.contains(it.key) }
        if (matchedTrade != null) {
            // 尝试提取股票代码
            val codeInTrade = stockCodeRegex.find(t)?.groupValues?.get(1)
            return UserIntent.SimulationTrade(
                action = matchedTrade.key,
                stockCode = codeInTrade,
                confidence = 0.85f
            )
        }

        // 5. 行业/板块关键词
        val sectorKeywords = listOf("板块", "行业", "概念", "产业链", "新能源", "半导体", "AI", "人工智能",
            "光通信", "医药", "新能源车", "光伏", "芯片", "云计算")
        val matchedSector = sectorKeywords.firstOrNull { t.contains(it) }
        if (matchedSector != null) {
            return UserIntent.SectorQuery(
                sectorName = matchedSector,
                confidence = 0.75f
            )
        }

        // 5.5 本地詞典匹配（Trie 樹，<10ms，覆蓋 5000+ A 股）
        // 延遲初始化：Trie 在首次調用時構建，後續查詢純內存
        try {
            val entities = com.chin.stockanalysis.ai.StockEntityExtractor.extractSync(t)
            if (entities.isNotEmpty()) {
                val best = entities.first()
                Log.d(TAG, "🔮 预判意图: Trie詞典命中 (${best.name}/${best.code}, ${best.matchType})")
                return UserIntent.StockQuery(
                    code = best.code,
                    name = best.name,
                    confidence = best.confidence
                )
            }
        } catch (_: Exception) { /* Trie 未構建，繼續 */ }

        // 6. 股票名称关键词（从文本中提取 2~6 字中文股票名）
        // 常见知名股票名稱快速匹配
        val stockNamePatterns = listOf("茅台", "宁德", "比亚迪", "腾讯", "阿里", "平安", "招商", "格力")
        val matchedName = stockNamePatterns.firstOrNull { t.contains(it) }
        if (matchedName != null) {
            return UserIntent.StockQuery(code = null, name = matchedName, confidence = 0.90f)
        }

        // 通用中文股票名提取：2~6個中文字（排除常见非股票詞）
        // 先剝離常見動詞/查詢詞，避免貪婪匹配到非股票名
        val queryStripWords = listOf("分析", "走势", "行情", "价格", "查询", "查看", "帮我", "请问",
            "看看", "怎么样", "是多少", "多少钱", "最新价", "技术面", "基本面")
        var stripped = text
        for (w in queryStripWords) { stripped = stripped.replace(w, " ") }
        stripped = stripped.replace(Regex("[\\s，。？！、,.?！]"), " ").trim()

        val chineseNameRegex = Regex("""[\u4e00-\u9fff]{2,6}""")
        val chineseMatch = chineseNameRegex.find(stripped)
        if (chineseMatch != null) {
            val candidate = chineseMatch.value.trim()
            // 排除明顯不是股票名的詞
            val excludeWords = listOf("分析", "走势", "行情", "价格", "最新价", "多少",
                "推荐", "策略", "选股", "回测", "排名", "打分", "买入", "卖出",
                "持仓", "仓位", "调仓", "板块", "行业", "概念", "量化", "请问",
                "今天", "昨天", "明天", "最近", "为什么", "怎么", "什么", "怎么")
            if (candidate !in excludeWords) {
                Log.d(TAG, "🔮 预判意图: 股票名称提取 ($candidate)")
                return UserIntent.StockQuery(code = null, name = candidate, confidence = 0.85f)
            }
        }

        // 7. 拼音缩写（大写英文字母 3-6 个）
        val pinyinRegex = Regex("""\b([A-Z]{3,6})\b""")
        val pinyinMatch = pinyinRegex.find(text)  // 原始大小写
        if (pinyinMatch != null) {
            return UserIntent.StockQuery(
                code = null,
                name = pinyinMatch.groupValues[1],
                confidence = 0.70f
            )
        }

        // 8. 分析/走势 → 可能查股票
        if (t.contains("分析") || t.contains("走势") || t.contains("行情") ||
            t.contains("涨") || t.contains("跌") || t.contains("价格") ||
            t.contains("最新价") || t.contains("多少钱")) {
            // 尝试提取可能的股票代码
            val possibleCode = stockCodeRegex.find(t)?.groupValues?.get(1)
            return UserIntent.StockQuery(
                code = possibleCode,
                name = null,
                confidence = 0.60f
            )
        }

        // 默认：闲聊
        Log.v(TAG, "🔮 预判意图: 闲聊/未知")
        return UserIntent.GeneralChat
    }
}