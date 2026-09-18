package com.chin.stockanalysis.ai

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.data.StockNameTrie
import com.chin.stockanalysis.stock.data.StockNameTrie.MatchType as TrieMatchType
import com.chin.stockanalysis.stock.data.StockNameTrie.TrieResult

/**
 * 股票实体提取引擎
 *
 * 从用户自然语言输入中提取股票实体（名称→代码）。
 * 三层级联：L1 本地规则 → L2 Trie 词典 → L3 网路模糊匹配
 *
 * ## 使用范例
 * ```
 * val entities = StockEntityExtractor.extract("分析兆易创新和韦尔股份", context)
 * // → [ExtractedEntity(text="兆易创新", code="603986", name="兆易创新", ...),
 * //    ExtractedEntity(text="韦尔股份", code="603501", name="韦尔股份", ...)]
 * ```
 *
 * ## 匹配优先级
 * 1. **EXACT_CODE** / **PREFIX_CODE** — 精确代码匹配，置信度最高
 * 2. **EXACT_NAME** — 精确名称匹配
 * 3. **PREFIX_NAME** — 前缀名称匹配
 * 4. **SUBSTRING_NAME** — 子串匹配
 * 5. **PINYIN_ABBR** / **PINYIN_FULL** — 拼音匹配
 * 6. **FUZZY_API** — 网路模糊匹配（降级方案）
 */
object StockEntityExtractor {

    /** 匹配类型，按优先级从高到低排列 */
    enum class MatchType {
        EXACT_CODE,      // 精确代码: "600519"
        PREFIX_CODE,     // 带前缀代码: "sh600519"
        EXACT_NAME,      // 精确名称: "兆易创新"
        PREFIX_NAME,     // 前缀名称: "兆易"
        SUBSTRING_NAME,  // 子串匹配: "创新"（在 "兆易创新" 中）
        PINYIN_ABBR,     // 拼音缩写: "zycx"
        PINYIN_FULL,     // 拼音全拼: "zhao yi chuang xin"
        FUZZY_API        // 网路匹配（降级）
    }

    /**
     * 提取到的股票实体
     *
     * @property text    原文匹配片段（如用户输入中的 "兆易创新"）
     * @property code    股票代码（如 "603986"）
     * @property name    标准名称（如 "兆易创新"）
     * @property matchType 匹配类型
     * @property confidence 置信度 0.0~1.0，越高越可靠
     */
    data class ExtractedEntity(
        val text: String,
        val code: String,
        val name: String,
        val matchType: MatchType,
        val confidence: Float
    )

    private const val TAG = "EntityExtractor"

    /** 需要从输入中剥离的查询前后缀词（按长度降序，长词优先替换） */
    private val STRIP_WORDS = listOf(
        "分析一下", "分析分析", "走势如何", "怎么样", "是多少",
        "还能买吗", "能不能买", "可以买吗", "能买吗",
        "可以追涨", "还能追涨", "的走势", "的行情",
        "的最新股价", "最新股价", "的股价",
        "价格是多少", "多少钱", "什么价格", "什么价",
        "帮我分析", "帮我看看", "帮我查",
        "分析", "查询", "查看", "走势", "行情", "价格", "股价",
        "追涨", "技术面", "基本面", "今天", "最新", "最近"
    )

    /** 非股票词黑名单——匹配到这些词时直接跳过 */
    private val BLACKLIST = setOf(
        "今天", "今日", "最新", "现在", "目前", "当前",
        "股票", "股市", "大盘", "行情", "指数", "推荐", "分析",
        "怎么", "如何", "什么", "为什么", "哪个", "哪只",
        "买入", "卖出", "买卖", "投资", "基金", "期货", "外汇",
        "上涨", "下跌", "涨停", "跌停", "涨幅", "跌幅",
        "走势", "价格", "股价", "查询", "查看",
        "量化", "选股", "策略", "回测", "排名", "打分",
        "板块", "行业", "概念", "产业链"
    )

    /** 多股票连接词，用于拆分用户输入中的多个股票名称 */
    private val CONNECTORS = Regex("和|跟|与|还有|以及|、|,|，")

    /**
     * 常用股票名称→代码映射（Trie 未构建时的降级方案）
     *
     * 覆盖 A 股市场最常见的龙头股，确保即使 Trie 还没构建也能基本识别。
     */
    private val FALLBACK_STOCK_MAP = mapOf(
        "兆易创新" to "603986", "兆易创新" to "603986",
        "贵州茅台" to "600519", "茅台" to "600519",
        "宁德时代" to "300750", "宁德" to "300750",
        "比亚迪" to "002594", "比亚迪" to "002594",
        "腾讯控股" to "00700", "腾讯" to "00700",
        "阿里巴巴" to "09988", "阿里" to "09988",
        "中国平安" to "601318", "平安" to "601318",
        "招商银行" to "600036", "招行" to "600036",
        "格力电器" to "000651", "格力" to "000651",
        "立讯精密" to "002475", "立讯" to "002475",
        "韦尔股份" to "603501", "韦尔" to "603501",
        "东山精密" to "002384", "东山" to "002384",
        "中际旭创" to "300308", "中际" to "300308",
        "北方华创" to "002371", "北方" to "002371",
        "中芯国际" to "00981", "中芯" to "00981",
        "海康威视" to "002415", "海康" to "002415",
        "美的集团" to "000333", "美的" to "000333",
        "五粮液" to "000858",
        "恒瑞医药" to "600276", "恒瑞" to "600276",
        "药明康德" to "603259", "药明" to "603259",
        "长江电力" to "600900",
        "比亚迪股份" to "01211",
        "快手" to "01024",
        "美团" to "03690",
        "京东" to "09618",
        "拼多多" to "PDD",
        "小米集团" to "01810", "小米" to "01810",
        "蔚来" to "NIO", "理想" to "LI", "小鹏" to "XPEV",
        "台积电" to "TSM",
        "英伟达" to "NVDA",
        "苹果" to "AAPL", "苹果" to "AAPL",
        "微软" to "MSFT",
        "谷歌" to "GOOGL",
        "亚马逊" to "AMZN", "亚马逊" to "AMZN",
        "特斯拉" to "TSLA",
        "波克夏" to "BRK"
    )

    /**
     * 从用户输入中提取所有股票实体
     *
     * 处理流程：
     * 1. 剥离查询词（"分析"、"走势" 等无意义词汇）
     * 2. 优先提取精确代码（6位数字 / sh+6位 / sz+6位）
     * 3. 按连接词拆分，对每个片段进行 Trie 词典匹配
     * 4. Trie 未命中时降级为拼音匹配
     *
     * @param input   用户原始输入（如 "分析兆易创新和韦尔股份"）
     * @param context Android Context（用于 Trie 初始化和网路查询降级）
     * @return 提取的实体列表，按 confidence 降序排列，已按 code 去重
     */
    suspend fun extract(input: String, context: Context): List<ExtractedEntity> {
        // 确保 Trie 已构建（IO 操作，因此是 suspend）
        if (!StockNameTrie.isBuilt) {
            try {
                StockNameTrie.build(context)
            } catch (e: Exception) {
                Log.w(TAG, "Trie 构建失败，将仅使用规则匹配", e)
            }
        }

        // Step 1: 剥离查询词，只保留可能包含股票名称的片段
        var stripped = input
        for (w in STRIP_WORDS) {
            stripped = stripped.replace(w, " ")
        }
        stripped = stripped.replace(Regex("[\\s，。？！、,.?！\\d]"), " ").trim()

        // Step 2: 提取精确代码（6位数字 / sh+6位 / sz+6位）
        // 如果找到代码匹配，直接返回，不需要再尝试名称匹配
        val codeResults = extractCodes(input)
        if (codeResults.isNotEmpty()) return codeResults

        // Step 3: 按连接词拆分，对每个片段提取名称
        val clauses = stripped.split(CONNECTORS).filter { it.isNotBlank() }
        val allEntities = mutableListOf<ExtractedEntity>()

        for (clause in clauses) {
            val trimmed = clause.trim()
            // 过滤过短或黑名单词汇
            if (trimmed.length < 2 || trimmed in BLACKLIST) continue

            // L1: Trie 词典匹配（精确名称 / 前缀 / 子串）
            val trieResults = trieMatch(trimmed)
            if (trieResults.isNotEmpty()) {
                allEntities.addAll(trieResults)
                continue
            }

            // L2: 拼音匹配（Trie 未命中的降级方案）
            val pinyinResults = pinyinMatch(trimmed)
            if (pinyinResults.isNotEmpty()) {
                allEntities.addAll(pinyinResults)
            }
        }

        // 按 code 去重，并按置信度降序排列
        return allEntities
            .distinctBy { it.code }
            .sortedByDescending { it.confidence }
    }

    /**
     * 提取股票代码
     *
     * 支援两种格式：
     * - 带前缀：sh600519、sz000001（置信度 0.98）
     * - 纯数字：600519、000001（置信度 0.95，需符合 A 股代码规则）
     *
     * @param input 用户原始输入
     * @return 代码匹配结果列表
     */
    private fun extractCodes(input: String): List<ExtractedEntity> {
        val results = mutableListOf<ExtractedEntity>()

        // sh/sz + 6位数字
        val shszPattern = Regex("[sS][hHzZ](\\d{6})")
        shszPattern.findAll(input).forEach { match ->
            results.add(
                ExtractedEntity(
                    text = match.value,
                    code = match.groupValues[1],
                    name = "",
                    matchType = MatchType.PREFIX_CODE,
                    confidence = 0.98f
                )
            )
        }

        // 6位纯数字（排除已被 sh/sz 匹配的位置）
        val pureCodePattern = Regex("(?<![sShHzZ])(\\d{6})(?![0-9])")
        pureCodePattern.findAll(input).forEach { match ->
            val code = match.groupValues[1]
            // 仅接受符合 A 股代码规则的号码
            val firstChar = code[0]
            if (firstChar == '6' || firstChar == '0' || firstChar == '3'
                || firstChar == '4' || firstChar == '8'
            ) {
                results.add(
                    ExtractedEntity(
                        text = code,
                        code = code,
                        name = "",
                        matchType = MatchType.EXACT_CODE,
                        confidence = 0.95f
                    )
                )
            }
        }

        return results
    }

    /**
     * Trie 词典匹配
     *
     * 委托 [StockNameTrie.search] 进行多维度匹配，
     * 并将 Trie 内部的 [TrieMatchType] 映射为本类的 [MatchType]。
     *
     * @param text 经过剥离后的用户输入片段
     * @return 匹配到的实体列表；若 Trie 未构建则返回空列表
     */
    private fun trieMatch(text: String): List<ExtractedEntity> {
        if (!StockNameTrie.isBuilt) {
            Log.d(TAG, "Trie 未构建，跳过词典匹配: $text")
            return emptyList()
        }

        val trieResults: List<TrieResult> = StockNameTrie.search(text)
        return trieResults.map { r ->
            ExtractedEntity(
                text = text,
                code = r.code,
                name = r.name,
                matchType = when (r.matchType) {
                    TrieMatchType.EXACT_NAME -> MatchType.EXACT_NAME
                    TrieMatchType.PREFIX_NAME -> MatchType.PREFIX_NAME
                    TrieMatchType.SUBSTRING_NAME -> MatchType.SUBSTRING_NAME
                    TrieMatchType.PINYIN_ABBR -> MatchType.PINYIN_ABBR
                    TrieMatchType.PINYIN_FULL -> MatchType.PINYIN_FULL
                },
                confidence = r.confidence
            )
        }
    }

    /**
     * 拼音匹配（Trie 未命中的降级方案）
     *
     * 将输入文本转为小写后，委托 [StockNameTrie.searchByPinyin] 进行拼音缩写匹配。
     * 适用于用户输入拼音而非汉字的场景（如 "zycx" → "兆易创新"）。
     *
     * @param text 经过剥离后的用户输入片段
     * @return 匹配到的实体列表；若 Trie 未构建则返回空列表
     */
    private fun pinyinMatch(text: String): List<ExtractedEntity> {
        val inputLower = text.lowercase().trim()
        if (!StockNameTrie.isBuilt) {
            Log.d(TAG, "Trie 未构建，跳过拼音匹配: $text")
            return emptyList()
        }

        val results: List<TrieResult> = StockNameTrie.searchByPinyin(inputLower)
        return results.map { r ->
            ExtractedEntity(
                text = text,
                code = r.code,
                name = r.name,
                matchType = MatchType.PINYIN_ABBR,
                confidence = r.confidence
            )
        }
    }

    /**
     * 同步版本提取（不需要 Context）
     *
     * 用于 IntentPredictionEngine 等同步场景。
     * Trie 已构建时走 Trie 匹配；未构建时走 FALLBACK_STOCK_MAP 降级。
     *
     * @param input 用户原始输入
     * @return 提取的实体列表
     */
    fun extractSync(input: String): List<ExtractedEntity> {
        return doExtract(input, useFallback = !StockNameTrie.isBuilt)
    }

    /**
     * 核心提取逻辑（可同步调用）
     *
     * @param input 用户原始输入
     * @param useFallback 是否使用 FALLBACK_STOCK_MAP 降级（Trie 未构建时）
     */
    private fun doExtract(input: String, useFallback: Boolean = false): List<ExtractedEntity> {
        var stripped = input
        for (w in STRIP_WORDS) { stripped = stripped.replace(w, " ") }
        stripped = stripped.replace(Regex("[\\s，。？！、,.?！\\d]"), " ").trim()

        val codeResults = extractCodes(input)
        if (codeResults.isNotEmpty()) return codeResults

        val clauses = stripped.split(CONNECTORS).filter { it.isNotBlank() }
        val allEntities = mutableListOf<ExtractedEntity>()

        for (clause in clauses) {
            val trimmed = clause.trim()
            if (trimmed.length < 2 || trimmed in BLACKLIST) continue

            if (!useFallback) {
                // L2: Trie 词典匹配
                val trieResults = trieMatch(trimmed)
                if (trieResults.isNotEmpty()) {
                    allEntities.addAll(trieResults)
                    continue
                }
                val pinyinResults = pinyinMatch(trimmed)
                if (pinyinResults.isNotEmpty()) {
                    allEntities.addAll(pinyinResults)
                }
            } else {
                // L2 降级：FALLBACK_STOCK_MAP 直接查找
                val fallback = fallbackMatch(trimmed)
                if (fallback != null) {
                    allEntities.add(fallback)
                }
            }
        }

        return allEntities.distinctBy { it.code }.sortedByDescending { it.confidence }
    }

    /**
     * 降级匹配：使用硬编码的常用股票映射表
     */
    private fun fallbackMatch(text: String): ExtractedEntity? {
        // 1. 精确匹配
        FALLBACK_STOCK_MAP[text]?.let { code ->
            return ExtractedEntity(text, code, text, MatchType.EXACT_NAME, 0.90f)
        }
        // 2. 遍历映射表，检查是否有 key 包含在 text 中（子串匹配）
        for ((name, code) in FALLBACK_STOCK_MAP) {
            if (text.contains(name)) {
                return ExtractedEntity(text, code, name, MatchType.SUBSTRING_NAME, 0.85f)
            }
        }
        return null
    }

    /**
     * 同步解析股票名称/代码 → 标准化代码（非 suspend，无需 Context）
     *
     * 用于 UI 入口处的即时解析：用户输入中文名称时立即转换为代码。
     * 优先级：FALLBACK_STOCK_MAP → StockNameTrie（若已构建）
     *
     * @return 解析后的标准代码（如 "603986"），若无法解析返回 null
     */
    fun resolveSync(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null

        // 如果已经是标准代码格式，直接返回
        if (trimmed.length == 6 && trimmed.all { it.isDigit() }) return trimmed
        if (trimmed.length > 6 && (trimmed.startsWith("sh") || trimmed.startsWith("sz") || trimmed.startsWith("bj"))) {
            return trimmed.substring(2) // 去掉前缀返回纯数字
        }

        // 1. FALLBACK_STOCK_MAP 精确匹配
        FALLBACK_STOCK_MAP[trimmed]?.let { return it }

        // 2. FALLBACK_STOCK_MAP 子串匹配
        for ((name, code) in FALLBACK_STOCK_MAP) {
            if (trimmed.contains(name) || name.contains(trimmed)) return code
        }

        // 3. Trie 词典匹配（若已构建）
        if (StockNameTrie.isBuilt) {
            val results = StockNameTrie.search(trimmed)
            if (results.isNotEmpty()) return results.first().code
        }

        return null
    }
}
