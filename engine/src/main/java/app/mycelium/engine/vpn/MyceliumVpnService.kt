package app.mycelium.engine.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.mycelium.engine.MyceliumEngine
import app.mycelium.engine.R
import app.mycelium.engine.attr.Attribution
import app.mycelium.engine.classify.Classifier
import app.mycelium.engine.db.Row
import app.mycelium.engine.dns.DnsQuery
import app.mycelium.engine.dns.Packets
import app.mycelium.engine.dns.TcpSyn
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** DNS-only VPN: watched apps' DNS goes to 10.111.222.1; SYNs to 10.111.222.9 are tripwires. Everything else stays off the tunnel. */
class MyceliumVpnService : VpnService() {
    private val tunLock = Any()
    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var running = false
    private var reader: Thread? = null
    private var retried = false
    private val main = Handler(Looper.getMainLooper())
    private val pool = ThreadPoolExecutor(2, 8, 30, TimeUnit.SECONDS, ArrayBlockingQueue(256), ThreadPoolExecutor.DiscardPolicy())
    private val pending = ConcurrentHashMap<Int, Long>()   // (src port << 16 | dns id) -> sent at

    override fun onCreate() {
        super.onCreate()
        MyceliumEngine.init(this)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (running) shutdown()
            stopped("stopped")
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundNotification()
        if (!running) {
            retried = false
            if (!launch()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        shutdown()
        stopped("revoked by system or another VPN")
        stopSelf()
    }

    override fun onDestroy() {
        instance = null
        if (running) { shutdown(); stopped("service destroyed") }
        main.removeCallbacksAndMessages(null)
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun stopped(note: String) {
        MyceliumEngine.setVpn("stopped")
        MyceliumEngine.logEvent(vpnEvent(note))
    }

    /** Builder with one addAllowedApplication per installed watched app. Returns the count added. */
    private fun builder(): Pair<Builder, Int> {
        val b = Builder()
            .setSession("Mycelium")
            .addAddress(TUN_ADDR, 24)
            .addRoute(ROUTE, 24)
            .addDnsServer(DNS_ADDR)
            .allowFamily(OsConstants.AF_INET6)
            .setMtu(1500)
        var added = 0
        for (pkg in WatchList.packages()) {
            if (pkg == packageName) continue
            try { b.addAllowedApplication(pkg); added++ } catch (_: PackageManager.NameNotFoundException) {}
        }
        return b to added
    }

    private fun establish(b: Builder) = try { b.establish() } catch (e: Exception) { Log.e(TAG, "establish", e); null }

    @Synchronized private fun launch(): Boolean {
        val (b, added) = builder()
        // Zero allowed apps would route every app: never establish in that case.
        if (added == 0) {
            stopped("not started: no watched app is installed")
            return false
        }
        val fd = establish(b)
        if (fd == null) {
            stopped("not started: establish failed (no consent?)")
            return false
        }
        tun = fd
        running = true
        MyceliumEngine.setVpn("running")
        MyceliumEngine.logEvent(vpnEvent("started: $added apps, mode ${MyceliumEngine.mode}"))
        reader = thread(name = "tun-reader") { readLoop(fd) }
        return true
    }

    /**
     * Seamless restart after a selection change: establish a new tun (no consent needed), point
     * writes and a new reader at it, then let the old reader exit and close the old fd off-thread.
     * Returns false if the VPN was not running.
     */
    @Synchronized private fun rebuild(): Boolean {
        if (!running) return false
        val (b, added) = builder()
        if (added == 0) {
            shutdown()
            stopped("stopped: no watched app is installed")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return true
        }
        val fd = establish(b) ?: run {
            MyceliumEngine.logEvent(vpnEvent("rebuild failed: establish returned null, keeping old tunnel"))
            return true
        }
        val oldFd: ParcelFileDescriptor?
        synchronized(tunLock) { oldFd = tun; tun = fd }
        val oldReader = reader
        reader = thread(name = "tun-reader") { readLoop(fd) }
        thread(name = "tun-retire") {
            oldReader?.join(2000)   // exits within one poll interval once tun != its fd
            try { oldFd?.close() } catch (_: IOException) {}
        }
        return true
    }

    private fun shutdown() {
        running = false
        main.removeCallbacksAndMessages(null)
        reader?.takeIf { it != Thread.currentThread() }?.join(1000)
        reader = null
        closeTun()
    }

    private fun closeTun() = synchronized(tunLock) {
        try { tun?.close() } catch (_: IOException) {}
        tun = null
    }

    private fun readLoop(pfd: ParcelFileDescriptor) {
        val fd = pfd.fileDescriptor
        val buf = ByteArray(32767)
        val polls = arrayOf(StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() })
        try {
            while (running && tun === pfd) {
                val n = try {
                    if (Os.poll(polls, 500) <= 0) continue
                    Os.read(fd, buf, 0, buf.size)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) continue
                    throw e
                }
                if (n <= 0) continue
                try { handle(buf.copyOf(n)) } catch (e: Exception) { Log.w(TAG, "packet dropped", e) }
            }
        } catch (e: Throwable) {
            if (running && tun === pfd) died(e)
        }
    }

    /** Watchdog: mark dead, record it, retry once after 2 s. */
    private fun died(e: Throwable) {
        Log.e(TAG, "reader died", e)
        running = false
        closeTun()
        MyceliumEngine.setVpn("dead")
        MyceliumEngine.logEvent(vpnEvent("dead: $e"))
        if (retried) return
        retried = true
        main.postDelayed({
            if (!running && MyceliumEngine.vpn == "dead") {
                MyceliumEngine.logEvent(vpnEvent("watchdog: restarting"))
                if (!launch()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            }
        }, 2000)
    }

    private fun handle(p: ByteArray) {
        val q = Packets.parseDnsQuery(p)
        if (q != null) {
            if (q.dstIp == DNS_IP && q.dstPort == 53) dns(q)
            return
        }
        val syn = Packets.parseTcpSyn(p) ?: return
        if (syn.dstIp == TRIP_IP) tripwire(syn)
    }

    private fun dns(q: DnsQuery) {
        val now = System.currentTimeMillis()
        val who = MyceliumEngine.attr.udp(q)   // before any reply, while the app's socket exists
        val v = Classifier.classify(q.qname, who.pkg, who.how)
        val online = MyceliumEngine.online
        // Blocked (any mode) and Sanctuary trackers point at the tripwire, so retries show up as SYNs.
        if (v.blockedBy != null || !online) {
            val ip = if (v.blockedBy != null || v.tracker) TRIP_IP else 0
            val answer = if (q.qtype == Packets.TYPE_A) Packets.buildAnswerA(q, listOf(ip)) else Packets.buildNoData(q)
            write(Packets.buildUdpReply(q, answer))
            if (online) {
                MyceliumEngine.logEvent(dnsEvent(q, who, v, now, "blocked", note = "rule ${v.blockedBy!!.scope}=${v.blockedBy.value} pkg=${v.blockedBy.pkg}"))
            } else {
                MyceliumEngine.enqueue(
                    mapOf("ts" to now, "kind" to "dns", "ptype" to Classifier.typer.type("dns", "udp", 53, q.qname, v.company), "uid" to who.uid,
                        "domain" to q.qname, "qtype" to q.qtype) + who.row(),
                )
            }
            return
        }
        if (q.qtype == Packets.TYPE_HTTPS) {
            write(Packets.buildUdpReply(q, Packets.buildNoData(q)))
            MyceliumEngine.logEvent(dnsEvent(q, who, v, now, "allowed", note = "https_nodata"))
            return
        }
        val key = (q.srcPort shl 16) or q.id
        val prev = pending.put(key, now)
        if (prev != null && now - prev < TIMEOUT_MS) { pending[key] = prev; return }   // retransmit of an in-flight query
        if (pending.size > 1024) pending.values.removeIf { now - it > TIMEOUT_MS }
        pool.execute { forward(q, who, v, key, now) }
    }

    private fun forward(q: DnsQuery, who: Attribution.Result, v: Classifier.Verdict, key: Int, ts: Long) {
        try {
            val server = MyceliumEngine.net.upstreamDns() ?: FALLBACK_DNS
            val reply = DatagramSocket().use { s ->
                protect(s)
                s.send(DatagramPacket(q.payload, q.payload.size, server, 53))
                receiveReply(s, q.id, ts + TIMEOUT_MS)
            }
            val capped = Packets.rewriteTtls(reply) ?: throw IOException("malformed upstream reply")
            if (!running) return
            write(Packets.buildUdpReply(q, capped))
            MyceliumEngine.logEvent(dnsEvent(q, who, v, ts, "allowed", answer = Packets.extractIps(capped).joinToString(",")))
        } catch (e: Exception) {
            MyceliumEngine.logEvent(dnsEvent(q, who, v, ts, "error", note = e.toString()))
        } finally {
            pending.remove(key)
        }
    }

    /** Waits for the reply with [id], ignoring strays, until [deadline]; throws SocketTimeoutException. */
    private fun receiveReply(s: DatagramSocket, id: Int, deadline: Long): ByteArray {
        val buf = ByteArray(4096)
        while (true) {
            s.soTimeout = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1)
            val pk = DatagramPacket(buf, buf.size)
            s.receive(pk)
            if (pk.length >= 12 && (((buf[0].toInt() and 255) shl 8) or (buf[1].toInt() and 255)) == id) return buf.copyOf(pk.length)
        }
    }

    private fun tripwire(s: TcpSyn) {
        val who = MyceliumEngine.attr.tcp(s)   // before the RST closes the app's socket
        write(Packets.buildTcpRst(s))
        val row: Row = mapOf("ts" to System.currentTimeMillis(), "kind" to "tripwire", "ptype" to Classifier.typer.type("tripwire", "tcp", s.dstPort, null, null),
            "port" to s.dstPort) + who.row()
        if (MyceliumEngine.online) MyceliumEngine.logEvent(row + ("verdict" to "blocked")) else MyceliumEngine.enqueue(row + ("uid" to who.uid))
    }

    private fun write(p: ByteArray) {
        synchronized(tunLock) {
            val fd = tun ?: return
            try { Os.write(fd.fileDescriptor, p, 0, p.size) } catch (e: Exception) { Log.w(TAG, "tun write", e) }
        }
    }

    private fun dnsEvent(
        q: DnsQuery, who: Attribution.Result, v: Classifier.Verdict, ts: Long, verdict: String,
        answer: String? = null, note: String? = null,
    ): Row = mapOf(
        "ts" to ts, "kind" to "dns", "ptype" to Classifier.typer.type("dns", "udp", 53, q.qname, v.company), "domain" to q.qname, "qtype" to q.qtype,
        "verdict" to verdict, "answer" to answer, "note" to note,
    ) + who.row() + v.row()

    private fun vpnEvent(note: String): Row = mapOf("ts" to System.currentTimeMillis(), "kind" to "vpn", "note" to note)

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.mycelium_notif_channel), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MyceliumVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 2, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mycelium)
            .setContentTitle(getString(R.string.mycelium_notif_title))
            .setContentText(getString(R.string.mycelium_notif_text))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_stat_mycelium), getString(R.string.mycelium_notif_pause), stop,
            ).build())
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
    }

    companion object {
        private const val TAG = "MyceliumVpn"
        private const val ACTION_START = "app.mycelium.engine.vpn.START"
        private const val ACTION_STOP = "app.mycelium.engine.vpn.STOP"
        private const val CHANNEL = "garden"
        private const val NOTIFICATION_ID = 1
        private const val TIMEOUT_MS = 2000L
        const val TUN_ADDR = "10.111.222.2"
        const val ROUTE = "10.111.222.0"
        const val DNS_ADDR = "10.111.222.1"
        private const val DNS_IP = 0x0A6FDE01    // 10.111.222.1
        private const val TRIP_IP = 0x0A6FDE09   // 10.111.222.9
        private val FALLBACK_DNS: InetAddress = InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))
        @Volatile private var instance: MyceliumVpnService? = null

        /** Rebuilds the tunnel with the current WatchList. False if the VPN is not running. */
        fun rebuild(): Boolean = instance?.rebuild() ?: false

        fun start(context: Context) = ContextCompat.startForegroundService(
            context, Intent(context, MyceliumVpnService::class.java).setAction(ACTION_START),
        )

        fun stop(context: Context) {
            context.startService(Intent(context, MyceliumVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
