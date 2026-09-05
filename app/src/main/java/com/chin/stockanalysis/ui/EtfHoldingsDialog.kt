package com.chin.stockanalysis.ui

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
import com.chin.stockanalysis.cloud.CloudSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * ETF 重仓 · 低吸观察对话框（股票栏「K线趋势」页「🧲 ETF池」入口）。
 *
 * 数据源：PC 候选清单（_publish_candidates.py 注入的顶层 `etf_holdings` 摘要，
 * 由 smalltools/_etf_holdings.py 生成：核心指数ETF 前十大重仓覆盖矩阵 + 距60日高回撤 pos60）。
 * 点击行 → 打开该股股票详情（含K线/形态，便于从K线角度确认低吸位）。
 */
class EtfHoldingsDialog(context: Context) : Dialog(context) {

    private val density = context.resources.displayMetrics.density
    private val cloud = CloudSyncManager(context)

    private lateinit var statusView: TextView
    private lateinit var listBox: LinearLayout

    private fun Int.dp() = (this * density + 0.5f).toInt()

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(buildView())
        window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(null)
        }
        refresh()
    }

    private fun TextBtn(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 15f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = rnd(0xFF1976D2.toInt(), 16.dp())
        setPadding(10.dp(), 6.dp(), 10.dp(), 6.dp())
        setOnClickListener { onClick() }
    }

    private fun rnd(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun buildView(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFAFBFC.toInt())
        }
        // 标题栏
        val titleBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF263238.toInt())
            setPadding(10.dp(), 8.dp(), 6.dp(), 8.dp())
        }
        titleBar.addView(TextView(context).apply {
            text = "🧲 ETF 重仓 · 低吸观察"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleBar.addView(TextBtn("🔄") { refresh() })
        titleBar.addView(TextView(context).apply {
            text = "✕"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(8.dp(), 6.dp(), 8.dp(), 6.dp())
            setOnClickListener { dismiss() }
        })
        root.addView(titleBar)

        statusView = TextView(context).apply {
            textSize = 11f
            setTextColor(0xFF607D8B.toInt())
            setPadding(10.dp(), 4.dp(), 10.dp(), 2.dp())
        }
        root.addView(statusView)

        listBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dp(), 4.dp(), 10.dp(), 16.dp())
        }
        root.addView(ScrollView(context).apply {
            addView(listBox)
        }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return root
    }

    private fun refresh() {
        val cfg = cloud.loadConfig()
        if (!cloud.isConfigured(cfg)) {
            statusView.text = "云同步未配置（app_config cloud_sync），无法下载 PC 候选摘要"
            return
        }
        statusView.text = "正在下载 PC 候选清单…"
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            cloud.downloadCandidates(cfg) { statusView.text = it }.fold(
                onSuccess = { json -> render(json) },
                onFailure = { e ->
                    statusView.text = "下载失败：${e.message}"
                    val cached = File(context.filesDir, "pc_candidates.json")
                    if (cached.exists() && cached.length() > 0) {
                        statusView.append("（显示上次缓存）")
                        render(cached.readText())
                    } else {
                        statusView.text = "暂无数据。请先在 PC 端运行 smalltools/_etf_holdings.py 并发布候选清单。"
                    }
                }
            )
        }
    }

    private fun render(jsonText: String) {
        listBox.removeAllViews()
        try {
            val j = JSONObject(jsonText)
            val eh = j.optJSONObject("etf_holdings")
            val top = eh?.optJSONArray("top") ?: return
            val updated = eh.optString("updated", "").take(10)
            val note = eh.optString("note", "")
            statusView.text = "数据 ${updated} 更新 | 覆盖 ${eh.optInt("fund_count", 0)} 只ETF · " +
                    "${eh.optInt("stock_count", 0)} 只股票\n按「被覆盖ETF数 + 距60日高回撤」排序，点击行查看K线" +
                    (if (note.isNotBlank()) " | ${note}" else "")
            listBox.addView(TextView(context).apply {
                text = "👇 核心ETF重仓 · 低吸价值榜（Top ${top.length()}）"
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(0xFF455A64.toInt())
                setPadding(2.dp(), 6.dp(), 2.dp(), 6.dp())
            })
            for (i in 0 until top.length()) {
                listBox.addView(row(top.getJSONObject(i)))
            }
        } catch (e: Exception) {
            statusView.text = "解析失败：${e.message}"
        }
    }

    private fun row(s: JSONObject): View {
        val code = s.optString("code", "")
        val name = s.optString("name", "-")
        val n = s.optInt("n", 1)
        val hasPos = s.has("pos60")
        val pos = s.optDouble("pos60", 0.0)
        val posText = if (hasPos) String.format(Locale.CHINA, "距60日高 %+.1f%%", pos) else "位置-"
        val posColor = when {
            !hasPos -> 0xFF90A4AE.toInt()
            pos <= -20 -> 0xFF2E7D32.toInt()   // 深度回撤（深绿=低吸价值）
            pos <= -8 -> 0xFF43A047.toInt()
            pos < 0 -> 0xFFFB8C00.toInt()
            else -> 0xFFE53935.toInt()          // 高位追风险
        }
        val fundsArr = s.optJSONArray("funds") ?: org.json.JSONArray()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10.dp(), 9.dp(), 10.dp(), 9.dp())
            background = rnd(if (pos <= -8) 0xFFE8F5E9.toInt() else 0xFFECEFF1.toInt(), 8.dp())
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
        // 名称区（垂直：名称 + 覆盖ETF简写）
        val nameBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        nameBox.addView(TextView(context).apply {
            text = name
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF212121.toInt())
        })
        val covers = buildString {
            for (f in 0 until minOf(fundsArr.length(), 4)) {
                val fc = fundsArr.optString(f, "")
                if (fc.isNotBlank()) { if (isNotEmpty()) append("·"); append(fc) }
            }
        }
        if (covers.isNotBlank()) nameBox.addView(TextView(context).apply {
            text = "${n}只ETF覆盖  $covers"
            textSize = 10f
            setTextColor(0xFF78909C.toInt())
        })
        row.addView(nameBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
        row.addView(TextView(context).apply {
            text = posText
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(posColor)
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }
}
