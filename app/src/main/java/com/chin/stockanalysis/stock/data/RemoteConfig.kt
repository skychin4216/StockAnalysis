package com.chin.stockanalysis.stock.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 远程连接配置（JSON 配置文件版）
 *
 * 相比 PcBridgeClient 的 SharedPreferences，这里：
 * - 把 PC 地址/端口/Token 存到独立 JSON 文件（filesDir/remote_config.json），便于预置与备份
 * - 自动探测本机(APK) WiFi IP，方便确认局域网网段
 * - 保存时同步写回 PcBridgeClient 的 SP，保证 PC候选等旧入口仍然可用
 */
object RemoteConfig {

    private const val FILE = "remote_config.json"
    const val DEFAULT_PORT = 8888

    data class Config(
        var host: String = "",      // 形如 "192.168.1.100:8888"
        var port: Int = DEFAULT_PORT,
        var token: String = "",     // PC 端 AutoQuant/data/remote_token.txt
        var localIp: String = "",   // 本机(APK)当前 IP
        var note: String = "",
        var updatedAt: String = "",
    )

    fun file(context: Context): File = File(context.filesDir, FILE)

    /** 读取配置：JSON 优先 → 旧 SP → APK 内置预置（app_config.json remote_control 区块） */
    fun load(context: Context): Config {
        val cfg = Config()
        try {
            val f = file(context)
            if (f.exists() && f.length() > 0) {
                val j = JSONObject(f.readText())
                cfg.host = j.optString("host", "")
                cfg.port = j.optInt("port", DEFAULT_PORT)
                cfg.token = j.optString("token", "")
                cfg.localIp = j.optString("local_ip", "")
                cfg.note = j.optString("note", "")
                cfg.updatedAt = j.optString("updated_at", "")
            }
        } catch (_: Exception) {}
        // 旧 SharedPreferences 迁移
        if (cfg.host.isBlank()) cfg.host = PcBridgeClient.loadHost(context)
        if (cfg.token.isBlank()) cfg.token = PcBridgeClient.loadToken(context)
        if (cfg.localIp.isBlank()) cfg.localIp = getLocalIp(context)
        // APK 内置预置兜底（assets/data/app_config.json → remote_control）
        val preset = loadPreset(context)
        if (cfg.host.isBlank()) cfg.host = preset.first
        if (cfg.port == DEFAULT_PORT && cfg.host.isBlank()) cfg.port = preset.second
        if (cfg.token.isBlank()) cfg.token = preset.third
        return cfg
    }

    /** 从 APK assets 读取预置 PC 地址/端口/Token（RemoteConfig 内置默认，安装即用） */
    fun loadPreset(context: Context): Triple<String, Int, String> {
        return try {
            val json = context.assets.open("data/app_config.json")
                .bufferedReader().use { it.readText() }
            val j = JSONObject(json)
            val rc = j.optJSONObject("remote_control") ?: JSONObject()
            val host = rc.optString("preset_host", "")
            val port = rc.optInt("preset_port", DEFAULT_PORT)
            val token = rc.optString("preset_token", "")
            Triple(host, port, token)
        } catch (_: Exception) {
            Triple("", DEFAULT_PORT, "")
        }
    }

    fun save(context: Context, cfg: Config) {
        cfg.updatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
        try {
            val j = JSONObject()
            j.put("host", cfg.host)
            j.put("port", cfg.port)
            j.put("token", cfg.token)
            j.put("local_ip", cfg.localIp)
            j.put("note", cfg.note)
            j.put("updated_at", cfg.updatedAt)
            file(context).writeText(j.toString(2))
        } catch (_: Exception) {}
        // 同步旧 SP 入口（PC候选直连等）
        if (cfg.host.isNotBlank()) PcBridgeClient.saveHost(context, cfg.host)
        if (cfg.token.isNotBlank()) PcBridgeClient.saveToken(context, cfg.token)
    }

    /** 组合完整 host:port */
    fun hostPort(cfg: Config): String {
        val h = cfg.host.trim()
        if (h.isEmpty()) return ""
        return if (':' in h) h else "$h:${cfg.port}"
    }

    /** 探测本机 IP：WiFi 优先，其次移动数据/以太网 */
    fun getLocalIp(context: Context): String {
        try {
            val cm = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                val wm = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as WifiManager
                val info = wm.connectionInfo
                val ip = info?.ipAddress ?: 0
                if (ip != 0 && ip.toUInt() != 0xFFFFFFFFu) return ipv4(ip)
            }
        } catch (_: Exception) {}
        try {
            val en = NetworkInterface.getNetworkInterfaces() ?: return ""
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                val name = intf.name.lowercase(Locale.ROOT)
                if (!name.startsWith("wlan") && !name.startsWith("eth") &&
                    !name.startsWith("rmnet") && !name.startsWith("usb")) continue
                val addr = intf.inetAddresses
                while (addr.hasMoreElements()) {
                    val a = addr.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress ?: ""
                }
            }
        } catch (_: Exception) {}
        return ""
    }

    private fun ipv4(ip: Int): String =
        "${ip and 0xff}.${(ip shr 8) and 0xff}.${(ip shr 16) and 0xff}.${(ip shr 24) and 0xff}"
}
