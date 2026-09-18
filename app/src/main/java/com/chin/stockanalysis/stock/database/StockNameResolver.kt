package com.chin.stockanalysis.stock.database

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.config.DataConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ## 股票名称统一解析器
 *
 * 不管在策略管道、AI对话、自选股、候选池等任何场景选到的股票，
 * 如果缺少名称，都可以通过此解析器动态补全。
 *
 * 三级查找策略（由快到慢）：
 *  1. stock_basics 表（本地快取，毫秒级）
 *  2. daily_snapshot 表（取最近一条有名称的记录）
 *  3. 新浪即时行情 API（网络拉取，同时写入快取）
 *
 * 使用方式：
 * ```
 * val name = StockNameResolver.resolve(context, "sh600000")  // 返回 "浦发银行"
 * val map = StockNameResolver.resolveBatch(context, listOf("sh600000", "sz000001"))
 * ```
 */
object StockNameResolver {

    private const val TAG = "StockNameResolver"

    /**
     * 解析单个股票名称
     * @return 股票名称；如果三级查找都失败，返回股票代码本身
     */
    suspend fun resolve(context: Context, stockCode: String): String {
        if (stockCode.isBlank()) return stockCode
        val map = resolveBatch(context, listOf(stockCode))
        return map[stockCode] ?: stockCode
    }

    /**
     * 批量解析股票名称
     *
     * 对传入的代码列表，先检查本地数据库，再从网络补全缺失的。
     * 网络拉取的名称会同时写入 stock_basics 表快取，下次无需网络。
     *
     * @return code → name 的映射；未找到的代码不在结果中
     */
    suspend fun resolveBatch(
        context: Context,
        codes: List<String>
    ): Map<String, String> {
        if (codes.isEmpty()) return emptyMap()
        val appCtx = context.applicationContext
        val db = StockDatabase.getInstance(appCtx)
        val result = mutableMapOf<String, String>()

        // ── 第一级：stock_basics 表 ──
        try {
            val basics = db.stockBasicDao().getByCodes(codes)
            for (b in basics) {
                if (b.name.isNotBlank()) result[b.code] = b.name
            }
        } catch (e: Exception) {
            Log.w(TAG, "stock_basics 查询失败: ${e.message}")
        }

        // ── 第二级：daily_snapshot 表（取最近5条中有名称的） ──
        val stillMissing = codes.filter { it !in result }
        for (code in stillMissing) {
            try {
                val snaps = db.dailySnapshotDao().getByCode(code, 5)
                val named = snaps.firstOrNull { it.name.isNotBlank() }
                if (named != null) result[code] = named.name
            } catch (_: Exception) {}
        }

        // ── 第三级：新浪即时行情 API ──
        val networkMissing = codes.filter { it !in result }
        if (networkMissing.isNotEmpty()) {
            try {
                val fetched = fetchFromSina(appCtx, networkMissing)
                result.putAll(fetched)
            } catch (e: Exception) {
                Log.w(TAG, "新浪API拉取失败: ${e.message}")
            }
        }

        return result
    }

    /**
     * 补全单个股票名称，如果原名不为空则直接返回
     */
    suspend fun resolveIfBlank(
        context: Context,
        stockCode: String,
        currentName: String?
    ): String {
        if (!currentName.isNullOrBlank()) return currentName
        return resolve(context, stockCode)
    }

    /**
     * 批量补全：传入 code→name 的映射，返回补全后的映射
     * 只对名称为空的条目进行查找
     */
    suspend fun fillBlanks(
        context: Context,
        codeNameMap: Map<String, String?>
    ): Map<String, String> {
        val blankCodes = codeNameMap.filter { it.value.isNullOrBlank() }.keys.toList()
        if (blankCodes.isEmpty()) {
            return codeNameMap.mapValues { it.value ?: it.key }
        }
        val resolved = resolveBatch(context, blankCodes)
        return codeNameMap.mapValues { (code, name) ->
            if (!name.isNullOrBlank()) name else resolved[code] ?: code
        }
    }

    /**
     * 从新浪即时行情 API 批量拉取股票名称
     * 新浪返回格式（GBK编码）: var hq_str_sh600000="浦发银行,10.50,...";
     * 拉取成功后同时写入 stock_basics 表快取
     */
    private suspend fun fetchFromSina(
        context: Context,
        codes: List<String>
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val client = okhttp3.OkHttpClient()
        val db = StockDatabase.getInstance(context)

        for (batch in codes.chunked(60)) {
            try {
                val url = "${DataConfig.sinaHq}/list=${batch.joinToString(",")}"
                val req = okhttp3.Request.Builder()
                    .url(url)
                    .header("Referer", DataConfig.sinaFinance)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                val resp = withContext(Dispatchers.IO) { client.newCall(req).execute() }
                if (!resp.isSuccessful) continue
                val bodyBytes = resp.body?.bytes() ?: continue
                val body = try {
                    String(bodyBytes, java.nio.charset.Charset.forName("GBK"))
                } catch (_: Exception) { String(bodyBytes) }
                val regex = Regex("var hq_str_(sh\\d+|sz\\d+|bj\\d+)=\"([^,]*)")
                for (match in regex.findAll(body)) {
                    val code = match.groupValues[1]
                    val name = match.groupValues[2].trim()
                    if (name.isNotBlank()) {
                        result[code] = name
                        // 写入 stock_basics 快取
                        try {
                            db.stockBasicDao().insert(
                                StockBasicEntity(code = code, name = name, business = "")
                            )
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }
        Log.i(TAG, "新浪API拉取: ${result.size}/${codes.size} 成功")
        return result
    }
}
