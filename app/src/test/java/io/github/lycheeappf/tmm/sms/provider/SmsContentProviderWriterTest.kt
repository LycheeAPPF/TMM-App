package io.github.lycheeappf.tmm.sms.provider

import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.data.store.SettingsStore
import org.junit.Test

/**
 * Reine String-Composer-Tests für die ADDRESS-Spalte. Stellt sicher, dass alle
 * drei [SettingsStore.displayMode]-Optionen das richtige Format produzieren und
 * dass die Sanitization weder die Number noch das Bracket-Anchor zerstört.
 */
class SmsContentProviderWriterTest {

    private val number = "+9994210000005"

    @Test fun `hybrid mode produces RFC-822-mailbox form`() {
        val result = SmsContentProviderWriter.composeDisplayAddressPure(
            displayName = "Grok",
            fakeAddress = number,
            mode = SettingsStore.DISPLAY_HYBRID
        )
        assertThat(result).isEqualTo("Grok <+9994210000005>")
    }

    @Test fun `padding mode injects 40 spaces between name and bracketed number`() {
        val result = SmsContentProviderWriter.composeDisplayAddressPure(
            displayName = "Grok",
            fakeAddress = number,
            mode = SettingsStore.DISPLAY_PADDING
        )
        assertThat(result).isEqualTo("Grok" + " ".repeat(40) + "<+9994210000005>")
        // Number bleibt als Bracket-Anchor erhalten — Reply-Pfad funktioniert
        assertThat(result).contains("<+9994210000005>")
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
        for (mode in listOf(
            SettingsStore.DISPLAY_HYBRID,
            SettingsStore.DISPLAY_PADDING,
            SettingsStore.DISPLAY_NUMERIC
        )) {
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
            mode = SettingsStore.DISPLAY_HYBRID
        )
        assertThat(result).isEqualTo(number)
    }

    @Test fun `RFC-mailbox-breaking chars are sanitized to spaces`() {
        // < > , ; " \ und Control-Chars dürfen das RFC-822-Format nicht brechen
        val result = SmsContentProviderWriter.composeDisplayAddressPure(
            displayName = "Anna<;Bob, \"Test\"\\\r\n\t",
            fakeAddress = number,
            mode = SettingsStore.DISPLAY_HYBRID
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
            mode = SettingsStore.DISPLAY_HYBRID
        )
        assertThat(result).isEqualTo("Anna Müller <+9994210000005>")
    }

    @Test fun `display name longer than max is truncated`() {
        val longName = "X".repeat(100)
        val result = SmsContentProviderWriter.composeDisplayAddressPure(
            displayName = longName,
            fakeAddress = number,
            mode = SettingsStore.DISPLAY_HYBRID
        )
        // Display-Teil ist auf MAX_DISPLAY_CHARS=40 capped
        val displayPart = result.substringBefore(" <")
        assertThat(displayPart).hasLength(SmsContentProviderWriter.MAX_DISPLAY_CHARS)
    }

    @Test fun `unknown mode falls back to hybrid form`() {
        val result = SmsContentProviderWriter.composeDisplayAddressPure(
            displayName = "Grok",
            fakeAddress = number,
            mode = "some_unknown_mode"
        )
        assertThat(result).isEqualTo("Grok <+9994210000005>")
    }

    @Test fun `padding number-suffix is parsable for reply routing`() {
        // Sanity: nach Tesla-Roundtrip strippt FakeAddress.parse alles außer +0-9
        val padded = SmsContentProviderWriter.composeDisplayAddressPure(
            displayName = "Anna",
            fakeAddress = number,
            mode = SettingsStore.DISPLAY_PADDING
        )
        val stripped = padded.replace(Regex("[^+0-9]"), "")
        assertThat(stripped).isEqualTo(number)
    }

    // --- displayMode-Fallback (Regression: vorher hart HYBRID, jetzt DEFAULT) ---

    @Test fun `displayMode read failure falls back to DEFAULT not HYBRID`() {
        val mode = SmsContentProviderWriter.displayModeOrFallback(
            Result.failure(RuntimeException("datastore unavailable"))
        )
        // Der frühere Bug: Fallback war hart DISPLAY_HYBRID → Tesla zeigte
        // "Grok <+999...>" obwohl der User Clean-Name (NUMERIC) wollte.
        assertThat(mode).isEqualTo(SettingsStore.DEFAULT_DISPLAY_MODE)
        assertThat(mode).isEqualTo(SettingsStore.DISPLAY_NUMERIC)
        assertThat(mode).isNotEqualTo(SettingsStore.DISPLAY_HYBRID)
    }

    @Test fun `displayMode success returns the read value`() {
        val mode = SmsContentProviderWriter.displayModeOrFallback(
            Result.success(SettingsStore.DISPLAY_HYBRID)
        )
        assertThat(mode).isEqualTo(SettingsStore.DISPLAY_HYBRID)
    }
}
