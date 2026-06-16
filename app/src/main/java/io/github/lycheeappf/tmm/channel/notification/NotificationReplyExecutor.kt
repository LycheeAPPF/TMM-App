package io.github.lycheeappf.tmm.channel.notification

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lycheeappf.tmm.domain.channel.ChannelPayload
import io.github.lycheeappf.tmm.domain.reply.ReplyResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Triggert die RemoteInput-Action einer gespeicherten Notification mit dem
 * Tesla-Reply-Text.
 *
 * - Primär: ActionCache.get(notificationKey) liefert die gecachte Action.
 * - Fallback: PendingIntentRebuilder iteriert activeNotifications.
 * - Bei Misserfolg: postFallback (Notification mit "Tap to Copy" Reply-Text).
 */
@Singleton
class NotificationReplyExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val actionCache: ActionCache,
    private val rebuilder: PendingIntentRebuilder,
    private val fallbackNotifier: FallbackNotifier
) {

    suspend fun reply(
        payload: ChannelPayload.Notification,
        mappingId: Long,
        text: String
    ): ReplyResult {
        val resolved = actionCache.get(payload.notificationKey)
            ?: rebuilder.rebuild(payload)
            ?: run {
                Log.w(TAG, "No action available for ${payload.notificationKey}")
                fallbackNotifier.post(payload, text)
                return ReplyResult.NoActionAvailable
            }

        if (resolved.remoteInputs.isEmpty()) {
            fallbackNotifier.post(payload, text)
            return ReplyResult.NoRemoteInput
        }

        // PI-Mutability-Check: RemoteInput-fill-in braucht einen mutable
        // PendingIntent. Bei IMMUTABLE würde Android den Text silent dropen.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && resolved.actionIntent.isImmutable) {
            Log.w(TAG, "PendingIntent is IMMUTABLE — RemoteInput would silently drop. Fallback.")
            actionCache.remove(payload.notificationKey)
            fallbackNotifier.post(payload, text)
            return ReplyResult.PendingIntentCanceled
        }

        val intent = Intent()
        val bundle = Bundle().apply {
            resolved.remoteInputs.forEach { ri -> putCharSequence(ri.resultKey, text) }
        }
        RemoteInput.addResultsToIntent(resolved.remoteInputs.toTypedArray(), intent, bundle)
        RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)

        return try {
            resolved.actionIntent.send(context, 0, intent)
            Log.i(TAG, "Reply sent via RemoteInput (notif=${payload.notificationKey}, mapping=$mappingId)")
            ReplyResult.Success
        } catch (e: PendingIntent.CanceledException) {
            Log.w(TAG, "PendingIntent canceled for ${payload.notificationKey}", e)
            // Canceled = PI ist tot. Cache aufräumen damit folgende Replies
            // nicht in derselben Sackgasse landen.
            actionCache.remove(payload.notificationKey)
            fallbackNotifier.post(payload, text)
            ReplyResult.PendingIntentCanceled
        } catch (e: Exception) {
            Log.e(TAG, "Reply trigger failed", e)
            fallbackNotifier.post(payload, text)
            ReplyResult.ProviderError(e.message ?: "Unknown")
        }
    }

    companion object {
        private const val TAG = "ReplyExecutor"
    }
}
