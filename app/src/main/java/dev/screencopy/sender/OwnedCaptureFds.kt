package dev.screencopy.sender

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.IOException
import java.io.InputStream

internal class OwnedCaptureFds : Closeable {
    private val lock = Any()
    private val fds = mutableListOf<Pair<ParcelFileDescriptor, Boolean>>()
    private var closed = false
    fun own(fd: ParcelFileDescriptor, socket: Boolean): InputStream {
        val cancelled = synchronized(lock) {
            if (!closed && fds.size < 3) {
                fds.add(fd to socket)
                return ParcelFileDescriptor.AutoCloseInputStream(fd)
            }
            closed
        }
        fd.close()
        if (cancelled) throw SessionCancelledException()
        throw IOException("Capture descriptor budget exceeded")
    }
    override fun close() {
        val owned = synchronized(lock) {
            if (closed) return
            closed = true
            fds.toList().also { fds.clear() }
        }
        var failure: IOException? = null
        fun failed(error: IOException) { if (failure == null) failure = error else failure?.addSuppressed(error) }
        owned.forEach { (fd, socket) ->
            if (socket) try { Os.shutdown(fd.fileDescriptor, OsConstants.SHUT_RDWR) }
            catch (e: ErrnoException) {
                if (e.errno != OsConstants.ENOTCONN && e.errno != OsConstants.EBADF)
                    failed(IOException("Media FD shutdown failed", e))
            }
            try { fd.close() } catch (e: IOException) { failed(e) }
        }
        failure?.let { throw it }
    }
}
