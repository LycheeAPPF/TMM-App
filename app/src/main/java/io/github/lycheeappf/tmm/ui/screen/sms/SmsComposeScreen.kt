package io.github.lycheeappf.tmm.ui.screen.sms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lycheeappf.tmm.R
import io.github.lycheeappf.tmm.ui.component.MfsScaffold
import io.github.lycheeappf.tmm.ui.component.PrimaryActionButton
import io.github.lycheeappf.tmm.ui.theme.MfsSpacing

@Composable
fun SmsComposeScreen(
    onBack: () -> Unit,
    viewModel: SmsComposeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Nach erfolgreichem Übergeben zurück zur Liste (neuer Thread erscheint dort).
    LaunchedEffect(state.sent) {
        if (state.sent) onBack()
    }

    LaunchedEffect(state.feedback) {
        state.feedback?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeFeedback()
        }
    }

    MfsScaffold(
        title = stringResource(R.string.sms_compose_title),
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = MfsSpacing.xl, vertical = MfsSpacing.md),
            verticalArrangement = Arrangement.spacedBy(MfsSpacing.md)
        ) {
            OutlinedTextField(
                value = state.recipient,
                onValueChange = viewModel::setRecipient,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.sms_compose_recipient_label)) },
                singleLine = true,
                enabled = !state.sending,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
            )
            OutlinedTextField(
                value = state.body,
                onValueChange = viewModel::setBody,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.sms_compose_body_label)) },
                minLines = 3,
                enabled = !state.sending
            )
            PrimaryActionButton(
                text = stringResource(R.string.sms_compose_send_action),
                onClick = viewModel::send,
                enabled = state.recipient.isNotBlank() && state.body.isNotBlank() && !state.sending,
                loading = state.sending
            )
        }
    }
}
