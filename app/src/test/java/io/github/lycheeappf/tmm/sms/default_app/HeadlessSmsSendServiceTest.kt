package io.github.lycheeappf.tmm.sms.default_app

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.MainActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HeadlessSmsSendServiceTest {

    @Test
    fun `quick-reply-failed notification opens own compose screen prefilled`() {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        // Robolectric grants POST_NOTIFICATIONS ungleichmäßig je nach Setup — hier
        // explizit gewähren, sonst bricht postQuickReplyFailedNotification() vor dem
        // notify()-Call ab und der Test scheitert an einem leeren Notification-Log
        // statt am eigentlichen (falschen) Ziel-Intent.
        shadowOf(application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

        val controller = Robolectric.buildService(
            HeadlessSmsSendService::class.java,
            Intent("android.intent.action.RESPOND_VIA_MESSAGE", Uri.parse("smsto:+491711234567"))
                .putExtra(Intent.EXTRA_TEXT, "Bin gleich da")
        )
        val service = controller.create().get()
        controller.startCommand(0, 1)

        val nm = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSystemService(NotificationManager::class.java)
        val posted = shadowOf(nm).allNotifications.single()

        val contentIntent: PendingIntent = posted.contentIntent
        val saved = shadowOf(contentIntent).savedIntent
        assertThat(saved.component?.className).isEqualTo(MainActivity::class.java.name)
        assertThat(saved.getStringExtra("compose_recipient")).isEqualTo("+491711234567")
        assertThat(saved.getStringExtra("compose_body")).isEqualTo("Bin gleich da")
    }
}
