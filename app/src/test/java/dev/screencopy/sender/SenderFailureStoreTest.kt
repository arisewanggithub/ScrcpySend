package dev.screencopy.sender

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.muxer.MuxerException
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SenderFailureStoreTest {
    private class StoredPreferences {
        val context = Mockito.mock(Context::class.java)
        val prefs = Mockito.mock(SharedPreferences::class.java)
        val editor = Mockito.mock(SharedPreferences.Editor::class.java, Mockito.RETURNS_SELF)
        val values = mutableMapOf<String, Any>()
        private val pending = mutableMapOf<String, Any>()
        init {
            Mockito.`when`(context.getSharedPreferences("sender-diagnostics", Context.MODE_PRIVATE)).thenReturn(prefs)
            Mockito.`when`(prefs.getString(Mockito.anyString(), Mockito.isNull())).thenAnswer {
                values[it.getArgument<String>(0)] as? String
            }
            Mockito.`when`(prefs.getLong(Mockito.anyString(), Mockito.anyLong())).thenAnswer {
                values[it.getArgument<String>(0)] as? Long ?: it.getArgument<Long>(1)
            }
            Mockito.`when`(prefs.contains(Mockito.anyString())).thenAnswer {
                values.containsKey(it.getArgument<String>(0))
            }
            Mockito.`when`(prefs.edit()).thenAnswer { pending.clear(); editor }
            Mockito.`when`(editor.putString(Mockito.anyString(), Mockito.anyString())).thenAnswer {
                pending[it.getArgument<String>(0)] = it.getArgument<String>(1); editor
            }
            Mockito.`when`(editor.putLong(Mockito.anyString(), Mockito.anyLong())).thenAnswer {
                pending[it.getArgument<String>(0)] = it.getArgument<Long>(1); editor
            }
            Mockito.`when`(editor.commit()).thenAnswer { values.putAll(pending); true }
        }
        fun record(line: String) = SenderFailureStore.recordDiagnostic(context, line)
        fun start(session: Long) = record("sender_session_started ${Wire.obj("session" to session.toString(), "timestamp_ms" to "1000")}")
        fun failure() = Wire.parseJson((values["lastSessionFailure"] as String).toByteArray())
    }

    @Test fun currentFirstFailurePersistsTimestampPhaseSafeCausesAndPublicSettingsWithoutReplacingHistory() {
        val stored = StoredPreferences()
        stored.values["lastQueueFailure"] = "old queue history"
        stored.values["lastFailure"] = "queue old queue history"
        stored.start(11)
        val privateText = "srt://private-peer.invalid?password=secret payload=captured"
        val root = IOException("SRT send: $privateText (code 2001) | snapshot snapshot=available state=6 packets_sent=17").apply {
            stackTrace = arrayOf(StackTraceElement("dev.screencopy.sender.SrtNative", "write", privateText, 108))
        }
        val error = IOException("CAPTURE_FAILED: fMP4 mux failed", MuxerException(privateText, root))
        val settings = Settings().apply {
            mediaTransportMode = "srt"; videoEncoder = privateText; audioOptions = privateText
            cropWidth = 1080; cropHeight = 1920
        }
        val trace = SessionStopTrace(stored::record)
        trace.firstFailure(error, SessionStopTrace.Phase.STREAMING, SessionOutcome.Origin.CAPTURE, 11, settings, 1700000000123L)
        val record = stored.failure()
        assertEquals("11", record.getString("session"))
        assertEquals("1700000000123", record.getString("timestamp_ms"))
        assertEquals(11L, record.getLong("session"))
        assertEquals(1700000000123L, record.getLong("timestamp_ms"))
        assertEquals("STREAMING", record.getString("phase"))
        assertEquals("CAPTURE", record.getString("origin"))
        assertEquals("BROKEN", record.getString("failure_category"))
        assertEquals("SEND", record.getString("operation"))
        assertEquals(2001, record.getInt("native_code"))
        assertEquals("IO", record.getString("root_exception"))
        assertEquals("17", record.getJSONObject("native_snapshot").getString("packets_sent"))
        assertEquals("6", record.getJSONObject("native_snapshot").getString("state"))
        assertEquals("h264", record.getString("video_codec"))
        assertEquals("opus", record.getString("audio_codec"))
        assertEquals(1080, record.getInt("crop_width"))
        assertEquals(1920, record.getInt("crop_height"))
        assertEquals("CAPTURE_FAILED: SRT failure stage=FMP4_MUX root=IO category=BROKEN operation=SEND code=2001",
            record.getString("summary"))
        assertTrue(record.getJSONArray("causes").toString().contains("depth=2 type=IO category=BROKEN operation=SEND code=2001"))
        assertTrue(record.getJSONArray("stack").toString().contains("SrtNative.write line=108"))
        assertFalse(record.toString().contains("private-peer"))
        assertFalse(record.toString().contains("secret"))
        assertEquals("old queue history", stored.values["lastQueueFailure"])
        val saved = stored.values["lastSessionFailure"]
        trace.firstFailure(IOException("SRT connection closed"), SessionStopTrace.Phase.CLEANUP,
            SessionOutcome.Origin.CLEANUP, 11, settings, 1700000000999L)
        stored.record("sender_queue_failure later teardown")
        assertEquals(saved, stored.values["lastSessionFailure"])
        assertEquals("session $saved", stored.values["lastFailure"])
        val ui = SenderFailureStore.read(stored.context)!!
        assertTrue(ui.startsWith("Last sender session failure at "))
        assertTrue(ui.contains("phase=STREAMING"))
        assertTrue(ui.contains("category=BROKEN operation=SEND code=2001"))
    }

    @Test fun newSessionCannotBeMistakenForOldFailureOrOverwrittenByLateOldCallback() {
        val stored = StoredPreferences()
        val trace = SessionStopTrace(stored::record)
        val settings = Settings().apply { mediaTransportMode = "srt" }
        stored.start(1)
        trace.firstFailure(IOException("SRT connection closed"), SessionStopTrace.Phase.STREAMING,
            SessionOutcome.Origin.CAPTURE, 1, settings, 2000)
        stored.start(2)
        assertTrue(SenderFailureStore.read(stored.context)!!.startsWith("Previous sender session failure at "))
        trace.firstFailure(IOException("SRT send made no progress within one second"), SessionStopTrace.Phase.STREAMING,
            SessionOutcome.Origin.CAPTURE, 2, settings, 3000)
        trace.firstFailure(IOException("SRT connection closed"), SessionStopTrace.Phase.CLEANUP,
            SessionOutcome.Origin.CLEANUP, 1, settings, 4000)
        assertEquals(2L, stored.failure().getLong("session"))
        assertEquals(3000L, stored.failure().getLong("timestamp_ms"))
        assertTrue(SenderFailureStore.read(stored.context)!!.contains("category=NO_PROGRESS operation=SEND"))
        Mockito.verify(stored.editor, Mockito.never()).clear()
    }

    @Test fun productionSessionStartAndFirstFailureRetainInt64IdsAndTimesWithoutRelaxingWireNumbers() {
        val stored = StoredPreferences()
        val trace = SessionStopTrace(stored::record)
        val id = Long.MAX_VALUE
        trace.sessionStarted(id)
        assertEquals(id, stored.values["currentSessionId"])
        assertTrue((stored.values["currentSessionStartedAt"] as Long) > Int.MAX_VALUE)
        trace.firstFailure(IOException("SRT connection closed"), SessionStopTrace.Phase.STREAMING,
            SessionOutcome.Origin.CAPTURE, id, Settings().apply { mediaTransportMode = "srt" }, 1700000000123L)
        assertEquals(id.toString(), stored.failure().getString("session"))
        assertEquals("1700000000123", stored.failure().getString("timestamp_ms"))
        assertTrue(SenderFailureStore.read(stored.context)!!.startsWith("Last sender session failure at "))
        assertThrows(IOException::class.java) {
            Wire.parseJson("""{"timestamp_ms":1700000000123}""".toByteArray())
        }

    }

    @Test fun exactDecimalDiagnosticIdsDoNotRoundAdjacentValuesOrAcceptFloatingPoint() {
        for (id in listOf(764986395015999395L, 764986395015999396L, Long.MAX_VALUE - 1, Long.MAX_VALUE)) {
            assertEquals(id, SenderFailureStore.exactLong(Wire.obj("id" to id.toString()), "id"))
        }
        assertEquals(1700000000123L, SenderFailureStore.exactLong(Wire.obj("id" to "1700000000123"), "id"))
        assertEquals(12L, SenderFailureStore.exactLong(Wire.obj("id" to 12), "id"))
        for (invalid in listOf<Any>("9223372036854775808", "1.0", "01", "1e3", 1.5, Long.MAX_VALUE)) {
            assertThrows(IOException::class.java) { SenderFailureStore.exactLong(Wire.obj("id" to invalid), "id") }
        }
    }

    @Test fun actualSenderCaptureFailureCallbackPersistsFirstErrorButNotLaterCancellationOrReceiverFault() {
        val stored = StoredPreferences()
        val settings = Settings().apply { mediaTransportMode = "srt" }
        val session = SenderSession(stored.context, settings, ReceiverTarget("192.0.2.1", 27184), {}, {}, stored::record)
        val root = IOException("SRT send made no progress within one second")
        val safe = SrtFailureDiagnostics.sanitize(IOException("CAPTURE_FAILED: fMP4 mux failed", MuxerException("mux", root)))
        val before = System.currentTimeMillis()
        session.captureFailed(safe)
        val after = System.currentTimeMillis()
        val record = stored.failure()
        assertTrue(record.getLong("timestamp_ms") in before..after)
        assertEquals("ACCESS", record.getString("phase"))
        assertEquals(safe.message, record.getString("summary"))
        assertEquals(safe.message, session.terminalMessage().json().getString("message"))
        val saved = stored.values["lastSessionFailure"]
        session.captureFailed(IOException("receiver teardown", SessionCancelledException()))
        session.stop()
        session.captureFailed(SessionCancelledException())
        assertEquals(saved, stored.values["lastSessionFailure"])
        assertEquals("session $saved", stored.values["lastFailure"])
        assertEquals(Wire.ERROR, session.terminalMessage().type)
    }

    @Test fun unsupportedGeometryRecordRetainsRequestedNormalizedSizeAndActualLimitsNotEncoderNames() {
        val plan = VideoGeometry(VideoSize(100, 100), VideoSize(96, 96), 1920, 1920, 60)
        val failure = VideoGeometryException(plan, VideoGeometryReason.SIZE_OR_ALIGNMENT, "controlled",
            listOf(VideoLimits(128, 4096, 128, 4096, 2, 2, 1.0, 60.0)), VideoSize(128, 128) to 60, 1)
        val lines = mutableListOf<String>()
        SessionStopTrace(lines::add).geometry(failure, CaptureFormat.H265, SessionStopTrace.Phase.CAPABILITIES)
        val line = lines.single()
        assertTrue(line.contains("requested_width=100 requested_height=100"))
        assertTrue(line.contains("output_width=96 output_height=96"))
        assertTrue(line.contains("limit0_width=128..4096"))
        assertTrue(line.contains("limit0_alignment=2x2"))
        val context = Mockito.mock(Context::class.java)
        val prefs = Mockito.mock(SharedPreferences::class.java)
        val editor = Mockito.mock(SharedPreferences.Editor::class.java, Mockito.RETURNS_SELF)
        Mockito.`when`(context.getSharedPreferences("sender-diagnostics", Context.MODE_PRIVATE)).thenReturn(prefs)
        Mockito.`when`(prefs.edit()).thenReturn(editor); Mockito.`when`(editor.commit()).thenReturn(true)
        SenderFailureStore.recordDiagnostic(context, line)
        Mockito.verify(editor).putString("lastStartupFailure", line.removePrefix("sender_startup_failure "))
    }

    @Test fun captureProtocolRecordRetainsOnlyControlledReasonAndIsPreferredOverOlderQueueRecord() {
        val logs = mutableListOf<String>()
        val evidence = CaptureProtocolFailure(CaptureProtocolReason.PTS_DECREASE, CaptureProtocolStage.MUX_MEDIA,
            CaptureTrack.AUDIO, 1, 1, 1, 1, 1, -64)
        SessionStopTrace(logs::add).captureProtocol(evidence, CaptureFormat.AAC, SessionStopTrace.Phase.CAPTURE_START,
            SessionOutcome.Origin.CAPTURE)
        val line = logs.single()
        val context = Mockito.mock(Context::class.java)
        val prefs = Mockito.mock(SharedPreferences::class.java)
        val editor = Mockito.mock(SharedPreferences.Editor::class.java, Mockito.RETURNS_SELF)
        Mockito.`when`(context.getSharedPreferences("sender-diagnostics", Context.MODE_PRIVATE)).thenReturn(prefs)
        Mockito.`when`(prefs.edit()).thenReturn(editor); Mockito.`when`(editor.commit()).thenReturn(true)
        val fields = line.removePrefix("sender_capture_failure ")
        SenderFailureStore.recordDiagnostic(context, line)
        Mockito.verify(editor).putString("lastCaptureFailure", fields)
        Mockito.verify(editor).putString("lastFailure", "capture $fields")
        Mockito.`when`(prefs.getString("lastFailure", null)).thenReturn("capture $fields")
        val displayed = SenderFailureStore.read(context)!!
        assertTrue(displayed.contains("code=PROTOCOL reason=PTS_DECREASE"))
        assertTrue(displayed.contains("format=AAC"))
        assertTrue(displayed.contains("pts_delta_us=-64"))
        assertFalse(displayed.contains("CSD")); assertFalse(displayed.contains("127.0.0.1"))
    }

    @Test fun firstQueueDiagnosticIsControlledRelativeAndRetainedForNextUiAttach() {
        val mux = MediaBudget(false, System::nanoTime, 20_000_000)
        mux.offer(Wire.config(1, 1, 1, Wire.obj("codec" to "h264"))); mux.poll(1); mux.ready(1, 1)
        mux.offer(Wire.Message(Wire.VIDEO_FRAME, 0, 1, 1, 1, 777_000_000_000L, byteArrayOf(1)))
        val failure = assertThrows(SenderCongestionException::class.java) {
            mux.offer(Wire.Message(Wire.VIDEO_FRAME, 0, 1, 1, 1, 777_000_600_000L, byteArrayOf(1)))
        }
        val logs = mutableListOf<String>()
        SessionStopTrace(logs::add).congestion(failure.snapshot, SessionStopTrace.Phase.STREAMING)
        val diagnostic = logs.single()
        assertTrue(diagnostic.contains("queue=VIDEO limit=PTS_SPAN"))
        assertTrue(diagnostic.contains("attempted_span_us=600000"))
        assertFalse(diagnostic.contains("777000000000")); assertFalse(diagnostic.contains("777000600000"))
        val context = Mockito.mock(Context::class.java)
        val prefs = Mockito.mock(SharedPreferences::class.java)
        val editor = Mockito.mock(SharedPreferences.Editor::class.java, Mockito.RETURNS_SELF)
        Mockito.`when`(context.getSharedPreferences("sender-diagnostics", Context.MODE_PRIVATE)).thenReturn(prefs)
        Mockito.`when`(prefs.edit()).thenReturn(editor)
        Mockito.`when`(editor.commit()).thenReturn(true)
        val stored = diagnostic.removePrefix("sender_queue_failure ")
        SenderFailureStore.recordDiagnostic(context, diagnostic)
        Mockito.verify(editor).putString("lastQueueFailure", stored)
        Mockito.`when`(prefs.getString("lastQueueFailure", null)).thenReturn(stored)
        assertTrue(SenderFailureStore.read(context)!!.startsWith("Historical sender queue failure (timestamp unavailable): phase=STREAMING"))
        Mockito.clearInvocations(editor)
        SenderFailureStore.recordDiagnostic(context, "stop_stage=FINISHED")
        Mockito.verifyNoInteractions(editor)
    }
}
