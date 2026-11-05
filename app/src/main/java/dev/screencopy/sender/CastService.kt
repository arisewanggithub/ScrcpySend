package dev.screencopy.sender

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class CastService : Service() {
    @Volatile private var session: SenderSession? = null
    private var owner: ProjectionOwner? = null
    private val casting = ReceiverCasting.shared
    private val state = CastingServiceState(casting)
    private val main = Handler(Looper.getMainLooper())
    private var foreground = false
    private var destroying = false
    private val observer: () -> Unit = {
        main.post {
            if (foreground && !destroying && state.request != null)
                getSystemService(NotificationManager::class.java).notify(1, CastingNotification.create(this, casting.snapshot()))
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("casting", getString(R.string.casting_channel), NotificationManager.IMPORTANCE_LOW))
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
                casting.complete(token); stopSelfResult(startId)
            }
            return START_NOT_STICKY
        }
        if (intent.action == SizeNotificationAction.ACTION) {
            val current = session
            if (state.owns(token) && current != null) {
                val version = intent.getLongExtra("version", -1)
                val desired = intent.getBooleanExtra("fullScreen", false)
                if (SizeNotificationAction.valid(intent, token, version, desired)) {
                    val result = casting.requestSizeSwitch(token, version, desired) {
                        current.validateSizeSwitch(it)
                    }
                    if (result == ReceiverCasting.SizeAction.Restart && !current.stopForSizeSwitch()) {
                        casting.cancelSizeSwitch(token); current.stop()
                    }
                }
            }
            return START_NOT_STICKY
        }
        if (intent.action != "start") { if (session == null) stopSelfResult(startId); return START_NOT_STICKY }
        if (session != null) return START_NOT_STICKY
        val lease = state.adopt(token)
        if (lease == null) { stopSelfResult(startId); return START_NOT_STICKY }
        try {
            val settings = Settings.from(Wire.parseJson(lease.request.settings.toByteArray()))
            @Suppress("DEPRECATION")
            val consent = intent.getParcelableExtra<Intent>("projection")
                ?: throw IllegalStateException("Screen-sharing consent is missing. Press Play again.")
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                if (settings.audio && settings.audioSource == "mic") ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            startForeground(1, CastingNotification.create(this, casting.snapshot()), type)
            foreground = true
            val projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, consent)
                ?: throw IllegalStateException("Android rejected screen-sharing consent. Press Play again.")
            owner = ProjectionOwner(projection, resources.configuration.densityDpi, PlaybackMute.create(this)) { error ->
                session?.captureFailed(error)
            }
            startOwned(lease)
        } catch (e: Exception) {
            state.finish(lease, "Could not start sharing: ${e.message ?: e.javaClass.simpleName}")
            finishProjection()
        }
        return START_NOT_STICKY
    }

    private fun startOwned(lease: ReceiverCasting.ServiceLease) {
        val settings = Settings.from(Wire.parseJson(lease.request.settings.toByteArray()))
        lateinit var current: SenderSession
        current = SenderSession(applicationContext, settings, lease.request.target, { state.progress(lease, it) }) {
            main.post {
                if (state.owns(lease)) {
                    session = null
                    val next = state.finishSizeSwitch(lease, !destroying && current.cleanSizeSwitch)
                    if (next != null) {
                        val nextLease = state.adopt(next.token)
                        if (nextLease != null) try { startOwned(nextLease) } catch (e: Exception) {
                            state.finish(nextLease, "Could not restart sharing: ${e.message ?: e.javaClass.simpleName}")
                            finishProjection()
                        } else finishProjection()
                    } else finishProjection()
                }
            }
        }
        current.projectionOwner = checkNotNull(owner)
        session = current
        Thread(current, "sender-session").start()
    }

    private fun finishProjection() {
        try { owner?.close() } catch (e: Exception) {
            android.util.Log.e("ScreenCopySender", "Projection cleanup failed", e)
        }
        owner = null
        foreground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        destroying = true
        casting.removeObserver(observer)
        state.request?.let { casting.cancelSizeSwitch(it.token) }
        session?.stop()
        // Token revocation immediately stops display/audio production even during bounded worker cleanup.
        try { owner?.close() } catch (e: Exception) { android.util.Log.e("ScreenCopySender", "Projection shutdown failed", e) }
        owner = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        val active get() = ReceiverCasting.shared.snapshot().active != null
    }
}

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
        val open = android.app.PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE)
        val label = if (snapshot.size.applied) "Full screen" else "Saved size/crop"
        val geometry = "max ${if (settings.maxSize == 0) "unlimited" else settings.maxSize.toString() + " px"}; " +
            if (settings.cropWidth == 0) "no crop" else "crop ${settings.cropWidth} × ${settings.cropHeight} at ${settings.cropX},${settings.cropY}"
        val phase = when (active.phase) {
            ReceiverCasting.Phase.CONNECTING -> "Connecting"
            ReceiverCasting.Phase.PLAYING -> "Playing"
            ReceiverCasting.Phase.STOPPING -> if (snapshot.size.busy) "Applying size..." else "Stopping"
        }
        val note = snapshot.size.message
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
            builder.addAction(Notification.Action.Builder(null, if (desired) "Full screen" else "Saved size/crop", action).build())
        }
        return builder.build()
    }
}
