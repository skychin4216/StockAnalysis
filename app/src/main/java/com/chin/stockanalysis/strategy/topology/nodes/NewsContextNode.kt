package com.chin.stockanalysis.strategy.topology.nodes

import android.os.Environment
import com.chin.stockanalysis.stock.database.StockDataCenter
import com.chin.stockanalysis.strategy.topology.core.BaseNode
import com.chin.stockanalysis.strategy.topology.core.NodeType
import com.chin.stockanalysis.strategy.topology.core.PipelineContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 新闻情报上下文（2026-09-19 用户需求，与 Python `usecase_pipeline.py:news_context` 同口径）。
 *
 * 把**当日累积的盘中消息**（PC 侧 `_market_scan` 写入 `_records/_news_accum_YYYYMMDD.jsonl`）
 * 整理成「板块/主题 → 提及次数 + 多空情绪」，供选股参考（usecase 新闻因子）。
 *
 * 数据落地：APK 侧通过 CS 同步 / adb 推送把该 jsonl 放到以下任一目录即可被读到，
 * 找不到时**安全降级**（返回空 + 日志），不影响其它节点。
 *
 * 输出：{day, n, keywords:[{kw,n,sample}], hotSectors[], sentiment:{pos,neg}}
 */
class NewsContextNode(
    private val topN: Int = 12
) : BaseNode<Any, JSONObject>("news_context", "新闻情报上下文", NodeType.FACTOR_COMPUTE) {

    companion object {
        private val POS = listOf("利好", "上涨", "突破", "涨价", "超预期", "增长", "扩产", "中标", "订单")
        private val NEG = listOf("利空", "下跌", "暴跌", "低于预期", "下滑", "亏损", "减持", "处罚", "退市")

        /** 内嵌核心板块词表（APK 无 board_index，做子串提及统计用；与 PC 口径等价）。 */
        private val SECTOR_WORDS = listOf(
            "半导体", "芯片", "光模块", "光伏", "储能", "锂电", "新能源", "军工", "航空", "航天",
            "医药", "创新药", "医疗器械", "白酒", "食品", "农业", "种业", "煤炭", "石油", "油气",
            "有色", "黄金", "稀土", "钢铁", "化工", "建材", "地产", "银行", "保险", "券商",
            "计算机", "软件", "通信", "消费电子", "人工智能", "机器人", "汽车", "电力", "环保",
            "传媒", "游戏", "旅游", "物流", "港口", "船舶", "电网", "风电", "核电", "氢能",
            "算力", "数据要素", "低空经济", "商业航天", "固态电池"
        )
    }

    override suspend fun execute(context: PipelineContext, input: Any): JSONObject {
        val day = SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date())
        val texts = ArrayList<String>()
        val f = findAccumFile(context, day)
        if (f != null && f.exists()) {
            try {
                f.forEachLine { ln ->
                    try {
                        val x = JSONObject(ln).optString("x")
                        val t = x.substringAfter("|", "").trim()
                        if (t.isNotEmpty()) texts.add(t)
                    } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
        }
        val pos = texts.count { t -> POS.any { t.contains(it) } }
        val neg = texts.count { t -> NEG.any { t.contains(it) } }
        // 板块关键词提及统计（用已加载的板块→个股映射的板块名做子串匹配）
        val kw = LinkedHashMap<String, Int>()
        if (texts.isNotEmpty()) {
            // APK 侧没有 PC 的 board_index.json（板块→成分表），这里用内嵌核心板块词表
            // 做子串统计（零依赖、可编译；PC 侧仍用 board_index 全量板块名，口径等价）
            for (nm in SECTOR_WORDS) {
                if (nm.length < 2) continue
                val c = texts.count { it.contains(nm) }
                if (c > 0) kw[nm] = c
            }
        }
        val top = kw.entries.sortedByDescending { it.value }.take(topN)
        val kws = JSONArray()
        val hots = JSONArray()
        for ((k, v) in top) {
            kws.put(JSONObject().put("kw", k).put("n", v)
                .put("sample", texts.firstOrNull { it.contains(k) }?.take(60) ?: ""))
        }
        for ((k, _) in top.take(8)) hots.put(k)
        val out = JSONObject()
            .put("as_of", context.tradeDate ?: "")
            .put("day", day).put("n", texts.size)
            .put("keywords", kws).put("hotSectors", hots)
            .put("sentiment", JSONObject().put("pos", pos).put("neg", neg))
            .put("source", "盘中消息累积 + 板块关键词提及统计")
        context.log(nodeId, if (texts.isNotEmpty())
            "📰 新闻上下文：累积 ${texts.size} 条（利多词 $pos/利空词 $neg）；" +
                "热议板块 " + top.take(6).joinToString("、") { "${it.key}×${it.value}" }
        else
            "新闻上下文：$day 无累积消息（PC 侧 _market_scan 盘中写入 _news_accum_*.jsonl）")
        context.setStageOutput(nodeId, out)
        return out
    }

    /** 累积文件可能落在：外部私有目录（CS 同步常用）/ 内部目录 / 公共目录。 */
    private fun findAccumFile(context: PipelineContext, day: String): File? {
        val name = "_records/_news_accum_$day.jsonl"
        val cands = listOfNotNull(
            context.androidContext.getExternalFilesDir(null)?.let { File(it, name) },
            File(context.androidContext.filesDir, name),
            File(Environment.getExternalStorageDirectory(), "StockAnalysis/$name")
        )
        return cands.firstOrNull { it.exists() }
    }
}
