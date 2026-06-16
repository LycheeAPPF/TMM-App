package io.github.lycheeappf.tmm.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Verkabelt die periodischen Worker via WorkManager.
 * Wird in Phase 5 vom Home/Onboarding-Screen aufgerufen, sobald Setup
 * abgeschlossen ist.
 */
@Singleton
class WorkScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun scheduleAll() {
        scheduleCleanup()
        scheduleHealthCheck()
    }

    private fun scheduleCleanup() {
        val request = PeriodicWorkRequestBuilder<CleanupWorker>(
            CleanupWorker.INTERVAL_HOURS, TimeUnit.HOURS,
            CleanupWorker.FLEX_HOURS, TimeUnit.HOURS
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            CleanupWorker.NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    private fun scheduleHealthCheck() {
        val request = PeriodicWorkRequestBuilder<HealthCheckWorker>(
            HealthCheckWorker.INTERVAL_HOURS, TimeUnit.HOURS
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            HealthCheckWorker.NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}
