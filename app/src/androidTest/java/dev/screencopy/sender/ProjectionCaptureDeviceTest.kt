package dev.screencopy.sender

import android.content.Intent
import android.media.MediaCodec
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit manual-consent H.264 test only. Never selected by unattended CI or generic smoke. */
@RunWith(AndroidJUnit4::class)
class ProjectionCaptureDeviceTest {
    @Test fun consentedH264AndPlaybackPcmReachCustomTcpAndStopCleanly() {
        assumeTrue("Requires user approval on the phone",
            InstrumentationRegistry.getArguments().getString("manualProjectionConsent") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val model = ReceiverCasting.shared
        check(!CastService.active) { "Stop the current cast before running the projection test" }
        val activity = instrumentation.startActivitySync(Intent(context, ProjectionConsentTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ProjectionConsentTestActivity
        try {
            assertTrue("Approve audio recording and screen sharing on the phone", activity.completed.await(180, TimeUnit.SECONDS))
            val consent = checkNotNull(activity.consent) { "User did not approve screen sharing" }
            ServerSocket(0).use { server ->
                server.soTimeout = 20_000
                val receiver = CompletableFuture.supplyAsync {
                    server.accept().use { socket ->
                        socket.soTimeout = 20_000
                        val hello = Wire.read(socket.inputStream)
                        assertEquals(Wire.HELLO, hello.type)
                        Wire.write(socket.outputStream, Wire.control(Wire.HELLO, 0,
                            Wire.obj("role" to "receiver", "versions" to org.json.JSONArray().put(3), "name" to "Projection fixture")))
                        val request = Wire.read(socket.inputStream)
                        assertEquals(Wire.SESSION_REQUEST, request.type)
                        Wire.write(socket.outputStream, Wire.control(Wire.SESSION_ACCEPT, request.session,
                            Wire.obj("tracks" to org.json.JSONArray().put(1).put(2))))
                        var decoder: MediaCodec? = null
                        var decoded = false
                        var videoCount = 0
                        var audioCount = 0
                        var firstVideo: Long? = null
                        var firstAudio: Long? = null
                        var stopped = false
                        try {
                            while (true) {
                                val message = Wire.read(socket.inputStream)
                                when (message.type) {
                                    Wire.STREAM_CONFIG -> {
                                        val config = message.json()
                                        if (message.track == 1) {
                                            val format = MediaFormat.createVideoFormat("video/avc", config.getInt("width"), config.getInt("height"))
                                            val csd = config.getJSONArray("csd")
                                            for (index in 0 until csd.length()) format.setByteBuffer("csd-$index",
                                                java.nio.ByteBuffer.wrap(Base64.getDecoder().decode(csd.getString(index))))
                                            decoder = MediaCodec.createDecoderByType("video/avc").apply {
                                                configure(format, null, null, 0); start()
                                            }
                                        } else {
                                            assertEquals("raw", config.getString("codec"))
                                            assertEquals(48000, config.getInt("sampleRate"))
                                            assertEquals(2, config.getInt("channels"))
                                        }
                                        Wire.write(socket.outputStream, Wire.Message(Wire.STREAM_READY, 0, request.session,
                                            message.track, message.generation, -1, Wire.obj("ok" to true).toString().toByteArray()))
                                    }
                                    Wire.VIDEO_FRAME -> {
                                        videoCount++; if (firstVideo == null) firstVideo = message.pts
                                        val codec = checkNotNull(decoder)
                                        if (!decoded) {
                                            val slot = codec.dequeueInputBuffer(100_000)
                                            assertTrue(slot >= 0)
                                            checkNotNull(codec.getInputBuffer(slot)).apply { clear(); put(message.payload) }
                                            codec.queueInputBuffer(slot, 0, message.payload.size, message.pts, 0)
                                            val info = MediaCodec.BufferInfo()
                                            val index = codec.dequeueOutputBuffer(info, 100_000)
                                            if (index >= 0) { decoded = info.size > 0; codec.releaseOutputBuffer(index, false) }
                                        }
                                    }
                                    Wire.AUDIO_PACKET -> {
                                        audioCount++; if (firstAudio == null) firstAudio = message.pts
                                        assertEquals(0, message.payload.size % 4)
                                    }
                                    Wire.PING -> Wire.write(socket.outputStream, Wire.control(Wire.PONG, request.session, message.json()))
                                    Wire.ERROR -> error(message.json().toString())
                                    Wire.SESSION_END -> break
                                }
                                if (!stopped && decoded && videoCount >= 3 && audioCount >= 3) {
                                    assertTrue("Video/audio must use the same monotonic timebase",
                                        kotlin.math.abs(checkNotNull(firstVideo) - checkNotNull(firstAudio)) < 1_000_000)
                                    Wire.write(socket.outputStream, Wire.control(Wire.STOP, request.session, Wire.obj("reason" to "user")))
                                    stopped = true
                                }
                            }
                            assertTrue(decoded)
                            assertTrue(videoCount >= 3 && audioCount >= 3)
                            true
                        } finally { decoder?.let { try { it.stop() } finally { it.release() } } }
                    }
                }
                instrumentation.runOnMainSync {
                    val target = ReceiverTarget("127.0.0.1", server.localPort, "Projection fixture")
                    model.updateDiscovery(listOf(Discovery.Receiver(target.name, target.host, target.port, "available", System.nanoTime())))
                    val request = model.reserve(ReceiverKey.of(target), Settings().apply {
                        audioCodec = "raw"; audioSource = "playback"; maxSize = 640; maxFps = 30
                    })
                    context.startForegroundService(Intent(context, CastService::class.java).setAction("start")
                        .putExtra("request", request.token).putExtra("projection", consent))
                }
                assertTrue(receiver.get(30, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (CastService.active && System.nanoTime() < deadline) Thread.sleep(25)
                assertFalse("Projection/service must finish owned cleanup", CastService.active)
                assertTrue(model.snapshot().error, model.snapshot().error.isEmpty())
            }
        } finally {
            model.snapshot().active?.request?.let {
                context.startService(Intent(context, CastService::class.java).setAction("stop").putExtra("request", it.token))
            }
            instrumentation.runOnMainSync { activity.finish(); model.updateDiscovery(emptyList()) }
        }
    }
}
