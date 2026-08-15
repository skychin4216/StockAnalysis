package com.chin.stockanalysis.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.*
import android.widget.*
import android.widget.LinearLayout.LayoutParams
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.stock.database.DataExportImport
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.data.HistoricalDataFetcher
import com.chin.stockanalysis.strategy.sector.StrategyMarketContext
import com.chin.stockanalysis.strategy.sector.UserMarketMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * ## 导入 Tab — v12.0 数据管理 + 导入过程 + 热门板块
 *
 * 从量化选股的「数据 / 导入」控件移植：
 * - 🔄 刷新市场上下文（清空 StrategyMarketContext 缓存）
 * - 📊 数据库统计信息
 * - 🧠 市场记忆设置
 * - 📥 拉取股票报告
 * - 📤 导出热门板块 / 📤 导出K线快照
 * - [导入] 显示导入进度，完成后展示当前热门板块
 */
class StrategyImportFragment : Fragment() {

    private lateinit var layout: LinearLayout
    private lateinit var importBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusTv: TextView
    private lateinit var dateLabelTv: TextView
    private lateinit var datePicker: TradingDayPickerView
    private var selectedHotPeriod = 0
    private var browsingDate: LocalDate = TradingDayPickerView.recentTradingDay()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#F5F6FA"))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        buildUI(); return layout
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUI() {
        // ── 标题 ──
        layout.addView(TextView(requireContext()).apply {
            text = "📥 数据管理 & 导入"
            textSize = 16f; setTextColor(Color.parseColor("#1A1A2E")); setTypeface(null, Typeface.BOLD)
            setPadding(dp(16), dp(16), dp(16), dp(4)); setBackgroundColor(Color.WHITE)
        })

        // ── 数据管理按钮区（2 行 × 2 列） ──
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)); setBackgroundColor(Color.WHITE)
        }
        fun buildActionRow(btn: Button): LinearLayout {
            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(btn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
            return row
        }
        fun actionButton(text: String, color: String, onClick: () -> Unit): Button =
            Button(requireContext()).apply {
                this.text = text; textSize = 12f; setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor(color)); setPadding(dp(6), dp(10), dp(6), dp(10))
                setMinWidth(0); setMinimumWidth(0)
                setOnClickListener { onClick() }
            }

        // 行1：刷新市场上下文 | 数据库统计信息
        card.addView(buildActionRow(actionButton("🔄 刷新市场上下文", "#455A64") { refreshMarketContext() }))
        val statsBtn = actionButton("📊 数据库统计信息", "#00897B") { showDbStats() }
        card.addView(buildActionRow(statsBtn))

        // 行2：市场记忆设置 | 拉取股票报告
        val memoryBtn = actionButton("🧠 市场记忆设置", "#6A1B9A") { showMarketMemoryDialog() }
        card.addView(buildActionRow(memoryBtn))
        val fetchBtn = actionButton("📥 拉取股票报告", "#1565C0") { showFetchReport() }
        card.addView(buildActionRow(fetchBtn))

        // 行3：导出热门板块 | 导出K线快照
        val hotBtn = actionButton("📤 导出热门板块", "#E65100") { showHotSectorsReport() }
        card.addView(buildActionRow(hotBtn))
        val snapBtn = actionButton("📤 导出K线快照", "#BF360C") { exportSnapshotData() }
        card.addView(buildActionRow(snapBtn))
        layout.addView(card)

        // ── 导入区域 ──
        val importCard = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(12)); setBackgroundColor(Color.WHITE)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        importCard.addView(TextView(requireContext()).apply {
            text = "⬇️ 导入历史行情（东方财富）"
            textSize = 13f; setTextColor(Color.parseColor("#1A1A2E")); setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(6))
        })
        // 周期选择
        val periodRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val periodSpinner = Spinner(requireContext()).apply {
            val presets = listOf("当日", "近3日", "近10日", "近30日", "近50日", "近100日")
            adapter = object : ArrayAdapter<String>(requireContext(), android.R.layout.simple_spinner_item, presets) {
                init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
                    val tv = super.getView(pos, cv, parent) as TextView
                    tv.textSize = 12f; tv.setTextColor(Color.parseColor("#2E7D32")); tv.typeface = Typeface.DEFAULT_BOLD
                    return tv
                }
            }
            setSelection(0); setBackgroundColor(Color.parseColor("#E8F5E9")); setPadding(dp(6), dp(4), dp(6), dp(4))
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { selectedHotPeriod = pos }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        periodRow.addView(periodSpinner)
        dateLabelTv = TextView(requireContext()).apply {
            text = "  交易日:"; textSize = 12f; setTextColor(Color.parseColor("#999999")); setPadding(dp(8), 0, dp(2), 0)
        }; periodRow.addView(dateLabelTv)
        datePicker = TradingDayPickerView(requireContext()).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            onDateChanged = { d ->
                browsingDate = d
                val isNonTrading = d.dayOfWeek == java.time.DayOfWeek.SATURDAY ||
                        d.dayOfWeek == java.time.DayOfWeek.SUNDAY ||
                        d in TradingDayPickerView.CHINESE_HOLIDAYS
                dateLabelTv.text = if (isNonTrading) "  非交易日:" else "  交易日:"
            }
        }; periodRow.addView(datePicker)
        importCard.addView(periodRow)

        // 进度行
        val statusRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
        progressBar = ProgressBar(requireContext()).apply { visibility = View.GONE; layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) } }
        statusRow.addView(progressBar)
        statusTv = TextView(requireContext()).apply { text = "  选择周期与起始交易日，点击开始导入"; textSize = 11f; setTextColor(Color.parseColor("#888888")) }
        statusRow.addView(statusTv)
        importCard.addView(statusRow)

        // 导入按钮
        importBtn = Button(requireContext()).apply {
            text = "⬇️ 开始导入"; textSize = 13f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#2E7D32"))
            setPadding(dp(8), dp(12), dp(8), dp(12)); layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) }
            setOnClickListener { importHistoricalData() }
        }
        importCard.addView(importBtn)
        layout.addView(importCard)

        // 说明
        layout.addView(TextView(requireContext()).apply {
            text = "💡 导入完成后自动展示当前热门板块；市场上下文缓存用于策略执行时聚合最新板块/指数数据，刷新后下次执行策略强制重新拉取。"
            textSize = 10f; setTextColor(Color.parseColor("#AAAAAA")); setPadding(dp(16), dp(8), dp(16), dp(4))
        })
    }

    // ═══════════════ ① 🔄 刷新市场上下文 ═══════════════
    private fun refreshMarketContext() {
        StrategyMarketContext.invalidateCache()
        Toast.makeText(requireContext(), "市场上下文缓存已清空，下次执行策略将重新拉取最新数据", Toast.LENGTH_SHORT).show()
    }

    // ═══════════════ ② 📊 数据库统计信息 ═══════════════
    private fun showDbStats() {
        val exporter = DataExportImport(requireContext())
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val stats = exporter.getDatabaseStats()
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("📊 数据库统计")
                        .setMessage(stats as CharSequence)
                        .setPositiveButton("关闭", null)
                        .show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "获取统计失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ═══════════════ ③ 🧠 市场记忆设置 ═══════════════
    private fun showMarketMemoryDialog() {
        val memory = UserMarketMemory(requireContext())
        val ctx = requireContext()

        val loadingTv = TextView(ctx).apply {
            text = "⏳ 正在载入..."
            textSize = 13f
            setPadding(dp(32), dp(48), dp(32), dp(48))
            gravity = Gravity.CENTER
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("🧠 市场记忆设置")
            .setView(loadingTv)
            .setNegativeButton("关闭", null)
            .show()

        lifecycleScope.launch(Dispatchers.IO) {
            val allSectors = memory.getAllFocusSectors()
            val aiDetection = memory.aiYearDetection
            val hotNames = memory.getRecentHotSectors().map { it.sectorName }
            val newsNames = memory.getRecentNewsSectors()
            val suggestedNames = (hotNames + newsNames).distinct()
                .filter { name -> allSectors.none { it.sectorName == name } }
                .take(8)

            withContext(Dispatchers.Main) {
                if (!isAdded) { dialog.dismiss(); return@withContext }
                dialog.dismiss()
                buildMemoryDialogContent(ctx, memory, allSectors, suggestedNames, aiDetection)
            }
        }
    }

    private fun buildMemoryDialogContent(
        ctx: android.content.Context,
        memory: UserMarketMemory,
        allSectors: List<com.chin.stockanalysis.strategy.sector.UserFocusSectorEntity>,
        suggestedNames: List<String>,
        aiDetection: String
    ) {
        val scrollView = ScrollView(ctx).apply { setPadding(dp(24), dp(16), dp(24), dp(16)) }
        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scrollView.addView(container)

        // AI 检测区
        container.addView(TextView(ctx).apply {
            text = "🤖 AI 市场判断：$aiDetection"
            textSize = 12f
            setTextColor(Color.parseColor("#1565C0"))
            setPadding(0, 0, 0, dp(12))
        })
        container.addView(Button(ctx).apply {
            text = "🔄 重新检测市场风格"
            textSize = 11f
            setOnClickListener {
                text = "⏳ 检测中..."
                isEnabled = false
                lifecycleScope.launch {
                    val result = memory.detectSectorYearByIndex()
                    memory.aiYearDetection = result
                    StrategyMarketContext.invalidateCache()
                    Toast.makeText(ctx, "AI 检测：$result", Toast.LENGTH_LONG).show()
                }
            }
        })

        // 分隔线
        container.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply { topMargin = dp(16); bottomMargin = dp(16) }
            setBackgroundColor(Color.parseColor("#E0E0E0"))
        })

        // 已记录板块区
        container.addView(TextView(ctx).apply {
            text = "📋 已记录的主力板块（点击切换启用/停用）"
            textSize = 12f
            setTextColor(Color.parseColor("#333333"))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        })
        if (allSectors.isEmpty()) {
            container.addView(TextView(ctx).apply {
                text = "（尚无记录，请在下方新增）"
                textSize = 11f
                setTextColor(Color.parseColor("#999999"))
                setPadding(0, dp(4), 0, dp(12))
            })
        } else {
            val chipsFlow = FlowLayout(ctx).apply { setPadding(0, 0, 0, dp(8)) }
            for (sector in allSectors) {
                chipsFlow.addView(buildSectorChip(ctx, memory, sector.sectorName, sector.isActive))
            }
            container.addView(chipsFlow)
        }

        // 分隔线
        container.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply { topMargin = dp(8); bottomMargin = dp(16) }
            setBackgroundColor(Color.parseColor("#E0E0E0"))
        })

        // 新增关注板块
        container.addView(TextView(ctx).apply {
            text = "➕ 新增关注板块"
            textSize = 12f
            setTextColor(Color.parseColor("#E65100"))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        })
        val input = EditText(ctx).apply { hint = "输入板块名称（如：新能源）"; textSize = 12f }
        container.addView(input)
        container.addView(Button(ctx).apply {
            text = "✅ 新增"
            textSize = 11f
            setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isEmpty()) { Toast.makeText(ctx, "请输入板块名称", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                lifecycleScope.launch(Dispatchers.IO) {
                    memory.addOrActivateSector(name)
                    StrategyMarketContext.invalidateCache()
                    val updated = memory.getAllFocusSectors()
                    val ai = memory.aiYearDetection
                    val hot = memory.getRecentHotSectors().map { it.sectorName }
                    val news = memory.getRecentNewsSectors()
                    val suggested = (hot + news).distinct().filter { n -> updated.none { it.sectorName == n } }.take(8)
                    withContext(Dispatchers.Main) {
                        if (isAdded) buildMemoryDialogContent(ctx, memory, updated, suggested, ai)
                        Toast.makeText(ctx, "已新增「$name」", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })

        // 建议板块
        if (suggestedNames.isNotEmpty()) {
            container.addView(TextView(ctx).apply {
                text = "💡 近期热门但未记录的板块（点击新增）："
                textSize = 11f
                setTextColor(Color.parseColor("#888888"))
                setPadding(0, dp(12), 0, dp(4))
            })
            val suggestFlow = FlowLayout(ctx)
            for (name in suggestedNames) {
                val chip = TextView(ctx).apply {
                    text = "+ $name"
                    textSize = 11f
                    setPadding(dp(20), dp(8), dp(20), dp(8))
                    setTextColor(Color.parseColor("#555555"))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = 16f
                        setColor(Color.parseColor("#FFF3E0"))
                    }
                    setOnClickListener {
                        lifecycleScope.launch(Dispatchers.IO) {
                            memory.addOrActivateSector(name)
                            StrategyMarketContext.invalidateCache()
                            val updated = memory.getAllFocusSectors()
                            val ai = memory.aiYearDetection
                            val hot = memory.getRecentHotSectors().map { it.sectorName }
                            val news = memory.getRecentNewsSectors()
                            val suggested = (hot + news).distinct().filter { n -> updated.none { it.sectorName == n } }.take(8)
                            withContext(Dispatchers.Main) {
                                if (isAdded) buildMemoryDialogContent(ctx, memory, updated, suggested, ai)
                                Toast.makeText(ctx, "已新增「$name」", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                val lp = FlowLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, 0, dp(8), dp(8))
                chip.layoutParams = lp
                suggestFlow.addView(chip)
            }
            container.addView(suggestFlow)
        }

        AlertDialog.Builder(ctx)
            .setTitle("🧠 市场记忆设置")
            .setView(scrollView)
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun buildSectorChip(
        ctx: android.content.Context,
        memory: UserMarketMemory,
        name: String,
        isActive: Boolean
    ): TextView {
        return TextView(ctx).apply {
            text = if (isActive) "✓ $name" else "☐ $name"
            textSize = 11f
            setPadding(dp(20), dp(8), dp(20), dp(8))
            setTextColor(if (isActive) Color.WHITE else Color.parseColor("#999999"))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 16f
                setColor(if (isActive) Color.parseColor("#4CAF50") else Color.parseColor("#EEEEEE"))
            }
            setOnClickListener {
                val newActive = !isActive
                lifecycleScope.launch(Dispatchers.IO) {
                    memory.toggleSector(name, newActive)
                    StrategyMarketContext.invalidateCache()
                    val updated = memory.getAllFocusSectors()
                    val ai = memory.aiYearDetection
                    val hot = memory.getRecentHotSectors().map { it.sectorName }
                    val news = memory.getRecentNewsSectors()
                    val suggested = (hot + news).distinct().filter { n -> updated.none { it.sectorName == n } }.take(8)
                    withContext(Dispatchers.Main) {
                        if (isAdded) buildMemoryDialogContent(ctx, memory, updated, suggested, ai)
                    }
                }
            }
        }
    }

    // ═══════════════ ④ 📥 拉取股票报告 ═══════════════
    private fun showFetchReport() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val latestDates = db.dailySnapshotDao().getAvailableDates(20).sorted()
                if (latestDates.size < 2) { withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "需先导入数据", Toast.LENGTH_SHORT).show() }; return@launch }
                val sb = StringBuilder()
                sb.appendLine("📥 拉取股票报告 (最近 ${latestDates.size} 天)")
                sb.appendLine()
                for (date in latestDates.takeLast(5)) {
                    val snaps = db.dailySnapshotDao().getByDate(date)
                    sb.appendLine("━━━ $date ━━━")
                    sb.appendLine("  总股票数: ${snaps.size}")
                    val avgPct = snaps.map { it.changePct }.average().let { String.format("%.2f", it) }
                    val posCount = snaps.count { it.changePct > 0 }
                    sb.appendLine("  上涨数: $posCount / ${snaps.size} (${(posCount * 100.0 / snaps.size).let { "%.1f".format(it) }}%)")
                    sb.appendLine("  平均涨幅: ${avgPct}%")
                    val topGainers = snaps.sortedByDescending { it.changePct }.take(5)
                    sb.appendLine("  Top5涨幅: ${topGainers.joinToString { "${it.name}(${String.format("%.2f", it.changePct)}%)" }}")
                    sb.appendLine()
                }
                val prefs = requireContext().getSharedPreferences("data_import", android.content.Context.MODE_PRIVATE)
                val lastImport = prefs.getString("last_import_date", "从未")
                sb.appendLine("📅 上次导入: $lastImport")
                withContext(Dispatchers.Main) { AlertDialog.Builder(requireContext()).setTitle("拉取股票报告").setMessage(sb.toString()).setPositiveButton("关闭", null).show() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "加载失败: ${e.message}", Toast.LENGTH_SHORT).show() } }
        }
    }

    // ═══════════════ ⑤ ⬇️ 导入历史行情 ═══════════════
    private fun importHistoricalData() {
        importBtn.isEnabled = false; importBtn.text = "⏳"
        progressBar.visibility = View.VISIBLE
        val days = when (selectedHotPeriod) { 0->1; 1->3; 2->10; 3->30; 4->50; 5->100; else->60 }
        val label = when (selectedHotPeriod) { 0->"当日"; 1->"近3日"; 2->"近10日"; 3->"近30日"; 4->"近50日"; 5->"近100日"; else->"历史" }
        val useStartDate = browsingDate
        statusTv.text = "  正在从东方财富拉取 $browsingDate ~ 至今 的${label}K线..."
        lifecycleScope.launch {
            try {
                val f = HistoricalDataFetcher(requireContext())
                val t = f.fetchAllHistoricalData(days, force = true, startDateOverride = useStartDate) { p ->
                    lifecycleScope.launch(Dispatchers.Main) { statusTv.text = "  进度: ${p.completedStocks}/${p.totalStocks} 只 · ${p.totalRecords} 条" }
                }
                withContext(Dispatchers.Main) {
                    importBtn.isEnabled = true; importBtn.text = "⬇️ 开始导入"; progressBar.visibility = View.GONE
                    statusTv.text = "  ✅ 导入完成 · $t 条历史记录"
                    // 导入完成后展示当前热门板块
                    showHotSectorsReport()
                    val recent = TradingDayPickerView.recentTradingDay()
                    if (browsingDate != recent) { browsingDate = recent; datePicker.selectedDate = recent }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { importBtn.isEnabled = true; importBtn.text = "⬇️ 开始导入"; progressBar.visibility = View.GONE; statusTv.text = "  导入失败: ${e.message}" }
            }
        }
    }

    /** 导入完成后展示热门板块（最近 30 个交易日报告） */
    private fun showHotSectorsReport() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val recentDays = db.sectorDailyRecordDao().getRecentDays(30)
                if (recentDays.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "无热门板块数据（可先运行策略扫描）", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val sb = StringBuilder()
                sb.appendLine("🔥 当前热门板块（最近 30 个交易日）")
                sb.appendLine("导出时间: ${LocalDate.now()}"); sb.appendLine()
                val grouped = recentDays.groupBy { it.date }.toSortedMap()
                for ((date, sectors) in grouped) {
                    val hotCount = sectors.count { it.isHot in listOf("S", "Y", "true", "A") }
                    sb.appendLine("📅 $date（${sectors.size} 板块，${hotCount} 热门）")
                    for (s in sectors.sortedByDescending { it.hotScore }.take(10)) {
                        val tag = when (s.rank) { in 1..3 -> "🔥"; in 4..10 -> "⭐"; else -> "  " }
                        val hotLabel = when (s.isHot) {
                            "S", "Y", "true" -> "🔥热门"
                            "A" -> "⭐关注"
                            else -> ""
                        }
                        val hotSuffix = if (hotLabel.isNotEmpty()) " $hotLabel" else ""
                        val consecLabel = if (s.consecutiveHotDays > 0) " 连板${s.consecutiveHotDays}天" else ""
                        sb.appendLine("  $tag ${s.sectorName} 涨幅:${"%.2f".format(s.changePct)}% 主力:${"%.0f".format(s.mainNetInflow)}万 评分:${"%.1f".format(s.hotScore)}$consecLabel$hotSuffix")
                    }
                    sb.appendLine()
                }
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("🔥 热门板块报告")
                        .setMessage(sb.toString() as CharSequence)
                        .setPositiveButton("关闭", null)
                        .show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "热门板块加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ═══════════════ ⑦ 📤 导出K线快照 ═══════════════
    /** 按所选周期导出 N 日 K 线快照到 Downloads（CSV），移植自旧版策略 Tab */
    private fun exportSnapshotData() {
        val days = when (selectedHotPeriod) { 0->1; 1->3; 2->10; 3->30; 4->50; 5->100; else->1 }
        val label = when (selectedHotPeriod) { 0->"1日"; 1->"3日"; 2->"10日"; 3->"30日"; 4->"50日"; 5->"100日"; else->"当日" }
        statusTv.text = "  正在导出${label}K线数据..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val allDates = db.dailySnapshotDao().getAvailableDates(days + 5).sorted().takeLast(days)
                if (allDates.isEmpty()) {
                    withContext(Dispatchers.Main) { statusTv.text = "  ⚠️ 无可用日期数据"; Toast.makeText(requireContext(), "数据库中没有K线数据，请先导入", Toast.LENGTH_SHORT).show() }
                    return@launch
                }
                val sb = StringBuilder()
                sb.appendLine("stockCode,stockName,date,open,high,low,close,volume,amount,changePct,turnoverRate")
                var totalRows = 0
                for (date in allDates) {
                    try {
                        val snaps = db.dailySnapshotDao().getByDate(date)
                        for (snap in snaps) {
                            sb.appendLine("${snap.code},${snap.name},${snap.date},${snap.open},${snap.high},${snap.low},${snap.close},${snap.volume},${snap.amount},${snap.changePct},${snap.turnoverRate}")
                            totalRows++
                        }
                    } catch (_: Exception) {}
                }
                if (totalRows == 0) {
                    withContext(Dispatchers.Main) { statusTv.text = "  ⚠️ 无快照数据可导出"; Toast.makeText(requireContext(), "无数据可导出", Toast.LENGTH_SHORT).show() }
                    return@launch
                }
                val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val fileName = "StockAnalysis_snapshot_${label}_${LocalDate.now()}.csv"
                val file = java.io.File(dir, fileName)
                file.writeText(sb.toString())
                val sizeKb = file.length() / 1024
                withContext(Dispatchers.Main) {
                    statusTv.text = "  ✅ 已导出 ${allDates.size}天K线数据 (${totalRows}行, ${sizeKb}KB)"
                    Toast.makeText(requireContext(), "已保存到 Downloads/$fileName（$totalRows 行）", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTv.text = "  导出失败: ${e.message?.take(30)}"
                    Toast.makeText(requireContext(), "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
