package dev.screencopy.sender

/** Snapshot ownership under the monitor; potentially blocking I/O must run after releasing it. */
internal object CaptureCancellation {
    inline fun closeOwnedProcess(process: Process?, guardian: Boolean, release: () -> Unit) {
        // A command reader may own the stream monitor until exit. Never wait for close before
        // terminating that owned non-capture helper. Real capture supervisors exit via EOF only.
        try { if (!guardian) process?.destroy() } finally { release() }
    }
    // Match desktop channel interruption; the private controller EOF wakes Server.Completion
    // even when both media encoders are idle and cannot observe a broken output pipe.
    inline fun <T> interruptChannels(media: List<T>, lifecycle: T?, interrupt: (T) -> Unit) {
        media.forEach(interrupt)
        lifecycle?.let(interrupt)
    }

    inline fun <T> closeDetached(lock: Any, detach: () -> T, close: (T) -> Unit): T {
        val owned = synchronized(lock) { detach() }
        close(owned)
        return owned
    }
}
