package io.github.lycheeappf.tmm.data.store

import kotlinx.coroutines.flow.Flow

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

    /** TRUE sobald ein Refresh-Token persistiert wurde (Schnellpfad ohne Decrypt). */
    suspend fun isAuthenticated(): Boolean

    /** Löscht alle gespeicherten Token und Metadaten (Logout). */
    suspend fun clear()
}
