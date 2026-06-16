package io.github.lycheeappf.tmm.channel.notification

import android.app.Notification
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Findet die erste Direct-Reply-Action einer Notification, falls vorhanden.
 *
 * Eine Direct-Reply-Action hat:
 * - mindestens einen [android.app.RemoteInput] mit allowFreeFormInput=true
 * - eine PendingIntent (BroadcastIntent o.Ä.), die wir später triggern können
 */
@Singleton
class ActionResolver @Inject constructor() {

    fun findReplyAction(notification: Notification): ResolvedReplyAction? {
        val actions = notification.actions ?: return null
        for (action in actions) {
            val ri = action.remoteInputs?.filter { it.allowFreeFormInput }.orEmpty()
            if (ri.isNotEmpty()) {
                return ResolvedReplyAction(
                    actionIntent = action.actionIntent,
                    remoteInputs = ri,
                    capturedAt = System.currentTimeMillis()
                )
            }
        }
        return null
    }
}
