package com.chin.stockanalysis.stock.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BacktestMetaDao {

    @Query("SELECT meta_value FROM backtest_meta WHERE meta_key = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: BacktestMetaEntity)

    @Query("SELECT * FROM backtest_meta")
    suspend fun getAll(): List<BacktestMetaEntity>
}

@Dao
interface BacktestSelectedStockDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: BacktestSelectedStockEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<BacktestSelectedStockEntity>)

    @Query("SELECT * FROM backtest_selected_stock WHERE period = :period ORDER BY signal_date DESC, code")
    suspend fun getByPeriod(period: String): List<BacktestSelectedStockEntity>

    @Query("SELECT * FROM backtest_selected_stock WHERE period IN (:periods) ORDER BY signal_date DESC, code")
    suspend fun getByPeriods(periods: List<String>): List<BacktestSelectedStockEntity>

    /** 短期记录只保留最近 [days] 天；中/长线长期保留 */
    @Query("DELETE FROM backtest_selected_stock WHERE period IN (:shortPeriods) AND signal_date < :cutoffDate")
    suspend fun pruneShortTerm(shortPeriods: List<String>, cutoffDate: String): Int

    @Query("SELECT COUNT(*) FROM backtest_selected_stock")
    suspend fun count(): Int
}
