package io.github.lycheeappf.tmm.data.store

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit-Tests für die {driver}-Token-Auflösung. Pure Funktion ohne DataStore,
 * deckt den gefüllten Namen, den leeren (neutralen) Fall und die Default-
 * Konstanten ab — verhindert ein unaufgelöstes Token im Tesla-TTS.
 */
class AssistantPreferencesResolveTest {

    @Test fun `filled name replaces all driver tokens`() {
        val out = AssistantPreferencesStore.resolveDriverTemplate(
            "Hey {driver}, im Tesla von {driver} fährt {driver}.", "Alex"
        )
        assertThat(out).isEqualTo("Hey Alex, im Tesla von Alex fährt Alex.")
    }

    @Test fun `blank name uses neutral grammatical forms`() {
        val out = AssistantPreferencesStore.resolveDriverTemplate(
            "Hey {driver}, im Tesla von {driver} schaut {driver} auf die Straße.", ""
        )
        assertThat(out).isEqualTo("Hey, im Tesla des Fahrers schaut der Fahrer auf die Straße.")
    }

    @Test fun `name is trimmed before substitution`() {
        val out = AssistantPreferencesStore.resolveDriverTemplate("von {driver}", "  Alex  ")
        assertThat(out).isEqualTo("von Alex")
    }

    @Test fun `default welcome renders cleanly with and without name`() {
        val withName = AssistantPreferencesStore.resolveDriverTemplate(
            AssistantPreferencesStore.DEFAULT_WELCOME, "Alex"
        )
        assertThat(withName).startsWith("Hey Alex, hier ist Grok.")
        assertThat(withName).doesNotContain("{driver}")

        val noName = AssistantPreferencesStore.resolveDriverTemplate(
            AssistantPreferencesStore.DEFAULT_WELCOME, ""
        )
        assertThat(noName).startsWith("Hey, hier ist Grok.")
        assertThat(noName).doesNotContain("{driver}")
    }

    @Test fun `default system prompt has no leftover token in either case`() {
        val withName = AssistantPreferencesStore.resolveDriverTemplate(
            AssistantPreferencesStore.DEFAULT_SYSTEM_PROMPT, "Alex"
        )
        assertThat(withName).contains("im Tesla von Alex.")
        assertThat(withName).doesNotContain("{driver}")

        val noName = AssistantPreferencesStore.resolveDriverTemplate(
            AssistantPreferencesStore.DEFAULT_SYSTEM_PROMPT, ""
        )
        assertThat(noName).contains("im Tesla des Fahrers.")
        assertThat(noName).doesNotContain("{driver}")
    }
}
