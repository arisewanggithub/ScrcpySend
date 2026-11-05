package dev.screencopy.sender

import android.net.ConnectivityManager
import android.net.InetAddresses
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in HELLO probes only; no session request, projection consent, media capture or settings writes. */
@RunWith(AndroidJUnit4::class)
class ReceiverNetworkProbeDeviceTest {
    @Test fun compareProductionHelloWithAndWithoutDiscoveryMulticastLock() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val peer = arguments.getString("probePeer")
        assumeTrue("Requires an explicitly supplied receiver IP", peer != null)
        val address = checkNotNull(peer)
        check(InetAddresses.isNumericAddress(address)) { "Probe requires a numeric IP" }
        val port = arguments.getString("probePort")?.toInt() ?: 27184
        check(port in 1..65535)
        val context = instrumentation.targetContext
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi.createMulticastLock("receiver-hello-diagnostic").apply { setReferenceCounted(false) }
        val results = JSONArray()
        fun probe(stage: String) {
            val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            val event = JSONObject().put("stage", stage).put("uid", android.os.Process.myUid())
                .put("multicastHeld", lock.isHeld).put("peer", address).put("port", port)
                .put("interactive", context.getSystemService(PowerManager::class.java).isInteractive)
                .put("wifi", capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
                .put("vpn", capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
            val started = System.nanoTime()
            try {
                ReceiverLink(ReceiverTarget(address, port, "Diagnostic HELLO")).use { it.connect(4000) }
                event.put("connected", true)
            } catch (e: Exception) {
                event.put("connected", false).put("exception", e.javaClass.simpleName).put("message", e.message)
            }
            event.put("elapsedMs", (System.nanoTime() - started) / 1_000_000)
            results.put(event)
        }
        try {
            probe("before_multicast_lock")
            lock.acquire()
            probe("with_multicast_lock")
            lock.release()
            probe("after_multicast_lock")
        } finally { if (lock.isHeld) lock.release() }
        instrumentation.addResults(Bundle().apply { putString("networkProbe", results.toString()) })
    }
}
