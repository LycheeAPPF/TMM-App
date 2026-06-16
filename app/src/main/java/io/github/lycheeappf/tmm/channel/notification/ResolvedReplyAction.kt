package io.github.lycheeappf.tmm.channel.notification

import android.app.PendingIntent
import android.app.RemoteInput

/**
 * Ergebnis aus [ActionResolver]: eine Notification.Action mit RemoteInput-Eingabe,
 * extrahiert in serialisierbare Dependencies. PendingIntent ist Plattform-Token,
 * lebt nur in-process – persistente Recovery via [PendingIntentRebuilder].
 */
data class ResolvedReplyAction(
    val actionIntent: PendingIntent,
    val remoteInputs: List<RemoteInput>,
    val capturedAt: Long
)
