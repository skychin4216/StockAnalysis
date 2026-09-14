package com.chin.stockanalysis.strategy.trade

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.chin.stockanalysis.cloud.CloudSyncManager
import com.chin.stockanalysis.stock.data.CosRelayClient
import com.chin.stockanalysis.stock.data.PcBridgeClient
import com.chin.stockanalysis.stock.database.AiSelectedStockEntity
import com.chin.stockanalysis.stock.database.AppBackgroundRunner
import com.chin.stockanalysis.ui.StockDetailNavigator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PC 候选清单 Dialog（工作台「📋 PC 候选」入口）。
 *
 * 优先经**联网中继**（`PcBridgeClient` → `CosRelayClient`，无需填 IP / Token）实时拉取；
 * 中继不可用时回退到 COS 直下 smalltools `_publish_candidates.py` 发布的候选清单。
 * 大盘状态 / 周期分组(短线·中线·长线，超短并入短线⚡) / 预备队 / 热点板块。
 * 支持：🔄 重新拉取、➕ 一键把全部候选加入 AI 精选（ai_selected_stock，source=pc_candidates）。
 */
class PcCandidatesDialog(context: Context) : Dialog(context) {

    private val density = context.resources.displayMetrics.density
    private val cloud = CloudSyncManager(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var latestJson: String? = null

    private lateinit var statusView: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var emptyView: TextView
    private lateinit var addBtn: TextView
    private lateinit var relayView: TextView
    /** P3：PC 主动推送（选股完成 / 盘前情报 / 任务结束）展示区 */
    private lateinit var pushView: TextView
    private var watchJob: Job? = null
    private var pushJob: Job? = null
    private var usingPc = false

    private fun Int.dp() = (this * density + 0.5f).toInt()

    init {
        // 联网中继需要 Application Context（IP / Token 均已废弃）
        PcBridgeClient.init(context)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(buildView())
        window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(null)
        }
        refresh()
    }

    override fun dismiss() {
        watchJob?.cancel()
        pushJob?.cancel()
        scope.cancel()
        super.dismiss()
    }

    private fun buildView(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        // ── 标题栏 ──
        val titleBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp(), 12.dp(), 8.dp(), 12.dp())
            setBackgroundColor(0xFF1565C0.toInt())
        }
        titleBar.addView(TextView(context).apply {
            text = "📋 PC 候选清单"
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleBar.addView(TextBtn("🔄") { refresh() })
        titleBar.addView(TextBtn("✕") { dismiss() })
        root.addView(titleBar)

        // ── 中继状态行（联网中继：无需填 IP / Token） ──
        val pcBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp(), 8.dp(), 12.dp(), 8.dp())
            setBackgroundColor(0xFFE3F2FD.toInt())
        }
        relayView = TextView(context).apply {
            text = CosRelayClient.statusText(context)
            textSize = 11f
            setTextColor(0xFF1565C0.toInt())
        }
        pcBar.addView(relayView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pcBar.addView(TextView(context).apply {
            text = "🔗 中继拉取"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(14.dp(), 8.dp(), 14.dp(), 8.dp())
            background = rnd(0xFF00838F.toInt(), 6.dp())
            setOnClickListener { refresh() }
        })
        root.addView(pcBar)

        // ── 状态行 ──
        statusView = TextView(context).apply {
            setPadding(16.dp(), 10.dp(), 16.dp(), 10.dp())
            textSize = 13f
            setTextColor(0xFF546E7A.toInt())
            text = "正在下载 PC 候选清单…"
        }
        root.addView(statusView)

        // ── PC 主动推送（P3）：PC 侧选股/盘前情报完成即写入 pc/push/，这里只做展示 ──
        pushView = TextView(context).apply {
            setPadding(16.dp(), 0, 16.dp(), 6.dp())
            textSize = 12f
            setTextColor(0xFF6A1B9A.toInt())
            visibility = View.GONE
        }
        root.addView(pushView)

        // ── 列表 ──
        val scroll = ScrollView(context).apply { isFillViewport = true }
        listBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp(), 4.dp(), 12.dp(), 80.dp())
        }
        scroll.addView(listBox, ViewGroup.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        emptyView = TextView(context).apply {
            text = "暂无 PC 候选清单\n请先在 PC 端运行 smalltools/_publish_candidates.py 发布"
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(0xFF90A4AE.toInt())
            visibility = View.GONE
        }
        root.addView(emptyView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // ── 底部操作 ──
        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(16.dp(), 10.dp(), 16.dp(), 16.dp())
            setBackgroundColor(0xFFFAFAFA.toInt())
        }
        addBtn = TextView(context).apply {
            text = "➕ 全部加入 AI 精选"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 12.dp(), 0, 12.dp())
            background = rnd(0xFF43A047.toInt(), 8.dp())
            isEnabled = false
            setOnClickListener { addAllToAiSelected() }
        }
        bottom.addView(addBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bottom)
        return root
    }

    private fun TextBtn(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 15f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(10.dp(), 8.dp(), 10.dp(), 8.dp())
        setOnClickListener { onClick() }
    }

    private fun rnd(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    // ═══════════════════════════════════════════
    // 加载与渲染
    // ═══════════════════════════════════════════

    /** 优先走联网中继（实时，无需 IP）；中继不可用则回退 COS 直下已发布的候选。 */
    private fun refresh() {
        usingPc = true
        watchJob?.cancel()
        statusView.text = "正在经中继拉取 PC 候选…"
        addBtn.isEnabled = false
        scope.launch {
            try {
                val json = PcBridgeClient.fetchCandidates()
                latestJson = json
                usingPc = true
                statusView.text = "🔗 中继拉取成功（联网实时）"
                relayView.text = CosRelayClient.statusText(context)
                render(json)
                startWatch()
            } catch (e: Exception) {
                usingPc = false
                statusView.text = "中继不可用（${e.message}），改用 COS 缓存…"
                refreshFromCloud()
            }
        }
    }

    private fun refreshFromCloud() {
        usingPc = false
        watchJob?.cancel()
        val cfg = cloud.loadConfig()
        if (!cloud.isConfigured(cfg)) {
            showEmpty("云同步未配置（app_config cloud_sync）\n配置 bucket/密钥后即可下载 PC 候选")
            return
        }
        statusView.text = "正在下载 PC 候选清单…"
        addBtn.isEnabled = false
        scope.launch {
            cloud.downloadCandidates(cfg) { statusView.text = it }.fold(
                onSuccess = { json -> latestJson = json; render(json) },
                onFailure = { e ->
                    statusView.text = "下载失败：${e.message}"
                    val cached = context.getSharedPreferences("cloud_sync", Context.MODE_PRIVATE)
                    val last = java.io.File(context.filesDir, "pc_candidates.json")
                    if (last.exists() && last.length() > 0) {
                        statusView.append("（显示上次缓存）")
                        latestJson = last.readText()
                        render(last.readText())
                    } else {
                        showEmpty("下载失败：${e.message}")
                    }
                }
            )
        }
    }

    /** 中继轮询: PC 候选更新时自动刷新（无长轮询，固定间隔拉取）。 */
    private fun startWatch() {
        watchJob?.cancel()
        watchJob = PcBridgeClient.watch(scope = scope) { json ->
            latestJson = json
            statusView.text = "🔗 中继更新 ${System.currentTimeMillis() % 100000}"
            relayView.text = CosRelayClient.statusText(context)
            render(json)
        }
        startPushWatch()
    }

    /**
     * P3：订阅 PC **主动推送**（选股完成 / 盘前情报 / 任务结束）。
     *
     * 与 [startWatch] 互补：候选是「APK 问、PC 才答」，而推送是 PC 侧主动写进
     * `pc/push/{deviceId}/` 的，打开本对话框就能看到，不必等下一次轮询窗口。
     * 每条新消息弹一次**系统**通知（PC 侧已同步推过企业微信，这里不重复推微信）。
     */
    private fun startPushWatch() {
        pushJob?.cancel()
        pushJob = PcBridgeClient.watchPushes(scope = scope) { list ->
            val latest = list.last()
            val title = latest.optString("title").ifBlank { "PC 推送" }
            val content = latest.optString("content")
            pushView.visibility = View.VISIBLE
            pushView.text = "📨 $title（本次 ${list.size} 条新推送）\n${content.take(160)}"
            scope.launch {
                runCatching {
                    com.chin.stockanalysis.notification.TradeNotifier
                        .sendSystemOnly(context, "📨 $title", content.take(300), tag = "pc_push")
                }
            }
        }
    }

    private fun showEmpty(msg: String) {
        emptyView.text = msg
        emptyView.visibility = View.VISIBLE
        listBox.removeAllViews()
        addBtn.isEnabled = false
    }

    private fun render(jsonText: String) {
        emptyView.visibility = View.GONE
        listBox.removeAllViews()
        try {
            val j = JSONObject(jsonText)
            val asof = j.optString("asof", "-")
            val state = j.optString("market_state", "-")
            val advice = j.optString("advice", "")
            val pool = j.optInt("pool_total", 0)
            val gen = j.optString("generated_at", "").replace("T", " ")

            statusView.text = String.format(
                Locale.CHINA, "大盘:%s | 数据截至 %s | 池 %d 只\n%s",
                stateColor(state, state), asof, pool, advice
            )
            if (gen.isNotBlank()) statusView.append("\n发布: $gen")

            var total = 0
            val order = listOf("超短", "短线", "中线", "长线")
            val groups = j.optJSONObject("groups") ?: JSONObject()
            for (period in order) {
                val arr = groups.optJSONArray(period) ?: org.json.JSONArray()
                if (arr.length() == 0) continue
                total += arr.length()
                listBox.addView(sectionTitle("$period · ${arr.length()} 只"))
                for (i in 0 until arr.length()) {
                    listBox.addView(stockRow(arr.getJSONObject(i)))
                }
            }
            // 预备队
            val prep = j.optJSONArray("prepared") ?: org.json.JSONArray()
            if (prep.length() > 0) {
                listBox.addView(sectionTitle("🎯 预备队 · 前 ${prep.length()} 只"))
                for (i in 0 until prep.length()) {
                    listBox.addView(stockRow(prep.getJSONObject(i), withPeriod = true))
                }
            }
            // 热点板块
            val hot = j.optJSONArray("hot_sectors") ?: org.json.JSONArray()
            if (hot.length() > 0) {
                listBox.addView(sectionTitle("🔥 热点板块（近20日涨幅）"))
                for (i in 0 until hot.length()) {
                    val h = hot.getJSONObject(i)
                    listBox.addView(hotRow(h.optString("industry"), h.optDouble("avg_pct_20d"), h.optString("names")))
                }
            }
            // 板块轮动（新 schema 2: 板块动量 + 催化剂 + 龙头）
            val rot = j.optJSONArray("rotation") ?: org.json.JSONArray()
            if (rot.length() > 0) {
                listBox.addView(sectionTitle("🎡 板块轮动（动量+催化剂）"))
                for (i in 0 until rot.length()) {
                    val r = rot.getJSONObject(i)
                    listBox.addView(rotationRow(r))
                }
            }
            // ETF 重仓·低吸观察（2026-09-06：核心ETF前十大重仓覆盖 + 距60日高回撤，来源 _etf_holdings.py）
            val eh = j.optJSONObject("etf_holdings")
            if (eh != null && eh.length() > 0) {
                val topArr = eh.optJSONArray("top") ?: org.json.JSONArray()
                if (topArr.length() > 0) {
                    listBox.addView(sectionTitle("🧲 ETF 重仓·低吸观察（${eh.optString("updated", "").take(10)}）"))
                    for (i in 0 until minOf(topArr.length(), 10)) {
                        listBox.addView(etfRow(topArr.getJSONObject(i)))
                    }
                }
            }
            addBtn.isEnabled = total > 0
        } catch (e: Exception) {
            showEmpty("候选数据解析失败：${e.message}")
        }
    }

    private fun stateColor(state: String, fallback: String): String = when (state) {
        "BULLISH" -> "🟢 多头"
        "BEARISH" -> "🔴 弱势"
        "OSCILLATION" -> "🟡 震荡"
        else -> fallback
    }

    private fun sectionTitle(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 14f
        setTypeface(null, Typeface.BOLD)
        setTextColor(0xFF37474F.toInt())
        setPadding(4.dp(), 14.dp(), 4.dp(), 6.dp())
    }

    private fun stockRow(j: JSONObject, withPeriod: Boolean = false): View {
        val name = j.optString("name", "-")
        val secid = j.optString("secid", "")
        val industry = j.optString("industry", "-")
        val board = j.optString("board", "")
        val ratio = j.optDouble("ratio", 0.0)
        val pass = j.optInt("pass", 0)
        val total = j.optInt("total", 0)
        val period = j.optString("period", "")

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10.dp(), 10.dp(), 10.dp(), 10.dp())
            background = rnd(0xFFECEFF1.toInt(), 8.dp())
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = 6.dp()
            layoutParams = lp
        }
        row.addView(TextView(context).apply {
            text = name
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF212121.toInt())
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(context).apply {
            text = buildString {
                append(secid.removePrefix("sh").removePrefix("sz"))
                if (withPeriod && period.isNotBlank()) append(" · $period")
                append("  ${board}")
            }
            textSize = 11f
            setTextColor(0xFF78909C.toInt())
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(context).apply {
            text = "$industry ${(ratio * 100).toInt()}%"
            textSize = 11f
            setTextColor(0xFF1565C0.toInt())
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun etfRow(s: JSONObject): View {
        val code = s.optString("code", "")
        val name = s.optString("name", "-")
        val n = s.optInt("n", 1)
        val pos = s.optDouble("pos60", 0.0)
        val posText = if (s.has("pos60")) String.format(Locale.CHINA, "距60日高%+.1f%%", pos)
        else "位置-"
        // 回撤越深越低吸价值，绿→深、橙→浅；高位红
        val posColor = when {
            pos <= -20 -> 0xFF2E7D32.toInt()
            pos <= -8 -> 0xFF43A047.toInt()
            pos < 0 -> 0xFFFB8C00.toInt()
            else -> 0xFFE53935.toInt()
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10.dp(), 8.dp(), 10.dp(), 8.dp())
            background = rnd(0xFFECEFF1.toInt(), 8.dp())
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = 4.dp()
            layoutParams = lp
            if (code.isNotBlank()) {
                setOnClickListener {
                    try {
                        StockDetailNavigator.navigateFromActivity(
                            context as androidx.fragment.app.FragmentActivity, code, name
                        )
                    } catch (_: Exception) { /* 非 Activity 环境忽略 */ }
                }
            }
        }
        row.addView(TextView(context).apply {
            text = name
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF212121.toInt())
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(context).apply {
            text = "${n}只ETF覆盖"
            textSize = 11f
            setTextColor(0xFF78909C.toInt())
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.7f))
        row.addView(TextView(context).apply {
            text = posText
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(posColor)
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun hotRow(industry: String, avgPct: Double, names: String): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10.dp(), 8.dp(), 10.dp(), 8.dp())
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = 4.dp()
            layoutParams = lp
        }
        row.addView(TextView(context).apply {
            text = industry
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFE65100.toInt())
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(context).apply {
            text = String.format(Locale.CHINA, "+%.1f%%", avgPct)
            textSize = 13f
            setTextColor(0xFFD32F2F.toInt())
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    /** 板块轮动行: 板块名 + 动量 + 催化剂理由 + 龙头股 */
    private fun rotationRow(r: JSONObject): View {
        val industry = r.optString("industry", "-")
        val secMom = r.optDouble("sec_mom", 0.0)
        val cat = r.optString("catalyst_reason", "")
        val leaders = r.optJSONArray("leaders") ?: org.json.JSONArray()
        val leaderNames = buildString {
            for (i in 0 until leaders.length()) {
                if (i > 0) append(" ")
                append(leaders.getJSONObject(i).optString("name", "-"))
            }
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dp(), 8.dp(), 10.dp(), 8.dp())
            background = rnd(0xFFF3E5F5.toInt(), 8.dp())
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = 6.dp()
            layoutParams = lp
        }
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(context).apply {
            text = industry
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF4A148C.toInt())
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(TextView(context).apply {
            text = String.format(Locale.CHINA, "%+.1f%%", secMom)
            textSize = 13f
            setTextColor(0xFFD32F2F.toInt())
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(top)
        if (cat.isNotBlank()) {
            card.addView(TextView(context).apply {
                text = "💡 $cat"
                textSize = 11f
                setTextColor(0xFF6A1B9A.toInt())
                setPadding(0, 4.dp(), 0, 0)
            })
        }
        if (leaderNames.isNotBlank()) {
            card.addView(TextView(context).apply {
                text = "龙头: $leaderNames"
                textSize = 11f
                setTextColor(0xFF455A64.toInt())
                setPadding(0, 2.dp(), 0, 0)
            })
        }
        return card
    }

    // ═══════════════════════════════════════════
    // 操作：全部加入 AI 精选
    // ═══════════════════════════════════════════

    private fun addAllToAiSelected() {
        val json = latestJson ?: return
        scope.launch {
            try {
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
                val j = JSONObject(json)
                val stocks = mutableListOf<AiSelectedStockEntity>()
                fun collect(arr: org.json.JSONArray, reasonPrefix: String) {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val secid = o.optString("secid", "")
                        if (secid.length < 2) continue
                        val ratio = o.optDouble("ratio", 0.0)
                        stocks.add(
                            AiSelectedStockEntity(
                                stockCode = secid.substring(2),
                                stockName = o.optString("name", "-"),
                                source = "pc_candidates",
                                selectedDate = today,
                                score = (ratio * 100).toInt().coerceIn(0, 100),
                                reason = "$reasonPrefix ${o.optString("industry", "")} 命中${o.optInt("pass", 0)}/${o.optInt("total", 0)}"
                            )
                        )
                    }
                }
                (j.optJSONObject("groups") ?: JSONObject()).keys().forEach { k ->
                    collect(j.optJSONObject("groups")!!.optJSONArray(k) ?: org.json.JSONArray(), "[$k]")
                }
                collect(j.optJSONArray("prepared") ?: org.json.JSONArray(), "[预备队]")
                if (stocks.isEmpty()) {
                    Toast.makeText(context, "清单为空，无可加入", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                AppBackgroundRunner.saveAiSelectedStocks(context, stocks.distinctBy { it.stockCode })
                Toast.makeText(context, "已加入 AI 精选 ${stocks.size} 只", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "加入失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
