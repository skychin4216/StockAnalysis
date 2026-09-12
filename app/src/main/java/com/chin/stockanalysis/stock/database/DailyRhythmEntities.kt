package com.chin.stockanalysis.stock.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 每日节奏情报（08:00 盘前 / 09:00 亚太 / 15:20 复盘）。
 *
 * 与 PC 侧 `market_data.db.intel_report` **字段一一对应**（单一事实源口径）：
 * 两端各自留痕，便于「同一交易日双端情报是否一致」的核对。
 *
 * @param tradeDate   交易日 YYYY-MM-DD
 * @param slot        pre8（08:00 盘前）/ pre9（09:00 亚太）/ review（15:20 复盘）
 * @param macroJson   全球/宏观快照 JSON（美股收盘、亚太、商品汇率、美债）
 * @param sectorsJson 利好利空板块判定 JSON
 * @param picksJson   推送标的 JSON
 * @param newsJson    新闻原文 JSON
 */
@Entity(
    tableName = "daily_intel",
    indices = [Index("tradeDate"), Index("slot")]
)
data class DailyIntelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tradeDate: String,
    val slot: String,
    val createdAt: String,
    val title: String = "",
    val digest: String = "",
    val macroJson: String = "",
    val sectorsJson: String = "",
    val picksJson: String = "",
    val newsJson: String = "",
    val content: String = "",
    val pushed: Boolean = false,
)

/**
 * 推送账本。与 PC 侧 `market_data.db.push_record` 字段一一对应。
 *
 * @param slot  pre8 / pre9 / am / pm / lunch / eod / review
 * @param kind  text / image / intel / round / lunch / eod / review
 */
@Entity(
    tableName = "push_record",
    indices = [Index("tradeDate"), Index("slot")]
)
data class PushRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tradeDate: String,
    val slot: String = "",
    val createdAt: String,
    val kind: String = "",
    val title: String = "",
    val codes: String = "",
    val ok: Boolean = false,
    val err: String = "",
    val content: String = "",
)

@Dao
interface DailyIntelDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DailyIntelEntity): Long

    @Query("SELECT * FROM daily_intel WHERE tradeDate = :date AND slot = :slot ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestOfSlot(date: String, slot: String): DailyIntelEntity?

    @Query("SELECT * FROM daily_intel WHERE tradeDate = :date ORDER BY createdAt ASC")
    suspend fun ofDate(date: String): List<DailyIntelEntity>

    @Query("SELECT * FROM daily_intel ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 50): List<DailyIntelEntity>

    @Query("DELETE FROM daily_intel WHERE tradeDate < :before")
    suspend fun deleteBefore(before: String): Int
}

@Dao
interface PushRecordDao {

    @Insert
    suspend fun insert(entity: PushRecordEntity): Long

    @Query("SELECT * FROM push_record WHERE tradeDate = :date ORDER BY id DESC LIMIT :limit")
    suspend fun ofDate(date: String, limit: Int = 200): List<PushRecordEntity>

    @Query("SELECT * FROM push_record ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int = 200): List<PushRecordEntity>

    @Query("SELECT COUNT(*) FROM push_record WHERE tradeDate = :date AND slot = :slot AND kind = :kind")
    suspend fun countOf(date: String, slot: String, kind: String): Int

    @Query("DELETE FROM push_record WHERE tradeDate < :before")
    suspend fun deleteBefore(before: String): Int
}
