package com.chin.stockanalysis.stock.data.sources

import android.util.Log
import com.chin.stockanalysis.stock.data.HttpClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * ## 新浪板块源（东财不可达时的兜底）
 *
 * - 行业板块榜：`vip.stock.finance.sina.com.cn/q/view/newSinaHy.php`
 *   （新浪行业分类全集，含成分数/平均涨跌幅/领涨股，按涨跌幅排序即当日热门行业）
 * - 板块成分：`Market_Center.getHQNodeData?node=<节点>`
 *   （symbol 带 sh/sz 前缀，含名称/最新价/涨跌幅/总市值万元）
 *
 * 已在 PC 侧验证两接口可达（2026-10-29）。
 */
class SinaSectorSource {
    private val client = HttpClientProvider.realtimeClient
    private val tag = "SinaSector"

    data class SinaBoard(
        val node: String, val name: String, val count: Int,
        val changePct: Double, val leaderCode: String, val leaderName: String
    )

    data class SinaStock(
        val symbol: String, val code: String, val name: String,
        val trade: Double, val changePct: Double, val mktcapYi: Double
    )

    /** 行业板块榜（按涨跌幅降序 = 当日热门在前） */
    suspend fun fetchIndustryBoards(): List<SinaBoard> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://vip.stock.finance.sina.com.cn/q/view/newSinaHy.php")
                .addHeader("Referer", "https://finance.sina.com.cn/")
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()
            val body = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                resp.body?.string() ?: throw Exception("空响应")
            }
            // var S_Finance_bankuai_sinaindustry = {...};
            val json = body.substring(body.indexOf('{'), body.lastIndexOf('}') + 1)
            val obj = JSONObject(json)
            val out = ArrayList<SinaBoard>(obj.length())
            for (key in obj.keys()) {
                // 值格式：node,name,成分数,均价,均涨跌,涨跌幅,成交量,成交额,领涨代码,领涨价,领涨涨跌,领涨涨幅,领涨名
                val f = obj.getString(key).split(",")
                if (f.size < 13) continue
                out.add(
                    SinaBoard(
                        node = f[0], name = f[1],
                        count = f[2].toIntOrNull() ?: 0,
                        changePct = f[5].toDoubleOrNull() ?: 0.0,
                        leaderCode = f[8], leaderName = f[12]
                    )
                )
            }
            out.filter { it.count >= 5 }.sortedByDescending { it.changePct }
        }.onFailure { Log.w(tag, "fetchIndustryBoards: ${it.message}") }
         .getOrDefault(emptyList())
    }

    /** 板块成分（按总市值降序取 topN；mktcapYi = 总市值/亿元） */
    suspend fun fetchComponents(node: String, topN: Int): List<SinaStock> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/" +
                    "Market_Center.getHQNodeData?page=1&num=${topN.coerceIn(5, 100)}" +
                    "&sort=mktcap&asc=0&node=$node&symbol=&_s_r_a=page")
                .addHeader("Referer", "https://finance.sina.com.cn/")
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()
            val body = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                resp.body?.string() ?: throw Exception("空响应")
            }
            val arr = org.json.JSONArray(body)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SinaStock(
                    symbol = o.optString("symbol"),
                    code = o.optString("code"),
                    name = o.optString("name"),
                    trade = o.optString("trade").toDoubleOrNull() ?: 0.0,
                    changePct = o.optDouble("changepercent", 0.0),
                    mktcapYi = o.optDouble("mktcap", 0.0) / 10000.0   // 万元 → 亿元
                )
            }.filter { it.symbol.isNotEmpty() }
        }.onFailure { Log.w(tag, "fetchComponents($node): ${it.message}") }
         .getOrDefault(emptyList())
    }
}
