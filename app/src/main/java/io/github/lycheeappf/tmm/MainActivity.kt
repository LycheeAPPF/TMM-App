package io.github.lycheeappf.tmm

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.lycheeappf.tmm.platform.tesla.auth.TeslaAuthManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // OAuth-Callback auch bei Kaltstart verarbeiten.
        intent?.data?.let { teslaAuthManager.postCallbackUri(it) }
        setContent {
            MfsTheme {
                MfsApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Tesla-OAuth-Redirect: io.github.lycheeappf.tmm://tesla/callback?code=...
        intent.data?.let { teslaAuthManager.postCallbackUri(it) }
    }
}

@Composable
private fun MfsApp(rootViewModel: RootViewModel = hiltViewModel()) {
    val startDestination by rootViewModel.startDestination.collectAsStateWithLifecycle()
    val developerMode by rootViewModel.developerMode.collectAsStateWithLifecycle()
    val navController = rememberNavController()

    // Kein äusseres Scaffold mehr — jeder Screen bringt via MfsScaffold seine
    // eigene TopAppBar (+ optional die geteilte BottomBar) mit.
    val dest = startDestination
    if (dest == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
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

    init {
        viewModelScope.launch {
            val onboarded = withContext(ioDispatcher) { settingsStore.isOnboarded() }
            _startDestination.value =
                if (onboarded) MfsDestination.Home else MfsDestination.Onboarding
        }
    }
}
