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

    @Test
    fun `emptied prompt stays empty at runtime even with search on`() = runTest {
        store.setSystemPrompt("")
        assertThat(store.systemPrompt(webSearch = true, xSearch = true)).isEmpty()
    }

    @Test
    fun `web and x search default off and persist`() = runTest {
        assertThat(store.webSearchEnabled()).isFalse()
        assertThat(store.xSearchEnabled()).isFalse()

        store.setWebSearchEnabled(true)
        store.setXSearchEnabled(true)
        assertThat(store.webSearchEnabled()).isTrue()
        assertThat(store.xSearchEnabled()).isTrue()
    }

    // Eigener Basis-Prompt je Klausel-Test → unabhängig von der (im selben
    // DataStore geteilten) Reihenfolge anderer Tests, die den Prompt verändern.
    @Test
    fun `system prompt without search keeps the no-realtime clause`() = runTest {
        store.setSystemPrompt("Basis.")
        val prompt = store.systemPrompt(webSearch = false, xSearch = false)
        assertThat(prompt).contains("Echtzeit")          // DE NO_SEARCH_CLAUSE
        assertThat(prompt).doesNotContain("live im Web")
    }

    @Test
    fun `system prompt with web search announces live web lookup`() = runTest {
        store.setSystemPrompt("Basis.")
        val prompt = store.systemPrompt(webSearch = true, xSearch = false)
        assertThat(prompt).contains("live im Web nachschlagen")
        assertThat(prompt).doesNotContain("und auf X")
    }

    @Test
    fun `system prompt with both searches announces web and X`() = runTest {
        store.setSystemPrompt("Basis.")
        val prompt = store.systemPrompt(webSearch = true, xSearch = true)
        assertThat(prompt).contains("live im Web und auf X")
    }

    @Test
    fun `system prompt search clause is localized to english`() = runTest {
        store.setSystemPrompt("Basis.")
        locale = Locale.ENGLISH
        assertThat(store.systemPrompt(webSearch = true, xSearch = false))
            .contains("live on the web")
        assertThat(store.systemPrompt(webSearch = false, xSearch = false))
            .contains("real time")
    }

    @Test
    fun `stored legacy default system-prompt migrates to the new default`() = runTest {
        store.setSystemPrompt(AssistantPreferencesStore.LEGACY_DEFAULT_SYSTEM_PROMPT)
        // Wird als Seed erkannt → liefert den NEUEN (umformulierten) Default, nicht den Legacy-Text.
        assertThat(store.systemPromptRaw())
            .isEqualTo(AssistantPreferencesStore.DEFAULT_SYSTEM_PROMPT)
        assertThat(store.systemPromptRaw())
            .isNotEqualTo(AssistantPreferencesStore.LEGACY_DEFAULT_SYSTEM_PROMPT)
    }

    @Test
    fun `stored legacy EN default system-prompt migrates to the new EN default`() = runTest {
        // Schützt die handgepflegte 27-zeilige EN-Legacy-Konstante vor stillem Drift.
        locale = Locale.ENGLISH
        store.setSystemPrompt(AssistantPreferencesStore.LEGACY_DEFAULT_SYSTEM_PROMPT_EN)
        assertThat(store.systemPromptRaw())
            .isEqualTo(AssistantPreferencesStore.DEFAULT_SYSTEM_PROMPT_EN)
        assertThat(store.systemPromptRaw())
            .isNotEqualTo(AssistantPreferencesStore.LEGACY_DEFAULT_SYSTEM_PROMPT_EN)
    }
}
