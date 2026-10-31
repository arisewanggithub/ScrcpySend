package dev.screencopy.sender

import java.io.Closeable
import java.io.IOException

/** Bounded cancellation ownership shared by UserService owner-death and explicit stop. */
internal class CaptureLifetime(private val limit: Int = 16) : Closeable {
    private val lock = Any()
    private val resources = mutableListOf<Closeable>()
    private var closed = false
    fun tryOwn(resource: Closeable): Boolean = synchronized(lock) {
        if (closed || resources.size >= limit) false else { resources.add(resource); true }
    }
    fun forget(resource: Closeable) { synchronized(lock) { resources.remove(resource) } }
    fun <T : Closeable> own(resource: T): T {
        if (tryOwn(resource)) return resource
        resource.close()
        throw IOException("Capture cancelled or resource budget exceeded")
    }
    fun check() = synchronized(lock) { if (closed) throw IOException("Capture owner died or session cancelled") }
    override fun close() {
        val owned = synchronized(lock) {
            if (closed) return
            closed = true; resources.toList().also { resources.clear() }
        }
        var failure: IOException? = null
        owned.asReversed().forEach {
            try { it.close() } catch (e: IOException) { if (failure == null) failure = e else failure?.addSuppressed(e) }
        }
        failure?.let { throw it }
    }
}
