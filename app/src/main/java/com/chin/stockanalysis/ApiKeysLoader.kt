package com.chin.stockanalysis

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.util.Properties

/**
 * API 密钥加载器 — 从 apk 内置 assets 或本地配置文件读取密钥
 *
 * 加载顺序（首次命中即返回）：
 * 1. apk 内置 assets/api_keys_local.properties（Context.assets.open）
 * 2. App 内部存储：/data/data/{package}/files/api_keys_local.properties
 * 3. 外部存储根目录：/sdcard/api_keys_local.properties
 * 4. 纯 JVM 文件系统（单元测试场景）
 *
 * 优先级链：用户设置(SharedPreferences) > 本配置文件 > 空字符串
 * 本文件不包含任何硬编码的 API Key。
 */
object ApiKeysLoader {

    private const val CONFIG_FILE_NAME = "api_keys_local.properties"
    private const val TAG = "ApiKeysLoader"

    private var loadedProps: Properties? = null

    /** 必须在 Application 或首次使用前调用，提供 Context 以读取 assets */
    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    fun get(key: String): String {
        return getProperties()?.getProperty(key, "")?.trim() ?: ""
    }

    private fun getProperties(): Properties? {
        if (loadedProps != null) return loadedProps

        val props = Properties()
        val ctx = appContext

        // 1. 优先从 apk 内置 assets 读取
        if (ctx != null) {
            try {
                ctx.assets.open(CONFIG_FILE_NAME).use { stream ->
                    props.load(stream)
                    android.util.Log.d(TAG, "✅ 从 assets 加载配置文件")
                    loadedProps = props
                    return props
                }
            } catch (_: Exception) {
                android.util.Log.d(TAG, "ℹ️ assets 中未找到 $CONFIG_FILE_NAME")
            }
        }

        // 2. App 内部存储
        val internalFile = try {
            File("/data/data/${ctx?.packageName ?: "com.chin.stockanalysis"}/files/$CONFIG_FILE_NAME")
        } catch (_: Exception) { null }
        if (internalFile != null && internalFile.exists()) {
            try {
                FileInputStream(internalFile).use { props.load(it) }
                android.util.Log.d(TAG, "✅ 从内部存储加载: ${internalFile.absolutePath}")
                loadedProps = props
                return props
            } catch (e: Exception) {
                android.util.Log.w(TAG, "⚠️ 读取内部存储失败: ${e.message}")
            }
        }

        // 3. 外部存储根目录
        val sdcardFile = File("/sdcard/$CONFIG_FILE_NAME")
        if (sdcardFile.exists()) {
            try {
                FileInputStream(sdcardFile).use { props.load(it) }
                android.util.Log.d(TAG, "✅ 从外部存储加载: ${sdcardFile.absolutePath}")
                loadedProps = props
                return props
            } catch (e: Exception) {
                android.util.Log.w(TAG, "⚠️ 读取外部存储失败: ${e.message}")
            }
        }

        // 4. 纯 JVM 文件系统（单元测试）
        val jvmCandidates = listOf(File(CONFIG_FILE_NAME), File("../$CONFIG_FILE_NAME"))
        for (f in jvmCandidates) {
            if (f.exists()) {
                try {
                    FileInputStream(f).use { props.load(it) }
                    android.util.Log.d(TAG, "✅ 从文件系统加载: ${f.absolutePath}")
                    loadedProps = props
                    return props
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "⚠️ 读取文件系统失败: ${e.message}")
                }
            }
        }

        android.util.Log.d(TAG, "ℹ️ 未找到 $CONFIG_FILE_NAME，使用空配置")
        loadedProps = props
        return props
    }

    fun reset() {
        loadedProps = null
    }

    const val KEY_SILICONFLOW = "SILICONFLOW_KEY"
    const val KEY_DOUBAO = "DOUBAO_KEY"
    const val KEY_DEEPSEEK = "DEEPSEEK_KEY"
    const val KEY_ALIYUN_MAAS = "ALIYUN_MAAS_KEY"

    fun siliconflowKey(): String = get(KEY_SILICONFLOW)
    fun doubaoKey(): String = get(KEY_DOUBAO)
    fun deepseekKey(): String = get(KEY_DEEPSEEK)
    fun aliyunMaasKey(): String = get(KEY_ALIYUN_MAAS)

}