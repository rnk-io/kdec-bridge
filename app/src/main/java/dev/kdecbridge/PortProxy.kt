package dev.kdecbridge

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger

/**
 * Listens on 127.0.0.1 and forwards each connection to [remotePort] on the
 * computer through [transport]. Used in direct mode; tsnet mode forwards in Go.
 *
 * Bytes are forwarded unmodified. KDE Connect's identity exchange and TLS
 * handshake run end to end, so certificate pinning is unaffected and the
 * bridge cannot read the traffic.
 *
 * [bind] tries [preferredPort] first, then each port up to [lastPort]. The
 * port actually bound is [boundPort].
 */
class PortProxy(
    private val preferredPort: Int,
    private val lastPort: Int,
    private val remotePort: Int,
    private val transport: Transport,
    private val pool: ExecutorService,
    private val log: (String) -> Unit,
    private val onChange: (() -> Unit)? = null
) {
    @Volatile private var running = false
    private var server: ServerSocket? = null

    /** Port actually listening on, 0 until [bind] succeeds. */
    @Volatile var boundPort: Int = 0
        private set

    /** Connections currently forwarded. Counted only once the upstream
     *  connection is established, so a failed attempt never reads as a live
     *  link. */
    val active = AtomicInteger(0)

    /** Binds synchronously, so the caller knows the real port before
     *  advertising it to KDE Connect. Returns false if no port in range is
     *  free. */
    fun bind(): Boolean {
        for (port in preferredPort..lastPort) {
            val s = ServerSocket()
            try {
                s.reuseAddress = true
                s.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
                server = s
                boundPort = port
                return true
            } catch (_: Exception) {
                runCatching { s.close() }
            }
        }
        return false
    }

    fun start() {
        val s = server ?: return
        running = true
        Thread({ acceptLoop(s) }, "proxy-$boundPort").start()
    }

    private fun acceptLoop(s: ServerSocket) {
        try {
            while (running) {
                val client = s.accept()
                pool.execute { handle(client) }
            }
        } catch (e: Exception) {
            if (running) log("listen :$boundPort ended - ${e.message}")
        }
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        var counted = false
        try {
            client.tcpNoDelay = true
            upstream = transport.connect(remotePort)
            active.incrementAndGet()
            counted = true
            onChange?.invoke()
            log("linked :$boundPort -> ${transport.name}:$remotePort")
            val up = upstream
            Thread { pipe(up, client) }.start()
            pipe(client, up)
            // The reverse direction is not joined: if the far end dies, that
            // thread stays blocked on a socket the near end has not closed.
            // The finally block closes both sockets, which unblocks it.
        } catch (e: Exception) {
            log("proxy :$boundPort - ${e.message}")
        } finally {
            closeQuietly(client)
            upstream?.let { closeQuietly(it) }
            if (counted) {
                active.decrementAndGet()
                onChange?.invoke()
            }
        }
    }

    private fun pipe(from: Socket, to: Socket) {
        val buf = ByteArray(16 * 1024)
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (_: Exception) {
            // Peer closed or link dropped; handle() closes both sockets.
        } finally {
            runCatching { to.shutdownOutput() }
        }
    }

    private fun closeQuietly(s: Socket) { runCatching { s.close() } }

    fun stop() {
        running = false
        runCatching { server?.close() }
    }
}
