package io.github.lycheeappf.tmm.channel.llm.tools.tesla

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.R
import io.github.lycheeappf.tmm.channel.llm.tools.ToolInvocationResult
import io.github.lycheeappf.tmm.core.locale.localizedString
import io.github.lycheeappf.tmm.data.store.TeslaTokenStore
import io.github.lycheeappf.tmm.platform.tesla.api.TeslaCommandError
import io.github.lycheeappf.tmm.platform.tesla.api.TeslaVehicleCommandClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Sichert das Tool-Result-JSON von [TeslaNavigateTool]: via kotlinx.serialization
 * gebaut (Quotes/Backslashes/Newlines korrekt escaped, Roundtrip-parsebar), Ziel-Echo
 * enthalten, Kürzung des ROHEN Werts vor dem Encoden. Fehlerpfade (fehlendes Fahrzeug,
 * fehlende Credentials, typisierte [TeslaCommandError]s, unerwartete Exceptions)
 * landen als lokalisierte [ToolInvocationResult.Failure]; Cancellation propagiert.
 */
class TeslaNavigateToolTest {

    private val context: Context = mockk()
    private val commandClient: TeslaVehicleCommandClient = mockk()
    private val tokenStore: TeslaTokenStore = mockk()

    private val tool = TeslaNavigateTool(context, commandClient, tokenStore)

    @Before fun setup() {
        mockkStatic("io.github.lycheeappf.tmm.core.locale.LocaleExtKt")
        every { context.localizedString(any()) } returns "Fehlertext"
        coEvery { tokenStore.readSelectedVin() } returns "5YJ3E1EA7KF000000"
        coEvery { commandClient.navigate(any(), any()) } returns Unit
    }

    @After fun tearDown() {
        unmockkStatic("io.github.lycheeappf.tmm.core.locale.LocaleExtKt")
    }

    private fun args(address: String) = buildJsonObject { put("address", address) }

    @Test fun `success result is valid JSON and echoes the destination`() = runTest {
        val result = tool.invoke(args("Alexanderplatz Berlin"))

        assertThat(result).isInstanceOf(ToolInvocationResult.Success::class.java)
        val parsed = Json.parseToJsonElement((result as ToolInvocationResult.Success).output).jsonObject
        assertThat(parsed["status"]?.jsonPrimitive?.content).isEqualTo("ok")
        assertThat(parsed["destination"]?.jsonPrimitive?.content).isEqualTo("Alexanderplatz Berlin")
    }

    @Test fun `quotes backslashes and newlines survive the JSON roundtrip`() = runTest {
        val tricky = "Cafe \"Zum \\ Hirschen\"\nHinterhof 3"

        val result = tool.invoke(args(tricky)) as ToolInvocationResult.Success

        val parsed = Json.parseToJsonElement(result.output).jsonObject
        assertThat(parsed["destination"]?.jsonPrimitive?.content).isEqualTo(tricky)
    }

    @Test fun `destination echo is truncated on the raw value before encoding`() = runTest {
        // 199 Zeichen + Quote an Position 200: würde NACH dem Escapen gekürzt, bliebe
        // ein einsamer Backslash zurück und das JSON wäre kaputt.
        val long = "a".repeat(199) + "\"" + "b".repeat(100)

        val result = tool.invoke(args(long)) as ToolInvocationResult.Success

        val parsed = Json.parseToJsonElement(result.output).jsonObject
        assertThat(parsed["destination"]?.jsonPrimitive?.content).isEqualTo(long.take(200))
    }

    @Test fun `blank address fails without calling the fleet api`() = runTest {
        val result = tool.invoke(args("   "))

        assertThat(result).isInstanceOf(ToolInvocationResult.Failure::class.java)
        coVerify(exactly = 0) { commandClient.navigate(any(), any()) }
    }

    @Test fun `missing vehicle selection returns the localized setup hint without calling the fleet api`() = runTest {
        coEvery { tokenStore.readSelectedVin() } returns null
        every { context.localizedString(R.string.tesla_error_no_vehicle_configured) } returns "Kein Fahrzeug verbunden"

        val result = tool.invoke(args("Alexanderplatz Berlin"))

        assertThat(result).isInstanceOf(ToolInvocationResult.Failure::class.java)
        assertThat((result as ToolInvocationResult.Failure).error).isEqualTo("Kein Fahrzeug verbunden")
        coVerify(exactly = 0) { commandClient.navigate(any(), any()) }
    }

    @Test fun `missing credentials error maps to the localized tool failure`() = runTest {
        coEvery { commandClient.navigate(any(), any()) } throws TeslaCommandError.MissingCredentials()
        every { context.localizedString(R.string.tesla_error_missing_credentials) } returns "Tesla-Zugang nicht eingerichtet"

        val result = tool.invoke(args("Alexanderplatz Berlin"))

        assertThat(result).isInstanceOf(ToolInvocationResult.Failure::class.java)
        assertThat((result as ToolInvocationResult.Failure).error).isEqualTo("Tesla-Zugang nicht eingerichtet")
    }

    @Test fun `command rejected error maps to the localized failure carrying the reason`() = runTest {
        coEvery { commandClient.navigate(any(), any()) } throws TeslaCommandError.CommandRejected("vehicle asleep")
        every {
            context.localizedString(R.string.tesla_error_command_rejected, "vehicle asleep")
        } returns "Kommando abgelehnt: vehicle asleep"

        val result = tool.invoke(args("Alexanderplatz Berlin"))

        assertThat(result).isInstanceOf(ToolInvocationResult.Failure::class.java)
        assertThat((result as ToolInvocationResult.Failure).error).isEqualTo("Kommando abgelehnt: vehicle asleep")
    }

    @Test fun `unexpected exceptions map to the localized network failure`() = runTest {
        coEvery { commandClient.navigate(any(), any()) } throws IllegalStateException("boom")
        every { context.localizedString(R.string.tesla_error_network) } returns "Netzwerkfehler"

        val result = tool.invoke(args("Alexanderplatz Berlin"))

        assertThat(result).isInstanceOf(ToolInvocationResult.Failure::class.java)
        assertThat((result as ToolInvocationResult.Failure).error).isEqualTo("Netzwerkfehler")
    }

    @Test fun `cancellation exception is rethrown not wrapped as failure`() = runTest {
        coEvery { commandClient.navigate(any(), any()) } throws CancellationException("scope dying")

        val ex = runCatching { tool.invoke(args("Alexanderplatz Berlin")) }.exceptionOrNull()

        assertThat(ex).isInstanceOf(CancellationException::class.java)
    }
}
