package dev.screencopy.sender

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual kernel socket/dup cancellation without Shizuku, capture, input or settings changes. */
@RunWith(AndroidJUnit4::class)
class CaptureFdDeviceTest {
    @Test fun privateLifecycleShutdownDeliversEofEvenWithoutAnyMediaOutput() {
        val video = ParcelFileDescriptor.createSocketPair()
        val audio = ParcelFileDescriptor.createSocketPair()
        val lifecycle = ParcelFileDescriptor.createSocketPair()
        val ended = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val controller = Thread {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(lifecycle[1]).use { assertEquals(-1, it.read()) }
            } catch (e: Throwable) { failure.set(e) }
            finally { ended.countDown() }
        }.apply { start() }
        try {
            CaptureCancellation.interruptChannels(listOf(video[0], audio[0]), lifecycle[0]) {
                Os.shutdown(it.fileDescriptor, OsConstants.SHUT_RDWR)
            }
            assertTrue(ended.await(1, TimeUnit.SECONDS)); controller.join(1000)
            failure.get()?.let { throw AssertionError(it) }
        } finally { (video.toList() + audio.toList() + lifecycle.toList()).forEach { it.close() } }
    }

    @Test fun productionFdShutdownUnblocksReadEvenWhenPrivilegedDuplicateRemainsOpen() {
        val pair = ParcelFileDescriptor.createSocketPair()
        val owner = OwnedCaptureFds()
        try {
            val input = owner.own(ParcelFileDescriptor.dup(pair[0].fileDescriptor), true)
            val reading = CountDownLatch(1)
            val ended = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            val worker = Thread {
                try { reading.countDown(); assertEquals(-1, input.read()) }
                catch (e: IOException) { /* Cancellation may report EOF or the closed descriptor. */ }
                catch (e: AssertionError) { failure.set(e) }
                finally { ended.countDown() }
            }.apply { start() }
            assertTrue(reading.await(1, TimeUnit.SECONDS))
            owner.close(); owner.close()
            assertTrue(ended.await(1, TimeUnit.SECONDS))
            worker.join(1000)
            failure.get()?.let { throw AssertionError(it) }
            assertThrows(IOException::class.java) {
                owner.own(ParcelFileDescriptor.dup(pair[0].fileDescriptor), true)
            }
        } finally { owner.close(); pair.forEach { it.close() } }
    }
}
