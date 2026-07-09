package io.github.lycheeappf.tmm.core.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.BuildConfig
import io.github.lycheeappf.tmm.core.model.ChannelId
import io.github.lycheeappf.tmm.data.db.MappingDao
import io.github.lycheeappf.tmm.data.db.MappingEntity
import io.github.lycheeappf.tmm.data.db.PayloadJson
import io.github.lycheeappf.tmm.data.db.ReplyHistoryDao
import io.github.lycheeappf.tmm.data.db.ReplyHistoryEntity
import io.github.lycheeappf.tmm.data.store.SettingsStore
import io.github.lycheeappf.tmm.data.store.TeslaRegionStore
import io.github.lycheeappf.tmm.data.store.TeslaTokenStore
import io.github.lycheeappf.tmm.domain.channel.ChannelPayload
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DiagnosticsExporterTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mappingDao = mockk<MappingDao>()
    private val replyHistoryDao = mockk<ReplyHistoryDao>()
    private val settingsStore = mockk<SettingsStore>(relaxed = true)
    private val logFileStore = LogFileStore(File(context.cacheDir, "diag-test"), Dispatchers.Unconfined)
    private val teslaTokenStore = mockk<TeslaTokenStore> {
        coEvery { isAuthenticated() } returns false
        coEvery { readSelectedVin() } returns null
        coEvery { readExpiresAtMs() } returns 0L
    }
    private val teslaRegionStore = mockk<TeslaRegionStore> {
        coEvery { readFleetApiBaseUrl() } returns null
    }

    private fun exporter() =
        DiagnosticsExporter(
            context, mappingDao, replyHistoryDao, logFileStore, settingsStore,
            teslaTokenStore, teslaRegionStore
        )

    @Test fun `export redacts contact names, conversation key and reply text`() = runTest {
        val payloadJson = PayloadJson.encode(
            ChannelPayload.Notification(
                sourcePackage = "com.whatsapp",
                notificationKey = "0|com.whatsapp|1|null|1",
                remoteInputResultKey = "k",
                conversationLabel = "Anna",
                senderDisplayName = "Anna"
            )
        )
        val mapping = MappingEntity(
            mappingId = 1L,
            channel = ChannelId.NOTIFICATION.code,
            fakeAddress = "+888100000001",
            conversationKey = "com.whatsapp::id::secretJid",
            payloadJson = payloadJson,
            createdAt = 0L,
            expiresAt = 0L,
            lastUsedAt = null,
            replyCount = 0,
            replyable = true
        )
        val history = ReplyHistoryEntity(
            id = 1L,
            mappingId = 1L,
            channel = ChannelId.NOTIFICATION.code,
            text = "secret dictation",
            attemptedAt = 0L,
            result = "SUCCESS",
            errorDetail = null
        )
        every { mappingDao.observeByChannel(any(), any()) } returns flowOf(emptyList())
        every { mappingDao.observeByChannel(ChannelId.NOTIFICATION.code, 200) } returns flowOf(listOf(mapping))
        every { replyHistoryDao.observeRecent(any()) } returns flowOf(listOf(history))

        val content = exporter().exportToCache().readText()

        // PII raus
        assertThat(content).doesNotContain("Anna")
        assertThat(content).doesNotContain("secretJid")
        assertThat(content).doesNotContain("secret dictation")
        // Diagnose-Signale erhalten
        assertThat(content).contains("sha1:")
        assertThat(content).contains("<len=16>")        // "secret dictation".length == 16
        assertThat(content).contains("com.whatsapp")     // Paketname bleibt
        assertThat(content).contains(BuildConfig.VERSION_NAME) // Build-Header gesetzt
        // Tesla-API-Abschnitt vorhanden, nicht authentifiziert → kein Token-/VIN-Material
        assertThat(content).contains("teslaApi")
        assertThat(content).contains("\"authenticated\": false")
    }

    @Test fun `export masks the VIN to its last 4 chars`() = runTest {
        coEvery { teslaTokenStore.readSelectedVin() } returns "5YJ3E7EB1KF000123"
        every { mappingDao.observeByChannel(any(), any()) } returns flowOf(emptyList())
        every { replyHistoryDao.observeRecent(any()) } returns flowOf(emptyList())

        val content = exporter().exportToCache().readText()

        assertThat(content).doesNotContain("5YJ3E7EB1KF000123")
        assertThat(content).contains("…0123")
    }
}
