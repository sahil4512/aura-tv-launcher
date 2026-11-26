package com.auratv.launcher.backup

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.Random
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Finds SMB servers on the local network, combining three methods because none of them finds
 * everything:
 *
 *  - mDNS / DNS-SD "_smb._tcp" (NsdManager): Samba with Avahi, most NAS, macOS
 *  - WS-Discovery probe (UDP 3702): Windows 10/11 with network discovery on, wsdd
 *  - TCP port 445 on every address of the local subnet (at most a /24): anything else
 *
 * Servers found without a name get one through a NetBIOS node status query (UDP 137).
 * Results are merged per IPv4 address, in the order found. Listener calls arrive on [ui].
 */
class SmbDiscovery(context: Context, private val ui: Handler, private val listener: Listener) {

    /** One server; fields change while discovery runs (UI thread only). */
    class Server(val ip: String) {
        var name: String? = null
        val label: String get() = name ?: ip
    }

    interface Listener {
        /** A server was found, or one already reported got a name. */
        fun onServer(s: Server)
        /** All methods have finished. */
        fun onFinished()
    }

    private val app = context.applicationContext
    private val found = LinkedHashMap<String, Server>()      // UI thread
    private val nameAsked = HashSet<String>()                // UI thread
    private var pool: ExecutorService? = null
    @Volatile private var generation = 0
    @Volatile var running = false
        private set
    private var lock: WifiManager.MulticastLock? = null
    private var nsd: NsdManager? = null
    private var nsdListener: NsdManager.DiscoveryListener? = null
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    /** Servers in the order found. UI thread. */
    fun servers(): List<Server> = found.values.toList()

    /** (Re)start all methods; earlier results are kept. UI thread. */
    fun start() {
        stop()
        running = true
        val gen = ++generation
        val p = Executors.newFixedThreadPool(SCAN_THREADS)
        pool = p
        try {
            val wm = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            lock = wm?.createMulticastLock("glowhome-smb-discovery")?.apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) {
            Log.w(TAG, "multicast lock: $e")
        }
        startMdns(gen)
        val net = localNet(app)
        try {
            p.execute {
                val wsd = Thread({ probeWsd(gen) }, "smb-wsd")
                wsd.start()
                if (net != null) scanSubnet(gen, p, net)
                try {
                    wsd.join(WSD_MS + 2000)
                    Thread.sleep((MDNS_MS - WSD_MS).coerceAtLeast(0)) // mDNS runs a little longer
                } catch (_: InterruptedException) {
                    return@execute
                }
                ui.post {
                    if (gen != generation || !running) return@post
                    stopMdns()
                    releaseLock()
                    running = false
                    listener.onFinished()
                }
            }
        } catch (_: Exception) {
            running = false
        }
    }

    /** Stop everything (results are kept). UI thread. */
    fun stop() {
        running = false
        generation++
        stopMdns()
        releaseLock()
        pool?.shutdownNow()
        pool = null
    }

    private fun releaseLock() {
        try { lock?.release() } catch (_: Exception) { }
        lock = null
    }

    /** From any thread: merge one result and tell the listener. */
    private fun report(gen: Int, ip: String, name: String?, mdns: Boolean) {
        ui.post {
            if (gen != generation) return@post
            var changed = false
            val s = found[ip] ?: Server(ip).also { found[ip] = it; changed = true }
            if (!name.isNullOrEmpty() && (s.name == null || mdns) && name != s.name) {
                s.name = name
                changed = true
            }
            if (changed) listener.onServer(s)
            val p = pool
            if (s.name == null && nameAsked.add(ip) && p != null && !p.isShutdown) {
                try {
                    p.execute { netbiosName(ip)?.let { report(gen, ip, it, false) } }
                } catch (_: Exception) { }
            }
        }
    }

    // ------------------------------------------------------------------- mDNS

    private fun startMdns(gen: Int) {
        val m = try { app.getSystemService(Context.NSD_SERVICE) as? NsdManager } catch (_: Exception) { null } ?: return
        nsd = m
        resolveQueue.clear()
        resolving = false
        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(type: String, err: Int) { Log.w(TAG, "mDNS discovery failed: $err") }
            override fun onStopDiscoveryFailed(type: String, err: Int) {}
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                ui.post {
                    if (gen != generation) return@post
                    resolveQueue.add(info)
                    resolveNext(gen)
                }
            }
        }
        nsdListener = l
        try {
            m.discoverServices("_smb._tcp", NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            Log.w(TAG, "mDNS: $e")
            nsdListener = null
        }
    }

    /** NsdManager resolves one service at a time. UI thread. */
    @Suppress("DEPRECATION")
    private fun resolveNext(gen: Int) {
        val m = nsd ?: return
        if (resolving || resolveQueue.isEmpty() || gen != generation) return
        resolving = true
        val info = resolveQueue.poll() ?: return
        try {
            m.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, err: Int) {
                    ui.post { resolving = false; resolveNext(gen) }
                }

                override fun onServiceResolved(i: NsdServiceInfo) {
                    val a = i.host
                    if (a is Inet4Address) report(gen, a.hostAddress ?: return, i.serviceName, true)
                    ui.post { resolving = false; resolveNext(gen) }
                }
            })
        } catch (_: Exception) {
            resolving = false
        }
    }

    private fun stopMdns() {
        val m = nsd
        val l = nsdListener
        if (m != null && l != null) try { m.stopServiceDiscovery(l) } catch (_: Exception) { }
        nsdListener = null
        resolveQueue.clear()
        resolving = false
    }

    // ---------------------------------------------------------- WS-Discovery

    /** Multicast a probe twice; every host answering with ProbeMatches is a candidate. */
    private fun probeWsd(gen: Int) {
        val probe = wsdProbe(UUID.randomUUID().toString()).toByteArray(StandardCharsets.UTF_8)
        try {
            DatagramSocket().use { s ->
                s.soTimeout = 300
                val group = InetSocketAddress("239.255.255.250", 3702)
                val start = System.currentTimeMillis()
                var second = false
                s.send(DatagramPacket(probe, probe.size, group))
                val buf = ByteArray(16384)
                while (running && gen == generation && System.currentTimeMillis() - start < WSD_MS) {
                    if (!second && System.currentTimeMillis() - start > 1500) {
                        second = true
                        s.send(DatagramPacket(probe, probe.size, group))
                    }
                    val p = DatagramPacket(buf, buf.size)
                    try { s.receive(p) } catch (_: IOException) { continue }
                    val body = String(p.data, 0, p.length, StandardCharsets.UTF_8)
                    val from = p.address
                    if (body.contains("ProbeMatch") && from is Inet4Address) report(gen, from.hostAddress ?: continue, null, false)
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "WS-Discovery: $e")
        }
    }

    // ------------------------------------------------------------ port scan

    private fun scanSubnet(gen: Int, p: ExecutorService, net: Pair<Int, Int>) {
        val hosts = scanTargets(net.first, net.second)
        val left = CountDownLatch(hosts.size)
        for (h in hosts) {
            try {
                p.execute {
                    try {
                        if (running && gen == generation && port445(h)) report(gen, h, null, false)
                    } finally {
                        left.countDown()
                    }
                }
            } catch (_: Exception) {
                return // stopped
            }
        }
        try { left.await(15, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
    }

    companion object {
        private const val TAG = "Aura"
        private const val MDNS_MS = 6000L
        private const val WSD_MS = 4000L
        private const val PORT_TIMEOUT_MS = 500
        private const val SCAN_THREADS = 64

        fun wsdProbe(messageId: String): String =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<soap:Envelope xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\"" +
                " xmlns:wsa=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\"" +
                " xmlns:wsd=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\"" +
                " xmlns:wsdp=\"http://schemas.xmlsoap.org/ws/2006/02/devprof\"" +
                " xmlns:pub=\"http://schemas.microsoft.com/windows/pub/2005/07\">" +
                "<soap:Header>" +
                "<wsa:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</wsa:To>" +
                "<wsa:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</wsa:Action>" +
                "<wsa:MessageID>urn:uuid:$messageId</wsa:MessageID>" +
                "</soap:Header><soap:Body><wsd:Probe>" +
                // Only wsdp:Device: Windows (wsdp:Device pub:Computer) matches it as a subset,
                // and wsdd (Samba hosts) answers nothing else.
                "<wsd:Types>wsdp:Device</wsd:Types>" +
                "</wsd:Probe></soap:Body></soap:Envelope>"

        /**
         * Own IPv4 address and prefix length: of the active network (Android 6+), else of the first
         * Wi-Fi / Ethernet interface that is up, else of any up, non-loopback interface; or null.
         */
        private fun localNet(ctx: Context): Pair<Int, Int>? {
            if (Build.VERSION.SDK_INT >= 23) {
                try {
                    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    val lp = cm?.activeNetwork?.let { cm.getLinkProperties(it) }
                    lp?.linkAddresses?.firstOrNull { it.address is Inet4Address }?.let { return toInt(it.address) to it.prefixLength }
                } catch (e: Exception) {
                    Log.w(TAG, "network: $e")
                }
            }
            try {
                val up = (NetworkInterface.getNetworkInterfaces() ?: return null).toList().filter { it.isUp && !it.isLoopback }
                val ordered = up.filter { it.name.startsWith("wlan") || it.name.startsWith("eth") } + up
                for (ni in ordered) for (ia in ni.interfaceAddresses) {
                    val a = ia.address
                    if (a is Inet4Address && !a.isLinkLocalAddress) return toInt(a) to ia.networkPrefixLength.toInt()
                }
            } catch (e: Exception) {
                Log.w(TAG, "network: $e")
            }
            return null
        }

        fun toInt(a: InetAddress): Int {
            val b = a.address
            return (b[0].toInt() and 0xff shl 24) or (b[1].toInt() and 0xff shl 16) or
                (b[2].toInt() and 0xff shl 8) or (b[3].toInt() and 0xff)
        }

        fun toIp(v: Int) = "${v ushr 24 and 0xff}.${v ushr 16 and 0xff}.${v ushr 8 and 0xff}.${v and 0xff}"

        /**
         * Hosts to scan: the subnet of ip/prefix, but never more than a /24 (the /24 that contains
         * ip). Network and broadcast address and ip itself are left out.
         */
        fun scanTargets(ip: Int, prefix: Int): List<String> {
            val p = maxOf(24, minOf(prefix, 30))
            val mask = -1 shl (32 - p)
            val net = ip and mask
            val size = 1 shl (32 - p)
            return (1 until size - 1).map { net + it }.filter { it != ip }.map { toIp(it) }
        }

        private fun port445(ip: String): Boolean = try {
            Socket().use { it.connect(InetSocketAddress(ip, 445), PORT_TIMEOUT_MS) }
            true
        } catch (_: IOException) {
            false
        }

        /** NetBIOS node status request (NBSTAT) for the wildcard name "*". */
        fun nbstatRequest(id: Int): ByteArray {
            val q = ByteArray(50)
            q[0] = (id shr 8).toByte()
            q[1] = id.toByte()
            q[5] = 1                                  // QDCOUNT
            q[12] = 0x20                              // encoded name length
            val name = ByteArray(16)
            name[0] = '*'.code.toByte()               // rest 0x00
            for (i in 0 until 16) {
                q[13 + 2 * i] = ('A'.code + (name[i].toInt() shr 4 and 0x0f)).toByte()
                q[14 + 2 * i] = ('A'.code + (name[i].toInt() and 0x0f)).toByte()
            }
            q[45] = 0                                 // end of name
            q[47] = 0x21                              // QTYPE NBSTAT
            q[49] = 0x01                              // QCLASS IN
            return q
        }

        /** First unique workstation name (suffix 0x00) in an NBSTAT answer, or null. */
        fun parseNbstat(r: ByteArray, len: Int): String? {
            if (len < 57) return null
            var pos = 12
            while (pos < len) { // answer name: labels until 0, or a compression pointer
                val l = r[pos].toInt() and 0xff
                if (l == 0) { pos++; break }
                if (l and 0xc0 == 0xc0) { pos += 2; break }
                pos += 1 + l
            }
            pos += 2 + 2 + 4 + 2                      // type, class, ttl, rdlength
            if (pos >= len) return null
            val count = r[pos++].toInt() and 0xff
            var i = 0
            while (i < count && pos + 18 <= len) {
                val suffix = r[pos + 15].toInt() and 0xff
                val group = r[pos + 16].toInt() and 0x80 != 0
                if (suffix == 0x00 && !group) {
                    val n = String(r, pos, 15, StandardCharsets.US_ASCII).trim()
                    if (n.isNotEmpty()) return n
                }
                i++
                pos += 18
            }
            return null
        }

        fun netbiosName(ip: String): String? {
            try {
                DatagramSocket().use { s ->
                    s.soTimeout = 700
                    val id = Random().nextInt(0xffff)
                    val q = nbstatRequest(id)
                    val to = InetSocketAddress(ip, 137)
                    val buf = ByteArray(1024)
                    repeat(2) {
                        s.send(DatagramPacket(q, q.size, to))
                        val p = DatagramPacket(buf, buf.size)
                        try { s.receive(p) } catch (_: IOException) { return@repeat }
                        if (p.length > 2 && (buf[0].toInt() and 0xff) == (id shr 8 and 0xff) &&
                            (buf[1].toInt() and 0xff) == (id and 0xff)) return parseNbstat(buf, p.length)
                    }
                }
            } catch (_: IOException) {
                // no NetBIOS on that host
            }
            return null
        }
    }
}
