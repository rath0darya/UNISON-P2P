package com.unison

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Identity keys are generated in Android Keystore and are not exported by this class.
 * They survive ordinary app updates. Android may remove app-owned keys on uninstall;
 * use a separately encrypted identity export/recovery flow for reinstall migration.
 */
object IdentityVault {
    private const val KEY_ALIAS = "unison.identity.ec.v1"

    @Synchronized
    fun publicIdentityFingerprint(): String {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(KEY_ALIAS)) {
            val generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore"
            )
            generator.initialize(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                ).setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
            )
            generator.generateKeyPair()
        }
        val publicKey = store.getCertificate(KEY_ALIAS).publicKey.encoded
        return MessageDigest.getInstance("SHA-256").digest(publicKey)
            .take(16).joinToString("") { "%02X".format(it) }
    }

    fun newPairingCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        val chars = CharArray(28) { alphabet[random.nextInt(alphabet.length)] }
        return chars.toList().chunked(4).joinToString("-") { it.joinToString("") }
    }

    fun newSaltHex(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02X".format(it) }
    }
}
