package io.github.lycheeappf.tmm.core.security

/**
 * Vom Nutzer bereitgestellte Tesla-Fleet-API-App-Credentials (eigene App-
 * Registrierung auf developer.tesla.com). Es gibt bewusst KEINE mitgelieferten
 * Defaults — ohne hinterlegte Credentials sind alle Fleet-Features aus.
 */
data class TeslaCredentials(
    val clientId: String,
    val clientSecret: String
)

/**
 * Persistenter, verschlüsselter Speicher für die Tesla-App-Credentials.
 * Interface, damit Tests einen In-Memory-Fake nutzen können — dasselbe Muster
 * wie [ApiKeyStore] für den xAI-Key.
 */
interface TeslaCredentialsStore {

    /** Liest die Credentials oder null wenn keine gesetzt / nicht entschlüsselbar. */
    suspend fun read(): TeslaCredentials?

    /** Schreibt die Credentials (überschreibt vorhandene). */
    suspend fun write(credentials: TeslaCredentials)

    /** Löscht die Credentials (Settings: "Zugangsdaten entfernen"). */
    suspend fun clear()

    /** Schnellpfad ohne Decrypt — TRUE wenn Credentials persistiert wurden. */
    suspend fun isSet(): Boolean
}
