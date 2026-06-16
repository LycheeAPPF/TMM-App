package io.github.lycheeappf.tmm.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_policies")
data class AppPolicyEntity(
    @PrimaryKey val packageName: String,
    val whitelisted: Boolean,
    val customDisplayName: String?,
    val lastSeenRemoteInput: Boolean,
    val lastSeenAt: Long
)
