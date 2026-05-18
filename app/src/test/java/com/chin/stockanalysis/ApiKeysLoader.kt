package com.chin.stockanalysis

import java.io.File
import java.io.FileInputStream
import java.util.Properties

/**
 * API 密钥加载器 - 从本地配置文件读取密钥
 *
 * 配置文件位置：项目根目录 api_keys_local.properties
 * 本文件已加入 .gitignore，不会提交到 Git 仓库。
 *
 * 优先级：用户设置 > 本配置文件 > 内置 Key
 */
object ApiKeysLoader {

    private const val CONFIG_FILE_NAME = "api_keys_local.properties"

    private val properties: Properties by lazy {
        loadProperties()
    }

    private fun loadProperties(): Properties {
        val props = Properties()
        try {
            // 1. 尝试从当前工作目录加载（Gradle 测试任务运行时）
            var file = File(CONFIG_FILE_NAME)
            if (file.exists()) {
                FileInputStream(file).use { props.load(it) }
                println("[ApiKeysLoader] ✅ 已加载: ${file.absolutePath}")
                return props
            }

            // 2. 尝试从项目根目录加载（Android Studio 直接运行测试时）
            file = File("../$CONFIG_FILE_NAME")
            if (file.exists()) {
                FileInputStream(file).use { props.load(it) }
                println("[ApiKeysLoader] ✅ 已加载: ${file.absolutePath}")
                return props
            }

            // 3. 尝试从用户目录加载
            val userHome = System.getProperty("user.home")
            file = File("$userHome/$CONFIG_FILE_NAME")
            if (file.exists()) {
                FileInputStream(file).use { props.load(it) }
                println("[ApiKeysLoader] ✅ 已加载: ${file.absolutePath}")
                return props
            }

            println("[ApiKeysLoader] ⚠️ 未找到 $CONFIG_FILE_NAME，将使用空配置")
        } catch (e: Exception) {
            println("[ApiKeysLoader] ❌ 加载失败: ${e.message}")
        }
        return props
    }

    /**
     * 获取指定 Key 的值
     * @param key 配置键名
     * @param default 默认值
     */
    fun get(key: String, default: String = ""): String {
        return properties.getProperty(key, default).trim()
    }

    // ═══════════════════════════════════════════════════════════════
    // 预定义的 Key 名称常量
    // ═══════════════════════════════════════════════════════════════

    const val KEY_SILICONFLOW = "SILICONFLOW_KEY"
    const val KEY_DOUBAO = "DOUBAO_KEY"
    const val KEY_DEEPSEEK = "DEEPSEEK_KEY"
    const val KEY_ALIYUN_MAAS = "ALIYUN_MAAS_KEY"

    // ═══════════════════════════════════════════════════════════════
    // 便捷方法
    // ═══════════════════════════════════════════════════════════════

    fun siliconflowKey(): String = get(KEY_SILICONFLOW)
    fun doubaoKey(): String = get(KEY_DOUBAO)
    fun deepseekKey(): String = get(KEY_DEEPSEEK)
    fun aliyunMaasKey(): String = get(KEY_ALIYUN_MAAS)
}