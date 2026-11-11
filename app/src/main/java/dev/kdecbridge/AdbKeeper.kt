package dev.kdecbridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import tsbridge.Tsbridge

/**
 * Keeps adbd listening on TCP port 5555, so that adb and scrcpy on the
 * computer can connect over the tailnet.
 *
 * An app can change adbd's mode only as an ADB client. Once adbd trusts the
 * app's key, through pairing with Wireless debugging ([AdbPairing]) or the
 * "Allow USB debugging?" prompt ([authorize]), the app turns Wireless
 * debugging on, connects to adbd on this phone and sends "tcpip:5555", as
 * `adb tcpip 5555` does, then turns Wireless debugging off again. Android
 * allows Wireless debugging only on Wi-Fi, so turning TCP ADB back on after a
 * restart waits for Wi-Fi. Once on, port 5555 stays open on every network
 * until the phone restarts or USB debugging is turned off.
 *
 * Android removes an ADB key that has not connected for seven days, and the
 * app's key is needed only after a restart, which can be weeks apart. While
 * TCP ADB is on, the app therefore turns that timeout off, unless the user
 * has unticked the option. It also checks its key on port 5555 at intervals,
 * to notice a removed key while allowing it again is still one tap.
 */
object AdbKeeper {

    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

    /** Minimum interval between checks that no event asked for. */
    private const val RECHECK_MS = 10 * 60_000L

    /** Delays before a failed attempt is repeated. After the last one, the
     *  next attempt waits for an event or the periodic check. */
    private val RETRY_MS = longArrayOf(15_000, 30_000, 60_000, 120_000, 300_000)

    /** How often the app's key is checked on port 5555. */
    private const val KEY_CHECK_MS = 12 * 3_600_000L

    /** Settings.Global.ADB_ALLOWED_CONNECTION_TIME: how long an ADB key stays
     *  trusted without connecting, in milliseconds. 0 means indefinitely. It
     *  is the setting behind "Disable adb authorization timeout" in
     *  Developer options. */
    private const val ADB_ALLOWED_CONNECTION_TIME = "adb_allowed_connection_time"
    private const val DEFAULT_ALLOWED_CONNECTION_TIME = 7 * 24 * 3_600_000L

    /** After a connection from the tailnet led to a failed attempt, further
     *  connections start no new attempt for this long, doubling up to the
     *  maximum. adb retries an unreachable device several times a second. */
    private const val WAKE_COOLDOWN_MS = 60_000L
    private const val WAKE_COOLDOWN_MAX_MS = 30 * 60_000L

    /** Upper bound for the wakelock held while port 5555 is being opened. */
    private const val WAKELOCK_MS = 120_000L

    private const val KEY_NOT_ALLOWED = "key not allowed yet - tap Turn on TCP ADB"
    private const val KEY_LOST = "the app's key is no longer allowed - tap Turn on TCP ADB to allow it again"

    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "adb") }

    private enum class Outcome { DONE, WAITING, FAILED }

    /** The application context, which lives as long as the process. */
    @SuppressLint("StaticFieldLeak")
    @Volatile private var appCtx: Context? = null

    // Used on the worker thread only.
    private var failures = 0
    private var pendingRetry: ScheduledFuture<*>? = null

    private val wakePending = AtomicBoolean(false)
    @Volatile private var wakeBlockedUntil = 0L
    @Volatile private var wakeFailures = 0

    /** Set when adbd refused a key it had trusted before. */
    @Volatile private var keyLost = false

    /** Whether adbd accepted a connection on port 5555 at the last check. */
    @Volatile var listening = false
        private set

    /** Why TCP ADB is not on, for display. Empty when there is nothing to report. */
    @Volatile var detail = ""
        private set

    /** True while the worker is opening, closing or authorizing. */
    @Volatile private var busy = false

    @Volatile private var lastCheck = 0L
    @Volatile private var lastProbe = 0L

    /** Wi-Fi network on which Android refused Wireless debugging. Automatic
     *  attempts skip it, so that the confirmation dialog is not shown again. */
    @Volatile private var refusedOn: Network? = null

    /** Whether that refusal happened with the screen locked. Android does not
     *  ask the user then, so one more attempt is made after the next unlock. */
    @Volatile private var refusedLocked = false

    fun keyDir(ctx: Context): String = File(ctx.filesDir, "adb").absolutePath

    /** Called when the service starts; [wake] needs a context. */
    fun attach(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    /** Opens or closes the tailnet listener to match the settings. */
    fun applyForward(cfg: Config) {
        val port = if (cfg.adbTcp && cfg.mode == Config.MODE_TSNET) Config.ADB_PORT else 0
        runCatching { Tsbridge.setAdbForward(port.toLong()) }
            .onFailure { BridgeService.log("adb: tailnet :${Config.ADB_PORT} not available - ${it.message}") }
    }

    /** Turns TCP ADB on and keeps it on. The caller has the key authorized
     *  first if [Config.adbAuthorized] is false. */
    fun turnOn(ctx: Context) {
        val app = ctx.applicationContext
        val cfg = Config(app)
        cfg.adbTcp = true
        refusedOn = null
        refusedLocked = false
        wakeBlockedUntil = 0L
        wakeFailures = 0
        applyForward(cfg)
        BridgeService.log("adb: TCP ADB turned on in the app")
        worker.execute { arm(app, "turned on in the app", manual = true) }
    }

    /**
     * Has adbd trust the app's key while port 5555 is open, for example after
     * `adb tcpip 5555` from a computer. adbd shows the "Allow USB debugging?"
     * prompt; with "Always allow", Wireless debugging accepts the key as well.
     */
    fun authorize(ctx: Context) {
        val app = ctx.applicationContext
        worker.execute {
            busy = true
            try {
                detail = "allow debugging on the prompt and tick Always allow"
                val r = runCatching {
                    Tsbridge.adbAuthorize(keyDir(app), "127.0.0.1", Config.ADB_PORT.toLong(), 60)
                }
                r.exceptionOrNull()?.let { return@execute report("key not allowed: ${it.message}") }
                // Without "Always allow", adbd trusts the key for that one
                // connection only. With it, adbd reloads its key list a moment
                // after the prompt, so allow a few attempts.
                var kept = false
                for (i in 1..5) {
                    kept = runCatching {
                        Tsbridge.adbExec(keyDir(app), "127.0.0.1", Config.ADB_PORT.toLong(), "shell:true", 10)
                    }.isSuccess
                    if (kept) break
                    Thread.sleep(1_000)
                }
                if (!kept) {
                    return@execute report("the key was allowed once only - tap Turn on TCP ADB again " +
                        "and tick Always allow")
                }
                Config(app).apply {
                    adbAuthorized = true
                    adbKeyCheckedAt = System.currentTimeMillis()
                }
                keyLost = false
                detail = ""
                BridgeService.log("adb: key allowed on the phone")
                if (!canWriteSecureSettings(app)) grantWriteSecureSettings(app)
                arm(app, "key allowed", manual = true)
            } finally {
                busy = false
            }
        }
    }

    /** Closes port 5555 and Wireless debugging, and stops turning TCP ADB back on. */
    fun turnOff(ctx: Context) {
        val app = ctx.applicationContext
        val cfg = Config(app)
        cfg.adbTcp = false
        applyForward(cfg)
        BridgeService.log("adb: TCP ADB turned off in the app")
        worker.execute { disarm(app) }
    }

    /** Checks port 5555 and turns TCP ADB back on if it is wanted and closed. */
    fun check(ctx: Context, why: String) {
        val app = ctx.applicationContext
        worker.execute { arm(app, why, manual = false) }
    }

    /**
     * Called when adb or scrcpy connects from the tailnet and port 5555 is
     * closed. Starts a check and reports whether the port is likely to open
     * within a few seconds, in which case the connection is kept waiting.
     */
    fun wake(): Boolean = runCatching {
        val ctx = appCtx ?: return false
        val cfg = Config(ctx)
        if (!cfg.adbTcp) return false
        if (SystemClock.elapsedRealtime() < wakeBlockedUntil) return false
        if (wakePending.compareAndSet(false, true)) {
            worker.execute {
                try {
                    arm(ctx, "incoming connection", manual = false)
                    if (listening) {
                        wakeFailures = 0
                    } else {
                        val wait = (WAKE_COOLDOWN_MS shl wakeFailures.coerceAtMost(5))
                            .coerceAtMost(WAKE_COOLDOWN_MAX_MS)
                        wakeFailures++
                        wakeBlockedUntil = SystemClock.elapsedRealtime() + wait
                    }
                } finally {
                    wakePending.set(false)
                }
            }
        }
        Build.VERSION.SDK_INT >= 30 && cfg.adbAuthorized && debuggingEnabled(ctx) &&
            canWriteSecureSettings(ctx) && wifiNetwork(ctx).let { it != null && it != refusedOn }
    }.getOrDefault(false)

    /** Called when the phone is unlocked. Android asks the user about an
     *  unknown Wi-Fi network only while unlocked, so a refusal that happened
     *  with the screen locked is tried once more. */
    fun onUnlock(ctx: Context) {
        val app = ctx.applicationContext
        if (!Config(app).adbTcp) return
        worker.execute {
            if (refusedLocked) {
                refusedLocked = false
                refusedOn = null
            }
            arm(app, "phone unlocked", manual = false)
        }
    }

    /** Called when the "keep authorizations" option changes. */
    fun keyTimeoutChanged(ctx: Context) {
        val app = ctx.applicationContext
        worker.execute { applyKeyTimeout(app, Config(app)) }
    }

    /** One line for the status panel, or null if the setting cannot be read. */
    fun keyTimeoutLine(ctx: Context): String? = when (val t = allowedConnectionTime(ctx)) {
        null -> null
        0L -> "kept - Android's authorization timeout is off"
        else -> "removed after ${t / (24 * 3_600_000L)} days without a connection"
    }

    /** Called on every injector pass; checks at most every [RECHECK_MS]. */
    fun periodic(ctx: Context) {
        if (Config(ctx).adbTcp && System.currentTimeMillis() - lastCheck > RECHECK_MS) {
            check(ctx, "periodic check")
        }
    }

    /** Called after pairing: grants the app permission to switch Wireless
     *  debugging on and off, then turns TCP ADB on if it is wanted. */
    fun afterPairing(ctx: Context) {
        val app = ctx.applicationContext
        worker.execute {
            busy = true
            try {
                if (!canWriteSecureSettings(app)) grantWriteSecureSettings(app)
                if (Config(app).adbTcp) arm(app, "paired", manual = true)
            } finally {
                busy = false
            }
        }
    }

    /** Refreshes [listening] in the background, at most every few seconds. */
    fun probeSoon() {
        val now = System.currentTimeMillis()
        if (now - lastProbe < 5_000) return
        lastProbe = now
        Thread({ listening = probe() }, "adb-probe").start()
    }

    /** Checks port 5555 now and calls [then] on a background thread. */
    fun probeThen(then: (Boolean) -> Unit) {
        Thread({ listening = probe(); then(listening) }, "adb-probe").start()
    }

    /** One line for the status panel. */
    fun statusLine(cfg: Config): String = when {
        !cfg.adbTcp && listening -> "off in the app, but port ${Config.ADB_PORT} is open"
        !cfg.adbTcp -> "off"
        busy && detail.isNotBlank() -> detail
        listening && !cfg.adbAuthorized && detail.isNotBlank() -> "on - :${Config.ADB_PORT} open; $detail"
        listening && cfg.mode == Config.MODE_TSNET -> "on - :${Config.ADB_PORT} open, forwarded from the tailnet"
        listening -> "on - :${Config.ADB_PORT} open (tailnet forwarding needs tsnet mode)"
        busy -> detail.ifBlank { "working…" }
        else -> detail.ifBlank { "closed" }
    }

    // ---- worker -------------------------------------------------------------

    private fun arm(ctx: Context, why: String, manual: Boolean, retry: Boolean = false) {
        busy = true
        try {
            pendingRetry?.cancel(false)
            pendingRetry = null
            if (!retry) failures = 0
            if (armOnce(ctx, why, manual) == Outcome.FAILED && failures < RETRY_MS.size) {
                val delay = RETRY_MS[failures++]
                BridgeService.log("adb: trying again in ${delay / 1000}s")
                pendingRetry = worker.schedule(
                    { arm(ctx, "retry", manual = false, retry = true) }, delay, TimeUnit.MILLISECONDS)
            }
        } finally {
            busy = false
        }
    }

    private fun armOnce(ctx: Context, why: String, manual: Boolean): Outcome {
        lastCheck = System.currentTimeMillis()
        val cfg = Config(ctx)
        listening = probe()
        applyKeyTimeout(ctx, cfg)
        if (!cfg.adbTcp) {
            detail = ""
            Attention.cancel(ctx, Attention.ADB)
            return Outcome.DONE
        }
        if (listening) {
            if (keepKey(ctx, cfg, force = false)) {
                detail = ""
                Attention.cancel(ctx, Attention.ADB)
            }
            return Outcome.DONE
        }
        if (!debuggingEnabled(ctx)) return waiting("USB debugging is off")
        if (Build.VERSION.SDK_INT < 30) {
            return waiting("closed - run adb tcpip 5555 from a computer (Android 10 and earlier)")
        }
        if (!cfg.adbAuthorized) return waiting(if (keyLost) KEY_LOST else KEY_NOT_ALLOWED)
        if (!canWriteSecureSettings(ctx)) {
            return waiting("cannot switch Wireless debugging - tap Turn on TCP ADB to allow the key again")
        }
        val wifi = wifiNetwork(ctx) ?: return waiting("waiting for Wi-Fi")
        if (!manual && wifi == refusedOn) {
            return waiting("Wireless debugging is not allowed on this Wi-Fi network")
        }
        // Opening the port takes several seconds. Without a wakelock the
        // phone can suspend halfway when the attempt starts with the screen
        // off, for example when Wi-Fi connects.
        return awake(ctx) { open(ctx, cfg, wifi, why) }
    }

    private fun open(ctx: Context, cfg: Config, wifi: Network, why: String): Outcome {
        BridgeService.log("adb: turning TCP ADB on ($why)")
        detail = "turning on…"
        val r = runCatching {
            withWirelessDebugging(ctx) { svc ->
                AdbServices.onDevice(svc) { host, port ->
                    Tsbridge.adbExec(keyDir(ctx), host, port.toLong(), "tcpip:${Config.ADB_PORT}", 15)
                }
            }
        }
        r.exceptionOrNull()?.let { e ->
            return when {
                e is RefusedException -> {
                    refusedOn = wifi
                    refusedLocked = ctx.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
                    if (refusedLocked) {
                        waiting("Wireless debugging is not allowed on this Wi-Fi network - " +
                            "will ask when the phone is unlocked")
                    } else {
                        Attention.show(ctx, Attention.ADB, "Allow Wireless debugging on this Wi-Fi",
                            "TCP ADB is closed. Tap, then tick \"Always allow on this network\" and allow.",
                            Attention.ACTION_ADB_ON)
                        waiting("Wireless debugging is not allowed on this Wi-Fi network - " +
                            "allow it once in Developer options")
                    }
                }
                isKeyRejected(e) -> {
                    keyRejected(ctx, cfg)
                    Outcome.WAITING
                }
                else -> {
                    report("could not turn on: ${e.message}")
                    Outcome.FAILED
                }
            }
        }
        listening = waitFor(true)
        if (!listening) {
            report("adbd did not open :${Config.ADB_PORT}")
            return Outcome.FAILED
        }
        detail = ""
        BridgeService.log("adb: TCP ADB on, :${Config.ADB_PORT} open")
        if (keepKey(ctx, cfg, force = true)) Attention.cancel(ctx, Attention.ADB)
        return Outcome.DONE
    }

    /** The ADB authorization timeout in milliseconds, or null if Android
     *  does not let the app read it. */
    private fun allowedConnectionTime(ctx: Context): Long? = runCatching {
        Settings.Global.getLong(ctx.contentResolver, ADB_ALLOWED_CONNECTION_TIME, DEFAULT_ALLOWED_CONNECTION_TIME)
    }.getOrNull()

    /**
     * Turns Android's ADB authorization timeout off while TCP ADB is on and
     * the option is ticked, and puts the previous value back otherwise. A
     * value the user changed in the meantime is left alone.
     */
    private fun applyKeyTimeout(ctx: Context, cfg: Config) {
        if (!canWriteSecureSettings(ctx)) return
        val cr = ctx.contentResolver
        val current = allowedConnectionTime(ctx)
        if (cfg.adbTcp && cfg.adbKeepKeys) {
            if (current == 0L) return
            // Unreadable, and already turned off by an earlier pass.
            if (current == null && cfg.adbTimeoutSaved >= 0) return
            runCatching { Settings.Global.putLong(cr, ADB_ALLOWED_CONNECTION_TIME, 0L) }
                .onSuccess {
                    cfg.adbTimeoutSaved = current ?: DEFAULT_ALLOWED_CONNECTION_TIME
                    BridgeService.log("adb: authorization timeout turned off, so that ADB keys stay trusted")
                }
                .onFailure { BridgeService.log("adb: could not turn off the authorization timeout - ${it.message}") }
        } else if (cfg.adbTimeoutSaved >= 0) {
            val saved = cfg.adbTimeoutSaved
            cfg.adbTimeoutSaved = -1
            if (current != null && current != 0L) return
            runCatching { Settings.Global.putLong(cr, ADB_ALLOWED_CONNECTION_TIME, saved) }
                .onSuccess { BridgeService.log("adb: authorization timeout restored") }
        }
    }

    /**
     * Checks the app's key on port 5555 if it has not been checked for
     * [KEY_CHECK_MS]. Returns false if the key is not trusted.
     */
    private fun keepKey(ctx: Context, cfg: Config, force: Boolean): Boolean {
        if (!cfg.adbAuthorized) {
            report(if (keyLost) KEY_LOST else KEY_NOT_ALLOWED)
            return false
        }
        val now = System.currentTimeMillis()
        if (!force && now - cfg.adbKeyCheckedAt in 0 until KEY_CHECK_MS) return true
        val e = runCatching {
            Tsbridge.adbCheck(keyDir(ctx), "127.0.0.1", Config.ADB_PORT.toLong(), 10)
        }.exceptionOrNull()
        when {
            e == null -> {
                cfg.adbKeyCheckedAt = now
                keyLost = false
            }
            isKeyRejected(e) -> {
                keyRejected(ctx, cfg)
                return false
            }
            // Anything else is not a verdict on the key; the next check tries again.
        }
        return true
    }

    private fun keyRejected(ctx: Context, cfg: Config) {
        cfg.adbAuthorized = false
        keyLost = true
        report(KEY_LOST)
        Attention.show(ctx, Attention.ADB, "TCP ADB needs your approval",
            "Android no longer trusts the app's ADB key. Tap to allow it again.",
            Attention.ACTION_ADB_ON)
    }

    private fun waiting(msg: String): Outcome {
        report(msg)
        return Outcome.WAITING
    }

    private fun <T> awake(ctx: Context, block: () -> T): T {
        val lock = runCatching {
            ctx.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kdecbridge:adb")
        }.getOrNull()
        runCatching { lock?.acquire(WAKELOCK_MS) }
        try {
            return block()
        } finally {
            runCatching { if (lock?.isHeld == true) lock.release() }
        }
    }

    private fun disarm(ctx: Context) {
        busy = true
        try { disarmOnce(ctx) } finally { busy = false }
    }

    private fun disarmOnce(ctx: Context) {
        pendingRetry?.cancel(false)
        pendingRetry = null
        Attention.cancel(ctx, Attention.ADB)
        detail = ""
        var error: String? = null
        if (probe()) {
            runCatching {
                Tsbridge.adbExec(keyDir(ctx), "127.0.0.1", Config.ADB_PORT.toLong(), "usb:", 10)
            }.onFailure { error = it.message }
        }
        // If port 5555 refused the key, send the same request over Wireless debugging.
        if (error != null && Config(ctx).adbAuthorized && canWriteSecureSettings(ctx) && wifiNetwork(ctx) != null) {
            runCatching {
                withWirelessDebugging(ctx) { svc ->
                    AdbServices.onDevice(svc) { host, port ->
                        Tsbridge.adbExec(keyDir(ctx), host, port.toLong(), "usb:", 10)
                    }
                }
            }.onSuccess { error = null }
        }
        if (canWriteSecureSettings(ctx)) {
            runCatching { Settings.Global.putInt(ctx.contentResolver, ADB_WIFI_ENABLED, 0) }
        }
        applyKeyTimeout(ctx, Config(ctx))
        listening = waitFor(false)
        if (listening) {
            report("could not close :${Config.ADB_PORT}" + (error?.let { " ($it)" } ?: "") +
                " - turn USB debugging off and on to close it")
        } else {
            BridgeService.log("adb: TCP ADB off, :${Config.ADB_PORT} closed")
        }
    }

    private fun grantWriteSecureSettings(ctx: Context) {
        val cmd = "shell:pm grant ${ctx.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}"
        runCatching {
            if (probe()) {
                Tsbridge.adbExec(keyDir(ctx), "127.0.0.1", Config.ADB_PORT.toLong(), cmd, 15)
            } else {
                // Wireless debugging is on while Settings shows the pairing dialog.
                val svc = AdbServices.find(ctx, AdbServices.CONNECT, 10_000)
                    ?: error("Wireless debugging port not found")
                AdbServices.onDevice(svc) { host, port ->
                    Tsbridge.adbExec(keyDir(ctx), host, port.toLong(), cmd, 15)
                }
            }
        }.onFailure { BridgeService.log("adb: could not grant WRITE_SECURE_SETTINGS - ${it.message}") }
        if (canWriteSecureSettings(ctx)) BridgeService.log("adb: granted WRITE_SECURE_SETTINGS")
    }

    private class RefusedException : Exception("Wireless debugging refused on this network")

    /**
     * Runs [block] with the Wireless debugging service. Turns Wireless
     * debugging on first if it is off, and off again afterwards.
     *
     * On a Wi-Fi network the user has not allowed, Android switches Wireless
     * debugging straight back off. With the screen unlocked it also asks the
     * user, who may still allow it, so the search continues; with the screen
     * locked it does not ask, so the search ends. Either way a refusal ends
     * in [RefusedException].
     */
    private fun <T> withWirelessDebugging(ctx: Context, block: (AdbServices.Service) -> T): T {
        val cr = ctx.contentResolver
        val keyguard = ctx.getSystemService(KeyguardManager::class.java)
        fun isOn() = Settings.Global.getInt(cr, ADB_WIFI_ENABLED, 0) == 1
        val wasOn = isOn()
        if (!wasOn) Settings.Global.putInt(cr, ADB_WIFI_ENABLED, 1)
        try {
            val svc = AdbServices.find(ctx, AdbServices.CONNECT, 30_000) {
                !isOn() && keyguard?.isKeyguardLocked == true
            }
            if (svc == null && !isOn()) throw RefusedException()
            return block(svc ?: error("Wireless debugging port not found"))
        } finally {
            if (!wasOn) runCatching { Settings.Global.putInt(cr, ADB_WIFI_ENABLED, 0) }
        }
    }

    private fun report(msg: String) {
        if (msg != detail) BridgeService.log("adb: $msg")
        detail = msg
    }

    private fun isKeyRejected(e: Throwable) = e.message?.contains("rejected this app's key") == true

    private fun probe(): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", Config.ADB_PORT), 500) }
        true
    }.getOrDefault(false)

    /** Waits up to 10 seconds for port 5555 to open or close. */
    private fun waitFor(open: Boolean): Boolean {
        repeat(20) {
            val now = probe()
            if (now == open) return now
            Thread.sleep(500)
        }
        return probe()
    }

    private fun debuggingEnabled(ctx: Context): Boolean {
        val cr = ctx.contentResolver
        return Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1 &&
            Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1
    }

    fun canWriteSecureSettings(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** The Wi-Fi network, if the phone is on one. A VPN may be the default network. */
    @Suppress("DEPRECATION") // allNetworks: a callback would only duplicate this one lookup
    private fun wifiNetwork(ctx: Context): Network? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.allNetworks.firstOrNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }
}
