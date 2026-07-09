package io.github.lycheeappf.tmm.data.store

import kotlinx.coroutines.flow.Flow

/**
 * Zwischenzustand eines laufenden OAuth-Flows: pro [startAuth]-Aufruf frisch
 * per SecureRandom generierter `state`-Parameter + PKCE-Verifier. Persistiert
 * (statt in-memory), damit der Callback-Exchange auch dann funktioniert, wenn
 * der Prozess zwischen Auth-Start (Custom Tab) und Redirect gestorben ist.
 */
data class TeslaPendingAuth(
    val state: String,
    val codeVerifier: String,
    val createdAtMs: Long
)

/**
 * Persistenz-Seam für Tesla Fleet API Tokens. Interface, damit Tests einen
 * In-Memory-Fake nutzen können (Android Keystore + DataStore sind unter
 * Robolectric umständlich) — gleiches Muster wie
 * [io.github.lycheeappf.tmm.core.security.ApiKeyStore].
 *
 * Produktions-Implementierung: [KeystoreTeslaTokenStore].
 */
interface TeslaTokenStore {

    /** Entschlüsselter Access-Token oder null (nicht gesetzt / nicht entschlüsselbar). */
    suspend fun readAccessToken(): String?

    /** Entschlüsselter Refresh-Token oder null (nicht gesetzt / nicht entschlüsselbar). */
    suspend fun readRefreshToken(): String?

    /** Ablaufzeitpunkt des Access-Tokens (epoch ms); 0 = nie authentifiziert. */
    suspend fun readExpiresAtMs(): Long

    /**
     * Persistiert ein frisch ausgestelltes Token-Set **atomar** (ein einziger
     * DataStore-Commit) — ein Absturz zwischen Access- und Refresh-Write kann
     * keinen halb-rotierten Zustand hinterlassen. `refreshToken == null` lässt
     * den vorhandenen (rotierenden) Refresh-Token unangetastet.
     */
    suspend fun writeTokens(accessToken: String, refreshToken: String?, expiresAtMs: Long)

    suspend fun readSelectedVin(): String?

    suspend fun writeSelectedVin(vin: String)

    fun selectedVinFlow(): Flow<String?>

    suspend fun readSelectedVehicleId(): Long?

    suspend fun writeSelectedVehicleId(id: Long)

    /**
     * Pending-OAuth-Flow lesen; null wenn keiner läuft oder der Verifier nicht
     * mehr entschlüsselbar ist (Keystore-Reset).
     */
    suspend fun readPendingAuth(): TeslaPendingAuth?

    /** Persistiert den Pending-Flow; null räumt ihn ab (one-shot Verbrauch im Callback). */
    suspend fun writePendingAuth(pending: TeslaPendingAuth?)

    /** TRUE sobald ein Refresh-Token persistiert wurde (Schnellpfad ohne Decrypt). */
    suspend fun isAuthenticated(): Boolean

    /** Löscht alle gespeicherten Token und Metadaten (Logout). */
    suspend fun clear()
}
