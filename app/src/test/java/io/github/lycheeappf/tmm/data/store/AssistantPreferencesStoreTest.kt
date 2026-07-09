package io.github.lycheeappf.tmm.data.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.platform.location.LocationFix
import kotlinx.coroutines.flow.first
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
        // Suspend-Accessor UND Flow-Variante müssen VOR dem Setzen geprüft werden
        // (der DataStore ist testübergreifend geteilt — ein eigener Test nach dem
        // Persist-Teil sähe die mutierten Werte). Schützt vor einem erneuten
        // Auseinanderlaufen der Defaults (der Suspend-Default war stillschweigend
        // auf true gekippt worden).
        assertThat(store.webSearchEnabled()).isFalse()
        assertThat(store.xSearchEnabled()).isFalse()
        assertThat(store.webSearchEnabledFlow().first()).isFalse()
        assertThat(store.xSearchEnabledFlow().first()).isFalse()

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
    fun `location context defaults off and persists`() = runTest {
        assertThat(store.locationContextEnabled()).isFalse()

        store.setLocationContextEnabled(true)
        assertThat(store.locationContextEnabled()).isTrue()
        store.setLocationContextEnabled(false)
        assertThat(store.locationContextEnabled()).isFalse()
    }

    @Test
    fun `german location clause uses US decimal dots and O for east`() = runTest {
        store.setSystemPrompt("Basis.")
        // Default-Locale bewusst auf GERMANY drehen: würde locationClause die
        // Default-Locale statt Locale.US formatieren, käme "48,1373" heraus.
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            val prompt = store.systemPrompt(
                webSearch = false,
                xSearch = false,
                location = LocationFix(latitude = 48.137254, longitude = 11.575382, accuracyInMeters = 12.7f)
            )
            assertThat(prompt).contains("48.1373° N, 11.5754° O")
            assertThat(prompt).contains("ca. 12 m")
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `english location clause uses S and E for southern-east coordinates`() = runTest {
        locale = Locale.ENGLISH
        store.setSystemPrompt("Base.")
        val prompt = store.systemPrompt(
            webSearch = false,
            xSearch = false,
            location = LocationFix(latitude = -33.868819, longitude = 151.209295, accuracyInMeters = 25.9f)
        )
        assertThat(prompt).contains("33.8688° S, 151.2093° E")
        assertThat(prompt).contains("about 25 m")
    }

    @Test
    fun `location clause truncates to four decimals and marks western longitude`() = runTest {
        store.setSystemPrompt("Basis.")
        val prompt = store.systemPrompt(
            webSearch = false,
            xSearch = false,
            location = LocationFix(latitude = 40.71277761, longitude = -74.00601528, accuracyInMeters = 8.2f)
        )
        assertThat(prompt).contains("40.7128° N, 74.0060° W")
    }

    @Test
    fun `without a fix the system prompt carries no location clause`() = runTest {
        store.setSystemPrompt("Basis.")
        val prompt = store.systemPrompt(webSearch = false, xSearch = false, location = null)
        assertThat(prompt).doesNotContain("GPS-Position")
    }

    @Test
    fun `stored legacy4 silent-success default system-prompt migrates to the new default`() = runTest {
        // Der Vorgänger-Default wies Grok an, bei erfolgreicher Navigation zu schweigen —
        // eine leere Antwort wird aber als Fehler vorgelesen. Unverändert gespeicherte
        // Nutzer flippen auf den neuen „bestätige kurz mit Ziel"-Default.
        store.setSystemPrompt(AssistantPreferencesStore.LEGACY4_DEFAULT_SYSTEM_PROMPT)
        assertThat(store.systemPromptRaw())
            .isEqualTo(AssistantPreferencesStore.DEFAULT_SYSTEM_PROMPT)
        assertThat(store.isSystemPromptCustomized()).isFalse()
    }

    @Test
    fun `stored legacy4 EN default system-prompt migrates to the new EN default`() = runTest {
        locale = Locale.ENGLISH
        store.setSystemPrompt(AssistantPreferencesStore.LEGACY4_DEFAULT_SYSTEM_PROMPT_EN)
        assertThat(store.systemPromptRaw())
            .isEqualTo(AssistantPreferencesStore.DEFAULT_SYSTEM_PROMPT_EN)
        assertThat(store.isSystemPromptCustomized()).isFalse()
    }

    @Test
    fun `stored legacy3 default system-prompt migrates to the new default`() = runTest {
        // Vor-Vorgänger (Navigations-Tool ohne Websuche-Vorschritt, „Im Erfolgsfall
        // schweige") — unverändert gespeichert gilt weiterhin als Seed.
        store.setSystemPrompt(AssistantPreferencesStore.LEGACY3_DEFAULT_SYSTEM_PROMPT)
        assertThat(store.systemPromptRaw())
            .isEqualTo(AssistantPreferencesStore.DEFAULT_SYSTEM_PROMPT)
        assertThat(store.isSystemPromptCustomized()).isFalse()
    }

    @Test
    fun `stored legacy3 EN default system-prompt migrates to the new EN default`() = runTest {
        locale = Locale.ENGLISH
        store.setSystemPrompt(AssistantPreferencesStore.LEGACY3_DEFAULT_SYSTEM_PROMPT_EN)
        assertThat(store.systemPromptRaw())
            .isEqualTo(AssistantPreferencesStore.DEFAULT_SYSTEM_PROMPT_EN)
        assertThat(store.isSystemPromptCustomized()).isFalse()
    }

    @Test
    fun `stored legacy2 default system-prompt migrates to the new default`() = runTest {
        // Default aus der Zeit VOR dem Navigations-Tool („steuerst du nichts im Auto").
        store.setSystemPrompt(AssistantPreferencesStore.LEGACY2_DEFAULT_SYSTEM_PROMPT)
        assertThat(store.systemPromptRaw())
            .isEqualTo(AssistantPreferencesStore.DEFAULT_SYSTEM_PROMPT)
        assertThat(store.isSystemPromptCustomized()).isFalse()
    }

    @Test
    fun `stored legacy2 EN default system-prompt migrates to the new EN default`() = runTest {
        locale = Locale.ENGLISH
        store.setSystemPrompt(AssistantPreferencesStore.LEGACY2_DEFAULT_SYSTEM_PROMPT_EN)
        assertThat(store.systemPromptRaw())
            .isEqualTo(AssistantPreferencesStore.DEFAULT_SYSTEM_PROMPT_EN)
        assertThat(store.isSystemPromptCustomized()).isFalse()
    }

    @Test
    fun `stored legacy seed follows a locale switch like an unmodified default`() = runTest {
        // Ein Seed ist sprachneutral erkannt: alter DE-Default + Wechsel auf EN
        // liefert den NEUEN EN-Default (nicht den DE-Text, nicht den Legacy-Text).
        store.setSystemPrompt(AssistantPreferencesStore.LEGACY2_DEFAULT_SYSTEM_PROMPT)
        locale = Locale.ENGLISH
        assertThat(store.systemPromptRaw())
            .isEqualTo(AssistantPreferencesStore.DEFAULT_SYSTEM_PROMPT_EN)
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

    // ---- isSystemPromptCustomized + Reset -----------------------------------

    @Test
    fun `never-touched system-prompt does not count as customized`() = runTest {
        assertThat(store.isSystemPromptCustomized()).isFalse()
    }

    @Test
    fun `unmodified stored default does not count as customized in either locale`() = runTest {
        store.setSystemPrompt(AssistantPreferencesStore.DEFAULT_SYSTEM_PROMPT)
        assertThat(store.isSystemPromptCustomized()).isFalse()

        locale = Locale.ENGLISH
        assertThat(store.isSystemPromptCustomized()).isFalse()
    }

    @Test
    fun `genuinely custom system-prompt counts as customized`() = runTest {
        store.setSystemPrompt("Antworte immer in Reimen, {driver}.")
        assertThat(store.isSystemPromptCustomized()).isTrue()

        locale = Locale.ENGLISH
        assertThat(store.isSystemPromptCustomized()).isTrue()
    }

    @Test
    fun `deliberately emptied system-prompt counts as customized`() = runTest {
        // Ein bewusst geleertes Feld ist eine Nutzerentscheidung — der Reset-Button
        // in den Settings muss dafür anwählbar bleiben.
        store.setSystemPrompt("")
        assertThat(store.isSystemPromptCustomized()).isTrue()
    }

    @Test
    fun `reset clears a custom system-prompt back to the localized default`() = runTest {
        store.setSystemPrompt("Sei knapp und sprich wie ein Pirat.")

        store.resetSystemPromptToDefault()

        assertThat(store.systemPromptRaw())
            .isEqualTo(AssistantPreferencesStore.DEFAULT_SYSTEM_PROMPT)
        assertThat(store.isSystemPromptCustomized()).isFalse()
    }

    @Test
    fun `reset restores the default after a deliberately emptied prompt`() = runTest {
        store.setSystemPrompt("")

        store.resetSystemPromptToDefault()

        assertThat(store.systemPromptRaw())
            .isEqualTo(AssistantPreferencesStore.DEFAULT_SYSTEM_PROMPT)
        assertThat(store.isSystemPromptCustomized()).isFalse()
    }

    @Test
    fun `reset prompt follows a later locale switch like a fresh install`() = runTest {
        store.setSystemPrompt("Ganz eigener Text.")
        store.resetSystemPromptToDefault()

        locale = Locale.ENGLISH
        assertThat(store.systemPromptRaw())
            .isEqualTo(AssistantPreferencesStore.DEFAULT_SYSTEM_PROMPT_EN)
        locale = Locale.GERMAN
        assertThat(store.systemPromptRaw())
            .isEqualTo(AssistantPreferencesStore.DEFAULT_SYSTEM_PROMPT)
    }
}
