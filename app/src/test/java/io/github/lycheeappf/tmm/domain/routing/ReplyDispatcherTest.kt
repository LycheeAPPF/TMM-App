package io.github.lycheeappf.tmm.domain.routing

import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.core.model.ChannelId
import io.github.lycheeappf.tmm.domain.channel.ChannelMapping
import io.github.lycheeappf.tmm.domain.channel.ChannelPayload
import io.github.lycheeappf.tmm.domain.channel.ChannelRegistry
import io.github.lycheeappf.tmm.domain.channel.MessagingChannel
import io.github.lycheeappf.tmm.domain.reply.ReplyResult
import io.github.lycheeappf.tmm.domain.repository.MappingRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class ReplyDispatcherTest {

    private val registry: ChannelRegistry = mockk()
    private val mappingRepo: MappingRepository = mockk()
    private val channel: MessagingChannel = mockk()
    private lateinit var dispatcher: ReplyDispatcher

    private val llmMapping = ChannelMapping(
        mappingId = 7L,
        channel = ChannelId.LLM,
        fakeAddress = "Grok",
        conversationKey = "default-assistant",
        payload = ChannelPayload.Llm(),
        createdAt = 100L,
        expiresAt = Long.MAX_VALUE,
        lastUsedAt = null,
        replyCount = 0,
        replyable = true
    )

    @Before fun setup() {
        dispatcher = ReplyDispatcher(registry, mappingRepo)
        coEvery { channel.id } returns ChannelId.LLM
        coEvery { channel.displayName } returns "AI"
        coEvery { channel.maybeInjectFollowUp(any(), any(), any()) } just Runs
        coEvery { mappingRepo.recordReplyAttempt(any(), any()) } just Runs
    }

    @Test fun `dispatch returns null for unknown channel code`() = runTest {
        val result = dispatcher.dispatch(mappingId = 1L, channelCode = 5, replyText = "Hi")
        assertThat(result).isNull()
        coVerify(exactly = 0) { mappingRepo.findById(any(), any()) }
    }

    @Test fun `dispatch returns Expired when mapping not found`() = runTest {
        coEvery { mappingRepo.findById(7L, ChannelId.LLM) } returns null
        val result = dispatcher.dispatch(7L, ChannelId.LLM.code, "Hi")
        assertThat(result).isEqualTo(ReplyResult.Expired)
    }

    @Test fun `dispatch returns Expired when mapping is in the past`() = runTest {
        coEvery { mappingRepo.findById(7L, ChannelId.LLM) } returns
            llmMapping.copy(expiresAt = 1L)
        val result = dispatcher.dispatch(7L, ChannelId.LLM.code, "Hi")
        assertThat(result).isEqualTo(ReplyResult.Expired)
    }

    @Test fun `dispatch returns ProviderError when channel not registered`() = runTest {
        coEvery { mappingRepo.findById(7L, ChannelId.LLM) } returns llmMapping
        coEvery { registry.get(ChannelId.LLM) } returns null
        val result = dispatcher.dispatch(7L, ChannelId.LLM.code, "Hi")
        assertThat(result).isInstanceOf(ReplyResult.ProviderError::class.java)
    }

    @Test fun `Ignored result skips recordReplyAttempt and maybeInjectFollowUp`() = runTest {
        coEvery { mappingRepo.findById(7L, ChannelId.LLM) } returns llmMapping
        coEvery { registry.get(ChannelId.LLM) } returns channel
        coEvery { channel.handleTeslaReply(llmMapping, any()) } returns ReplyResult.Ignored

        val result = dispatcher.dispatch(7L, ChannelId.LLM.code, "   ")
        assertThat(result).isEqualTo(ReplyResult.Ignored)
        coVerify(exactly = 0) { mappingRepo.recordReplyAttempt(any(), any()) }
        coVerify(exactly = 0) { channel.maybeInjectFollowUp(any(), any(), any()) }
    }

    @Test fun `FollowUp result triggers recordReplyAttempt and maybeInjectFollowUp`() = runTest {
        coEvery { mappingRepo.findById(7L, ChannelId.LLM) } returns llmMapping
        coEvery { registry.get(ChannelId.LLM) } returns channel
        val followUp = ReplyResult.FollowUp("Antwort")
        coEvery { channel.handleTeslaReply(llmMapping, any()) } returns followUp

        val result = dispatcher.dispatch(7L, ChannelId.LLM.code, "Frage")
        assertThat(result).isEqualTo(followUp)
        coVerify { mappingRepo.recordReplyAttempt(7L, ChannelId.LLM) }
        coVerify { channel.maybeInjectFollowUp(llmMapping, "Frage", followUp) }
    }

    @Test fun `Success result triggers both recordReplyAttempt and maybeInjectFollowUp`() = runTest {
        coEvery { mappingRepo.findById(7L, ChannelId.LLM) } returns llmMapping
        coEvery { registry.get(ChannelId.LLM) } returns channel
        coEvery { channel.handleTeslaReply(llmMapping, any()) } returns ReplyResult.Success

        dispatcher.dispatch(7L, ChannelId.LLM.code, "Frage")
        coVerify { mappingRepo.recordReplyAttempt(7L, ChannelId.LLM) }
        coVerify { channel.maybeInjectFollowUp(llmMapping, "Frage", ReplyResult.Success) }
    }

    @Test fun `ProviderError result does not call recordReplyAttempt but does inject follow-up`() = runTest {
        coEvery { mappingRepo.findById(7L, ChannelId.LLM) } returns llmMapping
        coEvery { registry.get(ChannelId.LLM) } returns channel
        val err = ReplyResult.ProviderError("no net")
        coEvery { channel.handleTeslaReply(llmMapping, any()) } returns err

        dispatcher.dispatch(7L, ChannelId.LLM.code, "Frage")
        coVerify(exactly = 0) { mappingRepo.recordReplyAttempt(any(), any()) }
        coVerify { channel.maybeInjectFollowUp(llmMapping, "Frage", err) }
    }

    @Test fun `recordReplyAttempt exception is swallowed not propagated`() = runTest {
        coEvery { mappingRepo.findById(7L, ChannelId.LLM) } returns llmMapping
        coEvery { registry.get(ChannelId.LLM) } returns channel
        coEvery { channel.handleTeslaReply(any(), any()) } returns ReplyResult.FollowUp("a")
        coEvery { mappingRepo.recordReplyAttempt(any(), any()) } throws RuntimeException("dbroken")

        // Must not throw — the dispatcher logs & continues to maybeInjectFollowUp
        val result = dispatcher.dispatch(7L, ChannelId.LLM.code, "Frage")
        assertThat(result).isInstanceOf(ReplyResult.FollowUp::class.java)
        coVerify { channel.maybeInjectFollowUp(any(), any(), any()) }
    }

    @Test fun `maybeInjectFollowUp exception is swallowed not propagated`() = runTest {
        coEvery { mappingRepo.findById(7L, ChannelId.LLM) } returns llmMapping
        coEvery { registry.get(ChannelId.LLM) } returns channel
        coEvery { channel.handleTeslaReply(any(), any()) } returns ReplyResult.FollowUp("a")
        coEvery { channel.maybeInjectFollowUp(any(), any(), any()) } throws RuntimeException("io")

        val result = dispatcher.dispatch(7L, ChannelId.LLM.code, "Frage")
        assertThat(result).isInstanceOf(ReplyResult.FollowUp::class.java)
    }
}
