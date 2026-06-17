package io.github.lycheeappf.tmm.platform.permission

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lycheeappf.tmm.listener.NotificationForwardingService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bündelt alle Permission-Checks an einem Ort. Runtime-Permissions werden in
 * den Compose-Screens via rememberLauncherForActivityResult angefordert.
 */
@Singleton
class PermissionGate @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun hasPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasReadPhoneState(): Boolean =
        ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * READ_SMS für die echte-SMS-Inbox (Lesen aus `content://sms`). Wird durch
     * die ROLE_SMS-Default-Rolle impliziert, hier aber explizit geprüft, um den
     * Fall „App nicht (mehr) Default" sauber im SMS-Screen zu behandeln.
     */
    fun hasReadSms(): Boolean =
        ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED

    /** SEND_SMS für das Senden echter SMS aus der App. Durch ROLE_SMS impliziert. */
    fun hasSendSms(): Boolean =
        ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * READ + WRITE Contacts werden benötigt, um den Tesla-Bridge-Account zu
     * pflegen (Sender-Name-Auflösung für MAP/PBAP). Optional — ohne Permission
     * fallen wir auf Anzeige der Rohnummer zurück.
     */
    fun hasContactsAccess(): Boolean {
        val read = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        val write = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.WRITE_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        return read && write
    }

    /**
     * Notification-Listener Access ist nicht runtime-grantbar – User muss in
     * Settings > Apps > Special access > Notification access aktivieren.
     */
    fun hasNotificationListenerAccess(): Boolean {
        val componentName = ComponentName(context, NotificationForwardingService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ).orEmpty()
        return enabled.split(":").any { ComponentName.unflattenFromString(it) == componentName }
    }

    fun openNotificationListenerSettings(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
}
