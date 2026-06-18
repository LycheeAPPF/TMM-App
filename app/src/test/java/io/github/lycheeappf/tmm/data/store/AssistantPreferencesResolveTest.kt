package io.github.lycheeappf.tmm.data.store

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

/**
 * Unit-Tests für die {driver}-Token-Auflösung. Pure Funktion ohne DataStore,
 * deckt den gefüllten Namen, den leeren (neutralen) Fall und die Default-
 * Konstanten in DE und EN ab — verhindert ein unaufgelöstes Token im Tesla-TTS.
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

    // ---- English ------------------------------------------------------------

    @Test fun `english blank name uses neutral english forms`() {
        val out = AssistantPreferencesStore.resolveDriverTemplate(
            "Hey {driver}, in {driver}'s Tesla {driver} watches the road.", "", Locale.ENGLISH
        )
        assertThat(out).isEqualTo("Hey, in the driver's Tesla the driver watches the road.")
    }

    @Test fun `english filled name replaces all driver tokens`() {
        val out = AssistantPreferencesStore.resolveDriverTemplate(
            "Hey {driver}, in {driver}'s Tesla {driver} drives.", "Alex", Locale.ENGLISH
        )
        assertThat(out).isEqualTo("Hey Alex, in Alex's Tesla Alex drives.")
    }

    @Test fun `default english welcome renders cleanly with and without name`() {
        val withName = AssistantPreferencesStore.resolveDriverTemplate(
            AssistantPreferencesStore.DEFAULT_WELCOME_EN, "Alex", Locale.ENGLISH
        )
        assertThat(withName).startsWith("Hey Alex, this is Grok.")
        assertThat(withName).doesNotContain("{driver}")

        val noName = AssistantPreferencesStore.resolveDriverTemplate(
            AssistantPreferencesStore.DEFAULT_WELCOME_EN, "", Locale.ENGLISH
        )
        assertThat(noName).startsWith("Hey, this is Grok.")
        assertThat(noName).doesNotContain("{driver}")
    }

    @Test fun `default english system prompt has no leftover token in either case`() {
        val withName = AssistantPreferencesStore.resolveDriverTemplate(
            AssistantPreferencesStore.DEFAULT_SYSTEM_PROMPT_EN, "Alex", Locale.ENGLISH
        )
        assertThat(withName).contains("in Alex's Tesla.")
        assertThat(withName).doesNotContain("{driver}")

        val noName = AssistantPreferencesStore.resolveDriverTemplate(
            AssistantPreferencesStore.DEFAULT_SYSTEM_PROMPT_EN, "", Locale.ENGLISH
        )
        assertThat(noName).contains("in the driver's Tesla.")
        assertThat(noName).doesNotContain("{driver}")
    }
}
