package io.github.lycheeappf.tmm.sms.default_app

import java.io.ByteArrayOutputStream
import java.util.Locale

/** Ergebnis des Parsens einer `sms:`/`smsto:`-URI: Empfänger und optionaler Body. */
data class SmsUriParts(val recipient: String?, val body: String?)

/**
 * Reiner (JVM-testbarer) Parser für `sms:`/`smsto:`/`mms:`/`mmsto:`-URIs nach
 * RFC 5724: `sms:<recipient>?body=<percent-encoded>`.
 *
 * Bewusst OHNE `android.net.Uri`: dessen `schemeSpecificPart` liefert bei opaken
 * URIs den Query-Teil MIT — `?body=…` würde im Empfänger landen und der Text
 * verloren gehen. Hier gilt: Empfänger = Scheme-Specific-Part VOR dem `?`,
 * Body = URL-decodierter `body`-Query-Parameter.
 */
object SmsUriParser {

    private val SCHEMES = setOf("sms", "smsto", "mms", "mmsto")

    fun parse(uri: String?): SmsUriParts {
        if (uri.isNullOrBlank()) return SmsUriParts(null, null)
        val colon = uri.indexOf(':')
        if (colon <= 0) return SmsUriParts(null, null)
        if (uri.substring(0, colon).lowercase(Locale.ROOT) !in SCHEMES) {
            return SmsUriParts(null, null)
        }

        val rest = uri.substring(colon + 1)
        val queryStart = rest.indexOf('?')
        val rawRecipient = if (queryStart >= 0) rest.substring(0, queryStart) else rest
        // '+' im Empfänger ist ein Länderpräfix, KEIN codiertes Leerzeichen.
        val recipient = percentDecode(rawRecipient, plusIsSpace = false)
            .trim().takeIf { it.isNotEmpty() }

        val body = if (queryStart >= 0) {
            rest.substring(queryStart + 1)
                .split('&')
                .map { it.split('=', limit = 2) }
                .firstOrNull { it.size == 2 && it[0] == "body" }
                // Im Query-Wert ist '+' die übliche Space-Codierung
                // (analog `Uri.getQueryParameter`).
                ?.let { percentDecode(it[1], plusIsSpace = true) }
                ?.takeIf { it.isNotEmpty() }
        } else {
            null
        }

        return SmsUriParts(recipient, body)
    }

    /**
     * Prozent-Decoder ohne Android-Abhängigkeit (UTF-8, Multi-Byte-Sequenzen wie
     * `%C3%A4` → `ä`). Ungültige `%`-Sequenzen bleiben literal erhalten.
     */
    private fun percentDecode(value: String, plusIsSpace: Boolean): String {
        val bytes = ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '%' && i + 2 < value.length &&
                    isHex(value[i + 1]) && isHex(value[i + 2]) -> {
                    bytes.write(value.substring(i + 1, i + 3).toInt(16))
                    i += 3
                }
                plusIsSpace && c == '+' -> {
                    bytes.write(' '.code)
                    i++
                }
                else -> {
                    bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                    i++
                }
            }
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    private fun isHex(c: Char): Boolean = c.isDigit() || c in 'a'..'f' || c in 'A'..'F'
}
