package dev.kdecbridge

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Finds this phone's Wireless debugging services through mDNS. adbd picks
 * random ports for them and does not expose the ports to apps any other way.
 */
object AdbServices {

    /** Wireless debugging connections (TLS). Advertised while Wireless debugging is on. */
    const val CONNECT = "_adb-tls-connect._tcp"
    /** Pairing. Advertised while Settings shows "Pair device with pairing code". */
    const val PAIRING = "_adb-tls-pairing._tcp"

    class Service(val address: InetAddress, val port: Int)

    /**
     * Returns the service of [type] advertised by this phone, or null after
     * [timeoutMs]. [abort] is checked a few times per second and ends the
     * search early when it returns true.
     */
    fun find(ctx: Context, type: String, timeoutMs: Long, abort: () -> Boolean = { false }): Service? {
        val nsd = ctx.getSystemService(NsdManager::class.java) ?: return null
        val found = LinkedBlockingQueue<NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) { found.add(info) }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (abort()) return null
                val info = found.poll(250, TimeUnit.MILLISECONDS) ?: continue
                resolveLocal(nsd, info)?.let { return it }
            }
            return null
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    /**
     * Runs [block] against [svc] on loopback, and on the advertised address if
     * adbd does not accept connections on loopback.
     */
    fun <T> onDevice(svc: Service, block: (host: String, port: Int) -> T): T =
        try {
            block("127.0.0.1", svc.port)
        } catch (e: Exception) {
            if (svc.address.isLoopbackAddress) throw e
            block(svc.address.hostAddress ?: throw e, svc.port)
        }

    /** Resolves [info] and returns it only if it is on this phone. Other
     *  devices on the network advertise the same service types. */
    @Suppress("DEPRECATION") // resolveService and host remain available and need no API 34 gate
    private fun resolveLocal(nsd: NsdManager, info: NsdServiceInfo): Service? {
        val done = CountDownLatch(1)
        var resolved: NsdServiceInfo? = null
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { done.countDown() }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                resolved = serviceInfo
                done.countDown()
            }
        })
        done.await(3, TimeUnit.SECONDS)
        val r = resolved ?: return null
        val addr = r.host ?: return null
        return if (isLocal(addr)) Service(addr, r.port) else null
    }

    private fun isLocal(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress) return true
        return runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .any { it == addr }
        }.getOrDefault(false)
    }
}
