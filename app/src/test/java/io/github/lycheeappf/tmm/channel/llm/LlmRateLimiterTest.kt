package io.github.lycheeappf.tmm.channel.llm

import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.data.store.AssistantPreferencesStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class LlmRateLimiterTest {

    private val prefs: AssistantPreferencesStore = mockk()
    private var nowMs = 1_000_000L
    private val limiter = LlmRateLimiter(prefs) { nowMs }

    @Before fun setup() {
        coEvery { prefs.maxRequestsPerMin() } returns 3
        coEvery { prefs.maxRequestsPerHour() } returns 10
    }

    @Test fun `allows up to per-minute limit then rejects`() = runTest {
        repeat(3) { i ->
            val d = limiter.checkAndAcquire(mappingId = 42L)
            assertThat(d).isInstanceOf(LlmRateLimiter.Decision.Allow::class.java)
            // Innerhalb der Minute bleiben
            nowMs += 1000
        }
        val rejected = limiter.checkAndAcquire(mappingId = 42L)
        assertThat(rejected).isInstanceOf(LlmRateLimiter.Decision.Reject::class.java)
        assertThat((rejected as LlmRateLimiter.Decision.Reject).reason)
            .isEqualTo(LlmRateLimiter.Reason.PER_MINUTE)
    }

    @Test fun `per-mapping isolation`() = runTest {
        repeat(3) { limiter.checkAndAcquire(mappingId = 42L); nowMs += 1000 }
        // Anderes Mapping ist nicht betroffen
        val other = limiter.checkAndAcquire(mappingId = 99L)
        assertThat(other).isInstanceOf(LlmRateLimiter.Decision.Allow::class.java)
    }

    @Test fun `per-hour cap triggers when minute window has reset`() = runTest {
        coEvery { prefs.maxRequestsPerMin() } returns 100
        coEvery { prefs.maxRequestsPerHour() } returns 5

        repeat(5) {
            assertThat(limiter.checkAndAcquire(7L))
                .isInstanceOf(LlmRateLimiter.Decision.Allow::class.java)
            nowMs += 5 * 60_000L                 // 5 min Schritte → Minute-Window weg
        }
        val out = limiter.checkAndAcquire(7L)
        assertThat(out).isInstanceOf(LlmRateLimiter.Decision.Reject::class.java)
        assertThat((out as LlmRateLimiter.Decision.Reject).reason)
            .isEqualTo(LlmRateLimiter.Reason.PER_HOUR)
    }

    @Test fun `older entries beyond one hour are evicted`() = runTest {
        repeat(3) { limiter.checkAndAcquire(11L); nowMs += 1000 }
        nowMs += 60 * 60_000L + 1_000          // +1h +1s
        // Eine vollkommen frische Allow-Sequenz sollte wieder möglich sein
        assertThat(limiter.checkAndAcquire(11L)).isInstanceOf(LlmRateLimiter.Decision.Allow::class.java)
    }

    @Test fun `refund makes another acquire possible at the limit`() = runTest {
        repeat(3) { limiter.checkAndAcquire(13L) }
        // 4. Acquire wäre normalerweise blockiert
        limiter.refund(13L)
        assertThat(limiter.checkAndAcquire(13L))
            .isInstanceOf(LlmRateLimiter.Decision.Allow::class.java)
    }

    @Test fun `reset(mappingId) only clears that mapping`() = runTest {
        repeat(3) { limiter.checkAndAcquire(1L) }
        repeat(3) { limiter.checkAndAcquire(2L) }
        limiter.reset(1L)
        // Mapping 1 hat wieder volles Budget
        assertThat(limiter.checkAndAcquire(1L))
            .isInstanceOf(LlmRateLimiter.Decision.Allow::class.java)
        // Mapping 2 ist immer noch am Limit
        assertThat(limiter.checkAndAcquire(2L))
            .isInstanceOf(LlmRateLimiter.Decision.Reject::class.java)
    }
}
