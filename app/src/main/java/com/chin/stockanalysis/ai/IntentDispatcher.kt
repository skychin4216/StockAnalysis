package com.chin.stockanalysis.ai

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.ui.CrossTabBus
import com.chin.stockanalysis.ui.CrossTabCommand

/**
 * ## 对话意图分发器
 *
 * 解析用户在对话框中的指令，触发跨Tab操作。
 * 被 ChatTabFragment.sendMessage() 调用。
 *
 * ### 支持的指令
 * | 用户输入 | 触发操作 |
 * |---------|---------|
 * | "执行模拟交易" / "买入" | → 策略Tab自动执行模拟交易 |
 * | "分析 sh600519" / "600519怎么样" | → AI 分析单只股票 |
 * | "修改布林带权重" | → 修改策略参数 |
 * | "打开量化选股" | → 切换到策略Tab |
 */
object IntentDispatcher {

    private const val TAG = "IntentDispatcher"

    /**
     * 尝试分发用户指令，返回 true 表示已处理，false 表示继续常规AI对话
     */
    fun dispatch(userText: String, context: Context): Boolean {
        val text = userText.trim()

        // ── 模拟交易指令 ──
        if (text.contains("执行模拟交易") || text.contains("开始模拟交易") || text.contains("模拟交易")) {
            CrossTabBus.postCommand(CrossTabCommand(action = "EXECUTE_SIMULATE_TRADE"))
            Log.i(TAG, "📢 分发指令: 执行模拟交易")
            return true
        }
        if (text.contains("一键买入") || text.contains("自动买入") || text.contains("全部买入")) {
            CrossTabBus.postCommand(CrossTabCommand(action = "BUY_ALL"))
            Log.i(TAG, "📢 分发指令: 一键买入")
            return true
        }

        // ── 运行 Pipeline ──
        if (text.contains("运行pipeline") || text.contains("运行Pipeline") || text.contains("pipeline")) {
            CrossTabBus.postCommand(CrossTabCommand(action = "RUN_PIPELINE"))
            Log.i(TAG, "📢 分发指令: 运行Pipeline")
            return true
        }

        // ── 切换到量化选股Tab ──
        if (text.contains("打开量化选股") || text.contains("量化选股") || text.contains("执行策略")) {
            CrossTabBus.postCommand(CrossTabCommand(action = "SWITCH_TO_STRATEGY_TAB"))
            Log.i(TAG, "📢 分发指令: 切换到策略Tab")
            return true
        }

        // ── 修改策略参数 ──
        if (text.contains("修改") && (text.contains("策略") || text.contains("权重"))) {
            // 提取策略名和参数
            val params = mutableMapOf<String, String>()
            Regex("""(\S+策略).*?(\d+)[%分]""").find(text)?.let { match ->
                params["strategy"] = match.groupValues[1]
                params["weight"] = match.groupValues[2]
            }
            CrossTabBus.postCommand(CrossTabCommand(
                action = "UPDATE_STRATEGY",
                extraParams = params
            ))
            Log.i(TAG, "📢 分发指令: 修改策略参数 $params")
            return true
        }

        // ── 修改智能体描述 ──
        if (text.contains("修改") && text.contains("智能体")) {
            val agentName = Regex("""修改(.*?)智能体""").find(text)?.groupValues?.get(1)?.trim() ?: ""
            val newDesc = text.replace(Regex("""修改.*?智能体.*?"""), "").trim()
            CrossTabBus.postCommand(CrossTabCommand(
                action = "UPDATE_AGENT",
                stockName = agentName,
                extraParams = mapOf("description" to newDesc)
            ))
            Log.i(TAG, "📢 分发指令: 修改智能体 $agentName")
            return true
        }

        // ── AI 预测单只股票 ──
        val stockCode = extractStockCode(text)
        if (stockCode.isNotBlank()) {
            CrossTabBus.postCommand(CrossTabCommand(
                action = "ANALYZE_SINGLE_STOCK",
                stockCode = stockCode,
                stockName = extractStockName(text, stockCode)
            ))
            Log.i(TAG, "📢 分发指令: 分析 $stockCode")
            return true
        }

        return false  // 常规AI对话
    }

    private fun extractStockCode(text: String): String {
        // 匹配 sh/sz + 6位数字
        Regex("""[sS][hHzZ](\d{6})""").find(text)?.let {
            return "sh${it.groupValues[1]}".takeIf { it.length == 8 } ?: "sz${it.groupValues[1]}"
        }
        // 匹配纯6位数字
        Regex("""\b(\d{6})\b""").find(text)?.let {
            val raw = it.groupValues[1]
            return when {
                raw.startsWith("6") || raw.startsWith("5") -> "sh$raw"
                raw.startsWith("0") || raw.startsWith("1") || raw.startsWith("2") || raw.startsWith("3") -> "sz$raw"
                else -> "sh$raw"
            }
        }
        return ""
    }

    private fun extractStockName(text: String, code: String): String {
        // 尝试提取代码前的名称
        val pattern = Regex("""(\p{IsHan}{2,6})\s*(?:$code|\b${code.takeLast(6)}\b)""")
        return pattern.find(text)?.groupValues?.get(1)?.trim() ?: ""
    }
}