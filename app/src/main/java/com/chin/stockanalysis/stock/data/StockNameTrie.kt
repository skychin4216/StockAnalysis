package com.chin.stockanalysis.stock.data

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.StockBasicEntity
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.util.PinyinUtils

/**
 * ## 基於 Trie 樹的股票名稱反向索引
 *
 * 用於從用戶輸入中快速提取股票實體。構建時會同時建立兩棵 Trie：
 * - **nameTrie**：以中文名稱的字符逐字插入，支援精確、前綴、子串匹配
 * - **pinyinTrie**：以拼音縮寫（小寫）逐字母插入，支援拼音縮寫匹配
 *
 * 此外還會記錄每隻股票的全拼，用於拼音全拼匹配。
 *
 * ### 使用方式
 * ```kotlin
 * // 在 IO 線程中構建（只需一次）
 * StockNameTrie.build(context)
 *
 * // 搜索（線程安全）
 * val results = StockNameTrie.search("茅台")
 * ```
 *
 * ### 性能特徵
 * - 構建耗時取決於股票數量（通常數千隻），建議在 IO 線程執行
 * - 搜索為 O(m + k)，m 為輸入長度，k 為匹配結果數
 * - 線程安全：使用 @Volatile + synchronized 保護
 *
 * @author StockAnalysis
 */
object StockNameTrie {

    private const val TAG = "StockNameTrie"

    /** Trie 是否已構建完成 */
    @Volatile
    var isBuilt: Boolean = false
        private set

    /** 中文名稱 Trie 樹根節點 */
    private var nameTrie: TrieNode = TrieNode()

    /** 拼音縮寫 Trie 樹根節點 */
    private var pinyinTrie: TrieNode = TrieNode()

    /**
     * 所有股票的全拼索引列表。
     * 每個元素為 Triple(股票代碼, 股票名稱, 全拼小寫空格分隔)
     * 用於拼音全拼匹配（非 Trie 查詢，使用 contains 判斷）
     */
    private var pinyinFullList: List<Triple<String, String, String>> = emptyList()

    /**
     * ## Trie 樹節點
     *
     * 每個節點包含：
     * - [children]：子節點映射（字符 → 子 TrieNode）
     * - [codes]：經過此節點的股票代碼列表
     * - [names]：對應 [codes] 的股票名稱列表
     */
    class TrieNode {
        val children: MutableMap<Char, TrieNode> = mutableMapOf()
        val codes: MutableList<String> = mutableListOf()
        val names: MutableList<String> = mutableListOf()
    }

    /**
     * ## 匹配類型枚舉
     *
     * 定義搜索結果的匹配方式，用於區分匹配精度和計算置信度。
     */
    enum class MatchType {
        /** 精確匹配：輸入與完整股票名完全相同 */
        EXACT_NAME,
        /** 前綴匹配：股票名以輸入開頭 */
        PREFIX_NAME,
        /** 子串匹配：輸入包含在股票名中 */
        SUBSTRING_NAME,
        /** 拼音縮寫匹配：輸入與股票名的拼音首字母縮寫匹配 */
        PINYIN_ABBR,
        /** 拼音全拼匹配：輸入與股票名的全拼匹配 */
        PINYIN_FULL
    }

    /**
     * ## Trie 搜索結果
     *
     * @property code 股票代碼，如 "600519"
     * @property name 股票名稱，如 "貴州茅台"
     * @property matchType 匹配類型
     * @property confidence 置信度（0.0 ~ 1.0），越高表示匹配越精確
     */
    data class TrieResult(
        val code: String,
        val name: String,
        val matchType: MatchType,
        val confidence: Float
    )

    /** 構建鎖對象，用於 synchronized 區塊 */
    private val buildLock = Any()

    /**
     * ## 構建 Trie 索引
     *
     * 從本地資料庫讀取全部股票基本信息，將股票名稱插入 nameTrie，
     * 同時為每個股票名構建拼音縮寫索引插入 pinyinTrie，
     * 並記錄全拼用於拼音全拼匹配。
     *
     * 此方法為冪等操作：只在首次調用時執行構建，後續調用直接返回。
     * **必須在 IO 線程中調用**，因為涉及資料庫查詢和大量數據處理。
     *
     * 注意：suspend 函數不能在 synchronized 區塊中調用，
     * 因此使用 Mutex 實現協程安全的互斥鎖。
     *
     * @param context Android Context，用於獲取資料庫實例
     */
    suspend fun build(context: Context) {
        if (isBuilt) {
            Log.d(TAG, "Trie 已構建，跳過重複構建")
            return
        }

        // 使用 kotlinx.coroutines.sync.Mutex 實現協程安全的互斥
        // 避免在 synchronized 中調用 suspend 函數
        val stocks: List<StockBasicEntity> = try {
            StockDatabase.getInstance(context).stockBasicDao().getAll()
        } catch (e: Exception) {
            Log.e(TAG, "讀取股票資料庫失敗", e)
            return
        }

        synchronized(buildLock) {
            if (isBuilt) {
                Log.d(TAG, "Trie 已被其他線程構建，跳過")
                return
            }

            buildTrie(stocks)
        }
    }

    /**
     * 從股票列表構建 Trie 樹（純 CPU 操作，可在 synchronized 中執行）。
     *
     * @param stocks 從資料庫讀取的全部股票列表
     */
    private fun buildTrie(stocks: List<StockBasicEntity>) {
        if (stocks.isEmpty()) {
            Log.w(TAG, "資料庫中無股票數據，跳過 Trie 構建")
            return
        }

        Log.i(TAG, "讀取到 ${stocks.size} 隻股票，開始構建 Trie...")
        val startTime = System.currentTimeMillis()

        val newNameTrie = TrieNode()
        val newPinyinTrie = TrieNode()
        val newPinyinFullList = mutableListOf<Triple<String, String, String>>()

        for (stock in stocks) {
            val code = stock.code
            val name = stock.name

            if (name.isEmpty()) continue

            // ── 插入 nameTrie（中文名逐字） ──
            insertIntoTrie(newNameTrie, name, code, name)

            // ── 插入 pinyinTrie（拼音縮寫逐字母） ──
            val abbr = PinyinUtils.toPinyinAbbrLower(name)
            if (abbr.isNotEmpty()) {
                insertIntoTrie(newPinyinTrie, abbr, code, name)
            }

            // ── 記錄全拼用於全拼匹配 ──
            val full = PinyinUtils.toPinyinFullLower(name)
            newPinyinFullList.add(Triple(code, name, full))
        }

        nameTrie = newNameTrie
        pinyinTrie = newPinyinTrie
        pinyinFullList = newPinyinFullList
        isBuilt = true

        val elapsed = System.currentTimeMillis() - startTime
        Log.i(TAG, "StockNameTrie 構建完成，耗時 ${elapsed}ms，共 ${stocks.size} 隻股票")
    }

    /**
     * 將一個字串插入 Trie 樹。
     * 在沿途每個節點都記錄該股票的 code 和 name，
     * 以便前綴匹配時能直接從中間節點取得所有經過的股票。
     *
     * @param root Trie 樹根節點
     * @param word 要插入的字串（中文名或拼音縮寫）
     * @param code 股票代碼
     * @param name 股票名稱
     */
    private fun insertIntoTrie(root: TrieNode, word: String, code: String, name: String) {
        var node = root
        for (ch in word) {
            node.codes.add(code)
            node.names.add(name)
            node = node.children.getOrPut(ch) { TrieNode() }
        }
        // 在最終節點也記錄（代表完整匹配）
        node.codes.add(code)
        node.names.add(name)
    }

    /**
     * ## 搜索匹配的股票
     *
     * 根據用戶輸入搜索匹配的股票，依次嘗試以下匹配策略：
     * 1. **精確匹配**：input == 完整股票名（confidence = 0.95）
     * 2. **前綴匹配**：股票名以 input 開頭（confidence = 0.90）
     * 3. **子串匹配**：input 包含在股票名中（confidence = 0.80）
     * 4. **拼音縮寫匹配**：input 的拼音縮寫匹配（confidence = 0.75）
     * 5. **拼音全拼匹配**：input 與股票全拼匹配（confidence = 0.70）
     *
     * 結果按 confidence 降序排序，同一股票只保留最高置信度的匹配。
     *
     * @param input 用戶搜索輸入，可以是中文名、拼音縮寫（如 "gzmt"）或拼音全拼（如 "maotai"）
     * @return 匹配的股票列表，按置信度降序排列；若 Trie 未構建則返回空列表
     */
    fun search(input: String): List<TrieResult> {
        if (!isBuilt || input.isBlank()) return emptyList()

        val trimmed = input.trim()
        if (trimmed.isEmpty()) return emptyList()

        // 使用 LinkedHashSet 去重，保留插入順序（先匹配的優先）
        val resultMap = linkedMapOf<String, TrieResult>()

        // ── 1. 精確匹配 ──
        searchExact(trimmed, resultMap)

        // ── 2. 前綴匹配 ──
        searchPrefix(trimmed, resultMap)

        // ── 3. 子串匹配 ──
        searchSubstring(trimmed, resultMap)

        // ── 4. 拼音縮寫匹配 ──
        searchPinyinAbbr(trimmed, resultMap)

        // ── 5. 拼音全拼匹配 ──
        searchPinyinFull(trimmed, resultMap)

        // 按 confidence 降序排序
        return resultMap.values.sortedByDescending { it.confidence }
    }

    /**
     * 精確匹配：遍歷 nameTrie 的所有終端節點，
     * 檢查是否有股票名與 input 完全相同。
     */
    private fun searchExact(input: String, resultMap: MutableMap<String, TrieResult>) {
        var node = nameTrie
        for (ch in input) {
            node = node.children[ch] ?: return
        }
        // 走到終端節點，檢查是否有股票名恰好等於 input
        val idx = node.names.indexOf(input)
        if (idx >= 0 && idx < node.codes.size) {
            val code = node.codes[idx]
            if (code !in resultMap) {
                resultMap[code] = TrieResult(
                    code = code,
                    name = input,
                    matchType = MatchType.EXACT_NAME,
                    confidence = 0.95f
                )
            }
        }
    }

    /**
     * 前綴匹配：從 nameTrie 中找到以 input 為前綴的所有股票。
     * 沿 Trie 路徑走到 input 的最後一個字符對應的節點，
     * 該節點及其所有子孫節點的 codes/names 均為前綴匹配結果。
     */
    private fun searchPrefix(input: String, resultMap: MutableMap<String, TrieResult>) {
        var node = nameTrie
        for (ch in input) {
            node = node.children[ch] ?: return
        }

        // 走到 input 結尾節點，收集所有經過此節點的股票（前綴匹配）
        val visitedCodes = mutableSetOf<String>()
        collectAll(node, visitedCodes)

        for (i in node.codes.indices) {
            val code = node.codes[i]
            val name = node.names[i]
            if (code in resultMap || code in visitedCodes) continue
            visitedCodes.add(code)

            // 確認股票名確實以 input 開頭（排除 Trie 中因共用前綴而誤匹配的情況）
            if (name.startsWith(input) && name != input) {
                resultMap[code] = TrieResult(
                    code = code,
                    name = name,
                    matchType = MatchType.PREFIX_NAME,
                    confidence = 0.90f
                )
            }
        }
    }

    /**
     * 子串匹配：遍歷 nameTrie 中所有終端節點，
     * 檢查是否有股票名包含 input 作為子串。
     * 使用 DFS 收集所有終端節點的股票，然後過濾。
     */
    private fun searchSubstring(input: String, resultMap: MutableMap<String, TrieResult>) {
        val allStocks = collectAllStocks(nameTrie)
        for ((code, name) in allStocks) {
            if (code in resultMap) continue
            if (name.contains(input)) {
                resultMap[code] = TrieResult(
                    code = code,
                    name = name,
                    matchType = MatchType.SUBSTRING_NAME,
                    confidence = 0.80f
                )
            }
        }
    }

    /**
     * 拼音縮寫匹配：將 input 轉為拼音縮寫，然後在 pinyinTrie 中做前綴匹配。
     * 支援用戶直接輸入拼音縮寫（如 "gzmt"）進行搜索。
     */
    private fun searchPinyinAbbr(input: String, resultMap: MutableMap<String, TrieResult>) {
        val inputLower = input.lowercase()

        // 在 pinyinTrie 中做前綴查找
        var node = pinyinTrie
        for (ch in inputLower) {
            node = node.children[ch] ?: return
        }

        // 收集此節點下所有股票
        val collected = mutableSetOf<String>()
        collectAll(node, collected)

        for (i in node.codes.indices) {
            val code = node.codes[i]
            val name = node.names[i]
            if (code in resultMap || code in collected) continue
            collected.add(code)

            // 驗證：股票名的拼音縮寫確實以 input 開頭
            val stockAbbr = PinyinUtils.toPinyinAbbrLower(name)
            if (stockAbbr.startsWith(inputLower)) {
                resultMap[code] = TrieResult(
                    code = code,
                    name = name,
                    matchType = MatchType.PINYIN_ABBR,
                    confidence = 0.75f
                )
            }
        }
    }

    /**
     * 拼音全拼匹配：將 input 在 [pinyinFullList] 中做子串搜索。
     * 同時嘗試帶空格和不帶空格的匹配。
     *
     * 例如輸入 "maotai" 可以匹配到 "貴州茅台"（全拼 "gui zhou mao tai"）。
     */
    private fun searchPinyinFull(input: String, resultMap: MutableMap<String, TrieResult>) {
        val inputLower = input.lowercase().trim()

        for ((code, name, fullPinyin) in pinyinFullList) {
            if (code in resultMap) continue

            // 帶空格匹配（如 "mao tai"）
            val matchWithSpace = fullPinyin.contains(inputLower)
            // 不帶空格匹配（如 "maotai"）
            val matchNoSpace = fullPinyin.replace(" ", "").contains(inputLower)

            if (matchWithSpace || matchNoSpace) {
                resultMap[code] = TrieResult(
                    code = code,
                    name = name,
                    matchType = MatchType.PINYIN_FULL,
                    confidence = 0.70f
                )
            }
        }
    }

    /**
     * 從給定節點開始，遞迴收集所有子孫節點中出現的股票代碼。
     * 用於前綴匹配時收集以某前綴開頭的所有股票。
     *
     * @param node 起始節點
     * @param collected 已收集的股票代碼集合（去重）
     */
    private fun collectAll(node: TrieNode, collected: MutableSet<String>) {
        for (i in node.codes.indices) {
            collected.add(node.codes[i])
        }
        for (child in node.children.values) {
            collectAll(child, collected)
        }
    }

    /**
     * 從給定節點開始，DFS 遍歷所有終端節點，收集所有 (code, name) 對。
     * 用於子串匹配時需要遍歷全部股票。
     *
     * @param root 起始節點（通常為 nameTrie 根節點）
     * @return 所有終端節點中的 (code, name) 對，去重後的列表
     */
    private fun collectAllStocks(root: TrieNode): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val seen = mutableSetOf<String>()

        fun dfs(node: TrieNode) {
            for (i in node.codes.indices) {
                val code = node.codes[i]
                if (code !in seen) {
                    seen.add(code)
                    result.add(code to node.names[i])
                }
            }
            for (child in node.children.values) {
                dfs(child)
            }
        }

        dfs(root)
        return result
    }
}
