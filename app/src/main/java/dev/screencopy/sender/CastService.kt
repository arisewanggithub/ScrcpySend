package dev.screencopy.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import java.lang.ref.WeakReference

class CastService : Service() {
    @Volatile private var session: SenderSession? = null
    private var captureLease: CaptureAccess.Lease? = null
    private val casting = ReceiverCasting.shared
    private val state = CastingServiceState(casting)
    private val main = Handler(android.os.Looper.getMainLooper())
    private var foreground = false
    private var destroying = false
    private val observer: () -> Unit = {
        main.post {
            if (foreground && !destroying && state.request != null) updateNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("casting", getString(R.string.casting_channel), NotificationManager.IMPORTANCE_LOW))
        ShizukuSetup.shared.onConnectionLost = CastService::stopForReadiness
        casting.observe(observer)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { if (session == null) stopSelfResult(startId); return START_NOT_STICKY }
        val token = intent.getLongExtra("request", -1)
        if (intent.action == "stop") {
            if (state.owns(token)) {
                casting.cancelSizeSwitch(token)
                casting.requestStop(token); session?.stop()
            } else if (session == null && casting.snapshot().active?.request?.token == token) {
                // Stop arrived before the queued foreground start: no capture was ever owned.
                if (casting.complete(token)) ShizukuSetup.shared.releaseIfUnobserved()
                stopSelfResult(startId)
            }
            return START_NOT_STICKY
        }
        if (intent.action == SizeNotificationAction.ACTION) {
            val version = intent.getLongExtra("version", -1)
            val desired = intent.getBooleanExtra("fullScreen", false)
            if (!destroying && state.owns(token) && session != null &&
                SizeNotificationAction.valid(intent, token, version, desired)) {
                val result = casting.requestSizeSwitch(token, version, desired,
                    preflight = ::preflightSizeSwitch)
                when (result) {
                    ReceiverCasting.SizeAction.Restart -> {
                        val stopped = session?.stopForSizeSwitch() == true
                        if (!stopped) {
                            casting.cancelSizeSwitch(token)
                            // A concurrent terminal cause owns cleanup; never reopen capture.
                            session?.stop()
                        }
                    }
                    ReceiverCasting.SizeAction.Ignored ->
                        android.util.Log.i("ScreenCopySender", "size_action=ignored_stale_busy_or_safety")
                    else -> Unit
                }
                updateNotification()
            } else android.util.Log.i("ScreenCopySender", "size_action=ignored_no_current_owned_session")
            if (session == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (intent.action != "start") {
            if (session == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (session != null) return START_NOT_STICKY
        startOwned(token, startId)
        return START_NOT_STICKY
    }
    private fun startOwned(token: Long, startId: Int? = null) {
        val lease = state.adopt(token)
        if (lease == null) {
            if (casting.snapshot().active?.request?.token == token) casting.complete(token)
            endForeground(startId)
            return
        }
        val request = lease.request
        try {
            val ready = ShizukuSetup.shared.requireReady()
            captureLease = CaptureAccess.shared.acquire("casting")
            val notification = notification()
            if (foreground) getSystemService(NotificationManager::class.java).notify(1, notification)
            else if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(1, notification)
            foreground = true
            val settings = Settings.from(Wire.parseJson(request.settings.toByteArray()))
            lateinit var current: SenderSession
            current = SenderSession(applicationContext, settings, request.target,
                { state.progress(lease, it) }) {
                main.post {
                    if (state.owns(lease)) {
                        session = null
                        if (runningSession?.token == token) runningSession = null
                        var failure: String? = null
                        try { captureLease?.close() } catch (e: Exception) {
                            failure = "Size change cleanup failed: ${e.message ?: e.javaClass.simpleName}"
                        }
                        captureLease = null
                        val next = state.finishSizeSwitch(lease, !destroying && current.cleanSizeSwitch, failure)
                        if (next != null) {
                            // Same running foreground service, pinned peer, one fresh negotiation.
                            // SenderSession still starts capture only after SESSION_ACCEPT.
                            startOwned(next.token)
                        } else {
                            ShizukuSetup.shared.releaseIfUnobserved()
                            endForeground()
                        }
                    }
                }
            }
            session = current
            runningSession = RunningSession(token, ready.generation, WeakReference(current))
            Thread(current, "sender-session").start()
        } catch (e: Exception) {
            if (state.owns(lease)) {
                session = null
                if (runningSession?.token == token) runningSession = null
                try { captureLease?.close() } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
                captureLease = null
                state.finish(lease, "Could not start sharing: ${e.message ?: e.javaClass.simpleName}")
                ShizukuSetup.shared.releaseIfUnobserved()
                endForeground(startId)
            }
        }
    }
    private fun endForeground(startId: Int? = null) {
        foreground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (startId == null) stopSelf() else stopSelfResult(startId)
    }
    /** Metadata queries against the existing owned privileged scope; no deploy/launch/cleanup. */
    private fun preflightSizeSwitch(settings: Settings) {
        val ready = ShizukuSetup.shared.requireReady()
        val owned = checkNotNull(runningSession) { "Capture ownership unavailable" }
        check(state.owns(owned.token) && owned.generation == ready.generation &&
            owned.session.get() === session && captureLease != null) { "Capture ownership changed" }
        val proof = ready.capabilities
        val resolved = HardwareEncoders.fromReport(proof).listedBy(proof).resolve(settings)
        Bitrates.validate(resolved, CodecBitrates.fromReport(proof))
        DisplayInventory.fromReport(proof).validate(resolved)
        val scope = getSharedPreferences("capture-ownership", MODE_PRIVATE).getString("scid", null)
        check(scope != null && scope.matches(Regex("[0-9a-f]{8}"))) { "Active capture scope unavailable" }
        ShizukuSetup.shared.bridge().validateVideo(scope, resolved)
    }
    private fun notification(): Notification {
        return CastingNotification.create(this, casting.snapshot())
    }
    private fun updateNotification() {
        if (casting.snapshot().active != null)
            getSystemService(NotificationManager::class.java).notify(1, notification())
    }
    override fun onDestroy() {
        destroying = true
        casting.removeObserver(observer)
        state.request?.let { casting.cancelSizeSwitch(it.token) }
        session?.stop()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        val active get() = ReceiverCasting.shared.snapshot().active != null
        private data class RunningSession(val token: Long, val generation: Long, val session: WeakReference<SenderSession>)
        @Volatile private var runningSession: RunningSession? = null
        internal fun stopForReadiness(generation: Long) {
            val owned = runningSession
            if (owned?.generation == generation) {
                ReceiverCasting.shared.requestStop(owned.token)
                owned.session.get()?.stop()
            }
        }
    }
}

/** Notification construction is testable without starting a service or any capture. */
internal object CastingNotification {
    fun create(context: android.content.Context, snapshot: ReceiverCasting.Snapshot): Notification {
        val active = checkNotNull(snapshot.active)
        val request = active.request
        val settings = Settings.from(Wire.parseJson(active.appliedRequest.settings.toByteArray()))
        val stop = android.app.PendingIntent.getService(context, 0,
            Intent(context, CastService::class.java).setAction("stop")
                .setData(android.net.Uri.parse("screencopy://stop/${request.token}"))
                .putExtra("request", request.token),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        val open = android.app.PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE)
        val label = if (snapshot.size.applied) "Full screen" else "Saved size/crop"
        val geometry = "max ${if (settings.maxSize == 0) "unlimited" else settings.maxSize.toString() + " px"}; " +
            if (settings.cropWidth == 0) "no crop" else
                "crop ${settings.cropWidth} × ${settings.cropHeight} at ${settings.cropX},${settings.cropY}"
        val phase = when (active.phase) {
            ReceiverCasting.Phase.CONNECTING -> "Connecting"
            ReceiverCasting.Phase.PLAYING -> "Playing"
            ReceiverCasting.Phase.STOPPING -> if (snapshot.size.busy) "Applying size..." else "Stopping"
        }
        val note = when {
            snapshot.size.busy -> "${if (snapshot.size.requested) "Full screen" else "Saved size/crop"} requested"
            snapshot.size.message == "Size applied" -> "Size applied"
            snapshot.size.message == "Size/crop already match; no restart needed" -> snapshot.size.message
            snapshot.size.message.startsWith("Size change cancelled") -> "Size change cancelled; no automatic restart"
            snapshot.size.message.startsWith("Size change unavailable") -> "Size change unavailable; see home for details"
            else -> snapshot.size.message
        }
        val text = "$label — $geometry\n$phase\n$note".trim()
        val builder = Notification.Builder(context, "casting").setContentTitle(context.getString(R.string.casting_notification))
            .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_menu_share).setOnlyAlertOnce(true)
            .setOngoing(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
        if (snapshot.size.enabled) {
            val desired = !snapshot.size.applied
            val action = android.app.PendingIntent.getService(context, 0,
                SizeNotificationAction.intent(context, request.token, snapshot.size.version, desired),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(Notification.Action.Builder(null,
                if (desired) "Full screen" else "Saved size/crop", action).build())
        }
        return builder.build()
    }
}
