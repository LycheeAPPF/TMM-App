package io.github.lycheeappf.tmm.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AppPolicyDao {

    @Query("SELECT * FROM app_policies WHERE packageName = :pkg LIMIT 1")
    suspend fun get(pkg: String): AppPolicyEntity?

    @Query("SELECT * FROM app_policies ORDER BY packageName")
    fun observeAll(): Flow<List<AppPolicyEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AppPolicyEntity)

    @Update
    suspend fun update(entity: AppPolicyEntity)

    @Query("SELECT whitelisted FROM app_policies WHERE packageName = :pkg LIMIT 1")
    suspend fun isWhitelisted(pkg: String): Boolean?

    /** Alle freigeschalteten Packages in EINER Query (statt N Einzel-Lookups beim Listen-Aufbau). */
    @Query("SELECT packageName FROM app_policies WHERE whitelisted = 1")
    suspend fun whitelistedPackages(): List<String>

    @Query("UPDATE app_policies SET whitelisted = :value WHERE packageName = :pkg")
    suspend fun setWhitelisted(pkg: String, value: Boolean): Int
}
