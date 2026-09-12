package com.chin.stockanalysis.strategy.trade.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.strategy.trade.EtfStrategyLab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ## ETF 选股思路实验室 · 全屏视图（2026-09-10 新增）
 *
 * 由工作台·ETF 页「🧪思路实验室」按钮唤起，专门分析「如何选择 ETF」。
 * 五个区块全部由公共构件 [QuantUiKit]（深色主题）构造，与三周期页共用同一套 UI 语言：
 *
 *   ① 参数         —— 生效参数（信号阈值 / 离场参数 / 门控）展示 + 编辑 / 恢复默认 / 重跑
 *   ② 选股判定     —— 13 只 ETF 五条件（RAS/MACD/OBV/RSI/位置）命中明细 + 推荐分
 *   ③ 买卖/做T信号 —— 底仓买入 / 底仓清仓 / 正T低吸 / 反T高抛（思路文档口径）
 *   ④ 回测         —— T+1 开盘成交、单仓状态机、大盘门控，输出胜率/盈亏比/收益/回撤
 *   ⑤ 网格拟合     —— 搜索「信号阈值 × 离场参数」最优组合，一键应用 → 修正选股思路
 *
 * 引擎见 [EtfStrategyLab]（与 PC `smalltools/_etf_buy.py` 同口径）。
 */
object EtfStrategyLabPanel {

    private val P = QuantUiKit.DARK

    fun show(fragment: Fragment) {
        if (!fragment.isAdded) return
        val ctx = fragment.requireContext()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(QuantUiKit.color(P.bg))
            setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
        }
        // ── 顶栏 ──
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(ctx).apply {
            text = "🧪 ETF 选股思路实验室"
            textSize = 16f
            setTextColor(QuantUiKit.color(P.title))
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val dialogRef = arrayOfNulls<Dialog>(1)
        top.addView(TextView(ctx).apply {
            text = "✕ 关闭"
            textSize = 13f
            setTextColor(QuantUiKit.color(P.accent))
            setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
            isClickable = true
            setOnClickListener { dialogRef[0]?.dismiss() }
        })
        root.addView(top)

        val scroll = ScrollView(ctx).apply { isFillViewport = true }
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 24))
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val dialog = Dialog(ctx).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(root)
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            window?.setBackgroundDrawableResource(android.R.color.black)
        }
        dialogRef[0] = dialog

        var params = EtfStrategyLab.loadParams(ctx)

        fun rerun(label: String) {
            body.removeAllViews()
            body.addView(QuantUiKit.note(ctx, P, "⏳ $label…（本地行情 + 全历史回测 + 网格拟合）",
                textSize = 12f, colorHex = P.warn))
            fragment.lifecycleScope.launch {
                val report = withContext(Dispatchers.Default) { EtfStrategyLab.run(ctx, params) }
                if (!fragment.isAdded) return@launch
                render(ctx, body, report, params,
                    onEdit = { openParamEditor(ctx, params) { np -> params = np; rerun("重新运行") } },
                    onReset = {
                        params = EtfStrategyLab.resetParams(ctx)
                        Toast.makeText(ctx, "已恢复默认参数", Toast.LENGTH_SHORT).show()
                        rerun("重新运行")
                    },
                    onRerun = { rerun("重新运行") },
                    onApplyFit = { fit ->
                        params = params.copy(
                            rsiHi = fit.rsiHi, ddLo = fit.ddLo, ddHi = fit.ddHi,
                            tp = fit.tp, sl = fit.sl, hold = fit.hold
                        )
                        EtfStrategyLab.saveParams(ctx, params)
                        Toast.makeText(ctx, "✅ 已应用拟合参数并保存为生效参数", Toast.LENGTH_SHORT).show()
                        rerun("按新参数重新运行")
                    })
            }
        }

        rerun("实验室启动")
        dialog.show()
    }

    // ══════════════════════════ 渲染 ══════════════════════════

    private fun render(
        ctx: Context,
        body: LinearLayout,
        r: EtfStrategyLab.Report,
        params: EtfStrategyLab.Params,
        onEdit: () -> Unit,
        onReset: () -> Unit,
        onRerun: () -> Unit,
        onApplyFit: (EtfStrategyLab.FitRow) -> Unit
    ) {
        body.removeAllViews()

        if (r.error != null) {
            body.addView(QuantUiKit.note(ctx, P, "⚠ ${r.error}", textSize = 12.5f, colorHex = P.bad))
            body.addView(buttonRow(ctx, listOf(
                Triple("📈 重新运行", "#E65100", onRerun)
            )))
            return
        }

        // ── 概览 ──
        body.addView(QuantUiKit.note(ctx, P,
            "数据截至 ${r.asOf} · 标的池 ${r.poolSize} 只 · 门控 ${if (r.gateOk) "开 ✅" else "关 ⛔"}",
            textSize = 12f, colorHex = P.text))
        body.addView(QuantUiKit.note(ctx, P, r.gateNote, textSize = 10.5f, colorHex = P.tip))

        // ── ① 参数 ──
        body.addView(QuantUiKit.titleRow(ctx, P, "① 生效参数", "（可编辑 / 拟合修正 / 恢复默认）"))
        body.addView(kv(ctx, "大盘门控（沪深300 结构多头）", if (params.useGate) "开启" else "关闭"))
        body.addView(kv(ctx, "选股条件 RSI6 区间", "${params.rsiLo.toInt()} ~ ${params.rsiHi.toInt()}"))
        body.addView(kv(ctx, "选股条件 250日高回撤区间", "${params.ddLo.toInt()}% ~ ${params.ddHi.toInt()}%"))
        body.addView(kv(ctx, "底仓清仓超买线", "RSI6 > ${params.rsiOverbought.toInt()}（或 RAS 转绿 / MACD 死叉）"))
        body.addView(kv(ctx, "做T阈值", "正T低吸 ≤ ${params.tBuyRsi.toInt()} · 反T高抛 ≥ ${params.tSellRsi.toInt()}"))
        body.addView(kv(ctx, "离场参数", "tp +${params.tp}% / sl ${params.sl}% / ${params.hold}日 · 冷却${params.cool}日"))
        body.addView(buttonRow(ctx, listOf(
            Triple("📝 编辑参数", "#1565C0", onEdit),
            Triple("♻ 恢复默认", "#455A64", onReset),
            Triple("🔄 重新运行", "#E65100", onRerun)
        )))

        // ── ② 选股判定 ──
        val selN = r.picks.count { it.selected }
        body.addView(QuantUiKit.titleRow(ctx, P, "② 选股判定（五条件）",
            "（入选 $selN / ${r.picks.size} 只）"))
        body.addView(QuantUiKit.note(ctx, P,
            "①RAS绿转红 ②MACD金叉/DIF拐头 ③OBV上行 ④RSI6区间 ⑤回撤区间 —— 五中全中=底仓买入信号",
            textSize = 10f, colorHex = P.tip))
        val pickCols = listOf("名称", "代码", "收盘", "回撤250", "RSI6", "RAS", "MACD", "OBV", "命中", "状态")
        val pickW = intArrayOf(84, 78, 58, 60, 42, 58, 66, 52, 60, 50)
        val pickTable = QuantUiKit.table(ctx, P, pickCols, pickW, body)
        if (r.picks.isEmpty()) {
            pickTable.addView(QuantUiKit.emptyHint(ctx, P, "  （无可判定标的）"))
        } else {
            for (p in r.picks) {
                val vals = listOf(
                    p.name, p.code,
                    "%.3f".format(p.close),
                    "%.0f%%".format(p.dd250),
                    "%.0f".format(p.rsi6),
                    p.ras, p.macd, p.obv, p.hitText(),
                    if (p.selected) "★入选" else "${p.pass}/5"
                )
                pickTable.addView(QuantUiKit.row(
                    ctx, P, vals, pickW,
                    aligns = IntArray(vals.size) { if (it in 2..4) Gravity.END else Gravity.CENTER },
                    colorOf = { idx, v -> pickColor(idx, v) },
                    bgHex = if (p.selected) "#1D3A2A" else null
                ))
            }
        }

        // ── ③ 买卖 / 做T 信号 ──
        body.addView(QuantUiKit.titleRow(ctx, P, "③ 买卖 / 做T 信号", "（${r.signals.size} 条）"))
        if (r.signals.isEmpty()) {
            body.addView(QuantUiKit.note(ctx, P,
                "  （今日无信号。底仓五条件全中才买入 —— 空仓多数时间是正确行为）",
                textSize = 11f, colorHex = P.tip))
        } else {
            for (s in r.signals) {
                body.addView(kv(ctx,
                    "${kindIcon(s.kind)} ${s.kind} · ${s.name} ${s.code}",
                    "收%s".format("%.3f".format(s.close)), valueColor = kindColor(s.kind)))
                body.addView(QuantUiKit.note(ctx, P, "     ${s.detail}",
                    textSize = 10.5f, colorHex = P.subText))
            }
        }

        // ── ④ 回测 ──
        body.addView(QuantUiKit.titleRow(ctx, P, "④ 回测（当前参数 · 全历史）",
            "（${r.stats.n} 笔回合）"))
        if (r.stats.n == 0) {
            body.addView(QuantUiKit.note(ctx, P,
                "  （当前参数未产生完整回合。可放宽 RSI/回撤区间，或用下方拟合结果）",
                textSize = 11f, colorHex = P.tip))
        } else {
            val st = r.stats
            body.addView(kv(ctx, "回合数", "${st.n} 笔"))
            body.addView(kv(ctx, "胜率", "%.1f%%".format(st.winRate), valueColor = rateColor(st.winRate)))
            body.addView(kv(ctx, "平均盈利 / 平均亏损", "+%.2f%% / %.2f%%".format(st.avgWin, st.avgLoss)))
            body.addView(kv(ctx, "盈亏比 (profit factor)", "%.2f".format(st.profitFactor)))
            body.addView(kv(ctx, "累计收益", "%+.1f%%".format(st.totalRet), valueColor = pnlColor(st.totalRet)))
            body.addView(kv(ctx, "年化收益", "%+.1f%%".format(st.cagr), valueColor = pnlColor(st.cagr)))
            body.addView(kv(ctx, "最大回撤", "%.1f%%".format(st.mdd), valueColor = QuantUiKit.color(P.down)))
            body.addView(kv(ctx, "平均持有", "%.0f 交易日".format(st.avgDays)))
            // 最近回合明细
            val recent = r.trades.takeLast(8)
            if (recent.isNotEmpty()) {
                body.addView(QuantUiKit.note(ctx, P, "最近回合：", textSize = 11f, colorHex = P.subText))
                for (t in recent.reversed()) {
                    body.addView(QuantUiKit.note(ctx, P,
                        "     ${t.code}  ${t.entryDate}→${t.exitDate}  ${t.days}日  %+.2f%%".format(t.pnlPct),
                        mono = true, textSize = 10.5f, colorHex = hexOf(pnlColor(t.pnlPct))))
                }
            }
        }

        // ── ⑤ 网格拟合 ──
        body.addView(QuantUiKit.titleRow(ctx, P, "⑤ 网格拟合", "（点行可应用为生效参数）"))
        if (r.fits.isEmpty()) {
            body.addView(QuantUiKit.note(ctx, P,
                "  （未找到满足最小样本(≥8笔)的参数组合；请先同步更长历史行情）",
                textSize = 11f, colorHex = P.tip))
        } else {
            body.addView(QuantUiKit.note(ctx, P,
                "评分 = 胜率×2.5 + 盈亏比×4 + 收益×0.6 − 回撤（与 PC _etf_buy 同口径，防过拟合）",
                textSize = 10f, colorHex = P.tip))
            val fitCols = listOf("信号阈值", "tp/sl/hold", "笔数", "胜率", "总收益", "评分")
            val fitW = intArrayOf(120, 88, 46, 50, 62, 48)
            val fitTable = QuantUiKit.table(ctx, P, fitCols, fitW, body)
            for ((idx, f) in r.fits.withIndex()) {
                val vals = listOf(
                    "RSI≤${f.rsiHi.toInt()} 回撤${f.ddLo.toInt()}~${f.ddHi.toInt()}%",
                    "${f.tp}/${f.sl}/${f.hold}",
                    "${f.stats.n}",
                    "%.0f%%".format(f.stats.winRate),
                    "%+.0f%%".format(f.stats.totalRet),
                    "%.0f".format(f.score)
                )
                fitTable.addView(QuantUiKit.row(
                    ctx, P, vals, fitW,
                    aligns = IntArray(vals.size) { if (it >= 2) Gravity.END else Gravity.CENTER },
                    colorOf = { i, v -> if (i == 3) rateColor(v.replace("%", "").toDoubleOrNull() ?: 0.0) else null },
                    boldFirst = false,
                    bgHex = if (idx == 0) "#1D3A2A" else null,
                    onClick = { onApplyFit(f) }
                ))
            }
            val best = r.fits.first()
            body.addView(buttonRow(ctx, listOf(
                Triple("✅ 应用最佳拟合", "#2E7D32", { onApplyFit(best) })
            )))
        }

        // ── 日志 ──
        if (r.log.isNotEmpty()) {
            body.addView(QuantUiKit.titleRow(ctx, P, "· 运行日志", textSize = 11.5f))
            body.addView(QuantUiKit.note(ctx, P, r.log.joinToString("\n"),
                mono = true, textSize = 10.5f, colorHex = P.tip))
        }
    }

    // ══════════════════════════ 参数编辑 ══════════════════════════

    private fun openParamEditor(
        ctx: Context,
        cur: EtfStrategyLab.Params,
        onSaved: (EtfStrategyLab.Params) -> Unit
    ) {
        fun label(t: String) = TextView(ctx).apply {
            text = t
            textSize = 12f
            setTextColor(QuantUiKit.color(P.accent))
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 2))
        }
        fun numField(v: String, decimal: Boolean = true) = EditText(ctx).apply {
            setText(v)
            textSize = 13f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER or
                (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0) or
                InputType.TYPE_NUMBER_FLAG_SIGNED
        }

        val gateCb = CheckBox(ctx).apply {
            text = "启用大盘门控（沪深300 close>MA20>MA60）"
            textSize = 12f
            isChecked = cur.useGate
        }
        val rsiHiEt = numField("%.0f".format(cur.rsiHi), false)
        val ddLoEt = numField("%.0f".format(cur.ddLo), false)
        val ddHiEt = numField("%.0f".format(cur.ddHi), false)
        val tpEt = numField("%.1f".format(cur.tp))
        val slEt = numField("%.1f".format(cur.sl))
        val holdEt = numField(cur.hold.toString(), false)
        val coolEt = numField(cur.cool.toString(), false)

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 18), dp(ctx, 4), dp(ctx, 18), dp(ctx, 4))
            addView(gateCb)
            addView(label("选股 RSI6 上限（下限固定 30）")); addView(rsiHiEt)
            addView(label("250日高回撤 下限 %")); addView(ddLoEt)
            addView(label("250日高回撤 上限 %")); addView(ddHiEt)
            addView(label("止盈 tp %")); addView(tpEt)
            addView(label("止损 sl %（负数）")); addView(slEt)
            addView(label("时间离场 持有交易日")); addView(holdEt)
            addView(label("平仓后冷却交易日")); addView(coolEt)
        }
        val scroll = ScrollView(ctx).apply { isFillViewport = true; addView(box) }
        AlertDialog.Builder(ctx)
            .setTitle("📝 ETF 思路参数")
            .setView(scroll)
            .setPositiveButton("保存并重跑") { _, _ ->
                val np = cur.copy(
                    useGate = gateCb.isChecked,
                    rsiHi = rsiHiEt.text.toString().toDoubleOrNull() ?: cur.rsiHi,
                    ddLo = ddLoEt.text.toString().toDoubleOrNull() ?: cur.ddLo,
                    ddHi = ddHiEt.text.toString().toDoubleOrNull() ?: cur.ddHi,
                    tp = tpEt.text.toString().toDoubleOrNull() ?: cur.tp,
                    sl = slEt.text.toString().toDoubleOrNull() ?: cur.sl,
                    hold = holdEt.text.toString().toIntOrNull() ?: cur.hold,
                    cool = coolEt.text.toString().toIntOrNull() ?: cur.cool
                )
                EtfStrategyLab.saveParams(ctx, np)
                Toast.makeText(ctx, "参数已保存为生效参数", Toast.LENGTH_SHORT).show()
                onSaved(np)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ══════════════════════════ 小块构件 ══════════════════════════

    private fun kv(ctx: Context, label: String, value: String, valueColor: Int? = null): LinearLayout =
        QuantUiKit.kvRow(ctx, P, label, value, valueColor = valueColor?.let { hexOf(it) })

    private fun buttonRow(
        ctx: Context,
        items: List<Triple<String, String, () -> Unit>>
    ): LinearLayout {
        val row = QuantUiKit.buttonRow(ctx, P)
        for ((text, colorHex, cb) in items) {
            row.addView(QuantUiKit.button(ctx, text, colorHex, weight = 1f,
                textSize = 11f, onClick = cb))
        }
        return row
    }

    private fun pickColor(idx: Int, v: String): Int? = when (idx) {
        0 -> QuantUiKit.color("#FFE0B2")
        1 -> QuantUiKit.color("#CE93D8")
        2, 3, 4 -> QuantUiKit.color(P.text)
        5 -> when {
            v.contains("绿转红") -> QuantUiKit.color(P.up)
            v.contains("红转绿") -> QuantUiKit.color(P.down)
            v.contains("强势") -> QuantUiKit.color(P.up)
            else -> QuantUiKit.color(P.subText)
        }
        6 -> when {
            v.contains("金叉") || v.contains("多头") || v.contains("拐头") -> QuantUiKit.color(P.up)
            v.contains("死叉") || v.contains("空头") -> QuantUiKit.color(P.down)
            else -> QuantUiKit.color(P.subText)
        }
        7 -> if (v.contains("上行")) QuantUiKit.color(P.up) else QuantUiKit.color(P.down)
        8 -> if (v == "—") QuantUiKit.color(P.subText) else QuantUiKit.color(P.warn)
        9 -> when {
            v.contains("入选") -> QuantUiKit.color(P.ok)
            v == "5/5" -> QuantUiKit.color(P.ok)
            else -> QuantUiKit.color(P.subText)
        }
        else -> null
    }

    private fun kindIcon(kind: String): String = when (kind) {
        "底仓买入" -> "🎯"
        "底仓清仓" -> "🛑"
        "正T低吸" -> "🔵"
        "反T高抛" -> "🔴"
        else -> "·"
    }

    private fun kindColor(kind: String): Int = when (kind) {
        "底仓买入" -> QuantUiKit.color(P.ok)
        "底仓清仓" -> QuantUiKit.color(P.bad)
        "正T低吸" -> QuantUiKit.color(P.up)
        "反T高抛" -> QuantUiKit.color(P.down)
        else -> QuantUiKit.color(P.text)
    }

    private fun rateColor(rate: Double): Int =
        if (rate >= 60) QuantUiKit.color(P.ok)
        else if (rate >= 45) QuantUiKit.color(P.warn) else QuantUiKit.color(P.bad)

    private fun pnlColor(v: Double): Int =
        if (v > 0) QuantUiKit.color(P.up) else if (v < 0) QuantUiKit.color(P.down)
        else QuantUiKit.color(P.subText)

    private fun hexOf(color: Int): String = String.format("#%06X", 0xFFFFFF and color)

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
