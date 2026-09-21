package com.chin.stockanalysis.strategy.trade

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 远程控制 Dialog（C/S 架构 · APK 客户端）
 *
 * 入口：AI 对话框「📡 远程」按钮 / PC 候选旧入口
 * 内容：RemoteControlPanel（连接配置 + 快捷任务 + CodeBuddy 消息 + 任务列表 + 日志）
 * 连接走**联网中继**（CosRelayClient，纯 COS）：无需填 IP / Token，两端联网即可。
 */
class RemoteControlDialog(context: Context) : Dialog(context) {

    private var panel: RemoteControlPanel? = null

    private fun Int.dp() = (this * context.resources.displayMetrics.density + 0.5f).toInt()

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(buildView())
        window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(null)
        }
    }

    override fun dismiss() {
        panel?.shutdown()
        super.dismiss()
    }

    private fun buildView(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        // ── 标题栏（2026-09-20：竖向留白减半 12dp→6dp，标题栏整体高度约为原来一半）──
        // ★ 这里是唯一的「远程控制」标题；RemoteControlPanel 内部不再重复渲染标题。
        root.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp(), 6.dp(), 8.dp(), 6.dp())
            setBackgroundColor(0xFF4527A0.toInt())
            addView(TextView(context).apply {
                text = "🌐 远程控制"
                textSize = 17f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                text = "✕"
                textSize = 18f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(12.dp(), 8.dp(), 12.dp(), 8.dp())
                setOnClickListener { dismiss() }
            })
        })
        panel = RemoteControlPanel(context, compact = false)
        root.addView(panel, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return root
    }
}
