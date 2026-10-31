package dev.screencopy.sender

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.nio.file.Path

class CaptureStartupEvidenceTest {
    @Test fun actualBackendBothStreamsEofRetainsObservedServerErrorBeforeCleanupAndNotRawOutput() {
        val failures = CopyOnWriteArrayList<Exception>()
        val failed = CountDownLatch(1)
        val video = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).apply { write(ByteArray(65)); writeInt(0x68323635); writeInt(96); writeInt(96) }
        }.toByteArray()
        val bridge = object : CaptureBridge {
            override fun deploy(id: String, server: ByteArray) = Unit
            override fun capabilities(id: String) = ""
            override fun launch(id: String, settings: Settings): InputStream = ByteArrayInputStream(
                ("SCREENCOPY_GUARDIAN_READY:12345\n[server] ERROR: Capture/encoding error: " +
                    "java.lang.IllegalArgumentException: vendor private invalid format CSD secret\n").toByteArray())
            override fun heartbeat(id: String) = Unit
            override fun openMedia(id: String, track: Int): InputStream =
                ByteArrayInputStream(if (track == 1) video else byteArrayOf())
            override fun cleanup(id: String) = Unit
            override fun close() = Unit
        }
        CaptureBackend(CaptureBridgeTest().context(), bridge, { failures.add(it); failed.countDown() }).use { backend ->
            backend.deploy()
            backend.start(Settings().apply { videoCodec = "h265" }, 1, sink)
            assertTrue(failed.await(2, TimeUnit.SECONDS))
            val first = failures.first() as CaptureStreamEof
            assertEquals(ServerErrorKind.VIDEO_ENCODING, first.evidence.server.kind)
            assertEquals(ServerErrorClass.ILLEGAL_ARGUMENT, first.evidence.server.exception)
            assertEquals(0L, first.evidence.media)
            assertFalse(first.message!!.contains("vendor private"))
            assertFalse(first.message!!.contains("CSD secret"))
        }
    }

    private val sink = object : ScrcpyAdapter.Sink {
        override fun config(track: Int, generation: Int, config: JSONObject) = Unit
        override fun packet(packet: Wire.Message) = Unit
    }
    @Test fun codecHeaderDimensionsPacketHeaderAndPayloadEofHaveExactControlledProgress() {
        val cases = listOf(
            byteArrayOf() to CaptureReadStage.CODEC_HEADER,
            ByteArrayOutputStream().also { DataOutputStream(it).writeInt(0x68323635) }.toByteArray() to CaptureReadStage.VIDEO_DIMENSIONS,
            ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(0x68323635); writeInt(96); writeInt(96) } }.toByteArray() to CaptureReadStage.PACKET_HEADER,
            ByteArrayOutputStream().also { DataOutputStream(it).apply {
                writeInt(0x68323635); writeInt(96); writeInt(96); writeLong(Long.MIN_VALUE); writeInt(20); writeByte(1)
            } }.toByteArray() to CaptureReadStage.PACKET_PAYLOAD
        )
        for ((bytes, stage) in cases) {
            val error = assertThrows(CaptureStreamEof::class.java) {
                ScrcpyAdapter.read(ByteArrayInputStream(bytes), 1, Settings().apply { videoCodec = "h265" }, 1, sink)
            }
            assertEquals(stage, error.evidence.stage)
            assertEquals(CaptureTrack.VIDEO, error.evidence.track)
            assertEquals(CaptureFormat.H265, error.evidence.format)
            assertEquals(0L, error.evidence.media)
        }
    }
    @Test fun boundedLogDrainPreservesOnlyFirstObservedErrorCategoryAndExceptionNotPrivateText() {
        val source = "SCREENCOPY_GUARDIAN_READY:12345\n[server] INFO: Device private-name\n" +
            "[server] ERROR: Capture/encoding error: java.lang.IllegalArgumentException: vendor private CSD secret\n" +
            "x".repeat(8192) + "\n[server] ERROR: Audio capture error\n"
        val log = CaptureServerLog()
        log.drain(ByteArrayInputStream(source.toByteArray()))
        assertEquals(ServerErrorKind.VIDEO_ENCODING, log.snapshot().kind)
        assertEquals(ServerErrorClass.ILLEGAL_ARGUMENT, log.snapshot().exception)
        val record = CaptureStreamEof(CaptureEofEvidence(CaptureTrack.VIDEO, CaptureReadStage.PACKET_HEADER,
            CaptureFormat.H265, true, true, 0, 0, 0, 0)).withServer(log.snapshot())
        val lines = mutableListOf<String>()
        SessionStopTrace(lines::add).startup(record, SessionStopTrace.Phase.CAPTURE_START, CaptureFormat.H265, CaptureFormat.AAC, true)
        val diagnostic = lines.single()
        assertTrue(diagnostic.contains("selected_audio_format=AAC"))
        assertTrue(diagnostic.contains("server_observed=VIDEO_ENCODING"))
        for (privateText in listOf("private-name", "12345", "CSD", "secret", "vendor private"))
            assertFalse(diagnostic.contains(privateText))
    }
    @Test fun unknownServerErrorsAreNotGuessedAndEofDoesNotOverrideEarlierFault() {
        val log = CaptureServerLog()
        log.drain(ByteArrayInputStream("[server] INFO: still alive\n".toByteArray()))
        assertEquals(ServerErrorKind.NONE, log.snapshot().kind)
        val state = SessionOutcome()
        val original = IOException("CAPTURE_FAILED: actual prior fault")
        state.record(original, SessionOutcome.Origin.CAPTURE)
        state.record(CaptureStreamEof(CaptureEofEvidence(CaptureTrack.AUDIO, CaptureReadStage.CODEC_HEADER,
            CaptureFormat.OPUS, false, false, 0, 0, 0, 0)), SessionOutcome.Origin.CAPTURE)
        assertSame(original, state.failure)
    }
    @Test fun backgroundCannotReleasePendingOrAdoptedCaptureReadinessAndLogDrainNeverOwnsStdin() {
        val model = ReceiverCasting { 1L }
        model.updateDiscovery(listOf(Discovery.Receiver("TV", "192.0.2.1", 27184, "available", 1)))
        val request = model.reserve(ReceiverKey("192.0.2.1", 27184), Settings())
        assertFalse(ShizukuPolicy.mayReleaseIdle(0, model.snapshot().active != null))
        val service = CastingServiceState(model); val owner = checkNotNull(service.adopt(request.token))
        assertFalse(ShizukuPolicy.mayReleaseIdle(0, model.snapshot().active != null))
        service.finish(owner)
        assertTrue(ShizukuPolicy.mayReleaseIdle(0, model.snapshot().active != null))
        assertFalse(ShizukuPolicy.mayReleaseIdle(1, false))
        val source = readUtf8Strict(Path.of(System.getProperty("senderSources"), "CaptureBackend.kt"))
        val reader = source.substringAfter("worker(\"scrcpy-log\")").substringBefore("worker(\"capture-heartbeat\")")
        assertTrue(reader.contains("serverLog.drain(shell)")); assertFalse(reader.contains("outputStream")); assertFalse(reader.contains("bridge.close"))
        val serviceSource = readUtf8Strict(Path.of(System.getProperty("senderSources"), "ShellCaptureService.kt"))
        assertTrue(serviceSource.contains("lifetime.own(java.io.Closeable { child.outputStream.close() })"))
    }
}
