package dev.screencopy.sender

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.screencopy.receiver.protocol.Configuration
import org.screencopy.receiver.protocol.ProtocolException
import org.screencopy.receiver.protocol.Request
import org.screencopy.receiver.protocol.Wire as ReceiverWire
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.screencopy.receiver.protocol.Message as ReceiverMessage
import org.screencopy.receiver.protocol.Type as ReceiverType

/** Uses the receiver owner's existing build read-only; no shared mutable app code. */
class InteropTest {
    @Test fun productionStopDecisionAndRealLinkInteroperateWithReceiverParserWithoutFatalCancellation() {
        for (fault in listOf(false, true)) for (accepted in listOf(false, true)) ServerSocket(0).use { server ->
            val outcome = SessionOutcome()
            val peer = CompletableFuture.runAsync {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    assertEquals(ReceiverType.HELLO, ReceiverWire.read(socket.inputStream).type)
                    ReceiverWire.write(socket.outputStream, ReceiverMessage.json(ReceiverType.HELLO,
                        Wire.obj("role" to "receiver", "versions" to org.json.JSONArray().put(3), "name" to "Stop receiver")))
                    val request = ReceiverWire.read(socket.inputStream)
                    assertEquals(ReceiverType.SESSION_REQUEST, request.type)
                    Request.parse(request.json())
                    if (accepted) ReceiverWire.write(socket.outputStream, ReceiverMessage.json(ReceiverType.SESSION_ACCEPT,
                        Wire.obj("tracks" to org.json.JSONArray().put(1)), request.session))
                    val terminal = ReceiverWire.read(socket.inputStream)
                    assertEquals(if (fault) ReceiverType.ERROR else ReceiverType.STOP, terminal.type)
                    assertEquals(request.session, terminal.session)
                    if (fault) {
                        assertEquals("INTERNAL", terminal.json().getString("code"))
                        assertTrue(terminal.json().getBoolean("fatal"))
                    } else ReceiverWire.write(socket.outputStream, ReceiverMessage.json(ReceiverType.SESSION_END, terminal.json(), request.session))
                    assertEquals(-1, socket.inputStream.read())
                }
            }
            ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                link.connect()
                link.send(Wire.control(Wire.SESSION_REQUEST, 47, Settings().apply { audio = false }.request()))
                if (accepted) assertEquals(Wire.SESSION_ACCEPT, link.receive().type)
                if (fault) outcome.record(IllegalStateException("real earlier fault"), SessionOutcome.Origin.CAPTURE)
                outcome.requestStop()
                outcome.record(java.io.EOFException(), SessionOutcome.Origin.RECEIVER_READ)
                outcome.record(SessionCancelledException(), SessionOutcome.Origin.CAPTURE)
                val mux = MediaBudget(false)
                mux.finish(outcome.terminal(47, false))
                link.send(checkNotNull(mux.poll(1)))
                if (!fault) assertEquals(Wire.SESSION_END, link.receive().type)
            }
            peer.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun bothImplementationsAgreeOnSharedBytesAndCodecStartup() {
        val fixtures = Path.of(System.getProperty("fixtures"))
        val cases = JSONObject(Files.readString(fixtures.resolve("v3.json"))).getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val item = cases.getJSONObject(index)
            val bytes = WireTest.unhex(item.getString("hex"))
            val expected = item.getBoolean("valid")
            try {
                val decoded = Wire.read(ByteArrayInputStream(bytes))
                assertTrue("Sender accepted ${item.getString("name")}", expected)
                val out = ByteArrayOutputStream().also { Wire.write(it, decoded) }
                assertArrayEquals("Sender changed fixture bytes", bytes, out.toByteArray())
            } catch (e: IOException) { assertFalse("Sender rejected ${item.getString("name")}", expected) }
            try {
                val decoded = ReceiverWire.read(ByteArrayInputStream(bytes))
                assertTrue("Receiver accepted ${item.getString("name")}", expected)
                val out = ByteArrayOutputStream().also { ReceiverWire.write(it, decoded) }
                assertArrayEquals("Receiver changed fixture bytes", bytes, out.toByteArray())
            } catch (e: Exception) {
                if (e !is ProtocolException && e !is IOException) throw e
                assertFalse("Receiver rejected ${item.getString("name")}", expected)
            }
        }
        val head = WireTest.unhex(Files.readString(fixtures.resolve("opus-head.hex")).trim())
        val settings = Settings()
        val accepted = Configuration.audio(cross(Wire.config(1, 2, 1, CodecConfig.audio("opus", head))), request(settings))
        assertEquals(312, accepted.preSkip)
        settings.audioCodec = "aac"
        Configuration.audio(cross(Wire.config(1, 2, 1, CodecConfig.audio("aac", byteArrayOf(0x11, 0x90.toByte())))), request(settings))
        settings.audioCodec = "raw"
        Configuration.audio(cross(Wire.config(1, 2, 1, CodecConfig.audio("raw", byteArrayOf()))), request(settings))
        settings.audio = false
        assertEquals(listOf(1), request(settings).tracks)
        val session = JSONObject(Files.readString(fixtures.resolve("session.json")))
        val parsedHello = cross(Wire.control(Wire.HELLO, 0, session.getJSONObject("senderHello")))
        assertFalse(parsedHello.has("code")); assertFalse(parsedHello.has("sessionCode"))
        assertEquals(3, parsedHello.getJSONArray("versions").getInt(0))
        val back = org.screencopy.receiver.protocol.Message.json(
            org.screencopy.receiver.protocol.Type.HELLO, session.getJSONObject("receiverHello"))
        val encoded = ByteArrayOutputStream().also { ReceiverWire.write(it, back) }
        val receiverHello = Wire.read(ByteArrayInputStream(encoded.toByteArray())).json()
        assertEquals("Fixture receiver", receiverHello.getString("name"))
        assertFalse(receiverHello.has("code")); assertFalse(receiverHello.has("sessionCode"))
        println("PASS: ${cases.length()} v3 wire fixtures through BOTH Kotlin implementations; code-free HELLO round-trip, internal cast IDs, request + Opus/AAC/PCM config, audio-off preserved.")
    }
    private fun request(settings: Settings): Request = Request.parse(cross(Wire.control(Wire.SESSION_REQUEST, 1, settings.request())))
    private fun cross(message: Wire.Message): JSONObject {
        val out = ByteArrayOutputStream().also { Wire.write(it, message) }
        return ReceiverWire.read(ByteArrayInputStream(out.toByteArray())).json()
    }

    @Test fun actualLoopbackUsesSenderLinkAndReceiverProtocolWithoutHumanCode() {
        ServerSocket(0).use { server ->
            val peer = CompletableFuture.runAsync {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val hello = ReceiverWire.read(socket.inputStream)
                    assertEquals(ReceiverType.HELLO, hello.type); assertEquals(0L, hello.session)
                    assertFalse(hello.json().has("code")); assertFalse(hello.json().has("sessionCode"))
                    ReceiverWire.write(socket.outputStream, ReceiverMessage.json(ReceiverType.HELLO,
                        Wire.obj("role" to "receiver", "versions" to org.json.JSONArray().put(3), "name" to "Loopback receiver")))
                    val requested = ReceiverWire.read(socket.inputStream)
                    assertEquals(ReceiverType.SESSION_REQUEST, requested.type); assertEquals(47L, requested.session)
                    assertEquals(listOf(1, 2), Request.parse(requested.json()).tracks)
                    ReceiverWire.write(socket.outputStream, ReceiverMessage.json(ReceiverType.SESSION_ACCEPT,
                        Wire.obj("tracks" to org.json.JSONArray().put(1).put(2)), 47))
                    val ping = ReceiverWire.read(socket.inputStream)
                    assertEquals(ReceiverType.PING, ping.type); assertEquals(47L, ping.session)
                    ReceiverWire.write(socket.outputStream, ReceiverMessage.json(ReceiverType.PONG, ping.json(), 47))
                    val stop = ReceiverWire.read(socket.inputStream)
                    assertEquals(ReceiverType.STOP, stop.type); assertEquals(47L, stop.session)
                    ReceiverWire.write(socket.outputStream, ReceiverMessage.json(ReceiverType.SESSION_END, stop.json(), 47))
                }
            }
            ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                link.connect()
                link.send(Wire.control(Wire.SESSION_REQUEST, 47, Settings().request()))
                assertEquals(Wire.SESSION_ACCEPT, link.receive().type)
                link.send(Wire.control(Wire.PING, 47, Wire.obj("nonce" to 7)))
                assertEquals(7, link.receive().json().getInt("nonce"))
                link.send(Wire.control(Wire.STOP, 47, Wire.obj("reason" to "user")))
                assertEquals(Wire.SESSION_END, link.receive().type)
            }
            peer.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun receiverParserLoopbackBusyRemainsAnExplicitRejection() {
        ServerSocket(0).use { server ->
            val peer = CompletableFuture.runAsync {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    assertEquals(ReceiverType.HELLO, ReceiverWire.read(socket.inputStream).type)
                    ReceiverWire.write(socket.outputStream, ReceiverMessage.json(ReceiverType.REJECT,
                        Wire.obj("code" to "BUSY", "message" to "Receiver already playing")))
                    assertEquals(-1, socket.inputStream.read())
                }
            }
            ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                assertTrue(assertThrows(IOException::class.java) { link.connect() }.message.orEmpty().startsWith("BUSY"))
            }
            peer.get(3, TimeUnit.SECONDS)
        }
    }
}
