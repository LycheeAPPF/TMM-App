package io.github.lycheeappf.tmm.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        MappingEntity::class,
        ReplyHistoryEntity::class,
        AppPolicyEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class MfsDatabase : RoomDatabase() {
    abstract fun mappingDao(): MappingDao
    abstract fun replyHistoryDao(): ReplyHistoryDao
    abstract fun appPolicyDao(): AppPolicyDao

    companion object {
        const val NAME = "mfs.db"
    }
}
