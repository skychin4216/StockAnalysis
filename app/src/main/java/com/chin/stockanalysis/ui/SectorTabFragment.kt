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
    private lateinit var grid: LinearLayout
    private lateinit var refreshTv: TextView
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
        val hdr = LinearLayout(requireContext()).apply {
            setPadding(16, 8, 16, 8); setBackgroundColor(Color.parseColor("#F5F6FA"))
        }
        refreshTv = TextView(requireContext()).apply {
            text = "⏳加载中..."; textSize = 10f; setTextColor(Color.parseColor("#999999"))
        }
        hdr.addView(refreshTv)
        root.addView(hdr)
        grid = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(8, 4, 8, 80)
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
            refreshFromCache()  // 立即从缓存读取
            while (isActive) {
                delay(10 * 60_000L)  // 每 10 分钟刷新一次
                if (isActive) refreshFromCache()
            }
        }
    }

    private fun refreshFromCache() {
        var list = when (sectorType) {
            1 -> EastMoneyHotSectorSource.indexSectors
            2 -> EastMoneyHotSectorSource.industrySectors
            3 -> EastMoneyHotSectorSource.conceptSectors
            else -> emptyList()
        }
        // Fallback：如果全局缓存为空（后台调度器还没完成首次拉取），直接同步获取
        if (list.isEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) {
                val direct = EastMoneyHotSectorSource().fetchSectorsByTypeDirect(sectorType, 20)
                lifecycleScope.launch(Dispatchers.Main) {
                    refreshTv.text = "🕐${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}"
                    updateGrid(direct)
                }
            }
            return
        }
        refreshTv.text = "🕐${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}"
        updateGrid(list)
    }

    override fun onDestroyView() { super.onDestroyView(); refreshJob?.cancel() }

    private fun updateGrid(sectors: List<EastMoneyHotSectorSource.HotSector>) {
        grid.removeAllViews()
        if (sectors.isEmpty()) { grid.addView(TextView(requireContext()).apply { text="暂无数据";textSize=12f;setTextColor(Color.GRAY)});return }
        for (s in sectors) { grid.addView(createSectorCard(s)) }
    }

    private fun createSectorCard(s: EastMoneyHotSectorSource.HotSector): LinearLayout {
        val ctx = requireContext()
        val card = LinearLayout(ctx).apply {
            orientation=LinearLayout.HORIZONTAL; setBackgroundColor(Color.WHITE); setPadding(12,10,12,10)
            (layoutParams as? LayoutParams)?.setMargins(0,0,0,6); elevation=2f
            gravity=Gravity.CENTER_VERTICAL
        }
        card.addView(TextView(ctx).apply{
            text=s.name;textSize=13f;setTextColor(Color.parseColor("#222222"));setTypeface(null,Typeface.BOLD)
            layoutParams=LayoutParams(0,LayoutParams.WRAP_CONTENT,1f)
            maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
        })
        val sign=if(s.changePercent>=0)"+" else ""
        card.addView(TextView(ctx).apply{
            text="$sign${"%.2f".format(s.changePercent)}%"
            textSize=13f;setTypeface(null,Typeface.BOLD)
            setTextColor(if(s.changePercent>=0) Color.parseColor("#E53935") else Color.parseColor("#43A047"))
            layoutParams=LayoutParams(LayoutParams.WRAP_CONTENT,LayoutParams.WRAP_CONTENT).apply{marginStart=8}
        })
        val inflow=s.mainNetInflow
        card.addView(TextView(ctx).apply{
            text="🔥${"%.1f".format(s.hotScore)}  ${if(inflow>=0)"+" else ""}${"%.1f".format(inflow)}亿"
            textSize=10f;setTextColor(if(inflow>=0)Color.parseColor("#E53935") else Color.parseColor("#43A047"))
            layoutParams=LayoutParams(LayoutParams.WRAP_CONTENT,LayoutParams.WRAP_CONTENT).apply{marginStart=8}
        })
        if(s.top1StockName.isNotEmpty()){
            card.addView(TextView(ctx).apply{
                text=" ↳${s.top1StockName}"
                textSize=10f;setTextColor(Color.parseColor("#888888"))
                maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
                layoutParams=LayoutParams(LayoutParams.WRAP_CONTENT,LayoutParams.WRAP_CONTENT).apply{marginStart=6}
            })
        }
        card.setOnClickListener {
            val t0 = System.currentTimeMillis()
            android.util.Log.i("SectorTab", "CLICK sector='${s.name}' code='${s.code}'")
            val d = SectorDetailFragment.newInstance(s.name, s.code)
            val t1 = System.currentTimeMillis()
            android.util.Log.i("SectorTab", "newInstance done (${t1-t0}ms)")
            activity?.supportFragmentManager?.beginTransaction()?.replace(android.R.id.content, d)?.addToBackStack(null)?.commit()
            val t2 = System.currentTimeMillis()
            android.util.Log.i("SectorTab", "fragment replace committed (${t2-t0}ms total)")
        }
        return card
    }
}