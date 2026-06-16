package io.github.lycheeappf.tmm.domain.reply

/**
 * Ergebnis eines Reply-Versuchs eines [io.github.lycheeappf.tmm.domain.channel.MessagingChannel].
 *
 * V1: Success / NoActionAvailable / PendingIntentCanceled / NoRemoteInput / PayloadMismatch.
 * V2: + [FollowUp] (Channel hat eine eigene Antwort, die der Dispatcher als
 *   Follow-up an den Tesla zurückspielen soll) und [Ignored] (Channel hat den
 *   Reply bewusst verworfen, z.B. Blank-Text — kein Statistik-Update, kein
 *   Follow-up-Inject).
 */
sealed class ReplyResult {
    data object Success : ReplyResult()
    data class FollowUp(val body: String) : ReplyResult()
    data object Ignored : ReplyResult()
    data object PayloadMismatch : ReplyResult()
    data object NoActionAvailable : ReplyResult()
    data object PendingIntentCanceled : ReplyResult()
    data object NoRemoteInput : ReplyResult()
    data class ProviderError(val message: String) : ReplyResult()
    data object Expired : ReplyResult()

    val isSuccess: Boolean get() = this is Success || this is FollowUp
}
