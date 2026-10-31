package dev.screencopy.sender

import org.json.JSONArray
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Unencrypted, unauthenticated trusted-LAN endpoint transport. */
class ReceiverLink(private val target: ReceiverTarget) : Closeable {
    private val socket = Socket()
    val localAddress: String get() = socket.localAddress.hostAddress
        ?: throw IOException("Receiver socket has no local address")
    val peerAddress: String get() = socket.inetAddress?.hostAddress
        ?: throw IOException("Receiver socket has no peer address")

    fun connect(timeoutMillis: Long = 10_000) {
        require(timeoutMillis > 0) { "Startup deadline must be positive" }
        val expired = AtomicBoolean(false)
        val timer = Executors.newSingleThreadScheduledExecutor()
        val timeout = timer.schedule({ expired.set(true); socket.close() }, timeoutMillis, TimeUnit.MILLISECONDS)
        var success = false
        try {
            socket.tcpNoDelay = true; socket.soTimeout = 10000
            socket.sendBufferSize = 64 * 1024; socket.receiveBufferSize = 64 * 1024
            socket.connect(InetSocketAddress(target.host, target.port), 10000)
            send(Wire.control(Wire.HELLO, 0, Wire.obj(
                "role" to "sender", "versions" to JSONArray().put(Wire.VERSION),
                "name" to "ScrcpySend"
            )))
            val hello = receive()
            if (hello.session != 0L) throw IOException("PROTOCOL: wrong HELLO session")
            if (hello.type == Wire.REJECT || hello.type == Wire.ERROR)
                throw IOException("${Json.string(hello.json(), "code")}: ${Json.string(hello.json(), "message")}")
            val json = hello.json()
            if (hello.type != Wire.HELLO || Json.string(json, "role") != "receiver")
                throw IOException("PROTOCOL: receiver HELLO required")
            val name = Json.string(json, "name")
            if (name.isEmpty() || name.length > 80) throw IOException("PROTOCOL: receiver name must be 1..80 characters")
            val versions = json.getJSONArray("versions")
            if ((0 until versions.length()).none { versions.get(it) is Int && versions.getInt(it) == Wire.VERSION })
                throw IOException("VERSION: receiver must support plain TCP ScreenCopy v3")
            if (expired.get()) throw SocketTimeoutException("TIMEOUT: receiver startup exceeded its phase deadline")
            success = true
        } catch (e: IOException) {
            if (expired.get()) throw SocketTimeoutException("TIMEOUT: receiver startup exceeded its phase deadline").apply { initCause(e) }
            throw e
        } finally {
            timeout.cancel(false); timer.shutdownNow()
            if (!success) socket.close()
        }
    }

    @Synchronized fun send(message: Wire.Message) = Wire.write(socket.outputStream, message)
    fun receive(): Wire.Message = Wire.read(socket.inputStream)
    override fun close() = socket.close()
}
