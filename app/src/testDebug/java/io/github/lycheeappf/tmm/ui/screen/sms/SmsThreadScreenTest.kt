package io.github.lycheeappf.tmm.ui.screen.sms

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.domain.sms.SmsDirection
import io.github.lycheeappf.tmm.domain.sms.SmsMessage
import io.github.lycheeappf.tmm.ui.theme.MfsTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

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

    // Robolectric hat keine funktionsfähige Shadow für android.widget.Magnifier:
    // Startet SelectionContainer per Long-Press eine echte Selektion, versucht
    // Compose die native Lupen-Vorschau per PixelCopy zu erzeugen; das schlägt
    // unter Robolectric fehl, und der Cleanup-Pfad des *echten* Framework-Codes
    // wirft dabei eine reale NullPointerException
    // (android.widget.Magnifier$InternalPopupWindow.destroy, Magnifier.java:1280) -
    // unabhängig vom hier getesteten Verhalten. Compose entscheidet rein über
    // Build.VERSION.SDK_INT >= 28, ob die Lupe aktiviert wird
    // (Magnifier_androidKt.isPlatformMagnifierSupported / SelectionManager_androidKt.
    // selectionMagnifier - per Bytecode-Inspektion verifiziert). Die Lupe ist ein
    // von Selektion/System-Toolbar komplett unabhängiges Subsystem; das
    // Herabsetzen von SDK_INT für die Dauer der Long-Press-Geste umgeht nur die
    // kaputte Lupen-Emulation der Testumgebung, ohne die geprüfte Selektions-/
    // Toolbar-Logik zu verändern.
    @Before
    fun disablePlatformMagnifier() {
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 27)
    }

    @After
    fun restoreConfiguredSdk() {
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 33)
    }

    @Test
    fun `long press does not copy and opens no menu`() {
        compose.setContent {
            composeContext = LocalContext.current
            MfsTheme { MessageBubble(message) }
        }

        compose.onNodeWithText(message.body).performTouchInput { longClick() }

        val clipboard = composeContext.getSystemService(ClipboardManager::class.java)
        assertThat(clipboard.primaryClip).isNull()
        // Kein Dialog, der den Body ein zweites Mal rendert.
        compose.onAllNodesWithText(message.body).assertCountEquals(1)
    }

    @Test
    fun `long press starts native text selection`() {
        val fakeToolbar = RecordingTextToolbar()

        compose.setContent {
            composeContext = LocalContext.current
            CompositionLocalProvider(LocalTextToolbar provides fakeToolbar) {
                MfsTheme { MessageBubble(message) }
            }
        }

        compose.onNodeWithText(message.body).performTouchInput { longClick() }
        compose.waitForIdle()

        // showMenu() wird von der Selection-Engine der SelectionContainer erst
        // aufgerufen, wenn Long-Press tatsächlich eine Textselektion erzeugt hat
        // (statt z. B. nur ein Klick-Event auszulösen) - das ist der reale Beleg
        // für native Selektion statt eines Menüs/Dialogs.
        assertThat(fakeToolbar.showMenuCalled).isTrue()
    }

    // Zeichnet auf, ob die Selection-Engine das System-Auswahlmenü angefordert
    // hat, statt tatsächlich ein UI zu zeigen (Robolectric hat kein echtes
    // System-Selektionsmenü). Muss exakt das TextToolbar-Interface dieser
    // Compose-Version implementieren (androidx.compose.ui:ui 1.8.3): eine
    // abstrakte 4-Parameter-showMenu()-Überladung plus eine 5-Parameter-
    // Default-Überladung (onAutofillRequested), die auf die 4-Parameter-Version
    // delegiert und daher nicht überschrieben werden muss.
    private class RecordingTextToolbar : TextToolbar {
        var showMenuCalled = false
            private set

        override var status: TextToolbarStatus = TextToolbarStatus.Hidden
            private set

        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?
        ) {
            showMenuCalled = true
            status = TextToolbarStatus.Shown
        }

        override fun hide() {
            status = TextToolbarStatus.Hidden
        }
    }
}
