package io.github.lycheeappf.tmm

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.lycheeappf.tmm.platform.tesla.auth.TeslaAuthManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lycheeappf.tmm.core.di.IoDispatcher
import io.github.lycheeappf.tmm.data.store.SettingsStore
import io.github.lycheeappf.tmm.ui.navigation.MfsDestination
import io.github.lycheeappf.tmm.ui.navigation.MfsNavHost
import io.github.lycheeappf.tmm.ui.navigation.smsComposeRoute
import io.github.lycheeappf.tmm.ui.navigation.smsThreadRoute
import io.github.lycheeappf.tmm.ui.theme.MfsTheme
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var teslaAuthManager: TeslaAuthManager
    private val rootViewModel: RootViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        intent?.data?.let { teslaAuthManager.postCallbackUri(it) }
        handleSmsIntent(intent)
        setContent {
            MfsTheme {
                MfsApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { teslaAuthManager.postCallbackUri(it) }
        handleSmsIntent(intent)
    }

    private fun handleSmsIntent(intent: Intent?) {
        when {
            intent?.hasExtra(EXTRA_THREAD_ID) == true -> {
                val threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1L)
                rootViewModel.postThreadRequest(threadId)
            }
            intent?.hasExtra(EXTRA_COMPOSE_RECIPIENT) == true -> {
                rootViewModel.postComposeRequest(
                    recipient = intent.getStringExtra(EXTRA_COMPOSE_RECIPIENT) ?: "",
                    body = intent.getStringExtra(EXTRA_COMPOSE_BODY) ?: ""
                )
            }
        }
    }

    companion object {
        private const val EXTRA_COMPOSE_RECIPIENT = "compose_recipient"
        private const val EXTRA_COMPOSE_BODY = "compose_body"
        const val EXTRA_THREAD_ID = "sms_thread_id"

        fun composeIntent(context: Context, recipient: String?, body: String?): Intent =
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_COMPOSE_RECIPIENT, recipient ?: "")
                if (body != null) putExtra(EXTRA_COMPOSE_BODY, body)
            }

        fun threadIntent(context: Context, threadId: Long): Intent =
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_THREAD_ID, threadId)
            }
    }
}

@Composable
private fun MfsApp(rootViewModel: RootViewModel = hiltViewModel()) {
    val startDestination by rootViewModel.startDestination.collectAsStateWithLifecycle()
    val developerMode by rootViewModel.developerMode.collectAsStateWithLifecycle()
    val composeRequest by rootViewModel.composeRequest.collectAsStateWithLifecycle()
    val threadRequest by rootViewModel.threadRequest.collectAsStateWithLifecycle()
    val navController = rememberNavController()

    val dest = startDestination
    if (dest == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        LaunchedEffect(composeRequest) {
            composeRequest?.let { (recipient, body) ->
                navController.navigate(
                    smsComposeRoute(
                        recipient.takeIf { it.isNotEmpty() },
                        body.takeIf { it.isNotEmpty() }
                    )
                )
                rootViewModel.consumeComposeRequest()
            }
        }
        LaunchedEffect(threadRequest) {
            val tid = threadRequest ?: return@LaunchedEffect
            if (tid > 0L) navController.navigate(smsThreadRoute(tid))
            else navController.navigate(MfsDestination.Sms.route)
            rootViewModel.consumeThreadRequest()
        }
        MfsNavHost(
            navController = navController,
            startDestination = dest,
            developerMode = developerMode
        )
    }
}

@HiltViewModel
class RootViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {
    private val _startDestination = MutableStateFlow<MfsDestination?>(null)
    val startDestination: StateFlow<MfsDestination?> = _startDestination.asStateFlow()

    /** Live-Flow (kein One-Shot-Read): ein Toggle in Settings wirkt ohne App-Neustart. */
    val developerMode: StateFlow<Boolean> =
        settingsStore.developerModeFlow()
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _composeRequest = MutableStateFlow<Pair<String, String>?>(null)
    val composeRequest: StateFlow<Pair<String, String>?> = _composeRequest.asStateFlow()

    private val _threadRequest = MutableStateFlow<Long?>(null)
    val threadRequest: StateFlow<Long?> = _threadRequest.asStateFlow()

    fun postComposeRequest(recipient: String, body: String) {
        _composeRequest.value = recipient to body
    }

    fun consumeComposeRequest() {
        _composeRequest.value = null
    }

    fun postThreadRequest(threadId: Long) {
        _threadRequest.value = threadId
    }

    fun consumeThreadRequest() {
        _threadRequest.value = null
    }

    init {
        viewModelScope.launch {
            val onboarded = withContext(ioDispatcher) { settingsStore.isOnboarded() }
            _startDestination.value =
                if (onboarded) MfsDestination.Home else MfsDestination.Onboarding
        }
    }
}
