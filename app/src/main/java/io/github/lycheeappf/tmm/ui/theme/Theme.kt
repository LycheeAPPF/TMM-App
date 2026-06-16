package io.github.lycheeappf.tmm.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = md_primary,
    onPrimary = md_onPrimary,
    primaryContainer = md_primaryContainer,
    onPrimaryContainer = md_onPrimaryContainer,
    secondary = md_secondary,
    onSecondary = md_onSecondary,
    secondaryContainer = md_secondaryContainer,
    onSecondaryContainer = md_onSecondaryContainer,
    tertiary = md_tertiary,
    onTertiary = md_onTertiary,
    tertiaryContainer = md_tertiaryContainer,
    onTertiaryContainer = md_onTertiaryContainer,
    error = md_error,
    onError = md_onError,
    errorContainer = md_errorContainer,
    onErrorContainer = md_onErrorContainer,
    background = md_background,
    onBackground = md_onBackground,
    surface = md_surface,
    onSurface = md_onSurface,
    surfaceVariant = md_surfaceVariant,
    onSurfaceVariant = md_onSurfaceVariant,
    outline = md_outline,
    outlineVariant = md_outlineVariant,
    scrim = md_scrim,
    inverseSurface = md_inverseSurface,
    inverseOnSurface = md_inverseOnSurface,
    inversePrimary = md_inversePrimary,
    surfaceDim = md_surfaceDim,
    surfaceBright = md_surfaceBright,
    surfaceContainerLowest = md_surfaceContainerLowest,
    surfaceContainerLow = md_surfaceContainerLow,
    surfaceContainer = md_surfaceContainer,
    surfaceContainerHigh = md_surfaceContainerHigh,
    surfaceContainerHighest = md_surfaceContainerHighest
)

private val DarkColors = darkColorScheme(
    primary = md_primary_dark,
    onPrimary = md_onPrimary_dark,
    primaryContainer = md_primaryContainer_dark,
    onPrimaryContainer = md_onPrimaryContainer_dark,
    secondary = md_secondary_dark,
    onSecondary = md_onSecondary_dark,
    secondaryContainer = md_secondaryContainer_dark,
    onSecondaryContainer = md_onSecondaryContainer_dark,
    tertiary = md_tertiary_dark,
    onTertiary = md_onTertiary_dark,
    tertiaryContainer = md_tertiaryContainer_dark,
    onTertiaryContainer = md_onTertiaryContainer_dark,
    error = md_error_dark,
    onError = md_onError_dark,
    errorContainer = md_errorContainer_dark,
    onErrorContainer = md_onErrorContainer_dark,
    background = md_background_dark,
    onBackground = md_onBackground_dark,
    surface = md_surface_dark,
    onSurface = md_onSurface_dark,
    surfaceVariant = md_surfaceVariant_dark,
    onSurfaceVariant = md_onSurfaceVariant_dark,
    outline = md_outline_dark,
    outlineVariant = md_outlineVariant_dark,
    scrim = md_scrim_dark,
    inverseSurface = md_inverseSurface_dark,
    inverseOnSurface = md_inverseOnSurface_dark,
    inversePrimary = md_inversePrimary_dark,
    surfaceDim = md_surfaceDim_dark,
    surfaceBright = md_surfaceBright_dark,
    surfaceContainerLowest = md_surfaceContainerLowest_dark,
    surfaceContainerLow = md_surfaceContainerLow_dark,
    surfaceContainer = md_surfaceContainer_dark,
    surfaceContainerHigh = md_surfaceContainerHigh_dark,
    surfaceContainerHighest = md_surfaceContainerHighest_dark
)

/**
 * Semantische Status-Farben (Erfolg/Warnung/Info). M3 kennt keine solche Rolle,
 * darum als eigenes Token-Set, das mit Dark-Mode umschaltet — bereitgestellt via
 * [LocalStatusColors] und abgerufen über [MfsColors].status.
 */
@Immutable
data class StatusColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val info: Color,
    val onInfo: Color,
    val infoContainer: Color,
    val onInfoContainer: Color
)

private val LightStatusColors = StatusColors(
    success = statusSuccess_light,
    onSuccess = statusOnSuccess_light,
    successContainer = statusSuccessContainer_light,
    onSuccessContainer = statusOnSuccessContainer_light,
    warning = statusWarning_light,
    onWarning = statusOnWarning_light,
    warningContainer = statusWarningContainer_light,
    onWarningContainer = statusOnWarningContainer_light,
    info = statusInfo_light,
    onInfo = statusOnInfo_light,
    infoContainer = statusInfoContainer_light,
    onInfoContainer = statusOnInfoContainer_light
)

private val DarkStatusColors = StatusColors(
    success = statusSuccess_dark,
    onSuccess = statusOnSuccess_dark,
    successContainer = statusSuccessContainer_dark,
    onSuccessContainer = statusOnSuccessContainer_dark,
    warning = statusWarning_dark,
    onWarning = statusOnWarning_dark,
    warningContainer = statusWarningContainer_dark,
    onWarningContainer = statusOnWarningContainer_dark,
    info = statusInfo_dark,
    onInfo = statusOnInfo_dark,
    infoContainer = statusInfoContainer_dark,
    onInfoContainer = statusOnInfoContainer_dark
)

val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }

/** Accessor analog zu `MaterialTheme.colorScheme` — `MfsColors.status.success`. */
object MfsColors {
    val status: StatusColors
        @Composable @ReadOnlyComposable
        get() = LocalStatusColors.current
}

@Composable
fun MfsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    // Status-Farben bleiben gebrandet (semantische Klarheit > Tonal-Match), auch
    // unter dynamicColor — Standardpraxis für success/warning/info.
    val statusColors = if (darkTheme) DarkStatusColors else LightStatusColors

    // Hinweis: M3-1.4.0 hält `MaterialExpressiveTheme`/`MotionScheme` noch `internal`
    // (Alpha-Track). Wir setzen die Expressive-Designsprache daher über das stabile
    // [MaterialTheme] um: volle Farb-/Typo-/Shape-Token + Spring-Motion via [MfsMotion].
    CompositionLocalProvider(LocalStatusColors provides statusColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MfsTypography,
            shapes = MfsShapes,
            content = content
        )
    }
}
