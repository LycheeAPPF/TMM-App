package io.github.lycheeappf.tmm.ui.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState

/**
 * Geteilte untere NavigationBar. Der ausgewählte Zustand leitet sich rein aus der
 * aktuellen Route ab (currentBackStackEntryAsState), sodass Back-Stack und
 * Prozess-Tod-Restore korrekt bleiben.
 */
@Composable
fun MfsBottomBar(navController: NavController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    NavigationBar {
        MfsBottomNavItem.entries.forEach { item ->
            val selected = currentRoute == item.destination.route
            val label = stringResource(item.labelRes)
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (!selected) navController.navigateToTab(item.destination.route)
                },
                icon = {
                    Icon(
                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                        contentDescription = label
                    )
                },
                label = { Text(label) }
            )
        }
    }
}

/**
 * Tab-Navigation: single-top + State-Save/Restore, sodass das Wechseln zwischen
 * Haupt-Zielen den Back-Stack nicht aufbläht und Scroll-Positionen erhält.
 *
 * Bewusst popUpTo(Home) statt popUpTo(graph start): die Graph-Start-Destination ist
 * dynamisch (Onboarding solange nicht eingerichtet) und wird nach Abschluss des
 * Onboardings inklusive aus dem Back-Stack entfernt. Home ist die echte Basis der
 * Haupt-Tabs, also poppen wir explizit dorthin — sonst zielte popUpTo auf die
 * bereits entfernte Onboarding-Destination und der Back-Stack/Save-Restore liefe falsch.
 */
fun NavController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(MfsDestination.Home.route) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
