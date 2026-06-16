package io.github.lycheeappf.tmm.ui.screen.onboarding

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Koordiniert den Pre-Flight-Test mit dem OutboundSmsObserver. Während der Test
 * läuft, soll der Observer die Test-SMS NICHT als normalen Tesla-Reply behandeln
 * (sonst würde er den Outbox-Row löschen, bevor der Tester den finalen TYPE
 * lesen kann).
 *
 * Ein Reservation enthält die exakte Adresse + Zeitfenster. Der Observer prüft
 * via `isReservedForPreflight()`, ob er die Row dem Tester überlassen soll.
 */
@Singleton
class PreFlightCoordinator @Inject constructor() {

    private val reservation = AtomicReference<Reservation?>(null)

    fun reserve(address: String, validForMs: Long = 90_000L) {
        reservation.set(
            Reservation(
                address = address,
                expiresAt = System.currentTimeMillis() + validForMs
            )
        )
    }

    fun release() {
        reservation.set(null)
    }

    fun isReservedForPreflight(address: String): Boolean {
        val current = reservation.get() ?: return false
        if (System.currentTimeMillis() > current.expiresAt) {
            reservation.compareAndSet(current, null)
            return false
        }
        return current.address == address
    }

    private data class Reservation(val address: String, val expiresAt: Long)
}
