package io.github.lycheeappf.tmm.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReplyHistoryDao {

    @Insert
    suspend fun insert(entity: ReplyHistoryEntity): Long

    @Query("SELECT * FROM reply_history ORDER BY attemptedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<ReplyHistoryEntity>>

    @Query("DELETE FROM reply_history WHERE attemptedAt < :before")
    suspend fun pruneBefore(before: Long): Int
}
