package com.chin.stockanalysis.config

import android.content.Context
import android.util.Base64
import android.util.Log
import com.chin.stockanalysis.BuildConfig
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ## 统一配置管理器
 *
 * 从 `assets/data/app_config.json` 加载数据源 URL、AI API 地址等配置。
 * 支持运行时通过 `override()` 覆盖（用于用户自定义配置）。
 *
 * ### Key 命名规则（按域名片段）
 * - `data_sources.sina.hq` → https://hq.sinajs.cn
 * - `data_sources.eastmoney.push2` → https://push2.eastmoney.com/api/qt
 * - `data_sources.search.duckduckgo` → https://html.duckduckgo.com/...
 * - `ai_providers.doubao.base_url` → https://ark.cn-beijing.volces.com/...
 *
 * ### 使用方式
 * ```kotlin
 * val url = DataConfig.get("data_sources.eastmoney.push2")
 * val url = DataConfig.getUrl("data_sources.sina.suggest", stockName)
 * DataConfig.override("search.tavily_api_key", "user-key")
 * ```
 */
object DataConfig {

    private const val TAG = "DataConfig"
    private const val CONFIG_FILE = "data/app_config.json"

    private val config = mutableMapOf<String, String>()
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        try {
            val json = context.assets.open(CONFIG_FILE).use { stream ->
                BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }
            }
            parseJson(JSONObject(json), "")
            loadSecrets(context)
            loaded = true
            Log.i(TAG, "配置加载完成，共 ${config.size} 项")
        } catch (e: Exception) {
            Log.e(TAG, "配置加载失败: ${e.message}")
        }
    }

    /**
     * 解密 `assets/data/secrets.enc`（AES-256-GCM，与 PC 端 secrets_util.py 格式一致）：
     * `base64( nonce(12B) || ciphertext-with-tag )`。
     * 主密钥来自本地 keystore.properties → BuildConfig.SECRET_MASTER_KEY（不入 git）。
     * 解密出的 `cloud_sync.secret_id/secret_key` 会覆盖 app_config.json 中的空占位。
     */
    private fun loadSecrets(context: Context) {
        val masterKey = BuildConfig.SECRET_MASTER_KEY
        if (masterKey.isBlank()) {
            Log.w(TAG, "未配置 SECRET_MASTER_KEY，跳过密钥解密")
            return
        }
        try {
            val b64 = context.assets.open("data/secrets.enc").use { stream ->
                BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }
            }.trim()
            if (b64.isEmpty()) {
                Log.w(TAG, "secrets.enc 为空，跳过密钥解密")
                return
            }
            val raw = Base64.decode(b64, Base64.NO_WRAP)
            val nonce = raw.copyOfRange(0, 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(masterKeyHexToBytes(masterKey), "AES"),
                GCMParameterSpec(128, nonce)
            )
            val plain = String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
            val obj = JSONObject(plain)
            parseJson(obj, "")
            Log.i(TAG, "密钥密文解密成功，已注入 ${obj.length()} 项云端密钥")
        } catch (e: Exception) {
            Log.w(TAG, "密钥解密失败（密文缺失或主密钥不匹配）: ${e.message}")
        }
    }

    private fun masterKeyHexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "主密钥必须为偶数长度的 hex" }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun parseJson(json: JSONObject, prefix: String) {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val fullKey = if (prefix.isEmpty()) key else "$prefix.$key"
            val value = json.opt(key)
            when {
                value is String -> config[fullKey] = value
                value is JSONObject -> parseJson(value, fullKey)
                value is Boolean -> config[fullKey] = value.toString()
                value is Int || value is Long || value is Double -> config[fullKey] = value.toString()
            }
        }
    }

    fun get(key: String, default: String = ""): String = config[key] ?: default

    fun getOrNull(key: String): String? = config[key]

    fun override(key: String, value: String) { config[key] = value }

    /** 替换 %s 占位符 */
    fun getUrl(key: String, vararg args: String): String {
        var url = get(key)
        args.forEach { url = url.replaceFirst("%s", it) }
        return url
    }

    // ═══════════════════════════════════════════════
    // 新浪 (sina)
    // ═══════════════════════════════════════════════
    val sinaHq get() = get("data_sources.sina.hq")
    val sinaSuggest get() = get("data_sources.sina.suggest")
    val sinaKline get() = get("data_sources.sina.kline")
    val sinaFinance get() = get("data_sources.sina.finance")

    // ═══════════════════════════════════════════════
    // 腾讯 (tencent)
    // ═══════════════════════════════════════════════
    val tencentGtimg get() = get("data_sources.tencent.gtimg")

    // ═══════════════════════════════════════════════
    // 东方财富 (eastmoney)
    // ═══════════════════════════════════════════════
    val eastmoneyPush2 get() = get("data_sources.eastmoney.push2")
    val eastmoneyPush2his get() = get("data_sources.eastmoney.push2his")
    val eastmoneySearchapi get() = get("data_sources.eastmoney.searchapi")
    val eastmoneySearchToken get() = get("data_sources.eastmoney.search_token")
    val eastmoneyDatacenter get() = get("data_sources.eastmoney.datacenter")
    val eastmoneyAnotice get() = get("data_sources.eastmoney.anotice")
    val eastmoneyQuote get() = get("data_sources.eastmoney.quote")
    val eastmoneyData get() = get("data_sources.eastmoney.data")
    val eastmoneyGuba get() = get("data_sources.eastmoney.guba")

    // ═══════════════════════════════════════════════
    // 搜索引擎 (search)
    // ═══════════════════════════════════════════════
    val searchDuckduckgo get() = get("data_sources.search.duckduckgo")
    val searchTavily get() = get("data_sources.search.tavily")
    val searchTavilyApiKey get() = get("data_sources.search.tavily_api_key")

    // ═══════════════════════════════════════════════
    // 新闻 (news)
    // ═══════════════════════════════════════════════
    val newsCninfo get() = get("data_sources.news.cninfo")
    val newsCls get() = get("data_sources.news.cls")
    val newsClsDetail get() = get("data_sources.news.cls_detail")
    val newsXueqiu get() = get("data_sources.news.xueqiu")
    val newsXueqiuDetail get() = get("data_sources.news.xueqiu_detail")

    // ═══════════════════════════════════════════════
    // 其他 (other)
    // ═══════════════════════════════════════════════
    val otherAkshare get() = get("data_sources.other.akshare")
    val otherJoinquants get() = get("data_sources.other.joinquants")

    // ═══════════════════════════════════════════════
    // 构建方法
    // ═══════════════════════════════════════════════

    /** 构建东方财富搜索 URL */
    fun eastmoneySearchUrl(input: String, type: String = "14", count: Int = 1): String =
        "${eastmoneySearchapi}?input=${URLEncoder.encode(input, "UTF-8")}&type=$type&token=$eastmoneySearchToken&count=$count"

    /** 构建 DuckDuckGo 搜索 URL */
    fun duckduckgoUrl(query: String): String =
        getUrl("data_sources.search.duckduckgo", URLEncoder.encode(query, "UTF-8"))

    /** 构建东方财富 push2 路径 URL */
    fun eastmoneyPush2Api(path: String): String = "${eastmoneyPush2}$path"

    /** 获取 AI Provider 配置 */
    fun getAiProvider(providerId: String): AiProviderConfig? {
        val prefix = "ai_providers.$providerId"
        val name = get("$prefix.name")
        val baseUrl = get("$prefix.base_url")
        val model = get("$prefix.default_model")
        return if (baseUrl.isNotEmpty()) AiProviderConfig(name, baseUrl, model) else null
    }

    data class AiProviderConfig(
        val name: String,
        val baseUrl: String,
        val defaultModel: String
    )
}
