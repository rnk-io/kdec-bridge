package dev.kdecbridge

import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface
import java.util.Collections

/**
 * Supplies network interfaces to tsnet.
 *
 * Go's net.Interfaces() reads the netlink RIB, which Android denies to apps
 * ("netlinkrib: permission denied"). java.net.NetworkInterface is not
 * restricted, so interfaces are enumerated here and passed to Go as JSON.
 */
object AndroidNetInfo : tsbridge.NetInfo {

    override fun interfaces(): String {
        val arr = JSONArray()
        val ifaces = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
        }.getOrDefault(emptyList())

        for (ni in ifaces) {
            val o = JSONObject()
            o.put("name", ni.name)
            o.put("index", runCatching { ni.index }.getOrDefault(0))
            o.put("mtu", runCatching { ni.mtu }.getOrDefault(1500))
            o.put("up", runCatching { ni.isUp }.getOrDefault(false))
            o.put("loopback", runCatching { ni.isLoopback }.getOrDefault(false))
            o.put("p2p", runCatching { ni.isPointToPoint }.getOrDefault(false))
            o.put("multicast", runCatching { ni.supportsMulticast() }.getOrDefault(false))

            val addrs = JSONArray()
            runCatching {
                for (ia in ni.interfaceAddresses) {
                    // Strip the scope suffix from link-local IPv6 (fe80::1%wlan0).
                    val host = ia.address?.hostAddress?.substringBefore('%') ?: continue
                    addrs.put("$host/${ia.networkPrefixLength}")
                }
            }
            o.put("addrs", addrs)
            arr.put(o)
        }
        return arr.toString()
    }
}
