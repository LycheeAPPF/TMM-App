package io.github.lycheeappf.tmm.data.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sichert den konfigurierbaren Tesla-Anzeigenamen
 * ([AssistantPreferencesStore.assistantDisplayName]): ein frischer Store liefert den
 * Default „Grok", nach [AssistantPreferencesStore.setAssistantDisplayName] den neuen
 * Wert. DataStore braucht einen echten Context → Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AssistantPreferencesStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = AssistantPreferencesStore(context)

    @Test
    fun `assistant display name defaults to Grok and persists updates`() = runTest {
        assertThat(store.assistantDisplayName())
            .isEqualTo(AssistantPreferencesStore.DEFAULT_ASSISTANT_NAME)

        store.setAssistantDisplayName("Walter Grok")
        assertThat(store.assistantDisplayName()).isEqualTo("Walter Grok")
    }
}
