package io.github.lycheeappf.tmm

import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.data.store.SettingsStore
import io.github.lycheeappf.tmm.ui.navigation.MfsDestination
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * One-Shot-Semantik der Deep-Link-Requests im [RootViewModel]: post macht den
 * Request sichtbar, consume löscht ihn (kein Replay bei Recomposition), erneutes
 * post nach consume triggert wieder. Plus Start-Destination-Routing.
 */
class RootViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val settingsStore = mockk<SettingsStore> {
        every { developerModeFlow() } returns flowOf(false)
        coEvery { isOnboarded() } returns true
    }

    private fun vm() = RootViewModel(settingsStore, dispatcher)

    @Test fun `compose request is null initially, visible after post and cleared on consume`() = runTest(dispatcher) {
        val viewModel = vm()

        assertThat(viewModel.composeRequest.value).isNull()

        viewModel.postComposeRequest(recipient = "+49170", body = "Hi")
        assertThat(viewModel.composeRequest.value).isEqualTo("+49170" to "Hi")

        viewModel.consumeComposeRequest()
        assertThat(viewModel.composeRequest.value).isNull()
    }

    @Test fun `thread request is null initially, visible after post and cleared on consume`() = runTest(dispatcher) {
        val viewModel = vm()

        assertThat(viewModel.threadRequest.value).isNull()

        viewModel.postThreadRequest(42L)
        assertThat(viewModel.threadRequest.value).isEqualTo(42L)

        viewModel.consumeThreadRequest()
        assertThat(viewModel.threadRequest.value).isNull()
    }

    @Test fun `re-posting the same thread id after consume triggers again`() = runTest(dispatcher) {
        val viewModel = vm()

        viewModel.postThreadRequest(7L)
        viewModel.consumeThreadRequest()
        viewModel.postThreadRequest(7L)

        assertThat(viewModel.threadRequest.value).isEqualTo(7L)
    }

    @Test fun `consuming one request kind leaves the other untouched`() = runTest(dispatcher) {
        val viewModel = vm()

        viewModel.postComposeRequest("555", "x")
        viewModel.postThreadRequest(3L)
        viewModel.consumeComposeRequest()

        assertThat(viewModel.composeRequest.value).isNull()
        assertThat(viewModel.threadRequest.value).isEqualTo(3L)
    }

    @Test fun `start destination is Home when onboarded`() = runTest(dispatcher) {
        val viewModel = vm()
        advanceUntilIdle()

        assertThat(viewModel.startDestination.value).isEqualTo(MfsDestination.Home)
    }

    @Test fun `start destination is Onboarding when not onboarded`() = runTest(dispatcher) {
        coEvery { settingsStore.isOnboarded() } returns false

        val viewModel = vm()
        advanceUntilIdle()

        assertThat(viewModel.startDestination.value).isEqualTo(MfsDestination.Onboarding)
    }
}
