package io.github.lycheeappf.tmm.core.util

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lycheeappf.tmm.MfsApplication
import io.github.lycheeappf.tmm.data.store.SettingsStore
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cost-Cap: verhindert, dass die App in einem Tag mehr Notifications ans Tesla
 * forwarded als das Settings-Budget erlaubt. Schützt vor Runaway-Loops (Messenger-Spam)
 * und vor unwahrscheinlichem Carrier-Routing der `+99942`-Test-Range.
 *
 * Zählt jeden erfolgreichen `injectIncoming`-Call. Bei Budget-Überlauf wird eine
 * persistente Notification gepostet und der Aufruf returnt false.
 */
@Singleton
class SendBudget @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore
) {

    private val mutex = kotlinx.coroutines.sync.Mutex()

    suspend fun checkAndIncrement(): Boolean {
        // Check + Increment werden über einen Mutex serialisiert, damit unter
        // gleichzeitig ankommenden Notifications zwei Aufrufer nicht beide den
        // Budget-Check passieren und dann jeweils inkrementieren (count overshoot).
        val budget = settingsStore.sendBudgetPerDay()
        return mutex.withLock {
            val current = settingsStore.dailySendCount()
            if (current >= budget) {
                postOverflowNotification(budget)
                false
            } else {
                settingsStore.incrementDailySendCount()
                true
            }
        }
    }

    /**
     * Rollback: zieht einen zuvor erhöhten Zähler wieder ab. Wird gerufen wenn
     * der nachfolgende Insert/Process-Schritt fehlschlug — sonst würden
     * fehlgeschlagene Inserts das Tagesbudget unnötig verbrennen.
     *
     * Läuft unter demselben [mutex] wie [checkAndIncrement], damit ein parallel
     * laufender check+inc keinen stale Count sieht.
     */
    suspend fun rollback() {
        mutex.withLock { settingsStore.decrementDailySendCount() }
    }

    private fun postOverflowNotification(budget: Int) {
        val nm = context.getSystemService<NotificationManager>() ?: return
        val notif = NotificationCompat.Builder(context, MfsApplication.CHANNEL_DIAGNOSTIC)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Send-Budget erreicht")
            .setContentText("Tageslimit von $budget Forwards aufgebraucht.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Tageslimit von $budget forwarded Notifications aufgebraucht. " +
                        "Weitere Nachrichten werden bis Mitternacht ignoriert. " +
                        "In den App-Einstellungen kann das Budget erhöht werden."
                )
            )
            .setOngoing(true)
            .build()
        try {
            nm.notify(NOTIF_ID, notif)
        } catch (e: SecurityException) {
            android.util.Log.e("SendBudget", "Budget-overflow notify failed (POST_NOTIFICATIONS)", e)
        }
    }

    companion object {
        private const val NOTIF_ID = 2001
    }
}
