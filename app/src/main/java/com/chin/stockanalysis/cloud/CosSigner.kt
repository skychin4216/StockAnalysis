package com.chin.stockanalysis.cloud

import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * ## 腾讯云 COS 签名 V5（HMAC-SHA1）
 *
 * 参考官方文档《请求签名》：https://cloud.tencent.com/document/product/436/7778
 *
 * 签名流程：
 * ```
 * signKey = HMAC-SHA1(SecretKey, KeyTime)
 * HttpString = Method\nUriPathname\nHttpParameters\nHttpHeaders\n
 * StringToSign = sha1\nKeyTime\nSHA1(HttpString)\n
 * Signature = HMAC-SHA1(signKey, StringToSign)
 * Authorization = q-sign-algorithm=sha1&q-ak=SecretId&q-sign-time=KeyTime&q-key-time=KeyTime
 *                &q-header-list=HeaderList&q-url-param-list=UrlParamList&q-signature=Signature
 * ```
 *
 * 使用场景：
 * - App 内置密钥直传数据包到 COS（PUT Object）
 * - App 签名下载参数文件（GET Object，bucket 私有读时）
 * - PC 端 Python 脚本同样实现（见 smalltools/cloud_download.py）
 */
object CosSigner {

    /** 生成 COS 请求的 Authorization 头。 */
    fun sign(
        secretId: String,
        secretKey: String,
        method: String,                 // "put" / "get"（小写）
        uriPathname: String,            // 例如 "/stockanalysis/phone_20260818.zip"
        httpParameters: Map<String, String> = emptyMap(),
        httpHeaders: Map<String, String>,   // 小写 key，如 mapOf("host" to "xxx.cos.ap-guangzhou.myqcloud.com")
        startTime: Long,
        endTime: Long
    ): String {
        val keyTime = "$startTime;$endTime"
        val signKey = hmacSha1Hex(secretKey, keyTime)

        // 参数/头按 key 字典序排列后拼 HttpString
        val paramStr = httpParameters.toSortedMap().entries.joinToString("&") { (k, v) ->
            "${urlEncode(k)}=${urlEncode(v)}"
        }
        val headerStr = httpHeaders.toSortedMap().entries.joinToString("&") { (k, v) ->
            "${urlEncode(k)}=${urlEncode(v)}"
        }
        val httpString = "${method.lowercase()}\n${urlEncodePath(uriPathname)}\n$paramStr\n$headerStr\n"

        val sha1Http = sha1Hex(httpString)
        val stringToSign = "sha1\n$keyTime\n$sha1Http\n"
        val signature = hmacSha1Hex(signKey, stringToSign)

        val headerList = httpHeaders.keys.sorted().joinToString(";")
        val paramList = httpParameters.keys.sorted().joinToString(";")

        return "q-sign-algorithm=sha1&q-ak=$secretId&q-sign-time=$keyTime&q-key-time=$keyTime" +
            "&q-header-list=$headerList&q-url-param-list=$paramList&q-signature=$signature"
    }

    // ───────────────────────── 工具函数 ─────────────────────────

    /** RFC 3986 URL 编码（COS 签名要求保留 -_.~ 并统一大小写） */
    private fun urlEncode(s: String): String {
        val encoded = URLEncoder.encode(s, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")
        return encoded
    }

    /**
     * UriPathname 的 URL 编码：COS 规定路径中的 `/` 分隔符**不编码**
     * （官方示例 HttpString = "put\n/exampleobject\n…"），其余字符按 RFC3986 编码。
     */
    private fun urlEncodePath(s: String): String = urlEncode(s).replace("%2F", "/")

    private fun hmacSha1Hex(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun sha1Hex(data: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(data.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
