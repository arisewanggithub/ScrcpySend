package dev.screencopy.sender

import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger

/** Reservation happens before enqueueing, and is shared by every Activity and the service. */
internal class CaptureAccess {
    private var owner: Lease? = null

    @Synchronized fun acquire(operation: String): Lease {
        check(owner == null) { "Capture setup or casting is already in progress; wait or stop it first" }
        return Lease(operation).also { owner = it }
    }
    @Synchronized fun requireAvailable() {
        check(owner == null) { "Capture setup or casting is already in progress; wait or stop it first" }
    }
    inner class Lease internal constructor(val operation: String) : Closeable {
        override fun close() = synchronized(this@CaptureAccess) {
            if (owner === this) owner = null
        }
    }
    class ReservedTask(private val lease: Lease, private val onCancelled: () -> Unit = {},
                       private val afterRun: () -> Unit = {}, private val action: () -> Unit) : Runnable {
        private val state = AtomicInteger(0)
        override fun run() {
            if (state.compareAndSet(0, 1)) try { lease.use { action() } } finally { afterRun() }
        }
        fun cancelPending() {
            if (state.compareAndSet(0, 2)) { lease.close(); onCancelled() }
        }
    }
    companion object { val shared = CaptureAccess() }
}
