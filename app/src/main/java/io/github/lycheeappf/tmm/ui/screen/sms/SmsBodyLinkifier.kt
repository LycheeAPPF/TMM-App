package io.github.lycheeappf.tmm.ui.screen.sms

import android.util.Patterns
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString

/**
 * Wandelt einen SMS-Body in einen AnnotatedString, in dem erkannte Web-URLs
 * als [LinkAnnotation.Url] markiert sind — Compose `Text` macht sie damit
 * über den Default-[androidx.compose.ui.platform.LocalUriHandler] tappbar.
 * Scheme-lose Treffer ("example.com") bekommen https:// vorangestellt, sonst
 * kann ACTION_VIEW sie nicht öffnen. Kein Composable: pur & testbar.
 */
fun linkifySmsBody(body: String, linkStyle: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(body)
    val matcher = Patterns.WEB_URL.matcher(body)
    while (matcher.find()) {
        val start = matcher.start()
        val end = matcher.end()
        val raw = body.substring(start, end)
        val url = if (raw.contains("://")) raw else "https://$raw"
        addLink(
            LinkAnnotation.Url(url = url, styles = TextLinkStyles(style = linkStyle)),
            start,
            end
        )
    }
}
