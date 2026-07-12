package io.github.lycheeappf.tmm.channel.notification

import android.util.Log
import io.github.lycheeappf.tmm.core.util.LogBuffer
import io.github.lycheeappf.tmm.domain.channel.ChannelPayload
import io.github.lycheeappf.tmm.listener.NotificationForwardingService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fallback wenn der in-memory ActionCache leer ist (typisch nach App-Restart).
 * Iteriert die aktiven Notifications über den NLS und extrahiert eine frische
 * RemoteInput-Action.
 *
 * Limit: funktioniert nur, wenn die Notification noch in der Tray ist und nicht
 * vom User oder der Quell-App zurückgezogen wurde.
 */
@Singleton
class PendingIntentRebuilder @Inject constructor(
    private val resolver: ActionResolver,
    private val logBuffer: LogBuffer
) {

    fun rebuild(payload: ChannelPayload.Notification): ResolvedReplyAction? {
        val nls = NotificationForwardingService.instance
        if (nls == null) {
            Log.w(TAG, "NLS instance null - listener disconnected")
            logBuffer.warn(TAG, "rebuild NLS-null")
            return null
        }
        val active = try {
            nls.activeNotifications ?: emptyArray()
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS revoked / NLS access removed
            Log.e(TAG, "activeNotifications threw SecurityException", e)
            logBuffer.error(TAG, "rebuild activeNotifications SecurityException")
            return null
        } catch (e: IllegalStateException) {
            // NLS disconnected zwischen `instance != null` und dem Aufruf — TOCTOU.
            Log.w(TAG, "NLS disconnected mid-call", e)
            logBuffer.warn(TAG, "rebuild NLS disconnected mid-call")
            return null
        } catch (e: RuntimeException) {
            // Generischer Schutz — der Service kann je nach OEM auch andere
            // RuntimeException werfen (Binder-DeadObjectException etc.).
            Log.w(TAG, "activeNotifications failed", e)
            logBuffer.warn(TAG, "rebuild activeNotifications failed: ${e::class.simpleName}")
            return null
        }
        val match = active.firstOrNull { it.key == payload.notificationKey }
        if (match == null) {
            Log.w(TAG, "Notification ${payload.notificationKey} no longer active")
            logBuffer.warn(TAG, "rebuild notif=${payload.notificationKey} no-longer-active")
            return null
        }
        // Erst NACH findReplyAction loggen — die alte "found-action"-Zeile feuerte
        // vor dem Check und hat bei der Issue-Triage zweimal in die Irre geführt
        // (Notification aktiv ≠ Reply-Action vorhanden, s. Group-Summary).
        val action = resolver.findReplyAction(match.notification)
        if (action == null) {
            logBuffer.warn(TAG, "rebuild notif=${payload.notificationKey} active-but-no-action")
        } else {
            logBuffer.info(TAG, "rebuild notif=${payload.notificationKey} action-rebuilt")
        }
        return action
    }

    companion object {
        private const val TAG = "PIRebuilder"
    }
}
