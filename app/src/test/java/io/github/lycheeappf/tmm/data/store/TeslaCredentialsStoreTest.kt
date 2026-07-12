package io.github.lycheeappf.tmm.data.store

import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.core.security.TeslaCredentials
import io.github.lycheeappf.tmm.core.security.TeslaCredentialsStore
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * Vertragstests für die [TeslaCredentialsStore]-Schnittstelle (core/security) über
 * einen In-Memory-Fake — dasselbe Muster wie beim xAI [io.github.lycheeappf.tmm.core.security.ApiKeyStore]:
 * die konkrete [io.github.lycheeappf.tmm.core.security.KeystoreTeslaCredentialsStore]
 * braucht AndroidKeyStore + DataStore und ist unter Robolectric nicht sinnvoll
 * testbar (kein AndroidKeyStore-Provider). Der Fake bildet den dokumentierten
 * Vertrag exakt ab: write trimmt, verlangt nicht-leere Werte ohne CR/LF und
 * validiert VOR dem Persistieren; fehlende ODER nicht entschlüsselbare
 * Credentials (Keystore-Reset) lesen sich als null → typisierter Missing-Zustand,
 * auf dem TeslaAuthState.MissingCredentials und das Turn-Time-Gate aufbauen.
 */
class TeslaCredentialsStoreTest {

    private lateinit var store: InMemoryTeslaCredentialsStore

    @Before fun setup() {
        store = InMemoryTeslaCredentialsStore()
    }

    // ---- Missing → typisierter Leer-Zustand ---------------------------------

    @Test fun `read returns null when no credentials were ever saved`() = runTest {
        assertThat(store.read()).isNull()
    }

    @Test fun `isSet is false when no credentials were ever saved`() = runTest {
        assertThat(store.isSet()).isFalse()
    }

    @Test fun `undecryptable credentials read as missing even though ciphertext is present`() = runTest {
        // Keystore-Reset (Werkseinstellungen / Backup-Restore): der Ciphertext
        // liegt noch im DataStore (isSet-Schnellpfad = true), aber read() muss
        // null liefern — die UI zeigt dann den Einrichtungshinweis.
        store.write(TeslaCredentials(clientId = "client-id", clientSecret = "client-secret"))
        store.simulateKeystoreReset = true

        assertThat(store.read()).isNull()
        assertThat(store.isSet()).isTrue()
    }

    // ---- Save/Read-Roundtrip -------------------------------------------------

    @Test fun `write then read round-trips the credentials`() = runTest {
        val credentials = TeslaCredentials(
            clientId = "abcd1234-client",
            clientSecret = "ta-secret.XYZ~987"
        )
        store.write(credentials)

        assertThat(store.read()).isEqualTo(credentials)
        assertThat(store.isSet()).isTrue()
    }

    @Test fun `write overwrites previously saved credentials`() = runTest {
        store.write(TeslaCredentials(clientId = "old-id", clientSecret = "old-secret"))
        store.write(TeslaCredentials(clientId = "new-id", clientSecret = "new-secret"))

        assertThat(store.read())
            .isEqualTo(TeslaCredentials(clientId = "new-id", clientSecret = "new-secret"))
    }

    @Test fun `write trims surrounding whitespace before persisting`() = runTest {
        store.write(TeslaCredentials(clientId = "  client-id \t", clientSecret = " client-secret  "))

        assertThat(store.read())
            .isEqualTo(TeslaCredentials(clientId = "client-id", clientSecret = "client-secret"))
    }

    // ---- Validierung (Vertrag: nicht leer, kein CR/LF) -----------------------

    @Test fun `write rejects a blank client id`() = runTest {
        val failure = runCatching {
            store.write(TeslaCredentials(clientId = "   ", clientSecret = "client-secret"))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(store.isSet()).isFalse()
    }

    @Test fun `write rejects a blank client secret`() = runTest {
        val failure = runCatching {
            store.write(TeslaCredentials(clientId = "client-id", clientSecret = ""))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(store.isSet()).isFalse()
    }

    @Test fun `write rejects credentials containing a newline`() = runTest {
        // Newline/CR würden das OkHttp-Form-Encoding des Token-Requests zerstören.
        val failure = runCatching {
            store.write(TeslaCredentials(clientId = "client\nid", clientSecret = "client-secret"))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `write rejects credentials containing a carriage return`() = runTest {
        val failure = runCatching {
            store.write(TeslaCredentials(clientId = "client-id", clientSecret = "secret\rvalue"))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `rejected write keeps previously saved credentials intact`() = runTest {
        // Validierung passiert VOR dem Persistieren — ein ungültiger Schreibversuch
        // darf einen gültigen Bestand nicht anfassen.
        val existing = TeslaCredentials(clientId = "client-id", clientSecret = "client-secret")
        store.write(existing)

        runCatching { store.write(TeslaCredentials(clientId = "", clientSecret = "new-secret")) }

        assertThat(store.read()).isEqualTo(existing)
        assertThat(store.isSet()).isTrue()
    }

    // ---- Clear ---------------------------------------------------------------

    @Test fun `clear removes saved credentials`() = runTest {
        store.write(TeslaCredentials(clientId = "client-id", clientSecret = "client-secret"))

        store.clear()

        assertThat(store.read()).isNull()
        assertThat(store.isSet()).isFalse()
    }

    @Test fun `clear on an empty store is a no-op`() = runTest {
        store.clear()

        assertThat(store.read()).isNull()
        assertThat(store.isSet()).isFalse()
    }

    @Test fun `credentials can be saved again after clear`() = runTest {
        store.write(TeslaCredentials(clientId = "first-id", clientSecret = "first-secret"))
        store.clear()
        store.write(TeslaCredentials(clientId = "second-id", clientSecret = "second-secret"))

        assertThat(store.read())
            .isEqualTo(TeslaCredentials(clientId = "second-id", clientSecret = "second-secret"))
    }
}

/**
 * In-Memory-Fake mit dem dokumentierten Vertrag von
 * [io.github.lycheeappf.tmm.core.security.KeystoreTeslaCredentialsStore]:
 * write trimmt + validiert (nicht leer, kein CR/LF) bevor persistiert wird;
 * [simulateKeystoreReset] bildet den Fall "Ciphertext vorhanden, aber nicht
 * entschlüsselbar" ab (isSet prüft nur Präsenz, read liefert dann null).
 */
private class InMemoryTeslaCredentialsStore : TeslaCredentialsStore {

    private var stored: TeslaCredentials? = null

    /** TRUE = read() verhält sich wie nach einem Keystore-Reset (Decrypt schlägt fehl). */
    var simulateKeystoreReset: Boolean = false

    override suspend fun read(): TeslaCredentials? =
        if (simulateKeystoreReset) null else stored

    override suspend fun write(credentials: TeslaCredentials) {
        val clientId = credentials.clientId.trim()
        val clientSecret = credentials.clientSecret.trim()
        require(clientId.isNotEmpty()) { "client_id must not be empty" }
        require(clientSecret.isNotEmpty()) { "client_secret must not be empty" }
        require(listOf(clientId, clientSecret).none { '\n' in it || '\r' in it }) {
            "credentials must not contain newline characters"
        }
        stored = TeslaCredentials(clientId = clientId, clientSecret = clientSecret)
    }

    override suspend fun clear() {
        stored = null
        simulateKeystoreReset = false
    }

    override suspend fun isSet(): Boolean = stored != null
}
