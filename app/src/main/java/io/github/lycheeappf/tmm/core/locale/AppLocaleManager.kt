package io.github.lycheeappf.tmm.core.locale

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dünner, testbarer Wrapper um den Framework-[LocaleManager] (API 33+) für die
 * per-app-Sprachwahl.
 *
 * Das System ist die Quelle der Wahrheit: die Wahl persistiert über Neustarts,
 * bleibt mit dem OS-Sprach-Picker (Einstellungen › Apps › TMM › Sprache) synchron
 * und rekreiert laufende Activities automatisch. Das Locale wird daher bewusst
 * NICHT zusätzlich in DataStore gespiegelt (sonst zwei konkurrierende Schreiber).
 */
@Singleton
class AppLocaleManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val localeManager: LocaleManager?
        get() = context.getSystemService(LocaleManager::class.java)

    /** `""` == Systemsprache folgen; sonst ein BCP-47-Tag wie `"de"` oder `"en"`. */
    fun currentTag(): String {
        val locales = localeManager?.applicationLocales ?: LocaleList.getEmptyLocaleList()
        return if (locales.isEmpty) "" else locales[0].toLanguageTag()
    }

    /** `""` == Systemsprache folgen. Triggert (API 33+) automatisch Activity-Recreate. */
    fun setLanguageTag(tag: String) {
        localeManager?.applicationLocales =
            if (tag.isBlank()) LocaleList.getEmptyLocaleList()
            else LocaleList.forLanguageTags(tag)
    }
}
