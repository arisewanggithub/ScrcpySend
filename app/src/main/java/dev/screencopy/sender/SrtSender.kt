package dev.screencopy.sender

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.container.OpusUtil
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.FragmentedMp4Muxer
import androidx.media3.muxer.MuxerException
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.util.ArrayDeque
import java.util.Base64

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class SrtSender(
    address: String, peer: String, session: Long, settings: Settings,
    private val diagnostic: (String) -> Unit = { android.util.Log.i("ScreenCopySender", it) }
) : Closeable, ScrcpyAdapter.Sink {
    private sealed interface Event
    private data class Config(val track: Int, val bytes: ByteArray, val first: Int, val second: Int) : Event
    private data class Sample(val track: Int, val sourcePts: Long, val key: Boolean, val bytes: ByteArray,
        val queuedSize: Int = bytes.size) : Event
    private val monitor = Object()
    private val events = ArrayDeque<Event>()
    private val queuedBytes = LongArray(3)
    private val queuedSamples = IntArray(3)
    private var queuedConfigs = 0
    @Volatile private var closed = false
    @Volatile private var failure: IOException? = null
    @Volatile var callerConnected = false
        private set
    private val audio = settings.audio
    private val videoCodec = settings.videoCodec
    private val audioCodec = settings.audioCodec
    private val frameRate = settings.maxFps
    private val transport: Long
    private val worker: Thread
    val port: Int
    private val encoded = SrtEncodedSink(
        { track, bytes, first, second -> offer(Config(track, bytes, first, second)) },
        { track, pts, key, bytes -> offer(Sample(track, pts, key, bytes.copyOf())) },
        videoCodec, audioCodec
    )

    init {
        if (!BuildConfig.SRT_TRIAL) throw IOException("UNSUPPORTED_CONFIG: install the SRT trial APK")
        settings.validate()
        transport = try {
            SrtNative.listen(address, peer, SrtTransport.streamId(session), SrtTransport.LATENCY_MS)
        } catch (e: UnsatisfiedLinkError) {
            throw IOException("UNSUPPORTED_CONFIG: SRT native library unavailable", e)
        }
        try {
            port = SrtNative.localPort(transport)
            worker = Thread(::muxLoop, "srt-fmp4-mux").also { it.start() }
        } catch (e: Exception) {
            SrtNative.close(transport)
            throw e
        }
    }

    private fun offer(event: Event) = synchronized(monitor) {
        checkHealth()
        val track = when (event) { is Config -> event.track; is Sample -> event.track }
        if (track == 2 && !audio) throw IOException("PROTOCOL: audio supplied to audio-off SRT session")
        when (event) {
            is Config -> {
                if (queuedConfigs >= 16) throw IOException("CAPTURE_FAILED: SRT configuration queue overflow")
                queuedConfigs++
            }
            is Sample -> {
                val maxBytes = if (track == 1) 8L * 1024 * 1024 else 1024L * 1024
                val maxSamples = if (track == 1) 240 else 100
                if (queuedBytes[track] + event.bytes.size > maxBytes || queuedSamples[track] >= maxSamples)
                    throw IOException("CAPTURE_FAILED: SRT track $track queue overflow")
                queuedBytes[track] += event.bytes.size
                queuedSamples[track]++
            }
        }
        events.addLast(event)
        monitor.notifyAll()
    }

    override fun config(track: Int, generation: Int, config: JSONObject) = encoded.config(track, generation, config)
    override fun packet(packet: Wire.Message) = encoded.packet(packet)
    fun checkHealth() {
        failure?.let { throw it }
        if (closed) throw SessionCancelledException()
    }

    private fun muxLoop() {
        var muxer: FragmentedMp4Muxer? = null
        try {
            if (!SrtNative.awaitCaller(transport, 10_000)) throw IOException("TIMEOUT: no SRT caller")
            checkHealth()
            callerConnected = true
            val output = BufferedOutputStream(object : OutputStream() {
                override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    if (closed) throw SessionCancelledException()
                    try { SrtNative.write(transport, bytes, offset, length) }
                    catch (e: IOException) {
                        if (closed) throw SessionCancelledException(e)
                        throw e
                    }
                }
            }, 64 * 1024)
            val configs = arrayOfNulls<Config>(3)
            val firstPts = arrayOfNulls<Long>(3)
            val trackIds = IntArray(3)
            val pending = ArrayDeque<Sample>()
            val timeline = SrtSampleTimeline(videoCodec, audioCodec)
            val videoConfiguration = SrtAvcConfiguration()
            val hevcConfiguration = SrtHevcConfiguration()
            val writtenBytes = LongArray(3)
            val writtenSamples = IntArray(3)
            var origin = 0L
            var lastFlush = System.nanoTime()
            var lastAudioDiagnostic = 0L
            var dirty = false
            while (!closed) {
                val event = synchronized(monitor) {
                    if (events.isEmpty() && !closed) {
                        val remaining = 100_000_000L - (System.nanoTime() - lastFlush)
                        if (remaining > 0) monitor.wait((remaining / 1_000_000).coerceAtLeast(1))
                    }
                    events.pollFirst()
                }
                when (event) {
                    is Config -> {
                        synchronized(monitor) { queuedConfigs-- }
                        val old = configs[event.track]
                        if ((event.track == 2 || videoCodec == "av1") && old != null && (old.first != event.first || old.second != event.second ||
                                !old.bytes.contentEquals(event.bytes)))
                            throw IOException("UNSUPPORTED_CONFIG: SRT trial does not support track ${event.track} configuration changes")
                        if (event.track == 1 && videoCodec == "h264") {
                            videoConfiguration.configure(event.bytes)
                            diagnostic("srt_video_config width=${event.first} height=${event.second} inBand=avc3")
                        }
                        if (event.track == 1 && videoCodec == "av1") {
                            Av1Configuration.dimensions(event.bytes)
                            diagnostic("srt_video_config codec=av1 width=${event.first} height=${event.second} fixedConfiguration=true")
                        }
                        if (event.track == 1 && videoCodec == "h265") {
                            hevcConfiguration.configure(event.bytes)
                            diagnostic("srt_video_config codec=h265 width=${event.first} height=${event.second} inBand=hev1")
                        }
                        if (event.track == 2) {
                            timeline.configureAudio(event.bytes)
                            diagnostic("srt_audio_config codec=$audioCodec channels=${event.first} sampleRate=${event.second}")
                        }
                        // moov describes the first configuration even if audio startup is delayed.
                        configs[event.track] = old ?: event
                    }
                    is Sample -> {
                        val configurationIdr = event.track == 1 &&
                            (videoCodec == "h264" && videoConfiguration.requiresIdr ||
                                videoCodec == "h265" && hevcConfiguration.requiresIrap)
                        val bytes = if (event.track == 1 && videoCodec == "h264") videoConfiguration.sample(event.key, event.bytes)
                            else if (event.track == 1 && videoCodec == "av1") {
                                val config = configs[1] ?: throw IOException("CAPTURE_FAILED: AV1 sample before configuration")
                                val obus = Av1Configuration.obus(event.bytes)
                                if (obus.any { it.type == androidx.media3.container.ObuParser.OBU_SEQUENCE_HEADER } &&
                                    !Av1Configuration.sequence(event.bytes).contentEquals(Av1Configuration.sequence(config.bytes)))
                                    throw IOException("UNSUPPORTED_CONFIG: AV1 in-band sequence changes unsupported")
                                // Official Media3 derives av1C from the first sample's real sequence OBU.
                                if (firstPts[1] == null) Av1Configuration.firstSample(config.bytes, event.bytes) else event.bytes
                            } else if (event.track == 1 && videoCodec == "h265")
                                hevcConfiguration.sample(event.key, event.bytes) else event.bytes
                        if (bytes.size != event.queuedSize) synchronized(monitor) {
                            val extra = bytes.size - event.queuedSize
                            if (queuedBytes[event.track] + extra > 8L * 1024 * 1024)
                                throw IOException("CAPTURE_FAILED: SRT video parameter sets exceed queue budget")
                            queuedBytes[event.track] += extra
                        }
                        val pts = timeline.presentationTime(event.track, event.sourcePts, event.key, bytes)
                        if (configurationIdr) diagnostic(
                            "srt_video_${if (videoCodec == "h265") "irap" else "idr"} sourcePtsUs=${event.sourcePts} muxPtsUs=$pts")
                        if (firstPts[event.track] == null) firstPts[event.track] = pts
                        pending.addLast(event.copy(sourcePts = pts, bytes = bytes, queuedSize = bytes.size))
                        if (event.track == 2 && timeline.lastAudioSourcePtsUs != pts &&
                            System.nanoTime() - lastAudioDiagnostic >= 1_000_000_000L) {
                            diagnostic("srt_audio codec=$audioCodec sourcePtsUs=${event.sourcePts} muxPtsUs=$pts")
                            lastAudioDiagnostic = System.nanoTime()
                        }
                    }
                    null -> Unit
                }
                if (muxer == null && configs[1] != null && firstPts[1] != null &&
                    (!audio || configs[2] != null && firstPts[2] != null)) {
                    origin = if (audio) minOf(checkNotNull(firstPts[1]), checkNotNull(firstPts[2])) else checkNotNull(firstPts[1])
                    muxer = FragmentedMp4Muxer.Builder(Channels.newChannel(output))
                        .setFragmentDurationMs(Long.MAX_VALUE / 1000)
                        .setSampleCopyingEnabled(false).build()
                    for (track in 1..(if (audio) 2 else 1))
                        trackIds[track] = muxer.addTrack(format(checkNotNull(configs[track])))
                }
                val active = muxer
                if (active != null) {
                    while (pending.isNotEmpty()) {
                        val sample = pending.removeFirst()
                        // Audio access units are MP4 sync samples; wire audio flags remain zero.
                        active.writeSampleData(trackIds[sample.track], ByteBuffer.wrap(sample.bytes),
                            BufferInfo(sample.sourcePts - origin, sample.bytes.size,
                                if (sample.track == 2 || sample.key) C.BUFFER_FLAG_KEY_FRAME else 0))
                        writtenBytes[sample.track] += sample.queuedSize
                        writtenSamples[sample.track]++
                        dirty = true
                    }
                }
                if (System.nanoTime() - lastFlush >= 100_000_000L) {
                    if (dirty && active != null) {
                        active.flushFragment()
                        output.flush()
                        synchronized(monitor) {
                            for (track in 1..2) {
                                queuedBytes[track] -= writtenBytes[track]
                                queuedSamples[track] -= writtenSamples[track]
                                writtenBytes[track] = 0
                                writtenSamples[track] = 0
                            }
                        }
                        dirty = false
                    }
                    lastFlush = System.nanoTime()
                }
            }
        } catch (e: SessionCancelledException) {
            if (!closed) recordFailure(e)
        } catch (e: IOException) {
            if (!closed) recordFailure(e)
        } catch (e: MuxerException) {
            if (!closed) recordFailure(IOException("CAPTURE_FAILED: fMP4 mux failed", e))
        } catch (e: IllegalArgumentException) {
            recordFailure(IOException("CAPTURE_FAILED: invalid fMP4 input", e))
        } catch (e: IllegalStateException) {
            recordFailure(IOException("CAPTURE_FAILED: invalid fMP4 state", e))
        } catch (e: IndexOutOfBoundsException) {
            recordFailure(IOException("CAPTURE_FAILED: truncated fMP4 codec input", e))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            if (!closed) recordFailure(IOException("CAPTURE_FAILED: SRT mux interrupted", e))
        } finally {
            callerConnected = false
            if (failure != null) SrtNative.close(transport)
            try { muxer?.close() }
            catch (e: MuxerException) {
                if (!closed || !isCancellation(e)) recordFailure(IOException("CAPTURE_FAILED: fMP4 finalization failed", e))
            }
        }
    }

    private fun format(config: Config): Format =
        if (config.track == 1) Format.Builder().setSampleMimeType(when (videoCodec) {
            "av1" -> MimeTypes.VIDEO_AV1; "h265" -> MimeTypes.VIDEO_H265; else -> MimeTypes.VIDEO_H264
        })
            .setWidth(config.first).setHeight(config.second).setFrameRate(frameRate.toFloat())
            // Media3 accepts av1C records here, not raw Android codec-config OBUs.
            // Empty Format initialization requests its official first-sample av1C parser;
            // the actual encoder sequence/metadata OBUs are retained in that first sample.
            .setInitializationData(if (videoCodec == "av1") emptyList()
                else if (videoCodec == "h265") listOf(HevcConfiguration.parse(config.bytes).csd)
                else CodecConfig.annexB(config.bytes).map { byteArrayOf(0, 0, 0, 1) + it }).build()
        else Format.Builder().setSampleMimeType(if (audioCodec == "aac") MimeTypes.AUDIO_AAC else MimeTypes.AUDIO_OPUS)
            .setChannelCount(config.first).setSampleRate(config.second)
            .setInitializationData(if (audioCodec == "aac") listOf(config.bytes) else OpusUtil.buildInitializationData(config.bytes)).build()

    private fun recordFailure(error: IOException) = synchronized(monitor) {
        if (failure == null) {
            val original = SrtFailureDiagnostics.withSnapshot(error) { SrtNative.snapshot(transport) }
            val safe = SrtFailureDiagnostics.sanitize(original)
            failure = safe
            SrtFailureDiagnostics.lines(original, safe).forEach(diagnostic)
        }
        monitor.notifyAll()
    }
    private fun isCancellation(error: Throwable): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is SessionCancelledException) return true
            cause = cause.cause
        }
        return false
    }

    internal class SrtAvcConfiguration {
        private var parameters: ByteArray? = null
        private var awaitingIdr = false
        private var emitParameters = false
        val requiresIdr: Boolean get() = awaitingIdr

        fun configure(bytes: ByteArray) {
            CodecConfig.video("h264", bytes)
            emitParameters = parameters != null
            parameters = bytes.copyOf()
            awaitingIdr = true
        }

        fun sample(key: Boolean, bytes: ByteArray): ByteArray {
            val config = parameters ?: throw IOException("CAPTURE_FAILED: SRT video before configuration")
            if (!awaitingIdr) return bytes
            val nals = CodecConfig.annexB(bytes)
            if (!key || nals.none { it[0].toInt() and 31 == 5 })
                throw IOException("CAPTURE_FAILED: SRT video configuration must be followed by a real IDR")
            awaitingIdr = false
            // avc3 permits new parameter sets in-band. Keep every original access-unit byte.
            return if (emitParameters) config + bytes else bytes
        }
    }

    override fun close() {
        synchronized(monitor) { closed = true; monitor.notifyAll() }
        // Interrupt accept/write before waiting for either mux or capture readers.
        SrtNative.close(transport)
        if (Thread.currentThread() != worker) {
            val deadline = System.nanoTime() + 2_000_000_000L
            var interrupted = false
            while (worker.isAlive && System.nanoTime() < deadline) {
                try { worker.join(((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)) }
                catch (_: InterruptedException) { interrupted = true }
            }
            if (interrupted) Thread.currentThread().interrupt()
            if (worker.isAlive) throw IOException("TIMEOUT: SRT mux worker did not stop")
        }
        failure?.let { throw it }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object SrtFailureDiagnostics {
    private data class Reason(val category: String, val operation: String = "NONE", val code: Int? = null) {
        fun fields() = "category=$category operation=$operation code=${code ?: "NONE"}"
    }
    private val nativeError = Regex(
        "^SRT (send|receive|wait|connect|accept|bind|listen|startup|socket option|create socket|create epoll|register epoll|local port|caller stream id|stream id|connection handshake):[^\\r\\n]* \\(code ([0-9]{1,6})\\)$"
    )
    private val frameName = Regex("[A-Za-z_][A-Za-z0-9_.$]{0,199}")

    private fun chain(error: Throwable): List<Throwable> {
        val seen = java.util.IdentityHashMap<Throwable, Boolean>()
        val result = mutableListOf<Throwable>()
        var next: Throwable? = error
        while (next != null && result.size < 8 && seen.put(next, true) == null) {
            result.add(next)
            next = next.cause
        }
        return result
    }
    private fun type(error: Throwable): String = when (error) {
        is SessionCancelledException -> "CANCELLED"
        is MuxerException -> "MUXER"
        is IOException -> "IO"
        is InterruptedException -> "INTERRUPTED"
        is IllegalArgumentException -> "INVALID_INPUT"
        is IllegalStateException -> "INVALID_STATE"
        is IndexOutOfBoundsException -> "TRUNCATED_INPUT"
        else -> "UNKNOWN"
    }
    private fun reason(error: Throwable): Reason {
        val message = error.message.orEmpty().substringBefore(" | snapshot ")
        when (message) {
            "SRT send made no progress within one second" -> return Reason("NO_PROGRESS", "SEND")
            "SRT connection closed" -> return Reason("CLOSED", "CONNECTION")
            "SRT caller has not connected" -> return Reason("NOT_CONNECTED", "CONNECTION")
            "SRT connection timed out" -> return Reason("TIMEOUT", "CONNECT")
            "SRT message was not accepted in full" -> return Reason("PARTIAL_WRITE", "SEND")
        }
        nativeError.matchEntire(message)?.let {
            val code = it.groupValues[2].toInt()
            return Reason(when (code) { 2001 -> "BROKEN"; 2002 -> "NOT_CONNECTED"; else -> "NATIVE_ERROR" },
                it.groupValues[1].uppercase().replace(' ', '_'), code)
        }
        return Reason(type(error))
    }
    fun sanitize(error: IOException, srt: Boolean = true): IOException {
        if (error is SessionCancelledException) return error
        val causes = chain(error)
        val root = causes.last()
        val detail = causes.asReversed().map(::reason).firstOrNull { it.operation != "NONE" } ?: reason(root)
        val code = SessionOutcome.errorCode(error).takeUnless { it == "INTERNAL" }
            ?: if (srt) "CAPTURE_FAILED" else "INTERNAL"
        val stage = if (causes.any { it is MuxerException }) "FMP4_MUX" else if (srt) "SRT" else "SESSION"
        val truncated = if (root.cause != null) " chain_truncated=true" else ""
        return IOException("$code: ${if (srt) "SRT" else "Sender"} failure stage=$stage root=${type(root)} ${detail.fields()}$truncated", error)
    }
    fun evidence(error: Throwable): JSONObject {
        val causes = chain(error)
        val root = causes.last()
        val detail = causes.asReversed().map(::reason).firstOrNull { it.operation != "NONE" } ?: reason(root)
        return Wire.obj("category" to detail.category, "operation" to detail.operation,
            "native_code" to detail.code, "root_exception" to type(root), "chain_truncated" to (root.cause != null),
            "native_snapshot" to causes.asReversed().map { snapshot(it.message.orEmpty().substringAfter(" | snapshot ", "")) }
                .firstOrNull { it.length() > 0 }.orEmptySnapshot())
    }
    fun withSnapshot(error: IOException, sample: () -> String): IOException {
        if (evidence(error).getJSONObject("native_snapshot").optString("snapshot_unavailable") != "not_recorded") return error
        val value = try { sample() } catch (_: IOException) {
            "snapshot=unavailable snapshot_unavailable=query_error state=UNKNOWN last_peer_response_ms=unavailable"
        }
        return IOException("${SessionOutcome.errorCode(error)}: diagnostic evidence | snapshot $value", error)
    }
    private fun JSONObject?.orEmptySnapshot() = this ?: Wire.obj("snapshot" to "unavailable", "snapshot_unavailable" to "not_recorded")
    fun snapshot(text: String): JSONObject {
        val numeric = setOf("elapsed_ms", "packets_sent", "packets_received", "send_loss", "receive_loss",
            "acknowledgements_sent", "acknowledgements_received", "negative_acknowledgements_sent", "negative_acknowledgements_received",
            "retransmissions", "bytes_sent", "bytes_received", "send_buffer_packets", "receive_buffer_packets",
            "send_buffer_bytes", "receive_buffer_bytes",
            "send_buffer_available_bytes", "receive_buffer_available_bytes", "rtt_ms", "snapshot_error_code")
        val enums = mapOf("snapshot" to setOf("available", "unavailable"),
            "snapshot_unavailable" to setOf("closed", "busy", "stats_error", "no_socket", "not_recorded", "query_error"),
            "state" to setOf("UNKNOWN", "CLOSED", "1", "2", "3", "4", "5", "6", "7", "8", "9"),
            "last_peer_response_ms" to setOf("unavailable"))
        return JSONObject().apply {
            text.take(2048).split(' ').take(24).forEach { token ->
                val parts = token.split('=', limit = 2)
                if (parts.size == 2 && !has(parts[0]) &&
                    (parts[1] in enums[parts[0]].orEmpty() ||
                        parts[0] in numeric && parts[1].matches(Regex("-?[0-9]{1,20}(\\.[0-9]{1,6})?"))))
                    put(parts[0], parts[1])
            }
        }.let { if (it.has("snapshot")) it else JSONObject() }
    }
    fun lines(error: IOException, safe: IOException = sanitize(error)): List<String> = buildList {
        add("srt_failed ${safe.message}")
        val causes = chain(error)
        add("srt_failure_chain causes=${causes.size} truncated=${causes.last().cause != null}")
        causes.forEachIndexed { depth, cause ->
            add("srt_failure_cause depth=$depth type=${type(cause)} ${reason(cause).fields()}")
            cause.stackTrace.asSequence().filter { frame ->
                frame.className.startsWith("dev.screencopy.sender.") ||
                    frame.className.startsWith("androidx.media3.muxer.") ||
                    frame.className.startsWith("java.io.") || frame.className.startsWith("java.nio.")
            }.filter { frame -> frameName.matches(frame.className) && frameName.matches(frame.methodName) }
                .take(6).forEachIndexed { index, frame ->
                    add("srt_failure_stack depth=$depth frame=$index at=${frame.className}.${frame.methodName} line=${frame.lineNumber.coerceAtLeast(-1)}")
                }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class SrtSampleTimeline(private val videoCodec: String = "h264", private val audioCodec: String = "opus") {
    private var lastVideo = -1L
    private var nextAudio: Long? = null
    private var aac: AacConfiguration? = null
    private var aacFrames = 0L
    private var aacOrigin: Long? = null
    var lastAudioSourcePtsUs = -1L
        private set

    fun configureAudio(bytes: ByteArray) {
        if (audioCodec == "aac") {
            val parsed = AacConfiguration.parse(bytes)
            if (aac != null && aac != parsed) throw IOException("UNSUPPORTED_CONFIG: AAC configuration changes unsupported")
            aac = parsed
        }
    }

    fun presentationTime(track: Int, sourcePtsUs: Long, key: Boolean, bytes: ByteArray): Long {
        if (track !in 1..2 || sourcePtsUs < 0 || bytes.isEmpty())
            throw IOException("PROTOCOL: invalid SRT encoded sample")
        if (track == 1) {
            if (lastVideo < 0 && !key) throw IOException("CAPTURE_FAILED: first SRT video frame must be a keyframe")
            if (sourcePtsUs <= lastVideo) throw IOException("CAPTURE_FAILED: SRT video timestamps must increase (no B-frames)")
            if (videoCodec == "av1") Av1Configuration.obus(bytes)
            else if (videoCodec == "h265") HevcConfiguration.nalUnits(bytes) else CodecConfig.annexB(bytes)
            lastVideo = sourcePtsUs
            return sourcePtsUs
        }
        if (audioCodec == "aac") {
            val config = aac ?: throw IOException("AUDIO_FAILED: AAC before AudioSpecificConfig")
            AacConfiguration.accessUnit(bytes)
            val first = aacOrigin ?: sourcePtsUs.also { aacOrigin = it }
            if (aacFrames > (Long.MAX_VALUE / 1_000_000 - config.samplesPerFrame))
                throw IOException("AUDIO_FAILED: AAC timeline overflow")
            val offset = (aacFrames * 1_000_000 + config.sampleRate / 2) / config.sampleRate
            if (first > Long.MAX_VALUE - offset) throw IOException("AUDIO_FAILED: AAC timeline overflow")
            aacFrames += config.samplesPerFrame
            lastAudioSourcePtsUs = sourcePtsUs
            return first + offset
        }
        val duration = try { OpusUtil.getPacketDurationUs(bytes) }
        catch (e: IndexOutOfBoundsException) { throw IOException("AUDIO_FAILED: truncated Opus packet", e) }
        if (duration !in 2500..120000) throw IOException("AUDIO_FAILED: invalid Opus packet duration")
        val pts = nextAudio ?: sourcePtsUs
        if (pts > Long.MAX_VALUE - duration) throw IOException("AUDIO_FAILED: Opus timeline overflow")
        nextAudio = pts + duration
        lastAudioSourcePtsUs = sourcePtsUs
        return pts
    }
}

internal class SrtEncodedSink(
    private val configure: (Int, ByteArray, Int, Int) -> Unit,
    private val push: (Int, Long, Boolean, ByteArray) -> Unit,
    private val videoCodec: String = "h264",
    private val audioCodec: String = "opus"
) : ScrcpyAdapter.Sink {
    override fun config(track: Int, generation: Int, config: JSONObject) {
        val codec = Json.string(config, "codec")
        if (!(track == 1 && codec == videoCodec && codec in setOf("h264", "h265", "av1") ||
                track == 2 && codec == audioCodec && codec in setOf("opus", "aac")))
            throw IOException("UNSUPPORTED_CONFIG: actual SRT codec differs from selected supported codec")
        val csd = config.getJSONArray("csd")
        val bytes = try {
            (0 until csd.length()).fold(byteArrayOf()) { data, index ->
                data + Base64.getDecoder().decode(csd.getString(index))
            }
        } catch (e: IllegalArgumentException) { throw IOException("PROTOCOL: invalid SRT codec initialization", e) }
        if (bytes.size > 64 * 1024) throw IOException("CAPTURE_FAILED: SRT codec initialization exceeds 64 KiB")
        if (track == 1) {
            if (codec == "h265") {
                val hevc = HevcConfiguration.parse(bytes)
                if (config.getInt("width") != hevc.width || config.getInt("height") != hevc.height)
                    throw IOException("CAPTURE_FAILED: HEVC dimensions differ from official SPS metadata")
            }
            val parsed = CodecConfig.video(codec, bytes)
            if (config.getInt("width") != parsed.getInt("width") || config.getInt("height") != parsed.getInt("height"))
                throw IOException("CAPTURE_FAILED: inconsistent SRT video dimensions")
        } else {
            if (csd.length() != 1) throw IOException("AUDIO_FAILED: expected one audio configuration buffer")
            val canonical = CodecConfig.audio(codec, bytes)
            if (config.getInt("channels") != canonical.getInt("channels") ||
                config.getInt("sampleRate") != canonical.getInt("sampleRate"))
                throw IOException("AUDIO_FAILED: inconsistent $codec format")
        }
        configure(track, bytes, config.getInt(if (track == 1) "width" else "channels"),
            config.getInt(if (track == 1) "height" else "sampleRate"))
    }
    override fun packet(packet: Wire.Message) {
        if (packet.track !in 1..2 || packet.pts < 0 || packet.payload.isEmpty() ||
            packet.type != (if (packet.track == 1) Wire.VIDEO_FRAME else Wire.AUDIO_PACKET) ||
            packet.flags and 1.inv() != 0 || packet.track == 2 && packet.flags != 0)
            throw IOException("PROTOCOL: invalid SRT encoded packet")
        push(packet.track, packet.pts, packet.flags and 1 != 0, packet.payload)
    }
}
