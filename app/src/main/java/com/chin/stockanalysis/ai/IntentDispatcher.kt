package com.chin.stockanalysis.ai

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.ui.CrossTabBus
import com.chin.stockanalysis.ui.CrossTabCommand

/**
 * ## 對話意圖分發器 v2.0
 *
 * 設計原則（參考豆包）：
 * - 只攔截**明確的系統操作指令**（執行量化、買入、切換Tab等）
 * - 所有其他輸入（股票分析、對比、非股票問題）全部走 AI 對話流程
 * - AI 自己判斷用戶意圖，動態選擇輸出格式
 *
 * 這樣就不需要關鍵詞硬編碼判斷「對比」「分析」等意圖。
 */
object IntentDispatcher {

    private const val TAG = "IntentDispatcher"

    /** 嘗試分發用戶指令，返回 true 表示已處理（系統指令），false 表示繼續常規AI對話 */
    fun dispatch(userText: String, context: Context): Boolean {
        val text = userText.trim()

        // ═══════════════════════════════════════
        // 只攔截明確的系統操作指令
        // ═══════════════════════════════════════

        // ── 中線量化操作指令 ──
        if (text.contains("执行中线量化") || text.contains("开始中线量化")) {
            CrossTabBus.postCommand(CrossTabCommand(action = "EXECUTE_SIMULATE_TRADE"))
            Log.i(TAG, "📢 系統指令: 执行中线量化"); return true
        }
        if (text.contains("一键买入") || text.contains("自动买入") || text.contains("全部买入")) {
            CrossTabBus.postCommand(CrossTabCommand(action = "BUY_ALL"))
            Log.i(TAG, "📢 系統指令: 一键买入"); return true
        }
        if (text.contains("运行短线选股") || text.contains("运行短线量化")) {
            CrossTabBus.postCommand(CrossTabCommand(action = "RUN_PIPELINE"))
            Log.i(TAG, "📢 系統指令: 运行短线量化"); return true
        }
        if (text.contains("打开量化选股") || text.contains("量化选股") || text.contains("执行策略")) {
            CrossTabBus.postCommand(CrossTabCommand(action = "SWITCH_TO_STRATEGY_TAB"))
            Log.i(TAG, "📢 系統指令: 切换到策略Tab"); return true
        }

        // ── 創建策略指令 ──
        if (text.contains("创建") && text.contains("策略")) {
            val desc = text.replace("创建", "").replace("策略", "").trim()
            if (desc.isNotBlank()) {
                CrossTabBus.postCommand(CrossTabCommand(
                    action = "CREATE_STRATEGY",
                    stockName = desc
                ))
                Log.i(TAG, "📢 系統指令: 创建策略")
                return true
            }
        }

        // ═══════════════════════════════════════
        // 以下全部走 AI 對話流程，由 AI 判斷意圖
        // 包括：股票分析、多股對比、非股票問題、閒聊等
        // ═══════════════════════════════════════
        return false
    }
}
