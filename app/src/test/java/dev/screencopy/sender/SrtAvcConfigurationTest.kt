package dev.screencopy.sender

import dev.screencopy.sender.SrtSender.SrtAvcConfiguration
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

class SrtAvcConfigurationTest {
    private val portrait = WireTest.unhex("000000016742001ef40f028c800000000168ce3c80")
    private val landscape = WireTest.unhex("000000016742001ef40501ec800000000168ce3c80")
    private val idr = WireTest.unhex("0000000165888400000001060102")
    private val delta = WireTest.unhex("0000000141888401")

    @Test fun changesAndIdenticalRestartsAddOnlyRealParameterSetsWithoutChangingMedia() {
        val state = SrtAvcConfiguration()
        state.configure(portrait)
        assertSame(idr, state.sample(true, idr))
        assertSame(delta, state.sample(false, delta))
        for (config in listOf(landscape, portrait, portrait)) {
            state.configure(config)
            assertArrayEquals(config + idr, state.sample(true, idr))
            assertSame(delta, state.sample(false, delta))
        }
    }

    @Test fun noFakeKeysDropsOrSilentConfigurationRecovery() {
        val state = SrtAvcConfiguration()
        assertThrows(IOException::class.java) { state.sample(true, idr) }
        state.configure(portrait)
        assertThrows(IOException::class.java) { state.sample(false, idr) }
        assertThrows(IOException::class.java) { state.sample(true, delta) }
        state.sample(true, idr)
        state.configure(landscape)
        assertThrows(IOException::class.java) { state.sample(false, delta) }
        assertArrayEquals(landscape + idr, state.sample(true, idr))
        assertThrows(IOException::class.java) { state.configure(delta) }
    }

    @Test fun adapterRotationKeepsRawSourcePtsAcrossEncoderGenerations() {
        val state = SrtAvcConfiguration()
        val timeline = SrtSampleTimeline()
        val observed = mutableListOf<Long>()
        val samples = mutableListOf<ByteArray>()
        val configs = listOf(portrait, landscape, portrait)
        val pts = listOf(18_000_000_000L, 18_000_400_123L, 18_000_800_987L)
        val sink = SrtEncodedSink({ track, bytes, _, _ ->
            assertEquals(1, track); state.configure(bytes)
        }, { track, sourcePts, key, bytes ->
            val sample = state.sample(key, bytes)
            observed += timeline.presentationTime(track, sourcePts, key, sample)
            samples += sample
        })
        val source = ByteArrayOutputStream()
        DataOutputStream(source).apply {
            writeInt(0x68323634); writeInt(480); writeInt(640)
            for (index in configs.indices) {
                writeLong(Long.MIN_VALUE); writeInt(configs[index].size); write(configs[index])
                writeLong(pts[index] or (1L shl 62)); writeInt(idr.size); write(idr)
            }
        }
        assertThrows(EOFException::class.java) {
            ScrcpyAdapter.read(ByteArrayInputStream(source.toByteArray()), 1,
                Settings().apply { mediaTransportMode = "srt" }, 1, sink)
        }
        assertEquals(pts, observed)
        assertArrayEquals(idr, samples[0])
        assertArrayEquals(landscape + idr, samples[1])
        assertArrayEquals(portrait + idr, samples[2])
    }
}
