package com.kazemail.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.kazemail.app.data.db.AccountSecretDao
import com.kazemail.app.data.db.AccountSecretEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class EncryptedBlob(
    val ciphertextB64: String,
    val ivB64: String,
)

interface SecretCipher {
    fun encrypt(plaintext: ByteArray): EncryptedBlob
    fun decrypt(blob: EncryptedBlob): ByteArray
}

/**
 * Hardware-backed AES-256-GCM encryption via Android Keystore.
 * Used both to wrap the random SQLCipher database key and to double-encrypt account secrets.
 */
class AndroidKeystoreSecretCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : SecretCipher {

    override fun encrypt(plaintext: ByteArray): EncryptedBlob {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(plaintext)
        return EncryptedBlob(
            ciphertextB64 = Base64.encodeToString(encrypted, Base64.NO_WRAP),
            ivB64 = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
        )
    }

    override fun decrypt(blob: EncryptedBlob): ByteArray {
        val iv = Base64.decode(blob.ivB64, Base64.NO_WRAP)
        val ciphertext = Base64.decode(blob.ciphertextB64, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGen.init(spec)
        return keyGen.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val DEFAULT_KEY_ALIAS = "kazemail_master_aes_key_v1"
    }
}

/**
 * Generates and stores a random 256-bit SQLCipher database passphrase wrapped by Android Keystore.
 * Plaintext passphrase is never persisted to disk.
 */
class DatabasePassphraseManager(
    context: Context,
    private val secretCipher: SecretCipher = AndroidKeystoreSecretCipher(),
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val random = SecureRandom()

    fun getOrCreatePassphraseBytes(): ByteArray {
        val existingCipher = prefs.getString(KEY_WRAPPED_PASSPHRASE, null)
        val existingIv = prefs.getString(KEY_WRAPPED_IV, null)
        if (existingCipher != null && existingIv != null) {
            return secretCipher.decrypt(
                EncryptedBlob(ciphertextB64 = existingCipher, ivB64 = existingIv),
            )
        }

        val rawKey = ByteArray(32)
        random.nextBytes(rawKey)
        val wrapped = secretCipher.encrypt(rawKey)
        prefs.edit()
            .putString(KEY_WRAPPED_PASSPHRASE, wrapped.ciphertextB64)
            .putString(KEY_WRAPPED_IV, wrapped.ivB64)
            .apply()
        return rawKey
    }

    companion object {
        private const val PREFS_NAME = "kazemail_vault_prefs"
        private const val KEY_WRAPPED_PASSPHRASE = "db_key_cipher"
        private const val KEY_WRAPPED_IV = "db_key_iv"
    }
}

@Serializable
enum class StoredSecurityMode {
    IMPLICIT_TLS,
    START_TLS,
    PLAINTEXT,
}

@Serializable
sealed interface StoredAccountCredentials {
    val securityMode: StoredSecurityMode

    @Serializable
    @SerialName("PASSWORD")
    data class Password(
        val username: String,
        val password: String,
        override val securityMode: StoredSecurityMode = StoredSecurityMode.IMPLICIT_TLS,
    ) : StoredAccountCredentials

    @Serializable
    @SerialName("OAUTH2")
    data class OAuth2(
        val email: String,
        val providerId: String,
        val authStateJson: String,
        override val securityMode: StoredSecurityMode = StoredSecurityMode.IMPLICIT_TLS,
    ) : StoredAccountCredentials
}

class AccountCredentialStore(
    private val secretDao: AccountSecretDao,
    private val secretCipher: SecretCipher,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun saveCredentials(accountId: Long, credentials: StoredAccountCredentials) {
        val serialized = json.encodeToString(credentials).encodeToByteArray()
        val encrypted = secretCipher.encrypt(serialized)
        secretDao.upsert(
            AccountSecretEntity(
                accountId = accountId,
                encryptedPayloadB64 = encrypted.ciphertextB64,
                ivB64 = encrypted.ivB64,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun loadCredentials(accountId: Long): StoredAccountCredentials? {
        val entity = secretDao.getByAccountId(accountId) ?: return null
        val plaintext = secretCipher.decrypt(
            EncryptedBlob(
                ciphertextB64 = entity.encryptedPayloadB64,
                ivB64 = entity.ivB64,
            ),
        )
        return json.decodeFromString<StoredAccountCredentials>(plaintext.decodeToString())
    }
}
