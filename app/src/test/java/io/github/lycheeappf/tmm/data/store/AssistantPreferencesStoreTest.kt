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
 * Sichert das Per-Alias-Pref ([AssistantPreferencesStore.isVoiceAliasEnabled]):
 * Default ist `true` (bisheriges Verhalten — Grog/Grogg vorhanden), jeder Alias
 * (Grog = 1, Grogg = 2) ist UNABHÄNGIG schaltbar. DataStore braucht einen echten
 * Context → Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AssistantPreferencesStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = AssistantPreferencesStore(context)

    @Test
    fun `each voice alias defaults to enabled and toggles independently`() = runTest {
        // Frischer Store: beide Aliasse lesen den Default true.
        assertThat(store.isVoiceAliasEnabled(1L)).isTrue()
        assertThat(store.isVoiceAliasEnabled(2L)).isTrue()

        // Nur Grogg (id 2) ausschalten — Grog (id 1) bleibt unberührt an.
        store.setVoiceAliasEnabled(2L, false)
        assertThat(store.isVoiceAliasEnabled(1L)).isTrue()
        assertThat(store.isVoiceAliasEnabled(2L)).isFalse()

        store.setVoiceAliasEnabled(2L, true)
        assertThat(store.isVoiceAliasEnabled(2L)).isTrue()
    }
}
