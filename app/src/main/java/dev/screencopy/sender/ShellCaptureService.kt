package dev.screencopy.sender

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import androidx.annotation.Keep

/** Created by Shizuku's supported UserService loader, never an ordinary Android Service. */
@Keep
class ShellCaptureService : IShizukuCapture.Stub() {
    private val lock = Any()
    private val closeLock = Any()
    private data class GuardianHandle(val id: String, val process: Process)
    private var guardianHandle: GuardianHandle? = null
    private var lastCleanedId: String? = null
    private var owner: IBinder? = null
    private var callerUid = -1
    private var rootAllowed = false
    private var process: Process? = null
    private var cleanupCommandId: String? = null
    private var activeId: String? = null
    private var nextTrack = 1
    private var openingTrack = 0
    private var lastMediaTrack = 2
    private var lifecycleSocket: LocalSocket? = null
    private val scope = CaptureScope()
    private var lifetime = CaptureLifetime()
    private val sockets = mutableListOf<LocalSocket>()
    private val pipes = mutableListOf<ParcelFileDescriptor>()
    private val workers = java.util.concurrent.ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(1))
    private val timers = Executors.newSingleThreadScheduledExecutor()
    private var cleanup = java.util.concurrent.CountDownLatch(0)
    // Kernel FD close and the supervisor's parent-death signal must not wait on Java I/O locks.
    private val ownerDeath = IBinder.DeathRecipient { terminateService() }

    override fun attach(client: IBinder, allowRoot: Boolean): Int {
        ShellCapturePolicy.requireUid(Os.getuid(), allowRoot)
        check(Binder.getCallingPid() > 1 && Binder.getCallingPid() != Os.getpid()) { "Capture requires a separate UserService process" }
        synchronized(lock) {
            if (owner != null && owner !== client && owner?.isBinderAlive == true)
                throw SecurityException("Capture service already has an owner")
            owner?.unlinkToDeath(ownerDeath, 0)
            client.linkToDeath(ownerDeath, 0)
            owner = client; callerUid = Binder.getCallingUid(); rootAllowed = allowRoot
        }
        return Os.getuid()
    }
    private fun authorized() {
        synchronized(lock) {
            ShellCapturePolicy.requireUid(Os.getuid(), rootAllowed)
            if (owner?.isBinderAlive != true || Binder.getCallingUid() != callerUid)
                throw SecurityException("UserService owner is unavailable")
        }
    }
    private inline fun <T> ipc(action: () -> T): T = try { action() }
        catch (e: IOException) { throw IllegalStateException("SHELL_IO: ${e.message ?: "Privileged capture I/O failed"}") }
        catch (e: android.system.ErrnoException) { throw IllegalStateException("SHELL_IO: ${e.message}") }

    override fun deploy(id: String, source: ParcelFileDescriptor) = ipc {
        authorized()
        val destination = File(ShellCapturePolicy.path(id))
        val operation = synchronized(lock) {
            check(process == null && cleanup.count == 0L) { "Wait for privileged cleanup before deployment" }
            scope.claim(id)
            lastCleanedId = null
            scope.beginOperation(id)
        }
        try {
            val data = ParcelFileDescriptor.AutoCloseInputStream(source).use { Streams.read(it, 2 * 1024 * 1024) }
            if (Secrets.hex(MessageDigest.getInstance("SHA-256").digest(data)) != ShellCapturePolicy.SERVER_HASH)
                throw SecurityException("Pinned scrcpy-server checksum mismatch")
            synchronized(lock) { scope.requireActive(id) }
            if (!destination.createNewFile()) throw IOException("Capture artifact already exists; recover before deploying")
            FileOutputStream(destination).use { it.write(data); it.fd.sync() }
            Os.chmod(destination.path, 0b110100100) // 0644, shell-readable; no app-private-path assumption.
        } finally { operation.countDown() }
    }
    override fun supervisorAbi(): String = ipc {
        authorized()
        val machine = Os.uname().machine
        val arm = machine.startsWith("aarch64") || machine.startsWith("arm")
        if (!arm && machine !in setOf("x86_64", "i686", "i386")) throw IOException("Unsupported Shizuku process architecture")
        if (android.os.Process.is64Bit()) { if (arm) "arm64-v8a" else "x86_64" }
            else { if (arm) "armeabi-v7a" else "x86" }
    }
    override fun deploySupervisor(id: String, source: ParcelFileDescriptor, sha256: String) = ipc {
        authorized()
        val operation = synchronized(lock) { scope.beginOperation(id) }
        val destination = File(ShellCapturePolicy.path(id) + ".supervisor")
        try {
            val data = ParcelFileDescriptor.AutoCloseInputStream(source).use { Streams.read(it, 256 * 1024) }
            if (!sha256.matches(Regex("[0-9a-f]{64}")) ||
                Secrets.hex(MessageDigest.getInstance("SHA-256").digest(data)) != sha256 ||
                data.size < 4 || !data.take(4).toByteArray().contentEquals(byteArrayOf(127, 69, 76, 70)))
                throw SecurityException("Capture supervisor integrity check failed")
            synchronized(lock) { scope.requireActive(id) }
            if (!destination.createNewFile()) throw IOException("Supervisor already exists; recover before deploying")
            FileOutputStream(destination).use { it.write(data); it.fd.sync() }
            Os.chmod(destination.path, 0b111000000) // 0700, only the privileged owner executes it.
        } finally { operation.countDown() }
    }
    private fun command(id: String, arguments: List<String>, limit: Int = 128 * 1024,
                        cleanupCommand: Boolean = false, supervisor: Boolean = false, classPath: String? = null): String {
        val builder = ProcessBuilder(arguments).redirectErrorStream(true)
        if (classPath != null) builder.environment()["CLASSPATH"] = classPath
        val child = builder.start()
        try {
            synchronized(lock) {
                if (cleanupCommand) scope.requireCleanup(id) else scope.requireActive(id)
                if (owner?.isBinderAlive != true || process != null || cleanup.count != 0L)
                    throw IOException("Capture service unavailable or busy")
                process = child
                cleanupCommandId = if (cleanupCommand) id else null
            }
        } catch (e: Exception) {
            child.destroy()
            try { child.outputStream.close() } catch (error: IOException) { e.addSuppressed(error) }
            throw e
        }
        val timedOut = java.util.concurrent.atomic.AtomicBoolean()
        val timeout = timers.schedule({
            timedOut.set(true); child.destroy()
            child.inputStream.close(); child.outputStream.close()
        }, 10, TimeUnit.SECONDS)
        try {
            if (supervisor) child.outputStream.apply { write("START\n".toByteArray()); flush() }
            val text = child.inputStream.use { String(Streams.read(it, limit), Charsets.UTF_8) }
            if (!cleanupCommand) synchronized(lock) { scope.requireActive(id) }
            if (timedOut.get() || !child.waitFor(1, TimeUnit.SECONDS))
                throw IOException(if (cleanupCommand) "CLEANUP_COMMAND_TIMEOUT wait_budget_ms=10000" else "Privileged command timed out")
            if (child.exitValue() != 0) throw IOException(if (cleanupCommand) "CLEANUP_COMMAND_EXIT exit=${child.exitValue()}"
                else "Privileged backend command failed (${child.exitValue()}); restart Shizuku and retry. Cleanup was not assumed successful.")
            return text
        } finally {
            timeout.cancel(false); child.destroy()
            synchronized(lock) { if (process === child) { process = null; cleanupCommandId = null } }
        }
    }
    override fun capabilities(id: String): String = ipc {
        authorized()
        val path = ShellCapturePolicy.path(id)
        if (!command(id, listOf("$path.supervisor", path, "probe-exit"), 4096, supervisor = true).contains("SCREENCOPY_GUARDIAN_READY:"))
            throw IOException("Native capture supervisor could not prove isolated direct-child startup/cleanup")
        val backend = command(id, listOf("/system/bin/app_process", "/", "com.genymobile.scrcpy.Server", CaptureBackend.VERSION,
            "list_encoders=true", "list_displays=true", "cleanup=false"), classPath = path)
        val hardware = HardwareEncoderDiscovery.read().listedBy(backend)
        hardware.filterBackendReport(backend) + "\n" + hardware.report() +
            "\n" + CodecBitrateDiscovery.read().report() + "\n" + DisplayInventoryDiscovery.read().report()
    }
    override fun displayInventory(): String = ipc { authorized(); DisplayInventoryDiscovery.read().report() }
    override fun validateVideo(id: String, settings: String) = ipc {
        authorized()
        synchronized(lock) { scope.requireActive(id) }
        val result = try { VideoPreflightReport.encode(VideoEncoderPreflight.validate(Settings.from(Wire.parseJson(settings.toByteArray())))) }
            catch (e: VideoGeometryException) { VideoPreflightReport.encode(e.geometry, e) }
        synchronized(lock) { scope.requireActive(id) }
        result
    }
    override fun launch(id: String, settings: String): ParcelFileDescriptor = ipc {
        authorized()
        val path = ShellCapturePolicy.path(id)
        val config = HardwareEncoderDiscovery.read().resolve(Settings.from(Wire.parseJson(settings.toByteArray())))
        Bitrates.validate(config, CodecBitrateDiscovery.read())
        DisplayInventoryDiscovery.read().validate(config)
        VideoEncoderPreflight.validate(config)
        synchronized(lock) { scope.requireActive(id) }
        if (!File(path).isFile) throw IOException("Backend artifact not deployed")
        val output = ParcelFileDescriptor.createPipe()
        val child = try {
            ProcessBuilder(CaptureGuardian.arguments(config, id, path)).redirectErrorStream(true).start()
        } catch (e: IOException) {
            output.forEach { try { it.close() } catch (closeError: IOException) { e.addSuppressed(closeError) } }
            throw e
        }
        try {
            synchronized(lock) {
                scope.requireActive(id)
                if (owner?.isBinderAlive != true || process != null || cleanup.count != 0L)
                    throw IOException("Capture service busy or owner died")
                process = child; activeId = id; nextTrack = 1; openingTrack = 0
                guardianHandle = GuardianHandle(id, child)
                lastMediaTrack = if (config.audio) 2 else 1
                lifetime = CaptureLifetime()
                lifetime.own(java.io.Closeable { child.outputStream.close() })
                pipes.add(output[1])
            }
        } catch (e: Exception) {
            try { child.outputStream.close() } catch (error: IOException) { e.addSuppressed(error) }
            output.forEach { try { it.close() } catch (error: IOException) { e.addSuppressed(error) } }
            throw e
        }
        try {
            child.outputStream.apply { write("START\n".toByteArray()); flush() }
            workers.execute {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(output[1]).use { sink ->
                        child.inputStream.use { it.copyTo(sink, 4096) }
                    }
                } catch (e: IOException) {
                    android.util.Log.w("ScreenCopyShell", "Capture log bridge closed")
                    cancelOwned(child)
                }
            }
        } catch (e: IOException) {
            cancelOwned(child); output[0].close(); throw e
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            cancelOwned(child); output[0].close()
            throw IOException("Capture log bridge did not finish its previous session", e)
        }
        output[0]
    }
    override fun heartbeat(id: String) = ipc {
        authorized(); ShellCapturePolicy.path(id)
        val child = synchronized(lock) { scope.requireActive(id); check(activeId == id) { "Capture session changed" }; checkNotNull(process) }
        child.outputStream.apply { write("KEEP\n".toByteArray()); flush() }
        Unit
    }
    override fun openMedia(id: String, track: Int): ParcelFileDescriptor = ipc {
        authorized()
        val name = ShellCapturePolicy.socket(id, track)
        val socket = LocalSocket()
        val resource = java.io.Closeable {
            try { socket.shutdownInput(); socket.shutdownOutput() } finally { socket.close() }
        }
        val ownerLifetime = try {
            synchronized(lock) {
                scope.requireActive(id)
                if (activeId != id || nextTrack != track || track > lastMediaTrack || openingTrack != 0)
                    throw IOException("Invalid capture socket order/session")
                if (!lifetime.tryOwn(resource)) throw IOException("Capture socket budget exceeded or cancelled")
                openingTrack = track
                sockets.add(socket) // Own before connect can block; cancel shuts it down.
                lifetime
            }
        } catch (e: Exception) {
            try { socket.close() } catch (error: IOException) { e.addSuppressed(error) }
            throw e
        }
        var connected = false
        try {
            socket.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
            connected = true
            val last = synchronized(lock) {
                if (activeId != id || owner?.isBinderAlive != true) throw IOException("Capture cancelled")
                track == lastMediaTrack
            }
            if (last) openLifecycle(id)
            synchronized(lock) {
                if (activeId != id || owner?.isBinderAlive != true) throw IOException("Capture cancelled")
                nextTrack++
            }
            // Only the shell/root UserService opens the localabstract socket. The app receives
            // an already-authorized FD; it never tries to open a shell socket under its own UID.
            ParcelFileDescriptor.dup(socket.fileDescriptor)
        } catch (e: IOException) {
            synchronized(lock) { sockets.remove(socket) }
            ownerLifetime.forget(resource)
            try { socket.close() } catch (closeError: IOException) { e.addSuppressed(closeError) }
            if (connected) cancelOwned(id = id) // A partially accepted channel set cannot be retried as another track.
            throw e
        } finally {
            synchronized(lock) { if (activeId == id && openingTrack == track) openingTrack = 0 }
        }
    }
    private fun openLifecycle(id: String) {
        val socket = LocalSocket()
        val resource = java.io.Closeable { socket.close() }
        val ownerLifetime = try {
            synchronized(lock) {
                scope.requireActive(id)
                if (activeId != id || lifecycleSocket != null || !lifetime.tryOwn(resource))
                    throw IOException("Lifecycle channel cancelled or already owned")
                lifecycleSocket = socket
                lifetime
            }
        } catch (e: Exception) {
            try { socket.close() } catch (closeError: IOException) { e.addSuppressed(closeError) }
            throw e
        }
        try {
            // Same abstract listener, after ALL enabled media sockets as DesktopConnection requires.
            // This FD never crosses Binder and nothing ever writes a control message to it.
            socket.connect(LocalSocketAddress(ShellCapturePolicy.socket(id, 1), LocalSocketAddress.Namespace.ABSTRACT))
        } catch (e: IOException) {
            synchronized(lock) { if (lifecycleSocket === socket) lifecycleSocket = null }
            ownerLifetime.forget(resource)
            try { socket.close() } catch (closeError: IOException) { e.addSuppressed(closeError) }
            throw e
        }
    }
    override fun closeSession(id: String) = ipc {
        authorized()
        val path = ShellCapturePolicy.path(id)
        synchronized(closeLock) {
            val alreadyClean = synchronized(lock) { scope.requireCleanup(id); lastCleanedId == id && scope.id == null }
            if (alreadyClean) return@synchronized
            if (!cancelOwned(id = id).await(2, TimeUnit.SECONDS)) throw IOException("CLEANUP_CANCEL_TIMEOUT wait_budget_ms=2000")
            val deployment = synchronized(lock) { scope.pendingOperation(id) }
            if (!deployment.await(2, TimeUnit.SECONDS)) throw IOException("CLEANUP_DEPLOY_TIMEOUT wait_budget_ms=2000")
            val guardian = synchronized(lock) { guardianHandle?.takeIf { it.id == id } }
            if (guardian != null) {
                synchronized(lock) { scope.requireCleanup(id); cleanupCommandId = id }
                try {
                    OwnedGuardianExit.await(guardian.process) { android.util.Log.i("ScreenCopySender", it) }
                    for (artifact in CaptureGuardian.artifacts(path, id)) {
                        val file = File(artifact)
                        if (file.exists() && !file.delete()) throw IOException("CLEANUP_ARTIFACT_REMOVE")
                    }
                    synchronized(lock) {
                        scope.finish(id)
                        if (guardianHandle === guardian) guardianHandle = null
                        lastCleanedId = id
                    }
                } finally { synchronized(lock) { if (cleanupCommandId == id) cleanupCommandId = null } }
            } else {
                val completion = synchronized(lock) {
                    scope.requireCleanup(id)
                    if (owner?.isBinderAlive != true || process != null || cleanup.count != 0L)
                        throw IOException("Capture service unavailable or busy")
                    if (scope.pendingOperation(id).count != 0L) throw IOException("Capture deployment changed during recovery")
                    if (scope.id == null) scope.claim(id)
                    scope.cancel(id)
                    java.util.concurrent.CountDownLatch(1).also { cleanup = it; cleanupCommandId = id }
                }
                try {
                    RecoveryArtifactCleanup.run(path, id) { android.util.Log.i("ScreenCopySender", it) }
                    synchronized(lock) { scope.finish(id); lastCleanedId = id }
                } finally {
                    synchronized(lock) {
                        if (cleanupCommandId == id) cleanupCommandId = null
                        completion.countDown()
                    }
                }
            }
        }
    }
    override fun cancel(id: String) { authorized(); ShellCapturePolicy.path(id); cancelOwned(id = id) }
    private fun cancelOwned(expected: Process? = null, id: String? = null): java.util.concurrent.CountDownLatch {
        val owned = CaptureCancellation.closeDetached(lock, {
            if (id != null && cleanupCommandId == id) return cleanup
            if (id != null && !scope.cancel(id)) return cleanup
            if (expected != null && process === expected) scope.id?.let(scope::cancel)
            if (expected != null && process !== expected || process == null && sockets.isEmpty() && lifecycleSocket == null && pipes.isEmpty()) return cleanup
            val completion = java.util.concurrent.CountDownLatch(1)
            cleanup = completion
            val result = Cancellation(process, lifetime, sockets.toList(), lifecycleSocket, pipes.toList(), activeId != null, completion)
            process = null; activeId = null; nextTrack = 1; openingTrack = 0
            lifecycleSocket = null; sockets.clear(); pipes.clear()
            lifetime = CaptureLifetime()
            result
        }) { owned -> CaptureCancellation.closeOwnedProcess(owned.process, owned.guardian) {
            try {
                CaptureCancellation.interruptChannels(owned.sockets, owned.lifecycle) { socket ->
                    val fd = socket.fileDescriptor
                    if (fd != null) try { Os.shutdown(fd, android.system.OsConstants.SHUT_RDWR) }
                    catch (e: android.system.ErrnoException) {
                        if (e.errno != android.system.OsConstants.ENOTCONN && e.errno != android.system.OsConstants.EBADF)
                            android.util.Log.w("ScreenCopyShell", "Privileged media shutdown failed: ${e.errno}")
                    }
                }
                owned.process?.let {
                    try { it.outputStream.close() } catch (e: IOException) { android.util.Log.w("ScreenCopyShell", "Guardian stdin already closed") }
                }
                try { owned.lifetime.close() } catch (e: IOException) { android.util.Log.w("ScreenCopyShell", "Owned capture resources closed with I/O errors") }
                owned.pipes.forEach { try { it.close() } catch (e: IOException) { android.util.Log.w("ScreenCopyShell", "Pipe close failed") } }
                owned.process?.let {
                    try { it.inputStream.close() } catch (e: IOException) { android.util.Log.w("ScreenCopyShell", "Guardian output already closed") }
                }
            } finally { owned.completion.countDown() }
        } }
        return owned.completion
    }
    private data class Cancellation(val process: Process?, val lifetime: CaptureLifetime,
        val sockets: List<LocalSocket>, val lifecycle: LocalSocket?, val pipes: List<ParcelFileDescriptor>, val guardian: Boolean,
        val completion: java.util.concurrent.CountDownLatch)
    private fun terminateService(): Nothing {
        workers.shutdownNow(); timers.shutdownNow()
        // Supported UserService destroy: exiting THIS process closes every FD. The native
        // supervisor observes parent death and terminates only its still-owned direct child.
        exitProcess(0)
    }
    override fun destroy() {
        terminateService()
    }
}
