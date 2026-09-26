package dev.kdecbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import tsbridge.Tsbridge

/**
 * Foreground service that runs the bridge.
 *
 * Forwards KDE Connect's ports from loopback to the computer and, while KDE
 * Connect is not connected, injects the computer's identity so that KDE
 * Connect connects to the bridge. Direct mode forwards here ([PortProxy]);
 * tsnet mode forwards in Go, and this class only injects.
 */
class BridgeService : Service() {

    companion object {
        const val ACTION_START = "dev.kdecbridge.START"
        const val ACTION_STOP = "dev.kdecbridge.STOP"
        private const val CHANNEL_ID = "kdec_bridge_quiet"
        private const val NOTIF_ID = 1
        private const val LOG_LIMIT = 60

        /** Longest wait while connected. Link changes wake the injector
         *  sooner; this bounds the heartbeat interval. */
        private const val IDLE_WAIT_MS = 120_000L

        /** Minimum interval between identity discovery attempts. */
        private const val DISCOVERY_RETRY_MS = 60_000L

        @Volatile var isRunning = false
            private set

        /** Whether KDE Connect currently has a live link through the bridge. */
        @Volatile var linkUpNow = false
            private set

        /** Loopback port the control channel is bound to, or 0 when stopped.
         *  Differs from Config.bridgePort if that port was taken. */
        @Volatile var activePort = 0
            private set

        private val logLines = ArrayDeque<String>()
        @Volatile var onLog: (() -> Unit)? = null

        fun log(msg: String) {
            android.util.Log.i("KdecBridge", msg)
            EventLog.add(msg)
            synchronized(logLines) {
                logLines.addLast(msg)
                while (logLines.size > LOG_LIMIT) logLines.removeFirst()
            }
            onLog?.invoke()
        }

        fun recentLog(): List<String> = synchronized(logLines) { logLines.toList() }

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, BridgeService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, BridgeService::class.java).setAction(ACTION_STOP))
        }
    }

    private lateinit var cfg: Config
    private var transport: Transport? = null
    private val proxies = mutableListOf<PortProxy>()
    private var pool: ExecutorService? = null
    private var injectThread: Thread? = null
    private var controlProxy: PortProxy? = null
    @Volatile private var discovering = false
    @Volatile private var lastDiscovery = 0L
    @Volatile private var tsnetUp = false
    @Volatile private var alive = false
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private val linkLock = Object()

    /** Go teardown runs off the main thread. A following start waits for it;
     *  otherwise Tsbridge.start() would still see the old server and return. */
    @Volatile private var pendingStop: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        EventLog.init(this)
    }

    /** Logged because some devices stop services when the app is swiped away. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        EventLog.add("app swiped out of recents (service continues)")
        super.onTaskRemoved(rootIntent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (::cfg.isInitialized) stopBridge()
            stopSelf()
            return START_NOT_STICKY
        }
        cfg = Config(this)
        startForegroundNotification("starting…")
        startBridge()
        return START_STICKY
    }

    // ---- bring-up -----------------------------------------------------------

    private fun startBridge() {
        if (alive) return
        val host = cfg.host
        if (host.isBlank()) {
            log("no computer address configured - open the app and set one")
            updateNotification("not configured")
            return
        }
        alive = true
        isRunning = true
        cfg.enabled = true
        cfg.cleanStop = false
        cfg.startedAt = System.currentTimeMillis()
        cfg.lastHeartbeat = System.currentTimeMillis()
        log("service started (mode=${cfg.mode}, target=$host)")
        registerNetworkCallback()

        if (cfg.mode == Config.MODE_TSNET) startTsnet(host) else startDirect(host)
    }

    private fun startDirect(host: String) {
        val t = DirectTcpTransport(host)
        transport = t
        val ex = Executors.newCachedThreadPool()
        pool = ex

        val control = PortProxy(cfg.bridgePort, Config.CONTROL_LAST, cfg.remotePort, t, ex, ::log, ::wakeInjector)
        if (!control.bind()) {
            log("no free loopback port in ${cfg.bridgePort}-${Config.CONTROL_LAST} - bridge cannot start")
            updateNotification("failed: no free port")
            return
        }
        controlProxy = control
        proxies += control
        control.start()
        activePort = control.boundPort
        if (activePort != cfg.bridgePort) log("port ${cfg.bridgePort} busy, control channel on :$activePort")
        log("control :$activePort -> $host:${cfg.remotePort}")

        if (cfg.proxyPayload) {
            val off = cfg.payloadOffset
            var ok = 0
            for (p in Config.PAYLOAD_MIN..Config.PAYLOAD_MAX) {
                val pp = PortProxy(p, p, p + off, t, ex, ::log)
                if (pp.bind()) { proxies += pp; pp.start(); ok++ }
            }
            log("payload :${Config.PAYLOAD_MIN}-${Config.PAYLOAD_MAX} -> $host (+$off), $ok bound")
        }

        tryLearnIdentity(host, "none learned yet")
        updateNotification("bridging ${Injector.deviceName(cfg.identityJson)} via $host")
        startInjector()
    }

    /** tsnet owns its listeners, so this only starts it and then injects.
     *  Start-up is retried with backoff, so that a failure at boot (for
     *  example, no network yet) does not leave the service idle. */
    private fun startTsnet(target: String) {
        updateNotification("starting tailnet…")
        Thread({
            pendingStop?.let { runCatching { it.join(5_000) } }
            var backoff = 5_000L
            while (alive) {
                val stateDir = java.io.File(filesDir, "tsnet").apply { mkdirs() }.absolutePath
                try {
                    // Must precede start(): tsnet queries interfaces during bring-up.
                    Tsbridge.setNetInfo(AndroidNetInfo)
                    Tsbridge.setLinkWatcher(object : tsbridge.LinkWatcher {
                        override fun onLink(up: Boolean) = wakeInjector()
                    })
                    Tsbridge.start(
                        stateDir, cfg.authKey, cfg.nodeName, target,
                        cfg.bridgePort.toLong(), cfg.remotePort.toLong(),
                        Config.PAYLOAD_MIN.toLong(), Config.FWD_LAST.toLong(),
                        Config.REV_FIRST.toLong(), Config.PAYLOAD_MAX.toLong(),
                        object : tsbridge.Logger {
                            override fun log(msg: String) = Companion.log(msg)
                        }
                    )
                    if (!alive) { runCatching { Tsbridge.stop() }; return@Thread }
                    tsnetUp = true
                    activePort = Tsbridge.controlPort().toInt()
                    if (cfg.authKey.isNotBlank()) {
                        // The node state on disk now holds the identity; the
                        // key is no longer needed and is not kept.
                        cfg.authKey = ""
                        log("auth key cleared - node identity is now on disk")
                    }
                    cfg.lastHeartbeat = System.currentTimeMillis()
                    tryLearnIdentity(target, "none learned yet")
                    updateNotification("bridging ${Injector.deviceName(cfg.identityJson)} via tailnet")
                    startInjector()
                    return@Thread
                } catch (e: Throwable) {
                    if (!alive) return@Thread
                    log("tsnet start failed - ${e.message}; retrying in ${backoff / 1000}s")
                    updateNotification("tailnet failed, retrying: ${e.message?.take(50)}")
                    try { Thread.sleep(backoff) } catch (_: InterruptedException) { return@Thread }
                    backoff = (backoff * 2).coerceAtMost(60_000L)
                }
            }
        }, "tsnet-start").start()
    }

    /**
     * Learns the identity of the computer at [host] if none is stored yet.
     *
     * Called at start-up and again from the injector loop while disconnected:
     * right after tsnet starts, MagicDNS may not resolve yet, so a single
     * attempt is not enough. Attempts are at least DISCOVERY_RETRY_MS apart.
     */
    private fun tryLearnIdentity(host: String, why: String) {
        if (cfg.learnedIdentity(host) != null) return
        if (discovering) return
        val now = System.currentTimeMillis()
        if (now - lastDiscovery < DISCOVERY_RETRY_MS) return
        lastDiscovery = now
        discovering = true
        Thread({
            log("identity: $why - asking $host")
            runCatching { IdentityDiscovery.learnAndStore(cfg) }
                .onSuccess {
                    log("identity: learned from ${Injector.deviceName(it)} (${Injector.deviceId(it).take(8)}…)")
                    updateNotification("bridging ${Injector.deviceName(it)} via ${cfg.mode}")
                    wakeInjector()
                }
                .onFailure { log("identity: discovery failed (${it.message}) - will retry") }
            discovering = false
        }, "identity-learn").start()
    }

    /**
     * Stores the injected identity once a link is up. KDE Connect completes
     * the TLS handshake only against the certificate pinned for that
     * deviceId, so a live link confirms the identity. Storing it also ends
     * discovery retries.
     */
    private fun adoptWorkingIdentity(host: String) {
        if (cfg.learnedIdentity(host) != null) return
        val id = cfg.identityJson
        cfg.storeIdentity(host, id)
        log("identity: adopted ${Injector.deviceName(id)} (confirmed by live link)")
    }

    // ---- injection loop -----------------------------------------------------

    private fun startInjector() {
        if (injectThread != null) return
        injectThread = Thread({
            var wasConnected = false
            while (alive) {
                val connected = linkUp()
                linkUpNow = connected
                if (connected) {
                    if (!wasConnected) {
                        log("LINK UP - KDE Connect is connected")
                        adoptWorkingIdentity(cfg.host)
                    }
                } else {
                    if (wasConnected) log("LINK DOWN - reconnecting")
                    // The first discovery attempt may run before MagicDNS
                    // resolves, so retry while disconnected.
                    tryLearnIdentity(cfg.host, "still unlearned")
                    // Read the identity and port on every pass, so a newly
                    // learned identity or fallback port applies immediately.
                    val port = activePort
                    if (port > 0) {
                        runCatching { Injector.inject(cfg.identityJson, port) }
                            .onFailure { log("inject failed - ${it.message}") }
                    }
                }
                wasConnected = connected
                cfg.lastHeartbeat = System.currentTimeMillis()

                // Connected: nothing to poll. PortProxy and the tsnet
                // LinkWatcher call wakeInjector() when the link drops; the
                // long wait only bounds the heartbeat. Disconnected: retry
                // every intervalSec.
                val waitMs = if (connected) IDLE_WAIT_MS else cfg.intervalSec * 1000L
                synchronized(linkLock) { runCatching { linkLock.wait(waitMs) } }
            }
        }, "injector").also { it.start() }
    }

    /** True while KDE Connect has a live link through the bridge. */
    private fun linkUp(): Boolean =
        if (cfg.mode == Config.MODE_TSNET) tsnetUp && Tsbridge.controlActive() > 0
        else (controlProxy?.active?.get() ?: 0) > 0

    /** Wakes the injector after a link or network change. */
    private fun wakeInjector() {
        synchronized(linkLock) { linkLock.notifyAll() }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                log("network changed - re-checking link")
                wakeInjector()
            }
        }
        netCallback = cb
        runCatching { cm.registerDefaultNetworkCallback(cb) }
    }

    // ---- teardown -----------------------------------------------------------

    private fun stopBridge() {
        alive = false
        isRunning = false
        linkUpNow = false
        activePort = 0
        cfg.enabled = false
        cfg.cleanStop = true
        netCallback?.let { cb ->
            runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        netCallback = null
        wakeInjector()
        injectThread = null
        proxies.forEach { it.stop() }
        proxies.clear()
        controlProxy = null
        pool?.shutdownNow()
        pool = null
        transport?.shutdown()
        transport = null
        if (cfg.mode == Config.MODE_TSNET) {
            // Closing sockets and aborting a pending login is network work;
            // keep it off the main thread (onStartCommand/onDestroy).
            tsnetUp = false
            pendingStop = Thread({ runCatching { Tsbridge.stop() } }, "tsnet-stop").also { it.start() }
        }
        log("bridge stopped")
    }

    override fun onDestroy() {
        // cleanStop is set only by a requested stop. Otherwise the service
        // was ended from outside the app (Doze, memory pressure, a task killer).
        if (::cfg.isInitialized && !cfg.cleanStop) {
            EventLog.add("SERVICE DESTROYED while running - killed by the system")
        }
        if (::cfg.isInitialized) stopBridge()
        super.onDestroy()
    }

    // ---- notification -------------------------------------------------------

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android raises IMPORTANCE_MIN to LOW for foreground services.
            // The icon can only be hidden by denying notification permission.
            val ch = NotificationChannel(CHANNEL_ID, "Bridge status", NotificationManager.IMPORTANCE_MIN)
            ch.setShowBadge(false)
            ch.lockscreenVisibility = Notification.VISIBILITY_SECRET
            nm.createNotificationChannel(ch)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("KDEC Bridge")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun startForegroundNotification(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification(text: String) {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text)) }
    }
}
