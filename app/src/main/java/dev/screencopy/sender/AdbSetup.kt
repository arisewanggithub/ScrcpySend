package dev.screencopy.sender

import android.content.Context
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbPairingRequiredException

/** Holds proof from a live connection, not a persisted "paired"/"ready" flag. */
internal class AdbSetup(private val access: CaptureAccess = CaptureAccess.shared) : Closeable {
    interface Connection : Closeable {
        val connected: Boolean
        val capabilities: String
        val port: Int? get() = null
        fun probe()
    }
    enum class Failure { NONE, PAIRING_REQUIRED, UNAVAILABLE }
    data class Snapshot(val generation: Long, val port: Int?, val capabilities: String, val checking: Boolean, val message: String,
                        val failure: Failure = Failure.NONE, val automatic: Boolean = false) {
        val ready get() = port != null && !checking
    }
    class Attempt internal constructor() {
        private var closed = false
        private var resource: Closeable? = null
        @Synchronized fun own(value: Closeable) {
            if (closed) { value.close(); throw IOException("ADB setup cancelled") }
            resource = value
        }
        @Synchronized fun check() { if (closed) throw IOException("ADB setup cancelled") }
        fun cancel() {
            val owned = synchronized(this) { closed = true; resource.also { resource = null } }
            owned?.close()
        }
    }
    private val lock = Any()
    private var generation = 0L
    private var connection: Connection? = null
    private var attempt: Attempt? = null
    private var pending: CaptureAccess.ReservedTask? = null
    private var state = Snapshot(0, null, "", false, "")
    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "adb-readiness").apply { isDaemon = true } }
    private val closing = Executors.newSingleThreadExecutor { Thread(it, "adb-readiness-close").apply { isDaemon = true } }
    private val monitoring = AtomicBoolean(false)
    var onConnectionLost: (Long) -> Unit = {}

    fun observe(listener: (Snapshot) -> Unit) { listeners.add(listener); listener(snapshot()) }
    fun removeObserver(listener: (Snapshot) -> Unit) { listeners.remove(listener) }
    fun releaseIfUnobserved() { if (listeners.isEmpty()) disconnect() }
    fun snapshot(): Snapshot {
        val broken = synchronized(lock) { connection?.takeIf { state.ready && !it.connected } }
        if (broken != null) lostConnection(broken, "ADB authorization/connection was lost")
        return synchronized(lock) { state }
    }
    private fun publish() { val current = snapshot(); listeners.forEach { it(current) } }

    fun connect(port: Int?, prepare: (Attempt) -> Connection) {
        if (port != null) Settings.range(port, 1, 65535, "ADB connection port")
        val lease = access.acquire("checking wireless ADB and capabilities")
        val current = Attempt()
        synchronized(lock) {
            if (connection != null || attempt != null) {
                lease.close(); throw IllegalStateException("Disconnect or cancel the current ADB setup first")
            }
            generation++
            attempt = current
            state = Snapshot(generation, null, "", true,
                if (port == null) "Connecting to wireless ADB..." else "Checking wireless ADB...",
                automatic = port == null)
        }
        publish()
        val task = CaptureAccess.ReservedTask(lease, onCancelled = {
            synchronized(lock) {
                if (attempt === current) {
                    attempt = null; pending = null
                    state = Snapshot(generation, null, "", false, "ADB setup cancelled; reconnect when ready.")
                } else if (attempt == null && connection == null) {
                    state = state.copy(checking = false, message = "ADB setup cancelled; reconnect when ready.")
                }
            }
            publish()
        }) {
            var prepared: Connection? = null
            try {
                current.check()
                prepared = prepare(current)
                current.check()
                if (!prepared.connected || prepared.capabilities.isBlank())
                    throw IOException("ADB/capture capabilities are unavailable; reconnect")
                val actualPort = port ?: prepared.port ?: throw IOException("Discovered ADB connection port missing")
                Settings.range(actualPort, 1, 65535, "ADB connection port")
                prepared.probe()
                synchronized(lock) {
                    current.check()
                    check(attempt === current) { "Superseded ADB setup" }
                    connection = prepared
                    lease.close() // Setup resources are finalized before UI may acquire casting ownership.
                    state = Snapshot(generation, actualPort, prepared.capabilities, false, "Wireless ADB connected.")
                    prepared = null
                }
            } catch (e: Exception) {
                synchronized(lock) {
                    if (attempt === current)
                        state = Snapshot(generation, null, "", false,
                            if (failureKind(e) == Failure.PAIRING_REQUIRED) "ADB authorization is missing or revoked. Pair this app using the current Android pairing dialog."
                            else "ADB connection unavailable: ${e.message ?: e.javaClass.simpleName}. Retry discovery or enter the connection port manually.",
                            failureKind(e), port == null)
                }
            } finally {
                try { prepared?.close() }
                catch (e: IOException) { synchronized(lock) { state = state.copy(message = "ADB cleanup failed: ${e.message}") } }
                if (!snapshot().ready) {
                    try { current.cancel() }
                    catch (e: IOException) { synchronized(lock) { state = state.copy(message = "ADB cleanup failed: ${e.message}") } }
                }
                synchronized(lock) {
                    if (attempt === current) { attempt = null; pending = null }
                    else if (attempt == null && connection == null && state.checking)
                        state = state.copy(checking = false, message = "ADB setup cancelled; reconnect when ready.")
                }
            }
        }
        synchronized(lock) { pending = task }
        try {
            worker.execute { task.run(); publish() }
            if (monitoring.compareAndSet(false, true))
                worker.scheduleWithFixedDelay(::checkConnection, 2, 2, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.RejectedExecutionException) { task.cancelPending(); throw e }
    }

    fun requireReady(port: Int): Snapshot {
        snapshot() // Observed disconnection invalidates the gate immediately, without a network read.
        val current = synchronized(lock) {
            check(state.ready && state.port == port && connection?.connected == true) {
                "Wireless ADB is disconnected; connect and check capabilities again"
            }
            state
        }
        return current
    }
    fun checkConnection() {
        val owned = synchronized(lock) { connection } ?: return
        try {
            if (!owned.connected) throw IOException("ADB authorization/connection was lost")
            owned.probe()
        } catch (e: Exception) {
            lostConnection(owned, e.message ?: e.javaClass.simpleName)
        }
    }
    private fun lostConnection(owned: Connection, reason: String) {
        synchronized(lock) {
            if (connection !== owned) return
            val lostGeneration = state.generation
            connection = null; generation++
            state = Snapshot(generation, null, "", false, "ADB disconnected: $reason. Reconnect.")
            closing.execute {
                onConnectionLost(lostGeneration)
                try { owned.close() }
                catch (e: IOException) { synchronized(lock) { if (!state.ready) state = state.copy(message = state.message + " Cleanup: ${e.message}") } }
                publish()
            }
        }
    }
    fun disconnect() {
        val resources = synchronized(lock) {
            if (state.checking && attempt == null && connection == null) return
            generation++
            val all = Triple(connection, attempt, pending)
            connection = null; attempt = null; pending = null
            state = Snapshot(generation, null, "", all.second != null,
                if (all.second != null) "Cancelling ADB setup; waiting for cleanup..." else "Disconnected. Connect again when ready.")
            all
        }
        resources.third?.cancelPending()
        publish()
        closing.execute {
            try { resources.second?.cancel(); resources.first?.close() }
            catch (e: IOException) {
                synchronized(lock) { if (!state.ready) state = state.copy(message = "ADB cleanup failed: ${e.message}. Reconnect for recovery.") }
                publish()
            }
        }
    }

    override fun close() {
        disconnect()
        worker.shutdownNow(); closing.shutdown()
        check(worker.awaitTermination(2, TimeUnit.SECONDS)) { "ADB setup worker did not stop" }
        check(closing.awaitTermination(2, TimeUnit.SECONDS)) { "ADB setup cleanup did not stop" }
    }

    companion object {
        val shared = AdbSetup()
        internal fun failureKind(error: Throwable): Failure {
            var current: Throwable? = error
            repeat(8) {
                if (current is AdbPairingRequiredException || current is AdbAuthenticationFailedException) return Failure.PAIRING_REQUIRED
                current = current?.cause
            }
            return Failure.UNAVAILABLE
        }
    }
}

internal fun prepareAdb(context: Context, port: Int, attempt: AdbSetup.Attempt): AdbSetup.Connection {
    val adb = LocalAdb(Secrets(context))
    val cancel = Closeable { adb.adbConnection?.close() }
    attempt.own(cancel)
    try {
        attempt.check(); adb.connectLocal(port); attempt.check()
        val report = CaptureBackend(context, adb) { throw it }.use { backend ->
            backend.deploy(); attempt.check()
            backend.capabilities().also {
                if (!it.contains("--display-id=") || !it.contains("--video-codec="))
                    throw IOException("Pinned backend did not list usable displays/encoders")
            }
        }
        attempt.check()
        val connection = checkNotNull(adb.adbConnection) { "ADB connection disappeared" }
        return object : AdbSetup.Connection {
            override val port = port
            // These are socket/volatile flags. Never take the manager's blocking OPEN lock on the UI thread.
            override val connected get() = connection.isConnected && connection.isConnectionEstablished && connection.isTls
            override val capabilities = report
            override fun probe() {
                if (!connected || !adb.shell("echo SCREENCOPY_ADB_READY", 256).contains("SCREENCOPY_ADB_READY"))
                    throw IOException("Wireless ADB probe failed")
            }

            override fun close() = adb.close()
        }
    } catch (e: Exception) {
        try { adb.close() } catch (closeError: IOException) { e.addSuppressed(closeError) }
        throw e
    }
}

internal fun prepareAutomaticAdb(context: Context, attempt: AdbSetup.Attempt): AdbSetup.Connection {
    val discovery = LocalAdbDiscovery.create(context, LocalAdbDiscovery.Kind.CONNECT)
    attempt.own(discovery)
    val endpoint = discovery.discover()
    attempt.check()
    // The local-interface advertisement supplies only a port. All ADB connections stay loopback.
    return prepareAdb(context, endpoint.port, attempt)
}
