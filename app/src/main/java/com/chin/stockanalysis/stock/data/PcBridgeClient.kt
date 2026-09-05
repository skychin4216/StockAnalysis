package com.chin.stockanalysis.stock.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * PC 直连客户端 —— APK 作为客户端, 连接 AutoQuant-GUI.exe(服务器) 的局域网 HTTP 服务。
 *
 * exe 端由 `autoquant/data_service.py` 提供(默认 0.0.0.0:8888):
 *   GET /candidates  最新 PC 候选清单(candidates_quant.json, 含 groups/rotation/hot_sectors)
 *   GET /rotation    板块轮动清单
 *   GET /push?since= 长轮询, 候选文件更新时立即返回(≥2s 轮询间隔)
 *
 * 用法: 在 PC 候选 Dialog 输入 exe 所在机器的局域网 IP, 即可实时获取+自动刷新。
 */
object PcBridgeClient {

    private const val TAG = "PcBridgeClient"
    private const val PREFS = "pc_bridge"
    private const val KEY_HOST = "exe_host"
    private const val DEFAULT_PORT = 8888

    /** 保存 PC 服务器地址(形如 192.168.1.5 或 192.168.1.5:8888) */
    fun saveHost(context: Context, hostPort: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_HOST, hostPort.trim()).apply()
    }

    fun loadHost(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HOST, "") ?: ""

    fun normalize(hostPort: String): Pair<String, Int> {
        val t = hostPort.trim().removePrefix("http://").removePrefix("https://")
        val idx = t.lastIndexOf(':')
        return if (idx > 0 && t.substring(idx + 1).toIntOrNull() != null) {
            t.substring(0, idx) to t.substring(idx + 1).toInt()
        } else {
            t to DEFAULT_PORT
        }
    }

    private fun baseUrl(hostPort: String): String {
        val raw = hostPort.trim()
        // 保留用户指定的协议（https 公网场景）；默认 http
        val scheme = when {
            raw.startsWith("https://") -> "https"
            raw.startsWith("http://") -> "http"
            else -> "http"
        }
        val (h, p) = normalize(raw)
        return "$scheme://$h:$p"
    }

    // ───────────────────────── 远程控制（C/S）API ─────────────────────────
    // exe 端: autoquant/data_service.py + remote_control.py
    //   POST /task/submit  GET /task/get|list|logs|types  POST /task/cancel
    // 鉴权: 请求头 X-Token（PC 端 data/remote_token.txt）

    private fun authedPost(hostPort: String, token: String, path: String, body: JSONObject): String {
        val client = HttpClientProvider.healthCheckClient
        val req = Request.Builder()
            .url("${baseUrl(hostPort)}/$path")
            .addHeader("X-Token", token)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: $text")
            return text
        }
    }

    private fun authedGet(hostPort: String, token: String, path: String): String {
        val client = HttpClientProvider.healthCheckClient
        val req = Request.Builder()
            .url("${baseUrl(hostPort)}/$path")
            .addHeader("X-Token", token)
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: $text")
            return text
        }
    }

    /** 获取远程控制 token（PC 端 data/remote_token.txt 内容，用户手动填写） */
    fun saveToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("exe_token", token.trim()).apply()
    }

    fun loadToken(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("exe_token", "") ?: ""

    /** 查询远程控制状态（验证 host/token 是否可用） */
    suspend fun remoteStatus(hostPort: String, token: String): String =
        authedGet(hostPort, token, "remote/status")

    /** 获取可用任务类型列表 */
    suspend fun taskTypes(hostPort: String, token: String): String =
        authedGet(hostPort, token, "task/types")

    /** 提交远程任务, 返回 JSON({task_id, task}) */
    suspend fun submitTask(hostPort: String, token: String, taskType: String, params: JSONObject = JSONObject()): String {
        val body = JSONObject()
            .put("task_type", taskType)
            .put("params", params)
            .put("requester", "apk")
        return authedPost(hostPort, token, "task/submit", body)
    }

    /** 查询任务状态, 返回 JSON({id,state,progress,...}) */
    suspend fun getTask(hostPort: String, token: String, taskId: String): String {
        val body = JSONObject().put("id", taskId)
        return authedPost(hostPort, token, "task/get", body)
    }

    /** 任务列表, 返回 JSON({tasks:[...]}) */
    suspend fun listTasks(hostPort: String, token: String, state: String? = null): String {
        val body = JSONObject()
        if (state != null) body.put("state", state)
        return authedPost(hostPort, token, "task/list", body)
    }

    /** 取消任务 */
    suspend fun cancelTask(hostPort: String, token: String, taskId: String): String {
        val body = JSONObject().put("id", taskId)
        return authedPost(hostPort, token, "task/cancel", body)
    }

    /** 增量获取任务日志, 返回 JSON({logs:[{ts,line}], cursor, state}) */
    suspend fun taskLogs(hostPort: String, token: String, taskId: String, cursor: Int): String {
        val body = JSONObject().put("id", taskId).put("cursor", cursor)
        return authedPost(hostPort, token, "task/logs", body)
    }

    // ───────────────────────── APK ↔ CodeBuddy 消息桥 ─────────────────────────
    // exe 端: autoquant/data_service.py
    //   POST /remote/msg           APK 发消息给 CodeBuddy → cb_inbox.json
    //   GET  /remote/msg?after=N   APK 拉取 CodeBuddy 回复 → cb_outbox.json
    //   GET  /remote/msg/inbox     CodeBuddy 拉取待处理消息（PC/自动化侧使用）

    /** 发送消息给 CodeBuddy, 返回 JSON({ok, msg_id}) */
    suspend fun sendMsg(hostPort: String, token: String, content: String): String {
        val body = JSONObject().put("content", content).put("sender", "apk")
        return authedPost(hostPort, token, "remote/msg", body)
    }

    /** 拉取 CodeBuddy 回复, 返回 JSON({ok, after, total, messages:[{content,ts,seq}]}) */
    suspend fun fetchMsgReplies(hostPort: String, token: String, after: Int): String =
        authedGet(hostPort, token, "remote/msg?after=$after")

    /** 拉取候选清单, 返回 JSON 文本; 失败抛异常。 */
    suspend fun fetchCandidates(hostPort: String): String {
        val client = HttpClientProvider.healthCheckClient
        val req = Request.Builder().url("${baseUrl(hostPort)}/candidates").build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: $body")
            return body
        }
    }

    /** 拉取板块轮动清单, 返回 JSON 文本; 失败抛异常。 */
    suspend fun fetchRotation(hostPort: String): String {
        val client = HttpClientProvider.healthCheckClient
        val req = Request.Builder().url("${baseUrl(hostPort)}/rotation").build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: $body")
            return body
        }
    }

    /** 拉取 ETF 低位选股名单(smalltools _etf_buy.py --live), 返回 JSON 文本; 失败抛异常。 */
    suspend fun fetchEtfLive(hostPort: String): String {
        val client = HttpClientProvider.healthCheckClient
        val req = Request.Builder().url("${baseUrl(hostPort)}/etf_live").build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: $body")
            return body
        }
    }

    /** 长轮询: 候选文件更新时回调 onNew(json); 返回可取消的 Job。 */
    fun watch(hostPort: String, scope: CoroutineScope, onNew: (String) -> Unit): Job {
        val client = HttpClientProvider.healthCheckClient
        var since = 0L
        val job = SupervisorJob()
        return scope.launch(job) {
            while (isActive) {
                try {
                    val url = "${baseUrl(hostPort)}/push?since=$since&timeout=30"
                    val req = Request.Builder().url(url).build()
                    client.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val body = resp.body?.string().orEmpty()
                            val j = JSONObject(body)
                            if (j.optBoolean("_pushed", false)) {
                                since = j.optLong("_file_mtime", 0L)
                                onNew(body)
                            } else {
                                since = j.optLong("_file_mtime", since)
                            }
                        }
                    }
                } catch (_: Exception) {
                    // 网络不可达时静默, 继续轮询
                }
                delay(3000)
            }
        }
    }
}
