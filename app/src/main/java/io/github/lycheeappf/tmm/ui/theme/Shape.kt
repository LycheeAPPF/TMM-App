package io.github.lycheeappf.tmm.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * M3-Shape-Skala. Wird in [MfsTheme] verdrahtet, sodass Komponenten ihre Form
 * aus dem Theme ziehen statt Ecken hart zu kodieren.
 *
 *  - extraSmall: Chips, kleine Surfaces (Code-/Mono-Blöcke)
 *  - small:      kompakte Karten, Eingabefelder
 *  - medium:     Standard-Karten
 *  - large:      prominente Karten, Bottom-Sheets
 *  - extraLarge: Hero-Karten, Dialoge, FABs
 */
val MfsShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/** Vollständig gerundete Pille — für StatusPill/Badge. */
val MfsPillShape = RoundedCornerShape(percent = 50)
