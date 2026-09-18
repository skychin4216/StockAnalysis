package com.chin.stockanalysis.update

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.chin.stockanalysis.config.DataConfig
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * ## App 自动更新管理器
 *
 * 工作流程：
 *   1. 启动时（或手动）调用 [checkForUpdate] 拉取服务端更新清单
 *   2. 有新版 → [showUpdateDialog] 弹窗提示用户
 *   3. 用户确认 → [downloadAndInstall] 后台下载 APK（带进度）
 *   4. 下载完成 → [installApk] 触发系统安装（Android 8+ 需"安装未知应用"授权）
 *
 * ### 服务端部署（任选一种方式）
 * - 自建 Nginx / 对象存储（腾讯云 COS、阿里云 OSS）+ 静态托管
 * - GitHub / Gitee Releases（附件的直链）
 * - CloudBase 静态托管
 *
 * 服务器上放置两个文件（目录自定，例如 /update/）：
 * ```json
 * // latest.json —— 更新清单
 * {
 *   "versionCode": 2,                      // 必须大于客户端当前 versionCode
 *   "versionName": "1.1.0",
 *   "url": "https://your-domain/update/app-release.apk",
 *   "changelog": "1. 修复持仓合并丢失订单\n2. 持仓显示时分\n3. 交易记录显示时间",
 *   "force": false                         // true=强制更新（弹窗不可取消）
 * }
 * ```
 * 以及对应的 app-release.apk 安装包。
 *
 * 清单地址在 `assets/data/app_config.json → update.manifest_url` 配置。
 */
object AppUpdateManager {

    private const val TAG = "AppUpdateManager"
    private const val DOWNLOAD_DIR = "apk_update"
    private const val APK_FILE_NAME = "app-release.apk"

    /** 本地自定义更新地址（设置页可覆盖内置默认值，优先于 app_config.json） */
    private const val PREF_NAME = "app_update_prefs"
    private const val KEY_MANIFEST_URL = "manifest_url_override"

    /** 检查更新结果（供手动检查按钮区分"无更新 / 未配置 / 失败"） */
    sealed class CheckResult {
        object NotConfigured : CheckResult()
        object NoUpdate : CheckResult()
        data class HasUpdate(val info: UpdateInfo) : CheckResult()
        data class Failed(val message: String) : CheckResult()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 用户跳转"安装未知应用"设置页前暂存的待安装包 */
    @Volatile
    private var pendingInstallFile: File? = null

    /** 服务端更新清单 */
    data class UpdateInfo(
        val versionCode: Int = 0,
        val versionName: String = "",
        val url: String = "",
        val changelog: String = "",
        val force: Boolean = false
    )

    // ═══════════════════════ 检查更新 ═══════════════════════

    /**
     * 检查更新（后台线程执行）。兼容回调：有更新返回 [UpdateInfo]，无更新或失败返回 null。
     * 需要区分"无更新/未配置/失败"时用 [checkForUpdateDetailed]。
     */
    fun checkForUpdate(context: Context, onResult: (UpdateInfo?) -> Unit) {
        checkForUpdateDetailed(context) { result ->
            onResult((result as? CheckResult.HasUpdate)?.info)
        }
    }

    /**
     * 检查更新（细分结果，供设置页手动"检查更新"按钮使用）。
     * @return [CheckResult.NotConfigured] 未配置地址 / [CheckResult.NoUpdate] 已是最新 /
     * [CheckResult.HasUpdate] 有新版本 / [CheckResult.Failed] 检查失败
     */
    fun checkForUpdateDetailed(context: Context, onResult: (CheckResult) -> Unit) {
        val manifestUrl = getManifestUrl(context)
        if (manifestUrl.isBlank() || manifestUrl.contains("example.com")) {
            android.util.Log.w(TAG, "未配置更新服务器地址，跳过更新检查")
            onResult(CheckResult.NotConfigured)
            return
        }
        scope.launch {
            try {
                val request = Request.Builder().url(manifestUrl).get().build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        android.util.Log.w(TAG, "更新清单请求失败: HTTP ${resp.code}")
                        onResult(CheckResult.Failed("HTTP ${resp.code}")); return@use
                    }
                    val body = resp.body?.string() ?: run {
                        onResult(CheckResult.Failed("响应为空")); return@use
                    }
                    val raw = gson.fromJson(body, UpdateInfo::class.java)
                    // Gson 对 Kotlin 默认值不生效，需手动兜底 null 字段
                    val info = UpdateInfo(
                        versionCode = raw.versionCode,
                        versionName = raw.versionName ?: "",
                        url = raw.url ?: "",
                        changelog = raw.changelog ?: "",
                        force = raw.force
                    )
                    val currentCode = currentVersionCode(context)
                    if (info.versionCode > currentCode) onResult(CheckResult.HasUpdate(info))
                    else onResult(CheckResult.NoUpdate)
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "检查更新失败: ${e.message}")
                onResult(CheckResult.Failed(e.message ?: "网络异常"))
            }
        }
    }

    // ═══════════════════════ 更新地址管理 ═══════════════════════

    /**
     * 获取更新清单地址。优先级：
     *   1. 旧版设置页自定义地址（SharedPreferences 兼容，正常不再使用）
     *   2. `assets/data/app_config.json → update.manifest_url`（若已显式配置）
     *   3. 自动从 `cloud_sync` 配置推导：
     *      `https://{bucket}.cos.{region}.myqcloud.com/{prefix}/update/latest.json`
     * 这样用户无需在设置页手工填写 URL，只要把 latest.json 与 apk 上传到 COS 对应目录即可。
     */
    fun getManifestUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val override = prefs.getString(KEY_MANIFEST_URL, "")?.trim().orEmpty()
        if (override.isNotBlank()) return override
        // ① app_config.json 显式配置的更新清单
        val configured = DataConfig.get("update.manifest_url", "").trim()
        if (configured.isNotBlank() && !configured.contains("example.com")) return configured
        // ② 自动从 COS 配置推导
        val bucket = DataConfig.get("cloud_sync.bucket", "").trim()
        val region = DataConfig.get("cloud_sync.region", "ap-guangzhou").trim()
        if (bucket.isNotBlank()) {
            val prefix = DataConfig.get("cloud_sync.prefix", "stockanalysis/phone").trim().trim('/')
            return "https://$bucket.cos.$region.myqcloud.com/$prefix/update/latest.json"
        }
        return ""
    }

    /** 保存自定义更新清单地址（兼容旧逻辑；写入空串可清除自定义值、回退到自动推导）。 */
    fun setManifestUrl(context: Context, url: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_MANIFEST_URL, url.trim()).apply()
    }

    // ═══════════════════════ 更新弹窗 ═══════════════════════

    /** 展示更新提示弹窗（须在主线程调用） */
    fun showUpdateDialog(activity: Activity, info: UpdateInfo) {
        mainHandler.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            val builder = AlertDialog.Builder(activity)
                .setTitle("发现新版本 v${info.versionName}")
                .setMessage(
                    "当前版本：v${currentVersionName(activity)}\n\n" +
                        "更新内容：\n${info.changelog.ifBlank { "（暂无更新说明）" }}"
                )
                .setPositiveButton("立即更新") { _, _ -> downloadAndInstall(activity, info) }
            if (info.force) {
                builder.setCancelable(false)
            } else {
                builder.setNegativeButton("暂不更新", null)
            }
            builder.show()
        }
    }

    // ═══════════════════════ 下载 + 安装 ═══════════════════════

    /**
     * 下载 APK 并安装。下载期间显示不可取消的进度对话框；
     * 下载完成后自动请求安装（Android 8+ 先引导授权"安装未知应用"）。
     */
    fun downloadAndInstall(activity: Activity, info: UpdateInfo) {
        if (activity.isFinishing || activity.isDestroyed) return

        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            minHeight = 48
        }
        val percentTv = TextView(activity).apply {
            text = "0%"
            gravity = Gravity.CENTER
            textSize = 14f
            setPadding(0, 8, 0, 0)
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 24, 56, 8)
            addView(progress)
            addView(percentTv)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("正在下载 v${info.versionName}…")
            .setView(container)
            .setCancelable(false)
            .show()

        scope.launch {
            try {
                if (info.url.isBlank()) throw RuntimeException("更新清单缺少 APK 下载地址")
                val targetDir = File(activity.cacheDir, DOWNLOAD_DIR).apply { mkdirs() }
                val apkFile = File(targetDir, APK_FILE_NAME)
                val request = Request.Builder().url(info.url).get().build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw RuntimeException("下载失败: HTTP ${resp.code}")
                    val body = resp.body ?: throw RuntimeException("下载响应为空")
                    val total = body.contentLength()
                    var downloaded = 0L
                    body.byteStream().use { input ->
                        apkFile.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buf)
                                if (read <= 0) break
                                output.write(buf, 0, read)
                                downloaded += read
                                val pct = if (total > 0) (downloaded * 100 / total).toInt() else 0
                                mainHandler.post {
                                    if (!activity.isFinishing && !activity.isDestroyed) {
                                        progress.progress = pct
                                        percentTv.text = "$pct%"
                                    }
                                }
                            }
                        }
                    }
                }
                mainHandler.post {
                    if (!activity.isFinishing && !activity.isDestroyed) dialog.dismiss()
                    installApk(activity, apkFile)
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "下载失败: ${e.message}", e)
                mainHandler.post {
                    if (!activity.isFinishing && !activity.isDestroyed) dialog.dismiss()
                    Toast.makeText(activity, "下载失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ═══════════════════════ 安装 APK ═══════════════════════

    /** 触发安装。Android 8.0+ 需"安装未知应用"授权，未授权时跳转系统设置。 */
    fun installApk(context: Context, apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            pendingInstallFile = apkFile
            try {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(context, "请在系统设置中允许安装未知应用", Toast.LENGTH_LONG).show()
            }
            return
        }
        doInstall(context, apkFile)
    }

    /** 用户从"安装未知应用"设置页返回后调用（Activity.onResume 中），继续未完成的安装。 */
    fun retryPendingInstall(context: Context) {
        val file = pendingInstallFile ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            return
        }
        pendingInstallFile = null
        doInstall(context, file)
    }

    private fun doInstall(context: Context, apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "安装失败: ${e.message}", e)
            Toast.makeText(context, "安装失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ═══════════════════════ 工具 ═══════════════════════

    @Suppress("DEPRECATION")
    fun currentVersionCode(context: Context): Int =
        context.packageManager.getPackageInfo(context.packageName, 0).versionCode

    @Suppress("DEPRECATION")
    fun currentVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
}
