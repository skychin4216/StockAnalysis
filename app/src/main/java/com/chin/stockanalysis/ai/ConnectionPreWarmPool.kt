package com.chin.stockanalysis.ai

import android.util.Log
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ## 连接预热池（参考豆包AI 架构）
 *
 * 在 App 启动或对话开始前预建立 HTTP/2 连接，消除首次 TCP/TLS 握手延迟（~200-300ms）。
 *
 * ### 工作原理
 * - 池内维护 2 个已建立 TCP + TLS 握手的 OkHttpClient
 * - 每次需要发起 AI 请求时，从池中 `acquire()` 取出已预热的 client
 * - 使用完毕后 `release()` 归还至池
 * - 池空时自动 fallback 创建新连接
 *
 * ### 使用方式
 * ```kotlin
 * val pool = ConnectionPreWarmPool.getInstance()
 * pool.preWarm("https://api.deepseek.com", bearerToken)
 * val client = pool.acquire()
 * // ... use client ...
 * pool.release(client)
 * ```
 */
class ConnectionPreWarmPool private constructor() {

    companion object {
        private const val TAG = "PreWarmPool"
        private const val POOL_SIZE = 2
        private const val WARM_TIMEOUT_SECONDS = 5L
        private const val MAX_IDLE_CONNECTIONS = 5
        private const val KEEP_ALIVE_MINUTES = 5L

        @Volatile
        private var instance: ConnectionPreWarmPool? = null

        fun getInstance(): ConnectionPreWarmPool {
            return instance ?: synchronized(this) {
                instance ?: ConnectionPreWarmPool().also { instance = it }
            }
        }
    }

    private val pool = ConcurrentLinkedQueue<OkHttpClient>()
    private val warmed = AtomicBoolean(false)

    // ── 创建带连接池 + HTTP/2 的 OkHttpClient ──
    private fun createClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)       // 流式无读取超时
            .retryOnConnectionFailure(true)
            .connectionPool(ConnectionPool(
                MAX_IDLE_CONNECTIONS,
                KEEP_ALIVE_MINUTES,
                TimeUnit.MINUTES
            ))
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))  // HTTP/2 多路复用
            .build()
    }

    /**
     * 预热连接：向指定 baseUrl 发送 HEAD 请求建立 TCP+TLS 连接。
     *
     * @param baseUrl AI 服务 base URL
     * @param bearerToken 用于 Authorization header（可选，预热 HEAD 请求也需要 auth）
     */
    fun preWarm(baseUrl: String, bearerToken: String? = null) {
        if (warmed.compareAndSet(false, true)) {
            Log.i(TAG, "🔥 开始预热 $POOL_SIZE 个连接 → $baseUrl")
            repeat(POOL_SIZE) {
                warmSingleConnection(baseUrl, bearerToken)
            }
        }
    }

    /** 单次预热：发送 HEAD 请求建立连接 */
    private fun warmSingleConnection(baseUrl: String, bearerToken: String?) {
        val client = createClient()
        val url = baseUrl.trimEnd('/') + "/chat/completions"

        val requestBuilder = Request.Builder()
            .url(url)
            .head()
        if (!bearerToken.isNullOrBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $bearerToken")
        }

        try {
            val response = client.newCall(requestBuilder.build()).execute()
            val code = response.code
            response.close()
            // 2xx/403/401 都说明连接已建立
            if (code in 200..499) {
                pool.offer(client)
                Log.d(TAG, "✅ 连接预热成功 (HTTP $code)")
            } else {
                pool.offer(client) // 仍加入池，连接已建立
                Log.w(TAG, "⚠️ 连接预热异常 (HTTP $code)，但连接已建立")
            }
        } catch (e: Exception) {
            // 预热失败也不影响后续使用，acquire 会 fallback
            Log.w(TAG, "⚠️ 连接预热失败: ${e.message?.take(80)}，fallback 到按需创建")
        }
    }

    /**
     * 从池中获取已预热的 OkHttpClient。池空时 fallback 创建新 client。
     */
    fun acquire(): OkHttpClient {
        val client = pool.poll()
        if (client != null) {
            Log.d(TAG, "♻️ 从池中获取预热连接（剩余: ${pool.size}）")
            return client
        }
        Log.d(TAG, "⚡ 池空，创建新连接")
        return createClient()
    }

    /**
     * 归还 client 到池。
     */
    fun release(client: OkHttpClient) {
        if (pool.size < POOL_SIZE) {
            pool.offer(client)
        }
        // 超过池容量则丢弃（OkHttp 内部连接池会自动管理）
    }

    /** 清空并关闭池中所有连接 */
    fun shutdown() {
        while (true) {
            val client = pool.poll() ?: break
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
        warmed.set(false)
        Log.d(TAG, "🛑 连接池已关闭")
    }

    /** 当前池中可用连接数 */
    val availableConnections: Int get() = pool.size

    /** 是否已预热 */
    val isWarmed: Boolean get() = warmed.get()
}