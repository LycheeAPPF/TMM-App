package io.github.lycheeappf.tmm.channel.llm

import javax.inject.Inject
import javax.inject.Singleton

/**
 * TTS-freundliches Bereinigen der LLM-Antwort:
 *  - Code-Blöcke entfernen (Tesla TTS würde "Tilde Tilde Tilde" vorlesen)
 *  - Inline-Code → Klartext
 *  - **bold**, *italic*, __underline__ → Klartext
 *  - Markdown-Headings entfernen
 *  - Bullet-Punkte ("- ", "* ") zu Komma-getrenntem Fließtext joinen
 *  - Doppelte Newlines zu ". " ersetzen
 *  - Auf [MAX_CHARS] kürzen (Tesla MAP-Frames sind in der Praxis bei ~1000 Zeichen problematisch)
 */
@Singleton
class LlmResponseFormatter @Inject constructor() {

    fun format(raw: String): String {
        if (raw.isBlank()) return ""
        var s = raw
        // Fenced code blocks weg — vor Inline-Code!
        s = FENCED_CODE.replace(s, " ")
        // Inline-Code: `xxx` → xxx
        s = INLINE_CODE.replace(s) { it.groupValues[1] }
        // **bold** / __bold__ → bold (alternation hat zwei groups, eine ist immer leer)
        s = BOLD.replace(s) { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }
        // *italic* / _italic_ → italic
        s = ITALIC.replace(s) { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }
        // Headings: "## Foo" → "Foo"
        s = HEADING.replace(s, "")
        // Bullet-Listen → flowing
        s = BULLET.replace(s, "")
        // Multi-Newlines → ". "
        s = MULTI_NEWLINE.replace(s, ". ")
        // Single-Newline → space
        s = s.replace("\n", " ")
        // Mehrfach-Whitespace → single space
        s = MULTI_SPACE.replace(s, " ")
        s = s.trim()
        if (s.length > MAX_CHARS) {
            // Versuche an Satz-Grenze zu kürzen
            val cutoff = s.lastIndexOf(". ", startIndex = MAX_CHARS - 1)
            s = if (cutoff > MAX_CHARS / 2) s.substring(0, cutoff + 1)
            else s.substring(0, MAX_CHARS - 1) + "…"
        }
        return s
    }

    companion object {
        const val MAX_CHARS = 800

        private val FENCED_CODE = Regex("```[\\s\\S]*?```")
        private val INLINE_CODE = Regex("`([^`]+)`")
        private val BOLD = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__", RegexOption.DOT_MATCHES_ALL)
        private val ITALIC = Regex("(?<![*])\\*([^*\\n]+)\\*(?![*])|(?<![_])_([^_\\n]+)_(?![_])")
        private val HEADING = Regex("(?m)^#{1,6}\\s+")
        private val BULLET = Regex("(?m)^\\s*[-*+]\\s+")
        private val MULTI_NEWLINE = Regex("\\n{2,}")
        private val MULTI_SPACE = Regex("[ \\t]{2,}")
    }
}
