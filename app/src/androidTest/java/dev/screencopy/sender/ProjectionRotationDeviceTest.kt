package dev.screencopy.sender

import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic H.264 only: no display rotation, screen capture, H.265 Stop or saved-setting changes. */
@RunWith(AndroidJUnit4::class)
class ProjectionRotationDeviceTest {
    @Test fun naturalRasterKeepsOneEncoderGenerationAndCornerColorsAcrossAllFourRotations() {
        check(!CastService.active)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = Settings().apply {
            audio = false; videoEncoder = HardwareEncoderDiscovery.read().resolve("h264", "")
            maxSize = 640; maxFps = 30; cropWidth = 360; cropHeight = 640; cropY = 80
        }
        val sources = (listOf(0, 1, 2, 3, 0)).map { rotation ->
            if (rotation % 2 == 0) CaptureDisplay(0, "Synthetic panel", 360, 800, rotation, 1)
            else CaptureDisplay(0, "Synthetic panel", 800, 360, rotation, 1)
        }
        val configurations = mutableListOf<JSONObject>()
        val packets = mutableListOf<Wire.Message>()
        val timeline = VideoRotationTimeline()
        val arguments = InstrumentationRegistry.getArguments()
        val peer = arguments.getString("probePeer")
        val link = peer?.let {
            check(android.net.InetAddresses.isNumericAddress(it))
            ReceiverLink(ReceiverTarget(it, arguments.getString("probePort")?.toInt() ?: 27184, "Synthetic rotation receiver"))
                .also { connection ->
                    var accepted = false
                    try {
                        connection.connect()
                        check(connection.videoRotation) { "Receiver must negotiate videoRotation" }
                        connection.send(Wire.control(Wire.SESSION_REQUEST, 77, settings.request()))
                        assertEquals(Wire.SESSION_ACCEPT, receiveControl(connection).type)
                        accepted = true
                    } finally { if (!accepted) connection.close() }
                }
        }
        val output = EncodedOutput(1, "h264", 77, object : ScrcpyAdapter.Sink {
            override fun config(track: Int, generation: Int, config: JSONObject) {
                assertEquals(1, generation); configurations += config
                link?.let {
                    it.send(Wire.config(77, track, generation, config))
                    val ready = receiveControl(it)
                    assertEquals(Wire.STREAM_READY, ready.type)
                    assertEquals(1, ready.generation)
                }
            }
            override fun packet(packet: Wire.Message) { packets += packet; link?.send(packet) }
        }, timeline::at)
        val encoder = MediaCodec.createByCodecName(settings.videoEncoder)
        var renderer: ProjectionRenderer? = null
        var surface: android.view.Surface? = null
        var started = false
        val failure = AtomicReference<Exception>()
        val checkpoints = mutableSetOf<Long>()
        try {
            val format = MediaFormat.createVideoFormat("video/avc", 360, 640).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, settings.videoBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, settings.maxFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = encoder.createInputSurface()
            encoder.start(); started = true
            renderer = ProjectionRenderer(context, surface, VideoSize(360, 640), settings, sources.first(),
                natural = true, onFrame = timeline::offer) { failure.compareAndSet(null, it) }
            val input = renderer.start()
            for (source in sources) {
                assertEquals(VideoSize(360, 640),
                    ScrcpyVideoSizing.calculate(settings, DisplayInventory(listOf(source)), natural = true).output)
                renderer.resize(source)
                val first = packets.size
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                val clockwise = (4 - checkNotNull(source.rotation)) % 4
                while (packets.drop(first).count { it.flags ushr 1 == clockwise } < 4 && System.nanoTime() < deadline) {
                    failure.get()?.let { throw it }
                    val canvas = input.lockCanvas(null)
                    val saved = canvas.save()
                    try {
                        canvas.drawColor(Color.BLACK)
                        when (source.rotation) {
                            1 -> { canvas.translate(0f, 360f); canvas.rotate(270f) }
                            2 -> { canvas.translate(360f, 800f); canvas.rotate(180f) }
                            3 -> { canvas.translate(800f, 0f); canvas.rotate(90f) }
                        }
                        val paint = android.graphics.Paint()
                        for ((index, color) in listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).withIndex()) {
                            paint.color = color
                            val x = index % 2 * 180f
                            val y = 80f + index / 2 * 320f
                            canvas.drawRect(x, y, x + 180f, y + 320f, paint)
                        }
                    } finally { canvas.restoreToCount(saved); input.unlockCanvasAndPost(canvas) }
                    val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(60)
                    while (System.nanoTime() < until) output.drain(encoder, 10_000)
                }
                assertTrue("Rotation ${source.rotation} must produce four metadata-tagged pictures",
                    packets.drop(first).count { it.flags ushr 1 == clockwise } >= 4)
                assertEquals(clockwise, packets.last().flags ushr 1)
                checkpoints += packets.last().pts
            }
            assertEquals(1, configurations.size)
            assertTrue(packets.all { it.generation == 1 })
            assertEquals(360, configurations.single().getInt("width"))
            assertEquals(640, configurations.single().getInt("height"))
            verifyGreenFrameEdges(configurations.single(), packets, checkpoints)
        } finally {
            try {
                try { renderer?.close() }
                finally { try { if (started) encoder.stop() } finally { encoder.release(); surface?.release() } }
            } finally {
                link?.use {
                    it.send(Wire.control(Wire.STOP, 77, Wire.obj("reason" to "synthetic rotation test")))
                    assertEquals(Wire.SESSION_END, receiveControl(it).type)
                }
            }
        }
    }

    private fun receiveControl(link: ReceiverLink): Wire.Message {
        repeat(8) {
            val message = link.receive()
            if (message.type != Wire.PING) return message
            link.send(Wire.control(Wire.PONG, message.session, message.json()))
        }
        throw AssertionError("Expected a non-heartbeat reply")
    }

    @Test fun portraitThenLandscapeReconfiguresWithNoEncodedBlackBorders() {
        check(!CastService.active) { "Do not interrupt an active cast" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = Settings().apply {
            audio = false; videoEncoder = HardwareEncoderDiscovery.read().resolve("h264", "")
            maxSize = 640; maxFps = 30; cropWidth = 360; cropHeight = 640; cropY = 80
        }
        val configurations = mutableListOf<JSONObject>()
        val generations = mutableListOf<Int>()
        val packets = mutableListOf<Wire.Message>()
        val output = EncodedOutput(1, "h264", 77, object : ScrcpyAdapter.Sink {
            override fun config(track: Int, generation: Int, config: JSONObject) {
                configurations += config; generations += generation
            }
            override fun packet(packet: Wire.Message) { packets += packet }
        })
        val sources = listOf(CaptureDisplay(0, "Synthetic panel", 360, 800, 0, 1),
            CaptureDisplay(0, "Synthetic panel", 800, 360, 1, 1),
            CaptureDisplay(0, "Synthetic panel", 360, 800, 2, 1))
        for ((phase, source) in sources.withIndex()) {
            val size = ScrcpyVideoSizing.calculate(settings, DisplayInventory(listOf(source))).output
            assertEquals(if (source.rotation?.rem(2) == 0) VideoSize(360, 640) else VideoSize(640, 360), size)
            if (phase > 0) output.restart()
            val firstPacket = packets.size
            val encoder = MediaCodec.createByCodecName(settings.videoEncoder)
            var surface: android.view.Surface? = null
            var renderer: ProjectionRenderer? = null
            var started = false
            val failure = AtomicReference<Exception>()
            try {
                val format = MediaFormat.createVideoFormat("video/avc", size.width, size.height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, settings.videoBitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, settings.maxFps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                surface = encoder.createInputSurface()
                encoder.start(); started = true
                renderer = ProjectionRenderer(context, surface, size, settings, source) { failure.compareAndSet(null, it) }
                val input = renderer.start()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (packets.size - firstPacket < 3 && System.nanoTime() < deadline) {
                    failure.get()?.let { throw it }
                    val canvas = input.lockCanvas(null)
                    try { canvas.drawColor(Color.GREEN) } finally { input.unlockCanvasAndPost(canvas) }
                    val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(60)
                    while (System.nanoTime() < until) output.drain(encoder, 10_000)
                }
                assertTrue(packets.size - firstPacket >= 3)
                assertEquals(phase + 1, configurations.size)
                assertTrue(packets.drop(firstPacket).all { it.generation == phase + 1 })
                assertEquals(size.width, configurations.last().getInt("width"))
                assertEquals(size.height, configurations.last().getInt("height"))
                verifyGreenFrameEdges(configurations.last(), packets.drop(firstPacket))
            } finally {
                try { renderer?.close() }
                finally { try { if (started) encoder.stop() } finally { encoder.release(); surface?.release() } }
            }
        }
        assertEquals(listOf(1, 2, 3), generations)
        assertEquals(80, settings.cropY)
    }

    private fun verifyGreenFrameEdges(config: JSONObject, packets: List<Wire.Message>, quadrantsAt: Set<Long> = emptySet()) {
        val name = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.first {
            !it.isEncoder && it.isSoftwareOnly && it.supportedTypes.any { type -> type.equals("video/avc", true) }
        }.name
        val decoder = MediaCodec.createByCodecName(name)
        var started = false
        val remaining = quadrantsAt.toMutableSet()
        try {
            val format = MediaFormat.createVideoFormat("video/avc", config.getInt("width"), config.getInt("height"))
            val csd = config.getJSONArray("csd")
            for (index in 0 until csd.length()) format.setByteBuffer("csd-$index",
                java.nio.ByteBuffer.wrap(Base64.getDecoder().decode(csd.getString(index))))
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            decoder.configure(format, null, null, 0)
            decoder.start(); started = true
            var queued = 0
            var ended = false
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                if (queued < packets.size) {
                    val slot = decoder.dequeueInputBuffer(10_000)
                    if (slot >= 0) {
                        val packet = packets[queued++]
                        checkNotNull(decoder.getInputBuffer(slot)).apply { clear(); put(packet.payload) }
                        decoder.queueInputBuffer(slot, 0, packet.payload.size, packet.pts, 0)
                    }
                } else if (!ended) {
                    val slot = decoder.dequeueInputBuffer(10_000)
                    if (slot >= 0) {
                        decoder.queueInputBuffer(slot, 0, 0, packets.last().pts + 1, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        ended = true
                    }
                }
                val info = MediaCodec.BufferInfo()
                val index = decoder.dequeueOutputBuffer(info, 10_000)
                if (index >= 0) try {
                    if (quadrantsAt.isNotEmpty() && info.presentationTimeUs !in remaining) continue
                    checkNotNull(decoder.getOutputImage(index)).use { image ->
                        val rect = image.cropRect
                        assertEquals(config.getInt("width"), rect.width())
                        assertEquals(config.getInt("height"), rect.height())
                        val plane = image.planes[0]
                        val pixels = plane.buffer
                        if (quadrantsAt.isNotEmpty()) {
                            fun luma(x: Int, y: Int) = pixels.get((rect.top + y) * plane.rowStride +
                                (rect.left + x) * plane.pixelStride).toInt() and 255
                            val red = luma(20, 20)
                            val green = luma(rect.width() - 21, 20)
                            val blue = luma(20, rect.height() - 21)
                            val yellow = luma(rect.width() - 21, rect.height() - 21)
                            assertTrue("Natural corners must remain red/green/blue/yellow: $red,$green,$blue,$yellow",
                                yellow > green && green > red && red > blue && blue > 20)
                            return@use
                        }
                        val points = listOf(4 to 4, rect.width() - 5 to 4,
                            4 to rect.height() - 5, rect.width() - 5 to rect.height() - 5,
                            rect.width() / 2 to 4, 4 to rect.height() / 2)
                        for ((x, y) in points) {
                            val luma = pixels.get((rect.top + y) * plane.rowStride + (rect.left + x) * plane.pixelStride).toInt() and 255
                            assertTrue("Encoded edge ($x,$y) must be green, not a black border: Y=$luma", luma > 64)
                        }
                    }
                    if (quadrantsAt.isEmpty()) return
                    remaining.remove(info.presentationTimeUs)
                    if (remaining.isEmpty()) return
                } finally { decoder.releaseOutputBuffer(index, false) }
            }
            fail("Synthetic H.264 frame did not decode: queued=$queued/${packets.size}, missing=$remaining")
        } finally { try { if (started) decoder.stop() } finally { decoder.release() } }
    }
}
