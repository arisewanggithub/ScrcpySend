package dev.screencopy.sender

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SessionStopTest {
    private fun syntheticSizeSwitch(backend: CaptureBackend): SenderSession {
        val session = sender()
        for ((name, value) in listOf("acceptedSession" to true, "playing" to true, "backend" to backend))
            SenderSession::class.java.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
        assertTrue(session.stopForSizeSwitch())
        val authorization = SenderSession::class.java.getDeclaredField("sizeSwitch")
            .apply { isAccessible = true }.get(session) as SizeSwitchAuthorization
        authorization.acknowledge(); authorization.peerClosed()
        (SenderSession::class.java.getDeclaredField("receiverReleased")
            .apply { isAccessible = true }.get(session) as CountDownLatch).countDown()
        // requestSent remains false: no control I/O. run() cancels before Shizuku/backend deployment.
        return session
    }

    @Test fun sizeRestartEligibilityBecomesTrueOnlyAfterActualOwnedSyntheticCleanupReturns() {
        val backend = Mockito.mock(CaptureBackend::class.java)
        val session = syntheticSizeSwitch(backend)
        assertFalse(session.cleanSizeSwitch)
        session.run()
        Mockito.verify(backend).close()
        Mockito.verify(backend, Mockito.never()).deploy()
        assertTrue(session.cleanSizeSwitch)
    }

    @Test fun anySyntheticCleanupExceptionIncludingCancellationShapedEofBlocksSizeRestart() {
        for (error in listOf(IOException("Cleanup failed"), EOFException("Cleanup incomplete"))) {
            val backend = Mockito.mock(CaptureBackend::class.java)
            Mockito.doThrow(error).`when`(backend).close()
            val session = syntheticSizeSwitch(backend)
            session.run()
            assertFalse(session.cleanSizeSwitch)
            Mockito.verify(backend).close()
            Mockito.verify(backend, Mockito.never()).deploy()
        }
    }

    @Test fun ordinaryStopRevokesPendingSizeAuthorizationEvenWhenAlreadyStopping() {
        val backend = Mockito.mock(CaptureBackend::class.java)
        val session = syntheticSizeSwitch(backend)
        session.stop()
        session.run()
        assertFalse(session.cleanSizeSwitch)
        Mockito.verify(backend, Mockito.never()).deploy()
    }

    @Test fun syntheticSupportedPlayingCodecsAllowExactlyOneExplicitSizeStopButNotRestartYet() {
        for (codec in listOf("h264", "h265", "av1")) {
            val status = mutableListOf<String>()
            val session = SenderSession(Mockito.mock(Context::class.java), Settings().apply { videoCodec = codec },
                ReceiverTarget("192.0.2.1", 27184), status::add, {}, {})
            for (name in listOf("acceptedSession", "playing"))
                SenderSession::class.java.getDeclaredField(name).apply { isAccessible = true }.set(session, true)
            // No run(), capture backend or sent request: only the explicit-action eligibility path.
            assertTrue(session.stopForSizeSwitch())
            assertEquals(listOf("Stopping"), status)
            assertFalse(session.cleanSizeSwitch)
            assertFalse(session.stopForSizeSwitch())
            assertEquals(listOf("Stopping"), status)
        }
    }

    @Test fun syntheticUnacceptedOrNotPlayingCodecsRejectSizeStopBeforeAnyStopLifecycle() {
        for (codec in listOf("h264", "h265", "av1")) {
            for ((accepted, playing) in listOf(false to false, false to true, true to false)) {
                val status = mutableListOf<String>()
                val session = SenderSession(Mockito.mock(Context::class.java), Settings().apply { videoCodec = codec },
                    ReceiverTarget("192.0.2.1", 27184), status::add, {}, {})
                for ((name, value) in listOf("acceptedSession" to accepted, "playing" to playing))
                    SenderSession::class.java.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
                assertFalse(session.stopForSizeSwitch())
                assertFalse(session.cleanSizeSwitch)
                assertTrue(status.isEmpty())
            }
        }
    }

    @Test fun cleanupCancelsNativeSinkBeforeJoiningCaptureReaders() {
        val backend = Mockito.mock(CaptureBackend::class.java)
        val srt = Mockito.mock(SrtSender::class.java)
        val session = SenderSession(Mockito.mock(Context::class.java),
            Settings().apply { audio = false; videoCodec = "unsupported"; mediaTransportMode = "srt" },
            ReceiverTarget("127.0.0.1", 27184), {}, {}, {})
        for ((name, value) in listOf("backend" to backend, "srt" to srt)) {
            SenderSession::class.java.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
        }
        session.run()
        val order = Mockito.inOrder(srt, backend)
        order.verify(srt).close()
        order.verify(backend).close()
    }

    @Test fun cleanupCancelsSrtBeforeJoiningCaptureReadersAndRetainsFirstFailure() {
        val backend = Mockito.mock(CaptureBackend::class.java)
        val srt = Mockito.mock(SrtSender::class.java)
        val logs = mutableListOf<String>()
        val session = SenderSession(Mockito.mock(Context::class.java),
            Settings().apply { audio = false; videoCodec = "unsupported"; mediaTransportMode = "srt" },
            ReceiverTarget("127.0.0.1", 27184), {}, {}, logs::add)
        for ((name, value) in listOf("backend" to backend, "srt" to srt))
            SenderSession::class.java.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
        Mockito.doThrow(IOException("CAPTURE_FAILED: later mux close")).`when`(srt).close()
        session.run()
        val order = Mockito.inOrder(srt, backend)
        order.verify(srt).close()
        order.verify(backend).close()
        assertTrue(logs.any { it.contains("retained=false") })
        assertEquals(Wire.ERROR, session.terminalMessage().type)
        assertEquals("INTERNAL", session.terminalMessage().json().getString("code"))
    }

    private fun sender(status: (String) -> Unit = {}, logs: MutableList<String> = CopyOnWriteArrayList()) =
        SenderSession(Mockito.mock(Context::class.java), Settings().apply { audio = false },
            ReceiverTarget("127.0.0.1", 27184), status, {}, logs::add)

    @Test fun actualCaptureSinkStopRaceProducesTypedCancellationAndOrderlyStopNotInternal() {
        val status = mutableListOf<String>()
        val logs = CopyOnWriteArrayList<String>()
        val session = sender(status::add, logs)
        session.stop()
        assertEquals("Stopping", status.single())
        val error = assertThrows(SessionCancelledException::class.java) {
            session.packet(Wire.Message(Wire.VIDEO_FRAME, 1, 1, 1, 1, 100, byteArrayOf(1)))
        }
        session.captureFailed(error) // Exact CaptureBackend reader -> callback path after sink rejection.
        assertEquals(Wire.STOP, session.terminalMessage().type)
        assertTrue(logs.any { it.contains("exception=SessionCancelledException") && it.contains("retained=false") })
        session.stop()
        assertEquals(1, status.size)
    }
    @Test fun cancelledReadSocketCloseEofAndInterruptDoNotReplaceIntentionalStop() {
        for (error in listOf(EOFException(), SocketException("Socket closed"), InterruptedException(),
            IOException("wrapped", SessionCancelledException()))) {
            val state = SessionOutcome()
            state.requestStop()
            assertFalse(state.record(error, SessionOutcome.Origin.RECEIVER_READ))
            assertNull(state.failure)
            assertEquals(Wire.STOP, state.terminal(1, false).type)
            assertEquals(Wire.SESSION_END, state.terminal(1, true).type)
        }
    }
    @Test fun realEarlierFailureSurvivesUserStopAndLateCancellation() {
        val state = SessionOutcome()
        val original = IOException("AUDIO_FAILED: actual capture failure")
        assertTrue(state.record(original, SessionOutcome.Origin.CAPTURE))
        state.requestStop()
        assertFalse(state.record(SessionCancelledException(), SessionOutcome.Origin.CAPTURE))
        val terminal = state.terminal(1, false)
        assertEquals(Wire.ERROR, terminal.type); assertEquals("AUDIO_FAILED", terminal.json().getString("code"))
        assertSame(original, state.failure)
    }
    @Test fun unexpectedExceptionStillSendsErrorAndLateUnexpectedFaultIsRetainedLocally() {
        val session = sender()
        session.captureFailed(IllegalStateException("actual encoder defect"))
        assertEquals(Wire.ERROR, session.terminalMessage().type)
        assertEquals("INTERNAL", session.terminalMessage().json().getString("code"))
        session.stop()
        assertEquals(Wire.ERROR, session.terminalMessage().type)
        val state = SessionOutcome()
        state.requestStop()
        val late = IllegalStateException("Unexpected cleanup bug")
        assertTrue(state.record(late, SessionOutcome.Origin.CLEANUP, closing = true))
        assertSame(late, state.failure)
        assertEquals(Wire.STOP, state.terminal(1, false).type)
    }
    @Test fun unexpectedEofBeforeAnyStopRemainsFatal() {
        val state = SessionOutcome()
        assertTrue(state.record(EOFException("connection lost unexpectedly"), SessionOutcome.Origin.RECEIVER_READ))
        assertEquals(Wire.ERROR, state.terminal(1, false).type)
    }
    @Test fun terminalBypassesFullControlAndMediaBacklogAndRejectsLateProducerWithoutGrowingQueues() {
        val mux = MediaBudget(false)
        mux.offer(Wire.config(1, 1, 1, Wire.obj("codec" to "h264")))
        repeat(63) { mux.offer(Wire.control(Wire.PING, 1, Wire.obj("nonce" to it))) }
        mux.offer(Wire.Message(Wire.VIDEO_FRAME, 0, 1, 1, 1, 0, ByteArray(1024)))
        val stop = Wire.control(Wire.STOP, 1, Wire.obj("reason" to "user"))
        mux.finish(stop); mux.finish(Wire.control(Wire.ERROR, 1, Wire.obj("code" to "INTERNAL")))
        assertEquals(0, mux.queuedBytes())
        assertEquals(Wire.STOP, mux.poll(1)?.type)
        assertNull(mux.poll(1))
        assertThrows(SessionCancelledException::class.java) { mux.offer(stop) }
    }
    @Test fun realReceiverLinkSendsOrderlyTerminalForPendingAndAcceptedSessionsWithCancelledRead() {
        for (accepted in listOf(false, true)) ServerSocket(0).use { server ->
            val session = sender()
            val id = session.terminalMessage().session
            val peer = CompletableFuture.runAsync {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    assertEquals(Wire.HELLO, Wire.read(socket.inputStream).type)
                    Wire.write(socket.outputStream, Wire.control(Wire.HELLO, 0,
                        Wire.obj("role" to "receiver", "versions" to org.json.JSONArray().put(3), "name" to "Stop test")))
                    assertEquals(Wire.SESSION_REQUEST, Wire.read(socket.inputStream).type)
                    if (accepted) Wire.write(socket.outputStream, Wire.control(Wire.SESSION_ACCEPT, id,
                        Wire.obj("tracks" to org.json.JSONArray().put(1))))
                    val terminal = Wire.read(socket.inputStream)
                    assertEquals(id, terminal.session); assertEquals(Wire.STOP, terminal.type)
                    Wire.write(socket.outputStream, Wire.control(Wire.SESSION_END, id, Wire.obj("reason" to "user")))
                    assertEquals(-1, socket.inputStream.read())
                }
            }
            ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                link.connect()
                link.send(Wire.control(Wire.SESSION_REQUEST, id, Settings().apply { audio = false }.request()))
                if (accepted) assertEquals(Wire.SESSION_ACCEPT, link.receive().type)
                session.stop(); session.captureFailed(EOFException())
                link.send(session.terminalMessage())
                assertEquals(Wire.SESSION_END, link.receive().type)
            }
            peer.get(3, TimeUnit.SECONDS)
        }
    }
    @Test fun actualBlockedReceiverReadClosedLocallyOrByPeerAfterStopCannotProduceFatalInternal() {
        for (peerCloses in listOf(false, true)) ServerSocket(0).use { server ->
            val close = CountDownLatch(1)
            val session = sender()
            val peer = CompletableFuture.runAsync {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    assertEquals(Wire.HELLO, Wire.read(socket.inputStream).type)
                    Wire.write(socket.outputStream, Wire.control(Wire.HELLO, 0,
                        Wire.obj("role" to "receiver", "versions" to org.json.JSONArray().put(3), "name" to "Read cancellation")))
                    assertTrue(close.await(1, TimeUnit.SECONDS))
                    if (!peerCloses) assertEquals(-1, socket.inputStream.read())
                }
            }
            ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                link.connect()
                val reading = CountDownLatch(1)
                val read = CompletableFuture.runAsync {
                    try { reading.countDown(); link.receive(); fail("Expected cancelled read") }
                    catch (e: IOException) { session.captureFailed(e) }
                }
                assertTrue(reading.await(1, TimeUnit.SECONDS))
                session.stop(); close.countDown()
                if (!peerCloses) link.close()
                read.get(1, TimeUnit.SECONDS)
                assertEquals(Wire.STOP, session.terminalMessage().type)
            }
            peer.get(1, TimeUnit.SECONDS)
        }
    }
    private class BlockedCommand : Process() {
        val destroyed = CountDownLatch(1)
        val closeDone = CountDownLatch(1)
        val destroyCalls = java.util.concurrent.atomic.AtomicInteger()
        override fun destroy() { destroyCalls.incrementAndGet(); destroyed.countDown() }
        override fun getInputStream(): InputStream = object : InputStream() {
            override fun read(): Int { destroyed.await(); return -1 }
            override fun close() { check(destroyed.await(1, TimeUnit.SECONDS)); closeDone.countDown() }
        }
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getErrorStream() = InputStream.nullInputStream()
        override fun waitFor(): Int { destroyed.await(); return 0 }
        override fun exitValue() = 0
    }
    @Test fun nonCaptureOwnedCommandIsTerminatedBeforePotentiallyLockedReadClose() {
        val process = BlockedCommand()
        val done = CompletableFuture.runAsync {
            CaptureCancellation.closeOwnedProcess(process, false) { process.inputStream.close() }
        }
        done.get(500, TimeUnit.MILLISECONDS)
        assertEquals(1, process.destroyCalls.get())
        assertEquals(0L, process.closeDone.count)
    }
    @Test fun captureGuardianNeverGetsProcessDestroyAndLeaseRemainsStoppingUntilCleanupReturns() {
        val process = BlockedCommand()
        val model = ReceiverCasting { 1L }
        model.updateDiscovery(listOf(Discovery.Receiver("TV", "192.0.2.1", 27184, "available", 1)))
        val request = model.reserve(ReceiverKey("192.0.2.1", 27184), Settings())
        val service = CastingServiceState(model)
        val owner = checkNotNull(service.adopt(request.token))
        val closing = CountDownLatch(1); val release = CountDownLatch(1)
        val worker = CompletableFuture.runAsync {
            CaptureCancellation.closeOwnedProcess(process, true) { closing.countDown(); release.await() }
            service.finish(owner)
        }
        model.requestStop(request.token)
        assertTrue(closing.await(1, TimeUnit.SECONDS))
        assertEquals(ReceiverCasting.Phase.STOPPING, model.snapshot().active?.phase)
        assertEquals(0, process.destroyCalls.get())
        release.countDown(); worker.get(1, TimeUnit.SECONDS)
        assertNull(model.snapshot().active)
    }
    @Test fun stopDiagnosticsContainFixedStageAndRelativeTimeNotExceptionMessageOrPeerData() {
        var now = 1_000_000L
        val logs = mutableListOf<String>()
        val trace = SessionStopTrace(logs::add) { now }
        trace.mark(SessionStopTrace.Stage.REQUESTED); now += 25_000_000
        trace.mark(SessionStopTrace.Stage.BRIDGE_CLOSE_BEGIN)
        trace.cause(SessionOutcome.Origin.CAPTURE, IOException("secret peer address and session ID"), true)
        trace.mark(SessionStopTrace.Stage.REQUESTED)
        assertEquals(3, logs.size)
        assertTrue(logs[1].contains("elapsed_ms=25"))
        assertTrue(logs.none { it.contains("secret") || it.contains("peer address") || it.contains("session ID") })
    }
}
