package com.chin.stockanalysis.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.github.mikephil.charting.data.CandleData
import com.github.mikephil.charting.data.CandleEntry
import com.github.mikephil.charting.data.CandleDataSet
import android.graphics.Color
import com.chin.stockanalysis.databinding.FragmentStockBinding
import okhttp3.*
import org.json.JSONObject
import java.io.IOException

/**
 * 股票查询页面 - Fragment 版本
 * 迁移自原 MainActivity
 */
class StockTabFragment : Fragment() {

    private var _binding: FragmentStockBinding? = null
    private val binding get() = _binding!!

    private val stockCode = "sh600000"
    private val client = OkHttpClient()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStockBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        getStockData()
        runMaStrategy()
    }

    // 获取A股数据（新浪接口）
    private fun getStockData() {
        val url = "https://quotes.sina.com/stock/api/json.php?symbol=$stockCode&start=20250101&end=20251231"
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val json = response.body?.string() ?: return
                view?.let { drawChart(json) }
            }
        })
    }

    // 绘制K线图
    private fun drawChart(json: String) {
        try {
            val entries = ArrayList<CandleEntry>()
            val closePrices = mutableListOf<Double>()
            val volumes = mutableListOf<Double>()
            val obj = JSONObject(json)
            val data = obj.getJSONArray("data")

            for (i in 0 until data.length()) {
                val item = data.getJSONArray(i)
                val open = item.getDouble(1).toFloat()
                val high = item.getDouble(2).toFloat()
                val low = item.getDouble(3).toFloat()
                val close = item.getDouble(4).toFloat()
                entries.add(CandleEntry(i.toFloat(), high, low, open, close))
                closePrices.add(close.toDouble())

                val vol = if (item.length() > 5) item.getDouble(5) else 0.0
                volumes.add(vol)
            }

            val dataSet = CandleDataSet(entries, "K线")
            dataSet.setIncreasingColor(Color.GREEN)
            dataSet.setDecreasingColor(Color.RED)
            dataSet.neutralColor = Color.GRAY

            val candleData = CandleData(dataSet)
            binding.klineChart.data = candleData
            binding.klineChart.invalidate()

            runMacdStrategy(closePrices)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ==================== 量化策略 ====================

    // ---- 策略1：5/10日均线金叉 ----
    private fun runMaStrategy() {
        val closePrices = listOf(10.2, 10.1, 10.5, 10.8, 11.2, 11.0, 10.9, 11.5, 12.0, 12.3)
        val ma5 = closePrices.takeLast(5).average()
        val ma10 = closePrices.takeLast(10).average()

        val maResult = if (ma5 > ma10) "✅ 金叉 → 买入信号" else "❌ 死叉 → 卖出信号"

        binding.tvStrategy.text = """
            [均线策略] 5/10日均线
            MA5：$ma5    MA10：$ma10
            结果：$maResult
        """.trimIndent()
    }

    // ---- 策略2：MACD金叉 ----
    private fun runMacdStrategy(closePrices: List<Double>) {
        if (closePrices.size < 35) {
            binding.tvStrategy.text = "MACD：数据不足（需要≥35个交易日）"
            return
        }

        val ema12 = ema(closePrices, 12)
        val ema26 = ema(closePrices, 26)
        val dif = ema12.zip(ema26) { e12, e26 -> e12 - e26 }
        val dea = ema(dif, 9)

        val difPrev = dif[dif.size - 2]
        val difCurr = dif.last()
        val deaPrev = dea[dea.size - 2]
        val deaCurr = dea.last()

        val macdHist = 2.0 * (difCurr - deaCurr)

        val isGoldenCross = difPrev <= deaPrev && difCurr > deaCurr
        val macdResult = when {
            isGoldenCross -> "✅ MACD金叉 → 买入信号"
            difCurr > deaCurr -> "📌 DIF在DEA上方（多头）"
            else -> "❌ MACD死叉 / 空头"
        }

        binding.tvStrategy.text = """
            [MACD策略] DIF/DEA
            DIF：${"%.4f".format(difCurr)}
            DEA：${"%.4f".format(deaCurr)}
            柱：${"%.4f".format(macdHist)}
            结果：$macdResult
        """.trimIndent()
    }

    // ==================== 辅助函数 ====================

    // EMA 指数移动平均
    private fun ema(values: List<Double>, period: Int): List<Double> {
        val result = mutableListOf<Double>()
        val multiplier = 2.0 / (period + 1)
        var emaPrev = values.take(period).average()
        result.add(emaPrev)

        for (i in period until values.size) {
            val emaCurr = (values[i] - emaPrev) * multiplier + emaPrev
            result.add(emaCurr)
            emaPrev = emaCurr
        }
        return result
    }

    // SMA 简单移动平均
    private fun sma(values: List<Double>, period: Int): Double {
        return values.takeLast(period).average()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

