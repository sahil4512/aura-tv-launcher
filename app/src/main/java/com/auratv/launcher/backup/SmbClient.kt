package com.auratv.launcher.backup

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Properties
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbAuthException
import jcifs.smb.SmbFile
import jcifs.util.Crypto

/**
 * The SMB (Samba / Windows share) calls of the network backup: blocking, background threads
 * only, no Android code. URLs are `smb://host/share/folder/` (folders end with a slash).
 */
object SmbClient {

    /** A folder entry: a sub-folder or a backup file. */
    class Entry(val name: String, val url: String, val dir: Boolean, val size: Long, val time: Long)

    /** Backups are small; anything bigger is not one of ours. */
    private const val MAX_BACKUP_BYTES = 1L shl 20

    @Volatile private var cryptoReady = false

    private fun initCrypto() {
        if (cryptoReady) return
        synchronized(this) {
            if (cryptoReady) return
            try { Crypto.initProvider(JcifsCrypto()) } catch (_: Exception) { /* already set */ }
            cryptoReady = true
        }
    }

    /** Context for one host: guest when [login] is null or a guest login, otherwise NTLM user / password. */
    fun context(login: SmbLogins.Login?): CIFSContext {
        initCrypto()
        val guest = login == null || login.guest
        val p = Properties()
        p.setProperty("jcifs.smb.client.maxVersion", "SMB311")
        p.setProperty("jcifs.smb.client.connTimeout", "8000")
        p.setProperty("jcifs.smb.client.responseTimeout", "15000")
        p.setProperty("jcifs.smb.client.soTimeout", "20000")
        p.setProperty("jcifs.resolveOrder", "DNS,BCAST")
        // A guest session has no key to sign with; listing shares (IPC$) would otherwise
        // be refused with "IPC signing is enforced, but no signing is available".
        if (guest) p.setProperty("jcifs.smb.client.ipcSigningEnforced", "false")
        val base = BaseContext(PropertyConfiguration(p))
        if (guest) return base.withGuestCrendentials()
        return base.withCredentials(NtlmPasswordAuthenticator(login.domain, login.user, login.password))
    }

    /** The server refused the login (wrong / missing user name or password, or no guest access). */
    fun isAuthError(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is SmbAuthException) return true
            val m = t.message ?: ""
            if (m.contains("Logon failure", true) || m.contains("Access is denied", true) ||
                m.contains("account", true) && m.contains("disabled", true)) return true
            t = t.cause
        }
        return false
    }

    /** A short reason for the screen. */
    fun describe(e: Throwable): String {
        val chain = generateSequence(e) { it.cause }.toList()
        if (chain.any { it is java.net.UnknownHostException }) return "server not found"
        val m = chain.mapNotNull { it.message }.firstOrNull { it.isNotBlank() }
        if (m == null || m.startsWith("Failed to connect") ||
            chain.any { it is java.net.ConnectException || it is java.net.SocketTimeoutException || it is java.net.NoRouteToHostException }) {
            return if (m == null) e.javaClass.simpleName else "no answer (is it switched on, with file sharing on?)"
        }
        return m
    }

    private fun trimSlash(n: String) = n.trimEnd('/')

    /** Share names of a server, sorted; administrative shares (IPC$, C$, print$…) left out. */
    fun shares(ctx: CIFSContext, host: String): List<String> =
        SmbFile("smb://$host/", ctx).use { root ->
            root.listFiles().map { trimSlash(it.name) }.filter { it.isNotEmpty() && !it.endsWith("$") }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
        }

    /** Folders (A–Z), then backup files (newest first); hidden entries left out. */
    fun list(ctx: CIFSContext, url: String): List<Entry> {
        val dirs = ArrayList<Entry>()
        val files = ArrayList<Entry>()
        SmbFile(url, ctx).use { dir ->
            for (f in dir.listFiles()) {
                val n = trimSlash(f.name)
                if (n.isEmpty() || n.startsWith(".")) continue
                if (f.isDirectory) dirs += Entry(n, url + n + "/", true, 0, f.lastModified())
                else if (BackupNames.isBackup(n)) files += Entry(n, url + n, false, f.length(), f.lastModified())
            }
        }
        dirs.sortWith { a, b -> a.name.compareTo(b.name, ignoreCase = true) }
        return dirs + BackupNames.newestFirst(files, { it.name }, { it.time })
    }

    fun readText(ctx: CIFSContext, url: String): String =
        SmbFile(url, ctx).use { f ->
            if (f.length() > MAX_BACKUP_BYTES) throw IOException("${f.name} is too big for a backup")
            f.inputStream.use { String(it.readBytes(), StandardCharsets.UTF_8) }
        }

    /** Writes [text] to [url], replacing a file of the same name. */
    fun writeText(ctx: CIFSContext, url: String, text: String) {
        SmbFile(url, ctx).use { f -> f.outputStream.use { it.write(text.toByteArray(StandardCharsets.UTF_8)) } }
    }

    /** smb://host/share/a/b/ → \\host\share\a\b (how Windows users write it). */
    fun displayPath(url: String): String =
        "\\\\" + url.removePrefix("smb://").trimEnd('/').replace('/', '\\')

    fun hostOf(url: String): String = url.removePrefix("smb://").substringBefore('/')

    /**
     * What the viewer typed as a server: `nas`, `192.168.1.10`, `\\nas\share\folder`,
     * `smb://nas/share`. Returns host and the folder URL (null when only a host was given),
     * or null if there is no host.
     */
    fun parseAddress(input: String): Pair<String, String?>? {
        var s = input.trim().replace('\\', '/')
        s = s.removePrefix("smb:").trimStart('/')
        val parts = s.split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        val host = parts[0]
        if (host.any { it.isWhitespace() }) return null
        val path = parts.drop(1)
        return host to (if (path.isEmpty()) null else "smb://$host/" + path.joinToString("/") + "/")
    }
}
