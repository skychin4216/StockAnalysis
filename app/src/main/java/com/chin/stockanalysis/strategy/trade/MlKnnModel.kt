package com.chin.stockanalysis.strategy.trade

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.strategy.topology.pipelines.StockCheckPipeline
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * ## 长线 KNN 概率模型（ml_prob 伪因子）
 *
 * 模型由 PC 端 smalltools/_export_ml_model.py 训练并导出到 assets/ml_knn_long.json：
 * - 特征：Kotlin 端口径（_kotlin_feats.py，与 StockCheckPipeline 同口径），10 维
 * - 标签：未来 30 日收益 > 0
 * - 样本：长线信号（_records/selected_*.json）最近 2 年，z-score 标准化
 * - 模型：KNeighborsClassifier(k=5, weights=distance, metric=euclidean)
 *
 * 推理与 sklearn 完全一致：特征 z-score 缩放 → 欧氏距离 → k 近邻 → 距离加权投票
 * 输出正类（未来 30 日上涨）概率，作为 IC 排序伪因子 ml_prob。
 */
object MlKnnModel {
    private const val TAG = "MlKnnModel"
    private const val ASSET_FILE = "ml_knn_long.json"
    private const val ZERO_DIST_WEIGHT = 1e10  // sklearn 对 dist=0 的邻居权重（1/0 的替代）

    /** 特征名 → (mean, std)，与导出文件 scale 一致 */
    data class Model(
        val k: Int,
        val feats: List<String>,
        val scale: Map<String, Pair<Double, Double>>,
        val samples: List<Pair<DoubleArray, Int>>,  // (标准化特征, 标签)
        val nTrain: Int,
        val nVal: Int,
        val valAuc: Double,
        val valAcc: Double
    )

    @Volatile private var model: Model? = null
    @Volatile private var loadFailed = false

    private fun load(context: Context): Model? {
        model?.let { return it }
        if (loadFailed) return null
        synchronized(this) {
            model?.let { return it }
            if (loadFailed) return null
            try {
                val text = context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
                val root = JSONObject(text)
                val k = root.optInt("k", 5)
                val feats = (0 until root.getJSONArray("feats").length())
                    .map { root.getJSONArray("feats").getString(it) }
                val scaleObj = root.getJSONObject("scale")
                val scale = feats.associateWith { f ->
                    val a = scaleObj.getJSONArray(f)
                    a.getDouble(0) to a.getDouble(1)
                }
                val raw = root.getJSONArray("samples")
                val samples = (0 until raw.length()).map { i ->
                    val arr = raw.getJSONArray(i)
                    val x = DoubleArray(feats.size) { j -> arr.getDouble(j) }
                    val label = arr.getInt(feats.size)
                    x to label
                }
                model = Model(
                    k = k, feats = feats, scale = scale, samples = samples,
                    nTrain = root.optInt("n_train", 0),
                    nVal = root.optInt("n_val", 0),
                    valAuc = root.optJSONObject("val")?.optDouble("auc", 0.0) ?: 0.0,
                    valAcc = root.optJSONObject("val")?.optDouble("acc", 0.0) ?: 0.0
                )
                Log.i(TAG, "模型加载: k=$k, 支撑样本=${samples.size}, valAUC=${model?.valAuc}")
            } catch (e: Exception) {
                loadFailed = true
                Log.w(TAG, "模型加载失败: ${e.message}")
            }
            return model
        }
    }

    /** 方向枚举名 → DIR_CODE 数值（与 PC DIR_CODE 一致） */
    fun directionCode(dir: String): Double = when (dir) {
        "DOWNTREND" -> -2.0
        "OSCILLATION" -> 0.0
        "ACCUMULATION" -> 2.0
        "UPTREND" -> 3.0
        "BREAKOUT" -> 4.0
        else -> 0.0
    }

    /** 由 StockCheckResult 构造特征 map（Kotlin 端口径） */
    fun featuresOf(r: StockCheckPipeline.StockCheckResult): Map<String, Double> = mapOf(
        "convergenceDegree" to r.convergenceDegree,
        "volumeRatio" to r.volumeRatio,
        "drawdownPct" to r.drawdownPct,
        "convergenceDays" to r.convergenceDays.toDouble(),
        "changePct" to r.changePct,
        "turnoverRate" to r.turnoverRate,
        "momentum5" to r.momentum5,
        "ma60Bias" to r.ma60Bias,
        "ma250Bias" to r.ma250Bias,
        "direction" to directionCode(r.direction)
    )

    /**
     * 长线信号未来 30 日上涨概率（0..1）。
     * 模型未加载或特征缺失（NaN）→ null，调用方按 0.5 中性值处理。
     */
    fun probability(context: Context, r: StockCheckPipeline.StockCheckResult): Double? {
        val m = load(context) ?: return null
        val feats = featuresOf(r)
        val x = DoubleArray(m.feats.size)
        for (i in m.feats.indices) {
            val v = feats[m.feats[i]] ?: return null
            if (v.isNaN()) return null
            val (mean, std) = m.scale[m.feats[i]] ?: return null
            x[i] = (v - mean) / std
        }
        // 欧氏距离（全部支撑样本）
        val n = m.samples.size
        val dists = DoubleArray(n)
        for (i in 0 until n) {
            val s = m.samples[i].first
            var sum = 0.0
            for (j in x.indices) {
                val d = x[j] - s[j]
                sum += d * d
            }
            dists[i] = sqrt(sum)
        }
        // 取 k 个最近邻，距离加权投票（sklearn weights="distance"）
        val k = m.k.coerceAtMost(n)
        val idx = (0 until n).sortedBy { dists[it] }.take(k)
        var posW = 0.0
        var totW = 0.0
        for (i in idx) {
            val w = if (dists[i] == 0.0) ZERO_DIST_WEIGHT else 1.0 / dists[i]
            if (m.samples[i].second == 1) posW += w
            totW += w
        }
        return if (totW > 0) posW / totW else 0.5
    }
}
