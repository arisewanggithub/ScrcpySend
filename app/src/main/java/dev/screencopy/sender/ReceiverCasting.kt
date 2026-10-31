package dev.screencopy.sender

import java.util.concurrent.CopyOnWriteArrayList

internal data class ReceiverKey(val host: String, val port: Int) {
    companion object { fun of(target: ReceiverTarget) = ReceiverKey(target.host, target.port) }
}
internal object ReceiverMainScreen {
    fun listVisible(ready: Boolean, active: Boolean) = ready || active
    data class Effects(val settingsEnabled: Boolean, val keepScreenOn: Boolean)
    fun effects(ready: Boolean, visible: Boolean, active: Boolean) =
        Effects(settingsEnabled = ready && !active, keepScreenOn = visible && active)
}

/** In-process service/Activity source of truth; never restored from stale runtime preferences. */
internal class ReceiverCasting(private val clock: () -> Long = System::nanoTime) {
    enum class Phase { CONNECTING, PLAYING, STOPPING }
    data class Request(val token: Long, val target: ReceiverTarget, val settings: String,
                       val savedSettings: String = settings, val fullScreen: Boolean = false)
    class ServiceLease internal constructor(val request: Request)
    data class Active(val request: Request, val phase: Phase, val detail: String, val cleanupStarted: Boolean = false,
                      val appliedRequest: Request = request)
    data class Row(val target: ReceiverTarget, val label: String, val enabled: Boolean, val detail: String,
                   val active: Boolean, val requestToken: Long?, val revision: Long)
    data class SizeState(val requested: Boolean, val applied: Boolean, val busy: Boolean,
                         val version: Long, val enabled: Boolean, val message: String)
    data class Snapshot(val active: Active?, val error: String, val revision: Long, val size: SizeState)
    sealed class SizeAction {
        object Ignored : SizeAction()
        object Unchanged : SizeAction()
        object Restart : SizeAction()
    }
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var known = linkedMapOf<ReceiverKey, Discovery.Receiver>()
    private var discoveryVisible = emptyList<Pair<ReceiverTarget, String>>()
    private var active: Active? = null
    private var serviceOwner: ServiceLease? = null
    private var error = ""
    private var pendingError = ""
    // Avoid reusing an old notification's request identity after app process recovery.
    private var nextToken = (java.security.SecureRandom().nextLong() and Long.MAX_VALUE).coerceAtLeast(1)
    private var revision = 0L
    private var fullScreen = false
    private var sizeVersion = 0L
    private var pendingSize: Request? = null
    private var sizeMessage = ""

    fun observe(listener: () -> Unit) { listeners.add(listener); listener() }
    fun removeObserver(listener: () -> Unit) { listeners.remove(listener) }
    private fun publish() { listeners.forEach { it() } }
    fun snapshot(): Snapshot = synchronized(lock) {
        val current = active
        Snapshot(current, error, revision, SizeState(fullScreen, current?.appliedRequest?.fullScreen ?: fullScreen,
            pendingSize != null, sizeVersion, current == null ||
                serviceOwner != null && current.phase == Phase.PLAYING && pendingSize == null,
            sizeMessage))
    }
    fun setNextSizeOverride(value: Boolean) {
        synchronized(lock) {
            if (active != null || fullScreen == value) return
            fullScreen = value; sizeVersion++; sizeMessage = ""
        }
        publish()
    }
    /** One versioned user action, never an unbounded queue or discovery-based redirect. */
    fun requestSizeSwitch(token: Long, version: Long, desired: Boolean,
                          preflight: (Settings) -> Unit = {}): SizeAction {
        val result = synchronized(lock) {
            val current = active ?: return SizeAction.Ignored
            if (current.request.token != token || sizeVersion != version || serviceOwner == null ||
                current.phase != Phase.PLAYING || pendingSize != null) return SizeAction.Ignored
            if (desired == fullScreen) return SizeAction.Ignored
            val saved = Settings.from(Wire.parseJson(current.request.savedSettings.toByteArray()), validate = false)
            val effectiveSettings = try { HomeCaptureSettings.effective(saved, desired) }
            catch (e: Exception) {
                sizeMessage = "Size change unavailable: ${e.message ?: e.javaClass.simpleName}"
                sizeVersion++
                return@synchronized SizeAction.Ignored
            }
            val effective = effectiveSettings.request().toString()
            if (effective != current.request.settings) try { preflight(effectiveSettings) }
            catch (e: Exception) {
                sizeVersion++
                sizeMessage = "Size change unavailable: ${e.message ?: e.javaClass.simpleName}; capture unchanged"
                return@synchronized SizeAction.Ignored
            }
            fullScreen = desired; sizeVersion++
            if (effective == current.request.settings) {
                val unchanged = current.request.copy(fullScreen = desired)
                active = current.copy(request = unchanged, appliedRequest = unchanged)
                sizeMessage = "Size/crop already match; no restart needed"
                SizeAction.Unchanged
            } else {
                pendingSize = current.request.copy(settings = effective, fullScreen = desired)
                active = current.copy(phase = Phase.STOPPING, detail = "Applying size...")
                sizeMessage = "Applying size... ${if (desired) "Full screen" else "Saved size/crop"} requested"; revision++
                SizeAction.Restart
            }
        }
        publish()
        return result
    }
    fun cancelSizeSwitch(token: Long) {
        synchronized(lock) {
            if (active?.request?.token != token || pendingSize == null) return
            pendingSize = null; sizeVersion++; sizeMessage = "Size change cancelled; no automatic restart"
        }
        publish()
    }
    fun updateDiscovery(receivers: List<Discovery.Receiver>) {
        val changed = synchronized(lock) {
            known = linkedMapOf<ReceiverKey, Discovery.Receiver>().apply {
                receivers.take(64).forEach { put(ReceiverKey.of(it.target()), it) }
            }
            // Retain freshness, but compare against the last projection so quiet expiry still publishes.
            val visible = fresh().map { it.target() to it.state }
            (visible != discoveryVisible).also { discoveryVisible = visible }
        }
        if (changed) publish()
    }
    private fun fresh(): List<Discovery.Receiver> = known.values.filter { clock() - it.seen <= 6_000_000_000L }
    fun rows(ready: Boolean): List<Row> = synchronized(lock) {
        val owned = active
        val current = fresh().associateBy { ReceiverKey.of(it.target()) }.toMutableMap()
        val targets = current.values.map { it.target() }.toMutableList()
        if (owned != null) {
            val key = ReceiverKey.of(owned.request.target)
            val currentTarget = targets.firstOrNull { ReceiverKey.of(it) == key } ?: owned.request.target
            targets.removeAll { ReceiverKey.of(it) == key }
            targets.add(0, currentTarget)
        }
        targets.map { target ->
            val own = owned?.takeIf { ReceiverKey.of(it.request.target) == ReceiverKey.of(target) }
            val busy = current[ReceiverKey.of(target)]?.state == "busy"
            when {
                own != null -> Row(target, when (own.phase) {
                    Phase.CONNECTING -> "Connecting... (Stop)"
                    Phase.PLAYING -> "Stop"
                    Phase.STOPPING -> if (pendingSize != null) "Applying size... (Stop)" else "Stopping..."
                }, own.phase != Phase.STOPPING || pendingSize != null, own.detail, true, own.request.token, revision)
                busy -> Row(target, "Busy", false, "Receiver is busy", false, null, revision)
                owned != null -> Row(target, "Play", false, "Another receiver is active", false, null, revision)
                !ready -> Row(target, "Play", false, "Shizuku is unavailable", false, null, revision)
                else -> Row(target, "Play", true, "", false, null, revision)
            }
        }
    }
    fun reserve(key: ReceiverKey, settings: Settings, expectedRevision: Long? = null,
                applyRuntimeOverride: Boolean = false): Request {
        val request = synchronized(lock) {
            val effective = HomeCaptureSettings.effective(settings, applyRuntimeOverride && fullScreen)
            check(active == null) { "Wait for the current receiver to stop and finish cleanup" }
            check(expectedRevision == null || revision == expectedRevision) { "Sharing state changed; use the current receiver control" }
            val receiver = fresh().firstOrNull { ReceiverKey.of(it.target()) == key }
                ?: throw IllegalStateException("Receiver is offline. Open Receiver on the same Wi-Fi and retry discovery.")
            check(receiver.state == "available") { "Receiver is busy" }
            val selected = Request(nextToken, receiver.target(), effective.request().toString(),
                settings.request().toString(), applyRuntimeOverride && fullScreen)
            nextToken = if (nextToken == Long.MAX_VALUE) 1 else nextToken + 1
            revision++
            error = ""; pendingError = ""; pendingSize = null; sizeMessage = ""; sizeVersion++
            active = Active(selected, Phase.CONNECTING, "Connecting")
            selected
        }
        publish()
        return request
    }
    fun claim(token: Long): ServiceLease? = synchronized(lock) {
        if (serviceOwner != null) return null
        val request = active?.takeIf { it.request.token == token && it.phase == Phase.CONNECTING }?.request ?: return null
        ServiceLease(request).also { serviceOwner = it }
    }
    fun requestStop(token: Long): Request? {
        val request = synchronized(lock) {
            val current = active ?: return null
            if (current.request.token != token) return null
            val sizeSwitchPending = pendingSize != null
            if (sizeSwitchPending) {
                pendingSize = null; sizeVersion++; sizeMessage = "Size change cancelled; no automatic restart"
            }
            if (current.phase == Phase.STOPPING && !sizeSwitchPending) return null
            active = current.copy(phase = Phase.STOPPING, detail = "Stop requested")
            current.request
        }
        publish()
        return request
    }
    fun progress(token: Long, message: String) {
        synchronized(lock) {
            val current = active?.takeIf { it.request.token == token } ?: return
            when {
                message.startsWith("Error:") -> {
                    sizeVersion++
                    if (pendingError.isEmpty()) pendingError = message.removePrefix("Error:").trim()
                    active = current.copy(phase = Phase.STOPPING, detail = "Finishing cleanup", cleanupStarted = true)
                }
                message == "Stopping" -> {
                    sizeVersion++
                    active = current.copy(phase = Phase.STOPPING, detail = "Stopping", cleanupStarted = true)
                }
                message == "Streaming" && current.phase != Phase.STOPPING -> {
                    active = current.copy(phase = Phase.PLAYING, detail = "Playing", appliedRequest = current.request)
                    if (sizeMessage.startsWith("Applying size")) sizeMessage = "Size applied"
                }
                message.startsWith("Receiver warning:") -> active = current.copy(detail = message)
                current.phase == Phase.CONNECTING -> active = current.copy(detail = message)
            }
        }
        publish()
    }
    fun complete(token: Long, failure: String? = null): Boolean {
        synchronized(lock) {
            if (active?.request?.token != token || serviceOwner != null) return false
            error = failure ?: pendingError
            pendingError = ""; active = null; sizeVersion++; revision++
        }
        publish()
        return true
    }
    fun completeService(owner: ServiceLease, failure: String? = null): Boolean {
        synchronized(lock) {
            if (serviceOwner !== owner || active?.request?.token != owner.request.token) return false
            serviceOwner = null; pendingSize = null; sizeVersion++
            error = failure ?: pendingError
            pendingError = ""; active = null; revision++
        }
        publish()
        return true
    }
    /** Called only after all owned session and capture-resource cleanup has returned. */
    fun finishSizeSwitch(owner: ServiceLease, cleanUserSwitch: Boolean, failure: String? = null): Request? {
        val next = synchronized(lock) {
            if (serviceOwner !== owner || active?.request?.token != owner.request.token) return null
            val pending = pendingSize
            val canRestart = cleanUserSwitch && pending != null && failure == null && pendingError.isEmpty()
            serviceOwner = null; pendingSize = null; sizeVersion++; revision++
            if (canRestart) {
                val request = checkNotNull(pending).copy(token = nextToken)
                nextToken = if (nextToken == Long.MAX_VALUE) 1 else nextToken + 1
                active = Active(request, Phase.CONNECTING, "Applying size: connecting",
                    appliedRequest = checkNotNull(active).appliedRequest)
                error = ""; pendingError = ""; sizeMessage = "Applying size: connecting"
                request
            } else {
                error = pendingError.ifEmpty { failure.orEmpty() }
                if (pending != null && error.isEmpty()) error = "Size change cancelled or cleanup unconfirmed; press Play to retry"
                sizeMessage = if (pending != null) error else sizeMessage
                pendingError = ""; active = null
                null
            }
        }
        publish()
        return next
    }
    fun stopDispatchFailed(token: Long, previous: Phase, message: String) {
        synchronized(lock) {
            val current = active?.takeIf { it.request.token == token && it.phase == Phase.STOPPING && !it.cleanupStarted } ?: return
            active = current.copy(phase = previous, detail = message)
            error = message
        }
        publish()
    }
    companion object {
        // Discovery timestamps use nanoTime; elapsedRealtime includes suspend and would expire every fresh peer.
        val shared = ReceiverCasting()
    }
}

/** This exact handler is used by native row buttons and deterministic tests. */
internal class ReceiverRowActions(
    private val casting: ReceiverCasting,
    private val requireReady: () -> Unit,
    private val readSettings: () -> Settings,
    private val start: (ReceiverCasting.Request) -> Unit,
    private val stop: (ReceiverCasting.Request) -> Unit,
    private val applyRuntimeOverride: Boolean = false
) {
    fun press(key: ReceiverKey, expectedToken: Long?, expectedRevision: Long) {
        val snapshot = casting.snapshot()
        check(snapshot.revision == expectedRevision && snapshot.active?.request?.token == expectedToken) {
            "Sharing state changed. Use the current receiver control."
        }
        val current = snapshot.active
        if (current != null) {
            check(ReceiverKey.of(current.request.target) == key) { "Stop the active receiver before choosing another" }
            casting.requestStop(current.request.token)?.let { request ->
                try { stop(request) }
                catch (e: Exception) {
                    casting.stopDispatchFailed(request.token, current.phase, "Stop failed: ${e.message ?: e.javaClass.simpleName}. Retry Stop.")
                    throw e
                }
            }
            return
        }
        requireReady()
        val request = casting.reserve(key, readSettings(), snapshot.revision, applyRuntimeOverride)
        try { start(request) }
        catch (e: Exception) {
            casting.complete(request.token, "Could not start sharing: ${e.message ?: e.javaClass.simpleName}")
            throw e
        }
    }
}
