package dev.screencopy.sender

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

class SrtAdditionalCodecsTest {
    // AndroidX Media3 Apache-2.0 AV1 sequence-header test vector, 854x480.
    private val sequence = WireTest.unhex("0a0e00000024c6abdf3efe2404040410")

    @Test fun aacActualAscRawPayloadAndRationalTimelineRetainSourceOffsetAndFifo() {
        val asc = WireTest.unhex("1190")
        val actual = AacConfiguration.parse(asc)
        assertEquals(AacConfiguration(48000, 2, 1024), actual)
        assertEquals(960, AacConfiguration.parse(WireTest.unhex("1194")).samplesPerFrame)
        val timeline = SrtSampleTimeline(audioCodec = "aac")
        timeline.configureAudio(asc)
        val payload = byteArrayOf(1, 2, 3)
        val expected = listOf(1_000_000L, 1_021_333L, 1_042_667L, 1_064_000L)
        val observed = listOf(1_000_000L, 999_551L, 1L, 3L)
        assertEquals(expected, observed.map { pts -> timeline.presentationTime(2, pts, false, payload).also {
            assertEquals(pts, timeline.lastAudioSourcePtsUs)
        } })
        repeat(47) { timeline.presentationTime(2, 0, false, payload) }
        assertEquals(2_088_000L, timeline.presentationTime(2, 0, false, payload))
        var delivered = false
        SrtEncodedSink({ _, bytes, channels, rate ->
            assertArrayEquals(asc, bytes); assertEquals(2, channels); assertEquals(48000, rate)
        }, { _, pts, key, bytes ->
            assertSame(payload, bytes); assertEquals(999_551L, pts); assertFalse(key); delivered = true
        }, audioCodec = "aac").also {
            it.config(2, 1, CodecConfig.audio("aac", asc))
            it.packet(Wire.Message(Wire.AUDIO_PACKET, 0, 1, 2, 1, 999_551L, payload))
        }
        assertTrue(delivered)
    }

    @Test fun malformedOrUnsupportedAacNeverFallsBackOrAcceptsAdts() {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(0x11), WireTest.unhex("2990"),
            WireTest.unhex("1192"), WireTest.unhex("1191"), WireTest.unhex("119001")))
            assertThrows(IOException::class.java) { AacConfiguration.parse(bytes) }
        assertThrows(IOException::class.java) { AacConfiguration.accessUnit(WireTest.unhex("fff150")) }
        assertThrows(IOException::class.java) {
            SrtSampleTimeline(audioCodec = "aac").presentationTime(2, 0, false, byteArrayOf(1))
        }
        assertThrows(IOException::class.java) {
            SrtEncodedSink({ _, _, _, _ -> }, { _, _, _, _ -> }).config(2, 1, CodecConfig.audio("aac", WireTest.unhex("1190")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Settings().apply { mediaTransportMode = "srt"; audioCodec = "aac"; audioOptions = "aac-profile=5" }.validate()
        }
    }

    @Test fun av1AdapterRetainsRealObusDimensionsKeysAndRawPtsWithoutAvcParsing() {
        assertEquals(854 to 480, Av1Configuration.dimensions(sequence))
        val config = CodecConfig.video("av1", sequence)
        assertEquals(854, config.getInt("width")); assertEquals(480, config.getInt("height"))
        val frames = listOf(sequence + WireTest.unhex("320100"), WireTest.unhex("320110"))
        val received = mutableListOf<ByteArray>()
        val keys = mutableListOf<Boolean>()
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(0x00617631); output.writeInt(854); output.writeInt(480)
            output.writeLong(Long.MIN_VALUE); output.writeInt(sequence.size); output.write(sequence)
            frames.forEachIndexed { i, frame ->
                output.writeLong(1_000_000 + i * 16_667L or (if (i == 0) 1L shl 62 else 0))
                output.writeInt(frame.size); output.write(frame)
            }
        }
        assertThrows(EOFException::class.java) {
            ScrcpyAdapter.read(ByteArrayInputStream(bytes.toByteArray()), 1,
                Settings().apply { videoCodec = "av1" }, 1,
                SrtEncodedSink({ _, data, width, height ->
                    assertArrayEquals(sequence, data); assertEquals(854, width); assertEquals(480, height)
                }, { _, _, key, data -> received += data; keys += key }, videoCodec = "av1"))
        }
        assertEquals(listOf(true, false), keys)
        frames.forEachIndexed { i, frame -> assertArrayEquals(frame, received[i]) }
        val timeline = SrtSampleTimeline(videoCodec = "av1")
        assertEquals(1_000_000L, timeline.presentationTime(1, 1_000_000, true, frames[0]))
        assertEquals(1_016_667L, timeline.presentationTime(1, 1_016_667, false, frames[1]))
        assertThrows(IOException::class.java) { Av1Configuration.dimensions(WireTest.unhex("320100")) }
        assertThrows(IOException::class.java) { Av1Configuration.dimensions(sequence.copyOf(4)) }
        assertThrows(IOException::class.java) { Av1Configuration.obus(sequence + WireTest.unhex("3205ff")) }
        assertArrayEquals(sequence + frames[1], Av1Configuration.firstSample(sequence, frames[1]))
        assertArrayEquals(WireTest.unhex("1200") + sequence + frames[1],
            Av1Configuration.firstSample(sequence, WireTest.unhex("1200") + frames[1]))
    }
}
