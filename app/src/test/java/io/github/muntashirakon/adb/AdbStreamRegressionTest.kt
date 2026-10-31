package io.github.muntashirakon.adb

import dev.screencopy.sender.ScrcpyAdapter
import dev.screencopy.sender.Settings
import dev.screencopy.sender.Wire
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class AdbStreamRegressionTest {
    private fun stream(): AdbStream {
        val connection = Mockito.mock(AdbConnection::class.java)
        Mockito.`when`(connection.maxData).thenReturn(1024 * 1024)
        return AdbStream(connection, 1)
    }

    @Test fun peerCloseDrainsFinalPayloadAfterPartialRead() {
        val stream = stream()
        val payload = ByteArray(15000) { (it % 256).toByte() }
        stream.addPayload(payload)
        val input = stream.openInputStream()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        val first = input.read(buffer)
        assertEquals(4096, first); out.write(buffer, 0, first)
        stream.notifyClose(true)
        assertFalse(stream.isClosed)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            out.write(buffer, 0, count)
        }
        assertArrayEquals(payload, out.toByteArray())
        assertTrue(stream.isClosed); assertEquals(-1, input.read())
    }

    @Test fun peerCloseDrainsQueuedAndCurrentBuffersInOrder() {
        val stream = stream()
        val first = ByteArray(8000) { 1 }
        val second = ByteArray(9000) { 2 }
        stream.addPayload(first); stream.addPayload(second)
        stream.notifyClose(true)
        val actual = ByteArrayOutputStream()
        val input = stream.openInputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            actual.write(buffer, 0, count)
        }
        assertArrayEquals(first + second, actual.toByteArray())
    }

    @Test fun initializedOpusSilenceOutlivesCommandReadDeadline() {
        val stream = stream().apply { setReadTimeoutMillis(30) }
        val head = byteArrayOf(79, 112, 117, 115, 72, 101, 97, 100, 1, 2, 56, 1, 0x80.toByte(), 0xbb.toByte(), 0, 0, 0, 0, 0)
        val data = ByteArrayOutputStream()
        DataOutputStream(data).apply {
            writeInt(0x6f707573); writeLong(Long.MIN_VALUE); writeInt(head.size); write(head)
        }
        stream.addPayload(data.toByteArray())
        val configured = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val reader = executor.submit {
            ScrcpyAdapter.readStream(stream, 2, Settings(), 1, object : ScrcpyAdapter.Sink {
                override fun config(track: Int, generation: Int, config: JSONObject) { configured.countDown() }
                override fun packet(packet: Wire.Message) { fail("No media was supplied during silence") }
            })
        }
        try {
            assertTrue(configured.await(2, TimeUnit.SECONDS))
            // A real idle read must still be waiting beyond the injected command deadline.
            assertThrows(TimeoutException::class.java) { reader.get(150, TimeUnit.MILLISECONDS) }
            stream.notifyClose(true)
            val end = assertThrows(ExecutionException::class.java) { reader.get(2, TimeUnit.SECONDS) }
            assertTrue(end.cause is EOFException)
        } finally { stream.notifyClose(false); executor.shutdownNow() }
    }

    @Test fun missingAudioInitializationStillTimesOutExplicitly() {
        val stream = stream().apply { setReadTimeoutMillis(30) }
        assertThrows(SocketTimeoutException::class.java) {
            ScrcpyAdapter.readStream(stream, 2, Settings(), 1, object : ScrcpyAdapter.Sink {
                override fun config(track: Int, generation: Int, config: JSONObject) { fail() }
                override fun packet(packet: Wire.Message) { fail() }
            })
        }
    }
}
