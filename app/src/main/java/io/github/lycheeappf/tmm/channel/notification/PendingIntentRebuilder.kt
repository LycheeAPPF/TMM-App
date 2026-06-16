package io.github.lycheeappf.tmm.channel.notification

import android.util.Log
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
    private val resolver: ActionResolver
) {

    fun rebuild(payload: ChannelPayload.Notification): ResolvedReplyAction? {
        val nls = NotificationForwardingService.instance
        if (nls == null) {
            Log.w(TAG, "NLS instance null - listener disconnected")
            return null
        }
        val active = try {
            nls.activeNotifications ?: emptyArray()
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS revoked / NLS access removed
            Log.e(TAG, "activeNotifications threw SecurityException", e)
            return null
        } catch (e: IllegalStateException) {
            // NLS disconnected zwischen `instance != null` und dem Aufruf — TOCTOU.
            Log.w(TAG, "NLS disconnected mid-call", e)
            return null
        } catch (e: RuntimeException) {
            // Generischer Schutz — der Service kann je nach OEM auch andere
            // RuntimeException werfen (Binder-DeadObjectException etc.).
            Log.w(TAG, "activeNotifications failed", e)
            return null
        }
        val match = active.firstOrNull { it.key == payload.notificationKey }
        if (match == null) {
            Log.w(TAG, "Notification ${payload.notificationKey} no longer active")
            return null
        }
        return resolver.findReplyAction(match.notification)
    }

    companion object {
        private const val TAG = "PIRebuilder"
    }
}
