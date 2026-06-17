package io.github.lycheeappf.tmm.ui.navigation

import androidx.compose.runtime.Composable
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
                        popUpTo(MfsDestination.Onboarding.route) { inclusive = true }
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
                onOpenChannels = { navController.navigate(MfsDestination.Channels.route) }
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
        composable(MfsDestination.SmsCompose.route) {
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
