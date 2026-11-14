package com.loqui.android.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Storage for provider API keys.
 *
 * Keys are user-supplied and optional. They are encrypted with an AES-256-GCM key
 * held in the Android Keystore, so the on-disk value is ciphertext only. Keys are
 * never logged, never sent anywhere except the provider endpoint the user chose,
 * and never bundled in the APK.
 */
interface SecretStore {
    fun hasKey(providerId: String): Boolean

    fun readKey(providerId: String): String?

    fun writeKey(providerId: String, apiKey: String)

    fun deleteKey(providerId: String)
}

@Singleton
class KeystoreSecretStore @Inject constructor(
    @ApplicationContext context: Context,
) : SecretStore {

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun hasKey(providerId: String): Boolean = preferences.contains(providerId)

    override fun readKey(providerId: String): String? {
        val stored = preferences.getString(providerId, null) ?: return null
        return runCatching { decrypt(stored) }
            .getOrElse { throwable ->
                // A rotated/invalidated Keystore key makes old ciphertext useless:
                // drop the entry so the user gets the "add your key" state again.
                Timber.w(throwable, "Stored secret for %s could not be read; clearing it", providerId)
                deleteKey(providerId)
                null
            }
    }

    override fun writeKey(providerId: String, apiKey: String) {
        preferences.edit().putString(providerId, encrypt(apiKey)).apply()
    }

    override fun deleteKey(providerId: String) {
        preferences.edit().remove(providerId).apply()
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return encode(cipher.iv) + PAYLOAD_SEPARATOR + encode(cipherText)
    }

    private fun decrypt(stored: String): String {
        val parts = stored.split(PAYLOAD_SEPARATOR, limit = 2)
        require(parts.size == 2) { "Malformed secret payload" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_BITS, decode(parts[0]))
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), spec)
        return String(cipher.doFinal(decode(parts[1])), Charsets.UTF_8)
    }

    @Synchronized
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "loqui_provider_keys"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PAYLOAD_SEPARATOR = ":"
        const val GCM_TAG_BITS = 128
        const val KEY_SIZE_BITS = 256
        const val PREFERENCES_NAME = "loqui_secrets_encrypted"
    }
}
