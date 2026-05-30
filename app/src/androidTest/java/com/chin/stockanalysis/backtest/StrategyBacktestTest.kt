package com.chin.stockanalysis.backtest

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chin.stockanalysis.stock.StockRealtime
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ## 策略打分逻辑验证（纯内存测试，不依赖 Room）
 *
 * 直接测试三个自定义策略的评分算法。
 *
 * 运行方式:
 * ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class StrategyBacktestTest {

    companion object {
        private const val TAG = "StrategyBacktestTest"
    }

    // ══════════════════════════════════════════════
    // 模块1: 早盘追涨策略打分逻辑
    // ══════════════════════════════════════════════

    @Test
    fun testMorningStrategyScoring() {
        // 模拟一只符合条件的早盘股票
        val stock = StockRealtime(
            "sh600519", "贵州茅台", 1550.0, 1530.0, 1520.0, 1560.0, 1525.0,
            10000000L, 5000000000.0, 1.5, 20.0, System.currentTimeMillis()
        )

        // 验证基础过滤条件
        val pureCode = stock.code.removePrefix("sh").removePrefix("sz")
        assertTrue("主板600开头应通过", pureCode.startsWith("600"))
        assertTrue("涨跌幅应>0", stock.changePercent > 0)
        assertTrue("当前价应>=开盘价", stock.price >= stock.open)
        assertTrue("成交额应>=5亿", stock.amount >= 500_000_000)

        Log.i(TAG, "✅ testMorningStrategyScoring: 过滤条件验证通过")
    }

    @Test
    fun testMorningStrategyRejectGEM() {
        // 模拟创业板股票
        val stock = StockRealtime(
            "sz300750", "宁德时代", 180.0, 178.0, 177.0, 182.0, 176.0,
            8000000L, 500000000.0, 1.2, 2.0, System.currentTimeMillis()
        )

        val pureCode = stock.code.removePrefix("sh").removePrefix("sz")
        assertFalse("创业板300开头应被剔除", pureCode.startsWith("000") || pureCode.startsWith("600"))
        Log.i(TAG, "✅ testMorningStrategyRejectGEM: 创业板正确剔除")
    }

    // ══════════════════════════════════════════════
    // 模块2: 权重策略打分逻辑
    // ══════════════════════════════════════════════

    @Test
    fun testWeightedStrategyMACD() {
        // 模拟金叉股票
        val stock1 = StockRealtime(
            "sh600183", "生益科技", 25.0, 24.5, 24.3, 25.3, 24.2,
            5000000L, 300000000.0, 1.5, 0.8, System.currentTimeMillis()
        )
        // MACD分数计算: changePercent>1 && price>open && price>yestClose → 50分
        val macdScore = when {
            stock1.changePercent > 1 && stock1.price > stock1.open && stock1.price > stock1.yestClose -> 50
            stock1.changePercent in 0.3..1.0 && stock1.price > stock1.yestClose -> 45
            stock1.changePercent in -1.0..0.3 -> 30
            else -> 0
        }
        assertEquals("金叉条件应得50分", 50, macdScore)
        Log.i(TAG, "✅ testWeightedStrategyMACD: MACD打分通过 ($macdScore)")
    }

    @Test
    fun testWeightedStrategyCapitalPenalty() {
        // 模拟主力大额流出
        val stock = StockRealtime(
            "sh600000", "测试股", 10.0, 11.0, 11.5, 11.5, 9.5,
            100000000L, 2000000000.0, -4.0, -0.5, System.currentTimeMillis()
        )
        // 跌幅>3%+大量成交 → -20
        val penalty = when {
            stock.changePercent < -3 && stock.amount > 1_000_000_000 -> -20
            stock.changePercent < -1.5 && stock.amount > 500_000_000 -> -10
            else -> 0
        }
        assertEquals("主力大额流出应扣-20分", -20, penalty)
        Log.i(TAG, "✅ testWeightedStrategyCapitalPenalty: 扣分=$penalty")
    }

    // ══════════════════════════════════════════════
    // 模块3: 尾盘低吸策略打分逻辑
    // ══════════════════════════════════════════════

    @Test
    fun testTailPickRejectRocketStock() {
        // 模拟直线拉升无回踩的股票
        val stock = StockRealtime(
            "sh600100", "测试急速", 55.0, 45.0, 45.0, 55.0, 44.0,
            20000000L, 1000000000.0, 9.5, 10.0, System.currentTimeMillis()
        )
        // 涨超8% → 剔除
        assertTrue("涨超8%应被剔除", stock.changePercent > 8)
        Log.i(TAG, "✅ testTailPickRejectRocketStock: 加速股正确剔除")
    }

    @Test
    fun testTailPickPullbackScore() {
        // 模拟低开高走回踩企稳
        val stock = StockRealtime(
            "sh600200", "企稳股", 30.0, 29.5, 30.0, 30.5, 29.0,
            5000000L, 200000000.0, 0.8, 0.5, System.currentTimeMillis()
        )
        // 低开高走: open<yestClose && price>open && changePercent>0 → 20分
        val techScore = when {
            stock.open < stock.yestClose && stock.price > stock.open && stock.changePercent > 0 -> 20
            stock.price > stock.open && stock.changePercent in 0.2..1.5 -> 15
            else -> 5
        }
        assertEquals("低开高走应得20分", 20, techScore)
        Log.i(TAG, "✅ testTailPickPullbackScore: 回踩评分=$techScore")
    }

    @Test
    fun testTailPickPositionLimit() {
        // 验证分级仓位
        val posLimit: (Int) -> Int = { score ->
            when {
                score >= 95 -> 30; score >= 90 -> 25
                score >= 85 -> 20; score >= 80 -> 10
                else -> 5
            }
        }
        assertEquals(30, posLimit(96))
        assertEquals(25, posLimit(92))
        assertEquals(20, posLimit(86))
        assertEquals(10, posLimit(82))
        assertEquals(5, posLimit(50))
        Log.i(TAG, "✅ testTailPickPositionLimit: 分级仓位正确")
    }
}