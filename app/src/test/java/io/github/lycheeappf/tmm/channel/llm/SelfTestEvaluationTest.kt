package io.github.lycheeappf.tmm.channel.llm

import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.channel.llm.tools.ToolCallExecutor
import io.github.lycheeappf.tmm.channel.llm.tools.ToolInvocationResult
import io.github.lycheeappf.tmm.channel.llm.provider.ToolCall
import io.github.lycheeappf.tmm.platform.location.LocationFix
import org.junit.Test

class SelfTestEvaluationTest {

    private val fix = LocationFix(latitude = 52.5200, longitude = 13.4050, accuracyInMeters = 25f)

    private fun step(
        name: String = "tesla_navigate",
        argumentsJson: String = """{"address":"Alexanderplatz, Berlin"}""",
        result: ToolInvocationResult = ToolInvocationResult.Success("""{"status":"ok"}""")
    ) = ToolCallExecutor.ToolStep(ToolCall("c1", name, argumentsJson), result)

    // ---- addressMatches ------------------------------------------------------

    @Test fun `address matches exactly`() {
        assertThat(SelfTestEvaluation.addressMatches("Alexanderplatz, Berlin", "Alexanderplatz, Berlin")).isTrue()
    }

    @Test fun `address match is case- and punctuation-tolerant`() {
        assertThat(SelfTestEvaluation.addressMatches("Alexanderplatz, Berlin", "alexanderplatz berlin")).isTrue()
    }

    @Test fun `address match accepts a more specific sent address`() {
        assertThat(
            SelfTestEvaluation.addressMatches("Alexanderplatz, Berlin", "Alexanderplatz Berlin 10178 Deutschland")
        ).isTrue()
    }

    @Test fun `different address does not match`() {
        assertThat(SelfTestEvaluation.addressMatches("Alexanderplatz, Berlin", "Hauptbahnhof München")).isFalse()
    }

    @Test fun `blank sent address does not match`() {
        assertThat(SelfTestEvaluation.addressMatches("Alexanderplatz, Berlin", "  ")).isFalse()
    }

    // ---- extractNumbers ------------------------------------------------------

    @Test fun `extracts dot and comma decimals and integers`() {
        assertThat(SelfTestEvaluation.extractNumbers("52.5200° N, 13,405° O bei 25 m"))
            .containsExactly(52.52, 13.405, 25.0).inOrder()
    }

    // ---- navCheck ------------------------------------------------------------

    @Test fun `nav not called when no tesla_navigate step exists`() {
        assertThat(SelfTestEvaluation.navCheck("Alexanderplatz, Berlin", emptyList()))
            .isEqualTo(NavCheck.NotCalled)
    }

    @Test fun `nav called ok when address matches and tool succeeded`() {
        assertThat(SelfTestEvaluation.navCheck("Alexanderplatz, Berlin", listOf(step())))
            .isEqualTo(NavCheck.CalledOk("Alexanderplatz, Berlin"))
    }

    @Test fun `wrong address wins even when the tool call succeeded`() {
        val s = step(argumentsJson = """{"address":"Hauptbahnhof München"}""")
        assertThat(SelfTestEvaluation.navCheck("Alexanderplatz, Berlin", listOf(s)))
            .isEqualTo(NavCheck.WrongAddress("Hauptbahnhof München"))
    }

    @Test fun `tool failure carries the localized error through`() {
        val s = step(result = ToolInvocationResult.Failure("Kein Tesla-Fahrzeug konfiguriert"))
        assertThat(SelfTestEvaluation.navCheck("Alexanderplatz, Berlin", listOf(s)))
            .isEqualTo(NavCheck.CalledFailed("Alexanderplatz, Berlin", "Kein Tesla-Fahrzeug konfiguriert"))
    }

    // ---- positionEcho --------------------------------------------------------

    @Test fun `echo skipped without a fix`() {
        assertThat(SelfTestEvaluation.positionEcho(null, false, "52.52, 13.40"))
            .isEqualTo(PositionEcho.SKIPPED)
    }

    @Test fun `echo no_clause when the system prompt is blank despite a fix`() {
        assertThat(SelfTestEvaluation.positionEcho(fix, true, "52.52, 13.40"))
            .isEqualTo(PositionEcho.NO_CLAUSE)
    }

    @Test fun `echo matched with dot decimals`() {
        assertThat(SelfTestEvaluation.positionEcho(fix, false, "Du bist bei 52.5200° N, 13.4050° O."))
            .isEqualTo(PositionEcho.MATCHED)
    }

    @Test fun `echo matched with german comma decimals and rounding`() {
        assertThat(SelfTestEvaluation.positionEcho(fix, false, "Position: 52,52 Grad Nord, 13,41 Grad Ost"))
            .isEqualTo(PositionEcho.MATCHED)
    }

    @Test fun `echo matched for southern-western fix via abs comparison`() {
        val sw = LocationFix(latitude = -33.8688, longitude = -151.2093, accuracyInMeters = 10f)
        assertThat(SelfTestEvaluation.positionEcho(sw, false, "33.87° S, 151.21° W"))
            .isEqualTo(PositionEcho.MATCHED)
    }

    @Test fun `echo not_found when the reply has no matching pair`() {
        assertThat(SelfTestEvaluation.positionEcho(fix, false, "NO POSITION"))
            .isEqualTo(PositionEcho.NOT_FOUND)
    }
}
