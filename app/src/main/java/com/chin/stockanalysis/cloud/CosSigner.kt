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
        // 2026-09-20 诊断 + 防御修复：
        //   APK 提交 push.round 一直报 403 SignatureDoesNotMatch，而 PC 侧用**完全相同**
        //   的输入（同一 host/key/headers/算法/密钥）PUT 到同一键空间是 HTTP 200。
        //   ⇒ 代码路径已逐行比对一致 ⇒ 高度怀疑 APK 运行时拿到的密钥被污染
        //     （BOM / 零宽字符 / 首尾空白 / JSON 解析残留）。
        //   故：① 先 trim 再签名（真含空白则直接修好）；② 打印输入摘要便于比对定位。
        val ak = secretId.trim()
        val sk = secretKey.trim()
        if (ak.length != secretId.length || sk.length != secretKey.length) {
            android.util.Log.w("CosSigner", "⚠️ 密钥含首尾空白，已 trim：" +
                "ak ${secretId.length}→${ak.length}, sk ${secretKey.length}→${sk.length}")
        }
        android.util.Log.w("CosSigner", "dbg ak=${ak.take(6)}..(${ak.length}) skLen=${sk.length} " +
            "m=$method path=$uriPathname hdr=${httpHeaders.keys.sorted()} t=$startTime")
        selfCheck()
        return signInner(ak, sk, method, uriPathname, httpParameters, httpHeaders,
            startTime, endTime)
    }

    private var selfChecked = false

    /**
     * 一次性自检：用**固定测试向量**验证本机算法输出是否与 PC 侧一致。
     *
     * ⚠️ 向量里的 ak/sk 是**编造的假值，绝不可替换成真实密钥** ——
     * 2026-09-20 曾误把真实密钥写在此处，导致 GitHub Push Protection 拦截整次
     * 推送（`GH013 Repository rule violations` / `Push cannot contain secrets` /
     * `Tencent Cloud Secret ID`）。自检只需要"固定输入"，用假值同样能验证算法。
     *
     * 期望值 `6b8cc60b...` 由 PC 侧 Python **逐行复现本算法**（与 cos_utils.sign 同构）
     * 在**同一假向量**上算出，用于判定平台差异：
     *   ✅ 通过 → 算法无恙，问题在运行时输入值（ak/sk/path/t）
     *   ❌ 失败 → 本机编码仍与 PC 不一致（说明还存在未发现的平台差异）
     */
    private fun selfCheck() {
        if (selfChecked) return
        selfChecked = true
        try {
            val auth = signInner(
                "FAKE_TEST_VECTOR_AK",
                "FAKE_TEST_VECTOR_SK",
                "put", "/stockanalysis/bridge/probe_apk.json", emptyMap(),
                mapOf("host" to "stockanalysis-1471852701-1471852701.cos.ap-guangzhou.myqcloud.com",
                      "content-type" to "application/json"),
                1758300000L, 1758300600L)
            val got = auth.substringAfter("q-signature=")
            val expect = "6b8cc60b6340465b9a8126b916f825081bc20755"
            android.util.Log.w("CosSigner", if (got == expect)
                "✅ 自检通过：算法与 PC 完全一致（sig=${got.take(16)}…）"
            else "❌ 自检失败：got=$got expect=$expect")
        } catch (e: Exception) {
            android.util.Log.w("CosSigner", "❌ 自检异常: ${e.message}")
        }
    }

    /** 实际签名逻辑（与 PC 侧 cos_utils.sign 逐行同构）。 */
    private fun signInner(
        secretId: String,
        secretKey: String,
        method: String,
        uriPathname: String,
        httpParameters: Map<String, String>,
        httpHeaders: Map<String, String>,
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

    /**
     * RFC 3986 URL 编码 —— **手写实现**（2026-09-20 修复 403 SignatureDoesNotMatch）。
     *
     * COS 签名要求：仅保留 `A-Za-z0-9` 与 `-_.~`，其余字节按 `%XX`（**大写**）编码。
     *
     * 原实现用 `URLEncoder.encode` + 三处 replace 打补丁，但 `URLEncoder` 是
     * **application/x-www-form-urlencoded** 编码（空格→`+`、保留 `*`、`~`→`%7E`），
     * 其行为在 **Java SE 与 Android 平台之间并不保证完全一致**；而 COS 签名对编码
     * **逐字节敏感** —— 只要有一个字节不同就报 `SignatureDoesNotMatch`。
     *
     * 已用测试向量证明：**PC 侧 Python 复现本算法** 与 `cos_utils.sign` 输出
     * **完全相同**（同一输入得同一签名，见上方 `selfCheck` 的期望值）；
     * 既然算法在 PC 上等价，那么 APK 侧失败只能源于**平台编码差异** →
     * 手写编码彻底消除该变量，与 PC 逐字节等价。
     */
    private fun urlEncode(s: String): String {
        val hex = "0123456789ABCDEF"
        val sb = StringBuilder(s.length * 2)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if ((ch in 'A'..'Z') || (ch in 'a'..'z') || (ch in '0'..'9') ||
                ch == '-' || ch == '_' || ch == '.' || ch == '~') {
                sb.append(ch)
            } else {
                sb.append('%').append(hex[c shr 4]).append(hex[c and 0x0F])
            }
        }
        return sb.toString()
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
