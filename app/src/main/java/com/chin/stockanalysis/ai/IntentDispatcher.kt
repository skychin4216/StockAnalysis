package com.chin.stockanalysis.ai

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.ui.CrossTabBus
import com.chin.stockanalysis.ui.CrossTabCommand

/**
 * ## 对话意图分发器 v2.0
 *
 * 设计原则（参考豆包）：
 * - 只拦截**明确的系统操作指令**（执行量化、买入、切换Tab等）
 * - 所有其他输入（股票分析、对比、非股票问题）全部走 AI 对话流程
 * - AI 自己判断用户意图，动态选择输出格式
 *
 * 这样就不需要关键词硬编码判断「对比」「分析」等意图。
 */
object IntentDispatcher {

    private const val TAG = "IntentDispatcher"

    /** 尝试分发用户指令，返回 true 表示已处理（系统指令），false 表示继续常规AI对话 */
    fun dispatch(userText: String, context: Context): Boolean {
        val text = userText.trim()

        // ═══════════════════════════════════════
        // 只拦截明确的系统操作指令
        // ═══════════════════════════════════════

        // ── 中线量化操作指令 ──
        if (text.contains("执行中线量化") || text.contains("开始中线量化")) {
            CrossTabBus.tryPostCommand(CrossTabCommand(action = "EXECUTE_SIMULATE_TRADE"))
            Log.i(TAG, "📢 系统指令: 执行中线量化"); return true
        }
        if (text.contains("一键买入") || text.contains("自动买入") || text.contains("全部买入")) {
            CrossTabBus.tryPostCommand(CrossTabCommand(action = "BUY_ALL"))
            Log.i(TAG, "📢 系统指令: 一键买入"); return true
        }
        if (text.contains("运行短线选股") || text.contains("运行短线量化")) {
            CrossTabBus.tryPostCommand(CrossTabCommand(action = "RUN_PIPELINE"))
            Log.i(TAG, "📢 系统指令: 运行短线量化"); return true
        }
        if (text.contains("打开量化选股") || text.contains("量化选股") || text.contains("执行策略")) {
            CrossTabBus.tryPostCommand(CrossTabCommand(action = "SWITCH_TO_STRATEGY_TAB"))
            Log.i(TAG, "📢 系统指令: 切换到策略Tab"); return true
        }

        // ── 创建策略指令 ──
        if (text.contains("创建") && text.contains("策略")) {
            val desc = text.replace("创建", "").replace("策略", "").trim()
            if (desc.isNotBlank()) {
                CrossTabBus.tryPostCommand(CrossTabCommand(
                    action = "CREATE_STRATEGY",
                    stockName = desc
                ))
                Log.i(TAG, "📢 系统指令: 创建策略")
                return true
            }
        }

        // ═══════════════════════════════════════
        // 以下全部走 AI 对话流程，由 AI 判断意图
        // 包括：股票分析、多股对比、非股票问题、闲聊等
        // ═══════════════════════════════════════
        return false
    }
}
