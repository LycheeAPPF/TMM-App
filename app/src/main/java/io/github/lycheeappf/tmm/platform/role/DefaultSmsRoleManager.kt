package io.github.lycheeappf.tmm.platform.role

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultSmsRoleManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val roleManager: RoleManager? =
        context.getSystemService(Context.ROLE_SERVICE) as? RoleManager

    /**
     * Prüft, ob die App aktuell die SMS-Default-Rolle innehat.
     * Kombiniert zwei API-Pfade (OR), weil je nach OEM oder Privacy-ROM-Build
     * die eine oder andere stale Ergebnisse liefern kann.
     */
    fun isDefault(): Boolean = detailedStatus().isDefault

    /** Liefert beide Check-Ergebnisse + Package-Vergleich für UI-Diagnose. */
    fun detailedStatus(): Status {
        val viaRoleManager = roleManager?.isRoleHeld(RoleManager.ROLE_SMS) ?: false
        val defaultPkg = runCatching {
            Telephony.Sms.getDefaultSmsPackage(context)
        }.getOrNull()
        val ourPkg = context.packageName
        val viaTelephony = defaultPkg == ourPkg
        val status = Status(
            isDefault = viaRoleManager || viaTelephony,
            roleManagerHeld = viaRoleManager,
            telephonyMatches = viaTelephony,
            currentDefaultPackage = defaultPkg,
            ourPackage = ourPkg
        )
        Log.d(TAG, status.toString())
        return status
    }

    data class Status(
        val isDefault: Boolean,
        val roleManagerHeld: Boolean,
        val telephonyMatches: Boolean,
        val currentDefaultPackage: String?,
        val ourPackage: String
    )

    fun isRoleAvailable(): Boolean =
        roleManager?.isRoleAvailable(RoleManager.ROLE_SMS) == true

    fun createRequestIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return roleManager?.createRequestRoleIntent(RoleManager.ROLE_SMS)
    }

    fun requestRoleFrom(activity: Activity, requestCode: Int): Boolean {
        val intent = createRequestIntent() ?: return false
        activity.startActivityForResult(intent, requestCode)
        return true
    }

    companion object {
        private const val TAG = "DefaultSmsRoleManager"
    }
}
