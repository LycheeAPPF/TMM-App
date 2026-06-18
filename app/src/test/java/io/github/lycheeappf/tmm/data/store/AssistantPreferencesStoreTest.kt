package io.github.lycheeappf.tmm.data.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Sichert den Antwort-Namen (fest „Grok"), den konfigurierbaren Sprach-Ansprech-
 * Kontakt (Default aktiv mit neutralem Namen „xAI Grok") und die Locale-abhängige
 * Default-Equality-Migration für System-Prompt + Welcome (DE↔EN-Flip vs. Erhalt
 * eigener Texte). DataStore braucht einen echten Context → Robolectric.
 *
 * Die App-Locale ist eine veränderliche [LocaleProvider]-Lambda (`{ locale }`), damit
 * ein Sprachwechsel auf demselben DataStore getestet werden kann.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AssistantPreferencesStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var locale: Locale = Locale.GERMAN
    private val store = AssistantPreferencesStore(context) { locale }

    @Test
    fun `assistant reply name defaults to Grok`() = runTest {
        assertThat(store.assistantDisplayName())
            .isEqualTo(AssistantPreferencesStore.DEFAULT_ASSISTANT_NAME)
        assertThat(store.assistantDisplayName()).isEqualTo("Grok")
    }

    @Test
    fun `voice alias defaults enabled to xAI Grok and persists updates`() = runTest {
        assertThat(store.voiceAliasEnabled()).isTrue()
        assertThat(store.voiceAliasName())
            .isEqualTo(AssistantPreferencesStore.DEFAULT_VOICE_ALIAS_NAME)
        assertThat(store.voiceAliasName()).isEqualTo("xAI Grok")

        store.setVoiceAliasName("Elon Musk")
        store.setVoiceAliasEnabled(false)
        assertThat(store.voiceAliasName()).isEqualTo("Elon Musk")
        assertThat(store.voiceAliasEnabled()).isFalse()
    }

    @Test
    fun `persisted unmodified system-prompt default flips with the app locale`() = runTest {
        // Ein nie angepasst gespeicherter DE-Default gilt als Seed → flippt mit der Sprache.
        store.setSystemPrompt(AssistantPreferencesStore.DEFAULT_SYSTEM_PROMPT)

        locale = Locale.ENGLISH
        assertThat(store.systemPromptRaw())
            .isEqualTo(AssistantPreferencesStore.DEFAULT_SYSTEM_PROMPT_EN)

        locale = Locale.GERMAN
        assertThat(store.systemPromptRaw())
            .isEqualTo(AssistantPreferencesStore.DEFAULT_SYSTEM_PROMPT)
    }

    @Test
    fun `persisted unmodified welcome default flips with the app locale`() = runTest {
        store.setWelcomeMessage(AssistantPreferencesStore.DEFAULT_WELCOME)

        locale = Locale.ENGLISH
        assertThat(store.welcomeMessageRaw())
            .isEqualTo(AssistantPreferencesStore.DEFAULT_WELCOME_EN)
    }

    @Test
    fun `genuinely custom system-prompt is preserved across a locale switch`() = runTest {
        val custom = "Sei knapp und sprich wie ein Pirat, {driver}."
        store.setSystemPrompt(custom)

        locale = Locale.ENGLISH
        assertThat(store.systemPromptRaw()).isEqualTo(custom)
        locale = Locale.GERMAN
        assertThat(store.systemPromptRaw()).isEqualTo(custom)
    }

    @Test
    fun `deliberately emptied prompt stays empty and does not fall back to a default`() = runTest {
        store.setSystemPrompt("")

        locale = Locale.ENGLISH
        assertThat(store.systemPromptRaw()).isEmpty()
        locale = Locale.GERMAN
        assertThat(store.systemPromptRaw()).isEmpty()
    }
}
