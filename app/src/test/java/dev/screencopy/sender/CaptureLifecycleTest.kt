package dev.screencopy.sender

import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real socket EOF through production cancellation; server stop callbacks model pinned Completion,
 * not Android MediaCodec execution. No media output is needed to wake this path. */
class CaptureLifecycleTest {
    private class Channels(audio: Boolean) : Closeable {
        private fun pair(): Pair<Socket, Socket> = ServerSocket(0).use { listener ->
            val client = Socket("127.0.0.1", listener.localPort)
            client to listener.accept()
        }
        val media = (1..if (audio) 2 else 1).map { pair() }
        val lifecycle = pair()
        val eos = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val controlBytes = AtomicInteger()
        val videoStops = AtomicInteger()
        val audioStops = AtomicInteger()
        private val server = Thread {
            try {
                while (lifecycle.second.getInputStream().read() != -1) controlBytes.incrementAndGet()
                eos.countDown()
                videoStops.incrementAndGet()
                if (audio) audioStops.incrementAndGet()
            } finally { stopped.countDown() }
        }.apply { start() }
        fun interrupt(socket: Socket) {
            if (!socket.isInputShutdown) socket.shutdownInput()
            if (!socket.isOutputShutdown) socket.shutdownOutput()
        }
        override fun close() {
            lifecycle.first.close()
            server.join(1000)
            lifecycle.second.close()
            media.forEach { it.first.close(); it.second.close() }
            assertFalse(server.isAlive)
        }
    }
    @Test fun idleVideoAndSilentAudioStopViaControllerEofWithoutAnyNewMediaPacket() {
        Channels(true).use { channels ->
            CaptureCancellation.interruptChannels(channels.media.map { it.first }, null, channels::interrupt)
            assertFalse("Media shutdown alone cannot wake a producer not writing", channels.eos.await(50, TimeUnit.MILLISECONDS))
            CaptureCancellation.interruptChannels(channels.media.map { it.first }, channels.lifecycle.first, channels::interrupt)
            assertTrue(channels.stopped.await(1, TimeUnit.SECONDS))
            assertEquals(0, channels.controlBytes.get())
            assertEquals(1, channels.videoStops.get()); assertEquals(1, channels.audioStops.get())
        }
    }
    @Test fun audioDisabledStillHasPrivateLifecycleAndRepeatedStopDoesNotSendInput() {
        Channels(false).use { channels ->
            repeat(2) { CaptureCancellation.interruptChannels(channels.media.map { it.first }, channels.lifecycle.first, channels::interrupt) }
            assertTrue(channels.stopped.await(1, TimeUnit.SECONDS))
            assertEquals(1, channels.videoStops.get()); assertEquals(0, channels.audioStops.get())
            assertEquals(0, channels.controlBytes.get())
        }
    }
    @Test fun ownerDescriptorDeathAlsoTriggersTheSameEofWithoutMediaOrSignal() {
        Channels(true).use { channels ->
            channels.lifecycle.first.close()
            assertTrue(channels.stopped.await(1, TimeUnit.SECONDS))
            assertEquals(1, channels.videoStops.get()); assertEquals(1, channels.audioStops.get())
            assertEquals(0, channels.controlBytes.get())
        }
    }
    @Test fun productionPrivateSocketIsOpenedAfterMediaNeverExportedAndInterruptedBeforeGuardian() {
        val sources = Path.of(System.getProperty("senderSources"))
        val shell = readUtf8Strict(sources.resolve("ShellCaptureService.kt"))
        val open = shell.substringAfter("override fun openMedia").substringBefore("override fun closeSession")
        assertTrue(open.indexOf("if (last) openLifecycle(id)") < open.indexOf("ParcelFileDescriptor.dup(socket.fileDescriptor)"))
        val lifecycle = open.substringAfter("private fun openLifecycle")
        assertFalse(lifecycle.contains("ParcelFileDescriptor")); assertFalse(lifecycle.contains("getOutputStream"))
        assertTrue(open.contains("track > lastMediaTrack"))
        val stop = shell.substringAfter("private fun cancelOwned")
        assertTrue(stop.indexOf("CaptureCancellation.interruptChannels") < stop.indexOf("it.outputStream.close()"))
        val settings = Settings()
        val options = CaptureBackend.options(settings, "00000001")
        assertTrue(options.contains("control=true")); assertTrue(options.contains("power_on=false"))
        assertTrue(options.contains("clipboard_autosync=false"))
        assertThrows(java.io.IOException::class.java) { ShellCapturePolicy.socket("00000001", 3) }
    }
}
