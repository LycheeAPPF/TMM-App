package io.github.lycheeappf.tmm.listener

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import io.github.lycheeappf.tmm.channel.notification.NotificationCapture
import io.github.lycheeappf.tmm.core.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Empfängt Notifications vom System und delegiert an [NotificationCapture],
 * der die Filtering-, Extraction- und Inject-Logik orchestriert.
 *
 * Stellt zusätzlich eine statische Instanz-Referenz bereit, die der
 * [io.github.lycheeappf.tmm.channel.notification.PendingIntentRebuilder] nutzt,
 * um über [getActiveNotifications] frische PendingIntents zu extrahieren.
 */
@AndroidEntryPoint
class NotificationForwardingService : NotificationListenerService() {

    @Inject lateinit var capture: NotificationCapture

    @Inject @IoDispatcher
    lateinit var ioDispatcher: CoroutineDispatcher

    private val scope = CoroutineScope(SupervisorJob())

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "NotificationListener connected (active=${activeNotifications?.size ?: 0})")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        Log.w(TAG, "NotificationListener disconnected — Android attempts auto-rebind")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        scope.launch(ioDispatcher) {
            capture.onPosted(sbn)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MfsNotificationListener"

        @Volatile
        var instance: NotificationForwardingService? = null
            private set
    }
}
