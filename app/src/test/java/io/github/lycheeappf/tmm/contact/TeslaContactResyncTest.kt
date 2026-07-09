package io.github.lycheeappf.tmm.contact

import android.content.Context
import io.github.lycheeappf.tmm.channel.llm.AssistantContactProvisioner
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Regression für den Race-Fix in [TeslaContactResync.force]: Grok + Sprach-Alias
 * müssen SYNCHRON via [AssistantContactProvisioner.reconcile] (re-)provisioniert
 * werden, nach dem Account-Neuaufbau aber noch VOR dem asynchronen
 * [ContactBackfillWorker] — sonst kann ein sofortiger PBAP-Pull des Tesla
 * (getriggert durch den account_changes-Bump aus removeAccount) ein leeres
 * Telefonbuch ziehen. Beide Schritte sind best-effort (coRunCatching): ein
 * Fehlschlag darf die restliche Kette nicht abbrechen.
 */
class TeslaContactResyncTest {

    private val context = mockk<Context>(relaxed = true)
    private val contactSyncWriter = mockk<ContactSyncWriter>(relaxed = true)
    private val provisioner = mockk<AssistantContactProvisioner>(relaxed = true)
    private val resync = TeslaContactResync(context, contactSyncWriter, provisioner)

    @Before
    fun setUp() {
        mockkObject(ContactBackfillWorker.Companion)
        every { ContactBackfillWorker.enqueue(any()) } just Runs
    }

    @After
    fun tearDown() {
        unmockkObject(ContactBackfillWorker.Companion)
    }

    @Test
    fun `force reconciles assistant contacts synchronously before enqueueing the backfill`() = runTest {
        resync.force()

        coVerifyOrder {
            contactSyncWriter.deleteAllContacts()
            contactSyncWriter.removeAccount()
            contactSyncWriter.ensureAccountAndVisibility()
            provisioner.reconcile()
            ContactBackfillWorker.enqueue(context)
        }
    }

    @Test
    fun `force still enqueues the backfill when reconcile fails`() = runTest {
        coEvery { provisioner.reconcile() } throws IllegalStateException("boom")

        resync.force()

        coVerify { ContactBackfillWorker.enqueue(context) }
    }

    @Test
    fun `force still reconciles and enqueues when the contact writer fails`() = runTest {
        coEvery { contactSyncWriter.deleteAllContacts() } throws IllegalStateException("boom")

        resync.force()

        coVerify { provisioner.reconcile() }
        coVerify { ContactBackfillWorker.enqueue(context) }
    }
}
