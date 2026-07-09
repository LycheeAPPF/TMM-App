package io.github.lycheeappf.tmm.data.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Produktions-Implementierung von [TeslaTokenStore].
 *
 * Sensible Werte (access_token, refresh_token) werden AES-256-GCM-verschlüsselt
 * mit Schlüsselmaterial aus dem AndroidKeyStore (eigene Aliases) — dasselbe
 * Muster wie [io.github.lycheeappf.tmm.core.security.KeystoreApiKeyStore].
 * Nicht-sensible Metadaten (Ablaufzeit, VIN) liegen im Plaintext.
 */
@Singleton
class KeystoreTeslaTokenStore @Inject constructor(
    @ApplicationContext private val context: Context
) : TeslaTokenStore {
    private val store: DataStore<Preferences> = context.teslaAuthDataStore

    // ---- Tokens ---------------------------------------------------------------

    override suspend fun readAccessToken(): String? = readEncrypted(ALIAS_ACCESS, KEY_ACCESS_CT)

    override suspend fun readRefreshToken(): String? = readEncrypted(ALIAS_REFRESH, KEY_REFRESH_CT)

    override suspend fun readExpiresAtMs(): Long = store.data.first()[KEY_EXPIRES_AT] ?: 0L

    override suspend fun writeTokens(accessToken: String, refreshToken: String?, expiresAtMs: Long) {
        // Erst verschlüsseln, dann EIN edit-Commit — atomare Token-Rotation.
        val accessCt = encrypt(ALIAS_ACCESS, accessToken)
        val refreshCt = refreshToken?.let { encrypt(ALIAS_REFRESH, it) }
        store.edit { prefs ->
            prefs[KEY_ACCESS_CT] = accessCt
            if (refreshCt != null) prefs[KEY_REFRESH_CT] = refreshCt
            prefs[KEY_EXPIRES_AT] = expiresAtMs
        }
    }

    // ---- Plaintext metadata -------------------------------------------------

    override suspend fun readSelectedVin(): String? = store.data.first()[KEY_VIN]

    override suspend fun writeSelectedVin(vin: String) { store.edit { it[KEY_VIN] = vin } }

    override fun selectedVinFlow(): Flow<String?> = store.data.map { it[KEY_VIN] }

    override suspend fun readSelectedVehicleId(): Long? = store.data.first()[KEY_VEHICLE_ID]

    override suspend fun writeSelectedVehicleId(id: Long) { store.edit { it[KEY_VEHICLE_ID] = id } }

    // ---- State helpers ------------------------------------------------------

    override suspend fun isAuthenticated(): Boolean = store.data.first()[KEY_REFRESH_CT] != null

    override suspend fun clear() {
        runCatching {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            for (alias in listOf(ALIAS_ACCESS, ALIAS_REFRESH)) {
                if (ks.containsAlias(alias)) ks.deleteEntry(alias)
            }
        }.onFailure { Log.w(TAG, "Keystore entry delete failed", it) }
        store.edit { prefs ->
            prefs.remove(KEY_ACCESS_CT)
            prefs.remove(KEY_REFRESH_CT)
            prefs.remove(KEY_EXPIRES_AT)
            prefs.remove(KEY_VIN)
            prefs.remove(KEY_VEHICLE_ID)
        }
    }

    // ---- Crypto internals ---------------------------------------------------

    private suspend fun readEncrypted(alias: String, prefKey: Preferences.Key<String>): String? {
        val encoded = try {
            store.data.first()[prefKey] ?: return null
        } catch (e: java.io.IOException) {
            Log.w(TAG, "DataStore read failed for $alias", e)
            return null
        }
        return try {
            decrypt(alias, encoded)
        } catch (e: Exception) {
            Log.w(TAG, "Decrypt failed for $alias — treating as missing", e)
            null
        }
    }

    private fun encrypt(alias: String, plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, getOrCreateKey(alias)) }
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val out = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(ct, 0, out, iv.size, ct.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(alias: String, encoded: String): String {
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        require(raw.size > GCM_IV_LENGTH) { "Ciphertext too short" }
        val iv = raw.copyOfRange(0, GCM_IV_LENGTH)
        val ct = raw.copyOfRange(GCM_IV_LENGTH, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, getOrCreateKey(alias), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    private fun getOrCreateKey(alias: String): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        kg.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return kg.generateKey()
    }

    companion object {
        private const val TAG = "TeslaTokenStore"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val ALIAS_ACCESS = "io.github.lycheeappf.tmm.tesla.access_token"
        private const val ALIAS_REFRESH = "io.github.lycheeappf.tmm.tesla.refresh_token"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128

        private val KEY_ACCESS_CT = stringPreferencesKey("tesla_access_token_ct")
        private val KEY_REFRESH_CT = stringPreferencesKey("tesla_refresh_token_ct")
        private val KEY_EXPIRES_AT = longPreferencesKey("tesla_expires_at_ms")
        private val KEY_VIN = stringPreferencesKey("tesla_selected_vin")
        private val KEY_VEHICLE_ID = longPreferencesKey("tesla_selected_vehicle_id")
    }
}

/**
 * Gemeinsames DataStore-File für Token- und Region-Store ([DataStoreTeslaRegionStore]).
 * `internal`, damit beide Impls DENSELBEN DataStore nutzen — zwei DataStore-Instanzen
 * auf einem File crashen zur Laufzeit.
 */
internal val Context.teslaAuthDataStore: DataStore<Preferences> by preferencesDataStore("mfs_tesla_auth")
