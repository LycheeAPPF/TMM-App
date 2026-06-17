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
 * Sichert das Sprach-Alias-Pref ([AssistantPreferencesStore.isVoiceAliasesEnabled]):
 * Default ist `true` (bisheriges Verhalten — Grog/Grogg vorhanden), und der Debug-
 * Schalter persistiert ein explizites `false`. DataStore braucht einen echten
 * Context → Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AssistantPreferencesStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = AssistantPreferencesStore(context)

    @Test
    fun `voice aliases default to enabled and can be toggled off`() = runTest {
        // Frischer Store: ungesetztes Pref liest den Default true.
        assertThat(store.isVoiceAliasesEnabled()).isTrue()

        store.setVoiceAliasesEnabled(false)
        assertThat(store.isVoiceAliasesEnabled()).isFalse()

        store.setVoiceAliasesEnabled(true)
        assertThat(store.isVoiceAliasesEnabled()).isTrue()
    }
}
