package com.strata.app.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Holds the database passphrase wrapped by an AES key that lives in the Android Keystore
 * (StrongBox / Titan M2 when available). The wrapping key can only be used right after a
 * strong biometric or device-credential check, so the database cannot be decrypted without one.
 *
 * Blob layout on disk: [iv length][iv][ciphertext].
 */
class KeyVault(context: Context) {
    private val blobFile = File(context.noBackupFilesDir, "vault.bin")
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    val isInitialized: Boolean get() = blobFile.exists() && keyStore.containsAlias(KEY_ALIAS)

    /** Cipher to hand to BiometricPrompt on first launch. Creates the Keystore key. */
    fun cipherForSetup(): Cipher {
        keyStore.deleteEntry(KEY_ALIAS)
        val key = generateKey()
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    /** Cipher to hand to BiometricPrompt on unlock. Throws KeyPermanentlyInvalidatedException if the key is gone. */
    fun cipherForUnlock(): Cipher {
        val bytes = blobFile.readBytes()
        val ivLength = bytes[0].toInt()
        val iv = bytes.copyOfRange(1, 1 + ivLength)
        val key = keyStore.getKey(KEY_ALIAS, null) as SecretKey
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
    }

    /** Generates a fresh passphrase, seals it with the authenticated cipher and returns it. */
    fun sealNewPassphrase(authenticatedCipher: Cipher): ByteArray {
        val passphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val sealed = authenticatedCipher.doFinal(passphrase)
        val iv = authenticatedCipher.iv
        blobFile.writeBytes(byteArrayOf(iv.size.toByte()) + iv + sealed)
        return passphrase
    }

    fun openPassphrase(authenticatedCipher: Cipher): ByteArray {
        val bytes = blobFile.readBytes()
        val ivLength = bytes[0].toInt()
        return authenticatedCipher.doFinal(bytes.copyOfRange(1 + ivLength, bytes.size))
    }

    /** Forgets the key and blob. The encrypted database becomes unreadable and must be deleted too. */
    fun reset() {
        keyStore.deleteEntry(KEY_ALIAS)
        blobFile.delete()
    }

    private fun generateKey(): SecretKey {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
            // Enrolling a new fingerprint must not destroy the data; the device PIN can unlock anyway.
            .setInvalidatedByBiometricEnrollment(false)
            .setIsStrongBoxBacked(strongBox)
            .build()

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        return try {
            generator.init(spec(strongBox = true))
            generator.generateKey()
        } catch (_: StrongBoxUnavailableException) {
            generator.init(spec(strongBox = false))
            generator.generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "strata.db.wrapping"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
