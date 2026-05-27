package com.chin.stockanalysis.conversation

import android.content.Context
import android.os.Environment
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.ui.Message
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 对话历史持久化仓库
 *
 * 封装 Room DB 读写 + 外部路径迁移导入逻辑。
 * 当新版本安装时，自动检查手机特定路径下是否存在旧的对话数据并导入。
 */
class ConversationRepository(context: Context) {

    companion object {
        private const val TAG = "ConvRepository"

        /** 外部存储中用于存放历史对话的目录名（可在升级时从旧版本迁移） */
        private const val LEGACY_DIR_NAME = "StockAnalysis/conversations"

        /** 旧版本可能在多个路径存放数据，按优先级检查 */
        private val LEGACY_SEARCH_PATHS = listOf(
            Environment.getExternalStorageDirectory().absolutePath,
            "/storage/emulated/0/Android/data/com.chin.stockanalysis/files",
            "/storage/emulated/0/Documents",
        )

        // ═══════════════════════════════════════════════════════
        // 序列化工具
        // ═══════════════════════════════════════════════════════

        fun serializeMessages(messages: List<Message>): String {
            val arr = JSONArray()
            for (msg in messages) {
                if (msg.isStreaming || msg.isError) continue
                val obj = JSONObject().apply {
                    put("id", msg.id)
                    put("content", msg.content)
                    put("isUser", msg.isUser)
                    put("timestamp", msg.timestamp)
                }
                arr.put(obj)
            }
            return arr.toString()
        }

        fun deserializeMessages(json: String): List<Message> {
            if (json.isBlank() || json == "[]") return emptyList()
            return try {
                val arr = JSONArray(json)
                val result = mutableListOf<Message>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    result.add(
                        Message(
                            id = obj.optString("id", System.currentTimeMillis().toString() + i),
                            content = obj.optString("content", ""),
                            isUser = obj.optBoolean("isUser", true),
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                        )
                    )
                }
                result
            } catch (e: Exception) {
                Log.e(TAG, "反序列化消息失败: ${e.message}")
                emptyList()
            }
        }

        fun generateTitleAndSubtitle(messages: List<Message>): Pair<String, String> {
            val firstUser = messages.firstOrNull { it.isUser }
            val lastBot = messages.lastOrNull { !it.isUser }
            val title = firstUser?.content?.let {
                if (it.length > 18) it.take(18) + "…" else it
            } ?: "新对话"
            val subtitle = lastBot?.content?.let {
                if (it.length > 30) it.take(30) + "…" else it
            } ?: "暂无回复"
            return Pair(title, subtitle)
        }
    }

    private val db = StockDatabase.getInstance(context)
    private val dao = db.conversationDao()

    /** 获取所有对话（Flow，实时观察） */
    fun getAllConvsFlow(): Flow<List<ConversationEntity>> = dao.getAllConvsDesc()

    /** 一次性获取所有对话 */
    suspend fun getAllConvs(): List<ConversationEntity> = dao.getAllConvsDescOnce()

    /** 按 ID 获取单个对话 */
    suspend fun getById(id: String): ConversationEntity? = dao.getById(id)

    /** 保存对话（插入或更新） */
    suspend fun saveConversation(conv: ConversationEntity) {
        dao.insertOrUpdate(conv)
    }

    /** 删除对话 */
    suspend fun deleteConversation(id: String) {
        dao.deleteById(id)
    }

    /** 清空所有对话 */
    suspend fun deleteAll() {
        dao.deleteAll()
    }

    // ═══════════════════════════════════════════════════════
    // 外部路径迁移（需求3：安装新版本时迁移旧数据）
    // ═══════════════════════════════════════════════════════

    suspend fun migrateLegacyConversations(): Int {
        var importedCount = 0
        for (basePath in LEGACY_SEARCH_PATHS) {
            val dir = File(basePath, LEGACY_DIR_NAME)
            if (!dir.exists() || !dir.isDirectory) {
                Log.d(TAG, "路径不存在，跳过: ${dir.absolutePath}")
                continue
            }
            val jsonFiles = dir.listFiles { file -> file.isFile && file.extension.equals("json", ignoreCase = true) }
            if (jsonFiles.isNullOrEmpty()) {
                Log.d(TAG, "路径下无 JSON 文件: ${dir.absolutePath}")
                continue
            }
            for (file in jsonFiles) {
                try {
                    val content = file.readText()
                    val json = JSONObject(content)
                    val convId = json.optString("id", file.nameWithoutExtension)
                    if (dao.getById(convId) != null) {
                        Log.d(TAG, "会话 $convId 已存在，跳过导入")
                        continue
                    }
                    val title = json.optString("title", "历史对话")
                    val subtitle = json.optString("subtitle", "")
                    val timestamp = json.optLong("timestamp", file.lastModified())
                    val messagesJson = json.optJSONArray("messages")?.toString() ?: "[]"

                    val entity = ConversationEntity(
                        id = convId,
                        title = title,
                        subtitle = subtitle,
                        timestamp = timestamp,
                        messagesJson = messagesJson
                    )
                    dao.insertOrUpdate(entity)
                    importedCount++
                    Log.d(TAG, "✅ 导入旧对话: $title (来自 ${file.absolutePath})")
                } catch (e: Exception) {
                    Log.e(TAG, "导入文件失败 ${file.name}: ${e.message}")
                }
            }
        }
        if (importedCount > 0) {
            Log.d(TAG, "🎉 共导入 $importedCount 条旧对话")
        } else {
            Log.d(TAG, "未发现需要导入的旧对话数据")
        }
        return importedCount
    }

    suspend fun exportAllToExternal(): Int {
        val dir = File(Environment.getExternalStorageDirectory(), LEGACY_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        val allConvs = dao.getAllConvsDescOnce()
        var count = 0
        for (conv in allConvs) {
            try {
                val json = JSONObject().apply {
                    put("id", conv.id)
                    put("title", conv.title)
                    put("subtitle", conv.subtitle)
                    put("timestamp", conv.timestamp)
                    put("messages", JSONArray(conv.messagesJson))
                }
                val file = File(dir, "${conv.id}.json")
                file.writeText(json.toString(2))
                count++
            } catch (e: Exception) {
                Log.e(TAG, "导出失败 ${conv.id}: ${e.message}")
            }
        }
        return count
    }
}