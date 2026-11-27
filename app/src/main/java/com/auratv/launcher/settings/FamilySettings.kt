package com.auratv.launcher.settings

import android.content.Context
import android.os.Build
import android.util.Log
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

    /** Milliseconds until another PIN attempt is allowed (0 when it is allowed now). */
    fun waitMs(now: Long = System.currentTimeMillis()): Long =
        PinThrottle.waitMs(store.getLong("locked_until", 0L), now)

    /** Records a wrong PIN; returns how long the keypad now waits (0 for no wait). */
    fun recordFailure(now: Long = System.currentTimeMillis()): Long {
        val failures = store.getInt("failures", 0) + 1
        val wait = PinThrottle.waitAfter(failures)
        store.edit().putInt("failures", failures).putLong("locked_until", if (wait > 0) now + wait else 0L).apply()
        return wait
    }

    fun clearFailures() { store.edit().remove("failures").remove("locked_until").apply() }
    private fun deviceVerifier(digits: String): String? {
        if (Build.VERSION.SDK_INT < 23) return null
        return try { DevicePin.encode(digits) } catch (e: Exception) {
            Log.w("GlowHome", "Device PIN key unavailable", e)
            null
        }
    }

    fun encodePin(digits: String): String {
        require(digits.length == 4 && digits.all { it in '0'..'9' })
        return deviceVerifier(digits) ?: PinVerifier.encode(digits)
    }

    fun matches(digits: String): Boolean {
        val saved = store.getString("pin", null) ?: return false
        if (saved.startsWith(DevicePin.PREFIX)) {
            return Build.VERSION.SDK_INT >= 23 && DevicePin.matches(digits, saved)
        }
        if (!PinVerifier.matches(digits, saved)) return false
        // Upgrade an existing PIN only after it was verified. Older TVs keep the existing format.
        deviceVerifier(digits)?.let { if (store.getString("pin", null) == saved) saveVerifier(it) }
        return true
    }
}

internal object PinThrottle {
    const val FREE_TRIES = 5
    const val BASE_WAIT_MS = 30_000L
    const val MAX_WAIT_MS = 15 * 60_000L

    /** 30 s after the 5th wrong PIN, doubling with each further one, up to 15 minutes. */
    fun waitAfter(failures: Int): Long {
        if (failures < FREE_TRIES) return 0L
        val steps = (failures - FREE_TRIES).coerceAtMost(5)
        return (BASE_WAIT_MS shl steps).coerceAtMost(MAX_WAIT_MS)
    }

    /** A clock set backwards can't stretch the wait beyond its longest value. */
    fun waitMs(lockedUntil: Long, now: Long): Long = (lockedUntil - now).coerceIn(0L, MAX_WAIT_MS)
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
