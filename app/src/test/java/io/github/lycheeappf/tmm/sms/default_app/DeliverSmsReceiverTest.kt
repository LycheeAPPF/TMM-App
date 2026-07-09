package io.github.lycheeappf.tmm.sms.default_app

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.MainActivity
import io.github.lycheeappf.tmm.MfsApplication
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Regression für den SMS-Empfangs-Pfad: die Notification muss als Tap-Target
 * den TMM-eigenen Thread-Deep-Link (MainActivity + EXTRA_THREAD_ID) tragen —
 * inklusive Propagation der beim Insert ermittelten threadId.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeliverSmsReceiverTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val receiver = DeliverSmsReceiver()

    @Before fun grantNotificationPermission() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun postedNotifications() =
        shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

    @Test fun `notification tap target is the MainActivity thread deep-link with the delivered threadId`() {
        receiver.postIncomingSmsNotification(context, "+4917012345", "hello", threadId = 42L)

        val posted = postedNotifications()
        assertThat(posted).hasSize(1)
        val saved = shadowOf(posted.first().contentIntent).savedIntent
        assertThat(saved.component?.className).isEqualTo(MainActivity::class.java.name)
        assertThat(saved.getLongExtra(MainActivity.EXTRA_THREAD_ID, 0L)).isEqualTo(42L)
    }

    @Test fun `unresolved threadId is propagated so the UI can fall back to the conversation list`() {
        receiver.postIncomingSmsNotification(context, "+4917012345", "hello", threadId = -1L)

        val posted = postedNotifications()
        assertThat(posted).hasSize(1)
        val saved = shadowOf(posted.first().contentIntent).savedIntent
        assertThat(saved.getLongExtra(MainActivity.EXTRA_THREAD_ID, 0L)).isEqualTo(-1L)
    }

    @Test fun `notification uses the fallback channel`() {
        receiver.postIncomingSmsNotification(context, "+4917012345", "hello", threadId = 1L)

        assertThat(postedNotifications().first().channelId)
            .isEqualTo(MfsApplication.CHANNEL_FALLBACK)
    }

    @Test fun `missing POST_NOTIFICATIONS permission skips the notification`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        receiver.postIncomingSmsNotification(context, "+4917012345", "hello", threadId = 42L)

        assertThat(postedNotifications()).isEmpty()
    }

    @Test fun `threadIntent contract carries target activity and thread id extra`() {
        val intent = MainActivity.threadIntent(context, 7L)

        assertThat(intent.component?.className).isEqualTo(MainActivity::class.java.name)
        assertThat(intent.getLongExtra(MainActivity.EXTRA_THREAD_ID, 0L)).isEqualTo(7L)
    }
}
