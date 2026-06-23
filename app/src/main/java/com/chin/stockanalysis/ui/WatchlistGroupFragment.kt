package com.chin.stockanalysis.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.widget.LinearLayout.LayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 自選組管理頁（東方財富風格 v3.0）
 *
 * 東方財富自選股特點：
 * - 頂部搜索欄 + 編輯/排序按鈕
 * - 表格化列表：股票名稱 | 最新價 | 漲跌幅 | 漲跌額 | 換手率 | 成交量
 * - 紅漲綠跌，漲跌幅帶背景色
 * - 左滑刪除，點擊進入詳情
 * - 分組標籤頁切換
 */
class WatchlistGroupFragment : Fragment() {

    private lateinit var rootLayout: ScrollView
    private lateinit var groupsContainer: LinearLayout
    private lateinit var lastUpdateTv: TextView

    private val prefs by lazy {
        requireContext().getSharedPreferences("watchlist_prefs", Context.MODE_PRIVATE)
    }

    data class WatchStock(val code: String, val name: String)
    data class WatchGroup(val id: String, val name: String, val stocks: List<WatchStock>)

    private var groups: MutableList<WatchGroup> = mutableListOf()
    private val stockDataCache = mutableMapOf<String, DailySnapshotEntity?>()
    private var currentGroupId: String = "default"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        rootLayout = ScrollView(requireContext()).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        val outer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F6FA"))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        rootLayout.addView(outer)
        loadGroups()
        buildUI(outer)
        refreshStockData()
        return rootLayout
    }

    // ======================== 持久化 ========================

    private fun loadGroups() {
        val json = prefs.getString("groups", "[]") ?: "[]"
        groups.clear()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val stocksArr = obj.optJSONArray("stocks") ?: JSONArray()
                val stocks = mutableListOf<WatchStock>()
                for (j in 0 until stocksArr.length()) {
                    val s = stocksArr.getJSONObject(j)
                    stocks.add(WatchStock(s.getString("code"), s.getString("name")))
                }
                groups.add(WatchGroup(obj.getString("id"), obj.getString("name"), stocks))
            }
        } catch (_: Exception) {}
        if (groups.isEmpty()) {
            groups.add(WatchGroup("default", "我的自选", mutableListOf()))
            saveGroups()
        }
        currentGroupId = groups.firstOrNull()?.id ?: "default"
    }

    private fun saveGroups() {
        val arr = JSONArray()
        for (g in groups) {
            val obj = JSONObject().apply {
                put("id", g.id)
                put("name", g.name)
                val stocksArr = JSONArray()
                for (s in g.stocks) {
                    stocksArr.put(JSONObject().apply {
                        put("code", s.code)
                        put("name", s.name)
                    })
                }
                put("stocks", stocksArr)
            }
            arr.put(obj)
        }
        prefs.edit().putString("groups", arr.toString()).apply()
    }

    // ======================== 數據獲取 ========================

    private fun refreshStockData() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                val allCodes = groups.flatMap { it.stocks.map { s -> s.code } }.distinct()

                stockDataCache.clear()
                for (code in allCodes) {
                    val snapshot = db.dailySnapshotDao().getByDateAndCode(today, code)
                    stockDataCache[code] = snapshot
                }

                withContext(Dispatchers.Main) {
                    refreshGroupsUI()
                    lastUpdateTv.text = "更新: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}"
                }
            } catch (_: Exception) {}
        }
    }

    // ======================== UI ========================

    private fun buildUI(outer: LinearLayout) {
        // ── 頂部導航欄 ──
        val topBar = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(16, 48, 16, 12)
            elevation = 2f
        }
        topBar.addView(TextView(requireContext()).apply {
            text = "自选股"
            textSize = 20f
            setTextColor(Color.parseColor("#1A1A2E"))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })

        // 搜索按鈕
        val searchBtn = ImageView(requireContext()).apply {
            setImageResource(android.R.drawable.ic_menu_search)
            setColorFilter(Color.parseColor("#666666"))
            setPadding(12, 12, 12, 12)
            setOnClickListener { showAddStockDialog(getCurrentGroup()) }
        }
        topBar.addView(searchBtn)

        // 新建組
        val addGroupBtn = TextView(requireContext()).apply {
            text = "+"
            textSize = 22f
            setTextColor(Color.parseColor("#1565C0"))
            setTypeface(null, Typeface.BOLD)
            setPadding(12, 8, 4, 8)
            setOnClickListener { showCreateGroupDialog() }
        }
        topBar.addView(addGroupBtn)

        // 更多菜單（導入/導出）
        val moreBtn = TextView(requireContext()).apply {
            text = "⋮"
            textSize = 20f
            setTextColor(Color.parseColor("#666666"))
            setPadding(8, 8, 8, 8)
            setOnClickListener { showMoreMenu(it) }
        }
        topBar.addView(moreBtn)
        outer.addView(topBar)

        // ── 分組標籤欄 ──
        val tabRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(16, 0, 16, 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        val tabContainer = HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        val tabInner = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for (g in groups) {
            tabInner.addView(createGroupTab(g))
        }
        tabContainer.addView(tabInner)
        tabRow.addView(tabContainer)

        // 編輯按鈕
        val editBtn = TextView(requireContext()).apply {
            text = "编辑"
            textSize = 13f
            setTextColor(Color.parseColor("#1565C0"))
            setPadding(12, 8, 4, 8)
        }
        tabRow.addView(editBtn)
        outer.addView(tabRow)

        // 分隔線
        outer.addView(View(requireContext()).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(Color.parseColor("#EEEEEE"))
        })

        // ── 更新時間 ──
        lastUpdateTv = TextView(requireContext()).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(16, 6, 16, 6)
            text = ""
        }
        outer.addView(lastUpdateTv)

        // ── 表頭 ──
        val headerRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#FAFAFA"))
            setPadding(16, 10, 16, 10)
        }
        headerRow.addView(createTableHeader("股票", 2f))
        headerRow.addView(createTableHeader("最新价", 1f, Gravity.END))
        headerRow.addView(createTableHeader("涨跌幅", 1f, Gravity.CENTER))
        headerRow.addView(createTableHeader("换手率", 0.8f, Gravity.END))
        headerRow.addView(createTableHeader("", 0.3f))
        outer.addView(headerRow)

        // ── 股票列表容器 ──
        groupsContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 80)
        }
        outer.addView(groupsContainer)
        refreshGroupsUI()
    }

    private fun createGroupTab(group: WatchGroup): TextView {
        return TextView(requireContext()).apply {
            text = group.name
            textSize = 14f
            setTextColor(if (group.id == currentGroupId) Color.parseColor("#1565C0") else Color.parseColor("#666666"))
            setTypeface(null, if (group.id == currentGroupId) Typeface.BOLD else Typeface.NORMAL)
            setPadding(12, 10, 12, 10)
            setOnClickListener {
                currentGroupId = group.id
                refreshGroupsUI()
                // 重建標籤欄
                val parent = parent as? LinearLayout ?: return@setOnClickListener
                rebuildTabs(parent)
            }
        }
    }

    private fun rebuildTabs(parent: LinearLayout) {
        // 找到 tabRow 並重建
        if (parent.childCount > 1) {
            val tabRow = parent.getChildAt(1) as? LinearLayout ?: return
            val tabContainer = tabRow.getChildAt(0) as? HorizontalScrollView ?: return
            val tabInner = tabContainer.getChildAt(0) as? LinearLayout ?: return
            tabInner.removeAllViews()
            for (g in groups) {
                tabInner.addView(createGroupTab(g))
            }
        }
    }

    private fun createTableHeader(text: String, weight: Float, gravity: Int = Gravity.START): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.parseColor("#888888"))
            this.gravity = gravity
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, weight)
        }
    }

    private fun refreshGroupsUI() {
        groupsContainer.removeAllViews()
        val group = groups.find { it.id == currentGroupId } ?: groups.firstOrNull() ?: return
        if (group.stocks.isEmpty()) {
            groupsContainer.addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, 60, 0, 60)
                addView(TextView(requireContext()).apply {
                    text = "暂无自选股"
                    textSize = 16f
                    setTextColor(Color.parseColor("#AAAAAA"))
                })
                addView(TextView(requireContext()).apply {
                    text = "点击右上角搜索添加"
                    textSize = 13f
                    setTextColor(Color.parseColor("#CCCCCC"))
                    setPadding(0, 8, 0, 0)
                })
            })
            return
        }
        for ((index, stock) in group.stocks.withIndex()) {
            val snapshot = stockDataCache[stock.code]
            groupsContainer.addView(createStockRow(stock, snapshot, group, index == group.stocks.size - 1))
        }
    }

    private fun createStockRow(
        stock: WatchStock,
        snapshot: DailySnapshotEntity?,
        group: WatchGroup,
        isLast: Boolean
    ): View {
        val ctx = requireContext()
        val dp = { v: Int -> (v * resources.displayMetrics.density + 0.5f).toInt() }

        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        // 內容行
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        // 股票名稱 + 代碼
        val nameCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 2f)
        }
        nameCol.addView(TextView(ctx).apply {
            text = stock.name
            textSize = 15f
            setTextColor(Color.parseColor("#222222"))
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        nameCol.addView(TextView(ctx).apply {
            text = stock.code
            textSize = 11f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(nameCol)

        // 最新價
        val price = snapshot?.close ?: 0.0
        val isUp = snapshot != null && snapshot.changePct > 0
        val isDown = snapshot != null && snapshot.changePct < 0
        val priceColor = when {
            snapshot == null -> Color.parseColor("#999999")
            isUp -> Color.parseColor("#E53935")
            isDown -> Color.parseColor("#43A047")
            else -> Color.parseColor("#333333")
        }
        row.addView(TextView(ctx).apply {
            text = if (snapshot != null) String.format("%.2f", price) else "--"
            textSize = 15f
            setTextColor(priceColor)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.END
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })

        // 漲跌幅（帶背景色）
        val changePct = snapshot?.changePct ?: 0.0
        val changeText = when {
            snapshot == null -> "--"
            changePct > 0 -> "+${String.format("%.2f", changePct)}%"
            else -> "${String.format("%.2f", changePct)}%"
        }
        val changeBg = when {
            snapshot == null -> Color.TRANSPARENT
            isUp -> Color.parseColor("#FFF0F0")
            isDown -> Color.parseColor("#F0FFF0")
            else -> Color.parseColor("#F5F5F5")
        }
        row.addView(TextView(ctx).apply {
            text = changeText
            textSize = 13f
            setTextColor(priceColor)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(changeBg)
                cornerRadius = dp(4).toFloat()
            }
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })

        // 換手率
        val turnoverRate = snapshot?.turnoverRate ?: 0.0
        row.addView(TextView(ctx).apply {
            text = if (snapshot != null) "${String.format("%.2f", turnoverRate)}%" else "--"
            textSize = 13f
            setTextColor(Color.parseColor("#666666"))
            gravity = Gravity.END
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.8f)
        })

        // 刪除按鈕
        row.addView(TextView(ctx).apply {
            text = "✕"
            textSize = 16f
            setTextColor(Color.parseColor("#CCCCCC"))
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.3f)
            setOnClickListener {
                val idx = groups.indexOf(group)
                if (idx >= 0) {
                    groups[idx] = group.copy(stocks = group.stocks.filter { it.code != stock.code })
                    saveGroups()
                    refreshStockData()
                }
            }
        })

        wrapper.addView(row)

        // 底部分隔線
        if (!isLast) {
            wrapper.addView(View(ctx).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 1).apply {
                    setMargins(dp(16), 0, dp(16), 0)
                }
                setBackgroundColor(Color.parseColor("#F0F0F0"))
            })
        }

        // 點擊行進入詳情
        row.setOnClickListener {
            Toast.makeText(ctx, "點擊 ${stock.name} - 詳情頁開發中", Toast.LENGTH_SHORT).show()
        }

        return wrapper
    }

    private fun getCurrentGroup(): WatchGroup {
        return groups.find { it.id == currentGroupId } ?: groups.first()
    }

    private fun showCreateGroupDialog() {
        val input = EditText(requireContext()).apply {
            hint = "输入组名 (如: 科技股, 美股)"
            setPadding(48, 20, 48, 20)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("新建自选组")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    val newGroup = WatchGroup(
                        id = System.currentTimeMillis().toString(),
                        name = name,
                        stocks = emptyList()
                    )
                    groups.add(newGroup)
                    currentGroupId = newGroup.id
                    saveGroups()
                    refreshGroupsUI()
                }
            }.show()
    }

    private fun showAddStockDialog(group: WatchGroup) {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 0)
        }
        val codeInput = EditText(requireContext()).apply {
            hint = "股票代码 (sh600519 / sz000858)"
        }
        val nameInput = EditText(requireContext()).apply {
            hint = "股票名称 (贵州茅台)"
            setPadding(0, 16, 0, 0)
        }
        layout.addView(codeInput)
        layout.addView(nameInput)

        AlertDialog.Builder(requireContext())
            .setTitle("添加股票到「${group.name}」")
            .setView(layout)
            .setNegativeButton("取消", null)
            .setPositiveButton("添加") { _, _ ->
                val code = codeInput.text.toString().trim()
                val name = nameInput.text.toString().trim()
                if (code.isNotBlank() && name.isNotBlank()) {
                    val idx = groups.indexOf(group)
                    if (idx >= 0) {
                        groups[idx] = group.copy(stocks = group.stocks + WatchStock(code, name))
                        saveGroups()
                        refreshStockData()
                    }
                }
            }.show()
    }

    // ═══════════════════════════════════════
    // 導入 / 導出
    // ═══════════════════════════════════════

    private fun showMoreMenu(anchor: View) {
        val popup = android.widget.PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, "📥 導入自選股")
        popup.menu.add(0, 2, 1, "📤 導出自選股")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> importWatchlist()
                2 -> exportWatchlist()
            }
            true
        }
        popup.show()
    }

    private fun importWatchlist() {
        val input = EditText(requireContext()).apply {
            hint = "粘貼JSON格式自選股數據"
            setPadding(24, 16, 24, 16)
            minLines = 4
            maxLines = 8
            setText("[{\"code\":\"sh600519\",\"name\":\"貴州茅臺\"},{\"code\":\"sz002594\",\"name\":\"比亞迪\"}]")
        }
        AlertDialog.Builder(requireContext())
            .setTitle("📥 導入自選股")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("導入") { _, _ ->
                try {
                    val json = input.text.toString().trim()
                    val arr = JSONArray(json)
                    val imported = mutableListOf<WatchStock>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        imported.add(WatchStock(obj.getString("code"), obj.getString("name")))
                    }
                    val group = getCurrentGroup()
                    val idx = groups.indexOf(group)
                    if (idx >= 0) {
                        groups[idx] = group.copy(stocks = group.stocks + imported)
                        saveGroups()
                        refreshStockData()
                        Toast.makeText(requireContext(), "✅ 已導入 ${imported.size} 只股票", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "❌ 格式錯誤: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }.show()
    }

    private fun exportWatchlist() {
        val group = getCurrentGroup()
        val arr = JSONArray()
        for (s in group.stocks) {
            arr.put(JSONObject().apply {
                put("code", s.code)
                put("name", s.name)
            })
        }
        val json = arr.toString()

        val input = EditText(requireContext()).apply {
            setText(json)
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(24, 16, 24, 16)
            minLines = 4
            maxLines = 8
        }

        AlertDialog.Builder(requireContext())
            .setTitle("📤 導出「${group.name}」")
            .setView(input)
            .setNegativeButton("關閉", null)
            .setPositiveButton("複製") { _, _ ->
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("自選股", json))
                Toast.makeText(requireContext(), "✅ 已複製到剪貼板", Toast.LENGTH_SHORT).show()
            }.show()
    }
}
