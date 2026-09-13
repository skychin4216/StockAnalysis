package com.chin.stockanalysis.strategy.topology.nodes

import androidx.room.*

/**
 * ## 机构线索实体
 *
 * 记录 AI 对话中提到的机构提示（研报/目标价/买入评级等），
 * 短线/超短线 Pipeline 运行时读取未过期线索，对相关股票加分。
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
    val sector: String = "",        // 板块（可选）

    @ColumnInfo(name = "source")
    val source: String = "ai_chat", // 来源：ai_chat / manual

    @ColumnInfo(name = "tip_type")
    val tipType: String = "research", // research=研报 / rating=评级 / target=目标价

    @ColumnInfo(name = "summary")
    val summary: String = "",       // 摘要（机构观点）

    @ColumnInfo(name = "chat_id")
    val chatId: String = "",        // 关联的对话 ID

    @ColumnInfo(name = "created_date")
    val createdDate: String = "",   // YYYY-MM-DD

    @ColumnInfo(name = "expire_date")
    val expireDate: String = ""     // YYYY-MM-DD（默认 created + 3天）
)

@Dao
interface InstitutionalTipDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tip: InstitutionalTipEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tips: List<InstitutionalTipEntity>)

    /** 获取未过期的机构线索 */
    @Query("SELECT * FROM institutional_tips WHERE expire_date >= :today ORDER BY created_date DESC")
    suspend fun getActiveTips(today: String): List<InstitutionalTipEntity>

    /** 获取某只股票的未过期线索 */
    @Query("SELECT * FROM institutional_tips WHERE stock_code = :code AND expire_date >= :today")
    suspend fun getActiveTipsByCode(code: String, today: String): List<InstitutionalTipEntity>

    /** 获取未过期线索涉及的所有股票代码 */
    @Query("SELECT DISTINCT stock_code FROM institutional_tips WHERE expire_date >= :today")
    suspend fun getActiveTipCodes(today: String): List<String>

    /** 删除过期线索 */
    @Query("DELETE FROM institutional_tips WHERE expire_date < :today")
    suspend fun deleteExpired(today: String)

    @Query("SELECT COUNT(*) FROM institutional_tips WHERE expire_date >= :today")
    suspend fun countActive(today: String): Int
}
