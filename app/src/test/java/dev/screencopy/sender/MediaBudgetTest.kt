package dev.screencopy.sender

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class MediaBudgetTest {
    private fun frame(track: Int, generation: Int, pts: Long, size: Int) =
        Wire.Message(if (track == 1) Wire.VIDEO_FRAME else Wire.AUDIO_PACKET, 0, 1, track, generation, pts, ByteArray(size))
    private fun configure(mux: MediaBudget, track: Int, generation: Int) =
        mux.offer(Wire.config(1, track, generation, Wire.obj("codec" to if (track == 1) "h264" else "opus")))

    @Test fun allTracksMustBeReadyAndThenFair() {
        val mux = MediaBudget(true)
        configure(mux, 1, 1); configure(mux, 2, 1)
        assertEquals(Wire.STREAM_CONFIG, mux.poll(1)?.type); assertEquals(Wire.STREAM_CONFIG, mux.poll(1)?.type)
        mux.offer(frame(1, 1, 1, 10)); mux.offer(frame(1, 1, 2, 10)); mux.offer(frame(2, 1, 1, 10))
        mux.ready(1, 1); assertNull(mux.poll(1)); mux.ready(2, 1)
        assertEquals(1, mux.poll(1)?.track); assertEquals(2, mux.poll(1)?.track); assertEquals(1, mux.poll(1)?.track)
        assertEquals(0, mux.queuedBytes())
    }
    @Test fun rotationDrainsOldGenerationAndKeepsAudioReady() {
        val mux = MediaBudget(true)
        configure(mux, 1, 1); configure(mux, 2, 1)
        mux.poll(1); mux.poll(1); mux.ready(1, 1); mux.ready(2, 1)
        mux.offer(frame(1, 1, 10, 5)); configure(mux, 1, 2)
        mux.offer(frame(1, 2, 20, 5)); mux.offer(frame(2, 1, 20, 5))
        assertEquals(Wire.VIDEO_FRAME, mux.poll(1)?.type); assertEquals(Wire.AUDIO_PACKET, mux.poll(1)?.type)
        val config = checkNotNull(mux.poll(1))
        assertEquals(2, config.generation); assertEquals(Wire.STREAM_CONFIG, config.type)
        assertNull(mux.poll(1)); mux.ready(1, 2); assertEquals(2, mux.poll(1)?.generation)
        assertTrue(mux.allReady()); assertThrows(IOException::class.java) { mux.ready(1, 1) }
    }
    @Test fun exactBytesAndDurationLimits() {
        val mux = MediaBudget(false)
        configure(mux, 1, 1); mux.poll(1); mux.ready(1, 1)
        mux.offer(frame(1, 1, 0, 4 * 1024 * 1024)); mux.offer(frame(1, 1, 500_000, 4 * 1024 * 1024))
        assertEquals(8 * 1024 * 1024, mux.queuedBytes())
        assertThrows(IOException::class.java) { mux.offer(frame(1, 1, 500_000, 1)) }
        val duration = MediaBudget(false)
        configure(duration, 1, 1); duration.poll(1)
        duration.offer(frame(1, 1, 0, 1)); duration.offer(frame(1, 1, 500_000, 1))
        assertThrows(IOException::class.java) { duration.offer(frame(1, 1, 500_001, 1)) }
    }
    @Test fun controlsPrioritizedAndBounded() {
        val mux = MediaBudget(false)
        repeat(64) { mux.offer(Wire.control(Wire.PING, 1, Wire.obj("nonce" to it))) }
        assertThrows(IOException::class.java) { mux.offer(Wire.control(Wire.PING, 1, Wire.obj("nonce" to 65))) }
        assertEquals(Wire.PING, mux.poll(1)?.type)
    }

    @Test fun oldReadyCannotEraseNextGenerationDeadline() {
        var time = 1L
        val mux = MediaBudget(false) { time }
        configure(mux, 1, 1); configure(mux, 1, 2)
        assertEquals(1, mux.poll(1)?.generation)
        mux.ready(1, 1)
        assertEquals(2, mux.poll(1)?.generation)
        time += 9_999_999_999L
        assertNull(mux.expiredTrack())
        time++
        assertEquals(1, mux.expiredTrack())
        assertThrows(IOException::class.java) { mux.ready(1, 1) }
        assertEquals(1, mux.expiredTrack())
        mux.ready(1, 2)
        assertNull(mux.expiredTrack())
    }

    @Test fun audioReadinessDoesNotClearVideoRestartDeadline() {
        var time = 1L
        val mux = MediaBudget(true) { time }
        configure(mux, 1, 1); configure(mux, 2, 1)
        mux.poll(1); mux.poll(1); mux.ready(1, 1); mux.ready(2, 1)
        configure(mux, 1, 2); mux.poll(1)
        mux.offer(frame(2, 1, 100, 3))
        assertEquals(2, mux.poll(1)?.track)
        time += 10_000_000_000L
        assertEquals(1, mux.expiredTrack())
    }

    @Test fun wakingConcurrentConfigWriterRetainsItsNewDeadline() {
        val time = AtomicLong(1)
        val mux = MediaBudget(false, time::get)
        configure(mux, 1, 1); configure(mux, 1, 2)
        mux.poll(1)
        val waiting = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val nextConfig = executor.submit<Wire.Message?> { waiting.countDown(); mux.poll(2000) }
            assertTrue(waiting.await(2, TimeUnit.SECONDS))
            mux.ready(1, 1)
            assertEquals(2, nextConfig.get(2, TimeUnit.SECONDS)?.generation)
            time.addAndGet(10_000_000_000L)
            assertEquals(1, mux.expiredTrack())
        } finally { executor.shutdownNow() }
    }
}
