package com.chin.stockanalysis.stock.autorefresh

import com.chin.stockanalysis.stock.StockRealtime

/**
 * ## 价格更新通知器
 *
 * 观察者模式：当 [StockAutoRefreshManager] 获取到最新价格后，
 * 通知所有注册的监听器，由监听器负责更新 UI。
 *
 * 使用 sealed class 封装不同类型的事件。
 */
object PriceUpdateNotifier {

    private val listeners = mutableSetOf<PriceUpdateListener>()

    /** 注册更新监听 */
    fun addListener(listener: PriceUpdateListener) {
        listeners.add(listener)
    }

    /** 移除更新监听 */
    fun removeListener(listener: PriceUpdateListener) {
        listeners.remove(listener)
    }

    /** 广播价格更新 */
    fun notifyPriceUpdated(data: Map<String, StockRealtime>, source: String = "auto-refresh") {
        if (data.isEmpty()) return
        val event = PriceUpdateEvent.PricesUpdated(data, source)
        for (listener in listeners.toList()) {
            try {
                listener.onPriceUpdate(event)
            } catch (e: Exception) {
                // 保护：单个监听器异常不影响其他监听器
            }
        }
    }

    /** 广播刷新状态变更 */
    fun notifyStatusChanged(status: RefreshStatus) {
        val event = PriceUpdateEvent.StatusChanged(status)
        for (listener in listeners.toList()) {
            try {
                listener.onPriceUpdate(event)
            } catch (_: Exception) { }
        }
    }

    /** 清空所有监听器（Fragment 销毁时调用） */
    fun clearAll() {
        listeners.clear()
    }
}

/**
 * 价格更新监听器接口
 */
interface PriceUpdateListener {
    fun onPriceUpdate(event: PriceUpdateEvent)
}

/**
 * 价格更新事件
 */
sealed class PriceUpdateEvent {
    data class PricesUpdated(
        val data: Map<String, StockRealtime>,
        val source: String
    ) : PriceUpdateEvent()

    data class StatusChanged(
        val status: RefreshStatus
    ) : PriceUpdateEvent()
}

/**
 * 刷新状态
 */
sealed class RefreshStatus {
    data object Idle : RefreshStatus()
    data class Refreshing(val stockCount: Int) : RefreshStatus()
    data class Success(val updateTimeMs: Long, val updatedCount: Int) : RefreshStatus()
    data class Error(val message: String) : RefreshStatus()
}