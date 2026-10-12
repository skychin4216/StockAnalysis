package com.chin.stockanalysis.stock.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * ## APK ↔ PC 通道（联网中继版，2026-09-13 起不再使用局域网直连）
 *
 * 设计定稿：`docs/bridge-relay-design.md`（彻底取消局域网/IP 模式）。
 *
 * 变更要点：
 * - **不再需要填 IP / 端口 / token**：全部走 [CosRelayClient]（腾讯云 COS 中继），
 *   两端各自联网即可，不要求同一 WiFi、不需要端口映射。
 * - 本类**不再有任何「地址」概念**（旧 `loadHost/saveHost/loadToken/saveToken` 已删除）。
 * - PC 端 HTTP 已降为仅监听 `127.0.0.1`，由 `relay_worker.py` 在中继与本机 API 间搭桥。
 *
 * 使用前需调用一次 [init]（面板/对话框入口处调用即可，幂等）。
 */
object PcBridgeClient {

    /** 候选清单轮询间隔（中继无长轮询，改为定期间隔拉取）。 */
    private const val WATCH_INTERVAL_MS = 30_000L

    /** 主动推送轮询间隔（P3）。60s 足够"选股完成自动到达"，又不费 COS 请求数。 */
    private const val PUSH_INTERVAL_MS = 60_000L

    @Volatile
    private var appCtx: Context? = null

    /** 绑定 Application Context（幂等；任意入口调用一次即可）。 */
    fun init(context: Context) {
        if (appCtx == null) appCtx = context.applicationContext
    }

    private fun ctx(): Context = appCtx ?: throw IllegalStateException(
        "PcBridgeClient 未初始化：请先调用 PcBridgeClient.init(context)"
    )

    private suspend fun cmd(method: String, params: JSONObject = JSONObject()): JSONObject =
        CosRelayClient.command(ctx(), method, params)

    // ───────────────────────── 远程控制（C/S）API ─────────────────────────
    // 对应 PC 端方法表 relay_worker.METHODS → AutoQuant/autoquant/data_service.py

    /**
     * 查询远程控制状态。
     * @return JSON 文本 `{ok, server, token, ...}`
     */
    suspend fun remoteStatus(): String = cmd("status.get").toString()

    /** 获取可用任务类型列表，返回 `{types:[...]}` */
    suspend fun taskTypes(): String = cmd("task.types").toString()

    /** 提交远程任务，返回 `{ok, task_id, task}` */
    suspend fun submitTask(taskType: String, params: JSONObject = JSONObject()): String {
        val body = JSONObject()
            .put("task_type", taskType)
            .put("params", params)
            .put("requester", "apk")
        return cmd("task.submit", body).toString()
    }

    /** 查询任务状态，返回 `{id, state, progress, ...}` */
    suspend fun getTask(taskId: String): String =
        cmd("task.get", JSONObject().put("id", taskId)).toString()

    /** 任务列表，返回 `{tasks:[...]}` */
    suspend fun listTasks(state: String? = null): String {
        val body = JSONObject()
        if (state != null) body.put("state", state)
        return cmd("task.list", body).toString()
    }

    /** 取消任务 */
    suspend fun cancelTask(taskId: String): String =
        cmd("task.cancel", JSONObject().put("id", taskId)).toString()

    /** 增量获取任务日志，返回 `{logs:[{ts,line}], cursor, state}` */
    suspend fun taskLogs(taskId: String, cursor: Int): String =
        cmd("task.logs", JSONObject().put("id", taskId).put("cursor", cursor)).toString()

    // ───────────────────────── APK ↔ CodeBuddy 消息桥 ─────────────────────────

    /**
     * 发送消息给 CodeBuddy，返回 `{ok, msg_id}`。
     *
     * ★ 2026-09-21：改用 `deliver()`（**只投递、不等应答**）。
     * 原实现走 `command()` 会最长等 90 秒同步应答，等不到就抛「中继超时」→ UI 报
     * 「发送失败」，但消息其实**已经落到 PC 的 cb_inbox.json**（用户实测踩到）。
     * 消息回复本来就是异步的（由 `fetchMsgReplies` 轮询），不需要同步应答。
     */
    suspend fun sendMsg(content: String): String {
        val msgId = CosRelayClient.deliver(
            ctx(), "msg.send",
            JSONObject().put("content", content).put("sender", "apk"))
        return JSONObject().put("ok", true).put("msg_id", msgId).toString()
    }

    /** 拉取 CodeBuddy 回复，返回 `{ok, after, total, messages:[{content,ts,seq}]}` */
    suspend fun fetchMsgReplies(after: Int): String =
        cmd("msg.replies", JSONObject().put("after", after)).toString()

    // ───────────────────────── 数据拉取 ─────────────────────────

    /** 拉取候选清单，返回 JSON 文本；失败抛异常。 */
    suspend fun fetchCandidates(): String = cmd("candidates.fetch").toString()

    /** 拉取板块轮动清单，返回 JSON 文本；失败抛异常。 */
    suspend fun fetchRotation(): String = cmd("rotation.fetch").toString()

    /** 拉取 ETF 选股名单，返回 JSON 文本；失败抛异常。 */
    suspend fun fetchEtfLive(): String = cmd("etf.live").toString()

    /**
     * 候选清单变更监听：中继无长轮询，改为每 [WATCH_INTERVAL_MS] 拉取一次，
     * 内容变化时回调 `onNew(json)`；返回可取消的 Job。
     */
    fun watch(scope: CoroutineScope, onNew: (String) -> Unit): Job {
        val job = SupervisorJob()
        return scope.launch(job) {
            var last = ""
            while (isActive) {
                try {
                    val json = cmd("candidates.fetch").toString()
                    if (json != last) {
                        last = json
                        onNew(json)
                    }
                } catch (_: Exception) {
                    // 联网不可达时静默，等待下个周期
                }
                delay(WATCH_INTERVAL_MS)
            }
        }
    }

    // ───────────────────────── PC → APK 主动推送（P3） ─────────────────────────

    /**
     * 拉取 PC 主动推送（选股完成 / 盘前情报 / 任务结束）。
     *
     * 与 [watch] 的「APK 主动问、PC 才答」互补：这类消息是 PC 侧**主动**写进
     * `pc/push/{deviceId}/` 的，APK 打开就能看到，不必等下一次轮询窗口。
     * 每条含 `title` / `content` / `payload`，`_seq` 是本地注入的序号。
     */
    suspend fun fetchPushes(limit: Int = 30, onEach: ((JSONObject) -> Unit)? = null): List<JSONObject> =
        CosRelayClient.fetchPushes(ctx(), limit, onEach)

    /** 已消费到的最大推送 seq（-1 = 从未消费）。 */
    fun pushCursor(): Int = CosRelayClient.pushCursor(ctx())

    /** 上报设备心跳（best-effort），让 PC 知道本机可推送。 */
    suspend fun beat() = CosRelayClient.beat(ctx())

    /**
     * 主动推送监听：按 [PUSH_INTERVAL_MS] 轮询 `pc/push/`，有新消息就回调。
     *
     * 与 [watch] 的差别：本方法**只在新消息到达时**回调（游标驱动），
     * 不会因为内容不变而重复触发，适合做"选股完成自动到达"的入口。
     *
     * @return 可取消的 Job
     */
    fun watchPushes(scope: CoroutineScope, onPush: (List<JSONObject>) -> Unit): Job {
        val job = SupervisorJob()
        return scope.launch(job) {
            // 启动即报一次心跳，PC 侧才知道本机可推送
            runCatching { beat() }
            while (isActive) {
                try {
                    val list = fetchPushes()
                    if (list.isNotEmpty()) onPush(list)
                } catch (_: Exception) {
                    // 联网不可达时静默，等下个周期
                }
                delay(PUSH_INTERVAL_MS)
            }
        }
    }

    /** 连通性自检：返回 PC 服务状态（不填 IP 即可验证）。 */
    suspend fun ping(): JSONObject = CosRelayClient.ping(ctx())
}
