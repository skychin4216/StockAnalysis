package com.chin.stockanalysis.strategy.trade

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.OnConflictStrategy
import androidx.room.Delete

/**
 * 真實持倉記錄（手動導入）
 *
 * 用戶從券商APP查看真實持倉後，手動輸入到本表。
 * 系統對真實持倉生成做T信號和持倉規劃建議，
 * 但不直接下單到券商——僅提供決策參考。
 *
 * 使用場景：
 * 1. 用戶在券商APP查看持倉 → 手動錄入到此 → 系統生成做T建議
 * 2. 用戶按建議在券商APP手動執行 → 回來記錄做T結果
 * 3. 系統統計真實持倉的做T收益
 */
@Entity(tableName = "real_positions")
data class RealPositionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stockCode: String,            // 股票代碼 sh600xxx / sz000xxx
    val stockName: String,            // 股票名稱
    val quantity: Int,                // 持有數量（股）
    val avgBuyPrice: Double,          // 買入均價
    val buyDate: String,              // 首次買入日期 yyyy-MM-dd
    val periodType: String = "",      // 分類：ShortTermQuant / MidTermQuant / LongTermQuant（空串=未分類）
    val sector: String = "",          // 板塊
    val notes: String = "",           // 備註
    val isActive: Boolean = true,     // 是否仍持有（false=已清倉）
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
}
