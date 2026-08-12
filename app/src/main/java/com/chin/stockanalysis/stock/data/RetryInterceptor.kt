package com.chin.stockanalysis.stock.data

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * OkHttp 网络重试拦截器 — 自动指数退避重试
 *
 * 解决的问题：
 * - 东方财富 API "unexpected end of stream" 频繁发生
 * - 并发请求导致连接被重置
 * - 网络抖动导致偶发失败
 *
 * 策略：
 * - 最多重试 maxRetries 次
 * - 指数退避：baseDelay * 2^attempt（300ms, 600ms, 1200ms）
 * - 仅对 IOException（网络错误）和 5xx 服务端错误重试
 * - 4xx 客户端错误不重试（请求本身有问题）
 */
class RetryInterceptor(
    private val maxRetries: Int = 2,
    private val baseDelayMs: Long = 300L,
    private val tag: String = "RetryInterceptor"
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var lastException: IOException? = null
        var lastResponse: Response? = null

        for (attempt in 0..maxRetries) {
            try {
                // 释放上一次的 response（避免连接泄漏）
                lastResponse?.close()

                val response = chain.proceed(request)

                // 5xx 服务端错误 → 重试
                if (response.code in 500..599 && attempt < maxRetries) {
                    Log.w(tag, "HTTP ${response.code} for ${request.url.host}${request.url.encodedPath}, 重试 $attempt/${maxRetries}")
                    response.close()
                    sleep(attempt)
                    continue
                }

                return response
            } catch (e: IOException) {
                lastException = e
                lastResponse?.close()

                if (attempt < maxRetries) {
                    Log.w(tag, "网络异常(${e.javaClass.simpleName}) for ${request.url.host}${request.url.encodedPath}, 重试 $attempt/${maxRetries}: ${e.message}")
                    sleep(attempt)
                }
            }
        }

        // 所有重试都失败
        throw lastException ?: IOException("所有重试失败: ${request.url}")
    }

    private fun sleep(attempt: Int) {
        val delay = baseDelayMs * (1L shl attempt)
        Thread.sleep(delay)
    }
}
