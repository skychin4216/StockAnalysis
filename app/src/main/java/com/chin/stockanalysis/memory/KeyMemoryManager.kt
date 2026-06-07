package com.chin.stockanalysis.memory

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.ApiConfigManager
import com.chin.stockanalysis.ApiProvider
import com.chin.stockanalysis.ui.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 关键信息记忆管理器（参考豆包思路）
 *
 * 核心功能：
 * 1. buildMemoryPrompt() — 从 DB 获取高权重记忆，拼接到 system prompt
 * 2. extractAndMergeMemories() — 分析对话提取关键信息，合并/更新记忆
 * 3. generateFollowUpSuggestions() — AI 生成上下文感知的智能追问
 * 4. boostMemoryWeight() — 用户确认追问后提升权重
 */
class KeyMemoryManager(private val context: Context) {

    companion object {
        private const val TAG = "KeyMemoryManager"
        private const val MIN_WEIGHT = 0.3f
        private const val INITIAL_WEIGHT = 0.3f
        private const val BOOST_DELTA = 0.15f
        private const val MAX_WEIGHT = 1.0f
        private const val DECAY_DAYS = 30L
    }

    private val db = com.chin.stockanalysis.stock.database.StockDatabase.getInstance(context)
    private val dao = db.keyMemoryDao()

    // ════════════════════════════════════════
    // 1. 构建记忆 Prompt
    // ════════════════════════════════════════

    suspend fun buildMemorySuffix(): String {
        val memories = withContext(Dispatchers.IO) { dao.getActiveMemories(MIN_WEIGHT) }
        if (memories.isEmpty()) return ""
        return buildString {
            appendLine()
            appendLine("【用户的长期偏好和关注点（请在下面对话中结合使用）】")
            for ((i, mem) in memories.withIndex()) {
                appendLine("${i + 1}. ${mem.value}")
            }
            appendLine()
        }
    }

    // ════════════════════════════════════════
    // 2. 提取和合并记忆
    // ════════════════════════════════════════

    suspend fun extractAndMergeMemories(messages: List<Message>, convId: String, provider: ApiProvider? = null): Int {
        if (messages.size < 2) return 0
        return try {
            val extracted = extractViaAI(messages, provider)
            if (extracted.isEmpty()) return 0
            var merged = 0
            val now = System.currentTimeMillis()
            for ((key, value, category) in extracted) {
                val existing = dao.getByKey(key)
                if (existing != null) {
                    val newWeight = minOf(existing.weight + 0.1f, MAX_WEIGHT)
                    val sourceIds = mergeSourceIds(existing.sourceConvIds, convId)
                    dao.upsert(existing.copy(weight = newWeight, sourceConvIds = sourceIds, updatedAt = now))
                } else {
                    dao.upsert(KeyMemoryEntity(
                        id = UUID.randomUUID().toString(), category = category,
                        key = key, value = value, weight = INITIAL_WEIGHT,
                        sourceConvIds = JSONArray().apply { put(convId) }.toString(),
                        createdAt = now, updatedAt = now
                    ))
                }
                merged++
            }
            Log.d(TAG, "🧠 从对话 $convId 提取了 $merged 条关键信息")
            merged
        } catch (e: Exception) {
            Log.e(TAG, "提取记忆失败: ${e.message}")
            0
        }
    }

    private suspend fun extractViaAI(messages: List<Message>, overrideProvider: ApiProvider? = null): List<Triple<String, String, String>> {
        val provider = overrideProvider ?: ApiConfigManager.getInstance(context).createCurrentProvider() ?: return emptyList()
        val conversationText = messages
            .filter { !it.isStreaming && !it.isError }
            .joinToString("\n") { "${if (it.isUser) "用户" else "AI"}: ${it.content}" }

        val analysisPrompt = """你是一个信息提取助手。从以下对话中提取用户的关键偏好。

仅输出 JSON 数组：
[{"key":"简短术语(3-8字)","value":"完整描述(15-30字)","category":"data_source/stock_focus/indicator/trading_rule/general"}]

类别：data_source(数据源)/stock_focus(股票关注)/indicator(技术指标)/trading_rule(交易规则)/general(其他)
只提取明确表达或多次强调的偏好；无偏好输出 []

对话：
$conversationText"""

        return try {
            val result = withContext(Dispatchers.IO) { sendSyncRequest(provider, analysisPrompt) }
            parseResultTriple(result)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun sendSyncRequest(provider: ApiProvider, prompt: String): String {
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            provider.sendMessageStream(
                messages = emptyList(), systemPrompt = prompt,
                onSuccess = {},
                onComplete = { full -> cont.resumeWith(Result.success(full)) },
                onError = { err -> cont.resumeWith(Result.failure(Exception(err))) }
            )
        }
    }

    private fun parseResultTriple(jsonStr: String): List<Triple<String, String, String>> {
        val result = mutableListOf<Triple<String, String, String>>()
        try {
            val start = jsonStr.indexOf('['); val end = jsonStr.lastIndexOf(']')
            if (start == -1 || end == -1) return result
            val arr = JSONArray(jsonStr.substring(start, end + 1))
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val key = obj.optString("key", "").trim()
                val value = obj.optString("value", "").trim()
                val category = obj.optString("category", "general").trim()
                if (key.isNotBlank() && value.isNotBlank() && key.length in 3..12)
                    result.add(Triple(key, value, category))
            }
        } catch (_: Exception) {}
        return result
    }

    private fun mergeSourceIds(existingJson: String, newConvId: String): String {
        return try {
            val arr = JSONArray(existingJson)
            var found = false
            for (i in 0 until arr.length()) { if (arr.getString(i) == newConvId) { found = true; break } }
            if (!found) arr.put(newConvId)
            arr.toString()
        } catch (_: Exception) { JSONArray().apply { put(newConvId) }.toString() }
    }

    // ════════════════════════════════════════
    // 3. AI 智能追问（参考豆包）
    // ════════════════════════════════════════

    /**
     * 优先用 AI 生成上下文感知的追问（类似豆包），失败时回退规则。
     * 返回 1-2 条追问。
     */
    suspend fun generateFollowUpSuggestions(
        messages: List<Message>,
        memories: List<KeyMemoryEntity>,
        provider: ApiProvider? = null
    ): List<FollowUpSuggestion> {
        // 优先 AI
        try {
            val ai = generateViaAI(messages, memories, provider)
            if (ai.isNotEmpty()) {
                Log.d(TAG, "🤖 AI 追问: ${ai.map { it.text.take(30) }}")
                return ai.take(2)
            }
        } catch (e: Exception) { Log.e(TAG, "AI 追问失败: ${e.message}") }

        // 回退规则
        return generateByRules(messages, memories).take(2)
    }

    private suspend fun generateViaAI(
        messages: List<Message>,
        memories: List<KeyMemoryEntity>,
        overrideProvider: ApiProvider? = null
    ): List<FollowUpSuggestion> {
        val provider = overrideProvider ?: ApiConfigManager.getInstance(context).createCurrentProvider() ?: return emptyList()
        val recent = messages.filter { !it.isStreaming && !it.isError && it.content.isNotBlank() }.takeLast(6)
        if (recent.size < 2) return emptyList()

        val historyText = recent.joinToString("\n") { "${if (it.isUser) "用户" else "AI"}: ${it.content}" }
        val memoryText = if (memories.isNotEmpty()) memories.joinToString("\n") { "- ${it.value}" } else "暂无"

        val prompt = """你是追问生成助手。根据对话内容生成 1-2 条用户可能想问的后续问题。

要求：
1. 问题必须与当前对话直接相关
2. 问题具体可执行（如"整理成尾盘低吸+止损止盈的交易清单"）
3. 融合用户长期偏好到追问中
4. 15-30 字自然中文
5. 如涉及股票/产业链，追问应包含具体操作建议方向

仅输出 JSON 数组：
[{"text":"追问文本","key":"记忆关键词(3-8字)","category":"indicator/stock_focus/trading_rule/data_source/general"}]

示例（MLCC 产业链场景）：
[{"text":"需要我把这条MLCC产业链整理成尾盘低吸+止损止盈的交易清单吗？","key":"尾盘低吸清单","category":"trading_rule"},{"text":"帮我把这些股票里尾盘买手大于卖手的挑出来","key":"尾盘买卖比过滤","category":"indicator"}]

对话：
$historyText

用户长期偏好：
$memoryText"""

        return try {
            val result = sendSyncRequest(provider, prompt)
            parseSuggestions(result)
        } catch (e: Exception) { emptyList() }
    }

    private fun parseSuggestions(jsonStr: String): List<FollowUpSuggestion> {
        val result = mutableListOf<FollowUpSuggestion>()
        try {
            val start = jsonStr.indexOf('['); val end = jsonStr.lastIndexOf(']')
            if (start == -1 || end == -1) return result
            val arr = JSONArray(jsonStr.substring(start, end + 1))
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val text = obj.optString("text", "").trim()
                if (text.isNotBlank() && text.length in 8..50) {
                    result.add(FollowUpSuggestion(
                        id = "ai_${System.currentTimeMillis()}_$i",
                        text = text,
                        memoryKey = obj.optString("key", "智能追问").trim(),
                        memoryValue = text,
                        memoryCategory = obj.optString("category", "general").trim()
                    ))
                }
            }
        } catch (_: Exception) {}
        return result
    }

    // ── 规则回退 ──

    private fun generateByRules(
        messages: List<Message>,
        memories: List<KeyMemoryEntity>
    ): List<FollowUpSuggestion> {
        val suggestions = mutableListOf<FollowUpSuggestion>()
        val allUser = messages.filter { it.isUser }.joinToString(" ") { it.content }
        val allBot = messages.filter { !it.isUser && !it.isStreaming && !it.isError }.joinToString(" ") { it.content }

        if (hasStockMention(allUser) || hasStockCode(allUser)) {
            suggestions.add(FollowUpSuggestion("r1", "需要我查看这些股票的最新尾盘买卖比，筛选买盘强势的吗？", "尾盘买卖比筛选", "筛选买手>卖手的标的", "indicator"))
            suggestions.add(FollowUpSuggestion("r2", "需要我结合5日线和10日线位置，给出追高/低吸的策略建议吗？", "均线策略建议", "结合均线给出追高/低吸建议", "indicator"))
        }
        if (hasSectorOrChain(allUser + allBot)) {
            suggestions.add(FollowUpSuggestion("r3", "需要我把这条产业链整理成尾盘低吸+止损止盈点位的精简交易清单吗？", "产业链低吸清单", "整理产业链股票成尾盘低吸+止损止盈清单", "trading_rule"))
        }
        if (hasSupplyChainRef(allUser)) {
            suggestions.add(FollowUpSuggestion("r4", "需要我过滤出与英伟达/特斯拉有直接供应关系的标的吗？", "美股供应链过滤", "聚焦英伟达/特斯拉供应链A股标的", "stock_focus"))
        }
        for (mem in memories.take(1)) {
            suggestions.add(FollowUpSuggestion("r5_${mem.id}", "继续按照「${mem.key}」的方向，深挖更多机会？", mem.key, mem.value, mem.category))
        }
        val seen = mutableSetOf<String>()
        return suggestions.filter { s -> seen.add(s.text.take(15)) != null }
    }

    private fun hasStockMention(text: String): Boolean =
        Regex("\\d{6}|股价|行情|涨跌|低吸|追高|买入|卖出").containsMatchIn(text)
    private fun hasStockCode(text: String): Boolean = Regex("\\d{6}").containsMatchIn(text)
    private fun hasSupplyChainRef(text: String): Boolean =
        Regex("产业链|供应链|上游|下游|供应商|英伟达|特斯拉|马斯克").containsMatchIn(text)
    private fun hasSectorOrChain(text: String): Boolean =
        Regex("板块|行业|产业链|龙头|卡脖子|主板|个股|标的|组合|闭环|MLCC|粉体|瓷片").containsMatchIn(text)

    // ════════════════════════════════════════
    // 4. 权重管理
    // ════════════════════════════════════════

    suspend fun boostMemoryWeight(key: String, value: String, category: String, convId: String) {
        val existing = withContext(Dispatchers.IO) { dao.getByKey(key) }
        val now = System.currentTimeMillis()
        if (existing != null) {
            val newWeight = minOf(existing.weight + BOOST_DELTA, MAX_WEIGHT)
            val sourceIds = mergeSourceIds(existing.sourceConvIds, convId)
            withContext(Dispatchers.IO) { dao.upsert(existing.copy(weight = newWeight, sourceConvIds = sourceIds, updatedAt = now)) }
        } else {
            withContext(Dispatchers.IO) {
                dao.upsert(KeyMemoryEntity(
                    id = UUID.randomUUID().toString(), category = category,
                    key = key, value = value, weight = INITIAL_WEIGHT + BOOST_DELTA,
                    sourceConvIds = JSONArray().apply { put(convId) }.toString(),
                    createdAt = now, updatedAt = now
                ))
            }
        }
    }

    suspend fun decayMemories() {
        val threshold = System.currentTimeMillis() - DECAY_DAYS * 24 * 60 * 60 * 1000L
        withContext(Dispatchers.IO) { dao.decayOldMemories(threshold) }
    }

    suspend fun getAllMemories(): List<KeyMemoryEntity> = withContext(Dispatchers.IO) { dao.getAllMemories() }
    suspend fun deleteMemory(id: String) { withContext(Dispatchers.IO) { dao.deleteById(id) } }
    suspend fun deleteAll() { withContext(Dispatchers.IO) { dao.deleteAll() } }

    data class FollowUpSuggestion(
        val id: String,
        val text: String,
        val memoryKey: String,
        val memoryValue: String,
        val memoryCategory: String
    )
}