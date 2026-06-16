package io.github.lycheeappf.tmm.listener.filter

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Entscheidet, ob eine Notification eines Packages ans Tesla weitergeleitet wird.
 *
 * Standardmäßig ist keine App freigegeben. Der User aktiviert die gewünschten
 * Messenger (z.B. WhatsApp, Telegram, Signal) in der UI. Die Whitelist wird aus
 * dem AppPolicyDao gespeist.
 */
@Singleton
class WhitelistFilter @Inject constructor(
    private val policyProvider: AppPolicyProvider
) {

    suspend fun allow(packageName: String): Boolean =
        policyProvider.isWhitelisted(packageName)
}

/**
 * Abstraktion über AppPolicyDao (Phase 4). Erlaubt WhitelistFilter zu
 * kompilieren, bevor Room verkabelt ist.
 */
interface AppPolicyProvider {
    suspend fun isWhitelisted(packageName: String): Boolean
}

