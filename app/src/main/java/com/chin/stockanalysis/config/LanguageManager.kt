package com.chin.stockanalysis.config

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import java.util.Locale

/**
 * 多语言管理器
 *
 * 支持：简体中文(zh-CN)、繁体中文(zh-TW)、英文(en)、韩文(ko)
 * 语言偏好存储在 SharedPreferences 中，通过 Configuration override 生效。
 */
object LanguageManager {

    private const val PREFS_NAME = "language_prefs"
    private const val KEY_LANGUAGE = "app_language"

    /** 支持的语言代码 */
    val SUPPORTED_LANGUAGES = listOf(
        "zh-CN" to "简体中文",
        "zh-TW" to "繁体中文",
        "en"    to "English",
        "ko"    to "한국어"
    )

    /** 当前选中的语言代码（null = 跟随系统） */
    fun getSavedLanguage(context: Context): String? {
        return getPrefs(context).getString(KEY_LANGUAGE, null)
    }

    /** 保存语言选择 */
    fun setLanguage(context: Context, languageCode: String?) {
        getPrefs(context).edit().putString(KEY_LANGUAGE, languageCode).apply()
    }

    /** 获取当前语言的显示名称 */
    fun getCurrentLanguageName(context: Context): String {
        val code = getSavedLanguage(context) ?: return "跟随系统"
        return SUPPORTED_LANGUAGES.firstOrNull { it.first == code }?.second ?: "跟随系统"
    }

    /**
     * 应用语言到 Context（在 Activity.attachBaseContext 中调用）
     */
    fun applyLanguage(baseContext: Context): Context {
        val langCode = getSavedLanguage(baseContext) ?: return baseContext
        val locale = parseLocale(langCode)
        Locale.setDefault(locale)
        val config = Configuration(baseContext.resources.configuration)
        config.setLocale(locale)
        return baseContext.createConfigurationContext(config)
    }

    /**
     * 获取指定语言的 Context（用于动态获取字符串）
     */
    fun getContextForLanguage(context: Context, langCode: String): Context {
        val locale = parseLocale(langCode)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    private fun parseLocale(langCode: String): Locale {
        return when (langCode) {
            "zh-CN" -> Locale.SIMPLIFIED_CHINESE
            "zh-TW" -> Locale.TRADITIONAL_CHINESE
            "en"    -> Locale.ENGLISH
            "ko"    -> Locale.KOREAN
            else    -> Locale.getDefault()
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
