package dev.kdecbridge

import android.content.Context

/** Persistent settings. Port numbers are fixed by the KDE Connect protocol. */
class Config(private val ctx: Context) {

    companion object {
        /** KDE Connect's UDP discovery port; identity packets are injected here. */
        const val KDEC_UDP_PORT = 1716
        /** TCP port range accepted by KDE Connect. */
        const val MIN_PORT = 1716
        const val MAX_PORT = 1764
        /** Payload (file transfer) port range. */
        const val PAYLOAD_MIN = 1739
        const val PAYLOAD_MAX = 1764

        /** Last port the control channel may fall back to when the preferred
         *  port is taken. KDE Connect itself binds 1717 when 1716 is busy.
         *  Must stay below the payload range. */
        const val CONTROL_LAST = 1738

        /** In tsnet mode the payload range is split by direction. The bridge
         *  holds the low ports on loopback for transfers started by the
         *  computer, which moves KDE Connect's own payload server into the
         *  high ports, where the tailnet listeners accept the computer's
         *  connections. */
        const val FWD_LAST = 1743
        const val REV_FIRST = 1744

        /** adbd's TCP port, as set by `adb tcpip 5555`. */
        const val ADB_PORT = 5555

        const val MODE_DIRECT = "direct"
        const val MODE_TSNET = "tsnet"

        /** Injected until the computer's identity is learned. The all-zero id
         *  passes KDE Connect's device id check (^[a-zA-Z0-9_-]{32,38}$) but
         *  matches no device, so it can never pair. */
        const val PLACEHOLDER_IDENTITY =
            """{"deviceId":"00000000000000000000000000000000",""" +
            """"deviceName":"unconfigured computer","deviceType":"desktop",""" +
            """"protocolVersion":8,"incomingCapabilities":[],"outgoingCapabilities":[]}"""
    }

    private val p = ctx.getSharedPreferences("kdec_bridge", Context.MODE_PRIVATE)

    // ---- transport ----------------------------------------------------------

    /** "tsnet" (userspace Tailscale, the default) or "direct". */
    var mode: String
        get() = p.getString("mode", MODE_TSNET) ?: MODE_TSNET
        set(v) = p.edit().putString("mode", v).apply()

    /** Computer address: a MagicDNS name in tsnet mode, or a LAN address or
     *  relay host in direct mode. */
    var host: String
        get() = p.getString("host", "") ?: ""
        set(v) = p.edit().putString("host", v.trim()).apply()

    /** Port kdeconnectd is reachable on through the transport. 1716 unless a
     *  relay maps it elsewhere. */
    var remotePort: Int
        get() = p.getInt("remotePort", MIN_PORT)
        set(v) = p.edit().putInt("remotePort", v).apply()

    /** Preferred loopback port for the control channel. The service binds a
     *  higher one if this is taken; see BridgeService.activePort. */
    var bridgePort: Int
        get() = p.getInt("bridgePort", 1717)
        set(v) = p.edit().putInt("bridgePort", v.coerceIn(MIN_PORT + 1, CONTROL_LAST)).apply()

    /** Added to payload ports when connecting, for relays that expose
     *  1739-1764 on a different external range. 0 maps ports 1:1. */
    var payloadOffset: Int
        get() = p.getInt("payloadOffset", 0)
        set(v) = p.edit().putInt("payloadOffset", v).apply()

    var proxyPayload: Boolean
        get() = p.getBoolean("proxyPayload", true)
        set(v) = p.edit().putBoolean("proxyPayload", v).apply()

    /** Seconds between reconnect attempts while disconnected. KDE Connect
     *  accepts at most one connection per second from an address. */
    var intervalSec: Int
        get() = p.getInt("intervalSec", 10)
        set(v) = p.edit().putInt("intervalSec", v.coerceAtLeast(2)).apply()

    // ---- tailscale ----------------------------------------------------------

    /** Tailscale auth key. Cleared once the node has registered; the node
     *  state on disk is what persists. */
    var authKey: String
        get() = p.getString("authKey", "") ?: ""
        set(v) = p.edit().putString("authKey", v.trim()).apply()

    /** Hostname this node registers under on the tailnet. */
    var nodeName: String
        get() = p.getString("nodeName", "kdec-bridge") ?: "kdec-bridge"
        set(v) = p.edit().putString("nodeName", v.trim().ifBlank { "kdec-bridge" }).apply()

    // ---- computer identity --------------------------------------------------
    //
    // The injector sends the identity of the computer being bridged. It is
    // learned from the computer and stored per address, so switching or
    // renaming computers needs no rebuild. PLACEHOLDER_IDENTITY is used until
    // one has been learned.

    private fun identityKey(host: String) = "identity:" + host.trim().lowercase()

    /** Identity learned from the computer at [host], or null. */
    fun learnedIdentity(host: String): String? =
        p.getString(identityKey(host), null)

    fun storeIdentity(host: String, body: String) =
        p.edit().putString(identityKey(host), body).apply()

    fun forgetIdentity(host: String) =
        p.edit().remove(identityKey(host)).apply()

    /** Identity to inject for the current host: the learned one, or the
     *  placeholder. */
    val identityJson: String
        get() = learnedIdentity(host) ?: PLACEHOLDER_IDENTITY

    /** "learned" or "not learned", for display. */
    val identitySource: String
        get() = if (learnedIdentity(host) != null) "learned" else "not learned"

    fun isLearned(): Boolean = learnedIdentity(host) != null

    // ---- ADB for scrcpy -----------------------------------------------------

    /** Keep adbd listening on TCP port 5555 and forward it from the tailnet. */
    var adbTcp: Boolean
        get() = p.getBoolean("adbTcp", false)
        set(v) = p.edit().putBoolean("adbTcp", v).apply()

    /** Whether adbd trusts the app's ADB key, through pairing with Wireless
     *  debugging or the "Allow USB debugging?" prompt. */
    var adbAuthorized: Boolean
        get() = p.getBoolean("adbAuthorized", false)
        set(v) = p.edit().putBoolean("adbAuthorized", v).apply()

    // ---- service state ------------------------------------------------------

    var enabled: Boolean
        get() = p.getBoolean("enabled", false)
        set(v) = p.edit().putBoolean("enabled", v).apply()

    /** Last time the running service reported in; used to date a kill. */
    var lastHeartbeat: Long
        get() = p.getLong("lastHeartbeat", 0L)
        set(v) = p.edit().putLong("lastHeartbeat", v).apply()

    /** True only after a requested stop. False while [enabled] with no
     *  service running means the system killed it. */
    var cleanStop: Boolean
        get() = p.getBoolean("cleanStop", true)
        set(v) = p.edit().putBoolean("cleanStop", v).apply()

    /** Heartbeat of the last kill written to the event log, so that each
     *  kill is reported once. */
    var killReported: Long
        get() = p.getLong("killReported", 0L)
        set(v) = p.edit().putLong("killReported", v).apply()

    var startedAt: Long
        get() = p.getLong("startedAt", 0L)
        set(v) = p.edit().putLong("startedAt", v).apply()

    /** Whether notification permission has been requested. */
    var notifPermAsked: Boolean
        get() = p.getBoolean("notifPermAsked", false)
        set(v) = p.edit().putBoolean("notifPermAsked", v).apply()
}
