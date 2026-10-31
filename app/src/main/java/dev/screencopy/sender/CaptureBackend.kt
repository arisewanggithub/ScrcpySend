package dev.screencopy.sender

import android.content.Context
import android.os.Build
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.EOFException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

class CaptureBackend internal constructor(
    private val context: Context, private val bridge: CaptureBridge, private val fail: (Exception) -> Unit,
    private val trace: SessionStopTrace?
) : Closeable {
    internal constructor(context: Context, bridge: CaptureBridge, fail: (Exception) -> Unit) : this(context, bridge, fail, null)
    constructor(context: Context, adb: LocalAdb, fail: (Exception) -> Unit) : this(context, AdbCaptureBridge(adb), fail, null)
    private val ownership = context.getSharedPreferences("capture-ownership", Context.MODE_PRIVATE)
    private val streams = mutableListOf<InputStream>()
    private val workers = mutableListOf<Thread>()
    private var scid: String? = null
    @Volatile private var closed = false
    private val serverLog = CaptureServerLog()
    private val firstStreamEof = java.util.concurrent.atomic.AtomicReference<CaptureStreamEof?>()
    private val streamEofObserved = java.util.concurrent.CountDownLatch(1)
    private fun reportCapture(error: Exception) {
        if (closed) return
        if (error is CaptureStreamEof && error.evidence.track != CaptureTrack.UNKNOWN) {
            firstStreamEof.compareAndSet(null, error); streamEofObserved.countDown()
        }
        val enriched = if (error is CaptureStreamEof) error.withServer(serverLog.awaitError()) else error
        if (!closed) fail(enriched)
    }

    fun recover() {
        val old = ownership.getString("scid", null) ?: return
        if (!old.matches(Regex("[0-9a-f]{8}"))) throw IOException("Invalid capture recovery record")
        cleanup(old)
        if (!ownership.edit().clear().commit()) throw IOException("Cannot clear recovered capture record")
    }
    fun deploy() {
        recover()
        val id = String.format(Locale.ROOT, "%08x", SecureRandom().nextInt() and 0x7fffffff)
        scid = id
        if (!ownership.edit().putString("scid", id).commit()) throw IOException("Cannot record capture ownership")
        val server = context.assets.open("scrcpy-server-v$VERSION").use { Streams.read(it, 2 * 1024 * 1024) }
        if (Secrets.hex(MessageDigest.getInstance("SHA-256").digest(server)) != SHA256)
            throw IOException("CAPTURE_FAILED: bundled backend checksum mismatch")
        bridge.deploy(id, server)
    }
    fun capabilities(): String = bridge.capabilities(checkNotNull(scid))
    fun validate(settings: Settings, capabilities: String): Settings {
        val resolved = HardwareEncoders.fromReport(capabilities).listedBy(capabilities).resolve(settings)
        Bitrates.validate(resolved, CodecBitrates.fromReport(capabilities))
        DisplayInventory.fromReport(capabilities).validate(resolved)
        bridge.validateVideo(checkNotNull(scid), resolved)
        if (resolved.audio && resolved.audioSource == "playback" && Build.VERSION.SDK_INT < 33)
            throw IOException("Playback audio source requires Android 13+; select output")
        return resolved
    }
    fun start(settings: Settings, session: Long, sink: ScrcpyAdapter.Sink) {
        if (closed) throw IOException("Capture stopped")
        val id = scid ?: throw IOException("Capture backend was not deployed")
        val shell = own(bridge.launch(id, settings))
        worker("scrcpy-log") {
            try {
                serverLog.drain(shell)
                if (!closed) {
                    try { streamEofObserved.await(100, java.util.concurrent.TimeUnit.MILLISECONDS) }
                    catch (e: InterruptedException) { Thread.currentThread().interrupt() }
                    reportCapture(firstStreamEof.get() ?: CaptureStreamEof(CaptureEofEvidence(CaptureTrack.UNKNOWN,
                        CaptureReadStage.SERVER_OUTPUT, captureFormat(settings.videoCodec), false, false, 0, 0, 0, 0)))
                }
            } catch (e: IOException) { if (!closed) reportCapture(e) }
        }
        worker("capture-heartbeat") {
            try {
                while (!closed) { bridge.heartbeat(id); Thread.sleep(1000) }
            } catch (e: Exception) {
                if (e is InterruptedException) Thread.currentThread().interrupt()
                if (!closed) fail(IOException("CAPTURE_FAILED: shell capture watchdog disconnected", e))
            }
        }
        var video: InputStream? = null
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!closed && System.nanoTime() < deadline) {
            try { video = own(bridge.openMedia(id, 1)); break }
            catch (e: IOException) {
                if (System.nanoTime() >= deadline) throw IOException("CAPTURE_FAILED: backend socket not available", e)
                Thread.sleep(100)
            }
        }
        val connectedVideo = video ?: throw IOException("Capture stopped before backend connection")
        val input = DataInputStream(connectedVideo)
        if (input.readUnsignedByte() != 0) throw IOException("CAPTURE_FAILED: invalid scrcpy forward dummy byte")
        val audio = if (settings.audio) own(bridge.openMedia(id, 2)) else null
        input.readFully(ByteArray(64))
        worker("capture-video") { read(input, 1, settings, session, sink) }
        if (audio != null) worker("capture-audio") {
            try { ScrcpyAdapter.read(audio, 2, settings, session, sink) }
            catch (e: Exception) { reportCapture(e) }
        }
    }
    private fun read(input: InputStream, track: Int, settings: Settings, session: Long, sink: ScrcpyAdapter.Sink) {
        try { ScrcpyAdapter.read(input, track, settings, session, sink) }
        catch (e: Exception) { reportCapture(e) }
    }
    @Synchronized private fun own(stream: InputStream): InputStream {
        if (closed) { stream.close(); throw IOException("Capture stopped") }
        streams.add(stream)
        return stream
    }
    @Synchronized private fun worker(name: String, action: () -> Unit) {
        if (!closed) workers.add(Thread(action, name).also { it.start() })
    }
    private fun cleanup(id: String) {
        bridge.cleanup(id)
    }
    override fun close() {
        val owned: List<InputStream>
        val threads: List<Thread>
        synchronized(this) {
            if (closed) return
            closed = true; owned = streams.toList(); threads = workers.toList()
        }
        var error: IOException? = null
        fun failed(e: IOException) { if (error == null) error = e else error?.addSuppressed(e) }
        trace?.mark(SessionStopTrace.Stage.BRIDGE_CLOSE_BEGIN)
        try { bridge.close() } catch (e: IOException) { failed(e) }
        trace?.mark(SessionStopTrace.Stage.BRIDGE_CLOSE_END)
        for (stream in owned) try { stream.close() } catch (e: IOException) { failed(e) }
        threads.forEach(Thread::interrupt)
        scid?.let { id ->
            trace?.mark(SessionStopTrace.Stage.SHELL_CLEANUP_BEGIN)
            try {
                cleanup(id)
                if (!ownership.edit().clear().commit()) throw IOException("Cannot clear capture ownership")
            } catch (e: Exception) { failed(IOException("Capture cleanup requires reconnect/recovery", e)) }
            finally { trace?.mark(SessionStopTrace.Stage.SHELL_CLEANUP_END) }
        }
        trace?.mark(SessionStopTrace.Stage.CAPTURE_JOIN_BEGIN)
        for (thread in threads) if (thread != Thread.currentThread()) {
            try {
                thread.join(250)
                if (thread.isAlive) failed(IOException("Capture reader did not stop; restart Shizuku before retrying"))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt(); failed(IOException("Capture cleanup interrupted", e))
            }
        }
        trace?.mark(SessionStopTrace.Stage.CAPTURE_JOIN_END)
        error?.let { throw it }
    }

    companion object {
        const val VERSION = "3.3.4"
        private const val SHA256 = "8588238c9a5a00aa542906b6ec7e6d5541d9ffb9b5d0f6e1bc0e365e2303079e"
        fun encoders(codec: String): List<String> = HardwareEncoderDiscovery.read().names(codec)
        fun options(settings: Settings, scid: String): String {
            settings.validate()
            val video = settings.videoOptions.let {
                if (Regex("(^|,)max-bframes(:int)?=0(,|$)").containsMatchIn(it)) it
                else (if (it.isEmpty()) "" else "$it,") + "max-bframes=0"
            }
            return buildString {
                append(" scid=$scid tunnel_forward=true control=true video=true audio=${settings.audio}")
                append(" send_dummy_byte=true send_device_meta=true send_codec_meta=true send_frame_meta=true")
                append(" cleanup=true downsize_on_error=false power_on=false clipboard_autosync=false")
                append(" video_codec=${settings.videoCodec} video_bit_rate=${settings.videoBitrate}")
                append(" max_size=${settings.maxSize} max_fps=${settings.maxFps} display_id=${settings.displayId}")
                append(" capture_orientation=${settings.orientation} video_codec_options=$video")
                if (settings.videoEncoder.isNotEmpty()) append(" video_encoder=${settings.videoEncoder}")
                if (settings.cropWidth > 0) append(" crop=${settings.cropWidth}:${settings.cropHeight}:${settings.cropX}:${settings.cropY}")
                if (settings.audio) {
                    append(" audio_codec=${settings.audioCodec} audio_source=${settings.audioSource}")
                    if (settings.audioCodec != "raw") {
                        append(" audio_bit_rate=${settings.audioBitrate}")
                        if (settings.audioEncoder.isNotEmpty()) append(" audio_encoder=${settings.audioEncoder}")
                        if (settings.audioOptions.isNotEmpty()) append(" audio_codec_options=${settings.audioOptions}")
                    }
                }
            }
        }
    }
}
