package io.github.lycheeappf.tmm.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.lycheeappf.tmm.data.db.ReplyHistoryDao
import io.github.lycheeappf.tmm.domain.repository.MappingRepository
import io.github.lycheeappf.tmm.sms.outbound.FailedRowCleaner
import java.util.concurrent.TimeUnit

/**
 * Periodischer Cleanup-Worker (6h Intervall):
 * 1. Mapping-Rows nach TTL-Expiry löschen
 * 2. FAILED-Outbox-Rows mit Fake-Adresse aus SMS-Provider löschen
 * 3. Alte Reply-History prunen (>30 Tage)
 */
@HiltWorker
class CleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val mappingRepository: MappingRepository,
    private val failedRowCleaner: FailedRowCleaner,
    private val replyHistoryDao: ReplyHistoryDao
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        val now = System.currentTimeMillis()
        mappingRepository.deleteExpired(now)
        failedRowCleaner.deleteAllFailedFakeRows()
        replyHistoryDao.pruneBefore(now - HISTORY_KEEP_MS)
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }

    companion object {
        const val NAME = "MfsCleanupWorker"
        val INTERVAL_HOURS = 6L
        val FLEX_HOURS = 1L
        val INTERVAL_MS: Long = TimeUnit.HOURS.toMillis(INTERVAL_HOURS)
        val FLEX_MS: Long = TimeUnit.HOURS.toMillis(FLEX_HOURS)
        val HISTORY_KEEP_MS: Long = TimeUnit.DAYS.toMillis(30)
    }
}
