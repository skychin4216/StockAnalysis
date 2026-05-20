package com.chin.stockanalysis.stock.data

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.data.sources.*

/**
 * ## 数据源工厂 - 管理优先级和多数据源配置
 *
 * 优先级策略：
 * | 源 | 优先级 | 特点 | 需要配置 |
 * |---|---|---|---|
 * | 聚宽 (JoinQuants) | 1 | 专业级数据 | ✅ Token |
 * | 新浪 (Sina) | 2 | 免费稳定 | ❌ 无需 |
 * | 腾讯 (Tencent) | 3 | 备用源 | ❌ 无需 |
 * | 东方财富 (EastMoney) | 4 | 备用源 | ❌ 无需 |
 * | AKShare | 5 | 补充源 | ❌ 无需 |
 */
object StockDataSourceFactory {

    private val tag = "StockDataSourceFactory"

    /**
     * 创建默认数据源列表（按优先级排序）
     *
     * @param context 上下文（用于读取聚宽 Token）
     * @return 排序后的数据源列表
     */
    fun createDefaultSources(context: Context): List<StockDataSource> {
        val sources = mutableListOf<StockDataSource>()

        // 1. 聚宽（优先级2，需要Token）
        try {
            val jqToken = loadJoinQuantsToken(context)
            if (jqToken.isNotEmpty()) {
                sources.add(JoinQuantsSource(jqToken))
                Log.d(tag, "✓ JoinQuants (priority=2)")
            }
        } catch (e: Exception) {
            Log.w(tag, "JoinQuants init failed: ${e.message}")
        }

        // 2. 新浪财经（优先级1，核心源）
        sources.add(SinaStockSource())
        Log.d(tag, "✓ Sina (priority=1)")

        // 3. AKShare（优先级4，免费补充）
        sources.add(AKShareSource())
        Log.d(tag, "✓ AKShare (priority=4)")

        // 4. 腾讯财经（优先级2，备用）
        sources.add(TencentStockSource())
        Log.d(tag, "✓ Tencent (priority=2)")

        // 5. 东方财富（优先级3，备用）
        sources.add(EastMoneyStockSource())
        Log.d(tag, "✓ EastMoney (priority=3)")

        return sources.sortedBy { it.priority() }
    }

    /**
     * 创建默认多源仓储（完整配置）
     */
    fun createDefaultRepository(context: Context): MultiSourceStockRepository {
        val sources = createDefaultSources(context)
        return MultiSourceStockRepository(sources, SmartStockCache())
    }

    /**
     * 创建轻量级多源仓储（仅核心源，无聚宽/东方财富）
     */
    @Suppress("unused")
    fun createLightweightRepository(context: Context): MultiSourceStockRepository {
        val sources = listOf(
            SinaStockSource(),
            AKShareSource(),
            TencentStockSource()
        ).sortedBy { it.priority() }
        return MultiSourceStockRepository(sources, SmartStockCache())
    }

    /**
     * 从 SharedPreferences 加载聚宽 Token
     */
    private fun loadJoinQuantsToken(context: Context): String {
        val sp = context.getSharedPreferences("stock_config", Context.MODE_PRIVATE)
        return sp.getString("joinquants_token", "") ?: ""
    }

    /**
     * 保存聚宽 Token
     */
    fun saveJoinQuantsToken(context: Context, token: String) {
        val sp = context.getSharedPreferences("stock_config", Context.MODE_PRIVATE)
        sp.edit().putString("joinquants_token", token).apply()
        Log.d(tag, "JoinQuants token saved")
    }
}