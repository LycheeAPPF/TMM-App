package io.github.lycheeappf.tmm.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schmaler Wrapper um [ConnectivityManager], damit Provider-Calls schon vor dem
 * eigentlichen Retrofit-Aufruf abgebrochen werden können, wenn das Gerät offline
 * ist. Gleichzeitig wird `LlmProviderError.NoNetwork` von echten Netzwerkfehlern
 * (TLS, DNS, Timeout) sauber unterscheidbar.
 *
 * Manche Privacy-ROMs (z.B. GrapheneOS) haben einen per-App-Network-Toggle. Wenn der für unsere App
 * aus ist, meldet [ConnectivityManager] kein passendes Transport-Capability und
 * wir geben dem User in der Antwort einen klaren Hinweis.
 */
@Singleton
class ConnectivityChecker @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun isOnline(): Boolean {
        val cm = context.getSystemService<ConnectivityManager>() ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
