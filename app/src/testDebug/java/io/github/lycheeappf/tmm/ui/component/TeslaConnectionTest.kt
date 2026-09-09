package io.github.lycheeappf.tmm.ui.component

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.domain.tesla.TeslaDevice
import io.github.lycheeappf.tmm.platform.bluetooth.PairedBtDevice
import io.github.lycheeappf.tmm.platform.bluetooth.TeslaDeviceStatus
import io.github.lycheeappf.tmm.ui.theme.MfsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose-Tests der Multi-Tesla-Karte und des Mehrfach-Pickers (EN = Default-Locale):
 * „Übernehmen" liefert genau die angehakten Geräte, Entfernen bleibt auch ohne
 * Bluetooth-Permission erreichbar (bestehender Vertrag), und Zeilen zeigen ihren
 * Zustand (nicht mehr gekoppelt / verbunden).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TeslaConnectionTest {

    @get:Rule
    val compose = createComposeRule()

    private val modelY = PairedBtDevice("AA:BB:CC:DD:EE:FF", "Model Y")
    private val model3 = PairedBtDevice("11:22:33:44:55:66", "Model 3")
    private val cybertruck = PairedBtDevice("99:88:77:66:55:44", "Cybertruck")

    @Test
    fun `picker apply returns exactly the checked devices`() {
        var confirmed: List<PairedBtDevice>? = null
        compose.setContent {
            MfsTheme {
                TeslaDevicePickerDialog(
                    devices = listOf(modelY, model3, cybertruck),
                    selectedAddresses = setOf(modelY.address),
                    loading = false,
                    onConfirm = { confirmed = it },
                    onCancel = {}
                )
            }
        }

        compose.onNodeWithText("Model 3").performClick()
        compose.onNodeWithText("Apply").performClick()

        assertThat(confirmed).containsExactly(modelY, model3).inOrder()
    }

    @Test
    fun `picker apply drops an unchecked device`() {
        var confirmed: List<PairedBtDevice>? = null
        compose.setContent {
            MfsTheme {
                TeslaDevicePickerDialog(
                    devices = listOf(modelY, model3),
                    selectedAddresses = setOf(modelY.address, model3.address),
                    loading = false,
                    onConfirm = { confirmed = it },
                    onCancel = {}
                )
            }
        }

        compose.onNodeWithText("Model Y").performClick()
        compose.onNodeWithText("Apply").performClick()

        assertThat(confirmed).containsExactly(model3)
    }

    @Test
    fun `remove stays available without bluetooth permission`() {
        var removed: String? = null
        compose.setContent {
            MfsTheme {
                TeslaConnectionCard(
                    devices = listOf(
                        TeslaDeviceStatus(TeslaDevice(modelY.address, modelY.name), missing = false, connected = false)
                    ),
                    hasPermission = false,
                    permanentlyDenied = false,
                    onGrantPermission = {},
                    onOpenAppSettings = {},
                    onSelectDevices = {},
                    onRemoveDevice = { removed = it }
                )
            }
        }

        compose.onNodeWithContentDescription("Remove").performClick()

        assertThat(removed).isEqualTo(modelY.address)
    }

    @Test
    fun `unpaired device shows the no longer paired hint`() {
        compose.setContent {
            MfsTheme {
                TeslaConnectionCard(
                    devices = listOf(
                        TeslaDeviceStatus(TeslaDevice(modelY.address, modelY.name), missing = true, connected = false)
                    ),
                    hasPermission = true,
                    permanentlyDenied = false,
                    onGrantPermission = {},
                    onOpenAppSettings = {},
                    onSelectDevices = {},
                    onRemoveDevice = {}
                )
            }
        }

        compose.onNodeWithText("No longer paired — pair again or remove it").assertIsDisplayed()
    }

    @Test
    fun `connected device shows the connected pill and its linked vehicle`() {
        compose.setContent {
            MfsTheme {
                TeslaConnectionCard(
                    devices = listOf(
                        TeslaDeviceStatus(
                            TeslaDevice(modelY.address, modelY.name, vin = "5YJ3E1EA7KF000001", vehicleId = 1L),
                            missing = false,
                            connected = true
                        )
                    ),
                    hasPermission = true,
                    permanentlyDenied = false,
                    onGrantPermission = {},
                    onOpenAppSettings = {},
                    onSelectDevices = {},
                    onRemoveDevice = {}
                )
            }
        }

        compose.onNodeWithText("Connected").assertIsDisplayed()
        // VIN maskiert (…letzte 4) — die volle VIN gehört nicht auf den Screen.
        compose.onNodeWithText("Vehicle: …0001").assertIsDisplayed()
    }
}
