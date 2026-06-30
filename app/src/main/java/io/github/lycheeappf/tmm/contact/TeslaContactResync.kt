package io.github.lycheeappf.tmm.contact

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lycheeappf.tmm.channel.llm.AssistantContactProvisioner
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
 * [force] löscht alle Bridge-Kontakte, entfernt den Account (Counter-Bump) und
 * baut ihn sofort wieder auf. Grok + Sprach-Alias werden dabei SYNCHRON noch VOR
 * dem BackfillWorker via [AssistantContactProvisioner.reconcile] angelegt, damit
 * Tesla bei einem zeitnahen PBAP-Pull (ausgelöst durch den Counter-Bump) bereits
 * einen vollständigen Stand vorfindet. Der [ContactBackfillWorker] ergänzt danach
 * asynchron die Messenger-Kontakte (idempotent).
 *
 * Gemeinsame Quelle für den Settings-Reset-Button und den Grok-Namen-Schalter.
 */
@Singleton
class TeslaContactResync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contactSyncWriter: ContactSyncWriter,
    private val assistantProvisioner: AssistantContactProvisioner
) {
    suspend fun force() {
        coRunCatching {
            contactSyncWriter.deleteAllContacts()
            contactSyncWriter.removeAccount()
            contactSyncWriter.ensureAccountAndVisibility()
        }
        // Grok + Sprach-Alias sofort synchron (re-)provisionieren — noch VOR dem
        // asynchronen BackfillWorker. Der account_changes-Bump aus removeAccount()
        // kann Tesla zu einem sofortigen PBAP-Pull veranlassen; ohne diesen Call
        // würden beide Assistenten-Kontakte erst nach dem WorkManager-Scheduling-
        // Delay erscheinen und Tesla hätte ein leeres Telefonbuch heruntergeladen.
        coRunCatching { assistantProvisioner.reconcile() }
        ContactBackfillWorker.enqueue(context)
    }
}
