package com.chin.stockanalysis.strategy

import android.content.Context
import com.chin.stockanalysis.stock.data.StockDataSourceFactory
import com.chin.stockanalysis.strategy.data.StockScreener
import com.chin.stockanalysis.strategy.strategies.*

/**
 * ## 策略引擎单例持有者
 *
 * 整个 App 共享一个 [StrategyEngine] 实例，
 * 策略注册、启用状态、开启/关闭状态在两个 Tab 之间共享。
 *
 * ### 使用方式
 * ```kotlin
 * // Application.onCreate() 或第一个 Fragment 创建时
 * StrategyEngineHolder.init(context)
 *
 * // 任意位置获取
 * val engine = StrategyEngineHolder.get()
 * ```
 */
object StrategyEngineHolder {
    private var engine: StrategyEngine? = null

    /**
     * 初始化全局引擎（只执行一次）
     */
    fun init(context: Context) {
        if (engine != null) return
        val repo = StockDataSourceFactory.createDefaultRepository(context.applicationContext)
        val screener = StockScreener(repo)
        engine = StrategyEngine(context.applicationContext, screener).apply {
            registerStrategy(MovingAverageStrategy(screener))
            registerStrategy(VolumeBreakStrategy(screener))
            registerStrategy(LowValuationStrategy(screener))
            registerStrategy(GapUpMomentumStrategy(screener))
            registerStrategy(TurnoverFilterStrategy(screener))
            registerStrategy(EarlyMorningChaseStrategy(screener))
            registerStrategy(TailLowPickStrategy(screener))
            registerStrategy(AIPredictionStrategy(context.applicationContext))
        }
    }

    /**
     * 获取全局引擎实例
     * @throws IllegalStateException 如果尚未初始化
     */
    fun get(): StrategyEngine = engine ?: throw IllegalStateException("StrategyEngineHolder 尚未初始化，请先调用 init()")
}