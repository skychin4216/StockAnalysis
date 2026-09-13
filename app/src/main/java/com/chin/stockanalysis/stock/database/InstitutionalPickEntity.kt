package com.chin.stockanalysis.stock.database

import androidx.room.*

/**
 * 机构推荐股票实体
 *
 * 支持自定义分组（如中金、中信、高盛等），每条记录属于一个分组。
 * 支持子级别（subGroup）用于更细粒度的分类。
 */
@Entity(
    tableName = "institutional_picks",
    indices = [
        Index(value = ["institution_name"]),
        Index(value = ["stock_code"])
    ]
)
data class InstitutionalPickEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "institution_name") val institutionName: String,
    @ColumnInfo(name = "stock_code") val stockCode: String,
    @ColumnInfo(name = "stock_name") val stockName: String,
    @ColumnInfo(name = "recommend_date") val recommendDate: String,
    @ColumnInfo(name = "target_price") val targetPrice: Double = 0.0,
    @ColumnInfo(name = "reason") val reason: String = "",
    @ColumnInfo(name = "source_type") val sourceType: String = "manual", // manual / ocr / paste
    @ColumnInfo(name = "sub_group") val subGroup: String = "",
    @ColumnInfo(name = "notes") val notes: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface InstitutionalPickDao {
    @Query("SELECT DISTINCT institution_name FROM institutional_picks ORDER BY institution_name")
    suspend fun getAllGroups(): List<String>

    @Query("SELECT * FROM institutional_picks WHERE institution_name = :group ORDER BY recommend_date DESC")
    suspend fun getByGroup(group: String): List<InstitutionalPickEntity>

    @Query("SELECT * FROM institutional_picks WHERE institution_name = :group AND sub_group = :subGroup ORDER BY recommend_date DESC")
    suspend fun getBySubGroup(group: String, subGroup: String): List<InstitutionalPickEntity>

    @Query("SELECT DISTINCT sub_group FROM institutional_picks WHERE institution_name = :group AND sub_group != '' ORDER BY sub_group")
    suspend fun getSubGroups(group: String): List<String>

    @Query("SELECT * FROM institutional_picks ORDER BY created_at DESC")
    suspend fun getAll(): List<InstitutionalPickEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: InstitutionalPickEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<InstitutionalPickEntity>)

    @Query("DELETE FROM institutional_picks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM institutional_picks WHERE institution_name = :group")
    suspend fun deleteByGroup(group: String)

    @Query("DELETE FROM institutional_picks WHERE stock_code = :code AND institution_name = :group")
    suspend fun deleteByCodeAndGroup(code: String, group: String)

    @Query("SELECT COUNT(*) FROM institutional_picks WHERE institution_name = :group")
    suspend fun countByGroup(group: String): Int
}
