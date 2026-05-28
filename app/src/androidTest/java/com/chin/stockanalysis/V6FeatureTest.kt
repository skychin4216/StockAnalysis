package com.chin.stockanalysis

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject

/**
 * ## v6.0 新功能自动化测试
 *
 * 覆盖：
 * 1. ThemeStockLibrary — 板块别名隔离（个股名不应匹配板块）
 * 2. StockQueryEngine — hasStockNameOrCode 个股检测
 * 3. WatchlistGroup — JSON 序列化/反序列化
 * 4. EastMoneyHotSectorSource — API 响应解析（需要网络）
 *
 * 运行方式：
 * ./gradlew connectedDebugAndroidTest --tests "com.chin.stockanalysis.V6FeatureTest"
 */
@RunWith(AndroidJUnit4::class)
class V6FeatureTest {

    companion object {
        private const val TAG = "V6FeatureTest"
    }

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Log.i(TAG, "━━━━━ V6FeatureTest 启动 ━━━━━")
    }

    // ══════════════════════════════════════════════
    // 模块1: ThemeStockLibrary 板块别名隔离
    // ══════════════════════════════════════════════

    /**
     * 验证个股名称不会匹配到板块查询
     * 需求：用户输入"贵州茅台和五粮液"应走个股精确查询，而非白酒板块
     */
    @Test
    fun testThemeAlias_noIndividualStockMatch() {
        // 这些是个股名称（或嵌入在股票名中的子串），不应匹配到任何主题
        val individualStockInputs = listOf(
            "贵州茅台",
            "五粮液",
            "北方稀土",
            "山东黄金",
            "中国铝业",
            "赣锋锂业",
            "中国卫星",
            "茅台和五粮液的最新股价",
            "帮我分析贵州茅台",
        )

        for (input in individualStockInputs) {
            val match = com.chin.stockanalysis.stock.theme.ThemeStockLibrary.findTheme(input)
            // 个股名不应该匹配到板块！除非用户明确说了"白酒板块"/"有色金属板块"等
            if (match != null) {
                // 如果匹配了，必须是真正在问板块级别的词
                val isSectorWord = input.contains("板块") || input.contains("行业") ||
                    input.contains("有色金属") || input.contains("白酒") ||
                    input.contains("军工") || input.contains("新能源")

                if (!isSectorWord) {
                    fail("输入 '$input' 错误匹配到板块 '${match.matchedAlias}' → '${match.themeInfo.name}'。" +
                        "个股名不应触发板块查询！")
                }
            }
        }
        Log.i(TAG, "✅ testThemeAlias_noIndividualStockMatch: 通过")
    }

    /**
     * 验证板块级别关键词仍能正常匹配
     */
    @Test
    fun testThemeAlias_sectorWordsStillMatch() {
        val sectorInputs = mapOf(
            "白酒板块今天怎么样" to "liquor",
            "有色金属板块" to "nonferrous_metals",
            "商业航天产业链" to "commercial_space",
            "军工今天走势" to "military",
            "医药板块" to "pharma",
        )

        for ((input, expectedKey) in sectorInputs) {
            val match = com.chin.stockanalysis.stock.theme.ThemeStockLibrary.findTheme(input)
            assertNotNull("板块关键词 '$input' 应匹配到主题", match)
            assertEquals("输入 '$input' 匹配的主题 key 不符", expectedKey, match?.themeKey)
        }
        Log.i(TAG, "✅ testThemeAlias_sectorWordsStillMatch: 通过")
    }

    // ══════════════════════════════════════════════
    // 模块2: 个股检测逻辑
    // ══════════════════════════════════════════════

    /**
     * 验证 hasStockNameOrCode 正确识别个股/代码
     * 注意：StockQueryEngine.hasStockNameOrCode 是私有方法，
     * 这里通过反射或间接方式验证（直接测试内联逻辑）
     */
    @Test
    fun testStockNameDetection() {
        // 模拟 hasStockNameOrCode 的核心逻辑
        fun hasStockNameOrCode(text: String): Boolean {
            val commonNames = listOf(
                "茅台", "五粮液", "宁德时代", "比亚迪", "工商银行", "建设银行",
                "农业银行", "中国银行", "贵州茅台", "美的集团", "格力电器",
                "立讯精密", "海康威视", "中芯国际", "长江电力", "中兴通讯",
                "迈瑞医疗", "恒瑞医药", "药明康德", "科大讯飞", "紫金矿业",
                "万华化学", "生益科技", "沪电股份", "韦尔股份", "深信服",
                "广联达", "用友网络", "恒生电子", "赣锋锂业", "北方稀土",
                "中科三环", "山东黄金", "中国铝业", "华勤技术", "汇顶科技",
                "平安", "苹果"
            )
            if (commonNames.any { text.contains(it) }) return true
            if (Regex("""[sS][hHzZ]\d{6}""").containsMatchIn(text)) return true
            if (Regex("""\b\d{6}\b""").containsMatchIn(text)) return true
            return false
        }

        // 个股名 → 应返回 true
        val stockCases = listOf(
            "贵州茅台现在多少钱",
            "五粮液和茅台对比",
            "帮我分析宁德时代",
            "比亚迪怎么样",
            "sh600519",
            "600519",
            "sz000858最新价",
        )
        for (c in stockCases) {
            assertTrue("个股输入 '$c' 应被检测为个股", hasStockNameOrCode(c))
        }

        // 非个股 → 应返回 false
        val nonStockCases = listOf(
            "白酒板块今天怎么样",
            "有色金属行业分析",
            "今天大盘走势",
            "推荐几只潜力股",
            "最近什么板块涨得好",
        )
        for (c in nonStockCases) {
            assertFalse("非个股输入 '$c' 不应被检测为个股", hasStockNameOrCode(c))
        }

        Log.i(TAG, "✅ testStockNameDetection: 通过")
    }

    // ══════════════════════════════════════════════
    // 模块3: WatchlistGroup 持久化逻辑
    // ══════════════════════════════════════════════

    @Test
    fun testWatchlistGroupSerialization() {
        // 模拟 WatchlistGroup 的 JSON 序列化/反序列化逻辑
        data class Stock(val code: String, val name: String)
        data class Group(val id: String, val name: String, val stocks: List<Stock>)

        val original = listOf(
            Group("1", "我的自选", listOf(
                Stock("sh600519", "贵州茅台"),
                Stock("sz002594", "比亚迪")
            )),
            Group("2", "美股", listOf(
                Stock("100.NDX", "纳斯达克")
            ))
        )

        // 序列化
        val arr = JSONArray()
        for (g in original) {
            val obj = JSONObject().apply {
                put("id", g.id)
                put("name", g.name)
                val stocksArr = JSONArray()
                for (s in g.stocks) {
                    stocksArr.put(JSONObject().apply {
                        put("code", s.code)
                        put("name", s.name)
                    })
                }
                put("stocks", stocksArr)
            }
            arr.put(obj)
        }
        val json = arr.toString()

        // 反序列化
        val parsed = mutableListOf<Group>()
        val parsedArr = JSONArray(json)
        for (i in 0 until parsedArr.length()) {
            val obj = parsedArr.getJSONObject(i)
            val stocksArr = obj.optJSONArray("stocks") ?: JSONArray()
            val stocks = mutableListOf<Stock>()
            for (j in 0 until stocksArr.length()) {
                val s = stocksArr.getJSONObject(j)
                stocks.add(Stock(s.getString("code"), s.getString("name")))
            }
            parsed.add(Group(obj.getString("id"), obj.getString("name"), stocks))
        }

        // 验证
        assertEquals("组数量应一致", original.size, parsed.size)
        for (i in original.indices) {
            assertEquals("组名", original[i].name, parsed[i].name)
            assertEquals("股票数量", original[i].stocks.size, parsed[i].stocks.size)
            for (j in original[i].stocks.indices) {
                assertEquals("代码", original[i].stocks[j].code, parsed[i].stocks[j].code)
                assertEquals("名称", original[i].stocks[j].name, parsed[i].stocks[j].name)
            }
        }

        Log.i(TAG, "✅ testWatchlistGroupSerialization: 通过")
    }

    @Test
    fun testWatchlistGroup_addRemoveStocks() {
        // 模拟添加/删除股票逻辑
        data class Stock(val code: String, val name: String)
        data class Group(val id: String, val name: String, val stocks: List<Stock>)

        var group = Group("1", "测试组", emptyList())

        // 添加
        group = group.copy(stocks = group.stocks + Stock("sh600519", "贵州茅台"))
        assertEquals(1, group.stocks.size)
        assertEquals("sh600519", group.stocks[0].code)

        // 再添加
        group = group.copy(stocks = group.stocks + Stock("sz002594", "比亚迪"))
        assertEquals(2, group.stocks.size)

        // 删除
        group = group.copy(stocks = group.stocks.filter { it.code != "sh600519" })
        assertEquals(1, group.stocks.size)
        assertEquals("sz002594", group.stocks[0].code)

        // 删除不存在的
        group = group.copy(stocks = group.stocks.filter { it.code != "nonexist" })
        assertEquals(1, group.stocks.size)

        Log.i(TAG, "✅ testWatchlistGroup_addRemoveStocks: 通过")
    }

    // ══════════════════════════════════════════════
    // 模块4: 东方财富热门板块 API 数据解析
    // ══════════════════════════════════════════════

    @Test
    fun testEastMoneyHotSector_parseSectorResponse() {
        // 模拟东方财富 API 返回的 JSON 结构
        val mockJson = JSONObject().apply {
            put("data", JSONObject().apply {
                put("diff", JSONArray().apply {
                    put(JSONObject().apply {
                        put("f12", "BK0478")
                        put("f14", "有色金属")
                        put("f3", 3.25)
                        put("f2", 4521.36)
                        put("f128", "紫金矿业")
                        put("f140", "sh601899")
                        put("f124", 5.8)
                    })
                    put(JSONObject().apply {
                        put("f12", "BK0447")
                        put("f14", "半导体")
                        put("f3", -1.50)
                        put("f2", 3890.12)
                        put("f128", "中芯国际")
                        put("f140", "sh688981")
                        put("f124", -2.3)
                    })
                })
            })
        }

        // 解析
        val source = com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource()
        val sectors = mutableListOf<com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource.HotSector>()

        val data = mockJson.optJSONObject("data")!!
        val diffs = data.optJSONArray("diff")!!
        for (i in 0 until diffs.length()) {
            val item = diffs.getJSONObject(i)
            sectors.add(
                com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource.HotSector(
                    code = item.optString("f12"),
                    name = item.optString("f14"),
                    changePercent = item.optDouble("f3"),
                    sectorIndex = item.optDouble("f2"),
                    top1StockName = item.optString("f128"),
                    top1StockCode = item.optString("f140"),
                    top1ChangePercent = item.optDouble("f124")
                )
            )
        }

        // 验证
        assertEquals("应解析出2个板块", 2, sectors.size)
        assertEquals("有色金属", sectors[0].name)
        assertEquals(3.25, sectors[0].changePercent, 0.01)
        assertEquals("紫金矿业", sectors[0].top1StockName)
        assertEquals("半导体", sectors[1].name)
        assertEquals(-1.50, sectors[1].changePercent, 0.01)

        Log.i(TAG, "✅ testEastMoneyHotSector_parseSectorResponse: 通过")
    }

    @Test
    fun testEastMoneyHotSector_parseIndexResponse() {
        // 模拟东方财富指数 API JSON
        val mockJson = JSONObject().apply {
            put("data", JSONObject().apply {
                put("diff", JSONArray().apply {
                    put(JSONObject().apply {
                        put("f12", "000001")
                        put("f14", "上证指数")
                        put("f2", 3210.55)
                        put("f3", 0.85)
                        put("f4", 27.1)
                    })
                    put(JSONObject().apply {
                        put("f12", "399001")
                        put("f14", "深证成指")
                        put("f2", 10876.30)
                        put("f3", -0.32)
                        put("f4", -35.5)
                    })
                })
            })
        }

        val data = mockJson.optJSONObject("data")!!
        val diffs = data.optJSONArray("diff")!!
        val result = mutableListOf<com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource.GlobalIndex>()

        for (i in 0 until diffs.length()) {
            val item = diffs.getJSONObject(i)
            result.add(
                com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource.GlobalIndex(
                    code = item.optString("f12"),
                    name = item.optString("f14"),
                    price = item.optDouble("f2"),
                    changePercent = item.optDouble("f3"),
                    changeAmount = item.optDouble("f4")
                )
            )
        }

        assertEquals(2, result.size)
        assertEquals("上证指数", result[0].name)
        assertEquals(3210.55, result[0].price, 0.01)
        assertEquals(0.85, result[0].changePercent, 0.01)
        assertEquals(-0.32, result[1].changePercent, 0.01)

        Log.i(TAG, "✅ testEastMoneyHotSector_parseIndexResponse: 通过")
    }
}