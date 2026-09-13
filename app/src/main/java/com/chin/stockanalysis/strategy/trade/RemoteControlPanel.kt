package com.chin.stockanalysis.strategy.trade

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.chin.stockanalysis.stock.data.PcBridgeClient
import com.chin.stockanalysis.stock.data.RemoteConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 远程控制面板（C/S · APK 客户端 · 可复用 View）
 *
 * 同一套面板可内嵌在两种入口：
 * - RemoteControlDialog（全屏）：AI 对话框「📡 远程」按钮等打开，含任务列表 + 日志区
 * - StrategyImportFragment「🎛 远程」卡片（compact=true）：数据→PC参数 页，含连接信息 + 快捷任务 + CodeBuddy 消息
 *
 * 连接配置走 RemoteConfig（JSON 文件 + 本机 IP 探测 + 旧 SP 迁移）。
 */
class RemoteControlPanel(context: Context, private val compact: Boolean = false) :
    LinearLayout(context) {

    private val density = context.resources.displayMetrics.density
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job? = null
    private var msgPollJob: Job? = null

    private var hostInput: EditText? = null
    private var tokenInput: EditText? = null
    private var statusTv: TextView? = null
    private var taskListBox: LinearLayout? = null
    private var logBox: LinearLayout? = null
    private var logScroll: ScrollView? = null

    // CodeBuddy 消息区
    private var msgInput: EditText? = null
    private var msgBox: LinearLayout? = null
    private var msgScroll: ScrollView? = null
    private var lastReplySeq = 0

    private val cfg = RemoteConfig.load(context)

    private fun Int.dp() = (this * density + 0.5f).toInt()

    init {
        orientation = LinearLayout.VERTICAL
        // buildView() 内部已把子视图 addView 到 this（root），这里不能再 addView 返回值，
        // 否则会把自己添加为自己，形成父子循环引用 → resetResolvedLayoutDirection 无限递归 → StackOverflowError
        buildView()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshTasks()
        startMsgPolling()
    }

    override fun onDetachedFromWindow() {
        shutdown()
        super.onDetachedFromWindow()
    }

    /** 供 Dialog dismiss 时显式回收协程 */
    fun shutdown() {
        pollJob?.cancel()
        msgPollJob?.cancel()
        scope.cancel()
    }

    private fun buildView(): View {
        val root = this

        // ── 连接配置（预置 + 可编辑 + 本机IP） ──
        val cfgBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp(), 8.dp(), 12.dp(), 8.dp())
            setBackgroundColor(0xFFEDE7F6.toInt())
        }
        val localIp = RemoteConfig.getLocalIp(context)
        cfgBox.addView(TextView(context).apply {
            text = "📡 本机IP: ${if (localIp.isBlank()) "未连接WiFi" else localIp}" +
                    "   |   PC地址: ${cfg.port}"
            textSize = 11f
            setTextColor(0xFF7B1FA2.toInt())
        })
        cfgBox.addView(TextView(context).apply {
            text = "📶 同一WiFi填 PC 局域网IP；异地用公网地址(域名/IP:8888)，PC 端需映射端口"
            textSize = 10f
            setTextColor(0xFFB39DDB.toInt())
        })
        hostInput = EditText(context).apply {
            hint = "PC 地址: 192.168.x.x:8888（预置 ${cfg.host.ifBlank { "待填写" }}）"
            textSize = 13f
            setText(cfg.host)
            setSingleLine(true)
        }
        tokenInput = EditText(context).apply {
            hint = "Token (PC端 AutoQuant/data/remote_token.txt)"
            textSize = 13f
            setText(cfg.token)
            setSingleLine(true)
        }
        cfgBox.addView(hostInput, ViewGroup.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        cfgBox.addView(tokenInput, ViewGroup.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        cfgBox.addView(TextView(context).apply {
            text = "🔗 保存并测试"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 10.dp(), 0, 10.dp())
            background = rnd(0xFF00838F.toInt(), 6.dp())
            setOnClickListener { saveAndTest() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 6.dp()
        })
        root.addView(cfgBox)

        // ── 快捷任务按钮 ──
        val quick = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp(), 8.dp(), 12.dp(), 8.dp())
        }
        quick.addView(TextView(context).apply {
            text = "▶ 快捷任务（预设控件，一键与 exe 沟通）"
            textSize = 13f
            setTextColor(0xFF4527A0.toInt())
            setTypeface(null, Typeface.BOLD)
        })
        quick.addView(flowRow(arrayOf(
            "📊 每日选股+发布" to "select.run",
            "📈 滚动回测" to "backtest.walk",
            "⚙ 自动拟合参数" to "fit.auto",
            "🗄 刷新K线缓存" to "data.refresh",
            "☁ 上传到COS" to "cloud.upload",
            "📋 季度复盘" to "backtest.quarter",
        )) { submit(it) })
        root.addView(quick)

        // ── 💬 CodeBuddy 即时通讯 ──
        val msgPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp(), 8.dp(), 12.dp(), 8.dp())
            setBackgroundColor(0xFFE8F5E9.toInt())
        }
        msgPanel.addView(TextView(context).apply {
            text = "💬 发给 CodeBuddy（AI 自动处理并回复）"
            textSize = 13f
            setTextColor(0xFF2E7D32.toInt())
            setTypeface(null, Typeface.BOLD)
        })
        val inputRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        msgInput = EditText(context).apply {
            hint = "例如：帮我跑每日选股并发布 / 分析近期市场状态"
            textSize = 13f
            setSingleLine(false)
            maxLines = 2
        }
        inputRow.addView(msgInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(TextView(context).apply {
            text = "发送"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(14.dp(), 8.dp(), 14.dp(), 8.dp())
            background = rnd(0xFF2E7D32.toInt(), 6.dp())
            setOnClickListener { sendToCodeBuddy() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = 6.dp()
        })
        msgPanel.addView(inputRow, LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 4.dp()
        })
        msgPanel.addView(TextView(context).apply {
            text = "回复区（PC 端 CodeBuddy 的回复会显示在这里）"
            textSize = 11f
            setTextColor(0xFF81C784.toInt())
            setPadding(0, 4.dp(), 0, 2.dp())
        })
        msgScroll = ScrollView(context).apply {
            isFillViewport = true
            setBackgroundColor(Color.WHITE)
        }
        msgBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(6.dp(), 4.dp(), 6.dp(), 4.dp())
        }
        msgScroll?.addView(msgBox, ViewGroup.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        msgPanel.addView(msgScroll, LinearLayout.LayoutParams(MATCH_PARENT, if (compact) 110.dp() else 150.dp()))
        root.addView(msgPanel)

        // ── 状态 ──
        statusTv = TextView(context).apply {
            setPadding(16.dp(), 8.dp(), 16.dp(), 8.dp())
            textSize = 12f
            setTextColor(0xFF546E7A.toInt())
            text = "连接中…"
        }
        root.addView(statusTv)

        if (compact) return root

        // ── 任务列表 ──
        taskListBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dp(), 4.dp(), 10.dp(), 4.dp())
        }
        root.addView(ScrollView(context).apply {
            isFillViewport = true
            addView(taskListBox, ViewGroup.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // ── 日志区 ──
        logScroll = ScrollView(context).apply {
            isFillViewport = true
            setBackgroundColor(0xFF101418.toInt())
        }
        logBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dp(), 8.dp(), 10.dp(), 8.dp())
        }
        logScroll?.addView(logBox, ViewGroup.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(logScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply {
            topMargin = 4.dp()
            bottomMargin = 4.dp()
        })
        return root
    }

    private fun flowRow(pairs: Array<Pair<String, String>>, onClick: (String) -> Unit): LinearLayout {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val half = (pairs.size + 1) / 2
        val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val right = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        pairs.forEachIndexed { i, (label, type) ->
            val btn = TextView(context).apply {
                text = label
                textSize = 12f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, 9.dp(), 0, 9.dp())
                background = rnd(if (type.contains("select")) 0xFF00838F.toInt() else 0xFF4527A0.toInt(), 6.dp())
                setOnClickListener { onClick(type) }
            }
            val box = if (i < half) left else right
            box.addView(btn, LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 3.dp()
                leftMargin = if (i < half) 0 else 3.dp()
                rightMargin = if (i < half) 3.dp() else 0
            })
        }
        row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun rnd(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    // ═══════════════════════════════════════════
    // 操作
    // ═══════════════════════════════════════════

    private fun host(): String {
        val h = hostInput?.text?.toString()?.trim().orEmpty()
        return if (':' in h) h else "$h:${cfg.port}"
    }

    private fun saveAndTest() {
        val h = hostInput?.text?.toString()?.trim().orEmpty()
        val t = tokenInput?.text?.toString()?.trim().orEmpty()
        cfg.host = h
        cfg.token = t
        cfg.localIp = RemoteConfig.getLocalIp(context)
        RemoteConfig.save(context, cfg)
        val hp = host()
        statusTv?.text = "测试连接 $hp …"
        scope.launch {
            try {
                val s = PcBridgeClient.remoteStatus(hp, t)
                val j = JSONObject(s)
                statusTv?.text = "✅ 已连接 ${j.optString("server", "PC")} | token=${j.optString("token", "")}"
                refreshTasks()
                startMsgPolling()
            } catch (e: Exception) {
                statusTv?.text = "❌ 连接失败: ${e.message}"
                Toast.makeText(context, "连接失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ═══════════════════════════════════════════
    // CodeBuddy 消息
    // ═══════════════════════════════════════════

    private fun sendToCodeBuddy() {
        val content = msgInput?.text?.toString()?.trim().orEmpty()
        if (content.isBlank()) {
            Toast.makeText(context, "请输入消息内容", Toast.LENGTH_LONG).show()
            return
        }
        val hp = host()
        val tk = tokenInput?.text?.toString()?.trim().orEmpty()
        if (hp.isBlank() || tk.isBlank()) {
            Toast.makeText(context, "请先填写 PC 地址和 Token", Toast.LENGTH_LONG).show()
            return
        }
        scope.launch {
            try {
                val r = PcBridgeClient.sendMsg(hp, tk, content)
                val j = JSONObject(r)
                if (j.optBoolean("ok", false)) {
                    addMsgRow("我: $content", fromApk = true)
                    msgInput?.setText("")
                    Toast.makeText(context, "已发送给 CodeBuddy", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "发送失败: ${j.optString("error", "未知错误")}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "发送失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 轮询 CodeBuddy 回复（outbox） */
    private fun startMsgPolling() {
        msgPollJob?.cancel()
        val hp = host()
        val tk = tokenInput?.text?.toString()?.trim().orEmpty()
        if (hp.isBlank() || tk.isBlank()) return
        msgPollJob = scope.launch {
            while (isActive) {
                try {
                    val r = PcBridgeClient.fetchMsgReplies(hp, tk, lastReplySeq)
                    val j = JSONObject(r)
                    val msgs = j.optJSONArray("messages") ?: JSONArray()
                    for (i in 0 until msgs.length()) {
                        val m = msgs.getJSONObject(i)
                        addMsgRow("CodeBuddy: ${m.optString("content")}", fromApk = false)
                    }
                    lastReplySeq = j.optInt("after", lastReplySeq)
                } catch (_: Exception) {
                    // 未连接或网络不可达，静默继续
                }
                delay(3000)
            }
        }
    }

    private fun addMsgRow(text: String, fromApk: Boolean) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (fromApk) Gravity.END else Gravity.START
        }
        row.addView(TextView(context).apply {
            this.text = text
            textSize = 12f
            setTextColor(if (fromApk) Color.WHITE else 0xFF263238.toInt())
            setPadding(10.dp(), 6.dp(), 10.dp(), 6.dp())
            background = rnd(if (fromApk) 0xFF2E7D32.toInt() else 0xFFDCEDC8.toInt(), 8.dp())
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = if (fromApk) 0 else 40.dp()
            leftMargin = if (fromApk) 40.dp() else 0
        })
        msgBox?.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 3.dp()
        })
        msgScroll?.post { msgScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun submit(taskType: String) {
        val hp = host()
        val tk = tokenInput?.text?.toString()?.trim().orEmpty()
        if (hp.isBlank() || tk.isBlank()) {
            Toast.makeText(context, "请先填写 PC 地址和 Token", Toast.LENGTH_LONG).show()
            return
        }
        statusTv?.text = "提交任务 $taskType …"
        scope.launch {
            try {
                val r = PcBridgeClient.submitTask(hp, tk, taskType)
                val j = JSONObject(r)
                if (j.optBoolean("ok", false)) {
                    Toast.makeText(context, "任务已提交: ${j.optString("task_id")}", Toast.LENGTH_SHORT).show()
                    startPolling(j.optString("task_id"))
                    refreshTasks()
                } else {
                    statusTv?.text = "❌ ${j.optString("error", "提交失败")}"
                }
            } catch (e: Exception) {
                statusTv?.text = "❌ 提交失败: ${e.message}"
            }
        }
    }

    private fun refreshTasks() {
        if (compact) return
        val hp = host()
        if (hp.isBlank()) {
            statusTv?.text = "未配置 PC 地址"
            return
        }
        val tk = tokenInput?.text?.toString()?.trim().orEmpty()
        scope.launch {
            try {
                val r = PcBridgeClient.listTasks(hp, tk)
                val tasks = JSONObject(r).optJSONArray("tasks") ?: JSONArray()
                taskListBox?.removeAllViews()
                for (i in 0 until tasks.length()) {
                    taskListBox?.addView(taskCard(tasks.getJSONObject(i)))
                }
                statusTv?.text = "共 ${tasks.length()} 个任务（最近） | PC: $hp"
            } catch (e: Exception) {
                statusTv?.text = "⚠ 获取任务列表失败: ${e.message}"
            }
        }
    }

    private fun taskCard(t: JSONObject): View {
        val id = t.optString("id")
        val state = t.optString("state")
        val color = when (state) {
            "success" -> 0xFF2E7D32.toInt()
            "failed" -> 0xFFC62828.toInt()
            "running" -> 0xFFEF6C00.toInt()
            "queued" -> 0xFF1565C0.toInt()
            else -> 0xFF757575.toInt()
        }
        val name = t.optString("name")
        val type = t.optString("type")
        val exit = t.optInt("exit_code", 0)
        val tail = t.optJSONArray("log_tail")
        val tailText = (0 until (tail?.length() ?: 0)).joinToString("\n") { tail!!.getString(it) }.takeLast(400)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dp(), 8.dp(), 10.dp(), 8.dp())
            setBackgroundColor(0xFFF5F5F5.toInt())
            setOnClickListener { startPolling(id); showLogs(id) }
        }
        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(context).apply {
            text = "$name  [$state]"
            textSize = 13f
            setTextColor(color)
            setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(TextView(context).apply {
            text = if (state == "success") "退出$exit" else if (state == "failed") "失败" else t.optString("created_at", "").takeLast(8)
            textSize = 11f
            setTextColor(0xFF90A4AE.toInt())
        })
        card.addView(head)
        card.addView(TextView(context).apply {
            text = "$type | ${t.optString("finished_at", "运行中").takeLast(8)}"
            textSize = 10f
            setTextColor(0xFF90A4AE.toInt())
        })
        if (tailText.isNotBlank()) {
            card.addView(TextView(context).apply {
                text = tailText.take(200)
                textSize = 10f
                maxLines = 3
                setTextColor(0xFF546E7A.toInt())
            })
        }
        val ops = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        if (state == "running" || state == "queued") {
            ops.addView(TextView(context).apply {
                text = "⏹ 取消"
                textSize = 12f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(10.dp(), 4.dp(), 10.dp(), 4.dp())
                background = rnd(0xFFC62828.toInt(), 4.dp())
                setOnClickListener {
                    scope.launch {
                        PcBridgeClient.cancelTask(host(), tokenInput?.text?.toString()?.trim().orEmpty(), id)
                        refreshTasks()
                    }
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = 6.dp()
            })
        }
        ops.addView(TextView(context).apply {
            text = "📜 日志"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(10.dp(), 4.dp(), 10.dp(), 4.dp())
            background = rnd(0xFF1565C0.toInt(), 4.dp())
            setOnClickListener { showLogs(id); startPolling(id) }
        })
        card.addView(ops, LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 4.dp()
        })
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 2.dp(), 0, 2.dp())
            addView(card)
        }
    }

    private fun showLogs(taskId: String) {
        scope.launch {
            try {
                val r = PcBridgeClient.taskLogs(host(), tokenInput?.text?.toString()?.trim().orEmpty(), taskId, 0)
                val j = JSONObject(r)
                val logs = j.optJSONArray("logs") ?: JSONArray()
                logBox?.removeAllViews()
                for (i in 0 until logs.length()) {
                    val item = logs.getJSONObject(i)
                    logBox?.addView(TextView(context).apply {
                        text = "${item.optString("ts")}  ${item.optString("line")}"
                        textSize = 11f
                        setTextColor(0xFFE0E0E0.toInt())
                    })
                }
                logScroll?.post { logScroll?.fullScroll(View.FOCUS_DOWN) }
            } catch (e: Exception) {
                statusTv?.text = "❌ 日志获取失败: ${e.message}"
            }
        }
    }

    /** 轮询任务状态与日志直到结束 */
    private fun startPolling(taskId: String) {
        if (compact) return
        pollJob?.cancel()
        pollJob = scope.launch {
            var cursor = 0
            while (isActive) {
                try {
                    val r = PcBridgeClient.taskLogs(host(), tokenInput?.text?.toString()?.trim().orEmpty(), taskId, cursor)
                    val j = JSONObject(r)
                    val logs = j.optJSONArray("logs") ?: JSONArray()
                    for (i in 0 until logs.length()) {
                        val item = logs.getJSONObject(i)
                        logBox?.addView(TextView(context).apply {
                            text = "${item.optString("ts")}  ${item.optString("line")}"
                            textSize = 11f
                            setTextColor(0xFFE0E0E0.toInt())
                        })
                    }
                    cursor = j.optInt("cursor", cursor)
                    logScroll?.post { logScroll?.fullScroll(View.FOCUS_DOWN) }
                    val state = j.optString("state")
                    if (state == "success" || state == "failed" || state == "cancelled") {
                        statusTv?.text = "任务 $taskId 结束: $state"
                        refreshTasks()
                        break
                    }
                } catch (_: Exception) {
                }
                delay(2000)
            }
        }
    }
}
