package io.github.lycheeappf.tmm.sms.default_app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Reiner JVM-Test für [SmsUriParser]: RFC-5724-`sms:`-URIs mit `?body=`,
 * Prozent-/Plus-Decoding, `smsto:`-Varianten und defensives Verhalten bei
 * kaputten Eingaben.
 */
class SmsUriParserTest {

    @Test fun `plain sms uri yields recipient and no body`() {
        val parts = SmsUriParser.parse("sms:+491701234567")

        assertThat(parts.recipient).isEqualTo("+491701234567")
        assertThat(parts.body).isNull()
    }

    @Test fun `smsto scheme is accepted`() {
        val parts = SmsUriParser.parse("smsto:+491701234567")

        assertThat(parts.recipient).isEqualTo("+491701234567")
        assertThat(parts.body).isNull()
    }

    @Test fun `mmsto scheme is accepted`() {
        val parts = SmsUriParser.parse("mmsto:5551234")

        assertThat(parts.recipient).isEqualTo("5551234")
    }

    @Test fun `body query parameter is split off the recipient and decoded`() {
        val parts = SmsUriParser.parse("sms:+491701234567?body=Hello%20World")

        assertThat(parts.recipient).isEqualTo("+491701234567")
        assertThat(parts.body).isEqualTo("Hello World")
    }

    @Test fun `body with utf-8 percent encoding decodes multi-byte sequences`() {
        val parts = SmsUriParser.parse("smsto:555?body=Gr%C3%BC%C3%9Fe%20aus%20K%C3%B6ln")

        assertThat(parts.body).isEqualTo("Grüße aus Köln")
    }

    @Test fun `plus in the body query decodes to a space`() {
        val parts = SmsUriParser.parse("sms:555?body=Hi+there")

        assertThat(parts.body).isEqualTo("Hi there")
    }

    @Test fun `plus in the recipient is preserved as country prefix`() {
        val parts = SmsUriParser.parse("sms:+49170?body=x")

        assertThat(parts.recipient).isEqualTo("+49170")
    }

    @Test fun `percent-encoded recipient is decoded`() {
        val parts = SmsUriParser.parse("sms:%2B491701234567")

        assertThat(parts.recipient).isEqualTo("+491701234567")
    }

    @Test fun `body is found among multiple query parameters`() {
        val parts = SmsUriParser.parse("sms:555?subject=ignored&body=Hi%20there&foo=bar")

        assertThat(parts.recipient).isEqualTo("555")
        assertThat(parts.body).isEqualTo("Hi there")
    }

    @Test fun `query without body parameter yields no body and clean recipient`() {
        val parts = SmsUriParser.parse("sms:555?subject=x")

        assertThat(parts.recipient).isEqualTo("555")
        assertThat(parts.body).isNull()
    }

    @Test fun `uri without recipient but with body yields only the body`() {
        val parts = SmsUriParser.parse("sms:?body=Hi")

        assertThat(parts.recipient).isNull()
        assertThat(parts.body).isEqualTo("Hi")
    }

    @Test fun `uppercase scheme is accepted`() {
        val parts = SmsUriParser.parse("SMSTO:555")

        assertThat(parts.recipient).isEqualTo("555")
    }

    @Test fun `non-sms scheme yields nothing`() {
        val parts = SmsUriParser.parse("https://example.com?body=x")

        assertThat(parts.recipient).isNull()
        assertThat(parts.body).isNull()
    }

    @Test fun `null and blank input yield nothing`() {
        assertThat(SmsUriParser.parse(null)).isEqualTo(SmsUriParts(null, null))
        assertThat(SmsUriParser.parse("  ")).isEqualTo(SmsUriParts(null, null))
        assertThat(SmsUriParser.parse("no-scheme")).isEqualTo(SmsUriParts(null, null))
    }

    @Test fun `invalid percent sequences stay literal instead of crashing`() {
        assertThat(SmsUriParser.parse("sms:555?body=100%25").body).isEqualTo("100%")
        assertThat(SmsUriParser.parse("sms:555?body=%zzx").body).isEqualTo("%zzx")
        assertThat(SmsUriParser.parse("sms:555?body=broken%2").body).isEqualTo("broken%2")
    }

    @Test fun `empty body parameter yields no body`() {
        val parts = SmsUriParser.parse("sms:555?body=")

        assertThat(parts.body).isNull()
    }
}
