package com.chin.stockanalysis.conversation

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations ORDER BY timestamp DESC")
    fun getAllConvsDesc(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY timestamp DESC")
    suspend fun getAllConvsDescOnce(): List<ConversationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(conv: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :convId")
    suspend fun deleteById(convId: String)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()

    @Query("SELECT * FROM conversations WHERE id = :convId LIMIT 1")
    suspend fun getById(convId: String): ConversationEntity?
}