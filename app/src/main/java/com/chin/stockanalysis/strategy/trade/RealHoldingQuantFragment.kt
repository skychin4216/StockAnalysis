package com.chin.stockanalysis.strategy.trade

import android.graphics.Color
import android.graphics.BitmapFactory
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.ai.StockEntityExtractor
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.stock.data.StockDataSourceFactory
import com.chin.stockanalysis.strategy.HoldingPeriod
import com.chin.stockanalysis.strategy.topology.xml.DagTradeExecutor
import com.chin.stockanalysis.ui.TradingDayPickerView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import android.webkit.WebView
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * ## 实仓管理 Tab — 用户真实持仓 + 大盘分析 Pipeline
 *
 * 拥有独立的 UseCase (real_holding) 和 DAG Pipeline：
 * - 大盘行情分析（冷/热/温和 + 板块轮动方向）
 * - 结论适用于所有持仓股票的评估
 *
 * ### 周期自动分类规则
 * - 持仓 ≤1 天 → 超短线
 * - 持仓 2-14 天 → 短线
 * - 持仓 15-180 天 → 中线
 * - 持仓 >180 天 → 长线
 */
class RealHoldingQuantFragment : QuantFragmentBase() {

    companion object {
        private const val TAG = "RealHolding"
    }

    /** 截图选择器（用于 OCR 导入） */
    private val screenshotPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { processScreenshotOcr(it) }
    }

    override fun getQuantType() = "RealHolding"

    override fun onFittingClick() {
        Toast.makeText(requireContext(), com.chin.stockanalysis.R.string.real_holding_no_fitting, Toast.LENGTH_SHORT).show()
    }

    override fun onBacktrackClick() {
        Toast.makeText(requireContext(), com.chin.stockanalysis.R.string.real_holding_no_backtrack, Toast.LENGTH_SHORT).show()
    }

    override fun onClearClick() {
        val items = arrayOf(
            "做T信号记录 (t_trade_records)",
            "做T推荐记录 (t_trade_recommendations)",
            "卖出评估缓存",
            "Pipeline 报告",
            "── 全部清空 ──"
        )
        AlertDialog.Builder(requireContext())
            .setTitle("🧹 清空实仓数据")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> clearTTradeRecords()
                    1 -> clearTTradeRecommendations()
                    2 -> clearSellCache()
                    3 -> clearPipelineReports()
                    4 -> clearAllRealPositionData()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun clearTTradeRecords() {
        AlertDialog.Builder(requireContext())
            .setTitle("🧹 清空做T信号记录")
            .setMessage("确定要清空所有做T执行记录吗？此操作不可撤销。")
            .setPositiveButton("确定") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val db = StockDatabase.getInstance(requireContext())
                        db.tTradeRecordDao().deleteAll()
                        withContext(Dispatchers.Main) {
                            statusTv.text = "✅ 做T信号记录已清空"
                            Toast.makeText(requireContext(), "做T信号记录已清空", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { statusTv.text = "❌ 清空失败: ${e.message?.take(40)}" }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun clearTTradeRecommendations() {
        AlertDialog.Builder(requireContext())
            .setTitle("🧹 清空做T推荐记录")
            .setMessage("确定要清空所有做T推荐信号吗？此操作不可撤销。")
            .setPositiveButton("确定") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val db = StockDatabase.getInstance(requireContext())
                        db.tTradeRecommendationDao().deleteAll()
                        withContext(Dispatchers.Main) {
                            statusTv.text = "✅ 做T推荐记录已清空"
                            Toast.makeText(requireContext(), "做T推荐记录已清空", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { statusTv.text = "❌ 清空失败: ${e.message?.take(40)}" }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun clearSellCache() {
        sellDecisionsCache = emptyList()
        statusTv.text = "✅ 卖出评估缓存已清空"
        Toast.makeText(requireContext(), "卖出评估缓存已清空", Toast.LENGTH_SHORT).show()
    }

    private fun clearPipelineReports() {
        AlertDialog.Builder(requireContext())
            .setTitle("🧹 清空Pipeline报告")
            .setMessage("确定要清空所有实仓Pipeline报告记录吗？")
            .setPositiveButton("确定") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val db = StockDatabase.getInstance(requireContext())
                        val entities = db.dailyPeriodResultDao().getRecent(1000)
                        for (e in entities) {
                            try { db.dailyPeriodResultDao().deleteByDate(e.tradeDate) } catch (_: Exception) {}
                        }
                        withContext(Dispatchers.Main) {
                            statusTv.text = "✅ Pipeline报告已清空"
                            Toast.makeText(requireContext(), "Pipeline报告已清空", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { statusTv.text = "❌ 清空失败: ${e.message?.take(40)}" }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun clearAllRealPositionData() {
        AlertDialog.Builder(requireContext())
            .setTitle("🧹 全部清空")
            .setMessage("确定要清空实仓所有数据吗？\n\n• 做T信号记录\n• 做T推荐记录\n• 卖出评估缓存\n• Pipeline报告\n\n此操作不可撤销！")
            .setPositiveButton("确定") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val db = StockDatabase.getInstance(requireContext())
                        db.tTradeRecordDao().deleteAll()
                        db.tTradeRecommendationDao().deleteAll()
                        val entities = db.dailyPeriodResultDao().getRecent(1000)
                        for (e in entities) {
                            try { db.dailyPeriodResultDao().deleteByDate(e.tradeDate) } catch (_: Exception) {}
                        }
                        withContext(Dispatchers.Main) {
                            sellDecisionsCache = emptyList()
                            statusTv.text = "✅ 实仓数据已全部清空"
                            Toast.makeText(requireContext(), "实仓数据已全部清空", Toast.LENGTH_SHORT).show()
                            refreshPositions()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { statusTv.text = "❌ 清空失败: ${e.message?.take(40)}" }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun initEngine() {
        super.initEngine()
    }

    /** 实仓持仓菜单：增加「清空实仓数据」选项 */
    override fun showHoldingMenu() {
        val items = arrayOf(
            "✏️ 编辑持仓",
            "💰 卖出/减仓",
            "📋 查看交易记录",
            "💰 查看持仓详情",
            "🧠 市场记忆设置",
            "🧹 清空持仓",
            "🧹 清空实仓数据 (做T/评估/报告)"
        )
        AlertDialog.Builder(requireContext())
            .setTitle("📦 持仓管理 — 实仓")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showEditRealPositionDialog()
                    1 -> showSellRealPositionDialog()
                    2 -> showTradeHistory()
                    3 -> loadPositions()
                    4 -> showMarketMemoryDialog()
                    5 -> confirmClearRealPositions()
                    6 -> onClearClick()
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 清空真实持仓（从 realPositionDao 删除，而非 strategyTradeOrderDao） */
    private fun confirmClearRealPositions() {
        AlertDialog.Builder(requireContext())
            .setTitle("🧹 清空真实持仓")
            .setMessage("确定要清空所有真实持仓记录吗？此操作不可撤销。")
            .setPositiveButton("确定") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val db = StockDatabase.getInstance(requireContext())
                        val positions = db.realPositionDao().getAllActive()
                        for (p in positions) {
                            db.realPositionDao().delete(p)
                        }
                        withContext(Dispatchers.Main) {
                            refreshPositions()
                            statusTv.text = "✅ 真实持仓已清空 (${positions.size} 只)"
                            Toast.makeText(requireContext(), "真实持仓已清空", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { statusTv.text = "❌ 清空失败: ${e.message?.take(40)}" }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 覆盖基类：实仓的「清空持仓」应清空 realPositionDao */
    override fun confirmAndClearPositions() = confirmClearRealPositions()

    override fun buildUI() {
        addTitleRow(getString(com.chin.stockanalysis.R.string.real_holding_title), textSize = 18f)
        rootLayout.addView(createProgressRow())
        rootLayout.addView(createButtonRow())
        addSeparator()
        rootLayout.addView(createContentScrollArea())
        refreshPositions()
    }

    /** 实仓的建仓按钮 → 弹出菜单：分析Pipeline / 手动添加 / 截图导入 */
    override fun onBuildClick(saveAsAiOnly: Boolean) {
        val items = arrayOf(
            getString(com.chin.stockanalysis.R.string.real_holding_pipeline),
            getString(com.chin.stockanalysis.R.string.real_holding_manual_add),
            getString(com.chin.stockanalysis.R.string.real_holding_ocr_import)
        )
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(com.chin.stockanalysis.R.string.real_holding_build_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> runRealHoldingPipeline()
                    1 -> showManualAddDialog()
                    2 -> screenshotPicker.launch("image/*")
                }
            }
            .show()
    }

    /** 执行实仓分析 Pipeline */
    private fun runRealHoldingPipeline() {
        runDagPipeline(
            holdingPeriod = HoldingPeriod.MID,
            useCaseId = "real_holding",
            orderType = "RealHolding",
            importDays = 120,
            titlePrefix = "实仓分析"
        )
    }

    /** Pipeline 按钮 → 显示流程图弹窗 */
    override fun openPipelineEditor() {
        val html = """
            <!DOCTYPE html>
            <html><head><meta charset="utf-8">
            <style>
              * { margin:0; padding:0; box-sizing:border-box; }
              body { background:#1a1a2e; font-family:'Microsoft YaHei',sans-serif; color:#e0e0e0; padding:12px; }
              h3 { text-align:center; font-size:14px; color:#90caf9; margin-bottom:12px; }
              .dag { display:flex; flex-direction:column; gap:8px; }
              .layer { display:flex; gap:8px; align-items:center; }
              .label { font-size:9px; color:#546e7a; min-width:50px; text-align:right; padding-right:6px; }
              .nodes { display:flex; gap:6px; flex-wrap:wrap; }
              .node { padding:6px 10px; border-radius:8px; font-size:11px; font-weight:600;
                      border:1px solid #334; min-width:90px; text-align:center; }
              .arrow { text-align:center; color:#455a64; font-size:16px; line-height:1; padding:2px 0 2px 50px; }
              .n-import { background:#2e7d32; border-color:#4caf50; color:#fff; }
              .n-market { background:#1565c0; border-color:#2196f3; color:#fff; }
              .n-news { background:#e65100; border-color:#ff9800; color:#fff; }
              .n-eval { background:#6a1b9a; border-color:#9c27b0; color:#fff; }
              .n-diag { background:#ad1457; border-color:#e91e63; color:#fff; }
              .n-predict { background:#00838f; border-color:#00bcd4; color:#fff; }
              .n-ttrade { background:#c62828; border-color:#f44336; color:#fff; }
              .desc { font-size:9px; color:#78909c; margin-top:8px; line-height:1.4; padding:0 50px; }
            </style></head><body>
            <h3>实仓分析 Pipeline v4</h3>
            <div class="dag">
              <div class="layer"><div class="label">L0 数据</div><div class="nodes">
                <div class="node n-import">数据导入(120天)</div>
                <div class="node n-import" style="opacity:.6">后台暂停</div>
              </div></div>
              <div class="arrow">↓ ↓</div>
              <div class="layer"><div class="label">L1 环境</div><div class="nodes">
                <div class="node n-market">大盘K线六维分析</div>
                <div class="node n-news">新闻力度(实时)</div>
              </div></div>
              <div class="arrow">↓ ↓ &nbsp;&nbsp; ↓ ↓</div>
              <div class="layer"><div class="label">L2 诊断</div><div class="nodes">
                <div class="node n-eval">逐股K线评估</div>
                <div class="node n-diag">持仓诊断<br><span style="font-size:9px;font-weight:normal">技术·风险·资金·板块</span></div>
              </div></div>
              <div class="arrow">↓</div>
              <div class="layer"><div class="label">L3 预测</div><div class="nodes">
                <div class="node n-predict">走势预测 + 场景分析<br><span style="font-size:9px;font-weight:normal">支撑/阻力·操作计划</span></div>
              </div></div>
              <div class="arrow">↓</div>
              <div class="layer"><div class="label">L4 操作</div><div class="nodes">
                <div class="node n-ttrade">做T/反T建议</div>
              </div></div>
            </div>
            <div class="desc">
              诊断维度: 技术健康分(MA排列+动量) + 风险分(止损距离+回撤) + 资金分(量价关系) + 板块集中度<br>
              预测输出: 短期趋势(看涨/震荡/看跌) + 支撑/阻力位 + 乐观/悲观场景 + 操作计划
            </div>
            </body></html>
        """.trimIndent()

        val webView = WebView(requireContext()).apply {
            settings.javaScriptEnabled = false
            loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("📊 实仓 Pipeline 流程")
            .setView(webView)
            .setPositiveButton("关闭", null)
            .setNeutralButton("执行 Pipeline") { _, _ -> runRealHoldingPipeline() }
            .show()
    }

    override fun refreshPositions() {
        android.util.Log.i(TAG, "🔄 refreshPositions START, isAdded=$isAdded")
        if (!isAdded) {
            android.util.Log.w(TAG, "⚠️ refreshPositions ABORT: fragment not added")
            return
        }
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            // ── 数据哈希比对：未变化则跳过重新渲染 ──
            val dataHash = withContext(Dispatchers.IO) {
                computeRealHoldingHash(ctx)
            }
            if (dataHash == lastRefreshHash) {
                android.util.Log.d(TAG, "⏭️ refreshPositions: 数据未变化，跳过重新渲染 (hash=$dataHash)")
                return@launch
            }
            lastRefreshHash = dataHash
            android.util.Log.d(TAG, "🔄 refreshPositions: 数据已变化，重新渲染 (hash=$dataHash)")

            android.util.Log.i(TAG, "🔄 refreshPositions: building report on Default dispatcher...")
            val report = withContext(Dispatchers.Default) {
                buildRealHoldingReport(ctx)
            }
            android.util.Log.i(TAG, "🔄 refreshPositions: report built, childCount=${report.childCount}, switching to Main")

            withContext(Dispatchers.Main) {
                if (!isAdded) {
                    android.util.Log.w(TAG, "⚠️ refreshPositions: fragment detached before UI update, abort")
                    return@withContext
                }
                android.util.Log.i(TAG, "🔄 refreshPositions: positionContainer BEFORE update, childCount=${positionContainer.childCount}")
                positionContainer.removeAllViews()
                positionContainer.addView(report)
                android.util.Log.i(TAG, "🔄 refreshPositions: positionContainer AFTER update, childCount=${positionContainer.childCount}")
                // 渲染完成后自动检测做T机会
                checkRealPositionTSignals()
                android.util.Log.i(TAG, "🔄 refreshPositions DONE")
            }
        }
    }

    /** 计算实仓数据哈希（orders + realPositions + picks） */
    private suspend fun computeRealHoldingHash(ctx: android.content.Context): Int {
        val db = StockDatabase.getInstance(ctx)
        val orders = withContext(Dispatchers.IO) {
            db.strategyTradeOrderDao().getRecent(500)
        }.filter { it.status == "BUYING" || it.status == "PENDING" || it.status == "HOLDING" }
        val realPositions = withContext(Dispatchers.IO) {
            db.realPositionDao().getAllActive()
        }
        val pickDate = TradingDayPickerView.recentTradingDay(browsingDate).format(DATE_FMT)
        val picks = withContext(Dispatchers.IO) {
            db.userWatchlistDao().getBySourceAndDate("RealHolding", pickDate)
        }
        var h = 17
        for (o in orders) { h = h * 31 + o.id.hashCode(); h = h * 31 + (o.stockCode ?: "").hashCode() }
        for (rp in realPositions) { h = h * 31 + rp.id.hashCode(); h = h * 31 + (rp.stockCode ?: "").hashCode() }
        for (p in picks) { h = h * 31 + (p.stockCode ?: "").hashCode(); h = h * 31 + (p.addedDate ?: "").hashCode() }
        return h
    }

    private suspend fun buildRealHoldingReport(ctx: android.content.Context): LinearLayout {
        android.util.Log.i(TAG, "📋 buildRealHoldingReport START")
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 4, 8, 4)
        }

        val db = StockDatabase.getInstance(ctx)

        // 只读取真实持仓（去重：同一 stockCode 只保留最新一笔）
        val allRealPositions = withContext(Dispatchers.IO) {
            db.realPositionDao().getAllActive()
        }
        android.util.Log.i(TAG, "📋 buildRealHoldingReport: DB raw — allRealPositions=${allRealPositions.size}")
        if (allRealPositions.isNotEmpty()) {
            for (rp in allRealPositions) {
                android.util.Log.d(TAG, "   DB row: id=${rp.id} code=${rp.stockCode} name=${rp.stockName} qty=${rp.quantity} price=${rp.avgBuyPrice} active=${rp.isActive}")
            }
        }
        val realPositions = allRealPositions
            .groupBy { it.stockCode.replace(Regex("^(sh|sz|bj)"), "") }
            .mapValues { (_, list) -> list.maxByOrNull { it.id }!! }
            .values.toList()
            .sortedByDescending { it.id }
        android.util.Log.i(TAG, "📊 buildRealHoldingReport: realPositions=${realPositions.size} (去重前${allRealPositions.size})")

        // 读取选股（Pipeline 选出的股票存在 user_watchlist）
        val pickDate = TradingDayPickerView.recentTradingDay(browsingDate).format(DATE_FMT)
        val picks = withContext(Dispatchers.IO) {
            db.userWatchlistDao().getBySourceAndDate("RealHolding", pickDate)
        }

        if (realPositions.isEmpty() && picks.isEmpty()) {
            android.util.Log.i(TAG, "📋 buildRealHoldingReport: all empty (realPositions/picks), showing placeholder")
            container.addView(TextView(ctx).apply {
                text = "暂无持仓记录\n\n点击「📈建仓」执行大盘分析 Pipeline\n点击「📦持仓」添加真实持仓"
                setTextColor(Color.GRAY)
                textSize = 14f
                setPadding(16, 32, 16, 32)
            })
            android.util.Log.i(TAG, "📋 buildRealHoldingReport DONE (placeholder), childCount=${container.childCount}")
            return container
        }

        // ── 将真实持仓转换为 StrategyTradeOrderEntity 格式，复用表格渲染 ──
        val orders = realPositions.map { rp ->
            val bareCode = rp.stockCode.replace(Regex("^(sh|sz|bj)"), "")
            StrategyTradeOrderEntity(
                id = rp.id,
                strategyId = "RealHolding",
                stockCode = bareCode,
                stockName = rp.stockName,
                tradeDate = rp.buyDate.ifEmpty { LocalDate.now().format(DATE_FMT) },
                buyPrice = rp.avgBuyPrice,
                buyTime = rp.buyDate.ifEmpty { LocalDate.now().format(DATE_FMT) },
                quantity = rp.quantity,
                orderType = "RealHolding",
                status = "BUYING",
                reason = "真实持仓",
                scoreAtBuy = 0
            )
        }

        // ── 获取日期列表和价格数据 ──
        val minTradeDate = orders.minByOrNull { it.tradeDate }?.tradeDate ?: browsingDate.format(DATE_FMT)
        val allDates = db.dailySnapshotDao().getAvailableDates(20)
        var dates = allDates
            .filter { it >= minTradeDate && it <= browsingDate.format(DATE_FMT) }
            .filter { dateStr ->
                try {
                    val d = java.time.LocalDate.parse(dateStr)
                    com.chin.stockanalysis.ui.TradingDayPickerView.isTradingDay(d)
                } catch (_: Exception) { false }
            }
            .sorted().takeLast(10)

        val priceMap = mutableMapOf<String, MutableMap<String, Double>>()
        for (date in dates) {
            val snaps = db.dailySnapshotDao().getByDate(date)
            for (snap in snaps) {
                priceMap.getOrPut(snap.code) { mutableMapOf() }[date] = snap.close
            }
        }

        // 实时行情补充
        val todayStr = browsingDate.format(DATE_FMT)
        val realtimeMap = try {
            com.chin.stockanalysis.stock.data.StockDataSourceFactory
                .createDefaultRepository(ctx)
                .getRealtime(orders.map { it.stockCode })
        } catch (_: Exception) { emptyMap() }
        if (realtimeMap.isNotEmpty()) {
            for ((code, rt) in realtimeMap) {
                if (rt.price > 0) {
                    priceMap.getOrPut(code) { mutableMapOf() }[todayStr] = rt.price
                }
            }
            if (todayStr !in dates) {
                dates.toMutableList().also {
                    it.add(todayStr); it.sort()
                }.let { dates = it }
            }
        }

        // ── 组合纪律卡片（机构化一期：单票上限/集中度/整体止损）──
        if (orders.isNotEmpty()) {
            addPortfolioDisciplineCard(container, ctx, orders, priceMap, dates)
        }
        // ── 真实持仓区（表格格式，参考其他周期） ──
        if (orders.isNotEmpty()) {
            android.util.Log.i(TAG, "📋 buildRealHoldingReport: rendering ${orders.size} real positions as table")
            buildOrderTableView(container, ctx, orders, dates, priceMap, "真实持仓", "#1565C0")
        }

        // ── 选股区（Pipeline 选出的股票） ──
        if (picks.isNotEmpty()) {
            container.addView(TextView(ctx).apply {
                text = "🎯 实仓选股 (${picks.size} 只)"
                setTextColor(Color.parseColor("#6A1B9A"))
                textSize = 14f
                setPadding(0, 12, 0, 4)
            })
            for (pick in picks) {
                container.addView(TextView(ctx).apply {
                    text = buildString {
                        append("▸ ${pick.stockName}(${pick.stockCode}) ")
                        append("评分${pick.scoreAtAdd}")
                        if (pick.notes.isNotEmpty()) append(" — ${pick.notes}")
                    }
                    setTextColor(Color.parseColor("#4A148C"))
                    textSize = 12f
                    setPadding(16, 2, 0, 2)
                })
            }
        }

        android.util.Log.i(TAG, "📋 buildRealHoldingReport DONE, childCount=${container.childCount}")
        return container
    }

    /**
     * 构建持仓表格视图（参考 QuantFragmentBase.renderOrderTable 格式）
     * 显示：标题行（总持仓 X 只 + 总盈亏）+ 多日价格表格
     */
    private fun buildOrderTableView(
        container: LinearLayout,
        ctx: android.content.Context,
        orders: List<StrategyTradeOrderEntity>,
        dates: List<String>,
        priceMap: Map<String, Map<String, Double>>,
        sectionTitle: String,
        titleColor: String
    ) {
        val lastDate = dates.lastOrNull() ?: browsingDate.format(DATE_FMT)
        var totalCost = 0.0; var totalValue = 0.0
        for (order in orders) {
            totalCost += order.buyPrice * order.quantity
            val lastPrice = priceMap[order.stockCode]?.get(lastDate) ?: order.buyPrice
            totalValue += lastPrice * order.quantity
        }
        val totalPnl = totalValue - totalCost
        val totalPnlPct = if (totalCost > 0) (totalPnl / totalCost * 100) else 0.0
        val pnlColor = if (totalPnl >= 0) "#D32F2F" else "#2E7D32"
        val pnlStr = "${if (totalPnl >= 0) "+" else ""}¥${"%.0f".format(totalPnl)} (${"%.2f".format(totalPnlPct)}%)"

        // 标题行
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 2, 0, 2)
        }
        titleRow.addView(TextView(ctx).apply {
            text = "📌 $sectionTitle"
            textSize = 12f; setTextColor(Color.parseColor(titleColor))
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(TextView(ctx).apply {
            text = "总持仓 ${orders.size} 只"
            textSize = 10f; setTextColor(Color.parseColor("#666666"))
            setPadding(0, 0, 6, 0)
        })
        titleRow.addView(TextView(ctx).apply {
            text = "清空"
            textSize = 10f; setTextColor(Color.parseColor("#C62828"))
            setPadding(0, 0, 6, 0)
            isClickable = true
            setOnClickListener { confirmClearRealPositions() }
        })
        titleRow.addView(TextView(ctx).apply {
            text = "总盈亏 $pnlStr"
            textSize = 10f; setTextColor(Color.parseColor(pnlColor))
            setPadding(0, 0, 4, 0)
        })
        titleRow.addView(TextView(ctx).apply {
            text = "🔄"; textSize = 14f
            setTextColor(Color.parseColor("#1976D2"))
            isClickable = true; setOnClickListener { refreshPositions() }
        })
        container.addView(titleRow)

        // 多日价格表格
        val scroll = android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val table = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        // 表头
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, 2, 0, 4)
            setBackgroundColor(Color.parseColor("#EEEEEE"))
        }
        for (header in listOf("股票", "买入日", "成本"))
            headerRow.addView(makeCell(ctx, header, 60, "#666666", 10f, bold = true))
        headerRow.addView(makeCell(ctx, "持仓", 45, "#666666", 9f, bold = true))
        for (date in dates)
            headerRow.addView(makeCell(ctx, date.takeLast(5), 72, "#666666", 10f, bold = true))
        table.addView(headerRow)

        // 每只股票一行
        for (order in orders) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, 2, 0, 2)
            }
            // 股票名/代码
            val nameCell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(dpToPx(60), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            nameCell.addView(TextView(ctx).apply {
                text = order.stockName; textSize = 9f
                setTextColor(Color.parseColor("#1A1A2E"))
                maxLines = 1; isSingleLine = true
            })
            nameCell.addView(TextView(ctx).apply {
                text = order.stockCode; textSize = 8f
                setTextColor(Color.parseColor("#999999"))
            })
            row.addView(nameCell)
            // 买入日
            row.addView(makeCell(ctx, order.tradeDate.takeLast(5), 60, "#333333", 9f))
            // 成本
            row.addView(makeCell(ctx, "¥${"%.1f".format(order.buyPrice)}", 60, "#333333", 9f))
            // 持仓天数
            val buyDate = try { java.time.LocalDate.parse(order.tradeDate) } catch (_: Exception) { java.time.LocalDate.now() }
            val daysHeld = java.time.temporal.ChronoUnit.DAYS.between(buyDate, java.time.LocalDate.now()).toInt().coerceAtLeast(0)
            row.addView(makeCell(ctx, "${daysHeld}天", 45, "#333333", 9f))
            // 每日价格
            for (date in dates) {
                val price = priceMap[order.stockCode]?.get(date)
                val prevIdx = dates.indexOf(date) - 1
                val prevPrice = if (prevIdx >= 0) priceMap[order.stockCode]?.get(dates[prevIdx]) else null
                val changeStr = if (price != null && prevPrice != null && prevPrice > 0) {
                    val chg = (price - prevPrice) / prevPrice * 100
                    "${if (chg >= 0) "+" else ""}${"%.1f".format(chg)}%"
                } else if (price != null) {
                    "¥${"%.1f".format(price)}"
                } else { "-" }
                val chgColor = when {
                    price == null -> "#999999"
                    prevPrice == null -> "#333333"
                    price > prevPrice -> "#D32F2F"
                    price < prevPrice -> "#2E7D32"
                    else -> "#333333"
                }
                row.addView(makeCell(ctx, changeStr, 72, chgColor, 9f))
            }
            table.addView(row)
        }
        scroll.addView(table)
        container.addView(scroll)
    }

    /** 构建表格单元格 */
    private fun makeCell(
        ctx: android.content.Context, text: String, widthDp: Int,
        color: String, textSize: Float, bold: Boolean = false
    ): TextView {
        return TextView(ctx).apply {
            this.text = text; this.textSize = textSize
            setTextColor(Color.parseColor(color))
            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
            val w = (widthDp * ctx.resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /**
     * 按持仓天数归类周期，边界与 [HoldingPeriod.holdingDays] 保持一致：
     * ULTRA_SHORT=1天、SHORT=2~29天、MID=30~180天、LONG=181天以上。
     */
    private fun classifyPeriod(daysHeld: Int): HoldingPeriod {
        return when {
            daysHeld <= 1 -> HoldingPeriod.ULTRA_SHORT
            daysHeld <= 29 -> HoldingPeriod.SHORT
            daysHeld <= 180 -> HoldingPeriod.MID
            else -> HoldingPeriod.LONG
        }
    }

    /**
     * 组合级风控纪律卡片（机构化一期）：与微信推送同一套阈值
     * 单票≤30%(占总持仓市值) / 前2大≤55% / 整体止损-8%。
     * 现价口径与表格一致：dates.lastDate 价（实时行情已并入 priceMap）。
     */
    private fun addPortfolioDisciplineCard(
        container: LinearLayout,
        ctx: android.content.Context,
        orders: List<StrategyTradeOrderEntity>,
        priceMap: Map<String, Map<String, Double>>,
        dates: List<String>
    ) {
        val maxSingle = 0.30
        val top2Max = 0.55
        val stopPct = -8.0
        val lastDate = dates.lastOrNull() ?: return
        val valid = orders.filter { it.quantity > 0 && it.buyPrice > 0 }
        if (valid.isEmpty()) return
        val curOf: (StrategyTradeOrderEntity) -> Double = { o ->
            priceMap[o.stockCode]?.get(lastDate) ?: o.buyPrice
        }
        val costMv = valid.sumOf { it.buyPrice * it.quantity }
        val curMv = valid.sumOf { curOf(it) * it.quantity }
        if (curMv <= 0 || costMv <= 0) return
        val pnlPct = (curMv / costMv - 1) * 100
        val ranked = valid.sortedByDescending { curOf(it) * it.quantity }
        val alerts = mutableListOf<String>()
        ranked.filter { curOf(it) * it.quantity / curMv > maxSingle }.take(3)
            .forEach {
                val ratio = curOf(it) * it.quantity / curMv * 100
                alerts.add("  🔴 %s(%s) 占持仓%.0f%% 超上限%d%% → 建议减至≤%d%%".format(
                    it.stockName, it.stockCode, ratio,
                    (maxSingle * 100).toInt(), (maxSingle * 100).toInt()))
            }
        if (valid.size >= 3) {
            val top2 = ranked.take(2).sumOf { curOf(it) * it.quantity }
            if (top2 / curMv > top2Max) {
                alerts.add("  🟠 前2大持仓占%.0f%% 超集中度上限%d%% → 建议分散至≥3只".format(
                    top2 / curMv * 100, (top2Max * 100).toInt()))
            }
        }
        if (pnlPct <= stopPct) {
            alerts.add("  🔻 组合整体浮亏%.1f%% 达整体止损%d%% → 建议降仓防守".format(
                pnlPct, (-stopPct).toInt()))
        }
        if (alerts.isEmpty()) {
            alerts.add("  ✅ 持仓%d只 整体%+.1f%% 无超限（单票≤%d%%/前2≤%d%%）".format(
                valid.size, pnlPct, (maxSingle * 100).toInt(), (top2Max * 100).toInt()))
        }
        val pnlColor = if (pnlPct >= 0) "#D32F2F" else "#2E7D32"
        container.addView(TextView(ctx).apply {
            text = "⚠️ 组合纪律  整体 %s%.1f%%".format(if (pnlPct >= 0) "+" else "", pnlPct)
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor(pnlColor))
            setPadding(0, 8, 0, 2)
        })
        container.addView(TextView(ctx).apply {
            text = alerts.joinToString("\n")
            textSize = 10.5f
            setTextColor(Color.parseColor("#455A64"))
            setPadding(8, 0, 0, 2)
        })
    }

    override fun getDefaultUseCaseId(): String = "real_holding"

    // ═══════════════════════════════════════
    // 手动添加持仓
    // ═══════════════════════════════════════

    /** 显示手动添加持仓对话框 */
    private fun showManualAddDialog() {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val form = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
        }
        scroll.addView(form)

        // 必填项标签（红色 * 标记）
        fun fieldLabel(labelText: String, required: Boolean = true): TextView = TextView(ctx).apply {
            text = if (required) "$labelText *" else labelText
            textSize = 12f
            setPadding(0, 4, 0, 2)
            if (required) setTextColor(Color.RED) else setTextColor(Color.parseColor("#666666"))
        }

        val codeInput = EditText(ctx).apply {
            hint = "股票代码（如 sh600519）"; inputType = android.text.InputType.TYPE_CLASS_TEXT
            setPadding(8, 8, 8, 8)
        }
        val nameInput = EditText(ctx).apply {
            hint = "股票名称（如 贵州茅台）"; inputType = android.text.InputType.TYPE_CLASS_TEXT
            setPadding(8, 8, 8, 8)
        }
        val qtyInput = EditText(ctx).apply {
            hint = "持有数量（股）"; inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(8, 8, 8, 8)
        }
        val priceInput = EditText(ctx).apply {
            hint = "买入均价（元）"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(8, 8, 8, 8)
        }
        val dateInput = EditText(ctx).apply {
            hint = "买入日期（yyyy-MM-dd）"; inputType = android.text.InputType.TYPE_CLASS_TEXT
            setText(LocalDate.now().toString())
            setPadding(8, 8, 8, 8)
        }
        val periodSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                listOf("未分类", "短线", "中线", "长线"))
        }

        form.addView(fieldLabel("股票代码"))
        form.addView(codeInput)
        form.addView(fieldLabel("股票名称"))
        form.addView(nameInput)
        form.addView(fieldLabel("持有数量"))
        form.addView(qtyInput)
        form.addView(fieldLabel("买入均价"))
        form.addView(priceInput)
        form.addView(fieldLabel("买入日期", required = false))
        form.addView(dateInput)
        form.addView(fieldLabel("持仓周期", required = false))
        form.addView(periodSpinner)
        form.addView(TextView(ctx).apply {
            text = "带 * 为必填项；仅填名称时自动匹配代码，匹配不到需手动填写代码"
            textSize = 10f
            setTextColor(Color.parseColor("#999999"))
            setPadding(0, 8, 0, 0)
        })

        val dialog = android.app.AlertDialog.Builder(ctx)
            .setTitle("✏️ 手动添加持仓")
            .setView(scroll)
            .setPositiveButton("添加", null)
            .setNegativeButton("取消", null)
            .create()

        // 使用 setOnShowListener 接管「添加」按钮，校验失败时保持对话框打开
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                var code = codeInput.text.toString().trim()
                val name = nameInput.text.toString().trim()
                val qty = qtyInput.text.toString().toIntOrNull() ?: 0
                val price = priceInput.text.toString().toDoubleOrNull() ?: 0.0
                val date = dateInput.text.toString().trim()
                val periodIdx = periodSpinner.selectedItemPosition
                val period = when (periodIdx) {
                    1 -> "ShortTermQuant"; 2 -> "MidTermQuant"
                    3 -> "LongTermQuant"; else -> ""
                }

                // 代码为空时尝试按名称自动解析
                if (code.isEmpty() && name.isNotEmpty()) {
                    code = StockEntityExtractor.resolveSync(name) ?: ""
                }

                // 必选项校验：代码/名称/数量/均价（日期、周期有默认值）
                val missing = mutableListOf<String>()
                if (code.isEmpty()) missing.add("股票代码")
                if (name.isEmpty()) missing.add("股票名称")
                if (qty <= 0) missing.add("持有数量")
                if (price <= 0.0) missing.add("买入均价")

                if (missing.isNotEmpty()) {
                    Toast.makeText(ctx, "请填写必选项: ${missing.joinToString("、")}", Toast.LENGTH_LONG).show()
                    return@setOnClickListener // 不关闭对话框，等待补齐
                }

                dialog.dismiss()
                saveRealPosition(code, name, qty, price, date, period)
            }
        }
        dialog.show()
    }

    /** 保存真实持仓到数据库 */
    private fun saveRealPosition(code: String, name: String, qty: Int, price: Double, date: String, period: String) {
        val ctx = requireContext().applicationContext
        android.util.Log.i(TAG, "📥 saveRealPosition: code=$code name=$name qty=$qty price=$price date=$date period=$period")
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(ctx)
                // 分配唯一 ID：避免 REPLACE 策略下 id=0 覆盖已有记录
                val nextId = db.realPositionDao().getMaxId() + 1
                android.util.Log.i(TAG, "📥 saveRealPosition: nextId=$nextId, inserting...")
                val entity = RealPositionEntity(
                    id = nextId,
                    stockCode = code, stockName = name, quantity = qty,
                    avgBuyPrice = price, buyDate = date, periodType = period
                )
                db.realPositionDao().insert(entity)
                android.util.Log.i(TAG, "📥 saveRealPosition: insert() returned, verifying...")
                val afterInsert = db.realPositionDao().getAllActive()
                android.util.Log.i(TAG, "📥 saveRealPosition: verify — DB now has ${afterInsert.size} active positions")
                withContext(Dispatchers.Main) {
                    if (!isAdded) {
                        android.util.Log.w(TAG, "⚠️ saveRealPosition: fragment detached, skip refresh")
                        return@withContext
                    }
                    Toast.makeText(ctx, "✅ 已添加 $name($code)", Toast.LENGTH_SHORT).show()
                    if (qty < 100) {
                        Toast.makeText(ctx, "⚠️ 底仓不足 100 股，做T引擎可能无信号", Toast.LENGTH_SHORT).show()
                    }
                    android.util.Log.i(TAG, "✅ saveRealPosition: calling refreshPositions()")
                    refreshPositions()
                }
                // 补齐该股日K快照（做T引擎要求 daily_snapshot ≥10 天，手工添加的股票往往无历史数据），
                // 完成后再次刷新触发做T检测
                ensureRealPositionDailyData(code, name)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ═══════════════════════════════════════
    // 截图 OCR 识别
    // ═══════════════════════════════════════

    /** AI 解析结果：区分成功、超时、获取失败 */
    private sealed class AiParseResult {
        data class Success(val positions: List<RealPositionEntity>) : AiParseResult()
        object Timeout : AiParseResult()
        object AcquireFailed : AiParseResult()
        data class ParseError(val msg: String) : AiParseResult()
    }

    /** 处理截图 OCR：识别文字 → AI 解析持仓 → 确认添加 */
    private fun processScreenshotOcr(uri: android.net.Uri) {
        val ctx = requireContext()
        statusTv.text = "🔄 正在识别截图..."

        try {
            val inputStream = ctx.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (bitmap == null) {
                statusTv.text = "❌ 无法读取图片"
                return
            }

            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    if (!isAdded) return@addOnSuccessListener
                    val rawText = visionText.text
                    android.util.Log.i(TAG, "OCR 原文:\n$rawText")

                    // 使用 AI 解析 OCR 文字
                    statusTv.text = "🤖 AI 正在解析持仓信息..."
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        val result = parseHoldingWithAi(rawText)

                        withContext(Dispatchers.Main) {
                            if (!isAdded) return@withContext
                            when (result) {
                                is AiParseResult.Success -> {
                                    android.util.Log.i(TAG, "AI 返回 ${result.positions.size} 只持仓")
                                    if (result.positions.isNotEmpty()) {
                                        showOcrConfirmDialog(result.positions)
                                    } else {
                                        // AI 返回空结果，尝试正则备选
                                        android.util.Log.i(TAG, "AI 返回空，尝试正则备选...")
                                        val fallback = parseHoldingFromOcr(rawText)
                                        android.util.Log.i(TAG, "正则备选结果: ${fallback.size} 只")
                                        if (fallback.isEmpty()) {
                                            statusTv.text = "⚠️ 未识别到持仓信息，请确保截图包含持仓数据"
                                            showOcrRawText(rawText)
                                        } else {
                                            showOcrConfirmDialog(fallback)
                                        }
                                    }
                                }
                                is AiParseResult.Timeout -> {
                                    statusTv.text = "⚠️ AI 解析超时，请重试"
                                    showAiRetryDialog(rawText)
                                }
                                is AiParseResult.AcquireFailed -> {
                                    statusTv.text = "⚠️ AI 服务繁忙，请稍后重试"
                                    showAiRetryDialog(rawText)
                                }
                                is AiParseResult.ParseError -> {
                                    // AI 解析失败，尝试正则备选
                                    android.util.Log.i(TAG, "AI 解析失败(${result.msg})，尝试正则备选...")
                                    val fallback = parseHoldingFromOcr(rawText)
                                    android.util.Log.i(TAG, "正则备选结果: ${fallback.size} 只")
                                    if (fallback.isEmpty()) {
                                        statusTv.text = "⚠️ 解析失败: ${result.msg}"
                                        showOcrRawText(rawText)
                                    } else {
                                        showOcrConfirmDialog(fallback)
                                    }
                                }
                            }
                        }
                    }
                }
                .addOnFailureListener { e ->
                    if (!isAdded) return@addOnFailureListener
                    statusTv.text = "❌ OCR 识别失败: ${e.message}"
                }
        } catch (e: Exception) {
            if (isAdded) statusTv.text = "❌ 图片处理失败: ${e.message}"
        }
    }

    /**
     * 使用 AI 从 OCR 文字中解析持仓信息。
     * AI 会理解表格结构，正确关联股票名称、代码、数量、价格等。
     */
    private suspend fun parseHoldingWithAi(ocrText: String): AiParseResult {
        val slot = com.chin.stockanalysis.ai.AiProviderPool.acquire(
            requireContext(),
            callerTag = "RealHoldingOCR",
            timeoutMs = 60_000L
        ) ?: return AiParseResult.AcquireFailed

        try {
            // 预处理 OCR 文字：去除明显的 UI 噪音
            val cleanedText = preprocessOcrText(ocrText)
            android.util.Log.i(TAG, "OCR 清理后:\n$cleanedText")

            val prompt = buildOcrPrompt(cleanedText)

            val response = withTimeoutOrNull(90_000L) {
                kotlinx.coroutines.suspendCancellableCoroutine<String> { cont ->
                    slot.provider.sendMessageStream(
                        messages = emptyList(),
                        systemPrompt = prompt,
                        onSuccess = {},
                        onComplete = { full -> cont.resumeWith(Result.success(full)) },
                        onError = { err -> cont.resumeWith(Result.failure(Exception(err))) }
                    )
                }
            }

            if (response == null) {
                android.util.Log.w(TAG, "AI 解析超时")
                return AiParseResult.Timeout
            }

            android.util.Log.i(TAG, "AI 解析结果: $response")

            // 解析 JSON 响应
            val positions = parseAiResponse(response)
            return AiParseResult.Success(positions)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "AI 解析失败: ${e.message}", e)
            return AiParseResult.ParseError(e.message ?: "未知错误")
        } finally {
            com.chin.stockanalysis.ai.AiProviderPool.releaseNonBlocking(slot)
        }
    }

    /**
     * 预处理 OCR 文字：去除券商 APP 截图中的 UI 噪音
     */
    private fun preprocessOcrText(raw: String): String {
        // 需要过滤的常见 UI 元素
        val noisePatterns = listOf(
            // 导航栏
            Regex("""(?m)^\s*(上证指数|深证成指|创业板指|沪深300|上证50|中证500|中证1000)\s*[\d,.]+\s*[-+]?[\d.]+%?\s*$"""),
            Regex("""(?m)^\s*(自选|市场|发现|理财|资讯|交易|我的)\s*$"""),
            Regex("""(?m)^\s*(涨|跌|平|Q刷新|更多)\s*$"""),
            // 状态栏
            Regex("""(?m)^\s*(Wifi|4G|5G|LTE|📶|🔋|📱)\s*"""),
            // 时间格式（通常是 HH:mm）
            Regex("""(?m)^\s*\d{1,2}:\d{2}\s*$"""),
            // 纯百分比（无股票代码关联）
            Regex("""(?m)^\s*[-+]?\d+\.\d+%\s*$"""),
            // 底部导航
            Regex("""(?m)^\s*(园|资讯|涨跌)\s*$"""),
        )

        var cleaned = raw
        for (pattern in noisePatterns) {
            cleaned = pattern.replace(cleaned, "")
        }

        // 去除连续空行
        cleaned = cleaned.replace(Regex("""\n{3,}"""), "\n\n")

        // 去除每行首尾空白
        cleaned = cleaned.lines().joinToString("\n") { it.trim() }

        return cleaned
    }

    /**
     * 构建高质量的 OCR 解析 Prompt
     */
    private fun buildOcrPrompt(cleanedOcrText: String): String = buildString {
        appendLine("## 任务：从券商APP截图OCR文字中提取持仓信息")
        appendLine()
        appendLine("### 背景")
        appendLine("这是从券商APP截图中通过OCR识别出的文字。由于OCR限制，文字可能：")
        appendLine("- 原本在表格同一行的数据被拆成多行")
        appendLine("- 股票名称、代码、数量、价格分散在不同位置")
        appendLine("- 包含一些无关的UI文字（已过滤大部分）")
        appendLine()
        appendLine("### 提取规则")
        appendLine("1. **股票代码**：6位数字，如 601168、000037、300750")
        appendLine("   - 6开头 → 上海(sh)")
        appendLine("   - 0或3开头 → 深圳(sz)")
        appendLine("   - 4或8开头 → 北京(bj)")
        appendLine("2. **股票名称**：通常是2-4个中文字，与代码相邻")
        appendLine("3. **数量**：通常是100的整数倍（如100、500、1000、2400）")
        appendLine("4. **价格**：带小数点的数字（如43.07、154.78）")
        appendLine("5. **持仓周期**：如「4天1板」「3天1板」表示持仓天数和涨停次数")
        appendLine()
        appendLine("### 关联逻辑")
        appendLine("- 股票名称和代码通常相邻出现")
        appendLine("- 数量（整数）和价格（小数）通常在同一区域")
        appendLine("- 涨跌幅百分比不是价格，不要混淆")
        appendLine("- 「X天Y板」是持仓统计，不是价格")
        appendLine()
        appendLine("### OCR文字（已清理）")
        appendLine("---")
        appendLine(cleanedOcrText)
        appendLine("---")
        appendLine()
        appendLine("### 输出格式")
        appendLine("严格返回JSON数组，不要有任何其他文字：")
        appendLine("""[{"code":"601168","name":"西部矿业","quantity":1000,"price":43.07}]""")
        appendLine()
        appendLine("如果某个字段无法确定：")
        appendLine("- quantity 默认 100")
        appendLine("- price 根据上下文合理推断")
        appendLine("- name 用代码代替")
    }

    /**
     * 解析 AI 返回的 JSON 响应（可能混杂思考过程）
     */
    private fun parseAiResponse(response: String): List<RealPositionEntity> {
        val results = mutableListOf<RealPositionEntity>()

        // 策略1：尝试逐个提取 JSON 对象（处理 AI 返回多个 JSON 片段的情况）
        val objRegex = Regex("""\{\s*"code"\s*:\s*"([^"]*)"[^}]*"name"\s*:\s*"([^"]*)"[^}]*"quantity"\s*:\s*(\d+)[^}]*"price"\s*:\s*([\d.]+)[^}]*\}""")
        val objMatches = objRegex.findAll(response)
        for (match in objMatches) {
            val rawCode = match.groupValues[1]
            val name = match.groupValues[2]
            val quantity = match.groupValues[3].toIntOrNull() ?: continue
            val price = match.groupValues[4].toDoubleOrNull() ?: continue
            val codeMatch = Regex("""(\d{6})""").find(rawCode) ?: continue
            val code = codeMatch.groupValues[1]
            if (code.length != 6) continue
            val fullCode = normalizeStockCode(code)
            if (quantity > 0 && price > 0) {
                if (results.none { it.stockCode == fullCode }) {
                    results.add(RealPositionEntity(
                        stockCode = fullCode, stockName = name,
                        quantity = quantity, avgBuyPrice = price,
                        buyDate = LocalDate.now().toString()
                    ))
                }
            }
        }
        if (results.isNotEmpty()) {
            android.util.Log.i(TAG, "AI 响应解析成功（逐对象提取）: ${results.size} 只")
            return results
        }

        // 策略2：尝试提取完整 JSON 数组
        try {
            val jsonMatch = Regex("""\[[\s\S]*?]""").find(response)
            val jsonStr = jsonMatch?.value ?: response
            val jsonArray = org.json.JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val rawCode = obj.optString("code", "")
                val name = obj.optString("name", "未知")
                val quantity = obj.optInt("quantity", 0)
                val price = obj.optDouble("price", 0.0)
                val codeMatch = Regex("""(\d{6})""").find(rawCode) ?: continue
                val code = codeMatch.groupValues[1]
                if (code.length != 6) continue
                val fullCode = normalizeStockCode(code)
                if (quantity > 0 && price > 0) {
                    if (results.none { it.stockCode == fullCode }) {
                        results.add(RealPositionEntity(
                            stockCode = fullCode, stockName = name,
                            quantity = quantity, avgBuyPrice = price,
                            buyDate = LocalDate.now().toString()
                        ))
                    }
                }
            }
            if (results.isNotEmpty()) {
                android.util.Log.i(TAG, "AI 响应解析成功（JSON数组）: ${results.size} 只")
                return results
            }
        } catch (_: Exception) { }

        android.util.Log.w(TAG, "AI 响应无法提取 JSON，将使用正则备选")
        return results
    }

    /**
     * 为股票代码添加交易所前缀
     */
    private fun normalizeStockCode(code: String): String {
        return when {
            code.startsWith("6") -> "sh$code"
            code.startsWith("0") || code.startsWith("3") -> "sz$code"
            code.startsWith("4") || code.startsWith("8") -> "bj$code"
            else -> "sh$code"
        }
    }

    /**
     * 从 OCR 文字中直接解析持仓信息（不依赖 AI）
     *
     * 券商 APP 截图 OCR 特点：
     * - 股票名称+代码可能在同一行或不同行
     * - 价格（现价区）和代码（持仓区）分散在不同区域
     * - 大盘指数价格混在中间
     *
     * 策略：
     * 1. 提取所有6位股票代码及关联名称
     * 2. 定位「交易/现价」区域，提取该区域的价格
     * 3. 按位置顺序匹配代码与价格
     */
    private fun parseHoldingFromOcr(text: String): List<RealPositionEntity> {
        val results = mutableListOf<RealPositionEntity>()

        // ── Step 1: 提取所有股票代码（按出现顺序）──
        val codeRegex = Regex("""(\d{6})""")
        val allCodes = mutableListOf<String>()
        val seenCodes = mutableSetOf<String>()
        for (m in codeRegex.findAll(text)) {
            val code = m.groupValues[1]
            if (code.length == 6
                && !code.all { it == code[0] }  // 排除 000000, 111111 等
                && !seenCodes.contains(code)
            ) {
                seenCodes.add(code)
                allCodes.add(code)
            }
        }
        if (allCodes.isEmpty()) {
            android.util.Log.w(TAG, "正则解析：未找到股票代码")
            return results
        }
        android.util.Log.i(TAG, "正则解析：找到 ${allCodes.size} 个代码: $allCodes")

        // ── Step 2: 为每个代码关联名称 ──
        val codeNameMap = mutableMapOf<String, String>()
        val lines = text.lines()
        for (line in lines) {
            val codeMatch = codeRegex.find(line) ?: continue
            val code = codeMatch.groupValues[1]
            if (code.length != 6 || code.all { it == code[0] }) continue
            if (!seenCodes.contains(code)) continue

            // 同行中查找中文名称
            val before = line.substring(0, codeMatch.range.first)
            val after = line.substring(codeMatch.range.last + 1)
            val nameBefore = Regex("""[\u4e00-\u9fa5]{2,4}""").find(before)?.value
            val nameAfter = Regex("""[\u4e00-\u9fa5]{2,4}""").find(after)?.value
            val name = (nameBefore ?: nameAfter)?.let {
                // 过滤常见噪音词
                if (it in listOf("融通", "自选", "持仓", "精选", "高评分", "金叉", "涨跌幅", "现价", "交易", "首页")) null else it
            }
            if (name != null && !codeNameMap.containsKey(code)) {
                codeNameMap[code] = name
            }
        }

        // 对于没有名称的代码，尝试在前面的行中查找
        for (i in lines.indices) {
            val codeMatch = codeRegex.find(lines[i]) ?: continue
            val code = codeMatch.groupValues[1]
            if (code.length != 6 || !seenCodes.contains(code)) continue
            if (codeNameMap.containsKey(code)) continue

            // 向上查找最近的中文名称
            for (j in (i - 1) downTo maxOf(0, i - 3)) {
                val nameMatch = Regex("""([\u4e00-\u9fa5]{2,4})""").find(lines[j])
                val name = nameMatch?.groupValues?.get(1)
                if (name != null && name !in listOf("融通", "自选", "持仓", "精选", "高评分", "金叉", "涨跌幅", "现价", "交易", "首页", "上证指数", "深证成指", "创业板指", "沪深300")) {
                    codeNameMap[code] = name
                    break
                }
            }
        }

        // ── Step 3: 定位价格区域 ──
        // 券商截图中，「交易」「现价」标记后面的数字是股票价格
        // 大盘指数价格在上方区域
        val priceSectionStart = findPriceSectionStart(lines)
        val priceSection = if (priceSectionStart >= 0) text.substring(priceSectionStart) else text
        val allPrices = Regex("""(\d+\.\d{1,2})""")
            .findAll(priceSection)
            .map { it.groupValues[1].toDouble() }
            .filter { it > 1.0 && it < 10000.0 }
            .toList()

        // 取最后 N 个价格（N = 代码数量），因为股票价格通常在文字最后
        val stockPrices = if (allPrices.size >= allCodes.size) {
            allPrices.takeLast(allCodes.size)
        } else {
            allPrices
        }
        android.util.Log.i(TAG, "正则解析：价格区域提取 ${allPrices.size} 个，取 ${stockPrices.size} 个匹配代码")

        // ── Step 4: 按位置匹配构建结果 ──
        for (i in allCodes.indices) {
            val code = allCodes[i]
            val fullCode = normalizeStockCode(code)
            val name = codeNameMap[code] ?: "未知"
            val price = stockPrices.getOrNull(i) ?: continue

            if (results.none { it.stockCode == fullCode }) {
                results.add(RealPositionEntity(
                    stockCode = fullCode,
                    stockName = name,
                    quantity = 100,  // 默认数量
                    avgBuyPrice = price,
                    buyDate = LocalDate.now().toString()
                ))
            }
        }

        android.util.Log.i(TAG, "正则解析结果: ${results.size} 只持仓")
        return results
    }

    /**
     * 定位价格区域的起始位置
     * 券商截图中，股票价格通常在「交易」「现价」「客户号」等标记之后
     */
    private fun findPriceSectionStart(lines: List<String>): Int {
        // 在行中查找价格区域标记
        val markers = listOf("客户号", "客户号", "交易", "现价", "现价")
        var charOffset = 0
        for (line in lines) {
            for (marker in markers) {
                if (line.contains(marker)) {
                    return charOffset
                }
            }
            charOffset += line.length + 1  // +1 for newline
        }

        // 如果找不到标记，尝试用大盘指数位置来估算
        // 找到最后一个指数关键词的位置，之后的文字视为价格区域
        val indexMarkers = listOf("创业极指", "创业板指", "创业板指", "沪深300", "沪深300", "中证500", "中证500")
        var lastIdxPos = -1
        charOffset = 0
        for (line in lines) {
            for (marker in indexMarkers) {
                if (line.contains(marker)) {
                    lastIdxPos = charOffset + line.length + 1
                }
            }
            charOffset += line.length + 1
        }
        return if (lastIdxPos > 0) lastIdxPos else -1
    }

    /**
     * 用网络实时行情数据增强 OCR 解析结果。
     * - 补充 PE、换手率、当前价等字段（OCR 现价经常不准，用 API 现价替换 currentPrice）
     * - 同时更新 stockName（以 API 返回的为准）
     *
     * 注意：avgBuyPrice 是**持仓成本价**，来自 OCR 截图，不能被 API 现价覆盖，
     * 否则后续统计盈亏会把成本当成现价，导致利润恒为 0。
     */
    private suspend fun enrichWithRealtimeData(positions: List<RealPositionEntity>): List<RealPositionEntity> {
        if (positions.isEmpty()) return positions
        val ctx = requireContext()
        val codes = positions.map { it.stockCode }
        android.util.Log.i(TAG, "📡 enrichWithRealtimeData: 获取 ${codes.size} 只股票实时行情: $codes")

        return try {
            val realtimeMap = withContext(Dispatchers.IO) {
                StockDataSourceFactory.createDefaultRepository(ctx).getRealtime(codes)
            }
            android.util.Log.i(TAG, "📡 enrichWithRealtimeData: 获取到 ${realtimeMap.size} 只行情")

            positions.map { p ->
                val rt = realtimeMap[p.stockCode]
                if (rt != null && rt.price > 0) {
                    android.util.Log.i(TAG, "📡 ${p.stockName}(${p.stockCode}): 成本=${p.avgBuyPrice} 现价=${rt.price}, PE=${rt.pe}, 换手率=${rt.turnoverRate}")
                    p.copy(
                        currentPrice = rt.price,
                        pe = rt.pe,
                        turnoverRate = rt.turnoverRate,
                        stockName = if (rt.name.isNotBlank()) rt.name else p.stockName
                    )
                } else {
                    android.util.Log.w(TAG, "⚠️ ${p.stockName}(${p.stockCode}): 未获取到行情，保留OCR数据")
                    p
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ enrichWithRealtimeData 失败: ${e.message}", e)
            positions
        }
    }

    /** 显示 OCR 识别结果确认对话框 */
    private fun showOcrConfirmDialog(positions: List<RealPositionEntity>) {
        if (!isAdded) return
        val ctx = requireContext()
        android.util.Log.i(TAG, "📋 showOcrConfirmDialog: ${positions.size} 只待确认")
        for (p in positions) {
            android.util.Log.i(TAG, "   → ${p.stockName}(${p.stockCode}) id=${p.id} qty=${p.quantity} price=${p.avgBuyPrice} date=${p.buyDate} active=${p.isActive}")
        }

        // 先获取实时行情增强数据，再显示确认框
        statusTv.text = "📡 正在获取实时行情..."
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val enriched = enrichWithRealtimeData(positions)

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                showOcrConfirmDialogInner(enriched)
            }
        }
    }

    /** 显示确认对话框（内部，数据已增强） */
    private fun showOcrConfirmDialogInner(positions: List<RealPositionEntity>) {
        val ctx = requireContext()
        android.util.Log.i(TAG, "📋 showOcrConfirmDialogInner: ${positions.size} 只（已增强）")

        val msg = buildString {
            appendLine("识别到 ${positions.size} 只持仓（已获取实时行情）：\n")
            for (p in positions) {
                appendLine("  ${p.stockName}(${p.stockCode})")
                append("    ${p.quantity}股 ¥${"%.2f".format(p.avgBuyPrice)}")
                if (p.pe > 0) append("  PE ${"%.1f".format(p.pe)}")
                if (p.turnoverRate > 0) append("  换手 ${"%.2f".format(p.turnoverRate)}%")
                appendLine()
            }
            appendLine("\n确认添加？")
        }

        android.app.AlertDialog.Builder(ctx)
            .setTitle("📷 截图识别结果")
            .setMessage(msg)
            .setPositiveButton("确认添加") { _, _ ->
                android.util.Log.i(TAG, "✅ 用户确认添加 ${positions.size} 只持仓")
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val db = StockDatabase.getInstance(ctx)
                        // 读取现有持仓，建立 bareCode → entity 映射
                        val existingAll = db.realPositionDao().getAllActive()
                        android.util.Log.i(TAG, "📥 insertAll: existing active positions=${existingAll.size}")
                        val existingMap = existingAll
                            .groupBy { it.stockCode.replace(Regex("^(sh|sz|bj)"), "") }
                            .mapValues { (_, list) -> list.maxByOrNull { it.id }!! }

                        var updatedCount = 0
                        var insertedCount = 0
                        val newPositions = mutableListOf<RealPositionEntity>()

                        for (p in positions) {
                            val bareCode = p.stockCode.replace(Regex("^(sh|sz|bj)"), "")
                            val existing = existingMap[bareCode]
                            if (existing != null) {
                                // 已有持仓 → 直接更新数量和价格 + 行情数据
                                android.util.Log.i(TAG, "📥 updateQuantity: id=${existing.id} ${p.stockName}($bareCode) oldQty=${existing.quantity} → newQty=${p.quantity} price=${p.avgBuyPrice}")
                                db.realPositionDao().updateQuantity(
                                    existing.id, p.quantity, p.avgBuyPrice
                                )
                                if (p.currentPrice > 0 || p.pe > 0 || p.turnoverRate > 0) {
                                    db.realPositionDao().updateMarketData(
                                        existing.id, p.currentPrice, p.pe, p.turnoverRate
                                    )
                                }
                                updatedCount++
                            } else {
                                // 新持仓 → 加入待插入列表
                                android.util.Log.i(TAG, "📥 newPosition queued: ${p.stockName}($bareCode) qty=${p.quantity} price=${p.avgBuyPrice}")
                                newPositions.add(p)
                                insertedCount++
                            }
                        }

                        // 批量插入新持仓
                        if (newPositions.isNotEmpty()) {
                            val baseId = db.realPositionDao().getMaxId()
                            android.util.Log.i(TAG, "📥 insertAll: baseId=$baseId, inserting ${newPositions.size} new positions")
                            val uniquePositions = newPositions.mapIndexed { i, p ->
                                val newId = baseId + i + 1
                                android.util.Log.i(TAG, "   → insert[$i]: id=$newId ${p.stockName}(${p.stockCode}) qty=${p.quantity}")
                                p.copy(id = newId)
                            }
                            db.realPositionDao().insertAll(uniquePositions)
                            android.util.Log.i(TAG, "📥 insertAll: insertAll() returned, verifying...")

                            // 验证写入是否成功
                            val afterInsert = db.realPositionDao().getAllActive()
                            android.util.Log.i(TAG, "📥 insertAll: verify — DB now has ${afterInsert.size} active positions (was ${existingAll.size})")
                        }

                        android.util.Log.i(TAG, "✅ DB write complete: updated=${updatedCount}, inserted=${insertedCount}, calling refreshPositions()")

                        withContext(Dispatchers.Main) {
                            if (!isAdded) {
                                android.util.Log.w(TAG, "⚠️ insertAll: fragment detached before UI refresh, skip")
                                return@withContext
                            }
                            val summary = buildString {
                                if (updatedCount > 0) append("更新${updatedCount}只")
                                if (updatedCount > 0 && insertedCount > 0) append("，")
                                if (insertedCount > 0) append("新增${insertedCount}只")
                                if (isEmpty()) append("无变更")
                            }
                            statusTv.text = "✅ $summary"
                            Toast.makeText(ctx, "✅ $summary", Toast.LENGTH_SHORT).show()
                            android.util.Log.i(TAG, "✅ calling refreshPositions() from insertAll callback")
                            refreshPositions()
                        }
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "❌ 插入失败: ${e.message}", e)
                        withContext(Dispatchers.Main) {
                            if (!isAdded) return@withContext
                            Toast.makeText(ctx, "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("查看原文") { _, _ ->
                // 可选：显示原始 OCR 文字供用户核对
            }
            .show()
    }

    /** 显示 OCR 原始文字（用于调试或识别失败时） */
    private fun showOcrRawText(text: String) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("OCR 识别原文")
            .setMessage(text.take(2000))
            .setPositiveButton("确定", null)
            .show()
    }

    /** AI 解析失败/超时时显示重试对话框 */
    private fun showAiRetryDialog(rawOcrText: String) {
        if (!isAdded) return
        val ctx = requireContext()
        android.app.AlertDialog.Builder(ctx)
            .setTitle("AI 解析失败")
            .setMessage("AI 服务繁忙或超时，无法解析持仓信息。\n\n您可以：\n• 重试：再次调用 AI 解析\n• 查看原文：查看 OCR 识别的文字")
            .setPositiveButton("重试") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    statusTv.text = "🤖 重新解析中..."
                    val result = parseHoldingWithAi(rawOcrText)
                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext
                        when (result) {
                            is AiParseResult.Success -> {
                                if (result.positions.isNotEmpty()) {
                                    showOcrConfirmDialog(result.positions)
                                } else {
                                    statusTv.text = "⚠️ AI 未识别到持仓"
                                    showOcrRawText(rawOcrText)
                                }
                            }
                            is AiParseResult.Timeout, is AiParseResult.AcquireFailed -> {
                                statusTv.text = "⚠️ 重试仍然超时，请稍后再试"
                                Toast.makeText(ctx, "AI 服务暂时不可用，请稍后再试", Toast.LENGTH_LONG).show()
                            }
                            is AiParseResult.ParseError -> {
                                statusTv.text = "⚠️ 解析失败: ${result.msg}"
                                showOcrRawText(rawOcrText)
                            }
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("查看原文") { _, _ ->
                showOcrRawText(rawOcrText)
            }
            .show()
    }
}
