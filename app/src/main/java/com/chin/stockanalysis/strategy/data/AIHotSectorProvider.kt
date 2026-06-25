package com.chin.stockanalysis.strategy.data

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.ApiProvider
import com.chin.stockanalysis.ai.AiProviderPool
import com.chin.stockanalysis.ui.Message
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * ## AI 熱門板塊查詢工具 v1.0
 *
 * 通過 DeepSeek/豆包 AI 直接查詢當前年度、月度、周度、昨日熱門板塊，
 * 替代原來的 ETF 漲跌 + 東方財富 compositeScore 判斷方式。
 *
 * AI 返回結構化 JSON，包含：
 * - annual_sectors: 年度熱門板塊（長期趨勢）
 * - monthly_sectors: 月度熱門板塊（中期趨勢）
 * - weekly_sectors: 周度熱門板塊（短期熱點）
 * - yesterday_sectors: 上一個交易日熱門板塊（最新資金流向）
 *
 * 緩存策略：每 3 小時刷新一次（避免頻繁調用 API）
 */
object AIHotSectorProvider {

    private const val TAG = "AIHotSectorProvider"
    private const val PREFS_NAME = "ai_hot_sectors_prefs"
    private const val KEY_CACHED_SECTORS = "cached_sectors_json"
    private const val KEY_LAST_FETCH_TIME = "last_fetch_time"
    private const val CACHE_DURATION_MS = 3 * 60 * 60 * 1000L  // 3 小時

    data class HotSectorResult(
        val annualSectors: List<String>,
        val monthlySectors: List<String>,
        val weeklySectors: List<String>,
        val yesterdaySectors: List<String>,
        val fetchTime: Long = System.currentTimeMillis()
    ) {
        /** 合併去重後的所有熱門板塊 */
        val allSectors: List<String> get() =
            (annualSectors + monthlySectors + weeklySectors + yesterdaySectors).distinct()
    }

    /**
     * 取得熱門板塊（優先從緩存，過期則 AI 查詢）
     */
    suspend fun getHotSectors(context: Context): HotSectorResult = withContext(Dispatchers.IO) {
        // 1. 嘗試緩存
        val cached = loadCache(context)
        if (cached != null) {
            Log.i(TAG, "📦 從緩存讀取熱門板塊: ${cached.allSectors.size} 個")
            return@withContext cached
        }

        // 2. AI 查詢
        Log.i(TAG, "🤖 開始 AI 查詢熱門板塊...")
        val result = try {
            fetchFromAI(context)
        } catch (e: Exception) {
            Log.w(TAG, "AI 查詢失敗，使用備用列表: ${e.message}")
            getDefaultHotSectors()
        }

        // 3. 寫入緩存
        saveCache(context, result)
        result
    }

    /**
     * 強制刷新（不讀緩存）
     */
    suspend fun forceRefresh(context: Context): HotSectorResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "🔄 強制刷新 AI 熱門板塊...")
        val result = try {
            fetchFromAI(context)
        } catch (e: Exception) {
            Log.w(TAG, "AI 查詢失敗，使用備用列表: ${e.message}")
            getDefaultHotSectors()
        }
        saveCache(context, result)
        result
    }

    /** 檢查緩存是否過期 */
    fun isCacheExpired(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastFetchTime = prefs.getLong(KEY_LAST_FETCH_TIME, 0L)
        return System.currentTimeMillis() - lastFetchTime > CACHE_DURATION_MS
    }

    // ════════════════════════════════════════
    // AI 查詢邏輯
    // ════════════════════════════════════════

    /**
     * 並行調用多個 AI Provider 查詢熱門板塊
     *
     * 將 4 個時間維度拆分到最多 4 個 Provider 並行查詢：
     *   - Provider 0 → 年度
     *   - Provider 1 → 月度
     *   - Provider 2 → 周度
     *   - Provider 3 → 昨日
     * 如果可用 Provider < 4，則用可用的 Provider 平分維度。
     */
    /** Task 輔助 data class */
    private data class Task(val dimension: String, val userMessage: String, val provider: ApiProvider, val providerName: String)

    private suspend fun fetchFromAI(context: Context): HotSectorResult = coroutineScope {
        val slots = AiProviderPool.acquireAllHealthy(context)
        if (slots.isEmpty()) throw IllegalStateException("無可用 AI Provider")

        val dimensions = listOf("annual", "monthly", "weekly", "yesterday")
        Log.i(TAG, "🚀 並行 AI 查詢: ${slots.size} 個 Provider × ${dimensions.size} 個維度")

        // 為每個維度建立任務，輪流分配給可用 Provider
        val tasks = dimensions.mapIndexed { i, dim ->
            val slot = slots[i % slots.size]
            Task(
                dimension = dim,
                userMessage = buildString {
                    appendLine("請列出當前A股市場在以下時間週期的熱門板塊（最多15個）：")
                    appendLine("- ${when(dim) {
                        "annual" -> "年度：持續受政策/資金關注的長期熱門板塊"
                        "monthly" -> "月度：近一個月資金流入明顯的中期熱門板塊"
                        "weekly" -> "周度：本週最活躍的短期熱點板塊"
                        else -> "昨日：上一個交易日表現最好的板塊"
                    }}")
                    appendLine()
                    appendLine("輸出嚴格JSON格式（不要markdown代碼塊）：")
                    appendLine("""{"${dim}_sectors":[]}""")
                },
                provider = slot.provider,
                providerName = slot.configName
            )
        }

        // 並行執行所有任務
        val results = tasks.map { task ->
            async(Dispatchers.IO) { querySingleProvider(task) }
        }.awaitAll()

        // 合併結果
        val annualSectors = mutableListOf<String>()
        val monthlySectors = mutableListOf<String>()
        val weeklySectors = mutableListOf<String>()
        val yesterdaySectors = mutableListOf<String>()

        results.forEachIndexed { i, sectors ->
            when (tasks[i].dimension) {
                "annual" -> annualSectors.addAll(sectors)
                "monthly" -> monthlySectors.addAll(sectors)
                "weekly" -> weeklySectors.addAll(sectors)
                "yesterday" -> yesterdaySectors.addAll(sectors)
            }
        }

        Log.i(TAG, "✅ 並行查詢完成: 年度${annualSectors.size} 月度${monthlySectors.size} 周度${weeklySectors.size} 昨日${yesterdaySectors.size}")
        HotSectorResult(
            annualSectors = annualSectors.distinct(),
            monthlySectors = monthlySectors.distinct(),
            weeklySectors = weeklySectors.distinct(),
            yesterdaySectors = yesterdaySectors.distinct()
        )
    }

    /** 對單個 Provider 執行查詢，返回板塊列表 */
    private suspend fun querySingleProvider(task: Task): List<String> {
        val (dimension, userMessage, provider, providerName) = task
        val resultRef = AtomicReference<String>()
        val latch = CountDownLatch(1)

        provider.sendMessageStream(
            messages = listOf(Message(content = userMessage, isUser = true)),
            systemPrompt = "你是一位專業的A股市場分析師，精通中國A股市場的板塊輪動和資金流向規律。你的任務是根據你的知識庫，列出指定的熱門A股板塊。",
            onSuccess = { chunk -> resultRef.set((resultRef.get() ?: "") + chunk) },
            onComplete = { fullContent ->
                resultRef.set(fullContent)
                latch.countDown()
            },
            onError = { errorMsg ->
                Log.e(TAG, "❌ $providerName 查詢[$dimension]失敗: $errorMsg")
                resultRef.set("")
                latch.countDown()
            }
        )

        val success = latch.await(30, TimeUnit.SECONDS)
        val rawResponse = resultRef.get() ?: ""
        if (!success || rawResponse.isBlank()) {
            Log.w(TAG, "⏰ $providerName 查詢[$dimension]超時或返回為空")
            return emptyList()
        }

        Log.i(TAG, "  ✅ $providerName → $dimension: ${rawResponse.length} 字")
        return parseSingleDimensionResponse(rawResponse, "${dimension}_sectors")
    }

    /** 解析單個維度的回應 */
    private fun parseSingleDimensionResponse(raw: String, key: String): List<String> {
        var json = raw.trim()
        val jsonBlockRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)```")
        val match = jsonBlockRegex.find(json)
        if (match != null) json = match.groupValues[1].trim()
        val braceStart = json.indexOf('{')
        val braceEnd = json.lastIndexOf('}')
        if (braceStart >= 0 && braceEnd > braceStart) json = json.substring(braceStart, braceEnd + 1)

        return try {
            val obj = JSONObject(json)
            obj.optJSONArray(key)?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()
        } catch (e: Exception) {
            // 正則 fallback
            val regex = Regex("\"$key\"\\s*:\\s*\\[([^\\]]*)\\]")
            val m = regex.find(json) ?: return emptyList()
            Regex("\"([^\"]+)\"").findAll(m.groupValues[1]).map { it.groupValues[1] }.toList()
        }
    }

    // ════════════════════════════════════════
    // JSON 解析
    // ════════════════════════════════════════

    private fun parseAIResponse(raw: String): HotSectorResult {
        var json = raw.trim()

        // 嘗試提取 JSON（處理 Markdown 代碼塊包裹的情況）
        val jsonBlockRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)```")
        val match = jsonBlockRegex.find(json)
        if (match != null) {
            json = match.groupValues[1].trim()
        }

        // 嘗試匹配最外層的 {...}
        val braceStart = json.indexOf('{')
        val braceEnd = json.lastIndexOf('}')
        if (braceStart >= 0 && braceEnd > braceStart) {
            json = json.substring(braceStart, braceEnd + 1)
        }

        try {
            val obj = JSONObject(json)
            return HotSectorResult(
                annualSectors = obj.optJSONArray("annual_sectors")?.toStringList() ?: emptyList(),
                monthlySectors = obj.optJSONArray("monthly_sectors")?.toStringList() ?: emptyList(),
                weeklySectors = obj.optJSONArray("weekly_sectors")?.toStringList() ?: emptyList(),
                yesterdaySectors = obj.optJSONArray("yesterday_sectors")?.toStringList() ?: emptyList()
            )
        } catch (e: Exception) {
            Log.w(TAG, "JSON 解析失敗: ${e.message}，原始: ${json.take(200)}")
            // 備用：嘗試正則提取
            return regexFallbackParse(json)
        }
    }

    private fun regexFallbackParse(text: String): HotSectorResult {
        fun extractSectors(key: String): List<String> {
            val regex = Regex("\"$key\"\\s*:\\s*\\[([^\\]]*)\\]")
            val m = regex.find(text) ?: return emptyList()
            val content = m.groupValues[1]
            return Regex("\"([^\"]+)\"").findAll(content).map { it.groupValues[1] }.toList()
        }
        return HotSectorResult(
            annualSectors = extractSectors("annual_sectors"),
            monthlySectors = extractSectors("monthly_sectors"),
            weeklySectors = extractSectors("weekly_sectors"),
            yesterdaySectors = extractSectors("yesterday_sectors")
        )
    }

    private fun JSONArray.toStringList(): List<String> {
        return (0 until length()).map { getString(it) }
    }

    // ════════════════════════════════════════
    // 緩存
    // ════════════════════════════════════════

    private fun loadCache(context: Context): HotSectorResult? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_CACHED_SECTORS, null) ?: return null
        val lastFetchTime = prefs.getLong(KEY_LAST_FETCH_TIME, 0L)

        if (System.currentTimeMillis() - lastFetchTime > CACHE_DURATION_MS) {
            return null
        }

        return try {
            val obj = JSONObject(json)
            HotSectorResult(
                annualSectors = obj.optJSONArray("annual_sectors")?.toStringList() ?: emptyList(),
                monthlySectors = obj.optJSONArray("monthly_sectors")?.toStringList() ?: emptyList(),
                weeklySectors = obj.optJSONArray("weekly_sectors")?.toStringList() ?: emptyList(),
                yesterdaySectors = obj.optJSONArray("yesterday_sectors")?.toStringList() ?: emptyList(),
                fetchTime = lastFetchTime
            )
        } catch (e: Exception) {
            Log.w(TAG, "緩存解析失敗: ${e.message}")
            null
        }
    }

    private fun saveCache(context: Context, result: HotSectorResult) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = JSONObject().apply {
            put("annual_sectors", JSONArray(result.annualSectors))
            put("monthly_sectors", JSONArray(result.monthlySectors))
            put("weekly_sectors", JSONArray(result.weeklySectors))
            put("yesterday_sectors", JSONArray(result.yesterdaySectors))
        }.toString()
        prefs.edit().apply {
            putString(KEY_CACHED_SECTORS, json)
            putLong(KEY_LAST_FETCH_TIME, System.currentTimeMillis())
            apply()
        }
        Log.i(TAG, "💾 熱門板塊快取已保存: ${result.allSectors.size} 個")
    }

    // ════════════════════════════════════════
    // 備用列表（AI 查詢失敗時使用）
    // ════════════════════════════════════════

    /** 備用列表（AI 查詢失敗時使用），對外公開供 fallback 使用 */
    fun getDefaultHotSectors(): HotSectorResult {
        return HotSectorResult(
            annualSectors = listOf(
                "人工智能", "半导体", "光通信", "新能源", "低空经济",
                "机器人", "算力", "智能汽车", "量子科技", "生物医药"
            ),
            monthlySectors = listOf(
                "CPO光模块", "算力租赁", "液冷", "铜箔", "PCB",
                "存储芯片", "固态电池", "无人机", "氢能源", "数据要素"
            ),
            weeklySectors = listOf(
                "光刻胶", "先进封装", "HBM", "高速连接器", "碳化硅",
                "钠离子电池", "钙钛矿", "卫星互联网", "脑机接口", "合成生物"
            ),
            yesterdaySectors = listOf(
                "半导体设备", "AI服务器", "光模块", "稀土永磁", "工业母机",
                "特高压", "充电桩", "光伏玻璃", "风电设备", "储能"
            )
        )
    }
}