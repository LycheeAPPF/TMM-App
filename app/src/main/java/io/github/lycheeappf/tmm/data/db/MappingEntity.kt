package io.github.lycheeappf.tmm.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "mappings",
    indices = [
        Index(value = ["fakeAddress"], unique = true),
        Index(value = ["channel", "conversationKey"], unique = true),
        Index("expiresAt")
    ]
)
data class MappingEntity(
    @PrimaryKey val mappingId: Long,
    val channel: Int,
    val fakeAddress: String,
    val conversationKey: String,
    val payloadJson: String,
    val createdAt: Long,
    val expiresAt: Long,
    val lastUsedAt: Long?,
    val replyCount: Int,
    val replyable: Boolean
)
