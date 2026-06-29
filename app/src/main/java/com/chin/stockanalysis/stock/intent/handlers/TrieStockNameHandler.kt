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
 * ## Trie 股票名稱匹配處理器
 *
 * 使用本地 Trie 樹詞典進行股票名稱匹配，插入在處理器鏈中
 * [StockNameHandler] 與 [FuzzyStockNameHandler] 之間。
 *
 * ### 定位
 * - **優先級**：StockCodeHandler → IndexHandler → StockNameHandler → **TrieStockNameHandler** → FuzzyStockNameHandler → ...
 * - **上遊**：[StockNameHandler] 處理硬編碼常見股票（高置信度快速匹配）
 * - **下遊**：[FuzzyStockNameHandler] 處理東方財富 API 模糊搜索（網路降級方案）
 *
 * ### 匹配原理
 * 透過 [StockEntityExtractor.extract] 進行三層級聯匹配：
 * 1. **L1 本地規則** — 硬編碼規則（精確代碼、前綴代碼）
 * 2. **L2 Trie 詞典** — 本地 Trie 樹（精確名稱、前綴、子串、拼音）
 * 3. **L3 網路模糊** — 東方財富 API（降級，此 Handler 僅使用 L2 層）
 *
 * ### 置信度映射
 * | 匹配類型       | 置信度 |
 * |----------------|--------|
 * | EXACT_NAME     | 0.92   |
 * | PREFIX_NAME    | 0.88   |
 * | SUBSTRING_NAME | 0.80   |
 * | PINYIN (縮寫/全拼) | 0.78 |
 *
 * ### Trie 未構建策略
 * 在 [match] 和 [parse] 中，若 [StockNameTrie.isBuilt] 為 false，
 * 則先嘗試同步構建（超時 2 秒），超時則返回 false / UNKNOWN，
 * 讓後續 [FuzzyStockNameHandler] 降級處理。
 *
 * @param context Android Context，用於構建 Trie 時讀取本地資料庫
 *
 * @author StockAnalysis
 */
class TrieStockNameHandler(private val context: Context) : IntentHandler {

    override var next: IntentHandler? = null

    companion object {
        private const val TAG = "TrieStockHandler"

        /** 同步構建 Trie 的超時時間（毫秒） */
        private const val BUILD_TIMEOUT_MS = 2000L

        /** 置信度映射 */
        private const val CONFIDENCE_EXACT_NAME = 0.92f
        private const val CONFIDENCE_PREFIX_NAME = 0.88f
        private const val CONFIDENCE_SUBSTRING_NAME = 0.80f
        private const val CONFIDENCE_PINYIN = 0.78f

        /** 技術分析關鍵詞 */
        private val TECHNICAL_KEYWORDS = listOf("分析", "走勢", "技術面")

        /** 對比關鍵詞 */
        private val COMPARE_KEYWORDS = listOf("對比", "比較", "哪個")
    }

    /**
     * 檢查輸入是否可能匹配 Trie 詞典中的股票名稱。
     *
     * 執行流程：
     * 1. 若 Trie 已構建（[StockNameTrie.isBuilt]），直接透過
     *    [StockEntityExtractor.extract] 判斷是否有匹配結果
     * 2. 若 Trie 未構建，嘗試同步構建（超時 [BUILD_TIMEOUT_MS]），
     *    超時則返回 false，交由下游 Handler 處理
     *
     * @param input 用戶原始輸入
     * @return true 若 Trie 匹配到至少一個股票實體
     */
    override fun match(input: String): Boolean {
        // 若 Trie 尚未構建，嘗試同步構建
        if (!StockNameTrie.isBuilt) {
            ensureTrieBuilt()
        }

        // 仍然未構建（構建超時或失敗），交由下游 Handler 處理
        if (!StockNameTrie.isBuilt) {
            Log.w(TAG, "match: Trie 未構建且同步構建超時，跳過 (input='${input.take(30)}')")
            return false
        }

        // Trie 已就緒，使用 runBlocking 呼叫 suspend 函數
        // Trie 已構建後 extract 不需要 IO，不會阻塞
        return try {
            val entities = runBlocking {
                StockEntityExtractor.extract(input, context)
            }
            entities.isNotEmpty().also { matched ->
                if (matched) {
                    Log.d(TAG, "match: 命中 ${entities.size} 個實體 (input='${input.take(30)}')")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "match: extract 異常: ${e.message}")
            false
        }
    }

    /**
     * 解析用戶輸入，提取股票實體並轉為 [IntentResult]。
     *
     * 執行流程：
     * 1. 確保 Trie 已構建（同 [match]）
     * 2. 呼叫 [StockEntityExtractor.extract] 取得實體列表
     * 3. 根據匹配類型計算置信度
     * 4. 根據輸入中的關鍵詞判斷意圖（技術分析 / 對比 / 查詢價格）
     * 5. 組裝 [IntentResult] 返回
     *
     * @param input 用戶原始輸入
     * @return [IntentResult] 包含提取的股票代碼、名稱、意圖和置信度
     */
    override fun parse(input: String): IntentResult {
        if (!StockNameTrie.isBuilt) {
            ensureTrieBuilt()
        }

        if (!StockNameTrie.isBuilt) {
            Log.w(TAG, "parse: Trie 未構建，返回 UNKNOWN")
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
            Log.w(TAG, "parse: extract 異常: ${e.message}")
            return IntentResult(
                intent = StockIntent.UNKNOWN,
                stockCodes = emptyList(),
                stockNames = emptyList(),
                confidence = 0.0f,
                rawQuery = input
            )
        }

        if (entities.isEmpty()) {
            Log.d(TAG, "parse: 無匹配實體 (input='${input.take(30)}')")
            return IntentResult(
                intent = StockIntent.UNKNOWN,
                stockCodes = emptyList(),
                stockNames = emptyList(),
                confidence = 0.0f,
                rawQuery = input
            )
        }

        // 去重（同一股票可能被多種匹配方式命中）
        val seen = mutableSetOf<String>()
        val distinctEntities = entities.filter { entity ->
            if (entity.code in seen) false
            else { seen.add(entity.code); true }
        }

        val codes = distinctEntities.map { it.code }
        val names = distinctEntities.map { it.name }
        val matchedNames = distinctEntities.map { it.text }

        // 取最高置信度作為整體置信度
        val bestConfidence = distinctEntities.maxOf { it.confidence }

        // 根據輸入判斷意圖
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
     * 確保 Trie 樹已構建。
     *
     * 若 [StockNameTrie.isBuilt] 為 false，嘗試在
     * [BUILD_TIMEOUT_MS] 毫秒內同步構建。
     * 超時或失敗不會拋出異常，僅記錄警告日誌。
     */
    private fun ensureTrieBuilt() {
        if (StockNameTrie.isBuilt) return

        Log.d(TAG, "ensureTrieBuilt: Trie 未構建，嘗試同步構建（超時 ${BUILD_TIMEOUT_MS}ms）...")
        try {
            runBlocking {
                withTimeoutOrNull(BUILD_TIMEOUT_MS) {
                    StockNameTrie.build(context)
                }
            }
            if (StockNameTrie.isBuilt) {
                Log.i(TAG, "ensureTrieBuilt: Trie 構建成功")
            } else {
                Log.w(TAG, "ensureTrieBuilt: Trie 構建超時（${BUILD_TIMEOUT_MS}ms）")
            }
        } catch (e: Exception) {
            Log.w(TAG, "ensureTrieBuilt: Trie 構建失敗: ${e.message}")
        }
    }

    /**
     * 根據用戶輸入和匹配到的股票數量判斷意圖。
     *
     * 意圖判斷規則：
     * - 輸入包含「分析 / 走勢 / 技術面」→ [StockIntent.TECHNICAL_ANALYSIS]
     * - 輸入包含「對比 / 比較 / 哪個」且匹配到多只股票 → [StockIntent.COMPARE_STOCKS]
     * - 其他情況 → [StockIntent.QUERY_PRICE]
     *
     * @param input 用戶原始輸入
     * @param stockCount 匹配到的股票數量
     * @return 判斷出的 [StockIntent]
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
