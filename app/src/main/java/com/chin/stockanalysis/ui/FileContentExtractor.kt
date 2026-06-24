package com.chin.stockanalysis.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * ## 檔案內容提取器 v1.0
 *
 * 支援格式：
 * - txt/csv: 純文本直接讀取
 * - pdf: Android PdfRenderer (API 21+)
 * - docx: ZIP+XML 解析（DOCX 本質是 ZIP 壓縮的 XML）
 * - xlsx: ZIP+XML 解析（XLSX 本質是 ZIP 壓縮的 XML）
 * - 圖片: base64 編碼供 AI Vision 分析
 * - word (doc): 返回提示（需第三方庫）
 */
object FileContentExtractor {

    private const val TAG = "FileContentExtractor"

    data class ExtractedContent(
        val text: String,
        val mimeType: String = "",
        val base64Image: String = "",
        val isImage: Boolean = false
    )

    /**
     * 從 Uri 提取內容（自動根據副檔名分發）
     */
    suspend fun extract(context: Context, uri: Uri, fileName: String? = null): ExtractedContent =
        withContext(Dispatchers.IO) {
            try {
                val name = fileName ?: getFileNameFromUri(context, uri) ?: "unknown"
                val extension = name.substringAfterLast('.', "").lowercase()
                val mimeType = context.contentResolver.getType(uri) ?: ""

                Log.i(TAG, "提取檔案: $name (ext=$extension, mime=$mimeType)")

                when {
                    // 圖片格式 → base64
                    mimeType.startsWith("image/") -> extractImageAsBase64(context, uri)
                    // PDF
                    extension == "pdf" || mimeType == "application/pdf" ->
                        ExtractedContent(text = extractPdf(context, uri))
                    // DOCX
                    extension == "docx" || mimeType.contains("wordprocessingml") ->
                        ExtractedContent(text = extractDocx(context, uri))
                    // XLSX
                    extension == "xlsx" || mimeType.contains("spreadsheetml") ->
                        ExtractedContent(text = extractXlsx(context, uri))
                    // TXT / CSV
                    extension in listOf("txt", "csv", "json", "xml", "md", "log") ->
                        ExtractedContent(text = extractPlainText(context, uri))
                    // 純文本
                    mimeType.startsWith("text/") ->
                        ExtractedContent(text = extractPlainText(context, uri))
                    // 其他
                    else -> ExtractedContent(text = "[不支援的檔案格式: .$extension]")
                }
            } catch (e: Exception) {
                Log.e(TAG, "提取失敗: ${e.message}")
                ExtractedContent(text = "[無法讀取檔案: ${e.message}]")
            }
        }

    // ════════════════════════════════════════
    // 純文本 (txt/csv/json/xml/md/log)
    // ════════════════════════════════════════

    private fun extractPlainText(context: Context, uri: Uri): String {
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader().use { it.readText() }
        } ?: "[無法讀取文件]"
    }

    // ════════════════════════════════════════
    // PDF (使用 Android PdfRenderer)
    // ════════════════════════════════════════

    private fun extractPdf(context: Context, uri: Uri): String {
        val sb = StringBuilder()
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null

        try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: return "[無法讀取 PDF: 開啟失敗]"

            renderer = PdfRenderer(pfd)
            val pageCount = renderer.pageCount.coerceAtMost(50) // 最多讀取 50 頁

            sb.appendLine("[PDF 共 $pageCount 頁]")
            sb.appendLine()

            for (i in 0 until pageCount) {
                val page = renderer.openPage(i)
                try {
                    // 將頁面渲染為 Bitmap 後嘗試 OCR / 尺寸記錄
                    val width = page.width
                    val height = page.height
                    sb.appendLine("--- 第 ${i + 1} 頁 (${width}x${height}) ---")
                    // 標記：Android PdfRenderer 只能渲染圖，無法直接提取文字
                    // 後續可接入 OCR，目前提供頁面信息供 AI 參考
                } finally {
                    page.close()
                }
            }

            if (pageCount > 0) {
                sb.appendLine()
                sb.appendLine("[提示: PDF 文字提取需 AI OCR 能力，請確認 AI 模型支援圖片分析]")
            }
        } catch (e: Exception) {
            Log.w(TAG, "PDF 提取失敗: ${e.message}")
            return "[PDF 解析失敗: ${e.message}]"
        } finally {
            renderer?.close()
            try { pfd?.close() } catch (_: Exception) {}
        }

        return sb.toString()
    }

    // ════════════════════════════════════════
    // DOCX (ZIP + XML)
    // ════════════════════════════════════════

    private fun extractDocx(context: Context, uri: Uri): String {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                extractTextFromOOXML(stream, "word/document.xml")
            } ?: "[無法讀取 DOCX]"
        } catch (e: Exception) {
            Log.w(TAG, "DOCX 提取失敗: ${e.message}")
            "[DOCX 解析失敗: ${e.message}]"
        }
    }

    // ════════════════════════════════════════
    // XLSX (ZIP + XML)
    // ════════════════════════════════════════

    private fun extractXlsx(context: Context, uri: Uri): String {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                // 先提取 shared strings
                val sharedStrings = extractSharedStrings(stream)
                // 重新打開流提取工作表
                context.contentResolver.openInputStream(uri)?.use { stream2 ->
                    extractSheetData(stream2, sharedStrings)
                } ?: "[無法讀取 XLSX]"
            } ?: "[無法讀取 XLSX]"
        } catch (e: Exception) {
            Log.w(TAG, "XLSX 提取失敗: ${e.message}")
            "[XLSX 解析失敗: ${e.message}]"
        }
    }

    // ════════════════════════════════════════
    // 圖片 → Base64
    // ════════════════════════════════════════

    private fun extractImageAsBase64(context: Context, uri: Uri): ExtractedContent {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)
                    ?: return ExtractedContent(text = "[無法解碼圖片]")

                // 壓縮大圖（限制最大 2048px，避免 base64 過大）
                val processed = if (bitmap.width > 2048 || bitmap.height > 2048) {
                    val scale = minOf(2048f / bitmap.width, 2048f / bitmap.height)
                    val w = (bitmap.width * scale).toInt()
                    val h = (bitmap.height * scale).toInt()
                    Bitmap.createScaledBitmap(bitmap, w, h, true).also {
                        if (it != bitmap) bitmap.recycle()
                    }
                } else bitmap

                val baos = ByteArrayOutputStream()
                processed.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                baos.close()

                if (processed != bitmap) processed.recycle()

                ExtractedContent(
                    text = "[圖片: ${processed.width}x${processed.height}]",
                    base64Image = base64,
                    isImage = true,
                    mimeType = "image/jpeg"
                )
            } ?: ExtractedContent(text = "[無法讀取圖片]")
        } catch (e: Exception) {
            Log.w(TAG, "圖片編碼失敗: ${e.message}")
            ExtractedContent(text = "[圖片處理失敗: ${e.message}]")
        }
    }

    // ════════════════════════════════════════
    // OOXML (DOCX/XLSX) 共用方法
    // ════════════════════════════════════════

    /** 從 OOXML ZIP 中提取指定 XML 文件的純文字 */
    private fun extractTextFromOOXML(inputStream: InputStream, targetEntry: String): String {
        val sb = StringBuilder()
        val zis = ZipInputStream(inputStream)
        var entry: ZipEntry? = zis.nextEntry

        while (entry != null) {
            if (entry.name == targetEntry) {
                val xmlContent = zis.bufferedReader().readText()
                sb.append(extractTextFromXml(xmlContent))
                break
            }
            entry = zis.nextEntry
        }

        zis.close()
        val result = sb.toString().trim()
        if (result.isEmpty()) return "[DOCX 文件中未找到文字內容]"
        return result
    }

    /** 從 OOXML 中提取 shared strings 表 */
    private fun extractSharedStrings(inputStream: InputStream): Map<Int, String> {
        val map = mutableMapOf<Int, String>()
        val zis = ZipInputStream(inputStream)
        var entry: ZipEntry? = zis.nextEntry

        while (entry != null) {
            if (entry.name == "xl/sharedStrings.xml") {
                val xml = zis.bufferedReader().readText()
                // 解析 <si><t>text</t></si>
                val siRegex = Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL)
                val tRegex = Regex("<t[^>]*>(.*?)</t>")
                var index = 0
                for (match in siRegex.findAll(xml)) {
                    val siContent = match.groupValues[1]
                    val tMatch = tRegex.find(siContent)
                    if (tMatch != null) {
                        map[index] = tMatch.groupValues[1]
                    }
                    index++
                }
                break
            }
            entry = zis.nextEntry
        }
        zis.close()
        return map
    }

    /** 從 XLSX 工作表提取數據（CSV 格式） */
    private fun extractSheetData(inputStream: InputStream, sharedStrings: Map<Int, String>): String {
        val sb = StringBuilder()
        val zis = ZipInputStream(inputStream)
        var entry: ZipEntry? = zis.nextEntry
        var found = false

        while (entry != null) {
            if (entry.name.startsWith("xl/worksheets/sheet") && entry.name.endsWith(".xml")) {
                val xml = zis.bufferedReader().readText()
                val rows = parseSheetXml(xml, sharedStrings)
                sb.append(rows)
                found = true
                if (sb.length > 50_000) break // 最多 50000 字
            }
            entry = zis.nextEntry
        }
        zis.close()

        val result = sb.toString().trim()
        if (result.isEmpty()) return "[XLSX 文件中未找到數據]"
        if (!found) return "[XLSX 解析: 未找到工作表]"
        return result
    }

    private fun parseSheetXml(xml: String, sharedStrings: Map<Int, String>): String {
        val sb = StringBuilder()
        val rowRegex = Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL)
        val cellRegex = Regex("<c[^>]*>(.*?)</c>", RegexOption.DOT_MATCHES_ALL)
        val vRegex = Regex("<v>(.*?)</v>")
        val tRegex = Regex("t=\"s\"")

        var rowCount = 0
        for (rowMatch in rowRegex.findAll(xml)) {
            if (rowCount >= 1000) break // 最多 1000 行
            val rowContent = rowMatch.groupValues[1]
            val cells = mutableListOf<String>()

            for (cellMatch in cellRegex.findAll(rowContent)) {
                val cellContent = cellMatch.groupValues[1]
                val isSharedString = tRegex.containsMatchIn(cellMatch.value)
                val vMatch = vRegex.find(cellContent)

                val value = if (vMatch != null) {
                    if (isSharedString) {
                        sharedStrings[vMatch.groupValues[1].toIntOrNull() ?: -1] ?: ""
                    } else {
                        vMatch.groupValues[1]
                    }
                } else ""
                cells.add(value)
            }

            if (cells.isNotEmpty()) {
                sb.appendLine(cells.joinToString("\t"))
                rowCount++
            }
        }
        return sb.toString()
    }

    /** 從 XML 中提取純文字（去除標籤） */
    private fun extractTextFromXml(xml: String): String {
        val sb = StringBuilder()

        // 提取 <w:t> 內容（Word 文字節點）
        val wtRegex = Regex("<w:t[^>]*>(.*?)</w:t>")
        var lastWasSpace = false

        for (match in wtRegex.findAll(xml)) {
            val text = match.groupValues[1]
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
            sb.append(text)
            lastWasSpace = false
        }

        // 提取 <w:p> 段落邊界，插入換行
        val result = sb.toString()
        if (result.isEmpty()) return ""

        // 在段落標記處插入換行（簡單處理）
        val paraRegex = Regex("<w:p[ >]")
        val paraMatches = paraRegex.findAll(xml).toList()
        if (paraMatches.size > 1) {
            // 已通過 w:t 提取，保留原始順序
            // w:t 之間的換行通過 w:p 自然分隔
            return result
        }

        return result
    }

    // ════════════════════════════════════════
    // 輔助方法
    // ════════════════════════════════════════

    private fun getFileNameFromUri(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) result = cursor.getString(idx)
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) result = result?.substring(cut + 1)
        }
        return result
    }
}