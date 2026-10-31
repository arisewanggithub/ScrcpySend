package dev.screencopy.sender

import android.content.SharedPreferences
import androidx.media3.muxer.MuxerException
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.File
import org.json.JSONObject
import java.net.URI

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SrtTransportTest {
    @Test fun nativeFailureSnapshotPreservesFirstCodeAndDropsUntrustedFields() {
        val root = IOException("SRT send: native failure (code 2001) | snapshot snapshot=available state=6 " +
            "packets_sent=10 bytes_received=99 send_buffer_packets=7 send_buffer_bytes=1316 receive_buffer_bytes=0 rtt_ms=2.000000 " +
            "last_peer_response_ms=unavailable peer=srt://private.invalid/password")
        val safe = SrtFailureDiagnostics.sanitize(IOException("CAPTURE_FAILED: fMP4 mux failed", root))
        assertTrue(safe.message!!.contains("category=BROKEN operation=SEND code=2001"))
        assertSame(root, safe.cause?.cause)
        val evidence = SrtFailureDiagnostics.evidence(safe)
        assertEquals(2001, evidence.getInt("native_code"))
        val stats = evidence.getJSONObject("native_snapshot")
        assertEquals("available", stats.getString("snapshot"))
        assertEquals("6", stats.getString("state"))
        assertEquals("7", stats.getString("send_buffer_packets"))
        assertEquals("1316", stats.getString("send_buffer_bytes"))
        assertEquals("0", stats.getString("receive_buffer_bytes"))
        assertEquals("unavailable", stats.getString("last_peer_response_ms"))
        assertFalse(stats.has("peer"))
        assertFalse(evidence.toString().contains("private.invalid"))
        val closed = IOException("SRT connection closed | snapshot snapshot=unavailable snapshot_unavailable=closed state=CLOSED")
        assertTrue(SrtFailureDiagnostics.sanitize(closed).message!!.contains("category=CLOSED operation=CONNECTION"))
        assertEquals("closed", SrtFailureDiagnostics.evidence(closed).getJSONObject("native_snapshot").getString("snapshot_unavailable"))
    }

    @Test fun failedSnapshotQueryCannotReplaceOriginalFailureAndRecordedNativeSnapshotIsNotResampled() {
        val original = IOException("TIMEOUT: no SRT caller")
        val withStats = SrtFailureDiagnostics.withSnapshot(original) { throw IOException("query private URL") }
        assertSame(original, withStats.cause)
        assertEquals("query_error", SrtFailureDiagnostics.evidence(withStats).getJSONObject("native_snapshot").getString("snapshot_unavailable"))
        assertEquals("TIMEOUT", SessionOutcome.errorCode(SrtFailureDiagnostics.sanitize(withStats)))
        assertFalse(SrtFailureDiagnostics.evidence(withStats).toString().contains("private URL"))
        val recorded = IOException("SRT send: native failure (code 2001) | snapshot snapshot=unavailable snapshot_unavailable=stats_error state=6 snapshot_error_code=5004 last_peer_response_ms=unavailable")
        assertSame(recorded, SrtFailureDiagnostics.withSnapshot(recorded) { fail("First native snapshot must not be resampled"); "" })
        val evidence = SrtFailureDiagnostics.evidence(recorded)
        assertEquals(2001, evidence.getInt("native_code"))
        assertEquals("5004", evidence.getJSONObject("native_snapshot").getString("snapshot_error_code"))
    }

    @Test fun nestedMuxOutputFailureReachesUiAndDiagnosticsWithoutLosingOriginalCause() {
        val privateText = "https://private-peer.invalid/path?password=secret&payload=captured"
        val output = IOException("SRT send made no progress within one second").apply {
            stackTrace = arrayOf(
                StackTraceElement("dev.screencopy.sender.SrtNative", "write", privateText, 108),
                StackTraceElement("private.peer.Secret", "send", privateText, 9))
        }
        val mux = MuxerException(privateText, output)
        val original = IOException("CAPTURE_FAILED: fMP4 mux failed", mux)
        val safe = SrtFailureDiagnostics.sanitize(original)
        assertEquals("CAPTURE_FAILED: SRT failure stage=FMP4_MUX root=IO category=NO_PROGRESS operation=SEND code=NONE",
            safe.message)
        assertSame(original, safe.cause)
        assertSame(mux, safe.cause?.cause)
        assertSame(output, safe.cause?.cause?.cause)
        val lines = SrtFailureDiagnostics.lines(original, safe)
        assertEquals("srt_failed ${safe.message}", lines.first())
        assertTrue(lines.contains("srt_failure_cause depth=1 type=MUXER category=MUXER operation=NONE code=NONE"))
        assertTrue(lines.contains("srt_failure_cause depth=2 type=IO category=NO_PROGRESS operation=SEND code=NONE"))
        assertTrue(lines.contains("srt_failure_stack depth=2 frame=0 at=dev.screencopy.sender.SrtNative.write line=108"))
        assertFalse(lines.joinToString().contains(privateText))
        assertFalse(lines.joinToString().contains("private.peer.Secret"))
        val outcome = SessionOutcome()
        assertTrue(outcome.record(safe, SessionOutcome.Origin.CAPTURE))
        val terminal = outcome.terminal(1, false)
        assertEquals(Wire.ERROR, terminal.type)
        assertEquals("CAPTURE_FAILED", terminal.json().getString("code"))
        assertEquals(safe.message, terminal.json().getString("message"))
        assertTrue(terminal.json().getBoolean("fatal"))
        outcome.requestStop()
        assertFalse(outcome.record(SessionCancelledException(), SessionOutcome.Origin.CLEANUP, closing = true))
        assertSame(safe, outcome.failure)
    }

    @Test fun nativeFailureClassificationNeverCopiesLibraryOrPeerText() {
        val privateText = "srt://private-peer.invalid:9000?streamid=credential payload=secret"
        val cases = listOf(
            Triple("SRT send: Connection was broken $privateText (code 2001)", "BROKEN", "SEND code=2001"),
            Triple("SRT wait: $privateText (code 2002)", "NOT_CONNECTED", "WAIT code=2002"),
            Triple("SRT send: $privateText (code 6001)", "NATIVE_ERROR", "SEND code=6001"),
            Triple("SRT connection closed", "CLOSED", "CONNECTION code=NONE"),
            Triple("SRT message was not accepted in full", "PARTIAL_WRITE", "SEND code=NONE"),
            Triple("SRT send: $privateText (code invalid)", "IO", "NONE code=NONE"),
            Triple(privateText, "IO", "NONE code=NONE"))
        for ((message, category, operation) in cases) {
            val root = IOException(message)
            val original = IOException("CAPTURE_FAILED: fMP4 mux failed", MuxerException(privateText, root))
            val safe = SrtFailureDiagnostics.sanitize(original)
            assertEquals("CAPTURE_FAILED: SRT failure stage=FMP4_MUX root=IO category=$category operation=$operation",
                safe.message)
            assertSame(root, safe.cause?.cause?.cause)
            val diagnostic = SrtFailureDiagnostics.lines(original, safe).joinToString("\n")
            assertFalse(diagnostic.contains(privateText))
            assertFalse(diagnostic.contains("credential"))
        }
    }

    @Test fun unknownMessagesAndCancellationRemainExplicitAndBounded() {
        val privateText = "CAPTURE_FAILED: https://private-peer.invalid/?password=secret"
        val original = IOException(privateText, IllegalStateException(privateText))
        val safe = SrtFailureDiagnostics.sanitize(original)
        assertEquals("CAPTURE_FAILED: SRT failure stage=SRT root=INVALID_STATE category=INVALID_STATE operation=NONE code=NONE",
            safe.message)
        assertFalse(SrtFailureDiagnostics.lines(original).joinToString().contains("private-peer"))
        val cancel = SessionCancelledException()
        assertSame(cancel, SrtFailureDiagnostics.sanitize(cancel))
        val wrapped = IOException("CAPTURE_FAILED: fMP4 mux failed", MuxerException(privateText, cancel))
        val outcome = SessionOutcome()
        outcome.requestStop()
        assertFalse(outcome.record(SrtFailureDiagnostics.sanitize(wrapped), SessionOutcome.Origin.CLEANUP, closing = true))
        assertNull(outcome.failure)
        assertEquals(Wire.STOP, outcome.terminal(1, false).type)
        val first = IOException("first")
        val second = IOException("second", first)
        first.initCause(second)
        val lines = SrtFailureDiagnostics.lines(first)
        assertEquals(2, lines.count { it.startsWith("srt_failure_cause ") })
        assertTrue(lines.contains("srt_failure_chain causes=2 truncated=true"))
        var nested = IOException(privateText)
        repeat(12) { nested = IOException(privateText, nested) }
        val bounded = SrtFailureDiagnostics.lines(nested)
        assertEquals(8, bounded.count { it.startsWith("srt_failure_cause ") })
        assertTrue(bounded.contains("srt_failure_chain causes=8 truncated=true"))
        assertTrue(SrtFailureDiagnostics.evidence(nested).getBoolean("chain_truncated"))
        assertFalse(bounded.joinToString().contains("private-peer"))
    }

    @Test fun removedSavedTransportsRequireExplicitChoiceWithoutRewritingCaptureSettings() {
        for (mode in listOf("rtsp-udp", "rtsp-tcp")) {
            val saved = Settings().apply {
                mediaTransportMode = mode; maxFps = 30; videoBitrate = 20_000_000
                receiverOrientation = "landscape"; orientation = "@90"
                cropWidth = 640; cropHeight = 480; cropX = 12; cropY = 24
                audioBitrate = 128_000; audioSource = "mic"
            }.request().toString()
            val prefs = Mockito.mock(SharedPreferences::class.java)
            Mockito.`when`(prefs.getString("capture", null)).thenReturn(saved)
            val store = CaptureSettingsStore(prefs)
            assertTrue(assertThrows(IllegalArgumentException::class.java) { store.read() }
                .message.orEmpty().contains("explicitly Apply"))
            val draft = store.readForEditing()
            assertEquals(saved, draft.request().toString())
            assertThrows(IllegalArgumentException::class.java) { store.write(draft) }
            Mockito.verify(prefs, Mockito.never()).edit()
            draft.mediaTransportMode = "srt"
            draft.validate()
            val expected = JSONObject(saved).put("mediaTransportMode", "srt").toString()
            assertEquals(expected, draft.request().toString())
        }
    }

    @Test fun customDefaultAndSrtPersistencePreserveAllCaptureChoices() {
        assertEquals("custom", Settings.from(Settings().request()).mediaTransportMode)
        assertFalse(Settings().request().has("mediaTransportMode"))
        val settings = Settings().apply {
            mediaTransportMode = "srt"; receiverOrientation = "portrait"
            videoEncoder = "c2.test.avc"; videoBitrate = 3_000_000; orientation = "@90"
            cropWidth = 640; cropHeight = 480; audioSource = "mic"
        }
        val prefs = Mockito.mock(SharedPreferences::class.java)
        val editor = Mockito.mock(SharedPreferences.Editor::class.java, Mockito.RETURNS_SELF)
        Mockito.`when`(prefs.edit()).thenReturn(editor)
        Mockito.`when`(editor.commit()).thenReturn(true)
        Mockito.`when`(prefs.getString("capture", null)).thenReturn(settings.request().toString())
        val store = CaptureSettingsStore(prefs)
        assertEquals(settings.request().toString(), store.read().request().toString())
        store.write(settings)
        Mockito.verify(editor).putString("capture", settings.request().toString())
    }

    @Test fun unsupportedCodecsNeverSilentlyChangeAndAudioOffIsAllowed() {
        for (codec in listOf("raw")) {
            val settings = Settings().apply { mediaTransportMode = "srt"; audioCodec = codec }
            assertThrows(IllegalArgumentException::class.java) { settings.validate() }
            assertEquals(codec, settings.audioCodec)
            settings.audio = false; settings.validate()
        }
        for (video in listOf("h264", "h265", "av1")) for (audio in listOf("opus", "aac")) {
            val settings = Settings().apply { mediaTransportMode = "srt"; videoCodec = video; audioCodec = audio }
            settings.validate()
            assertEquals(video, settings.videoCodec); assertEquals(audio, settings.audioCodec)
        }
    }

    @Test fun exactDescriptorAndExplicitAcceptanceAreRequired() {
        val fixture = JSONObject(File(System.getProperty("fixtures"), "srt-fmp4.json").readText())
        val expected = fixture.getJSONObject("requestTransport")
        val descriptor = SrtTransport.descriptor(fixture.getString("controlPeer"),
            URI(expected.getString("url")).port, fixture.getLong("session"))
        assertEquals(setOf("kind", "container", "url", "latencyMs", "streamId"), descriptor.keys().asSequence().toSet())
        assertEquals(expected.keys().asSequence().toSet(), descriptor.keys().asSequence().toSet())
        for (key in expected.keys()) assertEquals(key, expected.get(key), descriptor.get(key))
        assertEquals("sc-0000000000abcdef", SrtTransport.streamId(0xabcdefL))
        assertEquals("srt://[fe80::1234]:12345", SrtTransport.descriptor("fe80::1234", 12345, 1).getString("url"))
        SrtTransport.requireAccepted(fixture.getJSONObject("accept"))
        SrtTransport.requireAccepted(fixture.getJSONObject("acceptAudioOff"))
        for (accept in listOf(Wire.obj("tracks" to listOf(1, 2)),
            Wire.obj("mediaTransport" to Wire.obj("kind" to "rtsp"))))
            assertThrows(IOException::class.java) { SrtTransport.requireAccepted(accept) }
    }

    @Test fun avcAdapterForwardsRawAnnexBForOfficialMedia3Conversion() {
        val config = BackendTest().sps(640, 480)
        val frame = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3, 0, 0, 1, 0x06, 4)
        var delivered = false
        val sink = SrtEncodedSink({ track, bytes, width, height ->
            assertEquals(1, track); assertArrayEquals(config, bytes)
            assertEquals(640, width); assertEquals(480, height)
        }, { track, pts, key, bytes ->
            assertEquals(1, track); assertEquals(18_000_000_000L, pts)
            assertTrue(key); assertSame(frame, bytes); delivered = true
        })
        sink.config(1, 1, CodecConfig.video("h264", config))
        sink.packet(Wire.Message(Wire.VIDEO_FRAME, 1, 1, 1, 1, 18_000_000_000L, frame))
        assertTrue(delivered)
    }

    @Test fun durationBasedOpusTimelineRetainsRegressingSourcePtsAndInitialOffset() {
        val timeline = SrtSampleTimeline()
        val video = byteArrayOf(0, 0, 0, 1, 0x65, 1)
        assertEquals(900_000L, timeline.presentationTime(1, 900_000, true, video))
        val payload = WireTest.unhex("f8fffe")
        val observed = listOf(1_000_000L, 999_551L, 1_039_551L)
        val actual = observed.map {
            timeline.presentationTime(2, it, false, payload).also { _ ->
                assertEquals(it, timeline.lastAudioSourcePtsUs)
            }
        }
        assertEquals(listOf(1_000_000L, 1_020_000L, 1_040_000L), actual)
        assertEquals(100_000L, actual.first() - 900_000L)
        assertEquals(1_060_000L, timeline.presentationTime(2, 4, false, byteArrayOf(0x80.toByte())))
        assertEquals(1_062_500L, timeline.presentationTime(2, 3, false, payload))
        assertEquals(9_000_000L, timeline.presentationTime(1, 9_000_000, false, video))
    }

    @Test fun invalidFirstVideoRegressionsEmptyAndMalformedOpusFailExplicitly() {
        val timeline = SrtSampleTimeline()
        val video = byteArrayOf(0, 0, 0, 1, 0x65, 1)
        assertThrows(IOException::class.java) { timeline.presentationTime(1, 10, false, video) }
        timeline.presentationTime(1, 10, true, video)
        for (pts in listOf(10L, 9L, -1L))
            assertThrows(IOException::class.java) { timeline.presentationTime(1, pts, false, video) }
        for (payload in listOf(byteArrayOf(), byteArrayOf(3), byteArrayOf(3, 0), byteArrayOf(3, 63)))
            assertThrows(IOException::class.java) { timeline.presentationTime(2, 10, false, payload) }
    }

    @Test fun opusAdapterPreservesHeadGainPreskipPayloadAndFifoRegressingSourceObservations() {
        val head = WireTest.unhex("4f707573486561640102380180bb0000000000")
        val payload = WireTest.unhex("f8fffe")
        val observed = listOf(1_000_000L, 999_551L, 1_039_551L)
        val received = mutableListOf<Long>()
        val sink = SrtEncodedSink({ track, bytes, channels, rate ->
            assertEquals(2, track); assertArrayEquals(head, bytes)
            assertEquals(2, channels); assertEquals(48000, rate)
        }, { track, pts, key, bytes ->
            assertEquals(2, track); assertFalse(key)
            assertArrayEquals(payload, bytes); received.add(pts)
        })
        val source = ByteArrayOutputStream()
        DataOutputStream(source).apply {
            writeInt(0x6f707573); writeLong(Long.MIN_VALUE); writeInt(head.size); write(head)
            for (pts in observed) { writeLong(pts); writeInt(payload.size); write(payload) }
        }
        assertThrows(EOFException::class.java) {
            ScrcpyAdapter.read(ByteArrayInputStream(source.toByteArray()), 2,
                Settings().apply { mediaTransportMode = "srt" }, 1, sink)
        }
        assertEquals(observed, received)
    }

    @Test fun cancellationAndNativeFailureAreNotConvertedToSuccess() {
        val sink = SrtEncodedSink({ _, _, _, _ -> throw IOException("closed") },
            { _, _, _, _ -> throw SessionCancelledException() })
        assertThrows(IOException::class.java) {
            sink.config(2, 1, CodecConfig.audio("opus", WireTest.unhex("4f707573486561640102380180bb0000000000")))
        }
        assertThrows(SessionCancelledException::class.java) {
            sink.packet(Wire.Message(Wire.AUDIO_PACKET, 0, 1, 2, 1, 1, WireTest.unhex("f8fffe")))
        }
    }
}
