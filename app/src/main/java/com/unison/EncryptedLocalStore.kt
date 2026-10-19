package com.unison

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * Minimal encrypted-at-rest file store. Plaintext exists only in memory while callers
 * use this API. The Keystore key survives updates, not uninstall/factory reset.
 */
class EncryptedLocalStore(context: Context) {
    private val file = File(context.filesDir, "unison-vault.bin")
    private val keyAlias = "unison.storage.aes.v1"

    private fun key(): java.security.Key {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.getKey(keyAlias, null)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    @Synchronized
    fun write(plaintext: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(plaintext)
        file.writeBytes(byteArrayOf(1) + cipher.iv + ciphertext)
    }

    @Synchronized
    fun read(): ByteArray? {
        if (!file.exists()) return null
        val blob = file.readBytes()
        require(blob.size > 29 && blob[0].toInt() == 1) { "Invalid encrypted vault" }
        val iv = blob.copyOfRange(1, 13)
        val ciphertext = blob.copyOfRange(13, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }
}
