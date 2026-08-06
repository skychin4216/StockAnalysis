package com.chin.stockanalysis.strategy.topology.nodes

import android.util.Log
import com.chin.stockanalysis.strategy.data.HistoricalDataFetcher
import com.chin.stockanalysis.strategy.topology.core.BaseNode
import com.chin.stockanalysis.strategy.topology.core.NodeType
import com.chin.stockanalysis.strategy.topology.core.PipelineContext
import com.chin.stockanalysis.strategy.topology.core.PipelineNode
import com.chin.stockanalysis.stock.database.StockDatabase

/**
 * ## 數據導入檢查節點 (DataImportNode)
 *
 * 補齊 Hardcode 路徑的「首次數據導入」邏輯：
 * 如果 DB 中 daily_snapshot 數量不足（< minSnapshots），
 * 則阻塞拉取歷史 K 線數據，確保下游策略有足夠數據計算。
 *
 * 正常情況下（數據已充足）此節點 < 5ms 完成。
 * 僅首次使用或數據被清空後觸發實際拉取（可能耗時數分鐘）。
 *
 * @property days 拉取天數（默認 60）
 * @property minSnapshots 最低快照數量閾值（默認 100）
 */
class DataImportNode(
    private val days: Int = 60,
    private val minSnapshots: Int = 100
) : BaseNode<Any, Int>("data_import", "數據導入檢查", NodeType.DATA_SOURCE) {

    companion object {
        private const val TAG = "DataImportNode"
    }

    override suspend fun execute(context: PipelineContext, input: Any): Int {
        val db = StockDatabase.getInstance(context.androidContext)
        val dao = db.dailySnapshotDao()

        // 快速檢查：數據充足則直接返回
        val count = try {
            dao.count()
        } catch (e: Exception) {
            Log.w(TAG, "getCount 失敗: ${e.message}")
            0
        }

        if (count >= minSnapshots) {
            context.log(nodeId, "$nodeName: 數據充足($count 條)，跳過導入")
            return count
        }

        // 數據不足，觸發歷史數據拉取
        context.log(nodeId, "📥 $nodeName: 數據不足($count/$minSnapshots)，開始拉取 ${days} 天歷史數據...")

        return try {
            val fetcher = HistoricalDataFetcher(context.androidContext)
            fetcher.fetchAllHistoricalData(days)

            val newCount = dao.count()
            context.log(nodeId, "📤 $nodeName: 導入完成，當前 $newCount 條快照")
            newCount
        } catch (e: Exception) {
            Log.e(TAG, "數據導入失敗: ${e.message}", e)
            context.log(nodeId, "⚠ $nodeName: 導入失敗(${e.message})，使用現有數據繼續")
            context.recordError(nodeId, "數據導入失敗: ${e.message}")
            count  // 返回原有數量，不阻斷 pipeline
        }
    }
}
