package com.chin.stockanalysis.cloud

import android.content.Context
import com.tencent.cos.xml.CosXmlService
import com.tencent.cos.xml.CosXmlServiceConfig
// ⚠️ `object` 是 Kotlin 硬关键字 → 包路径中的 object 段必须用反引号转义，
//    否则报 "Qualified name must be a '.'-separated identifier"。
import com.tencent.cos.xml.model.`object`.PutObjectRequest
import com.tencent.qcloud.core.auth.ShortTimeCredentialProvider

/**
 * ## 腾讯云 COS 官方 SDK 封装（2026-09-20，仅实现 PUT）
 *
 * ### 为什么换官方 SDK
 *
 * APK 提交 `push.round` 长期 403 `SignatureDoesNotMatch`。排查结果（全部实测）：
 * - 自研 `CosSigner`（V5 HMAC-SHA1）与 PC 侧 `cos_utils.sign` **逐行一致**：
 *   URL 编码规则、HttpString 拼装、StringToSign、header 集合、host 拼接全部相同；
 * - Python **逐行复现 APK 算法**，同一输入得到**完全相同的签名**（自检期望值见
 *   `CosSigner.selfCheck`），且该签名 **PUT 到 COS 实测 HTTP 200**；
 * - 密钥值（`secrets.enc` 解密原文）、键名、注入时机（手机日志 `已注入 2 项云端密钥`）均正确。
 *
 * ⇒ 算法与输入都对，问题只能是 **Android 平台运行时差异**（如 `URLEncoder` 在
 *   Android 上行为与 Java SE 不同，而 COS 签名逐字节敏感）。
 * 故改用官方 SDK，把签名/编码/重试交给腾讯维护。
 *
 * 依赖：`com.qcloud.cos:cos-android:5.9.14`（Maven Central）。
 * 覆盖范围：**只实现 PUT** —— APK → COS 中继的提交是 PUT，且这是唯一出问题的路径；
 *   GET/列表仍走自研 `CosSigner`（那条路一直正常，不必动）。
 *
 * 自研 `CosSigner` **完整保留**作为 fallback：本类初始化失败时调用方自动回退。
 */
class CosXmlClient(
    context: Context,
    val bucket: String,
    region: String,
    secretId: String,
    secretKey: String
) {
    private val appContext = context.applicationContext

    /** 短时密钥提供者（SDK 内部据此完成 V5 签名与时钟处理）。 */
    private val credentialProvider = ShortTimeCredentialProvider(secretId, secretKey, 600)

    // 2026-09-20：改为**分步调用** —— 链式写法在 Kotlin 下报 `Unresolved reference: build`
    // （`Builder` 的 setter 返回类型使链式推断断裂）；分步后各调用独立解析，稳妥。
    // 2026-09-20：构建方法名是 **`.builder()`** —— 取自腾讯云官方快速入门示例原文
    // （该示例用 `.setEndpointSuffix(...).setDebuggable(true).builder()`）。
    // 网上广泛流传的 `.build()` 实为文档笔误，本 SDK 版本无此方法（已实测）。
    private val config: CosXmlServiceConfig = CosXmlServiceConfig.Builder()
        .setRegion(region)
        .builder()

    @Volatile
    private var service: CosXmlService? = null

    private fun svc(): CosXmlService = service ?: synchronized(this) {
        service ?: CosXmlService(appContext, config, credentialProvider).also { service = it }
    }

    /** 上传（PUT Object）。返回 (是否成功, HTTP 码或 -1, 详情)。 */
    fun put(key: String, body: ByteArray): Triple<Boolean, Int, String> = try {
        val req = PutObjectRequest(bucket, key, body)
        // 通过 ObjectMetadata 设置 Content-Type（SDK 无 setContentType 直连方法）
        runCatching { req.getMetadata()?.setContentType("application/json") }
        svc().putObject(req)
        Triple(true, 200, "ok")
    } catch (e: Exception) {
        Triple(false, -1, "${e.javaClass.simpleName}: ${e.message}")
    }

    /** 上传字符串（便利方法）。 */
    fun putText(key: String, text: String): Triple<Boolean, Int, String> =
        put(key, text.toByteArray(Charsets.UTF_8))

    companion object {
        private const val TAG = "CosXmlClient"

        /** 全局 Application Context（业务侧首次调用 init() 注入）。 */
        @Volatile
        private var appCtxRef: Context? = null

        /** 幂等注入 Application Context。 */
        fun init(context: Context) {
            if (appCtxRef == null) appCtxRef = context.applicationContext
        }

        /** 由 CloudSyncManager 配置构造（依赖 init() 已注入 context）。 */
        fun of(cfg: CloudSyncManager.CloudConfig): CosXmlClient? {
            val ctx = appCtxRef
            if (ctx == null) {
                android.util.Log.w(TAG, "尚未 init(appContext)，官方 SDK 不可用（回退自研签名）")
                return null
            }
            return if (cfg.bucket.isBlank() || cfg.region.isBlank() ||
                cfg.secretId.isBlank() || cfg.secretKey.isBlank()) {
                null
            } else {
                CosXmlClient(ctx, cfg.bucket, cfg.region,
                    cfg.secretId.trim(), cfg.secretKey.trim())
            }
        }
    }
}
