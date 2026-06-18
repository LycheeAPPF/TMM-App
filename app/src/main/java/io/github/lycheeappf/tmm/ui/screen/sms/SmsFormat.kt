package io.github.lycheeappf.tmm.ui.screen.sms

import android.text.format.DateUtils
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Zeit-Formatierung für die SMS-Screens (lokalisiert über das System-Locale). */
internal object SmsFormat {

    /** Relativ („vor 5 Min", „Gestern") für die Konversationsliste. */
    fun relativeTime(millis: Long, now: Long = System.currentTimeMillis()): String =
        DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS).toString()

    /** Kurze Uhrzeit für Nachrichten-Bubbles. */
    fun clockTime(millis: Long): String =
        Instant.ofEpochMilli(millis)
            .atZone(ZoneId.systemDefault())
            .format(timeFormatter())

    /**
     * Pro Aufruf neu gebaut: ein Sprachwechsel (per-app locale, API 33+) rekreiert nur
     * die Activity, nicht den Prozess. Ein als `object val` gecachter Formatter würde
     * `Locale.getDefault()` beim Klassen-Load einfrieren und nach einem DE↔EN-Wechsel
     * eine veraltete (z. B. 12h- statt 24h-) Uhrzeit anzeigen. `Locale.getDefault()`
     * folgt dem aktiven App-Locale, daher hier bei jedem Aufruf frisch gelesen.
     */
    private fun timeFormatter(): DateTimeFormatter =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
}
