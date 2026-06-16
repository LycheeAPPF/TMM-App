package io.github.lycheeappf.tmm.channel.notification

import android.app.PendingIntent
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sichert die Eviction-Politik nach dem A5-Guard ab: lazy TTL-Eviction in get(),
 * Common-Path ohne Allokation, Kapazitäts-Cap greift erst bei Overflow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ActionCacheTest {

    private val cache = ActionCache()
    private val intent = mockk<PendingIntent>(relaxed = true)

    private fun action(capturedAt: Long) =
        ResolvedReplyAction(actionIntent = intent, remoteInputs = emptyList(), capturedAt = capturedAt)

    @Test fun `get returns a fresh entry`() {
        cache.put("k", action(System.currentTimeMillis()))
        assertThat(cache.get("k")).isNotNull()
    }

    @Test fun `get evicts an entry older than the 24h TTL`() {
        val stale = System.currentTimeMillis() - 25L * 60 * 60 * 1000
        cache.put("k", action(stale))
        assertThat(cache.get("k")).isNull()
        assertThat(cache.size()).isEqualTo(0)
    }

    @Test fun `capacity eviction keeps the cache bounded at 500`() {
        // alle innerhalb der TTL → nur die Kapazitäts-Eviction darf greifen
        repeat(600) { cache.put("k$it", action(System.currentTimeMillis() + it)) }
        assertThat(cache.size()).isEqualTo(500)
    }
}
