package dev.kdecbridge

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import tsbridge.Tsbridge

/**
 * Learns the computer's identity packet.
 *
 * Sends the computer a probe identity that advertises a local port. The
 * computer connects back and sends its own identity in cleartext before the
 * TLS upgrade; the first line is read and the connection closed.
 *
 * Transports:
 *  - tsnet: performed by the Go library over the tailnet.
 *  - direct: performed here over the LAN. Not possible through a relay,
 *    because neither the UDP probe nor the inbound connection crosses it.
 */
object IdentityDiscovery {

    private const val PROBE_FIRST = 1725
    private const val PROBE_LAST = 1738

    /**
     * Random 32-character hex id. kdeconnect-kde validates identity packets
     * against `DEVICE_ID_REGEX("^[a-zA-Z0-9_-]{32,38}$")` and silently drops
     * packets that fail, so a shorter id would never get a response.
     */
    private fun probeDeviceId(): String =
        java.util.UUID.randomUUID().toString().replace("-", "")

    /** Learns, stores and returns the identity for [cfg].host, or throws.
     *
     *  The computer accepts at most one connection per second from an
     *  address, across TCP and UDP, so a probe that coincides with another
     *  connection is dropped. The probe is resent until the timeout. */
    fun learnAndStore(cfg: Config, timeoutSec: Int = 20): String {
        val host = cfg.host
        require(host.isNotBlank()) { "no computer address configured" }
        val raw = when (cfg.mode) {
            Config.MODE_TSNET -> Tsbridge.discoverIdentity(host, timeoutSec.toLong())
            else -> discoverDirect(host, timeoutSec)
        }
        val body = bodyFrom(raw)
        cfg.storeIdentity(host, body)
        return body
    }

    /** Extracts the identity body, dropping fields that describe the
     *  connection rather than the device. */
    fun bodyFrom(rawLine: String): String {
        val body = JSONObject(rawLine).getJSONObject("body")
        body.remove("tcpPort")
        body.remove("targetDeviceId")
        body.remove("targetProtocolVersion")
        require(body.has("deviceId") && body.has("deviceName")) { "not an identity packet" }
        return body.toString()
    }

    /** Direct-mode discovery over the LAN. */
    private fun discoverDirect(host: String, timeoutSec: Int): String {
        var server: ServerSocket? = null
        var port = 0
        for (p in PROBE_FIRST..PROBE_LAST) {
            val s = ServerSocket()
            try {
                s.reuseAddress = true
                s.bind(InetSocketAddress(p))
                server = s; port = p
                break
            } catch (_: Exception) {
                runCatching { s.close() }
            }
        }
        val ln = server ?: throw IllegalStateException("no free port for discovery")
        try {
            val decoy = JSONObject()
                .put("id", System.currentTimeMillis())
                .put("type", "kdeconnect.identity")
                .put("body", JSONObject()
                    .put("deviceId", probeDeviceId())
                    .put("deviceName", "kdec-bridge-probe")
                    .put("deviceType", "phone")
                    .put("protocolVersion", 8)
                    .put("tcpPort", port)
                    .put("incomingCapabilities", org.json.JSONArray())
                    .put("outgoingCapabilities", org.json.JSONArray()))
            val bytes = (decoy.toString() + "\n").toByteArray(Charsets.UTF_8)
            DatagramSocket().use { u ->
                u.send(DatagramPacket(bytes, bytes.size,
                    InetAddress.getByName(host), Config.KDEC_UDP_PORT))
            }

            // Resend on each accept timeout; see the rate limit noted above.
            ln.soTimeout = 3000
            var waited = 0
            var c: java.net.Socket? = null
            while (c == null) {
                c = try {
                    ln.accept()
                } catch (_: SocketTimeoutException) {
                    waited += 3
                    if (waited >= timeoutSec) {
                        throw IllegalStateException("no dial-back from $host within ${timeoutSec}s")
                    }
                    DatagramSocket().use { u ->
                        u.send(DatagramPacket(bytes, bytes.size,
                            InetAddress.getByName(host), Config.KDEC_UDP_PORT))
                    }
                    null
                }
            }
            c!!.use {
                it.soTimeout = timeoutSec * 1000
                val line = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8))
                    .readLine() ?: throw IllegalStateException("empty dial-back")
                return line
            }
        } finally {
            runCatching { ln.close() }
        }
    }
}
