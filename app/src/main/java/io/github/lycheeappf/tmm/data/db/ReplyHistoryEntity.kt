package io.github.lycheeappf.tmm.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "reply_history",
    indices = [Index("attemptedAt"), Index("mappingId")]
)
data class ReplyHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mappingId: Long,
    val channel: Int,
    val text: String,
    val attemptedAt: Long,
    val result: String,
    val errorDetail: String?
)
