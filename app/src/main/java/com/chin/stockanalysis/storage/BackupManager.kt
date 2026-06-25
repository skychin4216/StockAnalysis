package com.chin.stockanalysis.storage

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * ## SAF 数据库备份/恢复管理器
 *
 * 解决卸载重装后 Room 数据库丢失的问题：
 * - 首次使用 → 引导用户选择备份文件夹（/Documents/StockAnalysis）
 * - App 启动 → 检测内部 DB 为空时自动从 SAF 恢复
 * - 手动备份 → 数据中心菜单调用 backupNow()
 * - 自动备份 → App 进入后台时触发
 */
object BackupManager {

    private const val TAG = "BackupManager"
    private const val PREFS_NAME = "backup_prefs"
    private const val KEY_BACKUP_URI = "backup_uri"
    private const val KEY_LAST_BACKUP = "last_backup_time"
    private const val BACKUP_FOLDER_NAME = "StockAnalysis"
    private const val DB_NAME = "stock_analysis.db"

    private var backupUri: Uri? = null
    private var prefs: SharedPreferences? = null

    /**
     * 初始化：检查是否已选择备份文件夹，启动后检查是否需要恢复
     */
    fun initialize(activity: android.app.Activity, onNeedSetup: () -> Unit, onRestored: () -> Unit) {
        prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uriString = prefs?.getString(KEY_BACKUP_URI, null)
        backupUri = if (uriString != null) Uri.parse(uriString) else null

        if (backupUri == null) {
            // 首次使用，引导选择备份文件夹
            Log.i(TAG, "🔔 首次使用，需要选择备份文件夹")
            onNeedSetup()
        } else {
            // 检查是否需要恢复
            val internalDb = getInternalDbFile(activity)
            if (!internalDb.exists() || internalDb.length() < 1024) {
                Log.i(TAG, "🔔 内部DB不存在或为空，尝试从备份恢复...")
                val success = restoreFromBackup(activity)
                if (success) {
                    Log.i(TAG, "✅ 数据恢复成功")
                    onRestored()
                } else {
                    Log.w(TAG, "⚠️ 备份中没有数据，需重新导入")
                }
            } else {
                Log.i(TAG, "✅ 内部DB正常，跳过恢复 (${internalDb.length()/1024}KB)")
            }
        }
    }

    /**
     * 打开系统文件夹选择器，让用户选择备份目录
     */
    fun openFolderPicker(activity: android.app.Activity, requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                     Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            // 尝试定位到 /Documents 目录
            putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:Documents"
                ))
        }
        activity.startActivityForResult(intent, requestCode)
    }

    /**
     * 处理文件夹选择结果
     */
    fun onFolderSelected(context: Context, uri: Uri) {
        // 永久保存权限
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        backupUri = uri
        prefs?.edit()?.putString(KEY_BACKUP_URI, uri.toString())?.apply()
        Log.i(TAG, "✅ 备份文件夹已设置: $uri")
    }

    /**
     * 立即备份数据库到 SAF 文件夹
     */
    fun backupNow(context: Context): Boolean {
        if (backupUri == null) {
            Log.w(TAG, "⚠️ 未设置备份文件夹")
            return false
        }

        return try {
            val internalDb = getInternalDbFile(context)
            if (!internalDb.exists()) {
                Log.w(TAG, "⚠️ 内部DB不存在，跳过备份")
                return false
            }

            // 确保 SAF 中 StockAnalysis 文件夹存在
            val rootDoc = DocumentFile.fromTreeUri(context, backupUri!!) ?: return false
            val backupDir = rootDoc.findFile(BACKUP_FOLDER_NAME)
                ?: rootDoc.createDirectory(BACKUP_FOLDER_NAME)
                ?: return false

            // 获取 Room 数据库所有相关文件
            val dbFiles = listOf(
                internalDb,                          // stock_analysis.db
                File(internalDb.parent, "$DB_NAME-wal"),   // WAL 日志
                File(internalDb.parent, "$DB_NAME-shm")    // 共享内存
            )

            var copied = 0
            for (file in dbFiles) {
                if (!file.exists()) continue
                // 删除旧备份
                val oldBackup = backupDir.findFile(file.name)
                oldBackup?.delete()
                // 写入新备份
                val newFile = backupDir.createFile("application/octet-stream", file.name)
                    ?: continue
                context.contentResolver.openOutputStream(newFile.uri)?.use { output ->
                    FileInputStream(file).use { input ->
                        input.copyTo(output)
                    }
                }
                copied++
            }

            prefs?.edit()?.putString(KEY_LAST_BACKUP,
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            )?.apply()

            Log.i(TAG, "✅ 备份完成: ${copied}个文件 (${internalDb.length()/1024}KB)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ 备份失败: ${e.message}")
            false
        }
    }

    /**
     * 从 SAF 备份恢复数据库到内部存储
     */
    private fun restoreFromBackup(context: Context): Boolean {
        if (backupUri == null) return false

        return try {
            val rootDoc = DocumentFile.fromTreeUri(context, backupUri!!) ?: return false
            val backupDir = rootDoc.findFile(BACKUP_FOLDER_NAME) ?: return false

            val dbFile = backupDir.findFile(DB_NAME) ?: return false
            val internalDb = getInternalDbFile(context)

            // 复制主 DB 文件
            internalDb.parentFile?.mkdirs()
            context.contentResolver.openInputStream(dbFile.uri)?.use { input ->
                FileOutputStream(internalDb).use { output ->
                    input.copyTo(output)
                }
            }

            // 恢复 WAL 和 SHM 文件（如果存在）
            listOf("$DB_NAME-wal", "$DB_NAME-shm").forEach { name ->
                val backupFile = backupDir.findFile(name) ?: return@forEach
                val targetFile = File(internalDb.parent, name)
                context.contentResolver.openInputStream(backupFile.uri)?.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            // 删除旧的 Room 实例缓存，强制重新打开
            com.chin.stockanalysis.stock.database.StockDatabase.clearInstance()

            Log.i(TAG, "✅ 恢复完成: ${internalDb.length()/1024}KB")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ 恢复失败: ${e.message}")
            false
        }
    }

    /**
     * 获取内部存储的数据库文件路径
     */
    private fun getInternalDbFile(context: Context): File {
        return context.getDatabasePath(DB_NAME)
    }

    /**
     * 获取上次备份时间
     */
    fun getLastBackupTime(): String {
        return prefs?.getString(KEY_LAST_BACKUP, "从未备份") ?: "从未备份"
    }

    /**
     * 是否已设置备份文件夹
     */
    fun isSetup(): Boolean = backupUri != null
}