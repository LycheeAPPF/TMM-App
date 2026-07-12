package io.github.lycheeappf.tmm.ui.screen.settings

import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.R
import io.github.lycheeappf.tmm.contact.ContactSyncWriter
import io.github.lycheeappf.tmm.contact.TeslaContactResync
import io.github.lycheeappf.tmm.core.locale.AppLocaleManager
import io.github.lycheeappf.tmm.core.locale.localizedString
import io.github.lycheeappf.tmm.core.notification.AppNotificationChannels
import io.github.lycheeappf.tmm.data.store.SettingsStore
import io.github.lycheeappf.tmm.platform.bluetooth.BluetoothConnectionChecker
import io.github.lycheeappf.tmm.platform.permission.PermissionGate
import io.github.lycheeappf.tmm.platform.tesla.api.TeslaVehicleCommandClient
import io.github.lycheeappf.tmm.platform.tesla.api.VehicleInfo
import io.github.lycheeappf.tmm.platform.tesla.auth.TeslaAuthManager
import io.github.lycheeappf.tmm.platform.tesla.auth.TeslaAuthState
import io.github.lycheeappf.tmm.ui.screen.onboarding.PreFlightTester
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Sichert die i18n-tragende Verdrahtung von [SettingsViewModel.setLanguage]: der Umschalter
 * MUSS sowohl das App-Locale setzen ([AppLocaleManager.setLanguageTag]) ALS AUCH die
 * Notification-Channels neu anlegen ([AppNotificationChannels.ensure]) — sonst blieben die
 * Channel-Namen in der alten Sprache hängen. Außerdem spiegelt der UiState die aktive Sprache.
 *
 * Zusätzlich: die Tesla-Fleet-Auth-Pfade — Credentials-Präsenz im UiState, das
 * Connect-Gate ([SettingsViewModel.startTeslaLogin] ohne Credentials → kein Event)
 * und das Spiegeln des [TeslaAuthManager.state]-Flows inklusive des
 * Fahrzeug-Ladens NUR beim Übergang zu [TeslaAuthState.Authenticated].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val context = mockk<android.content.Context>(relaxed = true)
    private val store = mockk<SettingsStore>(relaxed = true)
    private val contactSyncWriter = mockk<ContactSyncWriter>(relaxed = true)
    private val teslaContactResync = mockk<TeslaContactResync>(relaxed = true)
    private val preFlightTester = mockk<PreFlightTester>(relaxed = true)
    private val appLocaleManager = mockk<AppLocaleManager>(relaxed = true)
    private val notificationChannels = mockk<AppNotificationChannels>(relaxed = true)
    private val diagnosticsExporter = mockk<io.github.lycheeappf.tmm.core.util.DiagnosticsExporter>(relaxed = true)
    private val permissionGate = mockk<PermissionGate>(relaxed = true)
    private val bluetoothConnectionChecker = mockk<BluetoothConnectionChecker>(relaxed = true)

    /** Test-getriebener Auth-State — Tests schieben hier Übergänge rein. */
    private val teslaAuthStateFlow = MutableStateFlow<TeslaAuthState>(TeslaAuthState.NotAuthenticated)

    // Konkrete Manager-Klasse: state wird im init des ViewModels collected —
    // einen echten Flow liefern, sonst hinge der Collector auf einem Mock-Flow.
    // Der Rest bleibt relaxed.
    private val teslaAuthManager = mockk<TeslaAuthManager>(relaxed = true) {
        every { state } returns teslaAuthStateFlow
    }
    private val teslaCommandClient = mockk<TeslaVehicleCommandClient>(relaxed = true)

    private fun viewModel() = SettingsViewModel(
        context, store, contactSyncWriter, teslaContactResync, preFlightTester,
        appLocaleManager, notificationChannels, diagnosticsExporter,
        permissionGate, bluetoothConnectionChecker, teslaAuthManager,
        teslaCommandClient, dispatcher
    )

    /**
     * Sammelt alle One-Shot-Events des ViewModels im [TestScope.backgroundScope].
     * Der Collector MUSS auf einem [UnconfinedTestDispatcher] laufen: `advanceUntilIdle`
     * treibt nur Foreground-Tasks — ein per Default gestarteter Background-Collector
     * würde die Channel-Events nie konsumieren (Liste bliebe leer).
     */
    private fun TestScope.collectEvents(vm: SettingsViewModel): List<SettingsEvent> {
        val events = mutableListOf<SettingsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }
        return events
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // localizedString ist eine Top-Level-Extension (LocaleExt) — auf der JVM ohne
        // Robolectric via mockkStatic stubben, statt einen echten Context zu brauchen.
        mockkStatic("io.github.lycheeappf.tmm.core.locale.LocaleExtKt")
        every { context.localizedString(any()) } returns ""
    }

    @After
    fun tearDown() {
        unmockkStatic("io.github.lycheeappf.tmm.core.locale.LocaleExtKt")
        Dispatchers.resetMain()
    }

    @Test
    fun `setLanguage sets the locale tag AND re-creates the notification channels`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle() // init refresh() abwarten

        vm.setLanguage("en")

        verify(exactly = 1) { appLocaleManager.setLanguageTag("en") }
        // ensure() wird NUR von setLanguage gerufen (nicht im init) → load-bearing für i18n.
        verify(exactly = 1) { notificationChannels.ensure() }
    }

    @Test
    fun `language tag in UiState mirrors AppLocaleManager currentTag`() = runTest(dispatcher) {
        every { appLocaleManager.currentTag() } returns "de"

        val vm = viewModel()
        advanceUntilIdle()

        assertThat(vm.uiState.value.languageTag).isEqualTo("de")
    }

    @Test
    fun `setBudgetEnabled writes the flag to the store`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.setBudgetEnabled(false)
        advanceUntilIdle()

        coVerify(exactly = 1) { store.setSendBudgetEnabled(false) }
    }

    @Test
    fun `selectTeslaDevice persists address and name`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.selectTeslaDevice("AA:BB:CC:DD:EE:FF", "Model Y")
        advanceUntilIdle()

        coVerify(exactly = 1) { store.setTeslaBtDevice("AA:BB:CC:DD:EE:FF", "Model Y") }
    }

    @Test
    fun `clearTeslaDevice removes the stored device`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.clearTeslaDevice()
        advanceUntilIdle()

        coVerify(exactly = 1) { store.clearTeslaBtDevice() }
    }

    // ---- Tesla Fleet API: Credentials im UiState ------------------------------

    @Test
    fun `stored Tesla credentials are mirrored as teslaCredentialsSet true`() = runTest(dispatcher) {
        coEvery { teslaAuthManager.hasCredentials() } returns true

        val vm = viewModel()
        advanceUntilIdle()

        assertThat(vm.uiState.value.teslaCredentialsSet).isTrue()
    }

    @Test
    fun `absent Tesla credentials keep teslaCredentialsSet false`() = runTest(dispatcher) {
        coEvery { teslaAuthManager.hasCredentials() } returns false

        val vm = viewModel()
        advanceUntilIdle()

        assertThat(vm.uiState.value.teslaCredentialsSet).isFalse()
    }

    @Test
    fun `saveTeslaCredentials does nothing while a draft is blank`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.setTeslaClientIdDraft("client-id")
        vm.saveTeslaCredentials() // Secret-Entwurf fehlt → Early-Return
        advanceUntilIdle()

        coVerify(exactly = 0) { teslaAuthManager.setCredentials(any(), any()) }
    }

    @Test
    fun `saveTeslaCredentials persists trimmed drafts and clears them on success`() = runTest(dispatcher) {
        every { context.localizedString(R.string.tesla_credentials_feedback_saved) } returns "saved"
        val vm = viewModel()
        val events = collectEvents(vm)
        advanceUntilIdle()

        vm.setTeslaClientIdDraft("  client-id  ")
        vm.setTeslaClientSecretDraft("  s3cret  ")
        vm.saveTeslaCredentials()
        advanceUntilIdle()

        coVerify(exactly = 1) { teslaAuthManager.setCredentials("client-id", "s3cret") }
        with(vm.uiState.value) {
            assertThat(teslaCredentialsSet).isTrue()
            assertThat(teslaClientIdDraft).isEmpty()
            assertThat(teslaClientSecretDraft).isEmpty()
            assertThat(teslaCredentialsSaving).isFalse()
        }
        assertThat(events).containsExactly(SettingsEvent.Feedback("saved"))
    }

    @Test
    fun `saveTeslaCredentials keeps the drafts and reports invalid when the store rejects them`() = runTest(dispatcher) {
        every { context.localizedString(R.string.tesla_credentials_feedback_invalid) } returns "invalid"
        coEvery { teslaAuthManager.setCredentials(any(), any()) } throws IllegalArgumentException("leer")
        val vm = viewModel()
        val events = collectEvents(vm)
        advanceUntilIdle()

        vm.setTeslaClientIdDraft("client-id")
        vm.setTeslaClientSecretDraft("s3cret")
        vm.saveTeslaCredentials()
        advanceUntilIdle()

        with(vm.uiState.value) {
            assertThat(teslaCredentialsSet).isFalse()
            // Entwürfe bleiben stehen, damit der Nutzer die Eingabe korrigieren kann.
            assertThat(teslaClientIdDraft).isEqualTo("client-id")
            assertThat(teslaClientSecretDraft).isEqualTo("s3cret")
            assertThat(teslaCredentialsSaving).isFalse()
        }
        assertThat(events).containsExactly(SettingsEvent.Feedback("invalid"))
    }

    @Test
    fun `clearTeslaCredentials wipes the manager state and resets the Fleet UI state`() = runTest(dispatcher) {
        every { context.localizedString(R.string.tesla_credentials_feedback_removed) } returns "removed"
        coEvery { teslaAuthManager.hasCredentials() } returns true
        coEvery { teslaCommandClient.listVehicles() } returns
            listOf(VehicleInfo(id = 1L, vin = "5YJ3E1EA7KF000002", displayName = "Karla"))
        val vm = viewModel()
        val events = collectEvents(vm)
        advanceUntilIdle()

        // Erst authentifizieren, damit die Fahrzeugliste gefüllt ist …
        teslaAuthStateFlow.value = TeslaAuthState.Authenticated(selectedVin = null, expiresAtMs = 1L)
        advanceUntilIdle()
        assertThat(vm.uiState.value.teslaVehicles).isNotEmpty()

        // … dann Credentials entfernen: Manager räumt auf, Fleet-UiState wird zurückgesetzt.
        vm.clearTeslaCredentials()
        advanceUntilIdle()

        coVerify(exactly = 1) { teslaAuthManager.clearCredentials() }
        with(vm.uiState.value) {
            assertThat(teslaCredentialsSet).isFalse()
            assertThat(teslaVehicles).isEmpty()
            assertThat(teslaVehiclesError).isNull()
            assertThat(teslaRegionDiagnostic).isNull()
        }
        assertThat(events).containsExactly(SettingsEvent.Feedback("removed"))
    }

    // ---- Tesla Fleet API: Connect-Gate + Auth-State-Spiegel --------------------

    @Test
    fun `startTeslaLogin without credentials emits no auth url event`() = runTest(dispatcher) {
        // startAuth() == null ist der Missing-Credentials-Kontrakt des Managers.
        coEvery { teslaAuthManager.startAuth() } returns null
        val vm = viewModel()
        val events = collectEvents(vm)
        advanceUntilIdle()

        vm.startTeslaLogin()
        advanceUntilIdle()

        coVerify(exactly = 1) { teslaAuthManager.startAuth() }
        assertThat(events).isEmpty()
    }

    @Test
    fun `startTeslaLogin with credentials emits the auth url`() = runTest(dispatcher) {
        val url = "https://auth.tesla.com/oauth2/v3/authorize?state=x"
        coEvery { teslaAuthManager.startAuth() } returns url
        val vm = viewModel()
        val events = collectEvents(vm)
        advanceUntilIdle()

        vm.startTeslaLogin()
        advanceUntilIdle()

        assertThat(events).containsExactly(SettingsEvent.OpenTeslaAuthUrl(url))
    }

    @Test
    fun `auth state transitions are mirrored into UiState including MissingCredentials`() = runTest(dispatcher) {
        teslaAuthStateFlow.value = TeslaAuthState.MissingCredentials
        val vm = viewModel()
        advanceUntilIdle()
        assertThat(vm.uiState.value.teslaAuthState).isEqualTo(TeslaAuthState.MissingCredentials)

        teslaAuthStateFlow.value = TeslaAuthState.NotAuthenticated
        advanceUntilIdle()
        assertThat(vm.uiState.value.teslaAuthState).isEqualTo(TeslaAuthState.NotAuthenticated)

        val authenticated = TeslaAuthState.Authenticated(selectedVin = "5YJ3E1EA7KF000001", expiresAtMs = 99L)
        teslaAuthStateFlow.value = authenticated
        advanceUntilIdle()
        assertThat(vm.uiState.value.teslaAuthState).isEqualTo(authenticated)
    }

    @Test
    fun `transition to Authenticated loads the vehicle list into UiState`() = runTest(dispatcher) {
        val vehicles = listOf(VehicleInfo(id = 7L, vin = "5YJ3E1EA7KF000001", displayName = "Karla"))
        coEvery { teslaCommandClient.listVehicles() } returns vehicles
        val vm = viewModel()
        advanceUntilIdle() // erster Zustand NotAuthenticated → previous ist gesetzt

        teslaAuthStateFlow.value = TeslaAuthState.Authenticated(selectedVin = null, expiresAtMs = 1L)
        advanceUntilIdle()

        coVerify(exactly = 1) { teslaCommandClient.listVehicles() }
        with(vm.uiState.value) {
            assertThat(teslaVehicles).isEqualTo(vehicles)
            assertThat(teslaVehiclesLoading).isFalse()
            assertThat(teslaVehiclesError).isNull()
        }
    }

    @Test
    fun `already Authenticated at cold start does not auto-load vehicles`() = runTest(dispatcher) {
        // Nur der ÜBERGANG zu Authenticated lädt — sonst gäbe es bei jedem
        // Settings-Öffnen mit bestehender Session einen Fleet-API-Call.
        teslaAuthStateFlow.value = TeslaAuthState.Authenticated(selectedVin = "5YJ3E1EA7KF000001", expiresAtMs = 1L)
        val vm = viewModel()
        advanceUntilIdle()

        coVerify(exactly = 0) { teslaCommandClient.listVehicles() }
        assertThat(vm.uiState.value.teslaAuthState).isInstanceOf(TeslaAuthState.Authenticated::class.java)
    }

    @Test
    fun `logoutTesla delegates to the manager and clears the vehicle list`() = runTest(dispatcher) {
        coEvery { teslaCommandClient.listVehicles() } returns
            listOf(VehicleInfo(id = 3L, vin = "5YJ3E1EA7KF000003", displayName = "Karla"))
        val vm = viewModel()
        advanceUntilIdle()
        teslaAuthStateFlow.value = TeslaAuthState.Authenticated(selectedVin = null, expiresAtMs = 1L)
        advanceUntilIdle()
        assertThat(vm.uiState.value.teslaVehicles).isNotEmpty()

        vm.logoutTesla()
        advanceUntilIdle()

        coVerify(exactly = 1) { teslaAuthManager.logout() }
        assertThat(vm.uiState.value.teslaVehicles).isEmpty()
    }
}
