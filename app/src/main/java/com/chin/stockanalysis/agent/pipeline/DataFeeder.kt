package com.chin.stockanalysis.agent.pipeline

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.ai.AiProviderPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Agent F — 科技賽道投研情報採集智能體（數據底座）
 *
 * 職責：
 * - 實時聯網採集最新產業原始資訊
 * - 細分賽道催化事件彙總
 * - 企業訂單/產品認證最新進展
 * - 外資機構完整評級內容
 * - 產業供需格局數據整理
 *
 * 動態調整：
 * - 默認採集 8 大科技賽道
 * - 用戶輸入其他板塊時，動態切換採集範圍
 * - 同一標的 5 分鐘內不重複採集（快取）
 */
class DataFeeder(private val context: Context) {

    companion object {
        private const val TAG = "DataFeeder"

        /** 板塊關鍵詞 → 賽道映射（保留用於 inferSector 推斷） */
        private val SECTOR_KEYWORD_MAP = mapOf(
            "半導體" to listOf("半導體", "芯片", "晶圓", "封裝", "IC", "GPU", "CPU"),
            "電網設備" to listOf("電網", "配電", "變壓器", "電力設備", "特高壓"),
            "PCB" to listOf("PCB", "印制電路板", "覆銅板"),
            "MLCC" to listOf("MLCC", "片式電容", "被動元件"),
            "光通信" to listOf("光通信", "光模組", "光纖", "光器件", "MPO", "連接器"),
            "存儲" to listOf("存儲", "閃存", "NAND", "DRAM", "SSD", "HBM"),
            "AI 硬體" to listOf("AI", "人工智能", "算力", "伺服器"),
            "新能源" to listOf("新能源", "鋰電", "光伏", "風電", "儲能"),
            "醫藥" to listOf("醫藥", "創新藥", "CXO", "醫療器械"),
            "消費" to listOf("消費", "白酒", "食品飲料", "家電"),
            "金融" to listOf("金融", "銀行", "券商", "保險"),
            "地產" to listOf("地產", "物業"),
            "汽車" to listOf("汽車", "新能源車", "智能駕駛", "電動車")
        )

        /** 快取有效期：5 分鐘 */
        private const val CACHE_DURATION_MS = 5 * 60 * 1000L
    }

    /** 快取：target + sector → result + timestamp */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<DataFeederResult, Long>>()

    /**
     * 根據用戶輸入推斷板塊/賽道
     */
    fun inferSector(userInput: String): String {
        val input = userInput.lowercase()
        for ((sector, keywords) in SECTOR_KEYWORD_MAP) {
            for (kw in keywords) {
                if (input.contains(kw.lowercase())) return sector
            }
        }
        return "科技" // 默認
    }

    /**
     * 獲取採集範圍的賽道列表（動態獲取今日熱門賽道，不硬編碼）
     */
    suspend fun getSectorsForInput(userInput: String): List<String> {
        val inferred = inferSector(userInput)
        return if (inferred == "科技") {
            // 動態獲取今日熱門賽道
            getDynamicHotSectors()
        } else {
            listOf(inferred)
        }
    }

    /**
     * 動態獲取今日熱門賽道列表
     * 數據來源：StrategyMarketContext 實時板塊漲幅 Top + 週/月熱門板塊
     */
    private suspend fun getDynamicHotSectors(): List<String> {
        return try {
            val today = java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            val mktCtx = com.chin.stockanalysis.strategy.sector.StrategyMarketContext
                .build(context, today)
            val sectors = mutableSetOf<String>()
            sectors.addAll(mktCtx.todayHotSectors)
            sectors.addAll(mktCtx.userFocusSectors)

            // 補充週/月熱門板塊
            try {
                val hotSectors = com.chin.stockanalysis.strategy.data.AIHotSectorProvider
                    .getHotSectors(context)
                sectors.addAll(hotSectors.weeklySectors.take(3))
                sectors.addAll(hotSectors.monthlySectors.take(3))
            } catch (_: Exception) { }

            if (sectors.isEmpty()) {
                // 最終 fallback：從 SECTOR_KEYWORD_MAP 取前 5 個賽道
                SECTOR_KEYWORD_MAP.keys.take(5).toList()
            } else {
                sectors.take(8).toList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "動態獲取熱門賽道失敗，使用關鍵詞映射 fallback: ${e.message}")
            SECTOR_KEYWORD_MAP.keys.take(5).toList()
        }
    }

    /**
     * 採集情報（帶快取）
     *
     * @param target 用戶輸入的標的
     * @param sector 動態板塊（可由 inferSector 推斷或用戶指定）
     */
    suspend fun fetch(target: String, sector: String? = null): DataFeederResult {
        val effectiveSector = sector ?: inferSector(target)
        val cacheKey = "$target|$effectiveSector"

        // 檢查快取
        cache[cacheKey]?.let { (result, ts) ->
            if (System.currentTimeMillis() - ts < CACHE_DURATION_MS) return result
        }

        // 實際採集（通過 LLM）
        val result = fetchFromLLM(target, effectiveSector)

        // 寫入快取
        cache[cacheKey] = result to System.currentTimeMillis()

        return result
    }

    /**
     * 通過 LLM 採集情報
     * 使用 AiProviderPool 獲取可用 Provider，構建採集 Prompt
     * SSE 串流獲取完整回覆後，用 StructuredOutputParser 解析 JSON
     */
    private suspend fun fetchFromLLM(target: String, sector: String): DataFeederResult {
        return withContext(Dispatchers.IO) {
            val sectors = if (sector == "科技") SECTOR_KEYWORD_MAP.keys.joinToString("、") else sector
            val prompt = buildFeederPrompt(target, sectors)

            val slot = AiProviderPool.acquire(context)
            if (slot == null) {
                Log.w(TAG, "無可用 AI Provider，返回空情報")
                return@withContext DataFeederResult()
            }

            try {
                val fullResponse = suspendCancellableCoroutine<String> { cont ->
                    slot.provider.sendMessageStream(
                        messages = emptyList(),
                        systemPrompt = prompt,
                        onSuccess = { /* 串流 chunk 不處理 */ },
                        onComplete = { full ->
                            val sanitized = full.replace(Regex(":\\s*null\\s*([,}\\]])"), ": \"\"$1")
                            cont.resume(sanitized)
                        },
                        onError = { err ->
                            Log.w(TAG, "Agent F 採集失敗: ${err.take(80)}")
                            cont.resume("") // 降級返回空結果
                        }
                    )
                }

                // 解析 LLM 回覆中的 JSON
                parseFeederResponse(fullResponse)
            } catch (e: Exception) {
                Log.w(TAG, "Agent F 採集異常: ${e.message?.take(60)}")
                DataFeederResult() // 降級返回空結果
            } finally {
                AiProviderPool.releaseNonBlocking(slot)
            }
        }
    }

    /**
     * 解析 LLM 回覆為 DataFeederResult
     * 嘗試從 ```json ... ``` 或 { ... } 中提取 JSON
     */
    private fun parseFeederResponse(text: String): DataFeederResult {
        if (text.isBlank()) return DataFeederResult()

        val json = extractJson(text) ?: return DataFeederResult()

        return try {
            val events = jsonToList(json, "events")
            val ratings = jsonToList(json, "ratings")
            val supplyChain = jsonToList(json, "supplyChain")

            DataFeederResult(
                events = events,
                ratings = ratings,
                supplyChain = supplyChain
            )
        } catch (e: Exception) {
            Log.w(TAG, "解析 Agent F 回覆失敗: ${e.message}")
            DataFeederResult()
        }
    }

    /**
     * 從文本中提取 JSON
     */
    private fun extractJson(text: String): JSONObject? {
        // ```json ... ```
        val fencedPattern = Regex("```json\\s*([\\s\\S]*?)```")
        fencedPattern.find(text)?.let { match ->
            return try { JSONObject(match.groupValues[1].trim()) } catch (_: Exception) { null }
        }
        // { ... }
        val bracePattern = Regex("\\{[\\s\\S]*\\}")
        bracePattern.find(text)?.let { match ->
            return try { JSONObject(match.value) } catch (_: Exception) { null }
        }
        return null
    }

    /**
     * 從 JSONObject 中提取 String List
     */
    private fun jsonToList(json: JSONObject, key: String): List<String> {
        val arr = json.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull {
            try { arr.getString(it) } catch (_: Exception) { null }
        }.filter { it.isNotBlank() }
    }

    /**
     * 構建 Agent F 的採集 Prompt
     */
    private fun buildFeederPrompt(target: String, sectors: String): String {
        return """
            |你是科技賽道投研情報採集智能體。你的任務是實時採集以下賽道的最新產業情報：
            |
            |採集範圍：$sectors
            |用戶關注標的：$target
            |時效約束：僅採集 2026 年 6 月及以後更新的資訊
            |
            |請按以下 JSON 格式輸出（必須嚴格遵循）：
            |```json
            |{
            |  "events": ["催化事件1", "催化事件2"],
            |  "ratings": ["機構名：股票名 評級，目標價溢價 X%"],
            |  "supplyChain": ["供需格局描述1", "供需格局描述2"]
            |}
            |```
            |
            |採集要點：
            |1. 細分賽道催化事件（訂單、認證、產能變化）
            |2. 外資機構（高盛/摩根大通/摩根士丹利/美銀美林）最新評級
            |3. 產業鏈供需格局數據
            |4. 企業訂單/產品認證最新進展
            |5. 國產替代進展、海外客戶導入情況
        """.trimMargin()
    }

    /** 清除快取 */
    fun clearCache() { cache.clear() }
}
