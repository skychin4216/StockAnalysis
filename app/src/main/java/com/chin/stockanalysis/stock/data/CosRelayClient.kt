package com.chin.stockanalysis.stock.data

import android.content.Context
import android.os.Build
import com.chin.stockanalysis.cloud.CloudSyncManager
import com.chin.stockanalysis.cloud.CosXmlClient   // 2026-09-20：官方 COS SDK 封装
import com.chin.stockanalysis.cloud.CosSigner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * ## APK ↔ PC 联网中继客户端（纯 COS，无局域网、不填 IP、不填 token）
 *
 * 设计定稿见 `docs/bridge-relay-design.md`（2026-09-13 修订：彻底取消局域网/IP 模式）。
 * PC 侧对应实现：`AutoQuant/autoquant/cos_relay.py`（运输层）+ `relay_worker.py`（执行体）。
 *
 * ### 为什么什么都不用填
 * - **不用填 IP**：两端各自连腾讯云 COS，通过桶里的对象箱体中转，不要求同一 WiFi。
 * - **不用填 token**：deviceToken 由两端共有的 COS `secret_key` 派生
 *   `HMAC-SHA256(secret_key, "sa-bridge-v1")`，能读写该桶的人本就能算出它，
 *   另设一个要手工填的 token 只是多一个会失效的字段。
 * - **只需 deviceId**：本机自动生成并持久化（`SharedPreferences: cos_relay`）。
 *
 * ### 键空间（prefix 默认 `stockanalysis/bridge`）
 * ```
 * pc/inbox/{deviceId}/{seq:08d}-{msgId}.json    APK → PC：命令
 * pc/outbox/{deviceId}/{seq:08d}-{msgId}.json   PC → APK：应答
 * pc/push/{deviceId}/{seq:08d}-{msgId}.json     PC → APK：主动推送（无需 APK 先发命令）
 * devices/{deviceId}.json                       设备心跳（每轮通信顺带上报，供 PC 广播）
 * ```
 *
 * ### 签名
 * `sig = HMAC-SHA256(deviceToken, "v|msgId|deviceId|seq|ts|kind|method|replyTo")[:32]`
 * **刻意只签元数据、不签 params**：两端对 JSON 的浮点/转义/嵌套序列化存在细微差异，
 * 纳入签名会跨端对不上且极难排查；通道完整性由 COS 桶写权限保证。
 * ★ `signBody()` 必须与 Python 端 `cos_relay.SIGN_FIELDS` **逐字一致**（改一处必须同步另一处）。
 */
object CosRelayClient {

    private const val TAG = "CosRelayClient"
    private const val PREF = "cos_relay"
    private const val KEY_DEVICE = "device_id"
    private const val KEY_SEQ = "seq"
    private const val KEY_LAST_OK = "last_ok_at"
    private const val KEY_PUSH_SEQ = "push_seq"
    private const val TOKEN_SALT = "sa-bridge-v1"

    /** 签名覆盖字段及顺序 —— 与 Python `cos_relay.SIGN_FIELDS` 严格一致。 */
    private val SIGN_FIELDS = arrayOf(
        "v", "msgId", "deviceId", "seq", "ts", "kind", "method", "replyTo"
    )

    /**
     * 专用 OkHttpClient。
     *
     * ★ 不能复用 `HttpClientProvider.healthCheckClient` —— 它的超时只有 3 秒，
     * 而中继单次 `command()` 是「PUT 命令 + 轮询等应答」，累计 20~90 秒，
     * 用 3 秒超时会在第一个 GET 上直接抛超时。
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    // ───────────────────────── 设备身份 ─────────────────────────

    /** 本机 deviceId（自动生成并持久化，用户无需配置）。 */
    fun deviceId(context: Context): String {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        sp.getString(KEY_DEVICE, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val model = (Build.MODEL ?: "device")
            .replace(Regex("[^A-Za-z0-9]"), "").lowercase().take(10).ifBlank { "device" }
        val id = "$model-${UUID.randomUUID().toString().replace("-", "").take(6)}"
        sp.edit().putString(KEY_DEVICE, id).apply()
        return id
    }

    /** 上次成功通信时间（毫秒），无记录返回 0。 */
    fun lastOkAt(context: Context): Long =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong(KEY_LAST_OK, 0L)

    /** 供 UI 展示的中继状态摘要（不含任何地址）。 */
    fun statusText(context: Context): String {
        val last = lastOkAt(context)
        val when_ = if (last <= 0) "尚未通信" else {
            val s = (System.currentTimeMillis() - last) / 1000
            if (s < 60) "${s}秒前" else if (s < 3600) "${s / 60}分钟前" else "${s / 3600}小时前"
        }
        return "中继设备 ${deviceId(context)} · 上次通信 $when_"
    }

    private fun nextSeq(context: Context): Int {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val n = sp.getInt(KEY_SEQ, 0) + 1
        sp.edit().putInt(KEY_SEQ, n).apply()
        return n
    }

    // ───────────────────────── 签名 ─────────────────────────

    /** deviceToken：由两端共有的 COS secret_key 派生（无需用户填写）。 */
    private fun token(secretKey: String): String = hmacSha256Hex(secretKey, TOKEN_SALT)

    private fun signBody(env: JSONObject): String =
        SIGN_FIELDS.joinToString("|") { k -> if (env.isNull(k)) "" else env.get(k).toString() }

    private fun sign(env: JSONObject, tok: String): String =
        hmacSha256Hex(tok, signBody(env)).take(32)

    private fun hmacSha256Hex(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    // ───────────────────────── 键空间 ─────────────────────────

    private fun bridgePrefix(cfg: CloudSyncManager.CloudConfig): String {
        val root = cfg.prefix.trim('/').substringBefore('/').ifBlank { "stockanalysis" }
        return "$root/bridge"
    }

    private fun host(cfg: CloudSyncManager.CloudConfig) =
        "${cfg.bucket}.cos.${cfg.region}.myqcloud.com"

    private fun requireCfg(context: Context): CloudSyncManager.CloudConfig {
        CosXmlClient.init(context)   // 2026-09-20：注入全局 context，供腾讯云官方 SDK 使用
        val cfg = CloudSyncManager(context).loadConfig()
        check(cfg.bucket.isNotBlank() && cfg.region.isNotBlank()
                && cfg.secretId.isNotBlank() && cfg.secretKey.isNotBlank()) {
            "云同步未配置（bucket/密钥缺失），联网中继不可用"
        }
        return cfg
    }

    // ───────────────────────── COS 原语 ─────────────────────────

    /** 官方 SDK 客户端（懒加载；context 未注入/初始化异常 → null，自动回退自研签名）。 */
    @Volatile
    private var sdkCli: CosXmlClient? = null

    @Volatile
    private var sdkInitTried = false

    private fun sdkClient(cfg: CloudSyncManager.CloudConfig): CosXmlClient? {
        sdkCli?.let { return it }
        if (sdkInitTried) return null
        synchronized(this) {
            if (sdkCli == null && !sdkInitTried) {
                sdkInitTried = true
                sdkCli = try {
                    CosXmlClient.of(cfg)
                } catch (e: Throwable) {
                    android.util.Log.w("CosRelayClient", "CosXmlClient 初始化失败: ${e.message}")
                    null
                }
            }
        }
        return sdkCli
    }

    /**
     * **只投递、不等应答**（消息类专用，2026-09-21 修复「发送失败」误报）。
     *
     * ### 为什么需要它
     * `command()` 的设计是「PUT 命令 → 轮询等 PC 写回应答 → 返回 data」，最长等 **90 秒**；
     * 等不到就 `throw RuntimeException("中继超时…")`。
     *
     * 但**消息投递本身在 PUT 成功那一刻就完成了**。PC 侧 `relay_worker` 若正在忙、
     * 轮询间隔内没来得及回写应答文件，APK 就会**误报「发送失败」**——而消息其实已经
     * 落到 `cb_inbox.json`（用户实测：报失败但 PC 确实收到了）。
     *
     * ⇒ 消息类（`msg.send`）没必要等同步应答：投递成功即返回 msgId，
     *   回复由 `msg.replies` 轮询获取（本来就是异步的）。
     *
     * 返回值：msgId（可用于日志关联）。
     */
    suspend fun deliver(
        context: Context,
        method: String,
        params: JSONObject = JSONObject(),
    ): String = withContext(Dispatchers.IO) {
        val cfg = requireCfg(context)
        val dev = deviceId(context)
        val prefix = bridgePrefix(cfg)
        val tok = token(cfg.secretKey)
        val seq = nextSeq(context)
        val msgId = UUID.randomUUID().toString().replace("-", "")

        val env = JSONObject()
            .put("v", 1).put("msgId", msgId).put("deviceId", dev).put("seq", seq)
            .put("ts", System.currentTimeMillis() / 1000).put("kind", "cmd")
            .put("method", method).put("params", params)
            .put("replyTo", JSONObject.NULL).put("ok", true)
            .put("data", JSONObject()).put("err", JSONObject.NULL)
        env.put("sig", sign(env, tok))

        putText(cfg, "$prefix/pc/inbox/$dev/${"%08d".format(seq)}-$msgId.json", env.toString())
        // 投递成功即视为成功：记录通信时间 + 顺手心跳
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_OK, System.currentTimeMillis()).apply()
        runCatching { beat(context) }
        msgId
    }

    private fun putText(cfg: CloudSyncManager.CloudConfig, key: String, body: String) {
        // 2026-09-20：优先走**腾讯云官方 SDK**（CosXmlClient）。
        // 自研 CosSigner 在 APK 侧持续 403 SignatureDoesNotMatch，而算法/编码/密钥/键名
        // 已逐项验证与 PC 侧一致（PC 同输入 PUT 实测 HTTP 200），疑为 Android 平台差异。
        // 官方 SDK 失败时**自动回退**自研签名，保证可用性不受影响。
        sdkClient(cfg)?.let { sdk ->
            val (ok, code, detail) = sdk.put(key, body.toByteArray(Charsets.UTF_8))
            if (ok) {
                android.util.Log.i("CosRelayClient", "SDK put 成功: $key（官方 SDK 通道）")
                return
            }
            android.util.Log.w("CosRelayClient", "SDK put 失败(code=$code $detail)，回退自研: $key")
        }
        val h = host(cfg)
        val now = System.currentTimeMillis() / 1000
        val headers = mapOf("host" to h, "content-type" to "application/json")
        val auth = CosSigner.sign(cfg.secretId, cfg.secretKey, "put", "/$key",
            emptyMap(), headers, now, now + 600)
        val req = Request.Builder()
            .url("https://$h/$key")
            .put(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", auth)
            .header("Content-Type", "application/json")
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) {
                // 2026-09-19：403 时把 COS 的 <Code>/<Message> 打全（旧实现只取 200 字符，
                // 只看到 xml 声明，无法区分「密钥错 / 时间偏差 / 地域不符 / 无权限」）。
                val body = r.body?.string() ?: ""
                val code = Regex("<Code>([^<]+)</Code>").find(body)?.groupValues?.get(1)
                val msg = Regex("<Message>([^<]+)</Message>").find(body)?.groupValues?.get(1)
                val hint = when (code) {
                    "SignatureDoesNotMatch" -> "（密钥 secretId/secretKey 不匹配或已轮换）"
                    "RequestTimeTooSkewed" -> "（手机系统时间与标准时间偏差过大，请开启自动对时）"
                    "AccessDenied" -> "（子账号/密钥无该桶写权限）"
                    "NoSuchBucket" -> "（bucket 名或 region 地域填错）"
                    else -> ""
                }
                throw RuntimeException(
                    "COS PUT HTTP ${r.code}${if (code != null) " [$code]$hint" else ""}: " +
                        (msg ?: body.take(300))
                )
            }
        }
    }

    private fun getText(cfg: CloudSyncManager.CloudConfig, key: String): String? {
        val h = host(cfg)
        val now = System.currentTimeMillis() / 1000
        val auth = CosSigner.sign(cfg.secretId, cfg.secretKey, "get", "/$key",
            emptyMap(), mapOf("host" to h), now, now + 600)
        val req = Request.Builder()
            .url("https://$h/$key")
            .header("Authorization", auth)
            .get()
            .build()
        client.newCall(req).execute().use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) {
                throw RuntimeException("COS GET HTTP ${r.code}: ${r.body?.string()?.take(200)}")
            }
            return r.body?.string()
        }
    }

    private fun listKeys(cfg: CloudSyncManager.CloudConfig, prefix: String): List<String> {
        val h = host(cfg)
        val now = System.currentTimeMillis() / 1000
        val params = mapOf("prefix" to prefix, "max-keys" to "1000")
        val auth = CosSigner.sign(cfg.secretId, cfg.secretKey, "get", "/",
            params, mapOf("host" to h), now, now + 600)
        val req = Request.Builder()
            .url("https://$h/?prefix=$prefix&max-keys=1000")
            .header("Authorization", auth)
            .get()
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) {
                throw RuntimeException("COS LIST HTTP ${r.code}: ${r.body?.string()?.take(200)}")
            }
            val text = r.body?.string().orEmpty()
            return Regex("<Key>(.*?)</Key>").findAll(text).map { it.groupValues[1] }.toList()
        }
    }

    // ───────────────────────── 命令（请求-应答） ─────────────────────────

    /**
     * 发送一条命令并等待 PC 应答，返回应答的 `data` 对象。
     *
     * 对应 PC 端方法表（见 `relay_worker.METHODS`）：
     * `status.get` / `task.types` / `task.submit` / `task.get` / `task.list` /
     * `task.cancel` / `task.logs` / `msg.send` / `msg.replies` /
     * `candidates.fetch` / `rotation.fetch` / `etf.live`
     *
     * @throws RuntimeException 云同步未配置 / PC 未回 / PC 返回失败
     */
    suspend fun command(
        context: Context,
        method: String,
        params: JSONObject = JSONObject(),
        timeoutMs: Long = 90_000,
    ): JSONObject = withContext(Dispatchers.IO) {
        val cfg = requireCfg(context)
        val dev = deviceId(context)
        val prefix = bridgePrefix(cfg)
        val tok = token(cfg.secretKey)
        val seq = nextSeq(context)
        val msgId = UUID.randomUUID().toString().replace("-", "")

        val env = JSONObject()
            .put("v", 1).put("msgId", msgId).put("deviceId", dev).put("seq", seq)
            .put("ts", System.currentTimeMillis() / 1000).put("kind", "cmd")
            .put("method", method).put("params", params)
            .put("replyTo", JSONObject.NULL).put("ok", true)
            .put("data", JSONObject()).put("err", JSONObject.NULL)
        env.put("sig", sign(env, tok))

        putText(cfg, "$prefix/pc/inbox/$dev/${"%08d".format(seq)}-$msgId.json", env.toString())

        val outPrefix = "$prefix/pc/outbox/$dev/"
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            for (k in listKeys(cfg, outPrefix).sorted()) {
                val txt = getText(cfg, k) ?: continue
                val obj = try {
                    JSONObject(txt)
                } catch (_: Exception) {
                    continue
                }
                if (obj.optString("replyTo") != msgId) continue
                if (!obj.optBoolean("ok", false)) {
                    throw RuntimeException(obj.optString("err").ifBlank { "PC 端执行失败" })
                }
                context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putLong(KEY_LAST_OK, System.currentTimeMillis()).apply()
                // 顺带上报心跳：PC 侧 relay_push 靠它把「选股完成」广播到本机
                runCatching { beat(context) }
                return@withContext obj.optJSONObject("data") ?: JSONObject()
            }
            delay(1200)
        }
        throw RuntimeException("中继超时（${timeoutMs / 1000}s）：PC 端 relay_worker 未运行？")
    }

    // ───────────────────────── 主动推送（P3） ─────────────────────────

    /**
     * 拉取 PC 主动推送（`pc/push/{deviceId}/`），返回比本地游标新的消息（按 seq 升序）。
     *
     * 与 [command] 的「请求-应答」不同：这是**单向** PC → APK 通知
     * （选股完成 / 盘前情报 / 任务结束）。APK 不必先发命令，PC 也不必反向连上手机
     * （手机多在 NAT 后面，根本连不上）。
     *
     * 游标存 `SharedPreferences(push_seq)`：只取比游标更新的消息，**不删 COS 对象**
     * （对象由 PC 侧 `relay_push._prune` 按 keep 条数滚动清理），
     * 所以多个入口（对话框 / 后台 / 通知）可以重复安全调用、互不抢消息。
     *
     * @param onEach 每命中一条且**验签通过**就回调一次，便于边拉边展示
     */
    suspend fun fetchPushes(
        context: Context,
        limit: Int = 30,
        onEach: ((JSONObject) -> Unit)? = null,
    ): List<JSONObject> = withContext(Dispatchers.IO) {
        val cfg = requireCfg(context)
        val dev = deviceId(context)
        val tok = token(cfg.secretKey)
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val after = sp.getInt(KEY_PUSH_SEQ, -1)
        val prefix = "${bridgePrefix(cfg)}/pc/push/$dev/"
        val out = ArrayList<JSONObject>()
        var cursor = after
        try {
            for (k in listKeys(cfg, prefix).sortedBy { seqOf(it) }) {
                val seq = seqOf(k)
                if (seq < 0 || seq <= after) continue
                val txt = getText(cfg, k) ?: continue
                val obj = try {
                    JSONObject(txt)
                } catch (_: Exception) {
                    continue
                }
                // 验签：只签元数据（见类注释），跨端一定对得齐
                if (obj.optString("sig") != sign(obj, tok)) continue
                obj.put("_seq", seq)
                out.add(obj)
                cursor = maxOf(cursor, seq)
                onEach?.invoke(obj)
                if (out.size >= limit) break
            }
        } catch (e: Exception) {
            // 网络抖动不该让已解析到的消息丢失；一条都没有时把异常抛给调用方
            android.util.Log.w(TAG, "拉取主动推送失败: ${e.message}")
            if (out.isEmpty()) throw e
        }
        if (cursor > after) {
            sp.edit().putInt(KEY_PUSH_SEQ, cursor).apply()
            sp.edit().putLong(KEY_LAST_OK, System.currentTimeMillis()).apply()
        }
        out
    }

    /** 已消费到的最大推送 seq（-1 = 从未消费过）。 */
    fun pushCursor(context: Context): Int =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_PUSH_SEQ, -1)

    /** 从对象键解析 seq（`…/00000012-<msgId>.json`）；解析失败返回 -1。 */
    private fun seqOf(key: String): Int =
        Regex("(\\d+)-[0-9a-fA-F]+\\.json$").find(key)
            ?.groupValues?.get(1)?.toIntOrNull() ?: -1

    /**
     * 写设备心跳 `devices/{deviceId}.json`，让 PC 侧 `relay_push.registered_devices()`
     * 能广播到本机。best-effort：失败只记一行日志（心跳不该影响业务）。
     */
    suspend fun beat(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                val cfg = requireCfg(context)
                val dev = deviceId(context)
                val obj = JSONObject()
                    .put("deviceId", dev)
                    .put("model", Build.MODEL ?: "android")
                    .put("app", "StockAnalysis")
                    .put("lastSeen", System.currentTimeMillis() / 1000)
                putText(cfg, "${bridgePrefix(cfg)}/devices/$dev.json", obj.toString())
            } catch (e: Exception) {
                android.util.Log.w(TAG, "心跳写入失败: ${e.message}")
            }
        }
    }

    /** 连通性自检：返回 PC 服务状态（不填 IP 即可验证）。 */
    suspend fun ping(context: Context): JSONObject = command(context, "status.get", JSONObject(), 20_000)
}
