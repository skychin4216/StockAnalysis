package com.chin.stockanalysis.ai

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.data.StockNameTrie
import com.chin.stockanalysis.stock.data.StockNameTrie.MatchType as TrieMatchType
import com.chin.stockanalysis.stock.data.StockNameTrie.TrieResult

/**
 * 股票實體提取引擎
 *
 * 從用戶自然語言輸入中提取股票實體（名稱→代碼）。
 * 三層級聯：L1 本地規則 → L2 Trie 詞典 → L3 網路模糊匹配
 *
 * ## 使用範例
 * ```
 * val entities = StockEntityExtractor.extract("分析兆易創新和韋爾股份", context)
 * // → [ExtractedEntity(text="兆易創新", code="603986", name="兆易創新", ...),
 * //    ExtractedEntity(text="韋爾股份", code="603501", name="韋爾股份", ...)]
 * ```
 *
 * ## 匹配優先級
 * 1. **EXACT_CODE** / **PREFIX_CODE** — 精確代碼匹配，置信度最高
 * 2. **EXACT_NAME** — 精確名稱匹配
 * 3. **PREFIX_NAME** — 前綴名稱匹配
 * 4. **SUBSTRING_NAME** — 子串匹配
 * 5. **PINYIN_ABBR** / **PINYIN_FULL** — 拼音匹配
 * 6. **FUZZY_API** — 網路模糊匹配（降級方案）
 */
object StockEntityExtractor {

    /** 匹配類型，按優先級從高到低排列 */
    enum class MatchType {
        EXACT_CODE,      // 精確代碼: "600519"
        PREFIX_CODE,     // 帶前綴代碼: "sh600519"
        EXACT_NAME,      // 精確名稱: "兆易創新"
        PREFIX_NAME,     // 前綴名稱: "兆易"
        SUBSTRING_NAME,  // 子串匹配: "創新"（在 "兆易創新" 中）
        PINYIN_ABBR,     // 拼音縮寫: "zycx"
        PINYIN_FULL,     // 拼音全拼: "zhao yi chuang xin"
        FUZZY_API        // 網路匹配（降級）
    }

    /**
     * 提取到的股票實體
     *
     * @property text    原文匹配片段（如用戶輸入中的 "兆易創新"）
     * @property code    股票代碼（如 "603986"）
     * @property name    標準名稱（如 "兆易創新"）
     * @property matchType 匹配類型
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

    /** 需要從輸入中剝離的查詢前後綴詞（按長度降序，長詞優先替換） */
    private val STRIP_WORDS = listOf(
        "分析一下", "分析分析", "走勢如何", "怎麼樣", "是多少",
        "還能買嗎", "能不能買", "可以買嗎", "能買嗎",
        "可以追漲", "還能追漲", "的走勢", "的行情",
        "的最新股價", "最新股價", "的股價",
        "價格是多少", "多少錢", "什麼價格", "什麼價",
        "幫我分析", "幫我看看", "幫我查",
        "分析", "查詢", "查看", "走勢", "行情", "價格", "股價",
        "追漲", "技術面", "基本面", "今天", "最新", "最近"
    )

    /** 非股票詞黑名單——匹配到這些詞時直接跳過 */
    private val BLACKLIST = setOf(
        "今天", "今日", "最新", "現在", "目前", "當前",
        "股票", "股市", "大盤", "行情", "指數", "推薦", "分析",
        "怎麼", "如何", "什麼", "為什麼", "哪個", "哪只",
        "買入", "賣出", "買賣", "投資", "基金", "期貨", "外匯",
        "上漲", "下跌", "漲停", "跌停", "漲幅", "跌幅",
        "走勢", "價格", "股價", "查詢", "查看",
        "量化", "選股", "策略", "回測", "排名", "打分",
        "板塊", "行業", "概念", "產業鏈"
    )

    /** 多股票連接詞，用於拆分用戶輸入中的多個股票名稱 */
    private val CONNECTORS = Regex("和|跟|與|還有|以及|、|,|，")

    /**
     * 常用股票名稱→代碼映射（Trie 未構建時的降級方案）
     *
     * 覆蓋 A 股市場最常見的龍頭股，確保即使 Trie 還沒構建也能基本識別。
     */
    private val FALLBACK_STOCK_MAP = mapOf(
        "兆易創新" to "603986", "兆易创新" to "603986",
        "貴州茅台" to "600519", "茅台" to "600519",
        "寧德時代" to "300750", "宁德" to "300750",
        "比亞迪" to "002594", "比亚迪" to "002594",
        "騰訊控股" to "00700", "腾讯" to "00700",
        "阿里巴巴" to "09988", "阿里" to "09988",
        "中國平安" to "601318", "平安" to "601318",
        "招商銀行" to "600036", "招行" to "600036",
        "格力電器" to "000651", "格力" to "000651",
        "立訊精密" to "002475", "立讯" to "002475",
        "韋爾股份" to "603501", "韦尔" to "603501",
        "東山精密" to "002384", "东山" to "002384",
        "中際旭創" to "300308", "中际" to "300308",
        "北方華創" to "002371", "北方" to "002371",
        "中芯國際" to "00981", "中芯" to "00981",
        "海康威視" to "002415", "海康" to "002415",
        "美的集團" to "000333", "美的" to "000333",
        "五糧液" to "000858",
        "恒瑞醫藥" to "600276", "恒瑞" to "600276",
        "藥明康德" to "603259", "药明" to "603259",
        "長江電力" to "600900",
        "比亞迪股份" to "01211",
        "快手" to "01024",
        "美團" to "03690",
        "京東" to "09618",
        "拼多多" to "PDD",
        "小米集團" to "01810", "小米" to "01810",
        "蔚來" to "NIO", "理想" to "LI", "小鵬" to "XPEV",
        "台積電" to "TSM",
        "英偉達" to "NVDA",
        "蘋果" to "AAPL", "苹果" to "AAPL",
        "微軟" to "MSFT",
        "谷歌" to "GOOGL",
        "亞馬遜" to "AMZN", "亚马逊" to "AMZN",
        "特斯拉" to "TSLA",
        "波克夏" to "BRK"
    )

    /**
     * 從用戶輸入中提取所有股票實體
     *
     * 處理流程：
     * 1. 剝離查詢詞（"分析"、"走勢" 等無意義詞彙）
     * 2. 優先提取精確代碼（6位數字 / sh+6位 / sz+6位）
     * 3. 按連接詞拆分，對每個片段進行 Trie 詞典匹配
     * 4. Trie 未命中時降級為拼音匹配
     *
     * @param input   用戶原始輸入（如 "分析兆易創新和韋爾股份"）
     * @param context Android Context（用於 Trie 初始化和網路查詢降級）
     * @return 提取的實體列表，按 confidence 降序排列，已按 code 去重
     */
    suspend fun extract(input: String, context: Context): List<ExtractedEntity> {
        // 確保 Trie 已構建（IO 操作，因此是 suspend）
        if (!StockNameTrie.isBuilt) {
            try {
                StockNameTrie.build(context)
            } catch (e: Exception) {
                Log.w(TAG, "Trie 構建失敗，將僅使用規則匹配", e)
            }
        }

        // Step 1: 剝離查詢詞，只保留可能包含股票名稱的片段
        var stripped = input
        for (w in STRIP_WORDS) {
            stripped = stripped.replace(w, " ")
        }
        stripped = stripped.replace(Regex("[\\s，。？！、,.?！\\d]"), " ").trim()

        // Step 2: 提取精確代碼（6位數字 / sh+6位 / sz+6位）
        // 如果找到代碼匹配，直接返回，不需要再嘗試名稱匹配
        val codeResults = extractCodes(input)
        if (codeResults.isNotEmpty()) return codeResults

        // Step 3: 按連接詞拆分，對每個片段提取名稱
        val clauses = stripped.split(CONNECTORS).filter { it.isNotBlank() }
        val allEntities = mutableListOf<ExtractedEntity>()

        for (clause in clauses) {
            val trimmed = clause.trim()
            // 過濾過短或黑名單詞彙
            if (trimmed.length < 2 || trimmed in BLACKLIST) continue

            // L1: Trie 詞典匹配（精確名稱 / 前綴 / 子串）
            val trieResults = trieMatch(trimmed)
            if (trieResults.isNotEmpty()) {
                allEntities.addAll(trieResults)
                continue
            }

            // L2: 拼音匹配（Trie 未命中的降級方案）
            val pinyinResults = pinyinMatch(trimmed)
            if (pinyinResults.isNotEmpty()) {
                allEntities.addAll(pinyinResults)
            }
        }

        // 按 code 去重，並按置信度降序排列
        return allEntities
            .distinctBy { it.code }
            .sortedByDescending { it.confidence }
    }

    /**
     * 提取股票代碼
     *
     * 支援兩種格式：
     * - 帶前綴：sh600519、sz000001（置信度 0.98）
     * - 純數字：600519、000001（置信度 0.95，需符合 A 股代碼規則）
     *
     * @param input 用戶原始輸入
     * @return 代碼匹配結果列表
     */
    private fun extractCodes(input: String): List<ExtractedEntity> {
        val results = mutableListOf<ExtractedEntity>()

        // sh/sz + 6位數字
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

        // 6位純數字（排除已被 sh/sz 匹配的位置）
        val pureCodePattern = Regex("(?<![sShHzZ])(\\d{6})(?![0-9])")
        pureCodePattern.findAll(input).forEach { match ->
            val code = match.groupValues[1]
            // 僅接受符合 A 股代碼規則的號碼
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
     * Trie 詞典匹配
     *
     * 委託 [StockNameTrie.search] 進行多維度匹配，
     * 並將 Trie 內部的 [TrieMatchType] 映射為本類的 [MatchType]。
     *
     * @param text 經過剝離後的用戶輸入片段
     * @return 匹配到的實體列表；若 Trie 未構建則返回空列表
     */
    private fun trieMatch(text: String): List<ExtractedEntity> {
        if (!StockNameTrie.isBuilt) {
            Log.d(TAG, "Trie 未構建，跳過詞典匹配: $text")
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
     * 拼音匹配（Trie 未命中的降級方案）
     *
     * 將輸入文本轉為小寫後，委託 [StockNameTrie.searchByPinyin] 進行拼音縮寫匹配。
     * 適用於用戶輸入拼音而非漢字的場景（如 "zycx" → "兆易創新"）。
     *
     * @param text 經過剝離後的用戶輸入片段
     * @return 匹配到的實體列表；若 Trie 未構建則返回空列表
     */
    private fun pinyinMatch(text: String): List<ExtractedEntity> {
        val inputLower = text.lowercase().trim()
        if (!StockNameTrie.isBuilt) {
            Log.d(TAG, "Trie 未構建，跳過拼音匹配: $text")
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
     * 用於 IntentPredictionEngine 等同步場景。
     * Trie 已構建時走 Trie 匹配；未構建時走 FALLBACK_STOCK_MAP 降級。
     *
     * @param input 用戶原始輸入
     * @return 提取的實體列表
     */
    fun extractSync(input: String): List<ExtractedEntity> {
        return doExtract(input, useFallback = !StockNameTrie.isBuilt)
    }

    /**
     * 核心提取邏輯（可同步調用）
     *
     * @param input 用戶原始輸入
     * @param useFallback 是否使用 FALLBACK_STOCK_MAP 降級（Trie 未構建時）
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
                // L2: Trie 詞典匹配
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
                // L2 降級：FALLBACK_STOCK_MAP 直接查找
                val fallback = fallbackMatch(trimmed)
                if (fallback != null) {
                    allEntities.add(fallback)
                }
            }
        }

        return allEntities.distinctBy { it.code }.sortedByDescending { it.confidence }
    }

    /**
     * 降級匹配：使用硬編碼的常用股票映射表
     */
    private fun fallbackMatch(text: String): ExtractedEntity? {
        // 1. 精確匹配
        FALLBACK_STOCK_MAP[text]?.let { code ->
            return ExtractedEntity(text, code, text, MatchType.EXACT_NAME, 0.90f)
        }
        // 2. 遍歷映射表，檢查是否有 key 包含在 text 中（子串匹配）
        for ((name, code) in FALLBACK_STOCK_MAP) {
            if (text.contains(name)) {
                return ExtractedEntity(text, code, name, MatchType.SUBSTRING_NAME, 0.85f)
            }
        }
        return null
    }
}
