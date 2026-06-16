package io.github.lycheeappf.tmm.channel.llm

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LlmResponseFormatterTest {

    private val formatter = LlmResponseFormatter()

    @Test fun `blank stays blank`() {
        assertThat(formatter.format("")).isEmpty()
        assertThat(formatter.format("   \n  ")).isEmpty()
    }

    @Test fun `strips fenced code blocks`() {
        val input = "Hier ist Code:\n```kotlin\nval x = 1\n```\nUnd dann mehr."
        val out = formatter.format(input)
        assertThat(out).contains("Hier ist Code")
        assertThat(out).contains("Und dann mehr")
        assertThat(out).doesNotContain("```")
        assertThat(out).doesNotContain("val x")
    }

    @Test fun `strips inline code`() {
        assertThat(formatter.format("Nimm `foo` und ruf `bar()` auf"))
            .isEqualTo("Nimm foo und ruf bar() auf")
    }

    @Test fun `strips bold markers`() {
        assertThat(formatter.format("Das ist **wichtig** für dich"))
            .isEqualTo("Das ist wichtig für dich")
        assertThat(formatter.format("Auch __so__ ist betont"))
            .isEqualTo("Auch so ist betont")
    }

    @Test fun `strips italic markers`() {
        assertThat(formatter.format("Es heißt *Hallo*, nicht _Tschüss_"))
            .isEqualTo("Es heißt Hallo, nicht Tschüss")
    }

    @Test fun `strips heading hash markers`() {
        assertThat(formatter.format("## Überschrift\nText"))
            .isEqualTo("Überschrift Text")
    }

    @Test fun `flattens bullet lists`() {
        val input = """
            Ich kann:
            - Punkt eins
            - Punkt zwei
            - Punkt drei
        """.trimIndent()
        val out = formatter.format(input)
        assertThat(out).contains("Punkt eins")
        assertThat(out).contains("Punkt zwei")
        assertThat(out).contains("Punkt drei")
        assertThat(out).doesNotContain("- ")
    }

    @Test fun `caps at max chars at sentence boundary if possible`() {
        val long = ("Das ist ein Test-Satz. ").repeat(80) // 80 * 22 = ~1760 chars
        val out = formatter.format(long)
        assertThat(out.length).isLessThan(LlmResponseFormatter.MAX_CHARS + 1)
        assertThat(out).endsWith(".")
    }

    @Test fun `caps at hard limit with ellipsis when no sentence break in window`() {
        val long = "X".repeat(2000)
        val out = formatter.format(long)
        assertThat(out.length).isAtMost(LlmResponseFormatter.MAX_CHARS)
        assertThat(out).endsWith("…")
    }

    @Test fun `multiple newlines become sentence separators`() {
        assertThat(formatter.format("Eins\n\nZwei\n\nDrei"))
            .isEqualTo("Eins. Zwei. Drei")
    }
}
