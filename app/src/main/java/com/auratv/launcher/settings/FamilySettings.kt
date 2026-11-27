package com.auratv.launcher.settings

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Kept outside the launcher's portable settings backup and excluded from Android backup. */
class FamilySettings(context: Context) {
    private val store = context.getSharedPreferences("family", Context.MODE_PRIVATE)
    val hasPin get() = store.contains("pin")
    var active: Boolean
        get() = store.getBoolean("active", false)
        set(value) { check(!value || hasPin); store.edit().putBoolean("active", value).apply() }
    var lockSettings: Boolean
        get() = store.getBoolean("lock", false)
        set(value) { check(!value || hasPin); store.edit().putBoolean("lock", value).apply() }
    var allowed: Set<String>
        get() = store.getStringSet("allowed", emptySet()).orEmpty()
        set(value) { store.edit().putStringSet("allowed", value.toSet()).apply() }

    fun saveVerifier(encoded: String) { store.edit().putString("pin", encoded).apply() }
    fun matches(digits: String) = PinVerifier.matches(digits, store.getString("pin", null))
}

internal object PinVerifier {
    private fun derive(digits: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(digits.toCharArray(), salt, 60000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    fun encode(digits: String): String {
        require(digits.length == 4 && digits.all { it in '0'..'9' })
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return hex(salt) + "." + hex(derive(digits, salt))
    }
    fun matches(digits: String, encoded: String?): Boolean {
        if (digits.length != 4 || encoded == null || encoded.length != 97 || encoded[32] != '.') return false
        val salt = encoded.take(32).chunked(2).map { it.toIntOrNull(16)?.toByte() ?: return false }.toByteArray()
        return MessageDigest.isEqual(hex(derive(digits, salt)).toByteArray(), encoded.drop(33).toByteArray())
    }
}
