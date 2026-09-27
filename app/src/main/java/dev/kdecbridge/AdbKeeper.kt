package dev.kdecbridge

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
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
 */
object AdbKeeper {

    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

    /** Minimum interval between checks that no event asked for. */
    private const val RECHECK_MS = 10 * 60_000L

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "adb") }

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

    fun keyDir(ctx: Context): String = File(ctx.filesDir, "adb").absolutePath

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
                Config(app).adbAuthorized = true
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
        listening && cfg.mode == Config.MODE_TSNET -> "on - :${Config.ADB_PORT} open, forwarded from the tailnet"
        listening -> "on - :${Config.ADB_PORT} open (tailnet forwarding needs tsnet mode)"
        busy -> detail.ifBlank { "working…" }
        else -> detail.ifBlank { "closed" }
    }

    // ---- worker -------------------------------------------------------------

    private fun arm(ctx: Context, why: String, manual: Boolean) {
        busy = true
        try { armOnce(ctx, why, manual) } finally { busy = false }
    }

    private fun armOnce(ctx: Context, why: String, manual: Boolean) {
        lastCheck = System.currentTimeMillis()
        val cfg = Config(ctx)
        listening = probe()
        if (!cfg.adbTcp || listening) {
            detail = ""
            return
        }
        if (!debuggingEnabled(ctx)) return report("USB debugging is off")
        if (Build.VERSION.SDK_INT < 30) {
            return report("closed - run adb tcpip 5555 from a computer (Android 10 and earlier)")
        }
        if (!cfg.adbAuthorized) return report("key not allowed yet - tap Turn on TCP ADB")
        if (!canWriteSecureSettings(ctx)) {
            return report("cannot switch Wireless debugging - tap Turn on TCP ADB to allow the key again")
        }
        val wifi = wifiNetwork(ctx) ?: return report("waiting for Wi-Fi")
        if (!manual && wifi == refusedOn) {
            return report("Wireless debugging is not allowed on this Wi-Fi network")
        }

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
            when {
                e is RefusedException -> {
                    refusedOn = wifi
                    report("Wireless debugging is not allowed on this Wi-Fi network - " +
                        "allow it once in Developer options")
                }
                isKeyRejected(e) -> {
                    cfg.adbAuthorized = false
                    report("the app's key is no longer allowed - tap Turn on TCP ADB to allow it again")
                }
                else -> report("could not turn on: ${e.message}")
            }
            return
        }
        listening = waitFor(true)
        if (listening) {
            detail = ""
            BridgeService.log("adb: TCP ADB on, :${Config.ADB_PORT} open")
        } else {
            report("adbd did not open :${Config.ADB_PORT}")
        }
    }

    private fun disarm(ctx: Context) {
        busy = true
        try { disarmOnce(ctx) } finally { busy = false }
    }

    private fun disarmOnce(ctx: Context) {
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
