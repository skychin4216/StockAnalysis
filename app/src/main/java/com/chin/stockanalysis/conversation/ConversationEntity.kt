package com.chin.stockanalysis.conversation

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.chin.stockanalysis.ui.Message

/**
 * Room 实体：持久化对话会话
 */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "subtitle")
    val subtitle: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    /** 对话消息列表的 JSON 序列化字符串 */
    @ColumnInfo(name = "messages_json")
    val messagesJson: String = "[]"
)