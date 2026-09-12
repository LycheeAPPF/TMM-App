package io.github.lycheeappf.tmm.sms.read

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.platform.role.DefaultSmsRoleManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wiring-Tests des destruktiven Pfads: Default-App-Gate und Fake-Guard müssen
 * VOR jedem ContentResolver.delete greifen (Robolectric + In-Memory-Provider).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SmsInboxReaderImplDeleteTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val roleManager = mockk<DefaultSmsRoleManager>()
    private val nameResolver = mockk<ContactNameResolver>(relaxed = true)

    @Before
    fun setUp() {
        FakeSmsProvider.reset()
        Robolectric.buildContentProvider(FakeSmsProvider::class.java).create("sms")
        every { roleManager.isDefault() } returns true
    }

    private fun TestScope.reader() =
        SmsInboxReaderImpl(context, nameResolver, roleManager, StandardTestDispatcher(testScheduler))

    @Test
    fun `delete refuses without default sms role`() = runTest {
        every { roleManager.isDefault() } returns false
        FakeSmsProvider.rows = listOf(1L to "+49111")

        assertThat(reader().deleteMessage(1)).isFalse()
        assertThat(FakeSmsProvider.deleteSelections).isEmpty()
    }

    @Test
    fun `delete refuses when provider query returns null`() = runTest {
        FakeSmsProvider.rows = null

        assertThat(reader().deleteMessage(1)).isFalse()
        assertThat(FakeSmsProvider.deleteSelections).isEmpty()
    }

    @Test
    fun `delete refuses when no rows match`() = runTest {
        FakeSmsProvider.rows = emptyList()

        assertThat(reader().deleteThread(7)).isFalse()
        assertThat(FakeSmsProvider.deleteSelections).isEmpty()
    }

    @Test
    fun `delete refuses fake-only targets`() = runTest {
        FakeSmsProvider.rows = listOf(1L to "+888100000005")

        assertThat(reader().deleteThread(7)).isFalse()
        assertThat(FakeSmsProvider.deleteSelections).isEmpty()
    }

    @Test
    fun `delete refuses mixed real and fake targets`() = runTest {
        FakeSmsProvider.rows = listOf(1L to "+49111", 2L to "+888100000005")

        assertThat(reader().deleteThread(7)).isFalse()
        assertThat(FakeSmsProvider.deleteSelections).isEmpty()
    }

    @Test
    fun `delete removes validated real rows by id`() = runTest {
        FakeSmsProvider.rows = listOf(1L to "+49111", 2L to "+49222")

        assertThat(reader().deleteThread(7)).isTrue()
        assertThat(FakeSmsProvider.deleteSelections).containsExactly("_id IN (1,2)")
    }

    @Test
    fun `delete allows rows with null address`() = runTest {
        // Service-SMS ohne Absenderadresse sind echte Rows und bleiben löschbar.
        FakeSmsProvider.rows = listOf(1L to null)

        assertThat(reader().deleteMessage(1)).isTrue()
        assertThat(FakeSmsProvider.deleteSelections).containsExactly("_id IN (1)")
    }
}

/** In-Memory-SMS-Provider: Query liefert den konfigurierten Snapshot, Delete wird protokolliert. */
class FakeSmsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        val data = rows ?: return null
        return MatrixCursor(arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS)).apply {
            data.forEach { (id, addr) -> addRow(arrayOf(id, addr)) }
        }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        deleteSelections += selection
        return rows?.size ?: 0
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun getType(uri: Uri): String? = null

    companion object {
        /** null ⇒ query() liefert null (Provider-Fehlerfall). */
        var rows: List<Pair<Long, String?>>? = emptyList()
        val deleteSelections = mutableListOf<String?>()
        fun reset() {
            rows = emptyList()
            deleteSelections.clear()
        }
    }
}
