package dev.screencopy.sender

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

class ScrcpyAudioTest {
    @Test fun allAudioCodecsKeepActualFramingCsdAbsolutePtsAndSharedReadinessGate() {
        for ((codec, id, csd) in listOf(
            Triple("opus", 0x6f707573, WireTest.unhex("4f707573486561640102380180bb0000000000")),
            Triple("aac", 0x00616163, byteArrayOf(0x11, 0x90.toByte())),
            Triple("raw", 0x00726177, byteArrayOf()))) {
            val settings = Settings().apply { audioCodec = codec }
            val bytes = ByteArrayOutputStream()
            val pts = 18_000_000_000L
            DataOutputStream(bytes).apply {
                writeInt(id)
                if (csd.isNotEmpty()) { writeLong(Long.MIN_VALUE); writeInt(csd.size); write(csd) }
                writeLong(pts); writeInt(4); write(byteArrayOf(1, 2, 3, 4))
            }
            val mux = MediaBudget(true)
            mux.offer(Wire.config(1, 1, 1, Wire.obj("codec" to "h264")))
            val error = assertThrows(EOFException::class.java) {
                ScrcpyAdapter.read(ByteArrayInputStream(bytes.toByteArray()), 2, settings, 1, object : ScrcpyAdapter.Sink {
                    override fun config(track: Int, generation: Int, config: JSONObject) {
                        assertEquals(codec, config.getString("codec"))
                        assertEquals(48000, config.getInt("sampleRate")); assertEquals(2, config.getInt("channels"))
                        mux.offer(Wire.config(1, track, generation, config))
                    }
                    override fun packet(packet: Wire.Message) { mux.offer(packet) }
                })
            }
            assertTrue(error.message.orEmpty().startsWith("AUDIO_FAILED:"))
            assertTrue(error.cause is EOFException)
            assertEquals(1, mux.poll(1)?.track); assertEquals(2, mux.poll(1)?.track)
            mux.ready(1, 1); assertNull(mux.poll(1))
            mux.ready(2, 1)
            val packet = checkNotNull(mux.poll(1))
            assertEquals(pts, packet.pts); assertEquals(1, packet.generation); assertEquals(2, packet.track)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), packet.payload)
        }
    }
    @Test fun upstreamAudioDisableAndMissingCsdRemainFatalRatherThanVideoOnlyFallback() {
        for (code in listOf(0, 1, 0x00616163)) {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).apply {
                writeInt(code)
                if (code > 1) { writeLong(100); writeInt(1); writeByte(1) }
            }
            val error = assertThrows(IOException::class.java) {
                ScrcpyAdapter.read(ByteArrayInputStream(bytes.toByteArray()), 2, Settings().apply { audioCodec = "aac" }, 1,
                    object : ScrcpyAdapter.Sink {
                        override fun config(track: Int, generation: Int, config: JSONObject) { fail("Unexpected config") }
                        override fun packet(packet: Wire.Message) { fail("Unexpected media") }
                    })
            }
            assertTrue(error.message.orEmpty().contains(if (code <= 1) "AUDIO_FAILED" else "before codec initialization"))
        }
    }
    @Test fun rawFrameAlignmentIsCheckedBeforeDelivery() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply { writeInt(0x00726177); writeLong(100); writeInt(3); write(byteArrayOf(1, 2, 3)) }
        val error = assertThrows(IOException::class.java) {
            ScrcpyAdapter.read(ByteArrayInputStream(bytes.toByteArray()), 2, Settings().apply { audioCodec = "raw" }, 1,
                object : ScrcpyAdapter.Sink {
                    override fun config(track: Int, generation: Int, config: JSONObject) = Unit
                    override fun packet(packet: Wire.Message) { fail("Misaligned PCM delivered") }
                })
        }
        assertTrue(error.message.orEmpty().contains("unaligned stereo PCM"))
    }
}
