package io.github.lycheeappf.tmm.ui.screen.onboarding

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lycheeappf.tmm.core.model.ChannelId
import io.github.lycheeappf.tmm.core.model.FakeAddress
import io.github.lycheeappf.tmm.data.store.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Verifiziert via SmsManager-Send, ob der Carrier eine SMS an die reservierte
 * Test-Adresse ohne Kosten ablehnt.
 *
 * Vorgehen:
 * 1. PreFlightCoordinator reserviert die Adresse → OutboundSmsObserver skippt sie
 * 2. SmsManager.sendTextMessage(...) mit einem `sentIntent`-BroadcastReceiver
 *    der das Result von Modem/Carrier capturt (RESULT_OK = SENT, andere = FAILED)
 * 3. Suspend bis Result oder Timeout (60s)
 *
 * Hintergrund: ContentProvider-Polling (alte Version) funktionierte nicht zuverlässig
 * weil SmsManager.sendTextMessage NICHT automatisch in `content://sms` schreibt —
 * das wäre Aufgabe der Default-SMS-App. Mit `sentIntent` bekommen wir das
 * Modem-Resultat direkt zurück.
 */
@Singleton
class PreFlightTester @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
    private val coordinator: PreFlightCoordinator
) {

    private val nonce = AtomicLong(0)

    suspend fun targetAddress(): String =
        FakeAddress(ChannelId.SYSTEM, 0).toE164(settingsStore.addressScheme())

    suspend fun run(): Result {
        settingsStore.setPreflightResult(SettingsStore.PREFLIGHT_RUNNING)
        val address = targetAddress()
        val sentAction = "$ACTION_PREFIX${System.currentTimeMillis()}_${nonce.incrementAndGet()}"

        coordinator.reserve(address, validForMs = TIMEOUT_MS + 30_000L)

        val resultDeferred = CompletableDeferred<Int>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                Log.i(TAG, "sentIntent received: resultCode=$resultCode action=${intent.action}")
                resultDeferred.complete(resultCode)
            }
        }

        try {
            val filter = IntentFilter(sentAction)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }

            val sendOk = sendTestSms(address, sentAction)
            if (!sendOk) {
                settingsStore.setPreflightResult(SettingsStore.PREFLIGHT_ERROR)
                return Result.Error("SmsManager.sendTextMessage threw exception")
            }

            val resultCode = withTimeoutOrNull(TIMEOUT_MS) { resultDeferred.await() }

            val outcome = when (resultCode) {
                null -> Result.Timeout.also {
                    settingsStore.setPreflightResult(SettingsStore.PREFLIGHT_TIMEOUT)
                }
                Activity.RESULT_OK -> Result.SentViaCarrier.also {
                    settingsStore.setPreflightResult(SettingsStore.PREFLIGHT_RISK)
                }
                else -> Result.SafelyRejected.also {
                    settingsStore.setPreflightResult(SettingsStore.PREFLIGHT_OK)
                }
            }
            Log.i(TAG, "Pre-flight for $address: $outcome (resultCode=$resultCode)")
            return outcome
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
            coordinator.release()
        }
    }

    private fun sendTestSms(address: String, sentAction: String): Boolean = try {
        val sentIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(sentAction).setPackage(context.packageName),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION") SmsManager.getDefault()
        }
        smsManager.sendTextMessage(address, null, "x", sentIntent, null)
        Log.i(TAG, "Pre-flight test SMS dispatched to $address (sentAction=$sentAction)")
        true
    } catch (e: Exception) {
        Log.e(TAG, "sendTestSms failed", e)
        false
    }

    sealed class Result {
        data object SafelyRejected : Result()
        data object SentViaCarrier : Result()
        data object Timeout : Result()
        data class Error(val message: String) : Result()
    }

    companion object {
        private const val TAG = "PreFlightTester"
        private const val ACTION_PREFIX = "io.github.lycheeappf.tmm.PREFLIGHT_SENT_"
        private const val TIMEOUT_MS = 60_000L
    }
}
