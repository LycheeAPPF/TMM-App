package io.github.lycheeappf.tmm.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.lycheeappf.tmm.R

// Labels als @StringRes (nicht als String): ein String würde beim enum-Class-Load
// in der damaligen Sprache eingefroren — die Resource-ID löst pro Render korrekt auf.
enum class MfsDestination(val route: String, @StringRes val labelRes: Int) {
    Onboarding("onboarding", R.string.nav_onboarding_label),
    Home("home", R.string.nav_home_label),
    Whitelist("whitelist", R.string.nav_whitelist_label),
    Channels("channels", R.string.nav_channels_label),
    Settings("settings", R.string.nav_settings_label),
    Assistant("assistant", R.string.nav_assistant_label),
    Diagnostics("diagnostics", R.string.nav_diagnostics_label),
    Sms("sms", R.string.nav_sms_label),
    SmsThread("sms_thread/{$ARG_THREAD_ID}", R.string.nav_sms_thread_label),
    SmsCompose("sms_compose", R.string.nav_sms_compose_label)
}

/** Arg-Key der SMS-Thread-Route (eine echte Nav-Argument-Route). */
const val ARG_THREAD_ID = "threadId"

/** Konkrete Route in einen SMS-Thread mit gegebener `thread_id`. */
fun smsThreadRoute(threadId: Long): String = "sms_thread/$threadId"

/** Konkrete Route zum SMS-Compose-Screen, optional mit vorausgefülltem Empfänger/Text. */
fun smsComposeRoute(recipient: String? = null, body: String? = null): String = buildString {
    append(MfsDestination.SmsCompose.route)
    val params = listOfNotNull(
        recipient?.takeIf { it.isNotBlank() }
            ?.let { "recipient=${android.net.Uri.encode(it)}" },
        body?.takeIf { it.isNotBlank() }
            ?.let { "body=${android.net.Uri.encode(it)}" }
    )
    if (params.isNotEmpty()) append("?${params.joinToString("&")}")
}

/**
 * Einträge der unteren NavigationBar: die drei Haupt-Ziele, zwischen denen der
 * User häufig wechselt. Whitelist/Channels/Diagnose sind Detail-/Dev-Routen und
 * bewusst NICHT hier (3–5 Items sind der M3-Sweet-Spot).
 */
enum class MfsBottomNavItem(
    val destination: MfsDestination,
    @StringRes val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    Home(MfsDestination.Home, R.string.nav_home_label, Icons.Filled.Home, Icons.Outlined.Home),
    Sms(MfsDestination.Sms, R.string.nav_sms_label, Icons.Filled.Sms, Icons.Outlined.Sms),
    Assistant(MfsDestination.Assistant, R.string.nav_tab_assistant, Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome),
    Settings(MfsDestination.Settings, R.string.nav_settings_label, Icons.Filled.Settings, Icons.Outlined.Settings)
}
