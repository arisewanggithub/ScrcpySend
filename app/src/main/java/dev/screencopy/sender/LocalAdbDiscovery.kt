package dev.screencopy.sender

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Uses Android NSD, but never connects to another phone's discovered adbd. */
internal class LocalAdbDiscovery(
    private val nsd: NsdManager,
    private val localAddresses: Set<InetAddress>,
    private val kind: Kind
) : Closeable {
    enum class Kind(val serviceType: String) {
        CONNECT("_adb-tls-connect._tcp."), PAIRING("_adb-tls-pairing._tcp.")
    }
    data class Endpoint(val address: InetAddress, val port: Int)
    private val lock = Any()
    private val completed = CountDownLatch(1)
    private val queued = ArrayDeque<NsdServiceInfo>()
    private val names = mutableSetOf<String>()
    private var started = false
    private var registered = false
    private var stopRequested = false
    private var closed = false
    private var resolving = false
    private var endpoint: Endpoint? = null
    private var failure: IOException? = null

    private val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            val stop = synchronized(lock) {
                registered = true
                closed
            }
            if (stop) stopDiscovery()?.let { android.util.Log.e("ScreenCopyADB", it.message.orEmpty()) }
        }
        override fun onDiscoveryStopped(serviceType: String) {
            synchronized(lock) {
                registered = false
                if (!closed && endpoint == null && failure == null)
                    failure = IOException("ADB discovery stopped unexpectedly. Retry or enter the port manually.")
            }
            completed.countDown()
        }
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) =
            fail(IOException("Cannot start Android ADB discovery ($errorCode). Use Retry or manual port entry."))
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            val error = IOException("Cannot stop Android ADB discovery ($errorCode). Retry after checking Wireless debugging.")
            if (synchronized(lock) { closed }) android.util.Log.e("ScreenCopyADB", error.message.orEmpty())
            else fail(error)
        }
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            var next: NsdServiceInfo? = null
            synchronized(lock) {
                if (closed || endpoint != null || failure != null || !matches(serviceInfo.serviceType, kind)) return
                if (!names.add(serviceInfo.serviceName)) return
                if (names.size > 32) {
                    failure = IOException("Too many ADB advertisements. Use manual local port entry.")
                    completed.countDown()
                    return
                }
                queued.add(serviceInfo)
                if (!resolving) { resolving = true; next = queued.removeFirst() }
            }
            next?.let(::resolve)
        }
        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            synchronized(lock) {
                queued.removeAll { it.serviceName == serviceInfo.serviceName }
                names.remove(serviceInfo.serviceName)
            }
        }
    }
    private fun resolve(service: NsdServiceInfo) {
        try {
            nsd.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    // Another advertised device may be resolvable; the overall discovery is bounded.
                    nextResolution()
                }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host
                    synchronized(lock) {
                        if (!closed && endpoint == null && failure == null && matches(serviceInfo.serviceType, kind) &&
                            host != null && isLocalEndpoint(host, serviceInfo.port, localAddresses)) {
                            endpoint = Endpoint(host, serviceInfo.port)
                            completed.countDown()
                        }
                    }
                    nextResolution()
                }
            })
        } catch (e: RuntimeException) {
            fail(IOException("Android could not resolve the local ADB advertisement. Retry or use a manual port.", e))
        }
    }
    private fun nextResolution() {
        val next = synchronized(lock) {
            if (closed || endpoint != null || failure != null || queued.isEmpty()) { resolving = false; null }
            else queued.removeFirst()
        }
        next?.let(::resolve)
    }
    private fun fail(error: IOException) {
        synchronized(lock) { if (failure == null && !closed) failure = error }
        completed.countDown()
    }

    fun discover(timeoutMillis: Long = 6000): Endpoint {
        require(timeoutMillis > 0) { "Discovery deadline must be positive" }
        synchronized(lock) {
            if (closed) throw IOException("ADB discovery cancelled")
            check(!started) { "Discovery already started" }
            started = true
        }
        try {
            try { nsd.discoverServices(kind.serviceType, NsdManager.PROTOCOL_DNS_SD, listener) }
            catch (e: RuntimeException) { throw IOException("Android ADB discovery unavailable. Use manual port entry.", e) }
            if (!completed.await(timeoutMillis, TimeUnit.MILLISECONDS))
                throw SocketTimeoutException(if (kind == Kind.CONNECT)
                    "No local wireless ADB connection advertisement. Enable Wireless debugging/Wi-Fi, Retry, or enter its connection port manually."
                    else "No local pairing advertisement. Keep the Android pairing dialog open or enter its port manually.")
            synchronized(lock) {
                failure?.let { throw it }
                if (closed) throw IOException("ADB discovery cancelled")
                return endpoint ?: throw IOException("Local ADB advertisement unavailable; use manual port entry.")
            }
        } finally { close() }
    }
    private fun stopDiscovery(): IOException? {
        synchronized(lock) {
            if (!registered || stopRequested) return null
            stopRequested = true
        }
        try { nsd.stopServiceDiscovery(listener) }
        catch (e: RuntimeException) {
            return IOException("Cannot release Android ADB discovery registration", e)
        }
        return null
    }
    override fun close() {
        synchronized(lock) { closed = true; queued.clear() }
        completed.countDown()
        stopDiscovery()?.let { throw it }
    }

    companion object {
        fun create(context: Context, kind: Kind): LocalAdbDiscovery {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: throw IOException("No local network interfaces; use manual port entry")
            val addresses = Collections.list(interfaces).flatMap { Collections.list(it.inetAddresses) }.toSet()
            return LocalAdbDiscovery(context.getSystemService(NsdManager::class.java), addresses, kind)
        }
        internal fun matches(serviceType: String?, kind: Kind): Boolean =
            serviceType?.trimEnd('.') == kind.serviceType.trimEnd('.')
        internal fun isLocalEndpoint(host: InetAddress, port: Int, addresses: Set<InetAddress>): Boolean =
            port in 1..65535 && !host.isAnyLocalAddress && !host.isMulticastAddress && host in addresses
    }
}
