package com.chin.stockanalysis.strategy.trade

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
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
import com.chin.stockanalysis.strategy.data.EtfCacheSync
import com.chin.stockanalysis.strategy.topology.xml.UseCaseLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ## 工作台 · ETF 选股页（实仓旁 Tab，2026-09-06 起本地化改造）
 *
 * 架构与三周期同构 —— usecase/pipeline 为 **XML 单一事实源**
 * （app/src/main/assets/usecases/etf_dip_usecase.xml + etf_dip_pipeline.xml），
 * 双端（APK UseCaseLoader + AutoQuant Python 引擎）读同一 XML 执行：
 *   门控: 沪深300 结构多头(close>MA20>MA60)
 *   信号: 距60日高回撤-25%~-12% + RSI6<30 + 年线上方 + 收阳/RSI拐头 + 非5日新低
 *   离场: tp+2% / sl-6% / 30 日（参数全在 XML，改 XML 双端自动同步）
 * 行情: 本地 etf_cache.json —— APK 自己拉取（EtfCacheSync：腾讯 fqkline qfq，
 * 13只ETF+sh000300 前复权日K，与 PC _etf_cache.json 同构同源，写 external files）；
 * 缓存缺失/过期时点击"刷新"即自动同步，无需 PC。
 * 已移除 PC 桥回退（2026-09-07）：本地无法执行时仅提示联网自拉。
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
        private const val TAG = "EtfDipFragment"
    }

    private data class LocalOutcome(val payloadJson: String?, val error: String?)

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

    /**
     * 由工作台 refreshAll / onResume / 刷新按钮 调用。
     * 0) 先自拉/校验本地行情 etf_cache.json（EtfCacheSync，腾讯 qfq，与 PC 同构，缺失或过期才拉）；
     * 1) 本地引擎：UseCaseLoader 跑 assets/usecases/etf_dip_usecase.xml（与 PC 同 XML 同源）→ 渲染；
     * 2) 引擎未产出（无缓存/无网络）→ 引导提示。APK 完全自给，无 PC 桥。
     */
    fun refresh() {
        lifecycleScope.launch {
            // ── 0) 本地行情自拉：APK 自给（腾讯 fqkline qfq → 手机 etf_cache.json）──
            try {
                statusLabel.text = "检查/同步本地 ETF 行情…"
                val sync = withContext(Dispatchers.IO) { EtfCacheSync(requireContext()).syncIfStale() }
                Log.i(TAG, "ETF 行情同步: ${sync.message}")
            } catch (e: Exception) {
                Log.w(TAG, "本地 ETF 行情同步异常: ${e.message}")
            }

            // ── 1) 本地引擎（彻底不依赖 PC） ──
            var outcome: LocalOutcome? = null
            try {
                statusLabel.text = "本地引擎执行 etf_dip usecase…"
                outcome = withContext(Dispatchers.IO) { runLocalUseCase() }
            } catch (e: Exception) {
                Log.w(TAG, "本地 etf_dip 执行异常: ${e.message}")
            }
            val payloadJson = outcome?.payloadJson
            if (payloadJson != null) {
                requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_JSON, payloadJson).apply()
                render(payloadJson)
                statusLabel.text = "✔ 本地引擎 · XML 单一源 · 行情 APK 腾讯自拉（与 PC 同构）"
                return@launch
            }
            val errMsg = outcome?.error
            if (errMsg != null) Log.w(TAG, "本地 etf_dip 未产出: $errMsg")

            // ── 2) 引擎未产出：本地自拉引导提示（PC 桥已移除，APK 完全自给）──
            gateLabel.text = "⚠ 本地无 ETF 行情缓存"
            statusLabel.text = (if (errMsg != null) "本地执行失败：$errMsg\n\n" else "") +
                "请确保手机网络可用后点「刷新」——APK 会自动拉取 13只ETF+沪深300\n" +
                "前复权日K到本地 etf_cache.json 并本地执行选股（无需 PC）。"
            footLabel.text = "引擎: XML etf_dip（门控→信号→离场）· 数据: etf_cache.json（APK 本地腾讯自拉）"
        }
    }

    /** 本地跑 etf_dip usecase：n_etf_exit 阶段输出即发布口径 JSON（与 PC _etf_live_picks.json 同构） */
    private suspend fun runLocalUseCase(): LocalOutcome {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val res = UseCaseLoader.run("etf_dip", date)
        if (!res.success) {
            val msg = res.errors.entries.joinToString("; ") { "${it.key}:${it.value}" }
                .ifBlank { "etf_dip 执行失败" }
            return LocalOutcome(null, msg)
        }
        val payload = res.stageOutputs["n_etf_exit"] as? JSONObject
            ?: return LocalOutcome(null, "引擎未产出 n_etf_exit 发布结果（可能行情缓存缺失）")
        return LocalOutcome(payload.toString(), null)
    }

    private fun render(jsonText: String) {
        try {
            val j = JSONObject(jsonText)
            val err = j.optString("error")
            if (err.isNotEmpty()) {
                gateLabel.text = "⚠ $err"
                statusLabel.text = j.optString("hint", "本地行情缓存缺失，请联网后点「刷新」自动同步并本地选股")
                return
            }
            val gate = j.optJSONObject("gate") ?: JSONObject()
            val gateOk = gate.optBoolean("ok", false)
            gateLabel.text = (if (gateOk) "✅ 大盘结构多头（可低吸）" else "⛔ 大盘空头（暂停低吸）") +
                    "  ·  " + (gate.optString("date", ""))
            gateLabel.setBackgroundColor(Color.parseColor(if (gateOk) "#1D3A2A" else "#3A1D1D"))
            statusLabel.text = "数据截至 ${j.optString("as_of", "-")} · 引擎 ${j.optString("generated_at", "-")} 生成"
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
