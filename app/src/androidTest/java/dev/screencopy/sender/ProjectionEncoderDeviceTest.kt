package dev.screencopy.sender

import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic Surface input only: exercises the production GPU/output path without screen consent or screen pixels. */
@RunWith(AndroidJUnit4::class)
class ProjectionEncoderDeviceTest {
    @Test fun androidAudioEncoderInitializationAndPacketsMatchExistingReceiverContract() {
        for (codec in listOf("opus", "aac")) {
            val name = HardwareEncoderDiscovery.read().resolve(codec, "")
            val encoder = MediaCodec.createByCodecName(name)
            var started = false
            try {
                val format = MediaFormat.createAudioFormat(checkNotNull(HardwareEncoderPolicy.mimeTypes[codec]), 48000, 2).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 3840)
                    if (codec == "aac") setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                }
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder.start(); started = true
                var configuration: JSONObject? = null
                val packets = mutableListOf<Wire.Message>()
                val output = EncodedOutput(2, codec, 77, object : ScrcpyAdapter.Sink {
                    override fun config(track: Int, generation: Int, config: JSONObject) { configuration = config }
                    override fun packet(packet: Wire.Message) { packets += packet }
                })
                var samples = 0L
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (packets.size < 3 && System.nanoTime() < deadline) {
                    val slot = encoder.dequeueInputBuffer(10_000)
                    if (slot >= 0) {
                        val buffer = checkNotNull(encoder.getInputBuffer(slot)).apply { clear() }
                        val frames = minOf(960, buffer.remaining() / 4)
                        assertTrue(frames > 0)
                        buffer.put(ByteArray(frames * 4))
                        encoder.queueInputBuffer(slot, 0, frames * 4, 1_000_000 + samples * 1_000_000 / 48000, 0)
                        samples += frames
                    }
                    output.drain(encoder, 10_000)
                }
                assertTrue("Actual $codec encoder must produce packets", packets.size >= 3)
                assertEquals(codec, checkNotNull(configuration).getString("codec"))
                assertEquals(48000, checkNotNull(configuration).getInt("sampleRate"))
                assertEquals(2, checkNotNull(configuration).getInt("channels"))
                assertTrue(packets.all { it.track == 2 && it.flags == 0 && it.pts > 0 && it.payload.isNotEmpty() })
            } finally { try { if (started) encoder.stop() } finally { encoder.release() } }
        }
    }

    @Test fun hardwareH264GpuCropAndRotationProduceRealCustomTcpInitializationAndFrames() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = HardwareEncoderDiscovery.read().resolve("h264", "")
        for (orientation in listOf("0", "90")) {
            val settings = Settings().apply {
                audio = false; videoEncoder = name; cropWidth = 640; cropHeight = 480
                this.orientation = orientation; maxSize = 640; maxFps = 30
            }
            val size = if (orientation == "0") VideoSize(640, 480) else VideoSize(480, 640)
            val encoder = MediaCodec.createByCodecName(name)
            var renderer: ProjectionRenderer? = null
            var surface: android.view.Surface? = null
            var started = false
            val failure = AtomicReference<Exception>()
            try {
                val format = MediaFormat.createVideoFormat("video/avc", size.width, size.height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, settings.videoBitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, settings.maxFps)
                    setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, settings.maxFps.toFloat())
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
                }
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                surface = encoder.createInputSurface()
                encoder.start(); started = true
                renderer = ProjectionRenderer(context, surface, size, settings) { failure.compareAndSet(null, it) }
                val input = renderer.start()
                var configuration: JSONObject? = null
                val packets = mutableListOf<Wire.Message>()
                val output = EncodedOutput(1, "h264", 77, object : ScrcpyAdapter.Sink {
                    override fun config(track: Int, generation: Int, config: JSONObject) {
                        assertEquals(1, track); assertEquals(1, generation)
                        configuration = config
                    }
                    override fun packet(packet: Wire.Message) { packets += packet }
                })
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                var frame = 0
                while (packets.size < 3 && System.nanoTime() < deadline) {
                    failure.get()?.let { throw it }
                    val canvas = input.lockCanvas(null)
                    try { canvas.drawColor(if (frame++ % 2 == 0) Color.BLUE else Color.GREEN) }
                    finally { input.unlockCanvasAndPost(canvas) }
                    val wait = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(60)
                    while (System.nanoTime() < wait) output.drain(encoder, 10_000)
                }
                assertTrue("Actual hardware encoder must produce frames", packets.size >= 3)
                assertEquals(size.width, checkNotNull(configuration).getInt("width"))
                assertEquals(size.height, checkNotNull(configuration).getInt("height"))
                assertTrue(packets.first().flags and 1 != 0)
                assertTrue(packets.all { it.session == 77L && it.generation == 1 && it.pts > 0 && it.payload.isNotEmpty() })
                assertTrue(packets.zipWithNext().all { (first, second) -> second.pts >= first.pts })
            } finally {
                try { renderer?.close() }
                finally { try { if (started) encoder.stop() } finally { encoder.release(); surface?.release() } }
            }
        }
    }
}
