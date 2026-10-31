package dev.screencopy.sender

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.screencopy.sender.SyntheticEncodedFixture.canonicalOpusHead
import dev.screencopy.sender.SyntheticEncodedFixture.encodeBlackFrames
import dev.screencopy.sender.SyntheticEncodedFixture.opusSilence
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit opt-in, wall-time-bounded synthetic media. Never captures a display or microphone. */
@RunWith(AndroidJUnit4::class)
class SrtFixtureDeviceTest {
    @Test(timeout = 120_000) fun serveStaticFixtureForReceiverProbe() = serve(staticVideo = true, export = false)
    @Test(timeout = 120_000) fun serveMovingFixtureForReceiverProbe() = serve(staticVideo = false, export = false)
    @Test(timeout = 120_000) fun serveAudioOffFixtureForReceiverProbe() =
        serve(staticVideo = true, export = false, audioEnabled = false)
    @Test(timeout = 120_000) fun saveStaticFixtureForReceiverActivity() = serve(staticVideo = true, export = true)
    @Test(timeout = 120_000) fun serveRotationFixtureForReceiverProbe() =
        serve(staticVideo = false, export = false, rotation = true)
    @Test(timeout = 120_000) fun serveCodecFixtureForReceiverProbe() =
        serve(staticVideo = false, export = false, selectableCodecs = true)
    @Test(timeout = 120_000) fun serveCodecAudioOffFixtureForReceiverProbe() =
        serve(staticVideo = true, export = false, audioEnabled = false, selectableCodecs = true)
    @Test(timeout = 120_000) fun saveCodecFixtureForReceiverActivity() =
        serve(staticVideo = false, export = true, selectableCodecs = true)
    @Test(timeout = 120_000) fun saveHevcStaticAudioOffFixtureForReceiverActivity() {
        require(InstrumentationRegistry.getArguments().getString("srtFixtureVideoCodec") == "h265") {
            "Explicit srtFixtureVideoCodec=h265 required for the synthetic HEVC export"
        }
        serve(staticVideo = true, export = true, audioEnabled = false, selectableCodecs = true)
    }
    @Test(timeout = 120_000) fun serveHevcRotationFixtureForReceiverProbe() {
        require(InstrumentationRegistry.getArguments().getString("srtFixtureVideoCodec") == "h265") {
            "Explicit srtFixtureVideoCodec=h265 required for synthetic HEVC rotation"
        }
        serve(staticVideo = false, export = false, rotation = true, selectableCodecs = true)
    }

    private fun serve(staticVideo: Boolean, export: Boolean, audioEnabled: Boolean = true, rotation: Boolean = false,
        selectableCodecs: Boolean = false) {
        val args = InstrumentationRegistry.getArguments()
        if (export) assumeTrue("Explicit export opt-in required", args.getString("srtFixtureExport") == "true")
        else assumeTrue("Explicit LAN endpoint arguments required", args.containsKey("srtFixtureHost"))
        assumeTrue(BuildConfig.SRT_TRIAL)
        val videoCodec = if (selectableCodecs) args.getString("srtFixtureVideoCodec") ?: "h264" else "h264"
        val audioCodec = if (selectableCodecs) args.getString("srtFixtureAudioCodec") ?: "aac" else "opus"
        require(videoCodec in setOf("h264", "h265", "av1") && audioCodec in setOf("opus", "aac"))
        check(!rotation || videoCodec in setOf("h264", "h265")) { "UNSUPPORTED_CONFIG: AV1 configuration changes/rotation require a new session" }
        check(SyntheticEncodedFixture.hardwareVideoEncoder(videoCodec) != null) {
            "UNSUPPORTED_CONFIG: no hardware $videoCodec encoder; NOT_VALIDATED (not a skipped PASS)"
        }
        val host = if (export) "127.0.0.1" else numericAddress(args.getString("srtFixtureHost"), true)
        val peer = if (export) "127.0.0.1" else numericAddress(args.getString("srtFixturePeer"), false)
        val seconds = args.getString("srtFixtureSeconds")?.let {
            it.toIntOrNull() ?: error("srtFixtureSeconds must be an integer")
        } ?: 20
        require(seconds in 16..90) { "srtFixtureSeconds must be 16..90 seconds of live media" }
        val profile = args.getString("srtFixtureProfile") ?: "small"
        require(profile in setOf("small", "screen")) { "srtFixtureProfile must be small or screen" }
        val sessionText = args.getString("srtFixtureSession") ?: "0000000000000001"
        require(sessionText.matches(Regex("[0-9a-f]{16}"))) { "srtFixtureSession must be 16 lowercase hex digits" }
        val session = sessionText.toLongOrNull(16)
        require(session != null && session > 0) { "srtFixtureSession must be a positive signed session ID" }
        val fps = if (profile == "screen") 60 else 30
        val width = if (profile == "screen") 1080 else if (rotation) 240 else 320
        val height = if (profile == "screen") 1920 else if (rotation) 320 else 240
        val bitrate = if (profile == "screen") 20_000_000 else 200_000
        val (config, clip) = encodeBlackFrames(
            if (staticVideo) 1 else fps,
            width, height, fps, bitrate,
            noise = profile == "screen", motion = !staticVideo, videoCodec = videoCodec)
        val landscape = if (rotation) encodeBlackFrames(fps, height, width, fps, bitrate,
            noise = profile == "screen", motion = true, videoCodec = videoCodec) else null
        val aac: SyntheticEncodedFixture.EncodedAudio? =
            if (audioEnabled && audioCodec == "aac") SyntheticEncodedFixture.encodeAacSilence() else null
        val audioSamples = aac?.samplesPerFrame ?: 960
        val audioRate = aac?.sampleRate ?: 48000
        fun audioOffsetUs(index: Long) = (index * audioSamples * 1_000_000 + audioRate / 2) / audioRate
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val server = SrtSender(host, peer, session,
            Settings().apply {
                mediaTransportMode = "srt"; maxFps = fps; audio = audioEnabled
                this.videoCodec = videoCodec; this.audioCodec = audioCodec
            },
            { Log.i(TAG, it) })
        val descriptor = SrtTransport.descriptor(host, server.port, session)
        val readyAt = SystemClock.elapsedRealtime()
        val stoppingReader = AtomicBoolean()
        val executor = if (export) Executors.newSingleThreadExecutor() else null
        val directory = if (export) checkNotNull(context.getExternalFilesDir(null)) else null
        val destination = directory?.let { File(it, "srt-static-fmp4.mp4") }
        val staging = directory?.let { File(it, "srt-static-fmp4.mp4.part") }
        var caller = 0L
        var videoSent = 0L
        var audioSent = 0L
        var mediaStart = 0L
        var videoGeneration = 1
        var complete = false
        try {
            server.config(1, 1, CodecConfig.video(videoCodec, config))
            if (audioEnabled) server.config(2, 1, CodecConfig.audio(audioCodec, aac?.config ?: canonicalOpusHead()))
            val metadata = Bundle().apply {
                putString("srtFixturePhase", "ready")
                putString("srtFixtureUrl", descriptor.getString("url"))
                putString("srtFixtureStreamId", descriptor.getString("streamId"))
                putInt("srtFixtureLatencyMs", SrtTransport.LATENCY_MS)
                putString("srtFixtureDescriptor", descriptor.toString())
                putString("srtFixtureSession", sessionText)
                putInt("srtFixtureSeconds", seconds)
                putBoolean("srtFixtureStaticVideo", staticVideo)
                putBoolean("srtFixtureAudio", audioEnabled)
                putString("srtFixtureProfile", profile)
                putBoolean("srtFixtureRotation", rotation)
                putString("srtFixtureVideoCodec", videoCodec)
                putString("srtFixtureAudioCodec", if (audioEnabled) audioCodec else "off")
            }
            instrumentation.sendStatus(0, metadata)
            Log.i(TAG, "phase=ready srtFixtureDescriptor=$descriptor seconds=$seconds static=$staticVideo audio=$audioEnabled profile=$profile firstKeyframeBytes=${clip.first().data.size}")
            val reader = if (export) {
                caller = SrtNative.createCaller(SrtTransport.streamId(session), SrtTransport.LATENCY_MS)
                SrtNative.connect(caller, host, server.port, 5000)
                val ownedCaller = caller
                checkNotNull(executor).submit(Callable {
                    var total = 0L
                    checkNotNull(staging).outputStream().buffered(64 * 1024).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        try {
                            while (!stoppingReader.get()) {
                                val count = SrtNative.read(ownedCaller, buffer, 0, buffer.size)
                                if (count < 0) throw EOFException("SRT fixture ended before export completed")
                                total += count
                                if (total > 16L * 1024 * 1024) throw IOException("SRT fixture export exceeded 16 MiB")
                                output.write(buffer, 0, count)
                            }
                        } catch (e: IOException) {
                            if (!stoppingReader.get()) throw e
                            Log.i(TAG, "phase=export-read-cancelled exception=${e.javaClass.simpleName}")
                        }
                    }
                    total
                })
            } else null
            try {
                while (!server.callerConnected) {
                    server.checkHealth()
                    Thread.sleep(5)
                }
                mediaStart = SystemClock.elapsedRealtimeNanos()
                metadata.putString("srtFixturePhase", "connected")
                instrumentation.sendStatus(0, metadata)
                val end = mediaStart + TimeUnit.SECONDS.toNanos(seconds.toLong())
                val silence = if (audioEnabled) opusSilence() else null
                var nextStatus = mediaStart
                while (SystemClock.elapsedRealtimeNanos() < end) {
                    val now = SystemClock.elapsedRealtimeNanos()
                    if (audioEnabled && now >= mediaStart + audioOffsetUs(audioSent) * 1000) {
                        server.packet(Wire.Message(Wire.AUDIO_PACKET, 0, session, 2, 1,
                            1_000_000 + audioOffsetUs(audioSent),
                            if (aac == null) checkNotNull(silence) else aac.packets[(audioSent % aac.packets.size).toInt()]))
                        audioSent++
                    }
                    if ((!staticVideo || videoSent == 0L) && now >= mediaStart + videoSent * 1_000_000_000L / fps) {
                        val generation = if (rotation) (videoSent / (fps * 4L)).toInt().coerceAtMost(2) + 1 else 1
                        val selected = if (generation == 2) checkNotNull(landscape) else config to clip
                        if (generation != videoGeneration) {
                            server.config(1, generation, CodecConfig.video(videoCodec, selected.first))
                            videoGeneration = generation
                            Log.i(TAG, "phase=rotation generation=$generation videoSent=$videoSent sourcePtsUs=${1_000_000 + videoSent * 1_000_000 / fps}")
                        }
                        val frame = selected.second[(videoSent % selected.second.size).toInt()]
                        server.packet(Wire.Message(Wire.VIDEO_FRAME, if (frame.key) 1 else 0,
                            session, 1, generation, 1_000_000 + videoSent * 1_000_000 / fps, frame.data))
                        videoSent++
                    }
                    server.checkHealth()
                    if (reader?.isDone == true) {
                        reader.get() // Propagate an unexpected native read/export failure immediately.
                        error("SRT fixture reader retired before the wall-time deadline")
                    }
                    if (now >= nextStatus) {
                        Log.i(TAG, "phase=progress wallElapsedMs=${SystemClock.elapsedRealtime() - readyAt} mediaElapsedMs=${TimeUnit.NANOSECONDS.toMillis(now - mediaStart)} videoSent=$videoSent audioSent=$audioSent")
                        nextStatus = now + TimeUnit.SECONDS.toNanos(1)
                    }
                    val nextVideo = if (staticVideo && videoSent > 0) Long.MAX_VALUE else
                        mediaStart + videoSent * 1_000_000_000L / fps
                    val nextAudio = if (audioEnabled) mediaStart + audioOffsetUs(audioSent) * 1000 else Long.MAX_VALUE
                    val next = minOf(nextAudio, nextVideo, end)
                    val waitMs = (next - SystemClock.elapsedRealtimeNanos()) / 1_000_000
                    Thread.sleep(waitMs.coerceIn(1, 20))
                }
                if (audioEnabled) assertTrue("At least eight seconds of $audioCodec must be retained", audioOffsetUs(audioSent) >= 8_000_000)
                else assertEquals("Audio-off fixture must never enqueue audio", 0L, audioSent)
                if (staticVideo) assertEquals(1L, videoSent)
                else assertTrue("Moving fixture must advance real video samples", videoSent >= fps * 8L)
                if (rotation) assertEquals("Portrait-landscape-portrait must retain one live session", 3, videoGeneration)
                assertTrue(SystemClock.elapsedRealtimeNanos() - mediaStart >= TimeUnit.SECONDS.toNanos(seconds.toLong()))
                // Allow the independent 100ms fragment tick and 100ms SRT latency to drain.
                Thread.sleep(500)
                server.checkHealth()
            } finally {
                stoppingReader.set(true)
                try { server.close() }
                finally { if (caller != 0L) SrtNative.close(caller) }
                reader?.get(3, TimeUnit.SECONDS)
            }
            if (export) {
                val temporary = checkNotNull(staging)
                assertTrue("Native loopback did not produce an fMP4 file", temporary.length() > 0)
                temporary.inputStream().use {
                    val prefix = ByteArray(8)
                    java.io.DataInputStream(it).readFully(prefix)
                    assertArrayEquals("ftyp".toByteArray(Charsets.US_ASCII), prefix.copyOfRange(4, 8))
                }
                Files.move(temporary.toPath(), checkNotNull(destination).toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("srtFixturePhase", "exported")
                    putString("srtFixturePath", destination.absolutePath)
                    putLong("srtFixtureBytes", destination.length())
                })
                Log.i(TAG, "phase=exported srtFixturePath=${destination.absolutePath} bytes=${destination.length()}")
            }
            complete = true
        } finally {
            stoppingReader.set(true)
            try { server.close() }
            finally {
                if (caller != 0L) SrtNative.close(caller)
                executor?.shutdownNow()
                if (executor != null) assertTrue("SRT fixture reader leaked", executor.awaitTermination(3, TimeUnit.SECONDS))
                if (staging?.exists() == true && !staging.delete())
                    Log.e(TAG, "Unable to remove incomplete fixture ${staging.absolutePath}")
                Log.i(TAG, "phase=${if (complete) "complete" else "failed"} wallElapsedMs=${SystemClock.elapsedRealtime() - readyAt} mediaElapsedMs=${if (mediaStart == 0L) 0 else TimeUnit.NANOSECONDS.toMillis(SystemClock.elapsedRealtimeNanos() - mediaStart)} videoSent=$videoSent audioSent=$audioSent")
            }
        }
    }

    private fun numericAddress(text: String?, local: Boolean): String {
        val value = checkNotNull(text) { "srtFixture${if (local) "Host" else "Peer"} is required" }
        val numeric = if (value.contains(':')) value.matches(Regex("[0-9a-fA-F:.]+(%[0-9]+)?"))
        else value.split('.').let { parts ->
            parts.size == 4 && parts.all { it.matches(Regex("[0-9]{1,3}")) && it.toInt() in 0..255 }
        }
        require(numeric) { "Fixture addresses must be numeric, not hostnames" }
        val address = InetAddress.getByName(value)
        require(!address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isMulticastAddress) {
            "LAN fixtures require non-loopback unicast addresses"
        }
        if (local) require(NetworkInterface.getNetworkInterfaces().toList().any {
            it.isUp && it.inetAddresses.toList().contains(address)
        }) { "srtFixtureHost must belong to an active Source network interface" }
        return value
    }

    companion object { private const val TAG = "ScreenCopySrtFixture" }
}
