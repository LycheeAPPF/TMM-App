package io.github.lycheeappf.tmm.core.util

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.data.store.SettingsStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Sichert das An/Aus-Verhalten des Tageslimits. Bei abgeschaltetem Limit MUSS
 * [SendBudget.checkAndIncrement] sofort `true` liefern, ohne den Tageszähler zu
 * berühren — und [SendBudget.rollback] symmetrisch ein No-op sein (sonst würde
 * ein fehlgeschlagener Inject einen evtl. noch von früher stehenden Zähler
 * fälschlich verringern).
 */
class SendBudgetTest {

    private val context = mockk<Context>(relaxed = true)
    private val store = mockk<SettingsStore>(relaxed = true)
    private val budget = SendBudget(context, store)

    @Test
    fun `disabled limit allows without touching the counter`() = runTest {
        coEvery { store.isSendBudgetEnabled() } returns false

        val allowed = budget.checkAndIncrement()

        assertThat(allowed).isTrue()
        coVerify(exactly = 0) { store.incrementDailySendCount() }
        coVerify(exactly = 0) { store.sendBudgetPerDay() }
    }

    @Test
    fun `disabled limit makes rollback a no-op`() = runTest {
        coEvery { store.isSendBudgetEnabled() } returns false

        budget.rollback()

        coVerify(exactly = 0) { store.decrementDailySendCount() }
    }

    @Test
    fun `enabled and under budget increments and allows`() = runTest {
        coEvery { store.isSendBudgetEnabled() } returns true
        coEvery { store.sendBudgetPerDay() } returns 100
        coEvery { store.dailySendCount() } returns 5

        val allowed = budget.checkAndIncrement()

        assertThat(allowed).isTrue()
        coVerify(exactly = 1) { store.incrementDailySendCount() }
    }

    @Test
    fun `enabled and at budget denies without incrementing`() = runTest {
        coEvery { store.isSendBudgetEnabled() } returns true
        coEvery { store.sendBudgetPerDay() } returns 10
        coEvery { store.dailySendCount() } returns 10
        // Overflow-Notification-Pfad: ohne echten NotificationManager früh aussteigen
        // lassen (relaxed-Mock liefert sonst einen nicht castbaren Dummy).
        every { context.getSystemService(any<String>()) } returns null

        val allowed = budget.checkAndIncrement()

        assertThat(allowed).isFalse()
        coVerify(exactly = 0) { store.incrementDailySendCount() }
    }

    @Test
    fun `enabled rollback decrements the count`() = runTest {
        coEvery { store.isSendBudgetEnabled() } returns true

        budget.rollback()

        coVerify(exactly = 1) { store.decrementDailySendCount() }
    }
}
