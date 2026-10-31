package dev.screencopy.sender

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SenderAdmissionTest {
    private fun frame(track: Int, pts: Long, size: Int = 1) = Wire.Message(
        if (track == 1) Wire.VIDEO_FRAME else Wire.AUDIO_PACKET, 0, 1, track, 1, pts, ByteArray(size))
    private fun ready(mux: MediaBudget, audio: Boolean = false) {
        mux.offer(Wire.config(1, 1, 1, Wire.obj("codec" to "h264")))
        if (audio) mux.offer(Wire.config(1, 2, 1, Wire.obj("codec" to "raw")))
        mux.poll(1); if (audio) mux.poll(1)
        mux.ready(1, 1); if (audio) mux.ready(2, 1)
    }
    @Test fun vfrSourceGapWaitsForLocalDequeueRatherThanFalseFailingFreshQueue() {
        val mux = MediaBudget(false); ready(mux)
        mux.offer(frame(1, 1_000_000))
        val waiting = CompletableFuture.runAsync { mux.offer(frame(1, 3_000_000)) }
        Thread.sleep(30)
        assertFalse(waiting.isDone)
        assertEquals(1_000_000L, mux.poll(1)?.pts)
        waiting.get(1, TimeUnit.SECONDS)
        assertEquals(0, mux.queuedSpanUs(1))
        assertEquals(3_000_000L, mux.poll(1)?.pts)
    }
    @Test fun allHardBoundsStillFailWithExactCheckAndNoAdmittedLimitIncrease() {
        val span = MediaBudget(false, System::nanoTime, 20_000_000); ready(span)
        span.offer(frame(1, 0))
        val failure = assertThrows(SenderCongestionException::class.java) { span.offer(frame(1, 500_001)) }
        assertEquals(SenderLimit.PTS_SPAN, failure.snapshot.limit)
        assertEquals(0, failure.snapshot.queuedSpanUs)
        assertEquals(500_001, failure.snapshot.attemptedSpanUs)
        assertEquals(0, span.queuedSpanUs(1))
        val count = MediaBudget(false, System::nanoTime, 20_000_000); ready(count)
        repeat(1024) { count.offer(frame(1, 0)) }
        assertEquals(SenderLimit.TRACK_COUNT,
            assertThrows(SenderCongestionException::class.java) { count.offer(frame(1, 0)) }.snapshot.limit)
        val bytes = MediaBudget(false, System::nanoTime, 20_000_000); ready(bytes)
        bytes.offer(frame(1, 0, 4 * 1024 * 1024)); bytes.offer(frame(1, 1, 4 * 1024 * 1024))
        val full = assertThrows(SenderCongestionException::class.java) { bytes.offer(frame(1, 2)) }
        assertEquals(SenderLimit.MEDIA_BYTES, full.snapshot.limit)
        assertEquals(8 * 1024 * 1024, full.snapshot.bytes)
        assertEquals(8 * 1024 * 1024, bytes.queuedBytes())
        val control = MediaBudget(false, System::nanoTime, 20_000_000)
        repeat(64) { control.offer(Wire.control(Wire.PING, 1, Wire.obj("nonce" to it))) }
        assertEquals(SenderLimit.CONTROL_COUNT,
            assertThrows(SenderCongestionException::class.java) { control.offer(Wire.control(Wire.PING, 1, Wire.obj("nonce" to 65))) }.snapshot.limit)
    }
    @Test fun defaultAdmissionDeadlineIsBoundedAndStopWakesBlockedProducerImmediately() {
        val mux = MediaBudget(false); ready(mux); mux.offer(frame(1, 0))
        val begin = System.nanoTime()
        val failure = assertThrows(SenderCongestionException::class.java) { mux.offer(frame(1, 600_000)) }
        assertTrue(failure.snapshot.waitMs >= 450)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin) < 2000)
        val waiting = CompletableFuture.supplyAsync {
            try { mux.offer(frame(1, 700_000)); false } catch (e: SessionCancelledException) { true }
        }
        Thread.sleep(30); mux.finish(Wire.control(Wire.STOP, 1, Wire.obj("reason" to "user")))
        assertTrue(waiting.get(200, TimeUnit.MILLISECONDS))
    }
    @Test fun actualCaptureReadersReadyFlushAndSteadyInterleavedVfrAndAudioKeepEveryPtsAndPacket() {
        val audioPts = (0 until 94).map { 10_000_000L + it * 20_000 + if (it >= 24) 64_112 else 0 }
        val videoPts = listOf(10_000_000L, 11_200_000L) + (2 until 12).map { 11_200_000L + (it - 1) * 90_000 }
        val received = CopyOnWriteArrayList<Pair<Int, Long>>()
        val allReceived = CountDownLatch(1)
        ServerSocket(0).use { server ->
            val peer = CompletableFuture.runAsync {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    assertEquals(Wire.HELLO, Wire.read(socket.inputStream).type)
                    Wire.write(socket.outputStream, Wire.control(Wire.HELLO, 0,
                        Wire.obj("role" to "receiver", "versions" to org.json.JSONArray().put(3), "name" to "Admission fixture")))
                    assertEquals(Wire.SESSION_REQUEST, Wire.read(socket.inputStream).type)
                    Wire.write(socket.outputStream, Wire.control(Wire.SESSION_ACCEPT, 1,
                        Wire.obj("tracks" to org.json.JSONArray().put(1).put(2))))
                    val configurations = mutableListOf<Wire.Message>()
                    repeat(2) { configurations.add(Wire.read(socket.inputStream)) }
                    assertTrue(configurations.all { it.type == Wire.STREAM_CONFIG })
                    Thread.sleep(150) // Real bounded initialization gate while capture readers already produce.
                    configurations.forEach { Wire.write(socket.outputStream,
                        Wire.Message(Wire.STREAM_READY, 0, 1, it.track, it.generation, -1, Wire.obj("ok" to true).toString().toByteArray())) }
                    while (true) {
                        val message = Wire.read(socket.inputStream)
                        if (message.type == Wire.STOP) break
                        assertTrue(message.type == Wire.VIDEO_FRAME || message.type == Wire.AUDIO_PACKET)
                        received.add(message.track to message.pts)
                        if (received.size == audioPts.size + videoPts.size) allReceived.countDown()
                    }
                    Wire.write(socket.outputStream, Wire.control(Wire.SESSION_END, 1, Wire.obj("reason" to "user")))
                }
            }
            ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                link.connect(); link.send(Wire.control(Wire.SESSION_REQUEST, 1, Settings().apply { audioCodec = "raw" }.request()))
                assertEquals(Wire.SESSION_ACCEPT, link.receive().type)
                val mux = MediaBudget(true)
                val errors = CopyOnWriteArrayList<Throwable>()
                val inputVideo = PipedInputStream(64 * 1024); val outputVideo = PipedOutputStream(inputVideo)
                val inputAudio = PipedInputStream(64 * 1024); val outputAudio = PipedOutputStream(inputAudio)
                val sink = object : ScrcpyAdapter.Sink {
                    override fun config(track: Int, generation: Int, config: JSONObject) { mux.offer(Wire.config(1, track, generation, config)) }
                    override fun packet(packet: Wire.Message) {
                        mux.offer(packet)
                        assertTrue(mux.queuedBytes() <= 8 * 1024 * 1024)
                        assertTrue(mux.queuedSpanUs(packet.track) <= 500_000)
                    }
                }
                fun worker(finiteSource: Boolean = false, action: () -> Unit) = Thread {
                    try { action() } catch (e: EOFException) { if (!finiteSource) errors.add(e) }
                    catch (e: Throwable) { errors.add(e) }
                }.apply { start() }
                val workers = mutableListOf<Thread>()
                try {
                    workers.add(worker(true) { ScrcpyAdapter.read(inputVideo, 1, Settings().apply { audioCodec = "raw" }, 1, sink) })
                    workers.add(worker(true) { ScrcpyAdapter.read(inputAudio, 2, Settings().apply { audioCodec = "raw" }, 1, sink) })
                    workers.add(worker {
                        while (true) {
                            val message = link.receive()
                            if (message.type == Wire.SESSION_END) break
                            assertEquals(Wire.STREAM_READY, message.type); mux.ready(message.track, message.generation)
                        }
                    })
                    workers.add(worker {
                        while (true) {
                            val message = mux.poll(100) ?: continue
                            mux.beginWrite(message)
                            try { link.send(message) } finally { mux.endWrite() }
                            if (message.type == Wire.STOP) break
                        }
                    })
                    val audioProducer = worker {
                        DataOutputStream(outputAudio).use { out ->
                            out.writeInt(0x00726177)
                            audioPts.forEachIndexed { index, pts ->
                                if (index >= 24) Thread.sleep(20)
                                out.writeLong(pts); out.writeInt(3840); out.write(ByteArray(3840)); out.flush()
                            }
                        }
                    }
                    val videoProducer = worker {
                        DataOutputStream(outputVideo).use { out ->
                            out.writeInt(0x68323634); out.writeInt(640); out.writeInt(480)
                            val csd = BackendTest().sps(640, 480)
                            out.writeLong(Long.MIN_VALUE); out.writeInt(csd.size); out.write(csd)
                            videoPts.forEachIndexed { index, pts ->
                                if (index >= 2) Thread.sleep(if (index % 2 == 0) 30 else 100)
                                out.writeLong(pts or (1L shl 62)); out.writeInt(6)
                                out.write(byteArrayOf(0, 0, 0, 1, 0x65, index.toByte())); out.flush()
                            }
                        }
                    }
                    workers.add(audioProducer); workers.add(videoProducer)
                    audioProducer.join(5000); videoProducer.join(5000)
                    workers.take(2).forEach { it.join(2000); assertFalse(it.isAlive) }
                    assertTrue(errors.toString(), errors.isEmpty())
                    assertTrue("Receiver did not get every admitted packet", allReceived.await(5, TimeUnit.SECONDS))
                    mux.finish(Wire.control(Wire.STOP, 1, Wire.obj("reason" to "user")))
                    workers[3].join(2000); workers[2].join(2000)
                    peer.get(3, TimeUnit.SECONDS)
                    assertTrue(errors.toString(), errors.isEmpty())
                    assertEquals(audioPts, received.filter { it.first == 2 }.map { it.second })
                    assertEquals(videoPts, received.filter { it.first == 1 }.map { it.second })
                } finally {
                    outputVideo.close(); outputAudio.close(); inputVideo.close(); inputAudio.close(); link.close()
                    workers.forEach { it.interrupt(); it.join(1000) }
                }
            }
        }
    }
    @Test fun blockedRealTcpWriterProducesSpecificCapacityAndInflightDiagnostics() {
        ServerSocket(0).use { server ->
            server.receiveBufferSize = 1024
            val release = CountDownLatch(1)
            val peer = CompletableFuture.runAsync {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    Wire.read(socket.inputStream)
                    Wire.write(socket.outputStream, Wire.control(Wire.HELLO, 0,
                        Wire.obj("role" to "receiver", "versions" to org.json.JSONArray().put(3), "name" to "Blocked fixture")))
                    release.await(10, TimeUnit.SECONDS) // Deliberately do not consume media.
                }
            }
            ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                link.connect()
                val mux = MediaBudget(false); ready(mux)
                val writing = CountDownLatch(1)
                val packet = frame(1, 0, Wire.MAX_VIDEO)
                val writer = CompletableFuture.runAsync {
                    mux.beginWrite(packet); writing.countDown()
                    try { link.send(packet) } catch (e: IOException) { /* Test cancellation closes socket. */ }
                    finally { mux.endWrite() }
                }
                try {
                    assertTrue(writing.await(1, TimeUnit.SECONDS))
                    mux.offer(frame(1, 20_000, 4 * 1024 * 1024)); mux.offer(frame(1, 40_000, 4 * 1024 * 1024))
                    val failure = assertThrows(SenderCongestionException::class.java) { mux.offer(frame(1, 60_000)) }
                    assertEquals(SenderLimit.MEDIA_BYTES, failure.snapshot.limit)
                    assertEquals(SenderQueue.VIDEO, failure.snapshot.queue)
                    assertEquals(8 * 1024 * 1024, failure.snapshot.bytes)
                    assertEquals(Wire.MAX_VIDEO, failure.snapshot.writerBytes)
                    assertTrue(failure.snapshot.writerMs >= 400)
                    assertFalse(failure.snapshot.fields().contains("127.0.0.1"))
                } finally { release.countDown(); link.close(); writer.get(2, TimeUnit.SECONDS) }
            }
            peer.get(3, TimeUnit.SECONDS)
        }
    }
}
