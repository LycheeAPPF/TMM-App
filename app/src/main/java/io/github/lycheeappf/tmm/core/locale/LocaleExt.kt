package io.github.lycheeappf.tmm.core.locale

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Löst eine String-Ressource in der aktuell gewählten App-Sprache (per-app locale)
 * auf — unabhängig davon, ob die Prozess-Konfiguration ein frisch gesetztes Locale
 * schon angewandt hat. Funktioniert für Hilt-injizierte `@ApplicationContext` genauso
 * wie für reine Framework-Komponenten (Activity/Service/Receiver), die selbst einen
 * [Context] halten — KEIN Hilt-Entry-Point nötig.
 *
 * Die App-Locale ist die Quelle der Wahrheit (Framework-[LocaleManager], API 33+);
 * ist sie leer ("Systemsprache folgen"), wird die Config des Receiver-Context genutzt.
 */
fun Context.localizedString(@StringRes id: Int, vararg args: Any): String =
    localizedContext().getString(id, *args)

/** Wie [localizedString], aber für Plural-Ressourcen (`<plurals>`). */
fun Context.localizedQuantityString(@PluralsRes id: Int, quantity: Int, vararg args: Any): String =
    localizedContext().resources.getQuantityString(id, quantity, *args)

/**
 * Ein [Context], dessen Resources in der aktiven App-Locale auflösen. Liest das Locale
 * direkt aus dem [LocaleManager], statt sich auf die (ggf. noch nicht angewandte)
 * Prozess-Config zu verlassen.
 */
fun Context.localizedContext(): Context {
    val locales = getSystemService(LocaleManager::class.java)?.applicationLocales
    if (locales == null || locales.isEmpty) return this
    val config = Configuration(resources.configuration).apply { setLocales(locales) }
    return createConfigurationContext(config)
}
