package com.chin.stockanalysis.strategy.trade

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.R
import com.chin.stockanalysis.stock.data.PcBridgeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * ## 工作台 · ETF 低位选股页（实仓旁新增 Tab4，2026-09-06）
 *
 * 数据源 = PC 端 smalltools/_etf_buy.py v0.3 引擎（data/_etf_live_picks.json），
 * 经 exe data_service.py 的 GET /etf_live 提供给 APK（PcBridgeClient 拉取）。
 * 规则(PC 已回测验证, OOS 11/11 全胜)：
 *   距60日高回撤-25%~-12% + RSI6<30 + 年线上方 + 收阳/RSI拐头 + 非5日新低
 *   + 沪深300结构多头门控(close>MA20>MA60)；离场 止盈+2%/止损-6%/30日。
 * 网络不可用/未连接 PC 时展示最近一次成功拉取名单（本地缓存）。
 */
@SuppressLint("SetTextI18n")
class EtfDipFragment : Fragment() {

    private lateinit var gateLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var sigTable: TableLayout
    private lateinit var watchTable: TableLayout
    private lateinit var footLabel: TextView
    private lateinit var refreshBtn: Button

    companion object {
        private const val PREFS = "etf_live"
        private const val KEY_JSON = "last_json"
    }

    override fun onCreateView(inflater: android.view.LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }

        // ── 顶栏：标题 + 门控 + 刷新 ──
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(ctx).apply {
            text = "🧲 ETF"
            textSize = 16f
            setTextColor(Color.parseColor("#E65100"))
            setTypeface(typeface, Typeface.BOLD)
        }
        top.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        refreshBtn = Button(ctx).apply {
            text = "🔄 刷新"
            textSize = 13f
            setOnClickListener { refresh() }
        }
        top.addView(refreshBtn)
        root.addView(top)

        gateLabel = TextView(ctx).apply {
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        root.addView(gateLabel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        statusLabel = TextView(ctx).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#9E9E9E"))
            setPadding(dp(4), dp(2), dp(4), dp(6))
        }
        root.addView(statusLabel)

        val scroll = ScrollView(ctx).apply { isFillViewport = true }
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        body.addView(sectionTitle(ctx, "⚡ 今日可低吸信号（次日开盘可买）"))
        sigTable = TableLayout(ctx)
        sigTable.setPadding(0, dp(4), 0, dp(12))
        body.addView(sigTable)

        body.addView(sectionTitle(ctx, "👀 接近低吸区观察池（提前跟踪）"))
        watchTable = TableLayout(ctx)
        watchTable.setPadding(0, dp(4), 0, dp(12))
        body.addView(watchTable)

        footLabel = TextView(ctx).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#9E9E9E"))
            setPadding(dp(4), dp(8), dp(4), dp(4))
        }
        body.addView(footLabel)

        return root
    }

    private fun sectionTitle(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.parseColor("#E0E0E0"))
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(6), 0, 0)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 先显示上次缓存（离线也可见），随后尝试在线刷新
        val cached = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_JSON, null)
        if (cached != null) render(cached)
        refresh()
    }

    /** 由工作台 refreshAll / onResume 调用 */
    fun refresh() {
        val host = PcBridgeClient.loadHost(requireContext())
        if (host.isBlank()) {
            gateLabel.text = "⚠ 未连接 PC（exe 端 data_service）"
            statusLabel.text = "请先在工作台 PC 候选弹窗填写 exe 所在机器的 IP（局域网 HTTP:8888），\n或确认 PC 已运行 AutoQuant-GUI 的数据服务。"
            footLabel.text = "引擎: smalltools/_etf_buy.py --live（需 PC 侧先生成名单）"
            return
        }
        lifecycleScope.launch {
            try {
                statusLabel.text = "正在从 PC 拉取名单… ($host)"
                val json = withContext(Dispatchers.IO) { PcBridgeClient.fetchEtfLive(host) }
                requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_JSON, json).apply()
                render(json)
            } catch (e: Exception) {
                statusLabel.text = "拉取失败: ${e.message ?: "网络不可达"}\n(展示上次缓存; 若从未成功请核对 PC 服务与 IP)"
            }
        }
    }

    private fun render(jsonText: String) {
        try {
            val j = JSONObject(jsonText)
            val err = j.optString("error")
            if (err.isNotEmpty()) {
                gateLabel.text = "⚠ $err"
                statusLabel.text = j.optString("hint", "请先在 PC 端运行 python smalltools/_etf_buy.py --live")
                return
            }
            val gate = j.optJSONObject("gate") ?: JSONObject()
            val gateOk = gate.optBoolean("ok", false)
            gateLabel.text = (if (gateOk) "✅ 大盘结构多头（可低吸）" else "⛔ 大盘空头（暂停低吸）") +
                    "  ·  " + (gate.optString("date", ""))
            gateLabel.setBackgroundColor(Color.parseColor(if (gateOk) "#1D3A2A" else "#3A1D1D"))
            statusLabel.text = "数据截至 ${j.optString("as_of", "-")}（PC ${j.optString("generated_at", "")} 生成）"
            sigTable.removeAllViews()
            watchTable.removeAllViews()
            addHeader(sigTable, listOf("名称", "代码", "收盘", "回撤60日%", "RSI6", "状态"))
            addHeader(watchTable, listOf("名称", "代码", "收盘", "回撤60日%", "RSI6", "状态"))
            fillRows(sigTable, j.optJSONArray("signal_today"), gateOk)
            fillRows(watchTable, j.optJSONArray("approach"), null)
            footLabel.text = (j.optString("strategy", "") + "\n离场: " + j.optString("exit", "")).trim()
        } catch (e: Exception) {
            gateLabel.text = "解析失败: ${e.message}"
        }
    }

    private fun addHeader(t: TableLayout, cols: List<String>) {
        val row = TableRow(requireContext())
        for (c in cols) {
            val tv = TextView(requireContext()).apply {
                text = c
                textSize = 12f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(2), dp(4), dp(6), dp(4))
                gravity = Gravity.CENTER
            }
            row.addView(tv)
        }
        t.addView(row)
    }

    private fun fillRows(t: TableLayout, arr: org.json.JSONArray?, isSignal: Boolean?) {
        if (arr == null || arr.length() == 0) {
            val empty = TextView(requireContext()).apply {
                text = "  （暂无。低位策略多数时间空仓 = 正确行为）"
                textSize = 12f
                setTextColor(Color.parseColor("#9E9E9E"))
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }
            t.addView(empty)
            return
        }
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val name = r.optString("name", r.optString("code", ""))
            val vals = listOf(
                name, r.optString("code", ""),
                String.format("%.3f", r.optDouble("close", 0.0)),
                String.format("%.1f", r.optDouble("dd60", 0.0)),
                String.format("%.0f", r.optDouble("rsi6", 0.0)),
                if (isSignal != null && isSignal && r.optBoolean("dip", false)) "🟢 信号" else "观察")
            val row = TableRow(requireContext())
            for ((idx, v) in vals.withIndex()) {
                val tv = TextView(requireContext()).apply {
                    text = v
                    textSize = 12f
                    setPadding(dp(2), dp(4), dp(6), dp(4))
                    when (idx) {
                        0 -> { setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#FFE0B2")) }
                        1 -> { setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#CE93D8")) }
                        3 -> { setTextColor(if ((v.toDoubleOrNull() ?: 0.0) < -20) Color.parseColor("#FF7043")
                        else Color.parseColor("#FFCC00")) }
                        5 -> { setTextColor(if (v.contains("🟢")) Color.parseColor("#69F0AE")
                        else Color.parseColor("#9E9E9E")) }
                        else -> setTextColor(Color.parseColor("#E0E0E0"))
                    }
                }
                row.addView(tv)
            }
            t.addView(row)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
