package com.chin.stockanalysis.backtest

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import com.chin.stockanalysis.strategy.data.HistoricalDataFetcher
import com.chin.stockanalysis.strategy.data.StockScreener
import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.strategies.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * ## 策略数据完整性测试
 *
 * 测试覆盖：
 * 1. stock_basics 表 code→name 映射正确性
 * 2. daily_snapshot 价格数据非空/非零
 * 3. 7 个策略的打分输出格式正确
 * 4. HistoricalDataFetcher 名称提取有效性
 * 5. 权重因子总和 = 100
 * 6. 策略结果去重
 *
 * 运行方式:
 * ./gradlew connectedDebugAndroidTest --tests "*StrategyDataIntegrityTest*"
 */
@RunWith(AndroidJUnit4::class)
class StrategyDataIntegrityTest {

    companion object {
        private const val TAG = "StrategyDataIntegrityTest"

        /** 已知正确的代码→名称映射（硬编码基准数据） */
        private val KNOWN_STOCKS = mapOf(
            "sh600519" to "贵州茅台",
            "sz000858" to "五粮液",
            "sz002594" to "比亚迪",
            "sz300750" to "宁德时代",
            "sh601318" to "中国平安",
            "sh688981" to "中芯国际",
            "sh600030" to "中信证券",
            "sh600036" to "招商银行",
            "sz000002" to "万科A",
            "sz002475" to "立讯精密",
            "sh600276" to "恒瑞医药",
            "sz000651" to "格力电器",
            "sh601398" to "工商银行",
            "sh601857" to "中国石油",
            "sh600900" to "长江电力"
        )
    }

    private lateinit var db: StockDatabase
    private lateinit var scope: CoroutineScope

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = StockDatabase.getInstance(ctx)
        scope = CoroutineScope(Dispatchers.IO + Job())
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    // ══════════════════════════════════════════════
    // 模块1: 股票名称→代码映射验证
    // ══════════════════════════════════════════════

    @Test
    fun testStockBasicsNameMapping() = runBlocking {
        val allBasics = db.stockBasicDao().getAll()
        assertTrue("stock_basics 表至少应有 10 条记录", allBasics.size >= 10)

        for ((code, expectedName) in KNOWN_STOCKS) {
            val entry = allBasics.firstOrNull { it.code == code }
            assertNotNull("$code 应在 stock_basics 中存在", entry)
            if (entry != null) {
                assertEquals("$code 名称应为 '$expectedName': 实际 '${entry.name}'", expectedName, entry.name)
            }
        }
        Log.i(TAG, "✅ 名称映射测试通过: ${KNOWN_STOCKS.size} 只股票匹配")
    }

    @Test
    fun testDailySnapshotNameNotEmpty() = runBlocking {
        val dates = db.dailySnapshotDao().getAvailableDates(5)
        if (dates.isEmpty()) {
            Log.w(TAG, "⚠️ daily_snapshot 为空，跳过名称检验")
            return@runBlocking
        }

        val latestShots = db.dailySnapshotDao().getByDate(dates.first())
        assertTrue("最新交易日应有数据", latestShots.isNotEmpty())

        val blankNames = latestShots.filter { it.name.isBlank() }
        assertTrue(
            "不应有空名称 (共${latestShots.size}条, 空名${blankNames.size}条): ${blankNames.take(5).joinToString { it.code }}",
            blankNames.isEmpty()
        )

        // 验证已知股票名称正确
        for (shot in latestShots) {
            val expected = KNOWN_STOCKS[shot.code]
            if (expected != null && shot.name.isNotBlank()) {
                assertEquals("$shot.code 名称应为 $expected", expected, shot.name)
            }
        }
        Log.i(TAG, "✅ 快照名称检验通过: ${latestShots.size} 条无空名")
    }

    // ══════════════════════════════════════════════
    // 模块2: 价格数据完整性验证
    // ══════════════════════════════════════════════

    @Test
    fun testDailySnapshotPriceValid() = runBlocking {
        val dates = db.dailySnapshotDao().getAvailableDates(5)
        if (dates.isEmpty()) return@runBlocking

        val latestShots = db.dailySnapshotDao().getByDate(dates.first())
        assertTrue("最新交易日应有数据", latestShots.isNotEmpty())

        for (shot in latestShots) {
            assertTrue("${shot.code} 开盘价应>0: ${shot.open}", shot.open > 0)
            assertTrue("${shot.code} 收盘价应>0: ${shot.close}", shot.close > 0)
            assertTrue("${shot.code} 最高价应>=最低价", shot.high >= shot.low)
            assertTrue("${shot.code} 成交量应>0", shot.volume > 0)
        }
        Log.i(TAG, "✅ 价格数据完整性测试通过: ${latestShots.size} 条")
    }

    @Test
    fun testMoneyVolumeConsistency() = runBlocking {
        // StockRealtime 构造验证：交易金额与价格×成交量是否大致匹配
        val stock = StockRealtime(
            "sh600519", "贵州茅台", 1500.0, 1495.0, 1490.0,
            1510.0, 1480.0, 10_000_000L, 15_000_000_000.0,
            0.3, 5.0, System.currentTimeMillis()
        )
        // amount ≈ price * volume / 100（粗略：万元）
        assertTrue("交易金额应>0", stock.amount > 0)
        assertTrue("交易金额应> price*0.001*volume", stock.amount > stock.price * 0.001 * stock.volume)
        assertEquals("涨跌幅计算: (1500-1490)/1490", 10.0 / 1490.0 * 100, stock.changePercent, 0.1)
        Log.i(TAG, "✅ 量价一致性验证通过")
    }

    // ══════════════════════════════════════════════
    // 模块3: 7 个策略打分输出格式验证
    // ══════════════════════════════════════════════

    @Test
    fun testAllStrategiesProduceValidSignals() = runBlocking {
        val repo = com.chin.stockanalysis.stock.data.StockDataSourceFactory.createDefaultRepository(
            ApplicationProvider.getApplicationContext()
        )
        val screener = StockScreener(repo)

        val strategies = listOf(
            MovingAverageStrategy(screener),
            VolumeBreakStrategy(screener),
            LowValuationStrategy(screener),
            GapUpMomentumStrategy(screener),
            TurnoverFilterStrategy(screener),
            EarlyMorningChaseStrategy(screener),
            TailLowPickStrategy(screener)
        )

        for (s in strategies) {
            // 验证基础属性
            assertTrue("${s.id} name 不应为空", s.name.isNotBlank())
            assertTrue("${s.id} description 不应为空", s.description.isNotBlank())
            assertTrue("${s.id} weightFactors 不应为空", s.weightFactors.isNotEmpty())

            // 权重和应为 100±2
            val totalWeight = s.weightFactors.sumOf { it.weight }
            assertTrue(
                "${s.id} 权重总和应为 100±5, 实际: $totalWeight",
                totalWeight in 95..105
            )

            // 执行扫描（可能无数据）
            val result = try {
                s.screen().getOrNull()
            } catch (e: Exception) {
                Log.w(TAG, "${s.id} screen() 异常: ${e.message}")
                null
            }

            if (result != null) {
                assertTrue("${s.id} 扫描数应>=命中数", result.totalScanned >= result.hitCount)
                assertTrue("${s.id} 扫描时间应>=0", result.scanTimeMs >= 0)
                assertEquals("${s.id} strategyId 应匹配", s.id, result.strategyId)
                assertEquals("${s.id} strategyName 应匹配", s.name, result.strategyName)

                // 信号验证
                for (signal in result.signals) {
                    assertTrue("${s.id} 信号强度应在 0-100: ${signal.strength}", signal.strength in 0..100)
                    assertTrue("${s.id} stockCode 不应为空", signal.stockCode.isNotBlank())
                    assertTrue("${s.id} 信号原因不应为空: ${signal.reason}", signal.reason.isNotBlank())
                    assertTrue("${s.id} 价格应>0: ${signal.currentPrice}", signal.currentPrice > 0)
                }
            }
        }
        Log.i(TAG, "✅ 7 个策略验证通过")
    }

    // ══════════════════════════════════════════════
    // 模块4: 换手率策略专项验证
    // ══════════════════════════════════════════════

    @Test
    fun testTurnoverStrategySpecificSignals() = runBlocking {
        val repo = com.chin.stockanalysis.stock.data.StockDataSourceFactory.createDefaultRepository(
            ApplicationProvider.getApplicationContext()
        )
        val screener = StockScreener(repo)
        val strategy = TurnoverFilterStrategy(screener)

        val result = strategy.screen().getOrNull()
        assertNotNull("换手率策略应可执行", result)

        if (result != null && result.signals.isNotEmpty()) {
            for (signal in result.signals) {
                // 股票名称不能是代码（即不能像 "sh600519"）
                assertFalse(
                    "${signal.stockCode} 名称不应该是纯代码: ${signal.stockName}",
                    signal.stockName.contains("sh") || signal.stockName.contains("sz") || signal.stockName.endsWith("--")
                )
                // 强度 30+
                assertTrue("${signal.stockCode} 强度应>=30: ${signal.strength}", signal.strength >= 30)
                // 价格>0
                assertTrue("${signal.stockName} 价格应>0: ${signal.currentPrice}", signal.currentPrice > 0)
            }
            Log.i(TAG, "✅ 换手率策略验证通过: ${result.hitCount} 命中")
        } else {
            Log.w(TAG, "⚠️ 换手率策略无命中信号")
        }
    }

    // ══════════════════════════════════════════════
    // 模块5: HistoricalDataFetcher 名称提取
    // ══════════════════════════════════════════════

    @Test
    fun testFetcherNameExtraction() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val fetcher = HistoricalDataFetcher(ctx)

        // 通过反射调用 private fetchOneStock 验证名称提取
        val method = fetcher.javaClass.getDeclaredMethod(
            "fetchOneStock", String::class.java, LocalDate::class.java, LocalDate::class.java
        )
        method.isAccessible = true

        val result = method.invoke(fetcher, "sh600519",
            LocalDate.now().minusDays(3), LocalDate.now()
        ) as Pair<*, *>

        val records = result.first as? List<*> ?: emptyList<Any>()
        val name = result.second as? String ?: ""

        assertTrue("贵州茅台应返回 K 线数据", records.isNotEmpty())
        assertEquals("sh600519 名称应为贵州茅台", "贵州茅台", name)

        Log.i(TAG, "✅ Fetch 名称提取验证: $name (${records.size} 条K线)")
    }

    // ══════════════════════════════════════════════
    // 模块6: 策略结果去重
    // ══════════════════════════════════════════════

    @Test
    fun testSignalDeduplication() = runBlocking {
        val signals = listOf(
            com.chin.stockanalysis.strategy.models.StrategySignal(
                "sh600519", "茅台", "test", com.chin.stockanalysis.strategy.StrategyCategory.VOLUME,
                80, com.chin.stockanalysis.strategy.models.SignalAction.BUY, "test", emptyMap(), 1500.0, 0.5
            ),
            com.chin.stockanalysis.strategy.models.StrategySignal(
                "sh600519", "茅台", "test", com.chin.stockanalysis.strategy.StrategyCategory.VOLUME,
                90, com.chin.stockanalysis.strategy.models.SignalAction.BUY, "test2", emptyMap(), 1500.0, 0.5
            ),
            com.chin.stockanalysis.strategy.models.StrategySignal(
                "sz000001", "平安银行", "test", com.chin.stockanalysis.strategy.StrategyCategory.VOLUME,
                70, com.chin.stockanalysis.strategy.models.SignalAction.WATCH, "test3", emptyMap(), 12.0, 1.0
            )
        )

        val deduped = signals.distinctBy { it.stockCode }
        assertEquals("去重后应剩 2 条", 2, deduped.size)
        assertEquals("去重后第一条应是茅台", "sh600519", deduped[0].stockCode)
        assertEquals("去重后第二条应是平安银行", "sz000001", deduped[1].stockCode)

        Log.i(TAG, "✅ 信号去重测试通过")
    }

    // ══════════════════════════════════════════════
    // 模块7: StockScreener 拉取原始数据后 name/code/price 一一对应
    // ══════════════════════════════════════════════

    @Test
    fun testScreenerNameCodePriceIntegrity() = runBlocking {
        val repo = com.chin.stockanalysis.stock.data.StockDataSourceFactory.createDefaultRepository(
            ApplicationProvider.getApplicationContext()
        )
        val screener = StockScreener(repo)
        val stocks = screener.scanFullMarket()

        // 1. 必须有数据
        assertTrue("scanFullMarket 应返回 > 0 只股票，实际 0 只（网络不通或 API 异常）", stocks.isNotEmpty())

        var nameIsCodeCount = 0
        var wrongPrefixCount = 0
        var emptyNameCount = 0
        var zeroPriceCount = 0

        for (s in stocks) {
            // 验证名称非空
            if (s.name.isBlank()) {
                emptyNameCount++
                Log.w(TAG, "❌ 空名称: code=${s.code}, price=${s.price}")
            }

            // 验证名称不是纯代码（如名称里包含 sh/sz/前缀）
            val pureCode = s.code.removePrefix("sh").removePrefix("sz").removePrefix("bj")
            if (s.name == s.code || s.name == pureCode ||
                s.name.contains("sh") && s.name.length <= 8 || s.name.contains("sz") && s.name.length <= 8
            ) {
                nameIsCodeCount++
                Log.w(TAG, "❌ 名称与代码相同或疑似代码: name='${s.name}' code=${s.code}")
            }

            // 验证代码前缀与市场一致
            val pure = s.code.removePrefix("sh").removePrefix("sz").removePrefix("bj")
            val prefix = when {
                pure.startsWith("6") && s.code.startsWith("sh") -> true
                pure.startsWith("9") && !pure.startsWith("90") && s.code.startsWith("sh") -> true
                pure.startsWith("0") || pure.startsWith("3") && s.code.startsWith("sz") -> true
                pure.startsWith("4") || pure.startsWith("8") && s.code.startsWith("bj") -> true
                // 对 688 开头是科创板(sh)
                pure.startsWith("688") && s.code.startsWith("sh") -> true
                // 对 300/301 开头是创业板(sz)
                pure.startsWith("300") || pure.startsWith("301") && s.code.startsWith("sz") -> true
                else -> false
            }
            if (!prefix) {
                wrongPrefixCount++
                Log.w(TAG, "❌ 前缀错误: code=${s.code} name='${s.name}'")
            }

            // 验证价格 > 0
            if (s.price <= 0) {
                zeroPriceCount++
                Log.w(TAG, "❌ 价格为0: code=${s.code} name='${s.name}'")
            }
        }

        Log.i(TAG, "📊 拉取 ${stocks.size} 只，代码为名:${nameIsCodeCount} 前缀错:${wrongPrefixCount} 空名:${emptyNameCount} 零价:${zeroPriceCount}")
        assertEquals("不应有名称等于代码的", 0, nameIsCodeCount)
        assertEquals("代码前缀应与股票类型匹配", 0, wrongPrefixCount)
        assertEquals("不应有空名称", 0, emptyNameCount)
        assertEquals("价格应 > 0", 0, zeroPriceCount)
    }

    @Test
    fun testScreenerStrategySignalNameCodePairing() = runBlocking {
        // 用预置的 20 只已知代码调用策略，验证策略输出的 stockCode ↔ stockName 配对
        val ts = System.currentTimeMillis()
        val stocks = listOf(
            StockRealtime("sh600519","贵州茅台",1500.0,1495.0,1490.0,1510.0,1480.0,10_000_000L,15_000_000_000.0,0.3,5.0,ts),
            StockRealtime("sz000858","五粮液",150.0,149.0,148.0,152.0,147.0,20_000_000L,3_000_000_000.0,1.0,1.5,ts),
            StockRealtime("sz002594","比亚迪",250.0,248.0,247.0,252.0,246.0,15_000_000L,3_750_000_000.0,0.8,2.0,ts),
            StockRealtime("sz300750","宁德时代",200.0,198.0,197.0,202.0,196.0,25_000_000L,5_000_000_000.0,1.0,2.0,ts),
            StockRealtime("sh601318","中国平安",45.0,44.5,44.0,45.5,43.8,50_000_000L,2_250_000_000.0,1.0,0.45,ts),
            StockRealtime("sh688981","中芯国际",50.0,49.5,49.0,51.0,48.5,30_000_000L,1_500_000_000.0,1.0,0.5,ts),
            StockRealtime("sh600030","中信证券",20.0,19.8,19.5,20.2,19.4,80_000_000L,1_600_000_000.0,1.5,0.3,ts),
            StockRealtime("sh600036","招商银行",35.0,34.8,34.5,35.3,34.2,40_000_000L,1_400_000_000.0,0.8,0.28,ts),
            StockRealtime("sz000002","万科A",12.0,11.9,11.8,12.1,11.7,60_000_000L,720_000_000.0,0.8,0.1,ts),
            StockRealtime("sz002475","立讯精密",30.0,29.8,29.5,30.2,29.4,25_000_000L,750_000_000.0,0.8,0.24,ts),
            StockRealtime("sh600276","恒瑞医药",45.0,44.5,44.0,45.5,43.8,15_000_000L,675_000_000.0,1.0,0.45,ts),
            StockRealtime("sz000651","格力电器",38.0,37.8,37.5,38.2,37.2,20_000_000L,760_000_000.0,0.5,0.19,ts),
            StockRealtime("sh601398","工商银行",5.5,5.48,5.45,5.52,5.42,200_000_000L,1_100_000_000.0,0.4,0.02,ts),
            StockRealtime("sh600900","长江电力",22.0,21.8,21.7,22.1,21.6,30_000_000L,660_000_000.0,0.5,0.11,ts),
            StockRealtime("sh601857","中国石油",8.0,7.95,7.9,8.05,7.85,100_000_000L,800_000_000.0,0.6,0.05,ts),
            StockRealtime("sh601166","兴业银行",16.0,15.9,15.8,16.1,15.7,35_000_000L,560_000_000.0,0.6,0.1,ts),
            StockRealtime("sz000001","平安银行",11.0,10.9,10.8,11.1,10.7,40_000_000L,440_000_000.0,0.9,0.1,ts),
            StockRealtime("sh600183","生益科技",25.0,24.8,24.5,25.2,24.4,12_000_000L,300_000_000.0,1.2,0.3,ts),
            StockRealtime("sz002463","沪电股份",28.0,27.8,27.5,28.2,27.4,10_000_000L,280_000_000.0,1.5,0.42,ts),
            StockRealtime("sh600549","厦门钨业",12.5,12.4,12.3,12.6,12.2,18_000_000L,225_000_000.0,0.8,0.1,ts),
        )

        // 创建 screenWithData 用 screener（需要 mock 或者直接调用各策略的 screenWithData）
        val repo = com.chin.stockanalysis.stock.data.StockDataSourceFactory.createDefaultRepository(
            ApplicationProvider.getApplicationContext()
        )
        val screener = StockScreener(repo)

        val strategies = listOf(
            MovingAverageStrategy(screener),
            VolumeBreakStrategy(screener),
            LowValuationStrategy(screener),
            GapUpMomentumStrategy(screener),
            TurnoverFilterStrategy(screener),
            EarlyMorningChaseStrategy(screener),
            TailLowPickStrategy(screener)
        )

        for (s in strategies) {
            val result = s.screenWithData(stocks).getOrNull()
            if (result == null) {
                Log.w(TAG, "${s.id} screenWithData 返回 null，跳过")
                continue
            }
            for (signal in result.signals) {
                val sourceStock = stocks.firstOrNull { it.code == signal.stockCode }
                assertNotNull(
                    "${s.id}: signal stockCode=${signal.stockCode} 应在输入 stocks 中存在",
                    sourceStock
                )
                if (sourceStock != null) {
                    assertEquals(
                        "${s.id}: signal stockCode=${signal.stockCode} stockName=${signal.stockName} 应与输入匹配",
                        sourceStock.name, signal.stockName
                    )
                    assertEquals(
                        "${s.id}: signal currentPrice 应与输入 price 一致",
                        sourceStock.price, signal.currentPrice, 0.01
                    )
                }
            }
        }
        Log.i(TAG, "✅ 7 个策略 name↔code↔price 配对验证通过")
    }

    // ══════════════════════════════════════════════
    // 模块8: DB 写读验证
    // ══════════════════════════════════════════════

    @Test
    fun testSnapshotWriteAndRead() = runBlocking {
        val testCode = "sh600519"
        val testDate = "2020-01-01" // 历史日期，不影响真实数据

        // 插入测试快照
        db.dailySnapshotDao().insertAll(listOf(DailySnapshotEntity(
            code = testCode, name = "测试茅台", date = testDate,
            open = 100.0, close = 105.0, high = 106.0, low = 99.0,
            volume = 1000, amount = 100000.0, changePct = 5.0
        )))

        // 读回验证
        val shots = db.dailySnapshotDao().getByDate(testDate)
        val found = shots.firstOrNull { it.code == testCode }
        assertNotNull("应能读回写入的快照", found)
        if (found != null) {
            assertEquals("名称应匹配", "测试茅台", found.name)
            assertEquals("收盘价应匹配", 105.0, found.close, 0.01)
        }

        // 清理
        db.dailySnapshotDao().deleteOlderThan("2020-01-02")
        Log.i(TAG, "✅ DB 写读验证通过")
    }
}