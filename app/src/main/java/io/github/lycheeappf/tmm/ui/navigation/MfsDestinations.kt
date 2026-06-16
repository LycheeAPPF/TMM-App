package io.github.lycheeappf.tmm.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class MfsDestination(val route: String, val label: String) {
    Onboarding("onboarding", "Setup"),
    Home("home", "Start"),
    Whitelist("whitelist", "Freigegebene Apps"),
    Channels("channels", "Channels"),
    Settings("settings", "Einstellungen"),
    Assistant("assistant", "Grok-Assistent"),
    Diagnostics("diagnostics", "Diagnose")
}

/**
 * Einträge der unteren NavigationBar: die drei Haupt-Ziele, zwischen denen der
 * User häufig wechselt. Whitelist/Channels/Diagnose sind Detail-/Dev-Routen und
 * bewusst NICHT hier (3–5 Items sind der M3-Sweet-Spot).
 */
enum class MfsBottomNavItem(
    val destination: MfsDestination,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    Home(MfsDestination.Home, "Start", Icons.Filled.Home, Icons.Outlined.Home),
    Assistant(MfsDestination.Assistant, "Grok", Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome),
    Settings(MfsDestination.Settings, "Einstellungen", Icons.Filled.Settings, Icons.Outlined.Settings)
}
