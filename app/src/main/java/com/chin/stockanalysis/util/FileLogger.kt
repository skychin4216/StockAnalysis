package com.chin.stockanalysis.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.BufferedWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ## logcat 自动落盘
 *
 * 进程启动后后台线程持续执行 `logcat -v time --pid=<pid>`，按天写入
 * `filesDir/logs/log_yyyyMMdd.txt`。
 *
 * 特性：
 * - 每次进程启动时删除上一次运行留下的全部日志，日志目录只保留本次运行记录
 * - 当日日志由 [readTodayLog] 读取，供「上传今日数据」时随数据包同步到 COS
 * - 所有方法线程安全，多次调用 [start] 幂等
 */
object FileLogger {

    private const val TAG = "FileLogger"
    private const val LOG_DIR = "logs"
    /** 上传日志上限（超过则只读尾部，防止整文件 readText 导致 OOM）。 */
    private const val MAX_UPLOAD_BYTES = 3 * 1024 * 1024

    private val started = AtomicBoolean(false)
    @Volatile private var logDir: File? = null

    /** 启动后台日志捕获（幂等，可在 MainActivity / Application 调用）。 */
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val dir = File(context.filesDir, LOG_DIR).apply { mkdirs() }
        logDir = dir
        cleanupOldLogs(dir)
        Thread({
            captureLoop(dir)
        }, "file-logger").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "FileLogger started -> ${dir.absolutePath}")
    }

    /**
     * 读取当日日志文本（无当日文件时返回空串）。
     *
     * 安全保护：日志文件可能高达数百 MB（logcat 全量落盘），直接 readText 会整文件
     * 读入内存导致 OOM（曾在上传 COS 时 FATAL）。因此超过 [MAX_UPLOAD_BYTES] 时
     * 仅从文件尾部流式读取，并丢弃可能被截断的半行，保证上传包小而稳。
     */
    fun readTodayLog(): String {
        val dir = logDir ?: return ""
        val f = todayFile(dir)
        return try {
            if (!f.exists()) ""
            else if (f.length() <= MAX_UPLOAD_BYTES) f.readText()
            else {
                val sb = StringBuilder(MAX_UPLOAD_BYTES + 64)
                sb.append("…[日志过大 ${f.length() / (1024 * 1024)}MB，已截断，仅保留尾部]\n")
                val skip = f.length() - MAX_UPLOAD_BYTES
                f.inputStream().use { ins ->
                    ins.skip(skip)
                    // 丢弃可能被截断的半个多字节字符，定位到下一行行首再开始读
                    var b: Int
                    while (ins.read().also { b = it } != -1 && b != '\n'.code) { }
                    val tail = ins.readBytes()
                    sb.append(String(tail, Charsets.UTF_8))
                }
                sb.toString()
            }
        } catch (e: Exception) {
            Log.w(TAG, "readTodayLog: ${e.message}")
            ""
        }
    }

    /** 追加一行到当日日志（供广播命令等非 logcat 来源写入）。 */
    @Synchronized
    fun append(line: String) {
        val dir = logDir ?: return
        try {
            val f = File(dir, "log_${todayStamp()}.txt")
            f.appendText(line + "\n", Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "append: ${e.message}")
        }
    }

    private fun captureLoop(dir: File) {
        var writer: BufferedWriter? = null
        try {
            val pid = android.os.Process.myPid()
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-v", "time", "--pid=$pid"))
            val reader = proc.inputStream.bufferedReader(Charsets.UTF_8)
            var currentDate = todayStamp()
            writer = openWriter(dir, currentDate)
            var lineCount = 0
            while (true) {
                val line = reader.readLine() ?: break
                val date = todayStamp()
                if (date != currentDate) {
                    writer?.flush()
                    writer?.close()
                    currentDate = date
                    writer = openWriter(dir, currentDate)
                    lineCount = 0
                }
                writer?.write(line)
                writer?.write("\n")
                lineCount++
                // 每 200 行刷盘一次，避免进程被杀时丢日志
                if (lineCount % 200 == 0) writer?.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "captureLoop stopped: ${e.message}")
        } finally {
            try { writer?.flush(); writer?.close() } catch (_: Exception) {}
        }
    }

    private fun openWriter(dir: File, date: String): BufferedWriter =
        BufferedWriter(FileWriter(File(dir, "log_$date.txt"), true), 64 * 1024)

    private fun todayFile(dir: File): File = File(dir, "log_${todayStamp()}.txt")

    /** 启动时清理上一次运行留下的日志（每次启动日志目录从零开始）。 */
    private fun cleanupOldLogs(dir: File) {
        try {
            dir.listFiles()?.forEach { f ->
                if (f.isFile) f.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "cleanupOldLogs: ${e.message}")
        }
    }

    private fun todayStamp(): String =
        SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date())
}
