package com.chin.stockanalysis.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource
import com.chin.stockanalysis.stock.data.sources.EastMoneySectorSource
import com.chin.stockanalysis.stock.data.sources.SinaSectorSource
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.data.IntradayKlineFetcher
import com.chin.stockanalysis.strategy.gsp.GspEngine
import com.chin.stockanalysis.strategy.gsp.SectorResonanceEngine
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * ## 🕸 板块共振 Tab — GSP 图信号处理
 *
 * 方案一（ETF 锚定网络）：板块综合指数为锚节点 → 去 β 残差相关建图 → 拉普拉斯热核扩散 τ
 * → 共模/残差分离 → 补涨排序（跟随度 + 未兑现度 + 价格位置）。
 * 方案二（龙头驱动扩散）：度中心性 + 拐点/动量 识别龙头 → 打脉冲看扩散场 → 良性/孤龙/混乱分类
 * → 接力池（响应靠后 + 共振强 + 位置低）。
 *
 * ⚠️ GSP 只做板块状态观测与补涨排序，买卖点仍用原有 SSA / 半分位 / 拐点规则。
 */
class SectorResonanceFragment : Fragment() {

    private val RED = Color.parseColor("#EF5350")
    private val GREEN = Color.parseColor("#43A047")
    private val ORANGE = Color.parseColor("#FB8C00")
    private val BLUE = Color.parseColor("#1E88E5")
    private val TEAL = Color.parseColor("#26A69A")
    private val PURPLE = Color.parseColor("#8E24AA")
    private val GRAY = Color.parseColor("#757575")
    private val DARK = Color.parseColor("#212121")
    private val LIGHT = Color.parseColor("#F7F7F7")

    private val fetcher by lazy { IntradayKlineFetcher(requireContext()) }

    private var plan = 0                    // 0=方案一 ETF锚定；1=方案二 龙头扩散
    private var window = 40                 // 相关性窗口
    private var order = 5                   // 切比雪夫阶数
    private var diffuseT = 2.0              // 热核扩散 t
    private var topN = 30                   // 成分股数量
    private var leaderMode = 0              // 龙头判定：0=拐点型；1=动量型

    private lateinit var poolBox: LinearLayout
    private lateinit var detailBox: LinearLayout
    private lateinit var statusTv: TextView
    private var planA: TextView? = null
    private var planB: TextView? = null
    private var leaderChipA: TextView? = null
    private var leaderChipB: TextView? = null
    private val cache = HashMap<String, BoardCache>()

    // ── 逐K回测状态 ──
    private var replayJob: kotlinx.coroutines.Job? = null
    private var replayStocks: List<SectorResonanceEngine.Stock>? = null
    private var replayBench: DoubleArray? = null
    private var replayDates: List<String>? = null
    private var replayBoardCode = ""
    private var replayBoardName = ""

    override fun onDestroyView() {
        replayJob?.cancel(); replayJob = null
        super.onDestroyView()
    }

    private data class BoardCache(
        val name: String, val state: String, val tau: Double,
        val breadth: Double, val at: String
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(16))
            setBackgroundColor(LIGHT)
        }
        scroll.addView(root)

        root.addView(tv("🕸 板块共振（GSP 图信号处理）", DARK, 15f, true))
        root.addView(tv("成分股按去 β 残差相关建图 → 图拉普拉斯 + 切比雪夫滤波 + 热核扩散。" +
            "判断板块处于 萌芽/共振/分化/衰竭，并排序「跟住主线但残差尚未兑现」的补涨个股。" +
            "GSP 只做状态与排序，买卖点仍走原有 K 线规则。", GRAY, 10f))

        // 方案切换
        val planRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(4))
        }
        planA = planChip("方案一 · ETF锚定")
        planB = planChip("方案二 · 龙头扩散")
        planRow.addView(planA, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginEnd = dp(6) })
        planRow.addView(planB, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(planRow)
        planA!!.setOnClickListener { plan = 0; paintPlan() }
        planB!!.setOnClickListener { plan = 1; paintPlan() }
        paintPlan()

        // 龙头判定口径切换
        val leaderRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(4))
        }
        leaderChipA = planChip("🎯 龙头·拐点型（跌后拐头）")
        leaderChipB = planChip("🚀 龙头·动量型（5日最强）")
        leaderRow.addView(leaderChipA, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginEnd = dp(6) })
        leaderRow.addView(leaderChipB, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(leaderRow)
        leaderChipA!!.setOnClickListener { leaderMode = 0; paintPlan() }
        leaderChipB!!.setOnClickListener { leaderMode = 1; paintPlan() }
        paintPlan()

        // 参数
        val paramRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        paramRow.addView(paramCell("相关窗口", window.toString()) { window = it.toInt().coerceIn(10, 120) })
        paramRow.addView(paramCell("切比阶数", order.toString()) { order = it.toInt().coerceIn(2, 10) })
        paramRow.addView(paramCell("扩散 t", diffuseT.toString()) { diffuseT = it.coerceIn(0.2, 10.0) })
        paramRow.addView(paramCell("成分数", topN.toString()) { topN = it.toInt().coerceIn(10, 60) })
        root.addView(paramRow)

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(2))
        }
        val btnScan = Button(ctx).apply {
            text = "🔄 扫描板块池"; textSize = 12f; setTextColor(Color.WHITE); setBackgroundColor(TEAL)
        }
        val btnBack = Button(ctx).apply {
            text = "← 返回板块池"; textSize = 12f
        }
        btnRow.addView(btnScan, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f)
            .apply { marginEnd = dp(6) })
        btnRow.addView(btnBack, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(btnRow)

        statusTv = tv("点「扫描板块池」加载行业/概念板块", GRAY, 11f)
        root.addView(statusTv)

        poolBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        detailBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        root.addView(poolBox)
        root.addView(detailBox)

        root.addView(tv("⚠️ 相关性在极端行情会整体漂移（牛熊拉满）；扩散 t 为超参，建议按板块回测调参；" +
            "结果不构成投资建议。", GRAY, 9f))

        btnScan.setOnClickListener { scanBoards() }
        btnBack.setOnClickListener {
            detailBox.visibility = View.GONE
            detailBox.removeAllViews()
            poolBox.visibility = View.VISIBLE
        }

        loadCache()
        return scroll
    }

    // ════════════════════════ 板块池 ════════════════════════

    /** 板块清单项：local=true 走本地 sector_stocks（离线可用）；sina=true 走新浪行业榜（东财不可达兜底）；否则东财实时榜 */
    private data class BoardItem(
        val key: String, val name: String, val type: String,
        val changePercent: Double = 0.0, val change5d: Double = 0.0, val change10d: Double = 0.0,
        val mainInflowYi: Double = 0.0, val local: Boolean = false, val compCount: Int = 0,
        val sina: Boolean = false, val leaderName: String = ""
    )

    private fun scanBoards() {
        lifecycleScope.launch {
            // ① 本地板块池（sector_stocks 存量映射，离线秒出——东财 clist 不可达时的主用路径）
            val local = withContext(Dispatchers.IO) {
                runCatching {
                    val db = StockDatabase.getInstance(requireContext())
                    db.sectorStockDao().getAllSectorKeys().mapNotNull { key ->
                        val name = db.sectorStockDao().getSectorName(key) ?: key
                        val cnt = db.sectorStockDao().countBySector(key)
                        if (cnt >= 5) BoardItem(key = key, name = name, type = "本地", local = true, compCount = cnt) else null
                    }.sortedByDescending { it.compCount }
                }.getOrDefault(emptyList())
            }
            if (local.isNotEmpty()) {
                renderBoards(local, "✅ 本地板块池 ${local.size} 个（离线可用，点卡片做 GSP 分析）")
            } else {
                statusTv.text = "⏳ 本地板块池为空，尝试东财实时榜…"
            }
            // ② 后台静默试东财实时榜一次，成功则替换为带行情的实时榜
            val src = EastMoneyHotSectorSource()
            val remote = withContext(Dispatchers.IO) {
                val ind = src.fetchSectorsByTypeDirect(2, 8)
                val con = src.fetchSectorsByTypeDirect(3, 6)
                (ind.map { it to "行业" } + con.map { it to "概念" }).distinctBy { it.first.code }
            }
            if (remote.isNotEmpty()) {
                renderBoards(remote.map { (b, t) ->
                    BoardItem(b.code, b.name, t, b.changePercent, b.change5d, b.change10d, b.mainNetInflow / 1e8)
                }, "✅ ${remote.size} 个板块（东财实时榜，点卡片做 GSP 分析）")
            } else {
                // ③ 新浪行业榜兜底（东财不可达时，按当日涨跌幅排序=当前热门）
                val sina = withContext(Dispatchers.IO) { SinaSectorSource().fetchIndustryBoards() }
                if (sina.isNotEmpty()) {
                    renderBoards(sina.take(40).map {
                        BoardItem(it.node, it.name, "行业·新浪", it.changePct, compCount = it.count,
                            sina = true, leaderName = it.leaderName)
                    }, "✅ ${sina.size} 个行业板块（新浪源兜底·按今日涨跌排序，点卡片做 GSP 分析）")
                } else if (local.isEmpty()) {
                    statusTv.text = "❌ 板块清单拉取失败：东财 ${src.lastError ?: "不可达"}；新浪亦不可达；本地 sector_stocks 为空"
                }
            }
        }
    }

    private fun renderBoards(items: List<BoardItem>, status: String) {
        poolBox.removeAllViews()
        poolBox.visibility = View.VISIBLE
        statusTv.text = status
        items.forEach { poolBox.addView(boardCard(it)) }
    }

    private fun boardCard(item: BoardItem): View {
        val ctx = requireContext()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded("#FFFFFF", 0)
        }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(tv("${item.type} · ${item.name}", DARK, 13f, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val c = cache[item.key]
        val badge = TextView(ctx).apply {
            text = c?.let { stateLabel(it.state) } ?: "待分析"
            textSize = 10f; setTextColor(Color.WHITE)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = rounded(stateColor(c?.state), 8)
        }
        head.addView(badge)
        box.addView(head)

        if (item.local) {
            box.addView(tv("本地板块 · 成分 ${item.compCount} 只（市值等权）", GRAY, 11f))
        } else if (item.sina) {
            val chg = item.changePercent
            box.addView(tv("涨跌 %+.2f%%   成分 ${item.compCount} 只   领涨 %s".format(
                chg, item.leaderName), if (chg >= 0) RED else GREEN, 11f))
        } else {
            val chg = item.changePercent
            val chgColor = if (chg >= 0) RED else GREEN
            box.addView(tv("涨跌 %+.2f%%   5日 %+.1f%%   10日 %+.1f%%   主力 %+.2f亿".format(
                chg, item.change5d, item.change10d, item.mainInflowYi), chgColor, 11f))
        }
        box.addView(tv(if (c != null) "τ=%.2f  广度 %.0f%%  分析于 %s".format(c.tau, c.breadth * 100, c.at)
            else "点击做 GSP 图分析（拉取 $topN 只成分股日线，首次较慢）", GRAY, 9f))

        box.setOnClickListener { analyzeBoard(item.key, item.name, item.local, item.sina) }

        return LinearLayout(ctx).apply {
            setPadding(0, 0, 0, dp(6)); addView(box)
        }
    }

    // ════════════════════════ 板块 GSP 分析 ════════════════════════

    /** 成分股三元组（本地/东财统一） */
    private data class Comp(val code: String, val name: String, val mcap: Double)

    private fun analyzeBoard(bkCode: String, boardName: String, local: Boolean = false, sina: Boolean = false) {
        lifecycleScope.launch {
            detailBox.removeAllViews()
            detailBox.visibility = View.VISIBLE
            poolBox.visibility = View.GONE
            statusTv.text = "⏳ $boardName 拉取成分股…"

            val comps: List<Comp> = withContext(Dispatchers.IO) {
                when {
                    local -> {
                        // 本地路径：sector_stocks 取成分代码（带 sh/sz 前缀），stock_basics 补名
                        // ⚠️ stock_basics.code 可能是裸 6 位 → 双格式一起查，命中哪个用哪个
                        runCatching {
                            val db = StockDatabase.getInstance(requireContext())
                            val codes = db.sectorStockDao().getStockCodesBySector(bkCode).take(topN)
                            val basics = db.stockBasicDao().getByCodes(codes + codes.map { it.takeLast(6) })
                            val nameMap = HashMap<String, String>(basics.size * 2)
                            basics.forEach { b -> nameMap[b.code] = b.name }
                            codes.map { Comp(it, nameMap[it] ?: nameMap[it.takeLast(6)] ?: it, 0.0) }
                        }.getOrDefault(emptyList())
                    }
                    sina -> runCatching {
                        SinaSectorSource().fetchComponents(bkCode, topN)
                            .map { Comp(it.symbol, it.name, it.mktcapYi) }
                    }.getOrDefault(emptyList())
                    else -> runCatching {
                        EastMoneySectorSource().fetchSectorComponents(
                            bkCode, topN = topN, excludeKcb = false, excludeCyb = false
                        ).map { Comp(it.code, it.name, it.marketCapBillion.toDouble()) }
                    }.getOrDefault(emptyList())
                }
            }
            if (comps.size < 5) {
                statusTv.text = "❌ 成分股拉取不足（${comps.size} 只），无法建图"
                return@launch
            }

            val benchCode = "sh000300"
            val bench = withContext(Dispatchers.IO) {
                runCatching { fetcher.fetchDailyHistory(benchCode, 200, preferCache = true) }
                    .getOrNull()?.takeIf { it.size >= 60 }
                    ?: runCatching { fetcher.fetchDailyHistory("sh000001", 200, preferCache = true) }.getOrNull()
            }
            if (bench == null || bench.size < 60) {
                statusTv.text = "❌ 大盘基准（沪深300/上证）拉取失败，无法去 β"
                return@launch
            }

            // 成分股日线：分批并发，节流 6 路
            var done = 0
            val stocks = ArrayList<SectorResonanceEngine.Stock>(comps.size)
            comps.chunked(6).forEach { chunk ->
                val part = coroutineScope {
                    chunk.map { s ->
                        async(Dispatchers.IO) {
                            val bars = runCatching {
                                fetcher.fetchDailyHistory(s.code, 200, preferCache = true)
                            }.getOrNull().orEmpty()
                            SectorResonanceEngine.Stock(
                                code = s.code, name = s.name,
                                close = bars.map { it.close }.toDoubleArray(),
                                marketCap = s.mcap
                            )
                        }
                    }.awaitAll()
                }
                stocks.addAll(part)
                done += chunk.size
                statusTv.text = "⏳ $boardName 行情 $done/${comps.size}…"
            }

            statusTv.text = "⏳ 建图 + 切比雪夫滤波 + 热核扩散…"
            val dates = bench.map { it.datetime.take(10) }
            val benchClose = bench.map { it.close }.toDoubleArray()
            val res = withContext(Dispatchers.Default) {
                runCatching {
                    SectorResonanceEngine.analyze(
                        boardCode = bkCode, boardName = boardName,
                        stocks = stocks, benchmarkClose = benchClose, dates = dates,
                        window = window, order = order, diffuseT = diffuseT,
                        leaderMode = leaderMode
                    )
                }.getOrNull()
            }
            if (res == null) {
                statusTv.text = "❌ 分析失败：有效成分股或 K 线不足（需 ≥5 只、每只 ≥60 根）"
                return@launch
            }
            // 存回放数据（逐K回测用）
            replayJob?.cancel()
            replayStocks = stocks; replayBench = benchClose; replayDates = dates
            replayBoardCode = bkCode; replayBoardName = boardName
            statusTv.text = "✅ $boardName 完成（${res.nStocks} 只 × ${res.nBars} 根）"
            renderDetail(res)
            saveCache(res)
        }
    }

    private fun renderDetail(r: SectorResonanceEngine.Result) {
        val ctx = requireContext()
        detailBox.addView(sectionTitle("${r.boardName}  ${r.boardCode}", r.state))

        // 概览
        val info = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded("#FFFFFF", 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }
        info.addView(tv("状态：${r.state.label}   扩散衰减 τ = %.2f   λmax = %.2f   共模广度 %.0f%%".format(
            r.tau, r.lambdaMax, r.breadth * 100), stateColor(r.state.name), 12f, true))
        info.addView(tv("板块综合指数（市值加权）：20日斜率 %+.3f%%/日，%s；EMA20 %s".format(
            r.anchorSlope * 100,
            if (r.anchorTrendUp) "低频趋势向上" else "低频趋势未确认",
            if (r.anchorNorm.last() > r.anchorEma.last()) "上方" else "下方"), DARK, 10f))
        info.addView(tv("扩散 τ 越大 → 脉冲传得远、共振越强；广度 = 共模向上成分股占比", GRAY, 9f))
        detailBox.addView(info)

        // 板块指数图
        detailBox.addView(tv("① 板块综合指数（蓝，归一100）vs EMA20（橙虚线）", DARK, 11f, true))
        val chart = LineChart(ctx).apply {
            description.isEnabled = false
            setTouchEnabled(true); isDragEnabled = true; setScaleEnabled(true)
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            axisRight.isEnabled = false
        }
        detailBox.addView(chart, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(180)
        ))
        renderAnchorChart(chart, r)

        // 网络热力图（方案二重点；方案一也显示共振分布）
        detailBox.addView(tv("② 板块网络热力（节点=成分股，大小/亮度=扩散能量，红圈=龙头）", DARK, 11f, true)
            .apply { setPadding(0, dp(8), 0, dp(2)) })
        detailBox.addView(GspNetworkView(ctx, r), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(240)
        ))
        detailBox.addView(tv("计算按 2026-10 口径：残差相关窗口 $window 日、切比雪夫 ${order} 阶、扩散 t=$diffuseT；" +
            "图只反映共振结构，不代表买卖点。", GRAY, 9f))

        // 候选池
        if (plan == 0) {
            detailBox.addView(tv("③ 补涨候选（共模跟随 + 残差未兑现 + 价格位置）", DARK, 11f, true)
                .apply { setPadding(0, dp(8), 0, dp(2)) })
            r.ranked.take(12).forEachIndexed { i, c -> detailBox.addView(candidateRow(i + 1, c, false)) }
        } else {
            detailBox.addView(tv("③ 龙头与扩散类型", DARK, 11f, true)
                .apply { setPadding(0, dp(8), 0, dp(2)) })
            val diffLabel = r.diffusion?.label ?: "—"
            val diffColor = when (r.diffusion) {
                GspEngine.DiffusionType.BENIGN -> GREEN
                GspEngine.DiffusionType.MALIGNANT -> RED
                GspEngine.DiffusionType.CHAOTIC -> ORANGE
                null -> GRAY
            }
            detailBox.addView(tv("鼓槌敲击点：${r.leaderName}（${r.leaderCode}）   扩散：$diffLabel",
                diffColor, 12f, true))
            detailBox.addView(tv("龙头口径：${if (r.leaderMode == 1) "动量型（5日最强 + 联动强）" else "拐点型（跌后拐头 + 联动强）"}",
                GRAY, 9f))
            detailBox.addView(tv(if (r.diffusion == GspEngine.DiffusionType.MALIGNANT)
                "⚠️ 孤龙行情：能量卡在龙头自身，其余节点无响应 → 不追跟风"
            else "接力池按「响应靠后 + 共振强度 + 价格位置」排序", GRAY, 9f))
            detailBox.addView(tv("④ 接力池", DARK, 11f, true)
                .apply { setPadding(0, dp(8), 0, dp(2)) })
            r.relay.take(12).forEachIndexed { i, c -> detailBox.addView(candidateRow(i + 1, c, true)) }
        }

        // ── ⑤ 逐K回测：以每根 K 线为终点重算网络，观察 龙头更替/状态切换/能量扩散 演化 ──
        detailBox.addView(tv("⑤ 逐K回测（以每根K线为终点重算热力图，看结构随时间演化）", DARK, 11f, true)
            .apply { setPadding(0, dp(10), 0, dp(2)) })
        val replayPanel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(8))
            background = rounded("#FFFFFF", 0)
        }
        val btnRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val btnPlay = Button(ctx).apply { text = "▶ 播放"; textSize = 11f }
        val btnPause = Button(ctx).apply { text = "⏸ 暂停"; textSize = 11f }
        val btnReset = Button(ctx).apply { text = "⏮ 重置"; textSize = 11f }
        // 播放速度：慢速 700ms/帧（默认，便于观察颜色演化）→ 中速 450 → 快速 250 循环
        var replayDelay = 700L
        val speedSteps = listOf(700L to "慢速", 450L to "中速", 250L to "快速")
        var speedIdx = 0
        val btnSpeed = Button(ctx).apply { text = "⏱ 慢速"; textSize = 11f }
        btnSpeed.setOnClickListener {
            speedIdx = (speedIdx + 1) % speedSteps.size
            replayDelay = speedSteps[speedIdx].first
            btnSpeed.text = "⏱ ${speedSteps[speedIdx].second}"
        }
        val progTv = tv("${replayDates?.size ?: 0} 根可回放", GRAY, 10f).apply { setPadding(dp(6), 0, 0, 0) }
        btnRow.addView(btnPlay); btnRow.addView(btnPause); btnRow.addView(btnReset); btnRow.addView(btnSpeed)
        btnRow.addView(progTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        replayPanel.addView(btnRow)
        val frameTv = tv("播放后显示每帧：状态 / τ / 广度 / 龙头", GRAY, 10f)
        replayPanel.addView(frameTv)
        val netView = GspNetworkView(ctx, r)
        replayPanel.addView(netView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(240)))
        val logTv = tv("", GRAY, 9f)
        val logScroll = ScrollView(ctx).apply { addView(logTv) }
        replayPanel.addView(logScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(100)))
        detailBox.addView(replayPanel)

        var replayPaused = false
        fun stopReplay() {
            replayJob?.cancel(); replayJob = null
            replayPaused = false; btnPause.text = "⏸ 暂停"; btnPlay.text = "▶ 播放"
            netView.r = r   // 恢复最新帧
        }
        btnPause.setOnClickListener {
            if (replayJob == null) return@setOnClickListener
            replayPaused = !replayPaused
            btnPause.text = if (replayPaused) "▶ 继续" else "⏸ 暂停"
        }
        btnReset.setOnClickListener {
            stopReplay(); progTv.text = "${replayDates?.size ?: 0} 根可回放"
            frameTv.text = "播放后显示每帧：状态 / τ / 广度 / 龙头"; logTv.text = ""
        }
        btnPlay.setOnClickListener {
            if (replayJob != null) { stopReplay(); return@setOnClickListener }
            val stocksAll = replayStocks ?: return@setOnClickListener
            val bench = replayBench ?: return@setOnClickListener
            val dts = replayDates ?: return@setOnClickListener
            // 与引擎同款过滤：≥60 根、剔除 ST，避免短历史票把回放长度拖到个位数
            val stocks = stocksAll.filter { it.close.size >= 60 && !it.name.contains("ST", true) }
            if (stocks.size < 5) { progTv.text = "有效票不足（${stocks.size} 只，需≥5）"; return@setOnClickListener }
            val m0 = minOf(stocks.minOf { it.close.size }, bench.size, dts.size)
            if (m0 < 90) { progTv.text = "数据不足（$m0 根，需≥90）"; return@setOnClickListener }
            // 尾部对齐后逐帧切片（analyze 内部 lookback=140 即滚动窗口）
            val closesFull = stocks.map { it.close.takeLast(m0).toDoubleArray() }
            val benchFull = bench.takeLast(m0).toDoubleArray()
            val datesFull = dts.takeLast(m0)
            val startIdx = 80
            btnPlay.text = "⏹ 停止"
            logTv.text = ""
            var prevLeader: String? = null
            var prevState: String? = null
            replayJob = lifecycleScope.launch {
                var idx = startIdx
                while (isActive && idx < m0) {
                    while (isActive && replayPaused) kotlinx.coroutines.delay(120)
                    if (!isActive) break
                    val frame = withContext(Dispatchers.Default) {
                        runCatching {
                            val sub = stocks.mapIndexed { i, s ->
                                SectorResonanceEngine.Stock(s.code, s.name, closesFull[i].copyOfRange(0, idx + 1), s.marketCap)
                            }
                            SectorResonanceEngine.analyze(
                                boardCode = replayBoardCode, boardName = replayBoardName,
                                stocks = sub, benchmarkClose = benchFull.copyOfRange(0, idx + 1),
                                dates = datesFull.subList(0, idx + 1),
                                window = window, order = order, diffuseT = diffuseT,
                                leaderMode = leaderMode
                            )
                        }.getOrNull()
                    }
                    if (frame != null) {
                        netView.r = frame
                        progTv.text = "${idx + 1}/$m0"
                        // 日期 + 状态（着色）+ 趋势方向，一眼确认状态是否随时间变化
                        frameTv.text = "📅 %s   状态 %s%s   τ=%.2f   广度 %.0f%%   龙头 %s".format(
                            datesFull[idx], frame.state.label,
                            if (frame.anchorTrendUp) "" else "·趋势↓",
                            frame.tau, frame.breadth * 100, frame.leaderName ?: "—")
                        frameTv.setTextColor(stateColor(frame.state.name))
                        // 日志倒序：最新事件打印在最上面
                        if (prevLeader != null && frame.leaderCode != prevLeader)
                            logTv.text = "⟿ ${datesFull[idx]} 龙头切换 → ${frame.leaderName}\n${logTv.text}"
                        if (prevState != null && frame.state.name != prevState)
                            logTv.text = "◆ ${datesFull[idx]} 状态 → ${frame.state.label}\n${logTv.text}"
                        prevLeader = frame.leaderCode; prevState = frame.state.name
                    }
                    idx++
                    kotlinx.coroutines.delay(replayDelay)
                }
                if (idx >= m0) { replayJob = null; btnPlay.text = "▶ 播放" }
            }
        }
    }

    private fun candidateRow(rank: Int, c: SectorResonanceEngine.Candidate, relay: Boolean): View {
        val ctx = requireContext()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rounded("#FFFFFF", 0)
        }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(tv("%d".format(rank), TEAL, 12f, true).apply {
            setPadding(dp(2), 0, dp(6), 0)
        })
        head.addView(tv("${c.name} ${c.code}", DARK, 12f, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val chgColor = if (c.changePct >= 0) RED else GREEN
        head.addView(tv("%.2f %+.2f%%".format(c.price, c.changePct), chgColor, 11f))
        head.addView(tv("  %.0f分".format(c.score * 100), BLUE, 11f, true))
        box.addView(head)
        box.addView(tv(if (relay)
            "共模%+.3f%%/日   未兑现分位 %.0f%%   位置 %.0f%%   能量 %.0f%%   %s".format(
                c.commonFollow, c.specPercentile * 100, c.position * 100, c.energy * 100, c.tag)
        else
            "共模%+.3f%%/日   未兑现分位 %.0f%%   位置 %.0f%%   %s".format(
                c.commonFollow, c.specPercentile * 100, c.position * 100, c.tag), GRAY, 9f))
        return LinearLayout(ctx).apply {
            setPadding(0, 0, 0, dp(5)); addView(box)
        }
    }

    private fun renderAnchorChart(chart: LineChart, r: SectorResonanceEngine.Result) {
        val x = r.anchorNorm.indices.map { Entry(it.toFloat(), r.anchorNorm[it].toFloat()) }
        val e = r.anchorEma.indices.map { Entry(it.toFloat(), r.anchorEma[it].toFloat()) }
        val d1 = LineDataSet(x, "综合指数").apply {
            color = BLUE; setDrawCircles(false); lineWidth = 1.6f; setDrawValues(false)
        }
        val d2 = LineDataSet(e, "EMA20").apply {
            color = ORANGE; setDrawCircles(false); lineWidth = 1.2f; setDrawValues(false)
            enableDashedLine(6f, 4f, 0f)
        }
        chart.data = LineData(d1, d2)
        chart.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String =
                r.dates.getOrNull(value.toInt())?.takeLast(5).orEmpty()
        }
        chart.xAxis.granularity = 1f
        chart.legend.textSize = 9f
        chart.invalidate()
    }

    // ════════════════════════ 网络热力图 ════════════════════════

    private class GspNetworkView(ctx: android.content.Context, r: SectorResonanceEngine.Result) : View(ctx) {
        /** 帧数据（逐K回测时换帧 + invalidate） */
        var r: SectorResonanceEngine.Result = r
            set(v) { field = v; invalidate() }
        private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.parseColor("#EF5350")
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#212121"); textSize = 24f
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val n = r.candidates.size
            if (n == 0) return
            val cx = width / 2f
            val cy = height / 2f - 6f
            val rad = minOf(width, height) * 0.34f
            val pt = FloatArray(n * 2)
            for (i in 0 until n) {
                val th = -Math.PI / 2 + 2 * Math.PI * i / n
                pt[i * 2] = cx + rad * Math.cos(th).toFloat()
                pt[i * 2 + 1] = cy + rad * Math.sin(th).toFloat()
            }
            // 边：权重越大 → 颜色越红、线越粗（灰 → 橙 → 深红），逐帧随相关性演化变色
            for ((a, b, w) in r.edges) {
                if (a >= n || b >= n) continue
                val t = ((w - 0.25) / 0.75).coerceIn(0.0, 1.0)
                edgePaint.color = blend3(
                    Color.parseColor("#CFD8DC"), Color.parseColor("#FFA726"), Color.parseColor("#D81B60"), t
                )
                edgePaint.alpha = (110 + t * 130).toInt()
                edgePaint.strokeWidth = 1f + (t * 3.2f).toFloat()
                canvas.drawLine(pt[a * 2], pt[a * 2 + 1], pt[b * 2], pt[b * 2 + 1], edgePaint)
            }
            // 节点
            for (i in 0 until n) {
                val c = r.candidates[i]
                val e = c.energy.coerceIn(0.0, 1.0)
                val nr = radius(e)
                val isLeader = c.code == r.leaderCode
                nodePaint.color = if (isLeader) Color.parseColor("#EF5350")
                else blend(Color.parseColor("#B0BEC5"), Color.parseColor("#1E88E5"), e)
                canvas.drawCircle(pt[i * 2], pt[i * 2 + 1], nr, nodePaint)
                if (isLeader) canvas.drawCircle(pt[i * 2], pt[i * 2 + 1], nr + 3f, ringPaint)
            }
            // 龙头名字
            r.leaderName?.let {
                canvas.drawText("龙头 $it", 10f, height - 10f, textPaint)
            }
        }

        private fun radius(e: Double) = 7f + (e * 9).toFloat()

        /** 三段混色：t<0.5 时 a→b，t≥0.5 时 b→c */
        private fun blend3(a: Int, b: Int, c: Int, t: Double): Int =
            if (t < 0.5) blend(a, b, t * 2) else blend(b, c, (t - 0.5) * 2)

        private fun blend(a: Int, b: Int, t: Double): Int {
            val f = t.coerceIn(0.0, 1.0)
            return Color.rgb(
                (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
                (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt()
            )
        }
    }

    // ════════════════════════ 缓存 ════════════════════════

    private fun cacheFile() = java.io.File(requireContext().filesDir, "gsp_board_cache.json")

    private fun loadCache() {
        runCatching {
            val root = JSONObject(cacheFile().readText()).optJSONObject("boards") ?: return
            val keys = root.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val o = root.optJSONObject(k) ?: continue
                cache[k] = BoardCache(
                    o.optString("name"), o.optString("state"),
                    o.optDouble("tau"), o.optDouble("breadth"), o.optString("at")
                )
            }
        }
    }

    private fun saveCache(r: SectorResonanceEngine.Result) {
        cache[r.boardCode] = BoardCache(r.boardName, r.state.name, r.tau, r.breadth, r.analyzedAt)
        runCatching {
            val root = JSONObject()
            val boards = JSONObject()
            cache.forEach { (k, v) ->
                boards.put(k, JSONObject().apply {
                    put("name", v.name); put("state", v.state)
                    put("tau", v.tau); put("breadth", v.breadth); put("at", v.at)
                })
            }
            root.put("boards", boards)
            cacheFile().writeText(root.toString())
        }
    }

    // ════════════════════════ UI 小件 ════════════════════════

    private fun paintPlan() {
        fun paint(tv: TextView, on: Boolean) {
            tv.background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                if (on) { setColor(Color.parseColor("#E0F2F1")); setStroke(dp(1), TEAL) }
                else { setColor(Color.parseColor("#F2F2F2")); setStroke(dp(1), Color.parseColor("#DDDDDD")) }
            }
            tv.setTextColor(if (on) TEAL else Color.parseColor("#666666"))
        }
        planA?.let { paint(it, plan == 0) }
        planB?.let { paint(it, plan == 1) }
        leaderChipA?.let { paint(it, leaderMode == 0) }
        leaderChipB?.let { paint(it, leaderMode == 1) }
    }

    private fun planChip(t: String) = TextView(requireContext()).apply {
        text = t; textSize = 12f; gravity = Gravity.CENTER
        setPadding(dp(8), dp(7), dp(8), dp(7))
    }

    private fun paramCell(cap: String, dft: String, onChange: (Double) -> Unit): View {
        val ctx = requireContext()
        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(6) }
        }
        cell.addView(tv(cap, GRAY, 9f))
        cell.addView(EditText(ctx).apply {
            setText(dft); textSize = 11f; isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(DARK)
            background = rounded("#FFFFFF", 6)
            setPadding(dp(6), 0, dp(6), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(32))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    s?.toString()?.toDoubleOrNull()?.let { onChange(it) }
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })
        return cell
    }

    private fun sectionTitle(t: String, state: SectorResonanceEngine.BoardState): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(6))
            addView(tv(t, DARK, 13f, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(ctx).apply {
                text = state.label; textSize = 10f; setTextColor(Color.WHITE)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                background = rounded(stateColor(state.name), 8)
            })
        }
    }

    private fun stateLabel(name: String): String = when (name) {
        "BOOTING" -> "萌芽"
        "RESONATING" -> "共振"
        "DIVERGING" -> "分化"
        "DECAYING" -> "衰竭"
        else -> name
    }

    private fun stateColor(name: String?): Int = when (name) {
        "BOOTING" -> BLUE
        "RESONATING" -> GREEN
        "DIVERGING" -> ORANGE
        "DECAYING" -> GRAY
        else -> Color.parseColor("#B0BEC5")
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(t: String, c: Int, s: Float, b: Boolean = false) = TextView(requireContext()).apply {
        text = t; textSize = s; setTextColor(c)
        if (b) setTypeface(null, android.graphics.Typeface.BOLD)
    }

    private fun rounded(color: String, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.parseColor(color))
            cornerRadius = dp(radius).toFloat()
        }

    private fun rounded(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }
}
