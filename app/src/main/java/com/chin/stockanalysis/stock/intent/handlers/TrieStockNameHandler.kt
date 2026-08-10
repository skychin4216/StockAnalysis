package com.chin.stockanalysis.stock.intent.handlers

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.ai.StockEntityExtractor
import com.chin.stockanalysis.stock.data.StockNameTrie
import com.chin.stockanalysis.stock.intent.IntentResult
import com.chin.stockanalysis.stock.intent.StockIntent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ## Trie 股票名称匹配处理器
 *
 * 使用本地 Trie 树词典进行股票名称匹配，插入在处理器链中
 * [StockNameHandler] 与 [FuzzyStockNameHandler] 之间。
 *
 * ### 定位
 * - **优先级**：StockCodeHandler → IndexHandler → StockNameHandler → **TrieStockNameHandler** → FuzzyStockNameHandler → ...
 * - **上游**：[StockNameHandler] 处理硬编码常见股票（高置信度快速匹配）
 * - **下游**：[FuzzyStockNameHandler] 处理东方财富 API 模糊搜索（网路降级方案）
 *
 * ### 匹配原理
 * 透过 [StockEntityExtractor.extract] 进行三层级联匹配：
 * 1. **L1 本地规则** — 硬编码规则（精确代码、前缀代码）
 * 2. **L2 Trie 词典** — 本地 Trie 树（精确名称、前缀、子串、拼音）
 * 3. **L3 网路模糊** — 东方财富 API（降级，此 Handler 仅使用 L2 层）
 *
 * ### 置信度映射
 * | 匹配类型       | 置信度 |
 * |----------------|--------|
 * | EXACT_NAME     | 0.92   |
 * | PREFIX_NAME    | 0.88   |
 * | SUBSTRING_NAME | 0.80   |
 * | PINYIN (缩写/全拼) | 0.78 |
 *
 * ### Trie 未构建策略
 * 在 [match] 和 [parse] 中，若 [StockNameTrie.isBuilt] 为 false，
 * 则先尝试同步构建（超时 2 秒），超时则返回 false / UNKNOWN，
 * 让后续 [FuzzyStockNameHandler] 降级处理。
 *
 * @param context Android Context，用于构建 Trie 时读取本地资料库
 *
 * @author StockAnalysis
 */
class TrieStockNameHandler(private val context: Context) : IntentHandler {

    override var next: IntentHandler? = null

    companion object {
        private const val TAG = "TrieStockHandler"

        /** 同步构建 Trie 的超时时间（毫秒） */
        private const val BUILD_TIMEOUT_MS = 2000L

        /** 置信度映射 */
        private const val CONFIDENCE_EXACT_NAME = 0.92f
        private const val CONFIDENCE_PREFIX_NAME = 0.88f
        private const val CONFIDENCE_SUBSTRING_NAME = 0.80f
        private const val CONFIDENCE_PINYIN = 0.78f

        /** 技术分析关键词 */
        private val TECHNICAL_KEYWORDS = listOf("分析", "走势", "技术面")

        /** 对比关键词 */
        private val COMPARE_KEYWORDS = listOf("对比", "比较", "哪个")
    }

    /**
     * 检查输入是否可能匹配 Trie 词典中的股票名称。
     *
     * 执行流程：
     * 1. 若 Trie 已构建（[StockNameTrie.isBuilt]），直接透过
     *    [StockEntityExtractor.extract] 判断是否有匹配结果
     * 2. 若 Trie 未构建，尝试同步构建（超时 [BUILD_TIMEOUT_MS]），
     *    超时则返回 false，交由下游 Handler 处理
     *
     * @param input 用户原始输入
     * @return true 若 Trie 匹配到至少一个股票实体
     */
    override fun match(input: String): Boolean {
        // 若 Trie 尚未构建，尝试同步构建
        if (!StockNameTrie.isBuilt) {
            ensureTrieBuilt()
        }

        // 仍然未构建（构建超时或失败），交由下游 Handler 处理
        if (!StockNameTrie.isBuilt) {
            Log.w(TAG, "match: Trie 未构建且同步构建超时，跳过 (input='${input.take(30)}')")
            return false
        }

        // Trie 已就绪，使用 runBlocking 呼叫 suspend 函数
        // Trie 已构建后 extract 不需要 IO，不会阻塞
        return try {
            val entities = runBlocking {
                StockEntityExtractor.extract(input, context)
            }
            entities.isNotEmpty().also { matched ->
                if (matched) {
                    Log.d(TAG, "match: 命中 ${entities.size} 个实体 (input='${input.take(30)}')")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "match: extract 异常: ${e.message}")
            false
        }
    }

    /**
     * 解析用户输入，提取股票实体并转为 [IntentResult]。
     *
     * 执行流程：
     * 1. 确保 Trie 已构建（同 [match]）
     * 2. 呼叫 [StockEntityExtractor.extract] 取得实体列表
     * 3. 根据匹配类型计算置信度
     * 4. 根据输入中的关键词判断意图（技术分析 / 对比 / 查询价格）
     * 5. 组装 [IntentResult] 返回
     *
     * @param input 用户原始输入
     * @return [IntentResult] 包含提取的股票代码、名称、意图和置信度
     */
    override fun parse(input: String): IntentResult {
        if (!StockNameTrie.isBuilt) {
            ensureTrieBuilt()
        }

        if (!StockNameTrie.isBuilt) {
            Log.w(TAG, "parse: Trie 未构建，返回 UNKNOWN")
            return IntentResult(
                intent = StockIntent.UNKNOWN,
                stockCodes = emptyList(),
                stockNames = emptyList(),
                confidence = 0.0f,
                rawQuery = input
            )
        }

        val entities = try {
            runBlocking {
                StockEntityExtractor.extract(input, context)
            }
        } catch (e: Exception) {
            Log.w(TAG, "parse: extract 异常: ${e.message}")
            return IntentResult(
                intent = StockIntent.UNKNOWN,
                stockCodes = emptyList(),
                stockNames = emptyList(),
                confidence = 0.0f,
                rawQuery = input
            )
        }

        if (entities.isEmpty()) {
            Log.d(TAG, "parse: 无匹配实体 (input='${input.take(30)}')")
            return IntentResult(
                intent = StockIntent.UNKNOWN,
                stockCodes = emptyList(),
                stockNames = emptyList(),
                confidence = 0.0f,
                rawQuery = input
            )
        }

        // 去重（同一股票可能被多种匹配方式命中）
        val seen = mutableSetOf<String>()
        val distinctEntities = entities.filter { entity ->
            if (entity.code in seen) false
            else { seen.add(entity.code); true }
        }

        val codes = distinctEntities.map { it.code }
        val names = distinctEntities.map { it.name }
        val matchedNames = distinctEntities.map { it.text }

        // 取最高置信度作为整体置信度
        val bestConfidence = distinctEntities.maxOf { it.confidence }

        // 根据输入判断意图
        val intent = determineIntent(input, distinctEntities.size)

        val result = IntentResult(
            intent = intent,
            stockCodes = codes,
            stockNames = names,
            confidence = bestConfidence,
            rawQuery = input,
            parsedParams = mapOf(
                "source" to "trie_lookup",
                "queries" to matchedNames
            )
        )

        Log.d(TAG, "parse: intent=$intent, codes=$codes, names=$names, confidence=$bestConfidence")
        return result
    }

    /**
     * 确保 Trie 树已构建。
     *
     * 若 [StockNameTrie.isBuilt] 为 false，尝试在
     * [BUILD_TIMEOUT_MS] 毫秒内同步构建。
     * 超时或失败不会抛出异常，仅记录警告日志。
     */
    private fun ensureTrieBuilt() {
        if (StockNameTrie.isBuilt) return

        Log.d(TAG, "ensureTrieBuilt: Trie 未构建，尝试同步构建（超时 ${BUILD_TIMEOUT_MS}ms）...")
        try {
            runBlocking {
                withTimeoutOrNull(BUILD_TIMEOUT_MS) {
                    StockNameTrie.build(context)
                }
            }
            if (StockNameTrie.isBuilt) {
                Log.i(TAG, "ensureTrieBuilt: Trie 构建成功")
            } else {
                Log.w(TAG, "ensureTrieBuilt: Trie 构建超时（${BUILD_TIMEOUT_MS}ms）")
            }
        } catch (e: Exception) {
            Log.w(TAG, "ensureTrieBuilt: Trie 构建失败: ${e.message}")
        }
    }

    /**
     * 根据用户输入和匹配到的股票数量判断意图。
     *
     * 意图判断规则：
     * - 输入包含「分析 / 走势 / 技术面」→ [StockIntent.TECHNICAL_ANALYSIS]
     * - 输入包含「对比 / 比较 / 哪个」且匹配到多只股票 → [StockIntent.COMPARE_STOCKS]
     * - 其他情况 → [StockIntent.QUERY_PRICE]
     *
     * @param input 用户原始输入
     * @param stockCount 匹配到的股票数量
     * @return 判断出的 [StockIntent]
     */
    private fun determineIntent(input: String, stockCount: Int): StockIntent {
        val hasTechnicalKeyword = TECHNICAL_KEYWORDS.any { input.contains(it) }
        val hasCompareKeyword = COMPARE_KEYWORDS.any { input.contains(it) }

        return when {
            hasCompareKeyword && stockCount >= 2 -> StockIntent.COMPARE_STOCKS
            hasTechnicalKeyword -> StockIntent.TECHNICAL_ANALYSIS
            else -> StockIntent.QUERY_PRICE
        }
    }
}
