package io.github.lycheeappf.tmm.ui.screen.sms

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.R
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

    // Clipboard über den Composition-Context lesen, nicht über den
    // Application-Context: Robolectric hält Shadow-State pro
    // ClipboardManager-Instanz, und LocalClipboardManager schreibt in die
    // Instanz des Host-Activity-Contexts.
    private lateinit var composeContext: Context

    private fun setBubbleContent() {
        compose.setContent {
            composeContext = LocalContext.current
            MfsTheme { MessageBubble(message) }
        }
    }

    @Test
    fun `long press opens the actions menu instead of copying directly`() {
        setBubbleContent()

        compose.onNodeWithText(message.body).performTouchInput { longClick() }

        val clipboard = composeContext.getSystemService(ClipboardManager::class.java)
        assertThat(clipboard.primaryClip).isNull()
        compose.onNodeWithText(composeContext.getString(R.string.sms_thread_copy_action))
            .assertExists()
        compose.onNodeWithText(composeContext.getString(R.string.sms_thread_select_action))
            .assertExists()
    }

    @Test
    fun `copy action copies the full body to the clipboard`() {
        setBubbleContent()

        compose.onNodeWithText(message.body).performTouchInput { longClick() }
        compose.onNodeWithText(composeContext.getString(R.string.sms_thread_copy_action))
            .performClick()

        val clipboard = composeContext.getSystemService(ClipboardManager::class.java)
        val copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        assertThat(copied).isEqualTo(message.body)
    }

    @Test
    fun `select text action opens a dialog with the message body`() {
        setBubbleContent()

        compose.onNodeWithText(message.body).performTouchInput { longClick() }
        compose.onNodeWithText(composeContext.getString(R.string.sms_thread_select_action))
            .performClick()

        // Body erscheint jetzt zweimal: in der Bubble und im Selektions-Dialog.
        compose.onAllNodesWithText(message.body).assertCountEquals(2)
    }
}
