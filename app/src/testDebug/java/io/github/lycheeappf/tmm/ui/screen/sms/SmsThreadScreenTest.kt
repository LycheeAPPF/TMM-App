package io.github.lycheeappf.tmm.ui.screen.sms

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.domain.sms.SmsDirection
import io.github.lycheeappf.tmm.domain.sms.SmsMessage
import io.github.lycheeappf.tmm.ui.theme.MfsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmsThreadScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val message = SmsMessage(
        id = 1L,
        threadId = 7L,
        address = "+491701234567",
        body = "Treffen um 18:30 am Haupteingang",
        date = 1_720_000_000_000L,
        direction = SmsDirection.INBOX,
        read = true
    )

    @Test
    fun `long press on message bubble copies body to clipboard`() {
        // Clipboard über den Composition-Context lesen, nicht über den
        // Application-Context: Robolectric hält Shadow-State pro
        // ClipboardManager-Instanz, und LocalClipboardManager schreibt in die
        // Instanz des Host-Activity-Contexts.
        lateinit var composeContext: Context
        compose.setContent {
            composeContext = LocalContext.current
            MfsTheme { MessageBubble(message) }
        }

        compose.onNodeWithText(message.body).performTouchInput { longClick() }

        val clipboard = composeContext.getSystemService(ClipboardManager::class.java)
        val copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        assertThat(copied).isEqualTo(message.body)
    }
}
