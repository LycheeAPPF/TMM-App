package io.github.lycheeappf.tmm.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistiert die entdeckte Fleet-API-Region des Tesla-Accounts.
 *
 * Die Region wird per Probe entdeckt (Command-Client) und hier abgelegt; der
 * Auth-Manager leitet daraus den `audience`-Parameter für Token-Requests ab —
 * ein Token mit falscher audience ist für keine Fleet-API-Region gültig
 * (412 auf allen Endpunkten). Interface-Seam, damit Tests Fakes nutzen und
 * Discovery-Ergebnisse aus mehreren Stellen (Auth-Flow, Command-Client)
 * hineingeschrieben werden können.
 */
interface TeslaRegionStore {

    /** Basis-URL der Fleet-API-Region (inkl. trailing slash) oder null = noch unbekannt. */
    suspend fun readFleetApiBaseUrl(): String?

    /** Persistiert die entdeckte Region; null verwirft sie (Logout / Region passt nicht mehr). */
    suspend fun writeFleetApiBaseUrl(url: String?)

    /** `audience`-Parameter für Token-Requests, abgeleitet aus der Region; null = unbekannt. */
    suspend fun readTokenAudience(): String? = readFleetApiBaseUrl()?.trimEnd('/')
}

/**
 * DataStore-Implementierung — nutzt dasselbe Preferences-File (und denselben
 * Key) wie bisher der Token-Store, damit die bereits entdeckte Region von
 * Bestandsinstallationen erhalten bleibt.
 */
@Singleton
class DataStoreTeslaRegionStore @Inject constructor(
    @ApplicationContext context: Context
) : TeslaRegionStore {

    private val store: DataStore<Preferences> = context.teslaAuthDataStore

    override suspend fun readFleetApiBaseUrl(): String? = store.data.first()[KEY_FLEET_URL]

    override suspend fun writeFleetApiBaseUrl(url: String?) {
        store.edit { if (url != null) it[KEY_FLEET_URL] = url else it.remove(KEY_FLEET_URL) }
    }

    companion object {
        private val KEY_FLEET_URL = stringPreferencesKey("tesla_fleet_api_base_url")
    }
}
