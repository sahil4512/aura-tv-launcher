package com.auratv.launcher.settings

import android.annotation.TargetApi
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/** A copy of the preference file alone cannot be used to test guesses against this verifier. */
@TargetApi(23)
internal object DevicePin {
    const val PREFIX = "ks1:"
    private const val ALIAS = "glowhome.family.pin.v1"
    private const val ALGORITHM = "HmacSHA256"
    private const val PROVIDER = "AndroidKeyStore"

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "PIN key unavailable" }
        val generator = KeyGenerator.getInstance(ALGORITHM, PROVIDER)
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setKeySize(256).setDigests(KeyProperties.DIGEST_SHA256).build())
        return generator.generateKey()
    }

    private fun tag(pin: String, salt: ByteArray, create: Boolean): ByteArray =
        Mac.getInstance(ALGORITHM).run {
            init(key(create))
            update(salt)
            doFinal(pin.toByteArray(Charsets.US_ASCII))
        }

    fun encode(pin: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val bytes = salt + tag(pin, salt, true)
        return PREFIX + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun matches(pin: String, encoded: String): Boolean {
        val bytes = Base64.decode(encoded.removePrefix(PREFIX), Base64.NO_WRAP)
        if (bytes.size != 48) return false
        return MessageDigest.isEqual(bytes.copyOfRange(16, 48), tag(pin, bytes.copyOfRange(0, 16), false))
    }
}
