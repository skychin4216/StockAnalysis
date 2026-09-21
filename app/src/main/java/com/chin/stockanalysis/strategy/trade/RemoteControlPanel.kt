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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.chin.stockanalysis.stock.data.CosRelayClient
import com.chin.stockanalysis.stock.data.PcBridgeClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
 * - RemoteControlDialog（全屏）：AI 对话框「📡 远程」按钮等打开，含任务列表
 * - StrategyImportFragment「🎛 远程」卡片（compact=true）：数据→PC参数 页
 *
 * ## 布局（2026-09-22 用户指定）
 * 1. `🌐 远程控制` 标题 + 中继状态（`设备ID · 同步时间 MM-dd HH:mm:ss`）+ 测试中继连接
 * 2. 快捷任务（预设控件，一键与 PC 端 exe 沟通）
 * 3. **五个 Tab**（各一套独立滚动容器，内容完全隔离）：`对话 股票 任务 日志 全部`，
 *    行尾是 `🗑 清空`（二次点击确认；与 Tab 同基线对齐）
 * 4. 输入框 + 发送（置于会话区**下方**）
 *
 * ## Tab 职责（各司其职）
 * - **对话** = 结论：`我 ↔ CodeBuddy` 的双向问答，一行摘要
 * - **股票** = ★ 选到的股票：PC 推送的选股结果，本地持久化（2026-09-22 新增）
 * - **任务** = 原底部任务区块（状态 + 取消）
 * - **日志** = 过程：仅 PC 端 exe 任务输出（`🖥`）
 * - **全部** = 完整时间线：对话 + 日志 + 选股按发生顺序穿插
 *
 * ## 消息来源（刻意区分）
 * - `我:` —— 本机发出（右对齐绿气泡）
 * - `CodeBuddy:` —— PC 端 AI 回复（左对齐浅绿气泡）
 * - `🖥` —— PC 端 exe 任务日志（左对齐深色条，带时间戳）
 * - `🎯` —— 选股结果（左对齐蓝字浅蓝底；完整卡片在「股票」Tab）
 *
 * ## PC → APK 推送消费
 * `startMsgPolling()` 每 3 秒拉一次 `PcBridgeClient.fetchPushes()`（`pc/push/{deviceId}/`），
 * 按 `kind` 分流：`candidates/stock/picks` → 「股票」Tab；其余 → 「对话」一行摘要。
 * `fetchPushes` 自带 seq 游标，已消费的不会重复回来。
 *
 * 连接走**联网中继**（`CosRelayClient`，纯 COS）：无需填 IP / Token，两端联网即可。
 */
class RemoteControlPanel(context: Context, private val compact: Boolean = false) :
    LinearLayout(context) {

    private val density = context.resources.displayMetrics.density
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job? = null
    private var msgPollJob: Job? = null

    private var statusTv: TextView? = null
    private var taskListBox: LinearLayout? = null
    private var syncTv: TextView? = null

    // 会话区（对话 / 日志 / 全部 / 任务 —— 四个 Tab 各自一套独立滚动容器）
    private var msgInput: EditText? = null
    private var lastReplySeq = prefs().getInt(PREF_SEQ, 0)

    /** Tab 类别 -> 该 Tab 的滚动容器 / 内容盒子。四者互不干扰，各自保留滚动位置。 */
    private val chatScrolls = mutableMapOf<String, ScrollView>()
    private val chatBoxes = mutableMapOf<String, LinearLayout>()
    private val filterTabs = mutableMapOf<String, TextView>()
    /** Tab 上的计数（对话=N / 日志=N / 全部=N / 任务=N） */
    private val tabLabels = mutableMapOf<String, String>()
    private val tabCounts = mutableMapOf<String, Int>()
    private var chatFilter = CAT_CHAT

    /** 回放历史时置位：此期间的 addMsgRow 不再重复落盘（否则会自我叠加） */
    private var restoring = false

    private companion object {
        const val CAT_CHAT = "chat"   // 对话（我 / CodeBuddy）
        const val CAT_LOG = "log"     // PC 端 exe 任务日志
        const val CAT_ALL = "all"     // 全部（对话+日志的完整时间线）
        const val CAT_TASK = "task"   // 任务列表（原底部区块，已收编为 Tab）
        const val CAT_STOCK = "stock" // ★ 选到的股票（2026-09-22 新增，持久化保存选股结果）

        /** PC 端日志行的来源标签（exe 侧输出；CodeBuddy 的回复走 CAT_CHAT）。 */
        const val PC_LOG_PREFIX = "🖥"

        /** 「股票」Tab 的来源标签；PC 推送的选股结果用它标记。 */
        const val STOCK_PREFIX = "🎯"

        /**
         * PC → APK 推送里代表「选股结果」的 kind 取值。
         * `relay_push.py` 定义的语义：notice / candidates / intel / signal；
         * 选股命中 `candidates`，另兼容 `stock`、`picks` 以防 PC 侧后续改名。
         */
        val STOCK_KINDS = setOf("candidates", "stock", "picks", "pick")

        /** 「对话」Tab 一行结论的最大字数；超出则折叠，完整内容转入「日志」。 */
        const val CHAT_SUMMARY_MAX = 60

        // ── 聊天记录持久化（跨 Dialog 开关 / 跨进程重启保留）──
        const val PREF_FILE = "remote_control"
        const val PREF_HISTORY = "history"
        const val PREF_SEQ = "reply_seq"
        // 注：PC 推送的已读游标由 CosRelayClient 内部维护（KEY_PUSH_SEQ），
        //     本面板不另存，避免两处游标不一致导致漏消费或重复消费。
        const val PREF_STOCKS = "stocks"        // ★ 选股结果单独持久化（不与会话共用上限）
        const val HISTORY_MAX = 300      // 超过则丢弃最旧的，避免无限膨胀
        const val STOCK_MAX = 500        // 「股票」Tab 最多保留的条数

        /** Tab 展示顺序（2026-09-22 用户指定）：对话 → 股票 → 任务 → 日志 → 全部 */
        val TAB_ORDER = listOf(CAT_CHAT, CAT_STOCK, CAT_TASK, CAT_LOG, CAT_ALL)
    }

    private fun prefs() =
        context.applicationContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    private fun Int.dp() = (this * density + 0.5f).toInt()

    init {
        orientation = LinearLayout.VERTICAL
        // 联网中继需要 Application Context（PC 地址/token 均已废弃，无需用户填写任何地址）
        PcBridgeClient.init(context)
        // buildView() 内部已把子视图 addView 到 this（root），这里不能再 addView 返回值，
        // 否则会把自己添加为自己，形成父子循环引用 → resetResolvedLayoutDirection 无限递归 → StackOverflowError
        buildView()
        restoreHistory()   // 恢复上一次的聊天/日志记录（2026-09-21：不要每次打开都从零开始）
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

        // ── 中继单行头部：左侧「测试中继连接」按钮 + **右侧**并入的设备/同步信息 ──
        //
        // ★ 面板本身**不再**无条件渲染「🌐 远程控制」标题 —— RemoteControlDialog 顶部
        //   已有标题栏，两处都画会重复；只有 compact（StrategyImportFragment 卡片）
        //   没有外层标题时才补一个。
        val cfgBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp(), 6.dp(), 12.dp(), 6.dp())
            setBackgroundColor(0xFFEDE7F6.toInt())
        }
        if (compact) {
            cfgBox.addView(TextView(context).apply {
                text = "🌐 远程控制"
                textSize = 15f
                setTextColor(0xFF4527A0.toInt())
                setTypeface(null, Typeface.BOLD)
            })
        }
        val headRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headRow.addView(TextView(context).apply {
            text = "🔗 测试中继连接"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(10.dp(), 6.dp(), 10.dp(), 6.dp())
            background = rnd(0xFF00838F.toInt(), 6.dp())
            setOnClickListener { saveAndTest() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // 设备 ID + **具体同步时间点**：CosRelayClient.statusText() 只给相对时间
        // （「X 分钟前」），这里改成绝对时间；已并入按钮右侧，不再单独占一行。
        syncTv = TextView(context).apply {
            text = syncStatusText()
            textSize = 10f
            setTextColor(0xFF7B1FA2.toInt())
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            maxLines = 2
        }
        headRow.addView(syncTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = 8.dp()
        })
        cfgBox.addView(headRow)
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

        // ── 💬 会话区（微信/QQ 气泡式：对话记录 + PC 端 exe 日志；输入框在下方） ──
        val msgPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8.dp(), 6.dp(), 8.dp(), 6.dp())
            setBackgroundColor(0xFFF2F3F5.toInt())
        }
        // ── 五个 Tab：各司其职、内容**完全隔离**（不再是一个列表做可见性过滤） ──
        //   对话 = 仅「我 ↔ CodeBuddy」的双向问答（结论，一行摘要）
        //   股票 = ★ 选到的股票（PC 推送的选股结果，本地持久化，2026-09-22 新增）
        //   任务 = 原底部任务区块（状态行 + 任务卡）
        //   日志 = 仅 PC 端 exe 任务输出（🖥），不含聊天
        //   全部 = 完整时间线：对话 + 日志按发生顺序穿插，排查问题看这里
        val tabs = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf(
            "对话" to CAT_CHAT,
            "股票" to CAT_STOCK,
            "任务" to CAT_TASK,
            "日志" to CAT_LOG,
            "全部" to CAT_ALL,
        ).forEach { (label, cat) ->
            tabs.addView(TextView(context).apply {
                text = "$label 0"
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(14.dp(), 4.dp(), 14.dp(), 4.dp())
                background = rnd(0xFFDDDDDD.toInt(), 12.dp())
                tabLabels[cat] = label
                tabCounts[cat] = 0
                filterTabs[cat] = this
                setOnClickListener { setChatFilter(cat) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = 6.dp()
                bottomMargin = 4.dp()
            })
        }
        // ── 🗑 清空：放在 Tab 行末尾（仅清空会话历史，不影响「任务」列表）──
        tabs.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))   // 柔性间距，把它推到最右
        var clearArmed = false
        tabs.addView(TextView(context).apply {
            text = "🗑 清空"
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(10.dp(), 4.dp(), 10.dp(), 4.dp())
            setTextColor(0xFFC62828.toInt())
            background = rnd(0xFFFFEBEE.toInt(), 12.dp())
            setOnClickListener {
                // 防误触：第一次点击进入「待确认」状态，3 秒内再点一次才真正清空
                if (!clearArmed) {
                    clearArmed = true
                    text = "🗑 再点确认"
                    postDelayed({ clearArmed = false; text = "🗑 清空" }, 3000)
                    return@setOnClickListener
                }
                clearArmed = false
                text = "🗑 清空"
                clearHistory()
                Toast.makeText(context, "已清空（任务列表保留）", Toast.LENGTH_SHORT).show()
            }
            // ★ 2026-09-22 修正「清空偏下」：Tab 行是 CENTER_VERTICAL 居中，而各 Tab
            //   都带 bottomMargin=4dp，唯独清空没有 → 它的盒子比 Tab 矮 4dp，居中时
            //   整体下移 2dp，肉眼看着就比 Tab 低一截。补上同样的 bottomMargin 即对齐。
            includeFontPadding = false          // 去除字体上下留白，基线再准一点
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = 4.dp()
            gravity = Gravity.CENTER_VERTICAL
        })
        msgPanel.addView(tabs)

        // 每个 Tab 一套**独立的** ScrollView + 内容盒：各自累积、各自保留滚动位置，
        // 同一时刻只显示当前 Tab 的那一个 —— 内容真正隔离，不会互相挤到一起。
        val bodyHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        TAB_ORDER.forEach { cat ->
            val sv = ScrollView(context).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = true
                setBackgroundColor(Color.WHITE)
            }
            val box = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(6.dp(), 6.dp(), 6.dp(), 6.dp())
            }
            // 「任务」Tab 的内容 = 原底部区块（状态行 + 任务卡片）
            if (cat == CAT_TASK) {
                statusTv = TextView(context).apply {
                    setPadding(4.dp(), 4.dp(), 4.dp(), 4.dp())
                    textSize = 12f
                    setTextColor(0xFF546E7A.toInt())
                    text = "连接中…"
                }
                box.addView(statusTv)
                taskListBox = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(6.dp(), 4.dp(), 6.dp(), 4.dp())
                }
                box.addView(taskListBox, LinearLayout.LayoutParams(
                    MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            sv.addView(box, ViewGroup.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            chatScrolls[cat] = sv
            chatBoxes[cat] = box
            bodyHost.addView(sv, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        msgPanel.addView(
            bodyHost,
            LinearLayout.LayoutParams(MATCH_PARENT, if (compact) 160.dp() else 0, 1f)
        )
        renderTabs()

        // 输入行：放在会话区**下方**，与微信/QQ 一致
        val inputRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        msgInput = EditText(context).apply {
            hint = "输入指令…（发送后由 PC 端 CodeBuddy 处理并回复）"
            textSize = 13f
            setSingleLine(false)
            maxLines = 3
        }
        inputRow.addView(msgInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(TextView(context).apply {
            text = "发送"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(16.dp(), 8.dp(), 16.dp(), 8.dp())
            background = rnd(0xFF2E7D32.toInt(), 6.dp())
            setOnClickListener { sendToCodeBuddy() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = 6.dp()
        })
        msgPanel.addView(inputRow, LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 6.dp()
        })
        root.addView(msgPanel, LinearLayout.LayoutParams(MATCH_PARENT, 0, if (compact) 1f else 2f))

        // 注1：原先这里还有一个独立的深色「日志区」—— 已并入会话区，成为「日志」Tab。
        // 注2：原先底部还有「状态行 + 任务列表」两个 view —— 2026-09-21 已收编为
        //      「任务」Tab（见上方 TAB_ORDER），底部不再单独占位。
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

    /** 中继模式已无「地址」概念；保留空串仅为兼容旧调用点。 */
    private fun host(): String = ""

    /** 测试中继连通性（无需任何地址 / token 输入）。 */
    private fun saveAndTest() {
        statusTv?.text = "测试中继连接…"
        scope.launch {
            try {
                val j = PcBridgeClient.ping()
                statusTv?.text = "✅ 中继已连通 ${j.optString("server", "PC")}"
                syncTv?.text = syncStatusText()      // 刷新「设备 · 同步时间」
                refreshTasks()
                startMsgPolling()
            } catch (e: Exception) {
                statusTv?.text = "❌ 中继连接失败: ${e.message}"
                Toast.makeText(context, "中继连接失败: ${e.message}", Toast.LENGTH_LONG).show()
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
        // ★ 乐观 UI（2026-09-21）：**先上屏再发送**，不必等中继往返几十秒。
        //   气泡下方实时显示状态：发送中… → ✅ 已送达 / ❌ 发送失败（QQ 风格）
        val refs = addMsgRow(content, prefix = "我:", category = CAT_CHAT,
            fromApk = true, status = "发送中…")
        msgInput?.setText("")
        scope.launch {
            try {
                val r = PcBridgeClient.sendMsg(content = content)
                val j = JSONObject(r)
                if (j.optBoolean("ok", false)) {
                    // 已送达后淡出（4 秒），不长期占用视觉
                    setStatus(refs, "✅ 已送达", 0xFF7CB342.toInt(), clearAfterMs = 4000)
                    Toast.makeText(context, "已发送给 CodeBuddy", Toast.LENGTH_SHORT).show()
                } else {
                    val err = j.optString("error", "未知错误")
                    setStatus(refs, "❌ 失败：$err", 0xFFC62828.toInt())
                    Toast.makeText(context, "发送失败: $err", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                setStatus(refs, "❌ 失败：${e.message}", 0xFFC62828.toInt())
                Toast.makeText(context, "发送失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 轮询 CodeBuddy 回复（outbox） */
    private fun startMsgPolling() {
        msgPollJob?.cancel()
        msgPollJob = scope.launch {
            while (isActive) {
                try {
                    val r = PcBridgeClient.fetchMsgReplies(after = lastReplySeq)
                    val j = JSONObject(r)
                    val msgs = j.optJSONArray("messages") ?: JSONArray()
                    for (i in 0 until msgs.length()) {
                        val m = msgs.getJSONObject(i)
                        // content = 结论 → 对话（一行摘要）；detail = 过程 → 日志（逐行）
                        addMsgRow(m.optString("content"), prefix = "CodeBuddy:",
                            category = CAT_CHAT, fromApk = false)
                        val detail = m.optString("detail")
                        if (detail.isNotBlank()) {
                            detail.split("\n").filter { it.isNotBlank() }.forEach { line ->
                                addMsgRow(line, prefix = "🖥 明细:", category = CAT_LOG)
                            }
                        }
                    }
                    lastReplySeq = j.optInt("after", lastReplySeq)
                    // 持久化已读游标 → 重开面板不会把历史回复再灌一遍
                    prefs().edit().putInt(PREF_SEQ, lastReplySeq).apply()

                    // ★ PC 主动推送（选股结果 / 情报 / 信号）。
                    //   fetchPushes 内部自带 seq 游标（CosRelayClient.KEY_PUSH_SEQ），
                    //   已消费的不会重复回来，重启也不会漏。
                    try {
                        for (p in PcBridgeClient.fetchPushes(limit = 30)) consumePush(p)
                    } catch (_: Exception) {
                        // 中继不可用/未登记设备 → 静默
                    }
                } catch (_: Exception) {
                    // 未连接或网络不可达，静默继续
                }
                delay(3000)
            }
        }
    }

    /**
     * 消费一条 PC 主动推送（`pc/push/{deviceId}/`）。
     *
     * 信封结构（`cos_relay.make_envelope`，method="push"）：
     * `{kind, params:{title, content, payload}, seq, ts}`。
     *
     * **分流原则 —— 「股票」Tab 只放选股结果**：
     * - kind ∈ STOCK_KINDS（candidates / stock / picks）→ 逐条进「股票」Tab
     * - 其余（notice / intel / signal…）→ 进「对话」一行摘要
     * 否则情报、信号会和系统通知一起把选出来的票淹没。
     */
    private fun consumePush(p: JSONObject) {
        val kind = p.optString("kind")
        val params = p.optJSONObject("params") ?: return
        val title = params.optString("title")
        val content = params.optString("content")
        val payload = params.optJSONObject("payload")
        val stamp = p.optLong("ts", System.currentTimeMillis() / 1000) * 1000
        val ts = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(stamp))

        if (kind !in STOCK_KINDS) {
            val one = listOf(title, content).filter { it.isNotBlank() }.joinToString(" — ")
            if (one.isNotBlank()) {
                addMsgRow(one, prefix = "🔔", category = CAT_CHAT, fromApk = false)
            }
            return
        }

        // ① 优先用结构化清单（payload.list / codes / items）
        val list = payload?.optJSONArray("list")
            ?: payload?.optJSONArray("codes")
            ?: payload?.optJSONArray("items")
        if (list != null && list.length() > 0) {
            for (i in 0 until list.length()) {
                val it = list.get(i)
                if (it is JSONObject) {
                    addStockPick(
                        code = it.optString("code").ifBlank { it.optString("symbol") },
                        name = it.optString("name"),
                        price = it.optString("price"),
                        pct = it.optString("pct").ifBlank { it.optString("change") },
                        reason = it.optString("reason").ifBlank { it.optString("why") },
                        ts = ts
                    )
                } else {
                    addStockPick(code = it.toString(), ts = ts)
                }
            }
            if (title.isNotBlank()) {
                addMsgRow("$title（${list.length()} 只）", prefix = STOCK_PREFIX,
                    category = CAT_CHAT, fromApk = false)
            }
            return
        }

        // ② 没有结构化清单 → 正文按行退化解析，至少不让选股结果丢掉
        val lines = content.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return
        for (line in lines) addStockPick(code = line, ts = ts)
        if (title.isNotBlank()) {
            addMsgRow("$title（${lines.size} 只）", prefix = STOCK_PREFIX,
                category = CAT_CHAT, fromApk = false)
        }
    }

    /**
     * 追加一条消息。
     *
     * ### 「结论 vs 过程」分工（2026-09-21 与用户确认的口径）
     * - **对话 Tab** = 结论：只放**一行摘要**（换行折叠、超长截断并标注「详见日志」）
     * - **日志 Tab** = 过程：完整原文 / 分步明细 / 任务流水
     * - **全部 Tab** = 完整时间线：对话 + 日志按发生顺序穿插，**不截断**
     *
     * 因此同一条内容在不同 Tab 里的详细程度是刻意不同的：
     * 对话里能一眼看清「发生了什么」，日志里才看「怎么发生的」。
     *
     * @param category CAT_CHAT(对话/结论) / CAT_LOG(PC 端 exe 日志)
     * @param fromApk  true=我方（右对齐绿气泡）；false=对端（左对齐）
     * @param prefix   来源标签，如 `我:` / `CodeBuddy:` / `🖥`
     */
    private fun addMsgRow(
        text: String,
        prefix: String = "",
        category: String = CAT_CHAT,
        fromApk: Boolean = false,
        persist: Boolean = true,
        card: Boolean = false,
        ts: String = "",
        status: String? = null
    ): MutableList<TextView> {
        val stamp = ts.ifBlank { nowTs() }
        val refs = mutableListOf<TextView>()
        fun add(containerCat: String, t: String, p: String, styleCat: String) {
            addTo(containerCat, t, p, styleCat, fromApk, stamp, status)?.let { refs.add(it) }
        }
        if (category == CAT_LOG) {
            add(CAT_ALL, text, prefix, CAT_LOG)                 // 时间线：完整
            add(CAT_LOG, text, prefix, CAT_LOG)                 // 日志：完整过程
            bump(CAT_ALL); bump(CAT_LOG)
        } else if (card) {
            // 结构化卡片（如选股清单）：多行直接进「对话」，**不折叠**、不转日志
            add(CAT_ALL, text, prefix, CAT_CHAT)
            add(CAT_CHAT, text, prefix, CAT_CHAT)
            bump(CAT_ALL); bump(CAT_CHAT)
            if (persist && !restoring) appendHistory(text, prefix, category, fromApk, card = true, ts = stamp)
            renderTabs(); autoScroll(); return refs
        } else {
            val full = if (prefix.isBlank()) text else "$prefix $text"
            val summary = summarize(text, prefix)
            val folded = summary != full                        // 被折叠 ⇒ 详情转入日志
            add(CAT_ALL, text, prefix, CAT_CHAT)                // 时间线：完整不截断
            add(CAT_CHAT, summary, "", CAT_CHAT)                // 对话：一行结论
            bump(CAT_ALL); bump(CAT_CHAT)
            if (folded) {
                add(CAT_LOG, text, prefix, CAT_CHAT)            // 日志：完整原文
                bump(CAT_LOG)
            }
        }
        if (persist && !restoring) appendHistory(text, prefix, category, fromApk, ts = stamp)
        renderTabs()
        autoScroll()
        return refs
    }

    // ── 聊天记录持久化（2026-09-21：重开面板不再从零开始）──

    private fun loadHistory(): JSONArray {
        val s = prefs().getString(PREF_HISTORY, null) ?: return JSONArray()
        return try {
            JSONArray(s)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun appendHistory(text: String, prefix: String, category: String,
                              fromApk: Boolean, card: Boolean = false, ts: String = "") {
        try {
            var arr = loadHistory()
            arr.put(JSONObject().apply {
                put("t", text); put("p", prefix)
                put("c", category); put("me", fromApk)
                if (card) put("card", true)
                put("ts", ts.ifBlank { nowTs() })
            })
            if (arr.length() > HISTORY_MAX) {          // 超出上限 → 丢弃最旧的
                val trimmed = JSONArray()
                for (i in arr.length() - HISTORY_MAX until arr.length()) trimmed.put(arr.get(i))
                arr = trimmed
            }
            prefs().edit().putString(PREF_HISTORY, arr.toString()).apply()
        } catch (_: Exception) {
            // 持久化失败不应影响 UI 正常使用
        }
    }

    /** panel 创建时回放历史记录（回放期间 `restoring=true`，不再重复落盘）。 */
    private fun restoreHistory() {
        restoring = true
        try {
            val arr = loadHistory()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                addMsgRow(o.optString("t"), o.optString("p"),
                    o.optString("c", CAT_CHAT), o.optBoolean("me", false),
                    persist = false, card = o.optBoolean("card", false),
                    ts = o.optString("ts"))
            }
        } catch (_: Exception) {
        } finally {
            restoring = false
        }
        restoreStocks()     // ★ 选股结果是独立持久化的，单独回放
        renderTabs()
    }

    /** 清空历史（同时重置 Tab 计数与容器；**「任务」Tab 不受影响**）。 */
    fun clearHistory() {
        prefs().edit()
            .remove(PREF_HISTORY)
            .remove(PREF_STOCKS)
            .putInt(PREF_SEQ, 0)
            .apply()
        lastReplySeq = 0
        chatBoxes.forEach { (cat, box) -> if (cat != CAT_TASK) box.removeAllViews() }
        TAB_ORDER.forEach { cat -> if (cat != CAT_TASK) tabCounts[cat] = 0 }
        renderTabs()
    }

    // ── ★「股票」Tab：选中的股票（2026-09-22 新增）──
    //
    // 数据来源：PC 端推送到微信群的同时，经中继写 `pc/push/{deviceId}/`
    // （`relay_push.py`，kind="candidates"）。面板轮询拉取，**只挑选股结果**
    // 进这个 Tab —— 情报/信号等其他通知不进来，免得把选股淹没。
    // 本地用 SharedPreferences 持久化，关掉面板/重启进程都还在。

    /** 追加一条选股结果：完整卡片进「股票」Tab，一行摘要进「全部」。 */
    private fun addStockPick(
        code: String, name: String = "", price: String = "", pct: String = "",
        reason: String = "", ts: String = "", persist: Boolean = true
    ) {
        val t = ts.ifBlank { nowTs() }
        chatBoxes[CAT_STOCK]?.addView(
            buildStockCard(code, name, price, pct, reason, t),
            LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = 4.dp() })
        val one = listOf(code, name, price, pct).filter { it.isNotBlank() }.joinToString(" ")
        addTo(CAT_ALL, one, STOCK_PREFIX, CAT_STOCK, false, t, null)
        bump(CAT_STOCK)
        bump(CAT_ALL)
        if (persist) appendStock(code, name, price, pct, reason, t)
        renderTabs()
        autoScroll()
    }

    /** 选股卡片：代码·名称 / 价格·涨幅 / 理由 / 时间。 */
    private fun buildStockCard(
        code: String, name: String, price: String, pct: String, reason: String, ts: String
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(10.dp(), 8.dp(), 10.dp(), 8.dp())
        background = rnd(0xFFE3F2FD.toInt(), 8.dp())
        addView(TextView(context).apply {
            text = listOf(code, name).filter { it.isNotBlank() }.joinToString(" ")
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF0D47A1.toInt())
            enableCopy()                        // ★ 代码/名称可复制
        })
        val mid = listOf(price, pct).filter { it.isNotBlank() }.joinToString("   ")
        if (mid.isNotBlank()) addView(TextView(context).apply {
            text = mid
            textSize = 12f
            setTextColor(0xFF37474F.toInt())
            setPadding(0, 2.dp(), 0, 0)
            enableCopy()
        })
        if (reason.isNotBlank()) addView(TextView(context).apply {
            text = reason
            textSize = 11f
            setTextColor(0xFF607D8B.toInt())
            setPadding(0, 2.dp(), 0, 0)
            setSingleLine(false)
            setHorizontallyScrolling(true)      // 长理由不撑破布局，可横向拖
            enableCopy()
        })
        addView(TextView(context).apply {
            text = ts
            textSize = 10f
            setTextColor(0xFF90A4AE.toInt())
            setPadding(0, 3.dp(), 0, 0)
            enableCopy()
        })
    }

    private fun appendStock(code: String, name: String, price: String, pct: String,
                            reason: String, ts: String) {
        try {
            val arr = loadStocks()
            arr.put(JSONObject().apply {
                put("code", code); put("name", name); put("price", price)
                put("pct", pct); put("reason", reason); put("ts", ts)
            })
            val trimmed = JSONArray()
            val from = if (arr.length() > STOCK_MAX) arr.length() - STOCK_MAX else 0
            for (i in from until arr.length()) trimmed.put(arr.get(i))
            prefs().edit().putString(PREF_STOCKS, trimmed.toString()).apply()
        } catch (_: Exception) {
            // 持久化失败不影响 UI
        }
    }

    private fun loadStocks(): JSONArray {
        val s = prefs().getString(PREF_STOCKS, null) ?: return JSONArray()
        return try { JSONArray(s) } catch (_: Exception) { JSONArray() }
    }

    /** 回放已保存的选股结果（panel 创建时调用）。 */
    private fun restoreStocks() {
        try {
            val arr = loadStocks()
            if (arr.length() == 0) {
                chatBoxes[CAT_STOCK]?.addView(TextView(context).apply {
                    text = "暂无选股记录。\nPC 端推送选股结果（kind=candidates）后会自动出现在这里，并保留在本地。"
                    textSize = 11f
                    setTextColor(0xFF90A4AE.toInt())
                    setPadding(8.dp(), 14.dp(), 8.dp(), 14.dp())
                })
                return
            }
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                addStockPick(o.optString("code"), o.optString("name"),
                    o.optString("price"), o.optString("pct"),
                    o.optString("reason"), o.optString("ts"), persist = false)
            }
        } catch (_: Exception) {
        }
    }

    /** 往指定 Tab 容器追加一行（View 只能有一个父容器 → 每个 Tab 各建一份实例）。 */
    private fun addTo(containerCat: String, text: String, prefix: String,
                      styleCat: String, fromApk: Boolean,
                      ts: String, status: String?): TextView? {
        val ref = buildRow(text, prefix, styleCat, fromApk, ts, status)
        chatBoxes[containerCat]?.addView(
            ref.root, LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return ref.statusTv
    }

    private fun bump(cat: String) { tabCounts[cat] = (tabCounts[cat] ?: 0) + 1 }

    /**
     * 对话用的**一行结论**：换行折叠成「 / 」、超长截断并提示详见日志。
     * 与完整原文不同（即发生折叠）时，调用方会把完整内容额外投放到「日志」Tab。
     */
    private fun summarize(text: String, prefix: String): String {
        val multiline = text.contains("\n")
        val one = text.replace(Regex("\\s*\\n+\\s*"), " / ").trim()
        val folded = multiline || one.length > CHAT_SUMMARY_MAX
        val head = if (one.length <= CHAT_SUMMARY_MAX) one else one.take(CHAT_SUMMARY_MAX) + "…"
        val s = if (prefix.isBlank()) head else "$prefix $head"
        return if (folded) "$s  （详见日志）" else s
    }

    /** 一行消息的视图引用：root 用于挂载，statusTv 用于后续更新「发送中/已送达/失败」。 */
    private class RowRef(val root: View, val statusTv: TextView?)

    /**
     * 构建单条气泡行（QQ 风格）：气泡 + 下方一行小字「时间戳 · 发送状态」。
     * 每行的气泡再套一层 HorizontalScrollView 以支持左右滚动。
     */
    /**
     * 让文本可被**长按选中 / 复制**（2026-09-22：对话·股票·任务·日志·全部 五个 Tab 全支持）。
     *
     * 用 Android 原生能力 `setTextIsSelectable(true)`：长按弹出系统「全选/复制」菜单，
     * 能正确复制**用户选中的部分**，比自己写剪贴板更稳（也自带粘贴光标）。
     *
     * 两个注意点：
     * - 该方法内部已把 TextView 设为可聚焦，无需再设 `isFocusableInTouchMode`，
     *   否则父容器（HorizontalScrollView）的横向拖动会被首个长按抢走。
     * - 与「长行横向滚动」不冲突：选择由系统处理，滚动仍归外层容器。
     */
    private fun TextView.enableCopy() {
        setTextIsSelectable(true)
        // 选中后光标可见；不改变布局，也不拦截触摸
        setCursorVisible(true)
    }

    private fun buildRow(
        text: String,
        prefix: String,
        category: String,
        fromApk: Boolean,
        ts: String,
        status: String?
    ): RowRef {
        val bubble = TextView(context).apply {
            this.text = if (prefix.isBlank()) text else "$prefix $text"
            textSize = 12f
            setTextColor(
                if (fromApk) Color.WHITE
                else if (category == CAT_LOG) 0xFFCFD8DC.toInt()
                else if (category == CAT_STOCK) 0xFF0D47A1.toInt()   // ★ 选股：蓝字
                else 0xFF263238.toInt()
            )
            setPadding(10.dp(), 6.dp(), 10.dp(), 6.dp())
            background = rnd(
                if (fromApk) 0xFF2E7D32.toInt()
                else if (category == CAT_LOG) 0xFF263238.toInt()
                else if (category == CAT_STOCK) 0xFFBBDEFB.toInt()   // ★ 选股：浅蓝底
                else 0xFFDCEDC8.toInt(), 8.dp()
            )
            // 关键：关掉自动换行、仍保留 \n 换行 → 超长行交给外层横向滚动
            setSingleLine(false)
            setHorizontallyScrolling(true)
            enableCopy()                        // ★ 长按可选中/复制
        }
        val hsv = HorizontalScrollView(context).apply {
            isFillViewport = false              // 短气泡保持内容宽度，长气泡才横向滚
            isHorizontalScrollBarEnabled = true
            addView(bubble, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // ── 气泡下方的小字：时间戳 + 发送状态（QQ/微信风格）──
        val meta = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        meta.addView(TextView(context).apply {
            this.text = ts
            textSize = 9f
            setTextColor(0xFF9E9E9E.toInt())
            enableCopy()
        })
        val statusTv = TextView(context).apply {
            this.text = status.orEmpty()
            textSize = 9f
            setTextColor(0xFF9E9E9E.toInt())
            setPadding(6.dp(), 0, 0, 0)
            visibility = if (status.isNullOrBlank()) View.GONE else View.VISIBLE
            enableCopy()
        }
        meta.addView(statusTv)

        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (fromApk) Gravity.END else Gravity.START
            addView(hsv, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(meta, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 1.dp()
            })
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (fromApk) Gravity.END else Gravity.START
        }
        row.addView(col, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.layoutParams = LinearLayout.LayoutParams(
            MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 3.dp()
            leftMargin = if (fromApk) 40.dp() else 0
            rightMargin = if (fromApk) 0 else 40.dp()
        }
        return RowRef(row, statusTv)
    }

    /** 更新某条消息的发送状态（并可选若干毫秒后自动清空，QQ 的「已读」淡出效果）。 */
    private fun setStatus(refs: List<TextView>, text: String, okColor: Int? = null,
                          clearAfterMs: Long = 0) {
        refs.forEach { tv ->
            tv.text = text
            tv.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
            if (okColor != null) tv.setTextColor(okColor)
            if (clearAfterMs > 0) {
                tv.postDelayed({
                    tv.text = ""
                    tv.visibility = View.GONE
                }, clearAfterMs)
            }
        }
    }

    private fun nowTs() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    // ── Tab 切换：只显示当前 Tab 的滚动容器，其余彻底隐藏（内容互不干扰） ──

    private fun setChatFilter(cat: String) {
        chatFilter = cat
        chatScrolls.forEach { (c, sv) -> sv.visibility = if (c == cat) View.VISIBLE else View.GONE }
        renderTabs()
        autoScroll()
    }

    /** 渲染 Tab：高亮当前项 + 显示各自条数（全部 12 / 对话 3 / 日志 9）。 */
    private fun renderTabs() {
        filterTabs.forEach { (cat, tv) ->
            val on = (cat == chatFilter)
            tv.text = "${tabLabels[cat] ?: cat} ${tabCounts[cat] ?: 0}"
            tv.setTextColor(if (on) Color.WHITE else 0xFF546E7A.toInt())
            tv.background = rnd(if (on) 0xFF4527A0.toInt() else 0xFFDDDDDD.toInt(), 12.dp())
        }
        chatScrolls.forEach { (c, sv) -> sv.visibility = if (c == chatFilter) View.VISIBLE else View.GONE }
    }

    /** 把**当前可见**的 Tab 滚到底部（隐藏的 Tab 不打扰，各自保留自己的滚动位置）。 */
    private fun autoScroll() {
        val sv = chatScrolls[chatFilter] ?: return
        sv.post { sv.fullScroll(View.FOCUS_DOWN) }
    }

    /** 中继状态：设备 ID（如 sms9280-xxxx）+ **具体同步时间点**（绝对时间，非「X分钟前」）。 */
    private fun syncStatusText(): String {
        val dev = CosRelayClient.deviceId(context)
        val last = CosRelayClient.lastOkAt(context)
        val t = if (last <= 0L) "尚未同步"
        else SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(last))
        return "$dev · 同步时间 $t"
    }

    private fun submit(taskType: String) {
        statusTv?.text = "提交任务 $taskType …"
        scope.launch {
            try {
                val r = PcBridgeClient.submitTask(taskType = taskType)
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
        scope.launch {
            try {
                val r = PcBridgeClient.listTasks()
                val tasks = JSONObject(r).optJSONArray("tasks") ?: JSONArray()
                taskListBox?.removeAllViews()
                for (i in 0 until tasks.length()) {
                    taskListBox?.addView(taskCard(tasks.getJSONObject(i)))
                }
                statusTv?.text = "共 ${tasks.length()} 个任务（最近） | 中继"
                tabCounts[CAT_TASK] = tasks.length()   // 任务数显示在「任务」Tab 上
                renderTabs()
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
        // 注：原先这里还会把 `log_tail` 渲染成 3 行预览 —— 与「日志」Tab 内容重复，
        // 已按需求移除；要看完整日志请点卡片上的「📜 日志」，结果会进「日志」Tab。

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
            enableCopy()                        // ★ 任务名/状态可复制
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(TextView(context).apply {
            text = if (state == "success") "退出$exit" else if (state == "failed") "失败" else t.optString("created_at", "").takeLast(8)
            textSize = 11f
            setTextColor(0xFF90A4AE.toInt())
            enableCopy()
        })
        card.addView(head)
        card.addView(TextView(context).apply {
            text = "$type | ${t.optString("finished_at", "运行中").takeLast(8)}"
            textSize = 10f
            setTextColor(0xFF90A4AE.toInt())
            enableCopy()                        // ★ 任务 ID 常在这里，务必可复制
        })
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
                        PcBridgeClient.cancelTask(taskId = id)
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

    /** 拉取某任务的完整日志，作为「日志」行并入会话流（`🖥` = PC 端 exe 输出）。 */
    private fun showLogs(taskId: String) {
        scope.launch {
            try {
                val r = PcBridgeClient.taskLogs(taskId = taskId, cursor = 0)
                val logs = JSONObject(r).optJSONArray("logs") ?: JSONArray()
                addMsgRow("── 任务 $taskId 日志（${logs.length()} 行）──",
                    prefix = PC_LOG_PREFIX, category = CAT_LOG)
                for (i in 0 until logs.length()) {
                    val item = logs.getJSONObject(i)
                    addMsgRow("${item.optString("ts")}  ${item.optString("line")}",
                        prefix = PC_LOG_PREFIX, category = CAT_LOG)
                }
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
                    val r = PcBridgeClient.taskLogs(taskId = taskId, cursor = cursor)
                    val j = JSONObject(r)
                    val logs = j.optJSONArray("logs") ?: JSONArray()
                    for (i in 0 until logs.length()) {
                        val item = logs.getJSONObject(i)
                        addMsgRow("${item.optString("ts")}  ${item.optString("line")}",
                            prefix = PC_LOG_PREFIX, category = CAT_LOG)
                    }
                    cursor = j.optInt("cursor", cursor)
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
