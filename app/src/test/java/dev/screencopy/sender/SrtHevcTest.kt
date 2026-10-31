package dev.screencopy.sender

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

class SrtHevcTest {
    // AndroidX Media3 Apache-2.0 BoxesTest CSD; slice bytes below are parser-only envelopes.
    private val csd = WireTest.unhex("0000000140010c01ffff0408000003009fc800000300001e959809" +
        "000000014201010408000003009fc800000300001ec1882165959ae4cae68080000003008000000c84" +
        "000000014401c173d089")
    private val key = WireTest.unhex("000000012601888400")
    private val delta = WireTest.unhex("000000010201888401")
    private val portrait = WireTest.unhex(
        "0000000140010c01ffff016000000300b0000003000003007bac09" +
        "00000001420101016000000300b0000003000003007ba00220801e072f96bb9324ba948281010176850940" +
        "000000014401c0e30f09418f6108")
    // Same valid SPS syntax with coded width/height and conformance-window axes exchanged.
    private val landscape = WireTest.unhex(
        "0000000140010c01ffff016000000300b0000003000003007bac09" +
        "00000001420101016000000300b0000003000003007ba003c0801107cb96bb9324ba948281010176850940" +
        "000000014401c0e30f09418f6108")

    @Test fun actualParameterSetsAreNormalizedForOfficialHvccWithoutChangingCodedPictureNals() {
        val config = HevcConfiguration.parse(csd)
        val actual = CodecConfig.video("h265", csd)
        assertEquals(actual.getInt("width"), config.width)
        assertEquals(actual.getInt("height"), config.height)
        assertArrayEquals(csd, config.csd)
        assertArrayEquals(key, config.sample(true, csd + key))
        assertArrayEquals(delta, config.sample(false, delta))
        val sets = CodecConfig.annexB(csd)
        val reordered = sets.reversed().fold(byteArrayOf()) { bytes, nal -> bytes + byteArrayOf(0, 0, 0, 1) + nal }
        assertArrayEquals(csd, HevcConfiguration.parse(reordered).csd)
        assertThrows(IOException::class.java) { HevcConfiguration.parse(byteArrayOf(0, 0, 0, 1) + sets[1]) }
        assertThrows(IOException::class.java) { config.sample(true, delta) }
        assertThrows(IOException::class.java) { config.sample(false, key) }
        assertThrows(IOException::class.java) { config.sample(true, csd) }
        assertThrows(IOException::class.java) { config.sample(true, WireTest.unhex("0000000126098884")) }
    }

    @Test fun rotationActivatesCompleteParametersOnlyAtAuthenticIrapAndKeepsRawTimeline() {
        val state = SrtHevcConfiguration()
        assertThrows(IOException::class.java) { state.sample(true, key) }
        val timeline = SrtSampleTimeline(videoCodec = "h265")
        for ((index, config) in listOf(portrait, landscape, portrait).withIndex()) {
            val parsed = HevcConfiguration.parse(config)
            assertEquals(if (index == 1) 1920 else 1080, parsed.width)
            assertEquals(if (index == 1) 1080 else 1920, parsed.height)
            state.configure(config); state.configure(config)
            assertTrue(state.requiresIrap)
            assertThrows(IOException::class.java) { state.sample(false, delta) }
            assertThrows(IOException::class.java) { state.sample(false, key) }
            assertThrows(IOException::class.java) { state.sample(true, delta) }
            val wrong = if (index == 1) portrait else landscape
            assertThrows(IOException::class.java) { state.sample(true, wrong + key) }
            val sample = state.sample(true, key)
            assertArrayEquals(config + key, sample)
            assertFalse(state.requiresIrap)
            val pts = 18_000_000_000L + index * 4_000_123L
            assertEquals(pts, timeline.presentationTime(1, pts, true, sample))
            assertArrayEquals(delta, state.sample(false, delta))
        }
        state.configure(portrait)
        assertArrayEquals(delta, state.sample(false, delta))
        assertThrows(IOException::class.java) { state.configure(key) }
        assertThrows(IOException::class.java) { state.sample(false, portrait + delta) }
        assertArrayEquals(portrait + key, state.sample(true, portrait + key))
    }

    @Test fun normalScrcpyHevcIdsAndSrtAdapterRetainSelectedCodecActualCsdKeysAndRawPts() {
        val config = CodecConfig.video("h265", csd)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(0x68323635); out.writeInt(config.getInt("width")); out.writeInt(config.getInt("height"))
            out.writeLong(Long.MIN_VALUE); out.writeInt(csd.size); out.write(csd)
            for ((index, frame) in listOf(key, delta).withIndex()) {
                out.writeLong((1_000_000L + index * 2_000_000L) or (if (index == 0) (1L shl 62) else 0L))
                out.writeInt(frame.size); out.write(frame)
            }
        }
        val pts = mutableListOf<Long>()
        val keys = mutableListOf<Boolean>()
        val samples = mutableListOf<ByteArray>()
        val sink = SrtEncodedSink({ track, data, _, _ -> assertEquals(1, track); assertArrayEquals(csd, data) },
            { _, time, sync, data -> pts += time; keys += sync; samples += data }, videoCodec = "h265")
        assertThrows(EOFException::class.java) {
            ScrcpyAdapter.read(ByteArrayInputStream(bytes.toByteArray()), 1,
                Settings().apply { videoCodec = "h265"; mediaTransportMode = "srt"; audio = false }, 1, sink)
        }
        assertEquals(listOf(1_000_000L, 3_000_000L), pts)
        assertEquals(listOf(true, false), keys)
        assertArrayEquals(key, samples[0]); assertArrayEquals(delta, samples[1])
        val timeline = SrtSampleTimeline(videoCodec = "h265")
        assertEquals(1_000_000L, timeline.presentationTime(1, pts[0], keys[0], samples[0]))
        assertEquals(3_000_000L, timeline.presentationTime(1, pts[1], keys[1], samples[1]))
    }
}
