package com.chin.stockanalysis.ui

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
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource
import com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource.LeaderStock
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * 行业龙头图谱页（MarketHotFragment 新增子 Tab）
 *
 * 形态：把「行业 → 龙头」两级钻取扁平化成一张图谱——
 *   · 行业列表 = 当日活跃行业（EastMoneyHotSectorSource.industrySectors，按 涨幅/换手/主力流入 综合分排序，零额外请求）
 *   · 每个行业一张卡片，卡片头显示 涨跌/主力净流入/领涨股，可点击钻取板块详情（Top20 完整龙头表）
 *   · 卡片身内联「市值龙头 Top3」实时表（fetchSectorLeaders，与详情页同口径），点击个股跳股票详情
 *   · 龙头数据进程级缓存 TTL=10min，避免来回切换子 Tab 重复打东财接口
 */
class SectorLeaderMapFragment : Fragment() {

    companion object {
        private const val ARG_TITLE = "title"
        private const val MAX_SECTORS = 15          // 图谱覆盖的活跃行业数
        private const val LEADERS_TOP_N = 3          // 每行业展示市值龙头 TopN
        private const val FETCH_CONCURRENCY = 4      // 龙头并发拉取数（温和限流）
        private const val LEADER_CACHE_TTL_MS = 10 * 60_000L

        fun newInstance(title: String = "行业龙头图谱"): SectorLeaderMapFragment =
            SectorLeaderMapFragment().also {
                it.arguments = Bundle().apply { putString(ARG_TITLE, title) }
            }

        // 进程级龙头缓存（按 BK 代码）
        private class LeaderCacheEntry(val time: Long, val leaders: List<LeaderStock>)
        private val leaderCache = ConcurrentHashMap<String, LeaderCacheEntry>()
    }

    private var refreshJob: Job? = null
    private lateinit var container: LinearLayout
    private lateinit var updateTv: TextView
    private var renderGen = 0L   // render 代际标记：重建列表后丢弃过期龙头填充

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, b: Bundle?): View {
        val title = arguments?.getString(ARG_TITLE) ?: "行业龙头图谱"
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F6FA"))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        // 顶栏说明 + 刷新时间
        val top = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(14, 10, 14, 8)
        }
        top.addView(TextView(requireContext()).apply {
            text = "📋 $title"
            textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#1A1A2E"))
        })
        top.addView(TextView(requireContext()).apply {
            text = "活跃行业按 涨幅·换手·主力流入 综合排序 | 龙头按总市值 Top$LEADERS_TOP_N | 数据：东方财富实时"
            textSize = 10f; setTextColor(Color.parseColor("#999999")); setPadding(0, 3, 0, 0)
        })
        updateTv = TextView(requireContext()).apply {
            text = "⏳ 加载中..."; textSize = 10f; setTextColor(Color.parseColor("#E65100")); setPadding(0, 3, 0, 0)
        }
        top.addView(updateTv)
        root.addView(top)

        container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 8, 10, 80)
        }
        root.addView(ScrollView(requireContext()).apply {
            addView(container)
            isVerticalScrollBarEnabled = false
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        })
        startRefresh()
        return root
    }

    override fun onDestroyView() { super.onDestroyView(); refreshJob?.cancel() }

    private fun startRefresh() {
        refreshJob = lifecycleScope.launch {
            var failCount = 0
            while (isActive) {
                val sectors = EastMoneyHotSectorSource.industrySectors
                if (sectors.isNotEmpty()) {
                    failCount = 0
                    renderSectors(sectors.take(MAX_SECTORS))
                } else {
                    failCount++
                }
                // 无缓存时等 pool scheduler 首拉（2s/5s 各重试一次），之后每 10 分钟跟随池调度刷新
                val wait = when {
                    failCount == 1 -> 2_000L
                    failCount == 2 -> 5_000L
                    else -> 10 * 60_000L
                }
                delay(wait)
            }
        }
    }

    // ======================== 渲染 ========================

    private fun renderSectors(sectors: List<EastMoneyHotSectorSource.HotSector>) {
        if (!isAdded) return
        val gen = ++renderGen
        updateTv.text = "更新于 " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()) +
            " · ${sectors.size} 个活跃行业"
        container.removeAllViews()

        // 等待填充龙头结果的容器映射（BK 代码 -> 卡片龙头区）
        val leadersBoxes = LinkedHashMap<String, LinearLayout>()
        for (s in sectors) {
            val card = createSectorCard(s, leadersBoxes)
            container.addView(card)
        }
        if (leadersBoxes.isNotEmpty()) fetchLeadersInBackground(leadersBoxes, gen)
    }

    /** 单个行业卡片：头部=行业概览(点击钻取详情)；身部=市值龙头 Top3 内联 */
    private fun createSectorCard(s: EastMoneyHotSectorSource.HotSector, boxes: MutableMap<String, LinearLayout>): LinearLayout {
        val ctx = requireContext()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            elevation = 3f
            (layoutParams as? LayoutParams)?.setMargins(0, 0, 0, 10)
        }
        // —— 头部行（行业名 / 涨跌 / 主力净流入），点击 → 板块详情 ——
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 10, 12, 6)
            setBackgroundColor(Color.parseColor("#FAFBFF"))
            setOnClickListener {
                if (s.code.isNotEmpty()) {
                    val d = SectorDetailFragment.newInstance(s.name, s.code)
                    activity?.supportFragmentManager?.beginTransaction()
                        ?.replace(android.R.id.content, d)?.addToBackStack(null)?.commit()
                }
            }
        }
        val up = s.changePercent >= 0
        val upColor = Color.parseColor("#E53935")
        val downColor = Color.parseColor("#43A047")
        head.addView(TextView(ctx).apply {
            text = s.name
            textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#1A1A2E"))
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 2.1f)
        })
        head.addView(TextView(ctx).apply {
            text = "${if (up) "+" else ""}${"%.2f".format(s.changePercent)}%"
            textSize = 15f; setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
            setTextColor(if (up) upColor else downColor)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.2f)
        })
        head.addView(TextView(ctx).apply {
            val v = s.mainNetInflow
            text = "${if (v >= 0) "+" else ""}${"%.1f".format(v)}亿"
            textSize = 11f; gravity = Gravity.CENTER
            setTextColor(if (v >= 0) upColor else downColor)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.4f)
        })
        card.addView(head)

        // —— 副行：人气领涨股 + 5日/20日 ——
        if (s.top1StockName.isNotEmpty()) {
            val sub = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(12, 2, 12, 4)
            }
            sub.addView(TextView(ctx).apply {
                text = "人气领涨:"
                textSize = 10f; setTextColor(Color.parseColor("#999999"))
            })
            val t1 = TextView(ctx).apply {
                text = s.top1StockName
                textSize = 11f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#1565C0"))
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(6, 0, 2, 0)
            }
            if (s.top1StockCode.isNotEmpty()) {
                sub.setOnClickListener {
                    StockDetailNavigator.navigateFromFragment(
                        this@SectorLeaderMapFragment,
                        s.top1StockCode, s.top1StockName,
                        sectorName = s.name
                    )
                }
            }
            sub.addView(t1, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            sub.addView(TextView(ctx).apply {
                val sign = if (s.top1ChangePercent >= 0) "+" else ""
                text = "$sign${"%.1f".format(s.top1ChangePercent)}%"
                textSize = 11f; setTypeface(null, Typeface.BOLD)
                setTextColor(if (s.top1ChangePercent >= 0) upColor else downColor)
                setPadding(4, 0, 0, 0)
            })
            card.addView(sub)
        }

        // —— 龙头容器：先占位，拉取后填充市值龙头 Top3 ——
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4, 2, 4, 6)
        }
        box.addView(TextView(ctx).apply {
            text = "⏳ 龙头获取中..."; textSize = 10f; setTextColor(Color.parseColor("#BBBBBB"))
            setPadding(10, 6, 10, 4)
        })
        card.addView(box)
        if (s.code.isNotEmpty()) boxes[s.code] = box
        return card
    }

    // ======================== 龙头数据（并发拉取 + 缓存） ========================

    private fun fetchLeadersInBackground(boxes: Map<String, LinearLayout>, gen: Long) {
        val sem = Semaphore(FETCH_CONCURRENCY)
        lifecycleScope.launch(Dispatchers.IO) {
            val jobs = boxes.map { (code, box) ->
                async {
                    sem.acquire()
                    try {
                        val now = System.currentTimeMillis()
                        val cached = leaderCache[code]
                        val leaders = if (cached != null && now - cached.time < LEADER_CACHE_TTL_MS) {
                            cached.leaders
                        } else {
                            val fetched = try {
                                EastMoneyHotSectorSource().fetchSectorLeaders(code, LEADERS_TOP_N, "f20")
                            } catch (e: Exception) { emptyList() }
                            leaderCache[code] = LeaderCacheEntry(now, fetched)
                            fetched
                        }
                        withContext(Dispatchers.Main) {
                            // 仅当列表未被新一轮 render 重建时才填充，避免脏写已卸载视图
                            if (isAdded && gen == renderGen) fillLeadersBox(box, leaders)
                        }
                    } finally {
                        sem.release()
                    }
                }
            }
            jobs.joinAll()
        }
    }

    /** 填充单个行业卡片的龙头区（市值 Top3 行） */
    private fun fillLeadersBox(box: LinearLayout, leaders: List<LeaderStock>) {
        if (box.childCount == 0) return
        val ctx = box.context
        box.removeAllViews()
        if (leaders.isEmpty()) {
            box.addView(TextView(ctx).apply {
                text = "👆 点击上方行业名查看龙头明细"
                textSize = 10f; setTextColor(Color.parseColor("#BBBBBB")); setPadding(10, 6, 10, 4)
            })
            return
        }
        // 微表头
        val hRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#E3F2FD")); setPadding(6, 4, 6, 4)
        }
        val hLabels = listOf("龙头股 (市值Top${leaders.size})", "现价", "涨跌", "市值")
        val weights = listOf(2.4f, 1.0f, 1.0f, 1.2f)
        for ((i, label) in hLabels.withIndex()) {
            hRow.addView(TextView(ctx).apply {
                text = label; textSize = 9f; setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#1565C0")); gravity = Gravity.CENTER
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, weights[i])
            })
        }
        box.addView(hRow)
        for ((idx, s) in leaders.withIndex()) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(6, 5, 6, 5)
                if (idx % 2 == 1) setBackgroundColor(Color.parseColor("#F7F8FC"))
                setOnClickListener {
                    StockDetailNavigator.navigateFromFragment(
                        this@SectorLeaderMapFragment,
                        s.code, s.name, s.price, s.changePercent
                    )
                }
            }
            row.addView(TextView(ctx).apply {
                text = "${idx + 1}. ${s.name}\n${s.code.takeLast(6)} · ${s.board}"
                textSize = 11f; setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#333333"))
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, weights[0])
            })
            row.addView(TextView(ctx).apply {
                text = if (s.price > 0) "${"%.2f".format(s.price)}" else "—"
                textSize = 11f; setTextColor(Color.parseColor("#E65100")); gravity = Gravity.CENTER
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, weights[1])
            })
            row.addView(TextView(ctx).apply {
                val up = s.changePercent >= 0
                text = if (s.changePercent != 0.0) "${if (up) "+" else ""}${"%.2f".format(s.changePercent)}%" else "—"
                textSize = 11f; gravity = Gravity.CENTER
                setTextColor(if (up) Color.parseColor("#E53935") else Color.parseColor("#43A047"))
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, weights[2])
            })
            row.addView(TextView(ctx).apply {
                text = if (s.marketCap > 0) "${"%.0f".format(s.marketCap)}亿" else "—"
                textSize = 10f; setTextColor(Color.parseColor("#666666")); gravity = Gravity.CENTER
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, weights[3])
            })
            box.addView(row)
        }
    }
}
