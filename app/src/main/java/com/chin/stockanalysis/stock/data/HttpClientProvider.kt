package com.chin.stockanalysis.stock.data

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 共享 OkHttp 客户端提供者
 *
 * 所有数据源共用同一个连接池，避免重复创建 TCP 连接。
 * 支持连接复用、keep-alive、降低 DNS 解析次数。
 */
object HttpClientProvider {

    private const val TAG = "HttpClientProvider"

    /**
     * 实时行情客户端（短连接、低超时）
     * - 连接超时 5s
     * - 读取超时 8s
     * - 最大空闲连接 10
     * - keep-alive 60s
     */
    val realtimeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(10, 60, TimeUnit.SECONDS))
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * 健康检查客户端（更短超时，避免阻塞）
     */
    val healthCheckClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(5, 30, TimeUnit.SECONDS))
            .retryOnConnectionFailure(false) // 健康检查不重试
            .followRedirects(false)
            .build()
    }

    /**
     * 长时间运行的 WebSocket 客户端（如果后续需要）
     */
    val webSocketClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)  // 无读取超时（长连接）
            .writeTimeout(10, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(5, 120, TimeUnit.SECONDS))
            .retryOnConnectionFailure(true)
            .pingInterval(30, TimeUnit.SECONDS) // 每30秒发送心跳
            .build()
    }
}