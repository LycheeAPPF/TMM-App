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
 * AES-256-GCM Verschlüsselung mit Schlüsselmaterial aus dem AndroidKeyStore;
 * Ciphertext liegt als Base64-String in einem eigenen DataStore<Preferences>.
 *
 * Hintergrund: `androidx.security:security-crypto` (EncryptedSharedPreferences)
 * ist seit 1.1.0-alpha07 deprecated. Statt eine als-instabil-bekannte Lib zu
 * importieren rollen wir die minimale Crypto-Pfeife selbst — der Keystore selbst
 * ist Google-supported Standard.
 *
 * Sicherheitsmodell:
 *  - Master-Key bleibt im AndroidKeyStore — wird nie als Bytes herausgegeben
 *  - IV wird pro Encrypt-Call neu generiert (Cipher.init macht das automatisch)
 *  - Ciphertext-Layout: [12 Byte IV] || [Ciphertext + 16 Byte GCM-Tag]
 *  - Base64-Encoding macht es DataStore-fähig (String-Preference)
 */
@Singleton
class KeystoreApiKeyStore @Inject constructor(
    @ApplicationContext private val context: Context
) : ApiKeyStore {

    private val store: DataStore<Preferences> = context.secureDataStore

    override suspend fun read(): String? {
        val encoded = try {
            store.data.first()[KEY_CIPHERTEXT] ?: return null
        } catch (e: java.io.IOException) {
            // DataStore-File korrupt / Lock-Konflikt / IO-Fehler. Wir loggen und
            // tun so, als wäre kein Key gesetzt — der Provider returnt dann
            // `LlmProviderError.MissingKey`, was die UI sauber fängt.
            Log.w(TAG, "DataStore read failed", e)
            return null
        }
        return try {
            decrypt(encoded)
        } catch (e: Exception) {
            // Häufig: Keystore-Reset (Werkseinstellungen, Reboot ohne Lock-Screen-Auth,
            // Backup-Restore). Wir loggen und behandeln den Slot als leer.
            Log.w(TAG, "decrypt failed — treating key as missing", e)
            null
        }
    }

    override suspend fun write(key: String) {
        // xAI-API-Keys haben kein offizielles Format-Spec, aber sind ASCII-only
        // mit `sk-...` Prefix. Newline/CR im Key zerstört OkHttp-Header-Encoding,
        // führende/trailende Whitespaces ebenfalls. Defensiv trimmen + validieren.
        val normalized = key.trim()
        require(normalized.isNotEmpty()) { "API key must not be empty" }
        require('\n' !in normalized && '\r' !in normalized) {
            "API key must not contain newline characters"
        }
        val encoded = encrypt(normalized)
        store.edit { it[KEY_CIPHERTEXT] = encoded }
    }

    override suspend fun clear() {
        // Reihenfolge: erst Schlüssel im AndroidKeyStore löschen, dann Ciphertext.
        // Sollte der Prozess zwischen den Schritten sterben, ist der Ciphertext
        // dann sowieso unentschlüsselbar (read() liefert null → kein Leak).
        runCatching {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
        }.onFailure { Log.w(TAG, "Keystore entry delete failed", it) }
        store.edit { it.remove(KEY_CIPHERTEXT) }
    }

    override suspend fun isSet(): Boolean = store.data.first()[KEY_CIPHERTEXT] != null

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
            // Kein setUserAuthenticationRequired — würde Background-Worker
            // blockieren (z.B. wenn Tesla später diktiert und Screen-Lock ist).
            .setRandomizedEncryptionRequired(true)
            .build()
        kg.init(spec)
        return kg.generateKey()
    }

    companion object {
        private const val TAG = "KeystoreApiKeyStore"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "io.github.lycheeappf.tmm.assistant.api_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128

        private val KEY_CIPHERTEXT = stringPreferencesKey("api_key_ciphertext")
    }
}

private val Context.secureDataStore: DataStore<Preferences> by preferencesDataStore("mfs_assistant_secure")
