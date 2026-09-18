package com.chin.stockanalysis.strategy.trade

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.OnConflictStrategy
import androidx.room.Delete

/**
 * 真实持仓记录（手动导入）
 *
 * 用户从券商APP查看真实持仓后，手动输入到本表。
 * 系统对真实持仓生成做T信号和持仓规划建议，
 * 但不直接下单到券商——仅提供决策参考。
 *
 * 使用场景：
 * 1. 用户在券商APP查看持仓 → 手动录入到此 → 系统生成做T建议
 * 2. 用户按建议在券商APP手动执行 → 回来记录做T结果
 * 3. 系统统计真实持仓的做T收益
 */
@Entity(tableName = "real_positions")
data class RealPositionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stockCode: String,            // 股票代码 sh600xxx / sz000xxx
    val stockName: String,            // 股票名称
    val quantity: Int,                // 持有数量（股）
    val avgBuyPrice: Double,          // 买入均价
    val buyDate: String,              // 首次买入日期 yyyy-MM-dd
    val periodType: String = "",      // 分类：ShortTermQuant / MidTermQuant / LongTermQuant（空串=未分类）
    val sector: String = "",          // 板块
    val notes: String = "",           // 备注
    val isActive: Boolean = true,     // 是否仍持有（false=已清仓）
    val currentPrice: Double = 0.0,   // 最新价（网络API获取）
    val pe: Double = 0.0,             // 市盈率
    val turnoverRate: Double = 0.0,   // 换手率
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface RealPositionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(position: RealPositionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(positions: List<RealPositionEntity>)

    @Delete
    suspend fun delete(position: RealPositionEntity)

    @Query("DELETE FROM real_positions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM real_positions WHERE stockCode = :code")
    suspend fun deleteByCode(code: String)

    @Query("DELETE FROM real_positions WHERE REPLACE(REPLACE(REPLACE(stockCode, 'sh', ''), 'sz', ''), 'bj', '') = :bareCode")
    suspend fun deleteByBareCode(bareCode: String)

    @Query("UPDATE real_positions SET isActive = 0, updatedAt = :updatedAt WHERE id = :id")
    suspend fun markInactive(id: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE real_positions SET quantity = :quantity, avgBuyPrice = :avgBuyPrice, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateQuantity(id: Long, quantity: Int, avgBuyPrice: Double, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE real_positions SET stockName = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStockName(id: Long, name: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE real_positions SET quantity = :quantity, avgBuyPrice = :avgBuyPrice, stockName = :name, sector = :sector, notes = :notes, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updatePosition(id: Long, quantity: Int, avgBuyPrice: Double, name: String, sector: String, notes: String, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM real_positions WHERE isActive = 1 ORDER BY updatedAt DESC")
    suspend fun getAllActive(): List<RealPositionEntity>

    @Query("SELECT * FROM real_positions WHERE isActive = 1 AND periodType = :period ORDER BY updatedAt DESC")
    suspend fun getByPeriod(period: String): List<RealPositionEntity>

    @Query("SELECT * FROM real_positions WHERE stockCode = :code AND isActive = 1 LIMIT 1")
    suspend fun getByCode(code: String): RealPositionEntity?

    @Query("SELECT COUNT(*) FROM real_positions WHERE isActive = 1")
    suspend fun getActiveCount(): Int

    @Query("SELECT * FROM real_positions ORDER BY updatedAt DESC")
    suspend fun getAll(): List<RealPositionEntity>

    @Query("SELECT COALESCE(MAX(id), 0) FROM real_positions")
    suspend fun getMaxId(): Long

    @Query("UPDATE real_positions SET currentPrice = :currentPrice, pe = :pe, turnoverRate = :turnoverRate, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateMarketData(id: Long, currentPrice: Double, pe: Double, turnoverRate: Double, updatedAt: Long = System.currentTimeMillis())
}
