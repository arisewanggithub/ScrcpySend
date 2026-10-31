package dev.screencopy.sender

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Transport integration with actual scrcpy framing, not a claim of device capture. */
class CaptureBridgeTest {
    @Test fun cleanupRetainsFirstRealFailureAndSuppressesLaterCleanupFailureWithoutSkippingWork() {
        var cleanups = 0
        val bridge = object : CaptureBridge {
            override fun deploy(id: String, server: ByteArray) = Unit
            override fun capabilities(id: String) = ""
            override fun launch(id: String, settings: Settings): InputStream = error("No capture")
            override fun heartbeat(id: String) = Unit
            override fun openMedia(id: String, track: Int): InputStream = error("No capture")
            override fun cleanup(id: String) { cleanups++; throw java.io.IOException("later cleanup failure") }
            override fun close() { throw java.io.IOException("first close failure") }
        }
        val backend = CaptureBackend(context(), bridge) { throw it }
        backend.deploy()
        val failure = assertThrows(java.io.IOException::class.java) { backend.close() }
        assertEquals("first close failure", failure.message)
        assertEquals(1, failure.suppressed.size)
        assertEquals(1, cleanups)
        backend.close()
        assertEquals(1, cleanups)
    }

    private class Tail : InputStream() {
        private val closed = CountDownLatch(1)
        override fun read(): Int { check(closed.await(3, TimeUnit.SECONDS)); return -1 }
        override fun close() { closed.countDown() }
    }
    internal fun context(): Context {
        val context = Mockito.mock(Context::class.java)
        val assets = Mockito.mock(AssetManager::class.java)
        val prefs = Mockito.mock(SharedPreferences::class.java)
        val editor = Mockito.mock(SharedPreferences.Editor::class.java, Mockito.RETURNS_SELF)
        Mockito.`when`(context.getSharedPreferences(Mockito.anyString(), Mockito.anyInt())).thenReturn(prefs)
        Mockito.`when`(prefs.edit()).thenReturn(editor)
        Mockito.`when`(editor.commit()).thenReturn(true)
        Mockito.`when`(context.assets).thenReturn(assets)
        Mockito.`when`(assets.open("scrcpy-server-v3.3.4")).thenAnswer {
            ByteArrayInputStream(Files.readAllBytes(Path.of(System.getProperty("serverAsset"))))
        }
        return context
    }
    @Test fun privilegedBridgeOwnsDeploymentGuardianAndOrderedStreamsPreservingCodecAndPts() {
        val log = Tail(); val videoTail = Tail(); val audioTail = Tail()
        val opened = mutableListOf<Int>()
        val cleaned = mutableListOf<String>()
        var deployed = ""
        val heartbeats = AtomicInteger()
        val heartbeat = CountDownLatch(1)
        val bridge = object : CaptureBridge {
            override fun deploy(id: String, server: ByteArray) {
                deployed = id
                assertEquals(ShellCapturePolicy.SERVER_HASH, Secrets.hex(MessageDigest.getInstance("SHA-256").digest(server)))
            }
            override fun capabilities(id: String) = "verified privileged capability listing"
            override fun launch(id: String, settings: Settings): InputStream {
                assertEquals(deployed, id); assertTrue(settings.audio); assertEquals("opus", settings.audioCodec)
                return log
            }
            override fun heartbeat(id: String) { assertEquals(deployed, id); heartbeats.incrementAndGet(); heartbeat.countDown() }
            override fun openMedia(id: String, track: Int): InputStream {
                assertEquals(deployed, id); opened.add(track)
                return if (track == 1) java.io.SequenceInputStream(ByteArrayInputStream(video()), videoTail)
                    else java.io.SequenceInputStream(ByteArrayInputStream(audio()), audioTail)
            }
            override fun cleanup(id: String) { cleaned.add(id) }
            override fun close() { log.close(); videoTail.close(); audioTail.close() }
        }
        val configs = CountDownLatch(2); val packets = CountDownLatch(2)
        val payloads = java.util.concurrent.CopyOnWriteArrayList<Wire.Message>()
        val failures = java.util.concurrent.CopyOnWriteArrayList<Exception>()
        CaptureBackend(context(), bridge, failures::add).use { backend ->
            backend.deploy()
            assertEquals("verified privileged capability listing", backend.capabilities())
            backend.start(Settings(), 7, object : ScrcpyAdapter.Sink {
                override fun config(track: Int, generation: Int, config: JSONObject) {
                    assertEquals(1, generation)
                    if (track == 1) { assertEquals(640, config.getInt("width")); assertEquals(480, config.getInt("height")) }
                    else { assertEquals("opus", config.getString("codec")); assertEquals(6500000, config.getInt("codecDelayNs")) }
                    configs.countDown()
                }
                override fun packet(packet: Wire.Message) { payloads.add(packet); packets.countDown() }
            })
            assertTrue(configs.await(2, TimeUnit.SECONDS)); assertTrue(packets.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(1, 2), opened)
            assertEquals(setOf(123456789L), payloads.map { it.pts }.toSet())
            assertTrue(heartbeat.await(2, TimeUnit.SECONDS)); assertTrue(heartbeats.get() > 0)
            assertTrue(failures.toString(), failures.isEmpty())
        }
        assertEquals(listOf(deployed), cleaned)
    }
    @Test fun stopBeforeStartupRejectsAnyLaterPrivilegedSocketWork() {
        var launches = 0
        val bridge = object : CaptureBridge {
            override fun deploy(id: String, server: ByteArray) = Unit
            override fun capabilities(id: String) = ""
            override fun launch(id: String, settings: Settings): InputStream { launches++; error("Must not launch") }
            override fun heartbeat(id: String) = Unit
            override fun openMedia(id: String, track: Int): InputStream = error("Must not open")
            override fun cleanup(id: String) = Unit
            override fun close() = Unit
        }
        val backend = CaptureBackend(context(), bridge) { throw it }
        backend.close()
        assertThrows(java.io.IOException::class.java) {
            backend.start(Settings(), 1, object : ScrcpyAdapter.Sink {
                override fun config(track: Int, generation: Int, config: JSONObject) = Unit
                override fun packet(packet: Wire.Message) = Unit
            })
        }
        assertEquals(0, launches)
    }
    private fun video(): ByteArray {
        val bits = StringBuilder("010000100000000000011110")
        for (v in listOf(0, 0, 0, 0, 1)) ue(bits, v)
        bits.append('0'); ue(bits, 39); ue(bits, 29); bits.append("1101")
        while (bits.length % 8 != 0) bits.append('0')
        val csd = ByteArrayOutputStream().apply {
            write(byteArrayOf(0, 0, 0, 1, 0x67))
            for (i in bits.indices step 8) write(bits.substring(i, i + 8).toInt(2))
            write(byteArrayOf(0, 0, 0, 1, 0x68, 0x80.toByte()))
        }.toByteArray()
        return ByteArrayOutputStream().also { out ->
            DataOutputStream(out).apply {
                writeByte(0); write(ByteArray(64)); writeInt(0x68323634); writeInt(640); writeInt(480)
                writeLong(Long.MIN_VALUE); writeInt(csd.size); write(csd)
                writeLong((1L shl 62) or 123456789L); writeInt(6); write(byteArrayOf(0, 0, 0, 1, 0x65, 0x88.toByte()))
            }
        }.toByteArray()
    }
    private fun audio(): ByteArray {
        val head = WireTest.unhex("4f707573486561640102380180bb0000000000")
        return ByteArrayOutputStream().also { out ->
            DataOutputStream(out).apply {
                writeInt(0x6f707573); writeLong(Long.MIN_VALUE); writeInt(head.size); write(head)
                writeLong(123456789L); writeInt(3); write(byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte()))
            }
        }.toByteArray()
    }
    private fun ue(bits: StringBuilder, n: Int) {
        val value = Integer.toBinaryString(n + 1); bits.append("0".repeat(value.length - 1)).append(value)
    }
}
