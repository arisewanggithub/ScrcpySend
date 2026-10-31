package dev.screencopy.sender

import java.io.Closeable
import java.io.InputStream

internal interface CaptureBridge : Closeable {
    fun deploy(id: String, server: ByteArray)
    fun capabilities(id: String): String
    fun validateVideo(id: String, settings: Settings): VideoGeometry {
        throw java.io.IOException("UNSUPPORTED_CONFIG: Privileged video size/rate preflight unavailable.")
    }
    fun launch(id: String, settings: Settings): InputStream
    fun heartbeat(id: String)
    fun openMedia(id: String, track: Int): InputStream
    fun cleanup(id: String)
}

/** Retained only for legacy regression coverage. The primary UI/session never constructs it. */
internal class AdbCaptureBridge(private val adb: LocalAdb) : CaptureBridge {
    private val streams = mutableListOf<io.github.muntashirakon.adb.AdbStream>()
    private var shell: io.github.muntashirakon.adb.AdbStream? = null
    private fun path(id: String) = "/data/local/tmp/screencopy-$id.jar"
    override fun deploy(id: String, server: ByteArray) = adb.push(server, path(id))
    override fun capabilities(id: String) = adb.shell(
        "CLASSPATH=${path(id)} app_process / com.genymobile.scrcpy.Server ${CaptureBackend.VERSION} list_encoders=true list_displays=true cleanup=false", 128 * 1024)
    override fun launch(id: String, settings: Settings): InputStream {
        throw java.io.IOException("Legacy ADB capture is disabled; the Shizuku direct-child supervisor is required")
    }
    override fun heartbeat(id: String) {
        checkNotNull(shell).openOutputStream().apply { write("KEEP\n".toByteArray()); flush() }
    }
    override fun openMedia(id: String, track: Int): InputStream {
        val stream = adb.openStream("localabstract:scrcpy_$id")
        stream.setReadTimeoutMillis(0); streams.add(stream)
        return stream.openInputStream()
    }
    override fun cleanup(id: String) {
        throw java.io.IOException("Legacy ADB cleanup is disabled; privileged Shizuku recovery is required")
    }
    override fun close() { streams.forEach { it.close() }; streams.clear() }
}
