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
 * Sichert den Antwort-Namen (fest „Grok") und den konfigurierbaren Sprach-Ansprech-
 * Kontakt: Default aktiv mit Name „Elon Musk", Setter persistieren. DataStore
 * braucht einen echten Context → Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AssistantPreferencesStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = AssistantPreferencesStore(context)

    @Test
    fun `assistant reply name defaults to Grok`() = runTest {
        assertThat(store.assistantDisplayName())
            .isEqualTo(AssistantPreferencesStore.DEFAULT_ASSISTANT_NAME)
        assertThat(store.assistantDisplayName()).isEqualTo("Grok")
    }

    @Test
    fun `voice alias defaults enabled to Elon Musk and persists updates`() = runTest {
        assertThat(store.voiceAliasEnabled()).isTrue()
        assertThat(store.voiceAliasName())
            .isEqualTo(AssistantPreferencesStore.DEFAULT_VOICE_ALIAS_NAME)
        assertThat(store.voiceAliasName()).isEqualTo("Elon Musk")

        store.setVoiceAliasName("xAI Grok")
        store.setVoiceAliasEnabled(false)
        assertThat(store.voiceAliasName()).isEqualTo("xAI Grok")
        assertThat(store.voiceAliasEnabled()).isFalse()
    }
}
