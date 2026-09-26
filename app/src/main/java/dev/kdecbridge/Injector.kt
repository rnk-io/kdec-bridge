package dev.kdecbridge

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Sends the computer's identity packet to KDE Connect's UDP port on loopback,
 * advertising [bridgePort] as the computer's TCP port.
 *
 * KDE Connect accepts a loopback source (NetworkHelper.isPrivateAddress) and
 * connects to 127.0.0.1:[bridgePort], where the bridge is listening. Loopback
 * is the only address KDE Connect can be pointed at without a VpnService.
 *
 * Stateless: the identity and port are passed on every call, so a newly
 * learned identity or a fallback port applies from the next injection.
 */
object Injector {

    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

    fun inject(identityBody: String, bridgePort: Int) {
        val body = JSONObject(identityBody).put("tcpPort", bridgePort)
        val packet = JSONObject()
            .put("id", System.currentTimeMillis())
            .put("type", "kdeconnect.identity")
            .put("body", body)
        val bytes = (packet.toString() + "\n").toByteArray(Charsets.UTF_8)
        DatagramSocket().use { sock ->
            sock.send(DatagramPacket(bytes, bytes.size, loopback, Config.KDEC_UDP_PORT))
        }
    }

    /** Device name from an identity body, for the notification and UI. */
    fun deviceName(identityBody: String): String =
        runCatching { JSONObject(identityBody).getString("deviceName") }.getOrDefault("computer")

    fun deviceId(identityBody: String): String =
        runCatching { JSONObject(identityBody).getString("deviceId") }.getOrDefault("?")
}
