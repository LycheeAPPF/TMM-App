package io.github.lycheeappf.tmm.ui.screen.whitelist

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lycheeappf.tmm.core.di.IoDispatcher
import io.github.lycheeappf.tmm.data.db.AppPolicyDao
import io.github.lycheeappf.tmm.data.db.AppPolicyEntity
import io.github.lycheeappf.tmm.data.repository.RoomAppPolicyProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class WhitelistItem(
    val packageName: String,
    val displayName: String,
    val isSystemApp: Boolean,
    val isWhitelisted: Boolean
)

data class WhitelistUiState(
    val items: List<WhitelistItem> = emptyList(),
    val showSystemApps: Boolean = false,
    val filter: String = "",
    val loading: Boolean = true
)

@HiltViewModel
class WhitelistViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: AppPolicyDao,
    private val cache: RoomAppPolicyProvider,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val _uiState = MutableStateFlow(WhitelistUiState())
    val uiState: StateFlow<WhitelistUiState> = _uiState.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            val items = withContext(ioDispatcher) { fetchInstalledApps() }
            _uiState.update { it.copy(items = items, loading = false) }
        }
    }

    fun toggleSystemApps() {
        _uiState.update { it.copy(showSystemApps = !it.showSystemApps) }
    }

    fun setFilter(value: String) {
        _uiState.update { it.copy(filter = value) }
    }

    fun setWhitelisted(packageName: String, value: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            val existing = dao.get(packageName)
            if (existing != null) {
                dao.setWhitelisted(packageName, value)
            } else {
                val pm = context.packageManager
                val label = runCatching {
                    pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
                }.getOrDefault(packageName)
                dao.upsert(
                    AppPolicyEntity(
                        packageName = packageName,
                        whitelisted = value,
                        customDisplayName = label,
                        lastSeenRemoteInput = false,
                        lastSeenAt = System.currentTimeMillis()
                    )
                )
            }
            cache.invalidateCache(packageName)
            // Nur die betroffene Zeile in-memory aktualisieren statt die komplette
            // App-Liste neu zu scannen. Der frühere `load()` rief pro Toggle
            // getInstalledApplications() + N×loadLabel() auf — der Haupt-Jank-
            // Verursacher. Sortierung bleibt stabil, damit die Zeile beim Tippen
            // nicht wegspringt.
            _uiState.update { state ->
                state.copy(
                    items = state.items.map { item ->
                        if (item.packageName == packageName) item.copy(isWhitelisted = value)
                        else item
                    }
                )
            }
        }
    }

    private suspend fun fetchInstalledApps(): List<WhitelistItem> {
        val pm = context.packageManager
        val whitelisted = dao.whitelistedPackages().toHashSet()
        val allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.packageName != context.packageName }

        return allApps.map { info ->
            WhitelistItem(
                packageName = info.packageName,
                displayName = runCatching { info.loadLabel(pm).toString() }
                    .getOrDefault(info.packageName),
                isSystemApp = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                isWhitelisted = info.packageName in whitelisted
            )
        }.sortedWith(compareByDescending<WhitelistItem> { it.isWhitelisted }
            .thenBy { it.displayName.lowercase() })
    }
}
