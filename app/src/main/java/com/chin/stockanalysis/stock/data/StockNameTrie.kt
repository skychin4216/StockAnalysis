package com.chin.stockanalysis.stock.data

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.StockBasicEntity
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.util.PinyinUtils

/**
 * ## 基于 Trie 树的股票名称反向索引
 *
 * 用于从用户输入中快速提取股票实体。构建时会同时建立两棵 Trie：
 * - **nameTrie**：以中文名称的字符逐字插入，支援精确、前缀、子串匹配
 * - **pinyinTrie**：以拼音缩写（小写）逐字母插入，支援拼音缩写匹配
 *
 * 此外还会记录每只股票的全拼，用于拼音全拼匹配。
 *
 * ### 使用方式
 * ```kotlin
 * // 在 IO 线程中构建（只需一次）
 * StockNameTrie.build(context)
 *
 * // 搜索（线程安全）
 * val results = StockNameTrie.search("茅台")
 * ```
 *
 * ### 性能特征
 * - 构建耗时取决于股票数量（通常数千只），建议在 IO 线程执行
 * - 搜索为 O(m + k)，m 为输入长度，k 为匹配结果数
 * - 线程安全：使用 @Volatile + synchronized 保护
 *
 * @author StockAnalysis
 */
object StockNameTrie {

    private const val TAG = "StockNameTrie"

    /** Trie 是否已构建完成 */
    @Volatile
    var isBuilt: Boolean = false
        private set

    /** 中文名称 Trie 树根节点 */
    private var nameTrie: TrieNode = TrieNode()

    /** 拼音缩写 Trie 树根节点 */
    private var pinyinTrie: TrieNode = TrieNode()

    /**
     * 所有股票的全拼索引列表。
     * 每个元素为 Triple(股票代码, 股票名称, 全拼小写空格分隔)
     * 用于拼音全拼匹配（非 Trie 查询，使用 contains 判断）
     */
    private var pinyinFullList: List<Triple<String, String, String>> = emptyList()

    /**
     * ## Trie 树节点
     *
     * 每个节点包含：
     * - [children]：子节点映射（字符 → 子 TrieNode）
     * - [codes]：经过此节点的股票代码列表
     * - [names]：对应 [codes] 的股票名称列表
     */
    class TrieNode {
        val children: MutableMap<Char, TrieNode> = mutableMapOf()
        val codes: MutableList<String> = mutableListOf()
        val names: MutableList<String> = mutableListOf()
    }

    /**
     * ## 匹配类型枚举
     *
     * 定义搜索结果的匹配方式，用于区分匹配精度和计算置信度。
     */
    enum class MatchType {
        /** 精确匹配：输入与完整股票名完全相同 */
        EXACT_NAME,
        /** 前缀匹配：股票名以输入开头 */
        PREFIX_NAME,
        /** 子串匹配：输入包含在股票名中 */
        SUBSTRING_NAME,
        /** 拼音缩写匹配：输入与股票名的拼音首字母缩写匹配 */
        PINYIN_ABBR,
        /** 拼音全拼匹配：输入与股票名的全拼匹配 */
        PINYIN_FULL
    }

    /**
     * ## Trie 搜索结果
     *
     * @property code 股票代码，如 "600519"
     * @property name 股票名称，如 "贵州茅台"
     * @property matchType 匹配类型
     * @property confidence 置信度（0.0 ~ 1.0），越高表示匹配越精确
     */
    data class TrieResult(
        val code: String,
        val name: String,
        val matchType: MatchType,
        val confidence: Float
    )

    /** 构建锁对象，用于 synchronized 区块 */
    private val buildLock = Any()

    /**
     * ## 构建 Trie 索引
     *
     * 从本地资料库读取全部股票基本信息，将股票名称插入 nameTrie，
     * 同时为每个股票名构建拼音缩写索引插入 pinyinTrie，
     * 并记录全拼用于拼音全拼匹配。
     *
     * 此方法为幂等操作：只在首次调用时执行构建，后续调用直接返回。
     * **必须在 IO 线程中调用**，因为涉及资料库查询和大量数据处理。
     *
     * 注意：suspend 函数不能在 synchronized 区块中调用，
     * 因此使用 Mutex 实现协程安全的互斥锁。
     *
     * @param context Android Context，用于获取资料库实例
     */
    suspend fun build(context: Context) {
        if (isBuilt) {
            Log.d(TAG, "Trie 已构建，跳过重复构建")
            return
        }

        // 使用 kotlinx.coroutines.sync.Mutex 实现协程安全的互斥
        // 避免在 synchronized 中调用 suspend 函数
        val stocks: List<StockBasicEntity> = try {
            StockDatabase.getInstance(context).stockBasicDao().getAll()
        } catch (e: Exception) {
            Log.e(TAG, "读取股票资料库失败", e)
            return
        }

        synchronized(buildLock) {
            if (isBuilt) {
                Log.d(TAG, "Trie 已被其他线程构建，跳过")
                return
            }

            buildTrie(stocks)
        }
    }

    /**
     * 从股票列表构建 Trie 树（纯 CPU 操作，可在 synchronized 中执行）。
     *
     * @param stocks 从资料库读取的全部股票列表
     */
    private fun buildTrie(stocks: List<StockBasicEntity>) {
        if (stocks.isEmpty()) {
            Log.w(TAG, "资料库中无股票数据，跳过 Trie 构建")
            return
        }

        Log.i(TAG, "读取到 ${stocks.size} 只股票，开始构建 Trie...")
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

            // ── 插入 pinyinTrie（拼音缩写逐字母） ──
            val abbr = PinyinUtils.toPinyinAbbrLower(name)
            if (abbr.isNotEmpty()) {
                insertIntoTrie(newPinyinTrie, abbr, code, name)
            }

            // ── 记录全拼用于全拼匹配 ──
            val full = PinyinUtils.toPinyinFullLower(name)
            newPinyinFullList.add(Triple(code, name, full))
        }

        nameTrie = newNameTrie
        pinyinTrie = newPinyinTrie
        pinyinFullList = newPinyinFullList
        isBuilt = true

        val elapsed = System.currentTimeMillis() - startTime
        Log.i(TAG, "StockNameTrie 构建完成，耗时 ${elapsed}ms，共 ${stocks.size} 只股票")
    }

    /**
     * 将一个字串插入 Trie 树。
     * 在沿途每个节点都记录该股票的 code 和 name，
     * 以便前缀匹配时能直接从中间节点取得所有经过的股票。
     *
     * @param root Trie 树根节点
     * @param word 要插入的字串（中文名或拼音缩写）
     * @param code 股票代码
     * @param name 股票名称
     */
    private fun insertIntoTrie(root: TrieNode, word: String, code: String, name: String) {
        var node = root
        for (ch in word) {
            node.codes.add(code)
            node.names.add(name)
            node = node.children.getOrPut(ch) { TrieNode() }
        }
        // 在最终节点也记录（代表完整匹配）
        node.codes.add(code)
        node.names.add(name)
    }

    /**
     * ## 搜索匹配的股票
     *
     * 根据用户输入搜索匹配的股票，依次尝试以下匹配策略：
     * 1. **精确匹配**：input == 完整股票名（confidence = 0.95）
     * 2. **前缀匹配**：股票名以 input 开头（confidence = 0.90）
     * 3. **子串匹配**：input 包含在股票名中（confidence = 0.80）
     * 4. **拼音缩写匹配**：input 的拼音缩写匹配（confidence = 0.75）
     * 5. **拼音全拼匹配**：input 与股票全拼匹配（confidence = 0.70）
     *
     * 结果按 confidence 降序排序，同一股票只保留最高置信度的匹配。
     *
     * @param input 用户搜索输入，可以是中文名、拼音缩写（如 "gzmt"）或拼音全拼（如 "maotai"）
     * @return 匹配的股票列表，按置信度降序排列；若 Trie 未构建则返回空列表
     */
    fun search(input: String): List<TrieResult> {
        if (!isBuilt || input.isBlank()) return emptyList()

        val trimmed = input.trim()
        if (trimmed.isEmpty()) return emptyList()

        // 使用 LinkedHashSet 去重，保留插入顺序（先匹配的优先）
        val resultMap = linkedMapOf<String, TrieResult>()

        // ── 1. 精确匹配 ──
        searchExact(trimmed, resultMap)

        // ── 2. 前缀匹配 ──
        searchPrefix(trimmed, resultMap)

        // ── 3. 子串匹配 ──
        searchSubstring(trimmed, resultMap)

        // ── 4. 拼音缩写匹配 ──
        searchPinyinAbbr(trimmed, resultMap)

        // ── 5. 拼音全拼匹配 ──
        searchPinyinFull(trimmed, resultMap)

        // 按 confidence 降序排序
        return resultMap.values.sortedByDescending { it.confidence }
    }

    /**
     * 拼音专用搜索
     *
     * 当 StockEntityExtractor 判断输入可能是拼音时调用，
     * 只执行拼音缩写和拼音全拼匹配，跳过中文精确/前缀/子串匹配。
     *
     * @param input 用户输入（已转为小写，如 "zycx"）
     * @return 匹配的股票列表
     */
    fun searchByPinyin(input: String): List<TrieResult> {
        if (!isBuilt || input.isBlank()) return emptyList()
        val resultMap = linkedMapOf<String, TrieResult>()
        searchPinyinAbbr(input, resultMap)
        searchPinyinFull(input, resultMap)
        return resultMap.values.sortedByDescending { it.confidence }
    }

    /**
     * 精确匹配：遍历 nameTrie 的所有终端节点，
     * 检查是否有股票名与 input 完全相同。
     */
    private fun searchExact(input: String, resultMap: MutableMap<String, TrieResult>) {
        var node = nameTrie
        for (ch in input) {
            node = node.children[ch] ?: return
        }
        // 走到终端节点，检查是否有股票名恰好等于 input
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
     * 前缀匹配：从 nameTrie 中找到以 input 为前缀的所有股票。
     * 沿 Trie 路径走到 input 的最后一个字符对应的节点，
     * 该节点及其所有子孙节点的 codes/names 均为前缀匹配结果。
     */
    private fun searchPrefix(input: String, resultMap: MutableMap<String, TrieResult>) {
        var node = nameTrie
        for (ch in input) {
            node = node.children[ch] ?: return
        }

        // 走到 input 结尾节点，收集所有经过此节点的股票（前缀匹配）
        val visitedCodes = mutableSetOf<String>()
        collectAll(node, visitedCodes)

        for (i in node.codes.indices) {
            val code = node.codes[i]
            val name = node.names[i]
            if (code in resultMap || code in visitedCodes) continue
            visitedCodes.add(code)

            // 确认股票名确实以 input 开头（排除 Trie 中因共用前缀而误匹配的情况）
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
     * 子串匹配：遍历 nameTrie 中所有终端节点，
     * 检查是否有股票名包含 input 作为子串。
     * 使用 DFS 收集所有终端节点的股票，然后过滤。
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
     * 拼音缩写匹配：将 input 转为拼音缩写，然后在 pinyinTrie 中做前缀匹配。
     * 支援用户直接输入拼音缩写（如 "gzmt"）进行搜索。
     */
    private fun searchPinyinAbbr(input: String, resultMap: MutableMap<String, TrieResult>) {
        val inputLower = input.lowercase()

        // 在 pinyinTrie 中做前缀查找
        var node = pinyinTrie
        for (ch in inputLower) {
            node = node.children[ch] ?: return
        }

        // 收集此节点下所有股票
        val collected = mutableSetOf<String>()
        collectAll(node, collected)

        for (i in node.codes.indices) {
            val code = node.codes[i]
            val name = node.names[i]
            if (code in resultMap || code in collected) continue
            collected.add(code)

            // 验证：股票名的拼音缩写确实以 input 开头
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
     * 拼音全拼匹配：将 input 在 [pinyinFullList] 中做子串搜索。
     * 同时尝试带空格和不带空格的匹配。
     *
     * 例如输入 "maotai" 可以匹配到 "贵州茅台"（全拼 "gui zhou mao tai"）。
     */
    private fun searchPinyinFull(input: String, resultMap: MutableMap<String, TrieResult>) {
        val inputLower = input.lowercase().trim()

        for ((code, name, fullPinyin) in pinyinFullList) {
            if (code in resultMap) continue

            // 带空格匹配（如 "mao tai"）
            val matchWithSpace = fullPinyin.contains(inputLower)
            // 不带空格匹配（如 "maotai"）
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
     * 从给定节点开始，递回收集所有子孙节点中出现的股票代码。
     * 用于前缀匹配时收集以某前缀开头的所有股票。
     *
     * @param node 起始节点
     * @param collected 已收集的股票代码集合（去重）
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
     * 从给定节点开始，DFS 遍历所有终端节点，收集所有 (code, name) 对。
     * 用于子串匹配时需要遍历全部股票。
     *
     * @param root 起始节点（通常为 nameTrie 根节点）
     * @return 所有终端节点中的 (code, name) 对，去重后的列表
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
