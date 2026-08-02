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
    @Volatile
    private var engine: StrategyEngine? = null

    /**
     * 初始化全局引擎（只执行一次）
     */
    @Synchronized
    fun init(context: Context) {
        if (engine != null) return
        val repo = StockDataSourceFactory.createDefaultRepository(context.applicationContext)
        val screener = StockScreener(repo, context.applicationContext)
        engine = StrategyEngine(context.applicationContext, screener).apply {
            registerStrategy(MovingAverageStrategy(screener))
            registerStrategy(VolumeBreakStrategy(screener))
            registerStrategy(LowValuationStrategy(screener))
            registerStrategy(GapUpMomentumStrategy(screener))
            registerStrategy(TurnoverFilterStrategy(screener))
            registerStrategy(BollingerBandStrategy(screener))
            registerStrategy(RSIDivergenceStrategy(screener))
            registerStrategy(FundamentalFilterStrategy(screener))
            registerStrategy(EarlyMorningChaseStrategy(screener))
            registerStrategy(TailLowPickStrategy(screener, context.applicationContext))
            registerStrategy(AIPredictionStrategy(context.applicationContext))
            registerStrategy(HotSpotDrivenStrategy(screener))
            registerStrategy(DragonHeadDipStrategy(context.applicationContext, screener))
            registerStrategy(InstitutionalAccumulationStrategy(screener))
            registerStrategy(MoatLeaderStrategy(screener))
            // ── v1.1 新增策略 ──
            registerStrategy(TrendFollowingStrategy(context.applicationContext, screener))
            registerStrategy(SectorRotationStrategy(context.applicationContext, screener))
            registerStrategy(MarketSentimentStrategy(context.applicationContext, screener))
            // ── v1.2 趨勢加減分策略（不過濾標的，只加減分）──
            registerStrategy(TrendScoreStrategy(context.applicationContext, screener))
            // ── v1.3 週期低位策略（長線左側佈局，防暴雷+52週低位）──
            registerStrategy(CyclicalLowPositionStrategy(context.applicationContext, screener))
            // 啟動時清理過期信號緩存
            cleanExpiredResults()
        }
    }

    /**
     * 获取全局引擎实例
     * @throws IllegalStateException 如果尚未初始化
     */
    fun get(): StrategyEngine = engine ?: throw IllegalStateException("StrategyEngineHolder 尚未初始化，请先调用 init()")
}