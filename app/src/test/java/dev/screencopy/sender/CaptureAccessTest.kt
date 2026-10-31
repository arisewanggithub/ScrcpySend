package dev.screencopy.sender

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CaptureAccessTest {
    @Test fun reservationBlocksStartBeforeQueuedSetupRuns() {
        val access = CaptureAccess()
        var deployed = false
        val pending = CaptureAccess.ReservedTask(access.acquire("queued setup")) { deployed = true }
        assertThrows(IllegalStateException::class.java) { access.acquire("service start") }
        assertThrows(IllegalStateException::class.java) { access.acquire("replacement Activity setup") }
        assertFalse(deployed)
        pending.run()
        assertTrue(deployed)
        access.acquire("service start").use {
            assertThrows(IllegalStateException::class.java) { access.acquire("late setup") }
        }
    }

    @Test fun cancelledQueuedTaskCannotRunOrReleaseNewOwner() {
        val access = CaptureAccess()
        val pending = CaptureAccess.ReservedTask(access.acquire("old Activity")) { fail("Cancelled setup ran") }
        pending.cancelPending()
        access.acquire("casting").use {
            pending.run(); pending.cancelPending()
            assertThrows(IllegalStateException::class.java) { access.acquire("another owner") }
        }
        access.requireAvailable()
    }

    @Test fun cancellingRunningSetupDoesNotReleaseItsLeaseEarly() {
        val access = CaptureAccess()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val pending = CaptureAccess.ReservedTask(access.acquire("running setup")) {
            entered.countDown()
            check(finish.await(2, TimeUnit.SECONDS))
        }
        try {
            val running = executor.submit(pending)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            pending.cancelPending()
            assertThrows(IllegalStateException::class.java) { access.acquire("casting") }
            finish.countDown(); running.get(2, TimeUnit.SECONDS)
            access.requireAvailable()
        } finally { finish.countDown(); executor.shutdownNow() }
    }

    @Test fun pairingCompletionRunsAfterLeaseReleaseSoAutomaticConnectCanReserveOwnership() {
        val access = CaptureAccess()
        var paired = false
        var autoConnected = false
        val pending = CaptureAccess.ReservedTask(access.acquire("pairing"),
            afterRun = { access.acquire("automatic connection").use { autoConnected = true } }) { paired = true }
        pending.run()
        assertTrue(paired); assertTrue(autoConnected); access.requireAvailable()
    }

    @Test fun failedSetupStillReleasesOwnership() {
        val access = CaptureAccess()
        val pending = CaptureAccess.ReservedTask(access.acquire("setup")) { throw IllegalStateException("Backend failed") }
        assertThrows(IllegalStateException::class.java) { pending.run() }
        access.requireAvailable()
    }
}
