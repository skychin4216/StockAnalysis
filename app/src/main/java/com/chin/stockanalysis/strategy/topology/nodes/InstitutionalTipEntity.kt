package com.chin.stockanalysis.strategy.topology.nodes

import androidx.room.*

/**
 * ## 機構線索實體
 *
 * 記錄 AI 對話中提到的機構提示（研報/目標價/買入評級等），
 * 短線/超短線 Pipeline 運行時讀取未過期線索，對相關股票加分。
 */
@Entity(
    tableName = "institutional_tips",
    indices = [Index(value = ["stock_code"]), Index(value = ["expire_date"])]
)
data class InstitutionalTipEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "stock_code")
    val stockCode: String,          // sh600519 / sz000001

    @ColumnInfo(name = "stock_name")
    val stockName: String = "",

    @ColumnInfo(name = "sector")
    val sector: String = "",        // 板塊（可選）

    @ColumnInfo(name = "source")
    val source: String = "ai_chat", // 來源：ai_chat / manual

    @ColumnInfo(name = "tip_type")
    val tipType: String = "research", // research=研報 / rating=評級 / target=目標價

    @ColumnInfo(name = "summary")
    val summary: String = "",       // 摘要（機構觀點）

    @ColumnInfo(name = "chat_id")
    val chatId: String = "",        // 關聯的對話 ID

    @ColumnInfo(name = "created_date")
    val createdDate: String = "",   // YYYY-MM-DD

    @ColumnInfo(name = "expire_date")
    val expireDate: String = ""     // YYYY-MM-DD（默認 created + 3天）
)

@Dao
interface InstitutionalTipDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tip: InstitutionalTipEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tips: List<InstitutionalTipEntity>)

    /** 獲取未過期的機構線索 */
    @Query("SELECT * FROM institutional_tips WHERE expire_date >= :today ORDER BY created_date DESC")
    suspend fun getActiveTips(today: String): List<InstitutionalTipEntity>

    /** 獲取某隻股票的未過期線索 */
    @Query("SELECT * FROM institutional_tips WHERE stock_code = :code AND expire_date >= :today")
    suspend fun getActiveTipsByCode(code: String, today: String): List<InstitutionalTipEntity>

    /** 獲取未過期線索涉及的所有股票代碼 */
    @Query("SELECT DISTINCT stock_code FROM institutional_tips WHERE expire_date >= :today")
    suspend fun getActiveTipCodes(today: String): List<String>

    /** 刪除過期線索 */
    @Query("DELETE FROM institutional_tips WHERE expire_date < :today")
    suspend fun deleteExpired(today: String)

    @Query("SELECT COUNT(*) FROM institutional_tips WHERE expire_date >= :today")
    suspend fun countActive(today: String): Int
}
