package com.chin.stockanalysis.memory

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room 实体：跨对话关键信息记忆
 *
 * 用于存储用户在多轮对话中反复强调的偏好/关注点/交易规则，
 * 下次对话时自动注入到 system prompt 中。
 */
@Entity(tableName = "key_memories")
data class KeyMemoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** 类别：data_source / stock_focus / indicator / trading_rule / general */
    @ColumnInfo(name = "category")
    val category: String,

    /** 关键术语（3-8字简短描述，用于列表展示） */
    @ColumnInfo(name = "key")
    val key: String,

    /** 完整记忆描述（注入 prompt 的文本） */
    @ColumnInfo(name = "value")
    val value: String,

    /** 权重 0.0~1.0，首次提取 0.3，确认后递增 */
    @ColumnInfo(name = "weight")
    val weight: Float,

    /** 来源对话 ID 列表（JSON 数组字符串） */
    @ColumnInfo(name = "source_conv_ids")
    val sourceConvIds: String = "[]",

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)