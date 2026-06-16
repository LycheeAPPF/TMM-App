package io.github.lycheeappf.tmm.listener.filter

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MessagingStyleExtractorTest {

    private val extractor = MessagingStyleExtractor()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `extracts sender and body from EXTRA_TITLE and EXTRA_TEXT fallback`() {
        val notif = NotificationCompat.Builder(context, "ch")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Anna")
            .setContentText("Hallo!")
            .build()

        val result = extractor.extractFromNotification("com.whatsapp", notif)
        assertThat(result).isNotNull()
        assertThat(result!!.senderName).isEqualTo("Anna")
        assertThat(result.body).isEqualTo("Hallo!")
        assertThat(result.conversationKey).startsWith("com.whatsapp::")
    }

    @Test
    fun `returns null when notification has no usable extras`() {
        val notif = NotificationCompat.Builder(context, "ch")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        assertThat(extractor.extractFromNotification("com.whatsapp", notif)).isNull()
    }

    @Test
    fun `returns null when notification is null`() {
        assertThat(extractor.extractFromNotification("com.whatsapp", null)).isNull()
    }

    @Test
    fun `conversation key is stable for same package + title`() {
        val notif1 = buildNotif("Anna", "Erste Nachricht")
        val notif2 = buildNotif("Anna", "Zweite Nachricht")
        val key1 = extractor.extractFromNotification("com.whatsapp", notif1)?.conversationKey
        val key2 = extractor.extractFromNotification("com.whatsapp", notif2)?.conversationKey
        assertThat(key1).isEqualTo(key2)
    }

    @Test
    fun `conversation key differs between different senders in same app`() {
        val notifAnna = buildNotif("Anna", "Hi")
        val notifMax = buildNotif("Max", "Hi")
        val keyAnna = extractor.extractFromNotification("com.whatsapp", notifAnna)?.conversationKey
        val keyMax = extractor.extractFromNotification("com.whatsapp", notifMax)?.conversationKey
        assertThat(keyAnna).isNotEqualTo(keyMax)
    }

    @Test
    fun `conversation key differs between different apps even with same title`() {
        val notif = buildNotif("Anna", "Hi")
        val keyWhatsapp = extractor.extractFromNotification("com.whatsapp", notif)?.conversationKey
        val keyOther = extractor.extractFromNotification("com.other.app", notif)?.conversationKey
        assertThat(keyWhatsapp).isNotEqualTo(keyOther)
    }

    @Test
    fun `falls back to EXTRA_BIG_TEXT when EXTRA_TEXT missing`() {
        val notif = NotificationCompat.Builder(context, "ch")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Anna")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Längerer Text"))
            .build()
        val result = extractor.extractFromNotification("com.whatsapp", notif)
        assertThat(result?.body).isEqualTo("Längerer Text")
    }

    @Test
    fun `MessagingStyle takes precedence over EXTRA_TITLE fallback`() {
        val msgStyle = NotificationCompat.MessagingStyle("MyName")
            .setConversationTitle("Anna Chat")
            .addMessage("Hallo!", System.currentTimeMillis(),
                androidx.core.app.Person.Builder().setName("Anna").build())
        val notif = NotificationCompat.Builder(context, "ch")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("WRONG_TITLE")
            .setStyle(msgStyle)
            .build()
        val result = extractor.extractFromNotification("com.whatsapp", notif)
        assertThat(result).isNotNull()
        assertThat(result!!.senderName).isEqualTo("Anna")
        assertThat(result.body).isEqualTo("Hallo!")
        assertThat(result.conversationLabel).isEqualTo("Anna Chat")
    }

    @Test
    fun `1-to-1 chat with conversationTitle is not treated as group`() {
        // WhatsApp/Signal/Telegram setzen conversationTitle auch für Einzelchats.
        // Das darf NICHT als Gruppe gelten, sonst liest Tesla "Anna: hallo".
        val msgStyle = NotificationCompat.MessagingStyle("MyName")
            .setConversationTitle("Anna")
            .addMessage("Hallo!", System.currentTimeMillis(),
                androidx.core.app.Person.Builder().setName("Anna").build())
        val notif = NotificationCompat.Builder(context, "ch")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setStyle(msgStyle)
            .build()
        val result = extractor.extractFromNotification("com.whatsapp", notif)
        assertThat(result).isNotNull()
        assertThat(result!!.isGroup).isFalse()
    }

    @Test
    fun `group conversation is detected via isGroupConversation flag`() {
        val msgStyle = NotificationCompat.MessagingStyle("MyName")
            .setConversationTitle("Familie")
            .setGroupConversation(true)
            .addMessage("Hallo!", System.currentTimeMillis(),
                androidx.core.app.Person.Builder().setName("Anna").build())
        val notif = NotificationCompat.Builder(context, "ch")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setStyle(msgStyle)
            .build()
        val result = extractor.extractFromNotification("com.whatsapp", notif)
        assertThat(result).isNotNull()
        assertThat(result!!.isGroup).isTrue()
    }

    private fun buildNotif(title: String, text: String): Notification =
        NotificationCompat.Builder(context, "ch")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .build()
}
