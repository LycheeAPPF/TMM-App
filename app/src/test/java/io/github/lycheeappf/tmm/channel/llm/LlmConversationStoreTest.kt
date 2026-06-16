package io.github.lycheeappf.tmm.channel.llm

import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.channel.llm.provider.LlmTurn
import io.github.lycheeappf.tmm.data.store.AssistantPreferencesStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class LlmConversationStoreTest {

    private val prefs: AssistantPreferencesStore = mockk()
    private var nowMs = 1_000_000L
    private val store = LlmConversationStore(prefs) { nowMs }

    @Before fun setup() {
        coEvery { prefs.contextTtlSeconds() } returns 120
    }

    @Test fun `append then snapshot returns inserted turns`() {
        val s = store.sessionFor(42L)
        store.append(s, LlmTurn("user", "Hi", 1L))
        store.append(s, LlmTurn("assistant", "Hi back", 2L))
        val snap = store.snapshot(s)
        assertThat(snap).hasSize(2)
        assertThat(snap[0].content).isEqualTo("Hi")
        assertThat(snap[1].content).isEqualTo("Hi back")
    }

    @Test fun `reset clears for one mapping only`() = runTest {
        val s42 = store.sessionFor(42L)
        val s99 = store.sessionFor(99L)
        store.append(s42, LlmTurn("user", "A", 1L))
        store.append(s99, LlmTurn("user", "B", 1L))
        store.resetUnderLock(42L)
        // resetUnderLock leert die History der Session in-place
        assertThat(store.snapshot(s42)).isEmpty()
        assertThat(store.snapshot(s99)).hasSize(1)
    }

    @Test fun `expireIfStale clears history when TTL exceeded`() = runTest {
        val s = store.sessionFor(42L)
        store.append(s, LlmTurn("user", "Hi", 1L))    // sets lastInteractionAt=nowMs
        nowMs += 130_000L                              // > 120s TTL
        store.expireIfStale(s)
        assertThat(store.snapshot(s)).isEmpty()
    }

    @Test fun `expireIfStale keeps history inside TTL window`() = runTest {
        val s = store.sessionFor(42L)
        store.append(s, LlmTurn("user", "Hi", 1L))
        nowMs += 110_000L                              // < 120s TTL
        store.expireIfStale(s)
        assertThat(store.snapshot(s)).hasSize(1)
    }

    @Test fun `expireIfStale at exact TTL boundary keeps history`() = runTest {
        val s = store.sessionFor(42L)
        store.append(s, LlmTurn("user", "Hi", 1L))
        nowMs += 120_000L                              // == 120s
        store.expireIfStale(s)
        assertThat(store.snapshot(s)).hasSize(1)
    }

    @Test fun `expireIfStale one ms past TTL drops history`() = runTest {
        val s = store.sessionFor(42L)
        store.append(s, LlmTurn("user", "Hi", 1L))
        nowMs += 120_001L
        store.expireIfStale(s)
        assertThat(store.snapshot(s)).isEmpty()
    }

    @Test fun `history cap drops oldest`() {
        val s = store.sessionFor(7L)
        repeat(LlmConversationStore.HISTORY_CAP + 5) { i ->
            store.append(s, LlmTurn("user", "msg-$i", i.toLong()))
        }
        val snap = store.snapshot(s)
        assertThat(snap).hasSize(LlmConversationStore.HISTORY_CAP)
        // ältester (index 0) sollte msg-5 sein, weil 0..4 weggedroppt
        assertThat(snap.first().content).isEqualTo("msg-5")
        assertThat(snap.last().content).isEqualTo("msg-${LlmConversationStore.HISTORY_CAP + 4}")
    }

    @Test fun `history at exact cap keeps all entries`() {
        val s = store.sessionFor(7L)
        repeat(LlmConversationStore.HISTORY_CAP) { i ->
            store.append(s, LlmTurn("user", "msg-$i", i.toLong()))
        }
        val snap = store.snapshot(s)
        assertThat(snap).hasSize(LlmConversationStore.HISTORY_CAP)
        assertThat(snap.first().content).isEqualTo("msg-0")
    }

    @Test fun `concurrent sessionFor returns same instance`() = runTest {
        // computeIfAbsent garantiert atomicity — sessionFor zweimal soll
        // dieselbe Session zurückgeben.
        val a = store.sessionFor(11L)
        val b = store.sessionFor(11L)
        assertThat(a).isSameInstanceAs(b)
    }

    @Test fun `resetUnderLock clears history in-place — same Session instance survives`() = runTest {
        val s = store.sessionFor(7L)
        store.append(s, LlmTurn("user", "A", 1L))
        store.resetUnderLock(7L)
        assertThat(store.snapshot(s)).isEmpty()
        // Selbe Session-Instanz nach reset (kein Replace)
        val sAfter = store.sessionFor(7L)
        assertThat(sAfter).isSameInstanceAs(s)
    }
}
