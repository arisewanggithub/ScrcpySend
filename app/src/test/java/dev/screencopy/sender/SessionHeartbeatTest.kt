package dev.screencopy.sender

import android.content.Context
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

class SessionHeartbeatTest {
    @Test fun delayedDecoderReadinessDrainsOldVideoBeforeRotationWithoutChangingPtsOrLimits() {
        val sender = SenderSession(Mockito.mock(Context::class.java), Settings().apply { audio = false },
            ReceiverTarget("127.0.0.1", 27184), {}, {}, {})
        fun field(name: String) = SenderSession::class.java.getDeclaredField(name).apply { isAccessible = true }
        val id = field("session").getLong(sender)
        val outcome = field("outcome").get(sender) as SessionOutcome
        val restartAttempted = CountDownLatch(1)
        val received = CountDownLatch(2)
        val messages = java.util.concurrent.CopyOnWriteArrayList<Wire.Message>()
        val executor = Executors.newFixedThreadPool(4)
        try { ServerSocket(0).use { server ->
            server.soTimeout = 10_000
            val peer = CompletableFuture.runAsync({
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    assertEquals(Wire.HELLO, Wire.read(socket.inputStream).type)
                    Wire.write(socket.outputStream, Wire.control(Wire.HELLO, 0,
                        Wire.obj("role" to "receiver", "versions" to org.json.JSONArray().put(3), "name" to "Rotation fixture")))
                    while (true) {
                        val message = Wire.read(socket.inputStream)
                        when (message.type) {
                            Wire.STREAM_CONFIG -> {
                                messages += message
                                if (message.generation == 1) {
                                    assertTrue(restartAttempted.await(2, TimeUnit.SECONDS))
                                    Thread.sleep(750)
                                }
                                Wire.write(socket.outputStream, Wire.Message(Wire.STREAM_READY, 0, id, 1,
                                    message.generation, -1, Wire.obj("ok" to true).toString().toByteArray()))
                            }
                            Wire.VIDEO_FRAME -> { messages += message; received.countDown() }
                            Wire.PING -> Wire.write(socket.outputStream, Wire.control(Wire.PONG, id, message.json()))
                            Wire.STOP, Wire.ERROR -> {
                                Wire.write(socket.outputStream, Wire.control(Wire.SESSION_END, id, Wire.obj("reason" to "user")))
                                break
                            }
                            else -> fail("Unexpected message ${message.type}")
                        }
                    }
                }
            }, executor)
            ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                link.connect()
                field("link").set(sender, link)
                field("acceptedSession").setBoolean(sender, true)
                field("requestSent").setBoolean(sender, true)
                field("lastIncoming").setLong(sender, System.nanoTime())
                val read = SenderSession::class.java.getDeclaredMethod("readLoop").apply { isAccessible = true }
                val write = SenderSession::class.java.getDeclaredMethod("writeLoop").apply { isAccessible = true }
                val reader = CompletableFuture.runAsync({ read.invoke(sender) }, executor)
                val writer = CompletableFuture.runAsync({ write.invoke(sender) }, executor)
                try {
                    val bytes = WireTest.unhex("000000016588")
                    sender.config(1, 1, CodecConfig.video("h264", BackendTest().sps(640, 480)))
                    sender.packet(Wire.Message(Wire.VIDEO_FRAME, 1, id, 1, 1, 1_000_000, bytes))
                    val restart = CompletableFuture.runAsync({
                        val started = System.nanoTime()
                        restartAttempted.countDown()
                        sender.awaitVideoDrain()
                        assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(700))
                        sender.config(1, 2, CodecConfig.video("h264", BackendTest().sps(480, 640)))
                        sender.packet(Wire.Message(Wire.VIDEO_FRAME, 1, id, 1, 2, 1_726_606, bytes))
                    }, executor)
                    restart.get(5, TimeUnit.SECONDS)
                    assertTrue(received.await(5, TimeUnit.SECONDS))
                    assertEquals(listOf(Wire.STREAM_CONFIG, Wire.VIDEO_FRAME, Wire.STREAM_CONFIG, Wire.VIDEO_FRAME),
                        messages.map { it.type })
                    assertEquals(listOf(1, 1, 2, 2), messages.map { it.generation })
                    assertEquals(listOf(1_000_000L, 1_726_606L),
                        messages.filter { it.type == Wire.VIDEO_FRAME }.map { it.pts })
                    assertTrue(messages.filter { it.type == Wire.VIDEO_FRAME }.all { it.payload.contentEquals(bytes) })
                    assertNull(outcome.failure)
                } finally {
                    sender.stop()
                    try { writer.get(2, TimeUnit.SECONDS); peer.get(2, TimeUnit.SECONDS) }
                    finally { link.close(); reader.get(2, TimeUnit.SECONDS) }
                }
            }
        } } finally { executor.shutdownNow() }
    }

    @Test fun continuousMediaStaysConnectedBeyondSocketTimeoutWhenPeerOnlyRepliesToProbes() {
        val sender = SenderSession(Mockito.mock(Context::class.java), Settings().apply { audio = false },
            ReceiverTarget("127.0.0.1", 27184), {}, {}, {})
        fun field(name: String) = SenderSession::class.java.getDeclaredField(name).apply { isAccessible = true }
        val id = field("session").getLong(sender)
        val outcome = field("outcome").get(sender) as SessionOutcome
        val frame = Wire.Message(Wire.VIDEO_FRAME, 1, id, 1, 1, 1_000_000, byteArrayOf(1))
        val terminal = AtomicReference<Wire.Message?>()
        val queue = Mockito.mock(MediaBudget::class.java)
        Mockito.`when`(queue.poll(Mockito.anyLong())).thenAnswer {
            terminal.getAndSet(null) ?: run { Thread.sleep(5); frame }
        }
        Mockito.doAnswer { terminal.set(it.getArgument(0)); null }.`when`(queue).finish(Mockito.any(Wire.Message::class.java) ?: frame)
        field("mux").set(sender, queue)
        val probes = CountDownLatch(7)
        val media = AtomicInteger()
        ServerSocket(0).use { server ->
            server.soTimeout = 20_000
            val peer = CompletableFuture.runAsync {
                server.accept().use { socket ->
                    socket.soTimeout = 20_000
                    assertEquals(Wire.HELLO, Wire.read(socket.inputStream).type)
                    Wire.write(socket.outputStream, Wire.control(Wire.HELLO, 0,
                        Wire.obj("role" to "receiver", "versions" to org.json.JSONArray().put(3), "name" to "Heartbeat fixture")))
                    while (true) {
                        val message = Wire.read(socket.inputStream)
                        when (message.type) {
                            Wire.PING -> {
                                Wire.write(socket.outputStream, Wire.control(Wire.PONG, id, message.json()))
                                probes.countDown()
                            }
                            Wire.VIDEO_FRAME -> media.incrementAndGet()
                            Wire.STOP, Wire.ERROR -> {
                                Wire.write(socket.outputStream, Wire.control(Wire.SESSION_END, id, Wire.obj("reason" to "user")))
                                break
                            }
                            else -> fail("Unexpected message ${message.type}")
                        }
                    }
                }
            }
            ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                link.connect()
                field("link").set(sender, link)
                field("acceptedSession").setBoolean(sender, true)
                field("requestSent").setBoolean(sender, true)
                field("lastIncoming").setLong(sender, System.nanoTime())
                val read = SenderSession::class.java.getDeclaredMethod("readLoop").apply { isAccessible = true }
                val write = SenderSession::class.java.getDeclaredMethod("writeLoop").apply { isAccessible = true }
                val reader = CompletableFuture.runAsync { read.invoke(sender) }
                val writer = CompletableFuture.runAsync { write.invoke(sender) }
                try {
                    // Seven probes at two-second spacing cover more than the real ten-second read timeout.
                    assertTrue("Continuous media must not starve liveness probes", probes.await(16, TimeUnit.SECONDS))
                    assertTrue(media.get() > 100)
                    assertNull(outcome.failure)
                    assertFalse(reader.isDone)
                } finally {
                    sender.stop()
                    try { writer.get(2, TimeUnit.SECONDS); peer.get(2, TimeUnit.SECONDS) }
                    finally { link.close(); reader.get(2, TimeUnit.SECONDS) }
                }
            }
        }
    }
}
