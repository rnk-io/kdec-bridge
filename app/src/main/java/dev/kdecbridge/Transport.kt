package dev.kdecbridge

import java.net.InetSocketAddress
import java.net.Socket

/**
 * How the bridge reaches the computer in direct mode. tsnet mode connects
 * from the Go library instead.
 */
interface Transport {
    val name: String
    /** Opens a connection to [port] on the computer. */
    fun connect(port: Int): Socket
    fun shutdown() {}
}

/** Plain TCP to a reachable host: LAN address, relay, or any routable IP. */
class DirectTcpTransport(private val host: String) : Transport {
    override val name: String get() = host

    override fun connect(port: Int): Socket {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        s.tcpNoDelay = true
        s.keepAlive = true
        return s
    }

    private companion object { const val CONNECT_TIMEOUT_MS = 8000 }
}
