package com.chin.stockanalysis.stock.intent.handlers

import com.chin.stockanalysis.stock.intent.IntentResult

/**
 * 意图处理器接口 - 职责链模式
 */
interface IntentHandler {
    /**
     * 检查是否匹配此处理器
     */
    fun match(input: String): Boolean

    /**
     * 解析用户输入
     */
    fun parse(input: String): IntentResult

    /**
     * 下一个处理器
     */
    var next: IntentHandler?
}


