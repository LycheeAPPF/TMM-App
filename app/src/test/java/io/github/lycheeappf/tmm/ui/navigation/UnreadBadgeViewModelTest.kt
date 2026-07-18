package io.github.lycheeappf.tmm.ui.navigation

import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.domain.sms.SmsInboxReader
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Badge-Kontrakt: initialer Count, Live-Refresh, Trigger-Serialisierung, 99+-Kappung. */
class UnreadBadgeViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val reader = mockk<SmsInboxReader>()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `initial load emits unread count`() = runTest(dispatcher) {
        every { reader.changes() } returns emptyFlow()
        coEvery { reader.unreadCount() } returns 3

        val viewModel = UnreadBadgeViewModel(reader)
        advanceUntilIdle()

        assertThat(viewModel.unreadCount.value).isEqualTo(3)
    }

    @Test
    fun `provider change refreshes the count`() = runTest(dispatcher) {
        val changes = MutableSharedFlow<Unit>()
        every { reader.changes() } returns changes
        coEvery { reader.unreadCount() } returnsMany listOf(1, 5)

        val viewModel = UnreadBadgeViewModel(reader)
        advanceUntilIdle()
        assertThat(viewModel.unreadCount.value).isEqualTo(1)

        changes.emit(Unit)
        advanceUntilIdle()
        assertThat(viewModel.unreadCount.value).isEqualTo(5)
    }

    @Test
    fun `refresh reloads the count on demand`() = runTest(dispatcher) {
        every { reader.changes() } returns emptyFlow()
        coEvery { reader.unreadCount() } returnsMany listOf(0, 2)

        val viewModel = UnreadBadgeViewModel(reader)
        advanceUntilIdle()
        assertThat(viewModel.unreadCount.value).isEqualTo(0)

        viewModel.refresh()
        advanceUntilIdle()
        assertThat(viewModel.unreadCount.value).isEqualTo(2)
    }

    @Test
    fun `triggers inside the debounce window coalesce into one query`() = runTest(dispatcher) {
        val changes = MutableSharedFlow<Unit>()
        every { reader.changes() } returns changes
        coEvery { reader.unreadCount() } returnsMany listOf(1, 5)

        val viewModel = UnreadBadgeViewModel(reader)
        advanceUntilIdle()

        changes.emit(Unit)
        viewModel.refresh()
        advanceUntilIdle()

        // Provider-Änderung + Refresh im selben Debounce-Fenster → genau EINE
        // zusätzliche Query (Serialisierung über den gemergten Trigger-Flow).
        assertThat(viewModel.unreadCount.value).isEqualTo(5)
        coVerify(exactly = 2) { reader.unreadCount() }
    }

    @Test
    fun `formatBadgeCount caps at 99+`() {
        assertThat(UnreadBadgeViewModel.formatBadgeCount(5)).isEqualTo("5")
        assertThat(UnreadBadgeViewModel.formatBadgeCount(99)).isEqualTo("99")
        assertThat(UnreadBadgeViewModel.formatBadgeCount(100)).isEqualTo("99+")
    }
}
