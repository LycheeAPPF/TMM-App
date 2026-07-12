package io.github.lycheeappf.tmm.ui.screen.sms

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SmsBodyLinkifierTest {

    private val style = SpanStyle()

    private fun urls(body: String): List<String> {
        val annotated = linkifySmsBody(body, style)
        return annotated.getLinkAnnotations(0, annotated.length)
            .map { (it.item as LinkAnnotation.Url).url }
    }

    @Test
    fun `plain text has no link annotations`() {
        assertThat(urls("nur Text ohne Link")).isEmpty()
    }

    @Test
    fun `https url is annotated as-is`() {
        assertThat(urls("schau mal https://example.com/pfad an"))
            .containsExactly("https://example.com/pfad")
    }

    @Test
    fun `scheme-less url gets https prefix`() {
        assertThat(urls("besuch example.com bitte"))
            .containsExactly("https://example.com")
    }

    @Test
    fun `multiple urls are all annotated`() {
        assertThat(urls("https://a.example.com und http://b.example.com"))
            .containsExactly("https://a.example.com", "http://b.example.com")
    }

    @Test
    fun `annotation range covers exactly the url text`() {
        val body = "vorne https://example.com hinten"
        val annotated = linkifySmsBody(body, style)
        val range = annotated.getLinkAnnotations(0, annotated.length).single()
        assertThat(body.substring(range.start, range.end)).isEqualTo("https://example.com")
        assertThat(annotated.text).isEqualTo(body)
    }

    @Test
    fun `scheme-less match with embedded url in query still gets https prefix`() {
        // Regression: Patterns.WEB_URL liefert hier EINEN zusammenhängenden Treffer
        // "example.com/redirect?u=http://evil.com" — der enthält "://", beginnt aber
        // nicht mit einem Scheme. Ein reiner contains-Check ließe die URI scheme-los
        // und damit für ACTION_VIEW tot.
        assertThat(urls("Track: example.com/redirect?u=http://evil.com bye"))
            .containsExactly("https://example.com/redirect?u=http://evil.com")
    }

    @Test
    fun `annotation range covers exactly the scheme-less url text`() {
        val body = "vorne example.com hinten"
        val annotated = linkifySmsBody(body, style)
        val range = annotated.getLinkAnnotations(0, annotated.length).single()
        assertThat(body.substring(range.start, range.end)).isEqualTo("example.com")
        assertThat(annotated.text).isEqualTo(body)
    }
}
