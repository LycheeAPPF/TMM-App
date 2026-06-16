package io.github.lycheeappf.tmm.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Material-3-Tonalpalette für das gebrandete (nicht-dynamische) Schema.
 *
 * Marken-Seed: "Graphite + Signal-Red" (≈ #C8102E) — ein klares, ruhiges Rot
 * statt des rohen Feuerwehr-Rots der ersten Version. Die Rollen sind in sich
 * konsistent (hell + dunkel) und decken ALLE M3-Slots inkl. der
 * `surfaceContainer*`-Familie ab, die in M3 1.4 die Karten-/Sheet-Elevation steuert.
 *
 * Auf Android 12+ (minSdk 33 → immer) übernimmt standardmässig Material You
 * (dynamicColor). Diese Palette ist der Marken-Fallback, wenn dynamicColor aus ist.
 */

// ---------- Light ----------
val md_primary = Color(0xFFB3261E)
val md_onPrimary = Color(0xFFFFFFFF)
val md_primaryContainer = Color(0xFFFFDAD5)
val md_onPrimaryContainer = Color(0xFF410001)
val md_secondary = Color(0xFF775652)
val md_onSecondary = Color(0xFFFFFFFF)
val md_secondaryContainer = Color(0xFFFFDAD5)
val md_onSecondaryContainer = Color(0xFF2C1512)
val md_tertiary = Color(0xFF6E5D2E)
val md_onTertiary = Color(0xFFFFFFFF)
val md_tertiaryContainer = Color(0xFFF9E1A6)
val md_onTertiaryContainer = Color(0xFF231B00)
val md_error = Color(0xFFBA1A1A)
val md_onError = Color(0xFFFFFFFF)
val md_errorContainer = Color(0xFFFFDAD6)
val md_onErrorContainer = Color(0xFF410002)
val md_background = Color(0xFFFFFBFF)
val md_onBackground = Color(0xFF201A19)
val md_surface = Color(0xFFFFFBFF)
val md_onSurface = Color(0xFF201A19)
val md_surfaceVariant = Color(0xFFF5DDDA)
val md_onSurfaceVariant = Color(0xFF534341)
val md_outline = Color(0xFF857370)
val md_outlineVariant = Color(0xFFD8C2BE)
val md_scrim = Color(0xFF000000)
val md_inverseSurface = Color(0xFF362F2E)
val md_inverseOnSurface = Color(0xFFFBEEEC)
val md_inversePrimary = Color(0xFFFFB4A9)
val md_surfaceDim = Color(0xFFE7D7D4)
val md_surfaceBright = Color(0xFFFFFBFF)
val md_surfaceContainerLowest = Color(0xFFFFFFFF)
val md_surfaceContainerLow = Color(0xFFFFF0EE)
val md_surfaceContainer = Color(0xFFFCEAE7)
val md_surfaceContainerHigh = Color(0xFFF6E4E1)
val md_surfaceContainerHighest = Color(0xFFF0DEDB)

// ---------- Dark ----------
val md_primary_dark = Color(0xFFFFB4A9)
val md_onPrimary_dark = Color(0xFF690002)
val md_primaryContainer_dark = Color(0xFF8C1009)
val md_onPrimaryContainer_dark = Color(0xFFFFDAD5)
val md_secondary_dark = Color(0xFFE7BDB6)
val md_onSecondary_dark = Color(0xFF442925)
val md_secondaryContainer_dark = Color(0xFF5D3F3B)
val md_onSecondaryContainer_dark = Color(0xFFFFDAD5)
val md_tertiary_dark = Color(0xFFDCC58C)
val md_onTertiary_dark = Color(0xFF3B2F05)
val md_tertiaryContainer_dark = Color(0xFF54461A)
val md_onTertiaryContainer_dark = Color(0xFFF9E1A6)
val md_error_dark = Color(0xFFFFB4AB)
val md_onError_dark = Color(0xFF690005)
val md_errorContainer_dark = Color(0xFF93000A)
val md_onErrorContainer_dark = Color(0xFFFFDAD6)
val md_background_dark = Color(0xFF201A19)
val md_onBackground_dark = Color(0xFFEDE0DE)
val md_surface_dark = Color(0xFF181210)
val md_onSurface_dark = Color(0xFFEDE0DE)
val md_surfaceVariant_dark = Color(0xFF534341)
val md_onSurfaceVariant_dark = Color(0xFFD8C2BE)
val md_outline_dark = Color(0xFFA08C89)
val md_outlineVariant_dark = Color(0xFF534341)
val md_scrim_dark = Color(0xFF000000)
val md_inverseSurface_dark = Color(0xFFEDE0DE)
val md_inverseOnSurface_dark = Color(0xFF362F2E)
val md_inversePrimary_dark = Color(0xFFB3261E)
val md_surfaceDim_dark = Color(0xFF181210)
val md_surfaceBright_dark = Color(0xFF3F3735)
val md_surfaceContainerLowest_dark = Color(0xFF120D0C)
val md_surfaceContainerLow_dark = Color(0xFF201A19)
val md_surfaceContainer_dark = Color(0xFF241E1D)
val md_surfaceContainerHigh_dark = Color(0xFF2F2827)
val md_surfaceContainerHighest_dark = Color(0xFF3A3231)

// ---------- Semantische Status-Farben ----------
// M3 hat keine success/warning/info-Rolle. Diese Tokens werden über
// [StatusColors] / LocalStatusColors bereitgestellt, damit Screens keine
// rohen Hex-Literale (0xFF2E7D32 etc.) mehr streuen.
val statusSuccess_light = Color(0xFF2E6B36)
val statusOnSuccess_light = Color(0xFFFFFFFF)
val statusSuccessContainer_light = Color(0xFFB6F2B5)
val statusOnSuccessContainer_light = Color(0xFF00210A)
val statusWarning_light = Color(0xFF8A5300)
val statusOnWarning_light = Color(0xFFFFFFFF)
val statusWarningContainer_light = Color(0xFFFFDDB5)
val statusOnWarningContainer_light = Color(0xFF2B1700)
val statusInfo_light = Color(0xFF1A5FB4)
val statusOnInfo_light = Color(0xFFFFFFFF)
val statusInfoContainer_light = Color(0xFFD6E3FF)
val statusOnInfoContainer_light = Color(0xFF001B3D)

val statusSuccess_dark = Color(0xFF9BD49B)
val statusOnSuccess_dark = Color(0xFF003914)
val statusSuccessContainer_dark = Color(0xFF135223)
val statusOnSuccessContainer_dark = Color(0xFFB6F2B5)
val statusWarning_dark = Color(0xFFFFB951)
val statusOnWarning_dark = Color(0xFF472A00)
val statusWarningContainer_dark = Color(0xFF684000)
val statusOnWarningContainer_dark = Color(0xFFFFDDB5)
val statusInfo_dark = Color(0xFFA8C8FF)
val statusOnInfo_dark = Color(0xFF002F65)
val statusInfoContainer_dark = Color(0xFF00468A)
val statusOnInfoContainer_dark = Color(0xFFD6E3FF)
