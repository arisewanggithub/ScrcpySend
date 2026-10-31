package dev.screencopy.sender

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import rikka.shizuku.Shizuku
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class ShizukuClient(context: Context, private val allowRoot: Boolean, private val died: () -> Unit) : Closeable {
    private val app = context.applicationContext
    private val owner = Binder()
    private val ready = CountDownLatch(1)
    private val lock = Any()
    private var service: IShizukuCapture? = null
    private var binding = false
    private var closed = false
    private val args = Shizuku.UserServiceArgs(ComponentName(app, ShellCaptureService::class.java))
        .daemon(false).tag("screencopy-capture-${java.util.UUID.randomUUID()}").processNameSuffix("capture").version(5).debuggable(false)
    private val death = IBinder.DeathRecipient { synchronized(lock) { service = null }; ready.countDown(); died() }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val late = synchronized(lock) {
                if (closed) true else {
                    binder.linkToDeath(death, 0)
                    service = IShizukuCapture.Stub.asInterface(binder)
                    false
                }
            }
            if (late) {
                if (Shizuku.pingBinder()) Shizuku.unbindUserService(args, this, true)
                return
            }
            ready.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName) { death.binderDied() }
    }
    // Local death state only: rendering/resume must never perform synchronous remote ping IPC.
    val alive get() = synchronized(lock) { !closed && service?.asBinder()?.isBinderAlive == true && Shizuku.getBinder()?.isBinderAlive == true }
    fun connect() {
        synchronized(lock) {
            check(!closed) { "Shizuku connection cancelled" }
            binding = true
        }
        Shizuku.bindUserService(args, connection)
        if (!ready.await(10, TimeUnit.SECONDS)) throw IOException("Shizuku UserService startup timed out; reopen Shizuku and retry")
        val remote = remote()
        ShellCapturePolicy.requireUid(remote.attach(owner, allowRoot), allowRoot)
    }
    fun remote(): IShizukuCapture = synchronized(lock) {
        if (closed) throw IOException("Shizuku capture connection closed")
        service?.takeIf { it.asBinder().isBinderAlive } ?: throw IOException("Shizuku UserService Binder is unavailable")
    }
    fun bridge(): CaptureBridge = object : CaptureBridge {
        private val fds = OwnedCaptureFds()
        private var bridgeClosed = false
        @Volatile private var ownedId: String? = null
        private fun <T> call(action: () -> T): T = try { action() }
            catch (e: IllegalStateException) {
                if (e.message?.startsWith("CAPTURE_CANCELLED:") == true) throw SessionCancelledException(e)
                if (e.message?.startsWith("SHELL_IO:") == true) throw IOException(e.message?.removePrefix("SHELL_IO: "), e)
                throw e
            }
        override fun deploy(id: String, server: ByteArray) {
            synchronized(fds) {
                if (bridgeClosed) throw SessionCancelledException()
                check(ownedId == null) { "Bridge already owns a capture" }
                ownedId = id
            }
            sendFile(server) { remote().deploy(id, it) }
            val abi = call { remote().supervisorAbi() }
            if (abi !in setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")) throw IOException("Unsupported Shizuku process ABI")
            val supervisor = app.assets.open("supervisor/$abi/supervisor").use { Streams.read(it, 256 * 1024) }
            val hash = Secrets.hex(java.security.MessageDigest.getInstance("SHA-256").digest(supervisor))
            sendFile(supervisor) { remote().deploySupervisor(id, it, hash) }
        }
        private fun sendFile(data: ByteArray, deploy: (ParcelFileDescriptor) -> Unit) {
            val file = File.createTempFile("capture-artifact-", ".tmp", app.cacheDir)
            try {
                file.outputStream().use { it.write(data) }
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { call { deploy(it) } }
            } finally { if (!file.delete()) throw IOException("Cannot remove temporary backend deployment file") }
        }
        override fun capabilities(id: String): String = call { remote().capabilities(id) }
        override fun validateVideo(id: String, settings: Settings) =
            VideoPreflightReport.decode(call { remote().validateVideo(id, settings.request().toString()) })
        override fun launch(id: String, settings: Settings): InputStream = own(call { remote().launch(id, settings.request().toString()) }, false)
        override fun openMedia(id: String, track: Int): InputStream = own(call { remote().openMedia(id, track) }, true)
        override fun heartbeat(id: String) = call { remote().heartbeat(id) }
        private fun own(fd: ParcelFileDescriptor, socket: Boolean): InputStream = fds.own(fd, socket)
        override fun cleanup(id: String) = call { remote().closeSession(id) }
        override fun close() {
            synchronized(fds) { if (bridgeClosed) return; bridgeClosed = true }
            var failure: Exception? = null
            try { ownedId?.let { remote().cancel(it) } } catch (e: Exception) { failure = e }
            try { fds.close() } catch (e: IOException) { if (failure == null) failure = e else failure.addSuppressed(e) }
            failure?.let { throw IOException("Shizuku capture cancellation failed", it) }
        }
    }
    override fun close() {
        val old = synchronized(lock) { if (closed) return; closed = true; service.also { service = null } }
        ready.countDown()
        old?.asBinder()?.unlinkToDeath(death, 0)
        var error: Exception? = null
        try { if (binding && Shizuku.pingBinder()) Shizuku.unbindUserService(args, connection, true) }
        catch (e: Exception) { if (error == null) error = e else error.addSuppressed(e) }
        error?.let { throw IOException("Shizuku UserService cleanup failed", it) }
    }
}
