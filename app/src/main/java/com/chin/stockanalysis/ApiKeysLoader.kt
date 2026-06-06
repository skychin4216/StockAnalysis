package com.chin.stockanalysis

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.util.Properties

/**
 * API 密钥加载器 — 多源回退
 *
 * 加载顺序（首次命中即返回）：
 * 0. BuildConfig 编译时常量 (highest priority for debug builds)
 * 1. apk 内置 assets/api_keys_local.properties
 * 2. App 内部存储：/data/data/{package}/files/api_keys_local.properties
 * 3. 外部存储根目录：/sdcard/api_keys_local.properties
 * 4. 纯 JVM 文件系统（单元测试场景）
 */
object ApiKeysLoader {

    private const val CONFIG_FILE_NAME = "api_keys_local.properties"
    private const val TAG = "ApiKeysLoader"

    const val KEY_SILICONFLOW = "SILICONFLOW_KEY"
    const val KEY_DOUBAO = "DOUBAO_KEY"
    const val KEY_DEEPSEEK = "DEEPSEEK_KEY"
    const val KEY_ALIYUN_MAAS = "ALIYUN_MAAS_KEY"

    private var loadedProps: Properties? = null

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    fun get(key: String): String {
        // ── 0. BuildConfig 编译时常量 (debug 构建自动注入) ──
        val bcVal = getFromBuildConfig(key)
        if (bcVal.isNotBlank()) {
            android.util.Log.d(TAG, "✅ 使用 BuildConfig Key: $key")
            return bcVal
        }

        // ── 1-4. Properties 文件回退 ──
        return getProperties()?.getProperty(key, "")?.trim() ?: ""
    }

    /** 从 BuildConfig 读取编译时注入的 Key（debug 版本自动填充） */
    private fun getFromBuildConfig(key: String): String {
        return try {
            when (key) {
                KEY_DOUBAO -> com.chin.stockanalysis.BuildConfig.DOUBAO_KEY
                KEY_SILICONFLOW -> com.chin.stockanalysis.BuildConfig.SILICONFLOW_KEY
                KEY_DEEPSEEK -> com.chin.stockanalysis.BuildConfig.DEEPSEEK_KEY
                KEY_ALIYUN_MAAS -> com.chin.stockanalysis.BuildConfig.ALIYUN_MAAS_KEY
                else -> ""
            }
        } catch (_: Exception) { "" }
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

        loadedProps = props
        return props
    }

    fun reset() {
        loadedProps = null
    }

    fun siliconflowKey(): String = get(KEY_SILICONFLOW)
    fun doubaoKey(): String = get(KEY_DOUBAO)
    fun deepseekKey(): String = get(KEY_DEEPSEEK)
    fun aliyunMaasKey(): String = get(KEY_ALIYUN_MAAS)
}