package com.chin.stockanalysis.strategy.trade.macro

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.config.DataConfig
import com.chin.stockanalysis.stock.data.HttpClientProvider
import com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * ## 全球市场 + 大宗商品数据采集器（MacroMarketDataFetcher）
 *
 * 为宏观环境 Pipeline 提供「跨市场、跨资产」的实时基准数据：
 * - **美股**：纳斯达克、标普500、道琼斯（来自 [EastMoneyHotSectorSource.globalIndices] 已缓存池）
 * - **韩股**：韩国 KOSPI（半导体/存储产业链风向标，常用于判断 A 股存储/半导体次日联动）
 * - **大宗商品**：原油（WTI/布伦特）、黄金、白银
 * - **利率与汇率**：美债 10Y、美元指数（全球流动性与风险偏好）
 * - **A 股核心指数**：上证、科创50、创业板指、深成指、沪深300
 *
 * ### 为什么需要
 * 1. **美股/韩股是 A 股开盘前的重要信号**：隔夜纳斯达克大跌 → 次日 A 股科技/半导体承压。
 * 2. **油价 → 化工联动**：原油涨 → 化工（尤其油头化工、石化）成本与产品价联动。
 * 3. **美债/美元**：反映全球流动性，美元走强利空新兴市场风险资产。
 *
 * 数据全部走东方财富统一 push2 `ulist.np/get` 接口（与既有 [EastMoneyHotSectorSource.fetchIndexRaw] 同一套），
 * 无需新增数据源。
 */
object MacroMarketDataFetcher {

    private const val TAG = "MacroMarketDataFetcher"
    private val client = HttpClientProvider.realtimeClient

    /** 单项资产的最新行情 */
    data class AssetQuote(
        val key: String,          // 唯一标识，如 US_NDX / OIL_WTI / GOLD / USD_INDEX
        val name: String,         // 展示名
        val price: Double,
        val changePct: Double,    // 当日涨跌幅 %
        val changeAmount: Double
    )

    /** 全球市场数据快照 */
    data class GlobalSnapshot(
        val quotes: Map<String, AssetQuote>,   // key → 行情
        val fetchTime: Long,
        val online: Boolean                    // 是否成功拉到实时数据
    )

    /** 资产 key 常量 */
    object Key {
        // 美股
        const val US_NDX = "US_NDX"      // 纳斯达克100
        const val US_SPX = "US_SPX"      // 标普500
        const val US_DJI = "US_DJI"      // 道琼斯
        // 亚太
        const val KR_KOSPI = "KR_KOSPI"  // 韩国KOSPI
        const val HK_HSI = "HK_HSI"      // 恒生
        // 大宗
        const val OIL_WTI = "OIL_WTI"    // 美原油
        const val OIL_BRENT = "OIL_BRENT"// 布伦特原油
        const val GOLD = "GOLD"          // 黄金
        const val SILVER = "SILVER"      // 白银
        // 利率/汇率
        const val US10Y = "US10Y"        // 美债10Y
        const val USD_INDEX = "USD_INDEX"// 美元指数
    }

    /**
     * 需要额外拉取的大宗/利率/汇率资产（东方财富 secid → 本地key/名称）。
     * 这些不在 [EastMoneyHotSectorSource.INDEX_CODES] 内，需单独请求。
     *
     * 说明：东方财富 `ulist.np/get` 支持 `100.` 前缀（海外指数/商品），
     * 具体 secid 若失效会自动跳过，不影响整体 Pipeline（在线数据优先）。
     */
    private val COMMODITY_SECIDS = listOf(
        // 美原油 / 布伦特 / 黄金 / 白银
        "100.WTI" to (Key.OIL_WTI to "WTI原油"),
        "100.BZ" to (Key.OIL_BRENT to "布伦特原油"),
        "100.AU" to (Key.GOLD to "黄金"),
        "100.SI" to (Key.SILVER to "白银"),
        // 美债10Y收益率 / 美元指数
        "100.US10Y" to (Key.US10Y to "美债10Y"),
        "100.UDI" to (Key.USD_INDEX to "美元指数")
    )

    /**
     * 拉取全球市场 + 大宗商品快照。
     *
     * 优先复用 [EastMoneyHotSectorSource.globalIndices] 缓存（美股/韩股/恒生/上证等已在后台定时刷新），
     * 再对大宗/利率额外实时拉取。
     */
    suspend fun fetch(context: Context): GlobalSnapshot = withContext(Dispatchers.IO) {
        val quotes = mutableMapOf<String, AssetQuote>()
        val online = try { snapshotFromCached() } catch (e: Exception) { false }

        // ── 1. 复用已缓存池中的全球指数 ──
        try {
            val cached = EastMoneyHotSectorSource.globalIndices
            for (g in cached) {
                val key = mapIndexKey(g.name)
                if (key != null) {
                    quotes[key] = AssetQuote(key, g.name, g.price, g.changePercent, g.changeAmount)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取缓存全球指数失败: ${e.message}")
        }

        // ── 2. 大宗商品 / 利率 / 汇率 ──
        try {
            val secids = COMMODITY_SECIDS.joinToString(",") { it.first }
            if (secids.isNotEmpty()) {
                val fetched = fetchSecidQuotes(secids)
                for ((key, name) in COMMODITY_SECIDS.map { it.second }) {
                    fetched[key]?.let { quotes[key] = it.copy(name = name) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "拉取大宗商品失败: ${e.message}")
        }

        GlobalSnapshot(quotes, System.currentTimeMillis(), online)
    }

    /**
     * 便捷读取单个资产行情。
     */
    suspend fun quote(context: Context, key: String): AssetQuote? =
        fetch(context).quotes[key]

    // ═══════════════════════════════════════════
    // 内部实现
    // ═══════════════════════════════════════════

    /** 检测调度器是否在线（globalIndices 非空即视为在线） */
    private fun snapshotFromCached(): Boolean {
        return EastMoneyHotSectorSource.globalIndices.isNotEmpty()
    }

    /** 从东方财富批量接口拉取指定 secid 的实时行情 */
    private fun fetchSecidQuotes(secids: String): Map<String, AssetQuote> {
        if (secids.isBlank()) return emptyMap()
        val map = mutableMapOf<String, AssetQuote>()
        try {
            val timestamp = System.currentTimeMillis()
            val url = "${DataConfig.eastmoneyPush2}/ulist.np/get?" +
                "fltt=2&invt=2&fields=f2,f3,f4,f12,f14&secids=$secids&_=$timestamp"
            val req = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0")
                .addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return map
            val diffs = JSONObject(resp.body?.string() ?: "").optJSONObject("data")?.optJSONArray("diff") ?: return map
            for (i in 0 until diffs.length()) {
                val item = diffs.getJSONObject(i)
                val secid = item.optString("f12", "")
                val key = COMMODITY_SECIDS.firstOrNull { it.first.endsWith(secid) }?.second?.first
                    ?: continue
                map[key] = AssetQuote(
                    key = key,
                    name = item.optString("f14", ""),
                    price = item.optDouble("f2", 0.0),
                    changePct = item.optDouble("f3", 0.0),
                    changeAmount = item.optDouble("f4", 0.0)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "secid 批量拉取失败: ${e.message}")
        }
        return map
    }

    /** 将缓存池中的指数名映射为内部 key（用于统一查询） */
    private fun mapIndexKey(name: String): String? = when {
        name.contains("纳斯达克") -> Key.US_NDX
        name.contains("标普") -> Key.US_SPX
        name.contains("道琼斯") -> Key.US_DJI
        name.contains("KOSPI") || name.contains("韩国") -> Key.KR_KOSPI
        name.contains("恒生") -> Key.HK_HSI
        else -> null
    }
}
