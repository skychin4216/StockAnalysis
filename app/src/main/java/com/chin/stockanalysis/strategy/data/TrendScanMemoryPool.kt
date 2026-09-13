package com.chin.stockanalysis.strategy.data

import java.util.Collections

/**
 * ## 趋势图扫描公共内存池
 *
 * 记录"已更新过最新 K 线"的股票代码（进程内单例）。
 * 供趋势图自动扫描、数据刷新等模块共用：
 * 如果某个股票刚刚被其他模块更新过（[isUpdated] 返回 true），
 * 则无需再次拉取，实现全局去重。
 */
object TrendScanMemoryPool {

    private val updatedCodes: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /** 该股票是否已在本次进程内更新过最新 K 线 */
    fun isUpdated(code: String): Boolean = updatedCodes.contains(code)

    /** 标记该股票已更新 */
    fun markUpdated(code: String) {
        updatedCodes.add(code)
    }

    /** 当前已记录数量 */
    fun size(): Int = updatedCodes.size

    /** 清空记录（下次全量重扫） */
    fun clear() {
        updatedCodes.clear()
    }
}
