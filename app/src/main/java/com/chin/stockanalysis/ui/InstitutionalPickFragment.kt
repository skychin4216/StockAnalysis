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
 * ## 机构推荐股票 Fragment
 *
 * 功能：
 * - 自定义分组管理（中金、中信、高盛等）
 * - 每个分组下可添加推荐股票
 * - 多源 OCR：图片/PDF/拍照/剪贴板/批量图片/扫描文件
 * - AI 自动搜索：OCR 后自动解析股票名称 → 代码
 * - 支持微信聊天截图、券商研究报告 PDF 等场景
 */
class InstitutionalPickFragment : Fragment() {

    private lateinit var rootLayout: LinearLayout
    private lateinit var groupChipRow: LinearLayout
    private lateinit var stockListContainer: LinearLayout
    private lateinit var statusTv: TextView
    private lateinit var addGroupBtn: TextView

    /** 当前选中的分组 */
    private var currentGroup: String = ""
    /** 所有分组名称 */
    private var allGroups: List<String> = emptyList()
    /** 当前分组的股票列表 */
    private var currentPicks: List<InstitutionalPickEntity> = emptyList()
    /** 分组 chip views */
    private val groupChipViews = mutableMapOf<String, TextView>()

    /** 拍照用：临时文件 URI */
    private var cameraPhotoUri: Uri? = null

    /** 批量图片 OCR 待处理队列 */
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
    //  UI 构建
    // ═══════════════════════════════════════

    private fun buildUI() {
        // ── 标题行 ──
        val titleRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 12, 16, 8)
            setBackgroundColor(Color.WHITE)
        }
        titleRow.addView(TextView(requireContext()).apply {
            text = "机构推荐"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#333333"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addGroupBtn = TextView(requireContext()).apply {
            text = "+ 分组"
            textSize = 13f
            setTextColor(Color.parseColor("#1565C0"))
            setTypeface(null, Typeface.BOLD)
            setPadding(16, 8, 16, 8)
            setOnClickListener { showAddGroupDialog() }
        }
        titleRow.addView(addGroupBtn)
        rootLayout.addView(titleRow)

        // ── 分组 Chip 行 ──
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

        // ── 操作按钮行 1（图片 / PDF / 拍照 / 扫描文件）──
        val actionRow1 = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(12, 8, 12, 4)
            setBackgroundColor(Color.WHITE)
        }
        actionRow1.addView(createActionBtn("图片识别") { pickImage() })
        actionRow1.addView(createActionBtn("PDF识别") { pickPdf() })
        actionRow1.addView(createActionBtn("拍照识别") { takePhoto() })
        actionRow1.addView(createActionBtn("扫描文件") { pickFile() })
        rootLayout.addView(actionRow1)

        // ── 操作按钮行 2（粘贴 / 剪贴板 / 批量 / 手动）──
        val actionRow2 = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(12, 4, 12, 8)
            setBackgroundColor(Color.WHITE)
        }
        actionRow2.addView(createActionBtn("粘贴文字") { showPasteDialog() })
        actionRow2.addView(createActionBtn("剪贴板") { readClipboard() })
        actionRow2.addView(createActionBtn("批量图片") { pickBatchImages() })
        actionRow2.addView(createActionBtn("手动添加") { showManualAddDialog() })
        rootLayout.addView(actionRow2)

        // ── 状态行 ──
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
    //  分组管理
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
                    statusTv.text = "加载失败: ${e.message?.take(30)}"
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
                text = "点击「+ 分组」添加机构"
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
            .setItems(arrayOf("重命名", "删除分组")) { _, which ->
                when (which) {
                    0 -> showRenameGroupDialog(groupName)
                    1 -> confirmDeleteGroup(groupName)
                }
            }.show()
    }

    private fun showAddGroupDialog() {
        if (!isAdded) return
        val input = EditText(requireContext()).apply {
            hint = "机构名称（如：中金、中信、高盛）"
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("新增机构分组")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
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
            .setTitle("重命名分组")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
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
                withContext(Dispatchers.Main) { statusTv.text = "添加失败: ${e.message?.take(30)}" }
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
                withContext(Dispatchers.Main) { statusTv.text = "重命名失败: ${e.message?.take(30)}" }
            }
        }
    }

    private fun confirmDeleteGroup(groupName: String) {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle("删除分组")
            .setMessage("确定删除「$groupName」及其所有推荐记录？")
            .setPositiveButton("删除") { _, _ ->
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
                withContext(Dispatchers.Main) { statusTv.text = "加载失败: ${e.message?.take(30)}" }
            }
        }
    }

    private fun renderStockList() {
        stockListContainer.removeAllViews()
        if (currentGroup.isEmpty()) { renderEmptyState(); return }
        if (currentPicks.isEmpty()) {
            stockListContainer.addView(TextView(requireContext()).apply {
                text = "暂无推荐记录\n点击下方按钮添加"
                textSize = 14f; setTextColor(Color.parseColor("#BBBBBB"))
                gravity = Gravity.CENTER; setPadding(0, 64, 0, 64)
            })
            statusTv.text = "${currentGroup}：0 只"
            return
        }
        statusTv.text = "${currentGroup}：${currentPicks.size} 只"

        // 表头
        val headerRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#FAFAFA"))
            setPadding(16, 8, 16, 8)
        }
        headerRow.addView(headerCell("股票", 2.5f, Gravity.START))
        headerRow.addView(headerCell("推荐日", 1.2f, Gravity.CENTER))
        headerRow.addView(headerCell("目标价", 1.0f, Gravity.END))
        headerRow.addView(headerCell("来源", 0.8f, Gravity.CENTER))
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
        "ocr" -> "图片"
        "pdf" -> "PDF"
        "paste" -> "粘贴"
        "camera" -> "拍照"
        "clipboard" -> "剪贴板"
        "batch" -> "批量"
        "file" -> "文件"
        "share" -> "分享"
        else -> "手动"
    }

    private fun renderEmptyState() {
        stockListContainer.removeAllViews()
        stockListContainer.addView(TextView(requireContext()).apply {
            text = "暂无机构分组\n点击右上角「+ 分组」开始"
            textSize = 14f; setTextColor(Color.parseColor("#BBBBBB"))
            gravity = Gravity.CENTER; setPadding(0, 80, 0, 80)
        })
        statusTv.text = ""
    }

    private fun confirmDeletePick(pick: InstitutionalPickEntity) {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle("移除推荐")
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
            appendLine("推荐日：${pick.recommendDate}")
            if (pick.targetPrice > 0) appendLine("目标价：${pick.targetPrice}")
            if (pick.subGroup.isNotEmpty()) appendLine("子分组：${pick.subGroup}")
            appendLine("来源：${sourceLabel(pick.sourceType)}")
            if (pick.reason.isNotEmpty()) { appendLine(); appendLine("理由："); appendLine(pick.reason) }
            if (pick.notes.isNotEmpty()) { appendLine(); appendLine("备注：${pick.notes}") }
        }
        AlertDialog.Builder(requireContext()).setTitle("${pick.stockName} 推荐详情")
            .setMessage(msg).setPositiveButton("确定", null).show()
    }

    // ═══════════════════════════════════════
    //  多源输入：图片 / PDF / 拍照 / 扫描 / 剪贴板 / 批量
    // ═══════════════════════════════════════

    /** 1. 图片识别 — 从相册选择（截图、微信图片、任何图片） */
    private fun pickImage() {
        if (!ensureGroup()) return
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try { startActivityForResult(intent, REQUEST_IMAGE_PICK) }
        catch (e: Exception) { statusTv.text = "无法打开图片选择器" }
    }

    /** 2. PDF 识别 — 选择 PDF 文件，逐页渲染 OCR */
    private fun pickPdf() {
        if (!ensureGroup()) return
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "application/pdf"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try { startActivityForResult(intent, REQUEST_PDF_PICK) }
        catch (e: Exception) { statusTv.text = "无法打开文件选择器" }
    }

    /** 3. 拍照识别 — 使用 FileProvider 保存全尺寸照片 */
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
        catch (e: Exception) { statusTv.text = "无法打开相机" }
    }

    /** 4. 扫描文件 — 支持图片+PDF 混合选择 */
    private fun pickFile() {
        if (!ensureGroup()) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/pdf"))
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try { startActivityForResult(intent, REQUEST_FILE_PICK) }
        catch (e: Exception) { statusTv.text = "无法打开文件选择器" }
    }

    /** 5. 粘贴文字 */
    private fun showPasteDialog() {
        if (!ensureGroup()) return
        val input = EditText(requireContext()).apply {
            hint = "粘贴机构推荐文字\n（支持微信聊天、研报文字、股票名称/代码）"
            minLines = 4; gravity = Gravity.TOP; setPadding(32, 24, 32, 24)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("粘贴推荐文字")
            .setView(input)
            .setPositiveButton("识别") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) processPastedText(text)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 6. 剪贴板 — 读取剪贴板内容并识别 */
    private fun readClipboard() {
        if (!ensureGroup()) return
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) {
            statusTv.text = "剪贴板为空"
            return
        }
        val text = clip.getItemAt(0).text?.toString()?.trim()
        if (text.isNullOrEmpty()) {
            statusTv.text = "剪贴板无文字内容"
            return
        }
        statusTv.text = "剪贴板：${text.take(30)}..."
        processPastedText(text)
    }

    /** 进入页面时自动检查剪贴板是否有股票相关内容 */
    private fun checkClipboardOnStart() {
        try {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0).text?.toString()?.trim() ?: return
            if (text.length < 4) return
            // 快速检查是否包含股票代码或常见股票名称
            val hasCode = Regex("""\d{6}""").containsMatchIn(text)
            val hasStockHint = text.contains("推荐") || text.contains("目标") || text.contains("买入")
                || text.contains("评级") || text.contains("关注")
            if (!hasCode && !hasStockHint) return

            // 有股票相关内容 → 显示提示
            val preview = text.take(60)
            statusTv.text = "剪贴板可能有股票信息"
            viewLifecycleOwner.lifecycleScope.launch {
                kotlinx.coroutines.delay(500)
                if (!isAdded) return@launch
                AlertDialog.Builder(requireContext())
                    .setTitle("检测到剪贴板内容")
                    .setMessage("剪贴板似乎包含股票信息：\n\n「$preview...」\n\n是否识别并添加？")
                    .setPositiveButton("识别") { _, _ ->
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

    /** 7. 批量图片 — 一次选多张图，逐张 OCR */
    private fun pickBatchImages() {
        if (!ensureGroup()) return
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try { startActivityForResult(intent, REQUEST_BATCH_IMAGE) }
        catch (e: Exception) { statusTv.text = "无法打开图片选择器" }
    }

    // ═══════════════════════════════════════
    //  Activity Result 处理
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
                // 处理单选或多选
                val clipData = data?.clipData
                if (clipData != null) {
                    batchImageUris.clear()
                    batchProcessedCount = 0
                    batchResults.clear()
                    for (i in 0 until clipData.itemCount) {
                        batchImageUris.add(clipData.getItemAt(i).uri)
                    }
                    statusTv.text = "批量处理 0/${batchImageUris.size}..."
                    processBatchImage(0)
                } else {
                    val uri = data?.data ?: return
                    processImageOcr(uri, "batch")
                }
            }
        }
    }

    // ═══════════════════════════════════════
    //  文件类型自动检测
    // ═══════════════════════════════════════

    /** 根据 MIME 或文件扩展名自动选择处理方式 */
    private fun processFileAuto(uri: Uri) {
        val mimeType = requireContext().contentResolver.getType(uri) ?: ""
        val path = uri.path ?: ""
        when {
            mimeType.startsWith("image/") || path.matches(Regex(""".*\.(jpg|jpeg|png|bmp|webp|gif)$""", RegexOption.IGNORE_CASE)) ->
                processImageOcr(uri, "file")
            mimeType == "application/pdf" || path.endsWith(".pdf", ignoreCase = true) ->
                processPdfOcr(uri)
            else -> {
                // 尝试当图片处理
                processImageOcr(uri, "file")
            }
        }
    }

    // ═══════════════════════════════════════
    //  OCR 核心：图片
    // ═══════════════════════════════════════

    private fun processImageOcr(uri: Uri, sourceType: String) {
        statusTv.text = "识别中..."
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bitmap = loadBitmapFromUri(uri)
                if (bitmap == null) {
                    withContext(Dispatchers.Main) { statusTv.text = "无法读取图片" }
                    return@launch
                }
                runOcrOnBitmap(bitmap, sourceType)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { statusTv.text = "图片处理失败: ${e.message?.take(30)}" }
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
    //  OCR 核心：PDF（PdfRenderer 逐页 → Bitmap → OCR）
    // ═══════════════════════════════════════

    private fun processPdfOcr(uri: Uri) {
        statusTv.text = "打开 PDF..."
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val pfd = requireContext().contentResolver.openFileDescriptor(uri, "r") ?: run {
                    withContext(Dispatchers.Main) { statusTv.text = "无法打开 PDF" }
                    return@launch
                }
                val renderer = PdfRenderer(pfd)
                val pageCount = minOf(renderer.pageCount, MAX_PDF_PAGES)
                val allText = StringBuilder()

                for (i in 0 until pageCount) {
                    withContext(Dispatchers.Main) {
                        statusTv.text = "PDF 识别 ${i + 1}/$pageCount 页..."
                    }
                    val page = renderer.openPage(i)
                    // 渲染为 2x 解析度的 bitmap
                    val bitmap = Bitmap.createBitmap(
                        page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888
                    )
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()

                    // OCR 此页
                    val pageText = runOcrSync(bitmap)
                    if (pageText.isNotBlank()) {
                        allText.appendLine("=== 第${i + 1}页 ===")
                        allText.appendLine(pageText)
                    }
                    bitmap.recycle()
                }
                renderer.close()
                pfd.close()

                val text = allText.toString().trim()
                if (text.isEmpty()) {
                    withContext(Dispatchers.Main) { statusTv.text = "PDF 未识别到文字" }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    processOcrResult(text, "pdf")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { statusTv.text = "PDF 处理失败: ${e.message?.take(40)}" }
            }
        }
    }

    // ═══════════════════════════════════════
    //  OCR 核心：批量图片
    // ═══════════════════════════════════════

    private fun processBatchImage(index: Int) {
        if (index >= batchImageUris.size) {
            // 全部处理完
            if (batchResults.isEmpty()) {
                statusTv.text = "批量识别未找到股票"
            } else {
                val distinct = batchResults.distinctBy { it.second }
                showOcrConfirmDialog(distinct, "batch", "批量图片识别结果")
            }
            return
        }
        statusTv.text = "批量处理 ${index + 1}/${batchImageUris.size}..."
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
    //  OCR 引擎：同步 + 异步
    // ═══════════════════════════════════════

    /** 同步 OCR（在 IO 线程调用） */
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

    /** 异步 OCR（自动弹出确认框） */
    private fun runOcrOnBitmap(bitmap: Bitmap, sourceType: String) {
        statusTv.text = "OCR 识别中..."
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val text = runOcrSync(bitmap)
            if (text.isBlank()) {
                withContext(Dispatchers.Main) { statusTv.text = "未识别到文字" }
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
    //  股票解析引擎（支持微信聊天、研报、通用格式）
    // ═══════════════════════════════════════

    /**
     * 从原始文字中提取股票（name, code）列表。
     * 支持多种格式：
     * - 微信聊天：「张三: 推荐买入 兆易创新 目标价 120」
     * - 研报：「603986 兆易创新 买入评级 目标价 150」
     * - 通用：每行一个股票名称或代码
     */
    private suspend fun extractStocksFromText(text: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()

        // 1. 先尝试 StockEntityExtractor（最可靠）
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
            // 2a. 尝试提取 6 位代码
            val codeMatch = Regex("""(\d{6})""").find(line)
            if (codeMatch != null) {
                val code = codeMatch.groupValues[1]
                // 名称：代码前后的中文
                val beforeCode = line.substring(0, codeMatch.range.first)
                val afterCode = line.substring(codeMatch.range.last + 1)
                val nameCandidate = Regex("""[\u4e00-\u9fa5]{2,6}""")
                val name = nameCandidate.find(beforeCode)?.value
                    ?: nameCandidate.find(afterCode)?.value
                    ?: ""
                if (code.length == 6) results.add(name to code)
                continue
            }

            // 2b. 尝试 resolveSync（名称 → 代码）
            val cleaned = line.replace(Regex("""[:：\s\-—|/\\,，。.!！?？]"""), " ").trim()
            // 取最后一段中文作为股票名（跳过可能的发送者名称）
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
     * 处理 OCR/粘贴结果：解析股票 → 显示确认
     */
    private fun processOcrResult(rawText: String, sourceType: String) {
        statusTv.text = "AI 解析股票..."
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val results = extractStocksFromText(rawText)
                withContext(Dispatchers.Main) {
                    if (results.isEmpty()) {
                        showOcrRawText(rawText)
                        statusTv.text = "未识别到股票"
                    } else {
                        showOcrConfirmDialog(results, sourceType, rawText)
                        statusTv.text = "识别到 ${results.size} 只股票"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTv.text = "解析失败: ${e.message?.take(30)}"
                    showOcrRawText(rawText)
                }
            }
        }
    }

    // ═══════════════════════════════════════
    //  确认 & 保存
    // ═══════════════════════════════════════

    private fun showOcrConfirmDialog(
        results: List<Pair<String, String>>,
        sourceType: String,
        rawText: String
    ) {
        if (!isAdded) return
        val msg = buildString {
            appendLine("识别到 ${results.size} 只股票：\n")
            for ((name, code) in results) {
                appendLine("  ${name.ifEmpty { "?" }} ($code)")
            }
            appendLine("\n添加到「$currentGroup」？")
        }
        AlertDialog.Builder(requireContext())
            .setTitle("确认添加")
            .setMessage(msg)
            .setPositiveButton("确认添加") { _, _ -> savePicks(results, sourceType) }
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
                    Toast.makeText(requireContext(), "保存失败: ${e.message?.take(30)}", Toast.LENGTH_SHORT).show()
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
        AlertDialog.Builder(requireContext()).setTitle("识别原文")
            .setView(scrollView).setPositiveButton("确定", null).show()
    }

    // ═══════════════════════════════════════
    //  手动添加
    // ═══════════════════════════════════════

    private fun showManualAddDialog() {
        if (!ensureGroup()) return
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 16)
        }
        val codeInput = EditText(requireContext()).apply {
            hint = "股票名称或代码"; setPadding(16, 12, 16, 12)
        }
        val priceInput = EditText(requireContext()).apply {
            hint = "目标价（可选）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(16, 12, 16, 12)
        }
        val reasonInput = EditText(requireContext()).apply {
            hint = "推荐理由（可选）"; minLines = 2; gravity = Gravity.TOP; setPadding(16, 12, 16, 12)
        }
        layout.addView(TextView(requireContext()).apply { text = "股票名称或代码："; textSize = 13f; setTextColor(Color.parseColor("#666666")) })
        layout.addView(codeInput)
        layout.addView(TextView(requireContext()).apply { text = "目标价："; textSize = 13f; setTextColor(Color.parseColor("#666666")); setPadding(0, 12, 0, 0) })
        layout.addView(priceInput)
        layout.addView(TextView(requireContext()).apply { text = "推荐理由："; textSize = 13f; setTextColor(Color.parseColor("#666666")); setPadding(0, 12, 0, 0) })
        layout.addView(reasonInput)

        AlertDialog.Builder(requireContext())
            .setTitle("手动添加推荐")
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
            Toast.makeText(requireContext(), "请先选择或创建机构分组", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    // ═══════════════════════════════════════
    //  外部分享入口
    // ═══════════════════════════════════════

    /** 处理外部传入的 URI（图片/PDF） */
    fun processSharedUri(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.delay(300) // 等待分组加载
            if (!isAdded) return@launch
            if (currentGroup.isEmpty() && allGroups.isNotEmpty()) {
                selectGroup(allGroups.first())
            }
            if (!ensureGroup()) return@launch
            processFileAuto(uri)
        }
    }

    /** 处理外部传入的文字 */
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
