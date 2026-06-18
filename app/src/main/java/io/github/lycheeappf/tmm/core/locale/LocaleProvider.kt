package io.github.lycheeappf.tmm.core.locale

import java.util.Locale

/**
 * Abstrahiert die aktuell aktive App-Locale für Komponenten, die ihren Default
 * sprachabhängig wählen müssen (z.B. der Grok-System-Prompt / das Welcome in
 * [io.github.lycheeappf.tmm.data.store.AssistantPreferencesStore]).
 *
 * Fakebar in Tests — analog zur [io.github.lycheeappf.tmm.core.util.Clock]-Seam.
 * Production-Binding in `AppModule` (liest aus dem Framework-LocaleManager, fällt
 * auf [Locale.getDefault] zurück, wenn "Systemsprache folgen" aktiv ist).
 */
fun interface LocaleProvider {
    fun current(): Locale
}
