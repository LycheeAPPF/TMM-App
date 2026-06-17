package io.github.lycheeappf.tmm.ui.screen.sms

import android.text.format.DateUtils
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Zeit-Formatierung für die SMS-Screens (lokalisiert über das System-Locale). */
internal object SmsFormat {

    /** Relativ („vor 5 Min", „Gestern") für die Konversationsliste. */
    fun relativeTime(millis: Long, now: Long = System.currentTimeMillis()): String =
        DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS).toString()

    /** Kurze Uhrzeit für Nachrichten-Bubbles. */
    fun clockTime(millis: Long): String =
        Instant.ofEpochMilli(millis)
            .atZone(ZoneId.systemDefault())
            .format(TIME_FORMATTER)

    private val TIME_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
}
