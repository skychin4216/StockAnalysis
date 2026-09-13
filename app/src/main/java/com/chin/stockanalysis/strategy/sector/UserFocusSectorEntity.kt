package com.chin.stockanalysis.strategy.sector

import androidx.room.*

/** 用户关注板块（历史记录 + 启用状态） */
@Entity(tableName = "user_focus_sectors", indices = [Index(value = ["sector_name"], unique = true)])
data class UserFocusSectorEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "sector_name") val sectorName: String,
    @ColumnInfo(name = "is_active") val isActive: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface UserFocusSectorDao {
    @Query("SELECT * FROM user_focus_sectors ORDER BY is_active DESC, created_at DESC")
    suspend fun getAll(): List<UserFocusSectorEntity>

    @Query("SELECT sector_name FROM user_focus_sectors WHERE is_active = 1")
    suspend fun getActiveNames(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: UserFocusSectorEntity): Long

    @Query("UPDATE user_focus_sectors SET is_active = :active WHERE sector_name = :sectorName")
    suspend fun setActive(sectorName: String, active: Boolean)

    @Query("DELETE FROM user_focus_sectors WHERE sector_name = :sectorName")
    suspend fun delete(sectorName: String)
}
