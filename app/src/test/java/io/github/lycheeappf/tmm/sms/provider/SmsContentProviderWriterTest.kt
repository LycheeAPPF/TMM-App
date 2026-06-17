package io.github.lycheeappf.tmm.sms.provider

import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.data.store.SettingsStore
import org.junit.Test

/**
 * Reine String-Composer-Tests für die ADDRESS-Spalte. Stellt sicher, dass der
 * [SettingsStore.DISPLAY_NUMERIC]-Modus die reine Nummer liefert, jeder andere
 * (Legacy-)Wert die `"Name <+number>"`-Bracket-Form, und dass die Sanitization
 * weder die Number noch das Bracket-Anchor zerstört.
 */
class SmsContentProviderWriterTest {

    private val number = "+9994210000005"

    /** Jeder Wert != [SettingsStore.DISPLAY_NUMERIC] trifft den Bracket-Form-Zweig. */
    private val bracketMode = "legacy"

    @Test fun `non-numeric mode produces RFC-822-mailbox bracket form`() {
        val result = SmsContentProviderWriter.composeDisplayAddressPure(
            displayName = "Grok",
            fakeAddress = number,
            mode = bracketMode
        )
        assertThat(result).isEqualTo("Grok <+9994210000005>")
    }

    @Test fun `numeric mode returns only the fake number regardless of displayName`() {
        val result = SmsContentProviderWriter.composeDisplayAddressPure(
            displayName = "Grok",
            fakeAddress = number,
            mode = SettingsStore.DISPLAY_NUMERIC
        )
        assertThat(result).isEqualTo("+9994210000005")
    }

    @Test fun `null displayName always returns pure number`() {
        for (mode in listOf(SettingsStore.DISPLAY_NUMERIC, bracketMode)) {
            val result = SmsContentProviderWriter.composeDisplayAddressPure(
                displayName = null, fakeAddress = number, mode = mode
            )
            assertThat(result).isEqualTo(number)
        }
    }

    @Test fun `blank displayName returns pure number`() {
        val result = SmsContentProviderWriter.composeDisplayAddressPure(
            displayName = "   ",
            fakeAddress = number,
            mode = bracketMode
        )
        assertThat(result).isEqualTo(number)
    }

    @Test fun `RFC-mailbox-breaking chars are sanitized to spaces`() {
        // < > , ; " \ und Control-Chars dürfen das RFC-822-Format nicht brechen
        val result = SmsContentProviderWriter.composeDisplayAddressPure(
            displayName = "Anna<;Bob, \"Test\"\\\r\n\t",
            fakeAddress = number,
            mode = bracketMode
        )
        // Keine der gefährlichen Chars sollte in der finalen Form sein außer
        // dem ein gewollten < > Bracket um die fakeAddress
        assertThat(result.substringBefore(" <")).doesNotContain("<")
        assertThat(result.substringBefore(" <")).doesNotContain(">")
        assertThat(result.substringBefore(" <")).doesNotContain(",")
        assertThat(result.substringBefore(" <")).doesNotContain(";")
        assertThat(result.substringBefore(" <")).doesNotContain("\"")
        assertThat(result.substringBefore(" <")).doesNotContain("\\")
        assertThat(result.substringBefore(" <")).doesNotContain("\r")
        assertThat(result.substringBefore(" <")).doesNotContain("\n")
        assertThat(result.substringBefore(" <")).doesNotContain("\t")
        assertThat(result).endsWith("<$number>")
    }

    @Test fun `multi-whitespace is collapsed`() {
        val result = SmsContentProviderWriter.composeDisplayAddressPure(
            displayName = "Anna    Müller",
            fakeAddress = number,
            mode = bracketMode
        )
        assertThat(result).isEqualTo("Anna Müller <+9994210000005>")
    }

    @Test fun `display name longer than max is truncated`() {
        val longName = "X".repeat(100)
        val result = SmsContentProviderWriter.composeDisplayAddressPure(
            displayName = longName,
            fakeAddress = number,
            mode = bracketMode
        )
        // Display-Teil ist auf MAX_DISPLAY_CHARS=40 capped
        val displayPart = result.substringBefore(" <")
        assertThat(displayPart).hasLength(SmsContentProviderWriter.MAX_DISPLAY_CHARS)
    }

    @Test fun `bracket-form number-suffix is parsable for reply routing`() {
        // Sanity: nach Tesla-Roundtrip strippt FakeAddress.parse alles außer +0-9
        val bracketed = SmsContentProviderWriter.composeDisplayAddressPure(
            displayName = "Anna",
            fakeAddress = number,
            mode = bracketMode
        )
        val stripped = bracketed.replace(Regex("[^+0-9]"), "")
        assertThat(stripped).isEqualTo(number)
    }

    // --- displayMode-Fallback (Regression: vorher hart Bracket-Modus, jetzt DEFAULT) ---

    @Test fun `displayMode read failure falls back to DEFAULT NUMERIC`() {
        val mode = SmsContentProviderWriter.displayModeOrFallback(
            Result.failure(RuntimeException("datastore unavailable"))
        )
        // Der frühere Bug: Fallback war hart ein Bracket-Modus → Tesla zeigte
        // "Grok <+999...>" obwohl der User Clean-Name (NUMERIC) wollte.
        assertThat(mode).isEqualTo(SettingsStore.DEFAULT_DISPLAY_MODE)
        assertThat(mode).isEqualTo(SettingsStore.DISPLAY_NUMERIC)
    }

    @Test fun `displayMode success returns the read value not the default`() {
        val mode = SmsContentProviderWriter.displayModeOrFallback(
            Result.success(bracketMode)
        )
        assertThat(mode).isEqualTo(bracketMode)
        assertThat(mode).isNotEqualTo(SettingsStore.DEFAULT_DISPLAY_MODE)
    }
}
