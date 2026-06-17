package io.github.lycheeappf.tmm.contact

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lycheeappf.tmm.core.util.coRunCatching
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Erzwingt, dass der Tesla das Telefonbuch frisch über Bluetooth-PBAP zieht.
 *
 * „Sync zum Tesla" gibt es technisch nur als PULL: die App schreibt RawContacts,
 * der Tesla holt sie beim nächsten Connect. Ein blosses Kontakt-Delta (z.B. ein
 * umbenannter Kontakt) sieht das Auto erst, wenn der `account_changes`-Counter
 * steigt — der einzige im Code bewährte Hebel dafür ist [ContactSyncWriter.removeAccount].
 *
 * [force] löscht daher alle Bridge-Kontakte, entfernt den Account (Counter-Bump)
 * und lässt ihn vom [ContactBackfillWorker] neu aufbauen. Der Backfill ruft am
 * Ende [io.github.lycheeappf.tmm.channel.llm.AssistantContactProvisioner.reconcile],
 * sodass der Grok-Kontakt mit dem aktuellen Anzeigenamen wieder erscheint.
 *
 * Gemeinsame Quelle für den Settings-Reset-Button und den Grok-Namen-Schalter.
 */
@Singleton
class TeslaContactResync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contactSyncWriter: ContactSyncWriter
) {
    suspend fun force() {
        coRunCatching {
            contactSyncWriter.deleteAllContacts()
            contactSyncWriter.removeAccount()
            contactSyncWriter.ensureAccountAndVisibility()
        }
        ContactBackfillWorker.enqueue(context)
    }
}
