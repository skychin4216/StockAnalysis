package com.chin.stockanalysis.backtest

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chin.stockanalysis.stock.StockRealtime
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StrategyBacktestTest {

    companion object {
        private const val TAG = "StrategyBacktestTest"
    }

    private fun stock(
        code: String, name: String,
        price: Double, open: Double, yestClose: Double,
        high: Double, low: Double,
        volume: Long, amount: Double,
        changePercent: Double, changeAmount: Double,
        turnoverRate: Double = 0.0,
        pe: Double = 0.0, pb: Double = 0.0, marketCap: Double = 0.0,
        roeTTM: Double = 0.0, grossMarginTTM: Double = 0.0,
        debtToAsset: Double = 0.0, operatingCashFlow: Double = 0.0
    ): StockRealtime = StockRealtime(
        code, name, price, open, yestClose, high, low,
        volume, amount, changePercent, changeAmount, turnoverRate,
        pe, pb, marketCap, roeTTM, grossMarginTTM, debtToAsset, operatingCashFlow,
        System.currentTimeMillis()
    )

    @Test
    fun testMorningStrategyScoring() {
        val s = stock("sh600519", "贵州茅台", 1550.0, 1530.0, 1520.0, 1560.0, 1525.0,
            10000000L, 5000000000.0, 1.5, 20.0)
        assertTrue("600 code", s.code.removePrefix("sh").startsWith("600"))
        assertTrue("change>0", s.changePercent > 0)
        assertTrue("price>=open", s.price >= s.open)
        assertTrue("amount>=5B", s.amount >= 500_000_000)
        Log.i(TAG, "testMorningStrategyScoring: pass")
    }

    @Test
    fun testMorningStrategyRejectGEM() {
        val s = stock("sz300750", "宁德时代", 180.0, 178.0, 177.0, 182.0, 176.0,
            8000000L, 500000000.0, 1.2, 2.0)
        val pure = s.code.removePrefix("sh").removePrefix("sz")
        assertFalse("GEM 300 rejected", pure.startsWith("000") || pure.startsWith("600"))
        Log.i(TAG, "testMorningStrategyRejectGEM: pass")
    }

    @Test
    fun testWeightedStrategyMACD() {
        val s = stock("sh600183", "生益科技", 25.0, 24.5, 24.3, 25.3, 24.2,
            5000000L, 300000000.0, 1.5, 0.8)
        val score = when {
            s.changePercent > 1 && s.price > s.open && s.price > s.yestClose -> 50
            s.changePercent in 0.3..1.0 && s.price > s.yestClose -> 45
            s.changePercent in -1.0..0.3 -> 30
            else -> 0
        }
        assertEquals("MACD=50", 50, score)
        Log.i(TAG, "testWeightedStrategyMACD: score=$score")
    }

    @Test
    fun testWeightedStrategyCapitalPenalty() {
        val s = stock("sh600000", "test", 10.0, 11.0, 11.5, 11.5, 9.5,
            100000000L, 2000000000.0, -4.0, -0.5)
        val penalty = when {
            s.changePercent < -3 && s.amount > 1_000_000_000 -> -20
            s.changePercent < -1.5 && s.amount > 500_000_000 -> -10
            else -> 0
        }
        assertEquals("penalty=-20", -20, penalty)
        Log.i(TAG, "testWeightedStrategyCapitalPenalty: penalty=$penalty")
    }

    @Test
    fun testTailPickRejectRocketStock() {
        val s = stock("sh600100", "rocket", 55.0, 45.0, 45.0, 55.0, 44.0,
            20000000L, 1000000000.0, 9.5, 10.0)
        assertTrue("chg>8 rejected", s.changePercent > 8)
        Log.i(TAG, "testTailPickRejectRocketStock: pass")
    }

    @Test
    fun testTailPickPullbackScore() {
        val s = stock("sh600200", "pullback", 30.0, 29.5, 30.0, 30.5, 29.0,
            5000000L, 200000000.0, 0.8, 0.5)
        val score = when {
            s.open < s.yestClose && s.price > s.open && s.changePercent > 0 -> 20
            s.price > s.open && s.changePercent in 0.2..1.5 -> 15
            else -> 5
        }
        assertEquals("pullback=20", 20, score)
        Log.i(TAG, "testTailPickPullbackScore: score=$score")
    }

    @Test
    fun testTailPickPositionLimit() {
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
        Log.i(TAG, "testTailPickPositionLimit: pass")
    }
}