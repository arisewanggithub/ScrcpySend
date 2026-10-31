package dev.screencopy.sender

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.DatagramSocket
import java.net.DatagramPacket
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.nio.file.Files
import java.nio.file.Path

class LanSessionTest {
    private fun hello() = Wire.control(Wire.HELLO, 0,
        Wire.obj("role" to "receiver", "versions" to JSONArray().put(3), "name" to "Family TV"))

    @Test fun realCodeFreeHelloThenRequestPreservesPositiveCastSessionAndAcceptance() {
        ServerSocket(0).use { server ->
            val peer = CompletableFuture.runAsync {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val received = Wire.read(socket.inputStream)
                    assertEquals(Wire.HELLO, received.type); assertEquals(0L, received.session)
                    assertEquals(3, received.json().getJSONArray("versions").getInt(0))
                    assertFalse(received.json().has("code")); assertFalse(received.json().has("sessionCode"))
                    assertFalse(received.json().has("senderId"))
                    Wire.write(socket.outputStream, hello())
                    val request = Wire.read(socket.inputStream)
                    assertEquals(Wire.SESSION_REQUEST, request.type); assertEquals(19L, request.session)
                    assertTrue(request.json().getJSONObject("audio").getBoolean("enabled"))
                    Wire.write(socket.outputStream, Wire.control(Wire.SESSION_ACCEPT, request.session,
                        Wire.obj("tracks" to JSONArray().put(1).put(2))))
                }
            }
            ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                link.connect(); link.send(Wire.control(Wire.SESSION_REQUEST, 19, Settings().request()))
                val accepted = link.receive()
                assertEquals(Wire.SESSION_ACCEPT, accepted.type); assertEquals(19L, accepted.session)
            }
            peer.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun busyAtHelloIsAnExplicitRejectionWithoutCaptureRequest() {
        ServerSocket(0).use { server ->
            val peer = CompletableFuture.runAsync {
                server.accept().use {
                    it.soTimeout = 3000; Wire.read(it.inputStream)
                    Wire.write(it.outputStream, Wire.control(Wire.REJECT, 0, Wire.obj("code" to "BUSY", "message" to "Receiver already playing")))
                    assertEquals(-1, it.inputStream.read())
                }
            }
            ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                assertTrue(assertThrows(IOException::class.java) { link.connect() }.message.orEmpty().startsWith("BUSY"))
            }
            peer.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun obsoleteHumanCodeFieldsAreIgnoredInV3AndNeverCompared() {
        ServerSocket(0).use { server ->
            val peer = CompletableFuture.runAsync {
                server.accept().use {
                    Wire.read(it.inputStream)
                    val response = hello().json().put("code", "obsolete").put("sessionCode", JSONObject.NULL)
                    Wire.write(it.outputStream, Wire.control(Wire.HELLO, 0, response))
                }
            }
            ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { it.connect() }
            peer.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun socketOwnershipPrecedesConnectAndCancellationInterruptsHello() {
        ServerSocket(0).use { server ->
            val received = CountDownLatch(1)
            val stop = CountDownLatch(1)
            val peer = CompletableFuture.runAsync {
                server.accept().use { Wire.read(it.inputStream); received.countDown(); check(stop.await(3, TimeUnit.SECONDS)) }
            }
            ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                assertEquals(1L, received.count)
                val connecting = CompletableFuture.runAsync { link.connect() }
                try {
                    assertTrue(received.await(2, TimeUnit.SECONDS)); link.close()
                    assertTrue(assertThrows(ExecutionException::class.java) { connecting.get(2, TimeUnit.SECONDS) }.cause is IOException)
                } finally { stop.countDown() }
            }
            peer.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun trickledHelloCannotExtendAbsoluteStartupDeadline() {
        ServerSocket(0).use { server ->
            val stop = CountDownLatch(1)
            val peer = CompletableFuture.runAsync {
                try {
                    server.accept().use { socket ->
                        Wire.read(socket.inputStream)
                        val bytes = ByteArrayOutputStream().also { Wire.write(it, hello()) }.toByteArray()
                        for (byte in bytes) {
                            if (stop.await(20, TimeUnit.MILLISECONDS)) break
                            socket.outputStream.write(byte.toInt()); socket.outputStream.flush()
                        }
                    }
                } catch (expected: IOException) { /* Client closes at its overall deadline. */ }
            }
            try {
                ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use {
                    assertThrows(SocketTimeoutException::class.java) { it.connect(200) }
                }
            } finally { stop.countDown() }
            peer.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun discoveryUsesSourceAddressAndNameWithNoCodeRequirement() {
        val fixture = Files.readString(Path.of(System.getProperty("fixtures"), "discovery.json"))
        val forgedHost = JSONObject(fixture).put("host", "198.51.100.99").toString().toByteArray()
        val receiver = Discovery.decode(forgedHost, "192.0.2.12", 10)
        assertEquals("192.0.2.12", receiver.host)
        assertTrue(receiver.toString().contains("192.0.2.12:27184"))
        assertTrue(receiver.toString().contains("Fixture receiver"))
        assertFalse(JSONObject(fixture).has("code")); assertFalse(JSONObject(fixture).has("sessionCode"))
        assertThrows(Exception::class.java) { Discovery.decode(fixture.replace("\"version\":3", "\"version\":2").toByteArray(), "source", 1) }
        val obsolete = JSONObject(fixture).put("code", JSONObject.NULL).put("sessionCode", "unused").toString()
        assertEquals(receiver, Discovery.decode(obsolete.toByteArray(), "192.0.2.12", 10))
    }

    @Test fun nameAndSourceEndpointRefreshStayDistinctWithoutSavedManualSelection() {
        val rows = ReceiverCasting { 1L }
        rows.updateDiscovery(listOf(Discovery.Receiver("TV", "192.0.2.12", 27184, "available", 1)))
        assertEquals("TV", rows.rows(true).single().target.name)
        rows.updateDiscovery(listOf(Discovery.Receiver("Living room", "192.0.2.12", 27184, "available", 1)))
        assertEquals("Living room", rows.rows(true).single().target.name)
        rows.updateDiscovery(listOf(Discovery.Receiver("Living room", "192.0.2.13", 27184, "available", 1)))
        assertEquals("192.0.2.13", rows.rows(true).single().target.host)
        assertNull(rows.snapshot().active)
        rows.updateDiscovery(emptyList())
        assertTrue(rows.rows(true).isEmpty())
    }

    @Test fun udpDatagramCarriesActualSourceAndNameRatherThanAdvertisedAddress() {
        val loopback = InetAddress.getLoopbackAddress()
        DatagramSocket(0, loopback).use { listener ->
            listener.soTimeout = 2000
            DatagramSocket(0, loopback).use { sender ->
                val data = Wire.obj("protocol" to "screencopy", "version" to 3, "name" to "Family TV",
                    "port" to 27184, "state" to "available", "host" to "198.51.100.99").toString().toByteArray()
                sender.send(DatagramPacket(data, data.size, loopback, listener.localPort))
                val packet = DatagramPacket(ByteArray(1025), 1025)
                listener.receive(packet)
                val decoded = Discovery.decode(packet.data.copyOf(packet.length), checkNotNull(packet.address.hostAddress), 1)
                assertEquals(loopback.hostAddress, decoded.host); assertEquals("Family TV", decoded.name)
            }
        }
    }

    @Test fun busyAfterRequestDoesNotBecomeAFalseAcceptance() {
        ServerSocket(0).use { server ->
            val peer = CompletableFuture.runAsync {
                server.accept().use { socket ->
                    Wire.read(socket.inputStream); Wire.write(socket.outputStream, hello())
                    val request = Wire.read(socket.inputStream)
                    Wire.write(socket.outputStream, Wire.control(Wire.REJECT, request.session,
                        Wire.obj("code" to "BUSY", "message" to "Receiver already playing")))
                }
            }
            ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                link.connect(); link.send(Wire.control(Wire.SESSION_REQUEST, 9, Settings().request()))
                val response = link.receive()
                assertEquals(Wire.REJECT, response.type)
                assertEquals("BUSY", response.json().getString("code")); assertEquals(9L, response.session)
            }
            peer.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun internalEndpointValidationAndNoPeerFieldsInSavedDraftRemain() {
        assertEquals("Receiver", ReceiverTarget("192.0.2.12", 27184).name)
        assertThrows(IllegalArgumentException::class.java) { ReceiverTarget("", 27184) }
        assertThrows(IllegalArgumentException::class.java) { ReceiverTarget("host", 0) }
        assertThrows(IllegalArgumentException::class.java) { ReceiverTarget("host", 27184, "") }
        val saved = WirelessPairing.nonSensitiveDraft(mapOf("host" to "192.0.2.12", "port" to "27184",
            "pairPort" to "37", "videoBitrate" to "8000000"))
        assertEquals(mapOf("pairPort" to "37", "videoBitrate" to "8000000"), saved)
    }
}
