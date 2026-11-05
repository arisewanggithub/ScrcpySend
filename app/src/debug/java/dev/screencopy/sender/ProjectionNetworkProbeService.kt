package dev.screencopy.sender

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.InetAddresses
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import org.json.JSONArray
import org.json.JSONObject

/** Debug-only consented API activation test. Never creates a VirtualDisplay or an audio recorder. */
class ProjectionNetworkProbeService : Service() {
    @Volatile private var projection: MediaProjection? = null
    @Volatile private var cancelled = false
    private var worker: Thread? = null
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() { cancelled = true }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            cancelled = true
            stopProjection()
            stopSelf()
            return START_NOT_STICKY
        }
        if (worker != null) return START_NOT_STICKY
        try {
            val request = checkNotNull(intent)
            val peer = checkNotNull(request.getStringExtra("peer"))
            check(InetAddresses.isNumericAddress(peer))
            val port = request.getIntExtra("port", 27184)
            check(port in 1..65535)
            @Suppress("DEPRECATION")
            val consent = checkNotNull(request.getParcelableExtra<Intent>("projection"))
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("projection-network-probe",
                "Temporary projection diagnostic", NotificationManager.IMPORTANCE_LOW))
            val stop = android.app.PendingIntent.getService(this, 0,
                Intent(this, ProjectionNetworkProbeService::class.java).setAction("stop"),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(this, "projection-network-probe")
                .setContentTitle("Temporary projection network test")
                .setContentText("No screen or audio data is captured")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
            startForeground(7813, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            worker = Thread({
                val events = JSONArray()
                try {
                    events.put(ProjectionNetworkChecks.probe(this, peer, port, "foreground_before_projection"))
                    if (cancelled) throw InterruptedException("Diagnostic cancelled")
                    val active = getSystemService(MediaProjectionManager::class.java)
                        .getMediaProjection(Activity.RESULT_OK, consent)
                    checkNotNull(active) { "Android did not return a projection" }
                    synchronized(this) {
                        if (cancelled) { active.stop(); throw InterruptedException("Diagnostic cancelled") }
                        projection = active
                        active.registerCallback(callback, Handler(Looper.getMainLooper()))
                    }
                    events.put(ProjectionNetworkChecks.probe(this, peer, port, "projection_active"))
                    Thread.sleep(3000)
                    if (cancelled) throw InterruptedException("Projection revoked or diagnostic cancelled")
                    events.put(ProjectionNetworkChecks.probe(this, peer, port, "projection_active_after_3s"))
                    stopProjection()
                    events.put(ProjectionNetworkChecks.probe(this, peer, port, "projection_stopped"))
                } catch (e: Exception) {
                    ProjectionNetworkProbeState.failure = "${e.javaClass.simpleName}: ${e.message}"
                    Log.e("ProjectionNetworkProbe", "Diagnostic interrupted or failed", e)
                } finally {
                    try { stopProjection() } catch (e: Exception) {
                        ProjectionNetworkProbeState.failure = "Projection cleanup failed: ${e.message}"
                        Log.e("ProjectionNetworkProbe", "Diagnostic cleanup failed", e)
                    }
                    ProjectionNetworkProbeState.results = events.toString()
                    ProjectionNetworkProbeState.completed.countDown()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }, "projection-network-diagnostic").apply { start() }
        } catch (e: Exception) {
            ProjectionNetworkProbeState.failure = "${e.javaClass.simpleName}: ${e.message}"
            ProjectionNetworkProbeState.completed.countDown()
            Log.e("ProjectionNetworkProbe", "Cannot start diagnostic", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun stopProjection() {
        val current = synchronized(this) { projection.also { projection = null } }
        if (current != null) {
            try { current.unregisterCallback(callback) } finally { current.stop() }
        }
    }
    override fun onDestroy() {
        cancelled = true
        worker?.interrupt()
        try { stopProjection() } catch (e: Exception) { Log.e("ProjectionNetworkProbe", "Shutdown failed", e) }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}

internal object ProjectionNetworkProbeState {
    @Volatile var completed = CountDownLatch(1)
    @Volatile var results: String? = null
    @Volatile var failure: String? = null
    fun reset() { completed = CountDownLatch(1); results = null; failure = null }
}

internal object ProjectionNetworkChecks {
    fun probe(context: Context, peer: String, port: Int, stage: String): JSONObject {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork
        val capabilities = connectivity.getNetworkCapabilities(network)
        val result = JSONObject().put("stage", stage).put("uid", android.os.Process.myUid())
            .put("peer", peer).put("port", port).put("network", network?.toString())
            .put("interactive", context.getSystemService(PowerManager::class.java).isInteractive)
            .put("wifi", capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
            .put("vpn", capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
        var started = System.nanoTime()
        try {
            Socket().use {
                it.connect(InetSocketAddress(peer, port), 3000)
                result.put("tcpConnected", true).put("localAddress", it.localAddress.hostAddress)
            }
        } catch (e: Exception) {
            result.put("tcpConnected", false).put("tcpException", e.javaClass.simpleName).put("tcpMessage", e.message)
        }
        result.put("tcpElapsedMs", (System.nanoTime() - started) / 1_000_000)
        started = System.nanoTime()
        try {
            ReceiverLink(ReceiverTarget(peer, port, "Projection diagnostic HELLO")).use { it.connect(4000) }
            result.put("helloConnected", true)
        } catch (e: Exception) {
            result.put("helloConnected", false).put("helloException", e.javaClass.simpleName).put("helloMessage", e.message)
        }
        result.put("helloElapsedMs", (System.nanoTime() - started) / 1_000_000)
        Log.i("ProjectionNetworkProbe", result.toString())
        return result
    }
}
