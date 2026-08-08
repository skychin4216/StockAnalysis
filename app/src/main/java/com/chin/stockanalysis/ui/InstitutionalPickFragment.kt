package com.chin.stockanalysis.ui

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.ai.StockEntityExtractor
import com.chin.stockanalysis.stock.database.InstitutionalPickEntity
import com.chin.stockanalysis.stock.database.StockDatabase
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/**
 * ## 機構推薦股票 Fragment
 *
 * 功能：
 * - 自定義分組管理（中金、中信、高盛等）
 * - 每個分組下可添加推薦股票
 * - 多源 OCR：圖片/PDF/拍照/剪貼板/批量圖片/掃描文件
 * - AI 自動搜索：OCR 後自動解析股票名稱 → 代碼
 * - 支持微信聊天截圖、券商研究報告 PDF 等場景
 */
class InstitutionalPickFragment : Fragment() {

    private lateinit var rootLayout: LinearLayout
    private lateinit var groupChipRow: LinearLayout
    private lateinit var stockListContainer: LinearLayout
    private lateinit var statusTv: TextView
    private lateinit var addGroupBtn: TextView

    /** 當前選中的分組 */
    private var currentGroup: String = ""
    /** 所有分組名稱 */
    private var allGroups: List<String> = emptyList()
    /** 當前分組的股票列表 */
    private var currentPicks: List<InstitutionalPickEntity> = emptyList()
    /** 分組 chip views */
    private val groupChipViews = mutableMapOf<String, TextView>()

    /** 拍照用：臨時文件 URI */
    private var cameraPhotoUri: Uri? = null

    /** 批量圖片 OCR 待處理隊列 */
    private val batchImageUris = mutableListOf<Uri>()
    private var batchProcessedCount = 0
    private val batchResults = mutableListOf<Pair<String, String>>()

    companion object {
        private val DATE_FMT = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private const val REQUEST_IMAGE_PICK = 1001
        private const val REQUEST_CAMERA = 1002
        private const val REQUEST_PDF_PICK = 1003
        private const val REQUEST_FILE_PICK = 1004
        private const val REQUEST_BATCH_IMAGE = 1005
        private const val MAX_PDF_PAGES = 10
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val sv = ScrollView(requireContext()).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F6FA"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        sv.addView(rootLayout)
        buildUI()
        loadGroups()
        checkClipboardOnStart()
        return sv
    }

    override fun onResume() {
        super.onResume()
        loadGroups()
    }

    // ═══════════════════════════════════════
    //  UI 構建
    // ═══════════════════════════════════════

    private fun buildUI() {
        // ── 標題行 ──
        val titleRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 12, 16, 8)
            setBackgroundColor(Color.WHITE)
        }
        titleRow.addView(TextView(requireContext()).apply {
            text = "機構推薦"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#333333"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addGroupBtn = TextView(requireContext()).apply {
            text = "+ 分組"
            textSize = 13f
            setTextColor(Color.parseColor("#1565C0"))
            setTypeface(null, Typeface.BOLD)
            setPadding(16, 8, 16, 8)
            setOnClickListener { showAddGroupDialog() }
        }
        titleRow.addView(addGroupBtn)
        rootLayout.addView(titleRow)

        // ── 分組 Chip 行 ──
        val chipScroll = HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.WHITE)
        }
        groupChipRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 4, 12, 8)
        }
        chipScroll.addView(groupChipRow)
        rootLayout.addView(chipScroll)

        // ── 操作按鈕行 1（圖片 / PDF / 拍照 / 掃描文件）──
        val actionRow1 = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(12, 8, 12, 4)
            setBackgroundColor(Color.WHITE)
        }
        actionRow1.addView(createActionBtn("圖片識別") { pickImage() })
        actionRow1.addView(createActionBtn("PDF識別") { pickPdf() })
        actionRow1.addView(createActionBtn("拍照識別") { takePhoto() })
        actionRow1.addView(createActionBtn("掃描文件") { pickFile() })
        rootLayout.addView(actionRow1)

        // ── 操作按鈕行 2（粘貼 / 剪貼板 / 批量 / 手動）──
        val actionRow2 = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(12, 4, 12, 8)
            setBackgroundColor(Color.WHITE)
        }
        actionRow2.addView(createActionBtn("粘貼文字") { showPasteDialog() })
        actionRow2.addView(createActionBtn("剪貼板") { readClipboard() })
        actionRow2.addView(createActionBtn("批量圖片") { pickBatchImages() })
        actionRow2.addView(createActionBtn("手動添加") { showManualAddDialog() })
        rootLayout.addView(actionRow2)

        // ── 狀態行 ──
        statusTv = TextView(requireContext()).apply {
            text = ""
            textSize = 12f
            setTextColor(Color.parseColor("#999999"))
            setPadding(16, 6, 16, 6)
        }
        rootLayout.addView(statusTv)

        // ── 股票列表容器 ──
        stockListContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 80)
        }
        rootLayout.addView(stockListContainer)
    }

    private fun createActionBtn(text: String, onClick: () -> Unit): TextView {
        val dp = resources.displayMetrics.density
        return TextView(requireContext()).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.parseColor("#1565C0"))
            setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#E3F2FD"))
                cornerRadius = 4f * dp
            }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins((3 * dp).toInt(), 0, (3 * dp).toInt(), 0)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    // ═══════════════════════════════════════
    //  分組管理
    // ═══════════════════════════════════════

    private fun loadGroups() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                allGroups = db.institutionalPickDao().getAllGroups()
                withContext(Dispatchers.Main) {
                    renderGroupChips()
                    if (currentGroup.isNotEmpty()) {
                        loadPicksForGroup(currentGroup)
                    } else if (allGroups.isNotEmpty()) {
                        selectGroup(allGroups.first())
                    } else {
                        renderEmptyState()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTv.text = "加載失敗: ${e.message?.take(30)}"
                }
            }
        }
    }

    private fun renderGroupChips() {
        groupChipRow.removeAllViews()
        groupChipViews.clear()
        for (group in allGroups) {
            val chip = createGroupChip(group, group == currentGroup)
            groupChipViews[group] = chip
            groupChipRow.addView(chip)
        }
        if (allGroups.isEmpty()) {
            groupChipRow.addView(TextView(requireContext()).apply {
                text = "點擊「+ 分組」添加機構"
                textSize = 12f
                setTextColor(Color.parseColor("#BBBBBB"))
                setPadding(8, 8, 8, 8)
            })
        }
    }

    private fun createGroupChip(name: String, selected: Boolean): TextView {
        val dp = resources.displayMetrics.density
        return TextView(requireContext()).apply {
            text = name
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins((3 * dp).toInt(), 0, (3 * dp).toInt(), 0)
            layoutParams = lp
            updateChipStyle(this, selected)
            setOnClickListener { selectGroup(name) }
            setOnLongClickListener { showGroupOptions(name); true }
        }
    }

    private fun updateChipStyle(chip: TextView, selected: Boolean) {
        val dp = resources.displayMetrics.density
        if (selected) {
            chip.setTextColor(Color.WHITE)
            chip.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#1565C0"))
                cornerRadius = 14f * dp
            }
            chip.setTypeface(null, Typeface.BOLD)
        } else {
            chip.setTextColor(Color.parseColor("#666666"))
            chip.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#F0F0F0"))
                cornerRadius = 14f * dp
            }
            chip.setTypeface(null, Typeface.NORMAL)
        }
    }

    private fun selectGroup(name: String) {
        currentGroup = name
        for ((g, chip) in groupChipViews) updateChipStyle(chip, g == name)
        loadPicksForGroup(name)
    }

    private fun showGroupOptions(groupName: String) {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle(groupName)
            .setItems(arrayOf("重命名", "刪除分組")) { _, which ->
                when (which) {
                    0 -> showRenameGroupDialog(groupName)
                    1 -> confirmDeleteGroup(groupName)
                }
            }.show()
    }

    private fun showAddGroupDialog() {
        if (!isAdded) return
        val input = EditText(requireContext()).apply {
            hint = "機構名稱（如：中金、中信、高盛）"
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("新增機構分組")
            .setView(input)
            .setPositiveButton("確定") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) addGroup(name)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRenameGroupDialog(oldName: String) {
        if (!isAdded) return
        val input = EditText(requireContext()).apply {
            setText(oldName)
            setSelection(oldName.length)
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("重命名分組")
            .setView(input)
            .setPositiveButton("確定") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != oldName) renameGroup(oldName, newName)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun addGroup(name: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val existing = db.institutionalPickDao().countByGroup(name)
                if (existing == 0) {
                    db.institutionalPickDao().insert(
                        InstitutionalPickEntity(
                            institutionName = name,
                            stockCode = "__GROUP_PLACEHOLDER__",
                            stockName = "",
                            recommendDate = LocalDate.now().format(DATE_FMT)
                        )
                    )
                }
                withContext(Dispatchers.Main) { loadGroups(); selectGroup(name) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { statusTv.text = "添加失敗: ${e.message?.take(30)}" }
            }
        }
    }

    private fun renameGroup(oldName: String, newName: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val picks = db.institutionalPickDao().getByGroup(oldName)
                for (p in picks) {
                    db.institutionalPickDao().deleteById(p.id)
                    db.institutionalPickDao().insert(p.copy(institutionName = newName))
                }
                withContext(Dispatchers.Main) {
                    if (currentGroup == oldName) currentGroup = newName
                    loadGroups()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { statusTv.text = "重命名失敗: ${e.message?.take(30)}" }
            }
        }
    }

    private fun confirmDeleteGroup(groupName: String) {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle("刪除分組")
            .setMessage("確定刪除「$groupName」及其所有推薦記錄？")
            .setPositiveButton("刪除") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val db = StockDatabase.getInstance(requireContext())
                        db.institutionalPickDao().deleteByGroup(groupName)
                        withContext(Dispatchers.Main) {
                            if (currentGroup == groupName) currentGroup = ""
                            loadGroups()
                        }
                    } catch (_: Exception) {}
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ═══════════════════════════════════════
    //  股票列表
    // ═══════════════════════════════════════

    private fun loadPicksForGroup(group: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val picks = db.institutionalPickDao().getByGroup(group)
                    .filter { it.stockCode != "__GROUP_PLACEHOLDER__" }
                currentPicks = picks
                withContext(Dispatchers.Main) { renderStockList() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { statusTv.text = "加載失敗: ${e.message?.take(30)}" }
            }
        }
    }

    private fun renderStockList() {
        stockListContainer.removeAllViews()
        if (currentGroup.isEmpty()) { renderEmptyState(); return }
        if (currentPicks.isEmpty()) {
            stockListContainer.addView(TextView(requireContext()).apply {
                text = "暫無推薦記錄\n點擊下方按鈕添加"
                textSize = 14f; setTextColor(Color.parseColor("#BBBBBB"))
                gravity = Gravity.CENTER; setPadding(0, 64, 0, 64)
            })
            statusTv.text = "${currentGroup}：0 只"
            return
        }
        statusTv.text = "${currentGroup}：${currentPicks.size} 只"

        // 表頭
        val headerRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#FAFAFA"))
            setPadding(16, 8, 16, 8)
        }
        headerRow.addView(headerCell("股票", 2.5f, Gravity.START))
        headerRow.addView(headerCell("推薦日", 1.2f, Gravity.CENTER))
        headerRow.addView(headerCell("目標價", 1.0f, Gravity.END))
        headerRow.addView(headerCell("來源", 0.8f, Gravity.CENTER))
        stockListContainer.addView(headerRow)

        for (pick in currentPicks) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16, 10, 16, 10)
                setBackgroundColor(Color.WHITE)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, 1, 0, 1)
                layoutParams = lp
            }
            val nameCol = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2.5f)
            }
            nameCol.addView(TextView(requireContext()).apply {
                text = pick.stockName.ifEmpty { pick.stockCode }; textSize = 14f
                setTextColor(Color.parseColor("#333333"))
            })
            nameCol.addView(TextView(requireContext()).apply {
                text = pick.stockCode; textSize = 11f
                setTextColor(Color.parseColor("#999999"))
            })
            row.addView(nameCol)
            row.addView(TextView(requireContext()).apply {
                text = pick.recommendDate.takeLast(5); textSize = 12f
                setTextColor(Color.parseColor("#666666")); gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
            })
            row.addView(TextView(requireContext()).apply {
                text = if (pick.targetPrice > 0) "%.2f".format(pick.targetPrice) else "-"; textSize = 12f
                setTextColor(if (pick.targetPrice > 0) Color.parseColor("#E65100") else Color.parseColor("#CCCCCC"))
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            })
            row.addView(TextView(requireContext()).apply {
                text = sourceLabel(pick.sourceType); textSize = 11f
                setTextColor(Color.parseColor("#888888")); gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f)
            })
            row.setOnLongClickListener { confirmDeletePick(pick); true }
            if (pick.reason.isNotEmpty()) row.setOnClickListener { showPickDetail(pick) }
            stockListContainer.addView(row)
        }
    }

    private fun headerCell(text: String, weight: Float, gravity: Int): TextView {
        return TextView(requireContext()).apply {
            this.text = text; textSize = 11f; setTextColor(Color.parseColor("#888888"))
            setTypeface(null, Typeface.BOLD); this.gravity = gravity
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
        }
    }

    private fun sourceLabel(source: String): String = when (source) {
        "ocr" -> "圖片"
        "pdf" -> "PDF"
        "paste" -> "粘貼"
        "camera" -> "拍照"
        "clipboard" -> "剪貼板"
        "batch" -> "批量"
        "file" -> "文件"
        "share" -> "分享"
        else -> "手動"
    }

    private fun renderEmptyState() {
        stockListContainer.removeAllViews()
        stockListContainer.addView(TextView(requireContext()).apply {
            text = "暫無機構分組\n點擊右上角「+ 分組」開始"
            textSize = 14f; setTextColor(Color.parseColor("#BBBBBB"))
            gravity = Gravity.CENTER; setPadding(0, 80, 0, 80)
        })
        statusTv.text = ""
    }

    private fun confirmDeletePick(pick: InstitutionalPickEntity) {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle("移除推薦")
            .setMessage("移除 ${pick.stockName}(${pick.stockCode})？")
            .setPositiveButton("移除") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    StockDatabase.getInstance(requireContext()).institutionalPickDao().deleteById(pick.id)
                    withContext(Dispatchers.Main) { loadPicksForGroup(currentGroup) }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showPickDetail(pick: InstitutionalPickEntity) {
        val msg = buildString {
            appendLine("股票：${pick.stockName}(${pick.stockCode})")
            appendLine("推薦日：${pick.recommendDate}")
            if (pick.targetPrice > 0) appendLine("目標價：${pick.targetPrice}")
            if (pick.subGroup.isNotEmpty()) appendLine("子分組：${pick.subGroup}")
            appendLine("來源：${sourceLabel(pick.sourceType)}")
            if (pick.reason.isNotEmpty()) { appendLine(); appendLine("理由："); appendLine(pick.reason) }
            if (pick.notes.isNotEmpty()) { appendLine(); appendLine("備註：${pick.notes}") }
        }
        AlertDialog.Builder(requireContext()).setTitle("${pick.stockName} 推薦詳情")
            .setMessage(msg).setPositiveButton("確定", null).show()
    }

    // ═══════════════════════════════════════
    //  多源輸入：圖片 / PDF / 拍照 / 掃描 / 剪貼板 / 批量
    // ═══════════════════════════════════════

    /** 1. 圖片識別 — 從相冊選擇（截圖、微信圖片、任何圖片） */
    private fun pickImage() {
        if (!ensureGroup()) return
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try { startActivityForResult(intent, REQUEST_IMAGE_PICK) }
        catch (e: Exception) { statusTv.text = "無法打開圖片選擇器" }
    }

    /** 2. PDF 識別 — 選擇 PDF 文件，逐頁渲染 OCR */
    private fun pickPdf() {
        if (!ensureGroup()) return
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "application/pdf"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try { startActivityForResult(intent, REQUEST_PDF_PICK) }
        catch (e: Exception) { statusTv.text = "無法打開文件選擇器" }
    }

    /** 3. 拍照識別 — 使用 FileProvider 保存全尺寸照片 */
    private fun takePhoto() {
        if (!ensureGroup()) return
        val photoFile = File(requireContext().cacheDir, "ocr_photo_${System.currentTimeMillis()}.jpg")
        cameraPhotoUri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            photoFile
        )
        val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(android.provider.MediaStore.EXTRA_OUTPUT, cameraPhotoUri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        try { startActivityForResult(intent, REQUEST_CAMERA) }
        catch (e: Exception) { statusTv.text = "無法打開相機" }
    }

    /** 4. 掃描文件 — 支持圖片+PDF 混合選擇 */
    private fun pickFile() {
        if (!ensureGroup()) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/pdf"))
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try { startActivityForResult(intent, REQUEST_FILE_PICK) }
        catch (e: Exception) { statusTv.text = "無法打開文件選擇器" }
    }

    /** 5. 粘貼文字 */
    private fun showPasteDialog() {
        if (!ensureGroup()) return
        val input = EditText(requireContext()).apply {
            hint = "粘貼機構推薦文字\n（支持微信聊天、研報文字、股票名稱/代碼）"
            minLines = 4; gravity = Gravity.TOP; setPadding(32, 24, 32, 24)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("粘貼推薦文字")
            .setView(input)
            .setPositiveButton("識別") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) processPastedText(text)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 6. 剪貼板 — 讀取剪貼板內容並識別 */
    private fun readClipboard() {
        if (!ensureGroup()) return
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) {
            statusTv.text = "剪貼板為空"
            return
        }
        val text = clip.getItemAt(0).text?.toString()?.trim()
        if (text.isNullOrEmpty()) {
            statusTv.text = "剪貼板無文字內容"
            return
        }
        statusTv.text = "剪貼板：${text.take(30)}..."
        processPastedText(text)
    }

    /** 進入頁面時自動檢查剪貼板是否有股票相關內容 */
    private fun checkClipboardOnStart() {
        try {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0).text?.toString()?.trim() ?: return
            if (text.length < 4) return
            // 快速檢查是否包含股票代碼或常見股票名稱
            val hasCode = Regex("""\d{6}""").containsMatchIn(text)
            val hasStockHint = text.contains("推薦") || text.contains("目標") || text.contains("買入")
                || text.contains("評級") || text.contains("關注")
            if (!hasCode && !hasStockHint) return

            // 有股票相關內容 → 顯示提示
            val preview = text.take(60)
            statusTv.text = "剪貼板可能有股票信息"
            viewLifecycleOwner.lifecycleScope.launch {
                kotlinx.coroutines.delay(500)
                if (!isAdded) return@launch
                AlertDialog.Builder(requireContext())
                    .setTitle("檢測到剪貼板內容")
                    .setMessage("剪貼板似乎包含股票信息：\n\n「$preview...」\n\n是否識別並添加？")
                    .setPositiveButton("識別") { _, _ ->
                        if (currentGroup.isEmpty() && allGroups.isNotEmpty()) {
                            selectGroup(allGroups.first())
                        }
                        if (ensureGroup()) processPastedText(text)
                    }
                    .setNegativeButton("忽略", null)
                    .show()
            }
        } catch (_: Exception) {}
    }

    /** 7. 批量圖片 — 一次選多張圖，逐張 OCR */
    private fun pickBatchImages() {
        if (!ensureGroup()) return
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try { startActivityForResult(intent, REQUEST_BATCH_IMAGE) }
        catch (e: Exception) { statusTv.text = "無法打開圖片選擇器" }
    }

    // ═══════════════════════════════════════
    //  Activity Result 處理
    // ═══════════════════════════════════════

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != android.app.Activity.RESULT_OK) return

        when (requestCode) {
            REQUEST_IMAGE_PICK -> {
                val uri = data?.data ?: return
                processImageOcr(uri, "ocr")
            }
            REQUEST_CAMERA -> {
                val uri = cameraPhotoUri ?: return
                processImageOcr(uri, "camera")
                cameraPhotoUri = null
            }
            REQUEST_PDF_PICK -> {
                val uri = data?.data ?: return
                processPdfOcr(uri)
            }
            REQUEST_FILE_PICK -> {
                val uri = data?.data ?: return
                processFileAuto(uri)
            }
            REQUEST_BATCH_IMAGE -> {
                // 處理單選或多選
                val clipData = data?.clipData
                if (clipData != null) {
                    batchImageUris.clear()
                    batchProcessedCount = 0
                    batchResults.clear()
                    for (i in 0 until clipData.itemCount) {
                        batchImageUris.add(clipData.getItemAt(i).uri)
                    }
                    statusTv.text = "批量處理 0/${batchImageUris.size}..."
                    processBatchImage(0)
                } else {
                    val uri = data?.data ?: return
                    processImageOcr(uri, "batch")
                }
            }
        }
    }

    // ═══════════════════════════════════════
    //  文件類型自動檢測
    // ═══════════════════════════════════════

    /** 根據 MIME 或文件擴展名自動選擇處理方式 */
    private fun processFileAuto(uri: Uri) {
        val mimeType = requireContext().contentResolver.getType(uri) ?: ""
        val path = uri.path ?: ""
        when {
            mimeType.startsWith("image/") || path.matches(Regex(""".*\.(jpg|jpeg|png|bmp|webp|gif)$""", RegexOption.IGNORE_CASE)) ->
                processImageOcr(uri, "file")
            mimeType == "application/pdf" || path.endsWith(".pdf", ignoreCase = true) ->
                processPdfOcr(uri)
            else -> {
                // 嘗試當圖片處理
                processImageOcr(uri, "file")
            }
        }
    }

    // ═══════════════════════════════════════
    //  OCR 核心：圖片
    // ═══════════════════════════════════════

    private fun processImageOcr(uri: Uri, sourceType: String) {
        statusTv.text = "識別中..."
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bitmap = loadBitmapFromUri(uri)
                if (bitmap == null) {
                    withContext(Dispatchers.Main) { statusTv.text = "無法讀取圖片" }
                    return@launch
                }
                runOcrOnBitmap(bitmap, sourceType)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { statusTv.text = "圖片處理失敗: ${e.message?.take(30)}" }
            }
        }
    }

    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            }
        } catch (_: Exception) { null }
    }

    // ═══════════════════════════════════════
    //  OCR 核心：PDF（PdfRenderer 逐頁 → Bitmap → OCR）
    // ═══════════════════════════════════════

    private fun processPdfOcr(uri: Uri) {
        statusTv.text = "打開 PDF..."
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val pfd = requireContext().contentResolver.openFileDescriptor(uri, "r") ?: run {
                    withContext(Dispatchers.Main) { statusTv.text = "無法打開 PDF" }
                    return@launch
                }
                val renderer = PdfRenderer(pfd)
                val pageCount = minOf(renderer.pageCount, MAX_PDF_PAGES)
                val allText = StringBuilder()

                for (i in 0 until pageCount) {
                    withContext(Dispatchers.Main) {
                        statusTv.text = "PDF 識別 ${i + 1}/$pageCount 頁..."
                    }
                    val page = renderer.openPage(i)
                    // 渲染為 2x 解析度的 bitmap
                    val bitmap = Bitmap.createBitmap(
                        page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888
                    )
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()

                    // OCR 此頁
                    val pageText = runOcrSync(bitmap)
                    if (pageText.isNotBlank()) {
                        allText.appendLine("=== 第${i + 1}頁 ===")
                        allText.appendLine(pageText)
                    }
                    bitmap.recycle()
                }
                renderer.close()
                pfd.close()

                val text = allText.toString().trim()
                if (text.isEmpty()) {
                    withContext(Dispatchers.Main) { statusTv.text = "PDF 未識別到文字" }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    processOcrResult(text, "pdf")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { statusTv.text = "PDF 處理失敗: ${e.message?.take(40)}" }
            }
        }
    }

    // ═══════════════════════════════════════
    //  OCR 核心：批量圖片
    // ═══════════════════════════════════════

    private fun processBatchImage(index: Int) {
        if (index >= batchImageUris.size) {
            // 全部處理完
            if (batchResults.isEmpty()) {
                statusTv.text = "批量識別未找到股票"
            } else {
                val distinct = batchResults.distinctBy { it.second }
                showOcrConfirmDialog(distinct, "batch", "批量圖片識別結果")
            }
            return
        }
        statusTv.text = "批量處理 ${index + 1}/${batchImageUris.size}..."
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bitmap = loadBitmapFromUri(batchImageUris[index])
                if (bitmap != null) {
                    val text = runOcrSync(bitmap)
                    val found = extractStocksFromText(text)
                    synchronized(batchResults) { batchResults.addAll(found) }
                }
            } catch (_: Exception) {}
            withContext(Dispatchers.Main) { processBatchImage(index + 1) }
        }
    }

    // ═══════════════════════════════════════
    //  OCR 引擎：同步 + 異步
    // ═══════════════════════════════════════

    /** 同步 OCR（在 IO 線程調用） */
    private suspend fun runOcrSync(bitmap: Bitmap): String {
        return withContext(Dispatchers.Default) {
            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            var result = ""
            val latch = java.util.concurrent.CountDownLatch(1)
            recognizer.process(image)
                .addOnSuccessListener { result = it.text; latch.countDown() }
                .addOnFailureListener { latch.countDown() }
            latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
            result
        }
    }

    /** 異步 OCR（自動彈出確認框） */
    private fun runOcrOnBitmap(bitmap: Bitmap, sourceType: String) {
        statusTv.text = "OCR 識別中..."
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val text = runOcrSync(bitmap)
            if (text.isBlank()) {
                withContext(Dispatchers.Main) { statusTv.text = "未識別到文字" }
                return@launch
            }
            withContext(Dispatchers.Main) { processOcrResult(text, sourceType) }
        }
    }

    private fun processPastedText(text: String) {
        statusTv.text = "解析中..."
        processOcrResult(text, "paste")
    }

    // ═══════════════════════════════════════
    //  股票解析引擎（支持微信聊天、研報、通用格式）
    // ═══════════════════════════════════════

    /**
     * 從原始文字中提取股票（name, code）列表。
     * 支持多種格式：
     * - 微信聊天：「張三: 推薦買入 兆易創新 目標價 120」
     * - 研報：「603986 兆易創新 買入評級 目標價 150」
     * - 通用：每行一個股票名稱或代碼
     */
    private suspend fun extractStocksFromText(text: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()

        // 1. 先嘗試 StockEntityExtractor（最可靠）
        try {
            val entities = StockEntityExtractor.extract(text, requireContext())
            if (entities.isNotEmpty()) {
                return entities.map { e ->
                    (e.name.ifEmpty { e.text }) to e.code
                }.distinctBy { it.second }
            }
        } catch (_: Exception) {}

        // 2. 逐行解析（fallback）
        val lines = text.lines().filter { it.isNotBlank() }
        for (line in lines) {
            // 2a. 嘗試提取 6 位代碼
            val codeMatch = Regex("""(\d{6})""").find(line)
            if (codeMatch != null) {
                val code = codeMatch.groupValues[1]
                // 名稱：代碼前後的中文
                val beforeCode = line.substring(0, codeMatch.range.first)
                val afterCode = line.substring(codeMatch.range.last + 1)
                val nameCandidate = Regex("""[\u4e00-\u9fa5]{2,6}""")
                val name = nameCandidate.find(beforeCode)?.value
                    ?: nameCandidate.find(afterCode)?.value
                    ?: ""
                if (code.length == 6) results.add(name to code)
                continue
            }

            // 2b. 嘗試 resolveSync（名稱 → 代碼）
            val cleaned = line.replace(Regex("""[:：\s\-—|/\\,，。.!！?？]"""), " ").trim()
            // 取最後一段中文作為股票名（跳過可能的發送者名稱）
            val chineseSegments = Regex("""[\u4e00-\u9fa5]{2,8}""").findAll(cleaned).map { it.value }.toList()
            for (seg in chineseSegments.reversed()) {
                val resolved = StockEntityExtractor.resolveSync(seg)
                if (resolved != null) {
                    results.add(seg to resolved)
                    break
                }
            }
        }
        return results.distinctBy { it.second }
    }

    /**
     * 處理 OCR/粘貼結果：解析股票 → 顯示確認
     */
    private fun processOcrResult(rawText: String, sourceType: String) {
        statusTv.text = "AI 解析股票..."
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val results = extractStocksFromText(rawText)
                withContext(Dispatchers.Main) {
                    if (results.isEmpty()) {
                        showOcrRawText(rawText)
                        statusTv.text = "未識別到股票"
                    } else {
                        showOcrConfirmDialog(results, sourceType, rawText)
                        statusTv.text = "識別到 ${results.size} 只股票"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTv.text = "解析失敗: ${e.message?.take(30)}"
                    showOcrRawText(rawText)
                }
            }
        }
    }

    // ═══════════════════════════════════════
    //  確認 & 保存
    // ═══════════════════════════════════════

    private fun showOcrConfirmDialog(
        results: List<Pair<String, String>>,
        sourceType: String,
        rawText: String
    ) {
        if (!isAdded) return
        val msg = buildString {
            appendLine("識別到 ${results.size} 只股票：\n")
            for ((name, code) in results) {
                appendLine("  ${name.ifEmpty { "?" }} ($code)")
            }
            appendLine("\n添加到「$currentGroup」？")
        }
        AlertDialog.Builder(requireContext())
            .setTitle("確認添加")
            .setMessage(msg)
            .setPositiveButton("確認添加") { _, _ -> savePicks(results, sourceType) }
            .setNegativeButton("取消", null)
            .setNeutralButton("查看原文") { _, _ -> showOcrRawText(rawText) }
            .show()
    }

    private fun savePicks(results: List<Pair<String, String>>, sourceType: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val today = LocalDate.now().format(DATE_FMT)
                val entities = results.map { (name, code) ->
                    InstitutionalPickEntity(
                        institutionName = currentGroup,
                        stockCode = code, stockName = name,
                        recommendDate = today, sourceType = sourceType
                    )
                }
                db.institutionalPickDao().insertAll(entities)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "已添加 ${results.size} 只", Toast.LENGTH_SHORT).show()
                    loadPicksForGroup(currentGroup)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "保存失敗: ${e.message?.take(30)}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showOcrRawText(text: String) {
        val scrollView = ScrollView(requireContext())
        val tv = TextView(requireContext()).apply {
            this.text = text; textSize = 12f
            setPadding(32, 24, 32, 24); setTextIsSelectable(true)
        }
        scrollView.addView(tv)
        AlertDialog.Builder(requireContext()).setTitle("識別原文")
            .setView(scrollView).setPositiveButton("確定", null).show()
    }

    // ═══════════════════════════════════════
    //  手動添加
    // ═══════════════════════════════════════

    private fun showManualAddDialog() {
        if (!ensureGroup()) return
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 16)
        }
        val codeInput = EditText(requireContext()).apply {
            hint = "股票名稱或代碼"; setPadding(16, 12, 16, 12)
        }
        val priceInput = EditText(requireContext()).apply {
            hint = "目標價（可選）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(16, 12, 16, 12)
        }
        val reasonInput = EditText(requireContext()).apply {
            hint = "推薦理由（可選）"; minLines = 2; gravity = Gravity.TOP; setPadding(16, 12, 16, 12)
        }
        layout.addView(TextView(requireContext()).apply { text = "股票名稱或代碼："; textSize = 13f; setTextColor(Color.parseColor("#666666")) })
        layout.addView(codeInput)
        layout.addView(TextView(requireContext()).apply { text = "目標價："; textSize = 13f; setTextColor(Color.parseColor("#666666")); setPadding(0, 12, 0, 0) })
        layout.addView(priceInput)
        layout.addView(TextView(requireContext()).apply { text = "推薦理由："; textSize = 13f; setTextColor(Color.parseColor("#666666")); setPadding(0, 12, 0, 0) })
        layout.addView(reasonInput)

        AlertDialog.Builder(requireContext())
            .setTitle("手動添加推薦")
            .setView(layout)
            .setPositiveButton("添加") { _, _ ->
                val raw = codeInput.text.toString().trim()
                if (raw.isEmpty()) return@setPositiveButton
                val targetPrice = priceInput.text.toString().toDoubleOrNull() ?: 0.0
                val reason = reasonInput.text.toString().trim()
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val code = StockEntityExtractor.resolveSync(raw) ?: raw
                    val name = if (code != raw) raw else ""
                    val db = StockDatabase.getInstance(requireContext())
                    db.institutionalPickDao().insert(
                        InstitutionalPickEntity(
                            institutionName = currentGroup, stockCode = code, stockName = name,
                            recommendDate = LocalDate.now().format(DATE_FMT),
                            targetPrice = targetPrice, reason = reason, sourceType = "manual"
                        )
                    )
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "已添加 $name($code)", Toast.LENGTH_SHORT).show()
                        loadPicksForGroup(currentGroup)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ═══════════════════════════════════════
    //  工具
    // ═══════════════════════════════════════

    private fun ensureGroup(): Boolean {
        if (currentGroup.isEmpty()) {
            Toast.makeText(requireContext(), "請先選擇或創建機構分組", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    // ═══════════════════════════════════════
    //  外部分享入口
    // ═══════════════════════════════════════

    /** 處理外部傳入的 URI（圖片/PDF） */
    fun processSharedUri(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.delay(300) // 等待分組加載
            if (!isAdded) return@launch
            if (currentGroup.isEmpty() && allGroups.isNotEmpty()) {
                selectGroup(allGroups.first())
            }
            if (!ensureGroup()) return@launch
            processFileAuto(uri)
        }
    }

    /** 處理外部傳入的文字 */
    fun processSharedText(text: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.delay(300)
            if (!isAdded) return@launch
            if (currentGroup.isEmpty() && allGroups.isNotEmpty()) {
                selectGroup(allGroups.first())
            }
            if (!ensureGroup()) return@launch
            processPastedText(text)
        }
    }
}
