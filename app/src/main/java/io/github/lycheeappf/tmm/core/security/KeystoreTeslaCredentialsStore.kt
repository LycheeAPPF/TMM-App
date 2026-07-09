package io.github.lycheeappf.tmm.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AES-256-GCM-verschlüsselte Persistenz der Tesla-App-Credentials — exakt das
 * Muster von [KeystoreApiKeyStore]: Master-Key im AndroidKeyStore, Ciphertext
 * als Base64-String in einem eigenen DataStore<Preferences>
 * (`mfs_tesla_secure`, per Data-Extraction-Rules vom Backup ausgeschlossen).
 *
 * Fehlende oder nicht entschlüsselbare Credentials (Keystore-Reset, Backup-
 * Restore) werden als "nicht gesetzt" behandelt — die UI zeigt dann den
 * Einrichtungshinweis, alle Fleet-Features sind gegated.
 */
@Singleton
class KeystoreTeslaCredentialsStore @Inject constructor(
    @ApplicationContext private val context: Context
) : TeslaCredentialsStore {

    private val store: DataStore<Preferences> = context.teslaSecureDataStore

    override suspend fun read(): TeslaCredentials? {
        val (idCt, secretCt) = try {
            val prefs = store.data.first()
            val idCt = prefs[KEY_CLIENT_ID_CT] ?: return null
            val secretCt = prefs[KEY_CLIENT_SECRET_CT] ?: return null
            idCt to secretCt
        } catch (e: java.io.IOException) {
            Log.w(TAG, "DataStore read failed", e)
            return null
        }
        return try {
            TeslaCredentials(clientId = decrypt(idCt), clientSecret = decrypt(secretCt))
        } catch (e: Exception) {
            // Häufig: Keystore-Reset (Werkseinstellungen, Backup-Restore).
            // Slot als leer behandeln → UI zeigt "Credentials fehlen".
            Log.w(TAG, "decrypt failed — treating credentials as missing", e)
            null
        }
    }

    override suspend fun write(credentials: TeslaCredentials) {
        // Newline/CR zerstören OkHttp-Form-Encoding; defensiv trimmen + validieren.
        val clientId = credentials.clientId.trim()
        val clientSecret = credentials.clientSecret.trim()
        require(clientId.isNotEmpty()) { "client_id must not be empty" }
        require(clientSecret.isNotEmpty()) { "client_secret must not be empty" }
        require(listOf(clientId, clientSecret).none { '\n' in it || '\r' in it }) {
            "credentials must not contain newline characters"
        }
        val idCt = encrypt(clientId)
        val secretCt = encrypt(clientSecret)
        store.edit {
            it[KEY_CLIENT_ID_CT] = idCt
            it[KEY_CLIENT_SECRET_CT] = secretCt
        }
    }

    override suspend fun clear() {
        // Reihenfolge wie im ApiKeyStore: erst Keystore-Alias, dann Ciphertext —
        // stirbt der Prozess dazwischen, ist der Rest unentschlüsselbar (kein Leak).
        runCatching {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
        }.onFailure { Log.w(TAG, "Keystore entry delete failed", it) }
        store.edit {
            it.remove(KEY_CLIENT_ID_CT)
            it.remove(KEY_CLIENT_SECRET_CT)
        }
    }

    override suspend fun isSet(): Boolean = store.data.first().let {
        it[KEY_CLIENT_ID_CT] != null && it[KEY_CLIENT_SECRET_CT] != null
    }

    // ---- Internals ----------------------------------------------------------

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        val iv = cipher.iv
        check(iv.size == GCM_IV_LENGTH) {
            "Unexpected GCM IV length ${iv.size} (expected $GCM_IV_LENGTH)"
        }
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val out = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(ct, 0, out, iv.size, ct.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        require(raw.size > GCM_IV_LENGTH) { "Ciphertext too short" }
        val iv = raw.copyOfRange(0, GCM_IV_LENGTH)
        val ct = raw.copyOfRange(GCM_IV_LENGTH, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // Kein setUserAuthenticationRequired — Token-Refresh läuft auch im
            // Hintergrund (Tesla-Diktat bei gesperrtem Screen).
            .setRandomizedEncryptionRequired(true)
            .build()
        kg.init(spec)
        return kg.generateKey()
    }

    companion object {
        private const val TAG = "TeslaCredStore"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "io.github.lycheeappf.tmm.tesla.credentials"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128

        private val KEY_CLIENT_ID_CT = stringPreferencesKey("tesla_client_id_ciphertext")
        private val KEY_CLIENT_SECRET_CT = stringPreferencesKey("tesla_client_secret_ciphertext")
    }
}

private val Context.teslaSecureDataStore: DataStore<Preferences> by preferencesDataStore("mfs_tesla_secure")
