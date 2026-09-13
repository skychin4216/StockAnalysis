package com.chin.stockanalysis.strategy.topology.nodes

import android.util.Log
import com.chin.stockanalysis.strategy.data.HistoricalDataFetcher
import com.chin.stockanalysis.strategy.topology.core.BaseNode
import com.chin.stockanalysis.strategy.topology.core.NodeType
import com.chin.stockanalysis.strategy.topology.core.PipelineContext
import com.chin.stockanalysis.strategy.topology.core.PipelineNode
import com.chin.stockanalysis.stock.database.StockDatabase

/**
 * ## 数据导入检查节点 (DataImportNode)
 *
 * 补齐 Hardcode 路径的「首次数据导入」逻辑：
 * 如果 DB 中 daily_snapshot 数量不足（< minSnapshots），
 * 则阻塞拉取历史 K 线数据，确保下游策略有足够数据计算。
 *
 * 正常情况下（数据已充足）此节点 < 5ms 完成。
 * 仅首次使用或数据被清空后触发实际拉取（可能耗时数分钟）。
 *
 * @property days 拉取天数（默认 60）
 * @property minSnapshots 最低快照数量阈值（默认 100）
 */
class DataImportNode(
    private val days: Int = 60,
    private val minSnapshots: Int = 100
) : BaseNode<Any, Int>("data_import", "数据导入检查", NodeType.DATA_SOURCE) {

    companion object {
        private const val TAG = "DataImportNode"
    }

    override suspend fun execute(context: PipelineContext, input: Any): Int {
        val db = StockDatabase.getInstance(context.androidContext)
        val dao = db.dailySnapshotDao()

        // 快速检查：数据充足则直接返回
        val count = try {
            dao.count()
        } catch (e: Exception) {
            Log.w(TAG, "getCount 失败: ${e.message}")
            0
        }

        if (count >= minSnapshots) {
            context.log(nodeId, "$nodeName: 数据充足($count 条)，跳过导入")
            return count
        }

        // 数据不足，触发历史数据拉取
        context.log(nodeId, "📥 $nodeName: 数据不足($count/$minSnapshots)，开始拉取 ${days} 天历史数据...")

        return try {
            val fetcher = HistoricalDataFetcher(context.androidContext)
            fetcher.fetchAllHistoricalData(days)

            val newCount = dao.count()
            context.log(nodeId, "📤 $nodeName: 导入完成，当前 $newCount 条快照")
            newCount
        } catch (e: Exception) {
            Log.e(TAG, "数据导入失败: ${e.message}", e)
            context.log(nodeId, "⚠ $nodeName: 导入失败(${e.message})，使用现有数据继续")
            context.recordError(nodeId, "数据导入失败: ${e.message}")
            count  // 返回原有数量，不阻断 pipeline
        }
    }
}
