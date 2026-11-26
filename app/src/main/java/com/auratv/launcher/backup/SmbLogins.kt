package com.auratv.launcher.backup

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/**
 * Logins for network shares, per host (as used in the URL).
 *
 * A login typed in is used for the rest of the session; with "Remember" it is also saved. Saved
 * logins live in their own file ("smb_logins"), never in the settings file, so a settings backup
 * never carries a password. On Android 6+ the password is encrypted with an AES key that never
 * leaves the Android Keystore; Android 5.1 has no usable Keystore AES, so there it is stored
 * as is (the file is private to GlowHome either way).
 */
class SmbLogins(context: Context) {

    class Login(val user: String, val password: String, val domain: String = "", val guest: Boolean = false) {
        companion object { val GUEST = Login("", "", "", true) }
    }

    /** A host with a saved login: [name] is what the browser showed for it (e.g. "NAS"). */
    class Saved(val host: String, val name: String?, val login: Login)

    private val sp: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Login for [host]: typed in this session, else saved, else null (try as guest). */
    fun get(host: String): Login? = session[key(host)] ?: load(host)

    fun isSaved(host: String) = sp.contains(PREFIX + key(host))

    /** Use [login] for [host] from now on; [remember] also saves it (false forgets a saved one). */
    fun set(host: String, login: Login, remember: Boolean, name: String? = null) {
        session[key(host)] = login
        if (!remember) { forget(host); session[key(host)] = login; return }
        try {
            val o = JSONObject()
                .put("u", login.user).put("d", login.domain).put("g", login.guest)
            if (name != null) o.put("n", name)
            if (!login.guest) {
                if (Build.VERSION.SDK_INT >= 23) o.put("p", encrypt(login.password)) else o.put("pp", login.password)
            }
            sp.edit().putString(PREFIX + key(host), o.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "saving the login failed: $e")
        }
    }

    fun forget(host: String) {
        session.remove(key(host))
        sp.edit().remove(PREFIX + key(host)).apply()
    }

    /** Hosts with a saved login, A–Z by name. */
    fun saved(): List<Saved> = sp.all.keys.filter { it.startsWith(PREFIX) }.mapNotNull { k ->
        val host = k.removePrefix(PREFIX)
        val l = load(host) ?: return@mapNotNull null
        val name = try { JSONObject(sp.getString(k, "{}")!!).optString("n").ifEmpty { null } } catch (_: Exception) { null }
        Saved(host, name, l)
    }.sortedBy { (it.name ?: it.host).lowercase() }

    private fun load(host: String): Login? {
        val v = sp.getString(PREFIX + key(host), null) ?: return null
        return try {
            val o = JSONObject(v)
            if (o.optBoolean("g")) return Login.GUEST
            val pw = when {
                o.has("p") -> decrypt(o.getString("p"))
                else -> o.optString("pp")
            }
            Login(o.optString("u"), pw, o.optString("d"))
        } catch (e: Exception) {
            // Key gone (e.g. app data restored to another device): ask again.
            Log.w(TAG, "saved login for $host unreadable: $e")
            null
        }
    }

    private fun key(host: String) = host.trim().lowercase()

    // ------------------------------------------------------------ Keystore (API 23+)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return g.generateKey()
    }

    /** IV (12 bytes) + ciphertext, Base64. */
    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        val out = c.iv + c.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(enc: String): String {
        if (Build.VERSION.SDK_INT < 23) throw IllegalStateException("no Keystore AES")
        val b = Base64.decode(enc, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, b, 0, 12))
        return String(c.doFinal(b, 12, b.size - 12), StandardCharsets.UTF_8)
    }

    companion object {
        private const val TAG = "Aura"
        private const val FILE = "smb_logins"
        private const val PREFIX = "login."
        private const val KEY_ALIAS = "glowhome_smb_logins"
        /** Logins typed in this session (until the launcher process ends). */
        private val session = ConcurrentHashMap<String, Login>()
    }
}
