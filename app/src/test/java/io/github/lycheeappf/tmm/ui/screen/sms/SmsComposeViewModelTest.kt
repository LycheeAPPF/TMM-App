package io.github.lycheeappf.tmm.ui.screen.sms

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.domain.sms.SmsSender
import io.github.lycheeappf.tmm.ui.navigation.ARG_BODY
import io.github.lycheeappf.tmm.ui.navigation.ARG_RECIPIENT
import io.mockk.mockk
import org.junit.Test

/**
 * SavedStateHandle-Kontrakt des [SmsComposeViewModel]: die Vorbelegung liest
 * exakt die Nav-Arg-Keys [ARG_RECIPIENT]/[ARG_BODY], die `smsComposeRoute`
 * beim Deep-Link schreibt.
 */
class SmsComposeViewModelTest {

    private val context = mockk<Context>(relaxed = true)
    private val sender = mockk<SmsSender>(relaxed = true)

    private fun vm(handle: SavedStateHandle) = SmsComposeViewModel(context, sender, handle)

    @Test fun `nav arguments prefill recipient and body`() {
        val viewModel = vm(
            SavedStateHandle(mapOf(ARG_RECIPIENT to "+49170", ARG_BODY to "Hello World"))
        )

        assertThat(viewModel.uiState.value.recipient).isEqualTo("+49170")
        assertThat(viewModel.uiState.value.body).isEqualTo("Hello World")
    }

    @Test fun `missing nav arguments yield an empty compose state`() {
        val viewModel = vm(SavedStateHandle())

        assertThat(viewModel.uiState.value.recipient).isEmpty()
        assertThat(viewModel.uiState.value.body).isEmpty()
    }

    @Test fun `recipient-only deep-link leaves the body empty`() {
        val viewModel = vm(SavedStateHandle(mapOf(ARG_RECIPIENT to "+49170")))

        assertThat(viewModel.uiState.value.recipient).isEqualTo("+49170")
        assertThat(viewModel.uiState.value.body).isEmpty()
    }
}
