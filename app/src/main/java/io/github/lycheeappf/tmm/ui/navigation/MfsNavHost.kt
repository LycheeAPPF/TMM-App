package io.github.lycheeappf.tmm.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.github.lycheeappf.tmm.ui.screen.assistant.AssistantScreen
import io.github.lycheeappf.tmm.ui.screen.channels.ChannelsScreen
import io.github.lycheeappf.tmm.ui.screen.diagnostics.DiagnosticsScreen
import io.github.lycheeappf.tmm.ui.screen.home.HomeScreen
import io.github.lycheeappf.tmm.ui.screen.onboarding.OnboardingScreen
import io.github.lycheeappf.tmm.ui.screen.settings.SettingsScreen
import io.github.lycheeappf.tmm.ui.screen.sms.SmsComposeScreen
import io.github.lycheeappf.tmm.ui.screen.sms.SmsConversationsScreen
import io.github.lycheeappf.tmm.ui.screen.sms.SmsThreadScreen
import io.github.lycheeappf.tmm.ui.screen.whitelist.WhitelistScreen

@Composable
fun MfsNavHost(
    navController: NavHostController,
    startDestination: MfsDestination,
    developerMode: Boolean
) {
    // Geteilte BottomBar — nur an die Haupt-Routen gehängt; Onboarding & Detail-/
    // Dev-Screens bekommen sie nicht (sie nutzen stattdessen einen Zurück-Pfeil).
    val bottomBar: @Composable () -> Unit = { MfsBottomBar(navController) }

    NavHost(
        navController = navController,
        startDestination = startDestination.route
    ) {
        composable(MfsDestination.Onboarding.route) {
            OnboardingScreen(
                onFinished = {
                    navController.navigate(MfsDestination.Home.route) {
                        // Kompletten Back-Stack leeren — korrekt für BEIDE Einstiege:
                        // Erststart (Start=Onboarding) und „Setup erneut" aus den Settings
                        // (Start=Home). Danach liegt nur Home auf dem Stack, Zurück verlässt
                        // die App (kein doppeltes Home, kein verwaister Settings-Eintrag).
                        popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onOpenWhitelist = { navController.navigate(MfsDestination.Whitelist.route) }
            )
        }
        composable(MfsDestination.Home.route) {
            HomeScreen(
                bottomBar = bottomBar,
                onOpenWhitelist = { navController.navigate(MfsDestination.Whitelist.route) },
                onOpenAssistant = { navController.navigateToTab(MfsDestination.Assistant.route) }
            )
        }
        composable(MfsDestination.Whitelist.route) {
            WhitelistScreen(onBack = { navController.popBackStack() })
        }
        composable(MfsDestination.Settings.route) {
            SettingsScreen(
                bottomBar = bottomBar,
                onOpenWhitelist = { navController.navigate(MfsDestination.Whitelist.route) },
                onOpenDiagnostics = { navController.navigate(MfsDestination.Diagnostics.route) },
                onOpenChannels = { navController.navigate(MfsDestination.Channels.route) },
                // Setup erneut ansehen (Dev): Onboarding-Screen öffnen. Setzt das
                // Onboarded-Flag NICHT zurück — die Schritte zeigen ihren Ist-Zustand;
                // „Fertig" landet wieder auf Home.
                onRestartSetup = { navController.navigate(MfsDestination.Onboarding.route) }
            )
        }
        composable(MfsDestination.Assistant.route) {
            AssistantScreen(bottomBar = bottomBar)
        }
        // Echte-SMS-Inbox: Haupt-Tab (mit BottomBar). Thread ist eine Detail-Route
        // mit Zurück-Pfeil und Long-Nav-Argument (erste Arg-Route der App).
        composable(MfsDestination.Sms.route) {
            SmsConversationsScreen(
                bottomBar = bottomBar,
                onOpenThread = { threadId -> navController.navigate(smsThreadRoute(threadId)) },
                onCompose = { navController.navigate(MfsDestination.SmsCompose.route) }
            )
        }
        composable(
            route = MfsDestination.SmsThread.route,
            arguments = listOf(navArgument(ARG_THREAD_ID) { type = NavType.LongType })
        ) {
            SmsThreadScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = "${MfsDestination.SmsCompose.route}?recipient={recipient}&body={body}",
            arguments = listOf(
                navArgument("recipient") {
                    type = NavType.StringType; nullable = true; defaultValue = null
                },
                navArgument("body") {
                    type = NavType.StringType; nullable = true; defaultValue = null
                }
            )
        ) {
            SmsComposeScreen(onBack = { navController.popBackStack() })
        }
        // Entwickler-/Diagnose-Oberflächen: nur registriert wenn Developer-Mode an
        // ist. Erreichbar ausschliesslich über den "Entwickler"-Abschnitt in den
        // Einstellungen. Detail-Routen mit Zurück-Pfeil, keine BottomBar.
        if (developerMode) {
            composable(MfsDestination.Channels.route) {
                ChannelsScreen(onBack = { navController.popBackStack() })
            }
            composable(MfsDestination.Diagnostics.route) {
                DiagnosticsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
