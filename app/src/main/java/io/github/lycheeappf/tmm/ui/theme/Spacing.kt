package io.github.lycheeappf.tmm.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 4-dp-basierte Abstands-Skala. Bewusst ein einfaches `object` (kein
 * CompositionLocal): Abstände variieren nie mit Theme/Dark-Mode/Dynamic-Color,
 * also wäre ein Local nur Laufzeit-Indirektion ohne Nutzen. Import-and-go.
 *
 * Migration: `padding(24.dp)` → `xl`, Karten-`padding(16.dp)` → `lg`,
 * `spacedBy(16/20.dp)` → `lg`/`xl`, `spacedBy(8.dp)` → `sm`, `12.dp` → `md`,
 * trailing `Spacer(40.dp)` → `listBottom` bzw. `contentPadding`.
 */
object MfsSpacing {
    val none = 0.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    /** Unterer Innenabstand für scrollende Listen, damit der letzte Eintrag frei steht. */
    val listBottom = 40.dp
}

/** Elevation-Tokens (tonale M3-Surfaces übernehmen die meiste Tiefe). */
object MfsElevation {
    val level0 = 0.dp
    val level1 = 1.dp
    val level2 = 3.dp
    val level3 = 6.dp
}
