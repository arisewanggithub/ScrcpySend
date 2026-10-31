package dev.screencopy.sender

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

internal enum class ShizukuState { MISSING, STOPPED, DENIED, ROOT_CONFIRMATION, CONNECTING, READY, ERROR }
internal object ShizukuPolicy {
    fun mayReleaseIdle(observers: Int, castActive: Boolean) = observers == 0 && !castActive
    fun state(installed: Boolean, alive: Boolean, granted: Boolean, uid: Int, rootConsent: Boolean): ShizukuState = when {
        !installed && !alive -> ShizukuState.MISSING
        !alive -> ShizukuState.STOPPED
        !granted -> ShizukuState.DENIED
        uid == 0 && !rootConsent -> ShizukuState.ROOT_CONFIRMATION
        uid != 2000 && uid != 0 -> ShizukuState.ERROR
        else -> ShizukuState.CONNECTING
    }
}
internal class ShizukuSetup private constructor() {
    data class Snapshot(val generation: Long = 0, val state: ShizukuState = ShizukuState.STOPPED,
                        val capabilities: String = "", val message: String = "",
                        val cleanupPending: Boolean = false, val rootApproved: Boolean = false) {
        val ready get() = state == ShizukuState.READY && !cleanupPending
        val checking get() = state == ShizukuState.CONNECTING || cleanupPending
    }
    private val lock = Any()
    private var current = Snapshot()
    private var client: ShizukuClient? = null
    private var generation = 0L
    private var setupToken: Long? = null
    private var cleanupCount = 0
    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()
    private val worker = Executors.newSingleThreadExecutor()
    private val closing = Executors.newSingleThreadExecutor()
    private val timers = Executors.newSingleThreadScheduledExecutor()
    private val displayWorker = Executors.newSingleThreadExecutor()
    private var displayRefreshPending = false
    var onConnectionLost: (Long) -> Unit = {}
    private val binderDeath = Shizuku.OnBinderDeadListener { invalidate("Shizuku stopped or Binder died. Start Shizuku again and reconnect.") }
    init { Shizuku.addBinderDeadListener(binderDeath) }
    fun observe(listener: (Snapshot) -> Unit) { listeners.add(listener); listener(snapshot()) }
    fun removeObserver(listener: (Snapshot) -> Unit) { listeners.remove(listener) }
    private fun publish() { val state = snapshot(); listeners.forEach { it(state) } }
    fun snapshot(): Snapshot {
        val dead = synchronized(lock) { current.ready && client?.alive != true }
        if (dead) invalidate("Shizuku UserService disconnected; reconnect.")
        return synchronized(lock) { current }
    }
    fun status(context: Context, rootConsent: Boolean): ShizukuState {
        val installed = try { context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0); true }
            catch (e: PackageManager.NameNotFoundException) { false }
        val alive = Shizuku.getBinder()?.isBinderAlive == true
        if (alive && (Shizuku.isPreV11() || Shizuku.getVersion() < 13)) return ShizukuState.ERROR
        val granted = alive && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        val uid = if (granted) Shizuku.getUid() else -1
        return ShizukuPolicy.state(installed, alive, granted, uid, rootConsent)
    }
    fun connect(context: Context, allowRoot: Boolean) {
        val access = status(context, allowRoot)
        check(access == ShizukuState.CONNECTING) { "Shizuku must be running, authorized, and shell mode (or explicitly approved root mode): $access" }
        check(Shizuku.getVersion() >= 13) { "Update Shizuku to version 13 or later for UserService support" }
        val lease = CaptureAccess.shared.acquire("Shizuku privileged capture readiness")
        val app = context.applicationContext
        val owned: ShizukuClient
        val token: Long
        synchronized(lock) {
            if (client != null || setupToken != null || cleanupCount != 0) {
                lease.close(); throw IllegalStateException("Wait for previous Shizuku capture setup/cleanup")
            }
            token = ++generation
            setupToken = token
            owned = ShizukuClient(app, allowRoot) {
                if (synchronized(lock) { current.generation == token && client != null })
                    invalidate("Capture UserService died; reconnect Shizuku.")
            }
            client = owned
            current = Snapshot(token, ShizukuState.CONNECTING, message = "Checking shell capture through Shizuku...")
        }
        publish()
        worker.execute {
            var timeout: java.util.concurrent.ScheduledFuture<*>? = null
            try {
                owned.connect()
                timeout = timers.schedule({
                    if (synchronized(lock) { client === owned && current.checking })
                        invalidate("Shizuku deployment/capability check exceeded 10 seconds. Restart Shizuku and retry.")
                }, 10, java.util.concurrent.TimeUnit.SECONDS)
                val report = CaptureBackend(app, owned.bridge()) { throw it }.use {
                    it.deploy()
                    it.capabilities().also { text ->
                        if (!text.contains("--video-codec="))
                            throw IOException("Privileged backend did not enumerate displays/encoders")
                        DisplayInventory.fromReport(text).requireAvailable()
                    }
                }
                check(owned.alive) { "Shizuku disconnected during capture readiness check" }
                synchronized(lock) {
                    check(client === owned && generation == token) { "Shizuku setup cancelled" }
                    current = Snapshot(token, ShizukuState.READY, report,
                        if (Shizuku.getUid() == 0) "Root-mode Shizuku explicitly approved." else "Shizuku shell capture ready.",
                        cleanupPending = true, rootApproved = allowRoot)
                }
            } catch (e: Exception) {
                try { owned.close() } catch (closeError: Exception) { e.addSuppressed(closeError) }
                synchronized(lock) {
                    if (client === owned) {
                        client = null
                        current = Snapshot(token, ShizukuState.ERROR, message = "Shizuku capture failed: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
            } finally {
                timeout?.cancel(false); lease.close()
                synchronized(lock) {
                    if (setupToken == token) setupToken = null
                    current = current.copy(cleanupPending = cleanupCount != 0 || setupToken != null)
                }
                publish()
            }
        }
    }
    fun requireReady(): Snapshot {
        val state = snapshot()
        check(state.ready) { "Authorize running Shizuku and verify capture readiness first" }
        check(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) { "Shizuku permission revoked" }
        return state
    }
    fun bridge(): CaptureBridge { requireReady(); return synchronized(lock) { checkNotNull(client).bridge() } }
    fun refreshDisplays(callback: (Result<DisplayInventory>) -> Unit) {
        val expected = snapshot()
        check(expected.ready) { "Reconnect Shizuku before refreshing displays." }
        val owned = synchronized(lock) {
            check(!displayRefreshPending) { "A display refresh is still pending. Wait and retry." }
            checkNotNull(client).also { displayRefreshPending = true }
        }
        val delivered = java.util.concurrent.atomic.AtomicBoolean()
        val timeout = timers.schedule({
            if (delivered.compareAndSet(false, true))
                callback(Result.failure(IOException("Display refresh timed out. Reconnect Shizuku and retry.")))
        }, 10, java.util.concurrent.TimeUnit.SECONDS)
        displayWorker.execute {
            val result = try {
                check(requireReady().generation == expected.generation) { "Shizuku changed before display refresh" }
                val inventory = DisplayInventory.fromReport(owned.remote().displayInventory())
                inventory.requireAvailable()
                synchronized(lock) {
                    check(client === owned && current.generation == expected.generation && current.ready) { "Shizuku changed during display refresh" }
                    current = current.copy(capabilities = inventory.updateReport(current.capabilities))
                }
                Result.success(inventory)
            } catch (e: Exception) {
                android.util.Log.w("ScreenCopyShizuku", "Display refresh failed: ${e.javaClass.simpleName}")
                Result.failure(e)
            } finally { synchronized(lock) { displayRefreshPending = false }; timeout.cancel(false) }
            if (delivered.compareAndSet(false, true)) callback(result)
        }
    }
    private fun invalidate(message: String) {
        val lost: Pair<Long, ShizukuClient?> = synchronized(lock) {
            if (client == null) return
            val old = current.generation to client
            client = null; cleanupCount++
            current = Snapshot(++generation, ShizukuState.ERROR, message = message, cleanupPending = true)
            old
        }
        onConnectionLost(lost.first)
        closing.execute {
            try { lost.second?.close() }
            catch (e: Exception) {
                synchronized(lock) { current = current.copy(message = "$message Cleanup failed: ${e.message ?: e.javaClass.simpleName}") }
                android.util.Log.w("ScreenCopyShizuku", "UserService cleanup failed: ${e.javaClass.simpleName}")
            } finally {
                synchronized(lock) {
                    cleanupCount--
                    current = current.copy(cleanupPending = cleanupCount != 0 || setupToken != null)
                }
                publish()
            }
        }
        publish()
    }
    fun disconnect() = invalidate("Shizuku capture disconnected; reconnect when ready.")
    fun accessUnavailable(message: String) = invalidate(message)
    fun releaseIfUnobserved() { if (ShizukuPolicy.mayReleaseIdle(listeners.size, CastService.active)) disconnect() }
    companion object { val shared = ShizukuSetup() }
}
