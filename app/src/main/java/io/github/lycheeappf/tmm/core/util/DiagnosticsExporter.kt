package io.github.lycheeappf.tmm.core.util

import android.content.Context
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lycheeappf.tmm.core.model.ChannelId
import io.github.lycheeappf.tmm.data.db.MappingDao
import io.github.lycheeappf.tmm.data.db.MappingEntity
import io.github.lycheeappf.tmm.data.db.ReplyHistoryDao
import io.github.lycheeappf.tmm.data.db.ReplyHistoryEntity
import io.github.lycheeappf.tmm.data.store.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dumpt einen Snapshot aller wichtigen Diagnose-Daten (Mappings, Reply-History,
 * Logs, Settings, OS/Device) als JSON in den App-Cache. Nutzbar zum Anhängen
 * an Bug-Reports.
 */
@Singleton
class DiagnosticsExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mappingDao: MappingDao,
    private val replyHistoryDao: ReplyHistoryDao,
    private val logBuffer: LogBuffer,
    private val settingsStore: SettingsStore
) {

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    suspend fun exportToCache(): File {
        val mappings = collectAllMappings()
        val history = collectRecentHistory()
        val payload = DiagnosticsSnapshot(
            generatedAt = System.currentTimeMillis(),
            android = AndroidInfo(
                sdkInt = Build.VERSION.SDK_INT,
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                release = Build.VERSION.RELEASE
            ),
            settings = SettingsSnapshot(
                ttlHours = settingsStore.mappingTtlHours(),
                sendBudget = settingsStore.sendBudgetPerDay(),
                sendCountToday = settingsStore.dailySendCount(),
                preflightResult = settingsStore.preflightResult()
            ),
            mappings = mappings.map { it.toSerializable() },
            replyHistory = history.map { it.toSerializable() },
            logs = logBuffer.snapshot().map {
                LogSerializable(
                    ts = it.timestamp,
                    level = it.level.name,
                    tag = it.tag,
                    message = it.message
                )
            }
        )
        val text = json.encodeToString(payload)
        val timestamp = exportFileName.format(Date())
        val file = File(context.cacheDir, "mfs-diagnostics-$timestamp.json")
        file.writeText(text)
        Log.i(TAG, "Diagnostics exported to ${file.absolutePath} (${file.length()} bytes)")
        return file
    }

    private suspend fun collectAllMappings(): List<MappingEntity> {
        val all = mutableListOf<MappingEntity>()
        for (channel in ChannelId.entries) {
            all += mappingDao.observeByChannel(channel.code, limit = 200).first()
        }
        return all
    }

    private suspend fun collectRecentHistory(): List<ReplyHistoryEntity> =
        replyHistoryDao.observeRecent(limit = 500).first()

    private fun MappingEntity.toSerializable() = MappingSerializable(
        mappingId, channel, fakeAddress, conversationKey, payloadJson,
        createdAt, expiresAt, lastUsedAt, replyCount, replyable
    )

    private fun ReplyHistoryEntity.toSerializable() = ReplyHistorySerializable(
        id, mappingId, channel, text, attemptedAt, result, errorDetail
    )

    companion object {
        private const val TAG = "DiagnosticsExporter"
        private val exportFileName =
            SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.GERMANY)
    }
}

@Serializable
private data class DiagnosticsSnapshot(
    val generatedAt: Long,
    val android: AndroidInfo,
    val settings: SettingsSnapshot,
    val mappings: List<MappingSerializable>,
    val replyHistory: List<ReplyHistorySerializable>,
    val logs: List<LogSerializable>
)

@Serializable
private data class AndroidInfo(
    val sdkInt: Int,
    val manufacturer: String,
    val model: String,
    val release: String
)

@Serializable
private data class SettingsSnapshot(
    val ttlHours: Int,
    val sendBudget: Int,
    val sendCountToday: Int,
    val preflightResult: String?
)

@Serializable
private data class MappingSerializable(
    val mappingId: Long,
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

@Serializable
private data class ReplyHistorySerializable(
    val id: Long,
    val mappingId: Long,
    val channel: Int,
    val text: String,
    val attemptedAt: Long,
    val result: String,
    val errorDetail: String?
)

@Serializable
private data class LogSerializable(
    val ts: Long,
    val level: String,
    val tag: String,
    val message: String
)
