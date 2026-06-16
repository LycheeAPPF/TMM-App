package io.github.lycheeappf.tmm.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.github.lycheeappf.tmm.work.HealthCheckWorker

/**
 * Boot/App-Update-Receiver. Stößt einen einmaligen Health-Check an, sodass
 * Permission-Verluste (Default-SMS-Role, NLS) bemerkt werden.
 *
 * Der OutboundSmsObserver wird automatisch durch [io.github.lycheeappf.tmm.MfsApplication.onCreate]
 * registriert sobald die App nach dem Boot das nächste Mal vom NLS aufgeweckt wird.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "BootReceiver fired: ${intent.action}")
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<HealthCheckWorker>().build()
        )
    }

    companion object {
        private const val TAG = "MfsBootReceiver"
    }
}
