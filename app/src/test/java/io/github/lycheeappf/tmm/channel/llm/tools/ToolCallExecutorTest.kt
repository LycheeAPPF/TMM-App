package io.github.lycheeappf.tmm.channel.llm.tools

import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.channel.llm.provider.LlmProviderError
import io.github.lycheeappf.tmm.channel.llm.provider.LlmRequest
import io.github.lycheeappf.tmm.channel.llm.provider.LlmResponse
import io.github.lycheeappf.tmm.channel.llm.provider.ToolCall
import io.github.lycheeappf.tmm.core.util.LogBuffer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class ToolCallExecutorTest {

    private val toolRegistry: ToolRegistry = mockk(relaxed = true)
    private val logBuffer: LogBuffer = mockk(relaxed = true)
    private val executor = ToolCallExecutor(toolRegistry, logBuffer)

    private fun request() = LlmRequest(
        model = "grok-4.3", systemPrompt = "Sys", history = emptyList(),
        userMessage = "hi", tools = emptyList(), maxTokens = 256, temperature = 0f
    )

    private fun response(toolCalls: List<ToolCall> = emptyList(), content: String? = null) =
        LlmResponse(
            content = content, toolCalls = toolCalls,
            finishReason = if (toolCalls.isEmpty()) "stop" else "tool_calls",
            usage = null, responseId = null
        )

    @Test fun `no tool calls returns initial response untouched and never completes`() = runTest {
        val initial = response(content = "Fertig")
        val result = executor.run(request(), initial) {
            throw AssertionError("complete darf ohne Tool-Calls nicht aufgerufen werden")
        }
        assertThat(result.finalResponse).isSameInstanceAs(initial)
        assertThat(result.steps).isEmpty()
    }

    @Test fun `executes tool and feeds result into follow-up request`() = runTest {
        val toolOutput = """{"status":"ok","destination":"Alexanderplatz"}"""
        coEvery { toolRegistry.invoke("tesla_navigate", any()) } returns
            ToolInvocationResult.Success(toolOutput)
        val initial = response(
            toolCalls = listOf(ToolCall("c1", "tesla_navigate", """{"address":"Alexanderplatz"}"""))
        )
        val followUps = mutableListOf<LlmRequest>()

        val result = executor.run(request(), initial) { req ->
            followUps += req
            response(content = "Ich navigiere dich.")
        }

        assertThat(result.finalResponse.content).isEqualTo("Ich navigiere dich.")
        assertThat(result.steps).hasSize(1)
        assertThat(result.steps[0].call.name).isEqualTo("tesla_navigate")
        assertThat(result.steps[0].result).isEqualTo(ToolInvocationResult.Success(toolOutput))
        assertThat(followUps).hasSize(1)
        assertThat(followUps[0].inFlightToolCalls).hasSize(1)
        assertThat(followUps[0].inFlightToolResults).hasSize(1)
        assertThat(followUps[0].inFlightToolResults[0].callId).isEqualTo("c1")
        assertThat(followUps[0].inFlightToolResults[0].output).isEqualTo(toolOutput)
    }

    @Test fun `stops after three iterations even if the model keeps calling tools`() = runTest {
        coEvery { toolRegistry.invoke(any(), any()) } returns ToolInvocationResult.Success("{}")
        var completions = 0
        val toolResponse = response(toolCalls = listOf(ToolCall("c", "t", "{}")))

        val result = executor.run(request(), toolResponse) {
            completions++
            toolResponse
        }

        assertThat(completions).isEqualTo(3)
        assertThat(result.steps).hasSize(3)
        assertThat(result.finalResponse.toolCalls).isNotEmpty()
    }

    @Test fun `malformed arguments fall back to empty json object`() = runTest {
        val args = slot<JsonObject>()
        coEvery { toolRegistry.invoke("t", capture(args)) } returns ToolInvocationResult.Success("{}")
        val initial = response(toolCalls = listOf(ToolCall("c1", "t", "NOT-JSON")))

        executor.run(request(), initial) { response(content = "ok") }

        assertThat(args.captured).isEmpty()
    }

    @Test fun `arguments are parsed and passed to the registry`() = runTest {
        val args = slot<JsonObject>()
        coEvery { toolRegistry.invoke("tesla_navigate", capture(args)) } returns
            ToolInvocationResult.Success("{}")
        val initial = response(
            toolCalls = listOf(ToolCall("c1", "tesla_navigate", """{"address":"Alexanderplatz"}"""))
        )

        executor.run(request(), initial) { response(content = "ok") }

        assertThat(args.captured["address"]?.jsonPrimitive?.content).isEqualTo("Alexanderplatz")
    }

    @Test fun `multiple tool calls in one response are all executed and returned in order`() = runTest {
        coEvery { toolRegistry.invoke("a", any()) } returns ToolInvocationResult.Success("""{"r":1}""")
        coEvery { toolRegistry.invoke("b", any()) } returns ToolInvocationResult.Failure("nope")
        val initial = response(
            toolCalls = listOf(ToolCall("c1", "a", "{}"), ToolCall("c2", "b", "{}"))
        )
        val followUps = mutableListOf<LlmRequest>()

        val result = executor.run(request(), initial) { req ->
            followUps += req
            response(content = "done")
        }

        assertThat(result.steps.map { it.call.id }).containsExactly("c1", "c2").inOrder()
        assertThat(followUps[0].inFlightToolResults.map { it.callId })
            .containsExactly("c1", "c2").inOrder()
        // Failure wird als error-JSON zurückgesendet (toOutputString-Vertrag).
        assertThat(followUps[0].inFlightToolResults[1].output).contains("nope")
        coVerify(exactly = 1) { toolRegistry.invoke("a", any()) }
        coVerify(exactly = 1) { toolRegistry.invoke("b", any()) }
    }

    @Test fun `provider error from complete propagates unchanged`() = runTest {
        coEvery { toolRegistry.invoke(any(), any()) } returns ToolInvocationResult.Success("{}")
        val initial = response(toolCalls = listOf(ToolCall("c1", "t", "{}")))

        var thrown: Throwable? = null
        try {
            executor.run(request(), initial) { throw LlmProviderError.Server(503, null) }
        } catch (e: LlmProviderError) {
            thrown = e
        }
        assertThat(thrown).isInstanceOf(LlmProviderError.Server::class.java)
    }

    @Test fun `inflight items accumulate across iterations`() = runTest {
        coEvery { toolRegistry.invoke(any(), any()) } returns ToolInvocationResult.Success("{}")
        val followUps = mutableListOf<LlmRequest>()
        var round = 0

        executor.run(request(), response(toolCalls = listOf(ToolCall("c1", "t", "{}")))) { req ->
            followUps += req
            round++
            if (round == 1) response(toolCalls = listOf(ToolCall("c2", "t", "{}")))
            else response(content = "done")
        }

        assertThat(followUps).hasSize(2)
        assertThat(followUps[0].inFlightToolCalls.map { it.id }).containsExactly("c1")
        assertThat(followUps[1].inFlightToolCalls.map { it.id }).containsExactly("c1", "c2").inOrder()
        assertThat(followUps[1].inFlightToolResults.map { it.callId }).containsExactly("c1", "c2").inOrder()
    }
}
