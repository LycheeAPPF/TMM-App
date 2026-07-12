package io.github.lycheeappf.tmm.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Kanarienvogel für den Compose-UI-Test-Harness unter Robolectric:
 * schlägt dieser Test fehl, ist der Harness kaputt — nicht der App-Code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposeTestHarnessSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `compose rule renders text under robolectric`() {
        compose.setContent { Text("smoke") }
        compose.onNodeWithText("smoke").assertExists()
    }
}
