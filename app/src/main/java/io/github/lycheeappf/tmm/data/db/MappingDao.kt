package io.github.lycheeappf.tmm.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MappingDao {

    @Query("SELECT * FROM mappings WHERE mappingId = :mappingId AND channel = :channel LIMIT 1")
    suspend fun findById(mappingId: Long, channel: Int): MappingEntity?

    @Query("SELECT * FROM mappings WHERE channel = :channel AND conversationKey = :key LIMIT 1")
    suspend fun findByConversationKey(channel: Int, key: String): MappingEntity?

    @Query("SELECT * FROM mappings WHERE fakeAddress = :address LIMIT 1")
    suspend fun findByFakeAddress(address: String): MappingEntity?

    @Query("SELECT * FROM mappings WHERE channel = :channel ORDER BY createdAt DESC LIMIT :limit")
    fun observeByChannel(channel: Int, limit: Int = 100): Flow<List<MappingEntity>>

    @Query("SELECT * FROM mappings")
    suspend fun findAll(): List<MappingEntity>

    @Query("SELECT * FROM mappings WHERE expiresAt < :now")
    suspend fun findExpired(now: Long): List<MappingEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: MappingEntity)

    @Update
    suspend fun update(entity: MappingEntity)

    @Query("UPDATE mappings SET lastUsedAt = :now, replyCount = replyCount + 1 WHERE mappingId = :mappingId AND channel = :channel")
    suspend fun recordReply(mappingId: Long, channel: Int, now: Long)

    @Query("UPDATE mappings SET expiresAt = :newExpiresAt, lastUsedAt = :now WHERE mappingId = :mappingId AND channel = :channel")
    suspend fun refreshExpiry(mappingId: Long, channel: Int, newExpiresAt: Long, now: Long)

    @Query("UPDATE mappings SET fakeAddress = :newFakeAddress WHERE mappingId = :mappingId AND channel = :channel")
    suspend fun updateFakeAddress(mappingId: Long, channel: Int, newFakeAddress: String): Int

    @Query("UPDATE mappings SET payloadJson = :payloadJson, replyable = :replyable, expiresAt = :newExpiresAt, lastUsedAt = :now WHERE mappingId = :mappingId AND channel = :channel")
    suspend fun refreshOnReuse(
        mappingId: Long,
        channel: Int,
        payloadJson: String,
        replyable: Boolean,
        newExpiresAt: Long,
        now: Long
    )

    @Query("DELETE FROM mappings WHERE expiresAt < :now")
    suspend fun deleteExpired(now: Long): Int

    @Query("DELETE FROM mappings")
    suspend fun deleteAll(): Int

    @Query("DELETE FROM mappings WHERE fakeAddress = :address")
    suspend fun deleteByAddress(address: String): Int

    @Query("DELETE FROM mappings WHERE mappingId = :mappingId AND channel = :channel")
    suspend fun deleteById(mappingId: Long, channel: Int): Int

    @Query("SELECT COUNT(*) FROM mappings WHERE channel = :channel")
    suspend fun countForChannel(channel: Int): Int
}
