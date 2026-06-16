package io.github.lycheeappf.tmm.platform.compat

import android.os.Build

/**
 * Minimal-Helper für SDK-Gating. Aktuell minSdk 33, daher hauptsächlich
 * obere Limits (ab welcher Version gilt eine neue Restriction).
 */
object ApiCompat {
    inline val isAndroid14OrHigher get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    inline val isAndroid15OrHigher get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
}
