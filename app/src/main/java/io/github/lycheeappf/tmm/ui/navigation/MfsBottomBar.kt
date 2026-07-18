package io.github.lycheeappf.tmm.ui.navigation

import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import io.github.lycheeappf.tmm.R

/**
 * Geteilte untere NavigationBar. Der ausgewählte Zustand leitet sich rein aus der
 * aktuellen Route ab (currentBackStackEntryAsState), sodass Back-Stack und
 * Prozess-Tod-Restore korrekt bleiben. Das SMS-Item trägt ein Unread-Badge
 * (Anzahl ungelesener echter SMS, „99+"-gekappt). Das [badgeViewModel] wird vom
 * Aufrufer (MfsNavHost, Activity-Scope) gereicht — hier per hiltViewModel()
 * aufgelöst wäre der Owner der jeweilige BackStack-Entry und jeder Tab bekäme
 * einen eigenen SMS-Observer.
 */
@Composable
fun MfsBottomBar(
    navController: NavController,
    badgeViewModel: UnreadBadgeViewModel
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val unreadCount by badgeViewModel.unreadCount.collectAsStateWithLifecycle()

    // Observer sieht keine Permission-Wechsel → beim Resume frisch laden.
    LifecycleResumeEffect(Unit) {
        badgeViewModel.refresh()
        onPauseOrDispose {}
    }

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
                    val icon = @Composable {
                        Icon(
                            imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                            contentDescription = label
                        )
                    }
                    if (item == MfsBottomNavItem.Sms && unreadCount > 0) {
                        val badgeDesc =
                            pluralStringResource(R.plurals.nav_sms_badge_desc, unreadCount, unreadCount)
                        BadgedBox(
                            badge = {
                                Badge(modifier = Modifier.semantics { contentDescription = badgeDesc }) {
                                    Text(UnreadBadgeViewModel.formatBadgeCount(unreadCount))
                                }
                            }
                        ) { icon() }
                    } else {
                        icon()
                    }
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
