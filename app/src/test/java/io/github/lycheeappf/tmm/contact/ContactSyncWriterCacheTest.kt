package io.github.lycheeappf.tmm.contact

import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import androidx.core.content.ContextCompat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sichert die A1/A2/A3-Hot-Path-Optimierungen ab:
 *  - A1: `ensureAccountAndVisibility` läuft pro Prozess nur einmal.
 *  - A2: identischer (Adresse, Name)-Upsert überspringt ALLE Provider-Writes;
 *        Delete invalidiert den Cache wieder.
 *  - A3: der Produktiv-Upsert macht KEINE PhoneLookup-Query mehr (verifyPhoneLookup
 *        entfernt).
 *
 * Der ContentResolver ist ein mockk; ContactsContract-Uris brauchen aber echte
 * Android-Klassen → Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ContactSyncWriterCacheTest {

    private val context = mockk<Context>(relaxed = true)
    private val cr = mockk<ContentResolver>(relaxed = true)
    private val am = mockk<AccountManager>(relaxed = true)
    private val cursor = mockk<Cursor>(relaxed = true)

    private lateinit var writer: ContactSyncWriter

    private val addr = "+8884210000007"

    @Before fun setup() {
        every { context.contentResolver } returns cr

        mockkStatic(ContextCompat::class)
        every { ContextCompat.checkSelfPermission(any(), any()) } returns PackageManager.PERMISSION_GRANTED

        mockkStatic(AccountManager::class)
        every { AccountManager.get(context) } returns am
        every { am.getAccountsByType(any()) } returns arrayOf(FakeContactAccount.account)

        // Vorhandener RawContact/Group → Update-Pfad (kein insertNewContact).
        every { cursor.moveToFirst() } returns true
        every { cursor.getLong(any()) } returns 10L
        every { cursor.isNull(any()) } returns false
        every { cr.query(any(), any(), any(), any(), any()) } returns cursor
        every { cr.update(any(), any(), any(), any()) } returns 1
        every { cr.delete(any(), any(), any()) } returns 0

        writer = ContactSyncWriter(context)
    }

    @After fun teardown() {
        unmockkStatic(ContextCompat::class, AccountManager::class)
    }

    @Test fun `repeat upsert with same name skips provider writes`() = runTest {
        writer.upsertContact(addr, "Grok")
        writer.upsertContact(addr, "Grok")
        verify(exactly = 1) { cr.update(any(), any(), any(), any()) }
    }

    @Test fun `account setup runs only once across repeated upserts`() = runTest {
        writer.upsertContact(addr, "Grok")
        writer.upsertContact(addr, "Grok")
        verify(exactly = 1) { am.getAccountsByType(any()) }
    }

    @Test fun `changed display name still writes`() = runTest {
        writer.upsertContact(addr, "Grok")
        writer.upsertContact(addr, "Assistant")
        verify(exactly = 2) { cr.update(any(), any(), any(), any()) }
    }

    @Test fun `delete invalidates the dedup cache so next upsert writes again`() = runTest {
        writer.upsertContact(addr, "Grok")
        writer.deleteContact(addr)
        writer.upsertContact(addr, "Grok")
        verify(exactly = 2) { cr.update(any(), any(), any(), any()) }
    }

    @Test fun `production upsert never runs a PhoneLookup query`() = runTest {
        writer.upsertContact(addr, "Grok")
        // 1x ensureAnchorGroupId (Groups) + 1x findRawContactIdBySourceId (RawContacts) = 2.
        // Eine dritte Query gäbe es nur durch das entfernte verifyPhoneLookup.
        verify(exactly = 2) { cr.query(any(), any(), any(), any(), any()) }
    }
}
