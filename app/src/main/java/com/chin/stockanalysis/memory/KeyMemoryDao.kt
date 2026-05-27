package com.chin.stockanalysis.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface KeyMemoryDao {

    /** 获取所有权重大于阈值的记忆（用于注入 prompt） */
    @Query("SELECT * FROM key_memories WHERE weight >= :minWeight ORDER BY weight DESC")
    suspend fun getActiveMemories(minWeight: Float = 0.3f): List<KeyMemoryEntity>

    /** Flow 观察所有记忆（用于设置页展示） */
    @Query("SELECT * FROM key_memories ORDER BY weight DESC")
    fun getAllMemoriesFlow(): Flow<List<KeyMemoryEntity>>

    /** 一次性获取所有记忆 */
    @Query("SELECT * FROM key_memories ORDER BY weight DESC")
    suspend fun getAllMemories(): List<KeyMemoryEntity>

    /** 按类别查询 */
    @Query("SELECT * FROM key_memories WHERE category = :category ORDER BY weight DESC")
    suspend fun getByCategory(category: String): List<KeyMemoryEntity>

    /** 按 key 查询（用于合并去重） */
    @Query("SELECT * FROM key_memories WHERE `key` = :key LIMIT 1")
    suspend fun getByKey(key: String): KeyMemoryEntity?

    /** 插入或更新 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: KeyMemoryEntity)

    /** 删除 */
    @Query("DELETE FROM key_memories WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 清空所有记忆 */
    @Query("DELETE FROM key_memories")
    suspend fun deleteAll()

    /** 衰减长期未更新的记忆（30天未引用 -0.05） */
    @Query("UPDATE key_memories SET weight = MAX(0.0, weight - 0.05) WHERE updated_at < :thresholdMs")
    suspend fun decayOldMemories(thresholdMs: Long)
}