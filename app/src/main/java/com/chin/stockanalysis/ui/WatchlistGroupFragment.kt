package com.chin.stockanalysis.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.widget.LinearLayout.LayoutParams
import androidx.fragment.app.Fragment
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自选组管理页
 *
 * - 支持创建/删除自选组
 * - 每组内添加/删除股票
 * - 数据持久化到 SharedPreferences（JSON 格式）
 * - 点击股票可展开分析（后续接入 AI 对话）
 */
class WatchlistGroupFragment : Fragment() {

    private lateinit var rootLayout: LinearLayout
    private lateinit var groupsContainer: LinearLayout

    private val prefs by lazy {
        requireContext().getSharedPreferences("watchlist_prefs", Context.MODE_PRIVATE)
    }

    data class WatchStock(val code: String, val name: String)
    data class WatchGroup(val id: String, val name: String, val stocks: List<WatchStock>)

    private var groups: MutableList<WatchGroup> = mutableListOf()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        rootLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F6FA"))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        loadGroups()
        buildUI()
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
            // 默认预设组
            groups.add(WatchGroup("default", "我的自选", listOf(
                WatchStock("sh600519", "贵州茅台"),
                WatchStock("sz002594", "比亚迪"),
                WatchStock("sz300750", "宁德时代")
            )))
        }
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

    // ======================== UI ========================

    private fun buildUI() {
        // 标题
        val header = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 40, 24, 12)
            setBackgroundColor(Color.WHITE)
        }
        header.addView(TextView(requireContext()).apply {
            text = "⭐ 我的自选"
            textSize = 22f
            setTextColor(Color.parseColor("#1A1A2E"))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })
        val addGroupBtn = Button(requireContext()).apply {
            text = "+ 新建组"
            textSize = 13f
            setBackgroundColor(Color.parseColor("#1565C0"))
            setTextColor(Color.WHITE)
            setPadding(16, 8, 16, 8)
            setOnClickListener { showCreateGroupDialog() }
        }
        header.addView(addGroupBtn)
        rootLayout.addView(header)

        // 组容器
        groupsContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 80)
        }
        rootLayout.addView(groupsContainer)
        refreshGroupsUI()
    }

    private fun refreshGroupsUI() {
        groupsContainer.removeAllViews()
        for (g in groups) {
            groupsContainer.addView(createGroupCard(g))
        }
    }

    private fun createGroupCard(group: WatchGroup): LinearLayout {
        val ctx = requireContext()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(16, 12, 16, 12)
            (layoutParams as? LayoutParams)?.setMargins(0, 0, 0, 12)
            elevation = 3f
        }

        // 组标题行
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(ctx).apply {
            text = "📁 ${group.name}  (${group.stocks.size}只)"
            textSize = 15f
            setTextColor(Color.parseColor("#333333"))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })
        val addStockBtn = TextView(ctx).apply {
            text = "+ 添加"
            textSize = 12f
            setTextColor(Color.parseColor("#1565C0"))
            setPadding(12, 4, 12, 4)
            setOnClickListener { showAddStockDialog(group) }
        }
        header.addView(addStockBtn)
        val delGroupBtn = TextView(ctx).apply {
            text = "删除组"
            textSize = 12f
            setTextColor(Color.parseColor("#E53935"))
            setPadding(12, 4, 0, 4)
            setOnClickListener {
                AlertDialog.Builder(ctx)
                    .setTitle("删除自选组")
                    .setMessage("确定要删除「${group.name}」吗？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除") { _, _ ->
                        groups.removeAll { it.id == group.id }
                        saveGroups()
                        refreshGroupsUI()
                    }.show()
            }
        }
        header.addView(delGroupBtn)
        card.addView(header)

        // 股票列表
        if (group.stocks.isEmpty()) {
            card.addView(TextView(ctx).apply {
                text = "  暂无股票，点击 + 添加"
                textSize = 12f
                setTextColor(Color.parseColor("#AAAAAA"))
                setPadding(0, 8, 0, 0)
            })
        } else {
            for (stock in group.stocks) {
                val stockRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(8, 8, 0, 4)
                }
                stockRow.addView(TextView(ctx).apply {
                    text = "• ${stock.name} (${stock.code.takeLast(6)})"
                    textSize = 13f
                    setTextColor(Color.parseColor("#555555"))
                    layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                })
                val delBtn = TextView(ctx).apply {
                    text = "✕"
                    textSize = 14f
                    setTextColor(Color.parseColor("#CCCCCC"))
                    setPadding(12, 0, 4, 0)
                    setOnClickListener {
                        groups.find { it.id == group.id }?.let { g ->
                            val idx = groups.indexOf(g)
                            groups[idx] = g.copy(stocks = g.stocks.filter { it.code != stock.code })
                            saveGroups()
                            refreshGroupsUI()
                        }
                    }
                }
                stockRow.addView(delBtn)
                card.addView(stockRow)
            }
        }
        return card
    }

    private fun showCreateGroupDialog() {
        val input = EditText(requireContext()).apply {
            hint = "输入组名 (如: 美股, 科技股)"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("新建自选组")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    groups.add(WatchGroup(
                        id = System.currentTimeMillis().toString(),
                        name = name,
                        stocks = emptyList()
                    ))
                    saveGroups()
                    refreshGroupsUI()
                }
            }.show()
    }

    private fun showAddStockDialog(group: WatchGroup) {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 0)
        }
        val codeInput = EditText(requireContext()).apply {
            hint = "股票代码 (sh600519 / sz000858)"
        }
        val nameInput = EditText(requireContext()).apply {
            hint = "股票名称 (贵州茅台)"
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
                    groups.find { it.id == group.id }?.let { g ->
                        val idx = groups.indexOf(g)
                        groups[idx] = g.copy(stocks = g.stocks + WatchStock(code, name))
                        saveGroups()
                        refreshGroupsUI()
                    }
                }
            }.show()
    }
}