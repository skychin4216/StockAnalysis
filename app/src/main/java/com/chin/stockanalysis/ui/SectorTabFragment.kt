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
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.backtest.SectorDailyRecordEntity
import kotlinx.coroutines.*

/**
 * 板块 Tab 内容页（行业/概念/指数）
 *
 * 数据来源：EastMoneyHotSectorSource 全局预计算缓存（零等待）
 * 刷新间隔：每 10 分钟（跟随后台调度器的排序节奏）
 */
class SectorTabFragment : Fragment() {
    companion object {
        private const val ARG_TYPE = "type" // 1=指数 2=行业 3=概念
        fun newInstance(type: Int): SectorTabFragment = SectorTabFragment().also {
            it.arguments = Bundle().apply { putInt(ARG_TYPE, type) }
        }
    }

    private var refreshJob: Job? = null
    private lateinit var grid: TableLayout
    private var sectorType: Int = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sectorType = arguments?.getInt(ARG_TYPE) ?: 2
    }

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, b: Bundle?): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        grid = TableLayout(requireContext()).apply {
            isStretchAllColumns = true; setPadding(4, 2, 4, 80)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        val sv = ScrollView(requireContext()).apply {
            addView(grid)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(sv)
        startRefresh()
        return root
    }

    private fun startRefresh() {
        refreshJob = lifecycleScope.launch {
            // 先检查全局缓存（MainActivity 启动时已触发 pool scheduler）
            val immediateData = when (sectorType) {
                1 -> EastMoneyHotSectorSource.indexSectors
                2 -> EastMoneyHotSectorSource.industrySectors
                3 -> EastMoneyHotSectorSource.conceptSectors
                else -> emptyList()
            }
            if (immediateData.isNotEmpty()) {
                updateGrid(immediateData)
            } else {
                // 等 2 秒让 pool scheduler 完成首次拉取
                delay(2_000L)
                if (isActive) refreshFromCache()
            }
            // 5 秒后再重试一次（确保数据正确）
            delay(5_000L)
            if (isActive) refreshFromCache()
            while (isActive) {
                delay(10 * 60_000L)
                if (isActive) refreshFromCache()
            }
        }
    }

    private fun refreshFromCache() {
        // 1. 优先使用全局缓存
        val live = when (sectorType) {
            1 -> EastMoneyHotSectorSource.indexSectors
            2 -> EastMoneyHotSectorSource.industrySectors
            3 -> EastMoneyHotSectorSource.conceptSectors
            else -> emptyList()
        }
        if (live.isNotEmpty()) { updateGrid(live); return }

        // 2. 全局缓存为空 → 直接 API 获取
        lifecycleScope.launch(Dispatchers.IO) {
            val direct = EastMoneyHotSectorSource().fetchSectorsByTypeDirect(sectorType, 20)
            if (direct.isNotEmpty()) { lifecycleScope.launch(Dispatchers.Main) { updateGrid(direct) }; return@launch }
            // 3. API 也失败 → 历史 DB
            loadFromHistoryOnly()
        }
    }

    /** 从历史 sector_daily_record 表加载（不使用快照聚合，避免显示错误子板块） */
    private suspend fun loadFromHistoryOnly() {
        try {
            val db = StockDatabase.getInstance(requireContext())
            val latestDates = db.dailySnapshotDao().getAvailableDates(3)
            if (latestDates.isNotEmpty()) {
                val date = latestDates.first()
                val records = db.sectorDailyRecordDao().getByDate(date)
                if (records.isNotEmpty()) {
                    val sectors = records.map { r: SectorDailyRecordEntity ->
                        EastMoneyHotSectorSource.HotSector(
                            code = r.sectorCode, name = r.sectorName,
                            changePercent = r.changePct, sectorIndex = 0.0,
                            hotScore = r.hotScore.toDouble(), mainNetInflow = r.mainNetInflow,
                            compositeScore = r.compositeScore
                        )
                    }
                    lifecycleScope.launch(Dispatchers.Main) { updateGrid(sectors) }
                    return
                }
            }
        } catch (_: Exception) {}
        // 历史 DB 也没有 → 显示"加载中"，等 pool scheduler 完成后会自动刷新
        lifecycleScope.launch(Dispatchers.Main) {
            if (grid.childCount == 0) {
                grid.removeAllViews()
                grid.addView(TableRow(requireContext()).apply {
                    addView(TextView(requireContext()).apply { text = "⏳ 加载中..."; textSize = 12f; setTextColor(Color.parseColor("#AAAAAA")); setPadding(8, 16, 8, 8) })
                })
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); refreshJob?.cancel() }

    private fun updateGrid(sectors: List<EastMoneyHotSectorSource.HotSector>) {
        grid.removeAllViews()
        if (sectors.isEmpty()) {
            val er = TableRow(requireContext()); er.addView(TextView(requireContext()).apply { text="暂无数据"; textSize=12f; setTextColor(Color.GRAY); setPadding(8,8,8,8) }); grid.addView(er)
            return
        }
        // 表头
        val hr = TableRow(requireContext()).apply { setBackgroundColor(Color.parseColor("#F0F0F5")) }
        for (h in listOf("板块", "涨跌", "热度", "主力流入", "领涨")) hr.addView(TextView(requireContext()).apply { text=h; textSize=10f; setTextColor(Color.parseColor("#888888")); setTypeface(null, Typeface.BOLD); gravity=Gravity.CENTER; setPadding(4,6,4,6) })
        grid.addView(hr)
        for (s in sectors) grid.addView(createSectorRow(s))
    }

    private fun createSectorRow(s: EastMoneyHotSectorSource.HotSector): TableRow {
        val ctx = requireContext()
        val row = TableRow(ctx).apply { setBackgroundColor(Color.WHITE); setPadding(0,0,0,2)
            setOnClickListener { val d = SectorDetailFragment.newInstance(s.name, s.code); activity?.supportFragmentManager?.beginTransaction()?.replace(android.R.id.content, d)?.addToBackStack(null)?.commit() }
        }
        row.addView(TextView(ctx).apply { text=s.name; textSize=12f; setTextColor(Color.parseColor("#222222")); setTypeface(null,Typeface.BOLD); maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END; setPadding(6,8,2,8) })
        val sign=if(s.changePercent>=0)"+" else ""
        row.addView(TextView(ctx).apply { text="$sign${"%.2f".format(s.changePercent)}%"; textSize=12f; setTypeface(null,Typeface.BOLD); setTextColor(if(s.changePercent>=0)Color.parseColor("#E53935") else Color.parseColor("#43A047")); gravity=Gravity.CENTER; setPadding(2,8,2,8) })
        row.addView(TextView(ctx).apply { text="${"%.0f".format(s.hotScore)}"; textSize=11f; setTextColor(Color.parseColor("#E65100")); gravity=Gravity.CENTER; setPadding(2,8,2,8) })
        val inflow=s.mainNetInflow; val isign=if(inflow>=0)"+" else ""
        row.addView(TextView(ctx).apply { text="${isign}${"%.1f".format(inflow)}亿"; textSize=10f; setTextColor(if(inflow>=0)Color.parseColor("#E53935") else Color.parseColor("#43A047")); gravity=Gravity.CENTER; setPadding(2,8,2,8) })
        row.addView(TextView(ctx).apply { text=if(s.top1StockName.isNotEmpty())s.top1StockName else "-"; textSize=10f; setTextColor(Color.parseColor("#888888")); maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END; gravity=Gravity.CENTER; setPadding(2,8,4,8) })
        return row
    }

}