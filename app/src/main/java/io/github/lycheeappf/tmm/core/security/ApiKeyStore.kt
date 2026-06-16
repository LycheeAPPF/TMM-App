package io.github.lycheeappf.tmm.core.security

/**
 * Persistenter, verschlüsselter Speicher für sensible Geheimnisse — derzeit nur
 * der xAI API-Key. Interface, damit Tests einen In-Memory-Fake nutzen können
 * (Android Keystore + DataStore sind unter Robolectric umständlich).
 */
interface ApiKeyStore {

    /** Liest den Plaintext-API-Key oder null wenn noch keiner gesetzt ist. */
    suspend fun read(): String?

    /** Schreibt den API-Key (überschreibt einen vorhandenen). */
    suspend fun write(key: String)

    /** Löscht den API-Key (Settings: "API-Key entfernen"). */
    suspend fun clear()

    /** Schnellpfad ohne Decrypt — TRUE wenn ein Key persistiert wurde. */
    suspend fun isSet(): Boolean
}
