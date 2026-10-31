package dev.screencopy.sender

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Synthetic loopback only. No UI, capture service, permission grant or Shizuku. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NativeSrtTest {
    @Test fun closedNativeFailureIncludesSnapshotWithoutTurningCancellationIntoSuccess() {
        assumeTrue(BuildConfig.SRT_TRIAL)
        val handle = SrtNative.listen("127.0.0.1", "127.0.0.1", "sc-0000000000000001", 100)
        try {
            SrtNative.close(handle)
            val error = assertThrows(IOException::class.java) { SrtNative.read(handle, ByteArray(1), 0, 1) }
            val evidence = SrtFailureDiagnostics.evidence(error)
            assertEquals("CLOSED", evidence.getString("category"))
            assertEquals("closed", evidence.getJSONObject("native_snapshot").getString("snapshot_unavailable"))
            val snapshot = SrtFailureDiagnostics.snapshot(SrtNative.snapshot(handle))
            assertEquals("unavailable", snapshot.getString("snapshot"))
            assertEquals("CLOSED", snapshot.getString("state"))
        } finally {
            SrtNative.close(handle)
        }
    }

    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private data class Box(val type: String, val start: Int, val payload: Int, val end: Int)
    private data class Sample(
        val track: Long, val time: Long, val duration: Long, val flags: Long,
        val offset: Int, val size: Int
    )
    private data class Run(val track: Long, val baseTime: Long, val samples: List<Sample>)

    private class Mp4(val bytes: ByteArray) {
        fun int(at: Int): Int = ByteBuffer.wrap(bytes, at, 4).int
        fun uint(at: Int): Long = int(at).toLong() and 0xffffffffL
        fun long(at: Int): Long = ByteBuffer.wrap(bytes, at, 8).long.also {
            assertTrue("64-bit box field exceeds signed test range", it >= 0)
        }
        fun ushort(at: Int): Int = ByteBuffer.wrap(bytes, at, 2).short.toInt() and 65535
        fun text(at: Int): String = String(bytes, at, 4, Charsets.US_ASCII)

        fun boxes(start: Int = 0, end: Int = bytes.size, partial: Boolean = false): List<Box> {
            val result = mutableListOf<Box>()
            var at = start
            while (at < end) {
                if (end - at < 8 && partial) break
                assertTrue("Truncated box header at $at", end - at >= 8)
                val header = if (uint(at) == 1L) 16 else 8
                if (end - at < header && partial) break
                assertTrue("Truncated extended box header at $at", end - at >= header)
                val size = if (header == 16) long(at + 8) else uint(at)
                assertTrue("Invalid/open-ended box size at $at", size >= header && size <= Int.MAX_VALUE)
                if (size > end - at && partial) break
                assertTrue("Truncated ${text(at + 4)} at $at", size <= end - at)
                result += Box(text(at + 4), at, at + header, at + size.toInt())
                at += size.toInt()
            }
            return result
        }

        fun children(box: Box): List<Box> = boxes(box.payload, box.end)
        fun child(box: Box, type: String): Box = children(box).single { it.type == type }

        fun runs(top: List<Box> = boxes()): List<Run> {
            val result = mutableListOf<Run>()
            for ((index, moof) in top.withIndex().filter { it.value.type == "moof" }) {
                assertTrue("moof must be followed by complete mdat", index + 1 < top.size)
                val mdat = top[index + 1]
                assertEquals("mdat", mdat.type)
                val fragmentSamples = mutableListOf<Sample>()
                for (traf in children(moof).filter { it.type == "traf" }) {
                    val tfhd = child(traf, "tfhd")
                    // This muxer emits explicit base-data-offset, not implicit track defaults.
                    assertEquals(1L, uint(tfhd.payload))
                    assertEquals(16, tfhd.end - tfhd.payload)
                    val track = uint(tfhd.payload + 4)
                    val baseOffset = long(tfhd.payload + 8)
                    val tfdt = child(traf, "tfdt")
                    assertEquals(0x01000000L, uint(tfdt.payload))
                    assertEquals(12, tfdt.end - tfdt.payload)
                    val baseTime = long(tfdt.payload + 4)
                    var time = baseTime
                    val samples = mutableListOf<Sample>()
                    for (trun in children(traf).filter { it.type == "trun" }) {
                        val version = uint(trun.payload) ushr 24
                        val flags = int(trun.payload) and 0xffffff
                        assertTrue("Unsupported trun version", version in 0L..1L)
                        assertEquals("Explicit data offset, durations and sizes", 0x301, flags and 0x301)
                        assertEquals("Unexpected trun flags", 0, flags and 0xfff0fa)
                        val count = uint(trun.payload + 4)
                        assertTrue("Nonempty bounded sample table", count in 1L..1000L)
                        var cursor = trun.payload + 8
                        var offset = baseOffset + int(cursor)
                        cursor += 4
                        val firstFlags = if (flags and 4 != 0) uint(cursor).also { cursor += 4 } else 0L
                        repeat(count.toInt()) { sampleIndex ->
                            val duration = uint(cursor)
                            val size = uint(cursor + 4)
                            cursor += 8
                            val sampleFlags = if (flags and 0x400 != 0)
                                uint(cursor).also { cursor += 4 } else if (sampleIndex == 0) firstFlags else 0L
                            if (flags and 0x800 != 0) {
                                assertEquals("No composition offset for this fixture", 0, int(cursor))
                                cursor += 4
                            }
                            assertTrue("Positive sample duration", duration > 0)
                            assertTrue("Sample range must belong to its mdat",
                                size > 0 && offset >= mdat.payload && offset <= mdat.end &&
                                    size <= mdat.end - offset)
                            samples += Sample(track, time, duration, sampleFlags, offset.toInt(), size.toInt())
                            offset += size
                            time += duration
                        }
                        assertEquals("trun field sizes/sample count", trun.end, cursor)
                    }
                    assertTrue("traf must contain samples", samples.isNotEmpty())
                    result += Run(track, baseTime, samples)
                    fragmentSamples += samples
                }
                var next = mdat.payload
                for (sample in fragmentSamples.sortedBy { it.offset }) {
                    assertEquals("No duplicate, omitted or overlapping payload bytes", next, sample.offset)
                    next += sample.size
                }
                assertEquals("Every mdat byte belongs to exactly one sample", mdat.end, next)
            }
            return result
        }
    }

    private fun receiveFragments(caller: Long, output: ByteArrayOutputStream, audioSamples: Int): ByteArray {
        val buffer = ByteArray(4096)
        while (true) {
            val bytes = output.toByteArray()
            val complete = Mp4(bytes).boxes(partial = true)
            val end = complete.lastOrNull { it.type == "mdat" }?.end
            if (end != null) {
                // Retain any partial next box in output for the next receive phase.
                val snapshot = bytes.copyOfRange(0, end)
                val parsed = Mp4(snapshot)
                if (parsed.runs().sumOf { run -> run.samples.count { it.track == 2L } } >= audioSamples)
                    return snapshot
            }
            val count = SrtNative.read(caller, buffer, 0, buffer.size)
            if (count < 1) throw IOException("EOF before $audioSamples complete Opus samples")
            output.write(buffer, 0, count)
        }
    }

    private fun assertFragmentSamples(bytes: ByteArray, silent: ByteArray, audioCount: Int) {
        val mp4 = Mp4(bytes)
        val top = mp4.boxes()
        assertEquals("ftyp", top.first().type)
        assertEquals(1, top.count { it.type == "ftyp" })
        assertEquals(1, top.count { it.type == "moov" })
        assertTrue(top.all { it.type in setOf("ftyp", "moov", "moof", "mdat") })
        assertTrue(top.count { it.type == "moof" } > 0)
        assertEquals(top.count { it.type == "moof" }, top.count { it.type == "mdat" })
        val moov = top.single { it.type == "moov" }
        assertEquals("Init precedes every fragment", 1, top.indexOf(moov))
        val tracks = mp4.children(moov).filter { it.type == "trak" }
        assertEquals(2, tracks.size)
        val handlers = mutableMapOf<Long, String>()
        for (trak in tracks) {
            val tkhd = mp4.child(trak, "tkhd")
            assertEquals(0L, mp4.uint(tkhd.payload) ushr 24)
            val id = mp4.uint(tkhd.payload + 12)
            val mdia = mp4.child(trak, "mdia")
            val mdhd = mp4.child(mdia, "mdhd")
            assertEquals(0L, mp4.uint(mdhd.payload) ushr 24)
            val hdlr = mp4.child(mdia, "hdlr")
            val handler = mp4.text(hdlr.payload + 8)
            assertNull("Unique track ID", handlers.put(id, handler))
            assertEquals(if (handler == "vide") 90_000L else 48_000L, mp4.uint(mdhd.payload + 12))
            if (handler == "vide") {
                val stsd = mp4.child(mp4.child(mp4.child(mdia, "minf"), "stbl"), "stsd")
                assertEquals("avc3", mp4.boxes(stsd.payload + 8, stsd.end).single().type)
            }
            if (handler == "soun") {
                val stbl = mp4.child(mp4.child(mdia, "minf"), "stbl")
                val stsd = mp4.child(stbl, "stsd")
                assertEquals(1L, mp4.uint(stsd.payload + 4))
                val entry = mp4.boxes(stsd.payload + 8, stsd.end).single()
                assertEquals("Opus", entry.type)
                assertEquals(2, mp4.ushort(entry.payload + 16))
                assertEquals(48_000L shl 16, mp4.uint(entry.payload + 24))
                val dops = mp4.boxes(entry.payload + 28, entry.end).single { it.type == "dOps" }
                assertEquals("Standard dOps, not raw OpusHead", 11, dops.end - dops.payload)
                assertEquals(0, bytes[dops.payload].toInt())
                assertEquals(2, bytes[dops.payload + 1].toInt())
                assertEquals(312, mp4.ushort(dops.payload + 2))
                assertEquals(48_000L, mp4.uint(dops.payload + 4))
                assertEquals(0, mp4.ushort(dops.payload + 8))
                assertEquals(0, bytes[dops.payload + 10].toInt())
            }
        }
        assertEquals(mapOf(1L to "vide", 2L to "soun"), handlers)
        val runs = mp4.runs(top)
        assertTrue(runs.all { it.track in handlers })
        val samples = runs.flatMap { it.samples }
        val video = samples.filter { it.track == 1L }.single()
        assertEquals(0L, runs.first { it.track == 1L }.baseTime)
        assertEquals(0L, video.time)
        assertEquals("Exactly one actual video keyframe", 0x02000000L, video.flags)
        assertArrayEquals(hex("0000000465888400"), bytes.copyOfRange(video.offset, video.offset + video.size))
        assertEquals("Initial 100ms audio offset in 48kHz ticks", 4800L, runs.first { it.track == 2L }.baseTime)
        val audio = samples.filter { it.track == 2L }
        assertEquals(audioCount, audio.size)
        for ((index, sample) in audio.withIndex()) {
            assertEquals("FIFO time despite regressing observed PTS", 4800L + index * 960L, sample.time)
            assertEquals("Actual 20ms Opus duration at 48kHz", 960L, sample.duration)
            assertEquals("Every Opus access unit is an MP4 sync sample", 0x02000000L, sample.flags)
            assertArrayEquals(silent, bytes.copyOfRange(sample.offset, sample.offset + sample.size))
        }
        for (run in runs) assertEquals(run.samples.first().time, run.baseTime)
    }

    @Test fun staticVideoAndRegressingOpusObservationsFlushWithoutAnotherVideoFrame() {
        assumeTrue(BuildConfig.SRT_TRIAL)
        val executor = Executors.newSingleThreadExecutor()
        val sender = SrtSender("127.0.0.1", "127.0.0.1", 1,
            Settings().apply { mediaTransportMode = "srt" }, {})
        val caller = SrtNative.createCaller("sc-0000000000000001", 100)
        try {
            SrtNative.connect(caller, "127.0.0.1", sender.port, 5000)
            val sps = hex("000000016742001ef40501ec800000000168ce3c80")
            sender.config(1, 1, Wire.obj("codec" to "h264", "width" to 640, "height" to 480,
                "csd" to org.json.JSONArray().put(java.util.Base64.getEncoder().encodeToString(sps))))
            val head = hex("4f707573486561640102380180bb0000000000")
            sender.config(2, 1, CodecConfig.audio("opus", head))
            val silent = hex("f8fffe")
            val output = ByteArrayOutputStream()
            val receiving = executor.submit(Callable {
                receiveFragments(caller, output, 3)
            })
            sender.packet(Wire.Message(Wire.VIDEO_FRAME, 1, 1, 1, 1, 900_000, hex("0000000165888400")))
            for (pts in listOf(1_000_000L, 999_551L, 1_039_551L))
                sender.packet(Wire.Message(Wire.AUDIO_PACKET, 0, 1, 2, 1, pts, silent))
            val bytes = receiving.get(8, TimeUnit.SECONDS)
            assertFragmentSamples(bytes, silent, 3)
            val continuing = executor.submit(Callable {
                receiveFragments(caller, output, 6)
            })
            Thread.sleep(250)
            for (pts in listOf(1_059_551L, 1_079_551L, 1_099_551L))
                sender.packet(Wire.Message(Wire.AUDIO_PACKET, 0, 1, 2, 1, pts, silent))
            val afterIdle = continuing.get(5, TimeUnit.SECONDS)
            assertFragmentSamples(afterIdle, silent, 6)
            assertArrayEquals(bytes, afterIdle.copyOfRange(0, bytes.size))
            assertTrue("Audio flushes in a new fragment without another video/keyframe",
                Mp4(afterIdle).boxes().count { it.type == "moof" } > Mp4(bytes).boxes().count { it.type == "moof" })
            sender.checkHealth()
            sender.config(2, 2, CodecConfig.audio("opus", head)) // Same configuration is harmless.
        } finally {
            try { sender.close() }
            finally {
                SrtNative.close(caller)
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun boundedQueuesRejectOverflowAndCloseCancelsPendingAccept() {
        assumeTrue(BuildConfig.SRT_TRIAL)
        val sender = SrtSender("127.0.0.1", "127.0.0.1", 3,
            Settings().apply { mediaTransportMode = "srt"; audio = false }, {})
        try {
            repeat(240) {
                sender.packet(Wire.Message(Wire.VIDEO_FRAME, if (it == 0) 1 else 0,
                    3, 1, 1, it.toLong(), hex("0000000165888400")))
            }
            assertThrows(IOException::class.java) {
                sender.packet(Wire.Message(Wire.VIDEO_FRAME, 0, 3, 1, 1, 241, hex("0000000141888400")))
            }
        } finally { sender.close() }
    }

    @Test fun rotationUsesOneAvc3InitAndPreservesRawNalsPtsAndOpus() {
        assumeTrue(BuildConfig.SRT_TRIAL)
        val executor = Executors.newSingleThreadExecutor()
        val sender = SrtSender("127.0.0.1", "127.0.0.1", 4,
            Settings().apply { mediaTransportMode = "srt" }, {})
        val caller = SrtNative.createCaller("sc-0000000000000004", 100)
        try {
            SrtNative.connect(caller, "127.0.0.1", sender.port, 5000)
            val configs = listOf(
                hex("000000016742001ef40f028c800000000168ce3c80"),
                hex("000000016742001ef40501ec800000000168ce3c80"),
                hex("000000016742001ef40f028c800000000168ce3c80"))
            val idr = hex("0000000165888400")
            val opus = hex("f8fffe")
            val head = hex("4f707573486561640102380180bb0000000000")
            sender.config(2, 1, CodecConfig.audio("opus", head))
            val receiving = executor.submit(Callable { receiveFragments(caller, ByteArrayOutputStream(), 9) })
            for ((index, config) in configs.withIndex()) {
                sender.config(1, index + 1, CodecConfig.video("h264", config))
                sender.packet(Wire.Message(Wire.VIDEO_FRAME, 1, 4, 1, index + 1,
                    1_000_000L + index * 4_000_000L, idr))
                repeat(3) {
                    val packet = index * 3 + it
                    sender.packet(Wire.Message(Wire.AUDIO_PACKET, 0, 4, 2, 1,
                        1_100_000L + packet * 20_000L - if (packet == 1) 20_449 else 0, opus))
                }
            }
            val bytes = receiving.get(8, TimeUnit.SECONDS)
            sender.checkHealth()
            val mp4 = Mp4(bytes)
            val top = mp4.boxes()
            assertEquals(1, top.count { it.type == "ftyp" })
            assertEquals(1, top.count { it.type == "moov" })
            val videoTrak = mp4.children(top.single { it.type == "moov" }).filter { it.type == "trak" }.single {
                mp4.text(mp4.child(mp4.child(it, "mdia"), "hdlr").payload + 8) == "vide"
            }
            val stsd = mp4.child(mp4.child(mp4.child(mp4.child(videoTrak, "mdia"), "minf"), "stbl"), "stsd")
            assertEquals("avc3", mp4.boxes(stsd.payload + 8, stsd.end).single().type)
            val video = mp4.runs(top).flatMap { it.samples }.filter { it.track == 1L }
            assertEquals(listOf(0L, 360_000L, 720_000L), video.map { it.time })
            for ((index, sample) in video.withIndex()) {
                val annexB = if (index == 0) idr else configs[index] + idr
                val expected = ByteArrayOutputStream()
                java.io.DataOutputStream(expected).use { out ->
                    for (nal in CodecConfig.annexB(annexB)) { out.writeInt(nal.size); out.write(nal) }
                }
                assertArrayEquals(expected.toByteArray(), bytes.copyOfRange(sample.offset, sample.offset + sample.size))
                assertEquals(0x02000000L, sample.flags)
            }
            val audio = mp4.runs(top).flatMap { it.samples }.filter { it.track == 2L }
            assertEquals(9, audio.size)
            for ((index, sample) in audio.withIndex()) {
                assertEquals(4800L + index * 960L, sample.time)
                assertEquals(960L, sample.duration)
                assertEquals(0x02000000L, sample.flags)
                assertArrayEquals(opus, bytes.copyOfRange(sample.offset, sample.offset + sample.size))
            }
            // Unchanged audio is retained; actual Opus configuration changes still fail explicitly.
            sender.config(2, 2, CodecConfig.audio("opus", head.copyOf().also { it[9] = 1 }))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var rejected = false
            while (!rejected && System.nanoTime() < deadline) {
                try { sender.checkHealth() }
                catch (e: IOException) { assertTrue(e.message.orEmpty().startsWith("UNSUPPORTED_CONFIG:")); rejected = true }
                if (!rejected) Thread.sleep(5)
            }
            assertTrue("Changed Opus config must remain unsupported", rejected)
        } finally {
            try { sender.close() }
            catch (e: IOException) { assertTrue(e.message.orEmpty().startsWith("UNSUPPORTED_CONFIG:")) }
            finally {
                SrtNative.close(caller)
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun hevcRotationUsesOneHev1InitAndPreservesParametersPtsKeysAndOpus() {
        assumeTrue(BuildConfig.SRT_TRIAL)
        val portrait = hex("0000000140010c01ffff016000000300b0000003000003007bac09" +
            "00000001420101016000000300b0000003000003007ba00220801e072f96bb9324ba948281010176850940" +
            "000000014401c0e30f09418f6108")
        val landscape = hex("0000000140010c01ffff016000000300b0000003000003007bac09" +
            "00000001420101016000000300b0000003000003007ba003c0801107cb96bb9324ba948281010176850940" +
            "000000014401c0e30f09418f6108")
        val configurations = listOf(portrait, landscape, portrait)
        // Parser-only IRAP envelope; actual decoder acceptance belongs to the hardware clip fixture.
        val irap = hex("000000012601888400")
        val opus = hex("f8fffe")
        val executor = Executors.newSingleThreadExecutor()
        val sender = SrtSender("127.0.0.1", "127.0.0.1", 5,
            Settings().apply { mediaTransportMode = "srt"; videoCodec = "h265" }, {})
        val caller = SrtNative.createCaller("sc-0000000000000005", 100)
        try {
            SrtNative.connect(caller, "127.0.0.1", sender.port, 5000)
            sender.config(2, 1, CodecConfig.audio("opus", EncodedTestPackets.canonicalOpusHead()))
            val receiving = executor.submit(Callable { receiveFragments(caller, ByteArrayOutputStream(), 9) })
            for ((index, config) in configurations.withIndex()) {
                sender.config(1, index + 1, CodecConfig.video("h265", config))
                sender.packet(Wire.Message(Wire.VIDEO_FRAME, 1, 5, 1, index + 1,
                    1_000_000L + index * 4_000_000L, irap))
                repeat(3) {
                    sender.packet(Wire.Message(Wire.AUDIO_PACKET, 0, 5, 2, 1,
                        1_100_000L + (index * 3 + it) * 20_000L, opus))
                }
            }
            val bytes = receiving.get(8, TimeUnit.SECONDS)
            sender.checkHealth()
            val mp4 = Mp4(bytes)
            val top = mp4.boxes()
            assertEquals(1, top.count { it.type == "ftyp" })
            assertEquals(1, top.count { it.type == "moov" })
            val videoTrak = mp4.children(top.single { it.type == "moov" }).filter { it.type == "trak" }.single {
                mp4.text(mp4.child(mp4.child(it, "mdia"), "hdlr").payload + 8) == "vide"
            }
            val stsd = mp4.child(mp4.child(mp4.child(mp4.child(videoTrak, "mdia"), "minf"), "stbl"), "stsd")
            val entry = mp4.boxes(stsd.payload + 8, stsd.end).single()
            assertEquals("hev1", entry.type)
            val hvcc = mp4.boxes(entry.payload + 78, entry.end).single { it.type == "hvcC" }
            assertEquals(3, bytes[hvcc.payload + 21].toInt() and 3)
            assertEquals(3, bytes[hvcc.payload + 22].toInt())
            val runs = mp4.runs(top)
            val video = runs.flatMap { it.samples }.filter { it.track == 1L }
            assertEquals(listOf(0L, 360_000L, 720_000L), video.map { it.time })
            for ((index, sample) in video.withIndex()) {
                val expected = ByteArrayOutputStream()
                java.io.DataOutputStream(expected).use { out ->
                    for (nal in CodecConfig.annexB(configurations[index] + irap)) {
                        out.writeInt(nal.size); out.write(nal)
                    }
                }
                assertArrayEquals(expected.toByteArray(), bytes.copyOfRange(sample.offset, sample.offset + sample.size))
                assertEquals(0x02000000L, sample.flags)
            }
            val audio = runs.flatMap { it.samples }.filter { it.track == 2L }
            assertEquals(9, audio.size)
            for ((index, sample) in audio.withIndex()) {
                assertEquals(4800L + index * 960L, sample.time)
                assertEquals(960L, sample.duration)
                assertArrayEquals(opus, bytes.copyOfRange(sample.offset, sample.offset + sample.size))
            }
        } finally {
            try { sender.close() }
            finally {
                SrtNative.close(caller)
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun closingTransportInterruptsAwaitWithoutLeakingWorker() {
        assumeTrue(BuildConfig.SRT_TRIAL)
        val executor = Executors.newSingleThreadExecutor()
        val listener = SrtNative.listen("127.0.0.1", "127.0.0.1", "sc-0000000000000002", 100)
        try {
            val pending = executor.submit(Callable {
                try { SrtNative.awaitCaller(listener, 10000) }
                catch (_: IOException) { false }
            })
            SrtNative.close(listener)
            assertFalse(pending.get(2, TimeUnit.SECONDS))
        } finally {
            SrtNative.close(listener)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        }
    }
}
